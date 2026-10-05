#!/usr/bin/env bash
# Build the two JNI libraries' binding code for the host JVM, so a unit test can load them against the
# real Kotlin classes: libhapaneld_mww registers its natives by class name in JNI_OnLoad and
# libhapaneld_led exports Java_<package>_<class>_<method> symbols, and a package or class rename that
# misses either fails only at load time on a panel, where the wake word silently reports unavailable.
#
#   build-host-jni.sh <output-directory>
#
# Only the binding is compiled. The wake-word engine stays unresolved: shared objects may leave
# symbols undefined, and lazy binding (-z lazy) resolves them at their first call, which JNI_OnLoad
# never makes. The shipped wake-word prebuilts are tied to these same sources by
# tools/wakeword/build-prebuilt.sh verify.
set -euo pipefail

out=${1:?usage: build-host-jni.sh <output-directory>}
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cpp="$here/../../main/cpp"
mww="$cpp/microwakeword"
java_home=${JAVA_HOME:-$(dirname "$(dirname "$(readlink -f "$(command -v javac)")")")}
jni=(-I"$java_home/include" -I"$java_home/include/linux")

mkdir -p "$out"
cc -shared -fPIC -Wall -Werror "${jni[@]}" -I"$here/include" \
  "$cpp/led_jni.c" -o "$out/libhapaneld_led.so"
c++ -std=c++17 -shared -fPIC -Wall -Wno-write-strings "${jni[@]}" -I"$mww" \
  -I"$mww/third_party/tflite-micro" -I"$mww/third_party/kissfft" \
  -I"$mww/third_party/flatbuffers/include" -I"$mww/third_party/gemmlowp" -I"$mww/third_party/ruy" \
  -DFIXED_POINT=16 -DTF_LITE_STATIC_MEMORY -DTF_LITE_DISABLE_X86_NEON \
  "$mww/MicroWakeWord_jni.cpp" -Wl,-z,lazy -o "$out/libhapaneld_mww.so"
