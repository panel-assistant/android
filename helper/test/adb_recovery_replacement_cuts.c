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
    /* Model a slow old guardian after owner death without changing the shipped source. */
    return __real_usleep(duration == 200000 && getenv("RECOVERY_REPLACEMENT_SLOW_GUARDIAN") ? 1000000 : duration);
}
