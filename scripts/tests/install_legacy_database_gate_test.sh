#!/usr/bin/env bash
# Focused, offline checks for the checkout-free installer's guardless historical provisioner path.
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
INSTALLER="$ROOT/scripts/install.sh"
FIXTURE="$ROOT/scripts/tests/fixtures/install-legacy-tool"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

BIN="$TMP/bin"
mkdir -p "$BIN"
ln -s "$FIXTURE" "$BIN/adb"
ln -s "$FIXTURE" "$BIN/curl"
ln -s "$FIXTURE" "$BIN/openssl"

passes=0
failures=0
LAST_STATUS=0
LAST_OUTPUT=""
LAST_STATE=""

pass() {
  passes=$((passes + 1))
  printf 'ok %d - %s\n' "$passes" "$1"
}

fail_test() {
  failures=$((failures + 1))
  printf 'not ok - %s\n' "$1" >&2
  [ -z "$LAST_OUTPUT" ] || printf '%s\n' "$LAST_OUTPUT" >&2
}

count_file() {
  local file="$1"
  if [ -f "$file" ]; then sed -n '1p' "$file"; else printf '0\n'; fi
}

run_installer() {
  local name="$1" package_initial="$2" package_final="$3" root_route="$4"
  local root_initial="$5" root_final="$6" current="${7:-0}" root_route_final="${8:-$root_route}"
  local su_form="${9:-shell}" root_exec_initial="${10:-rooted}" root_exec_final
  root_exec_final="${11:-$root_exec_initial}"
  LAST_STATE="$TMP/$name"
  mkdir -p "$LAST_STATE"
  LAST_OUTPUT="$(
    PATH="$BIN:/usr/bin:/bin" \
      INSTALL_LEGACY_STATE_DIR="$LAST_STATE" \
      MOCK_INSTALL_PACKAGE_INITIAL="$package_initial" \
      MOCK_INSTALL_PACKAGE_FINAL="$package_final" \
      MOCK_INSTALL_ROOT_ROUTE_INITIAL="$root_route" \
      MOCK_INSTALL_ROOT_ROUTE_FINAL="$root_route_final" \
      MOCK_INSTALL_SU_FORM="$su_form" \
      MOCK_INSTALL_ROOT_DATA_INITIAL="$root_initial" \
      MOCK_INSTALL_ROOT_DATA_FINAL="$root_final" \
      MOCK_INSTALL_ROOT_EXEC_UID_INITIAL="$root_exec_initial" \
      MOCK_INSTALL_ROOT_EXEC_UID_FINAL="$root_exec_final" \
      MOCK_INSTALL_CURRENT_PROVISIONER="$current" \
      bash "$INSTALLER" --provision panel.test 2>&1
  )"
  LAST_STATUS=$?
}

ran_once() {
  [ "$(grep -c '^provisioner-executed$' "$LAST_STATE/events.log" 2>/dev/null || true)" = 1 ]
}

did_not_run() {
  [ ! -e "$LAST_STATE/events.log" ]
}

run_installer rooted-fresh absent absent rooted fresh fresh
if [ "$LAST_STATUS" -eq 0 ] && ran_once &&
   [ "$(count_file "$LAST_STATE/package.count")" = 2 ] &&
   [ "$(count_file "$LAST_STATE/root-data.count")" = 2 ] &&
   grep -Fq 'root inspection proved this is a fresh install' <<< "$LAST_OUTPUT"; then
  pass "rooted legacy install requires two complete actual-data freshness observations"
else
  fail_test "rooted legacy install requires two complete actual-data freshness observations"
fi

for su_form in su0join su0shc surootjoin surootshc suc; do
  run_installer "rooted-$su_form" absent absent rooted fresh fresh 0 rooted "$su_form"
  if [ "$LAST_STATUS" -eq 0 ] && ran_once &&
     [ "$(count_file "$LAST_STATE/root-data.count")" = 2 ]; then
    pass "$su_form transports the exact rooted data observer"
  else
    fail_test "$su_form transports the exact rooted data observer"
  fi
done

run_installer rootless-fresh absent absent unrooted fresh fresh
if [ "$LAST_STATUS" -eq 0 ] && ran_once &&
   [ "$(count_file "$LAST_STATE/package.count")" = 2 ] &&
   [ "$(count_file "$LAST_STATE/root-data.count")" = 0 ] &&
   grep -Fq 'complete package/data-record removal' <<< "$LAST_OUTPUT"; then
  pass "rootless truly fresh install uses the complete Android removal proof"
else
  fail_test "rootless truly fresh install uses the complete Android removal proof"
fi

run_installer retained-app-data absent absent rooted app-data app-data
if [ "$LAST_STATUS" -ne 0 ] && did_not_run &&
   grep -Fq 'HAPANELD_INSTALLER_APP_DATA:retained' "$LAST_STATE/root-observations.log" &&
   grep -Fq 'root inspection found retained ha-paneld app-data, database, or recovery state' <<< "$LAST_OUTPUT"; then
  pass "rooted package absence cannot hide retained app data"
else
  fail_test "rooted package absence cannot hide retained app data"
fi

run_installer retained-database absent absent rooted database database
if [ "$LAST_STATUS" -ne 0 ] && did_not_run &&
   grep -Fq 'HAPANELD_INSTALLER_DATABASE:retained' "$LAST_STATE/root-observations.log" &&
   grep -Fq 'retained ha-paneld app-data, database, or recovery state' <<< "$LAST_OUTPUT"; then
  pass "rooted package absence cannot hide a canonical database"
else
  fail_test "rooted package absence cannot hide a canonical database"
fi

run_installer retained-recovery absent absent rooted recovery recovery
if [ "$LAST_STATUS" -ne 0 ] && did_not_run &&
   grep -Fq 'HAPANELD_INSTALLER_RECOVERY:retained' "$LAST_STATE/root-observations.log" &&
   grep -Fq 'retained ha-paneld app-data, database, or recovery state' <<< "$LAST_OUTPUT"; then
  pass "rooted package absence cannot hide recovery state"
else
  fail_test "rooted package absence cannot hide recovery state"
fi

run_installer unreadable-root absent absent rooted unreadable unreadable
if [ "$LAST_STATUS" -ne 0 ] && did_not_run &&
   grep -Fq 'could not establish a complete app-data, database, and recovery inventory' <<< "$LAST_OUTPUT"; then
  pass "a proven but unreadable root inventory fails closed"
else
  fail_test "a proven but unreadable root inventory fails closed"
fi

run_installer root-execution-not-privileged absent absent rooted fresh fresh 0 rooted shell unrooted unrooted
if [ "$LAST_STATUS" -ne 0 ] && did_not_run &&
   grep -Fq 'HAPANELD_INSTALLER_ROOT_UID:unknown' "$LAST_STATE/root-observations.log" &&
   grep -Fq 'could not establish a complete app-data, database, and recovery inventory' <<< "$LAST_OUTPUT"; then
  pass "the inventory command itself must prove effective uid zero"
else
  fail_test "the inventory command itself must prove effective uid zero"
fi

run_installer root-marker-not-zero absent absent rooted root-marker-unknown root-marker-unknown
if [ "$LAST_STATUS" -ne 0 ] && did_not_run &&
   grep -Fq 'could not establish a complete app-data, database, and recovery inventory' <<< "$LAST_OUTPUT"; then
  pass "a nonzero root marker is independently rejected"
else
  fail_test "a nonzero root marker is independently rejected"
fi

for missing_state in missing-ce missing-data-data missing-de; do
  run_installer "$missing_state" absent absent rooted "$missing_state" "$missing_state"
  if [ "$LAST_STATUS" -ne 0 ] && did_not_run &&
     grep -Fq 'could not establish a complete app-data, database, and recovery inventory' <<< "$LAST_OUTPUT"; then
    pass "$missing_state cannot license fresh installation"
  else
    fail_test "$missing_state cannot license fresh installation"
  fi
done

run_installer malformed-root absent absent rooted malformed malformed
if [ "$LAST_STATUS" -ne 0 ] && did_not_run &&
   grep -Fq 'could not establish a complete app-data, database, and recovery inventory' <<< "$LAST_OUTPUT"; then
  pass "a truncated root inventory fails closed"
else
  fail_test "a truncated root inventory fails closed"
fi

run_installer noisy-root absent absent rooted noise noise
if [ "$LAST_STATUS" -ne 0 ] && did_not_run &&
   grep -Fq 'could not establish a complete app-data, database, and recovery inventory' <<< "$LAST_OUTPUT"; then
  pass "unexpected root-observer output cannot be parsed as absence"
else
  fail_test "unexpected root-observer output cannot be parsed as absence"
fi

run_installer de-recovery absent absent rooted de-recovery de-recovery
if [ "$LAST_STATUS" -ne 0 ] && did_not_run &&
   grep -Fq 'HAPANELD_INSTALLER_RECOVERY:retained' "$LAST_STATE/root-observations.log" &&
   grep -Fq 'retained ha-paneld app-data, database, or recovery state' <<< "$LAST_OUTPUT"; then
  pass "device-encrypted recovery state is part of the actual inventory"
else
  fail_test "device-encrypted recovery state is part of the actual inventory"
fi

run_installer symlink-recovery absent absent rooted recovery-symlink recovery-symlink
if [ "$LAST_STATUS" -ne 0 ] && did_not_run &&
   grep -Fq 'HAPANELD_INSTALLER_RECOVERY:retained' "$LAST_STATE/root-observations.log" &&
   grep -Fq 'retained ha-paneld app-data, database, or recovery state' <<< "$LAST_OUTPUT"; then
  pass "a dangling recovery symlink is retained state"
else
  fail_test "a dangling recovery symlink is retained state"
fi

run_installer retained-record retained retained unrooted fresh fresh
if [ "$LAST_STATUS" -ne 0 ] && did_not_run &&
   grep -Fq 'Android retains an uninstalled ha-paneld package/data record' <<< "$LAST_OUTPUT"; then
  pass "package-path absence alone cannot hide Android retained data"
else
  fail_test "package-path absence alone cannot hide Android retained data"
fi

run_installer unknown-package target-failed target-failed unrooted fresh fresh
if [ "$LAST_STATUS" -ne 0 ] && did_not_run &&
   grep -Fq 'package/data state is unknown' <<< "$LAST_OUTPUT"; then
  pass "an incomplete package-manager observation fails closed"
else
  fail_test "an incomplete package-manager observation fails closed"
fi

run_installer noisy-package noise noise unrooted fresh fresh
if [ "$LAST_STATUS" -ne 0 ] && did_not_run &&
   grep -Fq 'package/data state is unknown' <<< "$LAST_OUTPUT"; then
  pass "unexpected package-manager output cannot be parsed as absence"
else
  fail_test "unexpected package-manager output cannot be parsed as absence"
fi

run_installer junk-package-marker marker-junk marker-junk unrooted fresh fresh
if [ "$LAST_STATUS" -ne 0 ] && did_not_run &&
   grep -Fq 'package/data state is unknown' <<< "$LAST_OUTPUT"; then
  pass "package protocol markers reject trailing fields"
else
  fail_test "package protocol markers reject trailing fields"
fi

run_installer installed-package present present rooted fresh fresh
if [ "$LAST_STATUS" -ne 0 ] && did_not_run &&
   grep -Fq 'against an installed ha-paneld package' <<< "$LAST_OUTPUT"; then
  pass "an installed package never reaches a guardless provisioner"
else
  fail_test "an installed package never reaches a guardless provisioner"
fi

run_installer package-drift absent present unrooted fresh fresh
if [ "$LAST_STATUS" -ne 0 ] && did_not_run &&
   [ "$(count_file "$LAST_STATE/package.count")" = 2 ] &&
   grep -Fq 'consume-time recheck against an installed ha-paneld package' <<< "$LAST_OUTPUT"; then
  pass "absent-to-installed drift is refused immediately before legacy execution"
else
  fail_test "absent-to-installed drift is refused immediately before legacy execution"
fi

run_installer retained-record-drift absent retained unrooted fresh fresh
if [ "$LAST_STATUS" -ne 0 ] && did_not_run &&
   grep -Fq 'consume-time recheck: Android retains an uninstalled ha-paneld package/data record' <<< "$LAST_OUTPUT"; then
  pass "absent-to-retained Android data drift is refused at consume time"
else
  fail_test "absent-to-retained Android data drift is refused at consume time"
fi

run_installer recovery-drift absent absent rooted fresh recovery
if [ "$LAST_STATUS" -ne 0 ] && did_not_run &&
   [ "$(count_file "$LAST_STATE/root-data.count")" = 2 ] &&
   grep -Fq 'consume-time recheck: root inspection found retained' <<< "$LAST_OUTPUT"; then
  pass "new recovery state is refused at the final actual-data observation"
else
  fail_test "new recovery state is refused at the final actual-data observation"
fi

run_installer root-proof-lost absent absent rooted fresh fresh 0 unrooted
if [ "$LAST_STATUS" -ne 0 ] && did_not_run &&
   grep -Fq 'consume-time recheck: the root route used to inspect actual app data at admission is no longer available' <<< "$LAST_OUTPUT"; then
  pass "consume time cannot weaken an actual-data proof to package bookkeeping"
else
  fail_test "consume time cannot weaken an actual-data proof to package bookkeeping"
fi

run_installer root-appears-with-recovery absent absent unrooted recovery recovery 0 rooted
if [ "$LAST_STATUS" -ne 0 ] && did_not_run &&
   grep -Fq 'consume-time recheck: root inspection found retained' <<< "$LAST_OUTPUT"; then
  pass "a newly usable root route exposes and refuses retained recovery state"
else
  fail_test "a newly usable root route exposes and refuses retained recovery state"
fi

run_installer current-provisioner present present rooted fresh fresh 1
if [ "$LAST_STATUS" -eq 0 ] && ran_once &&
   [ "$(count_file "$LAST_STATE/package.count")" = 0 ] &&
   [ "$(count_file "$LAST_STATE/root-data.count")" = 0 ]; then
  pass "current marker-bearing provisioners retain their authoritative host gate"
else
  fail_test "current marker-bearing provisioners retain their authoritative host gate"
fi

# A panel that runs only the old app is moved by Panel Assistant's Repair. The installer refuses
# before downloading or running anything, even with a current provisioner that would otherwise run.
MOCK_INSTALL_LEGACY_PRESENT=1 MOCK_INSTALL_RELEASE_SUCCESSOR=1 \
  run_installer old-app-only absent absent rooted fresh fresh 1
if [ "$LAST_STATUS" -ne 0 ] && did_not_run &&
   grep -Fq 'This panel still runs the old ha-paneld app.' <<< "$LAST_OUTPUT" &&
   grep -Fq 'Settings → Repairs → "Move <panel name> to the new app"' <<< "$LAST_OUTPUT" &&
   grep -Fq 'Panel Assistant is required for the move.' <<< "$LAST_OUTPUT" &&
   ! grep -Fq 'panel-assistant-v0.9.2-manual-setup-required.apk' "$LAST_STATE/calls.log"; then
  pass "a panel on the old app is refused before the new app is fetched or installed beside it"
else
  fail_test "a panel on the old app is refused before the new app is fetched or installed beside it"
fi

# The same panel with a release that carries only the old app's own build is updated in place.
MOCK_INSTALL_LEGACY_PRESENT=1 run_installer old-app-in-place absent absent rooted fresh fresh 1
if [ "$LAST_STATUS" -eq 0 ] && ran_once &&
   ! grep -Fq 'still runs the old ha-paneld app' <<< "$LAST_OUTPUT"; then
  pass "a release of the old app's own build still updates a panel on the old app"
else
  fail_test "a release of the old app's own build still updates a panel on the old app"
fi

printf '1..%d\n' "$((passes + failures))"
if [ "$failures" -ne 0 ]; then
  printf '%d assertion(s) failed\n' "$failures" >&2
  exit 1
fi
