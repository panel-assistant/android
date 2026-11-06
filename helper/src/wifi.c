#include "wifi.h"
#include "sysexec.h"
#include "util.h"
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <strings.h>

#define WIFI_CAPTURE_BYTES (128u * 1024u)

static int printable_utf8(const unsigned char *s, size_t length) {
    for (size_t i = 0; i < length;) {
        unsigned c = s[i++], value, count;
        if (c < 0x80) { if (c < 0x20 || c == 0x7f) return 0; continue; }
        if (c >= 0xc2 && c <= 0xdf) { value = c & 31; count = 1; }
        else if (c >= 0xe0 && c <= 0xef) { value = c & 15; count = 2; }
        else if (c >= 0xf0 && c <= 0xf4) { value = c & 7; count = 3; }
        else return 0;
        if (length - i < count) return 0;
        unsigned n = count;
        while (n--) { c = s[i++]; if ((c & 0xc0) != 0x80) return 0; value = (value << 6) | (c & 63); }
        if ((count == 1 && value < 0x80) || (count == 2 && value < 0x800) ||
            (count == 3 && value < 0x10000) || value > 0x10ffff ||
            (value >= 0xd800 && value <= 0xdfff) || (value >= 0x80 && value <= 0x9f)) return 0;
    }
    return 1;
}
static char *trim(char *s) {
    while (*s == ' ' || *s == '\t') s++;
    size_t n = strlen(s);
    while (n && (s[n-1] == ' ' || s[n-1] == '\t' || s[n-1] == '\r')) s[--n] = 0;
    return s;
}
static int failure_line(const char *s) {
    static const char *const prefixes[] = { "Error", "Exception", "SecurityException",
        "java.lang.", "Permission denial", "Permission denied", "Unknown command", "cmd:",
        "Can't find service", "Can't bind", "Service not found", "Failed", NULL };
    for (size_t i = 0; prefixes[i]; i++)
        if (!strncasecmp(s, prefixes[i], strlen(prefixes[i]))) return 1;
    return 0;
}
static int rssi_value(const char *s) {
    if (!strcmp(s, "0")) return 0;
    if (*s++ != '-' || *s < '1' || *s > '9') return 1;
    unsigned n = 0;
    while (*s) { if (*s < '0' || *s > '9' || n > 126) return 1; n = n * 10 + (unsigned)(*s++ - '0'); }
    return n <= 126 ? -(int)n : 1;
}
// AOSP's current WifiInfo record starts with SSID and delimits its next field with BSSID.
// Quoted SSIDs may contain commas; ambiguous quotes/field delimiters never become measurements.
static int record(char *s, char ssid[81], int *rssi) {
    if (strncmp(s, "SSID: ", 6)) return 0;
    s += 6;
    char *end = strstr(s, ", BSSID: ");
    if (!end || strstr(end + 1, ", BSSID: ")) return 0;
    size_t length = (size_t)(end - s);
    const char *value = s;
    int quoted = length >= 2 && s[0] == '"' && s[length-1] == '"';
    if (quoted) { value++; length -= 2; }
    int valid = length > 0 && length <= 32 && printable_utf8((const unsigned char *)value, length);
    for (size_t i = 0; i < length; i++)
        if (value[i] == '"' || value[i] == '\\' || (!quoted && value[i] == ',')) valid = 0;
    if (s[0] == '"' && !quoted) return 0;
    if (valid && ((length == 14 && !strncasecmp(value, "<unknown ssid>", length)) ||
        (length == 6 && !strncasecmp(value, "<none>", length)) ||
        (length == 4 && !strncasecmp(value, "null", length)) ||
        (length >= 2 && value[0] == '0' && (value[1] == 'x' || value[1] == 'X')))) valid = 0;
    if (valid) {
        static const char hex[] = "0123456789abcdef";
        for (size_t i = 0; i < length; i++) { unsigned c = (unsigned char)value[i]; ssid[i*2] = hex[c>>4]; ssid[i*2+1] = hex[c&15]; }
        ssid[length*2] = 0;
    }
    unsigned rssi_fields = 0;
    char *fields = end + 2, *next;
    do {
        next = strchr(fields, ',');
        if (next) *next = 0;
        char *field = trim(fields);
        if (!strncmp(field, "SSID:", 5)) return 0;
        if (!strncmp(field, "RSSI:", 5)) { rssi_fields++; *rssi = rssi_value(trim(field + 5)); }
        fields = next ? next + 1 : NULL;
    } while (fields);
    return rssi_fields <= 1;
}
void cmd_wifistatus(conn_ctx *ctx, const char *args) {
    if (*args) { reply(ctx->fd, "ERR\n"); return; }
    char *raw = malloc(WIFI_CAPTURE_BYTES + 1);
    if (!raw) { reply(ctx->fd, "ERR\n"); return; }
    size_t used = 0;
    if (sysexec_capture_wifi_status(raw, WIFI_CAPTURE_BYTES + 1, &used) != 0 ||
        !used || used > WIFI_CAPTURE_BYTES || memchr(raw, 0, used)) {
        free(raw); reply(ctx->fd, "ERR\n"); return;
    }
    raw[used] = 0;
    char ssid[81] = "-";
    int rssi = 1, records = 0, ambiguous = 0;
    char *line = raw, *next;
    do {
        next = strchr(line, '\n'); if (next) *next = 0;
        char *s = trim(line), *body = NULL;
        if (failure_line(s)) { free(raw); reply(ctx->fd, "ERR\n"); return; }
        if (!strncmp(s, "WifiInfo: ", 10)) body = s + 10;
        else if (!strncmp(s, "mWifiInfo ", 10)) body = s + 10;
        else if (!strncmp(s, "mWifiInfo: ", 11)) body = s + 11;
        else if (!strncmp(s, "mWifiInfo=", 10)) body = s + 10;
        if (body) { records++; if (!record(body, ssid, &rssi)) ambiguous = 1; }
        line = next ? next + 1 : NULL;
    } while (line);
    if (records != 1 || ambiguous) { strcpy(ssid, "-"); rssi = 1; }
    char signal[8] = "-", out[81];
    if (rssi <= 0) snprintf(signal, sizeof signal, "%d", rssi);
    snprintf(out, sizeof out, "SSID=%s RSSI=%s\n", ssid, signal);
    free(raw); reply(ctx->fd, out);
}
