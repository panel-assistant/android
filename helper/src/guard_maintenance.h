#ifndef HAPANELD_GUARD_MAINTENANCE_H
#define HAPANELD_GUARD_MAINTENANCE_H

#include "cmd.h"
#include <stdint.h>
#include <sys/types.h>

/* Wire protocol v1. Every request token is bounded ASCII and no verb accepts a filesystem path. */
#define GUARD_PROTOCOL_VERSION 1
#define GUARD_SESSION_HEX_CHARS 64
#define GUARD_SHA256_HEX_CHARS 64
#define GUARD_EVIDENCE_MAX_BYTES 4096
#define GUARD_EXECUTOR_TIMEOUT_MS 60000U
#define GUARD_REPLACEMENT_EXIT 75
#define GUARD_REPLACEMENT_LEASE_FD 3

enum guard_replacement_parent_result {
    GUARD_REPLACEMENT_HOLD = -1,
    GUARD_REPLACEMENT_NONE = 0,
    GUARD_REPLACEMENT_READY = 1,
};

enum guard_phase {
    GUARD_PHASE_EMPTY = 0,
    GUARD_PHASE_STAGING,
    GUARD_PHASE_PREPARED,
    GUARD_PHASE_SUBMITTED_A,
    GUARD_PHASE_SUBMITTED_B,
    GUARD_PHASE_WAIT_A_HEALTH,
    GUARD_PHASE_WAIT_B_HEALTH,
    GUARD_PHASE_B_HEALTHY,
    GUARD_PHASE_RECOVERY_WITHHELD,
    GUARD_PHASE_WAIT_A_REFUSAL,
    GUARD_PHASE_A_REFUSED,
    GUARD_PHASE_RECOVERY_RESTORED,
    GUARD_PHASE_ROLLBACK_REQUIRED,
    GUARD_PHASE_ROLLBACK_A_SUBMITTED,
    GUARD_PHASE_ROLLBACK_DB_PREPARED,
    GUARD_PHASE_ROLLBACK_DB_RESTORED,
    GUARD_PHASE_A_HEALTHY,
    GUARD_PHASE_FINALIZED,
    GUARD_PHASE_RETIRING,
    GUARD_PHASE_AMBIGUOUS,
};

const char *guard_phase_name(enum guard_phase phase);

/* Reconcile durable state and start the deadline worker. Must run before the accept loop. */
int guard_maintenance_init(void);
void guard_maintenance_set_supervised(int supervised);
int guard_maintenance_supervised(void);
void guard_maintenance_set_supervisor_owner(void);
int guard_maintenance_supervisor_authoritative(void);

enum guard_supervisor_work {
    GUARD_WORK_NONE = 0,
    GUARD_WORK_INSTALL_A,
    GUARD_WORK_INSTALL_B,
    GUARD_WORK_LAUNCH_A,
    GUARD_WORK_LAUNCH_B,
};
enum guard_execution_result {
    GUARD_EXEC_NOT_STARTED = 0,
    GUARD_EXEC_REAPED,
    GUARD_EXEC_TIMED_OUT,
    GUARD_EXEC_WAIT_LOST,
};
int guard_maintenance_supervisor_tick(void);
int guard_maintenance_supervisor_work_deadline(enum guard_supervisor_work work,
                                               uint64_t *deadline_ms);
int guard_maintenance_supervisor_start_work(enum guard_supervisor_work work, pid_t *pid);
int guard_maintenance_supervisor_complete(enum guard_supervisor_work work,
                                          enum guard_execution_result result, int wait_status);

void cmd_guardcaps(conn_ctx *ctx, const char *args);
void cmd_guardself(conn_ctx *ctx, const char *args);
void cmd_guardprepare(conn_ctx *ctx, const char *args);
void cmd_guarddefine(conn_ctx *ctx, const char *args);
void cmd_guardstream(conn_ctx *ctx, const char *args);
void cmd_guardaction(conn_ctx *ctx, const char *args);
void cmd_guardhealth(conn_ctx *ctx, const char *args);
void cmd_guardrefusal(conn_ctx *ctx, const char *args);
void cmd_guardstatus(conn_ctx *ctx, const char *args);
void cmd_guardevidence(conn_ctx *ctx, const char *args);
void cmd_guardcancel(conn_ctx *ctx, const char *args);
void cmd_guardretire(conn_ctx *ctx, const char *args);

/* INSTALLSTREAM and helper replacement must refuse while Guard owns package mutation. */
int guard_maintenance_package_busy(void);
int guard_maintenance_install_begin(void);
void guard_maintenance_install_end(void);
/* Root-side helper replacement admission. Returns 0 only for a locked, truly empty namespace. */
int guard_maintenance_replacement_safe(void);
// Supervisor only, with its worker/executor reaped. Returns zero only after owned files retire.
int guard_maintenance_retire_uninstalled(void);
int guard_maintenance_replacement_parent_grant(char nonce[65]);
int guard_maintenance_replacement_export_lease(void);
int guard_maintenance_replacement_parent_abort(const char nonce[65]);
int guard_maintenance_replacement_stage_app(const char nonce[65]);
int guard_maintenance_replacement_supervisor_adopt_app(const char nonce[65]);
int guard_maintenance_replacement_worker_commit_app(const char nonce[65]);
int guard_maintenance_replacement_startup_reconcile_app(char nonce[65]);

enum guard_test_fault {
    GUARD_TEST_FAULT_NONE = 0,
    GUARD_TEST_FAULT_DRAFT_FILE_SYNC,
    GUARD_TEST_FAULT_DRAFT_RENAME,
    GUARD_TEST_FAULT_DRAFT_DIR_SYNC,
    GUARD_TEST_FAULT_ARTIFACT_FILE_SYNC,
    GUARD_TEST_FAULT_ARTIFACT_RENAME,
    GUARD_TEST_FAULT_ARTIFACT_DIR_SYNC,
    GUARD_TEST_FAULT_CAPTURE_INTENT_FILE_SYNC,
    GUARD_TEST_FAULT_CAPTURE_INTENT_RENAME,
    GUARD_TEST_FAULT_CAPTURE_INTENT_DIR_SYNC,
    GUARD_TEST_FAULT_CAPTURE_STOP_FILE_SYNC,
    GUARD_TEST_FAULT_CAPTURE_STOP_RENAME,
    GUARD_TEST_FAULT_CAPTURE_STOP_DIR_SYNC,
    GUARD_TEST_FAULT_CAPTURE_LEGACY_DRAFT_REMOVED,
    GUARD_TEST_FAULT_CAPTURE_CLEANUP_AFTER_CAPTURE,
    GUARD_TEST_FAULT_MANIFEST_FILE_SYNC,
    GUARD_TEST_FAULT_MANIFEST_RENAME,
    GUARD_TEST_FAULT_MANIFEST_DIR_SYNC,
    GUARD_TEST_FAULT_JOURNAL_FILE_SYNC,
    GUARD_TEST_FAULT_JOURNAL_RENAME,
    GUARD_TEST_FAULT_JOURNAL_DIR_SYNC,
    GUARD_TEST_FAULT_BASELINE_COPY,
    GUARD_TEST_FAULT_BASELINE_RENAME,
    GUARD_TEST_FAULT_BASELINE_DIR_SYNC,
    GUARD_TEST_FAULT_PREMIGRATE_FILE_SYNC,
    GUARD_TEST_FAULT_PREMIGRATE_RENAME,
    GUARD_TEST_FAULT_PREMIGRATE_DIR_SYNC,
    GUARD_TEST_FAULT_RECOVERY_CUSTODY_PUBLISHED,
    GUARD_TEST_FAULT_WITHHOLD_AFTER_INTENT,
    GUARD_TEST_FAULT_WITHHOLD_AFTER_FORCE_STOP,
    GUARD_TEST_FAULT_RESTORE_FILE_SYNC,
    GUARD_TEST_FAULT_RESTORE_TEMP_CREATED,
    GUARD_TEST_FAULT_RESTORE_RENAME,
    GUARD_TEST_FAULT_RESTORE_DIR_SYNC,
    GUARD_TEST_FAULT_RESTORE_AFTER_INTENT,
    GUARD_TEST_FAULT_RESTORE_AFTER_FORCE_STOP,
    GUARD_TEST_FAULT_DB_AFTER_ASIDE,
    GUARD_TEST_FAULT_DB_AFTER_PROMOTE,
    GUARD_TEST_FAULT_REPLACEMENT_FILE_SYNC,
    GUARD_TEST_FAULT_REPLACEMENT_RENAME,
    GUARD_TEST_FAULT_REPLACEMENT_DIR_SYNC,
    GUARD_TEST_FAULT_REPLACEMENT_AFTER_BACKUP,
    GUARD_TEST_FAULT_REPLACEMENT_AFTER_SWAP,
    GUARD_TEST_FAULT_REPLACEMENT_BACKUP_DIR_SYNC,
    GUARD_TEST_FAULT_REPLACEMENT_SWAPPED_DIR_SYNC,
    GUARD_TEST_FAULT_REPLACEMENT_REQUESTED_TMP_SYNCED,
    GUARD_TEST_FAULT_REPLACEMENT_GRANTED_TMP_SYNCED,
    GUARD_TEST_FAULT_REPLACEMENT_BACKUP_TMP_SYNCED,
    GUARD_TEST_FAULT_REPLACEMENT_SWAPPED_TMP_SYNCED,
    GUARD_TEST_FAULT_REPLACEMENT_STAGE_FILE_SYNC,
    GUARD_TEST_FAULT_REPLACEMENT_STAGE_DIR_SYNC,
    GUARD_TEST_FAULT_REPLACEMENT_ABORT_AFTER_PREVIOUS,
    GUARD_TEST_FAULT_CANCEL_AFTER_ARTIFACT,
    GUARD_TEST_FAULT_CANCEL_AFTER_DRAFT,
    GUARD_TEST_FAULT_RETIRE_AFTER_A,
    GUARD_TEST_FAULT_RETIRE_AFTER_B,
    GUARD_TEST_FAULT_RETIRE_AFTER_SETTINGS,
    GUARD_TEST_FAULT_RETIRE_AFTER_BASELINE,
    GUARD_TEST_FAULT_RETIRE_AFTER_PREMIGRATE,
    GUARD_TEST_FAULT_RETIRE_AFTER_B_PRIMARY,
    GUARD_TEST_FAULT_RETIRE_AFTER_MANIFEST,
    GUARD_TEST_FAULT_RETIRE_JOURNAL_UNLINKED,
    GUARD_TEST_FAULT_RETIRE_AFTER_JOURNAL,
};
#ifdef HAPANELD_TEST
void guard_test_reset(void);
void guard_test_set_fault(enum guard_test_fault fault);
void guard_test_set_supervised(int supervised);
void guard_test_set_now_ms(uint64_t now_ms);
void guard_test_set_pm_process_state(int state);
void guard_test_set_app_autonomous_profile(int enabled);
void guard_test_drop_runtime(void);
int guard_test_reconcile(void);
int guard_test_restore_baseline_now(void);
int guard_test_final_restore_receipt_exact(void);
#endif

#endif
