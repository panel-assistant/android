#include "logcat.h"

#include <errno.h>
#include <poll.h>
#include <stdio.h>
#include <string.h>
#include <sys/socket.h>
#include <time.h>
#include <unistd.h>

#include "sysexec.h"
#include "util.h"

#ifndef HAPANELD_LOGCAT_PATH
#define HAPANELD_LOGCAT_PATH "/system/bin/logcat"
#endif

// Android's source payload is about 4 KiB. Keep the whole long-format entry or discard it;
// never emit a prefix of a stack trace. The fixed buffers also bound hostile child output.
#define ENTRY_MAX 8192u
#define LOGCAT_LINE_MAX 8192u
#define SECOND_MAX_BYTES (256u * 1024u)
#define SECOND_MAX_ENTRIES 256u

void cmd_logcatcaps(conn_ctx *ctx, const char *args) {
    reply(ctx->fd, *args ? "ERR\n" : "LOGCATCAPS 1\n");
}

static int send_all(int fd, const char *bytes, size_t size) {
    while (size) {
        ssize_t sent = send(fd, bytes, size, MSG_NOSIGNAL);
        if (sent < 0 && errno == EINTR) continue;
        if (sent <= 0) return -1;
        bytes += sent;
        size -= (size_t)sent;
    }
    return 0;
}

static time_t monotonic_second(void) {
    struct timespec now;
    return clock_gettime(CLOCK_MONOTONIC, &now) == 0 ? now.tv_sec : (time_t)-1;
}

static void send_dropped(int fd, unsigned dropped) {
    if (!dropped) return;
    struct timespec now;
    if (clock_gettime(CLOCK_REALTIME, &now) != 0) return;
    char marker[160];
    int length = snprintf(marker, sizeof marker,
                          "[ %lld.%03ld 0: 0 W/hapaneld-helper ]\n"
                          "%u complete log entries dropped by helper limit\n\n",
                          (long long)now.tv_sec, now.tv_nsec / 1000000L, dropped);
    if (length > 0 && (size_t)length < sizeof marker) (void)send_all(fd, marker, (size_t)length);
}

void cmd_logcat(conn_ctx *ctx, const char *args) {
    if (*args) { reply(ctx->fd, "ERR\n"); return; }
    // -T 1 follows from the tail. Filter its one buffered entry by the captured start time.
    // printable escapes message newlines, so an empty physical line is only the long-format
    // record separator; a blank line inside an exception can never split or drop its stack.
    const char *const argv[] = { "logcat", "-b", "all", "-T", "1", "-v", "long",
                                 "-v", "epoch", "-v", "printable", NULL };
    struct timespec start;
    if (clock_gettime(CLOCK_REALTIME, &start) != 0) { reply(ctx->fd, "ERR\n"); return; }
    pid_t child;
    int source;
    if (sysexec_start_stdout_argv(HAPANELD_LOGCAT_PATH, argv, &child, &source) != 0) {
        reply(ctx->fd, "ERR\n");
        return;
    }
    if (send_all(ctx->fd, "OK\n", 3) != 0) goto cleanup;

    char line[LOGCAT_LINE_MAX + 1], entry[ENTRY_MAX + 1], buffer[4096];
    size_t line_used = 0, entry_used = 0;
    int overflow = 0;
    unsigned dropped = 0, sent_entries = 0;
    size_t sent_bytes = 0;
    time_t window = monotonic_second();
    for (;;) {
        struct pollfd watched[] = {
            { .fd = source, .events = POLLIN | POLLHUP },
            { .fd = ctx->fd, .events = POLLIN | POLLHUP | POLLERR },
        };
        int ready = poll(watched, 2, 1000);
        if (ready < 0 && errno == EINTR) continue;
        if (ready < 0) break;
        if (ready == 0 && dropped && monotonic_second() != window) {
            send_dropped(ctx->fd, dropped);
            window = monotonic_second(); sent_bytes = 0; sent_entries = 0; dropped = 0;
        }
        if (watched[1].revents) {
            char probe;
            // A streaming connection accepts no further commands. EOF means close, even when
            // the client half-closes its write side to express cancellation.
            if (recv(ctx->fd, &probe, 1, MSG_PEEK | MSG_DONTWAIT) <= 0) break;
            break;
        }
        if (!(watched[0].revents & (POLLIN | POLLHUP | POLLERR))) continue;
        ssize_t count = read(source, buffer, sizeof buffer);
        if (count < 0 && errno == EINTR) continue;
        if (count <= 0) break;
        for (ssize_t i = 0; i < count; i++) {
            char c = buffer[i];
            if (c != '\n') {
                if (line_used < LOGCAT_LINE_MAX) line[line_used++] = c;
                else overflow = 1;
                continue;
            }
            if (line_used >= 23 && memcmp(line, "--------- beginning of ", 23) == 0) {
                line_used = 0; entry_used = 0; overflow = 0;
                continue;
            }
            if (line_used == 0) {
                if (entry_used || overflow) {
                    time_t second = monotonic_second();
                    if (second != window) {
                        send_dropped(ctx->fd, dropped);
                        window = second; sent_bytes = 0; sent_entries = 0; dropped = 0;
                    }
                    // A full long-format record starts with an epoch header. Never send the
                    // stale tail line from -T 1 or a fragment without its header.
                    entry[entry_used] = '\0';
                    double stamp = 0;
                    int valid = entry_used && sscanf(entry, "[ %lf", &stamp) == 1 &&
                                stamp >= (double)start.tv_sec + start.tv_nsec / 1000000000.0;
                    if (valid) {
                        if (overflow || entry_used + 1 > ENTRY_MAX ||
                            sent_entries >= SECOND_MAX_ENTRIES ||
                            entry_used + 1 > SECOND_MAX_BYTES - sent_bytes) {
                            dropped++;
                        } else {
                            entry[entry_used++] = '\n';
                            if (send_all(ctx->fd, entry, entry_used) != 0) goto cleanup;
                            sent_bytes += entry_used;
                            sent_entries++;
                        }
                    }
                }
                entry_used = 0; overflow = 0;
            } else if (!overflow && entry_used + line_used + 1 <= ENTRY_MAX) {
                memcpy(entry + entry_used, line, line_used);
                entry_used += line_used;
                entry[entry_used++] = '\n';
            } else {
                overflow = 1;
            }
            line_used = 0;
        }
    }
cleanup:
    close(source);
    int status;
    (void)sysexec_terminate_argv(child, &status);
}
