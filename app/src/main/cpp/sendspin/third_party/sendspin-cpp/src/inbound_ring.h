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

/// @file inbound_ring.h
/// @brief The shared inbound ring: its item layout, the ring wrapper the transports and the
/// protocol task use, the intrusive per-consumer item list, the per-role quotas, the
/// per-connection inbound gate, and the ring's size derivation
///
/// Every inbound WebSocket message of an admitted connection lands in one ring item: an
/// InboundItemHeader followed by the message bytes as received, which the protocol task decrypts
/// in place. An item the protocol task hands to a consumer (a player audio chunk, a visualizer
/// frame, an artwork image part, or an item it writes itself) stays in the ring and is linked onto
/// that consumer's InboundItemList through its own header, so no descriptor storage exists outside
/// the ring items. The consumer returns the item when it is done with it. An unadmitted connection
/// never writes into the ring (see InboundGate).

#pragma once

#include "crypto/constants.h"
#include "platform/crypto.h"
#include "platform/event_flags.h"
#include "platform/memory.h"
#include "platform/shared_ring_buffer.h"
#include "sendspin/config.h"
#include "sendspin/types.h"

#include <algorithm>
#include <array>
#include <atomic>
#include <cstddef>
#include <cstdint>
#include <mutex>
#include <type_traits>

namespace sendspin {

// ============================================================================
// Item layout
// ============================================================================

/// @brief The WebSocket frame type an inbound message arrived in
enum class InboundKind : uint8_t {
    TEXT,    ///< A text message (the pre-transport handshake)
    BINARY,  ///< A binary message (every Noise transport frame)
    /// An item whose receive failed after it was acquired. FreeRTOS cannot cancel an acquire,
    /// and the take order depends on every acquired item being completed, so the transport
    /// marks the item DISCARD, completes it, and uncounts it (InboundGate::abandon_ring_write());
    /// InboundRing::take() returns it to the ring without handing it out.
    DISCARD,
    /// An item the protocol task wrote itself and appended to a consumer's InboundItemList at
    /// once (a codec header, a stream boundary marker, or a message the task copied out of a
    /// reassembly or fallback buffer; see InboundRing::acquire_local()). Its place in the
    /// consumer's list is where the task appended it, not where it sits in the ring, so it is
    /// returned by two parties (LOCAL_ITEM_PARTIES): InboundRing::take() returns it on the
    /// protocol task's behalf when it reaches it in ring order, and its holder returns it when
    /// done (InboundRing::return_item(), which also releases the quota charge at once); whichever
    /// is second gives it back to the ring. The count is InboundItemHeader::local_returns, kept
    /// under the holder's InboundItemList mutex once the item is charged to that holder.
    LOCAL,
};

/// @brief The roles that hold ring items after the protocol task has routed them, each against
/// its own InboundQuota. Every other message is returned to the ring as soon as it is processed,
/// so it is never charged.
enum class InboundHolder : uint8_t {
    PLAYER,      ///< Encoded audio chunks and markers held by the sync task
    VISUALIZER,  ///< Frames held by the visualizer drain thread
    ARTWORK,     ///< Image parts, announces and markers held by the artwork decode thread
};

/// Number of InboundHolder values.
static constexpr size_t INBOUND_HOLDER_COUNT = 3;

/// @brief Throttles the warning at a drop site that can drop every message of a burst
///
/// The first drop of a run logs (note_drop() returns true), the drops after it are only counted,
/// and the next delivery at the site ends the run, logging its count (end_run()). A run that no
/// delivery follows (the stream or the connection ended) is ended where its site's owner tears
/// down: a role's recall after a teardown, a consumer's unbind() at stop, a connection's
/// destructor. A drop is therefore never silent, and a sustained overrun costs two log lines
/// rather than one per message. Not thread-safe: each instance belongs to the one thread that
/// runs its site, or to whichever thread tears it down once that one is joined.
class InboundDropLog {
public:
    /// @brief Counts a drop. @return true for the first drop of a run, which the site logs.
    bool note_drop() {
        return this->dropped_++ == 0;
    }

    /// @brief Ends a run of drops, logging "Dropped <count> <what>" under `tag` when it had any.
    void end_run(const char* tag, const char* what);

private:
    uint32_t dropped_{0};
};

/// InboundItemHeader::next for the last item of a list.
static constexpr uint32_t INBOUND_LIST_END = UINT32_MAX;

/// Bound on a transport's InboundRing::acquire() for an admitted connection's message, after
/// which the connection is closed with a warning (a frame never decrypted leaves the Noise receive
/// nonce behind, so the connection could not continue past it); the protocol task's own acquires
/// for a codec header or a marker wait the same bound. An acquire waits only while the protocol
/// task is behind on taking items or ring-order reclamation holds space behind the oldest held item
/// (see derive_inbound_ring_bytes()). Sized at the bottom of the 100-200 ms stall budget the
/// library's threads are held to: longer than a tick running a Noise handshake's DH operations
/// (tens of milliseconds on an ESP32), so a busy task does not cost a connection, and short enough
/// that a stalled one costs the connection rather than a parked transport, which on ESP is the
/// httpd task every inbound session shares.
static constexpr uint32_t INBOUND_ACQUIRE_TIMEOUT_MS = 100;

/// The largest WebSocket message a conforming peer sends: one Noise transport frame, plaintext
/// plus the AEAD tag. Larger messages are fragmented inside the Noise transport (messaging.md
/// "Fragmentation").
static constexpr size_t INBOUND_MAX_MESSAGE_BYTES = MAX_TRANSPORT_PLAINTEXT + AEAD_TAG_SIZE;

/// Size of InboundItemHeader, paid once per ring item.
static constexpr size_t INBOUND_ITEM_HEADER_BYTES = 32;

/// The most ring storage an item costs beyond its plaintext: the ring's own item header, the
/// InboundItemHeader, the AEAD tag, and the padding to SharedRingLayout::STORAGE_ALIGNMENT. A role
/// that holds items derives the share of its quota it advertises from this against its minimum
/// chunk size.
static constexpr size_t INBOUND_ITEM_STORED_OVERHEAD_BYTES =
    SharedRingLayout::ITEM_HEADER_BYTES + INBOUND_ITEM_HEADER_BYTES + AEAD_TAG_SIZE +
    (SharedRingLayout::STORAGE_ALIGNMENT - 1);

/**
 * @brief Leads every inbound ring item; the message bytes follow it
 *
 * InboundRing::acquire() zeroes the whole header; the transport then fills connection_id,
 * receive_time_us and kind, and every other field stays zero until the protocol task fills it.
 * The protocol task fills the consumer fields (next, data_offset, data_len, generation, type) and,
 * through InboundRing::charge(), charge and holder, before it appends the item to a consumer's
 * InboundItemList; from then on only that consumer touches the item until it returns it. A
 * chunk's or frame's server timestamp is not copied here: the consumer reads it from plaintext
 * bytes 1 to 8, where it arrived.
 *
 * Every field is at most 4-byte aligned: a FreeRTOS no-split ring places items on 4-byte
 * boundaries (SharedRingLayout::STORAGE_ALIGNMENT), which rules out an int64_t or, on a 64-bit
 * host, a pointer, so the list link is a storage offset.
 */
struct InboundItemHeader {
    // 32-bit fields
    /// Storage offset of the next item in the same consumer list, or INBOUND_LIST_END; guarded by
    /// the list's mutex.
    uint32_t next;
    /// Low 32 bits of SendspinConnection::get_instance_id(): the connection the item arrived on.
    /// Instance ids are process-unique and monotonic, so the low word only repeats after 2^32
    /// connections, far beyond any item's life.
    uint32_t connection_id;
    /// Low 32 bits of platform_time_us() when the transport received the message. An item lives
    /// far less than the 2^32 us (about 71 minutes) wrap, so the full time is recovered against
    /// the current clock with widen_time_stamp_us().
    uint32_t receive_time_us;
    /// Ring-stored bytes charged to holder's quota, 0 for an uncharged item; released by
    /// InboundRing::return_item().
    uint32_t charge;
    /// Teardown generation the consumer's role was at when the item was appended; a consumer
    /// discards an item whose stamp no longer matches its role's generation.
    uint32_t generation;
    /// Length of the slice the consumer reads, starting data_offset bytes into the message
    /// bytes. 32 bits: a LOCAL item can hold a message reassembled from several Noise frames.
    uint32_t data_len;

    // 8-bit fields
    /// Offset of the consumer's slice within the message bytes (past the type byte and any
    /// in-band header the consumer does not need).
    uint8_t data_offset;
    InboundKind kind;
    /// Consumer-defined item type: the ChunkType for the player, the wire message type for the
    /// visualizer, the ArtworkItemType for artwork.
    uint8_t type;
    /// The holder the item was handed to; meaningful only while holder_set is non-zero.
    InboundHolder holder;
    /// The returns counted against an InboundKind::LOCAL item; zero and unused for every other
    /// kind. A plain field, never an atomic: the ring may sit in external RAM, where the ESP32's
    /// compare-and-set cannot operate (esp_cpu_compare_and_set() refuses it), so once holder_set
    /// is non-zero it is updated only under the holder's InboundItemList mutex, which lives in
    /// internal RAM; before that both parties are the protocol task.
    uint8_t local_returns;
    /// Non-zero once InboundRing::charge() assigned the item to `holder`; never cleared.
    uint8_t holder_set;
    /// Consumer-defined: the player's stream ordinal on a codec header item, the visualizer's
    /// boundary sequence on a frame, the artwork channel (or, on a marker, the channel mask) on
    /// an artwork item, 0 on every other item.
    uint16_t serial;
};
static_assert(std::is_trivially_copyable_v<InboundItemHeader> &&
                  std::is_standard_layout_v<InboundItemHeader>,
              "an inbound item header lives in raw ring storage");
static_assert(alignof(InboundItemHeader) <= SharedRingLayout::STORAGE_ALIGNMENT,
              "a FreeRTOS no-split ring item is only 4-byte aligned");
static_assert(sizeof(InboundItemHeader) == INBOUND_ITEM_HEADER_BYTES,
              "the header is paid once per ring item, and every held audio chunk is one item");
static_assert(std::has_unique_object_representations_v<InboundItemHeader>,
              "the header has no padding: every byte is a field acquire() zeroes");

/// The returns an InboundKind::LOCAL item needs before it goes back to the ring.
static constexpr uint8_t LOCAL_ITEM_PARTIES = 2;

/// @brief The header at the start of a ring item
inline InboundItemHeader* inbound_item_header(void* item) {
    return static_cast<InboundItemHeader*>(item);
}

/// @brief The message bytes following an item's header
inline uint8_t* inbound_item_bytes(void* item) {
    return static_cast<uint8_t*>(item) + sizeof(InboundItemHeader);
}

/// @brief The consumer's slice of an item: its message bytes from data_offset on
inline uint8_t* inbound_item_data(void* item) {
    return inbound_item_bytes(item) + inbound_item_header(item)->data_offset;
}

/// @brief Recovers a full platform_time_us() reading from its low 32 bits, as stored in
/// InboundItemHeader::receive_time_us, against the current clock
///
/// Exact while the stamped moment is less than 2^32 us (about 71 minutes) before `now`, which an
/// inbound message's life never approaches. Shared by the protocol task (server/time keeps the
/// transport stamp) and the visualizer drain thread (a frame's arrival).
/// @param stamp The low 32 bits of the earlier reading.
/// @param now   The current platform_time_us().
inline int64_t widen_time_stamp_us(uint32_t stamp, int64_t now) {
    // Unsigned subtraction of the low words gives the age modulo 2^32 us.
    return now - static_cast<uint32_t>(static_cast<uint32_t>(now) - stamp);
}

// ============================================================================
// Quotas
// ============================================================================

/**
 * @brief An outstanding-byte budget charged and released from any threads
 *
 * try_charge() and release() are lock-free atomic updates, so the charging thread (the protocol
 * task) and the releasing thread (a consumer returning an item, or the protocol task recalling
 * one) need no common lock. Charges are in ring-stored bytes (SharedRingLayout::stored_size()),
 * the cost an item actually has in the ring.
 */
class InboundQuota {
public:
    InboundQuota() = default;
    explicit InboundQuota(size_t limit) : limit_(limit) {}

    InboundQuota(const InboundQuota&) = delete;
    InboundQuota& operator=(const InboundQuota&) = delete;

    /// @brief Sets the budget. Call before any thread charges it.
    void set_limit(size_t limit) {
        this->limit_ = limit;
    }

    /// @brief Bytes charged and not yet released. Any thread.
    size_t outstanding() const {
        return this->outstanding_.load(std::memory_order_acquire);
    }

    /// @brief Charges `bytes` when they fit the budget. Any thread.
    /// @return false, charging nothing, when the charge would exceed the limit; the caller
    ///         drops the item and logs the drop.
    bool try_charge(size_t bytes) {
        size_t current = this->outstanding_.load(std::memory_order_relaxed);
        do {
            if (bytes > this->limit_ || current > this->limit_ - bytes) {
                return false;
            }
        } while (!this->outstanding_.compare_exchange_weak(
            current, current + bytes, std::memory_order_acq_rel, std::memory_order_relaxed));
        return true;
    }

    /// @brief Releases bytes an earlier try_charge() charged. Any thread.
    void release(size_t bytes) {
        this->outstanding_.fetch_sub(bytes, std::memory_order_acq_rel);
    }

private:
    // size_t fields
    /// Written by set_limit() before any charge; read by every charging thread.
    size_t limit_{0};
    /// Charged by the charging thread, released by the releasing thread (see the class comment).
    std::atomic<size_t> outstanding_{0};
};

// ============================================================================
// InboundRing
// ============================================================================

class InboundItemList;
struct InboundMessage;

/**
 * @brief The shared inbound ring: a SharedRingBuffer over its own storage, the per-role quotas,
 * and the one return path that releases a returned item's charge
 *
 * Producers are the transport threads (acquire(), complete()); every acquired item must be
 * completed. The consumer is the protocol task (take()), which also charges what it hands to a
 * holder. Any thread returns items, always through return_item().
 *
 * take() never hands out an uncompleted item. On ESP, right after the consumer passes the filler
 * that marks a wrap, FreeRTOS hands out the item at the start of the storage whether or not its
 * producer has completed it (prvCheckItemAvail() checks the filler's flag, prvGetItemDefault()
 * then wraps unchecked; the host SharedRingBuffer refuses that item instead). So the ring counts
 * completions of items at the storage start (SharedRingBuffer::is_storage_head()) and the
 * protocol task counts its takes of them. An item there can only be acquired once the previous
 * one there was taken and returned, so the k-th such take is safe exactly when the k-th such
 * completion has happened; until then the item is held back as pending (pending_), never
 * returned early, and nothing behind it is taken. Stale bytes in reused storage cannot fake this,
 * unlike a flag inside the item. The two counts are never reset and stay in step across reset().
 */
class InboundRing {
public:
    InboundRing() = default;
    ~InboundRing() = default;

    InboundRing(const InboundRing&) = delete;
    InboundRing& operator=(const InboundRing&) = delete;

    /// @brief Allocates the storage and creates the ring. Call before any producer or consumer
    /// runs.
    /// @param storage_bytes From derive_inbound_ring_bytes().
    /// @param location Placement preference for the storage.
    /// @param largest_message_bytes The budget's InboundRingBudget::largest_message_bytes, which
    ///        largest_message_bytes() reports.
    /// @return false when the storage cannot be allocated or the size is refused.
    bool create(size_t storage_bytes, MemoryLocation location,
                size_t largest_message_bytes = INBOUND_MAX_MESSAGE_BYTES);

    /// @brief Whether create() succeeded
    bool is_created() const {
        return this->ring_.is_created();
    }

    /// @brief The longest received message a transport writes into a ring item:
    /// max_item_message_bytes() capped at INBOUND_MAX_MESSAGE_BYTES, since a conforming peer's
    /// WebSocket message is at most one Noise frame. The derivation sizes the ring for the
    /// largest message the enabled roles need (InboundRingBudget::largest_message_bytes), so a
    /// transport routes a longer one through the connection's fallback buffer instead. Written
    /// by create(); read by any thread after it.
    size_t max_message_bytes() const {
        return this->max_message_bytes_;
    }

    /// @brief The longest message any acquire() can succeed for, received or local: what an item
    /// of the ring's largest size (SharedRingLayout::max_item_size(), half its storage) holds
    /// after its header. A message the protocol task copies into a LOCAL item (a chunk
    /// reassembled from several Noise frames) is bounded by this alone, not by
    /// max_message_bytes(). Written by create(); read by any thread after it.
    size_t max_item_message_bytes() const {
        return this->max_item_message_bytes_;
    }

    /// @brief The longest message the derivation sized the ring for, at most
    /// max_message_bytes(): no conforming server sends an admitted connection a longer one the
    /// enabled roles need. Written by create(); read by any thread after it.
    size_t largest_message_bytes() const {
        return this->largest_message_bytes_;
    }

    /// @brief The ring's storage base, which InboundItemHeader::next offsets are relative to
    uint8_t* storage() const {
        return this->ring_.storage();
    }

    /// @brief The quota of one holder. Set its limit before the protocol task charges it.
    InboundQuota& quota(InboundHolder holder) {
        return this->quotas_[static_cast<size_t>(holder)];
    }

    /// @brief Reserves an item for a message of `message_len` bytes, its header zeroed.
    /// Transport threads.
    ///
    /// Every item acquired must be completed: FreeRTOS cannot cancel an acquire, an uncompleted
    /// item holds back every item behind it, and the storage-start count take() relies on counts
    /// completions. A transport whose receive fails after acquiring marks the item
    /// InboundKind::DISCARD and completes it. A peer that stalls part-way through a message
    /// keeps its item uncompleted until the liveness watchdog drops the connection, since the
    /// liveness stamp is taken when a message completes (see
    /// SendspinConnection::note_message_completed()).
    /// @param timeout_ms As SharedRingBuffer::acquire().
    /// @return The item (header first), or nullptr.
    void* acquire(size_t message_len, uint32_t timeout_ms);

    /// @brief Reserves an item the protocol task fills itself and appends to a consumer's list
    /// (InboundKind::LOCAL), its header zeroed apart from the kind. Protocol task only. Complete
    /// it with complete() as soon as it is filled: an uncompleted item holds back every item
    /// behind it.
    /// @param message_len Bytes after the header.
    /// @param timeout_ms As SharedRingBuffer::acquire(). The protocol task is the ring's only
    ///        taker, so a wait here ends only on returns from the consumers. Reclamation is in
    ///        ring order, so the wait can also be pinned by the message item the task is handling
    ///        while it acquires (a stream/start's own item, returned only once its handler ends):
    ///        with that item oldest and the ring otherwise full, the wait runs out.
    /// @return The item, or nullptr.
    void* acquire_local(size_t message_len, uint32_t timeout_ms);

    /// @brief Publishes a filled item. The thread that acquired it.
    void complete(void* item);

    /// @brief Takes the oldest completed item, returning DISCARD items and the protocol task's
    /// party of each LOCAL item on the way without handing them out. Protocol task only.
    /// @param[out] message_len The length of the message bytes after the header.
    /// @param timeout_ms As SharedRingBuffer::take(), applied to each underlying take.
    /// @return The item, or nullptr: nothing completed in time, a wake_receiver() interruption,
    ///         or the oldest item still being written.
    void* take(size_t* message_len, uint32_t timeout_ms);

    /// @brief Returns every item still in the ring, supplying the protocol task's ring-order
    /// return of each item it never took (the missing half of a LOCAL item's two returns), so
    /// the client's shutdown settles every count before it destroys the ring
    /// (SendspinClient::release_inbound_ring(); each start creates a fresh one). Waits for a
    /// pending item's completion first, up to INBOUND_ACQUIRE_TIMEOUT_MS, and leaves the rest to
    /// the ring's destruction when it never comes. Call only once every producer has stopped
    /// (each acquired item is then completed) and every holder has returned or recalled its
    /// items; the protocol task is the caller or is joined.
    void reset();

    /// @brief Assigns a taken item to a holder, recording the holder and the charge against its
    /// quota in the header. Protocol task only, before the item is appended to the holder's list.
    /// @param message_len The length take() reported for the item.
    /// @param exempt Records the holder without charging its quota (see InboundConsumer::hand()).
    /// @return false when the holder is over quota: the caller returns the item and logs the drop.
    bool charge(void* item, size_t message_len, InboundHolder holder, bool exempt);

    /// @brief Returns a taken item to the ring, releasing any quota charge it carries: the
    /// holder's return (the consumer, or the protocol task returning an item it did not hand
    /// over or recalled). Any thread.
    void return_item(void* item);

    /// @brief Registers the list whose mutex counts the returns of a LOCAL item assigned to
    /// `holder`; nullptr unregisters it. InboundItemList::create() registers it before any item
    /// is charged, and InboundItemList::unbind() unregisters it once the holder's consumer is
    /// joined and its items recalled, so with it unregistered the count needs no lock.
    void register_list(InboundHolder holder, InboundItemList* list) {
        this->lists_[static_cast<size_t>(holder)] = list;
    }

    /// @brief Wakes the protocol task out of a blocking take(). Any thread.
    void wake_receiver() {
        this->ring_.wake_receiver();
    }

private:
    /// @brief Whether every item taken at the storage start has been completed (see the class
    /// comment)
    bool head_takes_completed() const {
        const uint32_t completions = this->head_completions_.load(std::memory_order_acquire);
        return static_cast<int32_t>(completions - this->head_takes_) >= 0;
    }

    /// @brief Hands out the pending item once it is completed, waiting up to `timeout_ms`
    void* take_pending(size_t* message_len, uint32_t timeout_ms);

    /// @brief One take from the ring, before DISCARD items are filtered out
    void* take_one(size_t* message_len, uint32_t timeout_ms);

    /// @brief The protocol task's ring-order return of a taken item that is not handed out: a
    /// DISCARD item goes back at once, a LOCAL item counts its ring-order party.
    void return_in_ring_order(void* item);

    /// @brief Counts one of a LOCAL item's returns, through its holder's registered list once it
    /// has one. @return true when this was the last.
    bool count_local_return(InboundItemHeader* header, bool release_charge);

    // Struct fields
    SharedRingBuffer ring_;
    PlatformBuffer storage_;
    /// Charged by the protocol task, released by whichever thread returns a charged item.
    std::array<InboundQuota, INBOUND_HOLDER_COUNT> quotas_{};
    /// Each holder's list (register_list()), whose mutex guards the return count of a LOCAL item
    /// charged to it. Written before any item is charged.
    std::array<InboundItemList*, INBOUND_HOLDER_COUNT> lists_{};

    // Pointer fields
    /// An item taken at the storage start before its completion was confirmed (see the class
    /// comment), with its message length. Protocol task only.
    void* pending_{nullptr};

    // size_t fields
    size_t pending_len_{0};
    /// See max_message_bytes(), max_item_message_bytes() and largest_message_bytes(). Written by
    /// create() before any producer runs.
    size_t max_message_bytes_{0};
    size_t max_item_message_bytes_{0};
    size_t largest_message_bytes_{0};

    // 32-bit fields
    /// Completions of items at the storage start. Incremented by transport threads before the
    /// item is published; read by the protocol task.
    std::atomic<uint32_t> head_completions_{0};
    /// Takes of items at the storage start. Protocol task only.
    uint32_t head_takes_{0};
};

// ============================================================================
// InboundItemList
// ============================================================================

/**
 * @brief Intrusive FIFO of ring items handed from the protocol task to one consumer thread
 *
 * The link lives in each item's InboundItemHeader::next, so the list has no capacity of its
 * own: it holds exactly the items the ring holds and cannot overflow independently.
 * Back-pressure is the ring itself plus the per-role quotas. Single producer (the protocol task,
 * append()), single consumer (the sync task, the visualizer drain thread or the artwork decode
 * thread, take()); recall()
 * may run on the protocol task, or on any thread once the consumer is joined.
 *
 * mutex_ guards head_, tail_ and every next link of a linked item, and the return count of a
 * LOCAL item charged to this list's holder (InboundRing::return_item()). It is a leaf: nothing
 * is called under it, and the ring's lock is taken only after it is released (recall() detaches
 * the chain first, then returns the items).
 */
class InboundItemList {
public:
    InboundItemList() = default;
    ~InboundItemList() = default;

    InboundItemList(const InboundItemList&) = delete;
    InboundItemList& operator=(const InboundItemList&) = delete;

    /// @brief Binds the list to the ring whose items it links, registers it as `holder`'s list
    /// (InboundRing::register_list()) and creates its wake flags. Call after the ring is created
    /// and before the producer or the consumer runs.
    /// @return false when the event flags cannot be created.
    bool create(InboundRing* ring, InboundHolder holder);

    /// @brief Unregisters the list from its ring (InboundRing::register_list()). Call once the
    /// consumer is joined and the list recalled, before the list or the ring goes away; create()
    /// binds it again.
    void unbind();

    /// @brief Counts one of a LOCAL item's returns under this list's mutex (see
    /// InboundItemHeader::local_returns); the holder's return also releases the item's charge
    /// against `quota`, outside the lock. Any thread; called by InboundRing.
    /// @return true when this was the last return.
    bool count_local_return(InboundItemHeader* header, bool release_charge, InboundQuota& quota);

    /// @brief Appends a ring item and wakes the consumer. Protocol task only.
    /// @param item An item taken from the bound ring, its consumer header fields filled.
    void append(void* item);

    /// @brief Takes the oldest appended item. Consumer only.
    /// @param timeout_ms Milliseconds to wait for an item: 0 does not wait, UINT32_MAX waits
    ///        indefinitely.
    /// @return The item, which the consumer hands to InboundRing::return_item() when done, or
    ///         nullptr on timeout, on a wake_receiver() or signal() interruption, or on a wake
    ///         left by an append whose item an earlier take already removed. Treat nullptr as
    ///         "re-check state and retry".
    void* take(uint32_t timeout_ms);

    /// @brief Wakes the consumer out of a blocking take(); one-shot, and redundant wakes
    /// collapse. Any thread.
    void wake_receiver() {
        this->flags_.set(WAKE);
    }

    /// The lowest consumer command bit; the bits below it are the list's own (ITEMS_APPENDED,
    /// WAKE).
    static constexpr uint32_t FIRST_CONSUMER_BIT = 1U << 2;
    /// The highest consumer command bit: the eighth, the last every platform's group exposes
    /// (EventFlags::USABLE_BITS).
    static constexpr uint32_t LAST_CONSUMER_BIT = 1U << 7;

    /// @brief Sets consumer `bits` and wakes a blocking take(). Any thread, once the list is
    /// created.
    void signal(uint32_t bits) {
        this->flags_.set(bits | WAKE);
    }

    /// @brief Waits up to `timeout_ms` for any of consumer `bits`, clearing those set. Consumer
    /// only.
    /// @return The bits among `bits` that were set; 0 on timeout.
    uint32_t take_signals(uint32_t bits, uint32_t timeout_ms) {
        return this->flags_.wait(bits, false, true, timeout_ms) & bits;
    }

    /// @brief Clears every bit. Before the consumer thread starts, while the list is empty.
    void clear_signals() {
        this->flags_.clear_all();
    }

    /// @brief Unlinks every item not yet taken and returns each through
    /// InboundRing::return_item(), oldest first, releasing its charge. Protocol task, or any
    /// thread once the consumer is joined.
    /// @return The number of items returned.
    size_t recall();

    /// @brief Whether no item is linked. Any thread.
    bool is_empty() const {
        std::lock_guard<std::mutex> lock(this->mutex_);
        return this->head_ == INBOUND_LIST_END;
    }

private:
    /// Event flag bits
    static constexpr uint32_t ITEMS_APPENDED = 1U << 0;
    static constexpr uint32_t WAKE = 1U << 1;

    /// @brief Unlinks the head item, or returns nullptr when the list is empty
    void* pop();

    /// @brief The item at a storage offset
    void* item_at(uint32_t offset) const {
        return this->storage_ + offset;
    }

    // Struct fields
    /// Set by append(), wake_receiver() and signal(); take() waits on the list's own bits,
    /// take_signals() on the consumer's.
    EventFlags flags_;
    /// Guards head_, tail_ and the links of linked items; a leaf lock (see the class comment).
    mutable std::mutex mutex_;

    // Pointer fields
    /// Written once by create(), before the producer and consumer run.
    InboundRing* ring_{nullptr};
    uint8_t* storage_{nullptr};

    // 8-bit fields
    /// The holder create() registered the list for. Written once by create().
    InboundHolder holder_{InboundHolder::PLAYER};

    // 32-bit fields
    /// Storage offsets of the oldest and newest linked items, INBOUND_LIST_END when empty.
    /// Written by the protocol task (append, recall) and the consumer (take), under mutex_.
    uint32_t head_{INBOUND_LIST_END};
    uint32_t tail_{INBOUND_LIST_END};
};

// ============================================================================
// InboundConsumer
// ============================================================================

/// Fallback wake for a consumer thread's blocking take (the sync task while idle, the visualizer
/// drain thread, the artwork decode thread). Every command, teardown and artwork gate reopening
/// wakes the take at once, so this only turns a missed wake into a slow reaction, not a hang.
static constexpr uint32_t INBOUND_CONSUMER_FALLBACK_WAKE_MS = 5000;

/// @brief The consumer-defined InboundItemHeader fields every hand-over fills (documented there)
struct InboundItemFields {
    uint32_t data_len;
    uint16_t serial;
    uint8_t type;
    uint8_t data_offset;
};

/**
 * @brief One holder's end of the ring: the item list its consumer thread takes from, the ring it
 * is bound to for a run, and the protocol task's side of handing items over
 *
 * Shared by the sync task (InboundHolder::PLAYER), the visualizer drain thread
 * (InboundHolder::VISUALIZER) and the artwork decode thread (InboundHolder::ARTWORK). Every item
 * handed over carries the holder role's teardown
 * generation (InboundItemHeader::generation): a teardown moves the role's `cleanup_generation`
 * on and its cleanup() recalls what the consumer has not taken (recall()), and a consumer that
 * takes such an item between the two returns it unprocessed (take()).
 *
 * Threads: bind() and unbind() run on the main loop with neither the consumer thread nor the
 * protocol task running: SendspinClient::start() binds before it starts the protocol task, and
 * stop() (and a start() that fails part-way) unbinds after joining it. The protocol task hands
 * items over, counts drops and recalls items (hand(), hand_message(), hand_local(), note_drop(),
 * recall()); the consumer thread takes and returns them. ring() is read on all three, ordered by
 * those thread starts and joins.
 */
class InboundConsumer {
public:
    /// @brief Binds the item list to this run's ring as `holder`'s list. Main loop, before the
    /// consumer thread and the protocol task start. @return false when the list's event flags
    /// cannot be created.
    bool bind(InboundRing* ring, InboundHolder holder);

    /// @brief Returns every item left on the list, unbinds it from the ring and ends the drop
    /// log's run. Main loop, once the consumer thread and the protocol task are joined; bind()
    /// binds it again.
    void unbind();

    /// @brief The ring the list is bound to, or nullptr outside a run
    InboundRing* ring() const {
        return this->ring_;
    }

    /// @brief The item list itself, for a consumer that wakes or inspects it directly
    InboundItemList& items() {
        return this->items_;
    }
    const InboundItemList& items() const {
        return this->items_;
    }

    /// @brief Takes the next item whose stamp is still `generation`'s current value, returning
    /// the stale ones before it to the ring. Consumer thread.
    /// @param timeout_ms As InboundItemList::take(), applied to the first take only.
    /// @return The item, or nullptr: treat it as "re-check state and retry".
    void* take(uint32_t timeout_ms, const std::atomic<uint32_t>& generation);

    /// @brief Returns an item to the ring. The consumer thread once it has taken an item; the
    /// protocol task for an item hand() refuses over quota.
    void return_item(void* item) const {
        this->ring()->return_item(item);
    }

    /// @brief Fills `item`'s consumer fields, stamps it with `generation`, charges it to the
    /// holder's quota and appends it. Over quota the item is returned to the ring with a
    /// throttled warning: the server overran the buffer_capacity the role advertises, which the
    /// quota covers at the role's smallest message. Protocol task, inside a run. Called directly
    /// only for an item the caller acquired itself (a FLAC codec header decoded into its item).
    /// @param item_len The item's message length (InboundMessage::item_len, or the length the
    ///        item was acquired for).
    /// @param exempt Hands the item over without charging the quota: a codec header, an artwork
    ///        announce or a stream boundary marker the protocol task writes itself, one per
    ///        stream/start, stream/clear or image, so refusing it would end or blur a stream over
    ///        a quota the server's data overran. Under ring-order reclamation a clear pins both its
    ///        JSON item and its marker until the consumer returns the marker; that is no more than
    ///        a JSON flood from an authenticated server already holds, inside the pass-through
    ///        budget derive_inbound_ring_bytes() holds.
    /// @return false when the item was returned instead of handed over.
    bool hand(void* item, size_t item_len, InboundItemFields fields, uint32_t generation,
              bool exempt);

    /// @brief Hands a received message over, charged to the quota: its ring item in place
    /// (clearing message.item), or else a LOCAL copy of the whole message keeping its receive
    /// stamp. The copy does not wait; a message with no ring space, or longer than the ring's
    /// largest item, is dropped. Protocol task, inside a run.
    /// @return false when the message was dropped instead of handed over.
    bool hand_message(InboundMessage& message, InboundItemFields fields, uint32_t generation);

    /// @brief Copies `len` bytes (a codec header, an artwork announce, or none for a marker)
    /// into a LOCAL item, waiting up to INBOUND_ACQUIRE_TIMEOUT_MS for ring space, and hands it
    /// over exempt from the quota (see hand()). Protocol task.
    /// @return false when the consumer is not bound to a ring or the ring had no room in time.
    bool hand_local(const void* data, size_t len, InboundItemFields fields, uint32_t generation);

    /// @brief Returns every item the consumer has not taken to the ring and ends the drop log's
    /// run, since the stream or stretch the drops belonged to is over. The holder role's
    /// cleanup(), right after its generation moves on, and the visualizer's stream boundaries:
    /// on the protocol task, the thread that hands items over, so every item recalled predates
    /// the teardown or boundary. A no-op outside a run, where the main loop's cleanup() in
    /// SendspinClient::stop() finds the list already unbound.
    void recall();

    /// @brief Why a message was dropped instead of handed over, which picks note_drop()'s warning
    enum class DropReason : uint8_t {
        OVER_QUOTA,  ///< hand(): the holder is over its quota
        TOO_LONG,    ///< hand_message(): longer than the ring's largest item
        NO_ROOM,     ///< hand_message(): no ring space for the copy
        TOO_SHORT,   ///< The role's handler: too short for the holder's message header
    };

    /// @brief Counts a drop in the throttled run (InboundDropLog), logging `reason`'s warning
    /// when the drop starts one. Protocol task: the hand-overs, and a role handler dropping a
    /// malformed message. A no-op outside a run.
    void note_drop(DropReason reason);

private:
    /// @brief Acquires a LOCAL item (InboundRing::acquire_local()), copies `len` bytes into it
    /// and completes it. Protocol task, inside a run.
    /// @return The item, ready for hand(), or nullptr when the ring had no room in time.
    void* copy_local(const uint8_t* data, size_t len, uint32_t receive_time_us,
                     uint32_t timeout_ms) const;

    /// @brief What end_run() calls the holder's dropped items
    const char* dropped_items_name() const;
    /// @brief The holder's name, leading every drop warning
    const char* holder_name() const;
    /// @brief One of the holder's messages, for the drop warnings
    const char* item_noun() const;

    // Struct fields
    /// Appended on the protocol task, taken on the consumer thread, recalled on the protocol task
    /// or, once the consumer is joined, on the main loop.
    InboundItemList items_;
    /// Every drop of the holder's items. Protocol task, or the main loop's unbind() once the
    /// protocol task is joined.
    InboundDropLog drop_log_;

    // Pointer fields
    /// Written by bind() and unbind() on the main loop while neither the protocol task nor the
    /// consumer thread runs; read by both, ordered by their start and join. A role that starts
    /// while the protocol task runs would need this to be an atomic.
    InboundRing* ring_{nullptr};

    // 8-bit fields
    /// Written by bind() before the protocol task hands anything over.
    InboundHolder holder_{InboundHolder::PLAYER};
};

// ============================================================================
// InboundGate
// ============================================================================

/**
 * @brief The per-connection state the transport and the protocol task share without a lock
 *
 * Embedded in SendspinConnection. It carries:
 *  - the admitted flag, which decides where the transport puts a message;
 *  - the fallback hand-off: an unadmitted connection never writes into the shared ring,
 *    because items returned at once still stay unreclaimable behind held audio, so a peer that
 *    holds only the Sentinel PSK could otherwise fill the ring. It delivers each complete message
 *    through its own fallback buffer, of at most PRE_ADMISSION_MESSAGE_BYTES, one message at a
 *    time; a larger message closes the connection. An admitted connection uses the same hand-off
 *    for a message longer than the ring takes (InboundRing::max_message_bytes());
 *  - the in-flight count of ring items the transport has begun writing and the protocol task
 *    has not yet taken;
 *  - the out-of-band close flag, honoured only once nothing of the connection is still queued
 *    (close_ready());
 *  - the detached flag, set once nothing reads the connection any more: the transport then
 *    writes nothing anywhere and drops what it receives, and a wait_until_writable() ends at
 *    once, so no transport thread a release joins is parked on this gate.
 *
 * Ordering. While a message is pending in the fallback buffer the transport writes nowhere
 * (may_write() is false, and begin_ring_write() and publish_pending_message() refuse); it waits
 * for consume_pending_message() through wait_until_writable(). The protocol task handles a
 * connection's pending message only once every ring item the connection wrote before it has been
 * taken (in_flight() is 0; with the transport writing nowhere the count can only fall), and
 * before any ring item written after it. The message that gets a connection admitted is
 * therefore processed, and the admitted flag set, before the transport routes its next message,
 * which then goes to the ring; and an admitted connection's long message routed through the
 * fallback buffer keeps its place between its ring items.
 *
 * Cost: one event group per connection for that wait (an xEventGroupCreate() heap allocation on
 * ESP, made in the constructor); is_created() reports whether it succeeded.
 */
class InboundGate {
public:
    /// The largest message an unadmitted connection may deliver: what legitimately precedes
    /// admission (the handshake, server/hello, server/activate, pairing JSON, and the role JSON
    /// a server sends right behind its activation) is the traffic
    /// MAX_PRE_ADMISSION_REASSEMBLED_MESSAGE_BYTES already bounds for one reassembled message.
    static constexpr size_t PRE_ADMISSION_MESSAGE_BYTES =
        MAX_PRE_ADMISSION_REASSEMBLED_MESSAGE_BYTES;

    /// Bound on the transport's wait_until_writable() before it closes an unadmitted connection.
    /// The protocol tick consumes a pending message in its receive step, so the wait can cover the
    /// rest of a tick under way: its ring pass of up to MAX_ITEMS_PER_TICK items
    /// (SendspinClient::protocol_tick()), more than one of which may wait, and the steps after it.
    /// Most items hand over in microseconds; what waits is the Noise DH operations of a handshake
    /// message and the INBOUND_ACQUIRE_TIMEOUT_MS waits for ring space a message can make. A
    /// stream/start makes the most, two (the player's codec header and the artwork role's
    /// RECONFIGURE marker, 200 ms). An artwork announce, cancel, stream/end or a stream/clear
    /// makes one, as does an image part dropped over the artwork quota (the DISCARD marker in its
    /// place) and the announce of an image over its cap (the marker alone); an announce makes two
    /// only when handing the announce itself fails and its marker follows. A stream/start and
    /// three single-wait messages in one tick add up to five waits, which is the whole bound with
    /// nothing left for the handlers, so that mix, with the ring full and the role threads not
    /// draining it, can close the waiting connection, as can any tick with more waits. This is
    /// accepted: it takes a stream/start plus three marker-bearing messages from the incumbent in
    /// one tick under ring back-pressure, and the closed connection reconnects.
    /// Every lock the task takes is a leaf held for a copy, so nothing else stretches a tick, and
    /// a task stalled beyond the bound closes the waiting connection rather than parking the
    /// transport thread.
    static constexpr uint32_t WRITABLE_WAIT_MS = 500;

    InboundGate() {
        this->consumed_flags_.create();
    }

    /// @brief Whether the gate's event group was created. Connection setup refuses a connection
    /// whose gate has none (ConnectionManager::on_new_connection() and connect_to()), leaving it
    /// unattached so its transport drops what it receives: without the group the transport has no
    /// way to wait for a consume.
    bool is_created() const {
        return this->consumed_flags_.is_created();
    }

    InboundGate(const InboundGate&) = delete;
    InboundGate& operator=(const InboundGate&) = delete;

    // ---- Admission ----

    void set_admitted(bool admitted) {
        this->admitted_.store(admitted, std::memory_order_release);
    }

    bool is_admitted() const {
        return this->admitted_.load(std::memory_order_acquire);
    }

    // ---- Fallback hand-off ----

    /// @brief Whether the transport may write its next message anywhere: false while a message
    /// is pending. Only the transport publishes, so a true answer stays true until it does.
    bool may_write() const {
        return !this->message_pending_.load(std::memory_order_acquire);
    }

    /// @brief Waits until the protocol task has consumed the pending message. Transport thread.
    ///
    /// A CONSUMED bit can be left over from the previous message: consume_pending_message()
    /// clears the pending flag before it sets the bit, so the transport can publish the next
    /// message in between and the late bit then lands on it. The wait therefore loops on
    /// may_write() rather than trusting a single wake.
    /// @param timeout_ms Bound on the wait; UINT32_MAX waits until the message is consumed or the
    ///        gate is detached.
    /// @return may_write() at the end of the wait: false when it timed out, or at once once the
    ///         gate is detached with a message still pending.
    bool wait_until_writable(uint32_t timeout_ms);

    /// @brief Publishes the complete message now in the fallback buffer; the caller then wakes
    /// the protocol task. @return false, publishing nothing, while an earlier one is pending.
    bool publish_pending_message() {
        if (!this->may_write()) {
            return false;
        }
        // Cleared before the flag is raised, so a CONSUMED bit left by the previous message
        // cannot end the next wait_until_writable() early.
        this->consumed_flags_.clear(CONSUMED);
        this->message_pending_.store(true, std::memory_order_release);
        return true;
    }

    bool has_pending_message() const {
        return this->message_pending_.load(std::memory_order_acquire);
    }

    /// @brief Hands the fallback buffer back to the transport and wakes it out of
    /// wait_until_writable(), once the protocol task is done reading the message
    void consume_pending_message() {
        this->message_pending_.store(false, std::memory_order_release);
        this->consumed_flags_.set(CONSUMED);
    }

    // ---- In-flight ring items ----

    /// @brief Counts a ring item the transport is about to acquire. @return false, counting
    /// nothing, while a message is pending; the transport waits and routes again.
    bool begin_ring_write() {
        if (!this->may_write()) {
            return false;
        }
        this->in_flight_.fetch_add(1, std::memory_order_acq_rel);
        return true;
    }

    /// @brief Uncounts an item whose acquire failed, or a DISCARD item once it is completed
    /// (InboundRing::take() returns those without the protocol task seeing them)
    void abandon_ring_write() {
        this->in_flight_.fetch_sub(1, std::memory_order_acq_rel);
    }

    /// @brief Uncounts an item the protocol task has taken from the ring
    void note_item_taken() {
        this->in_flight_.fetch_sub(1, std::memory_order_acq_rel);
    }

    uint32_t in_flight() const {
        return this->in_flight_.load(std::memory_order_acquire);
    }

    // ---- Close ----

    /// @brief Records that the transport has closed, after its last item is completed or its
    /// last message published; the caller then wakes the protocol task
    void mark_transport_closed() {
        this->transport_closed_.store(true, std::memory_order_release);
    }

    /// @brief Whether the transport has reported its close, whatever is still queued: what the
    /// reap of a released connection waits for (ConnectionManager::reap_released())
    bool is_transport_closed() const {
        return this->transport_closed_.load(std::memory_order_acquire);
    }

    // ---- Detach ----

    /// @brief Records that nothing reads this connection any more and wakes a transport waiting
    /// in wait_until_writable(). Never cleared. The one gate call a connection refused for a
    /// missing event group still reaches (its transport failing it), so it sets no bit without
    /// the group.
    void detach() {
        this->detached_.store(true, std::memory_order_release);
        if (this->consumed_flags_.is_created()) {
            this->consumed_flags_.set(CONSUMED);
        }
    }

    bool is_detached() const {
        return this->detached_.load(std::memory_order_acquire);
    }

    /// @brief Whether the protocol task may honour the close: the transport has closed, and
    /// every ring item it wrote has been taken and no message waits, so acting on the close now
    /// keeps "after every message" ordering. The flag is read first: once it reads true the
    /// transport writes nothing more, so the counts read after it are final on the transport's
    /// side. An acquired-but-uncompleted item of another connection can hold this connection's
    /// completed items back in the ring, which is why the flag alone is not enough.
    bool close_ready() const {
        return this->transport_closed_.load(std::memory_order_acquire) && this->in_flight() == 0 &&
               !this->has_pending_message();
    }

private:
    /// Event flag bit: the protocol task consumed the pending message.
    static constexpr uint32_t CONSUMED = 1U << 0;

    // Struct fields
    /// Set by the protocol task (consume_pending_message()) and by detach(); waited on and
    /// cleared by the transport thread (wait_until_writable(), publish_pending_message()).
    EventFlags consumed_flags_;

    // 32-bit fields
    /// The items the protocol task will be handed. The transport uncounts an item it knows will
    /// never be handed over: a failed acquire at once, a DISCARD item only after complete(), so
    /// close_ready() can never be true while the transport still holds an uncompleted item.
    /// Incremented and uncounted by the transport thread; decremented by the protocol task at
    /// take.
    std::atomic<uint32_t> in_flight_{0};

    // 8-bit fields
    /// Written by the protocol task as the connection enters and leaves an admitted slot; read
    /// by the transport thread, to route a message, and the protocol task.
    std::atomic<bool> admitted_{false};
    /// Set by the protocol task (the connection manager when the connection leaves it, the
    /// receive path closing it); by the transport thread closing it (fail_inbound()); by the
    /// thread delivering an accept the command queue refused; and by the main loop with the
    /// protocol task joined (~ConnectionManager). Read by the transport thread and the protocol
    /// task.
    std::atomic<bool> detached_{false};
    /// Set by the transport thread (publish), cleared by the protocol task (consume).
    std::atomic<bool> message_pending_{false};
    /// Written by the transport thread (mark_transport_closed()); read by the protocol task.
    std::atomic<bool> transport_closed_{false};
};

// ============================================================================
// InboundMessage
// ============================================================================

/**
 * @brief One complete inbound message as the protocol task processes it
 *
 * The bytes are in a ring item (`item` set: an admitted connection's message), in the
 * connection's fallback buffer (a pre-admission message), or in the Noise reassembly buffer (a
 * fragmented message); only the first can be handed to a consumer as it is. A role that keeps the
 * item (appends it to its consumer's list) clears `item`, and the caller returns whatever is left
 * set.
 */
struct InboundMessage {
    // Pointer fields
    /// The ring item holding `data`, or nullptr. Cleared by a role that takes the item over.
    void* item{nullptr};
    /// The message bytes: ciphertext as received, plaintext (type byte first) once decrypted.
    uint8_t* data{nullptr};

    // size_t fields
    /// The item's message length, as InboundRing::take() reported it (what a charge is computed
    /// from); 0 when `item` is null.
    size_t item_len{0};
    /// Length of `data`.
    size_t len{0};

    // 32-bit fields
    /// Low 32 bits of platform_time_us() when the transport received the message.
    uint32_t receive_time_us{0};

    // 8-bit fields
    InboundKind kind{InboundKind::BINARY};
};

// ============================================================================
// Ring size derivation
// ============================================================================

/// The longest JSON message the ring is sized to take: the size a reassembled pre-admission
/// message is capped at (MAX_PRE_ADMISSION_REASSEMBLED_MESSAGE_BYTES, eight steady-state JSON
/// arenas), plus the AEAD tag. A longer one still arrives, through the connection's fallback
/// buffer (see InboundRing::max_message_bytes()).
static constexpr size_t INBOUND_JSON_MESSAGE_BYTES =
    MAX_PRE_ADMISSION_REASSEMBLED_MESSAGE_BYTES + AEAD_TAG_SIZE;

/// @brief The longest message a role that holds ring items can be sent, from the buffer_capacity
/// it advertises: the server sends no message longer than that, since it counts each whole
/// message against it (roles/player/v1.md "Player Buffer Accounting": an audio chunk's 13-byte
/// header and its payload; roles/visualizer/v1.md: a message's type byte, timestamp and data).
/// Plus the AEAD tag, and at most one Noise frame: a longer chunk arrives fragmented into maximal
/// frames. 0 for a role that is not enabled.
static constexpr size_t inbound_held_message_bytes(size_t advertised_capacity) {
    return advertised_capacity == 0
               ? 0
               : std::min(advertised_capacity + AEAD_TAG_SIZE, INBOUND_MAX_MESSAGE_BYTES);
}

/// @brief The longest message the enabled roles need delivered in one ring item: the largest of
/// one JSON message (INBOUND_JSON_MESSAGE_BYTES), which every admitted connection receives, and
/// the player's and the visualizer's longest message (inbound_held_message_bytes()); with the
/// artwork role a maximal Noise frame, since its images arrive in maximal frames
/// (roles/artwork/v1.md "Artwork (Binary)").
/// @param player_message_bytes inbound_held_message_bytes() of the player's share of its quota
///        (PlayerRole::Impl::buffer_capacity_share(), which the capacity it advertises never
///        exceeds), 0 without the player.
/// @param visualizer_message_bytes inbound_held_message_bytes() of the visualizer's advertised
///        capacity, 0 without the visualizer.
/// @param artwork Whether the artwork role is enabled.
static constexpr size_t inbound_largest_message_bytes(size_t player_message_bytes,
                                                      size_t visualizer_message_bytes,
                                                      bool artwork) {
    if (artwork) {
        return INBOUND_MAX_MESSAGE_BYTES;
    }
    return std::max({INBOUND_JSON_MESSAGE_BYTES, player_message_bytes, visualizer_message_bytes});
}

/// @brief Ring storage one item holding a message of `message_len` bytes occupies
static constexpr size_t inbound_item_stored_bytes(size_t message_len) {
    return SharedRingLayout::stored_size(sizeof(InboundItemHeader) + message_len);
}

/// @brief Ring storage below which a message of `largest_message_bytes` no longer fits: a
/// FreeRTOS no-split ring accepts an item of at most half its storage
/// (SharedRingLayout::max_item_size()), so the floor is two such items.
static constexpr size_t inbound_ring_min_storage_bytes(size_t largest_message_bytes) {
    return 2 * inbound_item_stored_bytes(largest_message_bytes);
}

/// The floor of a ring that accepts a maximal message (131,152 bytes).
static constexpr size_t INBOUND_RING_MIN_STORAGE_BYTES =
    inbound_ring_min_storage_bytes(INBOUND_MAX_MESSAGE_BYTES);
static_assert(SharedRingLayout::max_item_size(INBOUND_RING_MIN_STORAGE_BYTES) >=
                  sizeof(InboundItemHeader) + INBOUND_MAX_MESSAGE_BYTES,
              "the minimum ring must accept a maximal message");
static_assert(
    SharedRingLayout::max_item_size(inbound_ring_min_storage_bytes(INBOUND_JSON_MESSAGE_BYTES)) >=
        sizeof(InboundItemHeader) + INBOUND_JSON_MESSAGE_BYTES,
    "the minimum JSON-only ring must accept a maximal JSON message");

/// The baseline pass-through allowance every configuration pays, on top of the per-second budget
/// below: largest messages (JSON, or a protocol message the task returns at once) that can arrive
/// behind a held item. Two lets one arrive while the previous is still being processed.
static constexpr size_t INBOUND_PASSTHROUGH_MESSAGES = 2;

/// Bytes of an audio chunk message ahead of its encoded frame: the message type byte and the
/// 12-byte header (roles/player/v1.md "Audio Chunks (Binary)": 8-byte timestamp, 4-byte
/// send_ahead).
static constexpr size_t INBOUND_AUDIO_CHUNK_HEADER_BYTES = 13;

/// The smallest encoded audio frame the player's advertised share and the ring's hold window are
/// derived for: 20 ms of Opus at 64 kbit/s. PCM never goes below it (8 kHz mono 16-bit is 320 B
/// per 20 ms). Lower-rate Opus, and FLAC over near-silence, send smaller frames: the player's
/// quota then holds more seconds of audio than derived here, which pins more pass-through
/// traffic behind it (see derive_inbound_ring_bytes()).
static constexpr size_t INBOUND_MIN_AUDIO_FRAME_BYTES = 160;

/// Frames per second at INBOUND_MIN_AUDIO_FRAME_BYTES: one every 20 ms, Opus's default frame.
static constexpr size_t INBOUND_MIN_AUDIO_FRAMES_PER_SECOND = 50;

/// The lowest audio rate the library budgets for, in encoded bytes per second (64 kbit/s).
static constexpr size_t MIN_ADVERTISED_AUDIO_BYTES_PER_SECOND =
    INBOUND_MIN_AUDIO_FRAME_BYTES * INBOUND_MIN_AUDIO_FRAMES_PER_SECOND;

/// Ring storage per second of audio at MIN_ADVERTISED_AUDIO_BYTES_PER_SECOND: each frame stored
/// as one item with its chunk header and the stored overhead.
static constexpr size_t INBOUND_MIN_AUDIO_STORED_BYTES_PER_SECOND =
    (INBOUND_MIN_AUDIO_FRAME_BYTES + INBOUND_AUDIO_CHUNK_HEADER_BYTES +
     INBOUND_ITEM_STORED_OVERHEAD_BYTES) *
    INBOUND_MIN_AUDIO_FRAMES_PER_SECOND;

/// Ring storage budgeted per server/time reply: its JSON, well under 256 bytes, in one item.
static constexpr size_t INBOUND_TIME_REPLY_STORED_BYTES =
    SharedRingLayout::stored_size(sizeof(InboundItemHeader) + 256 + AEAD_TAG_SIZE);

/// Pass-through JSON budgeted per second apart from time replies: about one 1 KB message a second
/// (server/state for metadata, controller or color, group/update, server/command). Track metadata
/// with long strings is larger but arrives once per track.
static constexpr size_t INBOUND_STATE_BYTES_PER_SECOND = 1024;

/// The shortest track the artwork budget assumes: one track change, and so one new image per
/// artwork channel, every 30 seconds. An assumption about listening, not a protocol bound: a
/// listener skipping tracks faster than this pins more images than budgeted, which the transport
/// reports as "ring pinned behind held items" when it closes the connection.
static constexpr size_t INBOUND_MIN_TRACK_SECONDS = 30;

/// Images per artwork channel the artwork decode thread's quota covers: one in flight. The decode
/// thread copies each part into its channel's assembly buffer as it takes it and returns the item
/// at once, gate open or closed, so its parts wait in the ring only while the thread is busy (in
/// on_image_decode()), and the server sends one transfer at a time. The quota is one image: if a
/// second image for a channel arrives behind a complete one still queued while the thread is
/// inside a long decode, its parts go over the quota and it is dropped, and the channel keeps its
/// current image until the server sends the next one.
///
/// A third image arriving while the decode callback is still inside the first one is dropped at
/// the quota too, but its refused parts stay pinned behind the queued second image. With a hold
/// window of INBOUND_MIN_TRACK_SECONDS or more (the default player's) the ring carries further
/// images behind the held items (see derive_inbound_ring_bytes()). With none (artwork alone, or
/// a visualizer holding under INBOUND_MIN_TRACK_SECONDS) the ring has about one baseline message
/// of room behind the pinned parts, so a stall long enough for time replies and state JSON to
/// arrive behind them can exhaust the ring, and the connection closes after
/// INBOUND_ACQUIRE_TIMEOUT_MS. That needs a decode callback blocked for about two image cadences
/// (around a minute at one image per INBOUND_MIN_TRACK_SECONDS) or track skips faster than
/// INBOUND_MIN_TRACK_SECONDS, which the derivation treats as beyond its budget.
static constexpr size_t INBOUND_ARTWORK_IN_FLIGHT_IMAGES = 1;

/// Bytes of an artwork part message ahead of its image data: the type byte, then the flags byte
/// (roles/artwork/v1.md "Artwork (Binary)").
static constexpr size_t INBOUND_ARTWORK_PART_HEADER_BYTES = 2;

/// The most ring storage an artwork part costs beyond its image data: the part header and the
/// stored overhead of its item.
static constexpr size_t INBOUND_ARTWORK_PART_STORED_OVERHEAD_BYTES =
    INBOUND_ITEM_STORED_OVERHEAD_BYTES + INBOUND_ARTWORK_PART_HEADER_BYTES;

/// The smallest image part, in data bytes, the artwork quota is derived for. The quota charges
/// each part its stored size (the item headers, the type and flags bytes, the Noise tag and the
/// alignment padding: INBOUND_ARTWORK_PART_STORED_OVERHEAD_BYTES), so an image within
/// ImageSlotPreference::max_image_bytes split into parts smaller than this costs more than the
/// quota and is dropped while the decode thread is busy. The reference server sends maximal parts
/// (65,517 bytes).
static constexpr size_t INBOUND_ARTWORK_MIN_PART_BYTES = 4096;

/// @brief Ring storage one image of up to `max_image_bytes` occupies, sent in parts of at least
/// INBOUND_ARTWORK_MIN_PART_BYTES (the last one may be shorter): its data plus a part's stored
/// overhead for each of at most ceil(max_image_bytes / INBOUND_ARTWORK_MIN_PART_BYTES) parts.
static constexpr size_t inbound_artwork_image_stored_bytes(size_t max_image_bytes) {
    const size_t max_parts =
        (max_image_bytes + INBOUND_ARTWORK_MIN_PART_BYTES - 1) / INBOUND_ARTWORK_MIN_PART_BYTES;
    return max_image_bytes + max_parts * INBOUND_ARTWORK_PART_STORED_OVERHEAD_BYTES;
}

/// @brief The configuration figures the ring size is derived from
///
/// A holder's quota counts each item's stored overhead (SharedRingLayout::ITEM_HEADER_BYTES,
/// the InboundItemHeader, the Noise type byte, the in-band timestamp and the AEAD tag) as well
/// as its payload, so the payload a role can have outstanding is its quota less that overhead
/// per item. The share of its buffer a role advertises to the server must therefore be derived
/// from that stored overhead against a stated minimum chunk size, not assumed to fit.
struct InboundRingBudget {
    /// The player's quota: PlayerRoleConfig::audio_buffer_capacity. 0 without the player role.
    size_t audio_hold_bytes{0};
    /// The visualizer's quota: VisualizerSupportObject::buffer_capacity. 0 without the visualizer
    /// role.
    size_t visualizer_hold_bytes{0};
    /// Ring storage the visualizer's requested frames arrive at per second: every requested type
    /// at rate_max, each frame at its stored size (VisualizerRole's
    /// stored_frame_bytes_per_second()). 0 without the visualizer role.
    size_t visualizer_stored_bytes_per_second{0};
    /// Ring storage one image per artwork channel takes: the sum over the channels of
    /// inbound_artwork_image_stored_bytes(ImageSlotPreference::max_image_bytes). 0 without the
    /// artwork role.
    size_t artwork_images_stored_bytes{0};
    /// The artwork role's quota: INBOUND_ARTWORK_IN_FLIGHT_IMAGES * artwork_images_stored_bytes. 0
    /// without the artwork role.
    size_t artwork_hold_bytes{0};
    /// SendspinClientConfig::time_burst_size: server/time replies per burst.
    size_t time_burst_size{SendspinClientConfig::DEFAULT_BURST_SIZE};
    /// SendspinClientConfig::time_burst_interval_ms: milliseconds between bursts.
    int64_t time_burst_interval_ms{SendspinClientConfig::DEFAULT_BURST_INTERVAL_MS};
    /// The longest message the enabled roles need in one ring item
    /// (inbound_largest_message_bytes()), which sets the ring's floor and its pass-through
    /// allowance without artwork.
    size_t largest_message_bytes{INBOUND_MAX_MESSAGE_BYTES};
};

/// @brief The longest a holder keeps its oldest item, in whole seconds: `held_bytes` of quota
/// filled at `stored_bytes_per_second`, rounded up; 0 for a holder that is not enabled.
static constexpr size_t inbound_hold_seconds(size_t held_bytes, size_t stored_bytes_per_second) {
    return stored_bytes_per_second == 0
               ? 0
               : (held_bytes + stored_bytes_per_second - 1) / stored_bytes_per_second;
}

/**
 * @brief Storage for the shared inbound ring, derived from the configuration
 *
 * Reclamation is in ring order (see shared_ring_buffer.h), so every byte that arrives while the
 * oldest held item is outstanding stays unreclaimable until it is returned: JSON, time replies,
 * visualizer frames returned at their display time, artwork parts returned once copied, and
 * dropped items alike. The ring therefore holds:
 *  - the player's quota (audio_hold_bytes), the visualizer's (visualizer_hold_bytes) and the
 *    artwork role's (artwork_hold_bytes, one image per channel in flight to its decode thread;
 *    see INBOUND_ARTWORK_IN_FLIGHT_IMAGES);
 *  - the pass-through traffic arriving inside the longest hold window: the state JSON budget and
 *    every time-burst reply in it, and the visualizer frames the player's window carries, since a
 *    frame returned at its display time stays pinned behind audio held far longer. The player
 *    holds its oldest chunk for its quota at INBOUND_MIN_AUDIO_STORED_BYTES_PER_SECOND; the
 *    visualizer its oldest frame for its quota at rate_max, a lower bound, since rate_max is a
 *    cap: a sparser stream (a beat-only stream at two frames a second against a rate_max of 30)
 *    pins the pass-through longer than budgeted, and assuming a minimum rate instead would
 *    derive an absurd ring for a sparse stream. The codec headers and markers the protocol task
 *    writes for the holders are uncharged (InboundConsumer::hand()) and fall inside this term;
 *  - the baseline, INBOUND_PASSTHROUGH_MESSAGES items of largest_message_bytes, which can always
 *    arrive and sit behind a held item, whatever the holders' windows;
 *  - with the artwork role, the artwork that window carries: one image per channel per
 *    INBOUND_MIN_TRACK_SECONDS, copied out and returned but pinned behind the held items. With
 *    no hold window of INBOUND_MIN_TRACK_SECONDS or more (artwork alone, or a visualizer holding
 *    under INBOUND_MIN_TRACK_SECONDS) this term is 0: a third image arriving while the decode
 *    callback is still inside the first one is dropped at the quota, but its refused parts stay
 *    pinned behind the queued second image, so a stall long enough for time replies and state
 *    JSON to arrive behind them can exhaust the ring and close the connection (see
 *    INBOUND_ARTWORK_IN_FLIGHT_IMAGES). That needs a decode callback blocked for about two image
 *    cadences or track skips faster than INBOUND_MIN_TRACK_SECONDS, beyond this budget;
 * and never less than two items of largest_message_bytes (inbound_ring_min_storage_bytes()),
 * rounded up to the 4-byte multiple FreeRTOS requires. Unadmitted connections never write into
 * the ring (InboundGate), so they add nothing.
 *
 * The largest item follows the enabled roles (inbound_largest_message_bytes()): a maximal Noise
 * frame with the artwork role or a player advertising a buffer of a frame or more, which floors
 * the ring at 131,152 bytes; a 16 KiB JSON message with neither, which floors it at 32,880 bytes;
 * and the player's longest chunk in between. A longer message goes through the connection's
 * fallback buffer (InboundGate). A chunk or frame not in a ring item is copied into one bounded
 * by the ring's largest item alone (InboundRing::max_item_message_bytes(), half the storage):
 * audio and visualizer messages up to one Noise frame always fit, and each stream role
 * advertises a buffer of at most that bound (PlayerRole::Impl::advertised_buffer_capacity()), so
 * only a server over its advertised buffer sends one reassembled from several frames that is
 * longer, which is dropped with a warning naming that.
 *
 * Traffic beyond this budget (a burst of large JSON, a lower audio rate or a sparser visualizer
 * stream than budgeted, faster track changes) waits in the transport's acquire and, after
 * INBOUND_ACQUIRE_TIMEOUT_MS, closes the connection with a warning naming the held items. The
 * derivation assumes a server's visualizer lead never exceeds its audio lead: otherwise audio
 * returned as it plays would stay pinned behind the oldest visualizer frame, which nothing in the
 * configuration bounds.
 *
 * With the default configuration (a 1,000,000-byte player quota, no visualizer or artwork) this
 * is 1,000,000 + 111,552 held pass-through bytes (87 s at 1,024 B/s, plus 9 bursts of 8 time
 * replies at 312 stored bytes) + 131,152 for two maximal messages = 1,242,704 bytes, against
 * separate buffers of 1,000,000 bytes of encoded audio plus one maximal payload buffer per open
 * connection (1,065,535 bytes with one connection).
 */
static constexpr size_t derive_inbound_ring_bytes(const InboundRingBudget& budget) {
    const size_t player_hold_seconds =
        inbound_hold_seconds(budget.audio_hold_bytes, INBOUND_MIN_AUDIO_STORED_BYTES_PER_SECOND);
    const size_t hold_seconds = std::max(
        player_hold_seconds, inbound_hold_seconds(budget.visualizer_hold_bytes,
                                                  budget.visualizer_stored_bytes_per_second));
    const size_t bursts =
        hold_seconds > 0 && budget.time_burst_interval_ms > 0
            ? hold_seconds * 1000 / static_cast<size_t>(budget.time_burst_interval_ms) + 1
            : 0;
    const size_t held_passthrough =
        hold_seconds * INBOUND_STATE_BYTES_PER_SECOND +
        player_hold_seconds * budget.visualizer_stored_bytes_per_second +
        bursts * budget.time_burst_size * INBOUND_TIME_REPLY_STORED_BYTES;
    const size_t baseline =
        INBOUND_PASSTHROUGH_MESSAGES * inbound_item_stored_bytes(budget.largest_message_bytes);
    const size_t artwork_window =
        hold_seconds / INBOUND_MIN_TRACK_SECONDS * budget.artwork_images_stored_bytes;
    const size_t total = budget.audio_hold_bytes + budget.visualizer_hold_bytes +
                         budget.artwork_hold_bytes + held_passthrough + baseline + artwork_window;
    return SharedRingLayout::align(
        std::max(total, inbound_ring_min_storage_bytes(budget.largest_message_bytes)));
}

}  // namespace sendspin
