#include "guard_boundary_harness.h"

static void test_guardself_live_helper_identity(void) {
    char sha[65] = "", reply_line[256], expected[256];
    int fd = open(HELPER_LIVE, O_RDONLY | O_NOFOLLOW | O_CLOEXEC);
    struct stat st = {0};
    int hashed = fd >= 0 && fstat(fd, &st) == 0 && st.st_size > 0 &&
        hapaneld_sha256_fd(fd, (uint64_t)st.st_size, sha) == 0;
    CHECK(hashed, "hash canonical live helper fixture\n");
    if (fd >= 0) close(fd);
    if (!hashed) return;
    snprintf(expected, sizeof expected, "OK GUARDSELF 1 %llu %s %s\n",
        (unsigned long long)st.st_size, sha, REPLACEMENT_BUILD);
    dispatch_once("GUARDSELF", reply_line, sizeof reply_line);
    CHECK(strcmp(reply_line, expected) == 0,
        "GUARDSELF binds exact live bytes, hash and build identity (got %s)\n", reply_line);
    dispatch_once("GUARDSELF extra", reply_line, sizeof reply_line);
    CHECK(strcmp(reply_line, "ERR ARGS self\n") == 0,
        "GUARDSELF rejects arguments (got %s)\n", reply_line);

    CHECK(rename(HELPER_LIVE, HELPER_STAGE) == 0,
        "move executing helper away from canonical live name\n");
    static const char wrong_inode[] = "not-the-running-helper";
    write_file_mode(HELPER_LIVE, wrong_inode, sizeof wrong_inode - 1, 0700);
    dispatch_once("GUARDSELF", reply_line, sizeof reply_line);
    CHECK(strcmp(reply_line, "ERR HOLD self\n") == 0,
        "GUARDSELF rejects a canonical name pointing at the wrong inode (got %s)\n", reply_line);
    CHECK(unlink(HELPER_LIVE) == 0 && rename(HELPER_STAGE, HELPER_LIVE) == 0,
        "restore executing helper to canonical live name\n");

    CHECK(link(HELPER_LIVE, HELPER_STAGE) == 0,
        "create non-authoritative multiply-linked live helper fixture\n");
    dispatch_once("GUARDSELF", reply_line, sizeof reply_line);
    CHECK(strcmp(reply_line, "ERR HOLD self\n") == 0,
        "GUARDSELF rejects a multiply-linked live helper (got %s)\n", reply_line);
    CHECK(unlink(HELPER_STAGE) == 0, "remove helper hard-link fixture\n");

    CHECK(rename(HELPER_LIVE, HELPER_STAGE) == 0 &&
          symlink(HELPER_STAGE, HELPER_LIVE) == 0,
        "create non-authoritative symlinked helper fixture\n");
    dispatch_once("GUARDSELF", reply_line, sizeof reply_line);
    CHECK(strcmp(reply_line, "ERR HOLD self\n") == 0,
        "GUARDSELF rejects a symlinked canonical helper name (got %s)\n", reply_line);
    CHECK(unlink(HELPER_LIVE) == 0 && rename(HELPER_STAGE, HELPER_LIVE) == 0,
        "restore helper after symlink refusal\n");
    dispatch_once("GUARDSELF", reply_line, sizeof reply_line);
    CHECK(strcmp(reply_line, expected) == 0,
        "GUARDSELF recovers only after exact live topology is restored (got %s)\n", reply_line);
}

static void test_sha256_vectors(void) {
    char hex[65];
    hash_bytes("", 0, hex);
    CHECK(strcmp(hex, "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855") == 0,
        "SHA-256 empty vector\n");
    hash_bytes("abc", 3, hex);
    CHECK(strcmp(hex, "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad") == 0,
        "SHA-256 abc vector\n");
}

static void test_caps_supervision_and_empty_status(void) {
    setup();
    char reply[1024];
    dispatch_once("GUARDCAPS", reply, sizeof reply);
    CHECK(strcmp(reply,
        "OK GUARDCAPS 1 PREPARE DEFINE STREAM ACTION HEALTH REFUSAL STATUS EVIDENCE CANCEL RETIRE JOURNAL TERMINAL_RETIRE\n") == 0,
        "one-shot CAPS omits supervision claim\n");
    guard_test_set_supervised(1);
    dispatch_once("GUARDCAPS", reply, sizeof reply);
    CHECK(strcmp(reply,
        "OK GUARDCAPS 1 PREPARE DEFINE STREAM ACTION HEALTH REFUSAL STATUS EVIDENCE CANCEL RETIRE JOURNAL AUTONOMOUS SUPERVISED TERMINAL_RETIRE\n") == 0,
        "exact APP supervised CAPS includes autonomous in frozen order\n");
    guard_test_set_app_autonomous_profile(0);
    dispatch_once("GUARDCAPS", reply, sizeof reply);
    CHECK(strcmp(reply,
        "OK GUARDCAPS 1 PREPARE DEFINE STREAM ACTION HEALTH REFUSAL STATUS EVIDENCE CANCEL RETIRE JOURNAL SUPERVISED TERMINAL_RETIRE\n") == 0,
        "non-APP supervised CAPS omits autonomous\n");
    dispatch_once("GUARDSTATUS", reply, sizeof reply);
    CHECK(strcmp(reply, "OK GUARDSTATUS 0 EMPTY NONE NONE NONE NONE 0 0 0 NONE NONE 0 0\n") == 0,
        "empty status exact (got %s)\n", reply);
    char prepare_line[512];
    prepare_command(prepare_line);
    dispatch_once(prepare_line, reply, sizeof reply);
    CHECK(strcmp(reply, "ERR STATE autonomous\n") == 0,
        "non-APP supervised helper refuses PREPARE authority\n");
}

static void test_plan_stream_restart_roundtrip(void) {
    setup();
    prepare();
    char reply[4096], expected[1024];
    dispatch_once("GUARDSTATUS", reply, sizeof reply);
    snprintf(expected, sizeof expected,
        "OK GUARDSTATUS 1 STAGING %s %s NONE NONE 0 0 2 NONE NONE ", SESSION, boot_nonce);
    check_status_with_deadlines(reply, expected, "prepared draft status exact");

    static const char a_payload[] = "exact-a-apk";
    static const char b_payload[] = "exact-b-apk-next";
    char a_sha[65], b_sha[65];
    hash_bytes(a_payload, sizeof a_payload - 1, a_sha);
    hash_bytes(b_payload, sizeof b_payload - 1, b_sha);
    define_role("A", 1, a_sha, 568, 11, 14, 14, sizeof a_payload - 1, 2);
    define_role("B", 2, b_sha, 569, 11, 15, 15, sizeof b_payload - 1, 3);
    stream_role("A", 3, a_sha, a_payload, sizeof a_payload - 1, 4);
    stream_role("B", 4, b_sha, b_payload, sizeof b_payload - 1, 5);
    stream_role("SETTINGS", 5, SETTINGS_AUTHORITY_SHA, SETTINGS_AUTHORITY,
        sizeof SETTINGS_AUTHORITY - 1, 6);

    char evidence_command[128];
    snprintf(evidence_command, sizeof evidence_command, "GUARDEVIDENCE %s", SESSION);
    dispatch_once(evidence_command, reply, sizeof reply);
    CHECK(strstr(reply, "OK GUARDEVIDENCE 1\n") == reply, "evidence header exact\n");
    char settings_line[256];
    snprintf(settings_line, sizeof settings_line, "SETTINGS 2 %zu %s\n",
        sizeof SETTINGS_AUTHORITY - 1, SETTINGS_AUTHORITY_SHA);
    CHECK(strstr(reply, settings_line) != NULL,
        "evidence projects exact sealed settings authority identity\n");
    CHECK(strstr(reply, "STATE 6 STAGING NONE NONE 0 0 NONE NONE ") != NULL,
        "evidence preserves current generation and phase\n");
    char baseline_line[256];
    snprintf(baseline_line, sizeof baseline_line, "BASELINE 8 %s 14 2 %s %s\n",
        DB_SHA, STATE_SHA, SETTINGS_SHA);
    CHECK(strstr(reply, baseline_line) != NULL, "evidence preserves semantic baseline fields\n");
    char a_line[320], b_line[320];
    snprintf(a_line, sizeof a_line, "A 1 1 %zu %s 568 11 14 14\n", sizeof a_payload - 1, a_sha);
    snprintf(b_line, sizeof b_line, "B 1 1 %zu %s 569 11 15 15\n", sizeof b_payload - 1, b_sha);
    CHECK(strstr(reply, a_line) != NULL && strstr(reply, b_line) != NULL,
        "evidence round-trips every A/B field\n");

    guard_test_drop_runtime();
    CHECK(guard_test_reconcile() == 0, "new-process reconciliation accepts exact custody\n");
    dispatch_once(evidence_command, reply, sizeof reply);
    CHECK(strstr(reply, a_line) != NULL && strstr(reply, b_line) != NULL &&
          strstr(reply, "STATE 6 STAGING NONE NONE 0 0 NONE NONE ") != NULL,
        "restart round-trip preserves every artifact field\n");
}

static void test_stream_rejects_wrong_short_and_extra_bytes(void) {
    setup();
    prepare();
    static const char payload[] = "payload";
    char sha[65], command[512], reply[512];
    hash_bytes(payload, sizeof payload - 1, sha);
    define_role("A", 1, sha, 568, 11, 14, 14, sizeof payload - 1, 2);

    char wrong[65];
    memset(wrong, 'a', 64); wrong[64] = '\0';
    snprintf(command, sizeof command, "GUARDSTREAM %s 2 A %zu %s",
        SESSION, sizeof payload - 1, wrong);
    dispatch_once(command, reply, sizeof reply);
    CHECK(strcmp(reply, "ERR MISMATCH artifact\n") == 0, "command digest mismatch rejected before READY\n");

    dispatch_job job;
    pthread_t thread;
    int peer[2];
    snprintf(command, sizeof command, "GUARDSTREAM %s 2 A %zu %s",
        SESSION, sizeof payload - 1, sha);
    start_dispatch(&job, &thread, peer, command);
    read_line(peer[1], reply, sizeof reply);
    CHECK(strcmp(reply, "READY\n") == 0, "short stream admitted\n");
    write_all(peer[1], payload, sizeof payload - 2);
    shutdown(peer[1], SHUT_WR);
    read_line(peer[1], reply, sizeof reply);
    CHECK(strcmp(reply, "ERR STREAM custody\n") == 0, "short stream rejected terminally\n");
    finish_dispatch(thread, peer[1]);

    start_dispatch(&job, &thread, peer, command);
    read_line(peer[1], reply, sizeof reply);
    write_all(peer[1], payload, sizeof payload - 1);
    write_all(peer[1], "x", 1);
    shutdown(peer[1], SHUT_WR);
    read_line(peer[1], reply, sizeof reply);
    CHECK(strcmp(reply, "ERR STREAM custody\n") == 0, "extra stream byte rejected terminally\n");
    finish_dispatch(thread, peer[1]);
}

static void test_tamper_or_orphan_fails_closed(void) {
    setup();
    prepare();
    static const char payload[] = "owned-a";
    char sha[65];
    hash_bytes(payload, sizeof payload - 1, sha);
    define_role("A", 1, sha, 568, 11, 14, 14, sizeof payload - 1, 2);
    stream_role("A", 2, sha, payload, sizeof payload - 1, 3);
    CHECK(chmod(CUSTODY "/a.apk", 0600) == 0, "tamper staged mode fixture\n");
    guard_test_drop_runtime();
    CHECK(guard_test_reconcile() != 0, "restart refuses staged artifact with mutable mode\n");
    char reply[512];
    dispatch_once("GUARDSTATUS", reply, sizeof reply);
    CHECK(strcmp(reply, "ERR HOLD record\n") == 0, "tampered custody projects hold\n");

    setup();
    guard_test_drop_runtime();
    write_file_mode(CUSTODY "/a.apk", payload, sizeof payload - 1, 0400);
    CHECK(access(CUSTODY "/a.apk", F_OK) == 0, "orphan APK fixture exists\n");
    int orphan_reconcile = guard_test_reconcile();
    CHECK(orphan_reconcile != 0,
        "orphan APK without draft/manifest fails startup (got %d)\n", orphan_reconcile);
    dispatch_once("GUARDSTATUS", reply, sizeof reply);
    CHECK(strcmp(reply, "ERR HOLD retirement\n") == 0,
        "orphan residue without draft/manifest cannot project EMPTY (got %s)\n", reply);
    CHECK(unlink(CUSTODY "/a.apk") == 0, "remove orphan APK fixture\n");
    guard_test_drop_runtime();
    CHECK(guard_test_reconcile() == 0, "exact empty namespace restarts after orphan removal\n");

    CHECK(unlink(CUSTODY "/.owner.lock") == 0, "unlink held owner-lock name\n");
    write_file_mode(CUSTODY "/.owner.lock", "", 0, 0600);
    CHECK(guard_test_reconcile() != 0,
        "replaced owner-lock topology cannot initialize an EMPTY namespace\n");
    dispatch_once("GUARDSTATUS", reply, sizeof reply);
    CHECK(strcmp(reply, "ERR HOLD retirement\n") == 0,
        "replaced owner-lock topology cannot project EMPTY (got %s)\n", reply);

    guard_test_drop_runtime();
    CHECK(guard_test_reconcile() == 0, "new runtime acquires exact replacement owner lock\n");
    prepare();
    dispatch_once("GUARDSTATUS", reply, sizeof reply);
    char expected[512];
    snprintf(expected, sizeof expected,
        "OK GUARDSTATUS 1 STAGING %s %s NONE NONE 0 0 2 NONE NONE ", SESSION, boot_nonce);
    check_status_with_deadlines(reply, expected,
        "ordinary STAGING remains visible while empty-state proof is false");
}

static void test_atomic_draft_fault_and_owner_lock(void) {
    setup();
    char command[512], reply[512];
    prepare_command(command);
    guard_test_set_supervised(1);
    guard_test_set_fault(GUARD_TEST_FAULT_DRAFT_FILE_SYNC);
    dispatch_once(command, reply, sizeof reply);
    CHECK(strcmp(reply, "ERR IO draft\n") == 0, "draft fsync fault is visible\n");
    guard_test_set_fault(GUARD_TEST_FAULT_NONE);
    dispatch_once("GUARDSTATUS", reply, sizeof reply);
    CHECK(strcmp(reply, "OK GUARDSTATUS 0 EMPTY NONE NONE NONE NONE 0 0 0 NONE NONE 0 0\n") == 0,
        "failed draft publication grants no authority\n");

    int pipefd[2];
    CHECK(pipe(pipefd) == 0, "owner-lock result pipe\n");
    pid_t child = fork();
    CHECK(child >= 0, "fork owner-lock contender\n");
    if (child == 0) {
        close(pipefd[0]);
        guard_test_drop_runtime();
        int result = guard_test_reconcile();
        (void)write_all(pipefd[1], &result, sizeof result);
        close(pipefd[1]);
        _exit(0);
    }
    close(pipefd[1]);
    int child_result = 0;
    ssize_t received = read(pipefd[0], &child_result, sizeof child_result);
    close(pipefd[0]);
    int status;
    waitpid(child, &status, 0);
    CHECK(received == (ssize_t)sizeof child_result && child_result != 0,
        "second helper process cannot acquire custody owner lock\n");
}

static void test_nonreplacement_staging_dirsync_reconciliation(void) {
    char command[512], reply[1024], expected[1024];

    setup();
    prepare_command(command);
    guard_test_set_supervised(1);
    guard_test_set_fault(GUARD_TEST_FAULT_DRAFT_DIR_SYNC);
    dispatch_once(command, reply, sizeof reply);
    CHECK(strcmp(reply, "ERR INDETERMINATE draft\n") == 0,
        "post-rename draft dir-fsync fault is indeterminate (got %s)\n", reply);
    guard_test_set_fault(GUARD_TEST_FAULT_NONE);
    dispatch_once("GUARDSTATUS", reply, sizeof reply);
    snprintf(expected, sizeof expected,
        "OK GUARDSTATUS 1 STAGING %s %s NONE NONE 0 0 2 NONE NONE ",
        SESSION, boot_nonce);
    check_status_with_deadlines(reply, expected,
        "visible draft successor reconciles after dir-fsync ambiguity");
    guard_test_drop_runtime();
    CHECK(guard_test_reconcile() == 0,
        "new process accepts the exact visible draft after dir-fsync ambiguity\n");
    dispatch_once("GUARDSTATUS", reply, sizeof reply);
    check_status_with_deadlines(reply, expected,
        "draft publication loss is settled from status after restart");

    setup();
    prepare();
    static const char payload[] = "artifact-dirsync-a";
    char sha[65];
    hash_bytes(payload, sizeof payload - 1, sha);
    define_role("A", 1, sha, 568, 11, 14, 14, sizeof payload - 1, 2);
    guard_test_set_fault(GUARD_TEST_FAULT_ARTIFACT_DIR_SYNC);
    stream_role_expect("A", 2, sha, payload, sizeof payload - 1,
        "ERR INDETERMINATE artifact\n");
    guard_test_set_fault(GUARD_TEST_FAULT_NONE);
    CHECK(file_equals(CUSTODY "/a.apk", payload, sizeof payload - 1),
        "artifact final name contains exact bytes after dir-fsync ambiguity\n");
    dispatch_once("GUARDSTATUS", reply, sizeof reply);
    snprintf(expected, sizeof expected,
        "OK GUARDSTATUS 3 STAGING %s %s NONE NONE 0 0 2 NONE NONE ",
        SESSION, boot_nonce);
    check_status_with_deadlines(reply, expected,
        "status reconciles the exact visible artifact into one staged successor");
    snprintf(command, sizeof command, "GUARDSTREAM %s 3 A %zu %s",
        SESSION, sizeof payload - 1, sha);
    dispatch_once(command, reply, sizeof reply);
    CHECK(strcmp(reply, "OK GUARDSTREAM 3 STAGING\n") == 0,
        "status-settled artifact replay is idempotent without another upload (got %s)\n", reply);
    guard_test_drop_runtime();
    CHECK(guard_test_reconcile() == 0 && file_equals(
              CUSTODY "/a.apk", payload, sizeof payload - 1),
        "exact artifact retry publishes one reconciled staged successor\n");
}

static void begin_requested_replacement(void);

static int owner_lock_fd_count(void) {
    struct stat lock_st, fd_st;
    if (stat(CUSTODY "/.owner.lock", &lock_st) != 0) return -1;
    int count = 0;
    for (int fd = 0; fd < 1024; fd++)
        if (fstat(fd, &fd_st) == 0 && fd_st.st_dev == lock_st.st_dev &&
            fd_st.st_ino == lock_st.st_ino) count++;
    return count;
}

static void test_replacement_export_refuses_foreign_fd3(void) {
    guard_test_reset();
    int placeholder = open("/dev/null", O_RDONLY);
    CHECK(placeholder >= 0 && (placeholder == GUARD_REPLACEMENT_LEASE_FD ||
          dup2(placeholder, GUARD_REPLACEMENT_LEASE_FD) == GUARD_REPLACEMENT_LEASE_FD),
        "hold fd 3 so the owner lock lands elsewhere\n");
    if (placeholder != GUARD_REPLACEMENT_LEASE_FD) close(placeholder);
    setup();
    begin_requested_replacement();
    char nonce[65];
    CHECK(guard_maintenance_replacement_parent_grant(nonce) == GUARD_REPLACEMENT_READY,
        "foreign-fd3 fixture grants the replacement\n");
    int owner_fds = owner_lock_fd_count();
    CHECK(owner_fds == 1, "foreign-fd3 fixture holds one owner lock off fd 3 (got %d)\n", owner_fds);
    int foreign = open("/tmp/.hapaneld-guard-foreign-fd3", O_RDWR | O_CREAT | O_TRUNC, 0600);
    struct stat before, after;
    CHECK(foreign >= 0 && dup2(foreign, GUARD_REPLACEMENT_LEASE_FD) == GUARD_REPLACEMENT_LEASE_FD &&
          fstat(GUARD_REPLACEMENT_LEASE_FD, &before) == 0, "occupy fd 3 with a foreign file\n");
    CHECK(guard_maintenance_replacement_export_lease() != 0,
        "export refuses to overwrite a foreign descriptor on the lease fd\n");
    CHECK(fstat(GUARD_REPLACEMENT_LEASE_FD, &after) == 0 && after.st_dev == before.st_dev &&
          after.st_ino == before.st_ino, "foreign descriptor on the lease fd survives export\n");
    close(GUARD_REPLACEMENT_LEASE_FD);
    if (foreign >= 0 && foreign != GUARD_REPLACEMENT_LEASE_FD) close(foreign);
    unlink("/tmp/.hapaneld-guard-foreign-fd3");
    CHECK(guard_maintenance_replacement_parent_abort(nonce) == 0,
        "foreign-fd3 fixture aborts the granted replacement\n");
}

static void test_replacement_adopt_closes_previous_owner_fd(void) {
    guard_test_reset();
    int placeholder = open("/dev/null", O_RDONLY);
    CHECK(placeholder >= 0 && (placeholder == GUARD_REPLACEMENT_LEASE_FD ||
          dup2(placeholder, GUARD_REPLACEMENT_LEASE_FD) == GUARD_REPLACEMENT_LEASE_FD),
        "hold fd 3 so the owner lock lands elsewhere\n");
    if (placeholder != GUARD_REPLACEMENT_LEASE_FD) close(placeholder);
    setup();
    begin_requested_replacement();
    close(GUARD_REPLACEMENT_LEASE_FD);
    char nonce[65];
    CHECK(guard_maintenance_replacement_parent_grant(nonce) == GUARD_REPLACEMENT_READY &&
          guard_maintenance_replacement_export_lease() == 0,
        "adopt fixture grants and exports the owner lease\n");
    CHECK(owner_lock_fd_count() == 2, "export leaves the owner fd and its fd 3 copy\n");
    CHECK(guard_maintenance_replacement_stage_app(nonce) == 0 &&
          guard_maintenance_replacement_supervisor_adopt_app(nonce) == 0,
        "adopt fixture stages and adopts the inherited lease\n");
    int owner_fds = owner_lock_fd_count();
    CHECK(owner_fds == 1,
        "adopting the lease closes the previous owner-lock descriptor (got %d)\n", owner_fds);
    guard_test_set_supervised(1);
    CHECK(guard_maintenance_replacement_worker_commit_app(nonce) == 0,
        "adopt fixture commits the replacement\n");
}

static void test_replacement_fence_and_same_lease_app_swap(void) {
    setup();
    static const char old_helper[] = "old-app-helper-binary";
    static const char new_helper[] = "new-app-helper-binary";
    char new_sha[65];
    write_file_mode(HELPER_LIVE, old_helper, sizeof old_helper - 1, 0700);
    write_file_mode(HELPER_STAGE, new_helper, sizeof new_helper - 1, 0700);
    hash_bytes(new_helper, sizeof new_helper - 1, new_sha);
    guard_maintenance_set_supervisor_owner();

    int peer[2];
    CHECK(socketpair(AF_UNIX, SOCK_STREAM, 0, peer) == 0,
        "replacement retirement socket\n");
    pid_t child = fork();
    CHECK(child >= 0, "replacement retirement worker fork\n");
    if (child == 0) {
        close(peer[1]);
        char command[512];
        snprintf(command, sizeof command, "GUARDRETIRE APP %s %s %s",
            REPLACEMENT_NONCE, new_sha, REPLACEMENT_BUILD);
        conn_ctx ctx = { .fd = peer[0], .subscribed = 0, .caller = test_caller };
        dispatch(&ctx, command);
        _exit(99);
    }
    close(peer[0]);
    char reply[1024];
    read_line(peer[1], reply, sizeof reply);
    close(peer[1]);
    int status = 0;
    CHECK(waitpid(child, &status, 0) == child && WIFEXITED(status) &&
          WEXITSTATUS(status) == GUARD_REPLACEMENT_EXIT,
        "retirement worker exits only after durable fence\n");
    CHECK(strcmp(reply, "OK GUARDRETIRE 1 REQUESTED\n") == 0,
        "retirement reply identifies durable request (got %s)\n", reply);
    CHECK(access(SHARED_LOCK, F_OK) != 0,
        "durable replacement request releases the shared helper lock before retirement\n");

    char prepare_line[512];
    prepare_command(prepare_line);
    dispatch_once(prepare_line, reply, sizeof reply);
    CHECK(strcmp(reply, "ERR HOLD startup\n") == 0,
        "durable replacement fence blocks a racing PREPARE (got %s)\n", reply);

    char nonce[65];
    CHECK(guard_maintenance_replacement_parent_grant(nonce) == GUARD_REPLACEMENT_READY &&
          strcmp(nonce, REPLACEMENT_NONCE) == 0,
        "idle supervisor grants the exact durable request\n");
    CHECK(guard_maintenance_replacement_export_lease() == 0,
        "supervisor exports the same owner-lock open-file-description\n");
    int contender = open(CUSTODY "/.owner.lock", O_RDWR | O_NOFOLLOW | O_CLOEXEC);
    CHECK(contender >= 0 && flock(contender, LOCK_EX | LOCK_NB) != 0 &&
          (errno == EWOULDBLOCK || errno == EAGAIN),
        "independent contender cannot acquire during granted handoff\n");
    if (contender >= 0) close(contender);
    CHECK(guard_maintenance_replacement_stage_app(nonce) == 0,
        "staged helper backs up and atomically publishes exact new live bytes\n");
    CHECK(access(HELPER_STAGE, F_OK) != 0 && access(HELPER_PREVIOUS, F_OK) == 0,
        "swap leaves exact prior backup and consumes the stage\n");
    CHECK(guard_maintenance_replacement_supervisor_adopt_app(nonce) == 0,
        "new supervisor adopts the inherited lease and SWAPPED record\n");
    guard_test_set_supervised(1);
    CHECK(guard_maintenance_replacement_worker_commit_app(nonce) == 0,
        "listening worker commits replacement and cleans exact prior backup\n");
    CHECK(access(HELPER_PREVIOUS, F_OK) != 0 &&
          access(CUSTODY "/replacement.v1", F_OK) != 0,
        "terminal replacement cleanup leaves no stale fence or backup\n");
    contender = open(CUSTODY "/.owner.lock", O_RDWR | O_NOFOLLOW | O_CLOEXEC);
    CHECK(contender >= 0 && flock(contender, LOCK_EX | LOCK_NB) != 0 &&
          (errno == EWOULDBLOCK || errno == EAGAIN),
        "new supervised lineage continuously retains the same lease\n");
    if (contender >= 0) close(contender);
}

static void test_replacement_shared_lock_admission(void) {
    setup();
    static const char old_helper[] = "shared-lock-old-app-helper";
    static const char new_helper[] = "shared-lock-new-app-helper";
    char new_sha[65], command[512], reply[512];
    write_file_mode(HELPER_LIVE, old_helper, sizeof old_helper - 1, 0700);
    write_file_mode(HELPER_STAGE, new_helper, sizeof new_helper - 1, 0700);
    hash_bytes(new_helper, sizeof new_helper - 1, new_sha);
    write_shared_lock_pid(getpid());
    snprintf(command, sizeof command, "GUARDRETIRE APP %s %s %s",
        REPLACEMENT_NONCE, new_sha, REPLACEMENT_BUILD);
    dispatch_once(command, reply, sizeof reply);
    CHECK(strcmp(reply, "ERR BUSY replacement\n") == 0 &&
          access(CUSTODY "/replacement.v1", F_OK) != 0 &&
          file_equals(HELPER_STAGE, new_helper, sizeof new_helper - 1),
        "live shared helper lock blocks before replacement authority or stage mutation (got %s)\n",
        reply);

    setup();
    write_file_mode(HELPER_LIVE, old_helper, sizeof old_helper - 1, 0700);
    write_file_mode(HELPER_STAGE, new_helper, sizeof new_helper - 1, 0700);
    CHECK(mkdir(SHARED_LOCK, 0700) == 0,
        "create ambiguous shared helper-lock fixture\n");
    dispatch_once(command, reply, sizeof reply);
    CHECK(strcmp(reply, "ERR HOLD replacement\n") == 0 &&
          access(CUSTODY "/replacement.v1", F_OK) != 0 &&
          access(SHARED_LOCK, F_OK) == 0,
        "ambiguous shared helper lock fails closed without reclamation (got %s)\n", reply);

    setup();
    write_file_mode(HELPER_LIVE, old_helper, sizeof old_helper - 1, 0700);
    write_file_mode(HELPER_STAGE, new_helper, sizeof new_helper - 1, 0700);
    pid_t dead = fork();
    CHECK(dead >= 0, "fork stale shared-lock holder fixture\n");
    if (dead == 0) _exit(0);
    int status = 0;
    CHECK(waitpid(dead, &status, 0) == dead && WIFEXITED(status),
        "reap stale shared-lock holder fixture\n");
    write_shared_lock_pid(dead);
    snprintf(command, sizeof command, "GUARDRETIRE APP %s %064d %s",
        REPLACEMENT_NONCE, 0, REPLACEMENT_BUILD);
    dispatch_once(command, reply, sizeof reply);
    CHECK(strcmp(reply, "ERR HOLD replacement\n") == 0 &&
          access(CUSTODY "/replacement.v1", F_OK) != 0 &&
          access(SHARED_LOCK, F_OK) != 0 &&
          file_equals(HELPER_STAGE, new_helper, sizeof new_helper - 1),
        "dead-PID shared lock is reclaimed and released on validation failure (got %s)\n", reply);
}

static void test_replacement_respects_installstream_package_gate(void) {
    setup();
    static const char old_helper[] = "gate-old-app-helper";
    static const char new_helper[] = "gate-new-app-helper";
    char new_sha[65], command[512], reply[512];
    write_file_mode(HELPER_LIVE, old_helper, sizeof old_helper - 1, 0700);
    write_file_mode(HELPER_STAGE, new_helper, sizeof new_helper - 1, 0700);
    hash_bytes(new_helper, sizeof new_helper - 1, new_sha);
    CHECK(guard_maintenance_install_begin() == 0,
        "legacy package mutation acquires the shared gate\n");
    snprintf(command, sizeof command, "GUARDRETIRE APP %s %s %s",
        REPLACEMENT_NONCE, new_sha, REPLACEMENT_BUILD);
    dispatch_once(command, reply, sizeof reply);
    CHECK(strcmp(reply, "ERR BUSY replacement\n") == 0 &&
          access(CUSTODY "/replacement.v1", F_OK) != 0,
        "replacement cannot publish or retire while INSTALLSTREAM owns package mutation (got %s)\n",
        reply);
    guard_maintenance_install_end();
}

static void begin_requested_replacement_build(const char *build) {
    static const char old_helper[] = "cut-old-app-helper-binary";
    static const char new_helper[] = "cut-new-app-helper-binary";
    char new_sha[65];
    write_file_mode(HELPER_LIVE, old_helper, sizeof old_helper - 1, 0700);
    write_file_mode(HELPER_STAGE, new_helper, sizeof new_helper - 1, 0700);
    hash_bytes(new_helper, sizeof new_helper - 1, new_sha);
    guard_maintenance_set_supervisor_owner();
    int peer[2];
    CHECK(socketpair(AF_UNIX, SOCK_STREAM, 0, peer) == 0,
        "cut retirement socket\n");
    pid_t child = fork();
    CHECK(child >= 0, "cut retirement fork\n");
    if (child == 0) {
        close(peer[1]);
        char command[512];
        snprintf(command, sizeof command, "GUARDRETIRE APP %s %s %s",
            REPLACEMENT_NONCE, new_sha, build);
        conn_ctx ctx = { .fd = peer[0], .subscribed = 0, .caller = test_caller };
        dispatch(&ctx, command);
        _exit(99);
    }
    close(peer[0]);
    char reply[256];
    read_line(peer[1], reply, sizeof reply);
    close(peer[1]);
    int status = 0;
    CHECK(waitpid(child, &status, 0) == child && WIFEXITED(status) &&
          WEXITSTATUS(status) == GUARD_REPLACEMENT_EXIT &&
          strcmp(reply, "OK GUARDRETIRE 1 REQUESTED\n") == 0,
        "cut fixture publishes retirement fence\n");
}

static void begin_requested_replacement(void) {
    begin_requested_replacement_build(REPLACEMENT_BUILD);
}

static void begin_granted_replacement(char nonce[65]) {
    begin_requested_replacement();
    CHECK(guard_maintenance_replacement_parent_grant(nonce) == GUARD_REPLACEMENT_READY &&
          guard_maintenance_replacement_export_lease() == 0,
        "cut fixture grants and exports owner lease\n");
}

static void stale_fixture(int granted) {
    guard_test_drop_runtime();
    (void)unlink(CUSTODY "/replacement.v1");
    (void)unlink(CUSTODY "/.replacement.v1.tmp");
    setup();
    if (granted) {
        char nonce[65];
        begin_granted_replacement(nonce);
        close(GUARD_REPLACEMENT_LEASE_FD);
    } else begin_requested_replacement();
    guard_test_drop_runtime();
    static const char next_boot[] = "123e4567-e89b-12d3-a456-426614174001";
    write_file_mode(BOOT_FILE, next_boot, sizeof next_boot - 1, 0600);
}

static void test_stale_replacement_refusal_matrix(void) {
    for (int which = 0; which < 16; which++) {
        stale_fixture(which & 1);
        switch (which) {
        case 0: {
            static const char same_boot[] = "123e4567-e89b-12d3-a456-426614174000";
            write_file_mode(BOOT_FILE, same_boot, sizeof same_boot - 1, 0600); break;
        }
        case 1: write_file_mode(BOOT_FILE, "invalid", 7, 0600); break;
        case 2: write_file_mode(CUSTODY "/manifest.v1", "owned", 5, 0600); break;
        case 3: write_file_mode(CUSTODY "/.replacement.v1.tmp", "owned", 5, 0600); break;
        case 4: write_file_mode(HELPER_PREVIOUS, "owned", 5, 0700); break;
        case 5: write_file_mode(HELPER_PREVIOUS ".tmp", "owned", 5, 0700); break;
        case 6: write_file_mode(HELPER_LIVE, "bad-old-app-helper-binary",
                               sizeof "bad-old-app-helper-binary" - 1, 0700); break;
        case 7: write_file_mode(HELPER_STAGE, "bad-new-app-helper-binary",
                               sizeof "bad-new-app-helper-binary" - 1, 0700); break;
        case 8: CHECK(chmod(HELPER_STAGE, 0600) == 0, "stale stage mode fixture\n"); break;
        case 9: write_file_mode(CUSTODY "/replacement.v1", "invalid", 7, 0600); break;
        case 10: CHECK(chmod(CUSTODY "/.owner.lock", 0644) == 0,
                       "stale owner mode fixture\n"); break;
        case 11: CHECK(unlink(HELPER_LIVE) == 0 && mkfifo(HELPER_LIVE, 0700) == 0,
                       "stale live FIFO fixture\n"); break;
        case 12: CHECK(unlink(HELPER_STAGE) == 0 && mkfifo(HELPER_STAGE, 0700) == 0,
                       "stale stage FIFO fixture\n"); break;
        case 13: write_shared_lock_pid(getpid()); break;
        case 14: CHECK(link(HELPER_STAGE, HELPER_PREVIOUS) == 0,
                       "stale hardlink fixture\n"); break;
        case 15: CHECK(chmod(CUSTODY "/replacement.v1", 0644) == 0,
                       "stale journal mode fixture\n"); break;
        }
        CHECK(guard_maintenance_reconcile_stale_replacement() < 0 &&
              access(CUSTODY "/replacement.v1", F_OK) == 0,
            "stale recovery holds unsafe or conflicting custody case=%d\n", which);
        if (which == 10) {
            struct stat owner;
            CHECK(lstat(CUSTODY "/.owner.lock", &owner) == 0 &&
                  (owner.st_mode & 0777) == 0644,
                "stale recovery does not repair unsafe owner metadata\n");
        }
        if (which == 13) { unlink(SHARED_LOCK_PID); rmdir(SHARED_LOCK); }
        unlink(HELPER_PREVIOUS ".tmp");
    }
    (void)unlink(CUSTODY "/replacement.v1");
    (void)unlink(CUSTODY "/.replacement.v1.tmp");
}

static void test_stale_replacement_live_owner_and_postswap_hold(void) {
    stale_fixture(0);
    int owned = open(CUSTODY "/.owner.lock", O_RDWR | O_NOFOLLOW | O_CLOEXEC);
    CHECK(owned >= 0 && flock(owned, LOCK_EX | LOCK_NB) == 0,
        "stale external owner acquires custody\n");
    CHECK(guard_maintenance_reconcile_stale_replacement() < 0 &&
          access(CUSTODY "/replacement.v1", F_OK) == 0,
        "stale recovery cannot retire a live owner's journal\n");
    if (owned >= 0) close(owned);
    for (int swapped = 0; swapped < 2; swapped++) {
        guard_test_drop_runtime();
        (void)unlink(CUSTODY "/replacement.v1");
        setup();
        char nonce[65];
        begin_granted_replacement(nonce);
        guard_test_set_fault(swapped ? GUARD_TEST_FAULT_REPLACEMENT_AFTER_SWAP :
                                       GUARD_TEST_FAULT_REPLACEMENT_AFTER_BACKUP);
        CHECK(guard_maintenance_replacement_stage_app(nonce) != 0,
            "stale post-swap refusal fixture cut\n");
        guard_test_set_fault(GUARD_TEST_FAULT_NONE);
        close(GUARD_REPLACEMENT_LEASE_FD);
        guard_test_drop_runtime();
        static const char next_boot[] = "123e4567-e89b-12d3-a456-426614174001";
        write_file_mode(BOOT_FILE, next_boot, sizeof next_boot - 1, 0600);
        CHECK(guard_maintenance_reconcile_stale_replacement() < 0 &&
              access(CUSTODY "/replacement.v1", F_OK) == 0 &&
              access(HELPER_PREVIOUS, F_OK) == 0,
            "stale recovery preserves backup-durable and swapped custody phase=%d\n", swapped);
    }
}

static void test_stale_replacement_durability(void) {
    const enum guard_test_fault cuts[] = { GUARD_TEST_FAULT_STALE_REPLACEMENT_UNLINK,
                                          GUARD_TEST_FAULT_STALE_REPLACEMENT_DIR_SYNC };
    for (size_t i = 0; i < sizeof cuts / sizeof cuts[0]; i++) {
        stale_fixture(1);
        guard_test_set_fault(cuts[i]);
        CHECK(guard_maintenance_reconcile_stale_replacement() < 0,
            "stale cleanup never reports success before durable journal retirement cut=%zu\n", i);
        CHECK(file_equals(HELPER_LIVE, "cut-old-app-helper-binary", sizeof "cut-old-app-helper-binary" - 1) &&
              file_equals(HELPER_STAGE, "cut-new-app-helper-binary", sizeof "cut-new-app-helper-binary" - 1),
            "stale cleanup faults preserve both helper binaries\n");
        guard_test_set_fault(GUARD_TEST_FAULT_NONE);
        guard_test_drop_runtime();
        CHECK(guard_maintenance_reconcile_stale_replacement() == (i == 0 ? 1 : 0),
            "stale cleanup retries only exact remaining journal or exact empty namespace\n");
    }
}

static void test_replacement_backup_cut_recovers_exact_old(void) {
    setup();
    char nonce[65];
    begin_granted_replacement(nonce);
    guard_test_set_fault(GUARD_TEST_FAULT_REPLACEMENT_BACKUP_DIR_SYNC);
    CHECK(guard_maintenance_replacement_stage_app(nonce) != 0 &&
          access(HELPER_LIVE, F_OK) == 0 && access(HELPER_STAGE, F_OK) == 0 &&
          access(HELPER_PREVIOUS, F_OK) == 0,
        "BACKUP_DURABLE cut leaves explicit old-live new-stage prior-backup topology\n");
    guard_test_set_fault(GUARD_TEST_FAULT_REPLACEMENT_ABORT_AFTER_PREVIOUS);
    guard_test_drop_runtime();
    char recovered[65];
    CHECK(guard_maintenance_replacement_startup_reconcile_app(recovered) < 0 &&
          access(HELPER_PREVIOUS, F_OK) != 0 &&
          access(CUSTODY "/replacement.v1", F_OK) == 0,
        "abort cut after previous unlink retains the durable BACKUP_DURABLE fence\n");
    guard_test_set_fault(GUARD_TEST_FAULT_NONE);
    guard_test_drop_runtime();
    CHECK(guard_maintenance_replacement_startup_reconcile_app(recovered) == 0 &&
          recovered[0] == '\0',
        "startup deterministically aborts pre-swap backup cut to exact old\n");
    CHECK(access(HELPER_PREVIOUS, F_OK) != 0 &&
          access(CUSTODY "/replacement.v1", F_OK) != 0 &&
          access(HELPER_LIVE, F_OK) == 0 && access(HELPER_STAGE, F_OK) == 0,
        "old recovery cleans only the exact backup and fence\n");
}

static void test_replacement_build_mismatch_aborts_before_swap(void) {
    setup();
    begin_requested_replacement_build(WRONG_REPLACEMENT_BUILD);
    char nonce[65];
    CHECK(guard_maintenance_replacement_parent_grant(nonce) == GUARD_REPLACEMENT_READY &&
          guard_maintenance_replacement_export_lease() == 0,
        "wrong-build fixture reaches exact granted stage\n");
    CHECK(guard_maintenance_replacement_stage_app(nonce) != 0 &&
          access(HELPER_LIVE, F_OK) == 0 && access(HELPER_STAGE, F_OK) == 0 &&
          access(HELPER_PREVIOUS, F_OK) != 0,
        "staged process build mismatch refuses before backup or live swap\n");
    guard_test_drop_runtime();
    char recovered[65];
    CHECK(guard_maintenance_replacement_startup_reconcile_app(recovered) == 0 &&
          access(CUSTODY "/replacement.v1", F_OK) != 0 &&
          access(HELPER_PREVIOUS, F_OK) != 0,
        "startup aborts a pre-swap build mismatch to exact old live\n");
}

static void test_replacement_swap_cut_resumes_exact_new(void) {
    setup();
    char nonce[65];
    begin_granted_replacement(nonce);
    guard_test_set_fault(GUARD_TEST_FAULT_REPLACEMENT_AFTER_SWAP);
    CHECK(guard_maintenance_replacement_stage_app(nonce) != 0 &&
          access(HELPER_STAGE, F_OK) != 0 && access(HELPER_PREVIOUS, F_OK) == 0,
        "swap cut leaves new-live prior-backup topology\n");
    guard_test_set_fault(GUARD_TEST_FAULT_NONE);
    guard_test_drop_runtime();
    char recovered[65];
    CHECK(guard_maintenance_replacement_startup_reconcile_app(recovered) == 1 &&
          strcmp(recovered, REPLACEMENT_NONCE) == 0,
        "startup binds swap cut to exact new replacement nonce\n");
    guard_maintenance_set_supervisor_owner();
    CHECK(guard_maintenance_replacement_export_lease() == 0 &&
          guard_maintenance_replacement_supervisor_adopt_app(recovered) == 0,
        "restarted new supervisor adopts the recovered owner lease\n");
    guard_test_set_supervised(1);
    CHECK(guard_maintenance_replacement_worker_commit_app(recovered) == 0,
        "restarted listening worker commits recovered exact new topology\n");
    CHECK(access(HELPER_PREVIOUS, F_OK) != 0 &&
          access(CUSTODY "/replacement.v1", F_OK) != 0,
        "resumed exact-new replacement cleans terminal artifacts\n");
}

static void test_replacement_request_dirsync_is_indeterminate_and_reconciled(void) {
    setup();
    static const char old_helper[] = "dirsync-old-app-helper";
    static const char new_helper[] = "dirsync-new-app-helper";
    char new_sha[65];
    write_file_mode(HELPER_LIVE, old_helper, sizeof old_helper - 1, 0700);
    write_file_mode(HELPER_STAGE, new_helper, sizeof new_helper - 1, 0700);
    hash_bytes(new_helper, sizeof new_helper - 1, new_sha);
    guard_maintenance_set_supervisor_owner();
    guard_test_set_fault(GUARD_TEST_FAULT_REPLACEMENT_DIR_SYNC);
    int peer[2];
    CHECK(socketpair(AF_UNIX, SOCK_STREAM, 0, peer) == 0,
        "request dirsync retirement socket\n");
    pid_t child = fork();
    CHECK(child >= 0, "request dirsync worker fork\n");
    if (child == 0) {
        close(peer[1]);
        char command[512];
        snprintf(command, sizeof command, "GUARDRETIRE APP %s %s %s",
            REPLACEMENT_NONCE, new_sha, REPLACEMENT_BUILD);
        conn_ctx ctx = { .fd = peer[0], .subscribed = 0, .caller = test_caller };
        dispatch(&ctx, command);
        _exit(99);
    }
    close(peer[0]);
    char reply[512];
    read_line(peer[1], reply, sizeof reply);
    close(peer[1]);
    int status = 0;
    CHECK(waitpid(child, &status, 0) == child && WIFEXITED(status) &&
          WEXITSTATUS(status) == 99,
        "dirsync-uncertain request does not authorize worker retirement\n");
    CHECK(strcmp(reply, "ERR INDETERMINATE replacement\n") == 0 &&
          access(CUSTODY "/replacement.v1", F_OK) == 0,
        "post-rename request dirsync reports indeterminate with durable fence (got %s)\n", reply);
    char prepare_line[512];
    prepare_command(prepare_line);
    dispatch_once(prepare_line, reply, sizeof reply);
    CHECK(strcmp(reply, "ERR HOLD startup\n") == 0,
        "indeterminate request fence still blocks PREPARE\n");
    guard_test_set_fault(GUARD_TEST_FAULT_NONE);
    guard_test_drop_runtime();
    char recovered[65];
    CHECK(guard_maintenance_replacement_startup_reconcile_app(recovered) == 0 &&
          access(CUSTODY "/replacement.v1", F_OK) != 0 &&
          access(HELPER_LIVE, F_OK) == 0 && access(HELPER_STAGE, F_OK) == 0,
        "startup aborts indeterminate pre-grant request to exact old\n");
}

static void test_replacement_record_dirsync_cuts_reconcile(void) {
    setup();
    char nonce[65];
    begin_granted_replacement(nonce);
    guard_test_set_fault(GUARD_TEST_FAULT_REPLACEMENT_BACKUP_DIR_SYNC);
    CHECK(guard_maintenance_replacement_stage_app(nonce) != 0,
        "BACKUP_DURABLE post-rename dirsync cut is indeterminate\n");
    guard_test_set_fault(GUARD_TEST_FAULT_NONE);
    guard_test_drop_runtime();
    char recovered[65];
    CHECK(guard_maintenance_replacement_startup_reconcile_app(recovered) == 0 &&
          access(HELPER_PREVIOUS, F_OK) != 0 && access(HELPER_STAGE, F_OK) == 0,
        "BACKUP_DURABLE dirsync cut deterministically restores exact-old topology\n");

    setup();
    begin_granted_replacement(nonce);
    guard_test_set_fault(GUARD_TEST_FAULT_REPLACEMENT_SWAPPED_DIR_SYNC);
    CHECK(guard_maintenance_replacement_stage_app(nonce) != 0,
        "SWAPPED post-rename dirsync cut is indeterminate\n");
    guard_test_set_fault(GUARD_TEST_FAULT_NONE);
    guard_test_drop_runtime();
    CHECK(guard_maintenance_replacement_startup_reconcile_app(recovered) == 1 &&
          strcmp(recovered, REPLACEMENT_NONCE) == 0,
        "SWAPPED dirsync cut resumes only exact-new topology\n");
    guard_maintenance_set_supervisor_owner();
    CHECK(guard_maintenance_replacement_export_lease() == 0 &&
          guard_maintenance_replacement_supervisor_adopt_app(recovered) == 0,
        "recovered SWAPPED dirsync cut adopts same owner lease\n");
    guard_test_set_supervised(1);
    CHECK(guard_maintenance_replacement_worker_commit_app(recovered) == 0 &&
          access(HELPER_PREVIOUS, F_OK) != 0 &&
          access(CUSTODY "/replacement.v1", F_OK) != 0,
        "recovered SWAPPED dirsync cut reaches committed exact new\n");
}

static void finish_recovered_new_replacement(char nonce[65]) {
    guard_maintenance_set_supervisor_owner();
    CHECK(guard_maintenance_replacement_export_lease() == 0 &&
          guard_maintenance_replacement_supervisor_adopt_app(nonce) == 0,
        "temp-cut exact new adopts recovered owner lease\n");
    guard_test_set_supervised(1);
    CHECK(guard_maintenance_replacement_worker_commit_app(nonce) == 0,
        "temp-cut exact new reaches listening-worker commit\n");
}

static void test_replacement_synced_temp_cuts_reconcile(void) {
    setup();
    static const char old_helper[] = "temp-old-app-helper";
    static const char new_helper[] = "temp-new-app-helper";
    char new_sha[65];
    write_file_mode(HELPER_LIVE, old_helper, sizeof old_helper - 1, 0700);
    write_file_mode(HELPER_STAGE, new_helper, sizeof new_helper - 1, 0700);
    hash_bytes(new_helper, sizeof new_helper - 1, new_sha);
    guard_maintenance_set_supervisor_owner();
    guard_test_set_fault(GUARD_TEST_FAULT_REPLACEMENT_REQUESTED_TMP_SYNCED);
    int peer[2];
    CHECK(socketpair(AF_UNIX, SOCK_STREAM, 0, peer) == 0, "REQUESTED temp socket\n");
    pid_t child = fork();
    CHECK(child >= 0, "REQUESTED temp fork\n");
    if (child == 0) {
        close(peer[1]);
        char command[512];
        snprintf(command, sizeof command, "GUARDRETIRE APP %s %s %s",
            REPLACEMENT_NONCE, new_sha, REPLACEMENT_BUILD);
        conn_ctx ctx = { .fd = peer[0], .subscribed = 0, .caller = test_caller };
        dispatch(&ctx, command);
        _exit(99);
    }
    close(peer[0]);
    char reply[256];
    read_line(peer[1], reply, sizeof reply);
    close(peer[1]);
    int status = 0;
    CHECK(waitpid(child, &status, 0) == child && WIFEXITED(status) &&
          WEXITSTATUS(status) == 99 &&
          strcmp(reply, "ERR INDETERMINATE replacement\n") == 0 &&
          access(CUSTODY "/.replacement.v1.tmp", F_OK) == 0,
        "REQUESTED synced temp remains a fenced indeterminate cut\n");
    guard_test_set_fault(GUARD_TEST_FAULT_NONE);
    guard_test_drop_runtime();
    char recovered[65];
    CHECK(guard_maintenance_replacement_startup_reconcile_app(recovered) == 0 &&
          access(CUSTODY "/.replacement.v1.tmp", F_OK) != 0 &&
          access(CUSTODY "/replacement.v1", F_OK) != 0,
        "REQUESTED synced temp promotes then safely aborts exact old\n");

    setup();
    begin_requested_replacement();
    guard_test_set_fault(GUARD_TEST_FAULT_REPLACEMENT_GRANTED_TMP_SYNCED);
    CHECK(guard_maintenance_replacement_parent_grant(recovered) == GUARD_REPLACEMENT_HOLD &&
          access(CUSTODY "/.replacement.v1.tmp", F_OK) == 0,
        "GRANTED synced temp is not misreported committed\n");
    guard_test_set_fault(GUARD_TEST_FAULT_NONE);
    guard_test_drop_runtime();
    CHECK(guard_maintenance_replacement_startup_reconcile_app(recovered) == 0 &&
          access(CUSTODY "/.replacement.v1.tmp", F_OK) != 0,
        "GRANTED synced temp promotes then safely aborts exact old\n");

    setup();
    char nonce[65];
    begin_granted_replacement(nonce);
    guard_test_set_fault(GUARD_TEST_FAULT_REPLACEMENT_BACKUP_TMP_SYNCED);
    CHECK(guard_maintenance_replacement_stage_app(nonce) != 0 &&
          access(CUSTODY "/.replacement.v1.tmp", F_OK) == 0,
        "BACKUP_DURABLE synced temp interrupts before swap\n");
    guard_test_set_fault(GUARD_TEST_FAULT_NONE);
    guard_test_drop_runtime();
    CHECK(guard_maintenance_replacement_startup_reconcile_app(recovered) == 0 &&
          access(HELPER_PREVIOUS, F_OK) != 0 && access(HELPER_STAGE, F_OK) == 0,
        "BACKUP_DURABLE synced temp promotes then restores exact old\n");

    setup();
    begin_granted_replacement(nonce);
    guard_test_set_fault(GUARD_TEST_FAULT_REPLACEMENT_SWAPPED_TMP_SYNCED);
    CHECK(guard_maintenance_replacement_stage_app(nonce) != 0 &&
          access(CUSTODY "/.replacement.v1.tmp", F_OK) == 0,
        "SWAPPED synced temp interrupts after exact new publish\n");
    guard_test_set_fault(GUARD_TEST_FAULT_NONE);
    guard_test_drop_runtime();
    CHECK(guard_maintenance_replacement_startup_reconcile_app(recovered) == 1 &&
          strcmp(recovered, REPLACEMENT_NONCE) == 0,
        "SWAPPED synced temp promotes and resumes exact new\n");
    finish_recovered_new_replacement(recovered);
    CHECK(access(CUSTODY "/.replacement.v1.tmp", F_OK) != 0 &&
          access(CUSTODY "/replacement.v1", F_OK) != 0,
        "SWAPPED synced temp reconciliation reaches clean terminal state\n");
}

static void test_replacement_stage_durability_precedes_fence(void) {
    static const char old_helper[] = "stage-sync-old-helper";
    static const char new_helper[] = "stage-sync-new-helper";
    char new_sha[65], command[512], reply[512];
    enum guard_test_fault faults[] = {
        GUARD_TEST_FAULT_REPLACEMENT_STAGE_FILE_SYNC,
        GUARD_TEST_FAULT_REPLACEMENT_STAGE_DIR_SYNC,
    };
    for (size_t index = 0; index < sizeof faults / sizeof faults[0]; index++) {
        setup();
        write_file_mode(HELPER_LIVE, old_helper, sizeof old_helper - 1, 0700);
        write_file_mode(HELPER_STAGE, new_helper, sizeof new_helper - 1, 0700);
        hash_bytes(new_helper, sizeof new_helper - 1, new_sha);
        guard_test_set_fault(faults[index]);
        snprintf(command, sizeof command, "GUARDRETIRE APP %s %s %s",
            REPLACEMENT_NONCE, new_sha, REPLACEMENT_BUILD);
        dispatch_once(command, reply, sizeof reply);
        CHECK(strcmp(reply, "ERR HOLD replacement\n") == 0 &&
              access(CUSTODY "/replacement.v1", F_OK) != 0 &&
              access(CUSTODY "/.replacement.v1.tmp", F_OK) != 0,
            "stage file+parent durability failure refuses before fence (got %s)\n", reply);
        guard_test_set_fault(GUARD_TEST_FAULT_NONE);
    }
}

static void test_cancel_only_exact_staging(void) {
    setup();
    prepare();
    char command[256], reply[512];
    snprintf(command, sizeof command, "GUARDCANCEL %s 1", SESSION);
    dispatch_once(command, reply, sizeof reply);
    CHECK(strcmp(reply, "OK GUARDCANCEL 2 EMPTY\n") == 0, "staging cancel exact\n");
    dispatch_once("GUARDSTATUS", reply, sizeof reply);
    CHECK(strcmp(reply, "OK GUARDSTATUS 0 EMPTY NONE NONE NONE NONE 0 0 0 NONE NONE 0 0\n") == 0,
        "cancel returns to durable empty\n");

    enum guard_test_fault faults[] = {
        GUARD_TEST_FAULT_CANCEL_AFTER_ARTIFACT,
        GUARD_TEST_FAULT_CANCEL_AFTER_DRAFT,
    };
    for (size_t index = 0; index < sizeof faults / sizeof faults[0]; index++) {
        setup();
        prepare();
        static const char a_payload[] = "cancel-owned-a";
        static const char b_payload[] = "cancel-owned-b";
        char a_sha[65], b_sha[65];
        hash_bytes(a_payload, sizeof a_payload - 1, a_sha);
        hash_bytes(b_payload, sizeof b_payload - 1, b_sha);
        define_role("A", 1, a_sha, 568, 11, 14, 14, sizeof a_payload - 1, 2);
        define_role("B", 2, b_sha, 569, 11, 15, 15, sizeof b_payload - 1, 3);
        stream_role("A", 3, a_sha, a_payload, sizeof a_payload - 1, 4);
        stream_role("B", 4, b_sha, b_payload, sizeof b_payload - 1, 5);
        stream_role("SETTINGS", 5, SETTINGS_AUTHORITY_SHA, SETTINGS_AUTHORITY,
            sizeof SETTINGS_AUTHORITY - 1, 6);
        guard_test_set_fault(faults[index]);
        snprintf(command, sizeof command, "GUARDCANCEL %s 6", SESSION);
        dispatch_once(command, reply, sizeof reply);
        CHECK(strcmp(reply, "ERR INDETERMINATE cancel\n") == 0 &&
              access(CUSTODY "/capture.v1", F_OK) == 0,
            "cancel cut retains its durable draft-bound intent (fault %d, got %s)\n",
            faults[index], reply);
        if (faults[index] == GUARD_TEST_FAULT_CANCEL_AFTER_ARTIFACT) {
            CHECK(access(CUSTODY "/draft.v1", F_OK) == 0 &&
                  access(CUSTODY "/a.apk", F_OK) != 0 &&
                  access(CUSTODY "/b.apk", F_OK) == 0,
                "mid-artifact cancel cut leaves an exactly resumable partial inventory\n");
        } else {
            CHECK(access(CUSTODY "/draft.v1", F_OK) != 0 &&
                  access(CUSTODY "/capture.v1", F_OK) == 0,
                "post-draft cancel cut leaves only the durable completion marker\n");
        }
        guard_test_set_fault(GUARD_TEST_FAULT_NONE);
        guard_test_drop_runtime();
        CHECK(guard_test_reconcile() == 0,
            "new process completes exact cancellation after fault %d\n", faults[index]);
        dispatch_once("GUARDSTATUS", reply, sizeof reply);
        CHECK(strcmp(reply,
              "OK GUARDSTATUS 0 EMPTY NONE NONE NONE NONE 0 0 0 NONE NONE 0 0\n") == 0 &&
              access(CUSTODY "/draft.v1", F_OK) != 0 &&
              access(CUSTODY "/capture.v1", F_OK) != 0 &&
              access(CUSTODY "/a.apk", F_OK) != 0 &&
              access(CUSTODY "/b.apk", F_OK) != 0 &&
              access(CUSTODY "/settings.v2", F_OK) != 0,
            "reconciled cancel cut reaches durable empty (fault %d, got %s)\n",
            faults[index], reply);
    }
}

static void prepare_capture_recovery(void) {
    setup();
    unsigned char database[128] = {0};
    memcpy(database, "SQLite format 3", 15);
    database[15] = 0;
    database[63] = 14;
    char db_sha[65];
    write_file_mode(DB_PATH, database, sizeof database, 0600);
    prepare_exact_baseline(database, sizeof database, db_sha);

    static const char a_payload[] = "capture-cut-installed-a";
    static const char b_payload[] = "capture-cut-candidate-b";
    char a_sha[65], b_sha[65];
    hash_bytes(a_payload, sizeof a_payload - 1, a_sha);
    hash_bytes(b_payload, sizeof b_payload - 1, b_sha);
    define_role("A", 1, a_sha, 568, 11, 14, 14, sizeof a_payload - 1, 2);
    define_role("B", 2, b_sha, 569, 11, 15, 15, sizeof b_payload - 1, 3);
    stream_role("A", 3, a_sha, a_payload, sizeof a_payload - 1, 4);
    stream_role("B", 4, b_sha, b_payload, sizeof b_payload - 1, 5);
    stream_role("SETTINGS", 5, SETTINGS_AUTHORITY_SHA, SETTINGS_AUTHORITY,
        sizeof SETTINGS_AUTHORITY - 1, 6);
    write_file_mode(INSTALLED_APK, a_payload, sizeof a_payload - 1, 0600);
    write_file_mode(DB_PATH "-wal", "", 0, 0600);
    write_file_mode(DB_PATH "-shm", "owned-checkpoint-shm", 20, 0600);
    guard_test_set_supervised(1);
}

/* Run the handler failure through the same tick/deadline/start/complete seam as main. */
static void test_failed_capture_recovers_once(int mode) {
    prepare_capture_recovery();
    guard_test_set_fault(GUARD_TEST_FAULT_MANIFEST_FILE_SYNC);
    char command[1024], reply[1024];
    snprintf(command, sizeof command, "GUARDACTION %s 6 CAPTURE_BASELINE", SESSION);
    dispatch_once(command, reply, sizeof reply);
    CHECK(strcmp(reply, "ERR INDETERMINATE capture\n") == 0,
        "failed capture reaches supervisor recovery\n");
    guard_maintenance_set_supervisor_owner();
    int work = guard_maintenance_supervisor_tick();
    CHECK(work == GUARD_WORK_LAUNCH_A && access(CUSTODY "/manifest.v1", F_OK) != 0,
        "failed publication authorizes only incumbent recovery without manifest\n");
    guard_test_set_fault(GUARD_TEST_FAULT_NONE);
    uint64_t deadline = 0;
    CHECK(guard_maintenance_supervisor_work_deadline(work, &deadline) == 0,
        "failed capture recovery retains its original bounded deadline\n");
    uint64_t forbidden_deadline;
    CHECK(guard_maintenance_supervisor_work_deadline(GUARD_WORK_INSTALL_A, &forbidden_deadline) < 0 &&
          guard_maintenance_supervisor_work_deadline(GUARD_WORK_INSTALL_B, &forbidden_deadline) < 0 &&
          guard_maintenance_supervisor_work_deadline(GUARD_WORK_LAUNCH_B, &forbidden_deadline) < 0,
        "draft recovery grants neither install nor candidate launch authority\n");
    if (mode == 1) sysexec_stub_fail_run("monkey -p", -1);
    if (mode == 3) guard_test_set_now_ms(deadline);
    if (mode == 4) write_file_mode(INSTALLED_APK, "replaced", 8, 0600);
    pid_t executor = -1;
    int started = guard_maintenance_supervisor_work_deadline(work, &deadline) == 0
        ? guard_maintenance_supervisor_start_work(work, &executor) : -1;
    CHECK((started == 0) == (mode != 1 && mode != 3 && mode != 4),
        "recovery starts only an exact unexpired incumbent (mode %d)\n", mode);
    if (mode == 5) {
        guard_test_drop_runtime();
        CHECK(guard_test_reconcile() == 0, "restart reconciles consumed recovery authority\n");
        guard_maintenance_set_supervisor_owner();
    } else {
        enum guard_execution_result result = started != 0 ? GUARD_EXEC_NOT_STARTED
            : (mode == 2 ? GUARD_EXEC_TIMED_OUT : GUARD_EXEC_REAPED);
        CHECK(guard_maintenance_supervisor_complete(work, result, mode == 6 ? 256 : 0) == GUARD_WORK_NONE,
            "recovery settles success, failed spawn, timeout, expiry or launch failure (mode %d)\n", mode);
    }
    CHECK(guard_maintenance_supervisor_tick() == GUARD_WORK_NONE &&
          guard_maintenance_supervisor_tick() == GUARD_WORK_NONE,
        "settled recovery is never replayed (mode %d)\n", mode);
    CHECK(guard_maintenance_supervisor_start_work(GUARD_WORK_LAUNCH_A, &executor) < 0,
        "consumed launch authority cannot start a second executor\n");
    const char *const launch[] = {
        "monkey", "-p", APP_PKG, "-c", "android.intent.category.LAUNCHER", "1", NULL,
    };
    CHECK(sysexec_stub_count_argv("/system/bin/monkey", launch, 1) ==
          (mode == 3 || mode == 4 ? 0 : 1),
        "exact incumbent receives at most one launch attempt (mode %d)\n", mode);
    CHECK(access(CUSTODY "/manifest.v1", F_OK) != 0,
        "recovery never fabricates maintenance authority\n");
}

static void test_capture_intent_reconciles_after_process_cut(enum guard_test_fault fault) {
    static const char b_payload[] = "capture-cut-candidate-b";
    prepare_capture_recovery();
    guard_test_set_fault(fault);

    char command[1024], reply[1024], expected[1024];
    snprintf(command, sizeof command, "GUARDACTION %s 6 CAPTURE_BASELINE", SESSION);
    dispatch_once(command, reply, sizeof reply);
    const char *expected_error = fault == GUARD_TEST_FAULT_CAPTURE_INTENT_DIR_SYNC
        ? "ERR INDETERMINATE capture_intent\n" : "ERR INDETERMINATE capture\n";
    CHECK(strcmp(reply, expected_error) == 0,
        "capture publication failure is explicitly indeterminate (fault %d, got %s)\n",
        fault, reply);
    if (fault == GUARD_TEST_FAULT_MANIFEST_FILE_SYNC ||
        fault == GUARD_TEST_FAULT_BASELINE_DIR_SYNC) {
        dispatch_once("GUARDSTATUS", reply, sizeof reply);
        snprintf(expected, sizeof expected,
            "OK GUARDSTATUS 6 STAGING %s %s NONE NONE 0 0 2 CAPTURE_INTENT NONE ",
            SESSION, boot_nonce);
        check_status_with_deadlines(reply, expected,
            "durable capture intent remains authoritative before manifest publication");
    }

    guard_test_set_fault(GUARD_TEST_FAULT_NONE);
    guard_test_drop_runtime();
    CHECK(guard_test_reconcile() == 0,
        "new helper process admits the exact draft+capture authority tuple\n");
    guard_maintenance_set_supervisor_owner();
    int next = GUARD_WORK_NONE;
    for (int attempt = 0; attempt < 3 && next == GUARD_WORK_NONE; attempt++)
        next = guard_maintenance_supervisor_tick();
    CHECK(next == GUARD_WORK_INSTALL_B,
        "reconciled capture cut advances to one durable B work item (fault %d)\n", fault);
    dispatch_once("GUARDSTATUS", reply, sizeof reply);
    snprintf(expected, sizeof expected,
        "OK GUARDSTATUS 8 SUBMITTED_B %s %s NONE NONE 0 0 2 NONE NONE ",
        SESSION, boot_nonce);
    check_status_with_deadlines(reply, expected,
        "capture restart reaches the same exact SUBMITTED_B successor");

    pid_t executor = -1;
    CHECK(guard_maintenance_supervisor_start_work(GUARD_WORK_INSTALL_B, &executor) == 0,
        "reconciled supervisor records exact spawned B executor identity\n");
    write_file_mode(INSTALLED_APK, b_payload, sizeof b_payload - 1, 0600);
    guard_test_drop_runtime();
    guard_test_set_pm_process_state(-1);
    CHECK(guard_test_reconcile() == 0,
        "replacement supervisor loads the correlated executor receipt\n");
    guard_maintenance_set_supervisor_owner();
    CHECK(guard_maintenance_supervisor_tick() == GUARD_WORK_NONE,
        "lost supervisor executor fails closed despite exact B visibility\n");
    const char *const install[] = {
        "pm", "install", "-r", "-d", CUSTODY "/b.apk", NULL,
    };
    CHECK(sysexec_stub_count_argv("/system/bin/pm", install, 1) == 1,
        "supervisor restart never duplicates correlated B submission\n");
    dispatch_once("GUARDSTATUS", reply, sizeof reply);
    CHECK(strstr(reply, " AMBIGUOUS ") != NULL &&
          strstr(reply, " EXECUTOR_UNKNOWN AMBIGUOUS ") != NULL,
        "client-loss ambiguity is durable and explicit (got %s)\n", reply);
}

static void test_forward_deadline_blocks_late_executor_spawn(void) {
    setup();
    guard_test_set_now_ms(100000);
    unsigned char database[128] = {0};
    memcpy(database, "SQLite format 3", 15);
    database[15] = 0;
    database[63] = 14;
    char db_sha[65];
    write_file_mode(DB_PATH, database, sizeof database, 0600);
    prepare_exact_baseline(database, sizeof database, db_sha);
    static const char a_payload[] = "deadline-installed-a";
    static const char b_payload[] = "deadline-candidate-b";
    char a_sha[65], b_sha[65];
    hash_bytes(a_payload, sizeof a_payload - 1, a_sha);
    hash_bytes(b_payload, sizeof b_payload - 1, b_sha);
    define_role("A", 1, a_sha, 568, 11, 14, 14, sizeof a_payload - 1, 2);
    define_role("B", 2, b_sha, 569, 11, 15, 15, sizeof b_payload - 1, 3);
    stream_role("A", 3, a_sha, a_payload, sizeof a_payload - 1, 4);
    stream_role("B", 4, b_sha, b_payload, sizeof b_payload - 1, 5);
    stream_role("SETTINGS", 5, SETTINGS_AUTHORITY_SHA, SETTINGS_AUTHORITY,
        sizeof SETTINGS_AUTHORITY - 1, 6);
    write_file_mode(INSTALLED_APK, a_payload, sizeof a_payload - 1, 0600);
    guard_test_set_supervised(1);
    char command[1024], reply[1024];
    snprintf(command, sizeof command, "GUARDACTION %s 6 CAPTURE_BASELINE", SESSION);
    dispatch_once(command, reply, sizeof reply);
    CHECK(strcmp(reply, "OK GUARDACTION 7 PREPARED\n") == 0,
        "deadline fixture captures at fixed monotonic time\n");

    guard_maintenance_set_supervisor_owner();
    guard_test_set_now_ms(519999);
    int work = guard_maintenance_supervisor_tick();
    uint64_t deadline = 0;
    CHECK(work == GUARD_WORK_INSTALL_B &&
          guard_maintenance_supervisor_work_deadline(
              (enum guard_supervisor_work)work, &deadline) == 0 && deadline == 520000,
        "B work is bound to the immutable derived forward deadline\n");
    guard_test_set_now_ms(520000);
    pid_t executor = -1;
    CHECK(guard_maintenance_supervisor_start_work(
            (enum guard_supervisor_work)work, &executor) != 0,
        "forward deadline equality refuses executor spawn\n");
    const char *const install[] = {
        "pm", "install", "-r", "-d", CUSTODY "/b.apk", NULL,
    };
    CHECK(sysexec_stub_count_argv("/system/bin/pm", install, 1) == 0,
        "no PM argv crosses forward deadline equality\n");
    CHECK(guard_maintenance_supervisor_complete(
            GUARD_WORK_INSTALL_B, GUARD_EXEC_NOT_STARTED, 0) == GUARD_WORK_NONE,
        "proved not-started executor transitions without ambiguity\n");
    dispatch_once("GUARDSTATUS", reply, sizeof reply);
    CHECK(strstr(reply, " 1000000 520000\n") != NULL,
        "hard and forward deadlines remain immutable across failed spawn\n");
    guard_test_set_now_ms(0);
}

static void test_hard_and_recovery_deadline_equality(void) {
    char a_sha[65], b_sha[65], reply[1024];
    reach_prepared_at(100000, a_sha, b_sha);
    guard_maintenance_set_supervisor_owner();
    guard_test_set_now_ms(1000000);
    CHECK(guard_maintenance_supervisor_tick() == GUARD_WORK_NONE,
        "hard-deadline equality starts no forward or recovery executor\n");
    dispatch_once("GUARDSTATUS", reply, sizeof reply);
    CHECK(strstr(reply, " 8 ROLLBACK_REQUIRED ") != NULL &&
          strstr(reply, " OVERALL_TIMEOUT NONE ") != NULL,
        "hard-deadline equality is durably classified before recovery (got %s)\n", reply);
    CHECK(guard_maintenance_supervisor_tick() == GUARD_WORK_NONE,
        "recovery-deadline equality starts no rollback executor\n");
    dispatch_once("GUARDSTATUS", reply, sizeof reply);
    CHECK(strstr(reply, " 9 AMBIGUOUS ") != NULL &&
          strstr(reply, " RECOVERY_TIMEOUT AMBIGUOUS ") != NULL,
        "recovery-deadline equality settles to explicit HOLD ambiguity (got %s)\n", reply);
    const char *const install_a[] = {
        "pm", "install", "-r", "-d", CUSTODY "/a.apk", NULL,
    };
    const char *const install_b[] = {
        "pm", "install", "-r", "-d", CUSTODY "/b.apk", NULL,
    };
    CHECK(sysexec_stub_count_argv("/system/bin/pm", install_a, 1) == 0 &&
          sysexec_stub_count_argv("/system/bin/pm", install_b, 1) == 0,
        "deadline equalities issue zero PM submissions\n");
    guard_test_set_now_ms(0);
}

static void test_boot_change_holds_draft_and_manifest(void) {
    static const char changed_boot[] = "223e4567-e89b-12d3-a456-426614174000";
    char reply[1024];

    setup();
    prepare();
    write_file_mode(BOOT_FILE, changed_boot, sizeof changed_boot - 1, 0600);
    guard_test_drop_runtime();
    CHECK(guard_test_reconcile() != 0,
        "boot change rejects a prior same-boot draft\n");
    dispatch_once("GUARDSTATUS", reply, sizeof reply);
    CHECK(strcmp(reply, "ERR HOLD record\n") == 0,
        "prior-boot draft projects HOLD (got %s)\n", reply);

    char a_sha[65], b_sha[65];
    reach_prepared_at(100000, a_sha, b_sha);
    write_file_mode(BOOT_FILE, changed_boot, sizeof changed_boot - 1, 0600);
    guard_test_drop_runtime();
    CHECK(guard_test_reconcile() != 0,
        "boot change rejects an armed manifest+journal tuple\n");
    guard_maintenance_set_supervisor_owner();
    CHECK(guard_maintenance_supervisor_tick() < 0,
        "prior-boot armed authority performs no autonomous work\n");
    dispatch_once("GUARDSTATUS", reply, sizeof reply);
    CHECK(strcmp(reply, "ERR HOLD record\n") == 0,
        "prior-boot manifest projects HOLD (got %s)\n", reply);
    const char *const install_b[] = {
        "pm", "install", "-r", "-d", CUSTODY "/b.apk", NULL,
    };
    CHECK(sysexec_stub_count_argv("/system/bin/pm", install_b, 1) == 0,
        "boot change performs zero PM mutation\n");
    guard_test_set_now_ms(0);
}

static void test_capture_manifest_journal_and_b_install(int final_mode) {
    setup();
    if (final_mode >= 2 && final_mode <= 5) guard_test_set_now_ms(100000);
    unsigned char database[128] = {0};
    memcpy(database, "SQLite format 3", 15);
    database[15] = 0;
    database[63] = 14;
    char db_sha[65];
    write_file_mode(DB_PATH, database, sizeof database, 0600);
    prepare_exact_baseline(database, sizeof database, db_sha);

    static const char a_payload[] = "exact-installed-a";
    static const char b_payload[] = "exact-candidate-b";
    char a_sha[65], b_sha[65];
    hash_bytes(a_payload, sizeof a_payload - 1, a_sha);
    hash_bytes(b_payload, sizeof b_payload - 1, b_sha);
    define_role("A", 1, a_sha, 568, 11, 14, 14, sizeof a_payload - 1, 2);
    define_role("B", 2, b_sha, 569, 11, 15, 15, sizeof b_payload - 1, 3);
    stream_role("A", 3, a_sha, a_payload, sizeof a_payload - 1, 4);
    stream_role("B", 4, b_sha, b_payload, sizeof b_payload - 1, 5);
    stream_role("SETTINGS", 5, SETTINGS_AUTHORITY_SHA, SETTINGS_AUTHORITY,
        sizeof SETTINGS_AUTHORITY - 1, 6);
    write_file_mode(INSTALLED_APK, a_payload, sizeof a_payload - 1, 0600);
    guard_test_set_supervised(1);

    char command[1024], reply[1024];
    snprintf(command, sizeof command, "GUARDACTION %s 6 CAPTURE_BASELINE", SESSION);
    dispatch_once(command, reply, sizeof reply);
    CHECK(strcmp(reply, "OK GUARDACTION 7 PREPARED\n") == 0,
        "capture transfers authority to durable supervisor journal (got %s)\n", reply);
    const char *const stop[] = { "am", "force-stop", APP_PKG, NULL };
    const char *const install[] = {
        "pm", "install", "-r", "-d", CUSTODY "/b.apk", NULL,
    };
    const char *const launch[] = {
        "monkey", "-p", APP_PKG, "-c",
        "android.intent.category.LAUNCHER", "1", NULL,
    };
    CHECK(sysexec_stub_count_argv("/system/bin/am", stop, 0) == 1,
        "capture force-stops exact package once\n");
    guard_maintenance_set_supervisor_owner();
    int work = guard_maintenance_supervisor_tick();
    CHECK(work == GUARD_WORK_INSTALL_B, "supervisor journals and requests exact B install\n");
    pid_t executor = -1;
    CHECK(guard_maintenance_supervisor_start_work((enum guard_supervisor_work)work, &executor) == 0 &&
          executor > 1, "supervisor starts fixed B executor\n");
    CHECK(sysexec_stub_count_argv("/system/bin/pm", install, 1) == 1,
        "journaled install uses exact fixed B custody path\n");
    write_file_mode(INSTALLED_APK, b_payload, sizeof b_payload - 1, 0600);
    work = guard_maintenance_supervisor_complete(GUARD_WORK_INSTALL_B, GUARD_EXEC_REAPED, 0);
    CHECK(work == GUARD_WORK_LAUNCH_B, "exact settled B produces one launch work item\n");
    CHECK(guard_maintenance_supervisor_start_work((enum guard_supervisor_work)work, &executor) == 0,
        "supervisor starts fixed B launch\n");
    CHECK(guard_maintenance_supervisor_complete(GUARD_WORK_LAUNCH_B, GUARD_EXEC_REAPED, 0) == 0,
        "successful B launch receipt preserves health wait\n");
    CHECK(sysexec_stub_count_argv("/system/bin/monkey", launch, 1) == 1,
        "only B is launched after exact install settles\n");

    dispatch_once("GUARDSTATUS", reply, sizeof reply);
    char expected[1024];
    snprintf(expected, sizeof expected,
        "OK GUARDSTATUS 9 WAIT_B_HEALTH %s %s B %s 569 15 2 NONE NONE ",
        SESSION, boot_nonce, b_sha);
    check_status_with_deadlines(reply, expected, "durable WAIT_B_HEALTH status exact");
    struct stat st;
    CHECK(stat(CUSTODY "/baseline.db", &st) == 0 && (st.st_mode & 0777) == 0400 &&
          (size_t)st.st_size == sizeof database,
        "baseline custody is immutable exact bytes\n");
    CHECK(access(DB_PATH "-wal", F_OK) != 0 && access(DB_PATH "-shm", F_OK) != 0,
        "capture canonicalizes only proven owned zero-WAL/SHM sidecars\n");
    CHECK(access(CUSTODY "/draft.v1", F_OK) != 0 &&
          stat(CUSTODY "/manifest.v1", &st) == 0 && (st.st_mode & 0777) == 0600 &&
          stat(CUSTODY "/journal.v1", &st) == 0 && (st.st_mode & 0777) == 0600,
        "manifest+journal replace staging authority durably\n");

    guard_test_drop_runtime();
    CHECK(guard_test_reconcile() == 0, "new worker reconciles exact armed tuple\n");
    dispatch_once("GUARDSTATUS", reply, sizeof reply);
    check_status_with_deadlines(reply, expected,
        "restart preserves WAIT_B_HEALTH without PM replay");
    CHECK(sysexec_stub_count_argv("/system/bin/pm", install, 1) == 1,
        "restart performs no duplicate B install\n");

    /* The app-controlled maintenance proof checkpoints and closes before HEALTH. The helper
     * requires the resulting main-file v15 plus absent/zero WAL, then reads only an immutable copy. */
    database[63] = 15;
    write_file_mode(DB_PATH, database, sizeof database, 0600);
    write_file_mode(DB_PATH "-wal", "", 0, 0600);
    write_file_mode(DB_PATH "-shm", "owned-b-checkpoint-shm", 22, 0600);
    sysexec_stub_add_popen(
        "PRAGMA query_only=ON",
        "15\nok\n1\n2\n"
        "636F6E666967|616C706861|74657874|V6F6E65|1\n"
        "6F74686572|62657461|74657874|N|2\n",
        0);
    snprintf(command, sizeof command,
        "GUARDHEALTH %s 9 %s B %s 569 15 OK 2 %s %s PRESENT NA",
        SESSION, boot_nonce, b_sha, STATE_SHA, SETTINGS_SHA);
    if (final_mode == 3) {
        guard_test_set_now_ms(220000);
        dispatch_once(command, reply, sizeof reply);
        CHECK(strcmp(reply, "OK GUARDHEALTH 10 ROLLBACK_REQUIRED\n") == 0,
            "B HEALTH arriving at deadline equality cannot beat timeout (got %s)\n", reply);
        dispatch_once("GUARDSTATUS", reply, sizeof reply);
        CHECK(strstr(reply, " 10 ROLLBACK_REQUIRED ") != NULL &&
              strstr(reply, " HEALTH_TIMEOUT NONE ") != NULL,
            "B health equality durably enters rollback (got %s)\n", reply);
        guard_test_set_now_ms(0);
        return;
    }
    dispatch_once(command, reply, sizeof reply);
    CHECK(strcmp(reply, "OK GUARDHEALTH 10 B_HEALTHY\n") == 0,
        "B health requires exact identity, schema, semantics and probe (got %s)\n", reply);
    dispatch_once(command, reply, sizeof reply);
    CHECK(strcmp(reply, "OK GUARDHEALTH 10 B_HEALTHY\n") == 0,
        "lost B-health reply is exactly idempotent\n");
    dispatch_once("GUARDSTATUS", reply, sizeof reply);
    snprintf(expected, sizeof expected,
        "OK GUARDSTATUS 10 B_HEALTHY %s %s B %s 569 15 2 NONE NONE ",
        SESSION, boot_nonce, b_sha);
    check_status_with_deadlines(reply, expected, "durable B_HEALTHY status exact");
    guard_maintenance_set_supervisor_owner();
    CHECK(guard_maintenance_supervisor_tick() == GUARD_WORK_NONE,
        "empty B_HEALTHY awaits explicit WITHHOLD approval\n");
    if (final_mode == 6) {
        char active_evidence[GUARD_EVIDENCE_MAX_BYTES], active_sha[65];
        snprintf(command, sizeof command, "GUARDEVIDENCE %s", SESSION);
        dispatch_once(command, active_evidence, sizeof active_evidence);
        hash_bytes(active_evidence, strlen(active_evidence), active_sha);
        snprintf(command, sizeof command, "GUARDRETIRE TERMINAL %s 10 %s",
            SESSION, active_sha);
        dispatch_once(command, reply, sizeof reply);
        CHECK(strcmp(reply, "ERR STATE retirement\n") == 0,
            "active transaction cannot retire terminal evidence (got %s)\n", reply);
    }

    unsigned char premigrate[sizeof database];
    memcpy(premigrate, database, sizeof premigrate);
    premigrate[63] = 14;
    write_file_mode(DB_PATH ".v14.premigrate", premigrate, sizeof premigrate, 0600);
    guard_test_set_supervised(1);
    snprintf(command, sizeof command, "GUARDACTION %s 10 WITHHOLD_PREMIGRATE", SESSION);
    if (final_mode == 0) {
        guard_test_set_fault(GUARD_TEST_FAULT_WITHHOLD_AFTER_INTENT);
        dispatch_once(command, reply, sizeof reply);
        CHECK(strcmp(reply, "ERR INDETERMINATE premigrate\n") == 0,
            "durable WITHHOLD intent cut is indeterminate (got %s)\n", reply);
        CHECK(access(CUSTODY "/premigrate.db", F_OK) != 0 &&
              access(CUSTODY "/b-primary.db", F_OK) != 0,
            "WITHHOLD intent is durable before custody publication\n");
        guard_test_set_fault(GUARD_TEST_FAULT_NONE);
        guard_test_drop_runtime();
        CHECK(guard_test_reconcile() == 0,
            "new process admits the exact WITHHOLD intent without client retry\n");
        guard_maintenance_set_supervisor_owner();
        guard_test_set_fault(GUARD_TEST_FAULT_WITHHOLD_AFTER_FORCE_STOP);
        CHECK(guard_maintenance_supervisor_tick() == GUARD_WORK_NONE &&
              access(CUSTODY "/premigrate.db", F_OK) != 0 &&
              access(CUSTODY "/b-primary.db", F_OK) != 0,
            "supervisor resumes through the post-force-stop WITHHOLD cut\n");
        guard_test_set_fault(GUARD_TEST_FAULT_NONE);
        guard_test_drop_runtime();
        CHECK(guard_test_reconcile() == 0,
            "post-force-stop WITHHOLD cut is restart-reconcilable\n");
        guard_maintenance_set_supervisor_owner();
        guard_test_set_fault(GUARD_TEST_FAULT_PREMIGRATE_DIR_SYNC);
        CHECK(guard_maintenance_supervisor_tick() == GUARD_WORK_NONE &&
              access(CUSTODY "/premigrate.db", F_OK) == 0 &&
              access(CUSTODY "/b-primary.db", F_OK) != 0,
            "supervisor resumes through the first custody-publication cut\n");
        guard_test_set_fault(GUARD_TEST_FAULT_NONE);
        guard_test_drop_runtime();
        CHECK(guard_test_reconcile() == 0,
            "first custody-publication cut is restart-reconcilable\n");
        guard_maintenance_set_supervisor_owner();
        guard_test_set_fault(GUARD_TEST_FAULT_RECOVERY_CUSTODY_PUBLISHED);
        CHECK(guard_maintenance_supervisor_tick() == GUARD_WORK_NONE &&
              access(CUSTODY "/premigrate.db", F_OK) == 0 &&
              access(CUSTODY "/b-primary.db", F_OK) == 0,
            "supervisor resumes WITHHOLD through the second-publication crash cut\n");
        guard_test_set_fault(GUARD_TEST_FAULT_NONE);
        guard_test_drop_runtime();
        CHECK(guard_test_reconcile() == 0,
            "new process admits both custody files under the durable WITHHOLD intent\n");
        guard_maintenance_set_supervisor_owner();
        CHECK(guard_maintenance_supervisor_tick() == GUARD_WORK_NONE,
            "supervisor advances the recovered WITHHOLD intent without HTTP retry\n");
        dispatch_once("GUARDSTATUS", reply, sizeof reply);
        CHECK(strstr(reply, " 11 RECOVERY_WITHHELD ") != NULL,
            "recovered WITHHOLD intent reaches its durable successor (got %s)\n",
            reply);
        guard_test_set_supervised(1);
    } else {
        dispatch_once(command, reply, sizeof reply);
        CHECK(strcmp(reply, "OK GUARDACTION 11 RECOVERY_WITHHELD\n") == 0,
            "withhold seals exact premigrate and B-primary before runtime removal (got %s)\n",
            reply);
    }
    guard_maintenance_set_supervisor_owner();
    work = guard_maintenance_supervisor_tick();
    CHECK(work == GUARD_WORK_LAUNCH_B,
        "supervisor removes exact live premigrate and requests B maintenance relaunch\n");
    CHECK(guard_maintenance_supervisor_start_work((enum guard_supervisor_work)work, &executor) == 0 &&
          guard_maintenance_supervisor_complete(GUARD_WORK_LAUNCH_B, GUARD_EXEC_REAPED, 0) == 0,
        "B maintenance relaunch settles WAIT_A_REFUSAL\n");
    CHECK(access(DB_PATH ".v14.premigrate", F_OK) != 0,
        "premigrate is outside runtime discovery while refusal is attempted\n");
    CHECK(access(CUSTODY "/premigrate.db", R_OK) == 0 &&
          access(CUSTODY "/b-primary.db", R_OK) == 0,
        "exact premigrate and B-primary remain in root custody\n");
    CHECK(access(DB_PATH "-wal", F_OK) != 0 && access(DB_PATH "-shm", F_OK) != 0,
        "withhold canonicalizes checkpoint sidecars before sealing B primary\n");
    dispatch_once("GUARDSTATUS", reply, sizeof reply);
    snprintf(expected, sizeof expected,
        "OK GUARDSTATUS 12 WAIT_A_REFUSAL %s %s B %s 569 15 2 NONE NONE ",
        SESSION, boot_nonce, b_sha);
    check_status_with_deadlines(reply, expected, "WAIT_A_REFUSAL truthfully reports installed B");

    snprintf(command, sizeof command,
        "GUARDREFUSAL %s 12 %s A %s 568 PRIMARY_ABOVE_MAXIMUM_WITHOUT_PREMIGRATE",
        SESSION, boot_nonce, a_sha);
    guard_test_set_supervised(1);
    if (final_mode == 4) {
        guard_test_set_now_ms(220000);
        dispatch_once(command, reply, sizeof reply);
        CHECK(strcmp(reply, "ERR HOLD refusal\n") == 0,
            "refusal at deadline equality is rejected before A_REFUSED (got %s)\n", reply);
        guard_maintenance_set_supervisor_owner();
        CHECK(guard_maintenance_supervisor_tick() == GUARD_WORK_NONE,
            "refusal deadline equality schedules no forward work\n");
        dispatch_once("GUARDSTATUS", reply, sizeof reply);
        CHECK(strstr(reply, " 13 ROLLBACK_REQUIRED ") != NULL &&
              strstr(reply, " REFUSAL_TIMEOUT NONE ") != NULL,
            "refusal equality durably enters rollback (got %s)\n", reply);
        guard_test_set_now_ms(0);
        return;
    }
    dispatch_once(command, reply, sizeof reply);
    CHECK(strcmp(reply, "OK GUARDREFUSAL 13 A_REFUSED\n") == 0,
        "typed A candidate refusal is accepted while exact B remains installed (got %s)\n", reply);
    dispatch_once("GUARDSTATUS", reply, sizeof reply);
    snprintf(expected, sizeof expected,
        "OK GUARDSTATUS 13 A_REFUSED %s %s B %s 569 15 2 NONE NONE ",
        SESSION, boot_nonce, b_sha);
    check_status_with_deadlines(reply, expected, "A_REFUSED still truthfully reports installed B");

    /* WITHHOLD is admitted only from B_HEALTHY: a late one cannot re-seal recovery custody. */
    snprintf(command, sizeof command, "GUARDACTION %s 13 WITHHOLD_PREMIGRATE", SESSION);
    dispatch_once(command, reply, sizeof reply);
    CHECK(strcmp(reply, "ERR STALE withhold\n") == 0,
        "withhold after the A refusal is stale (got %s)\n", reply);
    dispatch_once("GUARDSTATUS", reply, sizeof reply);
    check_status_with_deadlines(reply, expected, "stale WITHHOLD leaves A_REFUSED unchanged");
    guard_maintenance_set_supervisor_owner();
    CHECK(guard_maintenance_supervisor_tick() == GUARD_WORK_NONE,
        "empty A_REFUSED awaits explicit RESTORE approval\n");

    guard_test_set_supervised(1);
    snprintf(command, sizeof command, "GUARDACTION %s 13 RESTORE_PREMIGRATE", SESSION);
    if (final_mode == 0) {
        guard_test_set_fault(GUARD_TEST_FAULT_JOURNAL_FILE_SYNC);
        dispatch_once(command, reply, sizeof reply);
        CHECK(strcmp(reply, "ERR HOLD restore\n") == 0,
            "pre-intent journal cut leaves RESTORE uncommitted (got %s)\n", reply);
        guard_test_set_fault(GUARD_TEST_FAULT_RESTORE_AFTER_INTENT);
        dispatch_once(command, reply, sizeof reply);
        CHECK(strcmp(reply, "ERR INDETERMINATE restore\n") == 0,
            "post-intent RESTORE cut is indeterminate (got %s)\n", reply);
        guard_test_set_fault(GUARD_TEST_FAULT_NONE);
        guard_test_drop_runtime();
        CHECK(guard_test_reconcile() == 0,
            "new process admits exact RESTORE intent without client retry\n");
        guard_maintenance_set_supervisor_owner();
        static const enum guard_test_fault restore_faults[] = {
            GUARD_TEST_FAULT_RESTORE_AFTER_FORCE_STOP,
            GUARD_TEST_FAULT_RESTORE_TEMP_CREATED,
            GUARD_TEST_FAULT_RESTORE_FILE_SYNC,
            GUARD_TEST_FAULT_RESTORE_RENAME,
            GUARD_TEST_FAULT_RESTORE_DIR_SYNC,
        };
        for (size_t fault = 0;
             fault < sizeof restore_faults / sizeof restore_faults[0]; fault++) {
            guard_test_set_fault(restore_faults[fault]);
            int cut_work = guard_maintenance_supervisor_tick();
            CHECK(cut_work == GUARD_WORK_NONE,
                "RESTORE crash cut %zu emits no premature package work (got %d)\n",
                fault, cut_work);
            guard_test_set_fault(GUARD_TEST_FAULT_NONE);
            guard_test_drop_runtime();
            CHECK(guard_test_reconcile() == 0,
                "RESTORE crash cut %zu is restart-reconcilable\n", fault);
            guard_maintenance_set_supervisor_owner();
        }
        CHECK(guard_maintenance_supervisor_tick() == GUARD_WORK_NONE,
            "supervisor completes exact RESTORE intent without HTTP retry\n");
        dispatch_once("GUARDSTATUS", reply, sizeof reply);
        CHECK(strstr(reply, " 14 RECOVERY_RESTORED ") != NULL,
            "recovered RESTORE intent reaches its durable successor (got %s)\n",
            reply);
    } else {
        dispatch_once(command, reply, sizeof reply);
        CHECK(strcmp(reply, "OK GUARDACTION 14 RECOVERY_RESTORED\n") == 0,
            "restore republishes the exact sealed premigrate before A submission (got %s)\n",
            reply);
    }
    CHECK(access(DB_PATH ".v14.premigrate", R_OK) == 0,
        "exact premigrate is visible to the ordinary A admission path\n");

    guard_maintenance_set_supervisor_owner();
    work = guard_maintenance_supervisor_tick();
    CHECK(work == GUARD_WORK_INSTALL_A,
        "supervisor journals one exact A submission only after recovery is restored\n");
    const char *const install_a[] = {
        "pm", "install", "-r", "-d", CUSTODY "/a.apk", NULL,
    };
    CHECK(guard_maintenance_supervisor_start_work((enum guard_supervisor_work)work, &executor) == 0 &&
          executor > 1, "supervisor starts fixed A executor\n");
    CHECK(sysexec_stub_count_argv("/system/bin/pm", install_a, 1) == 1,
        "journaled A install uses exact fixed custody path once\n");
    write_file_mode(INSTALLED_APK, a_payload, sizeof a_payload - 1, 0600);
    work = guard_maintenance_supervisor_complete(GUARD_WORK_INSTALL_A, GUARD_EXEC_REAPED, 0);
    CHECK(work == GUARD_WORK_LAUNCH_A, "exact settled A produces one launch work item\n");
    CHECK(guard_maintenance_supervisor_start_work((enum guard_supervisor_work)work, &executor) == 0 &&
          guard_maintenance_supervisor_complete(GUARD_WORK_LAUNCH_A, GUARD_EXEC_REAPED, 0) == 0,
        "exact A launch settles WAIT_A_HEALTH\n");

    /* Model A's durable recovery result: v14 is the promoted primary and the sealed v15
     * candidate primary is retained as the superseded artifact. */
    write_file_mode(DB_PATH, premigrate, sizeof premigrate, 0600);
    write_file_mode(DB_PATH ".v15.superseded", database, sizeof database, 0600);
    (void)unlink(DB_PATH "-wal");
    char b_primary_sha[65], premigrate_sha[65];
    hash_bytes(database, sizeof database, b_primary_sha);
    hash_bytes(premigrate, sizeof premigrate, premigrate_sha);
    write_restore_receipt("RESTORED", 15, sizeof database, b_primary_sha,
        14, sizeof premigrate, premigrate_sha, 16);
    CHECK(guard_test_final_restore_receipt_exact() == 0,
        "exact app RESTORED receipt matches WAIT_A_HEALTH authority\n");
    static const struct {
        const char *needle;
        const char *replacement;
    } receipt_mutants[] = {
        { "STATE RESTORED", "STATE PREPARED" },
        { "SOURCE 15 128", "SOURCE +15 128" },
        { "SOURCE 15 128", "SOURCE 015 128" },
        { "SOURCE 15 128", "SOURCE 15  128" },
        { "SOURCE 15 128", "SOURCE -4294967281 128" },
        { "STAGED 14 128", "STAGED 14 18446744073709551616" },
        { "GUARD " SESSION " 16", "GUARD " SESSION " 15" },
    };
    for (size_t index = 0;
         index < sizeof receipt_mutants / sizeof receipt_mutants[0]; index++) {
        mutate_restore_receipt(
            receipt_mutants[index].needle, receipt_mutants[index].replacement);
        CHECK(guard_test_final_restore_receipt_exact() != 0,
            "canonical receipt parser rejects checksummed grammar/semantic mutant %zu\n",
            index);
        write_restore_receipt("RESTORED", 15, sizeof database, b_primary_sha,
            14, sizeof premigrate, premigrate_sha, 16);
    }
    CHECK(chmod(DB_DIR "/.ha-paneld.db.restore.v1", 04600) == 0 &&
          guard_test_final_restore_receipt_exact() != 0,
        "restore receipt rejects special mode bits\n");
    CHECK(chmod(DB_DIR "/.ha-paneld.db.restore.v1", 0600) == 0,
        "restore receipt mode is restored after mutant\n");
    write_restore_receipt("RESTORED", 15, sizeof database, b_primary_sha,
        14, sizeof premigrate, premigrate_sha, 16);
    if (final_mode != 0 && final_mode != 6) {
        if (final_mode == 1 || final_mode == 5) {
            snprintf(command, sizeof command,
                "GUARDHEALTH %s 16 %s A %s 568 14 FAIL 2 %s %s ABSENT RESTORED",
                SESSION, boot_nonce, a_sha, STATE_SHA, SETTINGS_SHA);
            dispatch_once(command, reply, sizeof reply);
            CHECK(strcmp(reply, "OK GUARDHEALTH 17 ROLLBACK_REQUIRED\n") == 0,
                "failed final-A RESTORED health enters rollback (got %s)\n", reply);
        } else {
            guard_test_set_now_ms(220000);
            snprintf(command, sizeof command,
                "GUARDHEALTH %s 16 %s A %s 568 14 OK 2 %s %s ABSENT RESTORED",
                SESSION, boot_nonce, a_sha, STATE_SHA, SETTINGS_SHA);
            dispatch_once(command, reply, sizeof reply);
            CHECK(strcmp(reply, "OK GUARDHEALTH 17 ROLLBACK_REQUIRED\n") == 0,
                "final-A HEALTH at deadline equality cannot beat timeout (got %s)\n", reply);
            dispatch_once("GUARDSTATUS", reply, sizeof reply);
            CHECK(strstr(reply, " 17 ROLLBACK_REQUIRED ") != NULL &&
                  strstr(reply, " HEALTH_TIMEOUT NONE ") != NULL,
                "final-A health timeout is durably classified (got %s)\n", reply);
        }
        const char *const rollback_install_a[] = {
            "pm", "install", "-r", "-d", CUSTODY "/a.apk", NULL,
        };
        guard_maintenance_set_supervisor_owner();
        CHECK(guard_maintenance_supervisor_tick() == GUARD_WORK_NONE,
            "exact A already installed consumes rollback authority without another PM\n");
        CHECK(sysexec_stub_count_argv("/system/bin/pm", rollback_install_a, 1) == 1,
            "final-A rollback preserves the single forward A install\n");
        if (final_mode == 1) {
            guard_test_set_fault(GUARD_TEST_FAULT_DB_AFTER_PROMOTE);
            CHECK(guard_maintenance_supervisor_tick() == GUARD_WORK_NONE,
                "post-promote rollback dir-fsync ambiguity emits no premature launch\n");
            dispatch_once("GUARDSTATUS", reply, sizeof reply);
            CHECK(strstr(reply, " 18 ROLLBACK_DB_PREPARED ") != NULL,
                "baseline promotion ambiguity retains the pre-restore journal phase (got %s)\n",
                reply);
            guard_test_set_fault(GUARD_TEST_FAULT_NONE);
            guard_test_drop_runtime();
            CHECK(guard_test_reconcile() == 0,
                "restart reconciles exact baseline after post-promote dir-fsync ambiguity\n");
            guard_maintenance_set_supervisor_owner();
        }
        work = guard_maintenance_supervisor_tick();
        CHECK(work == GUARD_WORK_LAUNCH_A,
            "helper fallback restores sealed baseline before relaunch\n");
        CHECK(access(DB_DIR "/.ha-paneld.db.restore.v1", F_OK) != 0 &&
              access(DB_DIR "/.ha-paneld.db.restore.v1.tmp", F_OK) != 0 &&
              access(DB_DIR "/.ha-paneld.db.restore.prepared.v1", F_OK) != 0 &&
              access(DB_PATH ".v15.superseded", F_OK) != 0 &&
              access(DB_PATH ".v14.premigrate", F_OK) != 0,
            "fallback retires exact app receipt and recovery artifacts after baseline durability\n");
        CHECK(guard_maintenance_supervisor_start_work(
                  (enum guard_supervisor_work)work, &executor) == 0 &&
              guard_maintenance_supervisor_complete(
                  GUARD_WORK_LAUNCH_A, GUARD_EXEC_REAPED, 0) == 0,
            "fallback exact A relaunch reaches BASELINE health wait\n");
        sysexec_stub_clear_popen_rules();
        sysexec_stub_add_popen(
            "PRAGMA query_only=ON",
            "14\nok\n0\n2\n"
            "636F6E666967|616C706861|74657874|V6F6E65|1\n"
            "6F74686572|62657461|74657874|N|2\n",
            0);
        if (final_mode == 5) {
            guard_test_set_now_ms(220000);
            snprintf(command, sizeof command,
                "GUARDHEALTH %s 19 %s A %s 568 14 OK 2 %s %s ABSENT BASELINE",
                SESSION, boot_nonce, a_sha, STATE_SHA, SETTINGS_SHA);
            dispatch_once(command, reply, sizeof reply);
            CHECK(strcmp(reply, "OK GUARDHEALTH 20 AMBIGUOUS\n") == 0,
                "rollback HEALTH at deadline equality cannot false-finalize (got %s)\n", reply);
            dispatch_once("GUARDSTATUS", reply, sizeof reply);
            CHECK(strstr(reply, " 20 AMBIGUOUS ") != NULL &&
                  strstr(reply, " HEALTH_TIMEOUT AMBIGUOUS ") != NULL,
                "rollback health equality settles to explicit ambiguity (got %s)\n", reply);
            CHECK(sysexec_stub_count_argv("/system/bin/pm", rollback_install_a, 1) == 1,
                "rollback health expiry submits no additional PM work\n");
            char ambiguous_evidence[GUARD_EVIDENCE_MAX_BYTES], ambiguous_sha[65];
            snprintf(command, sizeof command, "GUARDEVIDENCE %s", SESSION);
            dispatch_once(command, ambiguous_evidence, sizeof ambiguous_evidence);
            hash_bytes(ambiguous_evidence, strlen(ambiguous_evidence), ambiguous_sha);
            snprintf(command, sizeof command, "GUARDRETIRE TERMINAL %s 20 %s",
                SESSION, ambiguous_sha);
            dispatch_once(command, reply, sizeof reply);
            CHECK(strcmp(reply, "ERR HOLD retirement\n") == 0,
                "ambiguous transaction cannot retire terminal evidence (got %s)\n", reply);
            guard_test_set_now_ms(0);
            return;
        }
        snprintf(command, sizeof command,
            "GUARDHEALTH %s 19 %s A %s 568 14 OK 2 %s %s ABSENT BASELINE",
            SESSION, boot_nonce, a_sha, STATE_SHA, SETTINGS_SHA);
        dispatch_once(command, reply, sizeof reply);
        CHECK(strcmp(reply, "OK GUARDHEALTH 20 A_HEALTHY\n") == 0,
            "fallback BASELINE health succeeds after exact receipt cleanup (got %s)\n", reply);
        dispatch_once("GUARDSTATUS", reply, sizeof reply);
        CHECK(strstr(reply, final_mode == 1
                ? " HEALTH_FAILED NONE " : " HEALTH_TIMEOUT NONE ") != NULL,
            "fallback A_HEALTHY retains cause without a terminal outcome (got %s)\n", reply);
        snprintf(command, sizeof command, "GUARDACTION %s 20 FINALIZE", SESSION);
        dispatch_once(command, reply, sizeof reply);
        CHECK(strcmp(reply, "OK GUARDACTION 21 FINALIZED\n") == 0,
            "fallback finalizes exact A without canary success (got %s)\n", reply);
        dispatch_once("GUARDSTATUS", reply, sizeof reply);
        CHECK(strstr(reply, final_mode == 1
                ? " NONE ROLLED_BACK_HEALTH_FAILED " : " NONE ROLLED_BACK_HEALTH_TIMEOUT ") != NULL,
            "fallback FINALIZED derives the typed rollback outcome (got %s)\n", reply);
        if (final_mode == 1) {
            char evidence_command[128], evidence_bytes[GUARD_EVIDENCE_MAX_BYTES], evidence_sha[65];
            snprintf(evidence_command, sizeof evidence_command,
                "GUARDEVIDENCE %s", SESSION);
            dispatch_once(evidence_command, evidence_bytes, sizeof evidence_bytes);
            hash_bytes(evidence_bytes, strlen(evidence_bytes), evidence_sha);
            snprintf(command, sizeof command, "GUARDRETIRE TERMINAL %s 21 %s",
                SESSION, evidence_sha);
            dispatch_once(command, reply, sizeof reply);
            CHECK(strcmp(reply, "OK GUARDRETIRE 22 EMPTY\n") == 0,
                "rolled-back terminal evidence retires to exact EMPTY (got %s)\n", reply);
        }
        guard_test_set_now_ms(0);
        return;
    }
    sysexec_stub_clear_popen_rules();
    sysexec_stub_add_popen(
        "PRAGMA query_only=ON",
        "14\nok\n0\n2\n"
        "636F6E666967|616C706861|74657874|V6F6E65|1\n"
        "6F74686572|62657461|74657874|N|2\n",
        0);
    guard_test_set_supervised(1);
    snprintf(command, sizeof command,
        "GUARDHEALTH %s 16 %s A %s 568 14 OK 2 %s %s ABSENT RESTORED",
        SESSION, boot_nonce, a_sha, STATE_SHA, SETTINGS_SHA);
    dispatch_once(command, reply, sizeof reply);
    CHECK(strcmp(reply, "OK GUARDHEALTH 17 A_HEALTHY\n") == 0,
        "final A health requires exact RESTORED artifacts and baseline semantics (got %s)\n", reply);
    dispatch_once(command, reply, sizeof reply);
    CHECK(strcmp(reply, "OK GUARDHEALTH 17 A_HEALTHY\n") == 0,
        "lost final A-health reply is exactly idempotent\n");

    snprintf(command, sizeof command, "GUARDACTION %s 17 FINALIZE", SESSION);
    guard_test_set_fault(GUARD_TEST_FAULT_JOURNAL_DIR_SYNC);
    dispatch_once(command, reply, sizeof reply);
    CHECK(strcmp(reply, "ERR INDETERMINATE terminal\n") == 0,
        "post-rename terminal journal dir-fsync is indeterminate (got %s)\n", reply);
    guard_test_set_fault(GUARD_TEST_FAULT_NONE);
    dispatch_once("GUARDSTATUS", reply, sizeof reply);
    CHECK(strstr(reply, " 18 FINALIZED ") != NULL &&
          strstr(reply, " CANARY_PASSED ") != NULL,
        "terminal publication ambiguity is settled from durable status (got %s)\n", reply);
    dispatch_once(command, reply, sizeof reply);
    CHECK(strcmp(reply, "OK GUARDACTION 18 FINALIZED\n") == 0,
        "finalize durably distinguishes a passed canary (got %s)\n", reply);
    dispatch_once("GUARDSTATUS", reply, sizeof reply);
    snprintf(expected, sizeof expected,
        "OK GUARDSTATUS 18 FINALIZED %s %s A %s 568 14 2 NONE CANARY_PASSED ",
        SESSION, boot_nonce, a_sha);
    check_status_with_deadlines(reply, expected,
        "terminal status projects exact A plus CANARY_PASSED outcome");
    CHECK(sysexec_stub_count_argv("/system/bin/pm", install_a, 1) == 1,
        "successful lifecycle submits exact A only once\n");
    if (final_mode == 6) {
        char evidence_command[128], evidence_bytes[GUARD_EVIDENCE_MAX_BYTES], evidence_sha[65];
        snprintf(evidence_command, sizeof evidence_command, "GUARDEVIDENCE %s", SESSION);
        dispatch_once(evidence_command, evidence_bytes, sizeof evidence_bytes);
        hash_bytes(evidence_bytes, strlen(evidence_bytes), evidence_sha);

        snprintf(command, sizeof command, "GUARDRETIRE TERMINAL %s 18 %s",
            SIGNER, evidence_sha);
        dispatch_once(command, reply, sizeof reply);
        CHECK(strcmp(reply, "ERR MISMATCH evidence\n") == 0,
            "terminal retirement rejects a wrong session identity (got %s)\n", reply);
        snprintf(command, sizeof command, "GUARDRETIRE TERMINAL %s 17 %s",
            SESSION, evidence_sha);
        dispatch_once(command, reply, sizeof reply);
        CHECK(strcmp(reply, "ERR STALE retirement\n") == 0,
            "terminal retirement rejects a stale generation (got %s)\n", reply);
        snprintf(command, sizeof command, "GUARDRETIRE TERMINAL %s 18 %s",
            SESSION, SIGNER);
        dispatch_once(command, reply, sizeof reply);
        CHECK(strcmp(reply, "ERR MISMATCH evidence\n") == 0,
            "terminal retirement rejects a wrong raw-evidence hash (got %s)\n", reply);

        CHECK(chmod(CUSTODY "/a.apk", 04400) == 0,
            "mutate retirement custody mode\n");
        snprintf(command, sizeof command, "GUARDRETIRE TERMINAL %s 18 %s",
            SESSION, evidence_sha);
        dispatch_once(command, reply, sizeof reply);
        CHECK(strcmp(reply, "ERR HOLD retirement\n") == 0,
            "terminal retirement refuses a special-mode custody artifact (got %s)\n", reply);
        CHECK(chmod(CUSTODY "/a.apk", 0400) == 0,
            "restore retirement custody mode\n");
        write_file_mode(INSTALLED_APK, "changed-a", 9, 0600);
        dispatch_once(command, reply, sizeof reply);
        CHECK(strcmp(reply, "ERR HOLD retirement\n") == 0,
            "terminal retirement holds after installed A changes (got %s)\n", reply);
        write_file_mode(INSTALLED_APK, a_payload, sizeof a_payload - 1, 0600);
        write_file_mode(CUSTODY "/replacement.v1", "foreign-r1", 10, 0600);
        dispatch_once(command, reply, sizeof reply);
        CHECK(strcmp(reply, "ERR HOLD retirement\n") == 0,
            "terminal retirement holds while R1 exists (got %s)\n", reply);
        CHECK(unlink(CUSTODY "/replacement.v1") == 0, "remove R1 retirement blocker\n");
        guard_test_drop_runtime();
        CHECK(guard_test_reconcile() == 0,
            "removing the R1 blocker restores exact FINALIZED admission\n");

        const enum guard_test_fault publication_faults[] = {
            GUARD_TEST_FAULT_JOURNAL_FILE_SYNC,
            GUARD_TEST_FAULT_JOURNAL_RENAME,
        };
        for (size_t fault = 0;
             fault < sizeof publication_faults / sizeof publication_faults[0]; fault++) {
            guard_test_set_fault(publication_faults[fault]);
            dispatch_once(command, reply, sizeof reply);
            CHECK(strcmp(reply, "ERR INDETERMINATE retirement\n") == 0,
                "retirement journal publication cut %zu is indeterminate (got %s)\n",
                fault, reply);
            guard_test_set_fault(GUARD_TEST_FAULT_NONE);
            dispatch_once("GUARDSTATUS", reply, sizeof reply);
            CHECK(strstr(reply, " 18 FINALIZED ") != NULL,
                "pre-rename retirement cut preserves FINALIZED status (got %s)\n", reply);
        }
        guard_test_set_fault(GUARD_TEST_FAULT_JOURNAL_DIR_SYNC);
        dispatch_once(command, reply, sizeof reply);
        CHECK(strcmp(reply, "ERR INDETERMINATE retirement\n") == 0,
            "post-rename RETIRING journal cut is indeterminate (got %s)\n", reply);
        guard_test_set_fault(GUARD_TEST_FAULT_NONE);
        dispatch_once(command, reply, sizeof reply);
        CHECK(strcmp(reply, "ERR STALE retirement\n") == 0,
            "a replay cannot resume a reply-lost retirement (got %s)\n", reply);

        guard_test_set_fault(GUARD_TEST_FAULT_RETIRE_AFTER_A);
        dispatch_once("GUARDSTATUS", reply, sizeof reply);
        CHECK(strcmp(reply, "ERR HOLD retirement\n") == 0 &&
              access(CUSTODY "/a.apk", F_OK) != 0,
            "status reconciliation stops exactly after A cleanup cut (got %s)\n", reply);
        const enum guard_test_fault cleanup_faults[] = {
            GUARD_TEST_FAULT_RETIRE_AFTER_B,
            GUARD_TEST_FAULT_RETIRE_AFTER_SETTINGS,
            GUARD_TEST_FAULT_RETIRE_AFTER_BASELINE,
            GUARD_TEST_FAULT_RETIRE_AFTER_PREMIGRATE,
            GUARD_TEST_FAULT_RETIRE_AFTER_B_PRIMARY,
            GUARD_TEST_FAULT_RETIRE_AFTER_MANIFEST,
            GUARD_TEST_FAULT_RETIRE_JOURNAL_UNLINKED,
            GUARD_TEST_FAULT_RETIRE_AFTER_JOURNAL,
        };
        for (size_t fault = 0;
             fault < sizeof cleanup_faults / sizeof cleanup_faults[0]; fault++) {
            guard_test_set_fault(GUARD_TEST_FAULT_NONE);
            guard_test_drop_runtime();
            guard_test_set_fault(cleanup_faults[fault]);
            CHECK(guard_test_reconcile() != 0,
                "retirement cleanup cut %zu withholds startup until restart\n", fault);
        }
        guard_test_set_fault(GUARD_TEST_FAULT_NONE);
        guard_test_drop_runtime();
        CHECK(guard_test_reconcile() == 0,
            "final retirement restart reconciles durable EMPTY\n");
        dispatch_once("GUARDSTATUS", reply, sizeof reply);
        CHECK(strcmp(reply,
            "OK GUARDSTATUS 0 EMPTY NONE NONE NONE NONE 0 0 0 NONE NONE 0 0\n") == 0,
            "post-retirement status is exact EMPTY (got %s)\n", reply);
        dispatch_once(evidence_command, reply, sizeof reply);
        CHECK(strcmp(reply, "ERR STATE record\n") == 0,
            "retired evidence is unavailable from the helper (got %s)\n", reply);
        CHECK(guard_maintenance_package_busy() == 0 &&
              guard_maintenance_replacement_safe() == 0 &&
              guard_maintenance_install_begin() == 0,
            "EMPTY reopens package mutation and APP R1 admission\n");
        guard_maintenance_install_end();
        return;
    }
    mutate_restore_receipt("GUARD " SESSION " 16", "GUARD " SESSION " 15");
    CHECK(guard_test_restore_baseline_now() != 0 &&
          access(DB_DIR "/.ha-paneld.db.restore.v1", F_OK) == 0 &&
          access(DB_PATH ".v15.superseded", F_OK) == 0,
        "fallback holds without deleting a foreign-generation restore transaction\n");
    write_restore_receipt("RESTORED", 15, sizeof database, b_primary_sha,
        14, sizeof premigrate, premigrate_sha, 16);

    /* The helper fallback must retire every exact app restore cut only after the sealed
     * baseline has been promoted. Each cut below reuses the terminal manifest solely as
     * a white-box rollback authority; production reaches the same resolver from
     * ROLLBACK_DB_PREPARED. */
    struct app_cut {
        const char *state;
        int source_live;
        int source_aside;
        int prepared_live;
        int restored_live;
    } cuts[] = {
        { "PREPARED", 1, 0, 1, 0 },
        { "PREPARED", 0, 1, 1, 0 },
        { "SOURCE_ASIDE", 0, 1, 1, 0 },
        { "SOURCE_ASIDE", 0, 1, 0, 1 },
        { "RESTORED", 0, 1, 0, 1 },
    };
    for (size_t index = 0; index < sizeof cuts / sizeof cuts[0]; index++) {
        (void)unlink(DB_PATH);
        (void)unlink(DB_PATH ".v15.superseded");
        (void)unlink(DB_DIR "/.ha-paneld.db.restore.prepared.v1");
        (void)unlink(DB_DIR "/.ha-paneld.db.restore.v1");
        (void)unlink(DB_DIR "/.ha-paneld.db.restore.v1.tmp");
        if (cuts[index].source_live)
            write_file_mode(DB_PATH, database, sizeof database, 0600);
        if (cuts[index].source_aside)
            write_file_mode(DB_PATH ".v15.superseded", database, sizeof database, 0600);
        if (cuts[index].prepared_live)
            write_file_mode(DB_DIR "/.ha-paneld.db.restore.prepared.v1",
                premigrate, sizeof premigrate, 0600);
        if (cuts[index].restored_live)
            write_file_mode(DB_PATH, premigrate, sizeof premigrate, 0600);
        write_restore_receipt(cuts[index].state, 15, sizeof database, b_primary_sha,
            14, sizeof premigrate, premigrate_sha, 16);
        if (index == 1) {
            CHECK(rename(DB_DIR "/.ha-paneld.db.restore.v1",
                         DB_DIR "/.ha-paneld.db.restore.v1.tmp") == 0,
                "model exact temp-only PREPARED publication cut\n");
        } else if (index == 2) {
            CHECK(rename(DB_DIR "/.ha-paneld.db.restore.v1",
                         DB_DIR "/.ha-paneld.db.restore.v1.tmp") == 0,
                "model exact next-state temp publication cut\n");
            write_restore_receipt("PREPARED", 15, sizeof database, b_primary_sha,
                14, sizeof premigrate, premigrate_sha, 16);
        }
        CHECK(guard_test_restore_baseline_now() == 0,
            "helper fallback canonicalizes exact app restore cut %s/%zu\n",
            cuts[index].state, index);
        CHECK(file_equals(DB_PATH, premigrate, sizeof premigrate) &&
              access(DB_PATH ".v15.superseded", F_OK) != 0 &&
              access(DB_DIR "/.ha-paneld.db.restore.prepared.v1", F_OK) != 0 &&
              access(DB_DIR "/.ha-paneld.db.restore.v1", F_OK) != 0 &&
              access(DB_DIR "/.ha-paneld.db.restore.v1.tmp", F_OK) != 0,
            "helper fallback leaves exact baseline and no stale app restore authority %zu\n",
            index);
    }
}

static void test_health_failure_rolls_back_once_to_exact_baseline(void) {
    setup();
    unsigned char database[128] = {0};
    memcpy(database, "SQLite format 3", 15);
    database[15] = 0;
    database[63] = 14;
    char db_sha[65];
    write_file_mode(DB_PATH, database, sizeof database, 0600);
    prepare_exact_baseline(database, sizeof database, db_sha);

    static const char a_payload[] = "rollback-installed-a";
    static const char b_payload[] = "rollback-candidate-b";
    char a_sha[65], b_sha[65];
    hash_bytes(a_payload, sizeof a_payload - 1, a_sha);
    hash_bytes(b_payload, sizeof b_payload - 1, b_sha);
    define_role("A", 1, a_sha, 568, 11, 14, 14, sizeof a_payload - 1, 2);
    define_role("B", 2, b_sha, 569, 11, 15, 15, sizeof b_payload - 1, 3);
    stream_role("A", 3, a_sha, a_payload, sizeof a_payload - 1, 4);
    stream_role("B", 4, b_sha, b_payload, sizeof b_payload - 1, 5);
    stream_role("SETTINGS", 5, SETTINGS_AUTHORITY_SHA, SETTINGS_AUTHORITY,
        sizeof SETTINGS_AUTHORITY - 1, 6);
    write_file_mode(INSTALLED_APK, a_payload, sizeof a_payload - 1, 0600);
    guard_test_set_supervised(1);

    char command[1024], reply[1024], expected[1024];
    snprintf(command, sizeof command, "GUARDACTION %s 6 CAPTURE_BASELINE", SESSION);
    dispatch_once(command, reply, sizeof reply);
    CHECK(strcmp(reply, "OK GUARDACTION 7 PREPARED\n") == 0,
        "rollback fixture captures baseline\n");
    guard_maintenance_set_supervisor_owner();
    int work = guard_maintenance_supervisor_tick();
    pid_t executor = -1;
    CHECK(work == GUARD_WORK_INSTALL_B &&
          guard_maintenance_supervisor_start_work((enum guard_supervisor_work)work, &executor) == 0,
        "rollback fixture starts exact B once\n");
    write_file_mode(INSTALLED_APK, b_payload, sizeof b_payload - 1, 0600);
    work = guard_maintenance_supervisor_complete(GUARD_WORK_INSTALL_B, GUARD_EXEC_REAPED, 0);
    CHECK(work == GUARD_WORK_LAUNCH_B &&
          guard_maintenance_supervisor_start_work((enum guard_supervisor_work)work, &executor) == 0 &&
          guard_maintenance_supervisor_complete(GUARD_WORK_LAUNCH_B, GUARD_EXEC_REAPED, 0) == 0,
        "rollback fixture reaches B health wait\n");

    guard_test_set_supervised(1);
    snprintf(command, sizeof command,
        "GUARDHEALTH %s 9 %s B %s 569 15 FAIL 2 %s %s PRESENT NA",
        SESSION, boot_nonce, b_sha, STATE_SHA, SETTINGS_SHA);
    dispatch_once(command, reply, sizeof reply);
    CHECK(strcmp(reply, "OK GUARDHEALTH 10 ROLLBACK_REQUIRED\n") == 0,
        "explicit failed B health durably enters rollback (got %s)\n", reply);

    guard_maintenance_set_supervisor_owner();
    work = guard_maintenance_supervisor_tick();
    CHECK(work == GUARD_WORK_INSTALL_A,
        "rollback atomically consumes its sole exact-A submission authority\n");
    const char *const install_a[] = {
        "pm", "install", "-r", "-d", CUSTODY "/a.apk", NULL,
    };
    CHECK(guard_maintenance_supervisor_start_work((enum guard_supervisor_work)work, &executor) == 0,
        "rollback starts exact A executor\n");
    write_file_mode(INSTALLED_APK, a_payload, sizeof a_payload - 1, 0600);
    CHECK(guard_maintenance_supervisor_complete(
            GUARD_WORK_INSTALL_A, GUARD_EXEC_REAPED, 0) == GUARD_WORK_NONE,
        "settled rollback A transitions to DB restore without relaunching early\n");
    CHECK(sysexec_stub_count_argv("/system/bin/pm", install_a, 1) == 1,
        "rollback submits exact A exactly once\n");

    work = guard_maintenance_supervisor_tick();
    CHECK(work == GUARD_WORK_LAUNCH_A,
        "helper atomically restores baseline before requesting A launch\n");
    CHECK(guard_maintenance_supervisor_start_work((enum guard_supervisor_work)work, &executor) == 0 &&
          guard_maintenance_supervisor_complete(GUARD_WORK_LAUNCH_A, GUARD_EXEC_REAPED, 0) == 0,
        "rollback A launch preserves BASELINE health wait\n");
    sysexec_stub_clear_popen_rules();
    sysexec_stub_add_popen(
        "PRAGMA query_only=ON",
        "14\nok\n0\n2\n"
        "636F6E666967|616C706861|74657874|V6F6E65|1\n"
        "6F74686572|62657461|74657874|N|2\n",
        0);
    guard_test_set_supervised(1);
    snprintf(command, sizeof command,
        "GUARDHEALTH %s 13 %s A %s 568 14 OK 2 %s %s ABSENT BASELINE",
        SESSION, boot_nonce, a_sha, STATE_SHA, SETTINGS_SHA);
    dispatch_once(command, reply, sizeof reply);
    CHECK(strcmp(reply, "OK GUARDHEALTH 14 A_HEALTHY\n") == 0,
        "rollback health requires exact A and baseline semantics (got %s)\n", reply);
    CHECK(access(DB_PATH ".v14.premigrate", F_OK) != 0 &&
          access(DB_PATH ".v15.superseded", F_OK) != 0 &&
          access(DB_DIR "/.guard.rollback.tmp", F_OK) != 0,
        "rollback health leaves canonical recovery inventory\n");
    dispatch_once("GUARDSTATUS", reply, sizeof reply);
    snprintf(expected, sizeof expected,
        "OK GUARDSTATUS 14 A_HEALTHY %s %s A %s 568 14 2 HEALTH_FAILED NONE ",
        SESSION, boot_nonce, a_sha);
    check_status_with_deadlines(reply, expected,
        "rollback A_HEALTHY retains its cause and no terminal outcome");
    snprintf(command, sizeof command, "GUARDACTION %s 14 FINALIZE", SESSION);
    dispatch_once(command, reply, sizeof reply);
    CHECK(strcmp(reply, "OK GUARDACTION 15 FINALIZED\n") == 0,
        "safe rollback finalizes without claiming canary success (got %s)\n", reply);
    dispatch_once("GUARDSTATUS", reply, sizeof reply);
    snprintf(expected, sizeof expected,
        "OK GUARDSTATUS 15 FINALIZED %s %s A %s 568 14 2 NONE ROLLED_BACK_HEALTH_FAILED ",
        SESSION, boot_nonce, a_sha);
    check_status_with_deadlines(reply, expected,
        "terminal rollback projects exact A plus typed failure outcome");
    CHECK(sysexec_stub_count_argv("/system/bin/pm", install_a, 1) == 1,
        "terminal rollback never retries its consumed A submission\n");
}

static void test_rollback_consumed_before_spawn_never_retries(void) {
    setup();
    unsigned char database[128] = {0};
    memcpy(database, "SQLite format 3", 15);
    database[15] = 0;
    database[63] = 14;
    char db_sha[65];
    write_file_mode(DB_PATH, database, sizeof database, 0600);
    prepare_exact_baseline(database, sizeof database, db_sha);
    static const char a_payload[] = "cut-rollback-installed-a";
    static const char b_payload[] = "cut-rollback-candidate-b";
    char a_sha[65], b_sha[65];
    hash_bytes(a_payload, sizeof a_payload - 1, a_sha);
    hash_bytes(b_payload, sizeof b_payload - 1, b_sha);
    define_role("A", 1, a_sha, 568, 11, 14, 14, sizeof a_payload - 1, 2);
    define_role("B", 2, b_sha, 569, 11, 15, 15, sizeof b_payload - 1, 3);
    stream_role("A", 3, a_sha, a_payload, sizeof a_payload - 1, 4);
    stream_role("B", 4, b_sha, b_payload, sizeof b_payload - 1, 5);
    stream_role("SETTINGS", 5, SETTINGS_AUTHORITY_SHA, SETTINGS_AUTHORITY,
        sizeof SETTINGS_AUTHORITY - 1, 6);
    write_file_mode(INSTALLED_APK, a_payload, sizeof a_payload - 1, 0600);
    guard_test_set_supervised(1);
    char command[1024], reply[1024];
    snprintf(command, sizeof command, "GUARDACTION %s 6 CAPTURE_BASELINE", SESSION);
    dispatch_once(command, reply, sizeof reply);
    guard_maintenance_set_supervisor_owner();
    int work = guard_maintenance_supervisor_tick();
    pid_t executor = -1;
    CHECK(work == GUARD_WORK_INSTALL_B &&
          guard_maintenance_supervisor_start_work((enum guard_supervisor_work)work, &executor) == 0,
        "cut fixture starts B\n");
    write_file_mode(INSTALLED_APK, b_payload, sizeof b_payload - 1, 0600);
    work = guard_maintenance_supervisor_complete(GUARD_WORK_INSTALL_B, GUARD_EXEC_REAPED, 0);
    CHECK(work == GUARD_WORK_LAUNCH_B &&
          guard_maintenance_supervisor_start_work((enum guard_supervisor_work)work, &executor) == 0 &&
          guard_maintenance_supervisor_complete(GUARD_WORK_LAUNCH_B, GUARD_EXEC_REAPED, 0) == 0,
        "cut fixture reaches B health wait\n");
    guard_test_set_supervised(1);
    snprintf(command, sizeof command,
        "GUARDHEALTH %s 9 %s B %s 569 15 FAIL 2 %s %s PRESENT NA",
        SESSION, boot_nonce, b_sha, STATE_SHA, SETTINGS_SHA);
    dispatch_once(command, reply, sizeof reply);
    CHECK(strcmp(reply, "OK GUARDHEALTH 10 ROLLBACK_REQUIRED\n") == 0,
        "cut fixture durably requires rollback\n");

    guard_maintenance_set_supervisor_owner();
    CHECK(guard_maintenance_supervisor_tick() == GUARD_WORK_INSTALL_A,
        "rollback attempt is consumed before its PM spawn\n");
    const char *const install_a[] = {
        "pm", "install", "-r", "-d", CUSTODY "/a.apk", NULL,
    };
    CHECK(sysexec_stub_count_argv("/system/bin/pm", install_a, 1) == 0,
        "crash before spawn has zero A PM argv\n");
    guard_test_drop_runtime();
    CHECK(guard_test_reconcile() == 0,
        "new supervisor loads consumed pre-spawn rollback intent\n");
    guard_maintenance_set_supervisor_owner();
    CHECK(guard_maintenance_supervisor_tick() == GUARD_WORK_NONE,
        "consumed rollback intent without executor record is never replayed\n");
    CHECK(sysexec_stub_count_argv("/system/bin/pm", install_a, 1) == 0,
        "restart preserves zero A PM attempts after pre-spawn cut\n");
    dispatch_once("GUARDSTATUS", reply, sizeof reply);
    CHECK(strstr(reply, " EXECUTOR_NOT_RECORDED AMBIGUOUS ") != NULL,
        "pre-spawn process cut settles only to explicit AMBIGUOUS (got %s)\n", reply);
}

static void reach_spawned_rollback(char a_sha[65], char b_sha[65],
                                   const char **a_payload_out, size_t *a_bytes_out) {
    static const char a_payload[] = "spawn-cut-rollback-installed-a";
    static const char b_payload[] = "spawn-cut-rollback-candidate-b";
    setup();
    unsigned char database[128] = {0};
    memcpy(database, "SQLite format 3", 15);
    database[15] = 0;
    database[63] = 14;
    char db_sha[65];
    write_file_mode(DB_PATH, database, sizeof database, 0600);
    prepare_exact_baseline(database, sizeof database, db_sha);
    hash_bytes(a_payload, sizeof a_payload - 1, a_sha);
    hash_bytes(b_payload, sizeof b_payload - 1, b_sha);
    define_role("A", 1, a_sha, 568, 11, 14, 14, sizeof a_payload - 1, 2);
    define_role("B", 2, b_sha, 569, 11, 15, 15, sizeof b_payload - 1, 3);
    stream_role("A", 3, a_sha, a_payload, sizeof a_payload - 1, 4);
    stream_role("B", 4, b_sha, b_payload, sizeof b_payload - 1, 5);
    stream_role("SETTINGS", 5, SETTINGS_AUTHORITY_SHA, SETTINGS_AUTHORITY,
        sizeof SETTINGS_AUTHORITY - 1, 6);
    write_file_mode(INSTALLED_APK, a_payload, sizeof a_payload - 1, 0600);
    guard_test_set_supervised(1);

    char command[1024], reply[1024];
    snprintf(command, sizeof command, "GUARDACTION %s 6 CAPTURE_BASELINE", SESSION);
    dispatch_once(command, reply, sizeof reply);
    guard_maintenance_set_supervisor_owner();
    int work = guard_maintenance_supervisor_tick();
    pid_t executor = -1;
    CHECK(work == GUARD_WORK_INSTALL_B &&
          guard_maintenance_supervisor_start_work((enum guard_supervisor_work)work, &executor) == 0,
        "spawn-cut fixture starts B\n");
    write_file_mode(INSTALLED_APK, b_payload, sizeof b_payload - 1, 0600);
    work = guard_maintenance_supervisor_complete(GUARD_WORK_INSTALL_B, GUARD_EXEC_REAPED, 0);
    CHECK(work == GUARD_WORK_LAUNCH_B &&
          guard_maintenance_supervisor_start_work((enum guard_supervisor_work)work, &executor) == 0 &&
          guard_maintenance_supervisor_complete(GUARD_WORK_LAUNCH_B, GUARD_EXEC_REAPED, 0) == 0,
        "spawn-cut fixture reaches B health wait\n");
    guard_test_set_supervised(1);
    snprintf(command, sizeof command,
        "GUARDHEALTH %s 9 %s B %s 569 15 FAIL 2 %s %s PRESENT NA",
        SESSION, boot_nonce, b_sha, STATE_SHA, SETTINGS_SHA);
    dispatch_once(command, reply, sizeof reply);
    CHECK(strcmp(reply, "OK GUARDHEALTH 10 ROLLBACK_REQUIRED\n") == 0,
        "spawn-cut fixture enters rollback\n");
    guard_maintenance_set_supervisor_owner();
    work = guard_maintenance_supervisor_tick();
    CHECK(work == GUARD_WORK_INSTALL_A &&
          guard_maintenance_supervisor_start_work((enum guard_supervisor_work)work, &executor) == 0,
        "spawn-cut fixture consumes and spawns sole A attempt\n");
    *a_payload_out = a_payload;
    *a_bytes_out = sizeof a_payload - 1;
}

static void test_rollback_spawned_executor_loss_never_retries(void) {
    char a_sha[65], b_sha[65];
    const char *a_payload;
    size_t a_bytes;
    reach_spawned_rollback(a_sha, b_sha, &a_payload, &a_bytes);
    const char *const install_a[] = {
        "pm", "install", "-r", "-d", CUSTODY "/a.apk", NULL,
    };
    CHECK(sysexec_stub_count_argv("/system/bin/pm", install_a, 1) == 1,
        "spawn cut records exactly one A PM argv\n");
    write_file_mode(INSTALLED_APK, a_payload, a_bytes, 0600);
    guard_test_set_pm_process_state(-1);
    guard_test_drop_runtime();
    CHECK(guard_test_reconcile() == 0,
        "new supervisor loads spawned rollback receipt\n");
    guard_maintenance_set_supervisor_owner();
    CHECK(guard_maintenance_supervisor_tick() == GUARD_WORK_NONE,
        "lost rollback executor is never replayed even when exact A is visible\n");
    CHECK(sysexec_stub_count_argv("/system/bin/pm", install_a, 1) == 1,
        "spawned rollback remains at most one PM argv after restart\n");
    char reply[1024];
    dispatch_once("GUARDSTATUS", reply, sizeof reply);
    CHECK(strstr(reply, " EXECUTOR_UNKNOWN AMBIGUOUS ") != NULL,
        "lost rollback executor holds explicitly instead of false-settling exact A (got %s)\n",
        reply);
}

static void test_rollback_timeout_with_exact_a_holds(void) {
    char a_sha[65], b_sha[65];
    const char *a_payload;
    size_t a_bytes;
    reach_spawned_rollback(a_sha, b_sha, &a_payload, &a_bytes);
    const char *const install_a[] = {
        "pm", "install", "-r", "-d", CUSTODY "/a.apk", NULL,
    };
    write_file_mode(INSTALLED_APK, a_payload, a_bytes, 0600);
    CHECK(guard_maintenance_supervisor_complete(
            GUARD_WORK_INSTALL_A, GUARD_EXEC_TIMED_OUT, 0) == GUARD_WORK_NONE,
        "timed-out rollback executor does not schedule competing work\n");
    CHECK(sysexec_stub_count_argv("/system/bin/pm", install_a, 1) == 1,
        "timeout preserves the single rollback PM attempt\n");
    char reply[1024];
    dispatch_once("GUARDSTATUS", reply, sizeof reply);
    CHECK(strstr(reply, " PM_TIMEOUT AMBIGUOUS ") != NULL,
        "timeout remains ambiguous despite exact A visibility (got %s)\n", reply);
}

static void test_pm_normal_rejection_rolls_back_with_typed_outcome(void) {
    char a_sha[65], b_sha[65];
    const char *b_payload;
    size_t b_bytes;
    pid_t executor = -1;
    reach_submitted_b_for_pm_result(a_sha, b_sha, &b_payload, &b_bytes, &executor);
    (void)b_payload;
    (void)b_bytes;
    CHECK(guard_maintenance_supervisor_complete(
            GUARD_WORK_INSTALL_B, GUARD_EXEC_REAPED, 1 << 8) == GUARD_WORK_NONE,
        "normal nonzero B with exact A unchanged is determinate PM rejection\n");
    char reply[1024], command[1024];
    dispatch_once("GUARDSTATUS", reply, sizeof reply);
    CHECK(strstr(reply, " 9 ROLLBACK_REQUIRED ") != NULL &&
          strstr(reply, " PM_REJECTED NONE ") != NULL,
        "PM rejection is durably typed before rollback (got %s)\n", reply);
    guard_maintenance_set_supervisor_owner();
    CHECK(guard_maintenance_supervisor_tick() == GUARD_WORK_NONE,
        "exact incumbent A consumes rollback without a second install\n");
    int work = guard_maintenance_supervisor_tick();
    CHECK(work == GUARD_WORK_LAUNCH_A &&
          guard_maintenance_supervisor_start_work(
              (enum guard_supervisor_work)work, &executor) == 0 &&
          guard_maintenance_supervisor_complete(
              GUARD_WORK_LAUNCH_A, GUARD_EXEC_REAPED, 0) == 0,
        "PM rejection restores baseline and relaunches exact A\n");
    const char *const install_a[] = {
        "pm", "install", "-r", "-d", CUSTODY "/a.apk", NULL,
    };
    CHECK(sysexec_stub_count_argv("/system/bin/pm", install_a, 1) == 0,
        "PM rejection with exact A submits no rollback A install\n");
    sysexec_stub_clear_popen_rules();
    sysexec_stub_add_popen(
        "PRAGMA query_only=ON",
        "14\nok\n0\n2\n"
        "636F6E666967|616C706861|74657874|V6F6E65|1\n"
        "6F74686572|62657461|74657874|N|2\n",
        0);
    snprintf(command, sizeof command,
        "GUARDHEALTH %s 11 %s A %s 568 14 OK 2 %s %s ABSENT BASELINE",
        SESSION, boot_nonce, a_sha, STATE_SHA, SETTINGS_SHA);
    dispatch_once(command, reply, sizeof reply);
    CHECK(strcmp(reply, "OK GUARDHEALTH 12 A_HEALTHY\n") == 0,
        "PM rejection baseline health succeeds (got %s)\n", reply);
    dispatch_once("GUARDSTATUS", reply, sizeof reply);
    CHECK(strstr(reply, " PM_REJECTED NONE ") != NULL,
        "PM rejection A_HEALTHY retains cause without a terminal outcome (got %s)\n", reply);
    snprintf(command, sizeof command, "GUARDACTION %s 12 FINALIZE", SESSION);
    dispatch_once(command, reply, sizeof reply);
    CHECK(strcmp(reply, "OK GUARDACTION 13 FINALIZED\n") == 0,
        "PM rejection finalizes safe exact A (got %s)\n", reply);
    dispatch_once("GUARDSTATUS", reply, sizeof reply);
    CHECK(strstr(reply, " NONE ROLLED_BACK_PM_REJECTED ") != NULL,
        "PM rejection FINALIZED derives the typed rollback outcome (got %s)\n", reply);
}

static void test_pm_nonzero_target_and_uncertain_target_matrix(void) {
    for (int variant = 0; variant < 3; variant++) {
        char a_sha[65], b_sha[65];
        const char *b_payload;
        size_t b_bytes;
        pid_t executor = -1;
        reach_submitted_b_for_pm_result(a_sha, b_sha, &b_payload, &b_bytes, &executor);
        write_file_mode(INSTALLED_APK, b_payload, b_bytes, 0600);
        enum guard_execution_result result = variant == 0
            ? GUARD_EXEC_REAPED : (variant == 1 ? GUARD_EXEC_TIMED_OUT : GUARD_EXEC_WAIT_LOST);
        int next = guard_maintenance_supervisor_complete(
            GUARD_WORK_INSTALL_B, result, variant == 0 ? (1 << 8) : 0);
        char reply[1024];
        dispatch_once("GUARDSTATUS", reply, sizeof reply);
        if (variant == 0) {
            CHECK(next == GUARD_WORK_LAUNCH_B && strstr(reply, " 9 WAIT_B_HEALTH ") != NULL,
                "normal nonzero with exact target settles from identity (got %s)\n", reply);
        } else {
            CHECK(next == GUARD_WORK_NONE && strstr(reply, " 9 AMBIGUOUS ") != NULL &&
                  strstr(reply, variant == 1 ? " PM_TIMEOUT AMBIGUOUS "
                                             : " PM_WAIT_LOST AMBIGUOUS ") != NULL,
                "uncertain executor with exact target remains no-retry ambiguous (got %s)\n",
                reply);
            const char *const install_b[] = {
                "pm", "install", "-r", "-d", CUSTODY "/b.apk", NULL,
            };
            CHECK(sysexec_stub_count_argv("/system/bin/pm", install_b, 1) == 1,
                "uncertain target-visible PM path stays at one submission\n");
        }
    }
}

/* ---------------------------------------------------------------------------------------------
 * Caller identity selects the Guard package.  The identity is bound once, at GUARDPREPARE, into the
 * durable plan: startup reconcile, the supervisor tick and the deadline worker have no caller at
 * all, so a per-call lookup could not serve them.  No Guard verb takes a package argument, so there
 * is nothing for a caller to forge.
 * ------------------------------------------------------------------------------------------- */

static void test_root_resolves_the_present_package(void) {
    setup();
    test_caller = HELPER_CALLER_ROOT;
    prepare();
    char command[512], reply[4096];
    snprintf(command, sizeof command, "GUARDEVIDENCE %s", SESSION);
    dispatch_once(command, reply, sizeof reply);
    CHECK(strstr(reply, "PACKAGE " APP_PKG "\n") != NULL,
        "root binds the app package when it is present (got %s)\n", reply);

    setup();
    (void)rmdir(DB_DIR);
    (void)rmdir(APP_ROOT "/data/user/0/" APP_PKG);
    guard_test_set_supervised(1);
    prepare_command(command);
    test_caller = HELPER_CALLER_ROOT;
    dispatch_once(command, reply, sizeof reply);
    CHECK(strcmp(reply, "ERR STATE package\n") == 0,
        "root GUARDPREPARE refuses when the app package is absent (got %s)\n", reply);

    /* The same request over the socket path: server_serve must hand the handler the connection's
     * authenticated caller, so the app binds its own package where root was just refused. */
    char line[600];
    snprintf(line, sizeof line, "%s\n", command);
    int peer[2];
    CHECK(socketpair(AF_UNIX, SOCK_STREAM, 0, peer) == 0, "create served socket\n");
    CHECK(write(peer[1], line, strlen(line)) == (ssize_t)strlen(line), "write served request\n");
    shutdown(peer[1], SHUT_WR);
    server_serve(peer[0], HELPER_CALLER_SUCCESSOR);
    ssize_t count = read(peer[1], reply, sizeof reply - 1);
    reply[count > 0 ? count : 0] = '\0';
    close(peer[0]);
    close(peer[1]);
    CHECK(strcmp(reply, "OK GUARDPREPARE 1 STAGING\n") == 0,
        "server_serve hands the handler the connection's own caller (got %s)\n", reply);
    create_fixed_test_dirs();
}

/* Root acts on no package of its own, but a bound plan says which app a session is for, and it is a
 * record this daemon wrote rather than a name the caller supplied.  So an ADB operator can drive a
 * session the app prepared, and can still cancel it after the app's data directory is gone, when
 * root no longer resolves any package for itself. */
static void test_root_drives_a_bound_plan(void) {
    setup();
    prepare();

    static const char a_payload[] = "exact-installed-a";
    char a_sha[65], command[512], reply[1024];
    hash_bytes(a_payload, sizeof a_payload - 1, a_sha);
    snprintf(command, sizeof command, "GUARDDEFINE %s 1 A %zu %s 568 11 14 14",
        SESSION, sizeof a_payload - 1, a_sha);
    test_caller = HELPER_CALLER_ROOT;
    dispatch_once(command, reply, sizeof reply);
    CHECK(strcmp(reply, "OK GUARDDEFINE 2 STAGING\n") == 0,
        "root drives a session the app prepared (got %s)\n", reply);

    (void)rmdir(DB_DIR);
    (void)rmdir(APP_ROOT "/data/user/0/" APP_PKG);
    CHECK(access(APP_ROOT "/data/user/0/" APP_PKG, F_OK) != 0, "the app data directory is gone\n");
    snprintf(command, sizeof command, "GUARDCANCEL %s 2", SESSION);
    dispatch_once(command, reply, sizeof reply);
    CHECK(strcmp(reply, "OK GUARDCANCEL 3 EMPTY\n") == 0,
        "root cancels a bound session after the app data directory is gone (got %s)\n", reply);
    create_fixed_test_dirs();
}

int main(void) {
    exec_from_canonical_helper();
    signal(SIGPIPE, SIG_IGN);
#ifdef HAPANELD_TEST_STALE_ONLY
    if (1) {
#else
    if (getenv("GUARD_TEST_STALE_ONLY")) {
#endif
        test_stale_replacement_refusal_matrix();
        test_stale_replacement_live_owner_and_postswap_hold();
        test_stale_replacement_durability();
        guard_test_reset();
        return failures ? 1 : 0;
    }
    if (getenv("GUARD_TEST_PLAN_ONLY")) {
        test_plan_stream_restart_roundtrip();
        return failures ? 1 : 0;
    }
    test_sha256_vectors();
    test_guardself_live_helper_identity();
    test_caps_supervision_and_empty_status();
    test_plan_stream_restart_roundtrip();
    test_stream_rejects_wrong_short_and_extra_bytes();
    test_tamper_or_orphan_fails_closed();
    test_atomic_draft_fault_and_owner_lock();
    test_nonreplacement_staging_dirsync_reconciliation();
    test_replacement_respects_installstream_package_gate();
    test_replacement_shared_lock_admission();
    test_replacement_fence_and_same_lease_app_swap();
    test_replacement_export_refuses_foreign_fd3();
    test_replacement_adopt_closes_previous_owner_fd();
    test_stale_replacement_refusal_matrix();
    test_stale_replacement_live_owner_and_postswap_hold();
    test_stale_replacement_durability();
    test_replacement_backup_cut_recovers_exact_old();
    test_replacement_build_mismatch_aborts_before_swap();
    test_replacement_swap_cut_resumes_exact_new();
    test_replacement_request_dirsync_is_indeterminate_and_reconciled();
    test_replacement_record_dirsync_cuts_reconcile();
    test_replacement_synced_temp_cuts_reconcile();
    test_replacement_stage_durability_precedes_fence();
    test_cancel_only_exact_staging();
    for (int mode = 0; mode < 7; mode++) test_failed_capture_recovers_once(mode);
    test_capture_intent_reconciles_after_process_cut(GUARD_TEST_FAULT_MANIFEST_FILE_SYNC);
    test_capture_intent_reconciles_after_process_cut(GUARD_TEST_FAULT_CAPTURE_INTENT_DIR_SYNC);
    test_capture_intent_reconciles_after_process_cut(GUARD_TEST_FAULT_CAPTURE_STOP_DIR_SYNC);
    test_capture_intent_reconciles_after_process_cut(GUARD_TEST_FAULT_BASELINE_DIR_SYNC);
    test_capture_intent_reconciles_after_process_cut(GUARD_TEST_FAULT_MANIFEST_DIR_SYNC);
    test_capture_intent_reconciles_after_process_cut(GUARD_TEST_FAULT_JOURNAL_DIR_SYNC);
    test_capture_intent_reconciles_after_process_cut(
        GUARD_TEST_FAULT_CAPTURE_LEGACY_DRAFT_REMOVED);
    test_capture_intent_reconciles_after_process_cut(
        GUARD_TEST_FAULT_CAPTURE_CLEANUP_AFTER_CAPTURE);
    test_forward_deadline_blocks_late_executor_spawn();
    test_hard_and_recovery_deadline_equality();
    test_boot_change_holds_draft_and_manifest();
    test_capture_manifest_journal_and_b_install(0);
    test_capture_manifest_journal_and_b_install(1);
    test_capture_manifest_journal_and_b_install(2);
    test_capture_manifest_journal_and_b_install(3);
    test_capture_manifest_journal_and_b_install(4);
    test_capture_manifest_journal_and_b_install(5);
    test_capture_manifest_journal_and_b_install(6);
    test_health_failure_rolls_back_once_to_exact_baseline();
    test_rollback_consumed_before_spawn_never_retries();
    test_rollback_spawned_executor_loss_never_retries();
    test_rollback_timeout_with_exact_a_holds();
    test_pm_normal_rejection_rolls_back_with_typed_outcome();
    test_pm_nonzero_target_and_uncertain_target_matrix();
    test_root_resolves_the_present_package();
    test_root_drives_a_bound_plan();
    guard_test_reset();
    (void)unlink(BOOT_FILE);
    if (failures) {
        printf("%d guard maintenance boundary test(s) failed\n", failures);
        return 1;
    }
    puts("guard maintenance boundary tests passed");
    return 0;
}
