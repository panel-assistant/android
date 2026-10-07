# Changes to the vendored Sendspin sources

The vendored trees under `third_party/` are upstream copies with only the pruning listed in [`THIRD_PARTY.md`](THIRD_PARTY.md) and the changes below. Re-apply these after any upstream bump.

## 1. No inbound WebSocket listener (`sendspin-cpp/src/connection_manager.cpp`)

The client normally listens on port 8928 so a server can dial in. The panel app only ever connects out to Panel Assistant, so nothing else should be able to reach the client. With `SendspinClientConfig::server_port == 0` (which the JNI shim sets) `maybe_start_ws_server()` never starts the server. The only other reader of `server_port` is the `set_port()` call that configures the server object, which then stays unstarted; `SendspinWsServer::stop()` and `tick()` are no-ops for an unstarted host server.

## 2. Android logging (`sendspin-cpp/src/platform/logging.h`)

The host `SS_LOG*` macros write to stderr, which Android discards. When `__ANDROID__` is defined they call `__android_log_print` with tag `sendspin` at the matching priority, keeping the runtime level filter; the non-Android host path is unchanged. `libhapaneld_sendspin.so` links `log`.

## 3. Build-level changes (no source edits)

- `-U_FORTIFY_SOURCE -D_FORTIFY_SOURCE=0` on the `noise_c` target only. noise-c `src/protocol/patterns.c` (`noise_pattern_expand`) `memchr`s `NOISE_MAX_TOKENS - 2` bytes over a shorter static pattern array; bionic's FORTIFY aborts the first handshake (`FORTIFY: memchr: prevented 62-byte read from 10-byte buffer`). ESP-IDF has no FORTIFY, so upstream never sees it. The proper fix is a bounded loop in `patterns.c` upstream.
- FetchContent is redirected to the vendored trees: `FETCHCONTENT_FULLY_DISCONNECTED=ON` and `FETCHCONTENT_SOURCE_DIR_{ARDUINOJSON,MICRO_FLAC,NOISE_C}` point at `third_party/`, so configure never touches the network. The optional third codec is disabled (`SENDSPIN_ENABLE_OPUS=OFF`) and its library is not vendored; micro-flac is still compiled because `src/decoder.cpp` references it unconditionally, but the app advertises PCM only.

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

## 5. App-supplied WebSocket transport (`sendspin-cpp/cmake/host.cmake`, and `transport/`)

The host build's client connection is IXWebSocket, which this build had without TLS, so a panel reaching Home Assistant over `https` could not dial `wss://`. With `SENDSPIN_HOST_TRANSPORT_DIR` set (our `CMakeLists.txt` points it at `transport/`), `host.cmake` takes `client_connection.cpp` from that directory instead of the IXWebSocket sources, searches it before `src/host` for headers, and neither fetches nor links IXWebSocket; `src/host/network_info.cpp` is still used. IXWebSocket is no longer vendored.

`transport/` holds:

- `client_connection.{h,cpp}`: `SendspinClientConnection` with the IXWebSocket class's interface and behaviour (open, complete messages into the inbound ring, close reporting, the released-while-connecting close), carried by a bridge: `start()` queues an open request for the app, sends queue frames, and the app (media/SendspinSocketPump.kt, over the app's own Ktor WebSocket client and therefore its TLS trust) delivers the socket's open, messages and close back. Each socket has a transport id, so frames from a replaced socket are dropped.
- `bridge.h`: the app side of the bridge for the JNI shim (`nTakeOutbound`, `nDeliver`).
- `server_connection.h`, `ws_server.h`: stand-ins for the inbound listener, which never starts (patch 1 already keeps it off).

```diff
--- a/cmake/host.cmake	2026-10-07 01:44:47.569923615 +0000
+++ b/cmake/host.cmake	2026-10-07 01:44:47.577889339 +0000
@@ -10,6 +10,12 @@
     #   - src: private implementation headers
     # ESP networking headers live in src/esp/ (only added to ESP builds).
     # =========================================================================
+    # SENDSPIN_HOST_TRANSPORT_DIR (patch): an application-supplied WebSocket transport replaces the
+    # IXWebSocket one. Its directory provides client_connection.{h,cpp}, server_connection.h and
+    # ws_server.h, and is searched before src/host; IXWebSocket is then neither fetched nor linked.
+    if(SENDSPIN_HOST_TRANSPORT_DIR)
+        target_include_directories(${TARGET_LIB} PUBLIC ${SENDSPIN_HOST_TRANSPORT_DIR})
+    endif()
     target_include_directories(${TARGET_LIB} PUBLIC ${SOURCE_DIR}/src/host)
     target_include_directories(${TARGET_LIB} PUBLIC ${SOURCE_DIR}/include)
     target_include_directories(${TARGET_LIB} PRIVATE ${SOURCE_DIR}/src)
@@ -21,7 +27,12 @@
     # =========================================================================
     # Host networking sources (IXWebSocket-based implementations)
     # =========================================================================
-    target_sources(${TARGET_LIB} PRIVATE ${SENDSPIN_HOST_SOURCES})
+    if(SENDSPIN_HOST_TRANSPORT_DIR)
+        target_sources(${TARGET_LIB} PRIVATE ${SENDSPIN_HOST_TRANSPORT_DIR}/client_connection.cpp
+                                             ${SOURCE_DIR}/src/host/network_info.cpp)
+    else()
+        target_sources(${TARGET_LIB} PRIVATE ${SENDSPIN_HOST_SOURCES})
+    endif()

     # =========================================================================
     # Compiler settings
@@ -86,17 +97,19 @@
         target_link_libraries(${TARGET_LIB} PUBLIC micro_opus)
     endif()

-    # IXWebSocket (WebSocket server/client for host networking)
-    set(USE_TLS OFF CACHE BOOL "" FORCE)
-    set(USE_ZLIB OFF CACHE BOOL "" FORCE)
-    FetchContent_Declare(
-        IXWebSocket
-        GIT_REPOSITORY https://github.com/machinezone/IXWebSocket.git
-        GIT_TAG        v12.0.1
-        GIT_SHALLOW    TRUE
-    )
-    FetchContent_MakeAvailable(IXWebSocket)
-    target_link_libraries(${TARGET_LIB} PUBLIC ixwebsocket)
+    if(NOT SENDSPIN_HOST_TRANSPORT_DIR)
+        # IXWebSocket (WebSocket server/client for host networking)
+        set(USE_TLS OFF CACHE BOOL "" FORCE)
+        set(USE_ZLIB OFF CACHE BOOL "" FORCE)
+        FetchContent_Declare(
+            IXWebSocket
+            GIT_REPOSITORY https://github.com/machinezone/IXWebSocket.git
+            GIT_TAG        v12.0.1
+            GIT_SHALLOW    TRUE
+        )
+        FetchContent_MakeAvailable(IXWebSocket)
+        target_link_libraries(${TARGET_LIB} PUBLIC ixwebsocket)
+    endif()

     # Threading support (for shim implementations)
     find_package(Threads REQUIRED)
```

## 6. First-chunk timestamp of each stream (`sendspin-cpp/include/sendspin/player_role.h`, `sendspin-cpp/src/player_role_impl.h`, `sendspin-cpp/src/player_role.cpp`)

The panel decides which announcement a stream belongs to by exact correlation: Panel Assistant sends `stream_start_us` (the server-clock time of the stream's first sample) with each streamed event, and the panel owns a stream only if its first audio chunk carries that timestamp. Upstream keeps chunk timestamps inside the player role. `handle_stream_start()` now sets `first_chunk_pending`, and `handle_binary()` reports the next chunk accepted for decoding through a new `PlayerRoleListener::on_stream_first_chunk(int64_t server_timestamp_us)` (default no-op, so other listeners are unaffected). Both run on the inbound dispatch thread, in wire order, so the reported chunk is always the stream's first. The callback can precede `on_stream_start()`, which the main loop drains later; the JNI shim pairs the two and queues the raw timestamp (event 5, read with `nEventValue`) right after its stream's start.

```diff
--- a/include/sendspin/player_role.h
+++ b/include/sendspin/player_role.h
@@ -107,6 +107,14 @@

     /// @brief Called when the output delay is changed by the server
     virtual void on_output_delay_changed(uint16_t /*delay_ms*/) {}
+
+    /// @brief Called with the server timestamp of the first audio chunk accepted after each
+    /// stream/start
+    ///
+    /// ha-paneld addition. Fires on the thread that dispatches inbound messages, in order with
+    /// stream/start and the chunks, and so possibly before on_stream_start() (which the main loop
+    /// drains later); implementations must be thread-safe.
+    virtual void on_stream_first_chunk(int64_t /*server_timestamp_us*/) {}
 };

 /**
--- a/src/player_role.cpp
+++ b/src/player_role.cpp
@@ -341,6 +341,11 @@
         SS_LOGV(TAG, "Audio chunk carries no encoded frame");
         return;
     }
+    // ha-paneld: report the first chunk's server timestamp after each stream start (PATCHES.md 6).
+    if (this->first_chunk_pending && this->listener != nullptr) {
+        this->first_chunk_pending = false;
+        this->listener->on_stream_first_chunk(chunk->timestamp_us);
+    }
     // roles/player/v1.md "client/hello player@v1 support object": the server keeps the
     // advertised buffer_capacity, which the quota covers at the smallest chunk size.
     (void)inbound.hand_message(message,
@@ -352,6 +357,7 @@
 }

 void PlayerRole::Impl::handle_stream_start(const ServerPlayerStreamObject& player_obj) {
+    this->first_chunk_pending = true;  // ha-paneld (PATCHES.md 6)
     const uint32_t generation = this->cleanup_generation.load(std::memory_order_acquire);
     bool header_sent = false;
     // Numbers the codec header and the STREAM_START, so the sync task starts the stream only on
--- a/src/player_role_impl.h
+++ b/src/player_role_impl.h
@@ -193,6 +193,9 @@
     std::unique_ptr<EventState> event_state;
     Inbox* inbox{nullptr};
     PlayerRoleListener* listener{nullptr};
+    /// ha-paneld: set by handle_stream_start(), cleared by the next accepted chunk, which is reported
+    /// through PlayerRoleListener::on_stream_first_chunk(). Inbound dispatch thread only.
+    bool first_chunk_pending{false};
     SendspinPersistenceProvider* persistence{nullptr};
     std::unique_ptr<SyncTask> sync_task;

```
