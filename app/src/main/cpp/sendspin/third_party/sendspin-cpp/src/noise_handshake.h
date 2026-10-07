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

/// @file noise_handshake.h
/// @brief Noise KKpsk2 cleartext init exchange + handshake state machine.
///
/// The Sendspin client is always the Noise responder (connection.md "Pattern").
///
/// Protocol sequence (all pre-transport frames are WS TEXT):
///   1. Send  `client/init` (TEXT)
///   2. Recv  `server/init` (TEXT): prologue = bytes(client/init) || bytes(server/init)
///   3. Recv  `noise/handshake` msg1 (TEXT): build a KKpsk2 responder with no PSK bound yet,
///      read msg1 through it, extract psk_id from the decrypted payload
///   4. Resolve psk_id via RecordStore; bind the resolved PSK onto the same responder
///      (NoiseSession::set_psk): valid because KKpsk2 processes the "psk" token in msg2,
///      not msg1
///   5. Send  `noise/handshake` msg2 (TEXT): write msg2, split -> transport
///
/// After step 5 the connection is in encrypted transport mode.
/// Any failure aborts silently (caller closes the WebSocket).
///
/// Re-handshake (in-band key rotation):
///   The server may initiate a new KKpsk2 handshake after transport is active.
///   The re-handshake msg1 arrives as a decrypted noise/handshake JSON envelope, which the
///   message dispatch reads (read_noise_handshake_data()) and releases before the re-handshake
///   runs. The prologue is the prior handshake hash `h` rather than init-text concatenation.
///   Use run_rehandshake_msg1() which applies the same deferred-PSK-binding sequence.

#pragma once

#include "crypto/keys.h"
#include "noise_session.h"
#include "record_store.h"
#include <ArduinoJson.h>

#include <array>
#include <cstdint>
#include <functional>
#include <memory>
#include <optional>
#include <string>
#include <utility>
#include <vector>

namespace sendspin {

class SendspinArenaAllocator;

/// @brief Outcome of a successful Noise handshake (initial or re-handshake).
struct NoiseHandshakeResult {
    /// The cipher session ready for transport-mode encrypt/decrypt.
    std::unique_ptr<NoiseSession> session;
    /// Server's public key peer_id (43-char base64url).
    std::string server_id;
    /// The PSK record that admitted the connection.
    ResolvedPsk resolved_psk;
    /// Serialized noise/handshake msg2 JSON to send to the peer.
    /// Non-empty only for re-handshake results (initial handshake sends msg2 inline).
    std::string msg2_text;
};

/// @brief Result of processing one incoming WS frame during the handshake.
enum class HandshakeFrameResult : uint8_t {
    NEED_MORE,  ///< Frame processed; waiting for the next frame.
    COMPLETE,   ///< Handshake complete; switch to transport mode.
    ABORT,      ///< Fatal error; caller must close the WebSocket silently.
};

// ============================================================================
// noise/handshake envelope
// ============================================================================

/// @brief Reads the Noise message bytes a noise/handshake envelope carries: its payload.data,
/// base64url-decoded.
///
/// The one reader of the envelope for both the initial handshake's msg1 frame and the in-band
/// re-handshake's, so the caller can release the parsed envelope before the handshake runs.
/// @param envelope    Parsed noise/handshake envelope; its type is the caller's to check.
/// @param log_context Prefix for the failure log line.
/// @return The decoded bytes, or nullopt (logged) when data is missing, empty or not base64url.
std::optional<std::vector<uint8_t>> read_noise_handshake_data(JsonObjectConst envelope,
                                                              const char* log_context);

// ============================================================================
// Re-handshake helper
// ============================================================================

/// @brief Run the responder side of an in-band Noise KKpsk2 re-handshake.
///
/// Called on the protocol task when a decrypted noise/handshake JSON arrives
/// after transport mode is already active, with the msg1 bytes read from it
/// (read_noise_handshake_data()).  The prologue for the re-handshake
/// is the 32-byte handshake hash `h` from the PRIOR handshake.
///
/// The same deferred-PSK-binding sequence used by the initial handshake applies here:
///   1. Build a session with no PSK bound.
///   2. Read msg1 through it to extract psk_id.
///   3. Resolve psk_id via record_store.
///   4. Bind the resolved PSK onto the same session (NoiseSession::set_psk).
///   5. Write msg2 and split -> new session.
///
/// @param msg1_bytes     The re-handshake msg1 (Noise bytes, decoded from the envelope).
/// @param server_id      Known server peer_id (43-char base64url) from the prior handshake.
/// @param identity       Our static X25519 identity.
/// @param record_store   Record store for psk_id resolution (read-only on protocol task).
/// @param suite_name     Noise suite name (NOISE_SUITE_CHACHAPOLY; see crypto/constants.h).
/// @param prior_h        32-byte handshake hash from the prior session (used as prologue).
/// @param arena          The client's JSON arena: msg1's payload is parsed and the msg2 envelope
///                       built in it, one after the other.
/// @return Populated NoiseHandshakeResult (session + msg2_text to send) on success,
///         or nullopt on any failure (caller should close the WebSocket).
std::optional<NoiseHandshakeResult> run_rehandshake_msg1(
    const std::vector<uint8_t>& msg1_bytes, const std::string& server_id, const Identity& identity,
    const RecordStore& record_store, const std::string& suite_name,
    const std::array<uint8_t, 32>& prior_h, SendspinArenaAllocator& arena);

// ============================================================================
// NoiseHandshake class (initial handshake state machine)
// ============================================================================

/// @brief Noise handshake state machine for the Sendspin client (Noise responder).
///
/// build_client_init() produces the first cleartext frame, on_text_frame() takes each incoming
/// text frame, and take_result() yields the session once that returns COMPLETE.
///
/// Threading: runs entirely on the protocol task, which is where RecordStore::resolve_by_psk_id()
/// runs (see record_store.h).
class NoiseHandshake {
public:
    /// @brief Construct the handshake driver.
    /// @param record_store Record store for psk_id resolution (read-only on protocol task).
    /// @param suite_name   Noise suite name (NOISE_SUITE_CHACHAPOLY; see crypto/constants.h).
    /// @param arena        The client's JSON arena, which every frame the handshake parses or
    ///                     builds is allocated from; outlives the handshake.
    NoiseHandshake(const Identity& identity, const RecordStore& record_store,
                   const std::string& suite_name, SendspinArenaAllocator& arena);

    ~NoiseHandshake() = default;

    // Non-copyable, non-movable (contains a reference member).
    NoiseHandshake(const NoiseHandshake&) = delete;
    NoiseHandshake& operator=(const NoiseHandshake&) = delete;
    NoiseHandshake(NoiseHandshake&&) = delete;
    NoiseHandshake& operator=(NoiseHandshake&&) = delete;

    // ========================================
    // Drive the state machine
    // ========================================

    /// @brief Serialize and return the client/init TEXT frame, or an empty string on error.
    /// Call this immediately after the WS connection is open, before reading frames.
    std::string build_client_init();

    /// @brief Process one incoming WS text frame.
    /// Must be called in sequence: server/init, then noise/handshake msg1.
    /// @param send_fn  Callable that sends a TEXT frame to the peer.
    ///                 Signature: `bool send_fn(const std::string& text)`.
    HandshakeFrameResult on_text_frame(const std::string& text,
                                       const std::function<bool(const std::string&)>& send_fn);

    /// @brief The reason carried by a server/error received during the handshake, or empty.
    ///
    /// messaging.md "server/error": the server sends one in place of server/init when it cannot
    /// accept our client/init. The message is unauthenticated, so connection.md "Failure Handling"
    /// makes the reason a hint for logging and operator display.
    const std::string& server_error_reason() const {
        return this->server_error_reason_;
    }

    /// @brief Take ownership of the handshake result (only valid after COMPLETE).
    std::optional<NoiseHandshakeResult> take_result() {
        return std::move(this->result_);
    }

private:
    enum class State : uint8_t {
        INIT,              ///< client/init not yet sent
        WAIT_SERVER_INIT,  ///< waiting for server/init
        WAIT_MSG1,         ///< waiting for noise/handshake msg1
        COMPLETE,          ///< handshake done
        ABORTED,           ///< terminal error
    };

    /// @brief Records and logs the reason a received server/error carries.
    /// @param root        Parsed envelope of the received frame.
    /// @param log_context Handshake state to name in the log line.
    void take_server_error(JsonObjectConst root, const char* log_context);

    /// @brief Validate the fields read from a server/init frame.
    /// @param version   payload.version (0 when absent).
    /// @param server_id payload.server_id (empty when absent).
    /// @param text      Exact received bytes, retained for the handshake prologue.
    bool handle_server_init(int version, std::string server_id, const std::string& text);

    /// @brief Authenticate and respond to the noise/handshake msg1.
    /// @param msg1_bytes The msg1 Noise bytes, read from the frame (read_noise_handshake_data()).
    /// @param send_fn    Sends the msg2 frame back to the peer.
    /// @return true when msg1 authenticated and msg2 was sent.
    bool handle_msg1(const std::vector<uint8_t>& msg1_bytes,
                     const std::function<bool(const std::string&)>& send_fn);

    // Struct fields
    /// Exact bytes of the client/init frame we sent (retained for prologue).
    std::string client_init_text_;

    /// Result available after COMPLETE.
    std::optional<NoiseHandshakeResult> result_;

    /// Reason from a received server/error. See server_error_reason().
    std::string server_error_reason_;

    /// server_id decoded from server/init (43-char base64url).
    std::string server_id_;

    /// Exact bytes of the server/init frame we received (retained for prologue).
    std::string server_init_text_;

    std::string suite_name_;

    // Pointer fields
    // Reference members, grouped with pointers: both are non-owning indirections to
    // another object.
    SendspinArenaAllocator& arena_;
    const Identity& identity_;
    const RecordStore& record_store_;

    // 32-bit fields
    State state_{State::INIT};
};

}  // namespace sendspin
