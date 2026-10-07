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

/// @file noise_session.h
/// @brief Noise KKpsk2 session wrapper (handshake + transport cipher states).
///
/// Implements the Noise pattern of connection.md "Pattern".
///
/// Key design choice: deferred PSK binding.
/// In KKpsk2 the PSK is mixed only during msg2, so msg1 (which carries
/// the encrypted psk_id) is decryptable with static keys alone, and the "psk"
/// token is not processed until msg2 is written. noise-c allows
/// `noise_handshakestate_set_pre_shared_key` to be called at any point before
/// that token is reached, including after `noise_handshakestate_start` and
/// after reading msg1. A single responder session is therefore built without
/// (or with a not-yet-final) PSK, reads msg1 to expose the psk_id, and only
/// then binds the real PSK via `set_psk` before writing msg2.
///
/// See `NoiseSession::read_msg1`, `NoiseSession::set_psk`, and
/// `NoiseSession::write_msg2_and_split`.

#pragma once

#include <array>
#include <cstddef>
#include <cstdint>
#include <optional>
#include <string>
#include <vector>

// noise-c forward declarations: the implementation includes the actual headers.
struct NoiseHandshakeState_s;
typedef struct NoiseHandshakeState_s NoiseHandshakeState;
struct NoiseCipherState_s;
typedef struct NoiseCipherState_s NoiseCipherState;

namespace sendspin {

/// @brief Noise KKpsk2 session: handshake and transport state.
///
/// Construct via the factory `NoiseSession::as_responder()`.
/// Call `read_msg1()`, then `set_psk()` (if the PSK was not supplied at
/// construction), then `write_msg2_and_split()` to complete the handshake.
/// After split(), use `encrypt()` / `decrypt()` for transport traffic.
class NoiseSession {
public:
    ~NoiseSession();

    // Non-copyable; movable.
    NoiseSession(const NoiseSession&) = delete;
    NoiseSession& operator=(const NoiseSession&) = delete;
    NoiseSession(NoiseSession&&) noexcept;
    NoiseSession& operator=(NoiseSession&&) noexcept;

    // ========================================
    // Factory
    // ========================================

    /// @brief Build a KKpsk2 responder session (the Sendspin client role).
    ///
    /// The real PSK is only known after `read_msg1` reveals the psk_id carried in its
    /// payload, so `psk` may be null here: noise-c permits
    /// `noise_handshakestate_set_pre_shared_key` to be called at any point before the "psk"
    /// token is processed, which for KKpsk2 happens in msg2. The handshake driver
    /// (noise_handshake.cpp run_msg1_core) builds the session with `psk = nullptr`, calls
    /// `read_msg1` to expose the psk_id (KK's "es"/"ss" msg1 tokens authenticate against the
    /// static keys and do not involve the PSK), resolves the real PSK via the record store,
    /// binds it with `set_psk`, and only then calls `write_msg2_and_split`.
    ///
    /// @param prologue    Exact prologue bytes (init messages, or prior hash on re-handshake).
    /// @param psk         32-byte PSK, or nullptr to bind it later via `set_psk`.
    /// @return Session ready for `read_msg1`, or nullopt on error.
    static std::optional<NoiseSession> as_responder(const std::string& suite_name,
                                                    const uint8_t* local_priv,
                                                    const uint8_t* remote_pub,
                                                    const uint8_t* prologue, size_t prologue_len,
                                                    const uint8_t* psk);

    // ========================================
    // Handshake
    // ========================================

    /// @brief Decrypt Noise message 1 and return its plaintext payload.
    /// @return Decrypted payload, or empty vector on auth failure.
    std::vector<uint8_t> read_msg1(const uint8_t* msg1_bytes, size_t msg1_len);

    /// @brief Bind (or rebind) the pre-shared key on this handshake state.
    ///
    /// Responder-only. Must be called before `write_msg2_and_split`, i.e. before the
    /// "psk" token is processed: for KKpsk2 that token is in msg2, so this may be
    /// called any time after construction and up through immediately before
    /// `write_msg2_and_split`, including after `read_msg1`. Calling it more than once
    /// simply replaces the previously bound key.
    ///
    /// @return false if the handshake state is null/consumed or noise-c rejects the key.
    bool set_psk(const uint8_t* psk);

    /// @brief Encrypt Noise message 2 (payload = `{}` UTF-8) and split into
    /// transport cipher states.
    /// @return true on success; transport mode is now active.
    bool write_msg2_and_split(std::vector<uint8_t>& msg2_out);

    /// @brief The 32-byte Noise handshake hash `h` (available after split).
    const std::array<uint8_t, 32>& handshake_hash() const {
        return this->handshake_hash_;
    }

    // ========================================
    // Transport
    // ========================================

    /// @brief Encrypt plaintext for transport.
    /// @param plaintext  Input bytes (modified in-place; the caller must supply
    ///                   `len + AEAD_TAG_SIZE` bytes of capacity).
    /// @return Number of ciphertext bytes (len + AEAD_TAG_SIZE of tag), or 0 on error.
    size_t encrypt(uint8_t* plaintext, size_t len, size_t capacity);

    /// @brief Decrypt ciphertext in-place.
    /// @param len  Number of ciphertext bytes (plaintext + AEAD_TAG_SIZE of tag).
    /// @return Number of plaintext bytes, or 0 on auth failure.
    size_t decrypt(uint8_t* ciphertext, size_t len);

private:
    NoiseSession() = default;

    // Struct fields
    /// @brief Noise handshake hash `h` (populated by write_msg2_and_split).
    std::array<uint8_t, 32> handshake_hash_{};

    // Pointer fields
    /// @brief The active handshakestate (null after split).
    NoiseHandshakeState* hs_{nullptr};

    /// @brief Transport cipher: receive direction (null until split).
    NoiseCipherState* recv_cipher_{nullptr};

    /// @brief Transport cipher: send direction (null until split).
    NoiseCipherState* send_cipher_{nullptr};
};

}  // namespace sendspin
