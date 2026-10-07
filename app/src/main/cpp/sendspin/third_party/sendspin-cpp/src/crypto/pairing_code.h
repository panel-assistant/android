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

/// @file pairing_code.h
/// @brief Pairing-code derivation and commitment (pairing.md "Dynamic Pairing Code Flow").

#pragma once

#include <array>
#include <cstddef>
#include <cstdint>
#include <optional>
#include <string>
#include <string_view>
#include <vector>

namespace sendspin {

// ============================================================================
// Pairing-code constants
// ============================================================================

/// @brief Size of a pairing-code binding nonce in bytes (nonce_A, nonce_B).
static constexpr size_t PAIRING_NONCE_SIZE = 32;

/// @brief Size of the commitment to nonce_B (a SHA-256 digest) in bytes.
static constexpr size_t PAIRING_COMMIT_SIZE = 32;

/// @brief Label prepended to the SHA-256 input that derives a dynamic pairing code
/// (pairing.md "Dynamic Pairing Code Flow").
static constexpr std::string_view PAIRING_CODE_DERIVE_LABEL{"sendspin-pairing-code-derive-v1"};

/// @brief Domain-separation label prepended to nonce_B in pairing_code_commit(), so the
/// commitment hash cannot be reused across a different protocol context.
static constexpr std::string_view PAIRING_COMMIT_LABEL{"sendspin-pair-commit-v1"};

/// @brief Length of a dynamic pairing code in the `digits` emission format, fixed by
/// pairing.md "Dynamic Pairing Code Flow".
static constexpr int DYNAMIC_PAIRING_CODE_DIGITS = 6;

/// @brief Length of a dynamic pairing code in the `qr_code` emission format: the first bytes of
/// the same digest, carried as the payload of a version-1 pairing token.
static constexpr size_t QR_PAIRING_CODE_SIZE = 24;

/// @brief Fixed length of a static pairing code, in decimal digits
/// (pairing.md "Static Pairing Code Flow").
static constexpr int STATIC_PAIRING_CODE_DIGITS = 8;

// ============================================================================
// Pairing-code helpers
// ============================================================================

/// @brief Return whether `code` is exactly STATIC_PAIRING_CODE_DIGITS decimal digits
inline bool is_valid_static_pairing_code(const std::string& code) {
    if (code.size() != static_cast<size_t>(STATIC_PAIRING_CODE_DIGITS)) {
        return false;
    }
    for (char c : code) {
        if (c < '0' || c > '9') {
            return false;
        }
    }
    return true;
}

/// @brief Generate a fresh 32-byte CSPRNG nonce (nonce_A or nonce_B)
std::array<uint8_t, PAIRING_NONCE_SIZE> pairing_generate_nonce();

/// @brief Compute the commitment commit_B = SHA-256(PAIRING_COMMIT_LABEL || nonce)
/// @param nonce_len Length of `nonce`, which must be PAIRING_NONCE_SIZE.
/// @return The commitment bytes, or std::nullopt if `nonce_len` is wrong or the hash fails.
std::optional<std::array<uint8_t, PAIRING_COMMIT_SIZE>> pairing_code_commit(const uint8_t* nonce,
                                                                            size_t nonce_len);

/// @brief Return true if SHA-256(PAIRING_COMMIT_LABEL || nonce) equals commitment
/// (constant-time)
bool pairing_code_verify_commit(const uint8_t* nonce, size_t nonce_len, const uint8_t* commitment,
                                size_t commitment_len);

/// @brief Derive the dynamic pairing code's digest from the Noise handshake hash and both nonces.
///
/// digest = SHA-256(PAIRING_CODE_DERIVE_LABEL || handshake_hash || nonce_a || nonce_b): the
/// label's literal UTF-8 bytes followed by the three 32-byte raw values
/// (pairing.md "Dynamic Pairing Code Flow").
/// @param hash_len    Length of `handshake_hash`, which must be SHA256_DIGEST_SIZE.
/// @param nonce_a_len Length of the server's nonce, which must be PAIRING_NONCE_SIZE.
/// @param nonce_b_len Length of the client's nonce, which must be PAIRING_NONCE_SIZE.
/// @return The 32-byte digest, or std::nullopt if any input has the wrong size or the hash fails.
std::optional<std::array<uint8_t, 32>> pairing_code_digest(const uint8_t* handshake_hash,
                                                           size_t hash_len, const uint8_t* nonce_a,
                                                           size_t nonce_a_len,
                                                           const uint8_t* nonce_b,
                                                           size_t nonce_b_len);

/// @brief Reduce a pairing-code digest to the `digits` emission format: the digest read as an
/// unsigned big-endian 256-bit integer modulo 10^6, left-padded to exactly six ASCII digits
std::string pairing_code_digits(const std::array<uint8_t, 32>& digest);

/// @brief Take the `qr_code` emission format's pairing code from a digest: its first
/// QR_PAIRING_CODE_SIZE bytes, raw
std::array<uint8_t, QR_PAIRING_CODE_SIZE> pairing_code_qr_bytes(
    const std::array<uint8_t, 32>& digest);

/// @brief Return the bytes CPace consumes as PRS for a decimal pairing code: its ASCII digits
/// (pairing.md "PAKE"). Shared by both the dynamic `digits` format and the static pairing code,
/// which encode their codes identically.
inline std::vector<uint8_t> pairing_code_digits_prs(const std::string& code) {
    return std::vector<uint8_t>(code.begin(), code.end());
}

}  // namespace sendspin
