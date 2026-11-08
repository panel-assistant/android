#define _GNU_SOURCE
#include "sysexec.h"
#include <dirent.h>
#include <errno.h>
#include <signal.h>
#include <stdatomic.h>
#include <stdio.h>
#include <string.h>
#include <sys/syscall.h>
#include <sys/wait.h>
#include <unistd.h>

/* Delay only kernel wait visibility for real, killed children. Fork, exec, signal,
 * process groups and the production custody path remain real. */
static atomic_int hold_reap, blocking_waits;
pid_t waitpid(pid_t pid, int *status, int options) {
    if (atomic_load(&hold_reap)) {
        if (!(options & WNOHANG)) {
            atomic_fetch_add(&blocking_waits, 1);
            errno = EBUSY;
            return -1;
        }
        siginfo_t info = {0};
        if (!waitid(P_PID, (id_t)pid, &info, WEXITED | WNOHANG | WNOWAIT) && info.si_pid == pid)
            return 0;
    }
    return (pid_t)syscall(SYS_wait4, pid, status, options, NULL);
}
static int failures;
#define CHECK(value, message) do { if (!(value)) { fprintf(stderr, "FAIL: %s\n", message); failures++; } } while (0)
static int thread_count(void) {
    DIR *directory = opendir("/proc/self/task");
    if (!directory) return -1;
    int count = 0; struct dirent *entry;
    while ((entry = readdir(directory))) if (entry->d_name[0] != '.') count++;
    closedir(directory); return count;
}
static void *thread_marker(void *value) { atomic_store((atomic_int *)value, 1); return NULL; }
static int pending_child(void) {
    siginfo_t info = {0};
    return waitid(P_ALL, 0, &info, WEXITED | WNOHANG | WNOWAIT) == 0;
}
int main(void) {
    sysexec_set_lifecycle_owner(1);
    atomic_int marker = 0;
    CHECK(sysexec_spawn(thread_marker, &marker) != 0 && !atomic_load(&marker),
          "lifecycle parent refuses background threads");

    const char *const status_argv[] = {"sh", "-c", "exit 7", NULL};
    pid_t active = -1; int status = 0;
    CHECK(!sysexec_start_argv("/bin/sh", status_argv, 1, &active), "owner starts an exec-proven status child");
    sysexec_sleep_ms(40);
    CHECK(sysexec_reap_pending() == 0, "parent tick does not consume active caller-owned status");
    CHECK(sysexec_poll_argv(active, &status) == 1 && WIFEXITED(status) && WEXITSTATUS(status) == 7,
          "active caller receives the exact status after parent tick");

    const char *const sleep_argv[] = {"sleep", "30", NULL};
    pid_t children[8];
    for (size_t i = 0; i < sizeof children / sizeof *children; i++) {
        children[i] = -1;
        CHECK(!sysexec_start_argv("/bin/sleep", sleep_argv, 1, &children[i]), "bounded owner capacity admits a child");
    }
    pid_t excess = -1;
    CHECK(sysexec_start_argv("/bin/sleep", sleep_argv, 1, &excess) != 0 && excess == -1,
          "active child custody exhaustion refuses an additional fork");
    atomic_store(&hold_reap, 1);
    for (size_t i = 0; i < sizeof children / sizeof *children; i++) if (children[i] > 1) {
        status = 0x1234;
        CHECK(sysexec_terminate_argv(children[i], &status) == -1 && status == 0x1234,
              "deferred owner reap never fabricates an exact wait status");
    }
    CHECK(sysexec_reap_pending() == 8, "delayed children retain bounded lifecycle custody");
    CHECK(thread_count() == 1 && atomic_load(&blocking_waits) == 0,
          "delayed lifecycle cleanup uses neither reaper threads nor blocking waits");
    CHECK(sysexec_start_argv("/bin/sleep", sleep_argv, 1, &excess) != 0,
          "deferred child custody exhaustion keeps fork admission closed");
    atomic_store(&hold_reap, 0);
    CHECK(sysexec_reap_pending() == 0 && !pending_child(), "parent tick eventually reaps every delayed child");
    CHECK(!sysexec_start_argv("/bin/sh", status_argv, 1, &active), "reaped custody permits new work");
    sysexec_sleep_ms(40);
    CHECK(sysexec_poll_argv(active, &status) == 1 && WIFEXITED(status) && WEXITSTATUS(status) == 7,
          "reused custody still preserves exact child status");

    /* This separate deadline entry had its own detached-reaper fallback. */
    atomic_store(&hold_reap, 1);
    unsigned elapsed = 0;
    CHECK(sysexec_run_argv_deadline("/bin/sleep", sleep_argv, 1, 20, &elapsed) == -1 && elapsed >= 520,
          "legacy deadline execution keeps its timeout and inline grace contract");
    CHECK(sysexec_reap_pending() == 1 && thread_count() == 1 && !atomic_load(&blocking_waits),
          "legacy deadline late reap also remains thread-free");
    atomic_store(&hold_reap, 0);
    CHECK(!sysexec_reap_pending() && !pending_child(), "legacy deadline deferred child is eventually reaped");

    pid_t worker = fork();
    if (!worker) {
        sysexec_set_lifecycle_owner(0);
        atomic_int done = 0;
        if (sysexec_spawn(thread_marker, &done)) _exit(1);
        for (int i = 0; i < 100 && !atomic_load(&done); i++) sysexec_sleep_ms(1);
        _exit(atomic_load(&done) ? 0 : 2);
    }
    CHECK(worker > 1 && waitpid(worker, &status, 0) == worker && WIFEXITED(status) && !WEXITSTATUS(status),
          "forked socket worker retains its ordinary background-thread behavior");
    CHECK(thread_count() == 1, "worker thread creation does not change the lifecycle parent");
    if (!failures) puts("lifecycle child custody contracts passed");
    return failures ? 1 : 0;
}
