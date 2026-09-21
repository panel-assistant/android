#!/usr/bin/env bash
# ha-paneld provisioning — install + grant all permissions over adb, no device UI, and (optionally)
# set the panel id + MQTT broker in one shot. Requires adb access; privileged features additionally
# need a route reported by the app's active hardware profile and live provisioning plan.
# For non-root panels, --shizuku installs and starts the pinned manager; approval remains on-panel.
#
# Usage:
#   scripts/provision.sh <panel-ip:5555> [APK] \
#       [--id PANEL_ID] [--mqtt tcp://host:1883] [--mqtt-user U] [--mqtt-pass P] [--apk PATH] \
#       [--mqtt-pass-file FILE]         # preferred: keep the password out of process arguments
#       [--log-host HOST] [--log-port N] [--log-proto syslog-udp|syslog-tcp|http]  # forward logcat to an aggregator
#       [--shizuku]                    # non-root enhanced access; still needs local approval
#   Built-in WebView renderer (experimental — no HA Companion / kiosk app needed). For a normal
#   install, follow the Browser sign-in URL printed after verification. Advanced non-interactive use:
#       [--ha-url https://homeassistant.local:8123] [--builtin] \
#       [--home-dashboard /dashboard-name/tab-name]  # show this dashboard instead of the account default
#       [--entity-filter on]             # limit HA's state stream to the entities the dashboard uses
#       [--ha-token <LLAT>]              # simple: a long-lived access token (a standing credential), OR
#       [--ha-token-file FILE]            # preferred token input: one private text file, OR
#       [--ha-user U --ha-pass P]        # preferred: log in HERE to mint a REFRESH token; the password
#                                        # never reaches the panel, and no 10-year token lives on it.
#       [--ha-user U --ha-pass-file FILE] # keeps the login password out of process arguments
#   scripts/provision.sh <panel-ip:5555> --verify        # check end-state only, make no changes
#
# IDEMPOTENCY (safe to re-run after a cancel/failure — re-running converges to the full state):
#   - install -r / appops allow / pm grant / accessibility_enabled / app launch : no-ops on re-run
#   - enabled_accessibility_services : appends our service only if absent (guarded)
#   - POST /api/v1/config            : partial-merge — only sets the keys you pass
# `set -e` aborts cleanly on any failure; the ERR trap tells you to re-run, and every run ends with a
# self-verify checklist. `--verify` is the standing control: run any time to confirm a panel is set up.
set -euo pipefail
umask 077

# Colours/emoji — only when writing to a terminal, so piped/redirected output stays clean.
if [ -t 1 ]; then
  B=$'\033[1m'; D=$'\033[2m'; X=$'\033[0m'
  RED=$'\033[31m'; GRN=$'\033[32m'; YEL=$'\033[33m'; CYN=$'\033[36m'; MAG=$'\033[35m'
else B=; D=; X=; RED=; GRN=; YEL=; CYN=; MAG=; fi
step() { echo "${CYN}${B}$1${X} $2"; }
warn() { echo "   ${YEL}⚠ $1${X}"; }
# Panel and HTTP responses are untrusted terminal input. Preserve ordinary text,
# tabs and line breaks while removing escape sequences and other control bytes.
sanitize_terminal() { LC_ALL=C tr -d '\000-\010\013\014\016-\037\177'; }
approval_required_response() {
  LC_ALL=C grep -Eq '"error"[[:space:]]*:[[:space:]]*"approval-required"' "$@"
}

usage() {
  cat <<'EOF'
Usage: scripts/provision.sh <panel-ip[:port]> [APK] [options]

Common operations:
  --latest                 Install the latest stable release
  --prerelease             Install the newest release, including release candidates
  --apk FILE               Install a specific APK
  --export FILE            Export config/settings only; never installs unless combined with mutations
  --restore FILE           Import a config JSON export (not a complete .hpb backup)
  --restore-fleet FILE     Import only portable, non-secret keys from a config JSON export
  --verify                 Check the existing installation only; never installs
  --hand-back-home         Re-enable the vendor apps ha-paneld disabled and give the panel's own
                           home screen back. Leaves ha-paneld installed.
  --uninstall              Hand the home screen back first, then remove ha-paneld. Refuses to
                           remove anything if the panel would be left with no home screen.
  --no-tame                Deprecated compatibility no-op; guidance is never auto-applied
  --shizuku                Install/start pinned Shizuku for locally approved non-root access
  --allow-unsigned-helper  Developer-only: allow a privileged helper from an unsigned local APK
  --require-release-signer Reject a local APK unless it uses the official ha-paneld release certificate
  --allow-missing-db-snapshot  Deprecated parser-only compatibility no-op
  --home-dashboard PATH    Built-in renderer: the dashboard to show, e.g. /lovelace or
                           /dashboard-name/tab-name. `auto` follows the account's default.
  --entity-filter on|off   Built-in renderer: limit Home Assistant's state stream to the entities
                           the dashboard uses. Recommended on for older or slower panels.
  --mqtt-pass-file FILE    Read the MQTT password from a private text file
  --ha-token-file FILE     Read the Home Assistant token from a private text file
  --ha-pass-file FILE      Read the Home Assistant login password from a private text file
  --help                   Show this help

The script installs, starts, and verifies ha-paneld. It exits nonzero if a required step is incomplete.
EOF
}

cleanup_provision_resources() {
  if type stop_root_helper_apk_install >/dev/null 2>&1; then stop_root_helper_apk_install || true; fi
  if type cleanup_root_helper_lease_guard >/dev/null 2>&1; then cleanup_root_helper_lease_guard || true; fi
  # Issue #76: reclaim this run's disposable staging on EVERY exit path, not only after a commit.
  if type cleanup_root_helper_staging >/dev/null 2>&1; then cleanup_root_helper_staging || true; fi
  # An interrupted database capture holds a credential-bearing copy of the whole store on the panel
  # and a partial one on the host; both are reclaimed on every exit path, never only on success.
  if type discard_db_snapshot_txn >/dev/null 2>&1; then discard_db_snapshot_txn || true; fi
  # Database admission also stages a nonce-owned observer script and private SQLite copy. Reclaim
  # either half if the host exits between push, verification, execution and result parsing.
  if type cleanup_root_database_observer >/dev/null 2>&1; then cleanup_root_database_observer || true; fi
  [ -z "${SHIZUKU_DIR:-}" ] || rm -rf "$SHIZUKU_DIR" || true
  SHIZUKU_DIR=""
  [ -z "${CANDIDATE_APK_DIR:-}" ] || rm -rf "$CANDIDATE_APK_DIR" || true
  CANDIDATE_APK_DIR=""
  [ -z "${SECRET_DIR:-}" ] || rm -rf "$SECRET_DIR" || true
  SECRET_DIR=""
  # Keep a possibly armed app quiesced until every abort cleanup above has stopped mutation and
  # removed this run's owned staging. Release it last; the app watchdog covers blocked cleanup.
  if type release_upgrade_quiescence >/dev/null 2>&1; then release_upgrade_quiescence || true; fi
}

handle_provision_signal() {
  local status="$1"
  trap - EXIT INT TERM
  PROVISION_SIGNAL_CLEANUP=1
  cleanup_provision_resources
  exit "$status"
}

trap 'echo "${RED}${B}✗ provisioning incomplete${X} — re-run the SAME command to finish (it is idempotent)." >&2' ERR
trap 'cleanup_provision_resources' EXIT
trap 'handle_provision_signal 130' INT
trap 'handle_provision_signal 143' TERM

# Fatal with SPECIFIC recovery steps. Disarms the generic re-run trap first: these are the failures
# re-running cannot fix, so the trap's blanket advice would mislead.
fail() {
  if type cleanup_root_helper_lease_guard >/dev/null 2>&1; then cleanup_root_helper_lease_guard; fi
  trap - ERR
  echo "${RED}${B}✗ $1${X}" >&2; shift
  local l; for l in "$@"; do echo "   $l" >&2; done
  exit 1
}

[ "$#" -gt 0 ] || { usage >&2; exit 2; }
case "$1" in -h|--help) usage; exit 0 ;; esac
TARGET="$1"
shift
REPO="panel-assistant/android"
LOCAL_APK="app/build/outputs/apk/debug/app-debug.apk"
# One source tree builds two installable identities. This provisioner installs and verifies the
# successor; the bridge keeps the historical application id so a panel can run both for one handover.
# Until the bridge is gone it may still be the package that holds the data, answers the upgrade
# control broadcast and owns the app-data directory, so those reads resolve the installed data
# holder rather than assuming the install target.
PKG="io.panelassistant.android"
LEGACY_PKG="io.github.maxlyth.hapaneld"
# The Gradle namespace and Kotlin package do NOT move with the applicationId, so the merged manifest
# carries io.github.maxlyth.hapaneld.<Class> for both builds. Android expands a `/.Class` shorthand
# against the package half of the component, which names a real class only for the legacy id.
# AppIdentity.className encodes the same rule inside the app.
CODE_PACKAGE="io.github.maxlyth.hapaneld"
# The installed package whose app-private data this run reads. resolve_data_package fills it once.
DATA_PKG=""
RELEASE_CERT_SHA256="ac6193307fb0b70113aae205d7549406f96e063bc5491b67b1d5694a34b0e339"
RELEASE_HELPER_BUILD_ID=""
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
HELPER_DIST_DIR="${HAPANELD_HELPER_DIST_DIR:-$SCRIPT_DIR/../helper/dist}"
# Flatten one of this app's own manifest components for a given installed identity.
app_component() {
  local pkg="$1" relative="$2"
  case "$relative" in .*) ;; *) return 1 ;; esac
  if [ "$pkg" = "$CODE_PACKAGE" ]; then printf '%s/%s\n' "$pkg" "$relative"
  else printf '%s/%s%s\n' "$pkg" "$CODE_PACKAGE" "$relative"; fi
}
# Android's own rule for comparing flattened components: a class name beginning with a dot is
# relative to the package half. A panel provisioned by an older script stores the shorthand
# spelling, which denotes the same component as the fully-qualified one under the legacy id and a
# different one under any other id.
normalize_component() {
  local flattened="$1" pkg class
  case "$flattened" in
    */*) pkg="${flattened%%/*}"; class="${flattened#*/}" ;;
    *) printf '%s\n' "$flattened"; return 0 ;;
  esac
  case "$class" in .*) class="$pkg$class" ;; esac
  printf '%s/%s\n' "$pkg" "$class"
}
# Is this accessibility component already in a colon-separated enabled-services list, in either
# spelling? Written as a search rather than a substring test so a retry appends nothing twice.
a11y_service_enabled() {
  local state="$1" service="$2" target entry rest
  target="$(normalize_component "$service")"
  rest="$state"
  while [ -n "$rest" ]; do
    entry="${rest%%:*}"
    if [ "$entry" = "$rest" ]; then rest=""; else rest="${rest#*:}"; fi
    [ -n "$entry" ] || continue
    [ "$(normalize_component "$entry")" != "$target" ] || return 0
  done
  return 1
}
A11Y="$(app_component "$PKG" .input.PanelAccessibilityService)"
APK=""; APK_RELEASE_TAG=""; PANEL_ID=""; MQTT=""; MQTT_USER=""; MQTT_PASS=""; VERIFY_ONLY=0; LATEST=0; PRERELEASE=0; FORCE=0; PERSIST_ADB=0; STRIP_VENDOR=0; SHIZUKU=0; ALLOW_UNSIGNED_HELPER=0; REQUIRE_RELEASE_SIGNER=0; TOINSTALL_VER=""; VERIFY_DIRECT_GRANTS=0; HA_OAUTH_CONFIGURED=0; RESET_CONFIG=0; RESET_CONFIG_COMPLETED=0; CANDIDATE_SIGNER_SHA256=""; HAND_BACK_HOME=0; UNINSTALL=0
# The schema versions the app has actually shipped. Bump the MAX alongside every new database
# migration; a snapshot admitting a version outside this set cannot be paired with any build this
# installer could restore it onto.
DB_SUPPORTED_USER_VERSION_MIN=1
DB_SUPPORTED_USER_VERSION_MAX=14
DB_COMPATIBILITY_METADATA_KEY="io.github.maxlyth.hapaneld.DATABASE_COMPATIBILITY"
DB_COMPATIBILITY_CONTRACT=""
DB_CONTRACT_EVALUATED=0
DB_CANDIDATE_MIN=""
DB_CANDIDATE_MAX=""
DB_GATE_RECOVERY=""
DB_GATE_DECISION_KIND=""
DB_GATE_INITIAL_EVIDENCE=""
DB_GATE_CONSUME_EVIDENCE=""
DB_GATE_RESET_FRESH=0
DB_GATE_HELPER_RECOVERY=""
DB_GATE_PROGRESS=""
HOST_DB_PRIMARY_FINGERPRINT=""
RESET_PACKAGE_STOPPED="unknown"
SNAPSHOT_TXN_REMOTE=""; SNAPSHOT_TXN_HOST_DB=""; SNAPSHOT_TXN_HOST_RECEIPT=""
SNAPSHOT_TXN_HOST_DB_WORK=""; SNAPSHOT_TXN_HOST_DB_TARGET=""
SNAPSHOT_TXN_HOST_RECEIPT_WORK=""; SNAPSHOT_TXN_HOST_RECEIPT_TARGET=""
SNAPSHOT_TXN_DEFERRED_SIGNAL=""
UPGRADE_QUIESCE_NONCE=""; UPGRADE_QUIESCE_PKG=""
UPGRADE_RECEIPT_PID=""; UPGRADE_RECEIPT_VERSION_CODE=""; UPGRADE_RECEIPT_DATABASE_BYTES=""
UPGRADE_RECEIPT_DATABASE_SHA256=""; UPGRADE_RECEIPT_USER_VERSION=""; UPGRADE_RECEIPT_APP_STATE_ROWS=""
SETUP_JOURNEY_AVAILABLE=0; SETUP_COMPLETE=0; SETUP_REPAIR=0; SETUP_NEXT=""; SETUP_NEXT_STATUS=""; SETUP_NEXT_DETAIL=""
LEGACY_MQTT_SET=0; LEGACY_HA_URL_SET=0; LEGACY_HA_AUTH_CONFIGURED=0
# Whether the panel agent ever answered /health. Every mutation after the launch step is gated on
# this: writing configuration into a panel that never came up cannot be verified, and may land in
# a half-started agent.
AGENT_HEALTHY=0
LOG_HOST=""; LOG_PORT=""; LOG_PROTO=""; LOG_ENABLE=""
EXPORT_FILE=""; RESTORE_FILE=""; RESTORE_MODE=""; AUTO_EXPORT_FILE=""
CONFIG_BACKUP_DIR="${HAPANELD_CONFIG_BACKUP_DIR:-${XDG_STATE_HOME:-${HOME:-.}/.local/state}/ha-paneld/config-backups}"
HA_URL=""; HA_TOKEN=""; HA_USER=""; HA_PASS=""; HA_REFRESH=""; HA_EXPIRY=""; BUILTIN=0
# Built-in renderer seeds. A seeded value is also an ANSWER to the guided wizard's matching question:
# an operator who took the trouble to pass it should not be asked again on the panel. Tracked with a
# separate *_SET flag rather than emptiness, because "" is a meaningful home_dashboard (follow the
# account's default) and would otherwise be indistinguishable from "not supplied".
HOME_DASHBOARD=""; HOME_DASHBOARD_SET=0; ENTITY_FILTER=""; ENTITY_FILTER_SET=0
MQTT_PASS_INPUT_FILE="${HAPANELD_MQTT_PASS_FILE:-}"
HA_TOKEN_INPUT_FILE="${HAPANELD_HA_TOKEN_FILE:-}"
HA_PASS_INPUT_FILE="${HAPANELD_HA_PASS_FILE:-}"
MQTT_PASS_SOURCE_COUNT=0; HA_TOKEN_SOURCE_COUNT=0; HA_PASS_SOURCE_COUNT=0
[ -z "$MQTT_PASS_INPUT_FILE" ] || MQTT_PASS_SOURCE_COUNT=1
[ -z "$HA_TOKEN_INPUT_FILE" ] || HA_TOKEN_SOURCE_COUNT=1
[ -z "$HA_PASS_INPUT_FILE" ] || HA_PASS_SOURCE_COUNT=1
MQTT_PASS_SECRET_FILE=""; HA_TOKEN_SECRET_FILE=""; HA_PASS_SECRET_FILE=""; HA_REFRESH_SECRET_FILE=""
SECRET_DIR=""
HA_LOGIN_FAILED=0
HELPER_REQUIRED=0
ROOT_HELPER_TRANSACTION_KIND=""
ROOT_HELPER_TRANSACTION_SHA256=""
ROOT_HELPER_TRANSACTION_PATH=""
ROOT_HELPER_TRANSACTION_ID=""
ROOT_HELPER_STAGED_HELPER=""
ROOT_HELPER_STAGED_RC=""
ROOT_HELPER_STAGED_HYBRID_RC=""
ROOT_HELPER_STAGED_SERVICE=""
ROOT_HELPER_STAGED_TRANSACTION=""
ROOT_HELPER_TARGET_BUILD_ID=""
ROOT_HELPER_TARGET_SHA256=""
ROOT_HELPER_LEASE_GUARD_PID=""
ROOT_HELPER_LEASE_GUARD_FAILURE=""
ROOT_HELPER_APK_INSTALL_PID=""
ROOT_HELPER_APK_INSTALL_OUTPUT_FILE=""
DB_OBSERVER_HOST_SCRIPT=""
DB_OBSERVER_REMOTE_SCRIPT=""
DB_OBSERVER_REMOTE_STAGE=""
DB_OBSERVER_REMOTE_OWNER=""
PROVISION_SIGNAL_CLEANUP=0
ADB_INSTALL_OUTPUT=""
SHIZUKU_DIR=""
TARGET_APK_SHA256=""

if [ "${1:-}" ] && [ "${1#--}" = "${1:-}" ]; then APK="$1"; shift; fi
while [ "${1:-}" ]; do
  case "$1" in
    --apk|--release-tag|--id|--mqtt|--mqtt-user|--mqtt-pass|--mqtt-pass-file|--log-host|--log-port|--log-proto|--ha-url|--ha-token|--ha-token-file|--ha-user|--ha-pass|--ha-pass-file|--export|--restore|--restore-fleet)
      if [ "$#" -lt 2 ] || [ -z "${2:-}" ] || [ "${2#--}" != "${2:-}" ]; then
        echo "${RED}✗ $1 needs a value.${X} Run with --help for examples." >&2
        exit 2
      fi
      ;;
  esac
  case "$1" in
    --apk) APK="$2"; shift 2 ;;
    --release-tag) APK_RELEASE_TAG="$2"; shift 2 ;; # internal: marks --apk as an official release asset
    --id) PANEL_ID="$2"; shift 2 ;;
    --mqtt) MQTT="$2"; shift 2 ;;
    --mqtt-user) MQTT_USER="$2"; shift 2 ;;
    --mqtt-pass) MQTT_PASS="$2"; MQTT_PASS_SOURCE_COUNT=$((MQTT_PASS_SOURCE_COUNT + 1)); shift 2 ;;
    --mqtt-pass-file) MQTT_PASS_INPUT_FILE="$2"; MQTT_PASS_SOURCE_COUNT=$((MQTT_PASS_SOURCE_COUNT + 1)); shift 2 ;;
    --latest) LATEST=1; shift ;;     # ignore any local build, fetch the newest STABLE GitHub release
    --prerelease|--pre) PRERELEASE=1; LATEST=1; shift ;;   # fetch the newest release incl. release-candidates
    --force) FORCE=1; shift ;;       # skip the same/older-version prompt
    --persist-adb) PERSIST_ADB=1; shift ;;  # keep network adb (tcp 5555) across reboots (opt-in; standing LAN port)
    --strip-vendor) STRIP_VENDOR=1; shift ;; # disable the Tuya vendor apps (TPA10) non-interactively (skips the prompt)
    --no-tame) shift ;;   # deprecated compatibility no-op; profile recommendations are report-only
    --shizuku) SHIZUKU=1; shift ;;   # install/start pinned Shizuku on a non-root panel; permission stays local
    --allow-unsigned-helper) ALLOW_UNSIGNED_HELPER=1; shift ;; # developer acknowledgement for local privileged bytes
    --allow-missing-db-snapshot) shift ;; # deprecated parser-only compatibility no-op
    --require-release-signer) REQUIRE_RELEASE_SIGNER=1; shift ;; # pin the official ha-paneld app signer
    --log-host) LOG_HOST="$2"; LOG_ENABLE=true; shift 2 ;;  # ship logcat to this aggregator (host enables shipping)
    --log-port) LOG_PORT="$2"; shift 2 ;;     # log sink port (default 514 for syslog)
    --log-proto) LOG_PROTO="$2"; shift 2 ;;   # syslog-tcp (default) | syslog-udp | http
    --log-off) LOG_ENABLE=false; shift ;;     # disable log shipping
    --ha-url) HA_URL="$2"; shift 2 ;;         # Home Assistant URL for the built-in WebView renderer
    --ha-token) HA_TOKEN="$2"; HA_TOKEN_SOURCE_COUNT=$((HA_TOKEN_SOURCE_COUNT + 1)); shift 2 ;;     # compatibility: visible in the invoking process argv
    --ha-token-file) HA_TOKEN_INPUT_FILE="$2"; HA_TOKEN_SOURCE_COUNT=$((HA_TOKEN_SOURCE_COUNT + 1)); shift 2 ;;
    --ha-user) HA_USER="$2"; shift 2 ;;       # HA username — login here to mint a refresh token (no LLAT)
    --ha-pass) HA_PASS="$2"; HA_PASS_SOURCE_COUNT=$((HA_PASS_SOURCE_COUNT + 1)); shift 2 ;;       # compatibility: visible in the invoking process argv
    --ha-pass-file) HA_PASS_INPUT_FILE="$2"; HA_PASS_SOURCE_COUNT=$((HA_PASS_SOURCE_COUNT + 1)); shift 2 ;;
    --builtin) BUILTIN=1; shift ;;            # select ha-paneld's built-in renderer as the dashboard
    --home-dashboard) HOME_DASHBOARD="$2"; HOME_DASHBOARD_SET=1; shift 2 ;;  # built-in renderer: dashboard path, or `auto`
    --entity-filter) ENTITY_FILTER="$2"; ENTITY_FILTER_SET=1; shift 2 ;;     # built-in renderer: on|off
    --export) EXPORT_FILE="$2"; shift 2 ;;    # save the panel's config bundle (incl. secrets) to FILE
    --restore) RESTORE_FILE="$2"; RESTORE_MODE="restore"; shift 2 ;;      # import config JSON, including device-scoped keys
    --restore-fleet) RESTORE_FILE="$2"; RESTORE_MODE="fleet"; shift 2 ;;  # apply only PORTABLE keys (cross-panel deploy)
    --verify) VERIFY_ONLY=1; shift ;;
    --hand-back-home) HAND_BACK_HOME=1; shift ;;  # re-enable the vendor apps ha-paneld disabled and stop being Home
    --uninstall) UNINSTALL=1; shift ;;            # hand the home screen back, then remove ha-paneld
    --reset-config) RESET_CONFIG=1; shift ;;
    -h|--help) usage; exit 0 ;;
    *) echo "${RED}unknown arg: $1${X}" >&2; exit 2 ;;
  esac
done
valid_release_tag() { printf '%s\n' "$1" | grep -Eq '^v[0-9]+\.[0-9]+\.[0-9]+(-[0-9A-Za-z][0-9A-Za-z.-]*)?$'; }
release_apk_name() { printf 'panel-assistant-%s-manual-setup-required.apk\n' "$1"; }
release_apk_url() { printf 'https://github.com/%s/releases/download/%s/%s\n' "$REPO" "$1" "$(release_apk_name "$1")"; }
release_checksum_url() { printf '%s.sha256\n' "$(release_apk_url "$1")"; }
release_signature_url() { printf '%s.sig\n' "$(release_checksum_url "$1")"; }
release_helper_name() { printf 'ha-paneld-helper-%s-%s\n' "$1" "$2"; }
release_helper_url() { printf 'https://github.com/%s/releases/download/%s/%s\n' "$REPO" "$1" "$(release_helper_name "$1" "$2")"; }
release_has_helper_assets() {
  local version major minor patch
  version="${1#v}"; version="${version%%-*}"
  IFS=. read -r major minor patch <<< "$version"
  [ "$major" -gt 0 ] || [ "$minor" -gt 9 ] || { [ "$minor" -eq 9 ] && [ "$patch" -ge 4 ]; }
}
write_release_public_key() {
  cat > "$1" <<'EOF'
-----BEGIN PUBLIC KEY-----
MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEA3LH+db6kzNld/ERP612x
UOOG6TINFvuKJKinQAWi6Gfm2jCmW4plhw+w4vXgP8B8FpY0SLatUVo3EeAi+f1K
EHj0syPi7Sx781o1oc9LicQG4LjWVZPe+m4AkPl9ByopobQwYTXOjaq6ZFpFgAZe
NwQ44hg5o9iVKtxpnnjHEc/m6o9TBySQvxDWF3RxCDyPLNBqhrsgKsDlAyh+dtA8
aJpQsDUJoX42xsRvA1hkRCpnWdEs1Bwfyv0ztlOxj7MxeFrFxWc3mnUyGhsn6rCT
O+ygQ2m7FHp3D5t1+wFIendluEzUC+y9MpUHmoyq/lFrVuA8EOiy1U+z7Lr1vBWf
LQIDAQAB
-----END PUBLIC KEY-----
EOF
}

# Normalize every credential source into an owner-only temporary text file before any panel contact.
# Legacy literal flags remain accepted for compatibility, but all descendant curl operations receive
# only file paths. A trailing text-file line ending is ignored; embedded line breaks and NUL bytes are
# rejected because HA and MQTT credentials are single-line values.
prepare_secret() {
  local label="$1" literal_var="$2" input_var="$3" source_count_var="$4" output_var="$5" filename="$6"
  local literal="${!literal_var}" source="${!input_var}" source_count="${!source_count_var}" bytes value output mode group_other line_feeds last_byte
  [ "$source_count" -le 1 ] || fail "$label source was supplied more than once" \
    "Use one file option only so the credential source is unambiguous."
  if [ -n "$source" ]; then
    [ ! -L "$source" ] || fail "$label file must not be a symbolic link: $source"
    [ -f "$source" ] || fail "$label file is not a regular file: $source"
    [ -r "$source" ] || fail "$label file is not readable: $source"
    bytes="$(wc -c < "$source")"
    [ "$bytes" -gt 0 ] || fail "$label file is empty: $source"
    [ "$bytes" -le 65536 ] || fail "$label file is too large: $source (maximum 65536 bytes)"
    case "$(uname -s 2>/dev/null || true)" in
      MINGW*|MSYS*|CYGWIN*) ;;
      *)
        mode="$(stat -c '%a' "$source" 2>/dev/null || stat -f '%Lp' "$source" 2>/dev/null || true)"
        case "$mode" in *[!0-7]*|'') fail "could not verify owner-only permissions on $label file: $source" ;; esac
        group_other="${mode: -2}"
        [ "$group_other" = 00 ] || fail "$label file is readable by other users: $source" \
          "Protect that file with chmod 600, then retry."
        ;;
    esac
    if od -An -tx1 "$source" 2>/dev/null | tr ' ' '\n' | grep -qx '00'; then
      fail "$label file contains a NUL byte: $source" "Use one plain-text credential line."
    fi
    line_feeds="$(LC_ALL=C tr -cd '\n' < "$source" | wc -c | tr -d ' ')"
    case "$line_feeds" in ''|*[!0-9]*) fail "could not validate the line structure of $label file: $source" ;; esac
    [ "$line_feeds" -le 1 ] || fail "$label file contains more than one text line: $source"
    if [ "$line_feeds" -eq 1 ]; then
      last_byte="$(tail -c 1 "$source" 2>/dev/null | od -An -tx1 | tr -d ' \n')"
      [ "$last_byte" = 0a ] || fail "$label file contains an embedded line break: $source"
    fi
    value="$(LC_ALL=C command cat -- "$source")"
    value="${value%$'\r'}"
    [ -n "$value" ] || fail "$label file contains no credential text: $source"
    case "$value" in *$'\n'*|*$'\r'*) fail "$label file contains more than one text line: $source" ;; esac
  else
    value="$literal"
  fi
  if [ -n "$value" ]; then
    if [ -z "$SECRET_DIR" ]; then
      SECRET_DIR="$(mktemp -d)" || fail "could not create private credential storage"
      chmod 700 "$SECRET_DIR" || fail "could not protect private credential storage"
    fi
    output="$SECRET_DIR/$filename"
    printf '%s' "$value" > "$output"
    chmod 600 "$output"
    printf -v "$literal_var" '%s' "$value"
    printf -v "$output_var" '%s' "$output"
  fi
}

prepare_secret "MQTT password" MQTT_PASS MQTT_PASS_INPUT_FILE MQTT_PASS_SOURCE_COUNT MQTT_PASS_SECRET_FILE mqtt-password
prepare_secret "Home Assistant token" HA_TOKEN HA_TOKEN_INPUT_FILE HA_TOKEN_SOURCE_COUNT HA_TOKEN_SECRET_FILE ha-token
prepare_secret "Home Assistant password" HA_PASS HA_PASS_INPUT_FILE HA_PASS_SOURCE_COUNT HA_PASS_SECRET_FILE ha-password

if [ -n "$APK_RELEASE_TAG" ]; then
  valid_release_tag "$APK_RELEASE_TAG" || { echo "${RED}✗ invalid release tag: $APK_RELEASE_TAG${X}" >&2; exit 2; }
  [ -n "$APK" ] || { echo "${RED}✗ --release-tag requires --apk.${X}" >&2; exit 2; }
fi
if { [ -n "$HA_USER" ] && [ -z "$HA_PASS" ]; } || { [ -z "$HA_USER" ] && [ -n "$HA_PASS" ]; }; then
  echo "${RED}✗ --ha-user and --ha-pass must be supplied together.${X}" >&2
  exit 2
fi
if [ -n "$HA_TOKEN" ] && [ -n "$HA_USER" ]; then
  echo "${RED}✗ choose either --ha-token or --ha-user/--ha-pass, not both.${X}" >&2
  exit 2
fi
if { [ -n "$HA_USER" ] || [ -n "$HA_TOKEN" ]; } && [ -z "$HA_URL" ]; then
  echo "${RED}✗ --ha-user/--ha-pass and --ha-token require --ha-url.${X}" >&2
  exit 2
fi
if [ "$RESET_CONFIG" = 1 ] && [ "$VERIFY_ONLY" = 1 ]; then
  echo "${RED}✗ --reset-config cannot be combined with --verify.${X} --verify never changes the panel." >&2
  exit 2
fi
if [ "$RESET_CONFIG" = 1 ] && [ -n "$EXPORT_FILE" ]; then
  echo "${RED}✗ --reset-config cannot be combined with --export.${X}" >&2
  echo "   Reset is irreversible and makes no backup. Run --export FILE separately first if you want a settings export." >&2
  exit 2
fi
if [ "$RESET_CONFIG" = 1 ] && [ -n "$RESTORE_FILE" ]; then
  echo "${RED}✗ --reset-config cannot be combined with --restore/--restore-fleet.${X}" >&2
  echo "   Erasing the configuration and then importing one are opposite intents. Run the reset first, then restore." >&2
  exit 2
fi
if [ "$BUILTIN" = 1 ] && [ -z "$HA_TOKEN" ] && [ -z "$HA_USER" ]; then
  echo "${RED}✗ --builtin needs Home Assistant credentials during non-interactive provisioning.${X}" >&2
  echo "   Run without --builtin, then open the printed guided-setup URL and select the Built-in renderer." >&2
  exit 2
fi
# Both seeds configure ha-paneld's OWN renderer, so they are meaningless against a foreign dashboard
# app and are refused before the panel is contacted. `--builtin` states that intent here; a panel
# already running the built-in renderer is accepted at seed time from its reported configuration.
if [ "$VERIFY_ONLY" = 1 ] && { [ "$HOME_DASHBOARD_SET" = 1 ] || [ "$ENTITY_FILTER_SET" = 1 ]; }; then
  echo "${RED}✗ --home-dashboard/--entity-filter cannot be combined with --verify.${X} --verify never changes the panel." >&2
  exit 2
fi
if [ "$RESET_CONFIG" = 1 ] && { [ "$HOME_DASHBOARD_SET" = 1 ] || [ "$ENTITY_FILTER_SET" = 1 ]; }; then
  echo "${RED}✗ --home-dashboard/--entity-filter cannot be combined with --reset-config.${X}" >&2
  echo "   Reset erases the configuration and restarts guided setup, which would discard the seeded values. Reset first, then re-run with the seeds." >&2
  exit 2
fi
# `on`/`off` only: an unrecognised spelling must not quietly become "off" and leave a slow panel
# rendering unfiltered. The dashboard PATH is deliberately not shape-checked here — the app's
# `home_dashboard` validator is the single authority for that grammar, and a second expression in
# bash would be a copy that can disagree with it.
case "$ENTITY_FILTER" in
  ""|on|off) ;;
  *)
    echo "${RED}✗ --entity-filter must be on or off (got: $ENTITY_FILTER).${X}" >&2
    echo "   on limits Home Assistant's state stream to the entities the dashboard uses; off leaves the panel on the full stream." >&2
    exit 2
    ;;
esac
if [ "$ENTITY_FILTER_SET" = 1 ] && [ -z "$ENTITY_FILTER" ]; then
  echo "${RED}✗ --entity-filter needs a value: on or off.${X}" >&2
  exit 2
fi
# Reject an unknown transport here rather than after the panel has been contacted. `syslog` is the
# retired spelling of `syslog-tcp` and stays accepted so older provisioning commands keep working.
case "$LOG_PROTO" in
  ""|syslog-udp|syslog-tcp|http|syslog|udp|tcp) ;;
  *)
    echo "${RED}✗ --log-proto must be syslog-udp, syslog-tcp, or http (got: $LOG_PROTO).${X}" >&2
    echo "   syslog-tcp is the default because connection failures are observable; choose syslog-udp explicitly for a UDP-only collector." >&2
    exit 2
    ;;
esac
# A host enables shipping. Materialise the fresh-install default instead of relying on whatever
# fallback the installed app inherited, so this command has one durable and truthful meaning on both
# fresh and upgraded panels. Explicit aliases remain server-normalized for compatibility.
if [ -n "$LOG_HOST" ] && [ -z "$LOG_PROTO" ]; then LOG_PROTO=syslog-tcp; fi

# Every credential/config POST has both a connection deadline and an end-to-end deadline. Keep the
# values overridable for unusually slow LANs and deterministic regression tests, but never allow an
# empty, zero, or non-numeric override to turn a bounded operation back into an unbounded one.
HA_AUTH_CONNECT_TIMEOUT_SECONDS="${HA_AUTH_CONNECT_TIMEOUT_SECONDS:-5}"
HA_AUTH_TIMEOUT_SECONDS="${HA_AUTH_TIMEOUT_SECONDS:-30}"
PANEL_POST_CONNECT_TIMEOUT_SECONDS="${PANEL_POST_CONNECT_TIMEOUT_SECONDS:-5}"
PANEL_POST_TIMEOUT_SECONDS="${PANEL_POST_TIMEOUT_SECONDS:-30}"
PANEL_RESTORE_TIMEOUT_SECONDS="${PANEL_RESTORE_TIMEOUT_SECONDS:-60}"
APK_INSTALL_TIMEOUT_SECONDS="${APK_INSTALL_TIMEOUT_SECONDS:-300}"
APP_LAUNCH_COMMAND_TIMEOUT_SECONDS="${APP_LAUNCH_COMMAND_TIMEOUT_SECONDS:-15}"
# How long the agent may take to answer /health after a launch route is issued. Separate from the
# launch command deadline above: issuing the intent is fast, but the FIRST start after an upgrade
# does schema migration and catalog work, which on a slow panel with a large store runs into
# minutes. Panels in #74 were serving ~2 minutes after provisioning had already declared failure.
APP_LAUNCH_PROBE_SECONDS="${APP_LAUNCH_PROBE_SECONDS:-20}"
APP_HEALTH_TIMEOUT_SECONDS="${APP_HEALTH_TIMEOUT_SECONDS:-180}"
STORAGE_HEALTH_VERIFY_ATTEMPTS="${STORAGE_HEALTH_VERIFY_ATTEMPTS:-6}"
STORAGE_HEALTH_VERIFY_POLL_SECONDS="${STORAGE_HEALTH_VERIFY_POLL_SECONDS:-2}"
# Deadline for the two package queries taken while classifying an unreachable status endpoint. Kept
# separate from the general adb deadline: this runs before anything is installed, so a wedged package
# manager still refuses promptly. Older panels can need about nine seconds to return both answers.
STORAGE_HEALTH_PACKAGE_QUERY_SECONDS="${STORAGE_HEALTH_PACKAGE_QUERY_SECONDS:-15}"
# Deadline for the package re-check taken immediately before an erasure, after a confirmation that
# may have been held open indefinitely. Slightly longer than the pre-install probe because the panel
# has been idle in the meantime, and short enough that a wedged panel cannot stall a confirmed reset.
RESET_RECHECK_PACKAGE_QUERY_SECONDS="${RESET_RECHECK_PACKAGE_QUERY_SECONDS:-10}"
# Total deadline for reading the Configure settings schema during verification. A verify that follows
# an app restart can reach the HTTP server a moment before it answers, so an unanswered read is
# retried a few times inside this bound instead of failing a healthy panel.
CONFIG_SCHEMA_VERIFY_TIMEOUT_SECONDS="${CONFIG_SCHEMA_VERIFY_TIMEOUT_SECONDS:-12}"
for timeout_name in HA_AUTH_CONNECT_TIMEOUT_SECONDS HA_AUTH_TIMEOUT_SECONDS \
    PANEL_POST_CONNECT_TIMEOUT_SECONDS PANEL_POST_TIMEOUT_SECONDS PANEL_RESTORE_TIMEOUT_SECONDS \
    APK_INSTALL_TIMEOUT_SECONDS APP_LAUNCH_COMMAND_TIMEOUT_SECONDS APP_LAUNCH_PROBE_SECONDS \
    APP_HEALTH_TIMEOUT_SECONDS STORAGE_HEALTH_VERIFY_ATTEMPTS \
    STORAGE_HEALTH_PACKAGE_QUERY_SECONDS RESET_RECHECK_PACKAGE_QUERY_SECONDS \
    CONFIG_SCHEMA_VERIFY_TIMEOUT_SECONDS; do
  timeout_value="${!timeout_name}"
  case "$timeout_value" in
    ''|*[!0-9]*|0)
      echo "${RED}✗ $timeout_name must be a positive whole number of seconds.${X}" >&2
      exit 2
      ;;
  esac
done
case "$STORAGE_HEALTH_VERIFY_POLL_SECONDS" in
  ''|*[!0-9]*)
    echo "${RED}✗ STORAGE_HEALTH_VERIFY_POLL_SECONDS must be a non-negative whole number of seconds.${X}" >&2
    exit 2
    ;;
esac

# A CLI restore is a config JSON import, whose server envelope is 1 MiB. Validate it before even
# contacting adb so a missing, unreadable, empty, symlinked, or oversized input cannot fail after an
# APK/helper install, permission grant, launch, or other panel mutation has already happened.
if [ -n "$RESTORE_FILE" ]; then
  [ ! -L "$RESTORE_FILE" ] || fail "config import must be a regular file, not a symbolic link: $RESTORE_FILE" \
    "Choose the config JSON file itself, then retry the identical command."
  [ -f "$RESTORE_FILE" ] || fail "config import file not found: $RESTORE_FILE" \
    "--restore accepts JSON created by --export; complete .hpb backups are restored from the panel's Install page."
  [ -r "$RESTORE_FILE" ] || fail "config import file is not readable: $RESTORE_FILE" \
    "Grant this user read access to the config JSON, then retry the identical command."
  restore_bytes="$(wc -c < "$RESTORE_FILE")"
  [ "$restore_bytes" -gt 0 ] || fail "config import file is empty: $RESTORE_FILE" \
    "Export the panel config again and verify the JSON file is non-empty before retrying."
  [ "$restore_bytes" -le 1048576 ] || fail "config import file is too large: $RESTORE_FILE ($restore_bytes bytes; maximum 1048576)" \
    "--restore accepts config JSON only. Restore a complete .hpb backup from the panel's Install page."
  restore_json_runtime=""
  for candidate in python3 python; do
    if command -v "$candidate" >/dev/null 2>&1 &&
       "$candidate" -c 'import sys; raise SystemExit(0 if sys.version_info[0] == 3 else 1)' >/dev/null 2>&1; then
      restore_json_runtime="$candidate"
      break
    fi
  done
  [ -n "$restore_json_runtime" ] || fail "config import validation needs Python 3 before the panel can be changed" \
    "Install Python 3, then retry. Complete .hpb backups are restored from the panel's Install page."
  "$restore_json_runtime" - "$RESTORE_FILE" <<'PY' || fail \
    "config import is not a valid ha-paneld config JSON export: $RESTORE_FILE" \
    "Use JSON created by provision.sh --export. Restore a complete .hpb backup from the panel's Install page."
import json
import sys

try:
    with open(sys.argv[1], "r", encoding="utf-8") as source:
        bundle = json.load(source)
except (OSError, UnicodeError, json.JSONDecodeError):
    raise SystemExit(1)
valid = (
    type(bundle) is dict
    and set(bundle).issubset({"kind", "schema", "exported_at", "exported_by", "values"})
    and bundle.get("kind") == "ha-paneld-config"
    and type(bundle.get("schema")) is int
    and bundle["schema"] >= 1
    and ("exported_at" not in bundle or type(bundle["exported_at"]) is str)
    and ("exported_by" not in bundle or type(bundle["exported_by"]) is str)
    and type(bundle.get("values")) is dict
    and all(type(key) is str and type(value) is str for key, value in bundle["values"].items())
)
if not valid:
    raise SystemExit(1)
PY
fi
HOST="${TARGET%:*}"
[ "$HOST" != "$TARGET" ] || HOST="$TARGET"
URL="http://$HOST:8888"
PROVISION_FAILED=0
PROVISIONING_PLAN_AVAILABLE=0

# The app, not Bash, owns profile parsing, hardware matching and provisioning policy. Bash only waits
# for and renders the app's terminal-safe view. HTTP 503 means profile activation is still settling;
# a paired install must not silently succeed without the endpoint promised by that release. `--verify`
# remains useful against pre-0.9.4 panels: a legacy 404 is advisory and all established GET checks run.
show_provisioning_plan() {
  local required="$1" label="$2"
  local timeout="${PROVISIONING_PLAN_TIMEOUT_SECONDS:-$APP_HEALTH_TIMEOUT_SECONDS}" deadline remaining request_timeout code="" body plan_text="" status=unavailable
  case "$timeout" in ''|*[!0-9]*|0) timeout="$APP_HEALTH_TIMEOUT_SECONDS" ;; esac
  deadline=$((SECONDS + timeout))
  body="$(mktemp)"
  step "🧭 panel profile" "$label"
  while [ "$SECONDS" -lt "$deadline" ]; do
    remaining=$((deadline - SECONDS))
    request_timeout=4
    [ "$remaining" -lt "$request_timeout" ] && request_timeout="$remaining"
    : > "$body"
    if code="$(curl -sS --max-time "$request_timeout" -o "$body" -w '%{http_code}' \
        "$URL/api/v1/provisioning/plan.txt" 2>/dev/null)"; then
      case "$code" in
        200)
          if [ -s "$body" ]; then
            plan_text="$(tr -d '\r' < "$body" | sanitize_terminal)"
            if [ -n "$plan_text" ]; then
              printf '%s\n' "$plan_text"
              PROVISIONING_PLAN_AVAILABLE=1
              rm -f "$body"
              return 0
            fi
          fi
          status=empty
          ;;
        404)
          rm -f "$body"
          if [ "$required" = 1 ]; then
            warn "the installed app does not provide its paired provisioning-plan endpoint; the install cannot be verified as complete"
            return 1
          fi
          warn "this older ha-paneld does not provide profile-guided provisioning; using the legacy verification checks"
          return 0
          ;;
        503) status=starting ;;
        *) status="HTTP ${code:-unknown}" ;;
      esac
    else
      status=unreachable
    fi
    [ "$SECONDS" -lt "$deadline" ] && sleep 1
  done
  rm -f "$body"
  if [ "$required" = 1 ]; then
    warn "the provisioning plan stayed ${status:-unavailable} for ${timeout}s; the paired app did not finish profile activation"
    return 1
  fi
  warn "profile-guided provisioning stayed ${status:-unavailable}; the installed app could not complete profile verification"
  return 1
}

# ── Closing guidance ────────────────────────────────────────────────────────────────────────────
# GET /api/v1/setup is the app's own authority for "what does this panel still need, and what is the
# one next thing to do". Rendering it here, instead of re-deriving the answer from config fields, is
# what keeps the installer's last line identical to the panel's standing screen and the /setup page.
# It replaced an unconditional "connect Home Assistant in your browser" that fired whenever OAuth was
# not yet configured — including on a brand-new panel, where that link cannot work at all, because
# POST /api/v1/ha/oauth/start rejects the request until an ha_url has been saved.
#
# Two properties of the journey have to survive the trip through bash:
#   1. `next` is the first BLOCKED stage, or failing that the first IN_FLIGHT one. So a non-null
#      `next` does NOT mean the user must act. In-flight is reported as progress, never as a fault.
#   2. `detail` is a machine token (a package name, an MQTT state, "awaiting_frontend"), never
#      display prose. The wording below is therefore ours, and an unrecognised stage falls through to
#      generic guidance rather than printing a raw token — which is what lets the app add a stage
#      (as ENTITY_FILTER was added) without silently degrading this output.
read_setup_journey() {
  local body steps line cfg
  SETUP_JOURNEY_AVAILABLE=0; SETUP_COMPLETE=0; SETUP_REPAIR=0
  SETUP_NEXT=""; SETUP_NEXT_STATUS=""; SETUP_NEXT_DETAIL=""
  LEGACY_MQTT_SET=0; LEGACY_HA_URL_SET=0; LEGACY_HA_AUTH_CONFIGURED=0
  body="$(curl -fsS --max-time 5 "$URL/api/v1/setup" 2>/dev/null || true)"
  case "$body" in
    *'"steps"'*) SETUP_JOURNEY_AVAILABLE=1 ;;
    *)
      # Pre-wizard build: fall back to the config fields this script already reads elsewhere.
      cfg="$(curl -fsS --max-time 3 "$URL/api/v1/config" 2>/dev/null || true)"
      [ -n "$cfg" ] || return 0
      printf '%s' "$cfg" | grep -Eq '"mqtt_broker"[[:space:]]*:[[:space:]]*"[^"]+"' && LEGACY_MQTT_SET=1
      printf '%s' "$cfg" | grep -Eq '"ha_url"[[:space:]]*:[[:space:]]*"[^"]+"' && LEGACY_HA_URL_SET=1
      printf '%s' "$cfg" | grep -Eq '"ha_auth"[[:space:]]*:[[:space:]]*\{[^}]*"configured"[[:space:]]*:[[:space:]]*true' &&
        LEGACY_HA_AUTH_CONFIGURED=1
      return 0
      ;;
  esac
  printf '%s' "$body" | grep -Eq '"complete"[[:space:]]*:[[:space:]]*true' && SETUP_COMPLETE=1
  printf '%s' "$body" | grep -Eq '"repair"[[:space:]]*:[[:space:]]*true' && SETUP_REPAIR=1
  SETUP_NEXT="$(printf '%s' "$body" | sed -n 's/.*"next"[[:space:]]*:[[:space:]]*"\([a-z_]*\)".*/\1/p')"
  [ -n "$SETUP_NEXT" ] || return 0
  # One step object per line, so the greedy field matches below cannot cross an object boundary.
  steps="$(printf '%s' "$body" | tr '\n' ' ' | sed 's/},[[:space:]]*{/}\n{/g')"
  line="$(printf '%s\n' "$steps" | grep -F "\"stage\":\"$SETUP_NEXT\"" | head -1 || true)"
  SETUP_NEXT_STATUS="$(printf '%s' "$line" | sed -n 's/.*"status"[[:space:]]*:[[:space:]]*"\([a-z_]*\)".*/\1/p')"
  SETUP_NEXT_DETAIL="$(printf '%s' "$line" | sed -n 's/.*"detail"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p' | sanitize_terminal)"
  return 0
}

# One short imperative per stage. Deliberately not derived from the stage name: these are the words a
# first-time installer reads, and they must match what the wizard page actually asks for.
setup_next_headline() {
  case "$1" in
    identity)         echo "confirm this panel's name" ;;
    mqtt_broker)      echo "set the MQTT broker" ;;
    mqtt_credentials) echo "enter MQTT credentials the broker accepts" ;;
    mqtt_connection)  echo "get MQTT connected" ;;
    renderer)         echo "choose how this panel shows its dashboard" ;;
    ha_url)           echo "set the Home Assistant address" ;;
    ha_credentials)   echo "sign in to Home Assistant" ;;
    home_dashboard)   echo "choose which Home Assistant dashboard to show" ;;
    entity_filter)    echo "answer the entity-filter question" ;;
    render_proof)     echo "confirm the dashboard renders" ;;
    *)                echo "finish guided setup" ;;
  esac
}

# An extra clarifying line for the tokens where the stage name alone leaves the user guessing why.
setup_next_reason() {
  case "$1/$2" in
    mqtt_connection/auth-failed)      echo "The broker is reachable but rejected these credentials." ;;
    mqtt_connection/unreachable)      echo "The broker did not answer at that address." ;;
    mqtt_connection/config-error)     echo "That broker URL is not valid." ;;
    renderer/webview_too_old_fixable) echo "This panel's system WebView is too old for the built-in renderer; setup offers the update." ;;
    renderer/webview_too_old)         echo "This panel's system WebView is too old for the built-in renderer." ;;
    ha_credentials/*)                 echo "Guided setup offers Browser sign-in, so no credentials are typed on the panel." ;;
    *) : ;;
  esac
}

print_next_step() {
  local headline reason
  if [ "$SETUP_JOURNEY_AVAILABLE" != 1 ]; then
    # Pre-wizard panel. Only ever offer the Home Assistant sign-in link once an address exists to
    # sign in to; without one the OAuth endpoint refuses the request.
    if [ "$LEGACY_HA_URL_SET" = 1 ] && [ "$LEGACY_HA_AUTH_CONFIGURED" != 1 ]; then
      echo "   ${CYN}${B}Next: connect Home Assistant in your browser${X} — ${B}$URL/configure#cfg-ha-oauth${X}"
    elif [ "$LEGACY_MQTT_SET" != 1 ] && [ "$LEGACY_HA_URL_SET" != 1 ]; then
      echo "   ${CYN}${B}Next: configure this panel in your browser${X} — ${B}$URL/configure${X}"
    fi
    return 0
  fi
  [ "$SETUP_COMPLETE" = 1 ] && return 0
  if [ -z "$SETUP_NEXT" ]; then
    echo "   ${CYN}${B}Next: finish guided setup${X} — ${B}$URL/setup${X}"
    return 0
  fi
  if [ "$SETUP_NEXT_STATUS" = "in_flight" ]; then
    # Never present work in progress as something to fix; that false positive is what makes a user
    # "repair" a panel that was about to succeed on its own.
    echo "   ${CYN}${B}Nothing to do yet${X} — the panel is still working on: $(setup_next_headline "$SETUP_NEXT")."
    echo "   ${D}Follow it at $URL/setup${X}"
    return 0
  fi
  headline="$(setup_next_headline "$SETUP_NEXT")"
  if [ "$SETUP_REPAIR" = 1 ]; then
    echo "   ${YEL}${B}This panel needs attention: $headline${X} — ${B}$URL/setup${X}"
  else
    echo "   ${CYN}${B}Next: $headline${X} — ${B}$URL/setup${X}"
  fi
  reason="$(setup_next_reason "$SETUP_NEXT" "$SETUP_NEXT_DETAIL")"
  [ -z "$reason" ] || echo "   ${D}$reason${X}"
  echo "   ${D}Or on the panel itself: tap Set up on its screen.${X}"
  return 0
}

STORAGE_HEALTH_RESULT=""
STORAGE_HEALTH_STATE=""
STORAGE_HEALTH_SCHEMA_VERSION=""
STORAGE_HEALTH_QUICK_CHECK=""
STORAGE_HEALTH_OBSERVATION_NONCE=""
POWER_SAFETY_RESULT=""
POWER_SAFETY_STATE=""
# Never read before classify_package_presence sets it; initialised so an unclassified read is the
# fail-closed value rather than an unbound-variable abort.
PACKAGE_PRESENCE="unknown"
PACKAGE_DATA_RECORD="unknown"

# Read the small status contract without adding jq as a fleet-host dependency. A successful response
# without storage_health is distinguishable only as a legacy build until this run installs a current
# APK; a present-but-invalid object is already evidence of a broken current contract.
read_storage_health() {
  local status flat_status
  STORAGE_HEALTH_RESULT="transport"
  STORAGE_HEALTH_STATE=""
  STORAGE_HEALTH_SCHEMA_VERSION=""
  STORAGE_HEALTH_QUICK_CHECK=""
  if ! status="$(curl -fsS --max-time 5 "$URL/api/v1/status" 2>/dev/null)"; then
    return 0
  fi
  flat_status="$(printf '%s' "$status" | tr '\n' ' ' | sed 's/^[[:space:]]*//; s/[[:space:]]*$//')"
  if ! printf '%s' "$status" | grep -Eq '"storage_health"[[:space:]]*:'; then
    case "$flat_status" in
      \{*\}) STORAGE_HEALTH_RESULT="absent" ;;
      *) STORAGE_HEALTH_RESULT="malformed" ;;
    esac
    return 0
  fi
  STORAGE_HEALTH_STATE="$(printf '%s' "$flat_status" | sed -n \
    's/.*"storage_health"[[:space:]]*:[[:space:]]*{[^}]*"state"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p')"
  STORAGE_HEALTH_SCHEMA_VERSION="$(printf '%s' "$flat_status" | sed -n \
    's/.*"storage_health"[[:space:]]*:[[:space:]]*{[^}]*"schema_version"[[:space:]]*:[[:space:]]*\([0-9][0-9]*\).*/\1/p')"
  STORAGE_HEALTH_QUICK_CHECK="$(printf '%s' "$flat_status" | sed -n \
    's/.*"storage_health"[[:space:]]*:[[:space:]]*{[^}]*"quick_check"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p')"
  STORAGE_HEALTH_STATE="$(printf '%s' "$STORAGE_HEALTH_STATE" | sanitize_terminal)"
  if [ -z "$STORAGE_HEALTH_STATE" ]; then
    STORAGE_HEALTH_RESULT="malformed"
    return 0
  fi
  case "$STORAGE_HEALTH_STATE" in
    healthy|unchecked|warning|critical|database_failure) STORAGE_HEALTH_RESULT="valid" ;;
    *) STORAGE_HEALTH_RESULT="unknown" ;;
  esac
}

# Compatibility admission needs one uncached observation from the current app. Keeping this distinct
# from routine status rendering makes the freshness requirement visible at both the call site and in
# the HTTP trace used by the cross-entry-point tests.
read_storage_health_refresh() {
  local requested_nonce="$1" status flat_status nonce_count schema_count quick_count
  STORAGE_HEALTH_RESULT="transport"
  STORAGE_HEALTH_STATE=""
  STORAGE_HEALTH_SCHEMA_VERSION=""
  STORAGE_HEALTH_QUICK_CHECK=""
  STORAGE_HEALTH_OBSERVATION_NONCE=""
  if ! status="$(curl -fsS --max-time 15 "$URL/api/v1/status?database_observation_nonce=$requested_nonce" 2>/dev/null)"; then return 0; fi
  flat_status="$(printf '%s' "$status" | tr '\n' ' ' | sed 's/^[[:space:]]*//; s/[[:space:]]*$//')"
  [ "$(printf '%s' "$flat_status" | grep -o '"storage_health"[[:space:]]*:' | wc -l | tr -d ' ')" = 1 ] || { STORAGE_HEALTH_RESULT="malformed"; return 0; }
  nonce_count="$(printf '%s' "$flat_status" | grep -o '"database_observation_nonce"[[:space:]]*:' | wc -l | tr -d ' ')"
  [ "$nonce_count" = 1 ] || { STORAGE_HEALTH_RESULT="malformed"; return 0; }
  schema_count="$(printf '%s' "$flat_status" | grep -o '"schema_version"[[:space:]]*:' | wc -l | tr -d ' ')"
  quick_count="$(printf '%s' "$flat_status" | grep -o '"quick_check"[[:space:]]*:' | wc -l | tr -d ' ')"
  [ "$schema_count" = 1 ] && [ "$quick_count" = 1 ] || { STORAGE_HEALTH_RESULT="malformed"; return 0; }
  STORAGE_HEALTH_STATE="$(printf '%s' "$flat_status" | sed -n 's/.*"storage_health"[[:space:]]*:[[:space:]]*{[^}]*"state"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p')"
  STORAGE_HEALTH_SCHEMA_VERSION="$(printf '%s' "$flat_status" | sed -n 's/.*"storage_health"[[:space:]]*:[[:space:]]*{[^}]*"schema_version"[[:space:]]*:[[:space:]]*\([0-9][0-9]*\).*/\1/p')"
  STORAGE_HEALTH_QUICK_CHECK="$(printf '%s' "$flat_status" | sed -n 's/.*"storage_health"[[:space:]]*:[[:space:]]*{[^}]*"quick_check"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p')"
  STORAGE_HEALTH_OBSERVATION_NONCE="$(printf '%s' "$flat_status" | sed -n 's/.*"database_observation_nonce"[[:space:]]*:[[:space:]]*"\([0-9a-f]*\)".*/\1/p')"
  STORAGE_HEALTH_RESULT="valid"
}

# Package replacement and process startup are asynchronous. Poll incomplete results after mutation,
# but cap both attempts and per-request duration so a wedged panel cannot hang a fleet worker.
read_storage_health_for_verify() {
  local attempt=1 waiting=0
  while :; do
    read_storage_health
    [ "$VERIFY_DIRECT_GRANTS" = 1 ] || return 0
    case "$STORAGE_HEALTH_RESULT:$STORAGE_HEALTH_STATE" in
      valid:unchecked|transport:|absent:) ;;
      *) return 0 ;;
    esac
    [ "$attempt" -lt "$STORAGE_HEALTH_VERIFY_ATTEMPTS" ] || return 0
    if [ "$waiting" = 0 ]; then
      warn "the installed app's storage health check is not ready; waiting for a bounded retry"
      waiting=1
    fi
    attempt=$((attempt + 1))
    [ "$STORAGE_HEALTH_VERIFY_POLL_SECONDS" = 0 ] || sleep "$STORAGE_HEALTH_VERIFY_POLL_SECONDS"
  done
}

# Read the app-owned power classification. Bash deliberately does not reconstruct this from raw Android
# settings: the app combines its live wake-lock state, native timeout, reported source, stay-on mask and
# Doze observations. Missing/legacy data is never called safe.
read_power_safety() {
  local body token_hex
  POWER_SAFETY_RESULT="transport"
  POWER_SAFETY_STATE=""
  body="$(mktemp)" || { POWER_SAFETY_RESULT="malformed"; return 0; }
  if ! curl -fsS --max-time 5 -o "$body" "$URL/api/v1/power-safety/state" 2>/dev/null; then
    rm -f "$body"
    return 0
  fi
  # Validate exact response bytes before Bash command substitution or terminal sanitization can discard
  # NUL/control bytes. Only one controlled token, with the app's optional final LF, can produce green.
  token_hex="$(od -An -tx1 "$body" 2>/dev/null | tr -d ' \n')"
  rm -f "$body"
  case "$token_hex" in
    73616665|736166650a) POWER_SAFETY_STATE="safe" ;;
    63617574696f6e|63617574696f6e0a) POWER_SAFETY_STATE="caution" ;;
    61745f7269736b|61745f7269736b0a) POWER_SAFETY_STATE="at_risk" ;;
    756e6b6e6f776e|756e6b6e6f776e0a) POWER_SAFETY_STATE="unknown" ;;
    *) POWER_SAFETY_RESULT="malformed"; return 0 ;;
  esac
  POWER_SAFETY_RESULT="valid"
}

# Android answers "is this package installed?" and "can the package manager answer at all?" through
# one command and one exit status, and the provisioner used to read them as the same signal: `pm
# path` exits non-zero both when the package manager cannot be reached and when it answers, quite
# correctly, that the package is not installed. A clean panel is the second case, so a first
# installation was refused as though adb had died, and only a hand-sideloaded APK let the next run
# past the gate (#89).
#
# Presence is therefore proved only by a non-empty `package:` path, never by an exit status: adb
# forwards the device's status only when both ends speak shell protocol v2, and `pm` reports a
# missing package as a failure. Absence is concluded only once the package manager has proved, in
# the same completed observation, that it still resolves path queries at all. Everything else stays
# unknown, and every caller fails closed on it.

# The package manager that exists on every Android build. `android` is the framework package; it
# cannot be uninstalled, so resolving it proves the package manager ran and answered, independently
# of whether ha-paneld is installed.
PACKAGE_MANAGER_LIVENESS_PKG="android"

# Ask the panel BOTH questions in ONE bounded shell run, and prove that EVERY PART of it completed.
#
# This has been wrong twice, in the same direction, so the reasoning is recorded rather than the
# conclusion. First it asked two separate `pm path` calls and enumerated the exit statuses it would
# not trust; that list missed the signal statuses, so an interrupted query read as absence. Then it
# put both calls in one marker-bounded shell — but the markers only proved the SHELL finished. A
# target `pm path` that died while the surrounding shell ran on still produced BEGIN, no path, and a
# healthy framework answer, which read as absence again. On a panel that does have ha-paneld that is
# worse than the original bug: it silently skips the pre-upgrade database snapshot, and it lets a
# failed install roll helper state back on a "definitely missing" verdict that was never established.
#
# The lesson both times: a wrapper proving ITSELF complete says nothing about the commands inside it.
# So each child's exit status is now emitted INTO the marked payload and validated with it. A child
# that is killed reports 128+signal and can never be mistaken for one that answered "no such
# package"; a child that never ran leaves its marker missing entirely.
#
# The observation is trusted only when all four markers appear exactly once, in order, carrying this
# run's nonce, and the command itself succeeded. Within that, the package manager must prove itself
# live in THIS run (framework query exited 0 and named a real path), and the target query must have
# completed normally before its silence is read as absence.
#
# Sets PACKAGE_PRESENCE to exactly one of: present, absent, unknown. Callers fail closed on unknown.
classify_package_presence() {
  local seconds="$1" pkg="${2:-$PKG}" nonce out verdict status=0
  PACKAGE_PRESENCE="unknown"
  nonce="$(host_transaction_id)" || return 0
  # `\$?` is escaped so the PANEL's shell expands each child's status, not this one.
  out="$(run_with_deadline "$seconds" adb_exec -s "$TARGET" shell \
    "echo HAPANELD_PKG_BEGIN:$nonce; pm path $pkg; echo HAPANELD_PKG_TARGET:$nonce:\$?; \
     pm path $PACKAGE_MANAGER_LIVENESS_PKG; echo HAPANELD_PKG_LIVE:$nonce:\$?; \
     echo HAPANELD_PKG_END:$nonce" 2>/dev/null)" || status=$?
  [ "$status" -eq 0 ] || return 0
  # A path counts only if it is absolute and free of whitespace. A bare `package:`, a relative
  # fragment or a truncated line proves neither that the package is installed nor that the package
  # manager is answering.
  verdict="$(printf '%s\n' "$out" | tr -d '\r' | awk -v n="$nonce" '
    /^HAPANELD_PKG_BEGIN:/  { split($0, a, ":"); if (a[2] != n || seg != 0) bad = 1; else seg = 1; next }
    /^HAPANELD_PKG_TARGET:/ { split($0, a, ":")
                              if (a[2] != n || seg != 1 || a[3] !~ /^[0-9]+$/) bad = 1
                              else { trc = a[3]; seg = 2 }
                              next }
    /^HAPANELD_PKG_LIVE:/   { split($0, a, ":")
                              if (a[2] != n || seg != 2 || a[3] !~ /^[0-9]+$/) bad = 1
                              else { lrc = a[3]; seg = 3 }
                              next }
    /^HAPANELD_PKG_END:/    { split($0, a, ":"); if (a[2] != n || seg != 3) bad = 1; else seg = 4; next }
    seg == 1 && /^package:/ { if ($0 ~ /^package:\/[^ \t]+$/) target = 1; else malformed = 1 }
    seg == 2 && /^package:/ { if ($0 ~ /^package:\/[^ \t]+$/) liveness = 1; else malformed = 1 }
    END {
      # A `package:` line that is not an absolute, whitespace-free path is the panel saying something
      # about the package that cannot be read. That is an untrustworthy answer, never a negative one:
      # calling it absence would skip a database snapshot or roll back on a verdict never established.
      if (bad || malformed || seg != 4) { print "unknown"; exit }
      # The package manager must have proved itself inside this same observation.
      if (lrc + 0 != 0 || !liveness) { print "unknown"; exit }
      if (target) { print (trc + 0 == 0) ? "present" : "unknown"; exit }
      # Silence is a real answer only from a child that completed normally. `pm` reports a missing
      # package as 0 on some builds and 1 on others; anything else — and 128+signal in particular —
      # means the child did not answer, which must never be read as absence.
      print (trc + 0 == 0 || trc + 0 == 1) ? "absent" : "unknown"
    }')"
  case "$verdict" in
    present|absent) PACKAGE_PRESENCE="$verdict" ;;
  esac
  return 0
}

# Which installed package holds this panel's ha-paneld data? Both identities may be installed during
# the handover, and before it begins the bridge is the only one there. The successor is preferred
# because it owns the state as soon as it exists, and the bridge answers for it until then. Resolved
# once per run: the answer must not change between the observation that reads a database and the
# mutation that acts on it.
resolve_data_package() {
  local verdict
  [ -z "$DATA_PKG" ] || return 0
  DATA_PKG="$PKG"
  # Every caller classifies the install target immediately before asking, and that verdict is the
  # authority for it. Only its proven absence can make the bridge the data holder, so only then is a
  # second nonce-bound observation worth a panel round trip. `unknown` keeps the install target,
  # whose own caller already refuses on it.
  [ "$PACKAGE_PRESENCE" = absent ] || return 0
  # Asked in a subshell so this resolution cannot overwrite the install target's own verdict.
  verdict="$(classify_package_presence "$ADB_COMMAND_TIMEOUT_SECONDS" "$LEGACY_PKG"; printf '%s\n' "$PACKAGE_PRESENCE")"
  [ "$verdict" = present ] || return 0
  DATA_PKG="$LEGACY_PKG"
  return 0
}

# `pm uninstall -k` removes the installed path while retaining a package-manager record and app
# data for a later reinstall. Rootless hosts cannot inspect /data/data directly, so `pm path` absence
# alone is never fresh-install proof. This second nonce-bound observation includes uninstalled
# records (`-u`) and accepts fresh only when the exact filtered listing is empty and the command and
# package manager both completed normally.
classify_package_data_record() {
  local seconds="$1" nonce out verdict status=0
  PACKAGE_DATA_RECORD="unknown"
  nonce="$(host_transaction_id)" || return 0
  out="$(run_with_deadline "$seconds" adb_exec -s "$TARGET" shell \
    "echo HAPANELD_DATA_BEGIN:$nonce; pm list packages -u $PKG; echo HAPANELD_DATA_TARGET:$nonce:\$?; \
     pm path $PACKAGE_MANAGER_LIVENESS_PKG; echo HAPANELD_DATA_LIVE:$nonce:\$?; echo HAPANELD_DATA_END:$nonce" 2>/dev/null)" || status=$?
  [ "$status" -eq 0 ] || return 0
  verdict="$(printf '%s\n' "$out" | tr -d '\r' | awk -v n="$nonce" -v p="$PKG" '
    /^HAPANELD_DATA_BEGIN:/  { split($0,a,":"); if (a[2]!=n || seg!=0) bad=1; else seg=1; next }
    /^HAPANELD_DATA_TARGET:/ { split($0,a,":"); if (a[2]!=n || seg!=1 || a[3]!~/^[0-9]+$/) bad=1; else { trc=a[3]; seg=2 }; next }
    /^HAPANELD_DATA_LIVE:/   { split($0,a,":"); if (a[2]!=n || seg!=2 || a[3]!~/^[0-9]+$/) bad=1; else { lrc=a[3]; seg=3 }; next }
    /^HAPANELD_DATA_END:/    { split($0,a,":"); if (a[2]!=n || seg!=3) bad=1; else seg=4; next }
    seg==1 && /^package:/    { if ($0=="package:" p) retained=1; else bad=1; next }
    seg==1 && NF             { bad=1; next }
    seg==2 && /^package:/    { if ($0~/^package:\/[^ \t]+$/) live=1; else bad=1; next }
    END {
      if (bad || seg!=4 || trc+0!=0 || lrc+0!=0 || !live) print "unknown"
      else print retained ? "retained" : "absent"
    }')"
  case "$verdict" in retained|absent) PACKAGE_DATA_RECORD="$verdict" ;; esac
}

# `pm clear` atomically deletes app data and places the package in Android's stopped state, but the
# successful command is only evidence about that instant. Before replacing a rootless package we
# must prove that the old app stayed stopped: if it ran again, it could already have recreated a
# database whose schema the candidate cannot read. The package dump, its child exit status, and a
# same-command package-manager liveness query are bound to this run's nonce so silence or a partial
# reply can never be mistaken for `stopped=true`.
classify_reset_package_stopped() {
  local seconds="$1" nonce out verdict status=0
  RESET_PACKAGE_STOPPED="unknown"
  nonce="$(host_transaction_id)" || return 0
  out="$(run_with_deadline "$seconds" adb_exec -s "$TARGET" shell \
    "echo HAPANELD_RESET_STOP_BEGIN:$nonce; dumpsys package $PKG; echo HAPANELD_RESET_STOP_TARGET:$nonce:\$?; \
     pm path $PACKAGE_MANAGER_LIVENESS_PKG; echo HAPANELD_RESET_STOP_LIVE:$nonce:\$?; \
     echo HAPANELD_RESET_STOP_END:$nonce" 2>/dev/null)" || status=$?
  [ "$status" -eq 0 ] || return 0
  verdict="$(printf '%s\n' "$out" | tr -d '\r' | awk -v n="$nonce" '
    /^HAPANELD_RESET_STOP_BEGIN:/  { split($0,a,":"); if (a[2]!=n || seg!=0) bad=1; else seg=1; next }
    /^HAPANELD_RESET_STOP_TARGET:/ { split($0,a,":"); if (a[2]!=n || seg!=1 || a[3]!~/^[0-9]+$/) bad=1; else { trc=a[3]; seg=2 }; next }
    /^HAPANELD_RESET_STOP_LIVE:/   { split($0,a,":"); if (a[2]!=n || seg!=2 || a[3]!~/^[0-9]+$/) bad=1; else { lrc=a[3]; seg=3 }; next }
    /^HAPANELD_RESET_STOP_END:/    { split($0,a,":"); if (a[2]!=n || seg!=3) bad=1; else seg=4; next }
    seg==1 && /^[ \t]*User 0:/     { user0=1; user0_count++
                                      for (i=1; i<=NF; i++) {
                                        if ($i ~ /^stopped=/) { stopped_count++; stopped=$i }
                                        if ($i ~ /^notLaunched=/) { not_launched_count++; not_launched=$i }
                                      }
                                      next }
    seg==1 && /^[ \t]*User [0-9]+:/ { user0=0; next }
    seg==1 && user0 {
      for (i=1; i<=NF; i++) if ($i ~ /^stopped=/) { stopped_count++; stopped=$i }
      next
    }
    seg==2 && /^package:/ { if ($0~/^package:\/[^ \t]+$/) live=1; else bad=1; next }
    END {
      if (bad || seg!=4 || trc+0!=0 || lrc+0!=0 || !live || user0_count!=1 || stopped_count!=1 || not_launched_count!=1) print "unknown"
      else if (stopped=="stopped=true" && not_launched=="notLaunched=true") print "stopped"
      else if ((stopped=="stopped=true" || stopped=="stopped=false") &&
               (not_launched=="notLaunched=true" || not_launched=="notLaunched=false")) print "running"
      else print "unknown"
    }')"
  case "$verdict" in stopped|running) RESET_PACKAGE_STOPPED="$verdict" ;; esac
}

# Storage health classifies the panel; it does not admit or refuse the replacement.
#
# Every run that reaches this point goes on to replace the package, and an in-place replacement is a
# recovery attempt: the most likely reason a panel reports critical pressure, a failing database, an
# unreadable status endpoint or a state this provisioner does not recognise is precisely that it is
# running a build the user is trying to move off. Refusing to install on that evidence strands the
# panel on the broken build and offers no route back. So every health result here warns and
# continues, and the operations that genuinely cannot proceed on bad input still fail on their own
# evidence: the adb/package query below, the database capture, APK verification and Android's own
# package manager.
#
# Successful absence of the field remains compatible with an older app.
preflight_storage_health() {
  read_storage_health
  case "$STORAGE_HEALTH_RESULT:$STORAGE_HEALTH_STATE" in
    transport:)
      # A missing app has no status server yet and is eligible for first installation. Telling that
      # apart from an app that is installed but not answering needs Android's package manager — and
      # an unanswered package query is not a health result, so it remains strict, because nothing
      # after this line can run without adb either. Normal package absence is not such a failure.
      classify_package_presence "$STORAGE_HEALTH_PACKAGE_QUERY_SECONDS"
      case "$PACKAGE_PRESENCE" in
        present)
          warn "storage health: the installed app's status endpoint could not be reached — advisory for this in-place recovery attempt; an unreachable app is a reason to replace it, not to refuse"
          ;;
        absent)
          echo "   ${D}fresh install — ha-paneld is not installed on this panel yet${X}"
          ;;
        *)
          fail "could not determine whether ha-paneld is already installed" \
            "The app's status endpoint did not answer, and Android's package manager did not return a usable reply either, so mutation safety could not be established." \
            "Nothing was installed or changed. Restore adb/package-manager responsiveness, then retry."
          ;;
      esac
      ;;
    valid:critical)
      if [ "$RESET_CONFIG" = 1 ]; then
        warn "storage health: critical — the requested reset will intentionally erase ha-paneld state if confirmed; Android's package manager will decide whether it can proceed"
      else
        warn "storage health: critical — fixed pressure thresholds are advisory for this in-place recovery attempt; the actual database capture and Android package manager will decide whether the upgrade can proceed"
      fi
      ;;
    valid:database_failure)
      if [ "$RESET_CONFIG" = 1 ]; then
        warn "storage health: database failure — the requested reset will intentionally discard the unhealthy database if confirmed instead of treating it as an admission gate"
      else
        warn "storage health: database failure — admitting this in-place replacement as a recovery attempt; a rejected or missing database snapshot will be reported, but will not preempt the APK install"
      fi
      ;;
    malformed:)
      warn "storage health: the installed app returned malformed status — advisory for this in-place recovery attempt; inspect $URL/api/v1/status afterwards if it persists on the new build"
      ;;
    unknown:*)
      warn "storage health: unrecognised state '$STORAGE_HEALTH_STATE' — advisory for this in-place recovery attempt; update this provisioner if it persists on the new build"
      ;;
  esac
}

# Read the Configure settings schema for verification. Only a read that returns nothing (no connection,
# timeout, HTTP error or empty body) is retried, at most four attempts inside the total deadline. Any
# body that arrives is returned at once for the caller to judge, so a malformed schema fails closed
# without further requests. Giving up returns no body, which the caller's schema check rejects.
read_config_schema() {
  local attempt=1 request_timeout="$CONFIG_SCHEMA_VERIFY_TIMEOUT_SECONDS" deadline body
  deadline=$((SECONDS + CONFIG_SCHEMA_VERIFY_TIMEOUT_SECONDS))
  while :; do
    [ "$request_timeout" -le 5 ] || request_timeout=5
    body="$(curl -fsS --max-time "$request_timeout" "$URL/api/v1/config/schema" 2>/dev/null || true)"
    if [ -n "$body" ]; then
      printf '%s' "$body"
      return 0
    fi
    [ "$attempt" -lt 4 ] || return 0
    # Pause for one second more on each attempt, and only when a request of at least one second still
    # fits before the deadline after that pause (curl reads --max-time 0 as no limit).
    [ $((deadline - SECONDS - attempt)) -ge 1 ] || return 0
    sleep "$attempt"
    attempt=$((attempt + 1))
    # A pause can overrun, so the next request's budget is measured after it; none starts late.
    request_timeout=$((deadline - SECONDS))
    [ "$request_timeout" -ge 1 ] || return 0
  done
}

verify() {
  step "🔎 verifying" "${D}$URL${X}"
  local health diag cfg schema schema_flat rc=0 write_settings_state="" a11y_state="" a11y_enabled_state="" a11y_granted=""
  local package_state="" sdk_level="" notifications_are_runtime=0
  health="$(curl -fsS --max-time 5 "$URL/health" 2>/dev/null || true)"
  read_storage_health_for_verify
  read_power_safety
  diag="$(curl -fsS --max-time 25 "$URL/api/v1/diag" 2>/dev/null || true)"
  if [ -z "$diag" ] && [ -n "$health" ] && [ "$VERIFY_DIRECT_GRANTS" != 1 ]; then
    warn "the agent is healthy but diagnostics are still starting; retrying once"
    sleep 3
    diag="$(curl -fsS --max-time 25 "$URL/api/v1/diag" 2>/dev/null || true)"
  fi
  # A package replacement can start the app before the grants below are applied. Its complete cached
  # support report is therefore not the authority for post-install grant verification: read Android's
  # actual settings back directly, with a short independent bound on each host-side adb operation.
  if [ "$VERIFY_DIRECT_GRANTS" = 1 ]; then
    write_settings_state="$(run_with_deadline 5 adb_exec -s "$TARGET" shell \
      appops get "$PKG" WRITE_SETTINGS 2>/dev/null || true)"
    a11y_state="$(run_with_deadline 5 adb_exec -s "$TARGET" shell \
      settings get secure enabled_accessibility_services 2>/dev/null || true)"
    a11y_enabled_state="$(run_with_deadline 5 adb_exec -s "$TARGET" shell \
      settings get secure accessibility_enabled 2>/dev/null || true)"
    a11y_state="${a11y_state//$'\r'/}"
    a11y_enabled_state="${a11y_enabled_state//$'\r'/}"
    if [ "$a11y_enabled_state" = 1 ]; then
      if a11y_service_enabled "$a11y_state" "$A11Y"; then a11y_granted=1; fi
    fi
    # The runtime permissions granted over adb above are read back from the package manager, not from
    # the app's own report: a grant that a vendor build silently refuses leaves the app running with a
    # capability quietly missing, and only Android knows which it kept.
    package_state="$(run_with_deadline 5 adb_exec -s "$TARGET" shell \
      dumpsys package "$PKG" 2>/dev/null || true)"
    # POST_NOTIFICATIONS is a runtime permission only from Android 13. Asking an older panel for it is
    # not a failure there, so the platform level decides whether that grant is checkable at all.
    sdk_level="$(run_with_deadline 5 adb_exec -s "$TARGET" shell \
      getprop ro.build.version.sdk 2>/dev/null || true)"
    sdk_level="${sdk_level//$'\r'/}"
    case "$sdk_level" in
      # An unreadable or nonsense platform level is not a reason to stop checking: fail closed and let
      # the check itself report what Android actually says.
      ''|*[!0-9]*) notifications_are_runtime=1 ;;
      *) if [ "$sdk_level" -ge 33 ]; then notifications_are_runtime=1; fi ;;
    esac
  fi
  cfg="$(curl -fsS --max-time 3 "$URL/api/v1/config" 2>/dev/null || true)"
  schema="$(read_config_schema)"
  schema_flat="$(printf '%s' "$schema" | tr -d '\r\n\t ')"
  if printf '%s' "$cfg" | grep -Eq '"ha_auth"[[:space:]]*:[[:space:]]*\{[^}]*"oauth"[[:space:]]*:[[:space:]]*true'; then
    HA_OAUTH_CONFIGURED=1
  else
    HA_OAUTH_CONFIGURED=0
  fi
  chk() { if printf '%s' "$2" | grep -q "$3"; then echo "   ${GRN}✓${X} $1"; else echo "   ${RED}✗ $1${X}"; rc=1; fi; }
  chk "HTTP server reachable"  "$health" "ha-paneld"
  if printf '%s' "$schema_flat" | grep -Eq '^\[\{.*\}\]$' &&
     printf '%s' "$schema_flat" | grep -Eq '"key":"[^"]+"' &&
     printf '%s' "$schema_flat" | grep -Eq '"label":"[^"]+"'; then
    echo "   ${GRN}✓${X} Configuration schema: ready"
  else
    echo "   ${RED}✗ Configuration schema: unavailable or malformed${X}"
    echo "     ${D}Open $URL/configure only after $URL/api/v1/config/schema returns a non-empty JSON settings array.${X}"
    rc=1
  fi
  case "$STORAGE_HEALTH_RESULT:$STORAGE_HEALTH_STATE" in
    valid:healthy)
      echo "   ${GRN}✓${X} storage health: healthy"
      ;;
    valid:unchecked)
      if [ "$VERIFY_DIRECT_GRANTS" = 1 ]; then
        echo "   ${YEL}⚠ storage health: still not checked after bounded post-install retries.${X}"
        echo "     ${D}The replacement completed. Wait for the scheduled check, then inspect $URL/api/v1/status.${X}"
      else
        echo "   ${YEL}ℹ${X} storage health: not checked yet ${D}(the scheduled check has not completed)${X}"
      fi
      ;;
    valid:warning)
      echo "   ${YEL}⚠ storage health: warning — storage or database-file pressure is elevated.${X}"
      echo "     ${D}Review panel free space and WAL/database growth, then check $URL again.${X}"
      ;;
    valid:critical)
      if [ "$VERIFY_DIRECT_GRANTS" = 1 ]; then
        echo "   ${YEL}⚠ storage health: critical — the in-place replacement completed, but storage or database-file pressure remains critical.${X}"
        echo "     ${D}Recover panel headroom or address WAL growth before writes fail. Details: $URL${X}"
      else
        echo "   ${RED}✗ storage health: critical — storage or database-file pressure is critical.${X}"
        echo "     ${D}Recover panel headroom or address WAL growth before writes fail, then re-run verification. Details: $URL${X}"
        rc=1
      fi
      ;;
    valid:database_failure)
      if [ "$VERIFY_DIRECT_GRANTS" = 1 ]; then
        echo "   ${YEL}⚠ storage health: database failure — the in-place recovery attempt completed, but SQLite writes or health checks are still failing.${X}"
        echo "     ${D}Preserve ha-paneld.db and inspect $URL/api/v1/diag. A missing or rejected pre-install database snapshot remains reported separately.${X}"
      else
        echo "   ${RED}✗ storage health: database failure — SQLite writes or health checks failed.${X}"
        echo "     ${D}Preserve ha-paneld.db, free panel storage if low, inspect $URL/api/v1/diag, then re-run verification.${X}"
        rc=1
      fi
      ;;
    transport:)
      if [ "$VERIFY_DIRECT_GRANTS" = 1 ]; then
        echo "   ${YEL}⚠ storage health: the status endpoint could not be reached after the replacement.${X}"
        echo "     ${D}Check $URL/api/v1/status once the app has settled; the package replacement itself is reported above.${X}"
      else
        echo "   ${RED}✗ storage health: the status endpoint could not be reached.${X}"
        echo "     ${D}Restore $URL/api/v1/status and re-run verification; transport failure is not a legacy result.${X}"
        rc=1
      fi
      ;;
    absent:)
      if [ "$VERIFY_DIRECT_GRANTS" = 1 ]; then
        echo "   ${YEL}⚠ storage health: the installed app did not return the status contract.${X}"
        echo "     ${D}The replacement completed. Inspect $URL/api/v1/status; a current build should report storage health.${X}"
      fi
      ;;
    malformed:)
      if [ "$VERIFY_DIRECT_GRANTS" = 1 ]; then
        echo "   ${YEL}⚠ storage health: malformed status response after the replacement.${X}"
        echo "     ${D}Inspect $URL/api/v1/status; the package replacement itself is reported above.${X}"
      else
        echo "   ${RED}✗ storage health: malformed status response.${X}"
        echo "     ${D}Inspect $URL/api/v1/status, then repair or update the app before relying on storage verification.${X}"
        rc=1
      fi
      ;;
    unknown:*)
      if [ "$VERIFY_DIRECT_GRANTS" = 1 ]; then
        echo "   ${YEL}⚠ storage health: unrecognised state '${STORAGE_HEALTH_STATE}' after the replacement.${X}"
        echo "     ${D}Update this provisioner or inspect $URL/api/v1/status; the package replacement itself is reported above.${X}"
      else
        echo "   ${RED}✗ storage health: unrecognised state '${STORAGE_HEALTH_STATE}'.${X}"
        echo "     ${D}Update this provisioner or inspect $URL/api/v1/status before relying on storage verification.${X}"
        rc=1
      fi
      ;;
  esac
  case "$POWER_SAFETY_RESULT:$POWER_SAFETY_STATE" in
    valid:safe)
      echo "   ${GRN}✓${X} panel power safety: safe"
      ;;
    valid:caution)
      echo "   ${YEL}⚠ panel power safety: caution — reachability depends on only one observed power guard.${X}"
      echo "     ${D}Review the exact observations at $URL/configure#cfg-keep_awake. Use Repair when offered; a healthy app-only caution can instead be explicitly hidden without changing this classification.${X}"
      ;;
    valid:at_risk)
      echo "   ${RED}✗ panel power safety: at risk — screen-off can leave this panel unreachable.${X}"
      echo "     ${D}Follow the warning at $URL/configure#cfg-keep_awake: use Repair when offered, otherwise inspect the manual guidance, then re-run verification.${X}"
      rc=1
      ;;
    valid:unknown)
      echo "   ${YEL}⚠ panel power safety: unknown — Android power probes did not establish an effective guard.${X}"
      echo "     ${D}Inspect $URL/api/v1/diag and the Configure warning; unknown is not treated as safe.${X}"
      ;;
    transport:)
      echo "   ${YEL}⚠ panel power safety: status unavailable.${X}"
      echo "     ${D}Restore $URL/api/v1/power-safety/state and re-run verification; no repair was attempted.${X}"
      ;;
    malformed:*|unknown:*)
      echo "   ${YEL}⚠ panel power safety: unrecognised status; not treating it as safe.${X}"
      echo "     ${D}Inspect $URL/api/v1/status and update the provisioner or app before relying on this result.${X}"
      ;;
  esac
  if [ "$VERIFY_DIRECT_GRANTS" = 1 ]; then
    chk "WRITE_SETTINGS granted" "$write_settings_state" 'WRITE_SETTINGS: allow'
    chk "accessibility enabled"  "$a11y_granted" '^1$'
    case "$write_settings_state" in
      *'WRITE_SETTINGS: allow'*) : ;;
      *) echo "     ${D}Enable Settings → Apps → ha-paneld → Modify system settings, then re-run this command.${X}" ;;
    esac
    if [ "$a11y_granted" != 1 ]; then
      echo "     ${D}Enable Settings → Accessibility → ha-paneld, then re-run this command.${X}"
    fi
    chk "microphone permission granted" "$package_state" 'android\.permission\.RECORD_AUDIO: granted=true'
    case "$package_state" in
      *'android.permission.RECORD_AUDIO: granted=true'*) : ;;
      *) echo "     ${D}Enable Settings → Apps → ha-paneld → Permissions → Microphone, then re-run this command.${X}" ;;
    esac
    if [ "$notifications_are_runtime" = 1 ]; then
      chk "notification permission granted" "$package_state" 'android\.permission\.POST_NOTIFICATIONS: granted=true'
      case "$package_state" in
        *'android.permission.POST_NOTIFICATIONS: granted=true'*) : ;;
        *) echo "     ${D}Enable Settings → Apps → ha-paneld → Notifications, then re-run this command.${X}" ;;
      esac
    else
      echo "   ${GRN}✓${X} notification permission: not a runtime permission before Android 13 ${D}(this panel reports API $sdk_level; notifications need no grant here)${X}"
    fi
  else
    chk "WRITE_SETTINGS granted" "$diag" "write_settings=true"
    chk "accessibility enabled"  "$diag" "a11y=true"
  fi
  # Root helper daemon — installed automatically on every rooted panel by current provisioners.
  if printf '%s' "$diag" | grep -q "daemon=true"; then
    echo "   ${GRN}✓${X} root helper daemon: running"
  elif [ -n "$diag" ]; then
    if [ "$HELPER_REQUIRED" = 1 ]; then
      echo "   ${RED}✗ root helper daemon: not detected after installation${X}"
      rc=1
    elif [ "$PROVISIONING_PLAN_AVAILABLE" != 1 ]; then
      echo "   ${YEL}ℹ${X} root helper daemon: ${YEL}not detected${X} ${D}(needed on sandbox-walled panels — helper/install-daemon.sh)${X}"
    fi
  fi
  # Root — the single biggest capability divider. Say it PLAINLY at install time so a no-root user
  # knows from the outset they're getting a subset (a panel-permissions shortfall, not ha-paneld bugs).
  if printf '%s' "$diag" | grep -q "su=true"; then
    echo "   ${GRN}✓${X} root (su): available — full feature set"
  elif printf '%s' "$diag" | grep -q "daemon=true"; then
    echo "   ${GRN}✓${X} root: via the helper daemon — full feature set"
  elif printf '%s' "$diag" | grep -q "shizuku=ready"; then
    echo "   ${GRN}✓${X} Shizuku enhanced access: ready — APK updates, screenshots/taps and display sizing enabled"
  elif [ -n "$diag" ]; then
    echo "   ${YEL}⚠ THIS PANEL HAS NO ROOT — ha-paneld runs with a REDUCED feature set.${X}"
    echo "     ${D}Working: HA sensors + MQTT, brightness, screen dim, audio/TTS, the dashboard renderers,${X}"
    echo "     ${D}the web UI and Back/Recents. Privileged hardware, system maintenance and private-app${X}"
    echo "     ${D}operations remain unavailable.${X}"
  fi
  # panel_id + MQTT (informational — install-only is valid). grep/cut so no python (Git Bash-friendly).
  local broker pid
  broker="$(printf '%s' "$cfg" | grep -o '"mqtt_broker":"[^"]*"' | head -1 | cut -d'"' -f4 || true)"
  pid="$(printf '%s' "$cfg" | grep -o '"panel_id":"[^"]*"' | head -1 | cut -d'"' -f4 || true)"
  broker="$(printf '%s' "$broker" | sanitize_terminal)"
  pid="$(printf '%s' "$pid" | sanitize_terminal)"
  if [ -n "$broker" ]; then echo "   ${GRN}✓${X} MQTT broker: ${B}$broker${X}"
  else echo "   ${YEL}ℹ${X} MQTT broker: ${YEL}not explicitly set${X} ${D}(LAN auto-discovery will be attempted; it can also be set in Configure)${X}"; fi
  echo "   ${YEL}ℹ${X} panel_id: ${B}${pid:-?}${X}"
  return $rc
}

# `adb connect` exits 0 even when it FAILS ("cannot connect"), and a connected device can still sit
# "unauthorized" (RSA dialog waiting on the panel screen) or "offline" (stale adbd session). Verify the
# state explicitly so the failure surfaces HERE with recovery steps, not as an obscure error (or a
# hang) at the first real adb command.
adb_preflight_raw() {
  local state="" i=0
  adb_exec connect "$TARGET" >/dev/null 2>&1 || true
  while [ "$i" -lt 12 ]; do
    i=$((i + 1))
    state="$(adb_exec devices 2>/dev/null | awk -v t="$TARGET" '$1==t {print $2}')"
    if [ "$state" = "device" ]; then printf 'device\n'; return 0; fi
    # Stale session ("offline"): reset it once, then keep polling.
    if [ "$state" = "offline" ] && [ "$i" = 4 ]; then
      adb_exec disconnect "$TARGET" >/dev/null 2>&1 || true
      adb_exec connect "$TARGET" >/dev/null 2>&1 || true
    fi
    sleep 1
  done
  printf '%s\n' "${state:-unreachable}"
  return 1
}

adb_preflight() {
  local state="" status timeout="${ADB_PREFLIGHT_TIMEOUT_SECONDS:-30}"
  case "$timeout" in ''|*[!0-9]*|0) timeout=30 ;; esac
  step "🔌 connecting" "$TARGET"
  if state="$(run_with_deadline "$timeout" adb_preflight_raw)"; then
    return 0
  else
    status=$?
  fi
  if [ "$status" -eq 124 ] || [ "$status" -eq 137 ]; then state=unreachable; fi
  case "$state" in
    unauthorized) fail "panel refused adb: unauthorized" \
      "Accept the ADB authorization dialog shown ON THE PANEL'S SCREEN (tick 'always allow'), then re-run." \
      "No dialog visible? Toggle 'ADB debugging' off/on in the panel's Developer options and re-run." ;;
    offline) fail "panel is stuck 'offline' on adb" \
      "Toggle 'ADB debugging' off/on in the panel's Developer options (or power-cycle the panel if you are next to it), then re-run." \
      "A session held by another machine can also cause this — run 'adb disconnect' there first." ;;
    *) fail "cannot reach $TARGET over adb" \
      "Check: the IP is right, network ADB is enabled (Developer options → 'ADB debugging' / 'Network ADB'), the port ($TARGET), and that this machine is on the same network/VLAN as the panel." \
      "Some panels only expose adb on USB until 'adb tcpip 5555' is run once — see https://panel-assistant.io/go/docs?page=provisioning ('Bootstrapping adb')." ;;
  esac
}

# Run one host command behind a hard deadline. Bash job control gives the command its own local process
# group so the supervisor can terminate its direct worker and non-detached local descendants, then
# reap the worker before returning. Keeping the supervisor in the provisioner's outer process group
# also makes fleet INT/TERM compositional: the fleet wrapper signals the supervisor, which owns and
# reaps this nested group. This bounds the host adb invocation; it does not claim to kill a device-side
# process after the adb transport is gone. Status 124 is reserved for the deadline so callers can
# distinguish it from a normal command failure.
run_with_deadline() {
  local seconds="$1" command_pid status deadline
  shift

  (
    command_pid=""
    terminate_deadline_command_group() {
      local signal_status="$1" kill_deadline
      trap - INT TERM
      if [ -n "$command_pid" ]; then
        kill -TERM -- "-$command_pid" 2>/dev/null || true
        kill_deadline=$((SECONDS + 2))
        while kill -0 -- "-$command_pid" 2>/dev/null && [ "$SECONDS" -lt "$kill_deadline" ]; do
          sleep 0.1
        done
        kill -KILL -- "-$command_pid" 2>/dev/null || true
        wait "$command_pid" 2>/dev/null || true
        command_pid=""
      fi
      return "$signal_status"
    }
    handle_deadline_signal() {
      local signal_status="$1"
      terminate_deadline_command_group "$signal_status" || true
      exit "$signal_status"
    }
    trap 'handle_deadline_signal 130' INT
    trap 'handle_deadline_signal 143' TERM
    set -m
    "$@" &
    command_pid=$!
    # The asynchronous command keeps the process group assigned above after monitor mode is disabled;
    # disabling it immediately prevents Bash job-completion notices from contaminating captured output.
    set +m
    deadline=$((SECONDS + seconds))
    while kill -0 -- "-$command_pid" 2>/dev/null && [ "$SECONDS" -lt "$deadline" ]; do
      sleep 0.1
    done
    if kill -0 -- "-$command_pid" 2>/dev/null; then
      terminate_deadline_command_group 124 || true
      return 124
    fi
    wait "$command_pid"
    status=$?
    command_pid=""
    return "$status"
  )
}

# Resolve the executable before defining the wrapper so every ordinary adb operation shares the same
# bounded host-command policy. Operations with stronger transaction-specific semantics (notably APK
# install) invoke the absolute executable beneath their own deadline owner instead of nesting owners.
ADB_COMMAND="$(command -v adb 2>/dev/null || true)"
[ -n "$ADB_COMMAND" ] || fail "adb (Android Platform Tools) was not found" \
  "Install adb, then re-run the identical command; no panel changes were made."
ADB_COMMAND_TIMEOUT_SECONDS="${ADB_COMMAND_TIMEOUT_SECONDS:-120}"
case "$ADB_COMMAND_TIMEOUT_SECONDS" in ''|*[!0-9]*|0)
  fail "ADB_COMMAND_TIMEOUT_SECONDS must be a positive whole number of seconds" ;;
esac

# Git for Windows runs this script on an MSYS runtime that rewrites any argument beginning with a
# forward slash into a Windows path before a native program such as adb.exe is exec'd. That silently
# turned `adb push <local> /data/local/tmp/hapaneld-helper` into a push to
# `D:/Program Files/Git/data/local/tmp/hapaneld-helper`, so the helper never reached the panel and
# provisioning failed with no indication of why (#24). The runtime honours MSYS2_ARG_CONV_EXCL, a
# semicolon-separated list of argument prefixes it must hand over untouched, so every Android
# filesystem root this script can name in an adb argument is listed here.
#
# Deliberately NOT `MSYS2_ARG_CONV_EXCL=*` and NOT `MSYS_NO_PATHCONV=1`: those switch conversion off
# for the whole command line, and this script also hands adb genuine HOST paths — the push sources,
# the pull destinations and the APK. Under Git Bash a `mktemp` file is `/tmp/...` and MUST still be
# translated to `D:/Program Files/Git/tmp/...` before adb.exe can open it, so a blanket exclusion
# would trade one broken direction for the other. Panel serials (`host:5555`), URLs, embedded spaces,
# quoting and credential arguments carry no leading slash and are unaffected either way.
#
# The variable means nothing outside an MSYS/Cygwin runtime, so it is set unconditionally rather than
# behind a `uname` branch that no Linux or macOS run could exercise. Keep this list aligned with the
# identical one in helper/install-daemon.sh.
ADB_MSYS_ARG_CONV_EXCL='/acct;/apex;/cache;/config;/data;/dev;/mnt;/odm;/oem;/proc;/product;/sbin;/sdcard;/storage;/sys;/system;/vendor'

# Every adb execution in this script crosses this one boundary so a new call site cannot quietly
# reintroduce the rewrite; scripts/tests/provision_test.sh enforces that statically.
adb_exec() {
  MSYS2_ARG_CONV_EXCL="$ADB_MSYS_ARG_CONV_EXCL" "$ADB_COMMAND" "$@"
}

adb() {
  run_with_deadline "$ADB_COMMAND_TIMEOUT_SECONDS" adb_exec "$@"
}

# Root-path probe — vendor root varies TWICE over: the prefix (`su 0`, `su root`, `su -c`) AND the
# dialect. Join-style su (SuperSU/toolbox) re-joins argv and runs it through its own `sh -c`, so a
# command must be passed as ONE quoted word (`su 0 "cmd a b"`) — adding `sh -c` double-wraps and
# silently STRIPS the quoting ("getprop x" becomes a bare getprop). Execvp-style su (AOSP) execs argv
# directly, so a command string DOES need the `sh -c` wrapper. `"id; id"` only succeeds through a
# shell, so probing with it identifies the wrapping that preserves a multi-word command. Userdebug
# panels can also have a root adbd with NO su at all — probed first. Probe once, cache the winner.
# A su that prompts on-screen (Magisk) can take ~10s to auto-deny a form — the probe tolerates that.
SU_FORM=""
SU_PROBE_TIMED_OUT=0
probe_su_uncached() {
  local u key pre
  u="$(adb_exec -s "$TARGET" shell id 2>/dev/null | tr -d '\r')" || u=""
  case "$u" in uid=0*) printf 'shell\n'; return 0 ;; esac
  for key in su0 suroot; do
    case "$key" in su0) pre="su 0" ;; suroot) pre="su root" ;; esac
    u="$(adb_exec -s "$TARGET" shell "$pre \"id; id\"" 2>/dev/null | tr -d '\r')" || u=""
    case "$u" in *uid=0*) printf '%sjoin\n' "$key"; return 0 ;; esac
    u="$(adb_exec -s "$TARGET" shell "$pre sh -c \"id; id\"" 2>/dev/null | tr -d '\r')" || u=""
    case "$u" in *uid=0*) printf '%sshc\n' "$key"; return 0 ;; esac
  done
  u="$(adb_exec -s "$TARGET" shell "su -c \"id; id\"" 2>/dev/null | tr -d '\r')" || u=""
  case "$u" in *uid=0*) printf 'suc\n'; return 0 ;; esac
  printf 'none\n'
  return 1
}

probe_su() {
  # The cached status is returned explicitly because of bash's trap rule for `return`: "If return is
  # executed by a trap handler, the last command used to determine the status is the last command
  # executed before the trap handler was invoked" — and that applies equally to a bare `return` in a
  # function the handler calls. So the old cached branch reported the run's pending (failing) exit
  # status instead of the test above it, silently no-opping every root command a failing run's exit
  # handler issued. The provisioner suite pins both directions of this semantics executably. Without
  # this fix the exit-path staging reclamation cannot work at all.
  local cached
  if [ -n "$SU_FORM" ]; then
    [ "$SU_FORM" != none ]
    cached=$?
    return "$cached"
  fi
  local result="" status timeout="${PRIVILEGE_INSPECTION_TIMEOUT_SECONDS:-45}"
  case "$timeout" in ''|*[!0-9]*|0) timeout=45 ;; esac
  PRIVILEGE_INSPECTION_TIMEOUT_SECONDS="$timeout"
  SU_PROBE_TIMED_OUT=0
  if result="$(run_with_deadline "$timeout" probe_su_uncached)"; then
    SU_FORM="$result"
    return 0
  else
    status=$?
  fi
  if [ "$status" -eq 124 ] || [ "$status" -eq 137 ]; then
    SU_PROBE_TIMED_OUT=1
    SU_FORM=""
  else
    SU_FORM="${result:-none}"
  fi
  return 1
}

# Quote one command string for the device shell's outer double quotes. Join-style su needs the whole
# command as one argv word, but the unprivileged device shell must not expand its dollars, backticks,
# substitutions, or embedded quotes before su receives it.
quote_root_command() {
  local command="$1"
  command="${command//\\/\\\\}"
  command="${command//\"/\\\"}"
  command="${command//\$/\\\$}"
  command="${command//\`/\\\`}"
  printf '%s\n' "$command"
}

# Run one command string as root via the probed form.
run_root() {
  local command="$1" quoted
  probe_su || return 1
  quoted="$(quote_root_command "$command")"
  case "$SU_FORM" in
    shell)      adb -s "$TARGET" shell "$command" ;;
    su0join)    adb -s "$TARGET" shell "su 0 \"$quoted\"" ;;
    su0shc)     adb -s "$TARGET" shell "su 0 sh -c \"$quoted\"" ;;
    surootjoin) adb -s "$TARGET" shell "su root \"$quoted\"" ;;
    surootshc)  adb -s "$TARGET" shell "su root sh -c \"$quoted\"" ;;
    suc)        adb -s "$TARGET" shell "su -c \"$quoted\"" ;;
    # A form this dispatch does not know is a failure, never a silent success with empty output —
    # callers treat run_root's exit status as "the panel was asked".
    *)          return 1 ;;
  # An adbd without shell_v2 runs every reply through a PTY, so each line comes back with a trailing
  # CR. This function's callers compare exact tokens — INSTALL_OK, TRANSACTION_READY, the classifier
  # payloads — and a stray CR makes every one of those comparisons fail on a panel that answered
  # correctly. helper/install-daemon.sh already normalises here and says to keep the two aligned; the
  # user-facing script was the one left out.
  esac | tr -d '\r'
}

# Tuya panels (TPA10) ship a closed vendor stack (launcher, system UI, Tuya IoT, hardware, diagnostics)
# that does nothing for an HA panel and uses CPU/RAM. Offer to disable it — but ONLY once the panel can
# run without it. Reversible: re-enable any package with `adb shell pm enable <pkg>`.
# The Tuya/TPA10 vendor stack this script disables. It is a SUBSET of the packages the panel's own device
# profile names for this hardware (the profile also covers com.smartos.xinch.smarthome, which the app tames
# and this script does not) — `scripts/tests/provision_test.sh` pins that containment, because the profile is
# what authorises the panel to adopt and re-enable a package it holds no ownership record for.
VENDOR_STRIP_PACKAGES="com.smartos.xinch.launcher com.smartos.xinch.systemui com.smartos.xinch.smartiot
com.smartos.xinch.hardware com.smartos.xinch.monitor com.smartos.xinch.setting
com.smartos.xinch.communicate com.tuya.devicetest"

# Tell the panel that WE disabled this package, so it can hand it back later. Without this the record lives
# nowhere: `pm disable-user` over adb leaves no trace on the panel, so removing ha-paneld would strand the
# device on Android's FallbackHome placeholder with no way for an ordinary user to recover it.
#
# Best-effort by design. An older app has no such route, and a panel that cannot be told is still a panel
# that was successfully minimised — so a failure warns, points at the manual undo, and never fails the run.
record_vendor_tame() {
  local pkg="$1" code
  code="$(curl -s -o /dev/null -w '%{http_code}' --connect-timeout "$PANEL_POST_CONNECT_TIMEOUT_SECONDS" \
    --max-time "$PANEL_POST_TIMEOUT_SECONDS" -X POST \
    --data-urlencode "pkg=$pkg" "$URL/api/v1/tame/record" 2>/dev/null || true)"
  case "$code" in
    200) return 0 ;;
    404|308) warn "this ha-paneld is too old to record $pkg; re-enable it by hand with: adb -s $TARGET shell pm enable $pkg" ;;
    *) warn "could not record $pkg on the panel (HTTP ${code:-none}); re-enable it by hand with: adb -s $TARGET shell pm enable $pkg" ;;
  esac
  return 0
}

# Wait for the panel agent to answer, for the hand-back and uninstall paths only.
#
# These run before the install flow, so the ordinary post-install health poll has not happened yet and
# AGENT_HEALTHY is still 0. The gate matters as much here as anywhere: handing the home screen back is a
# mutating call, and removing the app on the strength of a request that never landed is exactly how a panel
# ends up with no launcher.
wait_for_handback_health() {
  local deadline=$((SECONDS + APP_HEALTH_TIMEOUT_SECONDS))
  step "🔎 checking" "${D}waiting for the panel agent on $URL${X}"
  while [ "$SECONDS" -lt "$deadline" ]; do
    if curl -fsS --connect-timeout 2 --max-time 5 "$URL/health" >/dev/null 2>&1; then
      AGENT_HEALTHY=1
      return 0
    fi
    sleep 1
  done
  fail "the panel agent never answered on $URL" \
    "Nothing was changed: the panel still has whatever home screen it had, and ha-paneld is still installed." \
    "No packages were re-enabled and nothing was removed." \
    "Open $URL in a browser to check the panel is up, then re-run the same command."
}

# Hand the panel its home screen back: re-enable exactly the vendor packages ha-paneld disabled, stop being
# the preferred Home app, and confirm by readback that a real launcher took the role.
#
# The confirmation is the whole point. Uninstalling ha-paneld while it still holds HOME leaves the panel on
# `com.android.settings/.FallbackHome` — "Android is starting…" forever — which reads as a brick, and Android
# Settings will not uninstall the preferred Home app in the first place. So an unconfirmed hand-back REFUSES
# the uninstall rather than proceeding and recreating exactly the failure this exists to prevent.
hand_back_home() {
  local response http_status resp
  # The health gate is wait_for_handback_health, called by the dispatch before this runs. It cannot be
  # require_healthy_agent: these paths run before the install flow, and that helper is defined further down
  # the script than they are, so calling it here is a "command not found" at the worst possible moment.
  step "🏠 handing back" "${D}re-enabling the vendor apps ha-paneld disabled${X}"
  response="$(curl -s --connect-timeout "$PANEL_POST_CONNECT_TIMEOUT_SECONDS" \
    --max-time "$PANEL_POST_TIMEOUT_SECONDS" -X POST -w '\n%{http_code}' \
    "$URL/api/v1/hand-back-home" 2>&1 || true)"
  http_status="${response##*$'\n'}"
  resp="$(printf '%s' "${response%$'\n'*}" | sanitize_terminal)"
  case "$http_status" in
    200) ;;
    202)
      if printf '%s' "$resp" | approval_required_response; then
        fail "the panel needs this approved on its screen" \
          "Hardened security is on, so handing the home screen back has to be approved physically on the panel." \
          "Nothing was changed and ha-paneld is still installed." \
          "Approve the request on the panel, then re-run the identical command within ten minutes."
      fi
      fail "the panel did not complete handing the home screen back" "$resp" \
        "Nothing was removed." "Re-run the same command once the panel reports it is healthy."
      ;;
    409)
      # The panel ran the request and refused it. Each code is a different thing for the user to fix, and
      # the one that matters most is "there is no other launcher" — removing ha-paneld there is exactly
      # what would strand the panel.
      case "$resp" in
        *no-replacement-home*)
          fail "the panel could not give its home screen to another launcher" \
            "There is no other home-screen app installed, so ha-paneld is the only thing this panel can show." \
            "Nothing was removed: ha-paneld is still this panel's Home app, so the panel still works." \
            "Install a launcher, or re-enable the vendor one, and run this again before removing ha-paneld."
          ;;
        *ownership-unreadable*)
          fail "the panel could not read its own record of what it switched off" \
            "$resp" \
            "Nothing was removed and nothing was re-enabled." \
            "Restart the panel and run this again; if it persists, re-enable the vendor apps by hand with: adb -s $TARGET shell pm enable <pkg>"
          ;;
        *)
          fail "the panel refused to hand its home screen back" "$resp" \
            "Nothing was removed and the panel is unchanged." \
            "Check $URL in a browser, then re-run the same command."
          ;;
      esac
      ;;
    404|308)
      fail "this ha-paneld is too old to hand the home screen back" \
        "The panel is running a build without the hand-back route, so it cannot re-enable the vendor apps it disabled." \
        "Nothing was removed and the panel is unchanged." \
        "Upgrade the panel first, or re-enable the vendor launcher by hand with: adb -s $TARGET shell pm enable <pkg>"
      ;;
    *)
      fail "handing the home screen back failed (HTTP ${http_status:-none})" "$resp" \
        "Nothing was removed and the panel is unchanged." \
        "Check $URL in a browser, then re-run the same command."
      ;;
  esac
  case "$resp" in
    *'"home_handed_to"'*) ;;
    *)
      fail "the panel did not confirm another launcher took the home screen" "$resp" \
        "Nothing was removed: ha-paneld is still this panel's Home app, so the panel still has a working screen." \
        "Install a launcher (or re-enable the vendor one) and re-run, rather than removing ha-paneld now."
      ;;
  esac
  echo "   ${GRN}✓${X} the panel has its home screen back"
  case "$resp" in
    *'"outstanding":[]'*) ;;
    *) warn "some vendor apps are still disabled; running this again will retry them" ;;
  esac
}

# Remove ha-paneld, handing the home screen back first so the panel is never left without one.
#
# Removes EVERY installed identity, and does not assume which one is there. `$PKG` is the successor id;
# every panel in the field today carries only `$LEGACY_PKG`, and during the migration a panel can carry
# both. Uninstalling `$PKG` unconditionally would therefore remove nothing on an ordinary panel while
# reporting a retry that could never work -- after hand back had already surrendered HOME and cleared the
# desired tame state. "Uninstall ha-paneld" means no identity of it is left behind.
uninstall_ha_paneld() {
  local installed=() pkg removed=0
  for pkg in "$PKG" "$LEGACY_PKG"; do
    [ -n "$pkg" ] || continue
    case " ${installed[*]} " in *" $pkg "*) continue ;; esac
    adb -s "$TARGET" shell pm path "$pkg" >/dev/null 2>&1 && installed+=("$pkg")
  done

  if [ "${#installed[@]}" = 0 ]; then
    fail "ha-paneld is not installed on this panel" \
      "Neither $PKG nor $LEGACY_PKG is present, so there is nothing to remove." \
      "Nothing was changed." \
      "If the panel still shows ha-paneld, check you are pointed at the right panel: $TARGET"
  fi

  # Only after we know there is something to remove, so a panel with nothing installed is never made to
  # surrender its HOME role for no reason.
  hand_back_home

  for pkg in "${installed[@]}"; do
    step "🗑  removing" "${D}uninstalling $pkg${X}"
    if adb -s "$TARGET" uninstall "$pkg" >/dev/null 2>&1; then
      echo "   ${GRN}✓${X} removed $pkg"
      removed=$((removed + 1))
    else
      fail "could not remove $pkg" \
        "The panel already has its home screen back, so it is usable either way." \
        "$removed of ${#installed[@]} identities were removed; $pkg is still installed." \
        "Retry with: adb -s $TARGET uninstall $pkg"
    fi
  done
  echo "   ${D}the panel keeps its own home screen; its ha-paneld configuration is gone${X}"
}

offer_strip_vendor() {
  local brand model
  # This is an optional post-verification offer. A panel can disappear after a fully successful install
  # (or adb can transiently return 255), so every advisory probe must degrade to "not detected" rather
  # than trip the global ERR trap and turn a verified run into a false failure.
  brand="$(adb -s "$TARGET" shell getprop ro.product.brand 2>/dev/null | tr -d '\r' || true)"
  model="$(adb -s "$TARGET" shell getprop ro.product.model 2>/dev/null | tr -d '\r' || true)"
  case "$brand $model" in *[Tt]uya*|*TPA10*) ;; *) return 0 ;; esac   # Tuya/TPA10 only

  # Guard 1 — root (vendor apps are system apps; pm disable-user needs root on the userdebug build).
  if ! probe_su; then
    if [ "$STRIP_VENDOR" = 1 ]; then echo "   ${YEL}⚠ --strip-vendor ignored: no root path found (su or adbd-root).${X}"; fi
    return 0
  fi
  # Guard 2 — persistent adb, else disabling the diagnostics-app adb backdoor can lock you out.
  local adben tcp
  adben="$(adb -s "$TARGET" shell settings get global adb_enabled 2>/dev/null | tr -d '\r' || true)"
  tcp="$(adb -s "$TARGET" shell getprop persist.adb.tcp.port 2>/dev/null | tr -d '\r' || true)"
  if [ "$adben" != "1" ] || [ -z "$tcp" ]; then
    echo "   ${YEL}⚠ vendor-strip skipped: re-run with --persist-adb first (persistent adb is required so disabling the Tuya diagnostics backdoor can't lock you out).${X}"
    return 0
  fi
  # Guard 3 — a non-vendor home launcher must exist before we disable the vendor launcher.
  # ha-paneld itself ships an admin launcher (AdminLauncherActivity registers HOME), so it counts.
  local home=""
  for L in io.homeassistant.companion.android.minimal io.homeassistant.companion.android "$PKG" "$LEGACY_PKG"; do
    if adb -s "$TARGET" shell pm path "$L" >/dev/null 2>&1; then home="$L"; break; fi
  done
  if [ -z "$home" ]; then
    echo "   ${YEL}⚠ vendor-strip skipped: install a replacement launcher first (HA Companion, or ha-paneld's admin launcher).${X}"
    return 0
  fi

  # --strip-vendor forces it; otherwise prompt on a TTY (default No).
  if [ "$STRIP_VENDOR" != 1 ]; then
    [ -t 0 ] || return 0
    echo "${MAG}${B}Tuya panel detected.${X} ${D}Its vendor apps (launcher, system UI, Tuya IoT, hardware, diagnostics) are a closed stack with nothing for HA and use CPU/RAM.${X}"
    printf "   Disable them for a faster, minimal panel? Reversible with ${B}pm enable${X}. [y/N] "
    read -r a; case "$a" in [yY]*) ;; *) echo "   keeping the vendor apps."; return 0 ;; esac
  fi

  step "🧹 minimising" "${D}home → $home; disabling Tuya vendor apps${X}"
  adb -s "$TARGET" shell cmd package set-home-activity "$home" >/dev/null 2>&1 || true
  # Read the disabled set ONCE before touching anything. `pm disable-user` succeeds just as happily on a
  # package the firmware already shipped disabled, so its exit status cannot tell us whether WE disabled
  # something. Only a package observed enabled here and disabled afterwards is ours to hand back later.
  local already_disabled
  if ! already_disabled="$(adb -s "$TARGET" shell pm list packages -d 2>/dev/null | tr -d '\r')"; then
    # Fail closed. This snapshot is the only thing that distinguishes a package WE disabled from one the
    # firmware shipped disabled: `pm disable-user` moves a firmware-disabled package from state 2 to
    # state 3, so afterwards the panel cannot tell them apart either. Without the snapshot we cannot
    # record ownership honestly, so we record nothing and say so.
    warn "could not read which packages were already disabled, so nothing will be recorded as ours to hand back"
    already_disabled="__snapshot_unavailable__"
  fi
  for P in $VENDOR_STRIP_PACKAGES; do
    adb -s "$TARGET" shell pm path "$P" >/dev/null 2>&1 || continue
    if adb -s "$TARGET" shell pm disable-user --user 0 "$P" >/dev/null 2>&1; then
      echo "   ${GRN}✓${X} disabled $P"
      case "$already_disabled" in
        __snapshot_unavailable__) ;;             # cannot prove we disabled it, so never claim it
        *"package:$P"*) ;;                       # already off before we arrived — not ours, never record it
        *) record_vendor_tame "$P" ;;
      esac
    else
      echo "   ${YEL}–${X} could not disable $P"
    fi
  done
  echo "   ${D}undo any with: adb -s $TARGET shell pm enable <pkg>${X}"
  echo "   ${D}or hand the panel its home screen back with: $0 $TARGET --hand-back-home${X}"
}

# Warn when the system WebView is too old for a current HA frontend (the dashboard renders blank/broken).
# Informational only — points at the update instructions; does not change anything.
WEBVIEW_DOC="https://panel-assistant.io/go/docs?page=hardware/tpa10"
check_webview() {
  local wv major
  wv="$(adb -s "$TARGET" shell dumpsys webviewupdate 2>/dev/null | grep -m1 'Current WebView package' | grep -oE '[0-9]+\.[0-9]+\.[0-9]+\.[0-9]+' | head -1 || true)"
  [ -n "$wv" ] || return 0
  major="${wv%%.*}"
  case "$major" in ''|*[!0-9]*) return 0 ;; esac
  if [ "$major" -lt 110 ]; then
    echo "${YEL}${B}⚠ system WebView is very old ($wv)${X}${YEL} — too old for a current Home Assistant frontend; the dashboard may render blank or broken.${X}"
    echo "   ${CYN}update it: $WEBVIEW_DOC${X}"
    echo "   ${D}(Already swapped in Cromite SystemWebView? It stamps the OEM version, so it still reports $wv here — ignore this.)${X}"
  fi
}

# Report the panel's time zone against this computer's before anything is installed. A panel left on
# its factory zone timestamps its logs and runs its schedules in that zone, which nothing on screen
# ever states, so the discrepancy usually surfaces much later as something happening at the wrong
# hour. Purely diagnostic: no clock is read for correctness, neither zone is changed, and a mismatch
# never refuses an installation — the panel's zone is the operator's setting to make, not this
# script's, and a deliberate difference is legitimate.
#
# Zones are compared by NAME, never by the offset they happen to produce right now. Two zones can
# agree on their offset for months and part the moment one of them changes for daylight saving, so a
# matching offset is not evidence of a matching zone.
#
# Different names can still be one zone: the time-zone database carries "backward" links, so a panel
# may report Asia/Calcutta where this computer reports Asia/Kolkata. Such a pair is confirmed against
# the host database, where linked names resolve to byte-identical files. Debian and Ubuntu have moved
# those links into a separate tzdata-legacy package, so a host that cannot resolve one of the names is
# ordinary rather than broken; that pair is reported as a difference this computer could not confirm,
# because treating an unresolvable name as equal would be the offset mistake wearing another hat.
#
# TZDIR is libc's own override for the database location, not a hook added for tests.
TIMEZONE_DIR="${TZDIR:-/usr/share/zoneinfo}"
# The two host-side records live under one directory so a caller can point the whole lookup elsewhere.
TIMEZONE_HOST_ETC="${HAPANELD_HOST_TIMEZONE_ETC:-/etc}"
TIMEZONE_PROBE_SECONDS="${HAPANELD_TIMEZONE_PROBE_SECONDS:-10}"
# A bad override falls back rather than refusing. Every other bounded operation in this script gates
# a real outcome and is right to stop; this one gates a remark, so it must not be able to end a run.
case "$TIMEZONE_PROBE_SECONDS" in ''|*[!0-9]*|0) TIMEZONE_PROBE_SECONDS=10 ;; esac

# A zone name is Area/Location, sometimes Area/Region/Location. It arrives from the panel and is used
# to build a path under the time-zone database, so it is validated as untrusted input BEFORE any file
# is touched: no absolute path, no traversal, no separator or metacharacter that a name never carries.
# Names hold no dot, which is what makes the traversal check exhaustive rather than a blocklist.
valid_timezone_name() {
  printf '%s\n' "${1-}" | grep -Eq '^[A-Za-z][A-Za-z0-9_+-]*(/[A-Za-z0-9_+-]+){0,2}$'
}

# This computer's zone, in the order the rest of this computer would resolve it. TZ first because
# every other command here obeys it; a TZ naming a POSIX rule ("GMT0BST,M3.5.0/1") rather than a zone
# is not a name that can be compared, so it falls through rather than being reported as one.
host_timezone() {
  local candidate="${TZ:-}"
  candidate="${candidate#:}"
  if valid_timezone_name "$candidate"; then printf '%s\n' "$candidate"; return 0; fi
  # A link into the database on Linux and macOS alike. Absent, unreadable or a plain copy are all
  # ordinary shapes for a host to have rather than errors, so each falls through to the next source
  # and finally to "this computer did not say" — which is why these two reads are deliberately fail
  # open. They read nothing on the panel and decide nothing about it.
  candidate="$(readlink "$TIMEZONE_HOST_ETC/localtime" 2>/dev/null || true)"
  case "$candidate" in
    */zoneinfo/*) candidate="${candidate#*/zoneinfo/}" ;;
    *) candidate="" ;;
  esac
  if valid_timezone_name "$candidate"; then printf '%s\n' "$candidate"; return 0; fi
  # Debian records the name itself, which is the only source left when localtime is a plain copy.
  candidate="$(head -n 1 "$TIMEZONE_HOST_ETC/timezone" 2>/dev/null | tr -d '\r' || true)"
  if valid_timezone_name "$candidate"; then printf '%s\n' "$candidate"; return 0; fi
  return 1
}

# The panel's zone. Android resolves its default from this property, and it answers on a panel with
# nothing installed yet, which is what a first installation needs. Bounded on its own short deadline
# rather than the general adb one: this runs in the mutation path, and an advisory that can stall an
# installation for two minutes has cost more than it explains. Every failure reads as "not reported".
panel_timezone() {
  local value
  value="$(run_with_deadline "$TIMEZONE_PROBE_SECONDS" \
    adb_exec -s "$TARGET" shell getprop persist.sys.timezone 2>/dev/null || true)"
  # A panel whose adbd has no shell protocol v2 returns every reply through a PTY, so strip the CR.
  value="$(printf '%s' "$value" | tr -d '\r' | head -n 1)"
  valid_timezone_name "$value" || return 1
  printf '%s\n' "$value"
}

# match | alias | differ | silent. Never fails, and speaks only when it can establish the answer.
# Anything it cannot read, parse or resolve becomes `silent` and produces no output at all — not even
# a note about its own difficulty. This is an advisory with no bearing on whether the panel works, so
# a remark the operator has to read and then dismiss costs more than the remark is worth.
classify_timezone_pair() {
  local host="${1-}" panel="${2-}"
  if [ -z "$host" ] || [ -z "$panel" ]; then printf 'silent\n'; return 0; fi
  if [ "$host" = "$panel" ]; then printf 'match\n'; return 0; fi
  # Different names can still be one zone: the database records an older name as a link, so linked
  # names resolve to byte-identical files. A name this database does not carry settles nothing either
  # way, and Debian and Ubuntu ship those older names separately, so such a pair is passed over in
  # silence rather than reported as a difference that has not actually been established.
  if command -v cmp >/dev/null 2>&1 &&
     [ -f "$TIMEZONE_DIR/$host" ] && [ -f "$TIMEZONE_DIR/$panel" ]; then
    if cmp -s "$TIMEZONE_DIR/$host" "$TIMEZONE_DIR/$panel"; then printf 'alias\n'; else printf 'differ\n'; fi
    return 0
  fi
  printf 'silent\n'
}

# Fail-open AND fail-quiet, deliberately. This sits in the pre-mutation path of an installer and has
# no bearing on whether the panel works, so every read below is guarded and every unresolved outcome
# prints nothing at all. It cannot end a run, cannot delay one beyond its own short probe, and cannot
# put a question on screen that the operator has to work out how to dismiss. It starts nothing,
# changes nothing, and no later step consults its result. `silent` is the default, not the exception:
# the case below has no fallback arm, so any state that is not a settled answer produces no output.
report_timezone_alignment() {
  local host panel state
  host="$(host_timezone || true)"
  panel="$(panel_timezone || true)"
  state="$(classify_timezone_pair "$host" "$panel")"
  case "$state" in
    match)
      echo "   ${D}time zone: this computer and the panel are both $host${X}" ;;
    alias)
      echo "   ${D}time zone: this computer is $host and the panel is $panel — two names for the same zone${X}" ;;
    differ)
      warn "time zone: this computer is $host but the panel is $panel"
      # One sentence per line. A sentence split across two echoes reads the same on a terminal and
      # is invisible to anything line-based that reads this output, including this script's own tests.
      echo "     ${D}Nothing here changes either clock, and the installation continues.${X}"
      echo "     ${D}The panel's own timestamps and schedules follow $panel.${X}"
      echo "     ${D}Change it in the panel's Android date and time settings if that is not what you want.${X}" ;;
  esac
  return 0
}

# Fetch the newest signed release APK from GitHub's bounded HTTPS API. Sets APK + TOINSTALL_VER.
download_latest() {
  local dir tag="" urls json api record asset expected_url
  dir="$(mktemp -d)"
  # PRERELEASE=1 → newest published release of ANY kind (incl. rc); else the newest STABLE.
  # GitHub's /releases/latest excludes prereleases, so the inclusive path lists all releases and
  # takes the first non-draft record. A newer stable therefore wins, including after RC deletion.
  if [ "$PRERELEASE" = 1 ]; then
    api="https://api.github.com/repos/$REPO/releases?per_page=100"
  else
    api="https://api.github.com/repos/$REPO/releases/latest"
  fi
  json="$(curl -fsSL --proto '=https' --proto-redir '=https' --connect-timeout 15 --max-time 30 "$api" 2>/dev/null || true)"
  if [ "$PRERELEASE" = 1 ]; then
    record="$(printf '%s' "$json" | tr -d '\r\n' | \
      sed 's#{[[:space:]]*"url":[[:space:]]*"https://api.github.com/repos/'"$REPO"'/releases/\([0-9][0-9]*\)"#\
&#g' | \
      awk '/"draft":[[:space:]]*false/ && !found { print; found=1 }')"
  else
    record="$json"
  fi
  tag="$(printf '%s' "$record" | grep -o '"tag_name": *"[^"]*"' | head -1 | cut -d'"' -f4 || true)"
  # A release carries one APK per installable identity, so the first `.apk` in the record is no
  # longer this provisioner's artifact. Select by the exact published URL of the successor asset.
  urls="$(printf '%s' "$record" | grep -o '"browser_download_url": *"[^"]*\.apk"' | cut -d'"' -f4 || true)"
  if [ -n "$tag" ] && valid_release_tag "$tag"; then
    asset="$(release_apk_name "$tag")"
    expected_url="$(release_apk_url "$tag")"
    printf '%s\n' "$urls" | grep -Fxq "$expected_url" && curl -fsSL --proto '=https' --proto-redir '=https' --connect-timeout 15 --max-time 300 "$expected_url" -o "$dir/$asset" || true
  fi
  [ -n "${asset:-}" ] && [ -s "$dir/$asset" ] && APK="$dir/$asset" || APK=""
  [ -n "$APK" ] || { echo "${RED}could not fetch the latest release APK from the expected GitHub release path${X}" >&2; exit 1; }
  APK_RELEASE_TAG="$tag"
  TOINSTALL_VER="${tag#v}"
  step "⬇️  downloaded" "${D}$(basename "$APK")${X} ${B}${tag:-latest}${X}"
}

# Pick the APK: explicit --apk wins; else the local development build (unless --latest); else download the latest release.
resolve_apk() {
  local source_apk staged_apk
  if [ -n "$APK" ]; then :
  elif [ "$LATEST" = 1 ]; then download_latest
  elif [ -f "$LOCAL_APK" ]; then APK="$LOCAL_APK"; step "📂 using local build" "${D}$APK${X}"
  else download_latest; fi
  [ -f "$APK" ] || { echo "${RED}APK not found: $APK${X}" >&2; exit 1; }
  # Every subsequent verifier and Android install opens one owner-only snapshot, not a mutable
  # caller/download path. Digest assertions remain mandatory around meaningful reads so even an
  # accidental in-process replacement of this snapshot fails closed.
  source_apk="$APK"
  CANDIDATE_APK_DIR="$(mktemp -d)" || fail "could not create a private candidate APK directory" \
    "No panel changes were made. Free host disk space, then retry."
  chmod 700 "$CANDIDATE_APK_DIR" 2>/dev/null || fail "could not protect the private candidate APK directory" \
    "No panel changes were made. Check host filesystem permissions, then retry."
  staged_apk="$CANDIDATE_APK_DIR/$(basename "$source_apk")"
  cp "$source_apk" "$staged_apk" 2>/dev/null && chmod 600 "$staged_apk" 2>/dev/null || \
    fail "could not snapshot the candidate APK into private storage" \
      "No panel changes were made. Check that the APK is readable and host temporary storage is writable."
  APK="$staged_apk"
  if [ -z "$TOINSTALL_VER" ]; then  # local/--apk: read the version via aapt if available (else the guard is skipped)
    for t in aapt aapt2; do
      if command -v "$t" >/dev/null 2>&1; then
        TOINSTALL_VER="$("$t" dump badging "$APK" 2>/dev/null | grep -o "versionName='[^']*'" | head -1 | cut -d"'" -f2 || true)"
        break
      fi
    done
  fi
  return 0  # never let the (possibly non-zero) probe above abort the script under set -e
}

# Discovery means USABLE, not merely present. `apksigner` is a wrapper script that needs a Java
# runtime, so on a host without one it exists, is executable, and fails the moment it is invoked —
# which read as "this APK is not properly signed" rather than "this tool cannot run" (#106). The
# openssl probe below has always required `openssl version` to succeed for exactly this reason; this
# holds the build tools to the same standard instead of trusting the file to be there. It must also
# answer: a wrapper can exit 0 without running anything, and a silent apksigner would later read as an
# APK with no signer, blaming the artifact for the host again (#24). The answer counts on either stream:
# apksigner and aapt print their version on stdout, but aapt2 prints its banner on stderr only.
android_build_tool_runs() {
  local out
  out="$("$1" version 2>&1)" && [ -n "$out" ] && return 0
  out="$("$1" --version 2>&1)" && [ -n "$out" ]
}

# Windows Build-Tools ship apksigner as apksigner.bat. Git Bash's MSYS runtime resolves a bare command
# name to name.exe and nothing else, so neither `command -v apksigner` nor the build-tools glob saw the
# wrapper, and a host that had the tool was refused as though it had none (#24). Under that runtime the
# .bat and .cmd spellings are searched too, and whatever is found is executed by its full name: the
# runtime hands a wrapper to cmd.exe itself and still converts the host file operands the tool is given.
# An SDK folder set the Windows way (C:\Users\...\Sdk, as System Properties writes it) arrives
# untranslated, so it is read through cygpath. Unlike the adb exclusion list, this branch is reachable
# from a Linux run: the suite drives it with a stand-in uname and cygpath.
case "$(uname -s 2>/dev/null || true)" in
  MINGW*|MSYS*|CYGWIN*) HOST_MSYS_RUNTIME=1 ;;
  *) HOST_MSYS_RUNTIME=0 ;;
esac

android_build_tool_spellings() {
  printf '%s\n' "$1"
  [ "$HOST_MSYS_RUNTIME" = 0 ] || printf '%s\n' "$1.bat" "$1.cmd"
  return 0
}

android_sdk_root() {
  local root="$1" converted=""
  [ "$HOST_MSYS_RUNTIME" = 0 ] || converted="$(cygpath -u "$root" 2>/dev/null || true)"
  printf '%s\n' "${converted:-$root}"
}

# One candidate per line as `<origin><TAB><path>`: every PATH resolution first, in spelling order, then
# every SDK build-tools entry in ascending version order. The origin is what lets a PATH choice win.
android_build_tool_candidates() {
  local name="$1" spelling path path_hits=$'\n' root version_dir candidate
  while IFS= read -r spelling; do
    path="$(command -v "$spelling" 2>/dev/null || true)"
    [ -n "$path" ] || continue
    printf 'path\t%s\n' "$path"
    path_hits="$path_hits$path"$'\n'
  done < <(android_build_tool_spellings "$name")
  for root in "${ANDROID_HOME:-}" "${ANDROID_SDK_ROOT:-}"; do
    [ -n "$root" ] || continue
    root="$(android_sdk_root "$root")"
    [ -d "$root/build-tools" ] || continue
    for version_dir in "$root"/build-tools/*/; do
      while IFS= read -r spelling; do
        candidate="$version_dir$spelling"
        [ -x "$candidate" ] || continue
        case "$path_hits" in *$'\n'"$candidate"$'\n'*) continue ;; esac
        printf 'sdk\t%s\n' "$candidate"
      done < <(android_build_tool_spellings "$name")
    done
  done
  return 0
}

find_android_build_tool() {
  local name="$1" origin candidate newest=""
  # Requiring the tool to actually run is right: a present-but-unrunnable apksigner used to fail the whole
  # install. Taking the FIRST runnable candidate was not: the SDK glob is ascending, so it preferred the
  # OLDEST build-tools, where v0.9.6 preferred the newest. An old aapt whose `dump badging` prints nothing
  # then aborts a release install with "package mismatch / Got unavailable" on an APK that verified before.
  # A tool on PATH stays the operator's explicit choice and still wins outright.
  while IFS=$'\t' read -r origin candidate; do
    android_build_tool_runs "$candidate" || continue
    if [ "$origin" = path ]; then printf '%s\n' "$candidate"; return 0; fi
    newest="$candidate"
  done < <(android_build_tool_candidates "$name")
  [ -z "$newest" ] || printf '%s\n' "$newest"
  return 0
}

# Why a tool could not be used, so a refusal can name the real problem. Empty when it ran, or when the
# tool is simply not installed — "absent" already has its own wording and is not a malfunction.
android_build_tool_failure() {
  local name="$1" path detail failures=""
  while IFS=$'\t' read -r _ path; do
    if android_build_tool_runs "$path"; then continue; fi
    detail="$("$path" version 2>&1 | head -1 | LC_ALL=C tr -d '\000-\037\177' || true)"
    failures="${failures}${failures:+; }$name is installed at $path but could not run: ${detail:-no output}"
  done < <(android_build_tool_candidates "$name")
  [ -z "$failures" ] || printf '%s' "$failures"
  return 0
}

verify_release_checksum() {
  local openssl_tool proof_dir checksum_file signature_file public_key expected_name record expected_hash actual_hash
  openssl_tool="$(command -v openssl 2>/dev/null || true)"
  if [ -z "$openssl_tool" ] || ! "$openssl_tool" version >/dev/null 2>&1; then
    fail "OpenSSL is required to authenticate this official ha-paneld release" \
      "No panel changes were made. Install OpenSSL, then re-run the same command." \
      "Windows: use a current Git Bash or WSL terminal. macOS: run xcode-select --install, or brew install openssl." \
      "Debian/Ubuntu: sudo apt install openssl · Fedora: sudo dnf install openssl · Arch: sudo pacman -S openssl"
  fi

  proof_dir="$(mktemp -d)" || fail "could not create a temporary directory for release verification" \
    "Free some disk space, then re-run; no panel changes were made."
  checksum_file="$proof_dir/release.sha256"
  signature_file="$proof_dir/release.sha256.sig"
  public_key="$proof_dir/release-public-key.pem"
  if ! write_release_public_key "$public_key"; then
    rm -rf "$proof_dir"
    fail "could not prepare the trusted ha-paneld release key" "No panel changes were made. Check free disk space, then re-run."
  fi
  if ! RELEASE_APK_SOURCE="$APK" curl -fsSL --proto '=https' --proto-redir '=https' --connect-timeout 15 --max-time 60 \
      "$(release_checksum_url "$APK_RELEASE_TAG")" -o "$checksum_file"; then
    rm -rf "$proof_dir"
    fail "could not download the signed checksum for $APK_RELEASE_TAG" \
      "The release may be incomplete or GitHub may be unavailable. No panel changes were made; check internet access and retry."
  fi
  if ! curl -fsSL --proto '=https' --proto-redir '=https' --connect-timeout 15 --max-time 60 \
      "$(release_signature_url "$APK_RELEASE_TAG")" -o "$signature_file"; then
    rm -rf "$proof_dir"
    fail "could not download the checksum signature for $APK_RELEASE_TAG" \
      "The release may be incomplete or GitHub may be unavailable. No panel changes were made; check internet access and retry."
  fi
  if ! "$openssl_tool" dgst -sha256 -verify "$public_key" -signature "$signature_file" "$checksum_file" >/dev/null 2>&1; then
    rm -rf "$proof_dir"
    fail "the $APK_RELEASE_TAG checksum signature is invalid" \
      "The release assets could be incomplete, damaged, or substituted. Nothing was installed, started, or privileged." \
      "Delete the downloaded files and retry from the official GitHub release."
  fi

  expected_name="$(release_apk_name "$APK_RELEASE_TAG")"
  record="$(cat "$checksum_file")"
  expected_hash="${record%% *}"
  if ! printf '%s\n' "$expected_hash" | grep -Eq '^[0-9A-Fa-f]{64}$' || [ "$record" != "$expected_hash  $expected_name" ]; then
    rm -rf "$proof_dir"
    fail "the signed checksum record for $APK_RELEASE_TAG is malformed" \
      "Expected one SHA-256 record for $expected_name. Nothing was installed, started, or privileged."
  fi
  if ! actual_hash="$("$openssl_tool" dgst -sha256 -r "$APK" 2>/dev/null | awk '{print tolower($1)}')" || \
      ! printf '%s\n' "$actual_hash" | grep -Eq '^[0-9a-f]{64}$'; then
    rm -rf "$proof_dir"
    fail "OpenSSL could not calculate the downloaded APK checksum" \
      "No panel changes were made. Check that the APK is readable and re-run the installer."
  fi
  expected_hash="$(printf '%s' "$expected_hash" | tr '[:upper:]' '[:lower:]')"
  if [ "$actual_hash" != "$expected_hash" ]; then
    rm -rf "$proof_dir"
    fail "the $APK_RELEASE_TAG APK checksum does not match its signed record" \
      "The APK could be incomplete, damaged, or substituted. Nothing was installed, started, or privileged." \
      "Delete the downloaded files and retry from the official GitHub release."
  fi
  rm -rf "$proof_dir"
  step "🔐 authenticated" "${D}$APK_RELEASE_TAG · signed SHA-256 ${actual_hash:0:12}…${X}"
}

verify_release_apk() {
  local expected_name signer_tool signer_output signer signer_lines signer_count package_tool package_name="" artifact_label
  local signer_tool_problem signer_error package_tool_problem
  if [ -n "$APK_RELEASE_TAG" ]; then
    expected_name="$(release_apk_name "$APK_RELEASE_TAG")"
    [ "$(basename "$APK")" = "$expected_name" ] || fail "release APK filename does not match its tag" \
      "Expected $expected_name for $APK_RELEASE_TAG; nothing was installed."
    verify_release_checksum
    artifact_label="$APK_RELEASE_TAG"
  else
    artifact_label="local signed APK"
  fi
  signer_tool="$(find_android_build_tool apksigner || true)"
  signer_tool_problem="$(android_build_tool_failure apksigner || true)"
  if [ -z "$signer_tool" ]; then
    if [ -n "$APK_RELEASE_TAG" ] && [ "$REQUIRE_RELEASE_SIGNER" != 1 ]; then
      # A tool that cannot run carries exactly as much evidence as one that is absent — none — so it
      # takes the same path. Refusing here would reject an APK this run has already authenticated
      # against the pinned release key, and blame the artifact for the host's missing Java runtime.
      # It is only skippable for a first installation, though, so the warning must not call the tool
      # optional one line before an update of an installed panel refuses for the lack of it (#24).
      if [ -n "$signer_tool_problem" ]; then
        warn "The APK structure inspection was skipped: ${signer_tool_problem%.}. The APK was still authenticated by the signed checksum above, which is enough for a first installation because Android checks the APK signature itself. Updating a panel that already has ha-paneld needs this tool; that is checked before anything on the panel changes."
      else
        warn "Android Build-Tools were not found, so the APK structure inspection was skipped. The APK was still authenticated by the signed checksum above, which is enough for a first installation because Android checks the APK signature itself. Updating a panel that already has ha-paneld needs these tools; that is checked before anything on the panel changes."
      fi
    else
      fail "Android Build-Tools are required to verify a local APK signer" \
        "${signer_tool_problem:-Install apksigner and retry.} Nothing was backed up, installed, started, or privileged." \
        "Self-built APKs may use their developer signer; official-release deployments add --require-release-signer."
    fi
  else
    assert_candidate_apk_unchanged
    if signer_output="$("$signer_tool" verify --print-certs "$APK" 2>&1)"; then :; else
      signer_error="$(printf '%s\n' "$signer_output" | head -2 | LC_ALL=C tr -d '\000-\037\177' || true)"
      fail "release APK signature verification failed" \
        "The APK was not installed. Check that it is a valid signed APK and retry." \
        "${signer_error:-apksigner reported no reason}"
    fi
    assert_candidate_apk_unchanged
    signer_lines="$(printf '%s\n' "$signer_output" | sed -nE 's/^Signer #[0-9]+ certificate SHA-256 digest: *//p')"
    signer_count="$(printf '%s\n' "$signer_lines" | awk 'NF { count++ } END { print count + 0 }')"
    [ "$signer_count" = 1 ] || fail "release APK signer count mismatch" \
      "Expected exactly one signer; got $signer_count. Nothing was backed up, installed, started, or privileged."
    signer="$(printf '%s\n' "$signer_lines" | head -1 | tr -d ':\r' | tr '[:upper:]' '[:lower:]')"
    if [ -n "$APK_RELEASE_TAG" ] || [ "$REQUIRE_RELEASE_SIGNER" = 1 ]; then
      [ "$signer" = "$RELEASE_CERT_SHA256" ] || fail "release APK signer mismatch" \
        "Expected $RELEASE_CERT_SHA256" "Got      ${signer:-unavailable}" \
        "This run requires the official release signer. Nothing was installed, started, or privileged." \
        "No configuration backup or helper transaction was started."
    fi
    CANDIDATE_SIGNER_SHA256="$signer"
  fi
  package_tool="$(find_android_build_tool aapt || true)"
  [ -n "$package_tool" ] || package_tool="$(find_android_build_tool aapt2 || true)"
  package_tool_problem="$(android_build_tool_failure aapt || true)"
  [ -n "$package_tool_problem" ] || package_tool_problem="$(android_build_tool_failure aapt2 || true)"
  if [ -n "$package_tool" ]; then
    assert_candidate_apk_unchanged
    package_name="$("$package_tool" dump badging "$APK" 2>/dev/null | sed -nE "s/^package: name='([^']+)'.*/\1/p" | head -1 || true)"
    assert_candidate_apk_unchanged
    [ "$package_name" = "$PKG" ] || fail "release APK package mismatch" \
      "Expected $PKG" "Got      ${package_name:-unavailable}" \
      "A local build carries $LEGACY_PKG unless it is built with -PappIdentity=successor." \
      "Nothing was installed, started, or privileged."
  elif [ -z "$APK_RELEASE_TAG" ]; then
    fail "Android Build-Tools are required to verify a local APK package" \
      "${package_tool_problem:-Install aapt or aapt2 and retry.} Nothing was backed up, installed, started, or privileged."
  fi
  # `--require-release-signer` deliberately does NOT force the package tool. That flag is about who
  # signed the artifact, and the signer branch above already answers it with apksigner. The package
  # name is a different question, and for a release asset the signed checksum has already settled it
  # by hashing the whole file. Coupling the two refused a fleet deployment of an artifact this run had
  # just authenticated, on a host missing a tool it did not need — and a fleet deploy forces that flag
  # on its own download path, so the coupling fired exactly where the evidence was strongest.
  step "🛡️  verified" "${D}$artifact_label · package ${package_name:-authenticated release asset} · signer ${signer:-$RELEASE_CERT_SHA256}${X}"
}

verify_incumbent_signer_for_replacement() {
  local signer_tool installed_path installed_apk output signer_lines signer_count installed_signer candidate_signer
  [ "$PACKAGE_PRESENCE" = present ] || return 0
  signer_tool="$(find_android_build_tool apksigner || true)"
  [ -n "$signer_tool" ] || host_build_tools_refuse apksigner
  installed_path="$(run_with_deadline "$ADB_COMMAND_TIMEOUT_SECONDS" adb_exec -s "$TARGET" shell pm path "$PKG" 2>/dev/null \
    | sed -n 's/^package://p' | head -1 | tr -d '\r' || true)"
  case "$installed_path" in /*) ;; *) host_database_gate_refuse "the installed APK path could not be re-read for signer comparison" ;; esac
  case "$installed_path" in *[!0-9A-Za-z._/+,:=@~-]*) host_database_gate_refuse "the installed APK path is malformed" ;; esac
  installed_apk="$(mktemp "${TMPDIR:-/tmp}/hapaneld-installed-apk-XXXXXX.apk")" || \
    host_database_gate_refuse "a private working file for signer comparison could not be created"
  if ! run_with_deadline "$ADB_COMMAND_TIMEOUT_SECONDS" adb_exec -s "$TARGET" pull "$installed_path" "$installed_apk" >/dev/null 2>&1; then
    rm -f "$installed_apk"
    host_database_gate_refuse "the installed APK could not be read for signer comparison"
  fi
  output="$("$signer_tool" verify --print-certs "$installed_apk" 2>/dev/null || true)"
  rm -f "$installed_apk"
  signer_lines="$(printf '%s\n' "$output" | sed -nE 's/^Signer #[0-9]+ certificate SHA-256 digest: *//p')"
  signer_count="$(printf '%s\n' "$signer_lines" | awk 'NF { n++ } END { print n + 0 }')"
  [ "$signer_count" = 1 ] || host_database_gate_refuse "the installed APK signer could not be proven uniquely"
  installed_signer="$(printf '%s\n' "$signer_lines" | head -1 | tr -d ':\r' | tr '[:upper:]' '[:lower:]')"
  assert_candidate_apk_unchanged
  candidate_signer="$CANDIDATE_SIGNER_SHA256"
  printf '%s\n' "$candidate_signer" | grep -Eq '^[0-9a-f]{64}$' || \
    host_database_gate_refuse "the candidate APK signer could not be proven uniquely"
  [ "$candidate_signer" = "$installed_signer" ] || host_signer_mismatch_refuse "$installed_signer" "$candidate_signer"
}

verify_release_helper() {
  local helper="$1" tag="$2" abi="$3"
  local proof_dir checksum_file signature_file public_key expected_name record expected_hash actual_hash
  expected_name="$(release_helper_name "$tag" "$abi")"
  step "🔐 authenticating helper" "${D}$tag · $abi · signed checksum and build bytes${X}"
  proof_dir="$(mktemp -d)" || fail "could not create a temporary directory for helper verification" \
    "Free some disk space, then re-run; no panel changes were made."
  checksum_file="$proof_dir/helper.sha256"
  signature_file="$proof_dir/helper.sha256.sig"
  public_key="$proof_dir/release-public-key.pem"
  if ! write_release_public_key "$public_key"; then
    rm -rf "$proof_dir"
    fail "could not prepare the trusted ha-paneld release key" "No panel changes were made. Check free disk space, then re-run."
  fi
  if ! RELEASE_HELPER_SOURCE="$helper" curl -fsSL --proto '=https' --proto-redir '=https' --connect-timeout 15 --max-time 60 \
      "$(release_helper_url "$tag" "$abi").sha256" -o "$checksum_file"; then
    rm -rf "$proof_dir"
    fail "could not download the signed helper checksum for $tag ($abi)" \
      "The release may be incomplete or GitHub may be unavailable. No panel changes were made; check internet access and retry."
  fi
  if ! curl -fsSL --proto '=https' --proto-redir '=https' --connect-timeout 15 --max-time 60 \
      "$(release_helper_url "$tag" "$abi").sha256.sig" -o "$signature_file"; then
    rm -rf "$proof_dir"
    fail "could not download the helper checksum signature for $tag ($abi)" \
      "The release may be incomplete or GitHub may be unavailable. No panel changes were made; check internet access and retry."
  fi
  if ! openssl dgst -sha256 -verify "$public_key" -signature "$signature_file" "$checksum_file" >/dev/null 2>&1; then
    rm -rf "$proof_dir"
    fail "the $tag helper checksum signature is invalid" \
      "The release assets could be incomplete, damaged, or substituted. Nothing was installed, started, or privileged."
  fi
  record="$(cat "$checksum_file")"
  expected_hash="${record%% *}"
  if ! printf '%s\n' "$expected_hash" | grep -Eq '^[0-9A-Fa-f]{64}$' || [ "$record" != "$expected_hash  $expected_name" ]; then
    rm -rf "$proof_dir"
    fail "the signed helper checksum record for $tag is malformed" \
      "Expected one SHA-256 record for $expected_name. Nothing was installed, started, or privileged."
  fi
  if ! actual_hash="$(openssl dgst -sha256 -r "$helper" 2>/dev/null | awk '{print tolower($1)}')" || \
      ! printf '%s\n' "$actual_hash" | grep -Eq '^[0-9a-f]{64}$'; then
    rm -rf "$proof_dir"
    fail "OpenSSL could not calculate the downloaded helper checksum" \
      "No panel changes were made. Check that the helper asset is readable and re-run the installer."
  fi
  expected_hash="$(printf '%s' "$expected_hash" | tr '[:upper:]' '[:lower:]')"
  if [ "$actual_hash" != "$expected_hash" ]; then
    rm -rf "$proof_dir"
    fail "the $tag helper checksum does not match its signed record" \
      "The helper could be incomplete, damaged, or substituted. Nothing was installed, started, or privileged."
  fi
  rm -rf "$proof_dir"
  step "🔐 authenticated" "${D}$tag helper · $abi · signed SHA-256 ${actual_hash:0:12}…${X}"
}

# A helper binary stamps its own build identity into its bytes, so the identity of the helper that
# is about to serve the socket can be read from the artifact itself rather than inferred from
# whatever tree, tag or checkout the installer happens to be running from. Exactly one well-formed
# record must be present: none means the artifact cannot state who it is, and more than one means it
# states two contradictory answers. Both are refusals, never a guess.
extract_helper_build_id() {
  local file="$1" records ids
  # No match and an unreadable file are both "cannot state an identity", which the count check
  # below turns into a refusal; the tolerated statuses here only stop `set -e` from aborting the
  # run before that refusal can be reported with its own message.
  # Read the record as what it is, the same way helper/install-daemon.sh does. In the artifact the
  # record is a C string ended by a NUL and by nothing else, so translating NUL to newline first is
  # what makes it a line. The narrow pattern below used to accept a record that RUNS ON past the
  # identity, because it matched the first 64 hex characters and ignored whatever followed; matching
  # the whole record and then requiring exactly 64 hex refuses that. LC_ALL=C is pinned because `.`
  # is defined over characters, and in a stripped binary an invalid byte sequence is ordinary payload.
  records="$(LC_ALL=C tr '\0' '\n' < "$file" 2>/dev/null | LC_ALL=C grep -aoE 'BUILDID .*' || true)"
  [ "$(printf '%s\n' "$records" | LC_ALL=C grep -c '^BUILDID ')" -eq 1 ] || return 1
  ids="$(printf '%s\n' "$records" | LC_ALL=C sed -nE 's/^BUILDID ([0-9a-f]{64})$/\1/p')"
  [ "$(printf '%s\n' "$ids" | LC_ALL=C grep -Ec '^[0-9a-f]{64}$')" -eq 1 ] || return 1
  printf '%s\n' "$ids"
}

fetch_release_helper_build_id() {
  local tag="$1" proof_dir provisioner checksum_file signature_file public_key expected_name
  local record expected_hash actual_hash build_id
  expected_name="ha-paneld-provision-$tag.sh"
  proof_dir="$(mktemp -d)" || return 1
  provisioner="$proof_dir/$expected_name"
  checksum_file="$proof_dir/$expected_name.sha256"
  signature_file="$checksum_file.sig"
  public_key="$proof_dir/release-public-key.pem"
  write_release_public_key "$public_key" || { rm -rf "$proof_dir"; return 1; }
  curl -fsSL --proto '=https' --proto-redir '=https' --connect-timeout 15 --max-time 60 \
    "https://github.com/$REPO/releases/download/$tag/$expected_name" -o "$provisioner" || { rm -rf "$proof_dir"; return 1; }
  RELEASE_PROVISION_SOURCE="$provisioner" curl -fsSL --proto '=https' --proto-redir '=https' \
    --connect-timeout 15 --max-time 60 \
    "https://github.com/$REPO/releases/download/$tag/$expected_name.sha256" -o "$checksum_file" || {
      rm -rf "$proof_dir"; return 1;
    }
  curl -fsSL --proto '=https' --proto-redir '=https' --connect-timeout 15 --max-time 60 \
    "https://github.com/$REPO/releases/download/$tag/$expected_name.sha256.sig" -o "$signature_file" || {
      rm -rf "$proof_dir"; return 1;
    }
  openssl dgst -sha256 -verify "$public_key" -signature "$signature_file" "$checksum_file" >/dev/null 2>&1 || {
    rm -rf "$proof_dir"; return 1;
  }
  record="$(cat "$checksum_file")"
  expected_hash="${record%% *}"
  if ! printf '%s\n' "$expected_hash" | grep -Eq '^[0-9A-Fa-f]{64}$' || \
     [ "$record" != "$expected_hash  $expected_name" ]; then
    rm -rf "$proof_dir"; return 1
  fi
  actual_hash="$(openssl dgst -sha256 -r "$provisioner" 2>/dev/null | awk '{print tolower($1)}')" || {
    rm -rf "$proof_dir"; return 1;
  }
  expected_hash="$(printf '%s' "$expected_hash" | tr '[:upper:]' '[:lower:]')"
  [ "$actual_hash" = "$expected_hash" ] || { rm -rf "$proof_dir"; return 1; }
  build_id="$(sed -nE 's/^RELEASE_HELPER_BUILD_ID="([0-9a-f]{64})"$/\1/p' "$provisioner")"
  rm -rf "$proof_dir"
  printf '%s\n' "$build_id" | grep -Eq '^[0-9a-f]{64}$' || return 1
  printf '%s\n' "$build_id"
}

host_sha256() {
  if command -v sha256sum >/dev/null 2>&1; then sha256sum "$1" | awk '{print $1}'
  elif command -v shasum >/dev/null 2>&1; then shasum -a 256 "$1" | awk '{print $1}'
  else fail "cannot authenticate helper staging" "Install sha256sum (or shasum), then re-run."
  fi
}

bind_candidate_apk_bytes() {
  TARGET_APK_SHA256="$(host_sha256 "$APK" 2>/dev/null || true)"
  printf '%s\n' "$TARGET_APK_SHA256" | grep -Eq '^[0-9a-f]{64}$' || fail "could not bind the authenticated candidate APK bytes" \
    "No panel mutation was started. Check that the APK is a readable regular file, then retry."
}

assert_candidate_apk_unchanged() {
  local observed
  [ -n "$TARGET_APK_SHA256" ] || fail "the candidate APK has no authenticated byte binding" \
    "No panel mutation was started. Re-run the same provisioning command."
  observed="$(host_sha256 "$APK" 2>/dev/null || true)"
  if [ "$observed" != "$TARGET_APK_SHA256" ]; then
    if { [ "${DB_GATE_PHASE:-}" = consume ] || [ "${DB_GATE_PHASE:-}" = package ]; } && \
       type host_database_gate_refuse >/dev/null 2>&1; then
      host_database_gate_refuse "the candidate APK bytes changed after authentication (expected $TARGET_APK_SHA256, observed ${observed:-unreadable})"
    fi
    fail "the candidate APK bytes changed after authentication" \
      "Expected SHA-256 $TARGET_APK_SHA256" \
      "Observed       ${observed:-unreadable}" \
      "No settings backup, database snapshot, reset, helper, Shizuku, APK, permission or configuration mutation was started. Use an APK path that cannot be replaced during provisioning, then retry."
  fi
}

host_transaction_id() {
  local id
  id="$(od -An -N16 -tx1 /dev/urandom 2>/dev/null | tr -d ' \n')"
  printf '%s\n' "$id" | grep -Eq '^[0-9a-f]{32}$' || return 1
  printf '%s\n' "$id"
}

# A negative root probe is only accepted from a transport that proves it is still alive to answer:
# a probe whose transport died mid-question looks identical to a genuine "no", and acting on an
# unproven negative would treat an unknown capability as a decided one.
probe_transport_alive() {
  local deadline="${1:-15}" probe_alive
  probe_alive="$(run_with_deadline "$deadline" adb_exec -s "$TARGET" shell echo HAPANELD_TRANSPORT_ALIVE 2>/dev/null | tr -d '\r')" || probe_alive=""
  case "$probe_alive" in
    *HAPANELD_TRANSPORT_ALIVE*) return 0 ;;
    *) return 1 ;;
  esac
}

# The panel's root capability is decided ONCE, before anything consumes it. resolve_root_route is
# called from the main flow ahead of the database capture; the capture gate and the helper/APK
# mutation both act on the stored verdict, so they cannot disagree, and no later phase re-asks a
# question the panel might answer differently a second time. The whole decision — first probe,
# `adb root` escalation where the probe said no (userdebug panels may expose root only after
# `adb root`; unsupported production builds reject it harmlessly; a successful restart briefly
# drops the transport, so reconnect before probing again), and the reprobe — runs under one
# aggregate deadline (ROOT_RESOLVE_TIMEOUT_SECONDS, default 90s), each step spending only the
# remaining budget. Verdicts:
#   rooted            — a working root route; SU_FORM holds it for run_root
#   unrooted          — a PROVEN "no": every probe completed and the transport proved it was
#                       still alive to answer
#   unknown-timeout   — a probe or the whole sequence ran out of time; capability undecided
#   unknown-transport — the transport died mid-question; capability undecided
# Consumers fail closed on both unknown verdicts: an undecided panel is exactly the one whose
# transport could recover into root a moment after a skip or a helperless install.
ROOT_ROUTE_VERDICT=""
min_deadline() {
  if [ "$1" -le "$2" ]; then printf '%s\n' "$1"; else printf '%s\n' "$2"; fi
}
resolve_root_route() {
  local budget="${ROOT_RESOLVE_TIMEOUT_SECONDS:-90}" start="$SECONDS" remaining
  case "$budget" in ''|*[!0-9]*|0) budget=90 ;; esac
  local saved_probe_timeout="${PRIVILEGE_INSPECTION_TIMEOUT_SECONDS:-45}" probe_timeout
  case "$saved_probe_timeout" in ''|*[!0-9]*|0) saved_probe_timeout=45 ;; esac
  probe_timeout="$saved_probe_timeout"
  [ "$probe_timeout" -le "$budget" ] || probe_timeout="$budget"
  PRIVILEGE_INSPECTION_TIMEOUT_SECONDS="$probe_timeout"
  if probe_su; then
    PRIVILEGE_INSPECTION_TIMEOUT_SECONDS="$saved_probe_timeout"
    ROOT_ROUTE_VERDICT=rooted
    return 0
  fi
  PRIVILEGE_INSPECTION_TIMEOUT_SECONDS="$saved_probe_timeout"
  if [ "$SU_PROBE_TIMED_OUT" = 1 ]; then
    ROOT_ROUTE_VERDICT=unknown-timeout
    return 0
  fi
  remaining=$((budget - (SECONDS - start)))
  if [ "$remaining" -le 0 ]; then
    ROOT_ROUTE_VERDICT=unknown-timeout
    return 0
  fi
  if ! probe_transport_alive "$(min_deadline 15 "$remaining")"; then
    ROOT_ROUTE_VERDICT=unknown-transport
    return 0
  fi
  remaining=$((budget - (SECONDS - start)))
  if [ "$remaining" -le 0 ]; then
    ROOT_ROUTE_VERDICT=unknown-timeout
    return 0
  fi
  run_with_deadline "$remaining" adb_exec -s "$TARGET" root >/dev/null 2>&1 || true
  # A wait never STARTS past the deadline; a started one-second wait may overrun it by at most
  # that one quantum, which the resolver's unit contract asserts.
  remaining=$((budget - (SECONDS - start)))
  [ "$remaining" -le 0 ] || sleep 1
  remaining=$((budget - (SECONDS - start)))
  if [ "$remaining" -gt 0 ]; then
    run_with_deadline "$remaining" adb_exec connect "$TARGET" >/dev/null 2>&1 || true
  fi
  local state="" attempt=0
  while [ "$attempt" -lt 8 ]; do
    attempt=$((attempt + 1))
    remaining=$((budget - (SECONDS - start)))
    [ "$remaining" -gt 0 ] || break
    state="$(run_with_deadline "$remaining" adb_exec devices 2>/dev/null | awk -v t="$TARGET" '$1==t {print $2}')" || state=""
    [ "$state" = device ] && break
    remaining=$((budget - (SECONDS - start)))
    [ "$remaining" -le 0 ] || sleep 1
  done
  remaining=$((budget - (SECONDS - start)))
  if [ "$remaining" -le 0 ]; then
    ROOT_ROUTE_VERDICT=unknown-timeout
    return 0
  fi
  SU_FORM=""
  probe_timeout="$saved_probe_timeout"
  [ "$probe_timeout" -le "$remaining" ] || probe_timeout="$remaining"
  PRIVILEGE_INSPECTION_TIMEOUT_SECONDS="$probe_timeout"
  if probe_su; then
    PRIVILEGE_INSPECTION_TIMEOUT_SECONDS="$saved_probe_timeout"
    ROOT_ROUTE_VERDICT=rooted
    return 0
  fi
  PRIVILEGE_INSPECTION_TIMEOUT_SECONDS="$saved_probe_timeout"
  if [ "$SU_PROBE_TIMED_OUT" = 1 ]; then
    ROOT_ROUTE_VERDICT=unknown-timeout
    return 0
  fi
  remaining=$((budget - (SECONDS - start)))
  if [ "$remaining" -le 0 ]; then
    ROOT_ROUTE_VERDICT=unknown-timeout
    return 0
  fi
  # The liveness recheck spends only what is left, never more than its own 15s ceiling: a positive
  # allowance must not quietly reopen an exhausted aggregate budget.
  if probe_transport_alive "$(min_deadline 15 "$remaining")"; then
    ROOT_ROUTE_VERDICT=unrooted
    return 0
  fi
  ROOT_ROUTE_VERDICT=unknown-transport
  return 0
}

helper_daemon_reply() {
  local command="$1" install_kind="$2" helper_path="${3:-}"
  case "$command" in PING|COMPANIONCAPS|BUILDID|GUARDCAPS|GUARDSTATUS) ;; *) return 1 ;; esac
  if [ -z "$helper_path" ]; then
    case "$install_kind" in
      system|systemless|hybrid) helper_path=/data/local/hapaneld-helper ;;
      *) return 1 ;;
    esac
  fi
  case "$helper_path" in
    /data/local/hapaneld-helper|/system/bin/hapaneld-helper|/data/adb/hapaneld/hapaneld-helper|/data/adb/hapaneld/.helper-probe-[0-9a-f]*) ;;
    *) return 1 ;;
  esac
  if [ -n "${HAPANELD_HELPER_PROBE:-}" ]; then
    "$HAPANELD_HELPER_PROBE" "$command"
    return $?
  fi
  run_root 'exec '"$helper_path"' --request '"$command"
}

wait_for_helper_reply() {
  local command="$1" expected="$2" install_kind="$3" helper_path="${4:-}" reply="" attempt=0
  while [ "$attempt" -lt 10 ]; do
    attempt=$((attempt + 1))
    reply="$(helper_daemon_reply "$command" "$install_kind" "$helper_path" 2>/dev/null || true)"
    [ "$reply" = "$expected" ] && return 0
    sleep 1
  done
  return 1
}

prepare_root_helper_probe() {
  local transaction_id="$1" probe_path expected source ready
  printf '%s\n' "$transaction_id" | grep -Eq '^[0-9a-f]{32}$' || return 1
  expected="$ROOT_HELPER_TARGET_SHA256"
  source="$ROOT_HELPER_STAGED_HELPER"
  probe_path="/data/adb/hapaneld/.helper-probe-$transaction_id"
  printf '%s\n' "$expected" | grep -Eq '^[0-9a-f]{64}$' || return 1
  [ "$source" = "/data/local/tmp/hapaneld-helper-$ROOT_HELPER_TRANSACTION_ID" ] || return 1
  ready="$(run_root 'mkdir -p /data/adb/hapaneld || exit 1
    chown 0:0 /data/adb/hapaneld || exit 1
    chmod 700 /data/adb/hapaneld || exit 1
    source='"$source"'
    destination='"$probe_path"'
    expected='"$expected"'
    actual=$(sha256sum $source 2>/dev/null || toybox sha256sum $source 2>/dev/null) || exit 1
    [ ${actual%% *} = $expected ] || exit 1
    cp $source $destination.new || exit 1
    chown 0:0 $destination.new || exit 1
    chmod 700 $destination.new || exit 1
    actual=$(sha256sum $destination.new 2>/dev/null || toybox sha256sum $destination.new 2>/dev/null) || exit 1
    [ ${actual%% *} = $expected ] || exit 1
    mv -f $destination.new $destination || exit 1
    echo PROBE_READY' 2>&1)" || true
  [ "$ready" = PROBE_READY ]
}

rollback_root_helper() {
  local install_kind="$1" transaction_id="${2:-$ROOT_HELPER_TRANSACTION_ID}" target_apk="${3:-$TARGET_APK_SHA256}"
  local target_build="${4:-$ROOT_HELPER_TARGET_BUILD_ID}" target_helper="${5:-$ROOT_HELPER_TARGET_SHA256}" restored probe_path
  probe_path="/data/adb/hapaneld/.helper-probe-$transaction_id"
  prepare_root_helper_probe "$transaction_id" || return 1
  case "$install_kind" in
    system)
      restored="$(run_root_helper_transaction rollback-system "$transaction_id" "$target_apk" "$target_build" "$target_helper" 2>&1)" || true
      ;;
    systemless)
      restored="$(run_root_helper_transaction rollback-systemless "$transaction_id" "$target_apk" "$target_build" "$target_helper" 2>&1)" || true
      ;;
    hybrid)
      restored="$(run_root_helper_transaction rollback-hybrid "$transaction_id" "$target_apk" "$target_build" "$target_helper" 2>&1)" || true
      ;;
    *) return 1 ;;
  esac
  if printf '%s\n' "$restored" | grep -qx ROLLBACK_RESTARTED; then
    if wait_for_helper_reply PING OK "$install_kind" "$probe_path"; then
      # Restarting the restored system helper can briefly restart adbd. Do not race journal
      # finalization against that transport transition: reconnect once, then wait boundedly.
      adb connect "$TARGET" >/dev/null 2>&1 || true
      run_with_deadline 30 adb -s "$TARGET" wait-for-device >/dev/null 2>&1 || return 1
      run_root 'rm -f '"$probe_path" >/dev/null 2>&1 || true
      finalize_root_helper_rollback "$install_kind" "$transaction_id" "$target_apk" "$target_build" "$target_helper"
      return $?
    fi
    run_root 'rm -f '"$probe_path" >/dev/null 2>&1 || true
    return 1
  else
    run_root 'rm -f '"$probe_path" >/dev/null 2>&1 || true
    if printf '%s\n' "$restored" | grep -Eqx 'ROLLBACK_(EMPTY|LEGACY)'; then
      finalize_root_helper_rollback "$install_kind" "$transaction_id" "$target_apk" "$target_build" "$target_helper"
    else
      printf '%s\n' "$restored" | grep -qx ROLLBACK_UNNEEDED
    fi
  fi
}

finalize_root_helper_rollback() {
  local install_kind="$1" transaction_id="$2" target_apk="$3" target_build="$4" target_helper="$5" finalized
  case "$install_kind" in
    system)
      finalized="$(run_root_helper_transaction finalize-rollback-system "$transaction_id" "$target_apk" "$target_build" "$target_helper" 2>&1)" || true
      ;;
    systemless)
      finalized="$(run_root_helper_transaction finalize-rollback-systemless "$transaction_id" "$target_apk" "$target_build" "$target_helper" 2>&1)" || true
      ;;
    hybrid)
      finalized="$(run_root_helper_transaction finalize-rollback-hybrid "$transaction_id" "$target_apk" "$target_build" "$target_helper" 2>&1)" || true
      ;;
    *) return 1 ;;
  esac
  printf '%s\n' "$finalized" | grep -qx ROLLBACK_FINALIZED
}

commit_root_helper_upgrade() {
  local install_kind="$1" transaction_id="${2:-$ROOT_HELPER_TRANSACTION_ID}" target_apk="${3:-$TARGET_APK_SHA256}"
  local target_build="${4:-$ROOT_HELPER_TARGET_BUILD_ID}" target_helper="${5:-$ROOT_HELPER_TARGET_SHA256}" committed
  case "$install_kind" in
    system) committed="$(run_root_helper_transaction commit-system "$transaction_id" "$target_apk" "$target_build" "$target_helper" 2>&1)" || true ;;
    systemless) committed="$(run_root_helper_transaction commit-systemless "$transaction_id" "$target_apk" "$target_build" "$target_helper" 2>&1)" || true ;;
    hybrid) committed="$(run_root_helper_transaction commit-hybrid "$transaction_id" "$target_apk" "$target_build" "$target_helper" 2>&1)" || true ;;
    *) return 1 ;;
  esac
  printf '%s\n' "$committed" | grep -qx COMMIT_OK
}

cancel_root_helper_external_change() {
  local install_kind="$1" transaction_id="$2" target_apk="$3" target_build="$4" target_helper="$5" canceled
  case "$install_kind" in
    system) canceled="$(run_root_helper_transaction cancel-external-system "$transaction_id" "$target_apk" "$target_build" "$target_helper" 2>&1)" || true ;;
    systemless) canceled="$(run_root_helper_transaction cancel-external-systemless "$transaction_id" "$target_apk" "$target_build" "$target_helper" 2>&1)" || true ;;
    hybrid) canceled="$(run_root_helper_transaction cancel-external-hybrid "$transaction_id" "$target_apk" "$target_build" "$target_helper" 2>&1)" || true ;;
    *) return 1 ;;
  esac
  printf '%s\n' "$canceled" | grep -qx EXTERNAL_CANONICAL_RETRY
}

renew_root_helper_lease() {
  local install_kind="$1" renewed
  case "$install_kind" in
    system) renewed="$(run_root_helper_transaction lease-system 2>&1)" || true ;;
    systemless) renewed="$(run_root_helper_transaction lease-systemless 2>&1)" || true ;;
    hybrid) renewed="$(run_root_helper_transaction lease-hybrid 2>&1)" || true ;;
    *) return 1 ;;
  esac
  printf '%s\n' "$renewed" | grep -qx LEASE_OK
}

start_root_helper_lease_guard() {
  [ -n "$ROOT_HELPER_TRANSACTION_KIND" ] || return 0
  local owner_pid="$$" interval="${ROOT_HELPER_LEASE_GUARD_INTERVAL_SECONDS:-30}"
  printf '%s\n' "$interval" | grep -Eq '^[0-9]+([.][0-9]+)?$' || interval=30
  ROOT_HELPER_LEASE_GUARD_FAILURE="$(mktemp)"
  (
    sleep_pid=""
    stop_lease_guard_sleep() {
      [ -z "$sleep_pid" ] || kill "$sleep_pid" >/dev/null 2>&1 || true
      [ -z "$sleep_pid" ] || wait "$sleep_pid" >/dev/null 2>&1 || true
      exit 0
    }
    trap 'stop_lease_guard_sleep' INT TERM
    while :; do
      /bin/sleep "$interval" &
      sleep_pid=$!
      [ -z "${ROOT_HELPER_LEASE_GUARD_SLEEP_PID_FILE:-}" ] || printf '%s\n' "$sleep_pid" > "$ROOT_HELPER_LEASE_GUARD_SLEEP_PID_FILE"
      wait "$sleep_pid" || exit 0
      sleep_pid=""
      kill -0 "$owner_pid" >/dev/null 2>&1 || exit 0
      renew_root_helper_lease "$ROOT_HELPER_TRANSACTION_KIND" || {
        printf 'failed\n' > "$ROOT_HELPER_LEASE_GUARD_FAILURE"
        exit 1
      }
    done
  ) &
  ROOT_HELPER_LEASE_GUARD_PID=$!
  [ -z "${ROOT_HELPER_LEASE_GUARD_PID_FILE:-}" ] || printf '%s\n' "$ROOT_HELPER_LEASE_GUARD_PID" > "$ROOT_HELPER_LEASE_GUARD_PID_FILE"
}

stop_root_helper_lease_guard() {
  if [ -n "${ROOT_HELPER_LEASE_GUARD_PID:-}" ]; then
    kill "$ROOT_HELPER_LEASE_GUARD_PID" >/dev/null 2>&1 || true
    wait "$ROOT_HELPER_LEASE_GUARD_PID" >/dev/null 2>&1 || true
    ROOT_HELPER_LEASE_GUARD_PID=""
  fi
}

lease_guard_succeeded() {
  [ -z "${ROOT_HELPER_LEASE_GUARD_FAILURE:-}" ] || [ ! -s "$ROOT_HELPER_LEASE_GUARD_FAILURE" ]
}

cleanup_root_helper_lease_guard() {
  stop_root_helper_lease_guard
  [ -z "${ROOT_HELPER_LEASE_GUARD_FAILURE:-}" ] || rm -f "$ROOT_HELPER_LEASE_GUARD_FAILURE"
  ROOT_HELPER_LEASE_GUARD_FAILURE=""
}

stop_root_helper_apk_install() {
  if [ -n "${ROOT_HELPER_APK_INSTALL_PID:-}" ]; then
    terminate_root_helper_apk_install
    ROOT_HELPER_APK_INSTALL_PID=""
  fi
  [ -z "${ROOT_HELPER_APK_INSTALL_OUTPUT_FILE:-}" ] || rm -f "$ROOT_HELPER_APK_INSTALL_OUTPUT_FILE"
  ROOT_HELPER_APK_INSTALL_OUTPUT_FILE=""
}

terminate_root_helper_apk_install() {
  local kill_deadline
  [ -n "${ROOT_HELPER_APK_INSTALL_PID:-}" ] || return 0
  kill -TERM "$ROOT_HELPER_APK_INSTALL_PID" >/dev/null 2>&1 || true
  kill_deadline=$((SECONDS + 2))
  while kill -0 "$ROOT_HELPER_APK_INSTALL_PID" 2>/dev/null && [ "$SECONDS" -lt "$kill_deadline" ]; do
    sleep 0.1
  done
  kill -KILL "$ROOT_HELPER_APK_INSTALL_PID" >/dev/null 2>&1 || true
  wait "$ROOT_HELPER_APK_INSTALL_PID" >/dev/null 2>&1 || true
}

run_adb_install_attempt() {
  local status deadline
  assert_candidate_apk_unchanged
  ROOT_HELPER_APK_INSTALL_OUTPUT_FILE="$(mktemp)"
  # The only adb execution that does not route through adb_exec, because this one is backgrounded and
  # `terminate_root_helper_apk_install` signals a single PID rather than a process group: a shell
  # function would put a subshell in `$!` and leave adb.exe orphaned on the deadline path. A variable
  # assignment prefix carries the identical MSYS policy into adb's environment without forking an
  # extra process, so `$!` is still adb itself. Keep this in step with adb_exec.
  MSYS2_ARG_CONV_EXCL="$ADB_MSYS_ARG_CONV_EXCL" \
    "$ADB_COMMAND" -s "$TARGET" install "$@" "$APK" > "$ROOT_HELPER_APK_INSTALL_OUTPUT_FILE" 2>&1 &
  ROOT_HELPER_APK_INSTALL_PID=$!
  # Android has now begun consuming the already-bound path. Compatibility-phase rollback ownership
  # covered the final assertion and this start boundary; later install outcomes use reconciliation.
  DB_GATE_PHASE=""
  deadline=$((SECONDS + APK_INSTALL_TIMEOUT_SECONDS))
  while kill -0 "$ROOT_HELPER_APK_INSTALL_PID" 2>/dev/null && [ "$SECONDS" -lt "$deadline" ]; do
    sleep 0.1
  done
  if kill -0 "$ROOT_HELPER_APK_INSTALL_PID" 2>/dev/null; then
    terminate_root_helper_apk_install
    status=124
  elif wait "$ROOT_HELPER_APK_INSTALL_PID"; then
    status=0
  else
    status=$?
  fi
  ROOT_HELPER_APK_INSTALL_PID=""
  ADB_INSTALL_OUTPUT="$(cat "$ROOT_HELPER_APK_INSTALL_OUTPUT_FILE")"
  if [ "$status" -eq 124 ]; then
    ADB_INSTALL_OUTPUT="${ADB_INSTALL_OUTPUT}${ADB_INSTALL_OUTPUT:+
}ADB_INSTALL_DEADLINE_EXCEEDED after ${APK_INSTALL_TIMEOUT_SECONDS}s"
  fi
  rm -f "$ROOT_HELPER_APK_INSTALL_OUTPUT_FILE"
  ROOT_HELPER_APK_INSTALL_OUTPUT_FILE=""
  return "$status"
}

# Issue #120: a failed /system helper replacement was reported twice carrying only a step name.
# The reason was already in the same capture both times, because cp prints its errno there, and this
# grep threw it away — leaving nothing to separate a read-only partition from an exhausted one from
# a differently-mounted /system/bin.
root_helper_install_marker() {
  printf '%s\n' "$1" | grep -E '^(RETIREMENT_TIMEOUT|INSTALL_STEP_FAILED|INSTALL_UNCHANGED) ' | head -1 || true
}

# Each of these three ends `|| true` because grep exits 1 when nothing matches, and under the
# script's `set -e` that would abort the failure path itself. No match is a normal answer here: a
# transaction that emitted no marker, no reason or no diagnostic simply has none to show, and the
# caller tests the resulting string rather than an exit status.
#
# The displayed detail is the first marker, which for a retirement timeout is the line carrying the
# surviving pids. The reason a transaction refused is a different question with a different answer,
# and it is only ever on the INSTALL_UNCHANGED line.
root_helper_unchanged_reason() {
  printf '%s\n' "$1" | grep -E '^INSTALL_UNCHANGED ' | head -1 || true
}

# The transaction emits fixed keys over paths it already names in clear, so these lines are safe to
# paste into a public issue. They are capped in both directions anyway: a panel that answers
# unexpectedly must not be able to flood the host's output through the failure path.
root_helper_install_diagnostics() {
  printf '%s\n' "$1" | grep -E '^INSTALL_DIAG ' | head -6 | cut -c1-300 | sanitize_terminal || true
}

# `INSTALL_UNCHANGED <verb> <reason>` means the transaction refused before its first mutation. Only
# one reason is a wedged helper; the rest are the panel declining to accept the replacement at all,
# and telling their owner to restart a stuck process would send them after the wrong thing.
root_helper_unchanged_advice() {
  case "$1" in
    *\ helper_retirement)
      printf '%s\n' "Re-run once that process can be stopped. Restarting the panel clears a wedged helper." ;;
    *\ target_read_only)
      printf '%s\n' "The partition holding that directory is mounted read-only, so no upgrade can write to it." \
        "Try 'adb -s $TARGET root && adb -s $TARGET remount' first; it needs no reboot." ;;
    *\ target_insufficient_space)
      printf '%s\n' "That partition does not have room for the replacement plus its recovery copy." \
        "The INSTALL_DIAG line above gives the free space the panel reported for the exact directory." ;;
    *\ target_insufficient_inodes)
      printf '%s\n' "That partition has run out of inodes, so it cannot create another file at any size." ;;
    *\ target_directory_missing)
      printf '%s\n' "That directory does not exist on this panel, so this install layout does not apply to it." ;;
    *\ target_not_writable)
      printf '%s\n' "The panel reported the partition as writable but then refused a real write to that directory." \
        "SELinux state and the directory's mode, owner and context are on the INSTALL_DIAG line above." ;;
    *\ staged_source_unavailable)
      printf '%s\n' "The staged helper bundle was no longer on the panel when the transaction read it." \
        "Re-run the installer; nothing was replaced." ;;
    *\ staged_source_unauthenticated)
      printf '%s\n' "The staged helper bundle did not match the signed checksum this run authenticated." \
        "Re-run the installer; nothing was replaced." ;;
    *)
      printf '%s\n' "Re-run the installer; nothing was replaced." ;;
  esac
}

run_root_helper_transaction() {
  local action="$1" transaction_id="${2:-$ROOT_HELPER_TRANSACTION_ID}" target_apk="${3:-$TARGET_APK_SHA256}"
  local target_build="${4:-$ROOT_HELPER_TARGET_BUILD_ID}" target_helper="${5:-$ROOT_HELPER_TARGET_SHA256}"
  case "$action" in
    install-system|install-systemless|install-hybrid|discover-system|discover-systemless|discover-hybrid|status-system|status-systemless|status-hybrid|lease-system|lease-systemless|lease-hybrid|rollback-system|rollback-systemless|rollback-hybrid|cancel-external-system|cancel-external-systemless|cancel-external-hybrid|finalize-rollback-system|finalize-rollback-systemless|finalize-rollback-hybrid|commit-system|commit-systemless|commit-hybrid) ;;
    *) return 1 ;;
  esac
  printf '%s\n' "$ROOT_HELPER_TRANSACTION_SHA256" | grep -Eq '^[0-9a-f]{64}$' || return 1
  printf '%s\n' "$ROOT_HELPER_TRANSACTION_ID" | grep -Eq '^[0-9a-f]{32}$' || return 1
  [ "$ROOT_HELPER_TRANSACTION_PATH" = "/data/adb/hapaneld/.helper-transaction-$ROOT_HELPER_TRANSACTION_ID-$ROOT_HELPER_TRANSACTION_SHA256" ] || return 1
  run_root 'txn='"$ROOT_HELPER_TRANSACTION_PATH"'; expected='"$ROOT_HELPER_TRANSACTION_SHA256"'; owner=$(stat -c %u:%g $txn 2>/dev/null || toybox stat -c %u:%g $txn 2>/dev/null) || exit 1; [ $owner = 0:0 ] || exit 1; actual=$(sha256sum $txn 2>/dev/null || toybox sha256sum $txn 2>/dev/null) || exit 1; [ ${actual%% *} = $expected ] || exit 1; sh $txn '"$action $transaction_id $target_apk $target_build $target_helper"
}

# Reclaim only this run's own staging. Issue #76: this ran solely after a successful commit, so
# every failure between staging and commit retained one protected transaction script plus one staged
# bundle, and the next run minted a fresh identity instead of replacing them.
#
# Deleting staging cannot strand a panel: every mutating verb finishes reading its @STAGED_*@ inputs
# before its first destructive step, so a missing input can only cause an early, clean failure —
# never a half-applied transaction. The recovery journal is deliberately NOT touched; it and the
# .hapaneld-recovery copies are the transaction's durable record, and a later reconcile reads them
# with its own freshly staged files, never with these.
#
# Every path is validated against the transaction identity rather than trusted from the globals, so
# an exit before staging cannot turn an unset variable into a destructive privileged `rm`. Best
# effort by design: the exit may be caused by a dead transport, anything left behind is removed by
# the next transaction's sweep on the panel, and `warn` is deliberately not used because it sets
# PROVISION_FAILED — a successful run must stay successful.
cleanup_root_helper_staging() {
  local id="$ROOT_HELPER_TRANSACTION_ID" paths="" candidate
  printf '%s\n' "$id" | grep -Eq '^[0-9a-f]{32}$' || return 0
  for candidate in "$ROOT_HELPER_STAGED_HELPER" "$ROOT_HELPER_STAGED_RC" "$ROOT_HELPER_STAGED_HYBRID_RC" \
      "$ROOT_HELPER_STAGED_SERVICE" "$ROOT_HELPER_STAGED_TRANSACTION" "$ROOT_HELPER_TRANSACTION_PATH"; do
    case "$candidate" in
      "/data/local/tmp/hapaneld-helper-$id"|"/data/local/tmp/hapaneld-helper-$id."*) ;;
      "/data/local/.hapaneld-helper.provision-$id") ;;
      "/data/adb/hapaneld/.helper-transaction-$id-"*) ;;
      *) continue ;;
    esac
    paths="$paths $candidate"
  done
  [ -n "$paths" ] || return 0
  paths="$paths /data/adb/hapaneld/.helper-probe-$id /data/local/.hapaneld-helper.provision-$id"
  # A promotion interrupted between the copy and the rename leaves `<transaction>.new` behind and
  # nothing else names it — but append it only when the transaction path is the one this identity
  # owns: unguarded, an exit before that path is set would put a relative `.new` into a privileged
  # `rm -f`.
  case "$ROOT_HELPER_TRANSACTION_PATH" in
    "/data/adb/hapaneld/.helper-transaction-$id-"*) paths="$paths $ROOT_HELPER_TRANSACTION_PATH.new" ;;
  esac
  if ! run_root 'rm -f'"$paths" >/dev/null 2>&1; then
    echo "   ${YEL}note${X} could not reclaim this run's root-helper staging on the panel;" >&2
    echo "   the next provisioning transaction against this panel removes it automatically" >&2
  fi
}

root_helper_transaction_record() {
  case "$1" in
    system) run_root_helper_transaction discover-system 2>/dev/null ;;
    systemless) run_root_helper_transaction discover-systemless 2>/dev/null ;;
    hybrid) run_root_helper_transaction discover-hybrid 2>/dev/null ;;
    *) return 1 ;;
  esac
}

# Return 0 only when the exact APK bytes are installed, 1 for a definitive different/missing package,
# and 2 when adb transport or pulling the installed base APK is inconclusive.
#
# The "missing package" verdict used to be unreachable on precisely the panels where the package is
# missing: this read `pm path`'s exit status directly, so absence — which Android reports as a failed
# query — became "inconclusive". That is the same conflation this file's classifier exists to remove,
# and it matters most here. This function decides whether a FAILED APK install rolls the root-helper
# transaction back. Called on a first installation it answered "inconclusive", so the run refused to
# roll back and every later run refused to reconcile the retained journal, stranding the panel with a
# live helper and no app. Absence must reach the definitive `1` for that rollback to happen.
installed_apk_matches_hash() {
  local expected="$1" path_output path dir pulled actual
  classify_package_presence "$ADB_COMMAND_TIMEOUT_SECONDS"
  case "$PACKAGE_PRESENCE" in
    absent) return 1 ;;
    present) ;;
    *) return 2 ;;
  esac
  path_output="$(adb -s "$TARGET" shell pm path "$PKG" 2>/dev/null)" || return 2
  path="$(printf '%s\n' "$path_output" | tr -d '\r' | sed -n 's/^package://p' | head -1)"
  [ -n "$path" ] || return 1
  dir="$(mktemp -d)" || return 2
  pulled="$dir/installed.apk"
  if ! adb -s "$TARGET" pull "$path" "$pulled" >/dev/null 2>&1 || [ ! -s "$pulled" ]; then
    rm -rf "$dir"
    return 2
  fi
  actual="$(host_sha256 "$pulled" 2>/dev/null || true)"
  rm -rf "$dir"
  [ "$actual" = "$expected" ]
}

reconcile_stale_root_helper() {
  local install_kind="$1" record authenticated journal_version journal_scope transaction_id target_apk target_build target_helper live_state outcome helper_path="" resume_state latest
  record="$(root_helper_transaction_record "$install_kind" || true)"
  journal_version="$(printf '%s\n' "$record" | sed -n 's/^JOURNAL_VERSION=//p')"
  journal_scope="$(printf '%s\n' "$record" | sed -n 's/^JOURNAL_SCOPE=//p')"
  transaction_id="$(printf '%s\n' "$record" | sed -nE 's/^TRANSACTION_ID=([0-9a-f]{32})$/\1/p')"
  target_apk="$(printf '%s\n' "$record" | sed -nE 's/^TARGET_APK_SHA256=([0-9a-f]{64})$/\1/p')"
  target_build="$(printf '%s\n' "$record" | sed -nE 's/^TARGET_BUILD_ID=([0-9a-f]{64})$/\1/p')"
  target_helper="$(printf '%s\n' "$record" | sed -nE 's/^TARGET_HELPER_SHA256=([0-9a-f]{64})$/\1/p')"
  live_state="$(printf '%s\n' "$record" | sed -n 's/^LIVE_STATE=//p')"
  case "$journal_version" in 1|2) ;; *) return 2 ;; esac
  if [ "$journal_scope" != APK_HELPER ] || \
     ! printf '%s\n' "$transaction_id" | grep -Eq '^[0-9a-f]{32}$' || \
     ! printf '%s\n' "$target_apk" | grep -Eq '^[0-9a-f]{64}$' || \
     ! printf '%s\n' "$target_build" | grep -Eq '^[0-9a-f]{64}$' || \
     ! printf '%s\n' "$target_helper" | grep -Eq '^[0-9a-f]{64}$'; then
    return 2
  fi
  case "$install_kind" in
    system) authenticated="$(run_root_helper_transaction status-system "$transaction_id" "$target_apk" "$target_build" "$target_helper" 2>/dev/null || true)" ;;
    systemless) authenticated="$(run_root_helper_transaction status-systemless "$transaction_id" "$target_apk" "$target_build" "$target_helper" 2>/dev/null || true)" ;;
    hybrid) authenticated="$(run_root_helper_transaction status-hybrid "$transaction_id" "$target_apk" "$target_build" "$target_helper" 2>/dev/null || true)" ;;
  esac
  [ "$authenticated" = "$record" ] || return 2
  if [ "$journal_version" = 2 ]; then
    case "$live_state" in
      EXTERNAL_CANONICAL_CHANGE|CANCEL_EXTERNAL)
        cancel_root_helper_external_change "$install_kind" "$transaction_id" "$target_apk" "$target_build" "$target_helper" || return 2
        return 0 ;;
    esac
  fi
  if installed_apk_matches_hash "$target_apk"; then
    if [ "$journal_version" = 1 ]; then
      case "$install_kind" in
        system) helper_path=/system/bin/hapaneld-helper ;;
        systemless|hybrid) helper_path=/data/adb/hapaneld/hapaneld-helper ;;
      esac
    else
      [ "$live_state" = TARGET ] || return 2
      resume_state="$(run_root '
        if pidof hapaneld-helper >/dev/null 2>&1 || pidof hapaneld-ledd >/dev/null 2>&1; then
          echo HELPER_PROCESSES_PRESENT
        else
          echo NO_HELPER_PROCESSES
        fi
      ' 2>/dev/null || true)"
      case "$resume_state" in
        NO_HELPER_PROCESSES)
          case "$install_kind" in
            system) latest="$(run_root_helper_transaction status-system "$transaction_id" "$target_apk" "$target_build" "$target_helper" 2>/dev/null || true)" ;;
            systemless) latest="$(run_root_helper_transaction status-systemless "$transaction_id" "$target_apk" "$target_build" "$target_helper" 2>/dev/null || true)" ;;
            hybrid) latest="$(run_root_helper_transaction status-hybrid "$transaction_id" "$target_apk" "$target_build" "$target_helper" 2>/dev/null || true)" ;;
          esac
          [ "$latest" = "$record" ] || return 2
          run_root '/data/local/hapaneld-helper --supervise >/dev/null 2>&1 &' >/dev/null 2>&1 || return 2 ;;
        HELPER_PROCESSES_PRESENT) ;;
        *) return 2 ;;
      esac
    fi
    wait_for_helper_reply PING OK "$install_kind" "$helper_path" || return 2
    wait_for_helper_reply COMPANIONCAPS "COMPANIONCAPS 1 BACKUP RESTORE STATUS JOURNAL" "$install_kind" "$helper_path" || return 2
    wait_for_helper_reply BUILDID "BUILDID $target_build" "$install_kind" "$helper_path" || return 2
    if [ "$journal_version" = 2 ]; then
      wait_for_helper_reply GUARDCAPS "OK GUARDCAPS 1 PREPARE DEFINE STREAM ACTION HEALTH REFUSAL STATUS EVIDENCE CANCEL RETIRE JOURNAL AUTONOMOUS SUPERVISED TERMINAL_RETIRE" "$install_kind" || return 2
      wait_for_helper_reply GUARDSTATUS "OK GUARDSTATUS 0 EMPTY NONE NONE NONE NONE 0 0 0 NONE NONE 0 0" "$install_kind" || return 2
    fi
    commit_root_helper_upgrade "$install_kind" "$transaction_id" "$target_apk" "$target_build" "$target_helper" || return 2
    return 0
  else
    outcome=$?
  fi
  [ "$outcome" -eq 1 ] || return 2
  case "$live_state" in PRE_SWAP|CANONICAL_SWAPPED|BOOT_SWITCHED|TARGET|TRANSITION) ;; *) return 2 ;; esac
  rollback_root_helper "$install_kind" "$transaction_id" "$target_apk" "$target_build" "$target_helper"
}

resolve_root_helper_install_state() {
  local selected_kind="$1" stale_kind="" external_retry=0
  case "$out2" in
    STALE_SYSTEM_TRANSACTION) stale_kind=system ;;
    STALE_SYSTEMLESS_TRANSACTION) stale_kind=systemless ;;
    STALE_HYBRID_TRANSACTION) stale_kind=hybrid ;;
    MULTIPLE_STALE_TRANSACTIONS)
      fail "more than one root-helper recovery journal is present" \
        "No rollback was attempted because the authoritative prior install location is ambiguous." \
        "Inspect and recover the retained /system, systemless, and hybrid journals before provisioning again." ;;
    FOREIGN_MANUAL_TRANSACTION)
      fail "an incomplete standalone root-helper installation must be recovered first" \
        "Re-run ./helper/install-daemon.sh $TARGET from the same checkout; it owns the separate helper-only journal." \
        "Then re-run this provisioning command. No APK or helper files were changed by this attempt." ;;
    LEGACY_TAKEOVER_HOLD)
      fail "a retained app-managed root-helper takeover could not be normalized safely" \
        "The exact takeover record and helper bytes were preserved. Let the app finish recovery, then re-run provisioning." ;;
    APP_REPLACEMENT_HOLD)
      fail "app-managed root-helper replacement custody blocks provisioning" \
        "The fixed custody paths were preserved, and no helper topology, recovery journal, or APK was changed. Let the app finish or reconcile its replacement, then re-run provisioning." ;;
    TRANSACTION_BUSY)
      fail "another root-helper transaction is active on the panel" \
        "Wait for the other installer or provisioner to finish, then re-run this command." ;;
    ACTIVE_SYSTEM_TRANSACTION|ACTIVE_SYSTEMLESS_TRANSACTION|ACTIVE_HYBRID_TRANSACTION)
      fail "another provisioner still owns the active root-helper transaction" \
        "Wait up to ten minutes for the owning run to finish or its recovery lease to expire, then re-run." ;;
  esac
  if [ -n "$stale_kind" ]; then
    reconcile_stale_root_helper "$stale_kind" || fail "an incomplete prior root-helper and APK upgrade could not be reconciled safely" \
      "No rollback was attempted because the installed APK and running helper identity could not both be established." \
      "Restore adb connectivity, then re-run this command to reconcile the retained $stale_kind recovery journal."
    out2="$(run_root_helper_transaction "install-$selected_kind" 2>&1)" || true
  fi
  while printf '%s\n' "$out2" | grep -qx EXTERNAL_CANONICAL_RETRY && [ "$external_retry" -lt 3 ]; do
    external_retry=$((external_retry + 1))
    out2="$(run_root_helper_transaction "install-$selected_kind" 2>&1)" || true
  done
  case "$out2" in
    EXTERNAL_CANONICAL_RETRY|STALE_SYSTEM_TRANSACTION|STALE_SYSTEMLESS_TRANSACTION|STALE_HYBRID_TRANSACTION|MULTIPLE_STALE_TRANSACTIONS|FOREIGN_MANUAL_TRANSACTION|LEGACY_TAKEOVER_HOLD|APP_REPLACEMENT_HOLD|TRANSACTION_BUSY|ACTIVE_SYSTEM_TRANSACTION|ACTIVE_SYSTEMLESS_TRANSACTION|ACTIVE_HYBRID_TRANSACTION)
      fail "root-helper journal state changed while provisioning" \
        "No helper files were replaced by the conflicting transaction attempt." \
        "Wait for any other installer to finish, then re-run this command to reconcile the retained journal." ;;
  esac
}

install_root_helper() {
  local abi helper_dir="" helper="" helper_name="" helper_asset="" rc_file="" hybrid_rc_file="" service_file="" transaction_file=""
  local legacy_rc_file="" legacy_rc_supervised_file="" legacy_hybrid_rc_file="" legacy_hybrid_rc_supervised_file="" legacy_service_file="" legacy_service_supervised_file=""
  local bin_sha256 rc_sha256 hybrid_rc_sha256 service_sha256 transaction_sha256 transaction_ready="" expected_build_id="" staged_build_id="" out out2 root_ready=0 install_kind=""
  local legacy_rc_sha256 legacy_rc_supervised_sha256 legacy_hybrid_rc_sha256 legacy_hybrid_rc_supervised_sha256 legacy_service_sha256 legacy_service_supervised_sha256
  local system_avail_kb="" system_need_kb="" vendor_probe=""
  local access_timeout="${PRIVILEGE_INSPECTION_TIMEOUT_SECONDS:-45}" access_status

  case "$access_timeout" in ''|*[!0-9]*|0) access_timeout=45 ;; esac
  PRIVILEGE_INSPECTION_TIMEOUT_SECONDS="$access_timeout"
  step "🔎 inspecting access" "${D}panel ABI · root route · helper compatibility${X}"
  if abi="$(run_with_deadline "$access_timeout" adb_exec -s "$TARGET" shell getprop ro.product.cpu.abi 2>/dev/null)"; then
    abi="$(printf '%s' "$abi" | tr -d '\r')"
  else
    access_status=$?
    if [ "$access_status" -eq 124 ] || [ "$access_status" -eq 137 ]; then
      fail "panel-architecture inspection timed out after ${access_timeout}s" \
        "adb did not answer before any helper or APK change. Restore adb responsiveness, then re-run."
    fi
    fail "could not inspect the panel architecture" \
      "adb did not return the panel ABI. Nothing was installed; restore adb responsiveness, then re-run."
  fi

  # The run's one root-route verdict, resolved before the capture gate, governs every release
  # flavour below. An undecided route cannot reach this phase — the main-flow mutation gate stops
  # it before pm clear — but this phase must still never silently treat an undecided route as
  # rootless, so the unknown arm fails closed as defense in depth.
  case "$ROOT_ROUTE_VERDICT" in
    rooted) root_ready=1 ;;
    unrooted) ;;
    *)
      fail "the panel's root route could not be determined before the helper phase" \
        "Its root probe or adb-root escalation did not produce an answer, so the panel's capability is unknown." \
        "Nothing was installed. Restore adb responsiveness on $TARGET, dismiss any on-panel privilege prompt, then re-run." ;;
  esac
  case "$abi" in
    armeabi-v7a|arm64-v8a) ;;
    *)
      if [ "$root_ready" = 0 ]; then
        echo "   ${YEL}ℹ${X} no root path available — continuing without the root helper"
        return 0
      fi
      fail "this rooted panel reports unsupported ABI '${abi:-unknown}'" \
        "Official helper assets are available for armeabi-v7a and arm64-v8a. Nothing was installed." ;;
  esac
  if [ -n "$APK_RELEASE_TAG" ] && release_has_helper_assets "$APK_RELEASE_TAG"; then
    helper_dir="$(mktemp -d)" || fail "could not create a temporary directory for the root helper" \
      "Free some disk space, then re-run; no panel changes were made."
    helper_name="$(release_helper_name "$APK_RELEASE_TAG" "$abi")"
    helper="$helper_dir/$helper_name"
    step "⬇️  root helper" "${D}downloading $helper_name${X}"
    if ! curl -fsSL --proto '=https' --proto-redir '=https' --connect-timeout 15 --max-time 300 \
        "$(release_helper_url "$APK_RELEASE_TAG" "$abi")" -o "$helper"; then
      rm -rf "$helper_dir"
      fail "could not download the $APK_RELEASE_TAG root helper for $abi" \
        "The release may be incomplete or GitHub may be unavailable. No panel changes were made."
    fi
    verify_release_helper "$helper" "$APK_RELEASE_TAG" "$abi"
    if ! staged_build_id="$(extract_helper_build_id "$helper")"; then
      rm -rf "$helper_dir"
      fail "the $APK_RELEASE_TAG root helper for $abi does not state a single valid build identity" \
        "The authenticated asset carries no usable build-identity record, so it cannot be matched against the release's own metadata." \
        "Nothing was installed, started, or privileged. Report this release as incompletely packaged."
    fi
  elif [ -n "$APK_RELEASE_TAG" ]; then
    # Releases before the helper became a sealed release asset retain their historical direct-su
    # behaviour. Current releases always enter the authenticated branch above.
    if [ "$root_ready" = 1 ]; then
      # This flavour performs no helper transaction that would exercise the root route before the
      # APK replace, so the cached rooted verdict is confirmed live here — by proving the
      # PRIVILEGED property (uid=0), not an echo: for the adbd-root form a plain echo answers from
      # any adbd, rooted or not. A proven route that no longer answers as root fails closed
      # WITHOUT reclassification: the one decision stands; the panel is refusing to honour it.
      local root_live
      root_live="$(run_root "id" 2>/dev/null | tr -d '\r')" || root_live=""
      case "$root_live" in
        *uid=0*) : ;;
        *)
          fail "the panel's proven root route stopped answering before the legacy helper flow" \
            "The route the run proved earlier no longer answers as root, so the panel's live state is unknown." \
            "Nothing was installed. Re-run once adb and any on-panel root prompt are responsive again." ;;
      esac
    fi
    echo "   ${YEL}ℹ${X} $APK_RELEASE_TAG predates automatic root-helper assets"
    return 0
  else
    if [ "$root_ready" = 0 ]; then
      echo "   ${YEL}ℹ${X} no root path available — continuing without the root helper"
      return 0
    fi
    if [ "$ALLOW_UNSIGNED_HELPER" != 1 ]; then
      fail "the local APK is not an authenticated release, so its embedded root helper was not installed" \
        "If you built and trust this APK, re-run with --allow-unsigned-helper." \
        "The APK and existing helper were left unchanged. Official --latest/--prerelease installs do not need this flag."
    fi
    helper_dir="$(mktemp -d)" || fail "could not create a temporary directory for the root helper" \
      "Free some disk space, then re-run; no panel changes were made."
    case "$abi" in
      armeabi-v7a) helper_asset="assets/hapaneld-helper-arm" ;;
      arm64-v8a) helper_asset="assets/hapaneld-helper-arm64" ;;
    esac
    step "🧰 root helper" "${D}inspecting the $abi helper bundled in the local APK${X}"
    helper="$helper_dir/hapaneld-helper"
    if ! command -v unzip >/dev/null 2>&1; then
      rm -rf "$helper_dir"
      fail "unzip is required to read the root helper embedded in a local APK" \
        "Install unzip, then re-run this provisioning command." \
        "No helper or APK was replaced, so the panel remains on its previous working version."
    fi
    assert_candidate_apk_unchanged
    if ! unzip -p "$APK" "$helper_asset" > "$helper" 2>/dev/null || [ ! -s "$helper" ]; then
      rm -rf "$helper_dir"
      fail "the local APK does not contain its $abi root helper" \
        "Build the complete APK again, then re-run this provisioning command." \
        "No helper or APK was replaced, so the panel remains on its previous working version."
    fi
    assert_candidate_apk_unchanged
    if ! staged_build_id="$(extract_helper_build_id "$helper")"; then
      rm -rf "$helper_dir"
      fail "the root helper embedded in the local APK has an invalid build identity" \
        "Build the complete APK again, then re-run. No helper or APK was replaced."
    fi
  fi
  if [ "$root_ready" = 0 ]; then
    [ -z "$helper_dir" ] || rm -rf "$helper_dir"
    echo "   ${YEL}ℹ${X} no root path available — continuing without the root helper"
    return 0
  fi
  HELPER_REQUIRED=1
  # What the post-swap probe has to establish is that the daemon now answering the socket is the exact
  # helper this run staged, so a local APK is measured against the identity stamped into that staged
  # binary. Asking `helper/source-id.sh` instead describes whichever helper sources happen to sit
  # beside the running script, which is a different helper whenever the installer and the artifact
  # come from different trees — an older clone installing a downloaded APK, or a pinned provisioner
  # installing a newer one. The two identities then disagree for a correctly installed helper, and the
  # transaction rolls a good install back and reports it as a build-identity failure.
  #
  # A release keeps its existing rule instead: the signed versioned provisioner is the authority on
  # which helper that release ships, and a release that cannot state it still fails closed rather than
  # being trusted from the bytes it just served.
  expected_build_id="$RELEASE_HELPER_BUILD_ID"
  if [ -z "$expected_build_id" ] && [ -n "$APK_RELEASE_TAG" ]; then
    step "🔎 helper compatibility" "${D}checking the authenticated provisioner's build identity${X}"
    expected_build_id="$(fetch_release_helper_build_id "$APK_RELEASE_TAG" || true)"
  elif [ -z "$expected_build_id" ]; then
    expected_build_id="$staged_build_id"
  fi
  if ! printf '%s\n' "$expected_build_id" | grep -Eq '^[0-9a-f]{64}$'; then
    if [ -n "$APK_RELEASE_TAG" ]; then
      fail "the expected root-helper build identity is unavailable" \
        "The signed versioned provisioner did not provide a valid helper identity. No APK or helper was replaced."
    fi
    fail "the expected root-helper build identity is unavailable" \
      "Rebuild the APK from a complete checkout, then retry. No APK or helper was replaced."
  fi
  # Two independent authorities now describe the same release: the signed versioned provisioner says
  # which helper the release ships, and the authenticated helper asset says which helper it actually
  # is. Each is verified on its own, so a disagreement is not a damaged download — it is a release
  # whose metadata and payload were built from different trees, and installing either one would make
  # the panel's helper something other than what the release claims. Refuse before anything is staged
  # or replaced; the local-APK path has no second authority to consult and keeps its single one.
  if [ -n "$APK_RELEASE_TAG" ] && [ "$staged_build_id" != "$expected_build_id" ]; then
    [ -z "$helper_dir" ] || rm -rf "$helper_dir"
    fail "the $APK_RELEASE_TAG root helper does not match the identity its signed provisioner records" \
      "Release metadata expects ${expected_build_id:0:12}…, but the authenticated helper asset is ${staged_build_id:0:12}…." \
      "Nothing was installed, started, or privileged. Report this release as internally inconsistent, or install a release whose assets agree."
  fi
  ROOT_HELPER_TRANSACTION_ID="$(host_transaction_id)" || fail "could not create a unique root-helper transaction identity" \
    "No helper or APK files were changed. Check host access to /dev/urandom, then retry."
  ROOT_HELPER_STAGED_HELPER="/data/local/tmp/hapaneld-helper-$ROOT_HELPER_TRANSACTION_ID"
  ROOT_HELPER_STAGED_RC="$ROOT_HELPER_STAGED_HELPER.rc"
  ROOT_HELPER_STAGED_HYBRID_RC="$ROOT_HELPER_STAGED_HELPER.hrc"
  ROOT_HELPER_STAGED_SERVICE="$ROOT_HELPER_STAGED_HELPER.svc"
  ROOT_HELPER_STAGED_TRANSACTION="$ROOT_HELPER_STAGED_HELPER.txn"
  ROOT_HELPER_TARGET_BUILD_ID="$expected_build_id"

  rc_file="$(mktemp)"
  hybrid_rc_file="$(mktemp)"
  service_file="$(mktemp)"
  legacy_rc_file="$(mktemp)"
  legacy_rc_supervised_file="$(mktemp)"
  legacy_hybrid_rc_file="$(mktemp)"
  legacy_hybrid_rc_supervised_file="$(mktemp)"
  legacy_service_file="$(mktemp)"
  legacy_service_supervised_file="$(mktemp)"
  cat > "$rc_file" <<'EOF'
service hapaneld_helper /data/local/hapaneld-helper --supervise
    class main
    user root
    group root
    seclabel u:r:su:s0
EOF
  cat > "$hybrid_rc_file" <<'EOF'
service hapaneld_helper /data/local/hapaneld-helper --supervise
    class main
    user root
    group root
    seclabel u:r:su:s0
EOF
  cat > "$service_file" <<'EOF'
#!/system/bin/sh
while [ "$(getprop sys.boot_completed)" != "1" ]; do sleep 3; done
/system/bin/stop hapaneld_helper 2>/dev/null
/system/bin/stop hapaneld_ledd 2>/dev/null
/system/bin/pkill -x hapaneld-helper 2>/dev/null
/system/bin/pkill -x hapaneld-ledd 2>/dev/null
/data/local/hapaneld-helper --supervise >/dev/null 2>&1 &
EOF
  cat > "$legacy_rc_file" <<'EOF'
service hapaneld_helper /system/bin/hapaneld-helper
    class main
    user root
    group root
    seclabel u:r:su:s0
EOF
  cat > "$legacy_rc_supervised_file" <<'EOF'
service hapaneld_helper /system/bin/hapaneld-helper --supervise
    class main
    user root
    group root
    seclabel u:r:su:s0
EOF
  cat > "$legacy_hybrid_rc_file" <<'EOF'
service hapaneld_helper /data/adb/hapaneld/hapaneld-helper
    class main
    user root
    group root
    seclabel u:r:su:s0
EOF
  cat > "$legacy_hybrid_rc_supervised_file" <<'EOF'
service hapaneld_helper /data/adb/hapaneld/hapaneld-helper --supervise
    class main
    user root
    group root
    seclabel u:r:su:s0
EOF
  cat > "$legacy_service_file" <<'EOF'
#!/system/bin/sh
while [ "$(getprop sys.boot_completed)" != "1" ]; do sleep 3; done
/system/bin/stop hapaneld_helper 2>/dev/null
/system/bin/stop hapaneld_ledd 2>/dev/null
/system/bin/pkill -x hapaneld-helper 2>/dev/null
/system/bin/pkill -x hapaneld-ledd 2>/dev/null
/data/adb/hapaneld/hapaneld-helper >/dev/null 2>&1 &
EOF
  cat > "$legacy_service_supervised_file" <<'EOF'
#!/system/bin/sh
while [ "$(getprop sys.boot_completed)" != "1" ]; do sleep 3; done
/system/bin/stop hapaneld_helper 2>/dev/null
/system/bin/stop hapaneld_ledd 2>/dev/null
/system/bin/pkill -x hapaneld-helper 2>/dev/null
/system/bin/pkill -x hapaneld-ledd 2>/dev/null
/data/adb/hapaneld/hapaneld-helper --supervise >/dev/null 2>&1 &
EOF
  legacy_rc_sha256="$(host_sha256 "$legacy_rc_file")"
  legacy_rc_supervised_sha256="$(host_sha256 "$legacy_rc_supervised_file")"
  legacy_hybrid_rc_sha256="$(host_sha256 "$legacy_hybrid_rc_file")"
  legacy_hybrid_rc_supervised_sha256="$(host_sha256 "$legacy_hybrid_rc_supervised_file")"
  legacy_service_sha256="$(host_sha256 "$legacy_service_file")"
  legacy_service_supervised_sha256="$(host_sha256 "$legacy_service_supervised_file")"
  rm -f "$legacy_rc_file" "$legacy_rc_supervised_file" \
    "$legacy_hybrid_rc_file" "$legacy_hybrid_rc_supervised_file" \
    "$legacy_service_file" "$legacy_service_supervised_file"
  transaction_file="$(mktemp)"
  cat > "$transaction_file" <<'EOF'
#!/system/bin/sh
set -u

hash_matches() {
  [ "$(file_sha256 "$2")" = "$1" ]
}

file_sha256() {
  actual=$(sha256sum "$1" 2>/dev/null || toybox sha256sum "$1" 2>/dev/null) || return 1
  printf '%s\n' "${actual%% *}"
}

root_owned() {
  owner=$(stat -c %u:%g "$1" 2>/dev/null || toybox stat -c %u:%g "$1" 2>/dev/null) || return 1
  [ "$owner" = 0:0 ]
}

boot_id() {
  cat /proc/sys/kernel/random/boot_id 2>/dev/null
}

uptime_seconds() {
  value=$(cat /proc/uptime 2>/dev/null) || return 1
  value=${value%%.*}
  case "$value" in ''|*[!0-9]*) return 1 ;; esac
  printf '%s\n' "$value"
}

lease_active() {
  marker=$1
  recorded_boot=$(sed -n 's/^LEASE_BOOT_ID=//p' "$marker")
  lease_until=$(sed -n 's/^LEASE_UNTIL_UPTIME=//p' "$marker")
  current_boot=$(boot_id) || return 1
  now=$(uptime_seconds) || return 1
  case "$lease_until" in ''|*[!0-9]*) return 1 ;; esac
  [ -n "$recorded_boot" ] && [ "$recorded_boot" = "$current_boot" ] && [ "$now" -lt "$lease_until" ]
}

valid_transaction_identity() {
  marker=$1 transaction_id=$2 target_apk=$3 target_build=$4 target_helper=$5
  grep -qx "TRANSACTION_ID=$transaction_id" "$marker" &&
    grep -qx "TARGET_APK_SHA256=$target_apk" "$marker" &&
    grep -qx "TARGET_BUILD_ID=$target_build" "$marker" &&
    grep -qx "TARGET_HELPER_SHA256=$target_helper" "$marker"
}

refresh_lease() {
  marker=$1
  current_boot=$(boot_id) || return 1
  now=$(uptime_seconds) || return 1
  lease_until=$((now + 600))
  sed '/^LEASE_BOOT_ID=/d; /^LEASE_UNTIL_UPTIME=/d' "$marker" > "$marker.lease-new" || return 1
  echo LEASE_BOOT_ID=$current_boot >> "$marker.lease-new"
  echo LEASE_UNTIL_UPTIME=$lease_until >> "$marker.lease-new"
  chown 0:0 "$marker.lease-new" || return 1
  chmod 600 "$marker.lease-new" || return 1
  sync || return 1
  mv -f "$marker.lease-new" "$marker" || return 1
  sync || return 1
}

snapshot() {
  live=$1 recovery=$2 mode=$3
  rm -f "$recovery" || return 1
  if [ ! -e "$live" ] && [ ! -L "$live" ]; then
    echo "0 -"
    return 0
  fi
  [ -f "$live" ] && [ ! -L "$live" ] || return 1
  cp -p "$live" "$recovery" || return 1
  chown 0:0 "$recovery" || return 1
  chmod "$mode" "$recovery" || return 1
  cmp -s "$live" "$recovery" || return 1
  root_owned "$recovery" || return 1
  recovery_sha=$(file_sha256 "$recovery") || return 1
  echo "1 $recovery_sha"
}

# Issue #120: a /system helper replacement that failed at its very first copy reported one step name
# and threw the reason away. Two reports, a week apart, both said only
# `INSTALL_STEP_FAILED install_system cp_hapaneld-helper_new`, which cannot distinguish a read-only
# partition from an exhausted one, from exhausted inodes, from a `/system/bin` that is mounted
# differently to `/system`, from an SELinux refusal, from staging that is no longer there. cp's own
# errno text was already reaching the host inside the same capture and being filtered out by the
# marker grep, so the answer was in the output both times and the installer discarded it.
#
# Everything these functions emit is fixed-vocabulary or numeric, over paths this transaction
# already names in clear. No configuration, credential, entity name, panel identity or host path
# passes through them, so an INSTALL_DIAG line is safe to paste into a public issue. Every field
# degrades to `unknown` instead of failing: a diagnostic that can itself fail would be a second
# failure mode on the recovery path.
diag_reset() {
  diag_mount=unknown
  diag_state=unknown
  diag_availkb=unknown
  diag_inodesfree=unknown
  diag_mode=unknown
  diag_owner=unknown
  diag_selinux=unknown
  diag_context=unknown
}

# `df -P -k <dir>` resolves the filesystem that actually holds <dir>, which is the whole point: the
# route probe tested `/system` and the transaction writes to `/system/bin`, and nothing in the
# installer has ever checked that those two are the same mount.
describe_target() {
  describe_dir=$1
  diag_reset
  # A missing directory is reported as every field `unknown`, not as a failure: this function only
  # describes, and preflight_target is what refuses on it. Returning non-zero here would make the
  # reporter and the gate two places that can decide the same thing.
  [ -d "$describe_dir" ] || return 0

  describe_row=$(df -P -k "$describe_dir" 2>/dev/null | sed -n 2p) || describe_row=
  if [ -n "$describe_row" ]; then
    set -f
    set -- $describe_row
    set +f
    if [ "$#" -eq 6 ]; then
      case "$4" in ''|*[!0-9]*) ;; *) diag_availkb=$4 ;; esac
      diag_mount=$6
    fi
  fi

  # The LAST /proc/mounts row for a mount point is the effective one, so an overmount that made this
  # directory read-only under a writable parent is reported as read-only rather than missed.
  if [ "$diag_mount" != unknown ]; then
    while read -r mount_source mount_point mount_type mount_options mount_rest; do
      [ "$mount_point" = "$diag_mount" ] || continue
      case ",$mount_options," in
        *,rw,*) diag_state=rw ;;
        *,ro,*) diag_state=ro ;;
        *) diag_state=unknown ;;
      esac
    done < /proc/mounts 2>/dev/null
  fi

  # Android 8.1's toybox df has no -i, so free inodes are best effort through whichever tool can
  # answer. An unknown here is a gap in the report, never a reason to refuse an install.
  #
  # A filesystem that does not track inodes at all — overlayfs, which is how a panel with a scratch
  # overlay presents /system, and several tmpfs configurations — answers zero TOTAL and zero free.
  # Reading that free count on its own says "no inodes left" about a filesystem that cannot run out,
  # so the total is what decides whether the free count means anything.
  describe_inode_row=$(df -P -i "$describe_dir" 2>/dev/null | sed -n 2p) || describe_inode_row=
  if [ -z "$describe_inode_row" ]; then
    describe_inode_row=$(busybox df -P -i "$describe_dir" 2>/dev/null | sed -n 2p) || describe_inode_row=
  fi
  if [ -n "$describe_inode_row" ]; then
    set -f
    set -- $describe_inode_row
    set +f
    if [ "$#" -eq 6 ]; then
      case "$2" in
        ''|*[!0-9]*|0) ;;
        *) case "$4" in ''|*[!0-9]*) ;; *) diag_inodesfree=$4 ;; esac ;;
      esac
    fi
  fi

  describe_value=$(stat -c %a "$describe_dir" 2>/dev/null || toybox stat -c %a "$describe_dir" 2>/dev/null) || describe_value=
  [ -z "$describe_value" ] || diag_mode=$describe_value
  describe_value=$(stat -c %u:%g "$describe_dir" 2>/dev/null || toybox stat -c %u:%g "$describe_dir" 2>/dev/null) || describe_value=
  [ -z "$describe_value" ] || diag_owner=$describe_value
  describe_value=$(getenforce 2>/dev/null) || describe_value=
  [ -z "$describe_value" ] || diag_selinux=$describe_value
  describe_value=$(ls -Zd "$describe_dir" 2>/dev/null | sed -n 's/^\([^ ][^ ]*\)[ ].*$/\1/p') || describe_value=
  [ -z "$describe_value" ] || diag_context=$describe_value
  return 0
}

emit_target_diag() {
  describe_target "$3"
  echo "INSTALL_DIAG $1 $2 dir=$3 mount=$diag_mount state=$diag_state availkb=$diag_availkb inodesfree=$diag_inodesfree mode=$diag_mode owner=$diag_owner selinux=$diag_selinux context=$diag_context"
}

# The staged bundle is pushed by one adb invocation and read by a later one, with the disposable
# staging sweep running in between, so "the file this transaction was told to copy is still there
# and still authentic" is a distinct question from "the destination accepted a write".
emit_source_diag() {
  source_state=missing
  source_bytes=unknown
  if [ -e "$3" ]; then
    source_state=present
    source_value=$(stat -c %s "$3" 2>/dev/null || toybox stat -c %s "$3" 2>/dev/null) || source_value=
    [ -z "$source_value" ] || source_bytes=$source_value
    if [ ! -r "$3" ]; then
      source_state=unreadable
    elif hash_matches "$4" "$3"; then
      source_state=verified
    else
      source_state=mismatched
    fi
  fi
  echo "INSTALL_DIAG $1 $2 source=$3 state=$source_state bytes=$source_bytes"
}

file_bytes() {
  bytes_value=$(stat -c %s "$1" 2>/dev/null || toybox stat -c %s "$1" 2>/dev/null) || bytes_value=
  case "$bytes_value" in ''|*[!0-9]*) printf '0\n' ;; *) printf '%s\n' "$bytes_value" ;; esac
}

# Sum every byte this transaction is about to write into one filesystem: the staged replacements and
# the recovery copies it takes of whatever is live. Measuring the real files beats a fixed constant,
# which is how a panel carrying a legacy hapaneld-ledd pair could pass a headroom check computed for
# a panel that has none.
sum_bytes() {
  sum_total=0
  for sum_path in "$@"; do
    sum_total=$((sum_total + $(file_bytes "$sum_path")))
  done
  printf '%s\n' "$sum_total"
}

# Prove the destination accepts a real, non-zero write before anything is removed or replaced.
#
# The route probe this replaces wrote a ZERO-byte file to `/system`, then the transaction wrote
# 76KB to `/system/bin`. A zero-byte create at the parent cannot fail for a full filesystem, for
# exhausted inodes on a subtree, or for a read-only overmount one level down — which is precisely
# the class of panel that reached the first cp and failed there.
write_probe() {
  probe_dir=$1
  probe_path="$probe_dir/.hapaneld-write-probe-@TRANSACTION_ID@"
  rm -f "$probe_path" 2>/dev/null
  # 4096 bytes: one filesystem block, enough that the write reaches the allocator rather than
  # landing in an inode, and small enough that the probe cannot itself be what fills the partition.
  #
  # `dd` is also what keeps a refused write recoverable, and that is not obvious. Writing the probe
  # with `: > "$probe_path"` would be shorter, but `:` is a POSIX special builtin, so a redirection
  # error on it terminates a non-interactive shell outright. The transaction would die at the probe
  # with no marker, instead of returning false and refusing with a reason. `dd` is an ordinary
  # command: it fails, the shell survives, and the refusal path runs.
  if ! dd if=/dev/zero of="$probe_path" bs=4096 count=1 >/dev/null 2>&1; then
    rm -f "$probe_path" 2>/dev/null
    return 1
  fi
  probe_written=$(file_bytes "$probe_path")
  # Best effort, and deliberately not a verdict. The removal can only fail on a filesystem that went
  # read-only between the write and the unlink, and refusing an otherwise good upgrade over that
  # would be the wrong trade. The residue it would leave is bounded and self-identifying: one
  # 4096-byte file named for this transaction, beside the files the transaction is about to write.
  rm -f "$probe_path" 2>/dev/null
  [ "$probe_written" = 4096 ]
}

# Refuse before the first mutation, naming the blocker.
#
# Ordered cheapest and most decisive first, because the reasons are not interchangeable: staging
# that is gone is not a partition fault, a read-only partition is not a capacity fault, and a
# capacity fault is not a permissions fault. Anything that fails here has changed nothing, so the
# host can say so without offering a rollback of an install that never started.
preflight_target() {
  preflight_verb=$1
  preflight_dir=$2
  preflight_need=$3

  if [ ! -d "$preflight_dir" ]; then
    emit_target_diag "$preflight_verb" target "$preflight_dir"
    echo "INSTALL_UNCHANGED $preflight_verb target_directory_missing"
    return 1
  fi

  describe_target "$preflight_dir"
  if [ "$diag_state" = ro ]; then
    emit_target_diag "$preflight_verb" target "$preflight_dir"
    echo "INSTALL_UNCHANGED $preflight_verb target_read_only"
    return 1
  fi
  case "$diag_availkb" in
    ''|*[!0-9]*) ;;
    *)
      # Compare in kilobytes by dividing the need, never by multiplying the free space. The panel's
      # /system/bin/sh does 32-bit signed arithmetic, so availkb * 1024 goes negative for any
      # filesystem with more than 2 GiB free — and a negative number is "less than" every need, so a
      # panel with the MOST room was refused as having none. Measured on hardware: 2652400 * 1024 gave
      # -1578909696. The need is a few hundred KB at most, so the division side cannot overflow. The
      # host's own shell is 64-bit and cannot reproduce this; the source-shape assertion in the suite
      # and the hardware acceptance tool are what guard it.
      if [ "$diag_availkb" -lt "$(( (preflight_need + 1023) / 1024 ))" ]; then
        emit_target_diag "$preflight_verb" target "$preflight_dir"
        echo "INSTALL_UNCHANGED $preflight_verb target_insufficient_space"
        return 1
      fi
      ;;
  esac
  case "$diag_inodesfree" in
    ''|*[!0-9]*) ;;
    *)
      if [ "$diag_inodesfree" -lt 8 ]; then
        emit_target_diag "$preflight_verb" target "$preflight_dir"
        echo "INSTALL_UNCHANGED $preflight_verb target_insufficient_inodes"
        return 1
      fi
      ;;
  esac
  if ! write_probe "$preflight_dir"; then
    emit_target_diag "$preflight_verb" target "$preflight_dir"
    echo "INSTALL_UNCHANGED $preflight_verb target_not_writable"
    return 1
  fi
  return 0
}

preflight_source() {
  preflight_verb=$1
  preflight_path=$2
  preflight_sha=$3
  if [ ! -f "$preflight_path" ] || [ ! -r "$preflight_path" ]; then
    emit_source_diag "$preflight_verb" staged "$preflight_path" "$preflight_sha"
    echo "INSTALL_UNCHANGED $preflight_verb staged_source_unavailable"
    return 1
  fi
  # The destination hash check after each copy stays exactly as it was; this one only separates
  # "the bundle we were handed is wrong" from "the write went wrong", and refuses before mutating.
  if ! hash_matches "$preflight_sha" "$preflight_path"; then
    emit_source_diag "$preflight_verb" staged "$preflight_path" "$preflight_sha"
    echo "INSTALL_UNCHANGED $preflight_verb staged_source_unauthenticated"
    return 1
  fi
  return 0
}

# One copy, with the reason kept when it fails. The step names are unchanged, so an existing report
# still reads the same; what is new is the errno line and the two diagnostic lines beside it.
copy_staged() {
  copy_verb=$1
  copy_step=$2
  copy_source=$3
  copy_destination=$4
  copy_sha=$5
  if copy_error=$(cp "$copy_source" "$copy_destination" 2>&1); then
    return 0
  fi
  copy_error=$(printf '%s' "$copy_error" | tr '\n\t' '  ' | cut -c1-160)
  [ -n "$copy_error" ] || copy_error=none
  echo "INSTALL_DIAG $copy_verb $copy_step errno=$copy_error"
  emit_source_diag "$copy_verb" "$copy_step" "$copy_source" "$copy_sha"
  emit_target_diag "$copy_verb" "$copy_step" "${copy_destination%/*}"
  echo "INSTALL_STEP_FAILED $copy_verb $copy_step"
  return 1
}

flag() {
  grep -q "^$1=1$" "$2"
}

valid_recovery() {
  name=$1 recovery=$2 marker=$3
  expected=$(sed -n "s/^${name}_SHA256=//p" "$marker")
  case "$expected" in ''|*[!0-9a-f]*) return 1 ;; esac
  [ "${#expected}" -eq 64 ] || return 1
  [ -f "$recovery" ] && root_owned "$recovery" && [ "$(file_sha256 "$recovery")" = "$expected" ]
}

live_matches_recorded() {
  name=$1 live=$2 marker=$3
  if flag "$name" "$marker"; then
    expected=$(sed -n "s/^${name}_SHA256=//p" "$marker")
    [ -f "$live" ] && root_owned "$live" && [ "$(file_sha256 "$live")" = "$expected" ]
  else
    [ ! -e "$live" ] && [ ! -L "$live" ]
  fi
}

live_matches_recorded_or_target() {
  name=$1 live=$2 target_sha=$3 marker=$4
  if live_matches_recorded "$name" "$live" "$marker"; then
    return 0
  fi
  if [ "$target_sha" = - ]; then
    [ ! -e "$live" ] && [ ! -L "$live" ]
  else
    [ -f "$live" ] && root_owned "$live" && hash_matches "$target_sha" "$live"
  fi
}

file_exact() {
  file_exact_expected=$1 file_exact_path=$2 file_exact_mode=$3
  [ -f "$file_exact_path" ] && [ ! -L "$file_exact_path" ] && root_owned "$file_exact_path" || return 1
  file_exact_metadata=$(stat -c %a:%h "$file_exact_path" 2>/dev/null || toybox stat -c %a:%h "$file_exact_path" 2>/dev/null) || return 1
  [ "$file_exact_metadata" = "$file_exact_mode:1" ] && hash_matches "$file_exact_expected" "$file_exact_path"
}

hash_matches_known_legacy() {
  live=$1 first=$2 second=$3
  hash_matches "$first" "$live" || hash_matches "$second" "$live"
}

system_matches_recorded_v1() {
  marker=/system/bin/.hapaneld-helper-upgrade
  live_matches_recorded OLD_BIN /system/bin/hapaneld-helper "$marker" &&
    live_matches_recorded OLD_SERVICE /system/etc/init/hapaneld-helper.rc "$marker" &&
    live_matches_recorded LEGACY_BIN /system/bin/hapaneld-ledd "$marker" &&
    live_matches_recorded LEGACY_SERVICE /system/etc/init/hapaneld-ledd.rc "$marker" &&
    live_matches_recorded ALT_BIN /data/adb/hapaneld/hapaneld-helper "$marker" &&
    live_matches_recorded ALT_SERVICE /data/adb/service.d/hapaneld-helper.sh "$marker"
}

v1_rollback_intent() {
  [ "$(grep -c '^V1_ROLLBACK_IN_PROGRESS=1$' "$1" 2>/dev/null || true)" = 1 ]
}

system_transition_v1() {
  marker=/system/bin/.hapaneld-helper-upgrade
  live_matches_recorded_or_target OLD_BIN /system/bin/hapaneld-helper "$target_helper" "$marker" &&
    { live_matches_recorded OLD_SERVICE /system/etc/init/hapaneld-helper.rc "$marker" ||
      hash_matches_known_legacy /system/etc/init/hapaneld-helper.rc @LEGACY_RC_SHA256@ @LEGACY_RC_SUPERVISED_SHA256@; } &&
    live_matches_recorded_or_target LEGACY_BIN /system/bin/hapaneld-ledd - "$marker" &&
    live_matches_recorded_or_target LEGACY_SERVICE /system/etc/init/hapaneld-ledd.rc - "$marker" &&
    live_matches_recorded_or_target ALT_BIN /data/adb/hapaneld/hapaneld-helper - "$marker" &&
    live_matches_recorded_or_target ALT_SERVICE /data/adb/service.d/hapaneld-helper.sh - "$marker" &&
    [ ! -e /vendor/etc/init/hapaneld-helper.rc ] && [ ! -L /vendor/etc/init/hapaneld-helper.rc ]
}

classify_system_v1() {
  marker=/system/bin/.hapaneld-helper-upgrade
  # A no-op helper upgrade can match both the recorded old state and the desired target. Prefer
  # TARGET so a successful APK install can commit instead of stranding its recovery journal.
  if hash_matches "$target_helper" /system/bin/hapaneld-helper &&
       hash_matches_known_legacy /system/etc/init/hapaneld-helper.rc @LEGACY_RC_SHA256@ @LEGACY_RC_SUPERVISED_SHA256@ &&
       [ ! -e /system/bin/hapaneld-ledd ] && [ ! -L /system/bin/hapaneld-ledd ] &&
       [ ! -e /system/etc/init/hapaneld-ledd.rc ] && [ ! -L /system/etc/init/hapaneld-ledd.rc ] &&
       [ ! -e /vendor/etc/init/hapaneld-helper.rc ] && [ ! -L /vendor/etc/init/hapaneld-helper.rc ] &&
       [ ! -e /data/adb/hapaneld/hapaneld-helper ] && [ ! -L /data/adb/hapaneld/hapaneld-helper ] &&
       [ ! -e /data/adb/service.d/hapaneld-helper.sh ] && [ ! -L /data/adb/service.d/hapaneld-helper.sh ]; then
    echo TARGET
  elif system_matches_recorded_v1; then
    echo PRE_SWAP
  elif v1_rollback_intent "$marker" && system_transition_v1; then
    echo TRANSITION
  else
    echo UNKNOWN
  fi
}

systemless_matches_recorded_v1() {
  marker=/data/adb/hapaneld/.helper-upgrade.marker
  live_matches_recorded OLD_BIN /data/adb/hapaneld/hapaneld-helper "$marker" &&
    live_matches_recorded OLD_SERVICE /data/adb/service.d/hapaneld-helper.sh "$marker"
}

systemless_transition_v1() {
  marker=/data/adb/hapaneld/.helper-upgrade.marker
  live_matches_recorded_or_target OLD_BIN /data/adb/hapaneld/hapaneld-helper "$target_helper" "$marker" &&
    { live_matches_recorded OLD_SERVICE /data/adb/service.d/hapaneld-helper.sh "$marker" ||
      hash_matches_known_legacy /data/adb/service.d/hapaneld-helper.sh @LEGACY_SERVICE_SHA256@ @LEGACY_SERVICE_SUPERVISED_SHA256@; } &&
    [ ! -e /vendor/etc/init/hapaneld-helper.rc ] && [ ! -L /vendor/etc/init/hapaneld-helper.rc ]
}

classify_systemless_v1() {
  if hash_matches "$target_helper" /data/adb/hapaneld/hapaneld-helper &&
       hash_matches_known_legacy /data/adb/service.d/hapaneld-helper.sh @LEGACY_SERVICE_SHA256@ @LEGACY_SERVICE_SUPERVISED_SHA256@ &&
       [ ! -e /vendor/etc/init/hapaneld-helper.rc ] && [ ! -L /vendor/etc/init/hapaneld-helper.rc ]; then
    echo TARGET
  elif systemless_matches_recorded_v1; then
    echo PRE_SWAP
  elif v1_rollback_intent "$marker" && systemless_transition_v1; then
    echo TRANSITION
  else
    echo UNKNOWN
  fi
}

hybrid_matches_recorded_v1() {
  marker=/data/adb/hapaneld/.helper-hybrid-upgrade.marker
  live_matches_recorded OLD_BIN /data/adb/hapaneld/hapaneld-helper "$marker" &&
    live_matches_recorded OLD_RC /vendor/etc/init/hapaneld-helper.rc "$marker" &&
    live_matches_recorded SYS_RC /system/etc/init/hapaneld-helper.rc "$marker" &&
    live_matches_recorded SYS_BIN /system/bin/hapaneld-helper "$marker" &&
    live_matches_recorded LEGACY_BIN /system/bin/hapaneld-ledd "$marker" &&
    live_matches_recorded LEGACY_SERVICE /system/etc/init/hapaneld-ledd.rc "$marker" &&
    live_matches_recorded ALT_SERVICE /data/adb/service.d/hapaneld-helper.sh "$marker"
}

classify_hybrid_v1() {
  marker=/data/adb/hapaneld/.helper-hybrid-upgrade.marker
  if hash_matches "$target_helper" /data/adb/hapaneld/hapaneld-helper &&
       root_owned /data/adb/hapaneld/hapaneld-helper &&
       hash_matches_known_legacy /vendor/etc/init/hapaneld-helper.rc @LEGACY_HYBRID_RC_SHA256@ @LEGACY_HYBRID_RC_SUPERVISED_SHA256@ &&
       root_owned /vendor/etc/init/hapaneld-helper.rc &&
       [ ! -e /system/bin/hapaneld-helper ] && [ ! -L /system/bin/hapaneld-helper ] &&
       [ ! -e /system/etc/init/hapaneld-helper.rc ] && [ ! -L /system/etc/init/hapaneld-helper.rc ] &&
       [ ! -e /system/bin/hapaneld-ledd ] && [ ! -L /system/bin/hapaneld-ledd ] &&
       [ ! -e /system/etc/init/hapaneld-ledd.rc ] && [ ! -L /system/etc/init/hapaneld-ledd.rc ] &&
       [ ! -e /data/adb/service.d/hapaneld-helper.sh ] && [ ! -L /data/adb/service.d/hapaneld-helper.sh ]; then
    echo TARGET
  elif hybrid_matches_recorded_v1; then
    echo PRE_SWAP
  elif live_matches_recorded_or_target OLD_BIN /data/adb/hapaneld/hapaneld-helper "$target_helper" "$marker" &&
     (live_matches_recorded OLD_RC /vendor/etc/init/hapaneld-helper.rc "$marker" ||
       hash_matches_known_legacy /vendor/etc/init/hapaneld-helper.rc @LEGACY_HYBRID_RC_SHA256@ @LEGACY_HYBRID_RC_SUPERVISED_SHA256@) &&
     live_matches_recorded_or_target SYS_RC /system/etc/init/hapaneld-helper.rc - "$marker" &&
     live_matches_recorded_or_target SYS_BIN /system/bin/hapaneld-helper - "$marker" &&
     live_matches_recorded_or_target LEGACY_BIN /system/bin/hapaneld-ledd - "$marker" &&
     live_matches_recorded_or_target LEGACY_SERVICE /system/etc/init/hapaneld-ledd.rc - "$marker" &&
     live_matches_recorded_or_target ALT_SERVICE /data/adb/service.d/hapaneld-helper.sh - "$marker"; then
    echo TRANSITION
  else
    echo UNKNOWN
  fi
}

v2_recovery_path() {
  printf '/data/adb/hapaneld/.helper-recovery-%s.%s\n' "$transaction_id" "$1"
}

v2_live_matches_recorded() {
  name=$1 live=$2 mode=$3 marker=$4
  if flag "$name" "$marker"; then
    expected=$(sed -n "s/^${name}_SHA256=//p" "$marker")
    file_exact "$expected" "$live" "$mode"
  else
    [ ! -e "$live" ] && [ ! -L "$live" ]
  fi
}

valid_v2_marker_record() {
  marker=$1 name=$2
  [ "$(grep -c "^${name}=" "$marker" 2>/dev/null || true)" = 1 ] || return 1
  [ "$(grep -c "^${name}_SHA256=" "$marker" 2>/dev/null || true)" = 1 ] || return 1
  value=$(sed -n "s/^${name}=//p" "$marker")
  expected=$(sed -n "s/^${name}_SHA256=//p" "$marker")
  case "$value:$expected" in
    0:-) ;;
    1:*)
      case "$expected" in ''|*[!0-9a-f]*) return 1 ;; esac
      [ "${#expected}" -eq 64 ] || return 1 ;;
    *) return 1 ;;
  esac
}

valid_v2_marker() {
  kind=$1 marker=$2
  [ "$(grep -c '^JOURNAL_VERSION=2$' "$marker" 2>/dev/null || true)" = 1 ] || return 1
  [ "$(grep -c '^JOURNAL_SCOPE=APK_HELPER$' "$marker" 2>/dev/null || true)" = 1 ] || return 1
  [ "$(grep -c "^BOOT_KIND=$kind$" "$marker" 2>/dev/null || true)" = 1 ] || return 1
  for name in TARGET_HELPER_SHA256 TARGET_BOOT_SHA256; do
    [ "$(grep -c "^${name}=" "$marker" 2>/dev/null || true)" = 1 ] || return 1
    expected=$(sed -n "s/^${name}=//p" "$marker")
    case "$expected" in ''|*[!0-9a-f]*) return 1 ;; esac
    [ "${#expected}" -eq 64 ] || return 1
  done
  for name in LIVE_BIN SYS_BIN SYS_RC DATA_BIN DATA_SERVICE VENDOR_RC LEGACY_BIN LEGACY_SERVICE; do
    valid_v2_marker_record "$marker" "$name" || return 1
  done
}

v2_recorded() {
  marker=$1
  v2_live_matches_recorded LIVE_BIN /data/local/hapaneld-helper 700 "$marker" &&
    v2_live_matches_recorded SYS_BIN /system/bin/hapaneld-helper 755 "$marker" &&
    v2_live_matches_recorded SYS_RC /system/etc/init/hapaneld-helper.rc 644 "$marker" &&
    v2_live_matches_recorded DATA_BIN /data/adb/hapaneld/hapaneld-helper 755 "$marker" &&
    v2_live_matches_recorded DATA_SERVICE /data/adb/service.d/hapaneld-helper.sh 755 "$marker" &&
    v2_live_matches_recorded VENDOR_RC /vendor/etc/init/hapaneld-helper.rc 644 "$marker" &&
    v2_live_matches_recorded LEGACY_BIN /system/bin/hapaneld-ledd 755 "$marker" &&
    v2_live_matches_recorded LEGACY_SERVICE /system/etc/init/hapaneld-ledd.rc 644 "$marker"
}

v2_noncanonical_recorded() {
  marker=$1
  v2_live_matches_recorded SYS_BIN /system/bin/hapaneld-helper 755 "$marker" &&
    v2_live_matches_recorded SYS_RC /system/etc/init/hapaneld-helper.rc 644 "$marker" &&
    v2_live_matches_recorded DATA_BIN /data/adb/hapaneld/hapaneld-helper 755 "$marker" &&
    v2_live_matches_recorded DATA_SERVICE /data/adb/service.d/hapaneld-helper.sh 755 "$marker" &&
    v2_live_matches_recorded VENDOR_RC /vendor/etc/init/hapaneld-helper.rc 644 "$marker" &&
    v2_live_matches_recorded LEGACY_BIN /system/bin/hapaneld-ledd 755 "$marker" &&
    v2_live_matches_recorded LEGACY_SERVICE /system/etc/init/hapaneld-ledd.rc 644 "$marker"
}

app_replacement_custody_present() {
  for custody in \
      /data/local/.hapaneld-helper.new \
      /data/local/.hapaneld-helper.previous \
      /data/local/.hapaneld-helper.previous.tmp \
      /data/local/.hapaneld-helper.legacy-takeover \
      /data/local/.hapaneld-helper.legacy-takeover.tmp \
      /data/local/.hapaneld-guard-db/replacement.v1 \
      /data/local/.hapaneld-guard-db/.replacement.v1.tmp; do
    if [ -e "$custody" ] || [ -L "$custody" ]; then return 0; fi
  done
  return 1
}

v2_external_canonical_change() {
  marker=$1
  ! app_replacement_custody_present || return 1
  v2_noncanonical_recorded "$marker" || return 1
  [ -f /data/local/hapaneld-helper ] && [ ! -L /data/local/hapaneld-helper ] || return 1
  external_observed_sha=$(file_sha256 /data/local/hapaneld-helper) || return 1
  case "$external_observed_sha" in ''|*[!0-9a-f]*) return 1 ;; esac
  [ "${#external_observed_sha}" -eq 64 ] || return 1
  file_exact "$external_observed_sha" /data/local/hapaneld-helper 700 || return 1
  ! v2_live_matches_recorded LIVE_BIN /data/local/hapaneld-helper 700 "$marker"
}

v2_cancel_external() {
  marker=$1
  [ "$(grep -c '^CANCEL_EXTERNAL=1$' "$marker" 2>/dev/null || true)" = 1 ] || return 1
  [ "$(grep -c '^EXTERNAL_HELPER_SHA256=' "$marker" 2>/dev/null || true)" = 1 ] || return 1
  external_expected_sha=$(sed -n 's/^EXTERNAL_HELPER_SHA256=//p' "$marker")
  case "$external_expected_sha" in ''|*[!0-9a-f]*) return 1 ;; esac
  [ "${#external_expected_sha}" -eq 64 ] || return 1
  v2_noncanonical_recorded "$marker" || return 1
  file_exact "$external_expected_sha" /data/local/hapaneld-helper 700 || return 1
  ! v2_live_matches_recorded LIVE_BIN /data/local/hapaneld-helper 700 "$marker" || return 1
  ! app_replacement_custody_present
}

v2_cancel_marker_present() {
  grep -q '^CANCEL_EXTERNAL=' "$1" 2>/dev/null ||
    grep -q '^EXTERNAL_HELPER_SHA256=' "$1" 2>/dev/null
}

publish_cancel_external_v2() {
  kind=$1 marker=$2
  valid_v2_marker "$kind" "$marker" || return 1
  ! v2_cancel_marker_present "$marker" || return 1
  ! app_replacement_custody_present || return 1
  v2_external_canonical_change "$marker" || return 1
  external_sha=$external_observed_sha
  cancel_staging="$marker.cancel-$transaction_id"
  rm -f "$cancel_staging" || return 1
  sed '/^CANCEL_EXTERNAL=/d; /^EXTERNAL_HELPER_SHA256=/d' "$marker" > "$cancel_staging" || return 1
  echo CANCEL_EXTERNAL=1 >> "$cancel_staging"
  echo EXTERNAL_HELPER_SHA256=$external_sha >> "$cancel_staging"
  chown 0:0 "$cancel_staging" || return 1
  chmod 600 "$cancel_staging" || return 1
  sync || return 1
  ! app_replacement_custody_present || return 1
  v2_external_canonical_change "$marker" || return 1
  [ "$external_observed_sha" = "$external_sha" ] || return 1
  mv -f "$cancel_staging" "$marker" || return 1
  sync || return 1
  v2_cancel_external "$marker"
}

retire_cancel_external_v2() {
  marker=$1
  v2_cancel_external "$marker" || return 4
  rm -f "$marker" || return 4
  sync || return 4
  cleanup_v2_recoveries
  rm -f \
    /system/etc/init/hapaneld-helper.rc.new \
    /data/adb/service.d/hapaneld-helper.sh.new \
    /vendor/etc/init/hapaneld-helper.rc.new 2>/dev/null || true
  sync 2>/dev/null || true
  echo EXTERNAL_CANONICAL_RETRY
  return 5
}

replacement_safe_after_retirement() {
  kind=$1 marker=$2 candidate=$3
  attempt=0
  while [ "$attempt" -lt 3 ]; do
    if app_replacement_custody_present; then
      echo TOPOLOGY_HOLD
      return 4
    fi
    if ! v2_recorded "$marker"; then
      if v2_external_canonical_change "$marker" && publish_cancel_external_v2 "$kind" "$marker"; then
        retire_cancel_external_v2 "$marker"
        return $?
      fi
      echo TOPOLOGY_HOLD
      return 4
    fi
    replacement=$($candidate --replacement-safe 2>/dev/null); replacement_status=$?
    if app_replacement_custody_present; then
      echo TOPOLOGY_HOLD
      return 4
    fi
    if ! v2_recorded "$marker"; then
      attempt=$((attempt + 1))
      continue
    fi
    if [ "$replacement_status" -ne 0 ]; then
      [ "$replacement_status" -eq 3 ] && [ "$replacement" = GUARD_ARMED ] && { echo GUARD_ARMED; return 3; }
      return 1
    fi
    [ "$replacement" = REPLACE_SAFE ] || return 1
    return 0
  done
  echo TOPOLOGY_HOLD
  return 4
}

v2_recorded_or_absent() {
  name=$1 live=$2 mode=$3 marker=$4
  v2_live_matches_recorded "$name" "$live" "$mode" "$marker" ||
    { [ ! -e "$live" ] && [ ! -L "$live" ]; }
}

v2_canonical_exact() {
  marker=$1
  expected=$(sed -n 's/^TARGET_HELPER_SHA256=//p' "$marker")
  file_exact "$expected" /data/local/hapaneld-helper 700
}

v2_target() {
  kind=$1 marker=$2
  expected_boot=$(sed -n 's/^TARGET_BOOT_SHA256=//p' "$marker")
  v2_canonical_exact "$marker" &&
    [ ! -e /system/bin/hapaneld-helper ] && [ ! -L /system/bin/hapaneld-helper ] &&
    [ ! -e /data/adb/hapaneld/hapaneld-helper ] && [ ! -L /data/adb/hapaneld/hapaneld-helper ] &&
    [ ! -e /system/bin/hapaneld-ledd ] && [ ! -L /system/bin/hapaneld-ledd ] &&
    [ ! -e /system/etc/init/hapaneld-ledd.rc ] && [ ! -L /system/etc/init/hapaneld-ledd.rc ] || return 1
  case "$kind" in
    system)
      file_exact "$expected_boot" /system/etc/init/hapaneld-helper.rc 644 &&
        [ ! -e /vendor/etc/init/hapaneld-helper.rc ] && [ ! -L /vendor/etc/init/hapaneld-helper.rc ] &&
        [ ! -e /data/adb/service.d/hapaneld-helper.sh ] && [ ! -L /data/adb/service.d/hapaneld-helper.sh ] ;;
    systemless)
      file_exact "$expected_boot" /data/adb/service.d/hapaneld-helper.sh 755 &&
        [ ! -e /system/etc/init/hapaneld-helper.rc ] && [ ! -L /system/etc/init/hapaneld-helper.rc ] &&
        [ ! -e /vendor/etc/init/hapaneld-helper.rc ] && [ ! -L /vendor/etc/init/hapaneld-helper.rc ] ;;
    hybrid)
      file_exact "$expected_boot" /vendor/etc/init/hapaneld-helper.rc 644 &&
        [ ! -e /system/etc/init/hapaneld-helper.rc ] && [ ! -L /system/etc/init/hapaneld-helper.rc ] &&
        [ ! -e /data/adb/service.d/hapaneld-helper.sh ] && [ ! -L /data/adb/service.d/hapaneld-helper.sh ] ;;
    *) return 1 ;;
  esac
}

v2_canonical_swapped() {
  marker=$1
  v2_canonical_exact "$marker" &&
    v2_live_matches_recorded SYS_BIN /system/bin/hapaneld-helper 755 "$marker" &&
    v2_live_matches_recorded SYS_RC /system/etc/init/hapaneld-helper.rc 644 "$marker" &&
    v2_live_matches_recorded DATA_BIN /data/adb/hapaneld/hapaneld-helper 755 "$marker" &&
    v2_live_matches_recorded DATA_SERVICE /data/adb/service.d/hapaneld-helper.sh 755 "$marker" &&
    v2_live_matches_recorded VENDOR_RC /vendor/etc/init/hapaneld-helper.rc 644 "$marker" &&
    v2_live_matches_recorded LEGACY_BIN /system/bin/hapaneld-ledd 755 "$marker" &&
    v2_live_matches_recorded LEGACY_SERVICE /system/etc/init/hapaneld-ledd.rc 644 "$marker"
}

v2_boot_switched() {
  kind=$1 marker=$2
  expected_boot=$(sed -n 's/^TARGET_BOOT_SHA256=//p' "$marker")
  v2_canonical_exact "$marker" || return 1
  case "$kind" in
    system) file_exact "$expected_boot" /system/etc/init/hapaneld-helper.rc 644 || return 1 ;;
    systemless) file_exact "$expected_boot" /data/adb/service.d/hapaneld-helper.sh 755 || return 1 ;;
    hybrid) file_exact "$expected_boot" /vendor/etc/init/hapaneld-helper.rc 644 || return 1 ;;
    *) return 1 ;;
  esac
  v2_recorded_or_absent SYS_BIN /system/bin/hapaneld-helper 755 "$marker" &&
    v2_recorded_or_absent DATA_BIN /data/adb/hapaneld/hapaneld-helper 755 "$marker" &&
    v2_recorded_or_absent LEGACY_BIN /system/bin/hapaneld-ledd 755 "$marker" &&
    v2_recorded_or_absent LEGACY_SERVICE /system/etc/init/hapaneld-ledd.rc 644 "$marker" || return 1
  case "$kind" in
    system)
      v2_recorded_or_absent DATA_SERVICE /data/adb/service.d/hapaneld-helper.sh 755 "$marker" &&
        v2_recorded_or_absent VENDOR_RC /vendor/etc/init/hapaneld-helper.rc 644 "$marker" ;;
    systemless)
      v2_recorded_or_absent SYS_RC /system/etc/init/hapaneld-helper.rc 644 "$marker" &&
        v2_recorded_or_absent VENDOR_RC /vendor/etc/init/hapaneld-helper.rc 644 "$marker" ;;
    hybrid)
      v2_recorded_or_absent SYS_RC /system/etc/init/hapaneld-helper.rc 644 "$marker" &&
        v2_recorded_or_absent DATA_SERVICE /data/adb/service.d/hapaneld-helper.sh 755 "$marker" ;;
  esac
}

classify_v2() {
  kind=$1 marker=$2
  valid_v2_marker "$kind" "$marker" || { echo UNKNOWN; return; }
  if app_replacement_custody_present; then echo TOPOLOGY_HOLD
  elif v2_cancel_external "$marker"; then echo CANCEL_EXTERNAL
  elif v2_cancel_marker_present "$marker"; then echo UNKNOWN
  elif v2_target "$kind" "$marker"; then echo TARGET
  elif v2_recorded "$marker"; then echo PRE_SWAP
  elif v2_canonical_swapped "$marker"; then echo CANONICAL_SWAPPED
  elif v2_boot_switched "$kind" "$marker"; then echo BOOT_SWITCHED
  elif v2_external_canonical_change "$marker"; then echo EXTERNAL_CANONICAL_CHANGE
  else echo UNKNOWN
  fi
}

classify_system() {
  marker=/system/bin/.hapaneld-helper-upgrade
  if app_replacement_custody_present; then echo TOPOLOGY_HOLD
  elif grep -q ^JOURNAL_VERSION=2$ "$marker"; then classify_v2 system "$marker"
  else classify_system_v1
  fi
}

classify_systemless() {
  marker=/data/adb/hapaneld/.helper-upgrade.marker
  if app_replacement_custody_present; then echo TOPOLOGY_HOLD
  elif grep -q ^JOURNAL_VERSION=2$ "$marker"; then classify_v2 systemless "$marker"
  else classify_systemless_v1
  fi
}

classify_hybrid() {
  marker=/data/adb/hapaneld/.helper-hybrid-upgrade.marker
  if app_replacement_custody_present; then echo TOPOLOGY_HOLD
  elif grep -q ^JOURNAL_VERSION=2$ "$marker"; then classify_v2 hybrid "$marker"
  else classify_hybrid_v1
  fi
}

publish_v1_rollback_intent() {
  marker=$1
  grep -q '^JOURNAL_VERSION=1$' "$marker" || return 1
  if v1_rollback_intent "$marker"; then return 0; fi
  intent_staging="$marker.rollback-intent-$transaction_id"
  rm -f "$intent_staging" || return 1
  sed '/^V1_ROLLBACK_IN_PROGRESS=/d' "$marker" > "$intent_staging" || return 1
  echo V1_ROLLBACK_IN_PROGRESS=1 >> "$intent_staging"
  chown 0:0 "$intent_staging" || return 1
  chmod 600 "$intent_staging" || return 1
  sync || return 1
  mv -f "$intent_staging" "$marker" || return 1
  sync || return 1
  v1_rollback_intent "$marker"
}

restore_or_remove() {
  v1_restore_name=$1 v1_restore_recovery=$2 v1_restore_live=$3 v1_restore_mode=$4 v1_restore_marker=$5
  v1_restore_staging="$v1_restore_live.rollback-v1-$transaction_id"
  rm -f "$v1_restore_staging" || return 1
  if flag "$v1_restore_name" "$v1_restore_marker"; then
    v1_restore_expected=$(sed -n "s/^${v1_restore_name}_SHA256=//p" "$v1_restore_marker")
    cp -p "$v1_restore_recovery" "$v1_restore_staging" || return 1
    chown 0:0 "$v1_restore_staging" || return 1
    chmod "$v1_restore_mode" "$v1_restore_staging" || return 1
    file_exact "$v1_restore_expected" "$v1_restore_staging" "$v1_restore_mode" || return 1
    sync || return 1
    mv -f "$v1_restore_staging" "$v1_restore_live" || return 1
    sync || return 1
    file_exact "$v1_restore_expected" "$v1_restore_live" "$v1_restore_mode" || return 1
  else
    rm -f "$v1_restore_live" || return 1
    sync || return 1
    [ ! -e "$v1_restore_live" ] && [ ! -L "$v1_restore_live" ] || return 1
  fi
}

valid_v2_recoveries() {
  marker=$1
  for entry in \
      'LIVE_BIN live 700' 'SYS_BIN sysbin 755' 'SYS_RC sysrc 644' 'DATA_BIN databin 755' \
      'DATA_SERVICE datasvc 755' 'VENDOR_RC vendorrc 644' 'LEGACY_BIN legacybin 755' \
      'LEGACY_SERVICE legacyrc 644'; do
    set -- $entry
    name=$1 recovery=$(v2_recovery_path "$2")
    if flag "$name" "$marker"; then
      expected=$(sed -n "s/^${name}_SHA256=//p" "$marker")
      file_exact "$expected" "$recovery" "$3" || return 1
    else
      [ ! -e "$recovery" ] && [ ! -L "$recovery" ] || return 1
    fi
  done
}

cleanup_v2_recoveries() {
  rm -f \
    "$(v2_recovery_path live)" "$(v2_recovery_path sysbin)" \
    "$(v2_recovery_path sysrc)" "$(v2_recovery_path databin)" \
    "$(v2_recovery_path datasvc)" "$(v2_recovery_path vendorrc)" \
    "$(v2_recovery_path legacybin)" "$(v2_recovery_path legacyrc)" \
    "/data/local/.hapaneld-helper.provision-$transaction_id" \
    "/data/local/hapaneld-helper.rollback-$transaction_id" \
    "/system/bin/hapaneld-helper.rollback-$transaction_id" \
    "/system/etc/init/hapaneld-helper.rc.rollback-$transaction_id" \
    "/data/adb/hapaneld/hapaneld-helper.rollback-$transaction_id" \
    "/data/adb/service.d/hapaneld-helper.sh.rollback-$transaction_id" \
    "/vendor/etc/init/hapaneld-helper.rc.rollback-$transaction_id" \
    "/system/bin/hapaneld-ledd.rollback-$transaction_id" \
    "/system/etc/init/hapaneld-ledd.rc.rollback-$transaction_id" \
    "/system/bin/.hapaneld-helper-upgrade.cancel-$transaction_id" \
    "/data/adb/hapaneld/.helper-upgrade.marker.cancel-$transaction_id" \
    "/data/adb/hapaneld/.helper-hybrid-upgrade.marker.cancel-$transaction_id" 2>/dev/null || true
}

cleanup_v1_rollback_staging() {
  rm -f \
    "/system/bin/.hapaneld-helper-upgrade.rollback-intent-$transaction_id" \
    "/data/adb/hapaneld/.helper-upgrade.marker.rollback-intent-$transaction_id" \
    "/data/adb/hapaneld/.helper-hybrid-upgrade.marker.rollback-intent-$transaction_id" \
    "/data/local/hapaneld-helper.rollback-v1-$transaction_id" \
    "/system/bin/hapaneld-helper.rollback-v1-$transaction_id" \
    "/system/etc/init/hapaneld-helper.rc.rollback-v1-$transaction_id" \
    "/data/adb/hapaneld/hapaneld-helper.rollback-v1-$transaction_id" \
    "/data/adb/service.d/hapaneld-helper.sh.rollback-v1-$transaction_id" \
    "/vendor/etc/init/hapaneld-helper.rc.rollback-v1-$transaction_id" \
    "/system/bin/hapaneld-ledd.rollback-v1-$transaction_id" \
    "/system/etc/init/hapaneld-ledd.rc.rollback-v1-$transaction_id" 2>/dev/null || true
}

restore_or_remove_v2() {
  name=$1 recovery=$2 live=$3 mode=$4 marker=$5
  staging="$live.rollback-$transaction_id"
  rm -f "$staging" || return 1
  if flag "$name" "$marker"; then
    expected=$(sed -n "s/^${name}_SHA256=//p" "$marker")
    cp -p "$recovery" "$staging" || return 1
    chown 0:0 "$staging" || return 1
    chmod "$mode" "$staging" || return 1
    file_exact "$expected" "$staging" "$mode" || return 1
    sync || return 1
    mv -f "$staging" "$live" || return 1
    sync || return 1
    file_exact "$expected" "$live" "$mode" || return 1
  else
    rm -f "$live" || return 1
    sync || return 1
    [ ! -e "$live" ] && [ ! -L "$live" ] || return 1
  fi
}

rollback_v2() {
  kind=$1 marker=$2
  state=$3
  case "$state" in PRE_SWAP|CANONICAL_SWAPPED|BOOT_SWITCHED|TARGET) ;; *) return 1 ;; esac
  valid_v2_recoveries "$marker" || return 1

  probe=/data/adb/hapaneld/.helper-probe-$transaction_id
  rm -f "$probe"
  hash_matches @BIN_SHA256@ @STAGED_HELPER@ || return 1
  cp @STAGED_HELPER@ "$probe" || return 1
  chown 0:0 "$probe" || return 1
  chmod 700 "$probe" || return 1
  hash_matches @BIN_SHA256@ "$probe" || return 1

  retire_helpers || return 1

  # Keep the target canonical binary and selected target boot registration in place while every
  # non-authoritative incumbent path is restored. Each completed cut therefore remains
  # BOOT_SWITCHED. Restore the selected boot slot next (CANONICAL_SWAPPED), then restore/remove the
  # canonical binary last (PRE_SWAP). Per-path staging makes the individual restores atomic too.
  restore_or_remove_v2 SYS_BIN "$(v2_recovery_path sysbin)" /system/bin/hapaneld-helper 755 "$marker" || return 1
  restore_or_remove_v2 DATA_BIN "$(v2_recovery_path databin)" /data/adb/hapaneld/hapaneld-helper 755 "$marker" || return 1
  restore_or_remove_v2 LEGACY_BIN "$(v2_recovery_path legacybin)" /system/bin/hapaneld-ledd 755 "$marker" || return 1
  restore_or_remove_v2 LEGACY_SERVICE "$(v2_recovery_path legacyrc)" /system/etc/init/hapaneld-ledd.rc 644 "$marker" || return 1
  case "$kind" in
    system)
      restore_or_remove_v2 DATA_SERVICE "$(v2_recovery_path datasvc)" /data/adb/service.d/hapaneld-helper.sh 755 "$marker" || return 1
      restore_or_remove_v2 VENDOR_RC "$(v2_recovery_path vendorrc)" /vendor/etc/init/hapaneld-helper.rc 644 "$marker" || return 1
      restore_or_remove_v2 SYS_RC "$(v2_recovery_path sysrc)" /system/etc/init/hapaneld-helper.rc 644 "$marker" || return 1 ;;
    systemless)
      restore_or_remove_v2 SYS_RC "$(v2_recovery_path sysrc)" /system/etc/init/hapaneld-helper.rc 644 "$marker" || return 1
      restore_or_remove_v2 VENDOR_RC "$(v2_recovery_path vendorrc)" /vendor/etc/init/hapaneld-helper.rc 644 "$marker" || return 1
      restore_or_remove_v2 DATA_SERVICE "$(v2_recovery_path datasvc)" /data/adb/service.d/hapaneld-helper.sh 755 "$marker" || return 1 ;;
    hybrid)
      restore_or_remove_v2 SYS_RC "$(v2_recovery_path sysrc)" /system/etc/init/hapaneld-helper.rc 644 "$marker" || return 1
      restore_or_remove_v2 DATA_SERVICE "$(v2_recovery_path datasvc)" /data/adb/service.d/hapaneld-helper.sh 755 "$marker" || return 1
      restore_or_remove_v2 VENDOR_RC "$(v2_recovery_path vendorrc)" /vendor/etc/init/hapaneld-helper.rc 644 "$marker" || return 1 ;;
    *) return 1 ;;
  esac
  restore_or_remove_v2 LIVE_BIN "$(v2_recovery_path live)" /data/local/hapaneld-helper 700 "$marker" || return 1
  sync || return 1
  v2_recorded "$marker" || return 1

  if flag LIVE_BIN "$marker" && [ -x /data/local/hapaneld-helper ]; then
    /data/local/hapaneld-helper --supervise >/dev/null 2>&1 &
    result=ROLLBACK_RESTARTED
  elif flag SYS_BIN "$marker" && [ -x /system/bin/hapaneld-helper ]; then
    /system/bin/hapaneld-helper --supervise >/dev/null 2>&1 &
    result=ROLLBACK_RESTARTED
  elif flag DATA_BIN "$marker" && [ -x /data/adb/hapaneld/hapaneld-helper ]; then
    /data/adb/hapaneld/hapaneld-helper --supervise >/dev/null 2>&1 &
    result=ROLLBACK_RESTARTED
  elif flag LEGACY_BIN "$marker" && [ -x /system/bin/hapaneld-ledd ]; then
    start hapaneld_ledd 2>/dev/null || /system/bin/hapaneld-ledd >/dev/null 2>&1 &
    result=ROLLBACK_LEGACY
  else
    result=ROLLBACK_EMPTY
  fi
  echo "$result"
}

release_helper_lock() {
  rm -rf /dev/.hapaneld-helper-transaction.lock
}

abort_helper_transaction() {
  signal_status=$1
  trap - 0 1 2 3 15
  release_helper_lock
  exit "$signal_status"
}

acquire_helper_lock() {
  lock=/dev/.hapaneld-helper-transaction.lock
  if ! mkdir "$lock" 2>/dev/null; then
    holder=$(cat "$lock/pid" 2>/dev/null || true)
    case "$holder" in
      ''|*[!0-9]*) return 1 ;;
      *) [ ! -d "/proc/$holder" ] || return 1 ;;
    esac
    rm -rf "$lock" 2>/dev/null || return 1
    mkdir "$lock" 2>/dev/null || return 1
  fi
  echo $$ > "$lock/pid" || { rm -rf "$lock"; return 1; }
  trap release_helper_lock 0
  trap 'abort_helper_transaction 129' 1
  trap 'abort_helper_transaction 130' 2
  trap 'abort_helper_transaction 131' 3
  trap 'abort_helper_transaction 143' 15
}

# Issue #76: a run that failed between staging and commit left its id-named staging behind forever,
# one protected transaction script plus one staged bundle per attempt, and the host's exit-path
# reclamation cannot reach a panel whose transport already died. So, first thing under the
# transaction lock, remove every id-named staging or recovery artifact that is not owned by this transaction,
# not named by the verb argument, and not referenced by a recovery journal. Staged files are only
# ever read by the script instance that owns or names them — a later reconcile reads the journal and
# its transaction-unique recovery copies together with its OWN freshly staged files — so everything else is
# disposable by definition, including staging this same fix could not reclaim on earlier runs.
#
# The lock serializes this against every managed and standalone-manual transaction (both use
# /dev/.hapaneld-helper-transaction.lock). A concurrent provisioner that loses freshly pushed
# staging to this sweep fails early and cleanly at its own prologue reads, before its first
# destructive step — never mid-transaction.
#
# Fail closed twice over: a journal whose TRANSACTION_ID cannot be read as exactly 32 hex characters
# aborts the whole sweep rather than guess what that journal references, and a name whose id segment
# is not exactly 32 hex characters is left alone — which also keeps the standalone daemon
# installer's dot-separated hapaneld-helper.probe-* staging out of reach. Best effort on removal: a
# file that cannot be removed is clutter for the next transaction, never a verb failure.
sweep_disposable_staging() {
  keep=" @TRANSACTION_ID@ $transaction_id "
  for journal in /system/bin/.hapaneld-helper-upgrade /data/adb/hapaneld/.helper-upgrade.marker \
      /data/adb/hapaneld/.helper-hybrid-upgrade.marker; do
    # A marker path that exists but is not a plain regular file — a symlink (broken or not), a
    # directory, a fifo — is a journal whose references cannot be trusted; refuse the whole sweep
    # rather than guess what it protects.
    if [ -L "$journal" ]; then return 0; fi
    if [ -e "$journal" ] && [ ! -f "$journal" ]; then return 0; fi
    [ -f "$journal" ] || continue
    journaled=$(sed -n 's/^TRANSACTION_ID=//p' "$journal" 2>/dev/null)
    case "$journaled" in ''|*[!0-9a-f]*) return 0 ;; esac
    [ "${#journaled}" -eq 32 ] || return 0
    keep="$keep$journaled "
  done
  for staged in /data/local/tmp/hapaneld-helper-* /data/local/.hapaneld-helper.provision-* /data/adb/hapaneld/.helper-probe-* \
      /data/adb/hapaneld/.helper-recovery-*.* /data/adb/hapaneld/.helper-transaction-* \
      /system/bin/.hapaneld-helper-upgrade.cancel-* \
      /data/adb/hapaneld/.helper-upgrade.marker.cancel-* \
      /data/adb/hapaneld/.helper-hybrid-upgrade.marker.cancel-* \
      /system/bin/.hapaneld-helper-upgrade.rollback-intent-* \
      /data/adb/hapaneld/.helper-upgrade.marker.rollback-intent-* \
      /data/adb/hapaneld/.helper-hybrid-upgrade.marker.rollback-intent-* \
      /data/local/hapaneld-helper.rollback-* \
      /system/bin/hapaneld-helper.rollback-* /system/bin/hapaneld-ledd.rollback-* \
      /system/etc/init/hapaneld-helper.rc.rollback-* /system/etc/init/hapaneld-ledd.rc.rollback-* \
      /data/adb/hapaneld/hapaneld-helper.rollback-* \
      /data/adb/service.d/hapaneld-helper.sh.rollback-* \
      /vendor/etc/init/hapaneld-helper.rc.rollback-*; do
    [ -e "$staged" ] || [ -L "$staged" ] || continue
    case "$staged" in
      /data/local/tmp/hapaneld-helper-*) id=${staged#/data/local/tmp/hapaneld-helper-}; id=${id%%.*} ;;
      /data/local/.hapaneld-helper.provision-*) id=${staged#/data/local/.hapaneld-helper.provision-} ;;
      /data/adb/hapaneld/.helper-probe-*) id=${staged#/data/adb/hapaneld/.helper-probe-} ;;
      /data/adb/hapaneld/.helper-recovery-*) id=${staged#/data/adb/hapaneld/.helper-recovery-}; id=${id%%.*} ;;
      /system/bin/.hapaneld-helper-upgrade.cancel-*) id=${staged#/system/bin/.hapaneld-helper-upgrade.cancel-} ;;
      /data/adb/hapaneld/.helper-upgrade.marker.cancel-*) id=${staged#/data/adb/hapaneld/.helper-upgrade.marker.cancel-} ;;
      /data/adb/hapaneld/.helper-hybrid-upgrade.marker.cancel-*) id=${staged#/data/adb/hapaneld/.helper-hybrid-upgrade.marker.cancel-} ;;
      *.rollback-intent-*) id=${staged##*.rollback-intent-} ;;
      *.rollback-v1-*) id=${staged##*.rollback-v1-} ;;
      *.rollback-*) id=${staged##*.rollback-} ;;
      *) id=${staged#/data/adb/hapaneld/.helper-transaction-}; id=${id%%-*} ;;
    esac
    case "$id" in ''|*[!0-9a-f]*) continue ;; esac
    [ "${#id}" -eq 32 ] || continue
    case "$keep" in *" $id "*) continue ;; esac
    rm -f "$staged" 2>/dev/null
  done
  return 0
}

# BundledHelperInstaller streams a candidate into an identity-bound upload and atomically publishes
# it as .hapaneld-helper.new before asking either the incumbent helper or its exact legacy takeover
# authority to consume it. A process death before that second step leaves no transaction authority.
# Reclaim only that pre-authority state, under the shared helper lock and before discovery consults
# app_replacement_custody_present. Anything malformed or accompanied by an authority record remains
# untouched and therefore continues to fail closed as custody.
app_stage_authority_absent() {
  for authority in \
      /data/local/.hapaneld-guard-db/replacement.v1 \
      /data/local/.hapaneld-guard-db/.replacement.v1.tmp \
      /data/local/.hapaneld-helper.legacy-takeover \
      /data/local/.hapaneld-helper.legacy-takeover.tmp \
      /data/local/.hapaneld-helper.previous \
      /data/local/.hapaneld-helper.previous.tmp \
      /system/bin/.hapaneld-helper-upgrade \
      /data/adb/hapaneld/.helper-upgrade.marker \
      /data/adb/hapaneld/.helper-hybrid-upgrade.marker \
      /data/local/.hapaneld-helper-manual-upgrade \
      /system/bin/.hapaneld-helper-manual-upgrade \
      /data/adb/hapaneld/.helper-manual-upgrade.marker; do
    [ ! -e "$authority" ] && [ ! -L "$authority" ] || return 1
  done
  return 0
}

app_stage_metadata() {
  [ -f "$1" ] && [ ! -L "$1" ] || return 1
  stat -c '%d:%i:%u:%g:%a:%h:%s' "$1" 2>/dev/null ||
    toybox stat -c '%d:%i:%u:%g:%a:%h:%s' "$1" 2>/dev/null
}

valid_app_stage_metadata() {
  app_stage_value=$1 app_stage_modes=$2
  app_stage_old_ifs=$IFS
  IFS=:
  set -- $app_stage_value
  IFS=$app_stage_old_ifs
  [ "$#" -eq 7 ] || return 1
  [ "$3:$4:$6" = 0:0:1 ] || return 1
  case ":$app_stage_modes:" in *:"$5":*) ;; *) return 1 ;; esac
  case "$7" in ''|*[!0-9]*) return 1 ;; esac
  [ "$7" -ge 1 ] && [ "$7" -le 16777216 ]
}

legacy_takeover_only_authority() {
  for authority in \
      /data/local/.hapaneld-guard-db/replacement.v1 \
      /data/local/.hapaneld-guard-db/.replacement.v1.tmp \
      /data/local/.hapaneld-helper.legacy-takeover.tmp \
      /data/local/.hapaneld-helper.previous \
      /data/local/.hapaneld-helper.previous.tmp \
      /system/bin/.hapaneld-helper-upgrade \
      /data/adb/hapaneld/.helper-upgrade.marker \
      /data/adb/hapaneld/.helper-hybrid-upgrade.marker \
      /data/local/.hapaneld-helper-manual-upgrade \
      /system/bin/.hapaneld-helper-manual-upgrade \
      /data/adb/hapaneld/.helper-manual-upgrade.marker; do
    [ ! -e "$authority" ] && [ ! -L "$authority" ] || return 1
  done
  return 0
}

reconcile_lone_legacy_takeover_tmp() {
  legacy_tmp_record=/data/local/.hapaneld-helper.legacy-takeover.tmp
  [ ! -e /data/local/.hapaneld-helper.legacy-takeover ] &&
    [ ! -L /data/local/.hapaneld-helper.legacy-takeover ] || return 0
  if [ ! -e "$legacy_tmp_record" ] && [ ! -L "$legacy_tmp_record" ]; then return 0; fi
  # The final record is the first takeover authority publication. Under the shared lock, a lone
  # exact temporary record with every other authority absent is therefore pre-authority regardless
  # of whether the interrupted printf completed; no candidate path/process mutation has crossed.
  for legacy_tmp_foreign in \
      /data/local/.hapaneld-guard-db/replacement.v1 \
      /data/local/.hapaneld-guard-db/.replacement.v1.tmp \
      /data/local/.hapaneld-helper.previous \
      /data/local/.hapaneld-helper.previous.tmp \
      /system/bin/.hapaneld-helper-upgrade \
      /data/adb/hapaneld/.helper-upgrade.marker \
      /data/adb/hapaneld/.helper-hybrid-upgrade.marker \
      /data/local/.hapaneld-helper-manual-upgrade \
      /system/bin/.hapaneld-helper-manual-upgrade \
      /data/adb/hapaneld/.helper-manual-upgrade.marker; do
    [ ! -e "$legacy_tmp_foreign" ] && [ ! -L "$legacy_tmp_foreign" ] || return 1
  done
  if [ -f /data/local/.hapaneld-helper.new ]; then
    legacy_tmp_stage_processes=$(legacy_path_processes /data/local/.hapaneld-helper.new) || return 1
    [ -z "$legacy_tmp_stage_processes" ] || return 1
  fi
  legacy_tmp_before=$(app_stage_metadata "$legacy_tmp_record") || return 1
  legacy_tmp_old_ifs=$IFS
  IFS=:
  set -- $legacy_tmp_before
  IFS=$legacy_tmp_old_ifs
  [ "$#" -eq 7 ] && [ "$3:$4:$5:$6" = 0:0:600:1 ] || return 1
  case "$7" in ''|*[!0-9]*) return 1 ;; esac
  [ "$7" -le 1024 ] || return 1
  legacy_tmp_after=$(app_stage_metadata "$legacy_tmp_record") || return 1
  [ "$legacy_tmp_after" = "$legacy_tmp_before" ] || return 1
  rm -f "$legacy_tmp_record" || return 1
  sync || return 1
  return 0
}

legacy_valid_sha256() {
  case "$1" in ''|*[!0-9a-f]*) return 1 ;; esac
  [ "${#1}" -eq 64 ]
}

legacy_bounded_bytes() {
  case "$1" in ''|*[!0-9]*) return 1 ;; esac
  [ "$1" -ge 1 ] && [ "$1" -le 16777216 ]
}

legacy_exact_file() {
  legacy_path=$1 legacy_sha=$2 legacy_mode=$3 legacy_bytes=$4
  legacy_meta=$(app_stage_metadata "$legacy_path") || return 1
  legacy_meta_old_ifs=$IFS
  IFS=:
  set -- $legacy_meta
  IFS=$legacy_meta_old_ifs
  [ "$#" -eq 7 ] && [ "$3:$4:$5:$6:$7" = "0:0:$legacy_mode:1:$legacy_bytes" ] || return 1
  [ "$(file_sha256 "$legacy_path")" = "$legacy_sha" ]
}

legacy_path_processes() {
  legacy_process_inode=$(stat -c '%d:%i' "$1" 2>/dev/null || toybox stat -c '%d:%i' "$1" 2>/dev/null) || return 1
  legacy_process_found=
  for legacy_executable in /proc/[0-9]*/exe; do
    legacy_executable_inode=$(stat -Lc '%d:%i' "$legacy_executable" 2>/dev/null || toybox stat -L -c '%d:%i' "$legacy_executable" 2>/dev/null) || continue
    [ "$legacy_executable_inode" != "$legacy_process_inode" ] ||
      legacy_process_found="$legacy_process_found ${legacy_executable#/proc/}"
  done
  printf '%s\n' "$legacy_process_found"
}

legacy_stop_path_processes() {
  legacy_processes=$(legacy_path_processes "$1") || return 1
  for legacy_process in $legacy_processes; do
    legacy_process=${legacy_process%/exe}
    kill "$legacy_process" 2>/dev/null || true
  done
  legacy_stop_attempt=0
  while [ "$legacy_stop_attempt" -lt 3 ]; do
    legacy_processes=$(legacy_path_processes "$1") || return 1
    [ -n "$legacy_processes" ] || return 0
    sleep 1
    legacy_stop_attempt=$((legacy_stop_attempt + 1))
  done
  for legacy_process in $legacy_processes; do
    legacy_process=${legacy_process%/exe}
    kill -9 "$legacy_process" 2>/dev/null || true
  done
  sleep 1
  legacy_processes=$(legacy_path_processes "$1") || return 1
  [ -z "$legacy_processes" ]
}

legacy_old_serving() {
  legacy_old_processes=$(legacy_path_processes "$legacy_old_bin") || return 1
  [ -n "$legacy_old_processes" ] &&
    [ "$("$legacy_old_bin" --request BUILDID 2>/dev/null)" = "BUILDID $legacy_incumbent_build" ]
}

legacy_candidate_serving() {
  legacy_candidate_path_serving /data/local/hapaneld-helper
}

legacy_candidate_path_serving() {
  legacy_exact_file "$1" "$legacy_candidate_sha" 700 "$legacy_candidate_bytes" || return 1
  legacy_serving_processes=$(legacy_path_processes "$1") || return 1
  [ -n "$legacy_serving_processes" ] &&
    [ "$("$1" --request GUARDSELF 2>/dev/null)" = "OK GUARDSELF 1 $legacy_candidate_bytes $legacy_candidate_sha $legacy_staged_build" ]
}

legacy_start_old() {
  case "$legacy_topology" in
    system|hybrid) /system/bin/start hapaneld_helper >/dev/null 2>&1 || true ;;
    systemless) "$legacy_old_bin" >/dev/null 2>&1 & ;;
    *) return 1 ;;
  esac
  legacy_start_attempt=0
  while [ "$legacy_start_attempt" -lt 8 ]; do
    legacy_old_serving && return 0
    legacy_start_attempt=$((legacy_start_attempt + 1))
    [ "$legacy_start_attempt" -ge 8 ] || sleep 1
  done
  return 1
}

legacy_restart_candidate() {
  [ "$legacy_candidate_was_running" = 1 ] || return 1
  legacy_stop_path_processes "$legacy_old_bin" 2>/dev/null || return 1
  legacy_exact_file /data/local/hapaneld-helper "$legacy_candidate_sha" 700 "$legacy_candidate_bytes" || return 1
  # A signal can interrupt the original stop while its supervisor still answers GUARDSELF.
  # Fully drain that exact inode before launching a replacement, otherwise a doomed second
  # supervisor can be mistaken for a successful recovery through the dying original's socket.
  legacy_stop_path_processes /data/local/hapaneld-helper || return 1
  legacy_restart_processes=$(legacy_path_processes /data/local/hapaneld-helper) || return 1
  [ -z "$legacy_restart_processes" ] || return 1
  /data/local/hapaneld-helper --supervise >/dev/null 2>&1 &
  legacy_restore_attempt=0
  while [ "$legacy_restore_attempt" -lt 8 ]; do
    legacy_candidate_serving && return 0
    legacy_restore_attempt=$((legacy_restore_attempt + 1))
    [ "$legacy_restore_attempt" -ge 8 ] || sleep 1
  done
  return 1
}

legacy_restore_after_normalization_failure() {
  if [ "${legacy_normalization_phase:-candidate}" = candidate ]; then
    legacy_restart_candidate
  else
    legacy_start_old || legacy_restart_candidate
  fi
}

legacy_restore_signal_traps() {
  trap 'abort_helper_transaction 129' 1
  trap 'abort_helper_transaction 130' 2
  trap 'abort_helper_transaction 131' 3
  trap 'abort_helper_transaction 143' 15
}

legacy_normalization_signal() {
  legacy_signal_status=$1
  trap - 1 2 3 15
  legacy_restore_after_normalization_failure >/dev/null 2>&1 || true
  release_helper_lock
  trap - 0
  exit "$legacy_signal_status"
}

normalize_legacy_takeover() {
  legacy_record=/data/local/.hapaneld-helper.legacy-takeover
  if [ ! -e "$legacy_record" ] && [ ! -L "$legacy_record" ]; then
    [ ! -e "$legacy_record.tmp" ] && [ ! -L "$legacy_record.tmp" ]
    return $?
  fi
  legacy_takeover_only_authority || return 1
  legacy_record_meta=$(app_stage_metadata "$legacy_record") || return 1
  legacy_record_old_ifs=$IFS
  IFS=:
  set -- $legacy_record_meta
  IFS=$legacy_record_old_ifs
  [ "$#" -eq 7 ] && [ "$3:$4:$5:$6" = 0:0:600:1 ] || return 1
  legacy_record_bytes=$7
  case "$legacy_record_bytes" in ''|*[!0-9]*) return 1 ;; esac
  [ "$legacy_record_bytes" -ge 1 ] && [ "$legacy_record_bytes" -le 1024 ] || return 1
  legacy_record_sha=$(file_sha256 "$legacy_record") || return 1
  legacy_record_line=$(cat "$legacy_record") || return 1
  set -f
  set -- $legacy_record_line
  set +f
  [ "$#" -eq 13 ] && [ "$1:$2:$3" = OK:LEGACYTAKEOVER:1 ] || return 1
  legacy_topology=$4
  legacy_old_sha=$5
  legacy_old_bytes=$6
  legacy_registration_sha=$7
  legacy_registration_bytes=$8
  legacy_registration_mode=$9
  legacy_incumbent_build=${10}
  legacy_staged_build=${11}
  legacy_candidate_sha=${12}
  legacy_candidate_bytes=${13}
  [ "$legacy_record_line" = "OK LEGACYTAKEOVER 1 $legacy_topology $legacy_old_sha $legacy_old_bytes $legacy_registration_sha $legacy_registration_bytes $legacy_registration_mode $legacy_incumbent_build $legacy_staged_build $legacy_candidate_sha $legacy_candidate_bytes" ] || return 1
  [ "$legacy_record_bytes" -eq $((${#legacy_record_line} + 1)) ] || return 1
  legacy_valid_sha256 "$legacy_old_sha" && legacy_valid_sha256 "$legacy_registration_sha" &&
    legacy_valid_sha256 "$legacy_incumbent_build" && legacy_valid_sha256 "$legacy_staged_build" &&
    legacy_valid_sha256 "$legacy_candidate_sha" || return 1
  legacy_bounded_bytes "$legacy_old_bytes" && legacy_bounded_bytes "$legacy_registration_bytes" &&
    legacy_bounded_bytes "$legacy_candidate_bytes" || return 1
  [ "$legacy_old_sha" != "$legacy_candidate_sha" ] &&
    [ "$legacy_incumbent_build" != "$legacy_staged_build" ] || return 1
  case "$legacy_topology:$legacy_registration_mode" in
    system:644)
      legacy_old_bin=/system/bin/hapaneld-helper
      legacy_registration=/system/etc/init/hapaneld-helper.rc
      legacy_foreign='/data/adb/hapaneld/hapaneld-helper /data/adb/service.d/hapaneld-helper.sh /vendor/etc/init/hapaneld-helper.rc' ;;
    systemless:755)
      legacy_old_bin=/data/adb/hapaneld/hapaneld-helper
      legacy_registration=/data/adb/service.d/hapaneld-helper.sh
      legacy_foreign='/system/bin/hapaneld-helper /system/etc/init/hapaneld-helper.rc /vendor/etc/init/hapaneld-helper.rc' ;;
    hybrid:644)
      legacy_old_bin=/data/adb/hapaneld/hapaneld-helper
      legacy_registration=/vendor/etc/init/hapaneld-helper.rc
      legacy_foreign='/system/bin/hapaneld-helper /system/etc/init/hapaneld-helper.rc /data/adb/service.d/hapaneld-helper.sh' ;;
    *) return 1 ;;
  esac
  case "$legacy_topology:$legacy_registration_sha" in
    system:9b430712c493df177a19e5e893df445f6c2e951fc30ea140dcdbcdb7987de659|system:1ec2c7baef1b3961f3d8a4c20222fe63c358896238022f4d87bbb5b8b51bdf8e|system:b42a66ff435a830390c7f04e66ffa252e3bf4027e68c72a29002df4886f8d4f4) ;;
    systemless:60ff22aa9b38483cbffd95a653d804d0d9abf682e1b952e8b4519d5c0f3f9493|systemless:cc3eb30416693865345eb241493efaf846c803b9c7370883d0e7eed8101d1411) ;;
    hybrid:cf146dd5320fcb017514def6295fdb0c473e150a478d5c2219af2e3f03826ed1|hybrid:0bdc270e81edee3af5150dd6fe599cb5f3dd0571a7df5214be13ccbbbca33eba) ;;
    *) return 1 ;;
  esac
  legacy_exact_file "$legacy_old_bin" "$legacy_old_sha" 755 "$legacy_old_bytes" &&
    legacy_exact_file "$legacy_registration" "$legacy_registration_sha" "$legacy_registration_mode" "$legacy_registration_bytes" || return 1
  for legacy_absent in $legacy_foreign /system/bin/hapaneld-ledd /system/etc/init/hapaneld-ledd.rc; do
    [ ! -e "$legacy_absent" ] && [ ! -L "$legacy_absent" ] || return 1
  done
  legacy_candidate_present=0
  legacy_candidate_source=
  if [ -e /data/local/hapaneld-helper ] || [ -L /data/local/hapaneld-helper ]; then
    legacy_exact_file /data/local/hapaneld-helper "$legacy_candidate_sha" 700 "$legacy_candidate_bytes" || return 1
    legacy_candidate_present=1
    legacy_candidate_source=/data/local/hapaneld-helper
  fi
  if [ -e /data/local/.hapaneld-helper.new ] || [ -L /data/local/.hapaneld-helper.new ]; then
    legacy_exact_file /data/local/.hapaneld-helper.new "$legacy_candidate_sha" 700 "$legacy_candidate_bytes" || return 1
    legacy_candidate_present=1
    [ -n "${legacy_candidate_source:-}" ] || legacy_candidate_source=/data/local/.hapaneld-helper.new
  fi
  [ "$legacy_candidate_present" = 1 ] || return 1

  legacy_candidate_was_running=0
  legacy_running_candidate_source=
  if [ -f /data/local/hapaneld-helper ]; then
    legacy_live_processes=$(legacy_path_processes /data/local/hapaneld-helper) || return 1
    if [ -n "$legacy_live_processes" ]; then
      legacy_candidate_was_running=1
      legacy_running_candidate_source=/data/local/hapaneld-helper
    fi
  fi
  if [ -f /data/local/.hapaneld-helper.new ]; then
    legacy_stage_processes=$(legacy_path_processes /data/local/.hapaneld-helper.new) || return 1
    if [ -n "$legacy_stage_processes" ]; then
      # The app protocol never executes the staged pathname before final publication.  There is
      # no exact state to restore for such a process, so preserve custody without mutation.
      return 1
    fi
  fi
  if [ "$legacy_candidate_was_running" = 1 ]; then
    legacy_candidate_path_serving "$legacy_running_candidate_source" || return 1
  fi
  legacy_normalization_phase=candidate

  trap 'legacy_normalization_signal 129' 1
  trap 'legacy_normalization_signal 130' 2
  trap 'legacy_normalization_signal 131' 3
  trap 'legacy_normalization_signal 143' 15
  if [ -f /data/local/hapaneld-helper ]; then legacy_stop_path_processes /data/local/hapaneld-helper || { legacy_restore_after_normalization_failure || true; legacy_restore_signal_traps; return 1; }; fi
  if [ -f /data/local/.hapaneld-helper.new ]; then legacy_stop_path_processes /data/local/.hapaneld-helper.new || { legacy_restore_after_normalization_failure || true; legacy_restore_signal_traps; return 1; }; fi
  if [ -f /data/local/hapaneld-helper ]; then
    legacy_exact_file /data/local/hapaneld-helper "$legacy_candidate_sha" 700 "$legacy_candidate_bytes" || { legacy_restore_after_normalization_failure || true; legacy_restore_signal_traps; return 1; }
    legacy_live_processes=$(legacy_path_processes /data/local/hapaneld-helper) || { legacy_restore_after_normalization_failure || true; legacy_restore_signal_traps; return 1; }
    [ -z "$legacy_live_processes" ] || { legacy_restore_after_normalization_failure || true; legacy_restore_signal_traps; return 1; }
  fi
  if [ -f /data/local/.hapaneld-helper.new ]; then
    legacy_exact_file /data/local/.hapaneld-helper.new "$legacy_candidate_sha" 700 "$legacy_candidate_bytes" || { legacy_restore_after_normalization_failure || true; legacy_restore_signal_traps; return 1; }
    legacy_stage_processes=$(legacy_path_processes /data/local/.hapaneld-helper.new) || { legacy_restore_after_normalization_failure || true; legacy_restore_signal_traps; return 1; }
    [ -z "$legacy_stage_processes" ] || { legacy_restore_after_normalization_failure || true; legacy_restore_signal_traps; return 1; }
  fi
  legacy_safe_reply=$("$legacy_candidate_source" --replacement-safe 2>/dev/null)
  legacy_safe_status=$?
  if [ "$legacy_safe_status" -ne 0 ] || [ "$legacy_safe_reply" != REPLACE_SAFE ]; then
    legacy_restore_after_normalization_failure || true
    legacy_restore_signal_traps
    return 1
  fi
  legacy_normalization_phase=old
  if ! legacy_start_old; then
    legacy_restore_after_normalization_failure || true
    legacy_restore_signal_traps
    return 1
  fi
  legacy_restore_signal_traps

  legacy_takeover_only_authority || return 1
  legacy_exact_file "$legacy_old_bin" "$legacy_old_sha" 755 "$legacy_old_bytes" &&
    legacy_exact_file "$legacy_registration" "$legacy_registration_sha" "$legacy_registration_mode" "$legacy_registration_bytes" &&
    legacy_old_serving || return 1
  if [ -f /data/local/hapaneld-helper ]; then
    legacy_exact_file /data/local/hapaneld-helper "$legacy_candidate_sha" 700 "$legacy_candidate_bytes" || return 1
    legacy_live_processes=$(legacy_path_processes /data/local/hapaneld-helper) || return 1
    [ -z "$legacy_live_processes" ] || return 1
  fi
  if [ -f /data/local/.hapaneld-helper.new ]; then
    legacy_exact_file /data/local/.hapaneld-helper.new "$legacy_candidate_sha" 700 "$legacy_candidate_bytes" || return 1
    legacy_stage_processes=$(legacy_path_processes /data/local/.hapaneld-helper.new) || return 1
    [ -z "$legacy_stage_processes" ] || return 1
  fi
  legacy_record_after=$(app_stage_metadata "$legacy_record") || return 1
  [ "$legacy_record_after" = "$legacy_record_meta" ] && [ "$(file_sha256 "$legacy_record")" = "$legacy_record_sha" ] || return 1
  legacy_record_final=$(app_stage_metadata "$legacy_record") || return 1
  [ "$legacy_record_final" = "$legacy_record_meta" ] && [ "$(file_sha256 "$legacy_record")" = "$legacy_record_sha" ] || return 1
  rm -f "$legacy_record" || return 1
  sync || return 1
  return 0
}

reconcile_authority_free_app_staging() {
  [ -d /data/local ] && [ ! -L /data/local ] || return 0
  app_stage_authority_absent || return 0

  app_stage=/data/local/.hapaneld-helper.new
  if [ -e "$app_stage" ] || [ -L "$app_stage" ]; then
    app_stage_before=$(app_stage_metadata "$app_stage") || return 0
    valid_app_stage_metadata "$app_stage_before" 700 || return 0
    app_stage_processes=$(legacy_path_processes "$app_stage") || return 0
    [ -z "$app_stage_processes" ] || return 0
    app_stage_authority_absent || return 0
    app_stage_after=$(app_stage_metadata "$app_stage") || return 0
    [ "$app_stage_after" = "$app_stage_before" ] || return 0
    valid_app_stage_metadata "$app_stage_after" 700 || return 0
    app_stage_processes=$(legacy_path_processes "$app_stage") || return 0
    [ -z "$app_stage_processes" ] || return 0
    rm -f "$app_stage" || return 0
    sync || return 0
  fi

  for app_upload in /data/local/.hapaneld-helper.app-stage-*; do
    [ -e "$app_upload" ] || [ -L "$app_upload" ] || continue
    app_upload_id=${app_upload#/data/local/.hapaneld-helper.app-stage-}
    case "$app_upload_id" in ''|*[!0-9a-f]*) continue ;; esac
    [ "${#app_upload_id}" -eq 64 ] || continue
    app_upload_before=$(app_stage_metadata "$app_upload") || continue
    valid_app_stage_metadata "$app_upload_before" '600:700' || continue
    app_upload_processes=$(legacy_path_processes "$app_upload") || continue
    [ -z "$app_upload_processes" ] || continue
    app_stage_authority_absent || return 0
    app_upload_after=$(app_stage_metadata "$app_upload") || continue
    [ "$app_upload_after" = "$app_upload_before" ] || continue
    valid_app_stage_metadata "$app_upload_after" '600:700' || continue
    app_upload_processes=$(legacy_path_processes "$app_upload") || continue
    [ -z "$app_upload_processes" ] || continue
    rm -f "$app_upload" 2>/dev/null || continue
    sync 2>/dev/null || true
  done
  return 0
}

helper_journal_state() {
  system=0
  systemless=0
  hybrid=0
  [ ! -f /system/bin/.hapaneld-helper-upgrade ] || system=1
  [ ! -f /data/adb/hapaneld/.helper-upgrade.marker ] || systemless=1
  [ ! -f /data/adb/hapaneld/.helper-hybrid-upgrade.marker ] || hybrid=1
  if [ -e /data/local/.hapaneld-helper-manual-upgrade ] || [ -L /data/local/.hapaneld-helper-manual-upgrade ] || \
     [ -e /system/bin/.hapaneld-helper-manual-upgrade ] || [ -L /system/bin/.hapaneld-helper-manual-upgrade ] || \
     [ -e /data/adb/hapaneld/.helper-manual-upgrade.marker ] || [ -L /data/adb/hapaneld/.helper-manual-upgrade.marker ]; then
    echo FOREIGN_MANUAL_TRANSACTION
  elif [ $((system + systemless + hybrid)) -gt 1 ]; then
    echo MULTIPLE_STALE_TRANSACTIONS
  elif [ "$system" = 1 ]; then
    if lease_active /system/bin/.hapaneld-helper-upgrade; then echo ACTIVE_SYSTEM_TRANSACTION; else echo STALE_SYSTEM_TRANSACTION; fi
  elif [ "$systemless" = 1 ]; then
    if lease_active /data/adb/hapaneld/.helper-upgrade.marker; then echo ACTIVE_SYSTEMLESS_TRANSACTION; else echo STALE_SYSTEMLESS_TRANSACTION; fi
  elif [ "$hybrid" = 1 ]; then
    if lease_active /data/adb/hapaneld/.helper-hybrid-upgrade.marker; then echo ACTIVE_HYBRID_TRANSACTION; else echo STALE_HYBRID_TRANSACTION; fi
  elif app_replacement_custody_present; then
    echo APP_REPLACEMENT_HOLD
  else
    echo NO_STALE_TRANSACTION
  fi
}

# `rm -f` is not uniformly forgiving about a path that is not there. On a panel whose /vendor is a
# read-only mount, toybox 0.7.4 still fails on an ABSENT path under it, so a single rm naming
# /vendor/etc/init/hapaneld-helper.rc aborts the install on every panel that never had a vendor rc —
# which is the normal state of an NSPanel 86 (landing panel, 2026-09-20). Remove what is actually
# there, and judge the result by what remains rather than by rm's exit status: a path that is gone
# afterwards is the guarantee this step owes, however the removal was reported.
remove_if_present() {
  for stale in "$@"; do
    if [ -e "$stale" ]; then
      rm -f "$stale"
      if [ -e "$stale" ]; then
        return 1
      fi
    fi
  done
  return 0
}

install_system() {
  marker=/system/bin/.hapaneld-helper-upgrade
  state=$(helper_journal_state)
  [ "$state" = NO_STALE_TRANSACTION ] || { echo "$state"; return 2; }
  mount -o rw,remount / 2>/dev/null
  mount -o rw,remount /system 2>/dev/null
  [ ! -e /vendor/etc/init/hapaneld-helper.rc ] || { echo "INSTALL_STEP_FAILED install_system vendor_rc_present"; return 1; }
  mkdir -p /data/adb/hapaneld || { echo "INSTALL_STEP_FAILED install_system mkdir_hapaneld"; return 1; }
  chown 0:0 /data/adb/hapaneld || { echo "INSTALL_STEP_FAILED install_system chown_hapaneld"; return 1; }
  chmod 700 /data/adb/hapaneld || { echo "INSTALL_STEP_FAILED install_system chmod_hapaneld"; return 1; }

  # Nothing above this point has changed the panel, and nothing below it may run until the panel has
  # proved it can take the replacement. Each directory is measured in its own right, because the
  # route probe only ever tested `/system` and a write one level down can fail for reasons a probe at
  # the parent cannot see: a read-only overmount, an exhausted subtree, a directory that simply
  # refuses the write.
  #
  # The destinations are NOT the ones a reader of the old flow would expect. This layout keeps the
  # helper binary at /data/local and the recovery copies under /data/adb/hapaneld, so the bytes that
  # used to land in /system/bin no longer go there at all; what /system still takes is the boot file
  # alone. Measuring /system for a 76KB binary that is not written there would be theatre, so each
  # requirement below is the sum of the real files this transaction writes to that directory.
  preflight_source install_system @STAGED_HELPER@ @BIN_SHA256@ || return 1
  preflight_source install_system @STAGED_RC@ @RC_SHA256@ || return 1
  data_local_need=$(sum_bytes @STAGED_HELPER@ @STAGED_HELPER@ /data/local/hapaneld-helper)
  recovery_need=$(sum_bytes /data/local/hapaneld-helper /system/bin/hapaneld-helper /system/bin/hapaneld-ledd \
    /system/etc/init/hapaneld-helper.rc /system/etc/init/hapaneld-ledd.rc \
    /data/adb/hapaneld/hapaneld-helper /data/adb/service.d/hapaneld-helper.sh /vendor/etc/init/hapaneld-helper.rc)
  system_init_need=$(sum_bytes @STAGED_RC@ /system/etc/init/hapaneld-helper.rc)
  preflight_target install_system /data/local "$data_local_need" || return 1
  preflight_target install_system /data/adb/hapaneld "$recovery_need" || return 1
  preflight_target install_system /system/etc/init "$system_init_need" || return 1
  # /system/bin is still written by this route even though the helper no longer lives there: the
  # journal marker `$marker` is created here (as `.new`, then renamed), and the old helper is removed
  # from here after retirement. Both happen after the first mutation, so a read-only or full
  # /system/bin reproduced the Issue #120 failure class late, as `INSTALL_STEP_FAILED install_system _`,
  # with transaction artifacts left behind. The journal is a few hundred bytes; two blocks covers the
  # `.new` and the rename with room, and the probe itself proves the directory takes a real write.
  preflight_target install_system /system/bin 8192 || return 1

  candidate=/data/local/.hapaneld-helper.provision-$transaction_id
  rm -f "$candidate" || { echo "INSTALL_STEP_FAILED install_system rm_candidate"; return 1; }
  copy_staged install_system cp_hapaneld-helper_new @STAGED_HELPER@ "$candidate" @BIN_SHA256@ || return 1
  chown 0:0 "$candidate" || { echo "INSTALL_STEP_FAILED install_system chown_hapaneld-helper_new"; return 1; }
  chmod 700 "$candidate" || { echo "INSTALL_STEP_FAILED install_system chmod_hapaneld-helper_new"; return 1; }
  file_exact @BIN_SHA256@ "$candidate" 700 || { echo "INSTALL_STEP_FAILED install_system hash_matches_hapaneld-helper_new"; return 1; }
  copy_staged install_system cp_hapaneld-helper.rc_new @STAGED_RC@ /system/etc/init/hapaneld-helper.rc.new @RC_SHA256@ || return 1
  hash_matches @RC_SHA256@ /system/etc/init/hapaneld-helper.rc.new || { echo "INSTALL_STEP_FAILED install_system hash_matches_hapaneld-helper.rc_new"; return 1; }
  chown 0:0 /system/etc/init/hapaneld-helper.rc.new || { echo "INSTALL_STEP_FAILED install_system chown_hapaneld-helper.rc_new"; return 1; }
  chmod 644 /system/etc/init/hapaneld-helper.rc.new || { echo "INSTALL_STEP_FAILED install_system chmod_hapaneld-helper.rc_new"; return 1; }
  chcon u:object_r:system_file:s0 /system/etc/init/hapaneld-helper.rc.new 2>/dev/null

  live_bin_record=$(snapshot /data/local/hapaneld-helper "$(v2_recovery_path live)" 700) || { echo "INSTALL_STEP_FAILED install_system snapshot_live_bin_record"; return 1; }
  old_bin_record=$(snapshot /system/bin/hapaneld-helper "$(v2_recovery_path sysbin)" 755) || { echo "INSTALL_STEP_FAILED install_system snapshot_old_bin_record"; return 1; }
  old_service_record=$(snapshot /system/etc/init/hapaneld-helper.rc "$(v2_recovery_path sysrc)" 644) || { echo "INSTALL_STEP_FAILED install_system snapshot_old_service_record"; return 1; }
  legacy_bin_record=$(snapshot /system/bin/hapaneld-ledd "$(v2_recovery_path legacybin)" 755) || { echo "INSTALL_STEP_FAILED install_system snapshot_legacy_bin_record"; return 1; }
  legacy_service_record=$(snapshot /system/etc/init/hapaneld-ledd.rc "$(v2_recovery_path legacyrc)" 644) || { echo "INSTALL_STEP_FAILED install_system snapshot_legacy_service_record"; return 1; }
  alt_bin_record=$(snapshot /data/adb/hapaneld/hapaneld-helper "$(v2_recovery_path databin)" 755) || { echo "INSTALL_STEP_FAILED install_system snapshot_alt_bin_record"; return 1; }
  alt_service_record=$(snapshot /data/adb/service.d/hapaneld-helper.sh "$(v2_recovery_path datasvc)" 755) || { echo "INSTALL_STEP_FAILED install_system snapshot_alt_service_record"; return 1; }
  vendor_rc_record=$(snapshot /vendor/etc/init/hapaneld-helper.rc "$(v2_recovery_path vendorrc)" 644) || { echo "INSTALL_STEP_FAILED install_system snapshot_vendor_rc_record"; return 1; }
  live_bin=${live_bin_record%% *}; live_bin_sha=${live_bin_record#* }
  old_bin=${old_bin_record%% *}; old_bin_sha=${old_bin_record#* }
  old_service=${old_service_record%% *}; old_service_sha=${old_service_record#* }
  legacy_bin=${legacy_bin_record%% *}; legacy_bin_sha=${legacy_bin_record#* }
  legacy_service=${legacy_service_record%% *}; legacy_service_sha=${legacy_service_record#* }
  alt_bin=${alt_bin_record%% *}; alt_bin_sha=${alt_bin_record#* }
  alt_service=${alt_service_record%% *}; alt_service_sha=${alt_service_record#* }
  vendor_rc=${vendor_rc_record%% *}; vendor_rc_sha=${vendor_rc_record#* }
  current_boot=$(boot_id) || { echo "INSTALL_STEP_FAILED install_system boot_id_current_boot"; return 1; }
  now=$(uptime_seconds) || { echo "INSTALL_STEP_FAILED install_system uptime_seconds_now"; return 1; }
  lease_until=$((now + 600))

  {
    echo JOURNAL_VERSION=2
    echo JOURNAL_SCOPE=APK_HELPER
    echo TRANSACTION_ID=@TRANSACTION_ID@
    echo TARGET_APK_SHA256=@APK_SHA256@
    echo TARGET_BUILD_ID=@BUILD_ID@
    echo TARGET_HELPER_SHA256=@BIN_SHA256@
    echo TARGET_BOOT_SHA256=@RC_SHA256@
    echo BOOT_KIND=system
    echo LIVE_BIN=$live_bin
    echo LIVE_BIN_SHA256=$live_bin_sha
    echo SYS_BIN=$old_bin
    echo SYS_BIN_SHA256=$old_bin_sha
    echo SYS_RC=$old_service
    echo SYS_RC_SHA256=$old_service_sha
    echo DATA_BIN=$alt_bin
    echo DATA_BIN_SHA256=$alt_bin_sha
    echo DATA_SERVICE=$alt_service
    echo DATA_SERVICE_SHA256=$alt_service_sha
    echo VENDOR_RC=$vendor_rc
    echo VENDOR_RC_SHA256=$vendor_rc_sha
    echo LEGACY_BIN=$legacy_bin
    echo LEGACY_BIN_SHA256=$legacy_bin_sha
    echo LEGACY_SERVICE=$legacy_service
    echo LEGACY_SERVICE_SHA256=$legacy_service_sha
    echo LEASE_BOOT_ID=$current_boot
    echo LEASE_UNTIL_UPTIME=$lease_until
  } > "$marker.new" || { echo "INSTALL_STEP_FAILED install_system _"; return 1; }
  chown 0:0 "$marker.new" || { echo "INSTALL_STEP_FAILED install_system chown"; return 1; }
  chmod 600 "$marker.new" || { echo "INSTALL_STEP_FAILED install_system chmod"; return 1; }
  sync || { echo "INSTALL_STEP_FAILED install_system sync"; return 1; }
  mv -f "$marker.new" "$marker" || { echo "INSTALL_STEP_FAILED install_system mv"; return 1; }
  sync || { echo "INSTALL_STEP_FAILED install_system sync"; return 1; }

  # The swap has not started yet, so a failure here leaves every live file and the installed APK
  # exactly as they were. Say so in the reply: the host must not offer to roll back a panel that
  # was never changed, nor tell its owner to repair a helper that is still the one they had.
  retire_helpers || { echo "INSTALL_UNCHANGED install_system helper_retirement"; return 1; }
  replacement_safe_after_retirement system "$marker" "$candidate" || return $?
  mv -f "$candidate" /data/local/hapaneld-helper || { echo "INSTALL_STEP_FAILED install_system mv_hapaneld-helper"; return 1; }
  sync || { echo "INSTALL_STEP_FAILED install_system sync"; return 1; }
  mv -f /system/etc/init/hapaneld-helper.rc.new /system/etc/init/hapaneld-helper.rc || { echo "INSTALL_STEP_FAILED install_system mv_hapaneld-helper.rc"; return 1; }
  sync || { echo "INSTALL_STEP_FAILED install_system sync"; return 1; }
  remove_if_present /system/bin/hapaneld-helper \
    /system/bin/hapaneld-ledd /system/etc/init/hapaneld-ledd.rc \
    /data/adb/hapaneld/hapaneld-helper /data/adb/service.d/hapaneld-helper.sh \
    /vendor/etc/init/hapaneld-helper.rc || { echo "INSTALL_STEP_FAILED install_system remove_noncanonical_helpers"; return 1; }
  sync || { echo "INSTALL_STEP_FAILED install_system sync"; return 1; }
  v2_target system "$marker" || { echo "INSTALL_STEP_FAILED install_system v2_target"; return 1; }
  echo INSTALL_OK
}

install_systemless() {
  marker=/data/adb/hapaneld/.helper-upgrade.marker
  state=$(helper_journal_state)
  [ "$state" = NO_STALE_TRANSACTION ] || { echo "$state"; return 2; }
  mkdir -p /data/adb/service.d /data/adb/hapaneld || { echo "INSTALL_STEP_FAILED install_systemless mkdir_hapaneld"; return 1; }
  chown 0:0 /data/adb/hapaneld || { echo "INSTALL_STEP_FAILED install_systemless chown_hapaneld"; return 1; }
  chmod 700 /data/adb/hapaneld || { echo "INSTALL_STEP_FAILED install_systemless chmod_hapaneld"; return 1; }
  [ ! -e /vendor/etc/init/hapaneld-helper.rc ] && [ ! -L /vendor/etc/init/hapaneld-helper.rc ] &&
    [ ! -e /system/bin/hapaneld-helper ] && [ ! -L /system/bin/hapaneld-helper ] &&
    [ ! -e /system/etc/init/hapaneld-helper.rc ] && [ ! -L /system/etc/init/hapaneld-helper.rc ] &&
    [ ! -e /system/bin/hapaneld-ledd ] && [ ! -L /system/bin/hapaneld-ledd ] &&
    [ ! -e /system/etc/init/hapaneld-ledd.rc ] && [ ! -L /system/etc/init/hapaneld-ledd.rc ] || { echo "INSTALL_STEP_FAILED install_systemless noncanonical_paths_present"; return 1; }

  # Nothing above this point has changed the panel. This route writes to three directories under
  # /data, and each is measured in its own right before anything is removed or replaced.
  preflight_source install_systemless @STAGED_HELPER@ @BIN_SHA256@ || return 1
  preflight_source install_systemless @STAGED_SERVICE@ @SERVICE_SHA256@ || return 1
  data_local_need=$(sum_bytes @STAGED_HELPER@ @STAGED_HELPER@ /data/local/hapaneld-helper)
  recovery_need=$(sum_bytes /data/local/hapaneld-helper /data/adb/hapaneld/hapaneld-helper /data/adb/service.d/hapaneld-helper.sh)
  service_d_need=$(sum_bytes @STAGED_SERVICE@ /data/adb/service.d/hapaneld-helper.sh)
  preflight_target install_systemless /data/local "$data_local_need" || return 1
  preflight_target install_systemless /data/adb/hapaneld "$recovery_need" || return 1
  preflight_target install_systemless /data/adb/service.d "$service_d_need" || return 1

  candidate=/data/local/.hapaneld-helper.provision-$transaction_id
  rm -f "$candidate" || { echo "INSTALL_STEP_FAILED install_systemless rm_candidate"; return 1; }
  copy_staged install_systemless cp_hapaneld-helper_new @STAGED_HELPER@ "$candidate" @BIN_SHA256@ || return 1
  chown 0:0 "$candidate" || { echo "INSTALL_STEP_FAILED install_systemless chown_hapaneld-helper_new"; return 1; }
  chmod 700 "$candidate" || { echo "INSTALL_STEP_FAILED install_systemless chmod_hapaneld-helper_new"; return 1; }
  file_exact @BIN_SHA256@ "$candidate" 700 || { echo "INSTALL_STEP_FAILED install_systemless hash_matches_hapaneld-helper_new"; return 1; }
  copy_staged install_systemless cp_hapaneld-helper.sh_new @STAGED_SERVICE@ /data/adb/service.d/hapaneld-helper.sh.new @SERVICE_SHA256@ || return 1
  hash_matches @SERVICE_SHA256@ /data/adb/service.d/hapaneld-helper.sh.new || { echo "INSTALL_STEP_FAILED install_systemless hash_matches_hapaneld-helper.sh_new"; return 1; }
  chown 0:0 /data/adb/service.d/hapaneld-helper.sh.new || { echo "INSTALL_STEP_FAILED install_systemless chown_hapaneld-helper.sh_new"; return 1; }
  chmod 755 /data/adb/service.d/hapaneld-helper.sh.new || { echo "INSTALL_STEP_FAILED install_systemless chmod_hapaneld-helper.sh_new"; return 1; }

  live_bin_record=$(snapshot /data/local/hapaneld-helper "$(v2_recovery_path live)" 700) || { echo "INSTALL_STEP_FAILED install_systemless snapshot_live_bin_record"; return 1; }
  old_bin_record=$(snapshot /data/adb/hapaneld/hapaneld-helper "$(v2_recovery_path databin)" 755) || { echo "INSTALL_STEP_FAILED install_systemless snapshot_old_bin_record"; return 1; }
  old_service_record=$(snapshot /data/adb/service.d/hapaneld-helper.sh "$(v2_recovery_path datasvc)" 755) || { echo "INSTALL_STEP_FAILED install_systemless snapshot_old_service_record"; return 1; }
  sys_bin_record=$(snapshot /system/bin/hapaneld-helper "$(v2_recovery_path sysbin)" 755) || { echo "INSTALL_STEP_FAILED install_systemless snapshot_sys_bin_record"; return 1; }
  sys_rc_record=$(snapshot /system/etc/init/hapaneld-helper.rc "$(v2_recovery_path sysrc)" 644) || { echo "INSTALL_STEP_FAILED install_systemless snapshot_sys_rc_record"; return 1; }
  vendor_rc_record=$(snapshot /vendor/etc/init/hapaneld-helper.rc "$(v2_recovery_path vendorrc)" 644) || { echo "INSTALL_STEP_FAILED install_systemless snapshot_vendor_rc_record"; return 1; }
  legacy_bin_record=$(snapshot /system/bin/hapaneld-ledd "$(v2_recovery_path legacybin)" 755) || { echo "INSTALL_STEP_FAILED install_systemless snapshot_legacy_bin_record"; return 1; }
  legacy_service_record=$(snapshot /system/etc/init/hapaneld-ledd.rc "$(v2_recovery_path legacyrc)" 644) || { echo "INSTALL_STEP_FAILED install_systemless snapshot_legacy_service_record"; return 1; }
  live_bin=${live_bin_record%% *}; live_bin_sha=${live_bin_record#* }
  old_bin=${old_bin_record%% *}; old_bin_sha=${old_bin_record#* }
  old_service=${old_service_record%% *}; old_service_sha=${old_service_record#* }
  sys_bin=${sys_bin_record%% *}; sys_bin_sha=${sys_bin_record#* }
  sys_rc=${sys_rc_record%% *}; sys_rc_sha=${sys_rc_record#* }
  vendor_rc=${vendor_rc_record%% *}; vendor_rc_sha=${vendor_rc_record#* }
  legacy_bin=${legacy_bin_record%% *}; legacy_bin_sha=${legacy_bin_record#* }
  legacy_service=${legacy_service_record%% *}; legacy_service_sha=${legacy_service_record#* }
  current_boot=$(boot_id) || { echo "INSTALL_STEP_FAILED install_systemless boot_id_current_boot"; return 1; }
  now=$(uptime_seconds) || { echo "INSTALL_STEP_FAILED install_systemless uptime_seconds_now"; return 1; }
  lease_until=$((now + 600))
  {
    echo JOURNAL_VERSION=2
    echo JOURNAL_SCOPE=APK_HELPER
    echo TRANSACTION_ID=@TRANSACTION_ID@
    echo TARGET_APK_SHA256=@APK_SHA256@
    echo TARGET_BUILD_ID=@BUILD_ID@
    echo TARGET_HELPER_SHA256=@BIN_SHA256@
    echo TARGET_BOOT_SHA256=@SERVICE_SHA256@
    echo BOOT_KIND=systemless
    echo LIVE_BIN=$live_bin
    echo LIVE_BIN_SHA256=$live_bin_sha
    echo SYS_BIN=$sys_bin
    echo SYS_BIN_SHA256=$sys_bin_sha
    echo SYS_RC=$sys_rc
    echo SYS_RC_SHA256=$sys_rc_sha
    echo DATA_BIN=$old_bin
    echo DATA_BIN_SHA256=$old_bin_sha
    echo DATA_SERVICE=$old_service
    echo DATA_SERVICE_SHA256=$old_service_sha
    echo VENDOR_RC=$vendor_rc
    echo VENDOR_RC_SHA256=$vendor_rc_sha
    echo LEGACY_BIN=$legacy_bin
    echo LEGACY_BIN_SHA256=$legacy_bin_sha
    echo LEGACY_SERVICE=$legacy_service
    echo LEGACY_SERVICE_SHA256=$legacy_service_sha
    echo LEASE_BOOT_ID=$current_boot
    echo LEASE_UNTIL_UPTIME=$lease_until
  } > "$marker.new" || { echo "INSTALL_STEP_FAILED install_systemless _"; return 1; }
  chown 0:0 "$marker.new" || { echo "INSTALL_STEP_FAILED install_systemless chown"; return 1; }
  chmod 600 "$marker.new" || { echo "INSTALL_STEP_FAILED install_systemless chmod"; return 1; }
  sync || { echo "INSTALL_STEP_FAILED install_systemless sync"; return 1; }
  mv -f "$marker.new" "$marker" || { echo "INSTALL_STEP_FAILED install_systemless mv"; return 1; }
  sync || { echo "INSTALL_STEP_FAILED install_systemless sync"; return 1; }

  # The swap has not started yet, so a failure here leaves every live file and the installed APK
  # exactly as they were. Say so in the reply: the host must not offer to roll back a panel that
  # was never changed, nor tell its owner to repair a helper that is still the one they had.
  retire_helpers || { echo "INSTALL_UNCHANGED install_systemless helper_retirement"; return 1; }
  replacement_safe_after_retirement systemless "$marker" "$candidate" || return $?
  mv -f "$candidate" /data/local/hapaneld-helper || { echo "INSTALL_STEP_FAILED install_systemless mv_hapaneld-helper"; return 1; }
  sync || { echo "INSTALL_STEP_FAILED install_systemless sync"; return 1; }
  mv -f /data/adb/service.d/hapaneld-helper.sh.new /data/adb/service.d/hapaneld-helper.sh || { echo "INSTALL_STEP_FAILED install_systemless mv_hapaneld-helper.sh"; return 1; }
  sync || { echo "INSTALL_STEP_FAILED install_systemless sync"; return 1; }
  remove_if_present /data/adb/hapaneld/hapaneld-helper || { echo "INSTALL_STEP_FAILED install_systemless remove_noncanonical_helpers"; return 1; }
  sync || { echo "INSTALL_STEP_FAILED install_systemless sync"; return 1; }
  v2_target systemless "$marker" || { echo "INSTALL_STEP_FAILED install_systemless v2_target"; return 1; }
  echo INSTALL_OK
}

# Keep the init-owned boot vector on /vendor while the sealed helper binary lives at the canonical
# /data/local path and every recovery artifact lives under /data. An existing vendor vector must
# already be an exact recognized definition; unknown
# content is never adopted or overwritten.
install_hybrid() {
  marker=/data/adb/hapaneld/.helper-hybrid-upgrade.marker
  state=$(helper_journal_state)
  [ "$state" = NO_STALE_TRANSACTION ] || { echo "$state"; return 2; }
  mount -o rw,remount / 2>/dev/null
  mount -o rw,remount /system 2>/dev/null
  mount -o rw,remount /vendor 2>/dev/null
  mkdir -p /data/adb/hapaneld || { echo "INSTALL_STEP_FAILED install_hybrid mkdir_hapaneld"; return 1; }
  chown 0:0 /data/adb/hapaneld || { echo "INSTALL_STEP_FAILED install_hybrid chown_hapaneld"; return 1; }
  chmod 700 /data/adb/hapaneld || { echo "INSTALL_STEP_FAILED install_hybrid chmod_hapaneld"; return 1; }
  if [ -e /vendor/etc/init/hapaneld-helper.rc ]; then
    root_owned /vendor/etc/init/hapaneld-helper.rc || { echo "INSTALL_STEP_FAILED install_hybrid root_owned_hapaneld-helper.rc"; return 1; }
    hash_matches @HYBRID_RC_SHA256@ /vendor/etc/init/hapaneld-helper.rc ||
      hash_matches_known_legacy /vendor/etc/init/hapaneld-helper.rc @LEGACY_HYBRID_RC_SHA256@ @LEGACY_HYBRID_RC_SUPERVISED_SHA256@ || { echo "INSTALL_STEP_FAILED install_hybrid hash_matches_hapaneld-helper.rc"; return 1; }
  fi

  # Nothing above this point has changed the panel. The boot file goes to /vendor/etc/init and the
  # helper and recovery copies to /data; each directory is measured in its own right.
  preflight_source install_hybrid @STAGED_HELPER@ @BIN_SHA256@ || return 1
  preflight_source install_hybrid @STAGED_HYBRID_RC@ @HYBRID_RC_SHA256@ || return 1
  data_local_need=$(sum_bytes @STAGED_HELPER@ @STAGED_HELPER@ /data/local/hapaneld-helper)
  recovery_need=$(sum_bytes /data/local/hapaneld-helper /system/bin/hapaneld-helper /system/bin/hapaneld-ledd \
    /system/etc/init/hapaneld-helper.rc /system/etc/init/hapaneld-ledd.rc \
    /data/adb/hapaneld/hapaneld-helper /data/adb/service.d/hapaneld-helper.sh /vendor/etc/init/hapaneld-helper.rc)
  vendor_init_need=$(sum_bytes @STAGED_HYBRID_RC@ /vendor/etc/init/hapaneld-helper.rc)
  preflight_target install_hybrid /data/local "$data_local_need" || return 1
  preflight_target install_hybrid /data/adb/hapaneld "$recovery_need" || return 1
  preflight_target install_hybrid /vendor/etc/init "$vendor_init_need" || return 1
  # This route removes an old system-layout helper from /system/bin after retirement and fails the
  # transaction if that removal is refused, so a read-only /system/bin must be caught here rather
  # than there. No headroom is needed for a removal; the write probe alone proves writability.
  preflight_target install_hybrid /system/bin 0 || return 1

  candidate=/data/local/.hapaneld-helper.provision-$transaction_id
  rm -f "$candidate" || { echo "INSTALL_STEP_FAILED install_hybrid rm_candidate"; return 1; }
  copy_staged install_hybrid cp_hapaneld-helper_new @STAGED_HELPER@ "$candidate" @BIN_SHA256@ || return 1
  chown 0:0 "$candidate" || { echo "INSTALL_STEP_FAILED install_hybrid chown_hapaneld-helper_new"; return 1; }
  chmod 700 "$candidate" || { echo "INSTALL_STEP_FAILED install_hybrid chmod_hapaneld-helper_new"; return 1; }
  file_exact @BIN_SHA256@ "$candidate" 700 || { echo "INSTALL_STEP_FAILED install_hybrid hash_matches_hapaneld-helper_new"; return 1; }
  copy_staged install_hybrid cp_hapaneld-helper.rc_new @STAGED_HYBRID_RC@ /vendor/etc/init/hapaneld-helper.rc.new @HYBRID_RC_SHA256@ || return 1
  hash_matches @HYBRID_RC_SHA256@ /vendor/etc/init/hapaneld-helper.rc.new || { echo "INSTALL_STEP_FAILED install_hybrid hash_matches_hapaneld-helper.rc_new"; return 1; }
  chown 0:0 /vendor/etc/init/hapaneld-helper.rc.new || { echo "INSTALL_STEP_FAILED install_hybrid chown_hapaneld-helper.rc_new"; return 1; }
  chmod 644 /vendor/etc/init/hapaneld-helper.rc.new || { echo "INSTALL_STEP_FAILED install_hybrid chmod_hapaneld-helper.rc_new"; return 1; }
  chcon u:object_r:vendor_configs_file:s0 /vendor/etc/init/hapaneld-helper.rc.new 2>/dev/null

  live_bin_record=$(snapshot /data/local/hapaneld-helper "$(v2_recovery_path live)" 700) || { echo "INSTALL_STEP_FAILED install_hybrid snapshot_live_bin_record"; return 1; }
  old_bin_record=$(snapshot /data/adb/hapaneld/hapaneld-helper "$(v2_recovery_path databin)" 755) || { echo "INSTALL_STEP_FAILED install_hybrid snapshot_old_bin_record"; return 1; }
  old_rc_record=$(snapshot /vendor/etc/init/hapaneld-helper.rc "$(v2_recovery_path vendorrc)" 644) || { echo "INSTALL_STEP_FAILED install_hybrid snapshot_old_rc_record"; return 1; }
  sys_rc_record=$(snapshot /system/etc/init/hapaneld-helper.rc "$(v2_recovery_path sysrc)" 644) || { echo "INSTALL_STEP_FAILED install_hybrid snapshot_sys_rc_record"; return 1; }
  sys_bin_record=$(snapshot /system/bin/hapaneld-helper "$(v2_recovery_path sysbin)" 755) || { echo "INSTALL_STEP_FAILED install_hybrid snapshot_sys_bin_record"; return 1; }
  legacy_bin_record=$(snapshot /system/bin/hapaneld-ledd "$(v2_recovery_path legacybin)" 755) || { echo "INSTALL_STEP_FAILED install_hybrid snapshot_legacy_bin_record"; return 1; }
  legacy_service_record=$(snapshot /system/etc/init/hapaneld-ledd.rc "$(v2_recovery_path legacyrc)" 644) || { echo "INSTALL_STEP_FAILED install_hybrid snapshot_legacy_service_record"; return 1; }
  alt_service_record=$(snapshot /data/adb/service.d/hapaneld-helper.sh "$(v2_recovery_path datasvc)" 755) || { echo "INSTALL_STEP_FAILED install_hybrid snapshot_alt_service_record"; return 1; }
  live_bin=${live_bin_record%% *}; live_bin_sha=${live_bin_record#* }
  old_bin=${old_bin_record%% *}; old_bin_sha=${old_bin_record#* }
  old_rc=${old_rc_record%% *}; old_rc_sha=${old_rc_record#* }
  sys_rc=${sys_rc_record%% *}; sys_rc_sha=${sys_rc_record#* }
  sys_bin=${sys_bin_record%% *}; sys_bin_sha=${sys_bin_record#* }
  legacy_bin=${legacy_bin_record%% *}; legacy_bin_sha=${legacy_bin_record#* }
  legacy_service=${legacy_service_record%% *}; legacy_service_sha=${legacy_service_record#* }
  alt_service=${alt_service_record%% *}; alt_service_sha=${alt_service_record#* }
  current_boot=$(boot_id) || { echo "INSTALL_STEP_FAILED install_hybrid boot_id_current_boot"; return 1; }
  now=$(uptime_seconds) || { echo "INSTALL_STEP_FAILED install_hybrid uptime_seconds_now"; return 1; }
  lease_until=$((now + 600))

  {
    echo JOURNAL_VERSION=2
    echo JOURNAL_SCOPE=APK_HELPER
    echo TRANSACTION_ID=@TRANSACTION_ID@
    echo TARGET_APK_SHA256=@APK_SHA256@
    echo TARGET_BUILD_ID=@BUILD_ID@
    echo TARGET_HELPER_SHA256=@BIN_SHA256@
    echo TARGET_BOOT_SHA256=@HYBRID_RC_SHA256@
    echo BOOT_KIND=hybrid
    echo LIVE_BIN=$live_bin
    echo LIVE_BIN_SHA256=$live_bin_sha
    echo SYS_RC=$sys_rc
    echo SYS_RC_SHA256=$sys_rc_sha
    echo SYS_BIN=$sys_bin
    echo SYS_BIN_SHA256=$sys_bin_sha
    echo DATA_BIN=$old_bin
    echo DATA_BIN_SHA256=$old_bin_sha
    echo DATA_SERVICE=$alt_service
    echo DATA_SERVICE_SHA256=$alt_service_sha
    echo VENDOR_RC=$old_rc
    echo VENDOR_RC_SHA256=$old_rc_sha
    echo LEGACY_BIN=$legacy_bin
    echo LEGACY_BIN_SHA256=$legacy_bin_sha
    echo LEGACY_SERVICE=$legacy_service
    echo LEGACY_SERVICE_SHA256=$legacy_service_sha
    echo LEASE_BOOT_ID=$current_boot
    echo LEASE_UNTIL_UPTIME=$lease_until
  } > "$marker.new" || { echo "INSTALL_STEP_FAILED install_hybrid _"; return 1; }
  chown 0:0 "$marker.new" || { echo "INSTALL_STEP_FAILED install_hybrid chown"; return 1; }
  chmod 600 "$marker.new" || { echo "INSTALL_STEP_FAILED install_hybrid chmod"; return 1; }
  sync || { echo "INSTALL_STEP_FAILED install_hybrid sync"; return 1; }
  mv -f "$marker.new" "$marker" || { echo "INSTALL_STEP_FAILED install_hybrid mv"; return 1; }
  sync || { echo "INSTALL_STEP_FAILED install_hybrid sync"; return 1; }

  # The swap has not started yet, so a failure here leaves every live file and the installed APK
  # exactly as they were. Say so in the reply: the host must not offer to roll back a panel that
  # was never changed, nor tell its owner to repair a helper that is still the one they had.
  retire_helpers || { echo "INSTALL_UNCHANGED install_hybrid helper_retirement"; return 1; }
  replacement_safe_after_retirement hybrid "$marker" "$candidate" || return $?
  mv -f "$candidate" /data/local/hapaneld-helper || { echo "INSTALL_STEP_FAILED install_hybrid mv_hapaneld-helper"; return 1; }
  sync || { echo "INSTALL_STEP_FAILED install_hybrid sync"; return 1; }
  mv -f /vendor/etc/init/hapaneld-helper.rc.new /vendor/etc/init/hapaneld-helper.rc || { echo "INSTALL_STEP_FAILED install_hybrid mv_hapaneld-helper.rc"; return 1; }
  sync || { echo "INSTALL_STEP_FAILED install_hybrid sync"; return 1; }
  remove_if_present /system/etc/init/hapaneld-helper.rc /system/bin/hapaneld-helper \
    /system/bin/hapaneld-ledd /system/etc/init/hapaneld-ledd.rc \
    /data/adb/hapaneld/hapaneld-helper /data/adb/service.d/hapaneld-helper.sh || { echo "INSTALL_STEP_FAILED install_hybrid remove_noncanonical_helpers"; return 1; }
  [ ! -e /system/etc/init/hapaneld-helper.rc ] && [ ! -e /system/bin/hapaneld-helper ] && \
    [ ! -e /system/bin/hapaneld-ledd ] && [ ! -e /system/etc/init/hapaneld-ledd.rc ] && \
    [ ! -e /data/adb/hapaneld/hapaneld-helper ] && [ ! -e /data/adb/service.d/hapaneld-helper.sh ] || { echo "INSTALL_STEP_FAILED install_hybrid verify_noncanonical_helpers_absent"; return 1; }
  sync || { echo "INSTALL_STEP_FAILED install_hybrid sync"; return 1; }
  v2_target hybrid "$marker" || { echo "INSTALL_STEP_FAILED install_hybrid v2_target"; return 1; }
  echo INSTALL_OK
}

retire_helpers() {
  stop hapaneld_helper 2>/dev/null
  stop hapaneld_ledd 2>/dev/null
  pkill -x hapaneld-helper 2>/dev/null
  pkill -x hapaneld-ledd 2>/dev/null
  rh_attempt=0
  while [ "$rh_attempt" -lt 10 ]; do
    if ! pidof hapaneld-helper >/dev/null 2>&1 && ! pidof hapaneld-ledd >/dev/null 2>&1; then
      return 0
    fi
    if [ "$rh_attempt" -ge 2 ]; then
      stop hapaneld_helper 2>/dev/null
      stop hapaneld_ledd 2>/dev/null
    fi
    if [ "$rh_attempt" -ge 4 ]; then
      pkill -KILL -x hapaneld-helper 2>/dev/null
      pkill -KILL -x hapaneld-ledd 2>/dev/null
    fi
    rh_attempt=$((rh_attempt + 1))
    sleep 1
  done
  rh_helper_pids=$(pidof hapaneld-helper 2>/dev/null)
  rh_ledd_pids=$(pidof hapaneld-ledd 2>/dev/null)
  rh_init_helper=$(getprop init.svc.hapaneld_helper 2>/dev/null)
  rh_init_ledd=$(getprop init.svc.hapaneld_ledd 2>/dev/null)
  echo "RETIREMENT_TIMEOUT helper_pids=${rh_helper_pids:-none} ledd_pids=${rh_ledd_pids:-none} init_helper=${rh_init_helper:-unset} init_ledd=${rh_init_ledd:-unset}"
  return 1
}

rollback_system() {
  mount -o rw,remount / 2>/dev/null
  mount -o rw,remount /system 2>/dev/null
  marker=/system/bin/.hapaneld-helper-upgrade
  [ -f "$marker" ] || { echo ROLLBACK_UNNEEDED; return 0; }
  grep -q ^JOURNAL_SCOPE=APK_HELPER$ "$marker" || return 1
  valid_transaction_identity "$marker" "$transaction_id" "$target_apk" "$target_build" "$target_helper" || return 1
  state=$(classify_system)
  if grep -q ^JOURNAL_VERSION=2$ "$marker"; then
    rollback_v2 system "$marker" "$state"
    return
  fi
  grep -q ^JOURNAL_VERSION=1$ "$marker" || return 1
  [ "$state" = PRE_SWAP ] || [ "$state" = TARGET ] || [ "$state" = TRANSITION ] || return 1
  flag OLD_BIN "$marker" && valid_recovery OLD_BIN /system/bin/hapaneld-helper.hapaneld-recovery "$marker" || ! flag OLD_BIN "$marker" || return 1
  flag OLD_SERVICE "$marker" && valid_recovery OLD_SERVICE /system/etc/init/hapaneld-helper.rc.hapaneld-recovery "$marker" || ! flag OLD_SERVICE "$marker" || return 1
  flag LEGACY_BIN "$marker" && valid_recovery LEGACY_BIN /system/bin/hapaneld-ledd.hapaneld-recovery "$marker" || ! flag LEGACY_BIN "$marker" || return 1
  flag LEGACY_SERVICE "$marker" && valid_recovery LEGACY_SERVICE /system/etc/init/hapaneld-ledd.rc.hapaneld-recovery "$marker" || ! flag LEGACY_SERVICE "$marker" || return 1
  flag ALT_BIN "$marker" && valid_recovery ALT_BIN /data/adb/hapaneld/hapaneld-helper.hapaneld-recovery "$marker" || ! flag ALT_BIN "$marker" || return 1
  flag ALT_SERVICE "$marker" && valid_recovery ALT_SERVICE /data/adb/service.d/hapaneld-helper.sh.hapaneld-recovery "$marker" || ! flag ALT_SERVICE "$marker" || return 1

  probe=/data/adb/hapaneld/.helper-probe-$transaction_id
  rm -f "$probe"
  hash_matches @BIN_SHA256@ @STAGED_HELPER@ || return 1
  cp @STAGED_HELPER@ "$probe" || return 1
  chown 0:0 "$probe" || return 1
  chmod 700 "$probe" || return 1
  hash_matches @BIN_SHA256@ "$probe" || return 1

  retire_helpers || return 1
  state=$(classify_system)
  [ "$state" = PRE_SWAP ] || [ "$state" = TARGET ] || [ "$state" = TRANSITION ] || return 1
  publish_v1_rollback_intent "$marker" || return 1
  restore_or_remove ALT_BIN /data/adb/hapaneld/hapaneld-helper.hapaneld-recovery /data/adb/hapaneld/hapaneld-helper 755 "$marker" || return 1
  restore_or_remove ALT_SERVICE /data/adb/service.d/hapaneld-helper.sh.hapaneld-recovery /data/adb/service.d/hapaneld-helper.sh 755 "$marker" || return 1
  restore_or_remove LEGACY_BIN /system/bin/hapaneld-ledd.hapaneld-recovery /system/bin/hapaneld-ledd 755 "$marker" || return 1
  restore_or_remove LEGACY_SERVICE /system/etc/init/hapaneld-ledd.rc.hapaneld-recovery /system/etc/init/hapaneld-ledd.rc 644 "$marker" || return 1
  restore_or_remove OLD_SERVICE /system/etc/init/hapaneld-helper.rc.hapaneld-recovery /system/etc/init/hapaneld-helper.rc 644 "$marker" || return 1
  restore_or_remove OLD_BIN /system/bin/hapaneld-helper.hapaneld-recovery /system/bin/hapaneld-helper 755 "$marker" || return 1
  sync || return 1

  if [ -x /system/bin/hapaneld-helper ] && [ -f /system/bin/hapaneld-helper.hapaneld-recovery ]; then
    /system/bin/hapaneld-helper --supervise >/dev/null 2>&1 &
    result=ROLLBACK_RESTARTED
  elif [ -x /data/adb/hapaneld/hapaneld-helper ] && [ -f /data/adb/hapaneld/hapaneld-helper.hapaneld-recovery ]; then
    /data/adb/hapaneld/hapaneld-helper --supervise >/dev/null 2>&1 &
    result=ROLLBACK_RESTARTED
  elif [ -x /system/bin/hapaneld-ledd ]; then
    start hapaneld_ledd 2>/dev/null || /system/bin/hapaneld-ledd >/dev/null 2>&1 &
    result=ROLLBACK_LEGACY
  else
    result=ROLLBACK_EMPTY
  fi
  echo "$result"
}

rollback_systemless() {
  marker=/data/adb/hapaneld/.helper-upgrade.marker
  [ -f "$marker" ] || { echo ROLLBACK_UNNEEDED; return 0; }
  grep -q ^JOURNAL_SCOPE=APK_HELPER$ "$marker" || return 1
  valid_transaction_identity "$marker" "$transaction_id" "$target_apk" "$target_build" "$target_helper" || return 1
  state=$(classify_systemless)
  if grep -q ^JOURNAL_VERSION=2$ "$marker"; then
    rollback_v2 systemless "$marker" "$state"
    return
  fi
  grep -q ^JOURNAL_VERSION=1$ "$marker" || return 1
  [ "$state" = PRE_SWAP ] || [ "$state" = TARGET ] || [ "$state" = TRANSITION ] || return 1
  flag OLD_BIN "$marker" && valid_recovery OLD_BIN /data/adb/hapaneld/hapaneld-helper.hapaneld-recovery "$marker" || ! flag OLD_BIN "$marker" || return 1
  flag OLD_SERVICE "$marker" && valid_recovery OLD_SERVICE /data/adb/service.d/hapaneld-helper.sh.hapaneld-recovery "$marker" || ! flag OLD_SERVICE "$marker" || return 1
  probe=/data/adb/hapaneld/.helper-probe-$transaction_id
  rm -f "$probe"
  hash_matches @BIN_SHA256@ @STAGED_HELPER@ || return 1
  cp @STAGED_HELPER@ "$probe" || return 1
  chown 0:0 "$probe" || return 1
  chmod 700 "$probe" || return 1
  hash_matches @BIN_SHA256@ "$probe" || return 1
  retire_helpers || return 1
  state=$(classify_systemless)
  [ "$state" = PRE_SWAP ] || [ "$state" = TARGET ] || [ "$state" = TRANSITION ] || return 1
  publish_v1_rollback_intent "$marker" || return 1
  restore_or_remove OLD_SERVICE /data/adb/service.d/hapaneld-helper.sh.hapaneld-recovery /data/adb/service.d/hapaneld-helper.sh 755 "$marker" || return 1
  restore_or_remove OLD_BIN /data/adb/hapaneld/hapaneld-helper.hapaneld-recovery /data/adb/hapaneld/hapaneld-helper 755 "$marker" || return 1
  sync || return 1
  if [ -x /data/adb/hapaneld/hapaneld-helper ] && [ -f /data/adb/hapaneld/hapaneld-helper.hapaneld-recovery ]; then
    /data/adb/hapaneld/hapaneld-helper --supervise >/dev/null 2>&1 &
    result=ROLLBACK_RESTARTED
  elif [ -x /system/bin/hapaneld-helper ]; then
    /system/bin/hapaneld-helper --supervise >/dev/null 2>&1 &
    result=ROLLBACK_RESTARTED
  elif [ -x /system/bin/hapaneld-ledd ]; then
    start hapaneld_ledd 2>/dev/null || /system/bin/hapaneld-ledd >/dev/null 2>&1 &
    result=ROLLBACK_LEGACY
  else
    result=ROLLBACK_EMPTY
  fi
  echo "$result"
}

rollback_hybrid() {
  mount -o rw,remount / 2>/dev/null
  mount -o rw,remount /system 2>/dev/null
  mount -o rw,remount /vendor 2>/dev/null
  marker=/data/adb/hapaneld/.helper-hybrid-upgrade.marker
  [ -f "$marker" ] || { echo ROLLBACK_UNNEEDED; return 0; }
  grep -q ^JOURNAL_SCOPE=APK_HELPER$ "$marker" || return 1
  valid_transaction_identity "$marker" "$transaction_id" "$target_apk" "$target_build" "$target_helper" || return 1
  state=$(classify_hybrid)
  if grep -q ^JOURNAL_VERSION=2$ "$marker"; then
    rollback_v2 hybrid "$marker" "$state"
    return
  fi
  grep -q ^JOURNAL_VERSION=1$ "$marker" || return 1
  [ "$state" = PRE_SWAP ] || [ "$state" = TARGET ] || [ "$state" = TRANSITION ] || return 1
  flag OLD_BIN "$marker" && valid_recovery OLD_BIN /data/adb/hapaneld/hapaneld-helper.hapaneld-recovery "$marker" || ! flag OLD_BIN "$marker" || return 1
  flag OLD_RC "$marker" && valid_recovery OLD_RC /data/adb/hapaneld/hapaneld-helper.vrc.hapaneld-recovery "$marker" || ! flag OLD_RC "$marker" || return 1
  flag SYS_RC "$marker" && valid_recovery SYS_RC /data/adb/hapaneld/hapaneld-helper.sysrc.hapaneld-recovery "$marker" || ! flag SYS_RC "$marker" || return 1
  flag SYS_BIN "$marker" && valid_recovery SYS_BIN /data/adb/hapaneld/hapaneld-helper.sysbin.hapaneld-recovery "$marker" || ! flag SYS_BIN "$marker" || return 1
  flag LEGACY_BIN "$marker" && valid_recovery LEGACY_BIN /data/adb/hapaneld/hapaneld-ledd.sysbin.hapaneld-recovery "$marker" || ! flag LEGACY_BIN "$marker" || return 1
  flag LEGACY_SERVICE "$marker" && valid_recovery LEGACY_SERVICE /data/adb/hapaneld/hapaneld-ledd.rc.hapaneld-recovery "$marker" || ! flag LEGACY_SERVICE "$marker" || return 1
  flag ALT_SERVICE "$marker" && valid_recovery ALT_SERVICE /data/adb/service.d/hapaneld-helper.sh.hapaneld-recovery "$marker" || ! flag ALT_SERVICE "$marker" || return 1

  probe=/data/adb/hapaneld/.helper-probe-$transaction_id
  rm -f "$probe"
  hash_matches @BIN_SHA256@ @STAGED_HELPER@ || return 1
  cp @STAGED_HELPER@ "$probe" || return 1
  chown 0:0 "$probe" || return 1
  chmod 700 "$probe" || return 1
  hash_matches @BIN_SHA256@ "$probe" || return 1

  retire_helpers || return 1
  state=$(classify_hybrid)
  [ "$state" = PRE_SWAP ] || [ "$state" = TARGET ] || [ "$state" = TRANSITION ] || return 1
  publish_v1_rollback_intent "$marker" || return 1
  restore_or_remove SYS_RC /data/adb/hapaneld/hapaneld-helper.sysrc.hapaneld-recovery /system/etc/init/hapaneld-helper.rc 644 "$marker" || return 1
  restore_or_remove SYS_BIN /data/adb/hapaneld/hapaneld-helper.sysbin.hapaneld-recovery /system/bin/hapaneld-helper 755 "$marker" || return 1
  restore_or_remove LEGACY_BIN /data/adb/hapaneld/hapaneld-ledd.sysbin.hapaneld-recovery /system/bin/hapaneld-ledd 755 "$marker" || return 1
  restore_or_remove LEGACY_SERVICE /data/adb/hapaneld/hapaneld-ledd.rc.hapaneld-recovery /system/etc/init/hapaneld-ledd.rc 644 "$marker" || return 1
  restore_or_remove ALT_SERVICE /data/adb/service.d/hapaneld-helper.sh.hapaneld-recovery /data/adb/service.d/hapaneld-helper.sh 755 "$marker" || return 1
  restore_or_remove OLD_RC /data/adb/hapaneld/hapaneld-helper.vrc.hapaneld-recovery /vendor/etc/init/hapaneld-helper.rc 644 "$marker" || return 1
  restore_or_remove OLD_BIN /data/adb/hapaneld/hapaneld-helper.hapaneld-recovery /data/adb/hapaneld/hapaneld-helper 755 "$marker" || return 1
  sync || return 1

  if [ -x /data/adb/hapaneld/hapaneld-helper ] && [ -f /data/adb/hapaneld/hapaneld-helper.hapaneld-recovery ]; then
    /data/adb/hapaneld/hapaneld-helper --supervise >/dev/null 2>&1 &
    result=ROLLBACK_RESTARTED
  elif [ -x /system/bin/hapaneld-helper ]; then
    /system/bin/hapaneld-helper --supervise >/dev/null 2>&1 &
    result=ROLLBACK_RESTARTED
  elif [ -x /system/bin/hapaneld-ledd ]; then
    start hapaneld_ledd 2>/dev/null || /system/bin/hapaneld-ledd >/dev/null 2>&1 &
    result=ROLLBACK_LEGACY
  else
    result=ROLLBACK_EMPTY
  fi
  echo "$result"
}

cancel_external_v2() {
  kind=$1 marker=$2
  [ -f "$marker" ] && [ ! -L "$marker" ] || return 1
  valid_transaction_identity "$marker" "$transaction_id" "$target_apk" "$target_build" "$target_helper" || return 1
  valid_v2_marker "$kind" "$marker" || return 1
  state=$(classify_v2 "$kind" "$marker")
  case "$state" in
    EXTERNAL_CANONICAL_CHANGE)
      publish_cancel_external_v2 "$kind" "$marker" || { echo TOPOLOGY_HOLD; return 4; } ;;
    CANCEL_EXTERNAL) ;;
    *) echo TOPOLOGY_HOLD; return 4 ;;
  esac
  retire_cancel_external_v2 "$marker"
}

cancel_external_system() {
  mount -o rw,remount / 2>/dev/null
  mount -o rw,remount /system 2>/dev/null
  cancel_external_v2 system /system/bin/.hapaneld-helper-upgrade
}

cancel_external_systemless() {
  cancel_external_v2 systemless /data/adb/hapaneld/.helper-upgrade.marker
}

cancel_external_hybrid() {
  mount -o rw,remount / 2>/dev/null
  mount -o rw,remount /system 2>/dev/null
  mount -o rw,remount /vendor 2>/dev/null
  cancel_external_v2 hybrid /data/adb/hapaneld/.helper-hybrid-upgrade.marker
}

finalize_rollback_system() {
  marker=/system/bin/.hapaneld-helper-upgrade
  mount -o rw,remount / 2>/dev/null
  mount -o rw,remount /system 2>/dev/null
  valid_transaction_identity "$marker" "$transaction_id" "$target_apk" "$target_build" "$target_helper" || return 1
  # Do not use classify_system here: a no-op helper update can be both TARGET and the exact
  # journaled pre-swap state, and the classifier deliberately prefers TARGET so a successful APK
  # install can commit. Demanding PRE_SWAP from it meant a rollback of a same-helper upgrade could
  # never be finalized — the device rolled back, the journal survived, and every retry repeated it
  # until the panel could not be provisioned at all. Finalization proves the pre-swap state directly.
  if grep -q ^JOURNAL_VERSION=2$ "$marker"; then
    valid_v2_marker system "$marker" || return 1
    v2_recorded "$marker" || return 1
  else
    grep -q ^JOURNAL_VERSION=1$ "$marker" || return 1
    system_matches_recorded_v1 || return 1
  fi
  journal_version=$(sed -n 's/^JOURNAL_VERSION=//p' "$marker")
  rm -f "$marker" || return 1
  sync || return 1
  if [ "$journal_version" = 2 ]; then
    cleanup_v2_recoveries
  else
    rm -f /system/bin/hapaneld-helper.hapaneld-recovery \
      /system/etc/init/hapaneld-helper.rc.hapaneld-recovery \
      /system/bin/hapaneld-ledd.hapaneld-recovery \
      /system/etc/init/hapaneld-ledd.rc.hapaneld-recovery \
      /data/adb/hapaneld/hapaneld-helper.hapaneld-recovery \
      /data/adb/service.d/hapaneld-helper.sh.hapaneld-recovery 2>/dev/null || true
    cleanup_v1_rollback_staging
  fi
  sync 2>/dev/null || true
  echo ROLLBACK_FINALIZED
}

finalize_rollback_systemless() {
  marker=/data/adb/hapaneld/.helper-upgrade.marker
  valid_transaction_identity "$marker" "$transaction_id" "$target_apk" "$target_build" "$target_helper" || return 1
  # Same reasoning as finalize_rollback_system: prove the journaled pre-swap state directly rather
  # than through a classifier that resolves the no-op-upgrade tie in favour of TARGET.
  if grep -q ^JOURNAL_VERSION=2$ "$marker"; then
    valid_v2_marker systemless "$marker" || return 1
    v2_recorded "$marker" || return 1
  else
    grep -q ^JOURNAL_VERSION=1$ "$marker" || return 1
    systemless_matches_recorded_v1 || return 1
  fi
  journal_version=$(sed -n 's/^JOURNAL_VERSION=//p' "$marker")
  rm -f "$marker" || return 1
  sync || return 1
  if [ "$journal_version" = 2 ]; then
    cleanup_v2_recoveries
  else
    rm -f /data/adb/hapaneld/hapaneld-helper.hapaneld-recovery \
      /data/adb/service.d/hapaneld-helper.sh.hapaneld-recovery 2>/dev/null || true
    cleanup_v1_rollback_staging
  fi
  sync 2>/dev/null || true
  echo ROLLBACK_FINALIZED
}

finalize_rollback_hybrid() {
  marker=/data/adb/hapaneld/.helper-hybrid-upgrade.marker
  valid_transaction_identity "$marker" "$transaction_id" "$target_apk" "$target_build" "$target_helper" || return 1
  # Do not use classify_hybrid here: a no-op helper update can be both TARGET and the exact
  # journaled pre-swap state. Finalization is safe only after the latter is proved directly.
  if grep -q ^JOURNAL_VERSION=2$ "$marker"; then
    valid_v2_marker hybrid "$marker" || return 1
    v2_recorded "$marker" || return 1
  else
    grep -q ^JOURNAL_VERSION=1$ "$marker" || return 1
    hybrid_matches_recorded_v1 || return 1
  fi
  journal_version=$(sed -n 's/^JOURNAL_VERSION=//p' "$marker")
  rm -f "$marker" || return 1
  sync || return 1
  if [ "$journal_version" = 2 ]; then
    cleanup_v2_recoveries
  else
    rm -f /data/adb/hapaneld/hapaneld-helper.hapaneld-recovery \
      /data/adb/hapaneld/hapaneld-helper.vrc.hapaneld-recovery \
      /data/adb/hapaneld/hapaneld-helper.sysrc.hapaneld-recovery \
      /data/adb/hapaneld/hapaneld-helper.sysbin.hapaneld-recovery \
      /data/adb/hapaneld/hapaneld-ledd.sysbin.hapaneld-recovery \
      /data/adb/hapaneld/hapaneld-ledd.rc.hapaneld-recovery \
      /data/adb/service.d/hapaneld-helper.sh.hapaneld-recovery 2>/dev/null || true
    cleanup_v1_rollback_staging
  fi
  sync 2>/dev/null || true
  echo ROLLBACK_FINALIZED
}

commit_system() {
  marker=/system/bin/.hapaneld-helper-upgrade
  mount -o rw,remount / 2>/dev/null
  mount -o rw,remount /system 2>/dev/null
  valid_transaction_identity "$marker" "$transaction_id" "$target_apk" "$target_build" "$target_helper" || return 1
  [ "$(classify_system)" = TARGET ] || return 1
  journal_version=$(sed -n 's/^JOURNAL_VERSION=//p' "$marker")
  case "$journal_version" in 1|2) ;; *) return 1 ;; esac
  rm -f "$marker" || return 1
  sync || return 1
  if [ "$journal_version" = 2 ]; then
    cleanup_v2_recoveries
  else
    rm -f /system/bin/hapaneld-helper.hapaneld-recovery \
      /system/etc/init/hapaneld-helper.rc.hapaneld-recovery \
      /system/bin/hapaneld-ledd.hapaneld-recovery \
      /system/etc/init/hapaneld-ledd.rc.hapaneld-recovery \
      /data/adb/hapaneld/hapaneld-helper.hapaneld-recovery \
      /data/adb/service.d/hapaneld-helper.sh.hapaneld-recovery 2>/dev/null || true
    cleanup_v1_rollback_staging
  fi
  sync 2>/dev/null || true
  echo COMMIT_OK
}

commit_systemless() {
  marker=/data/adb/hapaneld/.helper-upgrade.marker
  valid_transaction_identity "$marker" "$transaction_id" "$target_apk" "$target_build" "$target_helper" || return 1
  [ "$(classify_systemless)" = TARGET ] || return 1
  journal_version=$(sed -n 's/^JOURNAL_VERSION=//p' "$marker")
  case "$journal_version" in 1|2) ;; *) return 1 ;; esac
  rm -f "$marker" || return 1
  sync || return 1
  if [ "$journal_version" = 2 ]; then
    cleanup_v2_recoveries
  else
    rm -f /data/adb/hapaneld/hapaneld-helper.hapaneld-recovery \
      /data/adb/service.d/hapaneld-helper.sh.hapaneld-recovery 2>/dev/null || true
    cleanup_v1_rollback_staging
  fi
  sync 2>/dev/null || true
  echo COMMIT_OK
}

commit_hybrid() {
  marker=/data/adb/hapaneld/.helper-hybrid-upgrade.marker
  valid_transaction_identity "$marker" "$transaction_id" "$target_apk" "$target_build" "$target_helper" || return 1
  [ "$(classify_hybrid)" = TARGET ] || return 1
  journal_version=$(sed -n 's/^JOURNAL_VERSION=//p' "$marker")
  case "$journal_version" in 1|2) ;; *) return 1 ;; esac
  rm -f "$marker" || return 1
  sync || return 1
  if [ "$journal_version" = 2 ]; then
    cleanup_v2_recoveries
  else
    rm -f /data/adb/hapaneld/hapaneld-helper.hapaneld-recovery \
      /data/adb/hapaneld/hapaneld-helper.vrc.hapaneld-recovery \
      /data/adb/hapaneld/hapaneld-helper.sysrc.hapaneld-recovery \
      /data/adb/hapaneld/hapaneld-helper.sysbin.hapaneld-recovery \
      /data/adb/hapaneld/hapaneld-ledd.sysbin.hapaneld-recovery \
      /data/adb/hapaneld/hapaneld-ledd.rc.hapaneld-recovery \
      /data/adb/service.d/hapaneld-helper.sh.hapaneld-recovery 2>/dev/null || true
    cleanup_v1_rollback_staging
  fi
  sync 2>/dev/null || true
  echo COMMIT_OK
}

discover_system() {
  cat /system/bin/.hapaneld-helper-upgrade
  echo LIVE_STATE=$(classify_system)
}

discover_systemless() {
  cat /data/adb/hapaneld/.helper-upgrade.marker
  echo LIVE_STATE=$(classify_systemless)
}

discover_hybrid() {
  cat /data/adb/hapaneld/.helper-hybrid-upgrade.marker
  echo LIVE_STATE=$(classify_hybrid)
}

status_system() {
  marker=/system/bin/.hapaneld-helper-upgrade
  valid_transaction_identity "$marker" "$transaction_id" "$target_apk" "$target_build" "$target_helper" || return 1
  discover_system
}

status_systemless() {
  marker=/data/adb/hapaneld/.helper-upgrade.marker
  valid_transaction_identity "$marker" "$transaction_id" "$target_apk" "$target_build" "$target_helper" || return 1
  discover_systemless
}

status_hybrid() {
  marker=/data/adb/hapaneld/.helper-hybrid-upgrade.marker
  valid_transaction_identity "$marker" "$transaction_id" "$target_apk" "$target_build" "$target_helper" || return 1
  discover_hybrid
}

lease_system() {
  marker=/system/bin/.hapaneld-helper-upgrade
  mount -o rw,remount / 2>/dev/null
  mount -o rw,remount /system 2>/dev/null
  valid_transaction_identity "$marker" "$transaction_id" "$target_apk" "$target_build" "$target_helper" || return 1
  refresh_lease "$marker" || return 1
  echo LEASE_OK
}

lease_systemless() {
  marker=/data/adb/hapaneld/.helper-upgrade.marker
  valid_transaction_identity "$marker" "$transaction_id" "$target_apk" "$target_build" "$target_helper" || return 1
  refresh_lease "$marker" || return 1
  echo LEASE_OK
}

lease_hybrid() {
  marker=/data/adb/hapaneld/.helper-hybrid-upgrade.marker
  valid_transaction_identity "$marker" "$transaction_id" "$target_apk" "$target_build" "$target_helper" || return 1
  refresh_lease "$marker" || return 1
  echo LEASE_OK
}

acquire_helper_lock || { echo TRANSACTION_BUSY; exit 75; }

transaction_id=${2:-}
target_apk=${3:-}
target_build=${4:-}
target_helper=${5:-}

reconcile_lone_legacy_takeover_tmp || { echo LEGACY_TAKEOVER_HOLD; exit 75; }
normalize_legacy_takeover || { echo LEGACY_TAKEOVER_HOLD; exit 75; }
reconcile_authority_free_app_staging
sweep_disposable_staging

case "${1:-}" in
  install-system) install_system ;;
  install-systemless) install_systemless ;;
  install-hybrid) install_hybrid ;;
  rollback-system) rollback_system ;;
  rollback-systemless) rollback_systemless ;;
  rollback-hybrid) rollback_hybrid ;;
  cancel-external-system) cancel_external_system ;;
  cancel-external-systemless) cancel_external_systemless ;;
  cancel-external-hybrid) cancel_external_hybrid ;;
  finalize-rollback-system) finalize_rollback_system ;;
  finalize-rollback-systemless) finalize_rollback_systemless ;;
  finalize-rollback-hybrid) finalize_rollback_hybrid ;;
  commit-system) commit_system ;;
  commit-systemless) commit_systemless ;;
  commit-hybrid) commit_hybrid ;;
  discover-system) discover_system ;;
  discover-systemless) discover_systemless ;;
  discover-hybrid) discover_hybrid ;;
  status-system) status_system ;;
  status-systemless) status_systemless ;;
  status-hybrid) status_hybrid ;;
  lease-system) lease_system ;;
  lease-systemless) lease_systemless ;;
  lease-hybrid) lease_hybrid ;;
  *) exit 2 ;;
esac
EOF
  bin_sha256="$(host_sha256 "$helper")"
  rc_sha256="$(host_sha256 "$rc_file")"
  hybrid_rc_sha256="$(host_sha256 "$hybrid_rc_file")"
  service_sha256="$(host_sha256 "$service_file")"
  sed -e "s/@BIN_SHA256@/$bin_sha256/g" \
      -e "s/@RC_SHA256@/$rc_sha256/g" \
      -e "s/@HYBRID_RC_SHA256@/$hybrid_rc_sha256/g" \
      -e "s/@SERVICE_SHA256@/$service_sha256/g" \
      -e "s/@LEGACY_RC_SHA256@/$legacy_rc_sha256/g" \
      -e "s/@LEGACY_RC_SUPERVISED_SHA256@/$legacy_rc_supervised_sha256/g" \
      -e "s/@LEGACY_HYBRID_RC_SHA256@/$legacy_hybrid_rc_sha256/g" \
      -e "s/@LEGACY_HYBRID_RC_SUPERVISED_SHA256@/$legacy_hybrid_rc_supervised_sha256/g" \
      -e "s/@LEGACY_SERVICE_SHA256@/$legacy_service_sha256/g" \
      -e "s/@LEGACY_SERVICE_SUPERVISED_SHA256@/$legacy_service_supervised_sha256/g" \
      -e "s/@APK_SHA256@/$TARGET_APK_SHA256/g" \
      -e "s/@BUILD_ID@/$expected_build_id/g" \
      -e "s/@TRANSACTION_ID@/$ROOT_HELPER_TRANSACTION_ID/g" \
      -e "s|@STAGED_HELPER@|$ROOT_HELPER_STAGED_HELPER|g" \
      -e "s|@STAGED_RC@|$ROOT_HELPER_STAGED_RC|g" \
      -e "s|@STAGED_HYBRID_RC@|$ROOT_HELPER_STAGED_HYBRID_RC|g" \
      -e "s|@STAGED_SERVICE@|$ROOT_HELPER_STAGED_SERVICE|g" \
      "$transaction_file" > "$transaction_file.ready"
  mv "$transaction_file.ready" "$transaction_file"
  transaction_sha256="$(host_sha256 "$transaction_file")"
  ROOT_HELPER_TARGET_SHA256="$bin_sha256"
  ROOT_HELPER_TRANSACTION_SHA256="$transaction_sha256"
  ROOT_HELPER_TRANSACTION_PATH="/data/adb/hapaneld/.helper-transaction-$ROOT_HELPER_TRANSACTION_ID-$transaction_sha256"

  if run_root '[ ! -e /data/local/.hapaneld-helper-manual-upgrade ] && [ ! -L /data/local/.hapaneld-helper-manual-upgrade ] && \
      [ ! -e /system/bin/.hapaneld-helper-manual-upgrade ] && [ ! -L /system/bin/.hapaneld-helper-manual-upgrade ] && \
      [ ! -e /data/adb/hapaneld/.helper-manual-upgrade.marker ] && [ ! -L /data/adb/hapaneld/.helper-manual-upgrade.marker ]' \
      >/dev/null 2>&1; then
    :
  else
    [ -z "$helper_dir" ] || rm -rf "$helper_dir"
    rm -f "$rc_file" "$hybrid_rc_file" "$service_file" "$transaction_file"
    fail "an incomplete standalone root-helper installation must be recovered first" \
      "Re-run ./helper/install-daemon.sh $TARGET $abi from the same checkout; it will verify and recover its separate journal." \
      "Then re-run this provisioning command. No APK or helper files were changed by this attempt."
  fi

  # A managed hybrid boot vector makes the layout sticky: updates keep using /vendor + /data instead
  # of attempting an implicit migration back to /system. Unknown content at the reserved vendor path
  # is never overwritten. Capacity must be an unambiguous numeric value before selecting /system.
  out="$(run_root '
    expected='"$hybrid_rc_sha256"'
    legacy='"$legacy_hybrid_rc_sha256"'
    legacy_supervised='"$legacy_hybrid_rc_supervised_sha256"'
    vendor_rc=/vendor/etc/init/hapaneld-helper.rc
    if [ -e $vendor_rc ] || [ -L $vendor_rc ]; then
      owner=$(stat -c %u:%g $vendor_rc 2>/dev/null || toybox stat -c %u:%g $vendor_rc 2>/dev/null) || owner=
      actual=$(sha256sum $vendor_rc 2>/dev/null || toybox sha256sum $vendor_rc 2>/dev/null) || actual=
      if [ "$owner" = 0:0 ] && { [ "${actual%% *}" = "$expected" ] || \
          [ "${actual%% *}" = "$legacy" ] || [ "${actual%% *}" = "$legacy_supervised" ]; }; then
        echo VENDOR_RC_MANAGED
      else
        echo VENDOR_RC_UNEXPECTED
      fi
    fi
    mount -o rw,remount / 2>/dev/null; mount -o rw,remount /system 2>/dev/null
    # Issue #120: this probed /system with a zero-byte `touch`, and the transaction writes its boot
    # file to /system/etc/init. A zero-byte create at the parent cannot fail for a full filesystem,
    # for exhausted inodes on a subtree, or for a read-only overmount one level down - which is the
    # class of panel that passed this probe and then died at its first copy with no reason attached.
    #
    # Both are still required. A panel whose /system mount never came back read-write is reported as
    # read-only here rather than as a mysteriously refused write further down. The second probe
    # writes real bytes and checks they arrived, mirroring the /vendor/etc/init probe the hybrid
    # route already uses; a create that stores nothing is not evidence that a file will fit.
    # The cleanup is deliberately NOT chained onto the success test. A probe that is created but
    # stores no bytes is exactly the case this exists to catch, and an `&&` chain would short-circuit
    # before removing it, leaving a stray file on the partition whose fullness is in question.
    system_init_probe=/system/etc/init/.hapaneld-rw-probe-@TRANSACTION_ID@
    rm -f "$system_init_probe" 2>/dev/null
    system_init_writable=0
    if touch /system/.rw_probe 2>/dev/null && rm /system/.rw_probe 2>/dev/null; then
      if printf hapaneld-system-init-write-probe > "$system_init_probe" 2>/dev/null &&
         [ -s "$system_init_probe" ]; then
        system_init_writable=1
      fi
    fi
    rm -f "$system_init_probe" 2>/dev/null
    if [ "$system_init_writable" = 1 ]; then
      echo SYSTEM_RW
      # HAPANELD_CAPACITY_PROBE_BEGIN
      capacity_output=
      if command -v df >/dev/null 2>&1 && command -v sed >/dev/null 2>&1; then
        capacity_output=$(df -P -k /system/etc/init 2>/dev/null) || capacity_output=
      fi
      capacity_row=
      capacity_extra=unknown
      if [ -n "$capacity_output" ]; then
        capacity_row=$(printf "%s\n" "$capacity_output" | sed -n "2p") || capacity_row=
        capacity_extra=$(printf "%s\n" "$capacity_output" | sed -n "3,\$p") || capacity_extra=unknown
      fi
      available=
      if [ -n "$capacity_row" ] && [ -z "$capacity_extra" ]; then
        set -f
        set -- $capacity_row
        set +f
        if [ "$#" -eq 6 ]; then
          case "$4" in
            ""|*[!0-9]*) ;;
            *) available=$4 ;;
          esac
        fi
      fi
      if [ -n "$available" ]; then echo SYSTEM_AVAIL_KB=$available; else echo SYSTEM_CAPACITY_UNKNOWN; fi
      # HAPANELD_CAPACITY_PROBE_END
    else
      echo SYSTEM_RO
      if command -v magisk >/dev/null 2>&1 || [ -x /data/adb/magisk/busybox ] || [ -x /data/adb/ksu/bin/busybox ] || [ -x /data/adb/ap/bin/busybox ]; then
        echo SYSTEMLESS_RUNNER
      else
        echo NO_SYSTEMLESS_RUNNER
      fi
    fi
  ' 2>&1)" || true

  if printf '%s\n' "$out" | grep -qx VENDOR_RC_UNEXPECTED; then
    [ -z "$helper_dir" ] || rm -rf "$helper_dir"
    rm -f "$rc_file" "$hybrid_rc_file" "$service_file" "$transaction_file"
    fail "unexpected existing /vendor/etc/init/hapaneld-helper.rc" \
      "The file was not created by this provisioner, so it was left unchanged. No helper or APK was installed."
  fi

  if printf '%s\n' "$out" | grep -qx VENDOR_RC_MANAGED; then
    install_kind=hybrid
  elif printf '%s\n' "$out" | grep -qx SYSTEM_RW; then
    system_avail_kb="$(printf '%s\n' "$out" | sed -n 's/^SYSTEM_AVAIL_KB=//p')"
    if [ "$(printf '%s\n' "$system_avail_kb" | grep -Ec '^[0-9]+$')" -ne 1 ]; then
      [ -z "$helper_dir" ] || rm -rf "$helper_dir"
      rm -f "$rc_file" "$hybrid_rc_file" "$service_file" "$transaction_file"
      fail "system capacity could not be determined safely" \
        "The helper and APK were left unchanged. Check the panel's df output, then re-run."
    fi
    # The live binary and every recovery now live on /data. /system only needs the target rc,
    # its temporary publish copy, and a small journal margin.
    system_need_kb=$(( ( $(wc -c < "$rc_file") * 2 + 1023 ) / 1024 + 64 ))
    [ "$system_need_kb" -ge 128 ] || system_need_kb=128
    if [ "$system_avail_kb" -lt "$system_need_kb" ]; then
      install_kind=hybrid
    else
      install_kind=system
    fi
  elif printf '%s\n' "$out" | grep -qx SYSTEMLESS_RUNNER; then
    install_kind=systemless
  else
    [ -z "$helper_dir" ] || rm -rf "$helper_dir"
    rm -f "$rc_file" "$hybrid_rc_file" "$service_file" "$transaction_file"
    # Keep this aligned with helper/install-daemon.sh: a read-only /system may need only its existing
    # writable overlay mounted, or it may still be protected by verity. Try the reboot-free route
    # first, then print the rebooting fallback as two executable stages.
    fail "the panel has read-only /system and no verified systemless boot-service runner" \
      "The helper was not installed and the previous APK was left in place." \
      "On a userdebug panel with an unlocked bootloader this is usually a writable overlay that is not mounted, or dm-verity — not a missing root manager." \
      "Try the host-side remount FIRST. It needs no reboot, and on panels that already carry a scratch overlay it is the whole fix:" \
      "  adb -s $TARGET root && adb -s $TARGET remount" \
      "Only if that is refused, disable verity — this REBOOTS the panel, so do it with the panel in front of you and unlock any PIN-protected panel before expecting it back:" \
      "  adb -s $TARGET disable-verity && adb -s $TARGET reboot" \
      "After the panel restarts and is unlocked:" \
      "  adb -s $TARGET root && adb -s $TARGET remount" \
      "Then re-run the installer. If remount is still refused after that, install a supported Magisk, KernelSU, or APatch service.d environment, or use firmware with a writable /system init path."
  fi

  if [ "$install_kind" = hybrid ]; then
    vendor_probe="$(run_root '
      mount -o rw,remount /vendor 2>/dev/null
      probe=/vendor/etc/init/.hapaneld-rw-probe-'"$ROOT_HELPER_TRANSACTION_ID"'
      rm -f $probe
      if printf hapaneld-vendor-write-probe > $probe 2>/dev/null && [ -s $probe ] && rm $probe 2>/dev/null; then
        echo VENDOR_INIT_RW
      else
        rm -f $probe 2>/dev/null
      fi
    ' 2>&1)" || true
    if ! printf '%s\n' "$vendor_probe" | grep -qx VENDOR_INIT_RW; then
      [ -z "$helper_dir" ] || rm -rf "$helper_dir"
      rm -f "$rc_file" "$hybrid_rc_file" "$service_file" "$transaction_file"
      fail "/vendor/etc/init is not writable for the hybrid root helper" \
        "The helper and APK were left unchanged. Restore vendor-partition writability, then re-run."
    fi
    if [ -n "$system_avail_kb" ]; then
      echo "   ${YEL}ℹ${X} /system has ${system_avail_kb}KB free; ${system_need_kb}KB is required for a transactional helper upgrade"
    else
      echo "   ${YEL}ℹ${X} keeping the existing hybrid root-helper layout"
    fi
    echo "     ${D}init service in /vendor/etc/init; sealed helper at /data/local/hapaneld-helper${X}"
  fi

  step "🧰 root helper" "${D}installing or upgrading $abi privileged service${X}"
  adb -s "$TARGET" push "$helper" "$ROOT_HELPER_STAGED_HELPER" >/dev/null
  adb -s "$TARGET" push "$rc_file" "$ROOT_HELPER_STAGED_RC" >/dev/null
  adb -s "$TARGET" push "$hybrid_rc_file" "$ROOT_HELPER_STAGED_HYBRID_RC" >/dev/null
  adb -s "$TARGET" push "$service_file" "$ROOT_HELPER_STAGED_SERVICE" >/dev/null
  adb -s "$TARGET" push "$transaction_file" "$ROOT_HELPER_STAGED_TRANSACTION" >/dev/null
  rm -f "$rc_file" "$hybrid_rc_file" "$service_file" "$transaction_file"
  transaction_ready="$(run_root 'mkdir -p /data/adb/hapaneld || exit 1
    chown 0:0 /data/adb/hapaneld || exit 1
    chmod 700 /data/adb/hapaneld || exit 1
    expected='"$ROOT_HELPER_TRANSACTION_SHA256"'
    source='"$ROOT_HELPER_STAGED_TRANSACTION"'
    destination='"$ROOT_HELPER_TRANSACTION_PATH"'
    source_hash=$(sha256sum $source 2>/dev/null || toybox sha256sum $source 2>/dev/null) || exit 1
    [ ${source_hash%% *} = $expected ] || exit 1
    cp $source $destination.new || exit 1
    chown 0:0 $destination.new || exit 1
    chmod 700 $destination.new || exit 1
    destination_hash=$(sha256sum $destination.new 2>/dev/null || toybox sha256sum $destination.new 2>/dev/null) || exit 1
    [ ${destination_hash%% *} = $expected ] || exit 1
    mv -f $destination.new $destination || exit 1
    sync || exit 1
    echo TRANSACTION_READY' 2>&1)" || true
  if ! printf '%s\n' "$transaction_ready" | grep -qx TRANSACTION_READY; then
    run_root 'rm -f '"$ROOT_HELPER_STAGED_HELPER $ROOT_HELPER_STAGED_RC $ROOT_HELPER_STAGED_HYBRID_RC $ROOT_HELPER_STAGED_SERVICE $ROOT_HELPER_STAGED_TRANSACTION $ROOT_HELPER_TRANSACTION_PATH"'.new' \
      >/dev/null 2>&1 || true
    [ -z "$helper_dir" ] || rm -rf "$helper_dir"
    fail "the root-helper transaction could not be promoted into protected storage" \
      "No privileged transaction script was executed and the APK was not replaced."
  fi

  case "$install_kind" in
    system)
      out2="$(run_root_helper_transaction install-system 2>&1)" || true
      resolve_root_helper_install_state "$install_kind"
      if ! printf '%s\n' "$out2" | grep -qx INSTALL_OK; then
        install_detail="$(root_helper_install_marker "$out2")"
        install_diag_lines=()
        while IFS= read -r diag_line; do
          [ -z "$diag_line" ] || install_diag_lines+=("${D}$diag_line${X}")
        done < <(root_helper_install_diagnostics "$out2")
        if printf '%s\n' "$out2" | grep -q '^INSTALL_UNCHANGED '; then
          [ -z "$helper_dir" ] || rm -rf "$helper_dir"
          install_unchanged="$(root_helper_unchanged_reason "$out2")"
          unchanged_headline="the root-helper upgrade did not start"
          case "$install_unchanged" in
            *\ helper_retirement) unchanged_headline="the old root helper could not be retired, so the upgrade did not start" ;;
            *\ staged_source_*) unchanged_headline="the staged root helper could not be used, so the upgrade did not start" ;;
            *\ target_*) unchanged_headline="this panel cannot take the root-helper replacement, so the upgrade did not start" ;;
          esac
          unchanged_advice=()
          while IFS= read -r advice_line; do
            [ -z "$advice_line" ] || unchanged_advice+=("$advice_line")
          done < <(root_helper_unchanged_advice "$install_unchanged")
          fail "$unchanged_headline" \
            "Nothing on the panel changed: the previous helper, its boot registration and the installed APK are all still in place." \
            ${install_detail:+"The panel reported: $install_detail"} \
            ${install_diag_lines[@]+"${install_diag_lines[@]}"} \
            ${unchanged_advice[@]+"${unchanged_advice[@]}"}
        fi
        if printf '%s\n' "$out2" | grep -qx TOPOLOGY_HOLD; then
          fail "root-helper topology changed concurrently after its recovery snapshot" \
            "No stale rollback or candidate replacement was attempted. The retained v2 journal blocks further managed changes until the concurrent installer state is reconciled."
        fi
        if printf '%s\n' "$out2" | grep -qx GUARD_ARMED; then
          if rollback_root_helper "$install_kind"; then
            fail "the incumbent root helper refused replacement while Guard maintenance was armed" \
              "The prior helper was restored and the APK was not replaced. Finish or cancel Guard maintenance, then re-run."
          fi
          fail "the incumbent root helper refused replacement and rollback could not be verified" \
            "The APK was not replaced. Restore the helper manually before relying on privileged operations."
        fi
        if rollback_root_helper "$install_kind"; then
          fail "/system root-helper install failed; the prior helper was preserved or restored" \
            "The previous APK and helper remain active. Re-run after checking writable-system capacity and permissions." \
            ${install_detail:+"The panel reported: $install_detail"} \
            ${install_diag_lines[@]+"${install_diag_lines[@]}"}
        fi
        fail "/system root-helper install failed and rollback could not be verified" \
          "The APK was not replaced. Restore the helper manually before relying on privileged operations." \
          ${install_detail:+"The panel reported: $install_detail"} \
          ${install_diag_lines[@]+"${install_diag_lines[@]}"}
      fi
      ;;
    hybrid)
      out2="$(run_root_helper_transaction install-hybrid 2>&1)" || true
      resolve_root_helper_install_state "$install_kind"
      if ! printf '%s\n' "$out2" | grep -qx INSTALL_OK; then
        install_detail="$(root_helper_install_marker "$out2")"
        install_diag_lines=()
        while IFS= read -r diag_line; do
          [ -z "$diag_line" ] || install_diag_lines+=("${D}$diag_line${X}")
        done < <(root_helper_install_diagnostics "$out2")
        if printf '%s\n' "$out2" | grep -q '^INSTALL_UNCHANGED '; then
          [ -z "$helper_dir" ] || rm -rf "$helper_dir"
          install_unchanged="$(root_helper_unchanged_reason "$out2")"
          unchanged_headline="the root-helper upgrade did not start"
          case "$install_unchanged" in
            *\ helper_retirement) unchanged_headline="the old root helper could not be retired, so the upgrade did not start" ;;
            *\ staged_source_*) unchanged_headline="the staged root helper could not be used, so the upgrade did not start" ;;
            *\ target_*) unchanged_headline="this panel cannot take the root-helper replacement, so the upgrade did not start" ;;
          esac
          unchanged_advice=()
          while IFS= read -r advice_line; do
            [ -z "$advice_line" ] || unchanged_advice+=("$advice_line")
          done < <(root_helper_unchanged_advice "$install_unchanged")
          fail "$unchanged_headline" \
            "Nothing on the panel changed: the previous helper, its boot registration and the installed APK are all still in place." \
            ${install_detail:+"The panel reported: $install_detail"} \
            ${install_diag_lines[@]+"${install_diag_lines[@]}"} \
            ${unchanged_advice[@]+"${unchanged_advice[@]}"}
        fi
        if printf '%s\n' "$out2" | grep -qx TOPOLOGY_HOLD; then
          fail "root-helper topology changed concurrently after its recovery snapshot" \
            "No stale rollback or candidate replacement was attempted. The retained v2 journal blocks further managed changes until the concurrent installer state is reconciled."
        fi
        if printf '%s\n' "$out2" | grep -qx GUARD_ARMED; then
          if rollback_root_helper "$install_kind"; then
            fail "the incumbent root helper refused replacement while Guard maintenance was armed" \
              "The prior helper was restored and the APK was not replaced. Finish or cancel Guard maintenance, then re-run."
          fi
          fail "the incumbent root helper refused replacement and rollback could not be verified" \
            "The APK was not replaced. Restore the helper manually before relying on privileged operations."
        fi
        if rollback_root_helper "$install_kind"; then
          fail "hybrid root-helper install failed; the prior helper was preserved or restored" \
            "The previous APK and helper remain active. Re-run after checking /vendor/etc/init and /data/adb." \
            ${install_detail:+"The panel reported: $install_detail"} \
            ${install_diag_lines[@]+"${install_diag_lines[@]}"}
        fi
        fail "hybrid root-helper install failed and rollback could not be verified" \
          "The APK was not replaced. Restore the helper manually before relying on privileged operations." \
          ${install_detail:+"The panel reported: $install_detail"} \
          ${install_diag_lines[@]+"${install_diag_lines[@]}"}
      fi
      ;;
    systemless)
      out2="$(run_root_helper_transaction install-systemless 2>&1)" || true
      resolve_root_helper_install_state "$install_kind"
      if ! printf '%s\n' "$out2" | grep -qx INSTALL_OK; then
        install_detail="$(root_helper_install_marker "$out2")"
        install_diag_lines=()
        while IFS= read -r diag_line; do
          [ -z "$diag_line" ] || install_diag_lines+=("${D}$diag_line${X}")
        done < <(root_helper_install_diagnostics "$out2")
        if printf '%s\n' "$out2" | grep -q '^INSTALL_UNCHANGED '; then
          [ -z "$helper_dir" ] || rm -rf "$helper_dir"
          install_unchanged="$(root_helper_unchanged_reason "$out2")"
          unchanged_headline="the root-helper upgrade did not start"
          case "$install_unchanged" in
            *\ helper_retirement) unchanged_headline="the old root helper could not be retired, so the upgrade did not start" ;;
            *\ staged_source_*) unchanged_headline="the staged root helper could not be used, so the upgrade did not start" ;;
            *\ target_*) unchanged_headline="this panel cannot take the root-helper replacement, so the upgrade did not start" ;;
          esac
          unchanged_advice=()
          while IFS= read -r advice_line; do
            [ -z "$advice_line" ] || unchanged_advice+=("$advice_line")
          done < <(root_helper_unchanged_advice "$install_unchanged")
          fail "$unchanged_headline" \
            "Nothing on the panel changed: the previous helper, its boot registration and the installed APK are all still in place." \
            ${install_detail:+"The panel reported: $install_detail"} \
            ${install_diag_lines[@]+"${install_diag_lines[@]}"} \
            ${unchanged_advice[@]+"${unchanged_advice[@]}"}
        fi
        if printf '%s\n' "$out2" | grep -qx TOPOLOGY_HOLD; then
          fail "root-helper topology changed concurrently after its recovery snapshot" \
            "No stale rollback or candidate replacement was attempted. The retained v2 journal blocks further managed changes until the concurrent installer state is reconciled."
        fi
        if printf '%s\n' "$out2" | grep -qx GUARD_ARMED; then
          if rollback_root_helper "$install_kind"; then
            fail "the incumbent root helper refused replacement while Guard maintenance was armed" \
              "The prior helper was restored and the APK was not replaced. Finish or cancel Guard maintenance, then re-run."
          fi
          fail "the incumbent root helper refused replacement and rollback could not be verified" \
            "The APK was not replaced. Restore the helper manually before relying on privileged operations."
        fi
        if rollback_root_helper "$install_kind"; then
          fail "systemless root-helper install failed; the prior helper was preserved or restored" \
            "The previous APK and helper remain active. Re-run after checking /data capacity and service.d permissions." \
            ${install_detail:+"The panel reported: $install_detail"} \
            ${install_diag_lines[@]+"${install_diag_lines[@]}"}
        fi
        fail "systemless root-helper install failed and rollback could not be verified" \
          "The APK was not replaced. Restore the helper manually before relying on privileged operations." \
          ${install_detail:+"The panel reported: $install_detail"} \
          ${install_diag_lines[@]+"${install_diag_lines[@]}"}
      fi
      ;;
  esac

  # All boot mechanisms only register the canonical authority; launch that exact sealed binary once
  # for this session instead of relying on an init definition that is not loaded until next boot.
  run_root '
    stop hapaneld_ledd 2>/dev/null; stop hapaneld_helper 2>/dev/null
    pkill -x hapaneld-ledd 2>/dev/null; pkill -x hapaneld-helper 2>/dev/null
    /data/local/hapaneld-helper --supervise >/dev/null 2>&1 &
  ' >/dev/null 2>&1 || true

  if ! wait_for_helper_reply PING OK "$install_kind"; then
    if rollback_root_helper "$install_kind"; then
      [ -z "$helper_dir" ] || rm -rf "$helper_dir"
      fail "new root helper failed its liveness check; the prior helper was restored" \
        "The previous APK and helper remain active. Re-run after checking the helper logs."
    fi
    [ -z "$helper_dir" ] || rm -rf "$helper_dir"
    fail "new root helper failed its liveness check and rollback could not be verified" \
      "The APK was not replaced. Restore the helper manually before relying on privileged operations."
  fi
  if ! wait_for_helper_reply COMPANIONCAPS "COMPANIONCAPS 1 BACKUP RESTORE STATUS JOURNAL" "$install_kind"; then
    if rollback_root_helper "$install_kind"; then
      [ -z "$helper_dir" ] || rm -rf "$helper_dir"
      fail "new root helper failed its capability check; the prior helper was restored" \
        "The previous APK and helper remain active. Re-run after checking the helper logs."
    fi
    [ -z "$helper_dir" ] || rm -rf "$helper_dir"
    fail "new root helper failed its capability check and rollback could not be verified" \
      "The APK was not replaced. Restore the helper manually before relying on privileged operations."
  fi
  if ! wait_for_helper_reply BUILDID "BUILDID $expected_build_id" "$install_kind"; then
    if rollback_root_helper "$install_kind"; then
      [ -z "$helper_dir" ] || rm -rf "$helper_dir"
      fail "new root helper failed its exact build-identity check; the prior helper was restored" \
        "The previous APK and helper remain active. Rebuild or re-download the matching helper, then retry."
    fi
    [ -z "$helper_dir" ] || rm -rf "$helper_dir"
    fail "new root helper failed its exact build-identity check and rollback could not be verified" \
      "The APK was not replaced. Restore the helper manually before relying on privileged operations."
  fi
  if ! wait_for_helper_reply GUARDCAPS "OK GUARDCAPS 1 PREPARE DEFINE STREAM ACTION HEALTH REFUSAL STATUS EVIDENCE CANCEL RETIRE JOURNAL AUTONOMOUS SUPERVISED TERMINAL_RETIRE" "$install_kind"; then
    if rollback_root_helper "$install_kind"; then
      [ -z "$helper_dir" ] || rm -rf "$helper_dir"
      fail "new root helper failed its exact Guard capability check; the prior helper was restored" \
        "The previous APK and helper remain active. Rebuild or re-download the matching helper, then retry."
    fi
    [ -z "$helper_dir" ] || rm -rf "$helper_dir"
    fail "new root helper failed its exact Guard capability check and rollback could not be verified" \
      "The APK was not replaced. Restore the helper manually before relying on privileged operations."
  fi
  if ! wait_for_helper_reply GUARDSTATUS "OK GUARDSTATUS 0 EMPTY NONE NONE NONE NONE 0 0 0 NONE NONE 0 0" "$install_kind"; then
    if rollback_root_helper "$install_kind"; then
      [ -z "$helper_dir" ] || rm -rf "$helper_dir"
      fail "new root helper did not report exact empty Guard state; the prior helper was restored" \
        "The previous APK and helper remain active. Finish or cancel Guard maintenance, then re-run."
    fi
    [ -z "$helper_dir" ] || rm -rf "$helper_dir"
    fail "new root helper did not report exact empty Guard state and rollback could not be verified" \
      "The APK was not replaced. Restore the helper manually before relying on privileged operations."
  fi
  renew_root_helper_lease "$install_kind" || fail "the root-helper transaction lease could not be renewed after validation" \
    "The APK was not replaced. Re-run after any competing provisioner finishes; this journal remains recoverable."
  ROOT_HELPER_TRANSACTION_KIND="$install_kind"
  [ -z "$helper_dir" ] || rm -rf "$helper_dir"
  echo "   ${GRN}✓${X} root helper running with Companion-data protocol 1; recovery retained until APK installation succeeds"
}

# DB_COMPAT_MUTATION_ANCHOR: HOST_GATE / HAPANELD_HOST_DB_GATE_V1
#
# This is the one host-side authority for database replacement admission. The candidate boundary is
# read from the exact authenticated APK, while the primary database (and, only for a too-new primary,
# the exact recovery file the app would select) is inspected from this run's actual panel state.
# Version prompts, --force, snapshots, reset and helper paths do not participate in this decision.
read_candidate_database_contract() {
  local tool tool_name xml contracts count family contract_version database minimum maximum extra
  DB_COMPATIBILITY_CONTRACT=""
  DB_CANDIDATE_MIN=""
  DB_CANDIDATE_MAX=""
  tool="$(find_android_build_tool aapt || true)"
  [ -n "$tool" ] || tool="$(find_android_build_tool aapt2 || true)"
  # 2, not 1: a missing host tool says nothing about the candidate, and must not be reported as missing
  # or malformed metadata (#24).
  [ -n "$tool" ] || return 2
  tool_name="${tool##*/}"
  tool_name="${tool_name%.bat}"; tool_name="${tool_name%.cmd}"
  assert_candidate_apk_unchanged
  if [ "$tool_name" = aapt2 ]; then
    xml="$("$tool" dump xmltree "$APK" --file AndroidManifest.xml 2>/dev/null || true)"
  else
    xml="$("$tool" dump xmltree "$APK" AndroidManifest.xml 2>/dev/null || true)"
  fi
  assert_candidate_apk_unchanged
  contracts="$(printf '%s\n' "$xml" | awk -v wanted="$DB_COMPATIBILITY_METADATA_KEY" '
    function quoted(line, start, rest, stop) {
      start = index(line, "=\"")
      if (!start) return ""
      rest = substr(line, start + 2)
      stop = index(rest, "\"")
      return stop ? substr(rest, 1, stop - 1) : ""
    }
    function indentation(line, copy) { copy=line; sub(/[^ ].*/, "", copy); return length(copy) }
    function flush_meta() {
      if (!inside) return
      if (name == wanted) {
        if (direct_meta) print value
        else wrong_scope=1
      }
      inside=0
    }
    /^[[:space:]]*E:/ {
      flush_meta()
      indent=indentation($0)
      entity=$0; sub(/^[[:space:]]*E:[[:space:]]*/, "", entity); sub(/[[:space:](].*/, "", entity)
      parent=""; parent_indent=-1
      for (level in stack) if ((level + 0) < indent && (level + 0) > parent_indent) {
        parent=stack[level]; parent_indent=level + 0
      }
      for (level in stack) if ((level + 0) >= indent) delete stack[level]
      stack[indent]=entity
      if (entity == "application" && parent == "manifest") application_count++
      if (entity == "meta-data") {
        inside=1; name=""; value=""; direct_meta=(parent == "application")
      }
      next
    }
    inside && /A: (android:|http:\/\/schemas\.android\.com\/apk\/res\/android:)name/  { name=quoted($0) }
    inside && /A: (android:|http:\/\/schemas\.android\.com\/apk\/res\/android:)value/ { value=quoted($0) }
    END {
      flush_meta()
      if (application_count != 1 || wrong_scope) print "__INVALID_APPLICATION_METADATA__"
    }
  ')"
  count="$(printf '%s\n' "$contracts" | awk 'NF { n++ } END { print n + 0 }')"
  [ "$count" = 1 ] || return 1
  DB_COMPATIBILITY_CONTRACT="$(printf '%s\n' "$contracts" | awk 'NF { print; exit }')"
  IFS=: read -r family contract_version database minimum maximum extra <<< "$DB_COMPATIBILITY_CONTRACT"
  [ "$family" = hapaneld-db ] && [ "$contract_version" = v1 ] && [ "$database" = ha-paneld.db ] && [ -z "${extra:-}" ] || return 1
  case "$minimum" in [1-9]|[1-9][0-9]*) ;; *) return 1 ;; esac
  case "$maximum" in [1-9]|[1-9][0-9]*) ;; *) return 1 ;; esac
  [ "${#minimum}" -le 10 ] && [ "${#maximum}" -le 10 ] || return 1
  [ "$minimum" -le 2147483647 ] && [ "$maximum" -le 2147483647 ] || return 1
  [ "$minimum" -ge 1 ] && [ "$minimum" -le "$maximum" ] || return 1
  DB_CANDIDATE_MIN="$minimum"
  DB_CANDIDATE_MAX="$maximum"
  return 0
}

cleanup_root_database_observer() {
  local host_script="${DB_OBSERVER_HOST_SCRIPT:-}" remote_script="${DB_OBSERVER_REMOTE_SCRIPT:-}" remote_stage="${DB_OBSERVER_REMOTE_STAGE:-}"
  local remote_owner="${DB_OBSERVER_REMOTE_OWNER:-}" cleanup_command=""
  DB_OBSERVER_HOST_SCRIPT=""
  DB_OBSERVER_REMOTE_SCRIPT=""
  DB_OBSERVER_REMOTE_STAGE=""
  DB_OBSERVER_REMOTE_OWNER=""
  [ -z "$host_script" ] || rm -f "$host_script" "$host_script.ready" 2>/dev/null || true
  # Do not start a new privileged transport after INT/TERM. The staged observer owns its remote
  # database copy with EXIT/HUP/INT/TERM traps; signal cleanup removes host bytes synchronously and
  # preserves the caller's signal status and latency. Ordinary exits still perform the guarded sweep.
  [ "${PROVISION_SIGNAL_CLEANUP:-0}" != 1 ] || return 0
  if [ -n "$remote_script" ]; then
    cleanup_command="rm -f $remote_script"
    if [ -n "$remote_stage" ] && [ -n "$remote_owner" ]; then
      cleanup_command="$cleanup_command; [ ! -f $remote_stage/$remote_owner ] || rm -rf $remote_stage"
    fi
    run_root "$cleanup_command" >/dev/null 2>&1 || true
  fi
  return 0
}

inspect_root_database_compatibility() {
  local command out begin end primary primary_fingerprint recovery retained inventory inventory_fingerprint observation_nonce
  local observer_stage observer_script observer_owner script_file script_sha panel_sha primary_mode
  resolve_data_package
  observation_nonce="$(host_transaction_id)" || return 1
  observer_stage="/data/local/tmp/.hapaneld-db-observer.$observation_nonce"
  observer_script="${observer_stage}-script"
  observer_owner=".owner-$observation_nonce"
  if [ "${DB_GATE_PHASE:-initial}" = initial ]; then primary_mode=live
  else primary_mode=stable; fi
  command='set -u
# HAPANELD_DB_COMPAT_OBSERVER_BEGIN
db=/data/data/@DATA_PACKAGE@/databases/ha-paneld.db
minimum=@MINIMUM@
maximum=@MAXIMUM@
primary_mode=@PRIMARY_MODE@
sqlite3_bin=""
if [ -x /system/bin/sqlite3 ]; then sqlite3_bin=/system/bin/sqlite3
else sqlite3_bin=$(command -v sqlite3 2>/dev/null) || sqlite3_bin=""; fi
observer_tmp=@OBSERVER_STAGE@
observer_owner=@OBSERVER_OWNER@
observer_stage_created=0
cleanup_observer() {
  [ "$observer_stage_created" = 0 ] || rm -rf "$observer_tmp" 2>/dev/null
}
trap cleanup_observer EXIT HUP INT TERM
[ ! -e "$observer_tmp" ] && [ ! -L "$observer_tmp" ] || exit 1
umask 077
mkdir -m 700 "$observer_tmp" 2>/dev/null || exit 1
observer_stage_created=1
: > "$observer_tmp/$observer_owner" 2>/dev/null || exit 1
source_digest() {
  digest_path=$1
  digest_line=$(sha256sum "$digest_path" 2>/dev/null || toybox sha256sum "$digest_path" 2>/dev/null || busybox sha256sum "$digest_path" 2>/dev/null) || return 1
  digest=${digest_line%% *}
  case "$digest" in ""|*[!0-9A-Fa-f]* ) return 1 ;; esac
  [ ${#digest} = 64 ] || return 1
  printf "%s" "$digest"
}
source_fingerprint() {
  fingerprint_path=$1
  fingerprint=""
  for fingerprint_suffix in "" -wal -shm -journal; do
    fingerprint_file=$fingerprint_path$fingerprint_suffix
    if [ -L "$fingerprint_file" ]; then return 1
    elif [ -e "$fingerprint_file" ]; then
      [ -f "$fingerprint_file" ] || return 1
      fingerprint_hash=$(source_digest "$fingerprint_file") || return 1
      fingerprint="$fingerprint|$fingerprint_suffix:$fingerprint_hash"
    fi
  done
  [ -n "$fingerprint" ] || return 1
  printf "%s\n" "$fingerprint"
}
inspect_database() {
  inspected_path=$1
  inspected_mode=${2:-stable}
  [ ! -L "$inspected_path" ] || { echo not_regular; return; }
  [ -f "$inspected_path" ] || { echo not_regular; return; }
  [ -n "$sqlite3_bin" ] && [ -n "$observer_tmp" ] || { echo unreadable; return; }
  inspected_copy=$observer_tmp/observed.db
  rm -f "$inspected_copy" "$inspected_copy"-wal "$inspected_copy"-shm "$inspected_copy"-journal
  if [ "$inspected_mode" = live ]; then
    inspected_source="file:$inspected_path?mode=ro"
    "$sqlite3_bin" "$inspected_source" ".backup $inspected_copy" 2>/dev/null || { echo unreadable; return; }
  else
    inspected_before=$(source_fingerprint "$inspected_path") || { echo unreadable; return; }
    cp "$inspected_path" "$inspected_copy" 2>/dev/null || { echo unreadable; return; }
    for inspected_suffix in -wal -shm -journal; do
      if [ -e "$inspected_path$inspected_suffix" ]; then
        cp "$inspected_path$inspected_suffix" "$inspected_copy$inspected_suffix" 2>/dev/null || { echo unreadable; return; }
      fi
    done
    inspected_after=$(source_fingerprint "$inspected_path") || { echo unreadable; return; }
    [ "$inspected_before" = "$inspected_after" ] || { echo changed; return; }
  fi
  inspected=$("$sqlite3_bin" "$inspected_copy" "PRAGMA query_only=ON; PRAGMA user_version; PRAGMA quick_check;" 2>/dev/null) || { echo unreadable; return; }
  if [ "$inspected_mode" != live ]; then
    inspected_final=$(source_fingerprint "$inspected_path") || { echo unreadable; return; }
    [ "$inspected_before" = "$inspected_final" ] || { echo changed; return; }
  fi
  inspected_version=$(printf "%s\n" "$inspected" | sed -n "1p") || { echo unreadable; return; }
  inspected_quick=$(printf "%s\n" "$inspected" | sed -n "2p") || { echo unreadable; return; }
  inspected_extra=$(printf "%s\n" "$inspected" | sed -n "3,\$p") || { echo unreadable; return; }
  case "$inspected_version" in ""|*[!0-9]*) echo unreadable; return ;; esac
  [ -z "$inspected_extra" ] || { echo "readable:$inspected_version:bad"; return; }
  [ "$inspected_quick" = ok ] && echo "readable:$inspected_version:ok" || echo "readable:$inspected_version:bad"
}
database_parent=${db%/*}
app_data=${database_parent%/*}
data_root=${app_data%/*}
inventory=unreadable
inventory_fingerprint=""
if data_entries=$(ls -1A "$data_root" 2>/dev/null); then
  if ! printf "%s\n" "$data_entries" | grep -Fxq "${app_data##*/}"; then inventory=absent
  elif [ -L "$app_data" ] || [ ! -d "$app_data" ]; then inventory=unreadable
  elif app_entries=$(ls -1A "$app_data" 2>/dev/null); then
    if ! printf "%s\n" "$app_entries" | grep -Fxq databases; then inventory=readable
    elif [ -L "$database_parent" ] || [ ! -d "$database_parent" ]; then inventory=unreadable
    elif find "$database_parent" -mindepth 1 -maxdepth 1 -print >/dev/null 2>&1; then
      inventory=readable
      for inventory_path in \
        "$db".restore.tmp "$db".v*.premigrate "$db".v*.superseded \
        "$db".v*.premigrate.tmp "$db".v*.superseded.tmp \
        "$db".v*.premigrate-wal "$db".v*.premigrate-shm "$db".v*.premigrate-journal \
        "$db".v*.superseded-wal "$db".v*.superseded-shm "$db".v*.superseded-journal; do
        [ -e "$inventory_path" ] || [ -L "$inventory_path" ] || continue
        inventory_name=${inventory_path##*/}
        if [ -L "$inventory_path" ]; then
          inventory_fingerprint="$inventory_fingerprint|$inventory_name:link"
        elif [ -f "$inventory_path" ]; then
          inventory_hash=$(source_digest "$inventory_path") || { inventory=unreadable; break; }
          inventory_fingerprint="$inventory_fingerprint|$inventory_name:file:$inventory_hash"
        else
          inventory_fingerprint="$inventory_fingerprint|$inventory_name:other"
        fi
      done
    fi
  fi
fi
[ -n "$inventory_fingerprint" ] || inventory_fingerprint=none
retained=0
for retained_path in \
  "$db" "$db"-wal "$db"-shm "$db"-journal "$db".restore.tmp \
  "$db".v*.premigrate "$db".v*.superseded \
  "$db".v*.premigrate.tmp "$db".v*.superseded.tmp \
  "$db".v*.premigrate-wal "$db".v*.premigrate-shm "$db".v*.premigrate-journal \
  "$db".v*.superseded-wal "$db".v*.superseded-shm "$db".v*.superseded-journal; do
  if [ -e "$retained_path" ] || [ -L "$retained_path" ]; then retained=1; break; fi
done
if [ -L "$db" ] || { [ -e "$db" ] && [ ! -f "$db" ]; }; then primary=not_regular
elif [ ! -e "$db" ]; then primary=missing
else primary=$(inspect_database "$db" "$primary_mode"); fi
primary_fingerprint=none
case "$primary" in
  readable:*)
    if [ "$primary_mode" = live ]; then primary_fingerprint=live
    else primary_fingerprint=$(source_fingerprint "$db") || { primary=changed; primary_fingerprint=unreadable; }; fi
    ;;
  unreadable|changed|not_regular) primary_fingerprint=unreadable ;;
esac
best_path=""
best_version=-1
for recovery_artifact in "$db".v*.premigrate "$db".v*.premigrate.tmp \
  "$db".v*.premigrate-wal "$db".v*.premigrate-shm "$db".v*.premigrate-journal; do
  [ -e "$recovery_artifact" ] || [ -L "$recovery_artifact" ] || continue
  case "$recovery_artifact" in
    *.premigrate) recovery_path=$recovery_artifact ;;
    *.premigrate.tmp) recovery_path=${recovery_artifact%.tmp} ;;
    *.premigrate-wal) recovery_path=${recovery_artifact%-wal} ;;
    *.premigrate-shm) recovery_path=${recovery_artifact%-shm} ;;
    *.premigrate-journal) recovery_path=${recovery_artifact%-journal} ;;
    *) continue ;;
  esac
  suffix=${recovery_path#"$db".v}
  recovery_version=${suffix%.premigrate}
  case "$recovery_version" in ""|*[!0-9]*) continue ;; esac
  recovery_named_schema=$(printf "%s\n" "$recovery_version" | sed "s/^0*//") || {
    inventory=unreadable
    best_path=""
    break
  }
  [ -n "$recovery_named_schema" ] || recovery_named_schema=0
  [ "$recovery_named_schema" -le "$maximum" ] || continue
  if [ "$recovery_named_schema" -gt "$best_version" ] || \
     { [ "$recovery_named_schema" -eq "$best_version" ] && [ "$recovery_path" \> "$best_path" ]; }; then
    best_version=$recovery_named_schema
    best_path=$recovery_path
  fi
done
if [ -z "$best_path" ]; then recovery=none
elif [ ! -e "$best_path" ] && [ ! -L "$best_path" ]; then recovery="v$best_version:incomplete"
elif [ -e "$best_path".tmp ] || [ -L "$best_path".tmp ] || \
     [ -e "$best_path"-wal ] || [ -L "$best_path"-wal ] || \
     [ -e "$best_path"-shm ] || [ -L "$best_path"-shm ] || \
     [ -e "$best_path"-journal ] || [ -L "$best_path"-journal ]; then
  recovery="v$best_version:sidecar"
else recovery="v$best_version:$(inspect_database "$best_path")"; fi
echo HOSTDB_BEGIN:@NONCE@
echo "HOSTDB_PRIMARY=$primary"
echo "HOSTDB_PRIMARY_FINGERPRINT=$primary_fingerprint"
echo "HOSTDB_RECOVERY=$recovery"
echo "HOSTDB_RETAINED=$retained"
echo "HOSTDB_INVENTORY=$inventory"
echo "HOSTDB_INVENTORY_FINGERPRINT=$inventory_fingerprint"
echo HOSTDB_END:@NONCE@
# HAPANELD_DB_COMPAT_OBSERVER_END'
  command="${command//@DATA_PACKAGE@/$DATA_PKG}"
  command="${command//@MINIMUM@/$DB_CANDIDATE_MIN}"
  command="${command//@MAXIMUM@/$DB_CANDIDATE_MAX}"
  command="${command//@PRIMARY_MODE@/$primary_mode}"
  command="${command//@NONCE@/$observation_nonce}"
  command="${command//@OBSERVER_STAGE@/$observer_stage}"
  command="${command//@OBSERVER_OWNER@/$observer_owner}"
  script_file="$(mktemp "${TMPDIR:-/tmp}/hapaneld-db-observer.XXXXXX")" || return 1
  DB_OBSERVER_HOST_SCRIPT="$script_file"
  if ! printf '%s\n' "$command" > "$script_file" || ! chmod 600 "$script_file"; then
    cleanup_root_database_observer
    return 1
  fi
  script_sha="$(host_sha256 "$script_file" 2>/dev/null)" || {
    cleanup_root_database_observer
    return 1
  }
  if ! run_root "[ ! -e $observer_script ] && [ ! -L $observer_script ] && [ ! -e $observer_stage ] && [ ! -L $observer_stage ]" >/dev/null 2>&1; then
    cleanup_root_database_observer
    return 1
  fi
  DB_OBSERVER_REMOTE_SCRIPT="$observer_script"
  DB_OBSERVER_REMOTE_STAGE="$observer_stage"
  DB_OBSERVER_REMOTE_OWNER="$observer_owner"
  if ! adb -s "$TARGET" push "$script_file" "$observer_script" >/dev/null 2>&1; then
    cleanup_root_database_observer
    return 1
  fi
  panel_sha="$(run_root "sha256sum $observer_script 2>/dev/null || toybox sha256sum $observer_script 2>/dev/null || busybox sha256sum $observer_script 2>/dev/null" 2>/dev/null | tr -d '\r')" || {
    cleanup_root_database_observer
    return 1
  }
  panel_sha="${panel_sha%% *}"
  if [ "$panel_sha" != "$script_sha" ]; then
    cleanup_root_database_observer
    return 1
  fi
  # Keep the invocation short and dialect-neutral. The comment is also a fixture-visible nonce bind;
  # the staged script emits the authoritative begin/end pair and remains the only executed payload.
  if ! out="$(run_root "sh $observer_script # HOSTDB_BEGIN:$observation_nonce" 2>/dev/null | tr -d '\r')"; then
    cleanup_root_database_observer
    return 1
  fi
  cleanup_root_database_observer
  begin="$(printf '%s\n' "$out" | grep -c "^HOSTDB_BEGIN:$observation_nonce$" || true)"
  end="$(printf '%s\n' "$out" | grep -c "^HOSTDB_END:$observation_nonce$" || true)"
  [ "$begin" = 1 ] && [ "$end" = 1 ] || return 1
  primary="$(printf '%s\n' "$out" | sed -n 's/^HOSTDB_PRIMARY=//p')"
  primary_fingerprint="$(printf '%s\n' "$out" | sed -n 's/^HOSTDB_PRIMARY_FINGERPRINT=//p')"
  recovery="$(printf '%s\n' "$out" | sed -n 's/^HOSTDB_RECOVERY=//p')"
  retained="$(printf '%s\n' "$out" | sed -n 's/^HOSTDB_RETAINED=//p')"
  inventory="$(printf '%s\n' "$out" | sed -n 's/^HOSTDB_INVENTORY=//p')"
  inventory_fingerprint="$(printf '%s\n' "$out" | sed -n 's/^HOSTDB_INVENTORY_FINGERPRINT=//p')"
  [ "$(printf '%s\n' "$primary" | awk 'NF { n++ } END { print n + 0 }')" = 1 ] || return 1
  [ "$(printf '%s\n' "$primary_fingerprint" | awk 'NF { n++ } END { print n + 0 }')" = 1 ] || return 1
  [ "$(printf '%s\n' "$recovery" | awk 'NF { n++ } END { print n + 0 }')" = 1 ] || return 1
  case "$retained" in 0|1) ;; *) return 1 ;; esac
  case "$inventory" in absent|readable|unreadable) ;; *) return 1 ;; esac
  [ "$(printf '%s\n' "$inventory_fingerprint" | awk 'NF { n++ } END { print n + 0 }')" = 1 ] || return 1
  HOST_DB_PRIMARY="$primary"
  HOST_DB_PRIMARY_FINGERPRINT="$primary_fingerprint"
  HOST_DB_RECOVERY="$recovery"
  HOST_DB_RETAINED="$retained"
  HOST_DB_INVENTORY="$inventory"
  HOST_DB_INVENTORY_FINGERPRINT="$inventory_fingerprint"
  return 0
}

# Every refusal inside the gate stops the same way: a package-phase refusal first rolls back the
# task-owned helper transaction, and the operator is told how far this run got. Only the reason
# differs, so each kind of refusal words its own message around DB_GATE_HELPER_RECOVERY and
# DB_GATE_PROGRESS rather than borrowing the database wording.
host_database_gate_unwind() {
  local phase="${DB_GATE_PHASE:-initial}" helper_kind=""
  DB_GATE_HELPER_RECOVERY=""
  if [ "$phase" = package ] && [ -n "${ROOT_HELPER_TRANSACTION_KIND:-}" ]; then
    helper_kind="$ROOT_HELPER_TRANSACTION_KIND"
    stop_root_helper_lease_guard
    cleanup_root_helper_lease_guard
    if rollback_root_helper "$helper_kind"; then
      cleanup_root_helper_staging
      ROOT_HELPER_TRANSACTION_KIND=""
      DB_GATE_HELPER_RECOVERY="The task-owned root-helper transaction was rolled back and verified before refusal."
    else
      DB_GATE_HELPER_RECOVERY="The APK was not replaced, but root-helper rollback could not be verified; preserve the recovery journal and repair the helper before retrying."
    fi
  fi
  case "$phase" in
    package) DB_GATE_PROGRESS="Backup/quiescence and requested helper or Shizuku preparation may already have completed, but the ha-paneld APK, permissions and configuration were not changed." ;;
    consume) DB_GATE_PROGRESS="A read-only backup or app quiescence may already have completed, but no reset, helper, Shizuku, APK, permission or configuration mutation was started." ;;
    *) DB_GATE_PROGRESS="No settings backup, database quiescence, reset, helper, Shizuku, APK, permission or configuration mutation was started." ;;
  esac
}

host_database_gate_refuse() {
  local headline="database compatibility could not be proven" lines=()
  host_database_gate_unwind
  case "${DB_GATE_PHASE:-initial}" in
    package) headline="$headline at the package-time recheck" ;;
    consume) headline="$headline at the consume-time recheck" ;;
  esac
  # The boundary is evidence about the candidate only once its manifest has been read. A refusal that
  # fires before then must not report the candidate's metadata as missing or malformed (#24).
  [ "$DB_CONTRACT_EVALUATED" = 0 ] || lines+=("Candidate boundary: ${DB_COMPATIBILITY_CONTRACT:-missing or malformed}.")
  [ -z "$DB_GATE_HELPER_RECOVERY" ] || lines+=("$DB_GATE_HELPER_RECOVERY")
  lines+=("$DB_GATE_PROGRESS")
  if [ "$DB_CONTRACT_EVALUATED" = 0 ]; then
    lines+=("The candidate's database boundary was not read yet, so nothing about its database support was judged. Resolve the reason above, then re-run the same command.")
  else
    lines+=("Use a candidate that supports the panel database, or recover the database with a current compatible build before retrying.")
  fi
  fail "$headline: $1" "${lines[@]}"
}

# Updating a panel that already carries ha-paneld needs two host tools that no checksum can stand in
# for: apksigner reads the signer of the app already on the panel, and aapt or aapt2 reads the database
# boundary the candidate declares in its manifest. Their absence is a fact about this computer, so it is
# refused in its own words and never as a candidate or database verdict (#24). The caller names the tool
# it could not find; the other is checked here too, so installing one does not end in a second refusal
# for the other.
host_build_tools_refuse() {
  local missing_tool="$1" missing="" problem lines=() not_found=0 needs_install=0 needs_java=0
  if [ "$missing_tool" = apksigner ] || [ -z "$(find_android_build_tool apksigner || true)" ]; then
    missing="apksigner"
    needs_java=1
    problem="$(android_build_tool_failure apksigner || true)"
    [ -n "$problem" ] || { not_found=1; needs_install=1; }
    problem="${problem%.}"
    lines+=("${problem:-apksigner was not found on PATH or in the build-tools folder of ANDROID_HOME or ANDROID_SDK_ROOT}. It proves the new APK is signed by the same key as the app already on the panel.")
  fi
  if [ "$missing_tool" = aapt ] || { [ -z "$(find_android_build_tool aapt || true)" ] && [ -z "$(find_android_build_tool aapt2 || true)" ]; }; then
    missing="${missing:+$missing and }aapt or aapt2"
    needs_install=1
    problem="$(android_build_tool_failure aapt || true)"
    [ -n "$problem" ] || problem="$(android_build_tool_failure aapt2 || true)"
    [ -n "$problem" ] || not_found=1
    problem="${problem%.}"
    lines+=("${problem:-Neither aapt nor aapt2 was found on PATH or in the build-tools folder of ANDROID_HOME or ANDROID_SDK_ROOT}. Either one reads which panel database versions the new APK supports.")
  fi
  host_database_gate_unwind
  lines+=("This is about the tools on this computer, not a verdict on the APK or the panel database.")
  [ -z "$DB_GATE_HELPER_RECOVERY" ] || lines+=("$DB_GATE_HELPER_RECOVERY")
  lines+=("$DB_GATE_PROGRESS")
  # An apksigner that is installed but cannot run is almost always missing Java, and reinstalling
  # Build-Tools would not help; where-to-find advice only helps a tool that was not found.
  [ "$needs_install" = 0 ] || \
    lines+=("Install Android SDK Build-Tools: in Android Studio open Tools > SDK Manager > SDK Tools and tick Android SDK Build-Tools, or run sdkmanager \"build-tools;36.0.0\" from the Android command-line tools (sdkmanager.bat in Git Bash).")
  [ "$needs_java" = 0 ] || \
    lines+=("apksigner runs on Java: install a Java runtime, or use the one bundled with Android Studio (its jbr folder) through PATH or JAVA_HOME, until java -version works in this terminal.")
  if [ "$not_found" = 1 ]; then
    lines+=("Then tell the installer where the SDK is and re-run the same command: export ANDROID_HOME=\"\$HOME/AppData/Local/Android/Sdk\" in Git Bash on Windows, \"\$HOME/Library/Android/sdk\" on macOS or \"\$HOME/Android/Sdk\" on Linux. Adding the build-tools/<version> folder to PATH works too.")
  else
    lines+=("Then re-run the same command.")
  fi
  fail "updating the ha-paneld already on this panel needs $missing from Android SDK Build-Tools on this computer" "${lines[@]}"
}

# The supported way past a signer change, shared by the pre-mutation refusal below and Android's own
# INSTALL_FAILED_UPDATE_INCOMPATIBLE so the two can never advise differently.
signer_change_recovery_steps() {
  SIGNER_CHANGE_RECOVERY_STEPS=(
    "1. For the fullest recovery, open the panel's :8888 page → Install → Backup and verify the downloaded .hpb file is non-empty. It carries settings, durable panel state, the profile catalog and the Companion login; the entity catalog and the proximity and ambient history are relearned rather than restored. --export saves settings only."
    "2. adb -s $TARGET uninstall $PKG    (removes the app AND its on-panel config)"
    "3. Re-run this command, then restore the .hpb from Install → Restore. Do not pass an .hpb to --restore; that CLI option accepts config JSON only."
  )
}

# A different signer is a real refusal, but it is not a database verdict and it is found before the
# candidate boundary is read. Android would reject the replacement anyway; the only way past it deletes
# the panel's settings and database, so this stops before any mutation and says so in its own words.
host_signer_mismatch_refuse() {
  local lines=()
  host_database_gate_unwind
  signer_change_recovery_steps
  lines+=("Installed app signer: $1" "New APK signer:       $2"
    "Android does not update an app from an APK signed by a different key.")
  [ -z "$DB_GATE_HELPER_RECOVERY" ] || lines+=("$DB_GATE_HELPER_RECOVERY")
  lines+=("$DB_GATE_PROGRESS"
    "Use an APK signed by the same key as the app on the panel. To change keys instead, the app must be uninstalled first:"
    "${SIGNER_CHANGE_RECOVERY_STEPS[@]}")
  fail "the new APK is signed by a different key than the ha-paneld already on this panel" "${lines[@]}"
}

host_database_compatibility_decision() {
  local primary_version primary_quick recovery_file_version recovery_state recovery_version recovery_quick observation_nonce contract_status
  DB_GATE_RECOVERY=""
  DB_GATE_DECISION_KIND=""
  DB_GATE_RESET_FRESH=0
  HOST_DB_PRIMARY=""; HOST_DB_RECOVERY=""; HOST_DB_RETAINED=""; HOST_DB_INVENTORY=""; HOST_DB_INVENTORY_FINGERPRINT=""
  classify_package_presence "$ADB_COMMAND_TIMEOUT_SECONDS"
  case "$PACKAGE_PRESENCE" in
    present|absent) ;;
    *) host_database_gate_refuse "the installed-package state is unknown" ;;
  esac
  if [ "$PACKAGE_PRESENCE" = absent ]; then
    classify_package_data_record "$ADB_COMMAND_TIMEOUT_SECONDS"
    case "$PACKAGE_DATA_RECORD" in
      absent) ;;
      retained) host_database_gate_refuse "Android retains an uninstalled ha-paneld package/data record" ;;
      *) host_database_gate_refuse "Android could not prove the absence of an uninstalled retained-data record" ;;
    esac
  fi
  verify_incumbent_signer_for_replacement

  contract_status=0
  read_candidate_database_contract || contract_status=$?
  if [ "$contract_status" = 2 ] && [ "$PACKAGE_PRESENCE" = present ]; then host_build_tools_refuse aapt; fi
  DB_CONTRACT_EVALUATED=1
  if [ "$contract_status" != 0 ]; then
    if [ "$PACKAGE_PRESENCE" = absent ]; then
      # Legacy APKs without the signed contract remain valid only for a positively proven first
      # installation. Android package removal owns and removes app-private data on rootless devices;
      # rooted devices receive the stronger retained-file check below.
      DB_COMPATIBILITY_CONTRACT="legacy candidate without database metadata"
      DB_CANDIDATE_MIN=1
      DB_CANDIDATE_MAX=2147483647
    else
      host_database_gate_refuse "the authenticated APK has missing, duplicate or malformed database metadata"
    fi
  fi

  case "$ROOT_ROUTE_VERDICT" in
    rooted)
      HOST_DB_PRIMARY=""; HOST_DB_PRIMARY_FINGERPRINT=""; HOST_DB_RECOVERY=""; HOST_DB_RETAINED=""; HOST_DB_INVENTORY=""; HOST_DB_INVENTORY_FINGERPRINT=""
      inspect_root_database_compatibility || host_database_gate_refuse "the canonical database observation was unreadable"
      if [ "$PACKAGE_PRESENCE" = absent ]; then
        [ "$HOST_DB_INVENTORY" != unreadable ] || \
          host_database_gate_refuse "the app-data database inventory could not be traversed"
        if [ "$HOST_DB_PRIMARY" = missing ] && [ "$HOST_DB_RETAINED" = 0 ]; then
          step "🛡️  database compatible" "${D}proven fresh install · no retained database state${X}"
          DB_GATE_DECISION_KIND=FRESH
          return 0
        fi
        # Retained state under the install target itself is the residue this gate has always
        # refused. Retained state under the bridge is this panel's own data, waiting for the
        # handover the successor performs once it is installed, so it is measured against the
        # candidate's schema boundary below — the same question asked of an in-place upgrade.
        [ "$DATA_PKG" != "$PKG" ] || \
          host_database_gate_refuse "the package is absent but retained database or recovery state still exists"
      fi
      if [ "${DB_GATE_PHASE:-}" = package ] && [ "${RESET_CONFIG_COMPLETED:-0}" = 1 ]; then
        [ "$HOST_DB_INVENTORY" != unreadable ] && [ "$HOST_DB_PRIMARY" = missing ] && \
          [ "$HOST_DB_RETAINED" = 0 ] || \
          host_database_gate_refuse "the confirmed reset did not leave a provably empty database and recovery inventory"
        DB_GATE_RESET_FRESH=1
        DB_GATE_DECISION_KIND=RESET_FRESH
        step "🛡️  database compatible" "${D}confirmed reset · package retained · database and recovery state proven empty${X}"
        return 0
      fi
      case "$HOST_DB_PRIMARY" in
        readable:*:*)
          primary_version="${HOST_DB_PRIMARY#readable:}"; primary_version="${primary_version%%:*}"
          primary_quick="${HOST_DB_PRIMARY##*:}"
          [ "$primary_quick" = ok ] || host_database_gate_refuse "the canonical database failed quick_check"
          ;;
        missing) host_database_gate_refuse "ha-paneld is installed but its canonical database is missing" ;;
        *) host_database_gate_refuse "the canonical database is unreadable or is not a regular file" ;;
      esac
      [ "$primary_version" -ge "$DB_CANDIDATE_MIN" ] || \
        host_database_gate_refuse "database schema $primary_version is below candidate minimum $DB_CANDIDATE_MIN"
      if [ "$primary_version" -le "$DB_CANDIDATE_MAX" ]; then
        DB_GATE_DECISION_KIND=DIRECT
        step "🛡️  database compatible" "${D}schema $primary_version is inside candidate boundary $DB_CANDIDATE_MIN..$DB_CANDIDATE_MAX${X}"
        return 0
      fi
      # DB_COMPAT_MUTATION_ANCHOR: HOST_RECOVERY_INVENTORY — recovery is authoritative only when
      # the complete database/recovery namespace was traversed successfully.
      [ "$HOST_DB_INVENTORY" = readable ] || \
        host_database_gate_refuse "the complete premigration recovery inventory could not be traversed"
      [ "$HOST_DB_RECOVERY" != none ] || \
        host_database_gate_refuse "database schema $primary_version is newer than candidate maximum $DB_CANDIDATE_MAX and no selectable premigration recovery exists"
      recovery_file_version="${HOST_DB_RECOVERY#v}"; recovery_file_version="${recovery_file_version%%:*}"
      recovery_state="${HOST_DB_RECOVERY#*:}"
      case "$recovery_state" in
        readable:*:*)
          recovery_version="${recovery_state#readable:}"; recovery_version="${recovery_version%%:*}"
          recovery_quick="${recovery_state##*:}"
          ;;
        not_regular) host_database_gate_refuse "the newest selectable premigration recovery is not a regular file" ;;
        incomplete) host_database_gate_refuse "the newest selectable premigration recovery is incomplete" ;;
        sidecar) host_database_gate_refuse "the newest selectable premigration recovery has a SQLite sidecar or temporary file" ;;
        changed) host_database_gate_refuse "the newest selectable premigration recovery changed during observation" ;;
        *) host_database_gate_refuse "the newest selectable premigration recovery is unreadable" ;;
      esac
      [ "$recovery_version" = "$recovery_file_version" ] || \
        host_database_gate_refuse "the newest selectable premigration recovery filename and schema disagree"
      [ "$recovery_version" -ge "$DB_CANDIDATE_MIN" ] || \
        host_database_gate_refuse "the newest selectable premigration recovery is below candidate minimum $DB_CANDIDATE_MIN"
      [ "$recovery_quick" = ok ] || \
        host_database_gate_refuse "the newest selectable premigration recovery failed quick_check"
      DB_GATE_RECOVERY="ha-paneld.db.v${recovery_file_version}.premigrate"
      DB_GATE_DECISION_KIND=RECOVER
      step "🛡️  database recovery proven" "${D}schema $primary_version → $DB_GATE_RECOVERY within $DB_CANDIDATE_MIN..$DB_CANDIDATE_MAX${X}"
      return 0
      ;;
    unrooted)
      if [ "$PACKAGE_PRESENCE" = absent ]; then
        HOST_DB_PRIMARY=missing; HOST_DB_RECOVERY=none; HOST_DB_RETAINED=0
        HOST_DB_PRIMARY_FINGERPRINT=android-absent
        HOST_DB_INVENTORY=android-absent; HOST_DB_INVENTORY_FINGERPRINT=android-absent
        DB_GATE_DECISION_KIND=FRESH
        step "🛡️  database compatible" "${D}proven fresh install · Android owns no installed app data${X}"
        return 0
      fi
      if [ "${DB_GATE_PHASE:-}" = package ] && [ "${RESET_CONFIG_COMPLETED:-0}" = 1 ]; then
        classify_reset_package_stopped "$ADB_COMMAND_TIMEOUT_SECONDS"
        case "$RESET_PACKAGE_STOPPED" in
          stopped) ;;
          running) host_database_gate_refuse "the reset package ran again after pm clear, so app data may have been recreated" ;;
          *) host_database_gate_refuse "Android could not prove that the reset package remained stopped after pm clear" ;;
        esac
        HOST_DB_PRIMARY=missing; HOST_DB_PRIMARY_FINGERPRINT=android-cleared-stopped
        HOST_DB_RECOVERY=none; HOST_DB_RETAINED=0
        HOST_DB_INVENTORY=android-cleared-stopped; HOST_DB_INVENTORY_FINGERPRINT=android-cleared-stopped
        DB_GATE_RESET_FRESH=1
        DB_GATE_DECISION_KIND=RESET_FRESH
        step "🛡️  database compatible" "${D}confirmed Android package-data clear · package remained stopped · fresh database state${X}"
        return 0
      fi
      # A rootless in-place install may proceed only from a fresh same-run app status observation.
      # Recovery files are app-private and therefore cannot be proven from this route.
      observation_nonce="$(host_transaction_id)" || host_database_gate_refuse "a fresh rootless observation nonce could not be created"
      read_storage_health_refresh "$observation_nonce"
      [ "$STORAGE_HEALTH_OBSERVATION_NONCE" = "$observation_nonce" ] || \
        host_database_gate_refuse "rootless status did not echo this run's database observation nonce"
      case "$STORAGE_HEALTH_SCHEMA_VERSION" in ""|*[!0-9]*) host_database_gate_refuse "rootless status did not report a concrete schema" ;; esac
      [ "$STORAGE_HEALTH_QUICK_CHECK" = ok ] || host_database_gate_refuse "rootless status did not prove quick_check=ok"
      primary_version="$STORAGE_HEALTH_SCHEMA_VERSION"
      [ "$primary_version" -ge "$DB_CANDIDATE_MIN" ] || \
        host_database_gate_refuse "database schema $primary_version is below candidate minimum $DB_CANDIDATE_MIN"
      [ "$primary_version" -le "$DB_CANDIDATE_MAX" ] || \
        host_database_gate_refuse "database schema $primary_version is newer than candidate maximum $DB_CANDIDATE_MAX; rootless recovery cannot be proven"
      HOST_DB_PRIMARY="readable:$primary_version:ok"; HOST_DB_RECOVERY=unobservable
      HOST_DB_PRIMARY_FINGERPRINT=unobservable
      HOST_DB_RETAINED=unobservable; HOST_DB_INVENTORY=unobservable
      HOST_DB_INVENTORY_FINGERPRINT=unobservable
      DB_GATE_DECISION_KIND=DIRECT
      step "🛡️  database compatible" "${D}rootless status proves schema $primary_version · quick_check ok · boundary $DB_CANDIDATE_MIN..$DB_CANDIDATE_MAX${X}"
      return 0
      ;;
    *) host_database_gate_refuse "the panel root route is unknown" ;;
  esac
}

host_database_compatibility_gate() {
  local phase="${1:-initial}" semantic evidence
  DB_GATE_PHASE="$phase"
  host_database_compatibility_decision
  semantic="package=$PACKAGE_PRESENCE;contract=$DB_COMPATIBILITY_CONTRACT;root=$ROOT_ROUTE_VERDICT;decision=$DB_GATE_DECISION_KIND;primary=$HOST_DB_PRIMARY"
  case "$DB_GATE_DECISION_KIND" in
    DIRECT)
      evidence="$semantic;primary_fingerprint=$HOST_DB_PRIMARY_FINGERPRINT"
      ;;
    RECOVER)
      semantic="$semantic;recovery=$HOST_DB_RECOVERY;retained=$HOST_DB_RETAINED;inventory=$HOST_DB_INVENTORY;selected=$DB_GATE_RECOVERY"
      evidence="$semantic;primary_fingerprint=$HOST_DB_PRIMARY_FINGERPRINT;inventory_fingerprint=$HOST_DB_INVENTORY_FINGERPRINT"
      ;;
    FRESH|RESET_FRESH)
      semantic="$semantic;recovery=$HOST_DB_RECOVERY;retained=$HOST_DB_RETAINED;inventory=$HOST_DB_INVENTORY"
      evidence="$semantic;primary_fingerprint=$HOST_DB_PRIMARY_FINGERPRINT;inventory_fingerprint=$HOST_DB_INVENTORY_FINGERPRINT"
      ;;
    *) host_database_gate_refuse "the compatibility decision kind is missing or invalid" ;;
  esac
  case "$phase" in
    initial) DB_GATE_INITIAL_EVIDENCE="$semantic" ;;
    consume)
      [ -n "$DB_GATE_INITIAL_EVIDENCE" ] || host_database_gate_refuse "the initial compatibility evidence is missing"
      [ "$semantic" = "$DB_GATE_INITIAL_EVIDENCE" ] || \
        host_database_gate_refuse "the package, database or recovery inventory changed after its initial observation"
      DB_GATE_CONSUME_EVIDENCE="$evidence"
      ;;
    package)
      [ -n "$DB_GATE_CONSUME_EVIDENCE" ] || host_database_gate_refuse "the consume-time compatibility evidence is missing"
      if [ "$DB_GATE_RESET_FRESH" != 1 ]; then
        [ "$evidence" = "$DB_GATE_CONSUME_EVIDENCE" ] || \
          host_database_gate_refuse "the package, database or recovery inventory changed after the consume-time observation"
      fi
      ;;
    *) host_database_gate_refuse "the compatibility gate phase is invalid" ;;
  esac
  # Keep the candidate byte binding inside the same phase so consume/package failures use the same
  # cleanup and task-owned-helper rollback semantics as database-evidence failures.
  assert_candidate_apk_unchanged
  # Package phase remains owned until run_adb_install_attempt performs its final byte assertion and
  # starts package-manager consumption. A lease renewal between this gate and that assertion must
  # not turn candidate drift into a plain failure that strands a task-owned helper transaction.
  [ "$phase" = package ] || DB_GATE_PHASE=""
}

# Give a useful version transition before install. Android's package manager remains the portable,
# authoritative downgrade guard; avoiding GNU `sort -V` keeps the supported macOS path working.
version_guard() {
  # DB_COMPAT_MUTATION_ANCHOR: HOST_FORCE_POLICY — --force suppresses only the version prompt; it never bypasses HOST_GATE.
  [ "$FORCE" = 1 ] && return 0
  [ -n "$TOINSTALL_VER" ] || return 0
  local installed raw status timeout="${VERSION_INSPECTION_TIMEOUT_SECONDS:-20}"
  case "$timeout" in ''|*[!0-9]*|0) timeout=20 ;; esac
  step "🔎 inspecting version" "${D}reading the installed ha-paneld package${X}"
  if raw="$(run_with_deadline "$timeout" adb_exec -s "$TARGET" shell dumpsys package "$PKG" 2>/dev/null)"; then
    installed="$(printf '%s\n' "$raw" | grep -m1 versionName | sed 's/.*versionName=//' | tr -d '\r ' || true)"
  else
    status=$?
    if [ "$status" -eq 124 ] || [ "$status" -eq 137 ]; then
      fail "installed-version inspection timed out after ${timeout}s" \
        "Android's package manager or adb is not responding. Nothing was installed or privileged." \
        "Restore adb/package-manager responsiveness, then re-run the same command."
    fi
    warn "could not read the installed ha-paneld version; Android will still enforce install compatibility"
    return 0
  fi
  [ -n "$installed" ] || return 0
  if [ "$installed" = "$TOINSTALL_VER" ]; then
    echo "${YEL}ℹ panel already on $installed — reinstalling.${X}"
  else
    echo "${GRN}↻ changing $installed → $TOINSTALL_VER${X}"
    echo "   ${D}Android will safely reject an unsupported downgrade; no app data is removed.${X}"
  fi
  return 0
}

# One export implementation, two obligations.
#
# An explicitly requested `--export FILE` is the deliverable of that command: the user asked for a
# trustworthy file, so anything short of one stops the run before installation.
#
# The implicit pre-upgrade export is not a deliverable. It is a convenience taken on the way to an
# ordinary in-place replacement, which is itself a recovery attempt, so its failure warns, withdraws
# whatever partial artifact exists and returns non-zero for the caller to continue past. Withdrawal
# matters as much as the warning: a half-written or rejected file left on disk would later be read as
# a recovery point that was never produced.
export_problem() {
  local mode="$1" reason="$2" headline="$3"
  shift 3
  [ "$mode" = advisory ] || fail "$headline" "$@"
  warn "pre-upgrade settings export: $reason"
}

export_config() {
  local destination="$1" mode="${2:-strict}" directory basename temporary http_status
  directory="$(dirname "$destination")"
  basename="$(basename "$destination")"
  if [ ! -d "$directory" ]; then
    export_problem "$mode" "the destination directory does not exist" \
      "config export destination directory does not exist" \
      "Create $directory first, then run the same --export command again."
    return 1
  fi
  if [ -L "$destination" ]; then
    export_problem "$mode" "the destination path is a symlink" \
      "refusing to replace a symlink as a secret config export destination" \
      "Choose a regular file path owned by you, then run the same --export command again."
    return 1
  fi
  if ! temporary="$(mktemp "$directory/.${basename}.partial.XXXXXX")"; then
    export_problem "$mode" "a secure working file could not be created in $directory" \
      "could not create a secure config export file" \
      "Check that $directory is writable, then run the same --export command again."
    return 1
  fi
  step "📦 exporting config" "${D}→ $destination (includes secrets — protect it)${X}"
  if ! http_status="$(curl -fsS --max-time 30 "$URL/api/v1/config/export?include_secrets=1" \
      -o "$temporary" -w '%{http_code}')"; then
    rm -f "$temporary"
    export_problem "$mode" "the panel did not answer the export request" \
      "config export failed; the panel was not changed" \
      "Confirm $URL opens from this computer, then run the same --export command again."
    return 1
  fi
  if [ "$http_status" = 202 ] && approval_required_response "$temporary"; then
    rm -f "$temporary"
    export_problem "$mode" "the panel requires on-panel approval before it will release the bundle" \
      "config export requires approval on the panel; the panel was not changed" \
      "On the panel, open Configure → toolbar overflow → Security mode → Review approvals, approve the config export, then retry the identical --export command from this computer within ten minutes."
    return 1
  fi
  if [ "$http_status" != 200 ]; then
    rm -f "$temporary"
    export_problem "$mode" "the panel returned unexpected HTTP $http_status" \
      "config export returned unexpected HTTP $http_status; no config file was saved" \
      "Confirm the panel is running the expected ha-paneld build, then retry the same --export command."
    return 1
  fi
  if [ ! -s "$temporary" ]; then
    rm -f "$temporary"
    export_problem "$mode" "the panel returned an empty bundle" \
      "config export was empty; the panel was not changed" \
      "Retry the export. For uninstall recovery, separately create and verify a complete .hpb from the panel's Install page."
    return 1
  fi
  chmod 600 "$temporary" 2>/dev/null || true
  if [ -L "$destination" ]; then
    rm -f "$temporary"
    export_problem "$mode" "the destination path became a symlink during the export" \
      "config export destination became a symlink during export" \
      "Choose a regular file path in a directory only you can write, then retry."
    return 1
  fi
  if ! mv "$temporary" "$destination"; then
    rm -f "$temporary"
    export_problem "$mode" "the completed export could not be published to $destination" \
      "could not publish config export" \
      "The temporary export could not be moved to $destination. Check that the destination is writable, then retry."
    return 1
  fi
  if [ ! -s "$destination" ]; then
    rm -f "$destination"
    export_problem "$mode" "the published export was empty" \
      "published config export is empty" \
      "Remove $destination, then retry the export before changing the panel."
    return 1
  fi
  echo "   ${GRN}✓${X} $(wc -c < "$destination") bytes saved with owner-only permissions"
}

# Prepare the persistent backup directory, or stop before touching the panel. Everything written
# here — the settings export and now the data-store snapshot — carries credentials, so the directory
# must be a real owner-only one and is verified as such rather than assumed.
ensure_config_backup_dir() {
  local backup_mode
  [ ! -L "$CONFIG_BACKUP_DIR" ] || fail "config backup directory must not be a symlink" \
    "Choose a real owner-only directory with HAPANELD_CONFIG_BACKUP_DIR, then retry before changing the panel."
  mkdir -p "$CONFIG_BACKUP_DIR" || fail "could not create the persistent config backup directory" \
    "Check that $CONFIG_BACKUP_DIR is writable, then retry before changing the panel."
  chmod 700 "$CONFIG_BACKUP_DIR" 2>/dev/null || fail "could not protect the persistent config backup directory" \
    "Set its permissions to 700, then retry before changing the panel."
  backup_mode="$(stat -c '%a' "$CONFIG_BACKUP_DIR" 2>/dev/null || stat -f '%Lp' "$CONFIG_BACKUP_DIR" 2>/dev/null || true)"
  case "$backup_mode" in *[!0-7]*|'') fail "could not verify config backup directory permissions" \
    "Set $CONFIG_BACKUP_DIR to owner-only mode 700, then retry before changing the panel." ;; esac
  [ "${backup_mode: -2}" = 00 ] || fail "config backup directory is readable by other users" \
    "Protect $CONFIG_BACKUP_DIR with mode 700, then retry before changing the panel."
}

announce_export_withdrawn() {
  echo "   ${D}continuing with the in-place replacement without a pre-upgrade settings export; the panel keeps its existing data, and the database snapshot is attempted separately below${X}"
}

auto_export_before_upgrade() {
  local safe_target stamp
  # Test harnesses may skip the export entirely when exercising later transaction branches. The
  # production default (unset/0) attempts it; failure is advisory, handled below.
  [ "${HAPANELD_SKIP_AUTO_EXPORT:-0}" = 1 ] && return 0
  [ -n "$EXPORT_FILE" ] && return 0
  # A first installation has no configuration to back up, so absence is an ordinary outcome here and
  # only an unanswerable package query stops the run.
  classify_package_presence "$ADB_COMMAND_TIMEOUT_SECONDS"
  case "$PACKAGE_PRESENCE" in
    absent)
      echo "   ${D}fresh install — no existing config requires an upgrade backup${X}"
      return 0
      ;;
    unknown)
      fail "could not verify the installed panel before upgrade" \
        "The configuration backup could not be guaranteed, so no helper or APK was changed." \
        "Restore adb/package-manager responsiveness, then re-run the same command."
      ;;
  esac
  # From here every failure is advisory. The panel keeps its existing data across an `adb install -r`,
  # so refusing to replace the package because this convenience copy could not be taken would strand
  # the user on the build they are trying to move off — the opposite of a recovery. `fail`'s own
  # recovery text is suppressed because it describes stopping, which is not what happens here.
  if ! (ensure_config_backup_dir) >/dev/null 2>&1; then
    warn "pre-upgrade settings export: the owner-only host backup directory $CONFIG_BACKUP_DIR could not be prepared"
    announce_export_withdrawn
    return 0
  fi
  safe_target="$(printf '%s' "$TARGET" | LC_ALL=C tr -c 'A-Za-z0-9._-' '_')"
  stamp="$(date -u +%Y%m%dT%H%M%SZ)-$$"
  AUTO_EXPORT_FILE="$CONFIG_BACKUP_DIR/${safe_target}-${stamp}.json"
  if ! export_config "$AUTO_EXPORT_FILE" advisory; then
    # export_config removes whatever it created on the way out; each of its exits knows what exists.
    # What is withdrawn here is the CLAIM: downstream receipts read AUTO_EXPORT_FILE, and a receipt
    # naming a settings export that was never produced is worse than one reporting none.
    AUTO_EXPORT_FILE=""
    announce_export_withdrawn
    return 0
  fi
}

# Copy the panel's canonical data store to the host, alongside the settings export.
#
# The settings export is NOT a recovery point. Configuration, the entity catalog, proximity models,
# ambient history and the on-panel revision ring all live in ha-paneld.db, and until now nothing in
# this toolchain held a copy of it. An ordinary `adb install -r` preserves app data, so this matters
# in the failure path the recovery guidance itself sends people to — an uninstall, which destroys the
# store with no off-panel copy in existence.
#
# Root-capable panels attempt the database capture before an ordinary upgrade. Any unavailable or
# rejected database candidate warns and package replacement may continue. A reset deliberately makes
# no backup and does not enter this path. Sandboxed panels cannot reach app-private storage, so they
# retain the settings export as their only ordinary pre-upgrade capture.
#
# A receipt-capable app first quiesces writers, checkpoints and closes SQLite, then authenticates the
# exact closed database bytes for a direct copy. Older apps fall back once to a coherent live SQLite
# backup; copying an open ha-paneld.db without its WAL would lose committed data.
#
# BREAK-GLASS, and named so on disk. The panel's own .hpb backup deliberately does NOT contain this
# file, because restoring a raw database across versions is the downgrade config-reset hazard and
# restoring one onto a different panel carries the wrong identity. Neither objection applies to what
# is written here — it is the same panel, the same version, taken seconds before the upgrade — and it
# covers the one case an .hpb structurally cannot, since producing an .hpb needs the app to be
# serving HTTP and the panel that most needs a backup is the one whose app will not start.
#
# Nothing restores it automatically and nothing should: this is a manual, same-panel, same-version
# last resort. The `.break-glass.` infix exists so that whoever finds these files months from now
# reads that from the filename rather than assuming they are an ordinary restorable backup.
# Reclaim both halves of an interrupted capture. The panel-side staging is created, verified and
# normally removed entirely inside the one transaction script, so this only matters when the HOST
# dies between the script succeeding and the post-pull cleanup: the script cannot clean up state it
# already handed over. Removal rides the production adb() deadline wrapper, so a dead panel cannot
# hold the exit open.
snapshot_txn_defer_host_signals() {
  SNAPSHOT_TXN_DEFERRED_SIGNAL=""
  trap 'SNAPSHOT_TXN_DEFERRED_SIGNAL=130' INT
  trap 'SNAPSHOT_TXN_DEFERRED_SIGNAL=143' TERM
}

snapshot_txn_restore_host_signals() {
  local deferred
  trap 'handle_provision_signal 130' INT
  trap 'handle_provision_signal 143' TERM
  deferred="$SNAPSHOT_TXN_DEFERRED_SIGNAL"
  SNAPSHOT_TXN_DEFERRED_SIGNAL=""
  [ -z "$deferred" ] || handle_provision_signal "$deferred"
}

discard_db_snapshot_txn() {
  local stale
  # Keep each temporary hardlink until its final name is registered. If a signal lands after ln but
  # before registration, inode identity proves whether this run created the final path; an unrelated
  # pre-existing destination is never removed.
  if [ -n "${SNAPSHOT_TXN_HOST_DB_WORK:-}" ]; then
    if [ -n "${SNAPSHOT_TXN_HOST_DB_TARGET:-}" ] &&
       [ "$SNAPSHOT_TXN_HOST_DB_WORK" -ef "$SNAPSHOT_TXN_HOST_DB_TARGET" ]; then
      rm -f "$SNAPSHOT_TXN_HOST_DB_TARGET" 2>/dev/null || true
    fi
    rm -f "$SNAPSHOT_TXN_HOST_DB_WORK" 2>/dev/null || true
  fi
  if [ -n "${SNAPSHOT_TXN_HOST_RECEIPT_WORK:-}" ]; then
    if [ -n "${SNAPSHOT_TXN_HOST_RECEIPT_TARGET:-}" ] &&
       [ "$SNAPSHOT_TXN_HOST_RECEIPT_WORK" -ef "$SNAPSHOT_TXN_HOST_RECEIPT_TARGET" ]; then
      rm -f "$SNAPSHOT_TXN_HOST_RECEIPT_TARGET" 2>/dev/null || true
    fi
    rm -f "$SNAPSHOT_TXN_HOST_RECEIPT_WORK" 2>/dev/null || true
  fi
  SNAPSHOT_TXN_HOST_DB_WORK=""; SNAPSHOT_TXN_HOST_DB_TARGET=""
  SNAPSHOT_TXN_HOST_RECEIPT_WORK=""; SNAPSHOT_TXN_HOST_RECEIPT_TARGET=""
  # The database and its receipt are registered in their own variables (never one word-split
  # string) and removed together: a signal in the publication window can never leave a receipt
  # describing a file that is gone, or a file no receipt admits to. A host survivor is named in
  # the warning — it is a local file, and its removal needs nothing more than rm.
  for stale in "${SNAPSHOT_TXN_HOST_DB:-}" "${SNAPSHOT_TXN_HOST_RECEIPT:-}"; do
    [ -n "$stale" ] || continue
    rm -f "$stale" 2>/dev/null || true
    if [ -e "$stale" ] || [ -L "$stale" ]; then
      warn "a file from the interrupted database backup was left at $stale — remove it by hand"
    fi
  done
  SNAPSHOT_TXN_HOST_DB=""; SNAPSHOT_TXN_HOST_RECEIPT=""
  if [ -n "${SNAPSHOT_TXN_REMOTE:-}" ]; then
    stale="$SNAPSHOT_TXN_REMOTE"; SNAPSHOT_TXN_REMOTE=""
    # Staging is owner-only, uniquely named so no later run can collide with it, and confined to
    # panel-local tmp. Cleanup remains best-effort because the transport may already be unavailable.
    run_root "rm -rf $stale ${stale}-script" >/dev/null 2>&1 || true
  fi
}

# Backup availability is best-effort for an ordinary in-place upgrade: Android's package replace
# preserves app data, and a fixed free-space guess must not turn a useful precaution into an upgrade
# blocker. A reset never enters this backup path. No incomplete backup is accepted or published.
snapshot_txn_refuse() {
  local reason="$1" advice="$2"
  discard_db_snapshot_txn
  warn "data-store snapshot: $reason — continuing the ordinary in-place upgrade WITHOUT a database restore point. $advice"
  return 0
}

# A rejected backup is discarded and treated as unavailable. Android package replacement preserves
# the original database, so the ordinary upgrade remains eligible to continue.
snapshot_txn_reject_unsafe() {
  local reason="$1" advice="$2"
  snapshot_txn_refuse "$reason" "$advice"
}

prepare_upgrade_quiescence() {
  local nonce output receipt ready_count tag receipt_nonce receipt_pid receipt_vcode
  local receipt_bytes receipt_sha receipt_uv receipt_rows receipt_extra timeout
  UPGRADE_QUIESCE_NONCE=""; UPGRADE_QUIESCE_PKG=""
  UPGRADE_RECEIPT_PID=""; UPGRADE_RECEIPT_VERSION_CODE=""; UPGRADE_RECEIPT_DATABASE_BYTES=""
  UPGRADE_RECEIPT_DATABASE_SHA256=""; UPGRADE_RECEIPT_USER_VERSION=""; UPGRADE_RECEIPT_APP_STATE_ROWS=""
  nonce="$(host_transaction_id)" || return 1
  # The bridge answers for the panel until the successor exists, so quiesce whichever identity holds
  # the data. Retain custody before transmission: a timeout or malformed result cannot prove the app
  # failed to arm. A harmless RELEASE to an old build is safer than abandoning a quiesced current one.
  resolve_data_package
  UPGRADE_QUIESCE_NONCE="$nonce"
  UPGRADE_QUIESCE_PKG="$DATA_PKG"
  timeout="${UPGRADE_PREPARE_TIMEOUT_SECONDS:-45}"
  case "$timeout" in ''|*[!0-9]*|0) timeout=45 ;; esac
  output="$(run_with_deadline "$timeout" adb_exec -s "$TARGET" shell am broadcast --user 0 \
    -a io.github.maxlyth.hapaneld.action.PREPARE_UPGRADE \
    -n "$(app_component "$UPGRADE_QUIESCE_PKG" .UpgradeControlReceiver)" --es nonce "$nonce" 2>/dev/null | tr -d '\r')" || output=""
  receipt="$(printf '%s\n' "$output" | sed -n 's/^Broadcast completed: result=-1, data="\([^"]*\)"$/\1/p')"
  ready_count="$(printf '%s\n' "$receipt" | grep -c '^HAPANELD_UPGRADE_READY_V1:' || true)"
  [ "$ready_count" = 1 ] || return 1
  IFS=: read -r tag receipt_nonce receipt_pid receipt_vcode receipt_bytes receipt_sha receipt_uv receipt_rows receipt_extra <<EOF
$receipt
EOF
  [ "$tag" = HAPANELD_UPGRADE_READY_V1 ] && [ "$receipt_nonce" = "$nonce" ] && [ -z "$receipt_extra" ] || return 1
  printf '%s\n' "$receipt_nonce" | grep -Eq '^[0-9a-f]{32}$' || return 1
  case "$receipt_pid:$receipt_vcode:$receipt_bytes:$receipt_uv:$receipt_rows" in
    *[!0-9:]*|:*|*::*|*:) return 1 ;;
  esac
  [ "${#receipt_pid}" -le 10 ] && [ "${#receipt_vcode}" -le 10 ] && \
    [ "${#receipt_bytes}" -le 20 ] && [ "${#receipt_uv}" -le 10 ] && \
    [ "${#receipt_rows}" -le 20 ] || return 1
  [ "$receipt_pid" -gt 0 ] && [ "$receipt_vcode" -gt 0 ] && [ "$receipt_bytes" -gt 0 ] || return 1
  [ "$receipt_uv" -ge "$DB_SUPPORTED_USER_VERSION_MIN" ] && \
    [ "$receipt_uv" -le "$DB_SUPPORTED_USER_VERSION_MAX" ] && [ "$receipt_rows" -gt 0 ] || return 1
  printf '%s\n' "$receipt_sha" | grep -Eq '^[0-9a-f]{64}$' || return 1
  UPGRADE_RECEIPT_PID="$receipt_pid"
  UPGRADE_RECEIPT_VERSION_CODE="$receipt_vcode"
  UPGRADE_RECEIPT_DATABASE_BYTES="$receipt_bytes"
  UPGRADE_RECEIPT_DATABASE_SHA256="$receipt_sha"
  UPGRADE_RECEIPT_USER_VERSION="$receipt_uv"
  UPGRADE_RECEIPT_APP_STATE_ROWS="$receipt_rows"
  return 0
}

release_upgrade_quiescence() {
  local nonce pkg output timeout attempt released_count root_start_failed=0
  nonce="${UPGRADE_QUIESCE_NONCE:-}"
  [ -n "$nonce" ] || return 0
  # RELEASE goes back to the identity that armed, never to the one this run happens to install.
  pkg="${UPGRADE_QUIESCE_PKG:-$PKG}"
  timeout="${UPGRADE_RELEASE_TIMEOUT_SECONDS:-10}"
  case "$timeout" in ''|*[!0-9]*|0) timeout=10 ;; esac
  attempt=1
  while [ "$attempt" -le 2 ]; do
    output="$(run_with_deadline "$timeout" adb_exec -s "$TARGET" shell am broadcast --user 0 \
      -a io.github.maxlyth.hapaneld.action.RELEASE_UPGRADE \
      -n "$(app_component "$pkg" .UpgradeControlReceiver)" --es nonce "$nonce" 2>/dev/null | tr -d '\r')" || output=""
    released_count="$(printf '%s\n' "$output" | grep -Fxc \
      "Broadcast completed: result=-1, data=\"HAPANELD_UPGRADE_RELEASED_V1:$nonce\"" || true)"
    # The receiver may have opened its barrier but be unable to restart its own foreground service
    # on API 31. Ask through the already-established root authority after every RELEASE attempt;
    # exact receipt validation below remains the only authority that can disown the nonce.
    if ! run_root "am start-foreground-service --user 0 -n $(app_component "$pkg" .PaneldService)" >/dev/null 2>&1; then
      root_start_failed=1
    fi
    if [ "$released_count" = 1 ]; then
      # Disown only after the exact ordered-broadcast acknowledgement.
      UPGRADE_QUIESCE_NONCE=""; UPGRADE_QUIESCE_PKG=""
      if [ "$root_start_failed" = 1 ]; then
        warn "upgrade release was acknowledged, but the root-authoritative PaneldService restart could not be confirmed; launch ha-paneld once on the panel"
      fi
      return 0
    fi
    attempt=$((attempt + 1))
  done
  # Keep the nonce owned. A later cleanup call may retry it; otherwise the app's bounded watchdog
  # resumes service. Never turn an unverified RELEASE into a disowned lease.
  if [ "$root_start_failed" = 1 ]; then
    warn "upgrade quiescence RELEASE was not acknowledged after two attempts, and the root-authoritative PaneldService restart could not be confirmed; the app watchdog will resume service automatically"
  else
    warn "upgrade quiescence RELEASE was not acknowledged after two attempts; an idempotent root service start was requested and the app watchdog remains the fallback"
  fi
  return 0
}

copy_root_file_binary() {
  local source="$1" destination="$2" command quoted
  command="cat $source"
  quoted="$(quote_root_command "$command")"
  case "$SU_FORM" in
    shell)      run_with_deadline "$ADB_COMMAND_TIMEOUT_SECONDS" adb_exec -s "$TARGET" exec-out cat "$source" > "$destination" ;;
    su0join)    run_with_deadline "$ADB_COMMAND_TIMEOUT_SECONDS" adb_exec -s "$TARGET" exec-out su 0 "$quoted" > "$destination" ;;
    su0shc)     run_with_deadline "$ADB_COMMAND_TIMEOUT_SECONDS" adb_exec -s "$TARGET" exec-out su 0 sh -c "$quoted" > "$destination" ;;
    surootjoin) run_with_deadline "$ADB_COMMAND_TIMEOUT_SECONDS" adb_exec -s "$TARGET" exec-out su root "$quoted" > "$destination" ;;
    surootshc)  run_with_deadline "$ADB_COMMAND_TIMEOUT_SECONDS" adb_exec -s "$TARGET" exec-out su root sh -c "$quoted" > "$destination" ;;
    suc)        run_with_deadline "$ADB_COMMAND_TIMEOUT_SECONDS" adb_exec -s "$TARGET" exec-out su -c "$quoted" > "$destination" ;;
    *) return 1 ;;
  esac
}

snapshot_prepared_database() {
  local base="$1" host_db receipt host_sha host_bytes sqlite_bin integrity user_version app_state_rows
  host_db="${base%.break-glass}-$UPGRADE_QUIESCE_NONCE.break-glass.db"
  snapshot_txn_defer_host_signals
  if [ -e "$host_db" ] || [ -L "$host_db" ] || ! (umask 077; set -o noclobber; : > "$host_db") 2>/dev/null; then
    snapshot_txn_restore_host_signals
    snapshot_txn_refuse "could not create the unique owner-only host database file" "Check that the backup directory is writable."
    return 0
  fi
  SNAPSHOT_TXN_HOST_DB="$host_db"
  snapshot_txn_restore_host_signals
  chmod 600 "$host_db" 2>/dev/null || true
  if ! copy_root_file_binary "/data/data/${UPGRADE_QUIESCE_PKG:-$PKG}/databases/ha-paneld.db" "$host_db" || [ ! -s "$host_db" ]; then
    snapshot_txn_refuse "the quiesced database could not be copied from the panel" "Check adb/root responsiveness to $TARGET, then re-run."
    return 0
  fi
  if ! host_bytes="$(wc -c < "$host_db" 2>/dev/null | tr -d ' ')"; then
    snapshot_txn_refuse "the direct copy size could not be read on this host" "Check the backup filesystem, then re-run."
    return 0
  fi
  if ! host_sha="$(host_sha256 "$host_db" 2>/dev/null)"; then
    snapshot_txn_refuse "the direct copy could not be hashed on this host" "Install sha256sum (or shasum), then re-run."
    return 0
  fi
  if [ "$host_bytes" != "$UPGRADE_RECEIPT_DATABASE_BYTES" ] || [ "$host_sha" != "$UPGRADE_RECEIPT_DATABASE_SHA256" ]; then
    snapshot_txn_reject_unsafe "the direct copy did not match the app's clean-shutdown receipt" "The rejected copy was removed; re-run against the same panel."
    return 0
  fi
  sqlite_bin="${HAPANELD_HOST_SQLITE3:-}"
  [ -n "$sqlite_bin" ] || sqlite_bin="$(command -v sqlite3 2>/dev/null || true)"
  [ -n "$sqlite_bin" ] && [ -x "$sqlite_bin" ] || {
    snapshot_txn_refuse "the host has no sqlite3 executable for local validation" "Install sqlite3, then retry if you need an automatic restore point."
    return 0
  }
  integrity="$("$sqlite_bin" "$host_db" 'PRAGMA integrity_check;' 2>/dev/null || true)"
  user_version="$("$sqlite_bin" "$host_db" 'PRAGMA user_version;' 2>/dev/null || true)"
  app_state_rows="$("$sqlite_bin" "$host_db" 'SELECT count(*) FROM app_state;' 2>/dev/null || true)"
  if [ "$integrity" != ok ] || [ "$user_version" != "$UPGRADE_RECEIPT_USER_VERSION" ] || \
     [ "$app_state_rows" != "$UPGRADE_RECEIPT_APP_STATE_ROWS" ]; then
    snapshot_txn_reject_unsafe "the direct copy failed local SQLite receipt validation" "The rejected copy was removed; re-run against the same panel."
    return 0
  fi
  receipt="$host_db.backup-receipt.txt"
  snapshot_txn_defer_host_signals
  if [ -e "$receipt" ] || [ -L "$receipt" ] || ! (umask 077; set -o noclobber; : > "$receipt") 2>/dev/null; then
    snapshot_txn_restore_host_signals
    snapshot_txn_refuse "could not create the unique owner-only direct-copy receipt" "Check that the backup directory is writable."
    return 0
  fi
  SNAPSHOT_TXN_HOST_RECEIPT="$receipt"
  snapshot_txn_restore_host_signals
  if ! {
    printf 'receipt_version=3\n'
    printf 'capture_mode=quiesced-direct\n'
    printf 'database=%s\n' "$host_db"
    printf 'database_bytes=%s\n' "$host_bytes"
    printf 'database_sha256_host=%s\n' "$host_sha"
    printf 'database_integrity=ok\n'
    printf 'database_app_state_rows=%s\n' "$app_state_rows"
    printf 'database_user_version=%s\n' "$user_version"
    printf 'app_version_code=%s\n' "$UPGRADE_RECEIPT_VERSION_CODE"
    printf 'app_pid=%s\n' "$UPGRADE_RECEIPT_PID"
    printf 'quiescence_nonce=%s\n' "$UPGRADE_QUIESCE_NONCE"
    printf 'settings_export=%s\n' "${AUTO_EXPORT_FILE:-${EXPORT_FILE:-none}}"
  } > "$receipt" || ! chmod 600 "$receipt"; then
    snapshot_txn_refuse "the direct-copy receipt could not be written" "Check that the backup directory is writable."
    return 0
  fi
  # Handoff is one signal-atomic ownership transition: before it, abort cleanup removes both
  # registered artifacts; after it, captured state owns and preserves both.
  snapshot_txn_defer_host_signals
  SNAPSHOT_TXN_HOST_DB=""
  SNAPSHOT_TXN_HOST_RECEIPT=""
  snapshot_txn_restore_host_signals
  echo "   ${GRN}✓${X} data-store snapshot: ${B}$host_db${X} ${D}(quiesced direct copy, receipt and local SQLite validation matched)${X}"
  echo "   ${D}        Receipt: $receipt${X}"
  echo "HAPANELD_SNAPSHOT_RESULT=captured"
  return 0
}

# Capture the canonical store as ONE on-panel transaction, then verify what arrived.
#
# A multi-step capture — stage, admit, verify, hash, pull, receipt, clean as separate host↔panel
# exchanges — has a custody edge at every seam, and each edge needs its own interruption and
# failure handling to stay safe. This shape removes the seams: a single generated script, pushed and
# hash-verified like the root-helper transaction script, performs backup, integrity check, schema
# and content admission, provenance capture and digest emission ATOMICALLY on the panel, cleans
# itself up on every internal failure, and speaks one machine-readable manifest. The host then does
# exactly one pull, one manifest-vs-bytes check, one receipt written strictly AFTER verification,
# and one best-effort cleanup of uniquely owned staging. There is no window in which a receipt can
# claim what verification has not proven, no staging the host knows about before the script owns it,
# and no discovery state
# consulted outside the transaction that acts on it.
snapshot_panel_database() {
  local db_source stage base safe_target stamp script_file script_sha panel_sha out line
  local mf_bytes="" mf_sha="" mf_integrity="" mf_rows="" mf_uv="" mf_vcode="" mf_vname="" mf_sqlite="" txn_ok=0 fail_reason=""
  local pulled_bytes host_sha receipt_tmp receipt
  # Discovery is tri-state: "not installed" must be the panel's positive answer, never the silence
  # of a transport that could not be asked. Unknown is reported through the explicit refusal policy,
  # not silently treated as a fresh install.
  classify_package_presence "$ADB_COMMAND_TIMEOUT_SECONDS"
  case "$PACKAGE_PRESENCE" in
    unknown)
      snapshot_txn_refuse "the panel could not be asked whether ha-paneld is installed" \
        "Check adb connectivity to $TARGET, then re-run."
      return 0
      ;;
  esac
  # The install target's absence is not the panel's. Before the handover the bridge is the package
  # holding the database this capture protects, and only when neither identity is installed is there
  # nothing to capture.
  resolve_data_package
  [ "$PACKAGE_PRESENCE" != absent ] || [ "$DATA_PKG" != "$PKG" ] || return 0
  # The run's one root-route verdict, resolved before this gate, decides the capture. Both unknown
  # verdicts refuse: a probe that never answered or a transport that died mid-question looks
  # exactly like a genuine "no root", and could recover minutes later, just in time for the
  # mutation — skipping on an undecided answer would silently drop the restore point on exactly
  # the panel that is misbehaving. The empty-verdict arm is an internal invariant guard: the main
  # flow resolves the route before any capture, and an unresolved route must never read as "no".
  # Positive guard: ONLY a verdict of exactly "rooted" proceeds to the capture. Written this way so
  # that deleting or mis-editing any arm below cannot let an unhandled verdict fall through into a
  # capture — the structure, not the completeness of the case, is what keeps this closed.
  if [ "$ROOT_ROUTE_VERDICT" != rooted ]; then
    case "$ROOT_ROUTE_VERDICT" in
      unrooted)
        # No root route means /data/data is unreachable by design; the settings export above remains
        # the only capture. This is a capability boundary, not a failure to reach a reachable
        # database — and it is a PROVEN "no", resolved once for the whole run, so the mutation
        # phases act on this same answer and can never proceed as root after this skip.
        echo "   ${D}data-store snapshot: skipped — this panel has no root route, so only settings could be saved${X}"
        return 0
        ;;
      unknown-timeout)
        snapshot_txn_refuse "the panel's root route could not be determined (its probe or adb-root escalation never answered in time)" \
          "Check adb responsiveness on $TARGET, then re-run."
        return 0
        ;;
      unknown-transport)
        snapshot_txn_refuse "the panel's root route could not be determined (its transport failed during root discovery)" \
          "Check adb connectivity to $TARGET, then re-run."
        return 0
        ;;
      *)
        snapshot_txn_refuse "the panel's root route was never resolved before the capture gate" \
          "This is an installer defect; please report it."
        return 0
        ;;
    esac
    # Unreachable: every arm above returns. Present so that a future edit which drops an arm skips
    # the capture rather than taking it.
    return 0
  fi
  db_source="/data/data/$DATA_PKG/databases/ha-paneld.db"
  # A globally unique staging identity: two hosts can share a pid, and a collision must be
  # impossible rather than unlikely, because the loser of a staging collision must never remove the
  # winner's in-flight capture. With 128 bits of /dev/urandom in the name, the registered path
  # cannot denote another run's staging, which is why registration may precede creation below.
  # The supported operating model is one installer run per panel at a time. The script still refuses
  # a staging collision rather than touching a path it did not create.
  local txn_capture_id
  txn_capture_id="$(host_transaction_id)" || { snapshot_txn_refuse "could not create a unique capture identity" "Check host access to /dev/urandom, then retry."; return 0; }
  stage="/data/local/tmp/.hapaneld-db-txn.$txn_capture_id"
  if [ -n "$AUTO_EXPORT_FILE" ]; then
    base="${AUTO_EXPORT_FILE%.json}"
  elif [ -n "$EXPORT_FILE" ]; then
    base="${EXPORT_FILE%.json}"
  else
    if ! (ensure_config_backup_dir) >/dev/null 2>&1; then
      snapshot_txn_refuse "the owner-only host backup directory could not be prepared" \
        "Check that $CONFIG_BACKUP_DIR is writable and mode 700, then retry."
      return 0
    fi
    if ! safe_target="$(printf '%s' "$TARGET" | LC_ALL=C tr -c 'A-Za-z0-9._-' '_')"; then
      snapshot_txn_refuse "the panel address could not be converted into a safe backup name" "Check host text tooling, then re-run."
      return 0
    fi
    if ! stamp="$(date -u +%Y%m%dT%H%M%SZ)-$$"; then
      snapshot_txn_refuse "the host could not create a backup timestamp" "Check the host date command, then re-run."
      return 0
    fi
    base="$CONFIG_BACKUP_DIR/${safe_target}-${stamp}"
  fi
  base="$base.break-glass"
  if prepare_upgrade_quiescence; then
    snapshot_prepared_database "$base"
    return 0
  fi
  warn "data-store snapshot: no valid upgrade-ready receipt was received (unsupported, non-ready, malformed, or timed out); using one legacy live SQLite backup attempt"
  # Ownership is registered BEFORE anything exists on either side, so an interruption at any later
  # instant already has a cleanup owner.
  SNAPSHOT_TXN_REMOTE="$stage"
  script_file="$(mktemp)" || { snapshot_txn_refuse "could not create a working file on this host" "Check TMPDIR is writable."; return 0; }
  # A recognisable name: the generated script is hashed and pushed under it, and digest tooling on
  # some hosts special-cases by suffix.
  if ! mv "$script_file" "$script_file.db-txn-script"; then
    rm -f "$script_file" 2>/dev/null || true
    snapshot_txn_refuse "the capture working file could not be named safely" "Check TMPDIR, then re-run."
    return 0
  fi
  script_file="$script_file.db-txn-script"
  if ! cat > "$script_file" <<'EOF'
#!/system/bin/sh
# One-transaction database capture. Every failure prints SNAPTXN_FAIL=<stage> as its LAST line and
# removes the staging it created; success prints an unconditional manifest then SNAPTXN_OK. Nothing
# outside @STAGE@ is ever written, and no state survives a failure.
set -u
read_vcode() { dumpsys package @PKG@ 2>/dev/null | sed -n 's/.*versionCode=\([0-9]\{1,\}\).*/\1/p' | head -1; }
stage_created=0
refuse() {
  # Only the stage THIS transaction created is its to remove: a refusal before creation — or
  # because the path already exists — must leave another owner's staging untouched.
  [ "$stage_created" = 1 ] && rm -rf @STAGE@ 2>/dev/null
  echo "SNAPTXN_FAIL=$1"
  exit 1
}
sqlite3_bin=""
if [ -x /system/bin/sqlite3 ]; then sqlite3_bin=/system/bin/sqlite3
else sqlite3_bin=$(command -v sqlite3 2>/dev/null) || sqlite3_bin=""; fi
case "$sqlite3_bin" in /*) ;; *) refuse sqlite_missing ;; esac
[ ! -L @DB_SOURCE@ ] || refuse source_not_regular
[ -e @DB_SOURCE@ ] || refuse source_missing
[ -f @DB_SOURCE@ ] || refuse source_not_regular
umask 077
mkdir -m 700 @STAGE@ 2>/dev/null || refuse stage_exists
stage_created=1
vcode_before=$(read_vcode)
case "$vcode_before" in ''|*[!0-9]*) refuse provenance_unreadable ;; esac
"$sqlite3_bin" @DB_SOURCE@ ".backup '@STAGE@/ha-paneld.db'" 2>/dev/null || refuse backup_failed
[ -s @STAGE@/ha-paneld.db ] || refuse backup_empty
integrity=$("$sqlite3_bin" @STAGE@/ha-paneld.db 'PRAGMA integrity_check;' 2>/dev/null) || refuse integrity_unreadable
[ "$integrity" = ok ] || refuse integrity_failed
rows=$("$sqlite3_bin" @STAGE@/ha-paneld.db 'SELECT count(*) FROM app_state;' 2>/dev/null) || refuse rows_unreadable
case "$rows" in ''|*[!0-9]*) refuse rows_unreadable ;; esac
[ "$rows" -gt 0 ] || refuse rows_empty
uv=$("$sqlite3_bin" @STAGE@/ha-paneld.db 'PRAGMA user_version;' 2>/dev/null) || refuse schema_unreadable
case "$uv" in ''|*[!0-9]*) refuse schema_unreadable ;; esac
[ "$uv" -ge @VMIN@ ] || refuse schema_alien
[ "$uv" -le @VMAX@ ] || refuse schema_alien
# Provenance is captured INSIDE the transaction that admits the snapshot: a break-glass copy is only
# restorable onto the exact build that wrote it, and reading the build in a separate step reopens the
# window in which an install changes it between capture and record.
vcode=$(read_vcode)
case "$vcode" in ''|*[!0-9]*) refuse provenance_unreadable ;; esac
# The build read before the backup and the build read after every admission check must agree, or an
# install raced the capture and this snapshot belongs to no attributable build.
[ "$vcode" = "$vcode_before" ] || refuse provenance_changed
vname=$(dumpsys package @PKG@ 2>/dev/null | sed -n 's/.*versionName=\([^ ]*\).*/\1/p' | head -1)
[ -n "$vname" ] || vname=unknown
bytes=$(wc -c < @STAGE@/ha-paneld.db) || refuse size_unreadable
case "$bytes" in ''|*[!0-9]*) refuse size_unreadable ;; esac
sha=$(sha256sum @STAGE@/ha-paneld.db 2>/dev/null || toybox sha256sum @STAGE@/ha-paneld.db 2>/dev/null || busybox sha256sum @STAGE@/ha-paneld.db 2>/dev/null) || sha=""
sha=${sha%% *}
case "$sha" in [0-9a-f]*) [ "${#sha}" -eq 64 ] || sha=none ;; *) sha=none ;; esac
chown shell:shell @STAGE@ @STAGE@/ha-paneld.db 2>/dev/null
chmod 700 @STAGE@ && chmod 600 @STAGE@/ha-paneld.db || refuse permissions_failed
echo "MF_BYTES=$bytes"
echo "MF_SHA256=$sha"
echo "MF_INTEGRITY=$integrity"
echo "MF_ROWS=$rows"
echo "MF_USER_VERSION=$uv"
echo "MF_VCODE=$vcode"
echo "MF_VNAME=$vname"
echo "MF_SQLITE=$sqlite3_bin"
echo "SNAPTXN_OK"
EOF
  then
    rm -f "$script_file" 2>/dev/null || true
    snapshot_txn_refuse "the capture script could not be written on this host" "Check TMPDIR, then re-run."
    return 0
  fi
  if ! sed -e "s|@STAGE@|$stage|g" -e "s|@DB_SOURCE@|$db_source|g" -e "s|@PKG@|$DATA_PKG|g" \
      -e "s|@VMIN@|$DB_SUPPORTED_USER_VERSION_MIN|g" -e "s|@VMAX@|$DB_SUPPORTED_USER_VERSION_MAX|g" \
      "$script_file" > "$script_file.ready" || ! mv "$script_file.ready" "$script_file"; then
    rm -f "$script_file" "$script_file.ready" 2>/dev/null || true
    snapshot_txn_refuse "the capture script could not be finalized on this host" "Check TMPDIR and sed, then re-run."
    return 0
  fi
  script_sha="$(host_sha256 "$script_file")" || { rm -f "$script_file" 2>/dev/null || true; snapshot_txn_refuse "could not hash the capture script" "Check host sha256 tooling."; return 0; }
  if ! adb -s "$TARGET" push "$script_file" "${stage}-script" >/dev/null 2>&1; then
    rm -f "$script_file" 2>/dev/null || true
    snapshot_txn_refuse "the capture script could not be pushed to the panel" "Check adb connectivity to $TARGET."
    return 0
  fi
  if ! rm -f "$script_file" 2>/dev/null; then
    snapshot_txn_refuse "the capture working file could not be removed" "Remove $script_file by hand, then re-run if a backup is required."
    return 0
  fi
  # The pushed bytes are proven before they run as root — the same rule the root-helper transaction
  # script follows. The panel's own hash decides; a transport that cannot hash cannot execute.
  if ! panel_sha="$(run_root "sha256sum ${stage}-script 2>/dev/null || toybox sha256sum ${stage}-script 2>/dev/null || busybox sha256sum ${stage}-script 2>/dev/null" 2>/dev/null | tr -d '\r')"; then
    snapshot_txn_refuse "the capture script could not be hashed through the panel's root route" "Check adb/root responsiveness, then re-run."
    return 0
  fi
  panel_sha="${panel_sha%% *}"
  if [ "$panel_sha" != "$script_sha" ]; then
    snapshot_txn_reject_unsafe "the capture script did not arrive intact on the panel" "Check adb connectivity to $TARGET, then re-run."
    return 0
  fi
  out="$(run_root "sh ${stage}-script" 2>&1 | tr -d '\r')" || true
  if ! while IFS= read -r line; do
    case "$line" in
      MF_BYTES=*)        mf_bytes="${line#MF_BYTES=}" ;;
      MF_SHA256=*)       mf_sha="${line#MF_SHA256=}" ;;
      MF_INTEGRITY=*)    mf_integrity="${line#MF_INTEGRITY=}" ;;
      MF_ROWS=*)         mf_rows="${line#MF_ROWS=}" ;;
      MF_USER_VERSION=*) mf_uv="${line#MF_USER_VERSION=}" ;;
      MF_VCODE=*)        mf_vcode="${line#MF_VCODE=}" ;;
      MF_VNAME=*)        mf_vname="${line#MF_VNAME=}" ;;
      MF_SQLITE=*)       mf_sqlite="${line#MF_SQLITE=}" ;;
      SNAPTXN_OK)        txn_ok=1 ;;
      SNAPTXN_FAIL=*)    fail_reason="${line#SNAPTXN_FAIL=}" ;;
    esac
  done <<EOF2
$out
EOF2
  then
    snapshot_txn_refuse "the capture manifest could not be parsed on this host" "Check host shell resources, then re-run."
    return 0
  fi
  if [ "$txn_ok" != 1 ]; then
    if [ "$fail_reason" = stage_exists ]; then
      # The path is occupied by a DIFFERENT owner's staging — with a urandom identity that is a
      # deliberate-interference signal, not a plausible accident. Either way it is not this run's to
      # remove: disown it so the refusal cleanup cannot delete the winner's in-flight capture.
      SNAPSHOT_TXN_REMOTE=""
    fi
    case "$fail_reason" in
      integrity_failed|rows_empty|schema_alien|provenance_unreadable|provenance_changed|source_not_regular|stage_exists)
        snapshot_txn_reject_unsafe "the on-panel capture transaction refused ($fail_reason)" \
          "The panel left nothing behind. Fix the named stage on the panel, then re-run."
        ;;
      *)
        snapshot_txn_refuse "the on-panel capture transaction refused (${fail_reason:-no transaction verdict})" \
          "The panel left nothing behind. Fix the named stage on the panel, then re-run."
        ;;
    esac
    return 0
  fi
  # Every manifest field is required — a script that succeeded without fully describing its output
  # did not run the shipped transaction.
  case "$mf_bytes" in ''|*[!0-9]*) snapshot_txn_reject_unsafe "the capture manifest is incomplete (bytes)" "Re-run against the same panel."; return 0 ;; esac
  case "$mf_rows" in ''|*[!0-9]*) snapshot_txn_reject_unsafe "the capture manifest is incomplete (rows)" "Re-run against the same panel."; return 0 ;; esac
  case "$mf_uv" in ''|*[!0-9]*) snapshot_txn_reject_unsafe "the capture manifest is incomplete (schema)" "Re-run against the same panel."; return 0 ;; esac
  case "$mf_vcode" in ''|*[!0-9]*) snapshot_txn_reject_unsafe "the capture manifest is incomplete (provenance)" "Re-run against the same panel."; return 0 ;; esac
  [ -n "$mf_integrity" ] && [ -n "$mf_sha" ] && [ -n "$mf_sqlite" ] || { snapshot_txn_reject_unsafe "the capture manifest is incomplete" "Re-run against the same panel."; return 0; }
  if [ "$mf_sha" != none ] && ! printf '%s\n' "$mf_sha" | grep -Eq '^[0-9a-f]{64}$'; then
    snapshot_txn_reject_unsafe "the capture manifest contains an invalid panel digest" "Re-run against the same panel."
    return 0
  fi
  # The pull lands in an owner-only working file, never at the final name: the final name must only
  # ever hold a fully verified snapshot, and a pre-existing file or symlink there is refused, not
  # followed and not replaced.
  # The working file is created in the destination's own directory — never a TMPDIR fallback, which
  # can sit on another filesystem where the no-replace ln publication below cannot work at all.
  local pull_tmp
  snapshot_txn_defer_host_signals
  pull_tmp="$(mktemp "$(dirname "$base.db")/.pull.XXXXXX" 2>/dev/null)" || pull_tmp=""
  if [ -n "$pull_tmp" ]; then
    SNAPSHOT_TXN_HOST_DB="$pull_tmp"
    SNAPSHOT_TXN_HOST_DB_WORK="$pull_tmp"
    if mv "$pull_tmp" "$pull_tmp.break-glass.db"; then
      pull_tmp="$pull_tmp.break-glass.db"
      SNAPSHOT_TXN_HOST_DB="$pull_tmp"
      SNAPSHOT_TXN_HOST_DB_WORK="$pull_tmp"
    else
      pull_tmp=""
    fi
  fi
  snapshot_txn_restore_host_signals
  if [ -z "$pull_tmp" ]; then
    snapshot_txn_refuse "could not create a working file beside $base.db" "Check that the backup directory is writable."
    return 0
  fi
  if ! run_with_deadline 60 adb_exec -s "$TARGET" pull "$stage/ha-paneld.db" "$pull_tmp" >/dev/null 2>&1 || [ ! -s "$pull_tmp" ]; then
    snapshot_txn_refuse "the verified snapshot could not be pulled from the panel" "Check adb connectivity to $TARGET, then re-run."
    return 0
  fi
  chmod 600 "$pull_tmp" 2>/dev/null || true
  if ! pulled_bytes="$(wc -c < "$pull_tmp" 2>/dev/null | tr -d ' ')"; then
    snapshot_txn_refuse "the pulled snapshot size could not be read on this host" "Check the backup filesystem, then re-run."
    return 0
  fi
  if [ "$pulled_bytes" != "$mf_bytes" ]; then
    snapshot_txn_reject_unsafe "the pulled snapshot is $pulled_bytes bytes but the panel captured $mf_bytes" "Check adb connectivity to $TARGET, then re-run."
    return 0
  fi
  if [ -e "$base.db" ] || [ -L "$base.db" ]; then
    snapshot_txn_refuse "the snapshot destination $base.db already exists" "Move it aside by hand, then re-run."
    return 0
  fi
  # ln is the no-replace publication: it fails atomically if anything appeared at the destination
  # since the check above, and never follows a symlink into overwriting something else.
  SNAPSHOT_TXN_HOST_DB_TARGET="$base.db"
  if ! ln "$pull_tmp" "$base.db" 2>/dev/null; then
    snapshot_txn_refuse "the snapshot could not be published exclusively at $base.db" "Check the backup directory, then re-run."
    return 0
  fi
  SNAPSHOT_TXN_HOST_DB="$base.db"
  rm -f "$pull_tmp" 2>/dev/null || true
  SNAPSHOT_TXN_HOST_DB_WORK=""; SNAPSHOT_TXN_HOST_DB_TARGET=""
  # Everything the receipt will claim is re-read from the PUBLISHED file, so the receipt binds the
  # bytes a later reader will actually find, not the working copy they came from.
  if ! pulled_bytes="$(wc -c < "$base.db" 2>/dev/null | tr -d ' ')"; then
    snapshot_txn_refuse "the published snapshot size could not be read on this host" "Check the backup filesystem, then re-run."
    return 0
  fi
  if [ "$pulled_bytes" != "$mf_bytes" ]; then
    snapshot_txn_reject_unsafe "the published snapshot is $pulled_bytes bytes but the panel captured $mf_bytes" "Re-run against the same panel."
    return 0
  fi
  # The host digest is mandatory because the host copy is the artifact an operator may later use.
  # A panel without digest tooling may report `none`; byte count still binds that transfer, while a
  # panel digest, when present, must match the mandatory host digest.
  if ! host_sha="$(host_sha256 "$base.db" 2>/dev/null)"; then
    snapshot_txn_refuse "the published snapshot could not be hashed on this host" "Install sha256sum (or shasum), then re-run."
    return 0
  fi
  case "$host_sha" in
    *[!0-9a-f]*|'')
      snapshot_txn_refuse "the published snapshot could not be hashed on this host" "Install sha256sum (or shasum), then re-run."
      return 0
      ;;
  esac
  if [ "${#host_sha}" -ne 64 ]; then
    snapshot_txn_refuse "the published snapshot could not be hashed on this host" "Install sha256sum (or shasum), then re-run."
    return 0
  fi
  if [ "$mf_sha" != none ] && [ "$host_sha" != "$mf_sha" ]; then
    snapshot_txn_reject_unsafe "the published snapshot's digest does not match what the panel captured" "Check adb connectivity to $TARGET, then re-run."
    return 0
  fi
  if [ "$mf_sha" = none ]; then
    echo "   ${YEL}note${X} the panel has no usable digest tool; transfer size and the mandatory host digest were verified, and database_sha256_panel=none records the panel-side limitation"
  fi
  # The receipt is written ONCE, after every verification above, so no receipt claiming this file
  # can exist before the claim is proven. Built exclusively, published atomically, non-regular
  # destinations refused.
  receipt="$base.backup-receipt.txt"
  if [ -e "$receipt" ] || [ -L "$receipt" ]; then
    snapshot_txn_refuse "the receipt destination $receipt already exists" "Move it aside by hand, then re-run."
    return 0
  fi
  # The working file is created in the destination's own directory — never a TMPDIR fallback, which
  # can sit on another filesystem where the no-replace ln publication below cannot work at all.
  snapshot_txn_defer_host_signals
  receipt_tmp="$(mktemp "$(dirname "$receipt")/.receipt.XXXXXX" 2>/dev/null)" || receipt_tmp=""
  if [ -n "$receipt_tmp" ]; then
    SNAPSHOT_TXN_HOST_RECEIPT_WORK="$receipt_tmp"
    SNAPSHOT_TXN_HOST_RECEIPT_TARGET="$receipt"
  fi
  snapshot_txn_restore_host_signals
  if [ -z "$receipt_tmp" ]; then
    snapshot_txn_refuse "could not create the receipt working file beside $receipt" "Check that the backup directory is writable."
    return 0
  fi
  if ! {
    printf 'receipt_version=2\n'
    printf 'database=%s\n' "$base.db"
    printf 'database_bytes=%s\n' "$pulled_bytes"
    printf 'database_sha256_panel=%s\n' "$mf_sha"
    printf 'database_sha256_host=%s\n' "$host_sha"
    printf 'database_integrity=%s\n' "$mf_integrity"
    printf 'database_app_state_rows=%s\n' "$mf_rows"
    printf 'database_user_version=%s\n' "$mf_uv"
    printf 'app_version_code=%s\n' "$mf_vcode"
    printf 'app_version_name=%s\n' "$mf_vname"
    printf 'capture_binary=%s\n' "$mf_sqlite"
    printf 'capture_script_sha256=%s\n' "$script_sha"
    printf 'settings_export=%s\n' "${AUTO_EXPORT_FILE:-${EXPORT_FILE:-none}}"
  } > "$receipt_tmp" || ! chmod 600 "$receipt_tmp"; then
    snapshot_txn_refuse "the receipt working file could not be written" "Check that $CONFIG_BACKUP_DIR is writable."
    return 0
  fi
  if ! ln "$receipt_tmp" "$receipt" 2>/dev/null; then
    snapshot_txn_refuse "the receipt could not be published" "Check that $CONFIG_BACKUP_DIR is writable."
    return 0
  fi
  SNAPSHOT_TXN_HOST_RECEIPT="$receipt"
  rm -f "$receipt_tmp" 2>/dev/null || true
  SNAPSHOT_TXN_HOST_RECEIPT_WORK=""; SNAPSHOT_TXN_HOST_RECEIPT_TARGET=""
  # Custody of the host artifacts ends HERE: both files are published and verified as a coherent
  # pair, so a signal from this point on must find nothing to repossess. Deregistering BEFORE the
  # best-effort remote cleanup below keeps an operator interrupt during a wedged transport from
  # deleting the proven restore point this run just published.
  snapshot_txn_defer_host_signals
  SNAPSHOT_TXN_HOST_DB=""; SNAPSHOT_TXN_HOST_RECEIPT=""
  snapshot_txn_restore_host_signals
  # Cleanup is best-effort after a proven capture: the host file remains the restore point even if
  # the uniquely named, owner-only panel staging cannot be removed over a failing transport.
  run_root "rm -f ${stage}-script && rm -rf $stage" >/dev/null 2>&1 || true
  SNAPSHOT_TXN_REMOTE=""
  echo "   ${GRN}✓${X} data-store snapshot: ${B}$base.db${X} ${D}(verified SQLite backup, integrity ok, $mf_rows app_state rows)${X}"
  echo "   ${D}        Receipt: $receipt${X}"
  echo "   ${D}        Break-glass copy — same panel, same version, restored by hand only. The panel's own${X}"
  echo "   ${D}        Install → Backup .hpb is the supported restore path.${X}"
  echo "HAPANELD_SNAPSHOT_RESULT=captured"
  return 0
}

# Erase this panel's ha-paneld configuration so the next launch is a genuine first run.
#
# `pm clear`, rather than posting schema defaults back through the API, for a reason that is easy to
# get wrong: setup completion is itself stored state. A key-by-key reset leaves it set, so the panel
# reopens in REPAIR mode — "nothing has been reset" — which is the opposite of what someone asking
# for a clean install wants. Clearing app data is also the only route that reaches every app_state
# namespace plus the legacy preference journal, which would otherwise be re-imported on the next
# start and resurrect the values just erased.
#
# The cost is real, so it is stated before the prompt rather than discovered afterwards. --force does
# not stand in for the confirmation: it exists to skip a version comparison, not to authorise a wipe.
reset_panel_config() {
  local answer out
  [ "$RESET_CONFIG" = 1 ] || return 0
  # Positive `package:` evidence decides presence and any uncertainty fails closed; a panel that
  # answers "not installed" simply has nothing to erase, which is not an uncertainty.
  classify_package_presence "$ADB_COMMAND_TIMEOUT_SECONDS"
  case "$PACKAGE_PRESENCE" in
    absent)
      echo "   ${D}--reset-config: ha-paneld is not installed here, so there is no configuration to erase.${X}"
      RESET_CONFIG=0
      return 0
      ;;
    unknown)
      fail "could not re-check the installed app before reset" \
        "Nothing was erased and no app or setting was changed." \
        "Restore adb/package-manager responsiveness, then retry the reset."
      ;;
  esac
  echo "${RED}${B}⚠ --reset-config will erase this panel's ha-paneld configuration.${X}"
  echo "   ${D}Erased: MQTT and Home Assistant settings, learned entity, proximity and ambient data, and the on-panel revision history.${X}"
  echo "   ${D}Kept:   the app itself, the root helper, and every other app on the panel.${X}"
  echo "   ${D}Backup: none — reset is irreversible and does not create a settings or database backup.${X}"
  echo "   ${D}        Cancel now and run --export FILE separately first if you want a settings export.${X}"
  if [ -t 0 ]; then
    printf "   Type ${B}RESET${X} to continue: "
    read -r answer
  else
    answer="${HAPANELD_RESET_CONFIRM:-}"
  fi
  [ "$answer" = "RESET" ] || fail "--reset-config was not confirmed" \
    "Nothing was erased and no app or setting was changed." \
    "Re-run and type RESET at the prompt, or set HAPANELD_RESET_CONFIRM=RESET for an unattended reset."
  # Confirmation may be held open indefinitely. Re-establish exact package presence immediately
  # before erasure; no backup identity or version-custody machinery belongs in a deliberate reset.
  # Both outcomes short of `present` stop the erasure, but they are reported apart: the user needs to
  # know whether the target vanished or whether the panel simply stopped answering.
  classify_package_presence "$RESET_RECHECK_PACKAGE_QUERY_SECONDS"
  case "$PACKAGE_PRESENCE" in
    present) ;;
    absent)
      fail "ha-paneld is no longer installed at the confirmed reset target" \
        "Nothing was erased and no app or setting was changed." \
        "Re-run reset and confirm the current package target."
      ;;
    *)
      fail "could not re-check the installed app before reset" \
        "Nothing was erased and no app or setting was changed." \
        "Restore adb/package-manager responsiveness, then retry the reset."
      ;;
  esac
  step "🧨 resetting" "${D}erasing this panel's configuration${X}"
  if out="$(adb -s "$TARGET" shell pm clear "$PKG" 2>&1 | tr -d '\r')"; then
    package_status=0
  else
    package_status=$?
  fi
  if [ "$package_status" -eq 0 ] && [ "$out" = "Success" ]; then
    RESET_CONFIG_COMPLETED=1
    echo "   ${GRN}✓${X} configuration erased — this panel will start guided setup fresh"
  else
    fail "could not erase the panel configuration" \
      "The package manager reported: ${out:-no output}" \
      "No successful reset was reported. Fix the cause above, then re-run."
  fi
}

export_is_only_operation() {
  [ -n "$EXPORT_FILE" ] && [ -z "$APK$PANEL_ID$MQTT$MQTT_USER$MQTT_PASS$LOG_HOST$LOG_PORT$LOG_PROTO$LOG_ENABLE" ] &&
    [ -z "$HA_URL$HA_TOKEN$HA_USER$HA_PASS$RESTORE_FILE" ] && [ "$LATEST" = 0 ] && [ "$PERSIST_ADB" = 0 ] &&
    [ "$STRIP_VENDOR" = 0 ] && [ "$BUILTIN" = 0 ] && [ "$SHIZUKU" = 0 ] && [ "$RESET_CONFIG" = 0 ]
}

echo "${MAG}${B}🛠  ha-paneld provisioning${X} ${D}→ $TARGET${X}"

# Preflight the panel BEFORE fetching an APK — an unreachable/unauthorized panel should fail in
# seconds with recovery steps, not after a release download.
adb_preflight

# A config export must happen before any install or configuration mutation. With no other requested
# operation, --export is deliberately read-only and exits here.
if [ -n "$EXPORT_FILE" ]; then export_config "$EXPORT_FILE"; fi
if [ "$VERIFY_ONLY" = 1 ]; then
  show_provisioning_plan 0 "${D}reading installed guidance${X}"
  verify
  exit $?
fi

# Handing the home screen back — and removing ha-paneld — are standalone operations on a panel that is
# already provisioned. Both run here, before any release resolution or install, because neither needs an
# APK and a user reaching for them wants out, not an upgrade.
if [ "$HAND_BACK_HOME" = 1 ] || [ "$UNINSTALL" = 1 ]; then
  wait_for_handback_health
  if [ "$UNINSTALL" = 1 ]; then uninstall_ha_paneld; else hand_back_home; fi
  exit 0
fi
if export_is_only_operation; then
  echo "${GRN}${B}✅ config export complete${X} — no app, setting, or panel state was changed."
  exit 0
fi

# Report how the panel's time zone compares with this computer's, before release resolution or any
# panel mutation. Purely diagnostic — it changes no clock and refuses nothing — and placed here so
# the read-only --export and --verify paths above stay silent on a question they do not act on.
report_timezone_alignment

# Classify storage health before release resolution or any panel mutation, and report it. Every
# result is advisory here: an in-place replacement is a recovery attempt, so unreachable, malformed,
# unrecognised, critical and database-failure states are all reasons to replace the build rather than
# grounds to refuse. Older builds without the contract remain eligible for upgrade.
preflight_storage_health

resolve_apk

# Bind the private snapshot before any authenticator opens it. Every signature, manifest, helper and
# package-manager read below is bracketed by this exact-byte assertion, so the authenticated object
# and the installed object cannot diverge through a path replacement.
bind_candidate_apk_bytes

verify_release_apk

version_guard

# Decide the panel's root capability ONCE, before anything consumes it: the capture gate below and
# the helper/APK mutation later act on this same verdict, so a "no" that lets the capture skip is
# the same "no" the mutation proceeds under, and an undecided route stops both. On userdebug
# panels this performs the run's only `adb root` escalation.
resolve_root_route

# The authenticated candidate and actual panel state must agree before any backup/quiescence,
# reset, helper, Shizuku, package, grant or configuration mutation can begin.
host_database_compatibility_gate

# DB_COMPAT_MUTATION_ANCHOR: HOST_FIRST_MUTATION — everything above is artifact authentication or read-only panel inspection.
# Emergency upgrade safety window: try to persist a unique, owner-only config export before an
# ordinary helper/APK mutation. Best effort — a failure warns, withdraws the partial artifact and
# lets the replacement continue. Reset is intentionally irreversible and creates no automatic backup.
[ "$RESET_CONFIG" = 1 ] || auto_export_before_upgrade

# Then the canonical store itself, where a root route allows it. Ordinary in-place upgrades warn and
# continue if no valid snapshot can be produced. Reset deliberately bypasses database capture.
[ "$RESET_CONFIG" = 1 ] || snapshot_panel_database

# Backups and quiescence deliberately sit between two observations. Re-run the complete authority
# now, at the consume boundary, so package, candidate, primary-schema or recovery-inventory drift
# cannot license reset/helper/Shizuku/APK/configuration mutation. The final digest assertion binds
# this re-observation to the exact candidate bytes Android will receive.
# DB_COMPAT_MUTATION_ANCHOR: HOST_CONSUME_REVALIDATION
host_database_compatibility_gate consume

# THE mutation gate for an undecided route. The capture above may refuse on an unknown verdict and
# an ordinary backup failure may be advisory, but neither condition authorises mutating a panel whose
# capability is undecided. Every panel-changing step of the run — pm clear, helper transactions, the
# APK replace, permission grants, accessibility, persist-adb, vendor taming — sits after this line,
# so this single stop governs them all.
case "$ROOT_ROUTE_VERDICT" in
  rooted|unrooted) ;;
  *)
    fail "the panel's root route could not be determined" \
      "Its root probe or adb-root escalation did not produce an answer, so the panel's capability is unknown." \
      "No setting, configuration, helper or APK was changed. Restore adb responsiveness on $TARGET, dismiss any on-panel privilege prompt, then re-run." ;;
esac

reset_panel_config

# Current app releases and the root helper are one compatibility unit. On every panel where a root
# path is available, install or upgrade the matching helper before replacing the APK. A helper
# failure therefore leaves the previous app/helper pair running rather than stranding new features.
install_root_helper
if [ "$SHIZUKU" = 1 ] && [ -n "$ROOT_HELPER_TRANSACTION_KIND" ]; then
  start_root_helper_lease_guard
fi

# Try with -g (grant-all) first. Retry without it only for the exact pre-transaction diagnostic that
# an older package manager does not recognize the flag; every other failure may follow a committed
# package-manager request and must be reconciled before any replay.
install_apk() {
  local out helper_recovery="" outcome install_status
  if [ -n "$ROOT_HELPER_TRANSACTION_KIND" ] && ! renew_root_helper_lease "$ROOT_HELPER_TRANSACTION_KIND"; then
    fail "the root-helper transaction is no longer owned by this provisioning run" \
      "The APK was not replaced and no other transaction was committed or rolled back." \
      "Wait for the owning provisioner to finish or its ten-minute lease to expire, then re-run."
  fi
  start_root_helper_lease_guard
  if run_adb_install_attempt -r -g; then install_status=0; else install_status=$?; fi
  out="$ADB_INSTALL_OUTPUT"
  if [ "$install_status" -ne 0 ] && printf '%s\n' "$out" | grep -Eqi '(^|[[:space:]])(Error:[[:space:]]*)?Unknown option:? -g([[:space:]]|$)'; then
    # The first attempt was rejected before a package-manager transaction. Restore package-phase
    # rollback ownership for the fallback's own final candidate-byte assertion/start boundary.
    DB_GATE_PHASE=package
    if run_adb_install_attempt -r; then install_status=0; else install_status=$?; fi
    out="$ADB_INSTALL_OUTPUT"
  fi
  stop_root_helper_lease_guard
  if ! lease_guard_succeeded; then
    cleanup_root_helper_lease_guard
    fail "the root-helper transaction lease was lost while Android installed the APK" \
      "The package-manager outcome will be reconciled from exact APK bytes on the next run; no foreign journal was changed."
  fi
  cleanup_root_helper_lease_guard
  [ "$install_status" -ne 0 ] || return 0
  if [ -n "$ROOT_HELPER_TRANSACTION_KIND" ]; then
    if installed_apk_matches_hash "$TARGET_APK_SHA256"; then
      warn "adb install returned an error, but the exact target APK bytes are installed; completing the helper transaction"
      return 0
    else
      outcome=$?
    fi
    if [ "$outcome" -eq 2 ]; then
      printf '%s\n' "$out" | sanitize_terminal | sed 's/^/   /' >&2
      fail "adb install outcome is ambiguous; helper recovery was retained without rollback" \
        "The transport failed before the installed APK bytes could be verified." \
        "Restore adb connectivity and re-run the same command; it will reconcile the APK/helper journal safely."
    fi
    if rollback_root_helper "$ROOT_HELPER_TRANSACTION_KIND"; then
      helper_recovery="The prior root helper was restored and verified; the previous APK remains installed."
    else
      helper_recovery="The previous APK remains installed, but root-helper rollback could not be verified; restore the prior helper manually."
    fi
    ROOT_HELPER_TRANSACTION_KIND=""
  fi
  printf '%s\n' "$out" | sanitize_terminal | sed 's/^/   /' >&2
  case "$out" in
    *ADB_INSTALL_DEADLINE_EXCEEDED*)
      fail "install did not finish within the ${APK_INSTALL_TIMEOUT_SECONDS}s safety deadline" \
        "$helper_recovery" \
        "Android's package manager outcome may be delayed. Restore adb connectivity if needed, then re-run the same command; provisioning will reconcile the installed APK before changing the helper journal." ;;
    *INSTALL_FAILED_UPDATE_INCOMPATIBLE*|*"signatures do not match"*)
      signer_change_recovery_steps
      fail "install failed: signature mismatch — the ha-paneld already on the panel was signed with a different key (e.g. a local debug build vs a GitHub release)" \
        "$helper_recovery" \
        "${SIGNER_CHANGE_RECOVERY_STEPS[@]}" ;;
    *INSTALL_FAILED_VERSION_DOWNGRADE*)
      fail "install failed: the panel already runs a NEWER version than this APK" \
        "$helper_recovery" \
        "Install the newest release instead (--latest). Before uninstalling to force an older build, make and verify a complete .hpb backup from :8888 → Install → Backup; uninstalling loses on-panel state." ;;
    *INSTALL_FAILED_INSUFFICIENT_STORAGE*)
      fail "install failed: the panel is out of storage" \
        "$helper_recovery" \
        "Free space on the panel (Settings → Storage; or clear app caches: adb -s $TARGET shell pm trim-caches 999G), then re-run." ;;
    *)
      fail "adb install failed (output above)" \
        "$helper_recovery" \
        "Fix the cause shown above, then re-run the SAME command — provisioning is idempotent." ;;
  esac
}

# Optional non-root privilege bootstrap. Keep this dependency curated: never chase "latest" here.
# The exact official APK blob is SHA-pinned, and ha-paneld independently checks the installed signing
# certificate before binding. Shizuku still presents its own permission UI on the panel; provisioning
# cannot silently approve ha-paneld. The adb-started service normally needs rearming after reboot on
# older Android versions, while Shizuku 13.6 can auto-start on supported Android 13+ trusted WLANs.
if [ "$SHIZUKU" = 1 ]; then
  SHIZUKU_PKG="moe.shizuku.privileged.api"
  SHIZUKU_VERSION_CODE=1086
  SHIZUKU_URL="https://github.com/RikkaApps/Shizuku/releases/download/v13.6.0/shizuku-v13.6.0.r1086.2650830c-release.apk"
  SHIZUKU_SHA256="6e273ab0e991c4e79bc8b1bbb9b9dd739ccac1a8712a541a214078886b7b790f"
  SHIZUKU_CERT_SHA256="268b5590e868fb08bae7e0ac413564cd1ff88f5ccff74af9dbd0dc918e30db30"
  SHIZUKU_DIR="$(mktemp -d)"
  SHIZUKU_APK="$SHIZUKU_DIR/shizuku.apk"
  SHIZUKU_INSPECT_TIMEOUT_SECONDS="${SHIZUKU_INSPECT_TIMEOUT_SECONDS:-20}"
  case "$SHIZUKU_INSPECT_TIMEOUT_SECONDS" in
    ''|*[!0-9]*|0) SHIZUKU_INSPECT_TIMEOUT_SECONDS=20 ;;
  esac
  step "🪄 Shizuku" "${D}inspecting the installed manager and signer${X}"
  if SHIZUKU_INSPECT_OUTPUT="$(run_with_deadline "$SHIZUKU_INSPECT_TIMEOUT_SECONDS" \
      adb_exec -s "$TARGET" shell dumpsys package "$SHIZUKU_PKG" 2>/dev/null)"; then
    SHIZUKU_CURRENT_CODE="$(printf '%s\n' "$SHIZUKU_INSPECT_OUTPUT" \
      | sed -nE 's/.*versionCode=([0-9]+).*/\1/p' | head -1 | tr -d '\r' || true)"
  else
    SHIZUKU_INSPECT_STATUS=$?
    if [ "$SHIZUKU_INSPECT_STATUS" -eq 124 ] || [ "$SHIZUKU_INSPECT_STATUS" -eq 137 ]; then
      fail "Shizuku manager inspection timed out after ${SHIZUKU_INSPECT_TIMEOUT_SECONDS}s" \
        "The ha-paneld APK was not replaced. Restore adb/package-manager responsiveness, then re-run."
    fi
    SHIZUKU_CURRENT_CODE=""
  fi
  if SHIZUKU_INSPECT_OUTPUT="$(run_with_deadline "$SHIZUKU_INSPECT_TIMEOUT_SECONDS" \
      adb_exec -s "$TARGET" shell pm path "$SHIZUKU_PKG" 2>/dev/null)"; then
    SHIZUKU_CURRENT_PATH="$(printf '%s\n' "$SHIZUKU_INSPECT_OUTPUT" \
      | sed -n 's/^package://p' | head -1 | tr -d '\r' || true)"
  else
    SHIZUKU_INSPECT_STATUS=$?
    if [ "$SHIZUKU_INSPECT_STATUS" -eq 124 ] || [ "$SHIZUKU_INSPECT_STATUS" -eq 137 ]; then
      fail "Shizuku package-path inspection timed out after ${SHIZUKU_INSPECT_TIMEOUT_SECONDS}s" \
        "The ha-paneld APK was not replaced. Restore adb/package-manager responsiveness, then re-run."
    fi
    SHIZUKU_CURRENT_PATH=""
  fi
  SHIZUKU_CURRENT_TRUSTED=0
  if [ -n "$SHIZUKU_CURRENT_PATH" ]; then
    SHIZUKU_CURRENT_APK="$SHIZUKU_DIR/installed-shizuku.apk"
    if run_with_deadline "$SHIZUKU_INSPECT_TIMEOUT_SECONDS" \
        adb_exec -s "$TARGET" pull "$SHIZUKU_CURRENT_PATH" "$SHIZUKU_CURRENT_APK" >/dev/null 2>&1; then
      if command -v sha256sum >/dev/null 2>&1; then
        SHIZUKU_CURRENT_SHA="$(sha256sum "$SHIZUKU_CURRENT_APK" | awk '{print $1}')"
      elif command -v shasum >/dev/null 2>&1; then
        SHIZUKU_CURRENT_SHA="$(shasum -a 256 "$SHIZUKU_CURRENT_APK" | awk '{print $1}')"
      else
        SHIZUKU_CURRENT_SHA=""
      fi
      [ "$SHIZUKU_CURRENT_SHA" = "$SHIZUKU_SHA256" ] && SHIZUKU_CURRENT_TRUSTED=1
      if [ "$SHIZUKU_CURRENT_TRUSTED" = 0 ]; then
        APKSIGNER="$(find_android_build_tool apksigner || true)"
        if [ -n "$APKSIGNER" ]; then
          SHIZUKU_CURRENT_CERT="$("$APKSIGNER" verify --print-certs "$SHIZUKU_CURRENT_APK" 2>/dev/null \
            | sed -nE 's/^Signer #[0-9]+ certificate SHA-256 digest: *//p' | head -1 \
            | tr -d ':\r' | tr '[:upper:]' '[:lower:]' || true)"
          [ "$SHIZUKU_CURRENT_CERT" = "$SHIZUKU_CERT_SHA256" ] && SHIZUKU_CURRENT_TRUSTED=1
        fi
      fi
    else
      SHIZUKU_INSPECT_STATUS=$?
      if [ "$SHIZUKU_INSPECT_STATUS" -eq 124 ] || [ "$SHIZUKU_INSPECT_STATUS" -eq 137 ]; then
        fail "installed Shizuku signer inspection timed out after ${SHIZUKU_INSPECT_TIMEOUT_SECONDS}s" \
          "The ha-paneld APK was not replaced. Restore adb/package-manager responsiveness, then re-run."
      fi
    fi
  fi
  case "$SHIZUKU_CURRENT_CODE" in ''|*[!0-9]*) SHIZUKU_CURRENT_CODE=0 ;; esac
  if [ "$SHIZUKU_CURRENT_CODE" -ge "$SHIZUKU_VERSION_CODE" ]; then
    [ "$SHIZUKU_CURRENT_TRUSTED" = 1 ] || fail "the installed Shizuku manager cannot be trusted" \
      "Its version is the same as or newer than ha-paneld's curated build, but its signer could not be verified." \
      "Install Android SDK Build-Tools (apksigner), or remove the manager and re-run this command."
    step "🪄 Shizuku" "${D}keeping trusted installed manager (versionCode $SHIZUKU_CURRENT_CODE)${X}"
  else
    step "🪄 Shizuku" "${D}installing curated v13.6.0 manager${X}"
    curl -fsSL --proto '=https' --proto-redir '=https' --connect-timeout 15 --max-time 300 \
      "$SHIZUKU_URL" -o "$SHIZUKU_APK" || fail "Shizuku download failed" \
      "Check internet access to github.com, then re-run. ha-paneld was not replaced or stopped."
    if command -v sha256sum >/dev/null 2>&1; then
      SHIZUKU_GOT="$(sha256sum "$SHIZUKU_APK" | awk '{print $1}')"
    elif command -v shasum >/dev/null 2>&1; then
      SHIZUKU_GOT="$(shasum -a 256 "$SHIZUKU_APK" | awk '{print $1}')"
    else
      fail "cannot verify the Shizuku download" "Install sha256sum (or shasum) and re-run."
    fi
    [ "$SHIZUKU_GOT" = "$SHIZUKU_SHA256" ] || fail "Shizuku download checksum mismatch" \
      "Expected $SHIZUKU_SHA256" "Got      $SHIZUKU_GOT" "Nothing was installed."
    SHIZUKU_INSTALL_TIMEOUT_SECONDS="${SHIZUKU_INSTALL_TIMEOUT_SECONDS:-180}"
    case "$SHIZUKU_INSTALL_TIMEOUT_SECONDS" in
      ''|*[!0-9]*|0) SHIZUKU_INSTALL_TIMEOUT_SECONDS=180 ;;
    esac
    if run_with_deadline "$SHIZUKU_INSTALL_TIMEOUT_SECONDS" \
        adb_exec -s "$TARGET" install -r "$SHIZUKU_APK" >/dev/null; then
      :
    else
      SHIZUKU_INSTALL_STATUS=$?
      if [ "$SHIZUKU_INSTALL_STATUS" -eq 124 ] || [ "$SHIZUKU_INSTALL_STATUS" -eq 137 ]; then
        fail "Shizuku installation timed out after ${SHIZUKU_INSTALL_TIMEOUT_SECONDS}s" \
          "The ha-paneld APK was not replaced. Restore adb/package-manager responsiveness, then re-run."
      fi
      fail "Shizuku installation failed" "Fix the package-manager error above, then re-run this command."
    fi
  fi
  # Launch the authenticated manager, then execute its installed native starter directly. Older
  # instructions ran a mutable start.sh from shared external storage, allowing another process with
  # storage access to replace commands between manager verification and service start.
  adb -s "$TARGET" shell monkey -p "$SHIZUKU_PKG" 1 >/dev/null 2>&1 || true
  SHIZUKU_NATIVE_DIR="$(adb -s "$TARGET" shell dumpsys package "$SHIZUKU_PKG" 2>/dev/null \
    | sed -n 's/^[[:space:]]*nativeLibraryDir=//p' | head -1 | tr -d '\r' || true)"
  case "$SHIZUKU_NATIVE_DIR" in
    /data/app/*|/mnt/expand/*/app/*) ;;
    *) SHIZUKU_NATIVE_DIR="" ;;
  esac
  case "$SHIZUKU_NATIVE_DIR" in *[!0-9A-Za-z._/+,:=@~-]*) SHIZUKU_NATIVE_DIR="" ;; esac
  SHIZUKU_STARTER="${SHIZUKU_NATIVE_DIR:+$SHIZUKU_NATIVE_DIR/libshizuku.so}"
  SHIZUKU_START_TIMEOUT_SECONDS="${SHIZUKU_START_TIMEOUT_SECONDS:-30}"
  case "$SHIZUKU_START_TIMEOUT_SECONDS" in
    ''|*[!0-9]*|0) SHIZUKU_START_TIMEOUT_SECONDS=30 ;;
  esac
  if [ -n "$SHIZUKU_STARTER" ] && adb -s "$TARGET" shell test -x "$SHIZUKU_STARTER" >/dev/null 2>&1 && \
      run_with_deadline "$SHIZUKU_START_TIMEOUT_SECONDS" \
        adb_exec -s "$TARGET" shell "$SHIZUKU_STARTER" >/dev/null 2>&1; then
    echo "   ${GRN}✓${X} Shizuku service started"
  else
    SHIZUKU_START_STATUS=$?
    if [ "$SHIZUKU_START_STATUS" -eq 124 ] || [ "$SHIZUKU_START_STATUS" -eq 137 ]; then
      warn "Shizuku installed, but its service start timed out after ${SHIZUKU_START_TIMEOUT_SECONDS}s. Open Shizuku on the panel and follow its ADB start instructions."
    else
      warn "Shizuku installed, but its service did not start. Open Shizuku on the panel and follow its ADB start instructions."
    fi
    PROVISION_FAILED=1
  fi
  # Enables Shizuku's supported Android 13+ trusted-WLAN auto-start option; the user still chooses
  # whether to turn that option on inside Shizuku. Harmless/ignored on versions that refuse the grant.
  adb -s "$TARGET" shell pm grant "$SHIZUKU_PKG" android.permission.WRITE_SECURE_SETTINGS >/dev/null 2>&1 || true
  echo "   ${YEL}→${X} On the panel: open ha-paneld Configure, use the toolbar overflow menu → Enhanced access → Enable, then approve the Shizuku prompt."
fi

# Bootstrap Shizuku before replacing ha-paneld. A fatal manager download/signature/install failure
# therefore cannot leave a previously working agent in Android's post-install stopped state. A service
# start failure is non-fatal to the core install: finish and launch ha-paneld, but return nonzero so an
# individual or fleet run cannot report enhanced-access setup as successful.
if [ -n "$ROOT_HELPER_TRANSACTION_KIND" ]; then
  stop_root_helper_lease_guard
  if ! lease_guard_succeeded; then
    cleanup_root_helper_lease_guard
    fail "the root-helper transaction lease was lost before APK installation" \
      "The APK was not replaced. Re-run to reconcile the retained helper recovery journal safely."
  fi
  cleanup_root_helper_lease_guard
fi
# Helper/Shizuku preparation can be slow and may let an external writer change the database after the
# consume-time check. Re-observe one final time immediately beside ha-paneld package replacement. A
# refusal rolls back this run's uncommitted helper transaction before exiting.
# DB_COMPAT_MUTATION_ANCHOR: HOST_PACKAGE_REVALIDATION
host_database_compatibility_gate package
step "📦 installing" "${D}$APK${X}"
install_apk
# Successful package replacement terminates the old quiesced process. A later failure belongs to
# the newly installed process and must not send it the old process's release nonce. A bridge
# quiesced for a successor installation is a different package, which this install did not replace
# and did not stop, so its lease is kept and released rather than silently abandoned.
if [ "${UPGRADE_QUIESCE_PKG:-$PKG}" = "$PKG" ]; then UPGRADE_QUIESCE_NONCE=""; UPGRADE_QUIESCE_PKG=""; fi
if [ -n "$ROOT_HELPER_TRANSACTION_KIND" ]; then
  if ! commit_root_helper_upgrade "$ROOT_HELPER_TRANSACTION_KIND" && \
     ! commit_root_helper_upgrade "$ROOT_HELPER_TRANSACTION_KIND"; then
    fail "the APK and root helper were installed, but the helper recovery journal could not be committed" \
      "Do not reboot yet. Check panel storage and permissions, then re-run this command to complete recovery cleanup."
  fi
  cleanup_root_helper_staging
  ROOT_HELPER_TRANSACTION_KIND=""
fi

step "🔑 permissions" "${D}notifications · WRITE_SETTINGS (brightness/screen) · SYSTEM_ALERT_WINDOW (navbar) · a11y (buttons)${X}"
# Grants degrade gracefully: some vendor builds refuse appops/settings writes from the adb shell.
# A failed grant must not abort the run — the app works with reduced capability, verify() reports the
# true end-state, and each warning names the manual Settings path to finish the job by hand.
adb -s "$TARGET" shell pm grant "$PKG" android.permission.POST_NOTIFICATIONS >/dev/null 2>&1 || true
# Microphone. Granted here for the same reason as notifications: a wall panel has nobody standing at
# it to answer a runtime permission dialog, and a permission that is only ever asked for on screen is
# a permission a headless panel never gets. Nothing records until a feature asks for the microphone.
adb -s "$TARGET" shell pm grant "$PKG" android.permission.RECORD_AUDIO >/dev/null 2>&1 || true
adb -s "$TARGET" shell appops set "$PKG" WRITE_SETTINGS allow >/dev/null 2>&1 \
  || warn "could not grant WRITE_SETTINGS via adb — grant manually: Settings → Apps → ha-paneld → 'Modify system settings'"
# Soft-navbar overlay. SuperSU panels self-grant this at runtime via in-app su, but sandbox-walled
# panels (Tuya TPA10, SELinux-blocked from exec'ing su) can't — so grant it here for every panel.
adb -s "$TARGET" shell appops set "$PKG" SYSTEM_ALERT_WINDOW allow >/dev/null 2>&1 \
  || warn "could not grant SYSTEM_ALERT_WINDOW via adb — grant manually: Settings → Apps → ha-paneld → 'Display over other apps'"
# Enable the accessibility service, preserving whatever else is enabled. If the READ fails we must
# NOT write: a blind write would replace the whole list and silently disable any other a11y service.
if EXISTING="$(adb -s "$TARGET" shell settings get secure enabled_accessibility_services 2>/dev/null)"; then
  EXISTING="${EXISTING//$'\r'/}"
  if [ "$EXISTING" = "null" ] || [ -z "$EXISTING" ]; then
    adb -s "$TARGET" shell settings put secure enabled_accessibility_services "$A11Y" >/dev/null 2>&1 \
      || warn "could not enable the accessibility service — enable manually: Settings → Accessibility → ha-paneld"
  else
    # A panel provisioned by an older script stored the shorthand spelling of this component, which
    # denotes the same service. Compare components rather than text so a retry appends nothing twice.
    if ! a11y_service_enabled "$EXISTING" "$A11Y"; then
      adb -s "$TARGET" shell settings put secure enabled_accessibility_services "$EXISTING:$A11Y" >/dev/null 2>&1 \
        || warn "could not enable the accessibility service — enable manually: Settings → Accessibility → ha-paneld"
    fi
  fi
  adb -s "$TARGET" shell settings put secure accessibility_enabled 1 >/dev/null 2>&1 || true
else
  warn "could not read the accessibility settings — skipping a11y enable (hardware buttons). Enable manually: Settings → Accessibility → ha-paneld."
fi

# Opt-in: persist network adb across reboots. Sets the prop only (no adbd restart — that would drop
# this very connection mid-provision); it's already on tcp, so the prop just makes it survive a reboot.
if [ "$PERSIST_ADB" = 1 ]; then
  step "🔌 network adb" "${D}persisting tcp 5555 across reboot${X}"
  run_root 'setprop persist.adb.tcp.port 5555' >/dev/null 2>&1 || true
  # Trust the read-back, not the command: su forms vary per vendor and a failed setprop is silent.
  PERSISTED="$(adb -s "$TARGET" shell getprop persist.adb.tcp.port 2>/dev/null | tr -d '\r' || true)"
  if [ "$PERSISTED" = "5555" ]; then
    echo "   ${GRN}✓${X} persist.adb.tcp.port=5555"
  else
    warn "could not persist network adb (needs root — no working su/adbd-root path). After a reboot, re-enable it on the panel (Developer options) or via USB: adb tcpip 5555"
  fi
fi

# MUST launch after install: `adb install -r` leaves the app in Android's "stopped" state, which does
# NOT auto-start — not even via START_STICKY — until something launches it (or the device reboots). A
# fleet update that installs without this step leaves panels installed-but-dead (their entities go
# `unavailable` in HA). Android's normal LAUNCHER route is the reporter-proven stopped-state recovery
# path on PX30/API 27. A zero launcher exit is not proof that Android cleared stopped state, so health
# remains authoritative: after one bounded LAUNCHER attempt and bounded health wait, advance to the
# distinct direct-component compatibility route. Invoke the absolute adb executable under the existing
# deadline owner rather than nesting the general adb wrapper's process group.
# Wait up to $1 seconds for the agent to answer. Deliberately does NOT relaunch while waiting: a
# repeat launch during first-run migration restarts the very work being waited on.
wait_for_launch_health() {
  local budget="$1" announced=0 deadline remaining probe
  # Bound by a real DEADLINE, and never start work that would cross it: checking elapsed only at the
  # top of the loop let a final curl plus sleep overrun the budget by their combined cost.
  deadline=$(( SECONDS + budget ))
  while :; do
    remaining=$(( deadline - SECONDS ))
    [ "$remaining" -gt 0 ] || break
    probe=2
    [ "$remaining" -ge "$probe" ] || probe="$remaining"
    curl -fsS --max-time "$probe" "$URL/health" >/dev/null 2>&1 && return 0
    if [ "$announced" = 0 ] && [ $(( budget - (deadline - SECONDS) )) -ge "$APP_LAUNCH_PROBE_SECONDS" ]; then
      announced=1
      echo "   ${D}still starting — the first launch after an upgrade migrates the database; allowing up to ${budget}s${X}"
    fi
    [ $(( deadline - SECONDS )) -gt 0 ] || break
    sleep 1
  done
  return 1
}

# Issuing a start and waiting for health are separate concerns. Keeping them in one function meant a
# failed start COMMAND skipped the health wait entirely, even though an earlier route may already
# have the agent starting.
start_panel_agent() {
  case "$1" in
    launcher)
      run_with_deadline "$APP_LAUNCH_COMMAND_TIMEOUT_SECONDS" adb_exec -s "$TARGET" shell \
        monkey -p "$PKG" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1 || return 1
      ;;
    direct)
      run_with_deadline "$APP_LAUNCH_COMMAND_TIMEOUT_SECONDS" adb_exec -s "$TARGET" shell \
        am start -n "$(app_component "$PKG" .MainActivity)" >/dev/null 2>&1 || return 1
      ;;
    *) return 2 ;;
  esac
}

step "▶️  starting" "the panel agent"
start_panel_agent launcher || true
if wait_for_launch_health "$APP_LAUNCH_PROBE_SECONDS"; then
  AGENT_HEALTHY=1
else
  # Escalate ONCE, and only while the agent is still not answering: a second start restarts the
  # first-run database migration that the long wait exists to accommodate. A failed start command
  # does not skip the wait — the launcher route may already have the agent coming up.
  step "▶️  re-starting" "${D}launcher did not answer yet — trying the direct route${X}"
  start_panel_agent direct || true
  if wait_for_launch_health "$APP_HEALTH_TIMEOUT_SECONDS"; then AGENT_HEALTHY=1; else
    warn "web server still not answering on $URL after ${APP_HEALTH_TIMEOUT_SECONDS}s — provisioning is incomplete."
    echo "   ${D}Open $URL in a browser. If it loads there, this computer cannot reach the panel's :8888 port (firewall/VLAN).${X}"
    PROVISION_FAILED=1
  fi
fi
if [ "$PROVISION_FAILED" = 0 ]; then
  show_provisioning_plan 1 "${D}waiting for the installed app to resolve this panel${X}" \
    || PROVISION_FAILED=1
fi

# Built-in-renderer login (optional): when --ha-user/--ha-pass are given, log in to HA *here on this
# machine* (the password never reaches the panel) to mint a refresh token, so the panel holds a
# revocable refresh token rather than a 10-year access token. Sets HA_TOKEN/HA_REFRESH/HA_EXPIRY.
# JSON string escaping for the hand-built login bodies: a quote or backslash in a username/password
# would otherwise produce malformed JSON and a baffling login failure.
# Refuse to write to a panel whose agent never answered. Before this, a health timeout still fell
# through to the configuration POST and the restore import: the run would push settings, or import a
# whole config bundle, into an agent that had not come up — unverifiable at best, and landing in a
# half-started process at worst.
require_healthy_agent() {
  # Explicit `if`, never `[ … ] && return 0`: under `set -e` a false test fails the whole AND-list
  # and kills the function before the guard can report anything.
  if [ "$AGENT_HEALTHY" = 1 ]; then return 0; fi
  fail "refusing to $1 — the panel agent never answered on $URL" \
    "The APK and helper are installed, but the agent did not become healthy within ${APP_HEALTH_TIMEOUT_SECONDS}s, so nothing can confirm a write landed." \
    "No configuration was written and no bundle was imported." \
    "Bring the panel up (check $URL in a browser), then re-run the same command to apply the configuration."
}

json_escape() { printf '%s' "$1" | sed -e 's/\\/\\\\/g' -e 's/"/\\"/g'; }

if [ -n "$HA_USER" ] && [ -n "$HA_PASS" ]; then
  # Minting happens against Home Assistant, not the panel, so it is a side effect OUTSIDE this
  # device: a token minted for a panel that never came up cannot be delivered and is left behind in
  # HA as a dangling credential the user has to find and revoke. Gate it like the panel writes.
  require_healthy_agent "mint a Home Assistant token"
  step "🔑  HA login" "${D}minting a refresh token for $HA_USER (password stays on this machine)${X}"
    # Every curl / grep-extract below is `|| true`-guarded: under set -euo pipefail a failed request or
    # a no-match grep pipeline would abort the whole provisioning run BEFORE the warn lines could
    # explain what happened — the warn-and-continue guards were dead code without this.
    cid="${HA_URL%/}/"
    ju="$(json_escape "$HA_USER")"; jp="$(json_escape "$HA_PASS")"; jc="$(json_escape "$cid")"
    fl="$(curl -fsS --connect-timeout "$HA_AUTH_CONNECT_TIMEOUT_SECONDS" --max-time "$HA_AUTH_TIMEOUT_SECONDS" \
          -X POST "${HA_URL%/}/auth/login_flow" -H 'Content-Type: application/json' \
          --data "{\"client_id\":\"$jc\",\"handler\":[\"homeassistant\",null],\"redirect_uri\":\"$jc\"}" 2>/dev/null || true)"
    flow_id="$(printf '%s' "$fl" | grep -o '"flow_id":"[^"]*"' | head -1 | cut -d'"' -f4 || true)"
    if [ -z "$flow_id" ]; then warn "HA login flow failed (is $HA_URL reachable?)"; HA_LOGIN_FAILED=1; else
      HA_LOGIN_BODY_FILE="$SECRET_DIR/ha-login-body.json"
      printf '{"client_id":"%s","username":"%s","password":"%s"}' "$jc" "$ju" "$jp" > "$HA_LOGIN_BODY_FILE"
      chmod 600 "$HA_LOGIN_BODY_FILE"
      res="$(curl -fsS --connect-timeout "$HA_AUTH_CONNECT_TIMEOUT_SECONDS" --max-time "$HA_AUTH_TIMEOUT_SECONDS" \
             -X POST "${HA_URL%/}/auth/login_flow/$flow_id" -H 'Content-Type: application/json' \
             --data-binary "@$HA_LOGIN_BODY_FILE" 2>/dev/null || true)"
      rm -f "$HA_LOGIN_BODY_FILE" "$HA_PASS_SECRET_FILE"
      HA_LOGIN_BODY_FILE=""; HA_PASS_SECRET_FILE=""; HA_PASS=""; jp=""
      code="$(printf '%s' "$res" | grep -o '"result":"[^"]*"' | head -1 | cut -d'"' -f4 || true)"
      if [ -z "$code" ]; then warn "HA login rejected (bad credentials or MFA-enabled account)"; HA_LOGIN_FAILED=1; else
        HA_AUTH_CODE_FILE="$SECRET_DIR/ha-authorization-code"
        printf '%s' "$code" > "$HA_AUTH_CODE_FILE"; chmod 600 "$HA_AUTH_CODE_FILE"
        tok="$(curl -fsS --connect-timeout "$HA_AUTH_CONNECT_TIMEOUT_SECONDS" --max-time "$HA_AUTH_TIMEOUT_SECONDS" \
               -X POST "${HA_URL%/}/auth/token" \
               --data-urlencode "grant_type=authorization_code" \
               --data-urlencode "code@$HA_AUTH_CODE_FILE" --data-urlencode "client_id=$cid" 2>/dev/null || true)"
        rm -f "$HA_AUTH_CODE_FILE"; HA_AUTH_CODE_FILE=""; code=""
        HA_TOKEN="$(printf '%s' "$tok" | grep -o '"access_token":"[^"]*"' | cut -d'"' -f4 || true)"
        HA_REFRESH="$(printf '%s' "$tok" | grep -o '"refresh_token":"[^"]*"' | cut -d'"' -f4 || true)"
        exp="$(printf '%s' "$tok" | grep -o '"expires_in":[0-9]*' | grep -o '[0-9]*' || true)"
        [ -n "$exp" ] && HA_EXPIRY="$(( $(date +%s) + exp ))"
        if [ -n "$HA_TOKEN" ] && [ -n "$HA_REFRESH" ]; then
          HA_TOKEN_SECRET_FILE="$SECRET_DIR/ha-token"
          HA_REFRESH_SECRET_FILE="$SECRET_DIR/ha-refresh-token"
          printf '%s' "$HA_TOKEN" > "$HA_TOKEN_SECRET_FILE"
          printf '%s' "$HA_REFRESH" > "$HA_REFRESH_SECRET_FILE"
          chmod 600 "$HA_TOKEN_SECRET_FILE" "$HA_REFRESH_SECRET_FILE"
          echo "   ${GRN}✓${X} refresh token minted"
        else
          warn "Home Assistant returned no usable access/refresh token pair"
          HA_LOGIN_FAILED=1
        fi
      fi
    fi
  if [ "$HA_LOGIN_FAILED" != 0 ]; then
    HA_TOKEN=""; HA_REFRESH=""; HA_EXPIRY=""
    PROVISION_FAILED=1
    echo "   ${RED}The existing dashboard login and renderer selection were left unchanged.${X}"
  fi
fi

# A long-lived token supplied directly is otherwise just opaque text: accepting it and selecting the
# built-in renderer would produce a convincing false-success followed by an on-panel login failure.
# Validate every resulting access token (direct LLAT or freshly minted short-lived token) against HA
# before changing any saved renderer/login settings.
if [ "$HA_LOGIN_FAILED" = 0 ] && [ -n "$HA_TOKEN" ]; then
  step "🔐  checking HA access" "${D}validating the token before changing the panel${X}"
  HA_AUTH_HEADER_FILE="$SECRET_DIR/ha-authorization-header"
  printf 'Authorization: Bearer %s' "$HA_TOKEN" > "$HA_AUTH_HEADER_FILE"; chmod 600 "$HA_AUTH_HEADER_FILE"
  if curl -fsS --connect-timeout "$HA_AUTH_CONNECT_TIMEOUT_SECONDS" --max-time 10 -H "@$HA_AUTH_HEADER_FILE" \
      -H 'Accept: application/json' "${HA_URL%/}/api/" >/dev/null 2>&1; then
    echo "   ${GRN}✓${X} Home Assistant accepted the token"
  else
    warn "Home Assistant rejected the token (or ${HA_URL%/} could not be reached)"
    HA_LOGIN_FAILED=1
    HA_TOKEN=""; HA_REFRESH=""; HA_EXPIRY=""
    PROVISION_FAILED=1
    echo "   ${RED}The existing dashboard login and renderer selection were left unchanged.${X}"
    echo "   ${D}Create a new long-lived access token in Home Assistant → your profile, then re-run.${X}"
  fi
  rm -f "$HA_AUTH_HEADER_FILE"; HA_AUTH_HEADER_FILE=""
fi

ARGS=()
[ -n "$PANEL_ID" ]   && ARGS+=(--data-urlencode "panel_id=$PANEL_ID")
[ -n "$MQTT" ]       && ARGS+=(--data-urlencode "mqtt_broker=$MQTT")
[ -n "$MQTT_USER" ]  && ARGS+=(--data-urlencode "mqtt_user=$MQTT_USER")
[ -n "$MQTT_PASS" ]  && ARGS+=(--data-urlencode "mqtt_password@$MQTT_PASS_SECRET_FILE")
if [ "$HA_LOGIN_FAILED" = 0 ]; then
  [ -n "$HA_URL" ]     && ARGS+=(--data-urlencode "ha_url=$HA_URL")
  [ -n "$HA_TOKEN" ]   && ARGS+=(--data-urlencode "ha_token@$HA_TOKEN_SECRET_FILE")
  [ -n "$HA_REFRESH" ] && ARGS+=(--data-urlencode "ha_refresh_token@$HA_REFRESH_SECRET_FILE")
  [ -n "$HA_EXPIRY" ]  && ARGS+=(--data-urlencode "ha_token_expiry=$HA_EXPIRY")
  [ "$BUILTIN" = 1 ]   && ARGS+=(--data-urlencode "dashboard_package=builtin")
fi
[ -n "$LOG_ENABLE" ] && ARGS+=(--data-urlencode "log_ship_enabled=$LOG_ENABLE")
[ -n "$LOG_HOST" ]   && ARGS+=(--data-urlencode "log_ship_host=$LOG_HOST")
[ -n "$LOG_PORT" ]   && ARGS+=(--data-urlencode "log_ship_port=$LOG_PORT")
[ -n "$LOG_PROTO" ]  && ARGS+=(--data-urlencode "log_ship_protocol=$LOG_PROTO")
PLAN_REFRESH_NEEDED=0
if [ ${#ARGS[@]} -gt 0 ]; then
  require_healthy_agent "write configuration"
  PLAN_REFRESH_NEEDED=1
  step "⚙️  configuring" "${D}panel_id / MQTT / renderer / log shipping${X}"
  if CFG_ERR="$(curl -fsS --connect-timeout "$PANEL_POST_CONNECT_TIMEOUT_SECONDS" \
      --max-time "$PANEL_POST_TIMEOUT_SECONDS" -H 'Accept: application/json' -X POST \
      "${ARGS[@]}" "$URL/api/v1/config" 2>&1 >/dev/null)"; then
    echo "   ${GRN}✓${X} applied"
  else
    CFG_ERR="$(printf '%s' "${CFG_ERR:-no response}" | sanitize_terminal)"
    warn "config not applied: $CFG_ERR"
    echo "   ${D}If the agent was still starting, re-running applies it. A 403 means the panel refused the source: its API only accepts LAN addresses — provision from a host on the panel's own network, not via VPN/routed subnet.${X}"
    PROVISION_FAILED=1
  fi
fi
MQTT_PASS=""; HA_TOKEN=""; HA_REFRESH=""; HA_PASS=""

# Config bundle restore (best-effort import: valid keys apply, invalid/unknown are reported + skipped —
# a bundle from different hardware or another ha-paneld version restores what it can). --restore applies
# everything incl. device-scoped keys (same-panel recovery / like-for-like replacement); --restore-fleet
# applies only PORTABLE non-secret keys (cross-panel deployment).
if [ -n "$RESTORE_FILE" ]; then
  require_healthy_agent "import a configuration bundle"
  PLAN_REFRESH_NEEDED=1
  MODE_Q=""; [ "$RESTORE_MODE" = "fleet" ] && MODE_Q="?mode=fleet"
  step "📦 restoring config" "${D}$RESTORE_FILE (${RESTORE_MODE})${X}"
  if response="$(curl -fsS --connect-timeout "$PANEL_POST_CONNECT_TIMEOUT_SECONDS" \
      --max-time "$PANEL_RESTORE_TIMEOUT_SECONDS" -H 'Content-Type: application/json' --data-binary @"$RESTORE_FILE" \
      -w '\n%{http_code}' "$URL/api/v1/config/import$MODE_Q" 2>&1)"; then
    http_status="${response##*$'\n'}"
    resp="${response%$'\n'*}"
    resp="$(printf '%s' "$resp" | sanitize_terminal)"
    if [ "$http_status" = 202 ] && printf '%s' "$resp" | approval_required_response; then
      echo "   ${YEL}⚠ config import requires approval on the panel; no settings were imported.${X}"
      echo "   ${D}On the panel, open Configure → toolbar overflow → Security mode → Review approvals, approve the config import, then retry the identical command from this computer within ten minutes.${X}"
      PROVISION_FAILED=1
    elif [ "$http_status" != 200 ]; then
      echo "   ${RED}✗ import failed:${X} unexpected HTTP $http_status${resp:+: $resp}"
      PROVISION_FAILED=1
    else
      echo "   ${GRN}✓${X} $resp"
    fi
  else
    resp="$(printf '%s' "$response" | sanitize_terminal)"
    echo "   ${RED}✗ import failed:${X} $resp"
    PROVISION_FAILED=1
  fi
fi

# Built-in renderer seeds, applied LAST so specificity wins. An explicit option beats both whatever
# the panel already held and anything a --restore bundle just imported: the operator naming a value on
# the command line is the most specific statement of intent in the run, and the import above is a bulk
# apply. Placed after that import for exactly that reason — seeded earlier, the bundle would silently
# overwrite it.
#
# Why this exists at all: a scripted install deliberately never asks the wizard's questions. A panel
# provisioned headlessly is excused them (nobody is standing at it to answer), so without a seed it
# renders whatever Home Assistant calls the account default — which on a large account is precisely
# the dashboard a slow panel cannot draw. These options are the headless equivalent of the two
# questions the wizard asks a human BEFORE the first load.
# Which renderer the panel's stored selection actually RESOLVES to, asked of the panel instead of
# inferred here. A blank `dashboard_package` selects ha-paneld's built-in renderer, so the stored string
# answers a different question than the seeds need: reading it directly refused panels that were running
# the built-in renderer all along, telling the operator it "is not set to use" a renderer it was using.
# Echoes exactly one of:
#   builtin           the panel resolves to ha-paneld's built-in renderer
#   foreign <package> the panel resolves to a different dashboard app
#   unresolved        the panel's stored selection resolves to nothing usable
#   unavailable       no resolution could be obtained (an ha-paneld older than the field, or unreadable)
# `unavailable` falls back to the one stored value that needs no resolving — a LITERAL `builtin`, which
# means the same thing on every version. Blank deliberately does not fall back, because blank is exactly
# the value only the panel can interpret; without an answer it stays ambiguous and the caller refuses.
read_effective_renderer() {
  local body renderer pkg cfg
  # Both reads swallow their transport error deliberately: an empty body falls through to `unavailable`,
  # which the caller refuses on. Nothing here can turn a failed read into an admission.
  body="$(curl -fsS --max-time 5 "$URL/api/v1/setup" 2>/dev/null || true)"
  # Isolate the renderer object before matching anything inside it, so a `"builtin":true` belonging to
  # another object can never be read as this one's answer. It carries no nested braces, so the first
  # closing brace ends it.
  renderer="$(printf '%s' "$body" | tr '\n' ' ' | sed -n 's/.*"renderer"[[:space:]]*:[[:space:]]*{\([^}]*\)}.*/\1/p')"
  if [ -n "$renderer" ]; then
    if printf '%s' "$renderer" | grep -Eq '"builtin"[[:space:]]*:[[:space:]]*true'; then
      echo builtin
      return 0
    fi
    pkg="$(printf '%s' "$renderer" | sed -n 's/.*"package"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p')"
    if [ -n "$pkg" ]; then echo "foreign $pkg"; else echo unresolved; fi
    return 0
  fi
  cfg="$(curl -fsS --max-time 3 "$URL/api/v1/config" 2>/dev/null || true)"
  if printf '%s' "$cfg" | grep -Eq '"dashboard_package"[[:space:]]*:[[:space:]]*"builtin"'; then
    echo builtin
    return 0
  fi
  echo unavailable
}

seed_builtin_renderer_preferences() {
  if [ "$HOME_DASHBOARD_SET" = 0 ] && [ "$ENTITY_FILTER_SET" = 0 ]; then return 0; fi
  # A seed configures the renderer's connection to Home Assistant. With no working login there is
  # nothing for it to select a dashboard on, and the failure above already reported the cause.
  if [ "$HA_LOGIN_FAILED" != 0 ]; then
    warn "skipped --home-dashboard/--entity-filter: the Home Assistant login above did not succeed"
    return 0
  fi
  require_healthy_agent "seed the built-in renderer's dashboard preferences"

  # --builtin selects the built-in renderer in this same command, and an explicitly supplied value is the
  # most specific statement of intent in the run, so it needs no second opinion from the panel.
  if [ "$BUILTIN" != 1 ]; then
    local renderer_state renderer_pkg
    # Fail CLOSED: only a positive built-in resolution admits, so an unreadable answer, an unresolvable
    # selection or a panel too old to answer all become refusals, never an assumed yes. Each refuses with
    # its own reason — none of them may claim the panel is running something else when the truth is that
    # nobody could confirm what it is running.
    renderer_state="$(read_effective_renderer)"
    renderer_pkg="$(printf '%s' "$renderer_state" | cut -s -d' ' -f2- | sanitize_terminal)"
    case "$renderer_state" in
      builtin) : ;;
      foreign*)
        echo "   ${RED}✗ --home-dashboard/--entity-filter apply to ha-paneld's built-in renderer, and this panel resolves to ${renderer_pkg}.${X}"
        echo "   ${D}Add --builtin to select the built-in renderer in the same command, or run guided setup and choose it first.${X}"
        PROVISION_FAILED=1
        return 0
        ;;
      unresolved)
        echo "   ${RED}✗ --home-dashboard/--entity-filter apply to ha-paneld's built-in renderer, and this panel cannot resolve which renderer it is set to use.${X}"
        echo "   ${D}Its stored dashboard selection does not name a usable app. Add --builtin to select the built-in renderer in the same command, or run guided setup and choose a renderer.${X}"
        PROVISION_FAILED=1
        return 0
        ;;
      *)
        echo "   ${RED}✗ could not confirm this panel uses ha-paneld's built-in renderer, which --home-dashboard/--entity-filter apply to.${X}"
        echo "   ${D}This ha-paneld does not report which renderer it resolves to, or could not be read just now. Add --builtin to select the built-in renderer in the same command.${X}"
        PROVISION_FAILED=1
        return 0
        ;;
    esac
  fi

  local seed_args=() seed_err seeded_path=""
  if [ "$HOME_DASHBOARD_SET" = 1 ]; then
    # `auto` is the friendly spelling of the Home dashboard picker's Auto choice. It is sent as the
    # bare root rather than as an empty parameter because the app's validator canonicalises every
    # "follow the account default" spelling — blank, `/`, `//` — to the same stored blank sentinel,
    # so this needs no second opinion about what empty means.
    case "$HOME_DASHBOARD" in
      auto|AUTO|Auto) seeded_path="/" ;;
      *) seeded_path="$HOME_DASHBOARD" ;;
    esac
    seed_args+=(--data-urlencode "home_dashboard=$seeded_path")
  fi
  if [ "$ENTITY_FILTER_SET" = 1 ]; then
    case "$ENTITY_FILTER" in
      on) seed_args+=(--data-urlencode "dashboard_entity_learning=true") ;;
      *) seed_args+=(--data-urlencode "dashboard_entity_learning=false") ;;
    esac
  fi

  PLAN_REFRESH_NEEDED=1
  step "🏠 seeding dashboard" "${D}home dashboard / entity filtering, before the first render${X}"
  if seed_err="$(curl -fsS --connect-timeout "$PANEL_POST_CONNECT_TIMEOUT_SECONDS" \
      --max-time "$PANEL_POST_TIMEOUT_SECONDS" -H 'Accept: application/json' -X POST \
      "${seed_args[@]}" "$URL/api/v1/config" 2>&1 >/dev/null)"; then
    [ "$HOME_DASHBOARD_SET" = 0 ] || echo "   ${GRN}✓${X} home dashboard: ${seeded_path}"
    [ "$ENTITY_FILTER_SET" = 0 ] || echo "   ${GRN}✓${X} entity filtering: $ENTITY_FILTER"
  else
    seed_err="$(printf '%s' "${seed_err:-no response}" | sanitize_terminal)"
    warn "dashboard seed not applied: $seed_err"
    echo "   ${D}A rejected path must name a dashboard on this Home Assistant, e.g. /lovelace or /dashboard-name/tab-name. Nothing was recorded as answered, so guided setup still asks.${X}"
    PROVISION_FAILED=1
    return 0
  fi

  # Record the matching wizard answers. Ordered strictly after the values above so an answer can never
  # be recorded for a value that failed to persist: the worst reachable failure is a correct value with
  # the question still open, which guided setup simply asks again.
  seed_answer() {
    local endpoint="$1" description="$2" answer_err
    if answer_err="$(curl -fsS --connect-timeout "$PANEL_POST_CONNECT_TIMEOUT_SECONDS" \
        --max-time "$PANEL_POST_TIMEOUT_SECONDS" -H 'Accept: application/json' \
        -X POST "$URL/api/v1/setup/$endpoint" 2>&1 >/dev/null)"; then
      return 0
    fi
    answer_err="$(printf '%s' "${answer_err:-no response}" | sanitize_terminal)"
    warn "$description was applied but not recorded as answered: $answer_err"
    echo "   ${D}The value is set; guided setup will still ask the question. Re-run the same command to record it.${X}"
    PROVISION_FAILED=1
  }
  [ "$HOME_DASHBOARD_SET" = 0 ] || seed_answer home-dashboard "the home dashboard"
  [ "$ENTITY_FILTER_SET" = 0 ] || seed_answer entity-filter "entity filtering"

  # An unknown dashboard is a WARNING, never a refusal: the catalogue is a live Home Assistant fact,
  # it may be unreachable, and a dashboard can legitimately be created after the panel is set up. Same
  # rule the two on-panel pickers already use.
  if [ "$HOME_DASHBOARD_SET" = 1 ] && [ "$seeded_path" != "/" ]; then
    local catalog root
    # An unreadable catalogue is reported as unverified rather than as an absence — the `queried`
    # check below distinguishes the two, so a swallowed error cannot become "no such dashboard".
    # This runs after the value is already durable and deliberately changes no outcome.
    catalog="$(curl -fsS --max-time 5 "$URL/api/v1/config/home-dashboards" 2>/dev/null || true)"
    # Compare on the dashboard ROOT: the catalogue lists dashboards, while a seed may name a view
    # BELOW one (/office/kitchen), which is exactly the case Issue #90 added and must not warn.
    root="/$(printf '%s' "${seeded_path#/}" | cut -d/ -f1 | cut -d'?' -f1 | cut -d'#' -f1)"
    if ! printf '%s' "$catalog" | grep -Eq '"queried"[[:space:]]*:[[:space:]]*true'; then
      echo "   ${D}Could not verify the dashboard against Home Assistant just now; the value is saved either way.${X}"
    elif ! printf '%s' "$catalog" | grep -Fq "\"path\":\"$root\""; then
      warn "Home Assistant does not currently list a dashboard at $root"
      echo "   ${D}The value is saved. Home Assistant silently falls back to the account default for an unknown dashboard, so check the path if the panel shows the wrong one.${X}"
    fi
  fi
}
seed_builtin_renderer_preferences

# Configuration can change live observations used by the planner. Re-render the app-owned plan after
# all config/import work; it remains guidance only and never authorises Bash to mutate panel state.
if [ "$PLAN_REFRESH_NEEDED" = 1 ]; then
  show_provisioning_plan 1 "${D}refreshing guidance after configuration${X}" \
    || PROVISION_FAILED=1
fi

echo
VERIFY_DIRECT_GRANTS=1
if verify; then
  if [ "$PROVISION_FAILED" = 0 ]; then
    read_setup_journey
    if [ "$SETUP_JOURNEY_AVAILABLE" = 1 ] && [ "$SETUP_COMPLETE" != 1 ]; then
      echo "${GRN}${B}✅ installed and verified${X} — ${B}$URL/${X}"
    else
      echo "${GRN}${B}✅ provisioned and verified${X} — ${B}$URL/${X}"
    fi
    print_next_step
  fi
else
  PROVISION_FAILED=1
fi
if [ "$PROVISION_FAILED" != 0 ]; then
  echo "${RED}${B}✗ provisioning incomplete${X} — correct the failed item above, then re-run the same command."
fi
if [ "$PROVISIONING_PLAN_AVAILABLE" != 1 ]; then
  echo "${D}   Root helper: needed only for helper-backed controls declared by the active profile, and installable only where live vendor su or root ADB works. Do not infer this from the SoC: some rk3576/PX30 panels are sandboxed, while others expose a usable root path. The app-owned plan is unavailable, so this fallback cannot state this panel's exact need.${X}"
  echo "${D}   From a source checkout, and only on a panel with a proven root path, install it with:  ./helper/build.sh && ./helper/install-daemon.sh $TARGET${X}"

  # Flag a too-old system WebView only for older apps. Current apps own this profile-aware guidance.
  check_webview
fi

# Tuya/TPA10 only: offer to disable the closed vendor app stack for a faster, minimal HA panel (guarded
# on root + persistent adb + a replacement launcher; prompts unless --strip-vendor was passed).
[ "$PROVISION_FAILED" = 0 ] && offer_strip_vendor

exit "$PROVISION_FAILED"
