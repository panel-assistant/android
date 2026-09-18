// Per-connection serving: the bounded line accumulator that turns a byte stream into command lines,
// dispatches each, and enforces the idle timeout. Connection setup, peer-auth, and the accept loop
// live in main.c; this is just "given a connected fd, serve it until it closes".
#ifndef HAPANELD_SERVER_H
#define HAPANELD_SERVER_H

#include "identity.h"

#define MAX_LINE 512   // longest accepted command line; longer lines are dropped, not mis-split
#define IDLE_SEC 30    // drop a connection not subscribed to an async stream after this idle period
#define SEND_SEC 5     // bound a stalled client's ability to hold a worker in a reply write
#define MAX_CONN 16    // max concurrent client connections; the (MAX_CONN+1)th is refused

// Serve a connected socket fd until the client (half-)closes or an idle non-subscriber times out.
// `caller` is the identity the accept loop already authenticated for this peer. It is fixed for the
// life of the connection and reaches every handler through conn_ctx.
void server_serve(int cfd, enum helper_caller caller);

// Concurrent-connection admission gate for the accept loop (main.c). conn_admit() atomically reserves
// a slot and returns 1, or returns 0 WITHOUT reserving when the cap (MAX_CONN) is already reached;
// conn_release() frees a slot when a connection ends; conn_active() reports the live count. Lock-free
// and thread-safe. Factored out of main.c's accept loop so the cap is unit-testable without it.
int  conn_admit(void);
void conn_release(void);
int  conn_active(void);

#endif
