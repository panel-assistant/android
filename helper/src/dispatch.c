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
#include "zigbee.h"
#include "wifi.h"
#include "adb_state.h"
#include "adb_recovery.h"
#include "sysfs_read.h"
#include "pa_commands.h"

static void cmd_ping(conn_ctx *ctx, const char *args) { (void)args; reply(ctx->fd, "OK\n"); }
static void cmd_buildid(conn_ctx *ctx, const char *args) {
    (void)args;
    reply(ctx->fd, helper_build_id_record());
    reply(ctx->fd, "\n");
}

static void cmd_adbrecoveryensure(conn_ctx *ctx, const char *args) {
    if (*args || (ctx->caller != HELPER_CALLER_ROOT && ctx->caller != HELPER_CALLER_SUCCESSOR)) {
        reply(ctx->fd, "ERR\n"); return;
    }
    reply(ctx->fd, adb_recovery_ensure() ? "ADBRECOVERYENSURE READY\n" : "ADBRECOVERYENSURE UNAVAILABLE\n");
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
