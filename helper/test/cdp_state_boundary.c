// Real dispatch with a private proc-shaped tree. Only syscalls that inject errors or bounded scan
// inventories are interposed; the same fixture compiles against the pre-CDPSTATE production core.
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

#ifndef CDP_PROC_ROOT
#error "CDP_PROC_ROOT must name the private fixture"
#endif
#define TABLE_BYTES (128u * 1024u)
static int failures, observing, observations, root_error, table_open_error[2], pid_open_missing;
static int vanish_comm, confirm_error, scan_error, scan_seen, synthetic_entries = -1, synthetic_index;
static int ordered_pids;
static const char *read_suffix;
static int read_after_data, read_seen, read_interrupt, read_interrupted;
static int read_error = EIO;
static size_t read_chunk;
#define CHECK(c, text) do { if (!(c)) { printf("FAIL: %s (%s:%d)\n", text, __FILE__, __LINE__); failures++; } } while (0)

static int fd_path(int fd, char path[1024]) {
    char link[64]; snprintf(link, sizeof link, "/proc/self/fd/%d", fd);
    ssize_t n = readlink(link, path, 1023);
    if (n < 0) return 0;
    path[n] = 0; return 1;
}
static int exact_fd(int fd, const char *path) {
    char actual[1024]; return fd_path(fd, actual) && !strcmp(actual, path);
}
static int suffix_fd(int fd, const char *suffix) {
    char actual[1024]; if (!fd_path(fd, actual)) return 0;
    size_t n = strlen(actual), root = strlen(CDP_PROC_ROOT), tail = strlen(suffix);
    return !strncmp(actual, CDP_PROC_ROOT, root) && actual[root] == '/' &&
        n >= tail && !strcmp(actual + n - tail, suffix);
}
static int open_failure(const char *path) {
    if (!observing) return 0;
    if (!strncmp(path, CDP_PROC_ROOT, strlen(CDP_PROC_ROOT))) observations++;
    if (!strcmp(path, CDP_PROC_ROOT)) return root_error;
    if (!strcmp(path, CDP_PROC_ROOT "/net/tcp")) return table_open_error[0];
    if (!strcmp(path, CDP_PROC_ROOT "/net/tcp6")) return table_open_error[1];
    return 0;
}
int __real_open(const char *path, int flags, ...);
int __wrap_open(const char *path, int flags, ...) {
    mode_t mode = 0;
    if ((flags & O_CREAT) || (flags & O_TMPFILE) == O_TMPFILE) {
        va_list ap; va_start(ap, flags); mode = (mode_t)va_arg(ap, int); va_end(ap);
    }
    int error = open_failure(path); if (error) { errno = error; return -1; }
    return __real_open(path, flags, mode);
}
int __real_openat(int dirfd, const char *path, int flags, ...);
int __wrap_openat(int dirfd, const char *path, int flags, ...) {
    mode_t mode = 0;
    if ((flags & O_CREAT) || (flags & O_TMPFILE) == O_TMPFILE) {
        va_list ap; va_start(ap, flags); mode = (mode_t)va_arg(ap, int); va_end(ap);
    }
    if (observing) {
        observations++;
        if (pid_open_missing && exact_fd(dirfd, CDP_PROC_ROOT) && !strcmp(path, "71")) { errno = ENOENT; return -1; }
        if (vanish_comm && exact_fd(dirfd, CDP_PROC_ROOT "/71") && !strcmp(path, "comm")) {
            CHECK(unlink(CDP_PROC_ROOT "/71/comm") == 0, "CDP vanished comm fixture removes its own file");
            CHECK(rmdir(CDP_PROC_ROOT "/71") == 0, "CDP vanished comm fixture removes its own PID directory");
            vanish_comm = 0; errno = ENOENT; return -1;
        }
    }
    return __real_openat(dirfd, path, flags, mode);
}
int __real_fstatat(int dirfd, const char *path, struct stat *st, int flags);
int __wrap_fstatat(int dirfd, const char *path, struct stat *st, int flags) {
    if (observing && confirm_error && exact_fd(dirfd, CDP_PROC_ROOT) && !strcmp(path, "71")) {
        errno = confirm_error; return -1;
    }
    return __real_fstatat(dirfd, path, st, flags);
}
ssize_t __real_read(int fd, void *buffer, size_t size);
ssize_t __wrap_read(int fd, void *buffer, size_t size) {
    if (observing && read_suffix && suffix_fd(fd, read_suffix)) {
        if (read_interrupt && !read_interrupted) { read_interrupted = 1; errno = EINTR; return -1; }
        if (read_after_data && read_seen++) { errno = read_error; return -1; }
        if (read_chunk && size > read_chunk) size = read_chunk;
    }
    return __real_read(fd, buffer, size);
}
ssize_t __wrap___read_chk(int fd, void *buffer, size_t size, size_t capacity) {
    if (size > capacity) abort();
    return __wrap_read(fd, buffer, size);
}
struct dirent *__real_readdir(DIR *dir);
struct dirent *__wrap_readdir(DIR *dir) {
    if (observing && exact_fd(dirfd(dir), CDP_PROC_ROOT)) {
        if (ordered_pids || synthetic_entries >= 0) {
            static struct dirent entry;
            static const char *const ordered[] = { ".", "..", "net", "71", "72" };
            if (synthetic_index >= (ordered_pids ? 5 : synthetic_entries)) return NULL;
            memset(&entry, 0, sizeof entry);
            entry.d_ino = (ino_t)synthetic_index + 1; entry.d_type = DT_DIR;
            if (ordered_pids) strcpy(entry.d_name, ordered[synthetic_index]);
            else if (synthetic_index == 0) strcpy(entry.d_name, ".");
            else if (synthetic_index == 1) strcpy(entry.d_name, "..");
            else if (synthetic_index == 2) strcpy(entry.d_name, "net");
            else snprintf(entry.d_name, sizeof entry.d_name, "other%d", synthetic_index);
            synthetic_index++; return &entry;
        }
        if (scan_error && scan_seen) { errno = EIO; return NULL; }
        struct dirent *entry = __real_readdir(dir);
        if (scan_error && entry && !strcmp(entry->d_name, "71")) scan_seen = 1;
        return entry;
    }
    return __real_readdir(dir);
}

static int remove_entry(const char *path, const struct stat *st, int type, struct FTW *f) {
    (void)st; (void)type; (void)f; return remove(path);
}
static void clean(void) { (void)nftw(CDP_PROC_ROOT, remove_entry, 32, FTW_DEPTH | FTW_PHYS); }
static void bytes(const char *path, const void *data, size_t length) {
    int fd = open(path, O_CREAT | O_TRUNC | O_WRONLY | O_CLOEXEC, 0600);
    CHECK(fd >= 0, "CDP fixture file opens");
    if (fd >= 0) {
        CHECK(write(fd, data, length) == (ssize_t)length, "CDP fixture file writes complete bytes");
        CHECK(close(fd) == 0, "CDP fixture file closes");
    }
}
static void text(const char *path, const char *value) { bytes(path, value, strlen(value)); }
static void directory(const char *path) { CHECK(mkdir(path, 0700) == 0, "CDP fixture directory opens"); }
static const char header[] = "  sl local_address rem_address st tx_queue rx_queue tr tm->when retrnsmt uid timeout inode\n";
static const char row4[] = "0: 0100007F:2406 00000000:0000 0A 00000000:00000000 00:00000000 00000000 0 0 1\n";
static const char row6[] = "0: 00000000000000000000000001000000:2406 00000000000000000000000000000000:0000 0A 00000000:00000000 00:00000000 00000000 0 0 1\n";
static void table(unsigned index, const char *body) {
    char raw[2048]; snprintf(raw, sizeof raw, "%s%s", header, body);
    text(index ? CDP_PROC_ROOT "/net/tcp6" : CDP_PROC_ROOT "/net/tcp", raw);
}
static void setup(void) {
    observing = observations = root_error = table_open_error[0] = table_open_error[1] = pid_open_missing = 0;
    vanish_comm = confirm_error = scan_error = scan_seen = synthetic_index = ordered_pids = 0; synthetic_entries = -1;
    read_suffix = NULL; read_after_data = read_seen = read_interrupt = read_interrupted = 0; read_chunk = 0;
    read_error = EIO;
    clean(); directory(CDP_PROC_ROOT); directory(CDP_PROC_ROOT "/net"); table(0, ""); table(1, "");
}
static void process(const char *pid, const void *value, size_t length) {
    char path[1024]; snprintf(path, sizeof path, "%s/%s", CDP_PROC_ROOT, pid); directory(path);
    snprintf(path, sizeof path, "%s/%s/comm", CDP_PROC_ROOT, pid); bytes(path, value, length);
}
static void process_text(const char *pid, const char *value) { process(pid, value, strlen(value)); }
static void command(const char *request, char out[128]) {
    int pair[2]; CHECK(socketpair(AF_UNIX, SOCK_STREAM, 0, pair) == 0, "CDP dispatch fixture socket opens");
    conn_ctx ctx = { .fd = pair[0] }; char input[256]; snprintf(input, sizeof input, "%s", request);
    observing = 1; dispatch(&ctx, input); observing = 0; shutdown(pair[0], SHUT_WR);
    size_t used = 0;
    while (used < 127) { ssize_t n = read(pair[1], out + used, 127 - used); if (n <= 0) break; used += (size_t)n; }
    out[used] = 0; close(pair[0]); close(pair[1]);
}
static void expect(const char *response, const char *assertion) {
    char out[128]; command("CDPSTATE", out); CHECK(!strcmp(out, response), assertion);
    CHECK(strlen(out) <= 17, "CDP state reply stays within its exact wire bound");
}

static void controls_and_processes(void) {
    setup(); char out[128]; command("PING", out);
    CHECK(!strcmp(out, "OK\n"), "CDP pre-feature PING control stays green");
    const char *bad[] = { "CDPSTATE extra", "CDPSTATE /proc", "CDPSTATE ;reboot", "CDPSTATE\nPING" };
    for (size_t i = 0; i < sizeof bad / sizeof bad[0]; i++) {
        int before = observations; command(bad[i], out);
        CHECK(!strcmp(out, "ERR\n") && observations == before, "CDP rejects every argument before observations");
    }
    expect("CDPSTATE ABSENT\n", "CDP complete empty process and TCP inventories prove absence");
    process_text("71", "cdprelay\n");
    expect("CDPSTATE PRESENT\n", "CDP exact live comm establishes presence without exe or stat dependencies");
    const char *negative[] = { "other\n", "cdprelay-helper\n", "xcdprelay\n", "cdprelay \n", "CDPRELAY\n", "\001cdprelay\n", "cdprelay\nextra\n" };
    for (size_t i = 0; i < sizeof negative / sizeof negative[0]; i++) {
        text(CDP_PROC_ROOT "/71/comm", negative[i]);
        expect("CDPSTATE ABSENT\n", "CDP comm matching rejects prefixes suffixes case and whitespace normalization");
    }
    text(CDP_PROC_ROOT "/71/comm", "cdprelay");
    expect("CDPSTATE UNKNOWN\n", "CDP unterminated comm cannot prove absence or presence");
    text(CDP_PROC_ROOT "/71/comm", "");
    expect("CDPSTATE UNKNOWN\n", "CDP empty live comm remains unknown");
    const char nul[] = "cdprelay\0hidden\n"; bytes(CDP_PROC_ROOT "/71/comm", nul, sizeof nul - 1);
    expect("CDPSTATE UNKNOWN\n", "CDP embedded NUL invalidates an exact-name prefix");
    char raw[65]; memset(raw, 'a', sizeof raw); raw[63] = '\n'; bytes(CDP_PROC_ROOT "/71/comm", raw, 64);
    expect("CDPSTATE ABSENT\n", "CDP complete sixty-four-byte comm remains authoritative");
    raw[63] = 'a'; raw[64] = '\n'; bytes(CDP_PROC_ROOT "/71/comm", raw, 65);
    expect("CDPSTATE UNKNOWN\n", "CDP comm overflow probe cannot accept a complete-looking prefix");
    text(CDP_PROC_ROOT "/71/comm", "cdprelay\n"); read_suffix = "/71/comm"; read_after_data = 1; read_chunk = 9;
    expect("CDPSTATE UNKNOWN\n", "CDP later comm I/O failure discards an exact positive prefix");
    read_after_data = read_seen = 0; read_chunk = 1; read_interrupt = 1;
    expect("CDPSTATE PRESENT\n", "CDP comm read retries interruption and assembles short reads");
    read_suffix = NULL; read_interrupt = 0; unlink(CDP_PROC_ROOT "/71/comm");
    expect("CDPSTATE UNKNOWN\n", "CDP missing comm inside a live numeric directory remains unknown");
    confirm_error = EIO;
    expect("CDPSTATE UNKNOWN\n", "CDP failed PID disappearance confirmation cannot prove absence");
    confirm_error = 0; text(CDP_PROC_ROOT "/71/comm", "other\n"); vanish_comm = 1;
    expect("CDPSTATE ABSENT\n", "CDP confirmed vanished PID may be skipped after missing comm");
    process_text("71", "other\n"); pid_open_missing = 1;
    expect("CDPSTATE ABSENT\n", "CDP PID directory open disappearance is an expected race");
    pid_open_missing = 0; unlink(CDP_PROC_ROOT "/71/comm");
    CHECK(mkfifo(CDP_PROC_ROOT "/71/comm", 0600) == 0, "CDP nonregular comm fixture opens");
    expect("CDPSTATE UNKNOWN\n", "CDP nonregular live comm cannot prove absence");
    process_text("72", "cdprelay\n");
    ordered_pids = 1; synthetic_index = 0;
    expect("CDPSTATE PRESENT\n", "CDP another exact process wins an earlier unreadable comm");
    ordered_pids = 0;
}

static void process_scan(void) {
    setup(); const char *ignored[] = { "+71", "-1", "abc" };
    for (size_t i = 0; i < sizeof ignored / sizeof ignored[0]; i++) process_text(ignored[i], "cdprelay\n");
    expect("CDPSTATE ABSENT\n", "CDP process scan ignores nonnumeric names rather than reading arbitrary comm paths");
    const char *invalid[] = { "0", "071", "2147483648" };
    for (size_t i = 0; i < sizeof invalid / sizeof invalid[0]; i++) process_text(invalid[i], "cdprelay\n");
    expect("CDPSTATE UNKNOWN\n", "CDP malformed numeric PID names cannot prove a complete process inventory");
    process_text("2147483647", "cdprelay\n");
    expect("CDPSTATE PRESENT\n", "CDP maximum canonical PID positive wins malformed numeric entries");
    setup(); process_text("71", "other\n"); scan_error = 1;
    expect("CDPSTATE UNKNOWN\n", "CDP incomplete process scan never proves absence");
    scan_error = scan_seen = 0; root_error = EACCES;
    expect("CDPSTATE UNKNOWN\n", "CDP inaccessible proc root cannot prove absence");
    root_error = 0; synthetic_entries = 8192; synthetic_index = 0;
    expect("CDPSTATE ABSENT\n", "CDP exact total process entry bound includes dots and nonnumeric entries");
    synthetic_entries = 8193; synthetic_index = 0;
    expect("CDPSTATE UNKNOWN\n", "CDP total process entry overflow cannot prove absence");
}

static void tcp_tables(void) {
    setup(); table(0, row4);
    expect("CDPSTATE PRESENT\n", "CDP IPv4 LISTEN at port9222 includes loopback local address");
    setup(); table(1, row6);
    expect("CDPSTATE PRESENT\n", "CDP complete IPv6 listener independently establishes presence");
    setup(); unlink(CDP_PROC_ROOT "/net/tcp6");
    expect("CDPSTATE ABSENT\n", "CDP IPv6 table open ENOENT alone is a known negative");
    unlink(CDP_PROC_ROOT "/net/tcp");
    expect("CDPSTATE UNKNOWN\n", "CDP missing IPv4 table cannot prove no listener");
    setup(); table_open_error[1] = EACCES;
    expect("CDPSTATE UNKNOWN\n", "CDP IPv6 table access denial is unknown rather than missing support");
    setup(); text(CDP_PROC_ROOT "/net/tcp6", "");
    expect("CDPSTATE UNKNOWN\n", "CDP empty readable IPv6 table is not missing protocol support");
    table(1, ""); read_suffix = "/net/tcp6"; read_after_data = 1; read_error = ENOENT;
    expect("CDPSTATE UNKNOWN\n", "CDP IPv6 read ENOENT is unknown rather than an open-time known negative");
    setup(); table_open_error[0] = EACCES; table(1, row6);
    expect("CDPSTATE PRESENT\n", "CDP independent IPv6 positive wins unreadable IPv4 inventory");
    setup(); root_error = EACCES; table(0, row4);
    expect("CDPSTATE PRESENT\n", "CDP listener positive wins inaccessible process inventory");
    setup(); process_text("71", "cdprelay\n"); table_open_error[0] = table_open_error[1] = EACCES;
    expect("CDPSTATE PRESENT\n", "CDP exact process positive wins both unreadable TCP tables");
    setup(); table(0, "0: 00000000:2406 00000000:0000 01 00000000:00000000 00:00000000 00000000 0 0 1\n");
    expect("CDPSTATE ABSENT\n", "CDP non-LISTEN target socket does not establish presence");
    table(0, "0: 00000000:2407 00000000:0000 0A 00000000:00000000 00:00000000 00000000 0 0 1\n");
    expect("CDPSTATE ABSENT\n", "CDP other listening port does not establish presence");
    const char *bad[] = { "broken\n", "\n", "0: Z0000000:2406 00000000:0000 0A 00000000:00000000 00:00000000 00000000 0 0 1\n", "0: 00000000:2406 00000000:0000 0A\n" };
    for (size_t i = 0; i < sizeof bad / sizeof bad[0]; i++) {
        table(0, bad[i]); expect("CDPSTATE UNKNOWN\n", "CDP malformed TCP row cannot prove listener absence");
    }
    text(CDP_PROC_ROOT "/net/tcp", "bad header\n");
    expect("CDPSTATE UNKNOWN\n", "CDP malformed TCP header cannot prove absence");
    char raw[2048]; snprintf(raw, sizeof raw, "%s%sbroken\n", header, row4); text(CDP_PROC_ROOT "/net/tcp", raw);
    expect("CDPSTATE UNKNOWN\n", "CDP later malformed row invalidates a same-table positive prefix");
    table(1, row6);
    expect("CDPSTATE PRESENT\n", "CDP independent complete table wins malformed other-table positive prefix");
    table(1, ""); snprintf(raw, sizeof raw, "%s%s", header, row4); bytes(CDP_PROC_ROOT "/net/tcp", raw, strlen(raw) - 1);
    expect("CDPSTATE UNKNOWN\n", "CDP unterminated TCP record cannot prove complete inventory");
    size_t size = strlen(raw); raw[size] = 0; raw[size + 1] = '\n'; bytes(CDP_PROC_ROOT "/net/tcp", raw, size + 2);
    expect("CDPSTATE UNKNOWN\n", "CDP embedded NUL invalidates the TCP inventory");
    table(0, row4); read_suffix = "/net/tcp"; read_after_data = 1;
    expect("CDPSTATE UNKNOWN\n", "CDP later TCP I/O failure discards a positive table prefix");
    read_seen = 0; table(1, row6);
    expect("CDPSTATE PRESENT\n", "CDP failed IPv4 descriptor cannot contaminate fresh IPv6 evidence");
    read_suffix = NULL; table(1, ""); unlink(CDP_PROC_ROOT "/net/tcp");
    CHECK(mkfifo(CDP_PROC_ROOT "/net/tcp", 0600) == 0, "CDP nonregular table fixture opens");
    expect("CDPSTATE UNKNOWN\n", "CDP nonregular TCP table cannot prove absence");
}

static void tcp_bound(void) {
    setup();
    char *raw = malloc(TABLE_BYTES + 1); CHECK(raw != NULL, "CDP bounded TCP fixture allocates");
    if (!raw) return;
    size_t h = strlen(header), r = strlen(row4), rows = (TABLE_BYTES - h) / r, pad = TABLE_BYTES - h - rows * r;
    memcpy(raw, header, h - 1); memset(raw + h - 1, ' ', pad); raw[h - 1 + pad] = '\n';
    size_t used = h + pad;
    for (size_t i = 0; i < rows; i++) { memcpy(raw + used, row4, r); used += r; }
    CHECK(used == TABLE_BYTES, "CDP TCP byte-bound fixture is exact");
    bytes(CDP_PROC_ROOT "/net/tcp", raw, used);
    expect("CDPSTATE PRESENT\n", "CDP complete exact128KiB TCP table remains authoritative");
    raw[used] = ' '; bytes(CDP_PROC_ROOT "/net/tcp", raw, used + 1);
    expect("CDPSTATE UNKNOWN\n", "CDP TCP overflow probe invalidates an otherwise complete positive table");
    free(raw);
}
int main(void) {
    controls_and_processes(); process_scan(); tcp_tables(); tcp_bound(); clean();
    if (failures) return 1;
    puts("CDP STATE BOUNDARY OK"); return 0;
}
