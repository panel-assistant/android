// Read-only observations for the one supported Sonoff layout. Caller bytes never select a path.
#define _GNU_SOURCE
#include "zigbee.h"
#include "sysexec.h"
#include "util.h"
#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <strings.h>
#include <sys/stat.h>
#include <unistd.h>

#ifndef ZIGBEE_DIR
#define ZIGBEE_DIR "/vendor/bin/siliconlabs_host"
#endif
#ifndef ZIGBEE_DATA
#define ZIGBEE_DATA "/data/vendor/siliconlabs_host"
#endif
#ifndef ZIGBEE_PROC
#define ZIGBEE_PROC "/proc"
#endif
#ifndef ZIGBEE_SH
#define ZIGBEE_SH "/system/bin/sh"
#endif
#define ZIGBEE_SCAN_LIMIT 8192u
#define ZIGBEE_RAW_LIMIT 4096u
#define ZIGBEE_GUARD_LIMIT 65536u
#define ZIGBEE_LOG_TAIL 65536u
#define KTHREAD_FLAG 0x00200000u

typedef struct { unsigned pid; unsigned long long start, jiffies; } process;
typedef struct {
    int files[6], recursive;
    char package[241];
    process gateway, guard;
    unsigned long long total;
} observation;
static const char *const names[] = {
    "run_guard_process.sh", "guard_process.sh", "run.sh", "zgateway", "mosquitto", "package_version"
};
static const char *const labels[] = { "managed", "guard", "run", "binary", "broker", "package_marker" };

static int vanished(void) { return errno == ENOENT || errno == ESRCH; }
// capacity includes a one-byte overflow probe: success always proves EOF before capacity.
static int read_file(const char *path, unsigned char *bytes, size_t capacity, size_t *used) {
    int fd = open(path, O_RDONLY | O_CLOEXEC | O_NOFOLLOW | O_NONBLOCK);
    if (fd < 0) return -1;
    struct stat st;
    if (fstat(fd, &st) || !S_ISREG(st.st_mode)) { close(fd); errno = EINVAL; return -1; }
    *used = 0;
    int ok = 1;
    while (*used < capacity) {
        ssize_t n = read(fd, bytes + *used, capacity - *used);
        if (n < 0 && errno == EINTR) continue;
        if (n < 0) { ok = 0; break; }
        if (!n) break;
        *used += (size_t)n;
    }
    int saved = errno;
    if (close(fd) != 0) { if (ok) saved = errno; ok = 0; }
    errno = saved;
    if (*used == capacity) { errno = EOVERFLOW; return -1; }
    return ok ? 0 : -1;
}
static void hex(const unsigned char *raw, size_t length, char *out) {
    static const char digits[] = "0123456789abcdef";
    for (size_t i = 0; i < length; i++) {
        out[2*i] = digits[raw[i] >> 4]; out[2*i+1] = digits[raw[i] & 15];
    }
    out[2*length] = 0;
}
// The wire hex is ASCII; package text itself must be complete, printable UTF-8, not lossy decoding.
static int printable_utf8(const unsigned char *s, size_t length) {
    for (size_t i = 0; i < length;) {
        unsigned c = s[i++], value, count;
        if (c < 0x80) { if (c < 0x20 || c == 0x7f) return 0; continue; }
        if (c >= 0xc2 && c <= 0xdf) { value = c & 31; count = 1; }
        else if (c >= 0xe0 && c <= 0xef) { value = c & 15; count = 2; }
        else if (c >= 0xf0 && c <= 0xf4) { value = c & 7; count = 3; }
        else return 0;
        if (length - i < count) return 0;
        unsigned n = count;
        while (n--) { c = s[i++]; if ((c & 0xc0) != 0x80) return 0; value = (value << 6) | (c & 63); }
        if ((count == 1 && value < 0x80) || (count == 2 && value < 0x800) ||
            (count == 3 && value < 0x10000) || value > 0x10ffff ||
            (value >= 0xd800 && value <= 0xdfff) || (value >= 0x80 && value <= 0x9f)) return 0;
    }
    return 1;
}
static int number(const char *s, size_t length, unsigned long long *value) {
    if (!length) return -1;
    unsigned long long n = 0;
    for (size_t i = 0; i < length; i++) {
        if (s[i] < '0' || s[i] > '9' || n > ((unsigned long long)LLONG_MAX - (s[i]-'0')) / 10) return -1;
        n = n * 10 + (s[i]-'0');
    }
    *value = n;
    return 0;
}
static int footprint(observation *out) {
    struct stat directory;
    if (lstat(ZIGBEE_DIR, &directory) != 0) {
        if (errno == ENOENT) return 0;
        return -1;
    }
    if (!S_ISDIR(directory.st_mode)) return -1;
    for (size_t i = 0; i < 6; i++) {
        char path[PATH_MAX]; struct stat st;
        snprintf(path, sizeof path, "%s/%s", ZIGBEE_DIR, names[i]);
        if (lstat(path, &st) != 0) { if (errno == ENOENT) continue; return -1; }
        if (!S_ISREG(st.st_mode)) return -1;
        out->files[i] = (i == 3 || i == 4) ? (st.st_mode & 0111) != 0 : 1;
    }
    if (out->files[5]) {
        unsigned char raw[ZIGBEE_RAW_LIMIT + 1]; size_t used;
        if (read_file(ZIGBEE_DIR "/package_version", raw, sizeof raw, &used) != 0) return -1;
        size_t first = 0;
        while (first < used && (raw[first] == ' ' || raw[first] == '\t' || raw[first] == '\r' || raw[first] == '\n')) first++;
        while (used > first && (raw[used-1] == ' ' || raw[used-1] == '\t' || raw[used-1] == '\r' || raw[used-1] == '\n')) used--;
        if (used - first > 120 || !printable_utf8(raw + first, used-first)) return -1;
        hex(raw + first, used-first, out->package);
    }
    return 0;
}
// /proc/<pid>/stat: comm may contain spaces and ')' characters. Fields after its LAST ')' are fixed.
static int process_stat(const char *path, unsigned pid, process *out, char *state, unsigned long long *flags) {
    unsigned char raw[2048]; size_t used;
    if (read_file(path, raw, sizeof raw, &used) != 0) return -1;
    errno = EINVAL;
    if (!used || memchr(raw, 0, used)) return -1;
    raw[used] = 0;
    char *open = strchr((char *)raw, '('), *end = strrchr((char *)raw, ')');
    unsigned long long found;
    if (!open || open == (char *)raw || open[-1] != ' ' || !end || end <= open ||
        number((char *)raw, (size_t)(open - (char *)raw - 1), &found) || found != pid ||
        end[1] != ' ' || !end[2] || end[3] != ' ') return -1;
    *state = end[2];
    unsigned long long user = 0, system = 0, start = 0;
    char *p = end + 4;
    for (unsigned field = 4; field <= 22; field++) {
        char *q = p;
        while (*q && *q != ' ' && *q != '\n') q++;
        if (q == p) return -1;
        if (field == 9 || field == 14 || field == 15 || field == 22) {
            unsigned long long value;
            if (number(p, (size_t)(q-p), &value)) return -1;
            if (field == 9) *flags = value;
            if (field == 14) user = value;
            if (field == 15) system = value;
            if (field == 22) start = value;
        }
        p = q; while (*p == ' ') p++;
    }
    if (user > (unsigned long long)LLONG_MAX - system) return -1;
    *out = (process){ pid, start, user + system };
    return 0;
}
static int same_executable(const char *exe, const char *target) {
    // Follow only the kernel's exe link and the fixed platform sh symlink; never compare literal mksh names.
    int a = open(exe, O_RDONLY | O_CLOEXEC), b = open(target, O_RDONLY | O_CLOEXEC);
    struct stat x, y;
    int result = a >= 0 && b >= 0 && fstat(a, &x) == 0 && fstat(b, &y) == 0 &&
        S_ISREG(x.st_mode) && S_ISREG(y.st_mode) && x.st_dev == y.st_dev && x.st_ino == y.st_ino;
    if (a >= 0) close(a);
    if (b >= 0) close(b);
    return result;
}
static int scan_processes(observation *out) {
    DIR *dir = opendir(ZIGBEE_PROC);
    if (!dir) return -1;
    int ok = 1;
    unsigned count = 0;
    for (;;) {
        errno = 0;
        struct dirent *entry = readdir(dir);
        if (!entry) { if (errno) ok = 0; break; }
        unsigned long long pid;
        if (number(entry->d_name, strlen(entry->d_name), &pid)) continue;
        if (!pid || pid > INT_MAX || ++count > ZIGBEE_SCAN_LIMIT) { ok = 0; break; }
        char path[PATH_MAX]; process sample; char state; unsigned long long flags = 0;
        snprintf(path, sizeof path, "%s/%s/stat", ZIGBEE_PROC, entry->d_name);
        if (process_stat(path, (unsigned)pid, &sample, &state, &flags)) {
            if (vanished()) continue;
            ok = 0; break;
        }
        if (state == 'Z' || state == 'X' || (flags & KTHREAD_FLAG)) continue;
        unsigned char raw[ZIGBEE_RAW_LIMIT + 1]; size_t used;
        snprintf(path, sizeof path, "%s/%s/cmdline", ZIGBEE_PROC, entry->d_name);
        if (read_file(path, raw, sizeof raw, &used)) { if (vanished()) continue; ok = 0; break; }
        if (!used || raw[used-1] != 0) { ok = 0; break; }
        const char *argv[64]; size_t argc = 0, offset = 0;
        while (offset < used) {
            if (argc == 64) { ok = 0; break; }
            argv[argc++] = (char *)raw + offset;
            offset += strlen((char *)raw + offset) + 1;
        }
        if (!ok) break;
        int gateway = !strcmp(argv[0], ZIGBEE_DIR "/zgateway") || !strcmp(argv[0], "zgateway");
        int guard = argc == 2 && (!strcmp(argv[0], "sh") || !strcmp(argv[0], ZIGBEE_SH)) &&
            !strcmp(argv[1], ZIGBEE_DIR "/guard_process.sh");
        if (!gateway && !guard) continue;
        snprintf(path, sizeof path, "%s/%s/exe", ZIGBEE_PROC, entry->d_name);
        if (!same_executable(path, gateway ? ZIGBEE_DIR "/zgateway" : ZIGBEE_SH)) { ok = 0; break; }
        process again; unsigned long long later_flags = 0; char later_state;
        snprintf(path, sizeof path, "%s/%s/stat", ZIGBEE_PROC, entry->d_name);
        if (process_stat(path, (unsigned)pid, &again, &later_state, &later_flags)) {
            if (vanished()) continue;
            ok = 0; break;
        }
        if (again.start != sample.start) { ok = 0; break; }
        if (later_state == 'Z' || later_state == 'X') continue;
        process *slot = gateway ? &out->gateway : &out->guard;
        if (slot->pid) { ok = 0; break; }
        *slot = again;
    }
    if (closedir(dir) != 0) ok = 0;
    return ok ? 0 : -1;
}
static int cpu_total(unsigned long long *total) {
    int fd = open(ZIGBEE_PROC "/stat", O_RDONLY | O_CLOEXEC | O_NOFOLLOW);
    if (fd < 0) return -1;
    char line[2048]; size_t used = 0;
    while (used < sizeof line - 1) {
        ssize_t n = read(fd, line + used, 1);
        if (n < 0 && errno == EINTR) continue;
        if (n != 1) { close(fd); return -1; }
        if (line[used++] == '\n') break;
    }
    close(fd);
    if (!used || line[used-1] != '\n') return -1;
    line[used-1] = 0;
    if (strncmp(line, "cpu ", 4)) return -1;
    char *p = line + 4;
    unsigned fields = 0; *total = 0;
    while (*p) {
        while (*p == ' ' || *p == '\t') p++;
        if (!*p) break;
        char *end = p; while (*end && *end != ' ' && *end != '\t') end++;
        unsigned long long value;
        if (number(p, (size_t)(end-p), &value) || *total > (unsigned long long)LLONG_MAX-value) return -1;
        // Linux's guest counters are already included in user/nice.
        if (fields < 8) *total += value;
        fields++; p = end;
    }
    return fields >= 4 && fields <= 10 ? 0 : -1;
}
static int recursive_guard(observation *out) {
    if (!out->files[1]) return 0;
    unsigned char raw[ZIGBEE_GUARD_LIMIT + 1]; size_t used;
    if (read_file(ZIGBEE_DIR "/guard_process.sh", raw, sizeof raw, &used) || memchr(raw, 0, used)) return -1;
    static const char wanted[] = "export LD_LIBRARY_PATH=/vendor/bin/siliconlabs_host/:${LD_LIBRARY_PATH}";
    size_t first = 0;
    for (size_t i = 0; i <= used; i++) if (i == used || raw[i] == '\n') {
        if (i-first == sizeof wanted-1 && !memcmp(raw+first, wanted, sizeof wanted-1)) out->recursive = 1;
        first = i+1;
    }
    return 0;
}
// Optional network evidence: never turn an unreadable/oversized source into a joined/unjoined claim.
// Return only a bounded opaque JSON candidate; the existing app parser owns aliases and redaction.
static size_t netinfo(unsigned char *out) {
    struct stat directory;
    int have_data = lstat(ZIGBEE_DATA, &directory) == 0;
    if (have_data && !S_ISDIR(directory.st_mode)) return 0;
    if (!have_data && errno != ENOENT) return 0;
    size_t used;
    if (read_file(ZIGBEE_DATA "/netinfo", out, ZIGBEE_RAW_LIMIT+1, &used) == 0) {
        if (used) return used;
    } else if (errno != ENOENT) return 0;
    static const char *const logs[] = { ZIGBEE_DATA "/zgateway.log", ZIGBEE_DIR "/zgateway.log" };
    size_t selected = 0;
    for (size_t file = 0; file < 2; file++) {
        int fd = open(logs[file], O_RDONLY | O_CLOEXEC | O_NOFOLLOW | O_NONBLOCK);
        if (fd < 0) { if (errno == ENOENT) continue; return 0; }
        struct stat st;
        unsigned char raw[ZIGBEE_LOG_TAIL + 1]; size_t have = 0;
        if (fstat(fd, &st) || !S_ISREG(st.st_mode) || st.st_size < 0) { close(fd); return 0; }
        off_t offset = st.st_size > ZIGBEE_LOG_TAIL ? st.st_size - ZIGBEE_LOG_TAIL : 0;
        if (lseek(fd, offset, SEEK_SET) < 0) { close(fd); return 0; }
        while (have < ZIGBEE_LOG_TAIL + 1) {
            ssize_t n = read(fd, raw+have, ZIGBEE_LOG_TAIL+1-have);
            if (n < 0 && errno == EINTR) continue;
            if (n < 0) { close(fd); return 0; }
            if (!n) break;
            have += (size_t)n;
        }
        close(fd);
        if (have > ZIGBEE_LOG_TAIL || memchr(raw, 0, have)) return 0;
        size_t start = have; unsigned lines = 0;
        while (start && lines < 200) { start--; if (raw[start] == '\n' && start+1 < have) lines++; }
        if (offset && lines < 200) return 0;
        if (start && raw[start] == '\n') start++;
        size_t begin = start;
        for (size_t i = start; i <= have; i++) if (i == have || raw[i] == '\n') {
            size_t length = i-begin;
            int matches = 0;
            for (size_t j = begin; j+7 <= i; j++) if (!strncasecmp((char *)raw+j, "netinfo", 7)) { matches = 1; break; }
            if (matches) {
                if (!length || length > ZIGBEE_RAW_LIMIT) return 0;
                memcpy(out, raw+begin, length); selected = length;
            }
            begin = i+1;
        }
    }
    return selected;
}
static int state_frame(char *frame, size_t capacity, const char *header, const observation *o) {
    int n = snprintf(frame, capacity, "%s 1\n", header);
    if (n < 0 || (size_t)n >= capacity) return -1;
    size_t used = (size_t)n;
    for (size_t i = 0; i < 6; i++) {
        n = snprintf(frame+used, capacity-used, "%s=%d\n", labels[i], o->files[i]);
        if (n < 0 || (size_t)n >= capacity-used) return -1;
        used += (size_t)n;
    }
    n = snprintf(frame+used, capacity-used, "running=%d\nguard_running=%d\npackage=%s\n",
        o->gateway.pid != 0, o->guard.pid != 0, o->package);
    return n >= 0 && (size_t)n < capacity-used ? (int)(used+(size_t)n) : -1;
}
static int process_frame(char *frame, size_t capacity, const char *name, const process *p) {
    if (p->pid) return snprintf(frame, capacity, "%s_pid=%u\n%s_start=%llu\n%s_jiffies=%llu\n",
        name, p->pid, name, p->start, name, p->jiffies);
    return snprintf(frame, capacity, "%s_pid=-\n%s_start=-\n%s_jiffies=-\n", name, name, name);
}
static void observe(conn_ctx *ctx, const char *args, int health) {
    if (*args) { reply(ctx->fd, "ERR\n"); return; }
    observation o = {0};
    if (footprint(&o) || scan_processes(&o) || (health && (cpu_total(&o.total) || recursive_guard(&o)))) {
        reply(ctx->fd, "ERR\n"); return;
    }
    char frame[9217];
    int used = state_frame(frame, sizeof frame, health ? "ZIGBEEHEALTH" : "ZIGBEESTATE", &o);
    if (used < 0) { reply(ctx->fd, "ERR\n"); return; }
    if (health) {
        used += process_frame(frame+used, sizeof frame-(size_t)used, "gateway", &o.gateway);
        used += process_frame(frame+used, sizeof frame-(size_t)used, "guard", &o.guard);
        used += snprintf(frame+used, sizeof frame-(size_t)used, "total_jiffies=%llu\nrecursive=%d\nnetinfo=", o.total, o.recursive);
        unsigned char raw[ZIGBEE_RAW_LIMIT+1]; size_t bytes = netinfo(raw);
        if (bytes) { hex(raw, bytes, frame+used); used += (int)(2*bytes); }
        else frame[used++] = '-';
        frame[used++] = '\n';
    }
    memcpy(frame+used, "END\n", 5);
    reply(ctx->fd, frame);
}
void cmd_zigbeestate(conn_ctx *ctx, const char *args) { observe(ctx, args, 0); }
void cmd_zigbeehealth(conn_ctx *ctx, const char *args) { observe(ctx, args, 1); }
void cmd_zigbeerole(conn_ctx *ctx, const char *args) {
    if (*args) { reply(ctx->fd, "ERR\n"); return; }
    struct stat dir, client;
    if (lstat(ZIGBEE_DIR, &dir) || !S_ISDIR(dir.st_mode) ||
        lstat(ZIGBEE_DIR "/mosquitto_sub", &client) || !S_ISREG(client.st_mode) || !(client.st_mode & 0111)) {
        reply(ctx->fd, "ERR\n"); return;
    }
    char raw[ZIGBEE_RAW_LIMIT+1]; size_t used = 0;
    if (sysexec_capture_zigbee_role(raw, sizeof raw, &used) != 0 || !used || used > ZIGBEE_RAW_LIMIT || memchr(raw, 0, used)) {
        reply(ctx->fd, "ERR\n"); return;
    }
    char frame[8205] = "ZIGBEEROLE ";
    hex((unsigned char *)raw, used, frame+11);
    frame[11+2*used] = '\n'; frame[12+2*used] = 0;
    reply(ctx->fd, frame);
}
