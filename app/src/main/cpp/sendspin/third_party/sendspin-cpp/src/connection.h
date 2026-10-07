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

/// @file connection.h
/// @brief Abstract base class for Sendspin WebSocket connections, providing handshake, time sync,
/// and message buffering

#pragma once

#include "crypto/cpace.h"
#include "crypto/keys.h"
#include "inbound_ring.h"
#include "noise_handshake.h"
#include "noise_transport.h"
#include "platform/crypto.h"
#include "platform/memory.h"
#include "platform/types.h"
#include "protocol_messages.h"
#include "record_store.h"
#include "sendspin/config.h"
#include "sendspin/types.h"
#include "time_burst.h"
#include "time_filter.h"

#include <array>
#include <atomic>
#include <cstddef>
#include <cstdint>
#include <memory>
#include <optional>
#include <string>
#include <utility>
#include <vector>

namespace sendspin {

class OutboundRing;
class ProtocolTask;
class SendspinArenaAllocator;

/**
 * @brief Abstract base class for Sendspin connections (server-initiated or client-initiated)
 *
 * This class represents a single connection to a Sendspin server. It manages connection state,
 * time synchronization, message buffering, and the hello handshake. Derived classes implement
 * the actual transport mechanism (e.g., incoming WebSocket server connection or outgoing client).
 */
class SendspinConnection : public std::enable_shared_from_this<SendspinConnection> {
public:
    /// @brief Wires the NoiseTransport frame sinks to this connection's send_transport_frame()
    /// and send_lent_frame().
    SendspinConnection();

    virtual ~SendspinConnection();

    /// @brief Starts the connection (e.g., initiates client connection or begins message
    /// processing)
    virtual void start() = 0;

    /// @brief Sends a goodbye message, then closes the transport, whether or not the send
    /// succeeded. Protocol task only, like every send on a connection.
    /// @param reason The reason for disconnecting (e.g., shutdown, another server).
    virtual void disconnect(SendspinGoodbyeReason reason) = 0;

    /// @brief Closes the underlying transport immediately, without blocking and without ever
    /// joining/stopping the calling thread.
    ///
    /// disconnect() is not safe to call from a transport's own thread on every platform: on host
    /// outbound it ends up calling ix::WebSocket::stop(), and on ESP outbound it ends up calling
    /// esp_websocket_client_stop(), and both of those join/block the transport's own worker
    /// thread. Calling either one from a callback already running on that same thread (e.g. from
    /// fail_inbound(), reached from the transport's own message callback) deadlocks that thread;
    /// on host, joining the current thread additionally throws std::system_error, which escapes
    /// IXWebSocket's thread entry point uncaught and crashes the process via std::terminate().
    ///
    /// Each platform implements this with whichever non-blocking close primitive it already uses
    /// elsewhere for the same hazard; an outbound attempt still connecting may be left to end on
    /// its own where no such primitive can end it (see each outbound override). It reports nothing:
    /// the protocol task reports the loss once the inbound gate is detached or the transport's
    /// close is drained (see SendspinClient::protocol_tick()), and
    /// ConnectionManager::drop_connection() no-ops on a repeat report for a connection it no longer
    /// manages.
    virtual void close_transport_now() = 0;

    /// @brief Checks if the transport connection is established
    /// @return true if connected, false otherwise.
    virtual bool is_connected() const = 0;

    /// @brief Whether this client opened the connection (connect_to()) rather than accepted it
    virtual bool is_outbound() const {
        return false;
    }

    /// @brief Stops this connection's inbound traffic: detaches the inbound gate, so the
    /// transport drops everything it receives from here on and the protocol task drops what it
    /// still takes for the connection. Protocol task (the connection manager when the connection
    /// leaves it, and close_silently()); the transport thread (fail_inbound()); the thread
    /// delivering an accept the command queue refused; or the main loop with the protocol task
    /// joined.
    ///
    /// A message the protocol task is already dispatching is not recalled: the drop that called
    /// this runs between two of the task's messages, and clears the admitted flag first.
    void detach_inbound() {
        this->inbound_gate_.detach();
    }

    /// @brief Whether an application message may still be sent: the transport is connected and
    /// the connection is not detached. A connection the manager releases is detached before its
    /// goodbye while its transport can still read as connected (the ESP server's closed flag is
    /// set by httpd asynchronously), so this keeps a later send off the wire after the goodbye.
    /// Protocol task only.
    bool accepts_app_sends() const {
        return this->is_connected() && !this->inbound_gate_.is_detached();
    }

    /// @brief Checks if the hello handshake has completed successfully
    /// @return true if handshake complete (hello exchange done), false otherwise.
    bool is_handshake_complete() const {
        return this->client_hello_sent_ && this->server_hello_received_;
    }

    /// @brief True once the connection has proven itself: hello handshake complete and the first
    /// server/activate applied.
    ///
    /// This is the nursery's prove gate (see ConnectionManager). is_handshake_complete() already
    /// implies the Noise handshake, since both hellos are encrypted application traffic.
    bool is_operational() const {
        return this->is_handshake_complete() && this->first_activate_received();
    }

    /// @brief Whether this connection currently occupies one of the manager's admitted slots.
    ///
    /// Being operational is not the same as being admitted: a nursery member can complete the
    /// Noise handshake and the hello cycle and still lose arbitration, and every peer that knows
    /// the Sentinel PSK (a spec constant, so effectively any peer on the network) can reach that
    /// state. Admission is where the PSK category is actually checked against the requested
    /// activities (see admission.h), so anything that drives shared client state (the roles)
    /// must gate on this, not on the handshake having succeeded. Which roles an admitted
    /// connection drives is a further question, answered by ConnectionManager::owns_role().
    ///
    /// Written on the protocol task only (see set_admitted()) into the inbound gate, which the
    /// transport reads to route a message and the protocol task reads on the dispatch path.
    /// @return true while this connection is admitted.
    bool is_admitted() const {
        return this->inbound_gate_.is_admitted();
    }

    /// @brief Mark this connection as occupying (or vacating) an admitted slot. Protocol task
    /// only: set and cleared by ConnectionManager as the connection enters and leaves its
    /// admitted array.
    /// @param admitted Whether this connection now occupies an admitted slot.
    void set_admitted(bool admitted) {
        this->inbound_gate_.set_admitted(admitted);
    }

    // ========================================
    // Noise transport
    // ========================================

    /// @brief Initialize the Noise handshake driver for this connection.
    /// Must be called before the WS connection is open. Also retains identity/record_store/
    /// suite_name pointers so a later in-band re-handshake can be driven after this
    /// initial NoiseHandshake object is destroyed.
    /// @param identity     Client static X25519 identity.
    /// @param record_store Record store for psk_id resolution.
    /// @param suite_name   Noise suite name string.
    void init_noise_handshake(const Identity& identity, const RecordStore& record_store,
                              const std::string& suite_name);

    /// @brief Return true once the Noise handshake has completed and transport is encrypted.
    bool is_noise_handshake_complete() const {
        return this->noise_handshake_complete_;
    }

    /// @brief Return true if a Noise handshake driver has been installed on this connection.
    bool has_noise_handshake() const {
        return this->noise_handshake_ != nullptr;
    }

    /// @brief Return the full Noise suite name supplied at init_noise_handshake() (e.g.
    /// "Noise_KKpsk2_25519_ChaChaPoly_SHA256"), or an empty string if none was set.
    /// Used to select the AEAD cipher for pairing.md "Wrapping" (see
    /// platform/crypto.h aead_cipher_name_from_noise_suite()).
    virtual const std::string& get_noise_suite_name() const {
        return this->noise_suite_name_;
    }

    /// @brief Build and send the client/init TEXT frame that starts the Noise handshake.
    /// No-op if no handshake driver is installed; call only once the WebSocket is open.
    void send_noise_client_init();  // implemented in connection.cpp

    /// @brief Handle an in-band re-handshake initiated by the server.
    ///
    /// Called on the protocol task when a decrypted noise/handshake JSON message arrives after
    /// transport is already active (routed here from the dispatch for the "noise/handshake"
    /// message type, which reads msg1 out of the envelope and releases it first). Runs the
    /// deferred-PSK-binding msg1 read with prologue = the current NoiseTransport's
    /// handshake_hash(), then commits the new session via NoiseTransport::send_msg2_and_swap()
    /// (msg2 sent under the OLD session, then the swap; every send runs on this task, so no
    /// encrypt falls between the two).
    ///
    /// Resets first_activate_received_ so the connection waits for the post-swap
    /// server/activate that connection.md "Re-handshake" makes the server's first message under
    /// the new keys; neither hello is re-sent, and the manager's nursery is not involved (the
    /// connection stays current/established throughout, with no drop/reconnect).
    ///
    /// @param msg1_bytes  The re-handshake msg1 Noise bytes (read_noise_handshake_data()).
    /// @return true on success (session swapped; a post-swap server/activate is expected next).
    ///         false on any failure (caller should close the WebSocket).
    bool handle_noise_rehandshake(const std::vector<uint8_t>& msg1_bytes);

    /// @brief Encrypt and send a JSON string as a Noise transport binary frame, through
    /// NoiseTransport::send_json() and settle_noise_send().
    /// @return SsErr::OK on success.
    SsErr send_encrypted_text(const char* json, size_t len) {
        return this->settle_noise_send(this->noise_transport_.send_json(json, len));
    }

    /// @brief std::string overload of send_encrypted_text().
    SsErr send_encrypted_text(const std::string& json) {
        return this->send_encrypted_text(json.data(), json.size());
    }

    /// @brief Send an application-level JSON message.
    ///
    /// This is the single choke point for all post-handshake application JSON.
    /// - If the Noise transport is active, routes through send_encrypted_text() so the
    ///   frame is encrypted.
    /// - If not yet encrypted (pre-handshake), routes through send_text_message().
    ///
    /// All role senders use this method. client/time is the one exception: send_time_message()
    /// calls NoiseTransport::send_json() directly to pass its write hook, and settles its result
    /// the same way. Protocol task only, like every send on a connection. On an ESP outbound
    /// connection the transport send blocks for up to its 10 ms send timeout
    /// (src/esp/client_connection.cpp), once per frame.
    ///
    /// A failure after the encrypt closes the connection (settle_noise_send()); one before it
    /// leaves the connection open. Either way the caller only sees the error.
    /// @return SsErr::OK if queued/sent, error code otherwise.
    SsErr send_app_json(const std::string& json);

    /// @brief Pointer/length form of send_app_json(); encrypts straight from the caller's
    /// buffer, and the pre-handshake text fallback builds the string it needs
    SsErr send_app_json(const char* json, size_t len);

    /// @brief Send an application binary message lying in an outbound ring item, encrypted in
    /// place and lent to the transport until its frame is written (NoiseTransport::
    /// send_binary_lent()). Protocol task only, like every send on a connection.
    ///
    /// Item ownership: OutboundRing, "Lending". Binary application messages exist only under the
    /// Noise transport, so there is no cleartext fallback: before the handshake, or once
    /// accepts_app_sends() is false, the item is returned unsent with INVALID_STATE. A failure
    /// after the encrypt closes the connection (settle_noise_send()), as for send_app_json().
    /// @param message_capacity As OutboundRing::take() reported it.
    /// @param plaintext_len    Bytes of the message, type byte first, at the start of the item's
    ///                         message bytes.
    /// @return SsErr::OK once the transport took the frame, error code otherwise.
    SsErr send_app_binary_lent(OutboundRing& ring, void* item, size_t message_capacity,
                               size_t plaintext_len);

    /// @brief Returns this connection's process-unique instance id
    /// @return A monotonic id assigned at construction, never reused for the lifetime of the
    ///         process. Identifies a connection without a raw pointer, which could ABA-collide
    ///         with a later connection allocated at the same address: an inbound ring item's
    ///         connection_id (its low 32 bits) and ConnectionManager's published primary id.
    ///         Ids start at 1, so 0 is a safe "no connection" sentinel.
    uint64_t get_instance_id() const {
        return this->instance_id;
    }

    /// @brief Records the accept/provisional timestamp (microseconds from platform_time_us()).
    /// Protocol task only: stamped when the connection enters the nursery (an accept or a
    /// connect_to()) and again when a re-handshake or a pairing-finalize ack starts a re-proving
    /// window; read by the establish and re-prove watchdogs.
    void set_provisional_time_us(int64_t t) {
        this->provisional_time_us_ = t;
    }

    /// @brief Returns the accept/provisional timestamp, or 0 if not yet set.
    int64_t get_provisional_time_us() const {
        return this->provisional_time_us_;
    }

    /// @brief Returns the low 32 bits of the last complete inbound message's arrival time.
    uint32_t get_last_receive_time_us() const {
        return this->last_receive_time_us_.load(std::memory_order_relaxed);
    }

    /// @brief Sends a text message to the peer.
    /// @param message The message string to send.
    /// @return SsErr::OK if queued/sent, error code otherwise.
    virtual SsErr send_text_message(const std::string& message) = 0;

    /// @brief Sends a client/time message and records it as the frame in flight (see
    /// time_frame_tag_). Needs the Noise transport, which is_operational() implies. Protocol task
    /// only.
    /// @return The client_transmitted the frame carries, or 0 if the message was not queued/sent.
    int64_t send_time_message();

    /// @brief Claims the client/time frame in flight for a server/time reply echoing
    /// `client_transmitted`, matching on its low 32 bits; the claim retires the frame, so at most
    /// one reply per frame succeeds. Protocol task.
    /// @return When the frame was handed to the socket, never earlier than the echo nor later
    ///         than the write, or nullopt if no frame in flight carries that value.
    std::optional<int64_t> claim_time_frame(int64_t client_transmitted);

    /// @brief Retires the client/time frame in flight, so a late reply to it is not claimed.
    /// Protocol task (the burst's response timeout).
    void cancel_time_frame() {
        this->time_frame_tag_ = 0;
    }

    /// @brief Sends a binary WebSocket frame to the peer.
    /// @param data   Pointer to the binary payload bytes.
    /// @param len    Number of bytes to send.
    /// @return SsErr::OK if queued/sent, error code otherwise.
    virtual SsErr send_binary_message(const uint8_t* data, size_t len) = 0;

    /// @brief Sends a goodbye message.
    /// @param reason The reason for disconnecting.
    /// @return SsErr::OK if queued/sent, error code otherwise.
    SsErr send_goodbye_reason(SendspinGoodbyeReason reason);

    /// @brief Closes the connection without sending any application-level message.
    ///
    /// connection.md "Failure Handling": handshake-phase failures, an AEAD failure once in
    /// transport mode, and malformed fragment sequences all close the WebSocket without sending a
    /// client/goodbye (or any other application-level message). Called on the protocol task, from
    /// the receive path and settle_noise_send(), so this routes to close_transport_now()
    /// (non-blocking on every platform) instead of disconnect() (which can block on a transport
    /// join; see close_transport_now()).
    /// The inbound gate is detached first, so nothing the peer sent after the failure is processed,
    /// and the protocol task reports the loss.
    void close_silently(SendspinGoodbyeReason /*reason*/) {
        this->detach_inbound();
        this->close_transport_now();
    }

    // ========================================
    // Server information accessors
    // ========================================

    /// @brief Gets the server ID from the Noise handshake result (set at COMPLETE; empty until
    /// then when a Noise handshake is installed on this connection).
    const std::string& get_server_id() const {
        return this->server_information_.server_id;
    }

    /// @brief Gets the server information from the server/hello message (empty until received).
    const ServerInformationObject& get_server_information() const {
        return this->server_information_;
    }

    /// @brief Whether server/hello's source@v1 support object lists `codec` among the codecs the
    /// server accepts in client-stream/start (roles/source/v1.md "server/hello source@v1 support
    /// object"). False for every codec before server/hello, or when it carried no valid object.
    /// Protocol task only.
    bool server_accepts_source_codec(SendspinCodecFormat codec) const {
        return (this->server_source_codecs_ & source_codec_bit(codec)) != 0;
    }

    // ========================================
    // server/activate state accessors
    // ========================================

    /// @brief Returns the current activity set declared by server/activate. Protocol task only.
    const std::vector<SendspinActivity>& get_activities() const {
        return this->activities_;
    }

    /// @brief Returns the sticky active_roles set declared by server/activate. Protocol task only.
    const std::vector<std::string>& get_active_roles() const {
        return this->active_roles_;
    }

    /// @brief Returns true once the first server/activate has been received and applied.
    /// Reset by a re-handshake (handle_noise_rehandshake()) and a pairing-finalize ack. Protocol
    /// task only.
    bool first_activate_received() const {
        return this->first_activate_received_;
    }

    /// @brief Returns the PSK category resolved by the Noise handshake (set at COMPLETE, or
    /// re-handshake). Defaults to SENTINEL when no Noise handshake has completed (e.g. when
    /// encryption is not required on this connection).
    PskCategory get_psk_category() const {
        return this->psk_category_;
    }

    /// @brief Returns the psk_id of the matched PSK (set at COMPLETE and rewritten at every
    /// in-band re-handshake; empty for Sentinel or when no Noise handshake has completed).
    /// Protocol task only, the thread that writes it.
    const std::string& get_psk_id() const {
        return this->psk_id_;
    }

    /// @brief Returns the pairing method the server selected (from the pairing object of the
    /// last pairing server/activate). Used by the pairing flow.
    const std::optional<SendspinPairMethod>& get_pairing_method() const {
        return this->pairing_method_;
    }

    /// @brief Returns the emission format from the pairing object of the last pairing
    /// server/activate (present only for dynamic_pairing_code, validated on receipt).
    const std::optional<SendspinPairingCodeFormat>& get_pairing_format() const {
        return this->pairing_format_;
    }

    /// @brief Returns the count of pairing server/activate messages received since the last
    /// Noise handshake (or re-handshake). Protocol task only.
    uint32_t get_pairing_index() const {
        return this->pairing_index_;
    }

    /// @brief Increments the pairing-server/activate counter and returns the new value.
    /// Call exactly once per pairing server/activate received, on the protocol task.
    uint32_t bump_pairing_index() {
        return ++this->pairing_index_;
    }

    /// @brief Resets the pairing-server/activate counter to zero.
    /// Call on the protocol task when a Noise handshake (initial or re-handshake) completes.
    void reset_pairing_index() {
        this->pairing_index_ = 0;
    }

    // ========================================
    // Pairing-code session state (dynamic and static)
    // ========================================

    /// @brief Steps in the pairing-code PAKE state machine (protocol task only).
    /// Shared by both code-based methods; AWAIT_SERVER_PAIR_INIT is exclusive to the dynamic
    /// pairing code (see PairingSession::method), the remaining steps (AWAIT_SERVER_PAIR_AUTH
    /// onward) are common to both.
    enum class PairingStep : uint8_t {
        IDLE,                        ///< No pairing-code session active.
        AWAIT_PAIRING_WINDOW,        ///< Gesture-gated attempt: client/pair-pending was sent and
                                     ///< client/pair-init waits for a pairing window to open.
        AWAIT_SERVER_PAIR_INIT,      ///< dynamic pairing code only: sent client/pair-init
                                     ///< (commit_B) or client/pair-retry; waiting for the
                                     ///< round's server/pair-init.
        AWAIT_SERVER_PAIR_AUTH,      ///< CPace RESPONDER started; waiting for server/pair-auth.
        AWAIT_SERVER_PAIR_CONFIRM,   ///< Sent client/pair-auth and derived; waiting for
                                     ///< server/pair-confirm.
        AWAIT_SERVER_PAIR_FINALIZE,  ///< Sent client/pair-finalize; waiting for
                                     ///< server/pair-finalize.
    };

    /// @brief All pairing-code session state (protocol task only).
    /// Shared by both code-based methods; `method` selects the gating policy, whether the
    /// attempt runs further rounds, and the pair-confirm wire shape.
    struct PairingSession {
        CPace cpace;
        std::array<uint8_t, 32> nonce_b{};
        /// nonce_A from the attempt's first server/pair-init. pairing.md "Rounds" keeps the
        /// binding values, and so the pairing code, unchanged across an attempt's rounds, and a
        /// later round's server/pair-init carries no nonce.
        std::array<uint8_t, 32> nonce_a{};
        std::array<uint8_t, 32> handshake_hash{};
        /// The pairing code as CPace consumes it (PRS, pairing.md "PAKE"): the six or eight ASCII
        /// digits, or the 24 raw bytes of the qr_code emission format.
        std::vector<uint8_t> prs;
        /// platform_time_us() deadline for the whole attempt; the Pairing PSK Flow arms it too.
        int64_t attempt_deadline_us{0};
        /// pairing_index captured when this attempt entered pairing (see
        /// SendspinConnection::bump_pairing_index()). Sent on client/pair-init and reused
        /// verbatim for the CPace sid, so both stay consistent even if the connection's running
        /// counter advances again before the PAKE steps run.
        uint32_t pairing_index{0};
        /// Number of the round within this attempt, 1 for the first, feeding the CPace sid
        /// (pairing.md "PAKE"). 0 until the attempt's first round begins; always 1 in the Static
        /// Pairing Code Flow, which runs a single round.
        uint32_t round{0};
        SendspinPairMethod method{SendspinPairMethod::DYNAMIC_PAIRING_CODE};
        /// Emission format the server selected; meaningful for DYNAMIC_PAIRING_CODE only.
        SendspinPairingCodeFormat format{SendspinPairingCodeFormat::DIGITS};
        PairingStep step{PairingStep::IDLE};
        bool code_emitted{false};  ///< True once a code was surfaced via on_display_pairing_code.
        bool window_shown{false};  ///< True once on_open_pairing_window was surfaced.

        PairingSession() = default;

        /// @brief Wipes the pairing code and both binding nonces on destruction. `prs` lives on
        /// the heap, so releasing it unwiped would leave the code in freed memory.
        ~PairingSession() {
            secure_zero_container(this->nonce_b);
            secure_zero_container(this->nonce_a);
            if (!this->prs.empty()) {
                secure_zero(this->prs.data(), this->prs.size());
            }
        }

        /// Reached only by reference (pairing_session()); a copy would carry the code and the
        /// nonces into a second buffer nothing wipes.
        PairingSession(const PairingSession&) = delete;
        PairingSession& operator=(const PairingSession&) = delete;
    };

    /// @brief Return the current pairing-code session state. Protocol task only.
    PairingSession& pairing_session() {
        return this->pairing_session_;
    }

    /// @brief Return the Noise handshake hash, or nullopt if no active transport session.
    /// Protocol task only, the thread that owns the session.
    ///
    /// Virtual so a fake connection can report a canned hash without an active Noise session;
    /// production connections keep the same implementation via dynamic dispatch.
    virtual std::optional<std::array<uint8_t, 32>> get_noise_handshake_hash() const {
        return this->noise_transport_.handshake_hash();
    }

    // ========================================
    // Pairing state
    // ========================================

    /// @brief Returns true if a pairing exchange is in progress on this connection.
    /// Set when entering pairing, cleared on abort and by handle_noise_rehandshake(). Protocol
    /// task only.
    bool is_pairing_in_progress() const {
        return this->pairing_in_progress_;
    }

    /// @brief Returns true if the server has acked server/pair-finalize but no fresh
    /// server/activate has arrived yet, i.e. get_activities() still reports the stale
    /// pre-finalize [PAIRING] set for an exchange that is already complete.
    bool is_pairing_finalized() const {
        return this->pairing_finalized_;
    }

    /// @brief Sets the pairing-in-progress flag. Protocol task only.
    void set_pairing_in_progress(bool value) {
        this->pairing_in_progress_ = value;
    }

    /// @brief Stores the pending pairing record, committed if/when the server acks with
    /// server/pair-finalize (the commit happens in that handler, before the server's re-handshake
    /// msg1, the next message, resolves the new PSK against the RecordStore). Latest wins.
    /// Protocol task only.
    void set_pending_pairing_record(SendspinPairingRecord record) {
        this->pending_pairing_record_ = std::move(record);
    }

    /// @brief Returns and clears the pending pairing record. A returned value is a record to
    /// store; nullopt means there was no pending pairing. Protocol task only.
    std::optional<SendspinPairingRecord> take_pending_pairing_record() {
        return std::exchange(this->pending_pairing_record_, std::nullopt);
    }

    /// @brief Clears all pairing state on this connection. Called on abort or leftover-activate.
    /// Protocol task only.
    void clear_pairing_state() {
        this->pairing_in_progress_ = false;
        this->pairing_finalized_ = false;
        this->pending_pairing_record_.reset();
        // Destroyed and rebuilt in place rather than assigned over: assignment would overwrite
        // the secrets instead of wiping them, and would never run ~PairingSession or ~CPace.
        std::destroy_at(&this->pairing_session_);
        std::construct_at(&this->pairing_session_);
    }

    /// @brief Clears first_activate_received_ and re-arms the provisional timeout after the
    /// server acks server/pair-finalize, so the re-proving watchdog bounds the rekey that
    /// follows. Also sets pairing_finalized_, which stops admission shielding this connection as
    /// an in-flight pairing. Protocol task; defined in connection.cpp.
    void note_pairing_finalize_ack();

    /// @brief Returns true if `role` is active on this connection, judged on the exact versioned
    /// name this library implements.
    ///
    /// The one activation test in the library: the receive gate, the send gate, the client/state
    /// role objects and role removal all read the mask active_role_mask() builds with the same
    /// exact-version test (role_in()) role removal applies, so none of them can disagree. The
    /// mask is rebuilt by apply_server_activate(). Protocol task only.
    bool is_role_active(SendspinRole role) const {
        return (this->active_role_mask_ & role_mask_bit(role)) != 0;
    }

    /// @brief The active roles as a bitmask of role_mask_bit() values. Protocol task only.
    uint16_t get_active_role_mask() const {
        return this->active_role_mask_;
    }

    /// @brief Returns true if the given activity is in the current activity set.
    bool has_activity(SendspinActivity activity) const {
        for (const auto& a : this->activities_) {
            if (a == activity) {
                return true;
            }
        }
        return false;
    }

    /// @brief Applies a server/activate update: stores activities and updates active_roles
    /// (sticky: a nullopt active_roles in the message leaves the prior set unchanged). Sets
    /// first_activate_received_ on every call (including after a re-handshake reset it).
    ///
    /// Protocol task only: ConnectionManager applies the activation as its message is processed.
    /// @param pairing_method Method from the activation's pairing object (nullopt when absent).
    ///                       Ignored (stored as nullopt) unless `activities` includes PAIRING
    ///                       (spec: "A client ignores this field when activities does not
    ///                       include 'pairing'").
    /// @param pairing_format Emission format from the pairing object (dynamic_pairing_code
    ///                       only); stored under the same PAIRING-activity condition.
    void apply_server_activate(const std::vector<SendspinActivity>& activities,
                               const std::optional<std::vector<std::string>>& active_roles,
                               const std::optional<SendspinPairMethod>& pairing_method,
                               const std::optional<SendspinPairingCodeFormat>& pairing_format) {
        this->activities_ = activities;
        if (active_roles.has_value()) {
            this->active_roles_ = active_roles.value();
        }
        // Rebuilt on every activation, sticky set included, so the mask cannot drift from
        // active_roles_.
        this->active_role_mask_ = active_role_mask(this->active_roles_);
        bool has_pairing = false;
        for (const auto& a : activities) {
            if (a == SendspinActivity::PAIRING) {
                has_pairing = true;
                break;
            }
        }
        this->pairing_method_ = has_pairing ? pairing_method : std::nullopt;
        this->pairing_format_ = has_pairing ? pairing_format : std::nullopt;
        this->first_activate_received_ = true;
        // activities_ is fresh again, so the post-finalize staleness window is over.
        this->pairing_finalized_ = false;
    }

    /// @brief Gets the time filter for this connection
    /// @return Pointer to the time filter, or nullptr if not initialized.
    SendspinTimeFilter* get_time_filter() {
        return this->time_filter_.get();
    }

    /// @brief Returns a shared reference to this connection's time filter, for ConnectionManager's
    /// time filter slot (see ConnectionManager::time_filter())
    /// @return The time filter, or nullptr if not initialized.
    std::shared_ptr<SendspinTimeFilter> get_shared_time_filter() const {
        return this->time_filter_;
    }

    /// @brief Returns true if the time filter has received at least one measurement
    /// @return True if time synchronization has started, false otherwise.
    bool is_time_synced() const {
        if (this->time_filter_ == nullptr) {
            return false;
        }
        return this->time_filter_->has_update();
    }

    /// @brief Initializes the time filter with Kalman parameters. Call once, before the connection
    /// can enter a manager slot: ConnectionManager's time filter slot copies this filter at install
    /// and never refreshes it.
    void init_time_filter();

    /// @brief This connection's time burst, which feeds its time filter. Protocol task only: the
    /// burst sends its client/time frames there and is fed the server/time replies there.
    SendspinTimeBurst& time_burst() {
        return this->time_burst_;
    }

    /// @brief Wakes the protocol task this connection was attached to (attach_inbound()), if any.
    /// Any thread: an outbound transport calls it to report its WebSocket upgrade.
    void wake_protocol_task() const;

    // ========================================
    // Configuration setters (called by hub after receiving server/hello message)
    // ========================================

    /// @brief Sets the client hello sent flag
    /// @param sent True if client hello message has been sent.
    /// @note Called by hub to track handshake state.
    void set_client_hello_sent(bool sent) {
        this->client_hello_sent_ = sent;
    }

    /// @brief Called by process_inbound_message() on the protocol task to drive the noise
    /// handshake for an incoming text frame received before transport mode is established. No-op
    /// if no handshake driver is installed on this connection.
    /// @param text The raw text content of the received TEXT frame.
    ///
    /// On HandshakeFrameResult::ABORT (spec Failure Handling: malformed cleartext message,
    /// unsupported version, unknown suite, psk_id lookup miss, or a msg1 auth failure), this
    /// closes the connection itself via close_silently(); the caller has nothing left to do.
    void handle_noise_handshake_text(const std::string& text);

    /// @brief Returns whether this connection has successfully sent its client/hello.
    /// @return true once a client/hello send has completed on this connection.
    bool has_client_hello_sent() const {
        return this->client_hello_sent_;
    }

    /// @brief Marks that the WebSocket upgrade completed
    /// @note Distinct from is_connected(): on ESP the socket is accepted before any WebSocket
    ///       handshake, so this flag signals that the peer spoke WebSocket.
    void mark_ws_upgraded() {
        this->ws_upgraded_.store(true, std::memory_order_release);
    }

    /// @brief Returns whether the WebSocket upgrade completed.
    bool is_ws_upgraded() const {
        return this->ws_upgraded_.load(std::memory_order_acquire);
    }

    /// @brief Sets the server_id and PSK metadata from the Noise handshake result.
    /// Called at COMPLETE in connection.cpp.
    void set_noise_handshake_result(const std::string& server_id, PskCategory psk_category,
                                    const std::string& psk_id) {
        this->server_information_.server_id = server_id;
        this->psk_category_ = psk_category;
        this->psk_id_ = psk_id;
    }

    /// @brief Sets the server hello received flag
    /// @param received True if server hello message has been received.
    /// @note Called by hub when SERVER_HELLO is processed.
    void set_server_hello_received(bool received) {
        this->server_hello_received_ = received;
    }

    /// @brief Records the codecs server/hello's source@v1 support object lists
    /// (ServerHelloMessage::source_codecs); nullopt for a hello without a valid object. Protocol
    /// task only, from the server/hello dispatch.
    void set_server_source_codecs(std::optional<uint8_t> codecs) {
        this->server_source_codecs_ = codecs.value_or(0);
    }

    /// @brief Sets the server information (from server/hello message)
    /// @param info The ServerInformationObject received during the hello handshake.
    /// @note Called by hub after receiving server/hello message.
    void set_server_information(ServerInformationObject info) {
        this->server_information_ = std::move(info);
    }

    // ========================================
    // Inbound messages (protocol task side)
    // ========================================

    /// @brief The admitted flag, pre-admission hand-off, in-flight count, detached flag and
    /// out-of-band close flag this connection's transport shares with the protocol task (see
    /// InboundGate for each member's threads)
    InboundGate& inbound_gate() {
        return this->inbound_gate_;
    }

    /// @brief Gives the transport the shared inbound ring an admitted connection receives into
    /// and the protocol task it wakes. Called once, by the connection manager, before the
    /// transport can deliver a message: on the transport's own delivery thread for an inbound
    /// connection (ahead of its first frame, before the connection is handed to the protocol
    /// task), before start() for an outbound one. A connection never given one drops everything
    /// it receives.
    void attach_inbound(InboundRing* ring, ProtocolTask* task) {
        this->inbound_ring_ = ring;
        this->inbound_task_ = task;
    }

    /// @brief What process_inbound_message() leaves its caller to dispatch
    enum class InboundDispatch : uint8_t {
        NONE,    ///< Nothing to dispatch
        JSON,    ///< `complete` holds a JSON body, without the type byte
        BINARY,  ///< `complete` holds a binary role message, type byte first
    };

    /// @brief Runs one received message through the receive path: the Noise handshake driver for
    /// a text frame; decrypt in place and Noise-level reassembly for a binary one. Protocol task
    /// only.
    ///
    /// A single-frame message moves to @p complete with its ring item, so the binary handler can
    /// keep it; a reassembled one lives in the Noise reassembly buffer, and the item stays in
    /// @p message. A failure closes the connection silently (connection.md "Failure Handling"),
    /// which detaches the inbound gate. The caller returns whichever of `message.item` and
    /// `complete.item` is still set after dispatch, NONE included.
    /// @param message The message as received.
    /// @param complete Set to the complete message, if one exists; a single-frame empty JSON body
    ///        sets it and returns NONE.
    /// @return What to dispatch @p complete as.
    InboundDispatch process_inbound_message(InboundMessage& message, InboundMessage& complete);

    /// @brief Describes the message the transport published to the fallback buffer (a
    /// pre-admission message, or an admitted connection's message longer than the ring takes),
    /// once it is next in this connection's order. Protocol task only; hand the buffer back with
    /// consume_pending_message() once done with it.
    /// @return false when no message is pending, or while a ring item the connection wrote before
    ///         it is still untaken (InboundGate::in_flight(); see InboundGate "Ordering").
    bool pending_message(InboundMessage& out);

    /// @brief Hands the fallback buffer back to the transport. Protocol task only.
    void consume_pending_message() {
        this->inbound_gate_.consume_pending_message();
    }

    /// @brief Records that the transport closed and wakes the protocol task, which honours the
    /// close once the connection's queued messages are drained (InboundGate::close_ready()).
    /// Transport thread, after its last message is completed or published.
    void notify_transport_closed();

    // ========================================
    // Initialization setters (called by hub before start)
    // ========================================

    /// @brief Sets the memory location preference for the fallback buffer the transport
    /// assembles pre-admission and multi-frame messages in
    /// @param location PREFER_EXTERNAL (SPIRAM-first) or PREFER_INTERNAL (internal-RAM-first).
    /// @note Must be called before the first received frame; takes effect on the next allocation.
    void set_inbound_buffer_location(MemoryLocation location) {
        this->fallback_location_ = location;
    }

    /// @brief Sets the memory location preference for the Noise transport's buffers: fragment
    /// reassembly, the fragmentation frame buffer, and the reused send buffer.
    /// @param location PREFER_EXTERNAL (SPIRAM-first) or PREFER_INTERNAL (internal-RAM-first).
    /// @note Must be called before the handshake completes; takes effect on the next allocation.
    void set_noise_buffer_location(MemoryLocation location) {
        this->noise_transport_.set_buffer_location(location);
    }

    /// @brief Sets the client's JSON arena, which every JSON document this connection parses or
    /// builds is allocated from: the Noise handshake and re-handshake frames and the goodbye.
    /// @param arena The client's arena; outlives the connection.
    /// @note Must be called before the connection reaches the protocol task, as the manager does
    /// alongside the buffer locations.
    void set_json_arena(SendspinArenaAllocator& arena) {
        this->json_arena_ = &arena;
    }

protected:
    // ========================================
    // Transport frames
    // ========================================

    /// @brief Sends one Noise transport frame, running `before_write` (if set) immediately
    /// before the frame is handed to the socket
    ///
    /// The default runs the hook and then calls send_binary_message(), which suits a transport
    /// that writes synchronously. A transport that queues its writes overrides this to run the
    /// hook where the write happens.
    virtual SsErr send_transport_frame(const uint8_t* data, size_t len,
                                       const NoiseTransport::FrameWriteHook& before_write);

    /// @brief Sends the first `len` message bytes of an outbound ring item as a binary WebSocket
    /// frame; the lent frame sink (item ownership: OutboundRing, "Lending").
    ///
    /// The default writes through send_binary_message() and returns the item after it, which
    /// suits a transport done with the bytes when its send returns (the host transports, the ESP
    /// client); a transport that queues writes overrides it to keep the item with the queued
    /// write.
    virtual SsErr send_lent_frame(OutboundRing& ring, void* item, size_t len);

    // ========================================
    // Noise transport helpers (connection.cpp)
    // ========================================

    /// @brief Settles the result of a send through the Noise transport: once a send failed after
    /// its encrypt (NoiseTransport::is_send_desynced()), the connection is closed without a
    /// goodbye, as an AEAD failure on the receive path is (connection.md "Failure Handling"), and
    /// the protocol task drops it in the same tick through the detached gate. A connection
    /// already detached is left to whoever detached it, so a goodbye that fails during a release
    /// closes nothing twice; one whose transport is no longer connected is left to that
    /// transport's close report, which the protocol task honours after the messages received
    /// before it (InboundGate::close_ready()); until then every send is refused before its
    /// encrypt. Protocol task only.
    /// @param err The send's result, returned unchanged.
    SsErr settle_noise_send(SsErr err);

    /// @brief Classifies a complete transport message: type 0 as JSON, stripping its type byte,
    /// all others as binary role messages. Protocol task only.
    static InboundDispatch classify_complete_noise_message(InboundMessage& complete);

    // ========================================
    // Inbound messages (transport side)
    // ========================================

    /// @brief What the transport does with a message begin_inbound_message() or
    /// begin_inbound_fragment() was asked about
    enum class InboundRoute : uint8_t {
        RECEIVE,  ///< Receive the bytes into InboundTarget::data, then end the message
        /// Read and discard the bytes (a detached or unattached connection, or the rest of a
        /// multi-frame message one of those began)
        DROP,
        CLOSE,  ///< Close the connection: fail_inbound() has already run
    };

    /// @brief Where the transport puts the bytes it is about to receive
    struct InboundTarget {
        uint8_t* data{nullptr};
        InboundRoute route{InboundRoute::DROP};
    };

    /// @brief Starts a complete single-frame WebSocket message of `len` bytes, routed by
    /// route_inbound_message(). Transport thread. A message arriving while a multi-frame message
    /// is being assembled closes the connection (RFC 6455 section 5.4).
    /// @param len Message length in bytes.
    /// @param is_text Whether the message arrived in a text frame.
    /// @param receive_time_us platform_time_us() when the transport received it.
    InboundTarget begin_inbound_message(size_t len, bool is_text, int64_t receive_time_us);

    /// @brief Ends the message begin_inbound_message() routed to RECEIVE: publishes it to the
    /// protocol task and wakes it. Transport thread.
    /// @param received false when the receive failed after the start: a ring item is completed
    ///        as InboundKind::DISCARD, a fallback message is not published.
    void end_inbound_message(bool received);

    /// @brief Starts receiving `len` more bytes of a multi-frame WebSocket message (a message
    /// sent as a frame plus continuation frames), assembled in the fallback buffer. Transport
    /// thread.
    ///
    /// The rare path: a conforming peer sends every message in one frame, since a Noise frame is
    /// at most INBOUND_MAX_MESSAGE_BYTES. The completed message costs one copy more than the
    /// single-frame path: from the fallback buffer into a ring item for an admitted connection.
    /// The routes and caps are those of begin_inbound_message(), applied to the running total.
    /// Bytes that are not `first` with no message being assembled (a stray continuation frame),
    /// or `first` bytes while one is, close the connection (RFC 6455 section 5.4) and never touch
    /// the fallback buffer.
    /// @param len Bytes this frame (or this chunk of it) carries.
    /// @param first Whether these are the first bytes of the message.
    /// @param is_text Whether the message's first frame is a text frame; read when `first`.
    /// @param receive_time_us platform_time_us() when the transport received these bytes.
    InboundTarget begin_inbound_fragment(size_t len, bool first, bool is_text,
                                         int64_t receive_time_us);

    /// @brief Commits the bytes begin_inbound_fragment() routed to RECEIVE and, on the message's
    /// last bytes, publishes the assembled message. Transport thread.
    /// @param len Bytes received into the target.
    /// @param last Whether these complete the message.
    void end_inbound_fragment(size_t len, bool last);

    /// @brief Gives up on the message being received, for a transport that stops part-way
    /// through one (a message delivered in several chunks): a ring item is completed as
    /// InboundKind::DISCARD, since every acquired item must be completed, and a message being
    /// assembled or received into the fallback buffer is not published. Transport thread, or the
    /// thread that joined it.
    void abandon_inbound_message();

    /// @brief Chooses the destination for a complete message of `len` bytes. Transport thread.
    ///
    /// An admitted connection receives straight into a ring item it acquires here, waiting up to
    /// INBOUND_ACQUIRE_TIMEOUT_MS for room, and is closed with a warning when there is none,
    /// since a frame never decrypted leaves the Noise receive nonce behind; a message longer than
    /// INBOUND_MAX_MESSAGE_BYTES closes the connection, since no conforming peer sends one (the
    /// Noise layer fragments), and one longer than the ring takes
    /// (InboundRing::max_message_bytes()) goes to route_to_fallback(), as does every message of
    /// an unadmitted connection; one over InboundGate::PRE_ADMISSION_MESSAGE_BYTES first waits for
    /// the message still pending to be consumed (it may be the one that admits the connection,
    /// wait_until_writable()) and closes the connection if it is still unadmitted then. A
    /// detached or unattached connection drops everything.
    InboundTarget route_inbound_message(size_t len, InboundKind kind, uint32_t stamp);

    /// @brief Routes a complete message of `len` bytes to the fallback buffer, in order with the
    /// connection's ring items, once the protocol task has consumed the previous one, allocating
    /// the buffer when it is shorter. Transport thread.
    ///
    /// An unadmitted connection waits up to InboundGate::WRITABLE_WAIT_MS and is closed when the
    /// wait times out (wait_until_writable()). An admitted connection's message, one longer than
    /// the ring takes, waits only INBOUND_ACQUIRE_TIMEOUT_MS, as a ring acquire does, and then
    /// closes the connection with a warning, for the same reason a full ring does (a detached
    /// connection's message is dropped instead).
    /// @return RECEIVE into the buffer; DROP or CLOSE as above; an allocation failure closes.
    InboundTarget route_to_fallback(size_t len, InboundKind kind, uint32_t stamp, bool admitted);

    /// @brief InboundGate::wait_until_writable() bounded by InboundGate::WRITABLE_WAIT_MS; a
    /// timeout on a connection that is not detached closes it through fail_inbound(). Transport
    /// thread.
    /// @return RECEIVE when the transport may write, DROP when the connection is detached, CLOSE
    ///         when the wait timed out and closed it.
    InboundRoute wait_until_writable();

    /// @brief Stamps last_receive_time_us_ with the current time. Transport thread, when a
    /// message is complete: a peer that stalls part-way through one stops refreshing the stamp,
    /// so the liveness watchdog bounds how long it can hold a ring item uncompleted.
    void note_message_completed();

    /// @brief Closes the connection over a receive failure the transport detected (an oversize
    /// message, a stalled protocol task, an allocation failure): detaches the inbound gate, closes
    /// the transport without blocking, and wakes the protocol task, which reports the loss.
    /// Transport thread.
    void fail_inbound();

    /// @brief Returns the next process-unique connection id (starts at 1, monotonic).
    /// @note The function-local atomic gives thread-safe, ordering-independent uniqueness.
    static uint64_t next_instance_id() {
        static std::atomic<uint64_t> counter{1};
        return counter.fetch_add(1, std::memory_order_relaxed);
    }

    // ========================================
    // Noise transport state
    // ========================================

    // Struct fields

    /// The fallback buffer: a pre-admission message, an admitted connection's message longer than
    /// the ring takes, or a multi-frame message being assembled (see begin_inbound_fragment()).
    /// Written by the transport thread; read by the protocol task
    /// only while a message is pending (InboundGate::has_pending_message()), during which the
    /// transport does not touch it. Allocated on demand up to the cap in force
    /// (InboundGate::PRE_ADMISSION_MESSAGE_BYTES unadmitted, INBOUND_MAX_MESSAGE_BYTES admitted)
    /// and released by the transport once an admitted connection receives a single-frame message
    /// into the ring, so an admitted connection holds none in steady state.
    PlatformBuffer fallback_buf_;

    /// Shared with the protocol task without a lock; InboundGate states each member's writer and
    /// reader threads.
    InboundGate inbound_gate_;

    /// Encrypted transport: owns the cipher session, outbound fragmentation, and inbound
    /// reassembly. Protocol task only; see noise_transport.h.
    NoiseTransport noise_transport_;

    /// Server identity: name from server/hello, server_id from the Noise handshake result
    /// (or re-handshake; unchanged across a re-handshake since it is the same server). Protocol
    /// task only; ConnectionManager publishes a copy to other threads at admission.
    ServerInformationObject server_information_{};

    /// The pairing record staged by the attempt in flight, committed by the
    /// server/pair-finalize handler (nullopt = nothing to store). Protocol task only.
    std::optional<SendspinPairingRecord> pending_pairing_record_{};

    /// Sends this connection's client/time bursts and feeds its time filter. Protocol task only.
    SendspinTimeBurst time_burst_{};

    // Pointer fields

    /// Time synchronization filter (Kalman-based). Shared so role threads can hold it without
    /// holding this connection (ConnectionManager::time_filter()).
    std::shared_ptr<SendspinTimeFilter> time_filter_;

    /// Noise handshake driver (active from connection open until handshake complete).
    std::unique_ptr<NoiseHandshake> noise_handshake_;

    /// Retained for re-handshake: pointer to the client identity supplied at
    /// init_noise_handshake(). Lifetime is owned by SendspinClient (outlives connections).
    const Identity* noise_identity_{nullptr};

    /// Retained for re-handshake: pointer to the RecordStore supplied at
    /// init_noise_handshake(). Lifetime is owned by SendspinClient (outlives connections).
    const RecordStore* noise_record_store_{nullptr};

    /// The client's JSON arena, from set_json_arena(). Written once before the connection reaches
    /// the protocol task (on the transport's delivery thread for an inbound one, published to the
    /// task by the accept command); read on the protocol task only, which the arena belongs to.
    SendspinArenaAllocator* json_arena_{nullptr};

    /// The shared inbound ring and the protocol task, from attach_inbound(). Written once before
    /// the transport can deliver; read by the transport thread. Owned by SendspinClient: the
    /// task for the client's life, the ring for one start()/stop() run, and every transport that
    /// could write into it is joined before stop() releases it.
    InboundRing* inbound_ring_{nullptr};
    ProtocolTask* inbound_task_{nullptr};

    /// The ring item the transport is receiving the current message into, or nullptr. Transport
    /// thread only.
    void* inbound_item_{nullptr};

    // 64-bit fields

    /// Monotonic timestamp (platform_time_us()) when this connection entered the nursery, or
    /// began its latest re-proving window. Protocol task only. 0 = not yet set.
    int64_t provisional_time_us_{0};

    /// Process-unique connection identity (see get_instance_id()). Assigned once at construction.
    const uint64_t instance_id{next_instance_id()};

    // size_t fields

    /// Bytes of fallback_buf_ holding the message being assembled or the pending message. Written
    /// by the transport thread; read by the protocol task while a message is pending.
    size_t fallback_len_{0};

    // 32-bit fields

    /// Low 32 bits of platform_time_us() at the last complete inbound message, or at the start of
    /// one the transport drops (see note_message_completed()). Atomic because it is written on
    /// the transport thread and read by the protocol task's liveness check; 32 bits because it is
    /// stored per message and a 64-bit atomic is not lock-free on the ESP32 family.
    std::atomic<uint32_t> last_receive_time_us_{0};

    /// Low 32 bits of the receive time of the message in fallback_buf_. Same threads as
    /// fallback_len_.
    uint32_t fallback_receive_time_us_{0};

    // String fields

    /// Retained for re-handshake: Noise suite name supplied at init_noise_handshake().
    std::string noise_suite_name_{};

    /// psk_id of the PSK matched by the Noise handshake (empty for Sentinel, or when no
    /// Noise handshake has completed). Written at handshake COMPLETE and at every in-band
    /// re-handshake. Protocol task only.
    std::string psk_id_{};

    // Vector fields

    /// Activities declared by server/activate (empty until the first activate is applied).
    /// Protocol task only: see apply_server_activate().
    std::vector<SendspinActivity> activities_{};

    /// Active roles declared by server/activate (sticky: preserved across activates that omit
    /// the field). Empty until the first activate that includes active_roles. Protocol task only.
    std::vector<std::string> active_roles_{};

    /// Pairing method from the pairing object of the last pairing server/activate; nullopt
    /// outside a pairing activation. Read by the pairing flow. Protocol task only.
    std::optional<SendspinPairMethod> pairing_method_{};

    /// Emission format from the same pairing object (dynamic_pairing_code only); nullopt outside
    /// a pairing activation. Protocol task only.
    std::optional<SendspinPairingCodeFormat> pairing_format_{};

    // ========================================
    // Pairing state members
    // ========================================

    /// Pairing-code PAKE session. Protocol task only.
    PairingSession pairing_session_{};

    /// Count of pairing server/activate messages received since the last Noise handshake (or
    /// re-handshake) (pairing.md "Pairing index"). Feeds both the wire `pairing_index` field on
    /// client/pair-init and the CPace `sid` (see PairingSession::pairing_index, captured at
    /// handle_enter_pairing() so a later PAKE step reuses the exact value client/pair-init sent).
    /// Protocol task only.
    uint32_t pairing_index_{0};

    /// Tag of the client/time frame in flight: the low 32 bits of the client_transmitted it
    /// carries, never 0 for a frame, and 0 once the frame is claimed or cancelled. A failed send
    /// leaves its tag, which no reply can echo. Protocol task only.
    uint32_t time_frame_tag_{0};

    /// Low 32 bits of the client clock when the frame in flight was handed to the socket, seeded
    /// with the tag on the protocol task until the write hook overwrites it on whichever thread
    /// performs the write (the ESP server's httpd worker); read on the protocol task
    /// (claim_time_frame()). 32 bits because a 64-bit atomic takes a lock on the ESP32 family.
    std::atomic<uint32_t> time_frame_sent_us_{0};

    // 16-bit fields

    /// active_roles_ as a bitmask of the roles this library implements (see is_role_active()).
    /// Rebuilt by apply_server_activate(). Protocol task only.
    uint16_t active_role_mask_{0};

    // 8-bit fields

    /// Lifecycle-flag axes.
    ///
    /// The lifecycle flags below, with the admitted flag in inbound_gate_, are three independent
    /// axes, not one linear lifecycle:
    ///  - Transport: ws_upgraded_.
    ///  - Proving: noise_handshake_complete_ (set once, never cleared, not even by an in-band
    ///    re-handshake, which keeps the transport active), client_hello_sent_ /
    ///    server_hello_received_ (once per connection; connection.md "Re-handshake" re-sends
    ///    neither hello), and first_activate_received_, which the server owes again after every
    ///    re-handshake.
    ///  - Admission: inbound_gate_'s admitted flag, whether this connection occupies one of the
    ///    manager's admitted slots.
    ///    Orthogonal to proving: an operational nursery loser is never admitted, and a
    ///    re-handshaking admitted connection is admitted but not operational.
    ///
    /// Each flag's own comment below names its writers and threads. Do not fold them into one
    /// phase enum: client_hello_sent_ and server_hello_received_ complete in either order, and
    /// handle_noise_rehandshake() / note_pairing_finalize_ack() rewind first_activate_received_
    /// from the protocol task. Derive a phase on demand instead, as SetupStage in
    /// connection_manager.cpp does for reap diagnostics.

    /// PSK category resolved by the Noise handshake (set at COMPLETE, or re-handshake). Protocol
    /// task only.
    PskCategory psk_category_{PskCategory::SENTINEL};

    /// True while a pairing exchange is in progress on this connection: set when entering
    /// pairing, cleared on abort, on a leftover activate and by handle_noise_rehandshake().
    /// Protocol task only.
    bool pairing_in_progress_{false};

    /// True from the moment the server acks server/pair-finalize until fresh activities arrive
    /// (or pairing state is cleared). In that window the exchange is protocol-complete (the
    /// record is already stored), but `activities_` still holds the pre-finalize [PAIRING] set,
    /// because only apply_server_activate() ever rewrites it and the post-finalize activate has
    /// not arrived yet. Admission consults this so the "in-flight pairing is not displaced" rule
    /// stops protecting a pairing that has already finished (see should_switch_to_new_server).
    /// Protocol task only.
    bool pairing_finalized_{false};

    /// Whether client/hello went out. Set when the hello send returns OK, and never cleared: a
    /// closed connection is not reused. Protocol task only.
    bool client_hello_sent_{false};

    /// True once the Noise transport handshake has completed. Protocol task only.
    bool noise_handshake_complete_{false};

    /// true once the transport delivered the connected event (WebSocket upgrade completed).
    /// Written from the transport connected callback (transport thread), read by the manager on
    /// the protocol task, hence atomic. See mark_ws_upgraded().
    std::atomic<bool> ws_upgraded_{false};

    /// The kind of the message in fallback_buf_ (TEXT or BINARY: a continuation frame does not
    /// carry the type its message started with). Same threads as fallback_len_.
    InboundKind fallback_kind_{InboundKind::BINARY};

    /// Where the current message goes. Transport thread only: true while it is being received
    /// into fallback_buf_ (a pre-admission message, or a multi-frame message being assembled).
    bool inbound_to_fallback_{false};

    /// True while the rest of a multi-frame message is being read and discarded. Transport thread
    /// only.
    bool fragment_dropping_{false};

    /// True from a multi-frame message's first bytes until its last bytes or
    /// abandon_inbound_message(), whether it is being assembled or dropped: a continuation frame
    /// is accepted only while it is set. Transport thread only.
    bool fragment_assembly_open_{false};

    /// Whether server/hello arrived. Set by its handler and never cleared: a closed connection is
    /// not reused. Protocol task only.
    bool server_hello_received_{false};

    /// The codecs server/hello's source@v1 support object lists, one source_codec_bit() each (see
    /// server_accepts_source_codec()). Protocol task only.
    uint8_t server_source_codecs_{0};

    /// True after the first server/activate message has been received and applied, until a
    /// re-handshake or a pairing-finalize ack rewinds it. Protocol task only.
    bool first_activate_received_{false};

    /// Memory placement preference for fallback_buf_ (ESP-IDF only).
    MemoryLocation fallback_location_{MemoryLocation::PREFER_EXTERNAL};
};

}  // namespace sendspin
