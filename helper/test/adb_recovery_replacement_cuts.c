#define _GNU_SOURCE
#include <errno.h>
#include <signal.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <unistd.h>
static int owner_terminated;
int __real_kill(pid_t pid, int signal_number);
int __wrap_kill(pid_t pid, int signal_number) {
    int result = __real_kill(pid, signal_number);
    if (!result && signal_number == SIGTERM) owner_terminated = 1;
    return result;
}
int __real_fstatat(int directory, const char *name, struct stat *metadata, int flags);
int __wrap_fstatat(int directory, const char *name, struct stat *metadata, int flags) {
    if (owner_terminated && getenv("RECOVERY_REPLACEMENT_SCAN_ERROR") && !strcmp(name, "exe")) {
        errno = EACCES; return -1;
    }
    return __real_fstatat(directory, name, metadata, flags);
}
#include <stdio.h>
FILE *__real_fopen(const char *path, const char *mode);
FILE *__wrap_fopen(const char *path, const char *mode) {
    if (getenv("RECOVERY_REPLACEMENT_HIDE_LISTENER") && !strcmp(path, ADB_RECOVERY_TCP))
        return __real_fopen("/dev/null", mode);
    return __real_fopen(path, mode);
}

int __real_usleep(useconds_t duration);
int __wrap_usleep(useconds_t duration) {
    const char *block = getenv("RECOVERY_REPLACEMENT_GUARDIAN_BLOCK");
    if (duration == 200000 && block) {
        char marker[4096];
        if (snprintf(marker, sizeof marker, "%s.entered", block) >= (int)sizeof marker) abort();
        FILE *file = fopen(marker, "w");
        if (!file) abort();
        fclose(file);
        while (!access(block, F_OK)) __real_usleep(20000);
    }
    /* Model a slow old guardian after owner death without changing the shipped source. */
    return __real_usleep(duration == 200000 && getenv("RECOVERY_REPLACEMENT_SLOW_GUARDIAN") ? 1000000 : duration);
}

int __real_fsync(int descriptor);
int __wrap_fsync(int descriptor) {
    if (getenv("RECOVERY_REPLACEMENT_RECEIPT_SYNC_ERROR")) {
        char path[64], target[4096];
        snprintf(path, sizeof path, "/proc/self/fd/%d", descriptor);
        ssize_t n = readlink(path, target, sizeof target - 1);
        if (n >= 0) {
            target[n] = 0;
            const char *name = strrchr(target, '/');
            if (name && !strcmp(name, "/.replacement-retiring")) { errno = EIO; return -1; }
        }
    }
    return __real_fsync(descriptor);
}
