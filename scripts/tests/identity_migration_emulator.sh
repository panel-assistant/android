#!/usr/bin/env bash
# Two-package application-id migration on a rooted emulator.
#
# Installs a base build under the legacy id, updates it in place to the bridge, installs the successor
# beside it and lets the two hand the panel over. The run is then repeated once per migration step with
# the successor process killed the instant that step's durable marker appears, which is the worst
# moment for each step: its effect has happened and nothing after it has. Every run must end the same
# way: one package, the restored panel id, the HTTP port answered by the successor, and HOME not
# resolving to a package that no longer exists.
#
# The bridge normally downloads the successor from its own GitHub release. No such release exists for a
# test build, so the successor APK is installed with adb and the bridge is asked to offer it; the
# bridge still verifies the installed package's signer before it starts it. Download and pin
# verification are covered by the JVM unit tests.
#
#   identity_migration_emulator.sh <base.apk> <bridge.apk> <successor.apk> <helper-binary>
#
# All three APKs must share one signer (the committed debug keystore in CI). The helper binary is the
# root daemon built for the emulator's ABI with the same HAPANELD_BUILD_ID the APKs carry.
set -euo pipefail

BASE_APK="${1:?usage: identity_migration_emulator.sh <base.apk> <bridge.apk> <successor.apk> <helper-binary>}"
BRIDGE_APK="${2:?missing bridge APK}"
SUCCESSOR_APK="${3:?missing successor APK}"
HELPER_BIN="${4:?missing helper binary}"

LEGACY=io.github.maxlyth.hapaneld
SUCCESSOR=io.panelassistant.android
CODE_PACKAGE=io.github.maxlyth.hapaneld
PANEL_ID=migration_emulator
HOST_PORT=18888
URL="http://127.0.0.1:$HOST_PORT"
MARKERS="/data/data/$SUCCESSOR/no_backup/identity-migration"
STEP_TIMEOUT_S="${IDENTITY_MIGRATION_STEP_TIMEOUT_S:-240}"
# Steps that record a marker, in order. The port wait records nothing, so it has no kill point.
KILL_STEPS=(pull verify release restore claim confirm uninstall)

log() { printf '%s %s\n' "$(date -u +%H:%M:%S)" "$*"; }
fail() { log "FAIL: $*"; diagnostics; exit 1; }

diagnostics() {
  log "--- packages"; adb shell pm list packages 2>/dev/null | grep -E "$LEGACY|$SUCCESSOR" || true
  log "--- markers"; adb shell ls -la "$MARKERS" 2>/dev/null || true
  log "--- health"; curl -fsS --max-time 3 "$URL/health" 2>/dev/null || true
  log "--- migration log"
  adb logcat -d -s ha-paneld/migration:V ha-paneld:V 2>/dev/null | tail -60 || true
}

health() { curl -fsS --max-time 3 "$URL/health" 2>/dev/null || true; }

# wait_for <seconds> <description> <command...>: poll until the command succeeds.
wait_for() {
  local deadline=$((SECONDS + $1)) what="$2"; shift 2
  until "$@" >/dev/null 2>&1; do
    [ "$SECONDS" -lt "$deadline" ] || fail "timed out waiting for $what"
    sleep 1
  done
}

health_is() { health | grep -q -- " pkg=$1\( \|$\)"; }
health_panel_is() { health | grep -q -- " panel=$1 "; }
installed() { adb shell pm list packages | tr -d '\r' | grep -qx "package:$1"; }
marker_exists() { adb shell "test -f $MARKERS/$1.v1"; }
home_package() {
  adb shell cmd package resolve-activity --brief -c android.intent.category.HOME 2>/dev/null |
    tr -d '\r' | tail -1 | cut -d/ -f1
}

start_helper() {
  adb shell "pkill -f /data/local/hapaneld-helper" >/dev/null 2>&1 || true
  adb push "$HELPER_BIN" /data/local/hapaneld-helper >/dev/null
  adb shell "chown root:root /data/local/hapaneld-helper && chmod 0700 /data/local/hapaneld-helper"
  adb shell "setsid /data/local/hapaneld-helper --supervise </dev/null >/dev/null 2>&1 &"
}

reset_device() {
  adb shell "pkill -f /data/local/hapaneld-helper" >/dev/null 2>&1 || true
  adb uninstall "$SUCCESSOR" >/dev/null 2>&1 || true
  adb uninstall "$LEGACY" >/dev/null 2>&1 || true
  adb forward --remove-all >/dev/null 2>&1 || true
  adb forward "tcp:$HOST_PORT" tcp:8888 >/dev/null
  adb logcat -c || true
}

launch() { adb shell am start -n "$1" >/dev/null; }

# The bridge confirms the helper, makes the successor a kiosk companion, starts it and hands it the
# release token. Anything but "Launched" means a precondition failed and nothing was started.
offer_launched() {
  curl -fsS --max-time 30 -X POST "$URL/api/v1/successor/offer" 2>/dev/null | grep -q '"outcome":"Launched"'
}

# After a kill: the bridge offers again while it still runs; once it has retired nothing is listening
# on its behalf, and the successor is started the way a HOME press or a boot would start it.
resume_migration() {
  offer_launched || launch "$SUCCESSOR/$CODE_PACKAGE.MainActivity"
}

scenario() {
  local kill_step="${1:-}"
  log "=== scenario: ${kill_step:+kill after the $kill_step marker}${kill_step:-uninterrupted}"
  reset_device

  adb install "$BASE_APK" >/dev/null
  launch "$LEGACY/.MainActivity"
  wait_for 120 "the base build to answer" health
  curl -fsS --max-time 30 -d "panel_id=$PANEL_ID" "$URL/api/v1/config" >/dev/null ||
    fail "could not set the panel id on the base build"
  wait_for 60 "the base build to adopt the panel id" health_panel_is "$PANEL_ID"

  adb install -r "$BRIDGE_APK" >/dev/null
  launch "$LEGACY/.MainActivity"
  wait_for 120 "the bridge to answer under the legacy id" health_is "$LEGACY"
  health_panel_is "$PANEL_ID" || fail "the in-place update to the bridge lost the panel id"

  start_helper
  adb install "$SUCCESSOR_APK" >/dev/null
  wait_for 60 "the bridge to start the successor" offer_launched

  if [ -n "$kill_step" ]; then
    wait_for "$STEP_TIMEOUT_S" "the $kill_step marker" marker_exists "step-$kill_step"
    adb shell "kill -9 \$(pidof $SUCCESSOR)" >/dev/null 2>&1 || true
    log "killed the successor after the $kill_step marker"
    [ "$kill_step" = uninstall ] || ! marker_exists complete ||
      fail "the migration completed before the kill could land; the kill point proves nothing"
    sleep 2
    resume_migration || true
  fi

  wait_for "$STEP_TIMEOUT_S" "the migration to complete" marker_exists complete

  installed "$SUCCESSOR" || fail "the successor is not installed"
  ! installed "$LEGACY" || fail "the legacy package is still installed after completion"
  wait_for 120 "the successor to own the HTTP port" health_is "$SUCCESSOR"
  health_panel_is "$PANEL_ID" || fail "the successor did not restore the panel id: $(health)"
  local home; home="$(home_package)"
  [ "$home" != "$LEGACY" ] || fail "HOME still resolves to the removed legacy package"
  [ -n "$home" ] || fail "HOME resolves to nothing"
  log "ok: port and panel id with the successor, HOME=$home, legacy package removed"
}

adb wait-for-device
adb root >/dev/null 2>&1 || true
adb wait-for-device

scenario ""
for step in "${KILL_STEPS[@]}"; do
  scenario "$step"
done
log "PASS: ${#KILL_STEPS[@]} kill points and the uninterrupted run converged"
