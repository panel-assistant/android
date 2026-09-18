#include "server.h"
#include "dispatch.h"

#include <errno.h>
#include <sys/socket.h>
#include <sys/time.h>
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
    int overlong = 0;        // current line exceeded MAX_LINE — drop bytes through to the next newline
    char buf[256];
    for (;;) {
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
                if (!overlong && len > 0) { line[len] = '\0'; dispatch(&ctx, line); }
                len = 0; overlong = 0;
            } else if (len < MAX_LINE) {
                line[len++] = c;
            } else {
                overlong = 1;
            }
        }
    }
}
