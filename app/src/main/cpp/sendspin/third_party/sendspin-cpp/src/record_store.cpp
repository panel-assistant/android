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

#include "record_store.h"

#include "crypto/constants.h"
#include "crypto/keys.h"
#include "platform/crypto.h"
#include "platform/logging.h"
#include "sendspin/persistence_codec.h"

#include <algorithm>
#include <cassert>
#include <cstddef>
#include <cstring>
#include <string>
#include <utility>

static const char* const TAG = "sendspin.record_store";

namespace sendspin {

namespace {

/// @brief Shared load -> decode -> warn-on-failure -> secure_zero(blob) shape used by the
/// record-slot and PAIRING_PSK loaders below.
/// @return The decoded value, or nullopt. The raw blob is wiped on both paths.
template <typename T>
std::optional<T> load_decode_wipe(SendspinPersistenceProvider& provider, const char* key,
                                  std::optional<T> (*decode)(const uint8_t*, size_t),
                                  const char* decode_fail_suffix) {
    auto blob = provider.load_blob(key);
    if (!blob.has_value()) {
        return std::nullopt;
    }
    // An all-zero blob is "nothing stored here": it is how a freed record slot is written.
    const bool all_zero = std::all_of(blob->begin(), blob->end(), [](uint8_t b) { return b == 0; });
    std::optional<T> decoded;
    if (!all_zero) {
        decoded = decode(blob->data(), blob->size());
        if (!decoded.has_value()) {
            SS_LOGW(TAG, "Stored \"%s\" blob failed to decode; %s", key, decode_fail_suffix);
        }
    }
    secure_zero(blob->data(), blob->size());
    return decoded;
}

}  // namespace

// ============================================================================
// Constructor
// ============================================================================

RecordStore::RecordStore(SendspinPersistenceProvider* provider, const SendspinClientConfig& config)
    : provider_(provider),
      max_records_(std::clamp(config.max_pairing_records, MIN_MAX_RECORDS, MAX_MAX_RECORDS)) {
    // The provider is a pure byte store, so decoding happens entirely on this side of the
    // interface.
    if (this->provider_ != nullptr) {
        this->load_records_from_provider();
    }

    if (config.pairing_psk.has_value()) {
        this->adopt_configured_pairing_psk(config.pairing_psk.value());
    } else {
        if (this->provider_ != nullptr) {
            this->load_pairing_psk_from_provider();
        }
        this->provision_pairing_psk_if_needed();
    }
}

// ============================================================================
// Construction helpers
// ============================================================================

void RecordStore::load_records_from_provider() {
    // One record per slot key, so a slot that fails to decode (corrupt bytes, or a record the
    // codec rejects) costs only that record: the others are separate keys. An absent or zeroed
    // blob is a free slot. Slots at or above the configured cap are not read, so lowering the cap
    // between boots orphans the records above it rather than loading them.
    //
    // load_decode_wipe() logs the warning and wipes the raw blob on both paths; the raw blob holds
    // the PSK (even when it failed to decode), mirroring the save-path wipe in save_slot_write().
    for (size_t slot = 0; slot < this->max_records_; ++slot) {
        const std::string key = persistence_keys::record_slot_key(slot);
        auto decoded = load_decode_wipe<SendspinPairingRecord>(
            *this->provider_, key.c_str(), decode_pairing_record, "ignoring that record");
        if (!decoded.has_value()) {
            continue;
        }
        // Two slots carrying the same psk_id would leave a revoked credential resolving: a
        // removal erases the first match from records_ and empties only that slot, so the
        // duplicate survives both the boot and the flush. Lowering max_pairing_records and
        // raising it again can produce one, so the lower slot wins and the later one is cleared.
        // Two slots bound to one server_id under different psk_ids are left alone: both are
        // credentials the server may still present, and the retire in
        // store_record_superseding() empties the extra slot at the next pairing with that server.
        if (this->record_by_psk_id(decoded->psk_id) != nullptr) {
            SS_LOGW(TAG, "Clearing \"%s\": psk_id %s is already stored in a lower slot",
                    key.c_str(), decoded->psk_id.c_str());
            // Owed a zeroed write, or the duplicate would come back at every boot and outlive a
            // revocation of the record it shadows. Constructor: no other thread holds the store.
            this->mark_slot_dirty_locked(static_cast<uint8_t>(slot));
            continue;
        }
        this->records_.push_back(
            StoredRecord{std::move(decoded.value()), static_cast<uint8_t>(slot)});
    }
    this->load_record_order_from_provider();
}

void RecordStore::load_record_order_from_provider() {
    auto blob = this->provider_->load_blob(persistence_keys::RECORD_ORDER);
    if (!blob.has_value()) {
        return;
    }
    // The blob names the occupied slots least recently used first, one byte each, which is the
    // order records_ is kept in and evict_one_locked() reads. A byte naming no loaded record
    // (stale, or a slot that failed to decode) is skipped, and a loaded record the blob does not
    // name sorts after the ones it does, in slot order: a damaged order blob costs recency, not
    // records.
    std::vector<StoredRecord> ordered;
    ordered.reserve(this->records_.size());
    for (uint8_t slot : blob.value()) {
        auto it = std::find_if(this->records_.begin(), this->records_.end(),
                               [slot](const StoredRecord& s) { return s.slot == slot; });
        if (it == this->records_.end()) {
            continue;
        }
        ordered.push_back(std::move(*it));
        this->records_.erase(it);
    }
    for (auto& remaining : this->records_) {
        ordered.push_back(std::move(remaining));
    }
    this->records_ = std::move(ordered);
}

void RecordStore::load_pairing_psk_from_provider() {
    // The raw PSK, like the record slots above; load_decode_wipe() wipes it on both paths.
    auto decoded = load_decode_wipe<SendspinPairingPsk>(
        *this->provider_, persistence_keys::PAIRING_PSK, decode_pairing_psk, "ignoring");
    // Treated as absent, like a blob that fails to decode, so a fresh one is generated and
    // persisted over it.
    if (decoded.has_value() && !is_usable_pairing_psk(decoded->psk)) {
        SS_LOGW(TAG, "Stored Pairing PSK is the published Sentinel PSK; ignoring it");
        decoded.reset();
    }
    this->pairing_psk_ = std::move(decoded);
}

bool RecordStore::is_usable_pairing_psk(const std::array<uint8_t, NOISE_PSK_SIZE>& psk) {
    const std::array<uint8_t, NOISE_PSK_SIZE> zero{};
    return !constant_time_equal(psk.data(), zero.data(), psk.size()) &&
           !constant_time_equal(psk.data(), SENTINEL_PSK.data(), psk.size());
}

void RecordStore::adopt_configured_pairing_psk(const SendspinPsk& configured) {
    // A stored Pairing PSK is neither read nor touched.
    SendspinPairingPsk adopted;
    adopted.psk = configured.bytes;
    adopted.psk_id = psk_id_for(adopted.psk);
    SS_LOGI(TAG, "Using the configured Sendspin Pairing PSK: %s", adopted.psk_id.c_str());
    this->pairing_psk_ = std::move(adopted);
}

void RecordStore::provision_pairing_psk_if_needed() {
    // pairing_psk is the one pairing method every client must implement (messaging.md
    // "client/hello"), so a client with no Pairing PSK would advertise a method it cannot
    // complete. The operator transfers the generated key to a server as a pairing token
    // (SendspinClient::pairing_token()). It is stable across reboots once persisted; if
    // persistence fails it is RAM-only for this boot, so a token printed then will not survive a
    // reboot.
    if (!this->pairing_psk_.has_value()) {
        std::array<uint8_t, NOISE_PSK_SIZE> psk{};
        platform_random_bytes(psk.data(), psk.size());

        SendspinPairingPsk provisioned;
        provisioned.psk_id = psk_id_for(psk);
        provisioned.psk = psk;

        bool psk_persisted = true;
        if (this->provider_ != nullptr) {
            auto encoded = encode_pairing_psk(provisioned);
            psk_persisted = this->provider_->save_blob(persistence_keys::PAIRING_PSK,
                                                       encoded.data(), encoded.size()) &&
                            this->provider_->commit();
            // The encoded blob is the PSK; wipe it now that save_blob() has its own copy (or has
            // failed).
            secure_zero(encoded.data(), encoded.size());
        }
        if (psk_persisted) {
            SS_LOGI(TAG, "Provisioned Sendspin Pairing PSK: %s", provisioned.psk_id.c_str());
        } else {
            SS_LOGW(TAG,
                    "Provisioned Sendspin Pairing PSK %s but failed to persist it; a pairing "
                    "token printed now will not survive a reboot",
                    provisioned.psk_id.c_str());
        }
        this->pairing_psk_ = std::move(provisioned);
    }
}

// ============================================================================
// PSK resolution
// ============================================================================

std::optional<ResolvedPsk> RecordStore::resolve_by_psk_id(const std::string& psk_id,
                                                          PskCategory category) const {
    // No lock: this runs on the protocol task, which makes every change to records_, and the main
    // loop's persist_records() only reads records_ (see the persistence locking note below).
    // Only the declared category's candidates are searched (connection.md "Pre-Shared Key"): the
    // same psk_id under another category is a lookup miss, which keeps a server from using, say, a
    // long-term PSK as though it were the Pairing PSK and inheriting that category's activities.
    switch (category) {
        case PskCategory::LONG_TERM: {
            const SendspinPairingRecord* rec = this->record_by_psk_id(psk_id);
            if (rec != nullptr) {
                ResolvedPsk r;
                r.psk_id = rec->psk_id;
                r.psk = rec->psk;
                r.category = PskCategory::LONG_TERM;
                r.counterparty_id = rec->server_id;
                return r;
            }
            break;
        }
        case PskCategory::PAIRING: {
            if (this->pairing_psk_.has_value() && this->pairing_psk_->psk_id == psk_id) {
                ResolvedPsk r;
                r.psk_id = this->pairing_psk_->psk_id;
                r.psk = this->pairing_psk_->psk;
                r.category = PskCategory::PAIRING;
                return r;
            }
            break;
        }
        case PskCategory::SENTINEL: {
            if (psk_id == SENTINEL_PSK_ID) {
                ResolvedPsk r;
                r.psk_id = SENTINEL_PSK_ID;
                r.psk = SENTINEL_PSK;
                r.category = PskCategory::SENTINEL;
                return r;
            }
            break;
        }
    }

    return std::nullopt;
}

// ============================================================================
// Long-term record management
// ============================================================================

size_t RecordStore::find_index(const std::string& psk_id) const {
    for (size_t i = 0; i < this->records_.size(); ++i) {
        if (this->records_[i].record.psk_id == psk_id) {
            return i;
        }
    }
    return NPOS;
}

const RecordStore::StoredRecord* RecordStore::record_in_slot(uint8_t slot) const {
    for (const auto& stored : this->records_) {
        if (stored.slot == slot) {
            return &stored;
        }
    }
    return nullptr;
}

uint8_t RecordStore::first_free_slot_locked() const {
    for (size_t slot = 0; slot < this->max_records_; ++slot) {
        if (this->record_in_slot(static_cast<uint8_t>(slot)) == nullptr) {
            return static_cast<uint8_t>(slot);
        }
    }
    return UNASSIGNED_SLOT;
}

void RecordStore::mark_slot_dirty_locked(uint8_t slot) {
    // One write carries every change made to the slot since the last flush.
    if (std::find(this->dirty_slots_.begin(), this->dirty_slots_.end(), slot) ==
        this->dirty_slots_.end()) {
        this->dirty_slots_.push_back(slot);
    }
}

const SendspinPairingRecord* RecordStore::record_by_psk_id(const std::string& psk_id) const {
    size_t idx = this->find_index(psk_id);
    if (idx == NPOS) {
        return nullptr;
    }
    return &this->records_[idx].record;
}

const SendspinPairingRecord* RecordStore::record_by_server_id(const std::string& server_id) const {
    for (const auto& stored : this->records_) {
        if (stored.record.server_id == server_id) {
            return &stored.record;
        }
    }
    return nullptr;
}

bool RecordStore::evict_one_locked(const std::vector<std::string>& psk_ids_in_use) {
    // records_ runs least-recently-used first (see note_record_played), so the first record no open
    // connection is resolving against is the victim pairing.md "Pairing Records" leaves to the
    // implementation. Evicting one that backs an open connection would strand a live session on a
    // PSK this store no longer holds.
    for (size_t i = 0; i < this->records_.size(); ++i) {
        const std::string& psk_id = this->records_[i].record.psk_id;
        if (std::find(psk_ids_in_use.begin(), psk_ids_in_use.end(), psk_id) !=
            psk_ids_in_use.end()) {
            continue;
        }
        SS_LOGW(TAG, "Evicting record %s for server_id=%s to make room for a new pairing",
                psk_id.c_str(), this->records_[i].record.server_id.c_str());
        this->mark_slot_dirty_locked(this->records_[i].slot);
        this->order_dirty_ = true;
        this->records_.erase(this->records_.begin() + static_cast<ptrdiff_t>(i));
        return true;
    }
    return false;
}

bool RecordStore::store_record_superseding(SendspinPairingRecord record,
                                           const std::vector<std::string>& psk_ids_in_use) {
    // RAM-only: runs on the protocol task (the server/pair-finalize ack handler), where the
    // record must resolve before the handler returns, since the server's follow-up re-handshake
    // is the next message on that thread.
    std::lock_guard<std::mutex> lock(this->mutex_);

    size_t idx = this->find_index(record.psk_id);
    const std::string incoming_psk_id = record.psk_id;
    const bool is_insert = (idx == NPOS);

    // Capacity: a replace by psk_id never grows the store, and neither does an insert that
    // supersedes an existing record for the same server_id, because the retire below drops that
    // record in the same locked section. Anything else is a genuine net-new record, which at
    // capacity evicts one rather than failing the pairing (pairing.md "Pairing Records").
    if (is_insert) {
        const bool will_supersede_existing = this->record_by_server_id(record.server_id) != nullptr;
        if (!will_supersede_existing && !this->has_capacity_locked() &&
            !this->evict_one_locked(psk_ids_in_use)) {
            // Only reachable if every record at capacity backs an open connection, which the
            // connection budget rules out (see MIN_MAX_RECORDS). Fails closed: the connection
            // drops when the server rekeys onto a PSK this store cannot resolve.
            SS_LOGW(TAG, "Storage full (%zu/%zu) and nothing evictable; rejecting record %s",
                    this->records_.size(), this->max_records_, incoming_psk_id.c_str());
            return false;
        }
    }

    if (!is_insert) {
        // A replace keeps the slot it already occupies, so only that one blob is rewritten.
        this->records_[idx].record = std::move(record);
    } else {
        // The slot is assigned after the retire below, which may be what frees one: a re-pair at
        // capacity supersedes rather than evicts.
        this->records_.push_back(StoredRecord{std::move(record), UNASSIGNED_SLOT});
        idx = this->records_.size() - 1;
        this->order_dirty_ = true;
    }

    // Retire any other record still bound to this server_id (see the header).
    const std::string superseded_server_id = this->records_[idx].record.server_id;
    uint8_t retired_slot = UNASSIGNED_SLOT;
    for (size_t i = 0; i < this->records_.size();) {
        if (i != idx && this->records_[i].record.server_id == superseded_server_id) {
            SS_LOGI(TAG, "Superseding prior record %s for server_id=%s",
                    this->records_[i].record.psk_id.c_str(), superseded_server_id.c_str());
            this->mark_slot_dirty_locked(this->records_[i].slot);
            this->order_dirty_ = true;
            retired_slot = this->records_[i].slot;
            this->records_.erase(this->records_.begin() + static_cast<ptrdiff_t>(i));
            if (i < idx) {
                --idx;
            }
            continue;  // The element that shifted into position i still needs checking.
        }
        ++i;
    }

    if (this->records_[idx].slot == UNASSIGNED_SLOT) {
        // A supersede takes the slot its own retire just freed, so re-pairing a server costs one
        // record-sized write rather than a write of the new record plus a zeroed write of the old
        // slot. Anything else takes the lowest free slot, which at capacity is the one the
        // eviction above freed.
        this->records_[idx].slot =
            (retired_slot != UNASSIGNED_SLOT) ? retired_slot : this->first_free_slot_locked();
    }
    // The capacity check, the eviction and the retire above between them guarantee a free slot
    // below the cap; writing UNASSIGNED_SLOT would name a key the load path never reads.
    assert(this->records_[idx].slot != UNASSIGNED_SLOT &&
           this->records_[idx].slot < this->max_records_ && "record assigned no usable slot");
    this->mark_slot_dirty_locked(this->records_[idx].slot);

    return true;
}

bool RecordStore::persist_records() {
    std::vector<SlotWrite> writes;
    {
        std::lock_guard<std::mutex> lock(this->mutex_);
        writes = this->take_dirty_writes_locked();
    }
    bool all_accepted = true;
    bool slot_accepted = false;
    for (auto& write : writes) {
        if (this->save_slot_write(write)) {
            slot_accepted |= write.key != persistence_keys::RECORD_ORDER;
            continue;
        }
        all_accepted = false;
        // Nothing is retried, and RAM stays authoritative for this boot. What a rejection costs
        // depends on what the write carries, so the message does too. The recency order is
        // advisory: the next boot rebuilds it from use, and it is written on every playback
        // handoff, so against a full or read-only store it stays quiet. A record slot decides
        // which records the next boot holds, so losing one warns.
        if (write.key == persistence_keys::RECORD_ORDER) {
            SS_LOGD(TAG,
                    "Provider rejected the \"%s\" write; the next boot rebuilds it from use, and "
                    "no record is at stake",
                    write.key.c_str());
            continue;
        }
        SS_LOGW(TAG,
                "Provider rejected the \"%s\" write; that change is RAM-only for this boot: "
                "a record stored since the last accepted write will not survive a reboot, and "
                "a record dropped since it will be valid again after one",
                write.key.c_str());
    }
    if (slot_accepted && !this->provider_->commit()) {
        all_accepted = false;
        SS_LOGW(TAG,
                "Provider failed to commit the record writes: a record stored since the last "
                "commit will not survive a reboot, and a record dropped since it will be valid "
                "again after one");
    }
    return all_accepted;
}

bool RecordStore::has_pending_writes() const {
    std::lock_guard<std::mutex> lock(this->mutex_);
    return this->order_dirty_ || !this->dirty_slots_.empty();
}

bool RecordStore::note_record_removed(const std::string& psk_id) {
    std::lock_guard<std::mutex> lock(this->mutex_);
    const size_t idx = this->find_index(psk_id);
    if (idx == NPOS) {
        return false;
    }
    this->mark_slot_dirty_locked(this->records_[idx].slot);
    this->order_dirty_ = true;
    this->records_.erase(this->records_.begin() + static_cast<ptrdiff_t>(idx));
    return true;
}

bool RecordStore::note_record_played(const std::string& psk_id) {
    std::lock_guard<std::mutex> lock(this->mutex_);
    const size_t idx = this->find_index(psk_id);
    // A record that is already the most recent moves nothing and writes nothing, which is every
    // repeat activate of the server already playing.
    if (idx == NPOS || idx == this->records_.size() - 1) {
        return false;
    }
    // Keeps records_ least-recently-used first for eviction (see evict_one_locked).
    std::rotate(this->records_.begin() + static_cast<ptrdiff_t>(idx),
                this->records_.begin() + static_cast<ptrdiff_t>(idx) + 1, this->records_.end());
    // Only the order blob: a reorder moves no record between slots, so persisting recency costs
    // one small write per playback handoff rather than a rewrite of the records themselves.
    this->order_dirty_ = true;
    return true;
}

// ============================================================================
// Pairing outcome
// ============================================================================

RecordStore::PairingOutcome RecordStore::resolve_pairing_outcome(const std::string& server_id) {
    std::array<uint8_t, NOISE_PSK_SIZE> psk{};
    platform_random_bytes(psk.data(), psk.size());

    SendspinPairingRecord record;
    record.psk_id = psk_id_for(psk);
    record.psk = psk;
    record.server_id = server_id;

    PairingOutcome outcome;
    outcome.psk = psk;
    outcome.record = std::move(record);
    return outcome;
}

// ============================================================================
// Private helpers
// ============================================================================

// ============================================================================
// Locking discipline for records_ persistence
// ============================================================================
//
// Every path that mutates records_ marks the slots whose stored blob no longer matches it (and
// the order blob, when the recency order moved). The next persist_records() encodes those writes
// under mutex_ (take_dirty_writes_locked()), then drops the lock before handing each blob to the
// provider (save_slot_write()). The provider write is an NVS commit on ESP, tens of milliseconds
// per key, and the protocol-task mutators take the same mutex.
//
// The two halves need not be atomic: persist_records() is main-loop-only, so blobs cannot land
// out of order, and the writers that can slip into the gap (the protocol-task mutators
// store_record_superseding, note_record_removed and note_record_played) are RAM-only and their
// callers schedule a flush, which redoes whatever slot they dirtied.
// A resolve in the gap sees the new RAM state, which is the authority for the boot; the blobs
// only decide what survives a reboot.
std::vector<RecordStore::SlotWrite> RecordStore::take_dirty_writes_locked() {
    std::vector<SlotWrite> writes;
    if (this->provider_ == nullptr) {
        this->dirty_slots_.clear();
        this->order_dirty_ = false;
        return writes;
    }
    writes.reserve(this->dirty_slots_.size() + 1);
    for (uint8_t slot : this->dirty_slots_) {
        SlotWrite write;
        write.key = persistence_keys::record_slot_key(slot);
        // A slot nothing occupies is written as zeros, which is how the store frees it: an
        // evicted, revoked or superseded record must not come back at the next boot.
        write.blob.assign(persistence_keys::RECORD_SLOT_SIZE, 0);
        const StoredRecord* held = this->record_in_slot(slot);
        if (held != nullptr) {
            auto encoded = encode_pairing_record(held->record);
            if (encoded.has_value()) {
                std::copy(encoded->begin(), encoded->end(), write.blob.begin());
                secure_zero(encoded->data(), encoded->size());
            } else {
                // Unreachable for a paired server: the handshake refuses a server_id that is not
                // a canonical public key. Freeing the slot keeps whatever it held before from
                // coming back in its place.
                SS_LOGE(TAG, "Record %s has an unstorable server_id; it will not survive a reboot",
                        held->record.psk_id.c_str());
            }
        }
        writes.push_back(std::move(write));
    }
    this->dirty_slots_.clear();

    if (this->order_dirty_) {
        SlotWrite order;
        order.key = persistence_keys::RECORD_ORDER;
        // Advisory: recency is rebuilt from use, so the next boot costs at most one eviction of
        // the wrong record.
        // Fixed at one byte per slot the store may use, whatever it holds now, padded with a
        // value no slot takes.
        order.blob.assign(this->max_records_, UNASSIGNED_SLOT);
        for (size_t i = 0; i < this->records_.size(); ++i) {
            order.blob[i] = this->records_[i].slot;
        }
        writes.push_back(std::move(order));
        this->order_dirty_ = false;
    }
    return writes;
}

bool RecordStore::save_slot_write(SlotWrite& write) {
    // Only reached for a write take_dirty_writes_locked() produced, which it does only when a
    // provider is set.
    const bool ok = this->provider_->save_blob(write.key, write.blob.data(), write.blob.size());
    // An encoded record holds the PSK; wipe it now that save_blob() has its own copy (or has
    // rejected it).
    secure_zero(write.blob.data(), write.blob.size());
    return ok;
}

}  // namespace sendspin
