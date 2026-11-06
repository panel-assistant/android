// Exercise real dispatch with a private sysfs-shaped tree and targeted syscall failures. This source
// also compiles with the pre-feature core: new positive replies fail without requiring a new API.
#define _GNU_SOURCE
#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <ftw.h>
#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <unistd.h>
#include "dispatch.h"
#include "sysexec_stub.h"

#ifndef SYSFS_READ_CLASS_ROOT
#error "SYSFS_READ_CLASS_ROOT must name the private fixture"
#endif

static int failures, scan_error, scan_seen, access_error, access_calls, access_bad, write_opens;
static const char *read_error_suffix;
static const char *read_short_suffix, *read_interrupt_suffix;
static int read_after_data, read_seen, read_interrupted;
static size_t read_short_size = 1;
static int gpio_open_error;
#define CHECK(c, text) do { if (!(c)) { printf("FAIL: %s (%s:%d)\n", text, __FILE__, __LINE__); failures++; } } while (0)

static int own_fd(int fd, const char *suffix) {
    char link[64], path[1024];
    snprintf(link, sizeof link, "/proc/self/fd/%d", fd);
    ssize_t n = readlink(link, path, sizeof path - 1);
    if (n < 0) return 0;
    path[n] = 0;
    size_t root = strlen(SYSFS_READ_CLASS_ROOT), tail = strlen(suffix);
    return !strncmp(path, SYSFS_READ_CLASS_ROOT, root) && path[root] == '/' &&
        (size_t)n >= tail && !strcmp(path + n - tail, suffix);
}

ssize_t __real_read(int fd, void *buffer, size_t size);
ssize_t __wrap_read(int fd, void *buffer, size_t size) {
    if (read_interrupt_suffix && !read_interrupted && own_fd(fd, read_interrupt_suffix)) {
        read_interrupted = 1; errno = EINTR; return -1;
    }
    if (read_error_suffix && own_fd(fd, read_error_suffix) && (!read_after_data || read_seen++)) {
        errno = EIO; return -1;
    }
    if (read_short_suffix && own_fd(fd, read_short_suffix) && size > read_short_size) size = read_short_size;
    return __real_read(fd, buffer, size);
}
ssize_t __wrap___read_chk(int fd, void *buffer, size_t size, size_t capacity) {
    if (size > capacity) abort();
    return __wrap_read(fd, buffer, size);
}

struct dirent *__real_readdir(DIR *dir);
struct dirent *__wrap_readdir(DIR *dir) {
    if (scan_error && scan_seen) { errno = EIO; return NULL; }
    struct dirent *entry = __real_readdir(dir);
    if (scan_error && entry && !strcmp(entry->d_name, "relay1")) scan_seen = 1;
    return entry;
}

int __real_faccessat(int dirfd, const char *path, int mode, int flags);
int __wrap_faccessat(int dirfd, const char *path, int mode, int flags) {
    access_calls++;
    if (strcmp(path, "value") || mode != W_OK || flags != 0) access_bad++;
    if (access_error) { errno = access_error; return -1; }
    return __real_faccessat(dirfd, path, mode, flags);
}

int __real_openat(int dirfd, const char *path, int flags, ...);
int __wrap_openat(int dirfd, const char *path, int flags, ...) {
    mode_t mode = 0;
    if ((flags & O_CREAT) || (flags & O_TMPFILE) == O_TMPFILE) {
        va_list ap; va_start(ap, flags); mode = (mode_t)va_arg(ap, int); va_end(ap);
    }
    if (flags & (O_WRONLY | O_RDWR)) { write_opens++; errno = EPERM; return -1; }
    return __real_openat(dirfd, path, flags, mode);
}
int __real_open(const char *path, int flags, ...);
int __wrap_open(const char *path, int flags, ...) {
    mode_t mode = 0;
    if ((flags & O_CREAT) || (flags & O_TMPFILE) == O_TMPFILE) {
        va_list ap; va_start(ap, flags); mode = (mode_t)va_arg(ap, int); va_end(ap);
    }
    if (gpio_open_error && !strcmp(path, SYSFS_READ_CLASS_ROOT "/gpio/gpio147")) {
        errno = gpio_open_error; return -1;
    }
    return __real_open(path, flags, mode);
}

static int remove_entry(const char *path, const struct stat *st, int type, struct FTW *f) {
    (void)st; (void)type; (void)f; return remove(path);
}
static void clean(void) { (void)nftw(SYSFS_READ_CLASS_ROOT, remove_entry, 32, FTW_DEPTH | FTW_PHYS); }
static void bytes(const char *path, const void *data, size_t length) {
    int fd = open(path, O_CREAT | O_TRUNC | O_WRONLY | O_CLOEXEC, 0600);
    CHECK(fd >= 0, "Sysfs read fixture file opens");
    if (fd >= 0) {
        CHECK(write(fd, data, length) == (ssize_t)length, "Sysfs read fixture file writes");
        CHECK(close(fd) == 0, "Sysfs read fixture file closes");
    }
}
static void text(const char *path, const char *value) { bytes(path, value, strlen(value)); }
static void directory(const char *path) { CHECK(mkdir(path, 0700) == 0, "Sysfs read fixture directory opens"); }
static void setup(void) {
    scan_error = scan_seen = access_error = access_calls = access_bad = write_opens = 0;
    read_error_suffix = read_short_suffix = read_interrupt_suffix = NULL;
    read_after_data = read_seen = read_interrupted = 0;
    read_short_size = 1; gpio_open_error = 0;
    clean(); directory(SYSFS_READ_CLASS_ROOT); directory(SYSFS_READ_CLASS_ROOT "/gpio");
    text(SYSFS_READ_CLASS_ROOT "/gpio/export", "not-exported\n");
    sysexec_stub_reset();
}
static void command(const char *line, char out[128]) {
    int pair[2]; CHECK(socketpair(AF_UNIX, SOCK_STREAM, 0, pair) == 0, "Sysfs read dispatch socket opens");
    conn_ctx ctx = { .fd = pair[0] }; char input[256]; snprintf(input, sizeof input, "%s", line);
    dispatch(&ctx, input); shutdown(pair[0], SHUT_WR);
    size_t used = 0;
    while (used + 1 < 128) {
        ssize_t n = read(pair[1], out + used, 127 - used);
        if (n <= 0) break;
        used += (size_t)n;
    }
    out[used] = 0; close(pair[0]); close(pair[1]);
}
static void expect(const char *request, const char *response, const char *assertion) {
    char out[128]; command(request, out); CHECK(!strcmp(out, response), assertion);
    CHECK(strlen(out) <= 64, "Sysfs read frames stay inside the exact wire bound");
}
static void gpio(void) {
    directory(SYSFS_READ_CLASS_ROOT "/gpio/gpio147");
    text(SYSFS_READ_CLASS_ROOT "/gpio/gpio147/value", "0\n");
    text(SYSFS_READ_CLASS_ROOT "/gpio/gpio147/direction", "out\n");
    text(SYSFS_READ_CLASS_ROOT "/gpio/gpio147/edge", "rising\n");
}

static void admission(void) {
    setup();
    expect("PING", "OK\n", "Sysfs read pre-feature PING control stays green");
    const char *bad[] = { "RELAYREAD", "RELAYREAD RELAY", "RELAYREAD relay COUNT",
        "RELAYREAD OTHER COUNT", "RELAYREAD RELAY count", "RELAYREAD RELAY 0",
        "RELAYREAD RELAY 17", "RELAYREAD RELAY 01", "RELAYREAD RELAY +1",
        "RELAYREAD RELAY  1", "RELAYREAD RELAY 1 extra", "RELAYREAD RELAY COUNT ",
        "RELAYREAD RELAY /proc", "GPIOREAD", "GPIOREAD -1", "GPIOREAD +1", "GPIOREAD 00",
        "GPIOREAD 65536", "GPIOREAD 99999999999999999999", "GPIOREAD 1 extra", "GPIOREAD 1 ",
        "GPIOREAD ../1", "GPIOREAD 1\nPING" };
    for (size_t i = 0; i < sizeof bad / sizeof bad[0]; i++)
        expect(bad[i], "ERR\n", "Sysfs read rejects unsupported selectors and noncanonical arguments");
    CHECK(access_calls == 0 && !write_opens && sysexec_stub_count_run("") == 0 &&
        sysexec_stub_count_argv_calls() == 0, "Sysfs read invalid requests have no hardware effects");
}

static void relay_topology(void) {
    setup();
    expect("RELAYREAD RELAY COUNT", "RELAYREAD RELAY COUNT=0\n", "Relay read known missing class is zero topology");
    directory(SYSFS_READ_CLASS_ROOT "/relay");
    text(SYSFS_READ_CLASS_ROOT "/relay/relay2", "1\n");
    expect("RELAYREAD RELAY COUNT", "RELAYREAD RELAY COUNT=0\n", "Relay read count starts at relay1 rather than counting arbitrary names");
    text(SYSFS_READ_CLASS_ROOT "/relay/relay1", "broken\n");
    text(SYSFS_READ_CLASS_ROOT "/relay/relay4", "1\n");
    expect("RELAYREAD RELAY COUNT", "RELAYREAD RELAY COUNT=2\n", "Relay read contiguous topology does not depend on scalar readability");
    CHECK(symlink("relay", SYSFS_READ_CLASS_ROOT "/st_relay") == 0, "Relay class alias fixture links");
    CHECK(symlink("st_relay", SYSFS_READ_CLASS_ROOT "/strelay") == 0, "Relay class second alias fixture links");
    expect("RELAYREAD ST_RELAY COUNT", "RELAYREAD ST_RELAY COUNT=2\n", "Relay read supports vendor class directory symlinks");
    expect("RELAYREAD STRELAY 2", "RELAYREAD STRELAY RELAY=2 VALUE=1\n", "Relay read echoes the exact class and relay selector");
    scan_error = 1; scan_seen = 0;
    expect("RELAYREAD RELAY COUNT", "RELAYREAD RELAY COUNT=?\n", "Relay read incomplete directory scan never proves a partial count");
    scan_error = scan_seen = 0;
    unlink(SYSFS_READ_CLASS_ROOT "/strelay");
    CHECK(symlink("missing-target", SYSFS_READ_CLASS_ROOT "/strelay") == 0, "Relay dangling class fixture links");
    expect("RELAYREAD STRELAY COUNT", "RELAYREAD STRELAY COUNT=?\n", "Relay read dangling class alias remains unknown rather than absent");
    unlink(SYSFS_READ_CLASS_ROOT "/strelay"); text(SYSFS_READ_CLASS_ROOT "/strelay", "not-directory\n");
    expect("RELAYREAD STRELAY COUNT", "RELAYREAD STRELAY COUNT=?\n", "Relay read non-directory class remains unknown rather than absent");
    for (unsigned i = 1; i <= 16; i++) {
        char path[1024]; snprintf(path, sizeof path, "%s/relay/relay%u", SYSFS_READ_CLASS_ROOT, i); text(path, "0\n");
    }
    expect("RELAYREAD RELAY COUNT", "RELAYREAD RELAY COUNT=16\n", "Relay read accepts exactly sixteen contiguous names");
    text(SYSFS_READ_CLASS_ROOT "/relay/relay17", "0\n");
    expect("RELAYREAD RELAY COUNT", "RELAYREAD RELAY COUNT=?\n", "Relay read unsupported seventeenth contiguous name cannot be clamped");
}

static void relay_scan_bound(void) {
    setup(); directory(SYSFS_READ_CLASS_ROOT "/relay");
    text(SYSFS_READ_CLASS_ROOT "/relay/relay1", "1\n");
    for (unsigned i = 0; i < 253; i++) {
        char path[1024]; snprintf(path, sizeof path, "%s/relay/other%u", SYSFS_READ_CLASS_ROOT, i); text(path, "");
    }
    expect("RELAYREAD RELAY COUNT", "RELAYREAD RELAY COUNT=1\n", "Relay read exact directory entry bound remains authoritative");
    text(SYSFS_READ_CLASS_ROOT "/relay/overflow", "");
    expect("RELAYREAD RELAY COUNT", "RELAYREAD RELAY COUNT=?\n", "Relay read directory entry overflow remains unknown");
}

static void relay_values(void) {
    setup(); directory(SYSFS_READ_CLASS_ROOT "/relay");
    text(SYSFS_READ_CLASS_ROOT "/relay/relay1", " \t\v\f1\r\n");
    expect("RELAYREAD RELAY 1", "RELAYREAD RELAY RELAY=1 VALUE=1\n", "Relay read normalizes only a complete binary scalar");
    read_short_suffix = read_interrupt_suffix = "/relay1";
    expect("RELAYREAD RELAY 1", "RELAYREAD RELAY RELAY=1 VALUE=1\n", "Relay read retries interruption and assembles short reads before accepting a scalar");
    read_short_suffix = read_interrupt_suffix = NULL;
    text(SYSFS_READ_CLASS_ROOT "/relay/relay1", "0\n");
    expect("RELAYREAD RELAY 1", "RELAYREAD RELAY RELAY=1 VALUE=0\n", "Relay read preserves a measured zero");
    char raw[65]; memset(raw, ' ', sizeof raw); raw[0] = '1'; bytes(SYSFS_READ_CLASS_ROOT "/relay/relay1", raw, 64);
    expect("RELAYREAD RELAY 1", "RELAYREAD RELAY RELAY=1 VALUE=1\n", "Relay read accepts the exact sixty-four-byte scalar bound");
    bytes(SYSFS_READ_CLASS_ROOT "/relay/relay1", raw, 65);
    expect("RELAYREAD RELAY 1", "RELAYREAD RELAY RELAY=1 VALUE=?\n", "Relay read scalar overflow cannot expose a valid prefix");
    const char *bad[] = { "", "11\n", "2\n", "1 junk\n" };
    for (size_t i = 0; i < sizeof bad / sizeof bad[0]; i++) {
        text(SYSFS_READ_CLASS_ROOT "/relay/relay1", bad[i]);
        expect("RELAYREAD RELAY 1", "RELAYREAD RELAY RELAY=1 VALUE=?\n", "Relay read malformed scalar remains unknown");
    }
    const char nul[] = "1\0hidden"; bytes(SYSFS_READ_CLASS_ROOT "/relay/relay1", nul, sizeof nul - 1);
    expect("RELAYREAD RELAY 1", "RELAYREAD RELAY RELAY=1 VALUE=?\n", "Relay read embedded NUL invalidates a valid prefix");
    text(SYSFS_READ_CLASS_ROOT "/relay/relay1", "1\n"); read_error_suffix = "/relay1";
    expect("RELAYREAD RELAY 1", "RELAYREAD RELAY RELAY=1 VALUE=?\n", "Relay read I/O failure never fabricates OFF");
    read_after_data = 1; read_seen = 0;
    read_short_suffix = "/relay1";
    expect("RELAYREAD RELAY 1", "RELAYREAD RELAY RELAY=1 VALUE=?\n", "Relay read later I/O failure invalidates an otherwise valid prefix");
    read_error_suffix = read_short_suffix = NULL; read_after_data = 0;
    expect("RELAYREAD RELAY 16", "RELAYREAD RELAY RELAY=16 VALUE=?\n", "Relay read missing scalar remains unknown");
    unlink(SYSFS_READ_CLASS_ROOT "/relay/relay1");
    CHECK(mkfifo(SYSFS_READ_CLASS_ROOT "/relay/relay1", 0600) == 0, "Relay nonregular scalar fixture opens");
    expect("RELAYREAD RELAY 1", "RELAYREAD RELAY RELAY=1 VALUE=?\n", "Relay read nonregular scalar remains unknown without blocking");
}

static void gpio_values_and_preparation(void) {
    setup(); gpio();
    expect("GPIOREAD 147", "GPIOREAD 147 VALUE=0 PREPARED=1\n", "GPIO read value and already prepared output are independent observations");
    gpio_open_error = EACCES;
    expect("GPIOREAD 147", "GPIOREAD 147 VALUE=? PREPARED=?\n", "GPIO read directory denial cannot prove the value is not writable");
    gpio_open_error = 0;
    CHECK(symlink("gpio147", SYSFS_READ_CLASS_ROOT "/gpio/gpio148") == 0, "GPIO directory alias fixture links");
    expect("GPIOREAD 148", "GPIOREAD 148 VALUE=0 PREPARED=1\n", "GPIO read supports kernel GPIO directory symlinks");
    expect("GPIOREAD 0", "GPIOREAD 0 VALUE=? PREPARED=0\n", "GPIO read unexported zero pin remains a read-only negative preparation");
    expect("GPIOREAD 65535", "GPIOREAD 65535 VALUE=? PREPARED=0\n", "GPIO read accepts its maximum pin without exporting it");
    read_error_suffix = "/value";
    expect("GPIOREAD 147", "GPIOREAD 147 VALUE=? PREPARED=1\n", "GPIO read unavailable value does not erase proven output preparation");
    read_error_suffix = NULL; access_error = EIO;
    expect("GPIOREAD 147", "GPIOREAD 147 VALUE=0 PREPARED=?\n", "GPIO read unexpected access I/O preserves measured value and unknown preparation");
    text(SYSFS_READ_CLASS_ROOT "/gpio/gpio147/direction", "in\n");
    expect("GPIOREAD 147", "GPIOREAD 147 VALUE=0 PREPARED=0\n", "GPIO read known input proves unprepared despite unknown access");
    text(SYSFS_READ_CLASS_ROOT "/gpio/gpio147/direction", "sideways\n");
    access_error = EACCES;
    expect("GPIOREAD 147", "GPIOREAD 147 VALUE=0 PREPARED=0\n", "GPIO read known access denial proves unprepared despite malformed direction");
    const int denied[] = { ENOENT, ENOTDIR, EACCES, EPERM, EROFS };
    text(SYSFS_READ_CLASS_ROOT "/gpio/gpio147/direction", "out\n");
    for (size_t i = 0; i < sizeof denied / sizeof denied[0]; i++) {
        access_error = denied[i];
        expect("GPIOREAD 147", "GPIOREAD 147 VALUE=0 PREPARED=0\n", "GPIO read expected access negatives never claim prepared output");
    }
    access_error = 0; read_error_suffix = "/direction";
    expect("GPIOREAD 147", "GPIOREAD 147 VALUE=0 PREPARED=?\n", "GPIO read direction I/O never fabricates prepared output");
    read_after_data = 1; read_seen = 0; read_short_suffix = "/direction"; read_short_size = 3;
    expect("GPIOREAD 147", "GPIOREAD 147 VALUE=0 PREPARED=?\n", "GPIO read later direction I/O failure invalidates an otherwise valid output prefix");
    read_error_suffix = read_short_suffix = NULL; read_after_data = 0; read_short_size = 1;
    const char *bad[] = { "", "sideways\n", "out junk\n" };
    for (size_t i = 0; i < sizeof bad / sizeof bad[0]; i++) {
        text(SYSFS_READ_CLASS_ROOT "/gpio/gpio147/direction", bad[i]);
        expect("GPIOREAD 147", "GPIOREAD 147 VALUE=0 PREPARED=?\n", "GPIO read malformed direction remains unknown");
    }
    char raw[65]; memset(raw, ' ', sizeof raw); memcpy(raw, "out", 3);
    bytes(SYSFS_READ_CLASS_ROOT "/gpio/gpio147/direction", raw, 64);
    expect("GPIOREAD 147", "GPIOREAD 147 VALUE=0 PREPARED=1\n", "GPIO read accepts the exact direction byte bound");
    bytes(SYSFS_READ_CLASS_ROOT "/gpio/gpio147/direction", raw, 65);
    expect("GPIOREAD 147", "GPIOREAD 147 VALUE=0 PREPARED=?\n", "GPIO read direction overflow cannot prove prepared output");
    const char nul[] = "out\0hidden"; bytes(SYSFS_READ_CLASS_ROOT "/gpio/gpio147/direction", nul, sizeof nul - 1);
    expect("GPIOREAD 147", "GPIOREAD 147 VALUE=0 PREPARED=?\n", "GPIO read direction NUL cannot prove prepared output");
    text(SYSFS_READ_CLASS_ROOT "/gpio/gpio147/direction", "out\n");
    bytes(SYSFS_READ_CLASS_ROOT "/gpio/gpio147/value", nul, sizeof nul - 1);
    expect("GPIOREAD 147", "GPIOREAD 147 VALUE=? PREPARED=1\n", "GPIO read malformed value remains independent from output preparation");
    CHECK(access_calls > 0 && !access_bad && !write_opens && sysexec_stub_count_run("") == 0 &&
        sysexec_stub_count_argv_calls() == 0, "Sysfs read uses only fixed read and writable checks without command or mutation");
}

int main(void) {
    admission(); relay_topology(); relay_scan_bound(); relay_values(); gpio_values_and_preparation();
    clean();
    if (failures) return 1;
    puts("SYSFS READ BOUNDARY OK"); return 0;
}
