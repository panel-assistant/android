// Real-exec proof for the bounded actuator primitive. The unit suite links sysexec_stub.c and so can
// only assert the POLICY above sysexec (which deadline each mechanism is given, and that the
// remainder of the window is slept). Nothing there executes anything, so the property that actually
// matters on a panel — a wedged app_process wrapper does not pin the calling thread, and is not left
// behind as a zombie — has to be proved against the production sysexec.c with a real child.
#include "sysexec.h"

#include <errno.h>
#include <pthread.h>
#include <poll.h>
#include <signal.h>
#include <stdio.h>
#include <stdatomic.h>
#include <stdlib.h>
#include <string.h>
#include <sys/syscall.h>
#include <sys/wait.h>
#include <time.h>
#include <unistd.h>

static int failures;

#define CHECK(cond, msg)                                    \
    do {                                                    \
        if (!(cond)) {                                      \
            printf("FAIL: %s\n", msg);                      \
            failures++;                                     \
        }                                                   \
    } while (0)

static unsigned long now_ms(void) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (unsigned long)ts.tv_sec * 1000UL + (unsigned long)(ts.tv_nsec / 1000000L);
}

// A real D-state child cannot be manufactured safely in a host test. Interpose only this binary's
// waitpid so one real child remains observable but unreaped for longer than production's 500 ms
// inline grace. WNOHANG reports it unavailable until the release time; the detached reaper's blocking
// wait remains blocked until the same time, then performs the real wait4. This deterministically drives
// the otherwise hardware/kernel-dependent handoff without replacing fork, exec, kill, or the child.
static atomic_int delay_reap;
static atomic_int delayed_wait_observed;
static atomic_int detached_wait_observed;
static atomic_int main_blocking_wait_observed;
static atomic_ulong reap_release_ms;
static pthread_t test_main_thread;

pid_t waitpid(pid_t pid, int *status, int options) {
    if (atomic_load(&delay_reap)) {
        if (options & WNOHANG) {
            siginfo_t info;
            memset(&info, 0, sizeof info);
            if (waitid(P_PID, (id_t)pid, &info, WEXITED | WNOHANG | WNOWAIT) == 0 &&
                info.si_pid == pid) {
                unsigned long release = atomic_load(&reap_release_ms);
                if (release == 0) {
                    unsigned long proposed = now_ms() + 1200UL;
                    (void)atomic_compare_exchange_strong(&reap_release_ms, &release, proposed);
                    release = atomic_load(&reap_release_ms);
                }
                if (now_ms() < release) {
                    atomic_store(&delayed_wait_observed, 1);
                    return 0;
                }
            }
        } else {
            unsigned long release = atomic_load(&reap_release_ms);
            if (release != 0) {
                if (pthread_equal(pthread_self(), test_main_thread))
                    atomic_store(&main_blocking_wait_observed, 1);
                else
                    atomic_store(&detached_wait_observed, 1);
                while (now_ms() < release) sysexec_sleep_ms(20);
            }
        }
    }
    return (pid_t)syscall(SYS_wait4, pid, status, options, NULL);
}

// The helper runs on Android, where these live in /system/bin; the host build needs whichever of the
// usual two prefixes exists, and a missing tool must fail the test rather than silently pass it.
static const char *resolve(const char *const candidates[]) {
    for (int i = 0; candidates[i]; i++)
        if (access(candidates[i], X_OK) == 0) return candidates[i];
    return NULL;
}

// The probe must OBSERVE, never reap: a plain waitpid(-1, WNOHANG) sweep consumes the very zombie
// whose absence it is supposed to prove, so a production path that abandoned reaping entirely would
// still pass — the probe would have done the reap itself on its first call. waitid with WNOWAIT
// reports without consuming: it succeeds while any child is pending (an unreaped zombie or a live
// child alike) and fails ECHILD only once production code has genuinely reaped everything.
static int child_pending(void) {
    siginfo_t info;
    return waitid(P_ALL, 0, &info, WEXITED | WNOHANG | WNOWAIT) == 0;
}

// Reaping past the inline grace is handed to a detached thread, so "reaped" is eventually true
// rather than immediately true. Poll for it with a bound far above any sane handoff latency.
static int eventually_reaped(void) {
    for (int waited_ms = 0; waited_ms < 3000; waited_ms += 20) {
        if (!child_pending()) return 1;
        sysexec_sleep_ms(20);
    }
    return !child_pending();
}

static int eventually_true(atomic_int *value) {
    for (int waited_ms = 0; waited_ms < 3000; waited_ms += 20) {
        if (atomic_load(value)) return 1;
        sysexec_sleep_ms(20);
    }
    return atomic_load(value);
}

static int process_not_acting(pid_t pid) {
    char path[64];
    snprintf(path, sizeof path, "/proc/%ld/stat", (long)pid);
    FILE *stat = fopen(path, "r");
    if (!stat) return errno == ENOENT;
    char line[512];
    int inactive = 0;
    if (fgets(line, sizeof line, stat)) {
        char *command_end = strrchr(line, ')');
        inactive = command_end && command_end[1] == ' ' &&
            (command_end[2] == 'Z' || command_end[2] == 'X');
    }
    fclose(stat);
    return inactive;
}

static int eventually_not_acting(pid_t pid) {
    for (int waited_ms = 0; waited_ms < 3000; waited_ms += 20) {
        if (process_not_acting(pid)) return 1;
        sysexec_sleep_ms(20);
    }
    return process_not_acting(pid);
}

// Interpose only the destination write syscall. The source remains a real fork/exec/pipe through
// production sysexec.c; unlike the dispatcher stub, this seam exercises its actual retry loop.
static const char stream_payload[] = "PNG-fixture-payload";
static int stream_write_mode;
static size_t stream_write_calls, stream_bytes;
static char stream_output[sizeof stream_payload];

ssize_t write(int fd, const void *bytes, size_t size) {
    if (fd != 707 || !stream_write_mode) return syscall(SYS_write, fd, bytes, size);
    size_t call = stream_write_calls++;
    if (stream_write_mode == 1 && call == 1) { errno = EINTR; return -1; }
    if (stream_write_mode == 2 && call == 1) { errno = EPIPE; return -1; }
    if (stream_write_mode == 3) return 0;
    size_t accepted = size;
    if (call == 0 && accepted > 3) accepted = 3;
    if (accepted > sizeof stream_output - stream_bytes) return -1;
    memcpy(stream_output + stream_bytes, bytes, accepted);
    stream_bytes += accepted;
    return (ssize_t)accepted;
}

static void test_stream_writes(void) {
    for (int mode = 1; mode <= 3; mode++) {
        const char *const argv[] = { "bounded-exec", "--stream-fixture", mode == 1 ? "fast" : "wait", NULL };
        stream_write_mode = mode;
        stream_write_calls = stream_bytes = 0;
        memset(stream_output, 0, sizeof stream_output);
        unsigned long began = now_ms();
        int status = sysexec_stream_argv("/proc/self/exe", argv, 707);
        unsigned long took = now_ms() - began;
        stream_write_mode = 0;
        if (mode == 1) {
            CHECK(status == 0, "real stream succeeds across short writes and EINTR");
            CHECK(stream_write_calls >= 3 && stream_bytes == sizeof stream_payload - 1 &&
                  memcmp(stream_output, stream_payload, sizeof stream_payload - 1) == 0,
                  "real stream preserves every byte across short writes and EINTR");
        } else {
            CHECK(status < 0, "real stream reports terminal output failure");
            CHECK(took < 500UL, "terminal output failure aborts instead of draining a live source");
            CHECK(stream_write_calls == (mode == 2 ? 2u : 1u) &&
                  stream_bytes == (mode == 2 ? 3u : 0u),
                  "real stream emits only the accepted prefix and never retries terminal writes");
        }
        CHECK(!child_pending(), "real stream reaps its source after success or output failure");
    }
}

int main(int argc, char **argv) {
    if (argc == 3 && strcmp(argv[1], "--stream-fixture") == 0) {
        if (syscall(SYS_write, STDOUT_FILENO, stream_payload, sizeof stream_payload - 1) !=
            (ssize_t)(sizeof stream_payload - 1)) return 2;
        if (strcmp(argv[2], "wait") == 0) sysexec_sleep_ms(1200);
        return 0;
    }
    test_stream_writes();
    static const char *const sleep_candidates[] = { "/bin/sleep", "/usr/bin/sleep", NULL };
    static const char *const true_candidates[] = { "/bin/true", "/usr/bin/true", NULL };
    const char *sleep_path = resolve(sleep_candidates);
    const char *true_path = resolve(true_candidates);
    if (!sleep_path || !true_path) {
        printf("FAIL: bounded-exec test needs sleep and true on the build host\n");
        return 1;
    }

    // A child that outlives its deadline: the call must return on the deadline, not on the child.
    {
        const char *const argv[] = { "sleep", "30", NULL };
        unsigned elapsed = 0;
        unsigned long began = now_ms();
        int status = sysexec_run_argv_deadline(sleep_path, argv, 1, 300u, &elapsed);
        unsigned long took = now_ms() - began;
        CHECK(status < 0, "a child that outlives its deadline reports no status\n");
        CHECK(took < 5000UL, "a wedged actuator releases the calling thread at its deadline\n");
        CHECK(took >= 300UL, "the deadline is honoured in full before the child is killed\n");
        CHECK(elapsed >= 300u, "an expired deadline reports its whole window as consumed\n");
        CHECK(eventually_reaped(),
              "a child killed at its deadline is reaped, not left a zombie\n");
    }

    // A wrapper that forked its real worker before wedging: killing only the direct child would leave
    // that worker acting past the deadline, unowned. Every process in the actuator's group inherits
    // this test's stdout, so a pipe placed there reads EOF exactly when the LAST of them is gone —
    // the leader alone dying is not enough to release it.
    {
        static const char *const sh_candidates[] = { "/bin/sh", "/usr/bin/sh", NULL };
        const char *sh_path = resolve(sh_candidates);
        int pipe_fds[2] = { -1, -1 };
        if (!sh_path || pipe(pipe_fds) != 0) {
            printf("FAIL: descendant test needs sh and a pipe on the build host\n");
            failures++;
        } else {
            fflush(stdout);
            int saved_stdout = dup(STDOUT_FILENO);
            dup2(pipe_fds[1], STDOUT_FILENO);
            close(pipe_fds[1]);
            // Absolute paths: children run under sysexec's sanitized Android PATH, which resolves
            // nothing on a build host — the same property the whole issue is about.
            char script[256];
            snprintf(script, sizeof script, "%s 30 & exec %s 31", sleep_path, sleep_path);
            const char *const argv[] = { "sh", "-c", script, NULL };
            unsigned elapsed = 0;
            int status = sysexec_run_argv_deadline(sh_path, argv, 0, 300u, &elapsed);
            fflush(stdout);
            dup2(saved_stdout, STDOUT_FILENO);
            close(saved_stdout);
            struct pollfd probe = { .fd = pipe_fds[0], .events = POLLIN, .revents = 0 };
            int drained = poll(&probe, 1, 3000);
            close(pipe_fds[0]);
            CHECK(status < 0, "the wrapper itself is expired at its deadline\n");
            CHECK(drained == 1 && (probe.revents & POLLHUP),
                  "a timed-out actuator's forked descendants die with it, not just the direct child\n");
            CHECK(eventually_reaped(), "the killed group's leader is still reaped\n");
        }
    }

    // Guard's monotonic timeout API has distinct result semantics (-2 plus timed_out), but it owns the
    // same process-group safety boundary as the RC1 deadline API.
    {
        static const char *const sh_candidates[] = { "/bin/sh", "/usr/bin/sh", NULL };
        const char *sh_path = resolve(sh_candidates);
        int pipe_fds[2] = { -1, -1 };
        if (!sh_path || pipe(pipe_fds) != 0) {
            printf("FAIL: timeout descendant test needs sh and a pipe on the build host\n");
            failures++;
        } else {
            fflush(stdout);
            int saved_stdout = dup(STDOUT_FILENO);
            dup2(pipe_fds[1], STDOUT_FILENO);
            close(pipe_fds[1]);
            char script[256];
            snprintf(script, sizeof script, "%s 30 & exec %s 31", sleep_path, sleep_path);
            const char *const argv[] = { "sh", "-c", script, NULL };
            int timed_out = 0;
            unsigned long began = now_ms();
            int status = sysexec_run_argv_timeout(sh_path, argv, 0, 300u, &timed_out);
            unsigned long took = now_ms() - began;
            fflush(stdout);
            dup2(saved_stdout, STDOUT_FILENO);
            close(saved_stdout);
            struct pollfd probe = { .fd = pipe_fds[0], .events = POLLIN, .revents = 0 };
            int drained = poll(&probe, 1, 3000);
            close(pipe_fds[0]);
            CHECK(status == -2 && timed_out == 1,
                  "the monotonic timeout preserves its -2 plus timed_out result\n");
            CHECK(took >= 300UL && took < 5000UL,
                  "the monotonic timeout remains bounded around its requested window\n");
            CHECK(drained == 1 && (probe.revents & POLLHUP),
                  "the monotonic timeout kills forked descendants with their leader\n");
            CHECK(eventually_reaped(), "the monotonic timeout reaps its group leader\n");
        }
    }

    // The separately-started Guard executor must prove exec, remain pollable, and terminate its whole
    // group. The shell announces after forking so the assertion cannot pass by killing it too early.
    {
        static const char *const sh_candidates[] = { "/bin/sh", "/usr/bin/sh", NULL };
        const char *sh_path = resolve(sh_candidates);
        int pipe_fds[2] = { -1, -1 };
        if (!sh_path || pipe(pipe_fds) != 0) {
            printf("FAIL: start/terminate descendant test needs sh and a pipe on the build host\n");
            failures++;
        } else {
            fflush(stdout);
            int saved_stdout = dup(STDOUT_FILENO);
            dup2(pipe_fds[1], STDOUT_FILENO);
            close(pipe_fds[1]);
            char script[256];
            snprintf(script, sizeof script, "%s 30 & echo ready; exec %s 31",
                     sleep_path, sleep_path);
            const char *const argv[] = { "sh", "-c", script, NULL };
            pid_t pid = -1;
            int started = sysexec_start_argv(sh_path, argv, 0, &pid);
            fflush(stdout);
            dup2(saved_stdout, STDOUT_FILENO);
            close(saved_stdout);
            struct pollfd ready_probe = { .fd = pipe_fds[0], .events = POLLIN, .revents = 0 };
            int ready = poll(&ready_probe, 1, 3000);
            char announcement[16] = { 0 };
            ssize_t announced = ready > 0 ? read(pipe_fds[0], announcement,
                                                sizeof announcement - 1) : -1;
            int before = 0, wait_status = 0;
            if (started == 0) before = sysexec_poll_argv(pid, &wait_status);
            int terminated = started == 0 ? sysexec_terminate_argv(pid, &wait_status) : -1;
            struct pollfd drained_probe = { .fd = pipe_fds[0], .events = POLLIN, .revents = 0 };
            int drained = poll(&drained_probe, 1, 3000);
            close(pipe_fds[0]);
            CHECK(started == 0 && pid > 1, "start returns an exec-proven process-group leader\n");
            CHECK(announced > 0 && strstr(announcement, "ready") != NULL,
                  "the executor forked its descendant before termination\n");
            CHECK(before == 0, "poll reports the exec-proven executor still running\n");
            CHECK(terminated == 0 && WIFSIGNALED(wait_status) && WTERMSIG(wait_status) == SIGKILL,
                  "terminate returns the leader's exact SIGKILL wait status\n");
            CHECK(drained == 1 && (drained_probe.revents & POLLHUP),
                  "terminate kills the executor's forked descendants too\n");
            CHECK(eventually_reaped(), "terminate reaps its process-group leader\n");
        }
    }

    // Bounded capture must not wait on a descendant that inherited stdout, and its timeout result and
    // partial-output/NUL contract stay distinct from ordinary child failure.
    {
        static const char *const sh_candidates[] = { "/bin/sh", "/usr/bin/sh", NULL };
        const char *sh_path = resolve(sh_candidates);
        if (!sh_path) {
            printf("FAIL: capture timeout descendant test needs sh on the build host\n");
            failures++;
        } else {
            char script[256];
            snprintf(script, sizeof script, "%s 30 & echo descendant=$!; exec %s 31",
                     sleep_path, sleep_path);
            const char *const argv[] = { "sh", "-c", script, NULL };
            char output[64];
            memset(output, 0xa5, sizeof output);
            int timed_out = 0;
            unsigned long began = now_ms();
            int status = sysexec_capture_argv_timeout(
                sh_path, argv, output, sizeof output, 300u, &timed_out);
            unsigned long took = now_ms() - began;
            char *nul = memchr(output, '\0', sizeof output);
            char *pid_text = memchr(output, '=', sizeof output);
            pid_t descendant = nul && pid_text && pid_text < nul
                ? (pid_t)strtol(pid_text + 1, NULL, 10) : -1;
            CHECK(status == -2 && timed_out == 1,
                  "capture timeout preserves its -2 plus timed_out result\n");
            CHECK(nul != NULL && strncmp(output, "descendant=", 11) == 0 && descendant > 1,
                  "capture timeout preserves bounded partial output and NUL termination\n");
            CHECK(took >= 300UL && took < 5000UL,
                  "capture with a stdout-holding descendant remains bounded\n");
            CHECK(descendant > 1 && eventually_not_acting(descendant),
                  "capture timeout kills its stdout-holding descendant\n");
            CHECK(eventually_reaped(), "capture timeout reaps its process-group leader\n");
        }
    }

    // Fast success keeps the Guard APIs' raw-status/timed_out contract, and a metacharacter-bearing
    // argument reaches echo literally: the timeout/capture reconciliation must not introduce a shell.
    {
        static const char *const echo_candidates[] = { "/bin/echo", "/usr/bin/echo", NULL };
        const char *echo_path = resolve(echo_candidates);
        if (!echo_path) {
            printf("FAIL: structural argv test needs echo on the build host\n");
            failures++;
        } else {
            const char *const true_argv[] = { "true", NULL };
            int run_timed_out = 9;
            int run_status = sysexec_run_argv_timeout(
                true_path, true_argv, 1, 2000u, &run_timed_out);
            const char *const echo_argv[] = { "echo", "literal;$(not-a-command)", NULL };
            char output[64];
            int capture_timed_out = 9;
            int capture_status = sysexec_capture_argv_timeout(
                echo_path, echo_argv, output, sizeof output, 2000u, &capture_timed_out);
            CHECK(run_status == 0 && run_timed_out == 0,
                  "a fast timeout child keeps its raw success status and clears timed_out\n");
            CHECK(capture_status == 0 && capture_timed_out == 0,
                  "a fast capture keeps its raw success status and clears timed_out\n");
            CHECK(strcmp(output, "literal;$(not-a-command)\n") == 0,
                  "capture executes the structural argv literally without a shell\n");
            CHECK(!child_pending(), "successful timeout APIs reap their direct children\n");

            // Output larger than the caller's buffer is a failure, never a truncated success: the
            // Guard sqlite proof parses this text and must not accept a prefix of it.
            const char *const long_argv[] = { "echo", "0123456789", NULL };
            char small[8];
            memset(small, 0xa5, sizeof small);
            int overflow_timed_out = 9;
            int overflow_status = sysexec_capture_argv_timeout(
                echo_path, long_argv, small, sizeof small, 2000u, &overflow_timed_out);
            CHECK(overflow_status == -1 && overflow_timed_out == 0 &&
                  memchr(small, '\0', sizeof small) != NULL && !child_pending(),
                  "capture output beyond its buffer is reported as failure, not truncated success\n");
        }
    }

    // The exec-proven child keeps Guard's parent-death contract. Run start in a short-lived owner
    // process; once that owner exits, the real sleep must be killed without an explicit terminate.
    {
        int pid_pipe[2] = { -1, -1 };
        if (pipe(pid_pipe) != 0) {
            printf("FAIL: PDEATHSIG test needs a pipe on the build host\n");
            failures++;
        } else {
            pid_t owner = fork();
            if (owner == 0) {
                close(pid_pipe[0]);
                const char *const argv[] = { "sleep", "30", NULL };
                pid_t child = -1;
                int started = sysexec_start_argv(sleep_path, argv, 1, &child);
                if (started == 0 && write(pid_pipe[1], &child, sizeof child) == sizeof child)
                    _exit(0);
                _exit(1);
            }
            close(pid_pipe[1]);
            pid_t child = -1;
            ssize_t received;
            do received = read(pid_pipe[0], &child, sizeof child);
            while (received < 0 && errno == EINTR);
            close(pid_pipe[0]);
            int owner_status = 0;
            pid_t owner_wait;
            do owner_wait = waitpid(owner, &owner_status, 0);
            while (owner_wait < 0 && errno == EINTR);
            CHECK(owner_wait == owner && WIFEXITED(owner_status) && WEXITSTATUS(owner_status) == 0 &&
                      received == sizeof child && child > 1,
                  "the short-lived owner started and reported an exec-proven child\n");
            CHECK(child > 1 && eventually_not_acting(child),
                  "PDEATHSIG kills the exec-proven child when its owner exits\n");
        }
    }

    // Drive the real detached-reaper branch with one real execed child whose wait visibility is
    // delayed beyond the 500 ms inline grace. The caller must return while the child remains pending;
    // a different thread then completes the real wait when the artificial kernel delay releases it.
    {
        const char *const argv[] = { "sleep", "30", NULL };
        test_main_thread = pthread_self();
        atomic_store(&reap_release_ms, 0);
        atomic_store(&delayed_wait_observed, 0);
        atomic_store(&detached_wait_observed, 0);
        atomic_store(&main_blocking_wait_observed, 0);
        atomic_store(&delay_reap, 1);
        int timed_out = 0;
        unsigned long began = now_ms();
        int status = sysexec_run_argv_timeout(sleep_path, argv, 1, 300u, &timed_out);
        unsigned long took = now_ms() - began;
        CHECK(status == -2 && timed_out == 1,
              "an inline-unreapable timeout preserves its public timeout result\n");
        CHECK(atomic_load(&delayed_wait_observed),
              "the child remained unavailable to WNOHANG beyond the inline grace\n");
        CHECK(took >= 750UL && took < 3000UL,
              "the caller spends only the timeout plus bounded inline reap grace\n");
        CHECK(child_pending(), "the detached handoff returns before the delayed child is reapable\n");
        CHECK(eventually_true(&detached_wait_observed),
              "a detached reaper owns the eventual blocking wait\n");
        CHECK(!atomic_load(&main_blocking_wait_observed),
              "the calling thread never falls back to an unbounded wait\n");
        CHECK(eventually_reaped(), "the detached reaper eventually consumes the real child\n");
        atomic_store(&delay_reap, 0);
    }

    // A child that exits inside its deadline: real status, and the unused remainder is the caller's.
    {
        const char *const argv[] = { "true", NULL };
        unsigned elapsed = 0;
        unsigned long began = now_ms();
        int status = sysexec_run_argv_deadline(true_path, argv, 1, 5000u, &elapsed);
        unsigned long took = now_ms() - began;
        CHECK(status == 0, "a fast actuator's real exit status survives the deadline wrapper\n");
        CHECK(took < 2000UL, "a fast actuator is not held until its deadline expires\n");
        CHECK(elapsed < 5000u, "a fast actuator reports an unconsumed remainder to its caller\n");
        CHECK(!child_pending(), "a child that exited inside its deadline is reaped\n");
    }

    // A child that fails inside its deadline is still distinguishable from one that timed out only by
    // its status, so the escalation above must not read "nonzero" as "the deadline expired".
    {
        const char *const argv[] = { "sleep", NULL };   // missing operand: exits nonzero, immediately
        unsigned elapsed = 0;
        int status = sysexec_run_argv_deadline(sleep_path, argv, 1, 5000u, &elapsed);
        CHECK(status != 0, "a failing actuator reports a nonzero status\n");
        CHECK(elapsed < 5000u, "a failing actuator returns without consuming its window\n");
        CHECK(!child_pending(), "a failing child is reaped\n");
    }

    // Spawn failure must not be reported as a consumed window, or the policy above would sleep out a
    // remainder for a mechanism that never ran.
    {
        const char *const argv[] = { "nope", NULL };
        unsigned elapsed = 99u;
        unsigned long began = now_ms();
        int status = sysexec_run_argv_deadline("/nonexistent/actuator", argv, 1, 5000u, &elapsed);
        CHECK(status != 0, "a missing actuator does not report success\n");
        CHECK(elapsed < 5000u, "a missing actuator leaves the unused deadline budget available\n");
        CHECK(now_ms() - began < 2000UL, "a missing actuator returns before its deadline\n");
        CHECK(!child_pending(), "a missing actuator leaves no child behind\n");
    }

    printf(failures ? "bounded-exec FAILED (%d)\n" : "bounded-exec ok (%d)\n", failures);
    return failures ? 1 : 0;
}
