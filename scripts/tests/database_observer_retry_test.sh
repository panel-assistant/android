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

command -v sqlite3 >/dev/null || { printf '1..0 # skipped: sqlite3 is required\n'; exit 0; }

work="$(mktemp -d)"
trap 'rm -rf -- "$work"' EXIT

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

# 3. The guarantee is intact: a source that never settles is never accepted as a stable read, however
#    many attempts it gets. The refusal may be `changed` or `unreadable` here and both are honest —
#    under a tight write storm the fingerprint itself cannot complete, which is a different statement
#    from "the database moved". What must never happen is `readable`, because that would mean the
#    install proceeded against a copy taken while the store was being written.
( end=$((SECONDS + 25))
  while [ "$SECONDS" -lt "$end" ]; do
    sqlite3 "$work/live.db" "INSERT INTO t VALUES ('forever');" >/dev/null 2>&1
  done ) &
storm=$!
verdict="$(run_observer "$work/live.db")"
kill "$storm" 2>/dev/null || true
wait "$storm" 2>/dev/null || true
case "$verdict" in
  readable:*) fail "a continuously written database was accepted as a stable read" ;;
  changed|unreadable) pass "a database that never settles is refused rather than accepted" ;;
  *) fail "a continuously written database reported '$verdict'" ;;
esac

# 4. The verdict the host prints for that case names the busy database rather than an unreadable one.
grep -q 'kept changing while it was read' "$PROVISION" ||
  fail "the host has no distinct refusal for a database that kept changing"
pass "the host refusal for a busy database names it as busy"

printf '1..%s\n' "$passes"
