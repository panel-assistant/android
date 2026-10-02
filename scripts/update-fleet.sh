#!/usr/bin/env bash
# Panel Assistant owns panel-app installation and updates for every panel.
set -euo pipefail
case "${1:-}" in
  -h|--help)
    echo "Panel app installs and updates require live Panel Assistant in Home Assistant."
    echo "Use Panel Assistant's installer for each panel."
    echo "Use scripts/provision.sh PANEL --verify or --export FILE for read-only inspection."
    exit 0
    ;;
esac
echo "Panel app installs and updates require live Panel Assistant in Home Assistant." >&2
echo "Use Panel Assistant's installer. No APK was fetched and no panel worker was started." >&2
exit 1
