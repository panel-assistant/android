// Sanitizer smoke harness for the hapaneld-helper parser and handlers.
//
// It links the REAL capability + transport modules (helper/src/*.c, the same set the daemon ships —
// see the Makefile's CORE_SRCS) together with test/sysexec_stub.c, so every byte of the parsing path
// runs under the sanitizers while the only host-effecting calls (system/popen/thread-spawn/reboot,
// all funnelled through sysexec) are neutralised at the LINK layer — no per-call macro stubbing.
//
// It has no semantic oracle and is not independent validation. It can detect sanitizer-visible faults
// in paths reached by its corpus and deterministic pseudo-random byte streams. Peer authentication,
// real host effects, process lifetime, and concurrent connection behaviour are outside this harness.
//
// Build + run:  ./helper/fuzz/run.sh [iterations]
// A clean run prints "SANITIZER SMOKE OK" and exits 0 with no ASan/UBSan/LSan report.

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

#include "cmd.h"
#include "dispatch.h"
#include "server.h"
#include "input.h"
#include "gpio.h"

static int devnull;

// SUBSCRIBE registers an fd in the subscriber registry; reset it between runs (input_init also makes
// the dropped/reused host fds safe across iterations).
static void reset_state(void) { input_init(); gpio_init(); }

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
    if (socketpair(AF_UNIX, SOCK_STREAM, 0, sv) != 0) return;
    write_all(sv[1], data, n);
    shutdown(sv[1], SHUT_WR);
    reset_state();
    server_serve(sv[0], HELPER_CALLER_LEGACY);
    close(sv[0]); close(sv[1]);
}

// Drive dispatch() directly with one NUL-terminated line (the fast path — millions of iterations).
static void run_handle(const uint8_t *data, size_t n) {
    static char line[8192];
    if (n > sizeof line - 1) n = sizeof line - 1;
    memcpy(line, data, n); line[n] = '\0';
    conn_ctx ctx = { .fd = devnull, .subscribed = 0 };
    reset_state();
    dispatch(&ctx, line);
}

int main(int argc, char **argv) {
    long iters = (argc > 1) ? strtol(argv[1], NULL, 10) : 100000;
    signal(SIGPIPE, SIG_IGN);
    devnull = open("/dev/null", O_WRONLY);
    reset_state();

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
        "REBOOT\n", "REBOOTNOW\n",
        "DENSITY\n", "DENSITY 240\n", "DENSITY reset\n", "DENSITY 99999999999999\n",
        "DENSITY ../../x\n", "DENSITY -1\n",
        "FONTSCALE\n", "FONTSCALE 1.15\n", "FONTSCALE reset\n", "FONTSCALE 1.2.3\n",
        "FONTSCALE -1\n", "FONTSCALE 1.0;reboot\n", "FONTSCALE .........\n",
        "GOV schedutil\n", "GOV \n", "GOV PERF;reboot\n", "GOV performance\n",
        "STOP com.eWeLinkControlPanel\n", "STOP com.android.systemui\n", "STOP\n", "STOP ../../x\n",
        "DISABLE com.eWeLinkControlPanel\n", "DISABLE android\n", "DISABLE\n",
        "ENABLE com.eWeLinkControlPanel\n", "ENABLE com.android.settings\n",
        "OVERLAY com.eWeLinkControlPanel deny\n", "OVERLAY com.android.systemui deny\n",
        "OVERLAY com.foo allow\n", "OVERLAY com.foo default\n", "OVERLAY com.foo ignore\n",
        "OVERLAY com.foo foreground\n", "OVERLAY com.foo wipe\n", "OVERLAY\n", "OVERLAY com.foo\n",
        "INSTALL /data/user/0/io.github.maxlyth.hapaneld/files/helper-install-staging/update.apk\n",
        "INSTALLSTREAM 3\n",
        "INSTALLGC /data/user/0/io.github.maxlyth.hapaneld/files/helper-install-staging/update.apk\n",
        "COMPANIONBACKUP io.homeassistant.companion.android.minimal\n",
        "COMPANIONBACKUP com.attacker.fake\n",
        "COMPANIONRESTORE io.homeassistant.companion.android.minimal 3 - -\n",
        "COMPANIONRESTORE io.homeassistant.companion.android.minimal 0 - -\n",
        "COMPANIONRESTORE com.attacker.fake 3 - -\n",
        "INSTALL /data/user/0/io.github.maxlyth.hapaneld/files/../escape.apk\n",
        "INSTALL /data/user/0/io.github.maxlyth.hapaneld/files/update.apk';reboot\n",
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
    const char *verbs[] = { "RGB ", "BTN ", "RELOAD ", "START ", "WATCH /dev/input/", "GPIOWATCH ",
                            "DENSITY ", "FONTSCALE ", "GOV ", "SCREEN ",
                            "STOP ", "DISABLE ", "ENABLE ", "OVERLAY ", "" };
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
