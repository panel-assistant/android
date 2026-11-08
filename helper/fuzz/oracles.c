// Semantic rules at production dispatch, reply and execution seams. These wrappers observe
// intended effects before the no-exec stub runs; truncated stub histories cannot hide violations.
#include "cmd.h"
#include "sysexec.h"
#include "sha256.h"
#include "identity.h"
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

static char verb[32];
static int active, checked_fd;
static size_t reply_bytes, reply_lines;
static unsigned char reply_last;

static void violation(const char *rule) {
    fprintf(stderr, "FAIL: fuzz-oracle: %s (verb=%s)\n", rule, verb);
    abort(); // libFuzzer saves the input only for a crash, not an exit
}

// Every package identity the app has shipped under (data, not the guard under test).
static int own_app(const char *package) {
    for (size_t i = 0; i < HELPER_APP_PACKAGE_COUNT; i++)
        if (!strcmp(package, HELPER_APP_PACKAGES[i].package)) return 1;
    return 0;
}

// Independent policy list: do not delegate the oracle to the guard it is checking.
static int critical(const char *package) {
    static const char *const packages[] = {
        "android", "com.android.systemui", "com.android.settings", "com.android.phone",
    };
    for (size_t i = 0; i < sizeof packages / sizeof packages[0]; i++)
        if (!strcmp(package, packages[i])) return 1;
    return own_app(package);
}

// Default deny: any exec naming a critical package fails unless it is one of the explicitly
// harmless forms below. A future verb is checked without anyone remembering to list it.
static int harmless_critical_form(const char *base, const char *const argv[], size_t argc) {
    // Enabling a package only restores it (`pm enable [--user N] <pkg>`).
    if (!strcmp(base, "pm") && argc >= 3 && !strcmp(argv[1], "enable")) return 1;
    // The app granting itself a runtime permission (`pm grant <own app> <permission>`).
    if (!strcmp(base, "pm") && argc == 4 && !strcmp(argv[1], "grant") && own_app(argv[2])) return 1;
    // `appops set <pkg> <op> allow|default` widens or resets; only deny/ignore can harm.
    if (!strcmp(base, "appops") && argc == 5 && !strcmp(argv[1], "set") &&
            (!strcmp(argv[4], "allow") || !strcmp(argv[4], "default"))) return 1;
    return 0;
}

static void check_argv(const char *path, const char *const argv[]) {
    const char *base = strrchr(path, '/'); base = base ? base + 1 : path;
    // Ordinary commands use structural argv. SHELL executes in the recovery owner, outside
    // sysexec; granting it a bypass here would conceal accidental alternate execution routes.
    if (!strcmp(base, "sh") || !strcmp(base, "bash") || !strcmp(base, "dash") ||
            !strcmp(base, "su") || !strcmp(base, "toybox") || !strcmp(base, "busybox"))
        violation("caller-shell");
    size_t argc = 0; while (argv[argc]) argc++;
    for (size_t i = 0; i < argc; i++)
        if (critical(argv[i]) && !harmless_critical_form(base, argv, argc))
            violation("critical-package");
}

static int line_reply(void) {
    // These verbs explicitly frame multiple lines, binary output, a stream handshake or an
    // asynchronous lifecycle-owner response. Other verbs must finish exactly one text line.
    static const char *const streams[] = {
        "SCREENCAP", "LOGCAT", "LOGCATDUMP", "PERFDUMP", "COMPANIONBACKUP",
        "COMPANIONRESTORE", "INSTALLSTREAM", "INSTALLSTREAMUPGRADE", "GUARDSTREAM",
        "ARM", "BEAT", "RECOVER", "STATUS", "SHELL"
    };
    for (size_t i = 0; i < sizeof streams / sizeof streams[0]; i++)
        if (!strcmp(verb, streams[i])) return 0;
    return 1;
}

void __real_dispatch(conn_ctx *, char *);
void __wrap_dispatch(conn_ctx *ctx, char *line) {
    const char *p = line;
    while (*p == ' ' || *p == '\t') p++;
    size_t n = strcspn(p, " \t");
    if (n >= sizeof verb) n = sizeof verb - 1;
    memcpy(verb, p, n); verb[n] = 0;
    checked_fd = ctx->fd;
    reply_bytes = reply_lines = 0; reply_last = 0; active = 1;
    __real_dispatch(ctx, line);
    active = 0;
    if (line_reply() && (!reply_bytes || reply_lines != 1 || reply_last != '\n'))
        violation("reply-line");
}

int __real_reply(int, const char *);
int __wrap_reply(int fd, const char *text) {
    if (active && fd == checked_fd && line_reply()) {
        for (const unsigned char *p = (const unsigned char *)text; *p; p++) {
            if ((*p < 32 && *p != '\n' && *p != '\t') || *p == 127)
                violation("reply-line");
            reply_bytes++;
            if (*p == '\n') reply_lines++;
            reply_last = *p;
        }
    }
    return __real_reply(fd, text);
}

int __real_sysexec_run_constant(const char *);
int __wrap_sysexec_run_constant(const char *program) {
    // The sole ordinary shell program is an audited argument-free containment script. A hash
    // pins the actual executed bytes, so interpolation (including plain alphanumerics) fails.
    hapaneld_sha256 state; unsigned char digest[32]; char hex[65];
    hapaneld_sha256_init(&state); hapaneld_sha256_update(&state, program, strlen(program));
    hapaneld_sha256_final(&state, digest); hapaneld_sha256_hex(digest, hex);
    if (strcmp(verb, "ZIGBEECONTAIN") ||
        strcmp(hex, "256f813116a2b1a4b35f2d60741787b66e8a0c2eb63700cf019f4ed00231f391"))
        violation("caller-shell");
    return __real_sysexec_run_constant(program);
}

#define ARGV_WRAPPER(name, tail, call) \
    int __real_##name(const char *, const char *const [], tail); \
    int __wrap_##name(const char *path, const char *const argv[], tail) { \
        check_argv(path, argv); return __real_##name(path, argv, call); \
    }
ARGV_WRAPPER(sysexec_run_argv, int quiet, quiet)
ARGV_WRAPPER(sysexec_stream_argv, int fd, fd)
// Multiple comma-bearing signatures are clearer written explicitly.
int __real_sysexec_run_argv_timeout(const char *, const char *const [], int, unsigned, int *);
int __wrap_sysexec_run_argv_timeout(const char *p, const char *const a[], int q, unsigned t, int *o) {
    check_argv(p, a); return __real_sysexec_run_argv_timeout(p, a, q, t, o);
}
int __real_sysexec_run_argv_deadline(const char *, const char *const [], int, unsigned, unsigned *);
int __wrap_sysexec_run_argv_deadline(const char *p, const char *const a[], int q, unsigned t, unsigned *o) {
    check_argv(p, a); return __real_sysexec_run_argv_deadline(p, a, q, t, o);
}
int __real_sysexec_capture_argv(const char *, const char *const [], char *, size_t);
int __wrap_sysexec_capture_argv(const char *p, const char *const a[], char *o, size_t n) {
    check_argv(p, a); return __real_sysexec_capture_argv(p, a, o, n);
}
int __real_sysexec_capture_argv_timeout(const char *, const char *const [], char *, size_t, unsigned, int *);
int __wrap_sysexec_capture_argv_timeout(const char *p, const char *const a[], char *o, size_t n, unsigned t, int *x) {
    check_argv(p, a); return __real_sysexec_capture_argv_timeout(p, a, o, n, t, x);
}
int __real_sysexec_start_argv(const char *, const char *const [], int, pid_t *);
int __wrap_sysexec_start_argv(const char *p, const char *const a[], int q, pid_t *pid) {
    check_argv(p, a); return __real_sysexec_start_argv(p, a, q, pid);
}
int __real_sysexec_start_stdout_argv(const char *, const char *const [], pid_t *, int *);
int __wrap_sysexec_start_stdout_argv(const char *p, const char *const a[], pid_t *pid, int *fd) {
    check_argv(p, a); return __real_sysexec_start_stdout_argv(p, a, pid, fd);
}
