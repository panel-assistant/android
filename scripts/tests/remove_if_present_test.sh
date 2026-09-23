#!/usr/bin/env bash
# The on-panel transaction script's stale-helper removal.
#
# Measured on an Android 8.1 panel, 2026-09-20: `install_system` aborted at `remove_noncanonical_helpers` because the
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
  [ -e "$arg" ] || [ -L "$arg" ] || { echo "rm: $arg: No such file or directory" >&2; exit 1; }
  [ "$arg" != "${REFUSE_REMOVAL:-}" ] || exit 1
  /bin/rm -f -- "$arg" || exit 1
done
exit 0
STUB
chmod +x "$work/bin/rm"

run_subject() ( PATH="$work/bin:$PATH"; . "$work/subject.sh"; remove_if_present "$@" )

# 1. The exact shape measured on hardware: every named path absent, one of them on the unforgiving mount.
run_subject "$work/absent-a" "$work/absent-b" && pass "absent paths are not a failure" \
  || fail "absent paths still abort the install (the defect measured on hardware)"

# 2. A path that is really there is really removed.
: > "$work/present"
run_subject "$work/present" || fail "a present path made the removal fail"
[ -e "$work/present" ] && fail "a present path survived removal" || pass "a present path is removed"

# 3. Mixed list: the present one goes, the absent ones are tolerated.
: > "$work/present2"
run_subject "$work/absent-c" "$work/present2" "$work/absent-d" || fail "a mixed list failed"
[ -e "$work/present2" ] && fail "the present path in a mixed list survived" \
  || pass "a mixed list removes what exists and tolerates what does not"

# 4. A directory cannot be removed by rm -f even as root, as used by CI.
mkdir -p "$work/undeletable"
: > "$work/undeletable/file"
run_subject "$work/undeletable" && fail "an unremovable present path was reported as removed" \
  || pass "an unremovable present path is still a failure"

# 5. -e follows symlinks, so a dangling stale helper needs an explicit no-follow existence check.
ln -s "$work/missing-target" "$work/broken-link"
run_subject "$work/broken-link" || fail "a removable broken symlink made the removal fail"
[ ! -L "$work/broken-link" ] || fail "a broken symlink survived successful removal"
pass "a broken symlink is removed"

# 6. Check the postcondition too: a failed unlink must not turn a dangling link into success.
ln -s "$work/missing-target" "$work/refused-link"
REFUSE_REMOVAL="$work/refused-link" run_subject "$work/refused-link" \
  && fail "an unremovable broken symlink was reported as removed" \
  || pass "an unremovable broken symlink is still a failure"
[ -L "$work/refused-link" ] || fail "the failed-unlink fixture did not retain its symlink"

# Rollback uses the same absence guarantee for both staging and live paths. Exercise its shipped
# functions too: fixing installation alone leaves recovery stuck on a missing read-only vendor rc.
for function_name in restore_or_remove restore_or_remove_v2 flag file_exact root_owned hash_matches file_sha256; do
  sed -n "/^${function_name}() {$/,/^}$/p" "$PROVISION" >> "$work/subject.sh"
done

run_restore() (
  PATH="$work/bin:$PATH"
  . "$work/subject.sh"
  transaction_id=0123456789abcdef0123456789abcdef
  # Model Android's root identity independently of the host user. Hash, mode, symlink and link-count
  # checks still execute the production code against actual files; ownership refusal is injected below.
  chown() { [ "$1" = 0:0 ]; }
  stat() {
    if [ "$1" = -c ] && [ "$2" = %u:%g ]; then
      printf '%s\n' "${RESTORE_OWNER:-0:0}"
    else
      command stat "$@"
    fi
  }
  sync() { :; }
  "$@"
)

for restore_function in restore_or_remove restore_or_remove_v2; do
  case_dir="$work/$restore_function"
  mkdir -p "$case_dir"
  rollback_live="$case_dir/helper"
  rollback_recovery="$case_dir/recovery"
  rollback_marker="$case_dir/marker"
  case "$restore_function" in
    restore_or_remove) rollback_staging="$rollback_live.rollback-v1-0123456789abcdef0123456789abcdef" ;;
    *) rollback_staging="$rollback_live.rollback-0123456789abcdef0123456789abcdef" ;;
  esac
  printf 'SLOT=0\nSLOT_SHA256=-\n' > "$rollback_marker"
  run_restore "$restore_function" SLOT "$rollback_recovery" "$rollback_live" 700 "$rollback_marker" \
    || fail "$restore_function rejects absent staging or live paths on read-only storage"
  pass "$restore_function accepts absent staging and live paths"

  printf incumbent > "$rollback_live"
  REFUSE_REMOVAL="$rollback_live" run_restore "$restore_function" SLOT "$rollback_recovery" "$rollback_live" 700 "$rollback_marker" \
    && fail "$restore_function accepts an undeletable live path"
  [ "$(cat "$rollback_live")" = incumbent ] || fail "$restore_function changed the protected live file"
  pass "$restore_function refuses an undeletable live path"

  printf retained > "$rollback_staging"
  REFUSE_REMOVAL="$rollback_staging" run_restore "$restore_function" SLOT "$rollback_recovery" "$rollback_live" 700 "$rollback_marker" \
    && fail "$restore_function accepts undeletable staging"
  [ "$(cat "$rollback_staging")" = retained ] && [ "$(cat "$rollback_live")" = incumbent ] \
    || fail "$restore_function changed files after staging removal failed"
  pass "$restore_function refuses undeletable staging before changing the live file"
  rm -f "$rollback_staging"

  printf recovered > "$rollback_recovery"
  recovery_sha=$(sha256sum "$rollback_recovery"); recovery_sha=${recovery_sha%% *}
  printf 'SLOT=1\nSLOT_SHA256=%s\n' "$recovery_sha" > "$rollback_marker"
  run_restore "$restore_function" SLOT "$rollback_recovery" "$rollback_live" 700 "$rollback_marker" \
    || fail "$restore_function cannot restore the exact recorded file"
  cmp -s "$rollback_recovery" "$rollback_live" && [ "$(stat -c %a "$rollback_live")" = 700 ] && [ ! -e "$rollback_staging" ] \
    || fail "$restore_function did not publish the exact recovery with its required mode"
  pass "$restore_function restores the exact recorded file and required mode"

  printf corrupt > "$rollback_recovery"
  run_restore "$restore_function" SLOT "$rollback_recovery" "$rollback_live" 700 "$rollback_marker" \
    && fail "$restore_function accepted a recovery hash mismatch"
  [ "$(cat "$rollback_live")" = recovered ] || fail "$restore_function replaced the live file with corrupt recovery"
  pass "$restore_function refuses mismatched recovery bytes before publication"

  printf recovered > "$rollback_recovery"
  RESTORE_OWNER=1000:1000 run_restore "$restore_function" SLOT "$rollback_recovery" "$rollback_live" 700 "$rollback_marker" \
    && fail "$restore_function accepted non-root-owned staging"
  [ "$(cat "$rollback_live")" = recovered ] || fail "$restore_function replaced the live file despite wrong ownership"
  pass "$restore_function refuses wrong ownership before publication"
done

printf '1..%s\n' "$passes"
