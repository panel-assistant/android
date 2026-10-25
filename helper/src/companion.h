// Descriptor-anchored HA Companion backup/restore boundary. The public protocol accepts only the two
// supported Companion packages and the fixed login-file set; callers never supply a filesystem path.
#ifndef HAPANELD_COMPANION_H
#define HAPANELD_COMPANION_H

#include "cmd.h"

enum companion_launch_guard {
    COMPANION_GUARD_BUSY = -1,
    COMPANION_GUARD_NOT_APPLICABLE = 0,
    COMPANION_GUARD_ACQUIRED = 1,
};

void cmd_companioncaps(conn_ctx *ctx, const char *args);
void cmd_companionstatus(conn_ctx *ctx, const char *args);
void cmd_companionbackup(conn_ctx *ctx, const char *args);
void cmd_companionrestore(conn_ctx *ctx, const char *args);

int companion_guard_package_launch(const char *pkg);
int companion_guard_component_launch(const char *component);
void companion_guard_release(int guard);

// Called by the supervisor only after its worker has been reaped. Pending restore custody holds.
int companion_retire_uninstalled(void);

#ifdef HAPANELD_TEST
enum companion_test_fault {
    COMPANION_TEST_FAULT_NONE = 0,
    COMPANION_TEST_FAULT_AFTER_MOVES,
    COMPANION_TEST_FAULT_AFTER_INSTALLS,
    COMPANION_TEST_FAULT_AFTER_COMMIT_MARKER,
};
void companion_test_set_fault(enum companion_test_fault fault);
#endif

#endif
