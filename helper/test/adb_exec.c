// Run the production batch capture. Only execve is interposed: the host has no Android getprop.
// Child stdout and exit status drive the same bounded pipe, deadline and reap path used on-device.
#define _GNU_SOURCE
#include "sysexec.h"

#include <errno.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mman.h>
#include <sys/wait.h>
#include <time.h>
#include <unistd.h>

static int failures;
#define CHECK(condition, message) do { if (!(condition)) { \
    printf("FAIL: %s\n", message); failures++; \
} } while (0)

enum fixture_mode { NORMAL, MIXED, OVERFLOW_FIRST, TIMEOUT_FIRST, SHARED_BUDGET };
static enum fixture_mode mode;
static const char *const names[] = {
    "service.adb.listen_addrs", "service.adb.tcp.port", "persist.adb.tcp.port",
    "service.adb.tls.port", "persist.adb.tls_server.enable"
};
static const char *const values[] = { "127.0.0.1:5555\n", "5555\n", "0\n", "37123\n", "1\n" };
static const char nul_bytes[] = "0\0hidden-positive";
struct observations { unsigned calls, invalid, count[5], order[10]; };
static volatile struct observations *observed;

static int exact_environment(char *const actual[]) {
    static const char *const expected[] = {
        "PATH=/system/bin:/vendor/bin", "ANDROID_ROOT=/system", "ANDROID_DATA=/data",
        "LANG=C", "LC_ALL=C", NULL
    };
    size_t count = 0;
    while (actual[count]) {
        if (count >= 5) return 0;
        size_t found = 0;
        for (size_t i = 0; i < 5; i++) found += strcmp(actual[count], expected[i]) == 0;
        if (found != 1) return 0;
        for (size_t i = 0; i < count; i++) if (!strcmp(actual[count], actual[i])) return 0;
        count++;
    }
    return count == 5;
}

static void emit(const void *bytes, size_t size) {
    const unsigned char *p = bytes;
    while (size) {
        ssize_t n = write(STDOUT_FILENO, p, size);
        if (n < 0 && errno == EINTR) continue;
        if (n <= 0) _exit(125);
        p += (size_t)n;
        size -= (size_t)n;
    }
}

static void delay_ms(unsigned ms) {
    struct timespec delay = { .tv_sec = ms / 1000, .tv_nsec = (long)(ms % 1000) * 1000000 };
    while (nanosleep(&delay, &delay) && errno == EINTR) {}
}

int __wrap_execve(const char *path, char *const argv[], char *const envp[]) {
    size_t index = 0;
    while (index < 5 && (!argv[1] || strcmp(argv[1], names[index]))) index++;
    if (index == 5 || strcmp(path, "/system/bin/getprop") || !argv[0] ||
        strcmp(argv[0], "/system/bin/getprop") || argv[2] || !exact_environment(envp)) {
        observed->invalid++; _exit(126);
    }
    unsigned call = observed->calls++;
    if (call < 10) observed->order[call] = (unsigned)index;
    observed->count[index]++;
    if (mode == TIMEOUT_FIRST && index == 0) {
        emit("partial", 7);
        for (;;) pause();
    }
    if (mode == SHARED_BUDGET && index < 2) delay_ms(1200);
    if (mode == MIXED) {
        if (index == 0) _exit(0);
        if (index == 1) { emit(values[index], strlen(values[index])); _exit(7); }
        if (index == 2) { emit(nul_bytes, sizeof nul_bytes - 1); _exit(0); }
        char bytes[257]; memset(bytes, 'x', sizeof bytes);
        emit(bytes, index == 3 ? 256u : 257u);
        _exit(0);
    }
    if (mode == OVERFLOW_FIRST && index == 0) {
        char bytes[257]; memset(bytes, 'x', sizeof bytes); emit(bytes, sizeof bytes); _exit(0);
    }
    emit(values[index], strlen(values[index]));
    _exit(0);
}

static unsigned long long now_ms(void) {
    struct timespec ts;
    if (clock_gettime(CLOCK_MONOTONIC, &ts)) exit(2);
    return (unsigned long long)ts.tv_sec * 1000u + (unsigned long long)ts.tv_nsec / 1000000u;
}

// Observe without reaping: a test-side waitpid sweep could conceal a production child leak.
static int no_children(void) {
    siginfo_t info;
    memset(&info, 0, sizeof info);
    return waitid(P_ALL, 0, &info, WEXITED | WNOHANG | WNOWAIT) < 0 && errno == ECHILD;
}

static int eventually_no_children(void) {
    for (unsigned i = 0; i < 150; i++) {
        if (no_children()) return 1;
        delay_ms(20);
    }
    return no_children();
}

static char *copy_env(const char *name) {
    const char *value = getenv(name);
    if (!value) return NULL;
    char *copy = strdup(value);
    if (!copy) exit(2);
    return copy;
}

static void capture(enum fixture_mode next, struct adb_property_capture *result) {
    mode = next;
    memset((void *)observed, 0, sizeof *observed);
    memset(result, 0xa5, sizeof *result);
    sysexec_capture_adb_properties(result);
}

static int calls(unsigned count) {
    if (observed->calls != count || observed->invalid) return 0;
    for (unsigned i = 0; i < 5; i++) {
        if (observed->count[i] != (i < count ? 1u : 0u)) return 0;
        if (i < count && observed->order[i] != i) return 0;
    }
    return 1;
}

static int successful_value(const struct adb_property_capture *result, size_t index) {
    size_t size = strlen(values[index]);
    return result->status[index] == 0 && result->used[index] == size &&
        !memcmp(result->output[index], values[index], size + 1);
}

static int unknown_from(const struct adb_property_capture *result, size_t first) {
    for (size_t i = first; i < 5; i++)
        if (result->status[i] != -1 || result->used[i] != 0 || result->output[i][0] != 0) return 0;
    return 1;
}

int main(void) {
    observed = mmap(NULL, sizeof *observed, PROT_READ | PROT_WRITE, MAP_SHARED | MAP_ANONYMOUS, -1, 0);
    if (observed == MAP_FAILED) return 2;
    char *old_library = copy_env("LD_LIBRARY_PATH");
    char *old_ambient = copy_env("HAPANELD_ADB_EXEC_AMBIENT");
    if (setenv("LD_LIBRARY_PATH", "/fixture-parent-only", 1) ||
        setenv("HAPANELD_ADB_EXEC_AMBIENT", "must-not-reach-child", 1)) return 2;
    struct adb_property_capture result;
    capture(NORMAL, &result);
    int normal = calls(5);
    for (size_t i = 0; i < 5; i++) normal &= successful_value(&result, i);
    CHECK(normal, "ADBSTATE exec reads exactly five fixed properties in order with clean argv and environment");
    CHECK(!strcmp(getenv("LD_LIBRARY_PATH"), "/fixture-parent-only") &&
        !strcmp(getenv("HAPANELD_ADB_EXEC_AMBIENT"), "must-not-reach-child"),
        "ADBSTATE batch leaves the parent environment unchanged");

    capture(MIXED, &result);
    CHECK(calls(5), "ADBSTATE continues after empty nonzero and NUL captures to preserve later evidence");
    CHECK(result.status[0] == 0 && result.used[0] == 0 && result.output[0][0] == 0,
        "ADBSTATE successful empty property stays distinct from failed capture");
    CHECK(result.status[1] >= 0 && WIFEXITED(result.status[1]) && WEXITSTATUS(result.status[1]) == 7 &&
        result.used[1] == strlen(values[1]) && !memcmp(result.output[1], values[1], strlen(values[1]) + 1),
        "ADBSTATE preserves each nonzero child status and complete stdout independently");
    CHECK(result.status[2] == 0 && result.used[2] == sizeof nul_bytes - 1 &&
        !memcmp(result.output[2], nul_bytes, sizeof nul_bytes),
        "ADBSTATE exact byte count preserves embedded NUL for module validation");
    CHECK(result.status[3] == 0 && result.used[3] == 256 && result.output[3][0] == 'x' &&
        result.output[3][255] == 'x' && result.output[3][256] == 0,
        "ADBSTATE accepts exactly 256 stdout bytes after complete EOF");
    CHECK(result.status[4] == -1 && result.used[4] == 0,
        "ADBSTATE overflow probe rejects 257 bytes instead of a successful prefix");
    CHECK(eventually_no_children(), "ADBSTATE overflow capture kills and reaps its child");

    capture(OVERFLOW_FIRST, &result);
    int later = calls(5) && result.status[0] == -1 && result.used[0] == 0;
    for (size_t i = 1; i < 5; i++) later &= successful_value(&result, i);
    CHECK(later, "ADBSTATE property overflow does not discard later independent property evidence");

    unsigned long long start = now_ms();
    capture(TIMEOUT_FIRST, &result);
    unsigned long long elapsed = now_ms() - start;
    CHECK(calls(1) && result.status[0] == -2 && result.used[0] == 0 && unknown_from(&result, 1) &&
        elapsed >= 1700 && elapsed < 6000,
        "ADBSTATE exhausted deadline skips remaining properties as initialized unknown");
    CHECK(eventually_no_children(), "ADBSTATE exhausted deadline kills and reaps its child");

    start = now_ms();
    capture(SHARED_BUDGET, &result);
    elapsed = now_ms() - start;
    CHECK(calls(2) && successful_value(&result, 0) && result.status[1] == -2 &&
        result.used[1] == 0 && unknown_from(&result, 2) && elapsed >= 1700 && elapsed < 6000,
        "ADBSTATE property reads share one two-second deadline instead of per-property budgets");
    CHECK(eventually_no_children(), "ADBSTATE shared deadline kills and reaps its final child");
    CHECK(!strcmp(getenv("LD_LIBRARY_PATH"), "/fixture-parent-only") &&
        !strcmp(getenv("HAPANELD_ADB_EXEC_AMBIENT"), "must-not-reach-child"),
        "ADBSTATE failures also leave the parent environment unchanged");
    CHECK(eventually_no_children(), "ADBSTATE exec fixture leaves no pending children");
    if (old_library) { (void)setenv("LD_LIBRARY_PATH", old_library, 1); free(old_library); }
    else (void)unsetenv("LD_LIBRARY_PATH");
    if (old_ambient) { (void)setenv("HAPANELD_ADB_EXEC_AMBIENT", old_ambient, 1); free(old_ambient); }
    else (void)unsetenv("HAPANELD_ADB_EXEC_AMBIENT");
    (void)munmap((void *)observed, sizeof *observed);
    if (failures) return 1;
    puts("ADB EXEC OK");
    return 0;
}
