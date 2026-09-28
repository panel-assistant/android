#include <errno.h>
#include <pthread.h>
#include <signal.h>
#include <stdio.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/time.h>
#include <sys/wait.h>
#include <unistd.h>

#include "dispatch.h"

static int failures;
#define CHECK(cond, ...) do { if (!(cond)) { printf("FAIL: " __VA_ARGS__); failures++; } } while (0)

static void *serve(void *arg) {
    int fd = *(int *)arg;
    conn_ctx ctx = { .fd = fd, .caller = HELPER_CALLER_LEGACY };
    char command[] = "LOGCAT";
    dispatch(&ctx, command);
    close(fd);
    return NULL;
}

int main(void) {
    signal(SIGPIPE, SIG_IGN);
    int pair[2];
    if (socketpair(AF_UNIX, SOCK_STREAM, 0, pair) != 0) return 2;
    conn_ctx ctx = { .fd = pair[0], .caller = HELPER_CALLER_LEGACY };
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
    if (failures) return 1;
    puts("LOGCAT BOUNDARY OK");
    return 0;
}
