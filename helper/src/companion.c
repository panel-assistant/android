#define _GNU_SOURCE
#include "companion.h"

#include "sha256.h"
#include "sysexec.h"
#include "util.h"

#include <errno.h>
#include <dirent.h>
#include <fcntl.h>
#include <pthread.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <sys/xattr.h>
#include <unistd.h>

#define COMPANION_FULL    "io.homeassistant.companion.android"
#define COMPANION_MINIMAL "io.homeassistant.companion.android.minimal"

#ifdef HAPANELD_TEST
#include "test_tmp.h"
#define DATA_PARENT        HAPANELD_TEST_TMP "/.hapaneld-companion-test/data/user/0"
#define STAGE_PARENT       HAPANELD_TEST_TMP
#define STAGE_DIR_NAME     ".hapaneld-companion-stage-test"
#else
#define DATA_PARENT        "/data/user/0"
#define STAGE_PARENT       "/data/local"
#define STAGE_DIR_NAME     ".hapaneld-helper-companion"
#endif

#define DB_MAX_BYTES       (32ULL * 1024ULL * 1024ULL)
#define PREF_MAX_BYTES     (2ULL * 1024ULL * 1024ULL)
#define WAL_MAX_BYTES      (64ULL * 1024ULL * 1024ULL)
#define SHM_MAX_BYTES      (8ULL * 1024ULL * 1024ULL)
#define BACKUP_MAX_BYTES   (DB_MAX_BYTES + WAL_MAX_BYTES + SHM_MAX_BYTES + 2ULL * PREF_MAX_BYTES)
#define RESTORE_MAX_BYTES  (DB_MAX_BYTES + 2ULL * PREF_MAX_BYTES)

#define TX_MARKER_PREPARED ".companion-restore.prepared"
#define TX_MARKER_COMMITTED ".companion-restore.committed"
#define TX_MARKER_TEMP ".companion-restore.marker.tmp"
#define TX_MARKER_MAX_BYTES 192

enum companion_slot {
    SLOT_DB = 0,
    SLOT_DB_WAL,
    SLOT_DB_SHM,
    SLOT_SESSION,
    SLOT_INTEGRATION,
    SLOT_COUNT,
};

typedef struct {
    const char *relative;
    const char *parent;
    const char *name;
    const char *stage_name;
    const char *rollback_name;
    uint64_t max_bytes;
    int restore_slot;
} slot_spec;

static const slot_spec SLOTS[SLOT_COUNT] = {
    {
        "databases/HomeAssistantDB", "databases", "HomeAssistantDB",
        ".HomeAssistantDB.hapaneld-restore", ".HomeAssistantDB.hapaneld-rollback",
        DB_MAX_BYTES, 1,
    },
    {
        "databases/HomeAssistantDB-wal", "databases", "HomeAssistantDB-wal",
        NULL, ".HomeAssistantDB-wal.hapaneld-rollback", WAL_MAX_BYTES, 0,
    },
    {
        "databases/HomeAssistantDB-shm", "databases", "HomeAssistantDB-shm",
        NULL, ".HomeAssistantDB-shm.hapaneld-rollback", SHM_MAX_BYTES, 0,
    },
    {
        "shared_prefs/session_0.xml", "shared_prefs", "session_0.xml",
        ".session_0.xml.hapaneld-restore", ".session_0.xml.hapaneld-rollback",
        PREF_MAX_BYTES, 1,
    },
    {
        "shared_prefs/integration_0.xml", "shared_prefs", "integration_0.xml",
        ".integration_0.xml.hapaneld-restore", ".integration_0.xml.hapaneld-rollback",
        PREF_MAX_BYTES, 1,
    },
};

static const char *const ROOT_STAGE_NAMES[3] = {
    "companion-db.payload",
    "companion-session.payload",
    "companion-integration.payload",
};

typedef struct {
    int base;
    int databases;
    int shared_prefs;
    uid_t uid;
    gid_t gid;
    char label[256];
    size_t label_len;
} target_dirs;

typedef struct {
    int fd;
    struct stat st;
    int exists;
} opened_file;

typedef struct {
    const slot_spec *spec;
    int parent;
    int existed;
    int moved;
    int installed;
    struct stat old_st;
    char label[256];
    size_t label_len;
} live_entry;

typedef struct {
    char pkg[128];
    uint32_t entries;
    uint32_t existed;
    int committed;
} transaction_marker;

static pthread_mutex_t companion_lock = PTHREAD_MUTEX_INITIALIZER;

#ifdef HAPANELD_TEST
static enum companion_test_fault test_fault;
void companion_test_set_fault(enum companion_test_fault fault) {
    test_fault = fault;
}
#define TEST_FAULT(phase) (test_fault == (phase))
#else
#define TEST_FAULT(phase) 0
#endif

static int capture_label(int fd, char *label, size_t capacity, size_t *length);
static int companion_pending_transaction(void);
static int open_stage_dir(void);
static int rollback_artifacts_absent(const target_dirs *target);
static int recover_pending_transaction(
    int stage_dir,
    const char *keep_stopped_pkg,
    int *kept_stopped
);

static int supported_pkg(const char *pkg) {
    return pkg && (strcmp(pkg, COMPANION_FULL) == 0 || strcmp(pkg, COMPANION_MINIMAL) == 0);
}

static const char *canonical_pkg(const char *pkg) {
    if (!pkg) return NULL;
    if (strcmp(pkg, COMPANION_FULL) == 0) return COMPANION_FULL;
    if (strcmp(pkg, COMPANION_MINIMAL) == 0) return COMPANION_MINIMAL;
    return NULL;
}

static int supported_component(const char *component) {
    const char *slash = strchr(component, '/');
    if (!slash || slash == component) return 0;
    size_t pkg_len = (size_t)(slash - component);
    return (strlen(COMPANION_FULL) == pkg_len &&
            memcmp(component, COMPANION_FULL, pkg_len) == 0) ||
           (strlen(COMPANION_MINIMAL) == pkg_len &&
            memcmp(component, COMPANION_MINIMAL, pkg_len) == 0);
}

int companion_guard_package_launch(const char *pkg) {
    if (!supported_pkg(pkg)) return COMPANION_GUARD_NOT_APPLICABLE;
    if (pthread_mutex_trylock(&companion_lock) != 0) return COMPANION_GUARD_BUSY;
    if (companion_pending_transaction()) {
        pthread_mutex_unlock(&companion_lock);
        return COMPANION_GUARD_BUSY;
    }
    return COMPANION_GUARD_ACQUIRED;
}

int companion_guard_component_launch(const char *component) {
    if (!supported_component(component)) return COMPANION_GUARD_NOT_APPLICABLE;
    if (pthread_mutex_trylock(&companion_lock) != 0) return COMPANION_GUARD_BUSY;
    if (companion_pending_transaction()) {
        pthread_mutex_unlock(&companion_lock);
        return COMPANION_GUARD_BUSY;
    }
    return COMPANION_GUARD_ACQUIRED;
}

void companion_guard_release(int guard) {
    if (guard == COMPANION_GUARD_ACQUIRED) pthread_mutex_unlock(&companion_lock);
}

void cmd_companioncaps(conn_ctx *ctx, const char *args) {
    reply(ctx->fd, args[0] == '\0'
        ? "COMPANIONCAPS 1 BACKUP RESTORE STATUS JOURNAL\n" : "ERR\n");
}

void cmd_companionstatus(conn_ctx *ctx, const char *args) {
    if (args[0] != '\0') {
        reply(ctx->fd, "ERR\n");
        return;
    }
    int idle = pthread_mutex_trylock(&companion_lock) == 0;
    if (idle) {
        // A daemon restart loses the process-local mutex but not the transaction marker. STATUS is
        // the app's startup reconciliation path, so repair any valid journal before deciding whether
        // Companion may launch. Malformed markers and unexplained rollback artifacts remain BUSY.
        int stage_dir = open_stage_dir();
        if (stage_dir >= 0) {
            int kept_stopped = 0;
            (void)recover_pending_transaction(stage_dir, "", &kept_stopped);
            close(stage_dir);
        }
        idle = !companion_pending_transaction();
        pthread_mutex_unlock(&companion_lock);
    }
    reply(ctx->fd, idle ? "IDLE\n" : "BUSY\n");
}

static int write_all(int fd, const void *bytes, size_t count) {
    const char *p = bytes;
    while (count > 0) {
        ssize_t n = write(fd, p, count);
        if (n < 0 && errno == EINTR) continue;
        if (n <= 0) return -1;
        p += (size_t)n;
        count -= (size_t)n;
    }
    return 0;
}

static int send_line(int fd, const char *line) {
    return write_all(fd, line, strlen(line));
}

static int copy_n(int input, int output, uint64_t remaining) {
    char buf[65536];
    while (remaining > 0) {
        size_t wanted = remaining < sizeof buf ? (size_t)remaining : sizeof buf;
        ssize_t n = read(input, buf, wanted);
        if (n < 0 && errno == EINTR) continue;
        if (n <= 0 || write_all(output, buf, (size_t)n) != 0) return -1;
        remaining -= (uint64_t)n;
    }
    return 0;
}

static int require_eof(int fd) {
    char extra;
    for (;;) {
        ssize_t n = read(fd, &extra, 1);
        if (n < 0 && errno == EINTR) continue;
        return n == 0 ? 0 : -1;
    }
}

static int safe_root_dir(const char *path) {
    int fd = open(path, O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC);
    if (fd < 0) return -1;
    struct stat st;
    if (fstat(fd, &st) != 0 || !S_ISDIR(st.st_mode) ||
        (st.st_uid != 0 && st.st_uid != geteuid()) ||
        ((st.st_mode & (S_IWGRP | S_IWOTH)) != 0 && (st.st_mode & S_ISVTX) == 0)) {
        close(fd);
        return -1;
    }
    return fd;
}

static int open_stage_dir(void) {
    int parent = safe_root_dir(STAGE_PARENT);
    if (parent < 0) return -1;
    if (mkdirat(parent, STAGE_DIR_NAME, 0700) != 0 && errno != EEXIST) {
        close(parent);
        return -1;
    }
    int dir = openat(parent, STAGE_DIR_NAME, O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC);
    close(parent);
    if (dir < 0) return -1;
    struct stat st;
    if (fstat(dir, &st) != 0 || !S_ISDIR(st.st_mode) || st.st_uid != geteuid() ||
        fchmod(dir, 0700) != 0) {
        close(dir);
        return -1;
    }
    return dir;
}

static int open_root_stage(int dir, const char *name) {
    if (unlinkat(dir, name, 0) != 0 && errno != ENOENT) return -1;
    int fd = openat(dir, name, O_RDWR | O_CREAT | O_EXCL | O_NOFOLLOW | O_CLOEXEC, 0600);
    if (fd < 0) return -1;
    struct stat st;
    if (fstat(fd, &st) != 0 || !S_ISREG(st.st_mode) || st.st_uid != geteuid() ||
        st.st_nlink != 1 || fchmod(fd, 0600) != 0) {
        close(fd);
        (void)unlinkat(dir, name, 0);
        return -1;
    }
    return fd;
}

static void close_target(target_dirs *target) {
    if (target->shared_prefs >= 0) close(target->shared_prefs);
    if (target->databases >= 0) close(target->databases);
    if (target->base >= 0) close(target->base);
    target->base = target->databases = target->shared_prefs = -1;
}

static int verified_child_dir(
    int parent,
    const char *name,
    uid_t uid,
    const char *expected_label,
    size_t expected_label_len
) {
    int fd = openat(parent, name, O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC);
    if (fd < 0) return -1;
    struct stat st;
    char label[256];
    size_t label_len = 0;
    if (fstat(fd, &st) != 0 || !S_ISDIR(st.st_mode) || st.st_uid != uid ||
        capture_label(fd, label, sizeof label, &label_len) != 0 ||
        label_len != expected_label_len || memcmp(label, expected_label, label_len) != 0) {
        close(fd);
        return -1;
    }
    return fd;
}

static int open_target(const char *pkg, target_dirs *target) {
    *target = (target_dirs){ .base = -1, .databases = -1, .shared_prefs = -1 };
    if (!supported_pkg(pkg)) return -1;
    int parent = open(DATA_PARENT, O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC);
    if (parent < 0) return -1;
    struct stat parent_st;
    if (fstat(parent, &parent_st) != 0 || !S_ISDIR(parent_st.st_mode)) {
        close(parent);
        return -1;
    }
    target->base = openat(parent, pkg, O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC);
    close(parent);
    if (target->base < 0) return -1;
    struct stat base_st;
    if (fstat(target->base, &base_st) != 0 || !S_ISDIR(base_st.st_mode) ||
#ifndef HAPANELD_TEST
        base_st.st_uid == 0 ||
#endif
        base_st.st_uid == (uid_t)-1) {
        close_target(target);
        return -1;
    }
    target->uid = base_st.st_uid;
    target->gid = base_st.st_gid;
    if (capture_label(
            target->base, target->label, sizeof target->label, &target->label_len) != 0) {
        close_target(target);
        return -1;
    }
    target->databases = verified_child_dir(
        target->base, "databases", target->uid, target->label, target->label_len);
    target->shared_prefs = verified_child_dir(
        target->base, "shared_prefs", target->uid, target->label, target->label_len);
    if (target->databases < 0 || target->shared_prefs < 0) {
        close_target(target);
        return -1;
    }
    return 0;
}

static int slot_parent(const target_dirs *target, const slot_spec *spec) {
    return strcmp(spec->parent, "databases") == 0 ? target->databases : target->shared_prefs;
}

// Returns 1 for a verified file, 0 only for ENOENT, and -1 for every unsafe/error condition.
static int open_named_file(
    int parent,
    const slot_spec *spec,
    const char *name,
    uid_t uid,
    const char *expected_label,
    size_t expected_label_len,
    int allow_empty,
    opened_file *out
) {
    *out = (opened_file){ .fd = -1 };
    int fd = openat(parent, name, O_RDONLY | O_NOFOLLOW | O_CLOEXEC);
    if (fd < 0) return errno == ENOENT ? 0 : -1;
    struct stat st;
    char label[256];
    size_t label_len = 0;
    if (fstat(fd, &st) != 0 || !S_ISREG(st.st_mode) || st.st_uid != uid || st.st_nlink != 1 ||
        st.st_size < (allow_empty ? 0 : 1) || (uint64_t)st.st_size > spec->max_bytes ||
        capture_label(fd, label, sizeof label, &label_len) != 0 ||
        label_len != expected_label_len || memcmp(label, expected_label, label_len) != 0) {
        close(fd);
        return -1;
    }
    out->fd = fd;
    out->st = st;
    out->exists = 1;
    return 1;
}

static int open_live_file(
    int parent,
    const slot_spec *spec,
    uid_t uid,
    const char *expected_label,
    size_t expected_label_len,
    int allow_empty,
    opened_file *out
) {
    return open_named_file(
        parent, spec, spec->name, uid, expected_label, expected_label_len, allow_empty, out);
}

static int capture_label(int fd, char *label, size_t capacity, size_t *length) {
#ifdef HAPANELD_TEST
    (void)fd; (void)capacity;
    label[0] = '\0';
    *length = 0;
    return 0;
#else
    ssize_t n = fgetxattr(fd, "security.selinux", label, capacity);
    if (n <= 0 || (size_t)n > capacity) return -1;
    *length = (size_t)n;
    return 0;
#endif
}

static int apply_label(int fd, const char *label, size_t length) {
#ifdef HAPANELD_TEST
    (void)fd; (void)label; (void)length;
    return 0;
#else
    return length > 0 && fsetxattr(fd, "security.selinux", label, length, 0) == 0 ? 0 : -1;
#endif
}

static int remove_nondir_at(int parent, const char *name) {
    struct stat st;
    if (fstatat(parent, name, &st, AT_SYMLINK_NOFOLLOW) != 0)
        return errno == ENOENT ? 0 : -1;
    if (S_ISDIR(st.st_mode)) return -1;
    return unlinkat(parent, name, 0);
}

static int force_stop(const char *pkg) {
    const char *allowed_pkg = canonical_pkg(pkg);
    if (!allowed_pkg) return -1;
    const char *const argv[] = { "am", "force-stop", allowed_pkg, NULL };
    return sysexec_run_argv("/system/bin/am", argv, 0) == 0 ? 0 : -1;
}

static int relaunch(const char *pkg) {
    const char *allowed_pkg = canonical_pkg(pkg);
    if (!allowed_pkg) return -1;
    const char *const argv[] = {
        "monkey", "-p", allowed_pkg,
        "-c", "android.intent.category.LAUNCHER", "1", NULL,
    };
    return sysexec_run_argv("/system/bin/monkey", argv, 1) == 0 ? 0 : -1;
}

static int marker_present(int stage_dir, const char *name) {
    struct stat st;
    if (fstatat(stage_dir, name, &st, AT_SYMLINK_NOFOLLOW) == 0) return 1;
    return errno == ENOENT ? 0 : -1;
}

static int valid_transaction_masks(uint32_t entries, uint32_t existed) {
    const uint32_t valid = (1U << SLOT_COUNT) - 1U;
    const uint32_t requested =
        (1U << SLOT_DB) | (1U << SLOT_SESSION) | (1U << SLOT_INTEGRATION);
    const uint32_t sidecars = (1U << SLOT_DB_WAL) | (1U << SLOT_DB_SHM);
    if (entries == 0 || (entries & ~valid) != 0 || (existed & ~entries) != 0 ||
        (entries & requested) == 0) return 0;
    return (entries & (1U << SLOT_DB)) != 0
        ? (entries & sidecars) == sidecars
        : (entries & sidecars) == 0;
}

static int read_marker_file(
    int stage_dir,
    const char *name,
    int committed,
    transaction_marker *marker
) {
    int fd = openat(stage_dir, name, O_RDONLY | O_NOFOLLOW | O_CLOEXEC);
    if (fd < 0) return -1;
    struct stat st;
    char text[TX_MARKER_MAX_BYTES + 1];
    int ok = fstat(fd, &st) == 0 && S_ISREG(st.st_mode) && st.st_uid == geteuid() &&
        st.st_nlink == 1 && st.st_size > 0 && st.st_size <= TX_MARKER_MAX_BYTES;
    ssize_t n = ok ? read(fd, text, (size_t)st.st_size) : -1;
    char extra;
    ssize_t trailing = n == st.st_size ? read(fd, &extra, 1) : -1;
    close(fd);
    if (!ok || n != st.st_size || trailing != 0) return -1;
    text[n] = '\0';

    unsigned entries = 0;
    unsigned existed = 0;
    int consumed = 0;
    char pkg[128] = "";
    if (sscanf(text, "V1 %127s %8x %8x\n%n", pkg, &entries, &existed, &consumed) != 3 ||
        consumed != n || !supported_pkg(pkg) ||
        !valid_transaction_masks((uint32_t)entries, (uint32_t)existed)) return -1;
    *marker = (transaction_marker){
        .entries = (uint32_t)entries,
        .existed = (uint32_t)existed,
        .committed = committed,
    };
    memcpy(marker->pkg, pkg, strlen(pkg) + 1);
    return 0;
}

// Returns 1 when a valid pending transaction exists, 0 when none exists, and -1 for an unsafe or
// ambiguous marker state. A stale temp contains no authority and is removed only when no phase marker
// exists; rollback artifacts are never inferred from a temp file.
static int read_pending_marker(int stage_dir, transaction_marker *marker) {
    int prepared = marker_present(stage_dir, TX_MARKER_PREPARED);
    int committed = marker_present(stage_dir, TX_MARKER_COMMITTED);
    if (prepared < 0 || committed < 0 || (prepared && committed)) return -1;
    if (!prepared && !committed) {
        int temp = marker_present(stage_dir, TX_MARKER_TEMP);
        if (temp < 0) return -1;
        if (temp && (remove_nondir_at(stage_dir, TX_MARKER_TEMP) != 0 ||
                     fsync(stage_dir) != 0)) return -1;
        return 0;
    }
    const char *name = committed ? TX_MARKER_COMMITTED : TX_MARKER_PREPARED;
    return read_marker_file(stage_dir, name, committed, marker) == 0 ? 1 : -1;
}

// A durable marker outlives the process-local mutex. Treat a valid marker, malformed marker, or an
// inaccessible stage compartment as BUSY so a daemon restart cannot reopen START/RELOAD before a
// Companion backup/restore command has recovered the interrupted transaction.
static int companion_pending_transaction(void) {
    int stage_dir = open_stage_dir();
    if (stage_dir < 0) return 1;
    transaction_marker marker;
    int pending = read_pending_marker(stage_dir, &marker);
    close(stage_dir);
    if (pending != 0) return 1;

    const char *const packages[] = { COMPANION_FULL, COMPANION_MINIMAL };
    for (size_t i = 0; i < sizeof packages / sizeof packages[0]; i++) {
        target_dirs target = { .base = -1, .databases = -1, .shared_prefs = -1 };
        if (open_target(packages[i], &target) != 0) continue;
        int clean = rollback_artifacts_absent(&target) == 0;
        close_target(&target);
        if (!clean) return 1;
    }
    return 0;
}

int companion_retire_uninstalled(void) {
    if (companion_pending_transaction()) return -1;
    int dir = open_stage_dir();
    if (dir < 0) return -1;
    int duplicate = openat(dir, ".", O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC);
    DIR *stream = duplicate >= 0 ? fdopendir(duplicate) : NULL;
    if (!stream) { if (duplicate >= 0) close(duplicate); close(dir); return -1; }
    int safe = 1;
    errno = 0;
    for (struct dirent *entry = readdir(stream); entry; entry = readdir(stream)) {
        if (!strcmp(entry->d_name, ".") || !strcmp(entry->d_name, "..")) continue;
        int allowed = 0;
        for (size_t i = 0; i < 3; i++)
            if (!strcmp(entry->d_name, ROOT_STAGE_NAMES[i])) allowed = 1;
        struct stat st;
        if (!allowed || fstatat(dir, entry->d_name, &st, AT_SYMLINK_NOFOLLOW) != 0 ||
            !S_ISREG(st.st_mode) || st.st_uid != geteuid() || st.st_nlink != 1) {
            safe = 0;
            break;
        }
        errno = 0;
    }
    if (errno != 0) safe = 0;
    closedir(stream);
    for (size_t i = 0; safe && i < 3; i++)
        if (unlinkat(dir, ROOT_STAGE_NAMES[i], 0) != 0 && errno != ENOENT) safe = 0;
    if (safe && fsync(dir) != 0) safe = 0;
    close(dir);
    if (safe && rmdir(STAGE_PARENT "/" STAGE_DIR_NAME) != 0) safe = 0;
    return safe ? 0 : -1;
}

static int write_prepared_marker(
    int stage_dir,
    const char *pkg,
    uint32_t entries,
    uint32_t existed
) {
    if (!supported_pkg(pkg) || !valid_transaction_masks(entries, existed) ||
        marker_present(stage_dir, TX_MARKER_PREPARED) != 0 ||
        marker_present(stage_dir, TX_MARKER_COMMITTED) != 0 ||
        remove_nondir_at(stage_dir, TX_MARKER_TEMP) != 0) return -1;
    int fd = openat(
        stage_dir, TX_MARKER_TEMP,
        O_WRONLY | O_CREAT | O_EXCL | O_NOFOLLOW | O_CLOEXEC, 0600);
    if (fd < 0) return -1;
    char text[TX_MARKER_MAX_BYTES + 1];
    int length = snprintf(text, sizeof text, "V1 %s %08x %08x\n", pkg, entries, existed);
    int ok = length > 0 && (size_t)length < sizeof text &&
        write_all(fd, text, (size_t)length) == 0 && fsync(fd) == 0;
    if (close(fd) != 0) ok = 0;
    if (!ok) {
        (void)unlinkat(stage_dir, TX_MARKER_TEMP, 0);
        return -1;
    }
    if (renameat(stage_dir, TX_MARKER_TEMP, stage_dir, TX_MARKER_PREPARED) != 0) {
        (void)unlinkat(stage_dir, TX_MARKER_TEMP, 0);
        return -1;
    }
    if (fsync(stage_dir) != 0) {
        (void)unlinkat(stage_dir, TX_MARKER_PREPARED, 0);
        (void)fsync(stage_dir);
        return -1;
    }
    return 0;
}

static int mark_transaction_committed(int stage_dir) {
    if (renameat(stage_dir, TX_MARKER_PREPARED, stage_dir, TX_MARKER_COMMITTED) != 0) return -1;
    return fsync(stage_dir) == 0 ? 0 : -2;
}

static int clear_transaction_marker(int stage_dir, int committed) {
    const char *name = committed ? TX_MARKER_COMMITTED : TX_MARKER_PREPARED;
    if (unlinkat(stage_dir, name, 0) != 0 && errno != ENOENT) return -1;
    return fsync(stage_dir);
}

static int fsync_target_dirs(const target_dirs *target) {
    return fsync(target->databases) == 0 && fsync(target->shared_prefs) == 0 ? 0 : -1;
}

static int rollback_artifacts_absent(const target_dirs *target) {
    for (int slot = 0; slot < SLOT_COUNT; slot++) {
        int parent = slot_parent(target, &SLOTS[slot]);
        struct stat st;
        if (fstatat(parent, SLOTS[slot].rollback_name, &st, AT_SYMLINK_NOFOLLOW) == 0)
            return -1;
        if (errno != ENOENT) return -1;
    }
    return 0;
}

static int recover_prepared_transaction(
    int stage_dir,
    const target_dirs *target,
    const transaction_marker *marker
) {
    for (int slot = 0; slot < SLOT_COUNT; slot++) {
        uint32_t bit = 1U << slot;
        if ((marker->entries & bit) == 0) continue;
        const slot_spec *spec = &SLOTS[slot];
        int parent = slot_parent(target, spec);
        opened_file rollback;
        opened_file live;
        int rollback_found = open_named_file(
            parent, spec, spec->rollback_name, target->uid,
            target->label, target->label_len, 1, &rollback);
        int live_found = open_live_file(
            parent, spec, target->uid, target->label, target->label_len, 1, &live);
        if (rollback.fd >= 0) close(rollback.fd);
        if (live.fd >= 0) close(live.fd);
        if (rollback_found < 0 || live_found < 0) return -1;

        if (rollback_found == 1) {
            if (live_found == 1 && unlinkat(parent, spec->name, 0) != 0) return -1;
            if (renameat(parent, spec->rollback_name, parent, spec->name) != 0) return -1;
        } else if ((marker->existed & bit) == 0) {
            if (live_found == 1 && unlinkat(parent, spec->name, 0) != 0) return -1;
        } else if (live_found == 0) {
            // The original existed, but neither its live name nor its rollback remains. Never erase
            // any other rollback or marker when the prior state can no longer be proven.
            return -1;
        }
        if (spec->stage_name && remove_nondir_at(parent, spec->stage_name) != 0) return -1;
    }
    return fsync_target_dirs(target) == 0 &&
        clear_transaction_marker(stage_dir, 0) == 0 ? 0 : -1;
}

static int recover_committed_transaction(
    int stage_dir,
    const target_dirs *target,
    const transaction_marker *marker
) {
    // A committed marker is authoritative only while every requested new live file still satisfies
    // the original owner/link/label/size boundary. If not, preserve all rollback artifacts.
    for (int slot = 0; slot < SLOT_COUNT; slot++) {
        uint32_t bit = 1U << slot;
        if ((marker->entries & bit) == 0 || !SLOTS[slot].restore_slot) continue;
        opened_file live;
        int found = open_live_file(
            slot_parent(target, &SLOTS[slot]), &SLOTS[slot], target->uid,
            target->label, target->label_len, 0, &live);
        if (live.fd >= 0) close(live.fd);
        if (found != 1) return -1;
    }
    for (int slot = 0; slot < SLOT_COUNT; slot++) {
        uint32_t bit = 1U << slot;
        if ((marker->entries & bit) == 0) continue;
        int parent = slot_parent(target, &SLOTS[slot]);
        if (remove_nondir_at(parent, SLOTS[slot].rollback_name) != 0 ||
            (SLOTS[slot].stage_name &&
             remove_nondir_at(parent, SLOTS[slot].stage_name) != 0)) return -1;
    }
    return fsync_target_dirs(target) == 0 &&
        clear_transaction_marker(stage_dir, 1) == 0 ? 0 : -1;
}

// Recover the single daemon-wide pending transaction before starting a new Companion data operation.
// The marker names are global because the mutex is global; the record carries the exact allowlisted
// package so a command for the other Companion variant still repairs the interrupted transaction.
static int recover_pending_transaction(
    int stage_dir,
    const char *keep_stopped_pkg,
    int *kept_stopped
) {
    transaction_marker marker;
    int pending = read_pending_marker(stage_dir, &marker);
    if (pending <= 0) return pending;
    if (force_stop(marker.pkg) != 0) return -1;

    target_dirs target = { .base = -1, .databases = -1, .shared_prefs = -1 };
    int recovered = open_target(marker.pkg, &target) == 0 &&
        (marker.committed
            ? recover_committed_transaction(stage_dir, &target, &marker)
            : recover_prepared_transaction(stage_dir, &target, &marker)) == 0;
    close_target(&target);
    if (!recovered) return -1;
    if (strcmp(marker.pkg, keep_stopped_pkg) == 0) {
        *kept_stopped = 1;
        return 1;
    }
    return relaunch(marker.pkg) == 0 ? 1 : -1;
}

static void close_opened(opened_file files[SLOT_COUNT]) {
    for (int i = 0; i < SLOT_COUNT; i++)
        if (files[i].fd >= 0) close(files[i].fd);
}

void cmd_companionbackup(conn_ctx *ctx, const char *args) {
    char pkg[128] = "", option[8] = "", extra[2] = "";
    int fields = sscanf(args, "%127s %7s %1s", pkg, option, extra);
    // HOLD leaves a captured Companion stopped, so nothing it writes can be lost before the caller's
    // restore; a capture that fails still relaunches.
    int hold = fields == 2 && strcmp(option, "HOLD") == 0;
    if ((fields != 1 && !hold) || !supported_pkg(pkg)) {
        reply(ctx->fd, "ERR\n");
        return;
    }
    if (pthread_mutex_trylock(&companion_lock) != 0) {
        reply(ctx->fd, "BUSY\n");
        return;
    }

    int stage_dir = open_stage_dir();
    int stopped = 0;
    int recovery_safe = stage_dir >= 0 &&
        recover_pending_transaction(stage_dir, pkg, &stopped) >= 0;
    if (recovery_safe && !stopped) stopped = force_stop(pkg) == 0;
    int captured = 0;
    int streamed = 0;
    int count = 0;
    uint64_t total = 0;
    target_dirs target = { .base = -1, .databases = -1, .shared_prefs = -1 };
    opened_file files[SLOT_COUNT];
    for (int i = 0; i < SLOT_COUNT; i++) files[i].fd = -1;

    if (recovery_safe && stopped && open_target(pkg, &target) == 0) {
        captured = rollback_artifacts_absent(&target) == 0;
        if (!captured) recovery_safe = 0;
        for (int i = 0; i < SLOT_COUNT; i++) {
            if (!captured) break;
            int found = open_live_file(
                slot_parent(&target, &SLOTS[i]), &SLOTS[i], target.uid,
                target.label, target.label_len, i == SLOT_DB_WAL, &files[i]);
            if (found < 0 || (i == SLOT_DB && found == 0)) {
                captured = 0;
                break;
            }
            if (found == 1 && files[i].st.st_size > 0) {
                uint64_t size = (uint64_t)files[i].st.st_size;
                if (total > BACKUP_MAX_BYTES - size) {
                    captured = 0;
                    break;
                }
                total += size;
                count++;
            } else if (found == 1) {
                close(files[i].fd);
                files[i].fd = -1;
                files[i].exists = 0;
            }
        }
    }

    if (captured) {
        char line[128];
        snprintf(line, sizeof line, "BACKUP %d %llu\n", count, (unsigned long long)total);
        streamed = send_line(ctx->fd, line) == 0;
        for (int i = 0; streamed && i < SLOT_COUNT; i++) {
            if (files[i].fd < 0) continue;
            snprintf(line, sizeof line, "FILE %s %llu\n",
                SLOTS[i].relative, (unsigned long long)files[i].st.st_size);
            if (send_line(ctx->fd, line) != 0 ||
                copy_n(files[i].fd, ctx->fd, (uint64_t)files[i].st.st_size) != 0) {
                streamed = 0;
            }
        }
    }

    close_opened(files);
    close_target(&target);
    if (stage_dir >= 0) close(stage_dir);
    int held = hold && captured && streamed;
    int relaunched = !held && recovery_safe && stopped && relaunch(pkg) == 0;
    if (!captured) {
        reply(ctx->fd, relaunched ? "ERR\n" : "ERR RELAUNCH_ERR\n");
    } else if (held) {
        reply(ctx->fd, "DONE HELD\n");
    } else if (streamed) {
        reply(ctx->fd, relaunched ? "DONE\n" : "DONE RELAUNCH_ERR\n");
    }
    pthread_mutex_unlock(&companion_lock);
}

static int parse_size(const char *text, uint64_t max, uint64_t *size) {
    if (strcmp(text, "-") == 0) {
        *size = 0;
        return 0;
    }
    errno = 0;
    char *end = NULL;
    unsigned long long value = strtoull(text, &end, 10);
    if (errno != 0 || end == text || *end != '\0' || value == 0 || value > max) return -1;
    *size = (uint64_t)value;
    return 0;
}

static int prepare_live_entry(
    const target_dirs *target,
    const slot_spec *spec,
    live_entry *entry
) {
    memset(entry, 0, sizeof *entry);
    entry->spec = spec;
    entry->parent = slot_parent(target, spec);
    opened_file old;
    int found = open_live_file(
        entry->parent, spec, target->uid, target->label, target->label_len, 1, &old);
    if (found < 0) return -1;
    entry->existed = found == 1;
    if (entry->existed) {
        entry->old_st = old.st;
    }
    memcpy(entry->label, target->label, target->label_len);
    entry->label_len = target->label_len;
    if (old.fd >= 0) close(old.fd);
    return 0;
}

static int make_target_stage(
    const target_dirs *target,
    live_entry *entry,
    int source,
    uint64_t size
) {
    if (remove_nondir_at(entry->parent, entry->spec->stage_name) != 0) return -1;
    int output = openat(
        entry->parent, entry->spec->stage_name,
        O_WRONLY | O_CREAT | O_EXCL | O_NOFOLLOW | O_CLOEXEC, 0600);
    if (output < 0) return -1;
    mode_t mode = entry->existed ? (entry->old_st.st_mode & 0777) : 0600;
    gid_t gid = entry->existed ? entry->old_st.st_gid : target->gid;
    int ok =
        lseek(source, 0, SEEK_SET) >= 0 &&
        copy_n(source, output, size) == 0 &&
        fchown(output, target->uid, gid) == 0 &&
        fchmod(output, mode) == 0 &&
        apply_label(output, entry->label, entry->label_len) == 0 &&
        fsync(output) == 0;
    struct stat st;
    if (!ok || fstat(output, &st) != 0 || !S_ISREG(st.st_mode) || st.st_uid != target->uid ||
        st.st_nlink != 1 || (uint64_t)st.st_size != size) {
        close(output);
        (void)unlinkat(entry->parent, entry->spec->stage_name, 0);
        return -1;
    }
    return close(output);
}

static int rollback_entries(live_entry *entries, size_t count) {
    int ok = 1;
    int database_parent = -1;
    int prefs_parent = -1;
    for (size_t i = 0; i < count; i++) {
        live_entry *entry = &entries[i];
        if (strcmp(entry->spec->parent, "databases") == 0) database_parent = entry->parent;
        else prefs_parent = entry->parent;
        if (entry->installed &&
            unlinkat(entry->parent, entry->spec->name, 0) != 0 && errno != ENOENT) ok = 0;
    }
    for (size_t i = count; i > 0; i--) {
        live_entry *entry = &entries[i - 1];
        if (entry->moved &&
            renameat(entry->parent, entry->spec->rollback_name,
                     entry->parent, entry->spec->name) != 0) ok = 0;
    }
    for (size_t i = 0; i < count; i++) {
        live_entry *entry = &entries[i];
        if (entry->spec->stage_name &&
            unlinkat(entry->parent, entry->spec->stage_name, 0) != 0 && errno != ENOENT) ok = 0;
    }
    if (database_parent >= 0 && fsync(database_parent) != 0) ok = 0;
    if (prefs_parent >= 0 && fsync(prefs_parent) != 0) ok = 0;
    return ok ? 0 : -1;
}

static int finish_failed_transaction(live_entry *entries, size_t count, int stage_dir) {
    if (rollback_entries(entries, count) != 0) return -1;
    return clear_transaction_marker(stage_dir, 0) == 0 ? 1 : -1;
}

// 0 committed, 1 failed with the prior live set intact, -1 rollback failed/partial state, and -2
// test-only simulated interruption or an indeterminate phase-marker durability failure. Both negative
// results suppress relaunch and leave the marker/rollback set for the next pre-command recovery.
static int commit_restore(
    const target_dirs *target,
    int stage_dir,
    const char *pkg,
    const uint64_t sizes[3],
    const int sources[3]
) {
    live_entry entries[SLOT_COUNT];
    size_t count = 0;
    const int requested_slots[3] = { SLOT_DB, SLOT_SESSION, SLOT_INTEGRATION };

    // An unexplained rollback name may be the only surviving prior live file. Only a valid durable
    // marker authorizes recovery or deletion; fail closed before creating any new target stages.
    if (rollback_artifacts_absent(target) != 0) return -1;

    for (int request = 0; request < 3; request++) {
        if (sizes[request] == 0) continue;
        int slot = requested_slots[request];
        if (prepare_live_entry(target, &SLOTS[slot], &entries[count]) != 0 ||
            make_target_stage(target, &entries[count], sources[request], sizes[request]) != 0) {
            (void)rollback_entries(entries, count);
            return 1;
        }
        count++;
    }
    if (sizes[0] > 0) {
        for (int slot = SLOT_DB_WAL; slot <= SLOT_DB_SHM; slot++) {
            if (prepare_live_entry(target, &SLOTS[slot], &entries[count]) != 0) {
                (void)rollback_entries(entries, count);
                return 1;
            }
            count++;
        }
    }

    uint32_t entry_mask = 0;
    uint32_t existed_mask = 0;
    for (size_t i = 0; i < count; i++) {
        uint32_t bit = 1U << (unsigned)(entries[i].spec - SLOTS);
        entry_mask |= bit;
        if (entries[i].existed) existed_mask |= bit;
    }
    if (write_prepared_marker(stage_dir, pkg, entry_mask, existed_mask) != 0)
        return rollback_entries(entries, count) == 0 ? 1 : -1;

    for (size_t i = 0; i < count; i++) {
        if (!entries[i].existed) continue;
        if (renameat(entries[i].parent, entries[i].spec->name,
                     entries[i].parent, entries[i].spec->rollback_name) != 0) {
            return finish_failed_transaction(entries, count, stage_dir);
        }
        entries[i].moved = 1;
    }
    // Persist every prior live->rollback rename before any new live name can become durable.
    if (fsync_target_dirs(target) != 0)
        return finish_failed_transaction(entries, count, stage_dir);
    if (TEST_FAULT(COMPANION_TEST_FAULT_AFTER_MOVES)) return -2;

    for (size_t i = 0; i < count; i++) {
        if (!entries[i].spec->restore_slot) continue;
        if (renameat(entries[i].parent, entries[i].spec->stage_name,
                     entries[i].parent, entries[i].spec->name) != 0) {
            return finish_failed_transaction(entries, count, stage_dir);
        }
        entries[i].installed = 1;
    }
    if (fsync_target_dirs(target) != 0)
        return finish_failed_transaction(entries, count, stage_dir);
    if (TEST_FAULT(COMPANION_TEST_FAULT_AFTER_INSTALLS)) return -2;

    int marked = mark_transaction_committed(stage_dir);
    if (marked == -1) return finish_failed_transaction(entries, count, stage_dir);
    if (marked == -2 || TEST_FAULT(COMPANION_TEST_FAULT_AFTER_COMMIT_MARKER)) return -2;

    int cleaned = 1;
    for (size_t i = 0; i < count; i++) {
        if (entries[i].moved &&
            remove_nondir_at(entries[i].parent, entries[i].spec->rollback_name) != 0)
            cleaned = 0;
    }
    if (fsync_target_dirs(target) != 0) cleaned = 0;
    if (cleaned) {
        // Once this unlink is durable there is no rollback authority left for a later command to
        // misinterpret. If cleanup fails, retain the committed marker and finish it next time.
        (void)clear_transaction_marker(stage_dir, 1);
    }
    return 0;
}

// 0 when the live database and WAL still hold exactly the captured bytes; -1 when either changed,
// vanished or cannot be read safely. "-" expects no WAL or an empty one, as a capture omits it.
static int live_matches_capture(const target_dirs *target, const char *db_sha, const char *wal_sha) {
    const char *const expected[2] = { db_sha, wal_sha };
    for (int slot = SLOT_DB; slot <= SLOT_DB_WAL; slot++) {
        opened_file file;
        int found = open_live_file(slot_parent(target, &SLOTS[slot]), &SLOTS[slot], target->uid,
            target->label, target->label_len, slot == SLOT_DB_WAL, &file);
        int absent = found == 0 || (found == 1 && file.st.st_size == 0);
        char actual[65];
        int same = found >= 0 && (strcmp(expected[slot], "-") == 0
            ? absent
            : !absent && hapaneld_sha256_fd(file.fd, (uint64_t)file.st.st_size, actual) == 0 &&
                strcmp(actual, expected[slot]) == 0);
        if (found == 1) close(file.fd);
        if (!same) return -1;
    }
    return 0;
}

void cmd_companionrestore(conn_ctx *ctx, const char *args) {
    char pkg[128] = "", db_text[32] = "", session_text[32] = "", integration_text[32] = "";
    char option[8] = "", db_sha[66] = "", wal_sha[66] = "", extra[2] = "";
    int fields = sscanf(args, "%127s %31s %31s %31s %7s %65s %65s %1s",
        pkg, db_text, session_text, integration_text, option, db_sha, wal_sha, extra);
    // EXPECT names the live database and WAL a held capture saw; the restore commits only over them.
    int expect = fields == 7 && strcmp(option, "EXPECT") == 0 && lower_hex_64(db_sha) &&
        (strcmp(wal_sha, "-") == 0 || lower_hex_64(wal_sha));
    uint64_t sizes[3];
    if ((fields != 4 && !expect) || !supported_pkg(pkg) ||
        parse_size(db_text, DB_MAX_BYTES, &sizes[0]) != 0 ||
        parse_size(session_text, PREF_MAX_BYTES, &sizes[1]) != 0 ||
        parse_size(integration_text, PREF_MAX_BYTES, &sizes[2]) != 0 ||
        (sizes[0] == 0 && sizes[1] == 0 && sizes[2] == 0) ||
        sizes[0] > RESTORE_MAX_BYTES - sizes[1] - sizes[2]) {
        reply(ctx->fd, "STREAMERR\n");
        return;
    }
    if (pthread_mutex_trylock(&companion_lock) != 0) {
        reply(ctx->fd, "BUSY\n");
        return;
    }

    int stage_dir = open_stage_dir();
    int sources[3] = { -1, -1, -1 };
    int ready = stage_dir >= 0;
    for (int i = 0; ready && i < 3; i++) {
        if (sizes[i] == 0) continue;
        sources[i] = open_root_stage(stage_dir, ROOT_STAGE_NAMES[i]);
        if (sources[i] < 0) ready = 0;
    }
    if (!ready) {
        reply(ctx->fd, "STREAMERR\n");
        goto cleanup;
    }

    reply(ctx->fd, "READY\n");
    int copied = 1;
    for (int i = 0; copied && i < 3; i++) {
        if (sources[i] < 0) continue;
        copied = copy_n(ctx->fd, sources[i], sizes[i]) == 0 && fsync(sources[i]) == 0;
    }
    if (!copied || require_eof(ctx->fd) != 0) {
        reply(ctx->fd, "ERR\n");
        goto cleanup;
    }

    int stopped = 0;
    int recovery_safe = recover_pending_transaction(stage_dir, pkg, &stopped) >= 0;
    if (recovery_safe && !stopped) stopped = force_stop(pkg) == 0;
    int commit_result = recovery_safe ? 1 : -1;
    int changed = 0;
    target_dirs target = { .base = -1, .databases = -1, .shared_prefs = -1 };
    if (stopped && open_target(pkg, &target) == 0) {
        changed = recovery_safe && expect && live_matches_capture(&target, db_sha, wal_sha) != 0;
        if (!changed) commit_result = commit_restore(&target, stage_dir, pkg, sizes, sources);
    }
    close_target(&target);
    // Never execute a partially restored application. A known rollback failure is a terminal safety
    // state that requires manual recovery; only committed or confirmed-rolled-back states may launch.
    int relaunched = commit_result >= 0 && stopped && relaunch(pkg) == 0;
    if (changed)
        reply(ctx->fd, relaunched ? "CHANGED\n" : "CHANGED RELAUNCH_ERR\n");
    else if (commit_result == 0)
        reply(ctx->fd, relaunched ? "OK\n" : "COMMITTED RELAUNCH_ERR\n");
    else if (commit_result > 0)
        reply(ctx->fd, relaunched ? "ROLLED_BACK\n" : "ROLLED_BACK RELAUNCH_ERR\n");
    else
        reply(ctx->fd, "ROLLBACK_FAILED RELAUNCH_SUPPRESSED\n");

cleanup:
    for (int i = 0; i < 3; i++) {
        if (sources[i] >= 0) close(sources[i]);
        if (stage_dir >= 0) (void)unlinkat(stage_dir, ROOT_STAGE_NAMES[i], 0);
    }
    if (stage_dir >= 0) close(stage_dir);
    pthread_mutex_unlock(&companion_lock);
}
