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

/// @file outbound_ring.h
/// @brief The outbound ring: its item layout, the ring wrapper one producer thread and the
/// protocol task use, and the ring's size derivation
///
/// Every outbound binary message a producer thread writes lands in one ring item: an
/// OutboundItemHeader followed by the message bytes. The protocol task takes the item, encrypts
/// the message in place and lends the item to the connection's transport, which returns it to
/// the ring once the frame is written (SendspinConnection::send_app_binary_lent()), so the
/// message is never copied between the producer and the socket.

#pragma once

#include "platform/memory.h"
#include "platform/shared_ring_buffer.h"
#include "sendspin/types.h"

#include <algorithm>
#include <cstddef>
#include <cstdint>
#include <cstring>

namespace sendspin {

// ============================================================================
// Item layout
// ============================================================================

/**
 * @brief Leads every outbound ring item; the message bytes follow it
 *
 * OutboundRing::acquire() zeroes it; the producer fills every field before it completes the item,
 * and the protocol task reads them once it takes the item. Ring items are only 4-byte aligned
 * (SharedRingLayout::STORAGE_ALIGNMENT), less than capture_time_us needs, so the header is only
 * copied in and out (outbound_item_header(), set_outbound_item_header()), never accessed in place.
 */
struct OutboundItemHeader {
    /// When the message's content was captured (platform_time_us()); the protocol task stamps the
    /// message with it.
    int64_t capture_time_us;
    /// The producer's stream generation; the consumer returns an item of an older one
    /// unsent.
    uint32_t generation;
    /// Message bytes filled; for an outbound message at most the item's capacity less
    /// AEAD_TAG_SIZE.
    uint32_t data_len;
};

/// Header bytes before each item's message.
static constexpr size_t OUTBOUND_ITEM_HEADER_BYTES = sizeof(OutboundItemHeader);
static_assert(OUTBOUND_ITEM_HEADER_BYTES % SharedRingLayout::STORAGE_ALIGNMENT == 0,
              "the message bytes must start on the ring's alignment");

/// @brief The header at the start of a ring item
inline OutboundItemHeader outbound_item_header(const void* item) {
    OutboundItemHeader header{};
    std::memcpy(&header, item, sizeof(header));
    return header;
}

/// @brief Writes the header at the start of a ring item
inline void set_outbound_item_header(void* item, const OutboundItemHeader& header) {
    std::memcpy(item, &header, sizeof(header));
}

/// @brief The message bytes following an item's header
inline uint8_t* outbound_item_message(void* item) {
    return static_cast<uint8_t*>(item) + OUTBOUND_ITEM_HEADER_BYTES;
}

// ============================================================================
// OutboundRing
// ============================================================================

/**
 * @brief The outbound ring: a SharedRingBuffer over its own storage with one producer and one
 * consumer
 *
 * The producer is one thread (acquire(), complete()), which completes each item before it
 * acquires the next. The consumer is one thread (take()): the protocol task for a ring of
 * outbound messages. The source role's capture ring reuses the class with the source task as its
 * consumer, capture PCM as its item bytes and no lending (SourceTask). Any thread returns items
 * (return_item()). With one producer completing in order, take() never hands out an uncompleted
 * item on either platform: FreeRTOS marks the filler that ends the storage passed only from the
 * completion of the item placed after it (prvSendItemDoneNoSplit()), so the storage-start guard
 * InboundRing needs for several producers has nothing to guard here.
 *
 * Item sizes. A ring that lends to the ESP server reserves every item at its largest size, the
 * one derive_outbound_ring_bytes() sized it for: then at most OUTBOUND_RING_ITEM_COUNT items are
 * out at once, which the server's lent block pool (LENT_BLOCK_COUNT) is sized for. Smaller items
 * would let more fit the ring than the pool has blocks.
 *
 * Message bytes. An item's message bytes are the plaintext Noise transport message, type byte
 * first, followed by AEAD_TAG_SIZE bytes of room the encrypt in place writes its tag into. The
 * ring itself is agnostic of that layout; its sizes count the tag room as message bytes.
 *
 * Lending. The protocol task hands a taken item to SendspinConnection::send_app_binary_lent(),
 * and from that call on the send chain owns it: on every path, success or failure, the item is
 * back in the ring by the time the chain is done, and the caller never touches it again. Each
 * link that fails before it hands the item on returns it itself
 * (SendspinConnection::send_app_binary_lent(), NoiseTransport::send_binary_lent()); the
 * transport's send_lent_frame() owns it once called and returns it after the write or on its
 * own failure, before it returns on a synchronous transport and from the httpd worker on the ESP
 * server. The ESP server takes a header block from its lent pool for each queued frame, so an
 * exhausted pool is a failure after the encrypt and closes the connection.
 *
 * Lifetime. A lent item can still be queued on the ESP httpd worker after the send returned, so
 * the ring must outlive every server that may hold one: its owner destroys the ring only after
 * the server is stopped, which also returns every lent item httpd discarded
 * (SendspinServerConnection::reclaim_discarded_sends()). Once that stop has run and the protocol
 * task is joined, every item is back.
 */
class OutboundRing {
public:
    OutboundRing() = default;
    ~OutboundRing() = default;

    OutboundRing(const OutboundRing&) = delete;
    OutboundRing& operator=(const OutboundRing&) = delete;

    /// @brief Allocates the storage and creates the ring. Call before the producer or the
    /// consumer runs.
    /// @param storage_bytes From derive_outbound_ring_bytes().
    /// @param location Placement preference for the storage.
    /// @return false when the storage cannot be allocated or the size is refused.
    bool create(size_t storage_bytes, MemoryLocation location);

    /// @brief Whether create() succeeded
    bool is_created() const {
        return this->ring_.is_created();
    }

    /// @brief The largest message capacity an acquire() can succeed for: what an item of the
    /// ring's largest size (SharedRingLayout::max_item_size(), half its storage) holds after its
    /// header. Written by create(); read by any thread after it.
    size_t max_message_bytes() const {
        return this->max_message_bytes_;
    }

    /// @brief Reserves an item with room for `message_capacity` message bytes, its header zeroed.
    /// The producer only, after completing its previous item.
    ///
    /// Every item acquired must be completed: FreeRTOS cannot cancel an acquire, and an
    /// uncompleted item holds back every item behind it. A producer that cannot finish an item
    /// completes it with a data_len of 0 or a stale generation rather than abandoning it.
    /// @param timeout_ms As SharedRingBuffer::acquire(). A capacity over max_message_bytes()
    ///        fails at once.
    /// @return The item (header first), or nullptr.
    void* acquire(size_t message_capacity, uint32_t timeout_ms);

    /// @brief Publishes a filled item to the protocol task. The producer.
    void complete(void* item) {
        this->ring_.complete(item);
    }

    /// @brief Takes the oldest completed item. The consumer only.
    /// @param[out] message_capacity The message capacity the item was acquired with.
    /// @param timeout_ms As SharedRingBuffer::take(). The protocol task passes 0: it polls the
    ///        ring from its tick rather than blocking on it. A blocking take also ends on
    ///        wake_consumer().
    /// @return The item, or nullptr when none is completed.
    void* take(size_t* message_capacity, uint32_t timeout_ms) {
        size_t item_size = 0;
        void* item = this->ring_.take(&item_size, timeout_ms);
        if (item != nullptr) {
            *message_capacity = item_size - OUTBOUND_ITEM_HEADER_BYTES;
        }
        return item;
    }

    /// @brief Ends the consumer's blocking take() at once (SharedRingBuffer::wake_receiver()).
    /// Any thread.
    void wake_consumer() {
        this->ring_.wake_receiver();
    }

    /// @brief Returns a taken item to the ring. Any thread (see the class comment for who).
    void return_item(void* item) {
        this->ring_.return_item(item);
    }

private:
    // Struct fields
    SharedRingBuffer ring_;
    PlatformBuffer storage_;

    // size_t fields
    /// See max_message_bytes().
    size_t max_message_bytes_{0};
};

// ============================================================================
// Size derivation
// ============================================================================

/// Items the outbound ring holds at the largest message size: one the producer fills, one queued
/// on the ESP httpd worker until its frame is written, and one spare, so the producer never waits
/// on a frame the worker has not yet written.
static constexpr size_t OUTBOUND_RING_ITEM_COUNT = 3;

/**
 * @brief Storage for the outbound ring that always holds `item_count` items of
 * `largest_message_bytes`
 *
 * At least two items, which SharedRingLayout::max_item_size() needs; items all of one size never
 * skip a tail at a wrap, so `item_count` fit at any ring position. A producer that also acquires
 * smaller items can lose up to one item's room per wrap.
 * @param largest_message_bytes The largest message capacity acquired, AEAD_TAG_SIZE of tag room
 *        included.
 * @param item_count Items that must fit at once, OUTBOUND_RING_ITEM_COUNT for the source role.
 */
static constexpr size_t derive_outbound_ring_bytes(size_t largest_message_bytes,
                                                   size_t item_count) {
    return std::max(item_count, size_t{2}) *
           SharedRingLayout::stored_size(OUTBOUND_ITEM_HEADER_BYTES + largest_message_bytes);
}

}  // namespace sendspin
