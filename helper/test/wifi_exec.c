// Exercise the production fork/pipe/deadline path. Only execve is interposed because the host has
// no Android cmd/dumpsys binaries; inspect each actual child argv/env and emit controlled stdout.
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

enum fixture_mode { NORMAL, FALLBACK, BOTH_FAIL, EMBEDDED_NUL, NONZERO_NUL, MAXIMUM, OVERFLOW, TIMEOUT, SHARED_BUDGET };
static enum fixture_mode mode;
struct observations { unsigned cmd, dumpsys, invalid; };
static volatile struct observations *observed;
static const char cmd_output[] = "WifiInfo: SSID: \"Current, network\", BSSID: 00:11:22:33:44:55, RSSI: -51\n";
static const char fallback_output[] = "mWifiInfo SSID: \"Fallback\", BSSID: 00:11:22:33:44:55, RSSI: -63\n";

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
    static const char *const cmd_argv[] = { "/system/bin/cmd", "wifi", "status", NULL };
    static const char *const dumpsys_argv[] = { "/system/bin/dumpsys", "wifi", NULL };
    int fallback = !strcmp(path, dumpsys_argv[0]);
    if (!strcmp(path, cmd_argv[0])) observed->cmd++;
    else if (fallback) observed->dumpsys++;
    else { observed->invalid++; _exit(126); }
    if (!exact_vector(argv, fallback ? dumpsys_argv : cmd_argv) || !exact_environment(envp)) {
        observed->invalid++; _exit(126);
    }
    if (mode == TIMEOUT) for (;;) pause();
    if (mode == SHARED_BUDGET) {
        delay_ms(1200);
        if (!fallback) _exit(7);
    }
    if (!fallback && (mode == FALLBACK || mode == BOTH_FAIL)) {
        static const char failure[] = "Error: unknown command\n";
        emit(failure, sizeof failure - 1);
        _exit(7);
    }
    if (fallback && mode == BOTH_FAIL) { emit(fallback_output, sizeof fallback_output - 1); _exit(9); }
    if (mode == EMBEDDED_NUL || mode == NONZERO_NUL) {
        static const char bytes[] = "WifiInfo: SSID: x\0hidden RSSI: -20";
        emit(bytes, sizeof bytes - 1);
        if (mode == NONZERO_NUL) _exit(7);
    } else if (mode == MAXIMUM || mode == OVERFLOW) {
        char bytes[131073]; memset(bytes, 'x', sizeof bytes);
        emit(bytes, mode == MAXIMUM ? 131072u : 131073u);
    } else if (fallback) emit(fallback_output, sizeof fallback_output - 1);
    else emit(cmd_output, sizeof cmd_output - 1);
    _exit(0);
}

static unsigned long long now_ms(void) {
    struct timespec ts;
    if (clock_gettime(CLOCK_MONOTONIC, &ts)) exit(2);
    return (unsigned long long)ts.tv_sec * 1000u + (unsigned long long)ts.tv_nsec / 1000000u;
}

// A test-side reap could conceal a production leak, so only observe pending children here.
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

static void begin(enum fixture_mode next) {
    mode = next;
    observed->cmd = observed->dumpsys = observed->invalid = 0;
}

static int calls(unsigned cmd, unsigned dumpsys) {
    return observed->cmd == cmd && observed->dumpsys == dumpsys && !observed->invalid;
}

int main(void) {
    observed = mmap(NULL, sizeof *observed, PROT_READ | PROT_WRITE, MAP_SHARED | MAP_ANONYMOUS, -1, 0);
    if (observed == MAP_FAILED) return 2;
    char *old_library = copy_env("LD_LIBRARY_PATH");
    char *old_ambient = copy_env("HAPANELD_WIFI_EXEC_AMBIENT");
    if (setenv("LD_LIBRARY_PATH", "/fixture-parent-only", 1) ||
        setenv("HAPANELD_WIFI_EXEC_AMBIENT", "must-not-reach-child", 1)) return 2;
    char output[131073];
    size_t used = 999;
    begin(NORMAL);
    int result = sysexec_capture_wifi_status(output, sizeof output, &used);
    CHECK(result == 0 && calls(1, 0) && used == sizeof cmd_output - 1 &&
        !memcmp(output, cmd_output, sizeof cmd_output),
        "WIFISTATUS exec uses fixed cmd argv and clean environment without fallback on success");
    CHECK(!strcmp(getenv("LD_LIBRARY_PATH"), "/fixture-parent-only") &&
        !strcmp(getenv("HAPANELD_WIFI_EXEC_AMBIENT"), "must-not-reach-child"),
        "WIFISTATUS leaves the parent environment unchanged");

    begin(FALLBACK); used = 999;
    result = sysexec_capture_wifi_status(output, sizeof output, &used);
    CHECK(result == 0 && calls(1, 1) && used == sizeof fallback_output - 1 &&
        !memcmp(output, fallback_output, sizeof fallback_output),
        "WIFISTATUS nonzero cmd falls back to fixed dumpsys argv and discards failed stdout");

    begin(BOTH_FAIL); used = 999;
    result = sysexec_capture_wifi_status(output, sizeof output, &used);
    CHECK(result >= 0 && WIFEXITED(result) && WEXITSTATUS(result) == 9 && calls(1, 1),
        "WIFISTATUS preserves the failing fallback child status");

    begin(EMBEDDED_NUL); used = 999;
    static const char nul_bytes[] = "WifiInfo: SSID: x\0hidden RSSI: -20";
    result = sysexec_capture_wifi_status(output, sizeof output, &used);
    CHECK(result < 0 && calls(1, 0) && used == sizeof nul_bytes - 1 &&
        !memcmp(output, nul_bytes, sizeof nul_bytes),
        "WIFISTATUS rejects embedded NUL without fallback and retains the exact byte count");

    begin(NONZERO_NUL); used = 999;
    result = sysexec_capture_wifi_status(output, sizeof output, &used);
    CHECK(result < 0 && calls(1, 0) && used == sizeof nul_bytes - 1 &&
        !memcmp(output, nul_bytes, sizeof nul_bytes),
        "WIFISTATUS NUL in failed cmd output cannot invoke fallback");

    begin(MAXIMUM); used = 999;
    result = sysexec_capture_wifi_status(output, sizeof output, &used);
    CHECK(result == 0 && calls(1, 0) && used == 131072 && output[0] == 'x' &&
        output[131071] == 'x' && output[131072] == 0,
        "WIFISTATUS capture accepts exactly 128KiB with complete EOF");

    begin(OVERFLOW); used = 999;
    result = sysexec_capture_wifi_status(output, sizeof output, &used);
    CHECK(result < 0 && used == 0 && calls(1, 0),
        "WIFISTATUS overflow rejects the prefix without fallback");
    CHECK(eventually_no_children(), "WIFISTATUS overflow kills and reaps its child");

    begin(TIMEOUT); used = 999;
    unsigned long long start = now_ms();
    result = sysexec_capture_wifi_status(output, sizeof output, &used);
    unsigned long long elapsed = now_ms() - start;
    CHECK(result == -2 && used == 0 && calls(1, 0) && elapsed >= 1700 && elapsed < 6000,
        "WIFISTATUS timeout enforces its fixed deadline without fallback");
    CHECK(eventually_no_children(), "WIFISTATUS timeout kills and reaps its child");

    begin(SHARED_BUDGET); used = 999;
    start = now_ms();
    result = sysexec_capture_wifi_status(output, sizeof output, &used);
    elapsed = now_ms() - start;
    CHECK(result == -2 && used == 0 && calls(1, 1) && elapsed >= 1700 && elapsed < 6000,
        "WIFISTATUS cmd and fallback share one two-second deadline");
    CHECK(eventually_no_children(), "WIFISTATUS shared deadline kills and reaps the fallback child");

    CHECK(!strcmp(getenv("LD_LIBRARY_PATH"), "/fixture-parent-only") &&
        !strcmp(getenv("HAPANELD_WIFI_EXEC_AMBIENT"), "must-not-reach-child"),
        "WIFISTATUS fallback and failures preserve the parent environment");
    CHECK(eventually_no_children(), "WIFISTATUS fixture leaves no pending children");
    if (old_library) { (void)setenv("LD_LIBRARY_PATH", old_library, 1); free(old_library); }
    else (void)unsetenv("LD_LIBRARY_PATH");
    if (old_ambient) { (void)setenv("HAPANELD_WIFI_EXEC_AMBIENT", old_ambient, 1); free(old_ambient); }
    else (void)unsetenv("HAPANELD_WIFI_EXEC_AMBIENT");
    (void)munmap((void *)observed, sizeof *observed);
    if (failures) return 1;
    puts("WIFI EXEC OK");
    return 0;
}
