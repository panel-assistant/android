#!/usr/bin/env bash
# Installs Android SDK packages on a hosted runner and proves each one landed. sdkmanager can report
# success after a truncated download ("Error reading Zip content"), leaving a package missing until
# Gradle fails much later, so every attempt is checked against the installed package directories.
set -euo pipefail

[ "$#" -gt 0 ] || { echo "usage: install-android-sdk.sh PACKAGE..." >&2; exit 2; }
sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
[ -n "$sdk" ] || { echo "::error::ANDROID_HOME is not set" >&2; exit 1; }

for attempt in 1 2 3; do
  sdkmanager "$@" || true
  missing=()
  for package in "$@"; do
    [ -f "$sdk/${package//;//}/source.properties" ] || missing+=("$package")
  done
  [ "${#missing[@]}" -eq 0 ] && exit 0
  echo "::warning::Android SDK install attempt $attempt left ${missing[*]} missing"
  [ "$attempt" -lt 3 ] && sleep 15
done
echo "::error::Android SDK packages still missing after three attempts: ${missing[*]}"
exit 1
