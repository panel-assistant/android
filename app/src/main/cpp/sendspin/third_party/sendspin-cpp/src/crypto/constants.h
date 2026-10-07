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

/// @file constants.h
/// @brief Sendspin Noise-protocol constants: Sentinel PSK, PSK-ID label, suite names.
///
/// The values are fixed by connection.md "Pre-Shared Key" and "Cipher Suites".  Any change
/// to these values is a breaking wire-protocol change.

#pragma once

#include "sendspin/config.h"

#include <array>
#include <cstddef>
#include <cstdint>
#include <string>
#include <string_view>

namespace sendspin {

// ============================================================================
// PSK constants
// ============================================================================

/// @brief Size of a Noise pre-shared key in bytes (Curve25519 key size).
static constexpr size_t NOISE_PSK_SIZE = 32;

/// @brief Label mixed into SHA-256 when deriving a PSK identifier.
/// Literal UTF-8 bytes, no NUL terminator included in the hash input.
static constexpr std::string_view PSK_ID_LABEL{"sendspin-psk-id-v1"};

/// @brief The Sentinel PSK: SHA-256("sendspin-sentinel-psk-v1").
/// This is a public constant; it authenticates nothing on its own.
/// Expected hex: 1b5e24dbc1aed95fc2a5a338a90c05df44bd10f5ec1f4cd66cbf86272767b9d3
extern const std::array<uint8_t, NOISE_PSK_SIZE> SENTINEL_PSK;

/// @brief The psk_id for the Sentinel PSK: base64url(SHA-256(PSK_ID_LABEL || SENTINEL_PSK)).
/// Expected value: "GFsV9tLaSQm9HcFWpKsgYQOr7wFTvNUtkmFwuVz3zoo"
/// Computed once at startup by constants.cpp.
extern const std::string SENTINEL_PSK_ID;

// ============================================================================
// Cipher suite name (passed to noise_protocol_name_to_id)
// ============================================================================

/// @brief The only cipher suite the client proposes: ChaCha20-Poly1305 (constant-time on
/// every target, no AES hardware dependency). The Sendspin client builds its Noise session from
/// its own client/init proposal and never reads server/init's suite field back, so a single
/// suite is sufficient; there is nothing to negotiate.
static constexpr std::string_view NOISE_SUITE_CHACHAPOLY{"Noise_KKpsk2_25519_ChaChaPoly_SHA256"};

// ============================================================================
// Transport framing constants
// ============================================================================

/// @brief Core protocol version carried in client/init and server/init.
static constexpr int PROTOCOL_VERSION = 1;

/// @brief Noise transport message limit minus the 16-byte AEAD tag.
/// Plaintext includes the leading type byte, so usable payload is one byte less.
static constexpr size_t MAX_TRANSPORT_PLAINTEXT = 65535 - 16;  // = 65519

/// @brief Buffer size for a single Noise handshake control message (msg1 decrypted payload,
/// msg2 ciphertext): the spec sets no bound, but the payload it defines is small (psk_id JSON,
/// ~60 bytes). 512 leaves room for future fields without paying for a buffer sized like
/// transport-mode traffic. noise-c bounds-checks against this capacity and returns an error
/// rather than overflowing it if a peer's message does not fit (verified against
/// noise_handshakestate_read_message()'s payload->max_size check), so a legitimate handshake
/// message exceeding this size fails the handshake rather than corrupting memory.
static constexpr size_t MAX_HANDSHAKE_MESSAGE_BYTES = 512;

/// @brief Per-connection reassembly buffer cap: 1 MiB.
/// Artwork is never fragmented: roles/artwork/v1.md "Artwork (Binary)" caps a message at
/// MAX_TRANSPORT_PLAINTEXT so it fits one Noise transport message. The message that can
/// legitimately need fragmentation is the player audio chunk, bounded by the buffer_capacity
/// this client advertises (roles/player/v1.md "Player Buffer Accounting"). The cap bounds what
/// an admitted peer can force this connection to reassemble; before admission the much smaller
/// MAX_PRE_ADMISSION_REASSEMBLED_MESSAGE_BYTES applies instead. The buffer behind it grows
/// geometrically and keeps its capacity for the connection's life, so the worst-case steady-state
/// pin is the cap plus the orig_type byte, which reasm_reserve() clamps the growth to.
static constexpr size_t MAX_REASSEMBLED_MESSAGE_BYTES = 1UL * 1024UL * 1024UL;

/// @brief Reassembly cap that applies until the connection is admitted: 16 KiB.
/// What legitimately precedes admission is server/hello, server/activate and pairing JSON, none of
/// which approaches one steady-state JSON message (SendspinClientConfig::DEFAULT_JSON_ARENA_SIZE).
/// Eight of those is far above any single one of them, while denying a peer holding only the
/// Sentinel PSK the 1 MiB it could otherwise pin per nursery slot. The traffic that makes
/// MAX_REASSEMBLED_MESSAGE_BYTES as large as it is (player audio chunks, artwork parts) is binary,
/// and reaches a role only once the connection is admitted (SendspinClient::
/// process_binary_message()).
static constexpr size_t MAX_PRE_ADMISSION_REASSEMBLED_MESSAGE_BYTES =
    8 * SendspinClientConfig::DEFAULT_JSON_ARENA_SIZE;

// ============================================================================
// Binary message type bytes (first byte of decrypted plaintext)
// ============================================================================

/// @brief Type byte: the plaintext is a JSON body (UTF-8, strip the type byte).
static constexpr uint8_t MSG_TYPE_JSON_BODY = 0;

/// @brief Type byte: one fragment of a larger message (messaging.md "Fragmentation").
/// A fragment frame is [1][flags][orig_type][data] when it opens a message and [1][flags][data]
/// otherwise.
static constexpr uint8_t MSG_TYPE_FRAGMENT = 1;

/// @brief Fragment flags bit 0: this is the last fragment of the message.
static constexpr uint8_t FRAGMENT_FLAG_LAST = 0x01;

/// @brief Fragment flags bit 1: this is the first fragment of the message. A single-fragment
/// message sets both bits.
static constexpr uint8_t FRAGMENT_FLAG_FIRST = 0x02;

/// @brief Fragment flags bits 2-7, which messaging.md "Fragmentation" reserves and requires to
/// be zero. A frame setting any of them is a malformed sequence.
static constexpr uint8_t FRAGMENT_FLAGS_RESERVED = 0xFC;

/// @brief Fragment header size in bytes: [1][flags] for a continuation frame, plus the
/// orig_type byte for a frame carrying FRAGMENT_FLAG_FIRST.
static constexpr size_t FRAGMENT_CONT_HEADER_SIZE = 2;
static constexpr size_t FRAGMENT_FIRST_HEADER_SIZE = FRAGMENT_CONT_HEADER_SIZE + 1;

/// @brief First and last binary message ID that messaging.md "Binary Message ID Structure"
/// reserves for future use, so a fragmented message claiming one as its orig_type is an
/// unimplemented message the receiver ignores.
static constexpr uint8_t MSG_TYPE_RESERVED_FIRST = 2;
static constexpr uint8_t MSG_TYPE_RESERVED_LAST = 3;

}  // namespace sendspin
