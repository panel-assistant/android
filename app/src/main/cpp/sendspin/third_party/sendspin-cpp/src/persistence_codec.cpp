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

#include "sendspin/persistence_codec.h"

#include "crypto/constants.h"
#include "crypto/keys.h"
#include "platform/base64.h"
#include "platform/crypto.h"

#include <array>
#include <cstring>
#include <optional>
#include <string>
#include <vector>

namespace sendspin {

namespace {

/// Offsets into a record blob; see the header's "Storage format".
constexpr size_t RECORD_PSK_OFFSET = 0;
constexpr size_t RECORD_SERVER_KEY_OFFSET = RECORD_PSK_OFFSET + NOISE_PSK_SIZE;
static_assert(RECORD_SERVER_KEY_OFFSET + X25519_KEY_SIZE == persistence_keys::RECORD_SLOT_SIZE,
              "the record layout must fill RECORD_SLOT_SIZE exactly");
static_assert(persistence_keys::PAIRING_PSK_SIZE == NOISE_PSK_SIZE,
              "the Pairing PSK blob is the bare PSK");

/// @brief Copies a stored PSK out of a blob, rejecting the all-zero key a freed record slot holds.
/// @return true when `out` holds a usable PSK.
bool read_psk(const uint8_t* data, std::array<uint8_t, NOISE_PSK_SIZE>& out) {
    std::memcpy(out.data(), data, out.size());
    const std::array<uint8_t, NOISE_PSK_SIZE> zero{};
    return !constant_time_equal(out.data(), zero.data(), out.size());
}

}  // namespace

// ============================================================================
// Pairing record
// ============================================================================

std::optional<std::array<uint8_t, persistence_keys::RECORD_SLOT_SIZE>> encode_pairing_record(
    const SendspinPairingRecord& r) {
    auto server_key = public_key_from_peer_id(r.server_id);
    if (!server_key.has_value()) {
        return std::nullopt;
    }
    std::array<uint8_t, persistence_keys::RECORD_SLOT_SIZE> out{};
    std::memcpy(out.data() + RECORD_PSK_OFFSET, r.psk.data(), r.psk.size());
    std::memcpy(out.data() + RECORD_SERVER_KEY_OFFSET, server_key->data(), server_key->size());
    return out;
}

std::optional<SendspinPairingRecord> decode_pairing_record(const uint8_t* data, size_t len) {
    if (len != persistence_keys::RECORD_SLOT_SIZE) {
        return std::nullopt;
    }
    SendspinPairingRecord rec;
    if (!read_psk(data + RECORD_PSK_OFFSET, rec.psk)) {
        return std::nullopt;
    }
    rec.psk_id = psk_id_for(rec.psk);
    rec.server_id = b64url_encode(data + RECORD_SERVER_KEY_OFFSET, X25519_KEY_SIZE);
    return rec;
}

// ============================================================================
// Pairing PSK
// ============================================================================

std::array<uint8_t, persistence_keys::PAIRING_PSK_SIZE> encode_pairing_psk(
    const SendspinPairingPsk& p) {
    return p.psk;
}

std::optional<SendspinPairingPsk> decode_pairing_psk(const uint8_t* data, size_t len) {
    if (len != persistence_keys::PAIRING_PSK_SIZE) {
        return std::nullopt;
    }
    SendspinPairingPsk psk;
    if (!read_psk(data, psk.psk)) {
        return std::nullopt;
    }
    psk.psk_id = psk_id_for(psk.psk);
    return psk;
}

// ============================================================================
// Base64url
// ============================================================================

std::string base64url_encode(const uint8_t* data, size_t len) {
    return b64url_encode(data, len);
}

std::optional<std::vector<uint8_t>> base64url_decode(std::string_view s) {
    return b64url_decode(std::string(s));
}

}  // namespace sendspin
