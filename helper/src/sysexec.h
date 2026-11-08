// sysexec owns the fixed-command execution, pipe, thread and reboot mechanisms. Request-derived
// values use structural argv or direct operations; its shell scripts are audited constants.
// adb_recovery.c is the administrative exception: its lifecycle owner forks fixed property tools
// and executes caller-supplied root programs through the authenticated SHELL operation.
//
// Fixed-command tests link sysexec_stub.c so real parser/validator code runs without host effects.
// Recovery and fuzz fixtures additionally isolate their own process and execution boundary.
#ifndef HAPANELD_SYSEXEC_H
#define HAPANELD_SYSEXEC_H

#include <stddef.h>
#include <sys/types.h>

/* Before any lifecycle-parent execution, enable bounded, thread-free child custody.
 * The forked socket worker disables it before starting threads. A parent tick reaps
 * only deferred children; live caller-owned statuses are never stolen. Eight owned
 * children (active or deferred) exhaust admission until a status is collected.
 * Disable only in a forked worker, never to abandon a parent's outstanding custody. */
void sysexec_set_lifecycle_owner(int enabled);
int sysexec_reap_pending(void);

// Run an audited, argument-free constant shell program, blocking until it exits. Never pass request,
// profile, environment, filesystem, or network-derived bytes. Returns the raw wait status.
int   sysexec_run_constant(const char *program);

// Execute an absolute path with a structural argument vector, without a shell. When quiet is true,
// stdin/stdout/stderr are connected to /dev/null. Returns the raw child wait status.
//
// This form waits for the child forever. Use it only where the program is known to terminate; for a
// privileged Android actuator use the deadline form below.
int   sysexec_run_argv(const char *path, const char *const argv[], int quiet);

// As sysexec_run_argv, but the child gets at most [deadline_ms] before it is killed and reaped.
// Returns the raw child wait status, or -1 when the child was spawned but did not exit inside the
// deadline (and on spawn failure). When [elapsed_ms] is non-NULL it receives how much of the deadline
// the wait consumed, so a caller whose policy is "give this mechanism N ms" can sleep the remainder
// without owning a clock.
//
// Why the deadline exists: several /system/bin actuators (`svc`, `input`, `am`, `wm`) are app_process
// wrappers, and one that cannot reach the framework can block indefinitely rather than fail. An
// unbounded wait there pins the calling connection thread for the life of the daemon, so a policy
// that escalates on a deadline can never reach its next mechanism and the connection cap is consumed
// one stuck request at a time. Bounding the wait is what makes the escalation above it real.
int   sysexec_run_argv_deadline(const char *path, const char *const argv[], int quiet,
                                unsigned deadline_ms, unsigned *elapsed_ms);

/* Execute with a monotonic deadline. Returns raw wait status, -1 for spawn/wait failure, or -2
 * after terminating the process group that exceeded timeout_ms. The leader is reaped inline for at
 * most 500 ms, then by a parent tick or worker reaper if necessary. timed_out is always initialized. */
int   sysexec_run_argv_timeout(const char *path, const char *const argv[], int quiet,
                               unsigned timeout_ms, int *timed_out);

/* Start one directly-execed child owned by the caller. A CLOEXEC handshake proves exec succeeded;
 * the returned pid is also its process-group id. poll returns 0 running, 1 reaped, -1 unknown.
 * terminate returns 0 only with the leader's exact wait status; it returns -1 after a deferred
 * reap handoff because no exact status is available to that caller. */
int   sysexec_start_argv(const char *path, const char *const argv[], int quiet, pid_t *pid);
/* Start a fixed argv with stdout on a CLOEXEC pipe owned by the caller. */
int   sysexec_start_stdout_argv(const char *path, const char *const argv[], pid_t *pid, int *stdout_fd);
int   sysexec_poll_argv(pid_t pid, int *status);
int   sysexec_terminate_argv(pid_t pid, int *status);

// Execute an absolute path without a shell and capture bounded stdout. Output is always NUL
// terminated when capacity is non-zero. Returns the raw child wait status, or -1 on I/O/fork error.
int   sysexec_capture_argv(const char *path, const char *const argv[], char *output, size_t capacity);

/* Capture bounded stdout under a monotonic deadline. The process group is killed on timeout and its
 * leader is reaped inline for at most 500 ms, then by a parent tick or worker reaper if necessary.
 * Output is always NUL terminated. Returns raw wait status, -1 for I/O/spawn failure, or -2 for timeout. Callers must
 * independently adjudicate side effects. */
int   sysexec_capture_argv_timeout(const char *path, const char *const argv[], char *output,
                                   size_t capacity, unsigned timeout_ms, int *timed_out);

/* The capture above with two extra controls: [stdin_fd] (or -1 for /dev/null) becomes the child's
 * stdin, which keeps a held lock alive across a framework command that hands stdin over Binder, and
 * [merge_stderr] sends stderr into the captured output for commands that report errors there while
 * exiting 0. */
int   sysexec_capture_argv_io(const char *path, const char *const argv[], char *output,
                              size_t capacity, unsigned timeout_ms, int *timed_out,
                              int stdin_fd, int merge_stderr);

// Fixed one-topic vendor broker read, child-only fixed LD_LIBRARY_PATH and 3500ms deadline.
// Successful capture reports its exact byte count, so embedded NUL cannot hide trailing data.
int sysexec_capture_zigbee_role(char *output, size_t capacity, size_t *used);

// Fixed Wi-Fi status read, cmd then nonzero-exit-only dumpsys fallback, one 2000ms budget.
// Exact byte count and complete capture let the handler reject embedded NUL and overflow.
int sysexec_capture_wifi_status(char *output, size_t capacity, size_t *used);
// Fixed dumpsys gfxinfo; package is selected internally by authenticated SELF identity.
int sysexec_capture_gfxinfo(const char *package, char *output, size_t capacity, size_t *used);

#define ADB_PROPERTY_COUNT 5
#define ADB_PROPERTY_CAPACITY 257
struct adb_property_capture {
    char output[ADB_PROPERTY_COUNT][ADB_PROPERTY_CAPACITY];
    size_t used[ADB_PROPERTY_COUNT];
    int status[ADB_PROPERTY_COUNT];
};
// Five fixed getprop reads under one 2000ms budget. Each status is independent; the caller validates
// exact bytes, including embedded NUL. Unattempted entries stay unknown (-1, zero bytes).
void sysexec_capture_adb_properties(struct adb_property_capture *capture);

// Execute an absolute path without a shell and copy stdout to an already-open descriptor. The child
// receives /dev/null on stdin and stderr. Returns the raw child wait status, or -1 on I/O/fork error.
int   sysexec_stream_argv(const char *path, const char *const argv[], int output_fd);

// Spawn a detached background thread running fn(arg). Returns 0 on success, non-zero on failure.
int   sysexec_spawn(void *(*fn)(void *), void *arg);

// Block this thread for [ms] milliseconds. Interruptions are absorbed so the caller gets the whole
// interval. Isolated here with the other host primitives so a policy that waits for a host-level
// effect (see sysctl.c's reboot escalation) stays deterministic and instant under the test stub.
void  sysexec_sleep_ms(unsigned ms);

#endif
