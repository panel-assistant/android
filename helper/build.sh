#!/usr/bin/env bash
#
# Build the hapaneld-helper root helper for Android (armeabi-v7a + arm64-v8a), using the same
# version-pinned Docker toolchain image as the app (tools/build). Only Docker is required.
#
#   ./helper/build.sh
#   -> helper/dist/<abi>/hapaneld-helper
#
# HOST_WORKDIR override: see tools/build/build.sh (same nested/remote-daemon caveat).
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO="$(cd "$HERE/.." && pwd)"
cd "$REPO"

IMAGE="ha-paneld-build:latest"
HOST_WORK="${HOST_WORKDIR:-$REPO}"

echo ">> Building toolchain image ($IMAGE) if missing/changed…"
docker build -t "$IMAGE" tools/build

echo ">> Compiling hapaneld-helper for both ABIs…"
BUILD_ID="$(helper/source-id.sh)"
# The daemon is split by capability under helper/src/ — compile the whole module set into the one
# binary (see helper/README.md). -Ihelper/src for the cross-module headers.
docker run --rm -e HAPANELD_BUILD_ID="$BUILD_ID" -v "$HOST_WORK":/work -w /work "$IMAGE" bash -c '
  set -e
  TC=$ANDROID_SDK_ROOT/ndk/27.0.12077973/toolchains/llvm/prebuilt/linux-x86_64/bin
  SRCS=helper/src/*.c
  mkdir -p helper/dist/armeabi-v7a helper/dist/arm64-v8a
  "$TC/armv7a-linux-androideabi26-clang" -O2 -s -Ihelper/src -DHAPANELD_BUILD_ID=\"$HAPANELD_BUILD_ID\" -o helper/dist/armeabi-v7a/hapaneld-helper $SRCS -llog
  "$TC/aarch64-linux-android26-clang"    -O2 -s -Ihelper/src -DHAPANELD_BUILD_ID=\"$HAPANELD_BUILD_ID\" -o helper/dist/arm64-v8a/hapaneld-helper  $SRCS -llog
'

echo ">> Built:"
find helper/dist -name hapaneld-helper
