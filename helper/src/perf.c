#include "perf.h"
#include "util.h"

#include <ctype.h>
#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <unistd.h>

int stat_process_metrics(const char *buf, char *comm, size_t commsz,
                         long *jiffies, long *rss_pages) {
    if (!jiffies) return -1;
    const char *lp = strchr(buf, '(');
    const char *rp = strrchr(buf, ')');
    if (!lp || !rp || rp < lp) return -1;
    if (comm && commsz) {
        size_t L = (size_t)(rp - lp - 1);
        if (L >= commsz) L = commsz - 1;
        memcpy(comm, lp + 1, L); comm[L] = '\0';
    }
    // Fields after ')': idx0 = state (a char), utime = idx11, stime = idx12, rss = idx21. Walk tokens
    // as strings (state isn't numeric) and convert only the fields needed by the CPU/RAM rankings.
    const char *p = rp + 1;
    long utime = -1, stime = -1, rss = -1;
    int idx = 0;
    while (*p) {
        while (*p == ' ') p++;
        if (!*p) break;
        if (idx == 11) utime = strtol(p, NULL, 10);
        else if (idx == 12) {
            stime = strtol(p, NULL, 10);
            if (!rss_pages) break;
        }
        else if (idx == 21) { rss = strtol(p, NULL, 10); break; }
        while (*p && *p != ' ') p++;
        idx++;
    }
    if (utime < 0 || stime < 0 || (rss_pages && rss < 0)) return -1;
    *jiffies = utime + stime;
    if (rss_pages) *rss_pages = rss;
    return 0;
}

long stat_jiffies(const char *buf, char *comm, size_t commsz) {
    long jiffies = -1;
    return stat_process_metrics(buf, comm, commsz, &jiffies, NULL) == 0 ? jiffies : -1;
}

static int ends_with(const char *text, const char *suffix) {
    size_t n = strlen(text), s = strlen(suffix);
    return n >= s && !strcmp(text + n - s, suffix);
}

int is_panel_bridge_cmdline(const char *buf, size_t length, const char *cwd) {
    if (!buf || length == 0) return 0;
    size_t exe_len = strnlen(buf, length);
    if (exe_len == length) return 0;
    const char *exe = buf;
    for (size_t i = 0; i < exe_len; i++) if (buf[i] == '/') exe = buf + i + 1;
    if (!strcmp(exe, "zigbee2mqtt") || !strcmp(exe, "zigbee2cube")) return 1;
    if (strcmp(exe, "node")) return 0;
    const char *script = buf + exe_len + 1;
    size_t left = length - exe_len - 1;
    size_t script_len = strnlen(script, left);
    if (script_len == 0 || script_len == left) return 0;
    if (script_len < 8 || strcmp(script + script_len - 8, "index.js")) return 0;
    if (ends_with(script, "/zigbee2mqtt/index.js") ||
        ends_with(script, "/zigbee2cube/index.js") ||
        ends_with(script, "/05cube-ts/dist/index.js")) return 1;
    if (script[0] == '/') return 0;
    if (!cwd || !*cwd) return -1;
    return (!strcmp(script, "index.js") &&
            (ends_with(cwd, "/zigbee2mqtt") || ends_with(cwd, "/zigbee2cube") ||
             ends_with(cwd, "/05cube-ts/dist"))) ||
           (!strcmp(script, "dist/index.js") && ends_with(cwd, "/05cube-ts"));
}

void cmd_perfdump(conn_ctx *ctx, const char *args) {
    int scan_bridge = 0;
    uid_t termux_uid = 0;
    if (args && *args) {
        if (strncmp(args, "BRIDGE ", 7)) { reply(ctx->fd, "ERR\n"); return; }
        const char *number = args + 7;
        char *end = NULL;
        unsigned long parsed = strtoul(number, &end, 10);
        if (!*number || !end || *end || parsed > 9999999UL) {
            reply(ctx->fd, "ERR\n"); return;
        }
        termux_uid = (uid_t)parsed;
        scan_bridge = 1;
    }
    int fd = ctx->fd;
    char out[320];
    reply(fd, "@STAT\n");
    cat_to(fd, "/proc/stat");

    char load[256]; first_line("/proc/loadavg", load, sizeof load);
    snprintf(out, sizeof out, "@LOAD %s\n", load[0] ? load : "-"); reply(fd, out);

    long maxt = -1;                              // max thermal_zone*/temp (millidegrees)
    DIR *dt = opendir("/sys/class/thermal");
    if (dt) {
        struct dirent *e; char path[256], b[32];
        while ((e = readdir(dt))) {
            if (strncmp(e->d_name, "thermal_zone", 12) != 0) continue;
            snprintf(path, sizeof path, "/sys/class/thermal/%s/temp", e->d_name);
            first_line(path, b, sizeof b);
            if (b[0]) { long t = strtol(b, NULL, 10); if (t > maxt) maxt = t; }
        }
        closedir(dt);
    }
    snprintf(out, sizeof out, "@TEMP %ld\n", maxt); reply(fd, out);

    char gpu[64] = "-";                          // first devfreq *gpu*/load ("<load>@<freq>Hz")
    DIR *dg = opendir("/sys/class/devfreq");
    if (dg) {
        struct dirent *e; char path[256];
        while ((e = readdir(dg))) {
            if (e->d_name[0] == '.' || !strstr(e->d_name, "gpu")) continue;
            snprintf(path, sizeof path, "/sys/class/devfreq/%s/load", e->d_name);
            first_line(path, gpu, sizeof gpu);
            if (gpu[0]) break; else snprintf(gpu, sizeof gpu, "-");
        }
        closedir(dg);
    }
    snprintf(out, sizeof out, "@GPU %s\n", gpu); reply(fd, out);

    reply(fd, "@PROC\n");                        // pid \t utime+stime \t comm; collect renderer pids
    int rend[32]; int rn = 0;
    int bridge_seen = 0;
    DIR *dp = opendir("/proc");
    if (!dp) bridge_seen = -1;
    if (dp) {
        struct dirent *e;
        while ((e = readdir(dp))) {
            if (!valid_num(e->d_name)) continue;
            char path[64], b[1024], comm[64];
            int termux_owned = 0;
            if (scan_bridge) {
                snprintf(path, sizeof path, "/proc/%s", e->d_name);
                struct stat owner;
                if (stat(path, &owner) == 0) termux_owned = owner.st_uid == termux_uid;
                else if (errno != ENOENT && bridge_seen == 0) bridge_seen = -1;
            }
            snprintf(path, sizeof path, "/proc/%s/stat", e->d_name);
            int f = open(path, O_RDONLY | O_CLOEXEC);
            if (f < 0) { if (termux_owned && bridge_seen == 0) bridge_seen = -1; continue; }
            ssize_t n = read(f, b, sizeof b - 1); close(f);
            if (n <= 0) { if (termux_owned && bridge_seen == 0) bridge_seen = -1; continue; }
            b[n] = '\0';
            long j, rss_pages;
            if (stat_process_metrics(b, comm, sizeof comm, &j, &rss_pages) != 0) {
                if (termux_owned && bridge_seen == 0) bridge_seen = -1;
                continue;
            }
            // Full name from cmdline argv0 (comm is truncated to 15 chars, losing the head — "axlyth.hapaneld"
            // not "io.github.maxlyth.hapaneld"); comm is the fallback for kernel threads (empty cmdline) and
            // isolated renderers (cmdline unreadable in the su domain).
            char cl[1024], name[160];
            snprintf(path, sizeof path, "/proc/%s/cmdline", e->d_name);
            int cf = open(path, O_RDONLY | O_CLOEXEC);
            ssize_t cn = (cf >= 0) ? read(cf, cl, scan_bridge ? sizeof cl - 1 : 159) : -1;
            if (cf >= 0) close(cf);
            if (termux_owned) {
                    if (cn <= 0 || cn == (ssize_t)sizeof cl - 1) {
                        if (bridge_seen == 0 && (strstr(comm, "node") || strstr(comm, "zigbee")))
                            bridge_seen = -1;
                    } else {
                        char cwd[512] = "";
                        snprintf(path, sizeof path, "/proc/%s/cwd", e->d_name);
                        ssize_t cw = readlink(path, cwd, sizeof cwd - 1);
                        if (cw >= 0) cwd[cw] = '\0';
                        int match = is_panel_bridge_cmdline(cl, (size_t)cn, cwd);
                        if (match > 0) bridge_seen = 1;
                        else if (match < 0 && bridge_seen == 0) bridge_seen = -1;
                    }
            }
            if (cn > 0) { cl[cn] = '\0'; snprintf(name, sizeof name, "%s", cl); }  // "%s" stops at argv0's NUL
            else snprintf(name, sizeof name, "%s", comm);
            for (char *t = name; *t; t++) if (*t == '\t') *t = ' ';                // tabs would break parsing
            // RSS is appended so older apps still parse pid/jiffies/name exactly as before.
            snprintf(out, sizeof out, "%s\t%ld\t%s\t%ld\n", e->d_name, j, name, rss_pages); reply(fd, out);
            // Chromium renderer: main-thread comm is the truncated tail of "…SandboxedProcessService0:N"
            // (e.g. "ocessService0:1") — match "cessService". (cmdline has the full name but the su domain
            // can't read an isolated process's cmdline; comm from stat is readable.)
            if (rn < 32 && strstr(comm, "cessService")) rend[rn++] = atoi(e->d_name);
        }
        closedir(dp);
    }

    if (scan_bridge) {
        snprintf(out, sizeof out, "@BRIDGE %d\n", bridge_seen); reply(fd, out);
    }
    reply(fd, "@REND\n");                        // CrRendererMain thread jiffies per renderer (pid \t jiffies)
    for (int i = 0; i < rn; i++) {
        char tdir[64]; snprintf(tdir, sizeof tdir, "/proc/%d/task", rend[i]);
        DIR *td = opendir(tdir); if (!td) continue;
        struct dirent *te;
        while ((te = readdir(td))) {
            if (!valid_num(te->d_name)) continue;
            char path[128], cb[32];
            snprintf(path, sizeof path, "/proc/%d/task/%s/comm", rend[i], te->d_name);
            first_line(path, cb, sizeof cb);
            if (strcmp(cb, "CrRendererMain") != 0) continue;
            snprintf(path, sizeof path, "/proc/%d/task/%s/stat", rend[i], te->d_name);
            char sb[1024]; int sf = open(path, O_RDONLY | O_CLOEXEC); if (sf < 0) continue;
            ssize_t sn = read(sf, sb, sizeof sb - 1); close(sf);
            if (sn <= 0) continue;
            sb[sn] = '\0';
            long j = stat_jiffies(sb, NULL, 0); if (j < 0) continue;
            snprintf(out, sizeof out, "%d\t%ld\n", rend[i], j); reply(fd, out);
            break;
        }
        closedir(td);
    }
    reply(fd, "@END\n");
}
