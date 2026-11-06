#define _GNU_SOURCE
#include <inttypes.h>
#include <poll.h>

/* Keep the real command-line admission and the real existing startup reconciler. The fixture
 * substitutes only the shared test paths; it never calls the new reconciliation API directly,
 * so the same file compiles against the old helper and fails on observable missing behavior. */
#define SOCK_NAME "hapaneld-stale-replacement-cli-test"
#define main helper_main
#include "../src/main.c"
#undef main
#include "guard_boundary_harness.h"

static const char old_bytes[] = "prior-boot-old-helper";
static const char new_bytes[] = "prior-boot-new-helper";

static void reset_case(void) {
    guard_test_drop_runtime();
    (void)unlink(CUSTODY "/replacement.v1");
    (void)unlink(CUSTODY "/.replacement.v1.tmp");
    setup();
    guard_test_drop_runtime();
}

static void retained_replacement(const char *phase) {
    reset_case();
    write_file_mode(HELPER_LIVE, old_bytes, sizeof old_bytes - 1, 0700);
    write_file_mode(HELPER_STAGE, new_bytes, sizeof new_bytes - 1, 0700);
    struct stat old_st, new_st;
    if (lstat(HELPER_LIVE, &old_st) != 0 || lstat(HELPER_STAGE, &new_st) != 0) abort();
    char old_sha[65], new_sha[65], checksum[65], body[2048], record[2200];
    hash_bytes(old_bytes, sizeof old_bytes - 1, old_sha);
    hash_bytes(new_bytes, sizeof new_bytes - 1, new_sha);
    int length = snprintf(body, sizeof body,
        "R1 %s %s APP %s %u %zu %s %" PRIu64 " %" PRIu64
        " %zu %s %" PRIu64 " %" PRIu64 " %s\n",
        boot_nonce, REPLACEMENT_NONCE, phase, !strcmp(phase, "REQUESTED") ? 1U : 2U,
        sizeof old_bytes - 1, old_sha, (uint64_t)old_st.st_dev, (uint64_t)old_st.st_ino,
        sizeof new_bytes - 1, new_sha, (uint64_t)new_st.st_dev, (uint64_t)new_st.st_ino,
        REPLACEMENT_BUILD);
    if (length <= 0 || (size_t)length >= sizeof body) abort();
    hash_bytes(body, (size_t)length, checksum);
    length = snprintf(record, sizeof record, "%sSHA256 %s\n", body, checksum);
    if (length <= 0 || (size_t)length >= sizeof record) abort();
    write_file_mode(CUSTODY "/replacement.v1", record, (size_t)length, 0600);
    static const char next_boot[] = "123e4567-e89b-12d3-a456-426614174001";
    write_file_mode(BOOT_FILE, next_boot, sizeof next_boot - 1, 0600);
}

/* Bound even an old binary that interprets the unknown flag as a daemon launch. Each invocation
 * owns a fresh process group; cleanup never selects a host process by name or scans /proc. */
static int invoke(int extra_argument, int unprivileged, char output[128]) {
    fflush(NULL);
    int channel[2];
    if (pipe2(channel, O_CLOEXEC | O_NONBLOCK) != 0) abort();
    pid_t child = fork();
    if (child == 0) {
        (void)setpgid(0, 0);
        close(channel[0]);
        if (dup2(channel[1], STDOUT_FILENO) < 0) _exit(98);
        close(channel[1]);
        if (unprivileged && (setresgid(65534, 65534, 65534) != 0 ||
                            setresuid(65534, 65534, 65534) != 0)) _exit(97);
        char *command[] = { (char *)"hapaneld-helper",
            (char *)"--reconcile-stale-replacement", (char *)"extra", NULL };
        int result = helper_main(extra_argument ? 3 : 2, command);
        fflush(NULL);
        _exit(result);
    }
    if (child < 0) abort();
    close(channel[1]);
    (void)setpgid(child, child);
    long long deadline = monotonic_millis() + 2000;
    size_t used = 0;
    int status = 0, reaped = 0;
    while (monotonic_millis() < deadline) {
        ssize_t count = read(channel[0], output + used, 127 - used);
        if (count > 0) used += (size_t)count;
        if (!reaped) reaped = waitpid(child, &status, WNOHANG) == child;
        if (reaped && count == 0) break;
        if (used == 127) break;
        struct pollfd descriptor = { .fd = channel[0], .events = POLLIN };
        (void)poll(&descriptor, 1, 10);
    }
    output[used] = '\0';
    (void)kill(-child, SIGKILL);
    if (!reaped) {
        while (waitpid(child, &status, 0) < 0 && errno == EINTR) { }
        close(channel[0]);
        return -1;
    }
    close(channel[0]);
    return WIFEXITED(status) ? WEXITSTATUS(status) : -1;
}

static void test_prior_boot(const char *phase) {
    retained_replacement(phase);
    char nonce[65], reply[128];
    CHECK(guard_maintenance_replacement_startup_reconcile_app(nonce) < 0 &&
          access(CUSTODY "/replacement.v1", F_OK) == 0,
        "existing startup holds prior-boot replacement before explicit recovery (%s)\n", phase);
    guard_test_drop_runtime();
    int result = invoke(0, 0, reply);
    CHECK(result == 0 && !strcmp(reply, "STALE_REPLACEMENT RECONCILED\n"),
        "fixed CLI reconciles prior-boot pre-swap replacement before startup (%s, rc=%d, reply=%s)\n",
        phase, result, reply);
    CHECK(file_equals(HELPER_LIVE, old_bytes, sizeof old_bytes - 1) &&
          file_equals(HELPER_STAGE, new_bytes, sizeof new_bytes - 1) &&
          access(HELPER_PREVIOUS, F_OK) != 0,
        "explicit stale recovery preserves both recorded helper binaries (%s)\n", phase);
    CHECK(access(CUSTODY "/replacement.v1", F_OK) != 0 &&
          guard_maintenance_replacement_startup_reconcile_app(nonce) == 0 && nonce[0] == '\0',
        "production startup accepts exact old helper after explicit stale recovery (%s)\n", phase);
    guard_test_drop_runtime();
}

static void test_same_boot(const char *phase) {
    retained_replacement(phase);
    static const char same_boot[] = "123e4567-e89b-12d3-a456-426614174000";
    write_file_mode(BOOT_FILE, same_boot, sizeof same_boot - 1, 0600);
    char reply[128];
    int result = invoke(0, 0, reply);
    CHECK(result == 0 && !strcmp(reply, "STALE_REPLACEMENT RECONCILED\n") &&
          access(CUSTODY "/replacement.v1", F_OK) != 0 &&
          file_equals(HELPER_LIVE, old_bytes, sizeof old_bytes - 1) &&
          file_equals(HELPER_STAGE, new_bytes, sizeof new_bytes - 1),
        "app recovery CLI reconciles exact same-boot orphan phase=%s rc=%d reply=%s\n",
        phase, result, reply);
}

int main(void) {
    if (geteuid() != 0) {
        fprintf(stderr, "stale-replacement-cli requires root for explicit identity controls\n");
        return 2;
    }
    test_same_boot("REQUESTED");
    test_same_boot("GRANTED");
    test_prior_boot("REQUESTED");
    test_prior_boot("GRANTED");
    reset_case();
    char reply[128];
    int result = invoke(0, 0, reply);
    CHECK(result == 0 && !strcmp(reply, "STALE_REPLACEMENT NONE\n"),
        "fixed CLI reports only exact empty replacement custody as NONE\n");
    retained_replacement("GRANTED");
    result = invoke(1, 0, reply);
    CHECK(result != 0 && !strcmp(reply, "STALE_REPLACEMENT HOLD\n") &&
          access(CUSTODY "/replacement.v1", F_OK) == 0,
        "fixed CLI rejects every argument without changing replacement custody\n");
    result = invoke(0, 1, reply);
    CHECK(result != 0 && !strcmp(reply, "STALE_REPLACEMENT HOLD\n") &&
          access(CUSTODY "/replacement.v1", F_OK) == 0,
        "fixed CLI rejects nonroot identity without changing replacement custody\n");
    reset_case();
    guard_test_drop_runtime();
    puts(failures ? "stale replacement CLI FAILED" : "stale replacement CLI PASS");
    return failures ? 1 : 0;
}
