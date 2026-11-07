// Shared sanitizer smoke and libFuzzer driver for the real helper transport/dispatch.
//
// Target filesystem operations stay in a private per-process sandbox; subprocess, thread,
// device and peer operations are refused at the link boundary. Production modules are unchanged.
// A clean run establishes only the exercised paths, not authentication, concurrency or hardware.

#define _GNU_SOURCE
#include <errno.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <fcntl.h>
#include <signal.h>
#include <sys/socket.h>
#include <dirent.h>
#include "guard_maintenance.h"
#include "guard_maintenance_internal.h"
#include "sandbox.h"
#include "sha256.h"
#include <sys/stat.h>

#include "cmd.h"
#include "dispatch.h"
#include "server.h"
#include "input.h"
#include "gpio.h"
#include "sysexec_stub.h"

static int devnull;

// SUBSCRIBE registers an fd in the subscriber registry; reset it between runs (input_init also makes
// the dropped/reused host fds safe across iterations).
static void reset_state(void) {
    fuzz_sandbox_leave();
    guard_test_drop_runtime();
    fuzz_sandbox_reset();
    fuzz_sandbox_enter();
    input_init(); gpio_init();
    sysexec_stub_reset();
    sysexec_stub_add_popen("dumpsys gfxinfo io.panelassistant.android",
        "** Graphics info for pid 42 [io.panelassistant.android] **\nStats since: 1ns\nTotal frames rendered: 2\nJanky frames: 1 (50.00%)\n", 0);
    guard_test_reset();
    guard_test_set_supervised(1);
    guard_test_set_app_autonomous_profile(1);
    guard_test_set_now_ms(1000);
    if (guard_maintenance_init() != 0) {
        fputs("fuzz: private Guard initialization failed\n", stderr); abort();
    }
}

static void initialize(void) {
    static int initialized;
    if (initialized) return;
    initialized = 1;
    signal(SIGPIPE, SIG_IGN);
    devnull = open("/dev/null", O_RDWR);
    if (devnull < 0) abort();
    // HAPANELD_TEST's environment path override must not inherit another fixture's location.
    unsetenv("HAPANELD_GUARD_TEST_PARENT");
    fuzz_sandbox_init();
    fuzz_sandbox_enter();
    // Fail visibly if the target was accidentally built without the required link boundaries.
    if (open("/dev/null", O_RDONLY) != -1 || errno != EACCES ||
        open("/tmp/..", O_RDONLY | O_DIRECTORY) != -1 || errno != EACCES ||
        openat(AT_FDCWD, ".", O_RDONLY | O_DIRECTORY) != -1 || errno != EACCES ||
        kill(getpid(), 0) != -1 || errno != EACCES || fopen("/dev/null", "r") != NULL) {
        fputs("fuzz: unsafe target boundary\n", stderr); abort();
    }
    fuzz_sandbox_leave();
}

static void write_all(int fd, const uint8_t *data, size_t n) {
    while (n > 0) {
        ssize_t written = write(fd, data, n);
        if (written > 0) {
            data += written;
            n -= (size_t)written;
        } else if (written < 0 && errno == EINTR) {
            continue;
        } else {
            break;
        }
    }
}

// Drive server_serve() exactly as a real connection would: write the bytes, then half-close so it
// sees EOF.
static void run_serve(const uint8_t *data, size_t n) {
    int sv[2];
    if (socketpair(AF_UNIX, SOCK_STREAM, 0, sv) != 0) abort();
    // Input fits in one socket buffer; nonblocking output prevents reply floods stalling the target.
    if (fcntl(sv[0], F_SETFL, O_NONBLOCK) || fcntl(sv[1], F_SETFL, O_NONBLOCK)) abort();
    write_all(sv[1], data, n);
    shutdown(sv[1], SHUT_WR);
    reset_state();
    server_serve(sv[0], HELPER_CALLER_SUCCESSOR);
    fuzz_sandbox_leave();
    close(sv[0]); close(sv[1]);
}

// Drive dispatch() directly with one NUL-terminated line (the fast path — millions of iterations).
static void run_handle(const uint8_t *data, size_t n) {
    static char line[8192];
    if (n > sizeof line - 1) n = sizeof line - 1;
    memcpy(line, data, n); line[n] = '\0';
    conn_ctx ctx = { .fd = devnull, .subscribed = 0, .caller = HELPER_CALLER_SUCCESSOR };
    reset_state();
    dispatch(&ctx, line);
    fuzz_sandbox_leave();
}

// Persisted-record mode. An input starting with NUL (which the line protocol would otherwise
// discard as an empty line) carries Guard records: repeated [selector][length:2 BE][body], ended
// by selector 0xFF, then command lines. The harness appends the production checksum trailer so
// mutation reaches the record deserializers and the state handlers behind them instead of dying
// at checksum verification. Records go only into the private sandbox Guard directory.
static const char *const record_names[] = {
    GUARD_DRAFT, GUARD_MANIFEST, GUARD_JOURNAL, GUARD_CAPTURE, GUARD_REPLACEMENT,
    GUARD_DRAFT_TMP, GUARD_MANIFEST_TMP, GUARD_JOURNAL_TMP, GUARD_CAPTURE_TMP, GUARD_REPLACEMENT_TMP,
};
#define RECORD_COUNT (sizeof record_names / sizeof record_names[0])

static void write_record(const char *name, const uint8_t *body, size_t n) {
    char path[4096];
    if (snprintf(path, sizeof path, "%s/" GUARD_DIR_NAME, fuzz_sandbox_root()) >= (int)sizeof path) abort();
    (void)mkdir(path, 0700);
    if (snprintf(path, sizeof path, "%s/" GUARD_DIR_NAME "/%s", fuzz_sandbox_root(), name) >= (int)sizeof path) abort();
    hapaneld_sha256 hash; unsigned char digest[32]; char hex[65];
    hapaneld_sha256_init(&hash); hapaneld_sha256_update(&hash, body, n);
    hapaneld_sha256_final(&hash, digest); hapaneld_sha256_hex(digest, hex);
    int fd = open(path, O_WRONLY | O_CREAT | O_TRUNC | O_NOFOLLOW | O_CLOEXEC, 0600);
    if (fd < 0 || fchmod(fd, 0600) != 0) abort();
    write_all(fd, body, n);
    char trailer[80];
    int t = snprintf(trailer, sizeof trailer, "SHA256 %s\n", hex);
    write_all(fd, (const uint8_t *)trailer, (size_t)t);
    close(fd);
}

static void run_records(const uint8_t *data, size_t size) {
    reset_state();
    fuzz_sandbox_leave();
    size_t at = 1;
    while (at < size && data[at] != 0xFF) {
        if (size - at < 3) return;
        size_t n = ((size_t)data[at + 1] << 8) | data[at + 2];
        const char *name = record_names[data[at] % RECORD_COUNT];
        at += 3;
        if (n > size - at) n = size - at;
        write_record(name, data + at, n);
        at += n;
    }
    if (at < size) at++;
    // Startup reconciliation reads the records exactly as a restarted daemon would.
    guard_test_drop_runtime();
    fuzz_sandbox_enter();
    (void)guard_maintenance_init();
    fuzz_sandbox_leave();
    static const char status[] = "GUARDSTATUS\nGUARDCAPS\nGUARDSELF\n";
    const uint8_t *commands = at < size ? data + at : (const uint8_t *)status;
    size_t count = at < size ? size - at : sizeof status - 1;
    int sv[2];
    if (socketpair(AF_UNIX, SOCK_STREAM, 0, sv) != 0) abort();
    if (fcntl(sv[0], F_SETFL, O_NONBLOCK) || fcntl(sv[1], F_SETFL, O_NONBLOCK)) abort();
    write_all(sv[1], commands, count);
    shutdown(sv[1], SHUT_WR);
    fuzz_sandbox_enter();
    server_serve(sv[0], HELPER_CALLER_SUCCESSOR);
    fuzz_sandbox_leave();
    close(sv[0]); close(sv[1]);
}

// Emits record-mode seeds from records the real handlers wrote, so the corpus starts from valid
// serializations rather than a second hand-written copy of the record grammar.
static void emit_record_seed(const char *dir, const char *label, const char *session, const char *tail) {
    reset_state();
    int sv[2];
    if (socketpair(AF_UNIX, SOCK_STREAM, 0, sv) != 0) abort();
    if (fcntl(sv[0], F_SETFL, O_NONBLOCK) || fcntl(sv[1], F_SETFL, O_NONBLOCK)) abort();
    fuzz_sandbox_leave();
    write_all(sv[1], (const uint8_t *)session, strlen(session));
    shutdown(sv[1], SHUT_WR);
    fuzz_sandbox_enter();
    server_serve(sv[0], HELPER_CALLER_SUCCESSOR);
    fuzz_sandbox_leave();
    close(sv[0]); close(sv[1]);
    static uint8_t seed[65536]; size_t used = 0;
    seed[used++] = 0;
    for (size_t i = 0; i < RECORD_COUNT; i++) {
        char path[4096];
        snprintf(path, sizeof path, "%s/" GUARD_DIR_NAME "/%s", fuzz_sandbox_root(), record_names[i]);
        FILE *in = fopen(path, "rb");
        if (!in) continue;
        static uint8_t body[GUARD_MAX_RECORD_BYTES];
        size_t n = fread(body, 1, sizeof body, in); fclose(in);
        // Drop the checksum trailer: record mode recomputes it.
        if (n < 72 || memcmp(body + n - 72, "SHA256 ", 7) != 0) abort();
        n -= 72;
        if (used + 3 + n >= sizeof seed) abort();
        seed[used++] = (uint8_t)i; seed[used++] = (uint8_t)(n >> 8); seed[used++] = (uint8_t)n;
        memcpy(seed + used, body, n); used += n;
    }
    seed[used++] = 0xFF;
    size_t tl = strlen(tail);
    if (used + tl > sizeof seed) abort();
    memcpy(seed + used, tail, tl); used += tl;
    char out[4096];
    if (snprintf(out, sizeof out, "%s/record-%s", dir, label) >= (int)sizeof out) abort();
    FILE *f = fopen(out, "wb");
    if (!f || fwrite(seed, 1, used, f) != used) abort();
    fclose(f);
}

static void emit_record_seeds(const char *dir) {
    char path[4096];
    struct { const char *label, *file; } sessions[] = {
        { "prepare", "guard-prepare-cancel" },
        { "define", "guard-prepare-define" },
        { "stream", "guard-prepare-stream" },
    };
    static const char tail[] = "GUARDSTATUS\nGUARDCAPS\n";
    for (size_t i = 0; i < sizeof sessions / sizeof sessions[0]; i++) {
        snprintf(path, sizeof path, "%s/%s", dir, sessions[i].file);
        FILE *f = fopen(path, "rb");
        if (!f) abort();
        static char session[65536]; size_t n = fread(session, 1, sizeof session - 1, f); fclose(f);
        session[n] = 0;
        // Run the session without its trailing cancel/status so its records survive.
        char *cut = strstr(session, "GUARDCANCEL");
        if (cut) *cut = 0;
        emit_record_seed(dir, sessions[i].label, session, tail);
    }
}

int LLVMFuzzerInitialize(int *argc, char ***argv) {
    (void)argc; (void)argv;
    initialize();
    const char *emit = getenv("HAPANELD_FUZZ_EMIT_RECORDS");
    if (emit && *emit) emit_record_seeds(emit);
    return 0;
}

int LLVMFuzzerTestOneInput(const uint8_t *data, size_t size) {
    initialize();
    if (size > 0 && data[0] == 0) { run_records(data, size); return 0; }
    run_handle(data, size);
    run_serve(data, size);
    return 0;
}

#ifndef HAPANELD_LIBFUZZER
int main(int argc, char **argv) {
    long iters = (argc > 1) ? strtol(argv[1], NULL, 10) : 100000;
    LLVMFuzzerInitialize(&argc, &argv);
    if (argc > 2) {
        DIR *dir = opendir(argv[2]);
        if (!dir) abort();
        struct dirent *entry;
        while ((entry = readdir(dir))) {
            if (entry->d_name[0] == '.') continue;
            char path[4096];
            if (snprintf(path, sizeof path, "%s/%s", argv[2], entry->d_name) >= (int)sizeof path) abort();
            FILE *seed = fopen(path, "rb");
            if (!seed) abort();
            uint8_t bytes[65536]; size_t used = fread(bytes, 1, sizeof bytes, seed);
            fclose(seed); LLVMFuzzerTestOneInput(bytes, used);
        }
        closedir(dir);
    }
    // The real server and dispatch must reject caller paths before reaching the wrapped publisher.
    const char *invalid_ensure = "ADBRECOVERYENSURE /tmp/helper\n";
    int ensure_calls = 0;
    run_serve((const uint8_t *)invalid_ensure, strlen(invalid_ensure));
    run_handle((const uint8_t *)invalid_ensure, strlen(invalid_ensure));
    if (sysexec_stub_count_ensure() != ensure_calls) {
        fputs("FAIL: ENSURE arguments reached the recovery publisher\n", stderr); return 1;
    }
    // 1) hand-crafted adversarial corpus (each fed to BOTH server_serve() and dispatch()).
    const char *corpus[] = {
        "", "\n", "\r", "\r\n", "\n\n\n\n", " ", "   \n   \n",
        "PING\n", "PING", "ping\n", "  PING  \n",
        "RGB 1 2 3\n", "RGB\n", "RGB   \n", "RGB 0 0 0\n",
        "RGB -1 -2 -3\n", "RGB 256 999 1000\n",
        "RGB 99999999999999999999 -99999999999999999999 2147483648\n",
        "RGB 2147483647 2147483647 2147483647\n",
        "OFF\n", "OFFOFFOFF\n", "OF\n",
        "BTN 5\n", "BTN -99999999999\n", "BTN 99999999999999999999\n", "BTN\n",
        "SCREEN ON\n", "SCREEN OFF\n", "SCREEN off\n", "SCREEN\n", "SCREEN xyzzyqqq\n",
        "BLPOWER\n", "BLREAD\n", "BLSET 127\n", "BLSET -1\n", "BLSET 99999999999999999999\n",
        "SCREENCAP\n", "SCREENCAPEXTRA\n",
        "RELOAD com.foo.bar\n", "RELOAD \n", "RELOAD ;reboot\n", "RELOAD a|b`c$d(e)\n",
        "RELOAD ../../etc/passwd\n", "RELOAD \"$(rm -rf)\"\n",
        "START com.foo/.Bar\n", "START a/b/c/../d\n", "START ;reboot\n", "START \n",
        "SETHOME com.foo/.Home\n", "SETHOME ;reboot\n",
        "APPSTATE com.foo\n", "APPSTATE ;reboot\n",
        "WATCH /dev/input/event0 1\n", "WATCH /dev/input/event0 0\n",
        "WATCH /etc/passwd 1\n", "WATCH ../../dev/input/x 1\n",
        "WATCH /dev/input/event0 99999999999\n", "WATCH\n", "WATCH /dev/input/\n",
        "SUBSCRIBE\n", "SUBSCRIBE\nRGB 1 2 3\n",
        "GPIOV1\n", "GPIOV1 extra\n", "GPIORESET\n",
        "GPIOWATCH 0\n", "GPIOWATCH 65535\n", "GPIOWATCH 65536\n",
        "GPIOWATCH -1\n", "GPIOWATCH 1 trailing\n", "GPIOSUBSCRIBE\n",
        "LOGCATDUMP\n", "LOGCATDUMP 300\n", "LOGCATDUMP 0\n", "LOGCATDUMP 1001\n", "LOGCATDUMP abc\n",
        "LOGCATDUMP 300 x\n", "LOGCATDUMP -1\n", "LOGCATDUMP 99999999999999999999\n",
        "REBOOT\n", "REBOOTNOW\n",
        "DENSITY\n", "DENSITY 240\n", "DENSITY reset\n", "DENSITY 99999999999999\n",
        "DENSITY ../../x\n", "DENSITY -1\n",
        "FONTSCALE\n", "FONTSCALE 1.15\n", "FONTSCALE reset\n", "FONTSCALE 1.2.3\n",
        "FONTSCALE -1\n", "FONTSCALE 1.0;reboot\n", "FONTSCALE .........\n",
        "GOVREAD CURRENT\n", "GOVREAD AVAILABLE\n", "GOVREAD\n", "GOVREAD current\n",
        "GOVREAD CURRENT extra\n", "GOVREAD /proc/self/status\n", "GOVREAD CURRENT;reboot\n",
        "RELAYREAD RELAY COUNT\n", "RELAYREAD ST_RELAY 1\n", "RELAYREAD STRELAY 16\n",
        "RELAYREAD RELAY 0\n", "RELAYREAD /sys/class/relay COUNT\n", "RELAYREAD RELAY 01\n",
        "GPIOREAD 0\n", "GPIOREAD 65535\n", "GPIOREAD 65536\n", "GPIOREAD 01\n", "GPIOREAD 1 extra\n",
        "ADBRECOVERYENSURE\n", "ADBRECOVERYENSURE extra\n", "GFXINFO SELF\n", "GFXINFO\n", "GFXINFO io.panelassistant.android\n", "GFXINFO SELF reset\n",
        "CDPSTATE\n", "CDPSTATE x\n", "CDPSTATE /proc\n", "CDPSTATE ;reboot\n",
        "ADBSTATE\n", "ADBSTATE x\n", "ADBSTATE /proc/net/tcp\n", "ADBSTATE ;reboot\n",
        "WIFISTATUS\n", "WIFISTATUS x\n", "WIFISTATUS ;reboot\n",
        "ZIGBEESTATE\n", "ZIGBEEHEALTH\n", "ZIGBEEROLE\n",
        "ZIGBEESTATE x\n", "ZIGBEEHEALTH /proc\n", "ZIGBEEROLE ;reboot\n",
        "GOV schedutil\n", "GOV \n", "GOV PERF;reboot\n", "GOV performance\n",
        "STOP com.eWeLinkControlPanel\n", "STOP com.android.systemui\n", "STOP\n", "STOP ../../x\n",
        "DISABLE com.eWeLinkControlPanel\n", "DISABLE android\n", "DISABLE\n",
        "ENABLE com.eWeLinkControlPanel\n", "ENABLE com.android.settings\n",
        "OVERLAY com.eWeLinkControlPanel deny\n", "OVERLAY com.android.systemui deny\n",
        "OVERLAY com.foo allow\n", "OVERLAY com.foo default\n", "OVERLAY com.foo ignore\n",
        "OVERLAY com.foo foreground\n", "OVERLAY com.foo wipe\n", "OVERLAY\n", "OVERLAY com.foo\n",
        "INSTALL /data/user/0/io.panelassistant.android/files/helper-install-staging/update.apk\n",
        "INSTALLSTREAM 3\n",
        "INSTALLGC /data/user/0/io.panelassistant.android/files/helper-install-staging/update.apk\n",
        "COMPANIONBACKUP io.homeassistant.companion.android.minimal\n",
        "COMPANIONBACKUP com.attacker.fake\n",
        "COMPANIONRESTORE io.homeassistant.companion.android.minimal 3 - -\n",
        "COMPANIONRESTORE io.homeassistant.companion.android.minimal 0 - -\n",
        "COMPANIONRESTORE com.attacker.fake 3 - -\n",
        "INSTALL /data/user/0/io.panelassistant.android/files/../escape.apk\n",
        "INSTALL /data/user/0/io.panelassistant.android/files/update.apk';reboot\n",
        "PERFDUMP\n", "LEDPROBE\n", "CHT8305\n",
        "RGB 1 2 3\r\nOFF\r\nBTN 9\r\n",
        "no newline at all just bytes",
    };
    size_t ncorp = sizeof corpus / sizeof corpus[0];
    for (size_t i = 0; i < ncorp; i++) {
        size_t L = strlen(corpus[i]);
        run_serve((const uint8_t *)corpus[i], L);
        run_handle((const uint8_t *)corpus[i], L);
    }
    static const uint8_t embedded_nul[] = { 'R','G','B',' ','1',' ','2',' ','3',0,'O','F','F','\n' };
    static const uint8_t binary_bytes[] = { 0xff,0xfe,0x00,0x01,0x02,' ','x',0x80,0x90,'\n' };
    run_serve(embedded_nul, sizeof embedded_nul);
    run_handle(embedded_nul, sizeof embedded_nul);
    run_serve(binary_bytes, sizeof binary_bytes);
    run_handle(binary_bytes, sizeof binary_bytes);

    // 2) length-boundary lines around MAX_LINE for every verb.
    size_t lens[] = { MAX_LINE - 1, MAX_LINE, MAX_LINE + 1, 4096, 65536 };
    const char *verbs[] = {
#define COMMAND(verb, handler) #verb " ",
#include "commands.def"
#undef COMMAND
    };
    for (size_t li = 0; li < sizeof lens / sizeof lens[0]; li++)
        for (size_t vi = 0; vi < sizeof verbs / sizeof verbs[0]; vi++) {
            size_t L = lens[li], vp = strlen(verbs[vi]);
            uint8_t *b = malloc(L + 2);
            memcpy(b, verbs[vi], vp);
            memset(b + vp, 'A', L - vp);
            b[L] = '\n'; b[L + 1] = '\0';
            run_serve(b, L + 1);
            run_handle(b, L);
            free(b);
        }

    // 3) random fuzzing: serve() (multi-line) and handle() (single line), fixed seed = reproducible.
    srand(0xC0FFEE);
    uint8_t buf[3000];
    const char *kw[] = {
#define COMMAND(verb, handler) #verb,
#include "commands.def"
#undef COMMAND
    };
    for (long it = 0; it < iters; it++) {
        size_t n = rand() % sizeof buf;
        for (size_t k = 0; k < n; k++) {
            int r = rand() % 100;
            if (r < 55)      buf[k] = (uint8_t)(rand() & 0xff);      // raw bytes
            else if (r < 75) buf[k] = " 0123456789"[rand() % 11];   // digits / spaces
            else if (r < 90) buf[k] = "\n\r /._:-"[rand() % 8];      // delimiters / path chars
            else             buf[k] = ";|`$()&<>*?\"'\\"[rand() % 14]; // shell metachars
        }
        if (n > 8 && (rand() % 3) == 0) {                            // bias toward real verbs
            const char *w = kw[rand() % (sizeof kw / sizeof kw[0])];
            size_t wl = strlen(w);
            memcpy(buf, w, wl);
            if (wl < n) buf[wl] = ' ';
        }
        if (it & 1) run_serve(buf, n);
        else        run_handle(buf, n);
    }

    return 0;
}

#endif
