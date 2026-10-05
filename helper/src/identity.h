// The app identities this daemon serves — the ONE place the panel-side package ids are written.
//
// The Android applicationId changes in 0.9.8 (`io.github.maxlyth.hapaneld` → `io.panelassistant.android`).
// A new applicationId is a new app, so the migration installs the successor BESIDE the legacy package
// and the two run side by side for a transition window. There is no `sharedUserId`, so they never
// share a uid or a data directory, and the successor cannot reach root by inheriting the legacy app's
// authorisation: on the SuperSU panels root is granted per uid behind a prompt nobody can answer
// remotely, so this socket is the successor's only root channel.
//
// Every consumer of an app identity reads this table rather than spelling an id out again: peer
// authentication (main.c), the INSTALLGC path validator and the never-tear-down backstop (util.c),
// Guard's package/database selection (guard_maintenance.c), and the package-restricted verbs
// (sysctl.c). Two copies of an identity rule drift; one table cannot.
//
// NOT here, and deliberately unchanged: the frozen schema and metadata identifier strings that embed
// the old id (`io.github.maxlyth.hapaneld.install.v1`, `…buildfeed.v1`, the DATABASE_COMPATIBILITY
// manifest key, `hapaneld-db:v1:ha-paneld.db`). Shipped verifiers compare those byte for byte; they
// are frozen identifiers, not addresses of a running app.
#ifndef HAPANELD_IDENTITY_H
#define HAPANELD_IDENTITY_H

#include <stddef.h>
#include <sys/types.h>

// Data directories are overridable so the replacement-identity fixture can point the probe at a path
// the test uid owns. Overriding only the legacy pair leaves the successor absent, which is exactly a
// single-package panel — the case whose behaviour must not change.
#ifndef APP_DATA
#define APP_DATA "/data/data/io.github.maxlyth.hapaneld"
#endif
#ifndef APP_USER_DATA
#define APP_USER_DATA "/data/user/0/io.github.maxlyth.hapaneld"
#endif
#ifndef APP_DATA_SUCCESSOR
#define APP_DATA_SUCCESSOR "/data/data/io.panelassistant.android"
#endif
#ifndef APP_USER_DATA_SUCCESSOR
#define APP_USER_DATA_SUCCESSOR "/data/user/0/io.panelassistant.android"
#endif

// The accessibility service's class lives in the Kotlin package whichever applicationId the build
// carries, so each id's component is that id plus this class. The `.input.PanelAccessibilityService`
// shorthand resolves against the id and names this class only for the successor, so the fully
// qualified form is the one spelling correct for both.
#define APP_ACCESSIBILITY_CLASS "io.panelassistant.android.input.PanelAccessibilityService"

// Who is on the other end of a connection. Resolved once per connection from SO_PEERCRED and carried
// in conn_ctx, so a handler never has to ask, and never takes the answer from the request.
enum helper_caller {
    HELPER_CALLER_NONE = 0,   // not authenticated — no verb runs
    HELPER_CALLER_ROOT,       // uid 0: an ADB/root operator, which owns no package data directory
    HELPER_CALLER_LEGACY,     // io.github.maxlyth.hapaneld
    HELPER_CALLER_SUCCESSOR,  // io.panelassistant.android
};

#define HELPER_APP_PACKAGE_COUNT 2

typedef struct {
    enum helper_caller caller;
    const char *package;   // exact Android package id
    const char *data_dir;  // legacy /data/data form — stat'ed to resolve the app's live uid
    const char *user_dir;  // multi-user /data/user/0 form returned by getCacheDir() on API 24+
} helper_app_package;

// Legacy first, successor second. The order is part of the contract in two places: the package list
// a status reply reports, and the tie-break when one uid owns both data directories — which should
// not happen without a sharedUserId, so the daemon settles it deterministically rather than treating
// one caller as two identities.
extern const helper_app_package HELPER_APP_PACKAGES[HELPER_APP_PACKAGE_COUNT];

// The table entry for an app caller, or NULL for ROOT/NONE (neither owns a package identity).
const helper_app_package *helper_app_package_for(enum helper_caller caller);

// The caller an exact package id names, or HELPER_CALLER_NONE for anything else. Never used to
// authorise a caller — only to check that a verb's target is one of the two known packages.
enum helper_caller helper_caller_for_package(const char *package);

// 1 when the id is one of the two known packages.
int helper_known_package(const char *package);

// Stable name for a caller, for the status verb and refusal records.
const char *helper_caller_name(enum helper_caller caller);

#endif
