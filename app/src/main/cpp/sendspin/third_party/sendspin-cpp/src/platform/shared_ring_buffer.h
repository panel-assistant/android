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

/// @file shared_ring_buffer.h
/// @brief Multi-producer ring buffer with acquire-then-complete writes, one ordered consumer and
/// out-of-order returns, over caller-provided storage: a FreeRTOS no-split ring buffer on ESP, a
/// mutex/condition-variable reimplementation of the same layout on host
///
/// Each item is one contiguous blob. A producer reserves it with acquire(), fills it in place,
/// and publishes it with complete(); several producers may hold acquired items at once. The
/// consumer takes items in ring (acquire) order with take(), and an item acquired earlier but
/// not yet completed holds back the items behind it, with one exception on ESP: the item at the
/// start of the storage can be handed out right after a wrap before its producer completed it
/// (InboundRing explains and guards it through is_storage_head()). Any thread may hand a taken
/// item back with return_item(), in any order.
///
/// Reclamation is in ring order. Returning an item marks it free, but its space becomes
/// available to acquire() only once every item ahead of it in the ring has been returned too. An
/// item that is held for a long time therefore pins every byte acquired after it, including
/// items that were taken and returned at once, until it is returned. Size the storage for the
/// longest hold window, not for the bytes outstanding at any instant.
///
/// A blocking take() can be interrupted from any thread with wake_receiver(), so a caller treats
/// a nullptr return as "re-check state and retry", never as proof the timeout elapsed.

#pragma once

#include <cstddef>
#include <cstdint>

namespace sendspin {

/**
 * @brief Storage layout of a SharedRingBuffer, identical on both platforms
 *
 * These are the FreeRTOS no-split ring buffer's rules (esp_ringbuf ringbuf.c), which the host
 * implementation reproduces so a size derived here holds on device:
 *  - Every item is preceded by an ITEM_HEADER_BYTES header and padded to STORAGE_ALIGNMENT.
 *  - An item that does not fit between the write position and the end of the storage is placed
 *    at the start, and the tail it skips stays unusable until the ring wraps past it.
 *  - The largest item the ring accepts is max_item_size(storage), half the storage less one
 *    header, so the storage must hold two of the largest items a producer can write.
 */
struct SharedRingLayout {
    /// FreeRTOS pads every no-split item to a 4-byte boundary and asserts a storage size that is
    /// a multiple of it at creation (rbALIGN_SIZE, xRingbufferCreateStatic()).
    static constexpr size_t STORAGE_ALIGNMENT = 4;

    /// The FreeRTOS per-item header: a size_t length and a UBaseType_t flag word, 8 bytes on the
    /// 32-bit ESP32 family.
    static constexpr size_t ITEM_HEADER_BYTES = 8;

    /// @brief Rounds `n` up to STORAGE_ALIGNMENT
    static constexpr size_t align(size_t n) {
        return (n + STORAGE_ALIGNMENT - 1) & ~(STORAGE_ALIGNMENT - 1);
    }

    /// @brief Ring storage one item of `len` bytes occupies: its header plus its padded payload
    static constexpr size_t stored_size(size_t len) {
        return ITEM_HEADER_BYTES + align(len);
    }

    /// @brief Largest item a ring of `storage_bytes` accepts (FreeRTOS xMaxItemSize for a
    /// no-split ring); an acquire() of more fails at once without waiting.
    static constexpr size_t max_item_size(size_t storage_bytes) {
        return align(storage_bytes / 2) - ITEM_HEADER_BYTES;
    }

    /// @brief Whether `storage_bytes` is a size create() accepts: a multiple of
    /// STORAGE_ALIGNMENT with room for at least one header-only item on each half
    static constexpr bool is_valid_storage_size(size_t storage_bytes) {
        return storage_bytes % STORAGE_ALIGNMENT == 0 && storage_bytes >= 4 * ITEM_HEADER_BYTES;
    }
};

}  // namespace sendspin

#ifdef ESP_PLATFORM

#include "platform/time.h"
#include <freertos/FreeRTOS.h>
#include <freertos/ringbuf.h>
#include <freertos/semphr.h>

namespace sendspin {

/**
 * @brief Multi-producer, ordered-consumer, any-order-return ring buffer (ESP-IDF implementation)
 *
 * A thin wrapper around a static FreeRTOS RINGBUF_TYPE_NOSPLIT ring buffer. The thread-safety
 * contract is FreeRTOS's own: xRingbufferSendAcquire() reserves space inside the ring's critical
 * section, so concurrent producers each get disjoint space and may complete in any order (the
 * write pointer advances only over completed items, prvSendItemDoneNoSplit());
 * xRingbufferReceive() checks the completed flag of the header at the read position
 * (prvCheckItemAvail()); and vRingbufferReturnItem() takes the same critical section and accepts
 * items in any order, advancing the free pointer only over a run of returned items starting at
 * the oldest (prvReturnItemDefault()), which is the ring-order reclamation the file comment
 * describes.
 *
 * The out-of-order returns need ESP-IDF v5.5.2 or later: before it, prvReturnItemDefault()
 * (esp_ringbuf ringbuf.c) clears the ring's full flag on a return that frees nothing (a newer item
 * returned while the oldest is still held on a full ring), and acquire() then hands out space
 * older items still occupy.
 *
 * vRingbufferReturnItem() unblocks one producer waiting in acquire() (the host wakes them all),
 * so with several producers waiting a bounded acquire() can time out while room it could use
 * exists; callers treat a timed-out acquire as "no room in time", never as a full ring.
 */
class SharedRingBuffer {
public:
    SharedRingBuffer() = default;
    ~SharedRingBuffer() {
        if (this->handle_ != nullptr) {
            vRingbufferDelete(this->handle_);
        }
        if (this->items_or_wake_sem_ != nullptr) {
            vSemaphoreDelete(this->items_or_wake_sem_);
        }
    }

    // Not copyable or movable
    SharedRingBuffer(const SharedRingBuffer&) = delete;
    SharedRingBuffer& operator=(const SharedRingBuffer&) = delete;

    /// @brief Creates the ring over caller-provided storage. Call before any producer or
    /// consumer runs.
    /// @param size Storage size in bytes. Must satisfy SharedRingLayout::is_valid_storage_size():
    ///        xRingbufferCreateStatic() asserts a multiple of 4, so a size that is not is refused
    ///        here instead of tripping the assert.
    /// @param storage At least `size` bytes, 4-byte aligned (any platform_malloc block is).
    /// @return false when the size is refused or the ring or its semaphore cannot be created.
    bool create(size_t size, uint8_t* storage) {
        if (!SharedRingLayout::is_valid_storage_size(size)) {
            return false;
        }
        this->handle_ =
            xRingbufferCreateStatic(size, RINGBUF_TYPE_NOSPLIT, storage, &this->structure_);
        if (this->handle_ == nullptr) {
            return false;
        }
        this->items_or_wake_sem_ = xSemaphoreCreateBinary();
        if (this->items_or_wake_sem_ == nullptr) {
            vRingbufferDelete(this->handle_);
            this->handle_ = nullptr;
            return false;
        }
        this->storage_ = storage;
        return true;
    }

    /// @brief The storage passed to create(), or nullptr before it
    uint8_t* storage() const {
        return this->storage_;
    }

    /// @brief Whether `item` is the item placed at the very start of the storage, the one item
    /// take() can hand out before its producer completed it (see InboundRing)
    bool is_storage_head(const void* item) const {
        return item == this->storage_ + SharedRingLayout::ITEM_HEADER_BYTES;
    }

    /// @brief Whether create() succeeded
    bool is_created() const {
        return this->handle_ != nullptr;
    }

    /// @brief Reserves `size` contiguous bytes for one item. Any producer thread, concurrently
    /// with other producers, the consumer and returns.
    /// @param timeout_ms Milliseconds to wait for room: 0 does not wait, UINT32_MAX waits
    ///        indefinitely. An item larger than SharedRingLayout::max_item_size() fails at once.
    /// @return The item's bytes, to be filled and passed to complete(), or nullptr.
    void* acquire(size_t size, uint32_t timeout_ms) {
        void* ptr = nullptr;
        if (xRingbufferSendAcquire(this->handle_, &ptr, size, platform_ms_to_ticks(timeout_ms)) !=
            pdTRUE) {
            return nullptr;
        }
        return ptr;
    }

    /// @brief Publishes an acquired item to the consumer and wakes it, always, so a consumer
    /// waiting in wait_for_completion() sees every completion. The producer that acquired it.
    void complete(void* ptr) {
        xRingbufferSendComplete(this->handle_, ptr);
        xSemaphoreGive(this->items_or_wake_sem_);
    }

    /// @brief Waits for a complete() or wake_receiver() without taking anything. The single
    /// consumer only, to wait for an item it already holds to be completed.
    /// @param timeout_ms As take(). The wait may also end on a token an earlier completion left.
    void wait_for_completion(uint32_t timeout_ms) {
        if (timeout_ms != 0) {
            xSemaphoreTake(this->items_or_wake_sem_, platform_ms_to_ticks(timeout_ms));
        }
    }

    /// @brief Takes the oldest completed item. The single consumer only.
    /// @param[out] item_size The item's size as acquired.
    /// @param timeout_ms Milliseconds to wait for an item: 0 does not wait, UINT32_MAX waits
    ///        indefinitely.
    /// @return The item's bytes, valid until return_item(), or nullptr on timeout, on a
    ///         wake_receiver() interruption, or (at most once after a burst is drained) on a token
    ///         a completion left behind after its item was taken by the non-blocking poll.
    void* take(size_t* item_size, uint32_t timeout_ms) {
        // The blocking wait goes through items_or_wake_sem_, not the ring buffer's own blocking
        // receive, so wake_receiver() can interrupt it. The semaphore is binary, so a burst of
        // completions collapses into one token; the non-blocking poll below, before the wait and
        // on every call while items remain, keeps an item from being stranded behind it.
        if (this->handle_ == nullptr) {
            return nullptr;
        }
        void* item = xRingbufferReceive(this->handle_, item_size, 0);
        if (item != nullptr || timeout_ms == 0) {
            return item;
        }
        if (xSemaphoreTake(this->items_or_wake_sem_, platform_ms_to_ticks(timeout_ms)) != pdTRUE) {
            return nullptr;
        }
        return xRingbufferReceive(this->handle_, item_size, 0);
    }

    /// @brief Wakes the consumer out of a blocking take(); one-shot, and redundant wakes
    /// collapse. Any thread.
    void wake_receiver() {
        xSemaphoreGive(this->items_or_wake_sem_);
    }

    /// @brief Hands a taken item back, in any order relative to other taken items. Any thread.
    /// Its space is reclaimed only once every older item has been returned too.
    void return_item(void* ptr) {
        vRingbufferReturnItem(this->handle_, ptr);
    }

private:
    // Struct fields
    StaticRingbuffer_t structure_;

    // Pointer fields
    RingbufHandle_t handle_{nullptr};
    /// Written once by create(), before any producer or consumer runs.
    uint8_t* storage_{nullptr};
    /// Given by every complete() and by wake_receiver(); a blocking take() waits on it instead
    /// of on the ring buffer so it stays interruptible.
    SemaphoreHandle_t items_or_wake_sem_{nullptr};
};

}  // namespace sendspin

#else  // Host

#include <cassert>
#include <chrono>
#include <condition_variable>
#include <mutex>

namespace sendspin {

/**
 * @brief Multi-producer, ordered-consumer, any-order-return ring buffer (host implementation)
 *
 * Reproduces the FreeRTOS no-split ring buffer's layout and pointer rules (esp_ringbuf
 * ringbuf.c) under one mutex, so the placement, wrap, and ring-order reclamation behavior a
 * test observes here is the device's. Four offsets walk the storage in the same direction:
 * free_ (oldest unreclaimed byte), read_ (next item to take), write_ (end of the completed run),
 * acquire_ (next byte to reserve). full_ tells a full ring from an empty one when acquire_ meets
 * free_.
 */
class SharedRingBuffer {
public:
    SharedRingBuffer() = default;
    ~SharedRingBuffer() = default;

    // Not copyable or movable
    SharedRingBuffer(const SharedRingBuffer&) = delete;
    SharedRingBuffer& operator=(const SharedRingBuffer&) = delete;

    /// @brief Creates the ring over caller-provided storage. Call before any producer or
    /// consumer runs.
    /// @param size Storage size in bytes. Must satisfy SharedRingLayout::is_valid_storage_size(),
    ///        the rule FreeRTOS asserts on device; a size that does not is refused.
    /// @param storage At least `size` bytes, 4-byte aligned.
    /// @return false when the size is refused.
    bool create(size_t size, uint8_t* storage) {
        if (!SharedRingLayout::is_valid_storage_size(size)) {
            return false;
        }
        std::lock_guard<std::mutex> lock(this->mtx_);
        this->storage_ = storage;
        this->size_ = size;
        this->max_item_size_ = SharedRingLayout::max_item_size(size);
        this->acquire_ = 0;
        this->write_ = 0;
        this->read_ = 0;
        this->free_ = 0;
        this->items_waiting_ = 0;
        this->full_ = false;
        this->completion_token_ = false;
        this->wake_pending_ = false;
        return true;
    }

    /// @brief Whether create() succeeded
    bool is_created() const {
        std::lock_guard<std::mutex> lock(this->mtx_);
        return this->storage_ != nullptr;
    }

    /// @brief The storage passed to create(), or nullptr before it
    uint8_t* storage() const {
        std::lock_guard<std::mutex> lock(this->mtx_);
        return this->storage_;
    }

    /// @brief Whether `item` is the item placed at the very start of the storage (see
    /// InboundRing; the host never hands it out uncompleted)
    bool is_storage_head(const void* item) const {
        std::lock_guard<std::mutex> lock(this->mtx_);
        return item == this->storage_ + SharedRingLayout::ITEM_HEADER_BYTES;
    }

    /// @brief Reserves `size` contiguous bytes for one item. Any producer thread, concurrently
    /// with other producers, the consumer and returns.
    /// @param timeout_ms Milliseconds to wait for room: 0 does not wait, UINT32_MAX waits
    ///        indefinitely. An item larger than SharedRingLayout::max_item_size() fails at once.
    /// @return The item's bytes, to be filled and passed to complete(), or nullptr.
    void* acquire(size_t size, uint32_t timeout_ms) {
        std::unique_lock<std::mutex> lock(this->mtx_);
        if (this->storage_ == nullptr || size > this->max_item_size_) {
            return nullptr;
        }
        auto fits = [&] { return this->item_fits(size); };
        if (!fits()) {
            if (timeout_ms == 0) {
                return nullptr;
            }
            if (timeout_ms == UINT32_MAX) {
                this->cv_space_.wait(lock, fits);
            } else if (!this->cv_space_.wait_for(lock, std::chrono::milliseconds(timeout_ms),
                                                 fits)) {
                return nullptr;
            }
        }
        return this->acquire_item(size);
    }

    /// @brief Publishes an acquired item to the consumer and wakes it, always, so a consumer
    /// waiting in wait_for_completion() sees every completion. The producer that acquired it.
    void complete(void* ptr) {
        {
            std::lock_guard<std::mutex> lock(this->mtx_);
            assert((header_of(ptr)->flags & FLAG_WRITTEN) == 0 && "item completed twice");
            header_of(ptr)->flags |= FLAG_WRITTEN;
            ++this->items_waiting_;
            this->completion_token_ = true;
            // Advance the write position over the completed run from it, so an item completed
            // ahead of an older acquired one becomes takeable only once the older one completes.
            ItemHeader* header = this->header_at(this->write_);
            while ((header->flags & (FLAG_WRITTEN | FLAG_DUMMY)) != 0) {
                if ((header->flags & FLAG_DUMMY) != 0) {
                    header->flags |= FLAG_WRITTEN;
                    this->write_ = 0;
                } else {
                    this->write_ += SharedRingLayout::stored_size(header->len);
                }
                this->wrap_if_header_cannot_fit(this->write_);
                if (this->write_ == this->acquire_) {
                    break;
                }
                header = this->header_at(this->write_);
            }
        }
        this->cv_items_.notify_all();
    }

    /// @brief Takes the oldest completed item. The single consumer only.
    /// @param[out] item_size The item's size as acquired.
    /// @param timeout_ms Milliseconds to wait for an item: 0 does not wait, UINT32_MAX waits
    ///        indefinitely.
    /// @return The item's bytes, valid until return_item(), or nullptr on timeout or on a
    ///         wake_receiver() interruption.
    void* take(size_t* item_size, uint32_t timeout_ms) {
        std::unique_lock<std::mutex> lock(this->mtx_);
        if (this->item_available()) {
            return this->take_item(item_size);
        }
        if (timeout_ms == 0) {
            return nullptr;
        }
        auto ready = [&] { return this->item_available() || this->wake_pending_; };
        if (timeout_ms == UINT32_MAX) {
            this->cv_items_.wait(lock, ready);
        } else {
            this->cv_items_.wait_for(lock, std::chrono::milliseconds(timeout_ms), ready);
        }
        // A pending wake is consumed by the blocking take it ends, even when an item arrived in
        // the same window, mirroring the ESP binary-semaphore collapse.
        this->wake_pending_ = false;
        return this->item_available() ? this->take_item(item_size) : nullptr;
    }

    /// @brief Waits for a complete() or wake_receiver() without taking anything. The single
    /// consumer only, to wait for an item it already holds to be completed.
    /// @param timeout_ms As take().
    void wait_for_completion(uint32_t timeout_ms) {
        if (timeout_ms == 0) {
            return;
        }
        std::unique_lock<std::mutex> lock(this->mtx_);
        // Mirrors the ESP binary semaphore: a completion since the last wait leaves a token, so
        // one that lands between the caller's check and this wait still ends it.
        auto ready = [&] { return this->completion_token_ || this->wake_pending_; };
        if (timeout_ms == UINT32_MAX) {
            this->cv_items_.wait(lock, ready);
        } else {
            this->cv_items_.wait_for(lock, std::chrono::milliseconds(timeout_ms), ready);
        }
        this->completion_token_ = false;
        this->wake_pending_ = false;
    }

    /// @brief Wakes the consumer out of a blocking take(); one-shot, and redundant wakes
    /// collapse. Any thread.
    void wake_receiver() {
        {
            std::lock_guard<std::mutex> lock(this->mtx_);
            this->wake_pending_ = true;
        }
        this->cv_items_.notify_all();
    }

    /// @brief Hands a taken item back, in any order relative to other taken items. Any thread.
    /// Its space is reclaimed only once every older item has been returned too.
    void return_item(void* ptr) {
        {
            std::lock_guard<std::mutex> lock(this->mtx_);
            assert((header_of(ptr)->flags & FLAG_FREE) == 0 && "item returned twice");
            header_of(ptr)->flags |= FLAG_FREE;

            // Advance the free position over the run of returned items (and wrap fillers) that
            // starts at the oldest unreclaimed item, never past the next item to take. A full or
            // empty ring has free_ == read_, so one step past it is allowed to release the oldest.
            bool allow_at_read = this->full_ || this->items_waiting_ == 0;
            bool freed_any = false;
            ItemHeader* header = this->header_at(this->free_);
            while ((header->flags & (FLAG_FREE | FLAG_DUMMY)) != 0 &&
                   (this->free_ != this->read_ || allow_at_read)) {
                allow_at_read = false;
                freed_any = true;
                if ((header->flags & FLAG_DUMMY) != 0) {
                    header->flags |= FLAG_FREE;
                    this->free_ = 0;
                } else {
                    this->free_ += SharedRingLayout::stored_size(header->len);
                }
                this->wrap_if_header_cannot_fit(this->free_);
                header = this->header_at(this->free_);
            }
            if (this->full_ && freed_any) {
                this->full_ = false;
            }
        }
        this->cv_space_.notify_all();
    }

private:
    /// @brief The per-item header stored ahead of each item, SharedRingLayout::ITEM_HEADER_BYTES
    struct ItemHeader {
        uint32_t len;
        uint32_t flags;
    };
    static_assert(sizeof(ItemHeader) == SharedRingLayout::ITEM_HEADER_BYTES,
                  "the host item header must match the FreeRTOS one the layout is derived from");

    /// Item flag bits, as FreeRTOS names them (rbITEM_*_FLAG).
    static constexpr uint32_t FLAG_FREE = 1U << 0;
    static constexpr uint32_t FLAG_DUMMY = 1U << 1;
    static constexpr uint32_t FLAG_WRITTEN = 1U << 3;

    /// @brief Header stored at a storage offset
    ItemHeader* header_at(size_t offset) {
        return reinterpret_cast<ItemHeader*>(this->storage_ + offset);
    }

    /// @brief Header of the item whose bytes start at `ptr`
    static ItemHeader* header_of(void* ptr) {
        return reinterpret_cast<ItemHeader*>(static_cast<uint8_t*>(ptr) -
                                             SharedRingLayout::ITEM_HEADER_BYTES);
    }

    /// @brief Wraps an offset to the start when too little storage is left for a header
    void wrap_if_header_cannot_fit(size_t& offset) const {
        if (this->size_ - offset < SharedRingLayout::ITEM_HEADER_BYTES) {
            offset = 0;
        }
    }

    /// @brief Whether an item of `len` bytes can be acquired now (prvCheckItemFitsDefault)
    bool item_fits(size_t len) const {
        const size_t total = SharedRingLayout::stored_size(len);
        if (this->acquire_ == this->free_) {
            return !this->full_;
        }
        if (this->free_ > this->acquire_) {
            return total <= this->free_ - this->acquire_;
        }
        if (total <= this->size_ - this->acquire_) {
            return true;
        }
        // No room before the end: the item goes to the start, ahead of the oldest unreclaimed.
        return total <= this->free_;
    }

    /// @brief Reserves an item of `len` bytes that item_fits() accepted (prvAcquireItemNoSplit)
    void* acquire_item(size_t len) {
        if (this->size_ - this->acquire_ < SharedRingLayout::stored_size(len)) {
            // The tail cannot hold the item: mark it as filler and place the item at the start.
            ItemHeader* filler = this->header_at(this->acquire_);
            filler->flags = FLAG_DUMMY;
            filler->len = 0;
            this->acquire_ = 0;
        }
        ItemHeader* header = this->header_at(this->acquire_);
        header->len = static_cast<uint32_t>(len);
        header->flags = 0;
        uint8_t* item = this->storage_ + this->acquire_ + SharedRingLayout::ITEM_HEADER_BYTES;
        this->acquire_ += SharedRingLayout::stored_size(len);
        this->wrap_if_header_cannot_fit(this->acquire_);
        if (this->acquire_ == this->free_) {
            this->full_ = true;
        }
        return item;
    }

    /// @brief Whether the item at read_ is completed and can be taken (prvCheckItemAvail, except
    /// that a wrap filler at read_ also requires the item it wraps to, at the start, to be
    /// completed, which FreeRTOS does not check)
    bool item_available() {
        if (this->storage_ == nullptr || this->items_waiting_ == 0) {
            return false;
        }
        if (this->read_ == this->write_ && !this->full_) {
            return false;
        }
        const ItemHeader* header = this->header_at(this->read_);
        if ((header->flags & FLAG_DUMMY) != 0) {
            header = this->header_at(0);
        }
        return (header->flags & FLAG_WRITTEN) != 0;
    }

    /// @brief Takes the item at read_, which item_available() accepted (prvGetItemDefault)
    void* take_item(size_t* item_size) {
        ItemHeader* header = this->header_at(this->read_);
        if ((header->flags & FLAG_DUMMY) != 0) {
            this->read_ = 0;
            header = this->header_at(this->read_);
        }
        uint8_t* item = this->storage_ + this->read_ + SharedRingLayout::ITEM_HEADER_BYTES;
        *item_size = header->len;
        --this->items_waiting_;
        this->read_ += SharedRingLayout::stored_size(header->len);
        this->wrap_if_header_cannot_fit(this->read_);
        return item;
    }

    // Struct fields
    /// Signalled by complete() and wake_receiver(); a blocking take() waits on it.
    std::condition_variable cv_items_;
    /// Signalled by return_item(); a blocking acquire() waits on it.
    std::condition_variable cv_space_;
    /// Guards every field below. A leaf: nothing is called under it.
    mutable std::mutex mtx_;

    // Pointer fields
    uint8_t* storage_{nullptr};

    // size_t fields
    size_t acquire_{0};
    size_t free_{0};
    size_t items_waiting_{0};
    size_t max_item_size_{0};
    size_t read_{0};
    size_t size_{0};
    size_t write_{0};

    // 8-bit fields
    /// Set by complete(), consumed by wait_for_completion(): the host's stand-in for the token
    /// complete() gives the ESP semaphore.
    bool completion_token_{false};
    bool full_{false};
    bool wake_pending_{false};
};

}  // namespace sendspin

#endif  // ESP_PLATFORM
