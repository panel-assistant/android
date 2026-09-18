#!/usr/bin/env bash
# Mutation proof for the dual-uid identity work: each production line below is reverted to what it
# said (or would have said) without this change, and the suite that covers it MUST fail. An assertion
# that survives its own mutation is asserting nothing.
#
#   bash test/mutation-dual-uid.sh
#
# Run from helper/. Every mutation is applied to a private copy of the tree, never to the worktree.
set -uo pipefail
cd "$(dirname "$0")/.."
ROOT="$PWD"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

pass=0
fail=0

# apply <name> <target-suite> <file> <python-mutation>
# target-suite is "unit" or "peer-auth".
apply() {
    local name="$1" suite="$2" file="$3" mutation="$4"
    local tree="$WORK/$RANDOM$RANDOM"
    mkdir -p "$tree"
    cp -r "$ROOT/src" "$ROOT/test" "$ROOT/Makefile" "$tree/"
    if ! python3 - "$tree/$file" <<PY
import sys
path = sys.argv[1]
source = open(path).read()
$mutation
open(path, 'w').write(mutated)
PY
    then
        printf 'MUTATION ERROR  %s (could not apply)\n' "$name"
        fail=$((fail + 1))
        return
    fi
    if cmp -s "$ROOT/$file" "$tree/$file"; then
        printf 'MUTATION ERROR  %s (edit changed nothing — the anchor no longer matches)\n' "$name"
        fail=$((fail + 1))
        return
    fi

    local log="$tree/run.log"
    local target binary
    case "$suite" in
        unit)      target="build/unit";      binary="./build/unit" ;;
        peer-auth) target="build/peer-auth"; binary="./build/peer-auth" ;;
        *) printf 'MUTATION ERROR  %s (unknown suite %s)\n' "$name" "$suite"; fail=$((fail + 1)); return ;;
    esac

    if ! (cd "$tree" && make "$target" >"$log" 2>&1); then
        # A mutation that no longer compiles still proves the line is load-bearing, but say so.
        printf 'PROVEN (build)  %s\n' "$name"
        pass=$((pass + 1))
        return
    fi
    if (cd "$tree" && $binary >>"$log" 2>&1); then
        printf 'SURVIVED        %s — the suite still passed without the change\n' "$name"
        fail=$((fail + 1))
    else
        printf 'PROVEN          %s :: %s\n' "$name" \
            "$(grep -m1 -E '^(FAIL|UNIT FAILED)' "$log" | cut -c1-120)"
        pass=$((pass + 1))
    fi
}

apply 'peer auth: only the first package data directory is probed' peer-auth src/main.c "
mutated = source.replace(
    '''    for (size_t i = 0; i < HELPER_APP_PACKAGE_COUNT; i++) {''',
    '''    for (size_t i = 0; i < 1; i++) {''')
"

apply 'peer auth: an unknown uid is admitted' peer-auth src/main.c "
mutated = source.replace('    return HELPER_CALLER_NONE;\n}', '    return HELPER_CALLER_ROOT;\n}', 1)
"

apply 'is_critical_pkg: the known ids are no longer critical' unit src/util.c "
mutated = source.replace('    return helper_known_package(s);', '    return 0;')
"

apply 'valid_apk_path: the directory separator is not required' unit src/util.c "
mutated = source.replace(
    '    return strncmp(path, directory, n) == 0 && path[n] == \'/\';',
    '    return strncmp(path, directory, n) == 0;')
"

apply 'valid_apk_path: only the legacy package is accepted' unit src/util.c "
mutated = source.replace(
    '    for (size_t i = 0; i < HELPER_APP_PACKAGE_COUNT && !known; i++)',
    '    for (size_t i = 0; i < 1 && !known; i++)')
"

apply 'UNINSTALL: any package id is accepted' unit src/sysctl.c "
mutated = source.replace(
    '    if (!valid_pkg(pkg) || !helper_known_package(pkg)) return -1;\n    if (helper_caller_for_package(pkg) == caller) return -1;',
    '    if (!valid_pkg(pkg)) return -1;\n    if (helper_caller_for_package(pkg) == caller) return -1;')
"

apply 'UNINSTALL: a caller may remove itself' unit src/sysctl.c "
mutated = source.replace(
    '    if (helper_caller_for_package(pkg) == caller) return -1;',
    '    if (0 && helper_caller_for_package(pkg) == caller) return -1;', 1)
"

apply 'GRANT: any package id is accepted' unit src/sysctl.c "
mutated = source.replace(
    '    if (!valid_pkg(pkg) || !helper_known_package(pkg)) return -1;\n    for (size_t i = 0; i < sizeof GRANTS',
    '    if (!valid_pkg(pkg)) return -1;\n    for (size_t i = 0; i < sizeof GRANTS')
"

apply 'GRANT BATTERY: no fallback to the dumpsys form' unit src/sysctl.c "
mutated = source.replace(
    '    if (command_ok(sysexec_run_argv(\"/system/bin/cmd\", modern, 1))) return 0;',
    '    return command_ok(sysexec_run_argv(\"/system/bin/cmd\", modern, 1)) ? 0 : -1;\n    if (0) {}')
"

apply 'GRANT ACCESSIBILITY: the component is appended unconditionally' unit src/sysctl.c "
mutated = source.replace('    if (!present) {', '    if (1) {')
"

apply 'GRANT ACCESSIBILITY: the shorthand spelling is not recognised' unit src/sysctl.c "
mutated = source.replace(
    '        present = strcmp(entry, component) == 0 || strcmp(entry, shorthand) == 0;',
    '        present = strcmp(entry, component) == 0;')
"

apply 'GRANT ACCESSIBILITY: an unset list is treated as the literal \"null\"' unit src/sysctl.c "
mutated = source.replace(
    '    if (strcmp(existing, \"null\") == 0) existing[0] = \'\\\\0\', length = 0;\n', '', 1)
"

apply 'GRANT ACCESSIBILITY: the existing list is written back unchecked' unit src/sysctl.c "
mutated = source.replace(
    '    if (!accessibility_list_plausible(existing)) return -1;',
    '    if (0 && !accessibility_list_plausible(existing)) return -1;', 1)
"

apply 'GRANT ACCESSIBILITY: an unreadable list is treated as empty' unit src/sysctl.c "
mutated = source.replace(
    '    if (!command_ok(sysexec_capture_argv(\"/system/bin/settings\", get, existing, sizeof existing))) return -1;',
    '    (void)sysexec_capture_argv(\"/system/bin/settings\", get, existing, sizeof existing);')
"

apply 'HELPERSTATUS: only the first accepted package is reported' unit src/dispatch.c "
mutated = source.replace(
    '    for (size_t i = 0; i < HELPER_APP_PACKAGE_COUNT; i++) {',
    '    for (size_t i = 0; i < 1; i++) {')
"

apply 'HELPERSTATUS: the caller identity is hard-coded' unit src/dispatch.c "
mutated = source.replace('helper_caller_name(ctx->caller)', 'helper_caller_name(HELPER_CALLER_ROOT)')
"

apply 'server_serve: the connection identity is dropped' unit src/server.c "
mutated = source.replace(
    '    conn_ctx ctx = { .fd = cfd, .subscribed = 0, .caller = caller };',
    '    (void)caller;\n    conn_ctx ctx = { .fd = cfd, .subscribed = 0, .caller = HELPER_CALLER_ROOT };')
"

printf '\n%d proven, %d survived\n' "$pass" "$fail"
[ "$fail" -eq 0 ]
