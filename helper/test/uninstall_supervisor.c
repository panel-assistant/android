#define _GNU_SOURCE
#include <ftw.h>
#include <stdio.h>
#include <stdlib.h>
#include <sys/wait.h>
#include <unistd.h>
#include "sha256.h"

/* Real production supervisor, socket worker, Guard and Companion compartments. Only fixed paths
 * and one unlink crash cut are substituted; assertions observe process/socket/filesystem behavior. */
#define main helper_main
#include "../src/main.c"
#undef main
#define LIVE "/tmp/.hapaneld-helper-live-test"
#define STAGE "/tmp/.hapaneld-companion-stage-test"
#define LEASE "/tmp/.hapaneld-helper-transaction-lock-test"
#define BOOT "/tmp/.hapaneld-guard-db-test.boot-id"
#define APP_ROOT "/tmp/.hapaneld-guard-app-test"
#define DB_DIR APP_ROOT "/data/user/0/io.panelassistant.android/databases"
#define DB DB_DIR "/ha-paneld.db"
#define INSTALLED "/tmp/.hapaneld-guard-installed"
#define SESSION "1111111111111111111111111111111111111111111111111111111111111111"
#define SIGNER "2222222222222222222222222222222222222222222222222222222222222222"
static char fixture[256];
static pid_t supervisor = -1;
static int failures;
static int boot_owned;
int __real_unlinkat(int dir, const char *name, int flags);
int __real_lstat(const char *path, struct stat *st);
int __wrap_lstat(const char *path, struct stat *st) {
    const char *marker = getenv("HAPANELD_RETIRE_STAT_ERROR");
    if (marker && access(marker, F_OK) == 0 && !strcmp(path, APP_DATA)) {
        errno = EIO;
        return -1;
    }
    return __real_lstat(path, st);
}
int __wrap_unlinkat(int dir, const char *name, int flags) {
    if (getenv("HAPANELD_RETIRE_CRASH") && !strcmp(name, ".hapaneld-helper-live-test")) _exit(77);
    if (getenv("HAPANELD_RETIRE_REAPPEAR") && !strcmp(name, ".owner.lock")) {
        unsetenv("HAPANELD_RETIRE_REAPPEAR");
        (void)mkdir(APP_DATA, 0700);
    }
    const char *cut = getenv("HAPANELD_RETIRE_FAIL_ONCE");
    if (cut && !strcmp(name, "companion-session.payload")) {
        int fd = open(cut, O_WRONLY | O_CREAT | O_EXCL, 0600);
        if (fd >= 0) { close(fd); errno = EIO; return -1; }
    }
    return __real_unlinkat(dir, name, flags);
}
#define CHECK(value, message) do { if (!(value)) { fprintf(stderr, "FAIL: %s\n", message); failures++; } } while (0)
static int remove_entry(const char *path, const struct stat *st, int type, struct FTW *walk) {
    (void)st; (void)type; (void)walk; return remove(path);
}
static void cleanup(void) {
    if (supervisor > 1) { kill(supervisor, SIGKILL); waitpid(supervisor, NULL, 0); supervisor = -1; }
    // A deliberately broken supervisor can fail to reap its worker. Own and reap that orphan too.
    for (int i = 0; i < 20; i++) {
        while (waitpid(-1, NULL, WNOHANG) > 0) { }
        usleep(1000);
    }
    unlink(LIVE); unlink(LIVE ".new"); unlink(LIVE ".old");
    nftw(STAGE, remove_entry, 16, FTW_DEPTH | FTW_PHYS);
    nftw(LEASE, remove_entry, 16, FTW_DEPTH | FTW_PHYS);
    nftw(APP_DATA, remove_entry, 16, FTW_DEPTH | FTW_PHYS);
    nftw(APP_USER_DATA, remove_entry, 16, FTW_DEPTH | FTW_PHYS);
    nftw(APP_ROOT, remove_entry, 16, FTW_DEPTH | FTW_PHYS);
    nftw(INSTALLED, remove_entry, 16, FTW_DEPTH | FTW_PHYS);
    if (*fixture) nftw(fixture, remove_entry, 16, FTW_DEPTH | FTW_PHYS);
}
static void finish(void) { cleanup(); if (boot_owned) unlink(BOOT); }
static void write_file(const char *path, const char *text) {
    int fd = open(path, O_WRONLY | O_CREAT | O_TRUNC, 0600);
    if (fd < 0 || write(fd, text, strlen(text)) != (ssize_t)strlen(text)) abort();
    close(fd);
}
static int exists(const char *path) { struct stat st; return lstat(path, &st) == 0; }
static void prepare(void) {
    cleanup();
    snprintf(fixture, sizeof fixture, "/tmp/helper-retire-XXXXXX");
    if (!mkdtemp(fixture) || setenv("HAPANELD_GUARD_TEST_PARENT", fixture, 1)) abort();
    int input = open("/proc/self/exe", O_RDONLY);
    int output = open(LIVE, O_CREAT | O_EXCL | O_WRONLY, 0700);
    if (input < 0 || output < 0) abort();
    char buffer[8192]; ssize_t n;
    while ((n = read(input, buffer, sizeof buffer)) > 0)
        if (write(output, buffer, (size_t)n) != n) abort();
    close(input); close(output);
    if (mkdir(APP_DATA, 0700) || mkdir(APP_USER_DATA, 0700)) abort();
}
static void start(void) {
    if (supervisor > 1) { kill(supervisor, SIGKILL); waitpid(supervisor, NULL, 0); supervisor = -1; }
    supervisor = fork();
    if (supervisor == 0) { execl(LIVE, LIVE, "--supervise", NULL); _exit(99); }
    if (supervisor < 0) abort();
}
static int connect_worker(void) {
    int fd = socket(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0);
    struct sockaddr_un addr = { .sun_family = AF_UNIX };
    memcpy(addr.sun_path + 1, SOCK_NAME, sizeof SOCK_NAME - 1);
    if (fd < 0 || connect(fd, (struct sockaddr *)&addr,
            offsetof(struct sockaddr_un, sun_path) + sizeof SOCK_NAME) != 0) {
        if (fd >= 0) close(fd);
        return -1;
    }
    struct timeval timeout = { .tv_sec = 1 };
    setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, &timeout, sizeof timeout);
    return fd;
}
static int ping(void) {
    int fd = connect_worker();
    if (fd < 0) return 0;
    char response[32] = {0};
    if (write(fd, "PING\n", 5) != 5) { close(fd); return 0; }
    int n = (int)read(fd, response, sizeof response - 1);
    close(fd);
    return n > 0 && !strcmp(response, "OK\n");
}
static int held_status_available(void) {
    int fd = connect_worker();
    if (fd < 0) return 0;
    char response[32] = {0};
    const char *command = "COMPANIONSTATUS\n";
    int ok = write(fd, command, strlen(command)) == (ssize_t)strlen(command) &&
        read(fd, response, sizeof response - 1) > 0 && !strcmp(response, "BUSY\n");
    usleep(200000);
    memset(response, 0, sizeof response);
    ok = ok && send(fd, "PING\n", 5, MSG_NOSIGNAL) == 5 &&
        read(fd, response, sizeof response - 1) > 0 && !strcmp(response, "OK\n");
    close(fd);
    return ok;
}
static int ready(void) {
    for (int i = 0; i < 100; i++) { if (ping()) return 1; usleep(20000); }
    return 0;
}
static int wait_exit(int expected) {
    for (int i = 0; i < 150; i++) {
        int status; pid_t done = waitpid(supervisor, &status, WNOHANG);
        if (done == supervisor) { supervisor = -1; return WIFEXITED(status) && WEXITSTATUS(status) == expected; }
        usleep(20000);
    }
    return 0;
}
static void absent(void) { rmdir(APP_DATA); rmdir(APP_USER_DATA); }
/* Persist the same canonical FINALIZED records retained by the Guard lifecycle. Retirement
 * authenticates these bytes by digest; APK signing was established before this terminal state.
 * These payloads exercise that custody contract without claiming APK signature verification. */
static const char *const retained[] = {
    "manifest.v1", "journal.v1", "a.apk", "b.apk", "settings.v2", "baseline.db",
    "premigrate.db", "b-primary.db", "foreign",
};
enum { RETAINED_COUNT = sizeof retained / sizeof retained[0] };
static char state[384];
static void digest(const void *data, size_t size, char hex[65]) {
    hapaneld_sha256 hash; unsigned char bytes[32];
    hapaneld_sha256_init(&hash); hapaneld_sha256_update(&hash, data, size);
    hapaneld_sha256_final(&hash, bytes); hapaneld_sha256_hex(bytes, hex);
}
static void retained_path(const char *name, char path[512]) {
    snprintf(path, 512, "%s/%s", state, name);
}
static void retain(const char *name, const char *bytes, mode_t mode) {
    char path[512]; retained_path(name, path); write_file(path, bytes);
    if (chmod(path, mode) != 0) abort();
}
static void record(const char *name, const char *body, char sha[65]) {
    char checksum[65], bytes[4096]; digest(body, strlen(body), checksum);
    int size = snprintf(bytes, sizeof bytes, "%sSHA256 %s\n", body, checksum);
    if (size < 0 || (size_t)size >= sizeof bytes) abort();
    digest(bytes, (size_t)size, sha); retain(name, bytes, 0600);
}
static int retained_digest(const char *name, char sha[65]) {
    char path[512]; retained_path(name, path);
    int fd = open(path, O_RDONLY | O_NOFOLLOW | O_CLOEXEC); struct stat st;
    int ok = fd >= 0 && fstat(fd, &st) == 0 && S_ISREG(st.st_mode) &&
        hapaneld_sha256_fd(fd, (uint64_t)st.st_size, sha) == 0;
    if (fd >= 0) close(fd);
    return ok;
}
static void finalized(int rollback) {
    static const char a[] = "exact-a-apk", b[] = "exact-b-apk-next";
    static const char baseline[] = "sealed baseline database";
    static const char primary[] = "sealed candidate database";
    static const char settings[] = "S2\n616c706861|737472696e67|64656661756c74\n";
    char a_sha[65], b_sha[65], db_sha[65], primary_sha[65], settings_sha[65];
    digest(a, strlen(a), a_sha); digest(b, strlen(b), b_sha);
    digest(baseline, strlen(baseline), db_sha); digest(primary, strlen(primary), primary_sha);
    digest(settings, strlen(settings), settings_sha);
    snprintf(state, sizeof state, "%s/.hapaneld-guard-db-test", fixture);
    if (mkdir(state, 0700) != 0) abort();
    const char *dirs[] = { APP_ROOT, APP_ROOT "/data", APP_ROOT "/data/user",
        APP_ROOT "/data/user/0", APP_ROOT "/data/user/0/io.panelassistant.android", DB_DIR, INSTALLED };
    for (size_t i = 0; i < sizeof dirs / sizeof dirs[0]; i++)
        if (mkdir(dirs[i], 0700) != 0) abort();
    write_file(DB, baseline); write_file(INSTALLED "/base.apk", a);
    struct stat dir, db;
    if (stat(DB_DIR, &dir) != 0 || stat(DB, &db) != 0) abort();
    retain("a.apk", a, 0400); retain("b.apk", b, 0400);
    retain("baseline.db", baseline, 0400); retain("settings.v2", settings, 0400);
    if (!rollback) { retain("premigrate.db", baseline, 0400); retain("b-primary.db", primary, 0400); }
    /* Use the existing host fixture boot identity; never replace another test's boot file. */
    int created = open(BOOT, O_WRONLY | O_CREAT | O_EXCL | O_NOFOLLOW | O_CLOEXEC, 0600);
    if (created >= 0) {
        static const char uuid[] = "123e4567-e89b-12d3-a456-426614174000\n";
        if (write(created, uuid, sizeof uuid - 1) != sizeof uuid - 1) abort();
        close(created); boot_owned = 1;
    } else if (errno != EEXIST) abort();
    int boot_fd = open(BOOT, O_RDONLY | O_NOFOLLOW | O_CLOEXEC);
    char raw[128] = {0}, boot[65];
    ssize_t count = boot_fd >= 0 ? read(boot_fd, raw, sizeof raw) : -1;
    if (boot_fd >= 0) close(boot_fd);
    if (count <= 0 || count >= (ssize_t)sizeof raw) abort();
    while (count > 0 && (raw[count - 1] == '\n' || raw[count - 1] == '\r')) count--;
    hapaneld_sha256 hash; unsigned char bytes[32];
    static const char domain[] = "hapaneld-guard-boot-v1";
    hapaneld_sha256_init(&hash); hapaneld_sha256_update(&hash, domain, sizeof domain);
    hapaneld_sha256_update(&hash, raw, (size_t)count); hapaneld_sha256_final(&hash, bytes);
    hapaneld_sha256_hex(bytes, boot);
    char body[3072], manifest_sha[65], journal_sha[65];
    snprintf(body, sizeof body,
        "V1 %s %s %s 7 %zu %s 14 2 %s %s"
        " 1 1 %zu %s 568 11 14 14 1 1 %zu %s 569 11 15 15"
        " 1 %llu %llu %llu %llu %llu %llu %u u:object_r:app_data_file:s0"
        " 1000000 520000 2 %zu %s 1 io.panelassistant.android\n",
        SESSION, boot, SIGNER, strlen(baseline), db_sha, db_sha, settings_sha,
        strlen(a), a_sha, strlen(b), b_sha,
        (unsigned long long)dir.st_dev, (unsigned long long)dir.st_ino,
        (unsigned long long)db.st_dev, (unsigned long long)db.st_ino,
        (unsigned long long)db.st_uid, (unsigned long long)db.st_gid,
        (unsigned)(db.st_mode & 07777), strlen(settings), settings_sha);
    record("manifest.v1", body, manifest_sha);
    snprintf(body, sizeof body,
        "J1 %s 7 %s %d FINALIZED A %s 568 14 0 NONE %s 1 %d %d 0 0 0 %zu %s %zu %s NONE\n",
        SESSION, manifest_sha, rollback ? 15 : 18, a_sha,
        rollback ? "ROLLED_BACK_HEALTH_FAILED" : "CANARY_PASSED", rollback, rollback ? 1000000 : 0,
        rollback ? 0 : strlen(baseline), rollback ? "NONE" : db_sha,
        rollback ? 0 : strlen(primary), rollback ? "NONE" : primary_sha);
    record("journal.v1", body, journal_sha);
}
static int terminal_status(const char *outcome) {
    int fd = connect_worker(); char response[1024] = {0};
    if (fd < 0) return 0;
    int ok = write(fd, "GUARDSTATUS\n", 12) == 12 && read(fd, response, sizeof response - 1) > 0 &&
        strstr(response, " FINALIZED ") && strstr(response, outcome);
    close(fd); return ok;
}
static void uninstall_finalized(void) {
    absent();
    nftw(APP_ROOT, remove_entry, 16, FTW_DEPTH | FTW_PHYS);
    nftw(INSTALLED, remove_entry, 16, FTW_DEPTH | FTW_PHYS);
    CHECK(!exists(APP_DATA) && !exists(APP_USER_DATA) && !exists(DB) &&
        !exists(INSTALLED "/base.apk"), "finalized removal has no app data, installed APK or live database");
}
static void test_finalized_retirement(void) {
    for (int rollback = 0; rollback < 2; rollback++) {
        prepare(); finalized(rollback); start();
        CHECK(ready() && terminal_status(rollback ? "ROLLED_BACK_HEALTH_FAILED" : "CANARY_PASSED"),
            "supervisor loads canonical finalized custody before uninstall");
        uninstall_finalized();
        CHECK(wait_exit(0) && !exists(LIVE) && !exists(state) && !exists(STAGE) && !exists(LEASE),
            "finalized success and rollback uninstall retire authenticated custody without live app evidence");
    }
}
static void test_finalized_holds(void) {
    for (int damage = 0; damage < 5; damage++) {
        prepare(); finalized(0); start();
        CHECK(ready() && terminal_status("CANARY_PASSED"), "finalized hold fixture starts from admitted terminal custody");
        char path[512];
        const char *damaged = damage == 0 ? "a.apk" : damage == 1 ? "journal.v1" :
            damage == 2 ? "foreign" : damage == 3 ? "a.apk" : "premigrate.db";
        retained_path(damaged, path);
        if (damage == 3) { if (unlink(path) != 0) abort(); }
        else write_file(path, "corrupted retained bytes");
        char before[RETAINED_COUNT][65]; int present[RETAINED_COUNT];
        for (size_t i = 0; i < RETAINED_COUNT; i++) present[i] = retained_digest(retained[i], before[i]);
        uninstall_finalized(); usleep(1200000);
        CHECK(kill(supervisor, 0) == 0 && exists(LIVE) && ready(),
            "damaged finalized custody retains the supervisor and live recovery socket");
        int unchanged = exists(state) && (damage != 2 || exists(path));
        for (size_t i = 0; i < RETAINED_COUNT; i++) {
            char after[65]; int found = retained_digest(retained[i], after);
            unchanged = unchanged && found == present[i] && (!found || !strcmp(before[i], after));
        }
        CHECK(unchanged, "finalized uninstall preserves every damaged or foreign custody record");
    }
}
static void test_retirement(void) {
    prepare(); start(); CHECK(ready(), "supervisor starts its real socket worker");
    char children[128]; long worker = -1;
    snprintf(children, sizeof children, "/proc/%ld/task/%ld/children", (long)supervisor, (long)supervisor);
    FILE *fp = fopen(children, "r");
    if (fp) { if (fscanf(fp, "%ld", &worker) != 1) worker = -1; fclose(fp); }
    CHECK(worker > 1, "supervisor owns a live worker before removal");
    mkdir(STAGE, 0700); write_file(STAGE "/companion-db.payload", "private backup");
    write_file(LIVE ".new", "orphan stage");
    rmdir(APP_DATA); usleep(200000);
    CHECK(ping() && exists(LIVE), "either app data address keeps the supervisor serving");
    rmdir(APP_USER_DATA);
    CHECK(wait_exit(0), "missing app data retires the production supervisor");
    CHECK(worker > 1 && kill((pid_t)worker, 0) == -1 && errno == ESRCH,
        "uninstall stops and reaps the socket worker");
    char state[320]; snprintf(state, sizeof state, "%s/.hapaneld-guard-db-test", fixture);
    CHECK(!exists(LIVE) && !exists(LIVE ".new") && !exists(state) && !exists(STAGE) && !exists(LEASE),
        "uninstall deletes its binary, staging and both owned state compartments");
}
static void test_holds(void) {
    for (size_t i = 0; i < 6; i++) {
        prepare();
        char held[384];
        if (i == 5) {
            snprintf(held, sizeof held, "%s/io-error", fixture); write_file(held, "error");
            setenv("HAPANELD_RETIRE_STAT_ERROR", held, 1);
        }
        start(); CHECK(ready(), "hold fixture starts the production supervisor");
        if (i == 0) snprintf(held, sizeof held, "%s/.hapaneld-guard-db-test/inflight", fixture);
        if (i == 1) { mkdir(STAGE, 0700); snprintf(held, sizeof held, STAGE "/.companion-restore.prepared"); }
        if (i == 2) {
            mkdir(LEASE, 0700); snprintf(held, sizeof held, LEASE "/pid");
            char pid[32]; snprintf(pid, sizeof pid, "%ld\n", (long)getpid()); write_file(held, pid);
        } else if (i < 2) write_file(held, "pending custody");
        char saved_owner[384];
        if (i == 4) {
            snprintf(held, sizeof held, "%s/.hapaneld-guard-db-test/.owner.lock", fixture);
            snprintf(saved_owner, sizeof saved_owner, "%s/saved-owner", fixture);
            if (rename(held, saved_owner) != 0) abort();
            write_file(held, "another lease inode");
        }
        absent(); if (i == 3) write_file(APP_DATA, "address still exists");
        usleep(220000);
        CHECK(kill(supervisor, 0) == 0 && exists(LIVE),
            "in-progress custody, lease or unproven absence retains the helper");
        if (i == 1) CHECK(held_status_available(),
            "a held retirement keeps the socket available for recovery status");
        if (i < 3) { CHECK(exists(held), "retirement preserves the pending transaction record"); unlink(held); if (i == 2) rmdir(LEASE); }
        else if (i == 3) unlink(APP_DATA);
        else if (i == 4) {
            if (rename(saved_owner, held) != 0) {
                CHECK(0, "held owner state survives until the hold clears");
                return;
            }
        }
        else { unlink(held); unsetenv("HAPANELD_RETIRE_STAT_ERROR"); }
        CHECK(wait_exit(0), "supervisor retries retirement after the hold clears");
    }
}
static void test_interruption(void) {
    prepare(); setenv("HAPANELD_RETIRE_CRASH", "1", 1);
    start(); CHECK(ready(), "interruption fixture starts its worker"); absent();
    CHECK(wait_exit(77) && exists(LIVE), "interruption before final unlink leaves a retryable binary");
    unsetenv("HAPANELD_RETIRE_CRASH"); start();
    CHECK(wait_exit(0) && !exists(LIVE), "a restarted supervisor completes interrupted retirement");

    prepare();
    char cut[384]; snprintf(cut, sizeof cut, "%s/unlink-cut", fixture);
    setenv("HAPANELD_RETIRE_FAIL_ONCE", cut, 1);
    start(); CHECK(ready(), "partial unlink fixture starts its worker");
    mkdir(STAGE, 0700);
    write_file(STAGE "/companion-db.payload", "payload one");
    write_file(STAGE "/companion-session.payload", "payload two");
    absent();
    CHECK(wait_exit(0) && exists(cut) && !exists(STAGE),
        "supervisor retries a partial Companion payload cleanup");
    unsetenv("HAPANELD_RETIRE_FAIL_ONCE");
}
static void test_reappearance_and_replacement(void) {
    prepare(); setenv("HAPANELD_RETIRE_REAPPEAR", "1", 1);
    start(); CHECK(ready(), "reappearance fixture starts its worker"); absent();
    usleep(250000);
    CHECK(exists(APP_DATA) && exists(LIVE) && ready(),
        "data reappearing during cleanup retains a serving helper");
    unsetenv("HAPANELD_RETIRE_REAPPEAR");
    rmdir(APP_DATA);
    CHECK(wait_exit(0), "supervisor retries after data reappearance clears");

    prepare(); start(); CHECK(ready(), "replacement fixture starts its worker");
    if (rename(LIVE, LIVE ".old") != 0) abort();
    write_file(LIVE, "a new helper inode"); absent(); usleep(220000);
    CHECK(exists(LIVE) && kill(supervisor, 0) == 0,
        "an old supervisor never unlinks a replacement helper inode");
    if (rename(LIVE ".old", LIVE) != 0) abort();
    CHECK(wait_exit(0), "supervisor retries after the binary identity hold clears");
}
int main(int argc, char **argv) {
    if (argc > 1) return helper_main(argc, argv);
    if (prctl(PR_SET_CHILD_SUBREAPER, 1) != 0) abort();
    atexit(finish); test_retirement(); test_holds(); test_interruption();
    test_reappearance_and_replacement(); test_finalized_retirement(); test_finalized_holds();
    return failures ? 1 : 0;
}
