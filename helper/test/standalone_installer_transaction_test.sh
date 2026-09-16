#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
FIXTURES="$ROOT/scripts/tests/fixtures"
INSTALLER="$ROOT/helper/install-daemon.sh"
TMP="$(mktemp -d)"
first_pid=""
cleanup() {
  [ -z "$first_pid" ] || kill "$first_pid" >/dev/null 2>&1 || true
  [ -z "$first_pid" ] || wait "$first_pid" >/dev/null 2>&1 || true
  rm -rf "$TMP"
}
trap cleanup EXIT

mkdir -p "$TMP/dist/arm64-v8a"
# This dist is overridden, so it is a foreign build as far as the installer is concerned. Stamp it with
# an identity that is deliberately NOT the checkout's: every transaction below then also proves that
# the journal, the lease and the post-start identity check all follow the staged bytes.
FOREIGN_BUILD_ID=facade00facade00facade00facade00facade00facade00facade00facade00
CHECKOUT_BUILD_ID="$(PATH=/usr/bin:/bin "$ROOT/helper/source-id.sh")"
if [ "$FOREIGN_BUILD_ID" = "$CHECKOUT_BUILD_ID" ]; then
  echo "the foreign dist identity must differ from the checkout identity or this file proves nothing" >&2
  exit 1
fi
printf 'mock helper\nBUILDID %s\n' "$FOREIGN_BUILD_ID" > "$TMP/dist/arm64-v8a/hapaneld-helper"
mkdir -p "$TMP/bin"
cat > "$TMP/bin/adb" <<'EOF'
#!/usr/bin/env bash
set -u
command_text="$*"
shared_lock_signal_reply() {
  [ "${MOCK_SHARED_LOCK_SIGNAL:-}" = 1 ] || return 125
  [ "${3:-}" = shell ] || return 125
  printf '%s' "$command_text" 2>/dev/null | grep -Fq 'inspect_manual_journal_v1' || return 125
  printf '%s' "$command_text" 2>/dev/null | grep -Fq '.hapaneld-helper-transaction.lock' || return 125

  signal_root="${MOCK_STATE_DIR:?}/shared-lock-signal"
  remote_command=${!#}
  remote_command=${remote_command//\/dev\/.hapaneld-helper-transaction.lock/$signal_root/dev/.hapaneld-helper-transaction.lock}
  injection=': > '"$signal_root"'/handler-ready
  while [ ! -f '"$signal_root"'/release-handler ]; do sleep 0.05; done
  : > '"$signal_root"'/post-signal-mutation
  inspect_manual_journal_v1() {'
  remote_command=${remote_command/'inspect_manual_journal_v1() {'/$injection}
  remote_command=$(printf '%s\n' "$remote_command" | sed 's/^\([[:space:]]*\)|\*/\1""|*/')
  printf '%s' "$remote_command" 2>/dev/null | grep -Fq "$signal_root/handler-ready" || return 1
  mkdir -p "$signal_root/dev"
  rm -f "$signal_root/handler-ready" "$signal_root/release-handler" \
    "$signal_root/post-signal-mutation" "$signal_root/remote-output"
  printf 'adb %s\n' "$*" >> "${MOCK_CALL_LOG:?}"
  PATH=/usr/bin:/bin bash -c "$remote_command" >"$signal_root/remote-output" 2>&1 &
  remote_pid=$!
  attempt=0
  while [ ! -f "$signal_root/handler-ready" ]; do
    attempt=$((attempt + 1)); [ "$attempt" -lt 600 ] || { kill -KILL "$remote_pid" 2>/dev/null; wait "$remote_pid" 2>/dev/null; return 1; }
    sleep 0.05
  done
  kill -TERM "$remote_pid" || return 1
  : > "$signal_root/release-handler"
  if wait "$remote_pid"; then remote_status=0; else remote_status=$?; fi
  printf '%s\n' "$remote_status" > "$signal_root/remote-status"
  cat "$signal_root/remote-output"
  return "$remote_status"
}
v3_lease_phase_race_reply() {
  [ "${MOCK_V3_LEASE_PHASE_RACE:-}" = 1 ] || return 125
  [ "${3:-}" = shell ] || return 125
  race_root="${MOCK_STATE_DIR:?}/v3-lease-race"
  remote_command=${!#}

  if printf '%s' "$command_text" 2>/dev/null | grep -Fq 'echo LEASE_OK' &&
     printf '%s' "$command_text" 2>/dev/null | grep -Fq '.hapaneld-helper-manual-upgrade'; then
    remote_command=${remote_command//\/dev\/.hapaneld-helper-transaction.lock/$race_root/dev/.hapaneld-helper-transaction.lock}
    remote_command=${remote_command//\/data/$race_root/data}
    remote_command=$(printf '%s\n' "$remote_command" | sed 's/^\([[:space:]]*\)|\*/\1""|*/')
    printf 'adb %s\n' "$*" >> "${MOCK_CALL_LOG:?}"
    renewal_output="$(PATH=/usr/bin:/bin bash -c "$remote_command" 2>&1)"
    renewal_status=$?
    printf '%s\n' "$renewal_output"
    if [ "$renewal_output" = TRANSACTION_BUSY ]; then
      : > "$race_root/renewal-blocked-by-phase-lock"
    elif [ "$renewal_output" = LEASE_OK ]; then
      grep -qx SWAP_PHASE=TARGET "$race_root/data/local/.hapaneld-helper-manual-upgrade" || return 1
      : > "$race_root/target-phase-renewed"
    fi
    return "$renewal_status"
  fi

  if printf '%s' "$command_text" 2>/dev/null | grep -Fq 'echo INSTALL_OK' &&
     printf '%s' "$command_text" 2>/dev/null | grep -Fq 'SWAP_PHASE=PREPARED'; then
    transaction_id="$(printf '%s\n' "$remote_command" |
      sed -nE 's#.*candidate=/data/local/\.hapaneld-helper\.manual-([0-9a-f]{32}).*#\1#p' | head -1)"
    [ "${#transaction_id}" -eq 32 ] || return 1
    mkdir -p "$race_root/dev" "$race_root/data/local"
    marker="$race_root/data/local/.hapaneld-helper-manual-upgrade"
    current_boot="$(cat /proc/sys/kernel/random/boot_id)"
    current_uptime="$(cut -d. -f1 /proc/uptime)"
    cat > "$marker" <<RACEEOF
JOURNAL_VERSION=3
JOURNAL_SCOPE=HELPER_ONLY
REGISTRATION_KIND=system
TRANSACTION_ID=$transaction_id
TARGET_BUILD_ID=${MOCK_HELPER_BUILD_ID:?}
TARGET_HELPER_SHA256=${MOCK_MANUAL_TARGET_HELPER_SHA256:?}
TARGET_SERVICE_SHA256=${MOCK_MANUAL_TARGET_SERVICE_SHA256:?}
SWAP_PHASE=PREPARED
LEASE_BOOT_ID=$current_boot
LEASE_UNTIL_UPTIME=$((current_uptime + 3))
RACEEOF
    chmod 600 "$marker"
    mkdir "$race_root/dev/.hapaneld-helper-transaction.lock"
    printf '%s\n' "$$" > "$race_root/dev/.hapaneld-helper-transaction.lock/pid"
    : > "$race_root/main-phase-lock-held"
    attempt=0
    while [ ! -f "$race_root/renewal-blocked-by-phase-lock" ]; do
      attempt=$((attempt + 1)); [ "$attempt" -lt 300 ] || return 1; sleep 0.1
    done
    sed 's/^SWAP_PHASE=PREPARED$/SWAP_PHASE=TARGET/' "$marker" > "$marker.target"
    chmod 600 "$marker.target"
    mv -f "$marker.target" "$marker"
    rm -rf "$race_root/dev/.hapaneld-helper-transaction.lock"
    attempt=0
    while [ ! -f "$race_root/target-phase-renewed" ]; do
      attempt=$((attempt + 1)); [ "$attempt" -lt 300 ] || return 1; sleep 0.1
    done
    grep -qx SWAP_PHASE=TARGET "$marker" || return 1
    : > "${MOCK_STATE_DIR:?}/manual-helper-transaction"
    printf 'INSTALL_OK\n'
    return 0
  fi

  if printf '%s' "$command_text" 2>/dev/null | grep -Fq 'echo COMMIT_OK' &&
     printf '%s' "$command_text" 2>/dev/null | grep -Fq '.hapaneld-helper-manual-upgrade'; then
    marker="$race_root/data/local/.hapaneld-helper-manual-upgrade"
    [ -f "$race_root/renewal-blocked-by-phase-lock" ] &&
      [ -f "$race_root/target-phase-renewed" ] && grep -qx SWAP_PHASE=TARGET "$marker" || return 1
    printf 'TARGET\n' > "$race_root/committed-phase"
    rm -f "$marker" "${MOCK_STATE_DIR:?}/manual-helper-transaction"
    printf 'COMMIT_OK\n'
    return 0
  fi
  return 125
}
v3_real_filesystem_reply() {
  [ "${MOCK_V3_REAL_FILESYSTEM:-}" = 1 ] || return 125
  real_root="${MOCK_STATE_DIR:?}/v3-real"
  if [ "${3:-}" = push ]; then
    destination=${5//\/system/$real_root/system}
    destination=${destination//\/vendor/$real_root/vendor}
    destination=${destination//\/data/$real_root/data}
    mkdir -p "${destination%/*}"
    cp "$4" "$destination"
    printf 'adb %s\n' "$*" >> "${MOCK_CALL_LOG:?}"
    return 0
  fi
  if { printf '%s' "$command_text" 2>/dev/null | grep -Fq inspect_manual_journal_v1 ||
       printf '%s' "$command_text" 2>/dev/null | grep -Fq 'restore LIVE_CANONICAL /data/local/hapaneld-helper 700' ||
       printf '%s' "$command_text" 2>/dev/null | grep -Fq 'echo ROLLBACK_FINALIZED'; } &&
     [ "${3:-}" = shell ]; then
    remote_command=${!#}
    remote_command=${remote_command//\/dev\/.hapaneld-helper-transaction.lock/$real_root/dev/.hapaneld-helper-transaction.lock}
    remote_command=${remote_command//\/system/$real_root/system}
    remote_command=${remote_command//\/vendor/$real_root/vendor}
    remote_command=${remote_command//\/data/$real_root/data}
    remote_command=$(printf '%s\n' "$remote_command" | sed 's/^\([[:space:]]*\)|\*/\1""|*/')
    printf 'adb %s\n' "$*" >> "${MOCK_CALL_LOG:?}"
    PATH="${MOCK_V1_COMMAND_DIR:?}:/usr/bin:/bin" bash -c "$remote_command"
    return $?
  fi
  return 125
}
v1_real_filesystem_reply() {
  [ "${MOCK_V1_REAL_FILESYSTEM:-}" = 1 ] || return 125
  v1_root="${MOCK_STATE_DIR:?}/v1-real"
  # For these cases adb-root mode makes the last argv item the exact shell block emitted by the
  # production installer. Execute that block after mapping Android's fixed filesystem roots into
  # the fixture. The only simulated event is a process cut in the mock mv used by the first restore.
  if [ "${3:-}" = push ]; then
    destination=${5//\/system/$v1_root/system}
    destination=${destination//\/vendor/$v1_root/vendor}
    destination=${destination//\/data/$v1_root/data}
    mkdir -p "${destination%/*}"
    cp "$4" "$destination"
    printf 'adb %s\n' "$*" >> "${MOCK_CALL_LOG:?}"
    return 0
  fi
  if { printf '%s' "$command_text" 2>/dev/null | grep -Fq inspect_manual_journal_v1 ||
       printf '%s' "$command_text" 2>/dev/null | grep -Fq 'publish_recorded OLD_BIN' ||
       printf '%s' "$command_text" 2>/dev/null | grep -Fq 'echo ROLLBACK_FINALIZED'; } &&
     [ "${3:-}" = shell ]; then
    remote_command=${!#}
    remote_command=${remote_command//\/dev\/.hapaneld-helper-transaction.lock/$v1_root/dev/.hapaneld-helper-transaction.lock}
    remote_command=${remote_command//\/system/$v1_root/system}
    remote_command=${remote_command//\/vendor/$v1_root/vendor}
    remote_command=${remote_command//\/data/$v1_root/data}
    # The installer intentionally targets Android mksh, which accepts an empty first case pattern.
    # Its host-side single-quoted block represents that as a leading `|`; spell the empty pattern
    # explicitly for bash without changing the command exercised by the fixture.
    remote_command=$(printf '%s\n' "$remote_command" | sed 's/^\([[:space:]]*\)|\*/\1""|*/')
    printf 'adb %s\n' "$*" >> "${MOCK_CALL_LOG:?}"
    PATH="${MOCK_V1_COMMAND_DIR:?}:/usr/bin:/bin" bash -c "$remote_command"
    remote_status=$?
    if [ "$remote_status" -eq 0 ] && printf '%s' "$command_text" 2>/dev/null | grep -Fq 'echo ROLLBACK_FINALIZED'; then
      rm -f "${MOCK_STATE_DIR:?}/manual-helper-transaction"
    fi
    return "$remote_status"
  fi
  return 125
}
shared_lock_signal_reply "$@"
shared_lock_signal_status=$?
[ "$shared_lock_signal_status" -eq 125 ] || exit "$shared_lock_signal_status"
v3_lease_phase_race_reply "$@"
v3_lease_race_status=$?
[ "$v3_lease_race_status" -eq 125 ] || exit "$v3_lease_race_status"
v3_real_filesystem_reply "$@"
v3_status=$?
[ "$v3_status" -eq 125 ] || exit "$v3_status"
v1_real_filesystem_reply "$@"
v1_status=$?
[ "$v1_status" -eq 125 ] || exit "$v1_status"
if [ "${MOCK_MANUAL_TRANSACTION_STATE:-}" = stale_v3 ] &&
   printf '%s' "$command_text" 2>/dev/null | grep -Fq inspect_manual_journal_v3; then
  printf 'adb %s\n' "$*" >> "${MOCK_CALL_LOG:?}"
  printf 'STALE_V3_TRANSACTION system %s %s %s %s\n' \
    "${MOCK_MANUAL_TRANSACTION_ID:?}" "${MOCK_MANUAL_TARGET_BUILD_ID:?}" \
    "${MOCK_MANUAL_TARGET_HELPER_SHA256:?}" "${MOCK_MANUAL_TARGET_SERVICE_SHA256:?}"
  exit 0
fi
if printf '%s' "$command_text" 2>/dev/null | grep -Fq ' --request GUARDCAPS'; then
  printf 'adb %s\n' "$*" >> "${MOCK_CALL_LOG:?}"
  printf 'OK GUARDCAPS 1 PREPARE DEFINE STREAM ACTION HEALTH REFUSAL STATUS EVIDENCE CANCEL RETIRE JOURNAL AUTONOMOUS SUPERVISED TERMINAL_RETIRE\n'
  exit 0
fi
if printf '%s' "$command_text" 2>/dev/null | grep -Fq ' --request GUARDSTATUS'; then
  printf 'adb %s\n' "$*" >> "${MOCK_CALL_LOG:?}"
  printf 'OK GUARDSTATUS 0 EMPTY NONE NONE NONE NONE 0 0 0 NONE NONE 0 0\n'
  exit 0
fi
if [ "${MOCK_REPLACEMENT_SAFE:-ok}" = armed ] &&
   printf '%s' "$command_text" 2>/dev/null | grep -Fq -- '--replacement-safe' &&
   printf '%s' "$command_text" 2>/dev/null | grep -Fq 'echo INSTALL_OK'; then
  printf 'adb %s\n' "$*" >> "${MOCK_CALL_LOG:?}"
  : > "${MOCK_STATE_DIR:?}/manual-helper-transaction"
  printf 'GUARD_ARMED_ROLLBACK\n'
  exit 1
fi
if [ "${MOCK_REPLACEMENT_SAFE:-ok}" = r1_custody ] &&
   printf '%s' "$command_text" 2>/dev/null | grep -Fq -- '--replacement-safe' &&
   printf '%s' "$command_text" 2>/dev/null | grep -Fq 'echo INSTALL_OK'; then
  printf 'adb %s\n' "$*" >> "${MOCK_CALL_LOG:?}"
  : > "${MOCK_STATE_DIR:?}/manual-helper-transaction"
  printf 'REPLACEMENT_AUTHORITY_ACTIVE\n'
  exit 1
fi
if [ "${MOCK_APP_REPLACEMENT_INTERVAL:-}" = initial ] &&
   printf '%s' "$command_text" 2>/dev/null | grep -Fq inspect_manual_journal &&
   printf '%s' "$command_text" 2>/dev/null | grep -Fq 'echo APP_REPLACEMENT_HOLD'; then
  printf 'adb %s\n' "$*" >> "${MOCK_CALL_LOG:?}"
  : > "${MOCK_STATE_DIR:?}/app-hold-initial"
  printf 'APP_REPLACEMENT_HOLD\n'
  exit 0
fi
if [ "${MOCK_APP_REPLACEMENT_INTERVAL:-}" = pre_stage ] &&
   printf '%s' "$command_text" 2>/dev/null | grep -Fq 'candidate=' &&
   printf '%s' "$command_text" 2>/dev/null | grep -Fq 'echo APP_REPLACEMENT_HOLD; exit 75'; then
  printf 'adb %s\n' "$*" >> "${MOCK_CALL_LOG:?}"
  : > "${MOCK_STATE_DIR:?}/app-hold-pre-stage"
  printf 'APP_REPLACEMENT_HOLD\n'
  exit 1
fi
if [ "${MOCK_APP_REPLACEMENT_INTERVAL:-}" = inflight ] &&
   printf '%s' "$command_text" 2>/dev/null | grep -Fq 'recorded_live LIVE_CANONICAL' &&
   printf '%s' "$command_text" 2>/dev/null | grep -Fq -- '--replacement-safe'; then
  printf 'adb %s\n' "$*" >> "${MOCK_CALL_LOG:?}"
  : > "${MOCK_STATE_DIR:?}/manual-helper-transaction"
  printf 'REPLACEMENT_AUTHORITY_ACTIVE\n'
  exit 1
fi
if [ "${MOCK_APP_REPLACEMENT_INTERVAL:-}" = slow_crash ] &&
   printf '%s' "$command_text" 2>/dev/null | grep -Fq 'echo INSTALL_OK' &&
   printf '%s' "$command_text" 2>/dev/null | grep -Fq 'while pidof hapaneld-helper'; then
  printf 'adb %s\n' "$*" >> "${MOCK_CALL_LOG:?}"
  : > "${MOCK_STATE_DIR:?}/manual-helper-transaction"
  printf 'R1_RETIREMENT_TIMEOUT\n'
  exit 1
fi
if [ "${MOCK_POST_RETIRE_LIVE_STATE:-}" = changed ] &&
   printf '%s' "$command_text" 2>/dev/null | grep -Fq 'echo LIVE_IDENTITY_CHANGED' &&
   printf '%s' "$command_text" 2>/dev/null | grep -Fq 'recorded_live LIVE_CANONICAL'; then
  printf 'adb %s\n' "$*" >> "${MOCK_CALL_LOG:?}"
  : > "${MOCK_STATE_DIR:?}/manual-helper-transaction"
  printf 'LIVE_IDENTITY_CHANGED\n'
  exit 1
fi
if [ "${MOCK_MANUAL_TRANSACTION_STATE:-}" = stale ] &&
   printf '%s' "$command_text" 2>/dev/null | grep -Fq inspect_manual_journal; then
  printf 'adb %s\n' "$*" >> "${MOCK_CALL_LOG:?}"
  printf 'STALE_SYSTEM_TRANSACTION %s %s %s %s\n' \
    "${MOCK_MANUAL_TRANSACTION_ID:?}" "${MOCK_MANUAL_TARGET_BUILD_ID:?}" \
    "${MOCK_MANUAL_TARGET_HELPER_SHA256:?}" "${MOCK_MANUAL_TARGET_SERVICE_SHA256:?}"
  exit 0
fi
if [ "${MOCK_MANUAL_LIVE_STATE:-}" = UNKNOWN ] &&
   printf '%s' "$command_text" 2>/dev/null | grep -Fq 'echo ROLLBACK_UNKNOWN' &&
   printf '%s' "$command_text" 2>/dev/null | grep -Fq '.hapaneld-helper-manual-upgrade'; then
  printf 'adb %s\n' "$*" >> "${MOCK_CALL_LOG:?}"
  printf 'ROLLBACK_UNKNOWN\n'
  exit 0
fi
if [ "${MOCK_MANUAL_COMMIT_LIVE_STATE:-}" = UNKNOWN ] &&
   printf '%s' "$command_text" 2>/dev/null | grep -Fq 'echo COMMIT_OK' &&
   printf '%s' "$command_text" 2>/dev/null | grep -Fq '.hapaneld-helper-manual-upgrade'; then
  printf 'adb %s\n' "$*" >> "${MOCK_CALL_LOG:?}"
  exit 1
fi
if [ "${MOCK_ROLLBACK_RETIREMENT:-ok}" = fail ] &&
   printf '%s' "$command_text" 2>/dev/null | grep -Fq ROLLBACK_RESTARTED; then
  printf 'adb %s\n' "$*" >> "${MOCK_CALL_LOG:?}"
  exit 1
fi
if [ "${MOCK_APP_REPLACEMENT_INTERVAL:-}" = slow_crash ] &&
   printf '%s' "$command_text" 2>/dev/null | grep -Fq 'phase_state_known' &&
   printf '%s' "$command_text" 2>/dev/null | grep -Fq 'restore LIVE_CANONICAL'; then
  printf 'adb %s\n' "$*" >> "${MOCK_CALL_LOG:?}"
  : > "${MOCK_STATE_DIR:?}/manual-helper-transaction"
  printf 'ROLLBACK_UNKNOWN\n'
  exit 0
fi
if [ -n "${MOCK_ROLLBACK_PUBLICATION_VERSION:-}" ] &&
   printf '%s' "$command_text" 2>/dev/null | grep -Fq 'ROLLBACK_RESTARTED' &&
   { printf '%s' "$command_text" 2>/dev/null | grep -Fq 'publish_recorded OLD_BIN' ||
     printf '%s' "$command_text" 2>/dev/null | grep -Fq 'restore LIVE_CANONICAL'; }; then
  printf 'adb %s\n' "$*" >> "${MOCK_CALL_LOG:?}"
  cut_state="${MOCK_STATE_DIR:?}/publication-cut-${MOCK_ROLLBACK_PUBLICATION_VERSION}"
  : > "${MOCK_STATE_DIR:?}/manual-helper-transaction"
  if [ ! -f "$cut_state" ]; then
    : > "$cut_state"
    printf 'ROLLBACK_PUBLICATION_CUT\n'
    exit 1
  fi
  printf 'ROLLBACK_RESTARTED\n'
  exit 0
fi
if printf '%s' "$command_text" 2>/dev/null | grep -Fq ROLLBACK_RESTARTED &&
   printf '%s' "$command_text" 2>/dev/null | grep -Fq '/data/local/.hapaneld-helper-manual-upgrade'; then
  printf 'adb %s\n' "$*" >> "${MOCK_CALL_LOG:?}"
  printf 'ROLLBACK_RESTARTED\n'
  exit 0
fi
exec "${REAL_ADB_FIXTURE:?}" "$@"
EOF
chmod +x "$TMP/bin/adb"
mkdir -p "$TMP/v1-command-bin"
cat > "$TMP/v1-command-bin/mv" <<'EOF'
#!/usr/bin/env bash
set -eu
restore_publish=0
for argument in "$@"; do
  case "$argument" in *.restore) restore_publish=1 ;; esac
done
printf 'before %s\n' "$*" >> "${MOCK_STATE_DIR:?}/real-mv.log"
/bin/mv "$@"
printf 'after %s\n' "$*" >> "${MOCK_STATE_DIR:?}/real-mv.log"
if [ "$restore_publish" -eq 1 ] && [ "${MOCK_V1_CUT_PUBLICATION:-}" = 1 ] &&
   [ ! -f "${MOCK_STATE_DIR:?}/v1-real/publication-cut" ]; then
  : > "${MOCK_STATE_DIR:?}/v1-real/publication-cut"
  kill -KILL "$PPID"
fi
EOF
for command_name in mount start stop; do
  cat > "$TMP/v1-command-bin/$command_name" <<'EOF'
#!/usr/bin/env bash
exit 0
EOF
done
for command_name in pidof pkill; do
  cat > "$TMP/v1-command-bin/$command_name" <<'EOF'
#!/usr/bin/env bash
exit 1
EOF
done
chmod +x "$TMP/v1-command-bin/"*
export PATH="$TMP/bin:/usr/bin:/bin"
export REAL_ADB_FIXTURE="$FIXTURES/adb"
export MOCK_TARGET="panel.test:5555"
export MOCK_CALL_LOG="$TMP/calls.log"
export MOCK_STATE_DIR="$TMP/state"
export MOCK_V1_COMMAND_DIR="$TMP/v1-command-bin"
export MOCK_ROOT=1
export MOCK_ADB_ROOT=0
export MOCK_SU_DIALECT=join
export MOCK_ABI=arm64-v8a
export MOCK_SYSTEM_WRITABLE=1
export MOCK_HELPER_BUILD_ID="$FOREIGN_BUILD_ID"
export MOCK_HELPER_REQUEST_DELAY=1
export MOCK_MANUAL_INSTALL_DELAY=2
export HAPANELD_HELPER_DIST_DIR="$TMP/dist"
export HAPANELD_MANUAL_LEASE_SECONDS=3
export HAPANELD_MANUAL_LEASE_RENEW_SECONDS=1
mkdir -p "$MOCK_STATE_DIR"
: > "$MOCK_CALL_LOG"

# Execute the exact root block passed to run_root_locked for stale-journal inspection. That is the
# first shared-lock owner in the standalone flow and now also reconciles app uploads/staging that
# never acquired replacement authority.
APP_STAGE_ROOT="$TMP/app-stage-root"
APP_STAGE_BLOCK="$TMP/app-stage-reconcile.sh"
awk '/^manual_journal_state="\$\(run_root_locked '\''$/{f=1;next} f&&/^'\'' 2>&1\)" \|\| true$/{exit} f' \
  "$INSTALLER" |
  sed -e 's|/system/|${APP_STAGE_ROOT}/system/|g' \
      -e 's|/vendor/|${APP_STAGE_ROOT}/vendor/|g' \
      -e 's|/data/|${APP_STAGE_ROOT}/data/|g' > "$APP_STAGE_BLOCK"
grep -q '^  reconcile_authority_free_app_staging$' "$APP_STAGE_BLOCK"
second_hold_line="$(grep -n 'echo APP_REPLACEMENT_HOLD; exit 75' "$INSTALLER" | tail -1 | cut -d: -f1)"
second_mount_line="$(awk -v after="$second_hold_line" 'NR > after && /mount -o rw,remount/{print NR; exit}' "$INSTALLER")"
second_candidate_line="$(awk -v after="$second_hold_line" 'NR > after && /^  candidate=/{print NR; exit}' "$INSTALLER")"
[ -n "$second_hold_line" ] && [ -n "$second_mount_line" ] && [ -n "$second_candidate_line" ]
[ "$second_hold_line" -lt "$second_mount_line" ] && [ "$second_mount_line" -lt "$second_candidate_line" ]
run_app_stage_block() {
  run_app_stage_script "$APP_STAGE_BLOCK"
}
run_app_stage_script() {
  local block="$1"
  APP_STAGE_ROOT="$APP_STAGE_ROOT" PATH=/usr/bin:/bin /bin/bash -u -c '
    cleanup_helper_lock() { rm -rf "$APP_STAGE_ROOT/dev/.hapaneld-helper-transaction.lock"; }
    abort_helper_lock() {
      status=$1
      trap - 0 1 2 3 15
      cleanup_helper_lock
      exit "$status"
    }
    . "$1"
  ' _ "$block"
}
run_app_stage_script_exec() {
  local block="$1"
  APP_STAGE_ROOT="$APP_STAGE_ROOT" PATH=/usr/bin:/bin exec /bin/bash -u -c '
    cleanup_helper_lock() { rm -rf "$APP_STAGE_ROOT/dev/.hapaneld-helper-transaction.lock"; }
    abort_helper_lock() {
      status=$1
      trap - 0 1 2 3 15
      cleanup_helper_lock
      exit "$status"
    }
    . "$1"
  ' _ "$block"
}
reset_app_stage_root() {
  rm -rf "$APP_STAGE_ROOT"
  mkdir -p "$APP_STAGE_ROOT/data/local" "$APP_STAGE_ROOT/data/adb/hapaneld" \
    "$APP_STAGE_ROOT/system/bin" "$APP_STAGE_ROOT/vendor"
}

APP_STAGE_SHA="$(printf '%064d' 1)"
reset_app_stage_root
printf 'verified candidate\n' > "$APP_STAGE_ROOT/data/local/.hapaneld-helper.new"
chmod 700 "$APP_STAGE_ROOT/data/local/.hapaneld-helper.new"
printf 'partial upload\n' > "$APP_STAGE_ROOT/data/local/.hapaneld-helper.app-stage-$APP_STAGE_SHA"
chmod 600 "$APP_STAGE_ROOT/data/local/.hapaneld-helper.app-stage-$APP_STAGE_SHA"
printf 'foreign upload\n' > "$APP_STAGE_ROOT/data/local/.hapaneld-helper.app-stage-not-a-sha"
chmod 600 "$APP_STAGE_ROOT/data/local/.hapaneld-helper.app-stage-not-a-sha"
[ "$(run_app_stage_block)" = NO_STALE_TRANSACTION ]
[ ! -e "$APP_STAGE_ROOT/data/local/.hapaneld-helper.new" ]
[ ! -e "$APP_STAGE_ROOT/data/local/.hapaneld-helper.app-stage-$APP_STAGE_SHA" ]
[ -f "$APP_STAGE_ROOT/data/local/.hapaneld-helper.app-stage-not-a-sha" ]

for app_authority in \
    "data/local/.hapaneld-guard-db/replacement.v1" \
    "data/local/.hapaneld-guard-db/.replacement.v1.tmp" \
    "data/local/.hapaneld-helper.legacy-takeover" \
    "data/local/.hapaneld-helper.legacy-takeover.tmp" \
    "data/local/.hapaneld-helper.previous" \
    "data/local/.hapaneld-helper.previous.tmp" \
    "system/bin/.hapaneld-helper-upgrade" \
    "data/adb/hapaneld/.helper-upgrade.marker" \
    "data/adb/hapaneld/.helper-hybrid-upgrade.marker" \
    "data/local/.hapaneld-helper-manual-upgrade" \
    "system/bin/.hapaneld-helper-manual-upgrade" \
    "data/adb/hapaneld/.helper-manual-upgrade.marker"; do
  reset_app_stage_root
  printf 'verified candidate\n' > "$APP_STAGE_ROOT/data/local/.hapaneld-helper.new"
  chmod 700 "$APP_STAGE_ROOT/data/local/.hapaneld-helper.new"
  mkdir -p "$(dirname "$APP_STAGE_ROOT/$app_authority")"
  : > "$APP_STAGE_ROOT/$app_authority"
  run_app_stage_block >/dev/null || true
  if [ ! -f "$APP_STAGE_ROOT/data/local/.hapaneld-helper.new" ]; then
    echo "$app_authority did not preserve app replacement staging" >&2
    exit 1
  fi
done

for malformed_stage in empty wrong-mode wrong-owner hardlink symlink oversized; do
  reset_app_stage_root
  stage_path="$APP_STAGE_ROOT/data/local/.hapaneld-helper.new"
  case "$malformed_stage" in
    empty) : > "$stage_path"; chmod 700 "$stage_path" ;;
    wrong-mode) printf x > "$stage_path"; chmod 600 "$stage_path" ;;
    wrong-owner) printf x > "$stage_path"; chmod 700 "$stage_path"; chown 1:1 "$stage_path" ;;
    hardlink) printf x > "$stage_path"; chmod 700 "$stage_path"; ln "$stage_path" "$APP_STAGE_ROOT/data/local/stage-second-link" ;;
    symlink) ln -s /nonexistent "$stage_path" ;;
    oversized) truncate -s 16777217 "$stage_path"; chmod 700 "$stage_path" ;;
  esac
  [ "$(run_app_stage_block)" = APP_REPLACEMENT_HOLD ]
  if [ ! -e "$stage_path" ] && [ ! -L "$stage_path" ]; then
    echo "$malformed_stage app replacement staging was deleted" >&2
    exit 1
  fi
done

# Exercise the embedded standalone normalizer itself. Distinct executables provide exact process
# inode binding; the candidate reports GUARD_ARMED while live, proving there is no pre-stop safety
# probe, then reports REPLACE_SAFE only after the exact candidate lineage is drained.
STANDALONE_LEGACY_C="$TMP/standalone-legacy-fixture.c"
STANDALONE_LEGACY_CANDIDATE="$TMP/standalone-legacy-candidate"
STANDALONE_LEGACY_OLD="$TMP/standalone-legacy-old"
cat > "$STANDALONE_LEGACY_C" <<'EOF'
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#ifndef CANDIDATE_ROLE
#define CANDIDATE_ROLE 0
#endif
static char ready_path[1024];
static volatile sig_atomic_t stop_requested;
static void request_stop(int signal_number) { (void)signal_number; stop_requested = 1; }
static int ready_file(const char *name) {
    const char *root = getenv("STANDALONE_LEGACY_ROOT");
    if (!root) return 0;
    snprintf(ready_path, sizeof(ready_path), "%s/%s", root, name);
    return access(ready_path, F_OK) == 0;
}
static int serve(const char *name) {
    const char *root = getenv("STANDALONE_LEGACY_ROOT");
    if (!root) return 1;
    snprintf(ready_path, sizeof(ready_path), "%s/%s", root, name);
    FILE *ready = fopen(ready_path, "w");
    if (!ready) return 1;
    fclose(ready);
    signal(SIGTERM, request_stop);
    signal(SIGINT, request_stop);
    signal(SIGHUP, request_stop);
    while (!stop_requested) pause();
    if (CANDIDATE_ROLE && getenv("STANDALONE_LEGACY_STOP_DELAY")) {
        char stopping_path[1024];
        snprintf(stopping_path, sizeof(stopping_path), "%s/candidate-stopping", root);
        FILE *stopping = fopen(stopping_path, "w");
        if (stopping) fclose(stopping);
        sleep(2);
    }
    unlink(ready_path);
    _exit(0);
}
int main(int argc, char **argv) {
    if (CANDIDATE_ROLE && argc == 2 && strcmp(argv[1], "--replacement-safe") == 0) {
        if (ready_file("candidate-ready") || getenv("STANDALONE_LEGACY_POST_SAFE_ARMED")) {
            puts("GUARD_ARMED");
            return 3;
        }
        puts("REPLACE_SAFE");
        return 0;
    }
    if (argc == 3 && strcmp(argv[1], "--request") == 0) {
        if (!CANDIDATE_ROLE && strcmp(argv[2], "BUILDID") == 0 && ready_file("old-ready")) {
            printf("BUILDID %s\n", getenv("STANDALONE_LEGACY_OLD_BUILD"));
            return 0;
        }
        if (CANDIDATE_ROLE && strcmp(argv[2], "GUARDSELF") == 0 && ready_file("candidate-ready")) {
            printf("OK GUARDSELF 1 %s %s %s\n", getenv("STANDALONE_LEGACY_CANDIDATE_BYTES"),
                   getenv("STANDALONE_LEGACY_CANDIDATE_SHA"),
                   getenv("STANDALONE_LEGACY_CANDIDATE_BUILD"));
            return 0;
        }
        return 1;
    }
    return serve(CANDIDATE_ROLE ? "candidate-ready" : "old-ready");
}
EOF
cc -O2 -DCANDIDATE_ROLE=1 "$STANDALONE_LEGACY_C" -o "$STANDALONE_LEGACY_CANDIDATE"
cc -O2 -DCANDIDATE_ROLE=0 "$STANDALONE_LEGACY_C" -o "$STANDALONE_LEGACY_OLD"

standalone_legacy_path_processes() {
  local path="$1" inode executable executable_inode found=
  [ -f "$path" ] || return 0
  inode="$(stat -c '%d:%i' "$path")"
  for executable in /proc/[0-9]*/exe; do
    executable_inode="$(stat -Lc '%d:%i' "$executable" 2>/dev/null || true)"
    [ "$executable_inode" != "$inode" ] || found="$found ${executable#/proc/}"
  done
  printf '%s\n' "$found"
}
standalone_legacy_stop_path() {
  local executable pid
  for executable in $(standalone_legacy_path_processes "$1"); do
    pid=${executable%/exe}
    kill "$pid" 2>/dev/null || true
  done
}
standalone_legacy_cleanup() {
  standalone_legacy_stop_path "$APP_STAGE_ROOT/data/local/hapaneld-helper"
  standalone_legacy_stop_path "$APP_STAGE_ROOT/data/local/.hapaneld-helper.new"
  standalone_legacy_stop_path "$APP_STAGE_ROOT/system/bin/hapaneld-helper"
  standalone_legacy_stop_path "$APP_STAGE_ROOT/data/adb/hapaneld/hapaneld-helper"
  sleep 0.05
}
standalone_legacy_wait() {
  local path="$1" attempt=0
  while [ ! -f "$path" ]; do
    attempt=$((attempt + 1)); [ "$attempt" -lt 3000 ] || return 1
    sleep 0.01
  done
}
standalone_legacy_registration() {
  local topology="$1"
  case "$topology" in
    system)
      STANDALONE_LEGACY_REGISTRATION="$APP_STAGE_ROOT/system/etc/init/hapaneld-helper.rc"
      mkdir -p "${STANDALONE_LEGACY_REGISTRATION%/*}"
      cat > "$STANDALONE_LEGACY_REGISTRATION" <<'EOF'
service hapaneld_helper /system/bin/hapaneld-helper
    class main
    user root
    group root
    seclabel u:r:su:s0
EOF
      STANDALONE_LEGACY_REGISTRATION_MODE=644
      STANDALONE_LEGACY_REGISTRATION_EXPECTED=9b430712c493df177a19e5e893df445f6c2e951fc30ea140dcdbcdb7987de659 ;;
    systemless)
      STANDALONE_LEGACY_REGISTRATION="$APP_STAGE_ROOT/data/adb/service.d/hapaneld-helper.sh"
      mkdir -p "${STANDALONE_LEGACY_REGISTRATION%/*}"
      cat > "$STANDALONE_LEGACY_REGISTRATION" <<'EOF'
#!/system/bin/sh
while [ "$(getprop sys.boot_completed)" != "1" ]; do sleep 3; done
/system/bin/stop hapaneld_helper 2>/dev/null
/system/bin/stop hapaneld_ledd 2>/dev/null
/system/bin/pkill -x hapaneld-helper 2>/dev/null
/system/bin/pkill -x hapaneld-ledd 2>/dev/null
/data/adb/hapaneld/hapaneld-helper >/dev/null 2>&1 &
EOF
      STANDALONE_LEGACY_REGISTRATION_MODE=755
      STANDALONE_LEGACY_REGISTRATION_EXPECTED=60ff22aa9b38483cbffd95a653d804d0d9abf682e1b952e8b4519d5c0f3f9493 ;;
    hybrid)
      STANDALONE_LEGACY_REGISTRATION="$APP_STAGE_ROOT/vendor/etc/init/hapaneld-helper.rc"
      mkdir -p "${STANDALONE_LEGACY_REGISTRATION%/*}"
      cat > "$STANDALONE_LEGACY_REGISTRATION" <<'EOF'
service hapaneld_helper /data/adb/hapaneld/hapaneld-helper
    class main
    user root
    group root
    seclabel u:r:su:s0
EOF
      STANDALONE_LEGACY_REGISTRATION_MODE=644
      STANDALONE_LEGACY_REGISTRATION_EXPECTED=cf146dd5320fcb017514def6295fdb0c473e150a478d5c2219af2e3f03826ed1 ;;
  esac
  chmod "$STANDALONE_LEGACY_REGISTRATION_MODE" "$STANDALONE_LEGACY_REGISTRATION"
  STANDALONE_LEGACY_REGISTRATION_SHA="$(sha256sum "$STANDALONE_LEGACY_REGISTRATION" | awk '{print $1}')"
  [ "$STANDALONE_LEGACY_REGISTRATION_SHA" = "$STANDALONE_LEGACY_REGISTRATION_EXPECTED" ]
}
standalone_legacy_prepare() {
  local topology="$1" location="${2:-live}" old_bin
  standalone_legacy_cleanup
  reset_app_stage_root
  mkdir -p "$APP_STAGE_ROOT/dev" "$APP_STAGE_ROOT/data/adb/hapaneld" \
    "$APP_STAGE_ROOT/system/bin" "$APP_STAGE_ROOT/vendor"
  standalone_legacy_registration "$topology"
  case "$topology" in
    system) old_bin="$APP_STAGE_ROOT/system/bin/hapaneld-helper" ;;
    systemless|hybrid) old_bin="$APP_STAGE_ROOT/data/adb/hapaneld/hapaneld-helper" ;;
  esac
  cp "$STANDALONE_LEGACY_OLD" "$old_bin"; chmod 755 "$old_bin"
  STANDALONE_LEGACY_OLD_BIN="$old_bin"
  if [ "$location" = stage ]; then
    STANDALONE_LEGACY_CANDIDATE_PATH="$APP_STAGE_ROOT/data/local/.hapaneld-helper.new"
  else
    STANDALONE_LEGACY_CANDIDATE_PATH="$APP_STAGE_ROOT/data/local/hapaneld-helper"
  fi
  cp "$STANDALONE_LEGACY_CANDIDATE" "$STANDALONE_LEGACY_CANDIDATE_PATH"
  chmod 700 "$STANDALONE_LEGACY_CANDIDATE_PATH"
  STANDALONE_LEGACY_OLD_SHA="$(sha256sum "$old_bin" | awk '{print $1}')"
  STANDALONE_LEGACY_OLD_BYTES="$(wc -c < "$old_bin")"
  STANDALONE_LEGACY_CANDIDATE_SHA="$(sha256sum "$STANDALONE_LEGACY_CANDIDATE_PATH" | awk '{print $1}')"
  STANDALONE_LEGACY_CANDIDATE_BYTES="$(wc -c < "$STANDALONE_LEGACY_CANDIDATE_PATH")"
  STANDALONE_LEGACY_REGISTRATION_BYTES="$(wc -c < "$STANDALONE_LEGACY_REGISTRATION")"
  STANDALONE_LEGACY_OLD_BUILD="$(printf 'c%.0s' {1..64})"
  STANDALONE_LEGACY_CANDIDATE_BUILD="$(printf 'd%.0s' {1..64})"
  export STANDALONE_LEGACY_ROOT="$APP_STAGE_ROOT" STANDALONE_LEGACY_OLD_BIN \
    STANDALONE_LEGACY_OLD_BUILD STANDALONE_LEGACY_CANDIDATE_BUILD \
    STANDALONE_LEGACY_CANDIDATE_SHA STANDALONE_LEGACY_CANDIDATE_BYTES
  cat > "$APP_STAGE_ROOT/system/bin/start" <<'EOF'
#!/bin/sh
: > "$STANDALONE_LEGACY_ROOT/init-started"
"$STANDALONE_LEGACY_OLD_BIN" >/dev/null 2>&1 &
EOF
  chmod 755 "$APP_STAGE_ROOT/system/bin/start"
  printf 'OK LEGACYTAKEOVER 1 %s %s %s %s %s %s %s %s %s %s\n' \
    "$topology" "$STANDALONE_LEGACY_OLD_SHA" "$STANDALONE_LEGACY_OLD_BYTES" \
    "$STANDALONE_LEGACY_REGISTRATION_SHA" "$STANDALONE_LEGACY_REGISTRATION_BYTES" \
    "$STANDALONE_LEGACY_REGISTRATION_MODE" "$STANDALONE_LEGACY_OLD_BUILD" \
    "$STANDALONE_LEGACY_CANDIDATE_BUILD" "$STANDALONE_LEGACY_CANDIDATE_SHA" \
    "$STANDALONE_LEGACY_CANDIDATE_BYTES" \
    > "$APP_STAGE_ROOT/data/local/.hapaneld-helper.legacy-takeover"
  chmod 600 "$APP_STAGE_ROOT/data/local/.hapaneld-helper.legacy-takeover"
}

for standalone_legacy_topology in system systemless hybrid; do
  standalone_legacy_prepare "$standalone_legacy_topology" live
  "$STANDALONE_LEGACY_CANDIDATE_PATH" --supervise >/dev/null 2>&1 &
  standalone_legacy_candidate_pid=$!
  standalone_legacy_wait "$APP_STAGE_ROOT/candidate-ready"
  [ "$(run_app_stage_block)" = NO_STALE_TRANSACTION ]
  [ ! -e "$APP_STAGE_ROOT/data/local/.hapaneld-helper.legacy-takeover" ]
  ! kill -0 "$standalone_legacy_candidate_pid" 2>/dev/null
  standalone_legacy_wait "$APP_STAGE_ROOT/old-ready"
  case "$standalone_legacy_topology" in
    system|hybrid) [ -f "$APP_STAGE_ROOT/init-started" ] ;;
    systemless) [ ! -e "$APP_STAGE_ROOT/init-started" ] ;;
  esac
  standalone_legacy_cleanup
done

# Replay the durable cut after the exact old daemon is verified but before the record unlink. The
# next locked entry must validate the retained authority again and finish on every topology.
STANDALONE_LEGACY_PRE_UNLINK="$TMP/standalone-legacy-pre-unlink.sh"
sed '/^    legacy_record_after=$(app_stage_metadata "$legacy_record")/i\
: > "$STANDALONE_LEGACY_ROOT/pre-unlink-ready"\
while [ ! -e "$STANDALONE_LEGACY_ROOT/pre-unlink-release" ]; do sleep 1; done
' "$APP_STAGE_BLOCK" > "$STANDALONE_LEGACY_PRE_UNLINK"
for standalone_legacy_topology in system systemless hybrid; do
  standalone_legacy_prepare "$standalone_legacy_topology" live
  "$STANDALONE_LEGACY_CANDIDATE_PATH" --supervise >/dev/null 2>&1 &
  standalone_legacy_wait "$APP_STAGE_ROOT/candidate-ready"
  run_app_stage_script_exec "$STANDALONE_LEGACY_PRE_UNLINK" \
    > "$TMP/standalone-legacy-pre-unlink-$standalone_legacy_topology.out" 2>&1 &
  standalone_legacy_cut_pid=$!
  standalone_legacy_wait "$APP_STAGE_ROOT/pre-unlink-ready"
  kill -KILL "$standalone_legacy_cut_pid"
  wait "$standalone_legacy_cut_pid" 2>/dev/null || true
  [ -f "$APP_STAGE_ROOT/data/local/.hapaneld-helper.legacy-takeover" ]
  standalone_legacy_wait "$APP_STAGE_ROOT/old-ready"
  [ "$(run_app_stage_block)" = NO_STALE_TRANSACTION ]
  [ ! -e "$APP_STAGE_ROOT/data/local/.hapaneld-helper.legacy-takeover" ]
  standalone_legacy_cleanup
done

# App death before candidate launch and both lone-tmp publication cuts recover without inventing
# authority; an executing stage is foreign live custody and remains untouched.
standalone_legacy_prepare system stage
[ "$(run_app_stage_block)" = NO_STALE_TRANSACTION ]
[ ! -e "$APP_STAGE_ROOT/data/local/.hapaneld-helper.legacy-takeover" ]
[ ! -e "$APP_STAGE_ROOT/data/local/.hapaneld-helper.new" ]
standalone_legacy_cleanup
for standalone_legacy_tmp_kind in partial complete; do
  standalone_legacy_prepare system stage
  rm -f "$APP_STAGE_ROOT/data/local/.hapaneld-helper.legacy-takeover"
  if [ "$standalone_legacy_tmp_kind" = partial ]; then
    : > "$APP_STAGE_ROOT/data/local/.hapaneld-helper.legacy-takeover.tmp"
  else
    printf 'complete preauthority record bytes\n' > "$APP_STAGE_ROOT/data/local/.hapaneld-helper.legacy-takeover.tmp"
  fi
  chmod 600 "$APP_STAGE_ROOT/data/local/.hapaneld-helper.legacy-takeover.tmp"
  [ "$(run_app_stage_block)" = NO_STALE_TRANSACTION ]
  [ ! -e "$APP_STAGE_ROOT/data/local/.hapaneld-helper.legacy-takeover.tmp" ]
  [ ! -e "$APP_STAGE_ROOT/data/local/.hapaneld-helper.new" ]
  standalone_legacy_cleanup
done
standalone_legacy_prepare system stage
"$STANDALONE_LEGACY_CANDIDATE_PATH" --supervise >/dev/null 2>&1 &
standalone_legacy_stage_pid=$!
standalone_legacy_wait "$APP_STAGE_ROOT/candidate-ready"
[ "$(run_app_stage_block)" = LEGACY_TAKEOVER_HOLD ]
kill -0 "$standalone_legacy_stage_pid"
[ -f "$APP_STAGE_ROOT/data/local/.hapaneld-helper.legacy-takeover" ]
[ -f "$APP_STAGE_ROOT/data/local/.hapaneld-helper.new" ]
standalone_legacy_cleanup

reset_app_stage_root
mkdir -p "$APP_STAGE_ROOT/dev"
cp "$STANDALONE_LEGACY_CANDIDATE" "$APP_STAGE_ROOT/data/local/.hapaneld-helper.new"
chmod 700 "$APP_STAGE_ROOT/data/local/.hapaneld-helper.new"
export STANDALONE_LEGACY_ROOT="$APP_STAGE_ROOT"
"$APP_STAGE_ROOT/data/local/.hapaneld-helper.new" --supervise >/dev/null 2>&1 &
standalone_legacy_stage_pid=$!
standalone_legacy_wait "$APP_STAGE_ROOT/candidate-ready"
printf 'partial preauthority record\n' > "$APP_STAGE_ROOT/data/local/.hapaneld-helper.legacy-takeover.tmp"
chmod 600 "$APP_STAGE_ROOT/data/local/.hapaneld-helper.legacy-takeover.tmp"
[ "$(run_app_stage_block)" = LEGACY_TAKEOVER_HOLD ]
kill -0 "$standalone_legacy_stage_pid"
[ -f "$APP_STAGE_ROOT/data/local/.hapaneld-helper.legacy-takeover.tmp" ]
[ -f "$APP_STAGE_ROOT/data/local/.hapaneld-helper.new" ]
rm -f "$APP_STAGE_ROOT/data/local/.hapaneld-helper.legacy-takeover.tmp"
[ "$(run_app_stage_block)" = APP_REPLACEMENT_HOLD ]
kill -0 "$standalone_legacy_stage_pid"
[ -f "$APP_STAGE_ROOT/data/local/.hapaneld-helper.new" ]
standalone_legacy_cleanup

# Post-stop GUARD_ARMED and TERM during the original candidate's delayed drain both restore the
# exact canonical candidate and retain the record. The signal path must drain before relaunch, leave
# one fixture process, release the shared lock, and permit immediate normal retry.
standalone_legacy_prepare system live
"$STANDALONE_LEGACY_CANDIDATE_PATH" --supervise >/dev/null 2>&1 &
standalone_legacy_wait "$APP_STAGE_ROOT/candidate-ready"
export STANDALONE_LEGACY_POST_SAFE_ARMED=1
[ "$(run_app_stage_block)" = LEGACY_TAKEOVER_HOLD ]
unset STANDALONE_LEGACY_POST_SAFE_ARMED
[ -f "$APP_STAGE_ROOT/data/local/.hapaneld-helper.legacy-takeover" ]
standalone_legacy_wait "$APP_STAGE_ROOT/candidate-ready"
standalone_legacy_cleanup

standalone_legacy_prepare system live
export STANDALONE_LEGACY_STOP_DELAY=1
"$STANDALONE_LEGACY_CANDIDATE_PATH" --supervise >/dev/null 2>&1 &
standalone_legacy_candidate_pid=$!
standalone_legacy_wait "$APP_STAGE_ROOT/candidate-ready"
mkdir -p "$APP_STAGE_ROOT/dev/.hapaneld-helper-transaction.lock"
APP_STAGE_ROOT="$APP_STAGE_ROOT" PATH=/usr/bin:/bin /bin/bash -u -c '
  cleanup_helper_lock() { rm -rf "$APP_STAGE_ROOT/dev/.hapaneld-helper-transaction.lock"; }
  abort_helper_lock() {
    status=$1
    trap - 0 1 2 3 15
    cleanup_helper_lock
    exit "$status"
  }
  . "$1"
' _ "$APP_STAGE_BLOCK" > "$TMP/standalone-legacy-drain-signal.out" 2>&1 &
standalone_legacy_signal_pid=$!
standalone_legacy_wait "$APP_STAGE_ROOT/candidate-stopping"
kill -TERM "$standalone_legacy_signal_pid"
if wait "$standalone_legacy_signal_pid"; then standalone_legacy_signal_status=0; else standalone_legacy_signal_status=$?; fi
[ "$standalone_legacy_signal_status" -eq 143 ]
[ ! -e "$APP_STAGE_ROOT/dev/.hapaneld-helper-transaction.lock" ]
[ -f "$APP_STAGE_ROOT/data/local/.hapaneld-helper.legacy-takeover" ]
! kill -0 "$standalone_legacy_candidate_pid" 2>/dev/null
standalone_legacy_wait "$APP_STAGE_ROOT/candidate-ready"
standalone_legacy_recovered="$(standalone_legacy_path_processes "$STANDALONE_LEGACY_CANDIDATE_PATH")"
[ "$(printf '%s\n' "$standalone_legacy_recovered" | wc -w)" -eq 1 ]
unset STANDALONE_LEGACY_STOP_DELAY
rm -f "$APP_STAGE_ROOT/candidate-stopping"
[ "$(run_app_stage_block)" = NO_STALE_TRANSACTION ]
[ ! -e "$APP_STAGE_ROOT/data/local/.hapaneld-helper.legacy-takeover" ]
standalone_legacy_cleanup

unset APP_STAGE_ROOT APP_STAGE_BLOCK APP_STAGE_SHA app_authority malformed_stage stage_path \
  STANDALONE_LEGACY_C STANDALONE_LEGACY_CANDIDATE STANDALONE_LEGACY_OLD \
  STANDALONE_LEGACY_ROOT STANDALONE_LEGACY_OLD_BIN STANDALONE_LEGACY_OLD_BUILD \
  STANDALONE_LEGACY_CANDIDATE_BUILD STANDALONE_LEGACY_CANDIDATE_SHA \
  STANDALONE_LEGACY_CANDIDATE_BYTES STANDALONE_LEGACY_REGISTRATION \
  STANDALONE_LEGACY_REGISTRATION_MODE STANDALONE_LEGACY_REGISTRATION_EXPECTED \
  STANDALONE_LEGACY_REGISTRATION_SHA STANDALONE_LEGACY_REGISTRATION_BYTES \
  STANDALONE_LEGACY_OLD_SHA STANDALONE_LEGACY_OLD_BYTES \
  STANDALONE_LEGACY_CANDIDATE_PATH standalone_legacy_topology \
  standalone_legacy_candidate_pid standalone_legacy_tmp_kind standalone_legacy_stage_pid \
  standalone_legacy_signal_pid standalone_legacy_signal_status standalone_legacy_recovered \
  STANDALONE_LEGACY_PRE_UNLINK standalone_legacy_cut_pid

rm -rf "$MOCK_STATE_DIR/shared-lock-signal"
export MOCK_SHARED_LOCK_SIGNAL=1
export MOCK_ADB_ROOT=1
if bash "$INSTALLER" "$MOCK_TARGET" >"$TMP/shared-lock-signal.out" 2>&1; then
  echo "signal-interrupted shared-lock transaction unexpectedly resumed" >&2
  exit 1
fi
[ -f "$MOCK_STATE_DIR/shared-lock-signal/remote-status" ] || {
  cat "$TMP/shared-lock-signal.out" >&2
  if grep -q inspect_manual_journal_v1 "$MOCK_CALL_LOG"; then
    echo "signal fixture observed the journal inspection but did not execute it" >&2
  else
    echo "signal fixture never observed the journal inspection" >&2
  fi
  exit 1
}
grep -qx 143 "$MOCK_STATE_DIR/shared-lock-signal/remote-status"
[ -f "$MOCK_STATE_DIR/shared-lock-signal/handler-ready" ]
[ ! -e "$MOCK_STATE_DIR/shared-lock-signal/post-signal-mutation" ]
[ ! -e "$MOCK_STATE_DIR/shared-lock-signal/dev/.hapaneld-helper-transaction.lock" ]
grep -q 'could not determine the root-helper recovery state' "$TMP/shared-lock-signal.out"
unset MOCK_SHARED_LOCK_SIGNAL
export MOCK_ADB_ROOT=0
: > "$MOCK_CALL_LOG"
bash "$INSTALLER" "$MOCK_TARGET" >"$TMP/shared-lock-signal-recovery.out" 2>&1
[ ! -f "$MOCK_STATE_DIR/manual-helper-transaction" ]
rm -rf "$MOCK_STATE_DIR/shared-lock-signal"

bash "$INSTALLER" "$MOCK_TARGET" >"$TMP/first.out" 2>&1 &
first_pid=$!
for _ in 1 2 3 4 5; do
  [ -f "$MOCK_STATE_DIR/manual-helper-transaction" ] && break
  /bin/sleep 1
done
[ -f "$MOCK_STATE_DIR/manual-helper-transaction" ]

if bash "$INSTALLER" "$MOCK_TARGET" >"$TMP/second.out" 2>&1; then
  echo "concurrent standalone installer unexpectedly acquired an active transaction" >&2
  exit 1
fi
grep -q 'another standalone root-helper installer owns an active transaction lease' "$TMP/second.out"

wait "$first_pid"
first_pid=""
export MOCK_HELPER_REQUEST_DELAY=0
export MOCK_MANUAL_INSTALL_DELAY=0
grep -q 'echo LEASE_OK' "$MOCK_CALL_LOG"
[ ! -f "$MOCK_STATE_DIR/manual-helper-transaction" ]

: > "$MOCK_CALL_LOG"
export MOCK_MANUAL_LEASE_RENEW=fail
export MOCK_MANUAL_INSTALL_DELAY=2
if bash "$INSTALLER" "$MOCK_TARGET" >"$TMP/renew-fail.out" 2>&1; then
  echo "installer unexpectedly committed after losing its transaction lease" >&2
  exit 1
fi
grep -q 'transaction lease could not be renewed; the prior helper was restored' "$TMP/renew-fail.out" || {
  cat "$TMP/renew-fail.out" >&2
  exit 1
}
grep -q 'echo ROLLBACK_FINALIZED' "$MOCK_CALL_LOG"
[ ! -f "$MOCK_STATE_DIR/manual-helper-transaction" ]
export MOCK_MANUAL_INSTALL_DELAY=0

export MOCK_MANUAL_LEASE_RENEW=ok
export MOCK_HELPER_CAPABILITY=fail
export MOCK_ROLLBACK_PING=fail
: > "$MOCK_CALL_LOG"
if bash "$INSTALLER" "$MOCK_TARGET" >"$TMP/ping-fail.out" 2>&1; then
  echo "installer unexpectedly finalized rollback after restored-helper PING failed" >&2
  exit 1
fi
grep -q 'rollback could not be verified' "$TMP/ping-fail.out"
[ -f "$MOCK_STATE_DIR/manual-helper-transaction" ]
! grep -q 'echo ROLLBACK_FINALIZED' "$MOCK_CALL_LOG"
rm -f "$MOCK_STATE_DIR/manual-helper-transaction"

export MOCK_ROLLBACK_PING=ok
export MOCK_ROLLBACK_RETIREMENT=fail
: > "$MOCK_CALL_LOG"
if bash "$INSTALLER" "$MOCK_TARGET" >"$TMP/retirement-fail.out" 2>&1; then
  echo "installer unexpectedly finalized rollback before the old helper retired" >&2
  exit 1
fi
grep -q 'rollback could not be verified' "$TMP/retirement-fail.out"
[ -f "$MOCK_STATE_DIR/manual-helper-transaction" ]
! grep -q 'echo ROLLBACK_FINALIZED' "$MOCK_CALL_LOG"
unset MOCK_ROLLBACK_RETIREMENT
rm -f "$MOCK_STATE_DIR/manual-helper-transaction"

export MOCK_HELPER_CAPABILITY=ok
export MOCK_ROLLBACK_PING=ok
export MOCK_MANUAL_TRANSACTION_STATE=stale
export MOCK_MANUAL_LIVE_STATE=UNKNOWN
export MOCK_MANUAL_TRANSACTION_ID=0123456789abcdef0123456789abcdef
export MOCK_MANUAL_TARGET_BUILD_ID="$MOCK_HELPER_BUILD_ID"
export MOCK_MANUAL_TARGET_HELPER_SHA256="$(sha256sum "$TMP/dist/arm64-v8a/hapaneld-helper" | awk '{print $1}')"
export MOCK_MANUAL_TARGET_SERVICE_SHA256="$(sha256sum "$ROOT/helper/hapaneld-helper.rc" | awk '{print $1}')"
: > "$MOCK_STATE_DIR/manual-helper-transaction"
: > "$MOCK_CALL_LOG"
if bash "$INSTALLER" "$MOCK_TARGET" >"$TMP/superseded-live.out" 2>&1; then
  echo "installer unexpectedly rolled back a stale journal over superseded live helper state" >&2
  exit 1
fi
grep -q 'retained /system helper-only journal could not be recovered safely' "$TMP/superseded-live.out"
grep -q 'ROLLBACK_UNKNOWN' "$MOCK_CALL_LOG"
[ -f "$MOCK_STATE_DIR/manual-helper-transaction" ]
! grep -q 'echo ROLLBACK_FINALIZED' "$MOCK_CALL_LOG"
unset MOCK_MANUAL_TRANSACTION_STATE MOCK_MANUAL_LIVE_STATE
rm -f "$MOCK_STATE_DIR/manual-helper-transaction"

rm -rf "$MOCK_STATE_DIR/v3-lease-race"
export MOCK_V3_LEASE_PHASE_RACE=1
export MOCK_ADB_ROOT=1
: > "$MOCK_CALL_LOG"
if ! bash "$INSTALLER" "$MOCK_TARGET" >"$TMP/v3-lease-phase-race.out" 2>&1; then
  cat "$TMP/v3-lease-phase-race.out" >&2
  cat "$MOCK_CALL_LOG" >&2
  exit 1
fi
[ -f "$MOCK_STATE_DIR/v3-lease-race/renewal-blocked-by-phase-lock" ]
[ -f "$MOCK_STATE_DIR/v3-lease-race/target-phase-renewed" ]
grep -qx TARGET "$MOCK_STATE_DIR/v3-lease-race/committed-phase"
[ ! -e "$MOCK_STATE_DIR/v3-lease-race/data/local/.hapaneld-helper-manual-upgrade" ]
grep -q 'echo LEASE_OK' "$MOCK_CALL_LOG"
unset MOCK_V3_LEASE_PHASE_RACE
export MOCK_ADB_ROOT=0
rm -rf "$MOCK_STATE_DIR/v3-lease-race"

export MOCK_MANUAL_TRANSACTION_STATE=stale_v3
: > "$MOCK_STATE_DIR/manual-helper-transaction"
: > "$MOCK_CALL_LOG"
bash "$INSTALLER" "$MOCK_TARGET" >"$TMP/stale-v3.out" 2>&1
grep -q 'ROLLBACK_RESTARTED' "$MOCK_CALL_LOG"
grep -q 'echo ROLLBACK_FINALIZED' "$MOCK_CALL_LOG"
[ ! -f "$MOCK_STATE_DIR/manual-helper-transaction" ]
unset MOCK_MANUAL_TRANSACTION_STATE

v3_root="$MOCK_STATE_DIR/v3-real"
v3_transaction=abcdef0123456789abcdef0123456789
prepare_v3_real_prepared() {
  rm -rf "$v3_root"
  mkdir -p "$v3_root/dev" "$v3_root/data/local" "$v3_root/system/bin" \
    "$v3_root/system/etc/init" "$v3_root/vendor/etc/init" "$v3_root/data/adb/hapaneld" \
    "$v3_root/data/adb/service.d"
  printf 'v3 retained prior canonical helper\n' > "$v3_root/data/local/hapaneld-helper"
  chmod 700 "$v3_root/data/local/hapaneld-helper"
  cp "$v3_root/data/local/hapaneld-helper" \
    "$v3_root/data/local/.hapaneld-helper-manual-$v3_transaction.recovery-LIVE_CANONICAL"
  chmod 600 "$v3_root/data/local/.hapaneld-helper-manual-$v3_transaction.recovery-LIVE_CANONICAL"
  v3_old_helper="$(sha256sum "$v3_root/data/local/hapaneld-helper" | awk '{print $1}')"
  cat > "$v3_root/data/local/.hapaneld-helper-manual-upgrade" <<EOF
JOURNAL_VERSION=3
JOURNAL_SCOPE=HELPER_ONLY
REGISTRATION_KIND=system
TRANSACTION_ID=$v3_transaction
TARGET_BUILD_ID=$MOCK_HELPER_BUILD_ID
TARGET_HELPER_SHA256=$MOCK_MANUAL_TARGET_HELPER_SHA256
TARGET_SERVICE_SHA256=$MOCK_MANUAL_TARGET_SERVICE_SHA256
SWAP_PHASE=PREPARED
LEASE_BOOT_ID=00000000-0000-0000-0000-000000000000
LEASE_UNTIL_UPTIME=0
LIVE_CANONICAL=1
LIVE_CANONICAL_SHA256=$v3_old_helper
LIVE_SYSTEM_BIN=0
LIVE_SYSTEM_BIN_SHA256=-
LIVE_SYSTEM_RC=0
LIVE_SYSTEM_RC_SHA256=-
LIVE_VENDOR_RC=0
LIVE_VENDOR_RC_SHA256=-
LIVE_SYSTEMLESS_BIN=0
LIVE_SYSTEMLESS_BIN_SHA256=-
LIVE_SYSTEMLESS_SERVICE=0
LIVE_SYSTEMLESS_SERVICE_SHA256=-
LIVE_LEGACY_BIN=0
LIVE_LEGACY_BIN_SHA256=-
LIVE_LEGACY_RC=0
LIVE_LEGACY_RC_SHA256=-
EOF
  chmod 600 "$v3_root/data/local/.hapaneld-helper-manual-upgrade"
  : > "$MOCK_STATE_DIR/manual-helper-transaction"
  rm -f "$MOCK_STATE_DIR/real-mv.log"
  : > "$MOCK_CALL_LOG"
}

export MOCK_V3_REAL_FILESYSTEM=1
export MOCK_ADB_ROOT=1
for custody_state in active hung; do
  prepare_v3_real_prepared
  case "$custody_state" in
    active) : > "$v3_root/data/local/.hapaneld-helper.new" ;;
    hung)
      mkdir -p "$v3_root/data/local/.hapaneld-guard-db"
      : > "$v3_root/data/local/.hapaneld-guard-db/replacement.v1"
      ;;
  esac
  v3_marker_hash="$(sha256sum "$v3_root/data/local/.hapaneld-helper-manual-upgrade" | awk '{print $1}')"
  v3_live_identity="$(stat -c %d:%i "$v3_root/data/local/hapaneld-helper")"
  if bash "$INSTALLER" "$MOCK_TARGET" >"$TMP/v3-$custody_state-custody.out" 2>&1; then
    echo "installer unexpectedly rolled back retained PREPARED v3 state under $custody_state R1 custody" >&2
    exit 1
  fi
  grep -q 'APK-coupled helper replacement custody blocks standalone journal recovery' \
    "$TMP/v3-$custody_state-custody.out"
  [ "$(sha256sum "$v3_root/data/local/.hapaneld-helper-manual-upgrade" | awk '{print $1}')" = "$v3_marker_hash" ]
  [ "$(stat -c %d:%i "$v3_root/data/local/hapaneld-helper")" = "$v3_live_identity" ]
  [ "$(sha256sum "$v3_root/data/local/hapaneld-helper" | awk '{print $1}')" = "$v3_old_helper" ]
  ! grep -q '^ROLLBACK_PHASE=' "$v3_root/data/local/.hapaneld-helper-manual-upgrade"
  [ ! -s "$MOCK_STATE_DIR/real-mv.log" ]
done

prepare_v3_real_prepared
ln -s /missing-r1-unrelated-target "$v3_root/system/bin/hapaneld-helper"
v3_marker_hash="$(sha256sum "$v3_root/data/local/.hapaneld-helper-manual-upgrade" | awk '{print $1}')"
for retry in first retry; do
  if bash "$INSTALLER" "$MOCK_TARGET" >"$TMP/v3-broken-link-$retry.out" 2>&1; then
    echo "installer unexpectedly accepted a broken symlink as absent in retained PREPARED v3 state" >&2
    exit 1
  fi
  grep -q 'retained canonical helper-only journal could not be recovered safely' \
    "$TMP/v3-broken-link-$retry.out"
  [ "$(readlink "$v3_root/system/bin/hapaneld-helper")" = /missing-r1-unrelated-target ]
  [ "$(sha256sum "$v3_root/data/local/.hapaneld-helper-manual-upgrade" | awk '{print $1}')" = "$v3_marker_hash" ]
  ! grep -q '^ROLLBACK_PHASE=' "$v3_root/data/local/.hapaneld-helper-manual-upgrade"
  [ ! -s "$MOCK_STATE_DIR/real-mv.log" ]
done
unset MOCK_V3_REAL_FILESYSTEM
export MOCK_ADB_ROOT=0
rm -rf "$v3_root"
rm -f "$MOCK_STATE_DIR/manual-helper-transaction"

v1_root="$MOCK_STATE_DIR/v1-real"
mkdir -p "$v1_root/dev" "$v1_root/system/bin" "$v1_root/system/etc/init"
cp "$TMP/dist/arm64-v8a/hapaneld-helper" "$v1_root/system/bin/hapaneld-helper"
cat > "$v1_root/system/etc/init/hapaneld-helper.rc" <<'V1RC'
# ha-paneld root helper — boot-persistent control daemon (LED, screen backlight, buttons, sysctls).
#
# Installed to /system/etc/init/hapaneld-helper.rc (auto-imported by init at boot). Runs in the `su`
# domain so it can write the root-only sysfs nodes (sysfs_lights, backlight bl_power) on a
# userdebug panel — the same domain a manual `su 0 shell uses. `class main` auto-starts it during
# boot. See helper/install-daemon.sh and helper/README.md.
#
# DO NOT add `critical` — it causes a reboot-loop if the daemon crashes at boot (7-second cycle,
# recovery-mode only). Without it, Android init's built-in backoff disables the service after
# 4 rapid crashes rather than rebooting, which is the safe behaviour we want.
service hapaneld_helper /system/bin/hapaneld-helper
    class main
    user root
    group root
    seclabel u:r:su:s0
V1RC
[ "$(sha256sum "$v1_root/system/etc/init/hapaneld-helper.rc" | awk '{print $1}')" = \
  b42a66ff435a830390c7f04e66ffa252e3bf4027e68c72a29002df4886f8d4f4 ]
printf 'v1 prior helper\n' > "$v1_root/system/bin/hapaneld-helper.hapaneld-manual-recovery"
printf 'v1 prior registration\n' > "$v1_root/system/etc/init/hapaneld-helper.rc.hapaneld-manual-recovery"
v1_target_helper="$(sha256sum "$v1_root/system/bin/hapaneld-helper" | awk '{print $1}')"
v1_old_helper="$(sha256sum "$v1_root/system/bin/hapaneld-helper.hapaneld-manual-recovery" | awk '{print $1}')"
v1_old_rc="$(sha256sum "$v1_root/system/etc/init/hapaneld-helper.rc.hapaneld-manual-recovery" | awk '{print $1}')"
cat > "$v1_root/system/bin/.hapaneld-helper-manual-upgrade" <<EOF
JOURNAL_VERSION=1
JOURNAL_SCOPE=HELPER_ONLY
TARGET_BUILD_ID=$MOCK_HELPER_BUILD_ID
TARGET_HELPER_SHA256=$v1_target_helper
OLD_BIN=1
OLD_BIN_SHA256=$v1_old_helper
OLD_SERVICE=1
OLD_SERVICE_SHA256=$v1_old_rc
LEGACY_BIN=0
LEGACY_BIN_SHA256=-
LEGACY_SERVICE=0
LEGACY_SERVICE_SHA256=-
ALT_BIN=0
ALT_BIN_SHA256=-
ALT_SERVICE=0
ALT_SERVICE_SHA256=-
EOF
chmod 600 "$v1_root/system/bin/.hapaneld-helper-manual-upgrade"
: > "$MOCK_STATE_DIR/manual-helper-transaction"
export MOCK_V1_REAL_FILESYSTEM=1
export MOCK_V1_REAL_KIND=system
export MOCK_V1_CUT_PUBLICATION=1
export MOCK_ADB_ROOT=1
: > "$MOCK_CALL_LOG"
ln -s /missing-legacy-helper "$v1_root/system/bin/hapaneld-ledd"
v1_marker_hash="$(sha256sum "$v1_root/system/bin/.hapaneld-helper-manual-upgrade" | awk '{print $1}')"
for retry in first retry; do
  rm -f "$MOCK_STATE_DIR/real-mv.log"
  if bash "$INSTALLER" "$MOCK_TARGET" >"$TMP/v1-broken-link-$retry.out" 2>&1; then
    echo "installer unexpectedly accepted a broken symlink as absent in retained v1 state" >&2
    exit 1
  fi
  grep -q 'retained /system helper-only journal could not be recovered safely' \
    "$TMP/v1-broken-link-$retry.out"
  [ "$(readlink "$v1_root/system/bin/hapaneld-ledd")" = /missing-legacy-helper ]
  [ "$(sha256sum "$v1_root/system/bin/.hapaneld-helper-manual-upgrade" | awk '{print $1}')" = "$v1_marker_hash" ]
  ! grep -q '^ROLLBACK_PHASE=' "$v1_root/system/bin/.hapaneld-helper-manual-upgrade"
  [ ! -s "$MOCK_STATE_DIR/real-mv.log" ]
done
rm -f "$v1_root/system/bin/hapaneld-ledd"
rm -f "$MOCK_STATE_DIR/real-mv.log"
if bash "$INSTALLER" "$MOCK_TARGET" >"$TMP/v1-real-publication-cut.out" 2>&1; then
  echo "installer unexpectedly finalized a real v1 post-swap rollback cut during publication" >&2
  exit 1
fi
grep -q 'could not be recovered safely' "$TMP/v1-real-publication-cut.out" || {
  cat "$TMP/v1-real-publication-cut.out" >&2
  exit 1
}
grep -qx 'ROLLBACK_PHASE=PUBLISHING' "$v1_root/system/bin/.hapaneld-helper-manual-upgrade"
[ "$(sha256sum "$v1_root/system/bin/hapaneld-helper" | awk '{print $1}')" = "$v1_old_helper" ] || {
  ls -la "$v1_root/system/bin" >&2
  cat "$MOCK_STATE_DIR/real-mv.log" >&2
  cat "$TMP/v1-real-publication-cut.out" >&2
  exit 1
}
[ "$(sha256sum "$v1_root/system/etc/init/hapaneld-helper.rc" | awk '{print $1}')" = \
  b42a66ff435a830390c7f04e66ffa252e3bf4027e68c72a29002df4886f8d4f4 ]

: > "$MOCK_CALL_LOG"
bash "$INSTALLER" "$MOCK_TARGET" >"$TMP/v1-real-publication-retry.out" 2>&1
[ "$(sha256sum "$v1_root/system/bin/hapaneld-helper" | awk '{print $1}')" = "$v1_old_helper" ]
[ "$(sha256sum "$v1_root/system/etc/init/hapaneld-helper.rc" | awk '{print $1}')" = "$v1_old_rc" ]
[ ! -f "$v1_root/system/bin/.hapaneld-helper-manual-upgrade" ]
[ ! -f "$MOCK_STATE_DIR/manual-helper-transaction" ]
grep -q 'ROLLBACK_RESTARTED' "$MOCK_CALL_LOG"
grep -q 'ROLLBACK_FINALIZED' "$MOCK_CALL_LOG"
rm -rf "$v1_root"

v2_transaction=0123456789abcdef0123456789abcdef
mkdir -p "$v1_root/dev" "$v1_root/system/bin" "$v1_root/system/etc/init"
cp "$TMP/dist/arm64-v8a/hapaneld-helper" "$v1_root/system/bin/hapaneld-helper"
cp "$ROOT/helper/hapaneld-helper.rc" "$v1_root/system/etc/init/hapaneld-helper.rc"
printf 'v2 prior helper\n' > "$v1_root/system/bin/hapaneld-helper.hapaneld-manual-recovery"
printf 'v2 prior registration\n' > "$v1_root/system/etc/init/hapaneld-helper.rc.hapaneld-manual-recovery"
v2_target_helper="$(sha256sum "$v1_root/system/bin/hapaneld-helper" | awk '{print $1}')"
v2_target_rc="$(sha256sum "$v1_root/system/etc/init/hapaneld-helper.rc" | awk '{print $1}')"
v2_old_helper="$(sha256sum "$v1_root/system/bin/hapaneld-helper.hapaneld-manual-recovery" | awk '{print $1}')"
v2_old_rc="$(sha256sum "$v1_root/system/etc/init/hapaneld-helper.rc.hapaneld-manual-recovery" | awk '{print $1}')"
cat > "$v1_root/system/bin/.hapaneld-helper-manual-upgrade" <<EOF
JOURNAL_VERSION=2
JOURNAL_SCOPE=HELPER_ONLY
TRANSACTION_ID=$v2_transaction
TARGET_BUILD_ID=$MOCK_HELPER_BUILD_ID
TARGET_HELPER_SHA256=$v2_target_helper
TARGET_SERVICE_SHA256=$v2_target_rc
LEASE_BOOT_ID=00000000-0000-0000-0000-000000000000
LEASE_UNTIL_UPTIME=0
OLD_BIN=1
OLD_BIN_SHA256=$v2_old_helper
OLD_SERVICE=1
OLD_SERVICE_SHA256=$v2_old_rc
LEGACY_BIN=0
LEGACY_BIN_SHA256=-
LEGACY_SERVICE=0
LEGACY_SERVICE_SHA256=-
ALT_BIN=0
ALT_BIN_SHA256=-
ALT_SERVICE=0
ALT_SERVICE_SHA256=-
EOF
chmod 600 "$v1_root/system/bin/.hapaneld-helper-manual-upgrade"
ln -s /missing-v2-legacy-helper "$v1_root/system/bin/hapaneld-ledd"
: > "$MOCK_STATE_DIR/manual-helper-transaction"
v2_marker_hash="$(sha256sum "$v1_root/system/bin/.hapaneld-helper-manual-upgrade" | awk '{print $1}')"
for retry in first retry; do
  rm -f "$MOCK_STATE_DIR/real-mv.log"
  if bash "$INSTALLER" "$MOCK_TARGET" >"$TMP/v2-broken-link-$retry.out" 2>&1; then
    echo "installer unexpectedly accepted a broken symlink as absent in retained v2 state" >&2
    exit 1
  fi
  grep -q 'retained /system helper-only journal could not be recovered safely' \
    "$TMP/v2-broken-link-$retry.out"
  [ "$(readlink "$v1_root/system/bin/hapaneld-ledd")" = /missing-v2-legacy-helper ]
  [ "$(sha256sum "$v1_root/system/bin/.hapaneld-helper-manual-upgrade" | awk '{print $1}')" = "$v2_marker_hash" ]
  ! grep -q '^ROLLBACK_PHASE=' "$v1_root/system/bin/.hapaneld-helper-manual-upgrade"
  [ ! -s "$MOCK_STATE_DIR/real-mv.log" ]
done
rm -rf "$v1_root"
rm -f "$MOCK_STATE_DIR/manual-helper-transaction"

mkdir -p "$v1_root/dev" "$v1_root/data/adb/hapaneld" "$v1_root/data/adb/service.d"
cp "$TMP/dist/arm64-v8a/hapaneld-helper" "$v1_root/data/adb/hapaneld/hapaneld-helper"
cat > "$v1_root/data/adb/service.d/hapaneld-helper.sh" <<'V1SVC'
#!/system/bin/sh
while [ "$(getprop sys.boot_completed)" != "1" ]; do sleep 3; done
/system/bin/stop hapaneld_helper 2>/dev/null
/system/bin/stop hapaneld_ledd 2>/dev/null
/system/bin/pkill -x hapaneld-helper 2>/dev/null
/system/bin/pkill -x hapaneld-ledd 2>/dev/null
/data/adb/hapaneld/hapaneld-helper >/dev/null 2>&1 &
V1SVC
[ "$(sha256sum "$v1_root/data/adb/service.d/hapaneld-helper.sh" | awk '{print $1}')" = \
  60ff22aa9b38483cbffd95a653d804d0d9abf682e1b952e8b4519d5c0f3f9493 ]
printf 'v1 systemless prior helper\n' > "$v1_root/data/adb/hapaneld/hapaneld-helper.hapaneld-manual-recovery"
printf 'v1 systemless prior registration\n' > "$v1_root/data/adb/service.d/hapaneld-helper.sh.hapaneld-manual-recovery"
v1_target_helper="$(sha256sum "$v1_root/data/adb/hapaneld/hapaneld-helper" | awk '{print $1}')"
v1_old_helper="$(sha256sum "$v1_root/data/adb/hapaneld/hapaneld-helper.hapaneld-manual-recovery" | awk '{print $1}')"
v1_old_rc="$(sha256sum "$v1_root/data/adb/service.d/hapaneld-helper.sh.hapaneld-manual-recovery" | awk '{print $1}')"
cat > "$v1_root/data/adb/hapaneld/.helper-manual-upgrade.marker" <<EOF
JOURNAL_VERSION=1
JOURNAL_SCOPE=HELPER_ONLY
TARGET_BUILD_ID=$MOCK_HELPER_BUILD_ID
TARGET_HELPER_SHA256=$v1_target_helper
OLD_BIN=1
OLD_BIN_SHA256=$v1_old_helper
OLD_SERVICE=1
OLD_SERVICE_SHA256=$v1_old_rc
EOF
chmod 600 "$v1_root/data/adb/hapaneld/.helper-manual-upgrade.marker"
: > "$MOCK_STATE_DIR/manual-helper-transaction"
export MOCK_V1_REAL_KIND=systemless
: > "$MOCK_CALL_LOG"
if bash "$INSTALLER" "$MOCK_TARGET" >"$TMP/v1-systemless-real-publication-cut.out" 2>&1; then
  echo "installer unexpectedly finalized a real systemless v1 rollback cut during publication" >&2
  exit 1
fi
grep -q 'could not be recovered safely' "$TMP/v1-systemless-real-publication-cut.out" || {
  cat "$TMP/v1-systemless-real-publication-cut.out" >&2
  exit 1
}
grep -qx 'ROLLBACK_PHASE=PUBLISHING' "$v1_root/data/adb/hapaneld/.helper-manual-upgrade.marker"
[ "$(sha256sum "$v1_root/data/adb/hapaneld/hapaneld-helper" | awk '{print $1}')" = "$v1_old_helper" ]
[ "$(sha256sum "$v1_root/data/adb/service.d/hapaneld-helper.sh" | awk '{print $1}')" = \
  60ff22aa9b38483cbffd95a653d804d0d9abf682e1b952e8b4519d5c0f3f9493 ]

: > "$MOCK_CALL_LOG"
bash "$INSTALLER" "$MOCK_TARGET" >"$TMP/v1-systemless-real-publication-retry.out" 2>&1
[ "$(sha256sum "$v1_root/data/adb/hapaneld/hapaneld-helper" | awk '{print $1}')" = "$v1_old_helper" ]
[ "$(sha256sum "$v1_root/data/adb/service.d/hapaneld-helper.sh" | awk '{print $1}')" = "$v1_old_rc" ]
[ ! -f "$v1_root/data/adb/hapaneld/.helper-manual-upgrade.marker" ]
[ ! -f "$MOCK_STATE_DIR/manual-helper-transaction" ]
grep -q 'ROLLBACK_RESTARTED' "$MOCK_CALL_LOG"
grep -q 'ROLLBACK_FINALIZED' "$MOCK_CALL_LOG"
unset MOCK_V1_REAL_FILESYSTEM MOCK_V1_REAL_KIND MOCK_V1_CUT_PUBLICATION
export MOCK_ADB_ROOT=0
rm -rf "$v1_root"

for publication_version in v2 v3; do
  export MOCK_ROLLBACK_PUBLICATION_VERSION="$publication_version"
  case "$publication_version" in
    v2)
      export MOCK_MANUAL_TRANSACTION_STATE=stale
      export MOCK_MANUAL_TRANSACTION_ID=0123456789abcdef0123456789abcdef
      export MOCK_MANUAL_TARGET_SERVICE_SHA256="$(sha256sum "$ROOT/helper/hapaneld-helper.rc" | awk '{print $1}')"
      ;;
    v3)
      export MOCK_MANUAL_TRANSACTION_STATE=stale_v3
      export MOCK_MANUAL_TRANSACTION_ID=0123456789abcdef0123456789abcdef
      export MOCK_MANUAL_TARGET_SERVICE_SHA256="$(sha256sum "$ROOT/helper/hapaneld-helper.rc" | awk '{print $1}')"
      ;;
  esac
  : > "$MOCK_STATE_DIR/manual-helper-transaction"
  rm -f "$MOCK_STATE_DIR/publication-cut-$publication_version"
  : > "$MOCK_CALL_LOG"
  if bash "$INSTALLER" "$MOCK_TARGET" >"$TMP/$publication_version-publication-cut.out" 2>&1; then
    echo "installer unexpectedly finalized a $publication_version rollback cut during publication" >&2
    cat "$TMP/$publication_version-publication-cut.out" >&2
    tail -80 "$MOCK_CALL_LOG" >&2
    exit 1
  fi
  grep -q 'could not be recovered safely' "$TMP/$publication_version-publication-cut.out"
  [ -f "$MOCK_STATE_DIR/manual-helper-transaction" ]
  [ -f "$MOCK_STATE_DIR/publication-cut-$publication_version" ]
  ! grep -q 'echo ROLLBACK_FINALIZED' "$MOCK_CALL_LOG"

  : > "$MOCK_CALL_LOG"
  bash "$INSTALLER" "$MOCK_TARGET" >"$TMP/$publication_version-publication-retry.out" 2>&1
  grep -q '\.restore' "$MOCK_CALL_LOG"
  grep -q 'ROLLBACK_PHASE=PUBLISHING' "$MOCK_CALL_LOG"
  grep -q 'sync_path' "$MOCK_CALL_LOG"
  grep -q 'mv -f' "$MOCK_CALL_LOG"
  grep -q 'ROLLBACK_RESTARTED' "$MOCK_CALL_LOG"
  grep -q 'echo ROLLBACK_FINALIZED' "$MOCK_CALL_LOG"
  [ ! -f "$MOCK_STATE_DIR/manual-helper-transaction" ]
done
unset MOCK_ROLLBACK_PUBLICATION_VERSION MOCK_MANUAL_TRANSACTION_STATE
rm -f "$MOCK_STATE_DIR"/publication-cut-v2 "$MOCK_STATE_DIR"/publication-cut-v3
export MOCK_MANUAL_TRANSACTION_ID=0123456789abcdef0123456789abcdef
export MOCK_MANUAL_TARGET_SERVICE_SHA256="$(sha256sum "$ROOT/helper/hapaneld-helper.rc" | awk '{print $1}')"

export MOCK_MANUAL_COMMIT_LIVE_STATE=UNKNOWN
: > "$MOCK_CALL_LOG"
if bash "$INSTALLER" "$MOCK_TARGET" >"$TMP/commit-unknown.out" 2>&1; then
  echo "installer unexpectedly committed after its live persistence state changed" >&2
  exit 1
fi
grep -q 'durable commit point could not be confirmed' "$TMP/commit-unknown.out" || {
  cat "$TMP/commit-unknown.out" >&2
  exit 1
}
[ -f "$MOCK_STATE_DIR/manual-helper-transaction" ]
grep -q 'echo COMMIT_OK' "$MOCK_CALL_LOG"
unset MOCK_MANUAL_COMMIT_LIVE_STATE
rm -f "$MOCK_STATE_DIR/manual-helper-transaction"

export MOCK_SYSTEM_WRITABLE=0
export MOCK_SYSTEMLESS_RUNNER=1
: > "$MOCK_CALL_LOG"
bash "$INSTALLER" "$MOCK_TARGET" >"$TMP/systemless.out" 2>&1
grep -q 'verified service.d registration' "$TMP/systemless.out"
grep -q '/data/local/hapaneld-helper --supervise' "$MOCK_CALL_LOG"
grep -q '/data/adb/service.d/hapaneld-helper.sh.manual-' "$MOCK_CALL_LOG"
[ ! -f "$MOCK_STATE_DIR/manual-helper-transaction" ]
export MOCK_SYSTEM_WRITABLE=1

export MOCK_APP_REPLACEMENT_INTERVAL=initial
: > "$MOCK_CALL_LOG"
if bash "$INSTALLER" "$MOCK_TARGET" >"$TMP/initial-app-hold.out" 2>&1; then
  echo "installer unexpectedly continued past fixed app custody during initial recovery inspection" >&2
  exit 1
fi
grep -q 'app-managed root-helper replacement custody blocks standalone installation' "$TMP/initial-app-hold.out"
[ -f "$MOCK_STATE_DIR/app-hold-initial" ]
[ ! -f "$MOCK_STATE_DIR/manual-helper-transaction" ]
unset MOCK_APP_REPLACEMENT_INTERVAL
rm -f "$MOCK_STATE_DIR/app-hold-initial"

export MOCK_APP_REPLACEMENT_INTERVAL=pre_stage
: > "$MOCK_CALL_LOG"
if bash "$INSTALLER" "$MOCK_TARGET" >"$TMP/pre-stage-app-hold.out" 2>&1; then
  echo "installer unexpectedly continued after fixed app custody appeared before staging" >&2
  exit 1
fi
grep -q 'app-managed root-helper replacement custody appeared while standalone installation was preparing' "$TMP/pre-stage-app-hold.out"
[ -f "$MOCK_STATE_DIR/app-hold-pre-stage" ]
[ ! -f "$MOCK_STATE_DIR/manual-helper-transaction" ]
! grep -q 'ROLLBACK_' "$MOCK_CALL_LOG"
unset MOCK_APP_REPLACEMENT_INTERVAL
rm -f "$MOCK_STATE_DIR/app-hold-pre-stage"

export MOCK_REPLACEMENT_SAFE=armed
: > "$MOCK_CALL_LOG"
if bash "$INSTALLER" "$MOCK_TARGET" >"$TMP/guard-armed.out" 2>&1; then
  echo "installer unexpectedly renamed the live helper while Guard DB authority was armed" >&2
  exit 1
fi
grep -q 'Guard DB authority is armed; the prior helper topology was restored' "$TMP/guard-armed.out"
grep -q -- '--replacement-safe' "$MOCK_CALL_LOG"
! grep -q 'mv -f .* /data/local/hapaneld-helper' "$MOCK_CALL_LOG"
grep -q 'ROLLBACK_RESTARTED' "$MOCK_CALL_LOG"
grep -q 'echo ROLLBACK_FINALIZED' "$MOCK_CALL_LOG"
[ ! -f "$MOCK_STATE_DIR/manual-helper-transaction" ]
unset MOCK_REPLACEMENT_SAFE

export MOCK_REPLACEMENT_SAFE=r1_custody
: > "$MOCK_CALL_LOG"
if bash "$INSTALLER" "$MOCK_TARGET" >"$TMP/replacement-custody.out" 2>&1; then
  echo "installer unexpectedly rolled back across active R1 custody" >&2
  exit 1
fi
grep -q 'APK-coupled helper replacement custody refused standalone helper replacement' "$TMP/replacement-custody.out"
grep -q -- '--replacement-safe' "$MOCK_CALL_LOG"
! grep -q 'echo ROLLBACK_FINALIZED' "$MOCK_CALL_LOG"
[ -f "$MOCK_STATE_DIR/manual-helper-transaction" ]
unset MOCK_REPLACEMENT_SAFE
rm -f "$MOCK_STATE_DIR/manual-helper-transaction"

export MOCK_APP_REPLACEMENT_INTERVAL=inflight
: > "$MOCK_CALL_LOG"
if bash "$INSTALLER" "$MOCK_TARGET" >"$TMP/inflight-r1.out" 2>&1; then
  echo "installer unexpectedly rolled back across an in-flight APK-coupled helper replacement" >&2
  exit 1
fi
grep -q 'APK-coupled helper replacement custody refused standalone helper replacement' "$TMP/inflight-r1.out"
grep -q 'recorded_live LIVE_CANONICAL' "$MOCK_CALL_LOG"
grep -q -- '--replacement-safe' "$MOCK_CALL_LOG"
! grep -q 'echo ROLLBACK_FINALIZED' "$MOCK_CALL_LOG"
[ -f "$MOCK_STATE_DIR/manual-helper-transaction" ]
unset MOCK_APP_REPLACEMENT_INTERVAL
rm -f "$MOCK_STATE_DIR/manual-helper-transaction"

export MOCK_APP_REPLACEMENT_INTERVAL=slow_crash
: > "$MOCK_CALL_LOG"
if bash "$INSTALLER" "$MOCK_TARGET" >"$TMP/slow-r1-rollback.out" 2>&1; then
  echo "installer unexpectedly restored a stale v3 snapshot over a slow app R1 swap" >&2
  exit 1
fi
grep -q 'rollback could not be verified' "$TMP/slow-r1-rollback.out"
grep -q 'R1_RETIREMENT_TIMEOUT' "$TMP/slow-r1-rollback.out"
grep -q 'ROLLBACK_UNKNOWN' "$MOCK_CALL_LOG"
! grep -q 'echo ROLLBACK_FINALIZED' "$MOCK_CALL_LOG"
[ -f "$MOCK_STATE_DIR/manual-helper-transaction" ]
unset MOCK_APP_REPLACEMENT_INTERVAL
rm -f "$MOCK_STATE_DIR/manual-helper-transaction"

export MOCK_POST_RETIRE_LIVE_STATE=changed
: > "$MOCK_CALL_LOG"
if bash "$INSTALLER" "$MOCK_TARGET" >"$TMP/post-retire-race.out" 2>&1; then
  echo "installer unexpectedly overwrote a helper replacement completed after its snapshot" >&2
  exit 1
fi
grep -q 'live helper topology changed after the standalone snapshot' "$TMP/post-retire-race.out"
grep -q 'LIVE_IDENTITY_CHANGED' "$MOCK_CALL_LOG"
! grep -q 'echo ROLLBACK_FINALIZED' "$MOCK_CALL_LOG"
[ -f "$MOCK_STATE_DIR/manual-helper-transaction" ]
unset MOCK_POST_RETIRE_LIVE_STATE
rm -f "$MOCK_STATE_DIR/manual-helper-transaction"

echo "standalone installer transaction tests passed"
