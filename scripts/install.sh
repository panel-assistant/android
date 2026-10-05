#!/usr/bin/env bash
#
# Authenticated checkout-free inspection for existing panels.
# Panel Assistant in Home Assistant owns panel app installation and updates.
# The release workflow pins RELEASE_TAG and PROVISION_COMMIT for historical inspection.
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
  echo "Panel app installs and updates require live Panel Assistant in Home Assistant."
  echo "Use Panel Assistant's installer to select a compatible build."
  echo
  echo "Read-only inspection: install.sh [--prerelease] --provision PANEL-IP[:PORT] --verify"
  echo "Settings export:      install.sh [--prerelease] --provision PANEL-IP[:PORT] --export FILE"
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

if [ "$PROVISION_NEEDS_APK" = 1 ]; then
  echo "Panel app installs and updates require live Panel Assistant in Home Assistant." >&2
  echo "Open Panel Assistant's installer to select a compatible build. Nothing was installed or changed." >&2
  exit 1
fi

if [ -t 1 ]; then B=$'\033[1m'; R=$'\033[31m'; G=$'\033[32m'; Y=$'\033[33m'; X=$'\033[0m'
else B=; R=; G=; Y=; X=; fi
REPO="panel-assistant/android"
PROVISION_REF="${RELEASE_TAG:-}"
PROVISION_URL=""
RESOLVED_APK_URL=""
RESOLVED_APK_NAME=""
valid_release_tag() { printf '%s\n' "$1" | grep -Eq '^v[0-9]+\.[0-9]+\.[0-9]+(-[0-9A-Za-z][0-9A-Za-z.-]*)?$'; }
valid_commit() { printf '%s\n' "$1" | grep -Eq '^[0-9a-f]{40}$'; }
release_asset_url() { printf 'https://github.com/%s/releases/download/%s/%s\n' "$REPO" "$1" "$2"; }
# Releases from 0.9.10 publish their APKs with a `.bin` suffix, so that no updater shipped before
# Panel Assistant 0.7.1 finds an asset ending `.apk`.
release_apk_name() { printf 'panel-assistant-%s-manual-setup-required.apk.bin\n' "$1"; }
# The bridge keeps the historical asset name, plus that suffix. A release published before the successor existed
# carries only that APK, and its own provisioner is the one that installs it.
bridge_apk_name() { printf 'ha-paneld-%s-manual-setup-required.apk.bin\n' "$1"; }
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
  # the bridge otherwise, always by exact published URL rather than by position. A release from
  # before 0.9.10 published the same names without the `.bin` suffix.
  release_apk_urls="$(printf '%s' "$release_record" | grep -o '"browser_download_url": *"[^"]*\.apk\(\.bin\)\{0,1\}"' | cut -d'"' -f4 || true)"
  if [ -n "$PROVISION_REF" ] && valid_release_tag "$PROVISION_REF"; then
    for candidate_name in "$(release_apk_name "$PROVISION_REF")" "$(bridge_apk_name "$PROVISION_REF")" \
      "$(release_apk_name "$PROVISION_REF" | sed 's/\.bin$//')" "$(bridge_apk_name "$PROVISION_REF" | sed 's/\.bin$//')"; do
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

IP="$ADVANCED_TARGET"
# Loose sanity check (hostname/IPv4[:port]) — catch typos here rather than as an obscure adb error.
case "$IP" in
  *[!0-9a-zA-Z.:-]*|.*|-*) echo "${R}'$IP' doesn't look like an IP address or hostname (optionally :port).${X} Find it on the panel under Settings → About → Status, or in your router's client list."; exit 1 ;;
esac
case "$IP" in *:*) TARGET="$IP" ;; *) TARGET="$IP:5555" ;; esac

# Run only the explicitly requested read-only operation through authenticated release code.
echo "${B}→ inspecting $TARGET${X}"
ARGS=("$TARGET" "${PROVISION_ARGS[@]}")
if ! bash "$SCRIPT" "${ARGS[@]}" < /dev/null; then
  echo "${R}${B}ha-paneld inspection did not complete.${X}" >&2
  echo "Read the failed item above, correct it, and repeat the inspection command." >&2
  exit 1
fi
