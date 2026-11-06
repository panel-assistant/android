#!/usr/bin/env bash
#
# Build + run the helper sanitizer smoke harness.
#
#   ./helper/fuzz/run.sh [iterations]      # default 100,000 deterministic random iters (+ corpus/bounds)
#
# It links the production CORE_SRCS capability/parser modules with test/sysexec_stub.c; main.c and the
# real sysexec.c are deliberately excluded. This is not a semantic oracle or independent validation.
# A sanitizer-visible fault aborts; a clean run only establishes that exercised paths stayed clean.
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
HELPER="$(cd "$HERE/.." && pwd)"
OUT="$HERE/build"
mkdir -p "$OUT"

ITERS="${1:-100000}"

# Reuse the single source-of-truth module list from the Makefile so the fuzzer can't drift from the
# daemon (add a module to CORE_SRCS and it's covered here automatically).
CORE_SRCS="$(cd "$HELPER" && make --no-print-directory -s print-core)"

echo ">> compiling sanitizer smoke harness (real src/*.c + sysexec stub, ASan+UBSan)…"
( cd "$HELPER" && \
  gcc -O1 -g -Wall -Wextra -Werror -Wno-format-truncation \
      -DHAPANELD_TEST \
      -fsanitize=address,undefined -fno-sanitize-recover=all \
      -Isrc -Itest $CORE_SRCS test/sysexec_stub.c "$HERE/fuzz_parser.c" -lpthread -Wl,--wrap=adb_recovery_ensure -o "$OUT/fuzz_parser" )

echo ">> running $ITERS iterations…"
ASAN_OPTIONS=abort_on_error=1:detect_leaks=1 \
UBSAN_OPTIONS=halt_on_error=1:print_stacktrace=1 \
  "$OUT/fuzz_parser" "$ITERS"

echo "SANITIZER SMOKE OK"
