#!/usr/bin/env bash
# The on-panel transaction script's stale-helper removal.
#
# Landing panel, 2026-09-20: `install_system` aborted at `remove_noncanonical_helpers` because the
# removal named /vendor/etc/init/hapaneld-helper.rc, /vendor was a read-only mount, and toybox 0.7.4
# fails on an ABSENT path there despite -f. Every NSPanel 86 is in exactly that state, so the step
# could never succeed on them. These cases run the shipped function against an `rm` that reproduces
# that behaviour, so the regression is caught on any host.
set -u

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
PROVISION="$ROOT/scripts/provision.sh"
passes=0
fail() { printf 'not ok - %s\n' "$*"; exit 1; }
pass() { passes=$((passes + 1)); printf 'ok %s - %s\n' "$passes" "$*"; }

work="$(mktemp -d)"
trap 'rm -rf -- "$work"' EXIT

# The function as shipped, taken from the transaction script rather than copied here, so the test
# cannot pass against a definition this file invented.
sed -n '/^remove_if_present() {$/,/^}$/p' "$PROVISION" > "$work/subject.sh"
[ -s "$work/subject.sh" ] || fail "remove_if_present is not defined in the transaction script"
grep -q 'for stale in' "$work/subject.sh" || fail "extracted subject is not the removal loop"

# An `rm` that behaves like toybox 0.7.4 on a read-only mount: refuses a path that is not there.
mkdir -p "$work/bin"
cat > "$work/bin/rm" <<'STUB'
#!/usr/bin/env bash
for arg in "$@"; do
  case "$arg" in -*) continue ;; esac
  [ -e "$arg" ] || { echo "rm: $arg: No such file or directory" >&2; exit 1; }
  /bin/rm -f -- "$arg" || exit 1
done
exit 0
STUB
chmod +x "$work/bin/rm"

run_subject() ( PATH="$work/bin:$PATH"; . "$work/subject.sh"; remove_if_present "$@" )

# 1. The exact landing-panel shape: every named path absent, one of them on the unforgiving mount.
run_subject "$work/absent-a" "$work/absent-b" && pass "absent paths are not a failure" \
  || fail "absent paths still abort the install (the landing panel defect)"

# 2. A path that is really there is really removed.
: > "$work/present"
run_subject "$work/present" || fail "a present path made the removal fail"
[ -e "$work/present" ] && fail "a present path survived removal" || pass "a present path is removed"

# 3. Mixed list: the present one goes, the absent ones are tolerated.
: > "$work/present2"
run_subject "$work/absent-c" "$work/present2" "$work/absent-d" || fail "a mixed list failed"
[ -e "$work/present2" ] && fail "the present path in a mixed list survived" \
  || pass "a mixed list removes what exists and tolerates what does not"

# 4. The guarantee still holds: something that exists and cannot be removed is a failure. This is
#    what stops the fix from degrading into "ignore every error".
mkdir -p "$work/undeletable"
: > "$work/undeletable/file"
chmod 500 "$work/undeletable"
if [ "$(id -u)" = 0 ]; then
  printf 'ok %s - skipped: root removes a file in a read-only directory anyway\n' "$((passes + 1))"
  passes=$((passes + 1))
else
  run_subject "$work/undeletable/file" && fail "an unremovable present path was reported as removed" \
    || pass "an unremovable present path is still a failure"
fi
chmod 700 "$work/undeletable"

printf '1..%s\n' "$passes"
