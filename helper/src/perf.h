// PERFDUMP — a CPU/load/temp/gpu/process snapshot for sandboxed apps. An untrusted_app is SELinux-
// denied /proc/stat, /proc/loadavg, thermal, and other pids' stat, so it can't compute CPU/load/top
// itself; root here can. One marker-delimited snapshot is streamed (client half-closes then reads to
// EOF, like SCREENCAP); PerfReader on the app side parses it. Pure file reads — no shell, no exec.
#ifndef HAPANELD_PERF_H
#define HAPANELD_PERF_H

#include <stddef.h>
#include "cmd.h"

void cmd_perfdump(conn_ctx *ctx, const char *args);  // PERFDUMP

// Exposed for unit tests: utime(field14)+stime(field15) from a /proc/<pid>[/task/<tid>]/stat buffer;
// comm (the text in the parens) into [comm] when non-NULL. Returns -1 on a malformed buffer.
long stat_jiffies(const char *buf, char *comm, size_t commsz);

// CPU jiffies and resident pages from one process stat line. Returns 0 on success, -1 on malformed
// input. rss_pages is field 24 and may be zero; it may be null when only CPU jiffies are needed.
int stat_process_metrics(const char *buf, char *comm, size_t commsz,
                         long *jiffies, long *rss_pages);

// Match a running bridge payload in a NUL-separated /proc/<pid>/cmdline, not its runit supervisor.
// Return -1 when a relative Node entrypoint needs an unreadable cwd to decide.
int is_panel_bridge_cmdline(const char *buf, size_t length, const char *cwd);

#endif
