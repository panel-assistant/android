#!/usr/bin/env bash
# Usage: shared_cc.sh <object-dir> <cc> <compiler and linker arguments...>
#
# Builds one test binary the way `cc <flags> a.c b.c ... -o out` would, but compiles each source to an
# object that every other test binary reuses when its preprocessed text and code-generation flags are
# the same. Most test binaries compile the same CORE_SRCS and differ only in a few defines, so this
# compiles each distinct translation unit once instead of once per binary. A miss compiles the real
# source with the full command line, so diagnostics and -Werror are unchanged; the key is the
# preprocessed text plus every non-preprocessor flag, so a define that changes a source changes its key.
set -euo pipefail
dir=$1 cc=$2; shift 2
sources=() pre=() codegen=() link=() out=
while (($#)); do
	case $1 in
		-o) out=$2; shift ;;
		-include) pre+=("$1" "$2"); shift ;;
		-D*|-U*|-I*) pre+=("$1") ;;
		-l*|-Wl,*) link+=("$1") ;;
		*.c) sources+=("$1") ;;
		*) codegen+=("$1") ;;
	esac
	shift
done
[ -n "$out" ] && ((${#sources[@]})) || { echo "shared_cc.sh: need sources and -o" >&2; exit 2; }
mkdir -p "$dir"

# object <source>: prints the shared object path for this source under this command line.
object() {
	local key
	key=$({ printf '%s\0' "$cc" "${codegen[@]}"; "$cc" "${codegen[@]}" "${pre[@]}" -E "$1"; } | sha256sum | cut -c1-32)
	printf '%s/%s.%s.o' "$dir" "$(basename "$1" .c)" "$key"
}
# build <source> <object> <flock option>: compiles the object unless present; returns 1 if the lock is busy.
build() {
	[ -s "$2" ] && return 0
	(
		flock $3 9 || exit 75
		[ -s "$2" ] && exit 0
		"$cc" "${codegen[@]}" "${pre[@]}" -c "$1" -o "$2.tmp.$$"
		mv -f "$2.tmp.$$" "$2"
	) 9>"$2.lock"
}

objects=() deferred=()
for i in "${!sources[@]}"; do
	objects[i]=$(object "${sources[i]}")
	# Another binary compiling the same object now: take the other sources first, then wait for it.
	build "${sources[i]}" "${objects[i]}" -n || { [ $? -eq 75 ] && deferred+=("$i") || exit 1; }
done
for i in "${deferred[@]}"; do build "${sources[i]}" "${objects[i]}" ""; done
"$cc" "${codegen[@]}" "${objects[@]}" "${link[@]}" -o "$out"
