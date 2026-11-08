// Production sysexec: real exec / pipe / thread / reboot. See sysexec.h for why this is isolated.
#include "sysexec.h"

#include <errno.h>
#include <fcntl.h>
#include <pthread.h>
#include <poll.h>
#include <signal.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <sys/prctl.h>
#include <sys/wait.h>
#include <time.h>
#include <unistd.h>

/* The lifecycle parent forks C executors after Guard work, so no delayed reap
 * may add a thread there. Reserve custody before each fork, including children
 * still owned by their caller, and poll only explicitly deferred children. */
#define OWNER_CHILD_LIMIT 8
static pid_t lifecycle_owner;
static struct { pid_t pid; int deferred; } owner_children[OWNER_CHILD_LIMIT];

void sysexec_set_lifecycle_owner(int enabled) {
    pid_t owner = enabled ? getpid() : 0;
    if (lifecycle_owner == owner) return;
    lifecycle_owner = owner;
    memset(owner_children, 0, sizeof owner_children);
}
static int in_lifecycle_owner(void) {
    return lifecycle_owner != 0 && lifecycle_owner == getpid();
}
int sysexec_reap_pending(void) {
    if (!in_lifecycle_owner()) return 0;
    int pending = 0;
    for (int i = 0; i < OWNER_CHILD_LIMIT; i++) {
        if (!owner_children[i].deferred) continue;
        pid_t done = waitpid(owner_children[i].pid, NULL, WNOHANG);
        if (done == owner_children[i].pid || (done < 0 && errno == ECHILD)) {
            owner_children[i].pid = 0;
            owner_children[i].deferred = 0;
        } else pending++;
    }
    return pending;
}
static pid_t wait_owned_child(pid_t pid, int *status, int options) {
    pid_t done = waitpid(pid, status, options);
    if (in_lifecycle_owner() && (done == pid || (done < 0 && errno == ECHILD))) {
        for (int i = 0; i < OWNER_CHILD_LIMIT; i++) if (owner_children[i].pid == pid) {
            owner_children[i].pid = 0;
            owner_children[i].deferred = 0;
            break;
        }
    }
    return done;
}
static pid_t fork_owned_child(void) {
    int slot = -1;
    if (in_lifecycle_owner()) {
        (void)sysexec_reap_pending();
        for (int i = 0; i < OWNER_CHILD_LIMIT; i++) if (!owner_children[i].pid) { slot = i; break; }
        if (slot < 0) { errno = EAGAIN; return -1; }
        owner_children[slot].pid = -1;
    }
    pid_t child = fork();
    if (slot >= 0) {
        if (child == 0) sysexec_set_lifecycle_owner(0);
        else owner_children[slot].pid = child > 0 ? child : 0;
    }
    return child;
}
static int defer_owner_child(pid_t pid) {
    for (int i = 0; i < OWNER_CHILD_LIMIT; i++) if (owner_children[i].pid == pid) {
        owner_children[i].deferred = 1;
        return 1;
    }
    /* Only start APIs reserve owner custody. An unknown PID cannot consume a
     * hidden extra slot or cause the fork-owning parent to create a thread. */
    return -1;
}

int sysexec_run_constant(const char *program) {
    if (!program) return -1;
    const char *const argv[] = { "sh", "-c", program, NULL };
    return sysexec_run_argv("/system/bin/sh", argv, 0);
}

// Several /system/bin actuators — pm, am, appops, settings — are shell wrappers whose real body is
// `exec app_process … com.android.commands.…`, and app_process aborts outright without ANDROID_ROOT
// and ANDROID_DATA. A daemon launched from init inherits neither, so every one of those actuators
// died with SIGABRT here while the same command succeeded from a root shell that had them. Measured
// on an Android 8.1 device, 2026-09-20: `env -i PATH=/system/bin:/vendor/bin pm path android`
// returns 134, and adding exactly these two returns 0. Keep the environment otherwise minimal:
// these name the platform's own fixed roots, not anything a caller can influence.
static char *const clean_env[] = {
    "PATH=/system/bin:/vendor/bin",
    "ANDROID_ROOT=/system",
    "ANDROID_DATA=/data",
    "LANG=C",
    "LC_ALL=C",
    NULL
};

// Some vendor builds ship these fixed platform wrappers without a shebang. Raw execve then
// returns ENOEXEC even though an interactive shell runs them. Interpret only that format failure,
// only for these trusted paths, and keep every argument separate (never shell command text).
static void exec_platform_argv(const char *path, const char *const argv[]) {
    execve(path, (char *const *)argv, clean_env);
    if (errno != ENOEXEC ||
        (strcmp(path, "/system/bin/pm") != 0 && strcmp(path, "/system/bin/am") != 0 &&
         strcmp(path, "/system/bin/appops") != 0 && strcmp(path, "/system/bin/settings") != 0)) return;

    // All callers have small fixed vectors. Bound the fallback without allocating after fork.
    const char *shell_argv[66] = { "sh", path };
    size_t i = 1;
    for (; argv[i]; i++) {
        if (i >= 64) { errno = E2BIG; return; }
        shell_argv[i + 1] = argv[i];
    }
    shell_argv[i + 1] = NULL;
    execve("/system/bin/sh", (char *const *)shell_argv, clean_env);
}

static void close_inherited_fds(int keep_a, int keep_b) {
    long limit = sysconf(_SC_OPEN_MAX);
    if (limit < 0 || limit > 65536) limit = 65536;
    for (int fd = STDERR_FILENO + 1; fd < limit; fd++)
        if (fd != keep_a && fd != keep_b) close(fd);
}

static void child_exec_env(const char *path, const char *const argv[], int input, int output, int error, char *const env[]) {
    if (dup2(input, STDIN_FILENO) < 0 || dup2(output, STDOUT_FILENO) < 0 ||
        dup2(error, STDERR_FILENO) < 0) _exit(127);
    close_inherited_fds(-1, -1);
    if (env == clean_env) exec_platform_argv(path, argv);
    else execve(path, (char *const *)argv, env);
    _exit(127);
}

static void child_exec(const char *path, const char *const argv[], int input, int output, int error) {
    child_exec_env(path, argv, input, output, error, clean_env);
}

static int wait_child(pid_t pid) {
    int status;
    while (wait_owned_child(pid, &status, 0) < 0) {
        if (errno != EINTR) return -1;
    }
    return status;
}

static pid_t spawn_argv(const char *path, const char *const argv[], int quiet, int parent_death) {
    if (!path || path[0] != '/' || !argv || !argv[0]) return -1;

    int null_fd = -1;
    if (quiet) {
        null_fd = open("/dev/null", O_RDWR | O_CLOEXEC);
        if (null_fd < 0) return -1;
    }

    pid_t parent = getpid();
    pid_t pid = fork_owned_child();
    if (pid < 0) {
        if (null_fd >= 0) close(null_fd);
        return -1;
    }
    if (pid == 0) {
        if (parent_death &&
            (prctl(PR_SET_PDEATHSIG, SIGKILL) != 0 || getppid() != parent)) _exit(127);
        // Own process group, so that killing an expired actuator kills everything it forked: several
        // /system/bin actuators are wrappers that spawn their real worker, and signalling only the
        // direct child would leave that worker running unowned past its deadline.
        (void)setpgid(0, 0);
        if (quiet) child_exec(path, argv, null_fd, null_fd, null_fd);
        close_inherited_fds(-1, -1);
        exec_platform_argv(path, argv);
        _exit(127);
    }

    // Both sides create the group so neither can observe a window where it does not exist yet: this
    // one fails harmlessly (EACCES) once the child has passed exec, by which point the child's own
    // setpgid has long since succeeded.
    (void)setpgid(pid, pid);
    if (null_fd >= 0) close(null_fd);
    return pid;
}

int sysexec_run_argv(const char *path, const char *const argv[], int quiet) {
    pid_t pid = spawn_argv(path, argv, quiet, 0);
    if (pid < 0) return -1;
    return wait_child(pid);
}

// Poll rather than install a SIGCHLD handler: this daemon is thread-per-connection, and a process-wide
// handler to bound one actuator would reach every other thread's children.
#define WAIT_POLL_MS 20u
// After SIGKILL the child normally becomes reapable within milliseconds, so a short inline grace
// completes the common case without spawning anything. A child wedged in uninterruptible sleep can
// outlast any grace, and it must neither become a second unbounded wait on the connection thread nor
// be abandoned as a permanent zombie. The lifecycle parent tracks it for later nonblocking ticks;
// an ordinary socket worker hands it to a detached reaper until the kernel releases the child.
#define REAP_GRACE_MS 500u

static void *reap_child_thread(void *arg) {
    pid_t pid = (pid_t)(intptr_t)arg;
    int status;
    while (wait_owned_child(pid, &status, 0) < 0 && errno == EINTR) continue;
    return NULL;
}

// Wait up to [deadline_ms] for [pid]. Returns its wait status, or -1 if the deadline passed first.
// [waited_out] always receives the elapsed portion of the deadline, including on the -1 paths.
static int wait_child_deadline(pid_t pid, unsigned deadline_ms, unsigned *waited_out) {
    unsigned waited = 0;
    for (;;) {
        int status;
        pid_t done = wait_owned_child(pid, &status, WNOHANG);
        if (done == pid) {
            *waited_out = waited;
            return status;
        }
        if (done < 0 && errno != EINTR) {
            *waited_out = waited;
            return -1;
        }
        if (waited >= deadline_ms) {
            *waited_out = waited;
            return -1;
        }
        unsigned remaining = deadline_ms - waited;
        unsigned slice = remaining < WAIT_POLL_MS ? remaining : WAIT_POLL_MS;
        sysexec_sleep_ms(slice);
        waited += slice;
    }
}

static int signal_process_group(pid_t pid) {
    if (kill(-pid, SIGKILL) == 0) return 0;
    // The child creates its own group before exec and the parent races to create the same group. A
    // direct-child fallback retains the prior fail-safe for a kernel that reports the group missing.
    if (kill(pid, SIGKILL) == 0 || errno == ESRCH) return 0;
    return -1;
}

// Reap for at most the RC1 inline grace. The caller receives the exact wait status only when this
// thread performed the reap; 1 means deferred custody owns the eventual reap, and -1 means wait
// ownership was lost. This keeps every post-SIGKILL path bounded without inventing a wait status.
static int reap_child_bounded(pid_t pid, int *status) {
    unsigned waited = 0;
    int result = wait_child_deadline(pid, REAP_GRACE_MS, &waited);
    if (result >= 0) {
        if (status) *status = result;
        return 0;
    }
    if (waited < REAP_GRACE_MS) return -1;
    if (in_lifecycle_owner()) return defer_owner_child(pid);
    return sysexec_spawn(reap_child_thread, (void *)(intptr_t)pid) == 0 ? 1 : -1;
}

static int terminate_child_bounded(pid_t pid, int *status) {
    if (signal_process_group(pid) != 0) return -1;
    return reap_child_bounded(pid, status);
}

int sysexec_run_argv_deadline(const char *path, const char *const argv[], int quiet,
                              unsigned deadline_ms, unsigned *elapsed_ms) {
    unsigned elapsed = 0;
    pid_t pid = spawn_argv(path, argv, quiet, 0);
    if (pid < 0) {
        if (elapsed_ms) *elapsed_ms = 0;
        return -1;
    }
    int status = wait_child_deadline(pid, deadline_ms, &elapsed);
    if (status < 0 && elapsed >= deadline_ms) {
        // Kill the whole process group, not just the direct child: a wrapper's forked worker would
        // otherwise keep acting past the deadline, unowned. The direct kill is the fallback for the
        // (should-be-impossible) case where the group was never created.
        (void)signal_process_group(pid);
        unsigned reaped = 0;
        int reap_status = wait_child_deadline(pid, REAP_GRACE_MS, &reaped);
        if (reap_status < 0 && reaped >= REAP_GRACE_MS) {
            if (in_lifecycle_owner()) (void)defer_owner_child(pid);
            else (void)sysexec_spawn(reap_child_thread, (void *)(intptr_t)pid);
        }
        elapsed += reaped;
    }
    if (elapsed_ms) *elapsed_ms = elapsed;
    return status;
}

static int monotonic_millis(unsigned long long *value) {
    struct timespec now;
    if (clock_gettime(CLOCK_MONOTONIC, &now) != 0 || now.tv_sec < 0) return -1;
    unsigned long long seconds = (unsigned long long)now.tv_sec;
    if (seconds > (~0ULL) / 1000ULL) return -1;
    *value = seconds * 1000ULL + (unsigned long long)now.tv_nsec / 1000000ULL;
    return 0;
}

int sysexec_run_argv_timeout(const char *path, const char *const argv[], int quiet,
                             unsigned timeout_ms, int *timed_out) {
    if (timed_out) *timed_out = 0;
    if (!path || path[0] != '/' || !argv || !argv[0] || timeout_ms == 0) return -1;
    pid_t pid = spawn_argv(path, argv, quiet, 1);
    if (pid < 0) return -1;
    unsigned long long start;
    if (monotonic_millis(&start) != 0 || start > (~0ULL) - timeout_ms) {
        (void)terminate_child_bounded(pid, NULL);
        return -1;
    }
    const unsigned long long deadline = start + timeout_ms;
    for (;;) {
        int status;
        pid_t waited = wait_owned_child(pid, &status, WNOHANG);
        if (waited == pid) return status;
        if (waited < 0 && errno != EINTR) return -1;
        unsigned long long now;
        if (monotonic_millis(&now) != 0) {
            (void)terminate_child_bounded(pid, NULL);
            return -1;
        }
        if (now >= deadline) {
            (void)terminate_child_bounded(pid, NULL);
            if (timed_out) *timed_out = 1;
            return -2;
        }
        unsigned long long remaining = deadline - now;
        unsigned long long sleep_ms = remaining < 20ULL ? remaining : 20ULL;
        struct timespec pause = {
            .tv_sec = (time_t)(sleep_ms / 1000ULL),
            .tv_nsec = (long)(sleep_ms % 1000ULL) * 1000000L,
        };
        while (nanosleep(&pause, &pause) != 0 && errno == EINTR) { }
    }
}

int sysexec_start_argv(const char *path, const char *const argv[], int quiet, pid_t *pid_out) {
    if (!path || path[0] != '/' || !argv || !argv[0] || !pid_out) return -1;
    int errors[2];
    if (pipe(errors) != 0) return -1;
    (void)fcntl(errors[0], F_SETFD, FD_CLOEXEC);
    (void)fcntl(errors[1], F_SETFD, FD_CLOEXEC);
    int null_fd = quiet ? open("/dev/null", O_RDWR | O_CLOEXEC) : -1;
    if (quiet && null_fd < 0) { close(errors[0]); close(errors[1]); return -1; }
    pid_t parent = getpid();
    pid_t pid = fork_owned_child();
    if (pid < 0) {
        if (null_fd >= 0) close(null_fd);
        close(errors[0]); close(errors[1]);
        return -1;
    }
    if (pid == 0) {
        close(errors[0]);
        int failure = 0;
        if (prctl(PR_SET_PDEATHSIG, SIGKILL) != 0 || getppid() != parent ||
            setpgid(0, 0) != 0) failure = errno ? errno : EIO;
        if (!failure && quiet &&
            (dup2(null_fd, STDIN_FILENO) < 0 || dup2(null_fd, STDOUT_FILENO) < 0 ||
             dup2(null_fd, STDERR_FILENO) < 0)) failure = errno ? errno : EIO;
        if (!failure) {
            close_inherited_fds(errors[1], -1);
            exec_platform_argv(path, argv);
            failure = errno ? errno : EIO;
        }
        const unsigned char *error_bytes = (const unsigned char *)&failure;
        size_t error_used = 0;
        while (error_used < sizeof failure) {
            ssize_t written = write(errors[1], error_bytes + error_used,
                                    sizeof failure - error_used);
            if (written < 0 && errno == EINTR) continue;
            if (written <= 0) break;
            error_used += (size_t)written;
        }
        _exit(127);
    }
    (void)setpgid(pid, pid);
    if (null_fd >= 0) close(null_fd);
    close(errors[1]);
    int child_error = 0;
    ssize_t count;
    do count = read(errors[0], &child_error, sizeof child_error); while (count < 0 && errno == EINTR);
    close(errors[0]);
    if (count != 0) {
        (void)wait_child(pid);
        return -1;
    }
    *pid_out = pid;
    return 0;
}

int sysexec_start_stdout_argv(const char *path, const char *const argv[], pid_t *pid_out,
                              int *stdout_fd) {
    if (!path || path[0] != '/' || !argv || !argv[0] || !pid_out || !stdout_fd) return -1;
    int output[2], errors[2];
    if (pipe(output) != 0) return -1;
    if (pipe(errors) != 0) { close(output[0]); close(output[1]); return -1; }
    (void)fcntl(output[0], F_SETFD, FD_CLOEXEC);
    (void)fcntl(output[1], F_SETFD, FD_CLOEXEC);
    (void)fcntl(errors[0], F_SETFD, FD_CLOEXEC);
    (void)fcntl(errors[1], F_SETFD, FD_CLOEXEC);
    int null_fd = open("/dev/null", O_RDWR | O_CLOEXEC);
    if (null_fd < 0) {
        close(output[0]); close(output[1]); close(errors[0]); close(errors[1]);
        return -1;
    }
    pid_t parent = getpid();
    pid_t pid = fork_owned_child();
    if (pid < 0) {
        close(null_fd); close(output[0]); close(output[1]); close(errors[0]); close(errors[1]);
        return -1;
    }
    if (pid == 0) {
        close(output[0]); close(errors[0]);
        int failure = 0;
        if (prctl(PR_SET_PDEATHSIG, SIGKILL) != 0 || getppid() != parent ||
            setpgid(0, 0) != 0 || dup2(null_fd, STDIN_FILENO) < 0 ||
            dup2(output[1], STDOUT_FILENO) < 0 || dup2(null_fd, STDERR_FILENO) < 0)
            failure = errno ? errno : EIO;
        if (!failure) {
            close_inherited_fds(errors[1], -1);
            exec_platform_argv(path, argv);
            failure = errno ? errno : EIO;
        }
        const unsigned char *bytes = (const unsigned char *)&failure;
        size_t used = 0;
        while (used < sizeof failure) {
            ssize_t written = write(errors[1], bytes + used, sizeof failure - used);
            if (written < 0 && errno == EINTR) continue;
            if (written <= 0) break;
            used += (size_t)written;
        }
        _exit(127);
    }
    (void)setpgid(pid, pid);
    close(null_fd); close(output[1]); close(errors[1]);
    int child_error = 0;
    ssize_t count;
    do count = read(errors[0], &child_error, sizeof child_error); while (count < 0 && errno == EINTR);
    close(errors[0]);
    if (count != 0) {
        close(output[0]);
        (void)wait_child(pid);
        return -1;
    }
    *pid_out = pid;
    *stdout_fd = output[0];
    return 0;
}

int sysexec_poll_argv(pid_t pid, int *status) {
    if (pid <= 1 || !status) return -1;
    pid_t result;
    do result = wait_owned_child(pid, status, WNOHANG); while (result < 0 && errno == EINTR);
    return result == 0 ? 0 : (result == pid ? 1 : -1);
}

int sysexec_terminate_argv(pid_t pid, int *status) {
    if (pid <= 1 || !status) return -1;
    // Preserve the API's exact-status contract: deferred custody safely owns the reap but returns
    // -1 because this caller cannot truthfully supply the eventual raw wait status.
    return terminate_child_bounded(pid, status) == 0 ? 0 : -1;
}

static int pipe_argv(const char *path, const char *const argv[], int output_fd,
                     char *capture, size_t capacity) {
    if (!path || path[0] != '/' || !argv || !argv[0] || (capture && capacity == 0)) return -1;
    int pipefd[2];
    if (pipe(pipefd) != 0) return -1;
    (void)fcntl(pipefd[0], F_SETFD, FD_CLOEXEC);
    (void)fcntl(pipefd[1], F_SETFD, FD_CLOEXEC);
    int null_fd = open("/dev/null", O_RDWR | O_CLOEXEC);
    if (null_fd < 0) { close(pipefd[0]); close(pipefd[1]); return -1; }
    pid_t pid = fork_owned_child();
    if (pid < 0) { close(null_fd); close(pipefd[0]); close(pipefd[1]); return -1; }
    if (pid == 0) {
        close(pipefd[0]);
        child_exec(path, argv, null_fd, pipefd[1], null_fd);
    }
    close(null_fd);
    close(pipefd[1]);
    size_t used = 0;
    int io_ok = 1;
    char buf[8192];
    for (;;) {
        ssize_t n = read(pipefd[0], buf, sizeof buf);
        if (n < 0 && errno == EINTR) continue;
        if (n < 0) { io_ok = 0; break; }
        if (n == 0) break;
        if (capture) {
            size_t available = capacity - 1 - used;
            size_t take = (size_t)n < available ? (size_t)n : available;
            if (take) memcpy(capture + used, buf, take);
            used += take;
            if (take != (size_t)n) io_ok = 0;
        } else {
            size_t offset = 0;
            while (offset < (size_t)n) {
                ssize_t written = write(output_fd, buf + offset, (size_t)n - offset);
                if (written < 0 && errno == EINTR) continue;
                if (written <= 0) { io_ok = 0; break; }
                offset += (size_t)written;
            }
        }
        if (!io_ok) break;
    }
    close(pipefd[0]);
    if (capture) capture[used] = '\0';
    if (!io_ok) {
        kill(pid, SIGKILL);
        (void)wait_child(pid);
        return -1;
    }
    return wait_child(pid);
}

int sysexec_capture_argv(const char *path, const char *const argv[], char *output, size_t capacity) {
    if (!output || capacity == 0) return -1;
    output[0] = '\0';
    return pipe_argv(path, argv, -1, output, capacity);
}

static int capture_argv_io(const char *path, const char *const argv[], char *output,
                           size_t capacity, unsigned timeout_ms, int *timed_out,
                           char *const env[], size_t *output_size, int stdin_fd, int merge_stderr);

static int capture_argv_timeout_env(const char *path, const char *const argv[], char *output,
                                 size_t capacity, unsigned timeout_ms, int *timed_out,
                                 char *const env[], size_t *output_size) {
    return capture_argv_io(path, argv, output, capacity, timeout_ms, timed_out, env, output_size, -1, 0);
}

int sysexec_capture_argv_io(const char *path, const char *const argv[], char *output,
                            size_t capacity, unsigned timeout_ms, int *timed_out,
                            int stdin_fd, int merge_stderr) {
    return capture_argv_io(path, argv, output, capacity, timeout_ms, timed_out, clean_env, NULL,
                           stdin_fd, merge_stderr);
}

static int capture_argv_io(const char *path, const char *const argv[], char *output,
                           size_t capacity, unsigned timeout_ms, int *timed_out,
                           char *const env[], size_t *output_size, int stdin_fd, int merge_stderr) {
    if (output_size) *output_size = 0;
    if (timed_out) *timed_out = 0;
    if (!path || path[0] != '/' || !argv || !argv[0] || !output || capacity == 0 ||
        timeout_ms == 0) return -1;
    output[0] = '\0';
    int pipefd[2];
    if (pipe(pipefd) != 0) return -1;
    (void)fcntl(pipefd[0], F_SETFD, FD_CLOEXEC);
    (void)fcntl(pipefd[1], F_SETFD, FD_CLOEXEC);
    int null_fd = open("/dev/null", O_RDWR | O_CLOEXEC);
    if (null_fd < 0) { close(pipefd[0]); close(pipefd[1]); return -1; }
    pid_t parent = getpid();
    pid_t pid = fork_owned_child();
    if (pid < 0) {
        close(null_fd); close(pipefd[0]); close(pipefd[1]);
        return -1;
    }
    if (pid == 0) {
        close(pipefd[0]);
        if (prctl(PR_SET_PDEATHSIG, SIGKILL) != 0 || getppid() != parent ||
            setpgid(0, 0) != 0) _exit(127);
        child_exec_env(path, argv, stdin_fd >= 0 ? stdin_fd : null_fd, pipefd[1],
                       merge_stderr ? pipefd[1] : null_fd, env);
    }
    (void)setpgid(pid, pid);
    close(null_fd);
    close(pipefd[1]);
    int flags = fcntl(pipefd[0], F_GETFL, 0);
    if (flags < 0 || fcntl(pipefd[0], F_SETFL, flags | O_NONBLOCK) != 0) {
        (void)terminate_child_bounded(pid, NULL); close(pipefd[0]); return -1;
    }
    unsigned long long start;
    if (monotonic_millis(&start) != 0 || start > (~0ULL) - timeout_ms) {
        (void)terminate_child_bounded(pid, NULL); close(pipefd[0]); return -1;
    }
    unsigned long long deadline = start + timeout_ms;
    size_t used = 0;
    int status = 0, reaped = 0, eof = 0, failed = 0;
    while (!reaped || !eof) {
        for (;;) {
            if (used == capacity - 1) {
                unsigned char extra;
                ssize_t more = read(pipefd[0], &extra, 1);
                if (more > 0) failed = 1;
                else if (more == 0) eof = 1;
                else if (errno != EAGAIN && errno != EWOULDBLOCK && errno != EINTR) failed = 1;
                break;
            }
            ssize_t count = read(pipefd[0], output + used, capacity - 1 - used);
            if (count > 0) {
                used += (size_t)count;
                continue;
            }
            if (count == 0) eof = 1;
            else if (errno != EAGAIN && errno != EWOULDBLOCK && errno != EINTR) failed = 1;
            break;
        }
        if (!reaped) {
            pid_t waited = wait_owned_child(pid, &status, WNOHANG);
            if (waited == pid) reaped = 1;
            else if (waited < 0 && errno != EINTR) failed = 1;
        }
        if (failed) break;
        if (reaped && eof) break;
        unsigned long long now;
        if (monotonic_millis(&now) != 0) { failed = 1; break; }
        if (now >= deadline) {
            if (reaped) (void)signal_process_group(pid);
            else (void)terminate_child_bounded(pid, NULL);
            close(pipefd[0]);
            output[used] = '\0';
            if (timed_out) *timed_out = 1;
            return -2;
        }
        unsigned long long remaining = deadline - now;
        int wait_ms = (int)(remaining < 20ULL ? remaining : 20ULL);
        struct pollfd event = { .fd = pipefd[0], .events = POLLIN | POLLHUP };
        int polled;
        do polled = poll(&event, 1, wait_ms); while (polled < 0 && errno == EINTR);
        if (polled < 0) { failed = 1; break; }
    }
    if (failed) {
        if (reaped) (void)signal_process_group(pid);
        else (void)terminate_child_bounded(pid, NULL);
        close(pipefd[0]);
        output[used] = '\0';
        return -1;
    }
    close(pipefd[0]);
    output[used] = '\0';
    if (output_size) *output_size = used;
    return status;
}

int sysexec_capture_argv_timeout(const char *path, const char *const argv[], char *output,
                                 size_t capacity, unsigned timeout_ms, int *timed_out) {
    return capture_argv_timeout_env(path, argv, output, capacity, timeout_ms, timed_out, clean_env, NULL);
}

int sysexec_capture_zigbee_role(char *output, size_t capacity, size_t *used) {
    // No generic environment input: only this fixed vendor client receives its fixed library root.
    static char *const role_env[] = {
        "PATH=/system/bin:/vendor/bin", "ANDROID_ROOT=/system", "ANDROID_DATA=/data",
        "LANG=C", "LC_ALL=C", "LD_LIBRARY_PATH=/vendor/bin/siliconlabs_host", NULL
    };
    const char *const argv[] = { "/vendor/bin/siliconlabs_host/mosquitto_sub", "-h", "127.0.0.1",
        "-p", "1883", "-i", "hapaneld_zr", "-t", "zigbee/system/network-role/information",
        "-C", "1", "-W", "3", NULL };
    return capture_argv_timeout_env(argv[0], argv, output, capacity, 3500u, NULL, role_env, used);
}

int sysexec_capture_gfxinfo(const char *package, char *output, size_t capacity, size_t *used) {
    if (used) *used = 0;
    if (!package || !*package || !used) return -1;
    // Package comes only from the authenticated SELF handler's fixed identity table.
    const char *const argv[] = { "/system/bin/dumpsys", "gfxinfo", package, NULL };
    return capture_argv_timeout_env(argv[0], argv, output, capacity, 2000u, NULL, clean_env, used);
}

int sysexec_capture_wifi_status(char *output, size_t capacity, size_t *used) {
    if (!used) return -1;
    *used = 0;
    unsigned long long start, now;
    if (monotonic_millis(&start) != 0) return -1;
    const char *const primary[] = { "/system/bin/cmd", "wifi", "status", NULL };
    int status = capture_argv_timeout_env(primary[0], primary, output, capacity, 2000u,
                                          NULL, clean_env, used);
    if (status >= 0 && memchr(output, 0, *used)) return -1;
    if (status <= 0 || !WIFEXITED(status) || WEXITSTATUS(status) == 0) return status;
    if (monotonic_millis(&now) != 0 || now < start || now - start >= 2000u) return -2;
    const char *const fallback[] = { "/system/bin/dumpsys", "wifi", NULL };
    return capture_argv_timeout_env(fallback[0], fallback, output, capacity,
                                    2000u - (unsigned)(now - start), NULL, clean_env, used);
}

void sysexec_capture_adb_properties(struct adb_property_capture *capture) {
    if (!capture) return;
    memset(capture, 0, sizeof *capture);
    for (size_t i = 0; i < ADB_PROPERTY_COUNT; i++) capture->status[i] = -1;
    static const char *const names[ADB_PROPERTY_COUNT] = {
        "service.adb.listen_addrs", "service.adb.tcp.port", "persist.adb.tcp.port",
        "service.adb.tls.port", "persist.adb.tls_server.enable"
    };
    unsigned long long start, now;
    if (monotonic_millis(&start) != 0) return;
    for (size_t i = 0; i < ADB_PROPERTY_COUNT; i++) {
        if (monotonic_millis(&now) != 0 || now < start || now - start >= 2000u) break;
        const char *const argv[] = { "/system/bin/getprop", names[i], NULL };
        capture->status[i] = capture_argv_timeout_env(argv[0], argv, capture->output[i],
            sizeof capture->output[i], 2000u - (unsigned)(now - start), NULL, clean_env,
            &capture->used[i]);
        if (capture->status[i] == -2) break;
    }
}

int sysexec_stream_argv(const char *path, const char *const argv[], int output_fd) {
    if (output_fd < 0) return -1;
    return pipe_argv(path, argv, output_fd, NULL, 0);
}

int sysexec_spawn(void *(*fn)(void *), void *arg) {
    if (in_lifecycle_owner()) { errno = EAGAIN; return -1; }
    pthread_t t;
    if (pthread_create(&t, NULL, fn, arg) != 0) return -1;
    pthread_detach(t);
    return 0;
}

void sysexec_sleep_ms(unsigned ms) {
    struct timespec remaining = {
        .tv_sec = (time_t)(ms / 1000u),
        .tv_nsec = (long)(ms % 1000u) * 1000000L,
    };
    // A signal must not shorten a bounded wait: nanosleep reports the unslept remainder, so resume
    // from it rather than returning early with the interval only partly elapsed.
    while (nanosleep(&remaining, &remaining) != 0 && errno == EINTR) continue;
}
