// Host-native unit tests for the hapaneld-helper daemon's pure logic. The sanitizer smoke exercises
// hostile parser inputs; these assert it produces the CORRECT result — the validators reject what
// they should, the byte clamp and /proc parser compute the right values, dispatch routes verbs by
// EXACT match (so SCREEN/SCREENCAP and OFF/OFFOFF can't collide), and the line accumulator reassembles
// split reads and drops overlong lines. Linked against the real src/*.c modules + the sysexec stub.
//
// A clean run prints "UNIT OK" and exits 0; any failed assertion prints the case and exits 1.
#define _GNU_SOURCE
#include "test_tmp.h"
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
#include <sys/file.h>
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
#include "pa_commands.h"
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
static char evdev_fixture[128];
static int govread_enabled, govread_open_error, govread_open_count[2], govread_fd = -1;
static char govread_fixtures[2][128];
static int govread_steps[8];
static size_t govread_step_count, govread_step_index;
ssize_t __real_read(int fd, void *buf, size_t count);

// Script only the fd returned for a fixed governor node; transport and other readers stay real.
ssize_t __wrap_read(int fd, void *buf, size_t count) {
    if (govread_enabled && fd == govread_fd && govread_step_index < govread_step_count) {
        int step = govread_steps[govread_step_index++];
        if (step < 0) { errno = step == -1 ? EINTR : EIO; return -1; }
        if (count > (size_t)step) count = (size_t)step;
    }
    return __real_read(fd, buf, count);
}

#ifdef __GLIBC__
// Fortified glibc routes a bounded stack-buffer read through this symbol instead of read().
ssize_t __real___read_chk(int fd, void *buf, size_t count, size_t capacity);
ssize_t __wrap___read_chk(int fd, void *buf, size_t count, size_t capacity) {
    if (govread_enabled && fd == govread_fd && count <= capacity) return __wrap_read(fd, buf, count);
    return __real___read_chk(fd, buf, count, capacity);
}
#endif

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
    // Host tests must never change any real CPU's governor, even when sysfs is writable.
    const char suffix[] = "/cpufreq/scaling_governor";
    size_t path_size = strlen(path);
    if ((flags & O_ACCMODE) != O_RDONLY &&
        strncmp(path, "/sys/devices/system/cpu/", 24) == 0 &&
        path_size >= sizeof suffix - 1 && strcmp(path + path_size - (sizeof suffix - 1), suffix) == 0) {
        errno = EACCES;
        return -1;
    }
    const char *selected = path;
    if (govread_enabled &&
        (strcmp(path, "/sys/devices/system/cpu/cpu0/cpufreq/scaling_governor") == 0 ||
         strcmp(path, "/sys/devices/system/cpu/cpu0/cpufreq/scaling_available_governors") == 0)) {
        int index = strstr(path, "scaling_available_governors") != NULL;
        govread_open_count[index]++;
        CHECK((flags & O_ACCMODE) == O_RDONLY, "GOVREAD opens its fixed nodes read-only\n");
        if (govread_open_error) { errno = EACCES; return -1; }
        govread_fd = __real_open(govread_fixtures[index], flags);
        return govread_fd;
    }
    if (strncmp(path, "/dev/input/event", 16) == 0) {
        evdev_open_count++;
        if (!evdev_open_ok) { errno = ENOENT; return -1; }
        selected = evdev_fixture[0] ? evdev_fixture : "/dev/null";
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
    dispatch_reply_as(HELPER_CALLER_SUCCESSOR, line, out, outsz);
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
    server_serve(sv[0], HELPER_CALLER_SUCCESSOR);
    fcntl(sv[1], F_SETFL, O_NONBLOCK);
    ssize_t n = read(sv[1], out, outsz - 1);
    out[n > 0 ? n : 0] = '\0';
    close(sv[0]); close(sv[1]);
}

static void test_validators(void) {
    // valid_pkg: package chars only; rejects shell metacharacters and traversal.
    CHECK(valid_pkg("com.foo.bar"), "valid_pkg accepts a package\n");
    CHECK(valid_pkg("io.panelassistant.android"), "valid_pkg accepts the app id\n");
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
    CHECK(is_critical_pkg("io.panelassistant.android"), "is_critical_pkg flags ourselves\n");
    CHECK(!is_critical_pkg("io.panelassistant.androidx"),
          "is_critical_pkg is exact for the app id, not a prefix\n");
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
    CHECK(valid_gbl_path("/data/user/0/io.panelassistant.android/cache/efr32.gbl"), "valid_gbl_path accepts app cache path\n");
    CHECK(!valid_gbl_path(""), "valid_gbl_path rejects empty\n");
    CHECK(!valid_gbl_path("relative/path.gbl"), "valid_gbl_path rejects non-absolute\n");
    CHECK(!valid_gbl_path("/data/../etc/passwd.gbl"), "valid_gbl_path rejects traversal\n");
    CHECK(!valid_gbl_path("/data/local/tmp/efr32.bin"), "valid_gbl_path rejects wrong extension\n");
    CHECK(!valid_gbl_path("/data/local/tmp/it'squoted.gbl"), "valid_gbl_path rejects single-quote\n");

    // valid_apk_path: an .apk under the app's own data dir, in either the legacy (/data/data) or
    // multi-user (/data/user/0) form.
    CHECK(valid_apk_path("/data/data/io.panelassistant.android/cache/update.apk"),
          "valid_apk_path accepts the app's /data/data form\n");
    CHECK(valid_apk_path("/data/user/0/io.panelassistant.android/cache/update.apk"),
          "valid_apk_path accepts the app's /data/user/0 form\n");
    // The separator after the directory is load-bearing: without it a sibling directory whose name
    // merely starts with the app id would pass as the app's private storage.
    CHECK(!valid_apk_path("/data/data/io.panelassistant.androidx/cache/update.apk"),
          "valid_apk_path rejects a directory that only prefixes the app id\n");
    CHECK(!valid_apk_path("/data/data/com.other.app/cache/update.apk"),
          "valid_apk_path rejects a third package's data dir\n");
    CHECK(!valid_apk_path("/data/local/tmp/update.apk"), "valid_apk_path rejects shared storage\n");
    CHECK(!valid_apk_path("/data/data/io.panelassistant.android/../../local/tmp/update.apk"),
          "valid_apk_path rejects traversal out of the app's data dir\n");
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
    CHECK(strcmp(helper_identity(), "HELPER version=1.17.0 proto=1.17") == 0,
          "helper identity is stable (got '%s')\n", helper_identity());
    dispatch_reply("VERSION", out, sizeof out);
    CHECK(strcmp(out, "HELPER version=1.17.0 proto=1.17\n") == 0,
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

static void govread_set(int index, const void *bytes, size_t size) {
    int fd = __real_open(govread_fixtures[index], O_WRONLY | O_TRUNC);
    CHECK(fd >= 0, "GOVREAD fixture opens for writing\n");
    if (fd < 0) return;
    CHECK(__real_write(fd, bytes, size) == (ssize_t)size, "GOVREAD fixture is complete\n");
    close(fd);
}

static void govread_begin(void) {
    govread_enabled = 1;
    govread_open_error = 0;
    govread_fd = -1;
    memset(govread_open_count, 0, sizeof govread_open_count);
    govread_step_count = govread_step_index = 0;
    for (int i = 0; i < 2; i++) {
        strcpy(govread_fixtures[i], "/tmp/hapaneld-govread-XXXXXX");
        int fd = mkstemp(govread_fixtures[i]);
        CHECK(fd >= 0, "GOVREAD fixture can be created\n");
        if (fd >= 0) close(fd);
    }
    govread_set(0, "schedutil\n", 10);
    govread_set(1, "performance powersave\n", 22);
    sysexec_stub_reset();
}

static void govread_end(void) {
    govread_enabled = 0;
    govread_fd = -1;
    for (int i = 0; i < 2; i++) unlink(govread_fixtures[i]);
}

static void test_govread_request_admission(void) {
    govread_begin();
    char out[64];
    const char *invalid[] = { "GOVREAD", "GOVREAD current", "GOVREAD CURRENT extra",
        "GOVREAD AVAILABLE extra", "GOVREAD CURRENT ", "GOVREAD AVAILABLE;reboot",
        "GOVREAD /proc/self/status" };
    for (size_t i = 0; i < sizeof invalid / sizeof invalid[0]; i++) {
        dispatch_reply(invalid[i], out, sizeof out);
        CHECK(strcmp(out, "ERR\n") == 0 && govread_open_count[0] == 0 && govread_open_count[1] == 0,
            "GOVREAD rejects unsupported selectors and extra arguments before reading\n");
    }
    govread_end();
}

static void test_govread_fixed_nodes(void) {
    govread_begin();
    char out[128];
    dispatch_reply("GOVREAD CURRENT", out, sizeof out);
    CHECK(strcmp(out, "GOVREAD schedutil\n") == 0 && govread_open_count[0] == 1 && govread_open_count[1] == 0,
        "GOVREAD CURRENT reads the current-governor node\n");
    const char available[] = " \tVendor.Fast-1_2\r\n powersave\tperformance \n";
    govread_set(1, available, sizeof available - 1);
    dispatch_reply("GOVREAD AVAILABLE", out, sizeof out);
    CHECK(strcmp(out, "GOVREAD Vendor.Fast-1_2 powersave performance\n") == 0 && govread_open_count[1] == 1,
        "GOVREAD AVAILABLE reads the available-governors node\n");
    govread_end();
}

static void test_govread_current_cardinality(void) {
    govread_begin();
    char out[64];
    govread_set(0, "performance powersave\n", 22);
    dispatch_reply("GOVREAD CURRENT", out, sizeof out);
    CHECK(strcmp(out, "ERR\n") == 0, "GOVREAD CURRENT rejects multiple tokens\n");
    for (int i = 0; i < 2; i++) {
        govread_set(i, " \t\r\n", 4);
        dispatch_reply(i ? "GOVREAD AVAILABLE" : "GOVREAD CURRENT", out, sizeof out);
        CHECK(strcmp(out, "ERR\n") == 0, "GOVREAD rejects an empty token list\n");
        govread_set(i, "", 0);
        dispatch_reply(i ? "GOVREAD AVAILABLE" : "GOVREAD CURRENT", out, sizeof out);
        CHECK(strcmp(out, "ERR\n") == 0, "GOVREAD rejects an empty node\n");
    }
    govread_end();
}

static void test_govread_invalid_content(void) {
    govread_begin();
    char out[64];
    const char invalid[][32] = { "sched/util", "schedutil;reboot", "schedutil\0powersave", "schedutil\v", "schedutil\177", "schedutil\200" };
    const size_t sizes[] = { 10, 16, 19, 10, 10, 10 };
    for (size_t i = 0; i < sizeof sizes / sizeof sizes[0]; i++) {
        govread_set(1, invalid[i], sizes[i]);
        dispatch_reply("GOVREAD AVAILABLE", out, sizeof out);
        CHECK(strcmp(out, "ERR\n") == 0, "GOVREAD rejects malformed governor bytes\n");
    }
    govread_end();
}

static void test_govread_token_bounds(void) {
    govread_begin();
    char token[64], expected[80], out[128];
    memset(token, 'a', sizeof token);
    govread_set(0, token, 63);
    snprintf(expected, sizeof expected, "GOVREAD %.*s\n", 63, token);
    dispatch_reply("GOVREAD CURRENT", out, sizeof out);
    CHECK(strcmp(out, expected) == 0, "GOVREAD accepts a 63-byte token\n");
    govread_set(0, token, sizeof token);
    dispatch_reply("GOVREAD CURRENT", out, sizeof out);
    CHECK(strcmp(out, "ERR\n") == 0, "GOVREAD rejects a 64-byte token\n");
    govread_end();
}

static void test_govread_raw_bound(void) {
    govread_begin();
    char bytes[4097], out[4200];
    memset(bytes, ' ', sizeof bytes);
    memcpy(bytes, "performance", 11);
    govread_set(0, bytes, 4095);
    dispatch_reply("GOVREAD CURRENT", out, sizeof out);
    CHECK(strcmp(out, "GOVREAD performance\n") == 0, "GOVREAD accepts 4095 complete raw bytes\n");
    for (size_t size = 4096; size <= sizeof bytes; size++) {
        govread_set(0, bytes, size);
        dispatch_reply("GOVREAD CURRENT", out, sizeof out);
        CHECK(strcmp(out, "ERR\n") == 0, "GOVREAD rejects raw input reaching the overflow probe\n");
    }
    // Largest normalized reply from valid 4095-byte input: 64 tokens of 63 bytes.
    for (size_t i = 0; i < 4095; i++) bytes[i] = i % 64 == 63 ? ' ' : 'a';
    govread_set(1, bytes, 4095);
    dispatch_reply("GOVREAD AVAILABLE", out, sizeof out);
    CHECK(strlen(out) == 4104 && memcmp(out, "GOVREAD ", 8) == 0 &&
        memcmp(out + 8, bytes, 4095) == 0 && out[4103] == '\n',
        "GOVREAD emits the complete maximum normalized reply\n");
    govread_end();
}

static void test_govread_read_failures(void) {
    govread_begin();
    char out[64];
    for (int i = 0; i < 2; i++) {
        govread_open_error = 1;
        dispatch_reply(i ? "GOVREAD AVAILABLE" : "GOVREAD CURRENT", out, sizeof out);
        CHECK(strcmp(out, "ERR\n") == 0, "GOVREAD reports denied fixed-node access\n");
    }
    govread_open_error = 0;
    govread_steps[0] = 4; govread_steps[1] = -2;
    govread_step_count = 2; govread_step_index = 0;
    dispatch_reply("GOVREAD CURRENT", out, sizeof out);
    CHECK(strcmp(out, "ERR\n") == 0, "GOVREAD discards a valid prefix when a later read fails (got %s; read steps %zu)\n", out, govread_step_index);
    govread_steps[0] = -1; govread_steps[1] = 2; govread_steps[2] = -1; govread_steps[3] = 3;
    govread_step_count = 4; govread_step_index = 0;
    dispatch_reply("GOVREAD CURRENT", out, sizeof out);
    CHECK(strcmp(out, "GOVREAD schedutil\n") == 0, "GOVREAD recovers from short reads and EINTR\n");
    govread_end();
}

static void test_govread_no_exec(void) {
    govread_begin();
    char out[64];
    dispatch_reply("GOVREAD CURRENT", out, sizeof out);
    dispatch_reply("GOVREAD AVAILABLE", out, sizeof out);
    dispatch_reply("GOVREAD CURRENT extra", out, sizeof out);
    govread_open_error = 1;
    dispatch_reply("GOVREAD CURRENT", out, sizeof out);
    dispatch_reply("GOVREAD AVAILABLE", out, sizeof out);
    CHECK(sysexec_stub_count_run("") == 0 && sysexec_stub_count_argv_calls() == 0,
        "GOVREAD never invokes subprocesses\n");
    govread_end();
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
    // RELOAD force-stops its target first, so it refuses a critical package as STOP does.
    sysexec_stub_reset();
    dispatch_reply("RELOAD com.android.systemui", out, sizeof out);
    CHECK(strcmp(out, "ERR\n") == 0, "RELOAD refuses a critical package (got '%s')\n", out);

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
    const char *install = "INSTALL /data/user/0/io.panelassistant.android/cache/update.apk";
    const char *install_dir = HAPANELD_TEST_TMP "/.hapaneld-helper-test";
    const char *install_stage = HAPANELD_TEST_TMP "/.hapaneld-helper-test/hapaneld-install.apk";
    unlink(install_stage);
    rmdir(install_dir);
    sysexec_stub_reset();
    dispatch_reply(install, out, sizeof out);
    CHECK(strcmp(out, "ERR\n") == 0, "legacy INSTALL fails closed (got '%s')\n", out);
    CHECK(sysexec_stub_count_run(install) == 0, "legacy INSTALL never executes caller-derived text\n");

    const char *install_gc = "INSTALLGC /data/user/0/io.panelassistant.android/files/helper-install-staging/retained.apk";
    dispatch_reply(install_gc, out, sizeof out);
    CHECK(strcmp(out, "OK\n") == 0, "INSTALLGC authorises retained-input cleanup while install lane is idle (got '%s')\n", out);
    dispatch_reply(install_gc, out, sizeof out);
    CHECK(strcmp(out, "OK\n") == 0, "duplicate INSTALLGC remains idempotent (got '%s')\n", out);
    dispatch_reply("INSTALL /data/user/0/io.panelassistant.android/files/helper-install-staging/retained.apk", out, sizeof out);
    CHECK(strcmp(out, "ERR\n") == 0, "a late legacy INSTALL remains rejected (got '%s')\n", out);
    dispatch_reply("INSTALLGC /data/local/tmp/not-owned.apk", out, sizeof out);
    CHECK(strcmp(out, "ERR\n") == 0, "INSTALLGC rejects paths outside app-private storage (got '%s')\n", out);

    // INSTALLSTREAM waits for READY before the client sends binary bytes, then installs only after the
    // exact declared length reaches root-owned staging. Block pm install so the staged bytes can be
    // inspected before terminal cleanup.
    const char *stream_stage = install_stage;
    const char *symlink_target = HAPANELD_TEST_TMP "/hapaneld-helper-install-stream-target";
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

    // Provider upgrades preserve Android's downgrade rejection. Exercise dispatch and exact argv,
    // including the completed upload, so a missing handler cannot pass by submitting nothing.
    sysexec_stub_reset();
    socketpair(AF_UNIX, SOCK_STREAM, 0, stream_sv);
    stream = (stream_dispatch_job){ .fd = stream_sv[0], .line = "INSTALLSTREAMUPGRADE 5" };
    pthread_create(&stream_thread, NULL, stream_dispatch_worker, &stream);
    read_reply_line(stream_sv[1], out, sizeof out);
    int upgrade_ready = strcmp(out, "READY\n") == 0;
    CHECK(upgrade_ready, "INSTALLSTREAMUPGRADE acknowledges before payload (got '%s')\n", out);
    if (upgrade_ready) {
        CHECK(write_all_fd(stream_sv[1], payload, sizeof payload) == 0,
              "INSTALLSTREAMUPGRADE client writes full payload\n");
        shutdown(stream_sv[1], SHUT_WR);
        read_reply_line(stream_sv[1], out, sizeof out);
        CHECK(strcmp(out, "OK\n") == 0, "INSTALLSTREAMUPGRADE reports package-manager success (got '%s')\n", out);
    }
    pthread_join(stream_thread, NULL);
    close(stream_sv[1]);
    const char *const upgrade_argv[] = { "pm", "install", "-r", stream_stage, NULL };
    CHECK(sysexec_stub_count_argv("/system/bin/pm", upgrade_argv, 1) == 1,
          "INSTALLSTREAMUPGRADE submits exactly one ordinary replacement without downgrade override\n");
    CHECK(sysexec_stub_count_argv_calls() == 1,
          "INSTALLSTREAMUPGRADE never retries another installer or removes the provider\n");
    CHECK(access(stream_stage, F_OK) != 0, "INSTALLSTREAMUPGRADE removes root staging after success\n");

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

// Streaming mechanics are covered against real sysexec.c in bounded_exec.c. This dispatcher test
// only owns the command selection and the raw-byte response at its injected actuator boundary.
static void test_screencap_command(void) {
    const char payload[] = "PNG-fixture-payload";
    const char *const argv[] = { "screencap", "-p", NULL };
    char out[64];
    sysexec_stub_reset();
    sysexec_stub_add_popen("screencap -p", payload, 0);
    dispatch_reply("SCREENCAP", out, sizeof out);
    CHECK(strcmp(out, payload) == 0, "SCREENCAP returns the raw actuator bytes\n");
    CHECK(sysexec_stub_count_argv("/system/bin/screencap", argv, 0) == 1,
          "SCREENCAP requests one PNG capture with exact structural argv\n");
}

// Verb matching is exact: a known verb as a prefix, a known verb with a suffix, or a case variant
// must reach no handler.
static void test_dispatch_exact_verb(void) {
    char out[256];
    static const char *const near_misses[] = { "PINGX", "PIN", "ping", "LOGCATDUMPX 5", "OFFX" };
    for (size_t i = 0; i < sizeof near_misses / sizeof near_misses[0]; i++) {
        dispatch_reply(near_misses[i], out, sizeof out);
        CHECK(strcmp(out, "ERR\n") == 0, "dispatch matches verbs exactly, not by prefix ('%s' got '%s')\n",
              near_misses[i], out);
    }
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
    server_serve(sv[0], HELPER_CALLER_SUCCESSOR);

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

// A subscriber whose socket died is dropped by the evdev reader's broadcast, not by an explicit
// unsubscribe; when it was the last one, that broadcast must release the exclusive touch grab.
static void test_dead_subscriber_on_broadcast_releases_grab(void) {
    input_init();
    sysexec_stub_reset();
    evdev_open_ok = 1; evdev_grab_ok = 1;
    snprintf(evdev_fixture, sizeof evdev_fixture, HAPANELD_TEST_TMP "/.hapaneld-evdev-fixture-%ld", (long)getpid());
    (void)unlink(evdev_fixture);
    CHECK(mkfifo(evdev_fixture, 0600) == 0, "create evdev FIFO fixture\n");
    int feed = __real_open(evdev_fixture, O_RDWR | O_CLOEXEC);
    sysexec_stub_set_spawn_real(1);
    CHECK(input_watch("/dev/input/event6", 1) == 0, "grab-capable WATCH starts the evdev reader\n");

    int dead[2], fresh[2];
    socketpair(AF_UNIX, SOCK_STREAM, 0, dead);
    socketpair(AF_UNIX, SOCK_STREAM, 0, fresh);
    conn_ctx dead_ctx = { .fd = dead[0], .subscribed = 0 };
    conn_ctx fresh_ctx = { .fd = fresh[0], .subscribed = 0 };
    char subscribe[] = "SUBSCRIBE";
    dispatch(&dead_ctx, subscribe);
    char out[8] = "";
    ssize_t n = read(dead[1], out, sizeof out - 1);
    out[n > 0 ? n : 0] = '\0';
    CHECK(strcmp(out, "OK\n") == 0, "subscriber admitted before its socket dies (got '%s')\n", out);
    int acquired = evdev_grab_acquire_count, released = evdev_grab_release_count;
    close(dead[1]);

    struct input_event ev;
    memset(&ev, 0, sizeof ev);
    ev.type = EV_KEY; ev.code = KEY_POWER; ev.value = 1;
    CHECK(feed >= 0 && write(feed, &ev, sizeof ev) == (ssize_t)sizeof ev, "feed one key event\n");
    for (int attempt = 0; attempt < 100 && evdev_grab_release_count == released; attempt++)
        usleep(20000);
    CHECK(evdev_grab_release_count == released + 1,
          "a broadcast that drops the last dead subscriber releases the live grab (got %d -> %d)\n",
          released, evdev_grab_release_count);
    char again[] = "SUBSCRIBE";
    dispatch(&fresh_ctx, again);
    n = read(fresh[1], out, sizeof out - 1);
    out[n > 0 ? n : 0] = '\0';
    CHECK(strcmp(out, "OK\n") == 0 && evdev_grab_acquire_count == acquired + 1,
          "the next subscriber acquires the grab afresh after the dead one was dropped\n");

    input_unsubscribe(fresh[0]);
    close(fresh[0]); close(fresh[1]); close(dead[0]);
    input_init();
    if (feed >= 0) close(feed);
    usleep(300000); // let the detached reader observe its generation change
    sysexec_stub_set_spawn_real(0);
    (void)unlink(evdev_fixture);
    evdev_fixture[0] = '\0';
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

static void test_room_climate_hwmon_discovery(void) {
    long temp = 0, humidity = 0;

    // The Smatek S9E route: one hwmon instance with a full temp+humidity pair in milli-units. The pair
    // converts to the centi-unit reply contract (milli / 10, rounded) that MetricParse.parseCht8305 reads.
    const struct cht8305_test_hwmon s9e[] = {
        { 1, 23456, 1, 55123 },
    };
    CHECK(cht8305_test_resolve_hwmon(s9e, sizeof s9e / sizeof s9e[0], &temp, &humidity) &&
          temp == 2346 && humidity == 5512,
          "an S9E hwmon pair converts milli-units to rounded centi-units\n");

    // A bare SoC/thermal hwmon exposes temperature but no humidity: not a room-climate sensor, fail closed
    // rather than publish SoC die temperature as the room reading.
    const struct cht8305_test_hwmon temp_only[] = {
        { 1, 48000, 0, 0 },
    };
    CHECK(!cht8305_test_resolve_hwmon(temp_only, sizeof temp_only / sizeof temp_only[0], &temp, &humidity),
          "a temperature-only hwmon instance fails closed\n");

    // The coherent-pair rule skips unrelated single-axis instances and selects the one full pair.
    const struct cht8305_test_hwmon with_thermal[] = {
        { 1, 52000, 0, 0 },
        { 1, 21000, 1, 48000 },
    };
    CHECK(cht8305_test_resolve_hwmon(with_thermal, sizeof with_thermal / sizeof with_thermal[0],
                                     &temp, &humidity) && temp == 2100 && humidity == 4800,
          "a full hwmon pair is selected alongside a temperature-only thermal zone\n");

    // Two full pairs are ambiguous — we cannot know which is the room sensor, so fail closed.
    const struct cht8305_test_hwmon two_pairs[] = {
        { 1, 21000, 1, 48000 },
        { 1, 22000, 1, 49000 },
    };
    CHECK(!cht8305_test_resolve_hwmon(two_pairs, sizeof two_pairs / sizeof two_pairs[0], &temp, &humidity),
          "two full hwmon pairs are ambiguous and fail closed\n");

    // No climate instance at all (only unrelated/empty hwmon) is an ordinary absence.
    CHECK(!cht8305_test_resolve_hwmon(NULL, 0, &temp, &humidity),
          "no hwmon climate instance fails closed\n");

    // Negative temperatures round away from zero symmetrically with positive ones.
    const struct cht8305_test_hwmon freezing[] = {
        { 1, -5125, 1, 90000 },
    };
    CHECK(cht8305_test_resolve_hwmon(freezing, sizeof freezing / sizeof freezing[0], &temp, &humidity) &&
          temp == -513 && humidity == 9000,
          "a below-zero hwmon temperature rounds away from zero\n");
}

static void test_room_climate_direct_discovery(void) {
    long temp = 0, humidity = 0;
    // The S9E direct device-tree route: both leaves present → milli converts to centi. Live hardware read
    // temp1_input=23880 humidity1_input=51892, which is 23.88 C / 51.89 %RH.
    CHECK(cht8305_test_resolve_direct(1, 23880, 1, 51892, &temp, &humidity) &&
          temp == 2388 && humidity == 5189,
          "an S9E direct-path pair converts milli-units to centi-units\n");
    // Either leaf missing (absent or unreadable node) fails closed rather than publishing half a reading.
    CHECK(!cht8305_test_resolve_direct(1, 23880, 0, 0, &temp, &humidity),
          "a direct-path read missing humidity fails closed\n");
    CHECK(!cht8305_test_resolve_direct(0, 0, 1, 51892, &temp, &humidity),
          "a direct-path read missing temperature fails closed\n");
}

// GRANT. Its whole safety argument is that it cannot be pointed at a package other than the app's
// own id and takes no free-form permission or setting name, so the assertions here are about the
// exact command it runs and about everything it refuses.
#define SUCCESSOR_ID "io.panelassistant.android"

static int ran_argv(const char *path, const char *const argv[]) {
    return sysexec_stub_count_argv(path, argv, 1);
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

    // Refusals: another package, and any capability outside the fixed table. A free-form permission
    // name must never reach `pm grant`.
    sysexec_stub_reset();
    dispatch_reply_as(HELPER_CALLER_SUCCESSOR, "GRANT com.other.app NOTIFICATIONS", out, sizeof out);
    CHECK(strcmp(out, "ERR\n") == 0, "GRANT refuses a third package (got '%s')\n", out);
    CHECK(sysexec_stub_count_argv_calls() == 0, "a refused GRANT executes nothing at all\n");

    sysexec_stub_reset();
    dispatch_reply_as(HELPER_CALLER_SUCCESSOR, "GRANT " SUCCESSOR_ID " WRITE_SECURE_SETTINGS", out, sizeof out);
    CHECK(strcmp(out, "ERR\n") == 0, "GRANT refuses a capability outside its table (got '%s')\n", out);
    CHECK(sysexec_stub_count_argv_calls() == 0, "an unknown capability never reaches an actuator\n");

    sysexec_stub_reset();
    dispatch_reply_as(HELPER_CALLER_SUCCESSOR, "GRANT " SUCCESSOR_ID, out, sizeof out);
    CHECK(strcmp(out, "ERR\n") == 0, "GRANT requires a capability (got '%s')\n", out);
    dispatch_reply_as(HELPER_CALLER_SUCCESSOR, "GRANT " SUCCESSOR_ID " OVERLAY extra", out, sizeof out);
    CHECK(strcmp(out, "ERR\n") == 0, "GRANT rejects a trailing argument (got '%s')\n", out);
    CHECK(sysexec_stub_count_argv_calls() == 0, "no malformed GRANT executes anything\n");
}

static void test_grant_accessibility(void) {
    char out[MAX_LINE + 2];
    // A component is the id plus the fully qualified class. The `.input.…` shorthand resolves against
    // the id and names the same class.
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

// --- Typed Panel Assistant commands (protocol 1.17) -----------------------------------------------
// The eight PA_ verbs replace root shell programs Panel Assistant used to send. Their safety argument
// is that only the app may call them, no request byte reaches argv unless it is an exact table
// member, and every write is a proven-missing repair. The cases below pin those properties through
// dispatch with stubbed panel output, the same way the GRANT cases do.
#define PA_TEST_DIR "/tmp/.hapaneld-pa-test"
#define PA_TEST_LOCK "/tmp/.hapaneld-pa-test-lock"
#define PA_TEST_DUMP "/tmp/.hapaneld-pa-test-chooser/chooser.xml"
#define PA_META_RECORD PA_TEST_DIR "/panel-assistant-meta-portal-restore"
#define PA_VERIFIER_RECORD PA_TEST_DIR "/panel-assistant-meta-portal-verifier-restore"
#define PA_SHELLY_RECORD PA_TEST_DIR "/panel-assistant-shelly-restore"
#define PA_DISPLAY_RECORD PA_TEST_DIR "/panel-assistant-display-restore"
#define PA_ACCESSIBILITY_NEW SUCCESSOR_ID "/io.panelassistant.android.input.PanelAccessibilityService"
#define PA_ACCESSIBILITY_OLD SUCCESSOR_ID "/io.github.maxlyth.hapaneld.input.PanelAccessibilityService"
#define PA_RECEIVER SUCCESSOR_ID "/io.panelassistant.android.platform.PreservePreviousHomeReceiver"
#define PA_HOME_QUERY "cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME"
#define PA_CHOOSER "priority=0 preferredOrder=0 match=0x108000 specificIndex=-1 isDefault=true\nandroid/com.android.internal.app.ResolverActivity\n"

static int captured_argv(const char *path, const char *const argv[]) {
    return sysexec_stub_count_argv(path, argv, 0);
}

// The value of [key] in an `OK <n>` body, or 0 when the reply is not OK or has no such line.
static int pa_field(const char *reply, const char *key, char *value, size_t size) {
    unsigned long declared = 0;
    int header = 0;
    if (sscanf(reply, "OK %lu\n%n", &declared, &header) != 1 || header <= 0) return 0;
    const char *body = reply + header;
    if (strlen(body) != declared) return 0;
    size_t key_size = strlen(key);
    for (const char *line = body; *line; ) {
        const char *end = strchr(line, '\n');
        if (!end) return 0;
        if ((size_t)(end - line) > key_size && strncmp(line, key, key_size) == 0 && line[key_size] == '=') {
            size_t n = (size_t)(end - line) - key_size - 1;
            if (n >= size) return 0;
            memcpy(value, line + key_size + 1, n);
            value[n] = '\0';
            return 1;
        }
        line = end + 1;
    }
    return 0;
}

static int pa_field_is(const char *reply, const char *key, const char *expected) {
    char value[512];
    return pa_field(reply, key, value, sizeof value) && strcmp(value, expected) == 0;
}

static void pa_stub_identity(const char *sdk) {
    sysexec_stub_add_popen("getprop ro.product.model", "NSPanel\n", 0);
    sysexec_stub_add_popen("getprop ro.serialno", "ABC123\n", 0);
    sysexec_stub_add_popen("getprop ro.product.cpu.abi", "armeabi-v7a\n", 0);
    sysexec_stub_add_popen("getprop ro.build.version.sdk", sdk, 0);
    sysexec_stub_add_popen("getprop ro.secure", "1\n", 0);
    sysexec_stub_add_popen("getprop ro.debuggable", "0\n", 0);
}

static void pa_write_fixture(const char *path, const char *content, mode_t mode) {
    unlink(path);
    int fd = open(path, O_WRONLY | O_CREAT | O_EXCL | O_CLOEXEC, mode);
    CHECK(fd >= 0, "PA fixture %s can be created\n", path);
    if (fd < 0) return;
    CHECK(write(fd, content, strlen(content)) == (ssize_t)strlen(content), "PA fixture %s is written\n", path);
    CHECK(fchmod(fd, mode) == 0, "PA fixture %s takes its mode\n", path);
    close(fd);
}

static int pa_file_is(const char *path, const char *expected) {
    char buffer[1024];
    int fd = open(path, O_RDONLY | O_CLOEXEC);
    if (fd < 0) return 0;
    ssize_t n = read(fd, buffer, sizeof buffer - 1);
    close(fd);
    if (n < 0) return 0;
    buffer[n] = '\0';
    return strcmp(buffer, expected) == 0;
}

static void pa_reset_records(void) {
    mkdir(PA_TEST_DIR, 0755);
    unlink(PA_META_RECORD); unlink(PA_META_RECORD ".part");
    unlink(PA_VERIFIER_RECORD); unlink(PA_VERIFIER_RECORD ".part");
    unlink(PA_SHELLY_RECORD); unlink(PA_SHELLY_RECORD ".part");
    unlink(PA_DISPLAY_RECORD); unlink(PA_DISPLAY_RECORD ".part");
    unlink(PA_TEST_DIR "/other-name");
    unlink(PA_TEST_DIR "/link-target");
}

static void test_pa_admission_and_grammar(void) {
    char out[8192];
    static const char *const valid[] = {
        "PA_IDENTITY", "PA_REPAIR 1 0", "PA_HOME 1 repair", "PA_ADMIN", "PA_VENDOR setup",
        "PA_RESTORE vendor", "PA_OBSERVE move", "PA_HANDBACK vendor",
    };
    for (size_t i = 0; i < sizeof valid / sizeof valid[0]; i++) {
        sysexec_stub_reset();
        dispatch_reply_as(HELPER_CALLER_ROOT, valid[i], out, sizeof out);
        CHECK(strcmp(out, "ERR caller\n") == 0, "%s from a root shell is refused as caller (got '%s')\n", valid[i], out);
        CHECK(sysexec_stub_count_argv_calls() == 0, "%s from a root shell runs nothing\n", valid[i]);
    }
    char overlong[256];
    snprintf(overlong, sizeof overlong, "PA_HANDBACK component com.example.%0150d/.Home", 0);
    const char *const refused[] = {
        "PA_IDENTITY x", "PA_ADMIN x", "PA_REPAIR", "PA_REPAIR 1", "PA_REPAIR 1 0 x", "PA_REPAIR 2 0",
        "PA_REPAIR 1 01", "PA_HOME 1", "PA_HOME 1 other", "PA_HOME 1 repair x", "PA_HOME 3 deferred",
        "PA_VENDOR", "PA_VENDOR restore", "PA_VENDOR setup x", "PA_RESTORE", "PA_RESTORE other",
        "PA_RESTORE display x", "PA_OBSERVE", "PA_OBSERVE other", "PA_OBSERVE move x",
        "PA_HANDBACK", "PA_HANDBACK other", "PA_HANDBACK vendor x", "PA_HANDBACK component",
        "PA_HANDBACK component a/b/c", "PA_HANDBACK component com.example.home/.Home extra",
        "PA_HANDBACK component com.example.home", "PA_HANDBACK component /.Home",
        "PA_HANDBACK component com.example.home/", "PA_HANDBACK component com.ex;ample/.Home", overlong,
    };
    for (size_t i = 0; i < sizeof refused / sizeof refused[0]; i++) {
        sysexec_stub_reset();
        dispatch_reply_as(HELPER_CALLER_SUCCESSOR, refused[i], out, sizeof out);
        CHECK(strcmp(out, "ERR args\n") == 0, "'%s' is refused as args (got '%s')\n", refused[i], out);
        CHECK(sysexec_stub_count_argv_calls() == 0, "'%s' runs nothing\n", refused[i]);
    }
}

static void test_pa_identity(void) {
    char out[8192];
    sysexec_stub_reset();
    pa_stub_identity("30\n");
    dispatch_reply_as(HELPER_CALLER_SUCCESSOR, "PA_IDENTITY", out, sizeof out);
    CHECK(strncmp(out, "OK ", 3) == 0 && strstr(out, "\nmodel=NSPanel\nserial=") != NULL,
          "PA_IDENTITY answers an OK body that starts with the model (got '%s')\n", out);
    char uid[32];
    snprintf(uid, sizeof uid, "%u", (unsigned)getuid());
    CHECK(pa_field_is(out, "sdk", "30") && pa_field_is(out, "abi", "armeabi-v7a") &&
          pa_field_is(out, "secure", "1") && pa_field_is(out, "debuggable", "0") &&
          pa_field_is(out, "uid", uid), "PA_IDENTITY reports the identity tuple\n");
    CHECK(pa_field_is(out, "serial", "ABC123"), "PA_IDENTITY reports the serial\n");
    char su[32];
    CHECK(pa_field(out, "su", su, sizeof su) &&
          (strcmp(su, "present") == 0 || strcmp(su, "absent") == 0 || strcmp(su, "abnormal") == 0),
          "PA_IDENTITY reports su as present, absent or abnormal\n");
    // No '=' from the panel may reach PA's key=value parser.
    sysexec_stub_reset();
    sysexec_stub_add_popen("getprop ro.serialno", "bad=serial\n", 0);
    dispatch_reply_as(HELPER_CALLER_SUCCESSOR, "PA_IDENTITY", out, sizeof out);
    CHECK(pa_field_is(out, "serial", "unknown"), "a panel value with '=' is reported as unknown (got '%s')\n", out);
    CHECK(pa_field_is(out, "model", "unknown"), "an unreadable property is reported as unknown\n");
}

// Common panel answers for REPAIR: the app installed, a shared accessibility list holding another
// service plus the obsolete class, and the vendor probe on an ordinary panel.
static void pa_stub_repair_base(const char *sdk) {
    pa_stub_identity(sdk);
    sysexec_stub_add_popen("pm path " SUCCESSOR_ID, "package:/data/app/base.apk\n", 0);
    sysexec_stub_add_popen("getprop ro.product.device", "nspanel\n", 0);
}

static void test_pa_repair(void) {
    char out[8192];
    // Any other pm path failure stays failed.
    sysexec_stub_reset();
    pa_stub_identity("33\n");
    sysexec_stub_add_popen("pm path " SUCCESSOR_ID, "Error: service\n", 1);
    dispatch_reply_as(HELPER_CALLER_SUCCESSOR, "PA_REPAIR 1 0", out, sizeof out);
    CHECK(strcmp(out, "ERR failed\n") == 0, "PA_REPAIR with an erroring pm path answers ERR failed (got '%s')\n", out);
    const char *before = "com.other/.Svc:" PA_ACCESSIBILITY_OLD "\n";
    const char *after = "com.other/.Svc:" PA_ACCESSIBILITY_NEW;
    char after_line[256];
    snprintf(after_line, sizeof after_line, "%s\n", after);

    sysexec_stub_reset();
    pa_stub_repair_base("32\n");
    sysexec_stub_add_popen_times("settings get secure enabled_accessibility_services", before, 0, 2);
    sysexec_stub_add_popen("settings get secure enabled_accessibility_services", after_line, 0);
    sysexec_stub_add_popen_times("settings get secure accessibility_enabled", "0\n", 0, 1);
    sysexec_stub_add_popen("settings get secure accessibility_enabled", "1\n", 0);
    // Android 14 reports an unset operation as two lines; that default is proven non-allowed.
    sysexec_stub_add_popen_times("appops get " SUCCESSOR_ID " WRITE_SETTINGS", "No operations.\nDefault mode: default\n", 0, 1);
    sysexec_stub_add_popen("appops get " SUCCESSOR_ID " WRITE_SETTINGS", "WRITE_SETTINGS: allow\n", 0);
    sysexec_stub_add_popen("appops get " SUCCESSOR_ID " SYSTEM_ALERT_WINDOW", "SYSTEM_ALERT_WINDOW: allow; time=+1d2h ago\n", 0);
    sysexec_stub_add_popen_times("appops get " SUCCESSOR_ID " REQUEST_INSTALL_PACKAGES", "REQUEST_INSTALL_PACKAGES: deny; rejectTime=+3m ago\n", 0, 1);
    sysexec_stub_add_popen("appops get " SUCCESSOR_ID " REQUEST_INSTALL_PACKAGES", "REQUEST_INSTALL_PACKAGES: allow\n", 0);
    sysexec_stub_add_popen("pm list features", "feature:android.hardware.microphone\nfeature:android.hardware.wifi\n", 0);
    sysexec_stub_add_popen_times("dumpsys package " SUCCESSOR_ID,
        "    android.permission.POST_NOTIFICATIONS: granted=false, flags=[ USER_SET ]\n"
        "    android.permission.CAMERA: granted=false\n"
        "    android.permission.RECORD_AUDIO: granted=false, flags=[ USER_SET ]\n", 0, 1);
    sysexec_stub_add_popen("dumpsys package " SUCCESSOR_ID,
        "    android.permission.CAMERA: granted=false\n"
        "    android.permission.RECORD_AUDIO: granted=true\n", 0);
    dispatch_reply_as(HELPER_CALLER_SUCCESSOR, "PA_REPAIR 1 0", out, sizeof out);
    CHECK(strncmp(out, "OK ", 3) == 0, "PA_REPAIR answers OK (got '%s')\n", out);
    const char *const put_services[] = { "settings", "put", "secure", "enabled_accessibility_services", after, NULL };
    CHECK(ran_argv("/system/bin/settings", put_services) == 1,
          "PA_REPAIR drops the obsolete class, keeps other services and adds the current one\n");
    const char *const enable[] = { "settings", "put", "secure", "accessibility_enabled", "1", NULL };
    CHECK(ran_argv("/system/bin/settings", enable) == 1, "PA_REPAIR enables accessibility when it reads 0\n");
    const char *const allow_settings[] = { "appops", "set", SUCCESSOR_ID, "WRITE_SETTINGS", "allow", NULL };
    const char *const allow_overlay[] = { "appops", "set", SUCCESSOR_ID, "SYSTEM_ALERT_WINDOW", "allow", NULL };
    const char *const allow_installs[] = { "appops", "set", SUCCESSOR_ID, "REQUEST_INSTALL_PACKAGES", "allow", NULL };
    CHECK(ran_argv("/system/bin/appops", allow_settings) == 1, "PA_REPAIR allows an op Android 14 reports as two default lines\n");
    CHECK(ran_argv("/system/bin/appops", allow_installs) == 1, "PA_REPAIR allows an op proven denied\n");
    CHECK(ran_argv("/system/bin/appops", allow_overlay) == 0, "PA_REPAIR leaves an op that already reads allow\n");
    const char *const grant_camera[] = { "pm", "grant", SUCCESSOR_ID, "android.permission.CAMERA", NULL };
    const char *const grant_microphone[] = { "pm", "grant", SUCCESSOR_ID, "android.permission.RECORD_AUDIO", NULL };
    const char *const grant_notifications[] = { "pm", "grant", SUCCESSOR_ID, "android.permission.POST_NOTIFICATIONS", NULL };
    CHECK(ran_argv("/system/bin/pm", grant_camera) == 0, "PA_REPAIR never grants CAMERA when the feature is absent\n");
    CHECK(ran_argv("/system/bin/pm", grant_microphone) == 1, "PA_REPAIR grants RECORD_AUDIO when the feature is listed and denied\n");
    CHECK(ran_argv("/system/bin/pm", grant_notifications) == 0, "PA_REPAIR never grants notifications below SDK 33\n");
    CHECK(pa_field_is(out, "accessibility", "granted") && pa_field_is(out, "accessibility_enabled", "1"),
          "PA_REPAIR reports the accessibility readback\n");
    CHECK(pa_field_is(out, "op.WRITE_SETTINGS", "allow") && pa_field_is(out, "op.SYSTEM_ALERT_WINDOW", "allow") &&
          pa_field_is(out, "op.REQUEST_INSTALL_PACKAGES", "allow"), "PA_REPAIR reports each op readback\n");
    CHECK(pa_field_is(out, "camera", "unsupported") && pa_field_is(out, "microphone", "granted") &&
          pa_field_is(out, "notifications", "unsupported") && pa_field_is(out, "secure_settings", "unsupported"),
          "PA_REPAIR reports the runtime grants (got '%s')\n", out);
    CHECK(pa_field_is(out, "device", "nspanel") && pa_field_is(out, "vendor", "other"),
          "PA_REPAIR reports the vendor probe\n");

    // A writer changed the list between the read and the re-read: nothing is written over it.
    sysexec_stub_reset();
    pa_stub_repair_base("33\n");
    sysexec_stub_add_popen_times("settings get secure enabled_accessibility_services", "com.other/.Svc\n", 0, 1);
    sysexec_stub_add_popen("settings get secure enabled_accessibility_services", "com.third/.Svc\n", 0);
    sysexec_stub_add_popen("appops get", "", 1);
    sysexec_stub_add_popen("pm list features", "", 1);
    sysexec_stub_add_popen_times("dumpsys package " SUCCESSOR_ID,
        "    android.permission.POST_NOTIFICATIONS: granted=false\n", 0, 1);
    sysexec_stub_add_popen("dumpsys package " SUCCESSOR_ID,
        "    android.permission.POST_NOTIFICATIONS: granted=true, flags=[ GRANTED_BY_DEFAULT ]\n", 0);
    dispatch_reply_as(HELPER_CALLER_SUCCESSOR, "PA_REPAIR 1 0", out, sizeof out);
    const char *const overwrite[] = {
        "settings", "put", "secure", "enabled_accessibility_services", "com.other/.Svc:" PA_ACCESSIBILITY_NEW, NULL
    };
    CHECK(ran_argv("/system/bin/settings", overwrite) == 0,
          "PA_REPAIR never writes a list that changed between its read and its re-read\n");
    CHECK(pa_field_is(out, "accessibility", "unknown"), "a changed list is reported as unknown so PA retries\n");
    CHECK(ran_argv("/system/bin/appops", allow_settings) == 0, "an unreadable op is never set\n");
    CHECK(ran_argv("/system/bin/pm", grant_notifications) == 1, "PA_REPAIR grants proven-missing notifications on SDK 33\n");
    CHECK(pa_field_is(out, "notifications", "granted"), "PA_REPAIR reads notifications back as granted\n");
    CHECK(pa_field_is(out, "camera", "unknown") && pa_field_is(out, "microphone", "unknown"),
          "unreadable features leave the runtime grants unknown\n");

    // A long service of another app is kept; the request-side component cap does not apply to it.
    char long_service[200], long_before[256], long_after[512];
    snprintf(long_service, sizeof long_service, "com.vendor.%0130d/.Svc", 0);
    snprintf(long_before, sizeof long_before, "%s\n", long_service);
    snprintf(long_after, sizeof long_after, "%s:%s", long_service, PA_ACCESSIBILITY_NEW);
    sysexec_stub_reset();
    pa_stub_repair_base("30\n");
    sysexec_stub_add_popen("settings get secure enabled_accessibility_services", long_before, 0);
    sysexec_stub_add_popen("appops get", "", 1);
    sysexec_stub_add_popen("pm list features", "", 1);
    dispatch_reply_as(HELPER_CALLER_SUCCESSOR, "PA_REPAIR 1 0", out, sizeof out);
    const char *const put_long[] = { "settings", "put", "secure", "enabled_accessibility_services", long_after, NULL };
    CHECK(ran_argv("/system/bin/settings", put_long) == 1,
          "PA_REPAIR keeps a long foreign service and appends the current one\n");

    // The app is not installed: nothing is repaired.
    sysexec_stub_reset();
    pa_stub_identity("33\n");
    // sysexec returns the raw wait status: pm exiting 1 arrives as 1 << 8.
    sysexec_stub_add_popen("pm path " SUCCESSOR_ID, "", 1 << 8);
    dispatch_reply_as(HELPER_CALLER_SUCCESSOR, "PA_REPAIR 1 1", out, sizeof out);
    CHECK(strcmp(out, "ERR absent\n") == 0, "PA_REPAIR without the app installed answers ERR absent (got '%s')\n", out);

    // A pm killed by a signal (raw status SIGKILL) proves nothing about the package: ERR failed.
    sysexec_stub_reset();
    pa_stub_identity("33\n");
    sysexec_stub_add_popen("pm path " SUCCESSOR_ID, "", SIGKILL);
    dispatch_reply_as(HELPER_CALLER_SUCCESSOR, "PA_REPAIR 1 1", out, sizeof out);
    CHECK(strcmp(out, "ERR failed\n") == 0, "PA_REPAIR with a signal-killed pm answers ERR failed (got '%s')\n", out);

    // A build that asks for WRITE_SECURE_SETTINGS gets it when the dump proves it missing.
    sysexec_stub_reset();
    pa_stub_repair_base("30\n");
    sysexec_stub_add_popen("settings get secure enabled_accessibility_services", PA_ACCESSIBILITY_NEW "\n", 0);
    sysexec_stub_add_popen("settings get secure accessibility_enabled", "1\n", 0);
    sysexec_stub_add_popen("appops get", "", 1);
    sysexec_stub_add_popen("pm list features", "feature:android.hardware.wifi\n", 0);
    sysexec_stub_add_popen_times("dumpsys package " SUCCESSOR_ID, "    requested permissions:\n      android.permission.WRITE_SECURE_SETTINGS\n", 0, 1);
    sysexec_stub_add_popen("dumpsys package " SUCCESSOR_ID,
        "      android.permission.WRITE_SECURE_SETTINGS\n    android.permission.WRITE_SECURE_SETTINGS: granted=true\n", 0);
    dispatch_reply_as(HELPER_CALLER_SUCCESSOR, "PA_REPAIR 1 1", out, sizeof out);
    const char *const grant_secure[] = { "pm", "grant", SUCCESSOR_ID, "android.permission.WRITE_SECURE_SETTINGS", NULL };
    CHECK(ran_argv("/system/bin/pm", grant_secure) == 1, "PA_REPAIR grants a requested, missing WRITE_SECURE_SETTINGS\n");
    CHECK(pa_field_is(out, "secure_settings", "granted"), "PA_REPAIR reads WRITE_SECURE_SETTINGS back (got '%s')\n", out);
    const char *const any_put[] = { "settings", "put", "secure", "enabled_accessibility_services", PA_ACCESSIBILITY_NEW, NULL };
    CHECK(ran_argv("/system/bin/settings", any_put) == 0, "a list that already holds the service is not written\n");
    CHECK(ran_argv("/system/bin/settings", enable) == 0, "accessibility that already reads 1 is not written\n");
}

static const char PA_XML_GOOD[] =
    "<?xml version='1.0' encoding='UTF-8' standalone='yes' ?><hierarchy rotation=\"0\">"
    "<node index=\"0\" text=\"Use Panel Assistant as Home\" resource-id=\"android:id/title\" class=\"android.widget.TextView\" package=\"android\" enabled=\"true\" bounds=\"[40,100][680,160]\" />"
    "<node index=\"1\" text=\"ALWAYS\" resource-id=\"android:id/button_always\" class=\"android.widget.Button\" package=\"android\" enabled=\"true\" bounds=\"[100,200][300,260]\" />"
    "<node index=\"2\" text=\"JUST ONCE\" resource-id=\"android:id/button_once\" class=\"android.widget.Button\" package=\"android\" enabled=\"true\" bounds=\"[400,200][600,260]\" />"
    "</hierarchy>";

static void pa_stub_home_builtin(void) {
    pa_stub_identity("29\n");
    sysexec_stub_add_popen_times("--es operation dashboard_home_policy",
        "Broadcasting: Intent { flg=0x400000 cmp=x }\nBroadcast completed: result=1, data=\"panel-assistant-home-builtin\"\n", 0, 1);
    sysexec_stub_add_popen("--es operation setup_eligibility",
        "Broadcast completed: result=1, data=\"panel-assistant-setup-eligible\"\n", 0);
    sysexec_stub_add_popen(PA_HOME_QUERY, PA_CHOOSER, 0);
}

static void test_pa_home_chooser(void) {
    char out[8192], xml[4096];
    const char *const tap[] = { "input", "tap", "200", "230", NULL };
    const char *const back[] = { "input", "keyevent", "4", NULL };
    const char *const set_home[] = {
        "cmd", "package", "set-home-activity", SUCCESSOR_ID "/io.panelassistant.android.DashboardActivity", NULL
    };
    const char *const ack[] = {
        "am", "broadcast", "--include-stopped-packages", "-n", PA_RECEIVER, "--es", "operation", "acknowledge_home", NULL
    };

    sysexec_stub_reset();
    pa_stub_home_builtin();
    sysexec_stub_add_popen("--es operation dashboard_home_policy",
        "Broadcast completed: result=1, data=\"panel-assistant-home-builtin\"\n", 0);
    sysexec_stub_write_file_on_run("uiautomator dump", PA_TEST_DUMP, PA_XML_GOOD);
    dispatch_reply_as(HELPER_CALLER_SUCCESSOR, "PA_HOME 1 repair", out, sizeof out);
    CHECK(pa_field_is(out, "chooser", "tapped"), "PA_HOME presses Always on the app's own chooser (got '%s')\n", out);
    CHECK(pa_field_is(out, "policy", "builtin"), "PA_HOME reports the builtin policy\n");
    CHECK(captured_argv("/system/bin/input", tap) == 1, "PA_HOME taps the centre of the Always button\n");
    CHECK(captured_argv("/system/bin/cmd", set_home) == 1, "PA_HOME asks set-home-activity for the dashboard first\n");
    CHECK(captured_argv("/system/bin/am", ack) == 1, "PA_HOME acknowledges the claim\n");
    CHECK(access(PA_TEST_DUMP, F_OK) != 0, "PA_HOME removes the chooser dump after reading it\n");

    // The chooser offers another app: nothing is pressed and the chooser is dismissed.
    replace_text(PA_XML_GOOD, "Use Panel Assistant as Home", "Use Launcher3 as Home", xml, sizeof xml);
    sysexec_stub_reset();
    pa_stub_home_builtin();
    sysexec_stub_add_popen("--es operation dashboard_home_policy",
        "Broadcast completed: result=1, data=\"panel-assistant-home-builtin\"\n", 0);
    sysexec_stub_write_file_on_run("uiautomator dump", PA_TEST_DUMP, xml);
    dispatch_reply_as(HELPER_CALLER_SUCCESSOR, "PA_HOME 1 repair", out, sizeof out);
    CHECK(captured_argv("/system/bin/input", tap) == 0, "PA_HOME never presses a chooser whose title names another app\n");
    CHECK(pa_field_is(out, "chooser", "other_app"), "a chooser for another app is reported as other_app\n");
    CHECK(captured_argv("/system/bin/input", back) == 1, "a chooser that is not pressed is dismissed\n");

    // Two Always buttons: ambiguous, so nothing is pressed.
    replace_text(PA_XML_GOOD, "</hierarchy>",
        "<node text=\"ALWAYS\" resource-id=\"android:id/button_always\" package=\"android\" enabled=\"true\" bounds=\"[0,0][50,50]\" /></hierarchy>",
        xml, sizeof xml);
    sysexec_stub_reset();
    pa_stub_home_builtin();
    sysexec_stub_add_popen("--es operation dashboard_home_policy",
        "Broadcast completed: result=1, data=\"panel-assistant-home-builtin\"\n", 0);
    sysexec_stub_write_file_on_run("uiautomator dump", PA_TEST_DUMP, xml);
    dispatch_reply_as(HELPER_CALLER_SUCCESSOR, "PA_HOME 1 repair", out, sizeof out);
    CHECK(captured_argv("/system/bin/input", tap) == 0, "PA_HOME never presses one of two Always buttons\n");
    CHECK(pa_field_is(out, "chooser", "no_button"), "two Always buttons are reported as no_button\n");

    // Zero-area bounds: nothing to press.
    replace_text(PA_XML_GOOD, "[100,200][300,260]", "[100,200][100,260]", xml, sizeof xml);
    sysexec_stub_reset();
    pa_stub_home_builtin();
    sysexec_stub_add_popen("--es operation dashboard_home_policy",
        "Broadcast completed: result=1, data=\"panel-assistant-home-builtin\"\n", 0);
    sysexec_stub_write_file_on_run("uiautomator dump", PA_TEST_DUMP, xml);
    dispatch_reply_as(HELPER_CALLER_SUCCESSOR, "PA_HOME 1 repair", out, sizeof out);
    const char *const zero_tap[] = { "input", "tap", "100", "230", NULL };
    CHECK(captured_argv("/system/bin/input", zero_tap) == 0, "PA_HOME never presses a zero-area button\n");

    // The policy changed during the chooser waits: the re-check before the tap refuses it. The
    // first two policy reads (the claim and its authority check) still say builtin.
    sysexec_stub_reset();
    pa_stub_home_builtin();
    sysexec_stub_add_popen_times("--es operation dashboard_home_policy",
        "Broadcast completed: result=1, data=\"panel-assistant-home-builtin\"\n", 0, 1);
    sysexec_stub_add_popen("--es operation dashboard_home_policy",
        "Broadcast completed: result=2, data=\"panel-assistant-home-retained\"\n", 0);
    sysexec_stub_write_file_on_run("uiautomator dump", PA_TEST_DUMP, PA_XML_GOOD);
    dispatch_reply_as(HELPER_CALLER_SUCCESSOR, "PA_HOME 1 repair", out, sizeof out);
    CHECK(captured_argv("/system/bin/input", tap) == 0, "PA_HOME re-checks the policy before pressing Always\n");
    CHECK(pa_field_is(out, "chooser", "deferred"), "a policy that changed before the tap is reported as deferred\n");

    // HOME already resolves to the dashboard: nothing is sent.
    sysexec_stub_reset();
    pa_stub_identity("29\n");
    sysexec_stub_add_popen("--es operation dashboard_home_policy",
        "Broadcast completed: result=1, data=\"panel-assistant-home-builtin\"\n", 0);
    sysexec_stub_add_popen("--es operation setup_eligibility",
        "Broadcast completed: result=1, data=\"panel-assistant-setup-eligible\"\n", 0);
    sysexec_stub_add_popen(PA_HOME_QUERY, "x\n" SUCCESSOR_ID "/.DashboardActivity\n", 0);
    dispatch_reply_as(HELPER_CALLER_SUCCESSOR, "PA_HOME 1 repair", out, sizeof out);
    CHECK(pa_field_is(out, "chooser", "kept") && captured_argv("/system/bin/cmd", set_home) == 0,
          "PA_HOME keeps a HOME that already resolves to the dashboard (got '%s')\n", out);
    CHECK(pa_field_is(out, "home", SUCCESSOR_ID "/.DashboardActivity"), "PA_HOME reports the HOME readback\n");
}

static void test_pa_home_lock(void) {
    char out[8192];
    mkdir(PA_TEST_LOCK, 0755);
    // Another process holds the shared HOME lock for a moment. The claim must refuse at once, not
    // wait for it.
    int ready[2];
    CHECK(pipe(ready) == 0, "lock holder pipe\n");
    pid_t holder = fork();
    if (holder == 0) {
        int fd = open(PA_TEST_LOCK, O_RDONLY | O_DIRECTORY | O_CLOEXEC);
        if (fd < 0 || flock(fd, LOCK_EX) != 0) _exit(1);
        if (write(ready[1], "R", 1) != 1) _exit(1);
        usleep(1500 * 1000);
        _exit(0);
    }
    char byte;
    CHECK(read(ready[0], &byte, 1) == 1, "lock holder took the lock\n");
    close(ready[0]); close(ready[1]);
    sysexec_stub_reset();
    pa_stub_identity("29\n");
    sysexec_stub_add_popen("--es operation claim_pending_home",
        "Broadcast completed: result=2, data=\"panel-assistant-pending-home-shell\"\n", 0);
    sysexec_stub_add_popen(PA_HOME_QUERY, PA_CHOOSER, 0);
    dispatch_reply_as(HELPER_CALLER_SUCCESSOR, "PA_HOME 1 deferred", out, sizeof out);
    CHECK(strcmp(out, "ERR busy\n") == 0, "PA_HOME refuses as busy while the HOME lock is held (got '%s')\n", out);
    int status;
    waitpid(holder, &status, 0);

    // Free lock: the eligibility broadcast carries the locked directory's identity, and every HOME
    // actuator runs with the lock as its stdin.
    struct stat lock_st;
    CHECK(stat(PA_TEST_LOCK, &lock_st) == 0, "lock directory exists\n");
    char identity[64];
    snprintf(identity, sizeof identity, "%llu:%llu", (unsigned long long)lock_st.st_dev,
             (unsigned long long)lock_st.st_ino);
    sysexec_stub_reset();
    pa_stub_identity("29\n");
    sysexec_stub_add_popen("--es operation claim_pending_home",
        "Broadcast completed: result=2, data=\"panel-assistant-pending-home-shell\"\n", 0);
    sysexec_stub_add_popen("--es operation pending_home_shell_lock_eligibility",
        "Broadcast completed: result=1, data=\"panel-assistant-pending-home-shell-locked\"\n", 0);
    sysexec_stub_add_popen_times(PA_HOME_QUERY, PA_CHOOSER, 0, 1);
    sysexec_stub_add_popen(PA_HOME_QUERY, SUCCESSOR_ID "/io.panelassistant.android.DashboardActivity\n", 0);
    dispatch_reply_as(HELPER_CALLER_SUCCESSOR, "PA_HOME 1 deferred", out, sizeof out);
    const char *const eligibility[] = {
        "am", "broadcast", "--include-stopped-packages", "-n", PA_RECEIVER, "--es", "operation",
        "pending_home_shell_lock_eligibility", "--es", "home_lock_identity", identity, NULL
    };
    CHECK(captured_argv("/system/bin/am", eligibility) == 1, "the eligibility broadcast names the locked identity\n");
    CHECK(pa_field_is(out, "policy", "shell") && pa_field_is(out, "chooser", "set"),
          "the locked deferred claim sets HOME (got '%s')\n", out);
    CHECK(sysexec_stub_count_stdin_fd_calls() >= 3, "HOME actuators run with the lock as stdin\n");
}

static int pa_ran(const char *a, const char *b);

static void test_pa_records(void) {
    char out[8192];
    const char *const deny_shelly[] = { "appops", "set", "cloud.shelly.stargate", "SYSTEM_ALERT_WINDOW", "allow", NULL };

    // A first-install record, written by the panel's shell with its default umask, is accepted.
    pa_reset_records();
    pa_write_fixture(PA_SHELLY_RECORD, "stargate_overlay=allow\n", 0644);
    CHECK(chown(PA_SHELLY_RECORD, 2000, 2000) == 0, "the shelly record can be owned by shell\n");
    sysexec_stub_reset();
    pa_stub_identity("30\n");
    sysexec_stub_add_popen("pm path cloud.shelly.stargate", "package:/system/app/stargate.apk\n", 0);
    sysexec_stub_add_popen("appops get cloud.shelly.stargate SYSTEM_ALERT_WINDOW", "SYSTEM_ALERT_WINDOW: allow\n", 0);
    dispatch_reply_as(HELPER_CALLER_SUCCESSOR, "PA_RESTORE vendor", out, sizeof out);
    CHECK(ran_argv("/system/bin/appops", deny_shelly) == 1, "PA_RESTORE puts back the recorded Shelly overlay mode\n");
    CHECK(pa_field_is(out, "result", "restored"), "PA_RESTORE reports restored (got '%s')\n", out);
    CHECK(access(PA_SHELLY_RECORD, F_OK) != 0, "PA_RESTORE forgets a record once it reads back\n");

    // A symlinked record is refused, and its target is never acted on.
    pa_reset_records();
    pa_write_fixture(PA_TEST_DIR "/link-target", "stargate_overlay=allow\n", 0644);
    CHECK(symlink(PA_TEST_DIR "/link-target", PA_SHELLY_RECORD) == 0, "symlinked record fixture\n");
    sysexec_stub_reset();
    sysexec_stub_add_popen("pm path cloud.shelly.stargate", "package:/system/app/stargate.apk\n", 0);
    dispatch_reply_as(HELPER_CALLER_SUCCESSOR, "PA_RESTORE vendor", out, sizeof out);
    CHECK(strcmp(out, "ERR failed\n") == 0, "PA_RESTORE refuses a symlinked record (got '%s')\n", out);
    CHECK(ran_argv("/system/bin/appops", deny_shelly) == 0, "a symlinked record is never applied\n");

    // A record with a second name is refused.
    pa_reset_records();
    pa_write_fixture(PA_SHELLY_RECORD, "stargate_overlay=allow\n", 0644);
    CHECK(link(PA_SHELLY_RECORD, PA_TEST_DIR "/other-name") == 0, "hard-linked record fixture\n");
    sysexec_stub_reset();
    sysexec_stub_add_popen("pm path cloud.shelly.stargate", "package:/system/app/stargate.apk\n", 0);
    dispatch_reply_as(HELPER_CALLER_SUCCESSOR, "PA_RESTORE vendor", out, sizeof out);
    CHECK(strcmp(out, "ERR failed\n") == 0, "PA_RESTORE refuses a record with two links (got '%s')\n", out);
    CHECK(ran_argv("/system/bin/appops", deny_shelly) == 0, "a hard-linked record is never applied\n");

    // No record: nothing changed, nothing to do.
    pa_reset_records();
    sysexec_stub_reset();
    dispatch_reply_as(HELPER_CALLER_SUCCESSOR, "PA_RESTORE display", out, sizeof out);
    CHECK(pa_field_is(out, "result", "none"), "PA_RESTORE display without a record reports none (got '%s')\n", out);

    // Display record: density reset and the recorded font scale, then the record goes.
    pa_reset_records();
    pa_write_fixture(PA_DISPLAY_RECORD, "density=none\nfont_scale=1.15\n", 0644);
    sysexec_stub_reset();
    sysexec_stub_add_popen("wm density", "Physical density: 240\n", 0);
    sysexec_stub_add_popen("settings get system font_scale", "1.15\n", 0);
    dispatch_reply_as(HELPER_CALLER_SUCCESSOR, "PA_RESTORE display", out, sizeof out);
    const char *const density_reset[] = { "wm", "density", "reset", NULL };
    const char *const font_put[] = { "settings", "put", "system", "font_scale", "1.15", NULL };
    CHECK(ran_argv("/system/bin/wm", density_reset) == 1 && ran_argv("/system/bin/settings", font_put) == 1,
          "PA_RESTORE display puts back density and font scale\n");
    CHECK(pa_field_is(out, "result", "restored") && access(PA_DISPLAY_RECORD, F_OK) != 0,
          "PA_RESTORE display forgets the record once it reads back (got '%s')\n", out);

    // A display record missing a key is no baseline: nothing is written and the record stays.
    pa_reset_records();
    pa_write_fixture(PA_DISPLAY_RECORD, "density=420\n", 0644);
    sysexec_stub_reset();
    sysexec_stub_add_popen("wm density", "Physical density: 240\n", 0);
    sysexec_stub_add_popen("settings get system font_scale", "1.0\n", 0);
    dispatch_reply_as(HELPER_CALLER_SUCCESSOR, "PA_RESTORE display", out, sizeof out);
    CHECK(pa_field_is(out, "result", "unrestored") && pa_ran("wm", "density") == 0 &&
          pa_ran("settings", "put") == 0 && pa_ran("settings", "delete") == 0 &&
          pa_file_is(PA_DISPLAY_RECORD, "density=420\n"),
          "PA_RESTORE display keeps a record missing a key and writes nothing (got '%s')\n", out);

    // A partial Meta or verifier record is no baseline: restore writes nothing and keeps it.
    const char *const partial[][2] = {
        { PA_META_RECORD, "screensaver_default_component=com.meta/.Dream\ncontrolcenter_overlay=allow\n" },
        { PA_META_RECORD, "screensaver_components=com.meta/.Dream\ncontrolcenter_overlay=allow\n" },
        { PA_META_RECORD, "screensaver_components=com.meta/.Dream\nscreensaver_default_component=com.meta/.Dream\n" },
        { PA_VERIFIER_RECORD, "" },
    };
    for (size_t i = 0; i < sizeof partial / sizeof partial[0]; i++) {
        pa_reset_records();
        pa_write_fixture(partial[i][0], partial[i][1], 0644);
        sysexec_stub_reset();
        sysexec_stub_add_popen("settings get secure screensaver_components", "\n", 0);
        sysexec_stub_add_popen("settings get secure screensaver_default_component", "\n", 0);
        sysexec_stub_add_popen("settings get global package_verifier_enable", "0\n", 0);
        sysexec_stub_add_popen("pm path com.facebook.alohaapps.controlcenter", "package:/system/app/cc.apk\n", 0);
        sysexec_stub_add_popen("appops get com.facebook.alohaapps.controlcenter SYSTEM_ALERT_WINDOW", "SYSTEM_ALERT_WINDOW: deny\n", 0);
        dispatch_reply_as(HELPER_CALLER_SUCCESSOR, "PA_RESTORE vendor", out, sizeof out);
        CHECK(pa_field_is(out, "result", "unrestored") && pa_ran("settings", "put") == 0 &&
              pa_ran("settings", "delete") == 0 && pa_ran("appops", "set") == 0 &&
              pa_file_is(partial[i][0], partial[i][1]),
              "PA_RESTORE keeps partial vendor record %zu and writes nothing (got '%s')\n", i, out);
    }

    // Setup treats a partial existing Meta record as invalid and changes nothing.
    pa_reset_records();
    pa_write_fixture(PA_META_RECORD, partial[0][1], 0644);
    sysexec_stub_reset();
    pa_stub_identity("29\n");
    sysexec_stub_add_popen("getprop ro.product.device", "omni\n", 0);
    sysexec_stub_add_popen("settings get secure screensaver_components", "com.meta/.Dream\n", 0);
    sysexec_stub_add_popen("settings get secure screensaver_default_component", "com.meta/.Dream\n", 0);
    sysexec_stub_add_popen("pm path com.facebook.alohaapps.controlcenter", "package:/system/app/cc.apk\n", 0);
    sysexec_stub_add_popen("appops get com.facebook.alohaapps.controlcenter SYSTEM_ALERT_WINDOW", "SYSTEM_ALERT_WINDOW: allow\n", 0);
    sysexec_stub_add_popen("settings get global package_verifier_enable", "1\n", 0);
    dispatch_reply_as(HELPER_CALLER_SUCCESSOR, "PA_VENDOR setup", out, sizeof out);
    CHECK(pa_ran("settings", "put") == 0 && pa_ran("appops", "set") == 0 &&
          pa_field_is(out, "record", "invalid") && pa_field_is(out, "converged", "0") &&
          pa_file_is(PA_META_RECORD, partial[0][1]),
          "setup keeps a partial Meta record and writes nothing (got '%s')\n", out);

    // Meta setup retry: the record already exists and must not be rewritten from blanked values.
    const char *meta = "screensaver_components=com.meta/.Dream\nscreensaver_default_component=com.meta/.Dream\ncontrolcenter_overlay=allow\n";
    pa_reset_records();
    pa_write_fixture(PA_META_RECORD, meta, 0644);
    sysexec_stub_reset();
    pa_stub_identity("29\n");
    sysexec_stub_add_popen("getprop ro.product.device", "omni\n", 0);
    sysexec_stub_add_popen("settings get secure screensaver_components", "\n", 0);
    sysexec_stub_add_popen("settings get secure screensaver_default_component", "\n", 0);
    sysexec_stub_add_popen("pm path com.facebook.alohaapps.controlcenter", "package:/system/app/cc.apk\n", 0);
    sysexec_stub_add_popen("appops get com.facebook.alohaapps.controlcenter SYSTEM_ALERT_WINDOW", "SYSTEM_ALERT_WINDOW: deny\n", 0);
    sysexec_stub_add_popen_times("settings get global package_verifier_enable", "1\n", 0, 2);
    sysexec_stub_add_popen("settings get global package_verifier_enable", "0\n", 0);
    dispatch_reply_as(HELPER_CALLER_SUCCESSOR, "PA_VENDOR setup", out, sizeof out);
    CHECK(pa_file_is(PA_META_RECORD, meta), "a Meta setup retry leaves the existing record unchanged\n");
    const char *const read_components[] = { "settings", "get", "secure", "screensaver_components", NULL };
    CHECK(captured_argv("/system/bin/settings", read_components) == 2,
          "a Meta setup retry that finds its record does not record again\n");
    CHECK(pa_file_is(PA_VERIFIER_RECORD, "package_verifier_enable=1\n"), "the verifier record is written once\n");
    const char *const verifier_off[] = { "settings", "put", "global", "package_verifier_enable", "0", NULL };
    CHECK(ran_argv("/system/bin/settings", verifier_off) == 1, "Meta setup switches the verifier off\n");
    const char *const blank[] = { "settings", "put", "secure", "screensaver_components", "", NULL };
    CHECK(ran_argv("/system/bin/settings", blank) == 0, "an already blank dream is not written\n");
    CHECK(pa_field_is(out, "device", "meta") && pa_field_is(out, "converged", "1") &&
          pa_field_is(out, "record.screensaver_components", "com.meta/.Dream"),
          "Meta setup reports convergence and the record (got '%s')\n", out);

    // A stale .part from an interrupted setup is replaced, and the record is published.
    pa_reset_records();
    pa_write_fixture(PA_SHELLY_RECORD ".part", "stargate_overlay=deny\n", 0644);
    sysexec_stub_reset();
    pa_stub_identity("29\n");
    sysexec_stub_add_popen("getprop ro.product.device", "wall_display\n", 0);
    sysexec_stub_add_popen("pm path cloud.shelly.stargate", "package:/system/app/stargate.apk\n", 0);
    sysexec_stub_add_popen_times("appops get cloud.shelly.stargate SYSTEM_ALERT_WINDOW", "SYSTEM_ALERT_WINDOW: allow\n", 0, 2);
    sysexec_stub_add_popen("appops get cloud.shelly.stargate SYSTEM_ALERT_WINDOW", "SYSTEM_ALERT_WINDOW: deny\n", 0);
    dispatch_reply_as(HELPER_CALLER_SUCCESSOR, "PA_VENDOR setup", out, sizeof out);
    CHECK(pa_file_is(PA_SHELLY_RECORD, "stargate_overlay=allow\n"), "a stale .part is replaced by a fresh record\n");
    CHECK(access(PA_SHELLY_RECORD ".part", F_OK) != 0, "the .part is gone after publishing\n");
    const char *const shelly_deny[] = { "appops", "set", "cloud.shelly.stargate", "SYSTEM_ALERT_WINDOW", "deny", NULL };
    CHECK(ran_argv("/system/bin/appops", shelly_deny) == 1, "Shelly setup denies the launcher overlay\n");
    CHECK(pa_field_is(out, "device", "shelly") && pa_field_is(out, "converged", "1"),
          "Shelly setup reports convergence (got '%s')\n", out);

    // A Meta record PA_RESTORE would refuse is no baseline: setup keeps it and changes nothing.
    const char *bad_meta = "screensaver_components=not-a-component\nscreensaver_default_component=null\n"
                           "controlcenter_overlay=allow\n";
    pa_reset_records();
    pa_write_fixture(PA_META_RECORD, bad_meta, 0644);
    sysexec_stub_reset();
    pa_stub_identity("29\n");
    sysexec_stub_add_popen("getprop ro.product.device", "omni\n", 0);
    sysexec_stub_add_popen("settings get secure screensaver_components", "com.meta/.Dream\n", 0);
    sysexec_stub_add_popen("settings get secure screensaver_default_component", "com.meta/.Dream\n", 0);
    sysexec_stub_add_popen("pm path com.facebook.alohaapps.controlcenter", "package:/system/app/cc.apk\n", 0);
    sysexec_stub_add_popen("appops get com.facebook.alohaapps.controlcenter SYSTEM_ALERT_WINDOW", "SYSTEM_ALERT_WINDOW: allow\n", 0);
    sysexec_stub_add_popen("settings get global package_verifier_enable", "1\n", 0);
    dispatch_reply_as(HELPER_CALLER_SUCCESSOR, "PA_VENDOR setup", out, sizeof out);
    CHECK(pa_file_is(PA_META_RECORD, bad_meta), "setup keeps a Meta record it cannot restore\n");
    CHECK(pa_ran("settings", "put") == 0 && pa_ran("appops", "set") == 0,
          "setup with an unrestorable Meta record writes no setting or app-op\n");
    CHECK(pa_field_is(out, "device", "meta") && pa_field_is(out, "converged", "0") &&
          pa_field_is(out, "record", "invalid"),
          "setup reports the unrestorable Meta record (got '%s')\n", out);

    // The same for a Shelly record PA_RESTORE would refuse.
    pa_reset_records();
    pa_write_fixture(PA_SHELLY_RECORD, "stargate_overlay=bogus\n", 0644);
    sysexec_stub_reset();
    pa_stub_identity("29\n");
    sysexec_stub_add_popen("getprop ro.product.device", "wall_display\n", 0);
    sysexec_stub_add_popen("pm path cloud.shelly.stargate", "package:/system/app/stargate.apk\n", 0);
    sysexec_stub_add_popen("appops get cloud.shelly.stargate SYSTEM_ALERT_WINDOW", "SYSTEM_ALERT_WINDOW: allow\n", 0);
    dispatch_reply_as(HELPER_CALLER_SUCCESSOR, "PA_VENDOR setup", out, sizeof out);
    CHECK(pa_file_is(PA_SHELLY_RECORD, "stargate_overlay=bogus\n"), "setup keeps a Shelly record it cannot restore\n");
    CHECK(pa_ran("appops", "set") == 0, "setup with an unrestorable Shelly record sets no app-op\n");
    CHECK(pa_field_is(out, "converged", "0") && pa_field_is(out, "record", "invalid"),
          "setup reports the unrestorable Shelly record (got '%s')\n", out);

    // A valid existing record still actuates.
    char rv[64];
    pa_reset_records();
    pa_write_fixture(PA_SHELLY_RECORD, "stargate_overlay=allow\n", 0644);
    sysexec_stub_reset();
    pa_stub_identity("29\n");
    sysexec_stub_add_popen("getprop ro.product.device", "wall_display\n", 0);
    sysexec_stub_add_popen("pm path cloud.shelly.stargate", "package:/system/app/stargate.apk\n", 0);
    sysexec_stub_add_popen_times("appops get cloud.shelly.stargate SYSTEM_ALERT_WINDOW", "SYSTEM_ALERT_WINDOW: allow\n", 0, 1);
    sysexec_stub_add_popen("appops get cloud.shelly.stargate SYSTEM_ALERT_WINDOW", "SYSTEM_ALERT_WINDOW: deny\n", 0);
    dispatch_reply_as(HELPER_CALLER_SUCCESSOR, "PA_VENDOR setup", out, sizeof out);
    CHECK(ran_argv("/system/bin/appops", shelly_deny) == 1 && pa_field_is(out, "converged", "1") &&
          !pa_field(out, "record", rv, sizeof rv),
          "setup with a valid existing Shelly record still denies the overlay (got '%s')\n", out);
    pa_reset_records();
}

static void test_pa_handback_admin_observe(void) {
    char out[8192];
    // A HOME target pm path cannot find is refused by the one HOME validator.
    sysexec_stub_reset();
    sysexec_stub_add_popen("pm path com.example.gone", "", 1);
    dispatch_reply_as(HELPER_CALLER_SUCCESSOR, "PA_HANDBACK component com.example.gone/.Home", out, sizeof out);
    CHECK(strcmp(out, "ERR home-unsafe\n") == 0, "PA_HANDBACK refuses a target that is not installed (got '%s')\n", out);
    // Critical packages are never handed HOME, even when installed.
    sysexec_stub_reset();
    sysexec_stub_add_popen("pm path", "package:/system/priv-app/x.apk\n", 0);
    dispatch_reply_as(HELPER_CALLER_SUCCESSOR, "PA_HANDBACK component com.android.systemui/.Home", out, sizeof out);
    CHECK(strcmp(out, "ERR home-unsafe\n") == 0, "PA_HANDBACK refuses a critical package (got '%s')\n", out);
    const char *const unsafe[] = {
        "PA_HANDBACK component android/.Resolver", "PA_HANDBACK component com.android.settings/.FallbackHome",
        "PA_HANDBACK component io.panelassistant.android/.DashboardActivity",
        "PA_HANDBACK component io.github.maxlyth.hapaneld/.MainActivity",
    };
    for (size_t i = 0; i < sizeof unsafe / sizeof unsafe[0]; i++) {
        sysexec_stub_reset();
        sysexec_stub_add_popen("pm path", "package:/system/priv-app/x.apk\n", 0);
        dispatch_reply_as(HELPER_CALLER_SUCCESSOR, unsafe[i], out, sizeof out);
        CHECK(strcmp(out, "ERR home-unsafe\n") == 0, "'%s' is refused as home-unsafe (got '%s')\n", unsafe[i], out);
    }

    sysexec_stub_reset();
    pa_stub_identity("29\n");
    sysexec_stub_add_popen("pm path com.example.home", "package:/data/app/home.apk\n", 0);
    sysexec_stub_add_popen(PA_HOME_QUERY, "com.example.home/.Home\n", 0);
    sysexec_stub_add_popen("pm list packages io.github.maxlyth.hapaneld", "", 0);
    sysexec_stub_add_popen("pm list packages " SUCCESSOR_ID, "package:" SUCCESSOR_ID "\n", 0);
    sysexec_stub_add_popen("pm list packages cloud.shelly.stargate", "Error: service failed\n", 0);
    sysexec_stub_add_popen("getprop ro.build.display.id", "4.0.12\n", 0);
    dispatch_reply_as(HELPER_CALLER_SUCCESSOR, "PA_HANDBACK component com.example.home/.Home", out, sizeof out);
    const char *const handback[] = { "cmd", "package", "set-home-activity", "com.example.home/.Home", NULL };
    CHECK(captured_argv("/system/bin/cmd", handback) == 1, "PA_HANDBACK sets the exact validated component\n");
    CHECK(pa_field_is(out, "handed", "1") && pa_field_is(out, "home", "com.example.home/com.example.home.Home"),
          "PA_HANDBACK expands the short readback and reports the hand-back (got '%s')\n", out);
    CHECK(pa_field_is(out, "legacy", "absent") && pa_field_is(out, "new", "installed") &&
          pa_field_is(out, "shelly", "failed") && pa_field_is(out, "firmware", "4.0.12"),
          "PA_HANDBACK reports the move observation\n");

    sysexec_stub_reset();
    sysexec_stub_add_popen("pm path cloud.shelly.stargate", "package:/system/app/stargate.apk\n", 0);
    sysexec_stub_add_popen(PA_HOME_QUERY, "cloud.shelly.stargate/.activities.SplashActivity\n", 0);
    dispatch_reply_as(HELPER_CALLER_SUCCESSOR, "PA_HANDBACK vendor", out, sizeof out);
    const char *const vendor_home[] = { "cmd", "package", "set-home-activity", "cloud.shelly.stargate/.activities.SplashActivity", NULL };
    CHECK(captured_argv("/system/bin/cmd", vendor_home) == 1 && pa_field_is(out, "handed", "1"),
          "PA_HANDBACK vendor hands HOME to the fixed Shelly splash (got '%s')\n", out);

    const char *const admin[] = {
        "dpm", "set-active-admin", SUCCESSOR_ID "/io.panelassistant.android.platform.PanelDeviceAdminReceiver", NULL
    };
    sysexec_stub_reset();
    sysexec_stub_add_popen_times("dumpsys device_policy", "Enabled Device Admins (User 0, provisioningState: 0):\n", 0, 1);
    sysexec_stub_add_popen("dumpsys device_policy", "  " SUCCESSOR_ID "/.platform.PanelDeviceAdminReceiver:\n", 0);
    dispatch_reply_as(HELPER_CALLER_SUCCESSOR, "PA_ADMIN", out, sizeof out);
    CHECK(captured_argv("/system/bin/dpm", admin) == 1 && pa_field_is(out, "admin", "active"),
          "PA_ADMIN claims a missing device admin and reads it back (got '%s')\n", out);
    sysexec_stub_reset();
    sysexec_stub_add_popen("dumpsys device_policy", "  " SUCCESSOR_ID "/.platform.PanelDeviceAdminReceiver:\n", 0);
    dispatch_reply_as(HELPER_CALLER_SUCCESSOR, "PA_ADMIN", out, sizeof out);
    CHECK(captured_argv("/system/bin/dpm", admin) == 0, "PA_ADMIN leaves an admin Android already lists\n");

    sysexec_stub_reset();
    sysexec_stub_add_popen("pidof cloud.shelly.stargate", "812\n", 0);
    sysexec_stub_add_popen("dumpsys package " SUCCESSOR_ID, "    User 0: ceDataInode=1 installed=true hidden=false suspended=false stopped=false\n", 0);
    sysexec_stub_add_popen(PA_HOME_QUERY, "cloud.shelly.stargate/.activities.SplashActivity\n", 0);
    dispatch_reply_as(HELPER_CALLER_SUCCESSOR, "PA_OBSERVE recovery", out, sizeof out);
    char boot[64], uptime[64];
    CHECK(pa_field(out, "boot_id", boot, sizeof boot) && strlen(boot) == 36 &&
          pa_field(out, "uptime", uptime, sizeof uptime) && strchr(uptime, '.') != NULL,
          "PA_OBSERVE recovery reports boot_id and uptime (got '%s')\n", out);
    CHECK(pa_field_is(out, "vendor_pid", "812") && pa_field_is(out, "stopped", "false"),
          "PA_OBSERVE recovery reports the vendor pid and stopped state\n");
}

// Review gaps: deadline and overflow refusals, launch 0, the policy restore, OBSERVE move and its
// recovery failure, VENDOR on another device, HANDBACK vendor without Shelly, and planted records.
static int pa_ran(const char *a, const char *b) {
    const char *const prefix[] = { a, b, strcmp(a, "cmd") == 0 ? "set-home-activity" : NULL, NULL };
    return sysexec_stub_count_argv_prefix(prefix);
}

static void test_pa_review_gaps(void) {
    char out[8192];

    // A child that times out spends the budget: ERR deadline, and no later write runs.
    sysexec_stub_reset();
    pa_stub_repair_base("33\n");
    sysexec_stub_add_popen("settings get secure enabled_accessibility_services", PA_ACCESSIBILITY_OLD "\n", 0);
    sysexec_stub_add_popen("settings get secure accessibility_enabled", "0\n", 0);
    sysexec_stub_add_popen("appops get " SUCCESSOR_ID " WRITE_SETTINGS", "WRITE_SETTINGS: deny\n", 0);
    sysexec_stub_timeout_run("settings get secure enabled_accessibility_services");
    dispatch_reply_as(HELPER_CALLER_SUCCESSOR, "PA_REPAIR 1 0", out, sizeof out);
    CHECK(strcmp(out, "ERR deadline\n") == 0, "PA_REPAIR past its budget answers ERR deadline (got '%s')\n", out);
    CHECK(pa_ran("settings", "put") == 0 && pa_ran("appops", "set") == 0 &&
          pa_ran("pm", "grant") == 0, "PA_REPAIR runs no write after its budget is spent\n");

    // launch 0 claims the older dashboard class.
    const char *const set_home0[] = {
        "cmd", "package", "set-home-activity", "io.panelassistant.android/io.github.maxlyth.hapaneld.DashboardActivity", NULL
    };
    sysexec_stub_reset();
    pa_stub_identity("29\n");
    sysexec_stub_add_popen("--es operation dashboard_home_policy",
        "Broadcast completed: result=1, data=\"panel-assistant-home-builtin\"\n", 0);
    sysexec_stub_add_popen("--es operation setup_eligibility",
        "Broadcast completed: result=1, data=\"panel-assistant-setup-eligible\"\n", 0);
    sysexec_stub_add_popen_times(PA_HOME_QUERY, "com.example.home/.Home\n", 0, 1);
    sysexec_stub_add_popen(PA_HOME_QUERY, "io.panelassistant.android/io.github.maxlyth.hapaneld.DashboardActivity\n", 0);
    dispatch_reply_as(HELPER_CALLER_SUCCESSOR, "PA_HOME 0 repair", out, sizeof out);
    CHECK(captured_argv("/system/bin/cmd", set_home0) == 1 && pa_field_is(out, "chooser", "set"),
          "PA_HOME 0 sets and reads back the launch-0 dashboard (got '%s')\n", out);

    // The policy names a previous HOME: it is put back under the lock and acknowledged on readback.
    mkdir(PA_TEST_LOCK, 0755);
    const char *const set_previous[] = { "cmd", "package", "set-home-activity", "com.example.home/.Home", NULL };
    const char *const ack_retained[] = {
        "am", "broadcast", "--include-stopped-packages", "-n", PA_RECEIVER, "--es", "operation", "acknowledge_retained_home",
        "--es", "previous_home_component", "com.example.home/.Home", NULL
    };
    for (int readback_ok = 1; readback_ok >= 0; readback_ok--) {
        sysexec_stub_reset();
        pa_stub_identity("29\n");
        sysexec_stub_add_popen("--es operation dashboard_home_policy",
            "Broadcast completed: result=3, data=\"panel-assistant-home-restore:com.example.home/.Home\"\n", 0);
        sysexec_stub_add_popen("pm path com.example.home", "package:/data/app/home.apk\n", 0);
        sysexec_stub_add_popen("--es operation restore_previous_home_shell_lock_eligibility",
            "Broadcast completed: result=1, data=\"panel-assistant-home-restore-shell-locked\"\n", 0);
        sysexec_stub_add_popen(PA_HOME_QUERY, readback_ok ? "com.example.home/.Home\n" : "com.other/.Launcher\n", 0);
        dispatch_reply_as(HELPER_CALLER_SUCCESSOR, "PA_HOME 1 repair", out, sizeof out);
        CHECK(captured_argv("/system/bin/cmd", set_previous) == 1, "PA_HOME restores the previous HOME (got '%s')\n", out);
        CHECK(pa_field_is(out, "policy", "restore") &&
              pa_field_is(out, "chooser", readback_ok ? "retained" : "deferred") &&
              (captured_argv("/system/bin/am", ack_retained) == readback_ok),
              "PA_HOME acknowledges a restore only when it reads back (ok=%d, got '%s')\n", readback_ok, out);
    }

    // OBSERVE move reports each package, HOME and firmware.
    sysexec_stub_reset();
    pa_stub_identity("29\n");
    sysexec_stub_add_popen("pm list packages io.github.maxlyth.hapaneld", "package:io.github.maxlyth.hapaneld\n", 0);
    sysexec_stub_add_popen("pm list packages " SUCCESSOR_ID, "package:" SUCCESSOR_ID "\n", 0);
    sysexec_stub_add_popen("pm list packages cloud.shelly.stargate", "package:cloud.shelly.stargate\n", 0);
    sysexec_stub_add_popen(PA_HOME_QUERY, "x\ncloud.shelly.stargate/.activities.SplashActivity\n", 0);
    sysexec_stub_add_popen("getprop ro.build.display.id", "1.2.3 beta\n", 0);
    dispatch_reply_as(HELPER_CALLER_SUCCESSOR, "PA_OBSERVE move", out, sizeof out);
    CHECK(pa_field_is(out, "legacy", "installed") && pa_field_is(out, "new", "installed") &&
          pa_field_is(out, "shelly", "installed") && pa_field_is(out, "firmware", "1.2.3 beta") &&
          pa_field_is(out, "home", "cloud.shelly.stargate/.activities.SplashActivity"),
          "PA_OBSERVE move reports packages, HOME and firmware (got '%s')\n", out);

    // OBSERVE recovery without a boot id refuses rather than reporting a partial answer.
    pa_test_boot_id_path = "/nonexistent/boot_id";
    sysexec_stub_reset();
    dispatch_reply_as(HELPER_CALLER_SUCCESSOR, "PA_OBSERVE recovery", out, sizeof out);
    pa_test_boot_id_path = "/proc/sys/kernel/random/boot_id";
    CHECK(strcmp(out, "ERR failed\n") == 0, "PA_OBSERVE recovery without a boot id answers ERR failed (got '%s')\n", out);

    // VENDOR setup on a device that is neither Meta nor Shelly writes nothing.
    pa_reset_records();
    sysexec_stub_reset();
    pa_stub_identity("29\n");
    sysexec_stub_add_popen("getprop ro.product.device", "nspanel\n", 0);
    dispatch_reply_as(HELPER_CALLER_SUCCESSOR, "PA_VENDOR setup", out, sizeof out);
    CHECK(pa_field_is(out, "device", "other") && pa_field_is(out, "converged", "1"),
          "PA_VENDOR setup on another device reports device=other (got '%s')\n", out);
    CHECK(pa_ran("settings", "put") == 0 && pa_ran("appops", "set") == 0 &&
          access(PA_META_RECORD, F_OK) != 0 && access(PA_SHELLY_RECORD, F_OK) != 0 && access(PA_VERIFIER_RECORD, F_OK) != 0,
          "PA_VENDOR setup on another device writes no setting, op or record\n");

    // HANDBACK vendor without the Shelly launcher refuses instead of naming a missing HOME.
    sysexec_stub_reset();
    sysexec_stub_add_popen(PA_HOME_QUERY, "com.other/.Launcher\n", 0);
    dispatch_reply_as(HELPER_CALLER_SUCCESSOR, "PA_HANDBACK vendor", out, sizeof out);
    CHECK(strcmp(out, "ERR home-unsafe\n") == 0 && pa_ran("cmd", "package") == 0,
          "PA_HANDBACK vendor without Shelly answers home-unsafe and sets nothing (got '%s')\n", out);

    // A planted Meta or verifier record whose values are not the expected shape is never written back.
    const char *const planted[] = {
        "screensaver_components=com.meta/.Dream;reboot\nscreensaver_default_component=null\ncontrolcenter_overlay=allow\n",
        "screensaver_components=com.meta/.Dream\nscreensaver_default_component=--user 0\ncontrolcenter_overlay=allow\n",
    };
    for (size_t i = 0; i < sizeof planted / sizeof planted[0]; i++) {
        pa_reset_records();
        pa_write_fixture(PA_META_RECORD, planted[i], 0644);
        sysexec_stub_reset();
        dispatch_reply_as(HELPER_CALLER_SUCCESSOR, "PA_RESTORE vendor", out, sizeof out);
        CHECK(pa_field_is(out, "result", "unrestored") && pa_ran("settings", "put") == 0 &&
              pa_ran("settings", "delete") == 0 && access(PA_META_RECORD, F_OK) == 0,
              "PA_RESTORE refuses a malformed Meta record %zu and keeps it (got '%s')\n", i, out);
    }
    pa_reset_records();
    pa_write_fixture(PA_VERIFIER_RECORD, "package_verifier_enable=7\n", 0644);
    sysexec_stub_reset();
    dispatch_reply_as(HELPER_CALLER_SUCCESSOR, "PA_RESTORE vendor", out, sizeof out);
    CHECK(pa_field_is(out, "result", "unrestored") && pa_ran("settings", "put") == 0,
          "PA_RESTORE refuses a verifier value other than 0, 1 or null (got '%s')\n", out);
    // An empty or partial record is no baseline: setup never disables the verifier or blanks Meta's
    // screensaver on it, and restore keeps it and reports unrestored.
    const char *const off[] = { "settings", "put", "global", "package_verifier_enable", "0", NULL };
    const char *const thin[][2] = {
        { PA_VERIFIER_RECORD, "" },
        { PA_VERIFIER_RECORD, "package_verifier_enable=\n" },
        { PA_META_RECORD, "screensaver_components=com.meta/.Dream\n" },
    };
    for (size_t i = 0; i < sizeof thin / sizeof thin[0]; i++) {
        pa_reset_records();
        pa_write_fixture(thin[i][0], thin[i][1], 0644);
        sysexec_stub_reset();
        pa_stub_identity("29\n");
        sysexec_stub_add_popen("getprop ro.product.device", "omni\n", 0);
        dispatch_reply_as(HELPER_CALLER_SUCCESSOR, "PA_VENDOR setup", out, sizeof out);
        int disabled = ran_argv("/system/bin/settings", off);
        int blanked = pa_ran("settings", "put") - disabled;
        CHECK((i < 2 ? disabled : blanked) == 0 && access(thin[i][0], F_OK) == 0,
              "PA_VENDOR setup never acts on thin record %zu (got '%s')\n", i, out);
        sysexec_stub_reset();
        pa_stub_identity("29\n");
        dispatch_reply_as(HELPER_CALLER_SUCCESSOR, "PA_RESTORE vendor", out, sizeof out);
        CHECK(pa_field_is(out, "result", "unrestored") && pa_ran("settings", "put") == 0 &&
              access(thin[i][0], F_OK) == 0,
              "PA_RESTORE keeps thin record %zu and reports unrestored (got '%s')\n", i, out);
    }
    // A well-formed two-component list still restores.
    pa_reset_records();
    pa_write_fixture(PA_META_RECORD,
        "screensaver_components=com.meta/.Dream,com.meta.b/.Frame\nscreensaver_default_component=null\ncontrolcenter_overlay=absent\n", 0644);
    sysexec_stub_reset();
    sysexec_stub_add_popen("settings get secure screensaver_components", "com.meta/.Dream,com.meta.b/.Frame\n", 0);
    sysexec_stub_add_popen("settings get secure screensaver_default_component", "null\n", 0);
    dispatch_reply_as(HELPER_CALLER_SUCCESSOR, "PA_RESTORE vendor", out, sizeof out);
    const char *const put_list[] = { "settings", "put", "secure", "screensaver_components", "com.meta/.Dream,com.meta.b/.Frame", NULL };
    CHECK(ran_argv("/system/bin/settings", put_list) == 1,
          "PA_RESTORE writes back a well-formed component list (got '%s')\n", out);
    pa_reset_records();
}

static void test_pa_commands(void) {
    test_pa_admission_and_grammar();
    test_pa_identity();
    test_pa_repair();
    test_pa_home_chooser();
    test_pa_home_lock();
    test_pa_records();
    test_pa_handback_admin_observe();
    test_pa_review_gaps();
}


int main(int argc, char **argv) {
    if (argc == 4 && strcmp(argv[2], "--bridge-fixture") == 0) {
        int fd = atoi(argv[3]);
        if (fd < 3 || write(fd, "R", 1) != 1) return 1;
        close(fd);
        sleep(30);
        return 0;
    }
    test_dispatch_exact_verb();
    // Every later case dispatches through the same table; an inexact matcher would route them to
    // the wrong handlers, so stop with this verdict rather than a cascade or a signal exit.
    if (failures) return 1;
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
    test_govread_request_admission();
    test_govread_fixed_nodes();
    test_govread_current_cardinality();
    test_govread_invalid_content();
    test_govread_token_bounds();
    test_govread_raw_bound();
    test_govread_read_failures();
    test_govread_no_exec();
    test_sysctl_execution_results();
    test_bootchime_protocol();
    test_screencap_command();
    test_line_accumulator();
    test_reply_stream_writes();
    test_server_send_deadline();
    test_input_watch_contract();
    test_gpio_watch_contract();
    test_subscriber_admission();
    test_grab_subscription_ownership();
    test_dead_subscriber_on_broadcast_releases_grab();
    test_ledjni_ioctl();
    test_conn_cap();
    test_room_climate_input_allowlist();
    test_vi530x_start_contract();
    test_room_climate_event_node_names();
    test_room_climate_input_discovery();
    test_room_climate_hwmon_discovery();
    test_room_climate_direct_discovery();
    test_grant_verb();
    test_grant_accessibility();
    test_pa_commands();

    if (failures) { printf("UNIT FAILED: %d assertion(s)\n", failures); return 1; }
    printf("UNIT OK\n");
    return 0;
}
