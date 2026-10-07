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

/// @file pairing_token.h
/// @brief Pairing Token encoder (pairing.md "Pairing Token"): the single "SP:"-prefixed,
/// versioned, base32 string that carries a pairing secret from the client to the server, so an
/// operator can transfer it by copy/paste or QR scan.
///
/// token   = "SP:" || version || body
/// body    = RFC 4648 base32(payload), '=' padding stripped, then every '2' -> '9'
///
/// Two versions carry two payloads:
///   '0': client_key (32 bytes) || pairing_psk (32 bytes), for the Pairing PSK Flow.
///   '1': the 24-byte dynamic pairing code, for the `qr_code` emission format.
///
/// This client only ever generates tokens (the server decodes them), so no decoder is provided
/// here; see pairing.md "Pairing Token" for the (server-side) decode algorithm.

#pragma once

#include "keys.h"
#include "pairing_code.h"

#include <array>
#include <cstdint>
#include <string>

namespace sendspin {

/// @brief Pairing-token version carrying a Pairing PSK with the client identity.
static constexpr char PAIRING_PSK_TOKEN_VERSION = '0';

/// @brief Length in characters of a version-0 pairing token ("SP:" + version + 103-char body).
static constexpr size_t PAIRING_PSK_TOKEN_LENGTH = 107;

/// @brief Pairing-token version carrying a dynamic pairing code in the `qr_code` format.
static constexpr char PAIRING_CODE_TOKEN_VERSION = '1';

/// @brief Length in characters of a version-1 pairing token ("SP:" + version + 39-char body:
/// base32 of the 24-byte code, whose 192 bits need exactly 39 characters once the single
/// padding character is stripped).
static constexpr size_t PAIRING_CODE_TOKEN_LENGTH = 43;

/// @brief Build a version-0 pairing token from a client's static public key and its Sendspin
/// Pairing PSK.
/// @param client_key   32-byte raw Curve25519 public key (the same bytes whose base64url form
///                     is the client_id).
/// @return The 107-character token string (e.g. "SP:0AAAQ...").
std::string format_pairing_token(const std::array<uint8_t, X25519_KEY_SIZE>& client_key,
                                 const std::array<uint8_t, NOISE_PSK_SIZE>& pairing_psk);

/// @brief Build a version-1 pairing token carrying a dynamic pairing code, the form the
/// `qr_code` emission format presents (pairing.md "Dynamic Pairing Code Flow").
/// @return The 43-character token string (e.g. "SP:14DQ...").
std::string format_pairing_code_token(const std::array<uint8_t, QR_PAIRING_CODE_SIZE>& code);

}  // namespace sendspin
