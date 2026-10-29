#define _GNU_SOURCE
#include <fcntl.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <unistd.h>
/* Inject package/upgrade changes at destructive syscall boundaries, after the last
 * presence/inode observation. The daemon still runs its production entry and loop. */
int __real_unlinkat(int, const char *, int);
int __real_rmdir(const char *);
int __wrap_unlinkat(int directory, const char *name, int flags) {
    const char *cut = getenv("RECOVERY_RETIREMENT_CUT");
    if (cut && !strncmp(name, ".retiring-", 10) && !strcmp(cut, "replacement-race")) {
        int file = openat(directory, "helper", O_CREAT | O_EXCL | O_WRONLY, 0700);
        if (file >= 0) { if (write(file, "replacement", 11) != 11) abort(); close(file); }
    }
    int result = __real_unlinkat(directory, name, flags);
    if (!result && cut && !strncmp(name, ".retiring-", 10) && !strcmp(cut, "late-return"))
        (void)mkdir(APP_DATA, 0700);
    return result;
}
int __wrap_rmdir(const char *path) {
    int result = __real_rmdir(path);
    const char *cut = getenv("RECOVERY_RETIREMENT_CUT");
    if (!result && cut && !strcmp(cut, "directory-return")) (void)mkdir(APP_DATA, 0700);
    return result;
}
