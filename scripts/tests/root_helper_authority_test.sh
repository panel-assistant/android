#!/usr/bin/env bash
# Historical recovery through the production CLI and its emitted on-device program.
# APK input is only a signed request probe; no panel or network is contacted.
set -u
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
PROVISION="${PROVISION_UNDER_TEST:-$ROOT/scripts/provision.sh}"
FIXTURES="$ROOT/scripts/tests/fixtures"
TMP="$(mktemp -d)"
trap 'if [ "${KEEP_ROOT_HELPER_AUTHORITY_TMP:-0}" = 1 ]; then echo "fixture state: $TMP"; else rm -rf "$TMP"; fi' EXIT
passes=0; failures=0; tests=0
check() { tests=$((tests+1)); if "$@"; then passes=$((passes+1)); printf 'ok %d - %s\n' "$tests" "$description"; else failures=$((failures+1)); printf 'not ok %d - %s\n' "$tests" "$description"; fi; }
BUILD_ID="$(PATH=/usr/bin:/bin "$ROOT/helper/source-id.sh")"
mkdir "$TMP/bin"
for fixture in "$FIXTURES"/*; do case "${fixture##*/}" in adb|sha256sum) ;; *) ln -s "$fixture" "$TMP/bin/${fixture##*/}" ;; esac; done
ln -s /usr/bin/sha256sum "$TMP/bin/sha256sum"
cat > "$TMP/bin/adb" <<'ADB'
#!/usr/bin/env bash
set -u
case "$*" in
  *JOURNAL_SCAN_COMPLETE*)
    printf 'adb %s\n' "$*" >> "$MOCK_CALL_LOG"
    [ "${MOCK_INVENTORY:-ok}" != failure ] || exit 1
    case "${MOCK_INVENTORY:-ok}" in
      missing) exit 0 ;; multiple) printf 'JOURNAL system\nJOURNAL hybrid\n' ;;
      *) [ ! -e "$MOCK_STATE_DIR/stale-helper-transaction" ] || printf 'JOURNAL %s\n' "${MOCK_STALE_TRANSACTION_KIND:-system}" ;;
    esac
    echo JOURNAL_SCAN_COMPLETE; exit 0 ;;
esac
exec "$RECOVERY_ADB_FIXTURE" "$@"
ADB
chmod 755 "$TMP/bin/adb"
printf '#!/bin/sh\nexit 0\nBUILDID %s\n' "$BUILD_ID" > "$TMP/helper"
/usr/bin/python3 - "$TMP/probe.apk" "$TMP/helper" <<'PY'
import sys,zipfile
with zipfile.ZipFile(sys.argv[1],'w') as z:
    for arch in ('arm','arm64'): z.write(sys.argv[2],'assets/hapaneld-helper-'+arch)
PY
run_case() {
  local label="$1" kind="$2" journal="$3" installed="$4"; shift 4
  STATE="$TMP/$label"; mkdir "$STATE"; : > "$STATE/calls"
  [ "$journal" = no ] || : > "$STATE/stale-helper-transaction"
  [ "$installed" != same ] || cp "$TMP/probe.apk" "$STATE/installed-apk"
  [ "$installed" != different ] || printf incumbent > "$STATE/installed-apk"
  PATH="$TMP/bin:/usr/bin:/bin" RECOVERY_ADB_FIXTURE="$FIXTURES/adb" MOCK_TARGET=panel.test:5555 MOCK_CALL_LOG="$STATE/calls" MOCK_STATE_DIR="$STATE" \
    MOCK_ROOT=1 MOCK_SU_DIALECT=join MOCK_HELPER_BUILD_ID="$BUILD_ID" MOCK_STALE_BUILD_ID="$BUILD_ID" MOCK_STALE_TRANSACTION_KIND="$kind" \
    MOCK_STALE_APK_SHA256="$(/usr/bin/sha256sum "$TMP/probe.apk" | cut -d' ' -f1)" MOCK_NO_INSTALLED_PACKAGE=0 \
    ROOT_HELPER_VERIFY_POLL_SECONDS=0 ROOT_HELPER_VERIFY_ATTEMPTS=1 bash "$PROVISION" panel.test:5555 --recover-helper --apk "$TMP/probe.apk" "$@" > "$STATE/output" 2>&1
  status=$?
}
run_case none system no same
description='no journal is a successful no-op'; check test "$status" -eq 0
description='no journal does not stage or authenticate an unnecessary APK'; check bash -c '! rg -q "^(apksigner|adb .*push)" "$1"' _ "$STATE/calls"
for kind in system systemless hybrid; do
  run_case "$kind-commit" "$kind" yes same
  description="$kind existing exact APK commits its historical journal"; check test "$status" -eq 0
  description="$kind commit consumes its original transaction identity"; check grep -Eq "commit-$kind 0123456789abcdef0123456789abcdef" "$STATE/calls"
  description="$kind recovery authenticates the read-only APK evidence"; check grep -Eq '^apksigner verify' "$STATE/calls"
  description="$kind recovery never installs an APK or new helper"; check bash -c '! rg -q "adb .* (install|shell pm install)|install-(system|systemless|hybrid)" "$1"' _ "$STATE/calls"
  run_case "$kind-rollback" "$kind" yes different
  description="$kind different installed APK rolls back only historical helper state"; check test "$status" -eq 0
  description="$kind rollback is finalized with its original ownership token"; check grep -Eq "finalize-rollback-$kind 0123456789abcdef0123456789abcdef" "$STATE/calls"
  description="$kind rollback retains the installed APK bytes"; check test "$(cat "$STATE/installed-apk")" = incumbent
done
for refusal in signature signer inventory multiple unknown token staging; do
  case "$refusal" in
    signature) MOCK_LOCAL_APK_VERIFY_FAIL=1 run_case "$refusal" system yes same ;;
    signer) MOCK_RELEASE_CERT="$(printf '%064d' 1)" run_case "$refusal" system yes same ;;
    inventory) MOCK_INVENTORY=failure run_case "$refusal" system yes same ;;
    multiple) MOCK_INVENTORY=multiple run_case "$refusal" system yes same ;;
    unknown) MOCK_PM_PROBE=truncated run_case "$refusal" system yes different ;;
    token) MOCK_TOKEN_MISMATCH_ACTION=commit run_case "$refusal" system yes same ;;
    staging) MOCK_TRANSACTION_TAMPER=1 run_case "$refusal" system yes same ;;
  esac
  description="$refusal refuses historical recovery"; check test "$status" -ne 0
  description="$refusal keeps the original journal available"; check test -f "$STATE/stale-helper-transaction"
  description="$refusal never performs panel package installation"; check bash -c '! rg -q "adb .* (install|shell pm install)" "$1"' _ "$STATE/calls"
done
# Run the actual emitted device program against explicit historical journals, without invoking
# removed installer verbs to manufacture a fixture. Only Android absolute paths are relocated.
awk '/cat > "\$transaction_file" <<\x27EOF\x27/ {capture=1;next} capture && /^EOF$/ {exit} capture {print}' "$PROVISION" > "$TMP/emitted"
for version in 1 2; do
  for kind in system systemless hybrid; do
    work="$TMP/emitted-$version-$kind"; mkdir -p "$work/data/local" "$work/data/adb/hapaneld" "$work/data/adb/service.d" "$work/system/bin" "$work/system/etc/init" "$work/vendor/etc/init" "$work/dev" "$work/bin"
    sed -e "s#/data/#$work/data/#g" -e "s#/system/#$work/system/#g" -e "s#/vendor/#$work/vendor/#g" -e "s#/dev/#$work/dev/#g" "$TMP/emitted" > "$work/program"
    for command in mount stop pkill sync chcon start; do printf '#!/bin/sh\nexit 0\n' > "$work/bin/$command"; chmod 755 "$work/bin/$command"; done
    printf '#!/bin/sh\nexit 1\n' > "$work/bin/pidof"; chmod 755 "$work/bin/pidof"
    case "$kind" in system) marker="$work/system/bin/.hapaneld-helper-upgrade" ;; systemless) marker="$work/data/adb/hapaneld/.helper-upgrade.marker" ;; hybrid) marker="$work/data/adb/hapaneld/.helper-hybrid-upgrade.marker" ;; esac
    id=0123456789abcdef0123456789abcdef; hash="$(printf '%064d' 1)"
    printf 'JOURNAL_VERSION=%s\nJOURNAL_SCOPE=APK_HELPER\nTRANSACTION_ID=%s\nTARGET_APK_SHA256=%s\nTARGET_BUILD_ID=%s\nTARGET_HELPER_SHA256=%s\nLEASE_BOOT_ID=old-boot\nLEASE_UNTIL_UPTIME=0\n' "$version" "$id" "$hash" "$hash" "$hash" > "$marker"
    if [ "$version" = 2 ]; then
      printf 'BOOT_KIND=%s\nTARGET_BOOT_SHA256=%s\n' "$kind" "$hash" >> "$marker"
      fields='LIVE_BIN SYS_BIN SYS_RC DATA_BIN DATA_SERVICE VENDOR_RC LEGACY_BIN LEGACY_SERVICE'
    else
      fields='OLD_BIN OLD_SERVICE LEGACY_BIN LEGACY_SERVICE ALT_BIN ALT_SERVICE SYS_RC SYS_BIN OLD_VENDOR_RC'
    fi
    for field in $fields; do printf '%s=0\n%s_SHA256=-\n' "$field" "$field" >> "$marker"; done
    before="$(sha256sum "$marker")"
    PATH="$work/bin:/usr/bin:/bin" /bin/sh "$work/program" "status-$kind" "$id" "$hash" "$hash" "$hash" > "$work/status" 2>&1; result=$?
    description="v$version $kind authenticates an existing PRE_SWAP journal"; check test "$result" -eq 0
    description="v$version $kind classifies the seeded absent topology"; check grep -qx LIVE_STATE=PRE_SWAP "$work/status"
    PATH="$work/bin:/usr/bin:/bin" /bin/sh "$work/program" "install-$kind" "$id" "$hash" "$hash" "$hash" > "$work/refused" 2>&1; result=$?
    description="v$version $kind emitted program has no fresh install verb"; check test "$result" -ne 0
    description="v$version $kind retired verb leaves journal bytes unchanged"; check test "$(sha256sum "$marker")" = "$before"
    PATH="$work/bin:/usr/bin:/bin" /bin/sh "$work/program" "finalize-rollback-$kind" wrong "$hash" "$hash" "$hash" > /dev/null 2>&1; result=$?
    description="v$version $kind rejects a foreign finalization token"; check test "$result" -ne 0
    cp "$marker" "$work/saved"
    sed -i "s/^LEASE_BOOT_ID=.*/LEASE_BOOT_ID=$(cat /proc/sys/kernel/random/boot_id)/;s/^LEASE_UNTIL_UPTIME=.*/LEASE_UNTIL_UPTIME=999999999/" "$marker"
    PATH="$work/bin:/usr/bin:/bin" /bin/sh "$work/program" "finalize-rollback-$kind" "$id" "$hash" "$hash" "$hash" > "$work/lease" 2>&1; result=$?
    description="v$version $kind refuses the active historical lease"; check test "$result" -eq 75
    mv "$work/saved" "$marker"
    cp "$marker" "$work/journal-saved"
    chown 1000:1000 "$marker"
    PATH="$work/bin:/usr/bin:/bin" /bin/sh "$work/program" "status-$kind" "$id" "$hash" "$hash" "$hash" > /dev/null 2>&1; result=$?
    description="v$version $kind rejects a journal outside root ownership"; check test "$result" -ne 0
    chown 0:0 "$marker"
    printf '#!/bin/sh\nexit 0\n' > "$work/probe"; chmod 700 "$work/probe"
    probe_hash="$(sha256sum "$work/probe" | cut -d' ' -f1)"
    sed -i "s|@BIN_SHA256@|$probe_hash|g;s|@STAGED_HELPER@|$work/probe|g" "$work/program"
    PATH="$work/bin:/usr/bin:/bin" /bin/sh "$work/program" "rollback-$kind" "$id" "$hash" "$hash" "$hash" > "$work/rollback" 2>&1; result=$?
    description="v$version $kind executes bounded rollback from a known empty topology"; check test "$result" -eq 0
    description="v$version $kind rollback preserves journal until verified finalization"; check test -f "$marker"
    PATH="$work/bin:/usr/bin:/bin" /bin/sh "$work/program" "finalize-rollback-$kind" "$id" "$hash" "$hash" "$hash" > "$work/finalize" 2>&1; result=$?
    description="v$version $kind finalizes a verified already-restored topology"; check test "$result" -eq 0
    description="v$version $kind finalization retires only its historical marker"; check test ! -e "$marker"
    if [ "$version" = 2 ]; then
      cp "$work/journal-saved" "$marker"
      printf '#!/bin/sh\nexit 0\n' > "$work/data/local/hapaneld-helper"; chmod 700 "$work/data/local/hapaneld-helper"
      external_before="$(sha256sum "$work/data/local/hapaneld-helper")"
      : > "$work/data/local/.hapaneld-helper.new"
      PATH="$work/bin:/usr/bin:/bin" /bin/sh "$work/program" "cancel-external-$kind" "$id" "$hash" "$hash" "$hash" > "$work/custody" 2>&1; result=$?
      description="v2 $kind holds recovery under app replacement custody"; check test "$result" -ne 0
      description="v2 $kind custody refusal keeps journal bytes"; check cmp -s "$marker" "$work/journal-saved"
      rm "$work/data/local/.hapaneld-helper.new"
      PATH="$work/bin:/usr/bin:/bin" /bin/sh "$work/program" "cancel-external-$kind" "$id" "$hash" "$hash" "$hash" > "$work/cancel" 2>&1; result=$?
      description="v2 $kind safely retires an owned external-change journal"; check test "$result" -eq 5
      description="v2 $kind cancellation preserves the external canonical helper"; check test "$(sha256sum "$work/data/local/hapaneld-helper")" = "$external_before"
    fi
  done
done
printf '1..%d\n' "$tests"
[ "$failures" = 0 ]
