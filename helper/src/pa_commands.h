// Typed Panel Assistant commands (protocol 1.17).
//
// Panel Assistant used to send its steady-state repairs to the app as root shell programs. These
// eight verbs do the same work with fixed argv and typed, allowlisted arguments, so no request byte
// reaches a command line unless it is an exact member of a table in pa_allowlist.h. Only the app may
// call them; a root shell gets `ERR caller`.
//
// Reply grammar: `OK <n>\n` followed by exactly n (at most 4096) bytes of `key=value\n` lines that
// start with the identity keys, or `ERR <code>\n` with code caller, args, deadline, busy, critical,
// home-unsafe or failed.
#ifndef HAPANELD_PA_COMMANDS_H
#define HAPANELD_PA_COMMANDS_H

#include "cmd.h"

void cmd_pa_identity(conn_ctx *ctx, const char *args);
void cmd_pa_repair(conn_ctx *ctx, const char *args);
void cmd_pa_home(conn_ctx *ctx, const char *args);
void cmd_pa_admin(conn_ctx *ctx, const char *args);
void cmd_pa_vendor(conn_ctx *ctx, const char *args);
void cmd_pa_restore(conn_ctx *ctx, const char *args);
void cmd_pa_observe(conn_ctx *ctx, const char *args);
void cmd_pa_handback(conn_ctx *ctx, const char *args);

#ifdef HAPANELD_TEST
extern const char *pa_test_boot_id_path; // OBSERVE recovery reads this in place of the kernel boot id
#endif

#endif
