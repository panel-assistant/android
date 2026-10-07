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

#include "pairing_code.h"

#include "platform/crypto.h"

#include <cstring>

namespace sendspin {

// ============================================================================
// pairing_generate_nonce
// ============================================================================

std::array<uint8_t, PAIRING_NONCE_SIZE> pairing_generate_nonce() {
    std::array<uint8_t, PAIRING_NONCE_SIZE> nonce{};
    platform_random_bytes(nonce.data(), nonce.size());
    return nonce;
}

// ============================================================================
// pairing_code_commit
// ============================================================================

std::optional<std::array<uint8_t, PAIRING_COMMIT_SIZE>> pairing_code_commit(const uint8_t* nonce,
                                                                            size_t nonce_len) {
    if (nonce_len != PAIRING_NONCE_SIZE) {
        return std::nullopt;
    }

    const auto* label = reinterpret_cast<const uint8_t*>(PAIRING_COMMIT_LABEL.data());
    size_t label_len = PAIRING_COMMIT_LABEL.size();

    Sha256 h;
    if (!h.ok()) {
        // Same rationale as pairing_code_digest(): a failed hash state finalizes to an all-zero
        // digest, and an all-zero commit_B would go out on the wire as a binding commitment.
        return std::nullopt;
    }
    h.update(label, label_len);
    h.update(nonce, nonce_len);
    auto commitment = h.finalize();
    if (!h.ok()) {
        return std::nullopt;
    }
    return commitment;
}

// ============================================================================
// pairing_code_verify_commit
// ============================================================================

bool pairing_code_verify_commit(const uint8_t* nonce, size_t nonce_len, const uint8_t* commitment,
                                size_t commitment_len) {
    if (commitment_len != PAIRING_COMMIT_SIZE || nonce_len != PAIRING_NONCE_SIZE) {
        return false;
    }
    auto computed = pairing_code_commit(nonce, nonce_len);
    if (!computed.has_value()) {
        return false;
    }
    return constant_time_equal(computed->data(), commitment, PAIRING_COMMIT_SIZE);
}

// ============================================================================
// pairing_code_digest
// ============================================================================

std::optional<std::array<uint8_t, 32>> pairing_code_digest(const uint8_t* handshake_hash,
                                                           size_t hash_len, const uint8_t* nonce_a,
                                                           size_t nonce_a_len,
                                                           const uint8_t* nonce_b,
                                                           size_t nonce_b_len) {
    if (hash_len != SHA256_DIGEST_SIZE || nonce_a_len != PAIRING_NONCE_SIZE ||
        nonce_b_len != PAIRING_NONCE_SIZE) {
        return std::nullopt;
    }

    const auto* label = reinterpret_cast<const uint8_t*>(PAIRING_CODE_DERIVE_LABEL.data());
    size_t label_len = PAIRING_CODE_DERIVE_LABEL.size();

    Sha256 h;
    if (!h.ok()) {
        // noise_hashstate_new_by_name() failed (allocation failure or missing algorithm); h is
        // a no-op in this state and finalize() would silently yield an all-zero digest, which
        // for a pairing code would produce a predictable code instead of failing loudly.
        return std::nullopt;
    }
    h.update(label, label_len);
    h.update(handshake_hash, hash_len);
    h.update(nonce_a, nonce_a_len);
    h.update(nonce_b, nonce_b_len);
    auto digest = h.finalize();
    if (!h.ok()) {
        // A mid-stream update()/finalize() failure; same rationale as the ok() check above:
        // do not derive a code from a zero digest.
        return std::nullopt;
    }
    return digest;
}

// ============================================================================
// pairing_code_digits / pairing_code_qr_bytes
// ============================================================================

/// @brief The radix of the decimal pairing code.
static constexpr uint64_t DECIMAL_BASE = 10;

std::string pairing_code_digits(const std::array<uint8_t, 32>& digest) {
    // The modulus fits in uint64_t: 10^6 = 1_000_000 < 2^20.
    uint64_t modulus = 1;
    for (int i = 0; i < DYNAMIC_PAIRING_CODE_DIGITS; ++i) {
        modulus *= DECIMAL_BASE;
    }

    // Reduce the big-endian 256-bit digest modulo 10^6 by Horner's method. acc stays below
    // modulus after every step, so the intermediate stays below modulus * 256 and well inside
    // uint64_t; no 128-bit arithmetic is needed (Xtensa and other 32-bit targets have no
    // __int128).
    uint64_t acc = 0;
    for (size_t i = 0; i < SHA256_DIGEST_SIZE; ++i) {
        acc = (acc * 256 + digest[i]) % modulus;
    }

    std::string code(static_cast<size_t>(DYNAMIC_PAIRING_CODE_DIGITS), '0');
    for (int i = DYNAMIC_PAIRING_CODE_DIGITS - 1; i >= 0; --i) {
        code[static_cast<size_t>(i)] = static_cast<char>('0' + (acc % DECIMAL_BASE));
        acc /= DECIMAL_BASE;
    }
    return code;
}

std::array<uint8_t, QR_PAIRING_CODE_SIZE> pairing_code_qr_bytes(
    const std::array<uint8_t, 32>& digest) {
    std::array<uint8_t, QR_PAIRING_CODE_SIZE> code{};
    std::memcpy(code.data(), digest.data(), QR_PAIRING_CODE_SIZE);
    return code;
}

}  // namespace sendspin
