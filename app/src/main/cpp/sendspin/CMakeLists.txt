# libhapaneld_sendspin.so: the Sendspin synchronised-audio player (sendspin-cpp, player role only),
# loaded by media/SendspinNative.kt. Every dependency is vendored under third_party/ (see
# THIRD_PARTY.md and PATCHES.md); FetchContent is redirected there and never touches the network.

set(SENDSPIN_THIRD_PARTY ${CMAKE_CURRENT_SOURCE_DIR}/third_party)
set(FETCHCONTENT_FULLY_DISCONNECTED ON CACHE BOOL "" FORCE)
set(FETCHCONTENT_SOURCE_DIR_ARDUINOJSON ${SENDSPIN_THIRD_PARTY}/arduinojson CACHE PATH "" FORCE)
set(FETCHCONTENT_SOURCE_DIR_MICRO_FLAC ${SENDSPIN_THIRD_PARTY}/micro-flac CACHE PATH "" FORCE)
set(FETCHCONTENT_SOURCE_DIR_NOISE_C ${SENDSPIN_THIRD_PARTY}/noise-c CACHE PATH "" FORCE)

set(SENDSPIN_ENABLE_PLAYER ON CACHE BOOL "" FORCE)
foreach(role CONTROLLER METADATA COLOR ARTWORK VISUALIZER SOURCE)
  set(SENDSPIN_ENABLE_${role} OFF CACHE BOOL "" FORCE)
endforeach()
set(SENDSPIN_ENABLE_OPUS OFF CACHE BOOL "" FORCE)
set(BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(SENDSPIN_BUILD_TESTS OFF CACHE BOOL "" FORCE)

# The Panel Assistant session carries the connection (transport/), so there is no second socket and
# IXWebSocket is not built; see PATCHES.md.
set(SENDSPIN_HOST_TRANSPORT_DIR ${CMAKE_CURRENT_SOURCE_DIR}/transport CACHE PATH "" FORCE)
add_subdirectory(third_party/sendspin-cpp)

# noise-c's patterns.c memchr()s past its static pattern arrays; bionic FORTIFY aborts the first
# handshake on it (ESP-IDF has no FORTIFY). Disabled for noise-c only; see PATCHES.md.
target_compile_options(noise_c PRIVATE -U_FORTIFY_SOURCE -D_FORTIFY_SOURCE=0)

add_library(hapaneld_sendspin SHARED sendspin_jni.cpp)
find_library(sendspin-log-lib log)
target_link_libraries(hapaneld_sendspin PRIVATE sendspin ${sendspin-log-lib})
target_compile_features(hapaneld_sendspin PRIVATE cxx_std_20)
target_compile_options(hapaneld_sendspin PRIVATE -ffunction-sections -fdata-sections -fvisibility=hidden -fvisibility-inlines-hidden)
target_link_options(hapaneld_sendspin PRIVATE -Wl,--gc-sections -Wl,--exclude-libs,ALL)
