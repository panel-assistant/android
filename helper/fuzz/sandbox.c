// A link boundary for the target only: libFuzzer itself still owns real corpus/artifact I/O.
// All target filesystem paths are either private /tmp fixtures or fail closed. No real device,
// procfs, sysfs, executable, peer socket, process or thread can be acquired by the target.
#define _GNU_SOURCE
#include "sandbox.h"
#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <pthread.h>
#include <signal.h>
#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <sys/xattr.h>
#include <sys/wait.h>
#include <unistd.h>

static int active;
static char root[PATH_MAX];
static int denied(void) { errno = EACCES; return -1; }
static int safe_relative(const char *path) {
    if (!path || !*path || *path == '/') return 0;
    const char *p = path;
    while (*p) {
        const char *end = strchr(p, '/');
        size_t n = end ? (size_t)(end - p) : strlen(p);
        if (!n || (n == 2 && p[0] == '.' && p[1] == '.')) return 0;
        p += n;
        if (*p) p++;
    }
    return 1;
}
static int mapped(const char *path, char out[PATH_MAX]) {
    if (!path || (strcmp(path, "/tmp") && strncmp(path, "/tmp/", 5))) return 0;
    const char *tail = path + 4;
    if (*tail && !safe_relative(tail + 1)) return 0;
    int n = snprintf(out, PATH_MAX, "%s%s", root, tail);
    return n > 0 && n < PATH_MAX;
}
static int owned_fd(int fd) {
    char proc[64], path[PATH_MAX];
    snprintf(proc, sizeof proc, "/proc/self/fd/%d", fd);
    ssize_t n = readlink(proc, path, sizeof path - 1);
    if (n < 0) return 0;
    path[n] = 0;
    size_t len = strlen(root);
    return !strncmp(path, root, len) && (path[len] == 0 || path[len] == '/');
}
#define REAL(name, result, args) extern result __real_##name args
REAL(open, int, (const char *, int, ...));
REAL(openat, int, (int, const char *, int, ...));
REAL(fopen, FILE *, (const char *, const char *));
REAL(opendir, DIR *, (const char *));
REAL(mkdir, int, (const char *, mode_t));
REAL(mkdirat, int, (int, const char *, mode_t));
REAL(unlink, int, (const char *));
REAL(unlinkat, int, (int, const char *, int));
REAL(rmdir, int, (const char *));
REAL(rename, int, (const char *, const char *));
REAL(renameat, int, (int, const char *, int, const char *));
REAL(chmod, int, (const char *, mode_t));
REAL(fchmod, int, (int, mode_t));
REAL(fchown, int, (int, uid_t, gid_t));
REAL(fstatat, int, (int, const char *, struct stat *, int));
REAL(stat, int, (const char *, struct stat *));
REAL(lstat, int, (const char *, struct stat *));
REAL(access, int, (const char *, int));
REAL(faccessat, int, (int, const char *, int, int));
REAL(ioctl, int, (int, unsigned long, ...));
REAL(kill, int, (pid_t, int));
REAL(fork, pid_t, (void));
REAL(pthread_create, int, (pthread_t *, const pthread_attr_t *, void *(*)(void *), void *));
REAL(connect, int, (int, const struct sockaddr *, socklen_t));
REAL(execve, int, (const char *, char *const[], char *const[]));

REAL(linkat, int, (int, const char *, int, const char *, int));
REAL(fsetxattr, int, (int, const char *, const void *, size_t, int));
REAL(socket, int, (int, int, int));
REAL(bind, int, (int, const struct sockaddr *, socklen_t));
REAL(sendmsg, ssize_t, (int, const struct msghdr *, int));
REAL(waitpid, pid_t, (pid_t, int *, int));
REAL(setpgid, int, (pid_t, pid_t));
REAL(setsid, pid_t, (void));
REAL(_exit, void, (int)) __attribute__((noreturn));
int __wrap_linkat(int a, const char *b, int c, const char *d, int e) {
    return active ? denied() : __real_linkat(a, b, c, d, e);
}
int __wrap_fsetxattr(int fd, const char *name, const void *value, size_t n, int flags) {
    return active ? denied() : __real_fsetxattr(fd, name, value, n, flags);
}
int __wrap_socket(int domain, int type, int protocol) {
    return active ? denied() : __real_socket(domain, type, protocol);
}
int __wrap_bind(int fd, const struct sockaddr *addr, socklen_t n) {
    return active ? denied() : __real_bind(fd, addr, n);
}
// A fuzz input cannot transfer descriptors to any lifecycle owner. Header validation remains real;
// process fixtures exercise accepted IPC records and byte-counted execution.
ssize_t __wrap_sendmsg(int fd, const struct msghdr *message, int flags) {
    return active ? denied() : __real_sendmsg(fd, message, flags);
}
pid_t __wrap_waitpid(pid_t pid, int *status, int flags) {
    return active ? denied() : __real_waitpid(pid, status, flags);
}
int __wrap_setpgid(pid_t pid, pid_t group) {
    return active ? denied() : __real_setpgid(pid, group);
}
pid_t __wrap_setsid(void) { return active ? denied() : __real_setsid(); }
__attribute__((noreturn)) void __wrap__exit(int status) {
    if (active) abort(); // a fuzz input must not silently terminate the driver
    __real__exit(status);
}

int __wrap_open(const char *path, int flags, ...) {
    mode_t mode = 0;
    if (flags & O_CREAT) { va_list ap; va_start(ap, flags); mode = va_arg(ap, int); va_end(ap); }
    char dst[PATH_MAX];
    if (active && !mapped(path, dst)) return denied();
    return __real_open(active ? dst : path, flags | (active ? O_NOFOLLOW : 0), mode);
}
int __wrap_openat(int fd, const char *path, int flags, ...) {
    mode_t mode = 0;
    if (flags & O_CREAT) { va_list ap; va_start(ap, flags); mode = va_arg(ap, int); va_end(ap); }
    if (active && (!owned_fd(fd) || !safe_relative(path))) return denied();
    return __real_openat(fd, path, flags | (active ? O_NOFOLLOW : 0), mode);
}
FILE *__wrap_fopen(const char *path, const char *mode) {
    char dst[PATH_MAX];
    if (active && !mapped(path, dst)) { denied(); return NULL; }
    return __real_fopen(active ? dst : path, mode);
}
DIR *__wrap_opendir(const char *path) {
    char dst[PATH_MAX];
    if (active && !mapped(path, dst)) { denied(); return NULL; }
    return __real_opendir(active ? dst : path);
}
#define PATH1(name, extra_decl, extra_call) \
int __wrap_##name(const char *path extra_decl) { \
    char dst[PATH_MAX]; \
    if (active && !mapped(path, dst)) return denied(); \
    return __real_##name(active ? dst : path extra_call); \
}
PATH1(unlink, , )
PATH1(rmdir, , )
// Explicit definitions keep commas out of the one-path macro.
int __wrap_mkdir(const char *path, mode_t mode) {
    char dst[PATH_MAX]; if (active && !mapped(path, dst)) return denied();
    return __real_mkdir(active ? dst : path, mode);
}
int __wrap_chmod(const char *path, mode_t mode) {
    char dst[PATH_MAX]; if (active && !mapped(path, dst)) return denied();
    return __real_chmod(active ? dst : path, mode);
}
int __wrap_mkdirat(int fd, const char *path, mode_t mode) {
    if (active && (!owned_fd(fd) || !safe_relative(path))) return denied();
    return __real_mkdirat(fd, path, mode);
}
int __wrap_unlinkat(int fd, const char *path, int flags) {
    if (active && (!owned_fd(fd) || !safe_relative(path))) return denied();
    return __real_unlinkat(fd, path, flags);
}
int __wrap_renameat(int oldfd, const char *old, int newfd, const char *new) {
    if (active && (!owned_fd(oldfd) || !owned_fd(newfd) || !safe_relative(old) || !safe_relative(new))) return denied();
    return __real_renameat(oldfd, old, newfd, new);
}
int __wrap_rename(const char *old, const char *new) {
    char a[PATH_MAX], b[PATH_MAX]; if (active && (!mapped(old, a) || !mapped(new, b))) return denied();
    return __real_rename(active ? a : old, active ? b : new);
}
int __wrap_fchmod(int fd, mode_t mode) {
    if (active && !owned_fd(fd)) return denied();
    return __real_fchmod(fd, mode);
}
int __wrap_fchown(int fd, uid_t uid, gid_t gid) {
    if (active) return denied(); // no ownership changes, even inside the fixture
    return __real_fchown(fd, uid, gid);
}
int __wrap_fstatat(int fd, const char *path, struct stat *st, int flags) {
    if (active && (!owned_fd(fd) || !safe_relative(path))) return denied();
    return __real_fstatat(fd, path, st, flags);
}
int __wrap_stat(const char *path, struct stat *st) {
    char dst[PATH_MAX]; if (active && !mapped(path, dst)) return denied();
    return __real_stat(active ? dst : path, st);
}
int __wrap_lstat(const char *path, struct stat *st) {
    char dst[PATH_MAX]; if (active && !mapped(path, dst)) return denied();
    return __real_lstat(active ? dst : path, st);
}
int __wrap_access(const char *path, int mode) {
    char dst[PATH_MAX]; if (active && !mapped(path, dst)) return denied();
    return __real_access(active ? dst : path, mode);
}
int __wrap_faccessat(int fd, const char *path, int mode, int flags) {
    if (active && (!owned_fd(fd) || !safe_relative(path))) return denied();
    return __real_faccessat(fd, path, mode, flags);
}
int __wrap_ioctl(int fd, unsigned long request, ...) {
    if (active) return denied();
    va_list ap; va_start(ap, request); void *arg = va_arg(ap, void *); va_end(ap);
    return __real_ioctl(fd, request, arg);
}
// Only the supervisor liveness probe (signal 0 to the parent) passes; it delivers nothing and
// without it every Guard verb stops at the supervision gate.
int __wrap_kill(pid_t pid, int sig) {
    if (active && !(sig == 0 && pid > 1 && pid == getppid())) return denied();
    return __real_kill(pid, sig);
}
pid_t __wrap_fork(void) { return active ? denied() : __real_fork(); }
int __wrap_pthread_create(pthread_t *t, const pthread_attr_t *a, void *(*fn)(void *), void *arg) {
    return active ? EACCES : __real_pthread_create(t, a, fn, arg);
}
int __wrap_connect(int fd, const struct sockaddr *addr, socklen_t n) {
    return active ? denied() : __real_connect(fd, addr, n);
}
int __wrap_execve(const char *path, char *const argv[], char *const env[]) {
    return active ? denied() : __real_execve(path, argv, env);
}

void fuzz_sandbox_init(void) {
    const char *parent = getenv("HAPANELD_FUZZ_ROOT");
    if (!parent || *parent != '/' || snprintf(root, sizeof root, "%s/process-XXXXXX", parent) >= (int)sizeof root || !mkdtemp(root)) {
        fputs("fuzz: HAPANELD_FUZZ_ROOT must name a private absolute directory\n", stderr); abort();
    }
}
static void clear_dir(int fd) {
    DIR *dir = fdopendir(dup(fd));
    if (!dir) abort();
    struct dirent *entry;
    while ((entry = readdir(dir))) {
        if (!strcmp(entry->d_name, ".") || !strcmp(entry->d_name, "..")) continue;
        struct stat st;
        if (__real_fstatat(fd, entry->d_name, &st, AT_SYMLINK_NOFOLLOW)) abort();
        if (S_ISDIR(st.st_mode)) {
            int child = __real_openat(fd, entry->d_name, O_RDONLY | O_DIRECTORY | O_NOFOLLOW);
            if (child < 0) abort();
            clear_dir(child); close(child);
            if (__real_unlinkat(fd, entry->d_name, AT_REMOVEDIR)) abort();
        } else if (__real_unlinkat(fd, entry->d_name, 0)) abort();
    }
    closedir(dir);
}
void fuzz_sandbox_reset(void) {
    int fd = __real_open(root, O_RDONLY | O_DIRECTORY | O_NOFOLLOW);
    if (fd < 0) abort();
    clear_dir(fd); close(fd);
    // This is the existing HAPANELD_TEST fixed boot-id path, mapped into this private root.
    char boot[PATH_MAX];
    if (snprintf(boot, sizeof boot, "%s/.hapaneld-guard-db-test.boot-id", root) >= (int)sizeof boot) abort();
    FILE *out = __real_fopen(boot, "w");
    if (!out) abort();
    fputs("00000000-0000-0000-0000-000000000000\n", out); fclose(out);
}
void fuzz_sandbox_enter(void) { active = 1; }
void fuzz_sandbox_leave(void) { active = 0; }
const char *fuzz_sandbox_root(void) { return root; }
