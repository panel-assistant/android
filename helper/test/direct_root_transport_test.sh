#!/usr/bin/env bash
# Behavioural coverage for the standalone root-helper installer on a DIRECT-ROOT panel (adbd already
# running as root, no su), across both transports a real adbd can serve.
#
# Why this file exists. Every prior installer test drove the su-wrapped dialect, so the direct-root
# path — the one an `adb root` panel actually takes — was never executed end to end. It also never
# varied the TRANSPORT. An adbd that does not advertise the `shell_v2` feature serves `shell:` through
# a PTY unconditionally, so the line discipline rewrites every device newline as CRLF before the host
# sees it. The installer classifies panel state by exact token, so on such a panel the panel's honest
# `NO_STALE_TRANSACTION` arrives as `NO_STALE_TRANSACTION\r` and matches nothing.
#
# CORRECTION, and it matters because this file used to claim otherwise: that transport is NOT what the
# SMT1019 reporter of #106 hit. Their `adb features` lists `shell_v2`, so their replies were never
# CRLF, and the report's helper-persistence symptom turned out to be a genuinely read-only /system
# with no root manager — a different problem with its own answer. The CRLF handling is still correct
# and still the right place for it, but it is hardening for a transport nobody has yet been shown to
# be on, not the explanation of that report. Kept because an exact-token classifier that trusts the
# transport is wrong regardless of who is currently affected.
#
# What this proves and what it does not. It drives the real installer and asserts which branch it
# takes and what it tells the operator. Device-side file identity, permissions and operation ordering
# are not modelled here — those stay under install_daemon_security_test.sh, which reads them from the
# source. Surviving a real reboot needs the reporter's hardware and is post-composition acceptance.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
FIXTURES="$ROOT/scripts/tests/fixtures"
INSTALLER="$ROOT/helper/install-daemon.sh"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

mkdir -p "$TMP/dist/arm64-v8a" "$TMP/bin"
# The staged binary is the only authority on which helper must be answering the socket afterwards, so
# this mock dist stamps its own identity — deliberately NOT the checkout's. That is what makes every
# run below a standing proof: `HAPANELD_HELPER_DIST_DIR` points at a foreign build, and when the two
# identities agree (as they do under the documented ./helper/build.sh flow) a stale-expectation defect
# is invisible.
FOREIGN_BUILD_ID=facade00facade00facade00facade00facade00facade00facade00facade00
CHECKOUT_BUILD_ID="$(PATH=/usr/bin:/bin "$ROOT/helper/source-id.sh")"
if [ "$FOREIGN_BUILD_ID" = "$CHECKOUT_BUILD_ID" ]; then
  echo "the foreign dist identity must differ from the checkout identity or this file proves nothing" >&2
  exit 1
fi
printf 'mock helper\nBUILDID %s\n' "$FOREIGN_BUILD_ID" > "$TMP/dist/arm64-v8a/hapaneld-helper"

# One wrapper, two jobs: inject answers the shared fixture has no reason to model (a journal reply
# that is not a known state, a stale journal to recover), and otherwise delegate. Injected replies go
# through the same CRLF filter as everything else, because a broken transport does not politely skip
# the interesting cases.
cat > "$TMP/bin/adb" <<'ADBEOF'
#!/usr/bin/env bash
set -u
command_text="$*"
emit() {
  if [ "${MOCK_SHELL_CRLF:-0}" = 1 ]; then printf '%s\r\n' "$1"; else printf '%s\n' "$1"; fi
}
if [ -n "${MOCK_JOURNAL_INJECT:-}" ] &&
   printf '%s' "$command_text" 2>/dev/null | grep -Fq inspect_manual_journal; then
  printf 'adb %s\n' "$*" >> "${MOCK_CALL_LOG:?}"
  [ "$MOCK_JOURNAL_INJECT" = __silence__ ] || emit "$MOCK_JOURNAL_INJECT"
  exit 0
fi
if [ -n "${MOCK_LAYOUT_INJECT:-}" ] &&
   printf '%s' "$command_text" 2>/dev/null | grep -Fq 'touch /system/.rw_probe'; then
  printf 'adb %s\n' "$*" >> "${MOCK_CALL_LOG:?}"
  emit "$MOCK_LAYOUT_INJECT"
  exit 0
fi
exec "${REAL_ADB_FIXTURE:?}" "$@"
ADBEOF
chmod +x "$TMP/bin/adb"

export PATH="$TMP/bin:/usr/bin:/bin"
export REAL_ADB_FIXTURE="$FIXTURES/adb"
export MOCK_TARGET="panel.test:5555"
export MOCK_CALL_LOG="$TMP/calls.log"
export MOCK_STATE_DIR="$TMP/state"
export MOCK_ABI=arm64-v8a
export MOCK_HELPER_BUILD_ID="$FOREIGN_BUILD_ID"
export HAPANELD_HELPER_DIST_DIR="$TMP/dist"
# A direct-root panel: adbd is already root, so probe_su resolves SU_FORM=shell and every privileged
# block is sent bare rather than wrapped in su.
export MOCK_ADB_ROOT=1
export MOCK_ROOT=1
export MOCK_JOURNAL_INJECT=""
export MOCK_LAYOUT_INJECT=""
mkdir -p "$MOCK_STATE_DIR"

failures=0
run_installer() {
  : > "$MOCK_CALL_LOG"
  rm -rf "$MOCK_STATE_DIR"; mkdir -p "$MOCK_STATE_DIR"
  set +e
  bash "$INSTALLER" "$MOCK_TARGET" > "$TMP/out.txt" 2>&1
  INSTALLER_STATUS=$?
  set -e
}
check() {
  local what="$1"
  if [ "$2" = ok ]; then
    printf '  ok   %s\n' "$what"
  else
    printf '  FAIL %s\n' "$what"
    sed 's/^/       | /' "$TMP/out.txt"
    failures=$((failures + 1))
  fi
}
expect_succeeds() {
  local what="$1"
  [ "$INSTALLER_STATUS" = 0 ] && check "$what" ok || check "$what" bad
}
expect_refuses() {
  local what="$1" phrase="$2"
  if [ "$INSTALLER_STATUS" != 0 ] && grep -Fq "$phrase" "$TMP/out.txt"; then
    check "$what" ok
  else
    check "$what" bad
  fi
}

for transport in shell_v2 pty; do
  case "$transport" in
    shell_v2) export MOCK_SHELL_CRLF=0 ;;
    pty)      export MOCK_SHELL_CRLF=1 ;;
  esac
  echo "== direct-root panel, $transport transport =="

  # 1. The whole point: a rooted panel with a writable /system installs and registers a boot service.
  #    Under the PTY transport this is exactly the run that refused at classification before the fix.
  export MOCK_SYSTEM_WRITABLE=1
  run_installer
  expect_succeeds "installs to /system and reports completion"
  grep -Fq 'hapaneld-helper.rc' "$MOCK_CALL_LOG" &&
    check "stages the init .rc, so the helper is registered for boot" ok ||
    check "stages the init .rc, so the helper is registered for boot" bad
  grep -Fq 'done. Reboot the panel' "$TMP/out.txt" &&
    check "tells the operator a reboot will confirm auto-start" ok ||
    check "tells the operator a reboot will confirm auto-start" bad
  # The overridden dist is a foreign build. Its own stamped identity must be what the journal records
  # and what the replacement daemon is required to answer; the checkout's identity must appear nowhere.
  grep -Fq "TARGET_BUILD_ID=$FOREIGN_BUILD_ID" "$MOCK_CALL_LOG" &&
    check "judges the foreign staged binary by its own stamped identity" ok ||
    check "judges the foreign staged binary by its own stamped identity" bad
  grep -Fq "$CHECKOUT_BUILD_ID" "$MOCK_CALL_LOG" &&
    check "never carries the checkout's source identity onto the panel" bad ||
    check "never carries the checkout's source identity onto the panel" ok

  # 2. Read-only /system with a real systemless runner must take the service.d route, not dead-end.
  export MOCK_SYSTEM_WRITABLE=0 MOCK_SYSTEMLESS_RUNNER=1
  run_installer
  expect_succeeds "falls back to the systemless service.d route"
  grep -Fq 'hapaneld-helper.sh' "$MOCK_CALL_LOG" &&
    check "stages the service.d boot script" ok ||
    check "stages the service.d boot script" bad

  # 3. A panel that genuinely has neither route keeps its specific, actionable cause.
  export MOCK_SYSTEM_WRITABLE=0 MOCK_SYSTEMLESS_RUNNER=0
  run_installer
  expect_refuses "names the real cause when there is genuinely no boot route" \
    "could not determine where the canonical helper can be boot-registered"
  # A rooted panel may need its existing overlay mounted or may still have verity enabled, rather than
  # needing another root manager. Offer the non-rebooting probe first and keep the post-reboot command
  # executable on its own line.
  # Order matters more than presence. A panel that already carries a scratch overlay needs only
  # `adb remount`; leading with disable-verity prescribes a reboot it does not need, and a reboot is
  # the one step that can strand a PIN-protected panel in Direct Boot.
  rl=$(grep -n 'remount$' "$TMP/out.txt" | head -1 | cut -d: -f1)
  vl=$(grep -n 'disable-verity' "$TMP/out.txt" | head -1 | cut -d: -f1)
  post_rl=$(grep -n 'remount$' "$TMP/out.txt" | tail -1 | cut -d: -f1)
  if [ -n "$rl" ] && [ -n "$vl" ] && [ -n "$post_rl" ] &&
     [ "$rl" -lt "$vl" ] && [ "$vl" -lt "$post_rl" ] &&
     ! grep -Eq 'disable-verity.*remount' "$TMP/out.txt" &&
     grep -Fq 'REBOOTS the panel' "$TMP/out.txt" &&
     grep -Fq 'unlock any PIN-protected panel' "$TMP/out.txt"; then
    check "offers the reboot-free remount first, then the rebooting fallback with its unlock step" ok
  else
    check "offers the reboot-free remount first, then the rebooting fallback with its unlock step" bad
  fi

  # 3b. An answer we cannot read says nothing about /system, so the refusal must not claim it did —
  #     telling this operator to go and install Magisk would send them to fix the wrong machine.
  export MOCK_SYSTEM_WRITABLE=1 MOCK_SYSTEMLESS_RUNNER=1
  export MOCK_LAYOUT_INJECT="MOUNT_PROBE_CONFUSED"
  run_installer
  expect_refuses "refuses an unreadable capability probe without inventing a cause" \
    "could not determine where the canonical helper can be boot-registered"
  grep -Fq 'read-only /system and no verified systemless boot-service runner' "$TMP/out.txt" &&
    check "does not claim read-only /system when it never learned that" bad ||
    check "does not claim read-only /system when it never learned that" ok
  export MOCK_LAYOUT_INJECT=""

  # 4. Unknown state still refuses — the fix normalises the transport, it does not widen acceptance.
  export MOCK_JOURNAL_INJECT="SOMETHING_ELSE_ENTIRELY"
  run_installer
  expect_refuses "still refuses a journal state it does not recognise" \
    "could not determine the root-helper recovery state"
  grep -Fq 'the panel answered: SOMETHING_ELSE_ENTIRELY' "$TMP/out.txt" &&
    check "names what the panel actually answered" ok ||
    check "names what the panel actually answered" bad

  # 4b. An answer carrying invisible bytes must SAY so. Reporting only the printable part would render
  #     it identical to a valid answer, deleting the one clue that identifies what is really wrong —
  #     and this line exists precisely to carry that clue back in a field report.
  export MOCK_JOURNAL_INJECT="ODD$(printf '\033')[0mSTATE"
  run_installer
  expect_refuses "refuses an answer carrying invisible bytes" \
    "could not determine the root-helper recovery state"
  grep -Fq 'plus non-printing characters' "$TMP/out.txt" &&
    check "reports invisible bytes instead of quietly deleting them" ok ||
    check "reports invisible bytes instead of quietly deleting them" bad

  # 5. A silent panel is unknown state too, and must say so rather than blame adb and root.
  export MOCK_JOURNAL_INJECT="__silence__"
  run_installer
  expect_refuses "refuses when the panel answers nothing" \
    "could not determine the root-helper recovery state"
  grep -Fq 'the panel answered nothing' "$TMP/out.txt" &&
    check "distinguishes silence from an unrecognised answer" ok ||
    check "distinguishes silence from an unrecognised answer" bad

  # 6. A retained journal from an interrupted run is recovered rather than ignored or overwritten.
  export MOCK_JOURNAL_INJECT="STALE_SYSTEM_TRANSACTION 0123456789abcdef0123456789abcdef $MOCK_HELPER_BUILD_ID $(sha256sum "$TMP/dist/arm64-v8a/hapaneld-helper" | awk '{print $1}') $(sha256sum "$ROOT/helper/hapaneld-helper.rc" | awk '{print $1}')"
  run_installer
  grep -Fq 'could not determine the root-helper recovery state' "$TMP/out.txt" &&
    check "a stale journal is recovered, never misread as unknown state" bad ||
    check "a stale journal is recovered, never misread as unknown state" ok

  # 7. Another installer holding the lease must still win the race.
  export MOCK_JOURNAL_INJECT=""
  run_installer_with_active() {
    : > "$MOCK_CALL_LOG"
    rm -rf "$MOCK_STATE_DIR"; mkdir -p "$MOCK_STATE_DIR"
    : > "$MOCK_STATE_DIR/manual-helper-transaction"
    set +e
    bash "$INSTALLER" "$MOCK_TARGET" > "$TMP/out.txt" 2>&1
    INSTALLER_STATUS=$?
    set -e
  }
  run_installer_with_active
  expect_refuses "refuses while another installer owns an active lease" \
    "owns an active transaction lease"
done

if [ "$failures" != 0 ]; then
  echo "direct-root transport tests FAILED ($failures)" >&2
  exit 1
fi
echo "direct-root transport tests passed"
