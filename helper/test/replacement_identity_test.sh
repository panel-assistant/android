#!/usr/bin/env bash
set -euo pipefail

OLD_HELPER="${1:?old helper path is required}"
NEW_HELPER="${2:?new helper path is required}"
OLD_BUILD_ID="$(printf 'a%.0s' {1..64})"
NEW_BUILD_ID="$(printf 'b%.0s' {1..64})"
TMP="$(mktemp -d)"
OLD_PID=""
NEW_PID=""

cleanup() {
  [ -z "$NEW_PID" ] || kill "$NEW_PID" >/dev/null 2>&1 || true
  [ -z "$OLD_PID" ] || kill "$OLD_PID" >/dev/null 2>&1 || true
  [ -z "$NEW_PID" ] || wait "$NEW_PID" >/dev/null 2>&1 || true
  [ -z "$OLD_PID" ] || wait "$OLD_PID" >/dev/null 2>&1 || true
  rm -rf "$TMP"
}
trap cleanup EXIT

wait_for_identity() {
  local helper="$1" expected="$2" reply="" attempt
  for attempt in {1..100}; do
    reply="$($helper --request BUILDID 2>/dev/null || true)"
    [ "$reply" = "BUILDID $expected" ] && return 0
    sleep 0.02
  done
  printf 'expected %s, observed %s\n' "$expected" "${reply:-<no reply>}" >&2
  return 1
}

"$OLD_HELPER" >"$TMP/old.log" 2>&1 &
OLD_PID=$!
wait_for_identity "$NEW_HELPER" "$OLD_BUILD_ID"
kill -0 "$OLD_PID"

# A merged owner has guardian custody. A different inode/build must refuse the occupied main
# socket, preserving both the incumbent process and its observable immutable identity.
"$NEW_HELPER" >"$TMP/new.log" 2>&1 &
NEW_PID=$!
for attempt in {1..100}; do
  kill -0 "$NEW_PID" >/dev/null 2>&1 || break
  sleep 0.02
done
if kill -0 "$NEW_PID" >/dev/null 2>&1; then
  echo "FAIL: replacement did not refuse the merged incumbent" >&2
  exit 1
fi
if wait "$NEW_PID"; then
  echo "FAIL: replacement reported success against the merged incumbent" >&2
  exit 1
fi
NEW_PID=""
kill -0 "$OLD_PID"
wait_for_identity "$NEW_HELPER" "$OLD_BUILD_ID"
kill "$OLD_PID"
wait "$OLD_PID" >/dev/null 2>&1 || true
OLD_PID=""

# A protocol-only legacy fixture has no independent guardian. Its root peer identity, legacy
# VERSION reply, distinct executable inode and panel comm name exercise the production legacy
# probe and retirement path. Actual released-daemon migration is qualified separately.
python3 - "$OLD_BUILD_ID" <<'LEGACY' >"$TMP/legacy.log" 2>&1 &
import ctypes
import socket
import sys
ctypes.CDLL(None).prctl(15, b"hapaneld-helper", 0, 0, 0)
with socket.socket(socket.AF_UNIX) as server:
    server.bind("\0hapaneld-helper-replacement-test")
    server.listen()
    while True:
        connection, _ = server.accept()
        with connection:
            data = b""
            while b"\n" not in data:
                chunk = connection.recv(256)
                if not chunk:
                    break
                data += chunk
            verb = data.split(b"\n", 1)[0]
            if verb == b"BUILDID":
                connection.sendall(("BUILDID " + sys.argv[1] + "\n").encode())
            elif verb == b"VERSION":
                connection.sendall(b"HELPER version=1.15.0 proto=1.15\n")
            else:
                connection.sendall(b"ERR\n")
LEGACY
OLD_PID=$!
wait_for_identity "$NEW_HELPER" "$OLD_BUILD_ID"
"$NEW_HELPER" >"$TMP/new.log" 2>&1 &
NEW_PID=$!
wait_for_identity "$NEW_HELPER" "$NEW_BUILD_ID"
kill -0 "$NEW_PID"
if kill -0 "$OLD_PID" >/dev/null 2>&1; then
  echo "FAIL: legacy fixture survived replacement" >&2
  exit 1
fi
wait "$OLD_PID" >/dev/null 2>&1 || true
OLD_PID=""

echo "replacement identity: merged refusal and legacy takeover passed"
