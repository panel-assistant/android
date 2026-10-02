#!/usr/bin/env bash
# Offline behavior of standalone refusal and authenticated read-only release dispatch.
set -uo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
INSTALLER="${INSTALLER_UNDER_TEST:-$ROOT/scripts/install.sh}"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
BIN="$TMP/bin"; mkdir "$BIN"
for tool in adb openssl; do ln -s "$ROOT/scripts/tests/fixtures/$tool" "$BIN/$tool"; done
cat > "$TMP/provision.sh" <<'PROVISION'
#!/usr/bin/env bash
printf 'provision' >> "$MOCK_CALL_LOG"
printf ' <%s>' "$@" >> "$MOCK_CALL_LOG"
printf '\n' >> "$MOCK_CALL_LOG"
PROVISION
cat > "$BIN/curl" <<'CURL'
#!/usr/bin/env bash
set -u
printf 'curl %s\n' "$*" >> "$MOCK_CALL_LOG"
url=; output=; next=0
for argument in "$@"; do
  if [ "$next" = 1 ]; then output="$argument"; next=0; continue; fi
  case "$argument" in -o) next=1 ;; https://*) url="$argument" ;; esac
done
case "$url" in
  */releases/latest)
    printf '%s\n' '{"tag_name":"v1.0.0","assets":[{"browser_download_url":"https://github.com/panel-assistant/android/releases/download/v1.0.0/panel-assistant-v1.0.0-manual-setup-required.apk"}]}' ;;
  */ha-paneld-provision-v1.0.0.sh.sha256.sig) printf 'signature\n' > "$output" ;;
  */ha-paneld-provision-v1.0.0.sh.sha256)
    hash="$(/usr/bin/sha256sum "$SUBJECT_PROVISION" | cut -d' ' -f1)"
    [ "${WRAPPER_BAD_DIGEST:-0}" = 0 ] || hash="$(printf '%064d' 0)"
    printf '%s  ha-paneld-provision-v1.0.0.sh\n' "$hash" > "$output" ;;
  */ha-paneld-provision-v1.0.0.sh) cp "$SUBJECT_PROVISION" "$output" ;;
  *) exit 22 ;;
esac
CURL
chmod 755 "$BIN/curl"
passes=0; failures=0; status=0; log="$TMP/calls"; output="$TMP/output"
check() { if "$@"; then passes=$((passes+1)); printf 'ok %s - %s\n' "$passes" "$description"; else failures=$((failures+1)); printf 'not ok - %s\n' "$description"; cat "$output"; fi; }
run() {
  : > "$log"
  PATH="$BIN:/usr/bin:/bin" MOCK_CALL_LOG="$log" SUBJECT_PROVISION="$TMP/provision.sh" bash "$INSTALLER" "$@" > "$output" 2>&1
  status=$?
}
for route in implicit explicit channel reset; do
  case "$route" in
    implicit) run ;;
    explicit) run --provision panel.test ;;
    channel) run --prerelease --provision panel.test --id panel ;;
    reset) run --provision panel.test --reset-config ;;
  esac
  description="$route install requires Panel Assistant"; check test "$status" -ne 0
  description="$route names live admission"; check grep -q 'require live Panel Assistant' "$output"
  description="$route refuses before any download or panel access"; check test ! -s "$log"
done
run --provision panel.test:5556 --verify
description='read-only verification executes authenticated release code'; check test "$status" -eq 0
description='verification preserves exact target and operation'; check grep -qx 'provision <panel.test:5556> <--verify>' "$log"
description='verification authenticates checksum signature'; check grep -q '^openssl dgst -sha256 -verify ' "$log"
description='verification never downloads an APK'; check bash -c '! grep -Eq "manual-setup-required.apk .* -o |^adb " "$1"' _ "$log"
run --provision panel.test --export panel-backup.json
description='export remains usable through the release wrapper'; check test "$status" -eq 0
description='export preserves its destination'; check grep -qx 'provision <panel.test:5555> <--export> <panel-backup.json>' "$log"
MOCK_RELEASE_SIGNATURE_FAIL=1 run --provision panel.test --verify
description='invalid executable signature fails closed'; check test "$status" -ne 0
description='invalid signature cannot execute downloaded code'; check bash -c '! grep -q "^provision " "$1"' _ "$log"
WRAPPER_BAD_DIGEST=1 run --provision panel.test --verify
description='mismatched executable bytes fail closed'; check test "$status" -ne 0
description='mismatched bytes cannot execute downloaded code'; check bash -c '! grep -q "^provision " "$1"' _ "$log"
run --provision panel.test --verify --id ignored
description='mixed verification and mutation remains rejected'; check test "$status" -eq 2
description='mixed request starts no download'; check test ! -s "$log"
printf '1..%s\n' "$((passes+failures))"
[ "$failures" -eq 0 ]
