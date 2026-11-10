#!/usr/bin/env bash
# Installs DejaVu Sans, the GitHub-hosted runners' default system-ui font, into a Playwright image whose
# only Latin sans fonts lose to a CJK default. The layout checks are tuned to it. Two pinned packages,
# kept in the directory given (restored from the Actions cache) and fetched only when missing:
# archive.ubuntu.com took 11 to 45 s for these 1.4 MB on Blacksmith runners, and timed out outright on
# 2026-11-10, so the packages come from the first of three Ubuntu mirrors that answers; the pinned hashes
# make the mirror irrelevant to what is installed.
set -euo pipefail
mirrors=(http://azure.archive.ubuntu.com/ubuntu https://mirrors.edge.kernel.org/ubuntu http://archive.ubuntu.com/ubuntu)
fetch() {
  local mirror
  for mirror in "${mirrors[@]}"; do
    curl -fsS --retry 1 --connect-timeout 5 --max-time 30 -o "$1" "$mirror/pool/main/f/fonts-dejavu/$1" && return 0
    echo "fonts: $mirror did not serve $1" >&2
  done
  return 1
}
dir=$1
mkdir -p "$dir"
cd "$dir"
for deb in fonts-dejavu-core_2.37-8_all.deb fonts-dejavu-mono_2.37-8_all.deb; do
  [ -s "$deb" ] || fetch "$deb"
done
printf '%s  %s\n' \
  40049660c194f3b8a2541fc7369efebb10e9f94bdac836a2f38fafedd10fa73a fonts-dejavu-core_2.37-8_all.deb \
  8a599d6553307db7ecb795d2f0e5a301e03234afc75c7358b0ba43466454c89a fonts-dejavu-mono_2.37-8_all.deb | sha256sum -c -
dpkg -i fonts-dejavu-mono_2.37-8_all.deb fonts-dejavu-core_2.37-8_all.deb > /dev/null
fc-match system-ui | grep -q 'DejaVu Sans'
