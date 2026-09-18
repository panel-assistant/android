#include "util.h"

#include "identity.h"

#include <errno.h>
#include <fcntl.h>
#include <string.h>
#include <sys/socket.h>
#include <unistd.h>

int clamp(int v) { return v < 0 ? 0 : (v > 255 ? 255 : v); }

int write_node(const char *path, const char *val) {
    int fd = open(path, O_WRONLY | O_CLOEXEC);
    if (fd < 0) return -1;
    ssize_t n = write(fd, val, strlen(val));
    close(fd);
    return n < 0 ? -1 : 0;
}

int write_complete(int fd, const void *bytes, size_t size) {
    const char *p = bytes;
    while (size > 0) {
        ssize_t n = write(fd, p, size);
        if (n < 0 && errno == EINTR) continue;
        if (n <= 0) return -1;
        p += (size_t)n;
        size -= (size_t)n;
    }
    return 0;
}

static int abort_failed_socket(int fd) {
    // ENOTSOCK is expected when tests or a non-socket caller reuse this primitive; the original write
    // failure remains the useful result. For a connection, SHUT_RDWR wakes server_serve's read loop.
    (void)shutdown(fd, SHUT_RDWR);
    return -1;
}

int reply(int fd, const char *s) {
    return write_complete(fd, s, strlen(s)) == 0 ? 0 : abort_failed_socket(fd);
}

void first_line(const char *path, char *dst, size_t dstsz) {
    dst[0] = '\0';
    int fd = open(path, O_RDONLY | O_CLOEXEC);
    if (fd < 0) return;
    ssize_t n = read(fd, dst, dstsz - 1);
    close(fd);
    if (n <= 0) { dst[0] = '\0'; return; }
    dst[n] = '\0';
    char *nl = strchr(dst, '\n'); if (nl) *nl = '\0';
}

int cat_to(int out, const char *path) {
    int fd = open(path, O_RDONLY | O_CLOEXEC);
    if (fd < 0) return -1;
    char b[4096];
    int result = 0;
    for (;;) {
        ssize_t n = read(fd, b, sizeof b);
        if (n < 0 && errno == EINTR) continue;
        if (n < 0) { result = -1; break; }
        if (n == 0) break;
        if (write_complete(out, b, (size_t)n) != 0) {
            result = abort_failed_socket(out);
            break;
        }
    }
    close(fd);
    return result;
}

// Android package name chars only — rejects malformed targets before structural argv execution.
int valid_pkg(const char *s) {
    if (!*s) return 0;
    for (const char *p = s; *p; p++)
        if (!((*p >= 'a' && *p <= 'z') || (*p >= 'A' && *p <= 'Z') ||
              (*p >= '0' && *p <= '9') || *p == '.' || *p == '_'))
            return 0;
    return 1;
}

// Component (pkg/class) — package chars plus the '/' separator.
int valid_component(const char *s) {
    if (!*s) return 0;
    for (const char *p = s; *p; p++)
        if (!((*p >= 'a' && *p <= 'z') || (*p >= 'A' && *p <= 'Z') ||
              (*p >= '0' && *p <= '9') || *p == '.' || *p == '_' || *p == '/'))
            return 0;
    return 1;
}

// Numeric (e.g. display-density arg) — decimal digits only.
int valid_num(const char *s) {
    if (!*s) return 0;
    for (const char *p = s; *p; p++) if (!(*p >= '0' && *p <= '9')) return 0;
    return 1;
}

// Decimal number (e.g. font-scale arg "0.85"/"1.15") — digits with at most one dot, at least one
// digit. No sign/exponent/whitespace; the validated value remains a single argv field.
int valid_decimal(const char *s) {
    if (!*s) return 0;
    int dot = 0, digit = 0;
    for (const char *p = s; *p; p++) {
        if (*p == '.') { if (dot++) return 0; }
        else if (*p >= '0' && *p <= '9') digit = 1;
        else return 0;
    }
    return digit;
}

// Packages this daemon will NEVER stop / disable / overlay-deny, even when asked — the privileged
// backstop against a buggy or hostile caller bricking the panel by tearing down the system UI,
// Settings, telephony, the framework, or ha-paneld's own controller. The app enforces its own block
// list too; this is defense-in-depth at the one place that actually holds root. (Re-enabling — ENABLE
// / overlay allow — is always permitted, so a panel can never get stuck disabled.)
// Both ha-paneld package ids are critical for the whole transition window: while the successor is
// installed beside the legacy package, tearing either one down strands the migration — the legacy app
// still owns the launcher and the state to hand over, and the successor is what will own them next.
int is_critical_pkg(const char *s) {
    static const char *const CRIT[] = {
        "android",
        "com.android.systemui",
        "com.android.settings",
        "com.android.phone",
    };
    for (size_t i = 0; i < sizeof CRIT / sizeof CRIT[0]; i++)
        if (strcmp(s, CRIT[i]) == 0) return 1;
    return helper_known_package(s);
}

// Lowercase-alnum CPU governor names (schedutil/performance/powersave/interactive/ondemand…).
int valid_gov(const char *s) {
    if (!*s) return 0;
    for (const char *p = s; *p; p++)
        if (!((*p >= 'a' && *p <= 'z') || (*p >= '0' && *p <= '9') || *p == '_')) return 0;
    return 1;
}

// Even-length hex string of at most 508 characters (= 254 raw dataset bytes). Only [0-9a-fA-F] are
// accepted — safe to pass as-is to a shell command.
int valid_hex_dataset(const char *s) {
    if (!s || !*s) return 0;
    size_t n = strlen(s);
    if (n % 2 != 0 || n > 508) return 0;
    for (size_t i = 0; i < n; i++) {
        char c = s[i];
        if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F')))
            return 0;
    }
    return 1;
}

// Absolute path to a GBL firmware image: must start with '/', contain no ".." component, contain no
// single-quote (the path is interpolated inside a single-quoted cp argument), and end with ".gbl".
int valid_gbl_path(const char *s) {
    if (!s || s[0] != '/') return 0;
    if (strstr(s, "..")) return 0;
    size_t n = strlen(s);
    if (n < 5 || strcmp(s + n - 4, ".gbl") != 0) return 0;
    for (size_t i = 0; i < n; i++) if (s[i] == '\'') return 0;
    return 1;
}

// INSTALL source path — only an .apk staged by ha-paneld INSIDE its own data dir. Peer-uid already
// restricts callers to ha-paneld's uid; this keeps a compromised caller from pointing the root install
// at an arbitrary /system, /sdcard or vendor APK. No traversal, no quote (shell-safe).
// A path is under a package's own data dir when it starts with that dir followed by a separator —
// the separator matters, or "/data/data/io.panelassistant.androidX/…" would pass as the successor's.
static int under_directory(const char *path, const char *directory) {
    size_t n = strlen(directory);
    return strncmp(path, directory, n) == 0 && path[n] == '/';
}

int valid_apk_path(const char *s) {
    if (!s || s[0] != '/') return 0;
    if (strstr(s, "..")) return 0;
    // Either known package's own data dir, in the legacy (/data/data) or multi-user (/data/user/0)
    // form — getCacheDir() returns the /data/user/0 form on API 24+. Both ids are accepted because
    // during the migration either app may hold a retained input of its own; neither can name a path
    // outside the two, and peer-uid authorisation already bounds who may ask at all.
    int known = 0;
    for (size_t i = 0; i < HELPER_APP_PACKAGE_COUNT && !known; i++)
        known = under_directory(s, HELPER_APP_PACKAGES[i].data_dir) ||
                under_directory(s, HELPER_APP_PACKAGES[i].user_dir);
    if (!known) return 0;
    size_t n = strlen(s);
    if (n < 5 || strcmp(s + n - 4, ".apk") != 0) return 0;
    for (size_t i = 0; i < n; i++) if (s[i] == '\'') return 0;
    return 1;
}
