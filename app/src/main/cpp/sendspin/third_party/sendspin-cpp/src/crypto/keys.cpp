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

#include "keys.h"

#include "constants.h"
#include "platform/base64.h"
#include "platform/crypto.h"

#include <algorithm>
#include <cstdlib>
#include <cstring>

// noise-c headers: included as extern "C" to silence pedantic warnings about C headers.
extern "C" {
#include <noise/protocol/constants.h>
#include <noise/protocol/dhstate.h>
}

namespace sendspin {

// ============================================================================
// PSK-ID derivation
// ============================================================================

std::optional<std::string> psk_id_for(const uint8_t* psk, size_t len) {
    if (len != NOISE_PSK_SIZE) {
        return std::nullopt;
    }
    Sha256 h;
    if (!h.ok()) {
        // noise_hashstate_new_by_name() failed (allocation failure or missing algorithm); h is
        // a no-op in this state and finalize() would silently yield an all-zero digest, which
        // would collapse every psk_id onto the same value (see RecordStore's psk_id dedupe).
        return std::nullopt;
    }
    h.update(reinterpret_cast<const uint8_t*>(PSK_ID_LABEL.data()), PSK_ID_LABEL.size());
    h.update(psk, len);
    auto digest = h.finalize();
    if (!h.ok()) {
        return std::nullopt;
    }
    return b64url_encode(digest.data(), digest.size());
}

std::string psk_id_for(const std::array<uint8_t, NOISE_PSK_SIZE>& psk) {
    // psk.size() == NOISE_PSK_SIZE always for a fixed-size array, so the only way the
    // pointer/length overload can fail here is if the underlying SHA-256 computation itself
    // failed. This overload's non-optional return type is depended on throughout the tree
    // (record store, tests) as a plain std::string, so there is no safe value to
    // return on failure; fail loudly rather than silently deriving a psk_id from a
    // zero digest (matching the CSPRNG-failure precedent in platform_random_bytes()).
    auto result = psk_id_for(psk.data(), psk.size());
    if (!result.has_value()) {
        abort();
    }
    return result.value();
}

// ============================================================================
// Peer ids
// ============================================================================

std::optional<std::array<uint8_t, X25519_KEY_SIZE>> public_key_from_peer_id(
    const std::string& peer_id) {
    if (peer_id.size() != PEER_ID_SIZE) {
        return std::nullopt;
    }
    auto decoded = b64url_decode(peer_id);
    if (!decoded.has_value() || decoded->size() != X25519_KEY_SIZE) {
        return std::nullopt;
    }
    // The last character carries 2 unused bits; a nonzero pair decodes to the same key.
    if (b64url_encode(decoded->data(), decoded->size()) != peer_id) {
        return std::nullopt;
    }
    std::array<uint8_t, X25519_KEY_SIZE> key{};
    std::copy(decoded->begin(), decoded->end(), key.begin());
    return key;
}

// ============================================================================
// Identity
// ============================================================================

Identity::~Identity() {
    // public_bytes is not secret and is deliberately left alone.
    secure_zero(this->private_bytes.data(), this->private_bytes.size());
}

std::string Identity::peer_id() const {
    return b64url_encode(this->public_bytes.data(), this->public_bytes.size());
}

std::optional<Identity> Identity::generate() {
    // Use noise-c's DHState to generate a fresh Curve25519 keypair.
    // This routes through noise-c's reference backend on both platforms.
    DhState dh;
    if (!dh.valid()) {
        return std::nullopt;
    }
    int err = noise_dhstate_generate_keypair(dh.get());
    if (err != NOISE_ERROR_NONE) {
        return std::nullopt;
    }
    size_t priv_len = noise_dhstate_get_private_key_length(dh.get());
    size_t pub_len = noise_dhstate_get_public_key_length(dh.get());
    if (priv_len != X25519_KEY_SIZE || pub_len != X25519_KEY_SIZE) {
        return std::nullopt;
    }

    Identity id{};
    err = noise_dhstate_get_keypair(dh.get(), id.private_bytes.data(), priv_len,
                                    id.public_bytes.data(), pub_len);
    if (err != NOISE_ERROR_NONE) {
        return std::nullopt;
    }
    return id;
}

std::optional<Identity> Identity::from_private_bytes(const uint8_t* priv, size_t len) {
    if (len != X25519_KEY_SIZE) {
        return std::nullopt;
    }

    DhState dh;
    if (!dh.valid()) {
        return std::nullopt;
    }

    int err = noise_dhstate_set_keypair_private(dh.get(), priv, len);
    if (err != NOISE_ERROR_NONE) {
        return std::nullopt;
    }

    size_t pub_len = noise_dhstate_get_public_key_length(dh.get());
    if (pub_len != X25519_KEY_SIZE) {
        return std::nullopt;
    }

    Identity id{};
    std::memcpy(id.private_bytes.data(), priv, X25519_KEY_SIZE);
    err = noise_dhstate_get_public_key(dh.get(), id.public_bytes.data(), pub_len);
    if (err != NOISE_ERROR_NONE) {
        return std::nullopt;
    }
    return id;
}

std::optional<Identity> Identity::from_private_bytes(
    const std::array<uint8_t, X25519_KEY_SIZE>& priv) {
    // Delegates to the pointer/length overload. The length always matches X25519_KEY_SIZE, but
    // the underlying DH-state failure paths (allocation failure, keypair-length mismatch) can
    // still fail, so this must propagate std::nullopt rather than assume success.
    return from_private_bytes(priv.data(), priv.size());
}

}  // namespace sendspin
