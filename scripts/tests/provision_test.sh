#!/usr/bin/env bash
# Black-box regression tests for the novice-facing provisioning contract.
# All adb and HTTP interactions are faked; this script never contacts a panel or the network.
set -u

PROVISION_TEST_SCOPE="${PROVISION_TEST_SCOPE:-all}"
case "$PROVISION_TEST_SCOPE" in
  all) exec bash "$(dirname "${BASH_SOURCE[0]}")/provision_gate_parallel.sh" ;;
  wrapper-admission|shard-wrapper-inspection)
    exec bash "$(dirname "${BASH_SOURCE[0]}")/install_legacy_database_gate_test.sh" ;;
  shard-helper-recovery)
    exec bash "$(dirname "${BASH_SOURCE[0]}")/root_helper_authority_test.sh" ;;
  admission|core|all|shard-admission|shard-read-only|shard-uninstall) ;;
  *) echo "unknown PROVISION_TEST_SCOPE: $PROVISION_TEST_SCOPE" >&2; exit 2 ;;
esac

provision_scope_is() {
  local candidate
  for candidate in "$@"; do
    [ "$PROVISION_TEST_SCOPE" != "$candidate" ] || return 0
  done
  return 1
}

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
PROVISION="${PROVISION_UNDER_TEST:-$ROOT/scripts/provision.sh}"
UPDATE_FLEET="$ROOT/scripts/update-fleet.sh"
FIXTURES="$ROOT/scripts/tests/fixtures"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
export PATH="$FIXTURES:$PATH"
export MOCK_TARGET=panel.test:5555 MOCK_CALL_LOG="$TMP/calls.log" MOCK_STATE_DIR="$TMP" PROVISION_TEST_STATE_DIR="$TMP"
export PROVISION_TEST_CURL="$FIXTURES/curl"
export MOCK_HELPER_BUILD_ID="$(PATH=/usr/bin:/bin "$ROOT/helper/source-id.sh")"
: > "$MOCK_CALL_LOG"
APK="$TMP/ha-paneld.apk"
printf 'unused refused install candidate\n' > "$APK"
LAST_OUTPUT=""; LAST_STATUS=0; passes=0; failures=0; tests=0
curl() {
  local arg output_file="" want_output=0 want_status=0 status
  case "$*" in
    *'/api/v1/config/export?include_secrets=1'*)
      if [ "${MOCK_EXPORT:-ok}" != ok ]; then
        for arg in "$@"; do
          if [ "$want_output" = 1 ]; then output_file="$arg"; want_output=0; continue; fi
          case "$arg" in
            -o|--output) want_output=1 ;;
            -w|--write-out) want_status=1 ;;
          esac
        done
        if [ -n "$output_file" ]; then
          case "$MOCK_EXPORT" in
            approval) printf '{"ok":false,"error":"approval-required","approval_id":"backup-test"}\n' > "$output_file" ;;
            malformed-approval) printf '{"ok":false,"error":"unexpected"}\n' > "$output_file" ;;
            unexpected-2xx) printf '{"status":"created"}\n' > "$output_file" ;;
          esac
        else
          case "$MOCK_EXPORT" in
            approval) printf '{"ok":false,"error":"approval-required","approval_id":"backup-test"}\n' ;;
            malformed-approval) printf '{"ok":false,"error":"unexpected"}\n' ;;
            unexpected-2xx) printf '{"status":"created"}\n' ;;
          esac
        fi
        if [ "$want_status" = 1 ]; then
          case "$MOCK_EXPORT" in
            approval|malformed-approval) printf '202' ;;
            unexpected-2xx) printf '201' ;;
          esac
        fi
        return 0
      fi
      command "$PROVISION_TEST_CURL" "$@"; status=$?
      [ "$status" -ne 0 ] || printf '200'
      return "$status"
      ;;
  esac
  command "$PROVISION_TEST_CURL" "$@"
}
export -f curl
run_provision() {
  local no_installed="${MOCK_NO_INSTALLED_PACKAGE:-1}"
  : > "$MOCK_CALL_LOG"
  rm -f "$TMP/installed-apk" "$TMP/config-schema-count"
  [ "$no_installed" = 1 ] || printf 'previous installed apk\n' > "$TMP/installed-apk"
  LAST_OUTPUT="$TMP/output.txt"
  MOCK_HEALTH="${MOCK_HEALTH:-ok}" \
  MOCK_HEALTH_READY_AFTER="${MOCK_HEALTH_READY_AFTER:-3}" \
  MOCK_HEALTH_HANG_SECONDS="${MOCK_HEALTH_HANG_SECONDS:-3}" \
  MOCK_HAND_BACK="${MOCK_HAND_BACK:-ok}" \
  MOCK_STORAGE_HEALTH="${MOCK_STORAGE_HEALTH:-healthy}" \
  MOCK_POWER_SAFETY="${MOCK_POWER_SAFETY:-safe}" \
  MOCK_VERIFY="${MOCK_VERIFY:-ok}" \
  MOCK_EXPORT="${MOCK_EXPORT:-ok}" \
  MOCK_ADB_STATE="${MOCK_ADB_STATE:-device}" \
  MOCK_PLAN="${MOCK_PLAN:-ok}" \
  MOCK_SETUP="${MOCK_SETUP:-complete}" \
  MOCK_INSTALLED_VCODE="${MOCK_INSTALLED_VCODE:-513}" \
  MOCK_PM_PATH="${MOCK_PM_PATH:-ok}" \
  MOCK_PM_LIVENESS="${MOCK_PM_LIVENESS:-ok}" \
  MOCK_PM_PROBE="${MOCK_PM_PROBE:-ok}" \
  MOCK_NO_INSTALLED_PACKAGE="$no_installed" \
  MOCK_PM_UNINSTALLED_RECORD="${MOCK_PM_UNINSTALLED_RECORD:-absent}" \
  MOCK_ROOT="${MOCK_ROOT:-1}" \
  HAPANELD_SKIP_AUTO_EXPORT="${HAPANELD_SKIP_AUTO_EXPORT:-1}" \
  MOCK_SU_DIALECT="${MOCK_SU_DIALECT:-join}" \
  MOCK_STATE_DIR="$TMP" \
  PROVISIONING_PLAN_TIMEOUT_SECONDS="${PROVISIONING_PLAN_TIMEOUT_SECONDS:-2}" \
  PANEL_POST_CONNECT_TIMEOUT_SECONDS="${PANEL_POST_CONNECT_TIMEOUT_SECONDS:-1}" \
  PANEL_POST_TIMEOUT_SECONDS="${PANEL_POST_TIMEOUT_SECONDS:-1}" \
  STORAGE_HEALTH_VERIFY_ATTEMPTS="${STORAGE_HEALTH_VERIFY_ATTEMPTS:-3}" \
  STORAGE_HEALTH_VERIFY_POLL_SECONDS="${STORAGE_HEALTH_VERIFY_POLL_SECONDS:-0}" \
  STORAGE_HEALTH_PACKAGE_QUERY_SECONDS="${STORAGE_HEALTH_PACKAGE_QUERY_SECONDS:-2}" \
    bash "$PROVISION" "$@" > "$LAST_OUTPUT" 2>&1
  LAST_STATUS=$?
}
pass() {
  passes=$((passes + 1))
  tests=$((tests + 1))
  printf 'ok %d - %s\n' "$tests" "$1"
}

fail_test() {
  failures=$((failures + 1))
  tests=$((tests + 1))
  printf 'not ok %d - %s\n' "$tests" "$1" >&2
  if [ -f "$LAST_OUTPUT" ]; then
    sed 's/^/  | /' "$LAST_OUTPUT" >&2
  fi
}

finish_provision_test() {
  printf '1..%d\n' "$tests"
  if [ "$failures" -ne 0 ]; then
    printf '%d assertion(s) failed\n' "$failures" >&2
    exit 1
  fi
  exit 0
}

assert_count() {
  actual="$1"; expected="$2"; description="$3"
  if [ "$actual" -eq "$expected" ] 2>/dev/null; then pass "$description"
  else fail_test "$description (expected $expected, got ${actual:-nothing})"; fi
}

assert_status() {
  expected="$1"
  description="$2"
  if [ "$LAST_STATUS" -eq "$expected" ]; then pass "$description"
  else fail_test "$description (expected status $expected, got $LAST_STATUS)"; fi
}

assert_success() {
  description="$1"
  if [ "$LAST_STATUS" -eq 0 ]; then pass "$description"
  else fail_test "$description (status $LAST_STATUS)"; fi
}

# The refusal provision.sh actually printed. `fail` emits exactly one "x <headline>" line and exits,
# so the last one is why the run ended - a stable identifier that needs no production change to read.
# Colour is off when stdout is not a tty, but the escapes are stripped anyway so a coloured capture
# cannot silently stop matching.
last_refusal_headline() {
  [ -f "$LAST_OUTPUT" ] || return 0
  sed 's/\x1b\[[0-9;]*m//g' "$LAST_OUTPUT" | sed -n 's/^[[:space:]]*✗ //p' | tail -n 1
}

# Refusals that mean the harness or the host broke rather than the condition under test.
#
# The first two name a root-shell probe that returned nothing recognisable; provision.sh reports
# those as unanswered rather than as a partition state. The rest are partition or storage verdicts no
# ordinary case sets up, so a case reaching one without asking for it did not reach the behaviour it
# names. That is not hypothetical: on the CI runner, `late primary unreadable` refused at the
# read-only-/system fallthrough (then also reached by an empty capture), `assert_failure` passed, and
# only the two content assertions after it failed.
#
# A case that MEANS to assert one of these passes it as assert_failure's second argument.
UNRELATED_REFUSALS='the /system layout probe returned no recognisable answer|the /vendor/etc/init write probe returned no recognisable answer|the panel has read-only /system and no verified systemless boot-service runner|/vendor/etc/init is not writable for the hybrid root helper|/vendor/etc/init has no free space for the hybrid root helper|the root-helper transaction could not be promoted into protected storage'

# assert_failure "<description>" ["<expected refusal pattern>"]
#
# With a second argument the refusal headline must match it, so the case passes only on the reason it
# exists to produce. Without one the run must at least not have refused from one of the unrelated
# paths above. The two are different strengths and the second argument is the strong one; a bare call
# rejects a class, it does not pin a reason.
assert_failure() {
  description="$1"
  expected_refusal="${2-}"
  refusal_headline="$(last_refusal_headline)"
  if [ "$LAST_STATUS" -eq 0 ]; then
    fail_test "$description (unexpected status 0)"
    return
  fi
  if [ -n "$expected_refusal" ]; then
    if printf '%s\n' "$refusal_headline" | grep -Eq -- "$expected_refusal"; then pass "$description"
    else fail_test "$description (refused for the wrong reason: wanted /$expected_refusal/, got '${refusal_headline:-no refusal line at all}')"; fi
    return
  fi
  # Once provision.sh has printed its banner, every deliberate refusal goes through `fail` or the ERR
  # trap, both of which print a headline. A run past the banner that exits non-zero without one aborted
  # rather than refused; a bare assertion passing on that is how a silent abort stayed green. The
  # installer and fleet wrappers refuse in their own formats, so they are not held to this.
  if [ -z "$refusal_headline" ]; then
    if grep -Fq 'ha-paneld provisioning' "$LAST_OUTPUT" 2>/dev/null; then
      fail_test "$description (no refusal line at all: the provisioner aborted without saying why)"
    else
      pass "$description"
    fi
    return
  fi
  if printf '%s\n' "$refusal_headline" | grep -Eq -- "$UNRELATED_REFUSALS"; then
    fail_test "$description (refused from an unrelated path: '$refusal_headline'; if this case means to assert that, pass it as assert_failure's second argument)"
    return
  fi
  pass "$description"
}

assert_contains() {
  pattern="$1"
  description="$2"
  if grep -Eqi -- "$pattern" "$LAST_OUTPUT"; then pass "$description"
  else fail_test "$description (missing pattern: $pattern)"; fi
}

assert_not_contains() {
  pattern="$1"
  file="$2"
  description="$3"
  if grep -Eqi -- "$pattern" "$file"; then fail_test "$description (unexpected pattern: $pattern)"
  else pass "$description"; fi
}

assert_log_contains() {
  pattern="$1"
  description="$2"
  if grep -Eqi -- "$pattern" "$MOCK_CALL_LOG"; then pass "$description"
  else fail_test "$description (missing call pattern: $pattern)"; fi
}

# Export is a recovery operation. It must be possible before resolving or installing an APK.
EXPORT="$TMP/panel-backup.json"
if provision_scope_is admission core all shard-admission; then
  for installed_identity in clean successor legacy; do
    no_installed=1; legacy_installed=0; probe=ok
    case "$installed_identity" in
      successor) no_installed=0 ;;
      legacy) legacy_installed=1 ;;
    esac
    MOCK_NO_INSTALLED_PACKAGE="$no_installed" MOCK_LEGACY_INSTALLED="$legacy_installed" MOCK_PM_PROBE="$probe" \
      run_provision "$MOCK_TARGET" --apk "$APK"
    assert_failure "$installed_identity panel refuses standalone APK installation" "requires? live Panel Assistant"
    assert_not_contains '^adb |^curl |^openssl |^aapt ' "$MOCK_CALL_LOG" "$installed_identity refusal precedes network and panel access"
  done
  unset installed_identity no_installed legacy_installed probe
  run_provision "$MOCK_TARGET" --export "$TMP/refused-candidate-export.json" --apk "$APK"
  assert_failure "read-only export cannot authorize a candidate APK" "requires? live Panel Assistant"
  assert_not_contains '^adb |^curl ' "$MOCK_CALL_LOG" "candidate plus export refusal precedes panel access"
  run_provision "$MOCK_TARGET"
  assert_failure "clean first installation without options requires live admission" "requires? live Panel Assistant"
  assert_not_contains '^adb |^curl ' "$MOCK_CALL_LOG" "default first-install refusal precedes panel access"
  run_provision "$MOCK_TARGET" --latest --reset-config
  assert_failure "reset cannot authorize a standalone panel install" "requires? live Panel Assistant"
  assert_not_contains '^adb |^curl ' "$MOCK_CALL_LOG" "reset refusal precedes network and panel access"
  for fleet_arguments in local latest bridge stdin; do
    : > "$MOCK_CALL_LOG"
    LAST_OUTPUT="$TMP/fleet-$fleet_arguments.txt"
    case "$fleet_arguments" in
      local) bash "$UPDATE_FLEET" --apk "$APK" -- "$MOCK_TARGET" > "$LAST_OUTPUT" 2>&1 ;;
      latest) bash "$UPDATE_FLEET" --latest --prerelease -- "$MOCK_TARGET" > "$LAST_OUTPUT" 2>&1 ;;
      bridge) bash "$UPDATE_FLEET" --bridge-apk "$APK" -- "$MOCK_TARGET" > "$LAST_OUTPUT" 2>&1 ;;
      stdin) printf '%s\n' "$MOCK_TARGET" | bash "$UPDATE_FLEET" --latest > "$LAST_OUTPUT" 2>&1 ;;
    esac
    LAST_STATUS=$?
    assert_failure "fleet $fleet_arguments route refuses standalone installation"
    assert_contains 'require live Panel Assistant' "fleet $fleet_arguments names the admission owner"
    assert_not_contains '^adb |^curl ' "$MOCK_CALL_LOG" "fleet $fleet_arguments starts no worker or download"
  done
  unset fleet_arguments
  LAST_OUTPUT="$TMP/fleet-help.txt"
  bash "$UPDATE_FLEET" --help > "$LAST_OUTPUT" 2>&1
  LAST_STATUS=$?
  assert_success "fleet help remains usable"
  assert_contains 'require live Panel Assistant' "fleet help directs the supported installer"
fi
[ "$PROVISION_TEST_SCOPE" != shard-admission ] || finish_provision_test
[ "$PROVISION_TEST_SCOPE" != admission ] || finish_provision_test

if provision_scope_is core all shard-read-only; then
MOCK_NO_INSTALLED_PACKAGE=0 run_provision "$MOCK_TARGET" --export "$EXPORT"
assert_success "export-only succeeds"
if [ -s "$EXPORT" ]; then pass "export-only writes a non-empty bundle"; else fail_test "export-only writes a non-empty bundle"; fi
if [ "$(stat -c '%a' "$EXPORT")" = 600 ]; then pass "secret export is owner-readable only"; else fail_test "secret export is owner-readable only"; fi
assert_not_contains '^adb .* install( |$)' "$MOCK_CALL_LOG" "export-only never installs an APK"
assert_not_contains '^adb .* (install|shell (settings put|appops set|pm grant|am start|monkey -p io\.panelassistant\.android))|^curl .* (-X POST|--data|--data-urlencode)' "$MOCK_CALL_LOG" "export-only performs no panel mutation"

FAILED_EXPORT="$TMP/failed-backup.json"
MOCK_EXPORT=fail MOCK_NO_INSTALLED_PACKAGE=0 run_provision "$MOCK_TARGET" --export "$FAILED_EXPORT"
assert_failure "failed explicit export returns nonzero" \
  "config export returned unexpected HTTP"
assert_not_contains '^adb .* install( |$)' "$MOCK_CALL_LOG" "failed explicit export stops before APK install"
if [ ! -e "$FAILED_EXPORT" ]; then pass "failed backup leaves no misleading output file"; else fail_test "failed backup leaves no misleading output file"; fi

APPROVAL_EXPORT="$TMP/approval-required-backup.json"
MOCK_EXPORT=approval MOCK_NO_INSTALLED_PACKAGE=0 run_provision "$MOCK_TARGET" --export "$APPROVAL_EXPORT"
assert_failure "approval-required backup returns nonzero"
assert_contains 'config export requires approval on the panel' "approval-required config export is not reported as successful"
assert_contains 'Review approvals.*approve the config export.*retry the identical --export command' "approval-required config export gives the on-panel approval and retry path"
assert_not_contains '^adb .* install( |$)' "$MOCK_CALL_LOG" "approval-required backup stops before APK install"
if [ ! -e "$APPROVAL_EXPORT" ]; then pass "approval response is not retained as a backup"; else fail_test "approval response is not retained as a backup"; fi

for export_case in malformed-approval unexpected-2xx; do
  REJECTED_EXPORT="$TMP/rejected-${export_case}-backup.json"
  MOCK_EXPORT="$export_case" MOCK_NO_INSTALLED_PACKAGE=0 run_provision "$MOCK_TARGET" --export "$REJECTED_EXPORT"
  assert_failure "$export_case backup response returns nonzero"
  assert_contains 'config export returned unexpected HTTP' "$export_case config export response is rejected"
  assert_not_contains '^adb .* install( |$)' "$MOCK_CALL_LOG" "$export_case backup response stops before APK install"
  if [ ! -e "$REJECTED_EXPORT" ]; then pass "$export_case response is not retained as a backup"; else fail_test "$export_case response is not retained as a backup"; fi
done

# The same failure on an explicitly requested --export is the failure of the requested deliverable.
rm -rf "$TMP/auto-backups"
EXPLICIT_STRICT_EXPORT="$TMP/explicit-strict-backup.json"
MOCK_EXPORT=fail HAPANELD_SKIP_AUTO_EXPORT=0 MOCK_NO_INSTALLED_PACKAGE=0 run_provision "$MOCK_TARGET" --export "$EXPLICIT_STRICT_EXPORT"
assert_failure "explicit export failure is reported" \
  "config export returned unexpected HTTP"
assert_not_contains '^adb .* install( |$)' "$MOCK_CALL_LOG" "explicit export failure stops before APK mutation"
if [ ! -e "$EXPLICIT_STRICT_EXPORT" ]; then
  pass "a failed explicit export publishes no file"
else
  fail_test "a failed explicit export publishes no file"
fi

SYMLINK_TARGET="$TMP/symlink-target.json"
SYMLINK_EXPORT="$TMP/symlink-backup.json"
printf 'do not replace\n' > "$SYMLINK_TARGET"
ln -s "$SYMLINK_TARGET" "$SYMLINK_EXPORT"
MOCK_NO_INSTALLED_PACKAGE=0 run_provision "$MOCK_TARGET" --export "$SYMLINK_EXPORT"
assert_failure "secret export refuses a symlink destination"
assert_contains 'refusing to replace a symlink' "symlink refusal explains the safe destination requirement"
if [ "$(cat "$SYMLINK_TARGET")" = 'do not replace' ]; then pass "symlink target remains untouched"; else fail_test "symlink target remains untouched"; fi

# Verification is explicitly read-only and must not even attempt installation.
MOCK_NO_INSTALLED_PACKAGE=0 run_provision "$MOCK_TARGET" --verify
assert_success "verify-only succeeds for a healthy panel"
assert_contains 'Detected panel: Test Panel' "verify-only displays the app-owned hardware profile guidance"
assert_contains 'storage health: healthy' "verify-only reports healthy storage"
assert_contains 'Configuration schema: ready' "verify-only proves the Configure settings schema is usable"
assert_contains 'panel power safety: safe' "verify-only reports the app-owned power classification"
assert_log_contains '^curl .* /api/v1/status$|^curl .*http://panel\.test:8888/api/v1/status$' "verify-only reads the shared storage-health status"
assert_log_contains '^curl .* /api/v1/config/schema$|^curl .*http://panel\.test:8888/api/v1/config/schema$' "verify-only reads the Configuration settings schema"
assert_log_contains '^curl .* /api/v1/power-safety/state$|^curl .*http://panel\.test:8888/api/v1/power-safety/state$' "verify-only reads the one-token app-owned power state"
assert_log_contains '^curl .* /api/v1/provisioning/plan\.txt$|^curl .*http://panel\.test:8888/api/v1/provisioning/plan\.txt$' "verify-only reads the provisioning plan"
assert_not_contains '^adb .* install( |$)' "$MOCK_CALL_LOG" "verify-only never installs an APK"
assert_not_contains '^adb .* (install|shell (settings put|appops set|pm grant|am start|monkey -p io\.panelassistant\.android))|^curl .* (-X POST|--data|--data-urlencode)' "$MOCK_CALL_LOG" "verify-only performs no panel mutation"

assert_count "$(grep -c -- '--max-time 60 .*/api/v1/config/schema$' "$MOCK_CALL_LOG")" 1 "a ready Configuration schema is read once within the total request budget"

# The notification permission is read back from the package manager on --verify as well, so a panel an
# installer missed, or one where a person turned notifications off, is reported rather than passed.
assert_contains 'notification permission granted' "verify-only reports the notification permission"
MOCK_POST_NOTIFICATIONS_HELD=0 MOCK_NO_INSTALLED_PACKAGE=0 run_provision "$MOCK_TARGET" --verify
assert_failure "verify-only fails a panel without the notification permission"
assert_contains 'ha-paneld . Notifications' "verify-only names the missing notification permission and its recovery"
MOCK_SDK=30 MOCK_POST_NOTIFICATIONS_HELD=0 MOCK_NO_INSTALLED_PACKAGE=0 run_provision "$MOCK_TARGET" --verify
assert_success "verify-only does not fail a pre-Android-13 panel for notifications"
assert_contains 'not a runtime permission before Android 13' "verify-only tells a pre-Android-13 panel why notifications are not checked"
unset MOCK_SDK MOCK_POST_NOTIFICATIONS_HELD

# A verify straight after a deploy can reach the app during its HTTP startup race; that read retries
# after a short pause, recorded here by a sleep that logs instead of waiting.
SCHEMA_SLEEP_DIR="$TMP/schema-retry-sleep"
mkdir -p "$SCHEMA_SLEEP_DIR"
printf '#!/usr/bin/env bash\nprintf "sleep %%s\\n" "$*" >> "%s/calls"\n' "$SCHEMA_SLEEP_DIR" > "$SCHEMA_SLEEP_DIR/sleep"
chmod +x "$SCHEMA_SLEEP_DIR/sleep"
: > "$SCHEMA_SLEEP_DIR/calls"
PATH="$SCHEMA_SLEEP_DIR:$PATH" MOCK_CONFIG_SCHEMA=ready-after-2 MOCK_NO_INSTALLED_PACKAGE=0 run_provision "$MOCK_TARGET" --verify
assert_success "verify-only accepts a Configuration schema that answers on the second attempt"
assert_contains 'Configuration schema: ready' "a schema that answers after the startup race is ready"
assert_count "$(grep -c '/api/v1/config/schema$' "$MOCK_CALL_LOG")" 2 "an unanswered schema read is retried until it answers"
assert_count "$(grep -c '^sleep 1$' "$SCHEMA_SLEEP_DIR/calls")" 1 "the schema retry pauses before its second attempt"
assert_log_contains 'curl .*--max-time 60 .*/api/v1/config/schema$' \
  "the default schema budget covers measured cold startup"

CONFIG_SCHEMA_VERIFY_TIMEOUT_SECONDS=8 MOCK_CONFIG_SCHEMA=cold-six-seconds \
  MOCK_NO_INSTALLED_PACKAGE=0 run_provision "$MOCK_TARGET" --verify
assert_success "a cold schema response can finish beyond the former five-second request cap"
assert_count "$(grep -c '/api/v1/config/schema$' "$MOCK_CALL_LOG")" 1 \
  "a cold schema completes in one request without restarting its wait"
assert_log_contains 'curl .*--max-time 8 .*/api/v1/config/schema$' \
  "a schema request receives its full remaining total budget"

MOCK_CONFIG_SCHEMA=transport-fail MOCK_NO_INSTALLED_PACKAGE=0 run_provision "$MOCK_TARGET" --verify
assert_failure "verify-only rejects an unavailable Configuration schema"
assert_contains 'Configuration schema: unavailable or malformed' "schema transport failure names the broken user surface"
assert_count "$(grep -c '/api/v1/config/schema$' "$MOCK_CALL_LOG")" 4 "a schema that never answers stops after the bounded attempts"

MOCK_CONFIG_SCHEMA=empty MOCK_NO_INSTALLED_PACKAGE=0 run_provision "$MOCK_TARGET" --verify
assert_failure "verify-only rejects an empty Configuration schema after its retries"
assert_contains 'Configuration schema: unavailable or malformed' "an empty schema names the broken user surface"
assert_count "$(grep -c '/api/v1/config/schema$' "$MOCK_CALL_LOG")" 4 "an empty schema body is retried like an unanswered read"

schema_hang_started=$SECONDS
CONFIG_SCHEMA_VERIFY_TIMEOUT_SECONDS=3 MOCK_CONFIG_SCHEMA=hang MOCK_NO_INSTALLED_PACKAGE=0 run_provision "$MOCK_TARGET" --verify
schema_hang_elapsed=$((SECONDS - schema_hang_started))
assert_failure "verify-only rejects a Configuration schema that never responds"
assert_contains 'Configuration schema: unavailable or malformed' "a hanging schema names the broken user surface"
schema_hang_budget="$(grep '/api/v1/config/schema$' "$MOCK_CALL_LOG" | sed -n 's/.*--max-time \([0-9]*\) .*/\1/p' | awk '{ total += $1 } END { print total + 0 }')"
if [ "$schema_hang_budget" -ge 1 ] && [ "$schema_hang_budget" -le 4 ] && [ "$schema_hang_elapsed" -le 10 ]; then
  pass "a hanging schema read is ended by the total verify deadline"
else
  fail_test "a hanging schema read is ended by the total verify deadline (request budget ${schema_hang_budget}s, run ${schema_hang_elapsed}s)"
fi

# A pause that overruns must not let a request start after the deadline: this pause fits on paper but
# really takes the whole three-second budget.
SCHEMA_OVERRUN_DIR="$TMP/schema-retry-overrun"
mkdir -p "$SCHEMA_OVERRUN_DIR"
printf '#!/usr/bin/env bash\ncase "$1" in 1|2|3) /bin/sleep 3 ;; *) /bin/sleep "$1" ;; esac\n' > "$SCHEMA_OVERRUN_DIR/sleep"
chmod +x "$SCHEMA_OVERRUN_DIR/sleep"
PATH="$SCHEMA_OVERRUN_DIR:$PATH" CONFIG_SCHEMA_VERIFY_TIMEOUT_SECONDS=3 MOCK_CONFIG_SCHEMA=transport-fail \
  MOCK_NO_INSTALLED_PACKAGE=0 run_provision "$MOCK_TARGET" --verify
assert_failure "verify-only rejects a schema still unavailable when an overrunning pause ends" \
  "Configuration schema: unavailable or malformed"
assert_count "$(grep -c '/api/v1/config/schema$' "$MOCK_CALL_LOG")" 1 "no schema request starts after an overrunning pause reaches the deadline"

MOCK_CONFIG_SCHEMA=malformed MOCK_NO_INSTALLED_PACKAGE=0 run_provision "$MOCK_TARGET" --verify
assert_failure "verify-only rejects a malformed Configuration schema"
assert_contains 'Configuration schema: unavailable or malformed' "malformed schema names the broken user surface"
assert_count "$(grep -c '/api/v1/config/schema$' "$MOCK_CALL_LOG")" 1 "a malformed schema fails without further requests"

MOCK_CONFIG_SCHEMA=invalid-fields MOCK_NO_INSTALLED_PACKAGE=0 run_provision "$MOCK_TARGET" --verify
assert_failure "verify-only rejects schema entries without string keys and labels"
assert_contains 'Configuration schema: unavailable or malformed' "invalid schema fields name the broken user surface"

MOCK_CONFIG_SCHEMA=reordered MOCK_NO_INSTALLED_PACKAGE=0 run_provision "$MOCK_TARGET" --verify
assert_success "verify-only accepts a compatible schema independent of object-member order"
assert_contains 'Configuration schema: ready' "reordered compatible schema remains usable"

MOCK_POWER_SAFETY=caution MOCK_NO_INSTALLED_PACKAGE=0 run_provision "$MOCK_TARGET" --verify
assert_success "a caution power classification remains advisory"
assert_contains 'panel power safety: caution.*only one observed power guard' "power caution explains the bounded risk"
assert_contains 'Use Repair when offered.*explicitly hidden' "power caution distinguishes repairable from healthy app-only states"
assert_not_contains '^adb .* shell settings put|^curl .* (-X POST|--data|--data-urlencode)' "$MOCK_CALL_LOG" "power warning verification never repairs settings"

MOCK_POWER_SAFETY=unknown MOCK_NO_INSTALLED_PACKAGE=0 run_provision "$MOCK_TARGET" --verify
assert_success "unknown power probes remain an explicit warning"
assert_contains 'panel power safety: unknown.*did not establish an effective guard' "unknown power probes are not reported safe"

MOCK_POWER_SAFETY=at_risk MOCK_NO_INSTALLED_PACKAGE=0 run_provision "$MOCK_TARGET" --verify
assert_failure "an at-risk power classification fails verification"
assert_contains 'panel power safety: at risk.*screen-off can leave this panel unreachable' "at-risk power explains the reachability failure"
assert_contains 'use Repair when offered.*manual guidance' "at-risk power gives capability-aware recovery guidance"
assert_not_contains '^adb .* shell settings put|^curl .* (-X POST|--data|--data-urlencode)' "$MOCK_CALL_LOG" "at-risk verification reports without mutating power settings"

MOCK_POWER_SAFETY=missing MOCK_NO_INSTALLED_PACKAGE=0 run_provision "$MOCK_TARGET" --verify
assert_success "a legacy build without power status remains verifiable"
assert_contains 'panel power safety: status unavailable' "legacy power status is not invented"
assert_contains 'power-safety/state.*no repair was attempted' "legacy power status remains read-only"

MOCK_POWER_SAFETY=malformed MOCK_NO_INSTALLED_PACKAGE=0 run_provision "$MOCK_TARGET" --verify
assert_success "malformed power status remains an explicit advisory warning"
assert_contains 'panel power safety: unrecognised status; not treating it as safe' "malformed power status is not reported safe"

for malformed_safe in truncated-safe garbage-safe duplicate-safe control-safe nul-safe; do
  MOCK_POWER_SAFETY="$malformed_safe" MOCK_NO_INSTALLED_PACKAGE=0 run_provision "$MOCK_TARGET" --verify
  assert_success "$malformed_safe power status remains an explicit advisory warning"
  assert_contains 'panel power safety: unrecognised status; not treating it as safe' "$malformed_safe power status is never reported safe"
  assert_not_contains 'panel power safety: safe' "$LAST_OUTPUT" "$malformed_safe power status cannot produce a green result"
done

MOCK_STORAGE_HEALTH=unchecked MOCK_NO_INSTALLED_PACKAGE=0 run_provision "$MOCK_TARGET" --verify
assert_success "verify-only accepts storage health before the first scheduled check"
assert_contains 'storage health: not checked yet' "unchecked storage health is explained without failing verification"

MOCK_STORAGE_HEALTH=legacy MOCK_NO_INSTALLED_PACKAGE=0 run_provision "$MOCK_TARGET" --verify
assert_success "verify-only remains compatible with panels before storage-health status"
assert_not_contains 'storage health:' "$LAST_OUTPUT" "legacy verification does not invent a storage-health result"

MOCK_STORAGE_HEALTH=legacy-json MOCK_NO_INSTALLED_PACKAGE=0 run_provision "$MOCK_TARGET" --verify
assert_success "verify-only accepts a successful legacy status response without storage health"
assert_not_contains 'storage health:' "$LAST_OUTPUT" "legacy status JSON remains advisory during read-only verification"

MOCK_STORAGE_HEALTH=transport-fail MOCK_NO_INSTALLED_PACKAGE=0 run_provision "$MOCK_TARGET" --verify
assert_failure "verify-only rejects storage-status transport failure"
assert_contains 'status endpoint could not be reached' "read-only transport failure is not mistaken for a legacy response"

MOCK_STORAGE_HEALTH=missing-state MOCK_NO_INSTALLED_PACKAGE=0 run_provision "$MOCK_TARGET" --verify
assert_failure "verify-only rejects a current storage-health object with no state"
assert_contains 'storage health: malformed status response' "missing current-build storage state is identified"

MOCK_STORAGE_HEALTH=malformed MOCK_NO_INSTALLED_PACKAGE=0 run_provision "$MOCK_TARGET" --verify
assert_failure "verify-only rejects malformed current status JSON"
assert_contains 'storage health: malformed status response' "malformed current-build status is identified"

MOCK_STORAGE_HEALTH=future-state MOCK_NO_INSTALLED_PACKAGE=0 run_provision "$MOCK_TARGET" --verify
assert_failure "verify-only rejects an unknown current storage-health state"
assert_contains "storage health: unrecognised state 'future-state'" "unknown current-build storage state is identified"

MOCK_STORAGE_HEALTH=warning MOCK_NO_INSTALLED_PACKAGE=0 run_provision "$MOCK_TARGET" --verify
assert_success "storage warning remains advisory"
assert_contains 'storage health: warning.*pressure is elevated' "storage warning clearly identifies pressure"
assert_contains 'Review panel free space and WAL/database growth.*then check' "storage warning provides a recovery action"

MOCK_STORAGE_HEALTH=critical MOCK_NO_INSTALLED_PACKAGE=0 run_provision "$MOCK_TARGET" --verify
assert_failure "critical storage makes verification fail"
assert_contains 'storage health: critical.*pressure is critical' "critical storage identifies the failing condition"
assert_contains 'Recover panel headroom or address WAL growth before writes fail.*re-run verification' "critical storage failure provides a recovery action"

MOCK_STORAGE_HEALTH=database_failure MOCK_NO_INSTALLED_PACKAGE=0 run_provision "$MOCK_TARGET" --verify
assert_failure "database failure makes verification fail"
assert_contains 'storage health: database failure.*SQLite writes or health checks failed' "database failure identifies the failing condition"
assert_contains 'Preserve ha-paneld\.db.*inspect.*/api/v1/diag.*re-run verification' "database failure protects the database and provides a recovery action"

fi
[ "$PROVISION_TEST_SCOPE" != shard-read-only ] || finish_provision_test

if provision_scope_is core all shard-uninstall; then
# MOCK_LEGACY_INSTALLED=1 with no successor is the state of every panel in the field today: the legacy
# application id only. The uninstall has to find that and remove it, not the successor id it is being
# migrated towards.
MOCK_NO_INSTALLED_PACKAGE=1 MOCK_LEGACY_INSTALLED=1 run_provision "$MOCK_TARGET" --uninstall
assert_success "an uninstall that can hand the home screen back succeeds"
assert_contains 'the panel has its home screen back' "the uninstall says the home screen came back"
assert_log_contains '^curl .*api/v1/hand-back-home$' "the uninstall asks the panel to hand its home screen back"
assert_log_contains '^adb .* uninstall io\.github\.maxlyth\.hapaneld$' "the uninstall removes the identity that is actually installed"
assert_not_contains '^adb .* uninstall io\.panelassistant\.android$' "$MOCK_CALL_LOG" "an identity that is not installed is never the uninstall target"
hand_back_line="$(grep -nE '^curl .*api/v1/hand-back-home$' "$MOCK_CALL_LOG" | head -1 | cut -d: -f1)"
uninstall_line="$(grep -nE '^adb .* uninstall io\.github\.maxlyth\.hapaneld$' "$MOCK_CALL_LOG" | head -1 | cut -d: -f1)"
if [ -n "$hand_back_line" ] && [ -n "$uninstall_line" ] && [ "$hand_back_line" -lt "$uninstall_line" ]; then
  pass "the home screen is handed back before ha-paneld is removed"
else
  fail_test "the home screen is handed back before ha-paneld is removed"
fi

# The refusal that matters most. A panel with no other launcher would be stranded by the removal, so the
# run stops with the app still installed and still serving as Home — an inconvenience, not a brick.
MOCK_NO_INSTALLED_PACKAGE=1 MOCK_LEGACY_INSTALLED=1 MOCK_HAND_BACK=no-home run_provision "$MOCK_TARGET" --uninstall
assert_failure "an uninstall refuses when the panel has no other launcher"
assert_contains 'could not give its home screen to another launcher' "the refusal says the panel has nowhere to hand the home screen"
assert_contains 'still this panel.s Home app' "the refusal says ha-paneld is still Home, so the panel still works"
assert_not_contains '^adb .* uninstall io\.github\.maxlyth\.hapaneld$' "$MOCK_CALL_LOG" "nothing is removed when the home screen cannot be handed back"

MOCK_NO_INSTALLED_PACKAGE=1 MOCK_LEGACY_INSTALLED=1 MOCK_HAND_BACK=too-old run_provision "$MOCK_TARGET" --uninstall
assert_failure "an uninstall refuses against a panel too old to hand the home screen back"
assert_contains 'too old to hand the home screen back' "the refusal names the panel's build as the reason"
assert_not_contains '^adb .* uninstall io\.github\.maxlyth\.hapaneld$' "$MOCK_CALL_LOG" "an old panel is not removed blind"

MOCK_NO_INSTALLED_PACKAGE=1 MOCK_LEGACY_INSTALLED=1 MOCK_HAND_BACK=transport-fail run_provision "$MOCK_TARGET" --uninstall
assert_failure "an uninstall refuses when the hand-back request never landed" \
  "handing the home screen back failed"
assert_not_contains '^adb .* uninstall io\.github\.maxlyth\.hapaneld$' "$MOCK_CALL_LOG" "an unanswered hand-back does not proceed to removal"

MOCK_NO_INSTALLED_PACKAGE=1 MOCK_LEGACY_INSTALLED=1 MOCK_HAND_BACK=approval run_provision "$MOCK_TARGET" --uninstall
assert_failure "an uninstall refuses while the hand-back is waiting for physical approval"
assert_contains 'approved on its screen' "the approval refusal says where to approve it"
assert_not_contains '^adb .* uninstall io\.github\.maxlyth\.hapaneld$' "$MOCK_CALL_LOG" "nothing is removed while approval is pending"

# A partially completed hand-back still moved the HOME role, which is the part that strands a panel. The
# vendor apps that are still disabled are a retry, not a reason to leave the panel without a home screen.
MOCK_NO_INSTALLED_PACKAGE=1 MOCK_LEGACY_INSTALLED=1 MOCK_HAND_BACK=partial run_provision "$MOCK_TARGET" --uninstall
assert_success "a partial hand-back that still moved the home role allows the removal"
assert_contains 'still disabled; running this again will retry them' "a partial hand-back says a retry will finish the job"
assert_log_contains '^adb .* uninstall io\.github\.maxlyth\.hapaneld$' "a panel with its home screen back is removed"

# --hand-back-home on its own is the recovery path for someone who wants their launcher back and is NOT
# removing ha-paneld.
MOCK_NO_INSTALLED_PACKAGE=0 run_provision "$MOCK_TARGET" --hand-back-home
assert_success "--hand-back-home succeeds on its own"
assert_log_contains '^curl .*api/v1/hand-back-home$' "--hand-back-home asks the panel to hand its home screen back"
assert_not_contains '^adb .* uninstall ' "$MOCK_CALL_LOG" "--hand-back-home never removes anything"
assert_not_contains 'install -r' "$MOCK_CALL_LOG" "--hand-back-home never installs anything"

# The defect this block missed until review: with no identity installed, the old code asked the panel to
# hand back its home screen -- surrendering HOME and clearing the desired tame state -- and only then tried
# to uninstall a package that was not there, advising a retry that could never work.
MOCK_NO_INSTALLED_PACKAGE=1 run_provision "$MOCK_TARGET" --uninstall
assert_failure "an uninstall refuses when no identity of ha-paneld is installed"
assert_contains 'not installed on this panel' "the refusal says there is nothing to remove"
assert_not_contains 'api/v1/hand-back-home' "$MOCK_CALL_LOG" "a panel with nothing installed is never asked to surrender its home screen"
assert_not_contains '^adb .* uninstall ' "$MOCK_CALL_LOG" "nothing is uninstalled when nothing is installed"

# Neither path may reach for an APK: someone handing a panel back its home screen is leaving, not upgrading.
assert_not_contains 'releases/latest' "$MOCK_CALL_LOG" "--hand-back-home does not resolve a release"
fi
[ "$PROVISION_TEST_SCOPE" != shard-uninstall ] || finish_provision_test

finish_provision_test
