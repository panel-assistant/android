# Changes to the vendored Sendspin sources

The vendored trees under `third_party/` are upstream copies with only the pruning listed in [`THIRD_PARTY.md`](THIRD_PARTY.md) and the changes below. Re-apply these after any upstream bump.

## 1. No inbound WebSocket listener (`sendspin-cpp/src/connection_manager.cpp`)

The client normally listens on port 8928 so a server can dial in. The panel app only ever connects out to Panel Assistant, so nothing else should be able to reach the client. With `SendspinClientConfig::server_port == 0` (which the JNI shim sets) `maybe_start_ws_server()` never starts the server. The only other reader of `server_port` is the `set_port()` call that configures the server object, which then stays unstarted; `SendspinWsServer::stop()` and `tick()` are no-ops for an unstarted host server.

## 2. Android logging (`sendspin-cpp/src/platform/logging.h`)

The host `SS_LOG*` macros write to stderr, which Android discards. When `__ANDROID__` is defined they call `__android_log_print` with tag `sendspin` at the matching priority, keeping the runtime level filter; the non-Android host path is unchanged. `libhapaneld_sendspin.so` links `log`.

## 3. Build-level changes (no source edits)

- `-U_FORTIFY_SOURCE -D_FORTIFY_SOURCE=0` on the `noise_c` target only. noise-c `src/protocol/patterns.c` (`noise_pattern_expand`) `memchr`s `NOISE_MAX_TOKENS - 2` bytes over a shorter static pattern array; bionic's FORTIFY aborts the first handshake (`FORTIFY: memchr: prevented 62-byte read from 10-byte buffer`). ESP-IDF has no FORTIFY, so upstream never sees it. The proper fix is a bounded loop in `patterns.c` upstream.
- FetchContent is redirected to the vendored trees: `FETCHCONTENT_FULLY_DISCONNECTED=ON` and `FETCHCONTENT_SOURCE_DIR_{ARDUINOJSON,MICRO_FLAC,IXWEBSOCKET,NOISE_C}` point at `third_party/`, so configure never touches the network. The optional third codec is disabled (`SENDSPIN_ENABLE_OPUS=OFF`) and its library is not vendored; micro-flac is still compiled because `src/decoder.cpp` references it unconditionally, but the app advertises PCM only.

## Exact diff

Paths are relative to `third_party/sendspin-cpp/`.

```diff
--- a/src/connection_manager.cpp
+++ b/src/connection_manager.cpp
@@ -1179,6 +1179,10 @@
     if (this->ws_server_ == nullptr || this->ws_server_->is_started() || !this->is_accepting()) {
         return ProtocolTask::NO_DEADLINE;
     }
+    // ha-paneld: server_port 0 disables the inbound listener; only connect_to() reaches the client.
+    if (this->client_->config_.server_port == 0) {
+        return ProtocolTask::NO_DEADLINE;
+    }
     if (now_us < this->ws_server_start_retry_time_us_) {
         return ms_until(this->ws_server_start_retry_time_us_, now_us);
     }
--- a/src/platform/logging.h
+++ b/src/platform/logging.h
@@ -55,6 +55,18 @@
 // Runtime log level - defaults to INFO, settable by the application (e.g., via command line)
 inline int ss_host_log_level = SS_LOG_INFO;

+#ifdef __ANDROID__
+// ha-paneld: route host logging to logcat (tag "sendspin") instead of stderr.
+#include <android/log.h>
+// clang-format off
+#define SS_LOG_ANDROID_(lvl, prio, tag, fmt, ...) do { if (ss_host_log_level >= (lvl)) __android_log_print((prio), "sendspin", "%s: " fmt, tag __VA_OPT__(,) __VA_ARGS__); } while(0)
+#define SS_LOGE(tag, fmt, ...) SS_LOG_ANDROID_(SS_LOG_ERROR,   ANDROID_LOG_ERROR,   tag, fmt __VA_OPT__(,) __VA_ARGS__)
+#define SS_LOGW(tag, fmt, ...) SS_LOG_ANDROID_(SS_LOG_WARN,    ANDROID_LOG_WARN,    tag, fmt __VA_OPT__(,) __VA_ARGS__)
+#define SS_LOGI(tag, fmt, ...) SS_LOG_ANDROID_(SS_LOG_INFO,    ANDROID_LOG_INFO,    tag, fmt __VA_OPT__(,) __VA_ARGS__)
+#define SS_LOGD(tag, fmt, ...) SS_LOG_ANDROID_(SS_LOG_DEBUG,   ANDROID_LOG_DEBUG,   tag, fmt __VA_OPT__(,) __VA_ARGS__)
+#define SS_LOGV(tag, fmt, ...) SS_LOG_ANDROID_(SS_LOG_VERBOSE, ANDROID_LOG_VERBOSE, tag, fmt __VA_OPT__(,) __VA_ARGS__)
+// clang-format on
+#else
 // clang-format off
 #define SS_LOGE(tag, fmt, ...) do { if (ss_host_log_level >= SS_LOG_ERROR)   fprintf(stderr, "E %s: " fmt "\n", tag __VA_OPT__(,) __VA_ARGS__); } while(0)
 #define SS_LOGW(tag, fmt, ...) do { if (ss_host_log_level >= SS_LOG_WARN)    fprintf(stderr, "W %s: " fmt "\n", tag __VA_OPT__(,) __VA_ARGS__); } while(0)
@@ -62,6 +74,7 @@
 #define SS_LOGD(tag, fmt, ...) do { if (ss_host_log_level >= SS_LOG_DEBUG)   fprintf(stderr, "D %s: " fmt "\n", tag __VA_OPT__(,) __VA_ARGS__); } while(0)
 #define SS_LOGV(tag, fmt, ...) do { if (ss_host_log_level >= SS_LOG_VERBOSE) fprintf(stderr, "V %s: " fmt "\n", tag __VA_OPT__(,) __VA_ARGS__); } while(0)
 // clang-format on
+#endif  // __ANDROID__

 namespace sendspin {

```

## 4. Comment wording (`sendspin-cpp/cmake/sources.cmake`)

One upstream comment in the core source list named upstream's contributor-notes file, which is not vendored. The vendored copy reads "see upstream contributor notes on #ifdef discipline" instead. Comment only; no build effect.
