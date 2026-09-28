// Stub sysexec for the fuzz + unit-test builds: the real parser/dispatch/validator code links and
// runs unmodified, but nothing here execs a command, opens a pipe, spawns a thread, or reboots the
// host. This is what lets a valid REBOOT/RELOAD/WATCH/GOV run through the real handlers on the build
// machine with no effect — replacing the per-call macro stubbing the fuzz harness used to need.
#include "sysexec.h"
#include "sysexec_stub.h"

#include <errno.h>
#include <pthread.h>
#include <stdio.h>
#include <string.h>
#include <unistd.h>

#define MAX_POPEN_RULES 8
#define MAX_RUN_HISTORY 32
#define MAX_RUN_BYTES 4096
#define MAX_ARGV_HISTORY 32
#define MAX_ARGV_ARGS 8
#define MAX_ARGV_BYTES 128

typedef struct {
    char needle[128];
    char output[16384];
    int close_status;
} popen_rule;

typedef struct {
    char path[MAX_ARGV_BYTES];
    char args[MAX_ARGV_ARGS][MAX_ARGV_BYTES];
    int argc;
    int quiet;
} argv_call;

static char run_fail_needle[128];
static int run_fail_status;
static char run_timeout_needle[128];
static char run_block_needle[128];
static int run_blocked;
static int run_released;
static pthread_mutex_t run_block_lock = PTHREAD_MUTEX_INITIALIZER;
static pthread_cond_t run_block_cond = PTHREAD_COND_INITIALIZER;
static char run_history[MAX_RUN_HISTORY][MAX_RUN_BYTES];
static int run_history_count;
static argv_call argv_history[MAX_ARGV_HISTORY];
static int argv_history_count;
static popen_rule popen_rules[MAX_POPEN_RULES];
static int popen_rule_count;
static int spawn_status = -1;
static int spawn_real;
static long last_pclose_offset = -1;
static int sleep_call_count;
static unsigned long sleep_total_ms;
#define MAX_DEADLINE_HISTORY 16
static unsigned deadline_history[MAX_DEADLINE_HISTORY];
static int deadline_call_count;
static unsigned deadline_elapsed_ms;

void sysexec_stub_reset(void) {
    memset(run_fail_needle, 0, sizeof run_fail_needle);
    run_fail_status = 0;
    memset(run_timeout_needle, 0, sizeof run_timeout_needle);
    pthread_mutex_lock(&run_block_lock);
    memset(run_block_needle, 0, sizeof run_block_needle);
    run_blocked = 0;
    run_released = 0;
    memset(run_history, 0, sizeof run_history);
    run_history_count = 0;
    memset(argv_history, 0, sizeof argv_history);
    argv_history_count = 0;
    pthread_mutex_unlock(&run_block_lock);
    memset(popen_rules, 0, sizeof popen_rules);
    popen_rule_count = 0;
    spawn_status = -1;
    spawn_real = 0;
    last_pclose_offset = -1;
    sleep_call_count = 0;
    sleep_total_ms = 0;
    memset(deadline_history, 0, sizeof deadline_history);
    deadline_call_count = 0;
    deadline_elapsed_ms = 0;
}

void sysexec_stub_set_spawn_result(int status) { spawn_status = status; }
void sysexec_stub_set_spawn_real(int enabled) { spawn_real = enabled; }

void sysexec_stub_block_run(const char *needle) {
    pthread_mutex_lock(&run_block_lock);
    snprintf(run_block_needle, sizeof run_block_needle, "%s", needle);
    run_blocked = 0;
    run_released = 0;
    pthread_mutex_unlock(&run_block_lock);
}

void sysexec_stub_wait_blocked(void) {
    pthread_mutex_lock(&run_block_lock);
    while (!run_blocked) pthread_cond_wait(&run_block_cond, &run_block_lock);
    pthread_mutex_unlock(&run_block_lock);
}

void sysexec_stub_release_run(void) {
    pthread_mutex_lock(&run_block_lock);
    run_released = 1;
    pthread_cond_broadcast(&run_block_cond);
    pthread_mutex_unlock(&run_block_lock);
}

void sysexec_stub_fail_run(const char *needle, int status) {
    pthread_mutex_lock(&run_block_lock);
    snprintf(run_fail_needle, sizeof run_fail_needle, "%s", needle);
    run_fail_status = status;
    pthread_mutex_unlock(&run_block_lock);
}

void sysexec_stub_timeout_run(const char *needle) {
    pthread_mutex_lock(&run_block_lock);
    snprintf(run_timeout_needle, sizeof run_timeout_needle, "%s", needle);
    pthread_mutex_unlock(&run_block_lock);
}

int sysexec_stub_count_run(const char *needle) {
    int count = 0;
    pthread_mutex_lock(&run_block_lock);
    for (int i = 0; i < run_history_count; i++)
        if (strstr(run_history[i], needle)) count++;
    pthread_mutex_unlock(&run_block_lock);
    return count;
}

int sysexec_stub_last_run(char *out, size_t size) {
    pthread_mutex_lock(&run_block_lock);
    int found = run_history_count > 0 && size > strlen(run_history[run_history_count - 1]);
    if (found) strcpy(out, run_history[run_history_count - 1]);
    pthread_mutex_unlock(&run_block_lock);
    return found;
}

int sysexec_stub_count_argv(const char *path, const char *const argv[], int quiet) {
    int count = 0;
    pthread_mutex_lock(&run_block_lock);
    for (int i = 0; i < argv_history_count; i++) {
        const argv_call *call = &argv_history[i];
        if (strcmp(call->path, path) != 0 || call->quiet != quiet) continue;
        int argc = 0;
        while (argv[argc]) argc++;
        if (argc != call->argc) continue;
        int matches = 1;
        for (int arg = 0; arg < argc; arg++) {
            if (strcmp(call->args[arg], argv[arg]) != 0) {
                matches = 0;
                break;
            }
        }
        if (matches) count++;
    }
    pthread_mutex_unlock(&run_block_lock);
    return count;
}

int sysexec_stub_count_argv_calls(void) {
    pthread_mutex_lock(&run_block_lock);
    int count = argv_history_count;
    pthread_mutex_unlock(&run_block_lock);
    return count;
}

void sysexec_stub_add_popen(const char *needle, const char *output, int close_status) {
    if (popen_rule_count >= MAX_POPEN_RULES) return;
    popen_rule *rule = &popen_rules[popen_rule_count++];
    snprintf(rule->needle, sizeof rule->needle, "%s", needle);
    snprintf(rule->output, sizeof rule->output, "%s", output);
    rule->close_status = close_status;
}

void sysexec_stub_clear_popen_rules(void) {
    memset(popen_rules, 0, sizeof popen_rules);
    popen_rule_count = 0;
}

int sysexec_run_constant(const char *cmd) {
    pthread_mutex_lock(&run_block_lock);
    if (run_history_count < MAX_RUN_HISTORY)
        snprintf(run_history[run_history_count++], sizeof run_history[0], "%s", cmd);
    if (run_fail_needle[0] && strstr(cmd, run_fail_needle)) {
        int status = run_fail_status;
        pthread_mutex_unlock(&run_block_lock);
        return status;
    }
    if (run_block_needle[0] && strstr(cmd, run_block_needle)) {
        run_blocked = 1;
        pthread_cond_broadcast(&run_block_cond);
        while (!run_released) pthread_cond_wait(&run_block_cond, &run_block_lock);
    }
    pthread_mutex_unlock(&run_block_lock);
    return 0;
}

int sysexec_run_argv(const char *path, const char *const argv[], int quiet) {
    char display[600] = {0};
    pthread_mutex_lock(&run_block_lock);
    if (argv_history_count < MAX_ARGV_HISTORY) {
        argv_call *call = &argv_history[argv_history_count++];
        snprintf(call->path, sizeof call->path, "%s", path);
        call->quiet = quiet;
        while (call->argc < MAX_ARGV_ARGS && argv[call->argc]) {
            snprintf(call->args[call->argc], sizeof call->args[call->argc],
                     "%s", argv[call->argc]);
            call->argc++;
        }
    }
    for (int i = 0; argv[i]; i++) {
        size_t used = strlen(display);
        if (used + 1 >= sizeof display) break;
        snprintf(display + used, sizeof display - used, "%s%s", i ? " " : "", argv[i]);
    }
    if (run_fail_needle[0] && strstr(display, run_fail_needle)) {
        int status = run_fail_status;
        pthread_mutex_unlock(&run_block_lock);
        return status;
    }
    if (run_block_needle[0] && strstr(display, run_block_needle)) {
        run_blocked = 1;
        pthread_cond_broadcast(&run_block_cond);
        while (!run_released) pthread_cond_wait(&run_block_cond, &run_block_lock);
    }
    pthread_mutex_unlock(&run_block_lock);
    return 0;
}

int sysexec_run_argv_timeout(const char *path, const char *const argv[], int quiet,
                             unsigned timeout_ms, int *timed_out) {
    (void)timeout_ms;
    if (timed_out) *timed_out = 0;
    char display[600] = {0};
    for (int i = 0; argv[i]; i++) {
        size_t used = strlen(display);
        if (used + 1 >= sizeof display) break;
        snprintf(display + used, sizeof display - used, "%s%s", i ? " " : "", argv[i]);
    }
    if (run_timeout_needle[0] && strstr(display, run_timeout_needle)) {
        (void)sysexec_run_argv(path, argv, quiet);
        if (timed_out) *timed_out = 1;
        return -2;
    }
    return sysexec_run_argv(path, argv, quiet);
}

int sysexec_start_argv(const char *path, const char *const argv[], int quiet, pid_t *pid) {
    if (!pid || sysexec_run_argv(path, argv, quiet) != 0) return -1;
    *pid = 4242;
    return 0;
}

int sysexec_start_stdout_argv(const char *path, const char *const argv[], pid_t *pid,
                              int *stdout_fd) {
    (void)path; (void)argv; (void)pid; (void)stdout_fd;
    return -1;
}

int sysexec_poll_argv(pid_t pid, int *status) { (void)pid; (void)status; return -1; }
int sysexec_terminate_argv(pid_t pid, int *status) { (void)pid; (void)status; return -1; }

static const popen_rule *find_argv_rule(const char *const argv[]) {
    char display[600] = {0};
    for (int i = 0; argv[i]; i++) {
        size_t used = strlen(display);
        if (used + 1 >= sizeof display) break;
        snprintf(display + used, sizeof display - used, "%s%s", i ? " " : "", argv[i]);
    }
    for (int i = 0; i < popen_rule_count; i++)
        if (strstr(display, popen_rules[i].needle)) return &popen_rules[i];
    return NULL;
}

int sysexec_capture_argv(const char *path, const char *const argv[], char *output, size_t capacity) {
    if (!output || capacity == 0) return -1;
    int run_status = sysexec_run_argv(path, argv, 0);
    const popen_rule *rule = find_argv_rule(argv);
    if (!rule) { output[0] = '\0'; return run_status == 0 ? -1 : run_status; }
    snprintf(output, capacity, "%s", rule->output);
    last_pclose_offset = (long)strlen(output);
    return rule->close_status;
}

int sysexec_capture_argv_timeout(const char *path, const char *const argv[], char *output,
                                 size_t capacity, unsigned timeout_ms, int *timed_out) {
    (void)timeout_ms;
    if (timed_out) *timed_out = 0;
    char display[600] = {0};
    for (int i = 0; argv[i]; i++) {
        size_t used = strlen(display);
        if (used + 1 >= sizeof display) break;
        snprintf(display + used, sizeof display - used, "%s%s", i ? " " : "", argv[i]);
    }
    if (run_timeout_needle[0] && strstr(display, run_timeout_needle)) {
        (void)sysexec_run_argv(path, argv, 0);
        if (output && capacity) output[0] = '\0';
        if (timed_out) *timed_out = 1;
        return -2;
    }
    return sysexec_capture_argv(path, argv, output, capacity);
}

int sysexec_stream_argv(const char *path, const char *const argv[], int output_fd) {
    int run_status = sysexec_run_argv(path, argv, 0);
    const popen_rule *rule = find_argv_rule(argv);
    if (!rule) return run_status == 0 ? -1 : run_status;
    size_t size = strlen(rule->output), offset = 0;
    while (offset < size) {
        size_t chunk_end = offset + 8192 < size ? offset + 8192 : size;
        while (offset < chunk_end) {
            ssize_t n = write(output_fd, rule->output + offset, chunk_end - offset);
            if (n < 0 && errno == EINTR) continue;
            if (n <= 0) { last_pclose_offset = (long)chunk_end; return -1; }
            offset += (size_t)n;
        }
    }
    last_pclose_offset = (long)offset;
    return rule->close_status;
}

long sysexec_stub_last_pclose_offset(void) { return last_pclose_offset; }

// Threads stay inert by default: input.c/gpio.c keep bounded watcher state, so a configured success is
// safe for parser tests and the default failure stays leak-free for fuzzing. One focused GPIO unit test
// opts into the real pthread path to prove change delivery through the production reader loop.
int sysexec_spawn(void *(*fn)(void *), void *arg) {
    if (!spawn_real) return spawn_status;
    pthread_t thread;
    if (pthread_create(&thread, NULL, fn, arg) != 0) return -1;
    pthread_detach(thread);
    return 0;
}

// Bounded host waits are recorded and return instantly. A real sleep here would make every unit and
// fuzz iteration that reaches the reboot escalation stall for its whole interval.
void sysexec_sleep_ms(unsigned ms) {
    sleep_call_count++;
    sleep_total_ms += ms;
}

int sysexec_stub_count_sleep(void) { return sleep_call_count; }
unsigned long sysexec_stub_total_sleep_ms(void) { return sleep_total_ms; }

// The deadline form records what bound it was given and then behaves exactly like the unbounded form,
// so every existing failure rule and argv assertion keeps working through it. The elapsed value is
// programmable because it is the input to the caller's remainder-sleep policy, not an output of it.
int sysexec_run_argv_deadline(const char *path, const char *const argv[], int quiet,
                              unsigned deadline_ms, unsigned *elapsed_ms) {
    pthread_mutex_lock(&run_block_lock);
    if (deadline_call_count < MAX_DEADLINE_HISTORY)
        deadline_history[deadline_call_count] = deadline_ms;
    deadline_call_count++;
    unsigned elapsed = deadline_elapsed_ms > deadline_ms ? deadline_ms : deadline_elapsed_ms;
    pthread_mutex_unlock(&run_block_lock);
    if (elapsed_ms) *elapsed_ms = elapsed;
    return sysexec_run_argv(path, argv, quiet);
}

int sysexec_stub_count_deadline_calls(void) {
    pthread_mutex_lock(&run_block_lock);
    int count = deadline_call_count;
    pthread_mutex_unlock(&run_block_lock);
    return count;
}

unsigned sysexec_stub_deadline_ms(int index) {
    if (index < 0 || index >= MAX_DEADLINE_HISTORY) return 0;
    pthread_mutex_lock(&run_block_lock);
    unsigned value = index < deadline_call_count ? deadline_history[index] : 0;
    pthread_mutex_unlock(&run_block_lock);
    return value;
}

void sysexec_stub_set_deadline_elapsed(unsigned ms) { deadline_elapsed_ms = ms; }
