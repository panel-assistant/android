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

/// @file connection_manager.h
/// @brief Manages WebSocket connection lifecycle including server handoff, hello handshake, and
/// graceful disconnection

#pragma once

#include "constants.h"
#include "inline_vector.h"
#include "protocol_messages.h"
#include "record_store.h"
#include "sendspin/client.h"
#include "sendspin/types.h"

#include <array>
#include <atomic>
#include <cstddef>
#include <cstdint>
#include <memory>
#include <mutex>
#include <optional>
#include <string>
#include <vector>

namespace sendspin {

// Forward declarations
class SendspinArenaAllocator;
class SendspinClient;
class SendspinConnection;
class SendspinServerConnection;
class SendspinTimeFilter;
class SendspinWsServer;

/// @brief Converts a duration in seconds to microseconds at compile time.
constexpr int64_t seconds_to_us(double s) {
    return static_cast<int64_t>(s * US_PER_SECOND);
}

/// @brief Deadline (seconds) for a nursery connection to become operational (hello handshake
/// complete and first server/activate applied; connection.md "Multiple servers
/// (server-initiated)"), measured from the connection's delivery or initiation
///
/// Reaps peers that connect and then stall before becoming operational, and outbound attempts that
/// stall without failing (a listener that never answers the upgrade). Sockets that never upgrade
/// are closed in the platform layer before the manager sees them (ESP ws_server tick() at
/// WS_UPGRADE_TIMEOUT_US; host IXWebSocket's 3 s handshake timeout).
static constexpr double NURSERY_ESTABLISH_TIMEOUT_S = 30.0;

/// @brief Timeout in microseconds (derived from NURSERY_ESTABLISH_TIMEOUT_S).
static constexpr int64_t NURSERY_ESTABLISH_TIMEOUT_US = seconds_to_us(NURSERY_ESTABLISH_TIMEOUT_S);

/// @brief Deadline (seconds) for an admitted connection to re-prove itself after
/// SendspinConnection::handle_noise_rehandshake() or ::note_pairing_finalize_ack() resets its
/// operational state. Enforced by ConnectionManager::scan_admitted().
///
/// Shares NURSERY_ESTABLISH_TIMEOUT_S's value and "reach the next milestone within a bounded
/// window" semantics, named separately because it applies to admitted connections, not the
/// nursery.
static constexpr double REPROVE_TIMEOUT_S = NURSERY_ESTABLISH_TIMEOUT_S;

/// @brief Timeout in microseconds (derived from REPROVE_TIMEOUT_S).
static constexpr int64_t REPROVE_TIMEOUT_US = seconds_to_us(REPROVE_TIMEOUT_S);
/// @brief Consecutive unanswered client/time messages the derived liveness timeout tolerates.
static constexpr int64_t LIVENESS_TOLERATED_MISSES = 2;

/// @brief Returns config.liveness_timeout_ms or, if unset, a timeout derived from the time burst
/// settings that outlasts LIVENESS_TOLERATED_MISSES consecutive unanswered time messages by at
/// least one response timeout; either is capped at SendspinClientConfig::MAX_LIVENESS_TIMEOUT_MS.
/// @param config The client configuration.
/// @return Timeout in milliseconds; 0 or negative disables the check.
int64_t resolve_liveness_timeout_ms(const SendspinClientConfig& config);

/// @brief Returns true if a connection last heard from at last_receive_us has been silent for at
/// least timeout_us as of now_us. Only the low 32 bits of each time count, so a silence of 2^31 us
/// (about 35.8 minutes) or more reads as none.
/// @param now_us Current time in microseconds.
/// @param last_receive_us SendspinConnection::get_last_receive_time_us().
/// @param timeout_us Liveness timeout in microseconds; 0 or negative never expires.
/// @return true if the connection should be dropped as lost.
bool liveness_expired(int64_t now_us, uint32_t last_receive_us, int64_t timeout_us);

/// @brief Microseconds until liveness_expired() would first return true, under the same 32-bit
/// arithmetic; 0 when it already would. Only meaningful for a positive timeout.
/// @param now_us Current time in microseconds.
/// @param last_receive_us SendspinConnection::get_last_receive_time_us().
/// @param timeout_us Liveness timeout in microseconds, positive.
int64_t liveness_remaining_us(int64_t now_us, uint32_t last_receive_us, int64_t timeout_us);

/// @brief Interval (milliseconds) at which the protocol task re-checks a network that is not ready
/// before starting the WebSocket server
///
/// The network provider has no readiness event, so the task polls it while the server is down.
/// The interval bounds the delay between the network coming up and the server listening; a
/// second is short beside the reconnect backoff of a server that found no listener.
static constexpr uint32_t NETWORK_POLL_INTERVAL_MS = 1000;

/// @brief A connection that has not completed the hello handshake
///
/// The hello is sent once, by the hello scan in ConnectionManager::scan_nursery() that first sees
/// the connection's Noise handshake complete, so whether it was attempted lives here and leaves
/// the nursery with the connection.
struct NurseryEntry {
    /// The only long-term owner, except for an ESP inbound connection, which its httpd session owns
    std::shared_ptr<SendspinConnection> conn;
    /// Whether the hello scan has made its one send attempt. Never cleared: a failed send drops
    /// the connection, and the close or the establish deadline reaps one whose hello was skipped
    /// (see ConnectionManager::send_hello_message()).
    bool hello_attempted{false};
    /// Whether client/init went out: at once for an inbound connection, once the transport
    /// reports its WebSocket upgrade for an outbound one.
    bool client_init_sent{false};
};

/// @brief One admitted connection and the roles it owns
///
/// Every role this client drives has at most one owner among the admitted connections: role
/// dispatch, role-message routing (role_send_target()) and the client/state role objects all gate
/// on ownership rather than on admission alone. Protocol task only.
struct AdmittedEntry {
    /// The admitted connection; null for a free slot.
    std::shared_ptr<SendspinConnection> conn;
    /// The ticket of the high-performance acquire this connection's burst requested
    /// (SendspinClient::request_high_performance()); meaningful while high_performance_held.
    uint32_t high_performance_ticket{0};
    /// The roles the connection owns, as SendspinConnection::get_active_role_mask() bits.
    uint16_t owned_roles{0};
    /// A client/state for this connection waits for its first clock measurement (see
    /// SendspinClient::publish_client_state()).
    bool state_held{false};
    /// This connection's time burst holds a high-performance request: from the tick its burst
    /// came due until the burst completes or the connection leaves the slot. The burst sends
    /// nothing until the main loop has granted the request (see run_time_sync()).
    bool high_performance_held{false};
};

/// @brief A released outbound connection waiting for its transport to finish
///
/// Releasing an outbound connection whose connect or WebSocket upgrade is still in flight must not
/// join its transport on the protocol task: the transport's stop waits out the connect timeout.
/// The release closes the transport without blocking (which leaves an attempt still connecting to
/// end on its own: see SendspinConnection::close_transport_now() on each platform) and parks the
/// connection here instead; the reap pass (ConnectionManager::reap_released()) drops it once its
/// inbound gate reports the transport closed or its upgrade completes, when the destructor's join
/// is short, or at `deadline_us`. A connection whose upgrade had completed when it was released
/// is not parked: its destructor's stop is that of an open transport, on ESP up to the websocket
/// task's one-second read poll (see ConnectionManager::release_connection()). Protocol task only.
struct ReapEntry {
    /// The released connection; detached, its transport closed without blocking.
    std::shared_ptr<SendspinConnection> conn;
    /// platform_time_us() past which the connection is dropped even with its transport still
    /// connecting: the release time plus SendspinClientConnection::CONNECT_TIMEOUT_MS, by when the
    /// transport's own connect bounds have ended the attempt or nearly so. The destructor's join
    /// at that point is bounded by what remains of the connect.
    int64_t deadline_us{0};
};

/// @brief Disposition for the connection once abort_pairing_attempt() ends a pairing attempt.
enum class PairingDropAction : uint8_t {
    KEEP_OPEN,           ///< Leave the connection open.
    CLOSE_SILENTLY,      ///< Drop the connection; no client/goodbye is sent.
    CLOSE_WITH_GOODBYE,  ///< Drop the connection, sending client/goodbye first.
};

/// @brief Which server-to-client pairing-code message arrived.
enum class PairingMessageKind : uint8_t {
    PAIR_INIT,     ///< server/pair-init: begins a round, carrying nonce_A in the first
    PAIR_AUTH,     ///< server/pair-auth: pake_msg_1
    PAIR_CONFIRM,  ///< server/pair-confirm: server_kc
    MALFORMED,     ///< a pairing message failed to parse; a spec Protocol Error when a
                   ///< pairing-code session is active (silent close, no pair/abort), ignored
                   ///< otherwise
};

/// @brief A parsed server pairing-code message, handed to ConnectionManager::on_pairing_message()
struct ServerPairingMessage {
    PairingMessageKind kind{};  ///< Which pairing message arrived

    // server/pair-init fields
    /// nonce_A decoded from the wire; absent after the attempt's first round
    /// (pairing.md "Server -> Client: server/pair-init").
    std::optional<std::array<uint8_t, 32>> nonce_a{};

    // server/pair-auth fields
    std::array<uint8_t, 32> pake_msg_1{};  ///< Server CPace public share

    // server/pair-confirm fields
    std::array<uint8_t, 64> server_kc{};  ///< Server CPace confirmation tag
};

/// @brief Pairing-UI display flags snapshotted from a connection's PairingSession.
///
/// conn->pairing_session().code_emitted / .window_shown are the sole record of whether a pairing
/// code or pairing-window prompt is still showing, and every path that ends a pairing attempt
/// clears that state before it gets a chance to dismiss the prompt. Capture the flags first,
/// then dismiss (SendspinClient::note_pairing_ui_dismissals()).
struct PairingUiSnapshot {
    bool code_was_emitted;
    bool window_was_shown;
};

/// @brief Captures `conn`'s pairing-UI display flags, before any pairing-state cleanup (see
/// PairingUiSnapshot). Protocol task, or the main loop once it is joined.
PairingUiSnapshot snapshot_pairing_ui(SendspinConnection* conn);

/**
 * @brief Manages WebSocket connection lifecycle.
 *
 * Accepts and creates connections, handles the hello handshake, decides admission and role
 * ownership, runs the pairing state machines, and performs graceful disconnection. Everything but
 * the entry points marked otherwise runs on the protocol task, which owns every slot below, so
 * none of them takes a lock.
 *
 * Connections prove themselves before they are trusted: every new connection enters a bounded
 * nursery and leaves it only by completing the hello handshake and being admitted by its first
 * server/activate (admission into a free slot, or a fair arbitration against the admitted
 * connections that own its roles) or by missing the establish deadline (reaped). The prove stage
 * starts with the Noise handshake: accept/connect -> Noise handshake complete -> hello handshake
 * complete -> first server/activate admitted. Admission is decided as that activate is
 * processed, so a message the server sends behind it is dispatched with the connection already
 * admitted. The platform ws_server delivers inbound connections only after observing their
 * WebSocket upgrade, so the manager never reasons about raw sockets that might not speak
 * WebSocket; those are closed inside the platform layer. Invariant: an admitted connection is
 * operational, except in the re-proving window bounded by scan_admitted().
 *
 * Every event-driven path here runs between SendspinClient::start() and ::stop(), so
 * client_->record_store_ and client_->identity_ are non-null and are not null-checked, except
 * where a site says why.
 */
class ConnectionManager {
public:
    /// @brief Maximum number of unproven inbound connections held at once
    ///
    /// The platform ws_server delivers only WS-upgraded sessions, so nursery slots are only ever
    /// occupied by peers that speak WebSocket; raw-TCP junk never reaches the nursery. Outbound
    /// entries do not count against the capacity in either direction: a user-initiated connect_to()
    /// is admitted even against full inbound slots, and an in-flight connect_to() never causes an
    /// inbound peer to be rejected. An outbound entry always replaces any previous one, so the
    /// whole nursery is bounded by MAX_NURSERY_ENTRIES.
    ///
    /// Socket-budget invariant: gracefully rejecting a surplus inbound peer requires the transport
    /// to accept MAX_ADMITTED + NURSERY_CAPACITY + 1 sockets (the admitted connections, the
    /// nursery, and the surplus peer, which must be connected to receive its goodbye). The default
    /// server_max_connections satisfies this; start() warns when a configured value does not.
    static constexpr size_t NURSERY_CAPACITY = 2;

    /// @brief Bound on the whole nursery: NURSERY_CAPACITY inbound entries plus the one outbound.
    static constexpr size_t MAX_NURSERY_ENTRIES = NURSERY_CAPACITY + 1;

    /// @brief Admitted slots: one connection drives the roles at a time.
    ///
    /// The array, role ownership and per-connection time sync already serve several admitted
    /// connections, each owning the roles no other one owns (admission_conflicts() in
    /// admission.h). Raising this admits a second server alongside the first whenever their roles
    /// do not overlap. These sites still assume one admitted connection and must be revisited
    /// first:
    /// - SendspinClient::cleanup_connection_state() resets the client-wide state (event ring,
    ///   group, time-sync report, pairing notes) only when the teardown covers every role,
    ///   which is keyed on no remaining connection owning one.
    /// - The time filter and server information slots follow primary() (the player's owner, else
    ///   the first admitted), so a role owned by another connection converts with the wrong clock.
    /// - group/update, leave() and get_server_information() are singular: they follow primary().
    /// - The artwork binary gate (SendspinClient::process_binary_message()) passes any admitted
    ///   connection while none owns the role.
    /// - A role released by a dropped connection is not reassigned to another admitted connection
    ///   that has it active until that connection's next activation.
    /// - An activation that adds a role another admitted connection owns never claims or
    ///   arbitrates for it (claimable_roles() simply leaves it out).
    /// - The socket budget: GRACEFUL_SOCKETS in start(), the default server_max_connections, and
    ///   the MAX_OPEN_CONNECTIONS < RecordStore::MIN_MAX_RECORDS static_assert below.
    static constexpr size_t MAX_ADMITTED = 1;

    /// @brief Maximum connections open at once: the admitted ones plus the nursery bound.
    static constexpr size_t MAX_OPEN_CONNECTIONS = MAX_NURSERY_ENTRIES + MAX_ADMITTED;

    // pairing.md "Pairing Records" requires the client to cap its concurrently open paired
    // connections below its record capacity, so that a completed pairing at capacity always has
    // a record left to evict. The connection budget is fixed at compile time and the record
    // capacity has a floor, so the cap is an invariant rather than a runtime check.
    static_assert(MAX_OPEN_CONNECTIONS < RecordStore::MIN_MAX_RECORDS,
                  "open connections must stay below the pairing-record capacity floor");
    static_assert(MAX_ADMITTED <= 32, "admission_conflicts() reports slots in a 32-bit mask");

    /// @brief Released outbound connections parked at once, waiting for their transports to finish
    /// (see ReapEntry).
    ///
    /// A release parks at most the one connection it releases, and only an outbound attempt still
    /// connecting is parked (connect_to() keeps one in the nursery, replacing the previous one),
    /// so the list fills only when attempts are released faster than their transports finish
    /// (connect_to() called repeatedly against a listener that never answers). The capacity, the
    /// whole nursery plus every admitted slot, is headroom for that case rather than a bound one
    /// pass can reach. A release that finds it full logs a warning and drops the entry parked
    /// longest, whose destructor's join is then paid on the protocol task: the shortest of the
    /// parked ones, since its attempt is the furthest along, and at most what remains of its
    /// connect timeout.
    static constexpr size_t REAPING_CAPACITY = MAX_NURSERY_ENTRIES + MAX_ADMITTED;

    /// @brief Every managed connection at one moment: the admitted ones and the nursery.
    using ConnectionSnapshot =
        InlineVector<std::shared_ptr<SendspinConnection>, MAX_OPEN_CONNECTIONS>;

    explicit ConnectionManager(SendspinClient* client);
    ~ConnectionManager();

    // ========================================
    // Main loop
    // ========================================

    /// @brief Opens admission (ProtocolTask::open_accepts(), the inverse of close_admission()),
    /// creates the WebSocket server on first use, and starts it at once when the network provider
    /// already reports ready
    ///
    /// Server configuration is read from the client's config when the server object is created;
    /// a restart reuses the object. A server that did not start here is started by the protocol
    /// task once the network is ready (maybe_start_ws_server()). Main loop only, before the
    /// protocol task starts: everything it writes is the task's from then on.
    void start();

    /// @brief Closes admission (ProtocolTask::close_accepts()) and wakes the protocol task.
    /// Main loop, from stop(), ~SendspinClient and a failed start(), before ProtocolTask::stop():
    /// the protocol task's next tick, or its final one, then runs the shutdown pass (shutdown())
    /// and refuses every accept already queued with a goodbye, and a delivery from here on is
    /// refused at its push, so its transport closes it without one.
    void close_admission();

    /// @brief The main-loop half of the shutdown, once the protocol task is joined: closes the
    /// transport of every connection still parked for reaping (the shutdown pass's disconnect()
    /// closed the ones it took out of the slots), stops the WebSocket server (joining its
    /// transport threads), then releases those connections, whose destructors may join an outbound
    /// transport thread. Main loop only.
    ///
    /// Blocks on the transports' own teardown: the host server joins every accepted connection
    /// thread, including a raw socket that never completed its WebSocket upgrade, which can hold
    /// the join for the full WS_HANDSHAKE_TIMEOUT_SECS (3 s); the ESP server waits for the httpd
    /// task to exit, and an outbound connection's transport stop is synchronous
    /// (esp_websocket_client_stop() / ix::WebSocket::stop()), up to its connect timeout
    /// (SendspinClientConnection::CONNECT_TIMEOUT_MS) for one still connecting.
    /// @return The pairing prompts the dropped connections left showing. The caller dismisses
    ///         them (SendspinClient::note_pairing_ui_dismissals()) after its own
    ///         cleanup_connection_state(), which would otherwise wipe the queued notes.
    PairingUiSnapshot finish_stop();

    // ========================================
    // Any thread
    // ========================================

    /// @brief Returns true if an admitted connection is connected, has completed its handshake
    /// and has its latest server/activate applied. Any thread: reads a flag the protocol task
    /// raises only at the end of a tick (publish_connected()), after every handler of that tick
    /// has run, so a reader that sees it true sees the tick's whole effect (the published slots
    /// and the events it queued for the drain); refresh_published_state() lowers it at once.
    bool is_connected() const {
        return this->connected_.load(std::memory_order_acquire);
    }

    /// @brief Returns the time filter of the primary admitted connection (see primary()), or
    /// nullptr when none is admitted. Any thread.
    ///
    /// Reads published_ behind the leaf published_mutex_, so a role thread converting
    /// timestamps (the sync task per chunk, the visualizer drain per frame) never waits on the
    /// protocol task. Hands out the filter, never the connection: a connection's destructor can
    /// join its transport thread, which must not happen on a role thread, while the filter's last
    /// drop only frees memory.
    std::shared_ptr<SendspinTimeFilter> time_filter() const;

    /// @brief Returns a copy of the primary admitted connection's server information, or nullopt
    /// when none is admitted. Any thread: reads a slot written at admission and cleared on the
    /// drop, behind the leaf published_mutex_.
    std::optional<ServerInformationObject> server_information() const;

    // ========================================
    // Transport threads
    // ========================================

    /// @brief Hands a WebSocket-upgraded inbound connection to the protocol task
    ///
    /// Called by the platform ws_server on its delivery thread (ESP: httpd task, host: IXWebSocket
    /// thread). Wires the connection to the inbound ring and the protocol task ahead of its first
    /// frame, then queues an ACCEPT_CONNECTION command; the task admits it into the nursery or
    /// refuses it with a goodbye (accept()).
    /// @return false when the connection's inbound gate has no event group
    ///         (InboundGate::is_created()), or the command queue refused the accept: every accept
    ///         slot is taken, or admission is closed (close_admission()). The connection
    ///         is then left with the caller, which closes its socket and releases it on its own
    ///         close path (see SendspinWsServer::NewConnectionCallback), so it is never destroyed
    ///         inside this call.
    bool on_new_connection(const std::shared_ptr<SendspinServerConnection>& conn);

    // ========================================
    // Protocol task: commands
    // ========================================

    /// @brief Admits a delivered inbound connection into the nursery and starts its prove stage
    ///
    /// Never admits it to an admitted slot directly (the connection has not proven itself yet).
    /// If the inbound slots are full (outbound entries do not count), or admission is closed, the
    /// newcomer is refused with a goodbye, which reaches the peer because its session is already
    /// upgraded. Sends client/init immediately: the connection is already WS-upgraded, so there is
    /// no earlier signal to wait for; the hello is armed once the Noise handshake completes.
    void accept(std::shared_ptr<SendspinConnection> conn);

    /// @brief Initiates an outbound connection to a Sendspin server (SendspinClient::connect_to()).
    /// Logs an error and connects nothing when the connection's inbound gate has no event group
    /// (InboundGate::is_created()).
    /// @param url WebSocket URL of the server to connect to.
    void connect_to(const std::string& url);

    /// @brief Goodbyes and drops every connected managed connection (SendspinClient::disconnect())
    /// through drop_connection(), so the goodbye is the last thing on the wire (the pairing
    /// dismissal follows it in the inbox). An admitted connection whose transport is already gone
    /// is left to the loss pass; an outbound attempt still connecting is released at once, without
    /// a goodbye or waiting for its transport (see ReapEntry).
    /// @param reason The goodbye reason to send before closing.
    void disconnect(SendspinGoodbyeReason reason);

    /// @brief Sends client/leave to the primary admitted connection (SendspinClient::leave()).
    void leave();

    /// @brief The connection a role message goes to: the admitted, connected owner of `role`
    /// once its server/activate has arrived, or nullptr (logged) when the message must be dropped.
    /// The caller builds the message only once it knows it will be sent (the controller commands
    /// SendspinClient::send_controller_command() queues). Protocol task only.
    SendspinConnection* role_send_target(SendspinRole role) const;

    /// @brief Opens the pairing window (SendspinClient::confirm_pairing_window(), the operator
    /// gesture). If an attempt is already waiting in AWAIT_PAIRING_WINDOW, the window admits it
    /// immediately; otherwise it stands open for WINDOW_LIFETIME_US (5 minutes) awaiting a
    /// pairing activate. The gesture is also the deliberate operator action that clears a
    /// standing round limit (pairing.md "Rounds").
    void open_pairing_window();

    /// @brief Closes the pairing window (SendspinClient::cancel_pairing_window()).
    void cancel_pairing_window();

    /// @brief Closes the managed connections a changed unpaired-access setting no longer fits
    /// (SendspinClient::set_unpaired_access_enabled()).
    void apply_unpaired_access_change(bool enabled);

    // ========================================
    // Protocol task: message handlers
    // ========================================

    /// @brief Applies a server/activate: trust enforcement, pairing-method admissibility, role
    /// ownership and removals, and, for a nursery connection that is now operational, admission
    /// before this returns.
    void on_server_activate(SendspinConnection* conn, ServerActivateMessage&& msg);

    /// @brief Handles a pair/abort from the server (an unrecognized reason arrives as
    /// METHOD_NOT_SUPPORTED). Only an admitted connection hosts a pairing attempt.
    void on_pair_abort(SendspinConnection* conn, PairAbortReason reason);

    /// @brief Handles server/unpair on an admitted connection.
    void on_server_unpair(SendspinConnection* conn);

    /// @brief Advances the pairing-code state machine of an admitted connection.
    void on_pairing_message(SendspinConnection* conn, const ServerPairingMessage& message);

    /// @brief Records a completed pairing: the server/pair-finalize handler stored the long-term
    /// record for `conn`. Closes the pairing window the attempt ran under and queues
    /// on_pairing_succeeded.
    void on_pairing_succeeded(SendspinConnection* conn);

    /// @brief Drops a lost connection (the transport closed with every message it sent processed,
    /// or its inbound gate was detached). A connection the manager no longer manages is a no-op.
    void on_connection_lost(SendspinConnection* conn);

    /// @brief psk_ids backing a currently-open connection, admitted or in the nursery. These are
    /// the records a completed pairing must not evict (pairing.md "Pairing Records").
    [[nodiscard]] std::vector<std::string> open_connection_psk_ids() const;

    // ========================================
    // Protocol task: tick
    // ========================================

    /// @brief Copies every managed connection (the admitted ones and the nursery) into `out`. A
    /// copy can be a connection's last reference, whose destructor then runs on the protocol
    /// task: every path that releases a connection detaches its inbound gate first
    /// (SendspinConnection::detach_inbound()), so a transport thread that destructor joins is
    /// never parked on the gate, and at most INBOUND_ACQUIRE_TIMEOUT_MS in a ring acquire. An
    /// outbound attempt still connecting is parked for reaping when released (see ReapEntry), so
    /// its last reference drops once its transport has closed or opened, or its deadline passed.
    void snapshot_connections(ConnectionSnapshot& out) const;

    /// @brief Sends client/init on every outbound nursery connection whose transport reported its
    /// WebSocket upgrade since the last tick. Ahead of the receive pass: the server says nothing
    /// before client/init.
    void start_upgraded_handshakes();

    /// @brief The lifecycle scans, in order: the hello sends, admission of nursery connections
    /// that became operational, the nursery establish reap, the admitted connections' liveness,
    /// re-prove and pairing-attempt watchdogs, the pairing window's lifetime, the reap of
    /// released outbound connections, the platform server's upgrade reap, and the WebSocket
    /// server start
    /// @param now_us platform_time_us() at the start of the tick.
    /// @return Milliseconds until the earliest of those timers, or ProtocolTask::NO_DEADLINE.
    uint32_t tick(int64_t now_us);

    /// @brief Drives each admitted, operational connection's time burst: requests the
    /// high-performance hold when a burst comes due and sends its first client/time only once the
    /// main loop has granted that request (SendspinClient::high_performance_granted()), releases
    /// the hold when the burst completes without waiting for anything, reports a completed
    /// burst's filter error to the main loop, and sends a client/state that waited for the clock
    /// @return Milliseconds until the earliest burst is due, or ProtocolTask::NO_DEADLINE; a
    ///         burst waiting for its grant adds no deadline, since the grant wakes the task.
    uint32_t run_time_sync();

    /// @brief Refreshes what other threads read without a protocol-task lock: the time filter and
    /// server-information slots (from primary()), and lowers the connected flag when no admitted
    /// connection is operational. Called at the end of every tick and after every change to the
    /// admitted array.
    void refresh_published_state();

    /// @brief Publishes the connected flag (is_connected()) as it stands, raising it as well as
    /// lowering it. Called only at the end of the tick, after refresh_published_state(): raised
    /// mid-handler it would read true before the tick's whole effect (the published slots and
    /// the events queued for the drain) is in place.
    void publish_connected();

    /// @brief Whether admission is open: false from close_admission() until the next start()
    /// (ProtocolTask::is_accepting()).
    bool is_accepting() const;

    /// @brief Whether the shutdown pass is due: admission is closed and the pass has not run.
    bool shutdown_pending() const;

    /// @brief The shutdown pass: snapshots the pairing-UI flags, detaches every managed
    /// connection, empties the slots, closes the pairing window, and goodbyes and closes each
    /// connection with reason shutdown. The connections are kept for finish_stop().
    void shutdown();

    // ========================================
    // Protocol task: role ownership
    // ========================================

    /// @brief The admitted entry holding `conn` (not null), or nullptr.
    AdmittedEntry* find_admitted(const SendspinConnection* conn);

    /// @brief Whether `conn` is admitted and owns `role`, the gate every role dispatch, the role
    /// send path and the client/state role objects apply. Ownership implies the role is active on
    /// the connection.
    bool owns_role(const SendspinConnection* conn, SendspinRole role) const;

    /// @brief The admitted connection that owns `role`, or nullptr when none does.
    SendspinConnection* role_owner(SendspinRole role) const;

    /// @brief The admitted connection whose clock and server information the client reports: the
    /// owner of the player role, or the first admitted connection when no connection owns it.
    /// With MAX_ADMITTED 1 that is the admitted connection. nullptr when none is admitted.
    AdmittedEntry* primary();

    /// @brief Whether an admitted connection is connected and operational: what is_connected()
    /// publishes.
    bool has_operational_connection() const;

    /// @brief The roles owned by every admitted connection but the one `except` holds (all of
    /// them for nullptr).
    uint16_t roles_owned_by_others(const AdmittedEntry* except) const;

    /// @brief Calls `visit` on every admitted entry.
    template <typename Visit>
    void for_each_admitted(Visit&& visit) {
        for (auto& entry : this->admitted_) {
            if (entry.conn != nullptr) {
                visit(entry);
            }
        }
    }

    // ========================================
    // Handoff support
    // ========================================

    /// @brief Sets the last-played server_id for handoff preference decisions. Main loop before the
    /// protocol task starts (SendspinClient::load_last_played_server()), protocol task after.
    /// @param server_id The server_id of the last-played server; empty clears it.
    void set_last_played_server_id(const std::string& server_id);

    /// @brief Returns the current last-played server_id, or nullopt if unset. Protocol task.
    const std::optional<std::string>& last_played_server_id() const {
        return this->last_played_server_id_;
    }

private:
    // ========================================
    // Tick steps
    // ========================================

    /// @brief Starts the WS server once the network becomes ready. A persistent failure (e.g. the
    /// server port is already in use) is retried with backoff instead of on every tick, which
    /// would spam the log. Nothing while admission is closed.
    /// @return Milliseconds until the next attempt or readiness poll, or NO_DEADLINE once started.
    uint32_t maybe_start_ws_server(int64_t now_us);

    /// @brief Arms hellos for nursery connections whose Noise handshake just completed and sends
    /// the ones that are due (the level-triggered hello scan), admits the ones that are now
    /// operational, and reaps the ones that miss the establish deadline.
    /// @return Milliseconds until the next hello attempt or establish deadline.
    uint32_t scan_nursery(int64_t now_us);

    /// @brief Drops the released outbound connections whose transport has closed or opened, or
    /// whose deadline has passed (see ReapEntry).
    /// @return Milliseconds until the earliest remaining deadline, or NO_DEADLINE; a transport's
    ///         close or upgrade wakes the task itself.
    uint32_t reap_released(int64_t now_us);

    /// @brief The admitted connections' watchdogs: liveness (a silent connection is dropped with
    /// a restart goodbye), re-prove (one that failed to re-prove after an in-band re-handshake or
    /// a pairing-finalize rekey within REPROVE_TIMEOUT_US is closed without a goodbye: one of
    /// those windows starts at Noise message 1, where connection.md "Re-handshake" allows no
    /// application message), and the pairing-attempt timeout (PAIRING_ATTEMPT_TIMEOUT_US,
    /// suppressed once the pairing is finalized and while the connection awaits a
    /// server/activate, where connection.md "Re-handshake" lets the client start no application
    /// message).
    /// @return Milliseconds until the earliest of those deadlines.
    uint32_t scan_admitted(int64_t now_us);

    // ========================================
    // Connection setup
    // ========================================

    /// @brief The client's JSON arena, which every message the manager builds is built in. The
    /// arena lives as long as the client, so handing out the reference is safe from any thread
    /// (on_new_connection() gives it to an inbound connection on its delivery thread); building
    /// in it is protocol task only.
    SendspinArenaAllocator& json_arena() const;

    /// @brief Installs the Noise handshake driver on a nursery connection and sends client/init.
    void start_noise_handshake(NurseryEntry& entry);

    /// @brief Finds the nursery entry holding `conn`, or nursery_.end().
    NurseryEntry* find_in_nursery(const SendspinConnection* conn);

    /// @brief Installs `conn` in a free admitted slot as the owner of `owned_roles`, marks it
    /// admitted, and refreshes the published slots. The slot must be free.
    void install_admitted(std::shared_ptr<SendspinConnection> conn, uint16_t owned_roles);

    /// @brief Takes the connection out of an occupied admitted slot: detaches its inbound gate,
    /// clears its admitted flag and time burst, releases the high-performance request its burst
    /// held, and frees the slot. The caller refreshes the published slots and releases the
    /// connection it returns.
    std::shared_ptr<SendspinConnection> vacate_admitted(AdmittedEntry& entry);

    /// @brief Releases a nursery entry: erases it, then goodbyes and drops the connection.
    /// @param reason The goodbye reason to send before closing, or nullopt when the transport is
    ///        already gone so no goodbye should be attempted.
    /// @return Iterator to the entry after the erased one.
    NurseryEntry* release_nursery_entry(NurseryEntry* it,
                                        std::optional<SendspinGoodbyeReason> reason);

    /// @brief Refuses an accept queued before admission closed and taken after: goodbye with
    /// reason shutdown and a close, and the connection kept for finish_stop().
    void refuse_accept(std::shared_ptr<SendspinConnection> conn);

    /// @brief Goodbyes and closes `conn` with reason shutdown, and keeps it for finish_stop(). Its
    /// gate must be detached.
    void goodbye_for_shutdown(std::shared_ptr<SendspinConnection> conn);

    /// @brief Detaches `conn`, sends `goodbye` if there is one or else closes a still-connected
    /// transport without one, and drops the caller's reference: a connection leaving the
    /// manager. An outbound attempt still connecting is parked for reaping instead
    /// (park_for_reaping()), since its destructor would join its transport for the rest of the
    /// connect.
    void release_connection(std::shared_ptr<SendspinConnection> conn,
                            std::optional<SendspinGoodbyeReason> goodbye);

    /// @brief Closes a released outbound connection's transport without blocking and parks it
    /// in reaping_ until reap_released() drops it; with the list full, first drops the entry
    /// parked longest, with a warning.
    void park_for_reaping(std::shared_ptr<SendspinConnection> conn);

    // ========================================
    // Hello handshake
    // ========================================

    /// @brief Sends the hello message to a connection whose Noise handshake completed, and marks
    /// it sent on success.
    /// @param conn The nursery connection to send the hello to.
    /// @return False if the send failed on a connected transport, which the caller closes without
    /// a goodbye and drops: the send nonce is spent by then unless the send buffer could not be
    /// allocated, so neither a resend nor a goodbye could be decrypted. True otherwise, including a
    /// transport that is not connected or that refuses the send as INVALID_STATE, which the close
    /// or the establish deadline reaps.
    bool send_hello_message(SendspinConnection* conn);

    // ========================================
    // Connection lifecycle
    // ========================================

    /// @brief Decides whether an incoming connection should displace an admitted one.
    ///
    /// Applies admission.h::should_admit_connection; trust enforcement runs earlier, on the
    /// activate.
    /// @param admitted The admitted connection the incoming one conflicts with, or nullptr.
    /// @param new_conn The newly proven candidate connection. Must not be null.
    /// @return True if the new connection should displace `admitted`.
    bool should_switch_to_new_server(const SendspinConnection* admitted,
                                     const SendspinConnection* new_conn) const;

    /// @brief Updates last_played_server_id when an admitted connection carries the PLAYBACK
    /// activity, per the last-playback server of connection.md "Multiple servers
    /// (server-initiated)". No-op if conn is not admitted, or does not declare PLAYBACK.
    /// On a long-term record, also moves that record to most recently used in the record store.
    /// Both RAM updates run here, so arbitration later in the same step sees the last-played
    /// server and a pair-finalize eviction sees the new recency; the durable writes are left to
    /// SendspinClient::flush_pending_persistence() on the main loop.
    /// @param conn The connection to check (typically the connection an activate just applied to).
    void note_playback_activity(const SendspinConnection* conn);

    /// @brief Admits a proven nursery entry (it->conn->is_operational() must already be true)
    /// into a free admitted slot, or arbitrates it against the admitted connections that own its
    /// roles (admission_conflicts()), or rejects it with concurrent_attempt if any of them wins.
    ///
    /// Erases the entry from the nursery unconditionally (it never returns to the nursery). The
    /// incoming side is always operational; an incumbent may be in its re-proving window, where
    /// its activities are still rank-correct, so arbitration on them stays valid. On the winning
    /// outcome, notifies the client, publishes state, and records playback activity.
    /// Runs inside the protocol tick, whose ConnectionSnapshot keeps the connection alive when a
    /// step here drops it.
    /// @param it Valid iterator into nursery_ whose connection satisfies is_operational().
    /// @return Iterator to the entry after the erased one (for use in a scanning loop).
    NurseryEntry* promote_or_arbitrate_nursery_entry(NurseryEntry* it);

    /// @brief Single teardown path: removes a managed connection (an admitted slot or a nursery
    /// entry), cleans up the client state no remaining admitted connection owns (admitted slot
    /// only), and goodbyes and drops it. No-op if conn is null or not a managed connection.
    ///
    /// @param conn The connection to drop; must be admitted or a nursery entry.
    /// @param goodbye Goodbye reason to send before closing, or nullopt to close without one
    ///        (the transport is already gone, or the spec forbids an application message).
    void drop_connection(SendspinConnection* conn, std::optional<SendspinGoodbyeReason> goodbye);

    /// @brief Drop every managed connection that authenticated with `psk_id`.
    ///
    /// Removing a record from the RecordStore does not by itself end a session that is already
    /// running on it: a connection caches its resolved psk_id and PSK category at Noise-handshake
    /// completion (see SendspinConnection::get_psk_category()) and never re-resolves them against
    /// the store. Without this sweep a revoked device would keep its LONG_TERM trust (and with
    /// it playback) until it happened to disconnect, so revocation would not take effect until
    /// the peer's next connection.
    ///
    /// Covers the admitted slots and the nursery.
    ///
    /// @param psk_id psk_id whose sessions are no longer trusted.
    /// @param except Connection to leave alone (its caller is already dropping it), or nullptr.
    void drop_connections_using_psk_id(const std::string& psk_id, const SendspinConnection* except);

    // ========================================
    // Pairing handlers
    // ========================================

    /// @brief Enters the pairing exchange for the given connection.
    /// Called when an admitted server/activate declares the PAIRING activity with a
    /// pairing.method the client offers. PLAYBACK may ride along, and the activate need not be
    /// the connection's first. Starts from a clean pairing session, dismissing any prompt an
    /// earlier attempt left showing.
    /// @param conn The connection entering pairing. Must be non-null.
    void handle_enter_pairing(SendspinConnection* conn);

    /// @brief Runs the pairing-code branch of handle_enter_pairing(): populates the
    /// PairingSession, applies gesture gating (pairing.md "Pairing Window"), and either sends
    /// client/pair-pending and waits for a window, or starts the attempt immediately.
    /// @param conn The connection entering pairing.
    /// @param pairing_index Current pairing_index counter, captured by handle_enter_pairing().
    /// @param server_id conn->get_server_id(), captured by handle_enter_pairing().
    /// @param selected_method The selected method; DYNAMIC_PAIRING_CODE or STATIC_PAIRING_CODE.
    void handle_enter_pairing_code(SendspinConnection* conn, uint32_t pairing_index,
                                   const std::string& server_id,
                                   SendspinPairMethod selected_method);

    /// @brief Runs the Pairing-PSK branch of handle_enter_pairing(): resolves the pairing
    /// outcome and sends client/pair-init followed by client/pair-finalize with the long-term
    /// PSK in the clear.
    /// @param conn The connection entering pairing.
    /// @param pairing_index Current pairing_index counter, captured by handle_enter_pairing().
    /// @param server_id conn->get_server_id(), captured by handle_enter_pairing().
    void handle_enter_pairing_psk(SendspinConnection* conn, uint32_t pairing_index,
                                  const std::string& server_id);

    /// @brief Handles a pair/abort on an admitted connection.
    /// Cleans up pairing state. Per pairing.md "pair/abort", only closes the connection for reason
    /// concurrent_attempt. A pair/abort that arrives after the attempt has already ended
    /// (is_pairing_in_progress() false) is silently ignored (stale).
    /// @param conn The connection on which the abort arrived. Must be non-null.
    /// @param reason The abort reason.
    void handle_pair_abort(SendspinConnection* conn, PairAbortReason reason);

    /// @brief Shared cleanup for every path that locally ends a pairing attempt on `conn`.
    ///
    /// Captures the code-emission / pairing-window flags before clear_pairing_state() resets
    /// them, optionally sends a wire pair/abort, clears the pairing state, optionally drops the
    /// connection, then queues on_pairing_failed and dismisses any pairing UI left showing (via
    /// SendspinClient::note_pairing_ui_dismissals()). When `drop_action` is not KEEP_OPEN,
    /// drop_connection() -> the admitted-slot cleanup runs before the note_* calls, matching the
    /// ordering every call site needs (the cleanup wipes the queued pairing notes). `conn` must be
    /// non-null.
    ///
    /// @param wire_abort_reason  If set, sends pair/abort(wire_abort_reason) to the peer first
    ///        (best-effort). Leave nullopt when the abort was received from the peer, or when
    ///        pairing.md "Protocol Errors" forbids sending one.
    /// @param drop_action        Disposition for the connection; see PairingDropAction.
    /// @param public_reason      Reason delivered to the application via on_pairing_failed.
    /// @param goodbye_reason     Read only when drop_action is CLOSE_WITH_GOODBYE, so callers for
    ///        the other actions omit it.
    void abort_pairing_attempt(
        SendspinConnection* conn, std::optional<PairAbortReason> wire_abort_reason,
        PairingDropAction drop_action, SendspinPairAbortReason public_reason,
        SendspinGoodbyeReason goodbye_reason = SendspinGoodbyeReason::UNAUTHORIZED);

    /// @brief Handle a server pairing-code message: advances the PairingStep state machine for
    /// the connection. `conn` must be non-null.
    void handle_pairing_message(SendspinConnection* conn, const ServerPairingMessage& message);

    /// @brief Handles PairingMessageKind::PAIR_INIT: begins a round (pairing.md "Rounds"),
    /// deriving and emitting the pairing code in the attempt's first one, and starts a fresh
    /// CPace run as RESPONDER. Dynamic pairing code only; a PAIR_INIT while
    /// ps.method == STATIC_PAIRING_CODE is a wrong-step protocol violation.
    /// @param conn The connection that received the message.
    /// @param message The parsed server pairing message; nonce_a is used here.
    void handle_pair_init(SendspinConnection* conn, const ServerPairingMessage& message);

    /// @brief Handles PairingMessageKind::PAIR_AUTH: sends client/pair-auth (pake_msg_2), then
    /// derives the MAC key from the server's share (pake_msg_1). A derive failure is a spec
    /// Protocol Errors close (no pair/abort), not a pairing-code mismatch.
    /// @param conn The connection that received the message.
    /// @param message The parsed server pairing message; pake_msg_1 is used here.
    void handle_pair_auth(SendspinConnection* conn, const ServerPairingMessage& message);

    /// @brief Handles PairingMessageKind::PAIR_CONFIRM: verifies server_kc, then either sends
    /// client/pair-confirm plus the CPace-wrapped client/pair-finalize, or asks for another
    /// round with client/pair-retry, or aborts at the round limit (pairing.md "Rounds").
    /// @param conn The connection that received the message.
    /// @param message The parsed server pairing message; server_kc is used here.
    void handle_pair_confirm(SendspinConnection* conn, const ServerPairingMessage& message);

    /// @brief Abort the current pairing-code session: send pair/abort, notify, and close the
    /// connection only for reason concurrent_attempt (pairing.md "pair/abort").
    /// @param conn The connection to abort. Must be non-null.
    /// @param reason The abort reason to send.
    void local_abort_pairing(SendspinConnection* conn, PairAbortReason reason);

    /// @brief End an attempt whose peer sent a pairing message out of sequence: close the
    /// connection without any application-level message and persist nothing
    /// (pairing.md "Sequence violations", "Protocol Errors").
    /// @param conn The connection that sent the out-of-sequence message.
    /// @param message_type The message type as it appears on the wire, for the log.
    void close_on_sequence_violation(SendspinConnection* conn, const char* message_type);

    // ========================================
    // Pairing window
    // ========================================

    /// @brief Start the prepared attempt on `conn`: send client/pair-init (with commit_B for a
    /// dynamic pairing code, bare plus CPace start for a static one), advance the PairingStep,
    /// and arm the attempt timeout. The PairingSession must already be populated by
    /// handle_enter_pairing.
    /// @param conn The connection whose session starts.
    void start_pairing_attempt(SendspinConnection* conn);

    /// @brief Start the CPace exchange for the current round: build the sid and start the
    /// RESPONDER run over the session's PRS (pairing.md "PAKE"), advancing the step to
    /// AWAIT_SERVER_PAIR_AUTH. Aborts the attempt and returns false when CPace refuses to start.
    /// @param conn The connection whose session runs the exchange.
    /// @return true when the round started.
    bool start_pake_round(SendspinConnection* conn);

    /// @brief Return true if a pairing window is open (opened by an operator gesture, not yet
    /// closed by one of pairing.md "Pairing Window"'s closing events, and within its 5-minute
    /// lifetime).
    [[nodiscard]] bool pairing_window_open() const;

    /// @brief Whether the dynamic-pairing-code round limit currently holds attempts back:
    /// PAIRING_ROUND_LIMIT rounds have run since the last verified server_kc
    /// (pairing.md "Rounds").
    [[nodiscard]] bool pairing_round_limit_reached() const;

    /// @brief Close the pairing window: clear its deadline, the connection it is bound to, and
    /// its failed-attempt count. Idempotent, and silent when no window is open.
    void close_pairing_window();

    /// @brief Whether an open window admits an attempt on `conn`. The window admits attempts
    /// only on the connection that carried its first (pairing.md "Pairing Window"), so a second
    /// server cannot ride a gesture the operator made for another one.
    /// @param conn The connection whose attempt is being judged.
    /// @return true when the window admits an attempt on `conn`.
    [[nodiscard]] bool pairing_window_admits(const SendspinConnection* conn) const;

    /// @brief Count one attempt under the current window whose server_kc verification failed,
    /// closing the window on the fifth (pairing.md "Pairing Window").
    void note_pairing_window_attempt_failed();

    // ========================================
    // Unpair handler
    // ========================================

    /// @brief Handles server/unpair on an admitted connection: checks PSK category (LONG_TERM
    /// only), removes the matched record, and disconnects with the UNPAIRED reason. `conn` must
    /// be non-null.
    void handle_server_unpair(SendspinConnection* conn);

    // Struct fields
    // Admitted connections and the roles each owns. Protocol task only.
    std::array<AdmittedEntry, MAX_ADMITTED> admitted_{};
    // Unproven connections awaiting establishment, each carrying its hello send state. Protocol
    // task only.
    InlineVector<NurseryEntry, MAX_NURSERY_ENTRIES> nursery_;
    // Released outbound connections waiting for their transports to finish (see ReapEntry).
    // Protocol task, then the main loop's finish_stop() once the task is joined.
    InlineVector<ReapEntry, REAPING_CAPACITY> reaping_;
    // Connections the shutdown pass took out of the slots, and accepts it refused, closed by
    // their disconnect() and kept for finish_stop() to release. Written by the protocol task's
    // shutdown pass, read on the main loop once the task is joined.
    std::vector<std::shared_ptr<SendspinConnection>> closing_;

    // Pointer fields
    SendspinClient* client_;
    // The connection carrying the window's first attempt, or nullptr while the window has
    // admitted none. Compared, never dereferenced, and cleared whenever the window closes, so a
    // released connection's address cannot be mistaken for a live one. Protocol task only.
    const SendspinConnection* pairing_window_conn_{nullptr};
    // Created by the main loop's start() on first use; started there or by the protocol task,
    // ticked by the task, and stopped by finish_stop() once the task is joined.
    std::unique_ptr<SendspinWsServer> ws_server_;

    // String fields
    /// server_id of the last-played server; nullopt if unset. Written on the main loop by
    /// start()'s load, before the protocol task runs, then protocol task only.
    std::optional<std::string> last_played_server_id_;

    // 64-bit fields
    /// From resolve_liveness_timeout_ms(), in microseconds; 0 or negative disables the check.
    /// Fixed at construction.
    int64_t liveness_timeout_us_{0};
    // Standing pairing window: platform_time_us() deadline until which the window admits
    // attempts on pairing_window_conn_; 0 = closed. Opened by the operator gesture; cleared by
    // close_pairing_window() on a completed pairing, the fifth failed attempt, the bound
    // connection's drop, operator cancel, expiry, and the shutdown pass. Protocol task only.
    int64_t pairing_window_open_until_us_{0};
    /// Earliest time (us) to attempt another WS server start after a failure. Reset by start() on
    /// the main loop before the protocol task runs, then protocol task only.
    int64_t ws_server_start_retry_time_us_{0};
    /// get_instance_id() of the connection the published slots were last filled from
    /// (refresh_published_state()), 0 for none: an id rather than an address, which a later
    /// connection could reuse. Protocol task only.
    uint64_t published_primary_id_{0};

    // 32-bit fields
    // Dynamic-pairing-code rounds run since the last verified server_kc (pairing.md "Rounds").
    // Not partitioned by server_id or source address, and not persisted: the limit gates how
    // fast an attacker can guess within one boot, which a reboot does not shorten. Protocol task
    // only.
    uint32_t pairing_rounds_since_verified_kc_{0};

    // Attempts under the current window that ended in a failed server_kc verification. Reset
    // when a window opens. Protocol task only.
    uint32_t pairing_window_failed_attempts_{0};

    // 8-bit fields
    /// The pairing prompts the shutdown pass found showing. Written by the protocol task's
    /// shutdown pass, read by finish_stop() on the main loop once the task is joined.
    PairingUiSnapshot shutdown_ui_{false, false};

    /// Whether the shutdown pass has run since start(). Reset by start() before the protocol task
    /// runs, then protocol task only.
    bool shutdown_done_{false};

    /// What is_connected() reports. Written by the protocol task (raised by publish_connected(),
    /// lowered by refresh_published_state() too), read from any thread.
    std::atomic<bool> connected_{false};

    /// @brief What other threads read of the primary admitted connection, written together
    struct PublishedPrimary {
        std::shared_ptr<SendspinTimeFilter> time_filter;
        std::optional<ServerInformationObject> server_information;
    };
    /// Guards published_. A leaf (docs/conventions.md): held only to copy or swap a member, so it
    /// also serialises the role threads' time_filter() reads behind server_information() copies.
    mutable std::mutex published_mutex_;
    /// Written by the protocol task (refresh_published_state()); read from any thread
    /// (time_filter(), server_information()): role threads, the main loop, the consumer.
    PublishedPrimary published_;
};

}  // namespace sendspin
