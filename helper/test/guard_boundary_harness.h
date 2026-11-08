/* Shared fixture for the Guard DB boundary test binaries: paths, the dispatch harness, the plan
 * and stream helpers. Each binary includes it once; a binary that leaves a helper unused is fine. */
#ifndef HAPANELD_GUARD_BOUNDARY_HARNESS_H
#define HAPANELD_GUARD_BOUNDARY_HARNESS_H
#pragma GCC diagnostic push
#pragma GCC diagnostic ignored "-Wunused-function"
#pragma GCC diagnostic ignored "-Wunused-variable"

#define _GNU_SOURCE
#include "test_tmp.h"
#include <errno.h>
#include <fcntl.h>
#include <pthread.h>
#include <signal.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/file.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <sys/wait.h>
#include <time.h>
#include <unistd.h>

#include "cmd.h"
#include "dispatch.h"
#include "guard_maintenance.h"
#include "server.h"
#include "sha256.h"
#include "sysexec_stub.h"

#define CUSTODY HAPANELD_TEST_TMP "/.hapaneld-guard-db-test"
#define BOOT_FILE HAPANELD_TEST_TMP "/.hapaneld-guard-db-test.boot-id"
#define APP_ROOT HAPANELD_TEST_TMP "/.hapaneld-guard-app-test"
#define APP_PKG "io.panelassistant.android"
#define DB_DIR APP_ROOT "/data/user/0/" APP_PKG "/databases"
#define DB_PATH DB_DIR "/ha-paneld.db"
#define INSTALLED_DIR HAPANELD_TEST_TMP "/.hapaneld-guard-installed"
#define INSTALLED_APK INSTALLED_DIR "/base.apk"
#define SESSION "1111111111111111111111111111111111111111111111111111111111111111"
#define SIGNER  "2222222222222222222222222222222222222222222222222222222222222222"
#define DB_SHA  "3333333333333333333333333333333333333333333333333333333333333333"
#define STATE_SHA "9b568de442ed62849bf3990ba7d9415ac0d497c5f9ebd12d7cab32e64407fe31"
#define SETTINGS_SHA "7d472f0c85867fe68bc231b7fe468b2340010eb8f13cd8e8c211708aadf0ffc3"
#define SETTINGS_AUTHORITY_SHA "b8f4f5eef03fd49c12195d0ec600511d1f56e68c77c994e2eba4840c1cac90b7"
#define REPLACEMENT_BUILD "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
#define WRONG_REPLACEMENT_BUILD "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc"
#define REPLACEMENT_NONCE "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
#define HELPER_LIVE HAPANELD_TEST_TMP "/.hapaneld-helper-live-test"
#define HELPER_STAGE HAPANELD_TEST_TMP "/.hapaneld-helper-live-test.new"
#define HELPER_PREVIOUS HAPANELD_TEST_TMP "/.hapaneld-helper-live-test.previous"
#define SHARED_LOCK HAPANELD_TEST_TMP "/.hapaneld-helper-transaction-lock-test"
#define SHARED_LOCK_PID SHARED_LOCK "/pid"
static const char SETTINGS_AUTHORITY[] =
    "S2\n616c706861|737472696e67|64656661756c74\n";

// Guard binds a session to the package the CALLER is, and on the socket that comes from
// SO_PEERCRED, resolved once in the accept loop.  This harness calls the handlers directly, so it
// supplies the same identity here: the app caller by default.
static enum helper_caller test_caller = HELPER_CALLER_SUCCESSOR;

static int failures;
#define CHECK(condition, ...) do { if (!(condition)) { \
    printf("FAIL: " __VA_ARGS__); printf("  (%s:%d)\n", __FILE__, __LINE__); failures++; } } while (0)

typedef struct {
    int fd;
    char line[MAX_LINE + 1];
} dispatch_job;

static int write_all(int fd, const void *bytes, size_t size) {
    const unsigned char *p = bytes;
    while (size > 0) {
        ssize_t count = write(fd, p, size);
        if (count < 0 && errno == EINTR) continue;
        if (count <= 0) return -1;
        p += (size_t)count;
        size -= (size_t)count;
    }
    return 0;
}

static void write_file_mode(const char *path, const void *bytes, size_t size, mode_t mode) {
    int fd = open(path, O_WRONLY | O_CREAT | O_TRUNC, mode);
    if (fd < 0 || fchmod(fd, mode) != 0 || write_all(fd, bytes, size) != 0 ||
        fsync(fd) != 0 || close(fd) != 0) {
        perror(path);
        exit(2);
    }
}

static int file_equals(const char *path, const void *bytes, size_t size) {
    int fd = open(path, O_RDONLY | O_NOFOLLOW | O_CLOEXEC);
    struct stat st;
    unsigned char buffer[512];
    if (fd < 0 || size > sizeof buffer || fstat(fd, &st) != 0 ||
        !S_ISREG(st.st_mode) || (size_t)st.st_size != size) {
        if (fd >= 0) close(fd);
        return 0;
    }
    size_t used = 0;
    while (used < size) {
        ssize_t count = read(fd, buffer + used, size - used);
        if (count < 0 && errno == EINTR) continue;
        if (count <= 0) { close(fd); return 0; }
        used += (size_t)count;
    }
    unsigned char extra;
    ssize_t extra_count;
    do extra_count = read(fd, &extra, 1); while (extra_count < 0 && errno == EINTR);
    close(fd);
    return extra_count == 0 && memcmp(buffer, bytes, size) == 0;
}

static ssize_t read_line(int fd, char *output, size_t capacity) {
    size_t used = 0;
    while (used + 1 < capacity) {
        ssize_t count = read(fd, output + used, 1);
        if (count < 0 && errno == EINTR) continue;
        if (count <= 0) break;
        if (output[used++] == '\n') break;
    }
    output[used] = '\0';
    return (ssize_t)used;
}

static void *dispatch_worker(void *argument) {
    dispatch_job *job = argument;
    conn_ctx ctx = { .fd = job->fd, .subscribed = 0, .caller = test_caller };
    dispatch(&ctx, job->line);
    close(job->fd);
    return NULL;
}

static void start_dispatch(dispatch_job *job, pthread_t *thread, int peer[2], const char *line) {
    CHECK(socketpair(AF_UNIX, SOCK_STREAM, 0, peer) == 0, "create protocol socket\n");
    memset(job, 0, sizeof *job);
    job->fd = peer[0];
    snprintf(job->line, sizeof job->line, "%s", line);
    CHECK(pthread_create(thread, NULL, dispatch_worker, job) == 0, "start dispatch worker\n");
}

static void finish_dispatch(pthread_t thread, int peer) {
    pthread_join(thread, NULL);
    close(peer);
}

static void dispatch_once(const char *line, char *output, size_t capacity) {
    int peer[2];
    CHECK(socketpair(AF_UNIX, SOCK_STREAM, 0, peer) == 0, "create direct socket\n");
    char command[MAX_LINE + 1];
    snprintf(command, sizeof command, "%s", line);
    conn_ctx ctx = { .fd = peer[0], .subscribed = 0, .caller = test_caller };
    dispatch(&ctx, command);
    shutdown(peer[0], SHUT_WR);
    size_t used = 0;
    while (used + 1 < capacity) {
        ssize_t count = read(peer[1], output + used, capacity - 1 - used);
        if (count < 0 && errno == EINTR) continue;
        if (count <= 0) break;
        used += (size_t)count;
    }
    output[used] = '\0';
    close(peer[0]);
    close(peer[1]);
}

static void hash_bytes(const void *bytes, size_t size, char output[65]) {
    hapaneld_sha256 ctx;
    unsigned char digest[32];
    hapaneld_sha256_init(&ctx);
    hapaneld_sha256_update(&ctx, bytes, size);
    hapaneld_sha256_final(&ctx, digest);
    hapaneld_sha256_hex(digest, output);
}

static void exec_from_canonical_helper(void) {
    if (getenv("GUARD_TEST_CANONICAL_HELPER")) return;
    (void)unlink(HELPER_LIVE);
    (void)unlink(HELPER_STAGE);
    int source = open("/proc/self/exe", O_RDONLY | O_CLOEXEC);
    int output = open(HELPER_LIVE,
        O_WRONLY | O_CREAT | O_EXCL | O_NOFOLLOW | O_CLOEXEC, 0700);
    unsigned char buffer[65536];
    int copied = source >= 0 && output >= 0;
    for (;;) {
        ssize_t count = copied ? read(source, buffer, sizeof buffer) : -1;
        if (count < 0 && errno == EINTR) continue;
        if (count < 0) copied = 0;
        if (count <= 0) break;
        if (write_all(output, buffer, (size_t)count) != 0) {
            copied = 0;
            break;
        }
    }
    copied = copied && fchmod(output, 0700) == 0 && fsync(output) == 0;
    if (source >= 0) close(source);
    if (output >= 0 && close(output) != 0) copied = 0;
    if (!copied || setenv("GUARD_TEST_CANONICAL_HELPER", "1", 1) != 0) {
        perror("prepare canonical helper test executable");
        exit(2);
    }
    for (int fd = 3; fd < 1024; fd++) (void)close(fd);
    char *const canonical_argv[] = { (char *)HELPER_LIVE, NULL };
    execv(HELPER_LIVE, canonical_argv);
    perror("exec canonical helper test executable");
    exit(2);
}

static void ascii_hex_bytes(const char *input, char *output, size_t capacity) {
    static const char digits[] = "0123456789abcdef";
    size_t size = strlen(input);
    CHECK(size > 0 && size * 2 + 1 <= capacity, "restore receipt hex input fits\n");
    if (size == 0 || size * 2 + 1 > capacity) {
        if (capacity > 0) output[0] = '\0';
        return;
    }
    for (size_t index = 0; index < size; index++) {
        unsigned char byte = (unsigned char)input[index];
        output[index * 2] = digits[byte >> 4];
        output[index * 2 + 1] = digits[byte & 15];
    }
    output[size * 2] = '\0';
}

static void write_restore_body_at(const char *path, const char *body) {
    char checksum[65], record[2200];
    size_t body_length = strlen(body);
    CHECK(body_length > 0 && body_length < 2048 && body[body_length - 1] == '\n',
        "restore receipt body is bounded and newline-terminated\n");
    if (body_length == 0 || body_length >= 2048 || body[body_length - 1] != '\n') return;
    hash_bytes(body, body_length, checksum);
    int record_length = snprintf(record, sizeof record, "%sCHECKSUM %s\n", body, checksum);
    CHECK(record_length > 0 && (size_t)record_length < sizeof record,
        "restore receipt record is bounded\n");
    if (record_length <= 0 || (size_t)record_length >= sizeof record) return;
    write_file_mode(path, record, (size_t)record_length, 0600);
}

static void write_restore_receipt(const char *state, uint32_t source_schema,
                                  uint64_t source_bytes, const char source_sha[65],
                                  uint32_t staged_schema, uint64_t staged_bytes,
                                  const char staged_sha[65], uint64_t generation) {
    char dir_hex[1025], name_hex[321], superseded_hex[321];
    ascii_hex_bytes(DB_DIR, dir_hex, sizeof dir_hex);
    ascii_hex_bytes("ha-paneld.db", name_hex, sizeof name_hex);
    ascii_hex_bytes("ha-paneld.db.v15.superseded", superseded_hex,
                    sizeof superseded_hex);
    char body[2048];
    int body_length = snprintf(body, sizeof body,
        "HAPANELD_DATABASE_RESTORE_V1\n"
        "STATE %s\n"
        "TARGET %s %s\n"
        "SOURCE %u %llu %s\n"
        "STAGED %u %llu %s\n"
        "SUPERSEDED %s\n"
        "GUARD %s %llu\n",
        state, dir_hex, name_hex, source_schema,
        (unsigned long long)source_bytes, source_sha, staged_schema,
        (unsigned long long)staged_bytes, staged_sha, superseded_hex, SESSION,
        (unsigned long long)generation);
    CHECK(body_length > 0 && (size_t)body_length < sizeof body,
        "restore receipt body is bounded\n");
    if (body_length <= 0 || (size_t)body_length >= sizeof body) return;
    write_restore_body_at(DB_DIR "/.ha-paneld.db.restore.v1", body);
}

static void mutate_restore_receipt(const char *needle, const char *replacement) {
    char record[2200], body[2200];
    int fd = open(DB_DIR "/.ha-paneld.db.restore.v1", O_RDONLY | O_NOFOLLOW | O_CLOEXEC);
    ssize_t used = fd >= 0 ? read(fd, record, sizeof record - 1) : -1;
    if (fd >= 0) close(fd);
    CHECK(used > 0, "read restore receipt for mutation\n");
    if (used <= 0) return;
    record[used] = '\0';
    char *checksum = strstr(record, "CHECKSUM ");
    char *match = strstr(record, needle);
    CHECK(checksum != NULL && match != NULL && match < checksum,
        "restore receipt mutation target is unique before checksum\n");
    if (!checksum || !match || match >= checksum) return;
    size_t prefix = (size_t)(match - record);
    size_t suffix = (size_t)(checksum - (match + strlen(needle)));
    size_t replacement_size = strlen(replacement);
    CHECK(prefix + replacement_size + suffix < sizeof body,
        "mutated restore receipt body fits\n");
    if (prefix + replacement_size + suffix >= sizeof body) return;
    memcpy(body, record, prefix);
    memcpy(body + prefix, replacement, replacement_size);
    memcpy(body + prefix + replacement_size, match + strlen(needle), suffix);
    body[prefix + replacement_size + suffix] = '\0';
    write_restore_body_at(DB_DIR "/.ha-paneld.db.restore.v1", body);
}

static void check_status_with_deadlines(const char *actual, const char *prefix,
                                        const char *description) {
    size_t prefix_size = strlen(prefix);
    unsigned long long overall = 0, forward = 0;
    char extra = 0;
    CHECK(strncmp(actual, prefix, prefix_size) == 0 &&
          sscanf(actual + prefix_size, "%llu %llu\n%c", &overall, &forward, &extra) == 2 &&
          overall > forward && overall - forward == 480000ULL,
        "%s (got %s)\n", description, actual);
}

static char boot_nonce[65];

static void mkdir_if_missing(const char *path, mode_t mode) {
    if (mkdir(path, mode) != 0 && errno != EEXIST) {
        perror(path);
        exit(2);
    }
}

static void create_fixed_test_dirs(void) {
    mkdir_if_missing(APP_ROOT, 0700);
    mkdir_if_missing(APP_ROOT "/data", 0700);
    mkdir_if_missing(APP_ROOT "/data/user", 0700);
    mkdir_if_missing(APP_ROOT "/data/user/0", 0700);
    mkdir_if_missing(APP_ROOT "/data/user/0/" APP_PKG, 0700);
    mkdir_if_missing(DB_DIR, 0700);
    mkdir_if_missing(INSTALLED_DIR, 0700);
}

static void setup(void) {
    test_caller = HELPER_CALLER_SUCCESSOR;
    guard_test_reset();
    sysexec_stub_reset();
    create_fixed_test_dirs();
    static const char *const leftovers[] = {
        DB_PATH, DB_PATH "-wal", DB_PATH "-shm", DB_PATH "-journal", DB_PATH ".restore.tmp",
        DB_PATH ".v14.premigrate", DB_PATH ".v14.premigrate.guard.tmp",
        DB_PATH ".v15.superseded", DB_PATH ".v15.superseded-wal",
        DB_PATH ".v15.superseded-shm", DB_PATH ".v15.superseded-journal",
        DB_DIR "/.ha-paneld.db.restore.v1",
        DB_DIR "/.ha-paneld.db.restore.v1.tmp",
        DB_DIR "/.ha-paneld.db.restore.prepared.v1",
        DB_DIR "/.guard.rollback.tmp",
        INSTALLED_APK,
    };
    for (size_t i = 0; i < sizeof leftovers / sizeof leftovers[0]; i++) (void)unlink(leftovers[i]);
    static const char boot_id[] = "123e4567-e89b-12d3-a456-426614174000";
    write_file_mode(BOOT_FILE, boot_id, sizeof boot_id - 1, 0600);
    static const unsigned char boot_domain[] = "hapaneld-guard-boot-v1";
    hapaneld_sha256 boot_hash;
    unsigned char boot_digest[32];
    hapaneld_sha256_init(&boot_hash);
    hapaneld_sha256_update(&boot_hash, boot_domain, sizeof boot_domain);
    hapaneld_sha256_update(&boot_hash, boot_id, sizeof boot_id - 1);
    hapaneld_sha256_final(&boot_hash, boot_digest);
    hapaneld_sha256_hex(boot_digest, boot_nonce);
    CHECK(strcmp(boot_nonce, "aa86f3516c79cbfa085067c6b8488e4d8d0b2d1deeeb5b6442d82de7851dbd23") == 0,
        "boot nonce domain-separated fixed vector\n");
    CHECK(guard_test_reconcile() == 0, "empty startup reconciliation succeeds\n");
    guard_test_set_app_autonomous_profile(1);
}

static void write_shared_lock_pid(pid_t pid) {
    char record[32];
    int length = snprintf(record, sizeof record, "%ld\n", (long)pid);
    CHECK(mkdir(SHARED_LOCK, 0700) == 0, "create shared helper-lock fixture\n");
    if (length > 1 && (size_t)length < sizeof record)
        write_file_mode(SHARED_LOCK_PID, record, (size_t)length, 0600);
}

static void prepare_exact_baseline(const void *database, size_t size, char db_sha[65]) {
    char command[512], reply[512];
    guard_test_set_supervised(1);
    hash_bytes(database, size, db_sha);
    snprintf(command, sizeof command,
        "GUARDPREPARE %s %s %s %zu %s 14 2 %s %s 900000 2 %zu %s",
        SESSION, boot_nonce, SIGNER, size, db_sha, STATE_SHA, SETTINGS_SHA,
        sizeof SETTINGS_AUTHORITY - 1, SETTINGS_AUTHORITY_SHA);
    dispatch_once(command, reply, sizeof reply);
    CHECK(strcmp(reply, "OK GUARDPREPARE 1 STAGING\n") == 0,
        "exact baseline prepare succeeds (got %s)\n", reply);
}

static void prepare_command(char output[512]) {
    snprintf(output, 512,
        "GUARDPREPARE %s %s %s 8 %s 14 2 %s %s 900000 2 %zu %s",
        SESSION, boot_nonce, SIGNER, DB_SHA, STATE_SHA, SETTINGS_SHA,
        sizeof SETTINGS_AUTHORITY - 1, SETTINGS_AUTHORITY_SHA);
}

static void prepare(void) {
    char command[512], reply[1024];
    guard_test_set_supervised(1);
    prepare_command(command);
    dispatch_once(command, reply, sizeof reply);
    CHECK(strcmp(reply, "OK GUARDPREPARE 1 STAGING\n") == 0,
        "prepare publishes generation one (got %s)\n", reply);
}

static void define_role(const char *role, uint64_t generation, const char *sha,
                        uint64_t version_code, unsigned minimum, unsigned maximum,
                        unsigned expected, uint64_t bytes, uint64_t next_generation) {
    char command[512], reply[512];
    snprintf(command, sizeof command,
        "GUARDDEFINE %s %llu %s %llu %s %llu %u %u %u",
        SESSION, (unsigned long long)generation, role, (unsigned long long)bytes, sha,
        (unsigned long long)version_code, minimum, maximum, expected);
    dispatch_once(command, reply, sizeof reply);
    char expected_reply[128];
    snprintf(expected_reply, sizeof expected_reply, "OK GUARDDEFINE %llu STAGING\n",
        (unsigned long long)next_generation);
    CHECK(strcmp(reply, expected_reply) == 0, "define %s exact reply (got %s)\n", role, reply);
}

static void stream_role(const char *role, uint64_t generation, const char *sha,
                        const void *payload, size_t size, uint64_t next_generation) {
    char command[512], reply[512];
    snprintf(command, sizeof command, "GUARDSTREAM %s %llu %s %zu %s",
        SESSION, (unsigned long long)generation, role, size, sha);
    dispatch_job job;
    pthread_t thread;
    int peer[2];
    start_dispatch(&job, &thread, peer, command);
    read_line(peer[1], reply, sizeof reply);
    CHECK(strcmp(reply, "READY\n") == 0, "stream %s is two-phase (got %s)\n", role, reply);
    CHECK(write_all(peer[1], payload, size) == 0, "write %s payload\n", role);
    shutdown(peer[1], SHUT_WR);
    read_line(peer[1], reply, sizeof reply);
    char expected[128];
    snprintf(expected, sizeof expected, "OK GUARDSTREAM %llu STAGING\n",
        (unsigned long long)next_generation);
    CHECK(strcmp(reply, expected) == 0, "stream %s terminal receipt exact (got %s)\n", role, reply);
    finish_dispatch(thread, peer[1]);
}

static void stream_role_expect(const char *role, uint64_t generation, const char *sha,
                               const void *payload, size_t size, const char *terminal) {
    char command[512], reply[512];
    snprintf(command, sizeof command, "GUARDSTREAM %s %llu %s %zu %s",
        SESSION, (unsigned long long)generation, role, size, sha);
    dispatch_job job;
    pthread_t thread;
    int peer[2];
    start_dispatch(&job, &thread, peer, command);
    read_line(peer[1], reply, sizeof reply);
    CHECK(strcmp(reply, "READY\n") == 0,
        "faulted stream %s is admitted before custody publication (got %s)\n", role, reply);
    CHECK(write_all(peer[1], payload, size) == 0, "write faulted %s payload\n", role);
    shutdown(peer[1], SHUT_WR);
    read_line(peer[1], reply, sizeof reply);
    CHECK(strcmp(reply, terminal) == 0,
        "faulted stream %s has exact terminal classification (got %s)\n", role, reply);
    finish_dispatch(thread, peer[1]);
}

static void reach_submitted_b_for_pm_result(char a_sha[65], char b_sha[65],
                                            const char **b_payload_out,
                                            size_t *b_bytes_out, pid_t *executor) {
    static const char a_payload[] = "pm-result-installed-a";
    static const char b_payload[] = "pm-result-candidate-b";
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
    CHECK(work == GUARD_WORK_INSTALL_B &&
          guard_maintenance_supervisor_start_work(
              (enum guard_supervisor_work)work, executor) == 0,
        "PM result fixture reaches one submitted B executor\n");
    *b_payload_out = b_payload;
    *b_bytes_out = sizeof b_payload - 1;
}

static void reach_prepared_at(uint64_t now_ms, char a_sha[65], char b_sha[65]) {
    setup();
    guard_test_set_now_ms(now_ms);
    unsigned char database[128] = {0};
    memcpy(database, "SQLite format 3", 15);
    database[15] = 0;
    database[63] = 14;
    char db_sha[65];
    write_file_mode(DB_PATH, database, sizeof database, 0600);
    prepare_exact_baseline(database, sizeof database, db_sha);
    static const char a_payload[] = "boundary-installed-a";
    static const char b_payload[] = "boundary-candidate-b";
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
        "deadline/boot fixture reaches durable PREPARED (got %s)\n", reply);
}

#pragma GCC diagnostic pop
#endif
