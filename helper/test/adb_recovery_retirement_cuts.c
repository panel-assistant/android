#define _GNU_SOURCE
#include <fcntl.h>
#include <errno.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <unistd.h>
/* Inject package/upgrade changes at destructive syscall boundaries, after the last
 * presence/inode observation. The daemon still runs its production entry and loop. */
int __real_mkdirat(int, const char *, mode_t);
int __wrap_mkdirat(int directory, const char *name, mode_t mode) {
    const char *cut = getenv("RECOVERY_RETIREMENT_CUT");
    if (cut && !strcmp(cut, "retirement-fails") && !strncmp(name, ".retiring-", 10)) {
        int file = open(APP_DATA ".attempts", O_CREAT | O_APPEND | O_WRONLY, 0600);
        if (file >= 0) { if (write(file, "x", 1) != 1) abort(); close(file); }
        errno = EIO; return -1;
    }
    return __real_mkdirat(directory, name, mode);
}
int __real_unlinkat(int, const char *, int);
int __real_rmdir(const char *);
int __wrap_unlinkat(int directory, const char *name, int flags) {
    const char *cut = getenv("RECOVERY_RETIREMENT_CUT");
    if (cut && !flags && !strncmp(name, ".retiring-", 10) && !strcmp(cut, "replacement-race")) {
        int file = openat(directory, "helper", O_CREAT | O_EXCL | O_WRONLY, 0700);
        if (file >= 0) { if (write(file, "replacement", 11) != 11) abort(); close(file); }
    }
    static int returned;
    int result = __real_unlinkat(directory, name, flags);
    if (!result && !flags && cut && !strncmp(name, ".retiring-", 10) && (!strcmp(cut, "late-return") || !strcmp(cut, "return-again")) && !returned) {
        returned = 1; (void)mkdir(APP_DATA, 0700);
    }
    return result;
}
int __wrap_rmdir(const char *path) {
    int result = __real_rmdir(path);
    const char *cut = getenv("RECOVERY_RETIREMENT_CUT");
    if (!result && cut && !strcmp(cut, "directory-return")) (void)mkdir(APP_DATA, 0700);
    return result;
}

/* Model pre-3.15 Android kernels: every process in the fixture receives ENOSYS
 * for renameat2. Retirement must use the older mkdirat/renameat/linkat APIs. */
#include <errno.h>
#include <linux/filter.h>
#include <linux/seccomp.h>
#include <stddef.h>
#include <sys/prctl.h>
#include <sys/syscall.h>
__attribute__((constructor)) static void old_kernel_syscalls(void) {
    struct sock_filter filters[] = {
        BPF_STMT(BPF_LD | BPF_W | BPF_ABS, offsetof(struct seccomp_data, nr)),
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, SYS_renameat2, 0, 1),
        BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ERRNO | ENOSYS),
        BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ALLOW),
    };
    struct sock_fprog program = { .len = sizeof filters / sizeof filters[0], .filter = filters };
    if (prctl(PR_SET_NO_NEW_PRIVS, 1, 0, 0, 0) || prctl(PR_SET_SECCOMP, SECCOMP_MODE_FILTER, &program)) abort();
}
