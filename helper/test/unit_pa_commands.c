// Typed Panel Assistant command cases for the helper unit binary, included by unit.c where its stub,
// dispatch and argv helpers are in scope; it is not a translation unit of its own.

// --- Typed Panel Assistant commands (protocol 1.17) -----------------------------------------------
// The eight PA_ verbs replace root shell programs Panel Assistant used to send. Their safety argument
// is that only the app may call them, no request byte reaches argv unless it is an exact table
// member, and every write is a proven-missing repair. The cases below pin those properties through
// dispatch with stubbed panel output, the same way the GRANT cases do.
#define PA_TEST_DIR "/tmp/.hapaneld-pa-test"
#define PA_TEST_LOCK "/tmp/.hapaneld-pa-test-lock"
#define PA_TEST_DUMP "/tmp/.hapaneld-pa-test-chooser/chooser.xml"
#define PA_META_RECORD PA_TEST_DIR "/panel-assistant-meta-portal-restore"
#define PA_VERIFIER_RECORD PA_TEST_DIR "/panel-assistant-meta-portal-verifier-restore"
#define PA_SHELLY_RECORD PA_TEST_DIR "/panel-assistant-shelly-restore"
#define PA_DISPLAY_RECORD PA_TEST_DIR "/panel-assistant-display-restore"
#define PA_ACCESSIBILITY_NEW SUCCESSOR_ID "/io.panelassistant.android.input.PanelAccessibilityService"
#define PA_ACCESSIBILITY_OLD SUCCESSOR_ID "/io.github.maxlyth.hapaneld.input.PanelAccessibilityService"
#define PA_RECEIVER SUCCESSOR_ID "/io.panelassistant.android.platform.PreservePreviousHomeReceiver"
#define PA_HOME_QUERY "cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME"
#define PA_CHOOSER "priority=0 preferredOrder=0 match=0x108000 specificIndex=-1 isDefault=true\nandroid/com.android.internal.app.ResolverActivity\n"

static int captured_argv(const char *path, const char *const argv[]) {
    return sysexec_stub_count_argv(path, argv, 0);
}

// The value of [key] in an `OK <n>` body, or 0 when the reply is not OK or has no such line.
static int pa_field(const char *reply, const char *key, char *value, size_t size) {
    unsigned long declared = 0;
    int header = 0;
    if (sscanf(reply, "OK %lu\n%n", &declared, &header) != 1 || header <= 0) return 0;
    const char *body = reply + header;
    if (strlen(body) != declared) return 0;
    size_t key_size = strlen(key);
    for (const char *line = body; *line; ) {
        const char *end = strchr(line, '\n');
        if (!end) return 0;
        if ((size_t)(end - line) > key_size && strncmp(line, key, key_size) == 0 && line[key_size] == '=') {
            size_t n = (size_t)(end - line) - key_size - 1;
            if (n >= size) return 0;
            memcpy(value, line + key_size + 1, n);
            value[n] = '\0';
            return 1;
        }
        line = end + 1;
    }
    return 0;
}

static int pa_field_is(const char *reply, const char *key, const char *expected) {
    char value[512];
    return pa_field(reply, key, value, sizeof value) && strcmp(value, expected) == 0;
}

static void pa_stub_identity(const char *sdk) {
    sysexec_stub_add_popen("getprop ro.product.model", "NSPanel\n", 0);
    sysexec_stub_add_popen("getprop ro.serialno", "ABC123\n", 0);
    sysexec_stub_add_popen("getprop ro.product.cpu.abi", "armeabi-v7a\n", 0);
    sysexec_stub_add_popen("getprop ro.build.version.sdk", sdk, 0);
    sysexec_stub_add_popen("getprop ro.secure", "1\n", 0);
    sysexec_stub_add_popen("getprop ro.debuggable", "0\n", 0);
}

static void pa_write_fixture(const char *path, const char *content, mode_t mode) {
    unlink(path);
    int fd = open(path, O_WRONLY | O_CREAT | O_EXCL | O_CLOEXEC, mode);
    CHECK(fd >= 0, "PA fixture %s can be created\n", path);
    if (fd < 0) return;
    CHECK(write(fd, content, strlen(content)) == (ssize_t)strlen(content), "PA fixture %s is written\n", path);
    CHECK(fchmod(fd, mode) == 0, "PA fixture %s takes its mode\n", path);
    close(fd);
}

static int pa_file_is(const char *path, const char *expected) {
    char buffer[1024];
    int fd = open(path, O_RDONLY | O_CLOEXEC);
    if (fd < 0) return 0;
    ssize_t n = read(fd, buffer, sizeof buffer - 1);
    close(fd);
    if (n < 0) return 0;
    buffer[n] = '\0';
    return strcmp(buffer, expected) == 0;
}

static void pa_reset_records(void) {
    mkdir(PA_TEST_DIR, 0755);
    unlink(PA_META_RECORD); unlink(PA_META_RECORD ".part");
    unlink(PA_VERIFIER_RECORD); unlink(PA_VERIFIER_RECORD ".part");
    unlink(PA_SHELLY_RECORD); unlink(PA_SHELLY_RECORD ".part");
    unlink(PA_DISPLAY_RECORD); unlink(PA_DISPLAY_RECORD ".part");
    unlink(PA_TEST_DIR "/other-name");
    unlink(PA_TEST_DIR "/link-target");
}

static void test_pa_admission_and_grammar(void) {
    char out[8192];
    static const char *const valid[] = {
        "PA_IDENTITY", "PA_REPAIR 1 0", "PA_HOME 1 repair", "PA_ADMIN", "PA_VENDOR setup",
        "PA_RESTORE vendor", "PA_OBSERVE move", "PA_HANDBACK vendor",
    };
    for (size_t i = 0; i < sizeof valid / sizeof valid[0]; i++) {
        sysexec_stub_reset();
        dispatch_reply_as(HELPER_CALLER_ROOT, valid[i], out, sizeof out);
        CHECK(strcmp(out, "ERR caller\n") == 0, "%s from a root shell is refused as caller (got '%s')\n", valid[i], out);
        CHECK(sysexec_stub_count_argv_calls() == 0, "%s from a root shell runs nothing\n", valid[i]);
    }
    char overlong[256];
    snprintf(overlong, sizeof overlong, "PA_HANDBACK component com.example.%0150d/.Home", 0);
    const char *const refused[] = {
        "PA_IDENTITY x", "PA_ADMIN x", "PA_REPAIR", "PA_REPAIR 1", "PA_REPAIR 1 0 x", "PA_REPAIR 2 0",
        "PA_REPAIR 1 01", "PA_HOME 1", "PA_HOME 1 other", "PA_HOME 1 repair x", "PA_HOME 3 deferred",
        "PA_VENDOR", "PA_VENDOR restore", "PA_VENDOR setup x", "PA_RESTORE", "PA_RESTORE other",
        "PA_RESTORE display x", "PA_OBSERVE", "PA_OBSERVE other", "PA_OBSERVE move x",
        "PA_HANDBACK", "PA_HANDBACK other", "PA_HANDBACK vendor x", "PA_HANDBACK component",
        "PA_HANDBACK component a/b/c", "PA_HANDBACK component com.example.home/.Home extra",
        "PA_HANDBACK component com.example.home", "PA_HANDBACK component /.Home",
        "PA_HANDBACK component com.example.home/", "PA_HANDBACK component com.ex;ample/.Home", overlong,
    };
    for (size_t i = 0; i < sizeof refused / sizeof refused[0]; i++) {
        sysexec_stub_reset();
        dispatch_reply_as(HELPER_CALLER_SUCCESSOR, refused[i], out, sizeof out);
        CHECK(strcmp(out, "ERR args\n") == 0, "'%s' is refused as args (got '%s')\n", refused[i], out);
        CHECK(sysexec_stub_count_argv_calls() == 0, "'%s' runs nothing\n", refused[i]);
    }
}

static void test_pa_identity(void) {
    char out[8192];
    sysexec_stub_reset();
    pa_stub_identity("30\n");
    dispatch_reply_as(HELPER_CALLER_SUCCESSOR, "PA_IDENTITY", out, sizeof out);
    CHECK(strncmp(out, "OK ", 3) == 0 && strstr(out, "\nmodel=NSPanel\nserial=") != NULL,
          "PA_IDENTITY answers an OK body that starts with the model (got '%s')\n", out);
    char uid[32];
    snprintf(uid, sizeof uid, "%u", (unsigned)getuid());
    CHECK(pa_field_is(out, "sdk", "30") && pa_field_is(out, "abi", "armeabi-v7a") &&
          pa_field_is(out, "secure", "1") && pa_field_is(out, "debuggable", "0") &&
          pa_field_is(out, "uid", uid), "PA_IDENTITY reports the identity tuple\n");
    CHECK(pa_field_is(out, "serial", "ABC123"), "PA_IDENTITY reports the serial\n");
    char su[32];
    CHECK(pa_field(out, "su", su, sizeof su) &&
          (strcmp(su, "present") == 0 || strcmp(su, "absent") == 0 || strcmp(su, "abnormal") == 0),
          "PA_IDENTITY reports su as present, absent or abnormal\n");
    // No '=' from the panel may reach PA's key=value parser.
    sysexec_stub_reset();
    sysexec_stub_add_popen("getprop ro.serialno", "bad=serial\n", 0);
    dispatch_reply_as(HELPER_CALLER_SUCCESSOR, "PA_IDENTITY", out, sizeof out);
    CHECK(pa_field_is(out, "serial", "unknown"), "a panel value with '=' is reported as unknown (got '%s')\n", out);
    CHECK(pa_field_is(out, "model", "unknown"), "an unreadable property is reported as unknown\n");
}

// Common panel answers for REPAIR: the app installed, a shared accessibility list holding another
// service plus the obsolete class, and the vendor probe on an ordinary panel.
static void pa_stub_repair_base(const char *sdk) {
    pa_stub_identity(sdk);
    sysexec_stub_add_popen("pm path " SUCCESSOR_ID, "package:/data/app/base.apk\n", 0);
    sysexec_stub_add_popen("getprop ro.product.device", "nspanel\n", 0);
}

static void test_pa_repair(void) {
    char out[8192];
    // Any other pm path failure stays failed.
    sysexec_stub_reset();
    pa_stub_identity("33\n");
    sysexec_stub_add_popen("pm path " SUCCESSOR_ID, "Error: service\n", 1);
    dispatch_reply_as(HELPER_CALLER_SUCCESSOR, "PA_REPAIR 1 0", out, sizeof out);
    CHECK(strcmp(out, "ERR failed\n") == 0, "PA_REPAIR with an erroring pm path answers ERR failed (got '%s')\n", out);
    const char *before = "com.other/.Svc:" PA_ACCESSIBILITY_OLD "\n";
    const char *after = "com.other/.Svc:" PA_ACCESSIBILITY_NEW;
    char after_line[256];
    snprintf(after_line, sizeof after_line, "%s\n", after);

    sysexec_stub_reset();
    pa_stub_repair_base("32\n");
    sysexec_stub_add_popen_times("settings get secure enabled_accessibility_services", before, 0, 2);
    sysexec_stub_add_popen("settings get secure enabled_accessibility_services", after_line, 0);
    sysexec_stub_add_popen_times("settings get secure accessibility_enabled", "0\n", 0, 1);
    sysexec_stub_add_popen("settings get secure accessibility_enabled", "1\n", 0);
    // Android 14 reports an unset operation as two lines; that default is proven non-allowed.
    sysexec_stub_add_popen_times("appops get " SUCCESSOR_ID " WRITE_SETTINGS", "No operations.\nDefault mode: default\n", 0, 1);
    sysexec_stub_add_popen("appops get " SUCCESSOR_ID " WRITE_SETTINGS", "WRITE_SETTINGS: allow\n", 0);
    sysexec_stub_add_popen("appops get " SUCCESSOR_ID " SYSTEM_ALERT_WINDOW", "SYSTEM_ALERT_WINDOW: allow; time=+1d2h ago\n", 0);
    sysexec_stub_add_popen_times("appops get " SUCCESSOR_ID " REQUEST_INSTALL_PACKAGES", "REQUEST_INSTALL_PACKAGES: default; rejectTime=+3m ago\n", 0, 1);
    sysexec_stub_add_popen("appops get " SUCCESSOR_ID " REQUEST_INSTALL_PACKAGES", "REQUEST_INSTALL_PACKAGES: allow\n", 0);
    sysexec_stub_add_popen("pm list features", "feature:android.hardware.microphone\nfeature:android.hardware.wifi\n", 0);
    sysexec_stub_add_popen_times("dumpsys package " SUCCESSOR_ID,
        "    android.permission.POST_NOTIFICATIONS: granted=false\n"
        "    android.permission.CAMERA: granted=false\n"
        "        android.permission.RECORD_AUDIO: granted=false, flags=[ USER_SENSITIVE_WHEN_GRANTED|USER_SENSITIVE_WHEN_DENIED]\n", 0, 1);
    sysexec_stub_add_popen("dumpsys package " SUCCESSOR_ID,
        "    android.permission.CAMERA: granted=false\n"
        "    android.permission.RECORD_AUDIO: granted=true\n", 0);
    dispatch_reply_as(HELPER_CALLER_SUCCESSOR, "PA_REPAIR 1 0", out, sizeof out);
    CHECK(strncmp(out, "OK ", 3) == 0, "PA_REPAIR answers OK (got '%s')\n", out);
    const char *const put_services[] = { "settings", "put", "secure", "enabled_accessibility_services", after, NULL };
    CHECK(ran_argv("/system/bin/settings", put_services) == 1,
          "PA_REPAIR drops the obsolete class, keeps other services and adds the current one\n");
    const char *const enable[] = { "settings", "put", "secure", "accessibility_enabled", "1", NULL };
    CHECK(ran_argv("/system/bin/settings", enable) == 1, "PA_REPAIR enables accessibility when it reads 0\n");
    const char *const allow_settings[] = { "appops", "set", SUCCESSOR_ID, "WRITE_SETTINGS", "allow", NULL };
    const char *const allow_overlay[] = { "appops", "set", SUCCESSOR_ID, "SYSTEM_ALERT_WINDOW", "allow", NULL };
    const char *const allow_installs[] = { "appops", "set", SUCCESSOR_ID, "REQUEST_INSTALL_PACKAGES", "allow", NULL };
    CHECK(ran_argv("/system/bin/appops", allow_settings) == 1, "PA_REPAIR allows an op Android 14 reports as two default lines\n");
    CHECK(ran_argv("/system/bin/appops", allow_installs) == 1, "PA_REPAIR allows an op that reads default\n");
    CHECK(ran_argv("/system/bin/appops", allow_overlay) == 0, "PA_REPAIR leaves an op that already reads allow\n");
    const char *const grant_camera[] = { "pm", "grant", SUCCESSOR_ID, "android.permission.CAMERA", NULL };
    const char *const grant_microphone[] = { "pm", "grant", SUCCESSOR_ID, "android.permission.RECORD_AUDIO", NULL };
    const char *const grant_notifications[] = { "pm", "grant", SUCCESSOR_ID, "android.permission.POST_NOTIFICATIONS", NULL };
    CHECK(ran_argv("/system/bin/pm", grant_camera) == 0, "PA_REPAIR never grants CAMERA when the feature is absent\n");
    CHECK(ran_argv("/system/bin/pm", grant_microphone) == 1, "PA_REPAIR grants RECORD_AUDIO when the feature is listed and denied by nobody\n");
    CHECK(ran_argv("/system/bin/pm", grant_notifications) == 0, "PA_REPAIR never grants notifications below SDK 33\n");
    CHECK(pa_field_is(out, "accessibility", "granted") && pa_field_is(out, "accessibility_enabled", "1"),
          "PA_REPAIR reports the accessibility readback\n");
    CHECK(pa_field_is(out, "op.WRITE_SETTINGS", "allow") && pa_field_is(out, "op.SYSTEM_ALERT_WINDOW", "allow") &&
          pa_field_is(out, "op.REQUEST_INSTALL_PACKAGES", "allow"), "PA_REPAIR reports each op readback\n");
    CHECK(pa_field_is(out, "camera", "unsupported") && pa_field_is(out, "microphone", "granted") &&
          pa_field_is(out, "notifications", "unsupported") && pa_field_is(out, "secure_settings", "unsupported"),
          "PA_REPAIR reports the runtime grants (got '%s')\n", out);
    CHECK(pa_field_is(out, "device", "nspanel") && pa_field_is(out, "vendor", "other"),
          "PA_REPAIR reports the vendor probe\n");

    // A writer changed the list between the read and the re-read: nothing is written over it.
    sysexec_stub_reset();
    pa_stub_repair_base("33\n");
    sysexec_stub_add_popen_times("settings get secure enabled_accessibility_services", "com.other/.Svc\n", 0, 1);
    sysexec_stub_add_popen("settings get secure enabled_accessibility_services", "com.third/.Svc\n", 0);
    sysexec_stub_add_popen("appops get", "", 1);
    sysexec_stub_add_popen("pm list features", "", 1);
    sysexec_stub_add_popen_times("dumpsys package " SUCCESSOR_ID,
        "    android.permission.POST_NOTIFICATIONS: granted=false\n", 0, 1);
    sysexec_stub_add_popen("dumpsys package " SUCCESSOR_ID,
        "    android.permission.POST_NOTIFICATIONS: granted=true, flags=[ GRANTED_BY_DEFAULT ]\n", 0);
    dispatch_reply_as(HELPER_CALLER_SUCCESSOR, "PA_REPAIR 1 0", out, sizeof out);
    const char *const overwrite[] = {
        "settings", "put", "secure", "enabled_accessibility_services", "com.other/.Svc:" PA_ACCESSIBILITY_NEW, NULL
    };
    CHECK(ran_argv("/system/bin/settings", overwrite) == 0,
          "PA_REPAIR never writes a list that changed between its read and its re-read\n");
    CHECK(pa_field_is(out, "accessibility", "unknown"), "a changed list is reported as unknown so PA retries\n");
    CHECK(ran_argv("/system/bin/appops", allow_settings) == 0, "an unreadable op is never set\n");
    CHECK(ran_argv("/system/bin/pm", grant_notifications) == 1, "PA_REPAIR grants proven-missing notifications on SDK 33\n");
    CHECK(pa_field_is(out, "notifications", "granted"), "PA_REPAIR reads notifications back as granted\n");
    CHECK(pa_field_is(out, "camera", "unknown") && pa_field_is(out, "microphone", "unknown"),
          "unreadable features leave the runtime grants unknown\n");

    // A long service of another app is kept; the request-side component cap does not apply to it.
    char long_service[200], long_before[256], long_after[512];
    snprintf(long_service, sizeof long_service, "com.vendor.%0130d/.Svc", 0);
    snprintf(long_before, sizeof long_before, "%s\n", long_service);
    snprintf(long_after, sizeof long_after, "%s:%s", long_service, PA_ACCESSIBILITY_NEW);
    sysexec_stub_reset();
    pa_stub_repair_base("30\n");
    sysexec_stub_add_popen("settings get secure enabled_accessibility_services", long_before, 0);
    sysexec_stub_add_popen("appops get", "", 1);
    sysexec_stub_add_popen("pm list features", "", 1);
    dispatch_reply_as(HELPER_CALLER_SUCCESSOR, "PA_REPAIR 1 0", out, sizeof out);
    const char *const put_long[] = { "settings", "put", "secure", "enabled_accessibility_services", long_after, NULL };
    CHECK(ran_argv("/system/bin/settings", put_long) == 1,
          "PA_REPAIR keeps a long foreign service and appends the current one\n");

    // The app is not installed: nothing is repaired.
    sysexec_stub_reset();
    pa_stub_identity("33\n");
    // sysexec returns the raw wait status: pm exiting 1 arrives as 1 << 8.
    sysexec_stub_add_popen("pm path " SUCCESSOR_ID, "", 1 << 8);
    dispatch_reply_as(HELPER_CALLER_SUCCESSOR, "PA_REPAIR 1 1", out, sizeof out);
    CHECK(strcmp(out, "ERR absent\n") == 0, "PA_REPAIR without the app installed answers ERR absent (got '%s')\n", out);

    // A pm killed by a signal (raw status SIGKILL) proves nothing about the package: ERR failed.
    sysexec_stub_reset();
    pa_stub_identity("33\n");
    sysexec_stub_add_popen("pm path " SUCCESSOR_ID, "", SIGKILL);
    dispatch_reply_as(HELPER_CALLER_SUCCESSOR, "PA_REPAIR 1 1", out, sizeof out);
    CHECK(strcmp(out, "ERR failed\n") == 0, "PA_REPAIR with a signal-killed pm answers ERR failed (got '%s')\n", out);

    // A build that asks for WRITE_SECURE_SETTINGS gets it when the dump proves it missing.
    sysexec_stub_reset();
    pa_stub_repair_base("30\n");
    sysexec_stub_add_popen("settings get secure enabled_accessibility_services", PA_ACCESSIBILITY_NEW "\n", 0);
    sysexec_stub_add_popen("settings get secure accessibility_enabled", "1\n", 0);
    sysexec_stub_add_popen("appops get", "", 1);
    sysexec_stub_add_popen("pm list features", "feature:android.hardware.wifi\n", 0);
    sysexec_stub_add_popen_times("dumpsys package " SUCCESSOR_ID, "    requested permissions:\n      android.permission.WRITE_SECURE_SETTINGS\n", 0, 1);
    sysexec_stub_add_popen("dumpsys package " SUCCESSOR_ID,
        "      android.permission.WRITE_SECURE_SETTINGS\n    android.permission.WRITE_SECURE_SETTINGS: granted=true\n", 0);
    dispatch_reply_as(HELPER_CALLER_SUCCESSOR, "PA_REPAIR 1 1", out, sizeof out);
    const char *const grant_secure[] = { "pm", "grant", SUCCESSOR_ID, "android.permission.WRITE_SECURE_SETTINGS", NULL };
    CHECK(ran_argv("/system/bin/pm", grant_secure) == 1, "PA_REPAIR grants a requested, missing WRITE_SECURE_SETTINGS\n");
    CHECK(pa_field_is(out, "secure_settings", "granted"), "PA_REPAIR reads WRITE_SECURE_SETTINGS back (got '%s')\n", out);
    const char *const any_put[] = { "settings", "put", "secure", "enabled_accessibility_services", PA_ACCESSIBILITY_NEW, NULL };
    CHECK(ran_argv("/system/bin/settings", any_put) == 0, "a list that already holds the service is not written\n");
    CHECK(ran_argv("/system/bin/settings", enable) == 0, "accessibility that already reads 1 is not written\n");
}

// PA_REPAIR on SDK 33 with camera and microphone listed, so only the decision checks stand between a
// denial and `pm grant`/`appops set`. The dump lines are the real Android 11 shape.
static void pa_stub_repair_choices(const char *dump, const char *op_answer) {
    pa_stub_repair_base("33\n");
    sysexec_stub_add_popen("settings get secure enabled_accessibility_services", PA_ACCESSIBILITY_NEW "\n", 0);
    sysexec_stub_add_popen("settings get secure accessibility_enabled", "1\n", 0);
    sysexec_stub_add_popen("appops get " SUCCESSOR_ID " WRITE_SETTINGS", op_answer, 0);
    sysexec_stub_add_popen("appops get " SUCCESSOR_ID " SYSTEM_ALERT_WINDOW", "SYSTEM_ALERT_WINDOW: allow\n", 0);
    sysexec_stub_add_popen("appops get " SUCCESSOR_ID " REQUEST_INSTALL_PACKAGES", "REQUEST_INSTALL_PACKAGES: allow\n", 0);
    sysexec_stub_add_popen("pm list features", "feature:android.hardware.camera.any\nfeature:android.hardware.microphone\n", 0);
    sysexec_stub_add_popen("dumpsys package " SUCCESSOR_ID, dump, 0);
}

static void test_pa_repair_keeps_owner_choices(void) {
    char out[8192];
    const char *const grant_camera[] = { "pm", "grant", SUCCESSOR_ID, "android.permission.CAMERA", NULL };
    const char *const grant_microphone[] = { "pm", "grant", SUCCESSOR_ID, "android.permission.RECORD_AUDIO", NULL };
    const char *const grant_notifications[] = { "pm", "grant", SUCCESSOR_ID, "android.permission.POST_NOTIFICATIONS", NULL };
    const char *const allow_settings[] = { "appops", "set", SUCCESSOR_ID, "WRITE_SETTINGS", "allow", NULL };

    // A denial the owner or a policy chose is kept: no pm grant, and the reply says denied.
    static const struct { const char *flags; const char *why; } decided[] = {
        { "[ USER_SET|USER_SENSITIVE_WHEN_GRANTED|USER_SENSITIVE_WHEN_DENIED]", "the owner's Deny in Settings" },
        { "[ USER_FIXED ]", "the owner's Don't ask again" },
        { "[ POLICY_FIXED ]", "a device policy" },
        { "[ USER_SENSITIVE_WHEN_GRANTED|USER_FIXED]", "USER_FIXED last in the list" },
    };
    for (size_t i = 0; i < sizeof decided / sizeof decided[0]; i++) {
        char dump[1024];
        snprintf(dump, sizeof dump,
            "    android.permission.POST_NOTIFICATIONS: granted=false, flags=%s\n"
            "        android.permission.CAMERA: granted=false, flags=%s\n"
            "        android.permission.RECORD_AUDIO: granted=false, flags=%s\n",
            decided[i].flags, decided[i].flags, decided[i].flags);
        sysexec_stub_reset();
        pa_stub_repair_choices(dump, "WRITE_SETTINGS: allow\n");
        dispatch_reply_as(HELPER_CALLER_SUCCESSOR, "PA_REPAIR 1 0", out, sizeof out);
        CHECK(ran_argv("/system/bin/pm", grant_microphone) == 0 && ran_argv("/system/bin/pm", grant_camera) == 0 &&
              ran_argv("/system/bin/pm", grant_notifications) == 0,
              "PA_REPAIR keeps a runtime denial flagged by %s\n", decided[i].why);
        CHECK(pa_field_is(out, "microphone", "denied") && pa_field_is(out, "camera", "denied") &&
              pa_field_is(out, "notifications", "denied"),
              "PA_REPAIR reports a runtime denial flagged by %s as denied (got '%s')\n", decided[i].why, out);
    }

    // Two users' lines: one decided denial is enough to keep the permission off.
    sysexec_stub_reset();
    pa_stub_repair_choices(
        "        android.permission.RECORD_AUDIO: granted=false, flags=[ USER_SENSITIVE_WHEN_GRANTED|USER_SENSITIVE_WHEN_DENIED]\n"
        "        android.permission.RECORD_AUDIO: granted=false, flags=[ USER_SET|USER_SENSITIVE_WHEN_GRANTED|USER_SENSITIVE_WHEN_DENIED]\n",
        "WRITE_SETTINGS: allow\n");
    dispatch_reply_as(HELPER_CALLER_SUCCESSOR, "PA_REPAIR 1 0", out, sizeof out);
    CHECK(ran_argv("/system/bin/pm", grant_microphone) == 0 && pa_field_is(out, "microphone", "denied"),
          "PA_REPAIR keeps a denial any user's line records as decided (got '%s')\n", out);

    // Android's own sensitivity flags, or a flag merely spelled like a decision, decide nothing: granted.
    static const struct { const char *flags; const char *why; } undecided[] = {
        { ", flags=[ USER_SENSITIVE_WHEN_GRANTED|USER_SENSITIVE_WHEN_DENIED]", "only sensitivity flags" },
        { "", "no flags" },
        { ", flags=[ USER_SETTLED|POLICY_FIXED_X ]", "near-miss flag names" },
    };
    for (size_t i = 0; i < sizeof undecided / sizeof undecided[0]; i++) {
        char dump[1024];
        snprintf(dump, sizeof dump, "        android.permission.RECORD_AUDIO: granted=false%s\n", undecided[i].flags);
        sysexec_stub_reset();
        pa_stub_repair_choices(dump, "WRITE_SETTINGS: allow\n");
        dispatch_reply_as(HELPER_CALLER_SUCCESSOR, "PA_REPAIR 1 0", out, sizeof out);
        CHECK(ran_argv("/system/bin/pm", grant_microphone) == 1,
              "PA_REPAIR grants a runtime denial with %s\n", undecided[i].why);
        CHECK(pa_field_is(out, "microphone", "unknown"),
              "PA_REPAIR reads an undecided denial back, never as denied (%s, got '%s')\n", undecided[i].why, out);
    }

    // An unreadable dump is unknown: nothing granted, nothing reported denied.
    sysexec_stub_reset();
    pa_stub_repair_choices("", "WRITE_SETTINGS: allow\n");
    dispatch_reply_as(HELPER_CALLER_SUCCESSOR, "PA_REPAIR 1 0", out, sizeof out);
    CHECK(ran_argv("/system/bin/pm", grant_microphone) == 0 && pa_field_is(out, "microphone", "unknown"),
          "PA_REPAIR leaves an unreadable runtime grant unknown (got '%s')\n", out);

    // An explicit app-op mode is the owner's choice: kept, and reported as Android prints it.
    static const struct { const char *answer; const char *word; } kept[] = {
        { "WRITE_SETTINGS: deny; rejectTime=+3m ago\n", "deny" },
        { "WRITE_SETTINGS: ignore\n", "ignore" },
        { "WRITE_SETTINGS: errored\n", "errored" },
        { "WRITE_SETTINGS: foreground\n", "foreground" },
    };
    for (size_t i = 0; i < sizeof kept / sizeof kept[0]; i++) {
        sysexec_stub_reset();
        pa_stub_repair_choices("        android.permission.RECORD_AUDIO: granted=true\n", kept[i].answer);
        dispatch_reply_as(HELPER_CALLER_SUCCESSOR, "PA_REPAIR 1 0", out, sizeof out);
        CHECK(ran_argv("/system/bin/appops", allow_settings) == 0, "PA_REPAIR keeps an op the owner set to %s\n", kept[i].word);
        CHECK(pa_field_is(out, "op.WRITE_SETTINGS", kept[i].word),
              "PA_REPAIR reports an op kept at %s (got '%s')\n", kept[i].word, out);
    }

    // Unset (`No operations.`, Android 14's two lines, whatever default they name) or default: allowed.
    static const char *const unset[] = {
        "WRITE_SETTINGS: default\n", "No operations.\n", "No operations.\nDefault mode: default\n",
        "No operations.\nDefault mode: deny\n",
    };
    for (size_t i = 0; i < sizeof unset / sizeof unset[0]; i++) {
        sysexec_stub_reset();
        pa_stub_repair_choices("        android.permission.RECORD_AUDIO: granted=true\n", unset[i]);
        dispatch_reply_as(HELPER_CALLER_SUCCESSOR, "PA_REPAIR 1 0", out, sizeof out);
        CHECK(ran_argv("/system/bin/appops", allow_settings) == 1, "PA_REPAIR allows an op nobody set ('%s')\n", unset[i]);
    }
    // An unset op whose default denies reads back as default, never as the owner's deny.
    CHECK(pa_field_is(out, "op.WRITE_SETTINGS", "default"),
          "PA_REPAIR reports Android 14's unset deny default as default (got '%s')\n", out);
    sysexec_stub_reset();
    pa_stub_repair_choices("        android.permission.RECORD_AUDIO: granted=true\n", "No operations.\nDefault mode: allow\n");
    dispatch_reply_as(HELPER_CALLER_SUCCESSOR, "PA_REPAIR 1 0", out, sizeof out);
    CHECK(ran_argv("/system/bin/appops", allow_settings) == 0 && pa_field_is(out, "op.WRITE_SETTINGS", "allow"),
          "PA_REPAIR leaves and reports an unset op whose default allows (got '%s')\n", out);
}

static const char PA_XML_GOOD[] =
    "<?xml version='1.0' encoding='UTF-8' standalone='yes' ?><hierarchy rotation=\"0\">"
    "<node index=\"0\" text=\"Use Panel Assistant as Home\" resource-id=\"android:id/title\" class=\"android.widget.TextView\" package=\"android\" enabled=\"true\" bounds=\"[40,100][680,160]\" />"
    "<node index=\"1\" text=\"ALWAYS\" resource-id=\"android:id/button_always\" class=\"android.widget.Button\" package=\"android\" enabled=\"true\" bounds=\"[100,200][300,260]\" />"
    "<node index=\"2\" text=\"JUST ONCE\" resource-id=\"android:id/button_once\" class=\"android.widget.Button\" package=\"android\" enabled=\"true\" bounds=\"[400,200][600,260]\" />"
    "</hierarchy>";

static void pa_stub_home_builtin(void) {
    pa_stub_identity("29\n");
    sysexec_stub_add_popen_times("--es operation dashboard_home_policy",
        "Broadcasting: Intent { flg=0x400000 cmp=x }\nBroadcast completed: result=1, data=\"panel-assistant-home-builtin\"\n", 0, 1);
    sysexec_stub_add_popen("--es operation setup_eligibility",
        "Broadcast completed: result=1, data=\"panel-assistant-setup-eligible\"\n", 0);
    sysexec_stub_add_popen(PA_HOME_QUERY, PA_CHOOSER, 0);
}

static void test_pa_home_chooser(void) {
    char out[8192], xml[4096];
    const char *const tap[] = { "input", "tap", "200", "230", NULL };
    const char *const back[] = { "input", "keyevent", "4", NULL };
    const char *const set_home[] = {
        "cmd", "package", "set-home-activity", SUCCESSOR_ID "/io.panelassistant.android.DashboardActivity", NULL
    };
    const char *const ack[] = {
        "am", "broadcast", "--include-stopped-packages", "-n", PA_RECEIVER, "--es", "operation", "acknowledge_home", NULL
    };

    sysexec_stub_reset();
    pa_stub_home_builtin();
    sysexec_stub_add_popen("--es operation dashboard_home_policy",
        "Broadcast completed: result=1, data=\"panel-assistant-home-builtin\"\n", 0);
    sysexec_stub_write_file_on_run("uiautomator dump", PA_TEST_DUMP, PA_XML_GOOD);
    dispatch_reply_as(HELPER_CALLER_SUCCESSOR, "PA_HOME 1 repair", out, sizeof out);
    CHECK(pa_field_is(out, "chooser", "tapped"), "PA_HOME presses Always on the app's own chooser (got '%s')\n", out);
    CHECK(pa_field_is(out, "policy", "builtin"), "PA_HOME reports the builtin policy\n");
    CHECK(captured_argv("/system/bin/input", tap) == 1, "PA_HOME taps the centre of the Always button\n");
    CHECK(captured_argv("/system/bin/cmd", set_home) == 1, "PA_HOME asks set-home-activity for the dashboard first\n");
    CHECK(captured_argv("/system/bin/am", ack) == 1, "PA_HOME acknowledges the claim\n");
    CHECK(access(PA_TEST_DUMP, F_OK) != 0, "PA_HOME removes the chooser dump after reading it\n");

    // The chooser offers another app: nothing is pressed and the chooser is dismissed.
    replace_text(PA_XML_GOOD, "Use Panel Assistant as Home", "Use Launcher3 as Home", xml, sizeof xml);
    sysexec_stub_reset();
    pa_stub_home_builtin();
    sysexec_stub_add_popen("--es operation dashboard_home_policy",
        "Broadcast completed: result=1, data=\"panel-assistant-home-builtin\"\n", 0);
    sysexec_stub_write_file_on_run("uiautomator dump", PA_TEST_DUMP, xml);
    dispatch_reply_as(HELPER_CALLER_SUCCESSOR, "PA_HOME 1 repair", out, sizeof out);
    CHECK(captured_argv("/system/bin/input", tap) == 0, "PA_HOME never presses a chooser whose title names another app\n");
    CHECK(pa_field_is(out, "chooser", "other_app"), "a chooser for another app is reported as other_app\n");
    CHECK(captured_argv("/system/bin/input", back) == 1, "a chooser that is not pressed is dismissed\n");

    // Two Always buttons: ambiguous, so nothing is pressed.
    replace_text(PA_XML_GOOD, "</hierarchy>",
        "<node text=\"ALWAYS\" resource-id=\"android:id/button_always\" package=\"android\" enabled=\"true\" bounds=\"[0,0][50,50]\" /></hierarchy>",
        xml, sizeof xml);
    sysexec_stub_reset();
    pa_stub_home_builtin();
    sysexec_stub_add_popen("--es operation dashboard_home_policy",
        "Broadcast completed: result=1, data=\"panel-assistant-home-builtin\"\n", 0);
    sysexec_stub_write_file_on_run("uiautomator dump", PA_TEST_DUMP, xml);
    dispatch_reply_as(HELPER_CALLER_SUCCESSOR, "PA_HOME 1 repair", out, sizeof out);
    CHECK(captured_argv("/system/bin/input", tap) == 0, "PA_HOME never presses one of two Always buttons\n");
    CHECK(pa_field_is(out, "chooser", "no_button"), "two Always buttons are reported as no_button\n");

    // Zero-area bounds: nothing to press.
    replace_text(PA_XML_GOOD, "[100,200][300,260]", "[100,200][100,260]", xml, sizeof xml);
    sysexec_stub_reset();
    pa_stub_home_builtin();
    sysexec_stub_add_popen("--es operation dashboard_home_policy",
        "Broadcast completed: result=1, data=\"panel-assistant-home-builtin\"\n", 0);
    sysexec_stub_write_file_on_run("uiautomator dump", PA_TEST_DUMP, xml);
    dispatch_reply_as(HELPER_CALLER_SUCCESSOR, "PA_HOME 1 repair", out, sizeof out);
    const char *const zero_tap[] = { "input", "tap", "100", "230", NULL };
    CHECK(captured_argv("/system/bin/input", zero_tap) == 0, "PA_HOME never presses a zero-area button\n");

    // The policy changed during the chooser waits: the re-check before the tap refuses it. The
    // first two policy reads (the claim and its authority check) still say builtin.
    sysexec_stub_reset();
    pa_stub_home_builtin();
    sysexec_stub_add_popen_times("--es operation dashboard_home_policy",
        "Broadcast completed: result=1, data=\"panel-assistant-home-builtin\"\n", 0, 1);
    sysexec_stub_add_popen("--es operation dashboard_home_policy",
        "Broadcast completed: result=2, data=\"panel-assistant-home-retained\"\n", 0);
    sysexec_stub_write_file_on_run("uiautomator dump", PA_TEST_DUMP, PA_XML_GOOD);
    dispatch_reply_as(HELPER_CALLER_SUCCESSOR, "PA_HOME 1 repair", out, sizeof out);
    CHECK(captured_argv("/system/bin/input", tap) == 0, "PA_HOME re-checks the policy before pressing Always\n");
    CHECK(pa_field_is(out, "chooser", "deferred"), "a policy that changed before the tap is reported as deferred\n");

    // HOME already resolves to the dashboard: nothing is sent.
    sysexec_stub_reset();
    pa_stub_identity("29\n");
    sysexec_stub_add_popen("--es operation dashboard_home_policy",
        "Broadcast completed: result=1, data=\"panel-assistant-home-builtin\"\n", 0);
    sysexec_stub_add_popen("--es operation setup_eligibility",
        "Broadcast completed: result=1, data=\"panel-assistant-setup-eligible\"\n", 0);
    sysexec_stub_add_popen(PA_HOME_QUERY, "x\n" SUCCESSOR_ID "/.DashboardActivity\n", 0);
    dispatch_reply_as(HELPER_CALLER_SUCCESSOR, "PA_HOME 1 repair", out, sizeof out);
    CHECK(pa_field_is(out, "chooser", "kept") && captured_argv("/system/bin/cmd", set_home) == 0,
          "PA_HOME keeps a HOME that already resolves to the dashboard (got '%s')\n", out);
    CHECK(pa_field_is(out, "home", SUCCESSOR_ID "/.DashboardActivity"), "PA_HOME reports the HOME readback\n");
}

static void test_pa_home_lock(void) {
    char out[8192];
    mkdir(PA_TEST_LOCK, 0755);
    // Another process holds the shared HOME lock for a moment. The claim must refuse at once, not
    // wait for it.
    int ready[2];
    CHECK(pipe(ready) == 0, "lock holder pipe\n");
    pid_t holder = fork();
    if (holder == 0) {
        int fd = open(PA_TEST_LOCK, O_RDONLY | O_DIRECTORY | O_CLOEXEC);
        if (fd < 0 || flock(fd, LOCK_EX) != 0) _exit(1);
        if (write(ready[1], "R", 1) != 1) _exit(1);
        usleep(1500 * 1000);
        _exit(0);
    }
    char byte;
    CHECK(read(ready[0], &byte, 1) == 1, "lock holder took the lock\n");
    close(ready[0]); close(ready[1]);
    sysexec_stub_reset();
    pa_stub_identity("29\n");
    sysexec_stub_add_popen("--es operation claim_pending_home",
        "Broadcast completed: result=2, data=\"panel-assistant-pending-home-shell\"\n", 0);
    sysexec_stub_add_popen(PA_HOME_QUERY, PA_CHOOSER, 0);
    dispatch_reply_as(HELPER_CALLER_SUCCESSOR, "PA_HOME 1 deferred", out, sizeof out);
    CHECK(strcmp(out, "ERR busy\n") == 0, "PA_HOME refuses as busy while the HOME lock is held (got '%s')\n", out);
    int status;
    waitpid(holder, &status, 0);

    // Free lock: the eligibility broadcast carries the locked directory's identity, and every HOME
    // actuator runs with the lock as its stdin.
    struct stat lock_st;
    CHECK(stat(PA_TEST_LOCK, &lock_st) == 0, "lock directory exists\n");
    char identity[64];
    snprintf(identity, sizeof identity, "%llu:%llu", (unsigned long long)lock_st.st_dev,
             (unsigned long long)lock_st.st_ino);
    sysexec_stub_reset();
    pa_stub_identity("29\n");
    sysexec_stub_add_popen("--es operation claim_pending_home",
        "Broadcast completed: result=2, data=\"panel-assistant-pending-home-shell\"\n", 0);
    sysexec_stub_add_popen("--es operation pending_home_shell_lock_eligibility",
        "Broadcast completed: result=1, data=\"panel-assistant-pending-home-shell-locked\"\n", 0);
    sysexec_stub_add_popen_times(PA_HOME_QUERY, PA_CHOOSER, 0, 1);
    sysexec_stub_add_popen(PA_HOME_QUERY, SUCCESSOR_ID "/io.panelassistant.android.DashboardActivity\n", 0);
    dispatch_reply_as(HELPER_CALLER_SUCCESSOR, "PA_HOME 1 deferred", out, sizeof out);
    const char *const eligibility[] = {
        "am", "broadcast", "--include-stopped-packages", "-n", PA_RECEIVER, "--es", "operation",
        "pending_home_shell_lock_eligibility", "--es", "home_lock_identity", identity, NULL
    };
    CHECK(captured_argv("/system/bin/am", eligibility) == 1, "the eligibility broadcast names the locked identity\n");
    CHECK(pa_field_is(out, "policy", "shell") && pa_field_is(out, "chooser", "set"),
          "the locked deferred claim sets HOME (got '%s')\n", out);
    CHECK(sysexec_stub_count_stdin_fd_calls() >= 3, "HOME actuators run with the lock as stdin\n");
}

static int pa_ran(const char *a, const char *b);

static void test_pa_records(void) {
    char out[8192];
    const char *const deny_shelly[] = { "appops", "set", "cloud.shelly.stargate", "SYSTEM_ALERT_WINDOW", "allow", NULL };

    // A first-install record, written by the panel's shell with its default umask, is accepted.
    pa_reset_records();
    pa_write_fixture(PA_SHELLY_RECORD, "stargate_overlay=allow\n", 0644);
    CHECK(chown(PA_SHELLY_RECORD, 2000, 2000) == 0, "the shelly record can be owned by shell\n");
    sysexec_stub_reset();
    pa_stub_identity("30\n");
    sysexec_stub_add_popen("pm path cloud.shelly.stargate", "package:/system/app/stargate.apk\n", 0);
    sysexec_stub_add_popen("appops get cloud.shelly.stargate SYSTEM_ALERT_WINDOW", "SYSTEM_ALERT_WINDOW: allow\n", 0);
    dispatch_reply_as(HELPER_CALLER_SUCCESSOR, "PA_RESTORE vendor", out, sizeof out);
    CHECK(ran_argv("/system/bin/appops", deny_shelly) == 1, "PA_RESTORE puts back the recorded Shelly overlay mode\n");
    CHECK(pa_field_is(out, "result", "restored"), "PA_RESTORE reports restored (got '%s')\n", out);
    CHECK(access(PA_SHELLY_RECORD, F_OK) != 0, "PA_RESTORE forgets a record once it reads back\n");

    // A symlinked record is refused, and its target is never acted on.
    pa_reset_records();
    pa_write_fixture(PA_TEST_DIR "/link-target", "stargate_overlay=allow\n", 0644);
    CHECK(symlink(PA_TEST_DIR "/link-target", PA_SHELLY_RECORD) == 0, "symlinked record fixture\n");
    sysexec_stub_reset();
    sysexec_stub_add_popen("pm path cloud.shelly.stargate", "package:/system/app/stargate.apk\n", 0);
    dispatch_reply_as(HELPER_CALLER_SUCCESSOR, "PA_RESTORE vendor", out, sizeof out);
    CHECK(strcmp(out, "ERR failed\n") == 0, "PA_RESTORE refuses a symlinked record (got '%s')\n", out);
    CHECK(ran_argv("/system/bin/appops", deny_shelly) == 0, "a symlinked record is never applied\n");

    // A record with a second name is refused.
    pa_reset_records();
    pa_write_fixture(PA_SHELLY_RECORD, "stargate_overlay=allow\n", 0644);
    CHECK(link(PA_SHELLY_RECORD, PA_TEST_DIR "/other-name") == 0, "hard-linked record fixture\n");
    sysexec_stub_reset();
    sysexec_stub_add_popen("pm path cloud.shelly.stargate", "package:/system/app/stargate.apk\n", 0);
    dispatch_reply_as(HELPER_CALLER_SUCCESSOR, "PA_RESTORE vendor", out, sizeof out);
    CHECK(strcmp(out, "ERR failed\n") == 0, "PA_RESTORE refuses a record with two links (got '%s')\n", out);
    CHECK(ran_argv("/system/bin/appops", deny_shelly) == 0, "a hard-linked record is never applied\n");

    // No record: nothing changed, nothing to do.
    pa_reset_records();
    sysexec_stub_reset();
    dispatch_reply_as(HELPER_CALLER_SUCCESSOR, "PA_RESTORE display", out, sizeof out);
    CHECK(pa_field_is(out, "result", "none"), "PA_RESTORE display without a record reports none (got '%s')\n", out);

    // Display record: density reset and the recorded font scale, then the record goes.
    pa_reset_records();
    pa_write_fixture(PA_DISPLAY_RECORD, "density=none\nfont_scale=1.15\n", 0644);
    sysexec_stub_reset();
    sysexec_stub_add_popen("wm density", "Physical density: 240\n", 0);
    sysexec_stub_add_popen("settings get system font_scale", "1.15\n", 0);
    dispatch_reply_as(HELPER_CALLER_SUCCESSOR, "PA_RESTORE display", out, sizeof out);
    const char *const density_reset[] = { "wm", "density", "reset", NULL };
    const char *const font_put[] = { "settings", "put", "system", "font_scale", "1.15", NULL };
    CHECK(ran_argv("/system/bin/wm", density_reset) == 1 && ran_argv("/system/bin/settings", font_put) == 1,
          "PA_RESTORE display puts back density and font scale\n");
    CHECK(pa_field_is(out, "result", "restored") && access(PA_DISPLAY_RECORD, F_OK) != 0,
          "PA_RESTORE display forgets the record once it reads back (got '%s')\n", out);

    // A display record missing a key is no baseline: nothing is written and the record stays.
    pa_reset_records();
    pa_write_fixture(PA_DISPLAY_RECORD, "density=420\n", 0644);
    sysexec_stub_reset();
    sysexec_stub_add_popen("wm density", "Physical density: 240\n", 0);
    sysexec_stub_add_popen("settings get system font_scale", "1.0\n", 0);
    dispatch_reply_as(HELPER_CALLER_SUCCESSOR, "PA_RESTORE display", out, sizeof out);
    CHECK(pa_field_is(out, "result", "unrestored") && pa_ran("wm", "density") == 0 &&
          pa_ran("settings", "put") == 0 && pa_ran("settings", "delete") == 0 &&
          pa_file_is(PA_DISPLAY_RECORD, "density=420\n"),
          "PA_RESTORE display keeps a record missing a key and writes nothing (got '%s')\n", out);

    // A partial Meta or verifier record is no baseline: restore writes nothing and keeps it.
    const char *const partial[][2] = {
        { PA_META_RECORD, "screensaver_default_component=com.meta/.Dream\ncontrolcenter_overlay=allow\n" },
        { PA_META_RECORD, "screensaver_components=com.meta/.Dream\ncontrolcenter_overlay=allow\n" },
        { PA_META_RECORD, "screensaver_components=com.meta/.Dream\nscreensaver_default_component=com.meta/.Dream\n" },
        { PA_VERIFIER_RECORD, "" },
    };
    for (size_t i = 0; i < sizeof partial / sizeof partial[0]; i++) {
        pa_reset_records();
        pa_write_fixture(partial[i][0], partial[i][1], 0644);
        sysexec_stub_reset();
        sysexec_stub_add_popen("settings get secure screensaver_components", "\n", 0);
        sysexec_stub_add_popen("settings get secure screensaver_default_component", "\n", 0);
        sysexec_stub_add_popen("settings get global package_verifier_enable", "0\n", 0);
        sysexec_stub_add_popen("pm path com.facebook.alohaapps.controlcenter", "package:/system/app/cc.apk\n", 0);
        sysexec_stub_add_popen("appops get com.facebook.alohaapps.controlcenter SYSTEM_ALERT_WINDOW", "SYSTEM_ALERT_WINDOW: deny\n", 0);
        dispatch_reply_as(HELPER_CALLER_SUCCESSOR, "PA_RESTORE vendor", out, sizeof out);
        CHECK(pa_field_is(out, "result", "unrestored") && pa_ran("settings", "put") == 0 &&
              pa_ran("settings", "delete") == 0 && pa_ran("appops", "set") == 0 &&
              pa_file_is(partial[i][0], partial[i][1]),
              "PA_RESTORE keeps partial vendor record %zu and writes nothing (got '%s')\n", i, out);
    }

    // Setup treats a partial existing Meta record as invalid and changes nothing.
    pa_reset_records();
    pa_write_fixture(PA_META_RECORD, partial[0][1], 0644);
    sysexec_stub_reset();
    pa_stub_identity("29\n");
    sysexec_stub_add_popen("getprop ro.product.device", "omni\n", 0);
    sysexec_stub_add_popen("settings get secure screensaver_components", "com.meta/.Dream\n", 0);
    sysexec_stub_add_popen("settings get secure screensaver_default_component", "com.meta/.Dream\n", 0);
    sysexec_stub_add_popen("pm path com.facebook.alohaapps.controlcenter", "package:/system/app/cc.apk\n", 0);
    sysexec_stub_add_popen("appops get com.facebook.alohaapps.controlcenter SYSTEM_ALERT_WINDOW", "SYSTEM_ALERT_WINDOW: allow\n", 0);
    sysexec_stub_add_popen("settings get global package_verifier_enable", "1\n", 0);
    dispatch_reply_as(HELPER_CALLER_SUCCESSOR, "PA_VENDOR setup", out, sizeof out);
    CHECK(pa_ran("settings", "put") == 0 && pa_ran("appops", "set") == 0 &&
          pa_field_is(out, "record", "invalid") && pa_field_is(out, "converged", "0") &&
          pa_file_is(PA_META_RECORD, partial[0][1]),
          "setup keeps a partial Meta record and writes nothing (got '%s')\n", out);

    // Meta setup retry: the record already exists and must not be rewritten from blanked values.
    const char *meta = "screensaver_components=com.meta/.Dream\nscreensaver_default_component=com.meta/.Dream\ncontrolcenter_overlay=allow\n";
    pa_reset_records();
    pa_write_fixture(PA_META_RECORD, meta, 0644);
    sysexec_stub_reset();
    pa_stub_identity("29\n");
    sysexec_stub_add_popen("getprop ro.product.device", "omni\n", 0);
    sysexec_stub_add_popen("settings get secure screensaver_components", "\n", 0);
    sysexec_stub_add_popen("settings get secure screensaver_default_component", "\n", 0);
    sysexec_stub_add_popen("pm path com.facebook.alohaapps.controlcenter", "package:/system/app/cc.apk\n", 0);
    sysexec_stub_add_popen("appops get com.facebook.alohaapps.controlcenter SYSTEM_ALERT_WINDOW", "SYSTEM_ALERT_WINDOW: deny\n", 0);
    sysexec_stub_add_popen_times("settings get global package_verifier_enable", "1\n", 0, 2);
    sysexec_stub_add_popen("settings get global package_verifier_enable", "0\n", 0);
    dispatch_reply_as(HELPER_CALLER_SUCCESSOR, "PA_VENDOR setup", out, sizeof out);
    CHECK(pa_file_is(PA_META_RECORD, meta), "a Meta setup retry leaves the existing record unchanged\n");
    const char *const read_components[] = { "settings", "get", "secure", "screensaver_components", NULL };
    CHECK(captured_argv("/system/bin/settings", read_components) == 2,
          "a Meta setup retry that finds its record does not record again\n");
    CHECK(pa_file_is(PA_VERIFIER_RECORD, "package_verifier_enable=1\n"), "the verifier record is written once\n");
    const char *const verifier_off[] = { "settings", "put", "global", "package_verifier_enable", "0", NULL };
    CHECK(ran_argv("/system/bin/settings", verifier_off) == 1, "Meta setup switches the verifier off\n");
    const char *const blank[] = { "settings", "put", "secure", "screensaver_components", "", NULL };
    CHECK(ran_argv("/system/bin/settings", blank) == 0, "an already blank dream is not written\n");
    CHECK(pa_field_is(out, "device", "meta") && pa_field_is(out, "converged", "1") &&
          pa_field_is(out, "record.screensaver_components", "com.meta/.Dream"),
          "Meta setup reports convergence and the record (got '%s')\n", out);

    // A stale .part from an interrupted setup is replaced, and the record is published.
    pa_reset_records();
    pa_write_fixture(PA_SHELLY_RECORD ".part", "stargate_overlay=deny\n", 0644);
    sysexec_stub_reset();
    pa_stub_identity("29\n");
    sysexec_stub_add_popen("getprop ro.product.device", "wall_display\n", 0);
    sysexec_stub_add_popen("pm path cloud.shelly.stargate", "package:/system/app/stargate.apk\n", 0);
    sysexec_stub_add_popen_times("appops get cloud.shelly.stargate SYSTEM_ALERT_WINDOW", "SYSTEM_ALERT_WINDOW: allow\n", 0, 2);
    sysexec_stub_add_popen("appops get cloud.shelly.stargate SYSTEM_ALERT_WINDOW", "SYSTEM_ALERT_WINDOW: deny\n", 0);
    dispatch_reply_as(HELPER_CALLER_SUCCESSOR, "PA_VENDOR setup", out, sizeof out);
    CHECK(pa_file_is(PA_SHELLY_RECORD, "stargate_overlay=allow\n"), "a stale .part is replaced by a fresh record\n");
    CHECK(access(PA_SHELLY_RECORD ".part", F_OK) != 0, "the .part is gone after publishing\n");
    const char *const shelly_deny[] = { "appops", "set", "cloud.shelly.stargate", "SYSTEM_ALERT_WINDOW", "deny", NULL };
    CHECK(ran_argv("/system/bin/appops", shelly_deny) == 1, "Shelly setup denies the launcher overlay\n");
    CHECK(pa_field_is(out, "device", "shelly") && pa_field_is(out, "converged", "1"),
          "Shelly setup reports convergence (got '%s')\n", out);

    // A Meta record PA_RESTORE would refuse is no baseline: setup keeps it and changes nothing.
    const char *bad_meta = "screensaver_components=not-a-component\nscreensaver_default_component=null\n"
                           "controlcenter_overlay=allow\n";
    pa_reset_records();
    pa_write_fixture(PA_META_RECORD, bad_meta, 0644);
    sysexec_stub_reset();
    pa_stub_identity("29\n");
    sysexec_stub_add_popen("getprop ro.product.device", "omni\n", 0);
    sysexec_stub_add_popen("settings get secure screensaver_components", "com.meta/.Dream\n", 0);
    sysexec_stub_add_popen("settings get secure screensaver_default_component", "com.meta/.Dream\n", 0);
    sysexec_stub_add_popen("pm path com.facebook.alohaapps.controlcenter", "package:/system/app/cc.apk\n", 0);
    sysexec_stub_add_popen("appops get com.facebook.alohaapps.controlcenter SYSTEM_ALERT_WINDOW", "SYSTEM_ALERT_WINDOW: allow\n", 0);
    sysexec_stub_add_popen("settings get global package_verifier_enable", "1\n", 0);
    dispatch_reply_as(HELPER_CALLER_SUCCESSOR, "PA_VENDOR setup", out, sizeof out);
    CHECK(pa_file_is(PA_META_RECORD, bad_meta), "setup keeps a Meta record it cannot restore\n");
    CHECK(pa_ran("settings", "put") == 0 && pa_ran("appops", "set") == 0,
          "setup with an unrestorable Meta record writes no setting or app-op\n");
    CHECK(pa_field_is(out, "device", "meta") && pa_field_is(out, "converged", "0") &&
          pa_field_is(out, "record", "invalid"),
          "setup reports the unrestorable Meta record (got '%s')\n", out);

    // The same for a Shelly record PA_RESTORE would refuse.
    pa_reset_records();
    pa_write_fixture(PA_SHELLY_RECORD, "stargate_overlay=bogus\n", 0644);
    sysexec_stub_reset();
    pa_stub_identity("29\n");
    sysexec_stub_add_popen("getprop ro.product.device", "wall_display\n", 0);
    sysexec_stub_add_popen("pm path cloud.shelly.stargate", "package:/system/app/stargate.apk\n", 0);
    sysexec_stub_add_popen("appops get cloud.shelly.stargate SYSTEM_ALERT_WINDOW", "SYSTEM_ALERT_WINDOW: allow\n", 0);
    dispatch_reply_as(HELPER_CALLER_SUCCESSOR, "PA_VENDOR setup", out, sizeof out);
    CHECK(pa_file_is(PA_SHELLY_RECORD, "stargate_overlay=bogus\n"), "setup keeps a Shelly record it cannot restore\n");
    CHECK(pa_ran("appops", "set") == 0, "setup with an unrestorable Shelly record sets no app-op\n");
    CHECK(pa_field_is(out, "converged", "0") && pa_field_is(out, "record", "invalid"),
          "setup reports the unrestorable Shelly record (got '%s')\n", out);

    // A valid existing record still actuates.
    char rv[64];
    pa_reset_records();
    pa_write_fixture(PA_SHELLY_RECORD, "stargate_overlay=allow\n", 0644);
    sysexec_stub_reset();
    pa_stub_identity("29\n");
    sysexec_stub_add_popen("getprop ro.product.device", "wall_display\n", 0);
    sysexec_stub_add_popen("pm path cloud.shelly.stargate", "package:/system/app/stargate.apk\n", 0);
    sysexec_stub_add_popen_times("appops get cloud.shelly.stargate SYSTEM_ALERT_WINDOW", "SYSTEM_ALERT_WINDOW: allow\n", 0, 1);
    sysexec_stub_add_popen("appops get cloud.shelly.stargate SYSTEM_ALERT_WINDOW", "SYSTEM_ALERT_WINDOW: deny\n", 0);
    dispatch_reply_as(HELPER_CALLER_SUCCESSOR, "PA_VENDOR setup", out, sizeof out);
    CHECK(ran_argv("/system/bin/appops", shelly_deny) == 1 && pa_field_is(out, "converged", "1") &&
          !pa_field(out, "record", rv, sizeof rv),
          "setup with a valid existing Shelly record still denies the overlay (got '%s')\n", out);
    pa_reset_records();
}

static void test_pa_handback_admin_observe(void) {
    char out[8192];
    // A HOME target pm path cannot find is refused by the one HOME validator.
    sysexec_stub_reset();
    sysexec_stub_add_popen("pm path com.example.gone", "", 1);
    dispatch_reply_as(HELPER_CALLER_SUCCESSOR, "PA_HANDBACK component com.example.gone/.Home", out, sizeof out);
    CHECK(strcmp(out, "ERR home-unsafe\n") == 0, "PA_HANDBACK refuses a target that is not installed (got '%s')\n", out);
    // Critical packages are never handed HOME, even when installed.
    sysexec_stub_reset();
    sysexec_stub_add_popen("pm path", "package:/system/priv-app/x.apk\n", 0);
    dispatch_reply_as(HELPER_CALLER_SUCCESSOR, "PA_HANDBACK component com.android.systemui/.Home", out, sizeof out);
    CHECK(strcmp(out, "ERR home-unsafe\n") == 0, "PA_HANDBACK refuses a critical package (got '%s')\n", out);
    const char *const unsafe[] = {
        "PA_HANDBACK component android/.Resolver", "PA_HANDBACK component com.android.settings/.FallbackHome",
        "PA_HANDBACK component io.panelassistant.android/.DashboardActivity",
        "PA_HANDBACK component io.github.maxlyth.hapaneld/.MainActivity",
    };
    for (size_t i = 0; i < sizeof unsafe / sizeof unsafe[0]; i++) {
        sysexec_stub_reset();
        sysexec_stub_add_popen("pm path", "package:/system/priv-app/x.apk\n", 0);
        dispatch_reply_as(HELPER_CALLER_SUCCESSOR, unsafe[i], out, sizeof out);
        CHECK(strcmp(out, "ERR home-unsafe\n") == 0, "'%s' is refused as home-unsafe (got '%s')\n", unsafe[i], out);
    }

    sysexec_stub_reset();
    pa_stub_identity("29\n");
    sysexec_stub_add_popen("pm path com.example.home", "package:/data/app/home.apk\n", 0);
    sysexec_stub_add_popen(PA_HOME_QUERY, "com.example.home/.Home\n", 0);
    sysexec_stub_add_popen("pm list packages io.github.maxlyth.hapaneld", "", 0);
    sysexec_stub_add_popen("pm list packages " SUCCESSOR_ID, "package:" SUCCESSOR_ID "\n", 0);
    sysexec_stub_add_popen("pm list packages cloud.shelly.stargate", "Error: service failed\n", 0);
    sysexec_stub_add_popen("getprop ro.build.display.id", "4.0.12\n", 0);
    dispatch_reply_as(HELPER_CALLER_SUCCESSOR, "PA_HANDBACK component com.example.home/.Home", out, sizeof out);
    const char *const handback[] = { "cmd", "package", "set-home-activity", "com.example.home/.Home", NULL };
    CHECK(captured_argv("/system/bin/cmd", handback) == 1, "PA_HANDBACK sets the exact validated component\n");
    CHECK(pa_field_is(out, "handed", "1") && pa_field_is(out, "home", "com.example.home/com.example.home.Home"),
          "PA_HANDBACK expands the short readback and reports the hand-back (got '%s')\n", out);
    CHECK(pa_field_is(out, "legacy", "absent") && pa_field_is(out, "new", "installed") &&
          pa_field_is(out, "shelly", "failed") && pa_field_is(out, "firmware", "4.0.12"),
          "PA_HANDBACK reports the move observation\n");

    sysexec_stub_reset();
    sysexec_stub_add_popen("pm path cloud.shelly.stargate", "package:/system/app/stargate.apk\n", 0);
    sysexec_stub_add_popen(PA_HOME_QUERY, "cloud.shelly.stargate/.activities.SplashActivity\n", 0);
    dispatch_reply_as(HELPER_CALLER_SUCCESSOR, "PA_HANDBACK vendor", out, sizeof out);
    const char *const vendor_home[] = { "cmd", "package", "set-home-activity", "cloud.shelly.stargate/.activities.SplashActivity", NULL };
    CHECK(captured_argv("/system/bin/cmd", vendor_home) == 1 && pa_field_is(out, "handed", "1"),
          "PA_HANDBACK vendor hands HOME to the fixed Shelly splash (got '%s')\n", out);

    const char *const admin[] = {
        "dpm", "set-active-admin", SUCCESSOR_ID "/io.panelassistant.android.platform.PanelDeviceAdminReceiver", NULL
    };
    sysexec_stub_reset();
    sysexec_stub_add_popen_times("dumpsys device_policy", "Enabled Device Admins (User 0, provisioningState: 0):\n", 0, 1);
    sysexec_stub_add_popen("dumpsys device_policy", "  " SUCCESSOR_ID "/.platform.PanelDeviceAdminReceiver:\n", 0);
    dispatch_reply_as(HELPER_CALLER_SUCCESSOR, "PA_ADMIN", out, sizeof out);
    CHECK(captured_argv("/system/bin/dpm", admin) == 1 && pa_field_is(out, "admin", "active"),
          "PA_ADMIN claims a missing device admin and reads it back (got '%s')\n", out);
    sysexec_stub_reset();
    sysexec_stub_add_popen("dumpsys device_policy", "  " SUCCESSOR_ID "/.platform.PanelDeviceAdminReceiver:\n", 0);
    dispatch_reply_as(HELPER_CALLER_SUCCESSOR, "PA_ADMIN", out, sizeof out);
    CHECK(captured_argv("/system/bin/dpm", admin) == 0, "PA_ADMIN leaves an admin Android already lists\n");

    sysexec_stub_reset();
    sysexec_stub_add_popen("pidof cloud.shelly.stargate", "812\n", 0);
    sysexec_stub_add_popen("dumpsys package " SUCCESSOR_ID, "    User 0: ceDataInode=1 installed=true hidden=false suspended=false stopped=false\n", 0);
    sysexec_stub_add_popen(PA_HOME_QUERY, "cloud.shelly.stargate/.activities.SplashActivity\n", 0);
    dispatch_reply_as(HELPER_CALLER_SUCCESSOR, "PA_OBSERVE recovery", out, sizeof out);
    char boot[64], uptime[64];
    CHECK(pa_field(out, "boot_id", boot, sizeof boot) && strlen(boot) == 36 &&
          pa_field(out, "uptime", uptime, sizeof uptime) && strchr(uptime, '.') != NULL,
          "PA_OBSERVE recovery reports boot_id and uptime (got '%s')\n", out);
    CHECK(pa_field_is(out, "vendor_pid", "812") && pa_field_is(out, "stopped", "false"),
          "PA_OBSERVE recovery reports the vendor pid and stopped state\n");
}

// Review gaps: deadline and overflow refusals, launch 0, the policy restore, OBSERVE move and its
// recovery failure, VENDOR on another device, HANDBACK vendor without Shelly, and planted records.
static int pa_ran(const char *a, const char *b) {
    const char *const prefix[] = { a, b, strcmp(a, "cmd") == 0 ? "set-home-activity" : NULL, NULL };
    return sysexec_stub_count_argv_prefix(prefix);
}

static void test_pa_review_gaps(void) {
    char out[8192];

    // A child that times out spends the budget: ERR deadline, and no later write runs.
    sysexec_stub_reset();
    pa_stub_repair_base("33\n");
    sysexec_stub_add_popen("settings get secure enabled_accessibility_services", PA_ACCESSIBILITY_OLD "\n", 0);
    sysexec_stub_add_popen("settings get secure accessibility_enabled", "0\n", 0);
    sysexec_stub_add_popen("appops get " SUCCESSOR_ID " WRITE_SETTINGS", "WRITE_SETTINGS: deny\n", 0);
    sysexec_stub_timeout_run("settings get secure enabled_accessibility_services");
    dispatch_reply_as(HELPER_CALLER_SUCCESSOR, "PA_REPAIR 1 0", out, sizeof out);
    CHECK(strcmp(out, "ERR deadline\n") == 0, "PA_REPAIR past its budget answers ERR deadline (got '%s')\n", out);
    CHECK(pa_ran("settings", "put") == 0 && pa_ran("appops", "set") == 0 &&
          pa_ran("pm", "grant") == 0, "PA_REPAIR runs no write after its budget is spent\n");

    // launch 0 claims the older dashboard class.
    const char *const set_home0[] = {
        "cmd", "package", "set-home-activity", "io.panelassistant.android/io.github.maxlyth.hapaneld.DashboardActivity", NULL
    };
    sysexec_stub_reset();
    pa_stub_identity("29\n");
    sysexec_stub_add_popen("--es operation dashboard_home_policy",
        "Broadcast completed: result=1, data=\"panel-assistant-home-builtin\"\n", 0);
    sysexec_stub_add_popen("--es operation setup_eligibility",
        "Broadcast completed: result=1, data=\"panel-assistant-setup-eligible\"\n", 0);
    sysexec_stub_add_popen_times(PA_HOME_QUERY, "com.example.home/.Home\n", 0, 1);
    sysexec_stub_add_popen(PA_HOME_QUERY, "io.panelassistant.android/io.github.maxlyth.hapaneld.DashboardActivity\n", 0);
    dispatch_reply_as(HELPER_CALLER_SUCCESSOR, "PA_HOME 0 repair", out, sizeof out);
    CHECK(captured_argv("/system/bin/cmd", set_home0) == 1 && pa_field_is(out, "chooser", "set"),
          "PA_HOME 0 sets and reads back the launch-0 dashboard (got '%s')\n", out);

    // The policy names a previous HOME: it is put back under the lock and acknowledged on readback.
    mkdir(PA_TEST_LOCK, 0755);
    const char *const set_previous[] = { "cmd", "package", "set-home-activity", "com.example.home/.Home", NULL };
    const char *const ack_retained[] = {
        "am", "broadcast", "--include-stopped-packages", "-n", PA_RECEIVER, "--es", "operation", "acknowledge_retained_home",
        "--es", "previous_home_component", "com.example.home/.Home", NULL
    };
    for (int readback_ok = 1; readback_ok >= 0; readback_ok--) {
        sysexec_stub_reset();
        pa_stub_identity("29\n");
        sysexec_stub_add_popen("--es operation dashboard_home_policy",
            "Broadcast completed: result=3, data=\"panel-assistant-home-restore:com.example.home/.Home\"\n", 0);
        sysexec_stub_add_popen("pm path com.example.home", "package:/data/app/home.apk\n", 0);
        sysexec_stub_add_popen("--es operation restore_previous_home_shell_lock_eligibility",
            "Broadcast completed: result=1, data=\"panel-assistant-home-restore-shell-locked\"\n", 0);
        sysexec_stub_add_popen(PA_HOME_QUERY, readback_ok ? "com.example.home/.Home\n" : "com.other/.Launcher\n", 0);
        dispatch_reply_as(HELPER_CALLER_SUCCESSOR, "PA_HOME 1 repair", out, sizeof out);
        CHECK(captured_argv("/system/bin/cmd", set_previous) == 1, "PA_HOME restores the previous HOME (got '%s')\n", out);
        CHECK(pa_field_is(out, "policy", "restore") &&
              pa_field_is(out, "chooser", readback_ok ? "retained" : "deferred") &&
              (captured_argv("/system/bin/am", ack_retained) == readback_ok),
              "PA_HOME acknowledges a restore only when it reads back (ok=%d, got '%s')\n", readback_ok, out);
    }

    // OBSERVE move reports each package, HOME and firmware.
    sysexec_stub_reset();
    pa_stub_identity("29\n");
    sysexec_stub_add_popen("pm list packages io.github.maxlyth.hapaneld", "package:io.github.maxlyth.hapaneld\n", 0);
    sysexec_stub_add_popen("pm list packages " SUCCESSOR_ID, "package:" SUCCESSOR_ID "\n", 0);
    sysexec_stub_add_popen("pm list packages cloud.shelly.stargate", "package:cloud.shelly.stargate\n", 0);
    sysexec_stub_add_popen(PA_HOME_QUERY, "x\ncloud.shelly.stargate/.activities.SplashActivity\n", 0);
    sysexec_stub_add_popen("getprop ro.build.display.id", "1.2.3 beta\n", 0);
    dispatch_reply_as(HELPER_CALLER_SUCCESSOR, "PA_OBSERVE move", out, sizeof out);
    CHECK(pa_field_is(out, "legacy", "installed") && pa_field_is(out, "new", "installed") &&
          pa_field_is(out, "shelly", "installed") && pa_field_is(out, "firmware", "1.2.3 beta") &&
          pa_field_is(out, "home", "cloud.shelly.stargate/.activities.SplashActivity"),
          "PA_OBSERVE move reports packages, HOME and firmware (got '%s')\n", out);

    // OBSERVE recovery without a boot id refuses rather than reporting a partial answer.
    pa_test_boot_id_path = "/nonexistent/boot_id";
    sysexec_stub_reset();
    dispatch_reply_as(HELPER_CALLER_SUCCESSOR, "PA_OBSERVE recovery", out, sizeof out);
    pa_test_boot_id_path = "/proc/sys/kernel/random/boot_id";
    CHECK(strcmp(out, "ERR failed\n") == 0, "PA_OBSERVE recovery without a boot id answers ERR failed (got '%s')\n", out);

    // VENDOR setup on a device that is neither Meta nor Shelly writes nothing.
    pa_reset_records();
    sysexec_stub_reset();
    pa_stub_identity("29\n");
    sysexec_stub_add_popen("getprop ro.product.device", "nspanel\n", 0);
    dispatch_reply_as(HELPER_CALLER_SUCCESSOR, "PA_VENDOR setup", out, sizeof out);
    CHECK(pa_field_is(out, "device", "other") && pa_field_is(out, "converged", "1"),
          "PA_VENDOR setup on another device reports device=other (got '%s')\n", out);
    CHECK(pa_ran("settings", "put") == 0 && pa_ran("appops", "set") == 0 &&
          access(PA_META_RECORD, F_OK) != 0 && access(PA_SHELLY_RECORD, F_OK) != 0 && access(PA_VERIFIER_RECORD, F_OK) != 0,
          "PA_VENDOR setup on another device writes no setting, op or record\n");

    // HANDBACK vendor without the Shelly launcher refuses instead of naming a missing HOME.
    sysexec_stub_reset();
    sysexec_stub_add_popen(PA_HOME_QUERY, "com.other/.Launcher\n", 0);
    dispatch_reply_as(HELPER_CALLER_SUCCESSOR, "PA_HANDBACK vendor", out, sizeof out);
    CHECK(strcmp(out, "ERR home-unsafe\n") == 0 && pa_ran("cmd", "package") == 0,
          "PA_HANDBACK vendor without Shelly answers home-unsafe and sets nothing (got '%s')\n", out);

    // A planted Meta or verifier record whose values are not the expected shape is never written back.
    const char *const planted[] = {
        "screensaver_components=com.meta/.Dream;reboot\nscreensaver_default_component=null\ncontrolcenter_overlay=allow\n",
        "screensaver_components=com.meta/.Dream\nscreensaver_default_component=--user 0\ncontrolcenter_overlay=allow\n",
    };
    for (size_t i = 0; i < sizeof planted / sizeof planted[0]; i++) {
        pa_reset_records();
        pa_write_fixture(PA_META_RECORD, planted[i], 0644);
        sysexec_stub_reset();
        dispatch_reply_as(HELPER_CALLER_SUCCESSOR, "PA_RESTORE vendor", out, sizeof out);
        CHECK(pa_field_is(out, "result", "unrestored") && pa_ran("settings", "put") == 0 &&
              pa_ran("settings", "delete") == 0 && access(PA_META_RECORD, F_OK) == 0,
              "PA_RESTORE refuses a malformed Meta record %zu and keeps it (got '%s')\n", i, out);
    }
    pa_reset_records();
    pa_write_fixture(PA_VERIFIER_RECORD, "package_verifier_enable=7\n", 0644);
    sysexec_stub_reset();
    dispatch_reply_as(HELPER_CALLER_SUCCESSOR, "PA_RESTORE vendor", out, sizeof out);
    CHECK(pa_field_is(out, "result", "unrestored") && pa_ran("settings", "put") == 0,
          "PA_RESTORE refuses a verifier value other than 0, 1 or null (got '%s')\n", out);
    // An empty or partial record is no baseline: setup never disables the verifier or blanks Meta's
    // screensaver on it, and restore keeps it and reports unrestored.
    const char *const off[] = { "settings", "put", "global", "package_verifier_enable", "0", NULL };
    const char *const thin[][2] = {
        { PA_VERIFIER_RECORD, "" },
        { PA_VERIFIER_RECORD, "package_verifier_enable=\n" },
        { PA_META_RECORD, "screensaver_components=com.meta/.Dream\n" },
    };
    for (size_t i = 0; i < sizeof thin / sizeof thin[0]; i++) {
        pa_reset_records();
        pa_write_fixture(thin[i][0], thin[i][1], 0644);
        sysexec_stub_reset();
        pa_stub_identity("29\n");
        sysexec_stub_add_popen("getprop ro.product.device", "omni\n", 0);
        dispatch_reply_as(HELPER_CALLER_SUCCESSOR, "PA_VENDOR setup", out, sizeof out);
        int disabled = ran_argv("/system/bin/settings", off);
        int blanked = pa_ran("settings", "put") - disabled;
        CHECK((i < 2 ? disabled : blanked) == 0 && access(thin[i][0], F_OK) == 0,
              "PA_VENDOR setup never acts on thin record %zu (got '%s')\n", i, out);
        sysexec_stub_reset();
        pa_stub_identity("29\n");
        dispatch_reply_as(HELPER_CALLER_SUCCESSOR, "PA_RESTORE vendor", out, sizeof out);
        CHECK(pa_field_is(out, "result", "unrestored") && pa_ran("settings", "put") == 0 &&
              access(thin[i][0], F_OK) == 0,
              "PA_RESTORE keeps thin record %zu and reports unrestored (got '%s')\n", i, out);
    }
    // A well-formed two-component list still restores.
    pa_reset_records();
    pa_write_fixture(PA_META_RECORD,
        "screensaver_components=com.meta/.Dream,com.meta.b/.Frame\nscreensaver_default_component=null\ncontrolcenter_overlay=absent\n", 0644);
    sysexec_stub_reset();
    sysexec_stub_add_popen("settings get secure screensaver_components", "com.meta/.Dream,com.meta.b/.Frame\n", 0);
    sysexec_stub_add_popen("settings get secure screensaver_default_component", "null\n", 0);
    dispatch_reply_as(HELPER_CALLER_SUCCESSOR, "PA_RESTORE vendor", out, sizeof out);
    const char *const put_list[] = { "settings", "put", "secure", "screensaver_components", "com.meta/.Dream,com.meta.b/.Frame", NULL };
    CHECK(ran_argv("/system/bin/settings", put_list) == 1,
          "PA_RESTORE writes back a well-formed component list (got '%s')\n", out);
    pa_reset_records();
}

static void test_pa_commands(void) {
    test_pa_admission_and_grammar();
    test_pa_identity();
    test_pa_repair();
    test_pa_repair_keeps_owner_choices();
    test_pa_home_chooser();
    test_pa_home_lock();
    test_pa_records();
    test_pa_handback_admin_observe();
    test_pa_review_gaps();
}
