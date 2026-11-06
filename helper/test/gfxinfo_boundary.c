// Drive real dispatch with complete command output supplied at the external capture boundary.
// This fixture also links unchanged against the pre-GFXINFO core: positive replies fail there.
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <unistd.h>
#include "dispatch.h"

#define HEADER "** Graphics info for pid 42 [io.panelassistant.android] **\n"
#define CAPTURE_LIMIT (128u * 1024u)
#define CHECK(c, text) do { if (!(c)) { printf("FAIL: %s (%s:%d)\n", text, __FILE__, __LINE__); failures++; } } while (0)
static int failures, captures, capture_status, override_used;
static const void *capture_bytes;
static size_t capture_size, reported_used;

int __wrap_sysexec_capture_gfxinfo(const char *package, char *output, size_t capacity, size_t *used) {
    captures++;
    CHECK(package && !strcmp(package, "io.panelassistant.android"), "GFXINFO capture uses only the authenticated successor package");
    CHECK(capacity == CAPTURE_LIMIT + 1, "GFXINFO capture reserves the complete bound and overflow probe");
    size_t n = capture_size < capacity ? capture_size : capacity;
    if (n) memcpy(output, capture_bytes, n);
    if (n < capacity) output[n] = 0;
    *used = override_used ? reported_used : capture_size;
    return capture_status;
}

static void command_as(const char *request, enum helper_caller caller, char out[256]) {
    int pair[2];
    if (socketpair(AF_UNIX, SOCK_STREAM, 0, pair) != 0) {
        CHECK(0, "GFXINFO dispatch fixture socket opens"); out[0] = 0; return;
    }
    conn_ctx ctx = { .fd = pair[0], .caller = caller };
    char input[256]; snprintf(input, sizeof input, "%s", request);
    dispatch(&ctx, input); shutdown(pair[0], SHUT_WR);
    size_t used = 0;
    while (used < 255) {
        ssize_t n = read(pair[1], out + used, 255 - used);
        if (n <= 0) break;
        used += (size_t)n;
    }
    out[used] = 0; close(pair[0]); close(pair[1]);
}
static void command(const char *request, char out[256]) {
    command_as(request, HELPER_CALLER_SUCCESSOR, out);
}
static void raw(const void *bytes, size_t size) {
    capture_bytes = bytes; capture_size = size; capture_status = 0; override_used = 0;
}
static void expect(const char *expected, const char *assertion) {
    char out[256]; command("GFXINFO SELF", out);
    CHECK(!strcmp(out, expected), assertion);
    CHECK(strlen(out) <= 128, "GFXINFO response remains within its wire bound");
}
static void sample(const char *bytes, const char *expected, const char *assertion) {
    /* Most scalar cases share one valid process header; explicit header cases supply their own. */
    if (!strstr(bytes, "** Graphics info")) {
        size_t n = strlen(bytes); char *framed = malloc(sizeof HEADER + n);
        CHECK(framed != NULL, "GFXINFO process fixture allocates");
        if (!framed) return;
        memcpy(framed, HEADER, sizeof HEADER - 1); memcpy(framed + sizeof HEADER - 1, bytes, n + 1);
        raw(framed, sizeof HEADER - 1 + n); expect(expected, assertion); free(framed);
    } else { raw(bytes, strlen(bytes)); expect(expected, assertion); }
}
static const char summary[] = HEADER
    "Stats since: 123456789ns\n"
    "Total frames rendered: 120\n"
    "Janky frames: 7 (5.83%)\n";
static const char reply[] = "GFXINFO SINCE=123456789 TOTAL=120 JANKY=7\n";

static void controls(void) {
    char out[256]; raw(summary, sizeof summary - 1);
    command("PING", out);
    CHECK(!strcmp(out, "OK\n"), "GFXINFO pre-feature PING control stays green");
    const char *bad[] = {
        "GFXINFO", "GFXINFO ROOT", "GFXINFO io.panelassistant.android", "GFXINFO self",
        "GFXINFO SELF extra", "GFXINFO SELF ;reboot", "GFXINFO SELF\nPING", "GFXINFO /proc"
    };
    for (size_t i = 0; i < sizeof bad / sizeof bad[0]; i++) {
        int before = captures; command(bad[i], out);
        CHECK(!strcmp(out, "ERR\n") && captures == before, "GFXINFO rejects invalid selectors and extra arguments before capture");
    }
    const enum helper_caller callers[] = { HELPER_CALLER_ROOT, HELPER_CALLER_NONE, (enum helper_caller)999 };
    for (size_t i = 0; i < sizeof callers / sizeof callers[0]; i++) {
        int before = captures; command_as("GFXINFO SELF", callers[i], out);
        CHECK(!strcmp(out, "ERR\n") && captures == before, "GFXINFO rejects callers without a successor identity before capture");
    }
}

static void valid_summaries(void) {
    raw(summary + sizeof HEADER - 1, sizeof summary - sizeof HEADER);
    expect("ERR\n", "GFXINFO requires the authenticated package process header");
    sample(summary, reply, "GFXINFO coherent API26 summary produces the typed frame");
    sample("Applications Graphics Acceleration Info:\nUptime: 987 Realtime: 1000\n"
        "** Graphics info for pid 42 [io.panelassistant.android] **\n\n"
        "Stats since: 123456789ns\nTotal frames rendered: 120\nJanky frames: 7 (5.83%)\n"
        "Janky frames (legacy): 40 (33.33%)\n50th percentile: 5ms\nView hierarchy:\n",
        reply, "GFXINFO modern process summary ignores separately named legacy jank and unrelated lines");
    sample("Stats since: 0ns\nTotal frames rendered: 0\nJanky frames: 0 (0.00%)\n",
        "GFXINFO SINCE=0 TOTAL=0 JANKY=0\n", "GFXINFO accepts canonical zero epoch and counts");
    sample("Stats since: 18446744073709551615ns\nTotal frames rendered: 4294967295\nJanky frames: 4294967295 (0.00%)\n",
        "GFXINFO SINCE=18446744073709551615 TOTAL=4294967295 JANKY=4294967295\n",
        "GFXINFO preserves full unsigned ranges without validating rounded percentage ratios");
    sample("Stats since: 1ns\nJanky frames (legacy): 99 (99.00%)\nTotal frames rendered: 100\nJanky frames: 2 (99.99%)\n",
        "GFXINFO SINCE=1 TOTAL=100 JANKY=2\n", "GFXINFO primary count is independent of legacy and presentation percentage");
    sample("** Graphics info for pid 2147483647 [io.panelassistant.android] **\n"
        "Stats since: 1ns\nTotal frames rendered: 1\nJanky frames: 0 (0.00%)\n",
        "GFXINFO SINCE=1 TOTAL=1 JANKY=0\n", "GFXINFO accepts the maximum positive process-header PID");
}

static void rejected_summaries(void) {
    const char *missing[] = {
        "", "No process found for: io.panelassistant.android\n",
        "Total frames rendered: 120\nJanky frames: 7 (5.83%)\n",
        "Stats since: 1ns\nJanky frames: 7 (5.83%)\n",
        "Stats since: 1ns\nTotal frames rendered: 120\n",
        "Stats since: 1ns\nTotal frames rendered: 120\nJanky frames (legacy): 7 (5.83%)\n",
        "stats since: 1ns\nTotal frames rendered: 120\nJanky frames: 7 (5.83%)\n",
        "Stats since: 1ns\nTotal frames rendered: 120\njanky frames: 7 (5.83%)\n"
    };
    for (size_t i = 0; i < sizeof missing / sizeof missing[0]; i++)
        sample(missing[i], "ERR\n", "GFXINFO requires every exact primary summary label");
    const char *ambiguous[] = {
        HEADER HEADER "Stats since: 1ns\nTotal frames rendered: 2\nJanky frames: 1 (50.00%)\n",
        "Stats since: 1ns\nStats since: 1ns\nTotal frames rendered: 2\nJanky frames: 1 (50.00%)\n",
        "Stats since: 1ns\nTotal frames rendered: 2\nTotal frames rendered: 2\nJanky frames: 1 (50.00%)\n",
        "Stats since: 1ns\nTotal frames rendered: 2\nJanky frames: 1 (50.00%)\nJanky frames: 1 (50.00%)\n",
        "Total frames rendered: 2\nStats since: 1ns\nJanky frames: 1 (50.00%)\n",
        "Stats since: 1ns\nJanky frames: 1 (50.00%)\nTotal frames rendered: 2\n",
        "Stats since: 1ns\nTotal frames rendered: 2\nJanky frames: 1 (50.00%)\nStats since: 3ns\nTotal frames rendered: 4\nJanky frames: 2 (50.00%)\n",
        "** Graphics info for pid 42 [io.panelassistant.android] **\nStats since: 1ns\nTotal frames rendered: 2\nJanky frames: 1 (50.00%)\n** Graphics info for pid 42 [io.panelassistant.android] **\n",
        "Stats since: 1ns\nTotal frames rendered: 2\nJanky frames: 3 (100.00%)\n"
    };
    for (size_t i = 0; i < sizeof ambiguous / sizeof ambiguous[0]; i++)
        sample(ambiguous[i], "ERR\n", "GFXINFO rejects duplicate out-of-order multiblock and incoherent summaries");
    const char *headers[] = {
        "** Graphics info for pid 42 [other.package] **",
        "** Graphics info for pid 0 [io.panelassistant.android] **",
        "** Graphics info for pid -1 [io.panelassistant.android] **",
        "** Graphics info for pid +1 [io.panelassistant.android] **",
        "** Graphics info for pid 01 [io.panelassistant.android] **",
        "** Graphics info for pid 2147483648 [io.panelassistant.android] **",
        "** Graphics info for pid 42 [io.panelassistant.android] **junk",
        "** Graphics info for pid broken"
    };
    for (size_t i = 0; i < sizeof headers / sizeof headers[0]; i++) {
        char bytes[512]; snprintf(bytes, sizeof bytes, "%s\n%s", headers[i], summary + sizeof HEADER - 1);
        sample(bytes, "ERR\n", "GFXINFO rejects malformed wrong-package and out-of-range process headers");
    }
    const char *epochs[] = { "-1ns", "+1ns", "01ns", "18446744073709551616ns", "1", "1ms", "1nsjunk", "1ns " };
    for (size_t i = 0; i < sizeof epochs / sizeof epochs[0]; i++) {
        char bytes[256]; snprintf(bytes, sizeof bytes, "Stats since: %s\nTotal frames rendered: 2\nJanky frames: 1 (50.00%%)\n", epochs[i]);
        sample(bytes, "ERR\n", "GFXINFO rejects malformed signed overflowing or noncanonical epochs");
    }
    const char *counts[] = { "-1", "+1", "01", "4294967296", "18446744073709551616", "1junk", "1 ", "" };
    for (size_t i = 0; i < sizeof counts / sizeof counts[0]; i++) {
        char bytes[256]; snprintf(bytes, sizeof bytes, "Stats since: 1ns\nTotal frames rendered: %s\nJanky frames: 0 (0.00%%)\n", counts[i]);
        sample(bytes, "ERR\n", "GFXINFO rejects malformed signed overflowing or noncanonical total counts");
        snprintf(bytes, sizeof bytes, "Stats since: 1ns\nTotal frames rendered: 4294967295\nJanky frames: %s (0.00%%)\n", counts[i]);
        sample(bytes, "ERR\n", "GFXINFO rejects malformed signed overflowing or noncanonical primary jank counts");
    }
    const char *presentations[] = { "", "50.00%", " (50%)", " (50.0%)", " (50.000%)", " (+50.00%)", " (-1.00%)", " (5e1%)", " (50.00)", " (50.00%)junk", " (50.00%) " };
    for (size_t i = 0; i < sizeof presentations / sizeof presentations[0]; i++) {
        char bytes[256]; snprintf(bytes, sizeof bytes, "Stats since: 1ns\nTotal frames rendered: 2\nJanky frames: 1%s\n", presentations[i]);
        sample(bytes, "ERR\n", "GFXINFO validates the complete AOSP percentage presentation shape");
    }
    sample("Error: permission denied\nStats since: 1ns\nTotal frames rendered: 2\nJanky frames: 1 (50.00%)\n", "ERR\n",
        "GFXINFO failure prefixes invalidate an otherwise complete stale summary");
    sample("Stats since: 1ns\nTotal frames rendered: 2\nJanky frames: 1 (50.00%)\nPermission Denial: dumping package\n", "ERR\n",
        "GFXINFO later failure lines invalidate a valid summary prefix");
}

static void capture_bounds(void) {
    raw(summary, sizeof summary - 2);
    expect("ERR\n", "GFXINFO truncated capture without final newline is unavailable");
    char nul[sizeof summary + 8]; memcpy(nul, summary, sizeof summary - 1);
    memcpy(nul + sizeof summary - 1, "\0hidden\n", 8); raw(nul, sizeof summary - 1 + 8);
    expect("ERR\n", "GFXINFO embedded NUL invalidates a complete valid prefix");
    const int statuses[] = { -2, -1, 1, 7 };
    for (size_t i = 0; i < sizeof statuses / sizeof statuses[0]; i++) {
        raw(summary, sizeof summary - 1); capture_status = statuses[i];
        expect("ERR\n", "GFXINFO capture failure discards complete-looking output");
    }
    raw(summary, sizeof summary - 1); override_used = 1; reported_used = CAPTURE_LIMIT + 2;
    expect("ERR\n", "GFXINFO rejects impossible reported capture lengths before parsing");
    reported_used = SIZE_MAX;
    expect("ERR\n", "GFXINFO rejects size_t overflow capture reports safely");
    char *large = malloc(CAPTURE_LIMIT + 1);
    CHECK(large != NULL, "GFXINFO bounded capture fixture allocates");
    if (!large) return;
    size_t n = sizeof summary - 1; memcpy(large, summary, n);
    while (CAPTURE_LIMIT - n >= 6) { memcpy(large + n, "other\n", 6); n += 6; }
    if (n < CAPTURE_LIMIT) { memset(large + n, ' ', CAPTURE_LIMIT - n); large[CAPTURE_LIMIT - 1] = '\n'; }
    raw(large, CAPTURE_LIMIT);
    expect(reply, "GFXINFO accepts a complete capture at exactly 128 KiB");
    large[CAPTURE_LIMIT] = '\n'; raw(large, CAPTURE_LIMIT + 1);
    expect("ERR\n", "GFXINFO overflow probe rejects one byte beyond 128 KiB");
    free(large);
}

int main(void) {
    controls(); valid_summaries(); rejected_summaries(); capture_bounds();
    if (failures) return 1;
    puts("GFXINFO BOUNDARY OK"); return 0;
}
