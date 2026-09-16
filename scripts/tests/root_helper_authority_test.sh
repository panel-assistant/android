#!/usr/bin/env bash
# Focused black-box coverage for the managed Option A helper authority.  All device and HTTP traffic
# is served by fixtures; this script never contacts a panel or the network.
set -u

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
PROVISION="$ROOT/scripts/provision.sh"
FIXTURES="$ROOT/scripts/tests/fixtures"
HELPER_STATE="$FIXTURES/helper-state"
TMP="$(mktemp -d)"
cleanup_authority_test() {
  if [ "${KEEP_ROOT_HELPER_AUTHORITY_TMP:-0}" = 1 ]; then printf 'kept fixture state: %s\n' "$TMP" >&2
  else rm -rf "$TMP"; fi
}
trap cleanup_authority_test EXIT

passes=0
failures=0
pass() { passes=$((passes + 1)); printf 'ok %d - %s\n' "$passes" "$1"; }
fail() { failures=$((failures + 1)); printf 'not ok - %s\n' "$1" >&2; }
expect_eq() {
  expected="$1" actual="$2" description="$3"
  if [ "$actual" = "$expected" ]; then pass "$description"; else fail "$description (expected '$expected', got '$actual')"; fi
}
expect_line() {
  pattern="$1" file="$2" description="$3"
  if grep -qx "$pattern" "$file"; then pass "$description"; else fail "$description (missing '$pattern')"; fi
}
expect_event() {
  pattern="$1" state="$2" description="$3"
  if grep -Eq "$pattern" "$state/helper-model/events"; then pass "$description"; else fail "$description (missing event '$pattern')"; fi
}
expect_no_event() {
  pattern="$1" state="$2" description="$3"
  if grep -Eq "$pattern" "$state/helper-model/events"; then fail "$description (unexpected event '$pattern')"; else pass "$description"; fi
}

BUILD_ID="$(PATH=/usr/bin:/bin "$ROOT/helper/source-id.sh")"
AUTHORITY_FIXTURES="$TMP/authority-fixtures"
mkdir -p "$AUTHORITY_FIXTURES"
for fixture in "$FIXTURES"/*; do
  [ "$(basename "$fixture")" = sha256sum ] || ln -s "$fixture" "$AUTHORITY_FIXTURES/$(basename "$fixture")"
done
ln -s /usr/bin/sha256sum "$AUTHORITY_FIXTURES/sha256sum"
DIST="$TMP/helper-dist"
mkdir -p "$DIST/armeabi-v7a" "$DIST/arm64-v8a"
for mock_helper in "$DIST/armeabi-v7a/hapaneld-helper" "$DIST/arm64-v8a/hapaneld-helper"; do
  printf '#!/bin/sh\nif [ "${1:-}" = --replacement-safe ]; then echo REPLACE_SAFE; exit 0; fi\nexit 2\nBUILDID %s\n' \
    "$BUILD_ID" > "$mock_helper"
  chmod 755 "$mock_helper"
done
APK="$TMP/ha-paneld.apk"
/usr/bin/python3 - "$APK" "$DIST/armeabi-v7a/hapaneld-helper" "$DIST/arm64-v8a/hapaneld-helper" <<'PY'
import sys
import zipfile

apk, arm, arm64 = sys.argv[1:]
with zipfile.ZipFile(apk, "w") as archive:
    archive.writestr("AndroidManifest.xml", b"test manifest\n")
    archive.write(arm, "assets/hapaneld-helper-arm")
    archive.write(arm64, "assets/hapaneld-helper-arm64")
PY

case_state() { printf '%s/%s-%s\n' "$TMP" "$1" "$2"; }
old_path_for() {
  case "$1" in system) printf '/system/bin/hapaneld-helper\n' ;; *) printf '/data/adb/hapaneld/hapaneld-helper\n' ;; esac
}

run_provision_case() {
  kind="$1" label="$2" guard="${3:-EMPTY}" ready_after="${4:-0}" bad_caps="${5:-0}" duplicate="${6:-0}" bad_status="${7:-0}"
  commit_fail="${8:-0}" resume="${9:-0}"
  state="$(case_state "$kind" "$label")"
  if [ "$resume" != 1 ]; then
    rm -rf "$state"; mkdir -p "$state/auto-backups"
    printf 'previous installed apk\n' > "$state/installed-apk"
    old_path="$(old_path_for "$kind")"
    MOCK_STATE_DIR="$state" MOCK_HELPER_READY_AFTER="$ready_after" "$HELPER_STATE" seed "$kind" "$old_path" "$BUILD_ID" "$guard" 1
    : > "$state/calls.log"
  fi
  before="$(MOCK_STATE_DIR="$state" "$HELPER_STATE" fingerprint)"

  system_writable=1; vendor_state=missing
  [ "$kind" != systemless ] || system_writable=0
  [ "$kind" != hybrid ] || vendor_state=managed
  PATH="$AUTHORITY_FIXTURES:/usr/bin:/bin" \
  MOCK_TARGET=panel.test:5555 \
  MOCK_CALL_LOG="$state/calls.log" \
  MOCK_STATE_DIR="$state" \
  PROVISION_TEST_STATE_DIR="$state" \
  MOCK_HELPER_STATEFUL=1 \
  MOCK_STATEFUL_BAD_CAPS="$bad_caps" \
  MOCK_STATEFUL_BAD_STATUS="$bad_status" \
  MOCK_STATEFUL_DUPLICATE_ON_LAUNCH="$duplicate" \
  MOCK_STATEFUL_COMMIT_FAIL="$commit_fail" \
  MOCK_HELPER_BUILD_ID="$BUILD_ID" \
  MOCK_PLAN="${CASE_PLAN:-ok}" \
  MOCK_ROOT=1 \
  MOCK_SU_DIALECT=join \
  MOCK_SYSTEM_WRITABLE="$system_writable" \
  MOCK_SYSTEMLESS_RUNNER=1 \
  MOCK_VENDOR_RC_STATE="$vendor_state" \
  MOCK_VENDOR_INIT_RW=1 \
  MOCK_DB_CANDIDATE_CONTRACT=hapaneld-db:v1:ha-paneld.db:1:14 \
  MOCK_DB_DEVICE_USER_VERSION=9 \
  HAPANELD_HELPER_PROBE= \
  HAPANELD_HELPER_DIST_DIR="$DIST" \
  HAPANELD_HOST_SQLITE3="$(command -v sqlite3)" \
  HAPANELD_SKIP_AUTO_EXPORT=1 \
  HAPANELD_CONFIG_BACKUP_DIR="$state/auto-backups" \
  STORAGE_HEALTH_VERIFY_POLL_SECONDS=0 \
  APP_LAUNCH_PROBE_SECONDS=1 \
  APP_HEALTH_TIMEOUT_SECONDS=3 \
  bash "$PROVISION" panel.test:5555 --apk "$APK" --no-tame --allow-unsigned-helper > "$state/output" 2>&1
  CASE_STATUS=$?
  CASE_STATE="$state"
  CASE_BEFORE="$before"
}

assert_canonical() {
  kind="$1" state="$2"
  inspect="$state/inspect"
  MOCK_STATE_DIR="$state" "$HELPER_STATE" inspect > "$inspect"
  expect_line 'LIVE_PATH=/data/local/hapaneld-helper' "$inspect" "$kind converges the live helper to the canonical path"
  expect_line "LIVE_BUILD=$BUILD_ID" "$inspect" "$kind retains the exact target BUILDID"
  expect_line 'LIVE_MODE=0700' "$inspect" "$kind seals the live helper owner-only"
  expect_line 'LIVE_OWNER=0:0' "$inspect" "$kind leaves the live helper root-owned"
  expect_line 'LIVE_NLINK=1' "$inspect" "$kind leaves one link to the live inode"
  expect_line 'FILE_CANONICAL=PRESENT' "$inspect" "$kind retains exactly the canonical helper file"
  expect_line 'FILE_SYSTEM_LEGACY=ABSENT' "$inspect" "$kind removes the legacy system helper file"
  expect_line 'FILE_DATA_LEGACY=ABSENT' "$inspect" "$kind removes the legacy data helper file"
  expect_line 'SUPERVISORS=1' "$inspect" "$kind has exactly one supervisor"
  expect_line 'WORKERS=1' "$inspect" "$kind has exactly one worker"
  expect_line 'PROCESS_PATH=/data/local/hapaneld-helper' "$inspect" "$kind process authority is canonical"
  expect_line 'GUARD_PHASE=EMPTY' "$inspect" "$kind live Guard phase is EMPTY"
  expect_line 'GUARD_RESIDUE=0' "$inspect" "$kind live Guard has no residue"
  expect_line "BOOT_KIND=$kind" "$inspect" "$kind retains the selected boot registration"
  case "$kind" in
    system)
      expect_line 'BOOT_SYSTEM=PRESENT' "$inspect" 'system has its one system init registration'
      expect_line 'BOOT_VENDOR=ABSENT' "$inspect" 'system removes the vendor registration'
      expect_line 'BOOT_SERVICE=ABSENT' "$inspect" 'system removes the service.d registration' ;;
    systemless)
      expect_line 'BOOT_SYSTEM=ABSENT' "$inspect" 'systemless removes the system init registration'
      expect_line 'BOOT_VENDOR=ABSENT' "$inspect" 'systemless removes the vendor registration'
      expect_line 'BOOT_SERVICE=PRESENT' "$inspect" 'systemless has its one service.d registration' ;;
    hybrid)
      expect_line 'BOOT_SYSTEM=ABSENT' "$inspect" 'hybrid removes the system init registration'
      expect_line 'BOOT_VENDOR=PRESENT' "$inspect" 'hybrid has its one vendor registration'
      expect_line 'BOOT_SERVICE=ABSENT' "$inspect" 'hybrid removes the service.d registration' ;;
  esac
  expect_line 'TRANSACTION_OPEN=0' "$inspect" "$kind commits its recovery journal only after APK success"
  expect_event '^INSTALL_OK .*path=/data/local/hapaneld-helper$' "$state" "$kind installed the actual captured helper asset"
  if cmp -s "$DIST/arm64-v8a/hapaneld-helper" "$state/helper-model/captured/helper"; then pass "$kind model consumed the exact pushed helper bytes"; else fail "$kind model did not retain the pushed helper bytes"; fi
  case "$kind" in system) captured_boot=rc; live_boot=system.rc ;; systemless) captured_boot=svc; live_boot=service.sh ;; hybrid) captured_boot=hrc; live_boot=vendor.rc ;; esac
  if cmp -s "$state/helper-model/captured/$captured_boot" "$state/helper-model/boot/$live_boot"; then pass "$kind model installed the exact pushed boot bytes"; else fail "$kind model did not install the pushed boot bytes"; fi
  if grep -Fq 'JOURNAL_VERSION=2' "$state/helper-model/captured/txn" && grep -Fq -- '--replacement-safe' "$state/helper-model/captured/txn"; then pass "$kind executed a captured v2 replacement-safe transaction"; else fail "$kind transaction capture lacks the v2 replacement contract"; fi
  expect_event '^LAUNCH_OK path=/data/local/hapaneld-helper supervised=1 attempt=1$' "$state" "$kind launched one supervised authority"
  expect_no_event '^LAUNCH_OK path=(/system/bin|/data/adb/hapaneld)/' "$state" "$kind never launched a legacy-path authority"
  caps="$(MOCK_STATE_DIR="$state" "$HELPER_STATE" request /data/local/hapaneld-helper GUARDCAPS)"
  status="$(MOCK_STATE_DIR="$state" "$HELPER_STATE" request /data/local/hapaneld-helper GUARDSTATUS)"
  expect_eq 'OK GUARDCAPS 1 PREPARE DEFINE STREAM ACTION HEALTH REFUSAL STATUS EVIDENCE CANCEL RETIRE JOURNAL AUTONOMOUS SUPERVISED TERMINAL_RETIRE' "$caps" "$kind exposes the exact live Guard capability contract"
  expect_eq 'OK GUARDSTATUS 0 EMPTY NONE NONE NONE NONE 0 0 0 NONE NONE 0 0' "$status" "$kind exposes the exact empty Guard status"
  emitted_cuts="$(MOCK_STATE_DIR="$state" "$HELPER_STATE" emitted-rollback-cuts "$kind" 2>>"$state/output")"; emitted_status=$?
  if [ "$emitted_status" -eq 0 ] && printf '%s\n' "$emitted_cuts" 2>/dev/null | grep -Eqx "EMITTED_ROLLBACK_CUTS_OK kind=$kind operations=[1-9][0-9]*"; then
    pass "$kind emitted transaction survives every authoritative rollback fault cut"
  else
    fail "$kind emitted transaction rollback cuts failed (see $state/output)"
  fi
  if [ "$emitted_status" -eq 0 ] && printf '%s\n' "$emitted_cuts" 2>/dev/null | grep -Eqx "EMITTED_EXTERNAL_CANCEL_OK kind=$kind operations=[1-9][0-9]*"; then
    pass "$kind exact emitted transaction crash-resumably retires external canonical changes and holds custody or ambiguity"
  else
    fail "$kind emitted external-canonical cancellation matrix failed (see $state/output)"
  fi
  if [ "$emitted_status" -eq 0 ] && printf '%s\n' "$emitted_cuts" 2>/dev/null | grep -Eqx "EMITTED_V1_ROLLBACK_CUTS_OK kind=$kind operations=[1-9][0-9]*"; then
    pass "$kind exact emitted v1 rollback survives intent and every authoritative restore cut"
  else
    fail "$kind emitted v1 rollback cut matrix failed (see $state/output)"
  fi
  if [ "$emitted_status" -eq 0 ] && printf '%s\n' "$emitted_cuts" 2>/dev/null | grep -Fqx "EMITTED_V1_NOFOLLOW_HOLD_OK kind=$kind"; then
    pass "$kind v1 rollback rejects a broken-symlink absence with zero mutation"
  else
    fail "$kind emitted v1 NOFOLLOW absence mutant failed (see $state/output)"
  fi
  if MOCK_STATE_DIR="$state" "$HELPER_STATE" boot; then pass "$kind boot registration starts exactly one valid authority"; else fail "$kind boot registration is not executable"; fi
  MOCK_STATE_DIR="$state" "$HELPER_STATE" inspect > "$inspect"
  expect_line 'SUPERVISORS=1' "$inspect" "$kind simulated reboot leaves one supervisor"
  expect_line 'PROCESS_PATH=/data/local/hapaneld-helper' "$inspect" "$kind simulated reboot starts the canonical path"
}

# Fixture-level fail-closed protocol and authority mutants.
fixture_state="$TMP/fixture-selftest"
mkdir -p "$fixture_state"
if MOCK_STATE_DIR="$fixture_state" "$HELPER_STATE" selftest >/dev/null; then pass 'stateful helper fixture self-test'; else fail 'stateful helper fixture self-test'; fi
MOCK_STATE_DIR="$fixture_state" "$HELPER_STATE" seed system /data/local/hapaneld-helper "$BUILD_ID" EMPTY 1
: > "$fixture_state/calls.log"
unknown="$(MOCK_CALL_LOG="$fixture_state/calls.log" MOCK_STATE_DIR="$fixture_state" MOCK_HELPER_STATEFUL=1 MOCK_HELPER_BUILD_ID="$BUILD_ID" MOCK_ROOT=1 \
  "$FIXTURES/adb" -s panel.test:5555 shell 'su 0 "exec /data/local/hapaneld-helper --request UNKNOWN_REQUEST"')"; unknown_status=$?
expect_eq ERR "$unknown" 'unknown helper request answers exact ERR'
if [ "$unknown_status" -ne 0 ]; then pass 'unknown helper request exits nonzero'; else fail 'unknown helper request falsely succeeded'; fi
safe="$(MOCK_STATE_DIR="$fixture_state" "$HELPER_STATE" replacement-safe)"; safe_status=$?
expect_eq REPLACE_SAFE "$safe" 'empty Guard answers exact REPLACE_SAFE'
expect_eq 0 "$safe_status" 'empty Guard replacement probe succeeds'
MOCK_STATE_DIR="$fixture_state" "$HELPER_STATE" seed system /data/local/hapaneld-helper "$BUILD_ID" PREPARED 1
armed_before="$(MOCK_STATE_DIR="$fixture_state" "$HELPER_STATE" fingerprint)"
armed="$(MOCK_STATE_DIR="$fixture_state" "$HELPER_STATE" replacement-safe)"; armed_status=$?
armed_after="$(MOCK_STATE_DIR="$fixture_state" "$HELPER_STATE" fingerprint)"
expect_eq GUARD_ARMED "$armed" 'armed Guard answers exact GUARD_ARMED'
expect_eq 3 "$armed_status" 'armed Guard replacement probe exits with refusal status 3'
expect_eq "$armed_before" "$armed_after" 'replacement-safe refusal changes no authoritative state'
MOCK_STATE_DIR="$fixture_state" "$HELPER_STATE" seed system /data/local/hapaneld-helper "$BUILD_ID" EMPTY 2
duplicate_reply="$(MOCK_STATE_DIR="$fixture_state" "$HELPER_STATE" request /data/local/hapaneld-helper PING)"; duplicate_status=$?
expect_eq ERR "$duplicate_reply" 'duplicate supervisor authority cannot answer PING successfully'
if [ "$duplicate_status" -ne 0 ]; then pass 'duplicate supervisor authority fails closed'; else fail 'duplicate supervisor authority was accepted'; fi
if grep -Fq 'case "$journal_version" in 1|2)' "$PROVISION" &&
   grep -Fq 'system) helper_path=/system/bin/hapaneld-helper' "$PROVISION" &&
   grep -Fq 'systemless|hybrid) helper_path=/data/adb/hapaneld/hapaneld-helper' "$PROVISION"; then
  pass 'v1 recovery keeps its authenticated legacy helper paths while v2 defaults canonical'
else
  fail 'v1 recovery compatibility or path selection is missing'
fi
if grep -Fq 'if grep -q ^JOURNAL_VERSION=2$ "$marker"; then' "$PROVISION" &&
   grep -Fq 'system_matches_recorded_v1 || return 1' "$PROVISION" &&
   grep -Fq 'systemless_matches_recorded_v1 || return 1' "$PROVISION" &&
   grep -Fq 'hybrid_matches_recorded_v1 || return 1' "$PROVISION"; then
  pass 'rollback finalization distinguishes v2 recovery from retained v1 journals'
else
  fail 'rollback finalization lost v1/v2 journal discrimination'
fi

# Same-BUILDID legacy-path incumbents must still converge all three install layouts to Option A.
for kind in system systemless hybrid; do
  run_provision_case "$kind" success
  if [ "$CASE_STATUS" -eq 0 ]; then pass "$kind canonical managed-provision scenario succeeds"; else fail "$kind canonical managed-provision scenario failed (see $CASE_STATE/output)"; fi
  assert_canonical "$kind" "$CASE_STATE"
done

# Helper eligibility follows the proven root route alone. A profile with no helper-demanding LED or
# other driver and one whose LED route needs the helper must both converge the same canonical helper.
for plan in helper-absent helper-required; do
  CASE_PLAN="$plan" run_provision_case system "plan-$plan"
  if [ "$CASE_STATUS" -eq 0 ]; then pass "$plan profile provisioning succeeds"; else fail "$plan profile provisioning failed (see $CASE_STATE/output)"; fi
  expect_event '^INSTALL_OK .*path=/data/local/hapaneld-helper$' "$CASE_STATE" "$plan profile installs the canonical helper"
  inspect="$CASE_STATE/inspect"
  MOCK_STATE_DIR="$CASE_STATE" "$HELPER_STATE" inspect > "$inspect"
  expect_line 'LIVE_PATH=/data/local/hapaneld-helper' "$inspect" "$plan profile moves the live helper off its legacy path"
  expect_event '^LAUNCH_OK path=/data/local/hapaneld-helper supervised=1 attempt=1$' "$CASE_STATE" "$plan profile launches the canonical supervisor"
done
if grep -Fqx 'No profile-specific provisioning guidance.' "$(case_state system plan-helper-absent)/output"; then pass 'helper-absent case rendered the driverless profile plan'; else fail 'helper-absent case never rendered its plan'; fi
if grep -Fq -- '- Root helper [required]:' "$(case_state system plan-helper-required)/output"; then pass 'helper-required case rendered the helper-backed LED profile plan'; else fail 'helper-required case never rendered its plan'; fi

# A post-APK crash can leave a durable v2 TARGET journal with no surviving helper process.  Re-run
# the whole provisioner against that state: it must authenticate the same record twice around the
# zero-process observation, launch canonical exactly once, validate every exact protocol reply, and
# commit the retained journal before it is allowed to start a new transaction.
run_provision_case system stale_target EMPTY 0 0 0 0 1
stale_state="$CASE_STATE"
if [ "$CASE_STATUS" -ne 0 ]; then pass 'post-APK commit failure leaves a recoverable v2 TARGET transaction'; else fail 'commit-failure setup unexpectedly succeeded'; fi
stale_inspect="$stale_state/stale-inspect"
MOCK_STATE_DIR="$stale_state" "$HELPER_STATE" inspect > "$stale_inspect"
expect_line 'TRANSACTION_OPEN=1' "$stale_inspect" 'v2 TARGET journal remains durable after commit failure'
if cmp -s "$APK" "$stale_state/installed-apk"; then pass 'stale v2 TARGET setup has the exact target APK installed'; else fail 'stale v2 TARGET setup does not contain the target APK bytes'; fi
MOCK_STATE_DIR="$stale_state" "$HELPER_STATE" stop >/dev/null
run_provision_case system stale_target EMPTY 0 0 0 0 0 1
if [ "$CASE_STATUS" -eq 0 ]; then pass 'stale v2 TARGET with zero helper pids reconciles successfully'; else fail "stale v2 TARGET reconciliation failed (see $CASE_STATE/output)"; fi
resume_events="$stale_state/resume-events"
awk '/^STALE_DETECTED / { inside=1 } inside { print } inside && /^COMMIT_OK$/ { exit }' "$stale_state/helper-model/events" > "$resume_events"
expect_line 'DISCOVER_OK kind=system state=TARGET' "$resume_events" 'stale v2 reconciliation discovers the authenticated TARGET record'
if [ "$(grep -c '^STATUS_OK kind=system state=TARGET$' "$resume_events")" -eq 2 ]; then pass 'zero-pid resume reauthenticates TARGET immediately before canonical launch'; else fail 'zero-pid resume did not authenticate the same TARGET record twice'; fi
expect_line 'PROCESS_PROBE result=NO_HELPER_PROCESSES' "$resume_events" 'stale v2 reconciliation observes exact NO_HELPER_PROCESSES'
if [ "$(grep -c '^LAUNCH_OK path=/data/local/hapaneld-helper supervised=1 attempt=2$' "$resume_events")" -eq 1 ]; then pass 'zero-pid resume launches the canonical supervisor exactly once'; else fail 'zero-pid resume did not launch exactly one canonical supervisor'; fi
if [ "$(grep -Ec '^REQUEST_OK verb=(PING|COMPANIONCAPS|BUILDID|GUARDCAPS|GUARDSTATUS)$' "$resume_events")" -eq 5 ]; then pass 'resumed authority passes PING, capabilities, BUILDID, exact Guard and EMPTY status'; else fail 'resumed authority did not pass all five exact protocol validations'; fi
expect_no_event '^ROLLBACK_RESTARTED$' "$stale_state" 'target-APK resume commits instead of rolling back the authenticated TARGET journal'
expect_line 'COMMIT_OK' "$resume_events" 'stale TARGET journal commits after resumed authority validation'

# An armed Guard refusal is pre-swap and must preserve every authoritative byte/process/boot field.
for kind in system systemless hybrid; do
  run_provision_case "$kind" armed PREPARED
  if [ "$CASE_STATUS" -ne 0 ]; then pass "$kind refuses replacement while Guard is armed"; else fail "$kind replaced an armed Guard helper"; fi
  after="$(MOCK_STATE_DIR="$CASE_STATE" "$HELPER_STATE" fingerprint)"
  expect_eq "$CASE_BEFORE" "$after" "$kind armed refusal leaves helper state byte-for-byte unchanged"
  expect_event 'REPLACEMENT_SAFE result=GUARD_ARMED' "$CASE_STATE" "$kind exercised the helper-owned replacement gate"
  expect_event '^PROCESS_STOP$' "$CASE_STATE" "$kind armed candidate probe follows incumbent retirement"
  expect_event '^ROLLBACK_RESTARTED$' "$CASE_STATE" "$kind armed refusal restores the retired incumbent"
  expect_no_event '^INSTALL_OK|^LAUNCH_OK path=/data/local/hapaneld-helper' "$CASE_STATE" "$kind armed refusal occurs before canonical swap or target launch"
  if [ ! -e "$CASE_STATE/apk-install-attempted" ]; then pass "$kind armed refusal precedes APK replacement"; else fail "$kind armed refusal happened after an APK attempt"; fi
done

# Post-swap validation failures must restore the exact prior path/process/Guard/boot snapshot.
for kind in system systemless hybrid; do
  run_provision_case "$kind" badcaps EMPTY 0 1
  if [ "$CASE_STATUS" -ne 0 ]; then pass "$kind refuses a live helper with incomplete Guard capabilities"; else fail "$kind accepted incomplete live Guard capabilities"; fi
  after="$(MOCK_STATE_DIR="$CASE_STATE" "$HELPER_STATE" fingerprint)"
  expect_eq "$CASE_BEFORE" "$after" "$kind validation failure rolls back the complete authority snapshot"
  expect_event '^INSTALL_OK ' "$CASE_STATE" "$kind validation mutant reaches the post-swap check"
  expect_event '^ROLLBACK_RESTARTED$' "$CASE_STATE" "$kind validation mutant executes rollback"
  if [ ! -e "$CASE_STATE/apk-install-attempted" ]; then pass "$kind validation rollback precedes APK replacement"; else fail "$kind validation rollback happened after an APK attempt"; fi
done

run_provision_case system badstatus EMPTY 0 0 0 1
if [ "$CASE_STATUS" -ne 0 ]; then pass 'non-empty live GUARDSTATUS is rejected'; else fail 'non-empty live GUARDSTATUS reached false success'; fi
after="$(MOCK_STATE_DIR="$CASE_STATE" "$HELPER_STATE" fingerprint)"
expect_eq "$CASE_BEFORE" "$after" 'GUARDSTATUS validation failure restores the prior authority snapshot'
expect_event '^INSTALL_OK ' "$CASE_STATE" 'GUARDSTATUS mutant reaches the post-swap check'
expect_event '^ROLLBACK_RESTARTED$' "$CASE_STATE" 'GUARDSTATUS mutant executes rollback'
if [ ! -e "$CASE_STATE/apk-install-attempted" ]; then pass 'GUARDSTATUS rollback precedes APK replacement'; else fail 'GUARDSTATUS rollback happened after an APK attempt'; fi

# Readiness polling is allowed; starting a second supervisor is not.
run_provision_case system delayed EMPTY 5
if [ "$CASE_STATUS" -eq 0 ]; then pass 'delayed helper readiness succeeds within the bounded poll window'; else fail "delayed helper readiness failed (see $CASE_STATE/output)"; fi
expect_event '^LAUNCH_OK path=/data/local/hapaneld-helper supervised=1 attempt=1$' "$CASE_STATE" 'delayed readiness uses the original supervisor launch'
if ! grep -Eq 'LAUNCH_OK .*attempt=2|LAUNCH_REFUSED .*duplicate' "$CASE_STATE/helper-model/events"; then pass 'delayed readiness never launches a second supervisor'; else fail 'delayed readiness retried by launching another supervisor'; fi

run_provision_case system duplicate EMPTY 0 0 1
if [ "$CASE_STATUS" -ne 0 ]; then pass 'duplicate live authority is rejected by validation'; else fail 'duplicate live authority reached false success'; fi
after="$(MOCK_STATE_DIR="$CASE_STATE" "$HELPER_STATE" fingerprint)"
expect_eq "$CASE_BEFORE" "$after" 'duplicate-authority validation failure restores the prior authority snapshot'
expect_event '^LAUNCH_MUTANT duplicate-authority' "$CASE_STATE" 'duplicate-authority mutant was actually exercised'
expect_event '^ROLLBACK_RESTARTED$' "$CASE_STATE" 'duplicate-authority mutant rolls back'

printf '%s passed, %s failed\n' "$passes" "$failures"
[ "$failures" -eq 0 ]
