#!/usr/bin/env bash
# The environment the root helper hands to the platform actuators it execs.
#
# `pm`, `am`, `appops` and `settings` are not binaries. Each is a shell wrapper whose body is
# `exec app_process … com.android.commands.…`, and app_process ABORTS without ANDROID_ROOT and
# ANDROID_DATA. A daemon launched from init inherits neither, so every actuator the helper ran died
# with SIGABRT while the identical command succeeded from a root shell that had them. Measured on an
# Android 8.1 panel: `env -i PATH=/system/bin:/vendor/bin pm path android` returns 134, and adding
# exactly those two returns 0.
#
# This is a source-shape assertion, and deliberately labelled as one: the helper is built in a
# container this suite cannot assume, and there is no app_process on the host to exec. It cannot
# prove the actuators run — only that the environment they need has not been quietly removed again,
# which is the regression that actually happened.
set -u

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
SYSEXEC="$ROOT/helper/src/sysexec.c"
passes=0
fail() { printf 'not ok - %s\n' "$*"; exit 1; }
pass() { passes=$((passes + 1)); printf 'ok %s - %s\n' "$passes" "$*"; }

[ -f "$SYSEXEC" ] || fail "helper/src/sysexec.c is missing, so the actuator environment cannot be checked"

env_block="$(sed -n '/^static char \*const clean_env\[\] = {$/,/^};$/p' "$SYSEXEC")"
[ -n "$env_block" ] || fail "clean_env could not be found in sysexec.c"

for required in 'ANDROID_ROOT=/system' 'ANDROID_DATA=/data'; do
  printf '%s\n' "$env_block" | grep -Fq "\"$required\"" ||
    fail "clean_env no longer carries $required, so every app_process wrapper the helper execs will abort"
done
pass "the actuator environment carries ANDROID_ROOT and ANDROID_DATA"

# PATH must stay too: the wrappers resolve app_process through it.
printf '%s\n' "$env_block" | grep -Fq '"PATH=' ||
  fail "clean_env no longer carries PATH"
pass "the actuator environment still carries PATH"

# The environment is meant to stay minimal. A large one is not a failure, but it is a review signal:
# every variable here is inherited by every command the helper runs as root.
entries="$(printf '%s\n' "$env_block" | grep -c '^    "')"
[ "$entries" -le 8 ] ||
  fail "clean_env has grown to $entries entries; keep the root actuator environment minimal"
pass "the actuator environment is still minimal ($entries entries)"

# Every exec in this file must use that environment. A future actuator added with execv or execvp
# would inherit the daemon's own environment instead, which is exactly the bug this guards.
stray="$(grep -nE '\bexec(v|vp|l|lp)\(' "$SYSEXEC" || true)"
[ -z "$stray" ] || fail "sysexec.c execs without the clean environment: $stray"
pass "every exec in the helper's exec path uses the clean environment"

printf '1..%s\n' "$passes"
