#define _GNU_SOURCE
#include <ftw.h>
#include <stdio.h>
#include <stdlib.h>
#include <sys/wait.h>
#include <unistd.h>

/* Real production supervisor, socket worker, Guard and Companion compartments. Only fixed paths
 * and one unlink crash cut are substituted; assertions observe process/socket/filesystem behavior. */
#define main helper_main
#include "../src/main.c"
#undef main
#define LIVE "/tmp/.hapaneld-helper-live-test"
#define STAGE "/tmp/.hapaneld-companion-stage-test"
#define LEASE "/tmp/.hapaneld-helper-transaction-lock-test"
static char fixture[256];
static pid_t supervisor = -1;
static int failures;
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
    if (*fixture) nftw(fixture, remove_entry, 16, FTW_DEPTH | FTW_PHYS);
}
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
    atexit(cleanup); test_retirement(); test_holds(); test_interruption();
    test_reappearance_and_replacement(); return failures ? 1 : 0;
}
