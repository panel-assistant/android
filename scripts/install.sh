#!/usr/bin/env bash
#
# ha-paneld one-line installer — no repo checkout needed. Run:
#   curl -fsSL https://raw.githubusercontent.com/panel-assistant/android/main/scripts/install.sh | bash
#
# To follow the newest published release, including release candidates, add --prerelease:
#   curl -fsSL https://raw.githubusercontent.com/panel-assistant/android/main/scripts/install.sh | bash -s -- --prerelease
#
# Preflights adb + curl (with per-OS fix-it hints), prompts for the panel IP (and optional id / MQTT
# broker), downloads the release, and provisions the panel. On rooted panels the authenticated
# provisioner also installs or upgrades the matching sealed root-helper asset. No parameters required
# (except --prerelease). Advanced checkout-free provisioning is also available with:
#   curl -fsSL https://raw.githubusercontent.com/panel-assistant/android/main/scripts/install.sh |
#     bash -s -- --provision panel-ip:5555 [provision options]
# The release workflow fills RELEASE_TAG, RELEASE_APK_NAME and PROVISION_COMMIT
# in its downloadable copy so an installer attached to a historical release always installs that exact
# release using its matching authenticated provisioner asset.
set -euo pipefail
umask 077

# Create private storage before parsing advanced arguments. Legacy literal credential flags remain
# accepted for compatibility, but are immediately rewritten to file-backed provisioner arguments so
# their values are not copied into the downloaded provisioner's argv. The original installer command
# line cannot be scrubbed portably; callers should use the corresponding --*-file options instead.
TMP_DIR="$(mktemp -d)"
trap 'rm -rf "$TMP_DIR"' EXIT

RELEASE_TAG=""
RELEASE_APK_NAME=""
PROVISION_COMMIT=""

# --prerelease selects the newest published release of either kind; --latest selects stable only.
CHANNEL_ARG="--latest"
ADVANCED_PROVISION=0
ADVANCED_TARGET=""
PROVISION_ARGS=()
PROVISION_NEEDS_APK=1
PROVISION_SECRET_KINDS='|'
claim_provision_secret() {
  local kind="$1"
  case "$PROVISION_SECRET_KINDS" in
    *"|$kind|"*) echo "credential source supplied more than once: --$kind-file" >&2; exit 2 ;;
  esac
  PROVISION_SECRET_KINDS="${PROVISION_SECRET_KINDS}${kind}|"
}
materialize_provision_secret() {
  local option="$1" value="$2" secret_file
  secret_file="$(mktemp "$TMP_DIR/${option#--}.XXXXXX")"
  printf '%s' "$value" > "$secret_file"
  chmod 600 "$secret_file" 2>/dev/null || true
  PROVISION_ARGS+=("${option}-file" "$secret_file")
}
show_usage() {
  echo "Usage: curl -fsSL https://raw.githubusercontent.com/panel-assistant/android/main/scripts/install.sh | bash"
  echo "       append: | bash -s -- --prerelease"
  echo "       advanced: | bash -s -- [--prerelease] --provision PANEL-IP[:PORT] [options]"
  echo
  echo "Advanced options cover configuration, backup, restore, verification, and exceptional"
  echo "access setup. APK/channel overrides (--apk, --release-tag, --latest, or a second"
  echo "--prerelease) are rejected so the authenticated provisioner stays paired with its release."
  echo "Use --mqtt-pass-file, --ha-token-file, or --ha-pass-file for credentials. The older literal"
  echo "flags remain compatible but expose their value in the original shell command and process list."
  echo
  echo "--home-dashboard PATH and --entity-filter on|off preseed ha-paneld's built-in renderer, so an"
  echo "unattended install shows the dashboard you name instead of the Home Assistant account default."
  echo "Both need --builtin (or a panel already on the built-in renderer), and both answer the matching"
  echo "guided-setup question so it is not asked again on the panel."
  echo
  echo "--reset-config erases the panel's existing ha-paneld configuration and starts guided setup"
  echo "from scratch. Reset is irreversible and makes no backup; use a separate backup or export"
  echo "operation first if you need one. It asks for confirmation before erasing."
}
while [ "$#" -gt 0 ]; do case "$1" in
  --prerelease|--pre)
    [ "$ADVANCED_PROVISION" = 0 ] || { echo "channel selection must appear before --provision" >&2; exit 2; }
    CHANNEL_ARG="--prerelease"
    shift
    ;;
  --provision)
    [ "$ADVANCED_PROVISION" = 0 ] || { echo "--provision may only be supplied once" >&2; exit 2; }
    [ "$#" -ge 2 ] && [ -n "${2:-}" ] && [ "${2#--}" = "$2" ] ||
      { echo "--provision needs a panel IP or hostname" >&2; exit 2; }
    ADVANCED_PROVISION=1
    ADVANCED_TARGET="$2"
    shift 2
    while [ "$#" -gt 0 ]; do
      case "$1" in
        --mqtt-pass|--ha-token|--ha-pass)
          [ "$#" -ge 2 ] && [ -n "${2:-}" ] && [ "${2#--}" = "$2" ] ||
            { echo "$1 needs a value" >&2; exit 2; }
          claim_provision_secret "${1#--}"
          materialize_provision_secret "$1" "$2"
          shift 2
          ;;
        --mqtt-pass-file|--ha-token-file|--ha-pass-file)
          [ "$#" -ge 2 ] && [ -n "${2:-}" ] && [ "${2#--}" = "$2" ] ||
            { echo "$1 needs a value" >&2; exit 2; }
          file_kind="${1#--}"; file_kind="${file_kind%-file}"
          claim_provision_secret "$file_kind"
          PROVISION_ARGS+=("$1" "$2")
          shift 2
          ;;
        --id|--mqtt|--mqtt-user|--log-host|--log-port|--log-proto|--ha-url|--ha-user|--export|--restore|--restore-fleet|--home-dashboard|--entity-filter)
          [ "$#" -ge 2 ] && [ -n "${2:-}" ] && [ "${2#--}" = "$2" ] ||
            { echo "$1 needs a value" >&2; exit 2; }
          PROVISION_ARGS+=("$1" "$2")
          shift 2
          ;;
        --force|--persist-adb|--strip-vendor|--no-tame|--shizuku|--log-off|--builtin|--reset-config|--allow-missing-db-snapshot)
          PROVISION_ARGS+=("$1")
          shift
          ;;
        --verify)
          PROVISION_ARGS+=("$1")
          PROVISION_NEEDS_APK=0
          shift
          ;;
        --apk|--release-tag|--latest|--prerelease|--pre)
          echo "$1 is not accepted after --provision; the installer selects a matching authenticated release" >&2
          exit 2
          ;;
        -h|--help)
          show_usage
          exit 0
          ;;
        *)
          echo "unknown provisioning option: $1" >&2
          exit 2
          ;;
      esac
    done
    ;;
  -h|--help)
    show_usage
    exit 0
    ;;
  *)
    echo "unknown option: $1"
    exit 2
    ;;
esac; done

if [ "$ADVANCED_PROVISION" = 1 ]; then
  HAS_EXPORT=0
  HAS_VERIFY=0
  HAS_OTHER=0
  i=0
  while [ "$i" -lt "${#PROVISION_ARGS[@]}" ]; do
    option="${PROVISION_ARGS[$i]}"
    case "$option" in
      --export) HAS_EXPORT=1; i=$((i + 2)) ;;
      --verify) HAS_VERIFY=1; i=$((i + 1)) ;;
      --id|--mqtt|--mqtt-user|--mqtt-pass-file|--log-host|--log-port|--log-proto|--ha-url|--ha-token-file|--ha-user|--ha-pass-file|--restore|--restore-fleet|--home-dashboard|--entity-filter)
        HAS_OTHER=1; i=$((i + 2)) ;;
      *) HAS_OTHER=1; i=$((i + 1)) ;;
    esac
  done
  if [ "$HAS_VERIFY" = 1 ] && [ "$HAS_OTHER" = 1 ]; then
    echo "--verify may only be combined with --export because verification is read-only" >&2
    exit 2
  fi
  if [ "$HAS_VERIFY" = 1 ] || { [ "$HAS_EXPORT" = 1 ] && [ "$HAS_OTHER" = 0 ]; }; then
    PROVISION_NEEDS_APK=0
  fi
fi

if [ -t 1 ]; then B=$'\033[1m'; R=$'\033[31m'; G=$'\033[32m'; Y=$'\033[33m'; X=$'\033[0m'
else B=; R=; G=; Y=; X=; fi
REPO="panel-assistant/android"
# The two installable identities. This installer hands the panel to the provisioner that belongs to
# the release it just authenticated, so it never installs either APK itself; it only has to refuse a
# historical provisioner when state under EITHER identity proves this is not a first installation.
PKG="io.panelassistant.android"
LEGACY_PKG="io.github.maxlyth.hapaneld"
PROVISION_REF="${RELEASE_TAG:-}"
PROVISION_URL=""
RESOLVED_APK_URL=""
RESOLVED_APK_NAME=""
valid_release_tag() { printf '%s\n' "$1" | grep -Eq '^v[0-9]+\.[0-9]+\.[0-9]+(-[0-9A-Za-z][0-9A-Za-z.-]*)?$'; }
valid_commit() { printf '%s\n' "$1" | grep -Eq '^[0-9a-f]{40}$'; }
release_asset_url() { printf 'https://github.com/%s/releases/download/%s/%s\n' "$REPO" "$1" "$2"; }
release_apk_name() { printf 'panel-assistant-%s-manual-setup-required.apk\n' "$1"; }
# The bridge keeps the historical asset name. A release published before the successor existed
# carries only that APK, and its own provisioner is the one that installs it.
bridge_apk_name() { printf 'ha-paneld-%s-manual-setup-required.apk\n' "$1"; }
release_apk_url() { release_asset_url "$1" "$(release_apk_name "$1")"; }
provision_asset_name() { printf 'ha-paneld-provision-%s.sh\n' "$1"; }
provision_asset_url() { release_asset_url "$1" "$(provision_asset_name "$1")"; }
release_has_authenticated_provisioner() {
  local version major minor patch
  version="${1#v}"; version="${version%%-*}"
  IFS=. read -r major minor patch <<< "$version"
  [ "$major" -gt 0 ] || [ "$minor" -gt 9 ] || { [ "$minor" -eq 9 ] && [ "$patch" -ge 3 ]; }
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
if [ -n "$RELEASE_TAG" ]; then
  valid_release_tag "$RELEASE_TAG" || { echo "${R}Release installer has an invalid tag.${X}" >&2; exit 1; }
  [ "$RELEASE_APK_NAME" = "$(release_apk_name "$RELEASE_TAG")" ] || { echo "${R}Release installer does not pair its tag with the expected APK asset.${X}" >&2; exit 1; }
  valid_commit "$PROVISION_COMMIT" || { echo "${R}Release installer is missing its immutable provisioner commit.${X}" >&2; exit 1; }
fi

if [ -n "$RELEASE_TAG" ]; then
  echo "${B}ha-paneld installer${X} ${Y}· $RELEASE_TAG${X}"
elif [ "$CHANNEL_ARG" = "--prerelease" ]; then
  echo "${B}ha-paneld installer${X} ${Y}· all-releases channel${X}"
else
  echo "${B}ha-paneld installer${X}"
fi

# --- preflight: required tools, with actionable install hints ---
miss=0
if ! command -v adb >/dev/null 2>&1; then
  miss=1
  echo "${R}✗ adb (Android Platform Tools) not found.${X} Install it, then re-run:"
  case "$(uname -s 2>/dev/null)" in
    Darwin) echo "    brew install android-platform-tools" ;;
    Linux)  echo "    Debian/Ubuntu: sudo apt install adb   ·   Fedora: sudo dnf install android-tools   ·   Arch: sudo pacman -S android-tools" ;;
    MINGW*|MSYS*|CYGWIN*) echo "    winget install Google.PlatformTools   (then reopen Git Bash)" ;;
    *) echo "    Windows: winget install Google.PlatformTools  (run this in Git Bash or WSL)" ;;
  esac
  echo "    …or download platform-tools: https://developer.android.com/tools/releases/platform-tools"
fi
if ! command -v curl >/dev/null 2>&1; then miss=1; echo "${R}✗ curl not found${X} — install curl, then re-run."; fi
if ! command -v openssl >/dev/null 2>&1 || ! openssl version >/dev/null 2>&1; then
  miss=1
  echo "${R}✗ OpenSSL not found.${X} It authenticates the release before anything is installed. Install it, then re-run:"
  case "$(uname -s 2>/dev/null)" in
    Darwin) echo "    xcode-select --install   ·   or: brew install openssl" ;;
    Linux)  echo "    Debian/Ubuntu: sudo apt install openssl   ·   Fedora: sudo dnf install openssl   ·   Arch: sudo pacman -S openssl" ;;
    MINGW*|MSYS*|CYGWIN*) echo "    Update Git for Windows, then reopen Git Bash   ·   or run the installer in WSL" ;;
    *) echo "    Windows: use a current Git Bash or WSL terminal   ·   macOS/Linux: install the openssl package" ;;
  esac
fi
[ "$miss" = 0 ] || { echo "${Y}Resolve the above and paste the one-liner again.${X}"; exit 1; }
echo "${G}✓ adb, curl, and OpenSSL present${X}"

# Pair the provisioner and APK from one immutable release. The generated installer's source commit is
# retained as release provenance, while executable bytes come from the matching signed release asset.
# Pulling provision.sh from moving main while installing an older stable APK can make a first-run
# script call APIs that APK does not have.
if [ -z "$RELEASE_TAG" ]; then
  if [ "$CHANNEL_ARG" = "--prerelease" ]; then api="https://api.github.com/repos/$REPO/releases?per_page=100";
  else api="https://api.github.com/repos/$REPO/releases/latest"; fi
  release_json="$(curl -fsSL --proto '=https' --proto-redir '=https' --connect-timeout 15 --max-time 30 "$api" 2>/dev/null || true)"
  if [ "$CHANNEL_ARG" = "--prerelease" ]; then
    release_record="$(printf '%s' "$release_json" | tr -d '\r\n' | \
      sed 's#{[[:space:]]*"url":[[:space:]]*"https://api.github.com/repos/'"$REPO"'/releases/\([0-9][0-9]*\)"#\
&#g' | \
      awk '/"draft":[[:space:]]*false/ && !found { print; found=1 }')"
  else
    release_record="$release_json"
  fi
  PROVISION_REF="$(printf '%s' "$release_record" | grep -o '"tag_name": *"[^"]*"' | head -1 | cut -d'"' -f4 || true)"
  # A release carries one APK per installable identity, so the first `.apk` in the record is not
  # necessarily the one this installer wants. Take the successor when the release publishes it and
  # the bridge otherwise, always by exact published URL rather than by position.
  release_apk_urls="$(printf '%s' "$release_record" | grep -o '"browser_download_url": *"[^"]*\.apk"' | cut -d'"' -f4 || true)"
  if [ -n "$PROVISION_REF" ] && valid_release_tag "$PROVISION_REF"; then
    for candidate_name in "$(release_apk_name "$PROVISION_REF")" "$(bridge_apk_name "$PROVISION_REF")"; do
      candidate_url="$(release_asset_url "$PROVISION_REF" "$candidate_name")"
      if printf '%s\n' "$release_apk_urls" | grep -Fxq "$candidate_url"; then
        RESOLVED_APK_URL="$candidate_url"
        RESOLVED_APK_NAME="$candidate_name"
        break
      fi
    done
  fi
  if [ -z "$PROVISION_REF" ] || ! valid_release_tag "$PROVISION_REF" || [ -z "$RESOLVED_APK_URL" ]; then
    echo "${R}Could not resolve a complete signed ha-paneld release.${X} Check internet/GitHub access and try again; no panel changes were made." >&2
    exit 1
  fi
fi
AUTHENTICATE_PROVISIONER=0
if [ -n "$RELEASE_TAG" ] || release_has_authenticated_provisioner "$PROVISION_REF"; then
  AUTHENTICATE_PROVISIONER=1
  PROVISION_URL="$(provision_asset_url "$PROVISION_REF")"
else
  # Compatibility for channel installs of releases published before provisioner proof assets existed.
  # v0.9.3 and newer never fall back to this transport-only legacy path.
  PROVISION_URL="https://raw.githubusercontent.com/$REPO/$PROVISION_REF/scripts/provision.sh"
fi

# New release code gets an independent release-key check before prompts or panel contact. HTTPS and
# an immutable tag prevent accidental drift; the detached signature also fails closed if the
# provisioner or its checksum is damaged, replaced, or served from an incomplete release.
SCRIPT="$TMP_DIR/provision.sh"
PROVISION_CHECKSUM="$TMP_DIR/provision.sha256"
PROVISION_SIGNATURE="$TMP_DIR/provision.sha256.sig"
PROVISION_PUBLIC_KEY="$TMP_DIR/release-public-key.pem"
if ! curl -fsSL --proto '=https' --proto-redir '=https' --connect-timeout 15 --max-time 60 "$PROVISION_URL" -o "$SCRIPT"; then
  echo "${R}Could not download the $PROVISION_REF ha-paneld provisioning script.${X} The release may be incomplete or GitHub may be unavailable; no panel changes were made." >&2
  exit 1
fi
if [ "$AUTHENTICATE_PROVISIONER" = 1 ]; then
  if ! curl -fsSL --proto '=https' --proto-redir '=https' --connect-timeout 15 --max-time 60 "$PROVISION_URL.sha256" -o "$PROVISION_CHECKSUM"; then
    echo "${R}Could not download the signed provisioner checksum for $PROVISION_REF.${X} No panel changes were made." >&2
    exit 1
  fi
  if ! curl -fsSL --proto '=https' --proto-redir '=https' --connect-timeout 15 --max-time 60 "$PROVISION_URL.sha256.sig" -o "$PROVISION_SIGNATURE"; then
    echo "${R}Could not download the provisioner checksum signature for $PROVISION_REF.${X} No panel changes were made." >&2
    exit 1
  fi
  write_release_public_key "$PROVISION_PUBLIC_KEY" || { echo "${R}Could not prepare the trusted ha-paneld release key.${X} No panel changes were made." >&2; exit 1; }
  if ! openssl dgst -sha256 -verify "$PROVISION_PUBLIC_KEY" -signature "$PROVISION_SIGNATURE" "$PROVISION_CHECKSUM" >/dev/null 2>&1; then
    echo "${R}The $PROVISION_REF provisioner checksum signature is invalid.${X} Nothing was installed, started, or privileged." >&2
    exit 1
  fi
  PROVISION_NAME="$(provision_asset_name "$PROVISION_REF")"
  PROVISION_RECORD="$(cat "$PROVISION_CHECKSUM")"
  PROVISION_EXPECTED_HASH="${PROVISION_RECORD%% *}"
  if ! printf '%s\n' "$PROVISION_EXPECTED_HASH" | grep -Eq '^[0-9A-Fa-f]{64}$' || \
     [ "$PROVISION_RECORD" != "$PROVISION_EXPECTED_HASH  $PROVISION_NAME" ]; then
    echo "${R}The signed provisioner checksum record for $PROVISION_REF is malformed.${X} Nothing was installed, started, or privileged." >&2
    exit 1
  fi
  if ! PROVISION_ACTUAL_HASH="$(openssl dgst -sha256 -r "$SCRIPT" 2>/dev/null | awk '{print tolower($1)}')" || \
     ! printf '%s\n' "$PROVISION_ACTUAL_HASH" | grep -Eq '^[0-9a-f]{64}$' || \
     [ "$PROVISION_ACTUAL_HASH" != "$(printf '%s' "$PROVISION_EXPECTED_HASH" | tr '[:upper:]' '[:lower:]')" ]; then
    echo "${R}The downloaded $PROVISION_REF provisioner does not match its signed checksum.${X} Nothing was installed, started, or privileged." >&2
    exit 1
  fi
  echo "${G}✓ authenticated $PROVISION_REF provisioner${X}"
fi

# --- prompts: stdin is the curl pipe, so interactive installs read from the terminal directly ---
TTY=/dev/tty
if [ "$ADVANCED_PROVISION" = 1 ]; then
  IP="$ADVANCED_TARGET"
else
  [ -r "$TTY" ] || { echo "${R}No terminal available for prompts.${X} Try: ${B}bash <(curl -fsSL https://raw.githubusercontent.com/$REPO/main/scripts/install.sh)${X}"; exit 1; }
  echo "${Y}First enable network ADB on the panel (Developer options → ADB / 'ADB debugging').${X}"
  printf "Panel IP (or ip:port): " > "$TTY"; read -r IP < "$TTY"
  [ -n "${IP:-}" ] || { echo "${R}No IP entered.${X}"; exit 1; }
fi
# Loose sanity check (hostname/IPv4[:port]) — catch typos here rather than as an obscure adb error.
case "$IP" in
  *[!0-9a-zA-Z.:-]*|.*|-*) echo "${R}'$IP' doesn't look like an IP address or hostname (optionally :port).${X} Find it on the panel under Settings → About → Status, or in your router's client list."; exit 1 ;;
esac
case "$IP" in *:*) TARGET="$IP" ;; *) TARGET="$IP:5555" ;; esac

# A historical release can carry a correctly authenticated provisioner that predates database
# admission. Never execute those bytes against existing or indeterminate app data. Package-path
# absence is not fresh-install proof: Android must also prove that no retained `-u` package/data
# record exists, and a usable root route strengthens that proof with the actual CE/DE app-data,
# canonical-database and recovery inventory. Current provisioners expose the stable marker below and
# perform the full exact-APK/actual-database HOST_GATE themselves.
legacy_provisioner_package_verdict() {
  local pkg="$1" nonce out status=0 verdict
  nonce="$(od -An -N16 -tx1 /dev/urandom 2>/dev/null | tr -d ' \n')"
  printf '%s\n' "$nonce" | grep -Eq '^[0-9a-f]{32}$' || { printf 'unknown\n'; return; }
  out="$(adb -s "$TARGET" shell \
    "echo HAPANELD_INSTALLER_PKG_BEGIN:$nonce; pm path $pkg; echo HAPANELD_INSTALLER_PKG_TARGET:$nonce:\$?; pm list packages -u $pkg; echo HAPANELD_INSTALLER_DATA:$nonce:\$?; pm path android; echo HAPANELD_INSTALLER_PKG_LIVE:$nonce:\$?; echo HAPANELD_INSTALLER_PKG_END:$nonce" 2>/dev/null)" || status=$?
  [ "$status" -eq 0 ] || { printf 'unknown\n'; return; }
  verdict="$(printf '%s\n' "$out" | tr -d '\r' | awk -v n="$nonce" -v p="$pkg" '
    /^HAPANELD_INSTALLER_PKG_BEGIN:/  { fields=split($0,a,":"); if (fields!=2 || a[2]!=n || seg!=0) bad=1; else seg=1; next }
    /^HAPANELD_INSTALLER_PKG_TARGET:/ { fields=split($0,a,":"); if (fields!=3 || a[2]!=n || seg!=1 || a[3]!~/^[0-9]+$/) bad=1; else { trc=a[3]; seg=2 }; next }
    /^HAPANELD_INSTALLER_DATA:/       { fields=split($0,a,":"); if (fields!=3 || a[2]!=n || seg!=2 || a[3]!~/^[0-9]+$/) bad=1; else { urc=a[3]; seg=3 }; next }
    /^HAPANELD_INSTALLER_PKG_LIVE:/   { fields=split($0,a,":"); if (fields!=3 || a[2]!=n || seg!=3 || a[3]!~/^[0-9]+$/) bad=1; else { lrc=a[3]; seg=4 }; next }
    /^HAPANELD_INSTALLER_PKG_END:/    { fields=split($0,a,":"); if (fields!=2 || a[2]!=n || seg!=4) bad=1; else seg=5; next }
    seg==1 && /^package:/ { if ($0~/^package:\/[^ \t]+$/) target=1; else malformed=1; next }
    seg==2 && /^package:/ { if ($0=="package:" p) retained=1; else malformed=1; next }
    seg==3 && /^package:/ { if ($0~/^package:\/[^ \t]+$/) live=1; else malformed=1; next }
    NF { malformed=1 }
    END {
      if (bad || malformed || seg!=5 || urc+0!=0 || lrc+0!=0 || !live) print "unknown"
      else if (target) print (trc+0==0) ? "present" : "unknown"
      else if (!(trc+0==0 || trc+0==1)) print "unknown"
      else print retained ? "retained" : "absent"
    }')"
  case "$verdict" in present|retained|absent) printf '%s\n' "$verdict" ;; *) printf 'unknown\n' ;; esac
}

# An installed or retained package under EITHER identity refuses the historical provisioner: a
# bridge package or its data record on a panel mid-migration is this app's own state, not a foreign
# app, and the guardless script must never run against it.
legacy_provisioner_packages_verdict() {
  local pkg verdict combined=absent
  for pkg in "$PKG" "$LEGACY_PKG"; do
    verdict="$(legacy_provisioner_package_verdict "$pkg")"
    case "$verdict" in
      present) printf 'present\n'; return ;;
      retained) combined=retained ;;
      absent) ;;
      *) printf 'unknown\n'; return ;;
    esac
  done
  printf '%s\n' "$combined"
}

legacy_provisioner_root_form() {
  local out key prefix
  out="$(adb -s "$TARGET" shell id 2>/dev/null | tr -d '\r')" || out=""
  case "$out" in uid=0*) printf 'shell\n'; return 0 ;; esac
  for key in su0 suroot; do
    case "$key" in su0) prefix='su 0' ;; suroot) prefix='su root' ;; esac
    out="$(adb -s "$TARGET" shell "$prefix \"id; id\"" 2>/dev/null | tr -d '\r')" || out=""
    case "$out" in *uid=0*) printf '%sjoin\n' "$key"; return 0 ;; esac
    out="$(adb -s "$TARGET" shell "$prefix sh -c \"id; id\"" 2>/dev/null | tr -d '\r')" || out=""
    case "$out" in *uid=0*) printf '%sshc\n' "$key"; return 0 ;; esac
  done
  out="$(adb -s "$TARGET" shell 'su -c "id; id"' 2>/dev/null | tr -d '\r')" || out=""
  case "$out" in *uid=0*) printf 'suc\n'; return 0 ;; esac
  printf 'none\n'
  return 1
}

legacy_quote_root_command() {
  local command="$1"
  command="${command//\\/\\\\}"
  command="${command//\"/\\\"}"
  command="${command//\$/\\\$}"
  command="${command//\`/\\\`}"
  printf '%s\n' "$command"
}

legacy_run_root() {
  local form="$1" command="$2" quoted
  quoted="$(legacy_quote_root_command "$command")"
  case "$form" in
    shell)      adb -s "$TARGET" shell "$command" ;;
    su0join)    adb -s "$TARGET" shell "su 0 \"$quoted\"" ;;
    su0shc)     adb -s "$TARGET" shell "su 0 sh -c \"$quoted\"" ;;
    surootjoin) adb -s "$TARGET" shell "su root \"$quoted\"" ;;
    surootshc)  adb -s "$TARGET" shell "su root sh -c \"$quoted\"" ;;
    suc)        adb -s "$TARGET" shell "su -c \"$quoted\"" ;;
    *) return 1 ;;
  esac
}

# A proven root route makes Android's private filesystem directly observable, so do not discard that
# stronger evidence and rely only on package-manager bookkeeping. The three independent fields keep
# an odd or partially removed tree fail-closed: an app-data directory, a canonical DB/sidecar, or any
# recovery/superseded artifact is retained state. `unknown` means root was proven but the inventory
# did not complete, which is also a refusal.
legacy_provisioner_root_data_verdict() {
  local form nonce command out status=0 verdict
  form="$(legacy_provisioner_root_form)" || form=none
  [ "$form" != none ] || { printf 'unavailable\n'; return; }
  nonce="$(od -An -N16 -tx1 /dev/urandom 2>/dev/null | tr -d ' \n')"
  printf '%s\n' "$nonce" | grep -Eq '^[0-9a-f]{32}$' || { printf 'unknown\n'; return; }
  command='set -u
app_data=absent
database=absent
recovery=absent
inventory=readable
root_uid=unknown
root_id=$(id 2>/dev/null) || root_id=""
case "$root_id" in uid=0*) root_uid=zero ;; *) inventory=unreadable ;; esac
for data_base in /data/user/0 /data/data /data/user_de/0; do
  if [ -L "$data_base" ] || [ -d "$data_base" ]; then
    if ! ls -1A "$data_base" >/dev/null 2>&1; then inventory=unreadable; continue; fi
  elif [ -e "$data_base" ]; then
    inventory=unreadable
    continue
  else
    inventory=unreadable
    continue
  fi
  for app_package in @PACKAGES@; do
    app="$data_base/$app_package"
    if [ -L "$app" ] || [ -e "$app" ]; then
      app_data=retained
      if [ -L "$app" ] || [ ! -d "$app" ] || ! ls -1A "$app" >/dev/null 2>&1; then
        inventory=unreadable
        continue
      fi
    else
      continue
    fi
    db_dir="$app/databases"
    if [ -L "$db_dir" ]; then inventory=unreadable; database=retained; recovery=retained; continue
    elif [ -e "$db_dir" ]; then
      if [ ! -d "$db_dir" ] || ! ls -1A "$db_dir" >/dev/null 2>&1; then
        inventory=unreadable
        database=retained
        recovery=retained
        continue
      fi
    else
      continue
    fi
    db="$db_dir/ha-paneld.db"
    for database_path in "$db" "$db"-wal "$db"-shm "$db"-journal; do
      if [ -L "$database_path" ] || [ -e "$database_path" ]; then database=retained; fi
    done
    for recovery_path in \
      "$db".restore.tmp "$db".v*.premigrate "$db".v*.superseded \
      "$db".v*.premigrate.tmp "$db".v*.superseded.tmp \
      "$db".v*.premigrate-wal "$db".v*.premigrate-shm "$db".v*.premigrate-journal \
      "$db".v*.superseded-wal "$db".v*.superseded-shm "$db".v*.superseded-journal; do
      if [ -L "$recovery_path" ] || [ -e "$recovery_path" ]; then recovery=retained; fi
    done
  done
done
echo HAPANELD_INSTALLER_DB_BEGIN:@NONCE@
echo HAPANELD_INSTALLER_ROOT_UID:$root_uid
echo HAPANELD_INSTALLER_APP_DATA:$app_data
echo HAPANELD_INSTALLER_DATABASE:$database
echo HAPANELD_INSTALLER_RECOVERY:$recovery
echo HAPANELD_INSTALLER_INVENTORY:$inventory
echo HAPANELD_INSTALLER_DB_END:@NONCE@'
  command="${command//@PACKAGES@/$PKG $LEGACY_PKG}"
  command="${command//@NONCE@/$nonce}"
  out="$(legacy_run_root "$form" "$command" 2>/dev/null)" || status=$?
  [ "$status" -eq 0 ] || { printf 'unknown\n'; return; }
  verdict="$(printf '%s\n' "$out" | tr -d '\r' | awk -v n="$nonce" '
    $0=="HAPANELD_INSTALLER_DB_BEGIN:" n { if (seg!=0) bad=1; else seg=1; next }
    /^HAPANELD_INSTALLER_ROOT_UID:/ {
      if (seg!=1 || root_uid!="") bad=1; else { root_uid=$0; sub(/^[^:]*:/,"",root_uid); seg=2 }; next
    }
    /^HAPANELD_INSTALLER_APP_DATA:/ {
      if (seg!=2 || app!="") bad=1; else { app=$0; sub(/^[^:]*:/,"",app); seg=3 }; next
    }
    /^HAPANELD_INSTALLER_DATABASE:/ {
      if (seg!=3 || db!="") bad=1; else { db=$0; sub(/^[^:]*:/,"",db); seg=4 }; next
    }
    /^HAPANELD_INSTALLER_RECOVERY:/ {
      if (seg!=4 || recovery!="") bad=1; else { recovery=$0; sub(/^[^:]*:/,"",recovery); seg=5 }; next
    }
    /^HAPANELD_INSTALLER_INVENTORY:/ {
      if (seg!=5 || inventory!="") bad=1; else { inventory=$0; sub(/^[^:]*:/,"",inventory); seg=6 }; next
    }
    $0=="HAPANELD_INSTALLER_DB_END:" n { if (seg!=6) bad=1; else seg=7; next }
    NF { bad=1 }
    END {
      if (bad || seg!=7 || root_uid!="zero" || (app!="absent" && app!="retained") ||
          (db!="absent" && db!="retained") ||
          (recovery!="absent" && recovery!="retained") ||
          (inventory!="readable" && inventory!="unreadable")) print "unknown"
      else if (inventory!="readable") print "unknown"
      else if (app=="absent" && db=="absent" && recovery=="absent") print "absent"
      else print "retained"
    }')"
  case "$verdict" in absent|retained) printf '%s\n' "$verdict" ;; *) printf 'unknown\n' ;; esac
}

legacy_provisioner_fresh_verdict() {
  local package_verdict root_verdict
  package_verdict="$(legacy_provisioner_packages_verdict)"
  case "$package_verdict" in
    present|retained) printf '%s\n' "$package_verdict"; return ;;
    absent) ;;
    *) printf 'unknown\n'; return ;;
  esac
  root_verdict="$(legacy_provisioner_root_data_verdict)"
  case "$root_verdict" in
    absent) printf 'fresh-root\n' ;;
    unavailable) printf 'fresh-android-removal\n' ;;
    retained) printf 'actual-retained\n' ;;
    *) printf 'actual-unknown\n' ;;
  esac
}

refuse_legacy_provisioner() {
  local verdict="$1" phase="${2:-initial}" timing=""
  [ "$phase" != consume ] || timing=" at the consume-time recheck"
  case "$verdict" in
    present)
      echo "${R}Refusing to run the $PROVISION_REF provisioner$timing against an installed ha-paneld package: that historical script has no database-compatibility gate.${X}" >&2
      ;;
    retained)
      echo "${R}Refusing to run the $PROVISION_REF provisioner$timing: Android retains an uninstalled ha-paneld package/data record, so this is not a proven fresh install.${X}" >&2
      ;;
    actual-retained)
      echo "${R}Refusing to run the $PROVISION_REF provisioner$timing: root inspection found retained ha-paneld app-data, database, or recovery state.${X}" >&2
      ;;
    actual-unknown)
      echo "${R}Refusing to run the $PROVISION_REF provisioner$timing: a proven root route could not establish a complete app-data, database, and recovery inventory.${X}" >&2
      ;;
    root-proof-lost)
      echo "${R}Refusing to run the $PROVISION_REF provisioner$timing: the root route used to inspect actual app data at admission is no longer available.${X}" >&2
      ;;
    *)
      echo "${R}Refusing to run the $PROVISION_REF provisioner$timing because package/data state is unknown and that historical script has no database-compatibility gate.${X}" >&2
      ;;
  esac
  echo "Use a current database-compatible provisioner, or prove a complete Android data removal before retrying. Nothing was installed or changed." >&2
  exit 1
}

# A panel that still runs only the old application id is moved by Panel Assistant's Home Assistant
# Repair, never by installing the new app beside it: a new app that has run beside the old one is a
# state that Repair refuses. Refuse before any download or panel change. A release that carries only
# the old app's own build updates that app in place and is unaffected. An undecided observation is
# left to the provisioner, which makes the same check against its own observation.
INSTALLS_SUCCESSOR=0
if [ "$PROVISION_NEEDS_APK" = 1 ]; then
  if [ -n "$RELEASE_TAG" ] || [ "$RESOLVED_APK_NAME" = "$(release_apk_name "$PROVISION_REF")" ]; then
    INSTALLS_SUCCESSOR=1
  fi
fi
if [ "$INSTALLS_SUCCESSOR" = 1 ] &&
   [ "$(legacy_provisioner_package_verdict "$LEGACY_PKG")" = present ]; then
  case "$(legacy_provisioner_package_verdict "$PKG")" in
    absent|retained)
      echo "${R}${B}This panel still runs the old ha-paneld app.${X}" >&2
      echo "Installers no longer put the new app beside the old one." >&2
      echo "Move the panel with Panel Assistant: in Home Assistant, open Settings → Repairs → \"Move <panel name> to the new app\"." >&2
      echo "Panel Assistant is required for the move. Nothing was installed or changed." >&2
      exit 1
      ;;
  esac
fi

LEGACY_PROVISIONER_FRESH_GATE=0
LEGACY_PROVISIONER_INITIAL_FRESH_VERDICT=""
if [ "$PROVISION_NEEDS_APK" = 1 ] && ! grep -q 'HAPANELD_HOST_DB_GATE_V1' "$SCRIPT"; then
  LEGACY_PROVISIONER_FRESH_VERDICT="$(legacy_provisioner_fresh_verdict)"
  case "$LEGACY_PROVISIONER_FRESH_VERDICT" in
    fresh-root)
      LEGACY_PROVISIONER_FRESH_GATE=1
      LEGACY_PROVISIONER_INITIAL_FRESH_VERDICT="$LEGACY_PROVISIONER_FRESH_VERDICT"
      echo "${Y}Legacy provisioner is eligible only because Android and root inspection proved this is a fresh install with no retained database or recovery state.${X}"
      ;;
    fresh-android-removal)
      LEGACY_PROVISIONER_FRESH_GATE=1
      LEGACY_PROVISIONER_INITIAL_FRESH_VERDICT="$LEGACY_PROVISIONER_FRESH_VERDICT"
      echo "${Y}Legacy provisioner is eligible only because Android proved both package absence and complete package/data-record removal.${X}"
      ;;
    *) refuse_legacy_provisioner "$LEGACY_PROVISIONER_FRESH_VERDICT" ;;
  esac
fi
if [ "$ADVANCED_PROVISION" = 0 ]; then
  printf "Panel id [blank = auto from device name]: " > "$TTY"; read -r PID < "$TTY" || PID=""
  printf "MQTT broker tcp://host:1883 [blank = auto-discover Home Assistant]: " > "$TTY"; read -r BROKER < "$TTY" || BROKER=""
fi

# --- fetch the APK and run the already-authenticated provisioner ---
echo "${B}→ provisioning $TARGET${X}"
ARGS=("$TARGET")
if [ "$PROVISION_NEEDS_APK" = 1 ] && [ -n "$RELEASE_TAG" ]; then
  [ -n "$RELEASE_APK_NAME" ] || { echo "${R}Release installer is missing its APK name.${X}"; exit 1; }
  APK="$TMP_DIR/$RELEASE_APK_NAME"
  if ! curl -fsSL --proto '=https' --proto-redir '=https' --connect-timeout 15 --max-time 300 "$(release_apk_url "$RELEASE_TAG")" -o "$APK"; then
    echo "${R}Could not download the $RELEASE_TAG APK.${X} The release may be incomplete or GitHub may be unavailable; no panel changes were made." >&2
    exit 1
  fi
  ARGS+=(--apk "$APK" --release-tag "$RELEASE_TAG")
elif [ "$PROVISION_NEEDS_APK" = 1 ]; then
  RELEASE_APK_NAME="$RESOLVED_APK_NAME"
  APK="$TMP_DIR/$RELEASE_APK_NAME"
  if ! curl -fsSL --proto '=https' --proto-redir '=https' --connect-timeout 15 --max-time 300 "$RESOLVED_APK_URL" -o "$APK"; then
    echo "${R}Could not download the $PROVISION_REF APK.${X} Check internet/GitHub access and try again; no panel changes were made." >&2
    exit 1
  fi
  ARGS+=(--apk "$APK" --release-tag "$PROVISION_REF")
fi
if [ "$ADVANCED_PROVISION" = 1 ]; then
  ARGS+=("${PROVISION_ARGS[@]}")
else
  [ -n "${PID:-}" ]    && ARGS+=(--id "$PID")
  [ -n "${BROKER:-}" ] && ARGS+=(--mqtt "$BROKER")
fi
# Give provision.sh the terminal as stdin so its own prompts (e.g. downgrade confirm) work.
PROVISION_STDIN=/dev/null
if { : < "$TTY"; } 2>/dev/null; then PROVISION_STDIN="$TTY"; fi
# The legacy provisioner is the first panel-mutating operation in this wrapper. Re-run the complete
# fresh-install proof as the immediately preceding observation so a package install, retained-data
# record, app-data directory, canonical DB, or recovery file that appeared since admission cannot be
# raced into guardless historical code.
if [ "$LEGACY_PROVISIONER_FRESH_GATE" = 1 ]; then
  LEGACY_PROVISIONER_FRESH_VERDICT="$(legacy_provisioner_fresh_verdict)"
  case "$LEGACY_PROVISIONER_INITIAL_FRESH_VERDICT:$LEGACY_PROVISIONER_FRESH_VERDICT" in
    fresh-root:fresh-root|fresh-android-removal:fresh-root|fresh-android-removal:fresh-android-removal) ;;
    fresh-root:fresh-android-removal) refuse_legacy_provisioner root-proof-lost consume ;;
    *) refuse_legacy_provisioner "$LEGACY_PROVISIONER_FRESH_VERDICT" consume ;;
  esac
fi
if ! bash "$SCRIPT" "${ARGS[@]}" < "$PROVISION_STDIN"; then
  echo "${R}${B}ha-paneld installation did not complete.${X}" >&2
  echo "Read the failed item above, correct it, and run the same installer command again. Existing panel configuration was not deliberately removed." >&2
  exit 1
fi
