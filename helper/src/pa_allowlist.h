// The one helper-side definition of every value the typed Panel Assistant commands (pa_commands.c)
// may put on a command line or compare against panel output.
//
// Panel Assistant's own definitions (app_identity.py, adb_protocol.py, home_chooser.py,
// previous_home.py, vendor_provisioning.py, display_provisioning.py, install_adb.py) stay the source
// of truth. test/pa-typed-allowlist.json holds these values; test/pa_allowlist_check.sh proves this
// header still equals that fixture, and Panel Assistant's parity test compares its own definitions to
// the same file. A change here needs the same change there.
//
// Per-launch tables are indexed by the REPAIR/HOME `launch` argument: 0 is the build whose classes
// still live in the old Kotlin package, 1 the build with the new classes (PA_LAUNCH_COMPONENTS).
#ifndef HAPANELD_PA_ALLOWLIST_H
#define HAPANELD_PA_ALLOWLIST_H

#define PA_UNUSED __attribute__((unused))
#define PA_LAUNCH_COUNT 2

static const char *const PA_LAUNCH_COMPONENTS[PA_LAUNCH_COUNT] PA_UNUSED = {
    "io.panelassistant.android/io.github.maxlyth.hapaneld.MainActivity",
    "io.panelassistant.android/io.panelassistant.android.MainActivity",
};

// Accessibility service written into the shared list, its equivalent spellings, and the class the
// 0.9.10 update removed. Lists end with NULL.
static const char *const PA_ACCESSIBILITY_COMPONENTS[PA_LAUNCH_COUNT] PA_UNUSED = {
    "io.panelassistant.android/io.github.maxlyth.hapaneld.input.PanelAccessibilityService",
    "io.panelassistant.android/io.panelassistant.android.input.PanelAccessibilityService",
};
static const char *const PA_EQUIVALENT_ACCESSIBILITY[PA_LAUNCH_COUNT][3] PA_UNUSED = {
    { "io.panelassistant.android/io.github.maxlyth.hapaneld.input.PanelAccessibilityService", NULL },
    { "io.panelassistant.android/.input.PanelAccessibilityService",
      "io.panelassistant.android/io.panelassistant.android.input.PanelAccessibilityService", NULL },
};
static const char *const PA_OBSOLETE_ACCESSIBILITY[PA_LAUNCH_COUNT][2] PA_UNUSED = {
    { NULL },
    { "io.panelassistant.android/io.github.maxlyth.hapaneld.input.PanelAccessibilityService", NULL },
};

// App ops REPAIR allows when Android proves them not allowed.
static const char *const PA_APP_OPS[3] PA_UNUSED = {
    "WRITE_SETTINGS", "SYSTEM_ALERT_WINDOW", "REQUEST_INSTALL_PACKAGES",
};

// Runtime grants and the hardware feature each needs (adb_protocol.RUNTIME_GRANTS), with the reply key.
static const struct { const char *feature; const char *permission; const char *key; } PA_RUNTIME_GRANTS[2] PA_UNUSED = {
    { "camera.any", "android.permission.CAMERA", "camera" },
    { "microphone", "android.permission.RECORD_AUDIO", "microphone" },
};
#define PA_NOTIFICATIONS_PERMISSION "android.permission.POST_NOTIFICATIONS"
#define PA_NOTIFICATIONS_MIN_SDK 33
#define PA_SECURE_SETTINGS_PERMISSION "android.permission.WRITE_SECURE_SETTINGS"

// The dashboard that answers HOME, and every spelling Android reads back for it.
static const char *const PA_HOME_COMPONENTS[PA_LAUNCH_COUNT] PA_UNUSED = {
    "io.panelassistant.android/io.github.maxlyth.hapaneld.DashboardActivity",
    "io.panelassistant.android/io.panelassistant.android.DashboardActivity",
};
static const char *const PA_EQUIVALENT_HOME[PA_LAUNCH_COUNT][3] PA_UNUSED = {
    { "io.panelassistant.android/io.github.maxlyth.hapaneld.DashboardActivity", NULL },
    { "io.panelassistant.android/.DashboardActivity",
      "io.panelassistant.android/io.panelassistant.android.DashboardActivity", NULL },
};
// The app's untranslated app_name values, matched inside Android's chooser title.
static const char *const PA_APP_LABELS[] PA_UNUSED = { "Panel Assistant", "ha-paneld", NULL };

// The app's receivers (full class names; components are the package plus one of these).
#define PA_DEVICE_ADMIN_RECEIVER "io.panelassistant.android.platform.PanelDeviceAdminReceiver"
#define PA_RELEASE_DEVICE_ADMIN_RECEIVER "io.panelassistant.android.platform.ReleaseDeviceAdminReceiver"
#define PA_HOME_POLICY_RECEIVER "io.panelassistant.android.platform.PreservePreviousHomeReceiver"

// Packages the move observation reports, and the vendor launcher HOME may be handed to.
#define PA_APP_PACKAGE "io.panelassistant.android"
#define PA_LEGACY_PACKAGE "io.github.maxlyth.hapaneld"
#define PA_SHELLY_PACKAGE "cloud.shelly.stargate"
#define PA_SHELLY_SPLASH "cloud.shelly.stargate/.activities.SplashActivity"

// Meta Portal codenames and Meta's own values, recorded in place of an already blank value.
static const char *const PA_META_DEVICES[] PA_UNUSED = { "omni", "aloha", NULL };
#define PA_META_CONTROL_CENTER "com.facebook.alohaapps.controlcenter"
#define PA_META_SCREENSAVER_COMPONENTS \
    "com.facebook.alohaapps.launcher/com.facebook.aloha.app.home.touch.HomeDreamService," \
    "com.facebook.alohaapps.superframe/com.facebook.alohaapps.superframe.SuperframeDream"
#define PA_META_SCREENSAVER_DEFAULT_COMPONENT \
    "com.facebook.alohaapps.launcher/com.facebook.aloha.app.home.touch.HomeDreamService"
static const char *const PA_OVERLAY_MODES[] PA_UNUSED = { "allow", "ignore", "deny", "default", "foreground", NULL };

// Settings the restore records name, by namespace.
#define PA_SETTING_SCREENSAVER_COMPONENTS "screensaver_components"          // secure
#define PA_SETTING_SCREENSAVER_DEFAULT "screensaver_default_component"      // secure
#define PA_SETTING_VERIFIER "package_verifier_enable"                       // global
#define PA_SETTING_FONT_SCALE "font_scale"                                   // system

// Restore records: the basenames the shell installers write under the record directory.
#define PA_RECORD_DIR_PANEL "/data/local/tmp"
#define PA_RECORD_META "panel-assistant-meta-portal-restore"
#define PA_RECORD_META_VERIFIER "panel-assistant-meta-portal-verifier-restore"
#define PA_RECORD_SHELLY "panel-assistant-shelly-restore"
#define PA_RECORD_DISPLAY "panel-assistant-display-restore"

// Keys each record may hold; any other key makes the record invalid.
static const char *const PA_RECORD_META_KEYS[] PA_UNUSED = {
    "screensaver_components", "screensaver_default_component", "controlcenter_overlay", NULL };
static const char *const PA_RECORD_VERIFIER_KEYS[] PA_UNUSED = { "package_verifier_enable", NULL };
static const char *const PA_RECORD_SHELLY_KEYS[] PA_UNUSED = { "stargate_overlay", NULL };
static const char *const PA_RECORD_DISPLAY_KEYS[] PA_UNUSED = { "density", "font_scale", NULL };

// Every word PA_HOME may report as `chooser=` (home_chooser.CHOOSER_OUTCOMES).
static const char *const PA_CHOOSER_OUTCOMES[] PA_UNUSED = {
    "deferred", "retained", "kept", "set", "tapped", "other_app", "no_button", "other_home", NULL };

#endif
