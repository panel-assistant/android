#define _GNU_SOURCE
#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/file.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/wait.h>
#include <unistd.h>

#include "cmd.h"
#include "dispatch.h"
#include "guard_maintenance.h"
#include "sha256.h"
#include "version.h"

#define LIVE "/tmp/.hapaneld-helper-live-test"
#define STAGE "/tmp/.hapaneld-helper-live-test.new"
#define PREVIOUS "/tmp/.hapaneld-helper-live-test.previous"
#define LIVE_LINK "/tmp/.hapaneld-helper-live-test.link"
#define OWNER "/tmp/.hapaneld-guard-db-test/.owner.lock"
#define BOOT "/tmp/.hapaneld-guard-db-test.boot-id"
#define REPLACEMENT "/tmp/.hapaneld-guard-db-test/replacement.v1"
#define NONCE "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc"
#define NEW_BUILD "dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd"

static void fail(const char *message) {
    fprintf(stderr, "FAIL: replacement lease process: %s: %s\n", message, strerror(errno));
    exit(1);
}

static void write_all(int fd, const void *bytes, size_t size) {
    const unsigned char *cursor = bytes;
    while (size > 0) {
        ssize_t count = write(fd, cursor, size);
        if (count < 0 && errno == EINTR) continue;
        if (count <= 0) fail("write");
        cursor += (size_t)count;
        size -= (size_t)count;
    }
}

static void copy_file(const char *source, const char *target) {
    int input = open(source, O_RDONLY | O_NOFOLLOW | O_CLOEXEC);
    int output = open(target, O_WRONLY | O_CREAT | O_TRUNC | O_NOFOLLOW | O_CLOEXEC, 0700);
    if (input < 0 || output < 0) fail("open copy");
    unsigned char buffer[65536];
    for (;;) {
        ssize_t count = read(input, buffer, sizeof buffer);
        if (count < 0 && errno == EINTR) continue;
        if (count < 0) fail("read copy");
        if (count == 0) break;
        write_all(output, buffer, (size_t)count);
    }
    if (fchmod(output, 0700) != 0 || fsync(output) != 0 ||
        close(output) != 0 || close(input) != 0) fail("finish copy");
}

static void file_sha(const char *path, char sha[65]) {
    int fd = open(path, O_RDONLY | O_NOFOLLOW | O_CLOEXEC);
    struct stat st;
    if (fd < 0 || fstat(fd, &st) != 0 || st.st_size <= 0 ||
        hapaneld_sha256_fd(fd, (uint64_t)st.st_size, sha) != 0 || close(fd) != 0)
        fail("hash stage");
}

static void prove_lease_held(const char *where) {
    int contender = open(OWNER, O_RDWR | O_NOFOLLOW | O_CLOEXEC);
    if (contender < 0) fail("open contender");
    errno = 0;
    if (flock(contender, LOCK_EX | LOCK_NB) == 0 ||
        (errno != EWOULDBLOCK && errno != EAGAIN)) {
        fprintf(stderr, "FAIL: replacement lease process: lease gap at %s\n", where);
        exit(1);
    }
    close(contender);
}

static void require_caps(const char *expected, const char *where) {
    int pair[2];
    if (socketpair(AF_UNIX, SOCK_STREAM, 0, pair) != 0) fail("caps socketpair");
    char line[] = "GUARDCAPS";
    conn_ctx ctx = { .fd = pair[0], .subscribed = 0 };
    dispatch(&ctx, line);
    shutdown(pair[0], SHUT_WR);
    char reply[512] = {0};
    ssize_t used = read(pair[1], reply, sizeof reply - 1);
    close(pair[0]);
    close(pair[1]);
    if (used <= 0 || strcmp(reply, expected) != 0) {
        fprintf(stderr, "replacement lease process: CAPS mismatch at %s: %s\n",
                where, reply);
        exit(1);
    }
}

static void issue_retire(char sha[65]) {
    int pair[2];
    if (socketpair(AF_UNIX, SOCK_STREAM, 0, pair) != 0) fail("socketpair");
    pid_t child = fork();
    if (child < 0) fail("fork retire");
    if (child == 0) {
        close(pair[1]);
        char line[512];
        snprintf(line, sizeof line, "GUARDRETIRE APP %s %s %s", NONCE, sha, NEW_BUILD);
        conn_ctx ctx = { .fd = pair[0], .subscribed = 0 };
        dispatch(&ctx, line);
        _exit(99);
    }
    close(pair[0]);
    char reply[128] = {0};
    ssize_t used = read(pair[1], reply, sizeof reply - 1);
    close(pair[1]);
    int status = 0;
    if (used <= 0 || waitpid(child, &status, 0) != child || !WIFEXITED(status) ||
        WEXITSTATUS(status) != GUARD_REPLACEMENT_EXIT ||
        strcmp(reply, "OK GUARDRETIRE 1 REQUESTED\n") != 0)
        fail("retire receipt");
}

/* A boot-owned helper may have an unrelated, stale file at LIVE. Retirement must refuse before
 * writing a fence or exiting: its supervisor cannot grant a lease for that other executable. */
static int foreign_live_retirement(void) {
    unlink(REPLACEMENT);
    guard_test_reset();
    unlink(BOOT);
    int boot = open(BOOT, O_WRONLY | O_CREAT | O_TRUNC | O_CLOEXEC, 0600);
    static const char boot_id[] = "123e4567-e89b-12d3-a456-426614174000";
    if (boot < 0) fail("refusal boot open");
    write_all(boot, boot_id, sizeof boot_id - 1);
    if (fsync(boot) != 0 || close(boot) != 0) fail("refusal boot sync");
    char executable[4096];
    ssize_t length = readlink("/proc/self/exe", executable, sizeof executable - 1);
    if (length <= 0 || (size_t)length >= sizeof executable - 1) fail("refusal executable");
    executable[length] = '\0';
    copy_file(executable, LIVE);
    int staged = open(STAGE, O_WRONLY | O_CREAT | O_TRUNC | O_CLOEXEC, 0700);
    static const char candidate[] = "distinct-staged-helper";
    if (staged < 0) fail("refusal stage open");
    write_all(staged, candidate, sizeof candidate - 1);
    if (fchmod(staged, 0700) || fsync(staged) || close(staged)) fail("refusal stage sync");
    char sha[65], live_sha[65], after_sha[65];
    file_sha(STAGE, sha);
    file_sha(LIVE, live_sha);
    if (guard_test_reconcile() != 0) fail("refusal guard init");
    guard_maintenance_set_supervisor_owner();
    guard_test_set_supervised(1);
    int pair[2];
    if (socketpair(AF_UNIX, SOCK_STREAM, 0, pair) != 0) fail("refusal socketpair");
    pid_t child = fork();
    if (child < 0) fail("refusal fork");
    if (child == 0) {
        close(pair[1]);
        char line[512];
        snprintf(line, sizeof line, "GUARDRETIRE APP %s %s %s", NONCE, sha, NEW_BUILD);
        conn_ctx ctx = { .fd = pair[0], .caller = HELPER_CALLER_ROOT };
        dispatch(&ctx, line);
        _exit(0);
    }
    close(pair[0]);
    char response[256] = {0};
    ssize_t used = read(pair[1], response, sizeof response - 1);
    close(pair[1]);
    int status = 0;
    int waited = waitpid(child, &status, 0) == child;
    int held = used > 0 && waited && WIFEXITED(status) && WEXITSTATUS(status) == 0 &&
        strcmp(response, "ERR HOLD topology\n") == 0 && access(REPLACEMENT, F_OK) != 0;
    file_sha(LIVE, after_sha);
    held = held && strcmp(live_sha, after_sha) == 0;
    file_sha(STAGE, after_sha);
    held = held && strcmp(sha, after_sha) == 0;
    if (!held) {
        printf("FAIL: foreign serving inode refuses retirement before publication (got %s)\n", response);
        unlink(REPLACEMENT);
        guard_test_reset();
        return 1;
    }
    require_caps("OK GUARDCAPS 1 PREPARE DEFINE STREAM ACTION HEALTH REFUSAL STATUS EVIDENCE CANCEL RETIRE JOURNAL SUPERVISED TERMINAL_RETIRE\n",
                 "foreign live refusal retains service");
    guard_test_reset();
    puts("foreign live retirement refusal passed");
    return 0;
}

static int coordinator(const char *old_binary, const char *new_binary) {
    if (foreign_live_retirement() != 0) return 1;
    guard_test_reset();
    unlink(BOOT);
    int boot = open(BOOT, O_WRONLY | O_CREAT | O_TRUNC | O_CLOEXEC, 0600);
    static const char boot_id[] = "123e4567-e89b-12d3-a456-426614174000";
    if (boot < 0) fail("boot open");
    write_all(boot, boot_id, sizeof boot_id - 1);
    if (fsync(boot) != 0 || close(boot) != 0) fail("boot sync");
    copy_file(old_binary, LIVE);
    copy_file(new_binary, STAGE);
    execl(LIVE, "replacement-lease-old", "--fixture-old", NULL);
    fail("exec old live");
    return 1;
}

static int old_role(void) {
    if (guard_test_reconcile() != 0) fail("old guard init");
    guard_maintenance_set_supervisor_owner();
    char sha[65], nonce[65];
    file_sha(STAGE, sha);
    issue_retire(sha);
    if (guard_maintenance_replacement_parent_grant(nonce) != GUARD_REPLACEMENT_READY ||
        strcmp(nonce, NONCE) != 0 || guard_maintenance_replacement_export_lease() != 0)
        fail("grant/export");
    if (guard_maintenance_replacement_parent_abort(nonce) != 0)
        fail("abort granted replacement");
    prove_lease_held("aborted replacement retains authoritative owner");
    int flags = fcntl(GUARD_REPLACEMENT_LEASE_FD, F_GETFD, 0);
    if (flags < 0 || !(flags & FD_CLOEXEC)) fail("aborted authoritative lease disables exec inheritance");
    issue_retire(sha);
    if (guard_maintenance_replacement_parent_grant(nonce) != GUARD_REPLACEMENT_READY ||
        strcmp(nonce, NONCE) != 0) fail("grant after aborted replacement");
    prove_lease_held("old-to-stage");
    execl(STAGE, "replacement-lease-new", "--fixture-stage", nonce, NULL);
    fail("exec stage");
    return 1;
}

static int stage_role(const char *nonce) {
    int flags = fcntl(GUARD_REPLACEMENT_LEASE_FD, F_GETFD, 0);
    if (flags < 0 || (flags & FD_CLOEXEC) != 0) fail("stage inherited lease flags");
    prove_lease_held("stage");
    if (guard_maintenance_replacement_stage_app(nonce) != 0) fail("stage swap");
    prove_lease_held("stage-to-live");
    execl(LIVE, "replacement-lease-new", "--fixture-new", nonce, NULL);
    fail("exec new live");
    return 1;
}

static int new_role(const char *nonce) {
    prove_lease_held("new-before-adopt");
    if (strcmp(helper_build_id(), NEW_BUILD) != 0 ||
        guard_maintenance_replacement_supervisor_adopt_app(nonce) != 0)
        fail("new adopt");
    int flags = fcntl(GUARD_REPLACEMENT_LEASE_FD, F_GETFD, 0);
    if (flags < 0 || (flags & FD_CLOEXEC) == 0) fail("new lease flags");
    guard_test_set_supervised(1);
    if (guard_maintenance_replacement_worker_commit_app(nonce) != 0 ||
        access(PREVIOUS, F_OK) == 0) fail("new commit");
    prove_lease_held("new-committed");
    static const char autonomous_caps[] =
        "OK GUARDCAPS 1 PREPARE DEFINE STREAM ACTION HEALTH REFUSAL STATUS EVIDENCE CANCEL RETIRE JOURNAL AUTONOMOUS SUPERVISED TERMINAL_RETIRE\n";
    static const char supervised_caps[] =
        "OK GUARDCAPS 1 PREPARE DEFINE STREAM ACTION HEALTH REFUSAL STATUS EVIDENCE CANCEL RETIRE JOURNAL SUPERVISED TERMINAL_RETIRE\n";
    require_caps(autonomous_caps, "exact live APP worker");
    if (chmod(LIVE, 0755) != 0) fail("mutate live mode");
    require_caps(supervised_caps, "wrong live mode");
    if (chmod(LIVE, 0700) != 0 || link(LIVE, LIVE_LINK) != 0) fail("mutate live nlink");
    require_caps(supervised_caps, "multiply-linked live binary");
    if (unlink(LIVE_LINK) != 0) fail("remove live hardlink");
    require_caps(autonomous_caps, "restored live identity");
    guard_test_drop_runtime();
    require_caps(supervised_caps, "missing owner lease");
    int contender = open(OWNER, O_RDWR | O_NOFOLLOW | O_CLOEXEC);
    if (contender < 0 || flock(contender, LOCK_EX | LOCK_NB) != 0)
        fail("lease release after fixture");
    close(contender);
    puts("replacement lease process tests passed");
    return 0;
}

int main(int argc, char **argv) {
    if (argc == 1) {
        char old_binary[PATH_MAX], new_binary[PATH_MAX];
        ssize_t size = readlink("/proc/self/exe", old_binary, sizeof old_binary - 1);
        if (size <= 0 || (size_t)size >= sizeof old_binary - 1) fail("resolve fixture executable");
        old_binary[size] = 0;
        char *leaf = strrchr(old_binary, '/');
        if (!leaf) fail("resolve fixture directory");
        int count = snprintf(new_binary, sizeof new_binary, "%.*s/replacement-lease-new",
                             (int)(leaf - old_binary), old_binary);
        if (count < 0 || (size_t)count >= sizeof new_binary) fail("resolve successor fixture");
        return coordinator(old_binary, new_binary);
    }
    if (argc == 3 && strcmp(argv[1], "--fixture-stage") == 0) return stage_role(argv[2]);
    if (argc == 3 && strcmp(argv[1], "--fixture-new") == 0) return new_role(argv[2]);
    if (argc == 2 && strcmp(argv[1], "--fixture-old") == 0) return old_role();
    if (argc == 3) return coordinator(argv[1], argv[2]);
    fprintf(stderr, "usage: %s OLD NEW\n", argv[0]);
    return 2;
}
