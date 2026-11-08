// Prints the typed-command allowlist tables (src/pa_allowlist.h) as JSON, so pa_allowlist_check.sh
// can compare them by value with test/pa-typed-allowlist.json, the fixture Panel Assistant's own
// parity test reads.
#include <stdio.h>
#include <string.h>

#include "pa_allowlist.h"

static void string(const char *s) {
    putchar('"');
    for (; *s; s++) {
        if (*s == '"' || *s == '\\') putchar('\\');
        putchar(*s);
    }
    putchar('"');
}

static void list(const char *const *items) {
    putchar('[');
    for (int i = 0; items[i]; i++) { if (i) putchar(','); string(items[i]); }
    putchar(']');
}

// A per-launch table as an object keyed by launch component, as Panel Assistant keys it.
static void by_launch_list(const char *const table[][3]) {
    putchar('{');
    for (int i = 0; i < PA_LAUNCH_COUNT; i++) {
        if (i) putchar(',');
        string(PA_LAUNCH_COMPONENTS[i]); putchar(':'); list(table[i]);
    }
    putchar('}');
}

static void by_launch(const char *const table[PA_LAUNCH_COUNT]) {
    putchar('{');
    for (int i = 0; i < PA_LAUNCH_COUNT; i++) {
        if (i) putchar(',');
        string(PA_LAUNCH_COMPONENTS[i]); putchar(':'); string(table[i]);
    }
    putchar('}');
}

// The layout is Panel Assistant's (tests/test_pa_typed_allowlist.py), which is the source of truth.
int main(void) {
    const char *const launches[] = { PA_LAUNCH_COMPONENTS[0], PA_LAUNCH_COMPONENTS[1], NULL };
    const char *const app_ops[] = { PA_APP_OPS[0], PA_APP_OPS[1], PA_APP_OPS[2], NULL };
    printf("{\"launch_components\":"); list(launches);
    printf(",\"accessibility_components\":{\"current\":"); by_launch(PA_ACCESSIBILITY_COMPONENTS);
    printf(",\"equivalent\":"); by_launch_list(PA_EQUIVALENT_ACCESSIBILITY);
    // Panel Assistant lists only the launches that have an obsolete service.
    printf(",\"obsolete\":{");
    for (int i = 0, n = 0; i < PA_LAUNCH_COUNT; i++) {
        if (!PA_OBSOLETE_ACCESSIBILITY[i][0]) continue;
        const char *const one[] = { PA_OBSOLETE_ACCESSIBILITY[i][0], PA_OBSOLETE_ACCESSIBILITY[i][1], NULL };
        if (n++) putchar(',');
        string(PA_LAUNCH_COMPONENTS[i]); putchar(':'); list(one);
    }
    printf("}}");
    printf(",\"app_ops\":"); list(app_ops);
    printf(",\"runtime_grants\":[");
    for (int i = 0; i < 2; i++) {
        if (i) putchar(',');
        printf("{\"feature\":"); string(PA_RUNTIME_GRANTS[i].feature);
        printf(",\"permission\":"); string(PA_RUNTIME_GRANTS[i].permission); putchar('}');
    }
    printf("],\"notifications\":{\"permission\":"); string(PA_NOTIFICATIONS_PERMISSION);
    printf(",\"min_sdk\":%d}", PA_NOTIFICATIONS_MIN_SDK);
    printf(",\"secure_settings_permission\":"); string(PA_SECURE_SETTINGS_PERMISSION);
    printf(",\"home_components\":{\"current\":"); by_launch(PA_HOME_COMPONENTS);
    printf(",\"equivalent\":"); by_launch_list(PA_EQUIVALENT_HOME);
    printf(",\"labels\":"); list(PA_APP_LABELS); putchar('}');
    printf(",\"device_admin\":{\"receiver\":"); string(PA_DEVICE_ADMIN_RECEIVER);
    printf(",\"release_receiver\":"); string(PA_RELEASE_DEVICE_ADMIN_RECEIVER); putchar('}');
    printf(",\"package_ids\":{\"app\":"); string(PA_APP_PACKAGE);
    printf(",\"legacy\":"); string(PA_LEGACY_PACKAGE);
    printf(",\"shelly\":"); string(PA_SHELLY_PACKAGE); putchar('}');
    printf(",\"shelly_splash\":"); string(PA_SHELLY_SPLASH);
    printf(",\"meta\":{\"devices\":"); list(PA_META_DEVICES);
    printf(",\"control_center\":"); string(PA_META_CONTROL_CENTER); putchar('}');
    static const struct { const char *name; const char *basename; const char *const *keys; } RECORDS[] = {
        { "meta", PA_RECORD_META, PA_RECORD_META_KEYS },
        { "meta_verifier", PA_RECORD_META_VERIFIER, PA_RECORD_VERIFIER_KEYS },
        { "shelly", PA_RECORD_SHELLY, PA_RECORD_SHELLY_KEYS },
        { "display", PA_RECORD_DISPLAY, PA_RECORD_DISPLAY_KEYS },
    };
    printf(",\"restore_settings_keys\":{");
    for (int i = 0; i < 4; i++) {
        if (i) putchar(',');
        string(RECORDS[i].name); putchar(':'); list(RECORDS[i].keys);
    }
    printf("},\"record_paths\":{");
    for (int i = 0; i < 4; i++) {
        char path[256];
        snprintf(path, sizeof path, "%s/%s", PA_RECORD_DIR_PANEL, RECORDS[i].basename);
        if (i) putchar(',');
        string(RECORDS[i].name); putchar(':'); string(path);
    }
    printf("}}\n");
    return 0;
}
