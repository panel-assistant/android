// Helper self-replacement: grant, export and adopt the Guard owner-lock lease, and stage, adopt
// and commit the replacement app helper. Split out of guard_maintenance.c.
#define _GNU_SOURCE
#include "guard_maintenance_internal.h"

int guard_maintenance_replacement_parent_grant(char nonce[65]) {
    if (!guard_maintenance_supervisor_authoritative() || !nonce ||
        guard_maintenance_replacement_export_lease() != 0) return GUARD_REPLACEMENT_HOLD;
    pthread_mutex_lock(&guard_lock);
    int dir = open_guard_dir();
    guard_replacement replacement;
    int loaded = dir >= 0 ? load_replacement_reconciled(dir, &replacement) : -1;
    int valid = loaded == 1 && replacement.phase == GUARD_REPLACEMENT_REQUESTED &&
        replacement_boot_current(&replacement) && replacement_old_caller_exact(&replacement) == 0;
    int stored = GUARD_PUBLISH_FAILED;
    /* The lease is already exported before opening the directory or publishing GRANTED. */
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
        owner.st_uid != geteuid() || owner.st_nlink != 1) return -1;
    /* FD3 can be /dev/null inherited from su. dup2 closes that local descriptor while the
     * authoritative open file description retains the lock without an ownership gap. */
    if (dup2(guard_owner_fd, GUARD_REPLACEMENT_LEASE_FD) < 0 ||
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
    if (guard_owner_fd >= 0 && guard_owner_fd != GUARD_REPLACEMENT_LEASE_FD) close(guard_owner_fd);
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
