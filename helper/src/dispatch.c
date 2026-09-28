#include "dispatch.h"

#include <stdio.h>
#include <string.h>

#include "led.h"
#include "screen.h"
#include "input.h"
#include "gpio.h"
#include "sysctl.h"
#include "perf.h"
#include "cht8305.h"
#include "vi530x.h"
#include "companion.h"
#include "guard_maintenance.h"
#include "identity.h"
#include "server.h"
#include "util.h"
#include "version.h"
#include "logcat.h"

static void cmd_ping(conn_ctx *ctx, const char *args) { (void)args; reply(ctx->fd, "OK\n"); }
static void cmd_buildid(conn_ctx *ctx, const char *args) {
    (void)args;
    reply(ctx->fd, helper_build_id_record());
    reply(ctx->fd, "\n");
}

// Whether the daemon a client is talking to is the DUAL-UID one. A client cannot infer that from
// BUILDID, which is an opaque source hash it can only compare against the build it bundles — and the
// bridge has to know before it installs and launches the successor at all, because a successor that
// comes up on a single-uid helper has no root on a SuperSU panel and no way to ask for it.
//
// BUILDID's own record is deliberately left alone: the provisioner and the app parse that exact
// format, so the answer goes in its own verb rather than widening one that already has consumers.
// CALLER is the identity this connection authenticated as, which is itself the proof for a caller
// that reached the socket at all — a single-uid helper would have closed the successor's connection
// before its first command.
static void cmd_helperstatus(conn_ctx *ctx, const char *args) {
    if (*args != '\0') {
        reply(ctx->fd, "ERR\n");
        return;
    }
    char record[MAX_LINE + 1];
    int written = snprintf(record, sizeof record, "HELPERSTATUS 1 BUILD=%s CALLER=%s PACKAGES=",
                           helper_build_id(), helper_caller_name(ctx->caller));
    if (written < 0 || (size_t)written >= sizeof record) {
        reply(ctx->fd, "ERR\n");
        return;
    }
    size_t used = (size_t)written;
    for (size_t i = 0; i < HELPER_APP_PACKAGE_COUNT; i++) {
        int appended = snprintf(record + used, sizeof record - used, "%s%s",
                                i == 0 ? "" : ",", HELPER_APP_PACKAGES[i].package);
        if (appended < 0 || (size_t)appended >= sizeof record - used) {
            reply(ctx->fd, "ERR\n");
            return;
        }
        used += (size_t)appended;
    }
    reply(ctx->fd, record);
    reply(ctx->fd, "\n");
}

// Handlers live in the capability module that owns the verb (led.c, screen.c, …). commands.def is the
// single manifest shared with the sanitizer smoke harness, so a new live verb cannot be omitted there.
static const struct { const char *verb; cmd_fn fn; } COMMANDS[] = {
#define COMMAND(verb, handler) { #verb, handler },
#include "commands.def"
#undef COMMAND
};

void dispatch(conn_ctx *ctx, char *line) {
    while (*line == ' ' || *line == '\t') line++;     // trim leading whitespace

    // Verb = the first whitespace-delimited token, copied out bounded (an overlong token matches
    // nothing → ERR, so it can't overflow or be mis-parsed).
    char verb[24];
    size_t v = 0;
    while (line[v] && line[v] != ' ' && line[v] != '\t') {
        if (v < sizeof verb - 1) verb[v] = line[v];
        v++;
    }
    verb[v < sizeof verb ? v : sizeof verb - 1] = '\0';

    const char *args = line + v;                      // remainder after the verb token…
    while (*args == ' ' || *args == '\t') args++;      // …with leading spaces trimmed

    for (size_t i = 0; i < sizeof COMMANDS / sizeof COMMANDS[0]; i++)
        if (v < sizeof verb && strcmp(verb, COMMANDS[i].verb) == 0) {
            COMMANDS[i].fn(ctx, args);
            return;
        }
    reply(ctx->fd, "ERR\n");
}
