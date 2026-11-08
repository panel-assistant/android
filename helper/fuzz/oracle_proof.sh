#!/usr/bin/env bash
# Prove every fuzz rule check (oracles.c) can fail: build deliberately broken copies of helper/, run the
# deterministic parser corpus against each, and require that exact rule to report. Then require the
# unmodified helper to pass the same corpus. A rule that never fires here protects nothing.
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
HELPER="$(cd "$HERE/.." && pwd)"
WORK="$(mktemp -d "${TMPDIR:-/tmp}/hapaneld-oracle-proof.XXXXXXXX")"
trap 'rm -rf -- "$WORK"' EXIT

# name ~ target ~ rule ~ file ~ exact original text ~ broken text
MUTATIONS=(
  'reload-critical~parser~critical-package~src/sysctl.c~    if (!valid_pkg(pkg) || is_critical_pkg(pkg)) return -1;
    uint64_t deadline = framework_deadline();~    if (!valid_pkg(pkg)) return -1;
    uint64_t deadline = framework_deadline();'
  'gov-shell~parser~caller-shell~src/sysctl.c~    sscanf(args, "%31s", gov);
    reply(~    sscanf(args, "%31s", gov);
    { const char *const shell[] = { "sh", "-c", gov, NULL }; (void)sysexec_run_argv("/system/bin/sh", shell, 1); }
    reply('
  'zigbee-script~parser~caller-shell~src/sysctl.c~        "exit 2";~        "exit 3";'
  'gov-two-lines~parser~reply-line~src/sysctl.c~    reply(ctx->fd, set_governor(gov) == 0 ? "OK\n" : "ERR\n");~    reply(ctx->fd, set_governor(gov) == 0 ? "OK\nOK\n" : "ERR\nERR\n");'
  'recovery-silent~recovery~reply-line~src/adb_recovery.c~        } else (void)write_all(fd, reply, strlen(reply));
    close(fd);~        }
    close(fd);'
  'cdprelay-port~cdprelay~argument-gate~cdprelay.c~parsed_port > 65535~parsed_port > 65536'
  'gov-no-newline~parser~reply-line~src/sysctl.c~    reply(ctx->fd, set_governor(gov) == 0 ? "OK\n" : "ERR\n");~    reply(ctx->fd, set_governor(gov) == 0 ? "OK" : "ERR");'
)

run_corpus() { # target helper-dir out-dir log
    HAPANELD_FUZZ_HELPER="$2" HAPANELD_FUZZ_OUT="$3" "$HERE/run.sh" --target "$1" 0 >"$4" 2>&1
}

failed=0
for entry in "${MUTATIONS[@]}"; do
    IFS='~' read -r -d '' name target rule file original broken < <(printf '%s\0' "$entry") || true
    copy="$WORK/$name"
    mkdir -p "$copy"
    (cd "$HELPER" && tar --exclude=./build --exclude=./fuzz/build -cf - .) | tar -C "$copy" -xf -
    python3 - "$copy/$file" "$original" "$broken" <<'PY'
import sys
path, original, broken = sys.argv[1:]
text = open(path).read()
if text.count(original) != 1:
    sys.exit(f"mutation site not found exactly once in {path}: {original!r}")
open(path, "w").write(text.replace(original, broken))
PY
    if run_corpus "$target" "$copy" "$WORK/$name.out" "$WORK/$name.log"; then
        echo "FAIL: broken copy '$name' passed; rule '$rule' did not fire"; failed=1
    elif grep -q "FAIL: fuzz-oracle: $rule " "$WORK/$name.log"; then
        echo "ok: broken copy '$name' -> $(grep -m1 'FAIL: fuzz-oracle:' "$WORK/$name.log")"
    else
        echo "FAIL: broken copy '$name' failed without rule '$rule':"; tail -20 "$WORK/$name.log"; failed=1
    fi
done
for target in parser recovery cdprelay; do
    if run_corpus "$target" "$HELPER" "$WORK/clean-$target.out" "$WORK/clean-$target.log"; then
        echo "ok: unmodified helper passes the same $target corpus"
    else
        echo "FAIL: unmodified helper fails the $target corpus:"; tail -20 "$WORK/clean-$target.log"; failed=1
    fi
done
exit "$failed"
