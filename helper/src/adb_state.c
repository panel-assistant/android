#include "adb_state.h"
#include "sysexec.h"
#include "util.h"
#include <dirent.h>
#include <errno.h>
#include <limits.h>
#include <fcntl.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <unistd.h>

#define TCP_CAPTURE_BYTES (128u * 1024u)
static int space(unsigned char c) { return c == ' ' || (c >= '\t' && c <= '\r'); }
static int property_value(struct adb_property_capture *capture, unsigned i) {
    size_t length = capture->used[i];
    char *value = capture->output[i];
    if (capture->status[i] != 0 || length >= ADB_PROPERTY_CAPACITY || memchr(value, 0, length)) return -1;
    while (length && space((unsigned char)*value)) { value++; length--; }
    while (length && space((unsigned char)value[length-1])) length--;
    if (!length) return 0;
    if (i == 0) return 1; // Any configured socket specification fails closed, including unfamiliar forms.
    if (i == 4) return length == 1 && (*value == '0' || *value == '1') ? *value - '0' : -1;
    int negative = 0;
    if (*value == '+' || *value == '-') { negative = *value == '-'; value++; length--; }
    if (!length) return -1;
    uint32_t n = 0, limit = negative ? UINT32_C(2147483648) : UINT32_C(2147483647);
    for (size_t j = 0; j < length; j++) {
        if (value[j] < '0' || value[j] > '9') return -1;
        unsigned digit = (unsigned)(value[j] - '0');
        if (n > (limit - digit) / 10u) return -1;
        n = n * 10u + digit;
    }
    if (negative || n == 0) return 0;
    return n <= 65535 ? 1 : -1;
}
static int hex_digit(unsigned char c) {
    if (c >= '0' && c <= '9') return c - '0';
    if (c >= 'A' && c <= 'F') return c - 'A' + 10;
    if (c >= 'a' && c <= 'f') return c - 'a' + 10;
    return -1;
}
static int hex(const char *s, size_t length) {
    if (strlen(s) != length) return 0;
    for (size_t i = 0; i < length; i++) if (hex_digit((unsigned char)s[i]) < 0) return 0;
    return 1;
}
static int decimal(const char *s) {
    if (!*s) return 0;
    for (; *s; s++) if (*s < '0' || *s > '9') return 0;
    return 1;
}
static int pair_hex(char *s, size_t first, size_t second) {
    if (strlen(s) != first + second + 1 || s[first] != ':') return 0;
    s[first] = 0;
    int valid = hex(s, first) && hex(s + first + 1, second);
    s[first] = ':';
    return valid;
}
static size_t columns(char *line, char **out, size_t capacity) {
    size_t count = 0;
    while (*line) {
        while (*line == ' ' || *line == '\t') line++;
        if (!*line) break;
        if (count == capacity) return capacity + 1;
        out[count++] = line;
        while (*line && *line != ' ' && *line != '\t') line++;
        if (*line) *line++ = 0;
    }
    return count;
}
static int tcp_table(char *raw, size_t length, size_t address_digits, unsigned target_port) {
    if (!length || raw[length-1] != '\n' || memchr(raw, 0, length)) return -1;
    raw[length] = 0;
    char *next = strchr(raw, '\n');
    *next++ = 0;
    char *col[24];
    size_t count = columns(raw, col, 24);
    static const char *const header[] = { "sl", "local_address", NULL, "st", "tx_queue", "rx_queue", "tr", "tm->when", "retrnsmt", "uid", "timeout", "inode" };
    if (count != 12) return -1;
    for (size_t i = 0; i < count; i++)
        if (i == 2 ? (strcmp(col[i], "rem_address") && strcmp(col[i], "remote_address")) : strcmp(col[i], header[i])) return -1;
    int listening = 0;
    while (*next) {
        char *line = next;
        next = strchr(line, '\n');
        if (!next) return -1;
        *next++ = 0;
        count = columns(line, col, 24);
        if (count < 10 || count > 24) return -1;
        size_t slot_length = strlen(col[0]);
        if (slot_length < 2 || col[0][slot_length-1] != ':') return -1;
        col[0][slot_length-1] = 0;
        if (!decimal(col[0]) || !pair_hex(col[1], address_digits, 4) || !pair_hex(col[2], address_digits, 4) ||
            !hex(col[3], 2) || !pair_hex(col[4], 8, 8) || !pair_hex(col[5], 2, 8) || !hex(col[6], 8) ||
            !decimal(col[7]) || !decimal(col[8]) || !decimal(col[9])) return -1;
        for (size_t i = 10; i < count; i++)
            if (strcmp(col[i], "-1") && !hex(col[i], strlen(col[i]))) return -1;
        const char *port = col[1] + address_digits + 1;
        unsigned n = 0;
        for (unsigned j = 0; j < 4; j++) n = n * 16u + (unsigned)hex_digit((unsigned char)port[j]);
        if (n == target_port && hex_digit((unsigned char)col[3][0]) == 0 && hex_digit((unsigned char)col[3][1]) == 10) listening = 1;
    }
    return listening;
}
static int listener(const char *path, size_t address_digits, unsigned target_port, int missing_ok) {
    int fd = open(path, O_RDONLY | O_CLOEXEC | O_NONBLOCK | O_NOFOLLOW);
    if (fd < 0) return missing_ok && errno == ENOENT ? 0 : -1;
    struct stat st;
    char *raw = NULL;
    int result = -1;
    if (fstat(fd, &st) || !S_ISREG(st.st_mode) || !(raw = malloc(TCP_CAPTURE_BYTES + 1))) goto done;
    size_t used = 0;
    for (;;) {
        unsigned char extra;
        ssize_t got = read(fd, used == TCP_CAPTURE_BYTES ? (void *)&extra : raw + used,
                           used == TCP_CAPTURE_BYTES ? 1 : TCP_CAPTURE_BYTES - used);
        if (got < 0) { if (errno == EINTR) continue; goto done; }
        if (!got) break;
        if (used == TCP_CAPTURE_BYTES) goto done;
        used += (size_t)got;
    }
    result = tcp_table(raw, used, address_digits, target_port);
done:
    free(raw); close(fd); return result;
}
void cmd_adbstate(conn_ctx *ctx, const char *args) {
    if (*args) { reply(ctx->fd, "ERR\n"); return; }
    struct adb_property_capture capture;
    sysexec_capture_adb_properties(&capture);
    int properties = 0;
    for (unsigned i = 0; i < ADB_PROPERTY_COUNT; i++) {
        int value = property_value(&capture, i);
        if (value == 1) { properties = 1; break; }
        if (value < 0) properties = -1;
    }
    int tcp = listener("/proc/net/tcp", 8, 5555, 0), tcp6 = listener("/proc/net/tcp6", 32, 5555, 0);
    int active = tcp == 1 || tcp6 == 1 ? 1 : tcp == 0 && tcp6 == 0 ? 0 : -1;
    char out[] = "PROPS=? LISTEN=?\n";
    out[6] = properties < 0 ? '?' : (char)('0' + properties);
    out[15] = active < 0 ? '?' : (char)('0' + active);
    reply(ctx->fd, out);
}

#ifndef CDP_PROC_ROOT
#define CDP_PROC_ROOT "/proc"
#endif
#define CDP_SCAN_ENTRIES 8192u
#define CDP_COMM_BYTES 64u

// Only kernel PID directory names can select a comm node; other proc entries are ignored.
static int cdp_pid(const char *name) {
    if (!*name) return 0;
    for (const char *p = name; *p; p++) if (*p < '0' || *p > '9') return 0;
    if (*name == '0') return -1;
    unsigned n = 0;
    for (; *name; name++) {
        unsigned digit = (unsigned)(*name - '0');
        if (n > ((unsigned)INT_MAX - digit) / 10u) return -1;
        n = n * 10u + digit;
    }
    return 1;
}
static int cdp_comm(int pidfd) {
    int fd = openat(pidfd, "comm", O_RDONLY | O_CLOEXEC | O_NOFOLLOW | O_NONBLOCK);
    if (fd < 0) return -1;
    unsigned char raw[CDP_COMM_BYTES + 1];
    size_t used = 0;
    int result = -1, error = EINVAL;
    struct stat st;
    if (fstat(fd, &st)) { error = errno; goto done; }
    if (!S_ISREG(st.st_mode)) goto done;
    for (;;) {
        ssize_t got = read(fd, raw + used, sizeof raw - used);
        if (got < 0) { if (errno == EINTR) continue; error = errno; goto done; }
        if (!got) break;
        used += (size_t)got;
        if (used > CDP_COMM_BYTES) { error = EOVERFLOW; goto done; }
    }
    if (!used || raw[used-1] != '\n' || memchr(raw, 0, used)) goto done;
    result = used == 9 && !memcmp(raw, "cdprelay\n", 9) ? 1 : 0;
done:
    if (close(fd)) { result = -1; error = errno; }
    if (result < 0) errno = error;
    return result;
}
static int cdp_process(void) {
    int rootfd = open(CDP_PROC_ROOT, O_RDONLY | O_DIRECTORY | O_CLOEXEC | O_NONBLOCK);
    if (rootfd < 0) return -1;
    DIR *dir = fdopendir(rootfd);
    if (!dir) { close(rootfd); return -1; }
    int result = 0;
    unsigned entries = 0;
    for (;;) {
        errno = 0;
        struct dirent *entry = readdir(dir);
        if (!entry) { if (errno) result = -1; break; }
        if (++entries > CDP_SCAN_ENTRIES) { result = -1; break; }
        int candidate = cdp_pid(entry->d_name);
        if (!candidate) continue;
        if (candidate < 0) { result = -1; continue; }
        int pidfd = openat(rootfd, entry->d_name, O_RDONLY | O_DIRECTORY | O_CLOEXEC | O_NOFOLLOW | O_NONBLOCK);
        if (pidfd < 0) { if (errno != ENOENT && errno != ESRCH) result = -1; continue; }
        int found = cdp_comm(pidfd), error = errno;
        if (close(pidfd)) { if (found != 1) found = -1; error = errno; }
        if (found == 1) { result = 1; break; }
        if (found < 0) {
            struct stat st;
            // Missing comm proves nothing about a live PID; verify the PID directory vanished.
            if ((error == ENOENT || error == ESRCH) &&
                fstatat(rootfd, entry->d_name, &st, AT_SYMLINK_NOFOLLOW) < 0 &&
                (errno == ENOENT || errno == ESRCH)) continue;
            result = -1;
        }
    }
    if (closedir(dir) && result != 1) result = -1;
    return result;
}
void cmd_cdpstate(conn_ctx *ctx, const char *args) {
    if (*args) { reply(ctx->fd, "ERR\n"); return; }
    int process = cdp_process();
    int tcp = listener(CDP_PROC_ROOT "/net/tcp", 8, 9222, 0);
    int tcp6 = listener(CDP_PROC_ROOT "/net/tcp6", 32, 9222, 1);
    if (process == 1 || tcp == 1 || tcp6 == 1) reply(ctx->fd, "CDPSTATE PRESENT\n");
    else if (process == 0 && tcp == 0 && tcp6 == 0) reply(ctx->fd, "CDPSTATE ABSENT\n");
    else reply(ctx->fd, "CDPSTATE UNKNOWN\n");
}
