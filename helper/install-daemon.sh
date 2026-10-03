#!/usr/bin/env bash
#
# Install the hapaneld-helper root helper as a boot-persistent service on a panel.
# Run it on every rooted supported panel. Sandbox-walled panels need the daemon for privileged
# controls; direct-su panels also need the current descriptor-confined Companion data protocol.
#
# One canonical live binary and three boot-registration paths, chosen by capability probes on the
# device (not by root-tool identity):
#
#   Every route installs root:root 0700 /data/local/hapaneld-helper. A writable /system or /vendor
#   receives only an init registration; a verified systemless environment receives only a service.d
#   registration. Every registration launches the canonical binary with --supervise. Historical
#   /system/bin and /data/adb helper layouts are removed only inside the authenticated transaction
#   and restored byte-for-byte if that transaction rolls back.
#
#   The new journal is version 3 and describes that canonical topology. Version 1 and 2 journals
#   remain version-dispatched to their original recovery code and are never interpreted as v3.
#
#   ./helper/install-daemon.sh <panel-ip:5555> [abi]
#
# Requires: adb access + root on the panel, and a built binary (./helper/build.sh). Root is probed —
# vendor su forms vary (`su 0`, `su -c`, `su root`) and userdebug adbd may be root with no su at all.
set -euo pipefail

TARGET="${1:?usage: install-daemon.sh <panel-ip:5555> [abi]}"
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

fail() {
  echo "✗ $1" >&2; shift
  local l; for l in "$@"; do echo "   $l" >&2; done
  exit 1
}

# Render what the panel actually answered so an unrecognised state is reportable instead of anonymous.
# A refusal that cannot say what it saw sends the operator to repair the wrong thing: the SMT1019
# report (#106) had working adb and working root, and was told to restore both. That report was later
# shown NOT to be a transport problem — the panel advertises `shell_v2` — which is exactly the point:
# the refusal could not say what it had seen, so the cause had to be guessed at from outside.
#
# Non-printing characters are REPORTED, not silently removed. Deleting them would render an answer
# that differs from a valid one only by an invisible byte as though it were the valid one — hiding the
# single most useful clue in the line whose whole purpose is to carry clues. They are still not
# printed raw: this is text from an untrusted device shell, so the line is sanitised and truncated and
# their presence is stated instead. Line breaks and tabs are ordinary answer shape and are not flagged.
describe_observed_state() {
  local observed="$1" flattened rendered
  flattened="$(printf '%s' "$observed" | LC_ALL=C tr '\n\t' '  ')"
  # `[:cntrl:]` rather than an octal range: this line runs on the operator's machine, and the reporter's
  # is macOS, whose BSD tr is the one that has to agree with GNU tr about what it means.
  rendered="$(printf '%s' "$flattened" | LC_ALL=C tr -d '[:cntrl:]')"
  if [ -z "$rendered" ]; then
    if [ -n "$flattened" ]; then
      printf 'the panel answered only non-printing characters'
    else
      printf 'the panel answered nothing'
    fi
    return
  fi
  [ "${#rendered}" -le 200 ] || rendered="${rendered:0:200}…"
  if [ "$flattened" = "$rendered" ]; then
    printf 'the panel answered: %s' "$rendered"
  else
    printf 'the panel answered: %s (plus non-printing characters, so this is how it was sent, not what it said)' "$rendered"
  fi
}

# Run one host command behind a hard deadline. The command owns a local process group so timeout and
# caller cancellation terminate and reap shell wrappers as well as their non-detached descendants.
# Status 124 is reserved for the deadline; ordinary command failures retain their original status.
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

ADB_COMMAND="$(command -v adb 2>/dev/null || true)"
[ -n "$ADB_COMMAND" ] || fail "adb (Android Platform Tools) was not found" \
  "Install adb, then re-run the identical command; no helper files were changed."
ADB_COMMAND_TIMEOUT_SECONDS="${ADB_COMMAND_TIMEOUT_SECONDS:-120}"
ADB_PREFLIGHT_TIMEOUT_SECONDS="${ADB_PREFLIGHT_TIMEOUT_SECONDS:-20}"
PRIVILEGE_INSPECTION_TIMEOUT_SECONDS="${PRIVILEGE_INSPECTION_TIMEOUT_SECONDS:-45}"
for timeout_name in ADB_COMMAND_TIMEOUT_SECONDS ADB_PREFLIGHT_TIMEOUT_SECONDS \
    PRIVILEGE_INSPECTION_TIMEOUT_SECONDS; do
  timeout_value="${!timeout_name}"
  case "$timeout_value" in
    ''|*[!0-9]*|0) fail "$timeout_name must be a positive whole number of seconds" ;;
  esac
done

# Git for Windows runs this script on an MSYS runtime that rewrites any argument beginning with a
# forward slash into a Windows path before a native program such as adb.exe is exec'd, which turned
# `adb push <local> /data/local/tmp/...` into a push to `D:/Program Files/Git/data/local/tmp/...` and
# staged nothing on the panel (#24). MSYS2_ARG_CONV_EXCL is the runtime's semicolon-separated list of
# argument prefixes to hand over untouched, so every Android filesystem root this script can name in
# an adb argument is listed. Deliberately not a blanket `*` or MSYS_NO_PATHCONV: this script also
# hands adb genuine host paths (the push sources), and under Git Bash those must still be translated.
# Inert outside MSYS/Cygwin, so it is set unconditionally. Keep aligned with scripts/provision.sh.
ADB_MSYS_ARG_CONV_EXCL='/acct;/apex;/cache;/config;/data;/dev;/mnt;/odm;/oem;/proc;/product;/sbin;/sdcard;/storage;/sys;/system;/vendor'

# Every adb execution in this script crosses this one boundary so a new call site cannot quietly
# reintroduce the rewrite; helper/test/standalone_installer_identity_test.sh enforces that statically.
adb_exec() {
  MSYS2_ARG_CONV_EXCL="$ADB_MSYS_ARG_CONV_EXCL" "$ADB_COMMAND" "$@"
}

# Every ordinary adb operation crosses this one boundary. Compound operations that need one total
# budget use the guarded executable inside their own run_with_deadline call instead of nesting owners.
adb() {
  run_with_deadline "$ADB_COMMAND_TIMEOUT_SECONDS" adb_exec "$@"
}

# `adb connect` exits 0 even when it fails, and a device can sit "unauthorized" (RSA dialog waiting on
# the panel screen) or "offline" (stale session). Verify the state so failures surface here with
# recovery steps, not as a raw abort at the first adb push. $1=quiet re-checks after an adbd restart.
adb_preflight_raw() {
  local state="" i=0
  adb_exec connect "$TARGET" >/dev/null 2>&1 || true
  while [ "$i" -lt 12 ]; do
    i=$((i + 1))
    state="$(adb_exec devices 2>/dev/null | awk -v t="$TARGET" '$1==t {print $2}')"
    if [ "$state" = "device" ]; then printf '%s\n' "$state"; return 0; fi
    if [ "$state" = "offline" ] && [ "$i" = 4 ]; then
      adb_exec disconnect "$TARGET" >/dev/null 2>&1 || true
      adb_exec connect "$TARGET" >/dev/null 2>&1 || true
    fi
    sleep 1
  done
  printf '%s\n' "${state:-missing}"
  return 1
}

adb_preflight() {
  [ "${1:-}" = quiet ] || echo "==> connecting to $TARGET"
  local state="" status
  if state="$(run_with_deadline "$ADB_PREFLIGHT_TIMEOUT_SECONDS" adb_preflight_raw)"; then
    return 0
  else
    status=$?
  fi
  if [ "$status" -eq 124 ] || [ "$status" -eq 137 ]; then
    fail "adb connection check timed out after ${ADB_PREFLIGHT_TIMEOUT_SECONDS}s" \
      "Restore adb responsiveness and re-run; no helper files were changed."
  fi
  case "$state" in
    unauthorized) fail "panel refused adb: unauthorized" \
      "Accept the ADB authorization dialog shown ON THE PANEL'S SCREEN (tick 'always allow'), then re-run." ;;
    offline) fail "panel is stuck 'offline' on adb" \
      "Toggle 'ADB debugging' off/on in the panel's Developer options (or power-cycle the panel), then re-run." ;;
    *) fail "cannot reach $TARGET over adb" \
      "Check: the IP, that network ADB is enabled (Developer options), the port ($TARGET), and that this machine is on the panel's network/VLAN." \
      "Some panels only expose adb on USB until 'adb tcpip 5555' is run once — see docs/provisioning.md ('Bootstrapping adb')." ;;
  esac
}

# Root-path probe — vendor root varies TWICE over: the prefix (`su 0`, `su root`, `su -c`) AND the
# dialect. Join-style su (SuperSU/toolbox — the NSPanel Pro) re-joins argv and runs it through its
# own `sh -c`, so a block must be passed as ONE quoted word (`su 0 "BLOCK"`) — adding `sh -c`
# double-wraps and silently STRIPS the quoting. Execvp-style su (AOSP) execs argv directly, so a
# block DOES need the `sh -c` wrapper. `"id; id"` only succeeds through a shell, so probing with it
# identifies the wrapping that preserves a multi-command block. A root adbd (userdebug after
# `adb root`) needs no su at all — probed first. A su that prompts on-screen (Magisk) can take ~10s
# to auto-deny a form; the probe tolerates that.
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
  if [ -n "$SU_FORM" ]; then [ "$SU_FORM" != none ]; return; fi
  local result="" status
  SU_PROBE_TIMED_OUT=0
  if result="$(run_with_deadline "$PRIVILEGE_INSPECTION_TIMEOUT_SECONDS" probe_su_uncached)"; then
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

# Quote a shell block for the device shell's outer double quotes. Join-style su needs the complete
# block as one argv word, while the unprivileged device shell must not expand dollars, backticks,
# substitutions, or embedded quotes before su receives it.
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
  case "$SU_FORM" in
    shell|su0join|su0shc|surootjoin|surootshc|suc) ;;
    # A form this dispatch does not know is a failure, never a silent success with empty output —
    # callers read run_root's status as "the panel was asked".
    *) return 1 ;;
  esac
  quoted="$(quote_root_command "$command")"
  # Every classifier below reads device state as an exact token, so the transport must not be able to
  # alter one. An adbd without the `shell_v2` feature serves `shell:` through a PTY unconditionally,
  # which turns each device newline into CRLF; the panel then answers NO_STALE_TRANSACTION\r and every
  # exact comparison in this script misses. Normalise once, here, at the single seam they all read
  # through, rather than at each call site where the next added site would forget it. Only the carriage
  # return is removed: unexpected tokens, extra lines and empty output stay unexpected, so an unknown
  # state still refuses.
  case "$SU_FORM" in
    shell)      adb -s "$TARGET" shell "$command" ;;
    su0join)    adb -s "$TARGET" shell "su 0 \"$quoted\"" ;;
    su0shc)     adb -s "$TARGET" shell "su 0 sh -c \"$quoted\"" ;;
    surootjoin) adb -s "$TARGET" shell "su root \"$quoted\"" ;;
    surootshc)  adb -s "$TARGET" shell "su root sh -c \"$quoted\"" ;;
    suc)        adb -s "$TARGET" shell "su -c \"$quoted\"" ;;
  esac | tr -d '\r'
}

run_root_locked() {
  local block="$1"
  run_root '
    lock=/dev/.hapaneld-helper-transaction.lock
    if ! mkdir "$lock" 2>/dev/null; then
      holder=$(cat "$lock/pid" 2>/dev/null || true)
      case "$holder" in
        ''|*[!0-9]*) echo TRANSACTION_BUSY; exit 75 ;;
        *) [ ! -d "/proc/$holder" ] || { echo TRANSACTION_BUSY; exit 75; } ;;
      esac
      rm -rf "$lock" 2>/dev/null || { echo TRANSACTION_BUSY; exit 75; }
      mkdir "$lock" 2>/dev/null || { echo TRANSACTION_BUSY; exit 75; }
    fi
    echo $$ > "$lock/pid" || { rm -rf "$lock"; echo TRANSACTION_BUSY; exit 75; }
    cleanup_helper_lock() { rm -rf /dev/.hapaneld-helper-transaction.lock; }
    abort_helper_lock() {
      signal_status=$1
      trap - 0 1 2 3 15
      cleanup_helper_lock
      exit "$signal_status"
    }
    trap cleanup_helper_lock 0
    trap "abort_helper_lock 129" 1
    trap "abort_helper_lock 130" 2
    trap "abort_helper_lock 131" 3
    trap "abort_helper_lock 143" 15
  '"$block"
}

adb_preflight

# WHICH helper this run installs is decided entirely from local files plus one unprivileged property
# read, so it is decided HERE, before the panel is asked for any privilege. A run that is going to be
# refused because the bytes on this host cannot state one identity must not first restart adbd as root
# or raise an on-screen root prompt on a wall panel; the refusal owes nothing to the panel's state and
# should cost the panel nothing.
ABI="${2:-$(adb -s "$TARGET" shell getprop ro.product.cpu.abi 2>/dev/null | tr -d '\r')}"
[ -n "$ABI" ] || fail "could not read the panel's ABI (getprop returned nothing)" \
  "Pass it explicitly: ./helper/install-daemon.sh $TARGET arm64-v8a   (or armeabi-v7a)"
BIN_ROOT="${HAPANELD_HELPER_DIST_DIR:-$HERE/dist}"
BIN="$BIN_ROOT/$ABI/hapaneld-helper"
[ -f "$BIN" ] || fail "missing $BIN" "Build it first: ./helper/build.sh   (builds every ABI into helper/dist/)"
host_sha256() {
  if command -v sha256sum >/dev/null 2>&1; then sha256sum "$1" | awk '{print $1}'
  elif command -v shasum >/dev/null 2>&1; then shasum -a 256 "$1" | awk '{print $1}'
  else fail "cannot authenticate helper staging" "Install sha256sum (or shasum), then re-run."
  fi
}
BIN_SHA256="$(host_sha256 "$BIN")"
RC_SHA256="$(host_sha256 "$HERE/hapaneld-helper.rc")"
# A helper binary stamps its own build identity into its bytes, so the identity the replacement daemon
# must answer with is read from the artifact this run is about to stage — never from the sources that
# happen to sit beside this script. `HAPANELD_HELPER_DIST_DIR` can point the staged bytes at a foreign
# build while `source-id.sh` keeps describing the local checkout; the two then disagree for a correctly
# installed helper, and the transaction rolls a good install back and reports it as a build-identity
# failure. Under the documented flow (./helper/build.sh writes helper/dist from these same sources) the
# two answers are identical, so nothing about that path changes.
#
# Exactly one well-formed record must be present: none means the artifact cannot state who it is, and
# more than one means it states two contradictory answers. Both are refusals, never a guess.
#
# THE RECORD'S TERMINATOR IS NOT FIXED ACROSS BUILDS, so do not assume one. Here `dispatch.c` replies
# with `"BUILDID " HAPANELD_BUILD_ID "\n"`, one literal, so the artifact ends the record with a newline
# and the NUL that terminates every C string. Store it instead as a bare `BUILD_ID_RECORD[]` and append
# the newline at reply time and the newline disappears from the artifact entirely, leaving only the
# NUL. Both forms have shipped in this project. Translating NUL to newline before the
# line-oriented pass makes BOTH layouts a line, and it keeps binary bytes out of the shell: a NUL
# cannot survive a Bash variable, so a record collected across one arrives silently mangled instead of
# refused. That cost a review round on a tree where the record was NUL-only; this reading is
# layout-independent so it cannot cost another.
#
# Each record is then collected WHOLE, to its terminator, and only then judged. Any pattern that stops
# earlier reports a prefix of the record as the identity: `BUILDID [0-9a-f]{64}` truncates a longer hex
# run, and stopping at the first character outside an identity alphabet truncates `…<64 hex>!garbage`
# to the leading 64. Either way the answer is a record the artifact never stated, reached by
# truncation, and afterwards indistinguishable from a correct one. Nothing bounds the match on the
# left, because in a stripped binary the byte before the literal is arbitrary and may itself be a hex
# digit. Bytes after the terminator are not part of the record and do not disqualify it; a NUL INSIDE
# the identity ends the record early, so it never stated 64 hex and is refused.
#
# Every stage reads in the C locale, for the same reason `describe_observed_state` above does: this
# code reads bytes that the operator's environment has opinions about. `.` is defined over CHARACTERS,
# so a multibyte locale must decide what an invalid byte sequence is, and in a stripped binary those
# are ordinary payload. This is not theoretical and the pin is not defensive: measured on a host whose
# ambient locale is `en_US.UTF-8`, the unpinned collection returns 72 bytes where the C locale returns
# 77 — it drops the trailing `\377junk` from `BUILDID <64 hex>\377junk` and the truncated prefix is
# then accepted as the identity. That is the very defect the pattern was widened to close, arriving
# through the environment rather than through the pattern. Bytes, not characters, is in any case the
# right reading for an ELF artifact.
extract_helper_build_id() {
  local file="$1" records ids
  # No match and an unreadable file are both "cannot state an identity", which the count checks below
  # turn into a refusal; the tolerated statuses here only stop `set -e` from aborting the run before
  # that refusal can be reported with its own message.
  records="$(LC_ALL=C tr '\0' '\n' < "$file" 2>/dev/null | LC_ALL=C grep -aoE 'BUILDID .*' || true)"
  [ "$(printf '%s\n' "$records" | LC_ALL=C grep -c '^BUILDID ')" -eq 1 ] || return 1
  ids="$(printf '%s\n' "$records" | LC_ALL=C sed -nE 's/^BUILDID ([0-9a-f]{64})$/\1/p')"
  [ "$(printf '%s\n' "$ids" | LC_ALL=C grep -Ec '^[0-9a-f]{64}$')" -eq 1 ] || return 1
  printf '%s\n' "$ids"
}
if ! BUILD_ID="$(extract_helper_build_id "$BIN")"; then
  fail "the helper at $BIN does not state a single valid build identity" \
    "The binary carries no usable build-identity record, or carries more than one, so the daemon that replaces the running helper could not be recognised afterwards." \
    "Rebuild it: ./helper/build.sh   (builds every ABI into helper/dist/)" \
    "Nothing was installed, started, or privileged."
fi

# Try for a root adbd (userdebug builds) — harmless where unsupported. adbd restarts on success and
# can drop the TCP session, so quietly re-verify the connection either way.
adb -s "$TARGET" root >/dev/null 2>&1 || true
sleep 1
adb_preflight quiet

if ! probe_su; then
  [ "$SU_PROBE_TIMED_OUT" = 0 ] || fail "root-access inspection timed out after ${PRIVILEGE_INSPECTION_TIMEOUT_SECONDS}s" \
    "adb or a privilege prompt did not respond. Nothing was installed; restore adb responsiveness, dismiss any on-panel root prompt, then re-run."
  fail "no working root path on this panel (tried: adbd-root, 'su 0', 'su -c', 'su root')" \
    "The helper daemon requires root — it IS the privileged control path on sandbox-walled panels." \
    "Rooted panel with a different su syntax? Run 'adb shell', find the invocation that gives uid=0, and open an issue: https://github.com/panel-assistant/android/issues" \
    "No root at all? The daemon cannot be installed; ha-paneld still runs with reduced control (see helper/README.md)."
fi
case "$SU_FORM" in
  shell)      echo "==> root path: adbd runs as root (no su needed)" ;;
  su0join)    echo "==> root path: su 0 \"<cmd>\" (join-style su)" ;;
  su0shc)     echo "==> root path: su 0 sh -c \"<cmd>\"" ;;
  surootjoin) echo "==> root path: su root \"<cmd>\" (join-style su)" ;;
  surootshc)  echo "==> root path: su root sh -c \"<cmd>\"" ;;
  suc)        echo "==> root path: su -c \"<cmd>\"" ;;
esac

if ! run_root '[ ! -f /system/bin/.hapaneld-helper-upgrade ] && [ ! -f /data/adb/hapaneld/.helper-upgrade.marker ] && [ ! -f /data/adb/hapaneld/.helper-hybrid-upgrade.marker ]' \
    >/dev/null 2>&1; then
  fail "an incomplete APK-coupled helper upgrade must be recovered by the provisioner first" \
    "Recover its authenticated journal first: scripts/provision.sh $TARGET --recover-helper --apk <release-signed-panel.apk>. The APK is recovery evidence and will not be installed." \
    "This standalone installer uses a separate journal and did not change helper files."
fi

# Journal v1 predates TARGET_SERVICE_SHA256. These are the only two registration byte streams ever
# published by that journal version; accepting anything else would let an unrecorded live file gain
# rollback authority merely because it occupies the historical fixed path.
LEGACY_V1_SYSTEM_RC_SHA256="b42a66ff435a830390c7f04e66ffa252e3bf4027e68c72a29002df4886f8d4f4"
LEGACY_V1_SYSTEMLESS_SERVICE_SHA256="60ff22aa9b38483cbffd95a653d804d0d9abf682e1b952e8b4519d5c0f3f9493"
TRANSACTION_ID="$(od -An -N16 -tx1 /dev/urandom 2>/dev/null | tr -d ' \n')"
printf '%s\n' "$TRANSACTION_ID" | grep -Eq '^[0-9a-f]{32}$' || fail "could not create a root-helper transaction nonce"
PROBE_STAGING_PATH="/data/local/tmp/hapaneld-helper.probe-$TRANSACTION_ID"
RC_STAGING_PATH="$PROBE_STAGING_PATH.rc"
SVC_STAGING_PATH="$PROBE_STAGING_PATH.svc"
CANONICAL_HELPER_PATH="/data/local/hapaneld-helper"
CANONICAL_CANDIDATE_PATH="/data/local/.hapaneld-helper.manual-$TRANSACTION_ID"
MANUAL_V3_MARKER="/data/local/.hapaneld-helper-manual-upgrade"
MANUAL_LEASE_SECONDS="${HAPANELD_MANUAL_LEASE_SECONDS:-600}"
MANUAL_LEASE_RENEW_SECONDS="${HAPANELD_MANUAL_LEASE_RENEW_SECONDS:-60}"
case "$MANUAL_LEASE_SECONDS" in ''|*[!0-9]*|0) fail "invalid standalone transaction lease duration" ;; esac
case "$MANUAL_LEASE_RENEW_SECONDS" in ''|*[!0-9]*|0) fail "invalid standalone transaction lease renewal interval" ;; esac
[ "$MANUAL_LEASE_RENEW_SECONDS" -lt "$MANUAL_LEASE_SECONDS" ] || fail "standalone transaction lease renewal must be shorter than its duration"
MANUAL_LEASE_GUARD_PID=""
MANUAL_LEASE_GUARD_FAILURE=""
cleanup_probe_staging() {
  adb -s "$TARGET" shell rm -f "$PROBE_STAGING_PATH" "$RC_STAGING_PATH" "$SVC_STAGING_PATH" >/dev/null 2>&1 || true
}
stop_manual_lease_guard() {
  if [ -n "${MANUAL_LEASE_GUARD_PID:-}" ]; then
    kill "$MANUAL_LEASE_GUARD_PID" >/dev/null 2>&1 || true
    wait "$MANUAL_LEASE_GUARD_PID" >/dev/null 2>&1 || true
    MANUAL_LEASE_GUARD_PID=""
  fi
}
cleanup_installer_resources() {
  stop_manual_lease_guard
  [ -z "${MANUAL_LEASE_GUARD_FAILURE:-}" ] || rm -f "$MANUAL_LEASE_GUARD_FAILURE"
  MANUAL_LEASE_GUARD_FAILURE=""
  cleanup_probe_staging
}
trap cleanup_installer_resources EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

helper_daemon_reply() {
  local command="$1" install_kind="$2" helper_path="${3:-}"
  case "$command" in PING|COMPANIONCAPS|BUILDID|GUARDCAPS|GUARDSTATUS) ;; *) return 1 ;; esac
  if [ -z "$helper_path" ]; then
    case "$install_kind" in
      system|vendor|systemless) helper_path="$CANONICAL_HELPER_PATH" ;;
      *) return 1 ;;
    esac
  fi
  case "$helper_path" in
    /data/local/hapaneld-helper|/data/adb/hapaneld/.helper-manual-probe-[0-9a-f]*|/data/local/.hapaneld-helper-manual-probe-[0-9a-f]*) ;;
    *) return 1 ;;
  esac
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

renew_manual_lease() {
  local install_kind="$1" marker target_service renewed
  case "$install_kind" in
    system|vendor|systemless)
      marker="$MANUAL_V3_MARKER"
      target_service="${SERVICE_SHA256:-}"
      ;;
    *) return 1 ;;
  esac
  printf '%s\n' "$target_service" | grep -Eq '^[0-9a-f]{64}$' || return 1
  renewed="$(run_root_locked '
    marker='"$marker"'
    [ -f "$marker" ] || { echo LEASE_PENDING; exit 0; }
    [ ! -L "$marker" ] || exit 1
    owner=$(stat -c %u:%g "$marker" 2>/dev/null || toybox stat -c %u:%g "$marker" 2>/dev/null) || exit 1
    mode=$(stat -c %a "$marker" 2>/dev/null || toybox stat -c %a "$marker" 2>/dev/null) || exit 1
    [ "$owner" = 0:0 ] && [ "$mode" = 600 ] || exit 1
    exact_field() {
      name=$1; expected=$2
      [ "$(grep -c ^"$name"= "$marker")" = 1 ] && grep -qx "$name=$expected" "$marker"
    }
    exact_field JOURNAL_VERSION 3 || exit 1
    exact_field JOURNAL_SCOPE HELPER_ONLY || exit 1
    exact_field REGISTRATION_KIND '"$install_kind"' || exit 1
    exact_field TRANSACTION_ID '"$TRANSACTION_ID"' || exit 1
    exact_field TARGET_BUILD_ID '"$BUILD_ID"' || exit 1
    exact_field TARGET_HELPER_SHA256 '"$BIN_SHA256"' || exit 1
    exact_field TARGET_SERVICE_SHA256 '"$target_service"' || exit 1
    phase=$(sed -n s/^SWAP_PHASE=//p "$marker")
    case "$phase" in PREPARED|MUTATING|TARGET) ;; *) exit 1 ;; esac
    [ "$(grep -c ^SWAP_PHASE= "$marker")" = 1 ] || exit 1
    file_hash() {
      actual=$(sha256sum "$1" 2>/dev/null || toybox sha256sum "$1" 2>/dev/null) || return 1
      printf %s "${actual%% *}"
    }
    marker_before=$(file_hash "$marker") || exit 1
    current_boot=$(cat /proc/sys/kernel/random/boot_id 2>/dev/null) || exit 1
    current_uptime=$(cut -d. -f1 /proc/uptime 2>/dev/null) || exit 1
    echo "$current_uptime" | grep -Eq ^[0-9]+$ || exit 1
    lease_until=$((current_uptime + '"$MANUAL_LEASE_SECONDS"'))
    sed "/^LEASE_BOOT_ID=/d; /^LEASE_UNTIL_UPTIME=/d" "$marker" > "$marker.lease-new" || exit 1
    echo LEASE_BOOT_ID="$current_boot" >> "$marker.lease-new"
    echo LEASE_UNTIL_UPTIME="$lease_until" >> "$marker.lease-new"
    chown 0:0 "$marker.lease-new" || exit 1
    chmod 600 "$marker.lease-new" || exit 1
    sync || exit 1
    [ "$(file_hash "$marker")" = "$marker_before" ] || exit 1
    mv -f "$marker.lease-new" "$marker" || exit 1
    sync || exit 1
    exact_field JOURNAL_VERSION 3 && exact_field JOURNAL_SCOPE HELPER_ONLY &&
      exact_field REGISTRATION_KIND '"$install_kind"' &&
      exact_field TRANSACTION_ID '"$TRANSACTION_ID"' &&
      exact_field TARGET_BUILD_ID '"$BUILD_ID"' &&
      exact_field TARGET_HELPER_SHA256 '"$BIN_SHA256"' &&
      exact_field TARGET_SERVICE_SHA256 '"$target_service"' &&
      exact_field SWAP_PHASE "$phase" || exit 1
    echo LEASE_OK
  ' 2>&1)" || true
  [ "$renewed" = LEASE_OK ] || [ "$renewed" = LEASE_PENDING ] ||
    [ "$renewed" = TRANSACTION_BUSY ]
}

start_manual_lease_guard() {
  local install_kind="$1" owner_pid="$$"
  MANUAL_LEASE_GUARD_FAILURE="$(mktemp)"
  (
    sleep_pid=""
    cleanup_manual_lease_sleep() {
      [ -z "$sleep_pid" ] || kill "$sleep_pid" >/dev/null 2>&1 || true
      [ -z "$sleep_pid" ] || wait "$sleep_pid" >/dev/null 2>&1 || true
      sleep_pid=""
    }
    trap 'cleanup_manual_lease_sleep; exit 0' INT TERM
    trap cleanup_manual_lease_sleep EXIT
    while :; do
      sleep "$MANUAL_LEASE_RENEW_SECONDS" &
      sleep_pid=$!
      wait "$sleep_pid" || exit 0
      sleep_pid=""
      kill -0 "$owner_pid" >/dev/null 2>&1 || exit 0
      renew_manual_lease "$install_kind" || {
        printf 'failed\n' > "$MANUAL_LEASE_GUARD_FAILURE"
        exit 1
      }
    done
  ) &
  MANUAL_LEASE_GUARD_PID=$!
}

manual_lease_guard_succeeded() {
  [ -z "${MANUAL_LEASE_GUARD_FAILURE:-}" ] || [ ! -s "$MANUAL_LEASE_GUARD_FAILURE" ]
}

rollback_root_helper() {
  local install_kind="$1" transaction_id="${2:-$TRANSACTION_ID}" target_build="${3:-$BUILD_ID}"
  local target_helper="${4:-$BIN_SHA256}" target_service="${5:-}" restored probe_path
  if [ -z "$target_service" ]; then
    case "$install_kind" in
      system) target_service="$RC_SHA256" ;;
      systemless) target_service="${SVC_SHA256:-}" ;;
    esac
  fi
  if ! printf '%s\n' "$target_service" | grep -Eq '^[0-9a-f]{64}$'; then
    [ "$transaction_id" = legacy ] && [ "$target_service" = - ] || return 1
  fi
  stop_manual_lease_guard
  # The probe client belongs to this invocation, not to the recovered journal. This keeps legacy V1
  # recovery compatible while the journal's own transaction identity still gates rollback content.
  probe_path="/data/adb/hapaneld/.helper-manual-probe-$TRANSACTION_ID"
  case "$install_kind" in
    system)
      restored="$(run_root_locked '
        mount -o rw,remount / 2>/dev/null; mount -o rw,remount /system 2>/dev/null
        [ -f /system/bin/.hapaneld-helper-manual-upgrade ] || { echo ROLLBACK_UNNEEDED; exit 0; }
        grep -q ^OLD_BIN=0$ /system/bin/.hapaneld-helper-manual-upgrade || [ -f /system/bin/hapaneld-helper.hapaneld-manual-recovery ] || exit 1
        grep -q ^OLD_SERVICE=0$ /system/bin/.hapaneld-helper-manual-upgrade || [ -f /system/etc/init/hapaneld-helper.rc.hapaneld-manual-recovery ] || exit 1
        grep -q ^LEGACY_BIN=0$ /system/bin/.hapaneld-helper-manual-upgrade || [ -f /system/bin/hapaneld-ledd.hapaneld-manual-recovery ] || exit 1
        grep -q ^LEGACY_SERVICE=0$ /system/bin/.hapaneld-helper-manual-upgrade || [ -f /system/etc/init/hapaneld-ledd.rc.hapaneld-manual-recovery ] || exit 1
        grep -q ^ALT_BIN=0$ /system/bin/.hapaneld-helper-manual-upgrade || [ -f /data/adb/hapaneld/hapaneld-helper.hapaneld-manual-recovery ] || exit 1
        grep -q ^ALT_SERVICE=0$ /system/bin/.hapaneld-helper-manual-upgrade || [ -f /data/adb/service.d/hapaneld-helper.sh.hapaneld-manual-recovery ] || exit 1
        if grep -q ^JOURNAL_VERSION=2$ /system/bin/.hapaneld-helper-manual-upgrade; then
          grep -qx TRANSACTION_ID='"$transaction_id"' /system/bin/.hapaneld-helper-manual-upgrade || exit 1
          grep -qx TARGET_BUILD_ID='"$target_build"' /system/bin/.hapaneld-helper-manual-upgrade || exit 1
          grep -qx TARGET_HELPER_SHA256='"$target_helper"' /system/bin/.hapaneld-helper-manual-upgrade || exit 1
          grep -qx TARGET_SERVICE_SHA256='"$target_service"' /system/bin/.hapaneld-helper-manual-upgrade || exit 1
        else
          [ '"$transaction_id"' = legacy ] || exit 1
          grep -q ^JOURNAL_VERSION=1$ /system/bin/.hapaneld-helper-manual-upgrade || exit 1
        fi
        grep -q ^JOURNAL_SCOPE=HELPER_ONLY$ /system/bin/.hapaneld-helper-manual-upgrade || exit 1
        marker=/system/bin/.hapaneld-helper-manual-upgrade
        transaction_id='"$transaction_id"'
        target_registration='"$target_service"'
        [ "$transaction_id" != legacy ] || target_registration='"$LEGACY_V1_SYSTEM_RC_SHA256"'
        live_recorded() {
          name=$1; live=$2
          if grep -q ^"$name"=1$ "$marker"; then
            expected=$(sed -n s/^"$name"_SHA256=//p "$marker")
            actual=$(sha256sum "$live" 2>/dev/null || toybox sha256sum "$live" 2>/dev/null) || return 1
            [ "${actual%% *}" = "$expected" ]
          else
            [ ! -e "$live" ] && [ ! -L "$live" ]
          fi
        }
        hash_is() {
          expected=$1; hash_path=$2
          actual=$(sha256sum "$hash_path" 2>/dev/null || toybox sha256sum "$hash_path" 2>/dev/null) || return 1
          [ "${actual%% *}" = "$expected" ]
        }
        owner_mode() {
          owner=$(stat -c %u:%g "$1" 2>/dev/null || toybox stat -c %u:%g "$1" 2>/dev/null) || return 1
          mode=$(stat -c %a "$1" 2>/dev/null || toybox stat -c %a "$1" 2>/dev/null) || return 1
          [ "$owner" = 0:0 ] && [ "$mode" = "$2" ]
        }
        sync_path() {
          sync "$1" 2>/dev/null || sync
        }
        publish_rollback_phase() {
          phase_tmp="$marker".hapaneld-manual-"$transaction_id".phase
          rm -f "$phase_tmp" || return 1
          sed "/^ROLLBACK_PHASE=/d" "$marker" > "$phase_tmp" || return 1
          echo ROLLBACK_PHASE=PUBLISHING >> "$phase_tmp"
          chown 0:0 "$phase_tmp" || return 1
          chmod 600 "$phase_tmp" || return 1
          sync_path "$phase_tmp" || return 1
          mv -f "$phase_tmp" "$marker" || return 1
          sync_path "${marker%/*}" || return 1
          [ "$(grep -c ^ROLLBACK_PHASE=PUBLISHING$ "$marker")" = 1 ]
        }
        publish_recorded() {
          name=$1; recovery=$2; live=$3; mode=$4
          expected=$(sed -n s/^"$name"_SHA256=//p "$marker")
          temporary="$live".hapaneld-manual-"$transaction_id".restore
          rm -f "$temporary" || return 1
          cp -p "$recovery" "$temporary" || return 1
          chown 0:0 "$temporary" || return 1
          chmod "$mode" "$temporary" || return 1
          hash_is "$expected" "$temporary" && owner_mode "$temporary" "$mode" || return 1
          sync_path "$temporary" || return 1
          mv -f "$temporary" "$live" || return 1
          sync_path "${live%/*}" || return 1
          hash_is "$expected" "$live" && owner_mode "$live" "$mode"
        }
        publish_absent() {
          live=$1
          temporary="$live".hapaneld-manual-"$transaction_id".restore
          rm -f "$temporary" "$live" || return 1
          sync_path "${live%/*}" || return 1
          [ ! -e "$live" ] && [ ! -L "$live" ]
        }
        recorded_exact() {
          name=$1; live=$2; mode=$3
          live_recorded "$name" "$live" || return 1
          grep -q ^"$name"=0$ "$marker" || owner_mode "$live" "$mode"
        }
        wait_for_helper_retirement() {
          attempt=0
          while [ "$attempt" -lt 10 ]; do
            if ! pidof hapaneld-helper >/dev/null 2>&1 && ! pidof hapaneld-ledd >/dev/null 2>&1; then
              return 0
            fi
            attempt=$((attempt + 1))
            sleep 1
          done
          return 1
        }
        all_live_recorded() {
          live_recorded OLD_BIN /system/bin/hapaneld-helper &&
          live_recorded OLD_SERVICE /system/etc/init/hapaneld-helper.rc &&
          live_recorded LEGACY_BIN /system/bin/hapaneld-ledd &&
          live_recorded LEGACY_SERVICE /system/etc/init/hapaneld-ledd.rc &&
          live_recorded ALT_BIN /data/adb/hapaneld/hapaneld-helper &&
          live_recorded ALT_SERVICE /data/adb/service.d/hapaneld-helper.sh
        }
        target_live() {
          hash_is '"$target_helper"' /system/bin/hapaneld-helper &&
          hash_is "$target_registration" /system/etc/init/hapaneld-helper.rc &&
          [ ! -e /system/bin/hapaneld-ledd ] && [ ! -L /system/bin/hapaneld-ledd ] &&
          [ ! -e /system/etc/init/hapaneld-ledd.rc ] && [ ! -L /system/etc/init/hapaneld-ledd.rc ] &&
          [ ! -e /data/adb/hapaneld/hapaneld-helper ] && [ ! -L /data/adb/hapaneld/hapaneld-helper ] &&
          [ ! -e /data/adb/service.d/hapaneld-helper.sh ] && [ ! -L /data/adb/service.d/hapaneld-helper.sh ]
        }
        rollback_live_known() {
          { live_recorded OLD_BIN /system/bin/hapaneld-helper ||
            hash_is '"$target_helper"' /system/bin/hapaneld-helper; } &&
          { live_recorded OLD_SERVICE /system/etc/init/hapaneld-helper.rc ||
            hash_is "$target_registration" /system/etc/init/hapaneld-helper.rc; } &&
          { live_recorded LEGACY_BIN /system/bin/hapaneld-ledd ||
            { [ ! -e /system/bin/hapaneld-ledd ] && [ ! -L /system/bin/hapaneld-ledd ]; }; } &&
          { live_recorded LEGACY_SERVICE /system/etc/init/hapaneld-ledd.rc ||
            { [ ! -e /system/etc/init/hapaneld-ledd.rc ] && [ ! -L /system/etc/init/hapaneld-ledd.rc ]; }; } &&
          { live_recorded ALT_BIN /data/adb/hapaneld/hapaneld-helper ||
            { [ ! -e /data/adb/hapaneld/hapaneld-helper ] && [ ! -L /data/adb/hapaneld/hapaneld-helper ]; }; } &&
          { live_recorded ALT_SERVICE /data/adb/service.d/hapaneld-helper.sh ||
            { [ ! -e /data/adb/service.d/hapaneld-helper.sh ] && [ ! -L /data/adb/service.d/hapaneld-helper.sh ]; }; }
        }
        rollback_phase=$(sed -n s/^ROLLBACK_PHASE=//p "$marker")
        case "$rollback_phase" in
          "") all_live_recorded || target_live || { echo ROLLBACK_UNKNOWN; exit 0; }; begin_rollback=1 ;;
          PUBLISHING) rollback_live_known || { echo ROLLBACK_UNKNOWN; exit 0; }; begin_rollback=0 ;;
          *) echo ROLLBACK_UNKNOWN; exit 0 ;;
        esac
        if grep -q ^OLD_BIN=1$ /system/bin/.hapaneld-helper-manual-upgrade; then
          expected=$(sed -n s/^OLD_BIN_SHA256=//p /system/bin/.hapaneld-helper-manual-upgrade)
          actual=$(sha256sum /system/bin/hapaneld-helper.hapaneld-manual-recovery 2>/dev/null || toybox sha256sum /system/bin/hapaneld-helper.hapaneld-manual-recovery 2>/dev/null) || exit 1
          [ "${actual%% *}" = "$expected" ] || exit 1
        fi
        if grep -q ^OLD_SERVICE=1$ /system/bin/.hapaneld-helper-manual-upgrade; then
          expected=$(sed -n s/^OLD_SERVICE_SHA256=//p /system/bin/.hapaneld-helper-manual-upgrade)
          actual=$(sha256sum /system/etc/init/hapaneld-helper.rc.hapaneld-manual-recovery 2>/dev/null || toybox sha256sum /system/etc/init/hapaneld-helper.rc.hapaneld-manual-recovery 2>/dev/null) || exit 1
          [ "${actual%% *}" = "$expected" ] || exit 1
        fi
        if grep -q ^LEGACY_BIN=1$ /system/bin/.hapaneld-helper-manual-upgrade; then
          expected=$(sed -n s/^LEGACY_BIN_SHA256=//p /system/bin/.hapaneld-helper-manual-upgrade)
          actual=$(sha256sum /system/bin/hapaneld-ledd.hapaneld-manual-recovery 2>/dev/null || toybox sha256sum /system/bin/hapaneld-ledd.hapaneld-manual-recovery 2>/dev/null) || exit 1
          [ "${actual%% *}" = "$expected" ] || exit 1
        fi
        if grep -q ^LEGACY_SERVICE=1$ /system/bin/.hapaneld-helper-manual-upgrade; then
          expected=$(sed -n s/^LEGACY_SERVICE_SHA256=//p /system/bin/.hapaneld-helper-manual-upgrade)
          actual=$(sha256sum /system/etc/init/hapaneld-ledd.rc.hapaneld-manual-recovery 2>/dev/null || toybox sha256sum /system/etc/init/hapaneld-ledd.rc.hapaneld-manual-recovery 2>/dev/null) || exit 1
          [ "${actual%% *}" = "$expected" ] || exit 1
        fi
        if grep -q ^ALT_BIN=1$ /system/bin/.hapaneld-helper-manual-upgrade; then
          expected=$(sed -n s/^ALT_BIN_SHA256=//p /system/bin/.hapaneld-helper-manual-upgrade)
          actual=$(sha256sum /data/adb/hapaneld/hapaneld-helper.hapaneld-manual-recovery 2>/dev/null || toybox sha256sum /data/adb/hapaneld/hapaneld-helper.hapaneld-manual-recovery 2>/dev/null) || exit 1
          [ "${actual%% *}" = "$expected" ] || exit 1
        fi
        if grep -q ^ALT_SERVICE=1$ /system/bin/.hapaneld-helper-manual-upgrade; then
          expected=$(sed -n s/^ALT_SERVICE_SHA256=//p /system/bin/.hapaneld-helper-manual-upgrade)
          actual=$(sha256sum /data/adb/service.d/hapaneld-helper.sh.hapaneld-manual-recovery 2>/dev/null || toybox sha256sum /data/adb/service.d/hapaneld-helper.sh.hapaneld-manual-recovery 2>/dev/null) || exit 1
          [ "${actual%% *}" = "$expected" ] || exit 1
        fi
        [ "$begin_rollback" = 0 ] || publish_rollback_phase || exit 1
        mkdir -p /data/adb/hapaneld || exit 1
        chown 0:0 /data/adb/hapaneld || exit 1
        chmod 700 /data/adb/hapaneld || exit 1
        rm -f '"$probe_path"'
        ( sha256sum '"$PROBE_STAGING_PATH"' 2>/dev/null || toybox sha256sum '"$PROBE_STAGING_PATH"' 2>/dev/null ) | grep -q ^'"$BIN_SHA256"' || exit 1
        cp '"$PROBE_STAGING_PATH"' '"$probe_path"' || exit 1
        chown 0:0 '"$probe_path"'; chmod 700 '"$probe_path"'
        ( sha256sum '"$probe_path"' 2>/dev/null || toybox sha256sum '"$probe_path"' 2>/dev/null ) | grep -q ^'"$BIN_SHA256"' || exit 1
        stop hapaneld_helper 2>/dev/null
        stop hapaneld_ledd 2>/dev/null
        pkill -x hapaneld-helper 2>/dev/null
        pkill -x hapaneld-ledd 2>/dev/null
        wait_for_helper_retirement || exit 1
        if grep -q ^OLD_BIN=1$ /system/bin/.hapaneld-helper-manual-upgrade; then
          publish_recorded OLD_BIN /system/bin/hapaneld-helper.hapaneld-manual-recovery /system/bin/hapaneld-helper 755 || exit 1
        else
          publish_absent /system/bin/hapaneld-helper || exit 1
        fi
        if grep -q ^OLD_SERVICE=1$ /system/bin/.hapaneld-helper-manual-upgrade; then
          publish_recorded OLD_SERVICE /system/etc/init/hapaneld-helper.rc.hapaneld-manual-recovery /system/etc/init/hapaneld-helper.rc 644 || exit 1
        else
          publish_absent /system/etc/init/hapaneld-helper.rc || exit 1
        fi
        if grep -q ^LEGACY_BIN=1$ /system/bin/.hapaneld-helper-manual-upgrade; then
          publish_recorded LEGACY_BIN /system/bin/hapaneld-ledd.hapaneld-manual-recovery /system/bin/hapaneld-ledd 755 || exit 1
        else
          publish_absent /system/bin/hapaneld-ledd || exit 1
        fi
        if grep -q ^LEGACY_SERVICE=1$ /system/bin/.hapaneld-helper-manual-upgrade; then
          publish_recorded LEGACY_SERVICE /system/etc/init/hapaneld-ledd.rc.hapaneld-manual-recovery /system/etc/init/hapaneld-ledd.rc 644 || exit 1
        else
          publish_absent /system/etc/init/hapaneld-ledd.rc || exit 1
        fi
        if grep -q ^ALT_BIN=1$ /system/bin/.hapaneld-helper-manual-upgrade; then
          publish_recorded ALT_BIN /data/adb/hapaneld/hapaneld-helper.hapaneld-manual-recovery /data/adb/hapaneld/hapaneld-helper 755 || exit 1
        else
          publish_absent /data/adb/hapaneld/hapaneld-helper || exit 1
        fi
        if grep -q ^ALT_SERVICE=1$ /system/bin/.hapaneld-helper-manual-upgrade; then
          publish_recorded ALT_SERVICE /data/adb/service.d/hapaneld-helper.sh.hapaneld-manual-recovery /data/adb/service.d/hapaneld-helper.sh 755 || exit 1
        else
          publish_absent /data/adb/service.d/hapaneld-helper.sh || exit 1
        fi
        sync || exit 1
        recorded_exact OLD_BIN /system/bin/hapaneld-helper 755 &&
          recorded_exact OLD_SERVICE /system/etc/init/hapaneld-helper.rc 644 &&
          recorded_exact LEGACY_BIN /system/bin/hapaneld-ledd 755 &&
          recorded_exact LEGACY_SERVICE /system/etc/init/hapaneld-ledd.rc 644 &&
          recorded_exact ALT_BIN /data/adb/hapaneld/hapaneld-helper 755 &&
          recorded_exact ALT_SERVICE /data/adb/service.d/hapaneld-helper.sh 755 || exit 1
        if [ -f /system/bin/hapaneld-helper.hapaneld-manual-recovery ]; then
          start hapaneld_helper 2>/dev/null
          /system/bin/hapaneld-helper --request PING >/dev/null 2>&1 ||
            ( /system/bin/hapaneld-helper >/dev/null 2>&1 & )
          echo ROLLBACK_RESTARTED
        elif [ -f /data/adb/hapaneld/hapaneld-helper.hapaneld-manual-recovery ]; then
          /data/adb/hapaneld/hapaneld-helper >/dev/null 2>&1 &
          echo ROLLBACK_RESTARTED
        elif [ -f /system/bin/hapaneld-ledd.hapaneld-manual-recovery ]; then
          start hapaneld_ledd 2>/dev/null || ( /system/bin/hapaneld-ledd >/dev/null 2>&1 & )
          echo ROLLBACK_LEGACY
        else
          echo ROLLBACK_EMPTY
        fi
      ' 2>&1)" || true
      ;;
    systemless)
      restored="$(run_root_locked '
        [ -f /data/adb/hapaneld/.helper-manual-upgrade.marker ] || { echo ROLLBACK_UNNEEDED; exit 0; }
        grep -q ^OLD_BIN=0$ /data/adb/hapaneld/.helper-manual-upgrade.marker || [ -f /data/adb/hapaneld/hapaneld-helper.hapaneld-manual-recovery ] || exit 1
        grep -q ^OLD_SERVICE=0$ /data/adb/hapaneld/.helper-manual-upgrade.marker || [ -f /data/adb/service.d/hapaneld-helper.sh.hapaneld-manual-recovery ] || exit 1
        if grep -q ^JOURNAL_VERSION=2$ /data/adb/hapaneld/.helper-manual-upgrade.marker; then
          grep -qx TRANSACTION_ID='"$transaction_id"' /data/adb/hapaneld/.helper-manual-upgrade.marker || exit 1
          grep -qx TARGET_BUILD_ID='"$target_build"' /data/adb/hapaneld/.helper-manual-upgrade.marker || exit 1
          grep -qx TARGET_HELPER_SHA256='"$target_helper"' /data/adb/hapaneld/.helper-manual-upgrade.marker || exit 1
          grep -qx TARGET_SERVICE_SHA256='"$target_service"' /data/adb/hapaneld/.helper-manual-upgrade.marker || exit 1
        else
          [ '"$transaction_id"' = legacy ] || exit 1
          grep -q ^JOURNAL_VERSION=1$ /data/adb/hapaneld/.helper-manual-upgrade.marker || exit 1
        fi
        grep -q ^JOURNAL_SCOPE=HELPER_ONLY$ /data/adb/hapaneld/.helper-manual-upgrade.marker || exit 1
        marker=/data/adb/hapaneld/.helper-manual-upgrade.marker
        transaction_id='"$transaction_id"'
        target_registration='"$target_service"'
        [ "$transaction_id" != legacy ] || target_registration='"$LEGACY_V1_SYSTEMLESS_SERVICE_SHA256"'
        live_recorded() {
          name=$1; live=$2
          if grep -q ^"$name"=1$ "$marker"; then
            expected=$(sed -n s/^"$name"_SHA256=//p "$marker")
            actual=$(sha256sum "$live" 2>/dev/null || toybox sha256sum "$live" 2>/dev/null) || return 1
            [ "${actual%% *}" = "$expected" ]
          else
            [ ! -e "$live" ] && [ ! -L "$live" ]
          fi
        }
        wait_for_helper_retirement() {
          attempt=0
          while [ "$attempt" -lt 10 ]; do
            if ! pidof hapaneld-helper >/dev/null 2>&1 && ! pidof hapaneld-ledd >/dev/null 2>&1; then
              return 0
            fi
            attempt=$((attempt + 1))
            sleep 1
          done
          return 1
        }
        hash_is() {
          expected=$1; hash_path=$2
          actual=$(sha256sum "$hash_path" 2>/dev/null || toybox sha256sum "$hash_path" 2>/dev/null) || return 1
          [ "${actual%% *}" = "$expected" ]
        }
        owner_mode() {
          owner=$(stat -c %u:%g "$1" 2>/dev/null || toybox stat -c %u:%g "$1" 2>/dev/null) || return 1
          mode=$(stat -c %a "$1" 2>/dev/null || toybox stat -c %a "$1" 2>/dev/null) || return 1
          [ "$owner" = 0:0 ] && [ "$mode" = "$2" ]
        }
        sync_path() {
          sync "$1" 2>/dev/null || sync
        }
        publish_recorded() {
          name=$1; recovery=$2; live=$3; mode=$4
          expected=$(sed -n s/^"$name"_SHA256=//p "$marker")
          temporary="$live".hapaneld-manual-"$transaction_id".restore
          rm -f "$temporary" || return 1
          cp -p "$recovery" "$temporary" || return 1
          chown 0:0 "$temporary" || return 1
          chmod "$mode" "$temporary" || return 1
          hash_is "$expected" "$temporary" && owner_mode "$temporary" "$mode" || return 1
          sync_path "$temporary" || return 1
          mv -f "$temporary" "$live" || return 1
          sync_path "${live%/*}" || return 1
          hash_is "$expected" "$live" && owner_mode "$live" "$mode"
        }
        publish_absent() {
          live=$1
          temporary="$live".hapaneld-manual-"$transaction_id".restore
          rm -f "$temporary" "$live" || return 1
          sync_path "${live%/*}" || return 1
          [ ! -e "$live" ] && [ ! -L "$live" ]
        }
        recorded_exact() {
          name=$1; live=$2; mode=$3
          live_recorded "$name" "$live" || return 1
          grep -q ^"$name"=0$ "$marker" || owner_mode "$live" "$mode"
        }
        publish_rollback_phase() {
          phase_tmp="$marker".hapaneld-manual-"$transaction_id".phase
          rm -f "$phase_tmp" || return 1
          sed "/^ROLLBACK_PHASE=/d" "$marker" > "$phase_tmp" || return 1
          echo ROLLBACK_PHASE=PUBLISHING >> "$phase_tmp"
          chown 0:0 "$phase_tmp" || return 1
          chmod 600 "$phase_tmp" || return 1
          sync_path "$phase_tmp" || return 1
          mv -f "$phase_tmp" "$marker" || return 1
          sync_path "${marker%/*}" || return 1
          [ "$(grep -c ^ROLLBACK_PHASE=PUBLISHING$ "$marker")" = 1 ]
        }
        all_live_recorded() {
          live_recorded OLD_BIN /data/adb/hapaneld/hapaneld-helper &&
          live_recorded OLD_SERVICE /data/adb/service.d/hapaneld-helper.sh
        }
        target_live() {
          hash_is '"$target_helper"' /data/adb/hapaneld/hapaneld-helper &&
          hash_is "$target_registration" /data/adb/service.d/hapaneld-helper.sh
        }
        rollback_live_known() {
          { live_recorded OLD_BIN /data/adb/hapaneld/hapaneld-helper ||
            hash_is '"$target_helper"' /data/adb/hapaneld/hapaneld-helper; } &&
          { live_recorded OLD_SERVICE /data/adb/service.d/hapaneld-helper.sh ||
            hash_is "$target_registration" /data/adb/service.d/hapaneld-helper.sh; }
        }
        rollback_phase=$(sed -n s/^ROLLBACK_PHASE=//p "$marker")
        case "$rollback_phase" in
          "") all_live_recorded || target_live || { echo ROLLBACK_UNKNOWN; exit 0; }; begin_rollback=1 ;;
          PUBLISHING) rollback_live_known || { echo ROLLBACK_UNKNOWN; exit 0; }; begin_rollback=0 ;;
          *) echo ROLLBACK_UNKNOWN; exit 0 ;;
        esac
        if grep -q ^OLD_BIN=1$ /data/adb/hapaneld/.helper-manual-upgrade.marker; then
          expected=$(sed -n s/^OLD_BIN_SHA256=//p /data/adb/hapaneld/.helper-manual-upgrade.marker)
          actual=$(sha256sum /data/adb/hapaneld/hapaneld-helper.hapaneld-manual-recovery 2>/dev/null || toybox sha256sum /data/adb/hapaneld/hapaneld-helper.hapaneld-manual-recovery 2>/dev/null) || exit 1
          [ "${actual%% *}" = "$expected" ] || exit 1
        fi
        if grep -q ^OLD_SERVICE=1$ /data/adb/hapaneld/.helper-manual-upgrade.marker; then
          expected=$(sed -n s/^OLD_SERVICE_SHA256=//p /data/adb/hapaneld/.helper-manual-upgrade.marker)
          actual=$(sha256sum /data/adb/service.d/hapaneld-helper.sh.hapaneld-manual-recovery 2>/dev/null || toybox sha256sum /data/adb/service.d/hapaneld-helper.sh.hapaneld-manual-recovery 2>/dev/null) || exit 1
          [ "${actual%% *}" = "$expected" ] || exit 1
        fi
        [ "$begin_rollback" = 0 ] || publish_rollback_phase || exit 1
        mkdir -p /data/adb/hapaneld || exit 1
        chown 0:0 /data/adb/hapaneld || exit 1
        chmod 700 /data/adb/hapaneld || exit 1
        rm -f '"$probe_path"'
        ( sha256sum '"$PROBE_STAGING_PATH"' 2>/dev/null || toybox sha256sum '"$PROBE_STAGING_PATH"' 2>/dev/null ) | grep -q ^'"$BIN_SHA256"' || exit 1
        cp '"$PROBE_STAGING_PATH"' '"$probe_path"' || exit 1
        chown 0:0 '"$probe_path"'; chmod 700 '"$probe_path"'
        ( sha256sum '"$probe_path"' 2>/dev/null || toybox sha256sum '"$probe_path"' 2>/dev/null ) | grep -q ^'"$BIN_SHA256"' || exit 1
        stop hapaneld_helper 2>/dev/null
        pkill -x hapaneld-helper 2>/dev/null
        pkill -x hapaneld-ledd 2>/dev/null
        wait_for_helper_retirement || exit 1
        if grep -q ^OLD_BIN=1$ /data/adb/hapaneld/.helper-manual-upgrade.marker; then
          publish_recorded OLD_BIN /data/adb/hapaneld/hapaneld-helper.hapaneld-manual-recovery /data/adb/hapaneld/hapaneld-helper 755 || exit 1
        else
          publish_absent /data/adb/hapaneld/hapaneld-helper || exit 1
        fi
        if grep -q ^OLD_SERVICE=1$ /data/adb/hapaneld/.helper-manual-upgrade.marker; then
          publish_recorded OLD_SERVICE /data/adb/service.d/hapaneld-helper.sh.hapaneld-manual-recovery /data/adb/service.d/hapaneld-helper.sh 755 || exit 1
        else
          publish_absent /data/adb/service.d/hapaneld-helper.sh || exit 1
        fi
        sync || exit 1
        recorded_exact OLD_BIN /data/adb/hapaneld/hapaneld-helper 755 &&
          recorded_exact OLD_SERVICE /data/adb/service.d/hapaneld-helper.sh 755 || exit 1
        if [ -f /data/adb/hapaneld/hapaneld-helper.hapaneld-manual-recovery ]; then
          /data/adb/hapaneld/hapaneld-helper >/dev/null 2>&1 &
          echo ROLLBACK_RESTARTED
        elif [ -x /system/bin/hapaneld-helper ]; then
          start hapaneld_helper 2>/dev/null
          /system/bin/hapaneld-helper --request PING >/dev/null 2>&1 ||
            ( /system/bin/hapaneld-helper >/dev/null 2>&1 & )
          echo ROLLBACK_RESTARTED
        elif [ -x /system/bin/hapaneld-ledd ]; then
          start hapaneld_ledd 2>/dev/null || ( /system/bin/hapaneld-ledd >/dev/null 2>&1 & )
          echo ROLLBACK_LEGACY
        else
          echo ROLLBACK_EMPTY
        fi
      ' 2>&1)" || true
      ;;
    *) return 1 ;;
  esac
  if printf '%s\n' "$restored" | grep -qx ROLLBACK_RESTARTED; then
    if wait_for_helper_reply PING OK "$install_kind" "$probe_path"; then
      run_root 'rm -f '"$probe_path" >/dev/null 2>&1 || true
      finalize_root_helper_rollback "$install_kind" "$transaction_id" "$target_build" "$target_helper" "$target_service"
      return $?
    fi
    run_root 'rm -f '"$probe_path" >/dev/null 2>&1 || true
    return 1
  else
    run_root 'rm -f '"$probe_path" >/dev/null 2>&1 || true
    if printf '%s\n' "$restored" | grep -Eqx 'ROLLBACK_(EMPTY|LEGACY)'; then
      finalize_root_helper_rollback "$install_kind" "$transaction_id" "$target_build" "$target_helper" "$target_service"
    else
      printf '%s\n' "$restored" | grep -qx ROLLBACK_UNNEEDED
    fi
  fi
}

finalize_root_helper_rollback() {
  local install_kind="$1" transaction_id="$2" target_build="$3" target_helper="$4" target_service="$5" finalized
  case "$install_kind" in
    system)
      finalized="$(run_root_locked '
        marker=/system/bin/.hapaneld-helper-manual-upgrade
        mount -o rw,remount / 2>/dev/null; mount -o rw,remount /system 2>/dev/null
        [ -f "$marker" ] || exit 1
        if grep -q ^JOURNAL_VERSION=2$ "$marker"; then
          grep -qx TRANSACTION_ID='"$transaction_id"' "$marker" || exit 1
          grep -qx TARGET_BUILD_ID='"$target_build"' "$marker" || exit 1
          grep -qx TARGET_HELPER_SHA256='"$target_helper"' "$marker" || exit 1
          grep -qx TARGET_SERVICE_SHA256='"$target_service"' "$marker" || exit 1
        else
          [ '"$transaction_id"' = legacy ] || exit 1
          grep -q ^JOURNAL_VERSION=1$ "$marker" || exit 1
        fi
        grep -q ^JOURNAL_SCOPE=HELPER_ONLY$ "$marker" || exit 1
        live_recorded() {
          name=$1; live=$2
          if grep -q ^"$name"=1$ "$marker"; then
            expected=$(sed -n s/^"$name"_SHA256=//p "$marker")
            actual=$(sha256sum "$live" 2>/dev/null || toybox sha256sum "$live" 2>/dev/null) || return 1
            [ "${actual%% *}" = "$expected" ]
          else
            [ ! -e "$live" ] && [ ! -L "$live" ]
          fi
        }
        live_recorded OLD_BIN /system/bin/hapaneld-helper &&
          live_recorded OLD_SERVICE /system/etc/init/hapaneld-helper.rc &&
          live_recorded LEGACY_BIN /system/bin/hapaneld-ledd &&
          live_recorded LEGACY_SERVICE /system/etc/init/hapaneld-ledd.rc &&
          live_recorded ALT_BIN /data/adb/hapaneld/hapaneld-helper &&
          live_recorded ALT_SERVICE /data/adb/service.d/hapaneld-helper.sh || exit 1
        rm -f "$marker" || exit 1
        sync || exit 1
        rm -f /system/bin/hapaneld-helper.hapaneld-manual-recovery \
          /system/etc/init/hapaneld-helper.rc.hapaneld-manual-recovery \
          /system/bin/hapaneld-ledd.hapaneld-manual-recovery \
          /system/etc/init/hapaneld-ledd.rc.hapaneld-manual-recovery \
          /data/adb/hapaneld/hapaneld-helper.hapaneld-manual-recovery \
          /data/adb/service.d/hapaneld-helper.sh.hapaneld-manual-recovery 2>/dev/null || true
        sync 2>/dev/null || true
        echo ROLLBACK_FINALIZED
      ' 2>&1)" || true
      ;;
    systemless)
      finalized="$(run_root_locked '
        marker=/data/adb/hapaneld/.helper-manual-upgrade.marker
        [ -f "$marker" ] || exit 1
        if grep -q ^JOURNAL_VERSION=2$ "$marker"; then
          grep -qx TRANSACTION_ID='"$transaction_id"' "$marker" || exit 1
          grep -qx TARGET_BUILD_ID='"$target_build"' "$marker" || exit 1
          grep -qx TARGET_HELPER_SHA256='"$target_helper"' "$marker" || exit 1
          grep -qx TARGET_SERVICE_SHA256='"$target_service"' "$marker" || exit 1
        else
          [ '"$transaction_id"' = legacy ] || exit 1
          grep -q ^JOURNAL_VERSION=1$ "$marker" || exit 1
        fi
        grep -q ^JOURNAL_SCOPE=HELPER_ONLY$ "$marker" || exit 1
        live_recorded() {
          name=$1; live=$2
          if grep -q ^"$name"=1$ "$marker"; then
            expected=$(sed -n s/^"$name"_SHA256=//p "$marker")
            actual=$(sha256sum "$live" 2>/dev/null || toybox sha256sum "$live" 2>/dev/null) || return 1
            [ "${actual%% *}" = "$expected" ]
          else
            [ ! -e "$live" ] && [ ! -L "$live" ]
          fi
        }
        live_recorded OLD_BIN /data/adb/hapaneld/hapaneld-helper &&
          live_recorded OLD_SERVICE /data/adb/service.d/hapaneld-helper.sh || exit 1
        rm -f "$marker" || exit 1
        sync || exit 1
        rm -f /data/adb/hapaneld/hapaneld-helper.hapaneld-manual-recovery \
          /data/adb/service.d/hapaneld-helper.sh.hapaneld-manual-recovery 2>/dev/null || true
        sync 2>/dev/null || true
        echo ROLLBACK_FINALIZED
      ' 2>&1)" || true
      ;;
    *) return 1 ;;
  esac
  printf '%s\n' "$finalized" | grep -qx ROLLBACK_FINALIZED
}

# Journal v3 is deliberately separate from the historical v1/v2 rollback above. The old formats
# describe binaries living under /system or /data/adb; v3 describes one canonical /data/local
# binary plus exactly one boot registration. Never feed an old marker through this classifier.
rollback_root_helper_v3() {
  local install_kind="$1" transaction_id="$2" target_build="$3"
  local target_helper="$4" target_service="$5" restored probe_path
  probe_path="/data/local/.hapaneld-helper-manual-probe-$TRANSACTION_ID"
  restored="$(run_root_locked '
    marker='"$MANUAL_V3_MARKER"'
    transaction_id='"$transaction_id"'
    case "$transaction_id" in *[!0-9a-f]*|"") exit 1 ;; esac
    [ "${#transaction_id}" -eq 32 ] || exit 1
    if [ ! -f "$marker" ]; then
      rm -f /data/local/.hapaneld-helper.manual-"$transaction_id" \
        /data/local/.hapaneld-helper-manual-"$transaction_id".recovery-* \
        /system/etc/init/hapaneld-helper.rc.manual-"$transaction_id" \
        /vendor/etc/init/hapaneld-helper.rc.manual-"$transaction_id" \
        /data/adb/service.d/hapaneld-helper.sh.manual-"$transaction_id" 2>/dev/null || true
      echo ROLLBACK_UNNEEDED
      exit 0
    fi
    grep -q ^JOURNAL_VERSION=3$ "$marker" || exit 1
    grep -q ^JOURNAL_SCOPE=HELPER_ONLY$ "$marker" || exit 1
    grep -qx REGISTRATION_KIND='"$install_kind"' "$marker" || exit 1
    grep -qx TRANSACTION_ID='"$transaction_id"' "$marker" || exit 1
    grep -qx TARGET_BUILD_ID='"$target_build"' "$marker" || exit 1
    grep -qx TARGET_HELPER_SHA256='"$target_helper"' "$marker" || exit 1
    grep -qx TARGET_SERVICE_SHA256='"$target_service"' "$marker" || exit 1
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
    if app_replacement_custody_present; then
      echo ROLLBACK_REPLACEMENT_AUTHORITY_ACTIVE
      exit 0
    fi

    file_hash() {
      actual=$(sha256sum "$1" 2>/dev/null || toybox sha256sum "$1" 2>/dev/null) || return 1
      printf %s "${actual%% *}"
    }
    owner_mode() {
      owner=$(stat -c %u:%g "$1" 2>/dev/null || toybox stat -c %u:%g "$1" 2>/dev/null) || return 1
      mode=$(stat -c %a "$1" 2>/dev/null || toybox stat -c %a "$1" 2>/dev/null) || return 1
      [ "$owner" = 0:0 ] && [ "$mode" = "$2" ]
    }
    sync_path() {
      sync "$1" 2>/dev/null || sync
    }
    publish_rollback_phase() {
      ! app_replacement_custody_present || { echo ROLLBACK_REPLACEMENT_AUTHORITY_ACTIVE; exit 0; }
      phase_tmp="$marker".hapaneld-manual-"$transaction_id".phase
      rm -f "$phase_tmp" || return 1
      sed "/^ROLLBACK_PHASE=/d" "$marker" > "$phase_tmp" || return 1
      echo ROLLBACK_PHASE=PUBLISHING >> "$phase_tmp"
      chown 0:0 "$phase_tmp" || return 1
      chmod 600 "$phase_tmp" || return 1
      sync_path "$phase_tmp" || return 1
      ! app_replacement_custody_present || { echo ROLLBACK_REPLACEMENT_AUTHORITY_ACTIVE; exit 0; }
      mv -f "$phase_tmp" "$marker" || return 1
      sync_path "${marker%/*}" || return 1
      [ "$(grep -c ^ROLLBACK_PHASE=PUBLISHING$ "$marker")" = 1 ]
    }
    recorded() {
      name=$1; live=$2
      flag=$(sed -n s/^"$name"=//p "$marker")
      expected=$(sed -n s/^"$name"_SHA256=//p "$marker")
      case "$flag" in
        0) [ "$expected" = - ] && [ ! -e "$live" ] && [ ! -L "$live" ] ;;
        1) printf %s "$expected" | grep -Eq ^[0-9a-f]{64}$ &&
           [ "$(file_hash "$live")" = "$expected" ] ;;
        *) return 1 ;;
      esac
    }
    snapshot_valid() {
      name=$1
      flag=$(sed -n s/^"$name"=//p "$marker")
      expected=$(sed -n s/^"$name"_SHA256=//p "$marker")
      snapshot=/data/local/.hapaneld-helper-manual-"$transaction_id".recovery-"$name"
      case "$flag" in
        0) [ "$expected" = - ] && [ ! -e "$snapshot" ] && [ ! -L "$snapshot" ] ;;
        1) [ "$(file_hash "$snapshot")" = "$expected" ] ;;
        *) return 1 ;;
      esac
    }
    original_or_absent() {
      recorded "$1" "$2" || { [ ! -e "$2" ] && [ ! -L "$2" ]; }
    }
    original_absent_or_hash() {
      recorded "$1" "$2" || { [ ! -e "$2" ] && [ ! -L "$2" ]; } ||
        [ "$(file_hash "$2")" = "$3" ]
    }
    all_recorded() {
      recorded LIVE_CANONICAL /data/local/hapaneld-helper &&
      recorded LIVE_SYSTEM_BIN /system/bin/hapaneld-helper &&
      recorded LIVE_SYSTEM_RC /system/etc/init/hapaneld-helper.rc &&
      recorded LIVE_VENDOR_RC /vendor/etc/init/hapaneld-helper.rc &&
      recorded LIVE_SYSTEMLESS_BIN /data/adb/hapaneld/hapaneld-helper &&
      recorded LIVE_SYSTEMLESS_SERVICE /data/adb/service.d/hapaneld-helper.sh &&
      recorded LIVE_LEGACY_BIN /system/bin/hapaneld-ledd &&
      recorded LIVE_LEGACY_RC /system/etc/init/hapaneld-ledd.rc
    }
    recorded_exact() {
      name=$1; live=$2; mode=$3
      recorded "$name" "$live" || return 1
      grep -q ^"$name"=0$ "$marker" || owner_mode "$live" "$mode"
    }
    all_recorded_exact() {
      recorded_exact LIVE_CANONICAL /data/local/hapaneld-helper 700 &&
      recorded_exact LIVE_SYSTEM_BIN /system/bin/hapaneld-helper 755 &&
      recorded_exact LIVE_SYSTEM_RC /system/etc/init/hapaneld-helper.rc 644 &&
      recorded_exact LIVE_VENDOR_RC /vendor/etc/init/hapaneld-helper.rc 644 &&
      recorded_exact LIVE_SYSTEMLESS_BIN /data/adb/hapaneld/hapaneld-helper 755 &&
      recorded_exact LIVE_SYSTEMLESS_SERVICE /data/adb/service.d/hapaneld-helper.sh 755 &&
      recorded_exact LIVE_LEGACY_BIN /system/bin/hapaneld-ledd 755 &&
      recorded_exact LIVE_LEGACY_RC /system/etc/init/hapaneld-ledd.rc 644
    }
    all_snapshots_valid() {
      snapshot_valid LIVE_CANONICAL && snapshot_valid LIVE_SYSTEM_BIN &&
      snapshot_valid LIVE_SYSTEM_RC && snapshot_valid LIVE_VENDOR_RC &&
      snapshot_valid LIVE_SYSTEMLESS_BIN && snapshot_valid LIVE_SYSTEMLESS_SERVICE &&
      snapshot_valid LIVE_LEGACY_BIN && snapshot_valid LIVE_LEGACY_RC
    }
    target_exact() {
      [ "$(file_hash /data/local/hapaneld-helper)" = '"$target_helper"' ] &&
      [ ! -e /system/bin/hapaneld-helper ] && [ ! -L /system/bin/hapaneld-helper ] &&
      [ ! -e /data/adb/hapaneld/hapaneld-helper ] && [ ! -L /data/adb/hapaneld/hapaneld-helper ] &&
      [ ! -e /system/bin/hapaneld-ledd ] && [ ! -L /system/bin/hapaneld-ledd ] &&
      [ ! -e /system/etc/init/hapaneld-ledd.rc ] && [ ! -L /system/etc/init/hapaneld-ledd.rc ] || return 1
      case '"$install_kind"' in
        system)
          [ "$(file_hash /system/etc/init/hapaneld-helper.rc)" = '"$target_service"' ] &&
          [ ! -e /vendor/etc/init/hapaneld-helper.rc ] && [ ! -L /vendor/etc/init/hapaneld-helper.rc ] &&
          [ ! -e /data/adb/service.d/hapaneld-helper.sh ] && [ ! -L /data/adb/service.d/hapaneld-helper.sh ] ;;
        vendor)
          [ "$(file_hash /vendor/etc/init/hapaneld-helper.rc)" = '"$target_service"' ] &&
          [ ! -e /system/etc/init/hapaneld-helper.rc ] && [ ! -L /system/etc/init/hapaneld-helper.rc ] &&
          [ ! -e /data/adb/service.d/hapaneld-helper.sh ] && [ ! -L /data/adb/service.d/hapaneld-helper.sh ] ;;
        systemless)
          [ "$(file_hash /data/adb/service.d/hapaneld-helper.sh)" = '"$target_service"' ] &&
          [ ! -e /system/etc/init/hapaneld-helper.rc ] && [ ! -L /system/etc/init/hapaneld-helper.rc ] &&
          [ ! -e /vendor/etc/init/hapaneld-helper.rc ] && [ ! -L /vendor/etc/init/hapaneld-helper.rc ] ;;
        *) return 1 ;;
      esac
    }
    mutation_state_known() {
      original_absent_or_hash LIVE_CANONICAL /data/local/hapaneld-helper '"$target_helper"' &&
      original_or_absent LIVE_SYSTEM_BIN /system/bin/hapaneld-helper &&
      original_or_absent LIVE_SYSTEMLESS_BIN /data/adb/hapaneld/hapaneld-helper &&
      original_or_absent LIVE_LEGACY_BIN /system/bin/hapaneld-ledd &&
      original_or_absent LIVE_LEGACY_RC /system/etc/init/hapaneld-ledd.rc || return 1
      case '"$install_kind"' in
        system)
          original_absent_or_hash LIVE_SYSTEM_RC /system/etc/init/hapaneld-helper.rc '"$target_service"' &&
          original_or_absent LIVE_VENDOR_RC /vendor/etc/init/hapaneld-helper.rc &&
          original_or_absent LIVE_SYSTEMLESS_SERVICE /data/adb/service.d/hapaneld-helper.sh ;;
        vendor)
          original_absent_or_hash LIVE_VENDOR_RC /vendor/etc/init/hapaneld-helper.rc '"$target_service"' &&
          original_or_absent LIVE_SYSTEM_RC /system/etc/init/hapaneld-helper.rc &&
          original_or_absent LIVE_SYSTEMLESS_SERVICE /data/adb/service.d/hapaneld-helper.sh ;;
        systemless)
          original_absent_or_hash LIVE_SYSTEMLESS_SERVICE /data/adb/service.d/hapaneld-helper.sh '"$target_service"' &&
          original_or_absent LIVE_SYSTEM_RC /system/etc/init/hapaneld-helper.rc &&
          original_or_absent LIVE_VENDOR_RC /vendor/etc/init/hapaneld-helper.rc ;;
        *) return 1 ;;
      esac
    }
    phase_state_known() {
      if [ "$rollback_phase" = PUBLISHING ]; then
        mutation_state_known
        return
      fi
      [ -z "$rollback_phase" ] || return 1
      case "$phase" in
        PREPARED) all_recorded ;;
        MUTATING) mutation_state_known ;;
        TARGET) target_exact ;;
        *) return 1 ;;
      esac
    }
    phase=$(sed -n s/^SWAP_PHASE=//p "$marker")
    rollback_phase=$(sed -n s/^ROLLBACK_PHASE=//p "$marker")
    phase_state_known || { echo ROLLBACK_UNKNOWN; exit 0; }
    all_snapshots_valid || exit 1
    if [ -z "$rollback_phase" ]; then
      ! app_replacement_custody_present || { echo ROLLBACK_REPLACEMENT_AUTHORITY_ACTIVE; exit 0; }
      publish_rollback_phase || exit 1
      rollback_phase=PUBLISHING
    fi

    rm -f '"$probe_path"'
    [ "$(file_hash '"$PROBE_STAGING_PATH"')" = '"$BIN_SHA256"' ] || exit 1
    cp '"$PROBE_STAGING_PATH"' '"$probe_path"' || exit 1
    chown 0:0 '"$probe_path"' || exit 1
    chmod 700 '"$probe_path"' || exit 1
    [ "$(file_hash '"$probe_path"')" = '"$BIN_SHA256"' ] || exit 1

    stop hapaneld_helper 2>/dev/null
    stop hapaneld_ledd 2>/dev/null
    pkill -x hapaneld-helper 2>/dev/null
    pkill -x hapaneld-ledd 2>/dev/null
    attempt=0
    while pidof hapaneld-helper >/dev/null 2>&1 || pidof hapaneld-ledd >/dev/null 2>&1; do
      attempt=$((attempt + 1)); [ "$attempt" -lt 10 ] || exit 1; sleep 1
    done
    # An already-execed app R1 stage can cross to the canonical live path during retirement. The
    # phase classifier above was true before the wait; require it again at the last possible point
    # before restore so a stale snapshot can never overwrite that newly published helper/topology.
    ! app_replacement_custody_present || { echo ROLLBACK_REPLACEMENT_AUTHORITY_ACTIVE; exit 0; }
    phase_state_known || { echo ROLLBACK_UNKNOWN; exit 0; }
    restore() {
      name=$1; live=$2; mode=$3
      flag=$(sed -n s/^"$name"=//p "$marker")
      snapshot=/data/local/.hapaneld-helper-manual-"$transaction_id".recovery-"$name"
      expected=$(sed -n s/^"$name"_SHA256=//p "$marker")
      temporary="$live".hapaneld-manual-"$transaction_id".restore
      ! app_replacement_custody_present || { echo ROLLBACK_REPLACEMENT_AUTHORITY_ACTIVE; exit 0; }
      rm -f "$temporary" || return 1
      if [ "$flag" = 1 ]; then
        mkdir -p "${live%/*}" || return 1
        cp -p "$snapshot" "$temporary" || return 1
        chown 0:0 "$temporary" || return 1
        chmod "$mode" "$temporary" || return 1
        [ "$(file_hash "$temporary")" = "$expected" ] &&
          owner_mode "$temporary" "$mode" || return 1
        sync_path "$temporary" || return 1
        ! app_replacement_custody_present || { echo ROLLBACK_REPLACEMENT_AUTHORITY_ACTIVE; exit 0; }
        mv -f "$temporary" "$live" || return 1
        sync_path "${live%/*}" || return 1
        [ "$(file_hash "$live")" = "$expected" ] && owner_mode "$live" "$mode"
      else
        ! app_replacement_custody_present || { echo ROLLBACK_REPLACEMENT_AUTHORITY_ACTIVE; exit 0; }
        rm -f "$live" || return 1
        sync_path "${live%/*}" || return 1
        [ ! -e "$live" ] && [ ! -L "$live" ]
      fi
    }
    mount -o rw,remount / 2>/dev/null
    mount -o rw,remount /system 2>/dev/null
    mount -o rw,remount /vendor 2>/dev/null
    restore LIVE_CANONICAL /data/local/hapaneld-helper 700 || exit 1
    restore LIVE_SYSTEM_BIN /system/bin/hapaneld-helper 755 || exit 1
    restore LIVE_SYSTEM_RC /system/etc/init/hapaneld-helper.rc 644 || exit 1
    restore LIVE_VENDOR_RC /vendor/etc/init/hapaneld-helper.rc 644 || exit 1
    restore LIVE_SYSTEMLESS_BIN /data/adb/hapaneld/hapaneld-helper 755 || exit 1
    restore LIVE_SYSTEMLESS_SERVICE /data/adb/service.d/hapaneld-helper.sh 755 || exit 1
    restore LIVE_LEGACY_BIN /system/bin/hapaneld-ledd 755 || exit 1
    restore LIVE_LEGACY_RC /system/etc/init/hapaneld-ledd.rc 644 || exit 1
    sync || exit 1
    all_recorded_exact || exit 1
    if grep -q ^LIVE_CANONICAL=1$ "$marker"; then
      /data/local/hapaneld-helper --supervise >/dev/null 2>&1 &
      echo ROLLBACK_RESTARTED
    elif grep -q ^LIVE_SYSTEM_BIN=1$ "$marker"; then
      /system/bin/hapaneld-helper --supervise >/dev/null 2>&1 &
      echo ROLLBACK_RESTARTED
    elif grep -q ^LIVE_SYSTEMLESS_BIN=1$ "$marker"; then
      /data/adb/hapaneld/hapaneld-helper --supervise >/dev/null 2>&1 &
      echo ROLLBACK_RESTARTED
    elif grep -q ^LIVE_LEGACY_BIN=1$ "$marker"; then
      /system/bin/hapaneld-ledd >/dev/null 2>&1 &
      echo ROLLBACK_LEGACY
    else
      echo ROLLBACK_EMPTY
    fi
  ' 2>&1)" || true
  if printf '%s\n' "$restored" | grep -qx ROLLBACK_REPLACEMENT_AUTHORITY_ACTIVE; then
    return 3
  fi
  if printf '%s\n' "$restored" | grep -qx ROLLBACK_RESTARTED; then
    if wait_for_helper_reply PING OK "$install_kind" "$probe_path"; then
      run_root 'rm -f '"$probe_path" >/dev/null 2>&1 || true
      finalize_root_helper_rollback_v3 "$install_kind" "$transaction_id" "$target_build" "$target_helper" "$target_service"
      return $?
    fi
    run_root 'rm -f '"$probe_path" >/dev/null 2>&1 || true
    return 1
  fi
  run_root 'rm -f '"$probe_path" >/dev/null 2>&1 || true
  if printf '%s\n' "$restored" | grep -Eqx 'ROLLBACK_(EMPTY|LEGACY)'; then
    finalize_root_helper_rollback_v3 "$install_kind" "$transaction_id" "$target_build" "$target_helper" "$target_service"
  else
    printf '%s\n' "$restored" | grep -qx ROLLBACK_UNNEEDED
  fi
}

finalize_root_helper_rollback_v3() {
  local install_kind="$1" transaction_id="$2" target_build="$3"
  local target_helper="$4" target_service="$5" finalized
  finalized="$(run_root_locked '
    marker='"$MANUAL_V3_MARKER"'
    grep -q ^JOURNAL_VERSION=3$ "$marker" || exit 1
    grep -qx REGISTRATION_KIND='"$install_kind"' "$marker" || exit 1
    grep -qx TRANSACTION_ID='"$transaction_id"' "$marker" || exit 1
    grep -qx TARGET_BUILD_ID='"$target_build"' "$marker" || exit 1
    grep -qx TARGET_HELPER_SHA256='"$target_helper"' "$marker" || exit 1
    grep -qx TARGET_SERVICE_SHA256='"$target_service"' "$marker" || exit 1
      for custody in \
        /data/local/.hapaneld-helper.new \
        /data/local/.hapaneld-helper.previous \
        /data/local/.hapaneld-helper.previous.tmp \
        /data/local/.hapaneld-helper.legacy-takeover \
        /data/local/.hapaneld-helper.legacy-takeover.tmp \
        /data/local/.hapaneld-guard-db/replacement.v1 \
        /data/local/.hapaneld-guard-db/.replacement.v1.tmp; do
      [ ! -e "$custody" ] && [ ! -L "$custody" ] || { echo ROLLBACK_REPLACEMENT_AUTHORITY_ACTIVE; exit 0; }
    done
    file_hash() {
      actual=$(sha256sum "$1" 2>/dev/null || toybox sha256sum "$1" 2>/dev/null) || return 1
      printf %s "${actual%% *}"
    }
    recorded() {
      name=$1; live=$2
      flag=$(sed -n s/^"$name"=//p "$marker")
      expected=$(sed -n s/^"$name"_SHA256=//p "$marker")
      [ "$flag" = 1 ] && [ "$(file_hash "$live")" = "$expected" ] ||
        { [ "$flag" = 0 ] && [ "$expected" = - ] && [ ! -e "$live" ] && [ ! -L "$live" ]; }
    }
    recorded LIVE_CANONICAL /data/local/hapaneld-helper &&
    recorded LIVE_SYSTEM_BIN /system/bin/hapaneld-helper &&
    recorded LIVE_SYSTEM_RC /system/etc/init/hapaneld-helper.rc &&
    recorded LIVE_VENDOR_RC /vendor/etc/init/hapaneld-helper.rc &&
    recorded LIVE_SYSTEMLESS_BIN /data/adb/hapaneld/hapaneld-helper &&
    recorded LIVE_SYSTEMLESS_SERVICE /data/adb/service.d/hapaneld-helper.sh &&
    recorded LIVE_LEGACY_BIN /system/bin/hapaneld-ledd &&
    recorded LIVE_LEGACY_RC /system/etc/init/hapaneld-ledd.rc || exit 1
      for custody in \
        /data/local/.hapaneld-helper.new \
        /data/local/.hapaneld-helper.previous \
        /data/local/.hapaneld-helper.previous.tmp \
        /data/local/.hapaneld-helper.legacy-takeover \
        /data/local/.hapaneld-helper.legacy-takeover.tmp \
        /data/local/.hapaneld-guard-db/replacement.v1 \
        /data/local/.hapaneld-guard-db/.replacement.v1.tmp; do
      [ ! -e "$custody" ] && [ ! -L "$custody" ] || { echo ROLLBACK_REPLACEMENT_AUTHORITY_ACTIVE; exit 0; }
    done
    rm -f "$marker" || exit 1
    sync || exit 1
    rm -f /data/local/.hapaneld-helper-manual-'"$transaction_id"'.recovery-* \
      /data/local/.hapaneld-helper.manual-'"$transaction_id"' \
      /system/etc/init/hapaneld-helper.rc.manual-'"$transaction_id"' \
      /vendor/etc/init/hapaneld-helper.rc.manual-'"$transaction_id"' \
      /data/adb/service.d/hapaneld-helper.sh.manual-'"$transaction_id"' 2>/dev/null || true
    sync 2>/dev/null || true
    echo ROLLBACK_FINALIZED
  ' 2>&1)" || true
  if printf '%s\n' "$finalized" | grep -qx ROLLBACK_REPLACEMENT_AUTHORITY_ACTIVE; then
    return 3
  fi
  printf '%s\n' "$finalized" | grep -qx ROLLBACK_FINALIZED
}

commit_root_helper_upgrade_v3() {
  local install_kind="$1" committed
  committed="$(run_root_locked '
    marker='"$MANUAL_V3_MARKER"'
    grep -q ^JOURNAL_VERSION=3$ "$marker" || exit 1
    grep -q ^SWAP_PHASE=TARGET$ "$marker" || exit 1
    grep -qx REGISTRATION_KIND='"$install_kind"' "$marker" || exit 1
    grep -qx TRANSACTION_ID='"$TRANSACTION_ID"' "$marker" || exit 1
    grep -qx TARGET_BUILD_ID='"$BUILD_ID"' "$marker" || exit 1
    grep -qx TARGET_HELPER_SHA256='"$BIN_SHA256"' "$marker" || exit 1
    grep -qx TARGET_SERVICE_SHA256='"$SERVICE_SHA256"' "$marker" || exit 1
    file_hash() {
      actual=$(sha256sum "$1" 2>/dev/null || toybox sha256sum "$1" 2>/dev/null) || return 1
      printf %s "${actual%% *}"
    }
    owner_mode() {
      owner=$(stat -c %u:%g "$1" 2>/dev/null || toybox stat -c %u:%g "$1" 2>/dev/null) || return 1
      mode=$(stat -c %a "$1" 2>/dev/null || toybox stat -c %a "$1" 2>/dev/null) || return 1
      [ "$owner" = 0:0 ] && [ "$mode" = "$2" ]
    }
    [ "$(file_hash /data/local/hapaneld-helper)" = '"$BIN_SHA256"' ] &&
    owner_mode /data/local/hapaneld-helper 700 || exit 1
    [ ! -e /system/bin/hapaneld-helper ] && [ ! -L /system/bin/hapaneld-helper ] &&
    [ ! -e /data/adb/hapaneld/hapaneld-helper ] && [ ! -L /data/adb/hapaneld/hapaneld-helper ] &&
    [ ! -e /system/bin/hapaneld-ledd ] && [ ! -L /system/bin/hapaneld-ledd ] &&
    [ ! -e /system/etc/init/hapaneld-ledd.rc ] && [ ! -L /system/etc/init/hapaneld-ledd.rc ] || exit 1
    case '"$install_kind"' in
      system)
        [ "$(file_hash /system/etc/init/hapaneld-helper.rc)" = '"$SERVICE_SHA256"' ] &&
        owner_mode /system/etc/init/hapaneld-helper.rc 644 &&
        [ ! -e /vendor/etc/init/hapaneld-helper.rc ] && [ ! -L /vendor/etc/init/hapaneld-helper.rc ] &&
        [ ! -e /data/adb/service.d/hapaneld-helper.sh ] && [ ! -L /data/adb/service.d/hapaneld-helper.sh ] || exit 1 ;;
      vendor)
        [ "$(file_hash /vendor/etc/init/hapaneld-helper.rc)" = '"$SERVICE_SHA256"' ] &&
        owner_mode /vendor/etc/init/hapaneld-helper.rc 644 &&
        [ ! -e /system/etc/init/hapaneld-helper.rc ] && [ ! -L /system/etc/init/hapaneld-helper.rc ] &&
        [ ! -e /data/adb/service.d/hapaneld-helper.sh ] && [ ! -L /data/adb/service.d/hapaneld-helper.sh ] || exit 1 ;;
      systemless)
        [ "$(file_hash /data/adb/service.d/hapaneld-helper.sh)" = '"$SERVICE_SHA256"' ] &&
        owner_mode /data/adb/service.d/hapaneld-helper.sh 755 &&
        [ ! -e /system/etc/init/hapaneld-helper.rc ] && [ ! -L /system/etc/init/hapaneld-helper.rc ] &&
        [ ! -e /vendor/etc/init/hapaneld-helper.rc ] && [ ! -L /vendor/etc/init/hapaneld-helper.rc ] || exit 1 ;;
      *) exit 1 ;;
    esac
    rm -f "$marker" || exit 1
    sync || exit 1
    rm -f /data/local/.hapaneld-helper-manual-'"$TRANSACTION_ID"'.recovery-* \
      /data/local/.hapaneld-helper.manual-'"$TRANSACTION_ID"' \
      /system/etc/init/hapaneld-helper.rc.manual-'"$TRANSACTION_ID"' \
      /vendor/etc/init/hapaneld-helper.rc.manual-'"$TRANSACTION_ID"' \
      /data/adb/service.d/hapaneld-helper.sh.manual-'"$TRANSACTION_ID"' 2>/dev/null || true
    sync 2>/dev/null || true
    echo COMMIT_OK
  ' 2>&1)" || true
  printf '%s\n' "$committed" | grep -qx COMMIT_OK
}

# Stage the current client-capable helper before stale-journal recovery. Root copies it only after
# authenticating BIN_SHA256; the shell-owned staging path is never executed directly.
adb -s "$TARGET" push "$BIN" "$PROBE_STAGING_PATH" >/dev/null

manual_journal_state="$(run_root_locked '
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
  app_stage_metadata() {
    [ -f "$1" ] && [ ! -L "$1" ] || return 1
    stat -c "%d:%i:%u:%g:%a:%h:%s" "$1" 2>/dev/null ||
      toybox stat -c "%d:%i:%u:%g:%a:%h:%s" "$1" 2>/dev/null
  }
  valid_app_stage_metadata() {
    app_stage_value=$1; app_stage_modes=$2
    app_stage_old_ifs=$IFS
    IFS=:
    set -- $app_stage_value
    IFS=$app_stage_old_ifs
    [ "$#" -eq 7 ] || return 1
    [ "$3:$4:$6" = 0:0:1 ] || return 1
    case ":$app_stage_modes:" in *:"$5":*) ;; *) return 1 ;; esac
    case "$7" in ""|*[!0-9]*) return 1 ;; esac
    [ "$7" -ge 1 ] && [ "$7" -le 16777216 ]
  }
  app_stage_file_sha256() {
    app_stage_hash=$(sha256sum "$1" 2>/dev/null || toybox sha256sum "$1" 2>/dev/null) || return 1
    printf "%s\n" "${app_stage_hash%% *}"
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
    case "$7" in ""|*[!0-9]*) return 1 ;; esac
    [ "$7" -le 1024 ] || return 1
    legacy_tmp_after=$(app_stage_metadata "$legacy_tmp_record") || return 1
    [ "$legacy_tmp_after" = "$legacy_tmp_before" ] || return 1
    rm -f "$legacy_tmp_record" || return 1
    sync || return 1
    return 0
  }
  legacy_valid_sha256() {
    case "$1" in ""|*[!0-9a-f]*) return 1 ;; esac
    [ "${#1}" -eq 64 ]
  }
  legacy_bounded_bytes() {
    case "$1" in ""|*[!0-9]*) return 1 ;; esac
    [ "$1" -ge 1 ] && [ "$1" -le 16777216 ]
  }
  legacy_exact_file() {
    legacy_path=$1; legacy_sha=$2; legacy_mode=$3; legacy_bytes=$4
    legacy_meta=$(app_stage_metadata "$legacy_path") || return 1
    legacy_meta_old_ifs=$IFS
    IFS=:
    set -- $legacy_meta
    IFS=$legacy_meta_old_ifs
    [ "$#" -eq 7 ] && [ "$3:$4:$5:$6:$7" = "0:0:$legacy_mode:1:$legacy_bytes" ] || return 1
    [ "$(app_stage_file_sha256 "$legacy_path")" = "$legacy_sha" ]
  }
  legacy_path_processes() {
    legacy_process_inode=$(stat -c "%d:%i" "$1" 2>/dev/null || toybox stat -c "%d:%i" "$1" 2>/dev/null) || return 1
    legacy_process_found=
    for legacy_executable in /proc/[0-9]*/exe; do
      legacy_executable_inode=$(stat -Lc "%d:%i" "$legacy_executable" 2>/dev/null || toybox stat -L -c "%d:%i" "$legacy_executable" 2>/dev/null) || continue
      [ "$legacy_executable_inode" != "$legacy_process_inode" ] || legacy_process_found="$legacy_process_found ${legacy_executable#/proc/}"
    done
    printf "%s\n" "$legacy_process_found"
  }
  legacy_stop_path_processes() {
    legacy_processes=$(legacy_path_processes "$1") || return 1
    for legacy_process in $legacy_processes; do legacy_process=${legacy_process%/exe}; kill "$legacy_process" 2>/dev/null || true; done
    legacy_stop_attempt=0
    while [ "$legacy_stop_attempt" -lt 3 ]; do
      legacy_processes=$(legacy_path_processes "$1") || return 1
      [ -n "$legacy_processes" ] || return 0
      sleep 1
      legacy_stop_attempt=$((legacy_stop_attempt + 1))
    done
    for legacy_process in $legacy_processes; do legacy_process=${legacy_process%/exe}; kill -9 "$legacy_process" 2>/dev/null || true; done
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
    # Fully drain an interrupted canonical supervisor before relaunching it.  Otherwise a dying
    # process can answer GUARDSELF while a second supervisor exits on its owner-lock collision.
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
    trap "abort_helper_lock 129" 1
    trap "abort_helper_lock 130" 2
    trap "abort_helper_lock 131" 3
    trap "abort_helper_lock 143" 15
  }
  legacy_normalization_signal() {
    legacy_signal_status=$1
    trap - 1 2 3 15
    legacy_restore_after_normalization_failure >/dev/null 2>&1 || true
    cleanup_helper_lock
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
    case "$legacy_record_bytes" in ""|*[!0-9]*) return 1 ;; esac
    [ "$legacy_record_bytes" -ge 1 ] && [ "$legacy_record_bytes" -le 1024 ] || return 1
    legacy_record_sha=$(app_stage_file_sha256 "$legacy_record") || return 1
    legacy_record_line=$(cat "$legacy_record") || return 1
    set -f
    set -- $legacy_record_line
    set +f
    [ "$#" -eq 13 ] && [ "$1:$2:$3" = OK:LEGACYTAKEOVER:1 ] || return 1
    legacy_topology=$4; legacy_old_sha=$5; legacy_old_bytes=$6
    legacy_registration_sha=$7; legacy_registration_bytes=$8; legacy_registration_mode=$9
    legacy_incumbent_build=${10}; legacy_staged_build=${11}; legacy_candidate_sha=${12}; legacy_candidate_bytes=${13}
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
        legacy_old_bin=/system/bin/hapaneld-helper; legacy_registration=/system/etc/init/hapaneld-helper.rc
        legacy_foreign="/data/adb/hapaneld/hapaneld-helper /data/adb/service.d/hapaneld-helper.sh /vendor/etc/init/hapaneld-helper.rc" ;;
      systemless:755)
        legacy_old_bin=/data/adb/hapaneld/hapaneld-helper; legacy_registration=/data/adb/service.d/hapaneld-helper.sh
        legacy_foreign="/system/bin/hapaneld-helper /system/etc/init/hapaneld-helper.rc /vendor/etc/init/hapaneld-helper.rc" ;;
      hybrid:644)
        legacy_old_bin=/data/adb/hapaneld/hapaneld-helper; legacy_registration=/vendor/etc/init/hapaneld-helper.rc
        legacy_foreign="/system/bin/hapaneld-helper /system/etc/init/hapaneld-helper.rc /data/adb/service.d/hapaneld-helper.sh" ;;
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
      [ -n "$legacy_candidate_source" ] || legacy_candidate_source=/data/local/.hapaneld-helper.new
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
        # No valid app takeover cut executes the staging pathname, and no exact restoration state
        # exists for one.  Preserve the record and staged process fail-closed.
        return 1
      fi
    fi
    if [ "$legacy_candidate_was_running" = 1 ]; then
      legacy_candidate_path_serving "$legacy_running_candidate_source" || return 1
    fi
    legacy_normalization_phase=candidate
    trap "legacy_normalization_signal 129" 1
    trap "legacy_normalization_signal 130" 2
    trap "legacy_normalization_signal 131" 3
    trap "legacy_normalization_signal 143" 15
    if [ -f /data/local/hapaneld-helper ]; then
      legacy_stop_path_processes /data/local/hapaneld-helper || { legacy_restore_after_normalization_failure || true; legacy_restore_signal_traps; return 1; }
    fi
    if [ -f /data/local/.hapaneld-helper.new ]; then
      legacy_stop_path_processes /data/local/.hapaneld-helper.new || { legacy_restore_after_normalization_failure || true; legacy_restore_signal_traps; return 1; }
    fi
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
    [ "$legacy_record_after" = "$legacy_record_meta" ] && [ "$(app_stage_file_sha256 "$legacy_record")" = "$legacy_record_sha" ] || return 1
    legacy_record_final=$(app_stage_metadata "$legacy_record") || return 1
    [ "$legacy_record_final" = "$legacy_record_meta" ] && [ "$(app_stage_file_sha256 "$legacy_record")" = "$legacy_record_sha" ] || return 1
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
      case "$app_upload_id" in ""|*[!0-9a-f]*) continue ;; esac
      [ "${#app_upload_id}" -eq 64 ] || continue
      app_upload_before=$(app_stage_metadata "$app_upload") || continue
      valid_app_stage_metadata "$app_upload_before" "600:700" || continue
      app_upload_processes=$(legacy_path_processes "$app_upload") || continue
      [ -z "$app_upload_processes" ] || continue
      app_stage_authority_absent || return 0
      app_upload_after=$(app_stage_metadata "$app_upload") || continue
      [ "$app_upload_after" = "$app_upload_before" ] || continue
      valid_app_stage_metadata "$app_upload_after" "600:700" || continue
      app_upload_processes=$(legacy_path_processes "$app_upload") || continue
      [ -z "$app_upload_processes" ] || continue
      rm -f "$app_upload" 2>/dev/null || continue
      sync 2>/dev/null || true
    done
    return 0
  }

  # This command is already inside run_root_locked. Reclaim only app staging that never acquired
  # any native, legacy, previous, provisioner, or manual transaction authority.
  reconcile_lone_legacy_takeover_tmp || { echo LEGACY_TAKEOVER_HOLD; exit 75; }
  normalize_legacy_takeover || { echo LEGACY_TAKEOVER_HOLD; exit 75; }
  reconcile_authority_free_app_staging

  inspect_manual_journal_v1() {
    kind=$1; marker=$2
    owner=$(stat -c %u:%g "$marker" 2>/dev/null || toybox stat -c %u:%g "$marker" 2>/dev/null) || { echo INVALID_MANUAL_TRANSACTION; return; }
    [ "$owner" = 0:0 ] || { echo INVALID_MANUAL_TRANSACTION; return; }
    grep -q ^JOURNAL_VERSION=1$ "$marker" || { echo INVALID_MANUAL_TRANSACTION; return; }
    target_build=$(sed -n s/^TARGET_BUILD_ID=//p "$marker")
    target_helper=$(sed -n s/^TARGET_HELPER_SHA256=//p "$marker")
    printf %s "$target_build" | grep -Eq ^[0-9a-f]{64}$ || { echo INVALID_MANUAL_TRANSACTION; return; }
    printf %s "$target_helper" | grep -Eq ^[0-9a-f]{64}$ || { echo INVALID_MANUAL_TRANSACTION; return; }
    echo STALE_${kind}_TRANSACTION legacy "$target_build" "$target_helper" -
  }
  inspect_manual_journal_v2() {
    kind=$1; marker=$2
    owner=$(stat -c %u:%g "$marker" 2>/dev/null || toybox stat -c %u:%g "$marker" 2>/dev/null) || { echo INVALID_MANUAL_TRANSACTION; return; }
    [ "$owner" = 0:0 ] || { echo INVALID_MANUAL_TRANSACTION; return; }
    grep -q ^JOURNAL_VERSION=2$ "$marker" || { echo INVALID_MANUAL_TRANSACTION; return; }
    transaction_id=$(sed -n s/^TRANSACTION_ID=//p "$marker")
    target_build=$(sed -n s/^TARGET_BUILD_ID=//p "$marker")
    target_helper=$(sed -n s/^TARGET_HELPER_SHA256=//p "$marker")
    target_service=$(sed -n s/^TARGET_SERVICE_SHA256=//p "$marker")
    lease_boot=$(sed -n s/^LEASE_BOOT_ID=//p "$marker")
    lease_until=$(sed -n s/^LEASE_UNTIL_UPTIME=//p "$marker")
    printf %s "$transaction_id" | grep -Eq ^[0-9a-f]{32}$ || { echo INVALID_MANUAL_TRANSACTION; return; }
    printf %s "$target_build" | grep -Eq ^[0-9a-f]{64}$ || { echo INVALID_MANUAL_TRANSACTION; return; }
    printf %s "$target_helper" | grep -Eq ^[0-9a-f]{64}$ || { echo INVALID_MANUAL_TRANSACTION; return; }
    printf %s "$target_service" | grep -Eq ^[0-9a-f]{64}$ || { echo INVALID_MANUAL_TRANSACTION; return; }
    printf %s "$lease_until" | grep -Eq ^[0-9]+$ || { echo INVALID_MANUAL_TRANSACTION; return; }
    current_boot=$(cat /proc/sys/kernel/random/boot_id 2>/dev/null || true)
    current_uptime=$(cut -d. -f1 /proc/uptime 2>/dev/null || true)
    if [ -n "$current_boot" ] && [ "$lease_boot" = "$current_boot" ] &&
       printf %s "$current_uptime" | grep -Eq ^[0-9]+$ && [ "$current_uptime" -lt "$lease_until" ]; then
      echo ACTIVE_${kind}_TRANSACTION
    else
      echo STALE_${kind}_TRANSACTION "$transaction_id" "$target_build" "$target_helper" "$target_service"
    fi
  }
  inspect_manual_journal_v3() {
    marker=$1
    owner=$(stat -c %u:%g "$marker" 2>/dev/null || toybox stat -c %u:%g "$marker" 2>/dev/null) || { echo INVALID_MANUAL_TRANSACTION; return; }
    [ "$owner" = 0:0 ] || { echo INVALID_MANUAL_TRANSACTION; return; }
    grep -q ^JOURNAL_VERSION=3$ "$marker" || { echo INVALID_MANUAL_TRANSACTION; return; }
    registration_kind=$(sed -n s/^REGISTRATION_KIND=//p "$marker")
    transaction_id=$(sed -n s/^TRANSACTION_ID=//p "$marker")
    target_build=$(sed -n s/^TARGET_BUILD_ID=//p "$marker")
    target_helper=$(sed -n s/^TARGET_HELPER_SHA256=//p "$marker")
    target_service=$(sed -n s/^TARGET_SERVICE_SHA256=//p "$marker")
    lease_boot=$(sed -n s/^LEASE_BOOT_ID=//p "$marker")
    lease_until=$(sed -n s/^LEASE_UNTIL_UPTIME=//p "$marker")
    case "$registration_kind" in system|vendor|systemless) ;; *) echo INVALID_MANUAL_TRANSACTION; return ;; esac
    printf %s "$transaction_id" | grep -Eq ^[0-9a-f]{32}$ || { echo INVALID_MANUAL_TRANSACTION; return; }
    printf %s "$target_build" | grep -Eq ^[0-9a-f]{64}$ || { echo INVALID_MANUAL_TRANSACTION; return; }
    printf %s "$target_helper" | grep -Eq ^[0-9a-f]{64}$ || { echo INVALID_MANUAL_TRANSACTION; return; }
    printf %s "$target_service" | grep -Eq ^[0-9a-f]{64}$ || { echo INVALID_MANUAL_TRANSACTION; return; }
    printf %s "$lease_until" | grep -Eq ^[0-9]+$ || { echo INVALID_MANUAL_TRANSACTION; return; }
    current_boot=$(cat /proc/sys/kernel/random/boot_id 2>/dev/null || true)
    current_uptime=$(cut -d. -f1 /proc/uptime 2>/dev/null || true)
    if [ -n "$current_boot" ] && [ "$lease_boot" = "$current_boot" ] &&
       printf %s "$current_uptime" | grep -Eq ^[0-9]+$ && [ "$current_uptime" -lt "$lease_until" ]; then
      echo ACTIVE_V3_TRANSACTION
    else
      echo STALE_V3_TRANSACTION "$registration_kind" "$transaction_id" "$target_build" "$target_helper" "$target_service"
    fi
  }
  inspect_historical_journal() {
    kind=$1; marker=$2
    version=$(sed -n s/^JOURNAL_VERSION=//p "$marker")
    case "$version" in
      1) inspect_manual_journal_v1 "$kind" "$marker" ;;
      2) inspect_manual_journal_v2 "$kind" "$marker" ;;
      *) echo INVALID_MANUAL_TRANSACTION ;;
    esac
  }
  if [ -f /system/bin/.hapaneld-helper-upgrade ] || [ -f /data/adb/hapaneld/.helper-upgrade.marker ] || [ -f /data/adb/hapaneld/.helper-hybrid-upgrade.marker ]; then
    echo FOREIGN_PROVISION_TRANSACTION
  elif { [ -f /data/local/.hapaneld-helper-manual-upgrade ] &&
         { [ -f /system/bin/.hapaneld-helper-manual-upgrade ] || [ -f /data/adb/hapaneld/.helper-manual-upgrade.marker ]; }; } ||
       { [ -f /system/bin/.hapaneld-helper-manual-upgrade ] && [ -f /data/adb/hapaneld/.helper-manual-upgrade.marker ]; }; then
    echo MULTIPLE_STALE_TRANSACTIONS
  elif [ -f /data/local/.hapaneld-helper-manual-upgrade ]; then
    inspect_manual_journal_v3 /data/local/.hapaneld-helper-manual-upgrade
  elif [ -f /system/bin/.hapaneld-helper-manual-upgrade ]; then
    inspect_historical_journal SYSTEM /system/bin/.hapaneld-helper-manual-upgrade
  elif [ -f /data/adb/hapaneld/.helper-manual-upgrade.marker ]; then
    inspect_historical_journal SYSTEMLESS /data/adb/hapaneld/.helper-manual-upgrade.marker
  elif app_replacement_custody_present; then
    echo APP_REPLACEMENT_HOLD
  else
    echo NO_STALE_TRANSACTION
  fi
' 2>&1)" || true
case "$manual_journal_state" in
  STALE_V3_TRANSACTION\ *)
    read -r _ recovery_kind recovery_id recovery_build recovery_helper recovery_service <<<"$manual_journal_state"
    if rollback_root_helper_v3 "$recovery_kind" "$recovery_id" "$recovery_build" "$recovery_helper" "$recovery_service"; then
      :
    else
      recovery_status=$?
      if [ "$recovery_status" -eq 3 ]; then
        fail "APK-coupled helper replacement custody blocks standalone journal recovery" \
          "The retained v3 journal and every live helper path were left unchanged. Let the app R1 replacement finish or reconcile its fixed custody records before retrying."
      fi
      fail "the retained canonical helper-only journal could not be recovered safely" \
        "No unrecognised live helper bytes were overwritten. Inspect the authenticated recovery snapshots before retrying."
    fi ;;
  STALE_SYSTEM_TRANSACTION\ *)
    read -r _ recovery_id recovery_build recovery_helper recovery_service <<<"$manual_journal_state"
    rollback_root_helper system "$recovery_id" "$recovery_build" "$recovery_helper" "$recovery_service" || fail "the retained /system helper-only journal could not be recovered safely" \
      "No live helper files were changed. Inspect the authenticated recovery snapshots before retrying." ;;
  STALE_SYSTEMLESS_TRANSACTION\ *)
    read -r _ recovery_id recovery_build recovery_helper recovery_service <<<"$manual_journal_state"
    rollback_root_helper systemless "$recovery_id" "$recovery_build" "$recovery_helper" "$recovery_service" || fail "the retained systemless helper-only journal could not be recovered safely" \
      "No live helper files were changed. Inspect the authenticated recovery snapshots before retrying." ;;
  ACTIVE_SYSTEM_TRANSACTION|ACTIVE_SYSTEMLESS_TRANSACTION|ACTIVE_V3_TRANSACTION)
    fail "another standalone root-helper installer owns an active transaction lease" \
      "Wait for it to finish or for its boot-scoped uptime lease to expire, then re-run." ;;
  MULTIPLE_STALE_TRANSACTIONS)
    fail "both standalone root-helper recovery journals are present" \
      "No rollback was attempted because the authoritative prior install location is ambiguous." ;;
  FOREIGN_PROVISION_TRANSACTION)
    fail "an incomplete APK-coupled helper upgrade must be recovered by the provisioner first" \
      "Recover its authenticated journal first: scripts/provision.sh $TARGET --recover-helper --apk <release-signed-panel.apk>. The APK is recovery evidence and will not be installed." \
      "This standalone installer did not change helper files." ;;
  LEGACY_TAKEOVER_HOLD)
    fail "a retained app-managed root-helper takeover could not be normalized safely" \
      "The exact takeover record and helper bytes were preserved. Let the app finish recovery, then re-run this installer." ;;
  APP_REPLACEMENT_HOLD)
    fail "app-managed root-helper replacement custody blocks standalone installation" \
      "The fixed custody paths were preserved, and no helper topology or standalone recovery journal was changed. Let the app finish or reconcile its replacement, then re-run this installer." ;;
  TRANSACTION_BUSY)
    fail "another root-helper transaction is active on the panel" \
      "Wait for the other installer or provisioner to finish, then re-run." ;;
  NO_STALE_TRANSACTION) ;;
  INVALID_MANUAL_TRANSACTION) fail "the retained standalone root-helper journal is invalid or not root-owned" \
    "No rollback was attempted. Inspect the journal and recovery snapshots before retrying." ;;
  *) fail "could not determine the root-helper recovery state" \
    "No live helper files were changed." \
    "$(describe_observed_state "$manual_journal_state")" \
    "Re-run, and if it repeats include that line and 'adb features $TARGET' in a report:" \
    "https://github.com/panel-assistant/android/issues" ;;
esac

# Select a verified boot-registration route before stopping the old daemon. /data/local is the only
# live binary location; writable system/vendor init and verified service.d are registration choices.
out="$(run_root '
  mount -o rw,remount / 2>/dev/null
  mount -o rw,remount /system 2>/dev/null
  mount -o rw,remount /vendor 2>/dev/null
  if touch /system/.rw_probe 2>/dev/null && rm /system/.rw_probe 2>/dev/null; then
    echo SYSTEM_RW
  else
    echo SYSTEM_RO
  fi
  if [ -d /vendor/etc/init ] &&
     touch /vendor/etc/init/.hapaneld-rw-probe 2>/dev/null &&
     rm /vendor/etc/init/.hapaneld-rw-probe 2>/dev/null; then
    echo VENDOR_INIT_RW
  else
    echo VENDOR_INIT_RO
  fi
  if command -v magisk >/dev/null 2>&1 || [ -x /data/adb/magisk/busybox ] ||
     [ -x /data/adb/ksu/bin/busybox ] || [ -x /data/adb/ap/bin/busybox ]; then
    echo SYSTEMLESS_RUNNER
  else
    echo NO_SYSTEMLESS_RUNNER
  fi
' 2>&1)" || true
echo "$out" | sed 's/^/   /'

INSTALL_KIND=""
REGISTRATION_PATH=""
REGISTRATION_STAGING_PATH=""
REGISTRATION_MODE=""
SERVICE_SHA256=""
if printf '%s\n' "$out" | grep -qx SYSTEM_RW; then
  INSTALL_KIND=system
  REGISTRATION_PATH=/system/etc/init/hapaneld-helper.rc
  REGISTRATION_STAGING_PATH=/system/etc/init/hapaneld-helper.rc.manual-$TRANSACTION_ID
  REGISTRATION_MODE=644
  SERVICE_SHA256="$RC_SHA256"
  adb -s "$TARGET" push "$HERE/hapaneld-helper.rc" "$RC_STAGING_PATH" >/dev/null
  echo "==> installing canonical helper with /system init registration ($ABI)"
elif printf '%s\n' "$out" | grep -qx VENDOR_INIT_RW; then
  INSTALL_KIND=vendor
  REGISTRATION_PATH=/vendor/etc/init/hapaneld-helper.rc
  REGISTRATION_STAGING_PATH=/vendor/etc/init/hapaneld-helper.rc.manual-$TRANSACTION_ID
  REGISTRATION_MODE=644
  SERVICE_SHA256="$RC_SHA256"
  adb -s "$TARGET" push "$HERE/hapaneld-helper.rc" "$RC_STAGING_PATH" >/dev/null
  echo "==> installing canonical helper with /vendor init registration ($ABI)"
elif printf '%s\n' "$out" | grep -qx SYSTEMLESS_RUNNER; then
  INSTALL_KIND=systemless
  REGISTRATION_PATH=/data/adb/service.d/hapaneld-helper.sh
  REGISTRATION_STAGING_PATH=/data/adb/service.d/hapaneld-helper.sh.manual-$TRANSACTION_ID
  REGISTRATION_MODE=755
  echo "==> installing canonical helper with verified service.d registration ($ABI)"
  SVC="$(mktemp)"
  cat > "$SVC" <<'SVCEOF'
#!/system/bin/sh
while [ "$(getprop sys.boot_completed)" != "1" ]; do sleep 3; done
/system/bin/stop hapaneld_helper 2>/dev/null
/system/bin/stop hapaneld_ledd 2>/dev/null
/system/bin/pkill -x hapaneld-helper 2>/dev/null
/system/bin/pkill -x hapaneld-ledd 2>/dev/null
/data/local/hapaneld-helper --supervise >/dev/null 2>&1 &
SVCEOF
  SERVICE_SHA256="$(host_sha256 "$SVC")"
  adb -s "$TARGET" push "$SVC" "$SVC_STAGING_PATH" >/dev/null
  rm -f "$SVC"
else
  run_root 'rm -f '"$PROBE_STAGING_PATH $RC_STAGING_PATH $SVC_STAGING_PATH" >/dev/null 2>&1 || true
  # A trustworthy negative capability result gets actionable recovery guidance. An unreadable or
  # unrecognised probe stays on the generic refusal below: it has not established that /system is
  # read-only and must not send the operator to modify verity or install a root manager.
  if printf '%s\n' "$out" | grep -qx SYSTEM_RO &&
     ! printf '%s\n' "$out" | grep -qx VENDOR_INIT_RW &&
     printf '%s\n' "$out" | grep -qx NO_SYSTEMLESS_RUNNER; then
    fail "could not determine where the canonical helper can be boot-registered" \
      "The panel has read-only /system and no verified systemless boot-service runner; no writable vendor init registration was found." \
      "The existing helper was left running and no live files were replaced." \
      "On a userdebug panel with an unlocked bootloader this is usually a writable overlay that is not mounted, or dm-verity — not a missing root manager." \
      "Try the host-side remount FIRST. It needs no reboot, and on panels that already carry a scratch overlay it is the whole fix:" \
      "  adb -s $TARGET root && adb -s $TARGET remount" \
      "Only if that is refused, disable verity — this REBOOTS the panel, so do it with the panel in front of you and unlock any PIN-protected panel before expecting it back:" \
      "  adb -s $TARGET disable-verity && adb -s $TARGET reboot" \
      "After the panel restarts and is unlocked:" \
      "  adb -s $TARGET root && adb -s $TARGET remount" \
      "Then re-run this installer. If remount is still refused after that, provide a writable /vendor init directory, install a supported Magisk, KernelSU, or APatch service.d environment, or use firmware with a writable /system init path."
  fi
  fail "could not determine where the canonical helper can be boot-registered" \
    "The existing helper was left running and no live files were replaced." \
    "$(describe_observed_state "$out")" \
    "Provide a writable /system or /vendor init directory, or a supported Magisk, KernelSU, or APatch service.d environment."
fi

start_manual_lease_guard "$INSTALL_KIND"
out2="$(run_root_locked '
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

  if [ -f /system/bin/.hapaneld-helper-upgrade ] ||
     [ -f /data/adb/hapaneld/.helper-upgrade.marker ] ||
     [ -f /data/adb/hapaneld/.helper-hybrid-upgrade.marker ]; then
    echo FOREIGN_PROVISION_TRANSACTION; exit 75
  elif [ -f /data/local/.hapaneld-helper-manual-upgrade ] ||
       [ -f /system/bin/.hapaneld-helper-manual-upgrade ] ||
       [ -f /data/adb/hapaneld/.helper-manual-upgrade.marker ]; then
    echo ACTIVE_MANUAL_TRANSACTION; exit 75
  elif app_replacement_custody_present; then
    echo APP_REPLACEMENT_HOLD; exit 75
  fi

  mount -o rw,remount / 2>/dev/null
  mount -o rw,remount /system 2>/dev/null
  mount -o rw,remount /vendor 2>/dev/null
  mkdir -p /data/local /data/adb/hapaneld || { echo TARGET_DIR_FAIL; exit 1; }
  [ '"$INSTALL_KIND"' != systemless ] || mkdir -p /data/adb/service.d ||
    { echo TARGET_DIR_FAIL; exit 1; }
  chown 0:0 /data/adb/hapaneld || { echo TARGET_DIR_FAIL; exit 1; }
  chmod 700 /data/adb/hapaneld || { echo TARGET_DIR_FAIL; exit 1; }

  file_hash() {
    actual=$(sha256sum "$1" 2>/dev/null || toybox sha256sum "$1" 2>/dev/null) || return 1
    printf %s "${actual%% *}"
  }
  candidate='"$CANONICAL_CANDIDATE_PATH"'
  registration='"$REGISTRATION_PATH"'
  registration_staged='"$REGISTRATION_STAGING_PATH"'
  cp '"$PROBE_STAGING_PATH"' "$candidate" || { echo CP_FAIL; exit 1; }
  chown 0:0 "$candidate" || exit 1
  chmod 700 "$candidate" || exit 1
  [ "$(file_hash "$candidate")" = '"$BIN_SHA256"' ] || { echo HASH_FAIL; exit 1; }
  case '"$INSTALL_KIND"' in
    system|vendor) registration_source='"$RC_STAGING_PATH"' ;;
    systemless) registration_source='"$SVC_STAGING_PATH"' ;;
    *) exit 1 ;;
  esac
  cp "$registration_source" "$registration_staged" || { echo SERVICE_FAIL; exit 1; }
  chown 0:0 "$registration_staged" || exit 1
  chmod '"$REGISTRATION_MODE"' "$registration_staged" || exit 1
  [ "$(file_hash "$registration_staged")" = '"$SERVICE_SHA256"' ] ||
    { echo SERVICE_HASH_FAIL; exit 1; }

  marker='"$MANUAL_V3_MARKER"'
  snapshot() {
    name=$1; live=$2; recovery=/data/local/.hapaneld-helper-manual-'"$TRANSACTION_ID"'.recovery-"$name"
    rm -f "$recovery"
    if [ -e "$live" ] || [ -L "$live" ]; then
      [ -f "$live" ] && [ ! -L "$live" ] || return 1
      cp -p "$live" "$recovery" || return 1
      chown 0:0 "$recovery" || return 1
      chmod 600 "$recovery" || return 1
      expected=$(file_hash "$recovery") || return 1
      [ "$(file_hash "$live")" = "$expected" ] || return 1
      echo "$name=1" >> "$marker.new"
      echo "$name"_SHA256="$expected" >> "$marker.new"
    else
      echo "$name=0" >> "$marker.new"
      echo "$name"_SHA256=- >> "$marker.new"
    fi
  }
  rm -f "$marker.new"
  lease_boot=$(cat /proc/sys/kernel/random/boot_id 2>/dev/null) || exit 1
  lease_uptime=$(cut -d. -f1 /proc/uptime 2>/dev/null) || exit 1
  echo "$lease_uptime" | grep -Eq ^[0-9]+$ || exit 1
  lease_until=$((lease_uptime + '"$MANUAL_LEASE_SECONDS"'))
  echo JOURNAL_VERSION=3 > "$marker.new"
  echo JOURNAL_SCOPE=HELPER_ONLY >> "$marker.new"
  echo REGISTRATION_KIND='"$INSTALL_KIND"' >> "$marker.new"
  echo TRANSACTION_ID='"$TRANSACTION_ID"' >> "$marker.new"
  echo TARGET_BUILD_ID='"$BUILD_ID"' >> "$marker.new"
  echo TARGET_HELPER_SHA256='"$BIN_SHA256"' >> "$marker.new"
  echo TARGET_SERVICE_SHA256='"$SERVICE_SHA256"' >> "$marker.new"
  echo SWAP_PHASE=PREPARED >> "$marker.new"
  echo LEASE_BOOT_ID="$lease_boot" >> "$marker.new"
  echo LEASE_UNTIL_UPTIME="$lease_until" >> "$marker.new"
  snapshot LIVE_CANONICAL /data/local/hapaneld-helper || exit 1
  snapshot LIVE_SYSTEM_BIN /system/bin/hapaneld-helper || exit 1
  snapshot LIVE_SYSTEM_RC /system/etc/init/hapaneld-helper.rc || exit 1
  snapshot LIVE_VENDOR_RC /vendor/etc/init/hapaneld-helper.rc || exit 1
  snapshot LIVE_SYSTEMLESS_BIN /data/adb/hapaneld/hapaneld-helper || exit 1
  snapshot LIVE_SYSTEMLESS_SERVICE /data/adb/service.d/hapaneld-helper.sh || exit 1
  snapshot LIVE_LEGACY_BIN /system/bin/hapaneld-ledd || exit 1
  snapshot LIVE_LEGACY_RC /system/etc/init/hapaneld-ledd.rc || exit 1
  chown 0:0 "$marker.new" || exit 1
  chmod 600 "$marker.new" || exit 1
  sync || exit 1
  mv -f "$marker.new" "$marker" || exit 1
  sync || exit 1

  stop hapaneld_helper 2>/dev/null
  stop hapaneld_ledd 2>/dev/null
  pkill -x hapaneld-helper 2>/dev/null
  pkill -x hapaneld-ledd 2>/dev/null
  attempt=0
  while pidof hapaneld-helper >/dev/null 2>&1 || pidof hapaneld-ledd >/dev/null 2>&1; do
    attempt=$((attempt + 1)); [ "$attempt" -lt 10 ] || exit 1; sleep 1
  done

  # The incumbent can finish an APK-coupled R1 replacement while this installer is waiting for it
  # to retire. Fence the complete recorded topology after retirement: a PREPARED journal whose live
  # identity has changed is evidence for another writer, never authority to overwrite or roll back
  # that writer with our now-stale snapshots.
  recorded_live() {
    name=$1; live=$2
    flag=$(sed -n s/^"$name"=//p "$marker")
    expected=$(sed -n s/^"$name"_SHA256=//p "$marker")
    case "$flag" in
      0) [ "$expected" = - ] && [ ! -e "$live" ] && [ ! -L "$live" ] ;;
      1) printf %s "$expected" | grep -Eq ^[0-9a-f]{64}$ &&
         [ -f "$live" ] && [ ! -L "$live" ] && [ "$(file_hash "$live")" = "$expected" ] ;;
      *) return 1 ;;
    esac
  }
  recorded_live LIVE_CANONICAL /data/local/hapaneld-helper &&
  recorded_live LIVE_SYSTEM_BIN /system/bin/hapaneld-helper &&
  recorded_live LIVE_SYSTEM_RC /system/etc/init/hapaneld-helper.rc &&
  recorded_live LIVE_VENDOR_RC /vendor/etc/init/hapaneld-helper.rc &&
  recorded_live LIVE_SYSTEMLESS_BIN /data/adb/hapaneld/hapaneld-helper &&
  recorded_live LIVE_SYSTEMLESS_SERVICE /data/adb/service.d/hapaneld-helper.sh &&
  recorded_live LIVE_LEGACY_BIN /system/bin/hapaneld-ledd &&
  recorded_live LIVE_LEGACY_RC /system/etc/init/hapaneld-ledd.rc || {
    echo LIVE_IDENTITY_CHANGED
    exit 76
  }

  replacement_reply=$("$candidate" --replacement-safe 2>/dev/null)
  replacement_status=$?
  if [ "$replacement_status" -ne 0 ] || [ "$replacement_reply" != REPLACE_SAFE ]; then
    if [ "$replacement_status" -eq 3 ] && [ "$replacement_reply" = GUARD_ARMED ]; then
      # Native status 3 covers either Guard state or the R1 replacement namespace. Fixed R1 custody
      # makes rollback unsafe; without custody this is ordinary Guard state and the exact recorded
      # topology must be restored immediately because the incumbent supervisor was already stopped.
      if app_replacement_custody_present; then
        echo REPLACEMENT_AUTHORITY_ACTIVE
        exit 73
      fi
      echo GUARD_ARMED_ROLLBACK
      exit 78
    fi
    echo REPLACEMENT_SAFETY_UNKNOWN
    exit 74
  fi

  sed s/^SWAP_PHASE=PREPARED$/SWAP_PHASE=MUTATING/ "$marker" > "$marker.phase" || exit 1
  chown 0:0 "$marker.phase" || exit 1
  chmod 600 "$marker.phase" || exit 1
  sync || exit 1
  mv -f "$marker.phase" "$marker" || exit 1
  sync || exit 1

  rm -f /system/bin/hapaneld-helper /data/adb/hapaneld/hapaneld-helper \
    /system/bin/hapaneld-ledd /system/etc/init/hapaneld-ledd.rc \
    /system/etc/init/hapaneld-helper.rc /vendor/etc/init/hapaneld-helper.rc \
    /data/adb/service.d/hapaneld-helper.sh || exit 1
  [ ! -e /system/bin/hapaneld-helper ] && [ ! -L /system/bin/hapaneld-helper ] &&
  [ ! -e /data/adb/hapaneld/hapaneld-helper ] && [ ! -L /data/adb/hapaneld/hapaneld-helper ] &&
  [ ! -e /system/bin/hapaneld-ledd ] && [ ! -L /system/bin/hapaneld-ledd ] &&
  [ ! -e /system/etc/init/hapaneld-ledd.rc ] && [ ! -L /system/etc/init/hapaneld-ledd.rc ] &&
  [ ! -e /system/etc/init/hapaneld-helper.rc ] && [ ! -L /system/etc/init/hapaneld-helper.rc ] &&
  [ ! -e /vendor/etc/init/hapaneld-helper.rc ] && [ ! -L /vendor/etc/init/hapaneld-helper.rc ] &&
  [ ! -e /data/adb/service.d/hapaneld-helper.sh ] && [ ! -L /data/adb/service.d/hapaneld-helper.sh ] || exit 1
  rm -f /data/local/hapaneld-helper || exit 1
  mv -f "$candidate" /data/local/hapaneld-helper || { echo LIVE_MV_FAIL; exit 1; }
  chown 0:0 /data/local/hapaneld-helper || exit 1
  chmod 700 /data/local/hapaneld-helper || exit 1
  [ "$(file_hash /data/local/hapaneld-helper)" = '"$BIN_SHA256"' ] || exit 1
  mv -f "$registration_staged" "$registration" || { echo SERVICE_MV_FAIL; exit 1; }
  chown 0:0 "$registration" || exit 1
  chmod '"$REGISTRATION_MODE"' "$registration" || exit 1
  [ "$(file_hash "$registration")" = '"$SERVICE_SHA256"' ] || exit 1
  sed s/^SWAP_PHASE=MUTATING$/SWAP_PHASE=TARGET/ "$marker" > "$marker.phase" || exit 1
  chown 0:0 "$marker.phase" || exit 1
  chmod 600 "$marker.phase" || exit 1
  sync || exit 1
  mv -f "$marker.phase" "$marker" || exit 1
  sync || exit 1

  /data/local/hapaneld-helper --supervise >/dev/null 2>&1 &
  echo INSTALL_OK
' 2>&1)" || true
echo "$out2" | sed 's/^/   /'
case "$out2" in
  *APP_REPLACEMENT_HOLD*)
    fail "app-managed root-helper replacement custody appeared while standalone installation was preparing" \
      "The fixed custody paths were preserved. No topology remount, candidate copy, recovery snapshot, journal, helper retirement, or rollback was attempted; re-run after the app replacement finishes." ;;
  *LIVE_IDENTITY_CHANGED*)
    fail "live helper topology changed after the standalone snapshot" \
      "Another helper replacement completed while the incumbent was retiring; no live file was overwritten and the stale snapshots were not rolled back." \
      "Recover the retained v3 journal after reconciling the current canonical helper topology." ;;
  *REPLACEMENT_AUTHORITY_ACTIVE*)
    fail "APK-coupled helper replacement custody refused standalone helper replacement" \
      "The staged candidate reported GUARD_ARMED and an exact fixed R1 custody path is present." \
      "No live file was overwritten and the PREPARED snapshots were not rolled back over that authority. Let R1 finish or reconcile its custody records before retrying." ;;
  *GUARD_ARMED_ROLLBACK*)
    if rollback_root_helper_v3 "$INSTALL_KIND" "$TRANSACTION_ID" "$BUILD_ID" "$BIN_SHA256" "$SERVICE_SHA256"; then
      fail "Guard DB authority is armed; the prior helper topology was restored" \
        "Complete or cancel the Guard plan before retrying standalone helper replacement."
    else
      guard_rollback_status=$?
    fi
    if [ "$guard_rollback_status" -eq 3 ]; then
      fail "APK-coupled helper replacement custody appeared while restoring the Guard-refused install" \
        "No further rollback publication was attempted. Let R1 finish or reconcile its custody records before retrying."
    fi
    fail "Guard DB authority is armed and the prior helper topology could not be verified" \
      "Recover the retained v3 journal before relying on privileged operations." ;;
  *ACTIVE_MANUAL_TRANSACTION*|*FOREIGN_PROVISION_TRANSACTION*|*TRANSACTION_BUSY*)
    fail "root-helper journal state changed while the standalone installer was running" \
      "No live helper files were replaced by this attempt. Re-run to recover the retained journal." ;;
esac
if ! printf '%s\n' "$out2" | grep -qx INSTALL_OK; then
  if rollback_root_helper_v3 "$INSTALL_KIND" "$TRANSACTION_ID" "$BUILD_ID" "$BIN_SHA256" "$SERVICE_SHA256"; then
    fail "canonical helper install failed; the prior helper topology was restored" \
      "Re-run after checking writable storage and registration permissions."
  fi
  fail "canonical helper install failed and rollback could not be verified" \
    "Recover the retained v3 journal before relying on privileged operations."
fi

if ! wait_for_helper_reply COMPANIONCAPS "COMPANIONCAPS 1 BACKUP RESTORE STATUS JOURNAL" "$INSTALL_KIND"; then
  if rollback_root_helper_v3 "$INSTALL_KIND" "$TRANSACTION_ID" "$BUILD_ID" "$BIN_SHA256" "$SERVICE_SHA256"; then
    fail "new helper failed its exact capability check; the prior helper was restored" \
      "Re-run after checking helper logs and available storage."
  fi
  fail "new helper failed its exact capability check and rollback could not be verified" \
    "Restore the helper manually before relying on privileged operations."
fi
if ! wait_for_helper_reply GUARDCAPS "OK GUARDCAPS 1 PREPARE DEFINE STREAM ACTION HEALTH REFUSAL STATUS EVIDENCE CANCEL RETIRE JOURNAL AUTONOMOUS SUPERVISED TERMINAL_RETIRE" "$INSTALL_KIND"; then
  if rollback_root_helper_v3 "$INSTALL_KIND" "$TRANSACTION_ID" "$BUILD_ID" "$BIN_SHA256" "$SERVICE_SHA256"; then
    fail "new helper failed its exact autonomous-supervision capability check; the prior helper was restored" "Re-run with the current helper build."
  fi
  fail "new helper failed its exact autonomous-supervision capability check and rollback could not be verified" "Recover the retained v3 journal before relying on Guard DB operations."
fi
if ! wait_for_helper_reply GUARDSTATUS "OK GUARDSTATUS 0 EMPTY NONE NONE NONE NONE 0 0 0 NONE NONE 0 0" "$INSTALL_KIND"; then
  if rollback_root_helper_v3 "$INSTALL_KIND" "$TRANSACTION_ID" "$BUILD_ID" "$BIN_SHA256" "$SERVICE_SHA256"; then
    fail "new helper did not report the exact empty Guard DB status; the prior helper was restored" "Do not replace a helper while Guard DB authority is armed."
  fi
  fail "new helper Guard DB status was not exact and rollback could not be verified" "Recover the retained v3 journal before relying on Guard DB operations."
fi
if ! wait_for_helper_reply BUILDID "BUILDID $BUILD_ID" "$INSTALL_KIND"; then
  if rollback_root_helper_v3 "$INSTALL_KIND" "$TRANSACTION_ID" "$BUILD_ID" "$BIN_SHA256" "$SERVICE_SHA256"; then
    fail "new helper failed its exact build-identity check; the prior helper was restored" \
      "Rebuild with ./helper/build.sh and retry."
  fi
  fail "new helper failed its exact build-identity check and rollback could not be verified" \
    "Restore the helper manually before relying on privileged operations."
fi
stop_manual_lease_guard
if ! manual_lease_guard_succeeded; then
  if rollback_root_helper_v3 "$INSTALL_KIND" "$TRANSACTION_ID" "$BUILD_ID" "$BIN_SHA256" "$SERVICE_SHA256"; then
    fail "the standalone root-helper transaction lease could not be renewed; the prior helper was restored" \
      "Re-run after checking adb/root stability and competing installer activity."
  fi
  fail "the standalone root-helper transaction lease failed and rollback could not be verified" \
    "Do not reboot yet. Recover the retained helper journal before relying on privileged operations."
fi
if ! commit_root_helper_upgrade_v3 "$INSTALL_KIND" && \
   ! commit_root_helper_upgrade_v3 "$INSTALL_KIND"; then
  fail "new helper passed its checks, but the durable commit point could not be confirmed" \
    "Do not reboot yet. Check panel storage and permissions, then re-run this installer; it will reconcile any retained journal."
fi
echo "   helper running with Companion-data protocol 1"

echo "==> done. Reboot the panel when convenient to confirm the daemon auto-starts."
