#!/usr/bin/env bash
# Black-box regression tests for the novice-facing provisioning contract.
# All adb and HTTP interactions are faked; this script never contacts a panel or the network.
set -u

PROVISION_TEST_SCOPE="${PROVISION_TEST_SCOPE:-all}"
case "$PROVISION_TEST_SCOPE" in
  db|backup|publication|core|all|\
  shard-database-host|shard-database-runtime|\
  shard-install-export|shard-install-runtime|shard-helper-transaction|\
  shard-release-integrity|shard-renderer-seeding|shard-install-finish|\
  shard-backup|shard-publication|shard-database-authority|shard-fleet-installer|\
  shard-host-reclamation|shard-git-bash) ;;
  *) echo "unknown PROVISION_TEST_SCOPE: $PROVISION_TEST_SCOPE" >&2; exit 2 ;;
esac

provision_scope_is() {
  local candidate
  for candidate in "$@"; do
    [ "$PROVISION_TEST_SCOPE" != "$candidate" ] || return 0
  done
  return 1
}

# A killed process can stay visible to `kill -0` until its reaper collects it, as a zombie or for a moment
# while an init process (a container's --init, or systemd) reaps asynchronously. Wait briefly for every
# PID, or "-PGID" for a whole group, to be gone, and count a zombie as gone.
processes_gone() {
  local deadline=$((SECONDS + 5)) id state alive
  while :; do
    alive=0
    for id in "$@"; do
      kill -0 -- "$id" 2>/dev/null || continue
      if [ "${id#-}" = "$id" ]; then
        state=$(sed -E 's/^.*\) ([A-Za-z]).*$/\1/' "/proc/$id/stat" 2>/dev/null || true)
        [ "$state" = Z ] && continue
      fi
      alive=1
    done
    [ "$alive" = 0 ] && return 0
    if [ "$SECONDS" -ge "$deadline" ]; then
      # Name what survived, so a rare failure on a shared builder says which process and in what state.
      for id in "$@"; do
        kill -0 -- "$id" 2>/dev/null || continue
        printf '# still running after 5s: %s %s\n' "$id" \
          "$(ps -o pid=,pgid=,stat=,etimes=,args= -p "${id#-}" 2>/dev/null | head -1 || true)"
      done
      return 1
    fi
    sleep 0.1
  done
}

case "$PROVISION_TEST_SCOPE" in shard-*) PROVISION_TEST_INTERNAL_SHARD=1 ;; *) PROVISION_TEST_INTERNAL_SHARD=0 ;; esac

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
PROVISION="$ROOT/scripts/provision.sh"
UPDATE_FLEET="$ROOT/scripts/update-fleet.sh"
FIXTURES="$ROOT/scripts/tests/fixtures"
TMP="$(mktemp -d)"
ACTIVE_PUBLICATION_PGID=""
readonly PROVISION_TEST_OWNER_BASHPID="$BASHPID"
cleanup_provision_test() {
  local status=$? pgid="${ACTIVE_PUBLICATION_PGID:-}" attempt=0
  [ "$BASHPID" = "$PROVISION_TEST_OWNER_BASHPID" ] || return "$status"
  trap - EXIT
  if [ -n "$pgid" ]; then
    kill -TERM -- "-$pgid" 2>/dev/null || true
    while kill -0 -- "-$pgid" 2>/dev/null && [ "$attempt" -lt 40 ]; do
      /bin/sleep 0.05
      attempt=$((attempt + 1))
    done
    kill -KILL -- "-$pgid" 2>/dev/null || true
    wait "$pgid" 2>/dev/null || true
    ACTIVE_PUBLICATION_PGID=""
  fi
  rm -rf "$TMP"
  exit "$status"
}
trap cleanup_provision_test EXIT

export MOCK_TARGET="panel.test:5555"
export MOCK_CALL_LOG="$TMP/calls.log"
export PROVISION_TEST_LEGACY_HELPER_PROBE="$FIXTURES/helper-probe"
HAPANELD_HELPER_PROBE_WITH_GUARD="$TMP/helper-probe-with-guard"
cat > "$HAPANELD_HELPER_PROBE_WITH_GUARD" <<'HELPER_PROBE_WITH_GUARD'
#!/usr/bin/env bash
set -u
case "${1:-}" in
  GUARDCAPS)
    printf 'helper-probe GUARDCAPS\n' >> "${MOCK_CALL_LOG:?MOCK_CALL_LOG is required}"
    [ "${MOCK_HELPER_START:-ok}" = ok ] || exit 1
    printf 'OK GUARDCAPS 1 PREPARE DEFINE STREAM ACTION HEALTH REFUSAL STATUS EVIDENCE CANCEL RETIRE JOURNAL AUTONOMOUS SUPERVISED TERMINAL_RETIRE\n'
    ;;
  GUARDSTATUS)
    printf 'helper-probe GUARDSTATUS\n' >> "${MOCK_CALL_LOG:?MOCK_CALL_LOG is required}"
    [ "${MOCK_HELPER_START:-ok}" = ok ] || exit 1
    printf 'OK GUARDSTATUS 0 EMPTY NONE NONE NONE NONE 0 0 0 NONE NONE 0 0\n'
    ;;
  *) exec "${PROVISION_TEST_LEGACY_HELPER_PROBE:?}" "$@" ;;
esac
HELPER_PROBE_WITH_GUARD
chmod 755 "$HAPANELD_HELPER_PROBE_WITH_GUARD"
export HAPANELD_HELPER_PROBE="$HAPANELD_HELPER_PROBE_WITH_GUARD"
export MOCK_HELPER_BUILD_ID="$(PATH=/usr/bin:/bin "$ROOT/helper/source-id.sh")"
export PROVISION_TEST_CURL="$FIXTURES/curl"
export PROVISION_TEST_ADB_FIXTURE="$FIXTURES/adb"
export PROVISION_TEST_STATE_DIR="$TMP"
export MOCK_STATE_DIR="$TMP"
export HAPANELD_HOST_SQLITE3="$(command -v sqlite3)"

# A private time-zone database, so the host/panel time-zone advisory is decided by these files rather
# than by whatever tzdata the machine running the suite happens to carry. That is not a convenience:
# Debian and Ubuntu moved the "backward" links into a separate tzdata-legacy package, so on this very
# container Asia/Calcutta does not exist and the alias case would be untestable against the real
# database — while a machine that does have it would silently take a different branch. TZDIR is
# libc's own override, which is why the production code honours it.
#
# The bytes are arbitrary because the comparison is byte identity and never parses TZif: two names
# are the same zone precisely when the database gives them the same file, which is how it records a
# link. Asia/Calcutta is therefore written as a copy of Asia/Kolkata, and Etc/UTC is left OUT
# altogether so a well-formed name the database cannot resolve stays expressible.
MOCK_ZONEINFO="$TMP/zoneinfo"
mkdir -p "$MOCK_ZONEINFO/Europe" "$MOCK_ZONEINFO/Asia"
printf 'TZif2 fixture utcoffset=+0000 dst=eu-summer\n' > "$MOCK_ZONEINFO/Europe/London"
printf 'TZif2 fixture utcoffset=+0530 dst=none\n' > "$MOCK_ZONEINFO/Asia/Kolkata"
cp "$MOCK_ZONEINFO/Asia/Kolkata" "$MOCK_ZONEINFO/Asia/Calcutta"
printf 'TZif2 fixture utcoffset=+0800 dst=none\n' > "$MOCK_ZONEINFO/Asia/Shanghai"
export MOCK_ZONEINFO

# An empty stand-in for /etc, so the host zone comes from the pinned TZ and nothing on this machine.
MOCK_HOST_ETC="$TMP/host-etc"
mkdir -p "$MOCK_HOST_ETC"
export MOCK_HOST_ETC

# Extend the shared adb fixture with targeted database-backup mutations. This must be an executable
# (not an exported function) because production deadlines launch adb via setsid(1).
ADB_WRAPPER_DIR="$TMP/adb-wrapper"
mkdir -p "$ADB_WRAPPER_DIR"
cat > "$ADB_WRAPPER_DIR/adb" <<'ADB_WRAPPER'
#!/usr/bin/env bash
set -u
case "${MOCK_DB_TXN:-ok}" in
  source_not_regular|source_directory)
  case "$*" in
    *'sh /data/local/tmp/.hapaneld-db-txn.'*-script*)
      source_db="$PROVISION_TEST_STATE_DIR/db-txn-sandbox/data/data/io.github.maxlyth.hapaneld/databases/ha-paneld.db"
      if [ -f "$source_db" ] && [ ! -L "$source_db" ]; then
        rm -f "$source_db"
        if [ "${MOCK_DB_TXN:-ok}" = source_directory ]; then
          mkdir "$source_db"
        else
          ln -s "$source_db.missing" "$source_db"
        fi
      fi
      ;;
  esac
  ;;
esac
case "${MOCK_DB_TXN:-ok}" in
  manifest_missing_bytes|manifest_missing_rows|manifest_missing_schema|manifest_missing_provenance|manifest_missing_integrity|manifest_missing_sqlite|panel_digest_invalid|panel_digest_mismatch)
    case "$*" in
      *'sh /data/local/tmp/.hapaneld-db-txn.'*-script*)
        output="$("$PROVISION_TEST_ADB_FIXTURE" "$@")"; status=$?
        case "${MOCK_DB_TXN:-ok}" in
          manifest_missing_bytes) output="$(printf '%s\n' "$output" | sed '/^MF_BYTES=/d')" ;;
          manifest_missing_rows) output="$(printf '%s\n' "$output" | sed '/^MF_ROWS=/d')" ;;
          manifest_missing_schema) output="$(printf '%s\n' "$output" | sed '/^MF_USER_VERSION=/d')" ;;
          manifest_missing_provenance) output="$(printf '%s\n' "$output" | sed '/^MF_VCODE=/d')" ;;
          manifest_missing_integrity) output="$(printf '%s\n' "$output" | sed '/^MF_INTEGRITY=/d')" ;;
          manifest_missing_sqlite) output="$(printf '%s\n' "$output" | sed '/^MF_SQLITE=/d')" ;;
          panel_digest_invalid) output="$(printf '%s\n' "$output" | sed 's/^MF_SHA256=.*/MF_SHA256=not-a-digest/')" ;;
          panel_digest_mismatch) output="$(printf '%s\n' "$output" | sed "s/^MF_SHA256=.*/MF_SHA256=$(printf '%064d' 0)/")" ;;
        esac
        [ -z "$output" ] || printf '%s\n' "$output"
        exit "$status"
        ;;
    esac
    ;;
esac
exec "$PROVISION_TEST_ADB_FIXTURE" "$@"
ADB_WRAPPER
chmod 755 "$ADB_WRAPPER_DIR/adb"

# Git for Windows emulation, used only by the Git Bash section. `fixtures/msys-adb` models the MSYS
# runtime's POSIX-to-Windows argument rewrite — the one that made `adb push … /data/local/tmp/…`
# stage nothing on a reporter's panel (#24) — and records the argv adb.exe would have received before
# delegating to the ordinary fixture. Its emulated installation root is a real directory holding the
# host filesystem roots and deliberately none of the Android ones taken from the provisioner's own
# exclusion list, so a converted HOST path still resolves to its file while a converted DEVICE path
# does not. That asymmetry is what lets a Linux runner tell the two directions apart.
MSYS_ROOT="$TMP/msys-root"
MSYS_BIN_DIR="$TMP/msys-bin"
MSYS_ARGV_LOG="$TMP/msys-argv.log"
mkdir -p "$MSYS_ROOT" "$MSYS_BIN_DIR"
MSYS_EXCL="$(grep -m1 '^ADB_MSYS_ARG_CONV_EXCL=' "$PROVISION")"
MSYS_EXCL="${MSYS_EXCL#*=}"
MSYS_EXCL="${MSYS_EXCL#\'}"
MSYS_EXCL="${MSYS_EXCL%\'}"
for msys_entry in /*; do
  msys_base="${msys_entry#/}"
  case ";$MSYS_EXCL;" in
    *";/$msys_base;"*) continue ;;
  esac
  ln -s "$msys_entry" "$MSYS_ROOT/$msys_base" 2>/dev/null || true
done
cp "$FIXTURES/msys-adb" "$MSYS_BIN_DIR/adb"
chmod 755 "$MSYS_BIN_DIR/adb"

export PATH="$FIXTURES:/usr/bin:/bin"

# The checked-in curl fixture predates Hardened mode. Keep its ordinary behavior intact while
# modelling the successful HTTP 202 transport response returned for protected operations awaiting
# local approval. Bash exports this function to the provisioner subprocess in place of curl(1).
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
    *'/api/v1/config/import'*)
      if [ "${MOCK_RESTORE:-ok}" != ok ]; then
        if [ "$MOCK_RESTORE" = timeout ]; then
          command "$PROVISION_TEST_CURL" "$@"
          return $?
        fi
        case "$MOCK_RESTORE" in
          approval)
            printf '{"ok":false,"error":"approval-required","approval_id":"import-test"}\n202'
            ;;
          malformed-approval)
            printf '{"ok":false,"error":"unexpected"}\n202'
            ;;
          unexpected-2xx)
            printf '{"status":"created"}\n201'
            ;;
        esac
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
MOCK_HELPER_DIST="$TMP/helper-dist"
mkdir -p "$MOCK_HELPER_DIST/armeabi-v7a" "$MOCK_HELPER_DIST/arm64-v8a"
printf 'mock arm helper\nBUILDID %s\n' "$MOCK_HELPER_BUILD_ID" > "$MOCK_HELPER_DIST/armeabi-v7a/hapaneld-helper"
printf 'mock arm64 helper\nBUILDID %s\n' "$MOCK_HELPER_BUILD_ID" > "$MOCK_HELPER_DIST/arm64-v8a/hapaneld-helper"
export HAPANELD_HELPER_DIST_DIR="$MOCK_HELPER_DIST"

passes=0
failures=0
LAST_OUTPUT=""
LAST_STATUS=0

run_provision() {
  local unsigned_ack=()
  local provision_path="$PATH"
  case "${MOCK_DB_TXN:-ok}" in
    source_not_regular|source_directory|manifest_missing_*|panel_digest_invalid|panel_digest_mismatch)
      provision_path="$ADB_WRAPPER_DIR:$provision_path" ;;
  esac
  [ "${MOCK_MSYS_PATHCONV:-0}" != 1 ] || provision_path="$MSYS_BIN_DIR:$provision_path"
  : > "$MSYS_ARGV_LOG"
  [ "${RUN_UNSIGNED_ACK:-1}" != 1 ] || unsigned_ack=(--allow-unsigned-helper)
  : > "$MOCK_CALL_LOG"
  rm -f "$TMP/diag-attempts" "$TMP/config-schema-probes" "$TMP"/write-settings-granted* "$TMP/accessibility-services" "$TMP/accessibility-enabled"
  # Runtime-permission grant state is per-run for the same reason: left behind, a run that never
  # granted anything would still verify green off the previous run's grant.
  rm -f "$TMP"/record-audio-granted* "$TMP"/post-notifications-granted*
  # The slow-health probe counter is per-run state; leaving it behind made one test's outcome
  # depend on how many health probes an earlier test happened to make.
  rm -f "$TMP/plan-attempts" "$TMP/storage-status-attempts" "$TMP/health-probes"
  rm -f "$TMP/upgrade-release-attempts" "$TMP/installed-apk-signer-reads"
  rm -f "$TMP/stale-helper-transaction" "$TMP/active-helper-transaction"
  rm -f "$TMP/package-stopped" "$TMP/apk-install-attempted" "$TMP/pm-probe-count"
  rm -f "$TMP/host-db-observation-count" "$TMP/installer-db-observation-count" \
    "$TMP/package-data-cleared" "$TMP/candidate-contract-read-count" \
    "$TMP/candidate-apk-path" \
    "$TMP/helper-lease-observation-count" "$TMP/reset-package-relaunched" \
    "$TMP/reset-database-recreated" "$TMP/reset-package-restopped"
  if [ "${MOCK_STALE_TRANSACTION:-0}" = 1 ]; then : > "$TMP/stale-helper-transaction"; fi
  # Every run used to fabricate a previously-installed package, which made a genuine first
  # installation inexpressible — the reason no test caught that a failed first install strands the
  # panel. MOCK_NO_INSTALLED_PACKAGE=1 models a truly clean panel: nothing installed at all.
  rm -f "$TMP/installed-apk"
  if [ "${MOCK_NO_INSTALLED_PACKAGE:-0}" != 1 ]; then
    if [ -n "${MOCK_INSTALLED_APK_SOURCE:-}" ]; then
      cp "$MOCK_INSTALLED_APK_SOURCE" "$TMP/installed-apk"
    else
      printf 'previous installed apk\n' > "$TMP/installed-apk"
    fi
  fi
  LAST_OUTPUT="$TMP/output.txt"
  MOCK_APKSIGNER_RUNS="${MOCK_APKSIGNER_RUNS:-1}" \
  MOCK_HEALTH="${MOCK_HEALTH:-ok}" \
  MOCK_HEALTH_READY_AFTER="${MOCK_HEALTH_READY_AFTER:-3}" \
  MOCK_HEALTH_HANG_SECONDS="${MOCK_HEALTH_HANG_SECONDS:-3}" \
  MOCK_HEALTH_HANG_PID_FILE="${MOCK_HEALTH_HANG_PID_FILE:-}" \
  MOCK_HEALTH_HANG_DONE_FILE="${MOCK_HEALTH_HANG_DONE_FILE:-}" \
  APP_LAUNCH_PROBE_SECONDS="${APP_LAUNCH_PROBE_SECONDS:-1}" \
  APP_HEALTH_TIMEOUT_SECONDS="${APP_HEALTH_TIMEOUT_SECONDS:-3}" \
  MOCK_STOPPED_STATE="${MOCK_STOPPED_STATE:-0}" \
  MOCK_LAUNCHER_START="${MOCK_LAUNCHER_START:-ok}" \
  MOCK_LAUNCHER_PID_FILE="${MOCK_LAUNCHER_PID_FILE:-}" \
  MOCK_DIRECT_START="${MOCK_DIRECT_START:-ok}" \
  MOCK_STORAGE_HEALTH="${MOCK_STORAGE_HEALTH:-healthy}" \
  MOCK_POWER_SAFETY="${MOCK_POWER_SAFETY:-safe}" \
  MOCK_VERIFY="${MOCK_VERIFY:-ok}" \
  MOCK_EXPORT="${MOCK_EXPORT:-ok}" \
  MOCK_CONFIG="${MOCK_CONFIG:-ok}" \
  MOCK_RESTORE="${MOCK_RESTORE:-ok}" \
  MOCK_ADB_STATE="${MOCK_ADB_STATE:-device}" \
  MOCK_HA_LOGIN="${MOCK_HA_LOGIN:-ok}" \
  MOCK_HA_TOKEN="${MOCK_HA_TOKEN:-ok}" \
  MOCK_PLAN="${MOCK_PLAN:-ok}" \
  MOCK_SETUP="${MOCK_SETUP:-complete}" \
  MOCK_PM_CLEAR="${MOCK_PM_CLEAR:-ok}" \
  MOCK_RELAUNCH_AFTER_PM_CLEAR="${MOCK_RELAUNCH_AFTER_PM_CLEAR:-0}" \
  MOCK_RESTOP_AFTER_RELAUNCH="${MOCK_RESTOP_AFTER_RELAUNCH:-0}" \
  MOCK_INSTALLED_VCODE="${MOCK_INSTALLED_VCODE:-513}" \
  MOCK_DATA_CAPACITY="${MOCK_DATA_CAPACITY:-valid}" \
  MOCK_DATA_AVAIL_KB="${MOCK_DATA_AVAIL_KB:-1048576}" \
  MOCK_DB_TXN="${MOCK_DB_TXN:-ok}" \
  MOCK_UPGRADE_PREPARE="${MOCK_UPGRADE_PREPARE:-unsupported}" \
  MOCK_UPGRADE_PREPARE_PID_FILE="${MOCK_UPGRADE_PREPARE_PID_FILE:-}" \
  MOCK_UPGRADE_PREPARE_BLOCK_SECONDS="${MOCK_UPGRADE_PREPARE_BLOCK_SECONDS:-30}" \
  MOCK_UPGRADE_RELEASE="${MOCK_UPGRADE_RELEASE:-ok}" \
  MOCK_DIRECT_COPY="${MOCK_DIRECT_COPY:-ok}" \
  MOCK_DIRECT_COPY_PID_FILE="${MOCK_DIRECT_COPY_PID_FILE:-}" \
  MOCK_DIRECT_COPY_BLOCK_SECONDS="${MOCK_DIRECT_COPY_BLOCK_SECONDS:-30}" \
  MOCK_PM_PATH="${MOCK_PM_PATH:-ok}" \
  MOCK_PM_PATH_PID_FILE="${MOCK_PM_PATH_PID_FILE:-}" \
  MOCK_PM_LIVENESS="${MOCK_PM_LIVENESS:-ok}" \
  MOCK_PM_LIVENESS_PID_FILE="${MOCK_PM_LIVENESS_PID_FILE:-}" \
  MOCK_PM_PROBE="${MOCK_PM_PROBE:-ok}" \
  MOCK_PM_VANISH_AFTER="${MOCK_PM_VANISH_AFTER:-}" \
  MOCK_PM_TARGET_RC="${MOCK_PM_TARGET_RC:-}" \
  MOCK_PM_LIVE_RC="${MOCK_PM_LIVE_RC:-}" \
  MOCK_PM_PROBE_PID_FILE="${MOCK_PM_PROBE_PID_FILE:-}" \
  MOCK_NO_INSTALLED_PACKAGE="${MOCK_NO_INSTALLED_PACKAGE:-0}" \
  MOCK_PM_UNINSTALLED_RECORD="${MOCK_PM_UNINSTALLED_RECORD:-absent}" \
  MOCK_DB_CLEANUP="${MOCK_DB_CLEANUP:-ok}" \
  MOCK_DB_DEVICE_ROWS="${MOCK_DB_DEVICE_ROWS:-7}" \
  MOCK_DB_DEVICE_USER_VERSION="${MOCK_DB_DEVICE_USER_VERSION:-9}" \
  MOCK_DB_DEVICE_VCODE="${MOCK_DB_DEVICE_VCODE:-513}" \
  MOCK_DB_CANDIDATE_CONTRACT="${MOCK_DB_CANDIDATE_CONTRACT:-hapaneld-db:v1:ha-paneld.db:1:14}" \
  MOCK_DB_CANDIDATE_DUPLICATE="${MOCK_DB_CANDIDATE_DUPLICATE:-0}" \
  MOCK_DB_METADATA_SCOPE="${MOCK_DB_METADATA_SCOPE:-application}" \
  MOCK_DB_APPLICATION_DUPLICATE="${MOCK_DB_APPLICATION_DUPLICATE:-0}" \
  MOCK_SWAP_APK_AFTER_CONTRACT="${MOCK_SWAP_APK_AFTER_CONTRACT:-0}" \
  MOCK_SWAP_APK_AFTER_CONTRACT_OBSERVATION="${MOCK_SWAP_APK_AFTER_CONTRACT_OBSERVATION:-}" \
  MOCK_SWAP_APK_AFTER_FINAL_GATE_LEASE="${MOCK_SWAP_APK_AFTER_FINAL_GATE_LEASE:-0}" \
  MOCK_HOST_DB_OBSERVATION="${MOCK_HOST_DB_OBSERVATION:-ok}" \
  MOCK_HOST_DB_PRIMARY="${MOCK_HOST_DB_PRIMARY:-}" \
  MOCK_HOST_DB_RECOVERY="${MOCK_HOST_DB_RECOVERY:-none}" \
  MOCK_HOST_DB_RETAINED="${MOCK_HOST_DB_RETAINED:-}" \
  MOCK_HOST_DB_INVENTORY="${MOCK_HOST_DB_INVENTORY:-}" \
  MOCK_HOST_DB_INVENTORY_FINGERPRINT="${MOCK_HOST_DB_INVENTORY_FINGERPRINT:-}" \
  MOCK_HOST_DB_PRIMARY_FINGERPRINT="${MOCK_HOST_DB_PRIMARY_FINGERPRINT:-}" \
  MOCK_HOST_DB_PRIMARY_AFTER_FIRST="${MOCK_HOST_DB_PRIMARY_AFTER_FIRST:-}" \
  MOCK_HOST_DB_PRIMARY_FINGERPRINT_AFTER_FIRST="${MOCK_HOST_DB_PRIMARY_FINGERPRINT_AFTER_FIRST:-}" \
  MOCK_HOST_DB_RECOVERY_AFTER_FIRST="${MOCK_HOST_DB_RECOVERY_AFTER_FIRST:-}" \
  MOCK_HOST_DB_RETAINED_AFTER_FIRST="${MOCK_HOST_DB_RETAINED_AFTER_FIRST:-}" \
  MOCK_HOST_DB_INVENTORY_AFTER_FIRST="${MOCK_HOST_DB_INVENTORY_AFTER_FIRST:-}" \
  MOCK_HOST_DB_INVENTORY_FINGERPRINT_AFTER_FIRST="${MOCK_HOST_DB_INVENTORY_FINGERPRINT_AFTER_FIRST:-}" \
  MOCK_HOST_DB_PRIMARY_AFTER_SECOND="${MOCK_HOST_DB_PRIMARY_AFTER_SECOND:-}" \
  MOCK_HOST_DB_PRIMARY_FINGERPRINT_AFTER_SECOND="${MOCK_HOST_DB_PRIMARY_FINGERPRINT_AFTER_SECOND:-}" \
  MOCK_HOST_DB_RECOVERY_AFTER_SECOND="${MOCK_HOST_DB_RECOVERY_AFTER_SECOND:-}" \
  MOCK_HOST_DB_RETAINED_AFTER_SECOND="${MOCK_HOST_DB_RETAINED_AFTER_SECOND:-}" \
  MOCK_HOST_DB_INVENTORY_AFTER_SECOND="${MOCK_HOST_DB_INVENTORY_AFTER_SECOND:-}" \
  MOCK_HOST_DB_INVENTORY_FINGERPRINT_AFTER_SECOND="${MOCK_HOST_DB_INVENTORY_FINGERPRINT_AFTER_SECOND:-}" \
  MOCK_PACKAGE_APPEAR_AFTER_DB_OBSERVATION="${MOCK_PACKAGE_APPEAR_AFTER_DB_OBSERVATION:-}" \
  MOCK_STATUS_DB_SCHEMA="${MOCK_STATUS_DB_SCHEMA:-${MOCK_DB_DEVICE_USER_VERSION:-9}}" \
  MOCK_STATUS_DB_QUICK_CHECK="${MOCK_STATUS_DB_QUICK_CHECK:-ok}" \
  MOCK_STATUS_DB_NONCE="${MOCK_STATUS_DB_NONCE:-exact}" \
  MOCK_STATUS_DB_FIELDS="${MOCK_STATUS_DB_FIELDS:-exact}" \
  MOCK_WEBVIEW_VERSION="${MOCK_WEBVIEW_VERSION:-150.0.0.0}" \
  MOCK_GH_FAIL="${MOCK_GH_FAIL:-0}" \
  MOCK_GITHUB_API="${MOCK_GITHUB_API:-fail}" \
  MOCK_RELEASE_CERT="${MOCK_RELEASE_CERT:-ac6193307fb0b70113aae205d7549406f96e063bc5491b67b1d5694a34b0e339}" \
  MOCK_INSTALLED_CERT="${MOCK_INSTALLED_CERT:-${MOCK_RELEASE_CERT:-ac6193307fb0b70113aae205d7549406f96e063bc5491b67b1d5694a34b0e339}}" \
  MOCK_INSTALLED_APK_VERIFY_FAIL="${MOCK_INSTALLED_APK_VERIFY_FAIL:-0}" \
  MOCK_ADDITIONAL_INSTALLED_CERT="${MOCK_ADDITIONAL_INSTALLED_CERT:-}" \
  MOCK_RELEASE_PACKAGE="${MOCK_RELEASE_PACKAGE:-io.github.maxlyth.hapaneld}" \
  MOCK_RELEASE_VERIFY_FAIL="${MOCK_RELEASE_VERIFY_FAIL:-0}" \
  MOCK_RELEASE_PROOF_DOWNLOAD="${MOCK_RELEASE_PROOF_DOWNLOAD:-ok}" \
  MOCK_RELEASE_CHECKSUM="${MOCK_RELEASE_CHECKSUM:-ok}" \
  MOCK_RELEASE_SIGNATURE_FAIL="${MOCK_RELEASE_SIGNATURE_FAIL:-0}" \
  MOCK_LATE_ADB_STATUS="${MOCK_LATE_ADB_STATUS:-0}" \
  MOCK_LATE_ADB_PHASE="${MOCK_LATE_ADB_PHASE:-identity}" \
  MOCK_PRODUCT_IDENTITY="${MOCK_PRODUCT_IDENTITY:-}" \
  MOCK_ADB_ROOT="${MOCK_ADB_ROOT:-0}" \
  MOCK_HELPER_PROOF_DOWNLOAD="${MOCK_HELPER_PROOF_DOWNLOAD:-ok}" \
  MOCK_HELPER_CHECKSUM="${MOCK_HELPER_CHECKSUM:-ok}" \
  MOCK_HELPER_SIGNATURE_FAIL="${MOCK_HELPER_SIGNATURE_FAIL:-0}" \
  MOCK_ROOT="${MOCK_ROOT:-1}" \
  MOCK_ABI="${MOCK_ABI:-arm64-v8a}" \
  MOCK_SYSTEM_WRITABLE="${MOCK_SYSTEM_WRITABLE:-1}" \
  MOCK_SYSTEM_CAPACITY="${MOCK_SYSTEM_CAPACITY:-valid}" \
  MOCK_SYSTEM_AVAIL_KB="${MOCK_SYSTEM_AVAIL_KB:-1048576}" \
  MOCK_DEVICE_AWK="${MOCK_DEVICE_AWK:-present}" \
  MOCK_SYSTEMLESS_RUNNER="${MOCK_SYSTEMLESS_RUNNER:-1}" \
  MOCK_VENDOR_INIT_RW="${MOCK_VENDOR_INIT_RW:-1}" \
  MOCK_VENDOR_RC_STATE="${MOCK_VENDOR_RC_STATE:-missing}" \
  MOCK_HELPER_INSTALL="${MOCK_HELPER_INSTALL:-ok}" \
  MOCK_APP_REPLACEMENT_HOLD="${MOCK_APP_REPLACEMENT_HOLD:-0}" \
  MOCK_HELPER_COMMIT="${MOCK_HELPER_COMMIT:-ok}" \
  MOCK_COMMIT_LIVE_STATE="${MOCK_COMMIT_LIVE_STATE:-TARGET}" \
  MOCK_TRANSACTION_BUSY="${MOCK_TRANSACTION_BUSY:-0}" \
  MOCK_TRANSACTION_TAMPER="${MOCK_TRANSACTION_TAMPER:-0}" \
  MOCK_TRANSACTION_TOKEN_MISMATCH="${MOCK_TRANSACTION_TOKEN_MISMATCH:-0}" \
  MOCK_TOKEN_MISMATCH_ACTION="${MOCK_TOKEN_MISMATCH_ACTION:-}" \
  MOCK_HELPER_START="${MOCK_HELPER_START:-ok}" \
  MOCK_HELPER_CAPABILITY="${MOCK_HELPER_CAPABILITY:-ok}" \
  MOCK_ROLLBACK_PING="${MOCK_ROLLBACK_PING:-ok}" \
  MOCK_ROLLBACK_RESULT="${MOCK_ROLLBACK_RESULT:-ok}" \
  MOCK_ROLLBACK_RETIREMENT="${MOCK_ROLLBACK_RETIREMENT:-ok}" \
  MOCK_HELPER_BUILD_ID="${MOCK_HELPER_BUILD_ID}" \
  MOCK_HELPER_BUILD_ID_MATCH="${MOCK_HELPER_BUILD_ID_MATCH:-ok}" \
  MOCK_RELEASE_PROVISION_BUILD_ID="${MOCK_RELEASE_PROVISION_BUILD_ID:-$MOCK_HELPER_BUILD_ID}" \
  MOCK_RELEASE_HELPER_BUILD_ID="${MOCK_RELEASE_HELPER_BUILD_ID:-$MOCK_HELPER_BUILD_ID}" \
  MOCK_APK_INSTALL="${MOCK_APK_INSTALL:-ok}" \
  MOCK_APK_QUERY="${MOCK_APK_QUERY:-ok}" \
  HAPANELD_SKIP_AUTO_EXPORT="${HAPANELD_SKIP_AUTO_EXPORT:-1}" \
  HAPANELD_CONFIG_BACKUP_DIR="${HAPANELD_CONFIG_BACKUP_DIR:-$TMP/auto-backups}" \
  MOCK_MANUAL_STALE="${MOCK_MANUAL_STALE:-0}" \
  MOCK_STALE_TRANSACTION_KIND="${MOCK_STALE_TRANSACTION_KIND:-system}" \
  MOCK_ACTIVE_TRANSACTION="${MOCK_ACTIVE_TRANSACTION:-0}" \
  MOCK_STALE_LIVE_STATE="${MOCK_STALE_LIVE_STATE:-TARGET}" \
  MOCK_STALE_APK_SHA256="${MOCK_STALE_APK_SHA256:-$(/usr/bin/sha256sum "$TMP/installed-apk" | awk '{print $1}')}" \
  MOCK_STALE_BUILD_ID="${MOCK_STALE_BUILD_ID:-$MOCK_HELPER_BUILD_ID}" \
  MOCK_SU_DIALECT="${MOCK_SU_DIALECT:-join}" \
  MOCK_SHIZUKU_START="${MOCK_SHIZUKU_START:-ok}" \
  MOCK_SHIZUKU_START_SCRIPT="${MOCK_SHIZUKU_START_SCRIPT:-ok}" \
  MOCK_SHIZUKU_INSPECT="${MOCK_SHIZUKU_INSPECT:-ok}" \
  MOCK_SHIZUKU_INSPECT_PID_FILE="${MOCK_SHIZUKU_INSPECT_PID_FILE:-}" \
  MOCK_SHIZUKU_INSTALL="${MOCK_SHIZUKU_INSTALL:-ok}" \
  MOCK_SHIZUKU_INSTALL_PID_FILE="${MOCK_SHIZUKU_INSTALL_PID_FILE:-}" \
  SHIZUKU_INSPECT_TIMEOUT_SECONDS="${SHIZUKU_INSPECT_TIMEOUT_SECONDS:-20}" \
  SHIZUKU_INSTALL_TIMEOUT_SECONDS="${SHIZUKU_INSTALL_TIMEOUT_SECONDS:-180}" \
  ROOT_HELPER_LEASE_GUARD_INTERVAL_SECONDS="${ROOT_HELPER_LEASE_GUARD_INTERVAL_SECONDS:-60}" \
  MOCK_OPENSSL_MISSING="${MOCK_OPENSSL_MISSING:-0}" \
  MOCK_OPENSSL_DIGEST_FAIL="${MOCK_OPENSSL_DIGEST_FAIL:-0}" \
  HAPANELD_HELPER_DIST_DIR="${HAPANELD_HELPER_DIST_DIR:-$MOCK_HELPER_DIST}" \
  MOCK_STATE_DIR="$TMP" \
  MOCK_MSYS_ROOT="$MSYS_ROOT" \
  MOCK_MSYS_ARGV_LOG="$MSYS_ARGV_LOG" \
  MOCK_MSYS_DELEGATE="$PROVISION_TEST_ADB_FIXTURE" \
  MOCK_MSYS_IGNORE_EXCL="${MOCK_MSYS_IGNORE_EXCL:-0}" \
  PROVISIONING_PLAN_TIMEOUT_SECONDS="${PROVISIONING_PLAN_TIMEOUT_SECONDS:-2}" \
  HA_AUTH_CONNECT_TIMEOUT_SECONDS="${HA_AUTH_CONNECT_TIMEOUT_SECONDS:-1}" \
  HA_AUTH_TIMEOUT_SECONDS="${HA_AUTH_TIMEOUT_SECONDS:-1}" \
  PANEL_POST_CONNECT_TIMEOUT_SECONDS="${PANEL_POST_CONNECT_TIMEOUT_SECONDS:-1}" \
  PANEL_POST_TIMEOUT_SECONDS="${PANEL_POST_TIMEOUT_SECONDS:-1}" \
  PANEL_RESTORE_TIMEOUT_SECONDS="${PANEL_RESTORE_TIMEOUT_SECONDS:-1}" \
  APK_INSTALL_TIMEOUT_SECONDS="${APK_INSTALL_TIMEOUT_SECONDS:-30}" \
  UPGRADE_PREPARE_TIMEOUT_SECONDS="${UPGRADE_PREPARE_TIMEOUT_SECONDS:-45}" \
  STORAGE_HEALTH_VERIFY_ATTEMPTS="${STORAGE_HEALTH_VERIFY_ATTEMPTS:-3}" \
  STORAGE_HEALTH_VERIFY_POLL_SECONDS="${STORAGE_HEALTH_VERIFY_POLL_SECONDS:-0}" \
  STORAGE_HEALTH_PACKAGE_QUERY_SECONDS="${STORAGE_HEALTH_PACKAGE_QUERY_SECONDS:-2}" \
  MOCK_APK_INSTALL_PID_FILE="${MOCK_APK_INSTALL_PID_FILE:-}" \
  MOCK_PANEL_TIMEZONE="${MOCK_PANEL_TIMEZONE-Europe/London}" \
  TZDIR="$MOCK_ZONEINFO" \
  HAPANELD_HOST_TIMEZONE_ETC="$MOCK_HOST_ETC" \
  HAPANELD_TIMEZONE_PROBE_SECONDS="${HAPANELD_TIMEZONE_PROBE_SECONDS:-2}" \
  TZ="${MOCK_HOST_TZ-Europe/London}" \
  PATH="$provision_path" \
    bash "$PROVISION" "$@" "${unsigned_ack[@]}" > "$LAST_OUTPUT" 2>&1
  LAST_STATUS=$?
}

pass() {
  passes=$((passes + 1))
  printf 'ok %d - %s\n' "$passes" "$1"
}

fail_test() {
  failures=$((failures + 1))
  printf 'not ok - %s\n' "$1" >&2
  if [ -f "$LAST_OUTPUT" ]; then
    sed 's/^/  | /' "$LAST_OUTPUT" >&2
  fi
}

finish_provision_test() {
  printf '1..%d\n' "$((passes + failures))"
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

assert_failure() {
  description="$1"
  if [ "$LAST_STATUS" -ne 0 ]; then pass "$description"
  else fail_test "$description (unexpected status 0)"; fi
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

assert_marker_captured() {
  if grep -qx 'HAPANELD_SNAPSHOT_RESULT=captured' "$LAST_OUTPUT"; then pass "$1"
  else fail_test "$1 (no whole-line captured marker)"; fi
}
assert_marker_absent() {
  if grep -qx 'HAPANELD_SNAPSHOT_RESULT=captured' "$LAST_OUTPUT"; then fail_test "$1 (unexpected captured marker)"
  else pass "$1"; fi
}
reset_db_txn_state() { rm -rf "$TMP/db-txn-sandbox" "$TMP"/db-txn-script*; rm -f "$TMP"/auto-backups/*.break-glass* "$TMP/adb-root-escalated" "$TMP/bare-id-count" 2>/dev/null; }


CAPACITY_PROBE_SOURCE="$(sed -n '
  /# HAPANELD_CAPACITY_PROBE_BEGIN/,/# HAPANELD_CAPACITY_PROBE_END/ {
    /# HAPANELD_CAPACITY_PROBE_BEGIN/d
    /# HAPANELD_CAPACITY_PROBE_END/d
    p
  }
' "$PROVISION")"

run_capacity_probe_fixture() {
  local state="$1" df_output="" prelude
  case "$state" in
    valid)
      df_output='Filesystem 1024-blocks Used Available Capacity Mounted on
/dev/block/platform/soc/by-name/system 2064192 1965516 98676 96% /system'
      ;;
    malformed)
      df_output='Filesystem 1024-blocks Used Available Capacity Mounted on
/dev/block/platform/soc/by-name/system 2064192 1965516 unknown 96% /system'
      ;;
    ambiguous)
      df_output='Filesystem 1024-blocks Used Available Capacity Mounted on
/dev/block/dm-0 2064192 1965516 98676 96% /system
/dev/block/dm-1 2064192 1965516 98676 96% /system'
      ;;
    missing) ;;
  esac
  case "$state" in
    missing-df)
      prelude='sed() { /bin/sed "$@"; }'
      ;;
    missing-sed)
      prelude='df() {
        [ "$#" -eq 3 ] && [ "$1" = -P ] && [ "$2" = -k ] && [ "$3" = /system/etc/init ] || return 2
        printf "%s\n" "$CAPACITY_DF_OUTPUT"
      }'
      ;;
    *)
      prelude='df() {
        [ "$#" -eq 3 ] && [ "$1" = -P ] && [ "$2" = -k ] && [ "$3" = /system/etc/init ] || return 2
        printf "%s\n" "$CAPACITY_DF_OUTPUT"
      }
      sed() { /bin/sed "$@"; }'
      ;;
  esac
  CAPACITY_DF_OUTPUT="$df_output" PATH="$TMP/no-device-tools" /bin/sh -c "$prelude
$CAPACITY_PROBE_SOURCE"
}

make_local_apk() {
  apk_path="$1"
  arm_helper="$2"
  arm64_helper="$3"
  /usr/bin/python3 - "$apk_path" "$arm_helper" "$arm64_helper" <<'PY'
import sys
import zipfile

apk, arm, arm64 = sys.argv[1:]
with zipfile.ZipFile(apk, "w") as archive:
    archive.writestr("AndroidManifest.xml", b"test manifest\n")
    archive.write(arm, "assets/hapaneld-helper-arm")
    archive.write(arm64, "assets/hapaneld-helper-arm64")
PY
}

APK="$TMP/ha-paneld.apk"
make_local_apk "$APK" \
  "$MOCK_HELPER_DIST/armeabi-v7a/hapaneld-helper" \
  "$MOCK_HELPER_DIST/arm64-v8a/hapaneld-helper"
RELEASE_APK="$TMP/ha-paneld-v0.9.2-rc3-manual-setup-required.apk"
printf 'test release apk\n' > "$RELEASE_APK"
HELPER_RELEASE_APK="$TMP/ha-paneld-v0.9.4-rc1-manual-setup-required.apk"
printf 'test helper release apk\n' > "$HELPER_RELEASE_APK"
NO_SIGNER_FIXTURES="$TMP/fixtures-without-apksigner"
mkdir -p "$NO_SIGNER_FIXTURES"
for fixture in "$FIXTURES"/*; do
  [ "$(basename "$fixture")" = apksigner ] || ln -s "$fixture" "$NO_SIGNER_FIXTURES/$(basename "$fixture")"
done
# A complete host, minus the one tool under test. Every apksigner-absence scenario used to run with
# `$NO_SIGNER_FIXTURES:/usr/bin:/bin`, which re-admits a host apksigner the moment it is installed in
# /usr/bin - and then the "absent" assertions pass for a reason that has nothing to do with the code.
# They passed here only because this host keeps apksigner in /usr/local/bin. Subtraction is the only
# honest way to model absence on a machine that has the tool: link everything, skip the tool.
for no_signer_dir in /usr/bin /bin; do
  [ -d "$no_signer_dir" ] || continue
  for no_signer_tool in "$no_signer_dir"/*; do
    case "${no_signer_tool##*/}" in apksigner) continue ;; esac
    [ -e "$NO_SIGNER_FIXTURES/${no_signer_tool##*/}" ] || ln -s "$no_signer_tool" "$NO_SIGNER_FIXTURES/${no_signer_tool##*/}" 2>/dev/null
  done
done
# Prove the sandbox excludes an apksigner that IS on the host's PATH, on every host. A decoy is placed
# ahead of the sandbox and must be found; the sandbox alone must not find it. Without the control the
# second check could pass because command -v itself was broken.
NO_SIGNER_DECOY="$TMP/host-apksigner-decoy"
mkdir -p "$NO_SIGNER_DECOY"
printf '#!/bin/sh\nexit 0\n' > "$NO_SIGNER_DECOY/apksigner"
chmod 755 "$NO_SIGNER_DECOY/apksigner"
if [ "$PROVISION_TEST_INTERNAL_SHARD" -eq 0 ] || [ "$PROVISION_TEST_SCOPE" = shard-database-host ]; then
if PATH="$NO_SIGNER_DECOY:$NO_SIGNER_FIXTURES" command -v apksigner >/dev/null 2>&1; then
  pass "a host apksigner ahead of the sandbox is findable (control)"
else
  fail_test "a host apksigner ahead of the sandbox is findable (control)"
fi
if PATH="$NO_SIGNER_FIXTURES" command -v apksigner >/dev/null 2>&1; then
  fail_test "the no-apksigner sandbox has no apksigner wherever the host keeps one"
else
  pass "the no-apksigner sandbox has no apksigner wherever the host keeps one"
fi
if PATH="$NO_SIGNER_FIXTURES" command -v bash >/dev/null 2>&1 && PATH="$NO_SIGNER_FIXTURES" command -v sed >/dev/null 2>&1; then
  pass "the no-apksigner sandbox is otherwise a complete host"
else
  fail_test "the no-apksigner sandbox is otherwise a complete host"
fi
# The leak is a habit, not a one-off: every absence site once appended the host directories. This
# reads the suite itself so the habit cannot come back unnoticed on a host where it happens to be
# harmless.
# Non-comment lines only, and the shape of a real PATH assignment - a bare quote before the dollar,
# then the variable, then a colon. This block's own comment and this grep line both mention the leak
# in other forms, and a self-grep that matched them failed on the clean tree the first time.
if grep -v '^[[:space:]]*#' "$0" | grep -Eq 'PATH="\$NO_SIGNER_FIXTURES:[^"]'; then
  fail_test "no apksigner-absence scenario re-admits the host's tool directories"
else
  pass "no apksigner-absence scenario re-admits the host's tool directories"
fi
fi
# The same subtraction for any set of tools, built only by the shards that use it: every fixture, then
# every host tool in /usr/bin and /bin, minus the named ones.
make_host_without() {
  local sandbox="$1" source; shift
  mkdir -p "$sandbox"
  for source in "$FIXTURES"/* /usr/bin/* /bin/*; do
    case " $* " in *" ${source##*/} "*) continue ;; esac
    [ -e "$sandbox/${source##*/}" ] || ln -s "$source" "$sandbox/${source##*/}" 2>/dev/null
  done
  return 0
}

NO_GH_FIXTURES="$TMP/fixtures-without-gh"
mkdir -p "$NO_GH_FIXTURES"
for fixture in "$FIXTURES"/*; do
  [ "$(basename "$fixture")" = gh ] || ln -s "$fixture" "$NO_GH_FIXTURES/$(basename "$fixture")"
done

# Export is a recovery operation. It must be possible before resolving or installing an APK.
EXPORT="$TMP/panel-backup.json"
RESTORE="$TMP/restore.json"
printf '{"kind":"ha-paneld-config","schema":1,"values":{}}\n' > "$RESTORE"
if provision_scope_is db core all \
  shard-database-host shard-database-runtime shard-install-export shard-install-runtime \
  shard-helper-transaction shard-release-integrity shard-renderer-seeding \
  shard-install-finish; then
if provision_scope_is db core all shard-database-host; then
# The host and Android gates consume one normative table. Every row whose owner can occur at a host
# install entry point is replayed through the real provisioner; refusal rows additionally prove the
# decision precedes every helper/package/configuration mutation, not merely that the command failed.
DB_COMPAT_VECTORS="$FIXTURES/database-compatibility-vectors.tsv"
db_vector_total=0
db_vector_applicable=0
while IFS=$'\t' read -r vector_id vector_contract vector_owner vector_primary vector_recoveries vector_verdict vector_reason; do
  case "$vector_id" in \#*|'') continue ;; esac
  db_vector_total=$((db_vector_total + 1))
  case "$vector_owner" in RUNTIME_STARTUP) continue ;; esac
  db_vector_applicable=$((db_vector_applicable + 1))

  vector_no_package=0
  vector_pm_probe=ok
  case "$vector_owner" in
    PACKAGE_ABSENT_PROVEN) vector_no_package=1 ;;
    PACKAGE_UNKNOWN) vector_pm_probe=truncated ;;
  esac
  case "$vector_contract" in -) vector_candidate=missing ;; *) vector_candidate="$vector_contract" ;; esac
  case "$vector_primary" in
    MISSING) vector_host_primary=missing ;;
    UNREADABLE) vector_host_primary=unreadable ;;
    READABLE:*) vector_host_primary="readable:${vector_primary#READABLE:}:ok" ;;
    *) fail_test "database vector $vector_id has an unsupported primary fixture"; continue ;;
  esac
  vector_retained=0
  [ "$vector_recoveries" = - ] || vector_retained=1
  vector_host_recovery=none
  vector_candidate_max="$(printf '%s' "$vector_contract" | awk -F: '$1=="hapaneld-db" && $2=="v1" && $3=="ha-paneld.db" && $4~/^[0-9]+$/ && $5~/^[0-9]+$/ { print $5 }')"
  if [ -n "$vector_candidate_max" ] && [ "$vector_recoveries" != - ]; then
    vector_best=-1
    IFS=';' read -r -a vector_recovery_entries <<< "$vector_recoveries"
    for vector_recovery in "${vector_recovery_entries[@]}"; do
      IFS=: read -r vector_kind vector_name vector_filename_schema vector_actual_schema vector_integrity vector_file_kind <<< "$vector_recovery"
      [ "$vector_kind" = P ] || continue
      case "$vector_filename_schema" in ''|*[!0-9]*) continue ;; esac
      [ "$vector_filename_schema" -le "$vector_candidate_max" ] || continue
      [ "$vector_filename_schema" -gt "$vector_best" ] || continue
      vector_best="$vector_filename_schema"
      if [ "$vector_file_kind" = incomplete ]; then
        vector_host_recovery="v${vector_filename_schema}:incomplete"
      elif [ "$vector_file_kind" = file+sidecar ]; then
        vector_host_recovery="v${vector_filename_schema}:sidecar"
      elif [ "$vector_file_kind" != file ]; then
        vector_host_recovery="v${vector_filename_schema}:not_regular"
      elif [ "$vector_actual_schema" = '?' ]; then
        vector_host_recovery="v${vector_filename_schema}:unreadable"
      else
        vector_host_recovery="v${vector_filename_schema}:readable:${vector_actual_schema}:${vector_integrity}"
      fi
    done
  fi

  MOCK_DB_CANDIDATE_CONTRACT="$vector_candidate" \
  MOCK_HOST_DB_PRIMARY="$vector_host_primary" \
  MOCK_HOST_DB_RECOVERY="$vector_host_recovery" \
  MOCK_HOST_DB_RETAINED="$vector_retained" \
  MOCK_NO_INSTALLED_PACKAGE="$vector_no_package" \
  MOCK_PM_PATH="$([ "$vector_no_package" = 1 ] && printf fail || printf ok)" \
  MOCK_PM_PROBE="$vector_pm_probe" \
    run_provision "$MOCK_TARGET" --apk "$APK" --no-tame
  case "$vector_verdict" in
    DIRECT|RECOVER|FRESH)
      assert_success "database vector $vector_id is admitted as $vector_verdict"
      if [ "$vector_verdict" = RECOVER ]; then
        assert_contains 'database recovery proven' "database vector $vector_id names its proven recovery path"
      fi
      ;;
    REFUSE)
      assert_failure "database vector $vector_id is refused"
      assert_contains 'database compatibility could not be proven' "database vector $vector_id fails at HOST_GATE"
      assert_not_contains 'config/export|PREPARE_UPGRADE|ha-paneld-db-txn|/data/local/tmp/hapaneld-helper|^adb .* install( |$)|pm clear|pm grant|appops set|settings put|monkey -p io\.github\.maxlyth\.hapaneld|am start -n io\.github\.maxlyth\.hapaneld|/api/v1/config($|[? /])' \
        "$MOCK_CALL_LOG" "database vector $vector_id refuses before every tracked mutation"
      ;;
    *) fail_test "database vector $vector_id has an unsupported verdict" ;;
  esac
done < "$DB_COMPAT_VECTORS"
if [ "$db_vector_total" -eq 25 ] && [ "$db_vector_applicable" -eq 23 ]; then
  pass "host gate consumed all 23 applicable rows from the 25-row shared compatibility table"
else
  fail_test "host gate consumed all applicable shared compatibility rows (total=$db_vector_total applicable=$db_vector_applicable)"
fi
unset vector_id vector_contract vector_owner vector_primary vector_recoveries vector_verdict vector_reason
unset vector_no_package vector_pm_probe vector_candidate vector_host_primary vector_retained vector_host_recovery
unset vector_candidate_max vector_best vector_recovery vector_kind vector_name vector_filename_schema
unset vector_actual_schema vector_integrity vector_file_kind vector_recovery_entries

# FORCE and deliberate reset affect version/UI policy only. Neither is authority to replace a package
# whose actual database is above the candidate boundary.
for vector_bypass in --force --reset-config; do
  MOCK_DB_CANDIDATE_CONTRACT='hapaneld-db:v1:ha-paneld.db:11:14' \
  MOCK_HOST_DB_PRIMARY='readable:15:ok' MOCK_HOST_DB_RECOVERY=none \
  HAPANELD_RESET_CONFIRM=RESET run_provision "$MOCK_TARGET" --apk "$APK" --no-tame "$vector_bypass"
  assert_failure "$vector_bypass cannot bypass database refusal"
  assert_not_contains 'config/export|PREPARE_UPGRADE|ha-paneld-db-txn|/data/local/tmp/hapaneld-helper|^adb .* install( |$)|pm clear|pm grant|appops set|settings put|monkey -p io\.github\.maxlyth\.hapaneld|am start -n io\.github\.maxlyth\.hapaneld|/api/v1/config($|[? /])' \
    "$MOCK_CALL_LOG" "$vector_bypass refusal has zero tracked mutations"
done
unset vector_bypass

MOCK_HOST_DB_PRIMARY='readable:14:ok' \
MOCK_DB_CANDIDATE_CONTRACT='hapaneld-db:v1:ha-paneld.db:11:14' \
HAPANELD_RESET_CONFIRM=RESET run_provision "$MOCK_TARGET" --apk "$APK" --no-tame --reset-config
assert_success "a compatible confirmed reset is re-proven fresh before package replacement"
assert_contains 'confirmed reset.*database and recovery state proven empty' \
  "rooted reset admission names its post-clear fresh proof"

MOCK_ROOT=0 MOCK_STATUS_DB_SCHEMA=14 MOCK_STATUS_DB_QUICK_CHECK=ok \
MOCK_DB_CANDIDATE_CONTRACT='hapaneld-db:v1:ha-paneld.db:11:14' \
HAPANELD_RESET_CONFIRM=RESET run_provision "$MOCK_TARGET" --apk "$APK" --no-tame --reset-config
assert_success "a rootless confirmed reset is re-proven stopped before package replacement"
assert_contains 'package remained stopped.*fresh database state' \
  "rootless reset admission names its post-clear stopped-state proof"
assert_log_contains 'HAPANELD_RESET_STOP_BEGIN:[0-9a-f]{32}' \
  "rootless reset uses a nonce-bound final Android stopped-state observation"

# A successful pm clear is not lasting fresh-state proof. If the old app is relaunched before the
# final boundary, it can recreate an arbitrarily new database while the host remains rootless.
MOCK_ROOT=0 MOCK_STATUS_DB_SCHEMA=14 MOCK_STATUS_DB_QUICK_CHECK=ok \
MOCK_RELAUNCH_AFTER_PM_CLEAR=1 \
MOCK_DB_CANDIDATE_CONTRACT='hapaneld-db:v1:ha-paneld.db:11:14' \
HAPANELD_RESET_CONFIRM=RESET run_provision "$MOCK_TARGET" --apk "$APK" --no-tame --reset-config
assert_failure "rootless reset refuses an old-app relaunch and database recreation race"
assert_contains 'package-time recheck.*reset package ran again after pm clear' \
  "rootless reset race names the lost fresh-state proof"
if [ -f "$TMP/reset-database-recreated" ]; then
  pass "rootless reset race deterministically recreates app-private database state"
else
  fail_test "rootless reset race deterministically recreates app-private database state"
fi
assert_not_contains '^adb .* install( |$)|pm grant|appops set io\.github\.maxlyth\.hapaneld|settings put|monkey -p io\.github\.maxlyth\.hapaneld|am start -n io\.github\.maxlyth\.hapaneld|/api/v1/config($|[? /])' \
  "$MOCK_CALL_LOG" "rootless reset race refuses before ha-paneld APK, grants or configuration mutation"

# A later force-stop cannot erase the historical notLaunched=false evidence. Refuse even when the
# package is stopped again at the final instant, because it already had an opportunity to write.
MOCK_ROOT=0 MOCK_STATUS_DB_SCHEMA=14 MOCK_STATUS_DB_QUICK_CHECK=ok \
MOCK_RELAUNCH_AFTER_PM_CLEAR=1 MOCK_RESTOP_AFTER_RELAUNCH=1 \
MOCK_DB_CANDIDATE_CONTRACT='hapaneld-db:v1:ha-paneld.db:11:14' \
HAPANELD_RESET_CONFIRM=RESET run_provision "$MOCK_TARGET" --apk "$APK" --no-tame --reset-config
assert_failure "rootless reset refuses a relaunched package that was stopped again"
assert_contains 'package-time recheck.*reset package ran again after pm clear' \
  "rootless reset binds Android notLaunched evidence, not only current stopped state"
assert_not_contains '^adb .* install( |$)|pm grant|appops set io\.github\.maxlyth\.hapaneld|settings put|monkey -p io\.github\.maxlyth\.hapaneld|am start -n io\.github\.maxlyth\.hapaneld|/api/v1/config($|[? /])' \
  "$MOCK_CALL_LOG" "restopped rootless reset race refuses before ha-paneld mutation"

for invalid_candidate_contract in \
  'hapaneld-db:v1:ha-paneld.db:011:14' \
  'hapaneld-db:v1:ha-paneld.db:11:014' \
  'hapaneld-db:v1:ha-paneld.db:11:2147483648'; do
  MOCK_DB_CANDIDATE_CONTRACT="$invalid_candidate_contract" \
  MOCK_HOST_DB_PRIMARY='readable:14:ok' run_provision "$MOCK_TARGET" --apk "$APK" --no-tame
  assert_failure "non-canonical candidate contract $invalid_candidate_contract is refused"
  assert_not_contains 'config/export|PREPARE_UPGRADE|ha-paneld-db-txn|/data/local/tmp/hapaneld-helper|^adb .* install( |$)|pm clear|pm grant|appops set|settings put|monkey -p io\.github\.maxlyth\.hapaneld|am start -n io\.github\.maxlyth\.hapaneld|/api/v1/config($|[? /])' \
    "$MOCK_CALL_LOG" "non-canonical candidate contract refusal has zero tracked mutations"
done
unset invalid_candidate_contract

# The admitted manifest must remain bound to the authenticated file bytes. This fixture replaces the
# candidate from inside the exact xmltree read, after signature/package authentication but before the
# first mutation boundary; the post-read digest assertion must catch it.
SWAP_APK="$TMP/ha-paneld-swap.apk"
make_local_apk "$SWAP_APK" \
  "$MOCK_HELPER_DIST/armeabi-v7a/hapaneld-helper" \
  "$MOCK_HELPER_DIST/arm64-v8a/hapaneld-helper"
MOCK_SWAP_APK_AFTER_CONTRACT=1 MOCK_HOST_DB_PRIMARY='readable:14:ok' \
  run_provision "$MOCK_TARGET" --apk "$SWAP_APK" --no-tame
assert_failure "candidate byte replacement after authentication is refused"
assert_contains 'candidate APK bytes changed after authentication' "candidate byte replacement names the exact binding failure"
assert_not_contains 'config/export|PREPARE_UPGRADE|ha-paneld-db-txn|/data/local/tmp/hapaneld-helper|^adb .* install( |$)|pm clear|pm grant|appops set|settings put|monkey -p io\.github\.maxlyth\.hapaneld|am start -n io\.github\.maxlyth\.hapaneld|/api/v1/config($|[? /])' \
  "$MOCK_CALL_LOG" "candidate byte replacement refuses before every tracked mutation"

AAPT2_ONLY_FIXTURES="$TMP/fixtures-aapt2-only"
mkdir -p "$AAPT2_ONLY_FIXTURES"
for aapt2_fixture in "$FIXTURES"/*; do
  [ "$(basename "$aapt2_fixture")" = aapt ] || ln -sf "$aapt2_fixture" "$AAPT2_ONLY_FIXTURES/$(basename "$aapt2_fixture")"
done
# Keep this selection test hermetic on hosts that provide a real aapt in /usr/bin. Discovery must
# observe an installed-but-unusable aapt, skip it, and then select the fixture aapt2.
ln -sf /bin/false "$AAPT2_ONLY_FIXTURES/aapt"
ln -sf "$FIXTURES/aapt" "$AAPT2_ONLY_FIXTURES/aapt2"
PATH="$AAPT2_ONLY_FIXTURES:/usr/bin:/bin" ANDROID_HOME= ANDROID_SDK_ROOT= MOCK_HOST_DB_PRIMARY='readable:14:ok' \
  run_provision "$MOCK_TARGET" --apk "$APK" --no-tame
assert_success "aapt2 full-namespace application metadata admits a compatible candidate"
assert_log_contains '^aapt2 dump xmltree .* --file AndroidManifest.xml$' "aapt2 contract uses its exact xmltree command grammar"

for metadata_scope_mode in component duplicate_application; do
  if [ "$metadata_scope_mode" = component ]; then
    MOCK_DB_METADATA_SCOPE=component MOCK_HOST_DB_PRIMARY='readable:14:ok' \
      run_provision "$MOCK_TARGET" --apk "$APK" --no-tame
  else
    MOCK_DB_APPLICATION_DUPLICATE=1 MOCK_HOST_DB_PRIMARY='readable:14:ok' \
      run_provision "$MOCK_TARGET" --apk "$APK" --no-tame
  fi
  assert_failure "$metadata_scope_mode database metadata is refused"
  assert_contains 'missing, duplicate or malformed database metadata' "$metadata_scope_mode refusal names candidate metadata"
  assert_contains 'Candidate boundary: ' "$metadata_scope_mode refusal keeps the candidate-boundary advice"
  assert_not_contains 'config/export|PREPARE_UPGRADE|ha-paneld-db-txn|/data/local/tmp/hapaneld-helper|^adb .* install( |$)|pm clear|pm grant|appops set|settings put|monkey -p io\.github\.maxlyth\.hapaneld|am start -n io\.github\.maxlyth\.hapaneld|/api/v1/config($|[? /])' \
    "$MOCK_CALL_LOG" "$metadata_scope_mode refusal has zero tracked mutations"
done
unset metadata_scope_mode aapt2_fixture

MOCK_NO_INSTALLED_PACKAGE=1 MOCK_PM_PATH=fail MOCK_PM_UNINSTALLED_RECORD=absent \
MOCK_HOST_DB_PRIMARY=missing MOCK_HOST_DB_RECOVERY=none MOCK_HOST_DB_RETAINED=0 \
MOCK_HOST_DB_INVENTORY=unreadable \
  run_provision "$MOCK_TARGET" --apk "$APK" --no-tame
assert_failure "rooted fresh install refuses when the app-data inventory is unreadable"
assert_contains 'app-data database inventory could not be traversed' "rooted inventory denial names the missing fresh proof"
assert_not_contains 'config/export|PREPARE_UPGRADE|ha-paneld-db-txn|/data/local/tmp/hapaneld-helper|^adb .* install( |$)|pm clear|pm grant|appops set|settings put|monkey -p io\.github\.maxlyth\.hapaneld|am start -n io\.github\.maxlyth\.hapaneld|/api/v1/config($|[? /])' \
  "$MOCK_CALL_LOG" "rooted inventory denial has zero tracked mutations"

for retained_fresh_artifact in primary-journal restore-temp malformed-recovery; do
  MOCK_NO_INSTALLED_PACKAGE=1 MOCK_PM_PATH=fail \
  MOCK_HOST_DB_PRIMARY=missing MOCK_HOST_DB_RECOVERY=none MOCK_HOST_DB_RETAINED=1 \
  MOCK_DB_CANDIDATE_CONTRACT='hapaneld-db:v1:ha-paneld.db:11:14' \
    run_provision "$MOCK_TARGET" --apk "$APK" --no-tame
  assert_failure "rooted package absence with $retained_fresh_artifact is not classified fresh"
  assert_not_contains 'config/export|PREPARE_UPGRADE|ha-paneld-db-txn|/data/local/tmp/hapaneld-helper|^adb .* install( |$)|pm clear|pm grant|appops set|settings put|monkey -p io\.github\.maxlyth\.hapaneld|am start -n io\.github\.maxlyth\.hapaneld|/api/v1/config($|[? /])' \
    "$MOCK_CALL_LOG" "rooted retained $retained_fresh_artifact refusal has zero tracked mutations"
done
unset retained_fresh_artifact

MOCK_HOST_DB_PRIMARY='readable:15:ok' MOCK_HOST_DB_RECOVERY='v14:sidecar' \
MOCK_DB_CANDIDATE_CONTRACT='hapaneld-db:v1:ha-paneld.db:11:14' \
  run_provision "$MOCK_TARGET" --apk "$APK" --no-tame
assert_failure "premigration recovery with a SQLite sidecar cannot license replacement"
assert_contains 'premigration recovery has a SQLite sidecar or temporary file' \
  "recovery-sidecar refusal names the incoherent artifact"
assert_not_contains 'config/export|PREPARE_UPGRADE|ha-paneld-db-txn|/data/local/tmp/hapaneld-helper|^adb .* install( |$)|pm clear|pm grant|appops set|settings put|monkey -p io\.github\.maxlyth\.hapaneld|am start -n io\.github\.maxlyth\.hapaneld|/api/v1/config($|[? /])' \
  "$MOCK_CALL_LOG" "recovery-sidecar refusal has zero tracked mutations"

MOCK_HOST_DB_PRIMARY='readable:15:ok' MOCK_HOST_DB_RECOVERY='v14:readable:14:ok' \
MOCK_HOST_DB_INVENTORY=unreadable \
MOCK_DB_CANDIDATE_CONTRACT='hapaneld-db:v1:ha-paneld.db:11:14' \
  run_provision "$MOCK_TARGET" --apk "$APK" --no-tame
assert_failure "present-package recovery refuses when the complete recovery inventory is unreadable"
assert_contains 'complete premigration recovery inventory could not be traversed' \
  "unreadable recovery inventory names the missing proof"
assert_not_contains 'config/export|PREPARE_UPGRADE|ha-paneld-db-txn|/data/local/tmp/hapaneld-helper|^adb .* install( |$)|pm clear|pm grant|appops set|settings put|monkey -p io\.github\.maxlyth\.hapaneld|am start -n io\.github\.maxlyth\.hapaneld|/api/v1/config($|[? /])' \
  "$MOCK_CALL_LOG" "unreadable recovery inventory refuses before every tracked mutation"

MOCK_HOST_DB_PRIMARY='readable:14:ok' MOCK_HOST_DB_INVENTORY=unreadable \
MOCK_DB_CANDIDATE_CONTRACT='hapaneld-db:v1:ha-paneld.db:11:14' \
  run_provision "$MOCK_TARGET" --apk "$APK" --no-tame
assert_success "in-range direct replacement does not require an irrelevant recovery inventory"

# Backup/quiescence sits between admission and consumption. Drift in either the canonical schema or
# the exact recovery inventory must be caught by the second full authority before reset/helper/APK or
# configuration mutation. The backup transaction itself is intentionally allowed to have completed.
MOCK_HOST_DB_PRIMARY='readable:14:ok' MOCK_HOST_DB_PRIMARY_AFTER_FIRST='readable:15:ok' \
MOCK_DB_CANDIDATE_CONTRACT='hapaneld-db:v1:ha-paneld.db:11:14' \
  run_provision "$MOCK_TARGET" --apk "$APK" --no-tame
assert_failure "canonical database drift after backup is refused at consume time"
assert_contains 'consume-time recheck.*database schema 15 is newer than candidate maximum 14' \
  "database drift names the consume-time authority and incompatible observed schema"
assert_log_contains 'PREPARE_UPGRADE|ha-paneld-db-txn' \
  "database drift fixture reaches the intervening backup boundary"
assert_not_contains '/data/local/tmp/hapaneld-helper|^adb .* install( |$)|pm clear|pm grant|appops set|settings put|monkey -p io\.github\.maxlyth\.hapaneld|am start -n io\.github\.maxlyth\.hapaneld|/api/v1/config($|[? /])' \
  "$MOCK_CALL_LOG" "database drift refuses before reset, helper, APK, grant or configuration mutation"

MOCK_HOST_DB_PRIMARY='readable:15:ok' MOCK_HOST_DB_RECOVERY='v14:readable:14:ok' \
MOCK_HOST_DB_RECOVERY_AFTER_FIRST=none \
MOCK_DB_CANDIDATE_CONTRACT='hapaneld-db:v1:ha-paneld.db:11:14' \
  run_provision "$MOCK_TARGET" --apk "$APK" --no-tame
assert_failure "selected recovery removal after backup is refused at consume time"
assert_contains 'consume-time recheck.*no selectable premigration recovery exists' \
  "recovery removal names the consume-time authority"
assert_not_contains '/data/local/tmp/hapaneld-helper|^adb .* install( |$)|pm clear|pm grant|appops set|settings put|monkey -p io\.github\.maxlyth\.hapaneld|am start -n io\.github\.maxlyth\.hapaneld|/api/v1/config($|[? /])' \
  "$MOCK_CALL_LOG" "recovery drift refuses before reset, helper, APK, grant or configuration mutation"

# A third observation is adjacent to ha-paneld package replacement. It closes the helper/Shizuku
# preparation window and must roll back this run's task-owned helper before refusing drift.
MOCK_HOST_DB_PRIMARY='readable:14:ok' MOCK_HOST_DB_PRIMARY_AFTER_SECOND='readable:15:ok' \
MOCK_DB_CANDIDATE_CONTRACT='hapaneld-db:v1:ha-paneld.db:11:14' \
  run_provision "$MOCK_TARGET" --apk "$APK" --no-tame
assert_failure "canonical schema drift during helper preparation is refused at package time"
assert_contains 'package-time recheck.*database schema 15 is newer than candidate maximum 14' \
  "late schema drift names the package-time authority"
assert_log_contains 'helper-transaction-[0-9a-f]+.*install-system' \
  "late drift fixture reaches task-owned helper preparation"
assert_log_contains 'helper-transaction-[0-9a-f]+.*rollback-system' \
  "package-time refusal rolls back task-owned helper preparation"
assert_not_contains '^adb .* install( |$)|pm clear|pm grant|appops set io\.github\.maxlyth\.hapaneld|settings put|monkey -p io\.github\.maxlyth\.hapaneld|am start -n io\.github\.maxlyth\.hapaneld|/api/v1/config($|[? /])' \
  "$MOCK_CALL_LOG" "late schema drift refuses before ha-paneld APK, grants or configuration mutation"

MOCK_HOST_DB_PRIMARY='readable:15:ok' MOCK_HOST_DB_RECOVERY='v14:readable:14:ok' \
MOCK_HOST_DB_INVENTORY_FINGERPRINT='v14:sha-stable' \
MOCK_HOST_DB_INVENTORY_FINGERPRINT_AFTER_SECOND='v14:sha-drifted' \
MOCK_DB_CANDIDATE_CONTRACT='hapaneld-db:v1:ha-paneld.db:11:14' \
  run_provision "$MOCK_TARGET" --apk "$APK" --no-tame
assert_failure "selected recovery content drift during helper preparation is refused at package time"
assert_contains 'package-time recheck.*package, database or recovery inventory changed' \
  "late recovery content drift names the exact package-time evidence mismatch"
assert_log_contains 'helper-transaction-[0-9a-f]+.*rollback-system' \
  "late recovery drift rolls back task-owned helper preparation"
assert_not_contains '^adb .* install( |$)|pm clear|pm grant|appops set io\.github\.maxlyth\.hapaneld|settings put|monkey -p io\.github\.maxlyth\.hapaneld|am start -n io\.github\.maxlyth\.hapaneld|/api/v1/config($|[? /])' \
  "$MOCK_CALL_LOG" "late recovery drift refuses before ha-paneld APK, grants or configuration mutation"

MOCK_HOST_DB_PRIMARY='readable:14:ok' MOCK_SWAP_APK_AFTER_CONTRACT_OBSERVATION=3 \
MOCK_DB_CANDIDATE_CONTRACT='hapaneld-db:v1:ha-paneld.db:11:14' \
  run_provision "$MOCK_TARGET" --apk "$APK" --no-tame
assert_failure "candidate byte drift during helper preparation is refused at package time"
assert_contains 'package-time recheck.*candidate APK bytes changed after authentication' \
  "late candidate drift names the package-time byte binding"
assert_log_contains 'helper-transaction-[0-9a-f]+.*rollback-system' \
  "late candidate drift rolls back task-owned helper preparation"
assert_not_contains '^adb .* install( |$)|pm clear|pm grant|appops set io\.github\.maxlyth\.hapaneld|settings put|monkey -p io\.github\.maxlyth\.hapaneld|am start -n io\.github\.maxlyth\.hapaneld|/api/v1/config($|[? /])' \
  "$MOCK_CALL_LOG" "late candidate drift refuses before ha-paneld APK, grants or configuration mutation"

# The last candidate assertion runs after install_apk renews the helper lease. Replacing the path in
# that exact interval must retain package-phase rollback ownership and never start adb install.
LEASE_SWAP_APK="$TMP/ha-paneld-lease-swap.apk"
make_local_apk "$LEASE_SWAP_APK" \
  "$MOCK_HELPER_DIST/armeabi-v7a/hapaneld-helper" \
  "$MOCK_HELPER_DIST/arm64-v8a/hapaneld-helper"
MOCK_HOST_DB_PRIMARY='readable:14:ok' MOCK_SWAP_APK_AFTER_FINAL_GATE_LEASE=1 \
MOCK_DB_CANDIDATE_CONTRACT='hapaneld-db:v1:ha-paneld.db:11:14' \
  run_provision "$MOCK_TARGET" --apk "$LEASE_SWAP_APK" --no-tame
assert_failure "candidate byte drift after final gate and helper lease renewal is refused"
assert_contains 'package-time recheck.*candidate APK bytes changed after authentication' \
  "post-gate candidate drift retains package-time refusal ownership"
assert_log_contains 'helper-transaction-[0-9a-f]+.*rollback-system' \
  "post-gate candidate drift rolls back task-owned helper preparation"
assert_not_contains '^adb .* install( |$)|pm clear|pm grant|appops set io\.github\.maxlyth\.hapaneld|settings put|monkey -p io\.github\.maxlyth\.hapaneld|am start -n io\.github\.maxlyth\.hapaneld|/api/v1/config($|[? /])' \
  "$MOCK_CALL_LOG" "post-gate candidate drift refuses before adb install, grants or configuration mutation"

for late_recovery_state in none 'v14:sidecar' 'v13:readable:13:ok'; do
  MOCK_HOST_DB_PRIMARY='readable:15:ok' MOCK_HOST_DB_RECOVERY='v14:readable:14:ok' \
  MOCK_HOST_DB_RECOVERY_AFTER_SECOND="$late_recovery_state" \
  MOCK_DB_CANDIDATE_CONTRACT='hapaneld-db:v1:ha-paneld.db:11:14' \
    run_provision "$MOCK_TARGET" --apk "$APK" --no-tame
  assert_failure "late selected recovery state $late_recovery_state is refused at package time"
  assert_contains 'package-time recheck' "late selected recovery $late_recovery_state reaches package-time refusal"
  assert_log_contains 'helper-transaction-[0-9a-f]+.*rollback-system' \
    "late selected recovery $late_recovery_state rolls back task-owned helper preparation"
  assert_not_contains '^adb .* install( |$)|pm clear|pm grant|appops set io\.github\.maxlyth\.hapaneld|settings put|monkey -p io\.github\.maxlyth\.hapaneld|am start -n io\.github\.maxlyth\.hapaneld|/api/v1/config($|[? /])' \
    "$MOCK_CALL_LOG" "late selected recovery $late_recovery_state refuses before ha-paneld mutation"
done
unset late_recovery_state

for late_primary_state in missing unreadable; do
  MOCK_HOST_DB_PRIMARY='readable:14:ok' MOCK_HOST_DB_PRIMARY_AFTER_SECOND="$late_primary_state" \
  MOCK_DB_CANDIDATE_CONTRACT='hapaneld-db:v1:ha-paneld.db:11:14' \
    run_provision "$MOCK_TARGET" --apk "$APK" --no-tame
  assert_failure "late primary state $late_primary_state is refused at package time"
  assert_contains 'package-time recheck' "late primary $late_primary_state reaches package-time refusal"
  assert_log_contains 'helper-transaction-[0-9a-f]+.*rollback-system' \
    "late primary $late_primary_state rolls back task-owned helper preparation"
done
unset late_primary_state

MOCK_NO_INSTALLED_PACKAGE=1 MOCK_PM_PATH=fail MOCK_PM_UNINSTALLED_RECORD=absent \
MOCK_HOST_DB_PRIMARY=missing MOCK_HOST_DB_RECOVERY=none MOCK_HOST_DB_RETAINED=0 \
MOCK_PACKAGE_APPEAR_AFTER_DB_OBSERVATION=2 \
MOCK_DB_CANDIDATE_CONTRACT='hapaneld-db:v1:ha-paneld.db:11:14' \
  run_provision "$MOCK_TARGET" --apk "$APK" --no-tame
assert_failure "fresh-install ownership changing to present before package replacement is refused"
assert_contains 'package-time recheck' "late package ownership change reaches package-time refusal"
assert_log_contains 'helper-transaction-[0-9a-f]+.*rollback-system' \
  "late package ownership change rolls back task-owned helper preparation"
assert_not_contains '^adb .* install( |$)|pm clear|pm grant|appops set io\.github\.maxlyth\.hapaneld|settings put|monkey -p io\.github\.maxlyth\.hapaneld|am start -n io\.github\.maxlyth\.hapaneld|/api/v1/config($|[? /])' \
  "$MOCK_CALL_LOG" "late package ownership change refuses before ha-paneld mutation"

MOCK_INSTALLED_CERT=ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff \
  MOCK_DB_CANDIDATE_CONTRACT='hapaneld-db:v1:ha-paneld.db:11:14' \
  MOCK_HOST_DB_PRIMARY='readable:14:ok' run_provision "$MOCK_TARGET" --apk "$APK" --no-tame
assert_failure "a candidate signed by a different key is refused before compatibility metadata can license replacement"
# A different signer is its own refusal (#24): not a database verdict, and not the candidate-boundary
# advice, which describes a manifest this refusal fires before reading.
assert_contains 'the new APK is signed by a different key than the ha-paneld already on this panel' \
  "incumbent signer mismatch has its own headline"
assert_contains 'Installed app signer: f{64}' "the signer refusal shows the installed signer"
assert_contains 'New APK signer: +ac6193307fb0b70113aae205d7549406f96e063bc5491b67b1d5694a34b0e339' \
  "the signer refusal shows the candidate signer"
assert_contains "uninstall io\\.github\\.maxlyth\\.hapaneld +\\(removes the app AND its on-panel config\\)" \
  "the signer refusal gives the signer-change recovery steps"
assert_contains 'No settings backup, database quiescence, reset, helper, Shizuku, APK, permission or configuration mutation was started' \
  "the signer refusal states that nothing was changed"
assert_not_contains 'database compatibility could not be proven|Candidate boundary|Use a candidate that supports' "$LAST_OUTPUT" \
  "a signer mismatch is not reported as a database or candidate-boundary verdict"
assert_not_contains 'config/export|PREPARE_UPGRADE|ha-paneld-db-txn|/data/local/tmp/hapaneld-helper|^adb .* install( |$)|pm clear|pm grant|appops set|settings put|monkey -p io\.github\.maxlyth\.hapaneld|am start -n io\.github\.maxlyth\.hapaneld|/api/v1/config($|[? /])' \
  "$MOCK_CALL_LOG" "incumbent signer mismatch has zero tracked mutations"

# The same refusal at the package-time recheck, after the helper transaction has been prepared: it
# must still roll that transaction back, which the database refusals already did through the same stop.
MOCK_INSTALLED_CERT_FROM_READ=3 \
  MOCK_INSTALLED_CERT_LATE=ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff \
  MOCK_DB_CANDIDATE_CONTRACT='hapaneld-db:v1:ha-paneld.db:11:14' \
  MOCK_HOST_DB_PRIMARY='readable:14:ok' run_provision "$MOCK_TARGET" --apk "$APK" --no-tame
assert_failure "an incumbent signer that changes before package replacement is refused"
assert_contains 'the new APK is signed by a different key' "the late signer change keeps its own headline"
assert_log_contains 'helper-transaction-[0-9a-f]+.*rollback-system' \
  "the late signer change rolls back task-owned helper preparation"
assert_contains 'The task-owned root-helper transaction was rolled back and verified before refusal' \
  "the late signer change reports the verified helper rollback"
assert_not_contains '^adb .* install( |$)' "$MOCK_CALL_LOG" "the late signer change never reaches APK install"

# Android's own rejection of a signer change gives the same recovery steps as the refusal above.
MOCK_APK_INSTALL=fail \
  MOCK_APK_INSTALL_OUTPUT='Failure [INSTALL_FAILED_UPDATE_INCOMPATIBLE: Package io.github.maxlyth.hapaneld signatures do not match newer version; ignoring!]' \
  MOCK_DB_CANDIDATE_CONTRACT='hapaneld-db:v1:ha-paneld.db:11:14' \
  MOCK_HOST_DB_PRIMARY='readable:14:ok' run_provision "$MOCK_TARGET" --apk "$APK" --no-tame
assert_failure "Android's signer-change rejection fails the run"
assert_contains 'install failed: signature mismatch' "Android's signer-change rejection keeps its headline"
assert_contains "uninstall io\\.github\\.maxlyth\\.hapaneld +\\(removes the app AND its on-panel config\\)" \
  "Android's signer-change rejection gives the shared recovery steps"

MOCK_INSTALLED_APK_VERIFY_FAIL=1 \
  MOCK_DB_CANDIDATE_CONTRACT='hapaneld-db:v1:ha-paneld.db:11:14' \
  MOCK_HOST_DB_PRIMARY='readable:14:ok' run_provision "$MOCK_TARGET" --apk "$APK" --no-tame
assert_failure "an unreadable incumbent signer fails closed"
assert_contains 'installed APK signer could not be proven uniquely' "an unreadable incumbent signer names the refusal"
# The candidate's manifest has not been read when this fires, so the refusal must not report its
# database metadata as missing or malformed (#24).
assert_not_contains 'Candidate boundary|Use a candidate that supports' "$LAST_OUTPUT" \
  "a refusal before the manifest is read makes no claim about the candidate boundary"
assert_contains 'nothing about its database support was judged' \
  "a refusal before the manifest is read says the candidate's database support was not judged"
assert_not_contains 'config/export|PREPARE_UPGRADE|ha-paneld-db-txn|/data/local/tmp/hapaneld-helper|^adb .* install( |$)|pm clear|pm grant|appops set|settings put|monkey -p io\.github\.maxlyth\.hapaneld|am start -n io\.github\.maxlyth\.hapaneld|/api/v1/config($|[? /])' \
  "$MOCK_CALL_LOG" "unreadable incumbent signer has zero tracked mutations"

fi
[ "$PROVISION_TEST_SCOPE" != shard-database-host ] || finish_provision_test

if provision_scope_is db core all shard-database-runtime; then
# Rootless panels have a distinct proof route: a same-run refreshed status can establish direct
# compatibility, but a too-new schema can never claim an app-private recovery the host cannot read.
MOCK_ROOT=0 MOCK_STATUS_DB_SCHEMA=14 MOCK_STATUS_DB_QUICK_CHECK=ok \
  MOCK_DB_CANDIDATE_CONTRACT='hapaneld-db:v1:ha-paneld.db:11:14' \
  run_provision "$MOCK_TARGET" --apk "$APK" --no-tame
assert_success "rootless compatible status admits package replacement"
assert_log_contains '/api/v1/status\?database_observation_nonce=[0-9a-f]{32}' \
  "rootless admission requests a nonce-bound same-run status observation"

MOCK_ROOT=0 MOCK_STATUS_DB_SCHEMA=15 MOCK_STATUS_DB_QUICK_CHECK=ok \
  MOCK_DB_CANDIDATE_CONTRACT='hapaneld-db:v1:ha-paneld.db:11:14' \
  run_provision "$MOCK_TARGET" --apk "$APK" --no-tame
assert_failure "rootless too-new database refuses without inspectable recovery"
assert_not_contains 'config/export|PREPARE_UPGRADE|ha-paneld-db-txn|/data/local/tmp/hapaneld-helper|^adb .* install( |$)|pm clear|pm grant|appops set|settings put|monkey -p io\.github\.maxlyth\.hapaneld|am start -n io\.github\.maxlyth\.hapaneld|/api/v1/config($|[? /])' \
  "$MOCK_CALL_LOG" "rootless refusal has zero tracked mutations"

for rootless_nonce_mode in missing wrong malformed duplicate; do
  MOCK_ROOT=0 MOCK_STATUS_DB_SCHEMA=14 MOCK_STATUS_DB_QUICK_CHECK=ok \
  MOCK_STATUS_DB_NONCE="$rootless_nonce_mode" \
  MOCK_DB_CANDIDATE_CONTRACT='hapaneld-db:v1:ha-paneld.db:11:14' \
    run_provision "$MOCK_TARGET" --apk "$APK" --no-tame
  assert_failure "rootless $rootless_nonce_mode observation nonce is refused"
  assert_not_contains 'config/export|PREPARE_UPGRADE|ha-paneld-db-txn|/data/local/tmp/hapaneld-helper|^adb .* install( |$)|pm clear|pm grant|appops set|settings put|monkey -p io\.github\.maxlyth\.hapaneld|am start -n io\.github\.maxlyth\.hapaneld|/api/v1/config($|[? /])' \
    "$MOCK_CALL_LOG" "rootless $rootless_nonce_mode nonce refusal has zero tracked mutations"
done
unset rootless_nonce_mode

for rootless_field_mode in duplicate_schema duplicate_quick; do
  MOCK_ROOT=0 MOCK_STATUS_DB_SCHEMA=14 MOCK_STATUS_DB_QUICK_CHECK=ok \
  MOCK_STATUS_DB_FIELDS="$rootless_field_mode" \
  MOCK_DB_CANDIDATE_CONTRACT='hapaneld-db:v1:ha-paneld.db:11:14' \
    run_provision "$MOCK_TARGET" --apk "$APK" --no-tame
  assert_failure "rootless $rootless_field_mode observation is refused"
  assert_not_contains 'config/export|PREPARE_UPGRADE|ha-paneld-db-txn|/data/local/tmp/hapaneld-helper|^adb .* install( |$)|pm clear|pm grant|appops set|settings put|monkey -p io\.github\.maxlyth\.hapaneld|am start -n io\.github\.maxlyth\.hapaneld|/api/v1/config($|[? /])' \
    "$MOCK_CALL_LOG" "rootless $rootless_field_mode refusal has zero tracked mutations"
done
unset rootless_field_mode

MOCK_ROOT=0 MOCK_NO_INSTALLED_PACKAGE=1 MOCK_PM_PATH=fail MOCK_PM_UNINSTALLED_RECORD=absent \
  MOCK_DB_CANDIDATE_CONTRACT='hapaneld-db:v1:ha-paneld.db:11:14' \
  run_provision "$MOCK_TARGET" --apk "$APK" --no-tame
assert_success "rootless package absence plus empty -u record is a proven fresh install"
assert_log_contains 'pm list packages -u io\.github\.maxlyth\.hapaneld' \
  "rootless fresh proof checks uninstalled retained-data records"

for uninstalled_record_mode in retained malformed fail; do
  MOCK_ROOT=0 MOCK_NO_INSTALLED_PACKAGE=1 MOCK_PM_PATH=fail \
  MOCK_PM_UNINSTALLED_RECORD="$uninstalled_record_mode" \
  MOCK_DB_CANDIDATE_CONTRACT='hapaneld-db:v1:ha-paneld.db:11:14' \
    run_provision "$MOCK_TARGET" --apk "$APK" --no-tame
  assert_failure "rootless $uninstalled_record_mode uninstalled-data observation is refused"
  assert_not_contains 'config/export|PREPARE_UPGRADE|ha-paneld-db-txn|/data/local/tmp/hapaneld-helper|^adb .* install( |$)|pm clear|pm grant|appops set|settings put|monkey -p io\.github\.maxlyth\.hapaneld|am start -n io\.github\.maxlyth\.hapaneld|/api/v1/config($|[? /])' \
    "$MOCK_CALL_LOG" "rootless $uninstalled_record_mode retained-data refusal has zero tracked mutations"
done
unset uninstalled_record_mode

for anchor in HOST_GATE HOST_FIRST_MUTATION HOST_FORCE_POLICY; do
  if grep -Fq "DB_COMPAT_MUTATION_ANCHOR: $anchor" "$PROVISION"; then pass "$anchor mutation-test anchor is present"
  else fail_test "$anchor mutation-test anchor is present"; fi
done
unset anchor

SERVER_SOURCE="$ROOT/app/src/main/kotlin/io/github/maxlyth/hapaneld/http/PaneldServer.kt"
if grep -Fq 'queryParameters["database_observation_nonce"]' "$SERVER_SOURCE" && \
   grep -Fq 'databaseObservationProof(refreshRequested, observationNonce, statusStorage)' "$SERVER_SOURCE" && \
   grep -Fq '"\"database_observation_nonce\":${jsonStr(it)},"' "$SERVER_SOURCE"; then
  pass "host and server share the exact nonce query and top-level response field grammar"
else fail_test "host and server share the exact nonce query and top-level response field grammar"; fi
unset SERVER_SOURCE

# Execute the shipped root observer itself against real SQLite files. The fixture above isolates the
# host parser/decision table; this catches drift inside the generated device program, including the
# no-follow rule, quick_check, retained-state classification and exact newest selectable recovery.
DB_OBSERVER_SOURCE="$TMP/database-compat-observer.sh"
sed -n '/# HAPANELD_DB_COMPAT_OBSERVER_BEGIN/,/# HAPANELD_DB_COMPAT_OBSERVER_END/p' "$PROVISION" > "$DB_OBSERVER_SOURCE"
chmod 700 "$DB_OBSERVER_SOURCE"
DB_OBSERVER_DIR="$TMP/database-compat-observer/data/data/io.github.maxlyth.hapaneld/databases"
mkdir -p "$DB_OBSERVER_DIR"
DB_OBSERVER_DB="$DB_OBSERVER_DIR/ha-paneld.db"
"$HAPANELD_HOST_SQLITE3" "$DB_OBSERVER_DB" 'PRAGMA user_version=15; CREATE TABLE canary(value TEXT); INSERT INTO canary VALUES("primary");'
"$HAPANELD_HOST_SQLITE3" "$DB_OBSERVER_DB.v13.premigrate" 'PRAGMA user_version=13; CREATE TABLE canary(value TEXT);'
"$HAPANELD_HOST_SQLITE3" "$DB_OBSERVER_DB.v14.premigrate" 'PRAGMA user_version=14; CREATE TABLE canary(value TEXT);'
DB_OBSERVER_RUN="$TMP/database-compat-observer-run.sh"
sed -e "s|^db=/data/data/io.github.maxlyth.hapaneld/databases/ha-paneld.db$|db=$DB_OBSERVER_DB|" \
    -e "s|^observer_tmp=@OBSERVER_STAGE@$|observer_tmp=$DB_OBSERVER_DIR/.observer.fixture|" \
    -e 's/^minimum=@MINIMUM@$/minimum=11/' -e 's/^maximum=@MAXIMUM@$/maximum=14/' \
    -e 's/^primary_mode=@PRIMARY_MODE@$/primary_mode=stable/' \
    -e 's/^observer_owner=@OBSERVER_OWNER@$/observer_owner=.owner-fixture/' \
    -e 's/@NONCE@/0123456789abcdef0123456789abcdef/g' \
    "$DB_OBSERVER_SOURCE" > "$DB_OBSERVER_RUN"
DB_OBSERVER_BIN="$DB_OBSERVER_DIR/bin"
DB_OBSERVER_SQLITE_LOG="$DB_OBSERVER_DIR/sqlite-argv.log"
DB_OBSERVER_HOST_SED=""
mkdir -p "$DB_OBSERVER_BIN"
# Target Android 8.1 userspaces do not necessarily provide awk. Give the extracted device program
# only its explicit applets so a host /usr/bin fallback cannot make a forbidden dependency look green.
for db_observer_tool in cp find grep ls mkdir mktemp rm sed sha256sum; do
  db_observer_tool_path="$(PATH=/usr/bin:/bin command -v "$db_observer_tool" 2>/dev/null || true)"
  if [ -n "$db_observer_tool_path" ]; then
    ln -s "$db_observer_tool_path" "$DB_OBSERVER_BIN/$db_observer_tool"
    [ "$db_observer_tool" != sed ] || DB_OBSERVER_HOST_SED="$db_observer_tool_path"
  else
    LAST_OUTPUT="$TMP/observer-missing-tool"
    printf 'missing host test prerequisite: %s\n' "$db_observer_tool" > "$LAST_OUTPUT"
    fail_test "production observer no-awk fixture has its required Android-style tools"
  fi
done
cat > "$DB_OBSERVER_BIN/sqlite3" <<EOF
#!/bin/sh
printf '%s\n' "\$*" >> "$DB_OBSERVER_SQLITE_LOG"
exec "$HAPANELD_HOST_SQLITE3" "\$@"
EOF
chmod 700 "$DB_OBSERVER_BIN/sqlite3"
if PATH="$DB_OBSERVER_BIN" command -v awk >/dev/null 2>&1; then
  fail_test "production observer no-awk fixture excludes awk from the device PATH"
else
  pass "production observer no-awk fixture excludes awk from the device PATH"
fi
observer_output="$(PATH="$DB_OBSERVER_BIN" "$BASH" "$DB_OBSERVER_RUN")"
if printf '%s\n' "$observer_output" 2>/dev/null | grep -qx 'HOSTDB_PRIMARY=readable:15:ok' && \
   printf '%s\n' "$observer_output" 2>/dev/null | grep -qx 'HOSTDB_RECOVERY=v14:readable:14:ok'; then
  pass "production observer reads the actual primary and selects the newest in-bound recovery"
else
  LAST_OUTPUT="$TMP/observer-output"; printf '%s\n' "$observer_output" > "$LAST_OUTPUT"
  fail_test "production observer reads the actual primary and selects the newest in-bound recovery"
fi
observer_primary_sha="$(/usr/bin/sha256sum "$DB_OBSERVER_DB" | awk '{print $1}')"
if printf '%s\n' "$observer_output" 2>/dev/null | grep -Fqx "HOSTDB_PRIMARY_FINGERPRINT=|:$observer_primary_sha" && \
   printf '%s\n' "$observer_output" 2>/dev/null | grep -qx 'HOSTDB_INVENTORY=readable' && \
   printf '%s\n' "$observer_output" 2>/dev/null | grep -Eq 'HOSTDB_INVENTORY_FINGERPRINT=.*ha-paneld\.db\.v13\.premigrate:file:[0-9a-f]{64}.*ha-paneld\.db\.v14\.premigrate:file:[0-9a-f]{64}'; then
  pass "production observer binds exact primary bytes and the complete readable recovery inventory"
else fail_test "production observer binds exact primary bytes and the complete readable recovery inventory"; fi

DB_OBSERVER_LIVE_RUN="$TMP/database-compat-observer-live-run.sh"
sed 's/^primary_mode=stable$/primary_mode=live/' "$DB_OBSERVER_RUN" > "$DB_OBSERVER_LIVE_RUN"
observer_output="$(PATH="$DB_OBSERVER_BIN" "$BASH" "$DB_OBSERVER_LIVE_RUN")"
if printf '%s\n' "$observer_output" 2>/dev/null | grep -qx 'HOSTDB_PRIMARY=readable:15:ok' && \
   grep -Fq "file:$DB_OBSERVER_DB?mode=ro .backup $DB_OBSERVER_DIR/.observer.fixture/observed.db" "$DB_OBSERVER_SQLITE_LOG"; then
  pass "production initial observer uses one coherent read-only online SQLite backup for a live canonical database"
else fail_test "production initial observer uses one coherent read-only online SQLite backup for a live canonical database"; fi
mkdir -p "$DB_OBSERVER_DIR/.observer.fixture"
: > "$DB_OBSERVER_DIR/.observer.fixture/foreign-owner"
if ! PATH="$DB_OBSERVER_BIN" "$BASH" "$DB_OBSERVER_LIVE_RUN" >/dev/null 2>&1 && \
   [ -f "$DB_OBSERVER_DIR/.observer.fixture/foreign-owner" ]; then
  pass "production observer refuses but never removes a stage it did not create"
else fail_test "production observer refuses but never removes a stage it did not create"; fi
rm -rf "$DB_OBSERVER_DIR/.observer.fixture"
observer_cleanup_source="$(sed -n '/^cleanup_root_database_observer()/,/^}/p' "$PROVISION")"
if printf '%s\n' "$observer_cleanup_source" 2>/dev/null | grep -Fq '[ ! -f $remote_stage/$remote_owner ] || rm -rf $remote_stage'; then
  pass "host cleanup removes a remote observer stage only through its nonce-owned marker"
else fail_test "host cleanup removes a remote observer stage only through its nonce-owned marker"; fi

# A failed parser must not turn missing output into proof that SQLite returned exactly two lines.
# Fail only the third selector while SQLite emits a malformed extra line: this was the fail-open seam.
rm -f "$DB_OBSERVER_BIN/sed"
cat > "$DB_OBSERVER_BIN/sed" <<EOF
#!/bin/sh
case "\${2:-}" in
  '3,\$p') exit 1 ;;
esac
exec "$DB_OBSERVER_HOST_SED" "\$@"
EOF
cat > "$DB_OBSERVER_BIN/sqlite3" <<'EOF'
#!/bin/sh
printf '15\nok\nunexpected-extra-line\n'
EOF
chmod 700 "$DB_OBSERVER_BIN/sed" "$DB_OBSERVER_BIN/sqlite3"
observer_output="$(PATH="$DB_OBSERVER_BIN" "$BASH" "$DB_OBSERVER_RUN")"
if printf '%s\n' "$observer_output" 2>/dev/null | grep -qx 'HOSTDB_PRIMARY=unreadable'; then
  pass "production observer fails closed when the extra-output parser fails"
else
  LAST_OUTPUT="$TMP/observer-output"; printf '%s\n' "$observer_output" > "$LAST_OUTPUT"
  fail_test "production observer fails closed when the extra-output parser fails"
fi
rm -f "$DB_OBSERVER_BIN/sed"
ln -s "$DB_OBSERVER_HOST_SED" "$DB_OBSERVER_BIN/sed"
cat > "$DB_OBSERVER_BIN/sqlite3" <<EOF
#!/bin/sh
printf '%s\n' "\$*" >> "$DB_OBSERVER_SQLITE_LOG"
exec "$HAPANELD_HOST_SQLITE3" "\$@"
EOF
chmod 700 "$DB_OBSERVER_BIN/sqlite3"

"$HAPANELD_HOST_SQLITE3" "$DB_OBSERVER_DB.v15.premigrate" 'PRAGMA user_version=15; CREATE TABLE poison(value TEXT);'
observer_output="$(PATH="$DB_OBSERVER_BIN" "$BASH" "$DB_OBSERVER_RUN")"
if printf '%s\n' "$observer_output" 2>/dev/null | grep -qx 'HOSTDB_RECOVERY=v14:readable:14:ok' && \
   printf '%s\n' "$observer_output" 2>/dev/null | grep -Eq 'HOSTDB_INVENTORY_FINGERPRINT=.*ha-paneld\.db\.v15\.premigrate:file:[0-9a-f]{64}'; then
  pass "production observer inventories an out-of-bound newer recovery without selecting it"
else fail_test "production observer inventories an out-of-bound newer recovery without selecting it"; fi
rm -f "$DB_OBSERVER_DB.v15.premigrate"
if [ "$(grep -c 'PRAGMA query_only=ON; PRAGMA user_version; PRAGMA quick_check;' "$DB_OBSERVER_SQLITE_LOG")" -ge 2 ] && \
   ! grep -q -- '^-readonly ' "$DB_OBSERVER_SQLITE_LOG"; then
  pass "production observer applies query_only to every private SQLite candidate without the unsupported CLI flag"
else fail_test "production observer applies query_only to every private SQLite candidate without the unsupported CLI flag"; fi
chmod 400 "$DB_OBSERVER_DB" "$DB_OBSERVER_DB.v13.premigrate" "$DB_OBSERVER_DB.v14.premigrate"
observer_output="$(PATH="$DB_OBSERVER_BIN" "$BASH" "$DB_OBSERVER_RUN")"
if printf '%s\n' "$observer_output" 2>/dev/null | grep -qx 'HOSTDB_PRIMARY=readable:15:ok'; then
  pass "production observer reads a non-writable canonical database without mutation"
else fail_test "production observer reads a non-writable canonical database without mutation"; fi
chmod 600 "$DB_OBSERVER_DB" "$DB_OBSERVER_DB.v13.premigrate" "$DB_OBSERVER_DB.v14.premigrate"
cat > "$DB_OBSERVER_BIN/sqlite3" <<EOF
#!/bin/sh
[ "\${1:-}" != -readonly ] || { echo 'sqlite3: Error: unknown option: -readonly' >&2; exit 1; }
exec "$HAPANELD_HOST_SQLITE3" "\$@"
EOF
chmod 700 "$DB_OBSERVER_BIN/sqlite3"
observer_output="$(PATH="$DB_OBSERVER_BIN" "$BASH" "$DB_OBSERVER_RUN")"
if printf '%s\n' "$observer_output" 2>/dev/null | grep -qx 'HOSTDB_PRIMARY=readable:15:ok' && \
   printf '%s\n' "$observer_output" 2>/dev/null | grep -qx 'HOSTDB_RECOVERY=v14:readable:14:ok'; then
  pass "production observer does not require the unsupported -readonly CLI option"
else fail_test "production observer does not require the unsupported -readonly CLI option"; fi
cat > "$DB_OBSERVER_BIN/sqlite3" <<EOF
#!/bin/sh
exec "$HAPANELD_HOST_SQLITE3" "\$@"
EOF
chmod 700 "$DB_OBSERVER_BIN/sqlite3"
printf 'not sqlite\n' > "$DB_OBSERVER_DB.v14.premigrate"
observer_output="$(PATH="$DB_OBSERVER_BIN" "$BASH" "$DB_OBSERVER_RUN")"
if printf '%s\n' "$observer_output" 2>/dev/null | grep -Eq '^HOSTDB_RECOVERY=v14:(unreadable|readable:.*:bad)$'; then
  pass "production observer does not fall back past a poisoned newest selectable recovery"
else fail_test "production observer does not fall back past a poisoned newest selectable recovery"; fi
rm -f "$DB_OBSERVER_DB.v14.premigrate"
"$HAPANELD_HOST_SQLITE3" "$DB_OBSERVER_DB.v14.premigrate" 'PRAGMA user_version=14; CREATE TABLE canary(value TEXT);'
: > "$DB_OBSERVER_DB.v14.premigrate-journal"
observer_output="$(PATH="$DB_OBSERVER_BIN" "$BASH" "$DB_OBSERVER_RUN")"
if printf '%s\n' "$observer_output" 2>/dev/null | grep -qx 'HOSTDB_RECOVERY=v14:sidecar'; then
  pass "production observer refuses a premigration recovery with a companion journal"
else fail_test "production observer refuses a premigration recovery with a companion journal"; fi
rm -f "$DB_OBSERVER_DB.v14.premigrate-journal"

# WAL mode is persistent in the database header. SQLite may try to create -wal/-shm merely by
# opening a standalone WAL-mode file, even with -readonly; production must inspect only its private
# copy so the recovery inventory it is deciding about remains byte-for-byte unchanged.
rm -f "$DB_OBSERVER_DB.v14.premigrate" "$DB_OBSERVER_DB.v14.premigrate-wal" "$DB_OBSERVER_DB.v14.premigrate-shm"
"$HAPANELD_HOST_SQLITE3" "$DB_OBSERVER_DB.v14.premigrate" \
  'PRAGMA journal_mode=WAL; PRAGMA user_version=14; CREATE TABLE wal_canary(value TEXT); INSERT INTO wal_canary VALUES("standalone");' >/dev/null
wal_source_hash_before="$(/usr/bin/sha256sum "$DB_OBSERVER_DB.v14.premigrate" | awk '{print $1}')"
wal_source_inventory_before="$(find "$DB_OBSERVER_DIR" -maxdepth 1 -name 'ha-paneld.db.v14.premigrate*' -printf '%f\n' | sort)"
observer_output="$(PATH="$DB_OBSERVER_BIN" "$BASH" "$DB_OBSERVER_RUN")"
wal_source_hash_after="$(/usr/bin/sha256sum "$DB_OBSERVER_DB.v14.premigrate" | awk '{print $1}')"
wal_source_inventory_after="$(find "$DB_OBSERVER_DIR" -maxdepth 1 -name 'ha-paneld.db.v14.premigrate*' -printf '%f\n' | sort)"
if printf '%s\n' "$observer_output" 2>/dev/null | grep -qx 'HOSTDB_RECOVERY=v14:readable:14:ok' && \
   [ "$wal_source_hash_before" = "$wal_source_hash_after" ] && \
   [ "$wal_source_inventory_before" = "$wal_source_inventory_after" ] && \
   [ "$wal_source_inventory_after" = 'ha-paneld.db.v14.premigrate' ]; then
  pass "production observer admits a standalone WAL-mode recovery without mutating its source bytes or inventory"
else fail_test "production observer admits a standalone WAL-mode recovery without mutating its source bytes or inventory"; fi

rm -f "$DB_OBSERVER_DB.v14.premigrate"
"$HAPANELD_HOST_SQLITE3" "$DB_OBSERVER_DB.v13.premigrate" 'PRAGMA user_version=13; CREATE TABLE IF NOT EXISTS canary(value TEXT);'
: > "$DB_OBSERVER_DB.v14.premigrate-journal"
observer_output="$(PATH="$DB_OBSERVER_BIN" "$BASH" "$DB_OBSERVER_RUN")"
if printf '%s\n' "$observer_output" 2>/dev/null | grep -qx 'HOSTDB_RECOVERY=v14:incomplete'; then
  pass "production observer lets an orphan newest recovery artifact block fallback"
else fail_test "production observer lets an orphan newest recovery artifact block fallback"; fi
rm -f "$DB_OBSERVER_DB.v14.premigrate-journal"

rm -f "$DB_OBSERVER_DB"
ln -s "$DB_OBSERVER_DIR/missing" "$DB_OBSERVER_DB"
observer_output="$(PATH="$DB_OBSERVER_BIN" "$BASH" "$DB_OBSERVER_RUN")"
if printf '%s\n' "$observer_output" 2>/dev/null | grep -qx 'HOSTDB_PRIMARY=not_regular'; then
  pass "production observer refuses to follow a symlinked canonical database"
else fail_test "production observer refuses to follow a symlinked canonical database"; fi
rm -f "$DB_OBSERVER_DB" "$DB_OBSERVER_DB.v13.premigrate" "$DB_OBSERVER_DB.v14.premigrate"
"$HAPANELD_HOST_SQLITE3" "$DB_OBSERVER_DB.v14.superseded" 'PRAGMA user_version=14;'
observer_output="$(PATH="$DB_OBSERVER_BIN" "$BASH" "$DB_OBSERVER_RUN")"
if printf '%s\n' "$observer_output" 2>/dev/null | grep -qx 'HOSTDB_PRIMARY=missing' && \
   printf '%s\n' "$observer_output" 2>/dev/null | grep -qx 'HOSTDB_RECOVERY=none' && \
   printf '%s\n' "$observer_output" 2>/dev/null | grep -qx 'HOSTDB_RETAINED=1'; then
  pass "production observer treats superseded state as retained but never as automatic recovery"
else fail_test "production observer treats superseded state as retained but never as automatic recovery"; fi
rm -f "$DB_OBSERVER_DB.v14.superseded"
for observer_retained_suffix in -journal .restore.tmp .vbad.premigrate.tmp; do
  : > "$DB_OBSERVER_DB$observer_retained_suffix"
  observer_output="$(PATH="$DB_OBSERVER_BIN" "$BASH" "$DB_OBSERVER_RUN")"
  if printf '%s\n' "$observer_output" 2>/dev/null | grep -qx 'HOSTDB_PRIMARY=missing' && \
     printf '%s\n' "$observer_output" 2>/dev/null | grep -qx 'HOSTDB_RETAINED=1'; then
    pass "production observer retains orphan artifact $observer_retained_suffix against fresh classification"
  else fail_test "production observer retains orphan artifact $observer_retained_suffix against fresh classification"; fi
  rm -f "$DB_OBSERVER_DB$observer_retained_suffix"
done
unset observer_retained_suffix
unset DB_OBSERVER_SOURCE DB_OBSERVER_DIR DB_OBSERVER_DB DB_OBSERVER_RUN DB_OBSERVER_BIN DB_OBSERVER_SQLITE_LOG DB_OBSERVER_HOST_SED observer_output observer_primary_sha
unset wal_source_hash_before wal_source_hash_after wal_source_inventory_before wal_source_inventory_after

if grep -Fq 'HAPANELD_HOST_DB_GATE_V1' "$ROOT/scripts/install.sh" && \
   grep -Fq 'legacy_provisioner_package_verdict' "$ROOT/scripts/install.sh"; then
  pass "checkout-free installer refuses a guardless historical provisioner except on proven fresh install"
else fail_test "checkout-free installer refuses a guardless historical provisioner except on proven fresh install"; fi

: > "$MOCK_CALL_LOG"
printf 'previous installed apk\n' > "$TMP/installed-apk"
LAST_OUTPUT="$TMP/install-legacy-present-output.txt"
MOCK_INSTALLER_RELEASE_API=authenticated MOCK_STATE_DIR="$TMP" \
  bash "$ROOT/scripts/install.sh" --provision panel.test --no-tame > "$LAST_OUTPUT" 2>&1
LAST_STATUS=$?
assert_failure "checkout-free installer blocks an authenticated but guardless provisioner on an existing panel"
assert_contains 'historical script has no database-compatibility gate' "checkout-free existing-panel refusal names the missing gate"
assert_not_contains 'ha-paneld-v0\.9\.3-manual-setup-required\.apk' "$MOCK_CALL_LOG" \
  "checkout-free refusal happens before downloading or executing replacement APK bytes"

: > "$MOCK_CALL_LOG"
rm -f "$TMP/installed-apk"
LAST_OUTPUT="$TMP/install-legacy-fresh-output.txt"
MOCK_INSTALLER_RELEASE_API=authenticated MOCK_NO_INSTALLED_PACKAGE=1 MOCK_PM_PATH=fail MOCK_STATE_DIR="$TMP" \
  bash "$ROOT/scripts/install.sh" --provision panel.test --no-tame > "$LAST_OUTPUT" 2>&1
LAST_STATUS=$?
assert_contains 'eligible only because Android.*proved this is a fresh install' \
  "checkout-free installer preserves the proven-fresh legacy install route"
assert_log_contains 'ha-paneld-v0\.9\.3-manual-setup-required\.apk' \
  "proven-fresh checkout-free install proceeds to the exact APK"

: > "$MOCK_CALL_LOG"
rm -f "$TMP/installed-apk"
LAST_OUTPUT="$TMP/install-legacy-retained-output.txt"
MOCK_INSTALLER_RELEASE_API=authenticated MOCK_NO_INSTALLED_PACKAGE=1 MOCK_PM_PATH=fail \
MOCK_PM_UNINSTALLED_RECORD=retained MOCK_STATE_DIR="$TMP" \
  bash "$ROOT/scripts/install.sh" --provision panel.test --no-tame > "$LAST_OUTPUT" 2>&1
LAST_STATUS=$?
assert_failure "checkout-free installer refuses a guardless provisioner with an uninstalled retained-data record"
assert_contains 'retains an uninstalled ha-paneld package/data record' \
  "checkout-free retained-data refusal names why the install is not fresh"
assert_not_contains 'ha-paneld-v0\.9\.3-manual-setup-required\.apk' "$MOCK_CALL_LOG" \
  "checkout-free retained-data refusal precedes replacement APK download"
if grep -Fq 'bash "$PROVISION" "$t" "${PARGS[@]}" --force' "$UPDATE_FLEET"; then
  pass "fleet workers delegate forced replacements to the guarded provisioner"
else fail_test "fleet workers delegate forced replacements to the guarded provisioner"; fi
: > "$MOCK_CALL_LOG"
printf 'previous installed apk\n' > "$TMP/installed-apk"
LAST_OUTPUT="$TMP/fleet-db-refusal-output.txt"
MOCK_STATE_DIR="$TMP" MOCK_HOST_DB_PRIMARY='readable:15:ok' MOCK_HOST_DB_RECOVERY=none \
MOCK_DB_CANDIDATE_CONTRACT='hapaneld-db:v1:ha-paneld.db:11:14' \
  bash "$UPDATE_FLEET" --apk "$APK" --allow-unsigned-helper --no-tame -- "$MOCK_TARGET" > "$LAST_OUTPUT" 2>&1
LAST_STATUS=$?
assert_failure "fleet --force path cannot bypass database incompatibility"
assert_contains 'database compatibility could not be proven' "fleet surfaces the guarded worker refusal"
assert_not_contains '^adb .* install( |$)|PREPARE_UPGRADE|/data/local/tmp/hapaneld-helper|pm clear|pm grant|appops set|settings put|/api/v1/config($|[? /])' \
  "$MOCK_CALL_LOG" "fleet database refusal has zero tracked worker mutations"

if provision_scope_is db shard-database-runtime; then finish_provision_test; fi
fi

if provision_scope_is core all shard-install-export; then
# A host-only logging request has one durable meaning on fresh and upgraded panels: explicit TCP.
run_provision "$MOCK_TARGET" --apk "$APK" --no-tame --log-host collector.test
assert_success "log host without protocol provisions successfully"
if grep -Fq -- 'log_ship_protocol=syslog-tcp' "$MOCK_CALL_LOG"; then
  pass "log host without protocol persists the TCP default"
else
  fail_test "log host without protocol persists the TCP default"
fi

run_provision "$MOCK_TARGET" --apk "$APK" --no-tame --log-host collector.test --log-proto syslog-udp
assert_success "explicit UDP log transport provisions successfully"
if grep -Fq -- 'log_ship_protocol=syslog-udp' "$MOCK_CALL_LOG"; then
  pass "explicit UDP log transport remains explicit"
else
  fail_test "explicit UDP log transport remains explicit"
fi

run_provision "$MOCK_TARGET" --log-proto invalid
assert_failure "invalid log transport is rejected before panel contact"
assert_contains 'syslog-tcp is the default' "invalid transport guidance names the TCP default"

run_provision "$MOCK_TARGET" --export "$EXPORT"
assert_success "export-only succeeds"
if [ -s "$EXPORT" ]; then pass "export-only writes a non-empty bundle"; else fail_test "export-only writes a non-empty bundle"; fi
if [ "$(stat -c '%a' "$EXPORT")" = 600 ]; then pass "secret export is owner-readable only"; else fail_test "secret export is owner-readable only"; fi
assert_not_contains '^adb .* install( |$)' "$MOCK_CALL_LOG" "export-only never installs an APK"
assert_not_contains '^adb .* (install|shell (settings put|appops set|pm grant|am start|monkey -p io\.github\.maxlyth\.hapaneld))|^curl .* (-X POST|--data|--data-urlencode)' "$MOCK_CALL_LOG" "export-only performs no panel mutation"

FAILED_EXPORT="$TMP/failed-backup.json"
MOCK_EXPORT=fail run_provision "$MOCK_TARGET" --export "$FAILED_EXPORT" --apk "$APK"
assert_failure "failed pre-install backup returns nonzero"
assert_not_contains '^adb .* install( |$)' "$MOCK_CALL_LOG" "failed pre-install backup stops before APK install"
if [ ! -e "$FAILED_EXPORT" ]; then pass "failed backup leaves no misleading output file"; else fail_test "failed backup leaves no misleading output file"; fi

APPROVAL_EXPORT="$TMP/approval-required-backup.json"
MOCK_EXPORT=approval run_provision "$MOCK_TARGET" --export "$APPROVAL_EXPORT" --apk "$APK"
assert_failure "approval-required backup returns nonzero"
assert_contains 'config export requires approval on the panel' "approval-required config export is not reported as successful"
assert_contains 'Review approvals.*approve the config export.*retry the identical --export command' "approval-required config export gives the on-panel approval and retry path"
assert_not_contains '^adb .* install( |$)' "$MOCK_CALL_LOG" "approval-required backup stops before APK install"
if [ ! -e "$APPROVAL_EXPORT" ]; then pass "approval response is not retained as a backup"; else fail_test "approval response is not retained as a backup"; fi

for export_case in malformed-approval unexpected-2xx; do
  REJECTED_EXPORT="$TMP/rejected-${export_case}-backup.json"
  MOCK_EXPORT="$export_case" run_provision "$MOCK_TARGET" --export "$REJECTED_EXPORT" --apk "$APK"
  assert_failure "$export_case backup response returns nonzero"
  assert_contains 'config export returned unexpected HTTP' "$export_case config export response is rejected"
  assert_not_contains '^adb .* install( |$)' "$MOCK_CALL_LOG" "$export_case backup response stops before APK install"
  if [ ! -e "$REJECTED_EXPORT" ]; then pass "$export_case response is not retained as a backup"; else fail_test "$export_case response is not retained as a backup"; fi
done

# The emergency upgrade guard is opt-out only for the transaction tests above. A normal existing-panel
# install must create a unique persistent export before any helper or APK mutation and fail closed if
# that export cannot be obtained.
rm -rf "$TMP/auto-backups"
HAPANELD_SKIP_AUTO_EXPORT=0 run_provision "$MOCK_TARGET" --apk "$APK"
assert_success "ordinary upgrade creates an automatic config backup"
auto_backup_count="$(find "$TMP/auto-backups" -maxdepth 1 -type f -name '*.json' | wc -l | tr -d ' ')"
if [ "$auto_backup_count" = 1 ]; then pass "automatic upgrade backup is unique and persistent"; else fail_test "automatic upgrade backup is unique and persistent"; fi
auto_backup="$(find "$TMP/auto-backups" -maxdepth 1 -type f -name '*.json' | head -1)"
if [ -n "$auto_backup" ] && [ -s "$auto_backup" ] && [ "$(stat -c '%a' "$auto_backup")" = 600 ]; then
  pass "automatic upgrade backup is non-empty and owner-only"
else
  fail_test "automatic upgrade backup is non-empty and owner-only"
fi
export_line="$(grep -n '/api/v1/config/export?include_secrets=1' "$MOCK_CALL_LOG" | head -1 | cut -d: -f1)"
install_line="$(grep -n '^adb .* install' "$MOCK_CALL_LOG" | head -1 | cut -d: -f1)"
if [ -n "$export_line" ] && [ -n "$install_line" ] && [ "$export_line" -lt "$install_line" ]; then
  pass "automatic config backup precedes APK mutation"
else
  fail_test "automatic config backup precedes APK mutation"
fi

# The implicit export is a convenience taken on the way to an in-place replacement, not a gate on it.
# Its failure must still reach the package replacement, and must not leave a file behind that would
# later read as a recovery point which was never produced.
rm -rf "$TMP/auto-backups"
MOCK_EXPORT=fail HAPANELD_SKIP_AUTO_EXPORT=0 run_provision "$MOCK_TARGET" --apk "$APK"
assert_success "implicit backup failure does not block an ordinary replacement"
assert_log_contains '^adb .* install' "implicit backup failure still reaches the APK install"
failed_auto_count="$(find "$TMP/auto-backups" -maxdepth 1 -type f \( -name '*.json' -o -name '*.partial.*' \) | wc -l | tr -d ' ')"
if [ "$failed_auto_count" = 0 ]; then
  pass "a rejected implicit backup is withdrawn rather than published"
else
  fail_test "a rejected implicit backup is withdrawn rather than published ($failed_auto_count left)"
fi
# Withdrawing the file is only half of it: the database receipt must not cite an export that was
# never produced, or a later recovery reads a path that does not exist.
failed_auto_receipt="$(find "$TMP/auto-backups" -maxdepth 1 -type f -name '*.backup-receipt.txt' | head -1)"
if [ -n "$failed_auto_receipt" ] && grep -qx 'settings_export=none' "$failed_auto_receipt"; then
  pass "the database receipt reports no settings export after the implicit export is withdrawn"
else
  fail_test "the database receipt reports no settings export after the implicit export is withdrawn"
fi

# The host-side directory is the other way the implicit export can fail, and it fails before any
# request is made. It must reach the replacement too, or a host-side permissions problem would strand
# every panel on its current build.
UNUSABLE_BACKUP_DIR="$TMP/unusable-backup-dir"
rm -rf "$UNUSABLE_BACKUP_DIR"
ln -s "$TMP/nowhere-at-all" "$UNUSABLE_BACKUP_DIR"
HAPANELD_CONFIG_BACKUP_DIR="$UNUSABLE_BACKUP_DIR" HAPANELD_SKIP_AUTO_EXPORT=0 \
  run_provision "$MOCK_TARGET" --apk "$APK"
assert_success "an unusable host backup directory does not block an ordinary replacement"
assert_contains 'owner-only host backup directory.*could not be prepared' "the unusable backup directory is named"
assert_log_contains '^adb .* install' "an unusable host backup directory still reaches the APK install"
rm -f "$UNUSABLE_BACKUP_DIR"

# The same failure on an explicitly requested --export is the failure of the requested deliverable.
rm -rf "$TMP/auto-backups"
EXPLICIT_STRICT_EXPORT="$TMP/explicit-strict-backup.json"
MOCK_EXPORT=fail HAPANELD_SKIP_AUTO_EXPORT=0 run_provision "$MOCK_TARGET" --export "$EXPLICIT_STRICT_EXPORT" --apk "$APK"
assert_failure "explicit export failure blocks an upgrade"
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
run_provision "$MOCK_TARGET" --export "$SYMLINK_EXPORT"
assert_failure "secret export refuses a symlink destination"
assert_contains 'refusing to replace a symlink' "symlink refusal explains the safe destination requirement"
if [ "$(cat "$SYMLINK_TARGET")" = 'do not replace' ]; then pass "symlink target remains untouched"; else fail_test "symlink target remains untouched"; fi

# Verification is explicitly read-only and must not even attempt installation.
run_provision "$MOCK_TARGET" --verify
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
assert_not_contains '^adb .* (install|shell (settings put|appops set|pm grant|am start|monkey -p io\.github\.maxlyth\.hapaneld))|^curl .* (-X POST|--data|--data-urlencode)' "$MOCK_CALL_LOG" "verify-only performs no panel mutation"

assert_count "$(grep -c -- '--max-time 5 .*/api/v1/config/schema$' "$MOCK_CALL_LOG")" 1 "a ready Configuration schema is read once within a five-second request"

# Grant state left by an earlier case would let a later case pass without granting anything, so every
# run starts without it. A read-only verify creates none, which isolates the reset itself.
printf '1\n' > "$TMP/write-settings-granted"
printf '1\n' > "$TMP/write-settings-granted.stale"
printf 'stale/.Service\n' > "$TMP/accessibility-services"
printf '1\n' > "$TMP/accessibility-enabled"
run_provision "$MOCK_TARGET" --verify
if [ ! -e "$TMP/write-settings-granted" ] && [ ! -e "$TMP/write-settings-granted.stale" ]; then
  pass "each run starts without WRITE_SETTINGS grant state from an earlier case"
else
  fail_test "each run starts without WRITE_SETTINGS grant state from an earlier case"
fi
if [ ! -e "$TMP/accessibility-services" ] && [ ! -e "$TMP/accessibility-enabled" ]; then
  pass "each run starts without accessibility grant state from an earlier case"
else
  fail_test "each run starts without accessibility grant state from an earlier case"
fi

# A verify straight after a deploy can reach the app during its HTTP startup race; that read retries
# after a short pause, recorded here by a sleep that logs instead of waiting.
SCHEMA_SLEEP_DIR="$TMP/schema-retry-sleep"
mkdir -p "$SCHEMA_SLEEP_DIR"
printf '#!/usr/bin/env bash\nprintf "sleep %%s\\n" "$*" >> "%s/calls"\n' "$SCHEMA_SLEEP_DIR" > "$SCHEMA_SLEEP_DIR/sleep"
chmod +x "$SCHEMA_SLEEP_DIR/sleep"
: > "$SCHEMA_SLEEP_DIR/calls"
PATH="$SCHEMA_SLEEP_DIR:$PATH" MOCK_CONFIG_SCHEMA=ready-after-2 run_provision "$MOCK_TARGET" --verify
assert_success "verify-only accepts a Configuration schema that answers on the second attempt"
assert_contains 'Configuration schema: ready' "a schema that answers after the startup race is ready"
assert_count "$(grep -c '/api/v1/config/schema$' "$MOCK_CALL_LOG")" 2 "an unanswered schema read is retried until it answers"
assert_count "$(grep -c '^sleep 1$' "$SCHEMA_SLEEP_DIR/calls")" 1 "the schema retry pauses before its second attempt"

MOCK_CONFIG_SCHEMA=transport-fail run_provision "$MOCK_TARGET" --verify
assert_failure "verify-only rejects an unavailable Configuration schema"
assert_contains 'Configuration schema: unavailable or malformed' "schema transport failure names the broken user surface"
assert_count "$(grep -c '/api/v1/config/schema$' "$MOCK_CALL_LOG")" 4 "a schema that never answers stops after the bounded attempts"

MOCK_CONFIG_SCHEMA=empty run_provision "$MOCK_TARGET" --verify
assert_failure "verify-only rejects an empty Configuration schema after its retries"
assert_contains 'Configuration schema: unavailable or malformed' "an empty schema names the broken user surface"
assert_count "$(grep -c '/api/v1/config/schema$' "$MOCK_CALL_LOG")" 4 "an empty schema body is retried like an unanswered read"

schema_hang_started=$SECONDS
CONFIG_SCHEMA_VERIFY_TIMEOUT_SECONDS=3 MOCK_CONFIG_SCHEMA=hang run_provision "$MOCK_TARGET" --verify
schema_hang_elapsed=$((SECONDS - schema_hang_started))
assert_failure "verify-only rejects a Configuration schema that never responds"
assert_contains 'Configuration schema: unavailable or malformed' "a hanging schema names the broken user surface"
schema_hang_budget="$(grep '/api/v1/config/schema$' "$MOCK_CALL_LOG" | sed -n 's/.*--max-time \([0-9]*\) .*/\1/p' | awk '{ total += $1 } END { print total + 0 }')"
if [ "$schema_hang_budget" -ge 1 ] && [ "$schema_hang_budget" -le 4 ] && [ "$schema_hang_elapsed" -le 10 ]; then
  pass "a hanging schema read is ended by the total verify deadline"
else
  fail_test "a hanging schema read is ended by the total verify deadline (request budget ${schema_hang_budget}s, run ${schema_hang_elapsed}s)"
fi

# A pause is taken only when a one-second request still fits after it. With a two-second deadline and
# pauses that return at once, the first pause fits and the second would leave no time. The retry's
# pauses are whole seconds; the adb deadline helper's 0.1 s polls also reach this shim.
: > "$SCHEMA_SLEEP_DIR/calls"
PATH="$SCHEMA_SLEEP_DIR:$PATH" CONFIG_SCHEMA_VERIFY_TIMEOUT_SECONDS=2 MOCK_CONFIG_SCHEMA=transport-fail \
  run_provision "$MOCK_TARGET" --verify
assert_count "$(grep -c '/api/v1/config/schema$' "$MOCK_CALL_LOG")" 2 "the schema retry stops when no request would fit after the next pause"
assert_count "$(grep -cE '^sleep [123]$' "$SCHEMA_SLEEP_DIR/calls")" 1 "the schema retry never pauses when no request would fit afterwards"

# A pause that overruns must not let a request start after the deadline: this pause fits on paper but
# really takes the whole three-second budget.
SCHEMA_OVERRUN_DIR="$TMP/schema-retry-overrun"
mkdir -p "$SCHEMA_OVERRUN_DIR"
printf '#!/usr/bin/env bash\ncase "$1" in 1|2|3) /bin/sleep 3 ;; *) /bin/sleep "$1" ;; esac\n' > "$SCHEMA_OVERRUN_DIR/sleep"
chmod +x "$SCHEMA_OVERRUN_DIR/sleep"
PATH="$SCHEMA_OVERRUN_DIR:$PATH" CONFIG_SCHEMA_VERIFY_TIMEOUT_SECONDS=3 MOCK_CONFIG_SCHEMA=transport-fail \
  run_provision "$MOCK_TARGET" --verify
assert_failure "verify-only rejects a schema still unavailable when an overrunning pause ends"
assert_count "$(grep -c '/api/v1/config/schema$' "$MOCK_CALL_LOG")" 1 "no schema request starts after an overrunning pause reaches the deadline"

MOCK_CONFIG_SCHEMA=malformed run_provision "$MOCK_TARGET" --verify
assert_failure "verify-only rejects a malformed Configuration schema"
assert_contains 'Configuration schema: unavailable or malformed' "malformed schema names the broken user surface"
assert_count "$(grep -c '/api/v1/config/schema$' "$MOCK_CALL_LOG")" 1 "a malformed schema fails without further requests"

MOCK_CONFIG_SCHEMA=invalid-fields run_provision "$MOCK_TARGET" --verify
assert_failure "verify-only rejects schema entries without string keys and labels"
assert_contains 'Configuration schema: unavailable or malformed' "invalid schema fields name the broken user surface"

MOCK_CONFIG_SCHEMA=reordered run_provision "$MOCK_TARGET" --verify
assert_success "verify-only accepts a compatible schema independent of object-member order"
assert_contains 'Configuration schema: ready' "reordered compatible schema remains usable"

MOCK_POWER_SAFETY=caution run_provision "$MOCK_TARGET" --verify
assert_success "a caution power classification remains advisory"
assert_contains 'panel power safety: caution.*only one observed power guard' "power caution explains the bounded risk"
assert_contains 'Use Repair when offered.*explicitly hidden' "power caution distinguishes repairable from healthy app-only states"
assert_not_contains '^adb .* shell settings put|^curl .* (-X POST|--data|--data-urlencode)' "$MOCK_CALL_LOG" "power warning verification never repairs settings"

MOCK_POWER_SAFETY=unknown run_provision "$MOCK_TARGET" --verify
assert_success "unknown power probes remain an explicit warning"
assert_contains 'panel power safety: unknown.*did not establish an effective guard' "unknown power probes are not reported safe"

MOCK_POWER_SAFETY=at_risk run_provision "$MOCK_TARGET" --verify
assert_failure "an at-risk power classification fails verification"
assert_contains 'panel power safety: at risk.*screen-off can leave this panel unreachable' "at-risk power explains the reachability failure"
assert_contains 'use Repair when offered.*manual guidance' "at-risk power gives capability-aware recovery guidance"
assert_not_contains '^adb .* shell settings put|^curl .* (-X POST|--data|--data-urlencode)' "$MOCK_CALL_LOG" "at-risk verification reports without mutating power settings"

MOCK_POWER_SAFETY=missing run_provision "$MOCK_TARGET" --verify
assert_success "a legacy build without power status remains verifiable"
assert_contains 'panel power safety: status unavailable' "legacy power status is not invented"
assert_contains 'power-safety/state.*no repair was attempted' "legacy power status remains read-only"

MOCK_POWER_SAFETY=malformed run_provision "$MOCK_TARGET" --verify
assert_success "malformed power status remains an explicit advisory warning"
assert_contains 'panel power safety: unrecognised status; not treating it as safe' "malformed power status is not reported safe"

for malformed_safe in truncated-safe garbage-safe duplicate-safe control-safe nul-safe; do
  MOCK_POWER_SAFETY="$malformed_safe" run_provision "$MOCK_TARGET" --verify
  assert_success "$malformed_safe power status remains an explicit advisory warning"
  assert_contains 'panel power safety: unrecognised status; not treating it as safe' "$malformed_safe power status is never reported safe"
  assert_not_contains 'panel power safety: safe' "$LAST_OUTPUT" "$malformed_safe power status cannot produce a green result"
done

MOCK_STORAGE_HEALTH=unchecked run_provision "$MOCK_TARGET" --verify
assert_success "verify-only accepts storage health before the first scheduled check"
assert_contains 'storage health: not checked yet' "unchecked storage health is explained without failing verification"

MOCK_STORAGE_HEALTH=legacy run_provision "$MOCK_TARGET" --verify
assert_success "verify-only remains compatible with panels before storage-health status"
assert_not_contains 'storage health:' "$LAST_OUTPUT" "legacy verification does not invent a storage-health result"

MOCK_STORAGE_HEALTH=legacy-json run_provision "$MOCK_TARGET" --verify
assert_success "verify-only accepts a successful legacy status response without storage health"
assert_not_contains 'storage health:' "$LAST_OUTPUT" "legacy status JSON remains advisory during read-only verification"

MOCK_STORAGE_HEALTH=transport-fail run_provision "$MOCK_TARGET" --verify
assert_failure "verify-only rejects storage-status transport failure"
assert_contains 'status endpoint could not be reached' "read-only transport failure is not mistaken for a legacy response"

MOCK_STORAGE_HEALTH=missing-state run_provision "$MOCK_TARGET" --verify
assert_failure "verify-only rejects a current storage-health object with no state"
assert_contains 'storage health: malformed status response' "missing current-build storage state is identified"

MOCK_STORAGE_HEALTH=malformed run_provision "$MOCK_TARGET" --verify
assert_failure "verify-only rejects malformed current status JSON"
assert_contains 'storage health: malformed status response' "malformed current-build status is identified"

MOCK_STORAGE_HEALTH=future-state run_provision "$MOCK_TARGET" --verify
assert_failure "verify-only rejects an unknown current storage-health state"
assert_contains "storage health: unrecognised state 'future-state'" "unknown current-build storage state is identified"

MOCK_STORAGE_HEALTH=warning run_provision "$MOCK_TARGET" --verify
assert_success "storage warning remains advisory"
assert_contains 'storage health: warning.*pressure is elevated' "storage warning clearly identifies pressure"
assert_contains 'Review panel free space and WAL/database growth.*then check' "storage warning provides a recovery action"

MOCK_STORAGE_HEALTH=critical run_provision "$MOCK_TARGET" --verify
assert_failure "critical storage makes verification fail"
assert_contains 'storage health: critical.*pressure is critical' "critical storage identifies the failing condition"
assert_contains 'Recover panel headroom or address WAL growth before writes fail.*re-run verification' "critical storage failure provides a recovery action"

MOCK_STORAGE_HEALTH=database_failure run_provision "$MOCK_TARGET" --verify
assert_failure "database failure makes verification fail"
assert_contains 'storage health: database failure.*SQLite writes or health checks failed' "database failure identifies the failing condition"
assert_contains 'Preserve ha-paneld\.db.*inspect.*/api/v1/diag.*re-run verification' "database failure protects the database and provides a recovery action"

# An ordinary package replacement is also a recovery attempt: known pressure and database failure
# warn but do not preempt the backup attempt or APK install. Standalone verification above remains
# strict, and unknown/malformed health below still fails closed.
MOCK_STORAGE_HEALTH=critical run_provision "$MOCK_TARGET" --apk "$APK" --no-tame
assert_success "critical storage admits an ordinary replacement as a recovery attempt"
assert_contains 'fixed pressure thresholds are advisory.*recovery attempt' "critical storage clearly records its recovery admission"
assert_marker_captured "critical storage still lets the actual snapshot decide whether the backup fits"
assert_log_contains '^adb .* install' "critical storage does not preempt the APK install"

MOCK_STORAGE_HEALTH=database_failure run_provision "$MOCK_TARGET" --apk "$APK" --no-tame
assert_success "database failure admits an ordinary replacement as a recovery attempt"
assert_contains 'database failure.*admitting this in-place replacement as a recovery attempt' "database failure clearly records its recovery admission"
assert_log_contains '^adb .* install' "database failure does not preempt the APK install"

# Damage that appears only after the replacement is reported, not converted into a failed run: the
# replacement already happened, so a non-zero exit would describe an outcome that did not occur.
MOCK_STORAGE_HEALTH=healthy-then-critical run_provision "$MOCK_TARGET" --apk "$APK" --no-tame
assert_success "new critical pressure after a healthy preflight does not fail a completed replacement"
assert_contains 'storage health: critical.*replacement completed' "new post-install pressure stays visible as a warning"

MOCK_STORAGE_HEALTH=healthy-then-database_failure run_provision "$MOCK_TARGET" --apk "$APK" --no-tame
assert_success "new database failure after a healthy preflight does not fail a completed replacement"
assert_contains 'storage health: database failure.*recovery attempt completed' "new post-install database failure stays visible as a warning"

HAPANELD_RESET_CONFIRM=RESET MOCK_STORAGE_HEALTH=critical \
  run_provision "$MOCK_TARGET" --apk "$APK" --no-tame --reset-config
assert_success "critical storage does not impose a backup gate on a confirmed reset"
assert_contains 'requested reset will intentionally erase ha-paneld state if confirmed' "critical storage explains why reset may proceed"
assert_log_contains '^adb .* pm clear io.github.maxlyth.hapaneld$' "critical storage still reaches the confirmed exact-package reset"

HAPANELD_RESET_CONFIRM=RESET MOCK_STORAGE_HEALTH=database_failure \
  run_provision "$MOCK_TARGET" --apk "$APK" --no-tame --reset-config
assert_success "database failure does not impose a backup gate on a confirmed reset"
assert_contains 'requested reset will intentionally discard the unhealthy database if confirmed' "database failure explains why reset may proceed"
assert_log_contains '^adb .* pm clear io.github.maxlyth.hapaneld$' "database failure still reaches the confirmed exact-package reset"

# A panel whose health cannot be read or understood is a candidate for replacement, not a panel to
# refuse. Standalone verification above still reports both states as failures.
if grep -Fq 'STORAGE_HEALTH_PACKAGE_QUERY_SECONDS="${STORAGE_HEALTH_PACKAGE_QUERY_SECONDS:-15}"' "$PROVISION"; then
  pass "the shipped package-presence proof allows both bounded queries on slower panels"
else
  fail_test "the shipped package-presence proof allows both bounded queries on slower panels"
fi

MOCK_STORAGE_HEALTH=missing-state run_provision "$MOCK_TARGET" --apk "$APK" --no-tame
assert_success "a malformed installed-app storage contract admits an ordinary replacement"
assert_log_contains '^adb .* install' "malformed storage status does not preempt the APK install"

MOCK_STORAGE_HEALTH=future-state run_provision "$MOCK_TARGET" --apk "$APK" --no-tame
assert_success "an unknown installed-app storage state admits an ordinary replacement"
assert_log_contains '^adb .* install' "unknown storage state does not preempt the APK install"

MOCK_STORAGE_HEALTH=unchecked-then-healthy run_provision "$MOCK_TARGET" --apk "$APK" --no-tame
assert_success "post-install verification polls until storage health becomes healthy"
assert_contains 'storage health check is not ready; waiting for a bounded retry' "post-install storage polling explains the wait"
if [ "$(grep -Ec '/api/v1/status$' "$MOCK_CALL_LOG")" -ge 3 ]; then
  pass "post-install storage verification retries the shared status endpoint"
else
  fail_test "post-install storage verification retries the shared status endpoint"
fi

MOCK_STORAGE_HEALTH=always-unchecked run_provision "$MOCK_TARGET" --apk "$APK" --no-tame
assert_success "an indeterminate health check does not fail a completed replacement"
assert_contains 'still not checked after bounded post-install retries' "the incomplete health check stays visible as a warning"

MOCK_STORAGE_HEALTH=transport-fail run_provision "$MOCK_TARGET" --apk "$APK" --no-tame
assert_success "an installed package with unavailable storage status admits an ordinary replacement"
assert_contains "status endpoint could not be reached" "unreachable storage status is reported"
assert_log_contains '^adb .* install' "unreachable storage status does not preempt the APK install"
assert_not_contains 'ha-paneld is not installed on this panel yet' "$LAST_OUTPUT" \
  "an installed panel is not announced as a fresh install"
assert_log_contains 'HAPANELD_PKG_BEGIN:[0-9a-f]{32}' \
  "classification uses one nonce-marked observation, not a sequence of separate queries"

# A clean panel has no status endpoint AND no package, which is exactly the shape v0.9.6 refused to
# install onto: `pm path` reports a missing package as a failure, and that was read as a failed
# package manager (#89). Both observed absence shapes must reach installation — and because the
# marked probe reads the payload rather than the exit status, they are now indistinguishable to it,
# which is the point: the classifier CANNOT depend on which variant a panel happens to use.
#   fail   = the package manager answers, non-zero, naming no path  (the reported NSPanel Pro 120)
#   absent = the package manager answers zero, naming no path
for absent_mode in fail absent; do
  MOCK_STORAGE_HEALTH=transport-fail MOCK_PM_PATH="$absent_mode" MOCK_NO_INSTALLED_PACKAGE=1 \
    run_provision "$MOCK_TARGET" --apk "$APK" --no-tame
  assert_success "a clean panel whose package query reports $absent_mode installs for the first time"
  assert_contains 'ha-paneld is not installed on this panel yet' \
    "the $absent_mode clean panel is named as a fresh install"
  assert_not_contains 'could not determine whether ha-paneld is already installed' "$LAST_OUTPUT" \
    "normal package absence ($absent_mode) is not reported as an unusable package manager"
  # Only the pre-install advisory is forbidden here; post-install verification legitimately reports
  # the same endpoint as unreachable, because the fixture panel never serves it.
  assert_not_contains 'an unreachable app is a reason to replace it' "$LAST_OUTPUT" \
    "a panel with no app installed is not described as an unreachable app ($absent_mode)"
  assert_log_contains 'HAPANELD_PKG_BEGIN:[0-9a-f]{32}' \
    "absence ($absent_mode) is decided from one completed marked observation"
  assert_log_contains '^adb .* install' "a clean panel ($absent_mode) reaches the APK install"
  # Positive control for the refusal contracts below: the very pattern they require to be ABSENT is
  # shown here to match a run that really does mutate the panel, so their silence is evidence rather
  # than a pattern that could never match.
  assert_log_contains '^adb .*( install | push |pm grant|pm clear|appops |settings put|am start)' \
    "the mutation pattern the refusal contracts rely on does match a run that mutates ($absent_mode)"
done

# The fail-closed gate is unchanged, and is now held by the COMPLETENESS of the observation rather
# than by a list of exit statuses. The first four cases each break the proof that the run finished;
# an earlier design that enumerated statuses instead classified an interrupted query as absence and
# installed onto it. The last two break what the package manager actually resolved.
#   hang         : the run never answers, so the deadline expires
#   truncated    : the run is cut off before its END marker, as when it is killed mid-stream
#   stale_nonce  : a complete sequence that belongs to some other run (replayed or buffered output)
#   out_of_order : the markers do not arrive in the order the probe emits them
#   dead_pm      : the run completes but resolves nothing at all, proving nothing
#   empty_path   : the run completes but names a bare `package:` with no path, which is not an answer
#   complete_then_fail : every marker arrives, but the command itself reported failure
# The `child` cases are the ones the previous implementation could not represent: the
# OUTER shell completes perfectly, every marker arrives, and only a command INSIDE it dies. A wrapper
# proving itself complete says nothing about the commands it wrapped, so each child's own status is
# now part of the validated payload. A killed child reports 128+signal and can never be read as the
# panel answering "no such package".
#   child_killed / child_interrupted : the target query dies (137 / 130) while the shell runs on
#   child_error                      : the target query fails for a reason that is not absence
#   child_live_killed                : the framework query dies, so nothing proves the pm answered
#   malformed_path / relative_path   : a `package:` line that is not an absolute, unbroken path
for unknown_case in "probe hang" "probe truncated" "probe stale_nonce" "probe out_of_order" \
                    "probe complete_then_fail" "resolve dead_pm" "resolve empty_path" \
                    "child killed" "child interrupted" "child error" "child live_killed" \
                    "resolve relative_path" "resolve contradictory"; do
  set -- $unknown_case
  kind="$1"; mode="$2"; label="$kind=$mode"
  probe_mode=ok; pm_mode=ok; live_mode=ok
  target_rc=; live_rc=; no_pkg=1
  case "$kind:$mode" in
    # A valid path from a child that reported failure is self-contradictory: the package must NOT be
    # read as installed on an answer the child itself disowned. Needs a real package present, so this
    # is the one case that does not model a clean panel.
    resolve:contradictory) pm_mode=ok; target_rc=1; no_pkg=0 ;;
    probe:*) probe_mode="$mode" ;;
    resolve:dead_pm) pm_mode=fail; live_mode=fail ;;
    resolve:empty_path) pm_mode=empty_path; live_mode=empty_path ;;
    resolve:relative_path) pm_mode=relative_path ;;
    child:killed) pm_mode=fail; target_rc=137 ;;
    child:interrupted) pm_mode=fail; target_rc=130 ;;
    child:error) pm_mode=fail; target_rc=2 ;;
    child:live_killed) pm_mode=fail; live_rc=137 ;;
  esac
  MOCK_STORAGE_HEALTH=transport-fail MOCK_PM_PROBE="$probe_mode" MOCK_PM_PATH="$pm_mode" \
    MOCK_PM_LIVENESS="$live_mode" MOCK_PM_TARGET_RC="$target_rc" MOCK_PM_LIVE_RC="$live_rc" \
    MOCK_NO_INSTALLED_PACKAGE="$no_pkg" \
    run_provision "$MOCK_TARGET" --apk "$APK" --no-tame
  assert_failure "an untrustworthy classification ($label) refuses before any mutation"
  assert_contains 'could not determine whether ha-paneld is already installed' \
    "the refusal ($label) names the undecided classification"
  assert_contains 'Nothing was installed or changed' "the refusal ($label) states what did not happen"
  assert_not_contains 'ha-paneld is not installed on this panel yet' "$LAST_OUTPUT" \
    "an undecided classification ($label) never claims a fresh install"
  # The mutation proof: no APK install, no helper push, no grant, no erase, no appops or settings
  # write and no app launch may appear in the call log. The clean-install case above proves this same
  # pattern DOES match when a run mutates, so passing here means the mutation genuinely did not start.
  # Deliberately not asserted: `adb root` and a config POST, which this scenario never reaches even
  # when it runs to completion, so requiring their absence would pass without exercising anything.
  assert_not_contains '^adb .*( install | push |pm grant|pm clear|appops |settings put|am start)' \
    "$MOCK_CALL_LOG" "no panel mutation is attempted ($label)"
done
unset kind mode label probe_mode pm_mode live_mode target_rc live_rc no_pkg

# Every site that asks "is ha-paneld installed?" was changed, so each is covered directly here rather
# than only through the one that happened to be reported. Leaving storage health healthy means the
# pre-install classification does not run, which isolates each later site in turn.

# The pre-upgrade settings export. A first installation has nothing to export, and would have been
# refused here even after the reported site was fixed.
HAPANELD_SKIP_AUTO_EXPORT=0 MOCK_NO_INSTALLED_PACKAGE=1 MOCK_PM_PATH=fail \
  run_provision "$MOCK_TARGET" --apk "$APK" --no-tame
assert_success "a first installation needs no pre-upgrade settings export"
assert_contains 'no existing config requires an upgrade backup' \
  "the export step names an absent package as a fresh install"
assert_log_contains '^adb .* install' "the skipped export still reaches the APK install"

HAPANELD_SKIP_AUTO_EXPORT=0 MOCK_PM_PROBE=truncated run_provision "$MOCK_TARGET" --apk "$APK" --no-tame
assert_failure "an unverifiable package state stops before the upgrade backup is skipped or taken"
assert_contains 'database compatibility could not be proven: the installed-package state is unknown' \
  "the authoritative pre-export gate names its refusal"
assert_not_contains '^adb .*( install | push )' "$MOCK_CALL_LOG" \
  "the export refusal precedes every helper and APK mutation"

# The reset check taken before the confirmation prompt.
HAPANELD_SKIP_AUTO_EXPORT=1 HAPANELD_RESET_CONFIRM=RESET MOCK_NO_INSTALLED_PACKAGE=1 MOCK_PM_PATH=fail \
  run_provision "$MOCK_TARGET" --apk "$APK" --no-tame --reset-config
assert_success "--reset-config on a panel with nothing installed is not an error"
assert_contains 'there is no configuration to erase' "the reset says plainly that there is nothing to erase"
assert_not_contains 'pm clear' "$MOCK_CALL_LOG" "nothing is erased when no package is installed"

HAPANELD_SKIP_AUTO_EXPORT=1 HAPANELD_RESET_CONFIRM=RESET MOCK_PM_PROBE=truncated \
  run_provision "$MOCK_TARGET" --apk "$APK" --no-tame --reset-config
assert_failure "an unverifiable package state refuses the reset"
assert_contains 'database compatibility could not be proven: the installed-package state is unknown' \
  "the authoritative pre-reset gate names its refusal"
assert_not_contains 'pm clear' "$MOCK_CALL_LOG" "nothing is erased while presence is undecided"

# The re-check taken after the confirmation, which exists because the prompt can be held open
# indefinitely. A target that has genuinely gone must be reported as gone, not as an unusable panel.
HAPANELD_SKIP_AUTO_EXPORT=1 HAPANELD_RESET_CONFIRM=RESET MOCK_PM_VANISH_AFTER=3 \
  run_provision "$MOCK_TARGET" --apk "$APK" --no-tame --reset-config

assert_count() {
  actual="$1"; expected="$2"; description="$3"
  if [ "$actual" -eq "$expected" ] 2>/dev/null; then pass "$description"
  else fail_test "$description (expected $expected, got ${actual:-nothing})"; fi
}

# This case turns on WHICH observation the package vanishes between, so the coupling is asserted
# rather than assumed: the compatibility/version gates make two observations before reset, then reset
# checks before and after confirmation. MOCK_PM_VANISH_AFTER=3 removes the package only at the fourth
# observation, immediately before erasure. If that sequence changes, this fails loudly rather than the
# case below quietly passing for a new reason.
assert_count "$(grep -c 'HAPANELD_PKG_BEGIN' "$MOCK_CALL_LOG")" 4 \
  "a confirmed reset classifies at HOST_GATE, before the prompt, and immediately before erasing"
assert_failure "a reset target that disappears after confirmation is refused"
assert_contains 'no longer installed at the confirmed reset target' \
  "a vanished reset target is named exactly"
assert_not_contains 'could not re-check the installed app before reset' "$LAST_OUTPUT" \
  "a vanished target is not blamed on an unresponsive package manager"
assert_not_contains 'pm clear' "$MOCK_CALL_LOG" "nothing is erased once the target has gone"

# A FAILED first installation must roll the root-helper transaction back. This is the site that made
# absence look inconclusive: the run refused to roll back, and because the retained journal was then
# re-examined by the same conflation, every later run refused to reconcile it too — leaving a live
# helper, no app, and no route back. The panel-side journal already handled "nothing was there".
MOCK_NO_INSTALLED_PACKAGE=1 MOCK_PM_PATH=fail MOCK_APK_INSTALL=fail \
  run_provision "$MOCK_TARGET" --apk "$HELPER_RELEASE_APK" --release-tag v0.9.4-rc1 --no-tame
assert_failure "a failed first installation is reported as a failure"
assert_log_contains 'helper-transaction-[0-9a-f]+.*rollback-system' \
  "a failed FIRST install rolls the helper transaction back instead of stranding the panel"
assert_not_contains 'adb install outcome is ambiguous' "$LAST_OUTPUT" \
  "an absent package is a definite install outcome, never an ambiguous one"

MOCK_STORAGE_HEALTH=legacy-json run_provision "$MOCK_TARGET" --apk "$APK" --no-tame
assert_success "a missing post-install storage contract does not fail a completed replacement"
assert_contains 'installed app did not return the status contract' "current-build missing status stays visible as a warning"

# Pre-plan releases remain verifiable. A 404 is an explicit compatibility result, not a reason to
# discard the established health, permissions and diagnostics checks.
MOCK_PLAN=missing run_provision "$MOCK_TARGET" --verify
assert_success "verify-only accepts a legacy panel without the provisioning-plan endpoint"
assert_contains 'older ha-paneld.*legacy verification checks' "legacy verify explains why profile guidance is unavailable"
assert_contains 'root helper daemon: running' "legacy verify retains the broad helper status"
assert_not_contains '^curl .* (-X POST|--data|--data-urlencode)' "$MOCK_CALL_LOG" "legacy verify remains GET-only"
unset MOCK_PLAN

# A current app that owns the endpoint but remains in 503 is not a legacy compatibility case.
MOCK_PLAN=unstable PROVISIONING_PLAN_TIMEOUT_SECONDS=1 run_provision "$MOCK_TARGET" --verify
assert_failure "verify-only fails while current profile activation remains unstable"
assert_contains 'could not complete profile verification' "unstable verify explains that profile verification is incomplete"
assert_not_contains '^curl .* (-X POST|--data|--data-urlencode)' "$MOCK_CALL_LOG" "unstable verify remains GET-only"
unset MOCK_PLAN PROVISIONING_PLAN_TIMEOUT_SECONDS

MOCK_PLAN=control run_provision "$MOCK_TARGET" --verify
assert_success "verify strips terminal controls from remote provisioning guidance"
if LC_ALL=C grep -q $'\033' "$LAST_OUTPUT"; then fail_test "remote provisioning guidance contains no terminal escape bytes"
else pass "remote provisioning guidance contains no terminal escape bytes"; fi
assert_contains 'Detected panel: Hostile.*forged title' "sanitized provisioning guidance retains readable text"
unset MOCK_PLAN

# A normal local install must work with only portable shell facilities. The fixture PATH deliberately
# supplies failing seq and GNU sort -V implementations; invoking either makes this test fail.
RUN_UNSIGNED_ACK=0 run_provision "$MOCK_TARGET" --apk "$APK"
assert_failure "unsigned local APK cannot install a privileged helper without developer acknowledgement"
assert_contains 're-run with --allow-unsigned-helper' "unsigned helper refusal names the explicit developer flag"
assert_not_contains '^adb .* (push .*/hapaneld-helper|install( |$))' "$MOCK_CALL_LOG" "unsigned helper refusal happens before privileged or package installation"

RUN_UNSIGNED_ACK=0 MOCK_ROOT=0 run_provision "$MOCK_TARGET" --apk "$APK"
assert_success "unrooted local install does not require irrelevant helper acknowledgement"
assert_contains 'no root path available.*continuing without the root helper' "unrooted local install explains why no privileged bytes were used"

run_provision "$MOCK_TARGET" --apk "$APK"
assert_success "successful install completes without seq or GNU sort -V"
if grep -Eq '^adb .* install( |$)' "$MOCK_CALL_LOG"; then pass "successful install invokes adb install"
else fail_test "successful install invokes adb install"; fi
assert_contains 'provisioned' "successful install reports completion"
assert_contains 'inspecting version.*installed ha-paneld package' "install reports installed-version inspection before mutation"
assert_contains 'inspecting access.*root route.*helper compatibility' "install reports privilege and helper inspection"
assert_contains 'Detected panel: Test Panel' "successful install identifies the resolved panel profile"
assert_not_contains '/api/v1/tame' "$MOCK_CALL_LOG" "ordinary install never auto-applies profile recommendations"
start_line="$(grep -nE '^adb .* shell monkey -p io\.github\.maxlyth\.hapaneld -c android\.intent\.category\.LAUNCHER 1$' "$MOCK_CALL_LOG" | head -1 | cut -d: -f1)"
plan_line="$(grep -nE '^curl .*api/v1/provisioning/plan\.txt' "$MOCK_CALL_LOG" | head -1 | cut -d: -f1)"
if [ -n "$start_line" ] && [ -n "$plan_line" ] && [ "$start_line" -lt "$plan_line" ]; then
  pass "profile guidance is read only after the newly installed app is launched"
else
  fail_test "profile guidance is read only after the newly installed app is launched"
fi

# Package replacement on API 27 can leave ha-paneld stopped even though a direct component start was
# requested. Model that state explicitly: only Android's normal package LAUNCHER route clears it, and
# health plus the ordinary post-install verification must then become reachable.
MOCK_STOPPED_STATE=1 run_provision "$MOCK_TARGET" --apk "$APK" --no-tame
assert_success "normal package launcher clears the post-install stopped state"
assert_log_contains '^adb .* shell monkey -p io\.github\.maxlyth\.hapaneld -c android\.intent\.category\.LAUNCHER 1$' "stopped-state recovery uses Android's normal launcher route"
assert_not_contains '^adb .* shell am start -n io\.github\.maxlyth\.hapaneld/\.MainActivity$' "$MOCK_CALL_LOG" "successful launcher recovery does not bypass the normal package route"
install_line="$(grep -nE '^adb .* install -r -g .*ha-paneld\.apk$' "$MOCK_CALL_LOG" | head -1 | cut -d: -f1)"
launcher_line="$(grep -nE '^adb .* shell monkey -p io\.github\.maxlyth\.hapaneld -c android\.intent\.category\.LAUNCHER 1$' "$MOCK_CALL_LOG" | head -1 | cut -d: -f1)"
plan_line="$(grep -nE '^curl .*api/v1/provisioning/plan\.txt' "$MOCK_CALL_LOG" | head -1 | cut -d: -f1)"
if [ -n "$install_line" ] && [ -n "$launcher_line" ] && [ -n "$plan_line" ] && \
   [ "$install_line" -lt "$launcher_line" ] && [ "$launcher_line" -lt "$plan_line" ]; then
  pass "stopped-state recovery launches after install and before verified app-owned guidance"
else
  fail_test "stopped-state recovery launches after install and before verified app-owned guidance"
fi

# A zero launcher exit is not recovery evidence. While the package remains stopped, health must stay
# unavailable and force the distinct direct route, whose successful start makes health reachable.
MOCK_STOPPED_STATE=1 MOCK_LAUNCHER_START=ineffective run_provision "$MOCK_TARGET" --apk "$APK" --no-tame
assert_success "ineffective zero-exit launcher advances to direct stopped-state recovery"
assert_log_contains '^adb .* shell monkey -p io\.github\.maxlyth\.hapaneld -c android\.intent\.category\.LAUNCHER 1$' "zero-exit recovery tries the normal launcher first"
assert_log_contains '^adb .* shell am start -n io\.github\.maxlyth\.hapaneld/\.MainActivity$' "unhealthy zero-exit launcher advances to the direct route"
if [ ! -e "$TMP/package-stopped" ]; then pass "direct fallback actually clears stopped state after an ineffective launcher"
else fail_test "direct fallback actually clears stopped state after an ineffective launcher"; fi

# Launcher failure while stopped must recover the package rather than merely proving that a fallback
# command was invoked. Health and the fixture's stopped-state authority both have to converge.
MOCK_STOPPED_STATE=1 MOCK_LAUNCHER_START=fail run_provision "$MOCK_TARGET" --apk "$APK" --no-tame
assert_success "launcher failure while stopped recovers through the direct route"
assert_log_contains '^adb .* shell am start -n io\.github\.maxlyth\.hapaneld/\.MainActivity$' "launcher command failure invokes the direct component fallback"
if [ ! -e "$TMP/package-stopped" ]; then pass "direct fallback actually clears stopped state after launcher failure"
else fail_test "direct fallback actually clears stopped state after launcher failure"; fi

# The primary launcher is a host ADB command and must not be able to strand provisioning before the
# bounded health state machine. Its deadline must reap the blocked fixture and continue via direct start.
LAUNCHER_PID_FILE="$TMP/blocked-launcher.pid"
MOCK_STOPPED_STATE=1 MOCK_LAUNCHER_START=block MOCK_LAUNCHER_PID_FILE="$LAUNCHER_PID_FILE" \
  APP_LAUNCH_COMMAND_TIMEOUT_SECONDS=1 run_provision "$MOCK_TARGET" --apk "$APK" --no-tame
assert_success "blocked launcher reaches its host deadline and recovers through direct start"
assert_log_contains '^adb .* shell am start -n io\.github\.maxlyth\.hapaneld/\.MainActivity$' "launcher deadline advances to the direct route"
blocked_launcher_pid="$(cat "$LAUNCHER_PID_FILE" 2>/dev/null || true)"
# The deadline fires and the reap follows, but the two are not the same instant: on a loaded
# host the process can still be present for a moment after provisioning has moved on. Give it
# a bounded interval to disappear rather than reading the pid table once.
launcher_reap_attempt=0
while [ -n "$blocked_launcher_pid" ] && kill -0 "$blocked_launcher_pid" 2>/dev/null; do
  launcher_reap_attempt=$((launcher_reap_attempt + 1))
  [ "$launcher_reap_attempt" -lt 300 ] || break
  sleep 0.1
done
if [ -n "$blocked_launcher_pid" ] && processes_gone "$blocked_launcher_pid"; then
  pass "launcher deadline reaps the blocked host ADB process"
else
  fail_test "launcher deadline reaps the blocked host ADB process"
fi

# A settling profile returns 503. The portable client retries it and then displays the final plan.
if grep -Fq 'local timeout="${PROVISIONING_PLAN_TIMEOUT_SECONDS:-$APP_HEALTH_TIMEOUT_SECONDS}"' "$PROVISION" &&
   grep -Fq 'timeout="$APP_HEALTH_TIMEOUT_SECONDS"' "$PROVISION"; then
  pass "provisioning-plan readiness inherits the bounded app-health startup budget"
else
  fail_test "provisioning-plan readiness inherits the bounded app-health startup budget"
fi
MOCK_PLAN=transient run_provision "$MOCK_TARGET" --apk "$APK"
assert_success "install waits through a transient provisioning-plan 503"
assert_contains 'Detected panel: Test Panel' "transient plan readiness eventually renders guidance"
plan_calls="$(grep -Ec '^curl .*api/v1/provisioning/plan\.txt' "$MOCK_CALL_LOG" || true)"
if [ "$plan_calls" -ge 2 ]; then pass "transient plan readiness is retried"
else fail_test "transient plan readiness is retried"; fi
unset MOCK_PLAN

# A release-paired provisioner cannot claim completion if the just-installed app lacks or never
# stabilises the endpoint that owns profile interpretation.
MOCK_PLAN=missing MOCK_WEBVIEW_VERSION=80.0.0.0 run_provision "$MOCK_TARGET" --apk "$APK"
assert_failure "new install fails when its paired provisioning-plan endpoint is missing"
assert_contains 'does not provide.*paired provisioning-plan endpoint|cannot be verified as complete' "missing paired endpoint explains the release contract"
assert_contains 'Root helper: needed only' "missing plan retains conservative helper guidance"
assert_contains 'Do not infer this from the SoC' "missing plan does not infer root authority from chipset"
assert_contains 'system WebView is very old' "missing plan retains the legacy WebView guidance"
assert_not_contains '/api/v1/tame' "$MOCK_CALL_LOG" "missing plan never falls back to automatic taming"
unset MOCK_PLAN MOCK_WEBVIEW_VERSION

MOCK_PLAN=unstable PROVISIONING_PLAN_TIMEOUT_SECONDS=1 run_provision "$MOCK_TARGET" --apk "$APK"
assert_failure "new install fails when profile activation remains 503"
assert_contains 'stayed.*starting|did not finish profile activation' "permanently unstable plan gives a retryable recovery reason"
unset MOCK_PLAN PROVISIONING_PLAN_TIMEOUT_SECONDS

# Recommendations are information, not exit-status gates or implicit consent. The plan replaces the
# blanket helper/WebView messages while it is available.
MOCK_PLAN=recommendations MOCK_WEBVIEW_VERSION=80.0.0.0 run_provision "$MOCK_TARGET" --apk "$APK"
assert_success "unsatisfied profile recommendations do not fail an ordinary install"
assert_contains 'Recommended: install the root helper' "app-owned helper recommendation is displayed"
assert_contains 'Recommended: update System WebView' "app-owned WebView recommendation is displayed"
assert_not_contains 'Root helper: needed only|system WebView is very old' "$LAST_OUTPUT" \
  "available plan suppresses duplicate blanket helper and WebView guidance"
assert_not_contains '/api/v1/tame|action=recommended' "$MOCK_CALL_LOG" "recommendations cause no hidden mutation"
unset MOCK_PLAN MOCK_WEBVIEW_VERSION

fi
[ "$PROVISION_TEST_SCOPE" != shard-install-export ] || finish_provision_test

if provision_scope_is core all shard-install-runtime; then
# ---- host/panel time-zone advisory ---------------------------------------------------------------
# Icing: it states both time zones, warns when they are provably different zones, and is silent about
# everything else. It changes no clock and blocks nothing, so these four runs cover only what would
# matter if it broke — that it speaks when it should, stays quiet when it cannot be sure, and never
# touches the panel or the exit status either way.

run_provision "$MOCK_TARGET" --apk "$APK" --no-tame
assert_success "a matching time zone provisions successfully"
assert_contains 'time zone: this computer and the panel are both Europe/London' \
  "a matching time zone is stated rather than left unsaid"
assert_not_contains '⚠ time zone' "$LAST_OUTPUT" "a matching time zone raises no advisory"

# The case this exists for, and the one this fleet actually has: a panel still on its factory zone.
# Asserted against the call log, not inferred from the wording: the run reaches the install, and
# nothing anywhere in it writes a time zone to the panel.
MOCK_PANEL_TIMEZONE=Asia/Shanghai run_provision "$MOCK_TARGET" --apk "$APK" --no-tame
assert_success "a mismatched time zone still provisions successfully"
assert_contains '⚠ time zone: this computer is Europe/London but the panel is Asia/Shanghai' \
  "a mismatch names both zones"
assert_contains 'installation continues' "the mismatch advisory says it is not blocking"
assert_log_contains '^adb .* install' "a mismatched time zone does not stop the run reaching the APK install"
assert_not_contains 'setprop .*persist\.sys\.timezone|settings put (global|system) (time_zone|auto_time_zone)|service call alarm' \
  "$MOCK_CALL_LOG" "the time-zone advisory changes no clock on the panel"

# Two names for one zone. The database records a link by giving both names the same entry, so this
# must read as agreement; warning about it would train the operator to ignore the advisory.
MOCK_HOST_TZ=Asia/Kolkata MOCK_PANEL_TIMEZONE=Asia/Calcutta run_provision "$MOCK_TARGET" --apk "$APK" --no-tame
assert_success "an aliased time zone provisions successfully"
assert_contains 'time zone: this computer is Asia/Kolkata and the panel is Asia/Calcutta — two names for the same zone' \
  "an alias is reported as one zone under two names"
assert_not_contains '⚠ time zone' "$LAST_OUTPUT" "an alias raises no advisory"

# Anything unreadable is passed over in complete silence, including the value itself: it is untrusted
# input from the panel, so it must not reach the terminal even in a message about not understanding it.
MOCK_PANEL_TIMEZONE='../../etc/passwd' run_provision "$MOCK_TARGET" --apk "$APK" --no-tame
assert_success "an unusable panel time zone provisions successfully"
assert_not_contains 'time zone' "$LAST_OUTPUT" "an unusable panel time zone produces no output at all"
assert_not_contains 'etc/passwd' "$LAST_OUTPUT" "an unusable panel time zone is never echoed back"
assert_log_contains '^adb .* install' "an unusable panel time zone does not stop the run reaching the APK install"

# Config/import may change observations. The core-rendered plan is fetched again only after those
# mutations complete, so its final guidance reflects the resulting panel state.
run_provision "$MOCK_TARGET" --apk "$APK" --id refreshed-panel
assert_success "configured install refreshes profile guidance"
config_line="$(grep -nE '^curl .* -X POST .*api/v1/config$' "$MOCK_CALL_LOG" | tail -1 | cut -d: -f1)"
last_plan_line="$(grep -nE '^curl .*api/v1/provisioning/plan\.txt' "$MOCK_CALL_LOG" | tail -1 | cut -d: -f1)"
plan_calls="$(grep -Ec '^curl .*api/v1/provisioning/plan\.txt' "$MOCK_CALL_LOG" || true)"
if [ "$plan_calls" -ge 2 ] && [ -n "$config_line" ] && [ -n "$last_plan_line" ] && [ "$config_line" -lt "$last_plan_line" ]; then
  pass "profile guidance is rendered again after configuration"
else
  fail_test "profile guidance is rendered again after configuration"
fi

RESTORE_REFRESH="$TMP/restore-refresh.json"
printf '{"kind":"ha-paneld-config","schema":1,"values":{}}\n' > "$RESTORE_REFRESH"
run_provision "$MOCK_TARGET" --apk "$APK" --restore "$RESTORE_REFRESH"
assert_success "restored install refreshes profile guidance"
restore_line="$(grep -nE '^curl .*api/v1/config/import' "$MOCK_CALL_LOG" | tail -1 | cut -d: -f1)"
last_plan_line="$(grep -nE '^curl .*api/v1/provisioning/plan\.txt' "$MOCK_CALL_LOG" | tail -1 | cut -d: -f1)"
if [ -n "$restore_line" ] && [ -n "$last_plan_line" ] && [ "$restore_line" -lt "$last_plan_line" ]; then
  pass "profile guidance is rendered again after restore"
else
  fail_test "profile guidance is rendered again after restore"
fi

# Vendor-strip detection is advisory and runs after verification. A transient late adb failure must not
# overwrite a genuinely successful result (the field report returned adb's 255 after printing success).
MOCK_LATE_ADB_STATUS=255 run_provision "$MOCK_TARGET" --apk "$APK" --no-tame
assert_success "late optional vendor probe cannot turn a verified install into exit 255"
assert_contains 'provisioned and verified' "late optional vendor probe preserves the successful result"
assert_log_contains 'shell getprop ro\.product\.brand$' "late optional vendor probe failure was exercised"

MOCK_LATE_ADB_STATUS=255 MOCK_LATE_ADB_PHASE=persistence MOCK_PRODUCT_IDENTITY=Tuya MOCK_ADB_ROOT=1 \
  run_provision "$MOCK_TARGET" --apk "$APK" --no-tame
assert_success "late vendor-strip safety probe cannot turn a verified install into exit 255"
assert_contains 'vendor-strip skipped' "failed persistence probe keeps vendor stripping fail-closed"
assert_log_contains '^adb .* push .*/hapaneld-helper /data/local/tmp/hapaneld-helper-[0-9a-f]{32}$' "local rooted provisioning stages the helper extracted from the APK under its transaction nonce"
assert_log_contains '^adb .* push .* /data/local/tmp/hapaneld-helper-[0-9a-f]{32}\.txn$' "local rooted provisioning stages its authenticated transaction under the same nonce"
helper_push_line="$(grep -nE '^adb .* push .*/hapaneld-helper /data/local/tmp/hapaneld-helper-[0-9a-f]{32}$' "$MOCK_CALL_LOG" | head -1 | cut -d: -f1)"
local_app_install_line="$(grep -nE '^adb .* install( |$)' "$MOCK_CALL_LOG" | head -1 | cut -d: -f1)"
if [ -n "$helper_push_line" ] && [ -n "$local_app_install_line" ] && [ "$helper_push_line" -lt "$local_app_install_line" ]; then
  pass "root helper is installed before the APK is replaced"
else
  fail_test "root helper is installed before the APK is replaced"
fi

HAPANELD_HELPER_PROBE= run_provision "$MOCK_TARGET" --apk "$APK" --no-tame
assert_success "join-style su validates the daemon through an authenticated root-side client"
assert_log_contains '/data/local/hapaneld-helper --request COMPANIONCAPS' "capability validation runs through the canonical root-side client"
assert_log_contains '/data/local/hapaneld-helper --request BUILDID' "build validation uses the same canonical authenticated client"
assert_log_contains '/data/local/hapaneld-helper --request GUARDCAPS' "managed validation proves the autonomous supervised Guard contract"
assert_log_contains '/data/local/hapaneld-helper --request GUARDSTATUS' "managed validation proves Guard is exactly empty"
assert_not_contains ' forward |/dev/tcp/' "$MOCK_CALL_LOG" "daemon validation does not weaken peer authentication with adb forwarding"

MOCK_ROOT=0 MOCK_PLAN=helper-required run_provision "$MOCK_TARGET" --apk "$APK" --no-tame
assert_success "non-root provisioning continues without a root helper"
assert_contains 'no root path available.*continuing without the root helper' "non-root helper skip is explicit"
assert_not_contains '/data/local/tmp/hapaneld-helper' "$MOCK_CALL_LOG" "non-root provisioning never stages a privileged binary"
assert_contains 'Root helper \[required\]' "the app-owned plan reports the unmet helper requirement"

MOCK_ROOT=0 MOCK_ABI=x86_64 run_provision "$MOCK_TARGET" --apk "$APK" --no-tame
assert_success "unsupported ABI is harmless on a genuinely unrooted panel"
assert_contains 'no root path available.*continuing without the root helper' "unrooted unsupported ABI remains on the reduced-capability path"

MOCK_ABI=x86_64 run_provision "$MOCK_TARGET" --apk "$APK" --no-tame
assert_failure "unsupported ABI fails before changing a rooted panel"
assert_contains 'rooted panel reports unsupported ABI.*x86_64' "unsupported rooted ABI gives the supported asset set"
assert_not_contains '/data/local/tmp/hapaneld-helper|^adb .* install( |$)' "$MOCK_CALL_LOG" "unsupported rooted ABI stops before helper staging or APK replacement"

STALE_HELPER_DIST="$TMP/stale-helper-dist"
mkdir -p "$STALE_HELPER_DIST/arm64-v8a"
printf 'stale helper that must never be staged\nBUILDID %064d\n' 0 > "$STALE_HELPER_DIST/arm64-v8a/hapaneld-helper"
HAPANELD_HELPER_DIST_DIR="$STALE_HELPER_DIST" run_provision "$MOCK_TARGET" --apk "$APK" --no-tame
assert_success "stale helper dist cannot override the helper embedded in a local APK"
assert_not_contains "$STALE_HELPER_DIST" "$MOCK_CALL_LOG" "local provisioning never stages stale helper dist bytes"

ARM64_ONLY_HELPER="$TMP/arm64-only-helper"
printf 'exact APK arm64 selection\nBUILDID %s\n' "$MOCK_HELPER_BUILD_ID" > "$ARM64_ONLY_HELPER"
WRONG_ABI_HELPER="$TMP/wrong-abi-helper"
printf 'wrong ABI helper with invalid identity\n' > "$WRONG_ABI_HELPER"
ABI_APK="$TMP/abi-specific.apk"
make_local_apk "$ABI_APK" \
  "$WRONG_ABI_HELPER" \
  "$ARM64_ONLY_HELPER"
run_provision "$MOCK_TARGET" --apk "$ABI_APK" --no-tame
assert_success "local provisioning selects the exact embedded helper for the panel ABI"
assert_log_contains '^adb .* push .*/hapaneld-helper /data/local/tmp/hapaneld-helper-[0-9a-f]{32}$' "arm64 panel stages the selected APK helper bytes"

MISSING_HELPER_APK="$TMP/missing-helper.apk"
/usr/bin/python3 - "$MISSING_HELPER_APK" <<'PY'
import sys
import zipfile
with zipfile.ZipFile(sys.argv[1], "w") as archive:
    archive.writestr("AndroidManifest.xml", b"test manifest\n")
PY
run_provision "$MOCK_TARGET" --apk "$MISSING_HELPER_APK" --no-tame
assert_failure "rooted local provisioning fails before mutation when the APK helper is missing"
assert_contains 'local APK does not contain its arm64-v8a root helper' "missing embedded helper gives exact build recovery"
assert_not_contains '/data/local/tmp/hapaneld-helper|^adb .* install( |$)' "$MOCK_CALL_LOG" "missing embedded helper leaves the panel untouched"

INVALID_ID_HELPER="$TMP/invalid-id-helper"
printf 'helper without a valid build identity\n' > "$INVALID_ID_HELPER"
INVALID_ID_APK="$TMP/invalid-id.apk"
make_local_apk "$INVALID_ID_APK" \
  "$MOCK_HELPER_DIST/armeabi-v7a/hapaneld-helper" \
  "$INVALID_ID_HELPER"
run_provision "$MOCK_TARGET" --apk "$INVALID_ID_APK" --no-tame
assert_failure "rooted local provisioning rejects an embedded helper with invalid identity"
assert_contains 'embedded in the local APK has an invalid build identity' "invalid embedded identity gives exact recovery"
assert_not_contains '/data/local/tmp/hapaneld-helper|^adb .* install( |$)' "$MOCK_CALL_LOG" "invalid embedded identity fails before panel mutation"

# A local APK carries its own helper, so the identity that must be proven is the one stamped into the
# APK — never the identity of whatever helper sources sit beside the running script. Every case below
# keeps the checkout identity deliberately different from the APK's, because when the two agree (as
# the shared fixtures otherwise arrange) this whole class of defect is invisible.
CHECKOUT_BUILD_ID="$MOCK_HELPER_BUILD_ID"
APK_ONLY_BUILD_ID=1111111111111111111111111111111111111111111111111111111111111111
PRIOR_BUILD_ID=2222222222222222222222222222222222222222222222222222222222222222
if [ "$APK_ONLY_BUILD_ID" != "$CHECKOUT_BUILD_ID" ] && [ "$PRIOR_BUILD_ID" != "$CHECKOUT_BUILD_ID" ] &&
   [ "$APK_ONLY_BUILD_ID" != "$PRIOR_BUILD_ID" ]; then
  pass "moving-checkout helper identities are distinct from the checkout identity"
else
  fail_test "moving-checkout helper identities are distinct from the checkout identity"
fi
MOVING_CHECKOUT_HELPER_DIST="$TMP/moving-checkout-helper-dist"
mkdir -p "$MOVING_CHECKOUT_HELPER_DIST"
printf 'moving-checkout arm helper\nBUILDID %s\n' "$APK_ONLY_BUILD_ID" > "$MOVING_CHECKOUT_HELPER_DIST/hapaneld-helper-arm"
printf 'moving-checkout arm64 helper\nBUILDID %s\n' "$APK_ONLY_BUILD_ID" > "$MOVING_CHECKOUT_HELPER_DIST/hapaneld-helper-arm64"
MOVING_CHECKOUT_APK="$TMP/moving-checkout.apk"
make_local_apk "$MOVING_CHECKOUT_APK" \
  "$MOVING_CHECKOUT_HELPER_DIST/hapaneld-helper-arm" \
  "$MOVING_CHECKOUT_HELPER_DIST/hapaneld-helper-arm64"

# The vc574 fleet defect: the replacement helper started correctly and answered its own identity, but
# the expectation had been re-derived from the installer's checkout, so all eight panels rolled a good
# install back. The staged helper is the authority here, so this install completes.
MOCK_HELPER_BUILD_ID="$APK_ONLY_BUILD_ID" \
  run_provision "$MOCK_TARGET" --apk "$MOVING_CHECKOUT_APK" --no-tame
assert_success "a local APK is verified against its own helper identity, not the installer's checkout"
assert_log_contains 'helper-probe BUILDID' "moving-checkout local provisioning still probes the running daemon identity"
assert_log_contains '^adb .* install( |$)' "$MOCK_CALL_LOG" "a proven replacement identity completes the coupled helper and APK transaction"
assert_not_contains "$CHECKOUT_BUILD_ID" "$MOCK_CALL_LOG" "local provisioning never stages or expects the checkout helper identity"

# The old daemon still owning the socket must stay indistinguishable from any other wrong answer: it
# is not the staged helper, so the transaction fails closed and rolls back. Reading the expectation
# from the staged binary must not become a reason to accept whoever happens to reply.
MOCK_HELPER_BUILD_ID="$PRIOR_BUILD_ID" \
  run_provision "$MOCK_TARGET" --apk "$MOVING_CHECKOUT_APK" --no-tame
assert_failure "a prior daemon still serving the socket cannot satisfy the replacement identity check"
assert_contains 'failed its exact build-identity check; the prior helper was restored' "an unretired prior daemon reports verified rollback"
assert_log_contains 'helper-transaction-[0-9a-f]+.*rollback-system' "an unretired prior daemon invokes the rollback journal"
assert_not_contains '^adb .* install( |$)' "$MOCK_CALL_LOG" "an unretired prior daemon stops before APK replacement"

MOCK_SYSTEM_WRITABLE=0 run_provision "$MOCK_TARGET" --apk "$APK" --no-tame
assert_success "rooted systemless provisioning installs the helper through service.d"
assert_log_contains 'helper-transaction-[0-9a-f]+.*install-systemless' "systemless helper path uses the same transactional installer"
if grep -Fq '/system/bin/stop hapaneld_ledd' "$PROVISION" && grep -Fq '/system/bin/pkill -x hapaneld-ledd' "$PROVISION"; then
  pass "systemless boot service retires the legacy daemon before binding the helper socket"
else
  fail_test "systemless boot service retires the legacy daemon before binding the helper socket"
fi

HAPANELD_HELPER_PROBE= MOCK_SYSTEM_WRITABLE=0 run_provision "$MOCK_TARGET" --apk "$APK" --no-tame
assert_success "systemless validation invokes the exact canonical helper path"
assert_log_contains 'exec /data/local/hapaneld-helper --request COMPANIONCAPS' "systemless validation cannot select a stale legacy helper"
assert_not_contains 'exec /(system/bin|data/adb/hapaneld)/hapaneld-helper --request' "$MOCK_CALL_LOG" "systemless validation never probes an alternate install location"

MOCK_SYSTEM_WRITABLE=0 MOCK_SYSTEMLESS_RUNNER=0 run_provision "$MOCK_TARGET" --apk "$APK" --no-tame
assert_failure "read-only system without a verified service.d runner fails closed"
assert_contains 'read-only /system and no verified systemless boot-service runner' "missing persistence mechanism names the migration blocker"
assert_contains 'Magisk, KernelSU, or APatch' "missing persistence mechanism gives supported recovery choices"
# A rooted panel may need its existing overlay mounted or may still have verity enabled, rather than
# needing another root manager. A real report (#106) reached this exact message and was pointed only
# at Magisk. Naming the reboot-free probe first, then keeping both fallback stages executable, avoids
# that detour without claiming which mechanism is active.
assert_contains 'not a missing root manager' "read-only /system names the usual Android 10+ cause"
assert_contains 'adb -s panel.test:5555 remount' "the reboot-free remount is offered"
assert_contains 'disable-verity' "verity removal remains available as the fallback"
assert_contains 'REBOOTS the panel' "the step that reboots says so before it is followed"
# Order matters more than presence. A TPA10 with a scratch overlay needs only `adb remount`, so
# leading with disable-verity prescribes an unnecessary reboot — and a reboot is the one step that can
# strand a PIN-protected panel in Direct Boot. Pin the reboot-free route ahead of the rebooting one.
remount_line="$(grep -n 'adb -s panel.test:5555 remount' "$LAST_OUTPUT" | head -1 | cut -d: -f1)"
verity_line="$(grep -n 'disable-verity' "$LAST_OUTPUT" | head -1 | cut -d: -f1)"
post_remount_line="$(grep -n 'adb -s panel.test:5555 remount' "$LAST_OUTPUT" | tail -1 | cut -d: -f1)"
if [ -n "$remount_line" ] && [ -n "$verity_line" ] && [ -n "$post_remount_line" ] &&
   [ "$remount_line" -lt "$verity_line" ] && [ "$verity_line" -lt "$post_remount_line" ]; then
  pass "the reboot-free remount is offered before the one that reboots"
else
  fail_test "the reboot-free remount is offered before the one that reboots"
fi
assert_not_contains 'disable-verity.*remount' "$LAST_OUTPUT" "the post-reboot remount remains an executable command"
assert_contains 'unlock any PIN-protected panel' "the remount advice names the Direct Boot unlock step"
assert_not_contains '/data/adb/service\.d/hapaneld-helper\.sh\.new|^adb .* install( |$)' "$MOCK_CALL_LOG" "unverified service.d path never installs a helper or replaces the APK"

# Stock NSPanel Pro firmware can have a writable but full /system. The zero-byte writability probe
# is not enough: capacity must be established before choosing a transactional install authority.
MOCK_SYSTEM_AVAIL_KB=12 run_provision "$MOCK_TARGET" --apk "$APK" --no-tame
assert_success "writable /system without helper capacity installs through the hybrid layout"
assert_contains '/system has 12KB free; [0-9]+KB is required' "hybrid selection explains the capacity-driven layout"
assert_log_contains 'helper-transaction-[0-9a-f]+.*install-hybrid' "full /system selects the hybrid transactional installer"
assert_not_contains 'helper-transaction-[0-9a-f]+.*install-(system|systemless)([^a-z]|$)' "$MOCK_CALL_LOG" "hybrid selection invokes no second boot authority"

# Android 8.1 images commonly provide df and sed but no standalone awk. Capacity detection must use
# only the portable device-side tools and still choose the correct transaction layout.
capacity_fixture_output="$(run_capacity_probe_fixture valid)"
if [ "$capacity_fixture_output" = 'SYSTEM_AVAIL_KB=98676' ]; then
  pass "the exact device parser accepts Android 8.1 df output without awk"
else
  fail_test "the exact device parser accepts Android 8.1 df output without awk"
fi
for capacity_state in missing missing-df missing-sed malformed ambiguous; do
  capacity_fixture_output="$(run_capacity_probe_fixture "$capacity_state")"
  if [ "$capacity_fixture_output" = 'SYSTEM_CAPACITY_UNKNOWN' ]; then
    pass "the exact device parser rejects $capacity_state capacity output"
  else
    fail_test "the exact device parser rejects $capacity_state capacity output"
  fi
done

MOCK_DEVICE_AWK=missing MOCK_SYSTEM_AVAIL_KB=12 run_provision "$MOCK_TARGET" --apk "$APK" --no-tame
assert_success "an awk-less Android shell still determines writable-system capacity"
assert_log_contains 'df -P -k /system/etc/init' \
  "capacity is measured at the directory the transaction writes, not at its parent"
# The write and the size check, not the path: the probe's cleanup `rm -f` names the same path whether
# or not anything was written, so matching the path let a zero-byte probe pass. A mutant proved it.
# Quoting-agnostic on purpose: run_root escapes `"` and `$` before the script reaches adb, so the
# logged text reads `> \"\$system_init_probe\"`. Matching the printf marker and the `-s` test around
# the variable name survives that, and both still vanish when the write is removed.
assert_log_contains 'printf hapaneld-system-init-write-probe > ' \
  "writability is proven with real bytes at the directory the transaction writes, not at its parent"
assert_log_contains '\[ -s [^]]*system_init_probe[^]]*\]' \
  "the route probe checks the bytes actually arrived rather than that a file was created"
assert_not_contains 'df[^|]*/system[^|]*\|[[:space:]]*awk' "$MOCK_CALL_LOG" \
  "capacity probing has no device-side awk dependency"

for capacity_state in missing missing-df missing-sed malformed ambiguous; do
  MOCK_SYSTEM_CAPACITY="$capacity_state" run_provision "$MOCK_TARGET" --apk "$APK" --no-tame
  assert_failure "$capacity_state /system capacity fails closed"
  assert_contains 'system capacity could not be determined safely' \
    "$capacity_state capacity gives a safe retry reason"
  assert_not_contains 'helper-transaction-[0-9a-f]+.*install-(system|systemless|hybrid)|^adb .* install( |$)' "$MOCK_CALL_LOG" \
    "$capacity_state capacity stops before a helper transaction or APK replacement"
done

MOCK_SYSTEM_AVAIL_KB=12 MOCK_VENDOR_INIT_RW=0 run_provision "$MOCK_TARGET" --apk "$APK" --no-tame
assert_failure "full /system without writable vendor init authority fails closed"
assert_contains '/vendor/etc/init.*not writable|writable.*vendor.*init' "vendor-blocked hybrid names the unavailable boot authority"
assert_not_contains 'helper-transaction-[0-9a-f]+.*install-(system|systemless|hybrid)|^adb .* install( |$)' "$MOCK_CALL_LOG" \
  "unwritable vendor init stops before a helper transaction or APK replacement"

MOCK_SYSTEM_AVAIL_KB=1048576 MOCK_VENDOR_RC_STATE=managed MOCK_VENDOR_INIT_RW=0 \
  run_provision "$MOCK_TARGET" --apk "$APK" --no-tame
assert_failure "an established hybrid layout fails closed when its vendor authority becomes unwritable"
assert_contains '/vendor/etc/init.*not writable|writable.*vendor.*init' "unwritable managed hybrid gives a direct recovery reason"
assert_not_contains 'helper-transaction-[0-9a-f]+.*install-(system|systemless|hybrid)|^adb .* install( |$)' "$MOCK_CALL_LOG" \
  "unwritable managed hybrid stops before a helper transaction or APK replacement"

MOCK_SYSTEM_AVAIL_KB=12 MOCK_VENDOR_RC_STATE=unexpected run_provision "$MOCK_TARGET" --apk "$APK" --no-tame
assert_failure "an unexpected vendor startup file is never overwritten"
assert_contains 'unexpected existing /vendor/etc/init/hapaneld-helper\.rc' "unexpected vendor authority is identified"
assert_not_contains 'helper-transaction-[0-9a-f]+.*install-(system|systemless|hybrid)|^adb .* install( |$)' "$MOCK_CALL_LOG" \
  "unexpected vendor authority stops before a helper transaction or APK replacement"

MOCK_SYSTEM_AVAIL_KB=1048576 run_provision "$MOCK_TARGET" --apk "$APK" --no-tame
assert_success "ample writable /system keeps the normal system layout"
assert_log_contains 'helper-transaction-[0-9a-f]+.*install-system' "ample capacity selects the system transactional installer"
assert_not_contains 'helper-transaction-[0-9a-f]+.*install-hybrid' "$MOCK_CALL_LOG" "ample capacity does not create a new hybrid layout"

# Bind the boundary to the exact rc bytes production renders.  Only that boot registration lives on
# /system now: the helper binary and recovery journal are canonical under /data, so retaining the old
# fixed 1MB floor would route healthy panels to hybrid for space they never need.
rendered_system_rc_bytes="$(sed -n '/^  cat > "\$rc_file" <<'\''EOF'\''$/,/^EOF$/p' "$PROVISION" | sed '1d;$d' | wc -c | tr -d ' ')"
system_transaction_floor_kb=$(( (rendered_system_rc_bytes * 2 + 1023) / 1024 + 64 ))
[ "$system_transaction_floor_kb" -ge 128 ] || system_transaction_floor_kb=128
system_transaction_below_kb=$((system_transaction_floor_kb - 1))

MOCK_SYSTEM_AVAIL_KB="$system_transaction_below_kb" run_provision "$MOCK_TARGET" --apk "$APK" --no-tame
assert_success "capacity immediately below the rendered-rc transactional floor selects hybrid"
assert_log_contains 'helper-transaction-[0-9a-f]+.*install-hybrid' \
  "${system_transaction_below_kb}KB remains below the ${system_transaction_floor_kb}KB rendered-rc headroom"

MOCK_SYSTEM_AVAIL_KB="$system_transaction_floor_kb" run_provision "$MOCK_TARGET" --apk "$APK" --no-tame
assert_success "capacity at the rendered-rc transactional floor keeps the system layout"
assert_log_contains 'helper-transaction-[0-9a-f]+.*install-system' \
  "${system_transaction_floor_kb}KB meets the rendered-rc transactional headroom"
assert_not_contains 'helper-transaction-[0-9a-f]+.*install-hybrid' "$MOCK_CALL_LOG" \
  "the exact rendered-rc capacity floor does not route to hybrid"

MOCK_SYSTEM_AVAIL_KB=1048576 MOCK_VENDOR_RC_STATE=managed \
  run_provision "$MOCK_TARGET" --apk "$APK" --no-tame
assert_success "an established hybrid layout remains sticky when /system later has space"
assert_log_contains 'helper-transaction-[0-9a-f]+.*install-hybrid' "sticky hybrid upgrades through its existing authority"
assert_not_contains 'helper-transaction-[0-9a-f]+.*install-system([^l]|$)' "$MOCK_CALL_LOG" "sticky hybrid does not migrate implicitly to system"

HAPANELD_HELPER_PROBE= MOCK_SYSTEM_AVAIL_KB=12 run_provision "$MOCK_TARGET" --apk "$APK" --no-tame
assert_success "hybrid validation invokes the exact canonical helper"
assert_log_contains 'exec /data/local/hapaneld-helper --request COMPANIONCAPS' "hybrid validation probes the canonical helper"
assert_not_contains 'exec /(system/bin|data/adb/hapaneld)/hapaneld-helper --request' "$MOCK_CALL_LOG" "hybrid validation never probes a legacy helper location"

MOCK_MANUAL_STALE=1 run_provision "$MOCK_TARGET" --apk "$APK" --no-tame
assert_failure "provisioning routes an interrupted standalone helper journal back to its owning installer"
assert_contains 'incomplete standalone root-helper installation must be recovered first' "manual-to-provision handoff names the cross-tool boundary"
assert_contains 'helper/install-daemon\.sh' "manual-to-provision handoff gives the exact recovery command"
assert_not_contains '^adb .* push .* /data/local/tmp/hapaneld-helper|^adb .* install( |$)' "$MOCK_CALL_LOG" "manual-to-provision handoff stops before privileged staging or APK replacement"

MOCK_TRANSACTION_TAMPER=1 run_provision "$MOCK_TARGET" --apk "$APK" --no-tame
assert_failure "a transaction substituted after adb staging fails before root execution"
assert_contains 'could not be promoted into protected storage' "substituted transaction names the protected-storage boundary"
assert_not_contains 'helper-transaction-[0-9a-f]+.*install-(system|systemless)|^adb .* install( |$)' "$MOCK_CALL_LOG" "substituted transaction executes no privileged installer and replaces no APK"

MOCK_TRANSACTION_BUSY=1 run_provision "$MOCK_TARGET" --apk "$APK" --no-tame
assert_failure "a live standalone-installer lock blocks provisioner mutation"
assert_contains 'another root-helper transaction is active' "cross-tool lock contention fails closed"
assert_not_contains 'helper-transaction-[0-9a-f]+.*rollback-(system|systemless)|^adb .* install( |$)' "$MOCK_CALL_LOG" "cross-tool contention performs no rollback or APK replacement"

MOCK_APP_REPLACEMENT_HOLD=1 run_provision "$MOCK_TARGET" --apk "$APK" --no-tame
assert_failure "fixed app replacement custody blocks provisioner mutation"
assert_contains 'app-managed root-helper replacement custody blocks provisioning' "fixed app custody has an exact host refusal"
assert_not_contains 'helper-transaction-[0-9a-f]+.*rollback-(system|systemless|hybrid)|^adb .* install( |$)' "$MOCK_CALL_LOG" "fixed app custody performs no rollback or APK replacement"

MOCK_STALE_TRANSACTION=1 MOCK_ACTIVE_TRANSACTION=1 \
  run_provision "$MOCK_TARGET" --apk "$HELPER_RELEASE_APK" --release-tag v0.9.4-rc1 --no-tame
assert_failure "provisioner B cannot recover provisioner A while A's durable lease is active"
assert_contains 'another provisioner still owns the active root-helper transaction' "interleaved provisioner reports the live owner"
assert_not_contains 'helper-transaction-[0-9a-f]+.*(status|rollback|commit)-(system|systemless)|^adb .* install( |$)' "$MOCK_CALL_LOG" "interleaved provisioner cannot inspect destructively, roll back, commit, or replace A's APK"

# Starting with v0.9.4, official releases carry ABI-specific helper assets authenticated by the same
# release key as the APK and provisioner. The helper proof and device-side staging must complete
# before the APK is replaced.
run_provision "$MOCK_TARGET" --apk "$HELPER_RELEASE_APK" --release-tag v0.9.4-rc1 --no-tame
assert_success "current release installs its sealed root-helper asset"
assert_contains 'root helper.*downloading ha-paneld-helper-v0\.9\.4-rc1-arm64-v8a' "release provisioning reports the helper download stage"
assert_contains 'authenticating helper.*v0\.9\.4-rc1.*arm64-v8a' "release provisioning reports helper proof inspection"
assert_contains 'helper compatibility.*authenticated provisioner.*build identity' "release provisioning reports paired helper identity inspection"
assert_log_contains '^curl .*releases/download/v0\.9\.4-rc1/ha-paneld-helper-v0\.9\.4-rc1-arm64-v8a -o ' "release provisioning downloads the exact ABI helper asset"
assert_log_contains '^curl .*ha-paneld-helper-v0\.9\.4-rc1-arm64-v8a\.sha256 -o ' "release provisioning downloads the helper checksum"
assert_log_contains '^curl .*ha-paneld-helper-v0\.9\.4-rc1-arm64-v8a\.sha256\.sig -o ' "release provisioning downloads the helper checksum signature"
assert_log_contains '^openssl dgst -sha256 -verify .* -signature .*/helper\.sha256\.sig .*/helper\.sha256$' "release provisioning authenticates the helper checksum"
assert_contains 'authenticated.*v0\.9\.4-rc1 helper.*arm64-v8a' "release provisioning reports helper authentication"
release_helper_signature_line="$(grep -nE '^openssl dgst -sha256 -verify .*helper\.sha256' "$MOCK_CALL_LOG" | head -1 | cut -d: -f1)"
release_helper_push_line="$(grep -nE '^adb .* push .*ha-paneld-helper-v0\.9\.4-rc1-arm64-v8a /data/local/tmp/hapaneld-helper-[0-9a-f]{32}$' "$MOCK_CALL_LOG" | head -1 | cut -d: -f1)"
release_app_install_line="$(grep -nE '^adb .* install( |$)' "$MOCK_CALL_LOG" | head -1 | cut -d: -f1)"
if [ -n "$release_helper_signature_line" ] && [ -n "$release_helper_push_line" ] && [ -n "$release_app_install_line" ] && \
   [ "$release_helper_signature_line" -lt "$release_helper_push_line" ] && [ "$release_helper_push_line" -lt "$release_app_install_line" ]; then
  pass "release helper is authenticated, staged, then followed by APK installation"
else
  fail_test "release helper is authenticated, staged, then followed by APK installation"
fi

MOCK_ABI=armeabi-v7a \
  run_provision "$MOCK_TARGET" --apk "$HELPER_RELEASE_APK" --release-tag v0.9.4-rc1 --no-tame
assert_success "current release selects the 32-bit helper on armeabi-v7a panels"
assert_log_contains '^curl .*releases/download/v0\.9\.4-rc1/ha-paneld-helper-v0\.9\.4-rc1-armeabi-v7a -o ' "32-bit provisioning downloads only the matching helper asset"
assert_not_contains 'ha-paneld-helper-v0\.9\.4-rc1-arm64-v8a' "$MOCK_CALL_LOG" "32-bit provisioning does not fetch the arm64 helper"

MOCK_HELPER_PROOF_DOWNLOAD=checksum_fail \
  run_provision "$MOCK_TARGET" --apk "$HELPER_RELEASE_APK" --release-tag v0.9.4-rc1 --no-tame
assert_failure "missing helper checksum fails closed"
assert_contains 'could not download the signed helper checksum' "missing helper proof names the incomplete release"
assert_not_contains '/data/local/tmp/hapaneld-helper|^adb .* install( |$)' "$MOCK_CALL_LOG" "missing helper proof stops before helper staging or APK replacement"

MOCK_HELPER_SIGNATURE_FAIL=1 \
  run_provision "$MOCK_TARGET" --apk "$HELPER_RELEASE_APK" --release-tag v0.9.4-rc1 --no-tame
assert_failure "invalid helper checksum signature fails closed"
assert_contains 'helper checksum signature is invalid' "invalid helper signature names the authentication failure"
assert_not_contains '/data/local/tmp/hapaneld-helper|^adb .* install( |$)' "$MOCK_CALL_LOG" "invalid helper signature stops before helper staging or APK replacement"

MOCK_HELPER_CHECKSUM=mismatch \
  run_provision "$MOCK_TARGET" --apk "$HELPER_RELEASE_APK" --release-tag v0.9.4-rc1 --no-tame
assert_failure "helper checksum mismatch fails closed"
assert_contains 'helper checksum does not match its signed record' "helper checksum mismatch names the integrity failure"
assert_not_contains '/data/local/tmp/hapaneld-helper|^adb .* install( |$)' "$MOCK_CALL_LOG" "helper mismatch stops before helper staging or APK replacement"

MOCK_HELPER_START=fail \
  run_provision "$MOCK_TARGET" --apk "$HELPER_RELEASE_APK" --release-tag v0.9.4-rc1 --no-tame
assert_failure "helper start failure leaves the previous APK installed"
assert_contains 'new root helper failed its capability check; the prior helper was restored' "helper start failure names the rollback outcome"
assert_not_contains '^adb .* install( |$)' "$MOCK_CALL_LOG" "helper start failure stops before APK replacement"
assert_log_contains 'helper-transaction-[0-9a-f]+.*rollback-system' "system helper capability failure invokes its rollback journal"
assert_log_contains 'helper-transaction-[0-9a-f]+.*finalize-rollback-system' "verified rollback reaches its identity-gated cleanup point"
if [ ! -f "$TMP/active-helper-transaction" ]; then
  pass "verified provisioner rollback removes its retained recovery state"
else
  fail_test "verified provisioner rollback removes its retained recovery state"
fi

MOCK_HELPER_CAPABILITY=fail MOCK_ROLLBACK_PING=fail \
  run_provision "$MOCK_TARGET" --apk "$HELPER_RELEASE_APK" --release-tag v0.9.4-rc1 --no-tame
assert_failure "failed restored-helper PING leaves rollback unverified"
assert_contains 'rollback could not be verified' "failed rollback PING does not claim restoration"
if [ -f "$TMP/active-helper-transaction" ]; then
  pass "failed provisioner rollback verification retains its durable recovery state"
else
  fail_test "failed provisioner rollback verification retains its durable recovery state"
fi
assert_not_contains 'finalize-rollback-system' "$MOCK_CALL_LOG" "unverified rollback never reaches destructive finalization"
rm -f "$TMP/active-helper-transaction"

MOCK_HELPER_CAPABILITY=fail MOCK_ROLLBACK_RETIREMENT=fail \
  run_provision "$MOCK_TARGET" --apk "$HELPER_RELEASE_APK" --release-tag v0.9.4-rc1 --no-tame
assert_failure "unterminated prior helper leaves rollback unverified"
assert_contains 'rollback could not be verified' "retirement timeout does not claim restoration"
if [ -f "$TMP/active-helper-transaction" ]; then
  pass "retirement timeout retains durable recovery state"
else
  fail_test "retirement timeout retains durable recovery state"
fi
assert_not_contains 'finalize-rollback-system' "$MOCK_CALL_LOG" "retirement timeout never finalizes rollback"
rm -f "$TMP/active-helper-transaction"

HAPANELD_HELPER_PROBE= MOCK_HELPER_CAPABILITY=fail \
  run_provision "$MOCK_TARGET" --apk "$HELPER_RELEASE_APK" --release-tag v0.9.4-rc1 --no-tame
assert_failure "rollback verification does not require the restored legacy helper to implement client mode"
assert_log_contains '/data/adb/hapaneld/\.helper-probe-[0-9a-f]+ --request PING' "rollback PING uses the retained authenticated new client"
assert_not_contains 'exec /system/bin/hapaneld-helper --request PING' "$MOCK_CALL_LOG" "rollback never executes the restored legacy daemon as a client"

MOCK_HELPER_INSTALL=fail \
  run_provision "$MOCK_TARGET" --apk "$HELPER_RELEASE_APK" --release-tag v0.9.4-rc1 --no-tame
assert_failure "helper staging failure leaves the previous APK installed"
assert_contains 'root-helper install failed' "helper staging failure names the incomplete migration"
assert_log_contains 'helper-transaction-[0-9a-f]+.*rollback-system' "failed system transaction preserves or restores the prior helper"
assert_not_contains '^adb .* install( |$)' "$MOCK_CALL_LOG" "failed helper staging does not replace the APK"

# The retirement gate runs before the swap, so a panel that fails it has not been touched: the old
# helper, its boot registration and the installed APK are all still there. Reporting that as a failed
# install with an unverified rollback told the owner to hand-repair a panel that was never changed,
# and it sent the run through a rollback of nothing.
MOCK_HELPER_INSTALL=retirement \
  run_provision "$MOCK_TARGET" --apk "$HELPER_RELEASE_APK" --release-tag v0.9.4-rc1 --no-tame
assert_failure "a helper that cannot be retired fails the install"
assert_contains 'the upgrade did not start' "a pre-swap retirement failure says the upgrade never began"
assert_contains 'Nothing on the panel changed' "an untouched panel is described as untouched"
assert_contains 'The panel reported: RETIREMENT_TIMEOUT helper_pids=4242 ledd_pids=none init_helper=running' \
  "retirement timeout surfaces the surviving pids and init state to the operator"
assert_not_contains 'Restore the helper manually' "$LAST_OUTPUT" \
  "an untouched panel is never told to repair its helper by hand"
assert_not_contains 'helper-transaction-[0-9a-f]+.*rollback-system' "$MOCK_CALL_LOG" \
  "a pre-swap failure does not roll back a panel that was never changed"

# The whole point of the Issue #120 lane was that a failed helper install names itself. It did so on
# the `system` arm only: the `hybrid` and `systemless` arms dropped the detail line on the ordinary
# path where rollback succeeds, which is exactly the path a real user lands on. Layout is chosen by
# writability, so these two runs reach the other two arms without any new fixture.
MOCK_SYSTEM_WRITABLE=0 MOCK_HELPER_INSTALL=retirement \
  run_provision "$MOCK_TARGET" --apk "$HELPER_RELEASE_APK" --release-tag v0.9.4-rc1 --no-tame
assert_failure "a systemless helper that cannot be retired fails the install"
assert_contains 'The panel reported: RETIREMENT_TIMEOUT helper_pids=4242 ledd_pids=none init_helper=running' \
  "a systemless retirement timeout still reaches the operator"
assert_contains 'the upgrade did not start' "a systemless pre-swap retirement failure says the upgrade never began"
assert_not_contains 'Restore the helper manually' "$LAST_OUTPUT" \
  "a systemless panel that was never touched is not told to repair its helper by hand"
assert_not_contains 'helper-transaction-[0-9a-f]+.*rollback-systemless' "$MOCK_CALL_LOG" \
  "a systemless pre-swap failure does not roll back a panel that was never changed"

MOCK_VENDOR_RC_STATE=managed MOCK_HELPER_INSTALL=retirement \
  run_provision "$MOCK_TARGET" --apk "$HELPER_RELEASE_APK" --release-tag v0.9.4-rc1 --no-tame
assert_failure "a hybrid helper that cannot be retired fails the install"
assert_contains 'The panel reported: RETIREMENT_TIMEOUT helper_pids=4242 ledd_pids=none init_helper=running' \
  "a hybrid retirement timeout still reaches the operator"
assert_contains 'the upgrade did not start' "a hybrid pre-swap retirement failure says the upgrade never began"
assert_not_contains 'Restore the helper manually' "$LAST_OUTPUT" \
  "a hybrid panel that was never touched is not told to repair its helper by hand"
assert_not_contains 'helper-transaction-[0-9a-f]+.*rollback-hybrid' "$MOCK_CALL_LOG" \
  "a hybrid pre-swap failure does not roll back a panel that was never changed"

MOCK_SYSTEM_WRITABLE=0 MOCK_HELPER_INSTALL=step_failed \
  run_provision "$MOCK_TARGET" --apk "$HELPER_RELEASE_APK" --release-tag v0.9.4-rc1 --no-tame
assert_failure "a failed systemless staging step fails the install"
assert_contains 'The panel reported: INSTALL_STEP_FAILED' \
  "a systemless staging failure names the step that failed"

MOCK_HELPER_INSTALL=step_failed \
  run_provision "$MOCK_TARGET" --apk "$HELPER_RELEASE_APK" --release-tag v0.9.4-rc1 --no-tame
assert_failure "a failed staging step fails the install"
assert_contains 'The panel reported: INSTALL_STEP_FAILED install_system cp_hapaneld-helper_new' \
  "a failed staging step names itself instead of one generic message"

# Issue #120, reopened 2026-08-27. rc2 cleared the retirement wait and then failed at its first copy,
# and the step name was all a report could carry: it cannot separate a read-only partition from a
# full one, from exhausted inodes, from a directory mounted differently to its parent, from an
# SELinux refusal, from staging that had been swept away. The helper now stages under /data/local
# and only the boot file still goes to /system, so the destinations differ from that report; the
# failure class does not. cp had already printed
# its errno into the same capture both times and the marker grep dropped it, so the answer was in
# the output twice and never reached anywhere it could be read.
MOCK_HELPER_INSTALL=step_failed_diag \
  run_provision "$MOCK_TARGET" --apk "$HELPER_RELEASE_APK" --release-tag v0.9.4-rc1 --no-tame
assert_failure "a failed copy of the staged helper fails the install"
assert_contains 'The panel reported: INSTALL_STEP_FAILED install_system cp_hapaneld-helper_new' \
  "the failing copy still names the step it failed at"
assert_contains 'No space left on device' \
  "the errno the panel printed for the failing copy reaches the operator"
assert_contains 'dir=/data/local mount=/data state=rw availkb=44' \
  "the operator is told what the panel measured at the exact directory it could not write"
assert_contains 'source=/data/local/tmp/hapaneld-helper-[0-9a-f]+ state=verified' \
  "the report separates an authenticated staged file from a destination that refused it"
# This copy happens after the transaction has removed stale recovery copies, so unlike a
# pre-mutation refusal the panel HAS been touched and must be rolled back. The diagnostics have to
# survive that path too, not only the untouched one.
assert_log_contains 'helper-transaction-[0-9a-f]+.*rollback-system' \
  "a copy that failed after staging began still rolls the panel back"
assert_contains 'the prior helper was preserved or restored' \
  "a rolled-back copy failure reports its rollback outcome"
assert_not_contains '^adb .* install( |$)' "$MOCK_CALL_LOG" \
  "a failed copy never replaces the APK"

# The shape of an ordinary v0.9.6-to-rc2 rooted upgrade, with the preflight in place: a panel that
# can take the replacement sees nothing new. A preflight that announced itself on the happy path
# would be a regression in its own right.
run_provision "$MOCK_TARGET" --apk "$HELPER_RELEASE_APK" --release-tag v0.9.4-rc1 --no-tame
assert_success "an ordinary rooted upgrade still completes with the preflight in place"
assert_not_contains 'INSTALL_DIAG' "$LAST_OUTPUT" "a successful upgrade prints no diagnostics"
assert_not_contains 'INSTALL_UNCHANGED' "$LAST_OUTPUT" "a successful upgrade is never described as refused"
assert_log_contains 'helper-transaction-[0-9a-f]+.*install-system' "the ordinary upgrade still takes the system layout"

# A refusal that happens before the transaction's first mutation must not be dressed as a wedged
# helper. The retirement advice sends its reader after a stuck process; a panel whose /system is out
# of room has no stuck process, and following that advice would waste their evening.
MOCK_HELPER_INSTALL=preflight_space \
  run_provision "$MOCK_TARGET" --apk "$HELPER_RELEASE_APK" --release-tag v0.9.4-rc1 --no-tame
assert_failure "a panel without room for the replacement fails the install"
assert_contains 'this panel cannot take the root-helper replacement, so the upgrade did not start' \
  "a capacity refusal is described as a panel that cannot take the replacement"
assert_contains 'Nothing on the panel changed' "a pre-mutation refusal is described as untouched"
assert_contains 'The panel reported: INSTALL_UNCHANGED install_system target_insufficient_space' \
  "a capacity refusal names its own reason"
assert_contains 'does not have room for the replacement plus its recovery copy' \
  "a capacity refusal explains what the panel ran out of"
assert_not_contains 'Restarting the panel clears a wedged helper' "$LAST_OUTPUT" \
  "a capacity refusal is never given the wedged-helper advice"
assert_not_contains 'Restore the helper manually' "$LAST_OUTPUT" \
  "a panel that was never touched is not told to repair its helper by hand"
assert_not_contains 'helper-transaction-[0-9a-f]+.*rollback-system' "$MOCK_CALL_LOG" \
  "a capacity refusal does not roll back a panel that was never changed"

MOCK_HELPER_INSTALL=preflight_readonly \
  run_provision "$MOCK_TARGET" --apk "$HELPER_RELEASE_APK" --release-tag v0.9.4-rc1 --no-tame
assert_failure "a read-only destination fails the install"
assert_contains 'The panel reported: INSTALL_UNCHANGED install_system target_read_only' \
  "a read-only destination names its own reason"
assert_contains 'mounted read-only' "a read-only refusal says the partition is read-only"
assert_contains 'remount' "a read-only refusal offers the reboot-free remount"
assert_not_contains 'Restarting the panel clears a wedged helper' "$LAST_OUTPUT" \
  "a read-only refusal is never given the wedged-helper advice"

# Both other layouts reach the same refusal, and both used to answer with the retirement advice.
MOCK_SYSTEM_WRITABLE=0 MOCK_HELPER_INSTALL=preflight_space \
  run_provision "$MOCK_TARGET" --apk "$HELPER_RELEASE_APK" --release-tag v0.9.4-rc1 --no-tame
assert_failure "a systemless panel without room for the replacement fails the install"
assert_contains 'The panel reported: INSTALL_UNCHANGED install_systemless target_insufficient_space' \
  "a systemless capacity refusal names its own reason"
assert_not_contains 'Restarting the panel clears a wedged helper' "$LAST_OUTPUT" \
  "a systemless capacity refusal is never given the wedged-helper advice"

MOCK_VENDOR_RC_STATE=managed MOCK_HELPER_INSTALL=preflight_space \
  run_provision "$MOCK_TARGET" --apk "$HELPER_RELEASE_APK" --release-tag v0.9.4-rc1 --no-tame
assert_failure "a hybrid panel without room for the replacement fails the install"
assert_contains 'The panel reported: INSTALL_UNCHANGED install_hybrid target_insufficient_space' \
  "a hybrid capacity refusal names its own reason"
assert_not_contains 'Restarting the panel clears a wedged helper' "$LAST_OUTPUT" \
  "a hybrid capacity refusal is never given the wedged-helper advice"

fi
[ "$PROVISION_TEST_SCOPE" != shard-install-runtime ] || finish_provision_test

if provision_scope_is core all shard-helper-transaction; then
# The retirement case must keep its own advice: this is the one INSTALL_UNCHANGED reason where a
# stuck process really is the blocker, and the split above is only correct if it did not take that
# guidance away from the case that needs it.
MOCK_HELPER_INSTALL=retirement \
  run_provision "$MOCK_TARGET" --apk "$HELPER_RELEASE_APK" --release-tag v0.9.4-rc1 --no-tame
assert_failure "a helper that cannot be retired still fails the install"
assert_contains 'Restarting the panel clears a wedged helper' \
  "the retirement refusal keeps the advice that belongs to it"

MOCK_SYSTEM_WRITABLE=0 MOCK_HELPER_CAPABILITY=fail \
  run_provision "$MOCK_TARGET" --apk "$HELPER_RELEASE_APK" --release-tag v0.9.4-rc1 --no-tame
assert_failure "systemless helper capability failure leaves the previous APK installed"
assert_contains 'new root helper failed its capability check; the prior helper was restored' "systemless capability failure reports verified rollback"
assert_log_contains 'helper-transaction-[0-9a-f]+.*rollback-systemless' "systemless capability failure invokes its rollback journal"
assert_not_contains '^adb .* install( |$)' "$MOCK_CALL_LOG" "systemless capability failure stops before APK replacement"

MOCK_SYSTEM_WRITABLE=0 MOCK_HELPER_INSTALL=fail \
  run_provision "$MOCK_TARGET" --apk "$HELPER_RELEASE_APK" --release-tag v0.9.4-rc1 --no-tame
assert_failure "systemless helper install failure leaves the previous APK installed"
assert_contains 'systemless root-helper install failed; the prior helper was preserved or restored' "systemless install failure reports its rollback outcome"
assert_log_contains 'helper-transaction-[0-9a-f]+.*rollback-systemless' "failed systemless transaction invokes its rollback journal"
assert_not_contains '^adb .* install( |$)' "$MOCK_CALL_LOG" "failed systemless transaction does not replace the APK"

MOCK_SYSTEM_AVAIL_KB=12 MOCK_HELPER_INSTALL=fail \
  run_provision "$MOCK_TARGET" --apk "$HELPER_RELEASE_APK" --release-tag v0.9.4-rc1 --no-tame
assert_failure "hybrid helper install failure leaves the previous APK installed"
assert_contains 'hybrid root-helper install failed; the prior helper was preserved or restored' "hybrid install failure reports its rollback outcome"
assert_log_contains 'helper-transaction-[0-9a-f]+.*rollback-hybrid' "failed hybrid transaction invokes its rollback journal"
assert_not_contains '^adb .* install( |$)' "$MOCK_CALL_LOG" "failed hybrid transaction does not replace the APK"

MOCK_SYSTEM_AVAIL_KB=12 MOCK_HELPER_CAPABILITY=fail \
  run_provision "$MOCK_TARGET" --apk "$HELPER_RELEASE_APK" --release-tag v0.9.4-rc1 --no-tame
assert_failure "hybrid helper capability failure leaves the previous APK installed"
assert_contains 'new root helper failed its capability check; the prior helper was restored' "hybrid capability failure reports verified rollback"
assert_log_contains 'helper-transaction-[0-9a-f]+.*rollback-hybrid' "hybrid capability failure invokes its rollback journal"
assert_not_contains '^adb .* install( |$)' "$MOCK_CALL_LOG" "hybrid capability failure stops before APK replacement"

MOCK_HELPER_BUILD_ID_MATCH=fail \
  run_provision "$MOCK_TARGET" --apk "$HELPER_RELEASE_APK" --release-tag v0.9.4-rc1 --no-tame
assert_failure "helper build-identity mismatch leaves the previous APK installed"
assert_contains 'failed its exact build-identity check; the prior helper was restored' "helper identity mismatch reports verified rollback"
assert_log_contains 'helper-probe BUILDID' "provisioning probes the running daemon build identity"
assert_log_contains 'helper-transaction-[0-9a-f]+.*rollback-system' "helper identity mismatch invokes the rollback journal"
assert_not_contains '^adb .* install( |$)' "$MOCK_CALL_LOG" "helper identity mismatch stops before APK replacement"

MOCK_HELPER_CAPABILITY=fail MOCK_ROLLBACK_RESULT=fail \
  run_provision "$MOCK_TARGET" --apk "$HELPER_RELEASE_APK" --release-tag v0.9.4-rc1 --no-tame
assert_failure "missing or invalid recovery snapshot fails closed"
assert_contains 'rollback could not be verified' "unverifiable recovery is reported without claiming restoration"
assert_not_contains '^adb .* install( |$)' "$MOCK_CALL_LOG" "unverifiable recovery still stops before APK replacement"

MOCK_APK_INSTALL=fail \
  run_provision "$MOCK_TARGET" --apk "$HELPER_RELEASE_APK" --release-tag v0.9.4-rc1 --no-tame
assert_failure "APK package-manager failure rolls back the already-verified new helper"
assert_contains 'prior root helper was restored and verified' "APK failure reports helper rollback"
assert_log_contains 'helper-transaction-[0-9a-f]+.*rollback-system' "APK failure invokes the retained helper rollback journal"
assert_not_contains 'helper-transaction-[0-9a-f]+.*commit-system' "$MOCK_CALL_LOG" "APK failure never commits helper recovery"

MOCK_SYSTEM_WRITABLE=0 MOCK_APK_INSTALL=fail \
  run_provision "$MOCK_TARGET" --apk "$HELPER_RELEASE_APK" --release-tag v0.9.4-rc1 --no-tame
assert_failure "APK failure also rolls back a systemless helper upgrade"
assert_contains 'prior root helper was restored and verified' "systemless APK failure reports helper rollback"
assert_log_contains 'helper-transaction-[0-9a-f]+.*rollback-systemless' "systemless APK failure invokes the retained rollback journal"
assert_not_contains 'helper-transaction-[0-9a-f]+.*commit-systemless' "$MOCK_CALL_LOG" "systemless APK failure never commits helper recovery"

MOCK_SYSTEM_AVAIL_KB=12 MOCK_APK_INSTALL=fail \
  run_provision "$MOCK_TARGET" --apk "$HELPER_RELEASE_APK" --release-tag v0.9.4-rc1 --no-tame
assert_failure "APK failure also rolls back a hybrid helper upgrade"
assert_contains 'prior root helper was restored and verified' "hybrid APK failure reports helper rollback"
assert_log_contains 'helper-transaction-[0-9a-f]+.*rollback-hybrid' "hybrid APK failure invokes the retained rollback journal"
assert_not_contains 'helper-transaction-[0-9a-f]+.*commit-hybrid' "$MOCK_CALL_LOG" "hybrid APK failure never commits helper recovery"

APK_INSTALL_PID_FILE="$TMP/apk-install-hang.pid"
MOCK_APK_INSTALL=ignore_term MOCK_APK_INSTALL_PID_FILE="$APK_INSTALL_PID_FILE" \
APK_INSTALL_TIMEOUT_SECONDS=1 \
  run_provision "$MOCK_TARGET" --apk "$HELPER_RELEASE_APK" --release-tag v0.9.4-rc1 --no-tame
assert_failure "stuck main APK install returns nonzero at its host deadline"
assert_contains 'install did not finish within the 1s safety deadline' "main APK timeout names the bounded failed step"
apk_install_pid="$(cat "$APK_INSTALL_PID_FILE" 2>/dev/null || true)"
if [ -n "$apk_install_pid" ] && processes_gone "$apk_install_pid"; then
  pass "main APK timeout reaps the blocked adb install"
else
  fail_test "main APK timeout reaps the blocked adb install"
fi
if [ "$(grep -Ec '^adb .* install( |$)' "$MOCK_CALL_LOG")" -eq 1 ]; then
  pass "ambiguous main APK timeout is not retried with different install flags"
else
  fail_test "ambiguous main APK timeout is not retried with different install flags"
fi
assert_log_contains 'helper-transaction-[0-9a-f]+.*rollback-system' "main APK timeout reconciles and rolls back the retained helper transaction"

HYBRID_APK_INSTALL_PID_FILE="$TMP/hybrid-apk-install-hang.pid"
MOCK_SYSTEM_AVAIL_KB=12 MOCK_APK_INSTALL=ignore_term MOCK_APK_INSTALL_PID_FILE="$HYBRID_APK_INSTALL_PID_FILE" \
APK_INSTALL_TIMEOUT_SECONDS=1 \
  run_provision "$MOCK_TARGET" --apk "$HELPER_RELEASE_APK" --release-tag v0.9.4-rc1 --no-tame
assert_failure "stuck APK install on the hybrid path returns nonzero at its host deadline"
assert_contains 'install did not finish within the 1s safety deadline' "hybrid APK timeout retains the bounded failure reason"
hybrid_apk_install_pid="$(cat "$HYBRID_APK_INSTALL_PID_FILE" 2>/dev/null || true)"
if [ -n "$hybrid_apk_install_pid" ] && processes_gone "$hybrid_apk_install_pid"; then
  pass "hybrid APK timeout reaps the blocked adb install"
else
  fail_test "hybrid APK timeout reaps the blocked adb install"
fi
assert_log_contains 'helper-transaction-[0-9a-f]+.*rollback-hybrid' "hybrid APK timeout reconciles and rolls back its retained transaction"

MOCK_APK_INSTALL=grant_flag_unsupported \
  run_provision "$MOCK_TARGET" --apk "$HELPER_RELEASE_APK" --release-tag v0.9.4-rc1 --no-tame
assert_success "exact unsupported grant-all diagnostic retries a plain install"
if [ "$(grep -Ec '^adb .* install( |$)' "$MOCK_CALL_LOG")" -eq 2 ]; then
  pass "grant-all fallback performs exactly one classified plain retry"
else
  fail_test "grant-all fallback performs exactly one classified plain retry"
fi

MOCK_APK_INSTALL=ambiguous_commit \
  run_provision "$MOCK_TARGET" --apk "$HELPER_RELEASE_APK" --release-tag v0.9.4-rc1 --no-tame
assert_success "transport loss after package-manager commit is reconciled from exact installed APK bytes"
assert_contains 'exact target APK bytes are installed; completing the helper transaction' "ambiguous install success explains the exact-byte reconciliation"
assert_log_contains '^adb .* shell pm path io\.github\.maxlyth\.hapaneld$' "ambiguous install outcome queries the installed package path"
assert_log_contains '^adb .* pull /data/app/io\.github\.maxlyth\.hapaneld/base\.apk ' "ambiguous install outcome authenticates the installed base APK"
assert_log_contains 'helper-transaction-[0-9a-f]+.*commit-system' "confirmed package-manager commit also commits helper recovery"
assert_not_contains 'helper-transaction-[0-9a-f]+.*rollback-system' "$MOCK_CALL_LOG" "confirmed package-manager commit never rolls the matching helper back"
if [ "$(grep -Ec '^adb .* install( |$)' "$MOCK_CALL_LOG")" -eq 1 ]; then
  pass "ambiguous committed install is reconciled without replay"
else
  fail_test "ambiguous committed install is reconciled without replay"
fi

MOCK_SYSTEM_AVAIL_KB=12 MOCK_APK_INSTALL=ambiguous_commit \
  run_provision "$MOCK_TARGET" --apk "$HELPER_RELEASE_APK" --release-tag v0.9.4-rc1 --no-tame
assert_success "hybrid transport loss after package-manager commit reconciles exact installed APK bytes"
assert_log_contains 'helper-transaction-[0-9a-f]+.*commit-hybrid' "confirmed package-manager commit commits hybrid recovery"
assert_not_contains 'helper-transaction-[0-9a-f]+.*rollback-hybrid' "$MOCK_CALL_LOG" "confirmed hybrid package-manager commit is never rolled back"
if [ "$(grep -Ec '^adb .* install( |$)' "$MOCK_CALL_LOG")" -eq 1 ]; then
  pass "ambiguous committed hybrid install is reconciled without replay"
else
  fail_test "ambiguous committed hybrid install is reconciled without replay"
fi

MOCK_HELPER_COMMIT=fail \
  run_provision "$MOCK_TARGET" --apk "$HELPER_RELEASE_APK" --release-tag v0.9.4-rc1 --no-tame
assert_failure "unconfirmed helper journal commit fails without an unsafe rollback"
assert_contains 'helper recovery journal could not be committed' "unconfirmed commit names the retained recovery state"
assert_not_contains 'helper-transaction-[0-9a-f]+.*rollback-system' "$MOCK_CALL_LOG" "unconfirmed post-APK commit never claims or attempts rollback"
commit_attempts="$(grep -Ec 'helper-transaction-[0-9a-f]+.*commit-system' "$MOCK_CALL_LOG" || true)"
if [ "$commit_attempts" -eq 2 ]; then
  pass "helper commit is retried idempotently before failing"
else
  fail_test "helper commit is retried idempotently before failing"
fi

MOCK_COMMIT_LIVE_STATE=UNKNOWN \
  run_provision "$MOCK_TARGET" --apk "$HELPER_RELEASE_APK" --release-tag v0.9.4-rc1 --no-tame
assert_failure "commit-time persistence mutation retains the active helper recovery journal"
assert_contains 'helper recovery journal could not be committed' "commit-time UNKNOWN state fails closed after APK installation"
if [ -f "$TMP/active-helper-transaction" ]; then
  pass "commit-time UNKNOWN state preserves active recovery evidence"
else
  fail_test "commit-time UNKNOWN state preserves active recovery evidence"
fi
assert_not_contains 'helper-transaction-[0-9a-f]+.*rollback-system' "$MOCK_CALL_LOG" "commit-time UNKNOWN state never performs an unsafe post-APK rollback"

MOCK_TOKEN_MISMATCH_ACTION=commit \
  run_provision "$MOCK_TARGET" --apk "$HELPER_RELEASE_APK" --release-tag v0.9.4-rc1 --no-tame
assert_failure "a mismatched transaction token cannot commit another provisioner's journal"
assert_contains 'helper recovery journal could not be committed' "commit token mismatch retains the recovery journal"
assert_not_contains 'helper-transaction-[0-9a-f]+.*rollback-system' "$MOCK_CALL_LOG" "commit token mismatch never falls back to another owner's rollback"

MOCK_HELPER_START=fail MOCK_TOKEN_MISMATCH_ACTION=rollback \
  run_provision "$MOCK_TARGET" --apk "$HELPER_RELEASE_APK" --release-tag v0.9.4-rc1 --no-tame
assert_failure "a mismatched transaction token cannot roll back another provisioner's journal"
assert_contains 'rollback could not be verified' "rollback token mismatch is reported without claiming restoration"
assert_not_contains '^adb .* install( |$)' "$MOCK_CALL_LOG" "rollback token mismatch leaves the APK untouched"

# Scope note: this models a panel that DOES have ha-paneld installed and whose package manager stops
# answering after the install attempt. That is genuinely ambiguous. The panel response is otherwise
# identical to a clean panel's, where it is NOT ambiguous but definite absence — see the failed
# first-install case above, which asserts a rollback rather than this retained-recovery path.
MOCK_APK_INSTALL=fail MOCK_APK_QUERY=fail \
  run_provision "$MOCK_TARGET" --apk "$HELPER_RELEASE_APK" --release-tag v0.9.4-rc1 --no-tame
assert_failure "unqueryable package-manager outcome on an INSTALLED panel retains helper recovery without rollback"
assert_contains 'adb install outcome is ambiguous; helper recovery was retained without rollback' "unqueryable install outcome gives the safe retry path"
assert_not_contains 'hapaneld-helper\.txn (rollback|commit)-system' "$MOCK_CALL_LOG" "unqueryable install outcome neither rolls back nor commits the helper journal"

# TERM must interrupt the shell's wait for package-manager completion, stop and reap both local
# subprocesses, and prevent the detached lease heartbeat from extending ownership indefinitely.
: > "$MOCK_CALL_LOG"
rm -f "$TMP/stale-helper-transaction" "$TMP/active-helper-transaction"
printf 'previous installed apk\n' > "$TMP/installed-apk"
blocked_install_pid_file="$TMP/blocked-install.pid"
blocked_guard_pid_file="$TMP/blocked-guard.pid"
blocked_guard_sleep_pid_file="$TMP/blocked-guard-sleep.pid"
blocked_output="$TMP/blocked-provision-output.txt"
lease_guard_tmp="$TMP/lease-guard-tmp"
mkdir -p "$lease_guard_tmp"
MOCK_APK_INSTALL=block \
MOCK_APK_INSTALL_PID_FILE="$blocked_install_pid_file" \
MOCK_STATE_DIR="$TMP" \
ROOT_HELPER_LEASE_GUARD_INTERVAL_SECONDS=0.05 \
ROOT_HELPER_LEASE_GUARD_PID_FILE="$blocked_guard_pid_file" \
ROOT_HELPER_LEASE_GUARD_SLEEP_PID_FILE="$blocked_guard_sleep_pid_file" \
TMPDIR="$lease_guard_tmp" \
  bash "$PROVISION" "$MOCK_TARGET" --apk "$HELPER_RELEASE_APK" --release-tag v0.9.4-rc1 --no-tame \
    > "$blocked_output" 2>&1 &
blocked_provision_pid=$!
blocked_ready=0
blocked_wait_attempt=0
while [ "$blocked_wait_attempt" -lt 100 ]; do
  if [ -s "$blocked_install_pid_file" ]; then blocked_ready=1; break; fi
  /bin/sleep 0.05
  blocked_wait_attempt=$((blocked_wait_attempt + 1))
done
if [ "$blocked_ready" -eq 1 ]; then
  pass "blocked package install exposes its exact subprocess lifecycle"
else
  LAST_OUTPUT="$blocked_output"
  fail_test "blocked package install exposes its exact subprocess lifecycle"
fi
/bin/sleep 0.2
lease_count_before_term="$(grep -Ec 'helper-transaction-[0-9a-f]+.*lease-system' "$MOCK_CALL_LOG" || true)"
kill -TERM "$blocked_provision_pid" 2>/dev/null || true
if wait "$blocked_provision_pid"; then blocked_status=0; else blocked_status=$?; fi
if [ "$blocked_status" -eq 143 ]; then
  pass "TERM exits a provisioner blocked in adb install with signal status"
else
  LAST_OUTPUT="$blocked_output"
  fail_test "TERM exits a provisioner blocked in adb install with signal status (got $blocked_status)"
fi
blocked_install_pid="$(cat "$blocked_install_pid_file" 2>/dev/null || true)"
if [ -n "$blocked_install_pid" ] && processes_gone "$blocked_install_pid"; then
  pass "TERM reaps the blocked adb install subprocess"
else
  fail_test "TERM reaps the blocked adb install subprocess"
fi
blocked_guard_pid="$(cat "$blocked_guard_pid_file" 2>/dev/null || true)"
blocked_guard_sleep_pid="$(cat "$blocked_guard_sleep_pid_file" 2>/dev/null || true)"
if [ -n "$blocked_guard_pid" ] && [ -n "$blocked_guard_sleep_pid" ] && \
   processes_gone "$blocked_guard_pid" "$blocked_guard_sleep_pid"; then
  pass "TERM reaps the lease guard and its current sleep child"
else
  fail_test "TERM reaps the lease guard and its current sleep child"
fi
lease_count_at_exit="$(grep -Ec 'helper-transaction-[0-9a-f]+.*lease-system' "$MOCK_CALL_LOG" || true)"
/bin/sleep 0.2
lease_count_after_term="$(grep -Ec 'helper-transaction-[0-9a-f]+.*lease-system' "$MOCK_CALL_LOG" || true)"
if [ "$lease_count_before_term" -ge 2 ] && [ "$lease_count_after_term" -eq "$lease_count_at_exit" ]; then
  pass "TERM leaves no orphan lease guard renewing after parent exit"
else
  fail_test "TERM leaves no orphan lease guard renewing after parent exit ($lease_count_at_exit at exit, $lease_count_after_term later)"
fi
if [ -z "$(find "$lease_guard_tmp" -mindepth 1 -maxdepth 1 -print -quit)" ]; then
  pass "TERM removes lease-guard and adb-capture temporary files"
else
  fail_test "TERM removes lease-guard and adb-capture temporary files"
fi

helper_release_apk_sha="$(/usr/bin/sha256sum "$HELPER_RELEASE_APK" | awk '{print $1}')"
MOCK_STALE_TRANSACTION=1 \
MOCK_INSTALLED_APK_SOURCE="$HELPER_RELEASE_APK" \
MOCK_STALE_APK_SHA256="$helper_release_apk_sha" \
  run_provision "$MOCK_TARGET" --apk "$HELPER_RELEASE_APK" --release-tag v0.9.4-rc1 --no-tame
assert_success "rerun commits a stale helper journal when the exact target APK is already installed"
assert_log_contains 'helper-transaction-[0-9a-f]+.*status-system' "stale transaction reads its durable target identity"
assert_log_contains '^adb .* pull /data/app/io\.github\.maxlyth\.hapaneld/base\.apk ' "stale transaction authenticates the installed APK bytes"
assert_log_contains 'helper-probe COMPANIONCAPS' "stale committed transaction rechecks the privileged protocol"
assert_log_contains 'helper-probe BUILDID' "stale committed transaction rechecks the recorded helper build"
assert_log_contains 'helper-transaction-[0-9a-f]+.*commit-system' "stale committed transaction discards obsolete recovery"
assert_not_contains 'helper-transaction-[0-9a-f]+.*rollback-system' "$MOCK_CALL_LOG" "stale committed transaction does not restore the superseded helper"

MOCK_STALE_TRANSACTION=1 \
MOCK_INSTALLED_APK_SOURCE="$HELPER_RELEASE_APK" \
MOCK_STALE_APK_SHA256="$helper_release_apk_sha" \
MOCK_COMMIT_LIVE_STATE=UNKNOWN \
  run_provision "$MOCK_TARGET" --apk "$HELPER_RELEASE_APK" --release-tag v0.9.4-rc1 --no-tame
assert_failure "stale reconciliation refuses to discard recovery after persistence changes before commit"
assert_contains 'incomplete prior root-helper and APK upgrade could not be reconciled safely' "stale commit-time UNKNOWN state names the retained reconciliation"
if [ -f "$TMP/stale-helper-transaction" ]; then
  pass "stale commit-time UNKNOWN state preserves its durable journal"
else
  fail_test "stale commit-time UNKNOWN state preserves its durable journal"
fi
assert_not_contains 'helper-transaction-[0-9a-f]+.*rollback-system' "$MOCK_CALL_LOG" "stale exact-APK UNKNOWN state is not rolled back destructively"

MOCK_STALE_TRANSACTION=1 \
MOCK_INSTALLED_APK_SOURCE="$RELEASE_APK" \
MOCK_STALE_APK_SHA256="$helper_release_apk_sha" \
  run_provision "$MOCK_TARGET" --apk "$HELPER_RELEASE_APK" --release-tag v0.9.4-rc1 --no-tame
assert_success "rerun rolls back a stale helper journal when the target APK was never installed"
assert_log_contains 'helper-transaction-[0-9a-f]+.*rollback-system' "stale pre-APK transaction restores the prior helper before retrying"
rollback_line="$(grep -nE 'helper-transaction-[0-9a-f]+.*rollback-system' "$MOCK_CALL_LOG" | head -1 | cut -d: -f1)"
retry_install_line="$(grep -nE '^adb .* install( |$)' "$MOCK_CALL_LOG" | head -1 | cut -d: -f1)"
if [ -n "$rollback_line" ] && [ -n "$retry_install_line" ] && [ "$rollback_line" -lt "$retry_install_line" ]; then
  pass "stale pre-APK recovery completes before the new package install"
else
  fail_test "stale pre-APK recovery completes before the new package install"
fi

MOCK_STALE_TRANSACTION=1 \
MOCK_STALE_TRANSACTION_KIND=systemless \
MOCK_INSTALLED_APK_SOURCE="$RELEASE_APK" \
MOCK_STALE_APK_SHA256="$helper_release_apk_sha" \
  run_provision "$MOCK_TARGET" --apk "$HELPER_RELEASE_APK" --release-tag v0.9.4-rc1 --no-tame
assert_success "writable-system provisioning recovers a stale systemless journal before changing paths"
assert_log_contains 'helper-transaction-[0-9a-f]+.*status-systemless' "systemless-to-system transition reads the retained systemless journal"
assert_log_contains 'helper-transaction-[0-9a-f]+.*rollback-systemless' "systemless-to-system transition restores the owning transaction"
assert_log_contains 'helper-transaction-[0-9a-f]+.*install-system' "systemless-to-system transition installs only after recovery"

MOCK_STALE_TRANSACTION=1 \
MOCK_STALE_TRANSACTION_KIND=systemless \
MOCK_SYSTEM_AVAIL_KB=12 \
MOCK_INSTALLED_APK_SOURCE="$RELEASE_APK" \
MOCK_STALE_APK_SHA256="$helper_release_apk_sha" \
  run_provision "$MOCK_TARGET" --apk "$HELPER_RELEASE_APK" --release-tag v0.9.4-rc1 --no-tame
assert_success "hybrid provisioning recovers a stale systemless journal before changing authority"
assert_log_contains 'helper-transaction-[0-9a-f]+.*status-systemless' "systemless-to-hybrid transition reads the retained systemless journal"
assert_log_contains 'helper-transaction-[0-9a-f]+.*rollback-systemless' "systemless-to-hybrid transition restores the owning transaction"
assert_log_contains 'helper-transaction-[0-9a-f]+.*install-hybrid' "systemless-to-hybrid transition selects one new boot authority after recovery"
assert_not_contains 'helper-transaction-[0-9a-f]+.*install-(system|systemless)([^a-z]|$)' "$MOCK_CALL_LOG" "systemless-to-hybrid transition invokes no duplicate installer"

MOCK_STALE_TRANSACTION=1 \
MOCK_STALE_TRANSACTION_KIND=system \
MOCK_SYSTEM_AVAIL_KB=12 \
MOCK_INSTALLED_APK_SOURCE="$RELEASE_APK" \
MOCK_STALE_APK_SHA256="$helper_release_apk_sha" \
  run_provision "$MOCK_TARGET" --apk "$HELPER_RELEASE_APK" --release-tag v0.9.4-rc1 --no-tame
assert_success "hybrid provisioning recovers a stale system journal before changing authority"
assert_log_contains 'helper-transaction-[0-9a-f]+.*status-system' "system-to-hybrid transition reads the retained system journal"
assert_log_contains 'helper-transaction-[0-9a-f]+.*rollback-system' "system-to-hybrid transition restores the owning transaction"
assert_log_contains 'helper-transaction-[0-9a-f]+.*install-hybrid' "system-to-hybrid transition selects one new boot authority after recovery"
assert_not_contains 'helper-transaction-[0-9a-f]+.*install-(system|systemless)([^a-z]|$)' "$MOCK_CALL_LOG" "system-to-hybrid transition invokes no duplicate installer"

MOCK_STALE_TRANSACTION=1 \
MOCK_STALE_TRANSACTION_KIND=hybrid \
MOCK_SYSTEM_AVAIL_KB=1048576 \
MOCK_VENDOR_RC_STATE=managed \
MOCK_INSTALLED_APK_SOURCE="$RELEASE_APK" \
MOCK_STALE_APK_SHA256="$helper_release_apk_sha" \
  run_provision "$MOCK_TARGET" --apk "$HELPER_RELEASE_APK" --release-tag v0.9.4-rc1 --no-tame
assert_success "sticky hybrid provisioning recovers its stale journal before retrying"
assert_log_contains 'helper-transaction-[0-9a-f]+.*status-hybrid' "hybrid reconciliation reads the retained hybrid journal"
assert_log_contains 'helper-transaction-[0-9a-f]+.*rollback-hybrid' "hybrid reconciliation restores its owning transaction"
assert_log_contains 'helper-transaction-[0-9a-f]+.*install-hybrid' "hybrid reconciliation retains the existing boot authority"
assert_not_contains 'helper-transaction-[0-9a-f]+.*install-(system|systemless)([^a-z]|$)' "$MOCK_CALL_LOG" "sticky hybrid reconciliation invokes no duplicate installer"

MOCK_STALE_TRANSACTION=1 \
MOCK_STALE_TRANSACTION_KIND=system \
MOCK_SYSTEM_WRITABLE=0 \
MOCK_INSTALLED_APK_SOURCE="$RELEASE_APK" \
MOCK_STALE_APK_SHA256="$helper_release_apk_sha" \
  run_provision "$MOCK_TARGET" --apk "$HELPER_RELEASE_APK" --release-tag v0.9.4-rc1 --no-tame
assert_success "systemless provisioning recovers a stale /system journal before changing paths"
assert_log_contains 'helper-transaction-[0-9a-f]+.*status-system' "system-to-systemless transition reads the retained /system journal"
assert_log_contains 'helper-transaction-[0-9a-f]+.*rollback-system' "system-to-systemless transition restores the owning transaction"
assert_log_contains 'helper-transaction-[0-9a-f]+.*install-systemless' "system-to-systemless transition installs only after recovery"

MOCK_STALE_TRANSACTION=1 \
MOCK_STALE_TRANSACTION_KIND=both \
  run_provision "$MOCK_TARGET" --apk "$HELPER_RELEASE_APK" --release-tag v0.9.4-rc1 --no-tame
assert_failure "simultaneous root-helper journals fail closed"
assert_contains 'more than one root-helper recovery journal is present' "multi-journal failure explains the ambiguity"
assert_not_contains 'hapaneld-helper\.txn (rollback|commit)-(system|systemless|hybrid)' "$MOCK_CALL_LOG" "multi-journal ambiguity preserves all recovery records"
assert_not_contains '^adb .* install( |$)' "$MOCK_CALL_LOG" "dual-journal ambiguity stops before APK replacement"

MOCK_STALE_TRANSACTION=1 \
MOCK_INSTALLED_APK_SOURCE="$RELEASE_APK" \
MOCK_STALE_APK_SHA256="$helper_release_apk_sha" \
MOCK_STALE_LIVE_STATE=PRE_SWAP \
MOCK_STALE_BUILD_ID=aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa \
  run_provision "$MOCK_TARGET" --apk "$HELPER_RELEASE_APK" --release-tag v0.9.4-rc1 --no-tame
assert_success "power loss after journal publication but before helper swap is recoverable"
assert_log_contains 'helper-transaction-[0-9a-f]+.*rollback-system' "pre-swap crash recovery restores idempotently without requiring the target helper to be running"

MOCK_STALE_TRANSACTION=1 \
MOCK_INSTALLED_APK_SOURCE="$RELEASE_APK" \
MOCK_STALE_APK_SHA256="$helper_release_apk_sha" \
MOCK_STALE_LIVE_STATE=UNKNOWN \
MOCK_STALE_BUILD_ID=aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa \
  run_provision "$MOCK_TARGET" --apk "$HELPER_RELEASE_APK" --release-tag v0.9.4-rc1 --no-tame
assert_failure "stale journal with a superseded running helper fails without destructive rollback"
assert_contains 'could not be reconciled safely' "superseded stale helper reports the ambiguous compatibility state"
assert_contains 'No rollback was attempted' "superseded stale helper states the non-destructive outcome"
assert_not_contains 'hapaneld-helper\.txn (rollback|commit)-system' "$MOCK_CALL_LOG" "superseded stale helper leaves recovery evidence intact"
assert_not_contains '^adb .* install( |$)' "$MOCK_CALL_LOG" "superseded stale helper stops before another APK replacement"

release_metadata_build_id=bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb
MOCK_HELPER_BUILD_ID="$release_metadata_build_id" \
MOCK_RELEASE_PROVISION_BUILD_ID="$release_metadata_build_id" \
  run_provision "$MOCK_TARGET" --apk "$HELPER_RELEASE_APK" --release-tag v0.9.4-rc1 --no-tame
assert_success "moving-checkout provisioning uses the authenticated release helper identity"
assert_log_contains 'ha-paneld-provision-v0\.9\.4-rc1\.sh -o ' "moving-checkout provisioning downloads the matching versioned provisioner"
assert_log_contains 'ha-paneld-provision-v0\.9\.4-rc1\.sh\.sha256 -o ' "moving-checkout provisioning downloads the provisioner checksum"
assert_log_contains 'ha-paneld-provision-v0\.9\.4-rc1\.sh\.sha256\.sig -o ' "moving-checkout provisioning downloads the provisioner signature"
assert_log_contains "helper-probe BUILDID" "moving-checkout provisioning checks the release-recorded build identity"
assert_not_contains "$release_metadata_build_id" "$ROOT/helper/source-id.sh" "release metadata test identity differs from the current checkout identity"

MOCK_RELEASE_PROVISION_BUILD_ID=missing \
  run_provision "$MOCK_TARGET" --apk "$HELPER_RELEASE_APK" --release-tag v0.9.4-rc1 --no-tame
assert_failure "signed provisioner without an exact helper identity fails closed"
assert_contains 'expected root-helper build identity is unavailable' "missing release helper identity names the packaging failure"
assert_not_contains '/data/local/tmp/hapaneld-helper|^adb .* install( |$)' "$MOCK_CALL_LOG" "missing release helper identity stops before privileged staging or APK replacement"

fi
[ "$PROVISION_TEST_SCOPE" != shard-helper-transaction ] || finish_provision_test

if provision_scope_is core all shard-release-integrity; then
# A release describes its helper twice: the signed versioned provisioner records which helper the
# release ships, and the authenticated helper asset carries its own stamped identity. Both are
# verified independently, so the installer must require them to agree before anything privileged
# happens — and must refuse when the asset cannot state an identity at all.
run_provision "$MOCK_TARGET" --apk "$HELPER_RELEASE_APK" --release-tag v0.9.4-rc1 --no-tame
assert_success "a release whose helper asset and signed provisioner agree installs normally"
assert_not_contains 'does not match the identity its signed provisioner records' "$LAST_OUTPUT" "agreeing release identities raise no cross-check failure"
assert_log_contains 'helper-probe BUILDID' "an agreeing release still proves the running daemon identity"

asset_provisioner_mismatch_id=cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc
MOCK_RELEASE_HELPER_BUILD_ID="$asset_provisioner_mismatch_id" \
  run_provision "$MOCK_TARGET" --apk "$HELPER_RELEASE_APK" --release-tag v0.9.4-rc1 --no-tame
assert_failure "a helper asset that disagrees with its signed provisioner fails closed"
assert_contains 'does not match the identity its signed provisioner records' "disagreeing release identities name the inconsistency"
assert_not_contains '/data/local/tmp/hapaneld-helper|^adb .* install( |$)' "$MOCK_CALL_LOG" "disagreeing release identities stop before privileged staging or APK replacement"
assert_not_contains 'helper-transaction-[0-9a-f]+' "$MOCK_CALL_LOG" "disagreeing release identities never open a helper transaction"

MOCK_RELEASE_HELPER_BUILD_ID=missing \
  run_provision "$MOCK_TARGET" --apk "$HELPER_RELEASE_APK" --release-tag v0.9.4-rc1 --no-tame
assert_failure "a release helper asset with no build identity fails closed"
assert_contains 'does not state a single valid build identity' "an identity-less release helper names the packaging failure"
assert_not_contains '/data/local/tmp/hapaneld-helper|^adb .* install( |$)' "$MOCK_CALL_LOG" "an identity-less release helper stops before privileged staging or APK replacement"

MOCK_RELEASE_HELPER_BUILD_ID=malformed \
  run_provision "$MOCK_TARGET" --apk "$HELPER_RELEASE_APK" --release-tag v0.9.4-rc1 --no-tame
assert_failure "a release helper asset with a malformed build identity fails closed"
assert_contains 'does not state a single valid build identity' "a malformed release helper identity is refused rather than parsed loosely"
assert_not_contains '/data/local/tmp/hapaneld-helper|^adb .* install( |$)' "$MOCK_CALL_LOG" "a malformed release helper identity stops before privileged staging or APK replacement"

MOCK_RELEASE_HELPER_BUILD_ID=duplicate \
  run_provision "$MOCK_TARGET" --apk "$HELPER_RELEASE_APK" --release-tag v0.9.4-rc1 --no-tame
assert_failure "a release helper asset stating two build identities fails closed"
assert_contains 'does not state a single valid build identity' "contradictory release helper identities are refused rather than guessed between"
assert_not_contains '/data/local/tmp/hapaneld-helper|^adb .* install( |$)' "$MOCK_CALL_LOG" "contradictory release helper identities stop before privileged staging or APK replacement"

# A provisioner still naming the helper of an earlier build is the stale-expectation case the
# post-swap probe used to discover only after the privileged swap had already happened.
stale_provisioner_id=dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd
MOCK_RELEASE_PROVISION_BUILD_ID="$stale_provisioner_id" \
  run_provision "$MOCK_TARGET" --apk "$HELPER_RELEASE_APK" --release-tag v0.9.4-rc1 --no-tame
assert_failure "a signed provisioner naming a stale helper identity fails closed"
assert_contains 'does not match the identity its signed provisioner records' "a stale release expectation names the inconsistency"
assert_not_contains '/data/local/tmp/hapaneld-helper|^adb .* install( |$)' "$MOCK_CALL_LOG" "a stale release expectation stops before privileged staging or APK replacement"

# The local-APK path has only one authority and must keep using it: its own staged helper. The
# release cross-check must not leak into it and must not reintroduce an installer-checkout source.
run_provision "$MOCK_TARGET" --apk "$APK" --no-tame
assert_success "a local APK is still verified against its own staged helper identity alone"
assert_not_contains 'signed provisioner records' "$LAST_OUTPUT" "the local-APK path consults no release provisioner identity"

MOCK_SU_DIALECT=shc \
  run_provision "$MOCK_TARGET" --apk "$APK" --no-tame
assert_success "root-helper transaction works through an exec-style vendor su dialect"
assert_log_contains 'su 0 sh -c .*helper-transaction-[0-9a-f]+.*install-system' "vendor su executes only the staged transaction path"

ping_line="$(grep -n '^helper-probe PING$' "$MOCK_CALL_LOG" | head -1 | cut -d: -f1)"
caps_line="$(grep -n '^helper-probe COMPANIONCAPS$' "$MOCK_CALL_LOG" | head -1 | cut -d: -f1)"
build_id_line="$(grep -n '^helper-probe BUILDID$' "$MOCK_CALL_LOG" | head -1 | cut -d: -f1)"
guard_caps_line="$(grep -n '^helper-probe GUARDCAPS$' "$MOCK_CALL_LOG" | head -1 | cut -d: -f1)"
guard_status_line="$(grep -n '^helper-probe GUARDSTATUS$' "$MOCK_CALL_LOG" | head -1 | cut -d: -f1)"
app_line="$(grep -nE '^adb .* install( |$)' "$MOCK_CALL_LOG" | head -1 | cut -d: -f1)"
commit_line="$(grep -nE 'helper-transaction-[0-9a-f]+.*commit-system' "$MOCK_CALL_LOG" | head -1 | cut -d: -f1)"
if [ "$(grep -Ec '^helper-probe (PING|COMPANIONCAPS|BUILDID|GUARDCAPS|GUARDSTATUS)$' "$MOCK_CALL_LOG")" = 5 ] && \
   [ -n "$ping_line" ] && [ -n "$caps_line" ] && [ -n "$build_id_line" ] && \
   [ -n "$guard_caps_line" ] && [ -n "$guard_status_line" ] && \
   [ -n "$app_line" ] && [ -n "$commit_line" ] && \
   [ "$ping_line" -lt "$caps_line" ] && [ "$caps_line" -lt "$build_id_line" ] && \
   [ "$build_id_line" -lt "$guard_caps_line" ] && [ "$guard_caps_line" -lt "$guard_status_line" ] && \
   [ "$guard_status_line" -lt "$app_line" ] && [ "$app_line" -lt "$commit_line" ]; then
  pass "one canonical helper validation sequence precedes APK replacement and commit"
else
  fail_test "one canonical helper validation sequence precedes APK replacement and commit"
fi

# Official release assets are authenticated before the first install, launch, or privilege grant
# whenever Android Build-Tools are present. The fixtures expose both apksigner and aapt.
run_provision "$MOCK_TARGET" --apk "$APK" --no-tame
assert_success "local APK with the pinned release signer and package is accepted"
assert_contains 'verified.*local signed APK' "local APK verification reports its signer"
assert_log_contains '^apksigner verify --print-certs ' "local APK verification invokes apksigner"
assert_log_contains '^aapt dump badging ' "local APK verification inspects the package name"

MOCK_RELEASE_CERT=0000000000000000000000000000000000000000000000000000000000000000 \
  run_provision "$MOCK_TARGET" --apk "$APK" --no-tame
assert_success "self-built local APK with one developer signer remains installable"

MOCK_RELEASE_CERT=0000000000000000000000000000000000000000000000000000000000000000 \
  run_provision "$MOCK_TARGET" --apk "$APK" --require-release-signer --no-tame
assert_failure "official-signer policy rejects a debug or foreign local APK"
assert_contains 'release APK signer mismatch' "local signer failure names the trust violation"
assert_contains 'This run requires the official release signer' "local signer failure states the official-signer requirement"
assert_not_contains 'config/export|ha-paneld-db-snapshot|/data/local/tmp/hapaneld-helper|^adb .* install( |$)' "$MOCK_CALL_LOG" "local signer failure stops before backup, helper staging, or APK replacement"

MOCK_ADDITIONAL_RELEASE_CERT=0000000000000000000000000000000000000000000000000000000000000000 \
  run_provision "$MOCK_TARGET" --apk "$APK" --no-tame
assert_failure "multi-signed local APK fails closed"
assert_contains 'release APK signer count mismatch' "multi-signed local APK names the signer-count violation"
assert_not_contains 'config/export|ha-paneld-db-snapshot|/data/local/tmp/hapaneld-helper|^adb .* install( |$)' "$MOCK_CALL_LOG" "multi-signed local APK stops before backup, helper staging, or APK replacement"

run_provision "$MOCK_TARGET" --apk "$RELEASE_APK" --release-tag v0.9.2-rc3 --no-tame
assert_success "release APK with the pinned signer and package is accepted"
assert_contains 'authenticated.*v0\.9\.2-rc3' "release verification reports the signed checksum authentication"
assert_contains 'verified.*v0\.9\.2-rc3' "release verification reports the authenticated tag"
assert_log_contains '^curl .*https://github\.com/maxlyth/ha-paneld/releases/download/v0\.9\.2-rc3/ha-paneld-v0\.9\.2-rc3-manual-setup-required\.apk\.sha256 -o ' "release verification downloads the canonical checksum asset"
assert_log_contains '^curl .*https://github\.com/maxlyth/ha-paneld/releases/download/v0\.9\.2-rc3/ha-paneld-v0\.9\.2-rc3-manual-setup-required\.apk\.sha256\.sig -o ' "release verification downloads the canonical detached signature"
assert_log_contains '^openssl dgst -sha256 -verify .* -signature .*/release\.sha256\.sig .*/release\.sha256$' "release verification authenticates the checksum record"
assert_log_contains '^openssl dgst -sha256 -r .*ha-paneld-v0\.9\.2-rc3-manual-setup-required\.apk$' "release verification hashes the downloaded APK"
assert_log_contains '^apksigner verify --print-certs .*ha-paneld-v0\.9\.2-rc3-manual-setup-required\.apk$' "release verification invokes apksigner"
signer_verify_count="$(grep -Ec '^apksigner verify --print-certs .*ha-paneld-v0\.9\.2-rc3-manual-setup-required\.apk$' "$MOCK_CALL_LOG" || true)"
if [ "$signer_verify_count" = 1 ]; then pass "release signer verification runs exactly once"; else fail_test "release signer verification runs exactly once"; fi
assert_log_contains '^aapt dump badging .*ha-paneld-v0\.9\.2-rc3-manual-setup-required\.apk$' "release verification inspects the package name"
signer_line="$(grep -nE '^apksigner verify --print-certs ' "$MOCK_CALL_LOG" | head -1 | cut -d: -f1)"
package_line="$(grep -nE '^aapt dump badging ' "$MOCK_CALL_LOG" | head -1 | cut -d: -f1)"
signature_line="$(grep -nE '^openssl dgst -sha256 -verify ' "$MOCK_CALL_LOG" | head -1 | cut -d: -f1)"
checksum_line="$(grep -nE '^openssl dgst -sha256 -r ' "$MOCK_CALL_LOG" | head -1 | cut -d: -f1)"
app_install_line="$(grep -nE '^adb .* install( |$)' "$MOCK_CALL_LOG" | head -1 | cut -d: -f1)"
if [ -n "$signature_line" ] && [ -n "$checksum_line" ] && [ -n "$signer_line" ] && [ -n "$package_line" ] && [ -n "$app_install_line" ] && \
   [ "$signature_line" -lt "$checksum_line" ] && [ "$checksum_line" -lt "$signer_line" ] && \
   [ "$signer_line" -lt "$app_install_line" ] && [ "$package_line" -lt "$app_install_line" ]; then
  pass "release proof, signer, and package are verified before adb install"
else
  fail_test "release proof, signer, and package are verified before adb install"
fi

# A signed checksum authenticates the publisher, but an existing panel also needs the incumbent
# signer comparison and exact manifest contract. Both require Android Build-Tools before mutation.
# The refusal is about this computer, so it names the missing tools, where to get them and how to
# point the installer at them, and never blames the candidate APK or the panel database (#24).
NO_BUILD_TOOL_FIXTURES="$TMP/fixtures-without-build-tools"
make_host_without "$NO_BUILD_TOOL_FIXTURES" apksigner aapt aapt2
if PATH="$NO_BUILD_TOOL_FIXTURES" command -v bash >/dev/null 2>&1 && \
   ! PATH="$NO_BUILD_TOOL_FIXTURES" command -v apksigner >/dev/null 2>&1 && \
   ! PATH="$NO_BUILD_TOOL_FIXTURES" command -v aapt >/dev/null 2>&1 && \
   ! PATH="$NO_BUILD_TOOL_FIXTURES" command -v aapt2 >/dev/null 2>&1; then
  pass "the no-Build-Tools sandbox is a complete host without apksigner, aapt or aapt2"
else
  fail_test "the no-Build-Tools sandbox is a complete host without apksigner, aapt or aapt2"
fi
PATH="$NO_BUILD_TOOL_FIXTURES" ANDROID_HOME= ANDROID_SDK_ROOT= \
  run_provision "$MOCK_TARGET" --apk "$RELEASE_APK" --release-tag v0.9.2-rc3 --no-tame
assert_failure "an existing release install refuses without required Android Build-Tools"
assert_contains 'updating the ha-paneld already on this panel needs apksigner and aapt or aapt2 from Android SDK Build-Tools on this computer' \
  "missing Build-Tools names every missing tool in one refusal"
assert_contains 'apksigner was not found on PATH or in the build-tools folder of ANDROID_HOME or ANDROID_SDK_ROOT' \
  "missing Build-Tools says where apksigner was looked for"
assert_contains 'sdkmanager "build-tools;36\.0\.0" from the Android command-line tools \(sdkmanager\.bat in Git Bash\)' \
  "missing Build-Tools says where to get them, including under Git Bash"
assert_contains 'export ANDROID_HOME="\$HOME/AppData/Local/Android/Sdk" in Git Bash on Windows' \
  "missing Build-Tools says how to point the installer at them"
assert_contains 'No settings backup, database quiescence, reset, helper, Shizuku, APK, permission or configuration mutation was started' \
  "missing Build-Tools states that nothing was changed"
assert_not_contains 'Candidate boundary|Use a candidate that supports|database compatibility could not be proven' "$LAST_OUTPUT" \
  "missing Build-Tools is not reported as a candidate or database verdict"
assert_not_contains 'optional APK structure inspection' "$LAST_OUTPUT" \
  "the skipped inspection is not called optional before an update refuses without it"
assert_contains 'Updating a panel that already has ha-paneld needs these tools' \
  "the skipped inspection says an update still needs the tools"
assert_log_contains '^openssl dgst -sha256 -verify ' "no-apksigner path still authenticates the checksum signature"
assert_not_contains 'config/export|PREPARE_UPGRADE|ha-paneld-db-txn|/data/local/tmp/hapaneld-helper|^adb .* install( |$)|pm clear|pm grant|appops set|settings put' \
  "$MOCK_CALL_LOG" "missing Build-Tools refuses before every tracked mutation"

# apksigner present, aapt and aapt2 absent: the signer comparison runs, then the manifest read refuses
# naming the missing tool rather than reporting the candidate's metadata as missing or malformed.
NO_AAPT_FIXTURES="$TMP/fixtures-without-aapt"
make_host_without "$NO_AAPT_FIXTURES" aapt aapt2
PATH="$NO_AAPT_FIXTURES" ANDROID_HOME= ANDROID_SDK_ROOT= \
  run_provision "$MOCK_TARGET" --apk "$RELEASE_APK" --release-tag v0.9.2-rc3 --no-tame
assert_failure "an existing release install refuses without aapt or aapt2"
assert_contains 'needs aapt or aapt2 from Android SDK Build-Tools on this computer' "a missing aapt is named as the missing tool"
assert_not_contains 'needs apksigner' "$LAST_OUTPUT" "a present apksigner is not reported missing"
assert_not_contains 'missing, duplicate or malformed database metadata|Candidate boundary' "$LAST_OUTPUT" \
  "a missing aapt is not reported as malformed candidate metadata"
assert_log_contains '^apksigner verify --print-certs .*installed-apk' "the incumbent signer comparison ran before the manifest read"
assert_not_contains 'config/export|PREPARE_UPGRADE|ha-paneld-db-txn|/data/local/tmp/hapaneld-helper|^adb .* install( |$)|pm clear|pm grant|appops set|settings put' \
  "$MOCK_CALL_LOG" "a missing aapt refuses before every tracked mutation"

# An apksigner that is PRESENT but cannot run still carries no signer-comparison evidence. The signed
# checksum authenticates the release bytes, but an existing database-bearing panel additionally needs
# the incumbent and candidate signers proved equal before replacement.
# ANDROID_HOME/ANDROID_SDK_ROOT are cleared because this container HAS a working apksigner in a real
# SDK; without that, discovery legitimately falls through to it and the scenario never occurs.
MOCK_APKSIGNER_RUNS=0 ANDROID_HOME= ANDROID_SDK_ROOT= \
  run_provision "$MOCK_TARGET" --apk "$RELEASE_APK" --release-tag v0.9.2-rc3 --no-tame
assert_failure "an unrunnable apksigner fails closed when incumbent signer equality is required"
assert_contains 'could not run' "the skip names the tool as the problem, not the APK"
assert_contains 'Unable to locate a Java Runtime' "the tool's own reason reaches the operator"
assert_not_contains 'release APK signature verification failed' "$LAST_OUTPUT" "the APK is not blamed for the host's missing runtime"
assert_contains 'needs apksigner from Android SDK Build-Tools on this computer' \
  "the missing signer equality proof is named"
# The tool is installed; what is missing is Java, so the remedy is Java rather than reinstalling
# Build-Tools or pointing the installer at an SDK it already found.
assert_contains 'apksigner runs on Java: install a Java runtime' "an unrunnable apksigner is sent to Java"
assert_not_contains 'Install Android SDK Build-Tools|tell the installer where the SDK is' "$LAST_OUTPUT" \
  "an unrunnable apksigner is not told to install or locate Build-Tools it already has"
assert_not_contains '^adb .* install( |$)' "$MOCK_CALL_LOG" "the unproved replacement never reaches APK install"

# A wrapper that exits 0 without running anything is not a usable tool either. Taken at its word it
# answers every signer question with nothing, and the run then blamed the APK for having no signer.
MOCK_APKSIGNER_RUNS=silent ANDROID_HOME= ANDROID_SDK_ROOT= \
  run_provision "$MOCK_TARGET" --apk "$RELEASE_APK" --release-tag v0.9.2-rc3 --no-tame
assert_failure "an apksigner that answers nothing cannot authorize incumbent replacement"
assert_contains 'apksigner is installed at .* but could not run: no output' "a silent apksigner is reported as a tool that did not run"
assert_not_contains 'signer count mismatch|release APK signature verification failed' "$LAST_OUTPUT" \
  "a silent apksigner is not reported as an APK without a signer"

UNRUNNABLE_SDK="$TMP/unrunnable-android-sdk"
mkdir -p "$UNRUNNABLE_SDK/build-tools/99.0.0"
ln -s "$FIXTURES/apksigner" "$UNRUNNABLE_SDK/build-tools/99.0.0/apksigner"
PATH="$NO_SIGNER_FIXTURES" MOCK_APKSIGNER_RUNS=0 ANDROID_HOME= ANDROID_SDK_ROOT="$UNRUNNABLE_SDK" \
  run_provision "$MOCK_TARGET" --apk "$RELEASE_APK" --release-tag v0.9.2-rc3 --no-tame
assert_failure "an SDK-root-only unrunnable apksigner cannot authorize incumbent replacement"
assert_contains "$UNRUNNABLE_SDK/build-tools/99.0.0/apksigner" "SDK-root-only failure names the discovered tool path"
assert_contains 'Unable to locate a Java Runtime' "SDK-root-only failure retains the tool reason"
assert_not_contains '^adb .* install( |$)' "$MOCK_CALL_LOG" "the SDK-root-only failure stays pre-mutation"

MOCK_RELEASE_VERIFY_FAIL=1 run_provision "$MOCK_TARGET" --apk "$RELEASE_APK" --release-tag v0.9.2-rc3 --no-tame
assert_failure "a real apksigner verification failure still fails closed"
assert_contains 'mock release verification failed' "the failing verification invocation supplies its own diagnostic"
signer_verify_count="$(grep -Ec '^apksigner verify --print-certs .*ha-paneld-v0\.9\.2-rc3-manual-setup-required\.apk$' "$MOCK_CALL_LOG" || true)"
if [ "$signer_verify_count" = 1 ]; then pass "failed release signer verification runs exactly once"; else fail_test "failed release signer verification runs exactly once"; fi

# The protection that must NOT be relaxed: a local APK has no signed checksum behind it, so an
# unusable signer stays fatal there.
MOCK_APKSIGNER_RUNS=0 ANDROID_HOME= ANDROID_SDK_ROOT= run_provision "$MOCK_TARGET" --apk "$APK" --no-tame
assert_failure "an unrunnable apksigner still fails closed for a local APK"
assert_contains 'could not run' "the local-APK refusal also names the tool"

MOCK_OPENSSL_MISSING=1 \
  run_provision "$MOCK_TARGET" --apk "$RELEASE_APK" --release-tag v0.9.2-rc3 --no-tame
assert_failure "official release install fails closed when OpenSSL is unavailable"
assert_contains 'OpenSSL is required to authenticate this official ha-paneld release' "missing OpenSSL names the required trust check"
assert_contains 'Windows:.*Git Bash or WSL' "missing OpenSSL gives novice-friendly Windows guidance"
assert_contains 'macOS:.*xcode-select' "missing OpenSSL gives novice-friendly macOS guidance"
assert_contains 'Debian/Ubuntu:.*apt install openssl' "missing OpenSSL gives novice-friendly Linux guidance"
assert_not_contains '^adb .* install( |$)' "$MOCK_CALL_LOG" "missing OpenSSL stops before APK install"
assert_not_contains '^adb .* shell (am start|monkey -p io\.github\.maxlyth\.hapaneld|settings put|appops set|pm grant)' "$MOCK_CALL_LOG" "missing OpenSSL stops before launch or grants"

MOCK_RELEASE_PROOF_DOWNLOAD=checksum_fail \
  run_provision "$MOCK_TARGET" --apk "$RELEASE_APK" --release-tag v0.9.2-rc3 --no-tame
assert_failure "missing release checksum asset fails closed"
assert_contains 'could not download the signed checksum' "missing checksum asset names the incomplete release"
assert_not_contains '^adb .* install( |$)' "$MOCK_CALL_LOG" "missing checksum asset stops before APK install"

MOCK_RELEASE_PROOF_DOWNLOAD=signature_fail \
  run_provision "$MOCK_TARGET" --apk "$RELEASE_APK" --release-tag v0.9.2-rc3 --no-tame
assert_failure "missing release checksum signature fails closed"
assert_contains 'could not download the checksum signature' "missing signature asset names the incomplete release"
assert_not_contains '^adb .* install( |$)' "$MOCK_CALL_LOG" "missing signature asset stops before APK install"

MOCK_RELEASE_SIGNATURE_FAIL=1 \
  run_provision "$MOCK_TARGET" --apk "$RELEASE_APK" --release-tag v0.9.2-rc3 --no-tame
assert_failure "invalid release checksum signature fails closed"
assert_contains 'checksum signature is invalid' "invalid signature names the authentication failure"
assert_contains 'Nothing was installed, started, or privileged' "invalid signature states the safe outcome"
assert_not_contains '^openssl dgst -sha256 -r ' "$MOCK_CALL_LOG" "invalid signature is rejected before trusting or hashing the APK"
assert_not_contains '^adb .* install( |$)' "$MOCK_CALL_LOG" "invalid signature stops before APK install"

MOCK_RELEASE_CHECKSUM=malformed \
  run_provision "$MOCK_TARGET" --apk "$RELEASE_APK" --release-tag v0.9.2-rc3 --no-tame
assert_failure "malformed but signed checksum record fails closed"
assert_contains 'signed checksum record.*is malformed' "malformed checksum names the release metadata error"
assert_not_contains '^adb .* install( |$)' "$MOCK_CALL_LOG" "malformed checksum stops before APK install"

MOCK_RELEASE_CHECKSUM=mismatch \
  run_provision "$MOCK_TARGET" --apk "$RELEASE_APK" --release-tag v0.9.2-rc3 --no-tame
assert_failure "APK checksum mismatch fails closed"
assert_contains 'APK checksum does not match its signed record' "checksum mismatch names the integrity failure"
assert_contains 'Nothing was installed, started, or privileged' "checksum mismatch states the safe outcome"
assert_not_contains '^adb .* install( |$)' "$MOCK_CALL_LOG" "checksum mismatch stops before APK install"

MOCK_OPENSSL_DIGEST_FAIL=1 \
  run_provision "$MOCK_TARGET" --apk "$RELEASE_APK" --release-tag v0.9.2-rc3 --no-tame
assert_failure "local APK digest failure stops release installation"
assert_contains 'OpenSSL could not calculate the downloaded APK checksum' "digest failure gives a direct recovery path"
assert_not_contains '^adb .* install( |$)' "$MOCK_CALL_LOG" "digest failure stops before APK install"

MOCK_RELEASE_CERT=0000000000000000000000000000000000000000000000000000000000000000 \
  run_provision "$MOCK_TARGET" --apk "$RELEASE_APK" --release-tag v0.9.2-rc3 --no-tame
assert_failure "release APK with a foreign signer fails closed"
assert_contains 'release APK signer mismatch' "foreign signer failure names the trust violation"
assert_contains 'Nothing was installed, started, or privileged' "foreign signer failure states the safe outcome"
assert_not_contains '^adb .* install( |$)' "$MOCK_CALL_LOG" "foreign signer is rejected before APK install"
assert_not_contains '^adb .* shell (am start|monkey -p io\.github\.maxlyth\.hapaneld|settings put|appops set|pm grant)' "$MOCK_CALL_LOG" "foreign signer is rejected before launch or grants"

MOCK_RELEASE_PACKAGE=example.foreign \
  run_provision "$MOCK_TARGET" --apk "$RELEASE_APK" --release-tag v0.9.2-rc3 --no-tame
assert_failure "release APK with a foreign package name fails closed"
assert_contains 'release APK package mismatch' "foreign package failure names the trust violation"
assert_not_contains '^adb .* install( |$)' "$MOCK_CALL_LOG" "foreign package is rejected before APK install"
assert_not_contains '^adb .* shell (am start|monkey -p io\.github\.maxlyth\.hapaneld|settings put|appops set|pm grant)' "$MOCK_CALL_LOG" "foreign package is rejected before launch or grants"

run_provision "$MOCK_TARGET" --apk "$RELEASE_APK" --release-tag '../../main' --no-tame
assert_status 2 "invalid internal release tag is rejected as a usage error"
assert_contains 'invalid release tag' "invalid internal release tag gives a direct correction"
assert_not_contains '^adb .* install( |$)' "$MOCK_CALL_LOG" "invalid release tag is rejected before APK install"

fi

if provision_scope_is core all shard-renderer-seeding; then
# ---- built-in renderer seeds (--home-dashboard / --entity-filter) --------------------------------
# A scripted install deliberately never asks guided setup's dashboard and entity-filter questions, so
# without these options an unattended panel renders whatever Home Assistant calls the account default.
# The contract under test: a seeded value is applied before the first render, is also recorded as the
# matching wizard ANSWER, wins over anything less specific, and never records an answer for a value
# that did not persist.
SEED_BUILTIN=(--builtin --ha-url https://ha.test --ha-user owner --ha-pass seed-secret)

run_provision "$MOCK_TARGET" --apk "$APK" "${SEED_BUILTIN[@]}" --no-tame
assert_success "a built-in provision without seeds still succeeds"
assert_not_contains 'home_dashboard=|dashboard_entity_learning=' "$MOCK_CALL_LOG" \
  "omitting both options writes neither dashboard setting"
assert_not_contains 'api/v1/setup/(home-dashboard|entity-filter)' "$MOCK_CALL_LOG" \
  "omitting both options records neither wizard answer"
assert_not_contains 'seeding dashboard' "$LAST_OUTPUT" "omitting both options performs no seed step"

run_provision "$MOCK_TARGET" --apk "$APK" "${SEED_BUILTIN[@]}" --home-dashboard /office --entity-filter on --no-tame
assert_success "seeding both a dashboard path and the entity filter succeeds"
assert_log_contains 'curl .*-X POST .*home_dashboard=/office.*api/v1/config' "the seeded dashboard path is written"
assert_log_contains 'curl .*-X POST .*dashboard_entity_learning=true.*api/v1/config' "--entity-filter on enables entity filtering"
assert_log_contains 'curl .*-X POST .*api/v1/setup/home-dashboard' "seeding the dashboard records the wizard answer"
assert_log_contains 'curl .*-X POST .*api/v1/setup/entity-filter' "seeding the filter records the wizard answer"
assert_contains 'home dashboard: /office' "the applied dashboard path is reported"
assert_contains 'entity filtering: on' "the applied filter policy is reported"

run_provision "$MOCK_TARGET" --apk "$APK" "${SEED_BUILTIN[@]}" --entity-filter off --no-tame
assert_success "seeding the filter off succeeds"
assert_log_contains 'curl .*-X POST .*dashboard_entity_learning=false.*api/v1/config' "--entity-filter off disables entity filtering"
assert_log_contains 'curl .*-X POST .*api/v1/setup/entity-filter' "an explicit off is still a recorded answer"
assert_not_contains 'api/v1/setup/home-dashboard' "$MOCK_CALL_LOG" \
  "seeding only the filter leaves the dashboard question unanswered"

# `auto` is the Home dashboard picker's Auto choice. It is sent as the bare root because the app
# canonicalises every "follow the account default" spelling to the same stored blank sentinel.
run_provision "$MOCK_TARGET" --apk "$APK" "${SEED_BUILTIN[@]}" --home-dashboard auto --no-tame
assert_success "seeding auto succeeds"
assert_log_contains 'curl .*-X POST .*home_dashboard=/[^a-z].*api/v1/config' "auto is sent as the account-default sentinel"
assert_not_contains 'api/v1/config/home-dashboards' "$MOCK_CALL_LOG" \
  "auto names no dashboard, so no catalogue lookup is made"

# Repeated options follow the same last-wins rule as every other provisioner value option.
run_provision "$MOCK_TARGET" --apk "$APK" "${SEED_BUILTIN[@]}" --home-dashboard /lovelace --home-dashboard /office --no-tame
assert_success "a repeated --home-dashboard succeeds"
assert_log_contains 'curl .*-X POST .*home_dashboard=/office.*api/v1/config' "the last --home-dashboard wins"
assert_not_contains 'home_dashboard=/lovelace' "$MOCK_CALL_LOG" "an earlier --home-dashboard is not also written"

# Specificity: an explicitly named value must beat a bulk import, which means the seed has to be
# applied AFTER it. Seeded earlier, the bundle would silently overwrite the operator's own choice.
SEED_RESTORE="$TMP/seed-restore.json"
printf '{"kind":"ha-paneld-config","schema":1,"values":{}}\n' > "$SEED_RESTORE"
run_provision "$MOCK_TARGET" --apk "$APK" "${SEED_BUILTIN[@]}" --restore "$SEED_RESTORE" --home-dashboard /office --no-tame
assert_success "seeding alongside a config import succeeds"
if [ "$(grep -n 'api/v1/config/import' "$MOCK_CALL_LOG" | head -1 | cut -d: -f1)" -lt \
     "$(grep -n 'home_dashboard=/office' "$MOCK_CALL_LOG" | head -1 | cut -d: -f1)" ]; then
  pass "an explicit seed is applied after a restored bundle, so the named value wins"
else
  fail_test "an explicit seed is applied after a restored bundle, so the named value wins"
fi

# An unknown dashboard warns and still saves: the catalogue is a live Home Assistant fact, and a
# dashboard may legitimately be created after the panel is provisioned.
run_provision "$MOCK_TARGET" --apk "$APK" "${SEED_BUILTIN[@]}" --home-dashboard /not-a-dashboard --no-tame
assert_success "an unknown dashboard path does not fail provisioning"
assert_contains 'does not currently list a dashboard at /not-a-dashboard' "an unknown dashboard is named"
assert_contains 'value is saved' "an unknown dashboard is retained rather than rejected"
assert_log_contains 'curl .*-X POST .*api/v1/setup/home-dashboard' "an unknown-but-valid dashboard is still a recorded answer"

# A view BELOW a known dashboard root is exactly what Issue #90 added, and must not warn.
run_provision "$MOCK_TARGET" --apk "$APK" "${SEED_BUILTIN[@]}" --home-dashboard /office/kitchen --no-tame
assert_success "a view below a known dashboard succeeds"
assert_not_contains 'does not currently list a dashboard' "$LAST_OUTPUT" "a view below a known dashboard root does not warn"

MOCK_HOME_DASHBOARDS=unreachable run_provision "$MOCK_TARGET" --apk "$APK" "${SEED_BUILTIN[@]}" --home-dashboard /office --no-tame
assert_success "an unqueryable dashboard catalogue does not fail provisioning"
assert_contains 'Could not verify the dashboard' "an unqueryable catalogue is reported as unverified, not as absent"
assert_not_contains 'does not currently list a dashboard' "$LAST_OUTPUT" "an unqueryable catalogue never claims the dashboard is missing"
unset MOCK_HOME_DASHBOARDS

MOCK_HOME_DASHBOARDS=transport-fail run_provision "$MOCK_TARGET" --apk "$APK" "${SEED_BUILTIN[@]}" --home-dashboard /office --no-tame
assert_success "a failed catalogue request does not fail provisioning"
assert_contains 'Could not verify the dashboard' "a failed catalogue request is reported as unverified"
unset MOCK_HOME_DASHBOARDS

# The app's home_dashboard validator is the single authority for the path grammar. A refusal must
# fail the run and, critically, must leave the question unanswered so guided setup still asks.
run_provision "$MOCK_TARGET" --apk "$APK" "${SEED_BUILTIN[@]}" --home-dashboard not-a-path --no-tame
assert_failure "a dashboard path the panel refuses fails the run"
assert_contains 'dashboard seed not applied' "a refused dashboard path is reported"
assert_contains 'guided setup still asks' "a refused dashboard path leaves the question open"
assert_not_contains 'api/v1/setup/home-dashboard' "$MOCK_CALL_LOG" \
  "a refused dashboard path records no wizard answer"

MOCK_SEED_CONFIG=fail run_provision "$MOCK_TARGET" --apk "$APK" "${SEED_BUILTIN[@]}" --home-dashboard /office --entity-filter on --no-tame
assert_failure "a failed seed write fails the run"
assert_not_contains 'api/v1/setup/(home-dashboard|entity-filter)' "$MOCK_CALL_LOG" \
  "no answer is recorded for a seed that did not persist"
unset MOCK_SEED_CONFIG

MOCK_SETUP_ANSWER=fail run_provision "$MOCK_TARGET" --apk "$APK" "${SEED_BUILTIN[@]}" --home-dashboard /office --no-tame
assert_failure "a seed whose answer could not be recorded fails the run"
assert_contains 'applied but not recorded as answered' "an unrecorded answer is distinguished from an unapplied value"
assert_contains 'Re-run the same command' "an unrecorded answer names the idempotent recovery"
unset MOCK_SETUP_ANSWER

# Both options configure ha-paneld's OWN renderer, so admission asks the panel which renderer its stored
# selection RESOLVES to rather than reading the stored string. The two are different facts: a blank
# `dashboard_package` resolves to the built-in renderer, and gating on the literal string refused exactly
# those panels — telling the operator they were "not set to use" a renderer they were in fact using.
# Every state below is fail-closed except a positive built-in resolution, and each refuses with its own
# reason: "running something else" and "nobody could confirm what it runs" must not be reported alike.

# A blank stored selection that the panel resolves to the built-in renderer. This is the live-hardware
# case the literal gate got wrong.
MOCK_DASHBOARD_PACKAGE='' MOCK_RENDERER=builtin \
  run_provision "$MOCK_TARGET" --apk "$APK" --home-dashboard /office --no-tame
assert_success "a blank dashboard selection the panel resolves to the built-in renderer accepts a seed"
assert_log_contains 'curl .*-X POST .*home_dashboard=/office.*api/v1/config' "a blank-but-built-in panel is seeded"
assert_not_contains 'not set to use|could not confirm' "$LAST_OUTPUT" \
  "a panel using the built-in renderer is never told it is not"

MOCK_DASHBOARD_PACKAGE=builtin run_provision "$MOCK_TARGET" --apk "$APK" --home-dashboard /office --no-tame
assert_success "a panel already on the built-in renderer accepts a seed without --builtin"
assert_log_contains 'curl .*-X POST .*home_dashboard=/office.*api/v1/config' "an already-built-in panel is seeded"
unset MOCK_DASHBOARD_PACKAGE

# A genuinely foreign renderer: refused, and named, because the operator's next move depends on which app
# the panel actually resolves to.
MOCK_RENDERER=io.homeassistant.companion.android.minimal \
  run_provision "$MOCK_TARGET" --apk "$APK" --home-dashboard /office --entity-filter on --no-tame
assert_failure "seeding a panel that resolves to a foreign renderer fails"
assert_contains 'resolves to io.homeassistant.companion.android.minimal' "the refusal names the renderer the panel resolves to"
assert_contains 'Add --builtin' "the refusal names the fix"
assert_not_contains 'home_dashboard=/office|dashboard_entity_learning=' "$MOCK_CALL_LOG" \
  "a refused seed mutates neither the dashboard nor the filter"
assert_not_contains 'api/v1/setup/(home-dashboard|entity-filter)' "$MOCK_CALL_LOG" \
  "a refused seed records no wizard answer"

# The panel answered, and its answer is that it cannot resolve its own selection. Distinct from a foreign
# renderer: there is no app to name, and the stored value itself is what needs correcting.
MOCK_RENDERER=unresolved run_provision "$MOCK_TARGET" --apk "$APK" --home-dashboard /office --no-tame
assert_failure "seeding a panel that cannot resolve its renderer fails"
assert_contains 'cannot resolve which renderer it is set to use' "the refusal reports an unresolvable selection as its own state"
assert_not_contains 'resolves to ' "$LAST_OUTPUT" "an unresolvable selection names no renderer"
assert_not_contains 'home_dashboard=/office' "$MOCK_CALL_LOG" "an unresolvable selection is not seeded"
assert_not_contains 'api/v1/setup/(home-dashboard|entity-filter)' "$MOCK_CALL_LOG" \
  "an unresolvable selection records no wizard answer"

# An ha-paneld too old to report a resolution. A LITERAL stored `builtin` still means the same thing on
# every version, so it is admitted; blank is exactly the value only the panel can interpret, so without an
# answer it stays ambiguous and is refused — as "could not confirm", never as "not set to use".
MOCK_RENDERER=absent MOCK_DASHBOARD_PACKAGE=builtin \
  run_provision "$MOCK_TARGET" --apk "$APK" --home-dashboard /office --no-tame
assert_success "a literal built-in selection is admitted by a panel too old to report a resolution"
assert_log_contains 'curl .*-X POST .*home_dashboard=/office.*api/v1/config' "the literal fallback seeds the panel"

MOCK_RENDERER=absent MOCK_DASHBOARD_PACKAGE='' \
  run_provision "$MOCK_TARGET" --apk "$APK" --home-dashboard /office --no-tame
assert_failure "a blank selection no panel can resolve fails closed"
assert_contains 'could not confirm this panel uses' "an unobtainable resolution is reported as unconfirmed"
assert_not_contains 'not set to use|resolves to ' "$LAST_OUTPUT" \
  "an unconfirmed panel is never described as running something else"
assert_not_contains 'home_dashboard=/office' "$MOCK_CALL_LOG" "an unconfirmed renderer is not seeded"
assert_not_contains 'api/v1/setup/(home-dashboard|entity-filter)' "$MOCK_CALL_LOG" \
  "an unconfirmed renderer records no wizard answer"

# An unreadable setup endpoint is the same fail-closed answer as an absent field, reached by a different
# route: a swallowed transport error must never become an assumed yes.
MOCK_SETUP=missing MOCK_DASHBOARD_PACKAGE='' \
  run_provision "$MOCK_TARGET" --apk "$APK" --home-dashboard /office --no-tame
assert_failure "an unreadable renderer answer fails closed"
assert_contains 'could not confirm this panel uses' "an unreadable answer is reported as unconfirmed"
assert_not_contains 'home_dashboard=/office' "$MOCK_CALL_LOG" "an unreadable answer is not seeded"
unset MOCK_SETUP

# --builtin selects the built-in renderer in the same command, so it is an override rather than a claim to
# be checked: the seed proceeds even when the panel currently resolves to something else.
MOCK_RENDERER=io.homeassistant.companion.android.minimal \
  run_provision "$MOCK_TARGET" --apk "$APK" "${SEED_BUILTIN[@]}" --home-dashboard /office --no-tame
assert_success "--builtin admits the seed whatever the panel currently resolves to"
assert_log_contains 'curl .*-X POST .*dashboard_package=builtin.*api/v1/config' "--builtin selects the built-in renderer first"
assert_log_contains 'curl .*-X POST .*home_dashboard=/office.*api/v1/config' "the override seeds the panel"
unset MOCK_RENDERER MOCK_DASHBOARD_PACKAGE

# Usage refusals happen before the panel is contacted at all.
run_provision "$MOCK_TARGET" --entity-filter maybe
assert_status 2 "an unrecognised --entity-filter value is rejected"
assert_contains 'must be on or off' "the invalid filter value names the accepted spellings"
assert_not_contains '^adb |^curl ' "$MOCK_CALL_LOG" "an invalid filter value contacts no panel"

run_provision "$MOCK_TARGET" --verify --home-dashboard /office
assert_status 2 "--home-dashboard is rejected with --verify"
assert_contains 'cannot be combined with --verify' "the verify refusal explains that verification never writes"
assert_not_contains '^adb |^curl ' "$MOCK_CALL_LOG" "a seed with --verify contacts no panel"

run_provision "$MOCK_TARGET" --reset-config --entity-filter on
assert_status 2 "--entity-filter is rejected with --reset-config"
assert_contains 'cannot be combined with --reset-config' "the reset refusal explains the conflicting intent"
assert_not_contains '^adb |^curl ' "$MOCK_CALL_LOG" "a seed with --reset-config contacts no panel"

# A seed configures the renderer's Home Assistant connection, so it cannot be trusted after a failed
# login: the panel would be pointed at a dashboard it has no credential to open.
MOCK_HA_LOGIN=rejected run_provision "$MOCK_TARGET" --apk "$APK" "${SEED_BUILTIN[@]}" --home-dashboard /office --no-tame
assert_failure "a seed after a failed Home Assistant login fails the run"
assert_contains 'skipped --home-dashboard/--entity-filter' "the skipped seed is reported, not silently dropped"
assert_not_contains 'home_dashboard=/office' "$MOCK_CALL_LOG" "a failed login writes no dashboard seed"
unset MOCK_HA_LOGIN

fi
[ "$PROVISION_TEST_SCOPE" != shard-renderer-seeding ] || finish_provision_test

if provision_scope_is all shard-release-integrity; then
# A panel without the manager must receive the exact pinned APK, verify it before installation, and
# start the authenticated installed native starter. The fake checksum tool makes this deterministic without network
# access while the call log proves the security-sensitive ordering.
run_provision "$MOCK_TARGET" --apk "$APK" --shizuku --no-tame
assert_success "missing Shizuku manager is bootstrapped"
assert_contains 'Shizuku.*inspecting the installed manager and signer' "Shizuku bootstrap reports its package and signer inspection"
assert_log_contains 'curl .*shizuku-v13\.6\.0\.r1086\.2650830c-release\.apk.*-o .*/shizuku\.apk' "Shizuku bootstrap downloads the curated manager"
assert_log_contains '^sha256sum .*/shizuku\.apk$' "Shizuku bootstrap verifies the downloaded manager"
assert_log_contains '^adb .* install -r .*/shizuku\.apk$' "Shizuku bootstrap installs the verified manager"
download_line="$(grep -nE 'curl .*shizuku-v13\.6\.0\.r1086\.2650830c-release\.apk' "$MOCK_CALL_LOG" | head -1 | cut -d: -f1)"
checksum_line="$(grep -nE '^sha256sum .*/shizuku\.apk$' "$MOCK_CALL_LOG" | head -1 | cut -d: -f1)"
install_line="$(grep -nE '^adb .* install -r .*/shizuku\.apk$' "$MOCK_CALL_LOG" | head -1 | cut -d: -f1)"
if [ -n "$download_line" ] && [ -n "$checksum_line" ] && [ -n "$install_line" ] && \
   [ "$download_line" -lt "$checksum_line" ] && [ "$checksum_line" -lt "$install_line" ]; then
  pass "Shizuku manager is downloaded, checksummed, then installed in order"
else
  fail_test "Shizuku manager is downloaded, checksummed, then installed in order"
fi
assert_log_contains '^adb .* shell monkey -p moe\.shizuku\.privileged\.api 1$' "Shizuku bootstrap launches the authenticated manager"
assert_log_contains '^adb .* shell test -x /data/app/.*/lib/arm64/libshizuku\.so$' "Shizuku bootstrap verifies the installed native starter"
assert_log_contains '^adb .* shell /data/app/.*/lib/arm64/libshizuku\.so$' "Shizuku bootstrap starts the service without external-storage shell code"
assert_not_contains '/storage/emulated/.*/start\.sh' "$MOCK_CALL_LOG" "Shizuku bootstrap never executes mutable external-storage script code"
assert_log_contains '^adb .* shell pm grant moe\.shizuku\.privileged\.api android\.permission\.WRITE_SECURE_SETTINGS$' "Shizuku bootstrap enables supported restart setup"

MOCK_SHIZUKU_INSPECT=block SHIZUKU_INSPECT_TIMEOUT_SECONDS=1 \
  run_provision "$MOCK_TARGET" --apk "$APK" --shizuku --no-tame
assert_failure "stuck Shizuku manager inspection returns nonzero at its host deadline"
assert_contains 'Shizuku manager inspection timed out after 1s' "Shizuku inspection timeout names the bounded failed step"
assert_contains 'Restore adb/package-manager responsiveness' "Shizuku inspection timeout gives an actionable recovery path"
assert_not_contains '^adb .* install -r -g .*ha-paneld.*\.apk$' "$MOCK_CALL_LOG" "timed-out Shizuku inspection leaves ha-paneld untouched"

# A stuck Shizuku package-manager operation must remain inside the root-helper ownership window,
# reject a competing provisioner, then be killed and reaped at its host deadline without replacing
# ha-paneld or leaving a lease heartbeat behind.
: > "$MOCK_CALL_LOG"
rm -f "$TMP/stale-helper-transaction" "$TMP/active-helper-transaction"
printf 'previous installed apk\n' > "$TMP/installed-apk"
SHIZUKU_INSTALL_PID_FILE="$TMP/shizuku-install-hang.pid"
SHIZUKU_INSTALL_OUTPUT="$TMP/shizuku-install-hang.out"
MOCK_SHIZUKU_INSTALL=block \
MOCK_SHIZUKU_INSTALL_PID_FILE="$SHIZUKU_INSTALL_PID_FILE" \
MOCK_STATE_DIR="$TMP" \
SHIZUKU_INSTALL_TIMEOUT_SECONDS=4 \
ROOT_HELPER_LEASE_GUARD_INTERVAL_SECONDS=0.05 \
  bash "$PROVISION" "$MOCK_TARGET" --apk "$HELPER_RELEASE_APK" --release-tag v0.9.4-rc1 --shizuku --no-tame \
    > "$SHIZUKU_INSTALL_OUTPUT" 2>&1 &
shizuku_owner_pid=$!
shizuku_install_ready=0
for _ in {1..100}; do
  if [ -s "$SHIZUKU_INSTALL_PID_FILE" ]; then shizuku_install_ready=1; break; fi
  /bin/sleep 0.05
done
if [ "$shizuku_install_ready" -eq 1 ]; then
  pass "blocked Shizuku package install exposes its subprocess lifecycle"
else
  LAST_OUTPUT="$SHIZUKU_INSTALL_OUTPUT"
  fail_test "blocked Shizuku package install exposes its subprocess lifecycle"
fi
touch "$TMP/stale-helper-transaction"
COMPETING_OUTPUT="$TMP/shizuku-competing-provision.out"
MOCK_ACTIVE_TRANSACTION=1 MOCK_STATE_DIR="$TMP" \
  bash "$PROVISION" "$MOCK_TARGET" --apk "$HELPER_RELEASE_APK" --release-tag v0.9.4-rc1 --no-tame \
    > "$COMPETING_OUTPUT" 2>&1
competing_status=$?
if [ "$competing_status" -ne 0 ] && grep -Eq 'active root-helper transaction|still owns' "$COMPETING_OUTPUT"; then
  pass "competing provisioner cannot recover a helper transaction during Shizuku installation"
else
  LAST_OUTPUT="$COMPETING_OUTPUT"
  fail_test "competing provisioner cannot recover a helper transaction during Shizuku installation"
fi
if wait "$shizuku_owner_pid"; then shizuku_owner_status=0; else shizuku_owner_status=$?; fi
LAST_OUTPUT="$SHIZUKU_INSTALL_OUTPUT"
if [ "$shizuku_owner_status" -ne 0 ]; then
  pass "stuck Shizuku package install returns nonzero at its host deadline"
else
  fail_test "stuck Shizuku package install returns nonzero at its host deadline"
fi
assert_contains 'Shizuku installation timed out after 4s' "Shizuku package timeout names the bounded failed step"
shizuku_install_pid="$(cat "$SHIZUKU_INSTALL_PID_FILE" 2>/dev/null || true)"
if [ -n "$shizuku_install_pid" ] && processes_gone "$shizuku_install_pid"; then
  pass "Shizuku package timeout reaps the blocked adb install"
else
  fail_test "Shizuku package timeout reaps the blocked adb install"
fi
if [ "$(grep -Ec 'helper-transaction-[0-9a-f]+.*lease-system' "$MOCK_CALL_LOG" || true)" -ge 2 ]; then
  pass "root-helper lease remains renewed throughout Shizuku package installation"
else
  fail_test "root-helper lease remains renewed throughout Shizuku package installation"
fi
assert_not_contains '^adb .* install -r -g .*ha-paneld.*\.apk$' "$MOCK_CALL_LOG" "timed-out Shizuku package install leaves ha-paneld untouched"
rm -f "$TMP/stale-helper-transaction" "$TMP/active-helper-transaction"

# A corrupt or substituted download must stop before the manager package or service is touched.
MOCK_SHIZUKU_DOWNLOAD_SHA=0000000000000000000000000000000000000000000000000000000000000000 \
  run_provision "$MOCK_TARGET" --apk "$APK" --shizuku --no-tame
assert_failure "Shizuku checksum mismatch returns nonzero"
assert_contains 'Shizuku download checksum mismatch' "Shizuku checksum failure names the integrity problem"
assert_contains 'Nothing was installed' "Shizuku checksum failure states the safe outcome"
assert_not_contains '^adb .* install -r .*/shizuku\.apk$' "$MOCK_CALL_LOG" "checksum failure blocks Shizuku installation"
assert_not_contains '^adb .* shell (monkey -p moe\.shizuku|sh .*/moe\.shizuku.*start\.sh|pm grant moe\.shizuku)' "$MOCK_CALL_LOG" "checksum failure blocks Shizuku launch and rearm"
assert_not_contains '^adb .* install -r -g .*ha-paneld\.apk$' "$MOCK_CALL_LOG" "fatal Shizuku bootstrap failure happens before replacing ha-paneld"

# Export composes with Shizuku setup: the verified backup must finish before any package mutation.
COMBINED_EXPORT="$TMP/panel-backup-with-shizuku.json"
run_provision "$MOCK_TARGET" --export "$COMBINED_EXPORT" --apk "$APK" --shizuku --no-tame
assert_success "export and Shizuku setup compose in one provisioning run"
assert_log_contains '^adb .* install -r .*/shizuku\.apk$' "combined export continues into Shizuku bootstrap"
export_line="$(grep -nE 'config/export' "$MOCK_CALL_LOG" | head -1 | cut -d: -f1)"
combined_install_line="$(grep -nE '^adb .* install( |$)' "$MOCK_CALL_LOG" | head -1 | cut -d: -f1)"
if [ -n "$export_line" ] && [ -n "$combined_install_line" ] && [ "$export_line" -lt "$combined_install_line" ]; then
  pass "combined export completes before package installation"
else
  fail_test "combined export completes before package installation"
fi

# A manager service-start failure must complete and launch the core agent for recovery, but the
# requested enhanced-access setup and any enclosing fleet operation must still fail truthfully.
MOCK_SHIZUKU_START=fail run_provision "$MOCK_TARGET" --apk "$APK" --shizuku --no-tame
assert_failure "Shizuku service-start failure returns nonzero"
assert_contains 'service did not start' "Shizuku start failure names the incomplete step"
assert_log_contains '^adb .* install -r -g .*ha-paneld\.apk$' "Shizuku start failure still installs the core agent"
assert_log_contains '^adb .* shell monkey -p io\.github\.maxlyth\.hapaneld -c android\.intent\.category\.LAUNCHER 1$' "Shizuku start failure still launches the core agent"

# A stuck device-side script must be terminated at a host deadline. It has the same recoverable
# semantics as any other service-start failure: install and relaunch the core agent, then return
# nonzero to both an individual caller and an enclosing fleet run.
SHIZUKU_HANG_PID_FILE="$TMP/shizuku-hang.pid"
MOCK_SHIZUKU_START=hang MOCK_SHIZUKU_HANG_PID_FILE="$SHIZUKU_HANG_PID_FILE" \
  SHIZUKU_START_TIMEOUT_SECONDS=1 run_provision "$MOCK_TARGET" --apk "$APK" --shizuku --no-tame
assert_failure "stuck Shizuku service start returns nonzero at its host deadline"
assert_contains 'service start timed out after 1s' "Shizuku timeout reports the bounded failed step"
assert_log_contains '^adb .* install -r -g .*ha-paneld\.apk$' "Shizuku timeout still installs the core agent"
assert_log_contains '^adb .* shell monkey -p io\.github\.maxlyth\.hapaneld -c android\.intent\.category\.LAUNCHER 1$' "Shizuku timeout still launches the core agent"
[ -s "$SHIZUKU_HANG_PID_FILE" ] || printf '# the Shizuku start fixture never recorded its PID before the deadline\n'
if [ -s "$SHIZUKU_HANG_PID_FILE" ] && processes_gone "$(cat "$SHIZUKU_HANG_PID_FILE")"; then
  pass "Shizuku timeout leaves no service-start worker behind"
else
  fail_test "Shizuku timeout leaves no service-start worker behind"
fi

# Hosts without GNU coreutils use Bash job control to isolate the host invocation in a process
# group. Force that fallback and retain a real child below the adb test double to prove both local
# processes are gone when provisioning resumes.
SHIZUKU_FALLBACK_PID_FILE="$TMP/shizuku-fallback-hang.pids"
MOCK_TIMEOUT_NON_GNU=1 MOCK_SHIZUKU_START=hang_with_child \
  MOCK_SHIZUKU_HANG_PID_FILE="$SHIZUKU_FALLBACK_PID_FILE" SHIZUKU_START_TIMEOUT_SECONDS=1 \
  run_provision "$MOCK_TARGET" --apk "$APK" --shizuku --no-tame
assert_failure "portable fallback bounds a stuck Shizuku service start"
assert_contains 'service start timed out after 1s' "portable fallback reports the bounded failed step"
if [ -s "$SHIZUKU_FALLBACK_PID_FILE" ]; then
  read -r fallback_parent_pid fallback_child_pid < "$SHIZUKU_FALLBACK_PID_FILE"
else
  fallback_parent_pid=""
  fallback_child_pid=""
fi
if [ -n "$fallback_parent_pid" ] && [ -n "$fallback_child_pid" ] && \
   processes_gone "$fallback_parent_pid" "$fallback_child_pid"; then
  pass "portable fallback leaves no service-start worker or child behind"
else
  fail_test "portable fallback leaves no service-start worker or child behind"
fi
assert_log_contains '^adb .* install -r -g .*ha-paneld\.apk$' "portable fallback timeout still installs the core agent"
assert_log_contains '^adb .* shell monkey -p io\.github\.maxlyth\.hapaneld -c android\.intent\.category\.LAUNCHER 1$' "portable fallback timeout still launches the core agent"

# Re-running --shizuku against the trusted curated manager (or a trusted newer manager) must not try
# to downgrade it. The manager stays locally approved; provisioning only restarts its service.
MOCK_SHIZUKU_VERSION_CODE=1086 MOCK_SHIZUKU_TRUSTED=1 run_provision "$MOCK_TARGET" --apk "$APK" --shizuku --no-tame
assert_success "trusted current Shizuku provisioning is idempotent"
assert_not_contains 'install -r .*shizuku\.apk' "$MOCK_CALL_LOG" "current Shizuku manager is not reinstalled"
assert_log_contains '^adb .* shell /data/app/.*/lib/arm64/libshizuku\.so$' "current trusted Shizuku is rearmed through its authenticated native starter"
assert_contains 'Configure.*toolbar overflow menu.*Enhanced access.*Enable' "Shizuku approval names the actual on-panel path"

MOCK_SHIZUKU_VERSION_CODE=1087 MOCK_SHIZUKU_TRUSTED=1 run_provision "$MOCK_TARGET" --apk "$APK" --shizuku --no-tame
assert_success "trusted newer Shizuku provisioning is idempotent"
assert_not_contains 'install -r .*shizuku\.apk' "$MOCK_CALL_LOG" "newer Shizuku manager is not downgraded"

SDK_ROOT="$TMP/android-sdk"
mkdir -p "$SDK_ROOT/build-tools/35.0.0"
ln -s "$FIXTURES/apksigner" "$SDK_ROOT/build-tools/35.0.0/apksigner"
PATH="$NO_SIGNER_FIXTURES" ANDROID_HOME= ANDROID_SDK_ROOT="$SDK_ROOT" \
  MOCK_SHIZUKU_VERSION_CODE=1087 MOCK_SHIZUKU_TRUSTED=1 \
  run_provision "$MOCK_TARGET" --apk "$APK" --shizuku --no-tame
assert_success "Shizuku signer verification discovers apksigner through ANDROID_SDK_ROOT"
assert_log_contains '^apksigner verify --print-certs .*/installed-shizuku\.apk$' "ANDROID_SDK_ROOT apksigner verifies an installed newer manager"

# A same-or-newer manager with an unverifiable signer must fail closed and tell the operator how to
# recover. In particular, it must not download over or start an untrusted package.
MOCK_SHIZUKU_VERSION_CODE=1087 MOCK_SHIZUKU_TRUSTED=0 run_provision "$MOCK_TARGET" --apk "$APK" --shizuku --no-tame
assert_failure "untrusted installed Shizuku manager fails closed"
assert_contains 'installed Shizuku manager cannot be trusted' "untrusted Shizuku failure names the trust boundary"
assert_contains 'remove the manager and re-run' "untrusted Shizuku failure gives a recovery path"
assert_not_contains 'shizuku-v13\.6\.0\.r1086.*-o .*/shizuku\.apk' "$MOCK_CALL_LOG" "untrusted newer Shizuku is not overwritten by a download"
assert_not_contains '^adb .* shell (monkey -p moe\.shizuku|sh .*/moe\.shizuku.*start\.sh|pm grant moe\.shizuku)' "$MOCK_CALL_LOG" "untrusted Shizuku is never launched or rearmed"
fi

[ "$PROVISION_TEST_SCOPE" != shard-release-integrity ] || finish_provision_test

if provision_scope_is core all shard-install-finish; then

# A launched app that never answers is not provisioned, even if adb install itself succeeded.
MOCK_HEALTH=fail run_provision "$MOCK_TARGET" --apk "$APK" --no-tame
assert_failure "launch timeout returns nonzero"
assert_contains '(did not start|not answering|launch|health)' "launch timeout explains what failed"
launcher_attempts="$(grep -Ec '^adb .* shell monkey -p io\.github\.maxlyth\.hapaneld -c android\.intent\.category\.LAUNCHER 1$' "$MOCK_CALL_LOG" || true)"
direct_attempts="$(grep -Ec '^adb .* shell am start -n io\.github\.maxlyth\.hapaneld/\.MainActivity$' "$MOCK_CALL_LOG" || true)"
if [ "$launcher_attempts" -eq 1 ] && [ "$direct_attempts" -eq 1 ]; then
  pass "launch timeout performs one launcher attempt and one distinct direct fallback"
else
  fail_test "launch timeout uses exactly one launcher and one direct fallback (got $launcher_attempts/$direct_attempts)"
fi
unset MOCK_HEALTH

# Some panels answer /health before the heavier diagnostics endpoint finishes root/capability probes.
MOCK_VERIFY=transient run_provision "$MOCK_TARGET" --apk "$APK" --no-tame
assert_success "healthy panel survives one transient slow diagnostics response"
# The runtime permissions a panel cannot be asked for in person are granted over adb instead. Both
# are asserted, because a grant block that silently loses a line still provisions and still passes
# every other check here: the app simply comes up with a capability quietly missing.
assert_log_contains '^adb .* shell pm grant io\.github\.maxlyth\.hapaneld android\.permission\.POST_NOTIFICATIONS$' "provisioning grants the notification permission"
assert_log_contains '^adb .* shell pm grant io\.github\.maxlyth\.hapaneld android\.permission\.RECORD_AUDIO$' "provisioning grants the microphone permission"
assert_log_contains 'appops get io.github.maxlyth.hapaneld WRITE_SETTINGS' "post-install WRITE_SETTINGS verification reads Android's authority"
assert_log_contains 'settings get secure enabled_accessibility_services' "post-install accessibility verification reads Android's authority"
# Granting is not verifying. A vendor build can accept `pm grant` and keep nothing, so the two
# runtime permissions are read back from the package manager the same way WRITE_SETTINGS is.
assert_contains 'microphone permission granted' "post-install verification reports the microphone permission"
assert_contains 'notification permission granted' "post-install verification reports the notification permission"
assert_not_contains 'Permissions . Microphone' "$LAST_OUTPUT" "a granted microphone permission offers no manual recovery"
assert_not_contains 'ha-paneld . Notifications' "$LAST_OUTPUT" "a granted notification permission offers no manual recovery"
# The platform-level read exists only in that verification block, so its presence is proof the block
# ran rather than proof the command is reachable from somewhere.
sdk_reads="$(grep -Ec '^adb .* shell getprop ro\.build\.version\.sdk$' "$MOCK_CALL_LOG" || true)"
if [ "$sdk_reads" -eq 1 ]; then
  pass "post-install verification reads the platform level exactly once"
else
  fail_test "post-install verification reads the platform level exactly once (got $sdk_reads)"
fi
unset MOCK_VERIFY

# Package replacement can start the app before adb finishes granting permissions. The app deliberately
# serves one complete last-known diagnostics report while it refreshes in the background. Final
# verification reads back the two Android settings it just changed instead of adding a second cache retry.
MOCK_VERIFY=stale run_provision "$MOCK_TARGET" --apk "$APK" --no-tame
assert_success "post-install verification ignores a stale permission snapshot"
if [ "$(grep -c '/api/v1/diag' "$MOCK_CALL_LOG")" -eq 1 ]; then
  pass "post-install verification does not poll the diagnostics cache"
else
  fail_test "post-install verification does not poll the diagnostics cache"
fi
unset MOCK_VERIFY

# A populated service list is not sufficient when Android's master accessibility switch is still off.
MOCK_A11Y_MASTER_FAIL=1 run_provision "$MOCK_TARGET" --apk "$APK" --no-tame
assert_failure "post-install verification rejects a disabled accessibility master switch"
assert_contains 'accessibility enabled' "disabled accessibility master switch names the failed item"
assert_contains 'Settings.*Accessibility.*ha-paneld' "disabled accessibility master switch gives manual recovery"
assert_log_contains 'settings get secure accessibility_enabled' "final verification reads Android's accessibility master authority"

# A runtime permission the panel refused is a capability quietly missing, not a cosmetic warning:
# provisioning fails and names the Settings path that finishes the job by hand.
MOCK_RECORD_AUDIO_GRANT_FAIL=1 run_provision "$MOCK_TARGET" --apk "$APK" --no-tame
assert_failure "post-install verification rejects a refused microphone grant"
assert_contains 'microphone permission granted' "refused microphone grant names the failed item"
assert_contains 'Permissions . Microphone' "refused microphone grant gives manual recovery"
# The pre-install version probe reads the same package report, so the call appearing in the log
# proves nothing on its own. What matters is that a read happens after the grants: a check answered
# from the probe taken before installation would be reporting the previous run's permissions.
last_grant_call="$(grep -n '^adb .* shell pm grant io\.github\.maxlyth\.hapaneld' "$MOCK_CALL_LOG" | tail -1 | cut -d: -f1)"
last_package_read="$(grep -n '^adb .* shell dumpsys package io\.github\.maxlyth\.hapaneld$' "$MOCK_CALL_LOG" | tail -1 | cut -d: -f1)"
if [ -n "$last_grant_call" ] && [ -n "$last_package_read" ] && [ "$last_package_read" -gt "$last_grant_call" ]; then
  pass "the grant record is read back after the grants, not from the pre-install version probe"
else
  fail_test "the grant record is read back after the grants, not from the pre-install version probe (grant=${last_grant_call:-none} read=${last_package_read:-none})"
fi
unset MOCK_RECORD_AUDIO_GRANT_FAIL

MOCK_POST_NOTIFICATIONS_GRANT_FAIL=1 run_provision "$MOCK_TARGET" --apk "$APK" --no-tame
assert_failure "post-install verification rejects a refused notification grant"
assert_contains 'notification permission granted' "refused notification grant names the failed item"
assert_contains 'ha-paneld . Notifications' "refused notification grant gives manual recovery"
unset MOCK_POST_NOTIFICATIONS_GRANT_FAIL

# POST_NOTIFICATIONS only became a runtime permission in Android 13. Failing an older panel for a
# grant its platform has no concept of would break provisioning across most of the supported fleet,
# so the platform level decides whether that check applies — and the panel is told so out loud.
MOCK_SDK=30 MOCK_POST_NOTIFICATIONS_GRANT_FAIL=1 run_provision "$MOCK_TARGET" --apk "$APK" --no-tame
assert_success "a pre-Android-13 panel is not failed for an ungrantable notification permission"
assert_contains 'not a runtime permission before Android 13' "a pre-Android-13 panel is told why the notification grant is not checked"
assert_contains 'microphone permission granted' "a pre-Android-13 panel is still checked for the microphone permission"
unset MOCK_SDK MOCK_POST_NOTIFICATIONS_GRANT_FAIL

# An unreadable platform level is not a licence to skip the check: it fails closed and reports what
# Android actually said about the grant.
MOCK_SDK= MOCK_POST_NOTIFICATIONS_GRANT_FAIL=1 run_provision "$MOCK_TARGET" --apk "$APK" --no-tame
assert_failure "an unreadable platform level still verifies the notification permission"
assert_contains 'notification permission granted' "an unreadable platform level names the failed item"
assert_not_contains 'not a runtime permission before Android 13' "$LAST_OUTPUT" "an unreadable platform level is not reported as an old platform"
unset MOCK_SDK MOCK_POST_NOTIFICATIONS_GRANT_FAIL

# Likewise, the final permission/HTTP checklist is a success gate rather than advisory output.
MOCK_VERIFY=fail run_provision "$MOCK_TARGET" --apk "$APK" --no-tame
assert_failure "final verification failure returns nonzero"
assert_contains '(verification|verify|incomplete|not reachable)' "verification failure gives recovery context"
unset MOCK_VERIFY

# Required configuration is part of provisioning success, not an advisory best-effort step.
MOCK_CONFIG=fail run_provision "$MOCK_TARGET" --apk "$APK" --id test-panel --no-tame
assert_failure "configuration failure returns nonzero"
assert_contains '(config.*not applied|provisioning incomplete)' "configuration failure explains what remains incomplete"
assert_not_contains '/api/v1/tame' "$MOCK_CALL_LOG" "required config failure suppresses optional vendor mutation"
unset MOCK_CONFIG

RESTORE="$TMP/restore.json"
printf '{"kind":"ha-paneld-config","schema":1,"values":{}}\n' > "$RESTORE"

# Restore input is validated before adb preflight or any mutation. The CLI imports config JSON only,
# and enforces the same 1 MiB request envelope as the server.
run_provision "$MOCK_TARGET" --apk "$APK" --restore "$TMP/missing-restore.json" --no-tame
assert_failure "missing restore input fails before panel contact"
assert_contains 'config import file not found' "missing restore input names the preflight failure"
assert_not_contains '^adb |^curl ' "$MOCK_CALL_LOG" "missing restore input performs no panel or network operation"

EMPTY_RESTORE="$TMP/empty-restore.json"
: > "$EMPTY_RESTORE"
run_provision "$MOCK_TARGET" --apk "$APK" --restore "$EMPTY_RESTORE" --no-tame
assert_failure "empty restore input fails before panel contact"
assert_contains 'config import file is empty' "empty restore input names the preflight failure"
assert_not_contains '^adb |^curl ' "$MOCK_CALL_LOG" "empty restore input performs no panel or network operation"

SYMLINK_RESTORE="$TMP/symlink-restore.json"
ln -s "$RESTORE" "$SYMLINK_RESTORE"
run_provision "$MOCK_TARGET" --apk "$APK" --restore "$SYMLINK_RESTORE" --no-tame
assert_failure "symlink restore input fails before panel contact"
assert_contains 'regular file, not a symbolic link' "symlink restore input names the preflight failure"
assert_not_contains '^adb |^curl ' "$MOCK_CALL_LOG" "symlink restore input performs no panel or network operation"

OVERSIZED_RESTORE="$TMP/oversized-restore.json"
truncate -s 1048577 "$OVERSIZED_RESTORE"
run_provision "$MOCK_TARGET" --apk "$APK" --restore "$OVERSIZED_RESTORE" --no-tame
assert_failure "oversized restore input fails before panel contact"
assert_contains 'config import file is too large.*maximum 1048576' "oversized restore input states the config envelope"
assert_not_contains '^adb |^curl ' "$MOCK_CALL_LOG" "oversized restore input performs no panel or network operation"

MALFORMED_RESTORE="$TMP/malformed-restore.json"
printf '{"kind":' > "$MALFORMED_RESTORE"
run_provision "$MOCK_TARGET" --apk "$APK" --restore "$MALFORMED_RESTORE" --no-tame
assert_failure "malformed restore input fails before panel contact"
assert_contains 'not a valid ha-paneld config JSON export' "malformed restore input names the structural failure"
assert_not_contains '^adb |^curl ' "$MOCK_CALL_LOG" "malformed restore input performs no panel or network operation"

WRONG_KIND_RESTORE="$TMP/wrong-kind-restore.json"
printf '{"kind":"ha-paneld-backup","schema":1,"values":{}}\n' > "$WRONG_KIND_RESTORE"
run_provision "$MOCK_TARGET" --apk "$APK" --restore "$WRONG_KIND_RESTORE" --no-tame
assert_failure "full backup passed to config restore fails before panel contact"
assert_contains 'not a valid ha-paneld config JSON export' "wrong-kind restore names the config-only contract"
assert_not_contains '^adb |^curl ' "$MOCK_CALL_LOG" "wrong-kind restore performs no panel or network operation"

NON_STRING_RESTORE="$TMP/non-string-restore.json"
printf '{"kind":"ha-paneld-config","schema":1,"values":{"keep_awake":true}}\n' > "$NON_STRING_RESTORE"
run_provision "$MOCK_TARGET" --apk "$APK" --restore "$NON_STRING_RESTORE" --no-tame
assert_failure "non-string config values fail before panel contact"
assert_contains 'not a valid ha-paneld config JSON export' "non-string config values name the structural failure"
assert_not_contains '^adb |^curl ' "$MOCK_CALL_LOG" "non-string config values perform no panel or network operation"

INVALID_METADATA_RESTORE="$TMP/invalid-metadata-restore.json"
printf '{"kind":"ha-paneld-config","schema":1,"exported_at":false,"values":{}}\n' > "$INVALID_METADATA_RESTORE"
run_provision "$MOCK_TARGET" --apk "$APK" --restore "$INVALID_METADATA_RESTORE" --no-tame
assert_failure "invalid config-export metadata fails before panel contact"
assert_contains 'not a valid ha-paneld config JSON export' "invalid metadata names the structural failure"
assert_not_contains '^adb |^curl ' "$MOCK_CALL_LOG" "invalid metadata performs no panel or network operation"

EXTRA_FIELD_RESTORE="$TMP/extra-field-restore.json"
printf '{"kind":"ha-paneld-config","schema":1,"values":{},"extra":false}\n' > "$EXTRA_FIELD_RESTORE"
run_provision "$MOCK_TARGET" --apk "$APK" --restore "$EXTRA_FIELD_RESTORE" --no-tame
assert_failure "unsupported config-export fields fail before panel contact"
assert_contains 'not a valid ha-paneld config JSON export' "unsupported field names the structural failure"
assert_not_contains '^adb |^curl ' "$MOCK_CALL_LOG" "unsupported field performs no panel or network operation"

MOCK_RESTORE=fail run_provision "$MOCK_TARGET" --apk "$APK" --restore "$RESTORE" --no-tame
assert_failure "restore failure returns nonzero"
assert_contains '(import failed|provisioning incomplete)' "restore failure explains what remains incomplete"
unset MOCK_RESTORE

MOCK_CONFIG=timeout run_provision "$MOCK_TARGET" --apk "$APK" --id test-panel --no-tame
assert_failure "stalled config POST returns nonzero"
assert_log_contains 'curl .*--connect-timeout 1 --max-time 1 .* -X POST .*api/v1/config' "config POST carries connect and total deadlines"
unset MOCK_CONFIG

MOCK_RESTORE=timeout run_provision "$MOCK_TARGET" --apk "$APK" --restore "$RESTORE" --no-tame
assert_failure "stalled config import returns nonzero"
assert_log_contains 'curl .*--connect-timeout 1 --max-time 1 .*api/v1/config/import' "config import carries connect and total deadlines"
unset MOCK_RESTORE

MOCK_RESTORE=approval run_provision "$MOCK_TARGET" --apk "$APK" --restore "$RESTORE" --no-tame
assert_failure "approval-required restore returns nonzero"
assert_contains 'config import requires approval on the panel; no settings were imported' "approval-required import is not reported as successful"
assert_contains 'Review approvals.*approve the config import.*retry the identical command' "approval-required import gives the on-panel approval and retry path"
unset MOCK_RESTORE

for restore_case in malformed-approval unexpected-2xx; do
  MOCK_RESTORE="$restore_case" run_provision "$MOCK_TARGET" --apk "$APK" --restore "$RESTORE" --no-tame
  assert_failure "$restore_case import response returns nonzero"
  assert_contains 'import failed:.*unexpected HTTP' "$restore_case import response is rejected"
done
unset MOCK_RESTORE

for mode in flow_fail rejected token_missing timeout; do
  HA_PASSWORD_SENTINEL='ha-login-secret-7f2c91'
  MOCK_HA_LOGIN="$mode" run_provision "$MOCK_TARGET" --apk "$APK" --builtin --ha-url https://ha.test --ha-user owner --ha-pass "$HA_PASSWORD_SENTINEL" --no-tame
  assert_failure "HA login $mode returns nonzero"
  assert_contains '(login|token).*failed|login rejected|no usable|provisioning incomplete' "HA login $mode explains the authentication failure"
  assert_not_contains "$HA_PASSWORD_SENTINEL" "$MOCK_CALL_LOG" "HA login $mode keeps the password out of descendant argv"
  assert_not_contains "$HA_PASSWORD_SENTINEL" "$LAST_OUTPUT" "HA login $mode keeps the password out of output"
  if grep -E '/api/v1/config.*dashboard_package=builtin|dashboard_package=builtin.*/api/v1/config' "$MOCK_CALL_LOG" >/dev/null; then
    fail_test "HA login $mode does not activate the built-in renderer"
  else
    pass "HA login $mode does not activate the built-in renderer"
  fi
done
assert_log_contains 'curl .*--connect-timeout 1 .*--max-time 1 .*https://ha\.test/auth/login_flow' "HA authentication POST carries connect and total deadlines"
unset MOCK_HA_LOGIN

HA_TOKEN_SENTINEL='ha-token-secret-419ad8'
MOCK_HA_TOKEN=invalid run_provision "$MOCK_TARGET" --apk "$APK" --builtin --ha-url https://ha.test --ha-token "$HA_TOKEN_SENTINEL" --no-tame
assert_failure "invalid long-lived HA token returns nonzero"
assert_contains '(rejected the token|long-lived access token|provisioning incomplete)' "invalid long-lived HA token explains authentication recovery"
assert_not_contains "$HA_TOKEN_SENTINEL" "$MOCK_CALL_LOG" "HA token validation keeps the token out of descendant argv"
assert_not_contains "$HA_TOKEN_SENTINEL" "$LAST_OUTPUT" "HA token validation keeps the token out of output"
if grep -E '/api/v1/config.*dashboard_package=builtin|dashboard_package=builtin.*/api/v1/config' "$MOCK_CALL_LOG" >/dev/null; then
  fail_test "invalid long-lived HA token does not activate the built-in renderer"
else
  pass "invalid long-lived HA token does not activate the built-in renderer"
fi
unset MOCK_HA_TOKEN

MQTT_PASSWORD_SENTINEL='mqtt-secret-20ec73'
run_provision "$MOCK_TARGET" --apk "$APK" --mqtt-pass "$MQTT_PASSWORD_SENTINEL" --no-tame
assert_success "legacy MQTT password input is normalized before descendant commands"
assert_not_contains "$MQTT_PASSWORD_SENTINEL" "$MOCK_CALL_LOG" "MQTT password stays out of curl, adb and helper argv"
assert_not_contains "$MQTT_PASSWORD_SENTINEL" "$LAST_OUTPUT" "MQTT password stays out of provisioning output"
assert_log_contains 'curl .*--data-urlencode mqtt_password@.*/mqtt-password .*api/v1/config' "MQTT password reaches config through a private file"

HA_TOKEN_FILE="$TMP/ha-token.txt"
printf '%s\n' 'file-token-secret-a4b781' > "$HA_TOKEN_FILE"
chmod 600 "$HA_TOKEN_FILE"
MOCK_HA_TOKEN=invalid run_provision "$MOCK_TARGET" --apk "$APK" --builtin --ha-url https://ha.test --ha-token-file "$HA_TOKEN_FILE" --no-tame
assert_failure "Home Assistant token file is accepted and validated"
assert_not_contains 'file-token-secret-a4b781' "$MOCK_CALL_LOG" "token-file content stays out of descendant argv"

EMPTY_SECRET_FILE="$TMP/empty-secret.txt"
: > "$EMPTY_SECRET_FILE"
run_provision "$MOCK_TARGET" --verify --mqtt-pass-file "$EMPTY_SECRET_FILE"
assert_failure "empty credential file fails before panel contact"
assert_contains 'MQTT password file is empty' "empty credential file names the unsafe input"
assert_not_contains '^adb |^curl ' "$MOCK_CALL_LOG" "empty credential file performs no panel or network operation"

NEWLINE_SECRET_FILE="$TMP/newline-secret.txt"
printf '\r\n' > "$NEWLINE_SECRET_FILE"
chmod 600 "$NEWLINE_SECRET_FILE"
run_provision "$MOCK_TARGET" --verify --mqtt-pass-file "$NEWLINE_SECRET_FILE"
assert_failure "line-ending-only credential file fails before panel contact"
assert_contains 'contains no credential text' "line-ending-only credential names the empty normalized value"
assert_not_contains '^adb |^curl ' "$MOCK_CALL_LOG" "line-ending-only credential performs no panel or network operation"

MULTILINE_SECRET_FILE="$TMP/multiline-secret.txt"
printf 'credential\n\n' > "$MULTILINE_SECRET_FILE"
chmod 600 "$MULTILINE_SECRET_FILE"
run_provision "$MOCK_TARGET" --verify --mqtt-pass-file "$MULTILINE_SECRET_FILE"
assert_failure "credential with extra trailing blank line fails before panel contact"
assert_contains 'contains more than one text line' "extra trailing blank line violates the single-line contract"
assert_not_contains '^adb |^curl ' "$MOCK_CALL_LOG" "multiline credential performs no panel or network operation"

PUBLIC_SECRET_FILE="$TMP/public-secret.txt"
printf '%s' 'not-owner-only' > "$PUBLIC_SECRET_FILE"
chmod 644 "$PUBLIC_SECRET_FILE"
run_provision "$MOCK_TARGET" --verify --mqtt-pass-file "$PUBLIC_SECRET_FILE"
assert_failure "group-readable credential file fails before panel contact"
assert_contains 'readable by other users' "group-readable credential names the permission problem"
assert_not_contains '^adb |^curl ' "$MOCK_CALL_LOG" "group-readable credential performs no panel or network operation"

run_provision "$MOCK_TARGET" --verify --mqtt-pass first-secret --mqtt-pass second-secret
assert_failure "duplicate literal credential sources are rejected"
assert_contains 'source was supplied more than once' "duplicate literal credential names the ambiguous source"
assert_not_contains '^adb |^curl ' "$MOCK_CALL_LOG" "duplicate literal credential performs no panel or network operation"

chmod 600 "$PUBLIC_SECRET_FILE"
run_provision "$MOCK_TARGET" --verify --mqtt-pass-file "$PUBLIC_SECRET_FILE" --mqtt-pass-file "$HA_TOKEN_FILE"
assert_failure "duplicate credential-file sources are rejected"
assert_contains 'source was supplied more than once' "duplicate credential files name the ambiguous source"
assert_not_contains '^adb |^curl ' "$MOCK_CALL_LOG" "duplicate credential files perform no panel or network operation"

SYMLINK_SECRET_FILE="$TMP/symlink-secret.txt"
ln -s "$HA_TOKEN_FILE" "$SYMLINK_SECRET_FILE"
run_provision "$MOCK_TARGET" --verify --ha-token-file "$SYMLINK_SECRET_FILE"
assert_failure "credential symlink fails before panel contact"
assert_contains 'must not be a symbolic link' "credential symlink names the safe-file requirement"
assert_not_contains '^adb |^curl ' "$MOCK_CALL_LOG" "credential symlink performs no panel or network operation"

run_provision "$MOCK_TARGET" --ha-token token-without-server
assert_failure "long-lived HA token without a server URL returns nonzero"
assert_contains '(--ha-token.*require.*--ha-url|require.*--ha-url)' "token without server URL gives a direct correction"

run_provision "$MOCK_TARGET" --builtin --ha-url https://ha.test
assert_failure "built-in renderer with a server URL but no credentials returns nonzero"
assert_contains 'printed guided-setup URL' "credentialless built-in setup directs the normal browser sign-in path"

# ── Closing guidance: the installer renders the panel's own setup journey ────────────────────────
# The rule these pin: the next step named at the end of a run must be the one the panel itself is
# waiting for. The previous contract printed the Home Assistant OAuth deep link on every run that
# had not completed OAuth — including a brand-new panel, where that link cannot work at all because
# the sign-in endpoint refuses the request until an ha_url exists.
MOCK_SETUP=identity run_provision "$MOCK_TARGET" --apk "$APK" --no-tame
assert_success "fresh install completes and hands over to guided setup"
assert_contains 'Next: confirm this panel.s name.*/setup' "a fresh panel is sent to guided setup, not to a sign-in it cannot complete"
assert_not_contains 'cfg-ha-oauth' "$LAST_OUTPUT" "a fresh panel is never offered the Home Assistant sign-in deep link"
assert_contains 'installed and verified' "an unfinished panel is not reported as fully provisioned"
assert_contains 'tap Set up' "guided setup names the on-panel route as well as the browser one"

MOCK_SETUP=ha_credentials run_provision "$MOCK_TARGET" --apk "$APK" --no-tame
assert_success "a panel awaiting Home Assistant sign-in completes provisioning"
assert_contains 'Next: sign in to Home Assistant.*/setup' "the sign-in step is named only once the panel is actually ready for it"

# A live journey can carry a home_dashboard stage that postdates the original wording table. The
# generic fallback keeps the output correct, but a stage the panel actually blocks on should be named.
MOCK_SETUP=home_dashboard run_provision "$MOCK_TARGET" --apk "$APK" --no-tame
assert_success "a panel awaiting a dashboard choice completes provisioning"
assert_contains 'Next: choose which Home Assistant dashboard to show.*/setup' "the dashboard-choice step is named specifically"

MOCK_SETUP=mqtt_auth_failed run_provision "$MOCK_TARGET" --apk "$APK" --no-tame
assert_success "a panel with a rejecting broker completes provisioning"
assert_contains 'Next: get MQTT connected' "a rejecting broker is named as the next step"
assert_contains 'rejected these credentials' "the broker failure explains itself rather than printing a state token"

MOCK_SETUP=in_flight run_provision "$MOCK_TARGET" --apk "$APK" --no-tame
assert_success "a panel mid-connect completes provisioning"
assert_contains 'Nothing to do yet' "work in progress is reported as progress, never as a fault to fix"
assert_not_contains 'Next: ' "$LAST_OUTPUT" "an in-flight stage does not issue an instruction"

MOCK_SETUP=repair run_provision "$MOCK_TARGET" --apk "$APK" --no-tame
assert_success "a previously working panel completes provisioning"
assert_contains 'This panel needs attention' "a re-armed journey reads as repair, not as a first run"

MOCK_SETUP=unknown_stage run_provision "$MOCK_TARGET" --apk "$APK" --no-tame
assert_success "an unrecognised journey stage completes provisioning"
assert_contains 'Next: finish guided setup.*/setup' "a stage this script does not know still yields correct generic guidance"
assert_not_contains 'opaque_token' "$LAST_OUTPUT" "an unrecognised stage never leaks a machine token into user-facing output"

MOCK_SETUP=complete run_provision "$MOCK_TARGET" --apk "$APK" --no-tame
assert_success "a fully configured panel completes provisioning"
assert_contains 'provisioned and verified' "a finished panel is reported as provisioned"
assert_not_contains 'Next: ' "$LAST_OUTPUT" "a finished panel is given no next step"

# ── Panel storage headroom ──────────────────────────────────────────────────────────────────────
# Snapshot capacity is determined by the actual capture, not a guessed free-space floor. Even a
# malformed df response must not preempt a package replacement that can capture and install.
MOCK_DATA_AVAIL_KB=1024 run_provision "$MOCK_TARGET" --apk "$APK" --no-tame
assert_success "a low reported free-space number does not impose an arbitrary upgrade floor"
assert_marker_captured "the real snapshot attempt, not df arithmetic, proves that this backup fits"
assert_log_contains '^adb .* install' "a successful capture on a constrained panel reaches APK install"
assert_not_contains 'shell df -P -k /data' "$MOCK_CALL_LOG" "ordinary provisioning does not query a fixed /data admission floor"

# Pin the whole shipped backup implementation, not one spelling: no executable data-volume df,
# stat or du probe may silently recreate a guessed capacity gate. /system helper-capacity probes
# live outside this backup range and remain intentionally allowed.
backup_source="$(sed -n '/^snapshot_prepared_database()/,/^reset_panel_config()/p' "$PROVISION" | sed '/^[[:space:]]*#/d')"
if printf '%s\n' "$backup_source" 2>/dev/null | grep -Eq '(^|[;&|[:space:]])(df|du|stat)([[:space:]]|$).*(/data|databases|ha-paneld\.db)'; then
  fail_test "the complete backup path has no data-volume df, stat or du capacity gate"
else pass "the complete backup path has no data-volume df, stat or du capacity gate"; fi

MOCK_DATA_CAPACITY=wrapped run_provision "$MOCK_TARGET" --apk "$APK" --no-tame
assert_success "an unreadable df response does not block an otherwise successful upgrade"
assert_marker_captured "a malformed df response cannot replace the actual capture result"
assert_not_contains 'shell df -P -k /data' "$MOCK_CALL_LOG" "malformed capacity data is irrelevant because the fixed gate is absent"

fi
[ "$PROVISION_TEST_SCOPE" != shard-install-finish ] || finish_provision_test
fi

# ── Data-store snapshot: acknowledged quiescence with one legacy fallback ──────────────────────
if provision_scope_is backup core all shard-backup; then
reset_db_txn_state
MOCK_UPGRADE_PREPARE=ready run_provision "$MOCK_TARGET" --apk "$APK" --no-tame
assert_success "a receipt-capable build upgrades through the quiesced direct-copy path"
assert_marker_captured "the receipt-bound direct copy earns the captured marker"
assert_log_contains 'PREPARE_UPGRADE.*--es nonce [0-9a-f]{32}' "PREPARE carries one exact lowercase nonce"
assert_log_contains 'exec-out su 0 cat /data/data/io.github.maxlyth.hapaneld/databases/ha-paneld.db' "the join-style root route copies the closed database with binary-safe exec-out"
assert_not_contains '\.hapaneld-db-txn\.' "$MOCK_CALL_LOG" "the READY path creates no on-panel staging"
assert_not_contains 'shell df -P -k /data' "$MOCK_CALL_LOG" "the READY path has no fixed capacity floor"
prepare_line="$(grep -n 'PREPARE_UPGRADE' "$MOCK_CALL_LOG" | head -1 | cut -d: -f1)"
copy_line="$(grep -n 'exec-out .*ha-paneld.db' "$MOCK_CALL_LOG" | head -1 | cut -d: -f1)"
install_line="$(grep -n '^adb .* install' "$MOCK_CALL_LOG" | head -1 | cut -d: -f1)"
if [ -n "$prepare_line" ] && [ -n "$copy_line" ] && [ -n "$install_line" ] && \
   [ "$prepare_line" -lt "$copy_line" ] && [ "$copy_line" -lt "$install_line" ]; then
  pass "PREPARE then direct copy then APK install occur in order"
else fail_test "PREPARE then direct copy then APK install occur in order"; fi
direct_receipt="$(find "$TMP/auto-backups" -maxdepth 1 -type f -name '*.break-glass.db.backup-receipt.txt' | head -1)"
if [ -n "$direct_receipt" ] && grep -Eq '^database_sha256_host=[0-9a-f]{64}$' "$direct_receipt"; then
  pass "the direct-copy receipt records the mandatory host digest"
else fail_test "the direct-copy receipt records the mandatory host digest"; fi
direct_database="$(sed -n 's/^database=//p' "$direct_receipt" 2>/dev/null)"
direct_receipt_sha="$(sed -n 's/^database_sha256_host=//p' "$direct_receipt" 2>/dev/null)"
direct_actual_sha="$(/usr/bin/sha256sum "$direct_database" 2>/dev/null | awk '{print $1}')"
if [ -n "$direct_database" ] && [ "$direct_receipt_sha" = "$direct_actual_sha" ]; then
  pass "the direct-copy receipt digest equals the actual published database bytes"
else fail_test "the direct-copy receipt digest equals the actual published database bytes"; fi
prepare_nonce="$(sed -n 's/.*PREPARE_UPGRADE.*--es nonce \([0-9a-f]\{32\}\).*/\1/p' "$MOCK_CALL_LOG" | head -1)"
if grep -Fq "quiescence_nonce=$prepare_nonce" "$direct_receipt" 2>/dev/null; then
  pass "the published receipt binds the exact acknowledged nonce"
else fail_test "the published receipt binds the exact acknowledged nonce"; fi
assert_not_contains 'RELEASE_UPGRADE' "$MOCK_CALL_LOG" "successful package replacement does not release the terminated old process"
if grep -q 'timeout="${UPGRADE_PREPARE_TIMEOUT_SECONDS:-45}"' "$PROVISION" &&
   grep -q 'case "$timeout" in .*timeout=45' "$PROVISION"; then
  pass "the host PREPARE budget covers the app's orderly teardown and digest window"
else fail_test "the host PREPARE budget covers the app's orderly teardown and digest window"; fi
nonce_owner_line="$(grep -n 'UPGRADE_QUIESCE_NONCE="\$nonce"' "$PROVISION" | head -1 | cut -d: -f1)"
prepare_send_line="$(grep -n -- '-a io.github.maxlyth.hapaneld.action.PREPARE_UPGRADE' "$PROVISION" | head -1 | cut -d: -f1)"
if [ -n "$nonce_owner_line" ] && [ -n "$prepare_send_line" ] && [ "$nonce_owner_line" -lt "$prepare_send_line" ]; then
  pass "the host retains nonce custody before PREPARE can arm the app"
else fail_test "the host retains nonce custody before PREPARE can arm the app"; fi
cleanup_discard_line="$(grep -n 'if type discard_db_snapshot_txn' "$PROVISION" | head -1 | cut -d: -f1)"
cleanup_release_line="$(grep -n 'if type release_upgrade_quiescence' "$PROVISION" | head -1 | cut -d: -f1)"
if [ -n "$cleanup_discard_line" ] && [ -n "$cleanup_release_line" ] && [ "$cleanup_discard_line" -lt "$cleanup_release_line" ]; then
  pass "abort cleanup releases quiescence only after owned snapshot cleanup"
else fail_test "abort cleanup releases quiescence only after owned snapshot cleanup"; fi

# Direct host artifacts use the same deferred-signal ownership primitive as the legacy publisher.
# Creation and registration are indivisible, and the successful handoff changes owners before
# signals are replayed.
direct_db_defer_line="$(grep -n '^  snapshot_txn_defer_host_signals$' "$PROVISION" | sed -n '1s/:.*//p')"
direct_receipt_defer_line="$(grep -n '^  snapshot_txn_defer_host_signals$' "$PROVISION" | sed -n '2s/:.*//p')"
direct_handoff_defer_line="$(grep -n '^  snapshot_txn_defer_host_signals$' "$PROVISION" | sed -n '3s/:.*//p')"
direct_db_restore_line="$(grep -n '^  snapshot_txn_restore_host_signals$' "$PROVISION" | sed -n '1s/:.*//p')"
direct_receipt_restore_line="$(grep -n '^  snapshot_txn_restore_host_signals$' "$PROVISION" | sed -n '2s/:.*//p')"
direct_handoff_restore_line="$(grep -n '^  snapshot_txn_restore_host_signals$' "$PROVISION" | sed -n '3s/:.*//p')"
direct_db_create_line="$(grep -n 'if \[ -e "\$host_db" \].*: > "\$host_db"' "$PROVISION" | cut -d: -f1)"
direct_db_owner_line="$(grep -n '^  SNAPSHOT_TXN_HOST_DB="\$host_db"$' "$PROVISION" | cut -d: -f1)"
direct_receipt_create_line="$(grep -n 'if \[ -e "\$receipt" \].*: > "\$receipt"' "$PROVISION" | head -1 | cut -d: -f1)"
direct_receipt_owner_line="$(grep -n '^  SNAPSHOT_TXN_HOST_RECEIPT="\$receipt"$' "$PROVISION" | head -1 | cut -d: -f1)"
direct_db_disown_line="$(grep -n '^  SNAPSHOT_TXN_HOST_DB=""$' "$PROVISION" | cut -d: -f1)"
direct_receipt_disown_line="$(grep -n '^  SNAPSHOT_TXN_HOST_RECEIPT=""$' "$PROVISION" | cut -d: -f1)"
if [ -n "$direct_db_defer_line" ] && [ "$direct_db_defer_line" -lt "$direct_db_create_line" ] &&
   [ "$direct_db_create_line" -lt "$direct_db_owner_line" ] && [ "$direct_db_owner_line" -lt "$direct_db_restore_line" ]; then
  pass "direct database creation and cleanup registration are signal-atomic"
else fail_test "direct database creation and cleanup registration are signal-atomic"; fi
if [ -n "$direct_receipt_defer_line" ] && [ "$direct_receipt_defer_line" -lt "$direct_receipt_create_line" ] &&
   [ "$direct_receipt_create_line" -lt "$direct_receipt_owner_line" ] && [ "$direct_receipt_owner_line" -lt "$direct_receipt_restore_line" ]; then
  pass "direct receipt creation and cleanup registration are signal-atomic"
else fail_test "direct receipt creation and cleanup registration are signal-atomic"; fi
if grep -q 'set -o noclobber; : > "\$host_db"' "$PROVISION" &&
   grep -q 'set -o noclobber; : > "\$receipt"' "$PROVISION"; then
  pass "direct database and receipt creation both enforce shell noclobber"
else fail_test "direct database and receipt creation both enforce shell noclobber"; fi
if [ -n "$direct_handoff_defer_line" ] && [ "$direct_handoff_defer_line" -lt "$direct_db_disown_line" ] &&
   [ "$direct_db_disown_line" -lt "$direct_receipt_disown_line" ] &&
   [ "$direct_receipt_disown_line" -lt "$direct_handoff_restore_line" ]; then
  pass "direct database and receipt handoff is signal-atomic before signals replay"
else fail_test "direct database and receipt handoff is signal-atomic before signals replay"; fi

# Runtime custody on each side of that handoff. Before acceptance, an interrupt removes the owned
# partial and RELEASEs the app. After acceptance, an interrupt during package installation preserves
# both published artifacts while still reaping the blocked child and RELEASEing the app.
reset_db_txn_state
: > "$MOCK_CALL_LOG"
direct_copy_pid_file="$TMP/direct-copy-block.pid"
direct_copy_output="$TMP/direct-copy-block-output.txt"
rm -f "$direct_copy_pid_file"
set -m
MOCK_UPGRADE_PREPARE=ready MOCK_DIRECT_COPY=block MOCK_DIRECT_COPY_PID_FILE="$direct_copy_pid_file" \
ADB_COMMAND_TIMEOUT_SECONDS=5 HAPANELD_SKIP_AUTO_EXPORT=1 \
HAPANELD_CONFIG_BACKUP_DIR="$TMP/auto-backups" MOCK_STATE_DIR="$TMP" \
  bash "$PROVISION" "$MOCK_TARGET" --apk "$APK" --no-tame --allow-unsigned-helper > "$direct_copy_output" 2>&1 &
direct_copy_owner_pid=$!
ACTIVE_PUBLICATION_PGID="$direct_copy_owner_pid"
set +m
direct_copy_ready=0
for _ in {1..100}; do
  if [ -s "$direct_copy_pid_file" ]; then direct_copy_ready=1; break; fi
  /bin/sleep 0.05
done
if [ "$direct_copy_ready" -eq 1 ]; then
  pass "direct-copy interruption reaches the blocked binary transfer"
else
  LAST_OUTPUT="$direct_copy_output"
  fail_test "direct-copy interruption reaches the blocked binary transfer"
fi
direct_partial_db="$(find "$TMP/auto-backups" -maxdepth 1 -type f -name '*.break-glass.db' | head -1)"
if [ -n "$direct_partial_db" ]; then
  pass "the direct database is registered before its transfer can block"
else
  LAST_OUTPUT="$direct_copy_output"
  fail_test "the direct database is registered before its transfer can block"
fi
direct_interrupt_started="$(date +%s)"
kill -INT -- "-$direct_copy_owner_pid" 2>/dev/null || true
if wait "$direct_copy_owner_pid"; then direct_copy_status=0; else direct_copy_status=$?; fi
direct_interrupt_elapsed=$(( $(date +%s) - direct_interrupt_started ))
ACTIVE_PUBLICATION_PGID=""
if [ "$direct_copy_status" -eq 130 ]; then
  pass "interrupting a direct copy preserves signal status"
else
  LAST_OUTPUT="$direct_copy_output"
  fail_test "interrupting a direct copy preserves signal status (got $direct_copy_status)"
fi
if find "$TMP/auto-backups" -maxdepth 1 -type f -name '*.break-glass.db*' | grep -q .; then
  LAST_OUTPUT="$direct_copy_output"
  fail_test "an interrupted unaccepted direct pair is removed"
else pass "an interrupted unaccepted direct pair is removed"; fi
direct_copy_blocked_pid="$(cat "$direct_copy_pid_file" 2>/dev/null || true)"
if [ -n "$direct_copy_blocked_pid" ] && processes_gone "-$direct_copy_blocked_pid"; then
  pass "direct-copy interruption reaps the entire nested adb process group"
else fail_test "direct-copy interruption reaps the entire nested adb process group"; fi
if [ "$direct_interrupt_elapsed" -lt 4 ]; then
  pass "direct-copy interruption beats the nested five-second deadline"
else fail_test "direct-copy interruption beats the nested five-second deadline (elapsed ${direct_interrupt_elapsed}s)"; fi
if [ "$(grep -c 'RELEASE_UPGRADE' "$MOCK_CALL_LOG")" = 1 ]; then
  pass "direct-copy interruption RELEASEs quiescence exactly once"
else fail_test "direct-copy interruption RELEASEs quiescence exactly once"; fi

reset_db_txn_state
: > "$MOCK_CALL_LOG"
direct_install_pid_file="$TMP/direct-handoff-install.pid"
direct_handoff_output="$TMP/direct-handoff-output.txt"
rm -f "$direct_install_pid_file" "$TMP/active-helper-transaction"
set -m
MOCK_UPGRADE_PREPARE=ready MOCK_DIRECT_COPY=ok MOCK_APK_INSTALL=block \
MOCK_APK_INSTALL_PID_FILE="$direct_install_pid_file" ADB_COMMAND_TIMEOUT_SECONDS=5 \
HAPANELD_SKIP_AUTO_EXPORT=1 HAPANELD_CONFIG_BACKUP_DIR="$TMP/auto-backups" MOCK_STATE_DIR="$TMP" \
  bash "$PROVISION" "$MOCK_TARGET" --apk "$APK" --no-tame --allow-unsigned-helper > "$direct_handoff_output" 2>&1 &
direct_handoff_owner_pid=$!
ACTIVE_PUBLICATION_PGID="$direct_handoff_owner_pid"
set +m
direct_handoff_ready=0
for _ in {1..100}; do
  if [ -s "$direct_install_pid_file" ]; then direct_handoff_ready=1; break; fi
  /bin/sleep 0.05
done
if [ "$direct_handoff_ready" -eq 1 ]; then
  pass "post-handoff interruption reaches the blocked package install"
else
  LAST_OUTPUT="$direct_handoff_output"
  fail_test "post-handoff interruption reaches the blocked package install"
fi
accepted_direct_db="$(find "$TMP/auto-backups" -maxdepth 1 -type f -name '*.break-glass.db' | head -1)"
accepted_direct_receipt="${accepted_direct_db}.backup-receipt.txt"
if [ -n "$accepted_direct_db" ] && [ -s "$accepted_direct_db" ] && [ -s "$accepted_direct_receipt" ]; then
  pass "the accepted direct pair exists before the post-handoff interrupt"
else
  LAST_OUTPUT="$direct_handoff_output"
  fail_test "the accepted direct pair exists before the post-handoff interrupt"
fi
kill -INT -- "-$direct_handoff_owner_pid" 2>/dev/null || true
if wait "$direct_handoff_owner_pid"; then direct_handoff_status=0; else direct_handoff_status=$?; fi
ACTIVE_PUBLICATION_PGID=""
if [ "$direct_handoff_status" -eq 130 ]; then
  pass "interrupting after direct handoff preserves signal status"
else
  LAST_OUTPUT="$direct_handoff_output"
  fail_test "interrupting after direct handoff preserves signal status (got $direct_handoff_status)"
fi
if [ -s "$accepted_direct_db" ] && [ -s "$accepted_direct_receipt" ]; then
  pass "post-handoff interruption preserves the accepted direct pair"
else
  LAST_OUTPUT="$direct_handoff_output"
  fail_test "post-handoff interruption preserves the accepted direct pair"
fi
direct_install_blocked_pid="$(cat "$direct_install_pid_file" 2>/dev/null || true)"
if [ -n "$direct_install_blocked_pid" ] && processes_gone "$direct_install_blocked_pid"; then
  pass "post-handoff interruption reaps the blocked package install"
else fail_test "post-handoff interruption reaps the blocked package install"; fi
if [ "$(grep -c 'RELEASE_UPGRADE' "$MOCK_CALL_LOG")" = 1 ]; then
  pass "post-handoff interruption RELEASEs quiescence exactly once"
else fail_test "post-handoff interruption RELEASEs quiescence exactly once"; fi
reset_db_txn_state

# Every non-ready or malformed outcome gets one PREPARE and exactly one legacy .backup attempt.
for prepare_mode in unsupported malformed wrong_nonce wrong_result nonready; do
  reset_db_txn_state
  MOCK_UPGRADE_PREPARE="$prepare_mode" run_provision "$MOCK_TARGET" --apk "$APK" --no-tame
  assert_success "a $prepare_mode PREPARE outcome uses the legacy backup and still upgrades"
  if [ "$(grep -c 'PREPARE_UPGRADE' "$MOCK_CALL_LOG")" = 1 ]; then
    pass "a $prepare_mode outcome is not retried"
  else fail_test "a $prepare_mode outcome is not retried"; fi
  assert_log_contains 'sh /data/local/tmp/\.hapaneld-db-txn\.[0-9a-f]+-script' "a $prepare_mode outcome uses the one legacy SQLite backup"
  if [ "$(grep -c 'sh /data/local/tmp/\.hapaneld-db-txn\..*-script' "$MOCK_CALL_LOG")" = 1 ] &&
     [ "$(grep -c '^sqlite3 \.backup$' "$MOCK_CALL_LOG")" = 1 ]; then
    pass "a $prepare_mode outcome executes exactly one legacy transaction and one SQLite .backup"
  else fail_test "a $prepare_mode outcome executes exactly one legacy transaction and one SQLite .backup"; fi
  assert_not_contains 'exec-out .*ha-paneld.db' "$MOCK_CALL_LOG" "a $prepare_mode receipt is never accepted for direct copy"
  assert_not_contains 'RELEASE_UPGRADE' "$MOCK_CALL_LOG" "successful replacement retires a $prepare_mode custody nonce without RELEASE"
done

reset_db_txn_state
prepare_timeout_pid_file="$TMP/upgrade-prepare-timeout.pid"
rm -f "$prepare_timeout_pid_file"
UPGRADE_PREPARE_TIMEOUT_SECONDS=1 MOCK_UPGRADE_PREPARE=timeout \
MOCK_UPGRADE_PREPARE_PID_FILE="$prepare_timeout_pid_file" \
  run_provision "$MOCK_TARGET" --apk "$APK" --no-tame
assert_success "a timed-out PREPARE falls back once and still upgrades"
assert_log_contains 'sh /data/local/tmp/\.hapaneld-db-txn\.[0-9a-f]+-script' "a timed-out PREPARE uses the one legacy SQLite backup"
if [ "$(grep -c 'sh /data/local/tmp/\.hapaneld-db-txn\..*-script' "$MOCK_CALL_LOG")" = 1 ] &&
   [ "$(grep -c '^sqlite3 \.backup$' "$MOCK_CALL_LOG")" = 1 ]; then
  pass "a timed-out PREPARE executes exactly one legacy transaction and one SQLite .backup"
else fail_test "a timed-out PREPARE executes exactly one legacy transaction and one SQLite .backup"; fi
assert_not_contains 'RELEASE_UPGRADE' "$MOCK_CALL_LOG" "successful replacement retires timed-out PREPARE custody without RELEASE"
prepare_timeout_pid="$(cat "$prepare_timeout_pid_file" 2>/dev/null || true)"
[ -n "$prepare_timeout_pid" ] || printf '# the PREPARE fixture never recorded its PID before the deadline\n'
if [ -n "$prepare_timeout_pid" ] && processes_gone "$prepare_timeout_pid"; then
  pass "a timed-out PREPARE reaps its adb fixture process"
else fail_test "a timed-out PREPARE reaps its adb fixture process"; fi

# A malformed or timed-out result cannot prove the app failed to arm. If a later pre-install step
# aborts, cleanup therefore RELEASEs the retained request nonce after staging cleanup.
for uncertain_prepare in malformed timeout; do
  reset_db_txn_state
  uncertain_timeout=45
  [ "$uncertain_prepare" != timeout ] || uncertain_timeout=1
  UPGRADE_PREPARE_TIMEOUT_SECONDS="$uncertain_timeout" MOCK_UPGRADE_PREPARE="$uncertain_prepare" \
  MOCK_HELPER_INSTALL=fail run_provision "$MOCK_TARGET" --apk "$APK" --no-tame
  assert_failure "a post-$uncertain_prepare abort fails before package replacement"
  if [ "$(grep -c 'RELEASE_UPGRADE' "$MOCK_CALL_LOG")" = 1 ]; then
    pass "a post-$uncertain_prepare abort releases its retained nonce exactly once"
  else fail_test "a post-$uncertain_prepare abort releases its retained nonce exactly once"; fi
  prepare_nonce="$(sed -n 's/.*PREPARE_UPGRADE.*--es nonce \([0-9a-f]\{32\}\).*/\1/p' "$MOCK_CALL_LOG" | head -1)"
  release_nonce="$(sed -n 's/.*RELEASE_UPGRADE.*--es nonce \([0-9a-f]\{32\}\).*/\1/p' "$MOCK_CALL_LOG" | head -1)"
  if [ -n "$prepare_nonce" ] && [ "$release_nonce" = "$prepare_nonce" ]; then
    pass "a post-$uncertain_prepare abort RELEASEs the exact request nonce"
  else fail_test "a post-$uncertain_prepare abort RELEASEs the exact request nonce"; fi
done

# Size, digest, schema, row-count and local SQLite failures reject only the optional ordinary-upgrade backup. They do
# not silently switch to a second capture mechanism after an authenticated READY receipt.
for direct_failure in size_mismatch digest_mismatch schema_mismatch rows_mismatch invalid_sqlite; do
  reset_db_txn_state
  MOCK_UPGRADE_PREPARE="$direct_failure" run_provision "$MOCK_TARGET" --apk "$APK" --no-tame
  assert_success "a $direct_failure direct copy is discarded while the ordinary upgrade continues"
  assert_marker_absent "a $direct_failure direct copy never earns the captured marker"
  assert_log_contains '^adb .* install' "a $direct_failure optional backup does not preempt install"
  assert_not_contains '\.hapaneld-db-txn\.' "$MOCK_CALL_LOG" "a $direct_failure result does not retry through the legacy path"
  if find "$TMP/auto-backups" -maxdepth 1 -type f -name '*.break-glass.db*' | grep -q .; then
    fail_test "a $direct_failure rejection removes its host artifacts"
  else pass "a $direct_failure rejection removes its host artifacts"; fi
done

reset_db_txn_state
MOCK_UPGRADE_PREPARE=ready MOCK_SU_DIALECT=shc run_provision "$MOCK_TARGET" --apk "$APK" --no-tame
assert_success "the quiesced direct copy supports the sh -c root route"
assert_log_contains 'exec-out su 0 sh -c cat /data/data/io.github.maxlyth.hapaneld/databases/ha-paneld.db' "the direct copy uses the probed sh -c form"
for direct_dialect in rootjoin:'exec-out su root cat ' rootshc:'exec-out su root sh -c cat ' suc:'exec-out su -c cat '; do
  dialect_name="${direct_dialect%%:*}"; wrapper_pattern="${direct_dialect#*:}"
  reset_db_txn_state
  MOCK_UPGRADE_PREPARE=ready MOCK_SU_DIALECT="$dialect_name" run_provision "$MOCK_TARGET" --apk "$APK" --no-tame
  assert_marker_captured "the READY direct copy supports the $dialect_name root form"
  assert_log_contains "$wrapper_pattern/data/data/io.github.maxlyth.hapaneld/databases/ha-paneld.db" "the direct copy dispatches through the exact $dialect_name form"
done
reset_db_txn_state
MOCK_UPGRADE_PREPARE=ready MOCK_ADB_ROOT=1 run_provision "$MOCK_TARGET" --apk "$APK" --no-tame
assert_marker_captured "the READY direct copy supports root adbd without su"
assert_log_contains 'exec-out cat /data/data/io.github.maxlyth.hapaneld/databases/ha-paneld.db' "root adbd uses the bare binary-safe copy form"

# A failure after READY but before package replacement releases the exact lease once. A destructive
# reset with rejected bytes remains fail-closed and likewise releases rather than erasing anything.
reset_db_txn_state
MOCK_UPGRADE_PREPARE=ready MOCK_HELPER_INSTALL=fail run_provision "$MOCK_TARGET" --apk "$APK" --no-tame
assert_failure "a pre-install helper failure aborts after READY"
if [ "$(grep -c 'RELEASE_UPGRADE' "$MOCK_CALL_LOG")" = 1 ]; then
  pass "a pre-install abort sends exactly one RELEASE"
else fail_test "a pre-install abort sends exactly one RELEASE"; fi
prepare_nonce="$(sed -n 's/.*PREPARE_UPGRADE.*--es nonce \([0-9a-f]\{32\}\).*/\1/p' "$MOCK_CALL_LOG" | head -1)"
release_nonce="$(sed -n 's/.*RELEASE_UPGRADE.*--es nonce \([0-9a-f]\{32\}\).*/\1/p' "$MOCK_CALL_LOG" | head -1)"
if [ -n "$prepare_nonce" ] && [ "$release_nonce" = "$prepare_nonce" ]; then
  pass "RELEASE carries the exact READY nonce"
else fail_test "RELEASE carries the exact READY nonce"; fi
assert_log_contains 'am start-foreground-service --user 0 -n io.github.maxlyth.hapaneld/.PaneldService' "an acknowledged RELEASE gets the API31 root-authoritative service start"

# A non-exact response never disowns the lease. The same nonce is retried once, and every RELEASE
# attempt receives the idempotent API31 root start backstop even when the receiver says release_failed.
reset_db_txn_state
MOCK_UPGRADE_PREPARE=ready MOCK_UPGRADE_RELEASE=fail_once MOCK_HELPER_INSTALL=fail \
  run_provision "$MOCK_TARGET" --apk "$APK" --no-tame
assert_failure "a transient RELEASE response preserves the original provisioning failure"
release_nonces="$(sed -n 's/.*RELEASE_UPGRADE.*--es nonce \([0-9a-f]\{32\}\).*/\1/p' "$MOCK_CALL_LOG")"
if [ "$(printf '%s\n' "$release_nonces" | grep -c .)" = 2 ] &&
   [ "$(printf '%s\n' "$release_nonces" | sort -u | grep -c .)" = 1 ]; then
  pass "a non-exact RELEASE is retried once with the same retained nonce"
else fail_test "a non-exact RELEASE is retried once with the same retained nonce"; fi
if [ "$(grep -c 'am start-foreground-service --user 0 -n io.github.maxlyth.hapaneld/.PaneldService' "$MOCK_CALL_LOG")" = 2 ]; then
  pass "each RELEASE attempt receives the idempotent API31 service-start backstop"
else fail_test "each RELEASE attempt receives the idempotent API31 service-start backstop"; fi

reset_db_txn_state
MOCK_UPGRADE_PREPARE=ready MOCK_UPGRADE_RELEASE=release_failed MOCK_HELPER_INSTALL=fail \
  run_provision "$MOCK_TARGET" --apk "$APK" --no-tame
assert_failure "a receiver release_failed response preserves the original provisioning failure"
if [ "$(grep -c 'RELEASE_UPGRADE' "$MOCK_CALL_LOG")" = 2 ]; then
  pass "release_failed is retried exactly once"
else fail_test "release_failed is retried exactly once"; fi
assert_log_contains 'am start-foreground-service --user 0 -n io.github.maxlyth.hapaneld/.PaneldService' "release_failed still receives the root-authoritative service-start backstop"
assert_contains 'RELEASE was not acknowledged after two attempts' "release_failed warns that lease acknowledgement is still absent"
release_clear_line="$(grep -n '^      UPGRADE_QUIESCE_NONCE=""$' "$PROVISION" | head -1 | cut -d: -f1)"
exact_release_branch_line="$(grep -n '^    if \[ "\$released_count" = 1 \]; then$' "$PROVISION" | head -1 | cut -d: -f1)"
if [ -n "$exact_release_branch_line" ] && [ -n "$release_clear_line" ] &&
   [ "$exact_release_branch_line" -lt "$release_clear_line" ]; then
  pass "source disowns RELEASE custody only inside the exact-response branch"
else fail_test "source disowns RELEASE custody only inside the exact-response branch"; fi

if grep -Eq 'am force-stop[^\n]*io\.github\.maxlyth\.hapaneld|p?kill(all)?[^\n]*io\.github\.maxlyth\.hapaneld' "$PROVISION"; then
  fail_test "the upgrade path never force-stops or kills ha-paneld"
else pass "the upgrade path never force-stops or kills ha-paneld"; fi

if provision_scope_is backup shard-backup; then finish_provision_test; fi
fi

if provision_scope_is core all publication shard-publication; then
# ── Data-store snapshot: one on-panel transaction (legacy fallback) ─────────────────────────────
# The settings export is not a recovery point: configuration, the entity catalog, proximity and
# ambient history and the revision ring all live in ha-paneld.db. The capture is ONE generated
# script, pushed and hash-verified before it runs as root, performing backup + integrity + admission
# + provenance + digest atomically on the panel. The fixture EXECUTES the pushed bytes in a sandbox,
# so every contract below observes the shipped script's own behaviour. The captured marker is always
# matched whole-line (-x), exactly as the fleet consumer greps it.
reset_db_txn_state
run_provision "$MOCK_TARGET" --apk "$APK" --no-tame
assert_success "an upgrade with a root route succeeds"
assert_marker_captured "a verified capture emits the exact whole-line captured marker"
assert_log_contains 'push .*/data/local/tmp/\.hapaneld-db-txn\.[0-9a-f]+-script' "the capture script is pushed to the panel"
assert_log_contains 'sha256sum /data/local/tmp/\.hapaneld-db-txn\.[0-9a-f]+-script' "the pushed script is hash-verified before it runs as root"
assert_log_contains 'sh /data/local/tmp/\.hapaneld-db-txn\.[0-9a-f]+-script' "the capture runs as one on-panel transaction"
assert_log_contains 'pull .*\.hapaneld-db-txn\.[0-9a-f]+/ha-paneld\.db .*break-glass\.db' "the verified snapshot is pulled to the break-glass name"
assert_contains 'verified SQLite backup, integrity ok' "the report states what was verified, not just where it wrote"
assert_contains 'restored by hand only' "the snapshot states that nothing restores it automatically"
assert_contains 'Install → Backup .hpb is the supported restore path' "the snapshot points at the supported restore route instead"
receipt_file="$(ls "$TMP"/auto-backups/*.break-glass.backup-receipt.txt 2>/dev/null | head -1)"
if [ -n "$receipt_file" ]; then pass "a receipt is published beside the snapshot"; else fail_test "a receipt is published beside the snapshot"; fi
for receipt_field in receipt_version=2 database_bytes= database_sha256_panel= database_sha256_host= database_integrity=ok database_app_state_rows=7 database_user_version=9 app_version_code=513 app_version_name= capture_binary= capture_script_sha256= settings_export=; do
  if grep -q "^$receipt_field" "$receipt_file" 2>/dev/null; then pass "the receipt records $receipt_field unconditionally"
  else fail_test "the receipt records $receipt_field unconditionally"; fi
done
snapshot_db="$(ls "$TMP"/auto-backups/*.break-glass.db 2>/dev/null | head -1)"
if [ -n "$snapshot_db" ] && [ -s "$snapshot_db" ] && [ "$(stat -c '%a' "$snapshot_db" 2>/dev/null)" = 600 ]; then
  pass "the pulled snapshot exists owner-only"
else fail_test "the pulled snapshot exists owner-only"; fi
if ls -d "$TMP/db-txn-sandbox/data/local/tmp"/.hapaneld-db-txn.* >/dev/null 2>&1; then
  fail_test "the on-panel staging is really removed after capture (sandbox truth)"
else pass "the on-panel staging is really removed after capture (sandbox truth)"; fi

# A cleanly checkpointed source with an empty -wal beside it is a normal state the sandbox seeds by
# default, and the capture above succeeded over it.
if [ -f "$TMP/db-txn-sandbox/data/data/io.github.maxlyth.hapaneld/databases/ha-paneld.db-wal" ] && \
   [ ! -s "$TMP/db-txn-sandbox/data/data/io.github.maxlyth.hapaneld/databases/ha-paneld.db-wal" ]; then
  pass "the capture succeeded over a source with a real zero-byte WAL present"
else fail_test "the capture succeeded over a source with a real zero-byte WAL present"; fi

# A published, verified snapshot survives the operator's Ctrl-C: once the receipt is published the
# pair's custody has ended, so an interrupt during the best-effort panel staging cleanup — the
# likeliest moment for one, because a wedged transport is exactly what makes an operator interrupt
# — must find nothing to repossess. The fixture blocks that cleanup so the signal lands inside it.
reset_db_txn_state
rm -f "$TMP/db-cleanup-blocked-once"
publication_int_pid_file="$TMP/db-cleanup-block.pid"
publication_int_output="$TMP/db-cleanup-block-output.txt"
rm -f "$publication_int_pid_file"
set -m
MOCK_DB_CLEANUP=block MOCK_DB_CLEANUP_PID_FILE="$publication_int_pid_file" MOCK_STATE_DIR="$TMP" \
ADB_COMMAND_TIMEOUT_SECONDS=5 \
HAPANELD_SKIP_AUTO_EXPORT=1 HAPANELD_CONFIG_BACKUP_DIR="$TMP/auto-backups" \
  bash "$PROVISION" "$MOCK_TARGET" --apk "$APK" --no-tame > "$publication_int_output" 2>&1 &
publication_int_pid=$!
ACTIVE_PUBLICATION_PGID="$publication_int_pid"
set +m
publication_int_ready=0
publication_int_attempt=0
while [ "$publication_int_attempt" -lt 200 ]; do
  if [ -s "$publication_int_pid_file" ]; then publication_int_ready=1; break; fi
  /bin/sleep 0.05
  publication_int_attempt=$((publication_int_attempt + 1))
done
if [ "$publication_int_ready" -eq 1 ]; then
  pass "the interrupt scenario reaches the blocked post-publication cleanup"
else
  LAST_OUTPUT="$publication_int_output"
  fail_test "the interrupt scenario reaches the blocked post-publication cleanup"
fi
published_db="$(ls "$TMP"/auto-backups/*.break-glass.db 2>/dev/null | head -1)"
published_receipt="$(ls "$TMP"/auto-backups/*.break-glass.backup-receipt.txt 2>/dev/null | head -1)"
if [ -n "$published_db" ] && [ -n "$published_receipt" ]; then
  pass "the snapshot and receipt are published before the interrupt arrives (guards the survival claims below)"
else
  LAST_OUTPUT="$publication_int_output"
  fail_test "the snapshot and receipt are published before the interrupt arrives (guards the survival claims below)"
fi
publication_blocked_pid="$(cat "$publication_int_pid_file" 2>/dev/null || true)"
publication_interrupt_started=$SECONDS
kill -INT -- "-$publication_int_pid" 2>/dev/null || true
if wait "$publication_int_pid"; then publication_int_status=0; else publication_int_status=$?; fi
publication_interrupt_elapsed=$((SECONDS - publication_interrupt_started))
ACTIVE_PUBLICATION_PGID=""
if [ "$publication_int_status" -eq 130 ]; then
  pass "the interrupted run exits with the signal status"
else
  LAST_OUTPUT="$publication_int_output"
  fail_test "the interrupted run exits with the signal status (got $publication_int_status)"
fi
if [ "$publication_interrupt_elapsed" -lt 4 ]; then
  pass "process-group interruption completes before the nested 5s adb deadline"
else
  LAST_OUTPUT="$publication_int_output"
  fail_test "process-group interruption completes before the nested 5s adb deadline (took ${publication_interrupt_elapsed}s)"
fi
if [ -f "$published_db" ] && [ -s "$published_db" ]; then
  pass "the published snapshot survives an interrupt during the panel staging cleanup"
else
  LAST_OUTPUT="$publication_int_output"
  fail_test "the published snapshot survives an interrupt during the panel staging cleanup"
fi
if [ -f "$published_receipt" ] && [ -s "$published_receipt" ]; then
  pass "the published receipt survives an interrupt during the panel staging cleanup"
else
  LAST_OUTPUT="$publication_int_output"
  fail_test "the published receipt survives an interrupt during the panel staging cleanup"
fi
if [ -n "$publication_blocked_pid" ] && processes_gone "$publication_blocked_pid"; then
  pass "the interrupt reaps the blocked cleanup fixture"
else
  LAST_OUTPUT="$publication_int_output"
  fail_test "the interrupt reaps the blocked cleanup fixture"
fi
rm -f "$TMP/db-cleanup-blocked-once" "$publication_int_pid_file"
reset_db_txn_state

if provision_scope_is publication shard-publication; then finish_provision_test; fi
fi

if provision_scope_is core all shard-database-authority; then
# An unanswerable package manager can prove neither an existing database nor a legitimate fresh
# install. The database authority therefore refuses before optional snapshot policy is consulted.
reset_db_txn_state
MOCK_PM_PATH=fail MOCK_PM_LIVENESS=fail run_provision "$MOCK_TARGET" --apk "$APK" --no-tame
assert_failure "an unanswerable package discovery refuses an ordinary upgrade"
assert_contains 'database compatibility could not be proven: the installed-package state is unknown' \
  "the unanswerable discovery is refused by the authoritative gate"
assert_marker_absent "an unanswerable discovery never claims a captured snapshot"
assert_not_contains '^adb .* install' "$MOCK_CALL_LOG" "the unanswerable discovery refuses before APK install"
reset_db_txn_state
MOCK_PM_PATH=fail MOCK_PM_LIVENESS=fail run_provision "$MOCK_TARGET" --apk "$APK" --no-tame --allow-missing-db-snapshot
assert_failure "the deprecated snapshot flag cannot bypass unknown database ownership"
assert_marker_absent "the compatibility flag does not manufacture a captured verdict"
reset_db_txn_state
# Package-manager absence does not erase retained storage authority. This fixture deliberately keeps
# the observer's retained database state; only the separate proven-fresh controls may install.
MOCK_PM_PATH=fail run_provision "$MOCK_TARGET" --apk "$APK" --no-tame
assert_failure "package-manager absence with retained database state is not a fresh install"
assert_contains 'package is absent but retained database or recovery state still exists' \
  "retained database state names why package absence is insufficient"
assert_not_contains 'could not be asked whether ha-paneld is installed' "$LAST_OUTPUT" \
  "normal package absence is never reported as an unreachable panel"
assert_marker_absent "an absent package never claims a captured snapshot"
assert_not_contains '^adb .* install' "$MOCK_CALL_LOG" "retained package data refuses before APK install"
reset_db_txn_state

# A capability boundary, not a failure: no root route means /data/data is unreachable by design.
MOCK_ROOT=0 run_provision "$MOCK_TARGET" --apk "$APK" --no-tame
assert_success "an upgrade on a panel with no root route still succeeds"
assert_contains 'no root route, so only settings could be saved' "a sandboxed panel is told what was and was not saved"
assert_marker_absent "a rootless skip never claims a captured snapshot"
assert_not_contains 'PREPARE_UPGRADE|exec-out .*ha-paneld.db' "$MOCK_CALL_LOG" "a rootless panel is neither quiesced nor asked for an inaccessible database"

# The rootless skip above is only accepted from a transport that proves it is still alive: a probe
# whose transport died mid-question looks identical to a genuine "no root", and skipping on it would
# drop the restore point on exactly the panel that is misbehaving. A dead transport refuses.
reset_db_txn_state
MOCK_ROOT=0 MOCK_SNAPSHOT_TRANSPORT=dead run_provision "$MOCK_TARGET" --apk "$APK" --no-tame
assert_failure "a transport that dies during root discovery stops the upgrade"
assert_contains 'database compatibility could not be proven: the panel root route is unknown' \
  "the dead-transport refusal is named by the authoritative compatibility gate"
assert_marker_absent "a dead-transport probe never claims a captured snapshot"
reset_db_txn_state

# The rootless "no" above is also not the mutation path's answer: install_root_helper escalates via
# `adb root` (ensure_root_path) before touching the panel, so a userdebug panel that answers
# uid=2000 until `adb root` would have been mutated as root moments after a "no root route" skip.
# The capture gate performs the same escalation first: the panel that CAN be captured IS captured.
rm -f "$TMP/adb-root-escalated"
MOCK_ROOT=0 MOCK_ADB_ROOT=escalates run_provision "$MOCK_TARGET" --apk "$APK" --no-tame
assert_success "an upgrade on a userdebug panel whose root appears only after adb root succeeds"
assert_marker_captured "the post-escalation capture emits the exact whole-line captured marker"
assert_not_contains 'no root route' "$LAST_OUTPUT" "escalated root is never reported as a rootless skip"
root_line="$(grep -En '^adb -s [^ ]+ root$' "$MOCK_CALL_LOG" | head -1 | cut -d: -f1)"
push_line="$(grep -En 'push .*/data/local/tmp/\.hapaneld-db-txn\.[0-9a-f]+-script' "$MOCK_CALL_LOG" | head -1 | cut -d: -f1)"
if [ -n "$root_line" ] && [ -n "$push_line" ] && [ "$root_line" -lt "$push_line" ]; then
  pass "the capture gate itself performed the adb-root escalation before pushing the capture script"
else
  fail_test "the capture gate itself performed the adb-root escalation before pushing the capture script (root=${root_line:-none} push=${push_line:-none})"
fi
rm -f "$TMP/adb-root-escalated"
reset_db_txn_state

# Escalation that ends in an unanswerable probe is an unproven root route, not a "no": refuse,
# naming the post-escalation probe, instead of mutating a panel whose capability is unknown.
PRIVILEGE_INSPECTION_TIMEOUT_SECONDS=1 MOCK_ROOT=0 MOCK_ADB_ROOT=escalates_then_hang \
  run_provision "$MOCK_TARGET" --apk "$APK" --no-tame
assert_failure "a probe that hangs after adb root stops the upgrade"
assert_contains 'database compatibility could not be proven: the panel root route is unknown' \
  "the post-escalation timeout refusal is named by the authoritative compatibility gate"
assert_marker_absent "a hung post-escalation probe never claims a captured snapshot"
rm -f "$TMP/adb-root-escalated"
reset_db_txn_state

# A transport that dies in the adbd restart answers exactly like a genuine "no root": the
# post-escalation negative is only accepted from a transport that proves it is still alive.
MOCK_ROOT=0 MOCK_ADB_ROOT=escalates_then_drop MOCK_SNAPSHOT_TRANSPORT=dead_after_escalation \
  run_provision "$MOCK_TARGET" --apk "$APK" --no-tame
assert_failure "a transport that dies after adb root stops the upgrade"
assert_contains 'database compatibility could not be proven: the panel root route is unknown' \
  "the post-escalation dead-transport refusal is named by the authoritative compatibility gate"
assert_marker_absent "a post-escalation dead transport never claims a captured snapshot"
assert_log_contains '^adb -s [^ ]+ root$' "the drop is observed AFTER a real escalation, not on the pre-escalation path"
alive_probes="$(grep -Ec 'shell echo HAPANELD_TRANSPORT_ALIVE' "$MOCK_CALL_LOG" || true)"
if [ "$alive_probes" -eq 2 ]; then
  pass "the post-escalation liveness recheck is what observed the dead transport"
else
  fail_test "the post-escalation liveness recheck is what observed the dead transport (got $alive_probes liveness probes)"
fi
rm -f "$TMP/adb-root-escalated"
reset_db_txn_state

# The escalation is ONE decision per run: a proven "no" at the capture gate is the same "no" the
# helper install acts on, so a rootless run issues exactly one adb-root attempt — a second attempt
# minutes later could answer differently and recreate the skip-then-mutate hazard the gate closes.
: > "$MOCK_CALL_LOG"
MOCK_ROOT=0 run_provision "$MOCK_TARGET" --apk "$APK" --no-tame
assert_success "a rootless upgrade still succeeds under the single-decision escalation"
root_calls="$(grep -Ec '^adb -s [^ ]+ root$' "$MOCK_CALL_LOG" || true)"
if [ "$root_calls" -eq 1 ]; then
  pass "the whole run makes exactly one adb-root escalation attempt"
else
  fail_test "the whole run makes exactly one adb-root escalation attempt (got $root_calls)"
fi
reset_db_txn_state

# An unknown capability is never mutated on: the operator's escape flag lets the REFUSED capture
# continue, but the same cached unknown verdict then stops the helper phase fail-closed — a
# transport that died in the adbd restart could recover into root a moment after a helperless
# install stranded the panel.
rm -f "$TMP/adb-root-escalated"
MOCK_ROOT=0 MOCK_ADB_ROOT=escalates_then_drop MOCK_SNAPSHOT_TRANSPORT=dead_after_escalation \
  run_provision "$MOCK_TARGET" --apk "$APK" --no-tame --allow-missing-db-snapshot
assert_failure "the escape flag does not let a run mutate a panel whose root capability is unknown"
assert_contains 'No settings backup.*mutation was started' \
  "the authoritative compatibility gate stopped the run before every mutation"
assert_not_contains 'continuing .*WITHOUT a database restore point' "$LAST_OUTPUT" \
  "the compatibility gate stops before optional backup refusal policy"
assert_marker_absent "an unknown-capability run never claims a captured snapshot"
assert_not_contains '^adb .* install( |$)' "$MOCK_CALL_LOG" "an unknown-capability run never reaches the APK install"
rm -f "$TMP/adb-root-escalated"
reset_db_txn_state

# The whole escalation runs under ONE aggregate deadline with remaining-time budgets per step: a
# panel whose adb wedges after `adb root` refuses as unknown in bounded time instead of spending
# the full per-step adb deadline on every reconnect attempt.
rm -f "$TMP/adb-root-escalated"
ROOT_RESOLVE_TIMEOUT_SECONDS=2 MOCK_ROOT=0 MOCK_ADB_ROOT=escalates MOCK_ADB_DEVICES=hang_after_escalation \
  run_provision "$MOCK_TARGET" --apk "$APK" --no-tame
assert_failure "a wedged escalation stops the upgrade within its aggregate deadline"
assert_contains 'database compatibility could not be proven: the panel root route is unknown' \
  "the aggregate-deadline refusal reads as an undecided compatibility resolution"
assert_marker_absent "a wedged escalation never claims a captured snapshot"
rm -f "$TMP/adb-root-escalated"
reset_db_txn_state

# A concluded unknown is consumed, never re-derived: with the escape flag the refused capture may
# continue, but the helper phase reads the SAME verdict and stops before any mutation — without
# re-asking the panel. Use a deterministic post-escalation transport drop here; the preceding tests
# separately prove the hung-probe and aggregate-deadline behavior.
rm -f "$TMP/adb-root-escalated"
: > "$MOCK_CALL_LOG"
MOCK_ROOT=0 MOCK_ADB_ROOT=escalates_then_drop MOCK_SNAPSHOT_TRANSPORT=dead_after_escalation \
  run_provision "$MOCK_TARGET" --apk "$APK" --no-tame --allow-missing-db-snapshot
assert_failure "the escape flag does not let the helper phase re-open a hung root resolution"
assert_not_contains 'continuing .*WITHOUT a database restore point' "$LAST_OUTPUT" \
  "the compatibility gate stops before optional backup refusal policy"
assert_contains 'No settings backup.*mutation was started' \
  "the compatibility gate stops the hung resolution before any later phase"
id_probes="$(grep -Ec '^adb -s [^ ]+ shell id$' "$MOCK_CALL_LOG" || true)"
if [ "$id_probes" -eq 2 ]; then
  pass "the resolution's hung probe is never re-asked by a later phase"
else
  fail_test "the resolution's hung probe is never re-asked by a later phase (got $id_probes id probes)"
fi
assert_not_contains '^adb .* install( |$)' "$MOCK_CALL_LOG" "an undecided route never reaches the APK install"
rm -f "$TMP/adb-root-escalated"
reset_db_txn_state

# Releases that predate helper assets return from the helper phase before any other root decision
# point, so they must consume the run's verdict at the phase's head: an undecided route stops the
# run before the historical direct-su path can reach the APK install.
rm -f "$TMP/adb-root-escalated"
PRE_ASSETS_APK="$TMP/ha-paneld-v0.9.2-manual-setup-required.apk"
cp "$APK" "$PRE_ASSETS_APK"
: > "$MOCK_CALL_LOG"
MOCK_ROOT=0 MOCK_ADB_ROOT=escalates_then_drop MOCK_SNAPSHOT_TRANSPORT=dead_after_escalation \
  run_provision "$MOCK_TARGET" --apk "$PRE_ASSETS_APK" --release-tag v0.9.2 --no-tame --allow-missing-db-snapshot
assert_failure "a pre-helper-assets release does not mutate a panel whose root capability is unknown"
assert_contains 'No settings backup.*mutation was started' \
  "the pre-assets flavour is stopped by the compatibility gate before its own defense-in-depth arm"
assert_not_contains 'predates automatic root-helper assets' "$LAST_OUTPUT" "the undecided route stops before the historical direct-su notice"
assert_not_contains '^adb .* install( |$)' "$MOCK_CALL_LOG" "the pre-assets flavour never reaches the APK install on an undecided route"
rm -f "$TMP/adb-root-escalated"
reset_db_txn_state

# The legacy pre-assets flavour performs no helper transaction before its APK replace, so it
# confirms the proven route is still live before its direct-su era return: a live route proceeds,
# a route that answered the resolution and has since died fails closed without reclassification.
reset_db_txn_state
: > "$MOCK_CALL_LOG"
run_provision "$MOCK_TARGET" --apk "$PRE_ASSETS_APK" --release-tag v0.9.2 --no-tame
assert_success "a rooted panel upgrades through the pre-assets flavour"
assert_contains 'predates automatic root-helper assets' "the legacy flavour is announced"
assert_log_contains 'shell su 0 .id.$' "the proven route is confirmed live as uid=0 before the legacy return"
assert_log_contains '^adb .* install' "the legacy flavour still installs the APK on a live route"
reset_db_txn_state
: > "$MOCK_CALL_LOG"
MOCK_ROOT_LIVE=dead run_provision "$MOCK_TARGET" --apk "$PRE_ASSETS_APK" --release-tag v0.9.2 --no-tame
assert_failure "a proven route that stopped answering fails the legacy flavour closed"
assert_contains 'proven root route stopped answering' "the legacy stop names the dead route"
assert_not_contains '^adb .* install( |$)' "$MOCK_CALL_LOG" "no APK install after the proven route died"
reset_db_txn_state

# The adbd-root form runs privileged commands as BARE shell commands, so a live check that merely
# echoed a marker would answer from any adbd, rooted or not — proving nothing. These pin that the
# check tests the privileged property itself on exactly that form.
: > "$MOCK_CALL_LOG"
MOCK_ADB_ROOT=1 run_provision "$MOCK_TARGET" --apk "$PRE_ASSETS_APK" --release-tag v0.9.2 --no-tame
assert_success "an adb-root panel upgrades through the pre-assets flavour"
assert_log_contains '^adb .* install' "the adb-root panel still installs when its route is live"
reset_db_txn_state
: > "$MOCK_CALL_LOG"
MOCK_ADB_ROOT=1 MOCK_ROOT_LIVE=dead run_provision "$MOCK_TARGET" --apk "$PRE_ASSETS_APK" --release-tag v0.9.2 --no-tame
assert_failure "an adb-root panel that lost root since resolution fails the legacy flavour closed"
assert_contains 'proven root route stopped answering' "the bare-shell live check names the dead route"
assert_not_contains '^adb .* install( |$)' "$MOCK_CALL_LOG" "no APK install after an adb-root route lost root"
reset_db_txn_state

# The resolver's aggregate deadline is enforced in the code, not only by wall-clock tests: driven
# directly with a manipulated clock, an exhausted budget concludes unknown without granting the
# liveness recheck a fresh allowance, and a positive allowance never exceeds min(remaining, 15).
RESOLVER_UNIT_SOURCE="$(sed -n '/^min_deadline() {$/,/^}$/p; /^resolve_root_route() {$/,/^}$/p' "$PROVISION")"
resolver_unit_case() {
  {
    # The extracted resolver reads $SECONDS, which is bash's REAL elapsed-time variable: assigning
    # to it only re-bases it, so wall-clock seconds kept accruing between statements and a loaded
    # machine could slip an extra second into the budget and flip the verdict (observed 2026-07-31
    # under a saturating mutation battery). `unset` strips the special attribute permanently, so
    # the reassignment below leaves an ORDINARY integer this harness owns: it starts at 0 and moves
    # only when a stub advances it. The extracted production source is unchanged and still reads
    # $SECONDS — only the meaning of the variable inside this fixture is test-owned.
    printf 'set -u\nunset SECONDS\nSECONDS=0\nTARGET=panel.test:5555\nADB_COMMAND=adb\nSU_FORM=""\nSU_PROBE_TIMED_OUT=0\nROOT_ROUTE_VERDICT=""\nPRIVILEGE_INSPECTION_TIMEOUT_SECONDS=45\nROOT_RESOLVE_TIMEOUT_SECONDS=50\n'
    printf 'ADVANCE=%s\nRWD_COST=%s\nREAL_DELAY=%s\n' "$1" "${2:-0}" "${3:-0}"
    printf 'TRACE=%s\n' "$TMP/resolver-unit-trace"
    printf ': > "$TRACE"\n'
    # Stubs trace to a FILE, not stdout: the resolver captures some calls in command substitutions,
    # which would otherwise swallow the very deadline values these contracts are about.
    # REAL_DELAY burns genuine wall-clock time inside a stub; it must never change an outcome.
    # It names /bin/sleep by absolute path deliberately: $FIXTURES leads PATH and ships a no-op
    # `sleep`, so a bare `sleep` (or even `command sleep`) here would burn no time at all and the
    # contract below would pass without ever exercising what it claims to.
    printf 'probe_su() { echo "PROBE_CAP=$PRIVILEGE_INSPECTION_TIMEOUT_SECONDS" >> "$TRACE"; [ "$REAL_DELAY" = 0 ] || /bin/sleep "$REAL_DELAY"; SU_PROBE_TIMED_OUT=0; return 1; }\n'
    printf 'probe_transport_alive() { echo "ALIVE_DEADLINE=$1" >> "$TRACE"; return 0; }\n'
    printf 'run_with_deadline() { echo "RWD_DEADLINE=$1 CMD=$3" >> "$TRACE"; shift; SECONDS=$((SECONDS + RWD_COST)); "$@" || true; }\n'
    printf 'adb() { :; }\n'
    printf 'sleep() { SECONDS=$((SECONDS + ADVANCE)); }\n'
    printf '%s\n' "$RESOLVER_UNIT_SOURCE"
    printf 'resolve_root_route\ncat "$TRACE"\nprintf "VERDICT=%%s ELAPSED=%%s\\n" "$ROOT_ROUTE_VERDICT" "$SECONDS"\n'
  } > "$TMP/resolver-unit-case.sh"
  bash "$TMP/resolver-unit-case.sh"
}
resolver_unit_out="$(resolver_unit_case 5)"
if printf '%s\n' "$resolver_unit_out" 2>/dev/null | grep -qx 'VERDICT=unrooted ELAPSED=45' && \
   [ "$(printf '%s\n' "$resolver_unit_out" | grep -c '^ALIVE_DEADLINE=')" -eq 2 ] && \
   printf '%s\n' "$resolver_unit_out" | tail -2 | head -1 | grep -qx 'ALIVE_DEADLINE=5'; then
  pass "the liveness allowance is capped to the remaining budget, not a fresh 15s"
else
  fail_test "the liveness allowance is capped to the remaining budget, not a fresh 15s ($resolver_unit_out)"
fi
resolver_unit_out="$(resolver_unit_case 40)"
if printf '%s\n' "$resolver_unit_out" 2>/dev/null | grep -qx 'VERDICT=unknown-timeout ELAPSED=80' && \
   [ "$(printf '%s\n' "$resolver_unit_out" | grep -c '^ALIVE_DEADLINE=')" -eq 1 ]; then
  pass "an exhausted aggregate budget concludes unknown without a post-deadline liveness probe, overrunning by at most one wait quantum"
else
  fail_test "an exhausted aggregate budget concludes unknown without a post-deadline liveness probe, overrunning by at most one wait quantum ($resolver_unit_out)"
fi
# Every deadline the resolver hands out is budget-derived, not a constant: the first liveness
# allowance takes its own 15s ceiling while budget remains, the reprobe is capped to what is left,
# and each adb step is given the remaining budget rather than a fixed value. These pin the
# arithmetic that the elapsed-time assertions alone cannot see. Case 1 exercises all three.
resolver_unit_out="$(resolver_unit_case 5)"
if printf '%s\n' "$resolver_unit_out" 2>/dev/null | grep -qx 'ALIVE_DEADLINE=15'; then
  pass "the first liveness allowance takes the 15s ceiling while budget remains"
else
  fail_test "the first liveness allowance takes the 15s ceiling while budget remains ($resolver_unit_out)"
fi
resolver_unit_caps="$(printf '%s\n' "$resolver_unit_out" | sed -n 's/^PROBE_CAP=//p' | tr '\n' ',')"
if [ "$resolver_unit_caps" = "45,5," ]; then
  pass "the reprobe's inspection timeout is capped to the remaining budget"
else
  fail_test "the reprobe's inspection timeout is capped to the remaining budget (got ${resolver_unit_caps:-none})"
fi
# Budget-derived means the allowance SHRINKS as the reconnect window burns time; a hard-coded
# per-step deadline would repeat one value.
resolver_devices_first="$(printf '%s\n' "$resolver_unit_out" | sed -n 's/^RWD_DEADLINE=\([0-9]*\) CMD=devices$/\1/p' | head -1)"
resolver_devices_last="$(printf '%s\n' "$resolver_unit_out" | sed -n 's/^RWD_DEADLINE=\([0-9]*\) CMD=devices$/\1/p' | tail -1)"
if [ "$resolver_devices_first" = 45 ] && [ "$resolver_devices_last" = 10 ]; then
  pass "each adb step in the escalation is bounded by the shrinking remaining budget, not a constant"
else
  fail_test "each adb step in the escalation is bounded by the shrinking remaining budget, not a constant (first=${resolver_devices_first:-none} last=${resolver_devices_last:-none})"
fi

# A wait must never START past the deadline: when the adb step itself consumes the whole budget,
# the follow-up wait is skipped and elapsed time equals the budget exactly.
resolver_unit_out="$(resolver_unit_case 25 50)"
if printf '%s\n' "$resolver_unit_out" 2>/dev/null | grep -qx 'VERDICT=unknown-timeout ELAPSED=50' && \
   [ "$(printf '%s\n' "$resolver_unit_out" | grep -c '^ALIVE_DEADLINE=')" -eq 1 ]; then
  pass "no wait starts past the deadline: an adb step consuming the budget ends the resolution at the budget"
else
  fail_test "no wait starts past the deadline: an adb step consuming the budget ends the resolution at the budget ($resolver_unit_out)"
fi

# The fixture's clock belongs to the fixture: a stub that burns more than a real second must not
# move the budget. Under the previous form — bash's real-elapsed SECONDS merely re-based to 0 —
# this same case drifted to ELAPSED=81 and lost a branch, so an unrelated mutation battery
# saturating the CPU could turn this suite red and make a green run untrustworthy.
# The delay is measured against THIS script's own SECONDS — still bash's real clock out here — so
# the case cannot pass vacuously if the injected wait ever stops elapsing.
resolver_unit_baseline="$(resolver_unit_case 40)"
resolver_clock_mark="$SECONDS"
resolver_unit_delayed="$(resolver_unit_case 40 0 1.1)"
resolver_clock_spent=$((SECONDS - resolver_clock_mark))
if [ "$resolver_clock_spent" -ge 1 ] && [ "$resolver_unit_delayed" = "$resolver_unit_baseline" ]; then
  pass "the resolver deadline contracts are driven by a test-owned clock, unmoved by real elapsed time"
else
  fail_test "the resolver deadline contracts are driven by a test-owned clock, unmoved by real elapsed time (real seconds spent: $resolver_clock_spent; undelayed: $resolver_unit_baseline / delayed: $resolver_unit_delayed)"
fi

# Every refusal is validity-strict but availability-best-effort for an ordinary upgrade: it names
# the failed stage, leaves no accepted/partial artifact, emits no captured marker, and still installs.
for refusal in backup_fail:backup_failed backup_empty:backup_empty; do
  txn_mode="${refusal%%:*}"; named="${refusal##*:}"
  reset_db_txn_state
  MOCK_DB_TXN="$txn_mode" run_provision "$MOCK_TARGET" --apk "$APK" --no-tame
  assert_success "a $txn_mode capture refusal remains advisory for an ordinary upgrade"
  assert_contains "$named" "the $txn_mode refusal names its stage"
  assert_marker_absent "a $txn_mode refusal never claims a captured snapshot"
  assert_log_contains '^adb .* install' "a $txn_mode refusal does not preempt APK install"
  if ls "$TMP"/auto-backups/*.break-glass.db >/dev/null 2>&1; then
    fail_test "a $txn_mode refusal leaves no host partial behind"
  else pass "a $txn_mode refusal leaves no host partial behind"; fi
done
# Root-route and adb transport failures are backup-only failures too. Under `set -e` they must flow
# through the explicit snapshot refusal policy so an ordinary recovery replacement still installs.
for transport_refusal in panel_hash_transport:'could not be hashed through the panel.s root route' push_fail:'could not be pushed to the panel'; do
  txn_mode="${transport_refusal%%:*}"; named="${transport_refusal#*:}"
  reset_db_txn_state
  MOCK_DB_TXN="$txn_mode" run_provision "$MOCK_TARGET" --apk "$APK" --no-tame
  assert_success "a $txn_mode backup transport failure remains advisory for an ordinary upgrade"
  assert_contains "$named" "the $txn_mode failure is routed through the explicit snapshot refusal"
  assert_marker_absent "a $txn_mode failure never claims a captured snapshot"
  assert_log_contains '^adb .* install' "a $txn_mode backup transport failure still reaches APK install"
done
# Invalid legacy evidence is discarded, but it does not turn an optional automatic backup into an
# ordinary-upgrade gate. Destructive reset coverage below retains the strict mutation boundary.
for refusal in integrity_bad:integrity_failed script_tamper:intact source_not_regular:source_not_regular source_directory:source_not_regular; do
  txn_mode="${refusal%%:*}"; named="${refusal##*:}"
  reset_db_txn_state
  MOCK_DB_TXN="$txn_mode" run_provision "$MOCK_TARGET" --apk "$APK" --no-tame
  assert_success "a $txn_mode unsafe snapshot is discarded while the ordinary upgrade continues"
  assert_contains "$named" "the $txn_mode unsafe evidence names its stage"
  assert_marker_absent "a $txn_mode unsafe snapshot never earns a captured marker"
  assert_log_contains '^adb .* install' "a $txn_mode unsafe snapshot does not preempt APK install"
done
# A tampered script must be refused BEFORE it executes as root: the sandbox proves no transaction ran.
reset_db_txn_state
MOCK_DB_TXN=script_tamper run_provision "$MOCK_TARGET" --apk "$APK" --no-tame
assert_success "a tampered capture script is refused without blocking the ordinary upgrade"
if [ -d "$TMP/db-txn-sandbox" ] && ls -d "$TMP/db-txn-sandbox/data/local/tmp"/.hapaneld-db-txn.* >/dev/null 2>&1; then
  fail_test "a tampered script is never executed as root (sandbox shows no staging)"
else pass "a tampered script is never executed as root (sandbox shows no staging)"; fi

# Admission refusals produced by the EXECUTED script: empty content, never-migrated and alien
# schemas — including the in-range-looking value one past the newest shipped migration — and a
# provenance the transaction itself cannot establish.
for admission in MOCK_DB_DEVICE_ROWS=0:rows_empty MOCK_DB_DEVICE_USER_VERSION=0:schema_alien MOCK_DB_DEVICE_USER_VERSION=15:schema_alien MOCK_DB_DEVICE_VCODE=absent:provenance_unreadable; do
  admission_env="${admission%%:*}"; named="${admission##*:}"
  env_name="${admission_env%%=*}"; env_value="${admission_env#*=}"
  reset_db_txn_state
  eval "$env_name=\"$env_value\" run_provision \"\$MOCK_TARGET\" --apk \"\$APK\" --no-tame"
  case "$admission_env" in
    MOCK_DB_DEVICE_USER_VERSION=0|MOCK_DB_DEVICE_USER_VERSION=15)
      assert_failure "a $admission_env incompatible database refuses before snapshot policy"
      assert_contains 'database compatibility could not be proven' \
        "the $admission_env refusal names the authoritative compatibility gate"
      assert_marker_absent "a $admission_env refusal never claims a captured snapshot"
      assert_not_contains '^adb .* install' "$MOCK_CALL_LOG" \
        "a $admission_env refusal precedes APK install"
      ;;
    *)
      assert_success "a $admission_env unsafe source is discarded while the ordinary upgrade continues"
      assert_contains "$named" "the $admission_env refusal names its stage"
      assert_marker_absent "a $admission_env refusal never claims a captured snapshot"
      assert_log_contains '^adb .* install' "a $admission_env unsafe source does not preempt APK install"
      ;;
  esac
done

# The host accepts only a complete manifest from the legacy transaction, while an invalid optional
# manifest is discarded without blocking an ordinary Android package replacement.
for manifest_case in manifest_missing_bytes:bytes manifest_missing_rows:rows manifest_missing_schema:schema manifest_missing_provenance:provenance manifest_missing_integrity:'capture manifest is incomplete' manifest_missing_sqlite:'capture manifest is incomplete'; do
  txn_mode="${manifest_case%%:*}"; named="${manifest_case#*:}"
  reset_db_txn_state
  MOCK_DB_TXN="$txn_mode" run_provision "$MOCK_TARGET" --apk "$APK" --no-tame
  assert_success "a $txn_mode unsafe manifest is discarded while the ordinary upgrade continues"
  assert_contains "$named" "the $txn_mode refusal names the incomplete evidence"
  assert_marker_absent "a $txn_mode manifest never earns a captured marker"
  assert_log_contains '^adb .* install' "a $txn_mode manifest does not preempt APK install"
done

reset_db_txn_state
MOCK_DB_TXN=panel_digest_invalid run_provision "$MOCK_TARGET" --apk "$APK" --no-tame
assert_success "a malformed panel digest discards the backup without stopping the ordinary upgrade"
assert_contains 'invalid panel digest' "the malformed panel digest is named"
assert_marker_absent "a malformed panel digest never earns a captured marker"
assert_log_contains '^adb .* install' "a malformed panel digest does not preempt APK install"

reset_db_txn_state
MOCK_DB_TXN=panel_digest_mismatch run_provision "$MOCK_TARGET" --apk "$APK" --no-tame
assert_success "a valid-shaped panel digest mismatch discards the backup without stopping the ordinary upgrade"
assert_contains "digest does not match what the panel captured" "the panel and host digest mismatch is named"
assert_marker_absent "a panel digest mismatch never earns a captured marker"
assert_log_contains '^adb .* install' "a panel digest mismatch does not preempt APK install"

# Transfer verification: a pull that truncates or fails never becomes a restore point.
reset_db_txn_state
MOCK_DB_TXN=pull_fail run_provision "$MOCK_TARGET" --apk "$APK" --no-tame
assert_success "a failed snapshot pull does not stop the ordinary upgrade"
assert_contains 'could not be pulled' "a failed pull is named"
assert_marker_absent "a failed pull never claims a captured snapshot"
assert_log_contains '^adb .* install' "a failed snapshot pull still reaches APK install"
reset_db_txn_state
MOCK_DB_TXN=pull_truncate run_provision "$MOCK_TARGET" --apk "$APK" --no-tame
assert_success "a truncated snapshot pull is discarded without stopping the ordinary upgrade"
assert_contains 'bytes but the panel captured' "a truncated pull is refused by the byte comparison"
assert_marker_absent "a truncated pull never claims a captured snapshot"
assert_log_contains '^adb .* install' "a truncated snapshot does not preempt APK install"

# Staging cleanup is best-effort: a proven capture keeps its verdict if the transport cannot remove
# the uniquely named panel-side staging afterwards, because the host file is the restore point.
reset_db_txn_state
MOCK_DB_CLEANUP=retained run_provision "$MOCK_TARGET" --apk "$APK" --no-tame
assert_success "a retained staging directory does not revoke a proven capture"
assert_marker_captured "the capture verdict stands despite a cleanup failure"
reset_db_txn_state
MOCK_DB_CLEANUP=unverifiable run_provision "$MOCK_TARGET" --apk "$APK" --no-tame
assert_success "an unverifiable removal does not revoke a proven capture"
reset_db_txn_state

# The whole capture, driven through the sh -c su dialect as well as the default join style, with the
# same whole-line captured evidence required.
MOCK_SU_DIALECT=shc run_provision "$MOCK_TARGET" --apk "$APK" --no-tame
assert_success "the capture succeeds through the sh -c su dialect"
assert_marker_captured "the sh -c dialect produces the same whole-line captured evidence"
reset_db_txn_state

# The remaining root forms. The capture transaction is proven under each with its exact wrapper
# visible in the call log; the run's LATER phases (helper transactions) are not modelled for these
# wrappers by this fixture, so only capture evidence is asserted.
for capture_dialect in rootjoin:'su root \"sh /data/local/tmp/\.hapaneld-db-txn' rootshc:'su root sh -c \"sh /data/local/tmp/\.hapaneld-db-txn' suc:'su -c \"sh /data/local/tmp/\.hapaneld-db-txn'; do
  dialect_name="${capture_dialect%%:*}"; wrapper_pattern="${capture_dialect#*:}"
  reset_db_txn_state
  MOCK_SU_DIALECT="$dialect_name" run_provision "$MOCK_TARGET" --apk "$APK" --no-tame
  assert_marker_captured "the $dialect_name dialect produces whole-line captured evidence"
  assert_log_contains "$wrapper_pattern" "the $dialect_name capture ran under its exact wrapper"
done

# Database admission uses the same independently probed root dispatch, but executes only the short
# nonce-owned staged-script path. Exercise every supported non-adbd form so the host-Bash observer
# fixture above cannot hide quoting, prefix or double-shell regressions in the production transport.
for observer_dialect in \
  join:'su 0 \"sh /data/local/tmp/\.hapaneld-db-observer\.[0-9a-f]{32}-script' \
  shc:'su 0 sh -c \"sh /data/local/tmp/\.hapaneld-db-observer\.[0-9a-f]{32}-script' \
  rootjoin:'su root \"sh /data/local/tmp/\.hapaneld-db-observer\.[0-9a-f]{32}-script' \
  rootshc:'su root sh -c \"sh /data/local/tmp/\.hapaneld-db-observer\.[0-9a-f]{32}-script' \
  suc:'su -c \"sh /data/local/tmp/\.hapaneld-db-observer\.[0-9a-f]{32}-script'; do
  dialect_name="${observer_dialect%%:*}"; wrapper_pattern="${observer_dialect#*:}"
  reset_db_txn_state
  MOCK_SU_DIALECT="$dialect_name" run_provision "$MOCK_TARGET" --apk "$APK" --no-tame
  assert_contains 'database compatible schema' "database admission succeeds through the $dialect_name root route"
  assert_log_contains "$wrapper_pattern" "database admission executes its staged observer through the $dialect_name root route"
done

reset_db_txn_state
MOCK_ADB_ROOT=1 MOCK_SU_DIALECT=none run_provision "$MOCK_TARGET" --apk "$APK" --no-tame
assert_contains 'database compatible schema' "database admission succeeds through root adbd"
assert_log_contains 'shell sh /data/local/tmp/\.hapaneld-db-observer\.[0-9a-f]{32}-script' \
  "database admission executes its staged observer directly through root adbd"
# The sixth form: adb-root panels run bare shell commands with no su wrapper at all.
reset_db_txn_state
MOCK_ADB_ROOT=1 run_provision "$MOCK_TARGET" --apk "$APK" --no-tame
assert_marker_captured "the plain-shell (adb root) form produces whole-line captured evidence"
reset_db_txn_state

# A staging collision: the loser must refuse by name and remove NOTHING — the winner's in-flight
# marker survives and no privileged removal for the staging path is even attempted.
MOCK_DB_TXN=stage_collision run_provision "$MOCK_TARGET" --apk "$APK" --no-tame
assert_success "a staging collision refuses the backup without blocking the ordinary upgrade"
assert_contains 'stage_exists' "the collision refusal names its stage"
collision_marker="$(find "$TMP/db-txn-sandbox/data/local/tmp" -name winner-in-flight 2>/dev/null | head -1)"
if [ -n "$collision_marker" ] && [ -f "$collision_marker" ]; then
  pass "the loser of a staging collision leaves the winner's in-flight capture untouched"
else fail_test "the loser of a staging collision leaves the winner's in-flight capture untouched"; fi
assert_not_contains 'rm -rf? [^|]*hapaneld-db-txn' "$MOCK_CALL_LOG" "the loser never even attempts a privileged removal of the contested staging"
reset_db_txn_state

# Panel-only digest degradation is stated, never silent: a panel with no digest tool still captures,
# while the host digest remains mandatory and is recorded in full.
MOCK_DB_TXN=no_device_digest run_provision "$MOCK_TARGET" --apk "$APK" --no-tame
assert_success "a panel without a digest tool still produces a verified capture"
assert_marker_captured "the digest-degraded capture still earns its marker"
assert_contains 'panel has no usable digest tool' "the panel-only degradation is stated on the console"
degraded_receipt="$(ls "$TMP"/auto-backups/*.break-glass.backup-receipt.txt 2>/dev/null | head -1)"
if grep -q '^database_sha256_panel=none$' "$degraded_receipt" 2>/dev/null; then
  pass "the receipt records the missing panel digest as none"
else fail_test "the receipt records the missing panel digest as none"; fi
if grep -Eq '^database_sha256_host=[0-9a-f]{64}$' "$degraded_receipt" 2>/dev/null; then
  pass "panel degradation retains a valid mandatory host digest"
else fail_test "panel degradation retains a valid mandatory host digest"; fi
reset_db_txn_state

# A nonempty but malformed host hash is not accepted as a digest. Export a one-run command shim so
# this stays black-box coverage without changing the shared adb/sha fixture owned by another lane.
export PROVISION_TEST_SHA256_FIXTURE="$FIXTURES/sha256sum"
sha256sum() {
  case "${1:-}" in
    *.break-glass.db) printf '%064d  %s\ntrailing-garbage  %s\n' 0 "$1" "$1" ;;
    *) command "$PROVISION_TEST_SHA256_FIXTURE" "$@" ;;
  esac
}
export -f sha256sum
run_provision "$MOCK_TARGET" --apk "$APK" --no-tame
assert_success "a multiline host digest rejects only the optional backup during an ordinary upgrade"
assert_contains 'could not be hashed on this host' "the multiline host digest is named"
assert_marker_absent "a multiline host digest never earns a captured marker"
assert_log_contains '^adb .* install' "host backup hashing failure does not preempt the ordinary APK replace"
if ls "$TMP"/auto-backups/*.break-glass* >/dev/null 2>&1; then
  fail_test "host digest refusal removes the database and receipt pair"
else pass "host digest refusal removes the database and receipt pair"; fi
unset -f sha256sum
unset PROVISION_TEST_SHA256_FIXTURE
reset_db_txn_state

# A digest executable can terminate instead of returning text. Guard the assignment itself: an
# inner `|| true` cannot contain `exit` from a shell function running inside command substitution.
export PROVISION_TEST_SHA256_FIXTURE="$FIXTURES/sha256sum"
sha256sum() {
  case "${1:-}" in
    *.break-glass.db) exit 17 ;;
    *) command "$PROVISION_TEST_SHA256_FIXTURE" "$@" ;;
  esac
}
export -f sha256sum
run_provision "$MOCK_TARGET" --apk "$APK" --no-tame
assert_success "a terminating legacy host digest remains an advisory backup refusal"
assert_contains 'published snapshot could not be hashed on this host' "legacy digest process failure is routed through snapshot refusal"
assert_log_contains '^adb .* install' "legacy digest process failure still reaches APK install"
reset_db_txn_state
MOCK_UPGRADE_PREPARE=ready run_provision "$MOCK_TARGET" --apk "$APK" --no-tame
assert_success "a terminating direct-copy host digest remains an advisory backup refusal"
assert_contains 'direct copy could not be hashed on this host' "direct digest process failure is routed through snapshot refusal"
assert_log_contains '^adb .* install' "direct digest process failure still reaches APK install"
unset -f sha256sum
unset PROVISION_TEST_SHA256_FIXTURE
reset_db_txn_state

# An install racing the capture changes the provenance answer between the transaction's two reads,
# and the snapshot is refused as unattributable.
MOCK_DB_TXN=provenance_race run_provision "$MOCK_TARGET" --apk "$APK" --no-tame
assert_success "a build change during capture discards the backup without blocking the ordinary upgrade"
assert_contains 'provenance_changed' "the provenance race is named"
assert_marker_absent "a provenance race never claims a captured snapshot"
reset_db_txn_state

# Structural pins on the publication custody that no black-box run can distinguish, each proven
# breakable by a named mutation: the snapshot reaches its final name only through no-replace ln
# after an existence refusal, and the database and receipt are registered as ONE cleanup unit so a
# signal in the publication window removes the pair or neither.
if [ "$(grep -c 'ln "\$pull_tmp" "\$base.db"' "$PROVISION")" -eq 1 ] &&    grep -q 'if \[ -e "\$base.db" \] || \[ -L "\$base.db" \]; then' "$PROVISION"; then
  pass "publication is no-replace: existence refusal plus hardlink finalization"
else fail_test "publication is no-replace: existence refusal plus hardlink finalization"; fi
if [ "$(grep -c 'SNAPSHOT_TXN_HOST_DB="\$base.db"' "$PROVISION")" -eq 1 ] &&
   grep -q 'SNAPSHOT_TXN_HOST_RECEIPT="\$receipt"' "$PROVISION" &&
   grep -q 'for stale in "\${SNAPSHOT_TXN_HOST_DB:-}" "\${SNAPSHOT_TXN_HOST_RECEIPT:-}"' "$PROVISION"; then
  pass "the database and its receipt are registered as one cleanup unit, in separate unsplittable variables"
else fail_test "the database and its receipt are registered as one cleanup unit, in separate unsplittable variables"; fi
receipt_guard_line="$(grep -n 'if \[ -e "\$receipt" \] || \[ -L "\$receipt" \]; then' "$PROVISION" | cut -d: -f1)"
receipt_owner_line="$(grep -n 'SNAPSHOT_TXN_HOST_RECEIPT="\$receipt"' "$PROVISION" | tail -1 | cut -d: -f1)"
if [ -n "$receipt_guard_line" ] && [ -n "$receipt_owner_line" ] && [ "$receipt_guard_line" -lt "$receipt_owner_line" ]; then
  pass "a pre-existing receipt is refused before this run claims ownership"
else fail_test "a pre-existing receipt is refused before this run claims ownership"; fi
if grep -q '\[ "\$SNAPSHOT_TXN_HOST_DB_WORK" -ef "\$SNAPSHOT_TXN_HOST_DB_TARGET" \]' "$PROVISION" &&
   grep -q '\[ "\$SNAPSHOT_TXN_HOST_RECEIPT_WORK" -ef "\$SNAPSHOT_TXN_HOST_RECEIPT_TARGET" \]' "$PROVISION"; then
  pass "publication-window cleanup removes final paths only when their working hardlink proves ownership"
else fail_test "publication-window cleanup removes final paths only when their working hardlink proves ownership"; fi
if [ "$(grep -c 'snapshot_txn_defer_host_signals' "$PROVISION")" -eq 7 ] &&
   [ "$(grep -c 'snapshot_txn_restore_host_signals' "$PROVISION")" -eq 9 ] &&
   grep -q 'SNAPSHOT_TXN_HOST_DB_WORK="\$pull_tmp"' "$PROVISION" &&
   grep -q 'SNAPSHOT_TXN_HOST_RECEIPT_WORK="\$receipt_tmp"' "$PROVISION"; then
  pass "temporary creation defers signals until database and receipt ownership are registered"
else fail_test "temporary creation defers signals until database and receipt ownership are registered"; fi
legacy_handoff_defer_line="$(grep -n '^  snapshot_txn_defer_host_signals$' "$PROVISION" | tail -1 | cut -d: -f1)"
legacy_db_disown_line="$(grep -n '^  SNAPSHOT_TXN_HOST_DB=""; SNAPSHOT_TXN_HOST_RECEIPT=""$' "$PROVISION" | tail -1 | cut -d: -f1)"
legacy_handoff_restore_line="$(grep -n '^  snapshot_txn_restore_host_signals$' "$PROVISION" | tail -1 | cut -d: -f1)"
legacy_remote_cleanup_line="$(grep -n 'run_root "rm -f \${stage}-script && rm -rf \$stage"' "$PROVISION" | tail -1 | cut -d: -f1)"
if [ -n "$legacy_handoff_defer_line" ] && [ "$legacy_handoff_defer_line" -lt "$legacy_db_disown_line" ] &&
   [ "$legacy_db_disown_line" -lt "$legacy_handoff_restore_line" ] &&
   [ "$legacy_handoff_restore_line" -lt "$legacy_remote_cleanup_line" ]; then
  pass "legacy accepted database and receipt handoff is signal-atomic before remote cleanup"
else fail_test "legacy accepted database and receipt handoff is signal-atomic before remote cleanup"; fi
restore_handler_line="$(grep -n "trap 'handle_provision_signal 130' INT" "$PROVISION" | tail -1 | cut -d: -f1)"
read_deferred_line="$(grep -n 'deferred="\$SNAPSHOT_TXN_DEFERRED_SIGNAL"' "$PROVISION" | cut -d: -f1)"
if [ -n "$restore_handler_line" ] && [ -n "$read_deferred_line" ] && [ "$restore_handler_line" -lt "$read_deferred_line" ]; then
  pass "real signal handlers are restored before the deferred signal is read or cleared"
else fail_test "real signal handlers are restored before the deferred signal is read or cleared"; fi

# The one-line installer forwards its argv to the downloaded provisioner unfiltered, so the escape
# reaches it; pinned on the exact invocation.
if grep -q 'bash "\$SCRIPT" "\${ARGS\[@\]}"' "$ROOT/scripts/install.sh"; then
  pass "the public installer hands its argument vector to the provisioner"
else fail_test "the public installer hands its argument vector to the provisioner"; fi

# Fleet workers share the best-effort ordinary-upgrade policy; the deprecated flag remains accepted
# as a compatibility no-op by the wrapper and each worker.
reset_db_txn_state
: > "$MOCK_CALL_LOG"
LAST_OUTPUT="$TMP/fleet-snapshot-escape.txt"
MOCK_TARGETS='panel-a.test:5555 panel-b.test:5555' MOCK_DB_TXN=backup_fail \
  bash "$UPDATE_FLEET" --apk "$APK" --allow-unsigned-helper --no-tame -- panel-a.test panel-b.test > "$LAST_OUTPUT" 2>&1
LAST_STATUS=$?
assert_success "a fleet whose snapshots are unavailable still completes ordinary upgrades"
assert_contains '2/2 panels OK' "snapshot availability alone does not fail a fleet wave"
fleet_observer_pushes="$(grep -E '^adb -s panel-(a|b)\.test:5555 push .+ /data/local/tmp/\.hapaneld-db-observer\.[0-9a-f]{32}-script$' "$MOCK_CALL_LOG" || true)"
fleet_observer_remote_paths="$(printf '%s\n' "$fleet_observer_pushes" | awk 'NF { print $NF }')"
fleet_observer_host_paths="$(grep -E '^sha256sum .*/hapaneld-db-observer\.[A-Za-z0-9]+$' "$MOCK_CALL_LOG" | awk 'NF { print $NF }' || true)"
fleet_observer_cleanup_paths="$(grep -E '^adb -s panel-(a|b)\.test:5555 shell .*rm -rf /data/local/tmp/\.hapaneld-db-observer\.[0-9a-f]{32}' "$MOCK_CALL_LOG" | sed -n 's#.*rm -rf \(/data/local/tmp/\.hapaneld-db-observer\.[0-9a-f]\{32\}\).*#\1#p')"
fleet_observer_count="$(printf '%s\n' "$fleet_observer_remote_paths" | grep -c . || true)"
if [ "$fleet_observer_count" -ge 2 ] &&
   [ "$(printf '%s\n' "$fleet_observer_remote_paths" | sort -u | grep -c .)" = "$fleet_observer_count" ] &&
   printf '%s\n' "$fleet_observer_pushes" 2>/dev/null | grep -q '^adb -s panel-a\.test:5555 ' &&
   printf '%s\n' "$fleet_observer_pushes" 2>/dev/null | grep -q '^adb -s panel-b\.test:5555 '; then
  pass "concurrent fleet workers use distinct nonce-owned remote observer scripts"
else fail_test "concurrent fleet workers use distinct nonce-owned remote observer scripts"; fi
if [ "$(printf '%s\n' "$fleet_observer_host_paths" | grep -c . || true)" = "$fleet_observer_count" ] &&
   [ "$(printf '%s\n' "$fleet_observer_host_paths" | sort -u | grep -c .)" = "$fleet_observer_count" ]; then
  pass "concurrent fleet workers use distinct host observer files"
else fail_test "concurrent fleet workers use distinct host observer files"; fi
if [ "$(printf '%s\n' "$fleet_observer_cleanup_paths" | grep -c . || true)" = "$fleet_observer_count" ] &&
   [ "$(printf '%s\n' "$fleet_observer_cleanup_paths" | sort -u | grep -c .)" = "$fleet_observer_count" ]; then
  pass "concurrent fleet workers clean only their nonce-owned observer stages"
else fail_test "concurrent fleet workers clean only their nonce-owned observer stages"; fi
reset_db_txn_state
: > "$MOCK_CALL_LOG"
LAST_OUTPUT="$TMP/fleet-snapshot-escape2.txt"
MOCK_TARGETS='panel-a.test:5555 panel-b.test:5555' MOCK_DB_TXN=backup_fail \
  bash "$UPDATE_FLEET" --apk "$APK" --allow-unsigned-helper --allow-missing-db-snapshot --no-tame -- panel-a.test panel-b.test > "$LAST_OUTPUT" 2>&1
LAST_STATUS=$?
assert_success "the fleet wrapper continues accepting the deprecated snapshot flag"
assert_contains '2/2 panels OK' "the compatibility flag preserves the same best-effort behavior"
reset_db_txn_state

# The public checkout-free installer must continue accepting the compatibility option. Acceptance is
# proven POSITIVELY, with no network: the flag is followed by a deliberate unknown sentinel, so an
# accepting parser consumes the flag and rejects the SENTINEL by name, while a rejecting parser
# names the escape itself. The assertion pair distinguishes the two exactly.
bash "$ROOT/scripts/install.sh" --provision panel-a.test --allow-missing-db-snapshot --hapaneld-test-sentinel > "$LAST_OUTPUT" 2>&1
LAST_STATUS=$?
assert_failure "the installer parser run ends at the deliberate sentinel"
assert_contains 'unknown provisioning option: --hapaneld-test-sentinel' "the public installer consumes the snapshot escape and rejects only the sentinel"
assert_not_contains 'unknown provisioning option: --allow-missing-db-snapshot' "$LAST_OUTPUT" "the public installer accepts --allow-missing-db-snapshot"

fi
[ "$PROVISION_TEST_SCOPE" != shard-database-authority ] || finish_provision_test

if provision_scope_is core all shard-fleet-installer; then
# ── --reset-config ──────────────────────────────────────────────────────────────────────────────
# A clean install must reach a genuine FIRST RUN, not a repair. Everything here exists to make the
# irreversible erase deliberate and impossible to trigger by accident or in bulk.
run_provision "$MOCK_TARGET" --verify --reset-config
assert_failure "--reset-config with --verify returns nonzero"
assert_contains '(read-only|never changes)' "a read-only run refuses to combine with an erase"
assert_not_contains 'pm clear' "$MOCK_CALL_LOG" "a rejected reset never reaches the package manager"

run_provision "$MOCK_TARGET" --apk "$APK" --no-tame --reset-config --restore "$RESTORE"
assert_failure "--reset-config with --restore returns nonzero"
assert_contains 'opposite intents' "erase-then-import names the contradiction"

run_provision "$MOCK_TARGET" --apk "$APK" --no-tame --export "$TMP/export-then-reset.json" --reset-config
assert_failure "--reset-config with --export returns nonzero"
assert_contains 'Run --export FILE separately first' "reset directs backup-seeking users to a separate operation"
assert_not_contains 'pm clear|config/export|PREPARE_UPGRADE' "$MOCK_CALL_LOG" "a rejected export/reset pairing does not contact backup or erase paths"

HAPANELD_RESET_CONFIRM=no run_provision "$MOCK_TARGET" --apk "$APK" --no-tame --reset-config
assert_failure "an unconfirmed reset returns nonzero"
assert_contains 'was not confirmed' "an unconfirmed reset says so"
assert_contains 'Nothing was erased' "an unconfirmed reset states that the panel is untouched"
assert_not_contains 'pm clear' "$MOCK_CALL_LOG" "an unconfirmed reset never reaches the package manager"
assert_not_contains 'config/export|PREPARE_UPGRADE|sqlite3 \.backup|exec-out .*ha-paneld.db|^adb .* install' "$MOCK_CALL_LOG" "an unconfirmed reset performs no backup or install work"

# --force skips a version comparison; it must not stand in for authorising a wipe.
run_provision "$MOCK_TARGET" --apk "$APK" --no-tame --reset-config --force
assert_failure "--force does not authorise an unconfirmed reset"
assert_not_contains 'pm clear' "$MOCK_CALL_LOG" "--force never reaches the package manager on its own"

HAPANELD_RESET_CONFIRM=RESET MOCK_SETUP=identity MOCK_ROOT=0 MOCK_EXPORT=fail MOCK_DB_TXN=backup_fail MOCK_UPGRADE_PREPARE=digest_mismatch \
  run_provision "$MOCK_TARGET" --apk "$APK" --no-tame --reset-config
assert_success "a confirmed reset completes"
assert_log_contains '^adb -s panel\.test:5555 shell pm clear io.github.maxlyth.hapaneld$' "a confirmed reset targets exactly ha-paneld"
if [ "$(grep -Ec '^adb -s panel\.test:5555 shell pm clear io\.github\.maxlyth\.hapaneld$' "$MOCK_CALL_LOG")" = 1 ]; then
  pass "a confirmed reset issues exactly one package clear"
else fail_test "a confirmed reset issues exactly one package clear"; fi
assert_not_contains 'config/export|PREPARE_UPGRADE|sqlite3 \.backup|exec-out .*ha-paneld.db' "$MOCK_CALL_LOG" "reset bypasses settings export and database capture"
assert_contains 'configuration erased' "a confirmed reset reports what it did"
assert_contains 'Next: confirm this panel.s name' "a reset panel lands in guided setup, not in repair"

HAPANELD_RESET_CONFIRM=RESET MOCK_PM_CLEAR=fail run_provision "$MOCK_TARGET" --apk "$APK" --no-tame --reset-config
assert_failure "a failed erase returns nonzero"
assert_contains 'could not erase the panel configuration' "a failed erase names what went wrong"
assert_log_contains '^adb -s panel\.test:5555 shell pm clear io.github.maxlyth.hapaneld$' "a failed erase still targets exactly ha-paneld"
assert_not_contains 'configuration erased' "$LAST_OUTPUT" "a failed erase never prints the success claim"

# ── Fleet argument scope ────────────────────────────────────────────────────────────────────────
# Every pass-through arg is forwarded verbatim to every worker, so an option that describes ONE panel is
# wrong when multiplied: it collides on a single shared resource or applies one panel's data to all of
# them, and because each worker still exits 0 the fleet reports all-OK while the damage is silent. Each
# such option must be refused before any worker starts. The table pairs the option with the wording that
# must point the operator at the per-panel alternative, and with the panel-side operation the refusal
# must prevent. Two panels are used because multiplication is the whole defect.
# Columns: option | value (empty when the option takes none) | required guidance | forbidden panel call
fleet_scoped_options=(
  # Bulk erase is not offered: workers run with stdin closed, so a single exported confirmation would
  # otherwise wipe every panel at once.
  "--reset-config||erase one panel at a time with scripts/provision.sh|pm clear"
  # One destination for the whole fleet is last-writer-wins, and naming it also cancels each panel's
  # own automatic pre-upgrade export — so the overwritten panels would have no backup anywhere.
  "--export|$TMP/fleet-shared-export.json|cancels each panel's own automatic pre-upgrade settings export|config/export"
  "--id|one-id-for-every-panel|a panel id names one panel|panel_id="
  # Device-scoped keys belong to the panel they came from; --restore-fleet is the portable form.
  "--restore|$RESTORE|use --restore-fleet to apply only the portable settings|config/import"
)
for fleet_scoped_case in "${fleet_scoped_options[@]}"; do
  IFS='|' read -r fleet_option fleet_value fleet_guidance fleet_forbidden <<< "$fleet_scoped_case"
  fleet_argv=("$fleet_option")
  [ -z "$fleet_value" ] || fleet_argv+=("$fleet_value")
  : > "$MOCK_CALL_LOG"
  LAST_OUTPUT="$TMP/fleet-scoped-${fleet_option#--}-output.txt"
  # HAPANELD_RESET_CONFIRM arms the precise hazard the --reset-config refusal exists to prevent: one
  # exported confirmation satisfying every worker at once. Without it an unconfirmed erase stops short
  # of the package manager on its own, so the forbidden-call assertion below would pass even with the
  # refusal deleted — proven by mutation. It is inert for the other options.
  MOCK_TARGETS='panel-a.test:5555 panel-b.test:5555' HAPANELD_RESET_CONFIRM=RESET \
    bash "$UPDATE_FLEET" --apk "$APK" --allow-unsigned-helper --no-tame "${fleet_argv[@]}" \
      -- panel-a.test panel-b.test > "$LAST_OUTPUT" 2>&1
  LAST_STATUS=$?
  assert_status 2 "fleet updates refuse the panel-scoped $fleet_option"
  assert_contains "$fleet_option is not available for fleet updates" "the $fleet_option refusal names the option it rejected"
  assert_contains "$fleet_guidance" "the $fleet_option refusal names its per-panel alternative"
  assert_not_contains '^adb ' "$MOCK_CALL_LOG" "a refused $fleet_option starts no panel worker"
  assert_not_contains "$fleet_forbidden" "$MOCK_CALL_LOG" "a refused $fleet_option never reaches its panel-side operation"
done

# --restore-fleet is deliberately absent from that table: it carries only portable, non-secret keys, so
# applying it to every panel is exactly what it is for. Proven POSITIVELY — the mode-tagged import must
# reach BOTH panels — because "the wrapper did not refuse it" is also true of a silently dropped flag.
: > "$MOCK_CALL_LOG"
rm -f "$TMP"/record-audio-granted.* "$TMP"/post-notifications-granted.* "$TMP"/write-settings-granted.*
LAST_OUTPUT="$TMP/fleet-restore-fleet-output.txt"
MOCK_TARGETS='panel-a.test:5555 panel-b.test:5555' \
  bash "$UPDATE_FLEET" --apk "$APK" --allow-unsigned-helper --no-tame --restore-fleet "$RESTORE" \
    -- panel-a.test panel-b.test > "$LAST_OUTPUT" 2>&1
LAST_STATUS=$?
assert_success "the portable --restore-fleet survives the panel-scoped guard"
assert_contains '2/2 panels OK' "the portable fleet restore completes on every panel"
fleet_portable_imports="$(grep -Ec 'config/import\?mode=fleet' "$MOCK_CALL_LOG" || true)"
if [ "$fleet_portable_imports" -eq 2 ]; then
  pass "the portable fleet restore reaches both panels in fleet mode"
else
  fail_test "the portable fleet restore reaches both panels in fleet mode (got $fleet_portable_imports)"
fi

# The implicit per-panel export is the backup --export would have cancelled, so the guard is only worth
# anything if that export still happens. An ordinary fleet run must leave one distinct file per panel.
FLEET_AUTO_BACKUP_DIR="$TMP/fleet-auto-backups"
rm -rf "$FLEET_AUTO_BACKUP_DIR"
: > "$MOCK_CALL_LOG"
rm -f "$TMP"/record-audio-granted.* "$TMP"/post-notifications-granted.* "$TMP"/write-settings-granted.*
LAST_OUTPUT="$TMP/fleet-auto-export-output.txt"
MOCK_TARGETS='panel-a.test:5555 panel-b.test:5555' HAPANELD_CONFIG_BACKUP_DIR="$FLEET_AUTO_BACKUP_DIR" \
  bash "$UPDATE_FLEET" --apk "$APK" --allow-unsigned-helper --no-tame \
    -- panel-a.test panel-b.test > "$LAST_OUTPUT" 2>&1
LAST_STATUS=$?
assert_success "an ordinary fleet run completes with no fleet-wide export destination"
fleet_auto_export_a=0; fleet_auto_export_b=0; fleet_auto_export_other=0
for fleet_auto_export in "$FLEET_AUTO_BACKUP_DIR"/*.json; do
  [ -f "$fleet_auto_export" ] || continue
  case "${fleet_auto_export##*/}" in
    panel-a.test_5555-*) fleet_auto_export_a=$((fleet_auto_export_a + 1)) ;;
    panel-b.test_5555-*) fleet_auto_export_b=$((fleet_auto_export_b + 1)) ;;
    *) fleet_auto_export_other=$((fleet_auto_export_other + 1)) ;;
  esac
done
if [ "$fleet_auto_export_a" -eq 1 ] && [ "$fleet_auto_export_b" -eq 1 ] && [ "$fleet_auto_export_other" -eq 0 ]; then
  pass "each panel keeps its own implicit pre-upgrade settings export"
else
  fail_test "each panel keeps its own implicit pre-upgrade settings export (panel-a $fleet_auto_export_a, panel-b $fleet_auto_export_b, other $fleet_auto_export_other)"
fi

# Official-signer policy is enforced once before workers start. The default still accepts one consistent
# developer signer; --require-release-signer pins the official ha-paneld release certificate.
: > "$MOCK_CALL_LOG"
LAST_OUTPUT="$TMP/fleet-wrong-signer-output.txt"
MOCK_RELEASE_CERT=0000000000000000000000000000000000000000000000000000000000000000 \
  bash "$UPDATE_FLEET" --require-release-signer --apk "$APK" -- "$MOCK_TARGET" > "$LAST_OUTPUT" 2>&1
LAST_STATUS=$?
assert_failure "fleet release policy rejects a foreign signer before workers start"
assert_contains 'does not use the required release signer' "fleet foreign-signer failure names the policy"
assert_not_contains '^adb ' "$MOCK_CALL_LOG" "fleet foreign-signer failure starts no panel worker"

: > "$MOCK_CALL_LOG"
LAST_OUTPUT="$TMP/fleet-multiple-signers-output.txt"
MOCK_ADDITIONAL_RELEASE_CERT=0000000000000000000000000000000000000000000000000000000000000000 \
  bash "$UPDATE_FLEET" --apk "$APK" -- "$MOCK_TARGET" > "$LAST_OUTPUT" 2>&1
LAST_STATUS=$?
assert_failure "fleet preflight rejects a multi-signed APK"
assert_contains 'must have exactly one signer' "fleet multi-signer failure names the invariant"
assert_not_contains '^adb ' "$MOCK_CALL_LOG" "fleet multi-signer failure starts no panel worker"

: > "$MOCK_CALL_LOG"
LAST_OUTPUT="$TMP/fleet-wrong-package-output.txt"
MOCK_RELEASE_PACKAGE=example.foreign bash "$UPDATE_FLEET" --apk "$APK" -- "$MOCK_TARGET" > "$LAST_OUTPUT" 2>&1
LAST_STATUS=$?
assert_failure "fleet preflight rejects a foreign package"
assert_contains 'fleet APK package mismatch' "fleet package failure names the mismatch"
assert_not_contains '^adb ' "$MOCK_CALL_LOG" "fleet package failure starts no panel worker"

: > "$MOCK_CALL_LOG"
LAST_OUTPUT="$TMP/fleet-unverifiable-apk-output.txt"
MOCK_LOCAL_APK_VERIFY_FAIL=1 bash "$UPDATE_FLEET" --apk "$APK" -- "$MOCK_TARGET" > "$LAST_OUTPUT" 2>&1
LAST_STATUS=$?
assert_failure "fleet preflight rejects an unverifiable local APK"
assert_contains 'fleet APK signature verification failed' "fleet unverifiable APK names the failure"
assert_not_contains '^adb ' "$MOCK_CALL_LOG" "fleet unverifiable APK starts no panel worker"

: > "$MOCK_CALL_LOG"
LAST_OUTPUT="$TMP/fleet-missing-apk-output.txt"
bash "$UPDATE_FLEET" --apk "$TMP/does-not-exist.apk" -- "$MOCK_TARGET" > "$LAST_OUTPUT" 2>&1
LAST_STATUS=$?
assert_failure "fleet preflight rejects a missing explicit APK"
assert_contains 'fleet APK is missing or empty' "fleet missing APK names the failure"
assert_not_contains '^adb ' "$MOCK_CALL_LOG" "fleet missing APK starts no panel worker"

: > "$MOCK_CALL_LOG"
LAST_OUTPUT="$TMP/fleet-missing-signer-tool-output.txt"
PATH="$NO_SIGNER_FIXTURES" ANDROID_HOME= ANDROID_SDK_ROOT= \
  bash "$UPDATE_FLEET" --require-release-signer --apk "$APK" -- "$MOCK_TARGET" > "$LAST_OUTPUT" 2>&1
LAST_STATUS=$?
assert_failure "fleet release policy fails closed without apksigner"
assert_contains 'apksigner is required for fleet deployment' "fleet missing-tool failure names apksigner"
assert_not_contains '^adb ' "$MOCK_CALL_LOG" "fleet missing-tool failure starts no panel worker"

: > "$MOCK_CALL_LOG"
SIGNER_ONLY_FIXTURES="$TMP/signer-only-fixtures"
mkdir -p "$SIGNER_ONLY_FIXTURES"
ln -s "$FIXTURES/apksigner" "$SIGNER_ONLY_FIXTURES/apksigner"
ln -s "$(command -v dirname)" "$SIGNER_ONLY_FIXTURES/dirname"
LAST_OUTPUT="$TMP/fleet-missing-aapt-output.txt"
# Invoke bash absolutely and expose only the two commands needed before the expected refusal. Adding
# /usr/bin or /bin here would leak a host-installed aapt/aapt2 back into this absence scenario.
PATH="$SIGNER_ONLY_FIXTURES" ANDROID_HOME= ANDROID_SDK_ROOT= \
  /bin/bash "$UPDATE_FLEET" --require-release-signer --apk "$APK" -- "$MOCK_TARGET" > "$LAST_OUTPUT" 2>&1
LAST_STATUS=$?
assert_failure "fleet release policy fails closed without aapt"
assert_contains 'aapt or aapt2 is required to verify a local fleet APK' "fleet missing-tool failure names aapt"
assert_not_contains '^adb ' "$MOCK_CALL_LOG" "fleet missing-aapt failure starts no panel worker"

# The same absent aapt, but an official release asset. The signed checksum has already pinned the whole
# file by hashing it, so the package name it would have read is settled and the tool is not needed.
#
# A host with everything EXCEPT aapt/aapt2. Subtraction is the only honest way to model an absent tool
# on a machine that has one: putting /usr/bin back on PATH would re-introduce the very thing under test,
# which is exactly how the sibling assertion above spent a month unable to fail.
NO_AAPT_BIN="$TMP/no-aapt-bin"
mkdir -p "$NO_AAPT_BIN"
for aapt_free_dir in "$FIXTURES" /usr/bin /bin; do
  [ -d "$aapt_free_dir" ] || continue
  for aapt_free_tool in "$aapt_free_dir"/*; do
    case "${aapt_free_tool##*/}" in aapt|aapt2) continue ;; esac
    [ -e "$NO_AAPT_BIN/${aapt_free_tool##*/}" ] || ln -s "$aapt_free_tool" "$NO_AAPT_BIN/${aapt_free_tool##*/}" 2>/dev/null
  done
done
if PATH="$NO_AAPT_BIN" command -v aapt >/dev/null 2>&1 || PATH="$NO_AAPT_BIN" command -v aapt2 >/dev/null 2>&1; then
  fail_test "the aapt-free sandbox really has no aapt"
else
  pass "the aapt-free sandbox really has no aapt"
fi
: > "$MOCK_CALL_LOG"
LAST_OUTPUT="$TMP/fleet-release-without-aapt-output.txt"
PATH="$NO_AAPT_BIN" ANDROID_HOME= ANDROID_SDK_ROOT= \
  bash "$UPDATE_FLEET" --require-release-signer --apk "$RELEASE_APK" --release-tag v0.9.2-rc3 -- "$MOCK_TARGET" > "$LAST_OUTPUT" 2>&1
LAST_STATUS=$?
# Deliberately NOT assert_success, and the reason is the whole point of this block. A host with no
# aapt still cannot read the candidate's database-compatibility metadata out of its manifest, and that
# gate refuses rather than guess. It is a data-loss guard, it is not what this change is about, and a
# signed checksum cannot stand in for it: the hash proves WHICH apk this is, not what schema range it
# declares. So the run must still stop - what changes is WHICH gate stops it, and what it says.
#
# Absence checks alone would not establish that. A run that died of a package mismatch also lacks the
# aapt message, so the positive assertions below carry the weight. Since #24 that gate refuses in the
# missing tool's own words, naming what the tool is for, rather than as a database verdict.
assert_failure "a release asset without aapt still stops at the database gate"
assert_contains 'updating the ha-paneld already on this panel needs aapt or aapt2 from Android SDK Build-Tools on this computer' \
  "the refusal comes from the database-contract read and names the missing tool"
assert_contains 'package authenticated release asset' "the package gate is satisfied by the signed checksum instead of a tool"
assert_not_contains 'aapt or aapt2 is required' "$LAST_OUTPUT" "a release asset is not refused for a missing aapt"
assert_not_contains 'package mismatch' "$LAST_OUTPUT" "a release asset is not refused for an unreadable package name"

# apksigner is NOT relaxed alongside aapt, and this is the assertion that says so on purpose. It reads
# the signer of the app ALREADY on the panel to refuse a cross-signer replacement, and no checksum over
# the candidate can answer that question.
: > "$MOCK_CALL_LOG"
NO_SIGNER_WITH_TAG="$TMP/no-signer-with-tag"
mkdir -p "$NO_SIGNER_WITH_TAG"
ln -s "$(command -v dirname)" "$NO_SIGNER_WITH_TAG/dirname"
LAST_OUTPUT="$TMP/fleet-release-without-apksigner-output.txt"
PATH="$NO_SIGNER_WITH_TAG" ANDROID_HOME= ANDROID_SDK_ROOT= \
  /bin/bash "$UPDATE_FLEET" --require-release-signer --apk "$RELEASE_APK" --release-tag v0.9.2-rc3 -- "$MOCK_TARGET" > "$LAST_OUTPUT" 2>&1
LAST_STATUS=$?
# The load-bearing check is the MESSAGE, not the exit status. Without apksigner the run fails either
# way - it would just fail later, at signature verification, for a reason that blames the artifact
# instead of the host. A bare assert_failure cannot tell those apart and would pass either way.
assert_failure "a release asset is still refused without apksigner"
assert_contains 'apksigner is required for fleet deployment' "the apksigner refusal survives the aapt relaxation"
assert_not_contains 'signature verification failed' "$LAST_OUTPUT" "a missing apksigner is not reported as a bad artifact"
assert_not_contains '^adb ' "$MOCK_CALL_LOG" "fleet missing-apksigner failure starts no panel worker"

# update-fleet.sh hands off to provision.sh, which asks the same question again. Relaxing only the
# wrapper would be cosmetic: its own download path forces --require-release-signer, and that used to
# drag the package tool in with it, so the refusal simply moved one script downstream.
#
# The condition is READ OUT OF THE SHIPPED FILE rather than restated here. A test that restates a
# boolean passes forever after the source stops matching it.
package_gate_condition="$(grep -n 'elif \[ -z "\$APK_RELEASE_TAG" \]' "$PROVISION" | head -1 | cut -d: -f2- | sed 's/^ *elif //; s/; then$//')"
if [ -n "$package_gate_condition" ]; then
  pass "the package-tool gate condition was found in provision.sh"
else
  fail_test "the package-tool gate condition was found in provision.sh"
fi
package_gate_refuses() {
  APK_RELEASE_TAG="$1" REQUIRE_RELEASE_SIGNER="$2" /bin/sh -c "
    APK_RELEASE_TAG='$1'; REQUIRE_RELEASE_SIGNER='$2'
    if $package_gate_condition; then echo refuses; else echo proceeds; fi"
}
[ "$(package_gate_refuses v0.9.2-rc3 1)" = proceeds ] \
  && pass "an authenticated release asset installs without a package tool even under --require-release-signer" \
  || fail_test "an authenticated release asset installs without a package tool even under --require-release-signer"
[ "$(package_gate_refuses v0.9.2-rc3 0)" = proceeds ] \
  && pass "an authenticated release asset installs without a package tool" \
  || fail_test "an authenticated release asset installs without a package tool"
[ "$(package_gate_refuses '' 0)" = refuses ] \
  && pass "a local APK still requires a package tool" \
  || fail_test "a local APK still requires a package tool"
[ "$(package_gate_refuses '' 1)" = refuses ] \
  && pass "a local APK still requires a package tool under --require-release-signer" \
  || fail_test "a local APK still requires a package tool under --require-release-signer"

: > "$MOCK_CALL_LOG"
LAST_OUTPUT="$TMP/fleet-duplicate-apk-output.txt"
bash "$UPDATE_FLEET" --apk "$APK" --apk "$APK" -- "$MOCK_TARGET" > "$LAST_OUTPUT" 2>&1
LAST_STATUS=$?
assert_status 2 "fleet preflight rejects duplicate APK selectors"
assert_contains '--apk may be supplied only once' "fleet duplicate APK failure names the conflict"
assert_not_contains '^adb ' "$MOCK_CALL_LOG" "fleet duplicate APK failure starts no panel worker"

# Builds older than the setup endpoint keep working guidance, derived from config alone.
MOCK_SETUP=missing run_provision "$MOCK_TARGET" --apk "$APK" --no-tame
assert_success "a panel without the setup endpoint completes provisioning"
assert_contains 'Next: configure this panel in your browser.*/configure' "a pre-wizard panel is still given a next step"
assert_not_contains 'cfg-ha-oauth' "$LAST_OUTPUT" "a pre-wizard panel with no server URL is not offered the sign-in deep link"

MOCK_ADB_STATE=unauthorized run_provision "$MOCK_TARGET" --verify
assert_failure "unauthorized adb returns nonzero"
assert_contains '(unauthorized|authorization dialog|accept)' "unauthorized adb gives on-panel recovery instructions"
unset MOCK_ADB_STATE

MOCK_ADB_STATE=offline run_provision "$MOCK_TARGET" --verify
assert_failure "offline adb returns nonzero"
assert_contains '(offline|ADB debugging|power-cycle)' "offline adb gives recovery instructions"
unset MOCK_ADB_STATE

MOCK_ADB_STATE=missing run_provision "$MOCK_TARGET" --verify
assert_failure "unreachable panel returns nonzero"
assert_contains '(cannot reach|network ADB|IP is right)' "unreachable panel gives network recovery instructions"
unset MOCK_ADB_STATE

# A typo must produce product language, not Bash's internal `unbound variable` diagnostic.
run_provision "$MOCK_TARGET" --mqtt
assert_failure "missing option value returns nonzero"
assert_contains '(--mqtt.*(value|argument)|missing.*(--mqtt|value)|requires?.*(value|argument))' "missing option value names the problem"
assert_not_contains 'unbound variable' "$LAST_OUTPUT" "missing option value hides Bash internals"

MOCK_GH_FAIL=1 run_provision "$MOCK_TARGET" --latest --no-tame
assert_failure "release resolver failure returns nonzero"
assert_contains 'could not fetch the latest release APK' "release resolver failure gives a product-level recovery message"
unset MOCK_GH_FAIL

# The unauthenticated fallback accepts only the exact HTTPS asset path implied by the release tag.
MOCK_GH_FAIL=1 MOCK_GITHUB_API=pretty run_provision "$MOCK_TARGET" --prerelease --no-tame
assert_success "provisioner prerelease REST fallback accepts a matching GitHub asset"
assert_log_contains 'curl .*--proto =https --proto-redir =https .*https://api\.github\.com/repos/maxlyth/ha-paneld/releases\?per_page=100' "release metadata redirects remain HTTPS"
assert_log_contains 'curl .*--proto =https --proto-redir =https .*https://github\.com/maxlyth/ha-paneld/releases/download/v0\.9\.2-rc3/ha-paneld-v0\.9\.2-rc3-manual-setup-required\.apk' "release APK download is constrained to the exact GitHub path"
rest_signer_line="$(grep -nE '^apksigner verify --print-certs ' "$MOCK_CALL_LOG" | head -1 | cut -d: -f1)"
rest_install_line="$(grep -nE '^adb .* install( |$)' "$MOCK_CALL_LOG" | head -1 | cut -d: -f1)"
if [ -n "$rest_signer_line" ] && [ -n "$rest_install_line" ] && [ "$rest_signer_line" -lt "$rest_install_line" ]; then
  pass "REST-downloaded release is signer-verified before install"
else
  fail_test "REST-downloaded release is signer-verified before install"
fi

: > "$MOCK_CALL_LOG"
MOCK_GH_FAIL=1 MOCK_GITHUB_API=stable_newest run_provision "$MOCK_TARGET" --prerelease --no-tame
assert_success "provisioner inclusive channel accepts a newer stable release"
assert_log_contains 'releases/download/v0\.9\.3/ha-paneld-v0\.9\.3-manual-setup-required\.apk' \
  "provisioner inclusive channel selects the newer stable asset"
assert_not_contains 'releases/download/v0\.9\.2-rc3/' "$MOCK_CALL_LOG" \
  "provisioner keeps the newer stable tag paired with its own asset"

: > "$MOCK_CALL_LOG"
MOCK_GH_FAIL=1 MOCK_GITHUB_API=stable_only run_provision "$MOCK_TARGET" --prerelease --no-tame
assert_success "provisioner inclusive channel works after release candidates are deleted"
assert_log_contains 'releases/download/v0\.9\.3/ha-paneld-v0\.9\.3-manual-setup-required\.apk' \
  "provisioner inclusive channel falls through to the remaining stable release"

: > "$MOCK_CALL_LOG"
MOCK_GH_FAIL=1 MOCK_GITHUB_API=draft_newest run_provision "$MOCK_TARGET" --prerelease --no-tame
assert_success "provisioner inclusive channel ignores an unpublished draft"
assert_log_contains 'releases/download/v0\.9\.3/ha-paneld-v0\.9\.3-manual-setup-required\.apk' \
  "provisioner inclusive channel selects the first published release"
assert_not_contains 'releases/download/v0\.9\.4-rc1/' "$MOCK_CALL_LOG" \
  "provisioner never downloads an asset from a draft release"

PATH="$NO_GH_FIXTURES:/usr/bin:/bin" MOCK_GITHUB_API=pretty \
  run_provision "$MOCK_TARGET" --prerelease --no-tame
assert_success "provisioner REST fallback works when gh is not installed"
assert_not_contains '^gh ' "$MOCK_CALL_LOG" "no-gh fallback does not invoke GitHub CLI"
assert_not_contains 'unbound variable' "$LAST_OUTPUT" "no-gh fallback never exposes an unset tag variable"

MOCK_GH_FAIL=1 MOCK_GITHUB_API=oversized run_provision "$MOCK_TARGET" --prerelease --no-tame
assert_success "provisioner consumes an oversized prerelease response without SIGPIPE"
assert_log_contains 'releases/download/v0\.9\.2-rc3/ha-paneld-v0\.9\.2-rc3-manual-setup-required\.apk' \
  "oversized provisioner response retains the first prerelease asset"

MOCK_GH_FAIL=1 MOCK_GITHUB_API=foreign run_provision "$MOCK_TARGET" --prerelease --no-tame
assert_failure "provisioner rejects a release asset hosted outside the canonical GitHub path"
assert_contains 'could not fetch the latest release APK' "foreign release URL failure gives safe recovery guidance"
assert_not_contains 'https://downloads\.test/' "$MOCK_CALL_LOG" "foreign release URL is never downloaded"
assert_not_contains '^adb .* install( |$)' "$MOCK_CALL_LOG" "foreign release URL is rejected before APK install"

# Fleet prerelease selection must resolve and pin the newest release including release candidates.
: > "$MOCK_CALL_LOG"
LAST_OUTPUT="$TMP/fleet-output.txt"
MOCK_GITHUB_API=pretty bash "$UPDATE_FLEET" --prerelease -- "$MOCK_TARGET" > "$LAST_OUTPUT" 2>&1
LAST_STATUS=$?
assert_success "fleet prerelease update succeeds"
if grep -Fq 'https://api.github.com/repos/maxlyth/ha-paneld/releases?per_page=100' "$MOCK_CALL_LOG" && \
   grep -Fq 'https://github.com/maxlyth/ha-paneld/releases/download/v0.9.2-rc3/ha-paneld-v0.9.2-rc3-manual-setup-required.apk' "$MOCK_CALL_LOG"; then
  pass "fleet prerelease resolves an explicit release-candidate tag"
else
  fail_test "fleet prerelease resolves an explicit release-candidate tag"
fi
assert_log_contains 'curl .*--proto =https --proto-redir =https .*ha-paneld-v0\.9\.2-rc3-manual-setup-required\.apk' "fleet download pins the exact release asset name"
assert_not_contains '^gh ' "$MOCK_CALL_LOG" "fleet release resolution has no unbounded GitHub CLI branch"
assert_contains 'verified.*v0\.9\.2-rc3' "fleet workers retain and verify the authenticated release tag"

# Fleet workers inherit the same recovery-attempt policy: a panel already reporting critical
# pressure is upgraded and remains explicitly warned, rather than being stranded by a guessed floor.
: > "$MOCK_CALL_LOG"
LAST_OUTPUT="$TMP/fleet-storage-critical-output.txt"
MOCK_STORAGE_HEALTH=critical MOCK_GITHUB_API=pretty \
  bash "$UPDATE_FLEET" --prerelease -- "$MOCK_TARGET" > "$LAST_OUTPUT" 2>&1
LAST_STATUS=$?
assert_success "fleet update admits a critically storage-constrained panel as a recovery attempt"
assert_contains 'fixed pressure thresholds are advisory.*recovery attempt' "fleet output retains the explicit storage-recovery admission"
assert_contains 'storage health: critical.*replacement completed' "fleet output keeps the remaining critical pressure visible"
assert_contains 'fleet update complete.*1/1 panels OK' "fleet summary counts the admitted recovery replacement as successful"

# The unauthenticated REST fallback receives GitHub's normal pretty multi-line JSON. When the
# candidate is newest, it must bind that candidate tag to that candidate's APK.
: > "$MOCK_CALL_LOG"
LAST_OUTPUT="$TMP/fleet-rest-output.txt"
MOCK_GH_FAIL=1 MOCK_GITHUB_API=pretty bash "$UPDATE_FLEET" --prerelease -- "$MOCK_TARGET" > "$LAST_OUTPUT" 2>&1
LAST_STATUS=$?
assert_success "fleet prerelease REST fallback accepts pretty GitHub JSON"
if grep -Fq 'https://github.com/maxlyth/ha-paneld/releases/download/v0.9.2-rc3/ha-paneld-v0.9.2-rc3-manual-setup-required.apk' "$MOCK_CALL_LOG" && \
   ! grep -Fq 'https://github.com/maxlyth/ha-paneld/releases/download/v0.9.1/ha-paneld-v0.9.1-manual-setup-required.apk' "$MOCK_CALL_LOG"; then
  pass "REST fallback selects the newest candidate and its paired APK"
else
  fail_test "REST fallback selects the newest candidate and its paired APK"
fi
assert_log_contains 'curl .*--proto =https --proto-redir =https .*https://github\.com/maxlyth/ha-paneld/releases/download/v0\.9\.2-rc3/ha-paneld-v0\.9\.2-rc3-manual-setup-required\.apk' "fleet REST APK redirects remain HTTPS"
assert_contains 'verified.*v0\.9\.2-rc3' "fleet REST workers retain and verify the authenticated release tag"

: > "$MOCK_CALL_LOG"
LAST_OUTPUT="$TMP/fleet-stable-newest-output.txt"
MOCK_GITHUB_API=stable_newest bash "$UPDATE_FLEET" --prerelease -- "$MOCK_TARGET" > "$LAST_OUTPUT" 2>&1
LAST_STATUS=$?
assert_success "fleet inclusive channel accepts a newer stable release"
assert_log_contains 'releases/download/v0\.9\.3/ha-paneld-v0\.9\.3-manual-setup-required\.apk' \
  "fleet inclusive channel selects the newer stable asset"
assert_not_contains 'releases/download/v0\.9\.2-rc3/' "$MOCK_CALL_LOG" \
  "fleet keeps the newer stable tag paired with its own asset"
assert_contains 'verified.*v0\.9\.3' "fleet workers retain the newer stable release tag"

: > "$MOCK_CALL_LOG"
LAST_OUTPUT="$TMP/fleet-stable-only-output.txt"
MOCK_GITHUB_API=stable_only bash "$UPDATE_FLEET" --prerelease -- "$MOCK_TARGET" > "$LAST_OUTPUT" 2>&1
LAST_STATUS=$?
assert_success "fleet inclusive channel works after release candidates are deleted"
assert_log_contains 'releases/download/v0\.9\.3/ha-paneld-v0\.9\.3-manual-setup-required\.apk' \
  "fleet inclusive channel falls through to the remaining stable release"

: > "$MOCK_CALL_LOG"
LAST_OUTPUT="$TMP/fleet-oversized-output.txt"
MOCK_GITHUB_API=oversized bash "$UPDATE_FLEET" --prerelease -- "$MOCK_TARGET" > "$LAST_OUTPUT" 2>&1
LAST_STATUS=$?
assert_success "fleet updater consumes an oversized prerelease response without SIGPIPE"
assert_log_contains 'releases/download/v0\.9\.2-rc3/ha-paneld-v0\.9\.2-rc3-manual-setup-required\.apk' \
  "oversized fleet response retains the first prerelease asset"

# A legacy literal secret is unavoidable in this wrapper's original argv, but it must be normalized
# once rather than copied into every fleet worker, provisioner, curl or adb command.
: > "$MOCK_CALL_LOG"
LAST_OUTPUT="$TMP/fleet-secret-output.txt"
FLEET_SECRET_SENTINEL='fleet-mqtt-secret-e6a901'
bash "$UPDATE_FLEET" --apk "$APK" --allow-unsigned-helper --no-tame \
  --mqtt-pass "$FLEET_SECRET_SENTINEL" -- "$MOCK_TARGET" > "$LAST_OUTPUT" 2>&1
LAST_STATUS=$?
assert_success "fleet normalizes a legacy literal credential"
assert_not_contains "$FLEET_SECRET_SENTINEL" "$MOCK_CALL_LOG" "fleet workers do not repeat the credential in descendant argv"
assert_not_contains "$FLEET_SECRET_SENTINEL" "$LAST_OUTPUT" "fleet output does not print the credential"
assert_log_contains 'mqtt_password@.*/mqtt-password' "fleet provisioner uses the normalized private credential file"

# Parallel fleet execution must aggregate a mixed outcome and replay both panel sections.
: > "$MOCK_CALL_LOG"
LAST_OUTPUT="$TMP/fleet-mixed-output.txt"
MOCK_TARGETS='panel-a.test:5555 panel-b.test:5555' MOCK_VERIFY_FAIL_HOST=panel-b.test \
  bash "$UPDATE_FLEET" --apk "$APK" --allow-unsigned-helper --no-tame -- panel-a.test panel-b.test > "$LAST_OUTPUT" 2>&1
LAST_STATUS=$?
assert_failure "parallel fleet reports nonzero for a mixed panel outcome"
assert_contains 'panel-a\.test:5555' "parallel fleet replays the successful panel section"
assert_contains 'panel-b\.test:5555' "parallel fleet replays the failed panel section"
assert_contains '1 OK, 1 failed' "parallel fleet aggregates mixed results"

# The wrapper consumes --jobs itself and does not launch a later batch while the selected worker
# limit is occupied. A blocked first install makes the scheduling boundary directly observable.
: > "$MOCK_CALL_LOG"
FLEET_JOBS_PID_FILE="$TMP/fleet-jobs-install.pid"
FLEET_JOBS_OUTPUT="$TMP/fleet-jobs-output.txt"
MOCK_TARGETS='panel-a.test:5555 panel-b.test:5555' MOCK_APK_INSTALL=block \
  MOCK_APK_INSTALL_PID_FILE="$FLEET_JOBS_PID_FILE" \
  bash "$UPDATE_FLEET" --jobs 1 --apk "$APK" --allow-unsigned-helper --no-tame -- \
    panel-a.test panel-b.test > "$FLEET_JOBS_OUTPUT" 2>&1 &
fleet_jobs_owner_pid=$!
fleet_jobs_ready=0
for _ in {1..100}; do
  if [ -s "$FLEET_JOBS_PID_FILE" ]; then fleet_jobs_ready=1; break; fi
  /bin/sleep 0.05
done
if [ "$fleet_jobs_ready" -eq 1 ]; then
  pass "fleet jobs test reaches the occupied worker slot"
else
  LAST_OUTPUT="$FLEET_JOBS_OUTPUT"
  fail_test "fleet jobs test reaches the occupied worker slot"
fi
assert_not_contains 'adb -s panel-b\.test:5555' "$MOCK_CALL_LOG" "--jobs 1 keeps the second panel queued"
kill -TERM "$fleet_jobs_owner_pid" 2>/dev/null || true
wait "$fleet_jobs_owner_pid" 2>/dev/null || true

LAST_OUTPUT="$TMP/fleet-invalid-jobs-output.txt"
bash "$UPDATE_FLEET" --jobs 0 --apk "$APK" -- "$MOCK_TARGET" > "$LAST_OUTPUT" 2>&1
LAST_STATUS=$?
assert_status 2 "fleet rejects an unbounded zero-worker override"
assert_contains '--jobs must be a whole number from 1 to 32' "invalid fleet concurrency gives its accepted range"

# The fleet wrapper owns every provisioner it launches. TERM must be forwarded through the worker
# wrapper to the blocked package install, then all descendants must be reaped before shared temp/log
# state is removed.
: > "$MOCK_CALL_LOG"
FLEET_BLOCKED_PID_FILE="$TMP/fleet-blocked-install.pid"
FLEET_BLOCKED_OUTPUT="$TMP/fleet-blocked-output.txt"
MOCK_APK_INSTALL=block MOCK_APK_INSTALL_PID_FILE="$FLEET_BLOCKED_PID_FILE" \
  bash "$UPDATE_FLEET" --apk "$APK" --allow-unsigned-helper --no-tame -- "$MOCK_TARGET" > "$FLEET_BLOCKED_OUTPUT" 2>&1 &
fleet_owner_pid=$!
fleet_blocked_ready=0
for _ in {1..100}; do
  if [ -s "$FLEET_BLOCKED_PID_FILE" ]; then fleet_blocked_ready=1; break; fi
  /bin/sleep 0.05
done
if [ "$fleet_blocked_ready" -eq 1 ]; then
  pass "blocked fleet worker exposes its package-install subprocess"
else
  LAST_OUTPUT="$FLEET_BLOCKED_OUTPUT"
  fail_test "blocked fleet worker exposes its package-install subprocess"
fi
kill -TERM "$fleet_owner_pid" 2>/dev/null || true
if wait "$fleet_owner_pid"; then fleet_signal_status=0; else fleet_signal_status=$?; fi
if [ "$fleet_signal_status" -eq 143 ]; then
  pass "TERM exits the fleet wrapper with signal status"
else
  LAST_OUTPUT="$FLEET_BLOCKED_OUTPUT"
  fail_test "TERM exits the fleet wrapper with signal status (got $fleet_signal_status)"
fi
fleet_blocked_pid="$(cat "$FLEET_BLOCKED_PID_FILE" 2>/dev/null || true)"
if [ -n "$fleet_blocked_pid" ] && processes_gone "$fleet_blocked_pid"; then
  pass "TERM reaps blocked fleet provisioning descendants"
else
  fail_test "TERM reaps blocked fleet provisioning descendants"
fi
/bin/sleep 0.2
if [ -n "$fleet_blocked_pid" ] && processes_gone "$fleet_blocked_pid"; then
  pass "fleet interruption leaves no orphan panel mutation"
else
  fail_test "fleet interruption leaves no orphan panel mutation"
fi

if provision_scope_is all shard-fleet-installer; then
# A foreground adb inspection is not one of provision.sh's explicitly tracked subprocesses. Fleet
# ownership must still terminate it through the provisioner's dedicated process group.
: > "$MOCK_CALL_LOG"
FLEET_SHIZUKU_INSPECT_PID_FILE="$TMP/fleet-shizuku-inspect.pid"
FLEET_SHIZUKU_INSPECT_OUTPUT="$TMP/fleet-shizuku-inspect-output.txt"
MOCK_SHIZUKU_INSPECT=block MOCK_SHIZUKU_INSPECT_PID_FILE="$FLEET_SHIZUKU_INSPECT_PID_FILE" \
  bash "$UPDATE_FLEET" --apk "$APK" --allow-unsigned-helper --shizuku --no-tame -- "$MOCK_TARGET" \
    > "$FLEET_SHIZUKU_INSPECT_OUTPUT" 2>&1 &
fleet_owner_pid=$!
fleet_blocked_ready=0
for _ in {1..100}; do
  if [ -s "$FLEET_SHIZUKU_INSPECT_PID_FILE" ]; then fleet_blocked_ready=1; break; fi
  /bin/sleep 0.05
done
if [ "$fleet_blocked_ready" -eq 1 ]; then
  pass "blocked foreground Shizuku inspection exposes its adb process"
else
  LAST_OUTPUT="$FLEET_SHIZUKU_INSPECT_OUTPUT"
  fail_test "blocked foreground Shizuku inspection exposes its adb process"
fi
kill -TERM "$fleet_owner_pid" 2>/dev/null || true
if wait "$fleet_owner_pid"; then fleet_signal_status=0; else fleet_signal_status=$?; fi
if [ "$fleet_signal_status" -eq 143 ]; then
  pass "TERM preserves fleet signal status while foreground adb is blocked"
else
  LAST_OUTPUT="$FLEET_SHIZUKU_INSPECT_OUTPUT"
  fail_test "TERM preserves fleet signal status while foreground adb is blocked (got $fleet_signal_status)"
fi
fleet_blocked_pid="$(cat "$FLEET_SHIZUKU_INSPECT_PID_FILE" 2>/dev/null || true)"
if [ -n "$fleet_blocked_pid" ] && processes_gone "$fleet_blocked_pid"; then
  pass "fleet process-group shutdown reaps untracked foreground adb"
else
  fail_test "fleet process-group shutdown reaps untracked foreground adb"
fi

# Deadline-wrapped commands intentionally run in a nested process group so their local timeout can
# kill every descendant. Fleet cancellation must compose with that ownership and signal the deadline
# supervisor, which in turn terminates and reaps the nested adb group before the fleet wrapper exits.
: > "$MOCK_CALL_LOG"
FLEET_SHIZUKU_INSTALL_PID_FILE="$TMP/fleet-shizuku-install.pid"
FLEET_SHIZUKU_INSTALL_OUTPUT="$TMP/fleet-shizuku-install-output.txt"
MOCK_SHIZUKU_INSTALL=block MOCK_SHIZUKU_INSTALL_PID_FILE="$FLEET_SHIZUKU_INSTALL_PID_FILE" \
SHIZUKU_INSTALL_TIMEOUT_SECONDS=180 ROOT_HELPER_LEASE_GUARD_INTERVAL_SECONDS=0.05 \
  bash "$UPDATE_FLEET" --apk "$APK" --allow-unsigned-helper --shizuku --no-tame -- "$MOCK_TARGET" \
    > "$FLEET_SHIZUKU_INSTALL_OUTPUT" 2>&1 &
fleet_owner_pid=$!
fleet_blocked_ready=0
for _ in {1..100}; do
  if [ -s "$FLEET_SHIZUKU_INSTALL_PID_FILE" ]; then fleet_blocked_ready=1; break; fi
  /bin/sleep 0.05
done
if [ "$fleet_blocked_ready" -eq 1 ]; then
  pass "blocked deadline-wrapped Shizuku install exposes its nested adb process"
else
  LAST_OUTPUT="$FLEET_SHIZUKU_INSTALL_OUTPUT"
  fail_test "blocked deadline-wrapped Shizuku install exposes its nested adb process"
fi
kill -TERM "$fleet_owner_pid" 2>/dev/null || true
if wait "$fleet_owner_pid"; then fleet_signal_status=0; else fleet_signal_status=$?; fi
if [ "$fleet_signal_status" -eq 143 ]; then
  pass "TERM preserves fleet signal status while a nested deadline command is blocked"
else
  LAST_OUTPUT="$FLEET_SHIZUKU_INSTALL_OUTPUT"
  fail_test "TERM preserves fleet signal status for a nested deadline command (got $fleet_signal_status)"
fi
fleet_blocked_pid="$(cat "$FLEET_SHIZUKU_INSTALL_PID_FILE" 2>/dev/null || true)"
if [ -n "$fleet_blocked_pid" ] && processes_gone "$fleet_blocked_pid"; then
  pass "fleet cancellation reaps the deadline wrapper's nested adb process group"
else
  fail_test "fleet cancellation reaps the deadline wrapper's nested adb process group"
fi

: > "$MOCK_CALL_LOG"
LAST_OUTPUT="$TMP/fleet-shizuku-failure-output.txt"
MOCK_SHIZUKU_START=fail bash "$UPDATE_FLEET" --apk "$APK" --allow-unsigned-helper --shizuku --no-tame -- "$MOCK_TARGET" > "$LAST_OUTPUT" 2>&1
LAST_STATUS=$?
assert_failure "fleet update fails when requested Shizuku service setup fails"
assert_contains '0 OK, 1 failed' "fleet summary does not count incomplete Shizuku setup as success"
assert_contains 'service did not start' "fleet output retains the Shizuku recovery reason"

: > "$MOCK_CALL_LOG"
LAST_OUTPUT="$TMP/fleet-shizuku-timeout-output.txt"
MOCK_SHIZUKU_START=hang SHIZUKU_START_TIMEOUT_SECONDS=1 \
  bash "$UPDATE_FLEET" --apk "$APK" --allow-unsigned-helper --shizuku --no-tame -- "$MOCK_TARGET" > "$LAST_OUTPUT" 2>&1
LAST_STATUS=$?
assert_failure "fleet update fails when Shizuku service setup times out"
assert_contains '0 OK, 1 failed' "fleet summary does not count timed-out Shizuku setup as success"
assert_contains 'service start timed out after 1s' "fleet output retains the Shizuku timeout reason"
fi

: > "$MOCK_CALL_LOG"
LAST_OUTPUT="$TMP/fleet-duplicate-output.txt"
bash "$UPDATE_FLEET" --apk "$APK" -- panel.test panel.test:5555 > "$LAST_OUTPUT" 2>&1
LAST_STATUS=$?
assert_status 2 "normalized duplicate fleet targets are rejected"
assert_contains 'duplicate panel target' "duplicate fleet target gives a direct correction"
assert_not_contains '^adb .* install( |$)' "$MOCK_CALL_LOG" "duplicate fleet input fails before any install"

LAST_OUTPUT="$TMP/install-help.txt"
bash "$ROOT/scripts/install.sh" --help > "$LAST_OUTPUT" 2>&1
LAST_STATUS=$?
assert_success "one-line installer exposes help without requiring a panel"
assert_contains 'Usage:.*install\.sh' "one-line installer help shows the supported command"
assert_not_contains 'Disable those recommended vendor apps|TAME=' "$ROOT/scripts/install.sh" "one-line installer has no broad pre-profile taming prompt"

# A release-generated installer must bind the immutable tag to its exact asset name and source commit
# before it asks for a panel or contacts one. This mirrors the release workflow's three substitutions.
BAD_INSTALLER="$TMP/install-bad-release.sh"
sed -e 's/^RELEASE_TAG=""/RELEASE_TAG="v0.9.2-rc3"/' \
    -e 's/^RELEASE_APK_NAME=""/RELEASE_APK_NAME="ha-paneld-v0.9.1-manual-setup-required.apk"/' \
    -e 's/^PROVISION_COMMIT=""/PROVISION_COMMIT="0123456789abcdef0123456789abcdef01234567"/' \
    "$ROOT/scripts/install.sh" > "$BAD_INSTALLER"
LAST_OUTPUT="$TMP/install-bad-release-output.txt"
: > "$MOCK_CALL_LOG"
bash "$BAD_INSTALLER" > "$LAST_OUTPUT" 2>&1
LAST_STATUS=$?
assert_failure "release installer rejects a mismatched injected asset name"
assert_contains 'does not pair its tag with the expected APK asset' "mismatched release installer names the packaging error"
assert_not_contains '^curl |^adb ' "$MOCK_CALL_LOG" "mismatched release installer fails before network or panel access"

RELEASE_INSTALLER="$TMP/install-release.sh"
sed -e 's/^RELEASE_TAG=""/RELEASE_TAG="v0.9.2-rc3"/' \
    -e 's/^RELEASE_APK_NAME=""/RELEASE_APK_NAME="ha-paneld-v0.9.2-rc3-manual-setup-required.apk"/' \
    -e 's/^PROVISION_COMMIT=""/PROVISION_COMMIT="0123456789abcdef0123456789abcdef01234567"/' \
    "$ROOT/scripts/install.sh" > "$RELEASE_INSTALLER"
if bash -n "$RELEASE_INSTALLER" && \
   grep -Fq -- '--proto '\''=https'\'' --proto-redir '\''=https'\''' "$RELEASE_INSTALLER" && \
   grep -Fq 'command -v openssl' "$RELEASE_INSTALLER" && \
   grep -Fq 'It authenticates the release before anything is installed' "$RELEASE_INSTALLER" && \
   grep -Fq -- '--release-tag "$RELEASE_TAG"' "$RELEASE_INSTALLER" && \
   grep -Fq 'PROVISION_URL="$(provision_asset_url "$PROVISION_REF")"' "$RELEASE_INSTALLER" && \
   grep -Fq 'openssl dgst -sha256 -verify "$PROVISION_PUBLIC_KEY"' "$RELEASE_INSTALLER" && \
   grep -Fq 'PROVISION_COMMIT="0123456789abcdef0123456789abcdef01234567"' "$RELEASE_INSTALLER"; then
  pass "generated release installer preserves HTTPS, OpenSSL authentication, release verification, and immutable provisioner source"
else
  fail_test "generated release installer preserves HTTPS, OpenSSL authentication, release verification, and immutable provisioner source"
fi

# Downloaded executable code must be independently authenticated with the release key. These runs
# stop at the no-terminal prompt gate, after authentication but before any panel contact.
LAST_OUTPUT="$TMP/install-release-auth-output.txt"
: > "$MOCK_CALL_LOG"
bash "$RELEASE_INSTALLER" > "$LAST_OUTPUT" 2>&1
LAST_STATUS=$?
assert_failure "release installer without a terminal stops safely after provisioner authentication"
assert_contains 'authenticated v0\.9\.2-rc3 provisioner' "release installer authenticates its provisioner before prompting"
assert_log_contains '^curl .*releases/download/v0\.9\.2-rc3/ha-paneld-provision-v0\.9\.2-rc3\.sh -o ' "release installer downloads the versioned provisioner asset"
assert_log_contains '^curl .*ha-paneld-provision-v0\.9\.2-rc3\.sh\.sha256 -o ' "release installer downloads the provisioner checksum"
assert_log_contains '^curl .*ha-paneld-provision-v0\.9\.2-rc3\.sh\.sha256\.sig -o ' "release installer downloads the provisioner checksum signature"
assert_log_contains '^openssl dgst -sha256 -verify .* -signature .*/provision\.sha256\.sig .*/provision\.sha256$' "release installer verifies the provisioner checksum signature"
assert_log_contains '^openssl dgst -sha256 -r .*/provision\.sh$' "release installer hashes the downloaded provisioner"
assert_not_contains '^adb ' "$MOCK_CALL_LOG" "provisioner authentication completes before panel contact"

LAST_OUTPUT="$TMP/install-release-invalid-signature-output.txt"
: > "$MOCK_CALL_LOG"
MOCK_RELEASE_SIGNATURE_FAIL=1 bash "$RELEASE_INSTALLER" > "$LAST_OUTPUT" 2>&1
LAST_STATUS=$?
assert_failure "release installer rejects an invalid provisioner checksum signature"
assert_contains 'provisioner checksum signature is invalid' "invalid provisioner signature names the authentication failure"
assert_contains 'Nothing was installed, started, or privileged' "invalid provisioner signature states the safe outcome"
assert_not_contains '^openssl dgst -sha256 -r ' "$MOCK_CALL_LOG" "invalid provisioner signature is rejected before hashing or trusting its checksum"
assert_not_contains '^adb ' "$MOCK_CALL_LOG" "invalid provisioner signature stops before panel contact"

LAST_OUTPUT="$TMP/install-release-missing-proof-output.txt"
: > "$MOCK_CALL_LOG"
MOCK_INSTALLER_PROOF_DOWNLOAD=checksum_fail bash "$RELEASE_INSTALLER" > "$LAST_OUTPUT" 2>&1
LAST_STATUS=$?
assert_failure "release installer fails closed when the provisioner checksum asset is missing"
assert_contains 'Could not download the signed provisioner checksum' "missing provisioner proof names the incomplete release"
assert_not_contains '^openssl dgst -sha256 -verify ' "$MOCK_CALL_LOG" "missing provisioner proof is never verified as if it were complete"
assert_not_contains '^adb ' "$MOCK_CALL_LOG" "missing provisioner proof stops before panel contact"

LAST_OUTPUT="$TMP/install-release-malformed-proof-output.txt"
: > "$MOCK_CALL_LOG"
MOCK_INSTALLER_CHECKSUM=malformed bash "$RELEASE_INSTALLER" > "$LAST_OUTPUT" 2>&1
LAST_STATUS=$?
assert_failure "release installer rejects a malformed signed provisioner record"
assert_contains 'signed provisioner checksum record.*is malformed' "malformed provisioner record names the metadata failure"
assert_not_contains '^openssl dgst -sha256 -r ' "$MOCK_CALL_LOG" "malformed provisioner record is rejected before hashing executable bytes"
assert_not_contains '^adb ' "$MOCK_CALL_LOG" "malformed provisioner record stops before panel contact"

LAST_OUTPUT="$TMP/install-release-mismatched-provisioner-output.txt"
: > "$MOCK_CALL_LOG"
MOCK_INSTALLER_CHECKSUM=mismatch bash "$RELEASE_INSTALLER" > "$LAST_OUTPUT" 2>&1
LAST_STATUS=$?
assert_failure "release installer rejects provisioner bytes that do not match the signed checksum"
assert_contains 'provisioner does not match its signed checksum' "provisioner checksum mismatch names the integrity failure"
assert_not_contains '^adb ' "$MOCK_CALL_LOG" "provisioner checksum mismatch stops before panel contact"

# The main-channel bootstrap must remain usable while latest stable is v0.9.2, which predates proof
# assets. Once it resolves v0.9.3 or newer, absence or failure of proof must never trigger downgrade.
LAST_OUTPUT="$TMP/install-legacy-channel-output.txt"
: > "$MOCK_CALL_LOG"
MOCK_INSTALLER_RELEASE_API=legacy bash "$ROOT/scripts/install.sh" > "$LAST_OUTPUT" 2>&1
LAST_STATUS=$?
assert_failure "pre-v0.9.3 channel installer reaches the prompt gate through its compatibility path"
assert_log_contains '^curl .*raw\.githubusercontent\.com/maxlyth/ha-paneld/v0\.9\.2/scripts/provision\.sh -o ' "pre-v0.9.3 channel install retains immutable-tag compatibility"
assert_not_contains 'ha-paneld-provision-v0\.9\.2\.sh\.sha256' "$MOCK_CALL_LOG" "legacy channel compatibility does not expect assets that were never published"
assert_not_contains '^adb ' "$MOCK_CALL_LOG" "legacy channel resolution still occurs before panel contact"

LAST_OUTPUT="$TMP/install-authenticated-channel-output.txt"
: > "$MOCK_CALL_LOG"
MOCK_INSTALLER_RELEASE_API=authenticated bash "$ROOT/scripts/install.sh" > "$LAST_OUTPUT" 2>&1
LAST_STATUS=$?
assert_failure "v0.9.3 channel installer reaches the prompt gate only after authentication"
assert_contains 'authenticated v0\.9\.3 provisioner' "v0.9.3 channel resolution requires the authenticated provisioner asset"
assert_log_contains '^curl .*releases/download/v0\.9\.3/ha-paneld-provision-v0\.9\.3\.sh\.sha256\.sig -o ' "v0.9.3 channel resolution fetches signed proof"
assert_not_contains 'raw\.githubusercontent\.com/.*/v0\.9\.3/scripts/provision\.sh' "$MOCK_CALL_LOG" "v0.9.3 channel resolution cannot downgrade to the legacy provisioner path"
assert_not_contains '^adb ' "$MOCK_CALL_LOG" "authenticated channel resolution completes before panel contact"

# Advanced installer mode is the checkout-free entry point for explicit provisioning. Use a
# downloaded fake provisioner here so the wrapper remains a black box while its exact hand-off can
# be inspected without contacting a panel.
ADVANCED_FIXTURES="$TMP/advanced-installer-fixtures"
mkdir -p "$ADVANCED_FIXTURES"
for fixture in "$FIXTURES"/*; do
  [ "$(basename "$fixture")" = curl ] || ln -s "$fixture" "$ADVANCED_FIXTURES/$(basename "$fixture")"
done
ADVANCED_PROVISION="$TMP/advanced-provision.sh"
cat > "$ADVANCED_PROVISION" <<'EOF'
#!/usr/bin/env bash
printf 'provision-argv' >> "${MOCK_CALL_LOG:?}"
printf ' <%s>' "$@" >> "$MOCK_CALL_LOG"
printf '\n' >> "$MOCK_CALL_LOG"
EOF
chmod +x "$ADVANCED_PROVISION"
cat > "$ADVANCED_FIXTURES/curl" <<'EOF'
#!/usr/bin/env bash
set -u
printf 'curl %s\n' "$*" >> "${MOCK_CALL_LOG:?}"
url=""
output=""
next_output=0
for arg in "$@"; do
  if [ "$next_output" = 1 ]; then output="$arg"; next_output=0; continue; fi
  case "$arg" in
    -o|--output) next_output=1 ;;
    http://*|https://*) url="$arg" ;;
  esac
done
case "$url" in
  https://api.github.com/repos/maxlyth/ha-paneld/releases/latest)
    printf '%s\n' '{"tag_name":"v0.9.3","assets":[{"browser_download_url":"https://github.com/maxlyth/ha-paneld/releases/download/v0.9.3/ha-paneld-v0.9.3-manual-setup-required.apk"}]}'
    ;;
  https://api.github.com/repos/maxlyth/ha-paneld/releases\?per_page=100)
    case "${MOCK_ADVANCED_GITHUB_API:-small}" in
      oversized)
        printf '%s' '[{"url":"https://api.github.com/repos/maxlyth/ha-paneld/releases/204","tag_name":"v0.9.4-rc1","draft":false,"prerelease":true,"assets":[{"browser_download_url":"https://github.com/maxlyth/ha-paneld/releases/download/v0.9.4-rc1/ha-paneld-v0.9.4-rc1-manual-setup-required.apk"}]},{"url":"https://api.github.com/repos/maxlyth/ha-paneld/releases/203","tag_name":"v0.9.3","draft":false,"prerelease":false,"padding":"'
        awk 'BEGIN { for (i = 0; i < 2097152; i++) printf "x" }'
        printf '%s\n' '"}]'
        ;;
      stable_newest)
        printf '%s\n' '[{"url":"https://api.github.com/repos/maxlyth/ha-paneld/releases/205","tag_name":"v0.9.5","draft":false,"prerelease":false,"assets":[{"browser_download_url":"https://github.com/maxlyth/ha-paneld/releases/download/v0.9.5/ha-paneld-v0.9.5-manual-setup-required.apk"}]},{"url":"https://api.github.com/repos/maxlyth/ha-paneld/releases/204","tag_name":"v0.9.4-rc1","draft":false,"prerelease":true,"assets":[{"browser_download_url":"https://github.com/maxlyth/ha-paneld/releases/download/v0.9.4-rc1/ha-paneld-v0.9.4-rc1-manual-setup-required.apk"}]}]'
        ;;
      stable_only)
        printf '%s\n' '[{"url":"https://api.github.com/repos/maxlyth/ha-paneld/releases/205","tag_name":"v0.9.5","draft":false,"prerelease":false,"assets":[{"browser_download_url":"https://github.com/maxlyth/ha-paneld/releases/download/v0.9.5/ha-paneld-v0.9.5-manual-setup-required.apk"}]}]'
        ;;
      *)
        printf '%s\n' '[{"url":"https://api.github.com/repos/maxlyth/ha-paneld/releases/204","tag_name":"v0.9.4-rc1","draft":false,"prerelease":true,"assets":[{"browser_download_url":"https://github.com/maxlyth/ha-paneld/releases/download/v0.9.4-rc1/ha-paneld-v0.9.4-rc1-manual-setup-required.apk"}]}]'
        ;;
    esac
    ;;
  */ha-paneld-provision-v*.sh.sha256.sig)
    printf 'mock signature\n' > "$output"
    ;;
  */ha-paneld-provision-v*.sh.sha256)
    name="$(basename "$url" .sha256)"
    hash="$(/usr/bin/sha256sum "${ADVANCED_PROVISION_SOURCE:?}" | awk '{print $1}')"
    printf '%s  %s\n' "$hash" "$name" > "$output"
    ;;
  */ha-paneld-provision-v*.sh)
    cp "${ADVANCED_PROVISION_SOURCE:?}" "$output"
    ;;
  */ha-paneld-v*-manual-setup-required.apk)
    printf 'release apk\n' > "$output"
    ;;
  *)
    # Once the generated installer has authenticated and exec'd the real provisioner, defer its
    # panel-facing HTTP contract to the normal safe fixture rather than maintaining a second fake.
    exec "$PROVISION_TEST_CURL" "$@"
    ;;
esac
EOF
chmod +x "$ADVANCED_FIXTURES/curl"

run_advanced_installer() {
  LAST_OUTPUT="$TMP/advanced-installer-output.txt"
  : > "$MOCK_CALL_LOG"
  env -u 'BASH_FUNC_curl%%' \
    PATH="$ADVANCED_FIXTURES:/usr/bin:/bin" \
    ADVANCED_PROVISION_SOURCE="$ADVANCED_PROVISION" \
    MOCK_CALL_LOG="$MOCK_CALL_LOG" \
      bash "$RELEASE_INSTALLER" "$@" > "$LAST_OUTPUT" 2>&1
  LAST_STATUS=$?
}

# This is deliberately not the small argv-logging child above. It proves that a release-generated
# installer can authenticate, download and hand off to the actual provisioner while every device and
# HTTP effect remains inside this script's existing mocks.
run_generated_installer_with_real_provisioner() {
  LAST_OUTPUT="$TMP/generated-installer-real-provisioner-output.txt"
  : > "$MOCK_CALL_LOG"
  rm -f "$TMP/write-settings-granted" "$TMP/accessibility-services" "$TMP/accessibility-enabled"
  printf 'previous installed apk\n' > "$TMP/installed-apk"
  env -u 'BASH_FUNC_curl%%' \
    PATH="$ADVANCED_FIXTURES:/usr/bin:/bin" \
    ADVANCED_PROVISION_SOURCE="$PROVISION" \
    MOCK_CURL_DIRECT=1 \
    MOCK_CALL_LOG="$MOCK_CALL_LOG" \
      bash "$RELEASE_INSTALLER" "$@" > "$LAST_OUTPUT" 2>&1
  LAST_STATUS=$?
}

run_moving_advanced_installer() {
  LAST_OUTPUT="$TMP/moving-advanced-installer-output.txt"
  : > "$MOCK_CALL_LOG"
  env -u 'BASH_FUNC_curl%%' \
    PATH="$ADVANCED_FIXTURES:/usr/bin:/bin" \
    ADVANCED_PROVISION_SOURCE="$ADVANCED_PROVISION" \
    MOCK_ADVANCED_GITHUB_API="${MOCK_ADVANCED_GITHUB_API:-small}" \
    MOCK_CALL_LOG="$MOCK_CALL_LOG" \
      bash "$ROOT/scripts/install.sh" "$@" > "$LAST_OUTPUT" 2>&1
  LAST_STATUS=$?
}

if provision_scope_is all shard-fleet-installer; then
run_advanced_installer --provision panel.test --id kitchen --shizuku
assert_failure "checkout-free advanced provisioning refuses a historical guardless provisioner on an existing panel"
assert_contains 'historical script has no database-compatibility gate' \
  "mutating advanced provisioning names the obsolete provisioner boundary"
assert_not_contains 'ha-paneld-v0\.9\.2-rc3-manual-setup-required\.apk -o ' "$MOCK_CALL_LOG" \
  "guardless advanced provisioning refuses before downloading replacement APK bytes"
fi

run_generated_installer_with_real_provisioner --provision panel.test --verify
assert_success "generated installer composes with the real provisioner for read-only verification"
assert_contains 'root helper daemon: running' \
  "generated installer hands helper-aware verification to the real provisioner"
assert_not_contains 'ha-paneld-v0\.9\.2-rc3-manual-setup-required\.apk -o ' "$MOCK_CALL_LOG" \
  "generated installer plus real provisioner does not download an APK for verification"

run_generated_installer_with_real_provisioner --provision panel.test --id kitchen
assert_success "generated installer composes with the real provisioner for a mocked install"
assert_log_contains '^adb -s panel\.test:5555 install ' \
  "generated installer hands the authenticated APK to the real provisioner"
assert_contains 'Detected panel: Test Panel' \
  "real provisioner preserves panel-aware installation through the generated hand-off"

# The dashboard seeds are documented and changelogged through the checkout-free one-liner, so the
# installer's own forwarding is part of the claim, not an implementation detail. An option missing from
# install.sh's value-option allowlist is rejected as an unknown argument before the provisioner is ever
# reached, which no provision.sh-level test can see.
MOCK_NO_INSTALLED_PACKAGE=1 MOCK_PM_PATH=fail run_advanced_installer \
  --provision panel.test --builtin --ha-url https://ha.test --ha-user owner \
  --home-dashboard /panel-dashboard/kitchen --entity-filter on
assert_success "the checkout-free installer accepts the dashboard seeds"
assert_log_contains '^provision-argv .*<--home-dashboard> </panel-dashboard/kitchen>' \
  "the checkout-free installer forwards --home-dashboard with its exact value"
assert_log_contains '^provision-argv .*<--entity-filter> <on>' \
  "the checkout-free installer forwards --entity-filter with its exact value"

run_advanced_installer --provision panel.test --home-dashboard
assert_status 2 "the checkout-free installer rejects --home-dashboard with no value"
assert_contains 'needs a value' "the missing seed value is named as a usage error"

ADVANCED_SECRET_SENTINEL='advanced-ha-token-secret-9b173e'
run_advanced_installer --provision panel.test --ha-url https://ha.test --ha-token "$ADVANCED_SECRET_SENTINEL"
assert_failure "checkout-free installer refuses a guardless provisioner after normalizing a legacy literal credential"
assert_contains 'historical script has no database-compatibility gate' \
  "checkout-free credential path still enforces the database-gate boundary"
assert_not_contains "$ADVANCED_SECRET_SENTINEL" "$MOCK_CALL_LOG" \
  "checkout-free installer does not copy the literal token into descendant argv"

ADVANCED_TOKEN_FILE="$TMP/advanced-token.txt"
printf '%s' 'advanced-file-token' > "$ADVANCED_TOKEN_FILE"; chmod 600 "$ADVANCED_TOKEN_FILE"
run_advanced_installer --provision panel.test --ha-token duplicate-literal --ha-token-file "$ADVANCED_TOKEN_FILE"
assert_status 2 "checkout-free installer rejects duplicate credential sources"
assert_contains 'credential source supplied more than once' "checkout-free duplicate names the ambiguous credential source"
assert_not_contains '^curl ' "$MOCK_CALL_LOG" "checkout-free duplicate stops before downloads or provisioner launch"

run_advanced_installer --provision panel.test:5556 --verify
assert_success "checkout-free verify succeeds without downloading an APK"
assert_log_contains '^provision-argv <panel\.test:5556> <--verify>$' \
  "checkout-free verify forwards only the read-only operation"
assert_not_contains 'ha-paneld-v0\.9\.2-rc3-manual-setup-required\.apk -o ' "$MOCK_CALL_LOG" \
  "checkout-free verify does not download or install an APK"
assert_log_contains 'ha-paneld-provision-v0\.9\.2-rc3\.sh\.sha256\.sig -o ' \
  "checkout-free verify still authenticates its downloaded provisioner"

run_advanced_installer --provision panel.test --export panel-backup.json
assert_success "checkout-free export-only succeeds without downloading an APK"
assert_log_contains '^provision-argv <panel\.test:5555> <--export> <panel-backup\.json>$' \
  "checkout-free export-only forwards the requested backup"
assert_not_contains 'ha-paneld-v0\.9\.2-rc3-manual-setup-required\.apk -o ' "$MOCK_CALL_LOG" \
  "checkout-free export-only does not download or install an APK"

for blocked in --apk --release-tag --latest --prerelease; do
  run_advanced_installer --provision panel.test "$blocked" value
  assert_status 2 "checkout-free provisioning rejects the $blocked source override"
  assert_not_contains '^curl ' "$MOCK_CALL_LOG" "rejected $blocked override stops before downloads"
done
run_advanced_installer --provision panel.test --unknown
assert_status 2 "checkout-free provisioning rejects unknown options"
assert_not_contains '^curl ' "$MOCK_CALL_LOG" "unknown advanced option stops before downloads"
run_advanced_installer --provision panel.test --verify --id kitchen
assert_status 2 "checkout-free verification rejects ignored mutating options"
assert_not_contains '^curl ' "$MOCK_CALL_LOG" "mixed read-only and mutating options stop before downloads"

# The user-facing command downloads the moving main installer rather than a release-generated copy.
# Exercise both channel resolvers through the complete advanced hand-off so their authenticated
# provisioner and APK selection cannot drift from the already-covered immutable-release path.
run_moving_advanced_installer --provision panel.test --id kitchen
assert_failure "moving stable-channel advanced provisioning refuses a guardless provisioner on an existing panel"
assert_log_contains '^curl .*api\.github\.com/repos/maxlyth/ha-paneld/releases/latest' \
  "moving stable-channel provisioning resolves the latest release"
assert_contains 'historical script has no database-compatibility gate' \
  "moving stable-channel provisioning refuses the obsolete provisioner before replacement"

if provision_scope_is all shard-fleet-installer; then
run_moving_advanced_installer --prerelease --provision panel.test --shizuku
assert_failure "moving prerelease-channel advanced provisioning refuses a guardless provisioner on an existing panel"
assert_log_contains '^curl .*api\.github\.com/repos/maxlyth/ha-paneld/releases\?per_page=100' \
  "moving prerelease provisioning resolves the release-candidate channel"
assert_contains 'historical script has no database-compatibility gate' \
  "moving prerelease provisioning refuses the obsolete provisioner before replacement"

MOCK_ADVANCED_GITHUB_API=oversized run_moving_advanced_installer --prerelease --provision panel.test --shizuku
assert_failure "moving installer consumes an oversized prerelease response then refuses its guardless provisioner"
assert_log_contains '^curl .*releases/download/v0\.9\.4-rc1/ha-paneld-provision-v0\.9\.4-rc1\.sh -o ' \
  "oversized moving-installer response retains the first prerelease provisioner"
assert_contains 'historical script has no database-compatibility gate' \
  "oversized moving-installer response retains fail-closed provisioner policy"
assert_not_contains '^provision-argv' "$MOCK_CALL_LOG" \
  "oversized moving-installer response never executes the guardless provisioner"

MOCK_ADVANCED_GITHUB_API=stable_newest run_moving_advanced_installer --prerelease --provision panel.test --shizuku
assert_failure "moving installer inclusive channel authenticates then refuses a newer guardless stable provisioner"
assert_log_contains '^curl .*releases/download/v0\.9\.5/ha-paneld-provision-v0\.9\.5\.sh -o ' \
  "moving installer pairs the newer stable tag and provisioner"
assert_log_contains '^curl .*releases/download/v0\.9\.5/ha-paneld-provision-v0\.9\.5\.sh\.sha256\.sig -o ' \
  "moving installer authenticates the newer stable provisioner"
assert_contains 'historical script has no database-compatibility gate' \
  "moving installer refuses the newer stable guardless provisioner"
assert_not_contains '^provision-argv' "$MOCK_CALL_LOG" \
  "moving installer does not execute the newer stable guardless provisioner"

MOCK_ADVANCED_GITHUB_API=stable_only run_moving_advanced_installer --prerelease --provision panel.test --shizuku
assert_failure "moving installer inclusive channel authenticates then refuses the remaining guardless stable provisioner"
assert_log_contains '^curl .*releases/download/v0\.9\.5/ha-paneld-provision-v0\.9\.5\.sh -o ' \
  "moving installer selects the remaining stable provisioner"
assert_log_contains '^curl .*releases/download/v0\.9\.5/ha-paneld-provision-v0\.9\.5\.sh\.sha256\.sig -o ' \
  "moving installer authenticates the remaining stable provisioner"
assert_contains 'historical script has no database-compatibility gate' \
  "moving installer refuses the remaining stable guardless provisioner"
assert_not_contains '^provision-argv' "$MOCK_CALL_LOG" \
  "moving installer does not execute the remaining stable guardless provisioner"
fi

run_moving_advanced_installer --provision panel.test --verify
assert_success "moving stable-channel verification succeeds without an APK"
assert_log_contains '^provision-argv <panel\.test:5555> <--verify>$' \
  "moving stable-channel verification forwards only the read-only operation"
assert_not_contains 'ha-paneld-v0\.9\.3-manual-setup-required\.apk -o ' "$MOCK_CALL_LOG" \
  "moving stable-channel verification does not download an APK"

run_moving_advanced_installer --prerelease --provision panel.test --export panel-backup.json
assert_success "moving prerelease-channel export succeeds without an APK"
assert_log_contains '^provision-argv <panel\.test:5555> <--export> <panel-backup\.json>$' \
  "moving prerelease-channel export forwards only the backup operation"
assert_not_contains 'ha-paneld-v0\.9\.4-rc1-manual-setup-required\.apk -o ' "$MOCK_CALL_LOG" \
  "moving prerelease-channel export does not download an APK"

LAST_OUTPUT="$TMP/provision-help.txt"
bash "$PROVISION" --help > "$LAST_OUTPUT" 2>&1
LAST_STATUS=$?
assert_success "provisioner exposes help without requiring a panel"
if provision_scope_is all shard-fleet-installer; then
  assert_contains '^ *--shizuku +Install/start pinned Shizuku' "provisioner help advertises enhanced-access setup"
fi

RELEASE_WORKFLOW="$ROOT/.github/workflows/release.yml"
CI_WORKFLOW="$ROOT/.github/workflows/ci.yml"
SHIZUKU_WORKFLOW="$ROOT/.github/workflows/shizuku-emulator.yml"
LAST_OUTPUT="$TMP/release-workflow-contract.txt"
cp "$RELEASE_WORKFLOW" "$LAST_OUTPUT"
if grep -Fq 'PROVISION_TEST_SCOPE: core' "$CI_WORKFLOW" && \
   ! grep -Fq 'scripts/tests/provision_test.sh' "$RELEASE_WORKFLOW" && \
   grep -Fq 'PROVISION_TEST_SCOPE=all scripts/tests/provision_test.sh' "$SHIZUKU_WORKFLOW"; then
  pass "Shizuku provisioning contracts stay outside CI and release gates"
else
  fail_test "Shizuku provisioning contracts stay outside CI and release gates"
fi
if grep -Fq 'release-input/hapaneld-helper-armeabi-v7a' "$RELEASE_WORKFLOW" && \
   grep -Fq 'release-input/hapaneld-helper-arm64-v8a' "$RELEASE_WORKFLOW"; then
  pass "release workflow builds both supported helper ABIs"
else
  fail_test "release workflow builds both supported helper ABIs"
fi
if grep -Fq 'armv7a-linux-androideabi26-clang' "$RELEASE_WORKFLOW" && \
   grep -Fq 'aarch64-linux-android26-clang' "$RELEASE_WORKFLOW" && \
   ! grep -Fq 'android27-clang' "$RELEASE_WORKFLOW"; then
  pass "release workflow aligns both helper assets with the app API 26 floor"
else
  fail_test "release workflow aligns both helper assets with the app API 26 floor"
fi
if grep -Fq 'name: Privileged helper' "$CI_WORKFLOW" && \
   grep -Fq '"Privileged helper"' "$RELEASE_WORKFLOW" && \
   ! grep -Fq 'make -C helper clean test contract' "$RELEASE_WORKFLOW"; then
  pass "release tags require the exact-source privileged-helper gate before publication"
else
  fail_test "release tags require the exact-source privileged-helper gate before publication"
fi
if grep -Fq 'fetch-depth: 0' "$RELEASE_WORKFLOW" && \
   grep -Fq "'+refs/heads/main:refs/remotes/origin/main'" "$RELEASE_WORKFLOW" && \
   grep -Fq 'git merge-base --is-ancestor "$source_commit" refs/remotes/origin/main' "$RELEASE_WORKFLOW" && \
   grep -Fq 'if [ "$GITHUB_EVENT_NAME" = push ]; then' "$RELEASE_WORKFLOW"; then
  pass "release tags must resolve to commits already merged on origin main"
else
  fail_test "release tags must resolve to commits already merged on origin main"
fi
snapshot_line="$(grep -n 'old_bin_record=.*snapshot /system/bin/hapaneld-helper ' "$PROVISION" | head -1 | cut -d: -f1)"
marker_line="$(grep -n '} > "\$marker.new"' "$PROVISION" | head -1 | cut -d: -f1)"
pre_marker_sync_line="$(awk -v after="$marker_line" 'NR > after && /sync \|\| /{print NR; exit}' "$PROVISION")"
marker_move_line="$(grep -n 'mv -f "\$marker.new" "\$marker"' "$PROVISION" | head -1 | cut -d: -f1)"
post_marker_sync_line="$(awk -v after="$marker_move_line" 'NR > after && /sync \|\| /{print NR; exit}' "$PROVISION")"
retire_alt_line="$(awk -v after="$post_marker_sync_line" \
  'NR > after && /\/data\/adb\/hapaneld\/hapaneld-helper \/data\/adb\/service.d\/hapaneld-helper.sh/{print NR; exit}' \
  "$PROVISION")"
if [ -n "$snapshot_line" ] && [ -n "$marker_line" ] && [ -n "$retire_alt_line" ] && \
   [ -n "$pre_marker_sync_line" ] && [ -n "$marker_move_line" ] && [ -n "$post_marker_sync_line" ] && \
   [ "$snapshot_line" -lt "$marker_line" ] && [ "$marker_line" -lt "$pre_marker_sync_line" ] && \
   [ "$pre_marker_sync_line" -lt "$marker_move_line" ] && [ "$marker_move_line" -lt "$post_marker_sync_line" ] && \
   [ "$post_marker_sync_line" -lt "$retire_alt_line" ] && \
   grep -Fq 'root_owned "$recovery"' "$PROVISION" && \
   grep -Fq 'SYS_BIN_SHA256=$old_bin_sha' "$PROVISION" && \
   grep -Fq '[ "$(file_sha256 "$recovery")" = "$expected" ]' "$PROVISION"; then
  pass "system migration durably verifies hashed root-owned recovery before journaling and retirement"
else
  fail_test "system migration durably verifies hashed root-owned recovery before journaling and retirement"
fi
if grep -Fq 'echo JOURNAL_VERSION=2' "$PROVISION" && \
   grep -Fq 'echo JOURNAL_SCOPE=APK_HELPER' "$PROVISION" && \
   grep -Fq 'echo BOOT_KIND=system' "$PROVISION" && \
   grep -Fq 'echo BOOT_KIND=systemless' "$PROVISION" && \
   grep -Fq 'echo BOOT_KIND=hybrid' "$PROVISION" && \
   grep -Fq 'echo TRANSACTION_ID=@TRANSACTION_ID@' "$PROVISION" && \
   grep -Fq 'echo LEASE_BOOT_ID=$current_boot' "$PROVISION" && \
   grep -Fq 'echo LEASE_UNTIL_UPTIME=$lease_until' "$PROVISION" && \
   grep -Fq 'echo TARGET_HELPER_SHA256=@BIN_SHA256@' "$PROVISION" && \
   grep -Fq '[ ! -e /data/local/.hapaneld-helper-manual-upgrade ]' "$PROVISION" && \
   grep -Fq '[ ! -L /data/local/.hapaneld-helper-manual-upgrade ]' "$PROVISION" && \
   grep -Fq '[ ! -e /system/bin/.hapaneld-helper-manual-upgrade ]' "$PROVISION" && \
   grep -Fq '[ ! -L /system/bin/.hapaneld-helper-manual-upgrade ]' "$PROVISION" && \
   grep -Fq '[ ! -e /data/adb/hapaneld/.helper-manual-upgrade.marker ]' "$PROVISION" && \
   grep -Fq '[ ! -L /data/adb/hapaneld/.helper-manual-upgrade.marker ]' "$PROVISION" && \
   grep -Fq 'incomplete standalone root-helper installation must be recovered first' "$PROVISION"; then
  pass "provisioning uses a layout-bound v2 APK-coupled journal and rejects the separate standalone journal"
else
  fail_test "provisioning uses a layout-bound v2 APK-coupled journal and rejects the separate standalone journal"
fi
if grep -Fq 'case "$journal_version" in 1|2)' "$PROVISION" && \
   grep -Fq 'system) helper_path=/system/bin/hapaneld-helper' "$PROVISION" && \
   grep -Fq 'systemless|hybrid) helper_path=/data/adb/hapaneld/hapaneld-helper' "$PROVISION" && \
   grep -Fq 'if [ "$journal_version" = 2 ]; then' "$PROVISION"; then
  pass "stale v1 journals retain legacy-path recovery while v2 recovery proves the Guard contract"
else
  fail_test "stale v1 journals retain legacy-path recovery while v2 recovery proves the Guard contract"
fi
if grep -Fq 'valid_transaction_identity "$marker" "$transaction_id" "$target_apk" "$target_build" "$target_helper"' "$PROVISION" && \
   grep -Fq 'ACTIVE_SYSTEM_TRANSACTION' "$PROVISION" && \
   grep -Fq 'ACTIVE_SYSTEMLESS_TRANSACTION' "$PROVISION" && \
   grep -Fq 'ACTIVE_HYBRID_TRANSACTION' "$PROVISION" && \
   grep -Fq 'renew_root_helper_lease "$install_kind"' "$PROVISION" && \
   grep -Fq 'start_root_helper_lease_guard' "$PROVISION"; then
  pass "transaction nonce and monotonic lease protect validation through APK install and matching commit"
else
  fail_test "transaction nonce and monotonic lease protect validation through APK install and matching commit"
fi

managed_case_line="$(grep -n '^  case "$install_kind" in$' "$PROVISION" | tail -1 | cut -d: -f1)"
managed_launch_body="$(tail -n "+$managed_case_line" "$PROVISION" | sed -n '1,/^  if ! wait_for_helper_reply PING OK /p')"
if [ "$(grep -Fxc '    /data/local/hapaneld-helper --supervise >/dev/null 2>&1 &' <<<"$managed_launch_body" || true)" = 1 ] && \
   ! grep -Eq '/system/bin/hapaneld-helper --supervise|/data/adb/hapaneld/hapaneld-helper --supervise' <<<"$managed_launch_body" && \
   ! grep -Fq 'run_root '\''/data/local/hapaneld-helper --supervise' <<<"$managed_launch_body"; then
  pass "all managed layouts share one canonical supervised launch without retry"
else
  LAST_OUTPUT="$PROVISION"
  fail_test "all managed layouts share one canonical supervised launch without retry"
fi

fi

if provision_scope_is core all shard-fleet-installer; then
# Pins the invariant rather than one spelling of it: retirement is attempted exactly once, it ends the
# function when it fails, and it happens before the first line that replaces anything. The gate also
# has to name the panel as unchanged, because the host uses that to skip a rollback of nothing and to
# avoid telling an owner to repair a helper that is still the one they had.
assert_install_retirement_before_swap() {
  local function_name="$1" body gate swap gates
  body="$(sed -n "/^${function_name}() {$/,/^}$/p" "$PROVISION")"
  gates="$(grep -c 'retire_helpers ||' <<<"$body" || true)"
  gate="$(grep -n 'retire_helpers ||' <<<"$body" | head -1 | cut -d: -f1)"
  swap="$(grep -nE '^  (rm -f|mv -f) ' <<<"$body" | tail -1 | cut -d: -f1)"
  if [ "$gates" = 1 ] && [ -n "$gate" ] && [ -n "$swap" ] && [ "$gate" -lt "$swap" ] &&
     grep -Fq "INSTALL_UNCHANGED ${function_name} helper_retirement" <<<"$body"; then
    pass "$function_name retires the old daemon before the swap and names an untouched panel"
  else
    fail_test "$function_name retires the old daemon before the swap and names an untouched panel"
  fi
}
assert_install_retirement_before_swap install_system
assert_install_retirement_before_swap install_systemless
assert_install_retirement_before_swap install_hybrid

# The retirement helper must escalate rather than poll one signal forever, and a timeout must
# emit machine-readable evidence naming the surviving pids and Android init's view of both
# services, because Issue #120 was undiagnosable from "install failed" alone.
retire_body="$(sed -n '/^retire_helpers() {$/,/^}$/p' "$PROVISION")"
if grep -Fq 'pkill -x hapaneld-helper 2>/dev/null' <<<"$retire_body" && \
   grep -Fq 'pkill -KILL -x hapaneld-helper 2>/dev/null' <<<"$retire_body" && \
   grep -Fq 'rh_attempt" -ge 2' <<<"$retire_body" && \
   grep -Fq 'rh_attempt" -ge 4' <<<"$retire_body" && \
   grep -Fq 'RETIREMENT_TIMEOUT helper_pids=' <<<"$retire_body" && \
   grep -Fq 'init_helper=' <<<"$retire_body" && \
   grep -Fq 'getprop init.svc.hapaneld_helper' <<<"$retire_body"; then
  pass "retire_helpers escalates to SIGKILL and reports pids plus init state on timeout"
else
  fail_test "retire_helpers escalates to SIGKILL and reports pids plus init state on timeout"
fi

# Execute the extracted retirement function offline against stubbed panel commands, so the
# escalation order and the timeout diagnostic are proven behaviour rather than source shape.
RETIRE_STUBS="$TMP/retire-stubs"; RETIRE_LOG="$TMP/retire-calls.log"
mkdir -p "$RETIRE_STUBS"
cat > "$RETIRE_STUBS/stop" <<'STUB'
#!/usr/bin/env bash
echo "stop $*" >> "$RETIRE_LOG"
STUB
cat > "$RETIRE_STUBS/pkill" <<'STUB'
#!/usr/bin/env bash
echo "pkill $*" >> "$RETIRE_LOG"
case " $* " in *" -KILL "*) [ "${RETIRE_SCENARIO:?}" = kill_needed ] && : > "$RETIRE_LOG.killed" ;; esac
exit 0
STUB
cat > "$RETIRE_STUBS/pidof" <<'STUB'
#!/usr/bin/env bash
echo "pidof $*" >> "$RETIRE_LOG"
case "${RETIRE_SCENARIO:?}:$1" in
  ledd_stuck:hapaneld-ledd) echo 4243; exit 0 ;;
  ledd_stuck:*) exit 1 ;;
esac
[ "$1" = hapaneld-helper ] || exit 1
case "${RETIRE_SCENARIO:?}" in
  normal) exit 1 ;;
  kill_needed) [ -e "$RETIRE_LOG.killed" ] && exit 1; echo 4242; exit 0 ;;
  stuck_init|no_init_service) echo 4242; exit 0 ;;
esac
STUB
cat > "$RETIRE_STUBS/getprop" <<'STUB'
#!/usr/bin/env bash
echo "getprop $*" >> "$RETIRE_LOG"
# A helper that was launched directly and has never been through a reboot has no init.svc property at
# all. That is an ordinary state on a rooted panel rather than an exotic one, so the diagnostic has to
# describe it instead of assuming a registered service.
[ "${RETIRE_SCENARIO:?}" = no_init_service ] && exit 0
[ "$1" = init.svc.hapaneld_helper ] && echo running
STUB
cat > "$RETIRE_STUBS/sleep" <<'STUB'
#!/usr/bin/env bash
echo "sleep $*" >> "$RETIRE_LOG"
STUB
chmod +x "$RETIRE_STUBS"/*
sed -n '/^retire_helpers() {$/,/^}$/p' "$PROVISION" > "$TMP/retire-fn.sh"

run_retire_scenario() {
  : > "$RETIRE_LOG"; rm -f "$RETIRE_LOG.killed"
  RETIRE_SCENARIO="$1" RETIRE_LOG="$RETIRE_LOG" PATH="$RETIRE_STUBS:$PATH" \
    bash -c ". '$TMP/retire-fn.sh'; retire_helpers" > "$TMP/retire-out.txt" 2>&1
}

if run_retire_scenario normal && ! grep -q 'pkill -KILL' "$RETIRE_LOG" && [ ! -s "$TMP/retire-out.txt" ]; then
  pass "a cleanly retired helper needs no SIGKILL and emits no diagnostic"
else
  fail_test "a cleanly retired helper needs no SIGKILL and emits no diagnostic"
fi

if run_retire_scenario kill_needed && grep -q 'pkill -KILL -x hapaneld-helper' "$RETIRE_LOG" && \
   [ "$(grep -n 'pkill -x hapaneld-helper' "$RETIRE_LOG" | head -1 | cut -d: -f1)" -lt \
     "$(grep -n 'pkill -KILL -x hapaneld-helper' "$RETIRE_LOG" | head -1 | cut -d: -f1)" ] && \
   ! grep -q RETIREMENT_TIMEOUT "$TMP/retire-out.txt"; then
  pass "a helper that ignores SIGTERM is retired by escalation to SIGKILL"
else
  fail_test "a helper that ignores SIGTERM is retired by escalation to SIGKILL"
fi

if ! run_retire_scenario stuck_init && \
   grep -q '^RETIREMENT_TIMEOUT helper_pids=4242 ledd_pids=none init_helper=running' "$TMP/retire-out.txt" && \
   [ "$(grep -c '^stop hapaneld_helper' "$RETIRE_LOG")" -ge 2 ] && \
   grep -q 'pkill -KILL -x hapaneld-helper' "$RETIRE_LOG"; then
  pass "an unkillable helper times out with surviving pids and init state in the diagnostic"
else
  fail_test "an unkillable helper times out with surviving pids and init state in the diagnostic"
fi

# The signature the Issue #120 panel actually had: a live helper with no registered init service. The
# diagnostic must say `unset` rather than inventing a state, because that word is what tells the next
# reader that `stop` was never going to work on this panel.
if ! run_retire_scenario no_init_service && \
   grep -q '^RETIREMENT_TIMEOUT helper_pids=4242 ledd_pids=none init_helper=unset init_ledd=unset$' "$TMP/retire-out.txt"; then
  pass "a helper with no registered init service reports init_helper=unset rather than a fabricated state"
else
  fail_test "a helper with no registered init service reports init_helper=unset rather than a fabricated state"
fi

# The ledd half of the retirement has never been exercised by any test. It is legacy, but it is still
# stopped, killed and reported on every rooted panel, so it needs one test that fails if it is quietly
# dropped.
if ! run_retire_scenario ledd_stuck && \
   grep -q '^RETIREMENT_TIMEOUT helper_pids=none ledd_pids=4243 ' "$TMP/retire-out.txt" && \
   grep -q 'pkill -KILL -x hapaneld-ledd' "$RETIRE_LOG"; then
  pass "a surviving LED daemon times out, is escalated to SIGKILL, and is named in the diagnostic"
else
  fail_test "a surviving LED daemon times out, is escalated to SIGKILL, and is named in the diagnostic"
fi

# ── Issue #120: the constrained /system replacement, executed rather than pattern-matched ────────
#
# The source-shape checks above cannot tell whether a refusal reason actually fires for its own
# condition. These run the real functions out of the transaction script against real directories,
# using a genuinely read-only mount that every Linux host has, so each reason is proven to fire for
# the condition it names and for no other.
PREFLIGHT_FN="$TMP/preflight-fn.sh"
PREFLIGHT_ID=b7f3c1d4e5a6908172635445362718b9
: > "$PREFLIGHT_FN"
preflight_extract_failed=""
for preflight_function in file_sha256 hash_matches file_bytes sum_bytes diag_reset describe_target \
    emit_target_diag emit_source_diag write_probe preflight_target preflight_source copy_staged; do
  preflight_body="$(sed -n "/^${preflight_function}() {$/,/^}$/p" "$PROVISION")"
  # Absence is not evidence: an extraction that silently produced nothing would let every assertion
  # below pass against an empty shell, so a missing or renamed function fails here and loudly.
  if [ -z "$preflight_body" ]; then
    preflight_extract_failed="$preflight_extract_failed $preflight_function"
    continue
  fi
  printf '%s\n' "$preflight_body" >> "$PREFLIGHT_FN"
done
sed -i "s/@TRANSACTION_ID@/$PREFLIGHT_ID/g" "$PREFLIGHT_FN"
if [ -z "$preflight_extract_failed" ]; then
  pass "every preflight and copy function is present in the transaction script"
else
  fail_test "every preflight and copy function is present in the transaction script"
fi

# Deliberately NOT the suite's mock PATH. The sha256sum fixture answers 64 zeros for any path it
# does not recognise, so running these functions under it would compare every digest against one
# constant and call the result authentication. Real df, real dd, real cp, real sha256sum.
run_preflight() {
  PATH=/usr/bin:/bin /bin/sh -c ". '$PREFLIGHT_FN'
$1" 2>&1
}

PREFLIGHT_DIR="$TMP/preflight"
mkdir -p "$PREFLIGHT_DIR/target"
printf 'staged helper bytes\n' > "$PREFLIGHT_DIR/staged"
PREFLIGHT_SHA="$(/usr/bin/sha256sum "$PREFLIGHT_DIR/staged" | cut -d' ' -f1)"
PREFLIGHT_WRONG_SHA="$(printf 'not the staged file\n' | /usr/bin/sha256sum | cut -d' ' -f1)"

# A destination that can take the write says nothing at all. Silence is the contract: any output on
# the success path would be parsed as a marker by the host.
preflight_out="$(run_preflight "preflight_target install_system '$PREFLIGHT_DIR/target' 1024")"
if [ -z "$preflight_out" ] && run_preflight "preflight_target install_system '$PREFLIGHT_DIR/target' 1024" >/dev/null; then
  pass "a writable destination with headroom passes the preflight silently"
else
  fail_test "a writable destination with headroom passes the preflight silently"
fi

# The probe must leave nothing behind, or the very check for a full partition would consume the
# last of it.
if [ -z "$(ls -A "$PREFLIGHT_DIR/target")" ]; then
  pass "the write probe removes its own file"
else
  fail_test "the write probe removes its own file"
fi

# A create that succeeds while writing nothing is exactly the probe this replaces: a zero-byte file
# lands in an inode and never reaches the block allocator, so it cannot fail on a full partition.
# The probe therefore has to check how many bytes actually arrived, and this proves that check is
# load-bearing rather than decorative.
preflight_out="$(run_preflight "dd() { : > \"\${2#of=}\"; }
write_probe '$PREFLIGHT_DIR/target' && echo PROBE_PASSED || echo PROBE_REFUSED")"
if printf '%s' "$preflight_out" 2>/dev/null | grep -Fqx PROBE_REFUSED; then
  pass "a write that creates the file but stores no bytes does not pass the probe"
else
  fail_test "a write that creates the file but stores no bytes does not pass the probe"
fi

# And the control: the same stub writing the full block passes, so the assertion above is failing on
# the byte count rather than on the stub merely existing.
preflight_out="$(run_preflight "dd() { /bin/dd if=/dev/zero \"\$2\" bs=4096 count=1 2>/dev/null; }
write_probe '$PREFLIGHT_DIR/target' && echo PROBE_PASSED || echo PROBE_REFUSED")"
if printf '%s' "$preflight_out" 2>/dev/null | grep -Fqx PROBE_PASSED; then
  pass "a write that stores the whole block passes the probe"
else
  fail_test "a write that stores the whole block passes the probe"
fi

preflight_out="$(run_preflight "preflight_target install_system '$PREFLIGHT_DIR/absent' 1024")"
if printf '%s' "$preflight_out" 2>/dev/null | grep -Fqx 'INSTALL_UNCHANGED install_system target_directory_missing'; then
  pass "a destination directory that does not exist refuses with its own reason"
else
  fail_test "a destination directory that does not exist refuses with its own reason"
fi

# Headroom is checked against what the transaction will actually write, so a requirement larger than
# the filesystem refuses on capacity rather than reaching the copy and failing there.
preflight_out="$(run_preflight "preflight_target install_system '$PREFLIGHT_DIR/target' 999999999999999")"
if printf '%s' "$preflight_out" 2>/dev/null | grep -Fqx 'INSTALL_UNCHANGED install_system target_insufficient_space' &&
   printf '%s' "$preflight_out" 2>/dev/null | grep -Eq '^INSTALL_DIAG install_system target dir=.* availkb=[0-9]+ '; then
  pass "a destination without headroom refuses on capacity and reports what it measured"
else
  fail_test "a destination without headroom refuses on capacity and reports what it measured"
fi

# A read-only directory small enough to stand in for a constrained partition. /sys/firmware is
# one on most hosts, but not on every builder, so mount a private one when it is absent and the
# caller can. This is the branch that the old zero-byte probe at the wrong directory could not
# reach, and it should not go untested wherever /sys/firmware happens not to be its own mount.
READONLY_TARGET=""
READONLY_TARGET_MOUNTED=0
if [ -d /sys/firmware ] && grep -Eq ' /sys/firmware [a-z0-9]+ ro[, ]' /proc/mounts; then
  READONLY_TARGET=/sys/firmware
elif [ "$(id -u)" = 0 ]; then
  READONLY_TARGET="$TMP/readonly-target"
  mkdir -p "$READONLY_TARGET"
  if mount -t tmpfs -o ro,size=4k,nr_inodes=1 tmpfs "$READONLY_TARGET" 2>/dev/null; then
    READONLY_TARGET_MOUNTED=1
  else
    READONLY_TARGET=""
  fi
fi
if [ -n "$READONLY_TARGET" ]; then
  preflight_out="$(run_preflight "preflight_target install_system $READONLY_TARGET 64")"
  if printf '%s' "$preflight_out" 2>/dev/null | grep -Fqx 'INSTALL_UNCHANGED install_system target_read_only' &&
     printf '%s' "$preflight_out" 2>/dev/null | grep -Eq "^INSTALL_DIAG install_system target dir=$READONLY_TARGET .* state=ro "; then
    pass "a read-only destination refuses as read-only rather than as a failed write"
  else
    fail_test "a read-only destination refuses as read-only rather than as a failed write"
  fi

  # And the same directory through the copy path: cp's own errno must survive to the caller.
  preflight_out="$(run_preflight "copy_staged install_system cp_hapaneld-helper_new '$PREFLIGHT_DIR/staged' $READONLY_TARGET/hapaneld-helper.new $PREFLIGHT_SHA")"
  if printf '%s' "$preflight_out" 2>/dev/null | grep -Fqx 'INSTALL_STEP_FAILED install_system cp_hapaneld-helper_new' &&
     printf '%s' "$preflight_out" 2>/dev/null | grep -Eq '^INSTALL_DIAG install_system cp_hapaneld-helper_new errno=.+' &&
     printf '%s' "$preflight_out" 2>/dev/null | grep -Eq '^INSTALL_DIAG install_system cp_hapaneld-helper_new source=.* state=verified '; then
    pass "a refused copy reports the errno, the staged file's authenticity and the destination"
  else
    fail_test "a refused copy reports the errno, the staged file's authenticity and the destination"
  fi
  [ "$READONLY_TARGET_MOUNTED" -eq 0 ] || umount "$READONLY_TARGET" 2>/dev/null || true
else
  fail_test "the read-only mount this fixture needs is present"
fi

# Exhausted inodes cannot be induced without mount privileges, so the panel's own reporting tool is
# replaced instead. The assertion is about the classifier's ordering and wording, not about kernel
# behaviour: a partition with blocks free and no inodes free must not be called full.
preflight_out="$(run_preflight "df() {
  case \" \$* \" in
    *' -i '*) printf '%s\n' 'Filesystem Inodes IUsed IFree IUse% Mounted on'
              printf '%s\n' \"tmpfs 65536 65536 0 100% $PREFLIGHT_DIR/target\" ;;
    *) printf '%s\n' 'Filesystem 1024-blocks Used Available Capacity Mounted on'
       printf '%s\n' \"tmpfs 100000 1 99999 1% $PREFLIGHT_DIR/target\" ;;
  esac
}
busybox() { return 127; }
preflight_target install_system '$PREFLIGHT_DIR/target' 1024")"
if printf '%s' "$preflight_out" 2>/dev/null | grep -Fqx 'INSTALL_UNCHANGED install_system target_insufficient_inodes'; then
  pass "a destination out of inodes refuses on inodes rather than on capacity"
else
  fail_test "a destination out of inodes refuses on inodes rather than on capacity"
fi

# The same shape with zero inodes TOTAL is a filesystem that does not track them at all, which some
# overlayfs and tmpfs mounts do. The free column cannot tell that apart from exhaustion, so reading
# it alone turns a working upgrade into a refusal on a number that means "not applicable here".
preflight_out="$(run_preflight "df() {
  case \" \$* \" in
    *' -i '*) printf '%s\n' 'Filesystem Inodes IUsed IFree IUse% Mounted on'
              printf '%s\n' \"overlay 0 0 0 - $PREFLIGHT_DIR/target\" ;;
    *) printf '%s\n' 'Filesystem 1024-blocks Used Available Capacity Mounted on'
       printf '%s\n' \"overlay 100000 1 99999 1% $PREFLIGHT_DIR/target\" ;;
  esac
}
busybox() { return 127; }
preflight_target install_system '$PREFLIGHT_DIR/target' 1024")"
if [ -z "$preflight_out" ]; then
  pass "a filesystem that does not count inodes is not read as having run out of them"
else
  fail_test "a filesystem that does not count inodes is not read as having run out of them"
fi

# A destination the caller genuinely cannot write, on a filesystem that is not read-only and not
# full. This is the catch-all, and it must stay distinguishable from the three named causes above.
if [ "$(id -u)" = 0 ] && command -v setpriv >/dev/null 2>&1; then
  mkdir -p "$PREFLIGHT_DIR/closed"
  chmod 700 "$PREFLIGHT_DIR/closed"
  # The dropped-privilege shell has to be able to read the extracted functions; only the destination
  # under test may be closed to it.
  PREFLIGHT_SHARED="$TMP/preflight-fn-readable.sh"
  cp "$PREFLIGHT_FN" "$PREFLIGHT_SHARED"
  chmod 755 "$TMP" "$PREFLIGHT_DIR" "$PREFLIGHT_SHARED"
  preflight_out="$(setpriv --reuid=65534 --regid=65534 --clear-groups \
    /bin/sh -c "PATH=/usr/bin:/bin; . '$PREFLIGHT_SHARED'; preflight_target install_system '$PREFLIGHT_DIR/closed' 1024" 2>&1 || true)"
  if printf '%s' "$preflight_out" 2>/dev/null | grep -Fqx 'INSTALL_UNCHANGED install_system target_not_writable'; then
    pass "a destination that refuses a real write refuses as unwritable"
  else
    fail_test "a destination that refuses a real write refuses as unwritable"
  fi
else
  # Never silently skip: an unrunnable fixture is reported so the gap is visible in the log.
  echo "   note: unprivileged write-refusal fixture needs root and setpriv; not run here"
fi

preflight_out="$(run_preflight "preflight_source install_system '$PREFLIGHT_DIR/absent' $PREFLIGHT_SHA")"
if printf '%s' "$preflight_out" 2>/dev/null | grep -Fqx 'INSTALL_UNCHANGED install_system staged_source_unavailable' &&
   printf '%s' "$preflight_out" 2>/dev/null | grep -Eq '^INSTALL_DIAG install_system staged source=.* state=missing '; then
  pass "staging that is no longer on the panel refuses as missing staging, not as a partition fault"
else
  fail_test "staging that is no longer on the panel refuses as missing staging, not as a partition fault"
fi

preflight_out="$(run_preflight "preflight_source install_system '$PREFLIGHT_DIR/staged' $PREFLIGHT_WRONG_SHA")"
if printf '%s' "$preflight_out" 2>/dev/null | grep -Fqx 'INSTALL_UNCHANGED install_system staged_source_unauthenticated' &&
   printf '%s' "$preflight_out" 2>/dev/null | grep -Eq '^INSTALL_DIAG install_system staged source=.* state=mismatched '; then
  pass "staging that does not match the signed checksum refuses before anything is replaced"
else
  fail_test "staging that does not match the signed checksum refuses before anything is replaced"
fi

preflight_out="$(run_preflight "preflight_source install_system '$PREFLIGHT_DIR/staged' $PREFLIGHT_SHA")"
if [ -z "$preflight_out" ]; then
  pass "authentic staging passes the source preflight silently"
else
  fail_test "authentic staging passes the source preflight silently"
fi

# The copy path's success case: silent, and the bytes actually arrive. Without this the errno
# assertions above would still pass against a copy_staged that never copied anything.
rm -f "$PREFLIGHT_DIR/target/copied"
preflight_out="$(run_preflight "copy_staged install_system cp_hapaneld-helper_new '$PREFLIGHT_DIR/staged' '$PREFLIGHT_DIR/target/copied' $PREFLIGHT_SHA")"
if [ -z "$preflight_out" ] && cmp -s "$PREFLIGHT_DIR/staged" "$PREFLIGHT_DIR/target/copied"; then
  pass "a successful copy is silent and the destination is byte-identical to the staged file"
else
  fail_test "a successful copy is silent and the destination is byte-identical to the staged file"
fi
rm -f "$PREFLIGHT_DIR/target/copied"

# Missing staging reaching the copy, rather than the preflight: the errno names it, and the source
# line says the file is gone rather than blaming the destination.
preflight_out="$(run_preflight "copy_staged install_system cp_hapaneld-helper_new '$PREFLIGHT_DIR/absent' '$PREFLIGHT_DIR/target/copied' $PREFLIGHT_SHA")"
if printf '%s' "$preflight_out" 2>/dev/null | grep -Fqx 'INSTALL_STEP_FAILED install_system cp_hapaneld-helper_new' &&
   printf '%s' "$preflight_out" 2>/dev/null | grep -Eq '^INSTALL_DIAG install_system cp_hapaneld-helper_new source=.* state=missing '; then
  pass "a copy whose staged file has gone reports the source, not the destination"
else
  fail_test "a copy whose staged file has gone reports the source, not the destination"
fi

# Nothing this emits may carry anything but the fixed keys and the paths the transaction already
# names in clear, because its whole purpose is to be pasted into a public issue.
preflight_out="$(run_preflight "emit_target_diag install_system target '$PREFLIGHT_DIR/target'")"
if [ "$(printf '%s\n' "$preflight_out" | grep -c .)" = 1 ] &&
   printf '%s' "$preflight_out" 2>/dev/null | grep -Eq '^INSTALL_DIAG install_system target dir=[^ ]+ mount=[^ ]+ state=[^ ]+ availkb=[^ ]+ inodesfree=[^ ]+ mode=[^ ]+ owner=[^ ]+ selinux=[^ ]+ context=[^ ]+$'; then
  pass "a target diagnostic is one line of fixed keys with no free-form text"
else
  fail_test "a target diagnostic is one line of fixed keys with no free-form text"
fi

# Every field must degrade rather than fail. A host with no getenforce and no SELinux labels still
# has to produce a complete line, because the diagnostic runs on the recovery path.
preflight_out="$(run_preflight "getenforce() { return 127; }
ls() { return 127; }
emit_target_diag install_system target '$PREFLIGHT_DIR/target'")"
if printf '%s' "$preflight_out" 2>/dev/null | grep -Eq 'selinux=unknown context=unknown$'; then
  pass "unreadable diagnostic fields degrade to unknown instead of failing the report"
else
  fail_test "unreadable diagnostic fields degrade to unknown instead of failing the report"
fi

# Every install verb must refuse before it removes or replaces anything. A failing panel was told
# its helper "was preserved or restored" by a transaction that had already deleted recovery copies
# from the partition it then failed to write.
#
# Removal and replacement is the exact claim, and it is narrower than "before anything at all":
# every verb creates /data/adb/hapaneld above the preflight, and install_systemless also creates
# /data/adb/service.d. That is idempotent directory creation which changes no helper, no boot
# registration and no installed package, so the host's "nothing on the panel changed" stays true;
# the scan below is deliberately over the removing and replacing verbs for that reason.
assert_preflight_precedes_mutation() {
  local function_name="$1" body first_mutation first_preflight
  body="$(sed -n "/^${function_name}() {$/,/^}$/p" "$PROVISION")"
  # The LAST preflight, not the first. A verb that preflights its sources, then removes a file, then
  # preflights its targets has still mutated before it finished asking - and taking the first
  # preflight line would call that correct. A mutant did exactly that and survived.
  last_preflight="$(grep -nE '^  preflight_(source|target) ' <<<"$body" | tail -1 | cut -d: -f1)"
  first_mutation="$(grep -nE '^  (rm -f|cp |copy_staged|mv -f) ' <<<"$body" | head -1 | cut -d: -f1)"
  if [ -n "$last_preflight" ] && [ -n "$first_mutation" ] && [ "$last_preflight" -lt "$first_mutation" ]; then
    pass "$function_name refuses before its first mutation"
  else
    fail_test "$function_name refuses before its first mutation"
  fi
}
assert_preflight_precedes_mutation install_system
assert_preflight_precedes_mutation install_systemless
assert_preflight_precedes_mutation install_hybrid

# The journal marker still lives in /system/bin on the system route, and both the system and hybrid
# routes remove an old helper from there after retirement. Each is a write that fails the transaction
# late on a read-only /system/bin unless that directory is preflighted like every other destination.
# A review of the first submission found exactly this gap; these pin the fix.
for preflight_verb in install_system install_hybrid; do
  if sed -n "/^${preflight_verb}() {$/,/^}$/p" "$PROVISION" | grep -qE "^  preflight_target ${preflight_verb} /system/bin "; then
    pass "$preflight_verb preflights /system/bin, where its journal or old-helper removal still writes"
  else
    fail_test "$preflight_verb preflights /system/bin, where its journal or old-helper removal still writes"
  fi
done

# Every copy of a staged file goes through the reporting path. One raw `cp @STAGED_...@` left behind
# is one more failure that can only ever report a step name.
preflight_raw_copies=0
for preflight_function in install_system install_systemless install_hybrid; do
  if sed -n "/^${preflight_function}() {$/,/^}$/p" "$PROVISION" | grep -qE '^  cp @STAGED_[A-Z_]+@ '; then
    preflight_raw_copies=$((preflight_raw_copies + 1))
  fi
done
if [ "$preflight_raw_copies" = 0 ]; then
  pass "no install verb copies staged content without the reporting path"
else
  fail_test "no install verb copies staged content without the reporting path"
fi

# The panel's /system/bin/sh does 32-bit signed arithmetic. Multiplying a df figure in KB by 1024 goes
# negative past 2 GiB free, and a negative number is below every need, so the panel with the MOST
# room was refused as having none - measured on hardware as 2652400 * 1024 = -1578909696. This host's
# shell is 64-bit, so no runtime assertion here can fail for the right reason; the shape of the
# comparison is asserted instead, and the hardware acceptance tool is what proves it live.
preflight_target_code="$(sed -n '/^preflight_target() {$/,/^}$/p' "$PROVISION" | grep -vE '^[[:space:]]*#')"
if printf '%s\n' "$preflight_target_code" 2>/dev/null | grep -qE 'availkb[[:space:]]*\*[[:space:]]*1024'; then
  fail_test "the capacity comparison never multiplies the panel's free-space figure"
else
  pass "the capacity comparison never multiplies the panel's free-space figure"
fi
if printf '%s\n' "$preflight_target_code" 2>/dev/null | grep -qF '(preflight_need + 1023) / 1024'; then
  pass "the capacity comparison divides the need into kilobytes instead"
else
  fail_test "the capacity comparison divides the need into kilobytes instead"
fi

# Host side: the diagnostics are bounded in both directions, and the advice is chosen by reason.
HOST_DIAG_FN="$TMP/host-diag-fn.sh"
{
  printf '%s\n' "sanitize_terminal() { LC_ALL=C tr -d '\\000-\\010\\013\\014\\016-\\037\\177'; }"
  sed -n '/^root_helper_install_diagnostics() {$/,/^}$/p' "$PROVISION"
  sed -n '/^root_helper_install_marker() {$/,/^}$/p' "$PROVISION"
  sed -n '/^root_helper_unchanged_advice() {$/,/^}$/p' "$PROVISION"
} > "$HOST_DIAG_FN"

host_diag_input="$(for i in 1 2 3 4 5 6 7 8 9; do printf 'INSTALL_DIAG install_system target line=%s\n' "$i"; done)"
host_diag_lines="$(TARGET=panel.test:5555 bash -c ". '$HOST_DIAG_FN'; root_helper_install_diagnostics \"\$1\"" _ "$host_diag_input" | grep -c .)"
if [ "$host_diag_lines" = 6 ]; then
  pass "a panel cannot flood the operator's output through the diagnostic path"
else
  fail_test "a panel cannot flood the operator's output through the diagnostic path"
fi

host_diag_out="$(TARGET=panel.test:5555 bash -c ". '$HOST_DIAG_FN'; root_helper_unchanged_advice 'INSTALL_UNCHANGED install_system target_read_only'")"
if printf '%s' "$host_diag_out" 2>/dev/null | grep -Fq 'mounted read-only' &&
   ! printf '%s' "$host_diag_out" 2>/dev/null | grep -Fq 'wedged helper'; then
  pass "a read-only refusal is advised about the mount, never about a wedged helper"
else
  fail_test "a read-only refusal is advised about the mount, never about a wedged helper"
fi

host_diag_out="$(TARGET=panel.test:5555 bash -c ". '$HOST_DIAG_FN'; root_helper_unchanged_advice 'INSTALL_UNCHANGED install_system helper_retirement'")"
if printf '%s' "$host_diag_out" 2>/dev/null | grep -Fq 'wedged helper'; then
  pass "the retirement refusal keeps its own advice after the split"
else
  fail_test "the retirement refusal keeps its own advice after the split"
fi

host_diag_out="$(TARGET=panel.test:5555 bash -c ". '$HOST_DIAG_FN'; root_helper_install_marker 'INSTALL_DIAG install_system target dir=/data/local
INSTALL_UNCHANGED install_system target_read_only'")"
if [ "$host_diag_out" = 'INSTALL_UNCHANGED install_system target_read_only' ]; then
  pass "the marker extractor reads a pre-mutation refusal, which it used to drop"
else
  fail_test "the marker extractor reads a pre-mutation refusal, which it used to drop"
fi

managed_restart_body="$(awk '
  /# All boot mechanisms only register the canonical authority/ { active=1 }
  active { print }
  active && /if ! wait_for_helper_reply PING OK/ { exit }
' "$PROVISION")"
if grep -Fq '/data/local/hapaneld-helper --supervise >/dev/null 2>&1 &' <<<"$managed_restart_body" && \
   ! grep -Fq 'start hapaneld_helper' <<<"$managed_restart_body"; then
  pass "managed helper restart bypasses stale in-memory init definitions and launches the canonical supervisor"
else
  fail_test "managed helper restart bypasses stale in-memory init definitions and launches the canonical supervisor"
fi

# Guard DB maintenance authority is valid only in the supervised worker. Keep every generated init
# command, service.d command and direct recovery fallback on that entry point. The exact count keeps
# this source contract from passing vacuously if one of the launch paths is deleted.
canonical_boot_launches="$(grep -Ec '^service hapaneld_helper /data/local/hapaneld-helper --supervise$|^/data/local/hapaneld-helper --supervise >/dev/null 2>&1 &$' "$PROVISION")"
canonical_unsupervised_launches="$(awk '
  /service hapaneld_helper \/data\/local\/hapaneld-helper/ && !/--supervise/ { print }
  /^\/data\/local\/hapaneld-helper .*\/dev\/null 2>&1 *&/ && !/--request/ && !/--supervise/ { print }
' "$PROVISION")"
if [ "$canonical_boot_launches" -eq 3 ] && [ -z "$canonical_unsupervised_launches" ]; then
  pass "all three target boot registrations use the one canonical supervised authority"
else
  LAST_OUTPUT="$PROVISION"
  fail_test "all three target boot registrations use the one canonical supervised authority (found $canonical_boot_launches target boot launches)"
fi

# Android init can retain an older in-memory service definition after its rc file is replaced, and
# `start` can report success without creating a process when that definition is not loaded. Every
# rollback route that can restore /system/bin/hapaneld-helper therefore launches the exact managed
# binary directly in supervised mode instead of trusting `start` to use the new argv.
assert_rollback_supervised_restart() {
  local function_name="$1" body
  body="$(sed -n "/^${function_name}() {$/,/^}$/p" "$PROVISION")"
  if [[ "$body" == *'/system/bin/hapaneld-helper --supervise >/dev/null 2>&1 &'* ]] &&
       [[ "$body" != *'start hapaneld_helper'* ]] &&
       grep -Fxq '  retire_helpers || return 1' <<<"$body"; then
    pass "$function_name restarts the restored system helper under direct supervision"
  else
    fail_test "$function_name restarts the restored system helper under direct supervision"
  fi
}
assert_rollback_supervised_restart rollback_system
assert_rollback_supervised_restart rollback_systemless
assert_rollback_supervised_restart rollback_hybrid

rollback_v2_body="$(sed -n '/^rollback_v2() {$/,/^}$/p' "$PROVISION")"
if grep -Fxq '  retire_helpers || return 1' <<<"$rollback_v2_body" && \
   ! grep -Fq 'wait_for_helper_retirement' "$PROVISION"; then
  pass "every rollback path uses the escalating retirement authority"
else
  fail_test "every rollback path uses the escalating retirement authority"
fi

commit_fn_line="$(grep -n '^commit_system() {' "$PROVISION" | head -1 | cut -d: -f1)"
commit_marker_line="$(awk -v after="$commit_fn_line" 'NR > after && /rm -f "\$marker" \|\| return 1/{print NR; exit}' "$PROVISION")"
commit_target_line="$(grep -n '\[ "$(classify_system)" = TARGET \] || return 1' "$PROVISION" | head -1 | cut -d: -f1)"
commit_sync_line="$(awk -v after="$commit_marker_line" 'NR > after && /sync \|\| return 1/{print NR; exit}' "$PROVISION")"
commit_recovery_line="$(awk -v after="$commit_sync_line" 'NR > after && /cleanup_v2_recoveries/{print NR; exit}' "$PROVISION")"
if [ -n "$commit_target_line" ] && [ -n "$commit_marker_line" ] && [ -n "$commit_sync_line" ] && [ -n "$commit_recovery_line" ] && \
   [ "$commit_target_line" -lt "$commit_marker_line" ] && [ "$commit_marker_line" -lt "$commit_sync_line" ] && \
   [ "$commit_sync_line" -lt "$commit_recovery_line" ] && \
   grep -Fq '[ "$(classify_systemless)" = TARGET ] || return 1' "$PROVISION" && \
   grep -Fq '[ "$(classify_hybrid)" = TARGET ] || return 1' "$PROVISION"; then
  pass "helper commit rechecks exact target state before durably removing recovery"
else
  fail_test "helper commit rechecks exact target state before durably removing recovery"
fi
if grep -Fq 'elif v2_canonical_swapped "$marker"; then echo CANONICAL_SWAPPED' "$PROVISION" && \
   grep -Fq 'elif v2_boot_switched "$kind" "$marker"; then echo BOOT_SWITCHED' "$PROVISION" && \
   grep -Fq 'case "$state" in PRE_SWAP|CANONICAL_SWAPPED|BOOT_SWITCHED|TARGET)' "$PROVISION" && \
   grep -Fq 'restore_or_remove_v2 LIVE_BIN "$(v2_recovery_path live)" /data/local/hapaneld-helper 700 "$marker"' "$PROVISION" && \
   grep -Fq 'restore_or_remove_v2 VENDOR_RC "$(v2_recovery_path vendorrc)" /vendor/etc/init/hapaneld-helper.rc 644 "$marker"' "$PROVISION" && \
   grep -Fq 'echo TARGET_BOOT_SHA256=@RC_SHA256@' "$PROVISION" && \
   grep -Fq 'echo TARGET_BOOT_SHA256=@SERVICE_SHA256@' "$PROVISION" && \
   grep -Fq 'echo TARGET_BOOT_SHA256=@HYBRID_RC_SHA256@' "$PROVISION" && \
   grep -Fq 'v2_recorded "$marker" || return 1' "$PROVISION"; then
  pass "v2 recovery authenticates canonical-swap and boot-switch states before complete rollback"
else
  fail_test "v2 recovery authenticates canonical-swap and boot-switch states before complete rollback"
fi
if grep -Fq 'mv -f "$candidate" /data/local/hapaneld-helper || { echo "INSTALL_STEP_FAILED install_system mv_hapaneld-helper"; return 1; }' "$PROVISION" && \
   grep -Fq 'mv -f /system/etc/init/hapaneld-helper.rc.new /system/etc/init/hapaneld-helper.rc || { echo "INSTALL_STEP_FAILED install_system mv_hapaneld-helper.rc"; return 1; }' "$PROVISION" && \
   grep -Fq 'mv -f "$candidate" /data/local/hapaneld-helper || { echo "INSTALL_STEP_FAILED install_systemless mv_hapaneld-helper"; return 1; }' "$PROVISION" && \
   grep -Fq 'mv -f /data/adb/service.d/hapaneld-helper.sh.new /data/adb/service.d/hapaneld-helper.sh || { echo "INSTALL_STEP_FAILED install_systemless mv_hapaneld-helper.sh"; return 1; }' "$PROVISION" && \
   grep -Fq 'mv -f "$candidate" /data/local/hapaneld-helper || { echo "INSTALL_STEP_FAILED install_hybrid mv_hapaneld-helper"; return 1; }' "$PROVISION" && \
   grep -Fq 'mv -f /vendor/etc/init/hapaneld-helper.rc.new /vendor/etc/init/hapaneld-helper.rc || { echo "INSTALL_STEP_FAILED install_hybrid mv_hapaneld-helper.rc"; return 1; }' "$PROVISION"; then
  pass "v2 canonical and boot-switch failures retain machine-readable install-step evidence"
else
  fail_test "v2 canonical and boot-switch failures retain machine-readable install-step evidence"
fi
if grep -Fq 'CANCEL_EXTERNAL=1' "$PROVISION" && \
   grep -Fq 'EXTERNAL_HELPER_SHA256=$external_sha' "$PROVISION" && \
   grep -Fq 'cancel-external-system|cancel-external-systemless|cancel-external-hybrid' "$PROVISION" && \
   grep -Fq 'printf '\''%s\n'\'' "$canceled" | grep -qx EXTERNAL_CANONICAL_RETRY' "$PROVISION" && \
   grep -Fq 'while printf '\''%s\n'\'' "$out2" | grep -qx EXTERNAL_CANONICAL_RETRY' "$PROVISION" && \
   grep -Fq '/data/local/.hapaneld-guard-db/replacement.v1' "$PROVISION" && \
   grep -Fq '*) echo TOPOLOGY_HOLD; return 4' "$PROVISION"; then
  pass "external canonical changes retire only their managed journal and retry from a fresh snapshot"
else
  fail_test "external canonical changes retire only their managed journal and retry from a fresh snapshot"
fi
if grep -Fq 'echo V1_ROLLBACK_IN_PROGRESS=1 >> "$intent_staging"' "$PROVISION" && \
   grep -Fq 'intent_staging="$marker.rollback-intent-$transaction_id"' "$PROVISION" && \
   grep -Fq 'v1_restore_staging="$v1_restore_live.rollback-v1-$transaction_id"' "$PROVISION" && \
   grep -Fq 'file_exact "$v1_restore_expected" "$v1_restore_staging" "$v1_restore_mode"' "$PROVISION" && \
   grep -Fq 'mv -f "$v1_restore_staging" "$v1_restore_live" || return 1' "$PROVISION" && \
   grep -Fq '[ "$state" = PRE_SWAP ] || [ "$state" = TARGET ] || [ "$state" = TRANSITION ] || return 1' "$PROVISION"; then
  pass "v1 rollback publishes durable intent and atomically restores each authenticated path"
else
  fail_test "v1 rollback publishes durable intent and atomically restores each authenticated path"
fi
if grep -Fq 'hybrid_matches_recorded_v1() {' "$PROVISION" && \
   grep -Fq 'elif hybrid_matches_recorded_v1; then' "$PROVISION" && \
   grep -Fq 'hybrid_matches_recorded_v1 || return 1' "$PROVISION"; then
  pass "legacy hybrid rollback still finalizes v1 journaled state when target bytes are unchanged"
else
  fail_test "legacy hybrid rollback still finalizes v1 journaled state when target bytes are unchanged"
fi
# System and systemless rollback finalization must compare the journaled state directly because their
# classifiers deliberately resolve unchanged target bytes in favour of a successful commit.
if grep -Fq 'system_matches_recorded_v1() {' "$PROVISION" && \
   grep -Fq 'elif system_matches_recorded_v1; then' "$PROVISION" && \
   grep -Fq 'system_matches_recorded_v1 || return 1' "$PROVISION" && \
   ! grep -Fq '[ "$(classify_system)" = PRE_SWAP ] || return 1' "$PROVISION"; then
  pass "system rollback finalizes against the journaled state even when target bytes are unchanged"
else
  fail_test "system rollback finalizes against the journaled state even when target bytes are unchanged"
fi
if grep -Fq 'systemless_matches_recorded_v1() {' "$PROVISION" && \
   grep -Fq 'elif systemless_matches_recorded_v1; then' "$PROVISION" && \
   grep -Fq 'systemless_matches_recorded_v1 || return 1' "$PROVISION" && \
   ! grep -Fq '[ "$(classify_systemless)" = PRE_SWAP ] || return 1' "$PROVISION"; then
  pass "systemless rollback finalizes against the journaled state even when target bytes are unchanged"
else
  fail_test "systemless rollback finalizes against the journaled state even when target bytes are unchanged"
fi
if grep -Fq 'helper_build_id="$(helper/source-id.sh)"' "$RELEASE_WORKFLOW" && \
   grep -Fq -- '-DHAPANELD_BUILD_ID=' "$RELEASE_WORKFLOW" && \
   grep -Fq 'RELEASE_HELPER_BUILD_ID=' "$RELEASE_WORKFLOW" && \
   grep -Fq 'HELPER_BUILD_ID' "$RELEASE_WORKFLOW"; then
  pass "release helpers and provisioner share one deterministic full-source build identity"
else
  fail_test "release helpers and provisioner share one deterministic full-source build identity"
fi
if grep -Fq 'cmp release-input/hapaneld-helper-armeabi-v7a app/src/main/assets/hapaneld-helper-arm' "$RELEASE_WORKFLOW" && \
   grep -Fq 'cmp release-input/hapaneld-helper-arm64-v8a app/src/main/assets/hapaneld-helper-arm64' "$RELEASE_WORKFLOW"; then
  pass "release workflow proves standalone privileged assets are byte-identical to the APK bundle"
else
  fail_test "release workflow proves standalone privileged assets are byte-identical to the APK bundle"
fi
if grep -Fq 'hapaneld-helper-arm64-v8a \' "$RELEASE_WORKFLOW" && \
   grep -Fq 'hapaneld-helper-armeabi-v7a \' "$RELEASE_WORKFLOW" && \
   grep -Fq 'sha256sum "$helper_arm_name"' "$RELEASE_WORKFLOW" && \
   grep -Fq 'sha256sum "$helper_arm64_name"' "$RELEASE_WORKFLOW"; then
  pass "release workflow seals both helper inputs and publishes checksums"
else
  fail_test "release workflow seals both helper inputs and publishes checksums"
fi
if grep -Fq 'for checksum_name in "$apk_name.sha256" "$provisioner_name.sha256" "$helper_arm_name.sha256" "$helper_arm64_name.sha256"' "$RELEASE_WORKFLOW" && \
   grep -Fq '"dist/$checksum_name.sig"' "$RELEASE_WORKFLOW"; then
  pass "release workflow signs both helper checksum records"
else
  fail_test "release workflow signs both helper checksum records"
fi
if grep -Fq 'trusted_public_key_sha256=$(/usr/bin/openssl pkey -pubin -in "$installer_public_key" -outform DER' "$RELEASE_WORKFLOW" && \
   grep -Fq 'if ! /usr/bin/cmp --silent "$installer_public_key" "$provisioner_public_key"; then' "$RELEASE_WORKFLOW" && \
   grep -Fq 'signing_public_key_sha256=$(/usr/bin/openssl pkey -pubin -in "$public_key" -outform DER' "$RELEASE_WORKFLOW" && \
   grep -Fq 'if [ "$signing_public_key_sha256" != "$trusted_public_key_sha256" ]; then' "$RELEASE_WORKFLOW"; then
  pass "release signing fails before publication when installer trust keys drift from the keystore"
else
  fail_test "release signing fails before publication when installer trust keys drift from the keystore"
fi

# A failed direct-start COMMAND must not skip the health wait: the launcher route may already have
# the agent coming up, and aborting there is how a starting panel gets called a failure.
MOCK_DIRECT_START=fail MOCK_HEALTH=slow MOCK_HEALTH_READY_AFTER=4 APP_HEALTH_TIMEOUT_SECONDS=20 \
  run_provision "$MOCK_TARGET" --apk "$APK" --no-tame
assert_success "a failed direct start still waits out the health budget"

# Exactly one direct start. A second launch restarts the first-run migration being waited on.
# MOCK_STOPPED_STATE + an ineffective launcher forces the escalation. Without that the launcher
# answers during the probe and this passes with zero direct launches, proving nothing.
MOCK_STOPPED_STATE=1 MOCK_LAUNCHER_START=ineffective APP_HEALTH_TIMEOUT_SECONDS=20 \
  run_provision "$MOCK_TARGET" --apk "$APK" --no-tame
assert_success "an ineffective launcher escalates to the direct route and still provisions"
direct_starts="$(grep -c 'shell am start -n io\.github\.maxlyth\.hapaneld/\.MainActivity' "$MOCK_CALL_LOG" || true)"
if [ "$direct_starts" -eq 1 ]; then
  pass "the direct route is issued exactly once while the agent is starting"
else
  fail_test "the direct route is issued exactly once while the agent is starting (saw $direct_starts)"
fi

# A hanging launcher command must not consume the health budget with it.
MOCK_LAUNCHER_START=block APP_LAUNCH_COMMAND_TIMEOUT_SECONDS=2 APP_HEALTH_TIMEOUT_SECONDS=3 \
  run_provision "$MOCK_TARGET" --apk "$APK" --no-tame
assert_success "a hanging launcher command is bounded and the run still completes"

# ── #74: a slow first start is not a failed start ───────────────────────────────────────────────
# A panel can need about two minutes to answer after an upgrade while it is still migrating. The
# launch route and the health budget must stay separate, and the shipped default must be long.
default_health_budget="$(sed -n 's/^APP_HEALTH_TIMEOUT_SECONDS="\${APP_HEALTH_TIMEOUT_SECONDS:-\([0-9]*\)}"/\1/p' "$PROVISION")"
if [ "${default_health_budget:-0}" -ge 120 ]; then
  pass "the shipped health budget tolerates a multi-minute first start (${default_health_budget}s)"
else
  fail_test "the shipped health budget tolerates a multi-minute first start (got '${default_health_budget}')"
fi

MOCK_HEALTH=slow MOCK_HEALTH_READY_AFTER=4 APP_HEALTH_TIMEOUT_SECONDS=20 \
  run_provision "$MOCK_TARGET" --apk "$APK" --no-tame
assert_success "a panel that answers only after a delay still provisions"
assert_not_contains 'provisioning is incomplete' "$LAST_OUTPUT" "a late-starting panel is not reported as incomplete"

MOCK_HEALTH=fail APP_HEALTH_TIMEOUT_SECONDS=2 run_provision "$MOCK_TARGET" --apk "$APK" --no-tame
assert_failure "a genuinely dead agent still fails after the budget expires"
assert_contains 'still not answering .* after [0-9]+s' "the failure names how long it waited"

# A health probe that never answers must consume only curl's remaining-budget clamp. This exercises
# the blocking shape rather than treating an immediate fixture failure as deadline coverage.
HEALTH_HANG_PID_FILE="$TMP/health-hang.pids"
HEALTH_HANG_DONE_FILE="$TMP/health-hang.done"
HEALTH_HANG_STATUS_FILE="$TMP/health-hang.status"
rm -f "$HEALTH_HANG_PID_FILE" "$HEALTH_HANG_DONE_FILE" "$HEALTH_HANG_STATUS_FILE"
(
  MOCK_HEALTH=hang \
  MOCK_HEALTH_HANG_SECONDS=30 \
  MOCK_HEALTH_HANG_PID_FILE="$HEALTH_HANG_PID_FILE" \
  MOCK_HEALTH_HANG_DONE_FILE="$HEALTH_HANG_DONE_FILE" \
  APP_LAUNCH_PROBE_SECONDS=2 \
  APP_HEALTH_TIMEOUT_SECONDS=1 \
    run_provision "$MOCK_TARGET" --apk "$APK" --no-tame
  printf '%s\n' "$LAST_STATUS" > "$HEALTH_HANG_STATUS_FILE"
) &
health_run_pid=$!
health_hang_ready=0
health_hang_attempt=0
while [ "$health_hang_attempt" -lt 500 ]; do
  if [ -s "$HEALTH_HANG_PID_FILE" ]; then health_hang_ready=1; break; fi
  kill -0 "$health_run_pid" 2>/dev/null || break
  /bin/sleep 0.01
  health_hang_attempt=$((health_hang_attempt + 1))
done
first_fixture_pid="$(awk 'NR == 1 {print $1}' "$HEALTH_HANG_PID_FILE" 2>/dev/null)"
first_sleep_pid="$(awk 'NR == 1 {print $2}' "$HEALTH_HANG_PID_FILE" 2>/dev/null)"
if [ "$health_hang_ready" = 1 ] && kill -0 "$first_fixture_pid" 2>/dev/null && \
   kill -0 "$first_sleep_pid" 2>/dev/null && [ ! -s "$HEALTH_HANG_DONE_FILE" ]; then
  pass "MOCK_HEALTH=hang observably blocks inside its bounded probe"
else
  fail_test "MOCK_HEALTH=hang observably blocks inside its bounded probe"
fi
wait "$health_run_pid"
LAST_OUTPUT="$TMP/output.txt"
LAST_STATUS="$(cat "$HEALTH_HANG_STATUS_FILE")"
assert_failure "a hanging health probe fails through the normal bounded health path"
assert_contains 'still not answering .* after 1s' "a hanging health probe reports the configured final budget"
direct_start_line="$(grep -n 'shell am start -n io\.github\.maxlyth\.hapaneld/\.MainActivity' "$MOCK_CALL_LOG" | tail -1 | cut -d: -f1)"
post_direct_health_calls="$(awk -v start="$direct_start_line" 'NR > start && /^curl .*\/health$/ {count++} END {print count+0}' "$MOCK_CALL_LOG")"
if [ "$post_direct_health_calls" -eq 2 ]; then
  pass "the direct route is followed by exactly two total health checks"
else
  fail_test "the direct route is followed by exactly two total health checks (saw $post_direct_health_calls)"
fi
post_direct_health_line="$(awk -v start="$direct_start_line" 'NR > start && /^curl .*\/health$/ {print}' "$MOCK_CALL_LOG")"
post_direct_launch_probes="$(printf '%s\n' "$post_direct_health_line" | grep -Ec '^curl .*--max-time 1 .*\/health$' || true)"
post_direct_status_checks="$(printf '%s\n' "$post_direct_health_line" | grep -Ec '^curl .*--max-time 5 .*\/health$' || true)"
if [ "$post_direct_launch_probes" -eq 1 ] && [ "$post_direct_status_checks" -eq 1 ]; then
  pass "post-direct health calls are exactly one launch probe and one bounded status check"
else
  fail_test "post-direct health calls are exactly one launch probe and one bounded status check (saw $post_direct_launch_probes/1s, $post_direct_status_checks/5s)"
fi
if [ -s "$HEALTH_HANG_PID_FILE" ] && cmp -s "$HEALTH_HANG_PID_FILE" "$HEALTH_HANG_DONE_FILE"; then
  pass "every hanging health probe completed its bounded wait"
else
  fail_test "every hanging health probe completed its bounded wait"
fi
health_process_leaked=0
while read -r fixture_pid sleep_pid _; do
  kill -0 "$fixture_pid" 2>/dev/null && health_process_leaked=1
  kill -0 "$sleep_pid" 2>/dev/null && health_process_leaked=1
done < "$HEALTH_HANG_PID_FILE"
if [ "$health_process_leaked" = 0 ]; then
  pass "hanging health probes leave no fixture or sleep process behind"
else
  fail_test "hanging health probes leave no fixture or sleep process behind"
fi

# ── #74: nothing may mutate a panel whose agent never answered ──────────────────────────────────
# A health timeout used to fall through to the configuration POST and the restore import, so the run
# wrote settings — or imported a whole bundle — into an agent that had not come up.
# A package that stays stopped fails health at the LAUNCH step specifically, leaving the earlier
# steps intact. MOCK_HEALTH=fail cannot be used here: it breaks health for the whole run, so the
# provisioner dies before the launch step and the gate under test is never reached.
MOCK_STOPPED_STATE=1 MOCK_LAUNCHER_START=ineffective MOCK_DIRECT_START=fail \
  APP_HEALTH_TIMEOUT_SECONDS=2 APP_LAUNCH_PROBE_SECONDS=1 \
  run_provision "$MOCK_TARGET" --apk "$APK" --no-tame --id post-timeout-panel --mqtt tcp://broker.test:1883
assert_failure "a panel whose agent never answered fails the run"
assert_contains 'refusing to write configuration' "the run says why it refused to write"
assert_not_contains 'X POST .*api/v1/config$' "$MOCK_CALL_LOG" "no configuration is written after a health timeout"

MOCK_STOPPED_STATE=1 MOCK_LAUNCHER_START=ineffective MOCK_DIRECT_START=fail \
  APP_HEALTH_TIMEOUT_SECONDS=2 APP_LAUNCH_PROBE_SECONDS=1 \
  run_provision "$MOCK_TARGET" --apk "$APK" --no-tame --restore "$RESTORE"
assert_failure "a restore into an unanswering panel fails the run"
assert_contains 'refusing to import a configuration bundle' "the run says why it refused to import"
assert_not_contains 'api/v1/config/import' "$MOCK_CALL_LOG" "no bundle is imported after a health timeout"

# Minting is a side effect on Home Assistant, not on the panel: a token minted for a panel that never
# came up cannot be delivered and is left behind as a dangling credential to find and revoke.
MOCK_STOPPED_STATE=1 MOCK_LAUNCHER_START=ineffective MOCK_DIRECT_START=fail \
  APP_HEALTH_TIMEOUT_SECONDS=2 APP_LAUNCH_PROBE_SECONDS=1 \
  run_provision "$MOCK_TARGET" --apk "$APK" --no-tame --ha-url https://ha.test --ha-user u --ha-pass p
assert_failure "a panel that never answered does not get a token minted for it"
assert_contains 'refusing to mint a Home Assistant token' "the run says which side effect it refused"
assert_not_contains 'auth/token' "$MOCK_CALL_LOG" "no token is minted against Home Assistant after a health timeout"

fi
[ "$PROVISION_TEST_SCOPE" != shard-fleet-installer ] || finish_provision_test

if provision_scope_is core all shard-host-reclamation; then
# ── #76: root-helper staging must not accumulate across failing runs ────────────────────────────
# The fixture models the panel's id-named staging as real files that survive run_provision's reset,
# because a real panel's /data/local/tmp and /data/adb/hapaneld do. Counts are exact, never bounds —
# a bound like -le 1 is also satisfied by a run that never staged anything.
DEVICE_ADB_STATE_DIR="$TMP/device-data-adb-hapaneld"
count_device_artifacts() { find "$DEVICE_ADB_STATE_DIR" -maxdepth 1 -name "$1" 2>/dev/null | wc -l | tr -d ' '; }

# A successful run leaves no staging behind.
rm -rf "$DEVICE_ADB_STATE_DIR"
run_provision "$MOCK_TARGET" --apk "$HELPER_RELEASE_APK" --release-tag v0.9.4-rc1 --no-tame
assert_success "baseline transactional run for staging accounting succeeds"
assert_log_contains 'push .*hapaneld-helper-[0-9a-f]{32}' "the successful run really staged a bundle (guards the counts below against vacuity)"
assert_count "$(count_device_artifacts 'hapaneld-helper-*')" 0 "a successful run leaves no staged bundle files"
assert_count "$(count_device_artifacts '.helper-transaction-*')" 0 "a successful run leaves no protected transaction record"

# Issue #76 itself: repeated failing runs accrued one protected transaction record plus one staged
# bundle per attempt. Reclamation now runs from the EXIT trap, and these counts also pin the
# probe_su cached-branch fix: inside a trap a bare `return` reports the shell's pending (failing)
# exit status, which silently no-opped every root command the handler issued — reverting that fix
# makes these counts nonzero.
rm -rf "$DEVICE_ADB_STATE_DIR"
for _ in 1 2 3; do
  MOCK_HELPER_INSTALL=fail \
    run_provision "$MOCK_TARGET" --apk "$HELPER_RELEASE_APK" --release-tag v0.9.4-rc1 --no-tame
done
assert_failure "a helper install failure still fails the run"
assert_log_contains 'push .*hapaneld-helper-[0-9a-f]{32}' "each failing run really staged a bundle (guards the counts below against vacuity)"
# These runs send no signals, so any reclamation observed is by construction the ORDINARY EXIT trap
# issuing a fresh privileged rm through the unmodified production run_root → adb() →
# run_with_deadline chain after the run's failing step — not a signal handler and not an extracted
# copy of the cleanup.
assert_log_contains 'rm -f .*hapaneld-helper-[0-9a-f]{32}' "the EXIT-trap reclamation rm went through the production adb wrapper in a signal-free failing run"
assert_count "$(count_device_artifacts 'hapaneld-helper-*')" 0 "three failing runs accrue no staged bundle files"
assert_count "$(count_device_artifacts '.helper-transaction-*')" 0 "three failing runs accrue no protected transaction records"

# Executable substantiation of the probe_su comment, in both directions: the cached branch's old
# bare-return form leaks the shell's pending exit status when the function runs inside an EXIT trap,
# and the explicit-status form is immune. Either assertion fails if bash's trap-return semantics
# were ever different from what the comment claims.
bare_return_form="$(bash -c 'f() { if [ -n "$V" ]; then [ "$V" != none ]; return; fi; }; h() { if f; then echo CACHE-HONOURED; else echo PENDING-STATUS-LEAKED; fi; }; trap h EXIT; V=su0join; exit 1')"
if [ "$bare_return_form" = PENDING-STATUS-LEAKED ]; then
  pass "a bare return inside an EXIT trap reports the pending exit status, not the preceding test (the defect probe_su guards against)"
else
  fail_test "a bare return inside an EXIT trap reports the pending exit status, not the preceding test (got: $bare_return_form)"
fi
explicit_return_form="$(bash -c 'f() { local c; if [ -n "$V" ]; then [ "$V" != none ]; c=$?; return "$c"; fi; }; h() { if f; then echo CACHE-HONOURED; else echo PENDING-STATUS-LEAKED; fi; }; trap h EXIT; V=su0join; exit 1')"
if [ "$explicit_return_form" = CACHE-HONOURED ]; then
  pass "the explicit-status form probe_su now uses is immune to trap-context status leakage"
else
  fail_test "the explicit-status form probe_su now uses is immune to trap-context status leakage (got: $explicit_return_form)"
fi

# The reclamation rm rides run_root, whose quoting differs per su dialect; prove the contract under
# the sh -c dialect as well as the default join style the runs above used.
rm -rf "$DEVICE_ADB_STATE_DIR"
MOCK_SU_DIALECT=shc MOCK_HELPER_INSTALL=fail \
  run_provision "$MOCK_TARGET" --apk "$HELPER_RELEASE_APK" --release-tag v0.9.4-rc1 --no-tame
assert_failure "a helper install failure still fails the run under the sh -c su dialect"
assert_log_contains 'push .*hapaneld-helper-[0-9a-f]{32}' "the sh -c dialect run really staged a bundle (guards the count below against vacuity)"
assert_count "$(count_device_artifacts 'hapaneld-helper-*')" 0 "a failing run reclaims its staging under the sh -c su dialect"

# Reclamation must also run from the SIGNAL path, not only plain exit: a provisioner killed while
# blocked in adb install has pushed its bundle and promoted its transaction record, and the signal
# handler — which never reaches the post-commit cleanup — must reclaim both. The journal marker is
# deliberately not part of this claim; it is the durable record reconcile needs.
for reclaim_signal in TERM INT; do
  case "$reclaim_signal" in TERM) expected_signal_status=143 ;; INT) expected_signal_status=130 ;; esac
  rm -rf "$DEVICE_ADB_STATE_DIR"
  : > "$MOCK_CALL_LOG"
  signal_install_pid_file="$TMP/reclaim-$reclaim_signal-install.pid"
  signal_output="$TMP/reclaim-$reclaim_signal-output.txt"
  # Job control is required for the INT case: a plain async job in a non-interactive shell starts
  # with SIGINT ignored, and a trap on a signal ignored at entry never arms — the provisioner would
  # ignore the kill instead of running its handler. Under set -m the child gets its own process
  # group with default dispositions, which is also how production's own deadline wrapper runs it.
  set -m
  MOCK_APK_INSTALL=block \
  MOCK_APK_INSTALL_PID_FILE="$signal_install_pid_file" \
  MOCK_STATE_DIR="$TMP" \
    bash "$PROVISION" "$MOCK_TARGET" --apk "$HELPER_RELEASE_APK" --release-tag v0.9.4-rc1 --no-tame \
      > "$signal_output" 2>&1 &
  signal_provision_pid=$!
  set +m
  signal_ready=0
  signal_attempt=0
  while [ "$signal_attempt" -lt 200 ]; do
    if [ -s "$signal_install_pid_file" ]; then signal_ready=1; break; fi
    /bin/sleep 0.05
    signal_attempt=$((signal_attempt + 1))
  done
  if [ "$signal_ready" -eq 1 ]; then
    pass "$reclaim_signal reclamation scenario reaches the blocked install with staging in place"
  else
    LAST_OUTPUT="$signal_output"
    fail_test "$reclaim_signal reclamation scenario reaches the blocked install with staging in place"
  fi
  assert_count "$(count_device_artifacts 'hapaneld-helper-*')" 5 "the staged bundle exists before $reclaim_signal arrives (guards the counts below against vacuity)"
  kill "-$reclaim_signal" "$signal_provision_pid" 2>/dev/null || true
  if wait "$signal_provision_pid"; then signal_status=0; else signal_status=$?; fi
  if [ "$signal_status" -eq "$expected_signal_status" ]; then
    pass "$reclaim_signal exits the provisioner with its signal status"
  else
    LAST_OUTPUT="$signal_output"
    fail_test "$reclaim_signal exits the provisioner with its signal status (got $signal_status)"
  fi
  assert_count "$(count_device_artifacts 'hapaneld-helper-*')" 0 "$reclaim_signal-interrupted run reclaims its staged bundle from the signal handler"
  assert_count "$(count_device_artifacts '.helper-transaction-*')" 0 "$reclaim_signal-interrupted run reclaims its protected transaction record from the signal handler"
done

# Reclamation is scoped to the run's OWN identity: a concurrent provisioner's staging must survive.
# The panel-side sweep that would remove a genuinely orphaned identity runs inside the device
# transaction script, which the fixture deliberately does not emulate — it is executed for real in
# the sandbox below instead, because a fixture model can agree with a test and disagree with
# production.
FOREIGN_STAGING_ID="0123456789abcdef0123456789abcdef"
mkdir -p "$DEVICE_ADB_STATE_DIR"
: > "$DEVICE_ADB_STATE_DIR/hapaneld-helper-$FOREIGN_STAGING_ID"
: > "$DEVICE_ADB_STATE_DIR/.helper-transaction-$FOREIGN_STAGING_ID-$(printf '%064d' 7)"
run_provision "$MOCK_TARGET" --apk "$HELPER_RELEASE_APK" --release-tag v0.9.4-rc1 --no-tame
assert_success "a run with a concurrent provisioner's staging present succeeds"
assert_count "$(count_device_artifacts "hapaneld-helper-$FOREIGN_STAGING_ID")" 1 "host reclamation never touches another run's staged bundle"
assert_count "$(count_device_artifacts ".helper-transaction-$FOREIGN_STAGING_ID-*")" 1 "host reclamation never touches another run's transaction record"
assert_count "$(count_device_artifacts 'hapaneld-helper-*')" 1 "the run's own staging is still reclaimed alongside the survivor"
rm -rf "$DEVICE_ADB_STATE_DIR"

# A reclamation the transport refuses must not change the run's outcome, must say so, and must name
# the mechanism that recovers the leftovers (the next transaction's panel-side sweep).
MOCK_STAGING_CLEANUP=fail \
  run_provision "$MOCK_TARGET" --apk "$HELPER_RELEASE_APK" --release-tag v0.9.4-rc1 --no-tame
assert_success "a successful provision stays successful when staging reclamation fails"
assert_contains "could not reclaim this run's root-helper staging" "a failed reclamation is reported, not silent"
assert_contains 'next provisioning transaction .* removes it automatically' "the report names the recovery mechanism"
assert_count "$(count_device_artifacts 'hapaneld-helper-*')" 5 "the unreclaimed bundle really was left behind (the report is about something real)"
assert_count "$(count_device_artifacts '.helper-transaction-*')" 1 "the unreclaimed transaction record really was left behind"
rm -rf "$DEVICE_ADB_STATE_DIR"

# A run that refuses before executing any transaction still reclaims what it staged.
MOCK_VENDOR_RC_STATE=unexpected \
  run_provision "$MOCK_TARGET" --apk "$HELPER_RELEASE_APK" --release-tag v0.9.4-rc1 --no-tame
assert_failure "an unexpected vendor rc still refuses the run"
assert_log_contains 'rm -f .*hapaneld-helper-[0-9a-f]{32}' "exit-path reclamation still runs in the pre-promotion window (guards the check below against vacuity)"

# The .new guard defends a state no end-to-end scenario reaches — any run that survives long enough
# to stage files has already derived the record path too — so the shipped cleanup is executed
# directly in that state: identity valid, record path still empty. Unguarded, the appended
# "$path.new" becomes a RELATIVE `.new` inside a privileged rm. The promoted state is asserted
# positively as well, so deleting the append outright cannot pass either.
CLEANUP_PROBE_ID="eeee5555eeee5555eeee5555eeee5555"
CLEANUP_PROBE_SHA="$(printf '%064d' 2)"
CLEANUP_SRC="$(sed -n '/^cleanup_root_helper_staging()/,/^}/p' "$PROVISION")"
if printf '%s\n' "$CLEANUP_SRC" 2>/dev/null | grep -q 'cleanup_root_helper_staging()'; then
  pass "the shipped cleanup function was extracted (an empty probe must not pass by doing nothing)"
else
  fail_test "the shipped cleanup function was extracted (an empty probe must not pass by doing nothing)"
fi
run_cleanup_probe() {
  local record_path="$1" capture="$TMP/cleanup-probe-capture"
  : > "$capture"
  /bin/bash -uc "
    YEL=''; X=''
    run_root() { printf '%s\n' \"\$1\" >> '$capture'; }
    $CLEANUP_SRC
    ROOT_HELPER_TRANSACTION_ID='$CLEANUP_PROBE_ID'
    ROOT_HELPER_STAGED_HELPER='/data/local/tmp/hapaneld-helper-$CLEANUP_PROBE_ID'
    ROOT_HELPER_STAGED_RC='/data/local/tmp/hapaneld-helper-$CLEANUP_PROBE_ID.rc'
    ROOT_HELPER_STAGED_HYBRID_RC='/data/local/tmp/hapaneld-helper-$CLEANUP_PROBE_ID.hrc'
    ROOT_HELPER_STAGED_SERVICE='/data/local/tmp/hapaneld-helper-$CLEANUP_PROBE_ID.svc'
    ROOT_HELPER_STAGED_TRANSACTION='/data/local/tmp/hapaneld-helper-$CLEANUP_PROBE_ID.txn'
    ROOT_HELPER_TRANSACTION_PATH='$record_path'
    cleanup_root_helper_staging"
  CLEANUP_PROBE_COMMAND="$(cat "$capture")"
}
run_cleanup_probe ""
case "$CLEANUP_PROBE_COMMAND" in
  *"hapaneld-helper-$CLEANUP_PROBE_ID"*) pass "pre-promotion cleanup still names the staged bundle (guards the check below against vacuity)" ;;
  *) fail_test "pre-promotion cleanup still names the staged bundle (guards the check below against vacuity)" ;;
esac
if printf '%s\n' "$CLEANUP_PROBE_COMMAND" 2>/dev/null | grep -Eq '(^| )\.new( |$)'; then
  fail_test "an exit before promotion never passes a relative .new to the privileged rm"
else
  pass "an exit before promotion never passes a relative .new to the privileged rm"
fi
run_cleanup_probe "/data/adb/hapaneld/.helper-transaction-$CLEANUP_PROBE_ID-$CLEANUP_PROBE_SHA"
case "$CLEANUP_PROBE_COMMAND" in
  *".helper-transaction-$CLEANUP_PROBE_ID-$CLEANUP_PROBE_SHA.new"*) pass "a promoted record's .new leftover is reclaimed once the path is owned" ;;
  *) fail_test "a promoted record's .new leftover is reclaimed once the path is owned" ;;
esac

fi

if provision_scope_is core all shard-host-reclamation; then
# ── #76: the panel-side sweep, executed from the shipped script ─────────────────────────────────
# The sweep is lifted from the transaction-script heredoc exactly as generation ships it, its
# @TRANSACTION_ID@ placeholder substituted the same way production's sed does, its absolute path
# roots mechanically redirected into a sandbox, and the real function body run under /bin/sh. No
# emulation is involved; the glob, the keep-set and the fail-closed journal parse are the shipped
# ones.
SWEEP_OWN_ID="aaaa1111aaaa1111aaaa1111aaaa1111"
SWEEP_SRC="$(awk '/^  cat > "\$transaction_file" <<'\''EOF'\''$/{f=1;next} f&&/^EOF$/{exit} f' "$PROVISION" \
  | sed -n '/^sweep_disposable_staging()/,/^}/p' \
  | sed -e "s/@TRANSACTION_ID@/$SWEEP_OWN_ID/g" \
        -e 's|/data/|${SWEEP_ROOT}/data/|g' -e 's|/system/|${SWEEP_ROOT}/system/|g')"
if printf '%s\n' "$SWEEP_SRC" 2>/dev/null | grep -q 'sweep_disposable_staging()' &&
   printf '%s\n' "$SWEEP_SRC" 2>/dev/null | grep -Fq "$SWEEP_OWN_ID"; then
  pass "the shipped sweep function was extracted and substituted (an empty harness must not pass by doing nothing)"
else
  fail_test "the shipped sweep function was extracted and substituted (an empty harness must not pass by doing nothing)"
fi

run_sweep() {
  SWEEP_ROOT="$1" transaction_id="$2" /bin/sh -uc "$SWEEP_SRC
sweep_disposable_staging"
}
mk_sweep_tree() {
  SWEEP_TREE="$TMP/sweep-tree"
  rm -rf "$SWEEP_TREE"
  mkdir -p "$SWEEP_TREE/data/local/tmp" "$SWEEP_TREE/data/adb/hapaneld" "$SWEEP_TREE/system/bin"
}
assert_swept() {
  if [ -e "$SWEEP_TREE/$1" ] || [ -L "$SWEEP_TREE/$1" ]; then fail_test "$2 (still present: $1)"
  else pass "$2"; fi
}
assert_kept() {
  if [ -e "$SWEEP_TREE/$1" ] || [ -L "$SWEEP_TREE/$1" ]; then pass "$2"
  else fail_test "$2 (missing: $1)"; fi
}

SWEEP_STALE_ID="bbbb2222bbbb2222bbbb2222bbbb2222"
SWEEP_ARG_ID="cccc3333cccc3333cccc3333cccc3333"
SWEEP_JOURNAL_ID="dddd4444dddd4444dddd4444dddd4444"
SWEEP_SHA="$(printf '%064d' 1)"

# Disposable, protected and namespaced artifacts side by side in one tree.
mk_sweep_tree
for suffix in '' .rc .txn; do : > "$SWEEP_TREE/data/local/tmp/hapaneld-helper-$SWEEP_STALE_ID$suffix"; done
: > "$SWEEP_TREE/data/adb/hapaneld/.helper-probe-$SWEEP_STALE_ID"
: > "$SWEEP_TREE/data/adb/hapaneld/.helper-transaction-$SWEEP_STALE_ID-$SWEEP_SHA"
: > "$SWEEP_TREE/data/adb/hapaneld/.helper-transaction-$SWEEP_STALE_ID-$SWEEP_SHA.new"
ln -s /nonexistent "$SWEEP_TREE/data/adb/hapaneld/.helper-probe-$SWEEP_JOURNAL_ID"
: > "$SWEEP_TREE/data/local/tmp/hapaneld-helper-$SWEEP_OWN_ID.txn"
: > "$SWEEP_TREE/data/local/tmp/hapaneld-helper-$SWEEP_ARG_ID"
: > "$SWEEP_TREE/data/local/tmp/hapaneld-helper.probe-$SWEEP_STALE_ID"
: > "$SWEEP_TREE/data/local/tmp/hapaneld-helper-deadbeef"
if run_sweep "$SWEEP_TREE" "$SWEEP_ARG_ID"; then pass "the shipped sweep runs cleanly"; else fail_test "the shipped sweep runs cleanly"; fi
assert_swept "data/local/tmp/hapaneld-helper-$SWEEP_STALE_ID" "a stale staged bundle is swept"
assert_swept "data/local/tmp/hapaneld-helper-$SWEEP_STALE_ID.rc" "a stale bundle sidecar is swept"
assert_swept "data/local/tmp/hapaneld-helper-$SWEEP_STALE_ID.txn" "a stale staged transaction script is swept"
assert_swept "data/adb/hapaneld/.helper-probe-$SWEEP_STALE_ID" "a stale probe is swept"
assert_swept "data/adb/hapaneld/.helper-transaction-$SWEEP_STALE_ID-$SWEEP_SHA" "a stale protected transaction record is swept"
assert_swept "data/adb/hapaneld/.helper-transaction-$SWEEP_STALE_ID-$SWEEP_SHA.new" "an interrupted promotion's .new leftover is swept"
assert_swept "data/adb/hapaneld/.helper-probe-$SWEEP_JOURNAL_ID" "a broken symlink still occupying a staging path is swept"
assert_kept "data/local/tmp/hapaneld-helper-$SWEEP_OWN_ID.txn" "the sweeping transaction's own staging is kept"
assert_kept "data/local/tmp/hapaneld-helper-$SWEEP_ARG_ID" "the identity named by the verb argument is kept"
assert_kept "data/local/tmp/hapaneld-helper.probe-$SWEEP_STALE_ID" "the standalone daemon installer's dot-separated staging is out of reach"
assert_kept "data/local/tmp/hapaneld-helper-deadbeef" "a name without a full 32-hex identity is left alone"

# A recovery journal protects the staging it references — on every journal marker path, including
# the /system one.
for journal_path in "data/adb/hapaneld/.helper-upgrade.marker" \
                    "data/adb/hapaneld/.helper-hybrid-upgrade.marker" \
                    "system/bin/.hapaneld-helper-upgrade"; do
  mk_sweep_tree
  printf 'JOURNAL_VERSION=1\nTRANSACTION_ID=%s\n' "$SWEEP_JOURNAL_ID" > "$SWEEP_TREE/$journal_path"
  : > "$SWEEP_TREE/data/local/tmp/hapaneld-helper-$SWEEP_JOURNAL_ID"
  : > "$SWEEP_TREE/data/local/tmp/hapaneld-helper-$SWEEP_STALE_ID"
  run_sweep "$SWEEP_TREE" "$SWEEP_ARG_ID" || true
  assert_kept "data/local/tmp/hapaneld-helper-$SWEEP_JOURNAL_ID" "staging referenced by the ${journal_path##*/} journal is kept"
  assert_swept "data/local/tmp/hapaneld-helper-$SWEEP_STALE_ID" "unreferenced staging is still swept while that journal exists"
done

# A journal whose identity cannot be read is a journal whose references are unknown: the sweep must
# refuse to act at all rather than guess.
mk_sweep_tree
printf 'JOURNAL_VERSION=1\nTRANSACTION_ID=not-a-valid-identity\n' > "$SWEEP_TREE/data/adb/hapaneld/.helper-upgrade.marker"
: > "$SWEEP_TREE/data/local/tmp/hapaneld-helper-$SWEEP_STALE_ID"
run_sweep "$SWEEP_TREE" "$SWEEP_ARG_ID" || true
assert_kept "data/local/tmp/hapaneld-helper-$SWEEP_STALE_ID" "a malformed journal identity aborts the whole sweep"

# A marker path that exists but is not a plain regular file aborts the sweep too — even a symlink
# pointing at a perfectly valid marker, because a journal is written as a root-owned regular file
# and anything else at that path is not a state the sweep may interpret.
mk_sweep_tree
printf 'JOURNAL_VERSION=1\nTRANSACTION_ID=%s\n' "$SWEEP_JOURNAL_ID" > "$SWEEP_TREE/data/adb/hapaneld/real-marker-target"
ln -s "$SWEEP_TREE/data/adb/hapaneld/real-marker-target" "$SWEEP_TREE/data/adb/hapaneld/.helper-upgrade.marker"
: > "$SWEEP_TREE/data/local/tmp/hapaneld-helper-$SWEEP_STALE_ID"
run_sweep "$SWEEP_TREE" "$SWEEP_ARG_ID" || true
assert_kept "data/local/tmp/hapaneld-helper-$SWEEP_STALE_ID" "a symlink journal marker aborts the whole sweep even when its target is valid"
mk_sweep_tree
mkdir "$SWEEP_TREE/data/adb/hapaneld/.helper-hybrid-upgrade.marker"
: > "$SWEEP_TREE/data/local/tmp/hapaneld-helper-$SWEEP_STALE_ID"
run_sweep "$SWEEP_TREE" "$SWEEP_ARG_ID" || true
assert_kept "data/local/tmp/hapaneld-helper-$SWEEP_STALE_ID" "a directory at a journal marker path aborts the whole sweep"

# ── #76: staged inputs are consumed before the first destructive step ───────────────────────────
# Both reclamation paths rest on this ordering: a verb that loses its staged inputs can only fail
# early and cleanly, never mid-mutation. Pinned against the shipped heredoc per mutating verb: the
# last @STAGED_*@ read must precede the first destructive operation — helper retirement (stop/pkill),
# any move onto a live path or journal marker (mv -f), any restore of recorded state
# (restore_or_remove), and any rm -f of a live path. Two rm -f forms are deliberately NOT
# destructive: removing `*.hapaneld-recovery` copies (they belong to an already-concluded
# transaction, and only after helper_journal_state proved no journal references them) and removing
# the verb's own `$probe` or transaction-identity-bound `$candidate` staging before re-creating it.
# Both line numbers must exist: a verb where either side is unmatched fails rather than passing
# vacuously.
DEVICE_HEREDOC="$TMP/device-heredoc.sh"
awk '/^  cat > "\$transaction_file" <<'\''EOF'\''$/{f=1;next} f&&/^EOF$/{exit} f' "$PROVISION" > "$DEVICE_HEREDOC"

# Execute the complete emitted script through its production lock -> sweep -> verb dispatch path.
# Direct function extraction above proves sweep semantics; this binds those semantics to the actual
# entry point so deleting or moving the production call cannot leave the suite green.
DISPATCH_SCRIPT="$TMP/device-dispatch.sh"
sed -e "s/@TRANSACTION_ID@/$SWEEP_OWN_ID/g" \
    -e 's|/data/|${SWEEP_ROOT}/data/|g' \
    -e 's|/system/|${SWEEP_ROOT}/system/|g' \
    -e 's|/vendor/|${SWEEP_ROOT}/vendor/|g' \
    -e 's|/dev/.hapaneld-helper-transaction.lock|${SWEEP_ROOT}/dev/.hapaneld-helper-transaction.lock|g' \
    "$DEVICE_HEREDOC" > "$DISPATCH_SCRIPT"
for app_hold_kind in system systemless hybrid; do
  app_hold_body="$(sed -n "/^install_${app_hold_kind}() {$/,/^}$/p" "$DEVICE_HEREDOC")"
  app_hold_admission_line="$(printf '%s\n' "$app_hold_body" | grep -n 'state=$(helper_journal_state)' | head -1 | cut -d: -f1)"
  app_hold_first_mutation_line="$(printf '%s\n' "$app_hold_body" | grep -En '^  (mount|mkdir|chown|chmod|cp|snapshot)' | head -1 | cut -d: -f1)"
  if [ -n "$app_hold_admission_line" ] && [ -n "$app_hold_first_mutation_line" ] && \
     [ "$app_hold_admission_line" -lt "$app_hold_first_mutation_line" ]; then
    pass "$app_hold_kind checks fixed app custody before its first topology mutation"
  else
    fail_test "$app_hold_kind checks fixed app custody before its first topology mutation"
  fi
done
if [ "$(grep -Fc 'APP_REPLACEMENT_HOLD' "$PROVISION")" -ge 3 ] && \
   grep -Fq 'APP_REPLACEMENT_HOLD)' "$PROVISION" && \
   grep -Eq 'APP_REPLACEMENT_HOLD\|TRANSACTION_BUSY|TRANSACTION_BUSY\|APP_REPLACEMENT_HOLD' "$PROVISION"; then
  pass "host provisioning treats fixed app custody as terminal before rollback"
else
  fail_test "host provisioning treats fixed app custody as terminal before rollback"
fi
mk_sweep_tree
mkdir -p "$SWEEP_TREE/dev" "$SWEEP_TREE/vendor"
: > "$SWEEP_TREE/data/local/tmp/hapaneld-helper-$SWEEP_STALE_ID"
: > "$SWEEP_TREE/data/local/tmp/hapaneld-helper-$SWEEP_OWN_ID"
: > "$SWEEP_TREE/data/local/tmp/hapaneld-helper-$SWEEP_ARG_ID"
dispatch_output="$TMP/device-dispatch-output"
if SWEEP_ROOT="$SWEEP_TREE" /bin/sh -u "$DISPATCH_SCRIPT" \
    discover-system "$SWEEP_ARG_ID" "$SWEEP_SHA" test-build "$SWEEP_SHA" \
    > "$dispatch_output" 2>&1; then
  pass "the complete emitted transaction script dispatches a production verb"
else
  LAST_OUTPUT="$dispatch_output"
  fail_test "the complete emitted transaction script dispatches a production verb"
fi
if [ "$(tail -n 1 "$dispatch_output")" = "LIVE_STATE=PRE_SWAP" ]; then
  pass "production dispatch executes the selected discover-system verb"
else
  LAST_OUTPUT="$dispatch_output"
  fail_test "production dispatch executes the selected discover-system verb"
fi
assert_swept "data/local/tmp/hapaneld-helper-$SWEEP_STALE_ID" "production transaction dispatch reaches the staging sweep"
assert_kept "data/local/tmp/hapaneld-helper-$SWEEP_OWN_ID" "production dispatch keeps the emitted transaction identity"
assert_kept "data/local/tmp/hapaneld-helper-$SWEEP_ARG_ID" "production dispatch keeps the argv transaction identity"
assert_swept "dev/.hapaneld-helper-transaction.lock" "production dispatch releases its transaction lock"

# BundledHelperInstaller publishes .new only after an identity-bound upload has been verified. If
# the app dies before publishing transaction authority, the next provisioner owns the shared lock
# and can safely discard both pre-authority files. Execute that reconciliation through the complete
# emitted script, not a shell model of it.
APP_STAGE_SHA="$SWEEP_SHA"
mk_sweep_tree
mkdir -p "$SWEEP_TREE/dev" "$SWEEP_TREE/vendor"
printf 'verified candidate\n' > "$SWEEP_TREE/data/local/.hapaneld-helper.new"
chmod 700 "$SWEEP_TREE/data/local/.hapaneld-helper.new"
printf 'partial upload\n' > "$SWEEP_TREE/data/local/.hapaneld-helper.app-stage-$APP_STAGE_SHA"
chmod 600 "$SWEEP_TREE/data/local/.hapaneld-helper.app-stage-$APP_STAGE_SHA"
printf 'foreign upload\n' > "$SWEEP_TREE/data/local/.hapaneld-helper.app-stage-not-a-sha"
chmod 600 "$SWEEP_TREE/data/local/.hapaneld-helper.app-stage-not-a-sha"
app_stage_output="$TMP/device-dispatch-app-stage-output"
SWEEP_ROOT="$SWEEP_TREE" /bin/sh -u "$DISPATCH_SCRIPT" \
  discover-system "$SWEEP_ARG_ID" "$SWEEP_SHA" test-build "$SWEEP_SHA" \
  > "$app_stage_output" 2>&1 || true
assert_swept "data/local/.hapaneld-helper.new" "production dispatch reclaims an exact authority-free app stage"
assert_swept "data/local/.hapaneld-helper.app-stage-$APP_STAGE_SHA" "production dispatch reclaims a strict authority-free app upload orphan"
assert_kept "data/local/.hapaneld-helper.app-stage-not-a-sha" "an upload outside the strict app-stage namespace is not guessed at"
if [ "$(tail -n 1 "$app_stage_output")" = "LIVE_STATE=PRE_SWAP" ]; then
  pass "an unowned upload orphan is never treated as replacement authority"
else
  LAST_OUTPUT="$app_stage_output"
  fail_test "an unowned upload orphan is never treated as replacement authority"
fi

# Every transaction-authority namespace fences .new reclamation. This includes native Guard
# replacement, retained legacy takeover, previous-byte custody, all three APK-coupled journal
# locations, and all three historical/canonical standalone journal locations.
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
  mk_sweep_tree
  mkdir -p "$SWEEP_TREE/dev" "$SWEEP_TREE/vendor" "$(dirname "$SWEEP_TREE/$app_authority")"
  printf 'verified candidate\n' > "$SWEEP_TREE/data/local/.hapaneld-helper.new"
  chmod 700 "$SWEEP_TREE/data/local/.hapaneld-helper.new"
  : > "$SWEEP_TREE/$app_authority"
  SWEEP_ROOT="$SWEEP_TREE" /bin/sh -u "$DISPATCH_SCRIPT" \
    discover-system "$SWEEP_ARG_ID" "$SWEEP_SHA" test-build "$SWEEP_SHA" \
    > "$TMP/device-dispatch-authority-output" 2>&1 || true
  assert_kept "data/local/.hapaneld-helper.new" "${app_authority#/} authority preserves app replacement staging"
done

# A specific provisioner or standalone journal remains the diagnostic authority even when fixed
# app staging is also present. Generic app custody must never hide the owning recovery path.
for app_precedence_case in \
    'system/bin/.hapaneld-helper-upgrade|STALE_SYSTEM_TRANSACTION' \
    'data/adb/hapaneld/.helper-upgrade.marker|STALE_SYSTEMLESS_TRANSACTION' \
    'data/adb/hapaneld/.helper-hybrid-upgrade.marker|STALE_HYBRID_TRANSACTION' \
    'data/local/.hapaneld-helper-manual-upgrade|FOREIGN_MANUAL_TRANSACTION' \
    'system/bin/.hapaneld-helper-manual-upgrade|FOREIGN_MANUAL_TRANSACTION' \
    'data/adb/hapaneld/.helper-manual-upgrade.marker|FOREIGN_MANUAL_TRANSACTION'; do
  app_precedence_path=${app_precedence_case%%|*}
  app_precedence_expected=${app_precedence_case#*|}
  mk_sweep_tree
  mkdir -p "$SWEEP_TREE/dev" "$SWEEP_TREE/vendor" "$(dirname "$SWEEP_TREE/$app_precedence_path")"
  printf 'fixed candidate\n' > "$SWEEP_TREE/data/local/.hapaneld-helper.new"
  chmod 700 "$SWEEP_TREE/data/local/.hapaneld-helper.new"
  : > "$SWEEP_TREE/$app_precedence_path"
  app_precedence_output="$TMP/device-dispatch-precedence-${app_precedence_path##*/}-output"
  if SWEEP_ROOT="$SWEEP_TREE" /bin/sh -u "$DISPATCH_SCRIPT" \
      install-systemless "$SWEEP_ARG_ID" "$SWEEP_SHA" test-build "$SWEEP_SHA" \
      > "$app_precedence_output" 2>&1; then
    app_precedence_status=0
  else
    app_precedence_status=$?
  fi
  if [ "$app_precedence_status" -eq 2 ] && \
     [ "$(cat "$app_precedence_output")" = "$app_precedence_expected" ]; then
    pass "$app_precedence_path takes precedence over generic app custody"
  else
    LAST_OUTPUT="$app_precedence_output"
    fail_test "$app_precedence_path takes precedence over generic app custody"
  fi
  assert_kept "data/local/.hapaneld-helper.new" "$app_precedence_path precedence preserves fixed app custody"
done

# A stage outside the exact root-owned, regular, one-link, 0700, 1..16MiB envelope is foreign. It
# remains visible to app_replacement_custody_present and therefore holds topology instead of being
# silently deleted.
for malformed_stage in empty wrong-mode wrong-owner hardlink symlink oversized; do
  mk_sweep_tree
  mkdir -p "$SWEEP_TREE/dev" "$SWEEP_TREE/vendor"
  stage_path="$SWEEP_TREE/data/local/.hapaneld-helper.new"
  case "$malformed_stage" in
    empty) : > "$stage_path"; chmod 700 "$stage_path" ;;
    wrong-mode) printf x > "$stage_path"; chmod 600 "$stage_path" ;;
    wrong-owner) printf x > "$stage_path"; chmod 700 "$stage_path"; chown 1:1 "$stage_path" ;;
    hardlink) printf x > "$stage_path"; chmod 700 "$stage_path"; ln "$stage_path" "$SWEEP_TREE/data/local/stage-second-link" ;;
    symlink) ln -s /nonexistent "$stage_path" ;;
    oversized) truncate -s 16777217 "$stage_path"; chmod 700 "$stage_path" ;;
  esac
  malformed_output="$TMP/device-dispatch-malformed-$malformed_stage-output"
  SWEEP_ROOT="$SWEEP_TREE" /bin/sh -u "$DISPATCH_SCRIPT" \
    discover-system "$SWEEP_ARG_ID" "$SWEEP_SHA" test-build "$SWEEP_SHA" \
    > "$malformed_output" 2>&1 || true
  assert_kept "data/local/.hapaneld-helper.new" "$malformed_stage app stage is preserved as foreign custody"
  if [ "$(tail -n 1 "$malformed_output")" = "LIVE_STATE=TOPOLOGY_HOLD" ]; then
    pass "$malformed_stage app stage fails closed as replacement custody"
  else
    LAST_OUTPUT="$malformed_output"
    fail_test "$malformed_stage app stage fails closed as replacement custody"
  fi
done

# Fixed app custody is an install admission boundary, not merely a discovery label. Every layout
# must refuse before remounts, target-directory creation, candidate staging, snapshots, or journals.
for app_hold_kind in system systemless hybrid; do
  mk_sweep_tree
  mkdir -p "$SWEEP_TREE/dev" "$SWEEP_TREE/vendor"
  : > "$SWEEP_TREE/data/local/.hapaneld-helper.new"
  chmod 700 "$SWEEP_TREE/data/local/.hapaneld-helper.new"
  app_hold_output="$TMP/device-dispatch-app-hold-$app_hold_kind-output"
  if SWEEP_ROOT="$SWEEP_TREE" /bin/sh -u "$DISPATCH_SCRIPT" \
      "install-$app_hold_kind" "$SWEEP_ARG_ID" "$SWEEP_SHA" test-build "$SWEEP_SHA" \
      > "$app_hold_output" 2>&1; then
    app_hold_status=0
  else
    app_hold_status=$?
  fi
  if [ "$app_hold_status" -eq 2 ] && [ "$(cat "$app_hold_output")" = APP_REPLACEMENT_HOLD ]; then
    pass "$app_hold_kind install refuses fixed app custody with its exact terminal state"
  else
    LAST_OUTPUT="$app_hold_output"
    fail_test "$app_hold_kind install refuses fixed app custody with status 2"
  fi
  assert_kept "data/local/.hapaneld-helper.new" "$app_hold_kind admission preserves fixed app custody"
  if [ ! -e "$SWEEP_TREE/data/local/.hapaneld-helper.provision-$SWEEP_ARG_ID" ] && \
     [ ! -e "$SWEEP_TREE/system/etc/init/hapaneld-helper.rc.new" ] && \
     [ ! -e "$SWEEP_TREE/vendor/etc/init/hapaneld-helper.rc.new" ] && \
     [ ! -e "$SWEEP_TREE/data/adb/service.d/hapaneld-helper.sh.new" ] && \
     [ ! -e "$SWEEP_TREE/system/bin/.hapaneld-helper-upgrade" ] && \
     [ ! -e "$SWEEP_TREE/data/adb/hapaneld/.helper-upgrade.marker" ] && \
     [ ! -e "$SWEEP_TREE/data/adb/hapaneld/.helper-hybrid-upgrade.marker" ]; then
    pass "$app_hold_kind admission performs no helper or journal staging"
  else
    fail_test "$app_hold_kind admission performs no helper or journal staging"
  fi
done

# Exact bytes and metadata are not enough to make an authority-free stage disposable: an executing
# inode is live foreign custody. Reconciliation must preserve both it and its pathname without
# inventing transaction authority.
mk_sweep_tree
mkdir -p "$SWEEP_TREE/dev" "$SWEEP_TREE/vendor"
stage_path="$SWEEP_TREE/data/local/.hapaneld-helper.new"
if cp /bin/sleep "$stage_path" && chmod 700 "$stage_path"; then
  pass "executing authority-free stage fixture publishes exact executable bytes"
else
  fail_test "executing authority-free stage fixture publishes exact executable bytes"
fi
"$stage_path" 60 &
app_stage_pid=$!
app_stage_inode="$(stat -c '%d:%i' "$stage_path")"
app_stage_wait=0
app_stage_process_inode=
while [ "$app_stage_wait" -lt 100 ]; do
  app_stage_process_inode="$(stat -Lc '%d:%i' "/proc/$app_stage_pid/exe" 2>/dev/null || true)"
  [ "$app_stage_process_inode" != "$app_stage_inode" ] || break
  sleep 0.01
  app_stage_wait=$((app_stage_wait + 1))
done
if [ "$app_stage_process_inode" = "$app_stage_inode" ]; then
  pass "executing authority-free stage fixture reaches a live exact inode"
else
  fail_test "executing authority-free stage fixture reaches a live exact inode"
fi
app_stage_running_output="$TMP/device-dispatch-running-app-stage-output"
SWEEP_ROOT="$SWEEP_TREE" /bin/sh -u "$DISPATCH_SCRIPT" \
  discover-system "$SWEEP_ARG_ID" "$SWEEP_SHA" test-build "$SWEEP_SHA" \
  > "$app_stage_running_output" 2>&1 || true
assert_kept "data/local/.hapaneld-helper.new" "an executing authority-free stage is never reclaimed"
if kill -0 "$app_stage_pid" 2>/dev/null; then
  pass "authority-free stage reconciliation preserves the executing inode"
else
  fail_test "authority-free stage reconciliation preserves the executing inode"
fi
if [ "$(tail -n 1 "$app_stage_running_output")" = LIVE_STATE=TOPOLOGY_HOLD ]; then
  pass "an executing authority-free stage remains visible as topology custody"
else
  LAST_OUTPUT="$app_stage_running_output"
  fail_test "an executing authority-free stage remains visible as topology custody"
fi
app_stage_install_output="$TMP/device-dispatch-running-app-stage-install-output"
if SWEEP_ROOT="$SWEEP_TREE" /bin/sh -u "$DISPATCH_SCRIPT" \
    install-systemless "$SWEEP_ARG_ID" "$SWEEP_SHA" test-build "$SWEEP_SHA" \
    > "$app_stage_install_output" 2>&1; then
  app_stage_install_status=0
else
  app_stage_install_status=$?
fi
if [ "$app_stage_install_status" -eq 2 ] && \
   [ "$(cat "$app_stage_install_output")" = APP_REPLACEMENT_HOLD ]; then
  pass "an executing authority-free stage blocks install before mutation"
else
  LAST_OUTPUT="$app_stage_install_output"
  fail_test "an executing authority-free stage blocks install before mutation"
fi
kill "$app_stage_pid" 2>/dev/null || true
wait "$app_stage_pid" 2>/dev/null || true
unset APP_STAGE_SHA app_stage_output app_authority malformed_stage stage_path malformed_output \
  app_stage_pid app_stage_inode app_stage_wait app_stage_process_inode app_stage_running_output

# Exercise retained legacy-takeover normalization through the complete emitted provisioner script.
# Two tiny executables provide real, distinct /proc/$pid/exe inodes. The candidate deliberately
# reports GUARD_ARMED from --replacement-safe while its server owns the simulated Guard lock, so a
# pre-stop safety probe would wedge this success case; only the daemon-down probe can return safe.
LEGACY_FIXTURE_ROOT="$TMP/legacy-normalization"
LEGACY_FIXTURE_C="$TMP/legacy-fixture.c"
LEGACY_FIXTURE_CANDIDATE="$TMP/legacy-candidate"
LEGACY_FIXTURE_OLD="$TMP/legacy-old"
legacy_assert() {
  local description="$1"
  shift
  if "$@"; then pass "$description"; else fail_test "$description"; return 1; fi
}
legacy_assert_not() {
  local description="$1"
  shift
  if "$@"; then fail_test "$description"; return 1; else pass "$description"; fi
}
legacy_assert_present() {
  local path="$1" description="$2"
  if [ -e "$path" ] || [ -L "$path" ]; then pass "$description"; else fail_test "$description"; return 1; fi
}
legacy_assert_absent() {
  local path="$1" description="$2"
  if [ ! -e "$path" ] && [ ! -L "$path" ]; then pass "$description"; else fail_test "$description"; return 1; fi
}
legacy_assert_dispatch_success() {
  local output="$1" description="$2"
  if legacy_run_dispatch > "$output" 2>&1; then pass "$description"
  else LAST_OUTPUT="$output"; fail_test "$description"; return 1; fi
}
legacy_assert_dispatch_failure() {
  local output="$1" description="$2" status
  if legacy_run_dispatch > "$output" 2>&1; then
    LAST_OUTPUT="$output"
    fail_test "$description"
    return 1
  else
    status=$?
  fi
  if [ "$status" -eq 75 ] && [ "$(tail -n 1 "$output")" = LEGACY_TAKEOVER_HOLD ]; then
    pass "$description"
  else
    LAST_OUTPUT="$output"
    fail_test "$description (got status $status without exact HOLD)"
    return 1
  fi
}
legacy_assert_status() {
  local actual="$1" expected="$2" description="$3"
  if [ "$actual" -eq "$expected" ]; then pass "$description"
  else fail_test "$description (got $actual, expected $expected)"; return 1; fi
}
legacy_pid_alive() { kill -0 "$1" 2>/dev/null; }
cat > "$LEGACY_FIXTURE_C" <<'EOF'
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
static void stop_server(int signal_number) {
    (void)signal_number;
    stop_requested = 1;
}
static int ready_file(const char *name) {
    const char *root = getenv("LEGACY_FIXTURE_ROOT");
    if (!root) return 0;
    snprintf(ready_path, sizeof(ready_path), "%s/%s", root, name);
    return access(ready_path, F_OK) == 0;
}
static int serve(const char *name) {
    const char *root = getenv("LEGACY_FIXTURE_ROOT");
    if (!root) return 1;
    if (!CANDIDATE_ROLE && getenv("LEGACY_OLD_START_FAIL")) return 1;
    snprintf(ready_path, sizeof(ready_path), "%s/%s", root, name);
    FILE *ready = fopen(ready_path, "w");
    if (!ready) return 1;
    fclose(ready);
    signal(SIGTERM, stop_server);
    signal(SIGINT, stop_server);
    signal(SIGHUP, stop_server);
    while (!stop_requested) pause();
    if (CANDIDATE_ROLE && getenv("LEGACY_CANDIDATE_STOP_DELAY")) {
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
        if (ready_file("candidate-ready") || getenv("LEGACY_POST_SAFE_ARMED")) {
            puts("GUARD_ARMED");
            return 3;
        }
        puts("REPLACE_SAFE");
        return 0;
    }
    if (argc == 3 && strcmp(argv[1], "--request") == 0) {
        if (!CANDIDATE_ROLE && strcmp(argv[2], "BUILDID") == 0 && ready_file("old-ready")) {
            printf("BUILDID %s\n", getenv("LEGACY_OLD_BUILD"));
            return 0;
        }
        if (CANDIDATE_ROLE && strcmp(argv[2], "GUARDSELF") == 0 && ready_file("candidate-ready")) {
            printf("OK GUARDSELF 1 %s %s %s\n", getenv("LEGACY_CANDIDATE_BYTES"),
                   getenv("LEGACY_CANDIDATE_SHA"), getenv("LEGACY_CANDIDATE_BUILD"));
            return 0;
        }
        return 1;
    }
    return serve(CANDIDATE_ROLE ? "candidate-ready" : "old-ready");
}
EOF
legacy_assert "legacy fixture compiles the candidate executable" \
  cc -O2 -DCANDIDATE_ROLE=1 "$LEGACY_FIXTURE_C" -o "$LEGACY_FIXTURE_CANDIDATE"
legacy_assert "legacy fixture compiles the distinct released executable" \
  cc -O2 -DCANDIDATE_ROLE=0 "$LEGACY_FIXTURE_C" -o "$LEGACY_FIXTURE_OLD"

legacy_stop_fixture_path() {
  local path="$1" inode executable executable_inode pid
  [ -f "$path" ] || return 0
  inode="$(stat -c '%d:%i' "$path")" || return 1
  for executable in /proc/[0-9]*/exe; do
    executable_inode="$(stat -Lc '%d:%i' "$executable" 2>/dev/null || true)"
    [ "$executable_inode" != "$inode" ] || { pid=${executable#/proc/}; pid=${pid%/exe}; kill "$pid" 2>/dev/null || true; }
  done
}
legacy_cleanup_fixture() {
  legacy_stop_fixture_path "$LEGACY_FIXTURE_ROOT/data/local/hapaneld-helper" || return 1
  legacy_stop_fixture_path "$LEGACY_FIXTURE_ROOT/data/local/.hapaneld-helper.new" || return 1
  legacy_stop_fixture_path "$LEGACY_FIXTURE_ROOT/system/bin/hapaneld-helper" || return 1
  legacy_stop_fixture_path "$LEGACY_FIXTURE_ROOT/data/adb/hapaneld/hapaneld-helper" || return 1
  sleep 0.05
}
legacy_fixture_processes() {
  local path="$1" inode executable executable_inode found=
  [ -f "$path" ] || return 0
  inode="$(stat -c '%d:%i' "$path")" || return 1
  for executable in /proc/[0-9]*/exe; do
    executable_inode="$(stat -Lc '%d:%i' "$executable" 2>/dev/null || true)"
    [ "$executable_inode" != "$inode" ] || found="$found ${executable#/proc/}"
  done
  printf '%s\n' "$found"
}
legacy_write_registration() {
  local topology="$1" registration
  case "$topology" in
    system)
      registration="$LEGACY_FIXTURE_ROOT/system/etc/init/hapaneld-helper.rc"
      mkdir -p "${registration%/*}" || return 1
      cat > "$registration" <<'EOF' || return 1
service hapaneld_helper /system/bin/hapaneld-helper
    class main
    user root
    group root
    seclabel u:r:su:s0
EOF
      LEGACY_REGISTRATION_MODE=644
      LEGACY_EXPECTED_REGISTRATION_SHA=9b430712c493df177a19e5e893df445f6c2e951fc30ea140dcdbcdb7987de659 ;;
    systemless)
      registration="$LEGACY_FIXTURE_ROOT/data/adb/service.d/hapaneld-helper.sh"
      mkdir -p "${registration%/*}" || return 1
      cat > "$registration" <<'EOF' || return 1
#!/system/bin/sh
while [ "$(getprop sys.boot_completed)" != "1" ]; do sleep 3; done
/system/bin/stop hapaneld_helper 2>/dev/null
/system/bin/stop hapaneld_ledd 2>/dev/null
/system/bin/pkill -x hapaneld-helper 2>/dev/null
/system/bin/pkill -x hapaneld-ledd 2>/dev/null
/data/adb/hapaneld/hapaneld-helper >/dev/null 2>&1 &
EOF
      LEGACY_REGISTRATION_MODE=755
      LEGACY_EXPECTED_REGISTRATION_SHA=60ff22aa9b38483cbffd95a653d804d0d9abf682e1b952e8b4519d5c0f3f9493 ;;
    hybrid)
      registration="$LEGACY_FIXTURE_ROOT/vendor/etc/init/hapaneld-helper.rc"
      mkdir -p "${registration%/*}" || return 1
      cat > "$registration" <<'EOF' || return 1
service hapaneld_helper /data/adb/hapaneld/hapaneld-helper
    class main
    user root
    group root
    seclabel u:r:su:s0
EOF
      LEGACY_REGISTRATION_MODE=644
      LEGACY_EXPECTED_REGISTRATION_SHA=cf146dd5320fcb017514def6295fdb0c473e150a478d5c2219af2e3f03826ed1 ;;
  esac
  chmod "$LEGACY_REGISTRATION_MODE" "$registration" || return 1
  LEGACY_REGISTRATION="$registration"
  LEGACY_REGISTRATION_SHA="$(/usr/bin/sha256sum "$registration" | awk '{print $1}')" || return 1
  [ "$LEGACY_REGISTRATION_SHA" = "$LEGACY_EXPECTED_REGISTRATION_SHA" ]
}
legacy_prepare_fixture() {
  local topology="$1" candidate_location="${2:-live}" old_bin
  legacy_cleanup_fixture || return 1
  rm -rf "$LEGACY_FIXTURE_ROOT" || return 1
  mkdir -p "$LEGACY_FIXTURE_ROOT/dev" "$LEGACY_FIXTURE_ROOT/data/local" \
    "$LEGACY_FIXTURE_ROOT/data/adb/hapaneld" "$LEGACY_FIXTURE_ROOT/system/bin" \
    "$LEGACY_FIXTURE_ROOT/vendor" || return 1
  legacy_write_registration "$topology" || return 1
  case "$topology" in
    system) old_bin="$LEGACY_FIXTURE_ROOT/system/bin/hapaneld-helper" ;;
    systemless|hybrid) old_bin="$LEGACY_FIXTURE_ROOT/data/adb/hapaneld/hapaneld-helper" ;;
  esac
  cp "$LEGACY_FIXTURE_OLD" "$old_bin" || return 1
  chmod 755 "$old_bin" || return 1
  LEGACY_FIXTURE_OLD_BIN="$old_bin"
  if [ "$candidate_location" = stage ]; then
    LEGACY_FIXTURE_CANDIDATE_PATH="$LEGACY_FIXTURE_ROOT/data/local/.hapaneld-helper.new"
  else
    LEGACY_FIXTURE_CANDIDATE_PATH="$LEGACY_FIXTURE_ROOT/data/local/hapaneld-helper"
  fi
  cp "$LEGACY_FIXTURE_CANDIDATE" "$LEGACY_FIXTURE_CANDIDATE_PATH" || return 1
  chmod 700 "$LEGACY_FIXTURE_CANDIDATE_PATH" || return 1
  LEGACY_OLD_SHA="$(/usr/bin/sha256sum "$old_bin" | awk '{print $1}')" || return 1
  LEGACY_OLD_BYTES="$(wc -c < "$old_bin")" || return 1
  LEGACY_CANDIDATE_SHA="$(/usr/bin/sha256sum "$LEGACY_FIXTURE_CANDIDATE_PATH" | awk '{print $1}')" || return 1
  LEGACY_CANDIDATE_BYTES="$(wc -c < "$LEGACY_FIXTURE_CANDIDATE_PATH")" || return 1
  LEGACY_REGISTRATION_BYTES="$(wc -c < "$LEGACY_REGISTRATION")" || return 1
  LEGACY_OLD_BUILD="$(printf 'a%.0s' {1..64})" || return 1
  LEGACY_CANDIDATE_BUILD="$(printf 'b%.0s' {1..64})" || return 1
  export LEGACY_FIXTURE_ROOT LEGACY_FIXTURE_OLD_BIN LEGACY_OLD_BUILD LEGACY_CANDIDATE_BUILD \
    LEGACY_CANDIDATE_SHA LEGACY_CANDIDATE_BYTES
  cat > "$LEGACY_FIXTURE_ROOT/system/bin/start" <<'EOF' || return 1
#!/bin/sh
"$LEGACY_FIXTURE_OLD_BIN" >/dev/null 2>&1 &
EOF
  chmod 755 "$LEGACY_FIXTURE_ROOT/system/bin/start" || return 1
  printf 'OK LEGACYTAKEOVER 1 %s %s %s %s %s %s %s %s %s %s\n' \
    "$topology" "$LEGACY_OLD_SHA" "$LEGACY_OLD_BYTES" "$LEGACY_REGISTRATION_SHA" \
    "$LEGACY_REGISTRATION_BYTES" "$LEGACY_REGISTRATION_MODE" "$LEGACY_OLD_BUILD" \
    "$LEGACY_CANDIDATE_BUILD" "$LEGACY_CANDIDATE_SHA" "$LEGACY_CANDIDATE_BYTES" \
    > "$LEGACY_FIXTURE_ROOT/data/local/.hapaneld-helper.legacy-takeover" || return 1
  chmod 600 "$LEGACY_FIXTURE_ROOT/data/local/.hapaneld-helper.legacy-takeover" || return 1
}
legacy_run_dispatch() {
  PATH=/usr/bin:/bin SWEEP_ROOT="$LEGACY_FIXTURE_ROOT" /bin/sh -u "$DISPATCH_SCRIPT" \
    discover-system "$SWEEP_ARG_ID" "$SWEEP_SHA" test-build "$SWEEP_SHA"
}
legacy_wait_ready() {
  local marker="$1" attempt=0
  while [ ! -f "$marker" ]; do attempt=$((attempt + 1)); [ "$attempt" -lt 1000 ] || return 1; sleep 0.01; done
}

for legacy_topology in system systemless hybrid; do
  legacy_assert "$legacy_topology legacy fixture binds canonical registration hashes and executable bytes" \
    legacy_prepare_fixture "$legacy_topology" live
  "$LEGACY_FIXTURE_CANDIDATE_PATH" --supervise >/dev/null 2>&1 &
  legacy_candidate_pid=$!
  legacy_assert "$legacy_topology live candidate fixture becomes serving" \
    legacy_wait_ready "$LEGACY_FIXTURE_ROOT/candidate-ready"
  legacy_output="$TMP/legacy-$legacy_topology-output"
  legacy_assert_dispatch_success "$legacy_output" "$legacy_topology live takeover normalizes through production dispatch"
  legacy_assert_absent "$LEGACY_FIXTURE_ROOT/data/local/.hapaneld-helper.legacy-takeover" \
    "$legacy_topology success retires exact takeover authority"
  legacy_assert_not "$legacy_topology success drains the retained candidate PID" \
    legacy_pid_alive "$legacy_candidate_pid"
  legacy_assert "$legacy_topology success starts and verifies exact old authority" \
    legacy_wait_ready "$LEGACY_FIXTURE_ROOT/old-ready"
  legacy_assert_not "$legacy_topology owner-lock fixture never returns takeover HOLD" \
    grep -q LEGACY_TAKEOVER_HOLD "$legacy_output"
  legacy_assert "$legacy_topology success fixture cleanup completes" legacy_cleanup_fixture
done

# SIGKILL after exact old start/verification but before record unlink leaves the durable record as
# the retry authority. Re-entry sees zero candidate processes, revalidates through the candidate
# CLI while the Guard-less released old daemon serves, and completes on every released topology.
LEGACY_PRE_UNLINK_SCRIPT="$TMP/device-dispatch-legacy-pre-unlink.sh"
if sed '/^  legacy_record_after=$(app_stage_metadata "$legacy_record")/i\
: > "$LEGACY_FIXTURE_ROOT/pre-unlink-ready"\
while [ ! -e "$LEGACY_FIXTURE_ROOT/pre-unlink-release" ]; do sleep 1; done
' "$DISPATCH_SCRIPT" > "$LEGACY_PRE_UNLINK_SCRIPT"; then
  pass "legacy pre-unlink cut script is generated from production dispatch"
else
  fail_test "legacy pre-unlink cut script is generated from production dispatch"
fi
for legacy_topology in system systemless hybrid; do
  legacy_assert "$legacy_topology pre-unlink fixture prepares exact custody" \
    legacy_prepare_fixture "$legacy_topology" live
  "$LEGACY_FIXTURE_CANDIDATE_PATH" --supervise >/dev/null 2>&1 &
  legacy_assert "$legacy_topology pre-unlink fixture starts its candidate" \
    legacy_wait_ready "$LEGACY_FIXTURE_ROOT/candidate-ready"
  PATH=/usr/bin:/bin SWEEP_ROOT="$LEGACY_FIXTURE_ROOT" /bin/sh -u "$LEGACY_PRE_UNLINK_SCRIPT" \
    discover-system "$SWEEP_ARG_ID" "$SWEEP_SHA" test-build "$SWEEP_SHA" \
    > "$TMP/legacy-pre-unlink-$legacy_topology-output" 2>&1 &
  legacy_cut_pid=$!
  legacy_assert "$legacy_topology pre-unlink fixture reaches the durable replay cut" \
    legacy_wait_ready "$LEGACY_FIXTURE_ROOT/pre-unlink-ready"
  legacy_assert "$legacy_topology pre-unlink fixture accepts SIGKILL" kill -KILL "$legacy_cut_pid"
  if wait "$legacy_cut_pid" 2>/dev/null; then legacy_cut_status=0; else legacy_cut_status=$?; fi
  legacy_assert_status "$legacy_cut_status" 137 "$legacy_topology pre-unlink cut exits from SIGKILL"
  legacy_assert_present "$LEGACY_FIXTURE_ROOT/data/local/.hapaneld-helper.legacy-takeover" \
    "$legacy_topology pre-unlink cut retains exact authority"
  legacy_assert "$legacy_topology pre-unlink cut leaves the released helper serving" \
    legacy_wait_ready "$LEGACY_FIXTURE_ROOT/old-ready"
  legacy_assert_dispatch_success "$TMP/legacy-pre-unlink-$legacy_topology-retry-output" \
    "$legacy_topology pre-unlink cut replays through ordinary dispatch"
  legacy_assert_absent "$LEGACY_FIXTURE_ROOT/data/local/.hapaneld-helper.legacy-takeover" \
    "$legacy_topology replay retires exact authority"
  legacy_assert "$legacy_topology pre-unlink fixture cleanup succeeds" legacy_cleanup_fixture
done

# A signal delivered while the original candidate is still draining must not launch against that
# dying supervisor and accept its stale GUARDSELF response. Recovery waits for the exact inode to
# disappear, starts one replacement, verifies it, keeps authority, and releases shared custody.
legacy_assert "delayed-drain signal fixture prepares exact live custody" \
  legacy_prepare_fixture system live
export LEGACY_CANDIDATE_STOP_DELAY=1
"$LEGACY_FIXTURE_CANDIDATE_PATH" --supervise >/dev/null 2>&1 &
legacy_candidate_pid=$!
legacy_assert "delayed-drain signal fixture starts its retained candidate" \
  legacy_wait_ready "$LEGACY_FIXTURE_ROOT/candidate-ready"
PATH=/usr/bin:/bin SWEEP_ROOT="$LEGACY_FIXTURE_ROOT" /bin/sh -u "$DISPATCH_SCRIPT" \
  discover-system "$SWEEP_ARG_ID" "$SWEEP_SHA" test-build "$SWEEP_SHA" \
  > "$TMP/legacy-candidate-drain-signal-output" 2>&1 &
legacy_signal_pid=$!
legacy_assert "delayed-drain fixture reaches the dying-original interval" \
  legacy_wait_ready "$LEGACY_FIXTURE_ROOT/candidate-stopping"
legacy_assert "delayed-drain normalizer accepts TERM" kill -TERM "$legacy_signal_pid"
if wait "$legacy_signal_pid"; then legacy_signal_status=0; else legacy_signal_status=$?; fi
legacy_assert_status "$legacy_signal_status" 143 "delayed-drain TERM exits normalization nonzero"
legacy_assert_present "$LEGACY_FIXTURE_ROOT/data/local/.hapaneld-helper.legacy-takeover" \
  "delayed-drain TERM retains exact takeover authority"
legacy_assert_absent "$LEGACY_FIXTURE_ROOT/dev/.hapaneld-helper-transaction.lock" \
  "delayed-drain TERM releases shared transaction custody"
legacy_assert_not "delayed-drain recovery retires the original candidate PID" \
  legacy_pid_alive "$legacy_candidate_pid"
legacy_assert "delayed-drain recovery starts a verified candidate" \
  legacy_wait_ready "$LEGACY_FIXTURE_ROOT/candidate-ready"
legacy_recovered_processes="$(legacy_fixture_processes "$LEGACY_FIXTURE_CANDIDATE_PATH")"
legacy_assert "delayed-drain recovery leaves exactly one canonical candidate process" \
  test "$(printf '%s\n' "$legacy_recovered_processes" | wc -w)" -eq 1
unset LEGACY_CANDIDATE_STOP_DELAY
legacy_assert "delayed-drain fixture clears its synthetic cut marker" \
  rm -f "$LEGACY_FIXTURE_ROOT/candidate-stopping"
legacy_assert_dispatch_success "$TMP/legacy-candidate-drain-retry-output" \
  "delayed-drain authority permits an ordinary normalization retry"
legacy_assert_absent "$LEGACY_FIXTURE_ROOT/data/local/.hapaneld-helper.legacy-takeover" \
  "delayed-drain retry retires exact takeover authority"
legacy_assert "delayed-drain fixture cleanup succeeds" legacy_cleanup_fixture

# Stage-only publication and both lone tmp cuts are preauthority: old init/process ownership is
# established, then the record/tmp and stage are durably reclaimed.
legacy_assert "stage-only app-death fixture prepares exact custody" legacy_prepare_fixture system stage
legacy_assert_dispatch_success "$TMP/legacy-stage-only-output" \
  "stage-only app-death cut normalizes through production dispatch"
legacy_assert_absent "$LEGACY_FIXTURE_ROOT/data/local/.hapaneld-helper.legacy-takeover" \
  "stage-only app-death cut retires exact takeover authority"
legacy_assert_absent "$LEGACY_FIXTURE_ROOT/data/local/.hapaneld-helper.new" \
  "stage-only app-death cut reclaims the authority-free candidate"
legacy_assert "stage-only app-death fixture cleanup succeeds" legacy_cleanup_fixture

# No valid app publication cut executes .new. An exact staged process is foreign live custody: do
# not stop it, retire its record, or mutate either candidate pathname.
legacy_assert "executing-stage fixture prepares exact staged custody" legacy_prepare_fixture system stage
"$LEGACY_FIXTURE_CANDIDATE_PATH" --supervise >/dev/null 2>&1 &
legacy_stage_pid=$!
legacy_assert "executing-stage fixture starts the foreign staged process" \
  legacy_wait_ready "$LEGACY_FIXTURE_ROOT/candidate-ready"
legacy_assert_dispatch_failure "$TMP/legacy-running-stage-output" \
  "an executing app stage is refused without normalization"
legacy_assert "executing-stage HOLD preserves the staged process" legacy_pid_alive "$legacy_stage_pid"
legacy_assert_present "$LEGACY_FIXTURE_ROOT/data/local/.hapaneld-helper.legacy-takeover" \
  "executing-stage HOLD preserves takeover authority"
legacy_assert_present "$LEGACY_FIXTURE_ROOT/data/local/.hapaneld-helper.new" \
  "executing-stage HOLD preserves candidate bytes"
legacy_assert "executing-stage fixture cleanup succeeds" legacy_cleanup_fixture

# TERM during the candidate-down phase must retain authority, release the shared lock, and restore
# the exact prior candidate because post-stop REPLACE_SAFE has not yet succeeded. The ordinary retry
# then normalizes it cleanly.
LEGACY_CANDIDATE_DOWN_SCRIPT="$TMP/device-dispatch-legacy-candidate-down.sh"
if sed '/^  legacy_safe_reply=$("$legacy_candidate_source" --replacement-safe/i\
: > "$LEGACY_FIXTURE_ROOT/candidate-down-ready"\
while [ ! -e "$LEGACY_FIXTURE_ROOT/candidate-down-release" ]; do sleep 1; done
' "$DISPATCH_SCRIPT" > "$LEGACY_CANDIDATE_DOWN_SCRIPT"; then
  pass "candidate-down cut script is generated from production dispatch"
else
  fail_test "candidate-down cut script is generated from production dispatch"
fi
legacy_assert "candidate-down signal fixture prepares exact live custody" legacy_prepare_fixture system live
"$LEGACY_FIXTURE_CANDIDATE_PATH" --supervise >/dev/null 2>&1 &
legacy_assert "candidate-down signal fixture starts its retained candidate" \
  legacy_wait_ready "$LEGACY_FIXTURE_ROOT/candidate-ready"
PATH=/usr/bin:/bin SWEEP_ROOT="$LEGACY_FIXTURE_ROOT" /bin/sh -u "$LEGACY_CANDIDATE_DOWN_SCRIPT" \
  discover-system "$SWEEP_ARG_ID" "$SWEEP_SHA" test-build "$SWEEP_SHA" \
  > "$TMP/legacy-candidate-down-signal-output" 2>&1 &
legacy_signal_pid=$!
legacy_assert "candidate-down signal fixture reaches the pre-safety cut" \
  legacy_wait_ready "$LEGACY_FIXTURE_ROOT/candidate-down-ready"
legacy_assert "candidate-down normalizer accepts TERM" kill -TERM "$legacy_signal_pid"
if wait "$legacy_signal_pid"; then legacy_signal_status=0; else legacy_signal_status=$?; fi
legacy_assert_status "$legacy_signal_status" 143 "candidate-down TERM exits normalization nonzero"
legacy_assert_present "$LEGACY_FIXTURE_ROOT/data/local/.hapaneld-helper.legacy-takeover" \
  "candidate-down TERM retains exact takeover authority"
legacy_assert_absent "$LEGACY_FIXTURE_ROOT/dev/.hapaneld-helper-transaction.lock" \
  "candidate-down TERM releases shared transaction custody"
legacy_assert "candidate-down recovery restores the exact prior candidate" \
  legacy_wait_ready "$LEGACY_FIXTURE_ROOT/candidate-ready"
legacy_assert_dispatch_success "$TMP/legacy-candidate-down-retry-output" \
  "candidate-down authority permits an ordinary normalization retry"
legacy_assert_absent "$LEGACY_FIXTURE_ROOT/data/local/.hapaneld-helper.legacy-takeover" \
  "candidate-down retry retires exact takeover authority"
legacy_assert "candidate-down fixture cleanup succeeds" legacy_cleanup_fixture
for legacy_tmp_kind in partial complete; do
  legacy_assert "$legacy_tmp_kind lone-tmp fixture prepares staged preauthority" \
    legacy_prepare_fixture system stage
  legacy_assert "$legacy_tmp_kind lone-tmp fixture removes the unpublished final record" \
    rm -f "$LEGACY_FIXTURE_ROOT/data/local/.hapaneld-helper.legacy-takeover"
  if [ "$legacy_tmp_kind" = partial ]; then
    if : > "$LEGACY_FIXTURE_ROOT/data/local/.hapaneld-helper.legacy-takeover.tmp"; then
      pass "partial lone-tmp fixture publishes zero preauthority bytes"
    else
      fail_test "partial lone-tmp fixture publishes zero preauthority bytes"
    fi
  else
    if printf 'complete preauthority record bytes\n' > "$LEGACY_FIXTURE_ROOT/data/local/.hapaneld-helper.legacy-takeover.tmp"; then
      pass "complete lone-tmp fixture publishes bounded preauthority bytes"
    else
      fail_test "complete lone-tmp fixture publishes bounded preauthority bytes"
    fi
  fi
  legacy_assert "$legacy_tmp_kind lone-tmp fixture binds root-only record mode" \
    chmod 600 "$LEGACY_FIXTURE_ROOT/data/local/.hapaneld-helper.legacy-takeover.tmp"
  legacy_assert_dispatch_success "$TMP/legacy-tmp-$legacy_tmp_kind-output" \
    "$legacy_tmp_kind lone-tmp cut reconciles through production dispatch"
  legacy_assert_absent "$LEGACY_FIXTURE_ROOT/data/local/.hapaneld-helper.legacy-takeover.tmp" \
    "$legacy_tmp_kind lone-tmp cut retires preauthority bytes"
  legacy_assert_absent "$LEGACY_FIXTURE_ROOT/data/local/.hapaneld-helper.new" \
    "$legacy_tmp_kind lone-tmp cut reclaims staged candidate bytes"
  legacy_assert "$legacy_tmp_kind lone-tmp fixture cleanup succeeds" legacy_cleanup_fixture
done

# A Guard plan that becomes active at the daemon-down cut restores the exact previously serving
# candidate and retains its authority record. This also proves normalization never probes
# --replacement-safe while the simulated owner lock (candidate-ready) is live.
legacy_assert "post-stop Guard-race fixture prepares exact live custody" legacy_prepare_fixture system live
"$LEGACY_FIXTURE_CANDIDATE_PATH" --supervise >/dev/null 2>&1 &
legacy_candidate_pid=$!
legacy_assert "post-stop Guard-race fixture starts its retained candidate" \
  legacy_wait_ready "$LEGACY_FIXTURE_ROOT/candidate-ready"
export LEGACY_POST_SAFE_ARMED=1
legacy_assert_dispatch_failure "$TMP/legacy-post-safe-armed-output" \
  "post-stop GUARD_ARMED refuses takeover normalization"
unset LEGACY_POST_SAFE_ARMED
legacy_assert_present "$LEGACY_FIXTURE_ROOT/data/local/.hapaneld-helper.legacy-takeover" \
  "post-stop GUARD_ARMED retains exact takeover authority"
legacy_assert "post-stop GUARD_ARMED restores a verified candidate" \
  legacy_wait_ready "$LEGACY_FIXTURE_ROOT/candidate-ready"
legacy_recovered_processes="$(legacy_fixture_processes "$LEGACY_FIXTURE_CANDIDATE_PATH")"
legacy_assert "post-stop GUARD_ARMED leaves a canonical candidate process" \
  test "$(printf '%s\n' "$legacy_recovered_processes" | wc -w)" -ge 1
legacy_assert "post-stop Guard-race fixture cleanup succeeds" legacy_cleanup_fixture

# Parser mismatch is a hard hold with zero process mutation, including Kotlin-rejected trailing
# blank lines. An unrelated executable inode also survives the successful normalization kill.
legacy_assert "noncanonical-record fixture prepares exact live custody" legacy_prepare_fixture system live
if printf '\n' >> "$LEGACY_FIXTURE_ROOT/data/local/.hapaneld-helper.legacy-takeover"; then
  pass "noncanonical-record fixture appends a second trailing newline"
else
  fail_test "noncanonical-record fixture appends a second trailing newline"
fi
"$LEGACY_FIXTURE_CANDIDATE_PATH" --supervise >/dev/null 2>&1 &
legacy_candidate_pid=$!
legacy_assert "noncanonical-record fixture starts its retained candidate" \
  legacy_wait_ready "$LEGACY_FIXTURE_ROOT/candidate-ready"
legacy_assert_dispatch_failure "$TMP/legacy-extra-newline-output" \
  "noncanonical legacy record is refused without normalization"
legacy_assert "noncanonical record refusal preserves the retained candidate" legacy_pid_alive "$legacy_candidate_pid"
legacy_assert_present "$LEGACY_FIXTURE_ROOT/data/local/.hapaneld-helper.legacy-takeover" \
  "noncanonical record refusal preserves exact bytes"
legacy_assert "noncanonical-record fixture cleanup succeeds" legacy_cleanup_fixture

legacy_assert "unrelated-inode fixture prepares exact takeover custody" legacy_prepare_fixture system live
legacy_assert "unrelated-inode fixture copies a distinct executable" \
  cp /bin/sleep "$LEGACY_FIXTURE_ROOT/data/local/unrelated-helper"
legacy_assert "unrelated-inode fixture makes its executable runnable" \
  chmod 700 "$LEGACY_FIXTURE_ROOT/data/local/unrelated-helper"
"$LEGACY_FIXTURE_ROOT/data/local/unrelated-helper" 60 &
legacy_unrelated_pid=$!
legacy_assert_dispatch_success "$TMP/legacy-unrelated-inode-output" \
  "normalization succeeds while an unrelated executable inode is live"
legacy_assert "normalization preserves the unrelated executable process" legacy_pid_alive "$legacy_unrelated_pid"
kill "$legacy_unrelated_pid" 2>/dev/null || true
wait "$legacy_unrelated_pid" 2>/dev/null || true
legacy_assert "unrelated-inode fixture cleanup succeeds" legacy_cleanup_fixture
unset LEGACY_FIXTURE_ROOT LEGACY_FIXTURE_C LEGACY_FIXTURE_CANDIDATE LEGACY_FIXTURE_OLD \
  LEGACY_FIXTURE_OLD_BIN LEGACY_REGISTRATION_MODE LEGACY_EXPECTED_REGISTRATION_SHA \
  LEGACY_REGISTRATION LEGACY_REGISTRATION_SHA LEGACY_OLD_SHA LEGACY_OLD_BYTES \
  LEGACY_CANDIDATE_SHA LEGACY_CANDIDATE_BYTES LEGACY_REGISTRATION_BYTES \
  LEGACY_OLD_BUILD LEGACY_CANDIDATE_BUILD LEGACY_FIXTURE_CANDIDATE_PATH \
  LEGACY_PRE_UNLINK_SCRIPT LEGACY_CANDIDATE_DOWN_SCRIPT legacy_topology legacy_candidate_pid \
  legacy_output legacy_tmp_kind legacy_unrelated_pid legacy_cut_pid legacy_signal_pid legacy_signal_status \
  legacy_stage_pid legacy_recovered_processes

# Signal cleanup must terminate the transaction as well as releasing its lock. Hold the emitted
# dispatch immediately after acquisition so TERM deterministically reaches a live lock owner.
INTERRUPT_DISPATCH_SCRIPT="$TMP/device-dispatch-interrupt.sh"
sed '/^transaction_id=${2:-}$/i\
printf %s armed > "${SWEEP_ROOT}/interrupt-ready"\
/bin/sleep 2
' "$DISPATCH_SCRIPT" > "$INTERRUPT_DISPATCH_SCRIPT"
mk_sweep_tree
mkdir -p "$SWEEP_TREE/dev" "$SWEEP_TREE/vendor"
interrupt_output="$TMP/device-dispatch-interrupt-output"
SWEEP_ROOT="$SWEEP_TREE" /bin/sh -u "$INTERRUPT_DISPATCH_SCRIPT" \
  discover-system "$SWEEP_ARG_ID" "$SWEEP_SHA" test-build "$SWEEP_SHA" \
  > "$interrupt_output" 2>&1 &
interrupt_pid=$!
interrupt_lock="$SWEEP_TREE/dev/.hapaneld-helper-transaction.lock"
interrupt_ready="$SWEEP_TREE/interrupt-ready"
interrupt_wait=0
while [ "$interrupt_wait" -lt 1000 ]; do
  if [ -s "$interrupt_ready" ] && [ -d "$interrupt_lock" ]; then break; fi
  kill -0 "$interrupt_pid" 2>/dev/null || break
  /bin/sleep 0.01
  interrupt_wait=$((interrupt_wait + 1))
done
if [ -s "$interrupt_ready" ] && [ -d "$interrupt_lock" ]; then
  pass "interruption fixture reaches a live production transaction lock"
else
  LAST_OUTPUT="$interrupt_output"
  fail_test "interruption fixture reaches a live production transaction lock"
fi
kill -TERM "$interrupt_pid" 2>/dev/null || true
if wait "$interrupt_pid"; then interrupt_status=0; else interrupt_status=$?; fi
if [ "$interrupt_status" -eq 143 ]; then
  pass "TERM cleanup exits the production transaction with a signal-derived failure"
else
  LAST_OUTPUT="$interrupt_output"
  fail_test "TERM cleanup exits the production transaction with a signal-derived failure (got $interrupt_status)"
fi
assert_swept "dev/.hapaneld-helper-transaction.lock" "TERM cleanup releases the production transaction lock"
unset INTERRUPT_DISPATCH_SCRIPT interrupt_output interrupt_pid interrupt_lock interrupt_ready interrupt_wait interrupt_status

# A live owner must reject before the sweep. This proves the top-level order, not merely that the
# successful path eventually leaves no lock directory behind.
mk_sweep_tree
mkdir -p "$SWEEP_TREE/dev/.hapaneld-helper-transaction.lock" "$SWEEP_TREE/vendor"
printf '%s\n' "$$" > "$SWEEP_TREE/dev/.hapaneld-helper-transaction.lock/pid"
: > "$SWEEP_TREE/data/local/tmp/hapaneld-helper-$SWEEP_STALE_ID"
busy_output="$TMP/device-dispatch-busy-output"
if SWEEP_ROOT="$SWEEP_TREE" /bin/sh -u "$DISPATCH_SCRIPT" \
    discover-system "$SWEEP_ARG_ID" "$SWEEP_SHA" test-build "$SWEEP_SHA" \
    > "$busy_output" 2>&1; then
  busy_status=0
else
  busy_status=$?
fi
if [ "$busy_status" -eq 75 ] && [ "$(tail -n 1 "$busy_output")" = "TRANSACTION_BUSY" ]; then
  pass "a live-owned transaction lock returns TRANSACTION_BUSY with status 75"
else
  LAST_OUTPUT="$busy_output"
  fail_test "a live-owned transaction lock returns TRANSACTION_BUSY with status 75 (got $busy_status)"
fi
assert_kept "data/local/tmp/hapaneld-helper-$SWEEP_STALE_ID" "a live-owned transaction lock rejects before staging sweep"
assert_kept "dev/.hapaneld-helper-transaction.lock" "failed lock admission preserves the live owner's lock"
if [ "$(cat "$SWEEP_TREE/dev/.hapaneld-helper-transaction.lock/pid")" = "$$" ]; then
  pass "failed lock admission preserves the live owner's identity"
else
  fail_test "failed lock admission preserves the live owner's identity"
fi

for verb in install_system install_systemless install_hybrid rollback_system rollback_systemless rollback_hybrid; do
  ordering="$(awk -v verb="$verb" '
    $0 == verb"() {" {inside=1}
    inside && /@STAGED_[A-Z_]*@/ {last_read=NR}
    inside && !first_destruct && (/^  stop hapaneld_helper/ || /^  pkill -x hapaneld-helper/ || /^  mv -f / || /^  restore_or_remove(_v2)? / || (/^  rm -f / && !/hapaneld-recovery/ && !/\$probe/ && !/\$candidate/)) {first_destruct=NR}
    inside && /^\}/ {print last_read+0, first_destruct+0; exit}
  ' "$DEVICE_HEREDOC")"
  last_read="${ordering%% *}"; first_destruct="${ordering##* }"
  if [ "${last_read:-0}" -gt 0 ] && [ "${first_destruct:-0}" -gt 0 ] && [ "$last_read" -lt "$first_destruct" ]; then
    pass "$verb consumes its staged inputs before its first destructive step (read $last_read < destroy $first_destruct)"
  else
    fail_test "$verb consumes its staged inputs before its first destructive step (read ${last_read:-none}, destroy ${first_destruct:-none})"
  fi
done

fi
[ "$PROVISION_TEST_SCOPE" != shard-host-reclamation ] || finish_provision_test

if provision_scope_is core all shard-git-bash; then
# --------------------------------------------------------------------------------------------------
# Git Bash: every adb argument the provisioner sends, in both directions.
#
# A reporter on Git for Windows could not provision at all (#24). The MSYS runtime rewrites any
# argument beginning with `/` into a Windows path before exec'ing a native program, so the helper was
# pushed to `D:/Program Files/Git/data/local/tmp/hapaneld-helper` and the panel received nothing —
# with adb, the provisioner and the reporter all seeing a successful command.
#
# The rewrite happens in the caller's exec, so no mock can observe it by being called. This section
# runs a whole provisioning flow behind `fixtures/msys-adb`, which models the rule, records the argv
# adb.exe would have received, and delegates. That covers every boundary the run touches — the helper
# staging pushes, the capture-script push, the database pull, exec-out, install and every shell
# command — rather than only the one call site the report happened to name.
#
# The contract has two directions, and a fix that forgot either would be worse than the defect:
# device paths must arrive literally, and host paths must still be translated or adb.exe cannot open
# them. Both are asserted, and each is paired with a negative control that re-runs the identical flow
# with the guard ignored, so a silently broken emulator cannot report green forever.
#
# Every other test in this file runs with no emulation in the path, which is where the Linux and
# macOS non-regression evidence comes from: the guard is an environment variable those platforms do
# not read, and this suite's existing assertions on $MOCK_CALL_LOG are unchanged by it.
# --------------------------------------------------------------------------------------------------

# Nothing the runtime converted may be a device path. The emulator decides that while the call is
# happening — a real host path can be gone by the time a test reads the log, because the provisioner
# deletes the capture script as soon as its push returns — and records each offender on its own line.
# This is the general form of the contract: it needs no list of the boundaries a run happens to reach.
msys_converted_non_host_argument() {
  grep -h '^rewritten-non-host=' "$MSYS_ARGV_LOG"
}

assert_msys_argv() {
  if grep -Eq -- "$1" "$MSYS_ARGV_LOG"; then pass "$2"
  else fail_test "$2 (missing argv pattern: $1)"; fi
}
assert_msys_argv_exact() {
  if grep -Fqx -- "argv=$1" "$MSYS_ARGV_LOG"; then pass "$2"
  else fail_test "$2 (missing exact argv: $1)"; fi
}
refute_msys_argv() {
  if grep -Eq -- "$1" "$MSYS_ARGV_LOG"; then fail_test "$2 (unexpected argv pattern: $1)"
  else pass "$2"; fi
}

MOCK_MSYS_PATHCONV=1 run_provision "$MOCK_TARGET" --apk "$APK" --no-tame
assert_success "a full provisioning run completes under the emulated Git Bash runtime"
if msys_converted_non_host_argument > "$TMP/msys-offender"; then
  LAST_OUTPUT="$TMP/msys-offender"
  fail_test "no adb argument is rewritten into the Windows tree"
else
  pass "no adb argument is rewritten into the Windows tree"
fi
assert_msys_argv '^argv=/data/local/tmp/hapaneld-helper-[0-9a-f]+$' \
  "the staged helper path reaches adb literally"
assert_msys_argv '^argv=/data/local/tmp/\.hapaneld-db-txn\.[0-9a-f]+-script$' \
  "the database capture script's destination reaches adb literally"
assert_msys_argv '^argv=/data/local/tmp/\.hapaneld-db-txn\.[0-9a-f]+/ha-paneld\.db$' \
  "the database pull source reaches adb literally"
refute_msys_argv "^argv=$MSYS_ROOT/(data|system|vendor|dev|proc|sys)" \
  "no Android filesystem root is rewritten"
# The other direction. The owner-bound APK snapshot and the pulled database are host files; under
# Git Bash adb.exe cannot open `/tmp/...`, so the runtime translating them is the only reason those
# operands work at all. The aapt fixture records the exact private snapshot selected by production,
# avoiding a stale assertion against the mutable caller path that production no longer installs.
assert_msys_argv_exact "$MSYS_ROOT$(cat "$TMP/candidate-apk-path")" \
  "the bound APK host path is still translated for adb.exe"
assert_msys_argv "^argv=$MSYS_ROOT$TMP/" \
  "host operands under the working directory are still translated"
assert_msys_argv '^argv=panel\.test:5555$' \
  "the panel serial crosses unchanged"

MOCK_MSYS_PATHCONV=1 MOCK_MSYS_IGNORE_EXCL=1 run_provision "$MOCK_TARGET" --apk "$APK" --no-tame
if msys_converted_non_host_argument >/dev/null; then
  pass "the unguarded control rewrites a device path into the Windows tree"
else
  fail_test "the unguarded control rewrites a device path into the Windows tree"
fi
assert_msys_argv "^argv=$MSYS_ROOT/data/local/tmp/\.hapaneld-db-observer\.[0-9a-f]+-script\$" \
  "the unguarded control reproduces the first protected staging failure"
refute_msys_argv '^argv=/data/local/tmp/\.hapaneld-db-observer\.[0-9a-f]+-script$' \
  "the unguarded control sends no literal device path"

run_provision "$MOCK_TARGET" --apk "$APK" --no-tame
assert_success "the same run completes with no emulation in the path"
if [ -s "$MSYS_ARGV_LOG" ]; then
  fail_test "an ordinary Linux run enters no conversion emulation"
else
  pass "an ordinary Linux run enters no conversion emulation"
fi
assert_log_contains '/data/local/tmp/hapaneld-helper-[0-9a-f]+' \
  "the staged helper path is unchanged on Linux"

# --------------------------------------------------------------------------------------------------
# Git Bash: Android Build-Tools that exist only as Windows wrappers.
#
# The Windows SDK ships apksigner as apksigner.bat, and the MSYS runtime resolves a bare command name
# to name.exe only, so a Git Bash host that had Build-Tools was refused as though it had none (#24).
# A stand-in uname reports the MSYS runtime and a stand-in cygpath translates the Windows spelling of
# ANDROID_HOME that System Properties writes. aapt ships as aapt.exe, which the runtime does resolve
# by its bare name, so the SDK holds a bare aapt beside apksigner.bat. Nothing else on the host
# carries a Build-Tool.
# --------------------------------------------------------------------------------------------------
GIT_BASH_HOST="$TMP/git-bash-host"
WINDOWS_SDK="$TMP/windows-android-sdk"
WINDOWS_TOOL_PATH="$TMP/windows-build-tools-on-path"
mkdir -p "$GIT_BASH_HOST" "$WINDOWS_SDK/build-tools/36.0.0" "$WINDOWS_TOOL_PATH"
printf '#!/bin/sh\necho MINGW64_NT-10.0-26100\n' > "$GIT_BASH_HOST/uname"
cat > "$GIT_BASH_HOST/cygpath" <<CYGPATH
#!/bin/sh
[ "\$1" = -u ] && shift
case "\$1" in
  'C:\\Users\\tester\\AppData\\Local\\Android\\Sdk') echo '$WINDOWS_SDK' ;;
  *) printf '%s\n' "\$1" ;;
esac
CYGPATH
chmod 755 "$GIT_BASH_HOST/uname" "$GIT_BASH_HOST/cygpath"
ln -s "$FIXTURES/apksigner" "$WINDOWS_SDK/build-tools/36.0.0/apksigner.bat"
ln -s "$FIXTURES/aapt" "$WINDOWS_SDK/build-tools/36.0.0/aapt"
ln -s "$FIXTURES/apksigner" "$WINDOWS_TOOL_PATH/apksigner.cmd"
ln -s "$FIXTURES/aapt" "$WINDOWS_TOOL_PATH/aapt2.cmd"
NO_BUILD_TOOL_FIXTURES="$TMP/fixtures-without-build-tools"
make_host_without "$NO_BUILD_TOOL_FIXTURES" apksigner aapt aapt2

PATH="$GIT_BASH_HOST:$NO_BUILD_TOOL_FIXTURES" ANDROID_HOME='C:\Users\tester\AppData\Local\Android\Sdk' ANDROID_SDK_ROOT= \
  run_provision "$MOCK_TARGET" --apk "$RELEASE_APK" --release-tag v0.9.2-rc3 --no-tame
assert_success "a Git Bash host whose apksigner is only apksigner.bat passes the signer gate"
assert_log_contains '^apksigner\.bat verify --print-certs .*installed-apk' \
  "the installed app's signer is read through apksigner.bat"
assert_log_contains '^apksigner\.bat verify --print-certs .*ha-paneld-v0\.9\.2-rc3-manual-setup-required\.apk$' \
  "the candidate's signer is read through apksigner.bat"
assert_contains 'database compatible' "the .bat-only host reaches the database decision"

PATH="$GIT_BASH_HOST:$WINDOWS_TOOL_PATH:$NO_BUILD_TOOL_FIXTURES" ANDROID_HOME= ANDROID_SDK_ROOT= \
  run_provision "$MOCK_TARGET" --apk "$RELEASE_APK" --release-tag v0.9.2-rc3 --no-tame
assert_success "a Git Bash host with .cmd wrappers on PATH passes the signer gate"
assert_log_contains '^apksigner\.cmd verify --print-certs .*installed-apk' \
  "the installed app's signer is read through apksigner.cmd from PATH"
assert_log_contains '^aapt2\.cmd dump xmltree .* --file AndroidManifest\.xml$' \
  "a wrapper-spelled aapt2 is given aapt2's own xmltree grammar"

# Both at once: a wrapper on PATH stays the operator's explicit choice over one in the SDK.
PATH="$GIT_BASH_HOST:$WINDOWS_TOOL_PATH:$NO_BUILD_TOOL_FIXTURES" ANDROID_HOME='C:\Users\tester\AppData\Local\Android\Sdk' ANDROID_SDK_ROOT= \
  run_provision "$MOCK_TARGET" --apk "$RELEASE_APK" --release-tag v0.9.2-rc3 --no-tame
assert_log_contains '^apksigner\.cmd verify --print-certs .*installed-apk' \
  "with wrappers on PATH and in the SDK, the PATH wrapper is the one run"
assert_not_contains '^apksigner\.bat ' "$MOCK_CALL_LOG" "with wrappers on PATH and in the SDK, the SDK wrapper is not run"

# Control: the identical .bat-only SDK on a host that is not Git Bash. The wrapper spellings are an
# MSYS rule, so they are not searched, and the run refuses in the tool's own words.
PATH="$NO_BUILD_TOOL_FIXTURES" ANDROID_HOME="$WINDOWS_SDK" ANDROID_SDK_ROOT= \
  run_provision "$MOCK_TARGET" --apk "$RELEASE_APK" --release-tag v0.9.2-rc3 --no-tame
assert_failure "the same .bat-only SDK is not searched outside the MSYS runtime"
assert_contains 'needs apksigner from Android SDK Build-Tools on this computer' \
  "outside the MSYS runtime the .bat-only host is refused for the missing tool"
assert_not_contains '^apksigner\.bat ' "$MOCK_CALL_LOG" "outside the MSYS runtime apksigner.bat is never run"
fi

[ "$PROVISION_TEST_SCOPE" != shard-git-bash ] || finish_provision_test

printf '1..%d\n' "$((passes + failures))"
if [ "$failures" -ne 0 ]; then
  printf '%d assertion(s) failed\n' "$failures" >&2
  exit 1
fi
