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

/// @file keys.h
/// @brief Sendspin key helpers: PSK-ID derivation and X25519 Identity.
///
/// Both values are fixed by connection.md "Pre-Shared Key" and "Identities":
///   - `psk_id_for(psk)` = base64url(SHA-256(PSK_ID_LABEL || psk))
///   - `Identity` holds a 32-byte X25519 keypair; `peer_id` = base64url(pubkey).

#pragma once

#include "constants.h"

#include <array>
#include <cstddef>
#include <cstdint>
#include <optional>
#include <string>

namespace sendspin {

// ============================================================================
// PSK-ID derivation
// ============================================================================

/// @brief Derive the psk_id for a 32-byte PSK.
/// @param psk  Exactly NOISE_PSK_SIZE (32) bytes.
/// @return base64url(SHA-256(PSK_ID_LABEL || psk)), a 43-char string with no `=`.
/// Never throws; calls abort() if the underlying SHA-256 computation fails (e.g. a noise-c
/// allocation failure), which is not expected to happen in practice.
std::string psk_id_for(const std::array<uint8_t, NOISE_PSK_SIZE>& psk);

/// @brief Derive the psk_id for an arbitrary-length PSK buffer.
/// Returns std::nullopt if len != NOISE_PSK_SIZE.
std::optional<std::string> psk_id_for(const uint8_t* psk, size_t len);

// ============================================================================
// X25519 Identity (long-term static keypair)
// ============================================================================

/// @brief Size of an X25519 key (private or public) in bytes.
static constexpr size_t X25519_KEY_SIZE = 32;

/// @brief Length of a base64url-encoded peer identifier (43 chars, no `=`).
/// A 32-byte value encodes to ceil(32*8/6)=43 base64url characters.
static constexpr size_t PEER_ID_SIZE = 43;

/// @brief Decode a peer_id to the X25519 public key it names.
///
/// Accepts only the exact string `Identity::peer_id()` would produce for that key: 43 base64url
/// characters whose unused trailing bits are zero. Base64url admits other spellings of the same
/// 32 bytes, and a peer_id is compared as a string wherever it is stored, so a second spelling
/// would name the same key under a different id.
/// @param peer_id The base64url `client_id` / `server_id`.
/// @return The public key, or nullopt when peer_id is not that canonical encoding.
std::optional<std::array<uint8_t, X25519_KEY_SIZE>> public_key_from_peer_id(
    const std::string& peer_id);

/// @brief Sendspin static X25519 identity: a long-term keypair.
///
/// See connection.md "Identities".
/// The `peer_id` property returns `base64url(public_bytes)`, which is the
/// Sendspin `client_id` / `server_id` wire representation.
struct Identity {
    std::array<uint8_t, X25519_KEY_SIZE> private_bytes{};
    std::array<uint8_t, X25519_KEY_SIZE> public_bytes{};

    /// @brief Wipes `private_bytes` on destruction (see secure_zero in platform/secure_zero.h).
    ///
    /// This is the same discipline ~CPace() applies to its ephemeral secrets. It bounds how long
    /// a *stale* copy of the long-term key outlives its use: the temporaries that
    /// load_or_generate_identity() creates while rehydrating the key, and any copy taken by a
    /// future caller. It does NOT protect the live key: the `identity_` the client owns must stay
    /// readable for the whole process lifetime because every Noise handshake dereferences it, and
    /// the same 32 bytes are persisted unencrypted under persistence_keys::KEYPAIR. An attacker
    /// who can already read RAM or flash is not shut out by this; it only removes the needless
    /// extra copies.
    ///
    /// Only the destructor is user-declared, deliberately: declaring any constructor would make
    /// Identity a non-aggregate under C++20 and break `Identity{}` value-init at its call sites.
    /// The cost is that the implicit move operations are suppressed, so moves degrade to copies;
    /// that is harmless for a 64-byte struct, and every such copy now wipes itself.
    ~Identity();

    /// @brief The base64url-encoded public key (Sendspin client_id / server_id).
    [[nodiscard]] std::string peer_id() const;

    // ========================================
    // Factory methods
    // ========================================

    /// @brief Generate a new random X25519 identity using the platform CSPRNG.
    /// Returns std::nullopt if the underlying noise-c DH-state allocation or keypair
    /// generation fails (e.g. heap pressure); callers must not treat a default-constructed
    /// Identity as a valid key.
    static std::optional<Identity> generate();

    /// @brief Reconstruct an Identity from its 32-byte raw private key.
    /// Returns std::nullopt if private_key_bytes.size() != X25519_KEY_SIZE, or if the
    /// underlying noise-c DH-state computation fails.
    static std::optional<Identity> from_private_bytes(const uint8_t* priv, size_t len);

    /// @brief Reconstruct an Identity from a 32-byte array private key.
    /// Returns std::nullopt on the same failure conditions as the pointer/length overload.
    static std::optional<Identity> from_private_bytes(
        const std::array<uint8_t, X25519_KEY_SIZE>& priv);
};

}  // namespace sendspin
