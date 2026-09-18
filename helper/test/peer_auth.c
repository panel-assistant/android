#define _GNU_SOURCE

#include <stdio.h>
#include <string.h>
#include <sys/stat.h>
#include <sys/types.h>

/* Each package's data directory is present or absent independently, and owned by its own uid — the
 * two apps carry no sharedUserId, so a panel mid-migration really does hold two distinct uids and two
 * distinct directories. A path-blind fake could not tell "the successor is installed" from "the
 * legacy app is", which is the entire distinction under test. */
#define LEGACY_DATA_DIR "/data/data/io.github.maxlyth.hapaneld"
#define SUCCESSOR_DATA_DIR "/data/data/io.panelassistant.android"

static int legacy_present;
static uid_t legacy_uid;
static int successor_present;
static uid_t successor_uid;

static int peer_auth_test_stat(const char *path, struct stat *st) {
    int present = 0;
    uid_t owner = 0;
    if (strcmp(path, LEGACY_DATA_DIR) == 0) { present = legacy_present; owner = legacy_uid; }
    else if (strcmp(path, SUCCESSOR_DATA_DIR) == 0) { present = successor_present; owner = successor_uid; }
    if (!present) return -1;
    memset(st, 0, sizeof *st);
    st->st_uid = owner;
    return 0;
}

/* White-box the accept-loop policy while replacing only stat(2). Keeping the production function in
 * this translation unit ensures the regression test cannot drift into a second implementation. */
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
void server_serve(int fd, enum helper_caller caller) { (void)fd; (void)caller; }
void input_unsubscribe(int fd) { (void)fd; }
void gpio_unsubscribe(int fd) { (void)fd; }
void conn_release(void) {}
int conn_admit(void) { return 0; }
int guard_maintenance_init(void) { return 0; }
int guard_maintenance_replacement_safe(void) { return 0; }
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

#define ALLOWED(uid) (resolve_caller(uid) != HELPER_CALLER_NONE)

int main(void) {
    /* A single-package panel: only the legacy app is installed. This is every panel in the field
     * before the migration, and its behaviour must be exactly what it was. */
    legacy_present = 1;
    legacy_uid = 12345;
    successor_present = 0;
    successor_uid = 0;

    CHECK(resolve_caller(0) == HELPER_CALLER_ROOT, "root must remain allowed");
    CHECK(resolve_caller(legacy_uid) == HELPER_CALLER_LEGACY,
          "the currently resolved legacy uid must be allowed, as the legacy package");
    CHECK(!ALLOWED(2000), "generic Android shell uid must not inherit root helper verbs");
    CHECK(!ALLOWED(12346), "an unrelated app uid must be rejected");

    /* Prove a successful lookup is not cached across uninstall/data-dir loss. */
    CHECK(ALLOWED(legacy_uid), "precondition: app uid is allowed while data dir exists");
    legacy_present = 0;
    CHECK(resolve_caller(0) == HELPER_CALLER_ROOT, "root must remain available while the app is absent");
    CHECK(!ALLOWED(legacy_uid), "former app uid must fail closed after data dir disappears");

    legacy_present = 1;
    legacy_uid = 23456;
    CHECK(!ALLOWED(12345), "old uid must stay rejected after reinstall with a new uid");
    CHECK(resolve_caller(legacy_uid) == HELPER_CALLER_LEGACY,
          "newly resolved app uid must be allowed after reinstall");

    /* Only the successor is installed — the end state of the migration, after the legacy package is
     * uninstalled. The successor is the app the daemon serves; nothing about it is a special case. */
    legacy_present = 0;
    successor_present = 1;
    successor_uid = 10188;
    CHECK(resolve_caller(successor_uid) == HELPER_CALLER_SUCCESSOR,
          "the successor package's uid must be allowed, as the successor");
    CHECK(!ALLOWED(23456), "the departed legacy uid must be refused once its data dir is gone");
    CHECK(!ALLOWED(2000), "shell uid stays refused on a successor-only panel");

    /* Both installed — the transition window. Each uid resolves to its OWN package, and a third uid
     * is refused exactly as on a single-package panel. This is the whole point: the successor gets
     * root through this socket without inheriting the legacy app's identity, and without the socket
     * widening to anyone else. */
    legacy_present = 1;
    legacy_uid = 10141;
    successor_present = 1;
    successor_uid = 10188;
    CHECK(resolve_caller(legacy_uid) == HELPER_CALLER_LEGACY,
          "with both installed, the legacy uid must resolve to the legacy package");
    CHECK(resolve_caller(successor_uid) == HELPER_CALLER_SUCCESSOR,
          "with both installed, the successor uid must resolve to the successor package");
    CHECK(resolve_caller(0) == HELPER_CALLER_ROOT, "root stays root with both installed");
    CHECK(!ALLOWED(10200), "a third app uid must be refused while both packages are installed");
    CHECK(!ALLOWED(2000), "shell uid must stay refused while both packages are installed");

    /* A uid owning neither directory is refused even when it equals a uid that once owned one. */
    successor_uid = 10141;
    CHECK(resolve_caller(10141) == HELPER_CALLER_LEGACY,
          "a uid owning both directories resolves to the legacy package, never to two identities");
    CHECK(probe_command_allowed("PING"), "root probe mode must allow PING");
    CHECK(probe_command_allowed("COMPANIONCAPS"), "root probe mode must allow capability discovery");
    CHECK(probe_command_allowed("BUILDID"), "root probe mode must allow build identity");
    CHECK(probe_command_allowed("HELPERSTATUS"),
          "root probe mode must allow the accepted-package status read");
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
    CHECK(!probe_command_allowed("PING extra"), "root probe mode must require an exact verb");
    CHECK(!probe_command_allowed("GUARDCAPS extra"),
          "root probe mode must require an exact Guard capability verb");
    CHECK(!probe_command_allowed("GUARDSELF extra"),
          "root probe mode must require an exact live helper identity verb");
    CHECK(!probe_command_allowed("GUARDSTATUS extra"),
          "root probe mode must require an exact Guard status verb");
    CHECK(!probe_command_allowed("HELPERSTATUS extra"),
          "root probe mode must require an exact accepted-package status verb");
    CHECK(!probe_command_allowed("UNINSTALL io.github.maxlyth.hapaneld"),
          "root probe mode must not expose package removal");
    CHECK(!probe_command_allowed("GRANT io.panelassistant.android ACCESSIBILITY"),
          "root probe mode must not expose the grant verb");

    puts("peer auth tests passed");
    return 0;
}
