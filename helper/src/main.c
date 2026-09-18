// hapaneld-helper — a tiny root helper that exposes a whitelisted control surface to ha-paneld over an
// authenticated UNIX socket, for capabilities a sandboxed Android app can't reach itself: the RGB LED
// (root-only sysfs or an app-denied ioctl), screen-backlight power, hardware-button/GPIO instrumentation,
// display density / CPU governor / screencap / perf snapshots, and app reload/start/reboot.
//
// It began as an LED-only helper (the former `hapaneld-ledd` name) but is now the general control
// path for sandbox-walled panels, and was renamed `hapaneld-helper` to match. Its code is split by
// capability under helper/src/ — see helper/README.md. (Migration: a freshly installed daemon must
// tear down any old `hapaneld-ledd` binary + init service — install-daemon.sh does this.)
//
// Security model (this file owns the transport + auth; see each module for its own validation):
//   * Transport is an ABSTRACT-namespace UNIX socket (SOCK_NAME), so peer credentials are available
//     via SO_PEERCRED. Every connection is authenticated: we accept ONLY a uid that owns one of the
//     two known ha-paneld package data directories (resolved at runtime from /data/data/<pkg>, which
//     changes per reinstall), plus root. ADB operators can still connect through a root shell;
//     accepting Android's generic shell uid would let any app with Shizuku access cross the
//     documented shell-only boundary into this root daemon.
//   * Concurrent connections are capped (MAX_CONN) and idle non-subscribers time out (server.c), so a
//     connection flood can't exhaust the thread-per-conn model.
//   * The command parser is bounded and exact-match (dispatch.c / server.c); every verb's arguments
//     are width-bounded and validated. Request values reach only structural argv/direct operations;
//     the sole fixed shell program has no caller-controlled bytes.
//
// Protocol: see helper/README.md and the dispatch table in dispatch.c.

#define _GNU_SOURCE             // struct ucred / SO_PEERCRED
#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <pthread.h>
#include <stddef.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <signal.h>
#include <poll.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/prctl.h>
#include <sys/un.h>
#include <sys/wait.h>
#include <time.h>
#include <unistd.h>

#include "led.h"
#include "screen.h"
#include "input.h"
#include "gpio.h"
#include "guard_maintenance.h"
#include "identity.h"
#include "server.h"
#include "sysexec.h"
#include "version.h"

// Abstract-namespace UNIX socket name (leading NUL added at bind time). Must match the app's
// LocalSocketAddress("hapaneld-helper", ABSTRACT) in HelperClient/EvdevButtonClient.
#ifndef SOCK_NAME
#define SOCK_NAME  "hapaneld-helper"
#endif
#ifndef REQUEST_TIMEOUT_MS
#define REQUEST_TIMEOUT_MS 3000
#endif
// The package data directories are stat()ed to learn each app's uid (it changes on every reinstall).
// Their paths, and the ids they belong to, live in identity.h — the single app-identity table.
#ifndef REPLACEMENT_RETIRE_ATTEMPTS
#define REPLACEMENT_RETIRE_ATTEMPTS 20
#endif
#ifndef REPLACEMENT_RETIRE_DELAY_MS
#define REPLACEMENT_RETIRE_DELAY_MS 50
#endif
#ifdef HAPANELD_TEST
#define APP_HELPER_LIVE "/tmp/.hapaneld-helper-live-test"
#define APP_HELPER_STAGE "/tmp/.hapaneld-helper-live-test.new"
#else
#define APP_HELPER_LIVE "/data/local/hapaneld-helper"
#define APP_HELPER_STAGE "/data/local/.hapaneld-helper.new"
#endif
// MAX_CONN (the concurrent-connection cap) + the conn_admit/release/active gate live in server.[ch]
// so the cap is unit-testable without this accept loop.

// --- peer authentication --------------------------------------------------------------------------
// An app's uid changes on every (re)install, so resolve it live by stat'ing its data dir rather than
// hardcoding it. Resolve the directories on every connection and fail closed when one is absent: this
// daemon can outlive an app uninstall, and Android may later reuse the former numeric app uid.
//
// During the identity migration the legacy package and its successor are installed side by side with
// no `sharedUserId`, so they hold two distinct uids and each owns only its own data directory. A
// caller is authorised when it owns EITHER directory, and is identified as whichever one it owns —
// any other uid is refused exactly as before. On a single-package panel only one directory exists,
// so the absent one never matches and the outcome is unchanged.

static enum helper_caller resolve_caller(uid_t uid) {
    if (uid == 0) return HELPER_CALLER_ROOT;
    for (size_t i = 0; i < HELPER_APP_PACKAGE_COUNT; i++) {
        struct stat st;
        if (stat(HELPER_APP_PACKAGES[i].data_dir, &st) != 0) continue;  // absent pre-install/after uninstall
        if (uid == st.st_uid) return HELPER_APP_PACKAGES[i].caller;
    }
    return HELPER_CALLER_NONE;
}

static int set_cloexec(int fd) {
    int flags = fcntl(fd, F_GETFD, 0);
    return flags >= 0 && fcntl(fd, F_SETFD, flags | FD_CLOEXEC) == 0 ? 0 : -1;
}

// An in-place upgrade replaces the helper inode before launching this process. Some Android root
// environments return from pkill while the previous daemon still owns the abstract socket, so a
// direct or init launch can otherwise lose the race and leave the old BUILDID observable. On an
// occupied socket, retire only helpers executing a different inode. A concurrent launch of this
// exact replacement is left alone and will continue serving the expected identity.
static int replaced_helper_processes(int signal_number) {
    struct stat self_executable;
    if (stat("/proc/self/exe", &self_executable) != 0) return -1;

    DIR *proc = opendir("/proc");
    if (!proc) return -1;
    int found = 0;
    struct dirent *entry;
    while ((entry = readdir(proc)) != NULL) {
        char *end = NULL;
        long parsed = strtol(entry->d_name, &end, 10);
        if (!entry->d_name[0] || !end || *end != '\0' || parsed <= 0 || parsed > INT_MAX ||
                (pid_t)parsed == getpid()) {
            continue;
        }

        char path[64];
        if (snprintf(path, sizeof path, "/proc/%ld/comm", parsed) >= (int)sizeof path) continue;
        FILE *comm_file = fopen(path, "r");
        if (!comm_file) continue;
        char comm[32];
        char *read_result = fgets(comm, sizeof comm, comm_file);
        fclose(comm_file);
        if (!read_result) continue;
        comm[strcspn(comm, "\r\n")] = '\0';
        if (strcmp(comm, "hapaneld-helper") != 0) continue;

        if (snprintf(path, sizeof path, "/proc/%ld/exe", parsed) >= (int)sizeof path) continue;
        struct stat executable;
        if (stat(path, &executable) != 0) continue;
        if (executable.st_dev == self_executable.st_dev && executable.st_ino == self_executable.st_ino) {
            continue;
        }
        found++;
        if (signal_number != 0 && kill((pid_t)parsed, signal_number) != 0 && errno != ESRCH) {
            closedir(proc);
            return -1;
        }
    }
    closedir(proc);
    return found;
}

static int retire_replaced_helper(void) {
    int found = replaced_helper_processes(SIGTERM);
    if (found <= 0) return found;

    const struct timespec delay = {
        .tv_sec = REPLACEMENT_RETIRE_DELAY_MS / 1000,
        .tv_nsec = (REPLACEMENT_RETIRE_DELAY_MS % 1000) * 1000000L,
    };
    for (int attempt = 0; attempt < REPLACEMENT_RETIRE_ATTEMPTS; attempt++) {
        nanosleep(&delay, NULL);
        found = replaced_helper_processes(0);
        if (found <= 0) return found;
    }

    found = replaced_helper_processes(SIGKILL);
    if (found < 0) return -1;
    for (int attempt = 0; attempt < REPLACEMENT_RETIRE_ATTEMPTS; attempt++) {
        nanosleep(&delay, NULL);
        found = replaced_helper_processes(0);
        if (found <= 0) return found;
    }
    return -1;
}

static int bind_helper_socket(
        int fd,
        const struct sockaddr *address,
        socklen_t length,
        int may_retire_replaced) {
    if (bind(fd, address, length) == 0) return 0;
    if (errno != EADDRINUSE || !may_retire_replaced) return -1;

    int retired = retire_replaced_helper();
    if (retired < 0) {
        errno = EADDRINUSE;
        return -1;
    }

    const struct timespec delay = {
        .tv_sec = REPLACEMENT_RETIRE_DELAY_MS / 1000,
        .tv_nsec = (REPLACEMENT_RETIRE_DELAY_MS % 1000) * 1000000L,
    };
    for (int attempt = 0; attempt < REPLACEMENT_RETIRE_ATTEMPTS; attempt++) {
        if (bind(fd, address, length) == 0) return 0;
        if (errno != EADDRINUSE) return -1;
        nanosleep(&delay, NULL);
    }
    errno = EADDRINUSE;
    return -1;
}

// Installer/provisioner probe mode. It is intentionally restricted to read-only identity verbs and
// is meant to be invoked through the panel's root path, so the daemon still authenticates uid 0 via
// SO_PEERCRED. Never broaden the daemon policy to Android's generic shell uid for adb forwarding.
static int probe_command_allowed(const char *command) {
    return strcmp(command, "PING") == 0 ||
           strcmp(command, "COMPANIONCAPS") == 0 ||
           strcmp(command, "BUILDID") == 0 ||
           strcmp(command, "GUARDCAPS") == 0 ||
           strcmp(command, "GUARDSELF") == 0 ||
           strcmp(command, "GUARDSTATUS") == 0;
}

static long long monotonic_millis(void) {
    struct timespec now;
    if (clock_gettime(CLOCK_MONOTONIC, &now) != 0) return -1;
    return (long long)now.tv_sec * 1000LL + now.tv_nsec / 1000000LL;
}

static int wait_for_socket(int fd, short events, long long deadline_ms) {
    for (;;) {
        long long now_ms = monotonic_millis();
        if (now_ms < 0 || now_ms >= deadline_ms) {
            errno = ETIMEDOUT;
            return -1;
        }
        long long remaining_ms = deadline_ms - now_ms;
        int timeout_ms = remaining_ms > INT_MAX ? INT_MAX : (int)remaining_ms;
        struct pollfd pfd = { .fd = fd, .events = events };
        int ready = poll(&pfd, 1, timeout_ms);
        if (ready < 0 && errno == EINTR) continue;
        if (ready <= 0) {
            if (ready == 0) errno = ETIMEDOUT;
            return -1;
        }
        if (pfd.revents & events) return 0;
        errno = (pfd.revents & POLLNVAL) ? EBADF : EIO;
        return -1;
    }
}

static int request_daemon(const char *command) {
    if (!probe_command_allowed(command)) return 2;

    int fd = socket(AF_UNIX, SOCK_STREAM, 0);
    if (fd < 0) return 1;
    if (set_cloexec(fd) != 0) { close(fd); return 1; }
    int flags = fcntl(fd, F_GETFL, 0);
    if (flags < 0 || fcntl(fd, F_SETFL, flags | O_NONBLOCK) < 0) {
        close(fd);
        return 1;
    }
    long long now_ms = monotonic_millis();
    if (now_ms < 0 || now_ms > LLONG_MAX - REQUEST_TIMEOUT_MS) {
        close(fd);
        return 1;
    }
    long long deadline_ms = now_ms + REQUEST_TIMEOUT_MS;

    struct sockaddr_un addr;
    memset(&addr, 0, sizeof addr);
    addr.sun_family = AF_UNIX;
    addr.sun_path[0] = '\0';
    memcpy(addr.sun_path + 1, SOCK_NAME, sizeof SOCK_NAME - 1);
    socklen_t alen = offsetof(struct sockaddr_un, sun_path) + 1 + (sizeof SOCK_NAME - 1);
    if (connect(fd, (struct sockaddr *)&addr, alen) < 0) {
        if ((errno != EINPROGRESS && errno != EINTR && errno != EAGAIN) ||
                wait_for_socket(fd, POLLOUT, deadline_ms) != 0) {
            close(fd);
            return 1;
        }
        int socket_error = 0;
        socklen_t error_len = sizeof socket_error;
        if (getsockopt(fd, SOL_SOCKET, SO_ERROR, &socket_error, &error_len) != 0 ||
                socket_error != 0) {
            if (socket_error != 0) errno = socket_error;
            close(fd);
            return 1;
        }
    }

    char request[64];
    int request_len = snprintf(request, sizeof request, "%s\n", command);
    if (request_len <= 0 || (size_t)request_len >= sizeof request) { close(fd); return 1; }
    size_t sent = 0;
    while (sent < (size_t)request_len) {
        ssize_t n = send(fd, request + sent, (size_t)request_len - sent, MSG_NOSIGNAL);
        if (n < 0 && errno == EINTR) continue;
        if (n < 0 && (errno == EAGAIN || errno == EWOULDBLOCK)) {
            if (wait_for_socket(fd, POLLOUT, deadline_ms) == 0) continue;
            close(fd);
            return 1;
        }
        if (n <= 0) { close(fd); return 1; }
        sent += (size_t)n;
    }

    // Single-line replies share the daemon protocol's MAX_LINE printable-byte bound. Reserve one
    // byte for the wire LF and one for the local NUL so a full Guard status cannot be mistaken for a
    // truncated reply merely because it carries the transaction's three 64-byte identities.
    char reply[MAX_LINE + 2];
    size_t used = 0;
    while (used + 1 < sizeof reply) {
        ssize_t n = read(fd, reply + used, 1);
        if (n < 0 && errno == EINTR) continue;
        if (n < 0 && (errno == EAGAIN || errno == EWOULDBLOCK)) {
            if (wait_for_socket(fd, POLLIN, deadline_ms) == 0) continue;
            close(fd);
            return 1;
        }
        if (n <= 0) break;
        if (reply[used++] == '\n') break;
    }
    close(fd);
    if (used == 0 || reply[used - 1] != '\n') return 1;
    reply[used] = '\0';
    if (fputs(reply, stdout) == EOF || fflush(stdout) != 0) return 1;
    return 0;
}

// One thread per connection, so a long-lived async stream doesn't block other commands. Removes the
// fd from both independent subscriber registries on disconnect. The caller identity travels with the
// fd because it was resolved before admission and must not be re-derived later: an app can be
// reinstalled, and a uid reused, while this connection is still open.
typedef struct {
    int fd;
    enum helper_caller caller;
} conn_handoff;

static void *conn_thread(void *arg) {
    conn_handoff handoff = *(conn_handoff *)arg;
    int cfd = handoff.fd;
    free(arg);
    server_serve(cfd, handoff.caller);
    input_unsubscribe(cfd);
    gpio_unsubscribe(cfd);
    close(cfd);
    conn_release();
    return NULL;
}

static int run_daemon(
        int argc,
        char **argv,
        const char *replacement_nonce,
        int may_retire_replaced) {
    signal(SIGPIPE, SIG_IGN);   // a dead subscriber's socket must not kill the daemon
    if (!replacement_nonce && guard_maintenance_init() != 0)
        fprintf(stderr, "hapaneld-helper guard maintenance recovery is in fail-closed hold\n");
    input_init();
    gpio_init();
    screen_init();
    led_init();

    // Optional startup watches from args (per-device .rc may pass them): --grab/--watch <node>. The
    // app also sets these via the WATCH command, so args are not required.
    for (int i = 1; i < argc - 1; i++) {
        if (strcmp(argv[i], "--grab") == 0) input_watch(argv[++i], 1);
        else if (strcmp(argv[i], "--watch") == 0) input_watch(argv[++i], 0);
    }

    int sfd = socket(AF_UNIX, SOCK_STREAM, 0);
    if (sfd < 0) { perror("socket"); return 1; }
    if (set_cloexec(sfd) != 0) { perror("fcntl"); close(sfd); return 1; }

    // Abstract-namespace UNIX socket: leading NUL in sun_path, name follows. No filesystem entry to
    // create, label (SELinux), permission, or clean up — and it's released automatically on close.
    struct sockaddr_un addr;
    memset(&addr, 0, sizeof addr);
    addr.sun_family = AF_UNIX;
    addr.sun_path[0] = '\0';
    memcpy(addr.sun_path + 1, SOCK_NAME, sizeof SOCK_NAME - 1);
    socklen_t alen = offsetof(struct sockaddr_un, sun_path) + 1 + (sizeof SOCK_NAME - 1);

    if (bind_helper_socket(sfd, (struct sockaddr *)&addr, alen, may_retire_replaced) < 0) {
        perror("bind");
        close(sfd);
        return 1;
    }
    if (listen(sfd, MAX_CONN) < 0) { perror("listen"); return 1; }
    if (replacement_nonce &&
        guard_maintenance_replacement_worker_commit_app(replacement_nonce) != 0) {
        fprintf(stderr, "hapaneld-helper replacement commit is in fail-closed hold\n");
        close(sfd);
        return 1;
    }
    fprintf(stderr, "hapaneld-helper listening on abstract unix socket @%s\n", SOCK_NAME);

    for (;;) {
        int cfd = accept(sfd, NULL, NULL);
        if (cfd < 0) { if (errno == EINTR) continue; perror("accept"); break; }
        if (set_cloexec(cfd) != 0) { close(cfd); continue; }

        // Authenticate the peer by uid — only possible because this is a UNIX socket. Reject (and
        // close) anything that is neither root nor one of the two known packages before it can issue
        // a single command, and keep WHICH one it is: that answer is this connection's identity.
        struct ucred cred; socklen_t cl = sizeof cred;
        enum helper_caller caller = HELPER_CALLER_NONE;
        if (getsockopt(cfd, SOL_SOCKET, SO_PEERCRED, &cred, &cl) < 0 ||
                (caller = resolve_caller(cred.uid)) == HELPER_CALLER_NONE) {
            close(cfd); continue;
        }

        // Cap concurrent connections so a connection flood can't exhaust the thread-per-conn model.
        if (!conn_admit()) { close(cfd); continue; }

        conn_handoff *p = malloc(sizeof *p);
        if (!p) { close(cfd); conn_release(); continue; }
        p->fd = cfd;
        p->caller = caller;
        pthread_t t;
        if (pthread_create(&t, NULL, conn_thread, p) != 0) {
            free(p); close(cfd);
            conn_release();
            continue;
        }
        pthread_detach(t);
    }
    close(sfd);
    return 0;
}

/*
 * Same-session crash supervision for writable-/data panels with no proved init or service.d hook.
 * The app launches this exact mode as root. The parent owns no socket or transaction state; it only
 * forks the worker before threads exist, waits for it, and restarts it after a bounded delay. The
 * internal supervised flag is set only in that fork branch, so invoking the ordinary daemon cannot
 * claim SUPERVISED. This is deliberately not a reboot-persistence claim.
 */
static pid_t spawn_socket_worker(
        char **argv,
        const char *replacement_nonce,
        int may_retire_replaced) {
    pid_t worker = fork();
    if (worker != 0) return worker;
    pid_t supervisor = getppid();
    if (supervisor <= 1 || prctl(PR_SET_PDEATHSIG, SIGTERM) != 0 ||
        getppid() != supervisor) _exit(1);
    guard_maintenance_set_supervised(1);
    _exit(run_daemon(1, argv, replacement_nonce, may_retire_replaced));
}

static long long supervisor_executor_deadline(long long now_ms, uint64_t phase_deadline_ms) {
    if (now_ms < 0) return -1;
    long long command_deadline = now_ms <= LLONG_MAX - GUARD_EXECUTOR_TIMEOUT_MS
        ? now_ms + GUARD_EXECUTOR_TIMEOUT_MS : now_ms;
    return phase_deadline_ms < (uint64_t)command_deadline
        ? (long long)phase_deadline_ms : command_deadline;
}

static int run_supervisor(char **argv, const char *replacement_nonce) {
    if (geteuid() != 0) return 1;
    if (!replacement_nonce && guard_maintenance_init() != 0) return 1;
    guard_maintenance_set_supervisor_owner();
    pid_t worker = -1;
    long long worker_after_ms = 0;
    pid_t executor = -1;
    enum guard_supervisor_work executor_work = GUARD_WORK_NONE;
    long long executor_deadline_ms = 0;
    enum guard_supervisor_work pending_work = GUARD_WORK_NONE;
    for (;;) {
        long long now = monotonic_millis();
        if (now < 0) return 1;
        if (worker <= 1 && now >= worker_after_ms) {
            int may_retire_replaced = replacement_nonce == NULL &&
                guard_maintenance_replacement_safe() == 0;
            worker = spawn_socket_worker(argv, replacement_nonce, may_retire_replaced);
            if (worker < 0) worker_after_ms = now + 1000;
        }
        if (worker > 1) {
            int status = 0;
            pid_t reaped = waitpid(worker, &status, WNOHANG);
            if (reaped == worker) {
                worker = -1;
                if (WIFEXITED(status) && WEXITSTATUS(status) == GUARD_REPLACEMENT_EXIT) {
                    char nonce[65];
                    if (executor > 1 || pending_work != GUARD_WORK_NONE ||
                        guard_maintenance_replacement_parent_grant(nonce) !=
                            GUARD_REPLACEMENT_READY ||
                        guard_maintenance_replacement_export_lease() != 0) {
                        /* A bare exit code is only a worker crash. A valid durable request may not
                         * preempt package work; remain fail-closed under the original lease. */
                        worker_after_ms = now + 1000;
                        continue;
                    }
                    char *const replacement_argv[] = {
                        (char *)"hapaneld-helper", (char *)"--replace-app-lease", nonce, NULL,
                    };
                    char *const replacement_env[] = {
                        (char *)"PATH=/system/bin:/system/xbin", NULL,
                    };
                    execve(APP_HELPER_STAGE, replacement_argv, replacement_env);
                    (void)guard_maintenance_replacement_parent_abort(nonce);
                    worker_after_ms = now + 1000;
                    continue;
                }
                worker_after_ms = now + 1000;
            } else if (reaped < 0 && errno != EINTR) {
                worker = -1;
                worker_after_ms = now + 1000;
            }
        }

        if (executor > 1) {
            int status = 0;
            int state = sysexec_poll_argv(executor, &status);
            enum guard_execution_result result = GUARD_EXEC_REAPED;
            if (state == 0 && now >= executor_deadline_ms) {
                result = sysexec_terminate_argv(executor, &status) == 0
                    ? GUARD_EXEC_TIMED_OUT : GUARD_EXEC_WAIT_LOST;
                state = 1;
            } else if (state < 0) {
                result = GUARD_EXEC_WAIT_LOST;
                state = 1;
            }
            if (state == 1) {
                int next = guard_maintenance_supervisor_complete(executor_work, result, status);
                executor = -1;
                executor_work = GUARD_WORK_NONE;
                executor_deadline_ms = 0;
                pending_work = next > 0 ? (enum guard_supervisor_work)next : GUARD_WORK_NONE;
            }
        }

        if (executor <= 1) {
            if (pending_work == GUARD_WORK_NONE) {
                int next = guard_maintenance_supervisor_tick();
                if (next > 0) pending_work = (enum guard_supervisor_work)next;
            }
            if (pending_work != GUARD_WORK_NONE) {
                pid_t started = -1;
                uint64_t phase_deadline = 0;
                int start_result = guard_maintenance_supervisor_work_deadline(
                        pending_work, &phase_deadline) == 0
                    ? guard_maintenance_supervisor_start_work(pending_work, &started) : -1;
                if (start_result == 0) {
                    executor = started;
                    executor_work = pending_work;
                    executor_deadline_ms = supervisor_executor_deadline(now, phase_deadline);
                    pending_work = GUARD_WORK_NONE;
                } else {
                    int next = guard_maintenance_supervisor_complete(
                        pending_work, start_result == -2
                            ? GUARD_EXEC_WAIT_LOST : GUARD_EXEC_NOT_STARTED, 0);
                    pending_work = next > 0 ? (enum guard_supervisor_work)next : GUARD_WORK_NONE;
                    continue;
                }
            }
        }
        struct timespec pause = { .tv_sec = 0, .tv_nsec = 50000000L };
        while (nanosleep(&pause, &pause) != 0 && errno == EINTR) { }
    }
}

static int run_supervisor_entry(char **argv) {
    char nonce[65];
    int replacement = guard_maintenance_replacement_startup_reconcile_app(nonce);
    if (replacement < 0) return 1;
    if (replacement == 1) {
        guard_maintenance_set_supervisor_owner();
        if (guard_maintenance_replacement_export_lease() != 0) return 1;
        char *const adopted_argv[] = {
            (char *)"hapaneld-helper", (char *)"--supervise-app-lease", nonce, NULL,
        };
        char *const replacement_env[] = {
            (char *)"PATH=/system/bin:/system/xbin", NULL,
        };
        execve(APP_HELPER_LIVE, adopted_argv, replacement_env);
        return 1;
    }
    return run_supervisor(argv, NULL);
}

int main(int argc, char **argv) {
    if (argc == 2 && strcmp(argv[1], "--version") == 0) {
        puts(helper_identity());
        return 0;
    }
    if (argc == 3 && strcmp(argv[1], "--request") == 0) return request_daemon(argv[2]);
    if (argc == 2 && strcmp(argv[1], "--replacement-safe") == 0) {
        int safe = guard_maintenance_replacement_safe() == 0;
        puts(safe ? "REPLACE_SAFE" : "GUARD_ARMED");
        return safe ? 0 : 3;
    }
    if (argc == 3 && strcmp(argv[1], "--replace-app-lease") == 0) {
        if (guard_maintenance_replacement_stage_app(argv[2]) != 0) return 4;
        char *const adopted_argv[] = {
            (char *)"hapaneld-helper", (char *)"--supervise-app-lease", argv[2], NULL,
        };
        char *const replacement_env[] = {
            (char *)"PATH=/system/bin:/system/xbin", NULL,
        };
        execve(APP_HELPER_LIVE, adopted_argv, replacement_env);
        return 5;
    }
    if (argc == 3 && strcmp(argv[1], "--supervise-app-lease") == 0) {
        if (guard_maintenance_replacement_supervisor_adopt_app(argv[2]) != 0) return 6;
        return run_supervisor(argv, argv[2]);
    }
    if (argc == 2 && strcmp(argv[1], "--supervise") == 0) return run_supervisor_entry(argv);
#ifdef HAPANELD_TEST_ALLOW_STALE_OWNER_RETIREMENT
    return run_daemon(argc, argv, NULL, 1);
#else
    return run_daemon(argc, argv, NULL, 0);
#endif
}
