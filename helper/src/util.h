// Small dependency-free helpers shared across the daemon: byte clamping, sysfs/file IO primitives,
// the socket reply writer, and input validators for structural privileged operations.
// Nothing here execs or spawns — that surface lives in sysexec.c, so this file stays pure and is
// unit-testable host-native.
#ifndef HAPANELD_UTIL_H
#define HAPANELD_UTIL_H

#include <stddef.h>

int  clamp(int v);                                  // clamp to 0..255

// Write a NUL-terminated string to a (sysfs) node. Returns 0 on success, -1 on any failure.
int  write_node(const char *path, const char *val);

// Write all bytes unless the peer fails/stalls. Returns 0 on success, -1 on failure.
int write_complete(int fd, const void *bytes, size_t size);

// Write a NUL-terminated string to a socket fd. A failed reply shuts down that connection so its
// server worker exits instead of parsing further commands for a peer that can no longer receive.
int reply(int fd, const char *s);

// First line of [path] into [dst] (NUL-terminated, newline stripped). dst[0]='\0' on failure.
void first_line(const char *path, char *dst, size_t dstsz);

// Copy the whole contents of [path] to fd [out]. Returns 0 on success, -1 on read/write/open failure;
// a failed socket write shuts down the connection.
int cat_to(int out, const char *path);

// Argument validators — request-derived values remain typed and are passed only as argv fields or
// direct filesystem values; they are never interpolated into shell programs.
int  valid_pkg(const char *s);        // Android package: [A-Za-z0-9._]+
int  valid_component(const char *s);  // pkg/class component: package chars plus '/'
int  valid_num(const char *s);        // non-empty decimal digits only
int  valid_decimal(const char *s);    // decimal with at most one dot, >=1 digit (e.g. "1.15")
int  valid_gov(const char *s);        // CPU governor name: [a-z0-9_]+
int  is_critical_pkg(const char *s);  // never-stop/disable backstop (systemui/settings/both our ids/…)
int  valid_gbl_path(const char *s);   // absolute path, no '..', no "'", ends with ".gbl"
int  valid_apk_path(const char *s);   // .apk under either known package's data dir, no '..', no "'"
int  valid_hex_dataset(const char *s); // even-length hex string, max 508 chars (254 dataset bytes)

#endif
