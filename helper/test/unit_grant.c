// GRANT owner-decision cases for the helper unit binary, included by unit.c after the typed Panel
// Assistant cases, whose stub, dispatch and argv helpers are in scope; not a translation unit of its own.
//
// The app sends GRANT at every service start for each grant Android reports missing, so these cases
// pin that a permission or app op the owner (or a device policy) decided survives it: the reply is
// ERR and no write reaches Android, while an undecided denial or an unset op is still repaired. Every
// "no write" check is paired with a count of the read that decided it, so it cannot pass by never
// reaching the rule.

static const char *const GRANT_ANY_PM_GRANT[] = { "pm", "grant", NULL };
static const char *const GRANT_ANY_APPOPS_SET[] = { "appops", "set", NULL };

static const struct { const char *capability; const char *permission; } GRANT_RUNTIME[] = {
    { "MICROPHONE", "android.permission.RECORD_AUDIO" },
    { "CAMERA", "android.permission.CAMERA" },
    { "NOTIFICATIONS", "android.permission.POST_NOTIFICATIONS" },
};

static const struct { const char *capability; const char *op; } GRANT_OPS[] = {
    { "WRITESETTINGS", "WRITE_SETTINGS" },
    { "OVERLAY", "SYSTEM_ALERT_WINDOW" },
};

// `dumpsys package` answers [first] to the first read and [after] to every later one (the readback).
static void grant_stub_dump(const char *first, const char *after) {
    sysexec_stub_add_popen_times("dumpsys package " SUCCESSOR_ID, first, 0, 1);
    sysexec_stub_add_popen("dumpsys package " SUCCESSOR_ID, after, 0);
}

static int grant_dump_reads(void) {
    const char *const dump[] = { "dumpsys", "package", SUCCESSOR_ID, NULL };
    return captured_argv("/system/bin/dumpsys", dump);
}

static void grant_request(const char *capability, char *out, size_t size) {
    char request[96];
    snprintf(request, sizeof request, "GRANT " SUCCESSOR_ID " %s", capability);
    dispatch_reply_as(HELPER_CALLER_SUCCESSOR, request, out, size);
}

static void test_grant_keeps_owner_permissions(void) {
    char out[MAX_LINE + 2];
    // A denial the owner or a policy chose, alone or beside Android's own sensitivity flags.
    static const struct { const char *flags; const char *why; } decided[] = {
        { "[ USER_SET|USER_SENSITIVE_WHEN_GRANTED|USER_SENSITIVE_WHEN_DENIED]", "the owner's Deny in Settings" },
        { "[ USER_SET ]", "USER_SET alone" },
        { "[ USER_FIXED ]", "the owner's Don't ask again" },
        { "[ POLICY_FIXED ]", "a device policy" },
        { "[ USER_SENSITIVE_WHEN_GRANTED|USER_FIXED]", "USER_FIXED after a sensitivity flag" },
        { "[ USER_SENSITIVE_WHEN_DENIED|POLICY_FIXED|USER_SENSITIVE_WHEN_GRANTED]", "POLICY_FIXED between sensitivity flags" },
    };
    for (size_t p = 0; p < sizeof GRANT_RUNTIME / sizeof GRANT_RUNTIME[0]; p++) {
        for (size_t i = 0; i < sizeof decided / sizeof decided[0]; i++) {
            char dump[512];
            snprintf(dump, sizeof dump, "        %s: granted=false, flags=%s\n", GRANT_RUNTIME[p].permission, decided[i].flags);
            sysexec_stub_reset();
            grant_stub_dump(dump, dump);
            grant_request(GRANT_RUNTIME[p].capability, out, sizeof out);
            CHECK(strcmp(out, "ERR\n") == 0, "GRANT %s answers ERR for a denial flagged by %s (got '%s')\n",
                  GRANT_RUNTIME[p].capability, decided[i].why, out);
            CHECK(grant_dump_reads() >= 1 && sysexec_stub_count_argv_prefix(GRANT_ANY_PM_GRANT) == 0,
                  "GRANT %s reads the dump and keeps a denial flagged by %s\n", GRANT_RUNTIME[p].capability, decided[i].why);
        }
    }

    // Two users' lines: one decided denial is enough to keep the permission off.
    sysexec_stub_reset();
    grant_stub_dump(
        "        android.permission.RECORD_AUDIO: granted=false, flags=[ USER_SENSITIVE_WHEN_GRANTED|USER_SENSITIVE_WHEN_DENIED]\n"
        "        android.permission.RECORD_AUDIO: granted=false, flags=[ USER_SET|USER_SENSITIVE_WHEN_GRANTED|USER_SENSITIVE_WHEN_DENIED]\n",
        "        android.permission.RECORD_AUDIO: granted=true\n");
    grant_request("MICROPHONE", out, sizeof out);
    CHECK(strcmp(out, "ERR\n") == 0 && grant_dump_reads() >= 1 && sysexec_stub_count_argv_prefix(GRANT_ANY_PM_GRANT) == 0,
          "GRANT MICROPHONE keeps a denial any user's line records as decided (got '%s')\n", out);

    // Undecided: Android's own sensitivity flags, no flags, or a flag merely spelled like a decision.
    static const struct { const char *flags; const char *why; } undecided[] = {
        { ", flags=[ USER_SENSITIVE_WHEN_GRANTED|USER_SENSITIVE_WHEN_DENIED]", "only sensitivity flags" },
        { "", "no flags" },
        { ", flags=[ USER_SETTLED|POLICY_FIXED_X ]", "near-miss flag names" },
    };
    for (size_t p = 0; p < sizeof GRANT_RUNTIME / sizeof GRANT_RUNTIME[0]; p++) {
        for (size_t i = 0; i < sizeof undecided / sizeof undecided[0]; i++) {
            char before[512], after[512];
            const char *const grant[] = { "pm", "grant", SUCCESSOR_ID, GRANT_RUNTIME[p].permission, NULL };
            snprintf(before, sizeof before, "        %s: granted=false%s\n", GRANT_RUNTIME[p].permission, undecided[i].flags);
            snprintf(after, sizeof after, "        %s: granted=true%s\n", GRANT_RUNTIME[p].permission, undecided[i].flags);
            sysexec_stub_reset();
            grant_stub_dump(before, after);
            grant_request(GRANT_RUNTIME[p].capability, out, sizeof out);
            CHECK(ran_argv("/system/bin/pm", grant) == 1 && sysexec_stub_count_argv_prefix(GRANT_ANY_PM_GRANT) == 1,
                  "GRANT %s grants a denial with %s exactly once\n", GRANT_RUNTIME[p].capability, undecided[i].why);
            CHECK(strcmp(out, "OK\n") == 0, "GRANT %s answers OK once Android reads the grant back (%s, got '%s')\n",
                  GRANT_RUNTIME[p].capability, undecided[i].why, out);
        }
    }

    // A grant Android does not read back is not reported as done.
    sysexec_stub_reset();
    grant_stub_dump("        android.permission.CAMERA: granted=false\n", "        android.permission.CAMERA: granted=false\n");
    grant_request("CAMERA", out, sizeof out);
    CHECK(strcmp(out, "ERR\n") == 0 && sysexec_stub_count_argv_prefix(GRANT_ANY_PM_GRANT) == 1,
          "GRANT CAMERA answers ERR when the grant does not read back (got '%s')\n", out);

    // Already granted: nothing to write.
    sysexec_stub_reset();
    grant_stub_dump("        android.permission.CAMERA: granted=true\n", "        android.permission.CAMERA: granted=true\n");
    grant_request("CAMERA", out, sizeof out);
    CHECK(strcmp(out, "OK\n") == 0 && grant_dump_reads() >= 1 && sysexec_stub_count_argv_prefix(GRANT_ANY_PM_GRANT) == 0,
          "GRANT CAMERA writes nothing for a permission already granted (got '%s')\n", out);

    // Unreadable is never a reason to grant: a failed dump, one that exits nonzero, one with no line
    // for the permission, and one where users disagree.
    static const struct { const char *dump; int status; const char *why; } unreadable[] = {
        { NULL, 0, "no dump at all" },
        { "        android.permission.CAMERA: granted=false\n", 1, "a dump that exits nonzero" },
        { "    requested permissions:\n      android.permission.CAMERA\n", 0, "a dump with no grant line" },
        { "        android.permission.CAMERA: granted=false\n        android.permission.CAMERA: granted=true\n", 0, "users that disagree" },
        { "        android.permission.CAMERA: granted=false, flags=[ USER_SENSITIVE_WHEN_GRANTED", 0, "a truncated line" },
    };
    for (size_t i = 0; i < sizeof unreadable / sizeof unreadable[0]; i++) {
        sysexec_stub_reset();
        if (unreadable[i].dump) sysexec_stub_add_popen("dumpsys package " SUCCESSOR_ID, unreadable[i].dump, unreadable[i].status);
        grant_request("CAMERA", out, sizeof out);
        CHECK(strcmp(out, "ERR\n") == 0 && grant_dump_reads() >= 1 && sysexec_stub_count_argv_prefix(GRANT_ANY_PM_GRANT) == 0,
              "GRANT CAMERA grants nothing on %s (got '%s')\n", unreadable[i].why, out);
    }
}

static void test_grant_keeps_owner_app_ops(void) {
    char out[MAX_LINE + 2];
    for (size_t o = 0; o < sizeof GRANT_OPS / sizeof GRANT_OPS[0]; o++) {
        const char *op = GRANT_OPS[o].op;
        const char *const get[] = { "appops", "get", SUCCESSOR_ID, op, NULL };
        const char *const allow[] = { "appops", "set", SUCCESSOR_ID, op, "allow", NULL };
        char needle[96], answer[160];
        snprintf(needle, sizeof needle, "appops get " SUCCESSOR_ID " %s", op);

        // An explicit mode is someone's choice, the owner's deny above all: kept, answered ERR.
        static const char *const kept[] = { "deny; rejectTime=+3m ago", "ignore", "errored", "foreground" };
        for (size_t i = 0; i < sizeof kept / sizeof kept[0]; i++) {
            snprintf(answer, sizeof answer, "%s: %s\n", op, kept[i]);
            sysexec_stub_reset();
            sysexec_stub_add_popen(needle, answer, 0);
            grant_request(GRANT_OPS[o].capability, out, sizeof out);
            CHECK(strcmp(out, "ERR\n") == 0, "GRANT %s answers ERR for an op set to %s (got '%s')\n",
                  GRANT_OPS[o].capability, kept[i], out);
            CHECK(captured_argv("/system/bin/appops", get) == 1 && sysexec_stub_count_argv_prefix(GRANT_ANY_APPOPS_SET) == 0,
                  "GRANT %s reads the op and keeps it at %s\n", GRANT_OPS[o].capability, kept[i]);
        }

        // Unset or default, including Android 14's two-line answer whatever default it names: allowed.
        static const char *const unset[] = {
            "%s: default\n", "No operations.\n", "No operations.\nDefault mode: default\n",
            "No operations.\nDefault mode: deny\n", "No operations.\nDefault mode: ignore\n",
        };
        for (size_t i = 0; i < sizeof unset / sizeof unset[0]; i++) {
            snprintf(answer, sizeof answer, unset[i], op);
            sysexec_stub_reset();
            sysexec_stub_add_popen(needle, answer, 0);
            grant_request(GRANT_OPS[o].capability, out, sizeof out);
            CHECK(strcmp(out, "OK\n") == 0 && ran_argv("/system/bin/appops", allow) == 1 &&
                  sysexec_stub_count_argv_prefix(GRANT_ANY_APPOPS_SET) == 1,
                  "GRANT %s allows an op nobody set ('%s', got '%s')\n", GRANT_OPS[o].capability, answer, out);
        }

        // Already allowed, explicitly or by an unset op's allowing default: nothing to write.
        static const char *const allowed[] = { "%s: allow\n", "%s: allow; time=+1d2h ago\n", "No operations.\nDefault mode: allow\n" };
        for (size_t i = 0; i < sizeof allowed / sizeof allowed[0]; i++) {
            snprintf(answer, sizeof answer, allowed[i], op);
            sysexec_stub_reset();
            sysexec_stub_add_popen(needle, answer, 0);
            grant_request(GRANT_OPS[o].capability, out, sizeof out);
            CHECK(strcmp(out, "OK\n") == 0 && captured_argv("/system/bin/appops", get) == 1 &&
                  sysexec_stub_count_argv_prefix(GRANT_ANY_APPOPS_SET) == 0,
                  "GRANT %s writes nothing for an op already allowed ('%s', got '%s')\n", GRANT_OPS[o].capability, answer, out);
        }

        // Unreadable is never a reason to allow: no answer, a failed read, or a mode it cannot name.
        static const struct { const char *answer; int status; const char *why; } unreadable[] = {
            { NULL, 0, "no answer" }, { "%s: default\n", 1, "a read that exits nonzero" },
            { "%s: maybe\n", 0, "an unknown mode" }, { "%s: default\n%s: deny\n", 0, "a default and a deny line" },
        };
        for (size_t i = 0; i < sizeof unreadable / sizeof unreadable[0]; i++) {
            sysexec_stub_reset();
            if (unreadable[i].answer) {
                snprintf(answer, sizeof answer, unreadable[i].answer, op, op);
                sysexec_stub_add_popen(needle, answer, unreadable[i].status);
            }
            grant_request(GRANT_OPS[o].capability, out, sizeof out);
            CHECK(strcmp(out, "ERR\n") == 0 && captured_argv("/system/bin/appops", get) == 1 &&
                  sysexec_stub_count_argv_prefix(GRANT_ANY_APPOPS_SET) == 0,
                  "GRANT %s allows nothing on %s (got '%s')\n", GRANT_OPS[o].capability, unreadable[i].why, out);
        }

        // A failed write on an unset op is reported, not assumed.
        snprintf(answer, sizeof answer, "%s: default\n", op);
        sysexec_stub_reset();
        sysexec_stub_add_popen(needle, answer, 0);
        sysexec_stub_fail_run("appops set " SUCCESSOR_ID, 256);
        grant_request(GRANT_OPS[o].capability, out, sizeof out);
        CHECK(strcmp(out, "ERR\n") == 0 && ran_argv("/system/bin/appops", allow) == 1,
              "GRANT %s reports a failed appops set (got '%s')\n", GRANT_OPS[o].capability, out);
    }
}
