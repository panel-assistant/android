// Exercise production fork/pipe/counted/deadline capture. Only the Android execve destination is
// interposed: the child must receive the exact fixed dumpsys request and clean environment before
// its fixture output is emitted. Caller/package admission is tested separately through dispatch.
#define _GNU_SOURCE
#include "sysexec.h"

#include <errno.h>
#include <signal.h>
#include <stdatomic.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mman.h>
#include <sys/wait.h>
#include <time.h>
#include <unistd.h>

#define GFXINFO_BYTES (128u * 1024u)
static const char package[] = "io.panelassistant.android";
static const char report[] = "Total frames rendered: 42\nJanky frames: 3 (7.14%)\n";
static const char control_report[] = "clean-control\n";
static int failures;
#define CHECK(condition, message) do { if (!(condition)) { \
    printf("FAIL: %s\n", message); failures++; \
} } while (0)

enum fixture_mode { NORMAL, EMBEDDED_NUL, MAXIMUM, OVERFLOW, CHILD_FAILURE, TIMEOUT, CLEAN_CONTROL };
static enum fixture_mode mode;
// A normal static counter would be copied at fork and never observed by the parent. This counter
// records attempted child execs across the real process boundary, including rejected internal calls.
static atomic_uint *exec_calls;

static int exact_vector(char *const actual[], const char *const expected[]) {
    size_t i = 0;
    while (expected[i]) {
        if (!actual[i] || strcmp(actual[i], expected[i])) return 0;
        i++;
    }
    return actual[i] == NULL;
}
static int exact_environment(char *const actual[]) {
    static const char *const expected[] = {
        "PATH=/system/bin:/vendor/bin", "ANDROID_ROOT=/system", "ANDROID_DATA=/data",
        "LANG=C", "LC_ALL=C"
    };
    const size_t want = sizeof expected / sizeof expected[0];
    size_t count = 0;
    while (actual[count]) {
        if (count >= want) return 0;
        size_t found = 0;
        for (size_t i = 0; i < want; i++) found += strcmp(actual[count], expected[i]) == 0;
        if (found != 1) return 0;
        for (size_t i = 0; i < count; i++) if (!strcmp(actual[count], actual[i])) return 0;
        count++;
    }
    return count == want;
}
static void emit(const void *bytes, size_t size) {
    const unsigned char *p = bytes;
    while (size) {
        ssize_t n = write(STDOUT_FILENO, p, size);
        if (n < 0 && errno == EINTR) continue;
        if (n <= 0) _exit(125);
        p += (size_t)n; size -= (size_t)n;
    }
}
static void emit_bound(size_t length) {
    char chunk[4096]; memset(chunk, 'x', sizeof chunk);
    while (length) {
        size_t take = length < sizeof chunk ? length : sizeof chunk;
        emit(chunk, take); length -= take;
    }
}
int __wrap_execve(const char *path, char *const argv[], char *const envp[]) {
    atomic_fetch_add_explicit(exec_calls, 1, memory_order_relaxed);
    static const char *const gfxinfo_argv[] = {
        "/system/bin/dumpsys", "gfxinfo", "io.panelassistant.android", NULL
    };
    static const char *const control_argv[] = { "fixed-control", NULL };
    if (mode == CLEAN_CONTROL) {
        if (strcmp(path, "/fixed-host-env-control") || !exact_vector(argv, control_argv) ||
            !exact_environment(envp)) _exit(126);
        emit(control_report, sizeof control_report - 1); _exit(0);
    }
    if (strcmp(path, gfxinfo_argv[0]) || !exact_vector(argv, gfxinfo_argv) ||
        !exact_environment(envp)) _exit(126);
    switch (mode) {
    case NORMAL: emit(report, sizeof report - 1); break;
    case EMBEDDED_NUL: {
        static const char bytes[] = "Total frames rendered: 42\0trailing";
        emit(bytes, sizeof bytes - 1); break;
    }
    case MAXIMUM: emit_bound(GFXINFO_BYTES); break;
    case OVERFLOW: emit_bound(GFXINFO_BYTES + 1); break;
    case CHILD_FAILURE: emit(report, sizeof report - 1); _exit(7);
    case TIMEOUT: for (;;) pause();
    case CLEAN_CONTROL: _exit(126);
    }
    _exit(0);
}
static unsigned long long now_ms(void) {
    struct timespec ts;
    if (clock_gettime(CLOCK_MONOTONIC, &ts)) exit(2);
    return (unsigned long long)ts.tv_sec * 1000u + (unsigned long long)ts.tv_nsec / 1000000u;
}
// Observe without consuming a child: a test-side reap could conceal a production zombie leak.
static int no_children(void) {
    siginfo_t info; memset(&info, 0, sizeof info);
    return waitid(P_ALL, 0, &info, WEXITED | WNOHANG | WNOWAIT) < 0 && errno == ECHILD;
}
static int eventually_no_children(void) {
    for (unsigned i = 0; i < 150; i++) {
        if (no_children()) return 1;
        struct timespec delay = { .tv_nsec = 20 * 1000 * 1000 };
        while (nanosleep(&delay, &delay) && errno == EINTR) {}
    }
    return no_children();
}
static char *copy_env(const char *name) {
    const char *value = getenv(name);
    if (!value) return NULL;
    char *copy = strdup(value); if (!copy) exit(2);
    return copy;
}
static int env_equals(const char *name, const char *expected) {
    const char *value = getenv(name); return value && !strcmp(value, expected);
}
static void restore_env(const char *name, char *value) {
    if (value) { (void)setenv(name, value, 1); free(value); }
    else (void)unsetenv(name);
}
static void invalid_arguments(char *output, size_t capacity) {
    mode = NORMAL;
    unsigned before = atomic_load_explicit(exec_calls, memory_order_relaxed);
    const char *const invalid[] = { NULL, "" };
    for (size_t i = 0; i < sizeof invalid / sizeof invalid[0]; i++) {
        size_t used = 999;
        int result = sysexec_capture_gfxinfo(invalid[i], output, capacity, &used);
        CHECK(result < 0 && used == 0 && no_children() &&
            atomic_load_explicit(exec_calls, memory_order_relaxed) == before,
            "GFXINFO exec rejects missing internal package without an exec or pending child");
    }
    size_t used = 999;
    int result = sysexec_capture_gfxinfo(package, NULL, capacity, &used);
    CHECK(result < 0 && used == 0 && no_children() &&
        atomic_load_explicit(exec_calls, memory_order_relaxed) == before,
        "GFXINFO exec rejects missing output without an exec or pending child");
    used = 999;
    result = sysexec_capture_gfxinfo(package, output, 0, &used);
    CHECK(result < 0 && used == 0 && no_children() &&
        atomic_load_explicit(exec_calls, memory_order_relaxed) == before,
        "GFXINFO exec rejects zero capture capacity without an exec or pending child");
    result = sysexec_capture_gfxinfo(package, output, capacity, NULL);
    CHECK(result < 0 && no_children() &&
        atomic_load_explicit(exec_calls, memory_order_relaxed) == before,
        "GFXINFO exec requires counted output without an exec or pending child");
}
int main(void) {
    exec_calls = mmap(NULL, sizeof *exec_calls, PROT_READ | PROT_WRITE, MAP_SHARED | MAP_ANONYMOUS, -1, 0);
    if (exec_calls == MAP_FAILED) return 2;
    atomic_init(exec_calls, 0);
    if (!atomic_is_lock_free(exec_calls)) return 2;
    char *output = malloc(GFXINFO_BYTES + 1); if (!output) return 2;
    char *old_library = copy_env("LD_LIBRARY_PATH");
    char *old_ambient = copy_env("HAPANELD_GFXINFO_EXEC_AMBIENT");
    if (setenv("LD_LIBRARY_PATH", "/fixture-parent-only", 1) ||
        setenv("HAPANELD_GFXINFO_EXEC_AMBIENT", "must-not-reach-child", 1)) return 2;

    invalid_arguments(output, GFXINFO_BYTES + 1);
    mode = NORMAL;
    size_t used = 999;
    int result = sysexec_capture_gfxinfo(package, output, GFXINFO_BYTES + 1, &used);
    CHECK(result == 0 && used == sizeof report - 1 && !memcmp(output, report, sizeof report),
        "GFXINFO exec uses the exact fixed dumpsys package argv and clean environment");
    CHECK(env_equals("LD_LIBRARY_PATH", "/fixture-parent-only") &&
        env_equals("HAPANELD_GFXINFO_EXEC_AMBIENT", "must-not-reach-child"),
        "GFXINFO exec leaves the parent environment unchanged");

    mode = EMBEDDED_NUL; used = 999;
    static const char nul_bytes[] = "Total frames rendered: 42\0trailing";
    result = sysexec_capture_gfxinfo(package, output, GFXINFO_BYTES + 1, &used);
    CHECK(result == 0 && used == sizeof nul_bytes - 1 && !memcmp(output, nul_bytes, sizeof nul_bytes),
        "GFXINFO exec preserves counted bytes across embedded NUL");

    mode = MAXIMUM; used = 999;
    result = sysexec_capture_gfxinfo(package, output, GFXINFO_BYTES + 1, &used);
    CHECK(result == 0 && used == GFXINFO_BYTES && output[0] == 'x' &&
        output[GFXINFO_BYTES - 1] == 'x' && output[GFXINFO_BYTES] == 0,
        "GFXINFO exec accepts exactly 128 KiB only after complete EOF");
    mode = OVERFLOW; used = 999;
    result = sysexec_capture_gfxinfo(package, output, GFXINFO_BYTES + 1, &used);
    CHECK(result < 0 && used == 0,
        "GFXINFO exec rejects a 128 KiB plus one overflow without a successful prefix");
    CHECK(eventually_no_children(), "GFXINFO overflow kills and reaps its child");

    mode = CHILD_FAILURE; used = 999;
    result = sysexec_capture_gfxinfo(package, output, GFXINFO_BYTES + 1, &used);
    CHECK(result >= 0 && WIFEXITED(result) && WEXITSTATUS(result) == 7,
        "GFXINFO exec preserves a failing child exit status");

    mode = TIMEOUT; used = 999;
    unsigned long long start = now_ms();
    result = sysexec_capture_gfxinfo(package, output, GFXINFO_BYTES + 1, &used);
    unsigned long long elapsed = now_ms() - start;
    CHECK(result == -2 && used == 0 && elapsed >= 1800 && elapsed < 3500,
        "GFXINFO exec enforces the fixed 2000 ms deadline");
    CHECK(eventually_no_children(), "GFXINFO deadline kills and reaps its child");

    mode = CLEAN_CONTROL;
    const char *const argv[] = { "fixed-control", NULL };
    result = sysexec_capture_argv_timeout("/fixed-host-env-control", argv, output, GFXINFO_BYTES + 1, 500u, NULL);
    CHECK(result == 0 && !strcmp(output, control_report),
        "GFXINFO generic exec control retains the ordinary clean environment");
    CHECK(env_equals("LD_LIBRARY_PATH", "/fixture-parent-only") &&
        env_equals("HAPANELD_GFXINFO_EXEC_AMBIENT", "must-not-reach-child"),
        "GFXINFO generic exec control leaves the parent environment unchanged");
    CHECK(eventually_no_children(), "GFXINFO fixture leaves no pending children");

    restore_env("LD_LIBRARY_PATH", old_library);
    restore_env("HAPANELD_GFXINFO_EXEC_AMBIENT", old_ambient);
    free(output); munmap(exec_calls, sizeof *exec_calls);
    if (failures) return 1;
    puts("GFXINFO EXEC OK"); return 0;
}
