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

#include "noise_session.h"

#include "crypto/constants.h"
#include "crypto/keys.h"
#include "platform/crypto.h"
#include "platform/logging.h"

// noise-c is a C library; wrap in extern "C" to avoid name-mangling issues.
extern "C" {
#include <noise/protocol/buffer.h>
#include <noise/protocol/cipherstate.h>
#include <noise/protocol/constants.h>
#include <noise/protocol/dhstate.h>
#include <noise/protocol/handshakestate.h>
}

#include <cstring>

static const char* const TAG = "sendspin.noise_session";

namespace sendspin {

// ============================================================================
// Destructor / Move
// ============================================================================

NoiseSession::~NoiseSession() {
    if (this->hs_) {
        noise_handshakestate_free(this->hs_);
        this->hs_ = nullptr;
    }
    if (this->send_cipher_) {
        noise_cipherstate_free(this->send_cipher_);
        this->send_cipher_ = nullptr;
    }
    if (this->recv_cipher_) {
        noise_cipherstate_free(this->recv_cipher_);
        this->recv_cipher_ = nullptr;
    }
}

NoiseSession::NoiseSession(NoiseSession&& other) noexcept
    : handshake_hash_(other.handshake_hash_),
      hs_(other.hs_),
      recv_cipher_(other.recv_cipher_),
      send_cipher_(other.send_cipher_) {
    other.hs_ = nullptr;
    other.send_cipher_ = nullptr;
    other.recv_cipher_ = nullptr;
}

NoiseSession& NoiseSession::operator=(NoiseSession&& other) noexcept {
    if (this != &other) {
        if (this->hs_) {
            noise_handshakestate_free(this->hs_);
        }
        if (this->send_cipher_) {
            noise_cipherstate_free(this->send_cipher_);
        }
        if (this->recv_cipher_) {
            noise_cipherstate_free(this->recv_cipher_);
        }
        this->hs_ = other.hs_;
        this->send_cipher_ = other.send_cipher_;
        this->recv_cipher_ = other.recv_cipher_;
        this->handshake_hash_ = other.handshake_hash_;
        other.hs_ = nullptr;
        other.send_cipher_ = nullptr;
        other.recv_cipher_ = nullptr;
    }
    return *this;
}

// ============================================================================
// Internal helper: build a configured handshakestate
// ============================================================================

/// @brief Allocate and configure a KKpsk2 responder handshakestate.
/// Returns nullptr on any error; the caller must free on failure.
static NoiseHandshakeState* build_responder_hs(const std::string& suite_name,
                                               const uint8_t* local_priv, const uint8_t* remote_pub,
                                               const uint8_t* prologue, size_t prologue_len,
                                               const uint8_t* psk) {
    NoiseHandshakeState* hs = nullptr;
    const int err = noise_handshakestate_new_by_name(&hs, suite_name.c_str(), NOISE_ROLE_RESPONDER);
    if (err != NOISE_ERROR_NONE) {
        SS_LOGE(TAG, "noise_handshakestate_new_by_name failed for suite %s (error 0x%04x)",
                suite_name.c_str(), err);
        return nullptr;
    }

    // Local static keypair
    NoiseDHState* local_dh = noise_handshakestate_get_local_keypair_dh(hs);
    if (noise_dhstate_set_keypair_private(local_dh, local_priv, X25519_KEY_SIZE) !=
        NOISE_ERROR_NONE) {
        SS_LOGE(TAG, "noise_dhstate_set_keypair_private failed");
        noise_handshakestate_free(hs);
        return nullptr;
    }

    // Remote static public key
    NoiseDHState* remote_dh = noise_handshakestate_get_remote_public_key_dh(hs);
    if (noise_dhstate_set_public_key(remote_dh, remote_pub, X25519_KEY_SIZE) != NOISE_ERROR_NONE) {
        SS_LOGE(TAG, "noise_dhstate_set_public_key failed");
        noise_handshakestate_free(hs);
        return nullptr;
    }

    // Prologue
    if (prologue_len > 0) {
        if (noise_handshakestate_set_prologue(hs, prologue, prologue_len) != NOISE_ERROR_NONE) {
            SS_LOGE(TAG, "noise_handshakestate_set_prologue failed");
            noise_handshakestate_free(hs);
            return nullptr;
        }
    }

    // PSK: optional at this point. noise-c allows the PSK to be set any time before the
    // "psk" token is processed (KKpsk2 mixes it only in msg2), so a null psk here just
    // defers binding to a later NoiseSession::set_psk call.
    if (psk) {
        if (noise_handshakestate_set_pre_shared_key(hs, psk, NOISE_PSK_SIZE) != NOISE_ERROR_NONE) {
            SS_LOGE(TAG, "noise_handshakestate_set_pre_shared_key failed");
            noise_handshakestate_free(hs);
            return nullptr;
        }
    }

    if (noise_handshakestate_start(hs) != NOISE_ERROR_NONE) {
        SS_LOGE(TAG, "noise_handshakestate_start failed");
        noise_handshakestate_free(hs);
        return nullptr;
    }

    return hs;
}

// ============================================================================
// Factory
// ============================================================================

std::optional<NoiseSession> NoiseSession::as_responder(const std::string& suite_name,
                                                       const uint8_t* local_priv,
                                                       const uint8_t* remote_pub,
                                                       const uint8_t* prologue, size_t prologue_len,
                                                       const uint8_t* psk) {
    NoiseHandshakeState* hs =
        build_responder_hs(suite_name, local_priv, remote_pub, prologue, prologue_len, psk);
    if (!hs) {
        return std::nullopt;
    }

    NoiseSession session;
    session.hs_ = hs;
    return session;
}

// ============================================================================
// Handshake
// ============================================================================

std::vector<uint8_t> NoiseSession::read_msg1(const uint8_t* msg1_bytes, size_t msg1_len) {
    if (!this->hs_) {
        SS_LOGE(TAG, "read_msg1: handshake state is null");
        return {};
    }

    if (noise_handshakestate_get_action(this->hs_) != NOISE_ACTION_READ_MESSAGE) {
        SS_LOGE(TAG, "read_msg1: unexpected handshake action (not READ_MESSAGE)");
        return {};
    }

    // Payload buffer large enough for any handshake payload; see
    // MAX_HANDSHAKE_MESSAGE_BYTES in crypto/constants.h.
    std::vector<uint8_t> payload_buf(MAX_HANDSHAKE_MESSAGE_BYTES);

    // Input buffer: noise-c needs a mutable copy
    std::vector<uint8_t> in_buf(msg1_bytes, msg1_bytes + msg1_len);

    NoiseBuffer msg_in;
    NoiseBuffer payload_out;
    noise_buffer_set_input(msg_in, in_buf.data(), in_buf.size());
    noise_buffer_set_output(payload_out, payload_buf.data(), payload_buf.size());

    int err = noise_handshakestate_read_message(this->hs_, &msg_in, &payload_out);
    if (err != NOISE_ERROR_NONE) {
        SS_LOGW(TAG, "read_msg1: noise_handshakestate_read_message failed (err=%d)", err);
        return {};
    }

    payload_buf.resize(payload_out.size);
    return payload_buf;
}

bool NoiseSession::set_psk(const uint8_t* psk) {
    if (!this->hs_) {
        SS_LOGE(TAG, "set_psk: handshake state is null");
        return false;
    }

    if (noise_handshakestate_set_pre_shared_key(this->hs_, psk, NOISE_PSK_SIZE) !=
        NOISE_ERROR_NONE) {
        SS_LOGE(TAG, "set_psk: noise_handshakestate_set_pre_shared_key failed");
        return false;
    }

    return true;
}

bool NoiseSession::write_msg2_and_split(std::vector<uint8_t>& msg2_out) {
    if (!this->hs_) {
        SS_LOGE(TAG, "write_msg2_and_split: handshake state is null");
        return false;
    }

    if (noise_handshakestate_get_action(this->hs_) != NOISE_ACTION_WRITE_MESSAGE) {
        SS_LOGE(TAG, "write_msg2_and_split: unexpected handshake action (not WRITE_MESSAGE)");
        return false;
    }

    // msg2 plaintext payload: `{}` (2 bytes UTF-8)
    const uint8_t msg2_payload[] = {'{', '}'};
    NoiseBuffer payload_in;
    noise_buffer_set_input(payload_in, const_cast<uint8_t*>(msg2_payload), sizeof(msg2_payload));

    // Output buffer: handshake message 2 is small; see MAX_HANDSHAKE_MESSAGE_BYTES in
    // crypto/constants.h.
    msg2_out.resize(MAX_HANDSHAKE_MESSAGE_BYTES);

    NoiseBuffer msg_out;
    noise_buffer_set_output(msg_out, msg2_out.data(), msg2_out.size());

    int err = noise_handshakestate_write_message(this->hs_, &msg_out, &payload_in);
    if (err != NOISE_ERROR_NONE) {
        SS_LOGE(TAG, "write_msg2_and_split: noise_handshakestate_write_message failed (err=%d)",
                err);
        return false;
    }
    msg2_out.resize(msg_out.size);

    if (noise_handshakestate_get_action(this->hs_) != NOISE_ACTION_SPLIT) {
        SS_LOGE(TAG, "write_msg2_and_split: expected SPLIT action after write, got %d",
                noise_handshakestate_get_action(this->hs_));
        return false;
    }

    // Retrieve the Noise handshake hash `h` BEFORE split.
    // noise_handshakestate_get_handshake_hash copies up to max_len bytes; our
    // suites use SHA-256 so the hash is exactly 32 bytes.
    noise_handshakestate_get_handshake_hash(this->hs_, this->handshake_hash_.data(),
                                            this->handshake_hash_.size());

    err = noise_handshakestate_split(this->hs_, &this->send_cipher_, &this->recv_cipher_);
    if (err != NOISE_ERROR_NONE) {
        SS_LOGE(TAG, "write_msg2_and_split: noise_handshakestate_split failed (err=%d)", err);
        return false;
    }

    // Free the handshakestate. Transport mode is now active.
    noise_handshakestate_free(this->hs_);
    this->hs_ = nullptr;

    return true;
}

// ============================================================================
// Transport
// ============================================================================

size_t NoiseSession::encrypt(uint8_t* plaintext, size_t len, size_t capacity) {
    if (!this->send_cipher_) {
        SS_LOGE(TAG, "encrypt: transport not ready");
        return 0;
    }
    if (capacity < len + AEAD_TAG_SIZE) {
        SS_LOGE(TAG, "encrypt: buffer too small (need %zu, have %zu)", len + AEAD_TAG_SIZE,
                capacity);
        return 0;
    }

    NoiseBuffer buf;
    noise_buffer_set_inout(buf, plaintext, len, capacity);
    int err = noise_cipherstate_encrypt(this->send_cipher_, &buf);
    if (err != NOISE_ERROR_NONE) {
        SS_LOGE(TAG, "encrypt: noise_cipherstate_encrypt failed (err=%d)", err);
        return 0;
    }
    return buf.size;
}

size_t NoiseSession::decrypt(uint8_t* ciphertext, size_t len) {
    if (!this->recv_cipher_) {
        SS_LOGE(TAG, "decrypt: transport not ready");
        return 0;
    }

    NoiseBuffer buf;
    noise_buffer_set_inout(buf, ciphertext, len, len);
    int err = noise_cipherstate_decrypt(this->recv_cipher_, &buf);
    if (err != NOISE_ERROR_NONE) {
        SS_LOGW(TAG, "decrypt: noise_cipherstate_decrypt failed (err=%d)", err);
        return 0;
    }
    return buf.size;
}

}  // namespace sendspin
