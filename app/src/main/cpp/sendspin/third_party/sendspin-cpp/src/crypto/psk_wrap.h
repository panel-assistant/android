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

/// @file psk_wrap.h
/// @brief Wrapping (pairing.md "Wrapping"): seals the two values a code-based pairing reveals
/// only under the CPace output, so `client/pair-finalize` carries `wrapped_psk` instead of the
/// new long-term PSK in the clear, and `client/pair-confirm` carries `wrapped_nonce_B` instead
/// of the commitment opening.
///
/// K_wrap = SHA-256(label || sid || ISK)
///
/// with a distinct label per field, where `sid` is the CPace session id (see cpace.h
/// CPace::sid(), pairing.md "PAKE") and `ISK` is the 64-byte CPace intermediate session key (see
/// cpace.h CPace::isk()). The 32-byte value is then sealed with the AEAD of the connection's
/// negotiated cipher suite, a 12-byte all-zero nonce, and empty associated data: the field
/// carries the 48-byte ciphertext-plus-tag.

#pragma once

#include "cpace.h"

#include <array>
#include <cstddef>
#include <cstdint>
#include <optional>
#include <string_view>
#include <vector>

namespace sendspin {

/// @brief Size in bytes of a wrapped 32-byte value (the value + a 16-byte AEAD tag). Both
/// `wrapped_psk` and `wrapped_nonce_B` seal exactly 32 bytes.
static constexpr size_t WRAPPED_VALUE_SIZE = 32 + 16;

/// @brief Domain-separation label for the `wrapped_psk` key (pairing.md "Wrapping").
static constexpr std::string_view PSK_WRAP_LABEL{"sendspin-pair-psk-wrap-v1"};

/// @brief Domain-separation label for the `wrapped_nonce_B` key (pairing.md "Wrapping"). Distinct
/// from PSK_WRAP_LABEL so the two fields of one attempt never share a key, which the all-zero
/// AEAD nonce would otherwise make a two-time pad.
static constexpr std::string_view NONCE_WRAP_LABEL{"sendspin-pair-nonce-wrap-v1"};

/// @brief Derive K_wrap = SHA-256(label || sid || isk)
/// @param label Per-field wrap label (PSK_WRAP_LABEL or NONCE_WRAP_LABEL).
/// @return The 32-byte key, or std::nullopt if the underlying SHA-256 computation fails (e.g.
///         noise-c allocation failure); callers must not treat that as a recoverable all-zero
///         key.
std::optional<std::array<uint8_t, 32>> derive_wrap_key(
    std::string_view label, const std::vector<uint8_t>& sid,
    const std::array<uint8_t, CPACE_ISK_SIZE>& isk);

/// @brief Seal a 32-byte value under K_wrap using the named AEAD cipher ("ChaChaPoly"), a
/// 12-byte all-zero nonce, and empty associated data.
/// @param label       Per-field wrap label (PSK_WRAP_LABEL or NONCE_WRAP_LABEL).
/// @param cipher_name Noise-c cipher name for the connection's negotiated suite.
/// @return The 48-byte wrapped field (ciphertext || tag), or nullopt on a cipher failure.
std::optional<std::array<uint8_t, WRAPPED_VALUE_SIZE>> wrap_value(
    std::string_view label, const char* cipher_name, const std::vector<uint8_t>& sid,
    const std::array<uint8_t, CPACE_ISK_SIZE>& isk, const std::array<uint8_t, 32>& value);

}  // namespace sendspin
