#!/usr/bin/env bash
# The on-panel database observer's stable read, executed rather than pattern-matched.
#
# The stable read fingerprints the database AND its -wal/-shm/-journal sidecars before and after
# copying them, and refuses if anything moved. A running app writes continuously, and the copy window
# scales with the store, so a single attempt condemned every large panel: measured on hardware, a
# 1 MB store passed while 16 MB, 33 MB and 37 MB stores failed every time, with nothing wrong with
# any of them. The observer now retries. These cases prove the retry exists, that it still refuses
# when the source never settles, and that a refusal says the database was busy rather than unreadable.
set -u

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
PROVISION="$ROOT/scripts/provision.sh"
passes=0
fail() { printf 'not ok - %s\n' "$*"; exit 1; }
pass() { passes=$((passes + 1)); printf 'ok %s - %s\n' "$passes" "$*"; }

command -v sqlite3 >/dev/null || fail "sqlite3 is required"

work="$(mktemp -d)"
trap 'rm -rf -- "$work"' EXIT

# Exercise the dependency check with a PATH that really has no sqlite3. The child stops before
# creating fixtures, so only dirname (used to resolve ROOT) is needed in this environment.
mkdir "$work/no-sqlite"
ln -s "$(command -v dirname)" "$work/no-sqlite/dirname"
PATH="$work/no-sqlite" "$BASH" "${BASH_SOURCE[0]}" > "$work/no-sqlite.log" 2>&1
status=$?
[ "$status" = 1 ] || fail "missing sqlite3 returned $status instead of failing"
grep -qx 'not ok - sqlite3 is required' "$work/no-sqlite.log" ||
  fail "missing sqlite3 did not name the required dependency"
pass "missing sqlite3 is a hard failure"

# Take the functions from the shipped transaction script, never a copy written here, so the test
# cannot pass against a definition this file invented.
subject="$work/subject.sh"
: > "$subject"
for fn in source_digest source_fingerprint inspect_database; do
  body="$(sed -n "/^${fn}() {$/,/^}$/p" "$PROVISION")"
  [ -n "$body" ] || fail "extracting $fn from the transaction script produced nothing"
  printf '%s\n' "$body" >> "$subject"
done
grep -q 'inspected_attempt' "$subject" || fail "the extracted stable read has no retry at all"

sqlite3 "$work/live.db" "PRAGMA journal_mode=WAL; CREATE TABLE t(v); INSERT INTO t VALUES ('x');" >/dev/null

run_observer() (
  cd "$work" || exit 1
  sqlite3_bin=$(command -v sqlite3)
  observer_tmp="$work/tmp"
  mkdir -p "$observer_tmp"
  # shellcheck disable=SC1090
  . "$subject"
  inspect_database "$1" "${2:-stable}"
)

# 1. A quiet database reads cleanly, which is the control: without this the later cases could pass
#    against an observer that never succeeds at all.
verdict="$(run_observer "$work/live.db")"
case "$verdict" in
  readable:*) pass "a quiet database is read and reported readable" ;;
  *) fail "a quiet database reported '$verdict'" ;;
esac

# Force failures at the in-loop fingerprints, independently of writer timing and the final
# post-quick_check call. Persist calls outside command-substitution subshells. Remove only retry
# sleeps: the shipped copy, fingerprint, SQLite check and six-attempt budget still execute.
run_observer_failing_in_loop() (
  cd "$work" || exit 1
  sqlite3_bin=$(command -v sqlite3)
  observer_tmp="$work/in-loop-$1"
  mkdir -p "$observer_tmp"
  # shellcheck disable=SC1090
  . "$subject"
  eval "$(declare -f source_fingerprint | sed '1s/^source_fingerprint/real_source_fingerprint/')"
  counter="$observer_tmp/fingerprint-calls"
  printf '0\n' > "$counter"
  failure=$1
  sleep() { :; }
  source_fingerprint() {
    local n
    n=$(( $(cat "$counter") + 1 ))
    printf '%s\n' "$n" > "$counter"
    case "$failure" in
      before-once) [ "$n" != 1 ] || return 1 ;;
      after-once) [ "$n" != 2 ] || return 1 ;;
      before-always) return 1 ;;
      after-always) [ $((n % 2)) != 0 ] || return 1 ;;
      after-then-before) [ "$n" = 1 ] || return 1 ;;
    esac
    real_source_fingerprint "$@"
  }
  inspect_database "$work/live.db" stable
)

# A transient failure at either position must recover. Persistent before failures have never
# observed a readable source; persistent after failures have. The mixed schedule also proves that
# a later failed before call cannot erase the earlier successful observation.
while read -r scenario expected calls; do
  verdict="$(run_observer_failing_in_loop "$scenario")"
  [ "$verdict" = "$expected" ] ||
    fail "$scenario fingerprint failure reported '$verdict', expected '$expected'"
  actual_calls=$(cat "$work/in-loop-$scenario/fingerprint-calls")
  [ "$actual_calls" = "$calls" ] ||
    fail "$scenario used $actual_calls fingerprint calls, expected $calls"
  pass "$scenario fingerprint failure returns $expected after $calls calls"
done <<'CASES'
before-once readable:0:ok 4
after-once readable:0:ok 5
before-always unreadable 6
after-always changed 12
after-then-before changed 7
CASES

# 2. A database written to during the read still succeeds, because the retry outlasts the writer.
#    The writer stops well inside the retry budget, so a single-attempt observer fails this and the
#    retrying one passes: this is the case that regressed every large panel.
( for _ in 1 2 3; do
    sqlite3 "$work/live.db" "INSERT INTO t VALUES ('churn');" >/dev/null 2>&1
    sleep 1
  done ) &
churn=$!
verdict="$(run_observer "$work/live.db")"
wait "$churn" 2>/dev/null || true
case "$verdict" in
  readable:*) pass "a database written to during the read is retried and still read" ;;
  *) fail "a database that settled within the budget reported '$verdict'" ;;
esac

# 3. A source that never settles is refused, and refused as CHANGED. An earlier version of this case
#    accepted `unreadable` too, on the reasoning that a fingerprint which cannot complete is a
#    different statement from "the database moved". That was the wrong call: it let the assertion
#    pass against code whose write-storm answer was `unreadable` six times in seven, while the
#    changelog promised the opposite. Weakening the assertion hid the defect instead of naming it,
#    so the case now demands the verdict the product claims to give.
#    The writer is one connection fed an endless stream of statements, and the observer starts only
#    once its first row has landed. It used to be a loop starting a fresh sqlite3 per row, with the
#    observer started at once, and on a hosted runner that raced both ways: the observer finished a
#    whole read before the first row landed (36 ms after case 2), or found a quiet gap between two
#    process starts. Either way a read that really was stable came back `readable`, a correct answer
#    about a database that was not being written, so the race was in the test, not the reader.
yes "INSERT INTO t VALUES ('forever');" | timeout 25 sqlite3 "$work/live.db" >/dev/null 2>&1 &
storm=$!
started=0
for _ in $(seq 100); do
  [ "$(sqlite3 "$work/live.db" "SELECT count(*) FROM t WHERE v = 'forever';" 2>/dev/null)" -gt 0 ] 2>/dev/null &&
    { started=1; break; }
  sleep 0.05
done
[ "$started" = 1 ] || fail "the write storm never wrote a row"
verdict="$(run_observer "$work/live.db")"
kill "$storm" 2>/dev/null || true
wait "$storm" 2>/dev/null || true
case "$verdict" in
  changed) pass "a database that never settles is refused as changed, not as unreadable" ;;
  readable:*) fail "a continuously written database was accepted as a stable read" ;;
  unreadable) fail "a busy database was reported unreadable, which is what the changelog denies" ;;
  *) fail "a continuously written database reported '$verdict'" ;;
esac

# 4. The same thing again, deterministically. Case 3 races a real writer, so it only sometimes drives
#    the path that matters: mutating the post-copy fingerprint back to a terminal `unreadable` killed
#    it two runs in four. That is not proof. Here `source_fingerprint` is replaced after the subject
#    is sourced so that the FINAL call — the one after quick_check — fails outright, which is exactly
#    what a writer touching a sidecar mid-read does. A busy store must still be reported as busy.
: > "$work/quiet.db"
sqlite3 "$work/quiet.db" "PRAGMA journal_mode=WAL; CREATE TABLE t(v); INSERT INTO t VALUES ('x');" >/dev/null

run_observer_failing_final() (
  cd "$work" || exit 1
  sqlite3_bin=$(command -v sqlite3)
  observer_tmp="$work/tmp2"
  mkdir -p "$observer_tmp"
  # shellcheck disable=SC1090
  . "$subject"
  real_source_fingerprint() { command true; }
  eval "$(declare -f source_fingerprint | sed '1s/^source_fingerprint/real_source_fingerprint/')"
  # The counter lives in a file, not a variable: inspect_database calls this from inside command
  # substitution, so a shell variable would be incremented in a subshell and lost every time — which
  # is exactly how the first version of this case silently tested nothing and reported `readable`.
  counter="$observer_tmp/fingerprint-calls"
  printf '0\n' > "$counter"
  source_fingerprint() {
    local n
    n=$(( $(cat "$counter") + 1 ))
    printf '%s\n' "$n" > "$counter"
    # Two good fingerprints bracket a clean copy; every third call is the post-copy one and fails.
    if [ $((n % 3)) = 0 ]; then return 1; fi
    real_source_fingerprint "$@"
  }
  inspect_database "$1" stable
)

verdict="$(run_observer_failing_final "$work/quiet.db")"
case "$verdict" in
  changed) pass "a post-copy fingerprint that keeps failing is reported busy, not unreadable" ;;
  unreadable) fail "a failing post-copy fingerprint still ends the read as unreadable" ;;
  readable:*) fail "a source that could not be re-verified was accepted as a stable read" ;;
  *) fail "a failing post-copy fingerprint reported '$verdict'" ;;
esac

# 5. The verdict the host prints for that case names the busy database rather than an unreadable one.
grep -q 'kept changing while it was read' "$PROVISION" ||
  fail "the host has no distinct refusal for a database that kept changing"
pass "the host refusal for a busy database names it as busy"

printf '1..%s\n' "$passes"
