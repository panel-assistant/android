#include <errno.h>
#include <pthread.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/time.h>
#include <sys/wait.h>
#include <time.h>
#include <unistd.h>

#include "dispatch.h"

static int failures;
#define CHECK(cond, ...) do { if (!(cond)) { printf("FAIL: " __VA_ARGS__); failures++; } } while (0)

static void *serve(void *arg) {
    int fd = *(int *)arg;
    conn_ctx ctx = { .fd = fd, .caller = HELPER_CALLER_SUCCESSOR };
    char command[] = "LOGCAT";
    dispatch(&ctx, command);
    close(fd);
    return NULL;
}

struct dump_request { int fd; char command[64]; };

static void *serve_dump(void *arg) {
    struct dump_request *request = arg;
    conn_ctx ctx = { .fd = request->fd, .caller = HELPER_CALLER_SUCCESSOR };
    dispatch(&ctx, request->command);
    close(request->fd);
    return NULL;
}

static long long now_ms(void) {
    struct timespec now;
    clock_gettime(CLOCK_MONOTONIC, &now);
    return (long long)now.tv_sec * 1000LL + now.tv_nsec / 1000000L;
}

// Run one LOGCATDUMP request on a fresh socket pair and collect everything up to EOF. A handler
// that never reaches EOF fails here instead of hanging the suite.
static size_t run_dump(const char *command, char *out, size_t capacity, long long *elapsed_ms) {
    static struct dump_request request;
    int pair[2];
    if (socketpair(AF_UNIX, SOCK_STREAM, 0, pair) != 0) exit(2);
    struct timeval timeout = { .tv_sec = 6 };
    setsockopt(pair[1], SOL_SOCKET, SO_RCVTIMEO, &timeout, sizeof timeout);
    request.fd = pair[0];
    snprintf(request.command, sizeof request.command, "%s", command);
    long long start = now_ms();
    pthread_t worker;
    if (pthread_create(&worker, NULL, serve_dump, &request) != 0) exit(2);
    size_t used = 0;
    for (;;) {
        ssize_t n = read(pair[1], out + used, capacity - used - 1);
        if (n == 0) break;
        if (n < 0) {
            printf("FAIL: LOGCATDUMP reaches EOF within its deadline (%s)\n", command);
            exit(1);
        }
        used += (size_t)n;
        if (used >= capacity - 1) break;
    }
    out[used] = '\0';
    if (elapsed_ms) *elapsed_ms = now_ms() - start;
    close(pair[1]);
    pthread_join(worker, NULL);
    return used;
}

static void check_dump(void) {
    static char out[400000];
    const char *refused[] = { "LOGCATDUMP", "LOGCATDUMP 0", "LOGCATDUMP 1001", "LOGCATDUMP 10000",
                              "LOGCATDUMP abc", "LOGCATDUMP 300 x", "LOGCATDUMP 300;sh", "LOGCATDUMP -1",
                              "LOGCATDUMP +5", "LOGCATDUMP 99999999999999999999" };
    for (size_t i = 0; i < sizeof refused / sizeof refused[0]; i++) {
        run_dump(refused[i], out, sizeof out, NULL);
        CHECK(strcmp(out, "ERR\n") == 0, "LOGCATDUMP refuses a malformed or out-of-range count: '%s' -> '%.20s'\n",
              refused[i], out);
    }

    run_dump("LOGCATDUMP 3", out, sizeof out, NULL);
    CHECK(strcmp(out, "OK\n"
                      "[ 1790592713.000 12: 13 I/Backlog ]\nbacklog entry 0\n\n"
                      "[ 1790592713.001 12: 13 I/Backlog ]\nbacklog entry 1\n\n"
                      "[ 1790592713.002 12: 13 I/Backlog ]\nbacklog entry 2\n\n") == 0,
          "LOGCATDUMP returns the fixed-argv backlog, then EOF\n");
    run_dump("LOGCATDUMP 1000", out, sizeof out, NULL);
    CHECK(strstr(out, "backlog entry 999\n\n") != NULL, "LOGCATDUMP accepts its upper bound\n");
    run_dump("LOGCATDUMP 1", out, sizeof out, NULL);
    CHECK(strstr(out, "backlog entry 0\n\n") != NULL, "LOGCATDUMP accepts its lower bound\n");

    long long elapsed = 0;
    run_dump("LOGCATDUMP 7", out, sizeof out, &elapsed);
    CHECK(strcmp(out, "OK\n[ 1790592713.000 12: 13 I/Wedged ]\nbefore the wedge\n\n") == 0,
          "LOGCATDUMP forwards output a wedged child produced before its deadline\n");
    CHECK(elapsed >= 250 && elapsed < 2500, "LOGCATDUMP ends a wedged child at its deadline (%lld ms)\n", elapsed);
    int status;
    CHECK(waitpid(-1, &status, WNOHANG) < 0 && errno == ECHILD, "LOGCATDUMP timeout kills and reaps its child\n");

    size_t used = run_dump("LOGCATDUMP 999", out, sizeof out, NULL);
    CHECK(strncmp(out, "OK\n[ ", 5) == 0 && used == 3 + 256u * 1024u,
          "LOGCATDUMP stops an unbounded producer at its byte cap (%zu bytes)\n", used);
    CHECK(waitpid(-1, &status, WNOHANG) < 0 && errno == ECHILD, "LOGCATDUMP cap kills and reaps its child\n");
}

int main(void) {
    signal(SIGPIPE, SIG_IGN);
    int pair[2];
    if (socketpair(AF_UNIX, SOCK_STREAM, 0, pair) != 0) return 2;
    conn_ctx ctx = { .fd = pair[0], .caller = HELPER_CALLER_SUCCESSOR };
    char caps[] = "LOGCATCAPS", caps_bad[] = "LOGCATCAPS extra", bad[] = "LOGCAT ;sh";
    char reply[64];
    dispatch(&ctx, caps);
    ssize_t n = read(pair[1], reply, sizeof reply - 1);
    reply[n > 0 ? n : 0] = '\0';
    CHECK(strcmp(reply, "LOGCATCAPS 1\n") == 0, "LOGCATCAPS advertises the exact capability\n");
    dispatch(&ctx, caps_bad);
    n = read(pair[1], reply, sizeof reply - 1); reply[n > 0 ? n : 0] = '\0';
    CHECK(strcmp(reply, "ERR\n") == 0, "LOGCATCAPS rejects arguments\n");
    dispatch(&ctx, bad);
    n = read(pair[1], reply, sizeof reply - 1); reply[n > 0 ? n : 0] = '\0';
    CHECK(strcmp(reply, "ERR\n") == 0, "LOGCAT rejects shell input before spawning\n");
    close(pair[0]); close(pair[1]);

    if (socketpair(AF_UNIX, SOCK_STREAM, 0, pair) != 0) return 2;
    struct timeval timeout = { .tv_sec = 7 };
    setsockopt(pair[1], SOL_SOCKET, SO_RCVTIMEO, &timeout, sizeof timeout);
    pthread_t worker;
    if (pthread_create(&worker, NULL, serve, &pair[0]) != 0) return 2;
    char stream[300000];
    size_t used = 0;
    stream[0] = '\0';
    for (;;) {
        n = read(pair[1], stream + used, sizeof stream - used - 1);
        if (n <= 0) break;
        used += (size_t)n;
        stream[used] = '\0';
        if (strstr(stream, "complete log entries dropped by helper limit")) break;
        if (used >= sizeof stream - 2) break;
    }
    CHECK(strncmp(stream, "OK\n[ ", 5) == 0, "LOGCAT acknowledges before streaming full-system entries\n");
    CHECK(strstr(stream, "old buffered entry") == NULL,
          "LOGCAT starts at now rather than forwarding the buffered tail\n");
    CHECK(strstr(stream, "java.lang.Exception: complete\\n\\n at example.First.one(First.java:1)\\n"
                         " at example.Second.two(Second.java:2)\n\n") != NULL,
          "LOGCAT keeps a complete multiline exception entry\n");
    CHECK(strstr(stream, "Oversize") == NULL && strstr(stream, "XXXXX") == NULL,
          "LOGCAT discards an oversized complete entry, never its prefix\n");
    CHECK(strstr(stream, "complete log entries dropped by helper limit") != NULL,
          "LOGCAT reports rate and size drops as complete records\n");
    int bursts = 0;
    for (const char *cursor = stream; (cursor = strstr(cursor, "I/Burst ]")) != NULL; cursor += 9)
        bursts++;
    CHECK(bursts > 200 && bursts < 250, "LOGCAT enforces its byte rate on complete entries\n");
    CHECK(used < 280000, "LOGCAT bounds output from a burst\n");
    close(pair[1]);
    pthread_join(worker, NULL);
    int status;
    CHECK(waitpid(-1, &status, WNOHANG) < 0 && errno == ECHILD,
          "LOGCAT disconnect kills and reaps its child\n");
    check_dump();
    if (failures) return 1;
    puts("LOGCAT BOUNDARY OK");
    return 0;
}
