#!/usr/bin/env bash
#
# App<->daemon contract cross-check.
#
# Verb names are hand-duplicated across the Kotlin clients and the daemon's dispatch table, so a verb
# added on one side but not the other (or a rename) would drift silently. This asserts every verb the
# app SENDS to the helper daemon is HANDLED by the shared commands.def manifest — a mismatch fails CI.
#
# Scope: verb NAMES only. Arg formats + reply strings are pinned separately by the daemon's own golden
# request->reply unit tests (helper/test/unit.c).
#
# App send sites covered:
#   - HelperClient.send / sendBytes / sendLong / bootstrap("VERB …") and injected Daemon equivalents
#   - HelperClient's own send("VERB …") calls (notably the PING availability probe)
#   - TameController.privileged("VERB …")                  (STOP/DISABLE/ENABLE/OVERLAY)
#   - input session socket writer.write("VERB …")          (INPUTV2/WATCH/SUBSCRIBE)
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
APP="$ROOT/app/src/main/kotlin"
COMMANDS="$ROOT/helper/src/commands.def"

daemon=$(grep -oE '^COMMAND\([A-Z][A-Z0-9_]*' "$COMMANDS" | cut -d'(' -f2 | sort -u)

app_helper=$(grep -rhoE '((HelperClient|daemon)\.(send|sendBytes|sendLong|sendFile)|privileged)\("[A-Z][A-Z0-9_]*' "$APP" | grep -oE '"[A-Z][A-Z0-9_]*' | tr -d '"')
app_client=$(grep -hoE '(send|sendBytes|sendLong|sendFile|requestRaw|request|bootstrap)\("[A-Z][A-Z0-9_]*' "$APP/io/github/maxlyth/hapaneld/util/HelperClient.kt" | grep -oE '"[A-Z][A-Z0-9_]*' | tr -d '"')
app_evdev=$(grep -rhoE '(out|writer)\.write\("[A-Z][A-Z0-9_]*' "$APP/io/github/maxlyth/hapaneld/input" 2>/dev/null | grep -oE '"[A-Z][A-Z0-9_]*' | tr -d '"' || true)
app=$(printf '%s\n%s\n%s\n' "$app_helper" "$app_client" "$app_evdev" | grep -E '.' | sort -u)

missing=""
for v in $app; do printf '%s\n' "$daemon" 2>/dev/null | grep -qx "$v" || missing="$missing $v"; done

if [ -n "$missing" ]; then
    echo "FAIL: app sends verb(s) the daemon does NOT handle:$missing" >&2
    echo "  daemon COMMANDS: $(printf '%s ' $daemon)" >&2
    exit 1
fi
echo "app<->daemon contract OK — $(printf '%s\n' "$app" | grep -c .) app verbs, all handled by the daemon"
