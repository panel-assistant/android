// Copyright 2026 Sendspin Contributors
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

/// @file client.h
/// @brief Main public API for the Sendspin synchronized audio streaming client

#pragma once

#include "sendspin/config.h"
#include "sendspin/persistence_keys.h"
#include "sendspin/types.h"

#include <array>
#include <atomic>
#include <cstddef>
#include <cstdint>
#include <memory>
#include <optional>
#include <string>
#include <vector>

namespace sendspin {

// Forward declarations for enabled roles
#ifdef SENDSPIN_ENABLE_ARTWORK
class ArtworkRole;
#endif
#ifdef SENDSPIN_ENABLE_COLOR
class ColorRole;
#endif
#ifdef SENDSPIN_ENABLE_CONTROLLER
class ControllerRole;
struct ClientCommandControllerObject;
#endif
#ifdef SENDSPIN_ENABLE_METADATA
class MetadataRole;
#endif
#ifdef SENDSPIN_ENABLE_PLAYER
class PlayerRole;
#endif
#ifdef SENDSPIN_ENABLE_SOURCE
class SourceRole;
#endif
#ifdef SENDSPIN_ENABLE_VISUALIZER
class VisualizerRole;
#endif

// Forward declarations for listener types
struct GroupUpdateObject;

/// @brief Listener for SendspinClient events
/// All methods fire on the main loop thread
class SendspinClientListener {
public:
    virtual ~SendspinClientListener() = default;

    /// @brief Called when the group state is updated by the server
    virtual void on_group_update(const GroupUpdateObject& /*group*/) {}

    /// @brief Called after a time sync burst completes with the Kalman filter error
    virtual void on_time_sync_updated(float /*error*/) {}

    /// @brief Called when the library needs high-performance networking (e.g., disable WiFi
    /// power saving)
    ///
    /// Toggle the platform's networking mode and return; the body must not call any
    /// SendspinClient or role method. A time burst sends its first time message only once this
    /// call has returned, so the round trips it measures run in the mode it selects. Every
    /// request is followed by exactly one on_release_high_performance(); requests do not nest.
    virtual void on_request_high_performance() {}

    /// @brief Called when the library no longer needs high-performance networking
    ///
    /// Same contract as on_request_high_performance(). Also fires from ~SendspinClient() for a
    /// hold still outstanding.
    virtual void on_release_high_performance() {}

    // ========================================
    // Encryption / pairing callbacks
    // ========================================

    /// @brief Called when a server begins a pairing exchange
    ///
    /// server_id is the base64url public key of the server entering pairing. Fires once per
    /// attempt whatever the method; the exchange ends at on_pairing_succeeded or
    /// on_pairing_failed, or at neither when the server cancels it with a server/activate
    /// (pairing.md "Entering and leaving pairing").
    virtual void on_pairing_started(const std::string& /*server_id*/) {}

    /// @brief Called when a pairing exchange completes and a long-term record is stored
    ///
    /// server_id is the base64url public key of the newly paired server. After this
    /// callback the server re-handshakes on the new long-term PSK. Subsequent connections from
    /// this server will report ConnectionTrust::USER.
    virtual void on_pairing_succeeded(const std::string& /*server_id*/) {}

    /// @brief Called when a pairing exchange is aborted (by the server or by the protocol)
    ///
    /// The connection usually stays open after this callback, so the server can re-activate
    /// pairing or resume normal operation on it (pairing.md "pair/abort"). It is closed for
    /// CONCURRENT_ATTEMPT, and closed without any further message for the UNKNOWN reported on a
    /// pairing protocol error (a malformed or out-of-sequence pairing message).
    virtual void on_pairing_failed(const std::string& /*server_id*/,
                                   SendspinPairAbortReason /*reason*/) {}

    /// @brief Called when the active connection's trust level is known after handshake
    ///
    /// Fires on the initial handshake and after each successful re-handshake (for example, after
    /// pairing). trust reflects the PSK category matched during the Noise handshake:
    ///   ConnectionTrust::USER:  long-term record (paired server)
    ///   ConnectionTrust::NONE:  Sentinel or Pairing PSK (unpaired access)
    /// The last reported trust describes the connection only while is_connected() is true: a
    /// disconnect fires no on_trust_changed.
    virtual void on_trust_changed(ConnectionTrust /*trust*/) {}

    /// @brief Called when a dynamic pairing code should be emitted to the operator.
    ///
    /// `code` carries the code in the format the server selected, and `format` names it:
    ///   DIGITS:  the six contiguous decimal digits (e.g. "042735"). pairing.md "Pairing Code
    ///            Presentation" asks for a `3-3` grouping when the code is shown or spoken; the
    ///            separator is the application's to add.
    ///   QR_CODE: the version-1 pairing token (e.g. "SP:14DQ..."), to be rendered verbatim as a
    ///            QR code with no URI scheme or wrapper around it.
    /// Fires at most once per pairing attempt: the code is unchanged across the attempt's rounds,
    /// and is always followed by on_clear_pairing_code.
    /// Only called when SendspinClientConfig::pairing_code_out_channels and
    /// ::pairing_code_formats are both non-empty.
    virtual void on_display_pairing_code(const std::string& /*code*/,
                                         SendspinPairingCodeFormat /*format*/) {}

    /// @brief Called to withdraw the emitted dynamic pairing code.
    ///
    /// Fires after every pairing attempt that triggered on_display_pairing_code, whatever its
    /// outcome.
    virtual void on_clear_pairing_code() {}

    /// @brief Called when the operator must perform the device pairing-window gesture to allow a
    /// gesture-gated pairing attempt (pairing.md "Pairing Window"): every static_pairing_code
    /// attempt, and a dynamic_pairing_code attempt held back by the round limit.
    ///
    /// Only called when SendspinClientConfig::pairing_window_supported is true; a device
    /// offering dynamic_pairing_code should therefore also implement this
    /// gesture UI, or such an attempt stalls until the server cancels it.
    /// Always followed by on_close_pairing_window when the attempt concludes. The application
    /// confirms the gesture by calling SendspinClient::confirm_pairing_window().
    virtual void on_open_pairing_window() {}

    /// @brief Called to dismiss the pairing-window prompt after every attempt that triggered
    /// on_open_pairing_window, regardless of outcome.
    virtual void on_close_pairing_window() {}
};

/// @brief Platform hook for network readiness
/// Must be set before start()
class SendspinNetworkProvider {
public:
    virtual ~SendspinNetworkProvider() = default;

    /// @brief Returns true if the network (WiFi/Ethernet) is ready for connections
    ///
    /// Called from any thread: from start() on the main loop, then from the library's protocol
    /// task, which polls it while the WebSocket server is down. Must be cheap and non-blocking, a
    /// read of state the platform keeps current, and must not call back into the library.
    virtual bool is_network_ready() = 0;
};

/// @brief Optional persistence provider for saving/loading client state as opaque byte blobs.
///
/// The platform (e.g., ESPHome) provides a concrete implementation that stores blobs keyed by
/// the fixed key constants in `persistence_keys` (sendspin/persistence_keys.h), backed by
/// NVS/Preferences (ESP) or a file (host). The library owns all serialization; see
/// `persistence_keys` for each key's layout and fixed size. A provider is a pure byte store and
/// must not parse the blobs.
///
/// Every method has a default no-op / nullopt implementation so a platform can opt in
/// incrementally.
///
/// Threading: every method is invoked on the main loop thread, for every key, so a provider needs
/// no locking of its own. Provisioning writes from inside `start()` rather than in response to a
/// runtime event: `KEYPAIR` when none is stored, and `PAIRING_PSK` when none is stored and
/// `SendspinClientConfig::pairing_psk` is unset.
///
/// Re-entrancy: implementations must not call back into the library from inside
/// load_blob/save_blob/commit. Every call is made from the middle of a library step that is
/// part-way through updating the state the call is about. No internal lock is held across the
/// call.
///
/// Blocking: perform one bounded storage operation and return. The main loop is stopped for the
/// duration, so the listener and role callbacks wait with it; connection work, time sync and
/// audio run on the library's own threads and do not. Report a failed write by returning false
/// rather than retrying inline.
class SendspinPersistenceProvider {
public:
    virtual ~SendspinPersistenceProvider() = default;

    /// @brief Load the blob stored under key.
    /// @return The bytes, or nullopt if absent.
    virtual std::optional<std::vector<uint8_t>> load_blob(const std::string& /*key*/) {
        return std::nullopt;
    }

    /// @brief Persist bytes under key. Returning true means the write was accepted: stored, or
    /// queued until the next commit(). Every blob the library writes has the fixed size its key's
    /// `persistence_keys` comment gives, so a provider may store each key as a fixed-size value.
    ///
    /// A rejected write is reported, not retried: the in-memory state stays authoritative for
    /// this boot and the library logs what will be lost at the next reboot. What a rejection
    /// costs decides the level: a write that changes which records the next boot holds warns;
    /// one the next boot rebuilds by itself (the recency order in `RECORD_ORDER`) reports at
    /// debug. The case that matters is a rejected write of the zeroed
    /// blob that clears a revoked record's slot: the store still holds the old record and hands
    /// it back at the next boot, silently making the revoked PSK valid again (the record is dropped
    /// from RAM either way).
    /// @return true on success, false on failure.
    virtual bool save_blob(const std::string& /*key*/, const uint8_t* /*data*/, size_t /*len*/) {
        return false;
    }

    /// @brief Make every write accepted so far durable. A provider that writes through in
    /// save_blob() keeps the default. Called once after a batch of accepted writes that holds
    /// pairing material (`KEYPAIR`, `PAIRING_PSK`, or a record slot) and never for the other
    /// keys alone, so a queueing provider may batch those on its own schedule. A false return
    /// is logged like a rejected save_blob(), not retried.
    /// @return true when the accepted writes are durable.
    virtual bool commit() {
        return true;
    }
};

/// @brief Log severity levels for host builds
/// Has no effect on ESP-IDF builds
enum class LogLevel : uint8_t {
    NONE = 0,
    ERROR = 1,
    WARN = 2,
    INFO = 3,
    DEBUG = 4,
    VERBOSE = 5,
};

// Forward declarations
class ConnectionManager;
class InboundRing;
struct InboundMessage;
struct LifecycleRequests;
struct PairingUiSnapshot;
struct ProtocolCommand;
class ProtocolTask;
class RecordStore;
class SendspinArenaAllocator;
class SendspinConnection;
struct ClientStateMessage;
struct Identity;

/**
 * @brief Main orchestration class for the sendspin-cpp library
 *
 * Manages WebSocket connections, message routing, NTP-style time synchronization,
 * audio playback, and all Sendspin protocol interactions. Roles are added at runtime
 * and each receives events via a listener interface. Only roles that are added will
 * participate in the protocol.
 *
 * @code
 * struct MyPlayerListener : PlayerRoleListener {
 *     size_t on_audio_write(uint8_t* data, size_t len, uint32_t timeout_ms) override {
 *         return audio_output.write(data, len, timeout_ms);
 *     }
 * };
 *
 * struct MyNetworkProvider : SendspinNetworkProvider {
 *     bool is_network_ready() override { return true; }
 * };
 *
 * MyPlayerListener player_listener;
 * MyNetworkProvider network_provider;
 *
 * SendspinClientConfig config;
 * config.name = "My Device";
 * config.product_name = "Speaker";
 * config.manufacturer = "Acme";
 * config.software_version = "1.0.0";
 * SendspinClient client(config);
 * PlayerRoleConfig player_config;
 * player_config.audio_formats = {{SendspinCodecFormat::FLAC, 2, 44100, 16}};
 * auto& player = client.add_player(player_config);
 * player.set_listener(&player_listener);
 * client.add_controller();
 * client.set_network_provider(&network_provider);
 * client.start();
 *
 * while (running) {
 *     client.loop();
 * }
 * client.stop();
 * @endcode
 */
class SendspinClient {
    friend class ConnectionManager;
#ifdef SENDSPIN_ENABLE_CONTROLLER
    friend class ControllerRole;
#endif

public:
    explicit SendspinClient(SendspinClientConfig config);
    ~SendspinClient();

    /// @brief Sets the library-wide log level (host builds only, no-op on ESP-IDF)
    static void set_log_level(LogLevel level);

    /// @brief Returns the current log level (host builds only, INFO on ESP-IDF)
    static LogLevel get_log_level();

    // ========================================
    // Lifecycle
    // ========================================

    /// @brief Starts the role threads and the protocol task, and arms the WebSocket server
    ///
    /// The server starts listening before this returns when the network provider already reports
    /// ready, and otherwise as soon as it does, on the protocol task. If a role fails to start,
    /// the roles that did start are stopped again so a corrected retry begins from the stopped
    /// state. A rejected configured pairing secret (SendspinClientConfig::pairing_psk or
    /// static_pairing_code) fails every start() of this instance, since the config is fixed at
    /// construction. Main-loop thread only.
    /// @return true if the client is running (including when it already was), false on failure
    bool start();

    /// @brief Stops the client and returns only once it is fully stopped
    ///
    /// Sends a client/goodbye (reason shutdown) to every peer and closes each connection behind
    /// it, joins the protocol task, then closes the server and every connection still open,
    /// joins the role threads, resets every role, and delivers the roles' clear callbacks
    /// (on_stream_end(), on_image_clear(), on_metadata_clear(), ...) before returning. A pairing
    /// prompt still showing is dismissed the same way (on_clear_pairing_code() /
    /// on_close_pairing_window()), and every provider write still owed is performed before
    /// returning. No-op when stopped. Calling start() afterwards restarts on the same identity and
    /// record store, unless the persistence provider changed in between. Start/stop cycles may be
    /// repeated indefinitely.
    ///
    /// Blocking is bounded by the transports' own send and close, and any listener callback
    /// already running on a role thread, which the join cannot interrupt. The per-transport
    /// bounds are described in docs/integration-guide.md (Stopping and Restarting).
    ///
    /// Listener callbacks fire from inside this call, after every role has been reset, so the
    /// state they observe through the getters is the stopped state. One that calls start() has
    /// no effect and returns false; one that calls stop(), connect_to(), or disconnect() is
    /// ignored. Main-loop thread only: calling it from a role-thread callback would join the
    /// calling thread.
    void stop();

    /// @brief Returns true between a successful start() and stop()
    ///
    /// Running means the role threads are up and the server is armed, not that the server is
    /// listening yet (that waits for the network provider). Reads false for the whole duration
    /// of stop(), including from the clear callbacks it fires. Safe to call from any thread.
    bool is_started() const {
        return this->lifecycle_.load(std::memory_order_acquire) == LifecycleState::RUNNING;
    }

    /// @brief Initiates a client connection to a Sendspin server at the given URL
    ///
    /// Ignored (with a warning) unless the client is running, including from a callback fired
    /// inside stop(). start() is where the identity and record store the Noise handshake needs
    /// are created. Any thread: the request is posted to the library's protocol task, which
    /// replaces any earlier outbound attempt. It is never refused, and a second call before the
    /// task takes it replaces the URL. With disconnect(), the pair resolves by call order: a
    /// connect_to() after a disconnect() opens the new attempt once the goodbyes are sent, and a
    /// disconnect() after a connect_to() the task has not taken cancels it, so nothing opens. The
    /// task applies both ahead of the sends queued since its last tick. Replacing an attempt that
    /// is still connecting does not wait for its transport: the attempt is closed without
    /// blocking and freed once its transport has finished, at the latest once its connect bound
    /// has passed (30 s on host; on ESP-IDF three connect steps of 10 s each, plus whatever a DNS
    /// lookup, which has no bound of its own, takes).
    /// @param url WebSocket server URL (e.g., "ws://server.local:8927/sendspin")
    void connect_to(const std::string& url);

    /// @brief Disconnects from the current server with the given reason
    ///
    /// Ignored unless the client is running, including from a callback fired inside stop(). Any
    /// thread: the request is posted to the protocol task, which sends the goodbyes. It is never
    /// refused, and a second call before the task takes it replaces the reason rather than
    /// adding a second disconnect. With connect_to() it resolves by call order (see
    /// connect_to()). The task applies it ahead of the commands queued since its last tick: a
    /// controller command queued before it in that window is dropped rather than
    /// sent after the goodbye, while a connection a server delivered before it still enters the
    /// nursery, since a disconnect addresses the current connections, not a newcomer. An outbound
    /// attempt still connecting is released the same way connect_to() releases one it replaces,
    /// without waiting for its transport.
    /// @param reason The goodbye reason to send
    void disconnect(SendspinGoodbyeReason reason);

    /// @brief Delivers what the library's threads produced since the last call: the listener and
    /// role callbacks, the persistence provider's writes, and the high-performance requests. Call
    /// from the main loop. A no-op while the client is stopped.
    ///
    /// Connection work (handshakes, time sync, sends, watchdogs) runs on the library's protocol
    /// task and does not wait for this call.
    void loop();

    // ========================================
    // Role registration (call before start())
    // ========================================

#ifdef SENDSPIN_ENABLE_PLAYER
    /// @brief Adds the player role. Returns a reference for setting callbacks
    PlayerRole& add_player(PlayerRoleConfig config);
#endif

#ifdef SENDSPIN_ENABLE_COLOR
    /// @brief Adds the color role. Returns a reference for setting callbacks
    ColorRole& add_color();
#endif

#ifdef SENDSPIN_ENABLE_CONTROLLER
    /// @brief Adds the controller role. Returns a reference for setting callbacks
    ControllerRole& add_controller();
#endif

#ifdef SENDSPIN_ENABLE_METADATA
    /// @brief Adds the metadata role. Returns a reference for setting callbacks
    MetadataRole& add_metadata();
#endif

#ifdef SENDSPIN_ENABLE_ARTWORK
    /// @brief Adds the artwork role. Returns a reference for setting callbacks
    ArtworkRole& add_artwork(ArtworkRoleConfig config);
#endif

#ifdef SENDSPIN_ENABLE_VISUALIZER
    /// @brief Adds the visualizer role. Returns a reference for setting callbacks
    VisualizerRole& add_visualizer(VisualizerRoleConfig config);
#endif

#ifdef SENDSPIN_ENABLE_SOURCE
    /// @brief Adds the source role. Returns a reference for setting callbacks
    SourceRole& add_source(SourceRoleConfig config);
#endif

    // ========================================
    // Role access (nullptr if not added)
    // ========================================

#ifdef SENDSPIN_ENABLE_ARTWORK
    /// @brief Returns the artwork role, or nullptr if not added
    // cppcheck-suppress unusedFunction
    // Public API: live entry point the reference examples don't happen to exercise, not dead code.
    ArtworkRole* artwork() {
        return this->artwork_.get();
    }
    /// @brief Returns the artwork role (const), or nullptr if not added
    const ArtworkRole* artwork() const {
        return this->artwork_.get();
    }
#endif
#ifdef SENDSPIN_ENABLE_COLOR
    /// @brief Returns the color role, or nullptr if not added
    ColorRole* color() {
        return this->color_.get();
    }
    /// @brief Returns the color role (const), or nullptr if not added
    const ColorRole* color() const {
        return this->color_.get();
    }
#endif
#ifdef SENDSPIN_ENABLE_CONTROLLER
    /// @brief Returns the controller role, or nullptr if not added
    ControllerRole* controller() {
        return this->controller_.get();
    }
    /// @brief Returns the controller role (const), or nullptr if not added
    const ControllerRole* controller() const {
        return this->controller_.get();
    }
#endif
#ifdef SENDSPIN_ENABLE_METADATA
    /// @brief Returns the metadata role, or nullptr if not added
    MetadataRole* metadata() {
        return this->metadata_.get();
    }
    /// @brief Returns the metadata role (const), or nullptr if not added
    const MetadataRole* metadata() const {
        return this->metadata_.get();
    }
#endif
#ifdef SENDSPIN_ENABLE_PLAYER
    /// @brief Returns the player role, or nullptr if not added
    PlayerRole* player() {
        return this->player_.get();
    }
    /// @brief Returns the player role (const), or nullptr if not added
    const PlayerRole* player() const {
        return this->player_.get();
    }
#endif
#ifdef SENDSPIN_ENABLE_SOURCE
    /// @brief Returns the source role, or nullptr if not added
    SourceRole* source() {
        return this->source_.get();
    }
    /// @brief Returns the source role (const), or nullptr if not added
    const SourceRole* source() const {
        return this->source_.get();
    }
#endif
#ifdef SENDSPIN_ENABLE_VISUALIZER
    /// @brief Returns the visualizer role, or nullptr if not added
    // cppcheck-suppress unusedFunction
    // Public API, not dead code.
    VisualizerRole* visualizer() {
        return this->visualizer_.get();
    }
    /// @brief Returns the visualizer role (const), or nullptr if not added
    const VisualizerRole* visualizer() const {
        return this->visualizer_.get();
    }
#endif

    // ========================================
    // Queries
    // ========================================

    /// @brief Returns the client's cryptographic identity string.
    /// This is base64url(X25519 public key), 43 chars: the Sendspin client_id.
    /// Generated on first boot and persisted via the persistence provider.
    /// Empty until start() is called.
    /// Main loop only. start() rewrites the value, so do not cache a c_str() across a restart.
    [[nodiscard]] const std::string& client_id() const {
        return this->client_id_;
    }

    /// @brief Builds the pairing token (pairing.md "Pairing Token") for a Sendspin Pairing
    /// PSK: the single "SP:"-prefixed, base32 string that carries this client's static public key
    /// alongside `pairing_psk`, for an operator to transfer into a server via copy/paste or QR
    /// code to begin the Pairing PSK flow. Clients offering `pairing_psk` SHOULD surface this
    /// token rather than the bare PSK.
    /// Main loop only.
    /// @param pairing_psk The 32-byte Sendspin Pairing PSK.
    /// @return The 107-character token string, or nullopt if no identity has been initialized
    ///         yet (before start() is called).
    [[nodiscard]] std::optional<std::string> format_pairing_token(
        const std::array<uint8_t, 32>& pairing_psk) const;

    /// @brief Builds the pairing token for the client's own Sendspin Pairing PSK.
    /// The Pairing PSK is SendspinClientConfig::pairing_psk when set, otherwise the stored one,
    /// generated and persisted on first boot when none is stored, so this token is stable for
    /// the lifetime of that key: display it (or its QR code) for the operator to transfer into a
    /// server that is setting this client up.
    /// Main loop only.
    /// @return The 107-character token string, or nullopt before start().
    [[nodiscard]] std::optional<std::string> pairing_token() const;

    /// @brief Returns true if there is an active connection whose handshake completed and whose
    /// latest server/activate has arrived. Any thread; reflects the protocol task's last tick.
    bool is_connected() const;

    /// @brief Returns the server information from the active connection's hello handshake, or
    /// nullopt when no connection is active. Any thread.
    std::optional<ServerInformationObject> get_server_information() const;

    /// @brief Returns true if the time filter has received at least one measurement. Any thread.
    bool is_time_synced() const;

    /// @brief Converts a server timestamp to the equivalent client timestamp. Any thread.
    /// @param server_time Server-side timestamp in microseconds
    /// @return Equivalent client-side timestamp in microseconds, or 0 with no active connection
    int64_t get_client_time(int64_t server_time) const;

    /// @brief Returns the current group state; fields are optional and may be unset
    const GroupUpdateObject& get_group_state() const {
        return this->group_state_;
    }

    // ========================================
    // State updates
    // ========================================

    /// @brief Sets whether the client is available for Sendspin playback; publishes on a change
    ///
    /// messaging.md "External Source Handling": false only while the device will not yield to
    /// Sendspin, which moves it to a stopped group of its own. An activity Sendspin may interrupt
    /// calls leave() instead. The player discards incoming audio from the client/state the next
    /// protocol tick sends, and the source ends its input stream ahead of that client/state and
    /// ignores starts while unavailable. Kept across disconnects and stop()/start(). Main loop
    /// only.
    /// @param available false while the device will not yield to Sendspin.
    void set_available(bool available);

    /// @brief Whether the client reports itself available; see set_available(). Main loop only.
    bool is_available() const {
        return this->available_;
    }

    /// @brief Leaves the current group with messaging.md "client/leave". Any thread: the request
    /// is posted to the protocol task, never refused, and calls before the task takes it send one
    /// client/leave.
    ///
    /// The client no longer wants to take part in its group's playback, for example while
    /// playing a local source. The
    /// server treats it as it treats a client becoming unavailable: this client ends up alone in
    /// a stopped group, and rejoins only when an operator switches it back. Leaving does not
    /// change availability, so the server may still take the client over for new playback; a
    /// client that will not yield calls set_available(false) instead.
    ///
    /// Only meaningful while the group is playing: a client in a stopped group keeps its
    /// grouping by staying. Ignored, with a log, unless the client is running, a connection is
    /// admitted and its latest server/activate has arrived.
    void leave();

    // ========================================
    // Pairing
    // ========================================

    /// @brief Signals that the operator performed the device pairing-window gesture.
    /// Any thread: posted to the protocol task, never refused; ignored unless the client is
    /// running. With cancel_pairing_window(), whichever of the two is called last before the
    /// task takes them is the one applied: a confirm then a cancel between two ticks does
    /// nothing, not even the round-limit reset, and a cancel then a confirm starts a waiting
    /// attempt. Opens a pairing window (pairing.md "Pairing Window"): a gesture-gated attempt
    /// already waiting proceeds immediately; otherwise the window stands open for 5 minutes and
    /// admits pairing attempts on one connection without a further gesture. It closes before
    /// those 5 minutes are up when a pairing under it succeeds, when the connection it is bound
    /// to is lost, after five attempts fail verification, or on cancel_pairing_window(). The
    /// gesture also clears a standing dynamic-pairing-code round limit.
    void confirm_pairing_window();

    /// @brief Signals that the operator cancelled the pairing window.
    /// Any thread: posted to the protocol task like confirm_pairing_window(), and the later of
    /// the two called between two ticks is the only one applied (see confirm_pairing_window());
    /// ignored unless the client is running. Closes any open window, one of the closing events
    /// pairing.md "Pairing Window" defines, so the next gesture-gated attempt waits for a fresh
    /// gesture. An attempt still withheld for that gesture ends with pair/abort reason
    /// user_cancelled; one already under way runs to its own end.
    void cancel_pairing_window();

    /// @brief Turns unpaired access on or off; it is off until this is called. Any thread: the
    /// value takes effect at once for the next client/hello and admission, and the connections it
    /// no longer fits are closed by the protocol task. Calls between two of its ticks collapse to
    /// the latest: the task applies the value the last one set, once.
    ///
    /// pairing.md "Unpaired Access": servers with no pairing record may declare playback and
    /// activate roles only while it is on. Turning it off closes every connection that relies on
    /// it with client/goodbye reason pairing_required. Turning it on closes each unpaired
    /// connection a server opened that is not declaring pairing with reason restart, so the server
    /// reconnects and sees the new value in the client/hello; a connect_to() connection is kept.
    /// So is one on the Pairing PSK still awaiting its first server/activate: it is most likely
    /// about to declare pairing, and a restart would cost that attempt. If it activates idle
    /// instead, it keeps the hello it already read until it reconnects.
    /// Callable at any time, before the first start() and while stopped included: while the
    /// client is stopped the value is stored, and the next start() uses it from its first
    /// client/hello on (there is no connection to close).
    ///
    /// The library never persists the setting. An application that keeps it across reboots
    /// stores it and restores it by calling this before start(), so the first client/hello and
    /// admission already use it; a later call works too, but restarts the unpaired connections a
    /// server opened that saw the old value.
    /// @param enabled Whether to admit unpaired access.
    void set_unpaired_access_enabled(bool enabled);

    /// @brief Whether unpaired access is on; see set_unpaired_access_enabled(). Any thread.
    bool is_unpaired_access_enabled() const;

    // ========================================
    // Listener and provider setters
    // ========================================

    /// @brief Sets the listener for client events. The listener must outlive this client
    void set_listener(SendspinClientListener* listener) {
        this->listener_ = listener;
    }

    /// @brief Sets the network provider (required before start())
    /// The provider must outlive this client
    void set_network_provider(SendspinNetworkProvider* provider) {
        this->network_provider_ = provider;
    }

    /// @brief Sets the optional persistence provider. The provider must outlive this client
    void set_persistence_provider(SendspinPersistenceProvider* provider) {
        this->persistence_provider_ = provider;
    }

    // ========================================
    // Role services (called by roles via SendspinClient pointer)
    // ========================================

    /// @brief Publishes the current client state to the active connections
    ///
    /// Main loop only, like the role setters that call it: the availability and the role fields
    /// it serializes are main-loop state. It builds a snapshot here and hands it to the protocol
    /// task, which keeps only the newest and sends each admitted connection the role objects of
    /// the roles that connection owns. Ignored unless the client is running.
    void publish_state();

    /// @brief Acquires a ref-counted high-performance networking request. Main loop only: the
    /// first acquire calls the listener inline.
    void acquire_high_performance();

    /// @brief Releases a ref-counted high-performance networking request. Main loop only: the
    /// last release calls the listener inline.
    void release_high_performance();

private:
#ifdef SENDSPIN_ENABLE_CONTROLLER
    /// @brief Queues a controller command for the protocol task, which formats it as a
    /// client/command and sends it to the admitted connection that owns the controller role
    ///
    /// The protocol task drops the command unless that connection has the role active:
    /// messaging.md "server/activate" tolerates inactive-role objects server-side because a client
    /// that received the role removal stops sending them. The activation test is on the
    /// versioned name, the same test the receive path applies. Also dropped, like client/state,
    /// while a re-handshake awaits the server/activate that follows it.
    ///
    /// The controller role's route to the protocol task, private to it (ControllerRole is a
    /// friend): ControllerRole::send_command() checks the command against the server's
    /// supported_commands and its parameter before calling this, which assumes those checks ran
    /// and repeats neither. The command crosses to the protocol task as the struct, so the
    /// message is built in the task's JSON arena. It carries the generation it was validated
    /// under, and the protocol task drops it if the role has been torn down since (its owner
    /// replaced, or the role removed): the supported commands it was validated against are
    /// retired.
    ///
    /// Callable from any thread.
    /// @param cmd The command, already validated by the controller role.
    /// @param generation The low 16 bits of the controller role's teardown generation that
    ///        stamped the supported_commands the command was validated against.
    /// @return false when the command was refused before it reached the protocol task: the
    ///         client is not running, or the command queue is full
    ///         (ProtocolTask::CONSUMER_COMMAND_BURST requests, logged). true means queued, not
    ///         sent.
    bool send_controller_command(const ClientCommandControllerObject& cmd, uint16_t generation);
#endif

    /// @brief The protocol-task half of a teardown: when `teardown_roles` covers every role, wipes
    /// the event ring, the pending group and time-sync slots, and the pairing notes other than a
    /// dismissal already owed; and runs cleanup() on each role in
    /// `teardown_roles`, each of which queues its stamped clear for the main loop. Protocol task,
    /// or the main loop in stop() once every other thread is joined. Each role's main-loop half
    /// runs in drain_inbox() before anything stamped with the new generation is acted on
    /// (catch_up_teardown() in src/teardown_tracker.h).
    /// @param teardown_roles The roles to tear down, as role_mask_bit() bits: the roles no
    ///        remaining admitted connection owns.
    void cleanup_connection_state(uint16_t teardown_roles);

    /// @brief Wakes the main loop to run flush_pending_persistence(). Callable from any thread
    /// and under any library lock: it takes only the Inbox mutex.
    void request_persist();

    /// @brief Performs the provider writes owed since the last call: the record store's dirty
    /// keys and a changed last-played server. Main loop only, with no library lock held: on ESP
    /// each write is a flash write.
    ///
    /// The store may be null here: the destructor calls this on a client whose start() never
    /// succeeded.
    void flush_pending_persistence();

    /// @brief Drains the inbox: high-performance requests, the time-sync report, lifecycle events
    /// (each role's teardown half caught up ahead of its stamped events), provider writes,
    /// pairing notes, role slots, and group updates, dispatching listener callbacks on the
    /// calling (main-loop) thread. Shared by loop() and stop(). A callback that calls stop()
    /// abandons the rest of this drain (EventState::drain_generation).
    void drain_inbox();

    /// @brief Applies the high-performance edges the protocol task queued: every acquire, then
    /// the grant for them (high_performance_granted_, waking the protocol task), then every
    /// release, so a release never lands before the acquire it ends. Main loop only.
    void apply_high_performance_requests();

    /// @brief Queues a high-performance acquire (true) or release (false) for the main loop,
    /// where the listener is called. Protocol task.
    /// @return For an acquire, its ticket: the burst that requested the hold sends its first
    ///         time frame only once high_performance_granted() reports the ticket granted. 0 for
    ///         a release, which waits for nothing.
    uint32_t request_high_performance(bool acquire);

    /// @brief Whether the main loop has called the listener for the acquire `ticket` names (see
    /// request_high_performance()). Protocol task.
    bool high_performance_granted(uint32_t ticket) const;

    /// @brief Queues a completed time burst's filter error for on_time_sync_updated() on the main
    /// loop. Protocol task.
    void post_time_sync_error(double error);

    /// @brief Signals the drain roles, closes admission, joins the protocol task (whose final
    /// tick goodbyes every peer within a bound), then closes every transport and stops the
    /// server, joining the transport threads. The shared first half of stop() and the
    /// destructor's teardown.
    /// @return The pairing prompts the dropped connections left showing; stop() dismisses them
    ///         after cleanup_connection_state(), the destructor dispatches nothing.
    PairingUiSnapshot close_transports();

    /// @brief Asks the artwork and visualizer threads to exit without joining them, so their
    /// exit overlaps the transport teardown. The player is excluded: its sync task returns the
    /// ring items it holds as it plays, which a transport waiting for ring space may need until
    /// the transports are gone (see stop()).
    void signal_drain_role_stops();

    /// @brief Stops and joins every threaded role; each is a no-op if not running. Each role
    /// returns the inbound ring items it holds after its join.
    void stop_role_threads();

    /// @brief Creates the shared inbound ring for this run, sized by derive_inbound_ring_bytes()
    /// from the enabled roles and placed per SendspinClientConfig::inbound_ring_location, and
    /// sets the per-role quotas. Main loop only, from start().
    /// @return false when the storage cannot be allocated.
    bool create_inbound_ring();

    /// @brief Returns every item left in the inbound ring and releases its storage. Main loop
    /// only, once every transport, the protocol task and every consumer are joined.
    void release_inbound_ring();

    // ========================================
    // Protocol task
    // ========================================

    /// @brief The protocol task's work, in order: the lifecycle requests and the command queue,
    /// the shutdown pass once admission is closed, the client/state snapshot, the outbound
    /// handshakes, each managed connection's pending pre-admission message, the inbound ring, the
    /// losses, the lifecycle scans, the time bursts, and the published slots (docs/internals.md
    /// "The Protocol Task's Tick"). Protocol task only.
    /// @return Milliseconds until the earliest of the task's timers, 0 to run again at once when
    ///         the ring was not drained within one pass, or ProtocolTask::NO_DEADLINE.
    uint32_t protocol_tick();

    /// @brief Acts on the lifecycle requests taken from the protocol task's request slot.
    /// Protocol task only.
    void apply_lifecycle_requests(const LifecycleRequests& requests);

    /// @brief Acts on one command from the queue. Protocol task only.
    void handle_command(ProtocolCommand& command);

    /// @brief Runs one of a connection's messages through the receive path and dispatch, and
    /// returns its ring item unless a role kept it. Resets json_arena_ first (the only reset), so
    /// it is called only from the tick's top level, with no arena document live. Protocol task
    /// only.
    void process_inbound(SendspinConnection& conn, InboundMessage& message);

    /// @brief Builds the formatted client hello message from config. Protocol task.
    std::string build_hello_message();

    /// @brief Builds the client/state snapshot publish_state() hands the protocol task: the
    /// availability and every role's state object. Main loop only, where that state lives.
    ClientStateMessage build_client_state() const;

    // ========================================
    // Message processing
    // ========================================

    /// @brief Parses and routes one JSON message from a connection, in json_arena_ (which
    /// process_inbound() reset). Protocol task only. `data` is not null-terminated and is valid for
    /// the duration of the call only.
    void process_json_message(SendspinConnection& connection, const char* data, size_t len,
                              int64_t timestamp);

    /// @brief Hands a pairing message that failed to parse to the pairing state machine.
    ///
    /// Shared by the pair-init, pair-auth and pair-confirm arms of process_json_message(), which
    /// differ only in the payload they parse.
    void report_malformed_pairing_message(SendspinConnection* conn, const char* type_name);

    /// @brief Processes a binary message from a connection. Protocol task only.
    /// Every binary message is role-bound, so this is dropped unless `connection` is admitted and
    /// owns the role. A player audio chunk or a visualizer frame is handed to its consumer by its
    /// ring item when it has one (the role clears `message.item`).
    void process_binary_message(SendspinConnection& connection, InboundMessage& message);

    // ========================================
    // State publishing
    // ========================================

    /// @brief Sends the latest client/state snapshot to an admitted connection, with the role
    /// objects of the roles it owns. Protocol task only.
    ///
    /// Nothing for a connection that is not admitted and operational. Held while the snapshot is
    /// available and `conn` owns an active player or source with no clock sync yet
    /// (AdmittedEntry::state_held); ConnectionManager::run_time_sync() sends it with the first
    /// measurement.
    void publish_client_state(SendspinConnection* conn);

    /// @brief Makes `snapshot` the client/state every admitted connection's is built from, and
    /// sends each admitted connection its copy (publish_client_state()). An unavailable snapshot
    /// first ends the source's input stream. Protocol task only.
    void adopt_client_state(ClientStateMessage&& snapshot);

    /// @brief Whether the adopted client/state snapshot reports the client available; false before
    /// the first is adopted (adopt_client_state()). Protocol task only.
    bool adopted_state_available() const;

    /// @brief Folds a group/update delta from the primary admitted connection into group_slot
    /// for the main loop. Protocol task only.
    void merge_group_update(GroupUpdateObject&& delta);

    // ========================================
    // Persistence & identity
    // ========================================

    /// @brief Loads or generates the static X25519 identity keypair via the persistence
    /// provider. Sets identity_ on success. Called once from start(), before the
    /// connection manager can hand the identity out to any connection.
    /// @return false only if key generation failed (e.g. noise-c allocation failure); a corrupt
    /// or wrong-length stored key is discarded and a fresh identity generated in its place (the
    /// device must then re-pair). identity_ is left null on false and the caller (start()) must
    /// not proceed.
    bool load_or_generate_identity();

    /// @brief Loads the last played server_id from persistence
    void load_last_played_server();

    /// @brief Makes server_id the last-played server in RAM and queues its provider write for
    /// flush_pending_persistence() on the main loop. Protocol task, where arbitration reads the
    /// value. An empty or unchanged server_id is a no-op: one flash write per handoff.
    void note_last_played_server(const std::string& server_id);

    /// @brief Writes the last-played server_id through the persistence provider.
    void write_last_played_server(const std::string& server_id);

    // ========================================
    // Connection event handlers (called by ConnectionManager via friend access)
    // ========================================

    /// @brief Publishes the initial client state once an admitted connection goes operational,
    /// clears its pairing state, dismissing any prompt the attempt left showing, and reports its
    /// trust level. Protocol task only.
    /// @param conn The connection that completed the handshake
    void on_handshake_complete(SendspinConnection* conn);

    /// @brief Tears down the roles an activation took away from their connection
    ///
    /// messaging.md "server/activate" has the client stop a removed stream role's output and clear
    /// its buffers, and discard a removed state role's current state and pending scheduled update,
    /// as part of applying the activation. Roles that stay active at the same version are
    /// untouched. Protocol task only, like the disconnect path's teardown, and like it it queues
    /// its listener callbacks for the main loop instead of calling them here.
    /// @param conn The connection the activation was applied on.
    /// @param removed_roles The roles the connection owned before the activation and no longer
    ///        does, as role_mask_bit() bits.
    void apply_role_removals(SendspinConnection* conn, uint16_t removed_roles);

    /// @brief A server/activate was applied on `conn`, ending any re-handshake quiet window
    /// (connection.md "Re-handshake"): sends what the roles owed until then, the source's
    /// client-stream/end. Called by ConnectionManager right after the activation is applied, ahead
    /// of its role removals and of anything else it sends. Protocol task only.
    void on_activation_applied(SendspinConnection* conn);

    // Pairing and trust notifications. Each is called on the protocol task and queues the
    // listener callback for delivery from the main loop's drain_inbox().

    /// @brief Queue an on_pairing_started notification
    void note_pairing_started(const std::string& server_id);

    /// @brief Queue an on_pairing_succeeded notification
    void note_pairing_succeeded(const std::string& server_id);

    /// @brief Queue an on_pairing_failed notification
    void note_pairing_failed(const std::string& server_id, SendspinPairAbortReason reason);

    /// @brief Queue an on_display_pairing_code notification
    void note_display_pairing_code(const std::string& code, SendspinPairingCodeFormat format);

    /// @brief Queue an on_clear_pairing_code notification
    void note_clear_pairing_code();

    /// @brief Queue an on_open_pairing_window notification
    void note_open_pairing_window();

    /// @brief Queue an on_close_pairing_window notification
    void note_close_pairing_window();

    /// @brief Queue on_clear_pairing_code and/or on_close_pairing_window for each prompt `ui`
    /// records as showing
    void note_pairing_ui_dismissals(const PairingUiSnapshot& ui);

    /// @brief Queue an on_trust_changed notification
    void note_trust_changed(ConnectionTrust trust);

    struct EventState;
    struct TaskState;

    // Struct fields
    SendspinClientConfig config_;
    GroupUpdateObject group_state_{};

    // String fields
    std::string client_id_;  ///< Derived from the static keypair: base64url(public_key).

    // Pointer fields
#ifdef SENDSPIN_ENABLE_ARTWORK
    std::unique_ptr<ArtworkRole> artwork_;
#endif
#ifdef SENDSPIN_ENABLE_COLOR
    std::unique_ptr<ColorRole> color_;
#endif
    std::unique_ptr<ConnectionManager> connection_manager_;
#ifdef SENDSPIN_ENABLE_CONTROLLER
    std::unique_ptr<ControllerRole> controller_;
#endif
    std::unique_ptr<EventState> event_state_;
    /// Static X25519 identity (generated on first boot, persisted via the persistence
    /// provider). Set by load_or_generate_identity() in start(); outlives every
    /// connection the manager hands it out to.
    std::unique_ptr<Identity> identity_;
    /// The shared inbound ring for the current run: created by start(), released by stop() once
    /// every producer and consumer is joined. Null while stopped. Reached by the transports and
    /// the protocol task through the pointers attach_inbound() and the role starts hand out.
    /// Written on the main loop only. The transport delivery threads and the protocol task also
    /// read this member (ConnectionManager::on_new_connection() through attach_inbound(), and the
    /// tick): safe because start() creates the ring before the manager opens admission and the
    /// protocol task starts, and stop() releases it only after the protocol task is joined and
    /// ConnectionManager::finish_stop() has stopped the server and joined its tasks.
    std::unique_ptr<InboundRing> inbound_ring_;
    /// Internal-RAM scratch arena (config_.json_arena_size bytes, all-heap at 0) backing every
    /// JSON document the protocol task parses or builds. Created with the client. Used by the
    /// protocol task only: process_inbound() resets it before each inbound message, and the
    /// connection manager, the connections and the handshake parse and build in it.
    std::unique_ptr<SendspinArenaAllocator> json_arena_;
    SendspinClientListener* listener_{nullptr};
#ifdef SENDSPIN_ENABLE_METADATA
    std::unique_ptr<MetadataRole> metadata_;
#endif
    /// The provider identity_ and record_store_ were built from; start() rebuilds both when
    /// persistence_provider_ no longer matches it.
    SendspinPersistenceProvider* identity_provider_{nullptr};
    SendspinNetworkProvider* network_provider_{nullptr};
    SendspinPersistenceProvider* persistence_provider_{nullptr};
#ifdef SENDSPIN_ENABLE_PLAYER
    std::unique_ptr<PlayerRole> player_;
#endif
    /// The protocol thread, its command queue, its state slot and its request slot. Created with
    /// the client and started/stopped by start()/stop(), so a transport may wake it at any time.
    std::unique_ptr<ProtocolTask> protocol_task_;
    /// In-memory pairing record store (PSK resolution). Set in start();
    /// outlives every connection the manager hands it out to.
    std::unique_ptr<RecordStore> record_store_;
    /// State the protocol task owns at client level (the latest client/state snapshot and the
    /// high-performance ticket count).
    std::unique_ptr<TaskState> task_state_;
#ifdef SENDSPIN_ENABLE_SOURCE
    std::unique_ptr<SourceRole> source_;
#endif
#ifdef SENDSPIN_ENABLE_VISUALIZER
    std::unique_ptr<VisualizerRole> visualizer_;
#endif

    // 32-bit fields
    /// High-performance acquires the main loop has applied, the listener called for each: the
    /// grant a time burst waits for before its first time frame (high_performance_granted()).
    /// Written by the main loop's drain (apply_high_performance_requests()); read by the protocol
    /// task.
    std::atomic<uint32_t> high_performance_granted_{0};

    // 8-bit fields
    /// Consumer-owned availability; see set_available(). Main loop only (the protocol task reads
    /// it from the client/state snapshot).
    bool available_{true};
    /// The unpaired-access setting admission and the client/hello read; see
    /// set_unpaired_access_enabled(). Written by the setter on any thread, read by the protocol
    /// task.
    std::atomic<bool> unpaired_access_enabled_{false};
    /// High-performance requests applied and not yet released. Main loop only.
    uint8_t high_performance_ref_count_{0};
    /// Where the client is in its lifecycle. Written only by start()/stop() on the main loop;
    /// atomic so is_started() can be read from any thread. STOPPING covers the whole of stop():
    /// start() is refused and stop()/connect_to()/disconnect() are ignored while it is set, so a
    /// listener callback fired from inside the teardown cannot recurse into it.
    enum class LifecycleState : uint8_t { STOPPED, RUNNING, STOPPING };
    std::atomic<LifecycleState> lifecycle_{LifecycleState::STOPPED};
};

}  // namespace sendspin
