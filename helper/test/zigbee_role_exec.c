// Production fork/pipe/deadline capture with only the destination execve interposed: a host does
// not have the fixed Android vendor client. Inspect the actual child argv/env before emitting its
// output, so the contract cannot pass merely because the dispatcher called a stub with a string.
#define _GNU_SOURCE
#include "sysexec.h"

#include <errno.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/wait.h>
#include <time.h>
#include <unistd.h>

static int failures;
#define CHECK(condition, message) do { if (!(condition)) { \
    printf("FAIL: %s\n", message); failures++; \
} } while (0)

enum fixture_mode { NORMAL, EMBEDDED_NUL, MAXIMUM, OVERFLOW, CHILD_FAILURE, TIMEOUT, CLEAN_CONTROL };
static enum fixture_mode mode;
static const char role_json[] = "{\"role\":\"Vendor repeater\"}\n";
static const char control_output[] = "clean-control\n";

static int exact_vector(char *const actual[], const char *const expected[]) {
    size_t i = 0;
    while (expected[i]) {
        if (!actual[i] || strcmp(actual[i], expected[i])) return 0;
        i++;
    }
    return actual[i] == NULL;
}

static int exact_environment(char *const actual[], int with_library_path) {
    static const char *const expected[] = {
        "PATH=/system/bin:/vendor/bin", "ANDROID_ROOT=/system", "ANDROID_DATA=/data",
        "LANG=C", "LC_ALL=C", "LD_LIBRARY_PATH=/vendor/bin/siliconlabs_host", NULL
    };
    size_t want = with_library_path ? 6u : 5u;
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
        p += (size_t)n;
        size -= (size_t)n;
    }
}

int __wrap_execve(const char *path, char *const argv[], char *const envp[]) {
    static const char *const role_argv[] = {
        "/vendor/bin/siliconlabs_host/mosquitto_sub", "-h", "127.0.0.1", "-p", "1883",
        "-i", "hapaneld_zr", "-t", "zigbee/system/network-role/information", "-C", "1", "-W", "3", NULL
    };
    static const char *const control_argv[] = { "fixed-control", NULL };
    if (mode == CLEAN_CONTROL) {
        if (strcmp(path, "/fixed-host-env-control") || !exact_vector(argv, control_argv) ||
            !exact_environment(envp, 0)) _exit(126);
        emit(control_output, sizeof control_output - 1);
        _exit(0);
    }
    if (strcmp(path, role_argv[0]) || !exact_vector(argv, role_argv) ||
        !exact_environment(envp, 1)) _exit(126);
    switch (mode) {
    case NORMAL: emit(role_json, sizeof role_json - 1); break;
    case EMBEDDED_NUL: {
        static const char bytes[] = "{\"role\":\"Repeater\"}\0trailing";
        emit(bytes, sizeof bytes - 1);
        break;
    }
    case MAXIMUM:
    case OVERFLOW: {
        char bytes[4097]; memset(bytes, 'x', sizeof bytes);
        emit(bytes, mode == MAXIMUM ? 4096u : 4097u);
        break;
    }
    case CHILD_FAILURE: emit(role_json, sizeof role_json - 1); _exit(7);
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

// Observe without reaping: a test-side waitpid sweep could hide a production zombie leak.
static int no_children(void) {
    siginfo_t info;
    memset(&info, 0, sizeof info);
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
    char *copy = strdup(value);
    if (!copy) exit(2);
    return copy;
}

int main(void) {
    char *old_library = copy_env("LD_LIBRARY_PATH");
    char *old_ambient = copy_env("HAPANELD_ZIGBEE_EXEC_AMBIENT");
    if (setenv("LD_LIBRARY_PATH", "/fixture-parent-only", 1) ||
        setenv("HAPANELD_ZIGBEE_EXEC_AMBIENT", "must-not-reach-child", 1)) return 2;
    char output[4097];
    size_t used = 999;
    mode = NORMAL;
    int result = sysexec_capture_zigbee_role(output, sizeof output, &used);
    CHECK(result == 0 && used == sizeof role_json - 1 &&
        !memcmp(output, role_json, sizeof role_json),
        "ZIGBEEROLE exec uses the exact fixed binary argv and isolated vendor environment");
    CHECK(!strcmp(getenv("LD_LIBRARY_PATH"), "/fixture-parent-only") &&
        !strcmp(getenv("HAPANELD_ZIGBEE_EXEC_AMBIENT"), "must-not-reach-child"),
        "ZIGBEEROLE leaves the parent environment unchanged");

    mode = EMBEDDED_NUL;
    used = 999;
    static const char nul_bytes[] = "{\"role\":\"Repeater\"}\0trailing";
    result = sysexec_capture_zigbee_role(output, sizeof output, &used);
    CHECK(result == 0 && used == sizeof nul_bytes - 1 && !memcmp(output, nul_bytes, sizeof nul_bytes),
        "ZIGBEEROLE capture preserves the exact byte count across embedded NUL");

    mode = MAXIMUM;
    used = 999;
    result = sysexec_capture_zigbee_role(output, sizeof output, &used);
    CHECK(result == 0 && used == 4096 && output[0] == 'x' && output[4095] == 'x' && output[4096] == 0,
        "ZIGBEEROLE capture accepts exactly 4096 bytes with complete EOF");

    mode = OVERFLOW;
    used = 999;
    result = sysexec_capture_zigbee_role(output, sizeof output, &used);
    CHECK(result < 0 && used == 0,
        "ZIGBEEROLE capture rejects overflow instead of reporting a successful prefix");
    CHECK(eventually_no_children(), "ZIGBEEROLE overflow kills and reaps its child");

    mode = CHILD_FAILURE;
    used = 999;
    result = sysexec_capture_zigbee_role(output, sizeof output, &used);
    CHECK(result >= 0 && WIFEXITED(result) && WEXITSTATUS(result) == 7,
        "ZIGBEEROLE capture preserves a failing child exit status");

    mode = TIMEOUT;
    used = 999;
    unsigned long long start = now_ms();
    result = sysexec_capture_zigbee_role(output, sizeof output, &used);
    unsigned long long elapsed = now_ms() - start;
    CHECK(result == -2 && used == 0 && elapsed >= 3000 && elapsed < 8000,
        "ZIGBEEROLE capture enforces its fixed deadline");
    CHECK(eventually_no_children(), "ZIGBEEROLE deadline kills and reaps its child");

    mode = CLEAN_CONTROL;
    const char *const argv[] = { "fixed-control", NULL };
    result = sysexec_capture_argv_timeout("/fixed-host-env-control", argv, output, sizeof output, 500u, NULL);
    CHECK(result == 0 && !strcmp(output, control_output),
        "ordinary bounded exec retains the clean environment without the Zigbee library path");
    CHECK(!strcmp(getenv("LD_LIBRARY_PATH"), "/fixture-parent-only"),
        "ordinary bounded exec also leaves the parent environment unchanged");
    CHECK(eventually_no_children(), "ZIGBEEROLE fixture leaves no pending children");

    if (old_library) { (void)setenv("LD_LIBRARY_PATH", old_library, 1); free(old_library); }
    else (void)unsetenv("LD_LIBRARY_PATH");
    if (old_ambient) { (void)setenv("HAPANELD_ZIGBEE_EXEC_AMBIENT", old_ambient, 1); free(old_ambient); }
    else (void)unsetenv("HAPANELD_ZIGBEE_EXEC_AMBIENT");
    if (failures) return 1;
    puts("ZIGBEE ROLE EXEC OK");
    return 0;
}
