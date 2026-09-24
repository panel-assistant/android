#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"

# Targeted regression guard for the novice-facing entry points that promise commands requiring no
# source checkout. It intentionally checks fenced literal scripts/... invocations; it is not a
# general Markdown parser or a policy for developer/contributor documentation.
#
# Most former docs/*.md pages are now one-line stubs pointing at panel-assistant.io (docs hub
# migration, slice 3): they carry no fenced code and trivially pass this check. Only pages that
# still hold real content stay listed here.
checkout_free_docs=(
  README.md
  docs/profiles/unofficial/yc-sm10p.md
)

failed=0
for doc in "${checkout_free_docs[@]}"; do
  while IFS=: read -r line text; do
    printf '%s:%s: consumer command assumes a source checkout: %s\n' "$doc" "$line" "$text" >&2
    failed=1
  done < <(
    awk '
      /<!-- source-checkout-only -->/ {
        allow_next_fence = 1
        next
      }
      /^```/ {
        if (!in_fence) {
          in_fence = 1
          checkout_only = allow_next_fence
          allow_next_fence = 0
        } else {
          in_fence = 0
          checkout_only = 0
        }
        next
      }
      allow_next_fence && $0 !~ /^[[:space:]]*$/ {
        allow_next_fence = 0
      }
      in_fence && !checkout_only &&
        $0 ~ /(^|[|;&[:space:]])(\.\/)?scripts\/[A-Za-z0-9._\/-]+/ {
          print NR ":" $0
      }
    ' "$doc"
  )
done

if (( failed )); then
  cat >&2 <<'EOF'
Checkout-free entry points must not tell ordinary users to execute a repository-relative script.
Use a checkout-free download command, prefer an on-panel UI, or put
<!-- source-checkout-only --> immediately before a code block that genuinely requires a checkout.
EOF
  exit 1
fi

# Pin consumer-facing behavior to the shipped code, not to docs/*.md prose — the fleet-recipe,
# backup-contract and API-currency claims formerly pinned here now live only on
# panel-assistant.io/hardware and manage pages, since docs/provisioning.md, provisioning-safety.md
# and api.md are one-line stubs (docs hub migration, slice 3). These checks still pin the actual
# shipped scripts, which is the part a docs edit cannot silently break.
if ! grep -Fq -- 'Reset is irreversible and makes no backup' scripts/install.sh ||
   grep -Fq -- 'backs the configuration up first' scripts/install.sh; then
  printf 'scripts/install.sh: checkout-free reset help contradicts the no-backup contract\n' >&2
  failed=1
fi

implementation_seams=(
  'io.github.maxlyth.hapaneld.action.PREPARE_UPGRADE'
  'HAPANELD_UPGRADE_READY_V1:'
  'exec-out'
  '".backup '\''@STAGE@/ha-paneld.db'\''"'
  'host_sha256 "$host_db" "the database copy"'
  'continuing the ordinary in-place upgrade WITHOUT a database restore point'
  '[ "$RESET_CONFIG" = 1 ] || auto_export_before_upgrade'
  '[ "$RESET_CONFIG" = 1 ] || snapshot_panel_database'
)
for seam in "${implementation_seams[@]}"; do
  if ! grep -Fq -- "$seam" scripts/provision.sh; then
    printf 'scripts/provision.sh: shipped backup seam is missing %s\n' "$seam" >&2
    failed=1
  fi
done
if grep -Fq -- 'df -P -k /data' scripts/provision.sh; then
  printf 'scripts/provision.sh: fixed /data capacity gate returned\n' >&2
  failed=1
fi

if (( failed )); then
  exit 1
fi

echo "checkout-free entry-point command regression: PASS"
