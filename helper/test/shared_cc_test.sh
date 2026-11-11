#!/usr/bin/env bash
# Usage: shared_cc_test.sh <cc>
#
# test/shared_cc.sh must never link a binary from a cached object when it cannot compute that object's
# key. A preprocessing error still prints most of the preprocessed text, so with a warm cache the
# partial text can hash to the key of an earlier good build; the wrapper must fail instead.
set -uo pipefail
cc=${1:-cc}
wrapper=$(cd "$(dirname "$0")" && pwd)/shared_cc.sh
work=$(mktemp -d)
trap 'rm -rf -- "$work"' EXIT
failures=0
check() { if eval "$2"; then echo "PASS $1"; else echo "FAIL $1"; failures=$((failures + 1)); fi; }

printf 'int main(void) { return 0; }\n' > "$work/main.c"
bash "$wrapper" "$work/obj" "$cc" -O1 -Werror "$work/main.c" -o "$work/good" >/dev/null 2>&1
check 'a clean source builds and caches its object' '[ -x "$work/good" ] && ls "$work"/obj/main.*.o >/dev/null 2>&1'

printf '#error deliberate_preprocessing_failure\n' >> "$work/main.c"
bash "$wrapper" "$work/obj" "$cc" -O1 -Werror "$work/main.c" -o "$work/bad" >"$work/bad.log" 2>&1
status=$?
check 'a preprocessing error with a warm cache exits non-zero' '[ "$status" -ne 0 ]'
check 'and links no binary' '[ ! -e "$work/bad" ]'
check 'and shows the preprocessor error' 'grep -q deliberate_preprocessing_failure "$work/bad.log"'

bash "$wrapper" "$work/obj" "$cc" -O1 -Werror "$work/missing.c" -o "$work/missing" >/dev/null 2>&1
status=$?
check 'a missing source exits non-zero' '[ "$status" -ne 0 ] && [ ! -e "$work/missing" ]'

# A compiler that writes a good object and still fails: the failure must stop the build, and the
# object must not be published to the cache for a later binary to reuse.
printf 'int main(void) { return 0; }\n' > "$work/fresh.c"
printf '%s\n' '#!/usr/bin/env bash' \
	"\"$cc\" \"\$@\" || exit" \
	'for argument in "$@"; do [ "$argument" = -c ] && { echo deliberate_compile_failure >&2; exit 42; }; done' \
	'exit 0' > "$work/failing-cc"
chmod +x "$work/failing-cc"
bash "$wrapper" "$work/obj" "$work/failing-cc" -O1 -Werror "$work/fresh.c" -o "$work/failed" >"$work/failed.log" 2>&1
status=$?
check 'a compiler that exits non-zero after writing its object exits non-zero' '[ "$status" -ne 0 ]'
check 'and links no binary' '[ ! -e "$work/failed" ]'
check 'and publishes no object' '! ls "$work"/obj/fresh.*.o >/dev/null 2>&1'
check 'and shows the compiler error' 'grep -q deliberate_compile_failure "$work/failed.log"'

echo "shared_cc: $failures failures"
[ "$failures" -eq 0 ]
