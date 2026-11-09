// Typed Panel Assistant commands. See pa_commands.h for the contract and pa_allowlist.h for every
// value these handlers may put on a command line.
//
// Each handler reproduces one of Panel Assistant's shell programs step for step (the builder named
// above it), with two differences that are the point of the change: Android tools run as fixed argv
// with no shell, and panel output (settings values, dumpsys, appops, broadcast replies, the chooser
// window dump) is parsed here so only bounded words, integers or table entries come out of it.
#include "pa_commands.h"

#include "identity.h"
#include "pa_allowlist.h"
#include "sysexec.h"
#include "util.h"

#include <errno.h>
#include <fcntl.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/file.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <time.h>
#include <unistd.h>

// Records live where the shell installers write them; the HOME lock is the directory both installers
// and the app agree on; the chooser dump goes to a root-only compartment, never /data/local/tmp.
#ifdef HAPANELD_TEST
#define PA_RECORD_DIR "/tmp/.hapaneld-pa-test"
#define PA_HOME_LOCK_PATH "/tmp/.hapaneld-pa-test-lock"
#define PA_DUMP_PARENT "/tmp"
#define PA_DUMP_NAME ".hapaneld-pa-test-chooser"
const char *pa_test_boot_id_path = "/proc/sys/kernel/random/boot_id";
#define PA_BOOT_ID_PATH pa_test_boot_id_path
#else
#define PA_RECORD_DIR PA_RECORD_DIR_PANEL
#define PA_HOME_LOCK_PATH "/system"
#define PA_DUMP_PARENT "/data/local"
#define PA_DUMP_NAME ".hapaneld-pa-chooser"
#define PA_BOOT_ID_PATH "/proc/sys/kernel/random/boot_id"
#endif
#define PA_DUMP_FILE "chooser.xml"
#define PA_DUMP_PATH PA_DUMP_PARENT "/" PA_DUMP_NAME "/" PA_DUMP_FILE

#ifndef PA_FRAMEWORK_TIMEOUT_MS
#define PA_FRAMEWORK_TIMEOUT_MS 25000U
#endif
#ifndef PA_HOME_TIMEOUT_MS
#define PA_HOME_TIMEOUT_MS 60000U
#endif

#define PA_BODY_MAX 4096
#define PA_VALUE_MAX 255
#define PA_LINE 4352        // one settings value or short command answer (4096 plus slack)
#define PA_DUMP_MAX (512U * 1024U) // dumpsys package, pm list features, the chooser window
#define PA_RECORD_MAX 512
#define PA_SHELL_UID 2000

typedef struct {
    conn_ctx *ctx;
    const char *package;
    uint64_t deadline;
    int expired;
    int lock_fd;            // the held HOME lock, passed as stdin to every child while held
    int sdk;                // ro.build.version.sdk, or -1
    char body[PA_BODY_MAX + 1];
    size_t used;
    int overflow;
    char *dump;             // PA_DUMP_MAX scratch for long outputs
} pa_run;

static __thread pa_run *pa_current; // for valid_home_target's package callback

static uint64_t pa_now_ms(void) {
    struct timespec now;
    if (clock_gettime(CLOCK_MONOTONIC, &now) != 0) return 0;
    return (uint64_t)now.tv_sec * 1000U + (uint64_t)now.tv_nsec / 1000000U;
}

static unsigned pa_left(pa_run *r) {
    if (r->expired) return 0; // a child that timed out spent the budget, whatever the clock says
    uint64_t now = pa_now_ms();
    if (!now || now >= r->deadline) { r->expired = 1; return 0; }
    return (unsigned)(r->deadline - now);
}

static int pa_admit(conn_ctx *ctx) {
    if (ctx->caller != HELPER_CALLER_SUCCESSOR) {
        reply(ctx->fd, "ERR caller\n");
        return 0;
    }
    return 1;
}

// Splits the request into at most two tokens. 3 means an extra token, which every verb refuses.
static int pa_parse(const char *args, char first[160], char second[160]) {
    char extra[2];
    first[0] = second[0] = '\0';
    int n = sscanf(args, "%159s %159s %1s", first, second, extra);
    return n < 0 ? 0 : n;
}

static pa_run *pa_begin(conn_ctx *ctx, unsigned budget_ms) {
    const helper_app_package *app = helper_app_package_for(ctx->caller);
    pa_run *r = app ? calloc(1, sizeof *r) : NULL;
    if (r) r->dump = malloc(PA_DUMP_MAX);
    if (!r || !r->dump) {
        if (r) free(r);
        reply(ctx->fd, "ERR failed\n");
        return NULL;
    }
    r->ctx = ctx;
    r->package = app->package;
    r->deadline = pa_now_ms() + budget_ms;
    r->lock_fd = -1;
    r->sdk = -1;
    pa_current = r;
    return r;
}

static void pa_end(pa_run *r) {
    if (r->lock_fd >= 0) close(r->lock_fd);
    pa_current = NULL;
    free(r->dump);
    free(r);
}

static void pa_refuse(pa_run *r, const char *code) {
    char line[32];
    snprintf(line, sizeof line, "ERR %s\n", code);
    reply(r->ctx->fd, line);
    pa_end(r);
}

static void pa_finish(pa_run *r) {
    if (r->expired) { pa_refuse(r, "deadline"); return; }
    if (r->overflow) { pa_refuse(r, "failed"); return; }
    char header[32];
    snprintf(header, sizeof header, "OK %zu\n", r->used);
    if (reply(r->ctx->fd, header) == 0) (void)reply(r->ctx->fd, r->body);
    pa_end(r);
}

// Every value from the panel: printable ASCII without '=' or newline, at most 255 bytes, else unknown.
static void pa_kv(pa_run *r, const char *key, const char *value) {
    int clean = strlen(value) <= PA_VALUE_MAX;
    for (const char *p = value; clean && *p; p++)
        clean = *p >= 0x20 && *p <= 0x7e && *p != '=';
    if (!clean) value = "unknown";
    int n = snprintf(r->body + r->used, sizeof r->body - r->used, "%s=%s\n", key, value);
    if (n < 0 || (size_t)n >= sizeof r->body - r->used) { r->overflow = 1; return; }
    r->used += (size_t)n;
}

// --- execution -----------------------------------------------------------------------------------

static void pa_trim(char *s) {
    size_t n = strlen(s);
    while (n > 0 && (s[n - 1] == '\n' || s[n - 1] == '\r')) s[--n] = '\0';
}

static int pa_exec_capture(pa_run *r, const char *const argv[], char *out, size_t cap, int merge) {
    char path[64];
    snprintf(path, sizeof path, "/system/bin/%s", argv[0]);
    out[0] = '\0';
    unsigned left = pa_left(r);
    if (!left) return -1;
    int timed_out = 0;
    int status = sysexec_capture_argv_io(path, argv, out, cap, left, &timed_out, r->lock_fd, merge);
    if (timed_out) r->expired = 1;
    pa_trim(out);
    return status;
}

static int pa_capture(pa_run *r, const char *const argv[], char *out, size_t cap) {
    return pa_exec_capture(r, argv, out, cap, 0);
}

// A write whose output is never read. Under the HOME lock it still runs with the lock as stdin.
static int pa_exec(pa_run *r, const char *const argv[]) {
    if (r->lock_fd >= 0) {
        char ignored[256];
        return pa_capture(r, argv, ignored, sizeof ignored);
    }
    char path[64];
    snprintf(path, sizeof path, "/system/bin/%s", argv[0]);
    unsigned left = pa_left(r);
    if (!left) return -1;
    int timed_out = 0;
    int status = sysexec_run_argv_timeout(path, argv, 1, left, &timed_out);
    if (timed_out) r->expired = 1;
    return status;
}

static int pa_getprop(pa_run *r, const char *name, char *out, size_t cap) {
    const char *const argv[] = { "getprop", name, NULL };
    if (pa_capture(r, argv, out, cap) != 0) { snprintf(out, cap, "unknown"); return -1; }
    return 0;
}

static int pa_has_line(const char *text, const char *line) {
    size_t n = strlen(line);
    for (const char *p = text; p && *p; ) {
        const char *end = strchr(p, '\n');
        size_t len = end ? (size_t)(end - p) : strlen(p);
        if (len == n && strncmp(p, line, n) == 0) return 1;
        p = end ? end + 1 : NULL;
    }
    return 0;
}

static int pa_in_list(const char *value, const char *const *list) {
    for (; *list; list++) if (strcmp(value, *list) == 0) return 1;
    return 0;
}

// `pm path` printing anything is the shell programs' test for an installed package.
static int pa_package_present(pa_run *r, const char *package) {
    if (!valid_pkg(package)) return 0;
    char out[PA_LINE];
    const char *const argv[] = { "pm", "path", package, NULL };
    (void)pa_capture(r, argv, out, sizeof out);
    return out[0] != '\0';
}

static int pa_package_present_current(const char *package) {
    return pa_current && pa_package_present(pa_current, package);
}

// --- identity --------------------------------------------------------------------------------------

// `command -v su` over the helper's own PATH: present, absent, or abnormal when PATH is unusable.
static const char *pa_su_state(void) {
    const char *path = getenv("PATH");
    if (!path || !*path || strlen(path) >= 1024) return "abnormal";
    char copy[1024];
    snprintf(copy, sizeof copy, "%s", path);
    char *save = NULL;
    for (char *dir = strtok_r(copy, ":", &save); dir; dir = strtok_r(NULL, ":", &save)) {
        char candidate[1100];
        if (dir[0] != '/') continue;
        snprintf(candidate, sizeof candidate, "%s/su", dir);
        if (access(candidate, X_OK) == 0) return "present";
        if (errno != ENOENT && errno != ENOTDIR && errno != EACCES) return "abnormal";
    }
    return "absent";
}

static void pa_identity(pa_run *r) {
    static const struct { const char *key, *prop; } PROPS[] = {
        { "model", "ro.product.model" }, { "serial", "ro.serialno" },
        { "abi", "ro.product.cpu.abi" }, { "sdk", "ro.build.version.sdk" },
    };
    char value[PA_LINE];
    for (size_t i = 0; i < sizeof PROPS / sizeof PROPS[0]; i++) {
        pa_getprop(r, PROPS[i].prop, value, sizeof value);
        pa_kv(r, PROPS[i].key, value);
        if (strcmp(PROPS[i].key, "sdk") == 0 && valid_num(value) && strlen(value) <= 3) r->sdk = atoi(value);
    }
    char uid[24];
    snprintf(uid, sizeof uid, "%u", (unsigned)getuid());
    pa_kv(r, "uid", uid);
    pa_getprop(r, "ro.secure", value, sizeof value);
    pa_kv(r, "secure", value);
    pa_getprop(r, "ro.debuggable", value, sizeof value);
    pa_kv(r, "debuggable", value);
    pa_kv(r, "su", pa_su_state());
}

// --- HOME readback ---------------------------------------------------------------------------------

// What HOME resolves to now: the last line of resolve-activity, or "" when there is none.
static void pa_home_query(pa_run *r, char *out, size_t cap) {
    const char *const argv[] = {
        "cmd", "package", "resolve-activity", "--brief", "-a", "android.intent.action.MAIN",
        "-c", "android.intent.category.HOME", NULL
    };
    char text[PA_LINE];
    (void)pa_capture(r, argv, text, sizeof text);
    const char *last = strrchr(text, '\n');
    snprintf(out, cap, "%s", last ? last + 1 : text);
}

// HOME resolution prints `pkg/.Class` where the class lives in the package; expand it.
static void pa_expand(const char *component, char *out, size_t cap) {
    const char *slash = strchr(component, '/');
    if (slash && slash[1] == '.' && (size_t)(slash - component) < 160)
        snprintf(out, cap, "%.*s/%.*s%s", (int)(slash - component), component,
                 (int)(slash - component), component, slash + 1);
    else
        snprintf(out, cap, "%s", component);
}

static int pa_home_is_dashboard(const char *home, int launch) {
    return pa_in_list(home, PA_EQUIVALENT_HOME[launch]);
}

// --- app ops ---------------------------------------------------------------------------------------

static const char *const PA_OP_MODES[] = { "allow", "default", "deny", "ignore", "foreground", "errored", NULL };
static const char *const PA_OP_NOT_ALLOWED[] = { "default", "deny", "ignore", "foreground", "errored", NULL };

// appops get, with Android 14's two-line answer for an unset op folded to `<op>: <mode>`.
static int pa_op_read(pa_run *r, const char *op, char *mode, size_t cap) {
    const char *const argv[] = { "appops", "get", r->package, op, NULL };
    int status = pa_capture(r, argv, mode, cap);
    for (const char *const *m = PA_OP_MODES; *m; m++) {
        char two_line[64];
        snprintf(two_line, sizeof two_line, "No operations.\nDefault mode: %s", *m);
        if (strcmp(mode, two_line) == 0) { snprintf(mode, cap, "%s: %s", op, *m); break; }
    }
    return status;
}

static int pa_printable_tail(const char *s) {
    // `; ` followed by at least one printable byte, and nothing else.
    if (strncmp(s, "; ", 2) != 0 || !s[2]) return 0;
    for (const char *p = s + 2; *p; p++) if (*p < 0x20 || *p > 0x7e) return 0;
    return 1;
}

// One line `<op>: <mode>` or `<op>: <mode>; …`. Returns the mode word or NULL.
static const char *pa_op_line_mode(const char *op, const char *line, size_t len, const char *const *modes) {
    size_t op_len = strlen(op);
    if (len <= op_len + 2 || strncmp(line, op, op_len) != 0 || strncmp(line + op_len, ": ", 2) != 0) return NULL;
    const char *word = line + op_len + 2;
    for (const char *const *m = modes; *m; m++) {
        size_t n = strlen(*m);
        if ((size_t)(line + len - word) < n || strncmp(word, *m, n) != 0) continue;
        char tail[1100];
        size_t tail_len = (size_t)(line + len - word) - n;
        if (tail_len >= sizeof tail) return NULL;
        memcpy(tail, word + n, tail_len);
        tail[tail_len] = '\0';
        if (tail_len == 0 || pa_printable_tail(tail)) return *m;
    }
    return NULL;
}

// Proven non-allowed: a non-empty answer whose every line is a non-allowed mode or `No operations.`.
static int pa_op_proven_not_allowed(const char *op, const char *mode) {
    if (!*mode) return 0;
    for (const char *p = mode; *p; ) {
        const char *end = strchr(p, '\n');
        size_t len = end ? (size_t)(end - p) : strlen(p);
        int line_ok = (len == strlen("No operations.") && strncmp(p, "No operations.", len) == 0) ||
                      pa_op_line_mode(op, p, len, PA_OP_NOT_ALLOWED) != NULL;
        if (!line_ok) return 0;
        if (!end) break;
        p = end + 1;
    }
    return 1;
}

static const char *pa_op_word(const char *op, const char *mode, int status) {
    if (status != 0 || strchr(mode, '\n')) return "unknown";
    const char *word = pa_op_line_mode(op, mode, strlen(mode), PA_OP_MODES);
    return word ? word : "unknown";
}

// --- runtime permissions ---------------------------------------------------------------------------

// `^\s*<perm>: granted=<state>(, flags=\[[ A-Z0-9_|]*\])?\s*$`
static int pa_grant_line(const char *line, size_t len, const char *permission, const char *state) {
    const char *p = line, *end = line + len;
    while (p < end && (*p == ' ' || *p == '\t')) p++;
    char head[160];
    snprintf(head, sizeof head, "%s: granted=%s", permission, state);
    size_t head_len = strlen(head);
    if ((size_t)(end - p) < head_len || strncmp(p, head, head_len) != 0) return 0;
    p += head_len;
    if (end - p >= 9 && strncmp(p, ", flags=[", 9) == 0) {
        p += 9;
        while (p < end && ((*p >= 'A' && *p <= 'Z') || (*p >= '0' && *p <= '9') || *p == ' ' || *p == '_' || *p == '|')) p++;
        if (p >= end || *p != ']') return 0;
        p++;
    }
    while (p < end && (*p == ' ' || *p == '\t')) p++;
    return p == end;
}

// Every line of `dumpsys package` naming `<perm>: granted=` must say [state]. 0 lines proves nothing.
static int pa_runtime_all(pa_run *r, const char *permission, const char *state, int *lines) {
    const char *const argv[] = { "dumpsys", "package", r->package, NULL };
    *lines = 0;
    if (pa_capture(r, argv, r->dump, PA_DUMP_MAX) != 0) return 0;
    char needle[160];
    snprintf(needle, sizeof needle, "%s: granted=", permission);
    int all = 1;
    for (const char *p = r->dump; *p; ) {
        const char *end = strchr(p, '\n');
        size_t len = end ? (size_t)(end - p) : strlen(p);
        char line[1024];
        size_t copy = len < sizeof line - 1 ? len : sizeof line - 1;
        memcpy(line, p, copy);
        line[copy] = '\0';
        if (strstr(line, needle)) {
            (*lines)++;
            if (len >= sizeof line || !pa_grant_line(p, len, permission, state)) all = 0;
        }
        if (!end) break;
        p = end + 1;
    }
    return *lines > 0 && all;
}

// Grant only when every line proves the grant missing, then report what Android kept.
static const char *pa_runtime_repair(pa_run *r, const char *permission) {
    int lines;
    if (pa_runtime_all(r, permission, "false", &lines)) {
        const char *const grant[] = { "pm", "grant", r->package, permission, NULL };
        (void)pa_exec(r, grant);
    }
    return pa_runtime_all(r, permission, "true", &lines) ? "granted" : "unknown";
}

// --- accessibility ---------------------------------------------------------------------------------

static int pa_settings_get(pa_run *r, const char *ns, const char *key, char *out, size_t cap) {
    const char *const argv[] = { "settings", "get", ns, key, NULL };
    return pa_capture(r, argv, out, cap);
}

// Reproduces the merge in adb_protocol._permission_grant_command: bounded list, valid unique
// components, obsolete class dropped, current class added only when no equivalent spelling is there.
static int pa_services_merge(const char *before, int launch, char *after, size_t cap) {
    after[0] = '\0';
    if (strlen(before) > 4096 || strchr(before, '\n')) return 0;
    if (!*before || strcmp(before, "null") == 0) {
        // nothing to keep
    } else {
        char list[4097];
        snprintf(list, sizeof list, "%s", before);
        const char *seen[65];
        int count = 0;
        char *p = list;
        for (;;) {
            char *colon = strchr(p, ':');
            if (colon) *colon = '\0';
            if (++count > 64 || !valid_component_shape(p)) return 0;
            for (int i = 0; i < count - 1; i++) if (strcmp(seen[i], p) == 0) return 0;
            seen[count - 1] = p;
            if (!pa_in_list(p, PA_OBSOLETE_ACCESSIBILITY[launch])) {
                size_t used = strlen(after);
                if (snprintf(after + used, cap - used, "%s%s", used ? ":" : "", p) >= (int)(cap - used)) return 0;
            }
            if (!colon) break;
            p = colon + 1;
        }
    }
    int known = 0;
    char scan[4400];
    snprintf(scan, sizeof scan, "%s", after);
    char *save = NULL;
    for (char *e = strtok_r(scan, ":", &save); e && !known; e = strtok_r(NULL, ":", &save))
        known = pa_in_list(e, PA_EQUIVALENT_ACCESSIBILITY[launch]);
    if (!known) {
        size_t used = strlen(after);
        if (snprintf(after + used, cap - used, "%s%s", used ? ":" : "", PA_ACCESSIBILITY_COMPONENTS[launch]) >= (int)(cap - used))
            return 0;
    }
    return 1;
}

static int pa_services_known(const char *list, int launch) {
    char scan[4400];
    snprintf(scan, sizeof scan, "%s", list);
    char *save = NULL;
    for (char *e = strtok_r(scan, ":", &save); e; e = strtok_r(NULL, ":", &save))
        if (pa_in_list(e, PA_EQUIVALENT_ACCESSIBILITY[launch])) return 1;
    return 0;
}

// --- PA_IDENTITY ---------------------------------------------------------------------------------

void cmd_pa_identity(conn_ctx *ctx, const char *args) {
    if (!pa_admit(ctx)) return;
    char a[160], b[160];
    if (pa_parse(args, a, b) != 0) { reply(ctx->fd, "ERR args\n"); return; }
    pa_run *r = pa_begin(ctx, PA_FRAMEWORK_TIMEOUT_MS);
    if (!r) return;
    pa_identity(r);
    pa_finish(r);
}

// --- PA_REPAIR <launch> <secure_settings> ---------------------------------------------------------

static const char *pa_secure_settings(pa_run *r) {
    const char *const argv[] = { "dumpsys", "package", r->package, NULL };
    for (int attempt = 0; attempt < 2; attempt++) {
        if (pa_capture(r, argv, r->dump, PA_DUMP_MAX) != 0 || !r->dump[0]) return "unknown";
        int requested = 0, granted = 0;
        size_t perm_len = strlen(PA_SECURE_SETTINGS_PERMISSION);
        for (const char *p = r->dump; *p; ) {
            const char *end = strchr(p, '\n');
            size_t len = end ? (size_t)(end - p) : strlen(p);
            const char *s = p, *e = p + len;
            while (s < e && (*s == ' ' || *s == '\t')) s++;
            const char *t = e;
            while (t > s && (t[-1] == ' ' || t[-1] == '\t')) t--;
            if ((size_t)(t - s) == perm_len && strncmp(s, PA_SECURE_SETTINGS_PERMISSION, perm_len) == 0) requested = 1;
            if ((size_t)(e - s) >= perm_len + 14 && strncmp(s, PA_SECURE_SETTINGS_PERMISSION, perm_len) == 0 &&
                strncmp(s + perm_len, ": granted=true", 14) == 0) granted = 1;
            if (!end) break;
            p = end + 1;
        }
        if (!requested) return "unsupported";
        if (granted) return "granted";
        if (attempt == 0) {
            const char *const grant[] = { "pm", "grant", r->package, PA_SECURE_SETTINGS_PERMISSION, NULL };
            (void)pa_exec(r, grant);
        }
    }
    return "unknown";
}

static int pa_meta_device(const char *device) {
    return pa_in_list(device, PA_META_DEVICES);
}

void cmd_pa_repair(conn_ctx *ctx, const char *args) {
    if (!pa_admit(ctx)) return;
    char a[160], b[160];
    if (pa_parse(args, a, b) != 2 || (strcmp(a, "0") && strcmp(a, "1")) || (strcmp(b, "0") && strcmp(b, "1"))) {
        reply(ctx->fd, "ERR args\n");
        return;
    }
    int launch = a[0] - '0', secure_requested = b[0] == '1';
    pa_run *r = pa_begin(ctx, PA_FRAMEWORK_TIMEOUT_MS);
    if (!r) return;
    pa_identity(r);

    // (1) The app must be installed.
    char line[PA_LINE];
    const char *const path_argv[] = { "pm", "path", r->package, NULL };
    int path_status = pa_capture(r, path_argv, line, sizeof line);
    if (path_status != 0 || strncmp(line, "package:", 8) != 0) {
        // Exit 1 with nothing printed is the shell route's installed-package-missing test.
        pa_refuse(r, r->expired ? "deadline" : (path_status > 0 && WIFEXITED(path_status) && WEXITSTATUS(path_status) == 1 && !line[0]) ? "absent" : "failed");
        return;
    }

    // (2) Accessibility: best-effort compare-and-set. A writer between the re-read and the put can
    // still be overwritten; the post-write readback then reports unknown and PA retries.
    char before[PA_LINE], after[PA_LINE], current[PA_LINE];
    int before_status = pa_settings_get(r, "secure", "enabled_accessibility_services", before, sizeof before);
    int valid = before_status == 0 && pa_services_merge(before, launch, after, sizeof after);
    if (valid && strcmp(after, before) != 0) {
        int current_status = pa_settings_get(r, "secure", "enabled_accessibility_services", current, sizeof current);
        if (current_status == 0 && strcmp(current, before) == 0) {
            const char *const put[] = { "settings", "put", "secure", "enabled_accessibility_services", after, NULL };
            (void)pa_exec(r, put);
        }
    }

    // (3) App ops: allow only an op Android proves is not allowed.
    for (size_t i = 0; i < sizeof PA_APP_OPS / sizeof PA_APP_OPS[0]; i++) {
        char mode[PA_LINE];
        int status = pa_op_read(r, PA_APP_OPS[i], mode, sizeof mode);
        if (status == 0 && pa_op_proven_not_allowed(PA_APP_OPS[i], mode)) {
            const char *const set[] = { "appops", "set", r->package, PA_APP_OPS[i], "allow", NULL };
            (void)pa_exec(r, set);
        }
    }

    char observed[PA_LINE];
    int observed_status = pa_settings_get(r, "secure", "enabled_accessibility_services", observed, sizeof observed);
    if (valid && observed_status == 0 && strcmp(observed, after) == 0 && pa_services_known(observed, launch)) {
        char enabled[PA_LINE];
        if (pa_settings_get(r, "secure", "accessibility_enabled", enabled, sizeof enabled) == 0 &&
            (strcmp(enabled, "0") == 0 || strcmp(enabled, "null") == 0)) {
            const char *const enable[] = { "settings", "put", "secure", "accessibility_enabled", "1", NULL };
            (void)pa_exec(r, enable);
        }
    }

    // (4) Notifications, from Android 13 only.
    const char *notifications = "unsupported";
    if (r->sdk >= PA_NOTIFICATIONS_MIN_SDK) notifications = pa_runtime_repair(r, PA_NOTIFICATIONS_PERMISSION);

    // (7) Readbacks.
    int final_status = pa_settings_get(r, "secure", "enabled_accessibility_services", line, sizeof line);
    pa_kv(r, "accessibility", valid && final_status == 0 && strcmp(line, after) == 0 ? "granted" : "unknown");
    if (pa_settings_get(r, "secure", "accessibility_enabled", line, sizeof line) != 0) snprintf(line, sizeof line, "unknown");
    pa_kv(r, "accessibility_enabled", line);
    for (size_t i = 0; i < sizeof PA_APP_OPS / sizeof PA_APP_OPS[0]; i++) {
        char mode[PA_LINE], key[64];
        int status = pa_op_read(r, PA_APP_OPS[i], mode, sizeof mode);
        snprintf(key, sizeof key, "op.%s", PA_APP_OPS[i]);
        pa_kv(r, key, pa_op_word(PA_APP_OPS[i], mode, status));
    }
    pa_kv(r, "notifications", notifications);

    // (5) Camera and microphone, only where Android lists the hardware feature. `pm list features`,
    // not has-feature: Android 8.1 has no has-feature.
    const char *const features_argv[] = { "pm", "list", "features", NULL };
    int features_status = pa_capture(r, features_argv, r->dump, PA_DUMP_MAX);
    char *features = strdup(r->dump);
    for (size_t i = 0; i < sizeof PA_RUNTIME_GRANTS / sizeof PA_RUNTIME_GRANTS[0]; i++) {
        const char *state;
        if (features_status != 0 || !features || !features[0]) {
            state = "unknown";
        } else {
            char listed[96];
            snprintf(listed, sizeof listed, "feature:android.hardware.%s", PA_RUNTIME_GRANTS[i].feature);
            int present = 0;
            size_t listed_len = strlen(listed);
            for (const char *p = features; *p && !present; ) {
                const char *end = strchr(p, '\n');
                size_t len = end ? (size_t)(end - p) : strlen(p);
                if (len >= listed_len && strncmp(p, listed, listed_len) == 0) {
                    const char *rest = p + listed_len;
                    size_t rest_len = len - listed_len;
                    int ok = rest_len == 0;
                    if (rest_len >= 2 && rest[0] == '=') {
                        ok = 1;
                        for (size_t k = 1; k < rest_len; k++) if (rest[k] < '0' || rest[k] > '9') ok = 0;
                    }
                    present = ok;
                }
                if (!end) break;
                p = end + 1;
            }
            state = present ? pa_runtime_repair(r, PA_RUNTIME_GRANTS[i].permission) : "unsupported";
        }
        pa_kv(r, PA_RUNTIME_GRANTS[i].key, state);
    }
    free(features);

    // (6) WRITE_SECURE_SETTINGS, only for a build that asks for it.
    pa_kv(r, "secure_settings", secure_requested ? pa_secure_settings(r) : "unsupported");

    // (8) Vendor probe, read for the vendor setup that follows.
    char device[PA_LINE];
    pa_getprop(r, "ro.product.device", device, sizeof device);
    pa_kv(r, "device", device);
    pa_kv(r, "vendor", pa_meta_device(device) ? "meta" :
                       pa_package_present(r, PA_SHELLY_PACKAGE) ? "shelly" : "other");
    pa_finish(r);
}

// --- PA_HOME <launch> <repair|deferred> -----------------------------------------------------------

typedef struct {
    int launch;
    char chooser[16];
    char policy[16];
    char identity[48];   // the held lock's dev:ino, for the shell-lock eligibility check
    int busy;
} pa_home_state;

static void pa_receiver(pa_run *r, char *out, size_t cap) {
    snprintf(out, cap, "%s/%s", r->package, PA_HOME_POLICY_RECEIVER);
}

// am broadcast to the app's fixed HOME receiver. [extra] is a NULL-terminated list of `--es` pairs.
static int pa_broadcast(pa_run *r, const char *operation, int pending, const char *const extra[],
                        char *out, size_t cap) {
    char receiver[192];
    pa_receiver(r, receiver, sizeof receiver);
    const char *argv[20];
    int n = 0;
    argv[n++] = "am"; argv[n++] = "broadcast"; argv[n++] = "--include-stopped-packages";
    argv[n++] = "-n"; argv[n++] = receiver; argv[n++] = "--es"; argv[n++] = "operation";
    argv[n++] = operation;
    if (pending) { argv[n++] = "--ez"; argv[n++] = "pending_setup"; argv[n++] = "true"; }
    for (int i = 0; extra && extra[i] && n < 18; i += 2) {
        argv[n++] = "--es"; argv[n++] = extra[i];
        if (!extra[i + 1]) return -1;
        argv[n++] = extra[i + 1];
        if (n >= 18) return -1;
    }
    argv[n] = NULL;
    return pa_capture(r, argv, out, cap);
}

static int pa_broadcast_says(pa_run *r, const char *operation, int pending, const char *const extra[],
                             const char *line) {
    char out[PA_LINE];
    return pa_broadcast(r, operation, pending, extra, out, sizeof out) == 0 && pa_has_line(out, line);
}

// previous_home.receiver_absence_check: an app too old to have the receiver at all.
static int pa_receiver_absent(pa_run *r) {
    char receiver[192], out[PA_LINE];
    pa_receiver(r, receiver, sizeof receiver);
    const char *const query[] = {
        "cmd", "package", "query-receivers", "--query-flags", "512", "--user", "0", "--brief",
        "--components", "-n", receiver, NULL
    };
    if (pa_capture(r, query, out, sizeof out) != 0 || strcmp(out, "No receivers found") != 0) return 0;
    const char *const dump[] = { "dumpsys", "package", r->package, NULL };
    if (pa_capture(r, dump, r->dump, PA_DUMP_MAX) != 0) return 0;
    char marker[160];
    snprintf(marker, sizeof marker, "Package [%s]", r->package);
    return strstr(r->dump, marker) != NULL;
}

#define PA_LINE_BUILTIN "Broadcast completed: result=1, data=\"panel-assistant-home-builtin\""

static int pa_claim_allowed_repair(pa_run *r, pa_home_state *st) {
    (void)st;
    return pa_broadcast_says(r, "dashboard_home_policy", 0, NULL, PA_LINE_BUILTIN) || pa_receiver_absent(r);
}

static int pa_claim_allowed_shell(pa_run *r, pa_home_state *st) {
    const char *const extra[] = { "home_lock_identity", st->identity, NULL };
    return pa_broadcast_says(r, "pending_home_shell_lock_eligibility", 0, extra,
        "Broadcast completed: result=1, data=\"panel-assistant-pending-home-shell-locked\"");
}

typedef int (*pa_allowed_fn)(pa_run *, pa_home_state *);

// Open /system and take the shared HOME lock without waiting. 1 held, 0 unavailable, -1 busy.
static int pa_lock(pa_run *r, pa_home_state *st) {
    int fd = open(PA_HOME_LOCK_PATH, O_RDONLY | O_DIRECTORY | O_CLOEXEC);
    if (fd < 0) return 0;
    if (flock(fd, LOCK_EX | LOCK_NB) != 0) {
        int busy = errno == EWOULDBLOCK;
        close(fd);
        return busy ? -1 : 0;
    }
    struct stat lock_st;
    if (fstat(fd, &lock_st) != 0 || !S_ISDIR(lock_st.st_mode)) { close(fd); return 0; }
    snprintf(st->identity, sizeof st->identity, "%llu:%llu", (unsigned long long)lock_st.st_dev,
             (unsigned long long)lock_st.st_ino);
    r->lock_fd = fd;
    return 1;
}

static void pa_unlock(pa_run *r) {
    if (r->lock_fd >= 0) close(r->lock_fd);
    r->lock_fd = -1;
}

// The root-only directory the chooser window is dumped into.
static int pa_dump_dir(void) {
    int parent = open(PA_DUMP_PARENT, O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC);
    if (parent < 0) return -1;
    struct stat parent_st;
    if (fstat(parent, &parent_st) != 0 || !S_ISDIR(parent_st.st_mode) ||
        (parent_st.st_uid != 0 && parent_st.st_uid != geteuid()) ||
        ((parent_st.st_mode & (S_IWGRP | S_IWOTH)) != 0 && (parent_st.st_mode & S_ISVTX) == 0)) {
        close(parent);
        return -1;
    }
    if (mkdirat(parent, PA_DUMP_NAME, 0700) != 0 && errno != EEXIST) { close(parent); return -1; }
    int fd = openat(parent, PA_DUMP_NAME, O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC);
    close(parent);
    if (fd < 0) return -1;
    struct stat st;
    if (fstat(fd, &st) != 0 || !S_ISDIR(st.st_mode) || st.st_uid != geteuid() || fchmod(fd, 0700) != 0) {
        close(fd);
        return -1;
    }
    return fd;
}

// Read the dump through the protected directory: no symlink, a regular file with one link.
static void pa_read_dump(int dir, char *out, size_t cap) {
    out[0] = '\0';
    int fd = openat(dir, PA_DUMP_FILE, O_RDONLY | O_NOFOLLOW | O_CLOEXEC | O_NONBLOCK);
    if (fd < 0) return;
    struct stat st;
    if (fstat(fd, &st) == 0 && S_ISREG(st.st_mode) && st.st_nlink == 1 && (size_t)st.st_size < cap) {
        size_t used = 0;
        for (;;) {
            ssize_t n = read(fd, out + used, cap - 1 - used);
            if (n < 0 && errno == EINTR) continue;
            if (n <= 0) break;
            used += (size_t)n;
            if (used == cap - 1) break;
        }
        out[used] = '\0';
        if (strlen(out) != used) out[0] = '\0';   // embedded NUL: not a window dump
    }
    close(fd);
}

// The last ` <name>="…"` attribute on a line (the shell's greedy sed), copied when it fits.
static int pa_attr(const char *line, const char *name, char *out, size_t cap) {
    char needle[32];
    snprintf(needle, sizeof needle, " %s=\"", name);
    const char *found = NULL;
    for (const char *p = strstr(line, needle); p; p = strstr(p + 1, needle)) found = p;
    if (!found) return 0;
    const char *value = found + strlen(needle);
    const char *end = strchr(value, '"');
    if (!end || (size_t)(end - value) >= cap) return 0;
    memcpy(out, value, (size_t)(end - value));
    out[end - value] = '\0';
    return 1;
}

static int pa_digits(const char **p, unsigned *value) {
    int n = 0;
    *value = 0;
    while (**p >= '0' && **p <= '9') {
        if (++n > 5) return 0;
        *value = *value * 10 + (unsigned)(**p - '0');
        (*p)++;
    }
    return n > 0;
}

enum pa_chooser_read { PA_CHOOSER_NONE, PA_CHOOSER_TAP, PA_CHOOSER_OTHER_APP };

// Exactly one enabled android:id/button_always and one title; the title names the app; bounds
// `[l,t][r,b]` with 1-5 digits each and a positive area. Anything else presses nothing.
static enum pa_chooser_read pa_parse_chooser(char *ui, unsigned *x, unsigned *y) {
    int always = 0, titles = 0, boxes = 0;
    char title[512] = "";
    unsigned l = 0, t = 0, rgt = 0, b = 0;
    for (char *save = NULL, *line = strtok_r(ui, ">", &save); line; line = strtok_r(NULL, ">", &save)) {
        int android = strstr(line, "package=\"android\"") != NULL;
        if (strstr(line, "resource-id=\"android:id/button_always\"") && android && strstr(line, "enabled=\"true\"")) {
            always++;
            char bounds[64];
            if (pa_attr(line, "bounds", bounds, sizeof bounds)) {
                const char *p = bounds;
                if (*p++ == '[' && pa_digits(&p, &l) && *p++ == ',' && pa_digits(&p, &t) && *p++ == ']' &&
                    *p++ == '[' && pa_digits(&p, &rgt) && *p++ == ',' && pa_digits(&p, &b) && *p++ == ']' && !*p)
                    boxes++;
            }
        }
        if (strstr(line, "resource-id=\"android:id/title\"") && android && pa_attr(line, "text", title, sizeof title))
            titles++;
    }
    if (always != 1 || titles > 1 || boxes != 1) return PA_CHOOSER_NONE;
    int named = 0;
    for (const char *const *label = PA_APP_LABELS; *label && !named; label++) named = strstr(title, *label) != NULL;
    if (!named) return PA_CHOOSER_OTHER_APP;
    if (!(rgt > l && b > t)) return PA_CHOOSER_NONE;
    *x = (l + rgt) / 2;
    *y = (t + b) / 2;
    return PA_CHOOSER_TAP;
}

static void pa_chooser(pa_run *r, pa_home_state *st, pa_allowed_fn allowed) {
    snprintf(st->chooser, sizeof st->chooser, "no_button");
    const char *const home_key[] = { "input", "keyevent", "3", NULL };
    (void)pa_capture(r, home_key, r->dump, 256);
    int dir = pa_dump_dir();
    char *ui = r->dump;
    ui[0] = '\0';
    for (int attempt = 0; attempt < 3 && dir >= 0; attempt++) {
        sysexec_sleep_ms(1000);
        if (unlinkat(dir, PA_DUMP_FILE, 0) != 0 && errno != ENOENT) break;
        const char *const dump[] = { "uiautomator", "dump", PA_DUMP_PATH, NULL };
        char ignored[256];
        (void)pa_capture(r, dump, ignored, sizeof ignored);
        pa_read_dump(dir, ui, PA_DUMP_MAX);
        (void)unlinkat(dir, PA_DUMP_FILE, 0);
        if (strstr(ui, "resource-id=\"android:id/button_always\"")) break;
    }
    if (dir >= 0) close(dir);
    int has_button = strstr(ui, "resource-id=\"android:id/button_") != NULL;
    unsigned x = 0, y = 0;
    enum pa_chooser_read read = pa_parse_chooser(ui, &x, &y);
    if (read == PA_CHOOSER_TAP) {
        // Chooser discovery waited on Android: re-read the authority immediately before pressing.
        if (allowed && !allowed(r, st)) {
            snprintf(st->chooser, sizeof st->chooser, "deferred");
        } else {
            char sx[12], sy[12];
            snprintf(sx, sizeof sx, "%u", x);
            snprintf(sy, sizeof sy, "%u", y);
            const char *const tap[] = { "input", "tap", sx, sy, NULL };
            char ignored[256];
            (void)pa_capture(r, tap, ignored, sizeof ignored);
            snprintf(st->chooser, sizeof st->chooser, "tapped");
            sysexec_sleep_ms(1000);
        }
    } else if (read == PA_CHOOSER_OTHER_APP) {
        snprintf(st->chooser, sizeof st->chooser, "other_app");
    }
    if (strcmp(st->chooser, "tapped") != 0 && has_button) {
        const char *const back[] = { "input", "keyevent", "4", NULL };
        char ignored[256];
        (void)pa_capture(r, back, ignored, sizeof ignored);
    }
}

// home_chooser.home_claim_command.
static void pa_home_claim(pa_run *r, pa_home_state *st, pa_allowed_fn allowed) {
    char home[PA_LINE];
    pa_home_query(r, home, sizeof home);
    if (pa_home_is_dashboard(home, st->launch)) { snprintf(st->chooser, sizeof st->chooser, "kept"); return; }
    if (allowed && !allowed(r, st)) { snprintf(st->chooser, sizeof st->chooser, "deferred"); return; }
    const char *const set[] = { "cmd", "package", "set-home-activity", PA_HOME_COMPONENTS[st->launch], NULL };
    char ignored[256];
    (void)pa_capture(r, set, ignored, sizeof ignored);
    pa_home_query(r, home, sizeof home);
    if (pa_home_is_dashboard(home, st->launch)) snprintf(st->chooser, sizeof st->chooser, "set");
    else if (strncmp(home, "android/", 8) == 0) pa_chooser(r, st, allowed);
    else snprintf(st->chooser, sizeof st->chooser, "other_home");
}

static void pa_ack(pa_run *r, const char *operation, int pending, const char *const extra[]) {
    char ignored[PA_LINE];
    (void)pa_broadcast(r, operation, pending, extra, ignored, sizeof ignored);
}

// The previous HOME the policy names, revalidated under the lock before set-home-activity.
static void pa_home_restore(pa_run *r, pa_home_state *st, const char *previous, int pending) {
    if (!valid_home_target(previous, pa_package_present_current)) return;
    int locked = pa_lock(r, st);
    if (locked < 0) { st->busy = 1; return; }
    if (!locked) return;
    const char *const extra[] = { "home_lock_identity", st->identity, "previous_home_component", previous, NULL };
    if (pa_broadcast_says(r, "restore_previous_home_shell_lock_eligibility", pending, extra,
            "Broadcast completed: result=1, data=\"panel-assistant-home-restore-shell-locked\"")) {
        const char *const set[] = { "cmd", "package", "set-home-activity", previous, NULL };
        char ignored[256], home[PA_LINE], expanded[PA_LINE], wanted[PA_LINE];
        (void)pa_capture(r, set, ignored, sizeof ignored);
        pa_home_query(r, home, sizeof home);
        pa_expand(home, expanded, sizeof expanded);
        pa_expand(previous, wanted, sizeof wanted);
        if (strcmp(expanded, wanted) == 0) {
            const char *const ack[] = { "previous_home_component", previous, NULL };
            pa_ack(r, "acknowledge_retained_home", pending, ack);
            snprintf(st->chooser, sizeof st->chooser, "retained");
        }
    }
    pa_unlock(r);
}

// The component in `Broadcast completed: result=3, data="panel-assistant-home-restore:<c>"`.
static int pa_restore_target(const char *out, char *previous, size_t cap) {
    static const char prefix[] = "Broadcast completed: result=3, data=\"panel-assistant-home-restore:";
    int found = 0;
    for (const char *p = out; p && *p; ) {
        const char *end = strchr(p, '\n');
        size_t len = end ? (size_t)(end - p) : strlen(p);
        size_t plen = sizeof prefix - 1;
        if (len > plen + 1 && strncmp(p, prefix, plen) == 0 && p[len - 1] == '"' && len - plen - 1 < cap) {
            memcpy(previous, p + plen, len - plen - 1);
            previous[len - plen - 1] = '\0';
            if (valid_component_strict(previous)) found++;
        }
        p = end ? end + 1 : NULL;
    }
    if (found != 1) previous[0] = '\0';
    return found == 1;
}

// adb_protocol._setup_home_claim_command (repair, or the pending restore inside deferred setup).
static void pa_home_policy(pa_run *r, pa_home_state *st, int pending) {
    char out[PA_LINE], previous[VALID_COMPONENT_MAX + 1] = "";
    int ok = pa_broadcast(r, "dashboard_home_policy", pending, NULL, out, sizeof out) == 0;
    int builtin = 0;
    if (ok && pa_has_line(out, PA_LINE_BUILTIN)) {
        builtin = 1;
        snprintf(st->policy, sizeof st->policy, "builtin");
    } else if (ok && pa_has_line(out, "Broadcast completed: result=2, data=\"panel-assistant-home-retained\"")) {
        snprintf(st->chooser, sizeof st->chooser, "retained");
        snprintf(st->policy, sizeof st->policy, "retained");
    } else if (ok && pa_restore_target(out, previous, sizeof previous)) {
        snprintf(st->policy, sizeof st->policy, "restore");
        pa_home_restore(r, st, previous, pending);
        if (st->busy) return;
    }
    if (!pending && strcmp(st->chooser, "deferred") == 0 && !builtin && !previous[0] && pa_receiver_absent(r)) {
        builtin = 1;
        snprintf(st->policy, sizeof st->policy, "builtin");
    }
    if (!builtin) return;
    if (pending) { snprintf(st->chooser, sizeof st->chooser, "deferred"); return; }
    // adb_protocol._setup_builtin_home_claim_command, explicit installer repair.
    int eligible = pa_broadcast_says(r, "setup_eligibility", 0, NULL,
        "Broadcast completed: result=1, data=\"panel-assistant-setup-eligible\"") || pa_receiver_absent(r);
    if (!eligible) { snprintf(st->chooser, sizeof st->chooser, "deferred"); return; }
    pa_home_claim(r, st, pa_claim_allowed_repair);
    pa_ack(r, "acknowledge_home", 0, NULL);
}

// adb_protocol._setup_builtin_home_claim_command(deferred_setup=True).
static void pa_home_deferred(pa_run *r, pa_home_state *st) {
    char out[PA_LINE];
    const char *const extra[] = { "home_component", PA_HOME_COMPONENTS[st->launch], NULL };
    if (pa_broadcast(r, "claim_pending_home", 0, extra, out, sizeof out) != 0) return;
    if (pa_has_line(out, "Broadcast completed: result=1, data=\"panel-assistant-pending-home-claimed\"")) {
        snprintf(st->chooser, sizeof st->chooser, "set");
        snprintf(st->policy, sizeof st->policy, "claimed");
    } else if (pa_has_line(out, "Broadcast completed: result=1, data=\"panel-assistant-pending-home-retained\"")) {
        snprintf(st->chooser, sizeof st->chooser, "retained");
        snprintf(st->policy, sizeof st->policy, "retained");
    } else if (pa_has_line(out, "Broadcast completed: result=3, data=\"panel-assistant-pending-home-restore\"")) {
        pa_home_policy(r, st, 1);
        snprintf(st->policy, sizeof st->policy, "restore");
    } else if (pa_has_line(out, "Broadcast completed: result=2, data=\"panel-assistant-pending-home-shell\"")) {
        snprintf(st->policy, sizeof st->policy, "shell");
        // Hold the lock through the final eligibility, every HOME actuator, the chooser waits and
        // the acknowledgement.
        int locked = pa_lock(r, st);
        if (locked < 0) { st->busy = 1; return; }
        if (!locked) return;
        pa_home_claim(r, st, pa_claim_allowed_shell);
        pa_ack(r, "acknowledge_home", 0, NULL);
        pa_unlock(r);
    }
}

void cmd_pa_home(conn_ctx *ctx, const char *args) {
    if (!pa_admit(ctx)) return;
    char a[160], b[160];
    if (pa_parse(args, a, b) != 2 || (strcmp(a, "0") && strcmp(a, "1")) ||
        (strcmp(b, "repair") && strcmp(b, "deferred"))) {
        reply(ctx->fd, "ERR args\n");
        return;
    }
    pa_run *r = pa_begin(ctx, PA_HOME_TIMEOUT_MS);
    if (!r) return;
    pa_home_state st = { .launch = a[0] - '0' };
    snprintf(st.chooser, sizeof st.chooser, "deferred");
    snprintf(st.policy, sizeof st.policy, "none");
    pa_identity(r);
    if (strcmp(b, "deferred") == 0) pa_home_deferred(r, &st);
    else pa_home_policy(r, &st, 0);
    if (st.busy) { pa_refuse(r, "busy"); return; }
    char home[PA_LINE];
    pa_home_query(r, home, sizeof home);
    pa_kv(r, "policy", st.policy);
    pa_kv(r, "chooser", st.chooser);
    pa_kv(r, "home", home);
    pa_finish(r);
}

// --- PA_ADMIN ------------------------------------------------------------------------------------

static int pa_admin_listed(pa_run *r) {
    char listed[192];
    size_t n = strlen(r->package);
    const char *receiver = PA_DEVICE_ADMIN_RECEIVER;
    if (strncmp(receiver, r->package, n) == 0) receiver += n;
    snprintf(listed, sizeof listed, "%s/%s:", r->package, receiver);
    const char *const argv[] = { "dumpsys", "device_policy", NULL };
    return pa_capture(r, argv, r->dump, PA_DUMP_MAX) == 0 && strstr(r->dump, listed) != NULL;
}

void cmd_pa_admin(conn_ctx *ctx, const char *args) {
    if (!pa_admit(ctx)) return;
    char a[160], b[160];
    if (pa_parse(args, a, b) != 0) { reply(ctx->fd, "ERR args\n"); return; }
    pa_run *r = pa_begin(ctx, PA_FRAMEWORK_TIMEOUT_MS);
    if (!r) return;
    pa_identity(r);
    if (!pa_admin_listed(r)) {
        char component[192], ignored[256];
        snprintf(component, sizeof component, "%s/%s", r->package, PA_DEVICE_ADMIN_RECEIVER);
        const char *const set[] = { "dpm", "set-active-admin", component, NULL };
        (void)pa_capture(r, set, ignored, sizeof ignored);
    }
    pa_kv(r, "admin", pa_admin_listed(r) ? "active" : "unknown");
    char home[PA_LINE];
    pa_home_query(r, home, sizeof home);
    pa_kv(r, "home", home);
    pa_finish(r);
}

// --- restore records -----------------------------------------------------------------------------

typedef struct {
    char value[4][PA_RECORD_MAX + 1];
    int present[4];
} pa_record;

// Load a record a setup left. 1 loaded, 0 absent, -1 refused: a symlink, anything but a regular
// file with one link, an owner other than root or shell, more than 512 bytes, or a key not listed.
static int pa_record_load(const char *name, const char *const keys[], pa_record *rec) {
    memset(rec, 0, sizeof *rec);
    char path[256];
    snprintf(path, sizeof path, "%s/%s", PA_RECORD_DIR, name);
    int fd = open(path, O_RDONLY | O_NOFOLLOW | O_CLOEXEC | O_NONBLOCK);
    if (fd < 0) return errno == ENOENT ? 0 : -1;
    struct stat st;
    if (fstat(fd, &st) != 0 || !S_ISREG(st.st_mode) || st.st_nlink != 1 ||
        (st.st_uid != 0 && st.st_uid != PA_SHELL_UID) || st.st_size > PA_RECORD_MAX) {
        close(fd);
        return -1;
    }
    char content[PA_RECORD_MAX + 2];
    size_t used = 0;
    for (;;) {
        ssize_t n = read(fd, content + used, sizeof content - 1 - used);
        if (n < 0 && errno == EINTR) continue;
        if (n < 0) { close(fd); return -1; }
        if (n == 0) break;
        used += (size_t)n;
        if (used > PA_RECORD_MAX) { close(fd); return -1; }
    }
    close(fd);
    content[used] = '\0';
    for (size_t i = 0; i < used; i++)
        if (content[i] != '\n' && (content[i] < 0x20 || content[i] > 0x7e)) return -1;
    for (char *save = NULL, *line = strtok_r(content, "\n", &save); line; line = strtok_r(NULL, "\n", &save)) {
        char *eq = strchr(line, '=');
        if (!eq) return -1;
        *eq = '\0';
        int k = 0;
        while (keys[k] && strcmp(keys[k], line) != 0) k++;
        if (!keys[k] || k >= 4) return -1;
        snprintf(rec->value[k], sizeof rec->value[k], "%s", eq + 1);
        rec->present[k] = 1;
    }
    // A record missing a key is no baseline: setup acts only on 1; restore keeps it and reports unrestored.
    for (int k = 0; keys[k] && k < 4; k++)
        if (!rec->present[k]) return 2;
    return 1;
}

// Write a record once. 1 written, 0 a record already exists, -1 failure. The final name is never
// replaced: a retry must not record already-blanked values as the vendor's.
static int pa_record_write_once(const char *name, const char *content) {
    char path[256], part[264];
    snprintf(path, sizeof path, "%s/%s", PA_RECORD_DIR, name);
    snprintf(part, sizeof part, "%s.part", path);
    if (strlen(content) > PA_RECORD_MAX) return -1;
    struct stat st;
    if (lstat(path, &st) == 0) return 0;
    if (errno != ENOENT) return -1;
    if (unlink(part) != 0 && errno != ENOENT) return -1;
    // 0644 like the shell installers' records: removal over network ADB reads it as shell.
    int fd = open(part, O_WRONLY | O_CREAT | O_EXCL | O_NOFOLLOW | O_CLOEXEC, 0644);
    if (fd < 0) return -1;
    int ok = fstat(fd, &st) == 0 && S_ISREG(st.st_mode) && st.st_nlink == 1 && fchmod(fd, 0644) == 0 &&
             write_complete(fd, content, strlen(content)) == 0 && fsync(fd) == 0;
    if (close(fd) != 0) ok = 0;
    if (!ok) { (void)unlink(part); return -1; }
    if (linkat(AT_FDCWD, part, AT_FDCWD, path, 0) != 0) {
        int exists = errno == EEXIST;
        (void)unlink(part);
        return exists ? 0 : -1;
    }
    if (unlink(part) != 0) return -1;
    return 1;
}

static int pa_record_forget(const char *name) {
    char path[256];
    snprintf(path, sizeof path, "%s/%s", PA_RECORD_DIR, name);
    return unlink(path) == 0 || errno == ENOENT ? 0 : -1;
}

// vendor_provisioning._OVERLAY_FUNCTION: the package's overlay mode as one word.
static const char *pa_overlay(pa_run *r, const char *package) {
    if (!pa_package_present(r, package)) return "absent";
    char mode[PA_LINE];
    const char *const argv[] = { "appops", "get", package, "SYSTEM_ALERT_WINDOW", NULL };
    if (pa_capture(r, argv, mode, sizeof mode) != 0) return "unknown";
    char word[PA_LINE];
    const char *prefix = "SYSTEM_ALERT_WINDOW: ";
    const char *dflt = NULL;
    for (const char *p = strstr(mode, "Default mode: "); p; p = strstr(p + 1, "Default mode: ")) dflt = p;
    if (strncmp(mode, prefix, strlen(prefix)) == 0) {
        snprintf(word, sizeof word, "%s", mode + strlen(prefix));
        char *semi = strchr(word, ';');
        if (semi) *semi = '\0';
    } else if (dflt) {
        snprintf(word, sizeof word, "%s", dflt + strlen("Default mode: "));
    } else {
        return "unknown";
    }
    for (const char *const *m = PA_OVERLAY_MODES; *m; m++) if (strcmp(word, *m) == 0) return *m;
    return "unknown";
}

static void pa_settings_put(pa_run *r, const char *ns, const char *key, const char *value) {
    const char *const put[] = { "settings", "put", ns, key, value, NULL };
    (void)pa_exec(r, put);
}

static void pa_settings_restore(pa_run *r, const char *ns, const char *key, const char *value) {
    if (strcmp(value, "null") == 0) {
        const char *const del[] = { "settings", "delete", ns, key, NULL };
        (void)pa_exec(r, del);
    } else {
        pa_settings_put(r, ns, key, value);
    }
}

static int pa_setting_is(pa_run *r, const char *ns, const char *key, const char *value) {
    char now[PA_LINE];
    return pa_settings_get(r, ns, key, now, sizeof now) == 0 && strcmp(now, value) == 0;
}

static void pa_overlay_set(pa_run *r, const char *package, const char *mode) {
    const char *const set[] = { "appops", "set", package, "SYSTEM_ALERT_WINDOW", mode, NULL };
    (void)pa_exec(r, set);
}

static int pa_mode_in(const char *mode, const char *const modes[]) { return pa_in_list(mode, modes); }

// A recorded screensaver value: empty, null, or `,`-separated strict components (one when single).
static int pa_valid_components(const char *value, int single) {
    if (!value[0] || strcmp(value, "null") == 0) return 1;
    char copy[PA_RECORD_MAX + 1];
    if (snprintf(copy, sizeof copy, "%s", value) >= (int)sizeof copy) return 0;
    if (copy[strlen(copy) - 1] == ',') return 0;
    int n = 0;
    for (char *save = NULL, *c = strtok_r(copy, ",", &save); c; c = strtok_r(NULL, ",", &save), n++)
        if (!valid_component_strict(c)) return 0;
    return n >= 1 && !strstr(value, ",,") && value[0] != ',' && (!single || n == 1);
}

static const char *const PA_META_RESTORED[] = { "absent", "allow", "ignore", "default", "foreground", NULL };

// The one restore grammar. The record directory is shell-writable, so setup actuates only on a
// record PA_RESTORE would put back, and restore writes nothing from a record that fails it.
static int pa_meta_record_valid(const pa_record *rec) {
    return pa_valid_components(rec->value[0], 0) && pa_valid_components(rec->value[1], 1) &&
           pa_mode_in(rec->value[2], PA_META_RESTORED);
}
static int pa_verifier_record_valid(const pa_record *rec) {
    const char *v = rec->value[0];
    return !strcmp(v, "1") || !strcmp(v, "0") || !strcmp(v, "null");
}
static int pa_shelly_record_valid(const pa_record *rec) { return pa_mode_in(rec->value[0], PA_OVERLAY_MODES); }

// --- PA_VENDOR setup -------------------------------------------------------------------------------

static void pa_vendor_meta(pa_run *r) {
    pa_kv(r, "device", "meta");
    char c[PA_LINE], d[PA_LINE], content[PA_RECORD_MAX * 2 + 256];
    // Record Meta's values once. Values someone already blanked are recorded as Meta's own.
    struct stat st;
    char meta_path[256];
    snprintf(meta_path, sizeof meta_path, "%s/%s", PA_RECORD_DIR, PA_RECORD_META);
    if (lstat(meta_path, &st) != 0 && errno == ENOENT &&
        pa_settings_get(r, "secure", PA_SETTING_SCREENSAVER_COMPONENTS, c, sizeof c) == 0 &&
        pa_settings_get(r, "secure", PA_SETTING_SCREENSAVER_DEFAULT, d, sizeof d) == 0 &&
        !strchr(c, '\n') && !strchr(d, '\n')) {
        if (!c[0]) snprintf(c, sizeof c, "%s", PA_META_SCREENSAVER_COMPONENTS);
        if (!d[0]) snprintf(d, sizeof d, "%s", PA_META_SCREENSAVER_DEFAULT_COMPONENT);
        const char *o = pa_overlay(r, PA_META_CONTROL_CENTER);
        if (!strcmp(o, "deny") || !strcmp(o, "unknown")) o = "allow";
        int n = snprintf(content, sizeof content, "screensaver_components=%s\nscreensaver_default_component=%s\n"
                         "controlcenter_overlay=%s\n", c, d, o);
        if (n > 0 && (size_t)n < sizeof content) (void)pa_record_write_once(PA_RECORD_META, content);
    }
    pa_record rec;
    int loaded = pa_record_load(PA_RECORD_META, PA_RECORD_META_KEYS, &rec);
    int have = loaded == 1 && pa_meta_record_valid(&rec);
    if (loaded != 0 && !have) {
        // A record restore would refuse: blanking now would lose the vendor's values for good.
        pa_kv(r, "record", "invalid");
        pa_kv(r, "converged", "0");
        return;
    }
    if (have) {
        pa_kv(r, "record.screensaver_components", rec.value[0]);
        pa_kv(r, "record.screensaver_default_component", rec.value[1]);
        pa_kv(r, "record.controlcenter_overlay", rec.value[2]);
        // Nothing changes until the record reads back.
        const char *const keys[] = { PA_SETTING_SCREENSAVER_COMPONENTS, PA_SETTING_SCREENSAVER_DEFAULT };
        for (int i = 0; i < 2; i++)
            if (!pa_setting_is(r, "secure", keys[i], "")) pa_settings_put(r, "secure", keys[i], "");
        const char *o = pa_overlay(r, PA_META_CONTROL_CENTER);
        if (strcmp(o, "absent") && strcmp(o, "deny")) pa_overlay_set(r, PA_META_CONTROL_CENTER, "deny");
    }
    // Meta's package verifier: its own record, written once; a value already off is recorded as on.
    char v_path[256], v[PA_LINE];
    snprintf(v_path, sizeof v_path, "%s/%s", PA_RECORD_DIR, PA_RECORD_META_VERIFIER);
    if (lstat(v_path, &st) != 0 && errno == ENOENT &&
        pa_settings_get(r, "global", PA_SETTING_VERIFIER, v, sizeof v) == 0 && !strchr(v, '\n')) {
        if (strcmp(v, "0") == 0) snprintf(v, sizeof v, "1");
        int n = snprintf(content, sizeof content, "package_verifier_enable=%s\n", v);
        if (n > 0 && (size_t)n < sizeof content) (void)pa_record_write_once(PA_RECORD_META_VERIFIER, content);
    }
    pa_record vrec;
    int have_verifier = pa_record_load(PA_RECORD_META_VERIFIER, PA_RECORD_VERIFIER_KEYS, &vrec) == 1 &&
                        pa_verifier_record_valid(&vrec);
    if (have_verifier) {
        pa_kv(r, "verifier_record", vrec.value[0]);
        if (!pa_setting_is(r, "global", PA_SETTING_VERIFIER, "0")) pa_settings_put(r, "global", PA_SETTING_VERIFIER, "0");
    }
    char now_c[PA_LINE], now_d[PA_LINE], now_v[PA_LINE];
    int c_ok = pa_settings_get(r, "secure", PA_SETTING_SCREENSAVER_COMPONENTS, now_c, sizeof now_c) == 0;
    int d_ok = pa_settings_get(r, "secure", PA_SETTING_SCREENSAVER_DEFAULT, now_d, sizeof now_d) == 0;
    const char *overlay = pa_overlay(r, PA_META_CONTROL_CENTER);
    int v_ok = pa_settings_get(r, "global", PA_SETTING_VERIFIER, now_v, sizeof now_v) == 0;
    pa_kv(r, "screensaver_components", c_ok ? now_c : "unknown");
    pa_kv(r, "screensaver_default_component", d_ok ? now_d : "unknown");
    pa_kv(r, "controlcenter_overlay", overlay);
    pa_kv(r, "package_verifier_enable", v_ok ? now_v : "unknown");
    int converged = have && c_ok && !now_c[0] && d_ok && !now_d[0] &&
                    (!strcmp(overlay, "deny") || !strcmp(overlay, "absent")) &&
                    have_verifier && v_ok && !strcmp(now_v, "0");
    pa_kv(r, "converged", converged ? "1" : "0");
}

static void pa_vendor_shelly(pa_run *r) {
    pa_kv(r, "device", "shelly");
    char path[256], content[64];
    struct stat st;
    snprintf(path, sizeof path, "%s/%s", PA_RECORD_DIR, PA_RECORD_SHELLY);
    // Only a mode Android actually reports is recorded; otherwise nothing is denied either.
    if (lstat(path, &st) != 0 && errno == ENOENT) {
        const char *o = pa_overlay(r, PA_SHELLY_PACKAGE);
        if (strcmp(o, "absent") && strcmp(o, "unknown")) {
            snprintf(content, sizeof content, "stargate_overlay=%s\n", o);
            (void)pa_record_write_once(PA_RECORD_SHELLY, content);
        }
    }
    pa_record rec;
    int loaded = pa_record_load(PA_RECORD_SHELLY, PA_RECORD_SHELLY_KEYS, &rec);
    int have = loaded == 1 && pa_shelly_record_valid(&rec);
    if (loaded != 0 && !have) {
        pa_kv(r, "record", "invalid");
        pa_kv(r, "converged", "0");
        return;
    }
    if (have) {
        pa_kv(r, "record.stargate_overlay", rec.value[0]);
        const char *o = pa_overlay(r, PA_SHELLY_PACKAGE);
        if (strcmp(o, "absent") && strcmp(o, "deny")) pa_overlay_set(r, PA_SHELLY_PACKAGE, "deny");
    }
    const char *overlay = pa_overlay(r, PA_SHELLY_PACKAGE);
    pa_kv(r, "stargate_overlay", overlay);
    pa_kv(r, "converged", have && (!strcmp(overlay, "deny") || !strcmp(overlay, "absent")) ? "1" : "0");
}

void cmd_pa_vendor(conn_ctx *ctx, const char *args) {
    if (!pa_admit(ctx)) return;
    char a[160], b[160];
    if (pa_parse(args, a, b) != 1 || strcmp(a, "setup") != 0) { reply(ctx->fd, "ERR args\n"); return; }
    pa_run *r = pa_begin(ctx, PA_FRAMEWORK_TIMEOUT_MS);
    if (!r) return;
    pa_identity(r);
    char device[PA_LINE];
    pa_getprop(r, "ro.product.device", device, sizeof device);
    if (pa_meta_device(device)) pa_vendor_meta(r);
    else if (pa_package_present(r, PA_SHELLY_PACKAGE)) pa_vendor_shelly(r);
    else { pa_kv(r, "device", "other"); pa_kv(r, "converged", "1"); }
    pa_finish(r);
}

// --- PA_RESTORE vendor|display ---------------------------------------------------------------------


static const char *pa_restore_vendor(pa_run *r) {
    pa_record meta, verifier, shelly;
    int have_meta = pa_record_load(PA_RECORD_META, PA_RECORD_META_KEYS, &meta);
    int have_verifier = pa_record_load(PA_RECORD_META_VERIFIER, PA_RECORD_VERIFIER_KEYS, &verifier);
    if (have_meta < 0 || have_verifier < 0) return NULL;
    if (have_meta == 2 || have_verifier == 2) return "unrestored";
    static const char *const META_ALLOW[] = { "allow", "ignore", "default", "foreground", NULL };
    if (have_meta || have_verifier) {
        const char *result = "restored";
        const char *c = meta.value[0], *d = meta.value[1], *o = meta.value[2];
        if (have_meta && !pa_meta_record_valid(&meta)) {
            result = "unrestored";
        } else if (have_meta) {
            pa_settings_restore(r, "secure", PA_SETTING_SCREENSAVER_COMPONENTS, c);
            pa_settings_restore(r, "secure", PA_SETTING_SCREENSAVER_DEFAULT, d);
            if (pa_mode_in(o, META_ALLOW) && pa_package_present(r, PA_META_CONTROL_CENTER))
                pa_overlay_set(r, PA_META_CONTROL_CENTER, o);
            // The overlay must read back as the mode recorded, not merely as something other than deny.
            const char *now = pa_overlay(r, PA_META_CONTROL_CENTER);
            if (pa_setting_is(r, "secure", PA_SETTING_SCREENSAVER_COMPONENTS, c) &&
                pa_setting_is(r, "secure", PA_SETTING_SCREENSAVER_DEFAULT, d) &&
                strcmp(now, o) == 0) {
                if (pa_record_forget(PA_RECORD_META) != 0) result = "unrestored";
            } else {
                result = "unrestored";
            }
        }
        if (have_verifier) {
            const char *v = verifier.value[0];
            if (!pa_verifier_record_valid(&verifier)) {
                result = "unrestored";
            } else {
                pa_settings_restore(r, "global", PA_SETTING_VERIFIER, v);
                if (!pa_setting_is(r, "global", PA_SETTING_VERIFIER, v) || pa_record_forget(PA_RECORD_META_VERIFIER) != 0)
                    result = "unrestored";
            }
        }
        return result;
    }
    int have_shelly = pa_record_load(PA_RECORD_SHELLY, PA_RECORD_SHELLY_KEYS, &shelly);
    if (have_shelly < 0) return NULL;
    if (!have_shelly) return "none";
    if (have_shelly == 2) return "unrestored";
    if (!pa_shelly_record_valid(&shelly)) return "unrestored";
    const char *o = shelly.value[0];
    if (pa_package_present(r, PA_SHELLY_PACKAGE)) pa_overlay_set(r, PA_SHELLY_PACKAGE, o);
    const char *now = pa_overlay(r, PA_SHELLY_PACKAGE);
    if ((strcmp(now, "absent") == 0 || strcmp(now, o) == 0) && pa_record_forget(PA_RECORD_SHELLY) == 0) return "restored";
    return "unrestored";
}

// display_provisioning._DENSITY_FUNCTION: the override, `none` without one, or unknown.
static void pa_density(pa_run *r, char *out, size_t cap) {
    char text[PA_LINE];
    const char *const argv[] = { "wm", "density", NULL };
    snprintf(out, cap, "unknown");
    if (pa_capture(r, argv, text, sizeof text) != 0) return;
    const char *over = NULL;
    for (const char *p = strstr(text, "Override density: "); p; p = strstr(p + 1, "Override density: ")) over = p;
    if (over) {
        const char *digits = over + strlen("Override density: ");
        size_t n = 0;
        while (digits[n] >= '0' && digits[n] <= '9' && n < 8) n++;
        if (n) snprintf(out, cap, "%.*s", (int)n, digits);
    } else if (strstr(text, "Physical density: ")) {
        snprintf(out, cap, "none");
    }
}

static const char *pa_restore_display(pa_run *r) {
    pa_record rec;
    int have = pa_record_load(PA_RECORD_DISPLAY, PA_RECORD_DISPLAY_KEYS, &rec);
    if (have < 0) return NULL;
    if (!have) return "none";
    if (have == 2) return "unrestored";
    const char *d = rec.value[0], *f = rec.value[1];
    if (strcmp(d, "none") == 0) {
        const char *const reset[] = { "wm", "density", "reset", NULL };
        (void)pa_exec(r, reset);
    } else if (valid_num(d) && strlen(d) <= 3 && atoi(d) >= 72 && atoi(d) <= 960) {
        const char *const set[] = { "wm", "density", d, NULL };
        (void)pa_exec(r, set);
    }
    if (strcmp(f, "null") == 0) {
        const char *const del[] = { "settings", "delete", "system", PA_SETTING_FONT_SCALE, NULL };
        (void)pa_exec(r, del);
    } else if (f[0] && valid_decimal(f) && strlen(f) <= 8) {
        pa_settings_put(r, "system", PA_SETTING_FONT_SCALE, f);
    }
    char density[32];
    pa_density(r, density, sizeof density);
    if (strcmp(density, d) == 0 && pa_setting_is(r, "system", PA_SETTING_FONT_SCALE, f) &&
        pa_record_forget(PA_RECORD_DISPLAY) == 0) return "restored";
    return "unrestored";
}

void cmd_pa_restore(conn_ctx *ctx, const char *args) {
    if (!pa_admit(ctx)) return;
    char a[160], b[160];
    if (pa_parse(args, a, b) != 1 || (strcmp(a, "vendor") && strcmp(a, "display"))) {
        reply(ctx->fd, "ERR args\n");
        return;
    }
    pa_run *r = pa_begin(ctx, PA_FRAMEWORK_TIMEOUT_MS);
    if (!r) return;
    pa_identity(r);
    const char *result = strcmp(a, "vendor") == 0 ? pa_restore_vendor(r) : pa_restore_display(r);
    if (!result) { pa_refuse(r, "failed"); return; }
    pa_kv(r, "result", result);
    pa_finish(r);
}

// --- PA_OBSERVE move|recovery ----------------------------------------------------------------------

static const char *pa_listed(pa_run *r, const char *package) {
    char out[PA_LINE], wanted[160];
    const char *const argv[] = { "pm", "list", "packages", package, NULL };
    // stderr too: some Android versions print a service error and still exit 0.
    if (pa_exec_capture(r, argv, out, sizeof out, 1) != 0) return "failed";
    snprintf(wanted, sizeof wanted, "package:%s", package);
    if (strcmp(out, wanted) == 0) return "installed";
    return out[0] ? "failed" : "absent";
}

// install_adb._FIRMWARE_PATTERN, fullmatch; anything else reports empty.
static void pa_firmware(pa_run *r, char *out, size_t cap) {
    pa_getprop(r, "ro.build.display.id", out, cap);
    size_t n = strlen(out);
    int ok = n >= 1 && n <= 80 &&
             ((out[0] >= '0' && out[0] <= '9') || (out[0] >= 'A' && out[0] <= 'Z') || (out[0] >= 'a' && out[0] <= 'z'));
    for (size_t i = 1; ok && i < n; i++) {
        char c = out[i];
        ok = (c >= '0' && c <= '9') || (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') ||
             c == ' ' || c == '.' || c == '_' || c == '+' || c == '-';
    }
    if (!ok) out[0] = '\0';
}

static void pa_observe_move(pa_run *r) {
    pa_kv(r, "legacy", pa_listed(r, PA_LEGACY_PACKAGE));
    pa_kv(r, "new", pa_listed(r, r->package));
    pa_kv(r, "shelly", pa_listed(r, PA_SHELLY_PACKAGE));
    char home[PA_LINE], firmware[PA_LINE];
    pa_home_query(r, home, sizeof home);
    pa_kv(r, "home", home);
    pa_firmware(r, firmware, sizeof firmware);
    pa_kv(r, "firmware", firmware);
}

static int pa_observe_recovery(pa_run *r) {
    char boot[128], uptime[128], line[PA_LINE];
    first_line(PA_BOOT_ID_PATH, boot, sizeof boot);
    first_line("/proc/uptime", uptime, sizeof uptime);
    char *space = strchr(uptime, ' ');
    if (space) *space = '\0';
    if (!boot[0] || !uptime[0]) return -1;
    pa_kv(r, "boot_id", boot);
    pa_kv(r, "uptime", uptime);
    pa_home_query(r, line, sizeof line);
    pa_kv(r, "home", line);
    const char *const pidof[] = { "pidof", PA_SHELLY_PACKAGE, NULL };
    (void)pa_capture(r, pidof, line, sizeof line);
    pa_kv(r, "vendor_pid", line[0] ? line : "0");
    // The app's `User 0:` line with installed=true, and its one stopped= flag.
    const char *stopped = "unknown";
    const char *const dump[] = { "dumpsys", "package", r->package, NULL };
    if (pa_capture(r, dump, r->dump, PA_DUMP_MAX) == 0) {
        int matches = 0;
        for (char *save = NULL, *l = strtok_r(r->dump, "\n", &save); l; l = strtok_r(NULL, "\n", &save)) {
            const char *s = l;
            while (*s == ' ') s++;
            if (strncmp(s, "User 0:", 7) != 0 || !strstr(s, "installed=true")) continue;
            int flags = 0;
            const char *value = NULL;
            for (const char *p = strstr(s, "stopped="); p; p = strstr(p + 1, "stopped=")) {
                if (p > s && ((p[-1] >= 'a' && p[-1] <= 'z') || (p[-1] >= 'A' && p[-1] <= 'Z') || p[-1] == '_')) continue;
                if (!strncmp(p + 8, "true", 4) || !strncmp(p + 8, "false", 5)) { flags++; value = p[8] == 't' ? "true" : "false"; }
            }
            matches++;
            stopped = flags == 1 ? value : "unknown";
            break;
        }
        if (!matches) stopped = "unknown";
    }
    pa_kv(r, "stopped", stopped);
    return 0;
}

void cmd_pa_observe(conn_ctx *ctx, const char *args) {
    if (!pa_admit(ctx)) return;
    char a[160], b[160];
    if (pa_parse(args, a, b) != 1 || (strcmp(a, "move") && strcmp(a, "recovery"))) {
        reply(ctx->fd, "ERR args\n");
        return;
    }
    pa_run *r = pa_begin(ctx, PA_FRAMEWORK_TIMEOUT_MS);
    if (!r) return;
    pa_identity(r);
    if (strcmp(a, "move") == 0) pa_observe_move(r);
    else if (pa_observe_recovery(r) != 0) { pa_refuse(r, "failed"); return; }
    pa_finish(r);
}

// --- PA_HANDBACK vendor | component <pkg/class> ----------------------------------------------------

void cmd_pa_handback(conn_ctx *ctx, const char *args) {
    if (!pa_admit(ctx)) return;
    char a[160], b[160];
    int n = pa_parse(args, a, b);
    int vendor = n == 1 && strcmp(a, "vendor") == 0;
    int component = n == 2 && strcmp(a, "component") == 0 && valid_component_strict(b);
    if (!vendor && !component) { reply(ctx->fd, "ERR args\n"); return; }
    pa_run *r = pa_begin(ctx, PA_FRAMEWORK_TIMEOUT_MS);
    if (!r) return;
    const char *target = vendor ? PA_SHELLY_SPLASH : b;
    if (vendor ? !pa_package_present(r, PA_SHELLY_PACKAGE) : !valid_home_target(b, pa_package_present_current)) {
        pa_refuse(r, r->expired ? "deadline" : "home-unsafe");
        return;
    }
    pa_identity(r);
    const char *const set[] = { "cmd", "package", "set-home-activity", target, NULL };
    char ignored[256], home[PA_LINE], expanded[PA_LINE], wanted[PA_LINE];
    (void)pa_capture(r, set, ignored, sizeof ignored);
    pa_home_query(r, home, sizeof home);
    pa_expand(home, expanded, sizeof expanded);
    pa_expand(target, wanted, sizeof wanted);
    pa_kv(r, "handed", strcmp(expanded, wanted) == 0 ? "1" : "0");
    // The move observation, with HOME in its expanded form.
    pa_kv(r, "legacy", pa_listed(r, PA_LEGACY_PACKAGE));
    pa_kv(r, "new", pa_listed(r, r->package));
    pa_kv(r, "shelly", pa_listed(r, PA_SHELLY_PACKAGE));
    pa_kv(r, "home", expanded);
    char firmware[PA_LINE];
    pa_firmware(r, firmware, sizeof firmware);
    pa_kv(r, "firmware", firmware);
    pa_finish(r);
}
