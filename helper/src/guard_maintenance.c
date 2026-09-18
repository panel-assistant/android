#define _GNU_SOURCE
#include "guard_maintenance.h"

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
#define GUARD_PARENT "/tmp"
#define GUARD_DIR_NAME ".hapaneld-guard-db-test"
#define GUARD_APP_USER_ROOT "/tmp/.hapaneld-guard-app-test/data/user/0"
#define GUARD_BOOT_ID "/tmp/.hapaneld-guard-db-test.boot-id"
#define GUARD_INSTALLED_APK "/tmp/.hapaneld-guard-installed/base.apk"
#else
#define GUARD_PARENT "/data/local"
#define GUARD_DIR_NAME ".hapaneld-guard-db"
#define GUARD_APP_USER_ROOT "/data/user/0"
#define GUARD_BOOT_ID "/proc/sys/kernel/random/boot_id"
#endif

#define GUARD_DIR GUARD_PARENT "/" GUARD_DIR_NAME
// Guard serves TWO app packages during the identity migration, so it can no longer name one at
// compile time.  The package a session acts on is bound once, at GUARDPREPARE, from the caller's
// SO_PEERCRED identity, and lives in the durable plan; every later verb and every reconcile derives
// the package id, its database directory, `pm path`, `am force-stop` and `monkey -p` target from
// that record.  Only the directory that CONTAINS the database varies: GUARD_DB_NAME and every
// restore-record, schema and metadata identifier stay byte-identical for both packages, because
// shipped 0.4.1 verifiers compare them exactly.
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
#define GUARD_APP_HELPER_PARENT "/tmp"
#define GUARD_APP_HELPER_LIVE ".hapaneld-helper-live-test"
#define GUARD_APP_HELPER_STAGE ".hapaneld-helper-live-test.new"
#define GUARD_APP_HELPER_PREVIOUS ".hapaneld-helper-live-test.previous"
#define GUARD_APP_HELPER_PREVIOUS_TMP ".hapaneld-helper-live-test.previous.tmp"
#define HELPER_TRANSACTION_LOCK_PARENT "/tmp"
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

static pthread_mutex_t guard_lock = PTHREAD_MUTEX_INITIALIZER;
static pthread_mutex_t package_gate = PTHREAD_MUTEX_INITIALIZER;
static int guard_initialized;
static int guard_supervised;
static int guard_supervisor_owner;
static pid_t guard_supervisor_pid = -1;
static int guard_owner_fd = -1;

static int known_file_present(int dir, const char *name);
static int empty_inventory_safe(int dir);
static int retirement_owner_lock_exact(int dir);
static int plans_compatible(const guard_plan *plan);
static int open_guard_dir(void);
static int load_replacement_at(int dir, guard_replacement *replacement);
static int load_replacement_reconciled(int dir, guard_replacement *replacement);
static uint64_t monotonic_ms(void);
static int load_manifest_reconciled(int dir, guard_plan *plan, guard_journal *journal,
                                    int repair_missing_journal);
static int validate_settings_authority_at(int dir, const guard_plan *plan);
static int snapshot_regular_at(int dir, const char *name, int required,
                               guard_file_snapshot *snapshot);
static int snapshot_unchanged(const guard_file_snapshot *before,
                              const guard_file_snapshot *after);
static int load_capture_at(int dir, guard_capture *capture);
static int db_dir_matches_plan(int db_dir, const guard_plan *plan);
static int finish_cancel_locked(int dir, const guard_plan *plan,
                                const guard_capture *capture);
static int reconcile_orphaned_cancel_at(int dir);
static int reconcile_terminal_retirement_at(int dir);

#ifdef HAPANELD_TEST
static enum guard_test_fault test_fault;
static uint64_t test_now_ms;
static int test_pm_process_state;
static int test_app_autonomous_profile;
#define TEST_FAULT(value) (test_fault == (value))
#else
#define TEST_FAULT(value) ((void)(value), 0)
#endif

static int lower_hex_64(const char *value) {
    if (!value || strlen(value) != 64) return 0;
    for (size_t i = 0; i < 64; i++)
        if (!((value[i] >= '0' && value[i] <= '9') ||
              (value[i] >= 'a' && value[i] <= 'f'))) return 0;
    return 1;
}

static int parse_u64(const char *text, uint64_t minimum, uint64_t maximum, uint64_t *value) {
    if (!text || !*text) return -1;
    if (text[0] == '0' && text[1] != '\0') return -1;
    for (const char *p = text; *p; p++) if (*p < '0' || *p > '9') return -1;
    errno = 0;
    char *end = NULL;
    unsigned long long parsed = strtoull(text, &end, 10);
    if (errno != 0 || !end || *end != '\0' || parsed < minimum || parsed > maximum) return -1;
    *value = (uint64_t)parsed;
    return 0;
}

static int parse_u32(const char *text, uint32_t minimum, uint32_t maximum, uint32_t *value) {
    uint64_t parsed;
    if (parse_u64(text, minimum, maximum, &parsed) != 0) return -1;
    *value = (uint32_t)parsed;
    return 0;
}

static int split_tokens(const char *args, char storage[513], char *tokens[], size_t capacity) {
    if (!args || strlen(args) > 512) return -1;
    size_t length = strlen(args);
    memcpy(storage, args, length + 1);
    size_t count = 0;
    char *p = storage;
    while (*p) {
        while (*p == ' ' || *p == '\t') p++;
        if (!*p) break;
        if (count == capacity) return -1;
        tokens[count++] = p;
        while (*p && *p != ' ' && *p != '\t') {
            unsigned char c = (unsigned char)*p;
            if (c < 0x21 || c > 0x7e) return -1;
            p++;
        }
        if (*p) *p++ = '\0';
    }
    return (int)count;
}

static void error_reply(conn_ctx *ctx, const char *code, const char *token) {
    char line[160];
    snprintf(line, sizeof line, "ERR %s %s\n", code, token);
    reply(ctx->fd, line);
}

static void ok_reply(conn_ctx *ctx, const char *verb, uint64_t generation, enum guard_phase phase) {
    char line[160];
    snprintf(line, sizeof line, "OK %s %" PRIu64 " %s\n", verb, generation, guard_phase_name(phase));
    reply(ctx->fd, line);
}

static void publish_reply(conn_ctx *ctx, int result, const char *detail, const char *verb,
                          uint64_t generation, enum guard_phase phase) {
    if (result == GUARD_PUBLISH_INDETERMINATE)
        error_reply(ctx, "INDETERMINATE", detail);
    else if (result != GUARD_PUBLISH_COMMITTED)
        error_reply(ctx, "IO", detail);
    else
        ok_reply(ctx, verb, generation, phase);
}

static int guard_admission_ready(conn_ctx *ctx) {
    if (guard_initialized) {
        pthread_mutex_lock(&guard_lock);
        int dir = open_guard_dir();
        guard_replacement replacement;
        int fenced = dir < 0 || load_replacement_reconciled(dir, &replacement) != 0;
        if (dir >= 0) close(dir);
        if (fenced) guard_initialized = 0;
        pthread_mutex_unlock(&guard_lock);
        if (!fenced) return 1;
    }
    error_reply(ctx, "HOLD", "startup");
    return 0;
}

const char *guard_phase_name(enum guard_phase phase) {
    static const char *const names[] = {
        "EMPTY", "STAGING", "PREPARED", "SUBMITTED_A", "SUBMITTED_B",
        "WAIT_A_HEALTH", "WAIT_B_HEALTH", "B_HEALTHY", "RECOVERY_WITHHELD",
        "WAIT_A_REFUSAL", "A_REFUSED", "RECOVERY_RESTORED", "ROLLBACK_REQUIRED",
        "ROLLBACK_A_SUBMITTED", "ROLLBACK_DB_PREPARED", "ROLLBACK_DB_RESTORED",
        "A_HEALTHY", "FINALIZED", "RETIRING", "AMBIGUOUS",
    };
    return phase >= GUARD_PHASE_EMPTY && phase <= GUARD_PHASE_AMBIGUOUS
        ? names[(unsigned)phase] : "AMBIGUOUS";
}

static int open_guard_dir(void) {
    int parent = open(GUARD_PARENT, O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC);
    if (parent < 0) return -1;
    struct stat parent_st;
    if (fstat(parent, &parent_st) != 0 || !S_ISDIR(parent_st.st_mode) ||
        (parent_st.st_uid != 0 && parent_st.st_uid != geteuid()) ||
        ((parent_st.st_mode & (S_IWGRP | S_IWOTH)) != 0 &&
         (parent_st.st_mode & S_ISVTX) == 0)) {
        close(parent);
        return -1;
    }
    if (mkdirat(parent, GUARD_DIR_NAME, 0700) != 0 && errno != EEXIST) {
        close(parent);
        return -1;
    }
    int dir = openat(parent, GUARD_DIR_NAME, O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC);
    close(parent);
    if (dir < 0) return -1;
    struct stat st;
    if (fstat(dir, &st) != 0 || !S_ISDIR(st.st_mode) || st.st_uid != geteuid() ||
        fchmod(dir, 0700) != 0 || flock(dir, LOCK_EX) != 0) {
        close(dir);
        return -1;
    }
    return dir;
}

static int remove_fixed_nondir(int dir, const char *name) {
    struct stat st;
    if (fstatat(dir, name, &st, AT_SYMLINK_NOFOLLOW) != 0) return errno == ENOENT ? 0 : -1;
    if (S_ISDIR(st.st_mode)) return -1;
    return unlinkat(dir, name, 0);
}

static int check_space(uint64_t wanted) {
    struct statvfs space;
    if (statvfs(GUARD_PARENT, &space) != 0 || space.f_bavail == 0 || space.f_frsize == 0 ||
        wanted > UINT64_MAX - GUARD_STORAGE_HEADROOM ||
        (uint64_t)space.f_bavail > UINT64_MAX / (uint64_t)space.f_frsize) return -1;
    return (uint64_t)space.f_bavail * (uint64_t)space.f_frsize >=
        wanted + GUARD_STORAGE_HEADROOM ? 0 : -1;
}

static int hash_regular_at(int dir, const char *name, uint64_t bytes, const char *sha) {
    int fd = openat(dir, name, O_RDONLY | O_NOFOLLOW | O_CLOEXEC);
    if (fd < 0) return -1;
    struct stat st;
    char actual[65];
    int ok = fstat(fd, &st) == 0 && S_ISREG(st.st_mode) && st.st_uid == geteuid() &&
        (st.st_mode & 0777) == 0400 &&
        st.st_nlink == 1 && (uint64_t)st.st_size == bytes &&
        hapaneld_sha256_fd(fd, bytes, actual) == 0 && strcmp(actual, sha) == 0;
    close(fd);
    return ok ? 0 : -1;
}

static int current_boot_nonce(char out[65]) {
    int fd = open(GUARD_BOOT_ID, O_RDONLY | O_NOFOLLOW | O_CLOEXEC);
    if (fd < 0) return -1;
    unsigned char raw[128];
    ssize_t count;
    do count = read(fd, raw, sizeof raw); while (count < 0 && errno == EINTR);
    close(fd);
    if (count <= 0 || count == (ssize_t)sizeof raw) return -1;
    while (count > 0 && (raw[count - 1] == '\n' || raw[count - 1] == '\r')) count--;
    if (count != 36) return -1;
    for (ssize_t i = 0; i < count; i++) {
        int hyphen = i == 8 || i == 13 || i == 18 || i == 23;
        if (hyphen ? raw[i] != '-' :
            !((raw[i] >= '0' && raw[i] <= '9') || (raw[i] >= 'a' && raw[i] <= 'f')))
            return -1;
    }
    hapaneld_sha256 hash;
    unsigned char digest[32];
    static const unsigned char domain[] = "hapaneld-guard-boot-v1";
    hapaneld_sha256_init(&hash);
    hapaneld_sha256_update(&hash, domain, sizeof domain);
    hapaneld_sha256_update(&hash, raw, (size_t)count);
    hapaneld_sha256_final(&hash, digest);
    hapaneld_sha256_hex(digest, out);
    return 0;
}

static int plan_boot_is_current(const guard_plan *plan) {
    char boot[65];
    return current_boot_nonce(boot) == 0 && strcmp(plan->boot, boot) == 0;
}

static int body_with_checksum(const char *body, char *output, size_t capacity) {
    hapaneld_sha256 hash;
    unsigned char digest[32];
    char hex[65];
    hapaneld_sha256_init(&hash);
    hapaneld_sha256_update(&hash, body, strlen(body));
    hapaneld_sha256_final(&hash, digest);
    hapaneld_sha256_hex(digest, hex);
    int length = snprintf(output, capacity, "%sSHA256 %s\n", body, hex);
    return length > 0 && (size_t)length < capacity ? length : -1;
}

static int serialize_plan(const guard_plan *plan, char output[GUARD_MAX_RECORD_BYTES]) {
    char body[GUARD_MAX_RECORD_BYTES];
    int length = snprintf(
        body, sizeof body,
        "V1 %s %s %s %" PRIu64 " %" PRIu64 " %s %u %" PRIu64 " %s %s"
        " %d %d %" PRIu64 " %s %" PRIu64 " %u %u %u"
        " %d %d %" PRIu64 " %s %" PRIu64 " %u %u %u"
        " %d %" PRIu64 " %" PRIu64 " %" PRIu64 " %" PRIu64
        " %" PRIu64 " %" PRIu64 " %u %s %" PRIu64 " %" PRIu64
        " %u %" PRIu64 " %s %d %s\n",
        plan->session, plan->boot, plan->signer, plan->generation,
        plan->baseline_bytes, plan->baseline_sha, plan->baseline_schema,
        plan->baseline_app_state, plan->baseline_app_state_sha, plan->baseline_settings_sha,
        plan->a.defined, plan->a.staged, plan->a.bytes,
        plan->a.defined ? plan->a.sha : "NONE", plan->a.version_code,
        plan->a.contract_min, plan->a.contract_max, plan->a.expected_schema,
        plan->b.defined, plan->b.staged, plan->b.bytes,
        plan->b.defined ? plan->b.sha : "NONE", plan->b.version_code,
        plan->b.contract_min, plan->b.contract_max, plan->b.expected_schema,
        plan->captured, plan->db_dir_dev, plan->db_dir_ino, plan->db_dev, plan->db_ino,
        plan->db_uid, plan->db_gid, plan->db_mode, plan->captured ? plan->db_label : "NONE",
        plan->overall_deadline_ms, plan->forward_deadline_ms,
        plan->settings_authority_version, plan->settings_authority_bytes,
        plan->settings_authority_sha, plan->settings_authority_staged, plan->package);
    if (length <= 0 || (size_t)length >= sizeof body) return -1;
    return body_with_checksum(body, output, GUARD_MAX_RECORD_BYTES);
}

static int verify_checksum(char *record, size_t length) {
    if (length < 73 || record[length - 1] != '\n') return -1;
    char *line = strrchr(record, '\n');
    if (!line || line == record) return -1;
    *line = '\0';
    char *checksum = strrchr(record, '\n');
    *line = '\n';
    if (!checksum) return -1;
    checksum++;
    if (strncmp(checksum, "SHA256 ", 7) != 0 || strlen(checksum) != 72 ||
        checksum[71] != '\n') return -1;
    char expected[65];
    memcpy(expected, checksum + 7, 64);
    expected[64] = '\0';
    if (!lower_hex_64(expected)) return -1;
    size_t body_size = (size_t)(checksum - record);
    hapaneld_sha256 hash;
    unsigned char digest[32];
    char actual[65];
    hapaneld_sha256_init(&hash);
    hapaneld_sha256_update(&hash, record, body_size);
    hapaneld_sha256_final(&hash, digest);
    hapaneld_sha256_hex(digest, actual);
    if (strcmp(actual, expected) != 0) return -1;
    record[body_size - 1] = '\0';
    return 0;
}

static int deserialize_plan(char *record, size_t length, guard_plan *plan) {
    if (verify_checksum(record, length) != 0) return -1;
    char *tokens[GUARD_TOKEN_COUNT];
    size_t count = 0;
    char *save = NULL;
    for (char *token = strtok_r(record, " ", &save); token; token = strtok_r(NULL, " ", &save)) {
        if (count == GUARD_TOKEN_COUNT) return -1;
        tokens[count++] = token;
    }
    /* The bound package is a required field with NO backward-compatible default: a record without
     * it fails the token count and is refused.  That is safe because a helper only ever replaces
     * itself through guard_maintenance_replacement_safe() / GUARDRETIRE APP; both admit a
     * replacement only on a provably empty Guard namespace, and the swapped-in binary re-arms in
     * guard_maintenance_replacement_worker_commit_app() only on one too (all three go through
     * empty_inventory_safe, which tolerates nothing but the owner lock).  So this build can never
     * inherit a manifest, draft or journal written by a build that did not record the package. */
    if (count != 43 || strcmp(tokens[0], "V1") != 0 ||
        !lower_hex_64(tokens[1]) || !lower_hex_64(tokens[2]) || !lower_hex_64(tokens[3]) ||
        !lower_hex_64(tokens[6]) || !lower_hex_64(tokens[9]) || !lower_hex_64(tokens[10]) ||
        !helper_known_package(tokens[42])) return -1;
    memset(plan, 0, sizeof *plan);
    snprintf(plan->package, sizeof plan->package, "%s", tokens[42]);
    snprintf(plan->session, sizeof plan->session, "%s", tokens[1]);
    snprintf(plan->boot, sizeof plan->boot, "%s", tokens[2]);
    snprintf(plan->signer, sizeof plan->signer, "%s", tokens[3]);
    snprintf(plan->baseline_sha, sizeof plan->baseline_sha, "%s", tokens[6]);
    snprintf(plan->baseline_app_state_sha, sizeof plan->baseline_app_state_sha, "%s", tokens[9]);
    snprintf(plan->baseline_settings_sha, sizeof plan->baseline_settings_sha, "%s", tokens[10]);
    uint32_t flag;
    if (parse_u64(tokens[4], 1, UINT64_MAX, &plan->generation) != 0 ||
        parse_u64(tokens[5], 1, GUARD_MAX_DB_BYTES, &plan->baseline_bytes) != 0 ||
        parse_u32(tokens[7], 1, UINT32_MAX, &plan->baseline_schema) != 0 ||
        parse_u64(tokens[8], 1, UINT64_MAX, &plan->baseline_app_state) != 0 ||
        parse_u32(tokens[11], 0, 1, &flag) != 0) return -1;
    plan->a.defined = (int)flag;
    if (parse_u32(tokens[12], 0, 1, &flag) != 0) return -1;
    plan->a.staged = (int)flag;
    if (parse_u64(tokens[13], 0, GUARD_MAX_APK_BYTES, &plan->a.bytes) != 0 ||
        parse_u64(tokens[15], 0, UINT64_MAX, &plan->a.version_code) != 0 ||
        parse_u32(tokens[16], 0, UINT32_MAX, &plan->a.contract_min) != 0 ||
        parse_u32(tokens[17], 0, UINT32_MAX, &plan->a.contract_max) != 0 ||
        parse_u32(tokens[18], 0, UINT32_MAX, &plan->a.expected_schema) != 0 ||
        parse_u32(tokens[19], 0, 1, &flag) != 0) return -1;
    plan->b.defined = (int)flag;
    if (parse_u32(tokens[20], 0, 1, &flag) != 0) return -1;
    plan->b.staged = (int)flag;
    if (parse_u64(tokens[21], 0, GUARD_MAX_APK_BYTES, &plan->b.bytes) != 0 ||
        parse_u64(tokens[23], 0, UINT64_MAX, &plan->b.version_code) != 0 ||
        parse_u32(tokens[24], 0, UINT32_MAX, &plan->b.contract_min) != 0 ||
        parse_u32(tokens[25], 0, UINT32_MAX, &plan->b.contract_max) != 0 ||
        parse_u32(tokens[26], 0, UINT32_MAX, &plan->b.expected_schema) != 0 ||
        parse_u32(tokens[27], 0, 1, &flag) != 0) return -1;
    plan->captured = (int)flag;
    if (parse_u64(tokens[28], 0, UINT64_MAX, &plan->db_dir_dev) != 0 ||
        parse_u64(tokens[29], 0, UINT64_MAX, &plan->db_dir_ino) != 0 ||
        parse_u64(tokens[30], 0, UINT64_MAX, &plan->db_dev) != 0 ||
        parse_u64(tokens[31], 0, UINT64_MAX, &plan->db_ino) != 0 ||
        parse_u64(tokens[32], 0, UINT64_MAX, &plan->db_uid) != 0 ||
        parse_u64(tokens[33], 0, UINT64_MAX, &plan->db_gid) != 0 ||
        parse_u32(tokens[34], 0, 07777, &plan->db_mode) != 0 ||
        parse_u64(tokens[36], 1, UINT64_MAX, &plan->overall_deadline_ms) != 0 ||
        parse_u64(tokens[37], 1, UINT64_MAX, &plan->forward_deadline_ms) != 0 ||
        plan->forward_deadline_ms >= plan->overall_deadline_ms ||
        plan->overall_deadline_ms - plan->forward_deadline_ms != GUARD_RECOVERY_RESERVE_MS ||
        parse_u32(tokens[38], 2, 2, &plan->settings_authority_version) != 0 ||
        parse_u64(tokens[39], 1, GUARD_MAX_SETTINGS_BYTES,
                  &plan->settings_authority_bytes) != 0 ||
        !lower_hex_64(tokens[40]) || parse_u32(tokens[41], 0, 1, &flag) != 0)
        return -1;
    snprintf(plan->settings_authority_sha, sizeof plan->settings_authority_sha, "%s", tokens[40]);
    plan->settings_authority_staged = (int)flag;
    if ((plan->a.defined && (!lower_hex_64(tokens[14]) || plan->a.bytes == 0 ||
         plan->a.version_code == 0 || plan->a.contract_min > plan->a.contract_max ||
         plan->a.expected_schema < plan->a.contract_min ||
         plan->a.expected_schema > plan->a.contract_max)) ||
        (!plan->a.defined && strcmp(tokens[14], "NONE") != 0) ||
        (plan->b.defined && (!lower_hex_64(tokens[22]) || plan->b.bytes == 0 ||
         plan->b.version_code == 0 || plan->b.contract_min > plan->b.contract_max ||
         plan->b.expected_schema < plan->b.contract_min ||
         plan->b.expected_schema > plan->b.contract_max)) ||
        (!plan->b.defined && strcmp(tokens[22], "NONE") != 0) ||
        (plan->a.staged && !plan->a.defined) || (plan->b.staged && !plan->b.defined)) return -1;
    if (plan->captured) {
        size_t label_length = strlen(tokens[35]);
        if (!plan->a.staged || !plan->b.staged || !label_length || label_length >= GUARD_LABEL_BYTES)
            return -1;
        for (size_t i = 0; i < label_length; i++)
            if ((unsigned char)tokens[35][i] < 0x21 || (unsigned char)tokens[35][i] > 0x7e)
                return -1;
        snprintf(plan->db_label, sizeof plan->db_label, "%s", tokens[35]);
    } else if (strcmp(tokens[35], "NONE") != 0 || plan->db_dir_dev || plan->db_dir_ino ||
               plan->db_dev || plan->db_ino || plan->db_uid || plan->db_gid || plan->db_mode) {
        return -1;
    }
    if (plan->a.defined) snprintf(plan->a.sha, sizeof plan->a.sha, "%s", tokens[14]);
    if (plan->b.defined) snprintf(plan->b.sha, sizeof plan->b.sha, "%s", tokens[22]);
    return 0;
}

static int parse_phase(const char *name, enum guard_phase *phase) {
    for (int candidate = GUARD_PHASE_EMPTY; candidate <= GUARD_PHASE_AMBIGUOUS; candidate++) {
        if (strcmp(name, guard_phase_name((enum guard_phase)candidate)) == 0) {
            *phase = (enum guard_phase)candidate;
            return 0;
        }
    }
    return -1;
}

static int safe_record_token(const char *token, size_t capacity) {
    size_t length = token ? strlen(token) : 0;
    if (!length || length >= capacity) return 0;
    for (size_t i = 0; i < length; i++)
        if (!((token[i] >= 'A' && token[i] <= 'Z') || token[i] == '_')) return 0;
    return 1;
}

static int serialize_journal(const guard_journal *journal, char output[GUARD_MAX_RECORD_BYTES]) {
    char body[GUARD_MAX_RECORD_BYTES];
    int length = snprintf(body, sizeof body,
        "J1 %s %" PRIu64 " %s %" PRIu64 " %s %s %s %" PRIu64 " %u %" PRIu64
        " %s %s %d %d %" PRIu64 " %d %" PRIu64 " %" PRIu64
        " %" PRIu64 " %s %" PRIu64 " %s %s\n",
        journal->session, journal->manifest_generation, journal->manifest_sha,
        journal->generation, guard_phase_name(journal->phase),
        journal->role, journal->installed_sha, journal->version_code, journal->schema,
        journal->deadline_ms, journal->error, journal->outcome,
        journal->pm_settled, journal->rollback_attempt_consumed,
        journal->recovery_deadline_ms, journal->pm_spawned, journal->pm_pid,
        journal->pm_start_ticks,
        journal->premigrate_bytes, journal->premigrate_sha,
        journal->b_primary_bytes, journal->b_primary_sha,
        journal->retirement_sha);
    if (length <= 0 || (size_t)length >= sizeof body) return -1;
    return body_with_checksum(body, output, GUARD_MAX_RECORD_BYTES);
}

static int deserialize_journal(char *record, size_t length, guard_journal *journal) {
    if (verify_checksum(record, length) != 0) return -1;
    char *tokens[25];
    size_t count = 0;
    char *save = NULL;
    for (char *token = strtok_r(record, " ", &save); token; token = strtok_r(NULL, " ", &save)) {
        if (count == sizeof tokens / sizeof tokens[0]) return -1;
        tokens[count++] = token;
    }
    if ((count != 23 && count != 24) || strcmp(tokens[0], "J1") != 0 ||
        !lower_hex_64(tokens[1]) ||
        !lower_hex_64(tokens[3])) return -1;
    memset(journal, 0, sizeof *journal);
    snprintf(journal->session, sizeof journal->session, "%s", tokens[1]);
    if (parse_u64(tokens[2], 1, UINT64_MAX, &journal->manifest_generation) != 0 ||
        parse_u64(tokens[4], 1, UINT64_MAX, &journal->generation) != 0 ||
        parse_phase(tokens[5], &journal->phase) != 0 ||
        (strcmp(tokens[6], "NONE") != 0 && strcmp(tokens[6], "A") != 0 &&
         strcmp(tokens[6], "B") != 0) ||
        (strcmp(tokens[7], "NONE") != 0 && !lower_hex_64(tokens[7])) ||
        parse_u64(tokens[8], 0, UINT64_MAX, &journal->version_code) != 0 ||
        parse_u32(tokens[9], 0, UINT32_MAX, &journal->schema) != 0 ||
        parse_u64(tokens[10], 0, UINT64_MAX, &journal->deadline_ms) != 0 ||
        !safe_record_token(tokens[11], sizeof journal->error) ||
        !safe_record_token(tokens[12], sizeof journal->outcome)) return -1;
    uint32_t flag;
    if (parse_u32(tokens[13], 0, 1, &flag) != 0) return -1;
    journal->pm_settled = (int)flag;
    if (parse_u32(tokens[14], 0, 1, &flag) != 0) return -1;
    journal->rollback_attempt_consumed = (int)flag;
    if (parse_u64(tokens[15], 0, UINT64_MAX, &journal->recovery_deadline_ms) != 0 ||
        parse_u32(tokens[16], 0, 1, &flag) != 0) return -1;
    journal->pm_spawned = (int)flag;
    if (parse_u64(tokens[17], 0, INT_MAX, &journal->pm_pid) != 0 ||
        parse_u64(tokens[18], 0, UINT64_MAX, &journal->pm_start_ticks) != 0 ||
        parse_u64(tokens[19], 0, GUARD_MAX_DB_BYTES, &journal->premigrate_bytes) != 0 ||
        (strcmp(tokens[20], "NONE") != 0 && !lower_hex_64(tokens[20])) ||
        parse_u64(tokens[21], 0, GUARD_MAX_DB_BYTES, &journal->b_primary_bytes) != 0 ||
        (strcmp(tokens[22], "NONE") != 0 && !lower_hex_64(tokens[22]))) return -1;
    snprintf(journal->manifest_sha, sizeof journal->manifest_sha, "%s", tokens[3]);
    snprintf(journal->role, sizeof journal->role, "%s", tokens[6]);
    snprintf(journal->installed_sha, sizeof journal->installed_sha, "%s", tokens[7]);
    snprintf(journal->error, sizeof journal->error, "%s", tokens[11]);
    snprintf(journal->outcome, sizeof journal->outcome, "%s", tokens[12]);
    snprintf(journal->premigrate_sha, sizeof journal->premigrate_sha, "%s", tokens[20]);
    snprintf(journal->b_primary_sha, sizeof journal->b_primary_sha, "%s", tokens[22]);
    snprintf(journal->retirement_sha, sizeof journal->retirement_sha, "%s",
             count == 24 ? tokens[23] : "NONE");
    if ((journal->premigrate_bytes == 0) != (strcmp(journal->premigrate_sha, "NONE") == 0) ||
        (journal->b_primary_bytes == 0) != (strcmp(journal->b_primary_sha, "NONE") == 0) ||
        (strcmp(journal->retirement_sha, "NONE") != 0 &&
         !lower_hex_64(journal->retirement_sha)))
        return -1;
    return 0;
}

static const char *capture_state_name(enum guard_capture_state state) {
    static const char *const names[] = {
        "INTENT", "STOP_ATTEMPTED", "FAILED_LAUNCHING", "FAILED_NO_MUTATION",
        "CANCEL_INTENT",
    };
    return state >= GUARD_CAPTURE_INTENT && state <= GUARD_CAPTURE_CANCEL_INTENT
        ? names[(unsigned)state] : "INVALID";
}

static int parse_capture_state(const char *name, enum guard_capture_state *state) {
    for (int candidate = GUARD_CAPTURE_INTENT;
         candidate <= GUARD_CAPTURE_CANCEL_INTENT; candidate++) {
        if (strcmp(name, capture_state_name((enum guard_capture_state)candidate)) == 0) {
            *state = (enum guard_capture_state)candidate;
            return 0;
        }
    }
    return -1;
}

static int serialize_capture(const guard_capture *capture,
                             char output[GUARD_MAX_RECORD_BYTES]) {
    char body[GUARD_MAX_RECORD_BYTES];
    int length = snprintf(body, sizeof body, "C1 %s %" PRIu64 " %s %s\n",
        capture->session, capture->draft_generation, capture->draft_sha,
        capture_state_name(capture->state));
    if (length <= 0 || (size_t)length >= sizeof body) return -1;
    return body_with_checksum(body, output, GUARD_MAX_RECORD_BYTES);
}

static int deserialize_capture(char *record, size_t length, guard_capture *capture) {
    if (verify_checksum(record, length) != 0) return -1;
    char *tokens[6];
    size_t count = 0;
    char *save = NULL;
    for (char *token = strtok_r(record, " ", &save); token;
         token = strtok_r(NULL, " ", &save)) {
        if (count == sizeof tokens / sizeof tokens[0]) return -1;
        tokens[count++] = token;
    }
    if (count != 5 || strcmp(tokens[0], "C1") != 0 || !lower_hex_64(tokens[1]) ||
        !lower_hex_64(tokens[3])) return -1;
    memset(capture, 0, sizeof *capture);
    snprintf(capture->session, sizeof capture->session, "%s", tokens[1]);
    snprintf(capture->draft_sha, sizeof capture->draft_sha, "%s", tokens[3]);
    return parse_u64(tokens[2], 1, UINT64_MAX, &capture->draft_generation) == 0 &&
        parse_capture_state(tokens[4], &capture->state) == 0 ? 0 : -1;
}

static const char *replacement_phase_name(enum guard_replacement_phase phase) {
    static const char *const names[] = { "REQUESTED", "GRANTED", "BACKUP_DURABLE", "SWAPPED" };
    return phase >= GUARD_REPLACEMENT_REQUESTED && phase <= GUARD_REPLACEMENT_SWAPPED
        ? names[(unsigned)phase] : "INVALID";
}

static int parse_replacement_phase(const char *name, enum guard_replacement_phase *phase) {
    for (int candidate = GUARD_REPLACEMENT_REQUESTED;
         candidate <= GUARD_REPLACEMENT_SWAPPED; candidate++) {
        if (strcmp(name, replacement_phase_name((enum guard_replacement_phase)candidate)) == 0) {
            *phase = (enum guard_replacement_phase)candidate;
            return 0;
        }
    }
    return -1;
}

static int serialize_replacement(const guard_replacement *replacement,
                                 char output[GUARD_MAX_RECORD_BYTES]) {
    char body[GUARD_MAX_RECORD_BYTES];
    int length = snprintf(body, sizeof body,
        "R1 %s %s APP %s %" PRIu64 " %" PRIu64 " %s %" PRIu64 " %" PRIu64
        " %" PRIu64 " %s %" PRIu64 " %" PRIu64 " %s\n",
        replacement->boot, replacement->nonce, replacement_phase_name(replacement->phase),
        replacement->generation, replacement->old_bytes, replacement->old_sha,
        replacement->old_dev, replacement->old_ino, replacement->new_bytes,
        replacement->new_sha, replacement->new_dev, replacement->new_ino,
        replacement->new_build);
    if (length <= 0 || (size_t)length >= sizeof body) return -1;
    return body_with_checksum(body, output, GUARD_MAX_RECORD_BYTES);
}

static int deserialize_replacement(char *record, size_t length,
                                   guard_replacement *replacement) {
    if (verify_checksum(record, length) != 0) return -1;
    char *tokens[16];
    size_t count = 0;
    char *save = NULL;
    for (char *token = strtok_r(record, " ", &save); token;
         token = strtok_r(NULL, " ", &save)) {
        if (count == sizeof tokens / sizeof tokens[0]) return -1;
        tokens[count++] = token;
    }
    if (count != 15 || strcmp(tokens[0], "R1") != 0 || !lower_hex_64(tokens[1]) ||
        !lower_hex_64(tokens[2]) || strcmp(tokens[3], "APP") != 0 ||
        !lower_hex_64(tokens[7]) || !lower_hex_64(tokens[11]) ||
        !lower_hex_64(tokens[14])) return -1;
    memset(replacement, 0, sizeof *replacement);
    snprintf(replacement->boot, sizeof replacement->boot, "%s", tokens[1]);
    snprintf(replacement->nonce, sizeof replacement->nonce, "%s", tokens[2]);
    snprintf(replacement->old_sha, sizeof replacement->old_sha, "%s", tokens[7]);
    snprintf(replacement->new_sha, sizeof replacement->new_sha, "%s", tokens[11]);
    snprintf(replacement->new_build, sizeof replacement->new_build, "%s", tokens[14]);
    return parse_replacement_phase(tokens[4], &replacement->phase) == 0 &&
        parse_u64(tokens[5], 1, UINT64_MAX, &replacement->generation) == 0 &&
        parse_u64(tokens[6], 1, GUARD_REPLACEMENT_MAX_BINARY_BYTES,
                  &replacement->old_bytes) == 0 &&
        parse_u64(tokens[8], 1, UINT64_MAX, &replacement->old_dev) == 0 &&
        parse_u64(tokens[9], 1, UINT64_MAX, &replacement->old_ino) == 0 &&
        parse_u64(tokens[10], 1, GUARD_REPLACEMENT_MAX_BINARY_BYTES,
                  &replacement->new_bytes) == 0 &&
        parse_u64(tokens[12], 1, UINT64_MAX, &replacement->new_dev) == 0 &&
        parse_u64(tokens[13], 1, UINT64_MAX, &replacement->new_ino) == 0 ? 0 : -1;
}

static int read_record_at(int dir, const char *name, char output[GUARD_MAX_RECORD_BYTES], size_t *length) {
    int fd = openat(dir, name, O_RDONLY | O_NOFOLLOW | O_CLOEXEC);
    if (fd < 0) return errno == ENOENT ? 0 : -1;
    struct stat st;
    if (fstat(fd, &st) != 0 || !S_ISREG(st.st_mode) || st.st_uid != geteuid() ||
        (st.st_mode & 0777) != 0600 || st.st_nlink != 1 ||
        st.st_size <= 0 || st.st_size >= GUARD_MAX_RECORD_BYTES) {
        close(fd);
        return -1;
    }
    size_t used = 0;
    while (used < (size_t)st.st_size) {
        ssize_t count = read(fd, output + used, (size_t)st.st_size - used);
        if (count < 0 && errno == EINTR) continue;
        if (count <= 0) { close(fd); return -1; }
        used += (size_t)count;
    }
    close(fd);
    output[used] = '\0';
    *length = used;
    return 1;
}

static int load_plan_at(int dir, const char *name, guard_plan *plan) {
    char record[GUARD_MAX_RECORD_BYTES];
    size_t length;
    int present = read_record_at(dir, name, record, &length);
    if (present <= 0) return present;
    return deserialize_plan(record, length, plan) == 0 ? 1 : -1;
}

static int load_journal_at(int dir, guard_journal *journal) {
    char record[GUARD_MAX_RECORD_BYTES];
    size_t length;
    int present = read_record_at(dir, GUARD_JOURNAL, record, &length);
    if (present <= 0) return present;
    return deserialize_journal(record, length, journal) == 0 ? 1 : -1;
}

static int load_capture_at(int dir, guard_capture *capture) {
    char record[GUARD_MAX_RECORD_BYTES];
    size_t length;
    int present = read_record_at(dir, GUARD_CAPTURE, record, &length);
    if (present <= 0) return present;
    return deserialize_capture(record, length, capture) == 0 ? 1 : -1;
}

static int load_replacement_at(int dir, guard_replacement *replacement) {
    char record[GUARD_MAX_RECORD_BYTES];
    size_t length;
    int present = read_record_at(dir, GUARD_REPLACEMENT, record, &length);
    if (present <= 0) return present;
    return deserialize_replacement(record, length, replacement) == 0 ? 1 : -1;
}

static int replacement_same_transaction(const guard_replacement *left,
                                        const guard_replacement *right) {
    return strcmp(left->boot, right->boot) == 0 &&
        strcmp(left->nonce, right->nonce) == 0 && left->old_bytes == right->old_bytes &&
        strcmp(left->old_sha, right->old_sha) == 0 && left->old_dev == right->old_dev &&
        left->old_ino == right->old_ino && left->new_bytes == right->new_bytes &&
        strcmp(left->new_sha, right->new_sha) == 0 && left->new_dev == right->new_dev &&
        left->new_ino == right->new_ino && strcmp(left->new_build, right->new_build) == 0;
}

static int load_replacement_reconciled(int dir, guard_replacement *replacement) {
    guard_replacement current, temporary;
    int final_state = load_replacement_at(dir, &current);
    char record[GUARD_MAX_RECORD_BYTES];
    size_t length = 0;
    int tmp_state = read_record_at(dir, GUARD_REPLACEMENT_TMP, record, &length);
    if (tmp_state == 1) tmp_state = deserialize_replacement(record, length, &temporary) == 0 ? 1 : -1;
    if (final_state < 0 || tmp_state < 0) return -1;
    if (tmp_state == 0) {
        if (final_state == 1) *replacement = current;
        return final_state;
    }
    if (final_state == 1 && replacement_same_transaction(&current, &temporary) &&
        temporary.generation == current.generation && temporary.phase == current.phase) {
        if (unlinkat(dir, GUARD_REPLACEMENT_TMP, 0) != 0 || fsync(dir) != 0) return -1;
        *replacement = current;
        return 1;
    }
    int promotable = final_state == 0
        ? temporary.phase == GUARD_REPLACEMENT_REQUESTED && temporary.generation == 1
        : replacement_same_transaction(&current, &temporary) &&
          temporary.generation == current.generation + 1 &&
          temporary.phase == current.phase + 1;
    if (!promotable) return -1;
    if (renameat(dir, GUARD_REPLACEMENT_TMP, dir, GUARD_REPLACEMENT) != 0) return -1;
    int synced = fsync(dir) == 0;
    guard_replacement promoted;
    int loaded = load_replacement_at(dir, &promoted);
    if (!synced || loaded != 1 || !replacement_same_transaction(&temporary, &promoted) ||
        temporary.phase != promoted.phase || temporary.generation != promoted.generation) return -1;
    *replacement = promoted;
    return 1;
}

static int atomic_write_at(
    int dir,
    const char *temporary,
    const char *final,
    const void *bytes,
    size_t size,
    enum guard_test_fault file_fault,
    enum guard_test_fault rename_fault,
    enum guard_test_fault dir_fault
) {
    if (remove_fixed_nondir(dir, temporary) != 0) return -1;
    int fd = openat(dir, temporary, O_WRONLY | O_CREAT | O_EXCL | O_NOFOLLOW | O_CLOEXEC, 0600);
    if (fd < 0) return -1;
    int ok = write_complete(fd, bytes, size) == 0 && fchmod(fd, 0600) == 0 &&
        !TEST_FAULT(file_fault) && fsync(fd) == 0;
    if (close(fd) != 0) ok = 0;
    if (!ok) { (void)unlinkat(dir, temporary, 0); return -1; }
    if (TEST_FAULT(rename_fault) || renameat(dir, temporary, dir, final) != 0) {
        (void)unlinkat(dir, temporary, 0);
        return -1;
    }
    return !TEST_FAULT(dir_fault) && fsync(dir) == 0
        ? GUARD_PUBLISH_COMMITTED : GUARD_PUBLISH_INDETERMINATE;
}

static int store_plan_at(int dir, const char *name, const char *temporary, const guard_plan *plan) {
    char record[GUARD_MAX_RECORD_BYTES];
    int length = serialize_plan(plan, record);
    if (length < 0) return -1;
    enum guard_test_fault file_fault = strcmp(name, GUARD_MANIFEST) == 0
        ? GUARD_TEST_FAULT_MANIFEST_FILE_SYNC : GUARD_TEST_FAULT_DRAFT_FILE_SYNC;
    enum guard_test_fault rename_fault = strcmp(name, GUARD_MANIFEST) == 0
        ? GUARD_TEST_FAULT_MANIFEST_RENAME : GUARD_TEST_FAULT_DRAFT_RENAME;
    enum guard_test_fault dir_fault = strcmp(name, GUARD_MANIFEST) == 0
        ? GUARD_TEST_FAULT_MANIFEST_DIR_SYNC : GUARD_TEST_FAULT_DRAFT_DIR_SYNC;
    return atomic_write_at(dir, temporary, name, record, (size_t)length,
        file_fault, rename_fault, dir_fault);
}

static int store_journal_at(int dir, const guard_journal *journal) {
    char record[GUARD_MAX_RECORD_BYTES];
    int length = serialize_journal(journal, record);
    if (length < 0) return -1;
    return atomic_write_at(dir, GUARD_JOURNAL_TMP, GUARD_JOURNAL,
        record, (size_t)length, GUARD_TEST_FAULT_JOURNAL_FILE_SYNC,
        GUARD_TEST_FAULT_JOURNAL_RENAME, GUARD_TEST_FAULT_JOURNAL_DIR_SYNC);
}

static int store_capture_at(int dir, const guard_capture *capture) {
    char record[GUARD_MAX_RECORD_BYTES];
    int length = serialize_capture(capture, record);
    if (length < 0) return -1;
    int stopped = capture->state != GUARD_CAPTURE_INTENT;
    return atomic_write_at(dir, GUARD_CAPTURE_TMP, GUARD_CAPTURE,
        record, (size_t)length,
        stopped ? GUARD_TEST_FAULT_CAPTURE_STOP_FILE_SYNC
                : GUARD_TEST_FAULT_CAPTURE_INTENT_FILE_SYNC,
        stopped ? GUARD_TEST_FAULT_CAPTURE_STOP_RENAME
                : GUARD_TEST_FAULT_CAPTURE_INTENT_RENAME,
        stopped ? GUARD_TEST_FAULT_CAPTURE_STOP_DIR_SYNC
                : GUARD_TEST_FAULT_CAPTURE_INTENT_DIR_SYNC);
}

static int store_replacement_at(int dir, const guard_replacement *replacement) {
    char record[GUARD_MAX_RECORD_BYTES];
    int length = serialize_replacement(replacement, record);
    if (length < 0) return GUARD_PUBLISH_FAILED;
    enum guard_test_fault dir_fault = replacement->phase == GUARD_REPLACEMENT_BACKUP_DURABLE
        ? GUARD_TEST_FAULT_REPLACEMENT_BACKUP_DIR_SYNC
        : (replacement->phase == GUARD_REPLACEMENT_SWAPPED
            ? GUARD_TEST_FAULT_REPLACEMENT_SWAPPED_DIR_SYNC
            : GUARD_TEST_FAULT_REPLACEMENT_DIR_SYNC);
    enum guard_test_fault retained_tmp_fault = replacement->phase == GUARD_REPLACEMENT_REQUESTED
        ? GUARD_TEST_FAULT_REPLACEMENT_REQUESTED_TMP_SYNCED
        : (replacement->phase == GUARD_REPLACEMENT_GRANTED
            ? GUARD_TEST_FAULT_REPLACEMENT_GRANTED_TMP_SYNCED
            : (replacement->phase == GUARD_REPLACEMENT_BACKUP_DURABLE
                ? GUARD_TEST_FAULT_REPLACEMENT_BACKUP_TMP_SYNCED
                : GUARD_TEST_FAULT_REPLACEMENT_SWAPPED_TMP_SYNCED));
    if (remove_fixed_nondir(dir, GUARD_REPLACEMENT_TMP) != 0) return GUARD_PUBLISH_FAILED;
    int fd = openat(dir, GUARD_REPLACEMENT_TMP,
        O_WRONLY | O_CREAT | O_EXCL | O_NOFOLLOW | O_CLOEXEC, 0600);
    if (fd < 0) return GUARD_PUBLISH_FAILED;
    int durable = write_complete(fd, record, (size_t)length) == 0 && fchmod(fd, 0600) == 0 &&
        !TEST_FAULT(GUARD_TEST_FAULT_REPLACEMENT_FILE_SYNC) && fsync(fd) == 0;
    if (close(fd) != 0) durable = 0;
    if (!durable) {
        (void)remove_fixed_nondir(dir, GUARD_REPLACEMENT_TMP);
        return GUARD_PUBLISH_FAILED;
    }
    if (TEST_FAULT(retained_tmp_fault)) return GUARD_PUBLISH_INDETERMINATE;
    if (TEST_FAULT(GUARD_TEST_FAULT_REPLACEMENT_RENAME) ||
        renameat(dir, GUARD_REPLACEMENT_TMP, dir, GUARD_REPLACEMENT) != 0) {
        (void)remove_fixed_nondir(dir, GUARD_REPLACEMENT_TMP);
        return GUARD_PUBLISH_FAILED;
    }
    return !TEST_FAULT(dir_fault) && fsync(dir) == 0
        ? GUARD_PUBLISH_COMMITTED : GUARD_PUBLISH_INDETERMINATE;
}

static int plan_record_sha(const guard_plan *plan, char output[65]) {
    char record[GUARD_MAX_RECORD_BYTES];
    int length = serialize_plan(plan, record);
    if (length < 0) return -1;
    hapaneld_sha256 hash;
    unsigned char digest[32];
    hapaneld_sha256_init(&hash);
    hapaneld_sha256_update(&hash, record, (size_t)length);
    hapaneld_sha256_final(&hash, digest);
    hapaneld_sha256_hex(digest, output);
    return 0;
}

static int initialize_capture(const guard_plan *draft, guard_capture *capture) {
    memset(capture, 0, sizeof *capture);
    snprintf(capture->session, sizeof capture->session, "%s", draft->session);
    capture->draft_generation = draft->generation;
    capture->state = GUARD_CAPTURE_INTENT;
    return plan_record_sha(draft, capture->draft_sha);
}

static int capture_matches_draft(const guard_capture *capture, const guard_plan *draft) {
    char draft_sha[65];
    return strcmp(capture->session, draft->session) == 0 &&
        capture->draft_generation == draft->generation &&
        plan_record_sha(draft, draft_sha) == 0 && strcmp(capture->draft_sha, draft_sha) == 0;
}

static int capture_matches_manifest_predecessor(const guard_capture *capture,
                                                const guard_plan *manifest) {
    if (!manifest->captured || manifest->generation <= 1) return 0;
    guard_plan draft = *manifest;
    draft.generation--;
    draft.captured = 0;
    draft.db_dir_dev = 0;
    draft.db_dir_ino = 0;
    draft.db_dev = 0;
    draft.db_ino = 0;
    draft.db_uid = 0;
    draft.db_gid = 0;
    draft.db_mode = 0;
    draft.db_label[0] = '\0';
    return capture_matches_draft(capture, &draft);
}

static int initialize_journal(const guard_plan *plan, guard_journal *journal) {
    memset(journal, 0, sizeof *journal);
    snprintf(journal->session, sizeof journal->session, "%s", plan->session);
    journal->manifest_generation = plan->generation;
    if (plan_record_sha(plan, journal->manifest_sha) != 0) return -1;
    journal->generation = plan->generation;
    journal->phase = GUARD_PHASE_PREPARED;
    snprintf(journal->role, sizeof journal->role, "NONE");
    snprintf(journal->installed_sha, sizeof journal->installed_sha, "NONE");
    snprintf(journal->error, sizeof journal->error, "NONE");
    snprintf(journal->outcome, sizeof journal->outcome, "NONE");
    journal->pm_settled = 1;
    snprintf(journal->premigrate_sha, sizeof journal->premigrate_sha, "NONE");
    snprintf(journal->b_primary_sha, sizeof journal->b_primary_sha, "NONE");
    snprintf(journal->retirement_sha, sizeof journal->retirement_sha, "NONE");
    return 0;
}

static int journal_matches_plan(const guard_plan *plan, const guard_journal *journal) {
    char manifest_sha[65];
    if (plan_record_sha(plan, manifest_sha) != 0 ||
        strcmp(plan->session, journal->session) != 0 ||
        journal->manifest_generation != plan->generation ||
        strcmp(journal->manifest_sha, manifest_sha) != 0 ||
        journal->generation < plan->generation || journal->deadline_ms > plan->overall_deadline_ms ||
        journal->phase < GUARD_PHASE_PREPARED || journal->phase > GUARD_PHASE_AMBIGUOUS ||
        ((journal->phase == GUARD_PHASE_RETIRING) !=
         lower_hex_64(journal->retirement_sha)) ||
        (journal->phase != GUARD_PHASE_RETIRING &&
         strcmp(journal->retirement_sha, "NONE") != 0))
        return 0;
    int submitted = journal->phase == GUARD_PHASE_SUBMITTED_A ||
        journal->phase == GUARD_PHASE_SUBMITTED_B ||
        journal->phase == GUARD_PHASE_ROLLBACK_A_SUBMITTED;
    int rollback_phase = journal->phase == GUARD_PHASE_ROLLBACK_REQUIRED ||
        journal->phase == GUARD_PHASE_ROLLBACK_A_SUBMITTED ||
        journal->phase == GUARD_PHASE_ROLLBACK_DB_PREPARED ||
        journal->phase == GUARD_PHASE_ROLLBACK_DB_RESTORED;
    if ((submitted && journal->pm_settled) ||
        (journal->pm_spawned != (journal->pm_pid > 1 && journal->pm_start_ticks > 0)) ||
        (journal->pm_spawned && !submitted) ||
        (!journal->pm_spawned && (journal->pm_pid != 0 || journal->pm_start_ticks != 0)) ||
        (journal->rollback_attempt_consumed &&
         journal->recovery_deadline_ms != plan->overall_deadline_ms) ||
        (!journal->rollback_attempt_consumed && journal->recovery_deadline_ms != 0) ||
        ((journal->phase == GUARD_PHASE_ROLLBACK_A_SUBMITTED ||
          journal->phase == GUARD_PHASE_ROLLBACK_DB_PREPARED ||
          journal->phase == GUARD_PHASE_ROLLBACK_DB_RESTORED) &&
         !journal->rollback_attempt_consumed) ||
        (rollback_phase && journal->recovery_deadline_ms != 0 &&
         journal->recovery_deadline_ms != plan->overall_deadline_ms))
        return 0;
    int waits = journal->phase == GUARD_PHASE_WAIT_A_HEALTH ||
        journal->phase == GUARD_PHASE_WAIT_B_HEALTH ||
        journal->phase == GUARD_PHASE_WAIT_A_REFUSAL ||
        journal->phase == GUARD_PHASE_ROLLBACK_DB_RESTORED;
    if (waits != (journal->deadline_ms != 0)) return 0;
    if ((journal->phase == GUARD_PHASE_WAIT_B_HEALTH ||
         journal->phase == GUARD_PHASE_WAIT_A_REFUSAL) &&
        journal->deadline_ms > plan->forward_deadline_ms) return 0;
    const guard_artifact *expected = NULL;
    switch (journal->phase) {
        case GUARD_PHASE_WAIT_B_HEALTH:
        case GUARD_PHASE_B_HEALTHY:
        case GUARD_PHASE_RECOVERY_WITHHELD:
        case GUARD_PHASE_WAIT_A_REFUSAL:
        case GUARD_PHASE_A_REFUSED:
        case GUARD_PHASE_RECOVERY_RESTORED:
            expected = &plan->b;
            break;
        case GUARD_PHASE_WAIT_A_HEALTH:
        case GUARD_PHASE_ROLLBACK_DB_PREPARED:
        case GUARD_PHASE_ROLLBACK_DB_RESTORED:
        case GUARD_PHASE_A_HEALTHY:
        case GUARD_PHASE_FINALIZED:
        case GUARD_PHASE_RETIRING:
            expected = &plan->a;
            break;
        default:
            break;
    }
    if (!expected)
        return strcmp(journal->role, "NONE") == 0 &&
            strcmp(journal->installed_sha, "NONE") == 0 &&
            journal->version_code == 0 && journal->schema == 0;
    return strcmp(journal->role, expected == &plan->a ? "A" : "B") == 0 &&
        strcmp(journal->installed_sha, expected->sha) == 0 &&
        journal->version_code == expected->version_code &&
        journal->schema == expected->expected_schema;
}

static int journal_custody_exact(int dir, const guard_plan *plan,
                                 const guard_journal *journal) {
    int has_recovery = journal->premigrate_bytes != 0 || journal->b_primary_bytes != 0;
    if (has_recovery) {
        if (journal->premigrate_bytes != plan->baseline_bytes ||
            strcmp(journal->premigrate_sha, plan->baseline_sha) != 0 ||
            journal->b_primary_bytes == 0 || !lower_hex_64(journal->b_primary_sha))
            return 0;
        int premigrate = known_file_present(dir, GUARD_PREMIGRATE);
        int primary = known_file_present(dir, GUARD_B_PRIMARY);
        int intent_only = journal->phase == GUARD_PHASE_B_HEALTHY;
        if (premigrate < 0 || primary < 0 ||
            (premigrate == 1 && hash_regular_at(dir, GUARD_PREMIGRATE,
                journal->premigrate_bytes, journal->premigrate_sha) != 0) ||
            (primary == 1 && hash_regular_at(dir, GUARD_B_PRIMARY,
                journal->b_primary_bytes, journal->b_primary_sha) != 0) ||
            (!intent_only && (premigrate != 1 || primary != 1)))
            return 0;
    } else {
        int premigrate = known_file_present(dir, GUARD_PREMIGRATE);
        int primary = known_file_present(dir, GUARD_B_PRIMARY);
        /* WITHHOLD seals premigrate first.  A crash or lost dir-fsync result after that
         * rename has one uniquely recoverable pre-journal topology: the B_HEALTHY
         * journal still carries no recovery identity, the sealed file is the exact
         * immutable baseline, and B-primary has not appeared.  Admit only that cut so
         * the same action can verify the existing premigrate and finish B-primary
         * custody; every other unjournaled recovery object remains HOLD. */
        int partial_withhold = journal->phase == GUARD_PHASE_B_HEALTHY &&
            premigrate == 1 && primary == 0 &&
            hash_regular_at(dir, GUARD_PREMIGRATE, plan->baseline_bytes,
                            plan->baseline_sha) == 0;
        if (strcmp(journal->premigrate_sha, "NONE") != 0 ||
            strcmp(journal->b_primary_sha, "NONE") != 0 ||
            premigrate < 0 || primary < 0 ||
            (!partial_withhold && (premigrate != 0 || primary != 0)))
            return 0;
    }
    int rolled_back = strncmp(journal->outcome, "ROLLED_BACK_", 12) == 0 ||
        (journal->phase == GUARD_PHASE_A_HEALTHY &&
         strcmp(journal->outcome, "NONE") == 0 &&
         strcmp(journal->error, "NONE") != 0);
    int must_have = journal->phase == GUARD_PHASE_RECOVERY_WITHHELD ||
        journal->phase == GUARD_PHASE_WAIT_A_REFUSAL ||
        journal->phase == GUARD_PHASE_A_REFUSED ||
        journal->phase == GUARD_PHASE_RECOVERY_RESTORED ||
        journal->phase == GUARD_PHASE_SUBMITTED_A ||
        journal->phase == GUARD_PHASE_WAIT_A_HEALTH ||
        ((journal->phase == GUARD_PHASE_A_HEALTHY ||
          journal->phase == GUARD_PHASE_FINALIZED ||
          journal->phase == GUARD_PHASE_RETIRING) && !rolled_back);
    return !must_have || has_recovery;
}

static int plan_identity_equal(const guard_plan *a, const guard_plan *b) {
    return strcmp(a->session, b->session) == 0 && strcmp(a->boot, b->boot) == 0 &&
        strcmp(a->package, b->package) == 0 &&
        strcmp(a->signer, b->signer) == 0 &&
        a->overall_deadline_ms == b->overall_deadline_ms &&
        a->forward_deadline_ms == b->forward_deadline_ms &&
        a->settings_authority_version == b->settings_authority_version &&
        a->settings_authority_bytes == b->settings_authority_bytes &&
        strcmp(a->settings_authority_sha, b->settings_authority_sha) == 0 &&
        a->settings_authority_staged == b->settings_authority_staged &&
        a->baseline_bytes == b->baseline_bytes && strcmp(a->baseline_sha, b->baseline_sha) == 0 &&
        a->baseline_schema == b->baseline_schema &&
        a->baseline_app_state == b->baseline_app_state &&
        strcmp(a->baseline_app_state_sha, b->baseline_app_state_sha) == 0 &&
        strcmp(a->baseline_settings_sha, b->baseline_settings_sha) == 0 &&
        a->a.defined == b->a.defined && a->a.staged == b->a.staged &&
        a->a.bytes == b->a.bytes && strcmp(a->a.sha, b->a.sha) == 0 &&
        a->a.version_code == b->a.version_code && a->a.contract_min == b->a.contract_min &&
        a->a.contract_max == b->a.contract_max && a->a.expected_schema == b->a.expected_schema &&
        a->b.defined == b->b.defined && a->b.staged == b->b.staged &&
        a->b.bytes == b->b.bytes && strcmp(a->b.sha, b->b.sha) == 0 &&
        a->b.version_code == b->b.version_code && a->b.contract_min == b->b.contract_min &&
        a->b.contract_max == b->b.contract_max && a->b.expected_schema == b->b.expected_schema;
}

static int known_namespace_name(const char *name) {
    static const char *const names[] = {
        GUARD_OWNER_LOCK, GUARD_DRAFT, GUARD_DRAFT_TMP, GUARD_MANIFEST, GUARD_MANIFEST_TMP,
        GUARD_JOURNAL, GUARD_JOURNAL_TMP, GUARD_CAPTURE, GUARD_CAPTURE_TMP,
        GUARD_REPLACEMENT, GUARD_REPLACEMENT_TMP,
        GUARD_A_APK, GUARD_B_APK, GUARD_A_UPLOAD,
        GUARD_B_UPLOAD, GUARD_BASELINE, GUARD_BASELINE_TMP,
        GUARD_PREMIGRATE, GUARD_PREMIGRATE_TMP, GUARD_B_PRIMARY, GUARD_B_PRIMARY_TMP,
        GUARD_HEALTH_COPY, GUARD_HEALTH_COPY_TMP,
        GUARD_SETTINGS, GUARD_SETTINGS_UPLOAD,
    };
    for (size_t i = 0; i < sizeof names / sizeof names[0]; i++)
        if (strcmp(name, names[i]) == 0) return 1;
    return 0;
}

static int namespace_has_only_known_names(int dir) {
    int duplicate = openat(dir, ".", O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC);
    if (duplicate < 0) return 0;
    DIR *stream = fdopendir(duplicate);
    if (!stream) { close(duplicate); return 0; }
    int safe = 1;
    errno = 0;
    for (struct dirent *entry = readdir(stream); entry; entry = readdir(stream)) {
        if (strcmp(entry->d_name, ".") == 0 || strcmp(entry->d_name, "..") == 0) continue;
        if (!known_namespace_name(entry->d_name)) { safe = 0; break; }
        errno = 0;
    }
    if (errno != 0) safe = 0;
    closedir(stream);
    return safe;
}

static int remove_reconciled_temps(int dir, const guard_plan *plan) {
    if (hash_regular_at(dir, GUARD_A_APK, plan->a.bytes, plan->a.sha) != 0 ||
        hash_regular_at(dir, GUARD_B_APK, plan->b.bytes, plan->b.sha) != 0 ||
        hash_regular_at(dir, GUARD_BASELINE, plan->baseline_bytes, plan->baseline_sha) != 0 ||
        !plan->settings_authority_staged ||
        validate_settings_authority_at(dir, plan) != 0)
        return -1;
    static const char *const names[] = {
        GUARD_DRAFT_TMP, GUARD_MANIFEST_TMP, GUARD_JOURNAL_TMP, GUARD_CAPTURE_TMP,
        GUARD_A_UPLOAD, GUARD_B_UPLOAD, GUARD_BASELINE_TMP,
        GUARD_PREMIGRATE_TMP, GUARD_B_PRIMARY_TMP,
        GUARD_HEALTH_COPY, GUARD_HEALTH_COPY_TMP,
        GUARD_SETTINGS_UPLOAD,
    };
    int changed = 0;
    for (size_t i = 0; i < sizeof names / sizeof names[0]; i++) {
        int present = known_file_present(dir, names[i]);
        if (present < 0) return -1;
        if (present == 1) {
            if (remove_fixed_nondir(dir, names[i]) != 0) return -1;
            changed = 1;
        }
    }
    return !changed || fsync(dir) == 0 ? 0 : -1;
}

static int cleanup_committed_capture_at(int dir, int draft_present, int capture_present) {
    if (capture_present) {
        if (unlinkat(dir, GUARD_CAPTURE, 0) != 0 || fsync(dir) != 0)
            return GUARD_PUBLISH_INDETERMINATE;
        if (TEST_FAULT(GUARD_TEST_FAULT_CAPTURE_CLEANUP_AFTER_CAPTURE))
            return GUARD_PUBLISH_INDETERMINATE;
    }
    if (draft_present && (unlinkat(dir, GUARD_DRAFT, 0) != 0 || fsync(dir) != 0))
        return GUARD_PUBLISH_INDETERMINATE;
    return GUARD_PUBLISH_COMMITTED;
}

static int load_manifest_reconciled(int dir, guard_plan *plan, guard_journal *journal,
                                    int repair_missing_journal) {
    int loaded = load_plan_at(dir, GUARD_MANIFEST, plan);
    if (loaded <= 0) return loaded;
    if (!namespace_has_only_known_names(dir) || !plan_boot_is_current(plan) || !plan->captured ||
        !plan->a.staged || !plan->b.staged || !plans_compatible(plan) ||
        plan->db_dir_dev == 0 || plan->db_dir_ino == 0 || plan->db_dev == 0 ||
        plan->db_ino == 0 || plan->db_mode == 0 || !plan->db_label[0] ||
        hash_regular_at(dir, GUARD_A_APK, plan->a.bytes, plan->a.sha) != 0 ||
        hash_regular_at(dir, GUARD_B_APK, plan->b.bytes, plan->b.sha) != 0 ||
        hash_regular_at(dir, GUARD_BASELINE, plan->baseline_bytes, plan->baseline_sha) != 0 ||
        !plan->settings_authority_staged ||
        validate_settings_authority_at(dir, plan) != 0)
        return -1;
    guard_plan draft;
    int draft_state = load_plan_at(dir, GUARD_DRAFT, &draft);
    if (draft_state < 0 || (draft_state == 1 &&
        (!plan_identity_equal(&draft, plan) || draft.captured || draft.generation + 1 != plan->generation)))
        return -1;
    guard_capture capture;
    int capture_state = load_capture_at(dir, &capture);
    int capture_valid = capture_state != 1 ||
        (capture.state == GUARD_CAPTURE_STOP_ATTEMPTED &&
         ((draft_state == 1 && capture_matches_draft(&capture, &draft)) ||
          (draft_state == 0 && capture_matches_manifest_predecessor(&capture, plan))));
    if (capture_state < 0 || !capture_valid)
        return -1;
    int journal_state = load_journal_at(dir, journal);
    if (journal_state == 0 && repair_missing_journal) {
        /* The only legitimate missing-journal topology is the initial manifest publication
         * window: the exact preceding draft is still present and proves that no later phase
         * could have existed.  Never manufacture PREPARED from a bare manifest because doing
         * so after journal loss could replay an already-submitted package operation. */
        if (draft_state != 1) return -1;
        if (initialize_journal(plan, journal) != 0) return -1;
        if (store_journal_at(dir, journal) != 0) return -1;
        journal_state = 1;
    }
    if (journal_state != 1 || !journal_matches_plan(plan, journal) ||
        !journal_custody_exact(dir, plan, journal)) return -1;
    if (remove_reconciled_temps(dir, plan) != 0) return -1;
    if (cleanup_committed_capture_at(dir, draft_state == 1, capture_state == 1) !=
        GUARD_PUBLISH_COMMITTED) return -1;
    return 1;
}

static int plan_role(guard_plan *plan, const char *role, guard_artifact **artifact,
                     const char **name, const char **upload) {
    if (strcmp(role, "A") == 0) {
        *artifact = &plan->a; *name = GUARD_A_APK; *upload = GUARD_A_UPLOAD; return 0;
    }
    if (strcmp(role, "B") == 0) {
        *artifact = &plan->b; *name = GUARD_B_APK; *upload = GUARD_B_UPLOAD; return 0;
    }
    return -1;
}

static int plans_compatible(const guard_plan *plan) {
    if (!plan->a.defined || !plan->b.defined) return 1;
    return plan->a.version_code < plan->b.version_code &&
        plan->a.expected_schema == plan->baseline_schema &&
        plan->a.expected_schema < UINT32_MAX &&
        plan->b.expected_schema == plan->a.expected_schema + 1 &&
        plan->a.contract_min <= plan->baseline_schema &&
        plan->a.contract_max >= plan->baseline_schema &&
        plan->a.contract_max < plan->b.expected_schema &&
        plan->b.contract_min <= plan->baseline_schema &&
        plan->b.contract_max >= plan->b.expected_schema &&
        plan->b.expected_schema > plan->a.expected_schema;
}

static int reconcile_staged_artifacts(int dir, guard_plan *plan) {
    int changed = 0;
    const char *names[2] = { GUARD_A_APK, GUARD_B_APK };
    guard_artifact *artifacts[2] = { &plan->a, &plan->b };
    for (size_t i = 0; i < 2; i++) {
        guard_artifact *artifact = artifacts[i];
        if (!artifact->defined) continue;
        int exact = hash_regular_at(dir, names[i], artifact->bytes, artifact->sha) == 0;
        if (artifact->staged && !exact) return -1;
        if (!artifact->staged && exact) {
            artifact->staged = 1;
            changed = 1;
        }
    }
    int settings_exact = hash_regular_at(dir, GUARD_SETTINGS,
        plan->settings_authority_bytes, plan->settings_authority_sha) == 0;
    if (plan->settings_authority_staged &&
        (!settings_exact || validate_settings_authority_at(dir, plan) != 0)) return -1;
    if (!plan->settings_authority_staged && settings_exact) {
        if (validate_settings_authority_at(dir, plan) != 0) return -1;
        plan->settings_authority_staged = 1;
        changed = 1;
    }
    if (!changed) return 0;
    plan->generation++;
    return store_plan_at(dir, GUARD_DRAFT, GUARD_DRAFT_TMP, plan);
}

static int load_draft_reconciled(int dir, guard_plan *plan) {
    int loaded = load_plan_at(dir, GUARD_DRAFT, plan);
    if (loaded == 0) return reconcile_orphaned_cancel_at(dir) < 0 ? -1 : 0;
    if (loaded < 0 || !namespace_has_only_known_names(dir) || !plan_boot_is_current(plan))
        return -1;
    guard_capture capture;
    int capture_state = load_capture_at(dir, &capture);
    if (capture_state < 0) return -1;
    if (capture_state == 1 && capture.state == GUARD_CAPTURE_CANCEL_INTENT) {
        if (!capture_matches_draft(&capture, plan)) return -1;
        return finish_cancel_locked(dir, plan, &capture) == GUARD_PUBLISH_COMMITTED ? 0 : -1;
    }
    uint64_t now = loaded == 1 ? monotonic_ms() : 0;
    if (now == 0 || now >= plan->overall_deadline_ms || reconcile_staged_artifacts(dir, plan) != 0)
        return -1;
    if (loaded == 1) {
        int baseline = known_file_present(dir, GUARD_BASELINE);
        if (baseline < 0 || (baseline == 1 &&
            (!plan->a.staged || !plan->b.staged ||
             hash_regular_at(dir, GUARD_BASELINE, plan->baseline_bytes, plan->baseline_sha) != 0)))
            return -1;
    }
    return loaded;
}

static int stream_into_artifact(
    conn_ctx *ctx, int dir, const char *upload, const char *final,
    uint64_t bytes, const char *expected_sha
) {
    if (check_space(bytes) != 0 || remove_fixed_nondir(dir, upload) != 0) return -1;
    int output = openat(dir, upload, O_WRONLY | O_CREAT | O_EXCL | O_NOFOLLOW | O_CLOEXEC, 0600);
    if (output < 0) return -1;
    struct stat st;
    if (fstat(output, &st) != 0 || !S_ISREG(st.st_mode) || st.st_uid != geteuid() || st.st_nlink != 1) {
        close(output); (void)unlinkat(dir, upload, 0); return -1;
    }
    if (reply(ctx->fd, "READY\n") != 0) {
        close(output); (void)unlinkat(dir, upload, 0); return -1;
    }
    hapaneld_sha256 hash;
    hapaneld_sha256_init(&hash);
    uint64_t remaining = bytes;
    unsigned char buffer[65536];
    int copied = 1;
    while (remaining > 0) {
        size_t wanted = remaining < sizeof buffer ? (size_t)remaining : sizeof buffer;
        ssize_t count = read(ctx->fd, buffer, wanted);
        if (count < 0 && errno == EINTR) continue;
        if (count <= 0) { copied = 0; break; }
        if (write_complete(output, buffer, (size_t)count) != 0) { copied = 0; break; }
        hapaneld_sha256_update(&hash, buffer, (size_t)count);
        remaining -= (uint64_t)count;
    }
    unsigned char extra;
    if (copied) {
        ssize_t count;
        do count = read(ctx->fd, &extra, 1); while (count < 0 && errno == EINTR);
        copied = count == 0;
    }
    unsigned char digest[32];
    char actual_sha[65];
    hapaneld_sha256_final(&hash, digest);
    hapaneld_sha256_hex(digest, actual_sha);
    int durable = copied && strcmp(actual_sha, expected_sha) == 0 && fchmod(output, 0400) == 0 &&
        !TEST_FAULT(GUARD_TEST_FAULT_ARTIFACT_FILE_SYNC) && fsync(output) == 0;
    if (close(output) != 0) durable = 0;
    if (!durable) { (void)unlinkat(dir, upload, 0); return -1; }
    if (TEST_FAULT(GUARD_TEST_FAULT_ARTIFACT_RENAME) || renameat(dir, upload, dir, final) != 0)
        return -1;
    if (TEST_FAULT(GUARD_TEST_FAULT_ARTIFACT_DIR_SYNC) || fsync(dir) != 0)
        return GUARD_PUBLISH_INDETERMINATE;
    return hash_regular_at(dir, final, bytes, expected_sha);
}

static int open_dir_chain(const char *absolute) {
    if (!absolute || absolute[0] != '/') return -1;
    int dir = open("/", O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC);
    if (dir < 0) return -1;
    char path[512];
    if (strlen(absolute) >= sizeof path) { close(dir); return -1; }
    snprintf(path, sizeof path, "%s", absolute + 1);
    char *save = NULL;
    for (char *part = strtok_r(path, "/", &save); part; part = strtok_r(NULL, "/", &save)) {
        if (!*part || strcmp(part, ".") == 0 || strcmp(part, "..") == 0) { close(dir); return -1; }
        int next = openat(dir, part, O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC);
        close(dir);
        if (next < 0) return -1;
        dir = next;
    }
    return dir;
}

// The database directory of one package.  In production this is byte-identical to the constant it
// replaces for the legacy package, and the test root mirrors the same shape, so a single-package
// panel opens exactly the path it opened before.
static int guard_app_db_dir_path(const char *package, char *output, size_t capacity) {
    if (!package || !helper_known_package(package)) return -1;
    int length = snprintf(output, capacity, GUARD_APP_USER_ROOT "/%s/databases", package);
    return length > 0 && (size_t)length < capacity ? 0 : -1;
}

static int open_app_db_dir(const guard_plan *plan) {
    char path[GUARD_APP_PATH_BYTES];
    if (guard_app_db_dir_path(plan->package, path, sizeof path) != 0) return -1;
    return open_dir_chain(path);
}

static int guard_app_dir_present(const char *package) {
    char path[GUARD_APP_PATH_BYTES];
    int length = snprintf(path, sizeof path, GUARD_APP_USER_ROOT "/%s", package);
    if (length <= 0 || (size_t)length >= sizeof path) return 0;
    struct stat st;
    return stat(path, &st) == 0 && S_ISDIR(st.st_mode);
}

// Which package this connection acts on.  An app caller acts on its own package and on nothing else:
// the answer comes from the SO_PEERCRED identity resolved once in the accept loop, never from a
// request token, which is why no Guard verb has or needs a package argument.  Root owns no package,
// so it resolves to the single package whose data directory is present -- on a single-package panel
// that is the one package, and behaviour is unchanged.  With both installed, root is ambiguous and
// gets NULL: binding a session to a guess would point `pm`, `am` and the live database at the wrong
// app's uid.
static const char *guard_caller_package(const conn_ctx *ctx) {
    if (!ctx) return NULL;
    const helper_app_package *entry = helper_app_package_for(ctx->caller);
    if (entry) return entry->package;
    if (ctx->caller != HELPER_CALLER_ROOT) return NULL;
    const char *resolved = NULL;
    for (size_t i = 0; i < HELPER_APP_PACKAGE_COUNT; i++) {
        if (!guard_app_dir_present(HELPER_APP_PACKAGES[i].package)) continue;
        if (resolved) return NULL;
        resolved = HELPER_APP_PACKAGES[i].package;
    }
    return resolved;
}

// A caller may only drive the session its own package prepared.  Startup reconcile, the supervisor
// tick and the deadline worker have no caller at all, which is precisely why the identity lives in
// the durable plan rather than being looked up per call.
static int guard_caller_owns_plan(const conn_ctx *ctx, const guard_plan *plan) {
    const char *package = guard_caller_package(ctx);
    return package && strcmp(package, plan->package) == 0;
}

static int sqlite_schema_fd(int fd, uint32_t *schema) {
    unsigned char header[64];
    if (lseek(fd, 0, SEEK_SET) < 0) return -1;
    size_t used = 0;
    while (used < sizeof header) {
        ssize_t count = read(fd, header + used, sizeof header - used);
        if (count < 0 && errno == EINTR) continue;
        if (count <= 0) return -1;
        used += (size_t)count;
    }
    static const unsigned char magic[16] = "SQLite format 3";
    if (memcmp(header, magic, 15) != 0 || header[15] != 0) return -1;
    *schema = ((uint32_t)header[60] << 24) | ((uint32_t)header[61] << 16) |
        ((uint32_t)header[62] << 8) | (uint32_t)header[63];
    return lseek(fd, 0, SEEK_SET) >= 0 ? 0 : -1;
}

static int fixed_db_sidecars_absent(int db_dir) {
    static const char *const names[] = {
        GUARD_DB_NAME "-wal", GUARD_DB_NAME "-shm", GUARD_DB_NAME "-journal",
        GUARD_DB_NAME ".restore.tmp",
    };
    for (size_t i = 0; i < sizeof names / sizeof names[0]; i++) {
        struct stat st;
        if (fstatat(db_dir, names[i], &st, AT_SYMLINK_NOFOLLOW) == 0 || errno != ENOENT) return 0;
    }
    return 1;
}

static int checkpointed_db_sidecars_safe(int db_dir, const guard_plan *plan) {
    guard_file_snapshot wal, shm;
    return known_file_present(db_dir, GUARD_DB_NAME "-journal") == 0 &&
        known_file_present(db_dir, GUARD_DB_NAME ".restore.tmp") == 0 &&
        snapshot_regular_at(db_dir, GUARD_DB_NAME "-wal", 0, &wal) == 0 &&
        snapshot_regular_at(db_dir, GUARD_DB_NAME "-shm", 0, &shm) == 0 &&
        (!wal.present || (wal.stat.st_size == 0 &&
          (uint64_t)wal.stat.st_uid == plan->db_uid &&
          (uint64_t)wal.stat.st_gid == plan->db_gid)) &&
        (!shm.present || ((uint64_t)shm.stat.st_uid == plan->db_uid &&
          (uint64_t)shm.stat.st_gid == plan->db_gid));
}

static int canonicalize_capture_sidecars(int db_dir, uid_t uid, gid_t gid) {
    if (known_file_present(db_dir, GUARD_DB_NAME "-journal") != 0 ||
        known_file_present(db_dir, GUARD_DB_NAME ".restore.tmp") != 0)
        return -1;
    static const char *const names[] = { GUARD_DB_NAME "-wal", GUARD_DB_NAME "-shm" };
    for (size_t i = 0; i < sizeof names / sizeof names[0]; i++) {
        struct stat named;
        if (fstatat(db_dir, names[i], &named, AT_SYMLINK_NOFOLLOW) != 0) {
            if (errno == ENOENT) continue;
            return -1;
        }
        if (!S_ISREG(named.st_mode) || named.st_nlink != 1 || named.st_uid != uid ||
            named.st_gid != gid || (i == 0 && named.st_size != 0) ||
            (i == 1 && (named.st_size < 0 || named.st_size > 1024 * 1024)))
            return -1;
        int fd = openat(db_dir, names[i], O_RDONLY | O_NOFOLLOW | O_CLOEXEC);
        struct stat opened;
        int exact = fd >= 0 && fstat(fd, &opened) == 0 &&
            snapshot_unchanged(&(guard_file_snapshot){ .present = 1, .stat = named },
                               &(guard_file_snapshot){ .present = 1, .stat = opened });
        if (fd >= 0) close(fd);
        if (!exact || unlinkat(db_dir, names[i], 0) != 0) return -1;
    }
    if (fsync(db_dir) != 0) return -1;
    return fixed_db_sidecars_absent(db_dir) ? 0 : -1;
}

static int installed_apk_exact(const char *package, const guard_artifact *artifact) {
#ifdef HAPANELD_TEST
    (void)package;
    int fd = open(GUARD_INSTALLED_APK, O_RDONLY | O_NOFOLLOW | O_CLOEXEC);
#else
    char output[512];
    if (!helper_known_package(package)) return -1;
    const char *const argv[] = { "pm", "path", package, NULL };
    if (sysexec_capture_argv("/system/bin/pm", argv, output, sizeof output) != 0) return -1;
    size_t length = strlen(output);
    while (length > 0 && (output[length - 1] == '\n' || output[length - 1] == '\r')) output[--length] = 0;
    static const char prefix[] = "package:";
    const char *path = output + sizeof prefix - 1;
    size_t path_length = strlen(path);
    if (strncmp(output, prefix, sizeof prefix - 1) != 0 || strncmp(path, "/data/app/", 10) != 0 ||
        strstr(path, "..") || path_length < 9 || strcmp(path + path_length - 9, "/base.apk") != 0) return -1;
    int fd = open(path, O_RDONLY | O_NOFOLLOW | O_CLOEXEC);
#endif
    if (fd < 0) return -1;
    struct stat st;
    char hash[65];
    int exact = fstat(fd, &st) == 0 && S_ISREG(st.st_mode) && st.st_nlink == 1 &&
        (uint64_t)st.st_size == artifact->bytes &&
        hapaneld_sha256_fd(fd, artifact->bytes, hash) == 0 && strcmp(hash, artifact->sha) == 0;
    close(fd);
    return exact ? 0 : -1;
}

static int copy_baseline(int custody, int source, const guard_plan *plan) {
    int existing = known_file_present(custody, GUARD_BASELINE);
    if (existing < 0) return -1;
    if (existing == 1)
        return hash_regular_at(custody, GUARD_BASELINE, plan->baseline_bytes, plan->baseline_sha);
    if (remove_fixed_nondir(custody, GUARD_BASELINE_TMP) != 0) return -1;
    int output = openat(custody, GUARD_BASELINE_TMP,
        O_WRONLY | O_CREAT | O_EXCL | O_NOFOLLOW | O_CLOEXEC, 0400);
    if (output < 0 || lseek(source, 0, SEEK_SET) < 0) {
        if (output >= 0) close(output);
        return -1;
    }
    hapaneld_sha256 hash;
    hapaneld_sha256_init(&hash);
    uint64_t remaining = plan->baseline_bytes;
    unsigned char buffer[65536];
    int copied = 1;
    while (remaining > 0) {
        size_t wanted = remaining < sizeof buffer ? (size_t)remaining : sizeof buffer;
        ssize_t count = read(source, buffer, wanted);
        if (count < 0 && errno == EINTR) continue;
        if (count <= 0 || write_complete(output, buffer, (size_t)count) != 0) { copied = 0; break; }
        hapaneld_sha256_update(&hash, buffer, (size_t)count);
        remaining -= (uint64_t)count;
    }
    unsigned char extra;
    ssize_t extra_count = -1;
    if (copied) do extra_count = read(source, &extra, 1); while (extra_count < 0 && errno == EINTR);
    unsigned char digest[32];
    char actual[65];
    hapaneld_sha256_final(&hash, digest);
    hapaneld_sha256_hex(digest, actual);
    int durable = copied && extra_count == 0 && strcmp(actual, plan->baseline_sha) == 0 &&
        fchmod(output, 0400) == 0 && !TEST_FAULT(GUARD_TEST_FAULT_BASELINE_COPY) &&
        fsync(output) == 0;
    if (close(output) != 0) durable = 0;
    if (!durable) { (void)unlinkat(custody, GUARD_BASELINE_TMP, 0); return -1; }
    if (TEST_FAULT(GUARD_TEST_FAULT_BASELINE_RENAME) ||
        renameat(custody, GUARD_BASELINE_TMP, custody, GUARD_BASELINE) != 0) return -1;
    if (TEST_FAULT(GUARD_TEST_FAULT_BASELINE_DIR_SYNC) || fsync(custody) != 0)
        return GUARD_PUBLISH_INDETERMINATE;
    return hash_regular_at(custody, GUARD_BASELINE, plan->baseline_bytes, plan->baseline_sha);
}

static int copy_exact_to_custody(int custody, int source, const char *temporary,
                                 const char *final, uint64_t bytes, const char *sha) {
    int recovery_custody = strcmp(final, GUARD_PREMIGRATE) == 0 ||
        strcmp(final, GUARD_B_PRIMARY) == 0;
    int existing = known_file_present(custody, final);
    if (existing < 0) return -1;
    if (existing == 1) return hash_regular_at(custody, final, bytes, sha);
    if (check_space(bytes) != 0 || remove_fixed_nondir(custody, temporary) != 0 ||
        lseek(source, 0, SEEK_SET) < 0) return -1;
    int output = openat(custody, temporary,
        O_WRONLY | O_CREAT | O_EXCL | O_NOFOLLOW | O_CLOEXEC, 0400);
    if (output < 0) return -1;
    hapaneld_sha256 hash;
    hapaneld_sha256_init(&hash);
    uint64_t remaining = bytes;
    unsigned char buffer[65536];
    int copied = 1;
    while (remaining > 0) {
        size_t wanted = remaining < sizeof buffer ? (size_t)remaining : sizeof buffer;
        ssize_t count = read(source, buffer, wanted);
        if (count < 0 && errno == EINTR) continue;
        if (count <= 0 || write_complete(output, buffer, (size_t)count) != 0) {
            copied = 0;
            break;
        }
        hapaneld_sha256_update(&hash, buffer, (size_t)count);
        remaining -= (uint64_t)count;
    }
    unsigned char extra;
    ssize_t extra_count = -1;
    if (copied) do extra_count = read(source, &extra, 1); while (extra_count < 0 && errno == EINTR);
    unsigned char digest[32];
    char actual[65];
    hapaneld_sha256_final(&hash, digest);
    hapaneld_sha256_hex(digest, actual);
    int durable = copied && extra_count == 0 && strcmp(actual, sha) == 0 &&
        fchmod(output, 0400) == 0 &&
        (!recovery_custody || !TEST_FAULT(GUARD_TEST_FAULT_PREMIGRATE_FILE_SYNC)) &&
        fsync(output) == 0;
    if (close(output) != 0) durable = 0;
    if (!durable) {
        (void)unlinkat(custody, temporary, 0);
        return -1;
    }
    if ((recovery_custody && TEST_FAULT(GUARD_TEST_FAULT_PREMIGRATE_RENAME)) ||
        renameat(custody, temporary, custody, final) != 0) return -1;
    if ((recovery_custody && TEST_FAULT(GUARD_TEST_FAULT_PREMIGRATE_DIR_SYNC)) ||
        fsync(custody) != 0) return GUARD_PUBLISH_INDETERMINATE;
    return hash_regular_at(custody, final, bytes, sha);
}

static int force_stop_guard_app(const char *package) {
    if (!helper_known_package(package)) return -1;
    const char *const argv[] = { "am", "force-stop", package, NULL };
    int timed_out = 0;
    int status = sysexec_run_argv_timeout(
        "/system/bin/am", argv, 0, GUARD_AM_TIMEOUT_MS, &timed_out);
    return !timed_out && status >= 0 && WIFEXITED(status) && WEXITSTATUS(status) == 0 ? 0 : -1;
}

static uint64_t monotonic_ms(void) {
#ifdef HAPANELD_TEST
    if (test_now_ms != 0) return test_now_ms;
#endif
    struct timespec now;
    if (clock_gettime(CLOCK_MONOTONIC, &now) != 0 || now.tv_sec < 0) return 0;
    uint64_t seconds = (uint64_t)now.tv_sec;
    if (seconds > UINT64_MAX / 1000ULL) return 0;
    return seconds * 1000ULL + (uint64_t)now.tv_nsec / 1000000ULL;
}

static void journal_set_role(guard_journal *journal, const char *role,
                             const guard_artifact *artifact) {
    snprintf(journal->role, sizeof journal->role, "%s", role);
    snprintf(journal->installed_sha, sizeof journal->installed_sha, "%s", artifact->sha);
    journal->version_code = artifact->version_code;
    journal->schema = artifact->expected_schema;
}

static void journal_clear_role(guard_journal *journal) {
    snprintf(journal->role, sizeof journal->role, "NONE");
    snprintf(journal->installed_sha, sizeof journal->installed_sha, "NONE");
    journal->version_code = 0;
    journal->schema = 0;
}

static void journal_clear_pm_process(guard_journal *journal) {
    journal->pm_spawned = 0;
    journal->pm_pid = 0;
    journal->pm_start_ticks = 0;
}

static int process_start_ticks(pid_t pid, uint64_t *ticks) {
#ifdef HAPANELD_TEST
    if (pid <= 1 || !ticks) return -1;
    *ticks = (uint64_t)pid * 100ULL + 7ULL;
    return 1;
#else
    char path[64], record[1024];
    int length = snprintf(path, sizeof path, "/proc/%ld/stat", (long)pid);
    if (length <= 0 || (size_t)length >= sizeof path) return -1;
    int fd = open(path, O_RDONLY | O_NOFOLLOW | O_CLOEXEC);
    if (fd < 0) return errno == ENOENT ? 0 : -1;
    ssize_t used;
    do used = read(fd, record, sizeof record - 1); while (used < 0 && errno == EINTR);
    close(fd);
    if (used <= 0 || used >= (ssize_t)sizeof record) return -1;
    record[used] = '\0';
    char *cursor = strrchr(record, ')');
    if (!cursor || cursor[1] != ' ') return -1;
    cursor += 2;
    char *save = NULL;
    for (unsigned field = 3; field <= 22; field++) {
        char *token = strtok_r(field == 3 ? cursor : NULL, " ", &save);
        if (!token) return -1;
        if (field == 22) return parse_u64(token, 1, UINT64_MAX, ticks) == 0 ? 1 : -1;
    }
    return -1;
#endif
}

static int pm_process_active(const guard_journal *journal) {
    if (!journal->pm_spawned || journal->pm_pid <= 1 || journal->pm_pid > INT_MAX ||
        journal->pm_start_ticks == 0) return -1;
#ifdef HAPANELD_TEST
    return test_pm_process_state;
#else
    uint64_t ticks = 0;
    int observed = process_start_ticks((pid_t)journal->pm_pid, &ticks);
    /* CLI disappearance is not PackageInstaller terminality.  Until a durable installer
     * session is correlated, it is unknown rather than inactive and must fail closed. */
    /* A missing/reused client PID is not PackageInstaller terminality.  Only the original
     * supervisor may consume its owned child's normal wait result; restart remains HOLD. */
    if (observed == 0) return -1;
    if (observed < 0) return -1;
    if (ticks != journal->pm_start_ticks) return -1;
    if (kill((pid_t)journal->pm_pid, 0) == 0 || errno == EPERM) return 1;
    return -1;
#endif
}

static int installed_apk_stable_exact(const char *package, const guard_artifact *artifact) {
    for (int observation = 0; observation < 3; observation++) {
        if (installed_apk_exact(package, artifact) != 0) return -1;
#ifndef HAPANELD_TEST
        if (observation != 2) {
            struct timespec pause = { .tv_sec = 0, .tv_nsec = 200000000L };
            while (nanosleep(&pause, &pause) != 0 && errno == EINTR) { }
        }
#endif
    }
    return 0;
}

static int snapshot_regular_at(int dir, const char *name, int required,
                               guard_file_snapshot *snapshot) {
    memset(snapshot, 0, sizeof *snapshot);
    if (fstatat(dir, name, &snapshot->stat, AT_SYMLINK_NOFOLLOW) != 0)
        return !required && errno == ENOENT ? 0 : -1;
    if (!S_ISREG(snapshot->stat.st_mode) || snapshot->stat.st_nlink != 1) return -1;
    snapshot->present = 1;
    return 0;
}

static int snapshot_unchanged(const guard_file_snapshot *before,
                              const guard_file_snapshot *after) {
    if (before->present != after->present) return 0;
    if (!before->present) return 1;
    return before->stat.st_dev == after->stat.st_dev &&
        before->stat.st_ino == after->stat.st_ino &&
        before->stat.st_uid == after->stat.st_uid &&
        before->stat.st_gid == after->stat.st_gid &&
        before->stat.st_mode == after->stat.st_mode &&
        before->stat.st_nlink == after->stat.st_nlink &&
        before->stat.st_size == after->stat.st_size &&
        before->stat.st_mtim.tv_sec == after->stat.st_mtim.tv_sec &&
        before->stat.st_mtim.tv_nsec == after->stat.st_mtim.tv_nsec;
}

static int hex_nibble(unsigned char value) {
    if (value >= '0' && value <= '9') return value - '0';
    if (value >= 'a' && value <= 'f') return value - 'a' + 10;
    if (value >= 'A' && value <= 'F') return value - 'A' + 10;
    return -1;
}

static int lower_hex_even(const char *value, int allow_empty) {
    size_t length = value ? strlen(value) : 0;
    if ((!allow_empty && length == 0) || (length & 1U) != 0) return 0;
    for (size_t i = 0; i < length; i++)
        if (!((value[i] >= '0' && value[i] <= '9') ||
              (value[i] >= 'a' && value[i] <= 'f'))) return 0;
    return 1;
}

static int valid_utf8_hex(const char *hex, int reject_control) {
    size_t length = strlen(hex);
    unsigned char bytes[4];
    size_t offset = 0;
    while (offset < length) {
        int high = hex_nibble((unsigned char)hex[offset]);
        int low = hex_nibble((unsigned char)hex[offset + 1]);
        if (high < 0 || low < 0) return 0;
        bytes[0] = (unsigned char)((high << 4) | low);
        size_t count = bytes[0] < 0x80 ? 1 :
            ((bytes[0] & 0xe0) == 0xc0 ? 2 :
             ((bytes[0] & 0xf0) == 0xe0 ? 3 :
              ((bytes[0] & 0xf8) == 0xf0 ? 4 : 0)));
        if (count == 0 || offset + count * 2 > length ||
            (reject_control && (bytes[0] < 0x20 || bytes[0] == 0x7f))) return 0;
        for (size_t i = 1; i < count; i++) {
            high = hex_nibble((unsigned char)hex[offset + i * 2]);
            low = hex_nibble((unsigned char)hex[offset + i * 2 + 1]);
            if (high < 0 || low < 0) return 0;
            bytes[i] = (unsigned char)((high << 4) | low);
            if ((bytes[i] & 0xc0) != 0x80) return 0;
        }
        uint32_t codepoint = count == 1 ? bytes[0] :
            (count == 2 ? ((uint32_t)(bytes[0] & 0x1f) << 6) | (bytes[1] & 0x3f) :
             (count == 3 ? ((uint32_t)(bytes[0] & 0x0f) << 12) |
                 ((uint32_t)(bytes[1] & 0x3f) << 6) | (bytes[2] & 0x3f) :
                 ((uint32_t)(bytes[0] & 0x07) << 18) |
                 ((uint32_t)(bytes[1] & 0x3f) << 12) |
                 ((uint32_t)(bytes[2] & 0x3f) << 6) | (bytes[3] & 0x3f)));
        if ((count == 2 && codepoint < 0x80) || (count == 3 && codepoint < 0x800) ||
            (count == 4 && codepoint < 0x10000) || codepoint > 0x10ffff ||
            (codepoint >= 0xd800 && codepoint <= 0xdfff)) return 0;
        offset += count * 2;
    }
    return 1;
}

static int validate_settings_authority_at(int dir, const guard_plan *plan) {
    if (plan->settings_authority_version != 2 ||
        hash_regular_at(dir, GUARD_SETTINGS, plan->settings_authority_bytes,
                        plan->settings_authority_sha) != 0)
        return -1;
    int fd = openat(dir, GUARD_SETTINGS, O_RDONLY | O_NOFOLLOW | O_CLOEXEC);
    char *record = malloc((size_t)plan->settings_authority_bytes + 1);
    if (fd < 0 || !record) {
        if (fd >= 0) close(fd);
        free(record);
        return -1;
    }
    size_t used = 0;
    while (used < (size_t)plan->settings_authority_bytes) {
        ssize_t count = read(fd, record + used,
            (size_t)plan->settings_authority_bytes - used);
        if (count < 0 && errno == EINTR) continue;
        if (count <= 0) break;
        used += (size_t)count;
    }
    close(fd);
    record[used] = '\0';
    int valid = used == (size_t)plan->settings_authority_bytes &&
        used >= 4 && memcmp(record, "S2\n", 3) == 0 && record[used - 1] == '\n';
    char previous[1025] = {0};
    unsigned records = 0;
    char *cursor = valid ? record + 3 : NULL;
    while (valid && cursor && *cursor) {
        char *end = strchr(cursor, '\n');
        if (!end) { valid = 0; break; }
        *end = '\0';
        char *fields[3] = { cursor, NULL, NULL };
        fields[1] = strchr(fields[0], '|');
        if (fields[1]) *fields[1]++ = '\0';
        fields[2] = fields[1] ? strchr(fields[1], '|') : NULL;
        if (fields[2]) *fields[2]++ = '\0';
        static const char *const type_hex[] = {
            "626f6f6c65616e", "696e74", "6c6f6e67", "666c6f6174", "737472696e67",
        };
        int known_type = 0;
        if (fields[2] && !strchr(fields[2], '|'))
            for (size_t i = 0; i < sizeof type_hex / sizeof type_hex[0]; i++)
                if (strcmp(fields[1], type_hex[i]) == 0) known_type = 1;
        valid = fields[1] && fields[2] && lower_hex_even(fields[0], 0) &&
            strlen(fields[0]) < sizeof previous && lower_hex_even(fields[1], 0) &&
            lower_hex_even(fields[2], 1) && valid_utf8_hex(fields[0], 1) &&
            valid_utf8_hex(fields[2], 0) && known_type &&
            (records == 0 || strcmp(previous, fields[0]) < 0) && records < 1024;
        if (valid) {
            snprintf(previous, sizeof previous, "%s", fields[0]);
            records++;
        }
        cursor = end + 1;
    }
    free(record);
    return valid && records > 0 ? 0 : -1;
}

static int sha_frame_hex(hapaneld_sha256 *hash, const char *hex, int nullable) {
    if (nullable && strcmp(hex, "N") == 0) {
        const unsigned char absent = 0;
        hapaneld_sha256_update(hash, &absent, 1);
        return 0;
    }
    if (nullable) {
        if (*hex != 'V') return -1;
        hex++;
    }
    size_t length = strlen(hex);
    if ((length & 1U) != 0) return -1;
    const unsigned char present = 1;
    unsigned char framed_length[8];
    uint64_t bytes = (uint64_t)(length / 2);
    for (int shift = 56, index = 0; shift >= 0; shift -= 8, index++)
        framed_length[index] = (unsigned char)((bytes >> shift) & 0xffU);
    hapaneld_sha256_update(hash, &present, 1);
    hapaneld_sha256_update(hash, framed_length, sizeof framed_length);
    unsigned char decoded[1024];
    size_t used = 0;
    for (size_t offset = 0; offset < length; offset += 2) {
        int high = hex_nibble((unsigned char)hex[offset]);
        int low = hex_nibble((unsigned char)hex[offset + 1]);
        if (high < 0 || low < 0) return -1;
        decoded[used++] = (unsigned char)((high << 4) | low);
        if (used == sizeof decoded) {
            hapaneld_sha256_update(hash, decoded, used);
            used = 0;
        }
    }
    if (used) hapaneld_sha256_update(hash, decoded, used);
    return 0;
}

static void sha_frame_ascii(hapaneld_sha256 *hash, const char *value) {
    const unsigned char present = 1;
    unsigned char framed_length[8];
    uint64_t bytes = (uint64_t)strlen(value);
    for (int shift = 56, index = 0; shift >= 0; shift -= 8, index++)
        framed_length[index] = (unsigned char)((bytes >> shift) & 0xffU);
    hapaneld_sha256_update(hash, &present, 1);
    hapaneld_sha256_update(hash, framed_length, sizeof framed_length);
    hapaneld_sha256_update(hash, value, (size_t)bytes);
}

static int canonical_signed_decimal(const char *value) {
    if (!value || !*value) return 0;
    const char *digits = value;
    if (*digits == '-') {
        digits++;
        if (!*digits || strcmp(digits, "0") == 0) return 0;
    }
    if (digits[0] == '0' && digits[1] != '\0') return 0;
    for (const char *p = digits; *p; p++) if (*p < '0' || *p > '9') return 0;
    return 1;
}

static int split_row_fields(char *line, char *fields[5]) {
    fields[0] = line;
    for (int index = 1; index < 5; index++) {
        char *separator = strchr(fields[index - 1], '|');
        if (!separator) return -1;
        *separator = '\0';
        fields[index] = separator + 1;
    }
    return strchr(fields[4], '|') ? -1 : 0;
}

static int hex_is_config(const char *hex) {
    static const char config_hex[] = "636f6e666967";
    if (strlen(hex) != sizeof config_hex - 1) return 0;
    for (size_t i = 0; i < sizeof config_hex - 1; i++)
        if ((unsigned char)(hex[i] | 0x20) != (unsigned char)config_hex[i]) return 0;
    return 1;
}

typedef struct {
    char *key;
    char *type;
    char *value;
    int known;
} guard_config_row;

static int hex_case_compare(const char *left, const char *right) {
    while (*left && *right) {
        unsigned char a = (unsigned char)*left++;
        unsigned char b = (unsigned char)*right++;
        if (a >= 'A' && a <= 'F') a = (unsigned char)(a + ('a' - 'A'));
        if (b >= 'A' && b <= 'F') b = (unsigned char)(b + ('a' - 'A'));
        if (a != b) return a < b ? -1 : 1;
    }
    return *left ? 1 : (*right ? -1 : 0);
}

static int settings_v2_digest(int dir, guard_config_row *config, size_t config_count,
                              char output[65]) {
    int fd = openat(dir, GUARD_SETTINGS, O_RDONLY | O_NOFOLLOW | O_CLOEXEC);
    struct stat st;
    if (fd < 0 || fstat(fd, &st) != 0 || st.st_size <= 0 ||
        st.st_size > (off_t)GUARD_MAX_SETTINGS_BYTES) {
        if (fd >= 0) close(fd);
        return -1;
    }
    char *authority = malloc((size_t)st.st_size + 1);
    if (!authority) { close(fd); return -1; }
    size_t used = 0;
    while (used < (size_t)st.st_size) {
        ssize_t count = read(fd, authority + used, (size_t)st.st_size - used);
        if (count < 0 && errno == EINTR) continue;
        if (count <= 0) break;
        used += (size_t)count;
    }
    close(fd);
    authority[used] = '\0';
    if (used != (size_t)st.st_size || used < 4 || memcmp(authority, "S2\n", 3) != 0) {
        free(authority);
        return -1;
    }
    hapaneld_sha256 settings;
    hapaneld_sha256_init(&settings);
    sha_frame_ascii(&settings, "guard-db/settings/v2");
    char *cursor = authority + 3;
    int valid = 1;
    while (valid && *cursor) {
        char *end = strchr(cursor, '\n');
        if (!end) { valid = 0; break; }
        *end = '\0';
        char *key = cursor;
        char *type = strchr(key, '|');
        if (type) *type++ = '\0';
        char *value = type ? strchr(type, '|') : NULL;
        if (value) *value++ = '\0';
        if (!type || !value || strchr(value, '|')) { valid = 0; break; }
        guard_config_row *present = NULL;
        for (size_t i = 0; i < config_count; i++) {
            if (hex_case_compare(config[i].key, key) == 0) {
                present = &config[i];
                present->known = 1;
                break;
            }
        }
        sha_frame_ascii(&settings, "config-effective-v2");
        valid = sha_frame_hex(&settings, key, 0) == 0 &&
            sha_frame_hex(&settings, present ? present->type : type, 0) == 0;
        if (valid) {
            if (present && strcmp(present->value, "N") != 0)
                valid = sha_frame_hex(&settings, present->value, 1) == 0;
            else
                valid = sha_frame_hex(&settings, value, 0) == 0;
        }
        cursor = end + 1;
    }
    for (size_t i = 0; valid && i < config_count; i++) {
        if (config[i].known) continue;
        sha_frame_ascii(&settings, "config-extra-v2");
        valid = sha_frame_hex(&settings, config[i].key, 0) == 0 &&
            sha_frame_hex(&settings, config[i].type, 0) == 0 &&
            sha_frame_hex(&settings, config[i].value, 1) == 0;
    }
    unsigned char digest[32];
    if (valid) {
        hapaneld_sha256_final(&settings, digest);
        hapaneld_sha256_hex(digest, output);
    }
    free(authority);
    return valid ? 0 : -1;
}

static int parse_sqlite_semantic_output(int dir, char *output, uint32_t expected_schema,
                                        int expected_probe, const guard_plan *plan) {
    char *save = NULL;
    char *schema_line = strtok_r(output, "\n", &save);
    char *quick_line = strtok_r(NULL, "\n", &save);
    char *probe_line = strtok_r(NULL, "\n", &save);
    char *count_line = strtok_r(NULL, "\n", &save);
    uint32_t schema, probe;
    uint64_t declared_count;
    if (!schema_line || !quick_line || !probe_line || !count_line ||
        parse_u32(schema_line, 1, UINT32_MAX, &schema) != 0 || schema != expected_schema ||
        strcmp(quick_line, "ok") != 0 || parse_u32(probe_line, 0, 1, &probe) != 0 ||
        probe != (uint32_t)expected_probe ||
        parse_u64(count_line, 1, UINT64_MAX, &declared_count) != 0 ||
        declared_count != plan->baseline_app_state) return -1;
    if (declared_count > 65536) return -1;
    guard_config_row *config = calloc((size_t)declared_count, sizeof *config);
    if (!config) return -1;
    hapaneld_sha256 ordered;
    hapaneld_sha256_init(&ordered);
    sha_frame_ascii(&ordered, "guard-db/app-state/v1");
    uint64_t rows = 0;
    size_t config_count = 0;
    for (char *line = strtok_r(NULL, "\n", &save); line; line = strtok_r(NULL, "\n", &save)) {
        char *fields[5];
        if (split_row_fields(line, fields) != 0 || !canonical_signed_decimal(fields[4]) ||
            sha_frame_hex(&ordered, fields[0], 0) != 0 ||
            sha_frame_hex(&ordered, fields[1], 0) != 0 ||
            sha_frame_hex(&ordered, fields[2], 0) != 0 ||
            sha_frame_hex(&ordered, fields[3], 1) != 0) {
            free(config);
            return -1;
        }
        sha_frame_ascii(&ordered, fields[4]);
        if (hex_is_config(fields[0])) {
            config[config_count].key = fields[1];
            config[config_count].type = fields[2];
            config[config_count].value = fields[3];
            config_count++;
        }
        if (rows == UINT64_MAX) { free(config); return -1; }
        rows++;
    }
    unsigned char digest[32];
    char ordered_hex[65], settings_hex[65];
    hapaneld_sha256_final(&ordered, digest);
    hapaneld_sha256_hex(digest, ordered_hex);
    int settings_valid = settings_v2_digest(dir, config, config_count, settings_hex) == 0;
    free(config);
    return settings_valid && rows == declared_count &&
        strcmp(ordered_hex, plan->baseline_app_state_sha) == 0 &&
        strcmp(settings_hex, plan->baseline_settings_sha) == 0 ? 0 : -1;
}

static int db_label_matches(int fd, const guard_plan *plan) {
    char label[GUARD_LABEL_BYTES];
    ssize_t length = fgetxattr(fd, "security.selinux", label, sizeof label);
#ifdef HAPANELD_TEST
    if (length < 0 && (errno == ENODATA || errno == ENOTSUP))
        return strcmp(plan->db_label, "NONE") == 0;
#endif
    return length > 0 && (size_t)length == strlen(plan->db_label) &&
        memcmp(label, plan->db_label, (size_t)length) == 0;
}

static int live_db_semantic_exact(int custody, const guard_plan *plan,
                                  uint32_t expected_schema, int expected_probe) {
    int db_dir = open_app_db_dir(plan);
    if (db_dir < 0) return -1;
    struct stat dir_before, dir_after;
    guard_file_snapshot before[3], after[3];
    static const char *const names[] = {
        GUARD_DB_NAME, GUARD_DB_NAME "-wal", GUARD_DB_NAME "-shm",
    };
    int db = openat(db_dir, GUARD_DB_NAME, O_RDONLY | O_NOFOLLOW | O_CLOEXEC);
    struct stat db_st;
    uint32_t raw_schema = 0;
    uint64_t source_bytes = 0;
    char source_sha[65];
    int exact = fstat(db_dir, &dir_before) == 0 &&
        (uint64_t)dir_before.st_dev == plan->db_dir_dev &&
        (uint64_t)dir_before.st_ino == plan->db_dir_ino && db >= 0 &&
        fstat(db, &db_st) == 0 && S_ISREG(db_st.st_mode) && db_st.st_nlink == 1 &&
        (uint64_t)db_st.st_uid == plan->db_uid && (uint64_t)db_st.st_gid == plan->db_gid &&
        (uint32_t)(db_st.st_mode & 07777) == plan->db_mode && db_label_matches(db, plan) &&
        db_st.st_size > 0 && (uint64_t)db_st.st_size <= GUARD_MAX_DB_BYTES &&
        (source_bytes = (uint64_t)db_st.st_size) > 0 &&
        sqlite_schema_fd(db, &raw_schema) == 0 && raw_schema == expected_schema &&
        hapaneld_sha256_fd(db, source_bytes, source_sha) == 0 &&
        known_file_present(db_dir, GUARD_DB_NAME "-journal") == 0 &&
        known_file_present(db_dir, GUARD_DB_NAME ".restore.tmp") == 0;
    for (size_t i = 0; exact && i < sizeof names / sizeof names[0]; i++)
        exact = snapshot_regular_at(db_dir, names[i], i == 0, &before[i]) == 0;
    if (exact && before[1].present)
        exact = before[1].stat.st_size == 0 &&
            (uint64_t)before[1].stat.st_uid == plan->db_uid &&
            (uint64_t)before[1].stat.st_gid == plan->db_gid;
    if (exact && before[2].present)
        exact = (uint64_t)before[2].stat.st_uid == plan->db_uid &&
            (uint64_t)before[2].stat.st_gid == plan->db_gid;
    if (exact)
        exact = copy_exact_to_custody(custody, db, GUARD_HEALTH_COPY_TMP,
            GUARD_HEALTH_COPY, source_bytes, source_sha) == 0;
    if (db >= 0) close(db);
    char *output = exact ? malloc((size_t)GUARD_SQLITE_PROOF_MAX_BYTES + 1) : NULL;
    static const char sql[] =
        "PRAGMA query_only=ON;BEGIN;PRAGMA user_version;PRAGMA quick_check(1);"
        "SELECT count(*) FROM sqlite_master WHERE type='table' AND name='" GUARD_PROBE_TABLE "';"
        "SELECT count(*) FROM app_state;"
        "SELECT hex(CAST(namespace AS BLOB)),hex(CAST(state_key AS BLOB)),"
        "hex(CAST(value_type AS BLOB)),CASE WHEN value_text IS NULL THEN 'N' "
        "ELSE 'V'||hex(CAST(value_text AS BLOB)) END,CAST(updated_at AS TEXT) "
        "FROM app_state ORDER BY namespace COLLATE BINARY,state_key COLLATE BINARY;COMMIT;";
    const char *const argv[] = {
        "sqlite3", "-batch", "-noheader", "-separator", "|", "-uri",
        GUARD_HEALTH_COPY_URI, sql, NULL,
    };
    int timed_out = 0;
    int status = output ? sysexec_capture_argv_timeout(
        GUARD_SQLITE_PATH, argv, output, (size_t)GUARD_SQLITE_PROOF_MAX_BYTES + 1,
        GUARD_SQLITE_PROOF_TIMEOUT_MS, &timed_out) : -1;
    exact = exact && output && status == 0 && !timed_out &&
        parse_sqlite_semantic_output(custody, output, expected_schema, expected_probe, plan) == 0;
    free(output);
    exact = exact && known_file_present(db_dir, GUARD_DB_NAME "-journal") == 0 &&
        known_file_present(db_dir, GUARD_DB_NAME ".restore.tmp") == 0 &&
        known_file_present(custody, GUARD_HEALTH_COPY "-wal") == 0 &&
        known_file_present(custody, GUARD_HEALTH_COPY "-shm") == 0 &&
        known_file_present(custody, GUARD_HEALTH_COPY "-journal") == 0;
    for (size_t i = 0; exact && i < sizeof names / sizeof names[0]; i++) {
        exact = snapshot_regular_at(db_dir, names[i], i == 0, &after[i]) == 0 &&
            snapshot_unchanged(&before[i], &after[i]);
    }
    int verify = exact
        ? openat(db_dir, GUARD_DB_NAME, O_RDONLY | O_NOFOLLOW | O_CLOEXEC) : -1;
    char verify_sha[65];
    exact = exact && verify >= 0 && hapaneld_sha256_fd(verify, source_bytes, verify_sha) == 0 &&
        strcmp(verify_sha, source_sha) == 0 && fstat(db_dir, &dir_after) == 0 &&
        dir_before.st_dev == dir_after.st_dev && dir_before.st_ino == dir_after.st_ino &&
        dir_before.st_mtim.tv_sec == dir_after.st_mtim.tv_sec &&
        dir_before.st_mtim.tv_nsec == dir_after.st_mtim.tv_nsec &&
        dir_before.st_ctim.tv_sec == dir_after.st_ctim.tv_sec &&
        dir_before.st_ctim.tv_nsec == dir_after.st_ctim.tv_nsec;
    if (verify >= 0) close(verify);
    int removed_copy = remove_fixed_nondir(custody, GUARD_HEALTH_COPY) == 0 &&
        remove_fixed_nondir(custody, GUARD_HEALTH_COPY_TMP) == 0 && fsync(custody) == 0;
    close(db_dir);
    return exact && removed_copy ? 0 : -1;
}

static int settle_submitted_locked(int dir, const guard_plan *plan, guard_journal *journal,
                                   const char *failure) {
    const char *role = journal->phase == GUARD_PHASE_SUBMITTED_A ? "A" : "B";
    const guard_artifact *artifact = strcmp(role, "A") == 0 ? &plan->a : &plan->b;
    int exact = installed_apk_stable_exact(plan->package, artifact) == 0;
    journal->generation++;
    journal->deadline_ms = 0;
    journal->pm_settled = 1;
    if (exact) {
        uint64_t now = monotonic_ms();
        if (now == 0 || now > UINT64_MAX - GUARD_HEALTH_TIMEOUT_MS) exact = 0;
        else {
            journal->phase = strcmp(role, "A") == 0
                ? GUARD_PHASE_WAIT_A_HEALTH : GUARD_PHASE_WAIT_B_HEALTH;
            journal_set_role(journal, role, artifact);
            uint64_t phase_deadline = now + GUARD_HEALTH_TIMEOUT_MS;
            uint64_t phase_limit = strcmp(role, "A") == 0
                ? plan->overall_deadline_ms : plan->forward_deadline_ms;
            journal->deadline_ms = phase_deadline < phase_limit ? phase_deadline : phase_limit;
            snprintf(journal->error, sizeof journal->error, "NONE");
        }
    }
    if (!exact) {
        journal->phase = GUARD_PHASE_AMBIGUOUS;
        journal_clear_role(journal);
        snprintf(journal->error, sizeof journal->error, "%s", failure);
        snprintf(journal->outcome, sizeof journal->outcome, "AMBIGUOUS");
    }
    return store_journal_at(dir, journal) == 0 ? (exact ? 1 : 0) : -1;
}

static int capture_baseline(int custody, guard_plan *plan) {
    if ((!guard_maintenance_supervised() && !guard_maintenance_supervisor_authoritative()) ||
        !plan->a.staged || !plan->b.staged ||
        installed_apk_exact(plan->package, &plan->a) != 0 || check_space(plan->baseline_bytes) != 0) return -1;
    int db_dir = open_app_db_dir(plan);
    if (db_dir < 0) return -1;
    int db = openat(db_dir, GUARD_DB_NAME, O_RDONLY | O_NOFOLLOW | O_CLOEXEC);
    struct stat dir_st, before, after;
    uint32_t schema;
    char actual[65];
    int valid = db >= 0 && fstat(db_dir, &dir_st) == 0 && fstat(db, &before) == 0 &&
        S_ISREG(before.st_mode) && before.st_nlink == 1 && before.st_uid == dir_st.st_uid &&
        canonicalize_capture_sidecars(db_dir, before.st_uid, before.st_gid) == 0 &&
        (uint64_t)before.st_size == plan->baseline_bytes &&
        sqlite_schema_fd(db, &schema) == 0 && schema == plan->baseline_schema &&
        hapaneld_sha256_fd(db, plan->baseline_bytes, actual) == 0 &&
        strcmp(actual, plan->baseline_sha) == 0;
    ssize_t label_length = valid ? fgetxattr(db, "security.selinux", plan->db_label,
        sizeof plan->db_label - 1) : -1;
#ifdef HAPANELD_TEST
    if (valid && label_length < 0 && (errno == ENODATA || errno == ENOTSUP)) {
        snprintf(plan->db_label, sizeof plan->db_label, "NONE");
        label_length = 4;
    }
#endif
    if (label_length <= 0 || label_length >= (ssize_t)sizeof plan->db_label) valid = 0;
    else plan->db_label[label_length] = 0;
    int baseline_publish = valid ? copy_baseline(custody, db, plan) : GUARD_PUBLISH_FAILED;
    if (baseline_publish != GUARD_PUBLISH_COMMITTED) valid = 0;
    if (valid && (fstat(db, &after) != 0 || before.st_dev != after.st_dev ||
        before.st_ino != after.st_ino || before.st_size != after.st_size ||
        before.st_mtim.tv_sec != after.st_mtim.tv_sec || before.st_mtim.tv_nsec != after.st_mtim.tv_nsec ||
        !fixed_db_sidecars_absent(db_dir))) valid = 0;
    if (valid) {
        plan->captured = 1;
        plan->db_dir_dev = (uint64_t)dir_st.st_dev;
        plan->db_dir_ino = (uint64_t)dir_st.st_ino;
        plan->db_dev = (uint64_t)before.st_dev;
        plan->db_ino = (uint64_t)before.st_ino;
        plan->db_uid = (uint64_t)before.st_uid;
        plan->db_gid = (uint64_t)before.st_gid;
        plan->db_mode = (uint32_t)(before.st_mode & 07777);
    }
    if (db >= 0) close(db);
    close(db_dir);
    return valid ? GUARD_PUBLISH_COMMITTED :
        (baseline_publish == GUARD_PUBLISH_INDETERMINATE
            ? GUARD_PUBLISH_INDETERMINATE : GUARD_PUBLISH_FAILED);
}

/* The capture record is the durable authority boundary before force-stop.  It is bound to the
 * exact last staging draft, so a restarted supervisor can neither mix a newer draft nor guess
 * whether package mutation was allowed. */
static int complete_capture_locked(int dir, guard_plan *plan, guard_capture *capture) {
    uint64_t now = monotonic_ms();
    if ((!guard_maintenance_supervised() && !guard_maintenance_supervisor_authoritative()) ||
        !capture_matches_draft(capture, plan) || now == 0 ||
        now >= plan->forward_deadline_ms || installed_apk_stable_exact(plan->package, &plan->a) != 0)
        return -1;

    if (capture->state == GUARD_CAPTURE_INTENT) {
        capture->state = GUARD_CAPTURE_STOP_ATTEMPTED;
        int stop_publish = store_capture_at(dir, capture);
        if (stop_publish != GUARD_PUBLISH_COMMITTED) return stop_publish;
    }
    if (capture->state != GUARD_CAPTURE_STOP_ATTEMPTED || force_stop_guard_app(plan->package) != 0)
        return GUARD_PUBLISH_FAILED;
    int baseline_publish = capture_baseline(dir, plan);
    if (baseline_publish != GUARD_PUBLISH_COMMITTED) return baseline_publish;

    plan->generation++;
    int manifest_publish = store_plan_at(dir, GUARD_MANIFEST, GUARD_MANIFEST_TMP, plan);
    if (manifest_publish != GUARD_PUBLISH_COMMITTED) return manifest_publish;
    guard_journal journal;
    if (initialize_journal(plan, &journal) != 0) return GUARD_PUBLISH_FAILED;
    int journal_publish = store_journal_at(dir, &journal);
    if (journal_publish != GUARD_PUBLISH_COMMITTED) return journal_publish;
    /* Compatibility fault reproduces the former draft-first cut so startup reconciliation
     * continues to prove that already-published manifest+journal authority. */
    if (TEST_FAULT(GUARD_TEST_FAULT_CAPTURE_LEGACY_DRAFT_REMOVED)) {
        if (unlinkat(dir, GUARD_DRAFT, 0) != 0 || fsync(dir) != 0)
            return GUARD_PUBLISH_FAILED;
        return GUARD_PUBLISH_INDETERMINATE;
    }
    return cleanup_committed_capture_at(dir, 1, 1);
}

static int start_capture_locked(int dir, const guard_plan *plan, guard_capture *capture) {
    if (initialize_capture(plan, capture) != 0) return GUARD_PUBLISH_FAILED;
    return store_capture_at(dir, capture);
}

static int recovery_database_name(char output[128], uint32_t schema, const char *kind) {
    int length = snprintf(output, 128, "%s.v%u.%s", GUARD_DB_NAME, schema, kind);
    return length > 0 && length < 128 ? 0 : -1;
}

static int named_sidecars_absent(int dir, const char *name) {
    static const char *const suffixes[] = { "-wal", "-shm", "-journal", ".guard.tmp" };
    char candidate[192];
    for (size_t i = 0; i < sizeof suffixes / sizeof suffixes[0]; i++) {
        int length = snprintf(candidate, sizeof candidate, "%s%s", name, suffixes[i]);
        if (length <= 0 || (size_t)length >= sizeof candidate ||
            known_file_present(dir, candidate) != 0) return 0;
    }
    return 1;
}

static int source_file_exact_metadata(int fd, const guard_plan *plan, uint32_t schema,
                                      struct stat *snapshot, uint64_t *bytes, char sha[65]) {
    uint32_t observed_schema = 0;
    return fstat(fd, snapshot) == 0 && S_ISREG(snapshot->st_mode) && snapshot->st_nlink == 1 &&
        (uint64_t)snapshot->st_uid == plan->db_uid && (uint64_t)snapshot->st_gid == plan->db_gid &&
        (uint32_t)(snapshot->st_mode & 07777) == plan->db_mode &&
        snapshot->st_size > 0 && (uint64_t)snapshot->st_size <= GUARD_MAX_DB_BYTES &&
        db_label_matches(fd, plan) && sqlite_schema_fd(fd, &observed_schema) == 0 &&
        observed_schema == schema && (*bytes = (uint64_t)snapshot->st_size) > 0 &&
        hapaneld_sha256_fd(fd, *bytes, sha) == 0 ? 0 : -1;
}

static int source_file_unchanged(int fd, const struct stat *before) {
    struct stat after;
    return fstat(fd, &after) == 0 && before->st_dev == after.st_dev &&
        before->st_ino == after.st_ino && before->st_uid == after.st_uid &&
        before->st_gid == after.st_gid && before->st_mode == after.st_mode &&
        before->st_nlink == after.st_nlink && before->st_size == after.st_size &&
        before->st_mtim.tv_sec == after.st_mtim.tv_sec &&
        before->st_mtim.tv_nsec == after.st_mtim.tv_nsec;
}

static int seal_recovery_custody(int custody, const guard_plan *plan,
                                 guard_journal *journal) {
    int db_dir = open_app_db_dir(plan);
    char premigrate_name[128];
    if (db_dir < 0 || recovery_database_name(
            premigrate_name, plan->a.expected_schema, "premigrate") != 0) {
        if (db_dir >= 0) close(db_dir);
        return -1;
    }
    struct stat dir_st;
    int main_fd = openat(db_dir, GUARD_DB_NAME, O_RDONLY | O_NOFOLLOW | O_CLOEXEC);
    int premigrate_fd = openat(db_dir, premigrate_name, O_RDONLY | O_NOFOLLOW | O_CLOEXEC);
    struct stat main_before, premigrate_before;
    uint64_t main_bytes = 0, premigrate_bytes = 0;
    char main_sha[65], premigrate_sha[65];
    int exact = fstat(db_dir, &dir_st) == 0 &&
        (uint64_t)dir_st.st_dev == plan->db_dir_dev &&
        (uint64_t)dir_st.st_ino == plan->db_dir_ino &&
        main_fd >= 0 && premigrate_fd >= 0 &&
        source_file_exact_metadata(main_fd, plan, plan->b.expected_schema,
            &main_before, &main_bytes, main_sha) == 0 &&
        checkpointed_db_sidecars_safe(db_dir, plan) &&
        canonicalize_capture_sidecars(db_dir, main_before.st_uid, main_before.st_gid) == 0 &&
        named_sidecars_absent(db_dir, premigrate_name) &&
        source_file_exact_metadata(premigrate_fd, plan, plan->a.expected_schema,
            &premigrate_before, &premigrate_bytes, premigrate_sha) == 0 &&
        premigrate_bytes == plan->baseline_bytes &&
        strcmp(premigrate_sha, plan->baseline_sha) == 0;
    int empty_intent = journal->premigrate_bytes == 0 && journal->b_primary_bytes == 0 &&
        strcmp(journal->premigrate_sha, "NONE") == 0 &&
        strcmp(journal->b_primary_sha, "NONE") == 0;
    int exact_intent = exact && journal->phase == GUARD_PHASE_B_HEALTHY &&
        journal->premigrate_bytes == premigrate_bytes &&
        strcmp(journal->premigrate_sha, premigrate_sha) == 0 &&
        journal->b_primary_bytes == main_bytes && strcmp(journal->b_primary_sha, main_sha) == 0;
    int intent_publish = GUARD_PUBLISH_COMMITTED;
    if (exact && empty_intent) {
        journal->premigrate_bytes = premigrate_bytes;
        snprintf(journal->premigrate_sha, sizeof journal->premigrate_sha, "%s", premigrate_sha);
        journal->b_primary_bytes = main_bytes;
        snprintf(journal->b_primary_sha, sizeof journal->b_primary_sha, "%s", main_sha);
        intent_publish = store_journal_at(custody, journal);
        exact_intent = intent_publish == GUARD_PUBLISH_COMMITTED;
    }
    exact = exact && exact_intent;
    int premigrate_publish = exact
        ? copy_exact_to_custody(custody, premigrate_fd, GUARD_PREMIGRATE_TMP,
            GUARD_PREMIGRATE, premigrate_bytes, premigrate_sha)
        : GUARD_PUBLISH_FAILED;
    int primary_publish = premigrate_publish == GUARD_PUBLISH_COMMITTED
        ? copy_exact_to_custody(custody, main_fd, GUARD_B_PRIMARY_TMP,
            GUARD_B_PRIMARY, main_bytes, main_sha)
        : GUARD_PUBLISH_FAILED;
    int publication_cut = primary_publish == GUARD_PUBLISH_COMMITTED &&
        TEST_FAULT(GUARD_TEST_FAULT_RECOVERY_CUSTODY_PUBLISHED);
    exact = exact && premigrate_publish == GUARD_PUBLISH_COMMITTED &&
        primary_publish == GUARD_PUBLISH_COMMITTED &&
        !publication_cut &&
        source_file_unchanged(main_fd, &main_before) &&
        source_file_unchanged(premigrate_fd, &premigrate_before) &&
        checkpointed_db_sidecars_safe(db_dir, plan) &&
        named_sidecars_absent(db_dir, premigrate_name);
    if (main_fd >= 0) close(main_fd);
    if (premigrate_fd >= 0) close(premigrate_fd);
    close(db_dir);
    if (!exact) {
        if (intent_publish == GUARD_PUBLISH_INDETERMINATE || publication_cut ||
            premigrate_publish == GUARD_PUBLISH_INDETERMINATE ||
            primary_publish == GUARD_PUBLISH_INDETERMINATE)
            return GUARD_PUBLISH_INDETERMINATE;
        return GUARD_PUBLISH_FAILED;
    }
    return GUARD_PUBLISH_COMMITTED;
}

static int prepare_recovery_custody_intent(int custody, const guard_plan *plan,
                                           guard_journal *journal) {
    if (journal->phase != GUARD_PHASE_B_HEALTHY) return GUARD_PUBLISH_FAILED;
    int empty_intent = journal->premigrate_bytes == 0 && journal->b_primary_bytes == 0 &&
        strcmp(journal->premigrate_sha, "NONE") == 0 &&
        strcmp(journal->b_primary_sha, "NONE") == 0;
    if (!empty_intent)
        return journal->premigrate_bytes == plan->baseline_bytes &&
            strcmp(journal->premigrate_sha, plan->baseline_sha) == 0 &&
            journal->b_primary_bytes != 0 && lower_hex_64(journal->b_primary_sha)
            ? GUARD_PUBLISH_COMMITTED : GUARD_PUBLISH_FAILED;

    int db_dir = open_app_db_dir(plan);
    char premigrate_name[128];
    if (db_dir < 0 || recovery_database_name(
            premigrate_name, plan->a.expected_schema, "premigrate") != 0) {
        if (db_dir >= 0) close(db_dir);
        return GUARD_PUBLISH_FAILED;
    }
    struct stat dir_st, main_before, premigrate_before;
    int main_fd = openat(db_dir, GUARD_DB_NAME, O_RDONLY | O_NOFOLLOW | O_CLOEXEC);
    int premigrate_fd = openat(db_dir, premigrate_name, O_RDONLY | O_NOFOLLOW | O_CLOEXEC);
    uint64_t main_bytes = 0, premigrate_bytes = 0;
    char main_sha[65], premigrate_sha[65];
    int exact = fstat(db_dir, &dir_st) == 0 &&
        (uint64_t)dir_st.st_dev == plan->db_dir_dev &&
        (uint64_t)dir_st.st_ino == plan->db_dir_ino &&
        main_fd >= 0 && premigrate_fd >= 0 &&
        source_file_exact_metadata(main_fd, plan, plan->b.expected_schema,
            &main_before, &main_bytes, main_sha) == 0 &&
        checkpointed_db_sidecars_safe(db_dir, plan) &&
        named_sidecars_absent(db_dir, premigrate_name) &&
        source_file_exact_metadata(premigrate_fd, plan, plan->a.expected_schema,
            &premigrate_before, &premigrate_bytes, premigrate_sha) == 0 &&
        premigrate_bytes == plan->baseline_bytes &&
        strcmp(premigrate_sha, plan->baseline_sha) == 0 &&
        source_file_unchanged(main_fd, &main_before) &&
        source_file_unchanged(premigrate_fd, &premigrate_before);
    if (main_fd >= 0) close(main_fd);
    if (premigrate_fd >= 0) close(premigrate_fd);
    close(db_dir);
    if (!exact) return GUARD_PUBLISH_FAILED;

    journal->premigrate_bytes = premigrate_bytes;
    snprintf(journal->premigrate_sha, sizeof journal->premigrate_sha, "%s", premigrate_sha);
    journal->b_primary_bytes = main_bytes;
    snprintf(journal->b_primary_sha, sizeof journal->b_primary_sha, "%s", main_sha);
    return store_journal_at(custody, journal);
}

static int live_premigrate_state(const guard_plan *plan) {
    int db_dir = open_app_db_dir(plan);
    char premigrate_name[128];
    if (!db_dir_matches_plan(db_dir, plan) || recovery_database_name(
            premigrate_name, plan->a.expected_schema, "premigrate") != 0) {
        if (db_dir >= 0) close(db_dir);
        return -1;
    }
    int present = known_file_present(db_dir, premigrate_name);
    if (present == 0) {
        close(db_dir);
        return 0;
    }
    int fd = present == 1
        ? openat(db_dir, premigrate_name, O_RDONLY | O_NOFOLLOW | O_CLOEXEC) : -1;
    struct stat snapshot;
    uint64_t bytes = 0;
    char sha[65];
    int exact = fd >= 0 && named_sidecars_absent(db_dir, premigrate_name) &&
        source_file_exact_metadata(fd, plan, plan->a.expected_schema,
            &snapshot, &bytes, sha) == 0 &&
        bytes == plan->baseline_bytes && strcmp(sha, plan->baseline_sha) == 0;
    if (fd >= 0) close(fd);
    close(db_dir);
    return exact ? 1 : -1;
}

static int remove_live_premigrate(const guard_plan *plan) {
    int state = live_premigrate_state(plan);
    if (state <= 0) return state;
    int db_dir = open_app_db_dir(plan);
    char premigrate_name[128];
    if (db_dir < 0 || recovery_database_name(
            premigrate_name, plan->a.expected_schema, "premigrate") != 0) {
        if (db_dir >= 0) close(db_dir);
        return -1;
    }
    int removed = unlinkat(db_dir, premigrate_name, 0) == 0 && fsync(db_dir) == 0;
    close(db_dir);
    return removed ? 0 : -1;
}

static int live_primary_matches_b_custody(const guard_plan *plan,
                                          const guard_journal *journal) {
    int db_dir = open_app_db_dir(plan);
    if (!db_dir_matches_plan(db_dir, plan) || !checkpointed_db_sidecars_safe(db_dir, plan)) {
        if (db_dir >= 0) close(db_dir);
        return -1;
    }
    int fd = openat(db_dir, GUARD_DB_NAME, O_RDONLY | O_NOFOLLOW | O_CLOEXEC);
    struct stat snapshot;
    uint64_t bytes = 0;
    char sha[65];
    int exact = fd >= 0 &&
        source_file_exact_metadata(fd, plan, plan->b.expected_schema,
            &snapshot, &bytes, sha) == 0 &&
        bytes == journal->b_primary_bytes && strcmp(sha, journal->b_primary_sha) == 0;
    if (fd >= 0) close(fd);
    close(db_dir);
    return exact ? 0 : -1;
}

static int apply_database_metadata(int fd, const guard_plan *plan) {
    if (fchown(fd, (uid_t)plan->db_uid, (gid_t)plan->db_gid) != 0 ||
        fchmod(fd, (mode_t)plan->db_mode) != 0) return -1;
#ifdef HAPANELD_TEST
    if (strcmp(plan->db_label, "NONE") == 0) return 0;
#endif
    return fsetxattr(fd, "security.selinux", plan->db_label,
        strlen(plan->db_label), 0) == 0 ? 0 : -1;
}

static int restore_live_premigrate(int custody, const guard_plan *plan,
                                   const guard_journal *journal) {
    int state = live_premigrate_state(plan);
    if (state == 1) return 0;
    int primary_exact = live_primary_matches_b_custody(plan, journal) == 0;
    int custody_exact = hash_regular_at(custody, GUARD_PREMIGRATE,
        journal->premigrate_bytes, journal->premigrate_sha) == 0;
    if (state < 0 || !primary_exact || !custody_exact)
        return -1;
    int db_dir = open_app_db_dir(plan);
    char final[128], temporary[160];
    if (db_dir < 0 || recovery_database_name(
            final, plan->a.expected_schema, "premigrate") != 0 ||
        snprintf(temporary, sizeof temporary, "%s.guard.tmp", final) <= 0 ||
        remove_fixed_nondir(db_dir, temporary) != 0 ||
        !named_sidecars_absent(db_dir, final)) {
        if (db_dir >= 0) close(db_dir);
        return -1;
    }
    int source = openat(custody, GUARD_PREMIGRATE, O_RDONLY | O_NOFOLLOW | O_CLOEXEC);
    int output = openat(db_dir, temporary,
        O_WRONLY | O_CREAT | O_EXCL | O_NOFOLLOW | O_CLOEXEC, plan->db_mode);
    if (output >= 0 && TEST_FAULT(GUARD_TEST_FAULT_RESTORE_TEMP_CREATED)) {
        if (source >= 0) close(source);
        close(output);
        close(db_dir);
        return GUARD_PUBLISH_INDETERMINATE;
    }
    int copied = source >= 0 && output >= 0;
    uint64_t remaining = journal->premigrate_bytes;
    hapaneld_sha256 hash;
    hapaneld_sha256_init(&hash);
    unsigned char buffer[65536];
    while (copied && remaining > 0) {
        size_t wanted = remaining < sizeof buffer ? (size_t)remaining : sizeof buffer;
        ssize_t count = read(source, buffer, wanted);
        if (count < 0 && errno == EINTR) continue;
        if (count <= 0 || write_complete(output, buffer, (size_t)count) != 0) {
            copied = 0;
            break;
        }
        hapaneld_sha256_update(&hash, buffer, (size_t)count);
        remaining -= (uint64_t)count;
    }
    unsigned char digest[32];
    char sha[65];
    hapaneld_sha256_final(&hash, digest);
    hapaneld_sha256_hex(digest, sha);
    int durable = copied && remaining == 0 && strcmp(sha, journal->premigrate_sha) == 0 &&
        apply_database_metadata(output, plan) == 0 &&
        !TEST_FAULT(GUARD_TEST_FAULT_RESTORE_FILE_SYNC) && fsync(output) == 0;
    if (source >= 0) close(source);
    if (output >= 0 && close(output) != 0) durable = 0;
    int file_cut = TEST_FAULT(GUARD_TEST_FAULT_RESTORE_FILE_SYNC);
    int rename_cut = TEST_FAULT(GUARD_TEST_FAULT_RESTORE_RENAME);
    if (!durable || rename_cut || renameat(db_dir, temporary, db_dir, final) != 0) {
        (void)remove_fixed_nondir(db_dir, temporary);
        close(db_dir);
        return file_cut || rename_cut ? GUARD_PUBLISH_INDETERMINATE
                                     : GUARD_PUBLISH_FAILED;
    }
    if (TEST_FAULT(GUARD_TEST_FAULT_RESTORE_DIR_SYNC) || fsync(db_dir) != 0) {
        close(db_dir);
        return GUARD_PUBLISH_INDETERMINATE;
    }
    close(db_dir);
    return live_premigrate_state(plan) == 1 ? 0 : -1;
}

static int resume_restore_intent_locked(int dir, const guard_plan *plan,
                                        guard_journal *journal) {
    if (journal->phase != GUARD_PHASE_A_REFUSED ||
        strcmp(journal->error, "RESTORE_INTENT") != 0 ||
        installed_apk_stable_exact(plan->package, &plan->b) != 0 ||
        force_stop_guard_app(plan->package) != 0)
        return GUARD_PUBLISH_FAILED;
    if (TEST_FAULT(GUARD_TEST_FAULT_RESTORE_AFTER_FORCE_STOP))
        return GUARD_PUBLISH_INDETERMINATE;
    int restored = restore_live_premigrate(dir, plan, journal);
    if (restored != GUARD_PUBLISH_COMMITTED) return restored;
    journal->generation++;
    journal->phase = GUARD_PHASE_RECOVERY_RESTORED;
    journal->deadline_ms = 0;
    journal_set_role(journal, "B", &plan->b);
    snprintf(journal->error, sizeof journal->error, "NONE");
    return store_journal_at(dir, journal);
}

static int remove_live_recovery_artifact_exact(int db_dir, const char *name,
                                               const guard_plan *plan, uint32_t schema,
                                               uint64_t bytes, const char *sha) {
    int present = known_file_present(db_dir, name);
    if (present <= 0) return present;
    if (!named_sidecars_absent(db_dir, name)) return -1;
    int fd = openat(db_dir, name, O_RDONLY | O_NOFOLLOW | O_CLOEXEC);
    struct stat snapshot;
    uint64_t actual_bytes = 0;
    char actual_sha[65];
    int exact = fd >= 0 && source_file_exact_metadata(fd, plan, schema,
        &snapshot, &actual_bytes, actual_sha) == 0 && actual_bytes == bytes &&
        strcmp(actual_sha, sha) == 0;
    if (fd >= 0) close(fd);
    return exact && unlinkat(db_dir, name, 0) == 0 ? 0 : -1;
}

static int app_restore_rollback_preflight(int db_dir, const guard_plan *plan,
                                          const guard_journal *journal,
                                          int *primary_may_be_absent);
static int cleanup_rollback_recovery_artifacts(int db_dir, const guard_plan *plan,
                                               const guard_journal *journal);

static int restore_baseline_primary(int custody, const guard_plan *plan,
                                    const guard_journal *journal) {
    if (installed_apk_stable_exact(plan->package, &plan->a) != 0 ||
        force_stop_guard_app(plan->package) != 0 ||
        hash_regular_at(custody, GUARD_BASELINE, plan->baseline_bytes,
                        plan->baseline_sha) != 0)
        return GUARD_PUBLISH_FAILED;
    int db_dir = open_app_db_dir(plan);
    struct stat dir_st;
    if (db_dir < 0 || fstat(db_dir, &dir_st) != 0 ||
        (uint64_t)dir_st.st_dev != plan->db_dir_dev ||
        (uint64_t)dir_st.st_ino != plan->db_dir_ino ||
        canonicalize_capture_sidecars(db_dir, (uid_t)plan->db_uid,
                                      (gid_t)plan->db_gid) != 0) {
        if (db_dir >= 0) close(db_dir);
        return GUARD_PUBLISH_FAILED;
    }
    int primary_may_be_absent = 0;
    if (app_restore_rollback_preflight(
            db_dir, plan, journal, &primary_may_be_absent) != 0) {
        close(db_dir);
        return GUARD_PUBLISH_FAILED;
    }

    int primary_state = known_file_present(db_dir, GUARD_DB_NAME);
    if (primary_state < 0) { close(db_dir); return GUARD_PUBLISH_FAILED; }
    if (primary_state == 0 && !primary_may_be_absent) {
        close(db_dir);
        return GUARD_PUBLISH_FAILED;
    }
    if (primary_state == 1) {
        int primary = openat(db_dir, GUARD_DB_NAME, O_RDONLY | O_NOFOLLOW | O_CLOEXEC);
        struct stat snapshot;
        uint64_t bytes = 0;
        char sha[65];
        uint32_t schema = 0;
        int exact = primary >= 0 && fstat(primary, &snapshot) == 0 &&
            S_ISREG(snapshot.st_mode) && snapshot.st_nlink == 1 &&
            (uint64_t)snapshot.st_uid == plan->db_uid &&
            (uint64_t)snapshot.st_gid == plan->db_gid &&
            (uint32_t)(snapshot.st_mode & 07777) == plan->db_mode &&
            db_label_matches(primary, plan) && sqlite_schema_fd(primary, &schema) == 0 &&
            snapshot.st_size > 0 && (uint64_t)snapshot.st_size <= GUARD_MAX_DB_BYTES &&
            (bytes = (uint64_t)snapshot.st_size) > 0 &&
            hapaneld_sha256_fd(primary, bytes, sha) == 0 &&
            ((schema == plan->a.expected_schema && bytes == plan->baseline_bytes &&
              strcmp(sha, plan->baseline_sha) == 0) ||
             (schema == plan->b.expected_schema &&
              (journal->b_primary_bytes == 0 ||
               (bytes == journal->b_primary_bytes &&
                strcmp(sha, journal->b_primary_sha) == 0))));
        if (primary >= 0) close(primary);
        if (!exact) { close(db_dir); return GUARD_PUBLISH_FAILED; }
    }

    int source = openat(custody, GUARD_BASELINE, O_RDONLY | O_NOFOLLOW | O_CLOEXEC);
    int output = openat(db_dir, GUARD_ROLLBACK_TMP,
        O_WRONLY | O_CREAT | O_EXCL | O_NOFOLLOW | O_CLOEXEC, plan->db_mode);
    uint64_t remaining = plan->baseline_bytes;
    hapaneld_sha256 hash;
    hapaneld_sha256_init(&hash);
    unsigned char buffer[65536];
    int copied = source >= 0 && output >= 0;
    while (copied && remaining > 0) {
        size_t wanted = remaining < sizeof buffer ? (size_t)remaining : sizeof buffer;
        ssize_t count = read(source, buffer, wanted);
        if (count < 0 && errno == EINTR) continue;
        if (count <= 0 || write_complete(output, buffer, (size_t)count) != 0) {
            copied = 0;
            break;
        }
        hapaneld_sha256_update(&hash, buffer, (size_t)count);
        remaining -= (uint64_t)count;
    }
    unsigned char digest[32];
    char sha[65];
    hapaneld_sha256_final(&hash, digest);
    hapaneld_sha256_hex(digest, sha);
    int durable = copied && remaining == 0 && strcmp(sha, plan->baseline_sha) == 0 &&
        apply_database_metadata(output, plan) == 0 && fsync(output) == 0 &&
        !TEST_FAULT(GUARD_TEST_FAULT_DB_AFTER_ASIDE);
    if (source >= 0) close(source);
    if (output >= 0 && close(output) != 0) durable = 0;
    if (!durable || renameat(db_dir, GUARD_ROLLBACK_TMP, db_dir, GUARD_DB_NAME) != 0) {
        (void)remove_fixed_nondir(db_dir, GUARD_ROLLBACK_TMP);
        close(db_dir);
        return GUARD_PUBLISH_FAILED;
    }
    if (TEST_FAULT(GUARD_TEST_FAULT_DB_AFTER_PROMOTE) || fsync(db_dir) != 0) {
        close(db_dir);
        return GUARD_PUBLISH_INDETERMINATE;
    }
    int cleanup = cleanup_rollback_recovery_artifacts(db_dir, plan, journal);
    close(db_dir);
    return cleanup;
}

enum app_restore_phase {
    APP_RESTORE_PREPARED = 0,
    APP_RESTORE_SOURCE_ASIDE,
    APP_RESTORE_RESTORED,
};

typedef struct {
    enum app_restore_phase phase;
    uint32_t source_schema;
    uint64_t source_bytes;
    char source_sha[65];
    uint32_t staged_schema;
    uint64_t staged_bytes;
    char staged_sha[65];
    char guard_session[65];
    uint64_t guard_generation;
} app_restore_receipt;

static int ascii_hex(const char *value, char *output, size_t capacity) {
    static const char digits[] = "0123456789abcdef";
    size_t length = strlen(value);
    if (length == 0 || length > (capacity - 1) / 2) return -1;
    for (size_t index = 0; index < length; index++) {
        unsigned char byte = (unsigned char)value[index];
        output[index * 2] = digits[byte >> 4];
        output[index * 2 + 1] = digits[byte & 15];
    }
    output[length * 2] = '\0';
    return 0;
}

static int parse_app_restore_phase(const char *name, enum app_restore_phase *phase) {
    if (strcmp(name, "PREPARED") == 0) *phase = APP_RESTORE_PREPARED;
    else if (strcmp(name, "SOURCE_ASIDE") == 0) *phase = APP_RESTORE_SOURCE_ASIDE;
    else if (strcmp(name, "RESTORED") == 0) *phase = APP_RESTORE_RESTORED;
    else return -1;
    return 0;
}

static int split_exact_record_line(char *line, const char *name,
                                   char **fields, size_t field_count) {
    size_t name_length = strlen(name);
    if (!line || !name || field_count == 0 || strncmp(line, name, name_length) != 0 ||
        line[name_length] != ' ' || line[name_length + 1] == '\0') return -1;
    char *cursor = line + name_length + 1;
    for (size_t index = 0; index < field_count; index++) {
        if (*cursor == '\0' || *cursor == ' ') return -1;
        fields[index] = cursor;
        char *space = strchr(cursor, ' ');
        if (index + 1 == field_count) {
            if (space != NULL) return -1;
        } else {
            if (!space || space[1] == '\0' || space[1] == ' ') return -1;
            *space = '\0';
            cursor = space + 1;
        }
    }
    return 0;
}

static int stat_identity_unchanged_strict(const struct stat *before,
                                          const struct stat *after) {
    return before->st_dev == after->st_dev && before->st_ino == after->st_ino &&
        before->st_uid == after->st_uid && before->st_gid == after->st_gid &&
        before->st_mode == after->st_mode && before->st_nlink == after->st_nlink &&
        before->st_size == after->st_size &&
        before->st_mtim.tv_sec == after->st_mtim.tv_sec &&
        before->st_mtim.tv_nsec == after->st_mtim.tv_nsec &&
        before->st_ctim.tv_sec == after->st_ctim.tv_sec &&
        before->st_ctim.tv_nsec == after->st_ctim.tv_nsec;
}

static int db_dir_matches_plan(int db_dir, const guard_plan *plan) {
    struct stat directory;
    return db_dir >= 0 && fstat(db_dir, &directory) == 0 &&
        S_ISDIR(directory.st_mode) && (uint64_t)directory.st_dev == plan->db_dir_dev &&
        (uint64_t)directory.st_ino == plan->db_dir_ino;
}

static int read_app_restore_receipt_at(int db_dir, const char *name,
                                       const guard_plan *plan,
                                       app_restore_receipt *receipt) {
    int fd = openat(db_dir, name, O_RDONLY | O_NOFOLLOW | O_CLOEXEC);
    if (fd < 0) return errno == ENOENT ? 0 : -1;
    struct stat st, after, named;
    char record[GUARD_APP_RESTORE_MAX_RECORD_BYTES + 1];
    int exact = db_dir_matches_plan(db_dir, plan) && fstat(fd, &st) == 0 &&
        S_ISREG(st.st_mode) && st.st_nlink == 1 &&
        (uint64_t)st.st_uid == plan->db_uid && (uint64_t)st.st_gid == plan->db_gid &&
        (st.st_mode & 07777) == 0600 && db_label_matches(fd, plan) && st.st_size > 0 &&
        st.st_size <= GUARD_APP_RESTORE_MAX_RECORD_BYTES;
    size_t used = 0;
    while (exact && used < (size_t)st.st_size) {
        ssize_t count = read(fd, record + used, (size_t)st.st_size - used);
        if (count < 0 && errno == EINTR) continue;
        if (count <= 0) { exact = 0; break; }
        used += (size_t)count;
    }
    unsigned char extra;
    ssize_t extra_count = -1;
    if (exact) {
        do extra_count = read(fd, &extra, 1); while (extra_count < 0 && errno == EINTR);
        exact = extra_count == 0 && fstat(fd, &after) == 0 &&
            stat_identity_unchanged_strict(&st, &after) &&
            fstatat(db_dir, name, &named, AT_SYMLINK_NOFOLLOW) == 0 &&
            stat_identity_unchanged_strict(&st, &named);
    }
    close(fd);
    if (!exact || used == 0 || record[used - 1] != '\n') return -1;
    record[used] = '\0';
    for (size_t index = 0; index < used; index++) {
        unsigned char byte = (unsigned char)record[index];
        if (byte < 0x0a || byte > 0x7e || (byte > 0x0a && byte < 0x20)) return -1;
    }
    char *lines[8];
    size_t line_count = 0;
    char *cursor = record;
    while (line_count < 8) {
        lines[line_count++] = cursor;
        char *newline = strchr(cursor, '\n');
        if (!newline) return -1;
        *newline = '\0';
        cursor = newline + 1;
        if (*cursor == '\0') break;
    }
    if (line_count != 8 || *cursor != '\0' ||
        strcmp(lines[0], "HAPANELD_DATABASE_RESTORE_V1") != 0) return -1;
    size_t checksum_offset = (size_t)(lines[7] - record);
    for (size_t index = 0; index < checksum_offset; index++)
        if (record[index] == '\0') record[index] = '\n';
    if (strncmp(lines[7], "CHECKSUM ", 9) != 0 || !lower_hex_64(lines[7] + 9)) return -1;
    hapaneld_sha256 hash;
    unsigned char digest[32];
    char checksum[65];
    hapaneld_sha256_init(&hash);
    hapaneld_sha256_update(&hash, record, checksum_offset);
    hapaneld_sha256_final(&hash, digest);
    hapaneld_sha256_hex(digest, checksum);
    if (strcmp(checksum, lines[7] + 9) != 0) return -1;
    /* Restore NUL line terminators for exact bounded parsing below. */
    for (size_t index = 0; index < checksum_offset; index++)
        if (record[index] == '\n') record[index] = '\0';

    char *state_fields[1], *target_fields[2], *source_fields[3], *staged_fields[3];
    char *superseded_fields[1], *guard_fields[2];
    uint32_t source_schema = 0, staged_schema = 0;
    uint64_t source_bytes = 0, staged_bytes = 0, guard_generation = 0;
    if (split_exact_record_line(lines[1], "STATE", state_fields, 1) != 0 ||
        split_exact_record_line(lines[2], "TARGET", target_fields, 2) != 0 ||
        split_exact_record_line(lines[3], "SOURCE", source_fields, 3) != 0 ||
        split_exact_record_line(lines[4], "STAGED", staged_fields, 3) != 0 ||
        split_exact_record_line(lines[5], "SUPERSEDED", superseded_fields, 1) != 0 ||
        split_exact_record_line(lines[6], "GUARD", guard_fields, 2) != 0 ||
        parse_app_restore_phase(state_fields[0], &receipt->phase) != 0 ||
        parse_u32(source_fields[0], 1, UINT32_MAX, &source_schema) != 0 ||
        parse_u64(source_fields[1], 1, GUARD_MAX_DB_BYTES, &source_bytes) != 0 ||
        !lower_hex_64(source_fields[2]) ||
        parse_u32(staged_fields[0], 1, UINT32_MAX, &staged_schema) != 0 ||
        parse_u64(staged_fields[1], 1, GUARD_MAX_DB_BYTES, &staged_bytes) != 0 ||
        !lower_hex_64(staged_fields[2]) || !lower_hex_64(guard_fields[0]) ||
        parse_u64(guard_fields[1], 1, UINT64_MAX, &guard_generation) != 0) return -1;
    char expected_dir[1025], expected_name[321], expected_superseded[321], superseded[128];
    char db_dir_path[GUARD_APP_PATH_BYTES];
    if (guard_app_db_dir_path(plan->package, db_dir_path, sizeof db_dir_path) != 0 ||
        ascii_hex(db_dir_path, expected_dir, sizeof expected_dir) != 0 ||
        ascii_hex(GUARD_DB_NAME, expected_name, sizeof expected_name) != 0 ||
        recovery_database_name(superseded, plan->b.expected_schema, "superseded") != 0 ||
        ascii_hex(superseded, expected_superseded, sizeof expected_superseded) != 0 ||
        strcmp(target_fields[0], expected_dir) != 0 ||
        strcmp(target_fields[1], expected_name) != 0 ||
        strcmp(superseded_fields[0], expected_superseded) != 0) return -1;
    memset(receipt, 0, sizeof *receipt);
    if (parse_app_restore_phase(state_fields[0], &receipt->phase) != 0) return -1;
    receipt->source_schema = source_schema;
    receipt->source_bytes = source_bytes;
    snprintf(receipt->source_sha, sizeof receipt->source_sha, "%s", source_fields[2]);
    receipt->staged_schema = staged_schema;
    receipt->staged_bytes = staged_bytes;
    snprintf(receipt->staged_sha, sizeof receipt->staged_sha, "%s", staged_fields[2]);
    snprintf(receipt->guard_session, sizeof receipt->guard_session, "%s", guard_fields[0]);
    receipt->guard_generation = guard_generation;
    return 1;
}

static int app_restore_receipt_identity_matches(const app_restore_receipt *receipt,
                                                const guard_plan *plan,
                                                const guard_journal *journal,
                                                uint64_t expected_generation) {
    return expected_generation != 0 &&
        receipt->source_schema == plan->b.expected_schema &&
        receipt->source_bytes == journal->b_primary_bytes &&
        strcmp(receipt->source_sha, journal->b_primary_sha) == 0 &&
        receipt->staged_schema == plan->a.expected_schema &&
        receipt->staged_bytes == journal->premigrate_bytes &&
        strcmp(receipt->staged_sha, journal->premigrate_sha) == 0 &&
        strcmp(receipt->guard_session, plan->session) == 0 &&
        receipt->guard_generation == expected_generation;
}

static int app_restore_receipt_matches(const app_restore_receipt *receipt,
                                       const guard_plan *plan,
                                       const guard_journal *journal) {
    uint64_t expected_generation = journal->generation;
    if (journal->phase == GUARD_PHASE_A_HEALTHY) {
        if (expected_generation == 0) return 0;
        expected_generation--;
    } else if (journal->phase == GUARD_PHASE_FINALIZED) {
        if (expected_generation < 2) return 0;
        expected_generation -= 2;
    } else if (journal->phase == GUARD_PHASE_RETIRING) {
        if (expected_generation < 3) return 0;
        expected_generation -= 3;
    } else if (journal->phase != GUARD_PHASE_WAIT_A_HEALTH) {
        return 0;
    }
    return app_restore_receipt_identity_matches(
        receipt, plan, journal, expected_generation);
}

static int same_app_restore_transaction(const app_restore_receipt *left,
                                        const app_restore_receipt *right) {
    return left->source_schema == right->source_schema &&
        left->source_bytes == right->source_bytes &&
        strcmp(left->source_sha, right->source_sha) == 0 &&
        left->staged_schema == right->staged_schema &&
        left->staged_bytes == right->staged_bytes &&
        strcmp(left->staged_sha, right->staged_sha) == 0 &&
        strcmp(left->guard_session, right->guard_session) == 0 &&
        left->guard_generation == right->guard_generation;
}

/* Returns 1 for exact, 0 for absent, and -1 for any present non-exact object. */
static int named_database_exact_state(int db_dir, const char *name,
                                      const guard_plan *plan, uint32_t schema,
                                      uint64_t bytes, const char *sha) {
    int present = known_file_present(db_dir, name);
    if (present <= 0) return present;
    if (bytes == 0 || !lower_hex_64(sha) || !named_sidecars_absent(db_dir, name)) return -1;
    int fd = openat(db_dir, name, O_RDONLY | O_NOFOLLOW | O_CLOEXEC);
    struct stat snapshot;
    uint64_t actual_bytes = 0;
    char actual_sha[65];
    int exact = fd >= 0 && source_file_exact_metadata(fd, plan, schema,
        &snapshot, &actual_bytes, actual_sha) == 0 && actual_bytes == bytes &&
        strcmp(actual_sha, sha) == 0;
    if (fd >= 0) close(fd);
    return exact ? 1 : -1;
}

static int recovery_namespace_has_only_expected(int db_dir, const guard_plan *plan) {
    char premigrate[128], superseded[128];
    if (recovery_database_name(premigrate, plan->a.expected_schema, "premigrate") != 0 ||
        recovery_database_name(superseded, plan->b.expected_schema, "superseded") != 0)
        return 0;
    int duplicate = dup(db_dir);
    DIR *directory = duplicate >= 0 ? fdopendir(duplicate) : NULL;
    if (!directory) {
        if (duplicate >= 0) close(duplicate);
        return 0;
    }
    int exact = 1;
    struct dirent *entry;
    while ((entry = readdir(directory)) != NULL) {
        const char *name = entry->d_name;
        if (strncmp(name, GUARD_DB_NAME ".v", strlen(GUARD_DB_NAME ".v")) != 0 ||
            (!strstr(name, ".premigrate") && !strstr(name, ".superseded"))) continue;
        if (strcmp(name, premigrate) != 0 && strcmp(name, superseded) != 0) {
            exact = 0;
            break;
        }
    }
    closedir(directory);
    return exact;
}

static uint64_t expected_wait_a_health_generation(const guard_journal *journal) {
    /* PREPARED is the manifest generation. The fixed v1 forward path reaches
     * WAIT_A_HEALTH after nine durable successor transitions. */
    return journal->manifest_generation <= UINT64_MAX - 9
        ? journal->manifest_generation + 9 : 0;
}

static int app_restore_rollback_preflight(int db_dir, const guard_plan *plan,
                                          const guard_journal *journal,
                                          int *primary_may_be_absent) {
    if (!primary_may_be_absent || !db_dir_matches_plan(db_dir, plan) ||
        !recovery_namespace_has_only_expected(db_dir, plan)) return -1;
    *primary_may_be_absent = 0;
    app_restore_receipt current, temporary;
    int current_state = read_app_restore_receipt_at(
        db_dir, GUARD_APP_RESTORE_RECORD, plan, &current);
    int temporary_state = read_app_restore_receipt_at(
        db_dir, GUARD_APP_RESTORE_RECORD_TMP, plan, &temporary);
    if (current_state < 0 || temporary_state < 0) return -1;

    uint64_t expected_generation = expected_wait_a_health_generation(journal);
    if ((current_state == 1 && !app_restore_receipt_identity_matches(
            &current, plan, journal, expected_generation)) ||
        (temporary_state == 1 && !app_restore_receipt_identity_matches(
            &temporary, plan, journal, expected_generation)) ||
        (current_state == 1 && temporary_state == 1 &&
         (!same_app_restore_transaction(&current, &temporary) ||
          (temporary.phase != current.phase &&
           temporary.phase != (enum app_restore_phase)(current.phase + 1)))))
        return -1;

    int prepared = named_database_exact_state(db_dir, GUARD_APP_RESTORE_PREPARED,
        plan, plan->a.expected_schema, journal->premigrate_bytes,
        journal->premigrate_sha);
    char superseded_name[128];
    if (recovery_database_name(superseded_name, plan->b.expected_schema,
            "superseded") != 0) return -1;
    int superseded = named_database_exact_state(db_dir, superseded_name,
        plan, plan->b.expected_schema, journal->b_primary_bytes,
        journal->b_primary_sha);
    int primary_a = named_database_exact_state(db_dir, GUARD_DB_NAME,
        plan, plan->a.expected_schema, plan->baseline_bytes, plan->baseline_sha);
    int primary_b = named_database_exact_state(db_dir, GUARD_DB_NAME,
        plan, plan->b.expected_schema, journal->b_primary_bytes,
        journal->b_primary_sha);
    int primary_present = known_file_present(db_dir, GUARD_DB_NAME);
    int has_record = current_state == 1 || temporary_state == 1;
    int has_app_artifact = has_record || prepared == 1 || superseded == 1;
    if (prepared < 0 || superseded < 0 || primary_present < 0) return -1;
    if (!has_app_artifact && journal->b_primary_bytes == 0)
        return primary_present == 1 ? 0 : -1;
    if (primary_present == 1 && primary_a != 1 && primary_b != 1) return -1;
    if (!has_app_artifact) return primary_present == 1 ? 0 : -1;
    if (journal->premigrate_bytes == 0 || journal->b_primary_bytes == 0) return -1;

    enum app_restore_phase phase = current_state == 1 ? current.phase
        : (temporary_state == 1 ? temporary.phase : APP_RESTORE_PREPARED);
    int source_live = primary_b == 1 && prepared == 1 && superseded == 0;
    int source_aside = primary_present == 0 && prepared == 1 && superseded == 1;
    int restored = primary_a == 1 && prepared == 0 && superseded == 1;
    int helper_promoted = primary_a == 1;
    int phase_exact = !has_record || helper_promoted ||
        (phase == APP_RESTORE_PREPARED && (source_live || source_aside || restored)) ||
        (phase == APP_RESTORE_SOURCE_ASIDE && (source_aside || restored)) ||
        (phase == APP_RESTORE_RESTORED && restored);
    if (!phase_exact) return -1;
    if (!has_record && !helper_promoted && !source_live && !source_aside) return -1;
    *primary_may_be_absent = source_aside;
    return 0;
}

static int cleanup_rollback_recovery_artifacts(int db_dir, const guard_plan *plan,
                                               const guard_journal *journal) {
    int primary_may_be_absent = 0;
    if (app_restore_rollback_preflight(
            db_dir, plan, journal, &primary_may_be_absent) != 0 ||
        named_database_exact_state(db_dir, GUARD_DB_NAME, plan,
            plan->a.expected_schema, plan->baseline_bytes, plan->baseline_sha) != 1)
        return GUARD_PUBLISH_FAILED;
    char premigrate[128], superseded[128];
    if (recovery_database_name(premigrate, plan->a.expected_schema, "premigrate") != 0 ||
        recovery_database_name(superseded, plan->b.expected_schema, "superseded") != 0 ||
        remove_live_recovery_artifact_exact(db_dir, premigrate, plan,
            plan->a.expected_schema, plan->baseline_bytes, plan->baseline_sha) != 0 ||
        remove_live_recovery_artifact_exact(db_dir, superseded, plan,
            plan->b.expected_schema, journal->b_primary_bytes,
            journal->b_primary_sha) != 0 ||
        remove_live_recovery_artifact_exact(db_dir, GUARD_APP_RESTORE_PREPARED, plan,
            plan->a.expected_schema, journal->premigrate_bytes,
            journal->premigrate_sha) != 0 ||
        remove_fixed_nondir(db_dir, GUARD_APP_RESTORE_RECORD_TMP) != 0 ||
        remove_fixed_nondir(db_dir, GUARD_APP_RESTORE_RECORD) != 0 ||
        remove_fixed_nondir(db_dir, GUARD_ROLLBACK_TMP) != 0)
        return GUARD_PUBLISH_FAILED;
    return fsync(db_dir) == 0 ? GUARD_PUBLISH_COMMITTED : GUARD_PUBLISH_INDETERMINATE;
}

static int final_restore_receipt_exact(const guard_plan *plan,
                                       const guard_journal *journal) {
    int db_dir = open_app_db_dir(plan);
    app_restore_receipt receipt;
    int exact = db_dir_matches_plan(db_dir, plan) &&
        read_app_restore_receipt_at(db_dir, GUARD_APP_RESTORE_RECORD,
            plan, &receipt) == 1 && receipt.phase == APP_RESTORE_RESTORED &&
        app_restore_receipt_matches(&receipt, plan, journal) &&
        known_file_present(db_dir, GUARD_APP_RESTORE_RECORD_TMP) == 0 &&
        known_file_present(db_dir, GUARD_APP_RESTORE_PREPARED) == 0;
    if (db_dir >= 0) close(db_dir);
    return exact ? 0 : -1;
}

static int final_recovery_artifacts_exact(const guard_plan *plan,
                                          const guard_journal *journal) {
    if (live_premigrate_state(plan) != 1 || final_restore_receipt_exact(plan, journal) != 0)
        return -1;
    int db_dir = open_app_db_dir(plan);
    char superseded[128];
    if (!db_dir_matches_plan(db_dir, plan) || recovery_database_name(
            superseded, plan->b.expected_schema, "superseded") != 0 ||
        !named_sidecars_absent(db_dir, superseded)) {
        if (db_dir >= 0) close(db_dir);
        return -1;
    }
    int fd = openat(db_dir, superseded, O_RDONLY | O_NOFOLLOW | O_CLOEXEC);
    struct stat snapshot;
    uint64_t bytes = 0;
    char sha[65];
    int exact = fd >= 0 &&
        source_file_exact_metadata(fd, plan, plan->b.expected_schema,
            &snapshot, &bytes, sha) == 0 &&
        bytes == journal->b_primary_bytes && strcmp(sha, journal->b_primary_sha) == 0;
    if (fd >= 0) close(fd);
    close(db_dir);
    return exact ? 0 : -1;
}

static const char *rollback_outcome_for_error(const char *error) {
    if (strcmp(error, "PM_REJECTED") == 0 || strcmp(error, "PM_NOT_STARTED") == 0)
        return "ROLLED_BACK_PM_REJECTED";
    if (strcmp(error, "HEALTH_TIMEOUT") == 0) return "ROLLED_BACK_HEALTH_TIMEOUT";
    if (strcmp(error, "REFUSAL_TIMEOUT") == 0) return "ROLLED_BACK_REFUSAL_TIMEOUT";
    if (strcmp(error, "FORWARD_TIMEOUT") == 0 || strcmp(error, "OVERALL_TIMEOUT") == 0)
        return "ROLLED_BACK_OVERALL_TIMEOUT";
    if (strcmp(error, "HEALTH_FAILED") == 0 || strcmp(error, "HEALTH_MISMATCH") == 0 ||
        strcmp(error, "HEALTH_UNVERIFIED") == 0)
        return "ROLLED_BACK_HEALTH_FAILED";
    return "ROLLED_BACK_OPERATOR";
}

static int rollback_inventory_exact(const guard_plan *plan) {
    if (live_premigrate_state(plan) != 0) return -1;
    int db_dir = open_app_db_dir(plan);
    char superseded[128];
    int exact = db_dir_matches_plan(db_dir, plan) && recovery_database_name(
        superseded, plan->b.expected_schema, "superseded") == 0 &&
        known_file_present(db_dir, superseded) == 0 &&
        known_file_present(db_dir, GUARD_ROLLBACK_TMP) == 0 &&
        known_file_present(db_dir, GUARD_APP_RESTORE_RECORD) == 0 &&
        known_file_present(db_dir, GUARD_APP_RESTORE_RECORD_TMP) == 0 &&
        known_file_present(db_dir, GUARD_APP_RESTORE_PREPARED) == 0 &&
        recovery_namespace_has_only_expected(db_dir, plan);
    if (db_dir >= 0) close(db_dir);
    return exact ? 0 : -1;
}

static int advance_recovery_withheld_locked(int dir, const guard_plan *plan,
                                            guard_journal *journal) {
    if (journal->phase != GUARD_PHASE_RECOVERY_WITHHELD ||
        installed_apk_stable_exact(plan->package, &plan->b) != 0 ||
        hash_regular_at(dir, GUARD_PREMIGRATE, journal->premigrate_bytes,
                        journal->premigrate_sha) != 0 ||
        hash_regular_at(dir, GUARD_B_PRIMARY, journal->b_primary_bytes,
                        journal->b_primary_sha) != 0 ||
        remove_live_premigrate(plan) != 0)
        return -1;
    uint64_t now = monotonic_ms();
    if (now == 0 || now >= plan->forward_deadline_ms ||
        now > UINT64_MAX - GUARD_HEALTH_TIMEOUT_MS) return -1;
    journal->generation++;
    journal->phase = GUARD_PHASE_WAIT_A_REFUSAL;
    journal_set_role(journal, "B", &plan->b);
    uint64_t phase_deadline = now + GUARD_HEALTH_TIMEOUT_MS;
    journal->deadline_ms = phase_deadline < plan->forward_deadline_ms
        ? phase_deadline : plan->forward_deadline_ms;
    snprintf(journal->error, sizeof journal->error, "NONE");
    return store_journal_at(dir, journal);
}

static int guard_app_autonomous_files_exact(void) {
#ifndef HAPANELD_TEST
    if (geteuid() != 0) return 0;
#endif
    if (guard_owner_fd < 0) return 0;
    uid_t expected_uid = geteuid();
    gid_t expected_gid = getegid();
    int parent = open(GUARD_APP_HELPER_PARENT,
        O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC);
    int guard_dir = open_guard_dir();
    int named_owner = guard_dir >= 0
        ? openat(guard_dir, GUARD_OWNER_LOCK, O_RDONLY | O_NOFOLLOW | O_CLOEXEC) : -1;
    struct stat self, live, owner, named;
    int exact = parent >= 0 && guard_dir >= 0 && named_owner >= 0 &&
        stat("/proc/self/exe", &self) == 0 &&
        fstatat(parent, GUARD_APP_HELPER_LIVE, &live, AT_SYMLINK_NOFOLLOW) == 0 &&
        S_ISREG(live.st_mode) && live.st_uid == expected_uid && live.st_gid == expected_gid &&
        live.st_nlink == 1 && (live.st_mode & 07777) == 0700 &&
        self.st_dev == live.st_dev && self.st_ino == live.st_ino &&
        fstat(guard_owner_fd, &owner) == 0 && fstat(named_owner, &named) == 0 &&
        S_ISREG(owner.st_mode) && owner.st_uid == expected_uid && owner.st_gid == expected_gid &&
        owner.st_nlink == 1 && (owner.st_mode & 07777) == 0600 &&
        owner.st_dev == named.st_dev && owner.st_ino == named.st_ino;
    if (named_owner >= 0) close(named_owner);
    if (guard_dir >= 0) close(guard_dir);
    if (parent >= 0) close(parent);
    return exact;
}

static int guard_app_autonomous_profile(void) {
    if (!guard_maintenance_supervised()) return 0;
#ifdef HAPANELD_TEST
    if (test_app_autonomous_profile == 1) return 1;
#endif
    return guard_app_autonomous_files_exact();
}

void cmd_guardcaps(conn_ctx *ctx, const char *args) {
    if (*args) { error_reply(ctx, "ARGS", "caps"); return; }
    reply(ctx->fd, guard_app_autonomous_profile()
        ? "OK GUARDCAPS 1 PREPARE DEFINE STREAM ACTION HEALTH REFUSAL STATUS EVIDENCE CANCEL RETIRE JOURNAL AUTONOMOUS SUPERVISED TERMINAL_RETIRE\n"
        : (guard_maintenance_supervised()
            ? "OK GUARDCAPS 1 PREPARE DEFINE STREAM ACTION HEALTH REFUSAL STATUS EVIDENCE CANCEL RETIRE JOURNAL SUPERVISED TERMINAL_RETIRE\n"
            : "OK GUARDCAPS 1 PREPARE DEFINE STREAM ACTION HEALTH REFUSAL STATUS EVIDENCE CANCEL RETIRE JOURNAL TERMINAL_RETIRE\n"));
}

void cmd_guardprepare(conn_ctx *ctx, const char *args) {
    if (!guard_admission_ready(ctx)) return;
    if (!guard_app_autonomous_profile()) {
        error_reply(ctx, "STATE", "autonomous");
        return;
    }
    char storage[513], *tokens[14];
    int count = split_tokens(args, storage, tokens, 14);
    guard_plan plan;
    uint64_t baseline_bytes, baseline_app_state, overall_budget, settings_bytes;
    uint32_t baseline_schema, settings_version;
    if (count != 13 || !lower_hex_64(tokens[0]) || !lower_hex_64(tokens[1]) ||
        !lower_hex_64(tokens[2]) || !lower_hex_64(tokens[4]) ||
        parse_u64(tokens[3], 1, GUARD_MAX_DB_BYTES, &baseline_bytes) != 0 ||
        parse_u32(tokens[5], 1, UINT32_MAX, &baseline_schema) != 0 ||
        parse_u64(tokens[6], 1, UINT64_MAX, &baseline_app_state) != 0 ||
        !lower_hex_64(tokens[7]) || !lower_hex_64(tokens[8]) ||
        parse_u64(tokens[9], GUARD_RECOVERY_RESERVE_MS + GUARD_FORWARD_MIN_MS,
                  GUARD_OVERALL_MAX_MS, &overall_budget) != 0 ||
        parse_u32(tokens[10], 2, 2, &settings_version) != 0 ||
        parse_u64(tokens[11], 1, GUARD_MAX_SETTINGS_BYTES, &settings_bytes) != 0 ||
        !lower_hex_64(tokens[12])) {
        error_reply(ctx, "ARGS", "prepare"); return;
    }
    /* Bind the session to the caller's package here, once.  Everything downstream -- including the
     * paths with no caller at all -- reads it back out of the durable record. */
    const char *caller_package = guard_caller_package(ctx);
    if (!caller_package) { error_reply(ctx, "STATE", "package"); return; }
    pthread_mutex_lock(&package_gate);
    pthread_mutex_lock(&guard_lock);
    int dir = open_guard_dir();
    if (dir < 0) {
        pthread_mutex_unlock(&guard_lock); pthread_mutex_unlock(&package_gate);
        error_reply(ctx, "IO", "custody"); return;
    }
    guard_plan existing;
    int draft = load_plan_at(dir, GUARD_DRAFT, &existing);
    int manifest = load_plan_at(dir, GUARD_MANIFEST, &existing);
    if (draft != 0 || manifest != 0) {
        close(dir); pthread_mutex_unlock(&guard_lock); pthread_mutex_unlock(&package_gate);
        error_reply(ctx, draft < 0 || manifest < 0 ? "HOLD" : "BUSY", "session"); return;
    }
    char boot[65];
    if (current_boot_nonce(boot) != 0 || strcmp(boot, tokens[1]) != 0) {
        close(dir); pthread_mutex_unlock(&guard_lock); pthread_mutex_unlock(&package_gate);
        error_reply(ctx, "BOOT", "mismatch"); return;
    }
    uint64_t now = monotonic_ms();
    if (now == 0 || now > UINT64_MAX - overall_budget) {
        close(dir); pthread_mutex_unlock(&guard_lock); pthread_mutex_unlock(&package_gate);
        error_reply(ctx, "DEADLINE", "prepare"); return;
    }
    memset(&plan, 0, sizeof plan);
    snprintf(plan.package, sizeof plan.package, "%s", caller_package);
    snprintf(plan.session, sizeof plan.session, "%s", tokens[0]);
    snprintf(plan.boot, sizeof plan.boot, "%s", tokens[1]);
    snprintf(plan.signer, sizeof plan.signer, "%s", tokens[2]);
    plan.generation = 1;
    plan.overall_deadline_ms = now + overall_budget;
    plan.forward_deadline_ms = plan.overall_deadline_ms - GUARD_RECOVERY_RESERVE_MS;
    plan.settings_authority_version = settings_version;
    plan.settings_authority_bytes = settings_bytes;
    snprintf(plan.settings_authority_sha, sizeof plan.settings_authority_sha, "%s", tokens[12]);
    plan.baseline_bytes = baseline_bytes;
    snprintf(plan.baseline_sha, sizeof plan.baseline_sha, "%s", tokens[4]);
    plan.baseline_schema = baseline_schema;
    plan.baseline_app_state = baseline_app_state;
    snprintf(plan.baseline_app_state_sha, sizeof plan.baseline_app_state_sha, "%s", tokens[7]);
    snprintf(plan.baseline_settings_sha, sizeof plan.baseline_settings_sha, "%s", tokens[8]);
    int stored = store_plan_at(dir, GUARD_DRAFT, GUARD_DRAFT_TMP, &plan);
    close(dir);
    pthread_mutex_unlock(&guard_lock);
    pthread_mutex_unlock(&package_gate);
    if (stored == GUARD_PUBLISH_INDETERMINATE) error_reply(ctx, "INDETERMINATE", "draft");
    else if (stored != 0) error_reply(ctx, "IO", "draft");
    else ok_reply(ctx, "GUARDPREPARE", plan.generation, GUARD_PHASE_STAGING);
}

void cmd_guarddefine(conn_ctx *ctx, const char *args) {
    if (!guard_admission_ready(ctx)) return;
    char storage[513], *tokens[10];
    int count = split_tokens(args, storage, tokens, 10);
    uint64_t generation, bytes, version_code;
    uint32_t minimum, maximum, expected;
    if (count != 9 || !lower_hex_64(tokens[0]) ||
        (strcmp(tokens[2], "A") != 0 && strcmp(tokens[2], "B") != 0) ||
        parse_u64(tokens[1], 1, UINT64_MAX, &generation) != 0 ||
        parse_u64(tokens[3], 1, GUARD_MAX_APK_BYTES, &bytes) != 0 ||
        !lower_hex_64(tokens[4]) || parse_u64(tokens[5], 1, UINT64_MAX, &version_code) != 0 ||
        parse_u32(tokens[6], 1, UINT32_MAX, &minimum) != 0 ||
        parse_u32(tokens[7], 1, UINT32_MAX, &maximum) != 0 ||
        parse_u32(tokens[8], 1, UINT32_MAX, &expected) != 0 ||
        minimum > maximum || expected < minimum || expected > maximum) {
        error_reply(ctx, "ARGS", "define"); return;
    }
    pthread_mutex_lock(&guard_lock);
    int dir = open_guard_dir();
    guard_plan plan;
    int loaded = dir >= 0 ? load_draft_reconciled(dir, &plan) : -1;
    if (loaded != 1) {
        if (dir >= 0) close(dir);
        pthread_mutex_unlock(&guard_lock);
        error_reply(ctx, loaded == 0 ? "STATE" : "HOLD", "draft"); return;
    }
    if (strcmp(plan.session, tokens[0]) != 0 || plan.generation != generation) {
        close(dir); pthread_mutex_unlock(&guard_lock); error_reply(ctx, "STALE", "generation"); return;
    }
    if (!guard_caller_owns_plan(ctx, &plan)) {
        close(dir); pthread_mutex_unlock(&guard_lock); error_reply(ctx, "STATE", "package"); return;
    }
    guard_artifact *artifact;
    const char *name, *upload;
    (void)name; (void)upload;
    if (plan_role(&plan, tokens[2], &artifact, &name, &upload) != 0) {
        close(dir); pthread_mutex_unlock(&guard_lock); error_reply(ctx, "ARGS", "role"); return;
    }
    if (artifact->defined) {
        int same = artifact->bytes == bytes && strcmp(artifact->sha, tokens[4]) == 0 &&
            artifact->version_code == version_code && artifact->contract_min == minimum &&
            artifact->contract_max == maximum && artifact->expected_schema == expected;
        close(dir); pthread_mutex_unlock(&guard_lock);
        if (same) ok_reply(ctx, "GUARDDEFINE", plan.generation, GUARD_PHASE_STAGING);
        else error_reply(ctx, "IMMUTABLE", "role");
        return;
    }
    artifact->defined = 1;
    artifact->bytes = bytes;
    snprintf(artifact->sha, sizeof artifact->sha, "%s", tokens[4]);
    artifact->version_code = version_code;
    artifact->contract_min = minimum;
    artifact->contract_max = maximum;
    artifact->expected_schema = expected;
    if (!plans_compatible(&plan)) {
        close(dir); pthread_mutex_unlock(&guard_lock); error_reply(ctx, "CONTRACT", "plan"); return;
    }
    plan.generation++;
    int stored = store_plan_at(dir, GUARD_DRAFT, GUARD_DRAFT_TMP, &plan);
    close(dir); pthread_mutex_unlock(&guard_lock);
    if (stored == GUARD_PUBLISH_INDETERMINATE) error_reply(ctx, "INDETERMINATE", "draft");
    else if (stored != 0) error_reply(ctx, "IO", "draft");
    else ok_reply(ctx, "GUARDDEFINE", plan.generation, GUARD_PHASE_STAGING);
}

void cmd_guardstream(conn_ctx *ctx, const char *args) {
    if (!guard_admission_ready(ctx)) return;
    char storage[513], *tokens[6];
    int count = split_tokens(args, storage, tokens, 6);
    uint64_t generation, bytes;
    if (count != 5 || !lower_hex_64(tokens[0]) ||
        parse_u64(tokens[1], 1, UINT64_MAX, &generation) != 0 ||
        (strcmp(tokens[2], "A") != 0 && strcmp(tokens[2], "B") != 0 &&
         strcmp(tokens[2], "SETTINGS") != 0) ||
        parse_u64(tokens[3], 1, strcmp(tokens[2], "SETTINGS") == 0
            ? GUARD_MAX_SETTINGS_BYTES : GUARD_MAX_APK_BYTES, &bytes) != 0 ||
        !lower_hex_64(tokens[4])) {
        error_reply(ctx, "ARGS", "stream"); return;
    }
    if (pthread_mutex_trylock(&guard_lock) != 0) { error_reply(ctx, "BUSY", "owner"); return; }
    int dir = open_guard_dir();
    guard_plan plan;
    int loaded = dir >= 0 ? load_draft_reconciled(dir, &plan) : -1;
    if (loaded != 1) {
        if (dir >= 0) close(dir);
        pthread_mutex_unlock(&guard_lock);
        error_reply(ctx, loaded == 0 ? "STATE" : "HOLD", "draft"); return;
    }
    if (strcmp(plan.session, tokens[0]) != 0 || plan.generation != generation) {
        close(dir); pthread_mutex_unlock(&guard_lock); error_reply(ctx, "STALE", "generation"); return;
    }
    if (!guard_caller_owns_plan(ctx, &plan)) {
        close(dir); pthread_mutex_unlock(&guard_lock); error_reply(ctx, "STATE", "package"); return;
    }
    guard_artifact *artifact = NULL;
    const char *name = NULL, *upload = NULL;
    int settings = strcmp(tokens[2], "SETTINGS") == 0;
    int identity = settings
        ? plan.settings_authority_bytes == bytes &&
          strcmp(plan.settings_authority_sha, tokens[4]) == 0
        : plan_role(&plan, tokens[2], &artifact, &name, &upload) == 0 &&
          artifact->defined && artifact->bytes == bytes &&
          strcmp(artifact->sha, tokens[4]) == 0;
    if (!identity) {
        close(dir); pthread_mutex_unlock(&guard_lock); error_reply(ctx, "MISMATCH", "artifact"); return;
    }
    if ((settings && plan.settings_authority_staged) || (!settings && artifact->staged)) {
        close(dir); pthread_mutex_unlock(&guard_lock);
        ok_reply(ctx, "GUARDSTREAM", plan.generation, GUARD_PHASE_STAGING); return;
    }
    if (settings) { name = GUARD_SETTINGS; upload = GUARD_SETTINGS_UPLOAD; }
    int streamed = stream_into_artifact(ctx, dir, upload, name, bytes, tokens[4]);
    const char *publish_detail = "artifact";
    if (streamed == 0 && settings && validate_settings_authority_at(dir, &plan) != 0) {
        streamed = -1;
        (void)remove_fixed_nondir(dir, GUARD_SETTINGS);
        (void)fsync(dir);
    }
    if (streamed == 0) {
        if (settings) plan.settings_authority_staged = 1;
        else artifact->staged = 1;
        plan.generation++;
        streamed = store_plan_at(dir, GUARD_DRAFT, GUARD_DRAFT_TMP, &plan);
        publish_detail = "draft";
    }
    close(dir); pthread_mutex_unlock(&guard_lock);
    if (streamed == GUARD_PUBLISH_INDETERMINATE)
        error_reply(ctx, "INDETERMINATE", publish_detail);
    else if (streamed != 0) error_reply(ctx, "STREAM", "custody");
    else ok_reply(ctx, "GUARDSTREAM", plan.generation, GUARD_PHASE_STAGING);
}

static void status_for_plan(conn_ctx *ctx, const guard_plan *plan, enum guard_phase phase,
                            const char *role, const char *sha, uint64_t vc, uint32_t schema,
                            const char *error, const char *outcome) {
    char line[512];
    snprintf(line, sizeof line,
        "OK GUARDSTATUS %" PRIu64 " %s %s %s %s %s %" PRIu64 " %u %" PRIu64
        " %s %s %" PRIu64 " %" PRIu64 "\n",
        plan ? plan->generation : 0, guard_phase_name(phase), plan ? plan->session : "NONE",
        plan ? plan->boot : "NONE", role, sha, vc, schema,
        plan ? plan->baseline_app_state : 0, error, outcome,
        plan ? plan->overall_deadline_ms : 0, plan ? plan->forward_deadline_ms : 0);
    reply(ctx->fd, line);
}

void cmd_guardstatus(conn_ctx *ctx, const char *args) {
    if (*args) { error_reply(ctx, "ARGS", "status"); return; }
    pthread_mutex_lock(&guard_lock);
    int dir = open_guard_dir();
    guard_plan plan;
    guard_journal journal;
    int retirement = dir >= 0 ? reconcile_terminal_retirement_at(dir) : -1;
    int manifest = retirement == 0
        ? load_manifest_reconciled(dir, &plan, &journal, 0)
        : (retirement == 1 ? 0 : -1);
    int journal_state = manifest == 1 ? 1 : 0;
    int draft = manifest == 0 ? load_draft_reconciled(dir, &plan) : 0;
    guard_capture capture;
    int capture_state = manifest == 0 && draft == 1 ? load_capture_at(dir, &capture) : 0;
    int capture_valid = capture_state != 1 || capture_matches_draft(&capture, &plan);
    int empty_exact = retirement >= 0 && retirement != 2 && manifest == 0 && draft == 0 &&
        capture_state == 0 && dir >= 0 && retirement_owner_lock_exact(dir) &&
        empty_inventory_safe(dir);
    if (dir >= 0) close(dir);
    pthread_mutex_unlock(&guard_lock);
    if (retirement < 0 || retirement == 2) {
        error_reply(ctx, "HOLD", "retirement");
    } else if (manifest < 0 || draft < 0 || journal_state < 0 || capture_state < 0 ||
        !capture_valid || (manifest == 1 && journal_state != 1))
        error_reply(ctx, "HOLD", "record");
    else if (manifest == 1) {
        plan.generation = journal.generation;
        status_for_plan(ctx, &plan, journal.phase, journal.role, journal.installed_sha,
            journal.version_code, journal.schema, journal.error, journal.outcome);
    }
    else if (draft == 1) status_for_plan(ctx, &plan, GUARD_PHASE_STAGING,
        "NONE", "NONE", 0, 0,
        capture_state == 1
            ? (capture.state == GUARD_CAPTURE_FAILED_NO_MUTATION
                ? "FAILED_NO_MUTATION" : "CAPTURE_INTENT")
            : "NONE",
        "NONE");
    else if (!empty_exact) error_reply(ctx, "HOLD", "retirement");
    else status_for_plan(ctx, NULL, GUARD_PHASE_EMPTY,
        "NONE", "NONE", 0, 0, "NONE", "NONE");
}

static int format_guard_evidence(const guard_plan *plan, const guard_journal *journal,
                                 int manifested,
                                 char evidence[GUARD_EVIDENCE_MAX_BYTES]) {
    int length = snprintf(evidence, GUARD_EVIDENCE_MAX_BYTES,
        "OK GUARDEVIDENCE 1\n"
        "SESSION %s\nBOOT %s\nPACKAGE %s\nSIGNER %s\n"
        "STATE %" PRIu64 " %s %s %s %" PRIu64 " %u %s %s %" PRIu64 " %" PRIu64 "\n"
        "BASELINE %" PRIu64 " %s %u %" PRIu64 " %s %s\n"
        "SETTINGS %u %" PRIu64 " %s\n"
        "A %d %d %" PRIu64 " %s %" PRIu64 " %u %u %u\n"
        "B %d %d %" PRIu64 " %s %" PRIu64 " %u %u %u\n"
        "PREMIGRATE %" PRIu64 " %s\n"
        "B_PRIMARY %" PRIu64 " %s\nEND\n",
        plan->session, plan->boot, plan->package, plan->signer,
        manifested ? journal->generation : plan->generation,
        guard_phase_name(manifested ? journal->phase : GUARD_PHASE_STAGING),
        manifested ? journal->role : "NONE", manifested ? journal->installed_sha : "NONE",
        manifested ? journal->version_code : 0, manifested ? journal->schema : 0,
        manifested ? journal->error : "NONE", manifested ? journal->outcome : "NONE",
        plan->overall_deadline_ms, plan->forward_deadline_ms,
        plan->baseline_bytes, plan->baseline_sha, plan->baseline_schema, plan->baseline_app_state,
        plan->baseline_app_state_sha, plan->baseline_settings_sha,
        plan->settings_authority_version, plan->settings_authority_bytes,
        plan->settings_authority_sha,
        plan->a.defined, plan->a.staged, plan->a.bytes,
        plan->a.defined ? plan->a.sha : "NONE",
        plan->a.version_code, plan->a.contract_min, plan->a.contract_max,
        plan->a.expected_schema,
        plan->b.defined, plan->b.staged, plan->b.bytes,
        plan->b.defined ? plan->b.sha : "NONE",
        plan->b.version_code, plan->b.contract_min, plan->b.contract_max,
        plan->b.expected_schema,
        manifested ? journal->premigrate_bytes : 0,
        manifested ? journal->premigrate_sha : "NONE",
        manifested ? journal->b_primary_bytes : 0,
        manifested ? journal->b_primary_sha : "NONE");
    return length > 0 && length < GUARD_EVIDENCE_MAX_BYTES ? length : -1;
}

static int guard_evidence_sha(const guard_plan *plan, const guard_journal *journal,
                              char output[65]) {
    char evidence[GUARD_EVIDENCE_MAX_BYTES];
    int length = format_guard_evidence(plan, journal, 1, evidence);
    if (length < 0) return -1;
    hapaneld_sha256 hash;
    unsigned char digest[32];
    hapaneld_sha256_init(&hash);
    hapaneld_sha256_update(&hash, evidence, (size_t)length);
    hapaneld_sha256_final(&hash, digest);
    hapaneld_sha256_hex(digest, output);
    return 0;
}

void cmd_guardevidence(conn_ctx *ctx, const char *args) {
    char storage[513], *tokens[2];
    int count = split_tokens(args, storage, tokens, 2);
    if (count != 1 || !lower_hex_64(tokens[0])) { error_reply(ctx, "ARGS", "evidence"); return; }
    pthread_mutex_lock(&guard_lock);
    int dir = open_guard_dir();
    guard_plan plan;
    guard_journal journal;
    int loaded = dir >= 0 ? load_manifest_reconciled(dir, &plan, &journal, 0) : -1;
    int manifested = loaded == 1;
    int journal_state = manifested ? 1 : 0;
    if (loaded == 0) loaded = load_draft_reconciled(dir, &plan);
    if (dir >= 0) close(dir);
    pthread_mutex_unlock(&guard_lock);
    if (loaded != 1 || (manifested && journal_state != 1)) {
        error_reply(ctx, loaded == 0 ? "STATE" : "HOLD", "record"); return;
    }
    if (strcmp(plan.session, tokens[0]) != 0) { error_reply(ctx, "SESSION", "mismatch"); return; }
    char evidence[GUARD_EVIDENCE_MAX_BYTES];
    int length = format_guard_evidence(&plan, &journal, manifested, evidence);
    if (length <= 0 || (size_t)length >= sizeof evidence) error_reply(ctx, "IO", "evidence");
    else reply(ctx->fd, evidence);
}

static int unlink_owned_artifact(int dir, const char *name, const guard_artifact *artifact) {
    if (!artifact->defined) return 0;
    struct stat st;
    if (fstatat(dir, name, &st, AT_SYMLINK_NOFOLLOW) != 0) return errno == ENOENT ? 0 : -1;
    if (hash_regular_at(dir, name, artifact->bytes, artifact->sha) != 0) return -1;
    return unlinkat(dir, name, 0);
}

static int cancel_marker_only(int dir) {
    int duplicate = openat(dir, ".", O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC);
    if (duplicate < 0) return 0;
    DIR *stream = fdopendir(duplicate);
    if (!stream) { close(duplicate); return 0; }
    int safe = 1;
    errno = 0;
    for (struct dirent *entry = readdir(stream); entry; entry = readdir(stream)) {
        if (strcmp(entry->d_name, ".") == 0 || strcmp(entry->d_name, "..") == 0 ||
            strcmp(entry->d_name, GUARD_OWNER_LOCK) == 0 ||
            strcmp(entry->d_name, GUARD_CAPTURE) == 0 ||
            strcmp(entry->d_name, GUARD_CAPTURE_TMP) == 0) {
            errno = 0;
            continue;
        }
        safe = 0;
        break;
    }
    if (errno != 0) safe = 0;
    closedir(stream);
    return safe;
}

static int reconcile_orphaned_cancel_at(int dir) {
    guard_capture capture;
    int state = load_capture_at(dir, &capture);
    if (state == 0) return 0;
    if (state != 1 || capture.state != GUARD_CAPTURE_CANCEL_INTENT ||
        !cancel_marker_only(dir)) return -1;
    if (remove_fixed_nondir(dir, GUARD_CAPTURE_TMP) != 0 ||
        unlinkat(dir, GUARD_CAPTURE, 0) != 0 || fsync(dir) != 0) return -1;
    return 1;
}

static int finish_cancel_locked(int dir, const guard_plan *plan,
                                const guard_capture *capture) {
    if (!capture_matches_draft(capture, plan) ||
        capture->state != GUARD_CAPTURE_CANCEL_INTENT) return GUARD_PUBLISH_FAILED;
    if (unlink_owned_artifact(dir, GUARD_A_APK, &plan->a) != 0 || fsync(dir) != 0)
        return GUARD_PUBLISH_INDETERMINATE;
    if (TEST_FAULT(GUARD_TEST_FAULT_CANCEL_AFTER_ARTIFACT))
        return GUARD_PUBLISH_INDETERMINATE;
    if (unlink_owned_artifact(dir, GUARD_B_APK, &plan->b) != 0)
        return GUARD_PUBLISH_INDETERMINATE;
    int settings = known_file_present(dir, GUARD_SETTINGS);
    if (settings < 0 || (settings == 1 &&
        (!plan->settings_authority_staged || validate_settings_authority_at(dir, plan) != 0 ||
         unlinkat(dir, GUARD_SETTINGS, 0) != 0))) return GUARD_PUBLISH_INDETERMINATE;
    int baseline = known_file_present(dir, GUARD_BASELINE);
    if (baseline < 0 || (baseline == 1 &&
        (hash_regular_at(dir, GUARD_BASELINE, plan->baseline_bytes, plan->baseline_sha) != 0 ||
         unlinkat(dir, GUARD_BASELINE, 0) != 0))) return GUARD_PUBLISH_INDETERMINATE;
    static const char *const transient[] = {
        GUARD_DRAFT_TMP, GUARD_MANIFEST_TMP, GUARD_JOURNAL_TMP, GUARD_CAPTURE_TMP,
        GUARD_A_UPLOAD, GUARD_B_UPLOAD, GUARD_SETTINGS_UPLOAD, GUARD_BASELINE_TMP,
        GUARD_PREMIGRATE_TMP, GUARD_B_PRIMARY_TMP, GUARD_HEALTH_COPY,
        GUARD_HEALTH_COPY_TMP,
    };
    for (size_t i = 0; i < sizeof transient / sizeof transient[0]; i++)
        if (remove_fixed_nondir(dir, transient[i]) != 0) return GUARD_PUBLISH_INDETERMINATE;
    if (known_file_present(dir, GUARD_MANIFEST) != 0 ||
        known_file_present(dir, GUARD_JOURNAL) != 0 ||
        known_file_present(dir, GUARD_PREMIGRATE) != 0 ||
        known_file_present(dir, GUARD_B_PRIMARY) != 0 ||
        known_file_present(dir, GUARD_REPLACEMENT) != 0 ||
        known_file_present(dir, GUARD_REPLACEMENT_TMP) != 0 || fsync(dir) != 0)
        return GUARD_PUBLISH_INDETERMINATE;
    if (unlinkat(dir, GUARD_DRAFT, 0) != 0 || fsync(dir) != 0)
        return GUARD_PUBLISH_INDETERMINATE;
    if (TEST_FAULT(GUARD_TEST_FAULT_CANCEL_AFTER_DRAFT))
        return GUARD_PUBLISH_INDETERMINATE;
    if (unlinkat(dir, GUARD_CAPTURE, 0) != 0 || fsync(dir) != 0)
        return GUARD_PUBLISH_INDETERMINATE;
    return GUARD_PUBLISH_COMMITTED;
}

void cmd_guardcancel(conn_ctx *ctx, const char *args) {
    if (!guard_admission_ready(ctx)) return;
    char storage[513], *tokens[3];
    int count = split_tokens(args, storage, tokens, 3);
    uint64_t generation;
    if (count != 2 || !lower_hex_64(tokens[0]) ||
        parse_u64(tokens[1], 1, UINT64_MAX, &generation) != 0) {
        error_reply(ctx, "ARGS", "cancel"); return;
    }
    pthread_mutex_lock(&guard_lock);
    int dir = open_guard_dir();
    guard_plan plan;
    int manifest = dir >= 0 ? load_plan_at(dir, GUARD_MANIFEST, &plan) : -1;
    int loaded = manifest == 0 ? load_draft_reconciled(dir, &plan) : manifest;
    if (loaded != 1 || strcmp(plan.session, tokens[0]) != 0 || plan.generation != generation) {
        if (dir >= 0) close(dir);
        pthread_mutex_unlock(&guard_lock);
        error_reply(ctx, loaded == 1 ? "STALE" : (loaded == 0 ? "STATE" : "HOLD"), "cancel"); return;
    }
    if (!guard_caller_owns_plan(ctx, &plan)) {
        close(dir); pthread_mutex_unlock(&guard_lock);
        error_reply(ctx, "STATE", "package"); return;
    }
    if (manifest == 1) {
        close(dir); pthread_mutex_unlock(&guard_lock); error_reply(ctx, "ARMED", "cancel"); return;
    }
    guard_capture capture;
    int capture_state = load_capture_at(dir, &capture);
    if (capture_state < 0 || (capture_state == 1 &&
        (!capture_matches_draft(&capture, &plan) ||
         capture.state != GUARD_CAPTURE_FAILED_NO_MUTATION))) {
        close(dir);
        pthread_mutex_unlock(&guard_lock);
        error_reply(ctx, capture_state < 0 ? "HOLD" : "ARMED", "cancel");
        return;
    }
    int intent = GUARD_PUBLISH_FAILED;
    if (capture_state == 0) {
        if (initialize_capture(&plan, &capture) == 0) {
            capture.state = GUARD_CAPTURE_CANCEL_INTENT;
            intent = store_capture_at(dir, &capture);
        }
    } else {
        capture.state = GUARD_CAPTURE_CANCEL_INTENT;
        intent = store_capture_at(dir, &capture);
    }
    int removed = intent == GUARD_PUBLISH_COMMITTED
        ? finish_cancel_locked(dir, &plan, &capture) : intent;
    close(dir); pthread_mutex_unlock(&guard_lock);
    if (removed == GUARD_PUBLISH_INDETERMINATE)
        error_reply(ctx, "INDETERMINATE", "cancel");
    else if (removed != GUARD_PUBLISH_COMMITTED) error_reply(ctx, "HOLD", "cancel");
    else ok_reply(ctx, "GUARDCANCEL", generation + 1, GUARD_PHASE_EMPTY);
}

void cmd_guardaction(conn_ctx *ctx, const char *args) {
    if (!guard_admission_ready(ctx)) return;
    char storage[513], *tokens[4];
    int count = split_tokens(args, storage, tokens, 4);
    uint64_t generation;
    if (count != 3 || !lower_hex_64(tokens[0]) ||
        parse_u64(tokens[1], 1, UINT64_MAX, &generation) != 0) {
        error_reply(ctx, "ARGS", "action"); return;
    }
    int capture_action = strcmp(tokens[2], "CAPTURE_BASELINE") == 0;
    int withhold_action = strcmp(tokens[2], "WITHHOLD_PREMIGRATE") == 0;
    int restore_action = strcmp(tokens[2], "RESTORE_PREMIGRATE") == 0;
    int finalize_action = strcmp(tokens[2], "FINALIZE") == 0;
    if (!capture_action && !withhold_action && !restore_action && !finalize_action) {
        error_reply(ctx, "STATE", "action"); return;
    }
    pthread_mutex_lock(&guard_lock);
    int dir = open_guard_dir();
    guard_plan plan;
    guard_journal existing;
    int manifest = dir >= 0 ? load_manifest_reconciled(dir, &plan, &existing, 1) : -1;
    if (manifest == 1 && !guard_caller_owns_plan(ctx, &plan)) {
        close(dir); pthread_mutex_unlock(&guard_lock);
        error_reply(ctx, "STATE", "package"); return;
    }
    if (withhold_action) {
        if (manifest != 1) {
            if (dir >= 0) close(dir);
            pthread_mutex_unlock(&guard_lock);
            error_reply(ctx, manifest == 0 ? "STATE" : "HOLD", "withhold");
            return;
        }
        int same_session = strcmp(plan.session, tokens[0]) == 0;
        if (same_session &&
            ((existing.phase == GUARD_PHASE_RECOVERY_WITHHELD &&
              existing.generation == generation + 1) ||
             (existing.phase == GUARD_PHASE_WAIT_A_REFUSAL &&
              existing.generation == generation + 2))) {
            uint64_t current = existing.generation;
            enum guard_phase phase = existing.phase;
            close(dir);
            pthread_mutex_unlock(&guard_lock);
            ok_reply(ctx, "GUARDACTION", current, phase);
            return;
        }
        if (!same_session || existing.generation != generation ||
            existing.phase != GUARD_PHASE_B_HEALTHY) {
            close(dir);
            pthread_mutex_unlock(&guard_lock);
            error_reply(ctx, same_session ? "STALE" : "MISMATCH", "withhold");
            return;
        }
        uint64_t now = monotonic_ms();
        int intent_publish = GUARD_PUBLISH_FAILED;
        int custody_publish = GUARD_PUBLISH_FAILED;
        int exact = guard_maintenance_supervised() && now != 0 &&
            now < plan.forward_deadline_ms &&
            installed_apk_stable_exact(plan.package, &plan.b) == 0;
        if (exact) intent_publish = prepare_recovery_custody_intent(dir, &plan, &existing);
        if (intent_publish != GUARD_PUBLISH_COMMITTED ||
            TEST_FAULT(GUARD_TEST_FAULT_WITHHOLD_AFTER_INTENT)) {
            close(dir);
            pthread_mutex_unlock(&guard_lock);
            error_reply(ctx, intent_publish == GUARD_PUBLISH_FAILED ? "HOLD" : "INDETERMINATE",
                        "premigrate");
            return;
        }
        exact = force_stop_guard_app(plan.package) == 0;
        if (exact && TEST_FAULT(GUARD_TEST_FAULT_WITHHOLD_AFTER_FORCE_STOP))
            custody_publish = GUARD_PUBLISH_INDETERMINATE;
        else if (exact) custody_publish = seal_recovery_custody(dir, &plan, &existing);
        if (custody_publish == GUARD_PUBLISH_INDETERMINATE) {
            close(dir);
            pthread_mutex_unlock(&guard_lock);
            error_reply(ctx, "INDETERMINATE", "premigrate");
            return;
        }
        exact = exact && custody_publish == GUARD_PUBLISH_COMMITTED;
        if (!exact) {
            close(dir);
            pthread_mutex_unlock(&guard_lock);
            error_reply(ctx, "HOLD", "withhold");
            return;
        }
        existing.generation++;
        existing.phase = GUARD_PHASE_RECOVERY_WITHHELD;
        existing.deadline_ms = 0;
        journal_set_role(&existing, "B", &plan.b);
        snprintf(existing.error, sizeof existing.error, "NONE");
        int stored = store_journal_at(dir, &existing);
        uint64_t current = existing.generation;
        close(dir);
        pthread_mutex_unlock(&guard_lock);
        publish_reply(ctx, stored, "premigrate", "GUARDACTION", current,
            GUARD_PHASE_RECOVERY_WITHHELD);
        return;
    }
    if (restore_action) {
        if (manifest != 1) {
            if (dir >= 0) close(dir);
            pthread_mutex_unlock(&guard_lock);
            error_reply(ctx, manifest == 0 ? "STATE" : "HOLD", "restore");
            return;
        }
        int same_session = strcmp(plan.session, tokens[0]) == 0;
        if (same_session &&
            ((existing.phase == GUARD_PHASE_RECOVERY_RESTORED &&
              existing.generation == generation + 1) ||
             (existing.phase == GUARD_PHASE_SUBMITTED_A &&
              existing.generation == generation + 2) ||
             (existing.phase == GUARD_PHASE_WAIT_A_HEALTH &&
              existing.generation == generation + 3))) {
            uint64_t current = existing.generation;
            enum guard_phase phase = existing.phase;
            close(dir);
            pthread_mutex_unlock(&guard_lock);
            ok_reply(ctx, "GUARDACTION", current, phase);
            return;
        }
        if (!same_session || existing.generation != generation ||
            existing.phase != GUARD_PHASE_A_REFUSED) {
            close(dir);
            pthread_mutex_unlock(&guard_lock);
            error_reply(ctx, same_session ? "STALE" : "MISMATCH", "restore");
            return;
        }
        uint64_t now = monotonic_ms();
        int intent_publish = GUARD_PUBLISH_FAILED;
        int restore_publish = GUARD_PUBLISH_FAILED;
        int exact = guard_maintenance_supervised() && now != 0 &&
            now < plan.overall_deadline_ms &&
            installed_apk_stable_exact(plan.package, &plan.b) == 0;
        if (exact && strcmp(existing.error, "NONE") == 0) {
            snprintf(existing.error, sizeof existing.error, "RESTORE_INTENT");
            intent_publish = store_journal_at(dir, &existing);
        } else if (exact && strcmp(existing.error, "RESTORE_INTENT") == 0) {
            intent_publish = GUARD_PUBLISH_COMMITTED;
        }
        if (intent_publish != GUARD_PUBLISH_COMMITTED ||
            TEST_FAULT(GUARD_TEST_FAULT_RESTORE_AFTER_INTENT)) {
            close(dir);
            pthread_mutex_unlock(&guard_lock);
            error_reply(ctx, intent_publish == GUARD_PUBLISH_FAILED ? "HOLD" : "INDETERMINATE",
                        "restore");
            return;
        }
        restore_publish = resume_restore_intent_locked(dir, &plan, &existing);
        if (restore_publish == GUARD_PUBLISH_INDETERMINATE) {
            close(dir);
            pthread_mutex_unlock(&guard_lock);
            error_reply(ctx, "INDETERMINATE", "restore");
            return;
        }
        if (restore_publish != GUARD_PUBLISH_COMMITTED) {
            close(dir);
            pthread_mutex_unlock(&guard_lock);
            error_reply(ctx, "HOLD", "restore");
            return;
        }
        uint64_t current = existing.generation;
        close(dir);
        pthread_mutex_unlock(&guard_lock);
        publish_reply(ctx, restore_publish, "restore", "GUARDACTION", current,
            GUARD_PHASE_RECOVERY_RESTORED);
        return;
    }
    if (finalize_action) {
        if (manifest != 1) {
            if (dir >= 0) close(dir);
            pthread_mutex_unlock(&guard_lock);
            error_reply(ctx, manifest == 0 ? "STATE" : "HOLD", "finalize");
            return;
        }
        int same_session = strcmp(plan.session, tokens[0]) == 0;
        if (same_session && existing.phase == GUARD_PHASE_FINALIZED &&
            existing.generation == generation + 1 &&
            strcmp(existing.outcome, "NONE") != 0) {
            uint64_t current = existing.generation;
            close(dir);
            pthread_mutex_unlock(&guard_lock);
            ok_reply(ctx, "GUARDACTION", current, GUARD_PHASE_FINALIZED);
            return;
        }
        if (!same_session || existing.generation != generation ||
            existing.phase != GUARD_PHASE_A_HEALTHY) {
            close(dir);
            pthread_mutex_unlock(&guard_lock);
            error_reply(ctx, same_session ? "STALE" : "MISMATCH", "finalize");
            return;
        }
        uint64_t now = monotonic_ms();
        int rolled_back = strcmp(existing.error, "NONE") != 0;
        const char *final_outcome = rolled_back
            ? rollback_outcome_for_error(existing.error) : "CANARY_PASSED";
        int exact = strcmp(existing.outcome, "NONE") == 0 &&
            now != 0 && now < plan.overall_deadline_ms &&
            installed_apk_stable_exact(plan.package, &plan.a) == 0 &&
            (rolled_back ? rollback_inventory_exact(&plan) == 0
                         : final_recovery_artifacts_exact(&plan, &existing) == 0) &&
            (rolled_back ||
             live_db_semantic_exact(dir, &plan, plan.a.expected_schema, 0) == 0);
        if (!exact) {
            close(dir);
            pthread_mutex_unlock(&guard_lock);
            error_reply(ctx, "HOLD", "finalize");
            return;
        }
        existing.generation++;
        existing.phase = GUARD_PHASE_FINALIZED;
        existing.deadline_ms = 0;
        journal_set_role(&existing, "A", &plan.a);
        snprintf(existing.error, sizeof existing.error, "NONE");
        snprintf(existing.outcome, sizeof existing.outcome, "%s", final_outcome);
        int stored = store_journal_at(dir, &existing);
        uint64_t current = existing.generation;
        close(dir);
        pthread_mutex_unlock(&guard_lock);
        publish_reply(ctx, stored, "terminal", "GUARDACTION", current,
            GUARD_PHASE_FINALIZED);
        return;
    }
    if (manifest == 1) {
        close(dir); pthread_mutex_unlock(&guard_lock);
        if (strcmp(plan.session, tokens[0]) == 0 &&
            existing.generation >= generation && existing.phase >= GUARD_PHASE_PREPARED)
            ok_reply(ctx, "GUARDACTION", existing.generation, existing.phase);
        else error_reply(ctx, "ARMED", "action");
        return;
    }
    int loaded = manifest == 0 ? load_draft_reconciled(dir, &plan) : -1;
    if (loaded != 1 || strcmp(plan.session, tokens[0]) != 0 || plan.generation != generation ||
        !plan.a.staged || !plan.b.staged || !plan.settings_authority_staged) {
        if (dir >= 0) close(dir);
        pthread_mutex_unlock(&guard_lock);
        error_reply(ctx, loaded == 1 ? "STALE" : (loaded == 0 ? "STATE" : "HOLD"), "capture");
        return;
    }
    if (!guard_caller_owns_plan(ctx, &plan)) {
        close(dir); pthread_mutex_unlock(&guard_lock);
        error_reply(ctx, "STATE", "package"); return;
    }
    if (!guard_maintenance_supervised()) {
        close(dir); pthread_mutex_unlock(&guard_lock);
        error_reply(ctx, "UNSUPERVISED", "arm"); return;
    }
    guard_capture capture;
    int capture_state = load_capture_at(dir, &capture);
    if (capture_state == 0) {
        int intent_publish = start_capture_locked(dir, &plan, &capture);
        if (intent_publish != GUARD_PUBLISH_COMMITTED) {
            close(dir);
            pthread_mutex_unlock(&guard_lock);
            error_reply(ctx,
                intent_publish == GUARD_PUBLISH_INDETERMINATE ? "INDETERMINATE" : "IO",
                "capture_intent");
            return;
        }
    } else if (capture_state < 0 || !capture_matches_draft(&capture, &plan) ||
               capture.state >= GUARD_CAPTURE_FAILED_LAUNCHING) {
        close(dir);
        pthread_mutex_unlock(&guard_lock);
        error_reply(ctx, "HOLD", "capture_intent");
        return;
    }
    int capture_publish = complete_capture_locked(dir, &plan, &capture);
    if (capture_publish != GUARD_PUBLISH_COMMITTED) {
        close(dir);
        pthread_mutex_unlock(&guard_lock);
        /* Once the intent is published the caller cannot infer whether force-stop or a later
         * rename occurred.  Durable STATUS is the only safe settlement authority. */
        error_reply(ctx, "INDETERMINATE", "capture");
        return;
    }

    guard_journal journal;
    int complete = load_manifest_reconciled(dir, &plan, &journal, 1);
    close(dir);
    pthread_mutex_unlock(&guard_lock);
    if (complete != 1 || !guard_maintenance_supervised()) {
        error_reply(ctx, "UNSUPERVISED", "owner_lost");
        return;
    }
    ok_reply(ctx, "GUARDACTION", journal.generation, journal.phase);
}

int guard_maintenance_supervisor_tick(void) {
    if (!guard_maintenance_supervisor_authoritative() || !guard_initialized) return -1;
    pthread_mutex_lock(&guard_lock);
    int dir = open_guard_dir();
    guard_plan plan;
    guard_journal journal;
    int retirement = dir >= 0 ? reconcile_terminal_retirement_at(dir) : -1;
    if (retirement != 0) {
        if (dir >= 0) close(dir);
        pthread_mutex_unlock(&guard_lock);
        return retirement == 1 ? GUARD_WORK_NONE : -1;
    }
    int loaded = load_manifest_reconciled(dir, &plan, &journal, 1);
    if (loaded == 0) {
        guard_plan draft;
        guard_capture capture;
        int draft_state = dir >= 0 ? load_draft_reconciled(dir, &draft) : -1;
        int capture_state = dir >= 0 ? load_capture_at(dir, &capture) : -1;
        if (capture_state == 0 && draft_state >= 0) {
            if (dir >= 0) close(dir);
            pthread_mutex_unlock(&guard_lock);
            return GUARD_WORK_NONE;
        }
        if (draft_state != 1 || capture_state != 1 ||
            !capture_matches_draft(&capture, &draft)) {
            if (dir >= 0) close(dir);
            pthread_mutex_unlock(&guard_lock);
            return -1;
        }
        if (capture.state == GUARD_CAPTURE_FAILED_NO_MUTATION) {
            close(dir);
            pthread_mutex_unlock(&guard_lock);
            return GUARD_WORK_NONE;
        }
        if (capture.state == GUARD_CAPTURE_FAILED_LAUNCHING) {
            close(dir);
            pthread_mutex_unlock(&guard_lock);
            return GUARD_WORK_LAUNCH_A;
        }
        int completed = complete_capture_locked(dir, &draft, &capture);
        if (completed == 0) {
            close(dir);
            pthread_mutex_unlock(&guard_lock);
            return GUARD_WORK_NONE;
        }
        /* A post-publication failure may already have produced a valid manifest.  Reconcile
         * that successor before classifying the capture as failed. */
        loaded = load_manifest_reconciled(dir, &plan, &journal, 1);
        if (loaded == 1) {
            close(dir);
            pthread_mutex_unlock(&guard_lock);
            return GUARD_WORK_NONE;
        }
        if (loaded < 0 || installed_apk_stable_exact(draft.package, &draft.a) != 0) {
            close(dir);
            pthread_mutex_unlock(&guard_lock);
            return -1;
        }
        capture.state = GUARD_CAPTURE_FAILED_LAUNCHING;
        if (store_capture_at(dir, &capture) != 0) {
            close(dir);
            pthread_mutex_unlock(&guard_lock);
            return -1;
        }
        close(dir);
        pthread_mutex_unlock(&guard_lock);
        return GUARD_WORK_LAUNCH_A;
    }
    if (loaded != 1) {
        if (dir >= 0) close(dir);
        pthread_mutex_unlock(&guard_lock);
        return -1;
    }
    int work = GUARD_WORK_NONE;
    uint64_t overall_now = monotonic_ms();
    if (overall_now == 0) {
        journal.generation++;
        journal.phase = GUARD_PHASE_AMBIGUOUS;
        journal_clear_role(&journal);
        journal.deadline_ms = 0;
        snprintf(journal.error, sizeof journal.error, "CLOCK_FAILED");
        snprintf(journal.outcome, sizeof journal.outcome, "AMBIGUOUS");
        int stored = store_journal_at(dir, &journal);
        close(dir); pthread_mutex_unlock(&guard_lock);
        return stored == 0 ? GUARD_WORK_NONE : -1;
    }
    if (overall_now >= plan.overall_deadline_ms &&
        journal.phase != GUARD_PHASE_ROLLBACK_REQUIRED &&
        journal.phase != GUARD_PHASE_ROLLBACK_A_SUBMITTED &&
        journal.phase != GUARD_PHASE_ROLLBACK_DB_PREPARED &&
        journal.phase != GUARD_PHASE_ROLLBACK_DB_RESTORED &&
        journal.phase != GUARD_PHASE_A_HEALTHY &&
        journal.phase != GUARD_PHASE_FINALIZED &&
        journal.phase != GUARD_PHASE_AMBIGUOUS) {
        journal.generation++;
        journal.phase = GUARD_PHASE_ROLLBACK_REQUIRED;
        journal_clear_role(&journal);
        journal.deadline_ms = 0;
        snprintf(journal.error, sizeof journal.error, "OVERALL_TIMEOUT");
        int stored = store_journal_at(dir, &journal);
        close(dir); pthread_mutex_unlock(&guard_lock);
        return stored == 0 ? GUARD_WORK_NONE : -1;
    }
    if (overall_now >= plan.overall_deadline_ms &&
        (journal.phase == GUARD_PHASE_ROLLBACK_REQUIRED ||
         journal.phase == GUARD_PHASE_ROLLBACK_A_SUBMITTED ||
         journal.phase == GUARD_PHASE_ROLLBACK_DB_PREPARED ||
         journal.phase == GUARD_PHASE_ROLLBACK_DB_RESTORED)) {
        journal.generation++;
        journal.phase = GUARD_PHASE_AMBIGUOUS;
        journal_clear_role(&journal);
        journal.deadline_ms = 0;
        snprintf(journal.error, sizeof journal.error, "RECOVERY_TIMEOUT");
        snprintf(journal.outcome, sizeof journal.outcome, "AMBIGUOUS");
        int stored = store_journal_at(dir, &journal);
        close(dir); pthread_mutex_unlock(&guard_lock);
        return stored == GUARD_PUBLISH_COMMITTED ? GUARD_WORK_NONE : -1;
    }
    int forward_phase = journal.phase == GUARD_PHASE_PREPARED ||
        journal.phase == GUARD_PHASE_SUBMITTED_B ||
        journal.phase == GUARD_PHASE_WAIT_B_HEALTH ||
        journal.phase == GUARD_PHASE_B_HEALTHY ||
        journal.phase == GUARD_PHASE_RECOVERY_WITHHELD ||
        journal.phase == GUARD_PHASE_WAIT_A_REFUSAL ||
        journal.phase == GUARD_PHASE_A_REFUSED;
    if (overall_now >= plan.forward_deadline_ms && forward_phase) {
        journal.generation++;
        journal.phase = GUARD_PHASE_ROLLBACK_REQUIRED;
        journal_clear_role(&journal);
        journal.deadline_ms = 0;
        snprintf(journal.error, sizeof journal.error, "FORWARD_TIMEOUT");
        int stored = store_journal_at(dir, &journal);
        close(dir); pthread_mutex_unlock(&guard_lock);
        return stored == 0 ? GUARD_WORK_NONE : -1;
    }
    if (journal.phase == GUARD_PHASE_PREPARED) {
        if (hash_regular_at(dir, GUARD_B_APK, plan.b.bytes, plan.b.sha) != 0) {
            close(dir); pthread_mutex_unlock(&guard_lock); return -1;
        }
        journal.generation++;
        journal.phase = GUARD_PHASE_SUBMITTED_B;
        journal.pm_settled = 0;
        journal_clear_role(&journal);
        journal.deadline_ms = 0;
        snprintf(journal.error, sizeof journal.error, "NONE");
        if (store_journal_at(dir, &journal) != 0) {
            close(dir); pthread_mutex_unlock(&guard_lock); return -1;
        }
        work = GUARD_WORK_INSTALL_B;
    } else if (journal.phase == GUARD_PHASE_B_HEALTHY &&
               journal.premigrate_bytes != 0 && journal.b_primary_bytes != 0) {
        int sealed = installed_apk_stable_exact(plan.package, &plan.b) == 0 &&
            force_stop_guard_app(plan.package) == 0
            ? (TEST_FAULT(GUARD_TEST_FAULT_WITHHOLD_AFTER_FORCE_STOP)
                ? GUARD_PUBLISH_INDETERMINATE
                : seal_recovery_custody(dir, &plan, &journal))
            : GUARD_PUBLISH_FAILED;
        if (sealed == GUARD_PUBLISH_COMMITTED) {
            journal.generation++;
            journal.phase = GUARD_PHASE_RECOVERY_WITHHELD;
            journal.deadline_ms = 0;
            journal_set_role(&journal, "B", &plan.b);
            snprintf(journal.error, sizeof journal.error, "NONE");
            if (store_journal_at(dir, &journal) != GUARD_PUBLISH_COMMITTED)
                work = GUARD_WORK_NONE;
        } else if (sealed == GUARD_PUBLISH_INDETERMINATE) {
            work = GUARD_WORK_NONE;
        } else {
            work = -1;
        }
    } else if (journal.phase == GUARD_PHASE_RECOVERY_WITHHELD) {
        if (advance_recovery_withheld_locked(dir, &plan, &journal) != 0) {
            close(dir); pthread_mutex_unlock(&guard_lock); return -1;
        }
        work = GUARD_WORK_LAUNCH_B;
    } else if (journal.phase == GUARD_PHASE_A_REFUSED &&
               strcmp(journal.error, "RESTORE_INTENT") == 0) {
        int restored = resume_restore_intent_locked(dir, &plan, &journal);
        work = restored == GUARD_PUBLISH_FAILED ? -1 : GUARD_WORK_NONE;
    } else if (journal.phase == GUARD_PHASE_RECOVERY_RESTORED) {
        if (overall_now >= plan.overall_deadline_ms ||
            installed_apk_stable_exact(plan.package, &plan.b) != 0 ||
            live_premigrate_state(&plan) != 1 ||
            hash_regular_at(dir, GUARD_PREMIGRATE, journal.premigrate_bytes,
                            journal.premigrate_sha) != 0) {
            close(dir); pthread_mutex_unlock(&guard_lock); return -1;
        }
        journal.generation++;
        journal.phase = GUARD_PHASE_SUBMITTED_A;
        journal.pm_settled = 0;
        journal_clear_role(&journal);
        journal.deadline_ms = 0;
        snprintf(journal.error, sizeof journal.error, "NONE");
        if (store_journal_at(dir, &journal) != 0) {
            close(dir); pthread_mutex_unlock(&guard_lock); return -1;
        }
        work = GUARD_WORK_INSTALL_A;
    } else if (journal.phase == GUARD_PHASE_ROLLBACK_REQUIRED) {
        if (!journal.pm_settled || journal.rollback_attempt_consumed ||
            overall_now >= plan.overall_deadline_ms) {
            journal.generation++;
            journal.phase = GUARD_PHASE_AMBIGUOUS;
            journal_clear_role(&journal);
            journal.deadline_ms = 0;
            snprintf(journal.error, sizeof journal.error, "ROLLBACK_AUTHORITY_LOST");
            snprintf(journal.outcome, sizeof journal.outcome, "AMBIGUOUS");
            if (store_journal_at(dir, &journal) != GUARD_PUBLISH_COMMITTED) work = -1;
        } else {
            int exact_a = installed_apk_stable_exact(plan.package, &plan.a) == 0;
            int exact_b = !exact_a && installed_apk_stable_exact(plan.package, &plan.b) == 0;
            journal.generation++;
            journal.rollback_attempt_consumed = 1;
            journal.recovery_deadline_ms = plan.overall_deadline_ms;
            journal.deadline_ms = 0;
            if (exact_a) {
                journal.phase = GUARD_PHASE_ROLLBACK_DB_PREPARED;
                journal.pm_settled = 1;
                journal_set_role(&journal, "A", &plan.a);
            } else if (exact_b) {
                journal.phase = GUARD_PHASE_ROLLBACK_A_SUBMITTED;
                journal.pm_settled = 0;
                journal_clear_role(&journal);
                work = GUARD_WORK_INSTALL_A;
            } else {
                journal.phase = GUARD_PHASE_AMBIGUOUS;
                journal.pm_settled = 0;
                journal_clear_role(&journal);
                snprintf(journal.error, sizeof journal.error, "PACKAGE_UNKNOWN");
                snprintf(journal.outcome, sizeof journal.outcome, "AMBIGUOUS");
            }
            if (store_journal_at(dir, &journal) != GUARD_PUBLISH_COMMITTED) work = -1;
        }
    } else if (journal.phase == GUARD_PHASE_ROLLBACK_DB_PREPARED) {
        int restored = restore_baseline_primary(dir, &plan, &journal);
        if (restored == GUARD_PUBLISH_COMMITTED) {
            uint64_t now = monotonic_ms();
            if (now == 0 || now >= plan.overall_deadline_ms ||
                now > UINT64_MAX - GUARD_HEALTH_TIMEOUT_MS) {
                work = -1;
            } else {
                journal.generation++;
                journal.phase = GUARD_PHASE_ROLLBACK_DB_RESTORED;
                journal.pm_settled = 1;
                journal_set_role(&journal, "A", &plan.a);
                uint64_t phase_deadline = now + GUARD_HEALTH_TIMEOUT_MS;
                journal.deadline_ms = phase_deadline < plan.overall_deadline_ms
                    ? phase_deadline : plan.overall_deadline_ms;
                if (store_journal_at(dir, &journal) != GUARD_PUBLISH_COMMITTED) work = -1;
                else work = GUARD_WORK_LAUNCH_A;
            }
        } else if (restored == GUARD_PUBLISH_INDETERMINATE) {
            work = GUARD_WORK_NONE;
        } else {
            work = -1;
        }
    } else if (journal.phase == GUARD_PHASE_SUBMITTED_A ||
               journal.phase == GUARD_PHASE_SUBMITTED_B ||
               journal.phase == GUARD_PHASE_ROLLBACK_A_SUBMITTED) {
        int active = pm_process_active(&journal);
        if (active == 0) {
            int rollback_submit = journal.phase == GUARD_PHASE_ROLLBACK_A_SUBMITTED;
            const guard_artifact *target = journal.phase == GUARD_PHASE_SUBMITTED_B
                ? &plan.b : &plan.a;
            const guard_artifact *incumbent = target == &plan.b ? &plan.a : &plan.b;
            int target_exact = installed_apk_stable_exact(plan.package, target) == 0;
            int incumbent_exact = !target_exact && installed_apk_stable_exact(plan.package, incumbent) == 0;
            journal_clear_pm_process(&journal);
            journal.pm_settled = 1;
            if (target_exact && rollback_submit) {
                journal.generation++;
                journal.phase = GUARD_PHASE_ROLLBACK_DB_PREPARED;
                journal.deadline_ms = 0;
                journal_set_role(&journal, "A", &plan.a);
                if (store_journal_at(dir, &journal) != GUARD_PUBLISH_COMMITTED) work = -1;
            } else if (target_exact) {
                int settled = settle_submitted_locked(
                    dir, &plan, &journal, "IDENTITY_MISMATCH");
                if (settled != 1) work = -1;
                else work = target == &plan.b ? GUARD_WORK_LAUNCH_B : GUARD_WORK_LAUNCH_A;
            } else if (incumbent_exact && !rollback_submit) {
                journal.generation++;
                journal.phase = GUARD_PHASE_ROLLBACK_REQUIRED;
                journal.deadline_ms = 0;
                journal_clear_role(&journal);
                snprintf(journal.error, sizeof journal.error, "PM_REJECTED");
                if (store_journal_at(dir, &journal) != GUARD_PUBLISH_COMMITTED) work = -1;
            } else {
                journal.generation++;
                journal.phase = GUARD_PHASE_AMBIGUOUS;
                journal.deadline_ms = 0;
                journal_clear_role(&journal);
                snprintf(journal.error, sizeof journal.error,
                    rollback_submit ? "ROLLBACK_EXECUTOR_LOST" : "PACKAGE_UNKNOWN");
                snprintf(journal.outcome, sizeof journal.outcome, "AMBIGUOUS");
                if (store_journal_at(dir, &journal) != GUARD_PUBLISH_COMMITTED) work = -1;
            }
        } else if (active < 0) {
            int recorded = journal.pm_spawned;
            journal_clear_pm_process(&journal);
            journal.generation++;
            journal.phase = GUARD_PHASE_AMBIGUOUS;
            journal_clear_role(&journal);
            journal.deadline_ms = 0;
            snprintf(journal.error, sizeof journal.error,
                recorded ? "EXECUTOR_UNKNOWN" : "EXECUTOR_NOT_RECORDED");
            snprintf(journal.outcome, sizeof journal.outcome, "AMBIGUOUS");
            if (store_journal_at(dir, &journal) != GUARD_PUBLISH_COMMITTED) work = -1;
        }
    } else if ((journal.phase == GUARD_PHASE_WAIT_B_HEALTH ||
                journal.phase == GUARD_PHASE_WAIT_A_HEALTH ||
                journal.phase == GUARD_PHASE_WAIT_A_REFUSAL ||
                journal.phase == GUARD_PHASE_ROLLBACK_DB_RESTORED) &&
               journal.deadline_ms != 0) {
        uint64_t now = monotonic_ms();
        if (now == 0) {
            journal.generation++;
            journal.phase = GUARD_PHASE_AMBIGUOUS;
            journal_clear_role(&journal);
            journal.deadline_ms = 0;
            snprintf(journal.error, sizeof journal.error, "CLOCK_FAILED");
            snprintf(journal.outcome, sizeof journal.outcome, "AMBIGUOUS");
            if (store_journal_at(dir, &journal) != 0) work = -1;
        } else if (now >= journal.deadline_ms) {
            enum guard_phase expired_phase = journal.phase;
            journal.generation++;
            journal.phase = expired_phase == GUARD_PHASE_ROLLBACK_DB_RESTORED
                ? GUARD_PHASE_AMBIGUOUS : GUARD_PHASE_ROLLBACK_REQUIRED;
            journal_clear_role(&journal);
            journal.deadline_ms = 0;
            snprintf(journal.error, sizeof journal.error, "%s",
                expired_phase == GUARD_PHASE_WAIT_A_REFUSAL
                    ? "REFUSAL_TIMEOUT" : "HEALTH_TIMEOUT");
            if (expired_phase == GUARD_PHASE_ROLLBACK_DB_RESTORED)
                snprintf(journal.outcome, sizeof journal.outcome, "AMBIGUOUS");
            if (store_journal_at(dir, &journal) != 0) work = -1;
        }
    }
    close(dir);
    pthread_mutex_unlock(&guard_lock);
    return work;
}

int guard_maintenance_supervisor_work_deadline(enum guard_supervisor_work work,
                                               uint64_t *deadline_ms) {
    if (!guard_maintenance_supervisor_authoritative() || !deadline_ms) return -1;
    pthread_mutex_lock(&guard_lock);
    int dir = open_guard_dir();
    guard_plan plan;
    guard_journal journal;
    int loaded = dir >= 0 ? load_manifest_reconciled(dir, &plan, &journal, 0) : -1;
    uint64_t deadline = 0;
    int expected = 0;
    if (loaded == 1) {
        switch (work) {
            case GUARD_WORK_INSTALL_B:
                expected = journal.phase == GUARD_PHASE_SUBMITTED_B;
                deadline = plan.forward_deadline_ms;
                break;
            case GUARD_WORK_INSTALL_A:
                expected = journal.phase == GUARD_PHASE_SUBMITTED_A ||
                    journal.phase == GUARD_PHASE_ROLLBACK_A_SUBMITTED;
                deadline = plan.overall_deadline_ms;
                break;
            case GUARD_WORK_LAUNCH_B:
                expected = journal.phase == GUARD_PHASE_WAIT_B_HEALTH ||
                    journal.phase == GUARD_PHASE_WAIT_A_REFUSAL;
                deadline = journal.deadline_ms;
                break;
            case GUARD_WORK_LAUNCH_A:
                expected = journal.phase == GUARD_PHASE_WAIT_A_HEALTH ||
                    journal.phase == GUARD_PHASE_ROLLBACK_DB_RESTORED;
                deadline = journal.deadline_ms;
                break;
            default:
                break;
        }
    }
    uint64_t now = monotonic_ms();
    if (dir >= 0) close(dir);
    pthread_mutex_unlock(&guard_lock);
    if (!expected || now == 0 || deadline == 0 || now >= deadline) return -1;
    *deadline_ms = deadline;
    return 0;
}

static int guard_plan_package_now(char package[GUARD_PACKAGE_BYTES]) {
    pthread_mutex_lock(&guard_lock);
    int dir = open_guard_dir();
    guard_plan plan;
    guard_journal journal;
    int loaded = dir >= 0 ? load_manifest_reconciled(dir, &plan, &journal, 0) : -1;
    if (loaded == 1) snprintf(package, GUARD_PACKAGE_BYTES, "%s", plan.package);
    if (dir >= 0) close(dir);
    pthread_mutex_unlock(&guard_lock);
    return loaded == 1 ? 0 : -1;
}

int guard_maintenance_supervisor_start_work(enum guard_supervisor_work work, pid_t *pid) {
    if (!guard_maintenance_supervisor_authoritative() || !pid) return -1;
    uint64_t deadline;
    if (guard_maintenance_supervisor_work_deadline(work, &deadline) != 0) return -1;
    const char *path;
    const char *const *argv;
    static const char *const install_a[] = {
        "pm", "install", "-r", "-d", GUARD_A_APK_PATH, NULL,
    };
    static const char *const install_b[] = {
        "pm", "install", "-r", "-d", GUARD_B_APK_PATH, NULL,
    };
    /* The launch target is the package the plan is bound to.  The supervisor has no caller, so it
     * reads the durable record; work_deadline above already required a loadable manifest. */
    char package[GUARD_PACKAGE_BYTES];
    const char *launch[7];
    int package_work = 1;
    if (work == GUARD_WORK_INSTALL_A) { path = "/system/bin/pm"; argv = install_a; }
    else if (work == GUARD_WORK_INSTALL_B) { path = "/system/bin/pm"; argv = install_b; }
    else if (work == GUARD_WORK_LAUNCH_A || work == GUARD_WORK_LAUNCH_B) {
        if (guard_plan_package_now(package) != 0) return -1;
        launch[0] = "monkey";
        launch[1] = "-p";
        launch[2] = package;
        launch[3] = "-c";
        launch[4] = "android.intent.category.LAUNCHER";
        launch[5] = "1";
        launch[6] = NULL;
        path = "/system/bin/monkey"; argv = launch; package_work = 0;
    } else return -1;
    if (sysexec_start_argv(path, argv, 1, pid) != 0) return -1;
    if (!package_work) return 0;

    uint64_t start_ticks = 0;
    if (process_start_ticks(*pid, &start_ticks) != 1) {
        int ignored;
        (void)sysexec_terminate_argv(*pid, &ignored);
        return -2;
    }
    pthread_mutex_lock(&guard_lock);
    int dir = open_guard_dir();
    guard_plan plan;
    guard_journal journal;
    int loaded = dir >= 0 ? load_manifest_reconciled(dir, &plan, &journal, 0) : -1;
    int expected = loaded == 1 &&
        ((work == GUARD_WORK_INSTALL_B && journal.phase == GUARD_PHASE_SUBMITTED_B) ||
         (work == GUARD_WORK_INSTALL_A &&
          (journal.phase == GUARD_PHASE_SUBMITTED_A ||
           journal.phase == GUARD_PHASE_ROLLBACK_A_SUBMITTED))) &&
        !journal.pm_spawned && !journal.pm_settled;
    int stored = GUARD_PUBLISH_FAILED;
    if (expected) {
        journal.pm_spawned = 1;
        journal.pm_pid = (uint64_t)*pid;
        journal.pm_start_ticks = start_ticks;
        stored = store_journal_at(dir, &journal);
    }
    if (dir >= 0) close(dir);
    pthread_mutex_unlock(&guard_lock);
    if (stored != GUARD_PUBLISH_COMMITTED) {
        int ignored;
        (void)sysexec_terminate_argv(*pid, &ignored);
        return -2;
    }
    return 0;
}

int guard_maintenance_supervisor_complete(enum guard_supervisor_work work,
                                          enum guard_execution_result result, int wait_status) {
    if (!guard_maintenance_supervisor_authoritative()) return -1;
    pthread_mutex_lock(&guard_lock);
    int dir = open_guard_dir();
    guard_plan plan;
    guard_journal journal;
    int loaded = dir >= 0 ? load_manifest_reconciled(dir, &plan, &journal, 0) : -1;
    if (loaded != 1) {
        if (loaded == 0 && work == GUARD_WORK_LAUNCH_A && dir >= 0) {
            guard_plan draft;
            guard_capture capture;
            int draft_state = load_draft_reconciled(dir, &draft);
            int capture_state = load_capture_at(dir, &capture);
            if (draft_state == 1 && capture_state == 1 &&
                capture_matches_draft(&capture, &draft) &&
                capture.state == GUARD_CAPTURE_FAILED_LAUNCHING &&
                result == GUARD_EXEC_REAPED && WIFEXITED(wait_status) &&
                WEXITSTATUS(wait_status) == 0 && installed_apk_stable_exact(draft.package, &draft.a) == 0) {
                capture.state = GUARD_CAPTURE_FAILED_NO_MUTATION;
                int stored = store_capture_at(dir, &capture);
                close(dir);
                pthread_mutex_unlock(&guard_lock);
                return stored == 0 ? GUARD_WORK_NONE : -1;
            }
        }
        if (dir >= 0) close(dir);
        pthread_mutex_unlock(&guard_lock);
        return -1;
    }
    if (work == GUARD_WORK_LAUNCH_A || work == GUARD_WORK_LAUNCH_B) {
        int expected = work == GUARD_WORK_LAUNCH_A
            ? (journal.phase == GUARD_PHASE_WAIT_A_HEALTH ||
               journal.phase == GUARD_PHASE_ROLLBACK_DB_RESTORED)
            : (journal.phase == GUARD_PHASE_WAIT_B_HEALTH ||
               journal.phase == GUARD_PHASE_WAIT_A_REFUSAL);
        if (!expected) { close(dir); pthread_mutex_unlock(&guard_lock); return -1; }
        if (result != GUARD_EXEC_REAPED || !WIFEXITED(wait_status) || WEXITSTATUS(wait_status) != 0) {
            journal.generation++;
            snprintf(journal.error, sizeof journal.error, "LAUNCH_FAILED");
            if (store_journal_at(dir, &journal) != 0) {
                close(dir); pthread_mutex_unlock(&guard_lock); return -1;
            }
        }
        close(dir); pthread_mutex_unlock(&guard_lock); return GUARD_WORK_NONE;
    }

    int rollback_install = work == GUARD_WORK_INSTALL_A &&
        journal.phase == GUARD_PHASE_ROLLBACK_A_SUBMITTED;
    enum guard_phase expected_phase = work == GUARD_WORK_INSTALL_B
        ? GUARD_PHASE_SUBMITTED_B : GUARD_PHASE_SUBMITTED_A;
    if (journal.phase != expected_phase && !rollback_install) {
        close(dir); pthread_mutex_unlock(&guard_lock); return -1;
    }
    journal_clear_pm_process(&journal);
    const guard_artifact *target = work == GUARD_WORK_INSTALL_B ? &plan.b : &plan.a;
    const guard_artifact *incumbent = work == GUARD_WORK_INSTALL_B ? &plan.a : &plan.b;
    int target_exact = installed_apk_stable_exact(plan.package, target) == 0;
    int incumbent_exact = !target_exact && installed_apk_stable_exact(plan.package, incumbent) == 0;
    int normal_exit = result == GUARD_EXEC_REAPED && WIFEXITED(wait_status);
    int success_exit = normal_exit && WEXITSTATUS(wait_status) == 0;
    int next_work = GUARD_WORK_NONE;
    if (target_exact && normal_exit) {
        if (rollback_install) {
            journal.generation++;
            journal.phase = GUARD_PHASE_ROLLBACK_DB_PREPARED;
            journal.pm_settled = 1;
            journal.deadline_ms = 0;
            journal_set_role(&journal, "A", &plan.a);
            if (store_journal_at(dir, &journal) != GUARD_PUBLISH_COMMITTED) next_work = -1;
        } else {
            int settled = settle_submitted_locked(dir, &plan, &journal, "IDENTITY_MISMATCH");
            if (settled != 1) next_work = -1;
            else next_work = work == GUARD_WORK_INSTALL_B
                ? GUARD_WORK_LAUNCH_B : GUARD_WORK_LAUNCH_A;
        }
    } else if (result == GUARD_EXEC_NOT_STARTED ||
               (normal_exit && !success_exit && incumbent_exact)) {
        journal.generation++;
        journal.phase = rollback_install ? GUARD_PHASE_AMBIGUOUS : GUARD_PHASE_ROLLBACK_REQUIRED;
        journal.pm_settled = 1;
        journal_clear_role(&journal);
        journal.deadline_ms = 0;
        snprintf(journal.error, sizeof journal.error,
            result == GUARD_EXEC_NOT_STARTED ? "PM_NOT_STARTED" : "PM_REJECTED");
        if (rollback_install) snprintf(journal.outcome, sizeof journal.outcome, "AMBIGUOUS");
        if (store_journal_at(dir, &journal) != 0) next_work = -1;
    } else {
        journal.generation++;
        journal.phase = GUARD_PHASE_AMBIGUOUS;
        journal_clear_role(&journal);
        journal.deadline_ms = 0;
        snprintf(journal.error, sizeof journal.error,
            result == GUARD_EXEC_TIMED_OUT ? "PM_TIMEOUT" :
            (result == GUARD_EXEC_WAIT_LOST ? "PM_WAIT_LOST" : "PM_INDETERMINATE"));
        snprintf(journal.outcome, sizeof journal.outcome, "AMBIGUOUS");
        if (store_journal_at(dir, &journal) != 0) next_work = -1;
    }
    close(dir);
    pthread_mutex_unlock(&guard_lock);
    return next_work;
}

void cmd_guardhealth(conn_ctx *ctx, const char *args) {
    if (!guard_admission_ready(ctx)) return;
    char storage[513], *tokens[14];
    int count = split_tokens(args, storage, tokens, 14);
    uint64_t generation, version_code, app_state_count;
    uint32_t schema;
    if (count != 13 || !lower_hex_64(tokens[0]) || !lower_hex_64(tokens[2]) ||
        (strcmp(tokens[3], "A") != 0 && strcmp(tokens[3], "B") != 0) ||
        !lower_hex_64(tokens[4]) ||
        parse_u64(tokens[1], 1, UINT64_MAX, &generation) != 0 ||
        parse_u64(tokens[5], 1, UINT64_MAX, &version_code) != 0 ||
        parse_u32(tokens[6], 1, UINT32_MAX, &schema) != 0 ||
        (strcmp(tokens[7], "OK") != 0 && strcmp(tokens[7], "FAIL") != 0) ||
        parse_u64(tokens[8], 1, UINT64_MAX, &app_state_count) != 0 ||
        !lower_hex_64(tokens[9]) || !lower_hex_64(tokens[10]) ||
        (strcmp(tokens[11], "PRESENT") != 0 && strcmp(tokens[11], "ABSENT") != 0) ||
        (strcmp(tokens[12], "NA") != 0 && strcmp(tokens[12], "RESTORED") != 0 &&
         strcmp(tokens[12], "BASELINE") != 0)) {
        error_reply(ctx, "ARGS", "health");
        return;
    }

    pthread_mutex_lock(&guard_lock);
    int dir = open_guard_dir();
    guard_plan plan;
    guard_journal journal;
    int loaded = dir >= 0 ? load_manifest_reconciled(dir, &plan, &journal, 0) : -1;
    if (loaded != 1) {
        if (dir >= 0) close(dir);
        pthread_mutex_unlock(&guard_lock);
        error_reply(ctx, loaded == 0 ? "STATE" : "HOLD", "health");
        return;
    }
    if (!guard_caller_owns_plan(ctx, &plan)) {
        close(dir); pthread_mutex_unlock(&guard_lock);
        error_reply(ctx, "STATE", "package"); return;
    }
    const guard_artifact *artifact = strcmp(tokens[3], "A") == 0 ? &plan.a : &plan.b;
    enum guard_phase expected_phase = artifact == &plan.b
        ? GUARD_PHASE_WAIT_B_HEALTH
        : (strcmp(tokens[12], "BASELINE") == 0
            ? GUARD_PHASE_ROLLBACK_DB_RESTORED : GUARD_PHASE_WAIT_A_HEALTH);
    int identity = strcmp(plan.session, tokens[0]) == 0 && strcmp(plan.boot, tokens[2]) == 0 &&
        strcmp(artifact->sha, tokens[4]) == 0 && artifact->version_code == version_code &&
        artifact->expected_schema == schema;
    int semantic = app_state_count == plan.baseline_app_state &&
        strcmp(tokens[9], plan.baseline_app_state_sha) == 0 &&
        strcmp(tokens[10], plan.baseline_settings_sha) == 0;
    int role_proof = artifact == &plan.b
        ? strcmp(tokens[11], "PRESENT") == 0 && strcmp(tokens[12], "NA") == 0
        : strcmp(tokens[11], "ABSENT") == 0 &&
          ((expected_phase == GUARD_PHASE_WAIT_A_HEALTH &&
            strcmp(tokens[12], "RESTORED") == 0) ||
           (expected_phase == GUARD_PHASE_ROLLBACK_DB_RESTORED &&
            strcmp(tokens[12], "BASELINE") == 0));

    /* An exact lost-reply replay is accepted only for the one immediately reached successor. */
    if (identity && semantic && role_proof && strcmp(tokens[7], "OK") == 0 &&
        journal.generation == generation + 1 &&
        ((artifact == &plan.b && journal.phase == GUARD_PHASE_B_HEALTHY) ||
         (artifact == &plan.a && journal.phase == GUARD_PHASE_A_HEALTHY))) {
        enum guard_phase phase = journal.phase;
        uint64_t current = journal.generation;
        close(dir);
        pthread_mutex_unlock(&guard_lock);
        ok_reply(ctx, "GUARDHEALTH", current, phase);
        return;
    }
    if (!identity || journal.generation != generation || journal.phase != expected_phase) {
        close(dir);
        pthread_mutex_unlock(&guard_lock);
        error_reply(ctx, identity ? "STALE" : "MISMATCH", "health");
        return;
    }

    uint64_t now = monotonic_ms();
    int phase_active = journal.deadline_ms != 0 && now != 0 &&
        now < journal.deadline_ms && now < plan.overall_deadline_ms &&
        (artifact != &plan.b || now < plan.forward_deadline_ms);
    int independently_exact = phase_active &&
        installed_apk_stable_exact(plan.package, artifact) == 0 &&
        live_db_semantic_exact(dir, &plan, schema, strcmp(tokens[11], "PRESENT") == 0) == 0 &&
        (artifact == &plan.b ||
         (strcmp(tokens[12], "BASELINE") == 0
            ? rollback_inventory_exact(&plan) == 0
            : final_recovery_artifacts_exact(&plan, &journal) == 0));
    if (expected_phase == GUARD_PHASE_ROLLBACK_DB_RESTORED &&
        (strcmp(journal.error, "NONE") == 0 || strcmp(journal.outcome, "NONE") != 0))
        independently_exact = 0;
    journal.generation++;
    journal.deadline_ms = 0;
    if (strcmp(tokens[7], "OK") == 0 && semantic && role_proof && independently_exact) {
        journal.phase = artifact == &plan.b ? GUARD_PHASE_B_HEALTHY : GUARD_PHASE_A_HEALTHY;
        journal_set_role(&journal, tokens[3], artifact);
        /* Rollback health is not terminal.  Preserve its durable cause and leave outcome NONE;
         * the generation-bound FINALIZE transition alone derives the public typed outcome. */
        if (expected_phase != GUARD_PHASE_ROLLBACK_DB_RESTORED)
            snprintf(journal.error, sizeof journal.error, "NONE");
    } else {
        journal.phase = expected_phase == GUARD_PHASE_ROLLBACK_DB_RESTORED
            ? GUARD_PHASE_AMBIGUOUS : GUARD_PHASE_ROLLBACK_REQUIRED;
        journal_clear_role(&journal);
        snprintf(journal.error, sizeof journal.error, "%s",
            !phase_active ? "HEALTH_TIMEOUT" :
            strcmp(tokens[7], "FAIL") == 0 ? "HEALTH_FAILED" :
            (!semantic || !role_proof ? "HEALTH_MISMATCH" : "HEALTH_UNVERIFIED"));
        if (expected_phase == GUARD_PHASE_ROLLBACK_DB_RESTORED)
            snprintf(journal.outcome, sizeof journal.outcome, "AMBIGUOUS");
    }
    int stored = store_journal_at(dir, &journal);
    enum guard_phase phase = journal.phase;
    uint64_t current = journal.generation;
    close(dir);
    pthread_mutex_unlock(&guard_lock);
    publish_reply(ctx, stored, "journal", "GUARDHEALTH", current, phase);
}

void cmd_guardrefusal(conn_ctx *ctx, const char *args) {
    if (!guard_admission_ready(ctx)) return;
    char storage[513], *tokens[8];
    int count = split_tokens(args, storage, tokens, 8);
    uint64_t generation, version_code;
    if (count != 7 || !lower_hex_64(tokens[0]) || !lower_hex_64(tokens[2]) ||
        strcmp(tokens[3], "A") != 0 || !lower_hex_64(tokens[4]) ||
        parse_u64(tokens[1], 1, UINT64_MAX, &generation) != 0 ||
        parse_u64(tokens[5], 1, UINT64_MAX, &version_code) != 0 ||
        strcmp(tokens[6], "PRIMARY_ABOVE_MAXIMUM_WITHOUT_PREMIGRATE") != 0) {
        error_reply(ctx, "ARGS", "refusal");
        return;
    }
    pthread_mutex_lock(&guard_lock);
    int dir = open_guard_dir();
    guard_plan plan;
    guard_journal journal;
    int loaded = dir >= 0 ? load_manifest_reconciled(dir, &plan, &journal, 0) : -1;
    if (loaded != 1) {
        if (dir >= 0) close(dir);
        pthread_mutex_unlock(&guard_lock);
        error_reply(ctx, loaded == 0 ? "STATE" : "HOLD", "refusal");
        return;
    }
    if (!guard_caller_owns_plan(ctx, &plan)) {
        close(dir); pthread_mutex_unlock(&guard_lock);
        error_reply(ctx, "STATE", "package"); return;
    }
    int identity = strcmp(plan.session, tokens[0]) == 0 && strcmp(plan.boot, tokens[2]) == 0 &&
        strcmp(plan.a.sha, tokens[4]) == 0 && plan.a.version_code == version_code;
    if (identity && journal.phase == GUARD_PHASE_A_REFUSED &&
        journal.generation == generation + 1) {
        uint64_t current = journal.generation;
        close(dir);
        pthread_mutex_unlock(&guard_lock);
        ok_reply(ctx, "GUARDREFUSAL", current, GUARD_PHASE_A_REFUSED);
        return;
    }
    if (!identity || journal.generation != generation ||
        journal.phase != GUARD_PHASE_WAIT_A_REFUSAL) {
        close(dir);
        pthread_mutex_unlock(&guard_lock);
        error_reply(ctx, identity ? "STALE" : "MISMATCH", "refusal");
        return;
    }
    uint64_t now = monotonic_ms();
    int independently_exact = journal.deadline_ms != 0 && now != 0 &&
        now < journal.deadline_ms && now < plan.forward_deadline_ms &&
        now < plan.overall_deadline_ms &&
        installed_apk_stable_exact(plan.package, &plan.b) == 0 &&
        live_db_semantic_exact(dir, &plan, plan.b.expected_schema, 1) == 0;
    if (!independently_exact) {
        close(dir);
        pthread_mutex_unlock(&guard_lock);
        error_reply(ctx, "HOLD", "refusal");
        return;
    }
    journal.generation++;
    journal.phase = GUARD_PHASE_A_REFUSED;
    journal.deadline_ms = 0;
    journal_set_role(&journal, "B", &plan.b);
    snprintf(journal.error, sizeof journal.error, "NONE");
    int stored = store_journal_at(dir, &journal);
    uint64_t current = journal.generation;
    close(dir);
    pthread_mutex_unlock(&guard_lock);
    publish_reply(ctx, stored, "journal", "GUARDREFUSAL", current,
        GUARD_PHASE_A_REFUSED);
}

int guard_maintenance_package_busy(void) {
    if (!guard_initialized) return 1;
    pthread_mutex_lock(&guard_lock);
    int dir = open_guard_dir();
    guard_plan plan;
    int manifest = dir >= 0 ? load_plan_at(dir, GUARD_MANIFEST, &plan) : -1;
    int draft = manifest == 0 && dir >= 0 ? load_plan_at(dir, GUARD_DRAFT, &plan) : 0;
    guard_journal journal;
    int journal_state = dir >= 0 ? load_journal_at(dir, &journal) : -1;
    int empty = dir >= 0 && manifest == 0 && draft == 0 && journal_state == 0 &&
        empty_inventory_safe(dir);
    if (dir >= 0) close(dir);
    pthread_mutex_unlock(&guard_lock);
    return !empty;
}

int guard_maintenance_install_begin(void) {
    if (pthread_mutex_trylock(&package_gate) != 0) return -1;
    if (guard_maintenance_package_busy()) {
        pthread_mutex_unlock(&package_gate);
        return -1;
    }
    return 0;
}

void guard_maintenance_install_end(void) { pthread_mutex_unlock(&package_gate); }

static int known_file_present(int dir, const char *name) {
    struct stat st;
    if (fstatat(dir, name, &st, AT_SYMLINK_NOFOLLOW) == 0) return 1;
    return errno == ENOENT ? 0 : -1;
}

static int empty_inventory_safe(int dir) {
    int duplicate = openat(dir, ".", O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC);
    if (duplicate < 0) return 0;
    DIR *stream = fdopendir(duplicate);
    if (!stream) { close(duplicate); return 0; }
    int safe = 1;
    errno = 0;
    for (struct dirent *entry = readdir(stream); entry; entry = readdir(stream)) {
        if (strcmp(entry->d_name, ".") == 0 || strcmp(entry->d_name, "..") == 0 ||
            strcmp(entry->d_name, GUARD_OWNER_LOCK) == 0) {
            errno = 0;
            continue;
        }
        safe = 0;
        break;
    }
    if (errno != 0) safe = 0;
    closedir(stream);
    return safe;
}

static int replacement_namespace_empty(int dir) {
    return empty_inventory_safe(dir);
}

static int terminal_outcome(const char *outcome) {
    static const char *const outcomes[] = {
        "CANARY_PASSED", "ROLLED_BACK_PM_REJECTED", "ROLLED_BACK_HEALTH_FAILED",
        "ROLLED_BACK_HEALTH_TIMEOUT", "ROLLED_BACK_REFUSAL_TIMEOUT",
        "ROLLED_BACK_OVERALL_TIMEOUT", "ROLLED_BACK_OPERATOR",
    };
    for (size_t index = 0; index < sizeof outcomes / sizeof outcomes[0]; index++)
        if (strcmp(outcome, outcomes[index]) == 0) return 1;
    return 0;
}

static int retirement_name_allowed(const char *name, int manifest_present) {
    if (strcmp(name, GUARD_OWNER_LOCK) == 0 || strcmp(name, GUARD_JOURNAL) == 0)
        return 1;
    if (!manifest_present) return 0;
    return strcmp(name, GUARD_MANIFEST) == 0 || strcmp(name, GUARD_A_APK) == 0 ||
        strcmp(name, GUARD_B_APK) == 0 || strcmp(name, GUARD_SETTINGS) == 0 ||
        strcmp(name, GUARD_BASELINE) == 0 || strcmp(name, GUARD_PREMIGRATE) == 0 ||
        strcmp(name, GUARD_B_PRIMARY) == 0;
}

static int retirement_namespace_exact(int dir, int manifest_present) {
    int duplicate = openat(dir, ".", O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC);
    if (duplicate < 0) return 0;
    DIR *stream = fdopendir(duplicate);
    if (!stream) { close(duplicate); return 0; }
    int exact = 1;
    errno = 0;
    for (struct dirent *entry = readdir(stream); entry; entry = readdir(stream)) {
        if (strcmp(entry->d_name, ".") == 0 || strcmp(entry->d_name, "..") == 0) {
            errno = 0;
            continue;
        }
        if (!retirement_name_allowed(entry->d_name, manifest_present)) {
            exact = 0;
            break;
        }
        errno = 0;
    }
    if (errno != 0) exact = 0;
    closedir(stream);
    return exact;
}

static int retirement_owner_lock_exact(int dir) {
    int named = openat(dir, GUARD_OWNER_LOCK, O_RDONLY | O_NOFOLLOW | O_CLOEXEC);
    struct stat held_st, named_st;
    int exact = guard_owner_fd >= 0 && named >= 0 &&
        fstat(guard_owner_fd, &held_st) == 0 && fstat(named, &named_st) == 0 &&
        S_ISREG(held_st.st_mode) && held_st.st_uid == geteuid() &&
        held_st.st_gid == getegid() && held_st.st_nlink == 1 &&
        (held_st.st_mode & 07777) == 0600 && held_st.st_dev == named_st.st_dev &&
        held_st.st_ino == named_st.st_ino;
    if (named >= 0) close(named);
    return exact;
}

static int retirement_regular_exact_at(int dir, const char *name,
                                       uint64_t bytes, const char *sha) {
    struct stat st;
    return fstatat(dir, name, &st, AT_SYMLINK_NOFOLLOW) == 0 &&
        S_ISREG(st.st_mode) && st.st_uid == geteuid() && st.st_nlink == 1 &&
        (st.st_mode & 07777) == 0400 && (uint64_t)st.st_size == bytes &&
        hash_regular_at(dir, name, bytes, sha) == 0;
}

static int retirement_file_exact_or_absent(int dir, const char *name,
                                           uint64_t bytes, const char *sha) {
    int present = known_file_present(dir, name);
    if (present < 0) return 0;
    if (bytes == 0 || strcmp(sha, "NONE") == 0)
        return present == 0 && bytes == 0 && strcmp(sha, "NONE") == 0;
    return present == 0 || retirement_regular_exact_at(dir, name, bytes, sha);
}

static int retirement_files_exact_or_absent(int dir, const guard_plan *plan,
                                            const guard_journal *journal) {
    int settings = known_file_present(dir, GUARD_SETTINGS);
    return retirement_namespace_exact(dir, 1) &&
        retirement_file_exact_or_absent(dir, GUARD_A_APK,
            plan->a.bytes, plan->a.sha) &&
        retirement_file_exact_or_absent(dir, GUARD_B_APK,
            plan->b.bytes, plan->b.sha) &&
        retirement_file_exact_or_absent(dir, GUARD_SETTINGS,
            plan->settings_authority_bytes, plan->settings_authority_sha) &&
        (settings == 0 || (settings == 1 && validate_settings_authority_at(dir, plan) == 0)) &&
        retirement_file_exact_or_absent(dir, GUARD_BASELINE,
            plan->baseline_bytes, plan->baseline_sha) &&
        retirement_file_exact_or_absent(dir, GUARD_PREMIGRATE,
            journal->premigrate_bytes, journal->premigrate_sha) &&
        retirement_file_exact_or_absent(dir, GUARD_B_PRIMARY,
            journal->b_primary_bytes, journal->b_primary_sha);
}

static int unlink_retirement_file(int dir, const char *name, uint64_t bytes,
                                  const char *sha, enum guard_test_fault fault) {
    int present = known_file_present(dir, name);
    if (present < 0 || (present == 1 &&
        (bytes == 0 || strcmp(sha, "NONE") == 0 ||
         !retirement_regular_exact_at(dir, name, bytes, sha))))
        return GUARD_PUBLISH_FAILED;
    if (present == 1 && (unlinkat(dir, name, 0) != 0 || fsync(dir) != 0))
        return GUARD_PUBLISH_INDETERMINATE;
    return TEST_FAULT(fault) ? GUARD_PUBLISH_INDETERMINATE
                             : GUARD_PUBLISH_COMMITTED;
}

static int retiring_journal_orphan_exact(const guard_journal *journal) {
    return journal->phase == GUARD_PHASE_RETIRING &&
        journal->generation > journal->manifest_generation &&
        lower_hex_64(journal->retirement_sha) && strcmp(journal->role, "A") == 0 &&
        lower_hex_64(journal->installed_sha) && journal->version_code != 0 &&
        journal->schema != 0 && journal->deadline_ms == 0 &&
        strcmp(journal->error, "NONE") == 0 && terminal_outcome(journal->outcome) &&
        journal->pm_settled && !journal->pm_spawned && journal->pm_pid == 0 &&
        journal->pm_start_ticks == 0;
}

/* Returns 0 when no retirement exists, 1 when EMPTY is durable, 2 at a simulated or
 * indeterminate cleanup cut, and -1 for an unproven RETIRING topology. */
static int reconcile_terminal_retirement_at(int dir) {
    guard_plan plan;
    guard_journal journal;
    int manifest = load_plan_at(dir, GUARD_MANIFEST, &plan);
    int journal_state = load_journal_at(dir, &journal);
    if (manifest < 0 || journal_state < 0) return -1;
    if (manifest == 0) {
        if (journal_state == 0) {
            int owner_exact = retirement_owner_lock_exact(dir);
            int inventory_empty = empty_inventory_safe(dir);
            if (!owner_exact || !inventory_empty) return 0;
            if (fsync(dir) != 0) return -1;
            return TEST_FAULT(GUARD_TEST_FAULT_RETIRE_AFTER_JOURNAL) ? 2 : 1;
        }
        if (!retiring_journal_orphan_exact(&journal) ||
            !retirement_owner_lock_exact(dir) ||
            !retirement_namespace_exact(dir, 0)) return -1;
        if (unlinkat(dir, GUARD_JOURNAL, 0) != 0) return -1;
        if (TEST_FAULT(GUARD_TEST_FAULT_RETIRE_JOURNAL_UNLINKED)) return 2;
        if (fsync(dir) != 0) return -1;
        return TEST_FAULT(GUARD_TEST_FAULT_RETIRE_AFTER_JOURNAL) ? 2 : 1;
    }
    if (journal_state != 1 || journal.phase != GUARD_PHASE_RETIRING) return 0;
    if (!plan_boot_is_current(&plan) || !plan.captured || !plan.a.staged || !plan.b.staged ||
        !plans_compatible(&plan) || !journal_matches_plan(&plan, &journal) ||
        !retirement_owner_lock_exact(dir) ||
        !terminal_outcome(journal.outcome) || !retirement_files_exact_or_absent(
            dir, &plan, &journal)) return -1;

    static const char *const names[] = {
        GUARD_A_APK, GUARD_B_APK, GUARD_SETTINGS, GUARD_BASELINE,
        GUARD_PREMIGRATE, GUARD_B_PRIMARY,
    };
    const uint64_t bytes[] = {
        plan.a.bytes, plan.b.bytes, plan.settings_authority_bytes, plan.baseline_bytes,
        journal.premigrate_bytes, journal.b_primary_bytes,
    };
    const char *const hashes[] = {
        plan.a.sha, plan.b.sha, plan.settings_authority_sha, plan.baseline_sha,
        journal.premigrate_sha, journal.b_primary_sha,
    };
    const enum guard_test_fault faults[] = {
        GUARD_TEST_FAULT_RETIRE_AFTER_A, GUARD_TEST_FAULT_RETIRE_AFTER_B,
        GUARD_TEST_FAULT_RETIRE_AFTER_SETTINGS, GUARD_TEST_FAULT_RETIRE_AFTER_BASELINE,
        GUARD_TEST_FAULT_RETIRE_AFTER_PREMIGRATE,
        GUARD_TEST_FAULT_RETIRE_AFTER_B_PRIMARY,
    };
    for (size_t index = 0; index < sizeof names / sizeof names[0]; index++) {
        if (!retirement_files_exact_or_absent(dir, &plan, &journal)) return -1;
        int removed = unlink_retirement_file(
            dir, names[index], bytes[index], hashes[index], faults[index]);
        if (removed != GUARD_PUBLISH_COMMITTED)
            return removed == GUARD_PUBLISH_INDETERMINATE ? 2 : -1;
    }
    if (!retirement_files_exact_or_absent(dir, &plan, &journal) ||
        unlinkat(dir, GUARD_MANIFEST, 0) != 0 || fsync(dir) != 0)
        return -1;
    if (TEST_FAULT(GUARD_TEST_FAULT_RETIRE_AFTER_MANIFEST)) return 2;
    if (!retiring_journal_orphan_exact(&journal) ||
        !retirement_owner_lock_exact(dir) ||
        !retirement_namespace_exact(dir, 0) || unlinkat(dir, GUARD_JOURNAL, 0) != 0)
        return -1;
    if (TEST_FAULT(GUARD_TEST_FAULT_RETIRE_JOURNAL_UNLINKED)) return 2;
    if (fsync(dir) != 0) return -1;
    return TEST_FAULT(GUARD_TEST_FAULT_RETIRE_AFTER_JOURNAL) ? 2 : 1;
}

static int finalized_retirement_preflight(int dir, const guard_plan *plan,
                                          const guard_journal *journal) {
    int rolled_back = strncmp(journal->outcome, "ROLLED_BACK_", 12) == 0;
    int recovery_exact = journal->premigrate_bytes == 0
        ? known_file_present(dir, GUARD_PREMIGRATE) == 0
        : retirement_regular_exact_at(dir, GUARD_PREMIGRATE,
            journal->premigrate_bytes, journal->premigrate_sha);
    recovery_exact = recovery_exact && (journal->b_primary_bytes == 0
        ? known_file_present(dir, GUARD_B_PRIMARY) == 0
        : retirement_regular_exact_at(dir, GUARD_B_PRIMARY,
            journal->b_primary_bytes, journal->b_primary_sha));
    int exact = journal->phase == GUARD_PHASE_FINALIZED && terminal_outcome(journal->outcome) &&
        strcmp(journal->role, "A") == 0 &&
        retirement_owner_lock_exact(dir) && retirement_namespace_exact(dir, 1) &&
        retirement_regular_exact_at(dir, GUARD_A_APK, plan->a.bytes, plan->a.sha) &&
        retirement_regular_exact_at(dir, GUARD_B_APK, plan->b.bytes, plan->b.sha) &&
        retirement_regular_exact_at(dir, GUARD_SETTINGS,
            plan->settings_authority_bytes, plan->settings_authority_sha) &&
        validate_settings_authority_at(dir, plan) == 0 &&
        retirement_regular_exact_at(dir, GUARD_BASELINE, plan->baseline_bytes,
                                    plan->baseline_sha) && recovery_exact &&
        installed_apk_stable_exact(plan->package, &plan->a) == 0 &&
        (rolled_back ? rollback_inventory_exact(plan) == 0
                     : (strcmp(journal->outcome, "CANARY_PASSED") == 0 &&
                        final_recovery_artifacts_exact(plan, journal) == 0 &&
                        live_db_semantic_exact(dir, plan, plan->a.expected_schema, 0) == 0)) &&
        known_file_present(dir, GUARD_REPLACEMENT) == 0 &&
        known_file_present(dir, GUARD_REPLACEMENT_TMP) == 0;
    return exact;
}

static int open_app_helper_parent(void) {
    int dir = open(GUARD_APP_HELPER_PARENT, O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC);
    struct stat st;
    if (dir < 0 || fstat(dir, &st) != 0 || !S_ISDIR(st.st_mode) ||
        (st.st_uid != 0 && st.st_uid != geteuid()) ||
        ((st.st_mode & (S_IWGRP | S_IWOTH)) != 0 && (st.st_mode & S_ISVTX) == 0)) {
        if (dir >= 0) close(dir);
        return -1;
    }
    return dir;
}

static int helper_transaction_lock_namespace_exact(int dir) {
    int duplicate = openat(dir, ".", O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC);
    if (duplicate < 0) return 0;
    DIR *stream = fdopendir(duplicate);
    if (!stream) { close(duplicate); return 0; }
    int exact = 1;
    errno = 0;
    for (struct dirent *entry = readdir(stream); entry; entry = readdir(stream)) {
        if (strcmp(entry->d_name, ".") == 0 || strcmp(entry->d_name, "..") == 0 ||
            strcmp(entry->d_name, HELPER_TRANSACTION_LOCK_PID) == 0) {
            errno = 0;
            continue;
        }
        exact = 0;
        break;
    }
    if (errno != 0) exact = 0;
    closedir(stream);
    return exact;
}

static int helper_transaction_lock_dir_exact(int parent, int dir, struct stat *snapshot) {
    struct stat held, named;
    int exact = fstat(dir, &held) == 0 &&
        fstatat(parent, HELPER_TRANSACTION_LOCK_NAME, &named, AT_SYMLINK_NOFOLLOW) == 0 &&
        S_ISDIR(held.st_mode) && held.st_uid == geteuid() && held.st_gid == getegid() &&
        (held.st_mode & (S_IWGRP | S_IWOTH)) == 0 &&
        stat_identity_unchanged_strict(&held, &named);
    if (exact && snapshot) *snapshot = held;
    return exact;
}

static int helper_transaction_lock_pid_exact(int dir, pid_t *holder,
                                             struct stat *snapshot) {
    int fd = openat(dir, HELPER_TRANSACTION_LOCK_PID,
        O_RDONLY | O_NOFOLLOW | O_CLOEXEC);
    struct stat before, after, named;
    char record[32];
    int exact = fd >= 0 && fstat(fd, &before) == 0 && S_ISREG(before.st_mode) &&
        before.st_uid == geteuid() && before.st_gid == getegid() && before.st_nlink == 1 &&
        (before.st_mode & (S_IWGRP | S_IWOTH)) == 0 && before.st_size >= 2 &&
        before.st_size < (off_t)sizeof record;
    size_t used = 0;
    while (exact && used < (size_t)before.st_size) {
        ssize_t count = read(fd, record + used, (size_t)before.st_size - used);
        if (count < 0 && errno == EINTR) continue;
        if (count <= 0) { exact = 0; break; }
        used += (size_t)count;
    }
    unsigned char extra;
    ssize_t extra_count = -1;
    if (exact) {
        do extra_count = read(fd, &extra, 1); while (extra_count < 0 && errno == EINTR);
        exact = extra_count == 0 && fstat(fd, &after) == 0 &&
            fstatat(dir, HELPER_TRANSACTION_LOCK_PID, &named, AT_SYMLINK_NOFOLLOW) == 0 &&
            stat_identity_unchanged_strict(&before, &after) &&
            stat_identity_unchanged_strict(&before, &named);
    }
    if (fd >= 0) close(fd);
    if (!exact || used < 2 || record[used - 1] != '\n') return -1;
    record[used - 1] = '\0';
    uint64_t parsed = 0;
    if (parse_u64(record, 2, INT_MAX, &parsed) != 0) return -1;
    if (holder) *holder = (pid_t)parsed;
    if (snapshot) *snapshot = before;
    return 0;
}

static int open_helper_transaction_lock_parent(void) {
    int parent = open(HELPER_TRANSACTION_LOCK_PARENT,
        O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC);
    struct stat st;
    int exact = parent >= 0 && fstat(parent, &st) == 0 && S_ISDIR(st.st_mode) &&
        st.st_uid == geteuid() && st.st_gid == getegid() &&
        ((st.st_mode & (S_IWGRP | S_IWOTH)) == 0 || (st.st_mode & S_ISVTX) != 0);
    if (!exact && parent >= 0) { close(parent); parent = -1; }
    return parent;
}

static int write_helper_transaction_lock_pid(int dir, struct stat *snapshot) {
    int fd = openat(dir, HELPER_TRANSACTION_LOCK_PID,
        O_WRONLY | O_CREAT | O_EXCL | O_NOFOLLOW | O_CLOEXEC, 0600);
    char record[32];
    int length = snprintf(record, sizeof record, "%ld\n", (long)getpid());
    int exact = fd >= 0 && length > 1 && (size_t)length < sizeof record &&
        fchmod(fd, 0600) == 0;
    size_t used = 0;
    while (exact && used < (size_t)length) {
        ssize_t count = write(fd, record + used, (size_t)length - used);
        if (count < 0 && errno == EINTR) continue;
        if (count <= 0) { exact = 0; break; }
        used += (size_t)count;
    }
    if (fd >= 0 && close(fd) != 0) exact = 0;
    pid_t holder = -1;
    return exact && helper_transaction_lock_pid_exact(dir, &holder, snapshot) == 0 &&
        holder == getpid() ? 0 : -1;
}

static void close_helper_transaction_lock(helper_transaction_lock *lock) {
    if (lock->dir >= 0) close(lock->dir);
    if (lock->parent >= 0) close(lock->parent);
    lock->dir = -1;
    lock->parent = -1;
}

static int release_helper_transaction_lock(helper_transaction_lock *lock) {
    struct stat dir_stat, pid_stat;
    pid_t holder = -1;
    int exact = lock && lock->parent >= 0 && lock->dir >= 0 &&
        helper_transaction_lock_dir_exact(lock->parent, lock->dir, &dir_stat) &&
        stat_identity_unchanged_strict(&lock->dir_stat, &dir_stat) &&
        helper_transaction_lock_namespace_exact(lock->dir) &&
        helper_transaction_lock_pid_exact(lock->dir, &holder, &pid_stat) == 0 &&
        holder == getpid() && stat_identity_unchanged_strict(&lock->pid_stat, &pid_stat);
    int removed = exact && unlinkat(lock->dir, HELPER_TRANSACTION_LOCK_PID, 0) == 0 &&
        unlinkat(lock->parent, HELPER_TRANSACTION_LOCK_NAME, AT_REMOVEDIR) == 0;
    close_helper_transaction_lock(lock);
    return removed ? 0 : -1;
}

static int create_helper_transaction_lock(int parent, helper_transaction_lock *lock) {
    if (mkdirat(parent, HELPER_TRANSACTION_LOCK_NAME, 0700) != 0) return -1;
    int dir = openat(parent, HELPER_TRANSACTION_LOCK_NAME,
        O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC);
    helper_transaction_lock created = { .parent = parent, .dir = dir };
    int exact = dir >= 0 && helper_transaction_lock_dir_exact(
        parent, dir, NULL) &&
        write_helper_transaction_lock_pid(dir, &created.pid_stat) == 0 &&
        helper_transaction_lock_dir_exact(parent, dir, &created.dir_stat) &&
        helper_transaction_lock_namespace_exact(dir);
    if (!exact) {
        if (dir >= 0) {
            pid_t holder = -1;
            struct stat ignored;
            if (helper_transaction_lock_pid_exact(dir, &holder, &ignored) == 0 &&
                holder == getpid()) (void)unlinkat(dir, HELPER_TRANSACTION_LOCK_PID, 0);
            close(dir);
        }
        (void)unlinkat(parent, HELPER_TRANSACTION_LOCK_NAME, AT_REMOVEDIR);
        return -1;
    }
    *lock = created;
    return 0;
}

static int acquire_helper_transaction_lock(helper_transaction_lock *lock) {
    memset(lock, 0, sizeof *lock);
    lock->parent = -1;
    lock->dir = -1;
    int parent = open_helper_transaction_lock_parent();
    if (parent < 0) return HELPER_TRANSACTION_LOCK_HOLD;
    if (create_helper_transaction_lock(parent, lock) == 0)
        return HELPER_TRANSACTION_LOCK_ACQUIRED;
    if (errno != EEXIST) {
        close(parent);
        return HELPER_TRANSACTION_LOCK_HOLD;
    }

    int dir = openat(parent, HELPER_TRANSACTION_LOCK_NAME,
        O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC);
    struct stat dir_before, pid_before, dir_after, pid_after;
    pid_t holder = -1, rebound_holder = -1;
    int exact = dir >= 0 && helper_transaction_lock_dir_exact(parent, dir, &dir_before) &&
        helper_transaction_lock_namespace_exact(dir) &&
        helper_transaction_lock_pid_exact(dir, &holder, &pid_before) == 0;
    if (!exact) {
        if (dir >= 0) close(dir);
        close(parent);
        return HELPER_TRANSACTION_LOCK_HOLD;
    }
    errno = 0;
    if (kill(holder, 0) == 0 || errno == EPERM) {
        close(dir);
        close(parent);
        return HELPER_TRANSACTION_LOCK_BUSY;
    }
    if (errno != ESRCH) {
        close(dir);
        close(parent);
        return HELPER_TRANSACTION_LOCK_HOLD;
    }
    errno = 0;
    if (kill(holder, 0) == 0 || errno != ESRCH ||
        !helper_transaction_lock_dir_exact(parent, dir, &dir_after) ||
        !stat_identity_unchanged_strict(&dir_before, &dir_after) ||
        !helper_transaction_lock_namespace_exact(dir) ||
        helper_transaction_lock_pid_exact(dir, &rebound_holder, &pid_after) != 0 ||
        holder != rebound_holder || !stat_identity_unchanged_strict(&pid_before, &pid_after) ||
        unlinkat(dir, HELPER_TRANSACTION_LOCK_PID, 0) != 0 ||
        unlinkat(parent, HELPER_TRANSACTION_LOCK_NAME, AT_REMOVEDIR) != 0) {
        close(dir);
        close(parent);
        return HELPER_TRANSACTION_LOCK_HOLD;
    }
    close(dir);
    if (create_helper_transaction_lock(parent, lock) != 0) {
        close(parent);
        return HELPER_TRANSACTION_LOCK_HOLD;
    }
    return HELPER_TRANSACTION_LOCK_ACQUIRED;
}

static int app_helper_identity_at(int dir, const char *name, uint64_t *bytes,
                                  char sha[65], uint64_t *dev, uint64_t *ino) {
    int fd = openat(dir, name, O_RDONLY | O_NOFOLLOW | O_CLOEXEC);
    struct stat st;
    int exact = fd >= 0 && fstat(fd, &st) == 0 && S_ISREG(st.st_mode) &&
        st.st_uid == geteuid() && st.st_nlink == 1 && (st.st_mode & 0777) == 0700 &&
        st.st_size > 0 && (uint64_t)st.st_size <= GUARD_REPLACEMENT_MAX_BINARY_BYTES;
    if (exact) {
        *bytes = (uint64_t)st.st_size;
        *dev = (uint64_t)st.st_dev;
        *ino = (uint64_t)st.st_ino;
        exact = hapaneld_sha256_fd(fd, *bytes, sha) == 0;
    }
    if (fd >= 0) close(fd);
    return exact ? 0 : -1;
}

static int app_helper_identity_matches(int dir, const char *name, uint64_t bytes,
                                       const char *sha, uint64_t dev, uint64_t ino) {
    uint64_t actual_bytes = 0, actual_dev = 0, actual_ino = 0;
    char actual_sha[65];
    return app_helper_identity_at(dir, name, &actual_bytes, actual_sha,
                                  &actual_dev, &actual_ino) == 0 &&
        actual_bytes == bytes && actual_dev == dev && actual_ino == ino &&
        strcmp(actual_sha, sha) == 0 ? 0 : -1;
}

/* Report bytes from the fixed live name only when this worker is executing that exact inode. The
 * second metadata/name check prevents a concurrent rename or in-place rewrite from turning the
 * opened file into a stale authority while it is being hashed. */
static int app_helper_self_identity(uint64_t *bytes, char sha[65]) {
    int parent = open_app_helper_parent();
    int fd = parent >= 0
        ? openat(parent, GUARD_APP_HELPER_LIVE, O_RDONLY | O_NOFOLLOW | O_CLOEXEC) : -1;
    struct stat before, after, self, named;
    int exact = bytes && sha && lower_hex_64(helper_build_id()) && fd >= 0 &&
        fstat(fd, &before) == 0 && stat("/proc/self/exe", &self) == 0 &&
        S_ISREG(before.st_mode) && before.st_uid == geteuid() && before.st_gid == getegid() &&
        before.st_nlink == 1 &&
        (before.st_mode & 0777) == 0700 && before.st_size > 0 &&
        (uint64_t)before.st_size <= GUARD_REPLACEMENT_MAX_BINARY_BYTES &&
        before.st_dev == self.st_dev && before.st_ino == self.st_ino;
    if (exact) {
        *bytes = (uint64_t)before.st_size;
        exact = hapaneld_sha256_fd(fd, *bytes, sha) == 0 && fstat(fd, &after) == 0 &&
            fstatat(parent, GUARD_APP_HELPER_LIVE, &named, AT_SYMLINK_NOFOLLOW) == 0 &&
            S_ISREG(named.st_mode) && stat_identity_unchanged_strict(&before, &after) &&
            stat_identity_unchanged_strict(&before, &named);
    }
    if (fd >= 0) close(fd);
    if (parent >= 0) close(parent);
    return exact ? 0 : -1;
}

void cmd_guardself(conn_ctx *ctx, const char *args) {
    if (*args) {
        error_reply(ctx, "ARGS", "self");
        return;
    }
    uint64_t bytes = 0;
    char sha[65];
    if (app_helper_self_identity(&bytes, sha) != 0) {
        error_reply(ctx, "HOLD", "self");
        return;
    }
    char line[192];
    snprintf(line, sizeof line, "OK GUARDSELF 1 %" PRIu64 " %s %s\n",
             bytes, sha, helper_build_id());
    reply(ctx->fd, line);
}

static int seal_app_helper_stage_at(int parent, uint64_t bytes, const char *sha,
                                    uint64_t dev, uint64_t ino) {
    int fd = openat(parent, GUARD_APP_HELPER_STAGE,
        O_RDWR | O_NOFOLLOW | O_CLOEXEC);
    struct stat st;
    int exact = fd >= 0 && fstat(fd, &st) == 0 && S_ISREG(st.st_mode) &&
        st.st_uid == geteuid() && st.st_nlink == 1 && (st.st_mode & 0777) == 0700 &&
        (uint64_t)st.st_size == bytes && (uint64_t)st.st_dev == dev &&
        (uint64_t)st.st_ino == ino && !TEST_FAULT(GUARD_TEST_FAULT_REPLACEMENT_STAGE_FILE_SYNC) &&
        fsync(fd) == 0;
    if (fd >= 0) close(fd);
    if (!exact || TEST_FAULT(GUARD_TEST_FAULT_REPLACEMENT_STAGE_DIR_SYNC) || fsync(parent) != 0)
        return -1;
    return app_helper_identity_matches(parent, GUARD_APP_HELPER_STAGE,
        bytes, sha, dev, ino);
}

static int replacement_boot_current(const guard_replacement *replacement) {
    char boot[65];
    return current_boot_nonce(boot) == 0 && strcmp(boot, replacement->boot) == 0;
}

static int replacement_request_topology_exact(const guard_replacement *replacement) {
    int parent = open_app_helper_parent();
    if (parent < 0) return -1;
    int exact = app_helper_identity_matches(parent, GUARD_APP_HELPER_LIVE,
            replacement->old_bytes, replacement->old_sha,
            replacement->old_dev, replacement->old_ino) == 0 &&
        app_helper_identity_matches(parent, GUARD_APP_HELPER_STAGE,
            replacement->new_bytes, replacement->new_sha,
            replacement->new_dev, replacement->new_ino) == 0 &&
        known_file_present(parent, GUARD_APP_HELPER_PREVIOUS) == 0;
    close(parent);
    return exact ? 0 : -1;
}

static int replacement_old_caller_exact(const guard_replacement *replacement) {
    if (replacement_request_topology_exact(replacement) != 0) return -1;
#ifndef HAPANELD_TEST_SKIP_SELF_BINDING
    int parent = open_app_helper_parent();
    struct stat self_st, live_st;
    int exact = parent >= 0 && stat("/proc/self/exe", &self_st) == 0 &&
        fstatat(parent, GUARD_APP_HELPER_LIVE, &live_st, AT_SYMLINK_NOFOLLOW) == 0 &&
        self_st.st_dev == live_st.st_dev && self_st.st_ino == live_st.st_ino;
    if (parent >= 0) close(parent);
    return exact ? 0 : -1;
#else
    return 0;
#endif
}

static int acquire_owner_lock(int dir) {
    if (guard_owner_fd >= 0) return 0;
    int fd = openat(dir, GUARD_OWNER_LOCK,
        O_RDWR | O_CREAT | O_NOFOLLOW | O_CLOEXEC, 0600);
    if (fd < 0) return -1;
    struct stat st;
    if (fstat(fd, &st) != 0 || !S_ISREG(st.st_mode) || st.st_uid != geteuid() ||
        st.st_nlink != 1 || fchmod(fd, 0600) != 0 || flock(fd, LOCK_EX | LOCK_NB) != 0) {
        close(fd);
        return -1;
    }
    guard_owner_fd = fd;
    return 0;
}

static int previous_helper_bytes_exact(int parent, const guard_replacement *replacement) {
    uint64_t bytes = 0, dev = 0, ino = 0;
    char sha[65];
    return app_helper_identity_at(parent, GUARD_APP_HELPER_PREVIOUS,
        &bytes, sha, &dev, &ino) == 0 && bytes == replacement->old_bytes &&
        strcmp(sha, replacement->old_sha) == 0 ? 0 : -1;
}

int guard_maintenance_replacement_startup_reconcile_app(char nonce[65]) {
    if (!nonce) return -1;
    nonce[0] = '\0';
    pthread_mutex_lock(&guard_lock);
    int dir = open_guard_dir();
    if (dir < 0 || acquire_owner_lock(dir) != 0) {
        if (dir >= 0) close(dir);
        pthread_mutex_unlock(&guard_lock);
        return -1;
    }
    guard_replacement replacement;
    int loaded = load_replacement_reconciled(dir, &replacement);
    if (loaded == 0) {
        close(dir);
        pthread_mutex_unlock(&guard_lock);
        return 0;
    }
    int parent = open_app_helper_parent();
    int previous = parent >= 0 ? known_file_present(parent, GUARD_APP_HELPER_PREVIOUS) : -1;
    int stage = parent >= 0 ? known_file_present(parent, GUARD_APP_HELPER_STAGE) : -1;
    int old_live = loaded == 1 && parent >= 0 && replacement_boot_current(&replacement) &&
        app_helper_identity_matches(parent, GUARD_APP_HELPER_LIVE,
            replacement.old_bytes, replacement.old_sha,
            replacement.old_dev, replacement.old_ino) == 0;
    int new_live = loaded == 1 && parent >= 0 && replacement_boot_current(&replacement) &&
        app_helper_identity_matches(parent, GUARD_APP_HELPER_LIVE,
            replacement.new_bytes, replacement.new_sha,
            replacement.new_dev, replacement.new_ino) == 0;
    int exact_stage = loaded == 1 && stage == 1 &&
        app_helper_identity_matches(parent, GUARD_APP_HELPER_STAGE,
            replacement.new_bytes, replacement.new_sha,
            replacement.new_dev, replacement.new_ino) == 0;
    int exact_previous = loaded == 1 && previous == 1 &&
        previous_helper_bytes_exact(parent, &replacement) == 0;
    int result = -1;
    if (loaded == 1 && replacement_boot_current(&replacement) &&
        (replacement.phase == GUARD_REPLACEMENT_REQUESTED ||
         replacement.phase == GUARD_REPLACEMENT_GRANTED) && old_live && exact_stage &&
        (previous == 0 || exact_previous)) {
        int cleaned = previous == 0 ||
            (unlinkat(parent, GUARD_APP_HELPER_PREVIOUS, 0) == 0 && fsync(parent) == 0);
        if (cleaned && unlinkat(dir, GUARD_REPLACEMENT, 0) == 0 && fsync(dir) == 0) result = 0;
    } else if (loaded == 1 && replacement_boot_current(&replacement) &&
        replacement.phase == GUARD_REPLACEMENT_BACKUP_DURABLE && old_live &&
        exact_stage && (previous == 0 || exact_previous)) {
        int cleaned = previous == 0 ||
            (unlinkat(parent, GUARD_APP_HELPER_PREVIOUS, 0) == 0 && fsync(parent) == 0);
        if (cleaned && !TEST_FAULT(GUARD_TEST_FAULT_REPLACEMENT_ABORT_AFTER_PREVIOUS) &&
            unlinkat(dir, GUARD_REPLACEMENT, 0) == 0 && fsync(dir) == 0) result = 0;
    } else if (loaded == 1 && replacement_boot_current(&replacement) &&
        (replacement.phase == GUARD_REPLACEMENT_BACKUP_DURABLE ||
         replacement.phase == GUARD_REPLACEMENT_SWAPPED) && new_live && stage == 0 &&
        exact_previous) {
        int durable = 1;
        if (replacement.phase == GUARD_REPLACEMENT_BACKUP_DURABLE) {
            replacement.phase = GUARD_REPLACEMENT_SWAPPED;
            replacement.generation++;
            durable = store_replacement_at(dir, &replacement) == GUARD_PUBLISH_COMMITTED;
        }
        if (replacement.phase == GUARD_REPLACEMENT_SWAPPED && durable) {
            snprintf(nonce, 65, "%s", replacement.nonce);
            result = 1;
        }
    } else if (loaded == 1 && replacement_boot_current(&replacement) &&
        replacement.phase == GUARD_REPLACEMENT_SWAPPED && new_live && stage == 0 && previous == 0 &&
        unlinkat(dir, GUARD_REPLACEMENT, 0) == 0 && fsync(dir) == 0) {
        result = 0;
    }
    if (parent >= 0) close(parent);
    close(dir);
    pthread_mutex_unlock(&guard_lock);
    return result;
}

int guard_maintenance_init(void) {
    pthread_mutex_lock(&guard_lock);
    int dir = open_guard_dir();
    if (dir < 0 || acquire_owner_lock(dir) != 0) {
        if (dir >= 0) close(dir);
        pthread_mutex_unlock(&guard_lock);
        return -1;
    }
    guard_plan plan;
    guard_journal journal;
    int retirement = reconcile_terminal_retirement_at(dir);
    int manifest = retirement == 0
        ? load_manifest_reconciled(dir, &plan, &journal, 1)
        : (retirement == 1 ? 0 : -1);
    int draft = manifest == 0 ? load_draft_reconciled(dir, &plan) : 0;
    guard_capture capture;
    int capture_state = manifest == 0 && draft == 1 ? load_capture_at(dir, &capture) : 0;
    int safe = retirement >= 0 && retirement != 2 && manifest >= 0 && draft >= 0 &&
        capture_state >= 0 && (capture_state != 1 || capture_matches_draft(&capture, &plan)) &&
        (manifest == 1 || draft == 1 ||
         (retirement_owner_lock_exact(dir) && empty_inventory_safe(dir)));
    guard_initialized = safe;
    close(dir);
    pthread_mutex_unlock(&guard_lock);
    return safe ? 0 : -1;
}

int guard_maintenance_replacement_safe(void) {
    if (guard_maintenance_init() != 0) return 1;
    pthread_mutex_lock(&guard_lock);
    int dir = open_guard_dir();
    int safe = dir >= 0 && replacement_namespace_empty(dir);
    if (dir >= 0) close(dir);
    pthread_mutex_unlock(&guard_lock);
    return safe ? 0 : 1;
}

static void cmd_guardretire_app(conn_ctx *ctx, const char *args) {
    char storage[513], *tokens[4];
    int count = split_tokens(args, storage, tokens, 4);
    if (count != 4 || strcmp(tokens[0], "APP") != 0 || !lower_hex_64(tokens[1]) ||
        !lower_hex_64(tokens[2]) || !lower_hex_64(tokens[3])) {
        error_reply(ctx, "ARGS", "retire");
        return;
    }
    if (pthread_mutex_trylock(&package_gate) != 0) {
        error_reply(ctx, "BUSY", "replacement");
        return;
    }
    helper_transaction_lock transaction_lock;
    int transaction = acquire_helper_transaction_lock(&transaction_lock);
    if (transaction != HELPER_TRANSACTION_LOCK_ACQUIRED) {
        pthread_mutex_unlock(&package_gate);
        error_reply(ctx, transaction == HELPER_TRANSACTION_LOCK_BUSY ? "BUSY" : "HOLD",
                    "replacement");
        return;
    }
    if (!guard_admission_ready(ctx)) {
        (void)release_helper_transaction_lock(&transaction_lock);
        pthread_mutex_unlock(&package_gate);
        return;
    }
    pthread_mutex_lock(&guard_lock);
    int dir = open_guard_dir();
    int safe = dir >= 0 && replacement_namespace_empty(dir);
    if (!safe) {
        if (dir >= 0) close(dir);
        pthread_mutex_unlock(&guard_lock);
        (void)release_helper_transaction_lock(&transaction_lock);
        pthread_mutex_unlock(&package_gate);
        error_reply(ctx, "ARMED", "replacement");
        return;
    }
    int parent = open_app_helper_parent();
    guard_replacement replacement;
    memset(&replacement, 0, sizeof replacement);
    int valid = parent >= 0 && current_boot_nonce(replacement.boot) == 0;
    replacement.phase = GUARD_REPLACEMENT_REQUESTED;
    replacement.generation = 1;
    snprintf(replacement.nonce, sizeof replacement.nonce, "%s", tokens[1]);
    snprintf(replacement.new_build, sizeof replacement.new_build, "%s", tokens[3]);
    if (valid) valid = app_helper_identity_at(parent, GUARD_APP_HELPER_LIVE,
        &replacement.old_bytes, replacement.old_sha,
        &replacement.old_dev, &replacement.old_ino) == 0;
    if (valid) valid = app_helper_identity_at(parent, GUARD_APP_HELPER_STAGE,
        &replacement.new_bytes, replacement.new_sha,
        &replacement.new_dev, &replacement.new_ino) == 0 &&
        strcmp(replacement.new_sha, tokens[2]) == 0 &&
        strcmp(replacement.old_sha, replacement.new_sha) != 0;
    if (valid) valid = seal_app_helper_stage_at(parent, replacement.new_bytes,
        replacement.new_sha, replacement.new_dev, replacement.new_ino) == 0;
    if (parent >= 0) close(parent);
    int published = valid ? store_replacement_at(dir, &replacement) : GUARD_PUBLISH_FAILED;
    if (dir >= 0) close(dir);
    if (published != GUARD_PUBLISH_COMMITTED) {
        pthread_mutex_unlock(&guard_lock);
        (void)release_helper_transaction_lock(&transaction_lock);
        pthread_mutex_unlock(&package_gate);
        error_reply(ctx, published == GUARD_PUBLISH_INDETERMINATE ? "INDETERMINATE" : "HOLD",
                    "replacement");
        return;
    }
    (void)release_helper_transaction_lock(&transaction_lock);
    guard_initialized = 0;
    /* Keep guard_lock held through process death: the durable fence closes admission before reply. */
    (void)reply(ctx->fd, "OK GUARDRETIRE 1 REQUESTED\n");
    _exit(GUARD_REPLACEMENT_EXIT);
}

static void cmd_guardretire_terminal(conn_ctx *ctx, const char *args) {
    char storage[513], *tokens[4];
    int count = split_tokens(args, storage, tokens, 4);
    uint64_t generation = 0;
    if (count != 4 || strcmp(tokens[0], "TERMINAL") != 0 ||
        !lower_hex_64(tokens[1]) ||
        parse_u64(tokens[2], 1, UINT64_MAX - 1, &generation) != 0 ||
        !lower_hex_64(tokens[3])) {
        error_reply(ctx, "ARGS", "retire");
        return;
    }
    if (!guard_initialized) {
        error_reply(ctx, "HOLD", "retirement");
        return;
    }
    if (pthread_mutex_trylock(&package_gate) != 0) {
        error_reply(ctx, "HOLD", "retirement");
        return;
    }
    pthread_mutex_lock(&guard_lock);
    int dir = open_guard_dir();
    guard_plan plan;
    guard_journal journal;
    int raw_manifest = dir >= 0 ? load_plan_at(dir, GUARD_MANIFEST, &plan) : -1;
    int raw_journal = dir >= 0 ? load_journal_at(dir, &journal) : -1;
    if (raw_journal == 1 && journal.phase == GUARD_PHASE_RETIRING) {
        int evidence_match = strcmp(journal.session, tokens[1]) == 0 &&
            strcmp(journal.retirement_sha, tokens[3]) == 0;
        if (dir >= 0) close(dir);
        pthread_mutex_unlock(&guard_lock);
        pthread_mutex_unlock(&package_gate);
        if (!evidence_match) error_reply(ctx, "MISMATCH", "evidence");
        else error_reply(ctx, "STALE", "retirement");
        return;
    }
    int loaded = raw_manifest >= 0 && raw_journal >= 0
        ? load_manifest_reconciled(dir, &plan, &journal, 0) : -1;
    if (loaded != 1) {
        if (dir >= 0) close(dir);
        pthread_mutex_unlock(&guard_lock);
        pthread_mutex_unlock(&package_gate);
        error_reply(ctx, loaded == 0 ? "STATE" : "HOLD", "retirement");
        return;
    }
    if (strcmp(plan.session, tokens[1]) != 0) {
        close(dir);
        pthread_mutex_unlock(&guard_lock);
        pthread_mutex_unlock(&package_gate);
        error_reply(ctx, "MISMATCH", "evidence");
        return;
    }
    if (!guard_caller_owns_plan(ctx, &plan)) {
        close(dir);
        pthread_mutex_unlock(&guard_lock);
        pthread_mutex_unlock(&package_gate);
        error_reply(ctx, "STATE", "package");
        return;
    }
    if (journal.generation != generation) {
        close(dir);
        pthread_mutex_unlock(&guard_lock);
        pthread_mutex_unlock(&package_gate);
        error_reply(ctx, "STALE", "retirement");
        return;
    }
    if (journal.phase != GUARD_PHASE_FINALIZED) {
        int hold = journal.phase == GUARD_PHASE_AMBIGUOUS;
        close(dir);
        pthread_mutex_unlock(&guard_lock);
        pthread_mutex_unlock(&package_gate);
        error_reply(ctx, hold ? "HOLD" : "STATE", "retirement");
        return;
    }
    char evidence_sha[65];
    int exact = finalized_retirement_preflight(dir, &plan, &journal) &&
        guard_evidence_sha(&plan, &journal, evidence_sha) == 0;
    if (!exact) {
        close(dir);
        pthread_mutex_unlock(&guard_lock);
        pthread_mutex_unlock(&package_gate);
        error_reply(ctx, "HOLD", "retirement");
        return;
    }
    if (strcmp(evidence_sha, tokens[3]) != 0) {
        close(dir);
        pthread_mutex_unlock(&guard_lock);
        pthread_mutex_unlock(&package_gate);
        error_reply(ctx, "MISMATCH", "evidence");
        return;
    }
    journal.generation++;
    journal.phase = GUARD_PHASE_RETIRING;
    snprintf(journal.retirement_sha, sizeof journal.retirement_sha, "%s", evidence_sha);
    int published = store_journal_at(dir, &journal);
    if (published != GUARD_PUBLISH_COMMITTED) {
        close(dir);
        pthread_mutex_unlock(&guard_lock);
        pthread_mutex_unlock(&package_gate);
        error_reply(ctx, "INDETERMINATE", "retirement");
        return;
    }
    int retired = reconcile_terminal_retirement_at(dir);
    close(dir);
    pthread_mutex_unlock(&guard_lock);
    pthread_mutex_unlock(&package_gate);
    if (retired != 1) {
        error_reply(ctx, "INDETERMINATE", "retirement");
        return;
    }
    char reply_line[160];
    snprintf(reply_line, sizeof reply_line, "OK GUARDRETIRE %" PRIu64 " EMPTY\n",
             generation + 1);
    reply(ctx->fd, reply_line);
}

void cmd_guardretire(conn_ctx *ctx, const char *args) {
    if (strncmp(args, "TERMINAL", 8) == 0 &&
        (args[8] == '\0' || args[8] == ' ' || args[8] == '\t'))
        cmd_guardretire_terminal(ctx, args);
    else
        cmd_guardretire_app(ctx, args);
}

int guard_maintenance_replacement_parent_grant(char nonce[65]) {
    if (!guard_maintenance_supervisor_authoritative() || !nonce) return GUARD_REPLACEMENT_HOLD;
    pthread_mutex_lock(&guard_lock);
    int dir = open_guard_dir();
    guard_replacement replacement;
    int loaded = dir >= 0 ? load_replacement_reconciled(dir, &replacement) : -1;
    int valid = loaded == 1 && replacement.phase == GUARD_REPLACEMENT_REQUESTED &&
        replacement_boot_current(&replacement) && replacement_old_caller_exact(&replacement) == 0;
    int stored = GUARD_PUBLISH_FAILED;
    if (valid) {
        replacement.phase = GUARD_REPLACEMENT_GRANTED;
        replacement.generation++;
        stored = store_replacement_at(dir, &replacement);
    }
    if (stored == GUARD_PUBLISH_COMMITTED) snprintf(nonce, 65, "%s", replacement.nonce);
    if (dir >= 0) close(dir);
    pthread_mutex_unlock(&guard_lock);
    return stored == GUARD_PUBLISH_COMMITTED ? GUARD_REPLACEMENT_READY : GUARD_REPLACEMENT_HOLD;
}

int guard_maintenance_replacement_export_lease(void) {
    if (!guard_maintenance_supervisor_authoritative() || guard_owner_fd < 0) return -1;
    struct stat owner, exported;
    if (fstat(guard_owner_fd, &owner) != 0 || !S_ISREG(owner.st_mode) ||
        owner.st_uid != geteuid() || owner.st_nlink != 1 ||
        dup2(guard_owner_fd, GUARD_REPLACEMENT_LEASE_FD) < 0 ||
        fstat(GUARD_REPLACEMENT_LEASE_FD, &exported) != 0 ||
        owner.st_dev != exported.st_dev || owner.st_ino != exported.st_ino) return -1;
    int flags = fcntl(GUARD_REPLACEMENT_LEASE_FD, F_GETFD, 0);
    return flags >= 0 && fcntl(GUARD_REPLACEMENT_LEASE_FD, F_SETFD,
        flags & ~FD_CLOEXEC) == 0 ? 0 : -1;
}

int guard_maintenance_replacement_parent_abort(const char nonce[65]) {
    if (!guard_maintenance_supervisor_authoritative() || !nonce || !lower_hex_64(nonce)) return -1;
    pthread_mutex_lock(&guard_lock);
    int dir = open_guard_dir();
    guard_replacement replacement;
    int loaded = dir >= 0 ? load_replacement_reconciled(dir, &replacement) : -1;
    int safe = loaded == 1 && strcmp(replacement.nonce, nonce) == 0 &&
        (replacement.phase == GUARD_REPLACEMENT_REQUESTED ||
         replacement.phase == GUARD_REPLACEMENT_GRANTED) &&
        replacement_boot_current(&replacement) && replacement_old_caller_exact(&replacement) == 0;
    int removed = safe && unlinkat(dir, GUARD_REPLACEMENT, 0) == 0 && fsync(dir) == 0;
    if (dir >= 0) close(dir);
    close(GUARD_REPLACEMENT_LEASE_FD);
    pthread_mutex_unlock(&guard_lock);
    return removed ? 0 : -1;
}

static int adopt_replacement_lease(const char nonce[65], guard_replacement *replacement,
                                   int *guard_dir) {
#ifndef HAPANELD_TEST_SKIP_SELF_BINDING
    if (geteuid() != 0) return -1;
#endif
    if (!nonce || !lower_hex_64(nonce)) return -1;
    int dir = open_guard_dir();
    int owner = dir >= 0 ? openat(dir, GUARD_OWNER_LOCK,
        O_RDWR | O_NOFOLLOW | O_CLOEXEC) : -1;
    struct stat lease_st, owner_st;
    int valid = dir >= 0 && owner >= 0 &&
        fstat(GUARD_REPLACEMENT_LEASE_FD, &lease_st) == 0 &&
        fstat(owner, &owner_st) == 0 && S_ISREG(lease_st.st_mode) &&
        lease_st.st_uid == geteuid() && lease_st.st_nlink == 1 &&
        (lease_st.st_mode & 0777) == 0600 && lease_st.st_dev == owner_st.st_dev &&
        lease_st.st_ino == owner_st.st_ino &&
        flock(GUARD_REPLACEMENT_LEASE_FD, LOCK_EX | LOCK_NB) == 0 &&
        load_replacement_reconciled(dir, replacement) == 1 &&
        strcmp(replacement->nonce, nonce) == 0 && replacement_boot_current(replacement);
    if (owner >= 0) close(owner);
    if (!valid) {
        if (dir >= 0) close(dir);
        return -1;
    }
    guard_owner_fd = GUARD_REPLACEMENT_LEASE_FD;
    *guard_dir = dir;
    return 0;
}

static int copy_app_helper_at(int parent, const char *source_name,
                              const char *temporary_name, uint64_t bytes,
                              const char *sha) {
    if (remove_fixed_nondir(parent, temporary_name) != 0) return -1;
    int source = openat(parent, source_name, O_RDONLY | O_NOFOLLOW | O_CLOEXEC);
    int output = openat(parent, temporary_name,
        O_WRONLY | O_CREAT | O_EXCL | O_NOFOLLOW | O_CLOEXEC, 0700);
    uint64_t remaining = bytes;
    hapaneld_sha256 hash;
    hapaneld_sha256_init(&hash);
    unsigned char buffer[65536];
    int copied = source >= 0 && output >= 0;
    while (copied && remaining > 0) {
        size_t wanted = remaining < sizeof buffer ? (size_t)remaining : sizeof buffer;
        ssize_t count = read(source, buffer, wanted);
        if (count < 0 && errno == EINTR) continue;
        if (count <= 0 || write_complete(output, buffer, (size_t)count) != 0) {
            copied = 0;
            break;
        }
        hapaneld_sha256_update(&hash, buffer, (size_t)count);
        remaining -= (uint64_t)count;
    }
    unsigned char digest[32];
    char actual[65];
    hapaneld_sha256_final(&hash, digest);
    hapaneld_sha256_hex(digest, actual);
    int durable = copied && remaining == 0 && strcmp(actual, sha) == 0 &&
        fchmod(output, 0700) == 0 && fsync(output) == 0;
    if (source >= 0) close(source);
    if (output >= 0 && close(output) != 0) durable = 0;
    if (!durable) {
        (void)remove_fixed_nondir(parent, temporary_name);
        return -1;
    }
    return 0;
}

int guard_maintenance_replacement_stage_app(const char nonce[65]) {
    guard_replacement replacement;
    int dir = -1;
    if (adopt_replacement_lease(nonce, &replacement, &dir) != 0 ||
        replacement.phase != GUARD_REPLACEMENT_GRANTED ||
        replacement_request_topology_exact(&replacement) != 0) {
        if (dir >= 0) close(dir);
        return -1;
    }
    int parent = open_app_helper_parent();
    int staged_self = parent >= 0;
#ifndef HAPANELD_TEST_SKIP_SELF_BINDING
    struct stat self_st, staged_st;
    staged_self = staged_self && stat("/proc/self/exe", &self_st) == 0 &&
        fstatat(parent, GUARD_APP_HELPER_STAGE, &staged_st, AT_SYMLINK_NOFOLLOW) == 0 &&
        self_st.st_dev == staged_st.st_dev && self_st.st_ino == staged_st.st_ino;
#endif
    staged_self = staged_self && strcmp(helper_build_id(), replacement.new_build) == 0;
    if (!staged_self || known_file_present(parent, GUARD_APP_HELPER_PREVIOUS) != 0 ||
        copy_app_helper_at(parent, GUARD_APP_HELPER_LIVE,
            GUARD_APP_HELPER_PREVIOUS_TMP, replacement.old_bytes,
            replacement.old_sha) != 0 ||
        renameat(parent, GUARD_APP_HELPER_PREVIOUS_TMP,
                 parent, GUARD_APP_HELPER_PREVIOUS) != 0 || fsync(parent) != 0) {
        if (parent >= 0) close(parent);
        close(dir);
        return -1;
    }
    uint64_t backup_bytes = 0, backup_dev = 0, backup_ino = 0;
    char backup_sha[65];
    int backup_exact = parent >= 0 && app_helper_identity_at(parent,
        GUARD_APP_HELPER_PREVIOUS, &backup_bytes, backup_sha,
        &backup_dev, &backup_ino) == 0 && backup_bytes == replacement.old_bytes &&
        strcmp(backup_sha, replacement.old_sha) == 0;
    if (!staged_self || !backup_exact || TEST_FAULT(GUARD_TEST_FAULT_REPLACEMENT_AFTER_BACKUP)) {
        if (parent >= 0) close(parent);
        close(dir);
        return -1;
    }
    replacement.phase = GUARD_REPLACEMENT_BACKUP_DURABLE;
    replacement.generation++;
    if (store_replacement_at(dir, &replacement) != GUARD_PUBLISH_COMMITTED ||
        renameat(parent, GUARD_APP_HELPER_STAGE, parent, GUARD_APP_HELPER_LIVE) != 0 ||
        fsync(parent) != 0 ||
        app_helper_identity_matches(parent, GUARD_APP_HELPER_LIVE,
            replacement.new_bytes, replacement.new_sha,
            replacement.new_dev, replacement.new_ino) != 0 ||
        TEST_FAULT(GUARD_TEST_FAULT_REPLACEMENT_AFTER_SWAP)) {
        close(parent);
        close(dir);
        return -1;
    }
    replacement.phase = GUARD_REPLACEMENT_SWAPPED;
    replacement.generation++;
    int stored = store_replacement_at(dir, &replacement);
    close(parent);
    close(dir);
    return stored == GUARD_PUBLISH_COMMITTED ? 0 : -1;
}

int guard_maintenance_replacement_supervisor_adopt_app(const char nonce[65]) {
    guard_replacement replacement;
    int dir = -1;
    if (adopt_replacement_lease(nonce, &replacement, &dir) != 0 ||
        replacement.phase != GUARD_REPLACEMENT_SWAPPED ||
        strcmp(helper_build_id(), replacement.new_build) != 0) {
        if (dir >= 0) close(dir);
        return -1;
    }
    int parent = open_app_helper_parent();
    uint64_t previous_bytes = 0, previous_dev = 0, previous_ino = 0;
    char previous_sha[65];
    int exact = parent >= 0 && app_helper_identity_matches(parent, GUARD_APP_HELPER_LIVE,
            replacement.new_bytes, replacement.new_sha,
            replacement.new_dev, replacement.new_ino) == 0 &&
        app_helper_identity_at(parent, GUARD_APP_HELPER_PREVIOUS,
            &previous_bytes, previous_sha, &previous_dev, &previous_ino) == 0 &&
        previous_bytes == replacement.old_bytes && strcmp(previous_sha, replacement.old_sha) == 0;
#ifndef HAPANELD_TEST
    struct stat self_st, live_st;
    exact = exact && stat("/proc/self/exe", &self_st) == 0 &&
        fstatat(parent, GUARD_APP_HELPER_LIVE, &live_st, AT_SYMLINK_NOFOLLOW) == 0 &&
        self_st.st_dev == live_st.st_dev && self_st.st_ino == live_st.st_ino;
#endif
    if (parent >= 0) close(parent);
    if (!exact) {
        close(dir);
        return -1;
    }
    int flags = fcntl(GUARD_REPLACEMENT_LEASE_FD, F_GETFD, 0);
    if (flags < 0 || fcntl(GUARD_REPLACEMENT_LEASE_FD, F_SETFD,
                           flags | FD_CLOEXEC) != 0) {
        close(dir);
        return -1;
    }
    guard_initialized = 0;
    close(dir);
    return 0;
}

int guard_maintenance_replacement_worker_commit_app(const char nonce[65]) {
    if (!guard_maintenance_supervised() || !nonce || !lower_hex_64(nonce)) return -1;
    pthread_mutex_lock(&guard_lock);
    int dir = open_guard_dir();
    guard_replacement replacement;
    int loaded = dir >= 0 ? load_replacement_reconciled(dir, &replacement) : -1;
    int parent = open_app_helper_parent();
    int previous_present = parent >= 0
        ? known_file_present(parent, GUARD_APP_HELPER_PREVIOUS) : -1;
    if (loaded == 0 && previous_present == 0 && dir >= 0 && empty_inventory_safe(dir)) {
        guard_initialized = 1;
        if (parent >= 0) close(parent);
        close(dir);
        pthread_mutex_unlock(&guard_lock);
        return 0;
    }
    if (loaded == 1 && previous_present == 0 && parent >= 0 &&
        replacement.phase == GUARD_REPLACEMENT_SWAPPED &&
        strcmp(replacement.nonce, nonce) == 0 && replacement_boot_current(&replacement) &&
        strcmp(helper_build_id(), replacement.new_build) == 0 &&
        app_helper_identity_matches(parent, GUARD_APP_HELPER_LIVE,
            replacement.new_bytes, replacement.new_sha,
            replacement.new_dev, replacement.new_ino) == 0) {
        int resumed = unlinkat(dir, GUARD_REPLACEMENT, 0) == 0 && fsync(dir) == 0;
        close(parent);
        close(dir);
        guard_initialized = resumed;
        pthread_mutex_unlock(&guard_lock);
        return resumed ? 0 : -1;
    }
    uint64_t previous_bytes = 0, previous_dev = 0, previous_ino = 0;
    char previous_sha[65];
    int exact = loaded == 1 && parent >= 0 &&
        replacement.phase == GUARD_REPLACEMENT_SWAPPED &&
        strcmp(replacement.nonce, nonce) == 0 && replacement_boot_current(&replacement) &&
        strcmp(helper_build_id(), replacement.new_build) == 0 &&
        app_helper_identity_matches(parent, GUARD_APP_HELPER_LIVE,
            replacement.new_bytes, replacement.new_sha,
            replacement.new_dev, replacement.new_ino) == 0 &&
        app_helper_identity_at(parent, GUARD_APP_HELPER_PREVIOUS,
            &previous_bytes, previous_sha, &previous_dev, &previous_ino) == 0 &&
        previous_bytes == replacement.old_bytes && strcmp(previous_sha, replacement.old_sha) == 0;
    int committed = exact && unlinkat(parent, GUARD_APP_HELPER_PREVIOUS, 0) == 0 &&
        fsync(parent) == 0 && unlinkat(dir, GUARD_REPLACEMENT, 0) == 0 && fsync(dir) == 0;
    if (parent >= 0) close(parent);
    if (dir >= 0) close(dir);
    guard_initialized = committed;
    pthread_mutex_unlock(&guard_lock);
    return committed ? 0 : -1;
}

void guard_maintenance_set_supervised(int supervised) {
    guard_supervised = supervised == 1;
    guard_supervisor_owner = 0;
    guard_supervisor_pid = guard_supervised ? getppid() : -1;
}

void guard_maintenance_set_supervisor_owner(void) {
    guard_supervised = 0;
    guard_supervisor_owner = 1;
    guard_supervisor_pid = getpid();
}

int guard_maintenance_supervised(void) {
    return guard_supervised && guard_supervisor_pid > 1 &&
        getppid() == guard_supervisor_pid && kill(guard_supervisor_pid, 0) == 0;
}

int guard_maintenance_supervisor_authoritative(void) {
    return guard_supervisor_owner && guard_supervisor_pid > 1 &&
        getpid() == guard_supervisor_pid;
}

#ifdef HAPANELD_TEST
void guard_test_set_fault(enum guard_test_fault fault) { test_fault = fault; }
void guard_test_set_supervised(int supervised) { guard_maintenance_set_supervised(supervised); }
void guard_test_set_now_ms(uint64_t now_ms) { test_now_ms = now_ms; }
void guard_test_set_pm_process_state(int state) { test_pm_process_state = state; }
void guard_test_set_app_autonomous_profile(int enabled) {
    test_app_autonomous_profile = enabled == 1;
}

static void remove_test_file(const char *name) {
    int dir = open_guard_dir();
    if (dir >= 0) { (void)remove_fixed_nondir(dir, name); close(dir); }
}

void guard_test_reset(void) {
    pthread_mutex_lock(&guard_lock);
    if (guard_owner_fd >= 0) { close(guard_owner_fd); guard_owner_fd = -1; }
    static const char *const names[] = {
        GUARD_DRAFT, GUARD_DRAFT_TMP, GUARD_MANIFEST, GUARD_MANIFEST_TMP,
        GUARD_JOURNAL, GUARD_JOURNAL_TMP, GUARD_CAPTURE, GUARD_CAPTURE_TMP,
        GUARD_A_APK, GUARD_B_APK,
        GUARD_A_UPLOAD, GUARD_B_UPLOAD, GUARD_BASELINE, GUARD_BASELINE_TMP,
        GUARD_PREMIGRATE, GUARD_PREMIGRATE_TMP, GUARD_B_PRIMARY, GUARD_B_PRIMARY_TMP,
        GUARD_HEALTH_COPY, GUARD_HEALTH_COPY_TMP,
        GUARD_SETTINGS, GUARD_SETTINGS_UPLOAD,
    };
    for (size_t i = 0; i < sizeof names / sizeof names[0]; i++) remove_test_file(names[i]);
    (void)unlink(GUARD_APP_HELPER_PARENT "/" GUARD_APP_HELPER_LIVE);
    (void)unlink(GUARD_APP_HELPER_PARENT "/" GUARD_APP_HELPER_STAGE);
    (void)unlink(GUARD_APP_HELPER_PARENT "/" GUARD_APP_HELPER_PREVIOUS);
    (void)unlink(GUARD_APP_HELPER_PARENT "/" GUARD_APP_HELPER_PREVIOUS_TMP);
    (void)unlink(HELPER_TRANSACTION_LOCK_PARENT "/" HELPER_TRANSACTION_LOCK_NAME "/"
                 HELPER_TRANSACTION_LOCK_PID);
    (void)rmdir(HELPER_TRANSACTION_LOCK_PARENT "/" HELPER_TRANSACTION_LOCK_NAME);
    test_fault = GUARD_TEST_FAULT_NONE;
    test_now_ms = 0;
    test_pm_process_state = 0;
    test_app_autonomous_profile = 0;
    guard_initialized = 0;
    guard_supervised = 0;
    guard_supervisor_owner = 0;
    guard_supervisor_pid = -1;
    pthread_mutex_unlock(&guard_lock);
}

int guard_test_reconcile(void) { return guard_maintenance_init(); }
int guard_test_restore_baseline_now(void) {
    pthread_mutex_lock(&guard_lock);
    int dir = open_guard_dir();
    guard_plan plan;
    guard_journal journal;
    int loaded = dir >= 0 ? load_manifest_reconciled(dir, &plan, &journal, 0) : -1;
    int restored = loaded == 1 ? restore_baseline_primary(dir, &plan, &journal) : -1;
    if (dir >= 0) close(dir);
    pthread_mutex_unlock(&guard_lock);
    return restored;
}
int guard_test_final_restore_receipt_exact(void) {
    pthread_mutex_lock(&guard_lock);
    int dir = open_guard_dir();
    guard_plan plan;
    guard_journal journal;
    int loaded = dir >= 0 ? load_manifest_reconciled(dir, &plan, &journal, 0) : -1;
    int exact = loaded == 1 ? final_restore_receipt_exact(&plan, &journal) : -1;
    if (dir >= 0) close(dir);
    pthread_mutex_unlock(&guard_lock);
    return exact;
}
void guard_test_drop_runtime(void) {
    pthread_mutex_lock(&guard_lock);
    if (guard_owner_fd >= 0) close(guard_owner_fd);
    guard_owner_fd = -1;
    guard_initialized = 0;
    pthread_mutex_unlock(&guard_lock);
}
#endif
