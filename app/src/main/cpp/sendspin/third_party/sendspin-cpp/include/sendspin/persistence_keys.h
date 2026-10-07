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

/// @file persistence_keys.h
/// @brief The storage keys and blob sizes `SendspinPersistenceProvider` (sendspin/client.h) is
/// called with. Separate from client.h so a provider or the codec can use them without the
/// client's API.

#pragma once

#include <cstddef>
#include <string>

namespace sendspin {

/// @brief Fixed, library-owned keyspace for SendspinPersistenceProvider.
///
/// Every key is at most 12 characters, comfortably under the 15-character NVS key limit.
/// Providers are pure byte stores: they must not parse or reinterpret these values.
///
/// Every blob has a fixed size, given by the `*_SIZE` constant beside its key (`RECORD_ORDER`'s
/// depends on the configured record count). The library writes exactly that many bytes and
/// treats a stored blob of any other length as absent. Multi-byte integers are in the device's
/// native byte order: a blob is only ever read back by the device that wrote it.
///
/// A record slot key (`record_slot_key()`) and `PAIRING_PSK` hold the binary layouts of the
/// codec in `sendspin/persistence_codec.h`; the other keys hold raw bytes, as each constant's
/// comment describes.
namespace persistence_keys {

/// Raw bytes: the static X25519 private key.
inline constexpr const char* KEYPAIR = "keypair";
/// Size of the `KEYPAIR` blob.
inline constexpr size_t KEYPAIR_SIZE = 32;

/// Prefix of the per-slot record keys; see `record_slot_key()`.
inline constexpr const char* RECORD_SLOT_PREFIX = "rec_";
/// Size of a record slot blob: one `SendspinPairingRecord` as the codec encodes it.
inline constexpr size_t RECORD_SLOT_SIZE = 64;

/// Raw bytes: the slot numbers of the occupied record slots, least recently used first, one byte
/// per slot, then `0xFF` in every remaining position. The blob is exactly
/// `SendspinClientConfig::max_pairing_records` bytes, after the library raises that to at least 5
/// and lowers it to at most 255. It decides which record is evicted when a pairing arrives at a
/// full store, so it is rewritten whenever that order changes. A byte naming no stored record is
/// ignored on load, and a stored record this blob does not name sorts after the ones it does.
/// Being advisory, it is the one blob read at any length.
inline constexpr const char* RECORD_ORDER = "rec_order";

/// @brief Key of one long-term record slot: `RECORD_SLOT_PREFIX` followed by the decimal slot
/// number, for example `rec_0`.
///
/// Each slot holds one `SendspinPairingRecord` as a codec blob (`encode_pairing_record()` /
/// `decode_pairing_record()`), or `RECORD_SLOT_SIZE` zero bytes when the slot is free. Only the
/// slot that changed is written, so a pairing or a revocation costs one record-sized write rather
/// than a rewrite of every record. Slot numbers run from 0 to
/// `SendspinClientConfig::max_pairing_records - 1`; a slot key is absent until that slot is first
/// filled.
/// @param slot The slot number.
/// @return The storage key for that slot.
inline std::string record_slot_key(size_t slot) {
    return std::string(RECORD_SLOT_PREFIX) + std::to_string(slot);
}

/// Codec blob: the accepted `SendspinPairingPsk` (`encode_pairing_psk()` / `decode_pairing_psk()`).
/// `SendspinClientConfig::pairing_psk` outranks it.
inline constexpr const char* PAIRING_PSK = "pairing_psk";
/// Size of the `PAIRING_PSK` blob.
inline constexpr size_t PAIRING_PSK_SIZE = 32;

/// Raw bytes: the X25519 public key of the last-playback server (connection.md "Multiple
/// servers"), the key its base64url `server_id` encodes.
inline constexpr const char* LAST_PLAYED = "last_played";
/// Size of the `LAST_PLAYED` blob.
inline constexpr size_t LAST_PLAYED_SIZE = 32;

/// Raw `uint16_t`: the player's output delay in milliseconds.
inline constexpr const char* OUTPUT_DELAY = "output_delay";
/// Size of the `OUTPUT_DELAY` blob.
inline constexpr size_t OUTPUT_DELAY_SIZE = 2;

}  // namespace persistence_keys

}  // namespace sendspin
