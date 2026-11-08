#ifndef HAPANELD_ADB_RECOVERY_H
#define HAPANELD_ADB_RECOVERY_H
#include "cmd.h"
int adb_recovery_ensure(void);
int adb_recovery_main(void);
void cmd_adb_recovery(conn_ctx *ctx, const char *args);
int adb_recovery_owner_init(void);
void adb_recovery_owner_poll(int timeout_ms);
void adb_recovery_owner_worker(void);
int adb_recovery_owner_recover(void);
int adb_recovery_owner_worker_lost(void);
int adb_recovery_owner_drain(void);
int adb_recovery_bootstrap(void);
int adb_recovery_legacy_main(const char *name);
#endif
