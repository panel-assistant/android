// The app identity this daemon serves — the ONE place the panel-side package id is written.
//
// Every consumer of the app identity reads this table rather than spelling the id out again: peer
// authentication (main.c), the INSTALLGC path validator and the never-tear-down backstop (util.c),
// Guard's package/database selection (guard_maintenance.c), and the package-restricted GRANT verb
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
// the test uid owns.
#ifndef APP_DATA
#define APP_DATA "/data/data/io.panelassistant.android"
#endif
#ifndef APP_USER_DATA
#define APP_USER_DATA "/data/user/0/io.panelassistant.android"
#endif

// The accessibility service's fully qualified class; the app's component is its id plus this class.
#define APP_ACCESSIBILITY_CLASS "io.panelassistant.android.input.PanelAccessibilityService"

// Who is on the other end of a connection. Resolved once per connection from SO_PEERCRED and carried
// in conn_ctx, so a handler never has to ask, and never takes the answer from the request.
enum helper_caller {
    HELPER_CALLER_NONE = 0,   // not authenticated — no verb runs
    HELPER_CALLER_ROOT,       // uid 0: an ADB/root operator, which owns no package data directory
    HELPER_CALLER_SUCCESSOR,  // io.panelassistant.android
};

#define HELPER_APP_PACKAGE_COUNT 1

typedef struct {
    enum helper_caller caller;
    const char *package;   // exact Android package id
    const char *data_dir;  // legacy /data/data form — stat'ed to resolve the app's live uid
    const char *user_dir;  // multi-user /data/user/0 form returned by getCacheDir() on API 24+
} helper_app_package;

extern const helper_app_package HELPER_APP_PACKAGES[HELPER_APP_PACKAGE_COUNT];

// The table entry for an app caller, or NULL for ROOT/NONE (neither owns a package identity).
const helper_app_package *helper_app_package_for(enum helper_caller caller);

// 1 when the id is the known package. Never used to authorise a caller — only to check that a
// verb's target is the app's own package.
int helper_known_package(const char *package);

// Only ENOENT at both canonical addresses proves removal. Permission/IO errors retain the helper.
int helper_app_data_absent(void);

#endif
