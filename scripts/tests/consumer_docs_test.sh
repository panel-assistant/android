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

echo "checkout-free entry-point command regression: PASS"
