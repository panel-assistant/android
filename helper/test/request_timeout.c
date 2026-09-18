#define _GNU_SOURCE

#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/types.h>
#include <sys/un.h>
#include <sys/wait.h>
#include <time.h>
#include <unistd.h>

#define SOCK_NAME "hapaneld-helper-request-timeout-test"
#define REQUEST_TIMEOUT_MS 150
#define main hapaneld_helper_main_for_request_timeout_test
#include "../src/main.c"
#undef main

/* main.c's daemon entry point references these modules; this test only calls request_daemon(). */
void input_init(void) {}
void gpio_init(void) {}
void screen_init(void) {}
void led_init(void) {}
int input_watch(const char *path, int grab) { (void)path; (void)grab; return 0; }
void server_serve(int fd, enum helper_caller caller) { (void)fd; (void)caller; }
void input_unsubscribe(int fd) { (void)fd; }
void gpio_unsubscribe(int fd) { (void)fd; }
void conn_release(void) {}
int conn_admit(void) { return 0; }
int guard_maintenance_init(void) { return 0; }
int guard_maintenance_replacement_safe(void) { return 0; }
int guard_maintenance_replacement_parent_grant(char nonce[65]) { (void)nonce; return -1; }
int guard_maintenance_replacement_export_lease(void) { return -1; }
int guard_maintenance_replacement_parent_abort(const char nonce[65]) { (void)nonce; return -1; }
int guard_maintenance_replacement_stage_app(const char nonce[65]) { (void)nonce; return -1; }
int guard_maintenance_replacement_supervisor_adopt_app(const char nonce[65]) {
    (void)nonce; return -1;
}
int guard_maintenance_replacement_worker_commit_app(const char nonce[65]) {
    (void)nonce; return -1;
}
int guard_maintenance_replacement_startup_reconcile_app(char nonce[65]) {
    (void)nonce; return 0;
}
void guard_maintenance_set_supervised(int supervised) { (void)supervised; }
void guard_maintenance_set_supervisor_owner(void) {}
int guard_maintenance_supervisor_tick(void) { return 0; }
int guard_maintenance_supervisor_work_deadline(enum guard_supervisor_work work,
        uint64_t *deadline_ms) { (void)work; (void)deadline_ms; return -1; }
int guard_maintenance_supervisor_start_work(enum guard_supervisor_work work, pid_t *pid) {
    (void)work; (void)pid; return -1;
}
int guard_maintenance_supervisor_complete(enum guard_supervisor_work work,
        enum guard_execution_result result, int status) {
    (void)work; (void)result; (void)status; return -1;
}
int sysexec_poll_argv(pid_t pid, int *status) { (void)pid; (void)status; return -1; }
int sysexec_terminate_argv(pid_t pid, int *status) { (void)pid; (void)status; return -1; }

#define CHECK(condition, message) do { \
    if (!(condition)) { fprintf(stderr, "FAIL: %s\n", message); return 1; } \
} while (0)

static long long elapsed_millis(struct timespec start, struct timespec end) {
    return (long long)(end.tv_sec - start.tv_sec) * 1000LL +
           (end.tv_nsec - start.tv_nsec) / 1000000LL;
}

static int create_listener(void) {
    int fd = socket(AF_UNIX, SOCK_STREAM, 0);
    if (fd < 0) return -1;
    struct sockaddr_un address;
    memset(&address, 0, sizeof address);
    address.sun_family = AF_UNIX;
    address.sun_path[0] = '\0';
    memcpy(address.sun_path + 1, SOCK_NAME, sizeof SOCK_NAME - 1);
    socklen_t length = offsetof(struct sockaddr_un, sun_path) + 1 + (sizeof SOCK_NAME - 1);
    if (bind(fd, (struct sockaddr *)&address, length) != 0 || listen(fd, 1) != 0) {
        close(fd);
        return -1;
    }
    return fd;
}

static int write_all(int fd, const char *bytes) {
    size_t remaining = strlen(bytes);
    while (remaining > 0) {
        ssize_t count = write(fd, bytes, remaining);
        if (count < 0 && errno == EINTR) continue;
        if (count <= 0) return -1;
        bytes += (size_t)count;
        remaining -= (size_t)count;
    }
    return 0;
}

static int read_exact_request(int fd, const char *expected) {
    char request[64];
    size_t used = 0;
    while (used < sizeof request) {
        ssize_t count = read(fd, request + used, sizeof request - used);
        if (count < 0 && errno == EINTR) continue;
        if (count <= 0) return -1;
        used += (size_t)count;
        if (memchr(request, '\n', used)) break;
    }
    return used == strlen(expected) && memcmp(request, expected, used) == 0 ? 0 : -1;
}

static int invoke_request_entry(const char *command, char *output, size_t capacity) {
    int capture[2];
    if (!output || capacity == 0 || pipe(capture) != 0) return -1;
    fflush(stdout);
    int saved_stdout = dup(STDOUT_FILENO);
    if (saved_stdout < 0 || dup2(capture[1], STDOUT_FILENO) < 0) {
        if (saved_stdout >= 0) close(saved_stdout);
        close(capture[0]);
        close(capture[1]);
        return -1;
    }
    close(capture[1]);
    char *argv[] = { (char *)"hapaneld-helper", (char *)"--request", (char *)command, NULL };
    int result = hapaneld_helper_main_for_request_timeout_test(3, argv);
    fflush(stdout);
    int restored = dup2(saved_stdout, STDOUT_FILENO);
    close(saved_stdout);
    if (restored < 0) {
        close(capture[0]);
        return -1;
    }
    size_t used = 0;
    while (used + 1 < capacity) {
        ssize_t count = read(capture[0], output + used, capacity - 1 - used);
        if (count < 0 && errno == EINTR) continue;
        if (count < 0) {
            close(capture[0]);
            return -1;
        }
        if (count == 0) break;
        used += (size_t)count;
    }
    close(capture[0]);
    output[used] = '\0';
    return result;
}

int main(void) {
    CHECK(supervisor_executor_deadline(100, 1000000) == 60100,
        "supervisor executor applies the fixed command cap");
    CHECK(supervisor_executor_deadline(100, 60099) == 60099,
        "persisted phase deadline tightens the command cap");
    CHECK(supervisor_executor_deadline(100, 100) == 100,
        "phase-deadline equality cannot receive fresh executor time");
    CHECK(supervisor_executor_deadline(LLONG_MAX - 10, UINT64_MAX) == LLONG_MAX - 10,
        "clock overflow fails closed without extending executor authority");
    CHECK(supervisor_executor_deadline(-1, 1000) == -1,
        "invalid monotonic time cannot create executor authority");

    int listener = create_listener();
    CHECK(listener >= 0, "partial-reply test listener must bind");

    pid_t server = fork();
    CHECK(server >= 0, "partial-reply test server must fork");
    if (server == 0) {
        int client = accept(listener, NULL, NULL);
        if (client < 0) _exit(2);
        char request[64];
        if (read(client, request, sizeof request) <= 0) _exit(3);
        if (write(client, "P", 1) != 1) _exit(4);
        sleep(2);
        close(client);
        _exit(0);
    }

    struct timespec start;
    struct timespec end;
    CHECK(clock_gettime(CLOCK_MONOTONIC, &start) == 0, "start time must be readable");
    int result = request_daemon("PING");
    CHECK(clock_gettime(CLOCK_MONOTONIC, &end) == 0, "end time must be readable");
    long long elapsed_ms = elapsed_millis(start, end);

    kill(server, SIGKILL);
    int status;
    CHECK(waitpid(server, &status, 0) == server, "partial-reply server must be reaped");
    close(listener);

    CHECK(result == 1, "a partial reply that stalls must fail");
    CHECK(elapsed_ms >= 75, "request must wait for its bounded reply deadline");
    CHECK(elapsed_ms < 1000, "partial reply must not leave the request blocked");

    listener = create_listener();
    CHECK(listener >= 0, "long Guard-status test listener must bind");
    char session[65], boot[65], sha[65];
    memset(session, 'a', sizeof session - 1); session[sizeof session - 1] = '\0';
    memset(boot, 'b', sizeof boot - 1); boot[sizeof boot - 1] = '\0';
    memset(sha, 'c', sizeof sha - 1); sha[sizeof sha - 1] = '\0';
    char guard_status[MAX_LINE + 2];
    int guard_status_length = snprintf(guard_status, sizeof guard_status,
        "OK GUARDSTATUS 12 WAIT_B_HEALTH %s %s B %s 569 15 37 NONE NONE "
        "1800000 1320000\n", session, boot, sha);
    CHECK(guard_status_length > 255 && guard_status_length <= MAX_LINE + 1,
        "role-bearing Guard status must cross the former probe ceiling within the protocol bound");

    server = fork();
    CHECK(server >= 0, "long Guard-status test server must fork");
    if (server == 0) {
        int client = accept(listener, NULL, NULL);
        if (client < 0) _exit(5);
        if (read_exact_request(client, "GUARDSTATUS\n") != 0) _exit(6);
        if (write_all(client, guard_status) != 0) _exit(7);
        close(client);
        _exit(0);
    }

    char observed[MAX_LINE + 2];
    result = invoke_request_entry("GUARDSTATUS", observed, sizeof observed);
    close(listener);
    CHECK(waitpid(server, &status, 0) == server && WIFEXITED(status) && WEXITSTATUS(status) == 0,
        "long Guard-status test server must complete the exact exchange");
    CHECK(result == 0, "helper --request GUARDSTATUS must accept a full role-bearing status");
    CHECK(strcmp(observed, guard_status) == 0,
        "helper --request GUARDSTATUS must preserve the complete role-bearing status");
    puts("request timeout tests passed");
    return 0;
}
