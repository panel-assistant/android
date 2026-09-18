// Shared command-handler contract for the hapaneld-helper daemon.
//
// dispatch() (dispatch.c) splits a command line into a verb + argument string and calls the matching
// handler. Each capability module (led.c, screen.c, …) defines its own handlers with this signature;
// the verb→handler table lives in dispatch.c. A handler parses `args` (the text AFTER the verb, with
// no leading spaces), does its work, and writes a reply on ctx->fd.
#ifndef HAPANELD_CMD_H
#define HAPANELD_CMD_H

#include "identity.h"

// Per-connection state threaded through a command. `subscribed` is set by an async-stream subscription
// handler so the server's idle-timeout exempts a connection meant to sit idle, only reading.
//
// `caller` is resolved ONCE, in the accept loop, from the peer's SO_PEERCRED uid — the only identity
// on this socket the client cannot choose. A handler that acts on one of the two app packages selects
// it from this field; it never reads the package out of the request, because a request value is
// caller-controlled and per-uid authorisation exists precisely because that is not trustworthy.
typedef struct {
    int fd;
    int subscribed;
    enum helper_caller caller;
} conn_ctx;

typedef void (*cmd_fn)(conn_ctx *ctx, const char *args);

#endif
