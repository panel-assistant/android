// Shared private definitions of the Guard maintenance module: paths, records and the state the
// replacement-lease code in guard_replacement.c shares with guard_maintenance.c. Not a public API.
#ifndef HAPANELD_GUARD_MAINTENANCE_INTERNAL_H
#define HAPANELD_GUARD_MAINTENANCE_INTERNAL_H

#include "guard_maintenance.h"
#include "companion.h"
#include "sha256.h"
#include "sysexec.h"
#include "util.h"
#include "version.h"
#include <errno.h>
#include <dirent.h>
#include <fcntl.h>
#include <inttypes.h>
#include <limits.h>
#include <pthread.h>
#include <signal.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <sys/statvfs.h>
#include <sys/types.h>
#include <sys/wait.h>
#include <sys/file.h>
#include <sys/xattr.h>
#include <time.h>
#include <unistd.h>

#ifdef HAPANELD_TEST
#include "test_tmp.h"
#define GUARD_PARENT HAPANELD_TEST_TMP
#define GUARD_DIR_NAME ".hapaneld-guard-db-test"
#define GUARD_APP_USER_ROOT HAPANELD_TEST_TMP "/.hapaneld-guard-app-test/data/user/0"
#define GUARD_BOOT_ID HAPANELD_TEST_TMP "/.hapaneld-guard-db-test.boot-id"
#define GUARD_INSTALLED_APK HAPANELD_TEST_TMP "/.hapaneld-guard-installed/base.apk"
#else
#define GUARD_PARENT "/data/local"
#define GUARD_DIR_NAME ".hapaneld-guard-db"
#define GUARD_APP_USER_ROOT "/data/user/0"
#define GUARD_BOOT_ID "/proc/sys/kernel/random/boot_id"
#endif

#define GUARD_DIR GUARD_PARENT "/" GUARD_DIR_NAME
// The package a session acts on is bound once, at GUARDPREPARE, from the caller's SO_PEERCRED
// identity, and lives in the durable plan; every later verb and every reconcile derives the package
// id, its database directory, `pm path`, `am force-stop` and `monkey -p` target from that record.
// GUARD_DB_NAME and every restore-record, schema and metadata identifier stay byte-identical,
// because shipped 0.4.1 verifiers compare them exactly.
#define GUARD_DB_NAME "ha-paneld.db"
#define GUARD_APP_PATH_BYTES 512
#define GUARD_PACKAGE_BYTES 64
#define GUARD_DRAFT "draft.v1"
#define GUARD_DRAFT_TMP ".draft.v1.tmp"
#define GUARD_MANIFEST "manifest.v1"
#define GUARD_MANIFEST_TMP ".manifest.v1.tmp"
#define GUARD_JOURNAL "journal.v1"
#define GUARD_JOURNAL_TMP ".journal.v1.tmp"
#define GUARD_CAPTURE "capture.v1"
#define GUARD_CAPTURE_TMP ".capture.v1.tmp"
#define GUARD_REPLACEMENT "replacement.v1"
#define GUARD_REPLACEMENT_TMP ".replacement.v1.tmp"
#define GUARD_A_APK "a.apk"
#define GUARD_B_APK "b.apk"
#define GUARD_A_UPLOAD ".a.apk.upload"
#define GUARD_B_UPLOAD ".b.apk.upload"
#define GUARD_BASELINE "baseline.db"
#define GUARD_BASELINE_TMP ".baseline.db.tmp"
#define GUARD_PREMIGRATE "premigrate.db"
#define GUARD_PREMIGRATE_TMP ".premigrate.db.tmp"
#define GUARD_B_PRIMARY "b-primary.db"
#define GUARD_B_PRIMARY_TMP ".b-primary.db.tmp"
#define GUARD_HEALTH_COPY "health.db"
#define GUARD_HEALTH_COPY_TMP ".health.db.tmp"
#define GUARD_SETTINGS "settings.v2"
#define GUARD_SETTINGS_UPLOAD ".settings.v2.upload"
#define GUARD_OWNER_LOCK ".owner.lock"
#define GUARD_A_APK_PATH GUARD_DIR "/" GUARD_A_APK
#define GUARD_B_APK_PATH GUARD_DIR "/" GUARD_B_APK
#define GUARD_HEALTH_COPY_URI "file:" GUARD_DIR "/" GUARD_HEALTH_COPY "?immutable=1"
#define GUARD_ROLLBACK_TMP ".guard.rollback.tmp"
#define GUARD_APP_RESTORE_RECORD ".ha-paneld.db.restore.v1"
#define GUARD_APP_RESTORE_RECORD_TMP ".ha-paneld.db.restore.v1.tmp"
#define GUARD_APP_RESTORE_PREPARED ".ha-paneld.db.restore.prepared.v1"
#define GUARD_APP_RESTORE_MAX_RECORD_BYTES 2048
#define GUARD_REPLACEMENT_MAX_BINARY_BYTES (16ULL * 1024ULL * 1024ULL)
#ifdef HAPANELD_TEST
#define GUARD_APP_HELPER_PARENT HAPANELD_TEST_TMP
#define GUARD_APP_HELPER_LIVE ".hapaneld-helper-live-test"
#define GUARD_APP_HELPER_STAGE ".hapaneld-helper-live-test.new"
#define GUARD_APP_HELPER_PREVIOUS ".hapaneld-helper-live-test.previous"
#define GUARD_APP_HELPER_PREVIOUS_TMP ".hapaneld-helper-live-test.previous.tmp"
#define HELPER_TRANSACTION_LOCK_PARENT HAPANELD_TEST_TMP
#define HELPER_TRANSACTION_LOCK_NAME ".hapaneld-helper-transaction-lock-test"
#else
#define GUARD_APP_HELPER_PARENT "/data/local"
#define GUARD_APP_HELPER_LIVE "hapaneld-helper"
#define GUARD_APP_HELPER_STAGE ".hapaneld-helper.new"
#define GUARD_APP_HELPER_PREVIOUS ".hapaneld-helper.previous"
#define GUARD_APP_HELPER_PREVIOUS_TMP ".hapaneld-helper.previous.tmp"
#define HELPER_TRANSACTION_LOCK_PARENT "/dev"
#define HELPER_TRANSACTION_LOCK_NAME ".hapaneld-helper-transaction.lock"
#endif
#define HELPER_TRANSACTION_LOCK_PID "pid"
#define GUARD_MAX_RECORD_BYTES 4096
#define GUARD_MAX_APK_BYTES (256ULL * 1024ULL * 1024ULL)
#define GUARD_MAX_DB_BYTES (64ULL * 1024ULL * 1024ULL)
#define GUARD_MAX_SETTINGS_BYTES (256ULL * 1024ULL)
#define GUARD_STORAGE_HEADROOM (64ULL * 1024ULL * 1024ULL)
#define GUARD_TOKEN_COUNT 48
#define GUARD_LABEL_BYTES 256
#define GUARD_HEALTH_TIMEOUT_MS (2ULL * 60ULL * 1000ULL)
#define GUARD_OVERALL_MAX_MS (30ULL * 60ULL * 1000ULL)
#define GUARD_RECOVERY_RESERVE_MS (8ULL * 60ULL * 1000ULL)
#define GUARD_FORWARD_MIN_MS (2ULL * 60ULL * 1000ULL)
#define GUARD_SQLITE_PROOF_MAX_BYTES (8ULL * 1024ULL * 1024ULL)
#define GUARD_SQLITE_PROOF_TIMEOUT_MS 5000U
#define GUARD_AM_TIMEOUT_MS 10000U
#define GUARD_SQLITE_PATH "/system/bin/sqlite3"
#define GUARD_PROBE_TABLE "db_compatibility_canary_v15"

typedef struct {
    int defined;
    int staged;
    uint64_t bytes;
    char sha[65];
    uint64_t version_code;
    uint32_t contract_min;
    uint32_t contract_max;
    uint32_t expected_schema;
} guard_artifact;

typedef struct {
    char session[65];
    char boot[65];
    char signer[65];
    char package[GUARD_PACKAGE_BYTES];
    uint64_t generation;
    uint64_t overall_deadline_ms;
    uint64_t forward_deadline_ms;
    uint32_t settings_authority_version;
    uint64_t settings_authority_bytes;
    char settings_authority_sha[65];
    int settings_authority_staged;
    uint64_t baseline_bytes;
    char baseline_sha[65];
    uint32_t baseline_schema;
    uint64_t baseline_app_state;
    char baseline_app_state_sha[65];
    char baseline_settings_sha[65];
    guard_artifact a;
    guard_artifact b;
    int captured;
    uint64_t db_dir_dev;
    uint64_t db_dir_ino;
    uint64_t db_dev;
    uint64_t db_ino;
    uint64_t db_uid;
    uint64_t db_gid;
    uint32_t db_mode;
    char db_label[GUARD_LABEL_BYTES];
} guard_plan;

typedef struct {
    char session[65];
    uint64_t manifest_generation;
    char manifest_sha[65];
    uint64_t generation;
    enum guard_phase phase;
    char role[5];
    char installed_sha[65];
    uint64_t version_code;
    uint32_t schema;
    uint64_t deadline_ms;
    char error[33];
    char outcome[33];
    int pm_settled;
    int pm_spawned;
    uint64_t pm_pid;
    uint64_t pm_start_ticks;
    int rollback_attempt_consumed;
    uint64_t recovery_deadline_ms;
    uint64_t premigrate_bytes;
    char premigrate_sha[65];
    uint64_t b_primary_bytes;
    char b_primary_sha[65];
    char retirement_sha[65];
} guard_journal;

enum guard_capture_state {
    GUARD_CAPTURE_INTENT = 0,
    GUARD_CAPTURE_STOP_ATTEMPTED,
    GUARD_CAPTURE_FAILED_LAUNCHING,
    GUARD_CAPTURE_FAILED_NO_MUTATION,
    GUARD_CAPTURE_FAILED_LAUNCH_ATTEMPTED,
    GUARD_CAPTURE_CANCEL_INTENT,
};

typedef struct {
    char session[65];
    uint64_t draft_generation;
    char draft_sha[65];
    enum guard_capture_state state;
} guard_capture;

enum guard_replacement_phase {
    GUARD_REPLACEMENT_REQUESTED = 0,
    GUARD_REPLACEMENT_GRANTED,
    GUARD_REPLACEMENT_BACKUP_DURABLE,
    GUARD_REPLACEMENT_SWAPPED,
};

typedef struct {
    char boot[65];
    char nonce[65];
    enum guard_replacement_phase phase;
    uint64_t generation;
    uint64_t old_bytes;
    char old_sha[65];
    uint64_t old_dev;
    uint64_t old_ino;
    uint64_t new_bytes;
    char new_sha[65];
    uint64_t new_dev;
    uint64_t new_ino;
    char new_build[65];
} guard_replacement;

typedef struct {
    int present;
    struct stat stat;
} guard_file_snapshot;

typedef struct {
    int parent;
    int dir;
    struct stat dir_stat;
    struct stat pid_stat;
} helper_transaction_lock;

enum helper_transaction_lock_result {
    HELPER_TRANSACTION_LOCK_HOLD = -1,
    HELPER_TRANSACTION_LOCK_ACQUIRED = 0,
    HELPER_TRANSACTION_LOCK_BUSY = 1,
};

enum guard_publish_result {
    GUARD_PUBLISH_FAILED = -1,
    GUARD_PUBLISH_COMMITTED = 0,
    GUARD_PUBLISH_INDETERMINATE = 1,
};

extern int guard_initialized;
extern pthread_mutex_t guard_lock;
extern int guard_owner_fd;
#ifdef HAPANELD_TEST
extern enum guard_test_fault test_fault;
#define TEST_FAULT(value) (test_fault == (value))
#else
#define TEST_FAULT(value) ((void)(value), 0)
#endif
int app_helper_identity_at(int dir, const char *name, uint64_t *bytes,
                                  char sha[65], uint64_t *dev, uint64_t *ino);
int app_helper_identity_matches(int dir, const char *name, uint64_t bytes,
                                       const char *sha, uint64_t dev, uint64_t ino);
int empty_inventory_safe(int dir);
int known_file_present(int dir, const char *name);
int load_replacement_reconciled(int dir, guard_replacement *replacement);
int open_app_helper_parent(void);
int open_guard_dir(void);
int remove_fixed_nondir(int dir, const char *name);
int replacement_boot_current(const guard_replacement *replacement);
int replacement_old_caller_exact(const guard_replacement *replacement);
int replacement_request_topology_exact(const guard_replacement *replacement);
int store_replacement_at(int dir, const guard_replacement *replacement);

#endif
