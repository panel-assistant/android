#include "server.h"
#include "dispatch.h"

#include <errno.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/time.h>
#include <time.h>
#include <unistd.h>

// --- concurrent-connection cap (see server.h) ---------------------------------------------------
// The accept loop admits at most MAX_CONN simultaneous connections; a flood beyond that is refused so
// the thread-per-conn model can't be exhausted. Lock-free via SEQ_CST atomics (this logic lived
// inline in main.c's accept loop before it was factored out here to be unit-testable).
static volatile int conn_count = 0;

int conn_admit(void) {
    // Optimistically reserve, then back out if that pushed us over the cap — so two racing admits
    // can never both slip past MAX_CONN (the compare and the increment are one atomic step).
    if (__atomic_add_fetch(&conn_count, 1, __ATOMIC_SEQ_CST) > MAX_CONN) {
        __atomic_sub_fetch(&conn_count, 1, __ATOMIC_SEQ_CST);
        return 0;
    }
    return 1;
}

void conn_release(void) {
    __atomic_sub_fetch(&conn_count, 1, __ATOMIC_SEQ_CST);
}

int conn_active(void) {
    return __atomic_load_n(&conn_count, __ATOMIC_SEQ_CST);
}

// An oversized terminal header must not turn its following body into ordinary commands. Other
// oversized lines retain the established discard-through-delimiter behavior.
static int recovery_header(const char *line, size_t len) {
    while (len && (*line == ' ' || *line == '\t')) { line++; len--; }
    static const char *const verbs[] = { "ARM", "BEAT", "RECOVER", "STATUS", "SHELL" };
    for (size_t i = 0; i < sizeof verbs / sizeof verbs[0]; i++) {
        size_t n = strlen(verbs[i]);
        if (len >= n && !memcmp(line, verbs[i], n) &&
            (len == n || (unsigned char)line[n] <= 32 || (unsigned char)line[n] >= 127)) return 1;
    }
    return 0;
}

void server_serve(int cfd, enum helper_caller caller) {
    // A connection that sends nothing for IDLE_SEC is dropped unless it joined an async stream, where
    // sitting idle (only reading events) is the whole point. Bound writes separately so a client that
    // stops reading cannot pin one of the finite worker slots forever.
    struct timeval receive_timeout = { .tv_sec = IDLE_SEC, .tv_usec = 0 };
    struct timeval send_timeout = { .tv_sec = SEND_SEC, .tv_usec = 0 };
    setsockopt(cfd, SOL_SOCKET, SO_RCVTIMEO, &receive_timeout, sizeof receive_timeout);
    setsockopt(cfd, SOL_SOCKET, SO_SNDTIMEO, &send_timeout, sizeof send_timeout);

    conn_ctx ctx = { .fd = cfd, .subscribed = 0, .caller = caller };
    char line[MAX_LINE + 1];
    size_t len = 0;
    long long request_started_ms = 0;
    int overlong = 0;        // current line exceeded MAX_LINE — drop bytes through to the next newline
    char buf[256];
    for (;;) {
        // Once its verb is known, a recovery header shares the legacy absolute two-second
        // framing budget with its body. An ambiguous prefix retains ordinary idle behavior;
        // recovery admission still rejects it if completing the header took too long.
        struct timeval next_timeout = receive_timeout;
        if (len && recovery_header(line, len)) {
            struct timespec now;
            if (!request_started_ms || clock_gettime(CLOCK_MONOTONIC, &now)) return;
            long long remaining = request_started_ms + 2000 -
                (now.tv_sec * 1000LL + now.tv_nsec / 1000000);
            if (remaining <= 0) return;
            next_timeout.tv_sec = (time_t)(remaining / 1000);
            next_timeout.tv_usec = (suseconds_t)((remaining % 1000) * 1000);
        }
        setsockopt(cfd, SOL_SOCKET, SO_RCVTIMEO, &next_timeout, sizeof next_timeout);
        ssize_t n = read(cfd, buf, sizeof buf);
        if (n == 0) break;                                    // client (half-)closed — done
        if (n < 0) {
            if (errno == EINTR) continue;
            if (errno == EAGAIN || errno == EWOULDBLOCK) {    // idle timeout fired
                if (ctx.subscribed) continue;                 // subscribers are meant to idle — keep
                break;                                        // drop an idle non-subscriber
            }
            break;                                            // other read error
        }
        // Accumulate into a bounded line buffer so a command split across reads still parses, and an
        // overlong (malicious) line is dropped instead of overflowing or being mis-split.
        for (ssize_t i = 0; i < n; i++) {
            char c = buf[i];
            if (c == '\n' || c == '\r') {
                if (!overlong && len > 0) {
                    line[len] = '\0';
                    ctx.request = line;
                    ctx.request_len = len;
                    ctx.request_terminator = (unsigned char)c;
                    ctx.request_started_ms = request_started_ms;
                    ctx.body_prefix = (const unsigned char *)buf + i + 1;
                    ctx.body_prefix_len = (size_t)(n - i - 1);
                    // A malformed delimiter after a reserved recovery verb must still terminate;
                    // otherwise an unknown-verb reply could expose its following body to dispatch.
                    if (recovery_header(line, len)) ctx.terminal = 1;
                    dispatch(&ctx, line);
                    if (ctx.terminal) return;
                }
                len = 0; overlong = 0; request_started_ms = 0;
            } else if (len < MAX_LINE) {
                if (!len) {
                    struct timespec now;
                    if (clock_gettime(CLOCK_MONOTONIC, &now) == 0)
                        request_started_ms = now.tv_sec * 1000LL + now.tv_nsec / 1000000;
                }
                line[len++] = c;
            } else {
                if (recovery_header(line, len)) return;
                overlong = 1;
            }
        }
    }
}
