// Real production exec boundary. Only translate Android paths to isolated host fixtures; do not
// stub fork/wait/exec results. The kernel must reject the no-shebang file before /bin/sh runs it.
#define _GNU_SOURCE
#include "sysexec.h"

#include <errno.h>
#include <fcntl.h>
#include <poll.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <sys/wait.h>
#include <time.h>
#include <unistd.h>

static char fixture[256];
static const char *platform_path = "/system/bin/pm";
static int forced_errno;
static int failures, checks;

#define CHECK(condition, message) do { \
    checks++; \
    if (!(condition)) { fprintf(stderr, "FAIL: %s\n", message); failures++; } \
} while (0)

int __real_execve(const char *path, char *const argv[], char *const envp[]);
int __wrap_execve(const char *path, char *const argv[], char *const envp[]) {
    static const char *const expected_env[] = {
        "PATH=/system/bin:/vendor/bin", "ANDROID_ROOT=/system", "ANDROID_DATA=/data",
        "LANG=C", "LC_ALL=C", NULL
    };
    for (size_t i = 0; expected_env[i]; i++)
        if (!envp[i] || strcmp(envp[i], expected_env[i]) != 0) _exit(98);
    if (envp[5]) _exit(98);
    if (strcmp(path, platform_path) == 0) {
        if (forced_errno) { errno = forced_errno; return -1; }
        return __real_execve(fixture, argv, envp);
    }
    if (strcmp(path, "/system/bin/sh") == 0) {
        // The fallback must execute the script pathname as data, never construct sh -c text.
        if (!argv[0] || strcmp(argv[0], "sh") != 0 || !argv[1] ||
            strcmp(argv[1], platform_path) != 0) _exit(98);
        char *mapped[128];
        size_t i = 0;
        for (; argv[i] && i < 127; i++) mapped[i] = argv[i];
        if (argv[i]) _exit(98);
        mapped[i] = NULL;
        mapped[1] = fixture;
        return __real_execve("/bin/sh", mapped, envp);
    }
    return __real_execve(path, argv, envp);
}

static void write_fixture(const char *body, mode_t mode) {
    int fd = open(fixture, O_WRONLY | O_CREAT | O_TRUNC, 0700);
    if (fd < 0 || write(fd, body, strlen(body)) != (ssize_t)strlen(body) ||
        fchmod(fd, mode) != 0 || close(fd) != 0) {
        perror("fixture"); exit(2);
    }
}

static int exited(int status, int code) {
    return status >= 0 && WIFEXITED(status) && WEXITSTATUS(status) == code;
}

static unsigned long now_ms(void) {
    struct timespec now;
    if (clock_gettime(CLOCK_MONOTONIC, &now) != 0) exit(2);
    return (unsigned long)now.tv_sec * 1000UL + (unsigned long)now.tv_nsec / 1000000UL;
}

static int wait_started(pid_t pid) {
    int status = -1;
    for (int i = 0; i < 300; i++) {
        int state = sysexec_poll_argv(pid, &status);
        if (state == 1) return status;
        if (state < 0) return -1;
        sysexec_sleep_ms(10);
    }
    (void)sysexec_terminate_argv(pid, &status);
    return -1;
}

static void check_reaped(void) {
    siginfo_t info;
    errno = 0;
    CHECK(waitid(P_ALL, 0, &info, WEXITED | WNOHANG | WNOWAIT) < 0 && errno == ECHILD,
          "production reaps every owned child");
}

int main(void) {
    char directory[] = "/tmp/hapaneld-platform-wrapper-XXXXXX";
    if (!mkdtemp(directory)) return 2;
    snprintf(fixture, sizeof fixture, "%s/wrapper", directory);
    char sentinel[256];
    snprintf(sentinel, sizeof sentinel, "%s/injected", directory);
    char injection[384];
    snprintf(injection, sizeof injection, "$(/bin/touch %s); 'quoted'\nnext", sentinel);
    const char *const args[] = { "untrusted argv zero", "alpha beta", injection, "", "-n", NULL };
    const char *const plain[] = { "pm", NULL };
    static const char body[] =
        "# Vendor wrapper deliberately has no shebang.\n"
        "[ ! -e /proc/self/fd/9 ] || exit 92\n"
        "[ \"$ANDROID_ROOT:$ANDROID_DATA\" = /system:/data ] || exit 93\n"
        "[ -z \"${HAPANELD_TEST_LEAK+x}\" ] || exit 94\n"
        "printf '<%s>' \"$@\"\n"
        "exit 23\n";
    write_fixture(body, 0700);
    int leaked = open("/dev/null", O_RDONLY);
    if (leaked < 0 || dup2(leaked, 9) < 0) return 2;
    if (leaked != 9) close(leaked);
    if (setenv("HAPANELD_TEST_LEAK", "must not survive", 1) != 0) return 2;

    static const char *const allowed[] = {
        "/system/bin/pm", "/system/bin/am", "/system/bin/appops", "/system/bin/settings"
    };
    for (size_t i = 0; i < sizeof allowed / sizeof allowed[0]; i++) {
        platform_path = allowed[i];
        CHECK(exited(sysexec_run_argv(platform_path, args, 1), 23),
              "each trusted no-shebang wrapper executes and retains exit status");
    }
    platform_path = allowed[0];
    write_fixture("# no shebang\nexit 23\n", 0700);
    CHECK(exited(sysexec_run_argv(platform_path, plain, 0), 23),
          "nonquiet spawn also uses the platform fallback");
    write_fixture(body, 0700);
    char expected[512], output[512];
    snprintf(expected, sizeof expected, "<alpha beta><%s><><-n>", injection);
    CHECK(exited(sysexec_capture_argv(platform_path, args, output, sizeof output), 23) &&
          strcmp(output, expected) == 0,
          "capture preserves spaces, empty fields, quotes, metacharacters and argument order");
    CHECK(access(sentinel, F_OK) != 0 && errno == ENOENT, "shell-looking arguments remain data");
    int timed_out = -1;
    CHECK(exited(sysexec_capture_argv_timeout(platform_path, args, output, sizeof output, 1000, &timed_out), 23) &&
          timed_out == 0 && strcmp(output, expected) == 0, "bounded capture retains output and status");

    char stream_path[256];
    snprintf(stream_path, sizeof stream_path, "%s/stream", directory);
    int stream = open(stream_path, O_RDWR | O_CREAT | O_EXCL, 0600);
    CHECK(stream >= 0 && exited(sysexec_stream_argv(platform_path, args, stream), 23),
          "stream execution uses the same fallback");
    memset(output, 0, sizeof output);
    CHECK(lseek(stream, 0, SEEK_SET) == 0 && read(stream, output, sizeof output - 1) == (ssize_t)strlen(expected) &&
          strcmp(output, expected) == 0, "stream output retains exact argument fields");
    if (stream >= 0) close(stream);

    pid_t child = -1;
    int started = sysexec_start_argv(platform_path, args, 1, &child);
    CHECK(started == 0, "owned start acknowledges successful interpreter exec");
    if (started == 0) CHECK(exited(wait_started(child), 23), "owned start preserves exit status and reaping");
    write_fixture("#!/bin/sh\nexit 24\n", 0700);
    CHECK(exited(sysexec_run_argv(platform_path, plain, 1), 24), "direct exec success is unchanged");

    write_fixture("# no shebang\nexit 0\n", 0700);
    const char *large_args[65];
    for (size_t i = 0; i < 64; i++) large_args[i] = "argument";
    large_args[64] = NULL;
    CHECK(exited(sysexec_run_argv(platform_path, large_args, 1), 0), "bounded stack vector accepts 64 original arguments");
    const char *oversized[66];
    for (size_t i = 0; i < 65; i++) oversized[i] = "argument";
    oversized[65] = NULL;
    CHECK(exited(sysexec_run_argv(platform_path, oversized, 1), 127), "oversized fallback argv fails closed");
    started = sysexec_start_argv(platform_path, oversized, 1, &child);
    CHECK(started == -1,
          "exec handshake rejects oversized fallback before claiming success");
    if (started == 0) (void)wait_started(child);

    static const char *const rejected[] = {
        "/vendor/bin/pm", "/system/bin/../bin/pm", "/system/bin/pm-extra", "/system/bin/sh"
    };
    for (size_t i = 0; i < sizeof rejected / sizeof rejected[0]; i++) {
        platform_path = rejected[i];
        CHECK(exited(sysexec_run_argv(platform_path, plain, 1), 127), "nonallowlisted no-shebang path has no fallback");
    }
    platform_path = allowed[0];
    static const int rejected_errors[] = { EACCES, ENOENT, EIO, ETXTBSY };
    for (size_t i = 0; i < sizeof rejected_errors / sizeof rejected_errors[0]; i++) {
        forced_errno = rejected_errors[i];
        CHECK(exited(sysexec_run_argv(platform_path, plain, 1), 127), "only ENOEXEC can enable fallback");
        started = sysexec_start_argv(platform_path, plain, 1, &child);
        CHECK(started == -1,
              "failed direct exec preserves negative owned-start handshake");
        if (started == 0) (void)wait_started(child);
    }
    forced_errno = 0;
    write_fixture("# no shebang\nexit 0\n", 0600);
    CHECK(exited(sysexec_run_argv(platform_path, plain, 1), 127), "real kernel permission failure is not bypassed");

    // A shell child and its descendant hold this pipe open. EOF proves deadline cleanup killed both.
    write_fixture("# no shebang\n/bin/sleep 30 &\nexec /bin/sleep 30\n", 0700);
    int pipefd[2];
    if (pipe(pipefd) != 0) return 2;
    fflush(stdout);
    int saved_stdout = dup(STDOUT_FILENO);
    if (saved_stdout < 0 || dup2(pipefd[1], STDOUT_FILENO) < 0) return 2;
    close(pipefd[1]);
    unsigned elapsed = 0;
    unsigned long before = now_ms();
    int status = sysexec_run_argv_deadline(platform_path, plain, 0, 300, &elapsed);
    unsigned long duration = now_ms() - before;
    if (dup2(saved_stdout, STDOUT_FILENO) < 0) return 2;
    close(saved_stdout);
    struct pollfd event = { .fd = pipefd[0], .events = POLLIN | POLLHUP };
    CHECK(status < 0 && elapsed >= 300 && duration >= 300 && duration < 5000,
          "fallback keeps the original bounded deadline");
    CHECK(poll(&event, 1, 3000) == 1 && (event.revents & POLLHUP),
          "deadline kills the interpreter and its entire descendant group");
    close(pipefd[0]);
    timed_out = 0;
    CHECK(sysexec_capture_argv_timeout(platform_path, plain, output, sizeof output, 300, &timed_out) == -2 && timed_out == 1,
          "fallback capture timeout retains explicit expiry semantics");
    check_reaped();

    close(9);
    unlink(fixture);
    unlink(stream_path);
    unlink(sentinel);
    if (rmdir(directory) != 0) return 2;
    printf("platform wrapper boundary: %d checks, %d failures\n", checks, failures);
    return failures ? 1 : 0;
}
