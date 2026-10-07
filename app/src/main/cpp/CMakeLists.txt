cmake_minimum_required(VERSION 3.22.1)

project(hapaneld_native C CXX)

# Built for the fleet ABIs (arm64-v8a, armeabi-v7a) — see app/build.gradle.kts ndk.abiFilters.
# Produces libhapaneld_led.so, loaded by hardware/NativeLed.kt via System.loadLibrary("hapaneld_led").
add_library(hapaneld_led SHARED led_jni.c)

find_library(log-lib log)
target_link_libraries(hapaneld_led ${log-lib})

# libhapaneld_mww.so, the micro-wake-word scorer loaded by assist/wakeword/NativeMicroWakeWord.kt, is not
# built here. Its vendored TFLite Micro sources took minutes to compile per ABI and rarely change, so the
# stripped library is committed under app/src/main/jniLibs/ and rebuilt with
# tools/wakeword/build-prebuilt.sh, which records and verifies it against these sources.
