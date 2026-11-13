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

// The owner-decision rules PA_REPAIR applies, for GRANT (sysctl.c) to apply to the app's own package,
// so the app's startup repair and Panel Assistant's repair cannot drift apart. A runtime permission is
// granted only when every `dumpsys package` line proves it denied and none records a decision
// (USER_SET, USER_FIXED or POLICY_FIXED); an app op is set to allow only when Android proves it unset.
// Each returns 0 when the grant holds afterwards and -1 when it was kept, unreadable or failed.
int pa_grant_runtime_permission(const char *package, const char *permission);
int pa_grant_app_op(const char *package, const char *op);

#ifdef HAPANELD_TEST
extern const char *pa_test_boot_id_path; // OBSERVE recovery reads this in place of the kernel boot id
#endif

#endif
