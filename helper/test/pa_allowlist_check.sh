#!/usr/bin/env bash
# The typed-command allowlist in src/pa_allowlist.h must equal test/pa-typed-allowlist.json by value.
# Panel Assistant's parity test compares its own definitions to the same fixture, so the two sides
# cannot drift without one of the two tests failing.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
BINARY="${1:?usage: pa_allowlist_check.sh <pa-allowlist-json binary>}"
actual="$(mktemp)"
trap 'rm -f -- "$actual"' EXIT
"$BINARY" > "$actual"
if ! python3 - "$actual" "$HERE/pa-typed-allowlist.json" <<'PY'
import json, sys
actual = json.load(open(sys.argv[1]))
expected = json.load(open(sys.argv[2]))
if actual != expected:
    for key in sorted(set(actual) | set(expected)):
        if actual.get(key) != expected.get(key):
            print(f"FAIL: allowlist key {key}: C {actual.get(key)!r} != fixture {expected.get(key)!r}")
    sys.exit(1)
PY
then
    echo "FAIL: src/pa_allowlist.h does not match test/pa-typed-allowlist.json" >&2
    exit 1
fi
echo "PA allowlist OK"
