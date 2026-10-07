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

/// @file persistence_codec.h
/// @brief Storage-format codec for the persistence structs in sendspin/config.h
///
/// `SendspinPersistenceProvider` (sendspin/client.h) is a plain blob store. The library is the
/// only caller of this codec: for the record slot keys (`persistence_keys::record_slot_key()`)
/// and `PAIRING_PSK` it turns `SendspinPairingRecord` / `SendspinPairingPsk` into the binary blob
/// a provider stores, and back. A provider must not parse these blobs itself. It is public so a
/// custom provider or a test can inspect or seed that content in the same format.
///
/// This is a storage codec, independent of the Sendspin protocol wire format.
///
/// ## Storage format
///
/// Both blobs have a fixed size, and neither stores `psk_id`: it is a pure function of the PSK
/// (connection.md "Pre-Shared Key"), so decoding derives it.
///
/// - Record, `persistence_keys::RECORD_SLOT_SIZE` (64) bytes: the 32-byte PSK, then the 32-byte
///   X25519 public key the record's `server_id` encodes. A slot holding no record stores 64 zero
///   bytes, which `decode_pairing_record()` rejects like any unusable record.
/// - Pairing PSK, `persistence_keys::PAIRING_PSK_SIZE` (32) bytes: the PSK.
///
/// ## Decode semantics
///
/// `decode_pairing_record()` / `decode_pairing_psk()` return `std::nullopt` for a blob of the
/// wrong size or an all-zero PSK. `base64url_decode()` follows RFC 4648 section 5: encode never
/// pads, decode tolerates padding, and any character outside the base64url alphabet makes it
/// return `std::nullopt`.
///
/// ## Keyspace
///
/// Storage keys are the fixed constants in `persistence_keys` (sendspin/persistence_keys.h), not a
/// provider's choice.
///
/// Long-term records are stored one per key, under the slot keys
/// `persistence_keys::record_slot_key()` names, with `persistence_keys::RECORD_ORDER` holding
/// their eviction order. A pairing or a revocation rewrites one slot instead of every record,
/// and a corrupt slot costs one record instead of the store. Slot numbers keep the keys short:
/// `psk_id` (43 characters) would not fit an NVS key at all.

#pragma once

#include "sendspin/config.h"
#include "sendspin/persistence_keys.h"

#include <array>
#include <cstddef>
#include <cstdint>
#include <optional>
#include <string>
#include <string_view>
#include <vector>

namespace sendspin {

/// @brief Encodes a pairing record to its storage format.
/// @return The blob, or std::nullopt when `server_id` is not the base64url encoding of a 32-byte
///         public key, the only form a record can store it in.
std::optional<std::array<uint8_t, persistence_keys::RECORD_SLOT_SIZE>> encode_pairing_record(
    const SendspinPairingRecord& r);

/// @brief Decodes a pairing record from its storage format, deriving its `psk_id`.
/// @return The decoded record, or std::nullopt for a blob of the wrong size or an all-zero PSK.
std::optional<SendspinPairingRecord> decode_pairing_record(const uint8_t* data, size_t len);

/// @brief Encodes the accepted Pairing PSK to its storage format.
std::array<uint8_t, persistence_keys::PAIRING_PSK_SIZE> encode_pairing_psk(
    const SendspinPairingPsk& p);

/// @brief Decodes the accepted Pairing PSK from its storage format, deriving its `psk_id`.
/// @return The decoded Pairing PSK, or std::nullopt for a blob of the wrong size or an all-zero
///         PSK.
std::optional<SendspinPairingPsk> decode_pairing_psk(const uint8_t* data, size_t len);

/// @brief Encodes bytes to base64url, no `=` padding (RFC 4648 section 5).
/// @return ASCII string using only `A-Z a-z 0-9 - _`.
std::string base64url_encode(const uint8_t* data, size_t len);

/// @brief Decodes base64url, tolerating missing or present `=` padding (RFC 4648 section 5).
/// @return The decoded bytes, or std::nullopt if s contains a character outside the base64url
///         alphabet.
std::optional<std::vector<uint8_t>> base64url_decode(std::string_view s);

}  // namespace sendspin
