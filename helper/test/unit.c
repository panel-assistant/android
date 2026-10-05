// Host-native unit tests for the hapaneld-helper daemon's pure logic. The sanitizer smoke exercises
// hostile parser inputs; these assert it produces the CORRECT result — the validators reject what
// they should, the byte clamp and /proc parser compute the right values, dispatch routes verbs by
// EXACT match (so SCREEN/SCREENCAP and OFF/OFFOFF can't collide), and the line accumulator reassembles
// split reads and drops overlong lines. Linked against the real src/*.c modules + the sysexec stub.
//
// A clean run prints "UNIT OK" and exits 0; any failed assertion prints the case and exits 1.
#define _GNU_SOURCE
#include <errno.h>
#include <fcntl.h>
#include <linux/input.h>
#include <poll.h>
#include <pthread.h>
#include <sched.h>
#include <signal.h>
#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/time.h>
#include <sys/wait.h>
#include <unistd.h>

#include "cmd.h"
#include "cht8305.h"
#include <stddef.h>
#include <stdint.h>
#include <sys/ioctl.h>
#include "vi530x.h"
#include "dispatch.h"
#include "server.h"
#include "sysctl.h"
#include "input.h"
#include "gpio.h"
#include "guard_maintenance.h"
#include "led.h"
#include "perf.h"
#include "sysexec_stub.h"
#include "util.h"
#include "version.h"

static int failures = 0;
#define CHECK(cond, ...) do { if (!(cond)) { \
    printf("FAIL: " __VA_ARGS__); printf("  (%s:%d)\n", __FILE__, __LINE__); failures++; } } while (0)

static void test_guard_test_parent(void) {
    char parent[] = "/tmp/hapaneld-guard-unit-XXXXXX";
    if (!mkdtemp(parent)) { CHECK(0, "Guard test parent can be created\n"); return; }
    CHECK(setenv("HAPANELD_GUARD_TEST_PARENT", parent, 1) == 0,
        "Guard test parent can be selected\n");
    CHECK(guard_maintenance_init() == 0, "Guard initializes under the selected test parent\n");
    char custody[sizeof parent + sizeof "/.hapaneld-guard-db-test"];
    snprintf(custody, sizeof custody, "%s/.hapaneld-guard-db-test", parent);
    struct stat st;
    CHECK(stat(custody, &st) == 0 && S_ISDIR(st.st_mode),
        "Guard custody uses the selected test parent\n");
    guard_test_reset();
    (void)unsetenv("HAPANELD_GUARD_TEST_PARENT");
    char lock[sizeof custody + sizeof "/.owner.lock"];
    snprintf(lock, sizeof lock, "%s/.owner.lock", custody);
    (void)unlink(lock);
    (void)rmdir(custody);
    (void)rmdir(parent);
}

// --- ioctl interception (linked with -Wl,--wrap=ioctl) -------------------------------------------
// Captures the ledjni per-channel ioctls so we can assert the command numbers + scaled values without
// a real /dev/ledjni (the SMT1019 isn't hardware we own). Returns 0 = success so the handler proceeds.
static unsigned long cap_cmd[8];
static int cap_val[8], cap_n;
static int evdev_open_ok = 1;
static int evdev_grab_ok = 1;
static int evdev_open_count;
static int evdev_grab_acquire_count;
static int evdev_grab_release_count;
static int gpio_value_open_ok = 1;
static int gpio_edge_open_ok = 1;
static int gpio_value_open_count;
static int gpio_edge_open_count;
static char gpio_value_fixture[128];
static char gpio_edge_fixture[128];
int __real_open(const char *path, int flags, ...);
ssize_t __real_write(int fd, const void *buf, size_t count);

enum write_step {
    WRITE_STEP_ALL = -1,
    WRITE_STEP_EINTR = -2,
    WRITE_STEP_ZERO = -3,
    WRITE_STEP_ERROR = -4,
    WRITE_STEP_STALLED = -5,
};

static int wrapped_write_fd = -1;
static int wrapped_write_steps[8];
static size_t wrapped_write_step_count;
static size_t wrapped_write_call_count;
static char wrapped_write_bytes[16384];
static size_t wrapped_write_size;

static void wrapped_write_reset(int fd, const int *steps, size_t count) {
    wrapped_write_fd = fd;
    wrapped_write_call_count = 0;
    wrapped_write_size = 0;
    memset(wrapped_write_steps, 0, sizeof wrapped_write_steps);
    if (count > sizeof wrapped_write_steps / sizeof wrapped_write_steps[0])
        count = sizeof wrapped_write_steps / sizeof wrapped_write_steps[0];
    wrapped_write_step_count = count;
    memcpy(wrapped_write_steps, steps, count * sizeof steps[0]);
}

static void wrapped_write_disable(void) {
    wrapped_write_fd = -1;
}

ssize_t __wrap_write(int fd, const void *buf, size_t count) {
    if (fd != wrapped_write_fd) return __real_write(fd, buf, count);

    size_t call = wrapped_write_call_count++;
    int step = call < wrapped_write_step_count ? wrapped_write_steps[call] : WRITE_STEP_ALL;
    if (step == WRITE_STEP_EINTR) {
        errno = EINTR;
        return -1;
    }
    if (step == WRITE_STEP_ZERO) return 0;
    if (step == WRITE_STEP_ERROR) {
        errno = EPIPE;
        return -1;
    }
    if (step == WRITE_STEP_STALLED) {
        errno = EAGAIN;
        return -1;
    }

    size_t accepted = step == WRITE_STEP_ALL ? count : (size_t)step;
    if (accepted > count) accepted = count;
    if (accepted > sizeof wrapped_write_bytes - wrapped_write_size)
        accepted = sizeof wrapped_write_bytes - wrapped_write_size;
    memcpy(wrapped_write_bytes + wrapped_write_size, buf, accepted);
    wrapped_write_size += accepted;
    return (ssize_t)accepted;
}

int __wrap_open(const char *path, int flags, ...) {
    const char *selected = path;
    if (strncmp(path, "/dev/input/event", 16) == 0) {
        evdev_open_count++;
        if (!evdev_open_ok) { errno = ENOENT; return -1; }
        selected = "/dev/null";
    } else if (strncmp(path, "/sys/class/gpio/gpio", 20) == 0) {
        size_t path_len = strlen(path);
        if (path_len >= 6 && strcmp(path + path_len - 6, "/value") == 0) {
            gpio_value_open_count++;
            if (!gpio_value_open_ok || gpio_value_fixture[0] == '\0') {
                errno = ENOENT;
                return -1;
            }
            selected = gpio_value_fixture;
        } else if (path_len >= 5 && strcmp(path + path_len - 5, "/edge") == 0) {
            gpio_edge_open_count++;
            if (!gpio_edge_open_ok || gpio_edge_fixture[0] == '\0') {
                errno = ENOENT;
                return -1;
            }
            selected = gpio_edge_fixture;
        }
    }
    int needs_mode = (flags & O_CREAT) != 0;
#ifdef O_TMPFILE
    needs_mode = needs_mode || (flags & O_TMPFILE) == O_TMPFILE;
#endif
    if (!needs_mode) return __real_open(selected, flags);
    va_list ap;
    va_start(ap, flags);
    mode_t mode = (mode_t)va_arg(ap, int);
    va_end(ap);
    return __real_open(selected, flags, mode);
}
int __wrap_ioctl(int fd, unsigned long req, ...) {
    va_list ap; va_start(ap, req);
    if (req == EVIOCGRAB) {
        (void)fd; long active = (long)va_arg(ap, void *); va_end(ap);
        if (!evdev_grab_ok) { errno = EBUSY; return -1; }
        if (active) evdev_grab_acquire_count++; else evdev_grab_release_count++;
        return 0;
    }
    int arg = va_arg(ap, int); va_end(ap);
    if (cap_n < 8) { cap_cmd[cap_n] = req; cap_val[cap_n] = arg; cap_n++; }
    return 0;
}

// --- helpers: capture what a handler / the server writes back ------------------------------------
// Run one line through dispatch() and return its reply bytes (NUL-terminated, into `out`).
// A connection whose caller is HELPER_CALLER_NONE cannot exist in production — the accept loop closes
// an unauthenticated peer before any command — so the default here is a real authenticated caller.
static void dispatch_reply_as(enum helper_caller caller, const char *line, char *out, size_t outsz) {
    int sv[2];
    socketpair(AF_UNIX, SOCK_STREAM, 0, sv);
    char tmp[MAX_LINE + 1];
    snprintf(tmp, sizeof tmp, "%s", line);
    conn_ctx ctx = { .fd = sv[0], .subscribed = 0, .caller = caller };
    input_init();
    gpio_init();
    dispatch(&ctx, tmp);
    fcntl(sv[1], F_SETFL, O_NONBLOCK);
    ssize_t n = read(sv[1], out, outsz - 1);
    out[n > 0 ? n : 0] = '\0';
    close(sv[0]); close(sv[1]);
}

static void dispatch_reply(const char *line, char *out, size_t outsz) {
    dispatch_reply_as(HELPER_CALLER_LEGACY, line, out, outsz);
}

typedef struct {
    int fd;
    const char *line;
} stream_dispatch_job;

static void *stream_dispatch_worker(void *arg) {
    stream_dispatch_job *job = arg;
    char line[MAX_LINE + 1];
    snprintf(line, sizeof line, "%s", job->line);
    conn_ctx ctx = { .fd = job->fd, .subscribed = 0 };
    dispatch(&ctx, line);
    close(job->fd);
    return NULL;
}

static ssize_t read_reply_line(int fd, char *out, size_t outsz) {
    size_t used = 0;
    while (used + 1 < outsz) {
        ssize_t n = read(fd, out + used, 1);
        if (n < 0 && errno == EINTR) continue;
        if (n <= 0) break;
        if (out[used++] == '\n') break;
    }
    out[used] = '\0';
    return (ssize_t)used;
}

static int write_all_fd(int fd, const void *bytes, size_t size) {
    const char *p = bytes;
    while (size > 0) {
        ssize_t n = write(fd, p, size);
        if (n < 0 && errno == EINTR) continue;
        if (n <= 0) return -1;
        p += n;
        size -= (size_t)n;
    }
    return 0;
}

// Feed a raw byte stream to server_serve() (half-closed) and return all reply bytes.
static void serve_reply(const char *bytes, size_t len, char *out, size_t outsz) {
    int sv[2];
    socketpair(AF_UNIX, SOCK_STREAM, 0, sv);
    (void)!write(sv[1], bytes, len);
    shutdown(sv[1], SHUT_WR);
    input_init();
    gpio_init();
    server_serve(sv[0], HELPER_CALLER_LEGACY);
    fcntl(sv[1], F_SETFL, O_NONBLOCK);
    ssize_t n = read(sv[1], out, outsz - 1);
    out[n > 0 ? n : 0] = '\0';
    close(sv[0]); close(sv[1]);
}

static void test_validators(void) {
    // valid_pkg: package chars only; rejects shell metacharacters and traversal.
    CHECK(valid_pkg("com.foo.bar"), "valid_pkg accepts a package\n");
    CHECK(valid_pkg("io.github.maxlyth.hapaneld"), "valid_pkg accepts the app id\n");
    CHECK(!valid_pkg(""), "valid_pkg rejects empty\n");
    CHECK(!valid_pkg(";reboot"), "valid_pkg rejects ;reboot\n");
    CHECK(!valid_pkg("a|b`c$d(e)"), "valid_pkg rejects metachars\n");
    CHECK(!valid_pkg("../../etc/passwd"), "valid_pkg rejects traversal (slash)\n");
    CHECK(!valid_pkg("com.foo bar"), "valid_pkg rejects space\n");

    // valid_component: like valid_pkg but '/' is allowed (pkg/class).
    CHECK(valid_component("com.foo/.Bar"), "valid_component accepts pkg/class\n");
    CHECK(!valid_component("com.foo/.Bar;rm"), "valid_component rejects ';'\n");
    CHECK(!valid_component(""), "valid_component rejects empty\n");

    // valid_num: decimal digits only.
    CHECK(valid_num("240"), "valid_num accepts digits\n");
    CHECK(!valid_num(""), "valid_num rejects empty\n");
    CHECK(!valid_num("-1"), "valid_num rejects sign\n");
    CHECK(!valid_num("12x"), "valid_num rejects trailing junk\n");

    // valid_decimal: digits with at most one dot (font-scale arg).
    CHECK(valid_decimal("1.15"), "valid_decimal accepts 1.15\n");
    CHECK(valid_decimal("1"), "valid_decimal accepts a bare integer\n");
    CHECK(valid_decimal(".5"), "valid_decimal accepts a leading dot\n");
    CHECK(!valid_decimal(""), "valid_decimal rejects empty\n");
    CHECK(!valid_decimal("."), "valid_decimal rejects a lone dot\n");
    CHECK(!valid_decimal("1.2.3"), "valid_decimal rejects two dots\n");
    CHECK(!valid_decimal("-1.0"), "valid_decimal rejects sign\n");
    CHECK(!valid_decimal("1.0;reboot"), "valid_decimal rejects trailing junk\n");

    // is_critical_pkg: the never-stop/disable backstop.
    CHECK(is_critical_pkg("android"), "is_critical_pkg flags the framework\n");
    CHECK(is_critical_pkg("com.android.systemui"), "is_critical_pkg flags systemui\n");
    CHECK(is_critical_pkg("io.github.maxlyth.hapaneld"), "is_critical_pkg flags ourselves\n");
    // Both ha-paneld ids are critical for the whole transition window: with the successor installed
    // beside the legacy package, tearing either one down strands the migration.
    CHECK(is_critical_pkg("io.panelassistant.android"), "is_critical_pkg flags the successor package\n");
    CHECK(!is_critical_pkg("io.panelassistant.androidx"),
          "is_critical_pkg is exact for the successor id, not a prefix\n");
    CHECK(!is_critical_pkg("com.eWeLinkControlPanel"), "is_critical_pkg allows a vendor app\n");
    CHECK(!is_critical_pkg("com.android.systemui.x"), "is_critical_pkg is exact, not prefix\n");
    CHECK(!is_critical_pkg(""), "is_critical_pkg allows empty (valid_pkg rejects it first)\n");

    // valid_gov: lowercase-alnum + underscore.
    CHECK(valid_gov("schedutil"), "valid_gov accepts schedutil\n");
    CHECK(valid_gov("performance"), "valid_gov accepts performance\n");
    CHECK(!valid_gov("PERF;reboot"), "valid_gov rejects uppercase + ';'\n");
    CHECK(!valid_gov(""), "valid_gov rejects empty\n");

    // valid_hex_dataset: even-length hex, max 508 chars (254 bytes), hex digits only.
    CHECK(valid_hex_dataset("0e080000000000010000"), "valid_hex_dataset accepts even hex\n");
    CHECK(valid_hex_dataset("aAbBcCdDeEfF0123456789"), "valid_hex_dataset accepts mixed case\n");
    CHECK(!valid_hex_dataset(""), "valid_hex_dataset rejects empty\n");
    CHECK(!valid_hex_dataset("abc"), "valid_hex_dataset rejects odd length\n");
    CHECK(!valid_hex_dataset("gg"), "valid_hex_dataset rejects non-hex char\n");
    CHECK(!valid_hex_dataset("0e 0f"), "valid_hex_dataset rejects whitespace\n");
    // 510-char string (255 bytes) exceeds the 508-char limit
    { char big[511]; memset(big, '0', 510); big[510] = '\0';
      CHECK(!valid_hex_dataset(big), "valid_hex_dataset rejects >508 chars\n"); }

    // valid_gbl_path: absolute, no '..', no single-quote, ends in ".gbl".
    CHECK(valid_gbl_path("/data/local/tmp/efr32.gbl"), "valid_gbl_path accepts a normal path\n");
    CHECK(valid_gbl_path("/data/user/0/io.github.maxlyth.hapaneld/cache/efr32.gbl"), "valid_gbl_path accepts app cache path\n");
    CHECK(!valid_gbl_path(""), "valid_gbl_path rejects empty\n");
    CHECK(!valid_gbl_path("relative/path.gbl"), "valid_gbl_path rejects non-absolute\n");
    CHECK(!valid_gbl_path("/data/../etc/passwd.gbl"), "valid_gbl_path rejects traversal\n");
    CHECK(!valid_gbl_path("/data/local/tmp/efr32.bin"), "valid_gbl_path rejects wrong extension\n");
    CHECK(!valid_gbl_path("/data/local/tmp/it'squoted.gbl"), "valid_gbl_path rejects single-quote\n");

    // valid_apk_path: an .apk under EITHER known package's own data dir, in either the legacy
    // (/data/data) or multi-user (/data/user/0) form. During the migration both apps are installed,
    // and each may hold a retained input of its own.
    CHECK(valid_apk_path("/data/data/io.github.maxlyth.hapaneld/cache/update.apk"),
          "valid_apk_path accepts the legacy package's /data/data form\n");
    CHECK(valid_apk_path("/data/user/0/io.github.maxlyth.hapaneld/cache/update.apk"),
          "valid_apk_path accepts the legacy package's /data/user/0 form\n");
    CHECK(valid_apk_path("/data/data/io.panelassistant.android/cache/update.apk"),
          "valid_apk_path accepts the successor package's /data/data form\n");
    CHECK(valid_apk_path("/data/user/0/io.panelassistant.android/cache/update.apk"),
          "valid_apk_path accepts the successor package's /data/user/0 form\n");
    // The separator after the directory is load-bearing: without it a sibling directory whose name
    // merely starts with a known id would pass as that package's private storage.
    CHECK(!valid_apk_path("/data/data/io.panelassistant.androidx/cache/update.apk"),
          "valid_apk_path rejects a directory that only prefixes the successor id\n");
    CHECK(!valid_apk_path("/data/data/io.github.maxlyth.hapaneldx/cache/update.apk"),
          "valid_apk_path rejects a directory that only prefixes the legacy id\n");
    CHECK(!valid_apk_path("/data/data/com.other.app/cache/update.apk"),
          "valid_apk_path rejects a third package's data dir\n");
    CHECK(!valid_apk_path("/data/local/tmp/update.apk"), "valid_apk_path rejects shared storage\n");
    CHECK(!valid_apk_path("/data/data/io.panelassistant.android/../../local/tmp/update.apk"),
          "valid_apk_path rejects traversal out of the successor's data dir\n");
    CHECK(!valid_apk_path("/data/data/io.panelassistant.android/cache/update.txt"),
          "valid_apk_path rejects a non-apk extension\n");
    CHECK(!valid_apk_path("/data/data/io.panelassistant.android/cache/it'squoted.apk"),
          "valid_apk_path rejects single-quote\n");
    CHECK(!valid_gbl_path("/data/local/tmp/"), "valid_gbl_path rejects path ending in slash\n");
}

static void test_clamp(void) {
    CHECK(clamp(-5) == 0, "clamp(-5)==0\n");
    CHECK(clamp(0) == 0, "clamp(0)==0\n");
    CHECK(clamp(128) == 128, "clamp(128)==128\n");
    CHECK(clamp(255) == 255, "clamp(255)==255\n");
    CHECK(clamp(300) == 255, "clamp(300)==255\n");
}

static void test_stat_jiffies(void) {
    char comm[64];
    // utime=idx11, stime=idx12 of the fields after ')'. comm contains a space (tests paren handling).
    const char *s = "1 (proc name) R 0 0 0 0 0 0 0 0 0 0 100 200 99 0 0\n";
    long j = stat_jiffies(s, comm, sizeof comm);
    CHECK(j == 300, "stat_jiffies sums utime+stime (got %ld, want 300)\n", j);
    CHECK(strcmp(comm, "proc name") == 0, "stat_jiffies extracts comm (got '%s')\n", comm);

    // comm with embedded ')' — strrchr finds the LAST ')', so the real end is used.
    const char *s2 = "7 (weird) name) S 0 0 0 0 0 0 0 0 0 0 5 6 0\n";
    CHECK(stat_jiffies(s2, NULL, 0) == 11, "stat_jiffies handles ')' in comm\n");

    // malformed: no parens -> -1.
    CHECK(stat_jiffies("garbage with no parens", NULL, 0) == -1, "stat_jiffies rejects no-paren\n");

    long process_jiffies = -1, rss_pages = -1;
    const char *full = "8 (resident proc) S 1 2 3 4 5 6 7 8 9 10 100 200 0 0 20 0 1 0 999 123456 777\n";
    CHECK(stat_process_metrics(full, comm, sizeof comm, &process_jiffies, &rss_pages) == 0,
          "stat_process_metrics accepts a complete process stat\n");
    CHECK(process_jiffies == 300, "stat_process_metrics sums CPU jiffies (got %ld)\n", process_jiffies);
    CHECK(rss_pages == 777, "stat_process_metrics extracts RSS pages (got %ld)\n", rss_pages);
    CHECK(strcmp(comm, "resident proc") == 0, "stat_process_metrics extracts comm (got '%s')\n", comm);
    CHECK(stat_process_metrics(s, comm, sizeof comm, &process_jiffies, &rss_pages) == -1,
          "stat_process_metrics rejects a stat line truncated before RSS\n");
}

static void test_panel_bridge_cmdline(void) {
    const char running[] = "/data/data/com.termux/files/usr/bin/node\0/data/data/com.termux/files/home/05cube-ts/dist/index.js\0";
    const char relative[] = "/data/data/com.termux/files/usr/bin/node\0dist/index.js\0";
    const char stopped[] = "runsv\0cube\0";
    const char z2m[] = "node\0/home/zigbee2mqtt/index.js\0";
    const char viewer[] = "tail\0/data/data/com.termux/files/home/zigbee2mqtt/log/current\0";
    const char maintenance[] = "node\0/data/data/com.termux/files/home/zigbee2mqtt/tools/backup.js\0";
    const char nested_index[] = "node\0/data/data/com.termux/files/home/zigbee2mqtt/tools/index.js\0";
    CHECK(is_panel_bridge_cmdline(running, sizeof running - 1, "") == 1, "CUBE node payload is running\n");
    CHECK(is_panel_bridge_cmdline(relative, sizeof relative - 1, "/data/data/com.termux/files/home/05cube-ts") == 1,
          "relative CUBE node payload is running\n");
    CHECK(is_panel_bridge_cmdline(z2m, sizeof z2m - 1, "") == 1, "zigbee2mqtt payload is running\n");
    CHECK(!is_panel_bridge_cmdline(stopped, sizeof stopped - 1, ""), "runit supervisor is not the bridge\n");
    CHECK(!is_panel_bridge_cmdline(viewer, sizeof viewer - 1, ""), "log viewer is not the bridge\n");
    CHECK(!is_panel_bridge_cmdline(maintenance, sizeof maintenance - 1, ""), "maintenance script is not the bridge\n");
    CHECK(!is_panel_bridge_cmdline(nested_index, sizeof nested_index - 1, ""), "nested index script is not the bridge\n");
    CHECK(is_panel_bridge_cmdline(relative, sizeof relative - 1, "") == -1,
          "unreadable bridge cwd is unknown\n");
}

static void test_perfdump_bridge_protocol(void) {
    FILE *snapshot = tmpfile();
    CHECK(snapshot != NULL, "PERFDUMP fixture opens a snapshot sink\n");
    if (!snapshot) return;
    conn_ctx ctx = { .fd = fileno(snapshot) };
    cmd_perfdump(&ctx, "BRIDGE 9999999");
    rewind(snapshot);
    char line[512];
    int marker = 0, ended = 0;
    while (fgets(line, sizeof line, snapshot)) {
        if (strcmp(line, "@BRIDGE 0\n") == 0) marker++;
        if (strcmp(line, "@END\n") == 0) ended++;
    }
    CHECK(marker == 1 && ended == 1, "UID-scoped PERFDUMP emits one absent bridge marker and terminates\n");
    fclose(snapshot);

    snapshot = tmpfile();
    CHECK(snapshot != NULL, "ordinary PERFDUMP fixture opens a snapshot sink\n");
    if (!snapshot) return;
    ctx.fd = fileno(snapshot);
    cmd_perfdump(&ctx, "");
    rewind(snapshot);
    marker = 0;
    while (fgets(line, sizeof line, snapshot)) if (strncmp(line, "@BRIDGE ", 8) == 0) marker++;
    CHECK(marker == 0, "ordinary PERFDUMP does no bridge scan or marker\n");
    fclose(snapshot);

    int ready[2];
    int piped = pipe(ready) == 0;
    CHECK(piped, "running bridge fixture opens readiness pipe\n");
    if (!piped) return;
    pid_t child = fork();
    CHECK(child >= 0, "running bridge fixture forks\n");
    if (child == 0) {
        close(ready[0]);
        char fd_arg[16]; snprintf(fd_arg, sizeof fd_arg, "%d", ready[1]);
        char *const args[] = { "node", "/tmp/05cube-ts/dist/index.js", "--bridge-fixture", fd_arg, NULL };
        execv("/proc/self/exe", args);
        _exit(127);
    }
    if (child < 0) { close(ready[0]); close(ready[1]); return; }
    close(ready[1]);
    char token = 0;
    int started = read(ready[0], &token, 1) == 1 && token == 'R';
    close(ready[0]);
    CHECK(started, "running bridge fixture starts its node-shaped process\n");
    if (started) {
        snapshot = tmpfile();
        CHECK(snapshot != NULL, "running PERFDUMP fixture opens a snapshot sink\n");
        if (snapshot) {
            ctx.fd = fileno(snapshot);
            char command[32]; snprintf(command, sizeof command, "BRIDGE %lu", (unsigned long)getuid());
            cmd_perfdump(&ctx, command);
            rewind(snapshot);
            marker = 0;
            while (fgets(line, sizeof line, snapshot)) if (strcmp(line, "@BRIDGE 1\n") == 0) marker++;
            CHECK(marker == 1, "UID-scoped PERFDUMP sees the running bridge fixture\n");
            fclose(snapshot);
        }
    }
    kill(child, SIGTERM);
    waitpid(child, NULL, 0);
}

static void test_dispatch_exact_match(void) {
    char out[64];
    CHECK(strcmp(helper_identity(), "HELPER version=1.3.1 proto=1.3") == 0,
          "helper identity is stable (got '%s')\n", helper_identity());
    dispatch_reply("VERSION", out, sizeof out);
    CHECK(strcmp(out, "HELPER version=1.3.1 proto=1.3\n") == 0,
          "VERSION -> machine-readable identity (got '%s')\n", out);
    dispatch_reply("VERSION extra", out, sizeof out);
    CHECK(strcmp(out, "ERR\n") == 0, "VERSION rejects arguments (got '%s')\n", out);

    dispatch_reply("PING", out, sizeof out);
    CHECK(strcmp(out, "OK\n") == 0, "PING -> OK (got '%s')\n", out);
    sysexec_stub_reset();
    dispatch_reply("ZIGBEECONTAIN extra", out, sizeof out);
    CHECK(strcmp(out, "ERR\n") == 0, "ZIGBEECONTAIN rejects arguments (got '%s')\n", out);
    dispatch_reply("ZIGBEECONTAIN", out, sizeof out);
    CHECK(strcmp(out, "OK\n") == 0, "ZIGBEECONTAIN accepts the exact argument-free verb (got '%s')\n", out);
    CHECK(sysexec_stub_count_run("/vendor/bin/siliconlabs_host") == 1,
          "ZIGBEECONTAIN uses the fixed Sonoff vendor directory\n");
    CHECK(sysexec_stub_count_run("com.android") == 0,
          "ZIGBEECONTAIN does not target unrelated Android packages\n");
    CHECK(sysexec_stub_count_run("killall") == 0,
          "ZIGBEECONTAIN never uses broad process-name signalling\n");
    sysexec_stub_reset();
    sysexec_stub_fail_run("/vendor/bin/siliconlabs_host", 2 << 8);
    dispatch_reply("ZIGBEECONTAIN", out, sizeof out);
    CHECK(strcmp(out, "PARTIAL\n") == 0, "ZIGBEECONTAIN reports demoted survivors (got '%s')\n", out);
    sysexec_stub_reset();
    sysexec_stub_fail_run("/vendor/bin/siliconlabs_host", 3 << 8);
    dispatch_reply("ZIGBEECONTAIN", out, sizeof out);
    CHECK(strcmp(out, "ERR\n") == 0, "ZIGBEECONTAIN reports failed admission (got '%s')\n", out);
    dispatch_reply("BUILDID", out, sizeof out);
    CHECK(strcmp(out, "BUILDID development\n") == 0,
          "host helper exposes its compile identity (got '%s')\n", out);

    // Case-sensitive, exact verb: lowercase and unknown verbs are ERR.
    dispatch_reply("ping", out, sizeof out);
    CHECK(strcmp(out, "ERR\n") == 0, "ping (lowercase) -> ERR (got '%s')\n", out);
    dispatch_reply("NOPE", out, sizeof out);
    CHECK(strcmp(out, "ERR\n") == 0, "unknown verb -> ERR (got '%s')\n", out);

    // The headline win of exact-match: a verb that is a PREFIX of input no longer matches.
    dispatch_reply("OFFOFF", out, sizeof out);
    CHECK(strcmp(out, "ERR\n") == 0, "OFFOFF -> ERR, not OFF (got '%s')\n", out);
    dispatch_reply("PINGEXTRA", out, sizeof out);
    CHECK(strcmp(out, "ERR\n") == 0, "PINGEXTRA -> ERR (got '%s')\n", out);

    // Leading whitespace is trimmed before the verb.
    dispatch_reply("   PING", out, sizeof out);
    CHECK(strcmp(out, "OK\n") == 0, "leading spaces trimmed (got '%s')\n", out);

    // LEDPROBE reports the backend; on a host with no LED node it is "none".
    dispatch_reply("LEDPROBE", out, sizeof out);
    CHECK(strcmp(out, "none\n") == 0, "LEDPROBE -> none on host (got '%s')\n", out);

    // SETHOME routes to set-home-activity, bounded to a valid component (reuses valid_component);
    // the sysexec stub makes a well-formed component succeed, a metachar/empty arg is rejected.
    dispatch_reply("SETHOME io.homeassistant.companion.android/.Home", out, sizeof out);
    CHECK(strcmp(out, "OK\n") == 0, "SETHOME valid component -> OK (got '%s')\n", out);
    dispatch_reply("SETHOME ;reboot", out, sizeof out);
    CHECK(strcmp(out, "ERR\n") == 0, "SETHOME metachar arg -> ERR (got '%s')\n", out);
    dispatch_reply("SETHOME", out, sizeof out);
    CHECK(strcmp(out, "ERR\n") == 0, "SETHOME no arg -> ERR (got '%s')\n", out);

    // OVERLAY without a mode reads the exact app-op for durable tame/untame restoration.
    sysexec_stub_reset();
    sysexec_stub_add_popen("appops get", "SYSTEM_ALERT_WINDOW: ignore; time=+2h1m\n", 0);
    dispatch_reply("OVERLAY com.example.app", out, sizeof out);
    CHECK(strcmp(out, "MODE=ignore\n") == 0, "OVERLAY reads an explicit prior mode (got '%s')\n", out);
    sysexec_stub_reset();
    sysexec_stub_add_popen("appops get", "No operations.\n", 0);
    dispatch_reply("OVERLAY com.example.app", out, sizeof out);
    CHECK(strcmp(out, "MODE=default\n") == 0, "OVERLAY maps an absent op to platform default (got '%s')\n", out);
    sysexec_stub_reset();
    dispatch_reply("OVERLAY com.example.app", out, sizeof out);
    CHECK(strcmp(out, "ERR\n") == 0, "OVERLAY reports query failure (got '%s')\n", out);
    dispatch_reply("OVERLAY com.example.app allow trailing", out, sizeof out);
    CHECK(strcmp(out, "ERR\n") == 0, "OVERLAY rejects trailing arguments (got '%s')\n", out);
    dispatch_reply("OVERLAY com.android.systemui ignore", out, sizeof out);
    CHECK(strcmp(out, "ERR\n") == 0, "OVERLAY refuses restrictive modes for critical packages (got '%s')\n", out);

    // APPSTATE: a missing probe is an execution failure, not proof that the process is dead.
    dispatch_reply("APPSTATE io.homeassistant.companion.android", out, sizeof out);
    CHECK(strcmp(out, "ERR\n") == 0, "APPSTATE missing pidof probe -> ERR (got '%s')\n", out);
    dispatch_reply("APPSTATE ;rm", out, sizeof out);
    CHECK(strcmp(out, "ERR\n") == 0, "APPSTATE metachar pkg -> ERR (got '%s')\n", out);
    dispatch_reply("APPSTATE", out, sizeof out);
    CHECK(strcmp(out, "ERR\n") == 0, "APPSTATE no arg -> ERR (got '%s')\n", out);

    // A handler with a failing target replies ERR (no LED/backlight node on the host).
    dispatch_reply("RGB 1 2 3", out, sizeof out);
    CHECK(strcmp(out, "ERR\n") == 0, "RGB with no LED node -> ERR (got '%s')\n", out);
    dispatch_reply("RGB 1 2", out, sizeof out);
    CHECK(strcmp(out, "ERR\n") == 0, "RGB with too few args -> ERR (got '%s')\n", out);
    dispatch_reply("BLPOWER", out, sizeof out);
    CHECK(strcmp(out, "ERR\n") == 0, "BLPOWER with no host backlight node -> ERR (got '%s')\n", out);
}

// Execute the command emitted by the real dispatch path against a synthetic vendor directory and
// proc tree. Only fixture paths are substituted; kill is a shell function that removes fixture PIDs.
static int replace_text(const char *source, const char *from, const char *to,
                        char *out, size_t capacity) {
    size_t used = 0, from_len = strlen(from), to_len = strlen(to);
    int replacements = 0;
    while (*source) {
        size_t copy = strncmp(source, from, from_len) == 0 ? to_len : 1;
        if (used + copy >= capacity) return -1;
        if (copy == to_len && strncmp(source, from, from_len) == 0) {
            memcpy(out + used, to, copy);
            source += from_len;
            replacements++;
        } else {
            out[used] = *source++;
        }
        used += copy;
    }
    out[used] = '\0';
    return replacements;
}

static void fixture_file(const char *path, mode_t mode) {
    int fd = open(path, O_CREAT | O_TRUNC | O_WRONLY, mode);
    CHECK(fd >= 0, "Zigbee fixture creates %s\n", path);
    if (fd >= 0) close(fd);
}

static void fixture_process(const char *proc, const char *pid, const char *exe,
                            const char *arg0, const char *arg1, const char *arg2) {
    char dir[300], path[320];
    snprintf(dir, sizeof dir, "%s/%s", proc, pid);
    CHECK(mkdir(dir, 0700) == 0, "Zigbee fixture creates process %s\n", pid);
    snprintf(path, sizeof path, "%s/exe", dir);
    CHECK(symlink(exe, path) == 0, "Zigbee fixture links process %s\n", pid);
    snprintf(path, sizeof path, "%s/cmdline", dir);
    int fd = open(path, O_CREAT | O_TRUNC | O_WRONLY, 0600);
    CHECK(fd >= 0, "Zigbee fixture creates cmdline %s\n", pid);
    if (fd < 0) return;
    const char *args[] = {arg0, arg1, arg2};
    for (size_t i = 0; i < 3 && args[i]; i++) {
        size_t length = strlen(args[i]) + 1;
        CHECK(write(fd, args[i], length) == (ssize_t)length,
              "Zigbee fixture writes cmdline %s\n", pid);
    }
    close(fd);
}

static void test_zigbeecontain_layouts(void) {
    struct layout_case {
        const char *name;
        int run, gateway, broker, guard, managed, version;
        const char *targets;
    } cases[] = {
        {"4.x", 1, 1, 1, 0, 0, 0, "-TERM 101 102\n"},
        {"older native", 1, 1, 0, 1, 0, 0, "-TERM 101 102 103\n"},
        {"NSPPT managed", 1, 1, 0, 1, 1, 1, "-TERM 101 102 103\n"},
        {"4.x without broker", 1, 1, 0, 0, 0, 0, ""},
        {"4.x nonexecutable broker", 1, 1, -1, 0, 0, 0, ""},
        {"4.x without launcher", 0, 1, 1, 0, 0, 0, ""},
        {"4.x without gateway", 1, 0, 1, 0, 0, 0, ""},
        {"native without launcher", 0, 1, 0, 1, 0, 0, "-TERM 101 102 103\n"},
        {"mixed managed launcher", 1, 1, 1, 1, 1, 0, ""},
        {"mixed package version", 1, 1, 1, 1, 0, 1, ""},
        {"guardless managed marker", 1, 1, 1, 0, 1, 0, ""},
        {"guardless package marker", 1, 1, 1, 0, 0, 1, ""},
        {"guard symlink", 1, 1, 1, -1, 0, 0, ""},
    };
    char out[64], original[4096];
    sysexec_stub_reset();
    dispatch_reply("ZIGBEECONTAIN", out, sizeof out);
    CHECK(sysexec_stub_last_run(original, sizeof original),
          "ZIGBEECONTAIN supplies a complete production command\n");
    if (!sysexec_stub_last_run(original, sizeof original)) return;

    for (size_t i = 0; i < sizeof cases / sizeof cases[0]; i++) {
        const struct layout_case *c = &cases[i];
        char root[] = "/tmp/zigbeecontain-XXXXXX";
        CHECK(mkdtemp(root) != NULL, "Zigbee fixture directory for %s\n", c->name);
        char vendor[256], proc[256], log[300], path[320], exe[320], conf[320];
        snprintf(vendor, sizeof vendor, "%s/vendor", root);
        snprintf(proc, sizeof proc, "%s/proc", root);
        snprintf(log, sizeof log, "%s/kills", root);
        CHECK(mkdir(vendor, 0700) == 0 && mkdir(proc, 0700) == 0,
              "Zigbee fixture subdirectories for %s\n", c->name);
        fixture_file(log, 0600);
        if (c->run) {
            snprintf(path, sizeof path, "%s/run.sh", vendor); fixture_file(path, 0600);
        }
        if (c->gateway) {
            snprintf(path, sizeof path, "%s/zgateway", vendor); fixture_file(path, 0700);
        }
        if (c->broker) {
            snprintf(path, sizeof path, "%s/mosquitto", vendor);
            fixture_file(path, c->broker > 0 ? 0700 : 0600);
        }
        if (c->guard > 0) {
            snprintf(path, sizeof path, "%s/guard_process.sh", vendor); fixture_file(path, 0600);
        } else if (c->guard < 0) {
            snprintf(path, sizeof path, "%s/guard_process.sh", vendor);
            CHECK(symlink("run.sh", path) == 0, "Zigbee fixture guard symlink\n");
        }
        if (c->managed) {
            snprintf(path, sizeof path, "%s/run_guard_process.sh", vendor); fixture_file(path, 0600);
        }
        if (c->version) {
            snprintf(path, sizeof path, "%s/package_version", vendor); fixture_file(path, 0600);
        }
        snprintf(exe, sizeof exe, "%s/zgateway", vendor);
        fixture_process(proc, "101", exe, exe, NULL, NULL);
        snprintf(exe, sizeof exe, "%s/mosquitto", vendor);
        snprintf(conf, sizeof conf, "%s/mosquitto.conf", vendor);
        fixture_process(proc, "102", exe, exe, "-c", conf);
        snprintf(exe, sizeof exe, "%s/guard_process.sh", vendor);
        fixture_process(proc, "103", "/system/bin/sh", "sh", exe, NULL);
        fixture_process(proc, "104", "/tmp/zgateway", exe, NULL, NULL);
        snprintf(exe, sizeof exe, "%s/zgateway", vendor);
        fixture_process(proc, "105", exe, "/tmp/zgateway", NULL, NULL);
        snprintf(exe, sizeof exe, "%s/mosquitto", vendor);
        fixture_process(proc, "106", exe, exe, "-c", "/tmp/mosquitto.conf");

        char substituted[8192], command[12000], proc_prefix[300];
        snprintf(proc_prefix, sizeof proc_prefix, "%s/proc/", root);
        int a = replace_text(original, "d=/vendor/bin/siliconlabs_host",
                             "d=__FIXTURE_VENDOR__", substituted, sizeof substituted);
        char with_vendor[8192];
        char assignment[300];
        snprintf(assignment, sizeof assignment, "d=%s", vendor);
        int b = replace_text(substituted, "d=__FIXTURE_VENDOR__", assignment,
                             with_vendor, sizeof with_vendor);
        int p = replace_text(with_vendor, "/proc/", proc_prefix,
                             substituted, sizeof substituted);
        CHECK(a == 1 && b == 1 && p >= 4, "Zigbee fixture rewrites all fixed paths for %s\n", c->name);
        if (a != 1 || b != 1 || p < 4) return;
        snprintf(command, sizeof command,
                 "kill() { printf '%%s\\n' \"$*\" >> '%s'; shift; "
                 "for zpid in \"$@\"; do rm -f '%s'/\"$zpid\"/exe '%s'/\"$zpid\"/cmdline; "
                 "rmdir '%s'/\"$zpid\"; done; }; "
                 "killall() { return 99; }; pkill() { return 99; }; sleep() { :; }; %s",
                 log, proc, proc, proc, substituted);
        int status = system(command);
        int expected = c->targets[0] ? 0 : 3;
        CHECK(WIFEXITED(status) && WEXITSTATUS(status) == expected,
              "Zigbee %s admission returns %d (status=%d)\n", c->name, expected, status);
        char actual[128] = "";
        FILE *kills = fopen(log, "r");
        if (kills) { size_t n = fread(actual, 1, sizeof actual - 1, kills); actual[n] = '\0'; fclose(kills); }
        CHECK(strcmp(actual, c->targets) == 0,
              "Zigbee %s signals only exact allowed processes (got '%s')\n", c->name, actual);
        snprintf(command, sizeof command, "rm -rf -- '%s'", root);
        CHECK(system(command) == 0, "Zigbee fixture cleanup for %s\n", c->name);
    }
}

// The reporter's defect and its fix, as a request/reply contract: `svc power reboot` exits 0 without
// rebooting under this daemon's sanitized environment, so a zero exit must never end the escalation.
static void test_reboot_escalation(void) {
    char out[64];
    static const char *const svc_argv[] = { "svc", "power", "reboot", NULL };
    static const char *const direct_argv[] = { "reboot", NULL };

    // A zero exit from the first mechanism is exactly the lie that used to suppress the fallback.
    sysexec_stub_reset();
    dispatch_reply("REBOOT AWAIT", out, sizeof out);
    CHECK(strcmp(out, "ERR\n") == 0,
          "REBOOT AWAIT reports failure when the panel is still up (got '%s')\n", out);
    CHECK(sysexec_stub_count_argv("/system/bin/svc", svc_argv, 1) == 1,
          "REBOOT AWAIT requests the svc mechanism exactly once\n");
    CHECK(sysexec_stub_count_argv("/system/bin/reboot", direct_argv, 1) == 1,
          "a zero exit from svc must still escalate to /system/bin/reboot\n");
    CHECK(sysexec_stub_count_argv_calls() == 2,
          "REBOOT AWAIT runs the two declared mechanisms and nothing else\n");
    CHECK(sysexec_stub_count_sleep() == 2 && sysexec_stub_total_sleep_ms() == 10000UL,
          "each request is followed by its bounded wait (calls=%d total=%lums)\n",
          sysexec_stub_count_sleep(), sysexec_stub_total_sleep_ms());
    // The actuator is an app_process wrapper that can wedge rather than fail, so an unbounded wait on
    // it would mean the second mechanism is never reached at all. Every request carries its own window
    // as a hard deadline.
    CHECK(sysexec_stub_count_deadline_calls() == 2,
          "every reboot mechanism runs under a deadline, never an unbounded wait (calls=%d)\n",
          sysexec_stub_count_deadline_calls());
    CHECK(sysexec_stub_deadline_ms(0) == 6000u && sysexec_stub_deadline_ms(1) == 4000u,
          "each mechanism's deadline is its own escalation window (%u, %u)\n",
          sysexec_stub_deadline_ms(0), sysexec_stub_deadline_ms(1));

    // The window is what the policy buys, so time already spent waiting on the actuator comes OUT of
    // it. A wedged mechanism that consumed its whole window must not then be slept for a second one.
    sysexec_stub_reset();
    sysexec_stub_set_deadline_elapsed(6000u);
    dispatch_reply("REBOOT AWAIT", out, sizeof out);
    CHECK(sysexec_stub_total_sleep_ms() == 0UL,
          "a mechanism that consumed its whole window is not slept again (total=%lums)\n",
          sysexec_stub_total_sleep_ms());
    CHECK(sysexec_stub_count_argv("/system/bin/reboot", direct_argv, 1) == 1,
          "a wedged first mechanism still escalates once its deadline expires\n");

    // And a partly consumed window sleeps only what is left of it, so the total stays the same in
    // every case — which is what keeps the whole escalation inside the client's own budget.
    sysexec_stub_reset();
    sysexec_stub_set_deadline_elapsed(1000u);
    dispatch_reply("REBOOT AWAIT", out, sizeof out);
    CHECK(sysexec_stub_total_sleep_ms() == 8000UL,
          "only the unconsumed remainder of each window is slept (total=%lums)\n",
          sysexec_stub_total_sleep_ms());

    // A failing first mechanism escalates identically — the outcome never depends on exit status.
    sysexec_stub_reset();
    sysexec_stub_fail_run("svc", 256);
    dispatch_reply("REBOOT AWAIT", out, sizeof out);
    CHECK(strcmp(out, "ERR\n") == 0,
          "REBOOT AWAIT still reports failure when svc exits nonzero (got '%s')\n", out);
    CHECK(sysexec_stub_count_argv("/system/bin/reboot", direct_argv, 1) == 1,
          "a nonzero exit from svc escalates to /system/bin/reboot\n");

    // Bare REBOOT keeps the legacy accept-then-go-down contract for clients that predate AWAIT, and
    // gains the escalation regardless — an old app on a new helper still reaches the fallback.
    sysexec_stub_reset();
    dispatch_reply("REBOOT", out, sizeof out);
    CHECK(strcmp(out, "OK\n") == 0, "bare REBOOT accepts before going down (got '%s')\n", out);
    CHECK(sysexec_stub_count_argv("/system/bin/reboot", direct_argv, 1) == 1,
          "bare REBOOT escalates through the same bounded mechanisms\n");

    sysexec_stub_reset();
    dispatch_reply("REBOOT NOW", out, sizeof out);
    CHECK(strcmp(out, "ERR\n") == 0, "REBOOT rejects an unknown mode (got '%s')\n", out);
    CHECK(sysexec_stub_count_argv_calls() == 0, "a rejected REBOOT mode executes nothing\n");

    sysexec_stub_reset();
    dispatch_reply("REBOOT AWAIT extra", out, sizeof out);
    CHECK(strcmp(out, "ERR\n") == 0, "REBOOT rejects a trailing argument (got '%s')\n", out);
    CHECK(sysexec_stub_count_argv_calls() == 0, "a rejected REBOOT argument executes nothing\n");
}

// KEYEVENT accepts named keys only. A numeric or unknown name must never reach `input`, because that
// would turn a screen-power verb into arbitrary key injection selectable from a profile document.
static void test_keyevent_named_keys_only(void) {
    char out[64];
    static const char *const sleep_argv[] = { "input", "keyevent", "223", NULL };
    static const char *const wakeup_argv[] = { "input", "keyevent", "224", NULL };

    sysexec_stub_reset();
    dispatch_reply("KEYEVENT SLEEP", out, sizeof out);
    CHECK(strcmp(out, "OK\n") == 0, "KEYEVENT SLEEP -> OK (got '%s')\n", out);
    CHECK(sysexec_stub_count_argv("/system/bin/input", sleep_argv, 1) == 1,
          "KEYEVENT SLEEP injects the compiled KEYCODE_SLEEP constant\n");

    sysexec_stub_reset();
    dispatch_reply("KEYEVENT WAKEUP", out, sizeof out);
    CHECK(strcmp(out, "OK\n") == 0, "KEYEVENT WAKEUP -> OK (got '%s')\n", out);
    CHECK(sysexec_stub_count_argv("/system/bin/input", wakeup_argv, 1) == 1,
          "KEYEVENT WAKEUP injects the compiled KEYCODE_WAKEUP constant\n");

    static const char *const refused[] = { "KEYEVENT 26", "KEYEVENT POWER", "KEYEVENT",
                                           "KEYEVENT SLEEP 26", "KEYEVENT sleep" };
    for (size_t i = 0; i < sizeof refused / sizeof refused[0]; i++) {
        sysexec_stub_reset();
        dispatch_reply(refused[i], out, sizeof out);
        CHECK(strcmp(out, "ERR\n") == 0, "'%s' -> ERR (got '%s')\n", refused[i], out);
        CHECK(sysexec_stub_count_argv_calls() == 0,
              "'%s' must not execute anything\n", refused[i]);
    }

    // The exit status of `input` is reported, but the app never treats OK as proof of a state change.
    sysexec_stub_reset();
    sysexec_stub_fail_run("input", 256);
    dispatch_reply("KEYEVENT SLEEP", out, sizeof out);
    CHECK(strcmp(out, "ERR\n") == 0, "a failed injection is reported as ERR (got '%s')\n", out);

    // `input` is an app_process wrapper too. Without a deadline a wedged one holds this connection
    // thread for the life of the daemon, and because the client abandons its read long before the
    // injection finishes, that is the ordinary case on a daemon-only panel rather than a rare one.
    sysexec_stub_reset();
    dispatch_reply("KEYEVENT SLEEP", out, sizeof out);
    CHECK(sysexec_stub_count_deadline_calls() == 1,
          "the key injection runs under a deadline, never an unbounded wait (calls=%d)\n",
          sysexec_stub_count_deadline_calls());
    CHECK(sysexec_stub_deadline_ms(0) == 4000u,
          "the injection deadline outlasts a slow app_process rather than racing the client (%u)\n",
          sysexec_stub_deadline_ms(0));
}

static void test_sysctl_execution_results(void) {
    char out[128];
    const struct { const char *line; const char *exec_match; } cases[] = {
        { "START com.example/.Main", "am start" },
        { "DENSITY 240", "wm density 240" },
        { "DENSITY reset", "wm density reset" },
        { "FONTSCALE 1.15", "settings put system font_scale" },
        { "FONTSCALE reset", "settings delete system font_scale" },
        { "STOP com.example.app", "am force-stop" },
        { "DISABLE com.example.app", "pm disable-user" },
        { "ENABLE com.example.app", "pm enable" },
        { "OVERLAY com.example.app deny", "appops set" },
        { "OVERLAY com.example.app default", "appops set" },
        { "OVERLAY com.example.app ignore", "appops set" },
        { "OVERLAY com.example.app foreground", "appops set" },
        { "SETHOME com.example/.Home", "set-home-activity" },
    };
    for (size_t i = 0; i < sizeof cases / sizeof cases[0]; i++) {
        sysexec_stub_reset();
        dispatch_reply(cases[i].line, out, sizeof out);
        CHECK(strcmp(out, "OK\n") == 0, "%s succeeds when command succeeds (got '%s')\n", cases[i].line, out);
        CHECK(sysexec_stub_count_run(cases[i].exec_match) == 0,
              "%s never reaches the constant-shell seam\n", cases[i].line);
        sysexec_stub_fail_run(cases[i].exec_match, 256);
        dispatch_reply(cases[i].line, out, sizeof out);
        CHECK(strcmp(out, "ERR\n") == 0, "%s reports command failure (got '%s')\n", cases[i].line, out);
    }

    // Governor updates are direct sysfs writes, not subprocesses. A host without writable cpufreq
    // nodes fails closed and must never construct a shell command.
    sysexec_stub_reset();
    dispatch_reply("GOV performance", out, sizeof out);
    // The fail-closed half only means anything where the nodes really are unwritable. A
    // privileged run on a machine that exposes them can write one, so assert that half
    // against the precondition it names. Never shelling out stays unconditional.
    int cpufreq_writable =
        access("/sys/devices/system/cpu/cpu0/cpufreq/scaling_governor", W_OK) == 0;
    CHECK(cpufreq_writable || strcmp(out, "ERR\n") == 0,
          "GOV fails closed without writable cpufreq nodes (got '%s')\n", out);
    CHECK(sysexec_stub_count_run("scaling_governor") == 0,
          "GOV never constructs a request-derived shell command\n");

    // RELOAD is a two-command transaction: either force-stop or relaunch failure makes it fail.
    sysexec_stub_reset();
    dispatch_reply("RELOAD com.example.app", out, sizeof out);
    CHECK(strcmp(out, "OK\n") == 0, "RELOAD succeeds when both commands succeed (got '%s')\n", out);
    sysexec_stub_fail_run("am force-stop", 256);
    dispatch_reply("RELOAD com.example.app", out, sizeof out);
    CHECK(strcmp(out, "ERR\n") == 0, "RELOAD reports force-stop failure (got '%s')\n", out);
    sysexec_stub_reset();
    sysexec_stub_fail_run("monkey -p", 256);
    dispatch_reply("RELOAD com.example.app", out, sizeof out);
    CHECK(strcmp(out, "ERR\n") == 0, "RELOAD reports relaunch failure (got '%s')\n", out);

    // Read verbs distinguish valid state from a pipe/open/exit failure.
    sysexec_stub_reset();
    sysexec_stub_add_popen("wm density", "Physical density: 320\nOverride density: 240\n", 0);
    dispatch_reply("DENSITY", out, sizeof out);
    CHECK(strcmp(out, "PHYS=320 OVER=240\n") == 0, "DENSITY reports parsed values (got '%s')\n", out);
    sysexec_stub_reset();
    dispatch_reply("DENSITY", out, sizeof out);
    CHECK(strcmp(out, "ERR\n") == 0, "DENSITY reports pipe-open failure (got '%s')\n", out);
    sysexec_stub_add_popen("wm density", "Physical density: 320\n", 256);
    dispatch_reply("DENSITY", out, sizeof out);
    CHECK(strcmp(out, "ERR\n") == 0, "DENSITY reports command-exit failure (got '%s')\n", out);
    sysexec_stub_reset();
    sysexec_stub_add_popen("wm density", "unexpected output\n", 0);
    dispatch_reply("DENSITY", out, sizeof out);
    CHECK(strcmp(out, "ERR\n") == 0, "DENSITY reports unparseable output (got '%s')\n", out);

    sysexec_stub_reset();
    sysexec_stub_add_popen("settings get system font_scale", "1.25\n", 0);
    dispatch_reply("FONTSCALE", out, sizeof out);
    CHECK(strcmp(out, "SCALE=1.25\n") == 0, "FONTSCALE reports parsed value (got '%s')\n", out);
    sysexec_stub_reset();
    dispatch_reply("FONTSCALE", out, sizeof out);
    CHECK(strcmp(out, "ERR\n") == 0, "FONTSCALE reports pipe-open failure (got '%s')\n", out);
    sysexec_stub_add_popen("settings get system font_scale", "1.25\n", 256);
    dispatch_reply("FONTSCALE", out, sizeof out);
    CHECK(strcmp(out, "ERR\n") == 0, "FONTSCALE reports command-exit failure (got '%s')\n", out);

    sysexec_stub_reset();
    sysexec_stub_add_popen("pidof", "", 256);  // pidof exit 1 is the expected no-process result.
    dispatch_reply("APPSTATE com.example.app", out, sizeof out);
    CHECK(strcmp(out, "DEAD\n") == 0, "APPSTATE distinguishes pidof exit 1 as DEAD (got '%s')\n", out);
    sysexec_stub_reset();
    sysexec_stub_add_popen("pidof", "123\n", 0);
    sysexec_stub_add_popen("dumpsys window", "mCurrentFocus=Window{ com.example.app/.Main }\n", 0);
    dispatch_reply("APPSTATE com.example.app", out, sizeof out);
    CHECK(strcmp(out, "FG\n") == 0, "APPSTATE reports focused live process (got '%s')\n", out);
    sysexec_stub_reset();
    sysexec_stub_add_popen("pidof", "123\n", 0);
    sysexec_stub_add_popen("dumpsys window", "mCurrentFocus=Window{ com.other/.Main }\n", 0);
    dispatch_reply("APPSTATE com.example.app", out, sizeof out);
    CHECK(strcmp(out, "BG\n") == 0, "APPSTATE reports background live process (got '%s')\n", out);
    sysexec_stub_reset();
    sysexec_stub_add_popen("pidof", "", 512);
    dispatch_reply("APPSTATE com.example.app", out, sizeof out);
    CHECK(strcmp(out, "ERR\n") == 0, "APPSTATE reports unexpected pidof failure (got '%s')\n", out);
    sysexec_stub_reset();
    sysexec_stub_add_popen("pidof", "123\n", 0);
    dispatch_reply("APPSTATE com.example.app", out, sizeof out);
    CHECK(strcmp(out, "ERR\n") == 0, "APPSTATE reports dumpsys pipe-open failure (got '%s')\n", out);
    sysexec_stub_reset();
    sysexec_stub_add_popen("pidof", "123\n", 0);
    sysexec_stub_add_popen("dumpsys window", "mCurrentFocus=Window{ com.other/.Main }\n", 256);
    dispatch_reply("APPSTATE com.example.app", out, sizeof out);
    CHECK(strcmp(out, "ERR\n") == 0, "APPSTATE reports dumpsys command failure (got '%s')\n", out);

    // Legacy pathname INSTALL remains protocol-compatible but fails closed without opening the
    // caller-selected path. INSTALLGC is an idempotent compatibility acknowledgement only.
    const char *install = "INSTALL /data/user/0/io.github.maxlyth.hapaneld/cache/update.apk";
    const char *install_dir = "/tmp/.hapaneld-helper-test";
    const char *install_stage = "/tmp/.hapaneld-helper-test/hapaneld-install.apk";
    unlink(install_stage);
    rmdir(install_dir);
    sysexec_stub_reset();
    dispatch_reply(install, out, sizeof out);
    CHECK(strcmp(out, "ERR\n") == 0, "legacy INSTALL fails closed (got '%s')\n", out);
    CHECK(sysexec_stub_count_run(install) == 0, "legacy INSTALL never executes caller-derived text\n");

    const char *install_gc = "INSTALLGC /data/user/0/io.github.maxlyth.hapaneld/files/helper-install-staging/retained.apk";
    dispatch_reply(install_gc, out, sizeof out);
    CHECK(strcmp(out, "OK\n") == 0, "INSTALLGC authorises retained-input cleanup while install lane is idle (got '%s')\n", out);
    dispatch_reply(install_gc, out, sizeof out);
    CHECK(strcmp(out, "OK\n") == 0, "duplicate INSTALLGC remains idempotent (got '%s')\n", out);
    dispatch_reply("INSTALL /data/user/0/io.github.maxlyth.hapaneld/files/helper-install-staging/retained.apk", out, sizeof out);
    CHECK(strcmp(out, "ERR\n") == 0, "a late legacy INSTALL remains rejected (got '%s')\n", out);
    dispatch_reply("INSTALLGC /data/local/tmp/not-owned.apk", out, sizeof out);
    CHECK(strcmp(out, "ERR\n") == 0, "INSTALLGC rejects paths outside app-private storage (got '%s')\n", out);

    // INSTALLSTREAM waits for READY before the client sends binary bytes, then installs only after the
    // exact declared length reaches root-owned staging. Block pm install so the staged bytes can be
    // inspected before terminal cleanup.
    const char *stream_stage = install_stage;
    const char *symlink_target = "/tmp/hapaneld-helper-install-stream-target";
    const char payload[] = { 0x50, 0x4b, 0x03, 0x04, 0x7f };
    unlink(stream_stage);
    sysexec_stub_reset();
    sysexec_stub_block_run("pm install");
    int stream_sv[2];
    socketpair(AF_UNIX, SOCK_STREAM, 0, stream_sv);
    stream_dispatch_job stream = { .fd = stream_sv[0], .line = "INSTALLSTREAM 5" };
    pthread_t stream_thread;
    pthread_create(&stream_thread, NULL, stream_dispatch_worker, &stream);
    read_reply_line(stream_sv[1], out, sizeof out);
    CHECK(strcmp(out, "READY\n") == 0, "INSTALLSTREAM acknowledges before payload (got '%s')\n", out);
    CHECK(write_all_fd(stream_sv[1], payload, sizeof payload) == 0, "INSTALLSTREAM client writes full payload\n");
    shutdown(stream_sv[1], SHUT_WR);
    sysexec_stub_wait_blocked();
    int staged = open(stream_stage, O_RDONLY);
    char staged_bytes[sizeof payload] = {0};
    ssize_t staged_n = staged >= 0 ? read(staged, staged_bytes, sizeof staged_bytes) : -1;
    if (staged >= 0) close(staged);
    CHECK(staged_n == (ssize_t)sizeof payload && memcmp(staged_bytes, payload, sizeof payload) == 0,
          "INSTALLSTREAM stages the exact declared bytes before package install\n");
    dispatch_reply("INSTALLSTREAM 5", out, sizeof out);
    CHECK(strcmp(out, "BUSY\n") == 0, "overlapping INSTALLSTREAM is rejected before READY (got '%s')\n", out);
    dispatch_reply(install_gc, out, sizeof out);
    CHECK(strcmp(out, "BUSY\n") == 0, "INSTALLGC waits for the active streamed install (got '%s')\n", out);
    sysexec_stub_release_run();
    read_reply_line(stream_sv[1], out, sizeof out);
    CHECK(strcmp(out, "OK\n") == 0, "INSTALLSTREAM reports package-manager success (got '%s')\n", out);
    pthread_join(stream_thread, NULL);
    close(stream_sv[1]);
    CHECK(access(stream_stage, F_OK) != 0, "INSTALLSTREAM removes root staging after success\n");

    // Package-manager failure is a terminal ERR and still removes the completed root staging file.
    sysexec_stub_reset();
    sysexec_stub_fail_run("pm install", 1);
    socketpair(AF_UNIX, SOCK_STREAM, 0, stream_sv);
    stream = (stream_dispatch_job){ .fd = stream_sv[0], .line = "INSTALLSTREAM 5" };
    pthread_create(&stream_thread, NULL, stream_dispatch_worker, &stream);
    read_reply_line(stream_sv[1], out, sizeof out);
    CHECK(strcmp(out, "READY\n") == 0, "failing INSTALLSTREAM receives READY (got '%s')\n", out);
    CHECK(write_all_fd(stream_sv[1], payload, sizeof payload) == 0, "failing INSTALLSTREAM writes full payload\n");
    shutdown(stream_sv[1], SHUT_WR);
    read_reply_line(stream_sv[1], out, sizeof out);
    CHECK(strcmp(out, "ERR\n") == 0, "INSTALLSTREAM reports package-manager failure (got '%s')\n", out);
    pthread_join(stream_thread, NULL);
    close(stream_sv[1]);
    CHECK(access(stream_stage, F_OK) != 0, "INSTALLSTREAM removes root staging after package-manager failure\n");

    // A shell/Shizuku peer can preplant names in local/tmp. The helper replaces the link itself and
    // must never truncate or write the target while staging a legitimate app request.
    int target = open(symlink_target, O_WRONLY | O_CREAT | O_TRUNC, 0600);
    CHECK(target >= 0 && write(target, "sentinel", 8) == 8, "create symlink target fixture\n");
    close(target);
    CHECK(symlink(symlink_target, stream_stage) == 0, "preplant install-stage symlink\n");
    sysexec_stub_reset();
    socketpair(AF_UNIX, SOCK_STREAM, 0, stream_sv);
    stream = (stream_dispatch_job){ .fd = stream_sv[0], .line = "INSTALLSTREAM 5" };
    pthread_create(&stream_thread, NULL, stream_dispatch_worker, &stream);
    read_reply_line(stream_sv[1], out, sizeof out);
    CHECK(strcmp(out, "READY\n") == 0, "safe staging replaces a preplanted symlink (got '%s')\n", out);
    CHECK(write_all_fd(stream_sv[1], payload, sizeof payload) == 0, "symlink test writes full payload\n");
    shutdown(stream_sv[1], SHUT_WR);
    read_reply_line(stream_sv[1], out, sizeof out);
    pthread_join(stream_thread, NULL);
    close(stream_sv[1]);
    char sentinel[9] = {0};
    target = open(symlink_target, O_RDONLY);
    CHECK(target >= 0 && read(target, sentinel, 8) == 8, "read symlink target fixture\n");
    close(target);
    CHECK(strcmp(sentinel, "sentinel") == 0, "INSTALLSTREAM never follows preplanted stage symlink\n");
    unlink(symlink_target);

    // EOF before the declared length is terminal failure: no package install and no stale root file.
    sysexec_stub_reset();
    socketpair(AF_UNIX, SOCK_STREAM, 0, stream_sv);
    stream = (stream_dispatch_job){ .fd = stream_sv[0], .line = "INSTALLSTREAM 5" };
    pthread_create(&stream_thread, NULL, stream_dispatch_worker, &stream);
    read_reply_line(stream_sv[1], out, sizeof out);
    CHECK(strcmp(out, "READY\n") == 0, "partial INSTALLSTREAM receives READY (got '%s')\n", out);
    CHECK(write_all_fd(stream_sv[1], payload, 3) == 0, "partial INSTALLSTREAM writes prefix\n");
    shutdown(stream_sv[1], SHUT_WR);
    read_reply_line(stream_sv[1], out, sizeof out);
    CHECK(strcmp(out, "ERR\n") == 0, "partial INSTALLSTREAM reports failure (got '%s')\n", out);
    pthread_join(stream_thread, NULL);
    close(stream_sv[1]);
    CHECK(sysexec_stub_count_run("pm install") == 0, "partial INSTALLSTREAM never invokes package manager\n");
    CHECK(access(stream_stage, F_OK) != 0, "partial INSTALLSTREAM removes root staging\n");

    // Extra bytes violate the frame and cannot be interpreted as another command after the handler.
    sysexec_stub_reset();
    socketpair(AF_UNIX, SOCK_STREAM, 0, stream_sv);
    stream = (stream_dispatch_job){ .fd = stream_sv[0], .line = "INSTALLSTREAM 4" };
    pthread_create(&stream_thread, NULL, stream_dispatch_worker, &stream);
    read_reply_line(stream_sv[1], out, sizeof out);
    CHECK(strcmp(out, "READY\n") == 0, "overlong INSTALLSTREAM receives READY (got '%s')\n", out);
    CHECK(write_all_fd(stream_sv[1], payload, sizeof payload) == 0, "overlong INSTALLSTREAM writes payload\n");
    shutdown(stream_sv[1], SHUT_WR);
    read_reply_line(stream_sv[1], out, sizeof out);
    CHECK(strcmp(out, "ERR\n") == 0, "overlong INSTALLSTREAM reports failure (got '%s')\n", out);
    pthread_join(stream_thread, NULL);
    close(stream_sv[1]);
    CHECK(sysexec_stub_count_run("pm install") == 0, "overlong INSTALLSTREAM never invokes package manager\n");
    CHECK(access(stream_stage, F_OK) != 0, "overlong INSTALLSTREAM removes root staging\n");

    // Protocol bounds and pre-staging failure are explicit responses before READY.
    dispatch_reply("INSTALLSTREAM 0", out, sizeof out);
    CHECK(strcmp(out, "STREAMERR\n") == 0, "INSTALLSTREAM rejects zero bytes (got '%s')\n", out);
    dispatch_reply("INSTALLSTREAM 268435457", out, sizeof out);
    CHECK(strcmp(out, "STREAMERR\n") == 0, "INSTALLSTREAM rejects over 256MiB (got '%s')\n", out);
    mkdir(stream_stage, 0700);
    dispatch_reply("INSTALLSTREAM 5", out, sizeof out);
    CHECK(strcmp(out, "STREAMERR\n") == 0, "INSTALLSTREAM reports root staging open failure (got '%s')\n", out);
    rmdir(stream_stage);

    sysexec_stub_reset();
}

static void test_bootchime_protocol(void) {
    char out[128];
    const char *const silence_calls[][8] = {
        { "settings", "put", "system", "volume_ring_speaker", "0", NULL },
        { "settings", "put", "system", "volume_ring", "0", NULL },
        { "settings", "put", "system", "volume_notification", "0", NULL },
        { "cmd", "media_session", "volume", "--stream", "2", "--set", "0", NULL },
        { "cmd", "media_session", "volume", "--stream", "5", "--set", "0", NULL },
    };
    const char *const silence_paths[] = {
        "/system/bin/settings", "/system/bin/settings", "/system/bin/settings",
        "/system/bin/cmd", "/system/bin/cmd",
    };

    sysexec_stub_reset();
    dispatch_reply("  BOOTCHIME\t SILENCE  \t", out, sizeof out);
    CHECK(strcmp(out, "OK\n") == 0,
          "BOOTCHIME SILENCE accepts normalized whitespace (got '%s')\n", out);
    for (size_t i = 0; i < sizeof silence_calls / sizeof silence_calls[0]; i++) {
        CHECK(sysexec_stub_count_argv(silence_paths[i], silence_calls[i], 1) == 1,
              "BOOTCHIME SILENCE emits exact argv call %zu\n", i + 1);
    }
    CHECK(sysexec_stub_count_run("") == 0,
          "BOOTCHIME SILENCE never crosses the constant-shell seam\n");

    const char *const restore_calls[][8] = {
        { "settings", "put", "system", "volume_ring_speaker", "43", NULL },
        { "settings", "delete", "system", "volume_ring", NULL },
        { "settings", "put", "system", "volume_notification", "5", NULL },
        { "cmd", "media_session", "volume", "--stream", "2", "--set", "7", NULL },
        { "cmd", "media_session", "volume", "--stream", "5", "--set", "9", NULL },
    };
    const char *const restore_paths[] = {
        "/system/bin/settings", "/system/bin/settings", "/system/bin/settings",
        "/system/bin/cmd", "/system/bin/cmd",
    };
    sysexec_stub_reset();
    dispatch_reply("BOOTCHIME  RESTORE\t43  -\t5  7\t9", out, sizeof out);
    CHECK(strcmp(out, "OK\n") == 0,
          "BOOTCHIME RESTORE accepts values and the exact null token (got '%s')\n", out);
    for (size_t i = 0; i < sizeof restore_calls / sizeof restore_calls[0]; i++) {
        CHECK(sysexec_stub_count_argv(restore_paths[i], restore_calls[i], 1) == 1,
              "BOOTCHIME RESTORE emits exact argv call %zu\n", i + 1);
    }
    CHECK(sysexec_stub_count_run("") == 0,
          "BOOTCHIME RESTORE never crosses the constant-shell seam\n");

    const char *invalid[] = {
        "BOOTCHIME",
        "BOOTCHIME SILENCE extra",
        "BOOTCHIME RESTORE",
        "BOOTCHIME RESTORE 1 2 3 4",
        "BOOTCHIME RESTORE 1 2 3 4 5 extra",
        "BOOTCHIME RESTORE - - - - 5",
        "BOOTCHIME RESTORE -1 2 3 4 5",
        "BOOTCHIME RESTORE +1 2 3 4 5",
        "BOOTCHIME RESTORE 1 2 3 4.0 5",
        "BOOTCHIME RESTORE 101 2 3 4 5",
        "BOOTCHIME RESTORE 1 2 3 4 101",
        "BOOTCHIME RESTORE 999999999999999999999 2 3 4 5",
        "BOOTCHIME RESTORE 1 2 3 4 5;reboot",
        "BOOTCHIME RESTORE 1 2 $(reboot) 4 5",
        "BOOTCHIME restore 1 2 3 4 5",
    };
    for (size_t i = 0; i < sizeof invalid / sizeof invalid[0]; i++) {
        sysexec_stub_reset();
        dispatch_reply(invalid[i], out, sizeof out);
        CHECK(strcmp(out, "ERR\n") == 0, "%s is rejected (got '%s')\n", invalid[i], out);
        CHECK(sysexec_stub_count_argv_calls() == 0,
              "%s cannot execute any structural argv mutation\n", invalid[i]);
        CHECK(sysexec_stub_count_run("") == 0,
              "%s cannot reach the constant-shell seam\n", invalid[i]);
    }

    const char *const failure_calls[][8] = {
        { "settings", "put", "system", "volume_ring_speaker", "7", NULL },
        { "settings", "put", "system", "volume_ring", "8", NULL },
        { "settings", "put", "system", "volume_notification", "9", NULL },
        { "cmd", "media_session", "volume", "--stream", "2", "--set", "10", NULL },
        { "cmd", "media_session", "volume", "--stream", "5", "--set", "11", NULL },
    };
    const char *const failure_paths[] = {
        "/system/bin/settings", "/system/bin/settings", "/system/bin/settings",
        "/system/bin/cmd", "/system/bin/cmd",
    };
    const char *const failure_needles[] = {
        "settings put system volume_ring_speaker 7",
        "settings put system volume_ring 8",
        "settings put system volume_notification 9",
        "cmd media_session volume --stream 2 --set 10",
        "cmd media_session volume --stream 5 --set 11",
    };
    for (size_t failed = 0; failed < sizeof failure_calls / sizeof failure_calls[0]; failed++) {
        sysexec_stub_reset();
        sysexec_stub_fail_run(failure_needles[failed], 256);
        dispatch_reply("BOOTCHIME RESTORE 7 8 9 10 11", out, sizeof out);
        const char *expected_reply = failed == 0 ? "ERR\n" : "PARTIAL\n";
        CHECK(strcmp(out, expected_reply) == 0,
              "BOOTCHIME RESTORE reports truthful failure at step %zu (got '%s')\n", failed + 1, out);
        for (size_t call = 0; call < sizeof failure_calls / sizeof failure_calls[0]; call++) {
            int expected = call <= failed ? 1 : 0;
            CHECK(sysexec_stub_count_argv(failure_paths[call], failure_calls[call], 1) == expected,
                  "BOOTCHIME RESTORE failure at step %zu leaves step %zu count at %d\n",
                  failed + 1, call + 1, expected);
        }
    }

    for (size_t failed = 0; failed < sizeof failure_calls / sizeof failure_calls[0]; failed++) {
        sysexec_stub_reset();
        sysexec_stub_fail_run(silence_calls[failed][0] == NULL ? "" : (const char *[]){
            "settings put system volume_ring_speaker 0",
            "settings put system volume_ring 0",
            "settings put system volume_notification 0",
            "cmd media_session volume --stream 2 --set 0",
            "cmd media_session volume --stream 5 --set 0",
        }[failed], 256);
        dispatch_reply("BOOTCHIME SILENCE", out, sizeof out);
        CHECK(strcmp(out, failed == 0 ? "ERR\n" : "PARTIAL\n") == 0,
              "BOOTCHIME SILENCE reports truthful failure at step %zu (got '%s')\n", failed + 1, out);
        for (size_t call = 0; call < sizeof silence_calls / sizeof silence_calls[0]; call++) {
            int expected = call <= failed ? 1 : 0;
            CHECK(sysexec_stub_count_argv(silence_paths[call], silence_calls[call], 1) == expected,
                  "BOOTCHIME SILENCE failure at step %zu leaves step %zu count at %d\n",
                  failed + 1, call + 1, expected);
        }
    }

    sysexec_stub_reset();
    sysexec_stub_fail_run("settings delete system volume_ring", 256);
    dispatch_reply("BOOTCHIME RESTORE 7 - 9 10 11", out, sizeof out);
    CHECK(strcmp(out, "PARTIAL\n") == 0,
          "BOOTCHIME RESTORE reports a partial nullable-delete failure (got '%s')\n", out);
    CHECK(sysexec_stub_count_argv(failure_paths[0], failure_calls[0], 1) == 1,
          "nullable-delete failure preserves the successful prefix\n");
    CHECK(sysexec_stub_count_argv_calls() == 2,
          "nullable-delete failure stops before later structural writes\n");
    sysexec_stub_reset();
}

static void test_screencap_stream_writes(void) {
    const char payload[] = "PNG-fixture-payload";
    const int test_fd = 4242;
    conn_ctx ctx = { .fd = test_fd, .subscribed = 0 };

    sysexec_stub_reset();
    sysexec_stub_add_popen("screencap -p", payload, 0);
    const int recoverable_steps[] = { 3, WRITE_STEP_EINTR, 4, WRITE_STEP_ALL };
    wrapped_write_reset(test_fd, recoverable_steps,
                        sizeof recoverable_steps / sizeof recoverable_steps[0]);
    cmd_screencap(&ctx, "");
    wrapped_write_disable();
    CHECK(wrapped_write_call_count == 4,
          "SCREENCAP retries short writes and EINTR (calls %zu, want 4)\n",
          wrapped_write_call_count);
    CHECK(wrapped_write_size == strlen(payload) &&
          memcmp(wrapped_write_bytes, payload, strlen(payload)) == 0,
          "SCREENCAP preserves the full byte stream across partial writes\n");

    char large_payload[12289];
    memset(large_payload, 'A', sizeof large_payload - 1);
    large_payload[sizeof large_payload - 1] = '\0';
    sysexec_stub_reset();
    sysexec_stub_add_popen("screencap -p", large_payload, 0);
    const int error_steps[] = { 5, WRITE_STEP_ERROR, WRITE_STEP_ALL };
    wrapped_write_reset(test_fd, error_steps, sizeof error_steps / sizeof error_steps[0]);
    cmd_screencap(&ctx, "");
    wrapped_write_disable();
    CHECK(wrapped_write_call_count == 2,
          "SCREENCAP stops writing after terminal peer error (calls %zu, want 2)\n",
          wrapped_write_call_count);
    CHECK(wrapped_write_size == 5 && memcmp(wrapped_write_bytes, large_payload, 5) == 0,
          "SCREENCAP emits only the accepted prefix after terminal peer error\n");
    CHECK(sysexec_stub_last_pclose_offset() == 8192,
          "SCREENCAP stops draining its source after terminal peer error (offset %ld, want 8192)\n",
          sysexec_stub_last_pclose_offset());

    sysexec_stub_reset();
    sysexec_stub_add_popen("screencap -p", payload, 0);
    const int zero_steps[] = { WRITE_STEP_ZERO, WRITE_STEP_ALL };
    wrapped_write_reset(test_fd, zero_steps, sizeof zero_steps / sizeof zero_steps[0]);
    cmd_screencap(&ctx, "");
    wrapped_write_disable();
    CHECK(wrapped_write_call_count == 1,
          "SCREENCAP treats a zero write as terminal (calls %zu, want 1)\n",
          wrapped_write_call_count);
    CHECK(wrapped_write_size == 0, "SCREENCAP emits no bytes after a zero write\n");
}

static void test_line_accumulator(void) {
    char out[256];

    // Two complete lines in one stream -> two replies, in order.
    serve_reply("PING\nPING\n", 10, out, sizeof out);
    CHECK(strcmp(out, "OK\nOK\n") == 0, "two PINGs -> OK\\nOK (got '%s')\n", out);

    // CRLF terminators are accepted the same as LF.
    serve_reply("PING\r\nPING\r\n", 12, out, sizeof out);
    CHECK(strcmp(out, "OK\nOK\n") == 0, "CRLF PINGs -> OK\\nOK (got '%s')\n", out);

    // Blank lines are ignored (len==0 lines aren't dispatched).
    serve_reply("\n\nPING\n", 7, out, sizeof out);
    CHECK(strcmp(out, "OK\n") == 0, "blank lines skipped (got '%s')\n", out);

    // An overlong line (> MAX_LINE) is dropped, not mis-split; the following PING still parses.
    char big[MAX_LINE + 64];
    memset(big, 'A', MAX_LINE + 10);
    memcpy(big + MAX_LINE + 10, "\nPING\n", 6);
    serve_reply(big, MAX_LINE + 16, out, sizeof out);
    CHECK(strcmp(out, "OK\n") == 0, "overlong line dropped, next line parses (got '%s')\n", out);
}

static void test_reply_stream_writes(void) {
    const int complete_steps[] = { 2, WRITE_STEP_EINTR, 1, WRITE_STEP_ALL };
    wrapped_write_reset(701, complete_steps, sizeof complete_steps / sizeof complete_steps[0]);
    CHECK(reply(701, "abcdef") == 0, "reply retries EINTR and completes short writes\n");
    wrapped_write_disable();
    CHECK(wrapped_write_call_count == 4,
          "reply uses four writes for deterministic short/EINTR sequence (got %zu)\n",
          wrapped_write_call_count);
    CHECK(wrapped_write_size == 6 && memcmp(wrapped_write_bytes, "abcdef", 6) == 0,
          "reply preserves all bytes across short writes\n");

    const int error_steps[] = { 2, WRITE_STEP_ERROR, WRITE_STEP_ALL };
    wrapped_write_reset(702, error_steps, sizeof error_steps / sizeof error_steps[0]);
    CHECK(reply(702, "abcdef") == -1, "reply propagates a terminal peer error\n");
    wrapped_write_disable();
    CHECK(wrapped_write_call_count == 2,
          "reply stops after a terminal peer error (calls %zu, want 2)\n",
          wrapped_write_call_count);
    CHECK(wrapped_write_size == 2 && memcmp(wrapped_write_bytes, "ab", 2) == 0,
          "reply emits only the accepted prefix after a terminal peer error\n");

    const int stalled_steps[] = { WRITE_STEP_STALLED, WRITE_STEP_ALL };
    wrapped_write_reset(705, stalled_steps, sizeof stalled_steps / sizeof stalled_steps[0]);
    CHECK(reply(705, "abcdef") == -1, "reply aborts when the send deadline expires\n");
    wrapped_write_disable();
    CHECK(wrapped_write_call_count == 1 && wrapped_write_size == 0,
          "reply does not spin or retry a stalled peer after EAGAIN\n");

    char path[] = "/tmp/hapaneld-cat-to-XXXXXX";
    int input = mkstemp(path);
    CHECK(input >= 0, "cat_to fixture created\n");
    if (input >= 0) {
        CHECK(write_all_fd(input, "0123456789", 10) == 0, "cat_to fixture populated\n");
        close(input);

        const int cat_steps[] = { 3, WRITE_STEP_EINTR, 2, WRITE_STEP_ALL };
        wrapped_write_reset(703, cat_steps, sizeof cat_steps / sizeof cat_steps[0]);
        CHECK(cat_to(703, path) == 0, "cat_to retries EINTR and completes short writes\n");
        wrapped_write_disable();
        CHECK(wrapped_write_size == 10 && memcmp(wrapped_write_bytes, "0123456789", 10) == 0,
              "cat_to preserves all file bytes across short writes\n");

        const int cat_error_steps[] = { 4, WRITE_STEP_ERROR };
        wrapped_write_reset(704, cat_error_steps,
                            sizeof cat_error_steps / sizeof cat_error_steps[0]);
        CHECK(cat_to(704, path) == -1, "cat_to propagates a terminal peer error\n");
        wrapped_write_disable();
        CHECK(wrapped_write_call_count == 2 && wrapped_write_size == 4,
              "cat_to aborts at the first terminal peer error\n");
        unlink(path);
    }
}

static void test_server_send_deadline(void) {
    int sv[2];
    int created = socketpair(AF_UNIX, SOCK_STREAM, 0, sv);
    CHECK(created == 0, "send-deadline socketpair created\n");
    if (created != 0) return;
    shutdown(sv[1], SHUT_WR);
    server_serve(sv[0], HELPER_CALLER_LEGACY);

    struct timeval timeout = { 0 };
    socklen_t timeout_size = sizeof timeout;
    CHECK(getsockopt(sv[0], SOL_SOCKET, SO_SNDTIMEO, &timeout, &timeout_size) == 0,
          "server send deadline is readable\n");
    CHECK(timeout.tv_sec == SEND_SEC,
          "server applies a %d-second send deadline (got %ld.%06ld)\n",
          SEND_SEC, (long)timeout.tv_sec, (long)timeout.tv_usec);
    close(sv[0]);
    close(sv[1]);
}

static void test_input_watch_contract(void) {
    char reply[16];
    dispatch_reply("INPUTV2", reply, sizeof reply);
    CHECK(strcmp(reply, "OK\n") == 0, "INPUTV2 identifies truthful WATCH semantics (got '%s')\n", reply);
    dispatch_reply("INPUTV3", reply, sizeof reply);
    CHECK(strcmp(reply, "OK\n") == 0, "INPUTV3 identifies restart-safe watch reconfiguration (got '%s')\n", reply);

    sysexec_stub_reset();
    sysexec_stub_set_spawn_result(0);
    evdev_open_ok = 1; evdev_grab_ok = 1;
    dispatch_reply("WATCH /dev/input/event7 1", reply, sizeof reply);
    CHECK(strcmp(reply, "OK\n") == 0, "well-formed WATCH gets OK after setup (got '%s')\n", reply);
    dispatch_reply("WATCH /dev/input/event7 2", reply, sizeof reply);
    CHECK(strcmp(reply, "ERR\n") == 0, "WATCH rejects non-boolean grab values (got '%s')\n", reply);
    dispatch_reply("WATCH /dev/input/event7 1 trailing", reply, sizeof reply);
    CHECK(strcmp(reply, "ERR\n") == 0, "WATCH rejects trailing fields (got '%s')\n", reply);
    dispatch_reply("WATCH /dev/input/event7/extra 1", reply, sizeof reply);
    CHECK(strcmp(reply, "ERR\n") == 0, "WATCH rejects paths below an event node (got '%s')\n", reply);

    input_init();
    sysexec_stub_reset();
    sysexec_stub_set_spawn_result(0);
    evdev_open_ok = 1; evdev_grab_ok = 1; evdev_open_count = 0;

    CHECK(input_watch("/dev/input/event1", 1) == 0, "WATCH succeeds only after initial open+grab+spawn\n");
    CHECK(evdev_open_count == 1, "first WATCH opens the node exactly once (got %d)\n", evdev_open_count);
    CHECK(input_watch("/dev/input/event1", 1) == 0, "same WATCH is idempotent\n");
    CHECK(evdev_open_count == 1, "idempotent WATCH does not reopen the node (got %d)\n", evdev_open_count);
    CHECK(input_watch("/dev/input/event1", 0) != 0, "same node with different grab policy is rejected\n");
    CHECK(input_reset_watches() == 0, "WATCHRESET clears a previous runtime's watch table\n");
    CHECK(input_watch("/dev/input/event1", 0) == 0, "WATCH accepts a changed grab policy after reset\n");

    input_init();
    evdev_grab_ok = 0;
    CHECK(input_watch("/dev/input/event2", 1) != 0, "failed EVIOCGRAB is not reported as a working watch\n");
    evdev_grab_ok = 1;
    CHECK(input_watch("/dev/input/event2", 1) == 0, "grab failure rolls back so a retry can succeed\n");

    input_init();
    evdev_open_ok = 0;
    CHECK(input_watch("/dev/input/event3", 0) != 0, "unopenable node is not reported as watched\n");
    evdev_open_ok = 1;
    CHECK(input_watch("/dev/input/event3", 0) == 0, "open failure rolls back so a retry can succeed\n");

    input_init();
    sysexec_stub_set_spawn_result(-1);
    CHECK(input_watch("/dev/input/event4", 0) != 0, "thread-start failure rejects the watch\n");
    sysexec_stub_set_spawn_result(0);
    CHECK(input_watch("/dev/input/event4", 0) == 0, "thread-start failure rolls back so a retry can succeed\n");

    CHECK(input_watch("/dev/input/event", 0) != 0, "WATCH rejects a path without a numeric event suffix\n");
    CHECK(input_watch("/dev/input/event4/extra", 0) != 0, "WATCH rejects path traversal below an event node\n");
    CHECK(input_watch("/dev/input/event5", 2) != 0, "WATCH rejects a grab value other than 0 or 1\n");
    input_init();
    sysexec_stub_reset();
}

static int create_gpio_fixture(char *path, size_t path_size, const char *contents) {
    snprintf(path, path_size, "/tmp/hapaneld-gpio-XXXXXX");
    int fd = mkstemp(path);
    if (fd < 0) return -1;
    size_t size = strlen(contents);
    int ok = write_all_fd(fd, contents, size) == 0;
    close(fd);
    return ok ? 0 : -1;
}

static int replace_gpio_fixture(const char *path, const char *contents) {
    int fd = open(path, O_WRONLY | O_TRUNC);
    if (fd < 0) return -1;
    size_t size = strlen(contents);
    int ok = write_all_fd(fd, contents, size) == 0;
    close(fd);
    return ok ? 0 : -1;
}

static void test_gpio_watch_contract(void) {
    CHECK(create_gpio_fixture(gpio_value_fixture, sizeof gpio_value_fixture, "0\n") == 0,
          "GPIO value fixture created\n");
    CHECK(create_gpio_fixture(gpio_edge_fixture, sizeof gpio_edge_fixture, "none\n") == 0,
          "GPIO edge fixture created\n");
    gpio_value_open_ok = 1;
    gpio_edge_open_ok = 1;
    gpio_value_open_count = 0;
    gpio_edge_open_count = 0;
    sysexec_stub_reset();
    sysexec_stub_set_spawn_result(0);

    char reply[64];
    dispatch_reply("GPIOV1", reply, sizeof reply);
    CHECK(strcmp(reply, "OK\n") == 0, "GPIOV1 advertises the separate GPIO stream (got '%s')\n", reply);
    dispatch_reply("GPIOV1 extra", reply, sizeof reply);
    CHECK(strcmp(reply, "ERR\n") == 0, "GPIOV1 rejects arguments (got '%s')\n", reply);
    dispatch_reply("GPIOWATCH 23", reply, sizeof reply);
    CHECK(strcmp(reply, "OK\n") == 0, "GPIOWATCH accepts an exported bounded GPIO (got '%s')\n", reply);
    dispatch_reply("GPIOWATCH -1", reply, sizeof reply);
    CHECK(strcmp(reply, "ERR\n") == 0, "GPIOWATCH rejects a signed GPIO number (got '%s')\n", reply);
    dispatch_reply("GPIOWATCH 65536", reply, sizeof reply);
    CHECK(strcmp(reply, "ERR\n") == 0, "GPIOWATCH rejects a GPIO beyond the safety bound (got '%s')\n", reply);
    dispatch_reply("GPIOWATCH 23 trailing", reply, sizeof reply);
    CHECK(strcmp(reply, "ERR\n") == 0, "GPIOWATCH rejects trailing fields (got '%s')\n", reply);
    dispatch_reply("GPIOWATCH 999999999999999999999", reply, sizeof reply);
    CHECK(strcmp(reply, "ERR\n") == 0, "GPIOWATCH rejects an overlong numeric token (got '%s')\n", reply);

    gpio_init();
    int no_watch_socket[2];
    socketpair(AF_UNIX, SOCK_STREAM, 0, no_watch_socket);
    conn_ctx no_watch_ctx = { .fd = no_watch_socket[0], .subscribed = 0 };
    char no_watch_subscribe[] = "GPIOSUBSCRIBE";
    dispatch(&no_watch_ctx, no_watch_subscribe);
    char line[64];
    read_reply_line(no_watch_socket[1], line, sizeof line);
    CHECK(strcmp(line, "ERR\n") == 0 && no_watch_ctx.subscribed == 0,
          "GPIOSUBSCRIBE rejects an empty watch table so reconnect can retry (got '%s')\n", line);
    close(no_watch_socket[0]);
    close(no_watch_socket[1]);

    gpio_value_open_count = 0;
    CHECK(gpio_watch(23) == 0, "GPIOWATCH establishes the initial held descriptor\n");
    CHECK(gpio_value_open_count == 1, "GPIOWATCH opens the value node exactly once (got %d)\n",
          gpio_value_open_count);
    CHECK(gpio_watch(23) == 0, "an identical GPIOWATCH is idempotent\n");
    CHECK(gpio_value_open_count == 1, "idempotent GPIOWATCH does not reopen the node (got %d)\n",
          gpio_value_open_count);
    CHECK(gpio_edge_open_count > 0, "GPIOWATCH attempts kernel edge configuration before sampling\n");
    int outsider_socket[2];
    socketpair(AF_UNIX, SOCK_STREAM, 0, outsider_socket);
    gpio_unsubscribe(outsider_socket[0]);
    close(outsider_socket[0]);
    close(outsider_socket[1]);
    CHECK(gpio_watch(23) == 0 && gpio_value_open_count == 1,
          "a non-subscriber disconnect cannot clear a prepared watch\n");

    int gpio_socket[2];
    socketpair(AF_UNIX, SOCK_STREAM, 0, gpio_socket);
    conn_ctx gpio_ctx = { .fd = gpio_socket[0], .subscribed = 0 };
    char subscribe[] = "GPIOSUBSCRIBE";
    dispatch(&gpio_ctx, subscribe);
    read_reply_line(gpio_socket[1], line, sizeof line);
    CHECK(strcmp(line, "OK\n") == 0 && gpio_ctx.subscribed == 1,
          "GPIOSUBSCRIBE is admitted and timeout-exempt (got '%s')\n", line);
    read_reply_line(gpio_socket[1], line, sizeof line);
    CHECK(strcmp(line, "GPIO 23 0\n") == 0,
          "GPIOSUBSCRIBE immediately reports the normalized current value (got '%s')\n", line);
    CHECK(input_reset_watches() == 0,
          "a GPIO subscriber does not block the independent evdev WATCHRESET domain\n");
    CHECK(gpio_reset_watches() != 0, "GPIORESET refuses to disrupt a live GPIO subscriber\n");

    int second_gpio_socket[2];
    socketpair(AF_UNIX, SOCK_STREAM, 0, second_gpio_socket);
    conn_ctx second_gpio_ctx = { .fd = second_gpio_socket[0], .subscribed = 0 };
    char second_subscribe[] = "GPIOSUBSCRIBE";
    dispatch(&second_gpio_ctx, second_subscribe);
    read_reply_line(second_gpio_socket[1], line, sizeof line);
    read_reply_line(second_gpio_socket[1], line, sizeof line);
    CHECK(second_gpio_ctx.subscribed == 1,
          "a second GPIO subscriber shares the prepared watch\n");
    gpio_test_broadcast_invalid_generation(23);
    struct pollfd stale_record = { .fd = second_gpio_socket[1], .events = POLLIN };
    CHECK(poll(&stale_record, 1, 100) == 0,
          "a replaced watch generation cannot publish stale value or unavailable records\n");
    gpio_unsubscribe(gpio_socket[0]);
    close(gpio_socket[0]);
    close(gpio_socket[1]);
    CHECK(gpio_reset_watches() != 0,
          "disconnecting one of two subscribers preserves the shared watch\n");
    CHECK(gpio_watch(23) == 0 && gpio_value_open_count == 1,
          "the shared watch remains idempotent until the final subscriber leaves\n");
    char active_edge[16];
    first_line(gpio_edge_fixture, active_edge, sizeof active_edge);
    CHECK(strcmp(active_edge, "both") == 0,
          "the shared watch keeps its active edge mode (got '%s')\n", active_edge);
    gpio_unsubscribe(second_gpio_socket[0]);
    close(second_gpio_socket[0]);
    close(second_gpio_socket[1]);

    char auto_restored_edge[16];
    first_line(gpio_edge_fixture, auto_restored_edge, sizeof auto_restored_edge);
    CHECK(strcmp(auto_restored_edge, "none") == 0,
          "the final GPIO subscriber restores the prior edge mode (got '%s')\n", auto_restored_edge);
    CHECK(gpio_watch(23) == 0 && gpio_value_open_count == 2,
          "the final GPIO subscriber clears watches so a later watch reopens the descriptor\n");

    int evdev_socket[2];
    socketpair(AF_UNIX, SOCK_STREAM, 0, evdev_socket);
    conn_ctx evdev_ctx = { .fd = evdev_socket[0], .subscribed = 0 };
    char evdev_subscribe[] = "SUBSCRIBE";
    dispatch(&evdev_ctx, evdev_subscribe);
    read_reply_line(evdev_socket[1], line, sizeof line);
    CHECK(strcmp(line, "OK\n") == 0, "evdev subscriber test precondition admitted (got '%s')\n", line);
    CHECK(gpio_reset_watches() == 0,
          "an evdev subscriber does not block the independent GPIORESET domain\n");
    input_unsubscribe(evdev_socket[0]);
    close(evdev_socket[0]);
    close(evdev_socket[1]);

    char restored_edge[16];
    first_line(gpio_edge_fixture, restored_edge, sizeof restored_edge);
    CHECK(strcmp(restored_edge, "none") == 0,
          "GPIORESET restores the edge mode that preceded the watch (got '%s')\n", restored_edge);

    gpio_init();
    gpio_value_open_ok = 0;
    CHECK(gpio_watch(24) != 0, "GPIOWATCH does not acknowledge an unreadable value descriptor\n");
    gpio_value_open_ok = 1;
    CHECK(gpio_watch(24) == 0, "an initial value-open failure rolls back so GPIOWATCH can retry\n");
    gpio_init();

    gpio_edge_open_ok = 0;
    CHECK(gpio_watch(24) == 0,
          "GPIOWATCH safely falls back to bounded descriptor sampling when edge setup is unavailable\n");
    gpio_edge_open_ok = 1;
    gpio_init();

    gpio_edge_open_ok = 0;
    gpio_value_open_count = 0;
    sysexec_stub_set_spawn_real(1);
    CHECK(gpio_watch(26) == 0, "the production GPIO reader starts for fallback-stream coverage\n");
    int stream_socket[2];
    socketpair(AF_UNIX, SOCK_STREAM, 0, stream_socket);
    conn_ctx stream_ctx = { .fd = stream_socket[0], .subscribed = 0 };
    char stream_subscribe[] = "GPIOSUBSCRIBE";
    dispatch(&stream_ctx, stream_subscribe);
    read_reply_line(stream_socket[1], line, sizeof line);
    read_reply_line(stream_socket[1], line, sizeof line);
    CHECK(strcmp(line, "GPIO 26 0\n") == 0, "fallback stream starts with the current value (got '%s')\n", line);
    CHECK(replace_gpio_fixture(gpio_value_fixture, "1\n") == 0, "GPIO fixture changed in place\n");
    struct pollfd changed = { .fd = stream_socket[1], .events = POLLIN };
    CHECK(poll(&changed, 1, 1500) == 1, "fallback reader reports a change within its bounded interval\n");
    read_reply_line(stream_socket[1], line, sizeof line);
    CHECK(strcmp(line, "GPIO 26 1\n") == 0, "fallback reader normalizes a changed value (got '%s')\n", line);
    CHECK(gpio_value_open_count == 1,
          "fallback samples reuse the held descriptor instead of reopening (opened %d)\n",
          gpio_value_open_count);
    changed.revents = 0;
    CHECK(poll(&changed, 1, 650) == 0, "an unchanged GPIO sample does not emit a duplicate record\n");
    CHECK(replace_gpio_fixture(gpio_value_fixture, "") == 0, "GPIO fixture can simulate descriptor loss\n");
    changed.revents = 0;
    CHECK(poll(&changed, 1, 1500) == 1, "descriptor loss emits bounded unavailability\n");
    read_reply_line(stream_socket[1], line, sizeof line);
    CHECK(strcmp(line, "GPIOUNAVAILABLE 26\n") == 0,
          "subscriber receives explicit GPIO unavailability (got '%s')\n", line);
    CHECK(replace_gpio_fixture(gpio_value_fixture, "0\n") == 0,
          "GPIO fixture is available for the fallback reader to reopen\n");
    changed.revents = 0;
    CHECK(poll(&changed, 1, 3500) == 1, "fallback reader reports recovery after descriptor loss\n");
    read_reply_line(stream_socket[1], line, sizeof line);
    CHECK(strcmp(line, "GPIO 26 0\n") == 0,
          "subscriber receives the recovered GPIO value (got '%s')\n", line);

    int opens_before_dead_subscriber = gpio_value_open_count;
    close(stream_socket[1]);
    CHECK(replace_gpio_fixture(gpio_value_fixture, "1\n") == 0,
          "GPIO fixture changes after its subscriber dies\n");
    int reopened_after_eviction = 0;
    for (int attempt = 0; attempt < 40; attempt++) {
        if (gpio_watch(26) == 0 && gpio_value_open_count > opens_before_dead_subscriber) {
            reopened_after_eviction = 1;
            break;
        }
        usleep(50000);
    }
    CHECK(reopened_after_eviction,
          "evicting the final dead subscriber clears watches and permits a fresh descriptor\n");
    gpio_unsubscribe(stream_socket[0]);
    close(stream_socket[0]);
    CHECK(gpio_watch(26) == 0 && gpio_value_open_count == opens_before_dead_subscriber + 1,
          "teardown of an already-evicted subscriber cannot clear the replacement watch\n");
    gpio_init();
    usleep(600000); // let the detached reader observe its generation change before reusing the fixture
    sysexec_stub_set_spawn_real(0);
    gpio_edge_open_ok = 1;
    CHECK(replace_gpio_fixture(gpio_value_fixture, "0\n") == 0, "GPIO fixture restored after stream test\n");

    sysexec_stub_set_spawn_result(-1);
    CHECK(gpio_watch(25) != 0, "GPIOWATCH rejects a failed reader-thread start\n");
    sysexec_stub_set_spawn_result(0);
    CHECK(gpio_watch(25) == 0, "thread-start failure rolls back so GPIOWATCH can retry\n");
    gpio_init();

    gpio_value_open_count = 0;
    for (unsigned i = 0; i < GPIO_MAX_WATCHES; i++)
        CHECK(gpio_watch(i) == 0, "GPIO watcher %u within the cap is admitted\n", i + 1);
    CHECK(gpio_watch(GPIO_MAX_WATCHES) != 0, "GPIO watcher beyond the fixed cap is rejected\n");
    CHECK(gpio_value_open_count == GPIO_MAX_WATCHES,
          "the rejected watcher consumes no descriptor (opened %d)\n", gpio_value_open_count);
    gpio_init();

    int sockets[GPIO_MAX_SUBSCRIBERS + 1][2];
    conn_ctx contexts[GPIO_MAX_SUBSCRIBERS + 1];
    CHECK(gpio_watch(27) == 0, "GPIO subscriber cap test establishes an owned watch\n");
    for (int i = 0; i <= GPIO_MAX_SUBSCRIBERS; i++) {
        socketpair(AF_UNIX, SOCK_STREAM, 0, sockets[i]);
        contexts[i] = (conn_ctx){ .fd = sockets[i][0], .subscribed = 0 };
        char command[] = "GPIOSUBSCRIBE";
        dispatch(&contexts[i], command);
        read_reply_line(sockets[i][1], line, sizeof line);
        if (i < GPIO_MAX_SUBSCRIBERS) {
            CHECK(strcmp(line, "OK\n") == 0 && contexts[i].subscribed == 1,
                  "GPIO subscriber %d within the cap is admitted (got '%s')\n", i + 1, line);
        } else {
            CHECK(strcmp(line, "ERR\n") == 0 && contexts[i].subscribed == 0,
                  "GPIO subscriber beyond the cap is rejected (got '%s')\n", line);
        }
    }
    for (int i = 0; i <= GPIO_MAX_SUBSCRIBERS; i++) {
        gpio_unsubscribe(sockets[i][0]);
        close(sockets[i][0]);
        close(sockets[i][1]);
    }
    gpio_init();
    sysexec_stub_reset();
    unlink(gpio_value_fixture);
    unlink(gpio_edge_fixture);
    gpio_value_fixture[0] = '\0';
    gpio_edge_fixture[0] = '\0';
}

static void test_subscriber_admission(void) {
    input_init();
    int sockets[INPUT_MAX_SUBSCRIBERS + 1][2];
    conn_ctx contexts[INPUT_MAX_SUBSCRIBERS + 1];
    for (int i = 0; i <= INPUT_MAX_SUBSCRIBERS; i++) {
        socketpair(AF_UNIX, SOCK_STREAM, 0, sockets[i]);
        contexts[i] = (conn_ctx){ .fd = sockets[i][0], .subscribed = 0 };
        char line[] = "SUBSCRIBE";
        dispatch(&contexts[i], line);
        char out[8] = "";
        ssize_t n = read(sockets[i][1], out, sizeof out - 1);
        out[n > 0 ? n : 0] = '\0';
        if (i < INPUT_MAX_SUBSCRIBERS) {
            CHECK(strcmp(out, "OK\n") == 0, "subscriber %d within cap gets OK (got '%s')\n", i + 1, out);
            CHECK(contexts[i].subscribed == 1, "admitted subscriber %d is timeout-exempt\n", i + 1);
        } else {
            CHECK(strcmp(out, "ERR\n") == 0, "subscriber beyond cap gets ERR (got '%s')\n", out);
            CHECK(contexts[i].subscribed == 0, "rejected subscriber is not timeout-exempt\n");
        }
    }

    input_unsubscribe(sockets[0][0]);
    char retry[] = "SUBSCRIBE";
    dispatch(&contexts[INPUT_MAX_SUBSCRIBERS], retry);
    char out[8] = "";
    ssize_t n = read(sockets[INPUT_MAX_SUBSCRIBERS][1], out, sizeof out - 1);
    out[n > 0 ? n : 0] = '\0';
    CHECK(strcmp(out, "OK\n") == 0, "unsubscribe releases one subscriber slot (got '%s')\n", out);

    for (int i = 0; i <= INPUT_MAX_SUBSCRIBERS; i++) {
        input_unsubscribe(sockets[i][0]);
        close(sockets[i][0]); close(sockets[i][1]);
    }
    input_init();
}

static void test_grab_subscription_ownership(void) {
    input_init();
    sysexec_stub_reset();
    sysexec_stub_set_spawn_result(0);
    evdev_open_ok = 1; evdev_grab_ok = 1;
    evdev_grab_acquire_count = 0; evdev_grab_release_count = 0;
    CHECK(input_watch("/dev/input/event6", 1) == 0, "grab-capable WATCH is established\n");
    CHECK(evdev_grab_acquire_count == 1 && evdev_grab_release_count == 1,
          "WATCH verifies grab capability but releases it before subscription (got %d/%d)\n",
          evdev_grab_acquire_count, evdev_grab_release_count);

    int sockets[2][2];
    conn_ctx contexts[2];
    for (int i = 0; i < 2; i++) {
        socketpair(AF_UNIX, SOCK_STREAM, 0, sockets[i]);
        contexts[i] = (conn_ctx){ .fd = sockets[i][0], .subscribed = 0 };
    }

    evdev_grab_ok = 0;
    char rejected[] = "SUBSCRIBE";
    dispatch(&contexts[0], rejected);
    char rejected_out[8] = "";
    ssize_t rejected_n = read(sockets[0][1], rejected_out, sizeof rejected_out - 1);
    rejected_out[rejected_n > 0 ? rejected_n : 0] = '\0';
    CHECK(strcmp(rejected_out, "ERR\n") == 0 && contexts[0].subscribed == 0,
          "SUBSCRIBE rejects a grab that can no longer be acquired (got '%s')\n", rejected_out);
    evdev_grab_ok = 1;

    for (int i = 0; i < 2; i++) {
        char subscribe[] = "SUBSCRIBE";
        dispatch(&contexts[i], subscribe);
        char out[8] = "";
        ssize_t n = read(sockets[i][1], out, sizeof out - 1);
        out[n > 0 ? n : 0] = '\0';
        CHECK(strcmp(out, "OK\n") == 0, "owned subscriber %d admitted (got '%s')\n", i + 1, out);
    }
    CHECK(evdev_grab_acquire_count == 2, "first subscriber acquires exactly one live grab (got %d)\n", evdev_grab_acquire_count);
    CHECK(input_reset_watches() != 0, "WATCHRESET refuses to disrupt an active subscriber\n");

    input_unsubscribe(sockets[0][0]);
    CHECK(evdev_grab_release_count == 1, "one remaining subscriber keeps the grab owned\n");
    input_unsubscribe(sockets[1][0]);
    CHECK(evdev_grab_release_count == 2, "last subscriber releases the live grab\n");

    for (int i = 0; i < 2; i++) { close(sockets[i][0]); close(sockets[i][1]); }
    input_init();
    sysexec_stub_reset();
}

// The SMT1019 LED path: per-channel ioctl on /dev/ledjni. Untestable on owned hardware, so pin the
// wire protocol here — the exact command numbers and the 0..255 -> 0..15 scaling. The expected values
// are HARDCODED (not pulled from led.c's #defines) so an accidental change to either fails this test.
static void test_ledjni_ioctl(void) {
    CHECK(led15(0) == 0,    "led15(0)==0\n");
    CHECK(led15(255) == 15, "led15(255)==15\n");
    CHECK(led15(128) == 7,  "led15(128)==7 (got %d)\n", led15(128));
    CHECK(led15(17) == 1,   "led15(17)==1 (got %d)\n", led15(17));
    CHECK(led15(-9) == 0,   "led15 clamps negative to 0\n");
    CHECK(led15(999) == 15, "led15 clamps over-range to 15\n");

    int fd = open("/dev/null", O_WRONLY);   // any fd; ioctl is wrapped, never reaches it

    cap_n = 0;
    CHECK(led_ledjni_rgb(fd, 255, 128, 0) == 0, "led_ledjni_rgb returns ok\n");
    CHECK(cap_n == 3, "rgb emits exactly 3 ioctls (got %d)\n", cap_n);
    CHECK(cap_cmd[0] == 0xa1 && cap_val[0] == 15, "R: cmd 0xa1, 255->15 (got 0x%lx/%d)\n", cap_cmd[0], cap_val[0]);
    CHECK(cap_cmd[1] == 0xa2 && cap_val[1] == 7,  "G: cmd 0xa2, 128->7 (got 0x%lx/%d)\n", cap_cmd[1], cap_val[1]);
    CHECK(cap_cmd[2] == 0xa3 && cap_val[2] == 0,  "B: cmd 0xa3, 0->0 (got 0x%lx/%d)\n", cap_cmd[2], cap_val[2]);

    cap_n = 0;
    CHECK(led_ledjni_off(fd) == 0, "led_ledjni_off returns ok\n");
    CHECK(cap_n == 1 && cap_cmd[0] == 0x99 && cap_val[0] == 0, "off: cmd 0x99, val 0 (got 0x%lx/%d)\n", cap_cmd[0], cap_val[0]);

    if (fd >= 0) close(fd);
}

// --- concurrent-connection cap (MAX_CONN) -------------------------------------------------------
// The accept loop admits at most MAX_CONN simultaneous connections (main.c), via server.c's
// conn_admit()/conn_release() gate. Fuzz/parsing tests can't reach this — the cap lives in the
// connection lifecycle, not the byte parser — so pin it three ways: (1) the exact boundary,
// deterministically; (2) concurrent rejection while MAX_CONN slots are genuinely held open by other
// threads; (3) that a hammer of racing admit/release never lets more than MAX_CONN through at once
// (the atomics are correct — a non-atomic gate would over-admit here).

// (2) holders: each admits, waits, then releases on a shared signal — so the cap is under real
// concurrent pressure when the main thread probes it.
static volatile int hold_admitted = 0;   // holders that have run conn_admit()
static volatile int hold_release  = 0;    // main thread sets this to let holders release
static volatile int hold_admit_ok = 1;   // cleared if any holder's admit was (wrongly) refused
static void *cap_holder(void *arg) {
    (void)arg;
    if (!conn_admit()) __atomic_store_n(&hold_admit_ok, 0, __ATOMIC_SEQ_CST);
    __atomic_add_fetch(&hold_admitted, 1, __ATOMIC_SEQ_CST);
    while (!__atomic_load_n(&hold_release, __ATOMIC_SEQ_CST)) sched_yield();
    conn_release();
    return NULL;
}

// (3) hammer: admit/release in a tight loop, tracking the high-water mark of simultaneously-admitted
// connections. A correct gate keeps that peak <= MAX_CONN under any interleaving.
#define CAP_THREADS 64
#define CAP_ITERS   4000
static volatile int cap_live = 0;         // connections currently past the gate (our own mirror)
static volatile int cap_peak = 0;         // high-water mark of cap_live
static void *cap_hammer(void *arg) {
    (void)arg;
    for (int i = 0; i < CAP_ITERS; i++) {
        if (conn_admit()) {
            int c = __atomic_add_fetch(&cap_live, 1, __ATOMIC_SEQ_CST);
            int peak;
            while ((peak = __atomic_load_n(&cap_peak, __ATOMIC_SEQ_CST)) < c &&
                   !__atomic_compare_exchange_n(&cap_peak, &peak, c, 0,
                                                __ATOMIC_SEQ_CST, __ATOMIC_SEQ_CST)) { }
            sched_yield();                 // widen the window so a racy gate would over-admit
            __atomic_sub_fetch(&cap_live, 1, __ATOMIC_SEQ_CST);
            conn_release();
        }
    }
    return NULL;
}

static void test_conn_cap(void) {
    CHECK(conn_active() == 0, "conn cap starts clean at 0 (got %d)\n", conn_active());

    // (1) Exact boundary, single-threaded: MAX_CONN admits succeed, the next is refused and leaks
    // nothing, one release frees exactly one slot, then it drains back to 0.
    for (int i = 0; i < MAX_CONN; i++)
        CHECK(conn_admit() == 1, "admit %d/%d within cap succeeds\n", i + 1, MAX_CONN);
    CHECK(conn_active() == MAX_CONN, "active == MAX_CONN when full (got %d)\n", conn_active());
    CHECK(conn_admit() == 0, "the (MAX_CONN+1)th admit is refused\n");
    CHECK(conn_active() == MAX_CONN, "a refused admit leaks no slot (got %d)\n", conn_active());
    conn_release();
    CHECK(conn_active() == MAX_CONN - 1, "release frees one slot (got %d)\n", conn_active());
    CHECK(conn_admit() == 1, "one admit is allowed again after a release\n");
    for (int i = 0; i < MAX_CONN; i++) conn_release();
    CHECK(conn_active() == 0, "drains back to 0 (got %d)\n", conn_active());

    // (2) Concurrent rejection: MAX_CONN threads hold slots open; a probe admit must be refused while
    // they hold, then everything releases cleanly.
    hold_admitted = 0; hold_release = 0; hold_admit_ok = 1;
    pthread_t holders[MAX_CONN];
    for (int i = 0; i < MAX_CONN; i++) pthread_create(&holders[i], NULL, cap_holder, NULL);
    while (__atomic_load_n(&hold_admitted, __ATOMIC_SEQ_CST) < MAX_CONN) sched_yield();
    CHECK(hold_admit_ok, "all MAX_CONN concurrent holders were admitted\n");
    CHECK(conn_active() == MAX_CONN, "MAX_CONN slots held concurrently (got %d)\n", conn_active());
    CHECK(conn_admit() == 0, "admit refused while MAX_CONN held by other threads\n");
    __atomic_store_n(&hold_release, 1, __ATOMIC_SEQ_CST);
    for (int i = 0; i < MAX_CONN; i++) pthread_join(holders[i], NULL);
    CHECK(conn_active() == 0, "all held slots released back to 0 (got %d)\n", conn_active());

    // (3) Race invariant: hammer admit/release from many threads; the gate must never let the live
    // count exceed MAX_CONN, and must not leak a slot afterwards.
    cap_live = 0; cap_peak = 0;
    pthread_t hammer[CAP_THREADS];
    for (int i = 0; i < CAP_THREADS; i++) pthread_create(&hammer[i], NULL, cap_hammer, NULL);
    for (int i = 0; i < CAP_THREADS; i++) pthread_join(hammer[i], NULL);
    CHECK(cap_peak <= MAX_CONN, "gate never admits more than MAX_CONN at once (peak %d)\n", cap_peak);
    CHECK(cap_peak >= 1, "the hammer actually admitted connections (peak %d)\n", cap_peak);
    CHECK(conn_active() == 0, "no slot leaked after the connection storm (got %d)\n", conn_active());
}

static void test_vi530x_start_contract(void) {
    // The driver rejects these out of order — CHIP_INIT before POWER_ON returns an error rather than
    // initialising anything — so the sequence is pinned, not merely present.
    CHECK(vi530x_test_start_request_count() == 4,
          "vi530x start sequence has exactly the four vendor steps\n");
    CHECK(vi530x_test_start_request(0) == _IO('p', 0x06), "vi530x starts with POWER_ON\n");
    CHECK(vi530x_test_start_request(1) == _IO('p', 0x07), "vi530x runs CHIP_INIT second\n");
    CHECK(vi530x_test_start_request(2) == _IOW('p', 0x01, uint32_t),
          "vi530x sets PERIOD third, before the sensor is running\n");
    CHECK(vi530x_test_start_request(3) == _IO('p', 0x08), "vi530x issues START last\n");
    CHECK(vi530x_test_default_period() == 30,
          "vi530x keeps the vendor's own period rather than an invented one\n");

    // Pin the REAL driver ABI, not a copy of it. sizeof alone is not enough: swapping two fields can
    // preserve the size while reading every value into the wrong place, and the ioctl number — which
    // encodes only the size — would not change either. Offsets are what actually catch that.
    CHECK(sizeof(struct vi530x_measurement) == 32,
          "vi530x measurement is still the vendor's 32-byte record\n");
    CHECK(offsetof(struct vi530x_measurement, range_tof) == 0, "range_tof stays first\n");
    CHECK(offsetof(struct vi530x_measurement, time_usec) == 4, "time_usec stays at 4\n");
    CHECK(offsetof(struct vi530x_measurement, range_noise) == 8, "range_noise stays at 8\n");
    CHECK(offsetof(struct vi530x_measurement, range_peak) == 12, "range_peak stays at 12\n");
    CHECK(offsetof(struct vi530x_measurement, range_confidence) == 16,
          "range_confidence stays at 16\n");
    CHECK(offsetof(struct vi530x_measurement, range_status) == 20, "range_status stays at 20\n");
    CHECK(offsetof(struct vi530x_measurement, range_integral) == 24, "range_integral stays at 24\n");
    CHECK(offsetof(struct vi530x_measurement, range_cg_count) == 28, "range_cg_count stays at 28\n");
    CHECK(VI530X_IOCTL_MZ_DATA == _IOR('p', 0x0a, struct vi530x_measurement),
          "vi530x read request is the vendor's MZ_DATA\n");

    char out[64];
    CHECK(vi530x_test_format(-1, 0, 0, out, sizeof out) && strcmp(out, "D=-1 S=0 C=0\n") == 0,
          "vi530x reports a negative range verbatim rather than clamping it\n");
    CHECK(vi530x_test_format(1234, 7, 900, out, sizeof out) &&
          strcmp(out, "D=1234 S=7 C=900\n") == 0, "vi530x wire format is D/S/C\n");
    CHECK(!vi530x_test_format(1234, 7, 900, out, 8),
          "vi530x refuses to emit a truncated reading\n");

    // Every panel that is not this board has no /dev/vi530x, and that must be an ordinary refusal.
    vi530x_test_reset();
    char reply[128];
    dispatch_reply("VI530X", reply, sizeof reply);
    CHECK(strcmp(reply, "ERR\n") == 0,
          "vi530x refuses cleanly where the sensor node is absent (got %s)\n", reply);
}

static void test_room_climate_input_allowlist(void) {
    const char *temp_name = NULL, *humidity_name = NULL;
    unsigned int temp_axis = 0, humidity_axis = 0;
    CHECK(cht8305_test_candidate_count() == 2,
          "room-climate input allowlist has exactly two proven layouts\n");
    CHECK(cht8305_test_candidate(0, &temp_name, &temp_axis, &humidity_name, &humidity_axis),
          "TPA10 climate layout exists\n");
    CHECK(strcmp(temp_name, "temperature") == 0 && temp_axis == ABS_THROTTLE &&
          strcmp(humidity_name, "humidity") == 0 && humidity_axis == ABS_THROTTLE,
          "TPA10 climate layout retains the two ABS_THROTTLE inputs\n");
    CHECK(cht8305_test_candidate(1, &temp_name, &temp_axis, &humidity_name, &humidity_axis),
          "ZX-SMT156 climate layout exists\n");
    CHECK(strcmp(temp_name, "sun-ths") == 0 && temp_axis == ABS_THROTTLE &&
          strcmp(humidity_name, "sun-hum") == 0 && humidity_axis == 0x1d,
          "ZX-SMT156 climate layout pins the reporter-proven names and axes\n");
    CHECK(!cht8305_test_candidate(2, &temp_name, &temp_axis, &humidity_name, &humidity_axis),
          "room-climate input allowlist rejects an unknown layout\n");
}

static void test_room_climate_event_node_names(void) {
    const char *accepted[] = { "event0", "event1", "event9", "event10", "event99", "event100", "event999" };
    for (size_t i = 0; i < sizeof accepted / sizeof accepted[0]; i++) {
        CHECK(cht8305_test_event_name(accepted[i]),
              "canonical room-climate event node %s is accepted\n", accepted[i]);
    }

    const char *rejected[] = {
        "", "event", "event00", "event01", "event000", "event001", "event1000",
        "event-1", "event1x", "eventx1", "event 1", "Event1", "mouse0", "event1/extra",
    };
    for (size_t i = 0; i < sizeof rejected / sizeof rejected[0]; i++) {
        CHECK(!cht8305_test_event_name(rejected[i]),
              "non-canonical room-climate event node %s is rejected\n", rejected[i]);
    }
}

static void test_room_climate_input_discovery(void) {
    long temp = 0, humidity = 0;
    const struct cht8305_test_input unrelated[] = {
        { "unrelated-touchscreen", 1, 9999 },
    };
    CHECK(!cht8305_test_resolve(unrelated, sizeof unrelated / sizeof unrelated[0],
                                &temp, &humidity),
          "unrelated input devices alone do not infer a room-climate layout\n");

    const struct cht8305_test_input tpa10[] = {
        { "temperature", 1, 2134 },
        { "unrelated-touchscreen", 1, 9999 },
        { "humidity", 1, 5678 },
    };
    CHECK(cht8305_test_resolve(tpa10, sizeof tpa10 / sizeof tpa10[0], &temp, &humidity) &&
          temp == 2134 && humidity == 5678,
          "an unambiguous TPA10 exact-name pair succeeds and ignores unrelated devices\n");

    const struct cht8305_test_input zx[] = {
        { "sun-hum", 1, 4321 },
        { "sun-ths", 1, -250 },
    };
    CHECK(cht8305_test_resolve(zx, sizeof zx / sizeof zx[0], &temp, &humidity) &&
          temp == -250 && humidity == 4321,
          "an unambiguous ZX-SMT156 exact-name pair succeeds independent of enumeration order\n");

    const struct cht8305_test_input duplicate_temp[] = {
        { "temperature", 1, 2100 },
        { "humidity", 1, 5000 },
        { "temperature", 1, 2200 },
    };
    CHECK(!cht8305_test_resolve(duplicate_temp,
                                sizeof duplicate_temp / sizeof duplicate_temp[0], &temp, &humidity),
          "duplicate exact temperature devices fail closed\n");

    const struct cht8305_test_input duplicate_humidity[] = {
        { "sun-ths", 1, 2100 },
        { "sun-hum", 1, 5000 },
        { "sun-hum", 1, 5100 },
    };
    CHECK(!cht8305_test_resolve(duplicate_humidity,
                                sizeof duplicate_humidity / sizeof duplicate_humidity[0],
                                &temp, &humidity),
          "duplicate exact humidity devices fail closed\n");

    const struct cht8305_test_input partial[] = {
        { "sun-ths", 1, 2100 },
    };
    CHECK(!cht8305_test_resolve(partial, sizeof partial / sizeof partial[0], &temp, &humidity),
          "a partial exact-name pair fails closed\n");

    const struct cht8305_test_input mixed[] = {
        { "temperature", 1, 2100 },
        { "sun-hum", 1, 5000 },
    };
    CHECK(!cht8305_test_resolve(mixed, sizeof mixed / sizeof mixed[0], &temp, &humidity),
          "a pair assembled from mixed vendor layouts fails closed\n");

    const struct cht8305_test_input complete_plus_partial[] = {
        { "temperature", 1, 2100 },
        { "humidity", 1, 5000 },
        { "sun-ths", 1, 2200 },
    };
    CHECK(!cht8305_test_resolve(complete_plus_partial,
                                sizeof complete_plus_partial / sizeof complete_plus_partial[0],
                                &temp, &humidity),
          "a complete pair mixed with a partial second layout fails closed\n");

    const struct cht8305_test_input both_complete[] = {
        { "temperature", 1, 2100 },
        { "humidity", 1, 5000 },
        { "sun-ths", 1, 2200 },
        { "sun-hum", 1, 5100 },
    };
    CHECK(!cht8305_test_resolve(both_complete,
                                sizeof both_complete / sizeof both_complete[0], &temp, &humidity),
          "two complete supported layouts are ambiguous and fail closed\n");

    const struct cht8305_test_input unreadable[] = {
        { "sun-ths", 1, 2100 },
        { "sun-hum", 0, 0 },
    };
    CHECK(!cht8305_test_resolve(unreadable, sizeof unreadable / sizeof unreadable[0],
                                &temp, &humidity),
          "an unreadable member of an exact-name pair fails closed\n");
}

// The identity-migration verbs. Their whole safety argument is that neither can be pointed at a
// package outside the two known ids and neither takes a free-form permission or setting name, so the
// assertions here are about the exact command each one runs and about everything it refuses.
#define LEGACY_ID "io.github.maxlyth.hapaneld"
#define SUCCESSOR_ID "io.panelassistant.android"

static int ran_argv(const char *path, const char *const argv[]) {
    return sysexec_stub_count_argv(path, argv, 1);
}

static void test_helper_status_verb(void) {
    char out[MAX_LINE + 2];
    const char *expected_packages = "PACKAGES=" LEGACY_ID "," SUCCESSOR_ID;

    dispatch_reply_as(HELPER_CALLER_SUCCESSOR, "HELPERSTATUS", out, sizeof out);
    CHECK(strstr(out, "HELPERSTATUS 1 BUILD=") == out,
          "HELPERSTATUS opens with its versioned record (got '%s')\n", out);
    CHECK(strstr(out, expected_packages) != NULL,
          "HELPERSTATUS reports BOTH accepted package ids, in table order (got '%s')\n", out);
    CHECK(strstr(out, "CALLER=SUCCESSOR") != NULL,
          "HELPERSTATUS names the successor as the authenticated caller (got '%s')\n", out);

    dispatch_reply_as(HELPER_CALLER_LEGACY, "HELPERSTATUS", out, sizeof out);
    CHECK(strstr(out, "CALLER=LEGACY") != NULL,
          "HELPERSTATUS names the legacy package as the authenticated caller (got '%s')\n", out);
    CHECK(strstr(out, expected_packages) != NULL,
          "the bridge sees both ids too — it must confirm the dual-uid helper before the handover (got '%s')\n", out);

    dispatch_reply_as(HELPER_CALLER_ROOT, "HELPERSTATUS", out, sizeof out);
    CHECK(strstr(out, "CALLER=ROOT") != NULL,
          "HELPERSTATUS names a root operator as ROOT (got '%s')\n", out);

    // The build id is the helper's source identity; the app compares it against the build it bundles.
    char expected_build[128];
    snprintf(expected_build, sizeof expected_build, "BUILD=%s ", helper_build_id());
    CHECK(strstr(out, expected_build) != NULL,
          "HELPERSTATUS carries the running helper's build id (got '%s')\n", out);

    dispatch_reply_as(HELPER_CALLER_LEGACY, "HELPERSTATUS extra", out, sizeof out);
    CHECK(strcmp(out, "ERR\n") == 0, "HELPERSTATUS requires an exact verb (got '%s')\n", out);

    // Through the real connection path, not just dispatch(): the identity the accept loop resolved
    // has to survive into conn_ctx, or every per-caller decision below it is reading a default.
    serve_reply("HELPERSTATUS\n", strlen("HELPERSTATUS\n"), out, sizeof out);
    CHECK(strstr(out, "CALLER=LEGACY") != NULL,
          "server_serve carries the connection's authenticated identity into conn_ctx (got '%s')\n", out);
}

static void test_uninstall_verb(void) {
    char out[MAX_LINE + 2];
    const char *const remove_successor[] = { "pm", "uninstall", SUCCESSOR_ID, NULL };
    const char *const remove_legacy[] = { "pm", "uninstall", LEGACY_ID, NULL };
    const char *const remove_other[] = { "pm", "uninstall", "com.other.app", NULL };

    // The successor removes the legacy package once its own handover is confirmed. That is the one
    // use this verb exists for, and it is the only direction each caller can drive.
    sysexec_stub_reset();
    dispatch_reply_as(HELPER_CALLER_SUCCESSOR, "UNINSTALL " LEGACY_ID, out, sizeof out);
    CHECK(strcmp(out, "OK\n") == 0, "the successor may remove the legacy package (got '%s')\n", out);
    CHECK(ran_argv("/system/bin/pm", remove_legacy) == 1,
          "UNINSTALL runs exactly `pm uninstall <legacy id>`\n");

    sysexec_stub_reset();
    dispatch_reply_as(HELPER_CALLER_LEGACY, "UNINSTALL " SUCCESSOR_ID, out, sizeof out);
    CHECK(strcmp(out, "OK\n") == 0, "the bridge may remove an abandoned successor (got '%s')\n", out);
    CHECK(ran_argv("/system/bin/pm", remove_successor) == 1,
          "UNINSTALL runs exactly `pm uninstall <successor id>`\n");

    // A request to delete the requester is either a mistake or a compromised caller; the migration
    // never needs it.
    sysexec_stub_reset();
    dispatch_reply_as(HELPER_CALLER_SUCCESSOR, "UNINSTALL " SUCCESSOR_ID, out, sizeof out);
    CHECK(strcmp(out, "ERR\n") == 0, "a caller may not uninstall its own package (got '%s')\n", out);
    CHECK(sysexec_stub_count_argv_calls() == 0, "a refused UNINSTALL executes nothing at all\n");

    sysexec_stub_reset();
    dispatch_reply_as(HELPER_CALLER_LEGACY, "UNINSTALL " LEGACY_ID, out, sizeof out);
    CHECK(strcmp(out, "ERR\n") == 0,
          "the legacy caller may not uninstall the legacy package (got '%s')\n", out);
    CHECK(sysexec_stub_count_argv_calls() == 0, "a refused self-UNINSTALL executes nothing at all\n");

    // Nothing outside the two known ids is reachable. This is the property that makes a root
    // uninstall verb safe to expose on this socket at all.
    sysexec_stub_reset();
    dispatch_reply_as(HELPER_CALLER_LEGACY, "UNINSTALL com.other.app", out, sizeof out);
    CHECK(strcmp(out, "ERR\n") == 0, "UNINSTALL refuses a third package (got '%s')\n", out);
    CHECK(ran_argv("/system/bin/pm", remove_other) == 0, "a refused UNINSTALL never reaches pm\n");

    sysexec_stub_reset();
    dispatch_reply_as(HELPER_CALLER_LEGACY, "UNINSTALL com.android.systemui", out, sizeof out);
    CHECK(strcmp(out, "ERR\n") == 0, "UNINSTALL refuses a system package (got '%s')\n", out);

    sysexec_stub_reset();
    dispatch_reply_as(HELPER_CALLER_LEGACY, "UNINSTALL", out, sizeof out);
    CHECK(strcmp(out, "ERR\n") == 0, "UNINSTALL requires a package (got '%s')\n", out);
    dispatch_reply_as(HELPER_CALLER_LEGACY, "UNINSTALL " SUCCESSOR_ID " extra", out, sizeof out);
    CHECK(strcmp(out, "ERR\n") == 0, "UNINSTALL rejects a trailing argument (got '%s')\n", out);
    dispatch_reply_as(HELPER_CALLER_LEGACY, "UNINSTALL " SUCCESSOR_ID ";reboot", out, sizeof out);
    CHECK(strcmp(out, "ERR\n") == 0, "UNINSTALL rejects package metacharacters (got '%s')\n", out);
    CHECK(sysexec_stub_count_argv_calls() == 0, "no malformed UNINSTALL executes anything\n");
}

static void test_grant_verb(void) {
    char out[MAX_LINE + 2];
    const char *const notifications[] = {
        "pm", "grant", SUCCESSOR_ID, "android.permission.POST_NOTIFICATIONS", NULL
    };
    const char *const microphone[] = { "pm", "grant", SUCCESSOR_ID, "android.permission.RECORD_AUDIO", NULL };
    const char *const camera[] = { "pm", "grant", SUCCESSOR_ID, "android.permission.CAMERA", NULL };
    const char *const write_settings[] = { "appops", "set", SUCCESSOR_ID, "WRITE_SETTINGS", "allow", NULL };
    const char *const overlay[] = { "appops", "set", SUCCESSOR_ID, "SYSTEM_ALERT_WINDOW", "allow", NULL };
    const char *const battery_modern[] = { "cmd", "deviceidle", "whitelist", "+" SUCCESSOR_ID, NULL };
    const char *const battery_legacy[] = { "dumpsys", "deviceidle", "whitelist", "+" SUCCESSOR_ID, NULL };

    sysexec_stub_reset();
    dispatch_reply_as(HELPER_CALLER_SUCCESSOR, "GRANT " SUCCESSOR_ID " NOTIFICATIONS", out, sizeof out);
    CHECK(strcmp(out, "OK\n") == 0, "GRANT NOTIFICATIONS succeeds (got '%s')\n", out);
    CHECK(ran_argv("/system/bin/pm", notifications) == 1, "GRANT NOTIFICATIONS runs the exact pm grant\n");

    sysexec_stub_reset();
    dispatch_reply_as(HELPER_CALLER_SUCCESSOR, "GRANT " SUCCESSOR_ID " MICROPHONE", out, sizeof out);
    CHECK(ran_argv("/system/bin/pm", microphone) == 1, "GRANT MICROPHONE runs the exact pm grant\n");

    sysexec_stub_reset();
    dispatch_reply_as(HELPER_CALLER_SUCCESSOR, "GRANT " SUCCESSOR_ID " CAMERA", out, sizeof out);
    CHECK(strcmp(out, "OK\n") == 0, "GRANT CAMERA succeeds (got '%s')\n", out);
    CHECK(ran_argv("/system/bin/pm", camera) == 1, "GRANT CAMERA runs the exact pm grant\n");
    sysexec_stub_reset();
    sysexec_stub_fail_run("pm grant " SUCCESSOR_ID " android.permission.CAMERA", 256);
    dispatch_reply_as(HELPER_CALLER_SUCCESSOR, "GRANT " SUCCESSOR_ID " CAMERA", out, sizeof out);
    CHECK(strcmp(out, "ERR\n") == 0, "GRANT CAMERA reports a failed pm grant (got '%s')\n", out);

    sysexec_stub_reset();
    dispatch_reply_as(HELPER_CALLER_SUCCESSOR, "GRANT " SUCCESSOR_ID " WRITESETTINGS", out, sizeof out);
    CHECK(ran_argv("/system/bin/appops", write_settings) == 1, "GRANT WRITESETTINGS runs the exact appop\n");

    sysexec_stub_reset();
    dispatch_reply_as(HELPER_CALLER_SUCCESSOR, "GRANT " SUCCESSOR_ID " OVERLAY", out, sizeof out);
    CHECK(ran_argv("/system/bin/appops", overlay) == 1, "GRANT OVERLAY runs the exact appop\n");

    // `cmd deviceidle` is the modern path; some vendor builds still need the dumpsys form, so the
    // fallback must be reached only when the first one fails.
    sysexec_stub_reset();
    dispatch_reply_as(HELPER_CALLER_SUCCESSOR, "GRANT " SUCCESSOR_ID " BATTERY", out, sizeof out);
    CHECK(strcmp(out, "OK\n") == 0, "GRANT BATTERY succeeds on the modern path (got '%s')\n", out);
    CHECK(ran_argv("/system/bin/cmd", battery_modern) == 1, "GRANT BATTERY whitelists through `cmd deviceidle`\n");
    CHECK(ran_argv("/system/bin/dumpsys", battery_legacy) == 0,
          "GRANT BATTERY does not run the fallback when the modern path works\n");

    sysexec_stub_reset();
    sysexec_stub_fail_run("cmd deviceidle", 256);
    dispatch_reply_as(HELPER_CALLER_SUCCESSOR, "GRANT " SUCCESSOR_ID " BATTERY", out, sizeof out);
    CHECK(strcmp(out, "OK\n") == 0, "GRANT BATTERY falls back to dumpsys (got '%s')\n", out);
    CHECK(ran_argv("/system/bin/dumpsys", battery_legacy) == 1,
          "GRANT BATTERY reaches the dumpsys form when `cmd deviceidle` fails\n");

    // Both ids are grantable — the bridge re-grants for itself after an upgrade, the successor grants
    // for itself during the handover.
    sysexec_stub_reset();
    const char *const legacy_notifications[] = {
        "pm", "grant", LEGACY_ID, "android.permission.POST_NOTIFICATIONS", NULL
    };
    dispatch_reply_as(HELPER_CALLER_LEGACY, "GRANT " LEGACY_ID " NOTIFICATIONS", out, sizeof out);
    CHECK(ran_argv("/system/bin/pm", legacy_notifications) == 1, "GRANT serves the legacy id too\n");

    // Refusals: a third package, and any capability outside the fixed table. A free-form permission
    // name must never reach `pm grant`.
    sysexec_stub_reset();
    dispatch_reply_as(HELPER_CALLER_LEGACY, "GRANT com.other.app NOTIFICATIONS", out, sizeof out);
    CHECK(strcmp(out, "ERR\n") == 0, "GRANT refuses a third package (got '%s')\n", out);
    CHECK(sysexec_stub_count_argv_calls() == 0, "a refused GRANT executes nothing at all\n");

    sysexec_stub_reset();
    dispatch_reply_as(HELPER_CALLER_LEGACY, "GRANT " SUCCESSOR_ID " WRITE_SECURE_SETTINGS", out, sizeof out);
    CHECK(strcmp(out, "ERR\n") == 0, "GRANT refuses a capability outside its table (got '%s')\n", out);
    CHECK(sysexec_stub_count_argv_calls() == 0, "an unknown capability never reaches an actuator\n");

    sysexec_stub_reset();
    dispatch_reply_as(HELPER_CALLER_LEGACY, "GRANT " SUCCESSOR_ID, out, sizeof out);
    CHECK(strcmp(out, "ERR\n") == 0, "GRANT requires a capability (got '%s')\n", out);
    dispatch_reply_as(HELPER_CALLER_LEGACY, "GRANT " SUCCESSOR_ID " OVERLAY extra", out, sizeof out);
    CHECK(strcmp(out, "ERR\n") == 0, "GRANT rejects a trailing argument (got '%s')\n", out);
    CHECK(sysexec_stub_count_argv_calls() == 0, "no malformed GRANT executes anything\n");
}

static void test_grant_accessibility(void) {
    char out[MAX_LINE + 2];
    // The class lives in the Kotlin package io.panelassistant.android whichever id the build carries,
    // so a component is the id plus that fully qualified class. The `.input.…` shorthand resolves
    // against the id, so it names the class only for the successor.
    const char *component = SUCCESSOR_ID "/io.panelassistant.android.input.PanelAccessibilityService";
    const char *const enable_flag[] = {
        "settings", "put", "secure", "accessibility_enabled", "1", NULL
    };

    // Unset: `settings get` prints "null", which is not a list containing an entry called "null".
    sysexec_stub_reset();
    sysexec_stub_add_popen("enabled_accessibility_services", "null\n", 0);
    dispatch_reply_as(HELPER_CALLER_SUCCESSOR, "GRANT " SUCCESSOR_ID " ACCESSIBILITY", out, sizeof out);
    CHECK(strcmp(out, "OK\n") == 0, "GRANT ACCESSIBILITY succeeds from an unset list (got '%s')\n", out);
    const char *const put_only[] = {
        "settings", "put", "secure", "enabled_accessibility_services", component, NULL
    };
    CHECK(ran_argv("/system/bin/settings", put_only) == 1,
          "an unset list is replaced by the component alone, never the string \"null\"\n");
    CHECK(ran_argv("/system/bin/settings", enable_flag) == 1, "GRANT ACCESSIBILITY sets accessibility_enabled\n");

    // Another service is already bound: the list is shared, so this is a read-modify-write.
    sysexec_stub_reset();
    sysexec_stub_add_popen("enabled_accessibility_services", "com.vendor.app/.TalkBack\n", 0);
    dispatch_reply_as(HELPER_CALLER_SUCCESSOR, "GRANT " SUCCESSOR_ID " ACCESSIBILITY", out, sizeof out);
    char appended[512];
    snprintf(appended, sizeof appended, "com.vendor.app/.TalkBack:%s", component);
    const char *const put_appended[] = {
        "settings", "put", "secure", "enabled_accessibility_services", appended, NULL
    };
    CHECK(ran_argv("/system/bin/settings", put_appended) == 1,
          "GRANT ACCESSIBILITY appends to the shared list instead of overwriting it\n");

    // Idempotence: a retry of an interrupted grant must converge, not append a second copy.
    // Granting when already present: read the list, then assert accessibility_enabled. No write.
    sysexec_stub_reset();
    char already[512];
    snprintf(already, sizeof already, "%s\n", component);
    sysexec_stub_add_popen("enabled_accessibility_services", already, 0);
    dispatch_reply_as(HELPER_CALLER_SUCCESSOR, "GRANT " SUCCESSOR_ID " ACCESSIBILITY", out, sizeof out);
    CHECK(strcmp(out, "OK\n") == 0, "a repeated GRANT ACCESSIBILITY still reports success (got '%s')\n", out);
    CHECK(sysexec_stub_count_argv_calls() == 2,
          "a repeated GRANT ACCESSIBILITY reads the list and enables, and writes NO list (ran %d calls)\n",
          sysexec_stub_count_argv_calls());
    CHECK(ran_argv("/system/bin/settings", enable_flag) == 1,
          "a repeated GRANT ACCESSIBILITY still asserts accessibility_enabled\n");

    // An installer that wrote the shorthand spelling names the same component, so a panel set up that
    // way must not gain a duplicate entry.
    sysexec_stub_reset();
    sysexec_stub_add_popen("enabled_accessibility_services", SUCCESSOR_ID "/.input.PanelAccessibilityService\n", 0);
    dispatch_reply_as(HELPER_CALLER_SUCCESSOR, "GRANT " SUCCESSOR_ID " ACCESSIBILITY", out, sizeof out);
    CHECK(strcmp(out, "OK\n") == 0, "the shorthand spelling is recognised as already granted (got '%s')\n", out);
    CHECK(sysexec_stub_count_argv_calls() == 2,
          "a list already carrying the shorthand is left alone (ran %d calls)\n",
          sysexec_stub_count_argv_calls());

    // An app updated from a build whose classes lived in io.github.maxlyth.hapaneld leaves an entry
    // naming a class that no longer exists. Preserve it and the unrelated vendor service while
    // appending the actual class, then a retry must leave that repaired list alone.
    const char *invalid = "com.vendor/.A:" SUCCESSOR_ID "/io.github.maxlyth.hapaneld.input.PanelAccessibilityService";
    char repaired[512];
    snprintf(repaired, sizeof repaired, "%s:%s", invalid, component);
    const char *const put_repaired[] = {
        "settings", "put", "secure", "enabled_accessibility_services", repaired, NULL
    };
    sysexec_stub_reset();
    sysexec_stub_add_popen("enabled_accessibility_services", invalid, 0);
    dispatch_reply_as(HELPER_CALLER_SUCCESSOR, "GRANT " SUCCESSOR_ID " ACCESSIBILITY", out, sizeof out);
    CHECK(strcmp(out, "OK\n") == 0 && sysexec_stub_count_argv_calls() == 3,
          "an entry for the pre-move class needs the real service appended before accessibility is enabled\n");
    CHECK(ran_argv("/system/bin/settings", put_repaired) == 1,
          "the pre-move entry repair preserves the complete shared list and actual class\n");

    sysexec_stub_reset();
    sysexec_stub_add_popen("enabled_accessibility_services", invalid, 0);
    sysexec_stub_fail_run("settings put secure enabled_accessibility_services", 256);
    dispatch_reply_as(HELPER_CALLER_SUCCESSOR, "GRANT " SUCCESSOR_ID " ACCESSIBILITY", out, sizeof out);
    CHECK(strcmp(out, "ERR\n") == 0,
          "a failed accessibility list write reports ERR\n");
    CHECK(ran_argv("/system/bin/settings", enable_flag) == 0,
          "a failed accessibility list write never enables accessibility\n");

    sysexec_stub_reset();
    sysexec_stub_add_popen("enabled_accessibility_services", repaired, 0);
    dispatch_reply_as(HELPER_CALLER_SUCCESSOR, "GRANT " SUCCESSOR_ID " ACCESSIBILITY", out, sizeof out);
    CHECK(strcmp(out, "OK\n") == 0 && sysexec_stub_count_argv_calls() == 2,
          "the pre-move entry repair converges without duplicating or rewriting the shared list\n");

    // A setting this daemon cannot account for is not something root hands back to the framework.
    sysexec_stub_reset();
    sysexec_stub_add_popen("enabled_accessibility_services", "com.vendor/.A;rm -rf /\n", 0);
    dispatch_reply_as(HELPER_CALLER_SUCCESSOR, "GRANT " SUCCESSOR_ID " ACCESSIBILITY", out, sizeof out);
    CHECK(strcmp(out, "ERR\n") == 0,
          "GRANT ACCESSIBILITY refuses an implausible existing list (got '%s')\n", out);
    CHECK(ran_argv("/system/bin/settings", enable_flag) == 0,
          "a refused GRANT ACCESSIBILITY never enables accessibility either\n");

    // An unreadable setting fails closed rather than guessing the list was empty.
    sysexec_stub_reset();
    dispatch_reply_as(HELPER_CALLER_SUCCESSOR, "GRANT " SUCCESSOR_ID " ACCESSIBILITY", out, sizeof out);
    CHECK(strcmp(out, "ERR\n") == 0,
          "GRANT ACCESSIBILITY fails closed when the list cannot be read (got '%s')\n", out);
}

int main(int argc, char **argv) {
    if (argc == 4 && strcmp(argv[2], "--bridge-fixture") == 0) {
        int fd = atoi(argv[3]);
        if (fd < 3 || write(fd, "R", 1) != 1) return 1;
        close(fd);
        sleep(30);
        return 0;
    }
    test_guard_test_parent();
    guard_test_reset();
    CHECK(guard_test_reconcile() == 0, "Guard package gate initializes empty for legacy unit cases\n");
    test_validators();
    test_clamp();
    test_stat_jiffies();
    test_panel_bridge_cmdline();
    test_perfdump_bridge_protocol();
    test_dispatch_exact_match();
    test_zigbeecontain_layouts();
    test_reboot_escalation();
    test_keyevent_named_keys_only();
    test_sysctl_execution_results();
    test_bootchime_protocol();
    test_screencap_stream_writes();
    test_line_accumulator();
    test_reply_stream_writes();
    test_server_send_deadline();
    test_input_watch_contract();
    test_gpio_watch_contract();
    test_subscriber_admission();
    test_grab_subscription_ownership();
    test_ledjni_ioctl();
    test_conn_cap();
    test_room_climate_input_allowlist();
    test_vi530x_start_contract();
    test_room_climate_event_node_names();
    test_room_climate_input_discovery();
    test_helper_status_verb();
    test_uninstall_verb();
    test_grant_verb();
    test_grant_accessibility();

    if (failures) { printf("UNIT FAILED: %d assertion(s)\n", failures); return 1; }
    printf("UNIT OK\n");
    return 0;
}
