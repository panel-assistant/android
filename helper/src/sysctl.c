#include "sysctl.h"
#include "companion.h"
#include "guard_maintenance.h"
#include "identity.h"
#include "sysexec.h"
#include "util.h"

#include <errno.h>
#include <dirent.h>
#include <fcntl.h>
#include <pthread.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <sys/statvfs.h>
#include <sys/wait.h>
#include <unistd.h>

static int command_ok(int status) {
    return status == 0;
}

static int command_exited_with(int status, int code) {
    return status >= 0 && WIFEXITED(status) && WEXITSTATUS(status) == code;
}

// Force-stop + relaunch a dashboard app (root via this daemon's su domain).
static int reload_pkg(const char *pkg) {
    if (!valid_pkg(pkg)) return -1;
    const char *const stop[] = { "am", "force-stop", pkg, NULL };
    if (!command_ok(sysexec_run_argv("/system/bin/am", stop, 1))) return -1;
    const char *const launch[] = {
        "monkey", "-p", pkg, "-c", "android.intent.category.LAUNCHER", "1", NULL
    };
    return command_ok(sysexec_run_argv("/system/bin/monkey", launch, 1)) ? 0 : -1;
}

// Launch an activity by component (pkg/cls) via `am start` — root, so it's not subject to the
// Android 10+ background-activity-launch limits that block an app's own startActivity from a service.
static int start_component(const char *comp) {
    if (!valid_component(comp)) return -1;
    const char *const argv[] = { "am", "start", "-n", comp, NULL };
    return command_ok(sysexec_run_argv("/system/bin/am", argv, 1)) ? 0 : -1;
}

// Forced display density (dpi) via `wm density`; arg = a number, or "reset" for the physical default.
static int set_density(const char *arg) {
    if (strcmp(arg, "reset") == 0) {
        const char *const argv[] = { "wm", "density", "reset", NULL };
        return command_ok(sysexec_run_argv("/system/bin/wm", argv, 1)) ? 0 : -1;
    }
    if (!valid_num(arg)) return -1;
    const char *const argv[] = { "wm", "density", arg, NULL };
    return command_ok(sysexec_run_argv("/system/bin/wm", argv, 1)) ? 0 : -1;
}

// Read the current density as "PHYS=<n> OVER=<n|->" parsed from `wm density` (one line).
static int get_density(char *out, size_t outsz) {
    const char *const argv[] = { "wm", "density", NULL };
    char phys[16] = "", over[16] = "-", output[512];
    if (!command_ok(sysexec_capture_argv("/system/bin/wm", argv, output, sizeof output))) return -1;
    char *save = NULL;
    for (char *buf = strtok_r(output, "\r\n", &save); buf; buf = strtok_r(NULL, "\r\n", &save)) {
        char *c;
        if ((c = strstr(buf, "Physical density:"))) sscanf(c + 17, "%15s", phys);
        else if ((c = strstr(buf, "Override density:"))) sscanf(c + 17, "%15s", over);
    }
    if (phys[0] == '\0') return -1;
    snprintf(out, outsz, "PHYS=%s OVER=%s\n", phys, over);
    return 0;
}

// System font scale (text size) via `settings system font_scale`; arg = a decimal, or "reset" to
// clear the override. Root-only on sandbox-walled panels (the app can't put a system setting there),
// so it's routed here — the daemon counterpart of the su-direct `settings put system font_scale`.
static int set_fontscale(const char *arg) {
    if (strcmp(arg, "reset") == 0) {
        const char *const argv[] = { "settings", "delete", "system", "font_scale", NULL };
        return command_ok(sysexec_run_argv("/system/bin/settings", argv, 1)) ? 0 : -1;
    }
    if (!valid_decimal(arg)) return -1;
    const char *const argv[] = { "settings", "put", "system", "font_scale", arg, NULL };
    return command_ok(sysexec_run_argv("/system/bin/settings", argv, 1)) ? 0 : -1;
}

#define BOOTCHIME_MAX_VALUE 100U

static int valid_bootchime_value(const char *value) {
    if (!value || value[0] == '\0') return 0;
    unsigned parsed = 0;
    for (const unsigned char *cursor = (const unsigned char *)value; *cursor; cursor++) {
        if (*cursor < '0' || *cursor > '9') return 0;
        unsigned digit = (unsigned)(*cursor - '0');
        if (parsed > (BOOTCHIME_MAX_VALUE - digit) / 10U) return 0;
        parsed = parsed * 10U + digit;
    }
    return 1;
}

static int valid_bootchime_setting(const char *value) {
    return strcmp(value, "-") == 0 || valid_bootchime_value(value);
}

static int write_bootchime_setting(const char *key, const char *value) {
    if (strcmp(value, "-") == 0) {
        const char *const argv[] = { "settings", "delete", "system", key, NULL };
        return command_ok(sysexec_run_argv("/system/bin/settings", argv, 1)) ? 0 : -1;
    }
    const char *const argv[] = { "settings", "put", "system", key, value, NULL };
    return command_ok(sysexec_run_argv("/system/bin/settings", argv, 1)) ? 0 : -1;
}

static int write_bootchime_stream(const char *stream, const char *value) {
    const char *const argv[] = {
        "cmd", "media_session", "volume", "--stream", stream, "--set", value, NULL
    };
    return command_ok(sysexec_run_argv("/system/bin/cmd", argv, 1)) ? 0 : -1;
}

static int apply_bootchime(
    const char *ring_speaker,
    const char *ring,
    const char *notification,
    const char *ring_stream,
    const char *notification_stream
) {
    int applied = 0;
    if (write_bootchime_setting("volume_ring_speaker", ring_speaker) != 0) return -1;
    applied++;
    if (write_bootchime_setting("volume_ring", ring) != 0) return applied;
    applied++;
    if (write_bootchime_setting("volume_notification", notification) != 0) return applied;
    applied++;
    if (write_bootchime_stream("2", ring_stream) != 0) return applied;
    applied++;
    if (write_bootchime_stream("5", notification_stream) != 0) return applied;
    return 0;
}

static const char *bootchime_reply(int result) {
    if (result == 0) return "OK\n";
    return result > 0 ? "PARTIAL\n" : "ERR\n";
}

// Read the current font scale as "SCALE=<v>" parsed from `settings get system font_scale`
// (v="null" when unset → the app reads that as the 1.0 default).
static int get_fontscale(char *out, size_t outsz) {
    const char *const argv[] = { "settings", "get", "system", "font_scale", NULL };
    char val[32] = "";
    if (!command_ok(sysexec_capture_argv("/system/bin/settings", argv, val, sizeof val))) return -1;
    char *nl = strpbrk(val, "\r\n");
    if (nl) *nl = '\0';
    if (val[0] == '\0') strcpy(val, "null");
    snprintf(out, outsz, "SCALE=%s\n", val);
    return 0;
}

// CPU scaling governor on all cores (cpufreq sysfs is root-writable; the app can read it itself).
static int set_governor(const char *gov) {
    if (!valid_gov(gov)) return -1;
    DIR *cpus = opendir("/sys/devices/system/cpu");
    if (!cpus) return -1;
    int found = 0, failed = 0;
    struct dirent *entry;
    while ((entry = readdir(cpus)) != NULL) {
        if (strncmp(entry->d_name, "cpu", 3) != 0 || entry->d_name[3] < '0' || entry->d_name[3] > '9')
            continue;
        size_t i = 4;
        while (entry->d_name[i] >= '0' && entry->d_name[i] <= '9') i++;
        if (entry->d_name[i] != '\0') continue;
        char path[256];
        int n = snprintf(path, sizeof path, "/sys/devices/system/cpu/%s/cpufreq/scaling_governor",
                         entry->d_name);
        if (n <= 0 || (size_t)n >= sizeof path) { failed = 1; continue; }
        int fd = open(path, O_WRONLY | O_CLOEXEC | O_NOFOLLOW);
        if (fd < 0) {
            if (errno != ENOENT) failed = 1;
            continue;
        }
        found = 1;
        size_t size = strlen(gov);
        int written = write(fd, gov, size) == (ssize_t)size;
        if (close(fd) != 0 || !written) failed = 1;
    }
    closedir(cpus);
    return found && !failed ? 0 : -1;
}

// Force-stop a package WITHOUT relaunching it — the "tame" kill (RELOAD's force-stop+monkey is the
// dashboard reload). Refused for critical system packages.
static int stop_pkg(const char *pkg) {
    if (!valid_pkg(pkg) || is_critical_pkg(pkg)) return -1;
    const char *const argv[] = { "am", "force-stop", pkg, NULL };
    return command_ok(sysexec_run_argv("/system/bin/am", argv, 1)) ? 0 : -1;
}

// Enable/disable a package for the primary user. Disable (`pm disable-user --user 0`) stops a vendor
// app relaunching on boot and is reversible via ENABLE (`pm enable`); disabling a critical package
// would brick the panel, so that's refused while enabling is always allowed.
static int set_pkg_enabled(const char *pkg, int enabled) {
    if (!valid_pkg(pkg)) return -1;
    if (!enabled && is_critical_pkg(pkg)) return -1;
    const char *const argv[] = { "pm", enabled ? "enable" : "disable-user", "--user", "0", pkg, NULL };
    return command_ok(sysexec_run_argv("/system/bin/pm", argv, 1)) ? 0 : -1;
}

// Read or set a package's SYSTEM_ALERT_WINDOW (floating-overlay) app-op. Taming records the exact
// prior mode before denying it, so undo can restore default/ignore/foreground rather than blindly
// granting a permission the package did not have. Restrictive modes remain refused for critical apps.
static int valid_overlay_mode(const char *mode) {
    return strcmp(mode, "allow") == 0 || strcmp(mode, "deny") == 0 ||
           strcmp(mode, "ignore") == 0 || strcmp(mode, "default") == 0 ||
           strcmp(mode, "foreground") == 0;
}

static int set_overlay(const char *pkg, const char *mode) {
    if (!valid_pkg(pkg) || !valid_overlay_mode(mode)) return -1;
    if (is_critical_pkg(pkg) && strcmp(mode, "allow") != 0 && strcmp(mode, "default") != 0) return -1;
    const char *const argv[] = { "appops", "set", pkg, "SYSTEM_ALERT_WINDOW", mode, NULL };
    return command_ok(sysexec_run_argv("/system/bin/appops", argv, 1)) ? 0 : -1;
}

static int get_overlay(const char *pkg, char *out, size_t outsz) {
    if (!valid_pkg(pkg)) return -1;
    const char *const argv[] = { "appops", "get", pkg, "SYSTEM_ALERT_WINDOW", NULL };
    char output[2048];
    if (!command_ok(sysexec_capture_argv("/system/bin/appops", argv, output, sizeof output))) return -1;
    char mode[16] = "";
    char *save = NULL;
    for (char *cursor = strtok_r(output, "\r\n", &save); cursor; cursor = strtok_r(NULL, "\r\n", &save)) {
        char *op = strstr(cursor, "SYSTEM_ALERT_WINDOW");
        if (!op) continue;
        char *colon = strchr(op, ':');
        if (!colon) continue;
        colon++;
        while (*colon == ' ' || *colon == '\t') colon++;
        size_t n = strcspn(colon, " ;\t\r\n");
        if (n == 0 || n >= sizeof mode) continue;
        memcpy(mode, colon, n);
        mode[n] = '\0';
        if (!valid_overlay_mode(mode)) mode[0] = '\0';
        else break;
    }
    // A successful query with no explicit entry means the package is using the platform default.
    if (mode[0] == '\0') strcpy(mode, "default");
    snprintf(out, outsz, "MODE=%s\n", mode);
    return 0;
}

// Set the default HOME (launcher) to [comp] (pkg/cls). ha-paneld re-asserts the dashboard app as the
// default home after a package change clears the association (declaring a new HOME activity resets it).
// Bounded to a valid component; the target package's own manifest still governs whether it can be home.
static int set_home(const char *comp) {
    if (!valid_component(comp)) return -1;
    const char *const argv[] = { "cmd", "package", "set-home-activity", comp, NULL };
    return command_ok(sysexec_run_argv("/system/bin/cmd", argv, 1)) ? 0 : -1;
}

// Report a package's state for the app watchdog: "DEAD" (no live process), "FG" (alive and the
// focused window), or "BG" (alive but not focused). Read-only: `pidof` for liveness, then a scan of
// `dumpsys window` for the "<pkg>/" component on the mCurrentFocus line. The pkg is validated.
static int app_state(const char *pkg, char *out, size_t outsz) {
    if (!valid_pkg(pkg)) return -1;
    const char *const pid_argv[] = { "pidof", pkg, NULL };
    char b[64];
    int status = sysexec_capture_argv("/system/bin/pidof", pid_argv, b, sizeof b);
    int alive = b[0] != '\0' && b[0] != '\n';
    // pidof exits 1 when there is no matching process; that is a truthful DEAD result, not a probe
    // failure. Any other non-zero status means the liveness probe itself failed.
    if (!command_ok(status) && !(command_exited_with(status, 1) && !alive)) return -1;
    if (!alive) { snprintf(out, outsz, "DEAD"); return 0; }
    int fg = 0;
    const char *const window_argv[] = { "dumpsys", "window", NULL };
    char output[128 * 1024];
    if (!command_ok(sysexec_capture_argv("/system/bin/dumpsys", window_argv, output, sizeof output))) return -1;
    char needle[160];
    snprintf(needle, sizeof needle, "%s/", pkg);
    char *save = NULL;
    for (char *cursor = strtok_r(output, "\r\n", &save); cursor; cursor = strtok_r(NULL, "\r\n", &save)) {
        if (strstr(cursor, "mCurrentFocus") && strstr(cursor, needle)) { fg = 1; break; }
    }
    snprintf(out, outsz, "%s", fg ? "FG" : "BG");
    return 0;
}

// Write a complete stream chunk, retrying only interrupted calls. A zero return cannot make progress
// and any other error means the peer is terminally unavailable.
// Capture the screen as PNG and stream the raw bytes to [fd]. Client half-closes then reads to EOF.
static void screencap_to(int fd) {
    const char *const argv[] = { "screencap", "-p", NULL };
    (void)sysexec_stream_argv("/system/bin/screencap", argv, fd);
}

void cmd_reload(conn_ctx *ctx, const char *args) {
    char pkg[128] = "", extra[2] = "";
    if (sscanf(args, "%127s %1s", pkg, extra) != 1) {
        reply(ctx->fd, "ERR\n");
        return;
    }
    int guard = companion_guard_package_launch(pkg);
    if (guard == COMPANION_GUARD_BUSY) {
        reply(ctx->fd, "BUSY\n");
        return;
    }
    int result = reload_pkg(pkg);
    companion_guard_release(guard);
    reply(ctx->fd, result == 0 ? "OK\n" : "ERR\n");
}

void cmd_start(conn_ctx *ctx, const char *args) {
    char comp[160] = "", extra[2] = "";
    if (sscanf(args, "%159s %1s", comp, extra) != 1) {
        reply(ctx->fd, "ERR\n");
        return;
    }
    int guard = companion_guard_component_launch(comp);
    if (guard == COMPANION_GUARD_BUSY) {
        reply(ctx->fd, "BUSY\n");
        return;
    }
    int result = start_component(comp);
    companion_guard_release(guard);
    reply(ctx->fd, result == 0 ? "OK\n" : "ERR\n");
}

void cmd_sethome(conn_ctx *ctx, const char *args) {
    char comp[160] = "";
    sscanf(args, "%159s", comp);
    reply(ctx->fd, set_home(comp) == 0 ? "OK\n" : "ERR\n");
}

/*
 * Reboot mechanisms, tried in order.
 *
 * A privileged actuator's exit status is not proof that the panel is going down. `/system/bin/svc`
 * is an app_process wrapper, and on some builds it exits 0 under this daemon's sanitized environment
 * without rebooting at all (reported on KonstaKANG LineageOS for the Raspberry Pi 4, where the same
 * command reboots correctly from an ordinary root shell). That lie used to suppress the fallback
 * below, so a panel simply never rebooted and nothing said so.
 *
 * Completion is therefore observed rather than reported: after each request we wait a bounded
 * interval, and reaching the next statement at all is the proof that the panel did not go down.
 * There is no positive "shutdown started" signal here on purpose — the portable ones are property
 * reads whose behaviour varies by init version, and absence of a hint would never be safe to act on
 * anyway. Escalating during a slow but genuine shutdown re-requests the same orderly reboot init is
 * already performing, which is why the first interval is the generous one.
 */
#define REBOOT_ESCALATE_MS 6000u   // window for a request before the next mechanism is tried
#define REBOOT_SETTLE_MS   4000u   // window for the last mechanism before reporting failure

static const char *const REBOOT_SVC_ARGV[] = { "svc", "power", "reboot", NULL };
static const char *const REBOOT_DIRECT_ARGV[] = { "reboot", NULL };

static const struct { const char *path; const char *const *argv; } REBOOT_MECHANISMS[] = {
    { "/system/bin/svc", REBOOT_SVC_ARGV },
    { "/system/bin/reboot", REBOOT_DIRECT_ARGV },
};

// Request a reboot through every mechanism in turn. Returns only when none of them took the panel
// down; a reboot that actually happens never comes back from here.
//
// What each mechanism is given is a WINDOW, not a call: the actuator's own exit tells us nothing (that
// is the whole premise here), so what the window buys is time for the host-level effect to appear.
// Running under a deadline and then sleeping the unused remainder makes the window the same length in
// all three cases — the actuator returned quickly, returned slowly, or never returned at all — which
// is what keeps the total inside the client's own budget. Without the deadline a wedged app_process
// wrapper simply never yields, and no later mechanism is ever tried.
static void request_reboot_bounded(void) {
    const size_t count = sizeof REBOOT_MECHANISMS / sizeof REBOOT_MECHANISMS[0];
    for (size_t i = 0; i < count; i++) {
        const unsigned window = i + 1 < count ? REBOOT_ESCALATE_MS : REBOOT_SETTLE_MS;
        unsigned elapsed = 0;
        (void)sysexec_run_argv_deadline(REBOOT_MECHANISMS[i].path, REBOOT_MECHANISMS[i].argv, 1, window, &elapsed);
        if (elapsed < window) sysexec_sleep_ms(window - elapsed);
    }
}

void cmd_reboot(conn_ctx *ctx, const char *args) {
    char mode[8] = "", extra[2] = "";
    int fields = sscanf(args, "%7s %1s", mode, extra);
    if (fields == 1 && strcmp(mode, "AWAIT") == 0) {
        // AWAIT holds the reply until the outcome is known. A reboot that happens never answers, so
        // the client's EOF is the success signal; ERR means every mechanism ran and the panel is
        // still up, which is the only reliable cue for the client to try its own root route (whose
        // environment differs from this daemon's, and can therefore succeed where these did not).
        request_reboot_bounded();
        reply(ctx->fd, "ERR\n");
        return;
    }
    if (fields > 0) {
        reply(ctx->fd, "ERR\n");
        return;
    }
    reply(ctx->fd, "OK\n");   // bare REBOOT keeps the legacy accept-then-go-down contract
    request_reboot_bounded();
}

void cmd_appstate(conn_ctx *ctx, const char *args) {
    char pkg[128] = "";
    sscanf(args, "%127s", pkg);
    char st[16];
    if (app_state(pkg, st, sizeof st) != 0) { reply(ctx->fd, "ERR\n"); return; }
    char line[24];
    snprintf(line, sizeof line, "%s\n", st);
    reply(ctx->fd, line);
}

void cmd_density(conn_ctx *ctx, const char *args) {
    char arg[16] = "";
    sscanf(args, "%15s", arg);
    if (arg[0] == '\0') {
        char out[48];
        reply(ctx->fd, get_density(out, sizeof out) == 0 ? out : "ERR\n");    // get -> "PHYS=.. OVER=.."
    } else reply(ctx->fd, set_density(arg) == 0 ? "OK\n" : "ERR\n");        // set <n>|reset
}

void cmd_fontscale(conn_ctx *ctx, const char *args) {
    char arg[16] = "";
    sscanf(args, "%15s", arg);
    if (arg[0] == '\0') {
        char out[48];
        reply(ctx->fd, get_fontscale(out, sizeof out) == 0 ? out : "ERR\n");  // get -> "SCALE=.."
    } else reply(ctx->fd, set_fontscale(arg) == 0 ? "OK\n" : "ERR\n");      // set <scale>|reset
}

void cmd_bootchime(conn_ctx *ctx, const char *args) {
    char action[16] = "", ring_speaker[16] = "", ring[16] = "", notification[16] = "";
    char ring_stream[16] = "", notification_stream[16] = "", extra[2] = "";
    int fields = sscanf(
        args,
        "%15s %15s %15s %15s %15s %15s %1s",
        action,
        ring_speaker,
        ring,
        notification,
        ring_stream,
        notification_stream,
        extra
    );

    if (fields == 1 && strcmp(action, "SILENCE") == 0) {
        reply(ctx->fd, bootchime_reply(apply_bootchime("0", "0", "0", "0", "0")));
        return;
    }
    if (fields != 6 || strcmp(action, "RESTORE") != 0 ||
        !valid_bootchime_setting(ring_speaker) ||
        !valid_bootchime_setting(ring) ||
        !valid_bootchime_setting(notification) ||
        !valid_bootchime_value(ring_stream) ||
        !valid_bootchime_value(notification_stream)) {
        reply(ctx->fd, "ERR\n");
        return;
    }
    reply(ctx->fd, bootchime_reply(
        apply_bootchime(ring_speaker, ring, notification, ring_stream, notification_stream)
    ));
}

void cmd_gov(conn_ctx *ctx, const char *args) {
    char gov[32] = "";
    sscanf(args, "%31s", gov);
    reply(ctx->fd, set_governor(gov) == 0 ? "OK\n" : "ERR\n");
}

void cmd_zigbeecontain(conn_ctx *ctx, const char *args) {
    if (args[0] != '\0') {
        reply(ctx->fd, "ERR\n");
        return;
    }
    /*
     * Argument-free by design. Admit the captured native, managed, or guardless 4.x Sonoff layout
     * before signalling anything. The process allowlist remains tied to that vendor directory:
     * its guard, zgateway, and the broker launched with that directory's config. No caller-controlled
     * bytes reach this shell. The 4.x run.sh has no stop action, so do not invoke it for containment.
     *
     * Exit 0 = every targeted process disappeared. Exit 2 = at least one survived signalling; the
     * respawner was removed where possible and surviving gateway/broker work was demoted to nice 19
     * plus Android's background cpuset. Any other result means admission/probing failed.
     */
    const char *cmd =
        "d=/vendor/bin/siliconlabs_host; "
        "[ -f \"$d/run.sh\" ] && [ ! -L \"$d/run.sh\" ] && "
        "[ -x \"$d/zgateway\" ] && [ ! -L \"$d/zgateway\" ] || exit 3; "
        "guard=0; if [ -f \"$d/guard_process.sh\" ] && [ ! -L \"$d/guard_process.sh\" ]; then "
          "guard=1; "
          "if [ -f \"$d/run_guard_process.sh\" ] && [ ! -L \"$d/run_guard_process.sh\" ] && "
             "[ -f \"$d/package_version\" ] && [ ! -L \"$d/package_version\" ]; then :; "
          "else [ ! -e \"$d/run_guard_process.sh\" ] && [ ! -L \"$d/run_guard_process.sh\" ] && "
               "[ ! -e \"$d/package_version\" ] && [ ! -L \"$d/package_version\" ] || exit 3; fi; "
        "else "
          "[ ! -L \"$d/guard_process.sh\" ] && [ ! -e \"$d/guard_process.sh\" ] && "
          "[ ! -e \"$d/run_guard_process.sh\" ] && [ ! -L \"$d/run_guard_process.sh\" ] && "
          "[ ! -e \"$d/package_version\" ] && [ ! -L \"$d/package_version\" ] && "
          "[ -x \"$d/mosquitto\" ] && [ ! -L \"$d/mosquitto\" ] || exit 3; "
        "fi; "
        "targets=''; gateways=''; "
        "for p in /proc/[0-9]*; do "
          "pid=${p#/proc/}; exe=$(readlink \"$p/exe\" 2>/dev/null || true); "
          "args=$(tr '\\000' ' ' < \"$p/cmdline\" 2>/dev/null | sed 's/ $//'); "
          "case \"$exe|$args\" in "
            "\"$d/zgateway|$d/zgateway\"|\"$d/zgateway|$d/zgateway \"*) "
              "targets=\"$targets $pid\"; gateways=\"$gateways $pid\" ;; "
            "\"/system/bin/sh|sh $d/guard_process.sh\"|"
            "\"/system/bin/sh|/system/bin/sh $d/guard_process.sh\") "
              "[ \"$guard\" -eq 0 ] || targets=\"$targets $pid\" ;; "
            "\"$d/mosquitto|$d/mosquitto -c $d/mosquitto.conf\"|"
            "\"$d/mosquitto|$d/mosquitto -c $d/mosquitto.conf \"*) "
              "targets=\"$targets $pid\" ;; "
          "esac; "
        "done; "
        "[ -n \"$targets\" ] || exit 0; "
        "kill -TERM $targets 2>/dev/null || true; sleep 1; "
        "alive=''; for pid in $targets; do [ -d /proc/$pid ] && alive=\"$alive $pid\"; done; "
        "[ -z \"$alive\" ] || { kill -KILL $alive 2>/dev/null || true; sleep 1; }; "
        "alive=''; for pid in $targets; do [ -d /proc/$pid ] && alive=\"$alive $pid\"; done; "
        "[ -z \"$alive\" ] && exit 0; "
        "for pid in $gateways; do if [ -d /proc/$pid ]; then "
          "renice 19 -p $pid >/dev/null 2>&1 || true; "
          "[ -w /dev/cpuset/background/tasks ] && echo $pid > /dev/cpuset/background/tasks 2>/dev/null || true; "
        "fi; done; "
        "exit 2";
    int status = sysexec_run_constant(cmd);
    if (command_ok(status)) reply(ctx->fd, "OK\n");
    else if (command_exited_with(status, 2)) reply(ctx->fd, "PARTIAL\n");
    else reply(ctx->fd, "ERR\n");
}

void cmd_screencap(conn_ctx *ctx, const char *args) {
    (void)args;
    screencap_to(ctx->fd);   // raw PNG bytes; server closes on the client half-close → client gets EOF
}

void cmd_stop(conn_ctx *ctx, const char *args) {
    char pkg[128] = "";
    sscanf(args, "%127s", pkg);
    reply(ctx->fd, stop_pkg(pkg) == 0 ? "OK\n" : "ERR\n");
}

void cmd_disable(conn_ctx *ctx, const char *args) {
    char pkg[128] = "";
    sscanf(args, "%127s", pkg);
    reply(ctx->fd, set_pkg_enabled(pkg, 0) == 0 ? "OK\n" : "ERR\n");
}

void cmd_enable(conn_ctx *ctx, const char *args) {
    char pkg[128] = "";
    sscanf(args, "%127s", pkg);
    reply(ctx->fd, set_pkg_enabled(pkg, 1) == 0 ? "OK\n" : "ERR\n");
}

void cmd_overlay(conn_ctx *ctx, const char *args) {
    char pkg[128] = "", mode[16] = "", extra[2] = "";
    int fields = sscanf(args, "%127s %15s %1s", pkg, mode, extra);
    if (fields == 1) {
        char out[32];
        reply(ctx->fd, get_overlay(pkg, out, sizeof out) == 0 ? out : "ERR\n");
    } else if (fields == 2) {
        reply(ctx->fd, set_overlay(pkg, mode) == 0 ? "OK\n" : "ERR\n");
    } else {
        reply(ctx->fd, "ERR\n");
    }
}

// --- identity-migration verbs ---------------------------------------------------------------------
// UNINSTALL and GRANT exist so the successor package never needs `su`. On the six SuperSU panels root
// is granted per uid behind a prompt nobody can answer remotely, so a new application id cannot get a
// root shell at all; this authenticated socket is its only root channel, and these are the two things
// the handover needs root for that no existing verb provides.
//
// Both refuse any package that is not one of the two known ids. That is not a convenience check: it
// is why the verbs are safe to expose at all. Neither takes a free-form permission, appop or setting
// name either — the capability argument selects from a fixed table, so the request can choose WHICH
// of a known set of grants to apply and nothing else.
//
// Note that is_critical_pkg() cannot serve as the gate here: it now flags BOTH our ids precisely so
// STOP/DISABLE can never tear them down, while UNINSTALL's whole purpose is to remove one of them at
// the end of a completed handover. The allowlist is therefore its own, and narrower.

// Remove one of the two known packages. Refuses to uninstall the CALLER's own package: a request to
// delete the requester is either a mistake or a compromised caller, and the migration never needs it
// — the successor removes the legacy app, after its own handover is confirmed. Ordering against the
// HOME role stays with the app, which queries the resolved launcher before asking; duplicating that
// query here would be a second definition of the same rule, and it would have to fail closed on any
// vendor build whose `cmd package` cannot answer it.
static int uninstall_pkg(const char *pkg, enum helper_caller caller) {
    if (!valid_pkg(pkg) || !helper_known_package(pkg)) return -1;
    if (helper_caller_for_package(pkg) == caller) return -1;
    const char *const argv[] = { "pm", "uninstall", pkg, NULL };
    return command_ok(sysexec_run_argv("/system/bin/pm", argv, 1)) ? 0 : -1;
}

void cmd_uninstall(conn_ctx *ctx, const char *args) {
    char pkg[128] = "", extra[2] = "";
    if (sscanf(args, "%127s %1s", pkg, extra) != 1) {
        reply(ctx->fd, "ERR\n");
        return;
    }
    if (guard_maintenance_install_begin() != 0) {
        reply(ctx->fd, "BUSY\n");
        return;
    }
    int removed = uninstall_pkg(pkg, ctx->caller) == 0;
    guard_maintenance_install_end();
    reply(ctx->fd, removed ? "OK\n" : "ERR\n");
}

// The grants the provisioner applies over ADB, which the successor must instead obtain for itself.
// Each entry names the exact actuator and arguments; nothing about a request reaches the command line
// except the already-validated package id.
enum grant_kind { GRANT_PERMISSION, GRANT_APPOP, GRANT_BATTERY, GRANT_ACCESSIBILITY };

static const struct {
    const char *capability;
    enum grant_kind kind;
    const char *name;     // permission or appop name; unused by BATTERY/ACCESSIBILITY
} GRANTS[] = {
    { "NOTIFICATIONS",  GRANT_PERMISSION,    "android.permission.POST_NOTIFICATIONS" },
    { "MICROPHONE",     GRANT_PERMISSION,    "android.permission.RECORD_AUDIO" },
    { "WRITESETTINGS",  GRANT_APPOP,         "WRITE_SETTINGS" },
    { "OVERLAY",        GRANT_APPOP,         "SYSTEM_ALERT_WINDOW" },
    { "BATTERY",        GRANT_BATTERY,       NULL },
    { "ACCESSIBILITY",  GRANT_ACCESSIBILITY, NULL },
};

// `cmd deviceidle` is the modern path and `dumpsys deviceidle` the fallback some vendor builds still
// need; the app probes the same pair. Whitelisting an already-whitelisted package is a no-op, so the
// verb stays idempotent.
static int grant_battery(const char *pkg) {
    char argument[160];
    if (snprintf(argument, sizeof argument, "+%s", pkg) >= (int)sizeof argument) return -1;
    const char *const modern[] = { "cmd", "deviceidle", "whitelist", argument, NULL };
    if (command_ok(sysexec_run_argv("/system/bin/cmd", modern, 1))) return 0;
    const char *const legacy[] = { "dumpsys", "deviceidle", "whitelist", argument, NULL };
    return command_ok(sysexec_run_argv("/system/bin/dumpsys", legacy, 1)) ? 0 : -1;
}

// enabled_accessibility_services is a colon-separated list that other services share, so this is a
// read-modify-write and never a blind overwrite. The value read back is written out again as a single
// argv field, never through a shell, but it is still rejected unless it looks like a component list:
// a setting this daemon cannot account for is not something root should hand back to the framework.
static int accessibility_list_plausible(const char *value) {
    for (const char *p = value; *p; p++) {
        if ((*p >= 'A' && *p <= 'Z') || (*p >= 'a' && *p <= 'z') || (*p >= '0' && *p <= '9')) continue;
        if (*p == '.' || *p == '_' || *p == '/' || *p == ':' || *p == '-') continue;
        return 0;
    }
    return 1;
}

static int grant_accessibility(const char *pkg) {
    // The service class comes from the Gradle namespace, which does NOT move with the applicationId,
    // so the successor's component is its own id plus the unchanged class. The `.input.…` shorthand
    // would resolve against the successor's package id and name a class that does not exist.
    char component[256], shorthand[160];
    if (snprintf(component, sizeof component, "%s/%s", pkg, APP_ACCESSIBILITY_CLASS) >= (int)sizeof component)
        return -1;
    if (snprintf(shorthand, sizeof shorthand, "%s/.input.PanelAccessibilityService", pkg) >= (int)sizeof shorthand)
        return -1;

    char existing[1024];
    const char *const get[] = { "settings", "get", "secure", "enabled_accessibility_services", NULL };
    if (!command_ok(sysexec_capture_argv("/system/bin/settings", get, existing, sizeof existing))) return -1;
    size_t length = strlen(existing);
    while (length > 0 && (existing[length - 1] == '\n' || existing[length - 1] == '\r' ||
                          existing[length - 1] == ' ')) existing[--length] = '\0';
    // `settings get` prints "null" for an unset secure setting; it is not a list containing "null".
    if (strcmp(existing, "null") == 0) existing[0] = '\0', length = 0;
    if (!accessibility_list_plausible(existing)) return -1;

    // Already enabled in either spelling: nothing to write. A retry of an interrupted grant must
    // converge rather than append the component a second time.
    int present = 0;
    char scan[sizeof existing];
    memcpy(scan, existing, length + 1);
    char *save = NULL;
    for (char *entry = strtok_r(scan, ":", &save); entry && !present; entry = strtok_r(NULL, ":", &save))
        present = strcmp(entry, component) == 0 || strcmp(entry, shorthand) == 0;

    if (!present) {
        char updated[sizeof existing + sizeof component];
        int written = length > 0
            ? snprintf(updated, sizeof updated, "%s:%s", existing, component)
            : snprintf(updated, sizeof updated, "%s", component);
        if (written < 0 || (size_t)written >= sizeof updated) return -1;
        const char *const put[] = {
            "settings", "put", "secure", "enabled_accessibility_services", updated, NULL
        };
        if (!command_ok(sysexec_run_argv("/system/bin/settings", put, 1))) return -1;
    }

    const char *const enable[] = { "settings", "put", "secure", "accessibility_enabled", "1", NULL };
    return command_ok(sysexec_run_argv("/system/bin/settings", enable, 1)) ? 0 : -1;
}

static int apply_grant(const char *pkg, const char *capability) {
    if (!valid_pkg(pkg) || !helper_known_package(pkg)) return -1;
    for (size_t i = 0; i < sizeof GRANTS / sizeof GRANTS[0]; i++) {
        if (strcmp(capability, GRANTS[i].capability) != 0) continue;
        switch (GRANTS[i].kind) {
            case GRANT_PERMISSION: {
                const char *const argv[] = { "pm", "grant", pkg, GRANTS[i].name, NULL };
                return command_ok(sysexec_run_argv("/system/bin/pm", argv, 1)) ? 0 : -1;
            }
            case GRANT_APPOP: {
                const char *const argv[] = { "appops", "set", pkg, GRANTS[i].name, "allow", NULL };
                return command_ok(sysexec_run_argv("/system/bin/appops", argv, 1)) ? 0 : -1;
            }
            case GRANT_BATTERY:
                return grant_battery(pkg);
            case GRANT_ACCESSIBILITY:
                return grant_accessibility(pkg);
        }
    }
    return -1;      // an unknown capability is refused, never passed through
}

void cmd_grant(conn_ctx *ctx, const char *args) {
    char pkg[128] = "", capability[32] = "", extra[2] = "";
    if (sscanf(args, "%127s %31s %1s", pkg, capability, extra) != 2) {
        reply(ctx->fd, "ERR\n");
        return;
    }
    reply(ctx->fd, apply_grant(pkg, capability) == 0 ? "OK\n" : "ERR\n");
}

// APK installation is streamed over the authenticated socket. The former pathname INSTALL accepted
// an app-private pathname which root then reopened; even with lexical validation that API cannot bind
// the checked inode to the copied inode and creates avoidable symlink/FIFO/TOCTOU risk. Keep the verb
// as a fail-closed compatibility response, but never open a caller-selected pathname.
static pthread_mutex_t install_lock = PTHREAD_MUTEX_INITIALIZER;

#ifdef HAPANELD_TEST
#define INSTALL_STAGE_PARENT "/tmp"
#define INSTALL_STAGE_NAME   ".hapaneld-helper-test"
#else
#define INSTALL_STAGE_PARENT "/data/local"
#define INSTALL_STAGE_NAME   ".hapaneld-helper"
#endif
#define INSTALL_STAGE_DIR INSTALL_STAGE_PARENT "/" INSTALL_STAGE_NAME
#define INSTALL_APK_NAME "hapaneld-install.apk"
#define INSTALL_STREAM_STAGE INSTALL_STAGE_DIR "/" INSTALL_APK_NAME
#define MAX_INSTALL_STREAM_BYTES (256ULL * 1024ULL * 1024ULL)
#define INSTALL_HEADROOM_BYTES   (32ULL * 1024ULL * 1024ULL)

/* Build a root-owned 0700 compartment below root-controlled /data/local (production). Opening the
 * directory itself with O_NOFOLLOW rejects a preplanted link. Do not put this below /data/local/tmp:
 * Android's shell user owns that parent and could rename even a root-owned child. Both streaming and
 * legacy cp may safely reopen the stage pathname inside this protected directory. */
static int open_install_dir(void) {
    int parent = open(INSTALL_STAGE_PARENT, O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC);
    if (parent < 0) return -1;
    struct stat parent_st;
    if (fstat(parent, &parent_st) != 0 || !S_ISDIR(parent_st.st_mode) ||
        (parent_st.st_uid != 0 && parent_st.st_uid != geteuid()) ||
        ((parent_st.st_mode & (S_IWGRP | S_IWOTH)) != 0 && (parent_st.st_mode & S_ISVTX) == 0)) {
        close(parent);
        return -1;
    }
    if (mkdirat(parent, INSTALL_STAGE_NAME, 0700) != 0 && errno != EEXIST) {
        close(parent);
        return -1;
    }
    int fd = openat(parent, INSTALL_STAGE_NAME, O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC);
    close(parent);
    if (fd < 0) return -1;
    struct stat st;
    if (fstat(fd, &st) != 0 || !S_ISDIR(st.st_mode) || st.st_uid != geteuid() || fchmod(fd, 0700) != 0) {
        close(fd);
        return -1;
    }
    return fd;
}

static int open_install_stage(void) {
    int dir = open_install_dir();
    if (dir < 0) return -1;
    if (unlinkat(dir, INSTALL_APK_NAME, 0) != 0 && errno != ENOENT) {
        close(dir);
        return -1;
    }
    int fd = openat(
        dir,
        INSTALL_APK_NAME,
        O_WRONLY | O_CREAT | O_EXCL | O_NOFOLLOW | O_CLOEXEC,
        0644
    );
    close(dir);
    if (fd < 0) return -1;
    struct stat st;
    if (fstat(fd, &st) != 0 || !S_ISREG(st.st_mode) || st.st_uid != geteuid() || st.st_nlink != 1) {
        close(fd);
        unlink(INSTALL_STREAM_STAGE);
        return -1;
    }
    return fd;
}

void cmd_install(conn_ctx *ctx, const char *args) {
    (void)args;
    reply(ctx->fd, "ERR\n");
}

static int parse_install_stream_size(const char *args, uint64_t *size) {
    if (!args || !*args) return -1;
    errno = 0;
    char *end = NULL;
    unsigned long long value = strtoull(args, &end, 10);
    if (errno != 0 || end == args || *end != '\0' || value == 0 || value > MAX_INSTALL_STREAM_BYTES)
        return -1;
    *size = (uint64_t)value;
    return 0;
}

static int copy_exact(int input, int output, uint64_t remaining) {
    char buf[65536];
    while (remaining > 0) {
        size_t wanted = remaining < sizeof buf ? (size_t)remaining : sizeof buf;
        ssize_t n = read(input, buf, wanted);
        if (n < 0) {
            if (errno == EINTR) continue;
            return -1;
        }
        if (n == 0) return -1;
        size_t offset = 0;
        while (offset < (size_t)n) {
            ssize_t written = write(output, buf + offset, (size_t)n - offset);
            if (written < 0) {
                if (errno == EINTR) continue;
                return -1;
            }
            if (written == 0) return -1;
            offset += (size_t)written;
        }
        remaining -= (uint64_t)n;
    }
    char extra;
    for (;;) {
        ssize_t n = read(input, &extra, 1);
        if (n < 0 && errno == EINTR) continue;
        return n == 0 ? 0 : -1;
    }
}

// Two-phase upload keeps binary bytes out of server.c's line accumulator: the client sends only this
// command, waits for READY, then writes exactly <bytes>. The app opened its own private file, while
// this SELinux domain reads only the authenticated socket and writes the root staging path.
void cmd_installstream(conn_ctx *ctx, const char *args) {
    uint64_t size;
    if (parse_install_stream_size(args, &size) != 0) {
        reply(ctx->fd, "STREAMERR\n");
        return;
    }
    if (guard_maintenance_install_begin() != 0) {
        reply(ctx->fd, "BUSY\n");
        return;
    }
    if (pthread_mutex_trylock(&install_lock) != 0) {
        guard_maintenance_install_end();
        reply(ctx->fd, "BUSY\n");
        return;
    }

    struct statvfs space;
    if (statvfs(INSTALL_STAGE_PARENT, &space) != 0 ||
        space.f_bavail == 0 || space.f_frsize == 0 ||
        size > UINT64_MAX - INSTALL_HEADROOM_BYTES ||
        (uint64_t)space.f_bavail > UINT64_MAX / (uint64_t)space.f_frsize ||
        (uint64_t)space.f_bavail * (uint64_t)space.f_frsize < size + INSTALL_HEADROOM_BYTES) {
        pthread_mutex_unlock(&install_lock);
        guard_maintenance_install_end();
        reply(ctx->fd, "STREAMERR\n");
        return;
    }

    int output = open_install_stage();
    if (output < 0 || fchmod(output, 0644) != 0) {
        if (output >= 0) close(output);
        unlink(INSTALL_STREAM_STAGE);
        pthread_mutex_unlock(&install_lock);
        guard_maintenance_install_end();
        reply(ctx->fd, "STREAMERR\n");
        return;
    }

    reply(ctx->fd, "READY\n");
    // EOF is part of the frame: it proves the client sent neither fewer nor more bytes than declared.
    int copied = copy_exact(ctx->fd, output, size) == 0;
    int synced = copied && fsync(output) == 0;
    int closed = close(output) == 0;
    int installed = 0;
    if (copied && synced && closed) {
        const char *const argv[] = { "pm", "install", "-r", "-d", INSTALL_STREAM_STAGE, NULL };
        installed = sysexec_run_argv("/system/bin/pm", argv, 1) == 0;
    }
    unlink(INSTALL_STREAM_STAGE);
    pthread_mutex_unlock(&install_lock);
    guard_maintenance_install_end();
    reply(ctx->fd, installed ? "OK\n" : "ERR\n");
}

// Compatibility acknowledgement for clients which clean retained inputs from the removed pathname
// installer. No pathname is opened and the streamed installer has no app-private retained input.
void cmd_installgc(conn_ctx *ctx, const char *args) {
    char path[256] = "";
    sscanf(args, "%255s", path);
    if (!valid_apk_path(path)) {
        reply(ctx->fd, "ERR\n");
        return;
    }
    if (pthread_mutex_trylock(&install_lock) != 0) {
        reply(ctx->fd, "BUSY\n");
        return;
    }
    pthread_mutex_unlock(&install_lock);
    reply(ctx->fd, "OK\n");
}
