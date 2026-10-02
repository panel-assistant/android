#!/usr/bin/env bash
# Panel verification, settings export, safe removal and historical helper recovery.
# Panel APK installation and updates require live Panel Assistant in Home Assistant.
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
Usage: scripts/provision.sh <panel-ip[:port]> [operation]
  --verify                 Check the existing installation without changing it
  --export FILE            Export config/settings; contains secrets
  --hand-back-home         Give the panel's home screen to another launcher
  --uninstall              Hand the home screen back, then remove both panel identities
  --recover-helper --apk FILE
                           Recover an existing APK-coupled helper journal using a
                           release-signed APK's embedded helper as a recovery probe.
                           The APK is never installed. No new helper upgrade begins.
Panel installation, configuration and updates use live Panel Assistant in Home Assistant.
EOF
}

cleanup_provision_resources() {
  if type cleanup_root_helper_staging >/dev/null 2>&1; then cleanup_root_helper_staging || true; fi
  [ -z "${RECOVERY_HOST_DIR:-}" ] || rm -rf "$RECOVERY_HOST_DIR" || true
  [ -z "${CANDIDATE_APK_DIR:-}" ] || rm -rf "$CANDIDATE_APK_DIR" || true
}
trap cleanup_provision_resources EXIT
trap 'exit 130' INT
trap 'exit 143' TERM
fail() {
  echo "${RED}${B}✗ $1${X}" >&2; shift
  local message; for message in "$@"; do echo "   $message" >&2; done
  exit 1
}
[ "$#" -gt 0 ] || { usage >&2; exit 2; }
case "$1" in -h|--help) usage; exit 0 ;; esac
TARGET="$1"; shift
PKG="io.panelassistant.android"
LEGACY_PKG="io.github.maxlyth.hapaneld"
CODE_PACKAGE="io.github.maxlyth.hapaneld"
REPO="panel-assistant/android"
RELEASE_CERT_SHA256="ac6193307fb0b70113aae205d7549406f96e063bc5491b67b1d5694a34b0e339"
RELEASE_HELPER_BUILD_ID=""
APK=""; APK_RELEASE_TAG=""; TOINSTALL_VER=""; REQUIRE_RELEASE_SIGNER=1; CANDIDATE_SIGNER_SHA256=""
VERIFY_ONLY=0; VERIFY_DIRECT_GRANTS=0; EXPORT_FILE=""; HAND_BACK_HOME=0; UNINSTALL=0; RECOVER_HELPER=0
AGENT_HEALTHY=0; HELPER_REQUIRED=0; PROVISIONING_PLAN_AVAILABLE=0; PROVISION_FAILED=0
RECOVERY_HOST_DIR=""; CANDIDATE_APK_DIR=""
ROOT_HELPER_TRANSACTION_KIND=""; ROOT_HELPER_TRANSACTION_SHA256=""; ROOT_HELPER_TRANSACTION_PATH=""
ROOT_HELPER_TRANSACTION_ID=""; ROOT_HELPER_TARGET_BUILD_ID=""; ROOT_HELPER_TARGET_SHA256=""
ROOT_HELPER_STAGED_HELPER=""; ROOT_HELPER_STAGED_RC=""; ROOT_HELPER_STAGED_HYBRID_RC=""
ROOT_HELPER_STAGED_SERVICE=""; ROOT_HELPER_STAGED_TRANSACTION=""; TARGET_APK_SHA256=""
PACKAGE_PRESENCE=unknown; PACKAGE_PRESENCE_FAULT=""
PACKAGE_MANAGER_LIVENESS_PKG="android"
PACKAGE_PROBE_BUDGET_SECONDS="${STORAGE_HEALTH_PACKAGE_QUERY_SECONDS:-15}"
PACKAGE_PROBE_CALIBRATION_SECONDS=""; PACKAGE_PROBE_CALIBRATION_TIMED_OUT=0
while [ "$#" -gt 0 ]; do
  case "$1" in
    --export|--apk)
      [ "$#" -ge 2 ] && [ -n "$2" ] && [ "${2#--}" = "$2" ] || fail "$1 needs a value"
      if [ "$1" = --export ]; then EXPORT_FILE="$2"; else APK="$2"; fi
      shift 2 ;;
    --verify) VERIFY_ONLY=1; shift ;;
    --hand-back-home) HAND_BACK_HOME=1; shift ;;
    --uninstall) UNINSTALL=1; shift ;;
    --recover-helper) RECOVER_HELPER=1; shift ;;
    -h|--help) usage; exit 0 ;;
    *) fail "panel app installation requires live Panel Assistant" "Install and configure this panel through Panel Assistant in Home Assistant. No panel changes were made." ;;
  esac
done
operation_count=$((VERIFY_ONLY + HAND_BACK_HOME + UNINSTALL + RECOVER_HELPER))
[ "$operation_count" -le 1 ] || fail "choose one standalone operation"
if [ "$RECOVER_HELPER" = 1 ]; then
  [ -z "$EXPORT_FILE" ] || fail "helper recovery cannot be combined with settings export"
else
  [ -z "$APK" ] || fail "panel app installation requires live Panel Assistant" "The shell cannot admit a panel APK without live Panel Assistant."
fi
[ "$operation_count" -ne 0 ] || [ -n "$EXPORT_FILE" ] || fail "panel app installation requires live Panel Assistant" "Use Panel Assistant in Home Assistant, including for a first installation."
HOST="${TARGET%:*}"; [ "$HOST" != "$TARGET" ] || HOST="$TARGET"
URL="http://$HOST:8888"
PANEL_POST_CONNECT_TIMEOUT_SECONDS="${PANEL_POST_CONNECT_TIMEOUT_SECONDS:-5}"
PANEL_POST_TIMEOUT_SECONDS="${PANEL_POST_TIMEOUT_SECONDS:-30}"
APP_HEALTH_TIMEOUT_SECONDS="${APP_HEALTH_TIMEOUT_SECONDS:-180}"
STORAGE_HEALTH_PACKAGE_QUERY_SECONDS="${STORAGE_HEALTH_PACKAGE_QUERY_SECONDS:-15}"
STORAGE_HEALTH_VERIFY_ATTEMPTS="${STORAGE_HEALTH_VERIFY_ATTEMPTS:-6}"
STORAGE_HEALTH_VERIFY_POLL_SECONDS="${STORAGE_HEALTH_VERIFY_POLL_SECONDS:-2}"
CONFIG_SCHEMA_VERIFY_TIMEOUT_SECONDS="${CONFIG_SCHEMA_VERIFY_TIMEOUT_SECONDS:-60}"
for timeout_name in PANEL_POST_CONNECT_TIMEOUT_SECONDS PANEL_POST_TIMEOUT_SECONDS APP_HEALTH_TIMEOUT_SECONDS STORAGE_HEALTH_VERIFY_ATTEMPTS CONFIG_SCHEMA_VERIFY_TIMEOUT_SECONDS PACKAGE_PROBE_BUDGET_SECONDS; do
  case "${!timeout_name}" in ''|*[!0-9]*|0) fail "$timeout_name must be a positive whole number of seconds" ;; esac
done
case "$STORAGE_HEALTH_VERIFY_POLL_SECONDS" in ''|*[!0-9]*) fail "STORAGE_HEALTH_VERIFY_POLL_SECONDS must be a non-negative whole number of seconds" ;; esac

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
app_component() {
  local pkg="$1" relative="$2"
  case "$relative" in .*) ;; *) return 1 ;; esac
  if [ "$pkg" = "$CODE_PACKAGE" ]; then printf '%s/%s\n' "$pkg" "$relative"
  else printf '%s/%s%s\n' "$pkg" "$CODE_PACKAGE" "$relative"; fi
}

normalize_component() {
  local flattened="$1" pkg class
  case "$flattened" in
    */*) pkg="${flattened%%/*}"; class="${flattened#*/}" ;;
    *) printf '%s\n' "$flattened"; return 0 ;;
  esac
  case "$class" in .*) class="$pkg$class" ;; esac
  printf '%s/%s\n' "$pkg" "$class"
}

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

classify_package_presence() {
  local seconds="$1" pkg="${2:-$PKG}" nonce out verdict status=0
  PACKAGE_PRESENCE="unknown"
  PACKAGE_PRESENCE_FAULT="no run nonce could be created"
  nonce="$(host_transaction_id)" || return 0
  # `\$?` is escaped so the PANEL's shell expands each child's status, not this one.
  out="$(run_with_deadline "$seconds" adb_exec -s "$TARGET" shell \
    "echo HAPANELD_PKG_BEGIN:$nonce; pm path $pkg; echo HAPANELD_PKG_TARGET:$nonce:\$?; \
     pm path $PACKAGE_MANAGER_LIVENESS_PKG; echo HAPANELD_PKG_LIVE:$nonce:\$?; \
     echo HAPANELD_PKG_END:$nonce" 2>/dev/null)" || status=$?
  case "$status" in
    0) PACKAGE_PRESENCE_FAULT="a reply that did not complete the probe or name a usable path" ;;
    124) PACKAGE_PRESENCE_FAULT="deadline"; return 0 ;;
    *) PACKAGE_PRESENCE_FAULT="adb exiting with status $status"; return 0 ;;
  esac
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
    present|absent) PACKAGE_PRESENCE="$verdict"; PACKAGE_PRESENCE_FAULT="" ;;
  esac
  return 0
}

read_config_schema() {
  local attempt=1 request_timeout="$CONFIG_SCHEMA_VERIFY_TIMEOUT_SECONDS" deadline body
  deadline=$((SECONDS + CONFIG_SCHEMA_VERIFY_TIMEOUT_SECONDS))
  while :; do
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
  fi
  # Runtime permissions are read back from the package manager, not from the app's own report: a grant
  # that a vendor build silently refuses leaves the app running with a capability quietly missing, and
  # only Android knows which it kept. Read on --verify too, where nothing was granted this run.
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
  else
    chk "WRITE_SETTINGS granted" "$diag" "write_settings=true"
    chk "accessibility enabled"  "$diag" "a11y=true"
  fi
  if [ "$notifications_are_runtime" = 1 ]; then
    chk "notification permission granted" "$package_state" 'android\.permission\.POST_NOTIFICATIONS: granted=true'
    case "$package_state" in
      *'android.permission.POST_NOTIFICATIONS: granted=true'*) : ;;
      *) echo "     ${D}Enable Settings → Apps → ha-paneld → Notifications, then re-run this command.${X}" ;;
    esac
  else
    echo "   ${GRN}✓${X} notification permission: not a runtime permission before Android 13 ${D}(this panel reports API $sdk_level; notifications need no grant here)${X}"
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

quote_root_command() {
  local command="$1"
  command="${command//\\/\\\\}"
  command="${command//\"/\\\"}"
  command="${command//\$/\\\$}"
  command="${command//\`/\\\`}"
  printf '%s\n' "$command"
}

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

probe_capture_excerpt() {
  local excerpt
  # awk stops after three lines, which can end the pipeline early under pipefail; a short excerpt is
  # still an excerpt, so that is not a failure.
  excerpt="$(printf '%s\n' "$1" | sanitize_terminal | awk 'NF { print; if (++n == 3) exit }')" || true
  if [ -n "$excerpt" ]; then printf '%s\n' "$excerpt"; else printf '(no output)\n'; fi
}

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

android_build_tool_runs() {
  local out
  out="$("$1" version 2>&1)" && [ -n "$out" ] && return 0
  out="$("$1" --version 2>&1)" && [ -n "$out" ]
}

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

verify_release_apk() {
  local expected_name signer_tool signer_output signer signer_lines signer_count package_tool package_name="" artifact_label
  local signer_tool_problem signer_error package_tool_problem
  artifact_label="local signed APK recovery evidence"
  signer_tool="$(find_android_build_tool apksigner || true)"
  signer_tool_problem="$(android_build_tool_failure apksigner || true)"
  if [ -z "$signer_tool" ]; then
    fail "Android Build-Tools are required to verify the recovery APK signer"       "${signer_tool_problem:-Install apksigner and retry.} No helper recovery was attempted."
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
    if [ "$REQUIRE_RELEASE_SIGNER" = 1 ]; then
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
  step "🛡️  verified" "${D}$artifact_label · package ${package_name:-authenticated release asset} · signer ${signer:-$RELEASE_CERT_SHA256}${X}"
}

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

host_sha256() {
  local file="$1" subject="$2"
  HOST_SHA256=""
  if command -v sha256sum >/dev/null 2>&1; then
    HOST_SHA256="$(sha256sum "$file" | awk '{print $1}')" || return 1
  elif command -v shasum >/dev/null 2>&1; then
    HOST_SHA256="$(shasum -a 256 "$file" | awk '{print $1}')" || return 1
  else
    fail "this computer cannot hash $subject: neither sha256sum nor shasum is installed" \
      "Install sha256sum (coreutils) or shasum, then re-run."
  fi
}

bind_candidate_apk_bytes() {
  host_sha256 "$APK" "the candidate APK" || true
  TARGET_APK_SHA256="$HOST_SHA256"
  printf '%s\n' "$TARGET_APK_SHA256" | grep -Eq '^[0-9a-f]{64}$' || fail "could not bind the authenticated candidate APK bytes" \
    "No panel mutation was started. Check that the APK is a readable regular file, then retry."
}

assert_candidate_apk_unchanged() {
  local observed
  [ -n "$TARGET_APK_SHA256" ] || fail "the candidate APK has no authenticated byte binding" \
    "No panel mutation was started. Re-run the same provisioning command."
  host_sha256 "$APK" "the candidate APK" || true
  observed="$HOST_SHA256"
  if [ "$observed" != "$TARGET_APK_SHA256" ]; then
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

probe_transport_alive() {
  local deadline="${1:-15}" probe_alive
  probe_alive="$(run_with_deadline "$deadline" adb_exec -s "$TARGET" shell echo HAPANELD_TRANSPORT_ALIVE 2>/dev/null | tr -d '\r')" || probe_alive=""
  case "$probe_alive" in
    *HAPANELD_TRANSPORT_ALIVE*) return 0 ;;
    *) return 1 ;;
  esac
}

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

run_root_helper_transaction() {
  local action="$1" transaction_id="${2:-$ROOT_HELPER_TRANSACTION_ID}" target_apk="${3:-$TARGET_APK_SHA256}"
  local target_build="${4:-$ROOT_HELPER_TARGET_BUILD_ID}" target_helper="${5:-$ROOT_HELPER_TARGET_SHA256}"
  case "$action" in
    discover-system|discover-systemless|discover-hybrid|status-system|status-systemless|status-hybrid|rollback-system|rollback-systemless|rollback-hybrid|cancel-external-system|cancel-external-systemless|cancel-external-hybrid|finalize-rollback-system|finalize-rollback-systemless|finalize-rollback-hybrid|commit-system|commit-systemless|commit-hybrid) ;;
    *) return 1 ;;
  esac
  printf '%s\n' "$ROOT_HELPER_TRANSACTION_SHA256" | grep -Eq '^[0-9a-f]{64}$' || return 1
  printf '%s\n' "$ROOT_HELPER_TRANSACTION_ID" | grep -Eq '^[0-9a-f]{32}$' || return 1
  [ "$ROOT_HELPER_TRANSACTION_PATH" = "/data/adb/hapaneld/.helper-transaction-$ROOT_HELPER_TRANSACTION_ID-$ROOT_HELPER_TRANSACTION_SHA256" ] || return 1
  run_root 'txn='"$ROOT_HELPER_TRANSACTION_PATH"'; expected='"$ROOT_HELPER_TRANSACTION_SHA256"'; owner=$(stat -c %u:%g $txn 2>/dev/null || toybox stat -c %u:%g $txn 2>/dev/null) || exit 1; [ $owner = 0:0 ] || exit 1; actual=$(sha256sum $txn 2>/dev/null || toybox sha256sum $txn 2>/dev/null) || exit 1; [ ${actual%% *} = $expected ] || exit 1; sh $txn '"$action $transaction_id $target_apk $target_build $target_helper"
}

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
  # A host adb deadline does not prove the device-side transaction stopped. Reclamation must
  # acquire the same lock before removing inputs that a still-running transaction may consume.
  # Never recover an existing lock here: uncertain custody leaves staging for the next sweep.
  # Quote the device path explicitly: MSYS exclusions match the whole argument, not lock= values.
  if ! run_root "lock='/dev/.hapaneld-helper-transaction.lock'"'
    trap "" 1 2 3 15
    mkdir "$lock" 2>/dev/null || exit 0
    trap "rm -f $lock/pid; rmdir $lock" 0
    trap "exit 129" 1
    trap "exit 130" 2
    trap "exit 131" 3
    trap "exit 143" 15
    echo $$ > "$lock/pid" || exit 1
    rm -f'"$paths" >/dev/null 2>&1; then
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
  host_sha256 "$pulled" "the installed ha-paneld APK" || true
  actual="$HOST_SHA256"
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

resolve_apk() {
  local source_apk staged_apk
  [ -f "$APK" ] || fail "APK not found: $APK"
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

prepare_root_helper_recovery() {
  local abi helper helper_asset expected_build_id staged_build_id transaction_file transaction_sha256 transaction_ready
  local rc_file hybrid_rc_file service_file legacy_rc_file legacy_rc_supervised_file
  local legacy_hybrid_rc_file legacy_hybrid_rc_supervised_file legacy_service_file legacy_service_supervised_file
  local bin_sha256 rc_sha256 hybrid_rc_sha256 service_sha256 legacy_rc_sha256 legacy_rc_supervised_sha256
  local legacy_hybrid_rc_sha256 legacy_hybrid_rc_supervised_sha256 legacy_service_sha256 legacy_service_supervised_sha256
  RECOVERY_HOST_DIR="$(mktemp -d)" || fail "could not create private helper recovery storage"
  abi="$(adb -s "$TARGET" shell getprop ro.product.cpu.abi | tr -d '\r')"
  case "$abi" in
    armeabi-v7a) helper_asset=assets/hapaneld-helper-arm ;;
    arm64-v8a) helper_asset=assets/hapaneld-helper-arm64 ;;
    *) fail "unsupported helper recovery architecture: $abi" ;;
  esac
  helper="$RECOVERY_HOST_DIR/hapaneld-helper"
  assert_candidate_apk_unchanged
  unzip -p "$APK" "$helper_asset" > "$helper" || fail "the signed APK has no helper recovery probe"
  [ -s "$helper" ] || fail "the signed APK has no helper recovery probe"
  assert_candidate_apk_unchanged
  staged_build_id="$(extract_helper_build_id "$helper")" || fail "the signed APK helper has an invalid build identity"
  expected_build_id="$staged_build_id"
  ROOT_HELPER_TRANSACTION_ID="$(host_transaction_id)" || fail "could not create a unique root-helper transaction identity" \
    "No helper or APK files were changed. Check host access to /dev/urandom, then retry."
  ROOT_HELPER_STAGED_HELPER="/data/local/tmp/hapaneld-helper-$ROOT_HELPER_TRANSACTION_ID"
  ROOT_HELPER_STAGED_RC="$ROOT_HELPER_STAGED_HELPER.rc"
  ROOT_HELPER_STAGED_HYBRID_RC="$ROOT_HELPER_STAGED_HELPER.hrc"
  ROOT_HELPER_STAGED_SERVICE="$ROOT_HELPER_STAGED_HELPER.svc"
  ROOT_HELPER_STAGED_TRANSACTION="$ROOT_HELPER_STAGED_HELPER.txn"
  ROOT_HELPER_TARGET_BUILD_ID="$expected_build_id"

  rc_file="$RECOVERY_HOST_DIR/current.rc"
  hybrid_rc_file="$RECOVERY_HOST_DIR/current.hrc"
  service_file="$RECOVERY_HOST_DIR/current.svc"
  legacy_rc_file="$RECOVERY_HOST_DIR/legacy_rc_file"
  legacy_rc_supervised_file="$RECOVERY_HOST_DIR/legacy_rc_supervised_file"
  legacy_hybrid_rc_file="$RECOVERY_HOST_DIR/legacy_hybrid_rc_file"
  legacy_hybrid_rc_supervised_file="$RECOVERY_HOST_DIR/legacy_hybrid_rc_supervised_file"
  legacy_service_file="$RECOVERY_HOST_DIR/legacy_service_file"
  legacy_service_supervised_file="$RECOVERY_HOST_DIR/legacy_service_supervised_file"
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
  host_sha256 "$legacy_rc_file" "the root-helper staging"; legacy_rc_sha256="$HOST_SHA256"
  host_sha256 "$legacy_rc_supervised_file" "the root-helper staging"; legacy_rc_supervised_sha256="$HOST_SHA256"
  host_sha256 "$legacy_hybrid_rc_file" "the root-helper staging"; legacy_hybrid_rc_sha256="$HOST_SHA256"
  host_sha256 "$legacy_hybrid_rc_supervised_file" "the root-helper staging"; legacy_hybrid_rc_supervised_sha256="$HOST_SHA256"
  host_sha256 "$legacy_service_file" "the root-helper staging"; legacy_service_sha256="$HOST_SHA256"
  host_sha256 "$legacy_service_supervised_file" "the root-helper staging"; legacy_service_supervised_sha256="$HOST_SHA256"
  rm -f "$legacy_rc_file" "$legacy_rc_supervised_file" \
    "$legacy_hybrid_rc_file" "$legacy_hybrid_rc_supervised_file" \
    "$legacy_service_file" "$legacy_service_supervised_file"
  transaction_file="$RECOVERY_HOST_DIR/transaction.sh"
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

# `df -P -k <dir>` resolves the filesystem that actually holds <dir>, which is the whole point: the
# route probe tested `/system` and the transaction writes to `/system/bin`, and nothing in the
# installer has ever checked that those two are the same mount.


# The staged bundle is pushed by one adb invocation and read by a later one, with the disposable
# staging sweep running in between, so "the file this transaction was told to copy is still there
# and still authentic" is a distinct question from "the destination accepted a write".


# Sum every byte this transaction is about to write into one filesystem: the staged replacements and
# the recovery copies it takes of whatever is live. Measuring the real files beats a fixed constant,
# which is how a panel carrying a legacy hapaneld-ledd pair could pass a headroom check computed for
# a panel that has none.

# Prove the destination accepts a real, non-zero write before anything is removed or replaced.
#
# The route probe this replaces wrote a ZERO-byte file to `/system`, then the transaction wrote
# 76KB to `/system/bin`. A zero-byte create at the parent cannot fail for a full filesystem, for
# exhausted inodes on a subtree, or for a read-only overmount one level down — which is precisely
# the class of panel that reached the first cp and failed there.

# Refuse before the first mutation, naming the blocker.
#
# Ordered cheapest and most decisive first, because the reasons are not interchangeable: staging
# that is gone is not a partition fault, a read-only partition is not a capacity fault, and a
# capacity fault is not a permissions fault. Anything that fails here has changed nothing, so the
# host can say so without offering a rollback of an install that never started.


# One copy, with the reason kept when it fails. The step names are unchanged, so an existing report
# still reads the same; what is new is the errno line and the two diagnostic lines beside it.

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
  remove_if_present "$v1_restore_staging" || return 1
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
    remove_if_present "$v1_restore_live" || return 1
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
  remove_if_present "$staging" || return 1
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
    remove_if_present "$live" || return 1
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

remove_if_present() {
  for stale in "$@"; do
    if [ -e "$stale" ] || [ -L "$stale" ]; then
      rm -f "$stale"
      if [ -e "$stale" ] || [ -L "$stale" ]; then
        return 1
      fi
    fi
  done
  return 0
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

acquire_helper_lock || { echo TRANSACTION_BUSY; exit 75; }

transaction_id=${2:-}
target_apk=${3:-}
target_build=${4:-}
target_helper=${5:-}

case "${1:-}" in
  *-system) marker=/system/bin/.hapaneld-helper-upgrade ;;
  *-systemless) marker=/data/adb/hapaneld/.helper-upgrade.marker ;;
  *-hybrid) marker=/data/adb/hapaneld/.helper-hybrid-upgrade.marker ;;
  *) exit 2 ;;
esac
root_owned "$marker" || exit 1
lease_active "$marker" && { echo TRANSACTION_BUSY; exit 75; }

case "${1:-}" in
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
  *) exit 2 ;;
esac
EOF
  host_sha256 "$helper" "the root-helper staging"; bin_sha256="$HOST_SHA256"
  host_sha256 "$rc_file" "the root-helper staging"; rc_sha256="$HOST_SHA256"
  host_sha256 "$hybrid_rc_file" "the root-helper staging"; hybrid_rc_sha256="$HOST_SHA256"
  host_sha256 "$service_file" "the root-helper staging"; service_sha256="$HOST_SHA256"
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
  host_sha256 "$transaction_file" "the root-helper staging"; transaction_sha256="$HOST_SHA256"
  ROOT_HELPER_TARGET_SHA256="$bin_sha256"
  ROOT_HELPER_TRANSACTION_SHA256="$transaction_sha256"
  ROOT_HELPER_TRANSACTION_PATH="/data/adb/hapaneld/.helper-transaction-$ROOT_HELPER_TRANSACTION_ID-$transaction_sha256"

  # Only the authenticated request probe and recovery program are staged; no boot registration or
  # candidate installation action is available through this program.
  adb -s "$TARGET" push "$helper" "$ROOT_HELPER_STAGED_HELPER" >/dev/null
  adb -s "$TARGET" push "$transaction_file" "$ROOT_HELPER_STAGED_TRANSACTION" >/dev/null
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
    [ -z "$RECOVERY_HOST_DIR" ] || rm -rf "$RECOVERY_HOST_DIR"
    fail "the root-helper transaction could not be promoted into protected storage" \
      "No privileged transaction script was executed and the APK was not replaced."
  fi
}

recover_historical_helper() {
  local inventory kinds=() kind
  resolve_root_route
  [ "$ROOT_ROUTE_VERDICT" = rooted ] || fail "helper recovery requires a proven root route" "No journal, helper or APK was changed."
  inventory="$(run_root '
    for kind in system systemless hybrid; do
      case $kind in
        system) marker=/system/bin/.hapaneld-helper-upgrade ;;
        systemless) marker=/data/adb/hapaneld/.helper-upgrade.marker ;;
        hybrid) marker=/data/adb/hapaneld/.helper-hybrid-upgrade.marker ;;
      esac
      if [ -e "$marker" ] || [ -L "$marker" ]; then echo "JOURNAL $kind"; fi
    done
    echo JOURNAL_SCAN_COMPLETE
  ')" || fail "the helper recovery journal inventory could not be read"
  [ "$(printf '%s\n' "$inventory" | grep -c '^JOURNAL_SCAN_COMPLETE$')" = 1 ] || fail "the helper recovery journal inventory is incomplete"
  while IFS= read -r kind; do
    case "$kind" in system|systemless|hybrid) kinds+=("$kind") ;; *) fail "the helper recovery journal inventory is malformed" ;; esac
  done < <(printf '%s\n' "$inventory" | sed -n 's/^JOURNAL //p')
  [ "${#kinds[@]}" -le 1 ] || fail "more than one root-helper recovery journal is present" "No recovery was attempted because ownership is ambiguous."
  if [ "${#kinds[@]}" = 0 ]; then echo "No APK-coupled helper recovery journal is present; nothing changed."; return 0; fi
  [ -n "$APK" ] || fail "helper recovery requires --apk FILE" "Use a release-signed APK as the request probe source. It will not be installed."
  resolve_apk
  bind_candidate_apk_bytes
  verify_release_apk
  prepare_root_helper_recovery
  reconcile_stale_root_helper "${kinds[0]}" || fail "the retained helper journal could not be recovered safely" "No APK was installed. The journal and authenticated recovery copies remain available."
  echo "Historical helper recovery verified; no APK was installed."
}

A11Y="$(app_component "$PKG" .input.PanelAccessibilityService)"
echo "${MAG}${B}🛠 ha-paneld maintenance${X} ${D}→ $TARGET${X}"
adb_preflight
if [ "$RECOVER_HELPER" = 1 ]; then recover_historical_helper; exit 0; fi
if [ -n "$EXPORT_FILE" ]; then export_config "$EXPORT_FILE"; fi
if [ "$VERIFY_ONLY" = 1 ]; then show_provisioning_plan 0 "${D}reading installed guidance${X}"; verify; exit $?; fi
if [ "$HAND_BACK_HOME" = 1 ] || [ "$UNINSTALL" = 1 ]; then
  wait_for_handback_health
  if [ "$UNINSTALL" = 1 ]; then uninstall_ha_paneld; else hand_back_home; fi
fi
