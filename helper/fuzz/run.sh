#!/usr/bin/env bash
# Deterministic sanitizer smoke, or bounded clang libFuzzer exploration of the same real core.
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# HAPANELD_FUZZ_HELPER builds a modified copy of helper/ (oracle_proof.sh uses it for broken copies).
HELPER="$(cd "${HAPANELD_FUZZ_HELPER:-$HERE/..}" && pwd)"
OUT="${HAPANELD_FUZZ_OUT:-$HERE/build}"
mkdir -p "$OUT"
OUT="$(cd "$OUT" && pwd)"
TARGET=parser
if [[ "${1:-}" == --target ]]; then TARGET="${2:?Expected target}"; shift 2; fi
[[ "$TARGET" == parser || "$TARGET" == cdprelay || "$TARGET" == recovery ]] || { echo "Unknown fuzz target: $TARGET" >&2; exit 2; }
OUT="$OUT/$TARGET"
mkdir -p "$OUT"
MODE=smoke
REPLAY=""
if [[ "${1:-}" == --replay ]]; then MODE=libfuzzer; REPLAY="${2:?Expected a crash file}"; shift 2; fi
if [[ "${1:-}" == --libfuzzer ]]; then MODE=libfuzzer; shift; fi
if [[ "${1:-}" == --help ]]; then
    echo 'Usage: run.sh [--target parser|cdprelay|recovery] [smoke-iterations | --libfuzzer seconds [options] | --replay crash-file]'
    echo 'HAPANELD_FUZZ_OUT selects the persistent output root (targets have separate corpora).'
    echo 'HAPANELD_FUZZ_PHASE=build|run splits compilation from execution; HAPANELD_FUZZ_HELPER builds another helper/ copy.'
    echo 'CLANG selects clang for libFuzzer; CC selects the smoke compiler. Default: 100000 smoke inputs / 30 fuzz seconds.'
    exit 0
fi
LIMIT="${1:-}"
if (($#)); then shift; fi
LIMIT="${LIMIT:-$([[ "$MODE" == smoke ]] && echo 100000 || echo 30)}"
[[ "$LIMIT" =~ ^[0-9]+$ ]] || { echo 'Expected a nonnegative iteration/seconds limit' >&2; exit 2; }
# Decimal, whatever the leading zeros: 00 is 0 and 035 is 35, so every zero form meets the check below.
LIMIT=$((10#$LIMIT))
# libFuzzer reads -max_total_time=0 as "no limit", so a zero campaign would never end. Zero is
# meaningful only for a corpus merge (-merge=1), which ends on its own, or a replay.
if [[ "$MODE" == libfuzzer && -z "$REPLAY" && "$LIMIT" == 0 && " $* " != *" -merge=1 "* ]]; then
    echo 'A coverage-guided run needs at least 1 second (0 would be unbounded)' >&2; exit 2
fi
read -r -a CORE_SRCS <<< "$(cd "$HELPER" && make --no-print-directory -s print-core)"
WRAPS=(dispatch reply sysexec_run_constant sysexec_run_argv sysexec_run_argv_timeout sysexec_run_argv_deadline sysexec_capture_argv sysexec_capture_argv_timeout sysexec_stream_argv sysexec_start_argv sysexec_start_stdout_argv open openat fopen opendir mkdir mkdirat unlink unlinkat rmdir rename renameat chmod fchmod fchown fstatat stat lstat access faccessat ioctl kill fork pthread_create connect execve linkat fsetxattr socket bind waitpid setpgid setsid _exit adb_recovery_ensure sendmsg)
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
BINARY="$OUT/fuzz_$MODE"
# HAPANELD_FUZZ_PHASE=build writes seeds, compiles and stops; =run reuses both. make test builds every
# test before running any, because compiling beside deadline-asserting tests starves them.
PHASE="${HAPANELD_FUZZ_PHASE:-all}"
[[ "$PHASE" == all || "$PHASE" == build || "$PHASE" == run ]] || { echo "Unknown HAPANELD_FUZZ_PHASE: $PHASE" >&2; exit 2; }
if [[ "$PHASE" != run && "$TARGET" == parser ]]; then
    python3 "$HERE/seeds.py" "$OUT/seeds"
elif [[ "$PHASE" != run ]]; then
    python3 "$HERE/target_seeds.py" "$TARGET" "$OUT"
fi
if [[ "$PHASE" == run ]]; then
    [[ -x "$BINARY" ]] || { echo "No prebuilt $BINARY; run with HAPANELD_FUZZ_PHASE=build first" >&2; exit 2; }
else
echo ">> compiling $MODE driver with $COMPILER (real core, isolated filesystem + no-exec boundary)"
if [[ "$TARGET" == parser ]]; then
    (cd "$HELPER" && "$COMPILER" "${FLAGS[@]}" "${CORE_SRCS[@]}" test/sysexec_stub.c \
        "$HERE/sandbox.c" "$HERE/oracles.c" "$HERE/fuzz_parser.c" -lpthread "${LINK[@]}" -o "$BINARY")
elif [[ "$TARGET" == recovery ]]; then
    RECOVERY_SRCS=()
    for source in "${CORE_SRCS[@]}"; do
        [[ "$source" == src/adb_recovery.c ]] || RECOVERY_SRCS+=("$source")
    done
    # This translation unit includes the real owner parser with fork and peer fixtures.
    (cd "$HELPER" && "$COMPILER" "${FLAGS[@]}" "${RECOVERY_SRCS[@]}" test/sysexec_stub.c \
        "$HERE/sandbox.c" "$HERE/oracles.c" "$HERE/fuzz_recovery.c" -lpthread "${LINK[@]}" -o "$BINARY")
else
    (cd "$HELPER" && "$COMPILER" "${FLAGS[@]}" -I. "$HERE/fuzz_cdprelay.c" -o "$BINARY")
fi
fi
[[ "$PHASE" != build ]] || exit 0
HAPANELD_FUZZ_ROOT="$(mktemp -d "$OUT/sandbox.XXXXXXXX")"
export HAPANELD_FUZZ_ROOT
trap 'rm -rf -- "$HAPANELD_FUZZ_ROOT"' EXIT
export ASAN_OPTIONS="${ASAN_OPTIONS:-abort_on_error=1:detect_leaks=1}"
export UBSAN_OPTIONS="${UBSAN_OPTIONS:-halt_on_error=1:print_stacktrace=1}"
# The target writes persisted-record seeds from real Guard handlers before reading the corpus.
if [[ "$TARGET" == parser ]]; then export HAPANELD_FUZZ_EMIT_RECORDS="$OUT/seeds"; fi
if [[ "$MODE" == smoke ]]; then
    "$BINARY" "$LIMIT" "$OUT/seeds"
    echo 'SANITIZER SMOKE OK'
else
    mkdir -p "$OUT/corpus" "$OUT/artifacts"
    INPUTS=("$OUT/corpus" "$OUT/seeds")
    if [[ -n "$REPLAY" ]]; then INPUTS=("$REPLAY"); fi
    MAX_LEN=8192
    [[ "$TARGET" != recovery ]] || MAX_LEN=65696
    "$BINARY" -max_total_time="$LIMIT" -timeout=10 -max_len="$MAX_LEN" -seed=12648430 \
        -dict="$OUT/commands.dict" -artifact_prefix="$OUT/artifacts/" -print_final_stats=1 \
        "${INPUTS[@]}" "$@"
    echo 'BOUNDED LIBFUZZER RUN OK'
fi
