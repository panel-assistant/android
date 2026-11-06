#define _GNU_SOURCE

#include <stdio.h>
#include <string.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <sys/wait.h>

/* The app's data directory is present or absent, and owned by the app's uid. A path-blind fake could
 * not tell the app's directory from any other path the daemon stats. */
#define APP_DATA_DIR "/data/data/io.panelassistant.android"

static int app_present;
static uid_t app_uid;

static int peer_auth_test_stat(const char *path, struct stat *st) {
    if (strcmp(path, APP_DATA_DIR) != 0 || !app_present) return -1;
    memset(st, 0, sizeof *st);
    st->st_uid = app_uid;
    return 0;
}

/* White-box the accept-loop policy while replacing only stat(2). Keeping the production function in
 * this translation unit ensures the regression test cannot drift into a second implementation. */
#define SOCK_NAME "hapaneld-helper-peer-auth-test"
#define stat(path, st) peer_auth_test_stat((path), (st))
#define main hapaneld_helper_main_for_peer_auth_test
#include "../src/main.c"
#undef main
#undef stat

/* main.c's unused daemon entry point references these modules; the auth test never calls them. */
void input_init(void) {}
void gpio_init(void) {}
void screen_init(void) {}
void led_init(void) {}
int input_watch(const char *path, int grab) { (void)path; (void)grab; return 0; }
/* An admitted connection answers one marker byte, so a test peer can tell admission from rejection. */
void server_serve(int fd, enum helper_caller caller) { (void)caller; (void)!write(fd, "S", 1); }
void input_unsubscribe(int fd) { (void)fd; }
void gpio_unsubscribe(int fd) { (void)fd; }
void conn_release(void) {}
int conn_admit(void) { return 1; }
int guard_maintenance_init(void) { return 0; }
int guard_maintenance_replacement_safe(void) { return 0; }
int guard_maintenance_retire_uninstalled(void) { return -1; }
int guard_maintenance_replacement_parent_grant(char nonce[65]) { (void)nonce; return -1; }
int guard_maintenance_replacement_export_lease(void) { return -1; }
int guard_maintenance_replacement_parent_abort(const char nonce[65]) { (void)nonce; return -1; }
int guard_maintenance_replacement_stage_app(const char nonce[65]) { (void)nonce; return -1; }
int guard_maintenance_replacement_supervisor_adopt_app(const char nonce[65]) {
    (void)nonce; return -1;
}
int guard_maintenance_replacement_worker_commit_app(const char nonce[65]) {
    (void)nonce; return -1;
}
int guard_maintenance_reconcile_stale_replacement(void) { return -1; }
int guard_maintenance_replacement_startup_reconcile_app(char nonce[65]) {
    (void)nonce; return 0;
}
void guard_maintenance_set_supervised(int supervised) { (void)supervised; }
void guard_maintenance_set_supervisor_owner(void) {}
int guard_maintenance_supervisor_tick(void) { return 0; }
int guard_maintenance_supervisor_work_deadline(enum guard_supervisor_work work,
        uint64_t *deadline_ms) { (void)work; (void)deadline_ms; return -1; }
int guard_maintenance_supervisor_start_work(enum guard_supervisor_work work, pid_t *pid) {
    (void)work; (void)pid; return -1;
}
int guard_maintenance_supervisor_complete(enum guard_supervisor_work work,
        enum guard_execution_result result, int status) {
    (void)work; (void)result; (void)status; return -1;
}
int sysexec_poll_argv(pid_t pid, int *status) { (void)pid; (void)status; return -1; }
int sysexec_terminate_argv(pid_t pid, int *status) { (void)pid; (void)status; return -1; }

#define CHECK(condition, message) do { \
    if (!(condition)) { fprintf(stderr, "FAIL: %s\n", message); return 1; } \
} while (0)

/* Connect to the test daemon and return the bytes it sends before closing, or -1 if unreachable. */
static int connect_and_read(void) {
    struct sockaddr_un addr;
    memset(&addr, 0, sizeof addr);
    addr.sun_family = AF_UNIX;
    memcpy(addr.sun_path + 1, SOCK_NAME, sizeof SOCK_NAME - 1);
    socklen_t alen = offsetof(struct sockaddr_un, sun_path) + 1 + (sizeof SOCK_NAME - 1);
    int fd = socket(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0);
    if (fd < 0) return -1;
    if (connect(fd, (struct sockaddr *)&addr, alen) != 0) { close(fd); return -1; }
    struct timeval timeout = { .tv_sec = 3, .tv_usec = 0 };
    (void)setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, &timeout, sizeof timeout);
    char buffer[8];
    int total = 0;
    for (;;) {
        ssize_t n = read(fd, buffer, sizeof buffer);
        if (n <= 0) break;
        total += (int)n;
    }
    close(fd);
    return total;
}

/* Runs the production accept loop and reports what a root peer and an unrelated uid each receive.
 * Returns 0 when root is served and the unrelated uid is closed with no bytes. */
static int accept_loop_rejects_unrelated_uid(void) {
    if (getuid() != 0) return 1;
    pid_t daemon = fork();
    if (daemon == 0) {
        char *argv[] = { "hapaneld-helper", NULL };
        _exit(run_daemon(1, argv, NULL, 0));
    }
    if (daemon < 0) return 1;
    int root_bytes = -1;
    for (int attempt = 0; attempt < 100 && root_bytes < 0; attempt++) {
        root_bytes = connect_and_read();
        if (root_bytes < 0) usleep(20000);
    }
    pid_t peer = fork();
    if (peer == 0) {
        if (setresgid(65534, 65534, 65534) != 0 || setresuid(65534, 65534, 65534) != 0) _exit(2);
        _exit(connect_and_read() == 0 ? 0 : 1);
    }
    int peer_status = -1;
    if (peer > 0) waitpid(peer, &peer_status, 0);
    kill(daemon, SIGKILL);
    waitpid(daemon, NULL, 0);
    return root_bytes == 1 && WIFEXITED(peer_status) && WEXITSTATUS(peer_status) == 0 ? 0 : 1;
}

#define ALLOWED(uid) (resolve_caller(uid) != HELPER_CALLER_NONE)

int main(void) {
    app_present = 1;
    app_uid = 12345;

    CHECK(resolve_caller(0) == HELPER_CALLER_ROOT, "root must remain allowed");
    CHECK(resolve_caller(app_uid) == HELPER_CALLER_SUCCESSOR,
          "the currently resolved app uid must be allowed, as the app");
    CHECK(!ALLOWED(2000), "generic Android shell uid must not inherit root helper verbs");
    CHECK(!ALLOWED(12346), "an unrelated app uid must be rejected");

    /* Prove a successful lookup is not cached across uninstall/data-dir loss. */
    CHECK(ALLOWED(app_uid), "precondition: app uid is allowed while data dir exists");
    app_present = 0;
    CHECK(resolve_caller(0) == HELPER_CALLER_ROOT, "root must remain available while the app is absent");
    CHECK(!ALLOWED(app_uid), "former app uid must fail closed after data dir disappears");

    app_present = 1;
    app_uid = 23456;
    CHECK(!ALLOWED(12345), "old uid must stay rejected after reinstall with a new uid");
    CHECK(resolve_caller(app_uid) == HELPER_CALLER_SUCCESSOR,
          "newly resolved app uid must be allowed after reinstall");
    CHECK(probe_command_allowed("PING"), "root probe mode must allow PING");
    CHECK(probe_command_allowed("COMPANIONCAPS"), "root probe mode must allow capability discovery");
    CHECK(probe_command_allowed("BUILDID"), "root probe mode must allow build identity");
    CHECK(probe_command_allowed("GUARDCAPS"), "root probe mode must allow Guard capability discovery");
    CHECK(probe_command_allowed("GUARDSELF"), "root probe mode must allow live helper byte identity");
    CHECK(probe_command_allowed("GUARDSTATUS"), "root probe mode must allow Guard status observation");
    const char *guard_mutation_verbs[] = {
        "GUARDPREPARE", "GUARDDEFINE", "GUARDSTREAM", "GUARDACTION", "GUARDHEALTH",
        "GUARDREFUSAL", "GUARDEVIDENCE", "GUARDCANCEL", "GUARDRETIRE",
    };
    for (size_t i = 0; i < sizeof guard_mutation_verbs / sizeof guard_mutation_verbs[0]; i++) {
        CHECK(!probe_command_allowed(guard_mutation_verbs[i]),
              "root probe mode must not expose Guard mutation verbs");
    }
    CHECK(!probe_command_allowed("REBOOT"), "root probe mode must not expose mutating verbs");
    CHECK(!probe_command_allowed("KEYEVENT SLEEP"),
          "root probe mode must not expose screen-power key injection");
    CHECK(!probe_command_allowed("LOGCAT"),
          "root probe mode must not bypass socket authentication for LOGCAT");
    CHECK(!probe_command_allowed("PING extra"), "root probe mode must require an exact verb");
    CHECK(!probe_command_allowed("GUARDCAPS extra"),
          "root probe mode must require an exact Guard capability verb");
    CHECK(!probe_command_allowed("GUARDSELF extra"),
          "root probe mode must require an exact live helper identity verb");
    CHECK(!probe_command_allowed("GUARDSTATUS extra"),
          "root probe mode must require an exact Guard status verb");
    CHECK(!probe_command_allowed("GRANT io.panelassistant.android ACCESSIBILITY"),
          "root probe mode must not expose the grant verb");

    app_present = 1;
    app_uid = 12345;
    CHECK(accept_loop_rejects_unrelated_uid() == 0,
          "the accept loop closes an unrelated uid before any reply while serving root");

    puts("peer auth tests passed");
    return 0;
}
