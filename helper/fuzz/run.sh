#!/usr/bin/env bash
# Deterministic sanitizer smoke, or bounded clang libFuzzer exploration of the same real core.
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
HELPER="$(cd "$HERE/.." && pwd)"
OUT="$HERE/build"
mkdir -p "$OUT"
MODE=smoke
REPLAY=""
if [[ "${1:-}" == --replay ]]; then MODE=libfuzzer; REPLAY="${2:?Expected a crash file}"; shift 2; fi
if [[ "${1:-}" == --libfuzzer ]]; then MODE=libfuzzer; shift; fi
if [[ "${1:-}" == --help ]]; then
    echo 'Usage: run.sh [smoke-iterations] | --libfuzzer [seconds] [libFuzzer options] | --replay crash-file'
    echo 'CLANG selects clang for libFuzzer; CC selects the smoke compiler. Default: 100000 smoke inputs / 30 fuzz seconds.'
    exit 0
fi
LIMIT="${1:-}"
if (($#)); then shift; fi
LIMIT="${LIMIT:-$([[ "$MODE" == smoke ]] && echo 100000 || echo 30)}"
[[ "$LIMIT" =~ ^[0-9]+$ ]] || { echo 'Expected a nonnegative iteration/seconds limit' >&2; exit 2; }
read -r -a CORE_SRCS <<< "$(cd "$HELPER" && make --no-print-directory -s print-core)"
WRAPS=(open openat fopen opendir mkdir mkdirat unlink unlinkat rmdir rename renameat chmod fchmod fchown fstatat stat lstat access faccessat ioctl kill fork pthread_create connect execve linkat fsetxattr socket bind waitpid setpgid setsid _exit adb_recovery_ensure)
LINK=()
for symbol in "${WRAPS[@]}"; do LINK+=("-Wl,--wrap=$symbol"); done
FLAGS=(-O1 -g -Wall -Wextra -Werror -DHAPANELD_TEST -DHAPANELD_TEST_SKIP_SELF_BINDING
    '-DHAPANELD_BUILD_ID="aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"'
    -fsanitize=address,undefined -fno-sanitize-recover=all -fno-omit-frame-pointer -Isrc -Itest)
if [[ "$MODE" == libfuzzer ]]; then
    COMPILER="${CLANG:-clang}"
    FLAGS+=(-DHAPANELD_LIBFUZZER -fsanitize=fuzzer)
else
    COMPILER="${CC:-gcc}"
    [[ "$COMPILER" == *clang* ]] || FLAGS+=(-Wno-format-truncation)
fi
python3 "$HERE/seeds.py" "$OUT/seeds"
BINARY="$OUT/fuzz_$MODE"
echo ">> compiling $MODE driver with $COMPILER (real core, isolated filesystem + no-exec boundary)"
(cd "$HELPER" && "$COMPILER" "${FLAGS[@]}" "${CORE_SRCS[@]}" test/sysexec_stub.c \
    "$HERE/sandbox.c" "$HERE/fuzz_parser.c" -lpthread "${LINK[@]}" -o "$BINARY")
HAPANELD_FUZZ_ROOT="$(mktemp -d "$OUT/sandbox.XXXXXXXX")"
export HAPANELD_FUZZ_ROOT
trap 'rm -rf -- "$HAPANELD_FUZZ_ROOT"' EXIT
export ASAN_OPTIONS="${ASAN_OPTIONS:-abort_on_error=1:detect_leaks=1}"
export UBSAN_OPTIONS="${UBSAN_OPTIONS:-halt_on_error=1:print_stacktrace=1}"
# The target writes persisted-record seeds from real Guard handlers before reading the corpus.
export HAPANELD_FUZZ_EMIT_RECORDS="$OUT/seeds"
if [[ "$MODE" == smoke ]]; then
    "$BINARY" "$LIMIT" "$OUT/seeds"
    echo 'SANITIZER SMOKE OK'
else
    mkdir -p "$OUT/corpus" "$OUT/artifacts"
    INPUTS=("$OUT/corpus" "$OUT/seeds")
    if [[ -n "$REPLAY" ]]; then INPUTS=("$REPLAY"); fi
    "$BINARY" -max_total_time="$LIMIT" -timeout=10 -max_len=8192 -seed=12648430 \
        -dict="$OUT/commands.dict" -artifact_prefix="$OUT/artifacts/" -print_final_stats=1 \
        "${INPUTS[@]}" "$@"
    echo 'BOUNDED LIBFUZZER RUN OK'
fi
