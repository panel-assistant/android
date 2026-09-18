#!/usr/bin/env bash
# Fleet update — update several ha-paneld panels in one go. Use this instead of a raw
# `adb install -r` loop: `adb install -r` leaves the app in Android's "stopped" state, so a plain
# install loop leaves panels installed-but-DEAD (their entities go `unavailable` in HA until each is
# launched). This wraps scripts/provision.sh, which installs AND launches AND verifies every panel.
#
# The APK is downloaded once (for --latest) and reused for the whole fleet, rather than re-fetched
# per panel.
#
# A release publishes one APK per installable identity, and a panel crosses the application-id change
# by running both for one handover. Each panel that still carries the old package therefore gets the
# bridge updated in place and started first, then the successor installed and provisioned; the
# successor performs the handover and removes the bridge, and this script verifies that it did.
#
# Usage:
#   scripts/update-fleet.sh [--jobs N] [provision-args...] -- <ip|ip:port> [<ip> ...]
#   scripts/update-fleet.sh --jobs 2 --latest -- 192.168.1.10 192.168.1.11:5555
#   scripts/update-fleet.sh --apk path/to.apk -- 192.168.1.10 192.168.1.11
#   scripts/update-fleet.sh --apk successor.apk --bridge-apk bridge.apk -- 192.168.1.10
#   printf '%s\n' 192.168.1.10 192.168.1.11 | scripts/update-fleet.sh --latest
#
# Panels are listed after `--` and/or on stdin (one per line). Except for this wrapper's `--jobs N`,
# args before `--` pass through to every provision.sh call (e.g. --apk PATH, --mqtt ...). Options that
# describe a single panel — --reset-config, --export, --id, --restore — are refused up front rather than
# multiplied; --restore-fleet is the fleet-safe restore. At most four panels run concurrently by
# default; HAPANELD_FLEET_JOBS or --jobs can select 1..32 workers.
set -euo pipefail
umask 077

if [ -t 1 ]; then B=$'\033[1m'; D=$'\033[2m'; X=$'\033[0m'; RED=$'\033[31m'; GRN=$'\033[32m'; YEL=$'\033[33m'
else B=; D=; X=; RED=; GRN=; YEL=; fi

HERE="$(cd "$(dirname "$0")" && pwd)"
PROVISION="$HERE/provision.sh"
REPO="panel-assistant/android"
PKG="io.panelassistant.android"
LEGACY_PKG="io.github.maxlyth.hapaneld"
# How long a panel is given to start the updated bridge, and to complete the handover and remove it.
BRIDGE_START_TIMEOUT="${HAPANELD_FLEET_BRIDGE_START_SECONDS:-60}"
BRIDGE_REMOVAL_TIMEOUT="${HAPANELD_FLEET_MIGRATION_SECONDS:-180}"
valid_release_tag() { printf '%s\n' "$1" | grep -Eq '^v[0-9]+\.[0-9]+\.[0-9]+(-[0-9A-Za-z][0-9A-Za-z.-]*)?$'; }
release_asset_url() { printf 'https://github.com/%s/releases/download/%s/%s\n' "$REPO" "$1" "$2"; }
release_apk_name() { printf 'panel-assistant-%s-manual-setup-required.apk\n' "$1"; }
# The bridge keeps the historical asset name so a shipped updater still resolves it.
bridge_apk_name() { printf 'ha-paneld-%s-manual-setup-required.apk\n' "$1"; }
release_apk_url() { release_asset_url "$1" "$(release_apk_name "$1")"; }
bridge_apk_url() { release_asset_url "$1" "$(bridge_apk_name "$1")"; }
[ -f "$PROVISION" ] || { echo "${RED}provision.sh not found next to this script${X}" >&2; exit 1; }
TEMP_PATHS=()
cleanup() { local path; for path in "${TEMP_PATHS[@]}"; do rm -rf "$path"; done; }
pids=()
stop_workers() {
  local pid
  for pid in "${pids[@]}"; do kill -TERM "$pid" >/dev/null 2>&1 || true; done
  for pid in "${pids[@]}"; do wait "$pid" >/dev/null 2>&1 || true; done
  pids=()
}
handle_signal() {
  local status="$1"
  trap - INT TERM
  stop_workers
  exit "$status"
}
trap cleanup EXIT
trap 'handle_signal 130' INT
trap 'handle_signal 143' TERM

# Split args at `--` into pass-through provision args and the panel list. Consume the fleet-only jobs
# option here so provision.sh never sees it.
PARGS=(); PANELS=(); seen_dd=0
JOBS="${HAPANELD_FLEET_JOBS:-4}"
while [ "$#" -gt 0 ]; do
  if [ "$seen_dd" = 0 ]; then
    case "$1" in
      --) seen_dd=1; shift; continue ;;
      --jobs)
        [ "$#" -ge 2 ] || { echo "${RED}--jobs needs a value from 1 to 32${X}" >&2; exit 2; }
        JOBS="$2"; shift 2; continue
        ;;
      --jobs=*) JOBS="${1#--jobs=}"; shift; continue ;;
    esac
    PARGS+=("$1")
  else
    PANELS+=("$1")
  fi
  shift
done
case "$JOBS" in
  ''|*[!0-9]*|0) echo "${RED}--jobs must be a whole number from 1 to 32${X}" >&2; exit 2 ;;
esac
# Some provision.sh options describe ONE panel, and every pass-through arg is forwarded verbatim to
# every worker below. Multiplied, they either collide on a single shared resource or apply one panel's
# data to all of them — and because each worker still exits 0, the fleet reports all-OK while the damage
# is silent. Refuse them here, before any worker starts, naming the per-panel alternative. Note what is
# NOT listed: --restore-fleet exists precisely because it carries only portable, non-secret keys, so
# multiplying it across panels is its intended use.
for a in "${PARGS[@]}"; do
  guidance=""
  case "$a" in
    # Workers run with stdin closed, so the per-panel confirmation could only be satisfied by an
    # environment variable — which one export would then apply to every panel at once.
    --reset-config) guidance="erase one panel at a time with scripts/provision.sh" ;;
    # Every worker would publish its export onto the one destination, so only the last panel to finish
    # would still be represented there. Worse, naming the file suppresses each panel's own automatic
    # pre-upgrade settings export, so the panels overwritten here would have no backup anywhere.
    --export) guidance="every panel would overwrite the same file, and naming it also cancels each panel's own automatic pre-upgrade settings export — omit it to keep those per-panel backups, or export one panel at a time with scripts/provision.sh" ;;
    # A panel id is that panel's identity; one value cannot name a fleet.
    --id) guidance="a panel id names one panel — provision each panel separately with its own --id" ;;
    # --restore applies device-scoped keys, which belong to the panel they were exported from.
    --restore) guidance="it imports device-scoped keys belonging to one panel — use --restore-fleet to apply only the portable settings across a fleet, or restore one panel at a time with scripts/provision.sh" ;;
  esac
  if [ -n "$guidance" ]; then
    echo "${RED}$a is not available for fleet updates${X} — $guidance" >&2
    exit 2
  fi
done
[ "$JOBS" -le 32 ] || { echo "${RED}--jobs must be a whole number from 1 to 32${X}" >&2; exit 2; }
# Panels may also arrive on stdin (one per line) — but ONLY when none were given as args, so a
# non-tty stdin (pipelines, CI) can't clobber an explicit `-- <ip> …` list.
if [ "${#PANELS[@]}" -eq 0 ] && [ ! -t 0 ]; then
  while IFS= read -r line; do line="${line%%#*}"; line="$(echo "$line" | tr -d '[:space:]')"; [ -n "$line" ] && PANELS+=("$line"); done
fi
[ "${#PANELS[@]}" -gt 0 ] || { echo "${RED}no panels given${X} (after -- or on stdin)" >&2; exit 2; }

# Legacy literal credential flags are visible in this wrapper's original argv, but must not be
# multiplied into every worker. Normalize them once into owner-only files; provision.sh accepts the
# same file options directly for callers that avoid literal argv exposure altogether.
NORMALIZED_PARGS=()
FLEET_SECRET_DIR=""
seen_mqtt_pass=0; seen_ha_token=0; seen_ha_pass=0
for ((i=0; i<${#PARGS[@]}; i++)); do
  option="${PARGS[$i]}"
  case "$option" in
    --mqtt-pass|--mqtt-pass-file|--ha-token|--ha-token-file|--ha-pass|--ha-pass-file)
      [ $((i + 1)) -lt ${#PARGS[@]} ] || { echo "${RED}$option needs a value${X}" >&2; exit 2; }
      value="${PARGS[$((i + 1))]}"
      [ -n "$value" ] && [ "${value#--}" = "$value" ] || { echo "${RED}$option needs a value${X}" >&2; exit 2; }
      case "$option" in
        --mqtt-pass|--mqtt-pass-file) seen_var=seen_mqtt_pass; file_option=--mqtt-pass-file; filename=mqtt-password ;;
        --ha-token|--ha-token-file) seen_var=seen_ha_token; file_option=--ha-token-file; filename=ha-token ;;
        *) seen_var=seen_ha_pass; file_option=--ha-pass-file; filename=ha-password ;;
      esac
      [ "${!seen_var}" = 0 ] || { echo "${RED}credential source supplied more than once: $file_option${X}" >&2; exit 2; }
      printf -v "$seen_var" '%s' 1
      case "$option" in
        *-file) NORMALIZED_PARGS+=("$file_option" "$value") ;;
        *)
          if [ -z "$FLEET_SECRET_DIR" ]; then
            FLEET_SECRET_DIR="$(mktemp -d)"; chmod 700 "$FLEET_SECRET_DIR"; TEMP_PATHS+=("$FLEET_SECRET_DIR")
          fi
          secret_path="$FLEET_SECRET_DIR/$filename"
          printf '%s' "$value" > "$secret_path"; chmod 600 "$secret_path"
          NORMALIZED_PARGS+=("$file_option" "$secret_path")
          ;;
      esac
      value=""
      i=$((i + 1))
      ;;
    *) NORMALIZED_PARGS+=("$option") ;;
  esac
done
PARGS=("${NORMALIZED_PARGS[@]}")

# Normalize before parallel work starts. `panel` and `panel:5555` name the same adb endpoint; running
# both concurrently would race two install/config transactions against one device.
NORMALIZED=()
for p in "${PANELS[@]}"; do
  case "$p" in *:*) t="$p" ;; *) t="$p:5555" ;; esac
  duplicate=0
  for seen in "${NORMALIZED[@]}"; do [ "$seen" = "$t" ] && duplicate=1; done
  if [ "$duplicate" = 1 ]; then
    echo "${RED}duplicate panel target: $t${X} — list each panel only once" >&2
    exit 2
  fi
  NORMALIZED+=("$t")
done
PANELS=("${NORMALIZED[@]}")

# Resolve a single APK for the whole fleet. If --apk was passed through, reuse it; otherwise download
# the latest signed release ONCE and convert the pass-through args to --apk for every panel.
have_apk=0
apk_arg_count=0
want_prerelease=0
require_release_signer=0
for ((i=0; i<${#PARGS[@]}; i++)); do
  if [ "${PARGS[$i]}" = "--apk" ]; then have_apk=1; apk_arg_count=$((apk_arg_count + 1)); fi
done
[ "$apk_arg_count" -le 1 ] || { echo "${RED}--apk may be supplied only once${X}" >&2; exit 2; }
# `--bridge-apk PATH` names the legacy-id half of a locally sealed pair. It belongs to this wrapper
# alone and is removed from the pass-through args, because provision.sh installs the successor and
# knows nothing about the bridge. A release download resolves both halves from the release itself, so
# supplying one by hand alongside it would give the run two answers; that is refused rather than
# silently preferred. Without it a local --apk carries the successor only, exactly as before.
BRIDGE_APK=""
bridge_arg_count=0
KEEP=()
for ((i=0; i<${#PARGS[@]}; i++)); do
  case "${PARGS[$i]}" in
    --bridge-apk)
      bridge_arg_count=$((bridge_arg_count + 1))
      [ $((i + 1)) -lt ${#PARGS[@]} ] || { echo "${RED}--bridge-apk needs a path${X}" >&2; exit 2; }
      BRIDGE_APK="${PARGS[$((i + 1))]}"
      i=$((i + 1))
      ;;
    --bridge-apk=*)
      echo "${RED}--bridge-apk takes its path as the next argument, not --bridge-apk=PATH${X}" >&2; exit 2 ;;
    *) KEEP+=("${PARGS[$i]}") ;;
  esac
done
PARGS=(${KEEP[@]+"${KEEP[@]}"})
[ "$bridge_arg_count" -le 1 ] || { echo "${RED}--bridge-apk may be supplied only once${X}" >&2; exit 2; }
if [ -n "$BRIDGE_APK" ]; then
  [ "$have_apk" = 1 ] || { echo "${RED}--bridge-apk names the bridge beside a local --apk; a release download carries its own${X}" >&2; exit 2; }
  [ -s "$BRIDGE_APK" ] || { echo "${RED}bridge APK is missing or empty: $BRIDGE_APK${X}" >&2; exit 1; }
fi
for a in "${PARGS[@]}"; do case "$a" in --prerelease|--pre) want_prerelease=1 ;; esac; done
for a in "${PARGS[@]}"; do case "$a" in --require-release-signer) require_release_signer=1 ;; esac; done
if [ "$have_apk" = 0 ]; then
  require_release_signer=1
  dir="$(mktemp -d)"; TEMP_PATHS+=("$dir")
  if [ "$want_prerelease" = 1 ]; then channel="latest release, including pre-releases"; else channel="latest stable release"; fi
  echo "${B}⬇️  fetching $channel (once for the fleet)${X}"
  tag=""; asset=""; expected_url=""
  if [ "$want_prerelease" = 1 ]; then api="https://api.github.com/repos/$REPO/releases?per_page=100"; else api="https://api.github.com/repos/$REPO/releases/latest"; fi
  json="$(curl -fsSL --proto '=https' --proto-redir '=https' --connect-timeout 15 --max-time 30 "$api" 2>/dev/null || true)"
  if [ "$want_prerelease" = 1 ]; then
    # Split the GitHub release array at each top-level release URL, retain the first published
    # release record of either kind, then extract its tag and APK from that record only.
    record="$(printf '%s' "$json" | tr -d '\r\n' | \
      sed 's#{[[:space:]]*"url":[[:space:]]*"https://api.github.com/repos/'"$REPO"'/releases/\([0-9][0-9]*\)"#\
&#g' | \
      awk '/"draft":[[:space:]]*false/ && !found { print; found=1 }')"
  else
    record="$json"
  fi
  tag="$(printf '%s' "$record" | grep -o '"tag_name": *"[^"]*"' | head -1 | cut -d'"' -f4 || true)"
  # A release publishes one APK per installable identity, so each asset is taken by its exact
  # published URL rather than by being the first `.apk` in the record.
  urls="$(printf '%s' "$record" | grep -o '"browser_download_url": *"[^"]*\.apk"' | cut -d'"' -f4 || true)"
  if [ -n "$tag" ] && valid_release_tag "$tag"; then
    asset="$(release_apk_name "$tag")"
    expected_url="$(release_apk_url "$tag")"
    printf '%s\n' "$urls" | grep -Fxq "$expected_url" && curl -fsSL --proto '=https' --proto-redir '=https' --connect-timeout 15 --max-time 300 "$expected_url" -o "$dir/$asset" || true
    bridge_asset="$(bridge_apk_name "$tag")"
    bridge_expected_url="$(bridge_apk_url "$tag")"
    printf '%s\n' "$urls" | grep -Fxq "$bridge_expected_url" && curl -fsSL --proto '=https' --proto-redir '=https' --connect-timeout 15 --max-time 300 "$bridge_expected_url" -o "$dir/$bridge_asset" || true
  fi
  [ -n "$asset" ] && [ -s "$dir/$asset" ] && APK="$dir/$asset" || APK=""
  [ -n "$APK" ] || { echo "${RED}could not fetch the latest release APK from the expected GitHub release path${X}" >&2; exit 1; }
  [ -n "${bridge_asset:-}" ] && [ -s "$dir/$bridge_asset" ] && BRIDGE_APK="$dir/$bridge_asset" || BRIDGE_APK=""
  # A release published before, or after, the two-identity window carries no bridge asset. That is
  # only a problem for a panel that still runs the old application id, and each such panel says so
  # for itself below, so this is a warning rather than a refusal of the whole fleet.
  [ -n "$BRIDGE_APK" ] || echo "${YEL}⚠ this release publishes no bridge APK; a panel still on the old application id cannot be carried across by this run${X}" >&2
  # Strip channel selectors, then pin every panel to the exact one downloaded APK.
  NEW=(); for a in "${PARGS[@]}"; do case "$a" in --latest|--prerelease|--pre) ;; *) NEW+=("$a") ;; esac; done
  PARGS=("${NEW[@]}" --apk "$APK" --release-tag "$tag")
  echo "   ${GRN}✓${X} ${D}$(basename "$APK")${X}${tag:+ · $tag}"
fi

# Authenticate the one fleet artifact before starting any panel worker. Self-built fleets may use one
# consistent developer signer; official-release deployments add --require-release-signer and pin the
# public release-certificate fingerprint without exposing any private signing material.
if [ "$have_apk" = 1 ]; then
  APK=""
  for ((i=0; i<${#PARGS[@]}; i++)); do
    if [ "${PARGS[$i]}" = "--apk" ] && [ $((i + 1)) -lt ${#PARGS[@]} ]; then APK="${PARGS[$((i + 1))]}"; break; fi
  done
fi
[ -n "${APK:-}" ] && [ -s "$APK" ] || { echo "${RED}fleet APK is missing or empty: ${APK:-unspecified}${X}" >&2; exit 1; }
find_build_tool() {
  local name="$1" found="" root candidate
  found="$(command -v "$name" 2>/dev/null || true)"
  if [ -n "$found" ]; then printf '%s\n' "$found"; return 0; fi
  for root in "${ANDROID_HOME:-}" "${ANDROID_SDK_ROOT:-}"; do
    [ -n "$root" ] && [ -d "$root/build-tools" ] || continue
    for candidate in "$root"/build-tools/*/"$name"; do [ -x "$candidate" ] && found="$candidate"; done
  done
  [ -n "$found" ] && printf '%s\n' "$found"
}
release_tag=""
for ((i=0; i<${#PARGS[@]}; i++)); do
  if [ "${PARGS[$i]}" = "--release-tag" ] && [ $((i + 1)) -lt ${#PARGS[@]} ]; then release_tag="${PARGS[$((i + 1))]}"; break; fi
done
APKSIGNER="$(find_build_tool apksigner || true)"
AAPT="$(find_build_tool aapt || find_build_tool aapt2 || true)"
# apksigner stays unconditional, and that is deliberate. Beyond pinning the release certificate here,
# provision.sh compares the INSTALLED app's signer against the candidate's before replacing it, on
# every panel that already has the app. A signed checksum cannot stand in for that: it proves what the
# candidate is, and says nothing about what is already on the panel. Dropping the tool would drop a
# guard against a cross-signer replacement, which wipes app data.
[ -n "$APKSIGNER" ] || { echo "${RED}apksigner is required for fleet deployment${X}" >&2; exit 1; }
# aapt is different. Its only use below is reading the package name to compare against a constant, and
# for a release asset the signed checksum has already pinned the whole file — package name included —
# by hashing it. So it is required only for a local APK, which has no such anchor. This mirrors the
# policy provision.sh already applies, and it means a normal fleet deploy of an official release needs
# no Android build tool for the package check.
if [ -z "$AAPT" ] && [ -z "$release_tag" ]; then
  echo "${RED}aapt or aapt2 is required to verify a local fleet APK${X}" >&2
  echo "Official releases are authenticated by their signed checksum and do not need it." >&2
  exit 1
fi
RELEASE_CERT_SHA256="ac6193307fb0b70113aae205d7549406f96e063bc5491b67b1d5694a34b0e339"
# Each artifact is authenticated against the identity it is supposed to carry. One tree builds both,
# so a swapped pair passes every signer check and would install the wrong app on every panel.
verify_fleet_artifact() {
  local apk="$1" expected_package="$2" label="$3" signer_output signer_lines signer_count package_name apk_sha256
  signer_output="$("$APKSIGNER" verify --print-certs "$apk" 2>/dev/null)" || { echo "${RED}fleet $label APK signature verification failed${X}" >&2; exit 1; }
  signer_lines="$(printf '%s\n' "$signer_output" | sed -nE 's/^Signer #[0-9]+ certificate SHA-256 digest: *//p' | tr -d ':\r' | tr '[:upper:]' '[:lower:]')"
  signer_count="$(printf '%s\n' "$signer_lines" | awk 'NF { count++ } END { print count + 0 }')"
  [ "$signer_count" = 1 ] || {
    echo "${RED}fleet $label APK must have exactly one signer${X}" >&2
    echo "Got: ${signer_lines:-no signer} (count=$signer_count)" >&2
    exit 1
  }
  if [ "$require_release_signer" = 1 ] && [ "$signer_lines" != "$RELEASE_CERT_SHA256" ]; then
    echo "${RED}fleet $label APK does not use the required release signer${X}" >&2
    echo "Expected: $RELEASE_CERT_SHA256" >&2
    echo "Got: $signer_lines" >&2
    exit 1
  fi
  if [ -n "$AAPT" ]; then
    package_name="$("$AAPT" dump badging "$apk" 2>/dev/null | sed -nE "s/^package: name='([^']+)'.*/\1/p" | head -1 || true)"
    [ "$package_name" = "$expected_package" ] || { echo "${RED}fleet $label APK package mismatch: ${package_name:-unavailable} (expected $expected_package)${X}" >&2; exit 1; }
  fi
  apk_sha256="$(sha256sum "$apk" | awk '{print $1}')"
  echo "${GRN}✓${X} fleet $label artifact signer ${signer_lines:0:12}… · sha256 $apk_sha256"
}
verify_fleet_artifact "$APK" "$PKG" successor
[ -z "${BRIDGE_APK:-}" ] || verify_fleet_artifact "$BRIDGE_APK" "$LEGACY_PKG" bridge

# Observations, never mutations: nothing below uninstalls a package or reboots a panel. A fleet
# updater that strands a panel's launcher is worse than one that reports an unfinished handover.
package_installed() {
  local out
  out="$(adb -s "$1" shell pm path "$2" 2>/dev/null | tr -d '\r')" || return 1
  case "$out" in *package:/*) return 0 ;; esac
  return 1
}
package_running() {
  local out
  out="$(adb -s "$1" shell pidof "$2" 2>/dev/null | tr -d '\r\n ')" || return 1
  case "$out" in ''|*[!0-9]*) return 1 ;; esac
  return 0
}

# Update and start the bridge before the successor arrives. `adb install -r` against the package the
# panel already carries is an in-place update, never a first install, so the panel keeps its data and
# its launcher throughout; it does leave the app in Android's stopped state, and a stopped bridge has
# no running process to hand its state over from. A panel without the bridge skips straight ahead.
run_bridge_phase() {
  local target="$1" deadline
  package_installed "$target" "$LEGACY_PKG" || return 0
  if [ -z "${BRIDGE_APK:-}" ]; then
    echo "${YEL}⚠ --apk supplies the successor only, so the bridge on this panel is not updated first — name its sealed bridge with --bridge-apk, or use a release download that carries one${X}"
    return 0
  fi
  echo "${B}🌉 bridge${X} ${D}$(basename "$BRIDGE_APK")${X}"
  if ! adb -s "$target" install -r "$BRIDGE_APK" >/dev/null 2>&1; then
    echo "${RED}✗ the bridge update failed; the panel keeps the app it already had${X}" >&2
    return 1
  fi
  if ! package_installed "$target" "$LEGACY_PKG"; then
    echo "${RED}✗ the bridge is absent after its own update reported success${X}" >&2
    return 1
  fi
  adb -s "$target" shell am start -n "$LEGACY_PKG/.MainActivity" >/dev/null 2>&1 || true
  deadline=$((SECONDS + BRIDGE_START_TIMEOUT))
  while [ "$SECONDS" -lt "$deadline" ]; do
    if package_running "$target" "$LEGACY_PKG"; then return 0; fi
    sleep 2
  done
  echo "${YEL}⚠ the bridge reported no running process within ${BRIDGE_START_TIMEOUT}s; continuing, and the app's own watchdog remains the fallback${X}"
  return 0
}

# The successor removes the bridge once it has taken the panel over. This only observes that it did:
# a re-run converges, so an unfinished handover is reported rather than forced.
verify_bridge_removed() {
  local target="$1" deadline
  deadline=$((SECONDS + BRIDGE_REMOVAL_TIMEOUT))
  while :; do
    package_installed "$target" "$LEGACY_PKG" || return 0
    [ "$SECONDS" -lt "$deadline" ] || break
    sleep 5
  done
  echo "${RED}✗ $LEGACY_PKG is still installed ${BRIDGE_REMOVAL_TIMEOUT}s after the successor was provisioned${X}" >&2
  echo "${D}   the handover may still be running; re-run this fleet update for the panel — it is idempotent${X}" >&2
  return 1
}

run_dir="$(mktemp -d)"; TEMP_PATHS+=("$run_dir")
targets=()
for p in "${PANELS[@]}"; do
  t="$p"
  targets+=("$t")
  index=$((${#targets[@]} - 1))
  # Output is isolated and replayed per panel below. Completed batches release their worker slots;
  # this deliberately simple scheduler bounds adb/install pressure without requiring wait -n support.
  (
    provision_pid=""
    provision_pgid=""
    terminate_provision_group() {
      local deadline
      [ -z "$provision_pgid" ] || kill -TERM -- "-$provision_pgid" >/dev/null 2>&1 || true
      deadline=$((SECONDS + 3))
      while [ -n "$provision_pgid" ] && kill -0 -- "-$provision_pgid" >/dev/null 2>&1 &&
            [ "$SECONDS" -lt "$deadline" ]; do
        sleep 0.1
      done
      [ -z "$provision_pgid" ] || kill -KILL -- "-$provision_pgid" >/dev/null 2>&1 || true
      [ -z "$provision_pid" ] || wait "$provision_pid" >/dev/null 2>&1 || true
      provision_pid=""
      provision_pgid=""
    }
    stop_provisioner() {
      local status="$1"
      trap - INT TERM
      terminate_provision_group
      exit "$status"
    }
    trap 'stop_provisioner 130' INT
    trap 'stop_provisioner 143' TERM
    if ! run_bridge_phase "$t"; then
      echo 1 > "$run_dir/$index.status"
      exit 1
    fi
    # Job control assigns the provisioner its own process group even in this non-interactive worker.
    # Signalling that group owns synchronous adb/curl children as well as the subprocesses provision.sh
    # tracks explicitly, so interruption cannot wait indefinitely on or orphan a foreground mutation.
    set -m
    bash "$PROVISION" "$t" "${PARGS[@]}" --force </dev/null &
    provision_pid=$!
    provision_pgid=$provision_pid
    if wait "$provision_pid"; then status=0; else status=$?; fi
    provision_pid=""
    provision_pgid=""
    if [ "$status" = 0 ] && ! verify_bridge_removed "$t"; then status=1; fi
    echo "$status" > "$run_dir/$index.status"
  ) > "$run_dir/$index.log" 2>&1 &
  pids+=("$!")
  if [ "${#pids[@]}" -ge "$JOBS" ]; then
    for pid in "${pids[@]}"; do wait "$pid" || true; done
    pids=()
  fi
done

for pid in "${pids[@]}"; do wait "$pid" || true; done
pids=()

ok=0; fail=0; failed=()
for index in "${!targets[@]}"; do
  t="${targets[$index]}"
  echo
  echo "${B}════════ $t ════════${X}"
  if [ -f "$run_dir/$index.log" ]; then cat "$run_dir/$index.log"
  else echo "${RED}✗ provisioning worker ended without a log${X}"; fi
  if [ -f "$run_dir/$index.status" ] && [ "$(cat "$run_dir/$index.status")" = 0 ]; then
    ok=$((ok+1))
  else
    [ -f "$run_dir/$index.status" ] || echo "${RED}✗ provisioning worker ended before reporting status${X}"
    fail=$((fail+1)); failed+=("$t")
  fi
done

echo
if [ "$fail" = 0 ]; then
  echo "${GRN}${B}✅ fleet update complete — $ok/$ok panels OK${X}"
else
  echo "${YEL}${B}fleet update: $ok OK, $fail failed${X} — ${RED}${failed[*]}${X}"
  echo "${D}   re-run for the failed panels (provision.sh is idempotent).${X}"
fi
[ "$fail" = 0 ]
