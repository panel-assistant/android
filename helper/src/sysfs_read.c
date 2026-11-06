#include "sysfs_read.h"
#include "util.h"
#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <stdio.h>
#include <string.h>
#include <sys/stat.h>
#include <unistd.h>

#ifndef SYSFS_READ_CLASS_ROOT
#define SYSFS_READ_CLASS_ROOT "/sys/class"
#endif
#define SCALAR_BYTES 64u
#define DIRECTORY_ENTRIES 256u

static int whitespace(unsigned char c) { return c == ' ' || (c >= '\t' && c <= '\r'); }
static char state(int n) { return n < 0 ? '?' : (char)('0' + n); }
static int decimal(const char *s, unsigned maximum, unsigned *out) {
    if (!*s || (*s == '0' && s[1])) return 0;
    unsigned n = 0;
    for (; *s; s++) {
        if (*s < '0' || *s > '9') return 0;
        unsigned digit = (unsigned)(*s - '0');
        if (n > maximum / 10 || (n == maximum / 10 && digit > maximum % 10)) return 0;
        n = n * 10 + digit;
    }
    *out = n;
    return 1;
}
// Complete finite reads only; sysfs class/device symlinks are legitimate kernel layouts.
static int scalar(int dirfd, const char *name, const char *zero, const char *one) {
    int fd = openat(dirfd, name, O_RDONLY | O_CLOEXEC | O_NONBLOCK);
    if (fd < 0) return -1;
    char raw[SCALAR_BYTES + 1];
    size_t used = 0;
    int result = -1;
    struct stat st;
    if (fstat(fd, &st) || !S_ISREG(st.st_mode)) goto done;
    for (;;) {
        ssize_t got = read(fd, raw + used, sizeof raw - used);
        if (got < 0) { if (errno == EINTR) continue; goto done; }
        if (!got) break;
        used += (size_t)got;
        if (used > SCALAR_BYTES) goto done;
    }
    if (memchr(raw, 0, used)) goto done;
    size_t start = 0;
    while (start < used && whitespace((unsigned char)raw[start])) start++;
    while (used > start && whitespace((unsigned char)raw[used-1])) used--;
    raw[used] = 0;
    if (!strcmp(raw + start, zero)) result = 0;
    else if (!strcmp(raw + start, one)) result = 1;
done:
    close(fd);
    return result;
}
static int inaccessible(int error) {
    return error == ENOENT || error == ENOTDIR || error == EACCES || error == EPERM || error == EROFS;
}
static int relay_count(const char *path) {
    int fd = open(path, O_RDONLY | O_DIRECTORY | O_CLOEXEC | O_NONBLOCK);
    if (fd < 0) {
        int error = errno;
        struct stat st;
        // A dangling class symlink is not proof that the class itself is absent.
        return error == ENOENT && lstat(path, &st) < 0 && errno == ENOENT ? 0 : -1;
    }
    DIR *dir = fdopendir(fd);
    if (!dir) { close(fd); return -1; }
    unsigned present = 0, entries = 0;
    int result = -1;
    for (;;) {
        errno = 0;
        struct dirent *entry = readdir(dir);
        if (!entry) { if (errno) goto done; break; }
        if (++entries > DIRECTORY_ENTRIES) goto done;
        unsigned n;
        if (!strncmp(entry->d_name, "relay", 5) && decimal(entry->d_name + 5, 17, &n) && n >= 1)
            present |= 1u << (n - 1);
    }
    unsigned count = 0;
    while (count < 17 && (present & (1u << count))) count++;
    if (count > 16) goto done;
    result = (int)count;
done:
    closedir(dir);
    return result;
}
void cmd_relayread(conn_ctx *ctx, const char *args) {
    static const struct { const char *selector; const char *directory; } classes[] = {
        { "RELAY", "relay" }, { "ST_RELAY", "st_relay" }, { "STRELAY", "strelay" }
    };
    const char *selector = NULL, *directory = NULL, *value = NULL;
    for (unsigned i = 0; i < sizeof classes / sizeof classes[0]; i++) {
        size_t length = strlen(classes[i].selector);
        if (!strncmp(args, classes[i].selector, length) && args[length] == ' ') {
            selector = classes[i].selector; directory = classes[i].directory; value = args + length + 1; break;
        }
    }
    unsigned index = 0;
    int count = value && !strcmp(value, "COUNT");
    if (!selector || (!count && (!decimal(value, 16, &index) || index < 1))) { reply(ctx->fd, "ERR\n"); return; }
    char path[sizeof SYSFS_READ_CLASS_ROOT + 32], response[64];
    snprintf(path, sizeof path, "%s/%s", SYSFS_READ_CLASS_ROOT, directory);
    if (count) {
        int n = relay_count(path);
        if (n < 0) snprintf(response, sizeof response, "RELAYREAD %s COUNT=?\n", selector);
        else snprintf(response, sizeof response, "RELAYREAD %s COUNT=%d\n", selector, n);
    } else {
        int fd = open(path, O_RDONLY | O_DIRECTORY | O_CLOEXEC | O_NONBLOCK);
        int n = -1;
        if (fd >= 0) { char name[16]; snprintf(name, sizeof name, "relay%u", index); n = scalar(fd, name, "0", "1"); close(fd); }
        snprintf(response, sizeof response, "RELAYREAD %s RELAY=%u VALUE=%c\n", selector, index, state(n));
    }
    reply(ctx->fd, response);
}
void cmd_gpioread(conn_ctx *ctx, const char *args) {
    unsigned pin;
    if (!decimal(args, 65535, &pin)) { reply(ctx->fd, "ERR\n"); return; }
    char path[sizeof SYSFS_READ_CLASS_ROOT + 32], response[64];
    snprintf(path, sizeof path, "%s/gpio/gpio%u", SYSFS_READ_CLASS_ROOT, pin);
    int fd = open(path, O_RDONLY | O_DIRECTORY | O_CLOEXEC | O_NONBLOCK);
    int value = -1, prepared = -1;
    if (fd >= 0) {
        value = scalar(fd, "value", "0", "1");
        int direction = scalar(fd, "direction", "in", "out");
        int writable = faccessat(fd, "value", W_OK, 0) == 0 ? 1 : inaccessible(errno) ? 0 : -1;
        prepared = direction == 0 || writable == 0 ? 0 : direction == 1 && writable == 1 ? 1 : -1;
        close(fd);
    } else if (errno == ENOENT || errno == ENOTDIR) prepared = 0;
    snprintf(response, sizeof response, "GPIOREAD %u VALUE=%c PREPARED=%c\n", pin, state(value), state(prepared));
    reply(ctx->fd, response);
}
