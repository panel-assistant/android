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

/// @file json_arena.h
/// @brief Bounded internal-RAM bump-arena ArduinoJson allocator with PSRAM fallback, wiping
/// every block it frees

#pragma once

#include "platform/memory.h"
#include "platform/secure_zero.h"
#include "sendspin/types.h"
#include <ArduinoJson.h>

#include <algorithm>
#include <cstddef>
#include <cstdint>
#include <cstring>
#include <optional>
#include <type_traits>
#include <utility>

namespace sendspin {

/**
 * @brief ArduinoJson allocator backed by a fixed internal-RAM byte buffer, falling back to the
 * PSRAM-preferring platform allocator when the buffer is exhausted
 *
 * ArduinoJson allocates a document's variant pool and copied strings out of its Allocator, which
 * on ESP32 would otherwise land in slow PSRAM. The client's one instance backs every JSON
 * document the protocol task works with, the parse of each incoming message and every message
 * it builds, so that traffic stays in internal RAM. Internal RAM is scarce, so the buffer is a
 * hard budget: an allocation that does not fit falls back to platform_malloc, and an
 * unexpectedly large document still works, just slowly. deallocate()/reallocate() route each
 * pointer back by checking whether it lies in the buffer.
 *
 * Bump allocator: a block freed while it is the top one is popped; any other stays stranded until
 * reset(). ArduinoJson does not free a document in LIFO order: its destructor frees the strings
 * newest first, then the variant pools oldest first. A parse copies its first key before it
 * allocates its first pool, so that key is stranded below the pool, and a document with several
 * pools strands every pool but the last. A single-pool document whose first member is a linked
 * literal allocates its pool first and so drains completely; the messages the library builds
 * start with the literal "type" key. ArduinoJson::Allocator has no "document destroyed"
 * hook, so the owner calls reset() before each inbound message, with no document live, to
 * reclaim what was stranded; nothing else resets it. It does not touch blocks that escaped to
 * PSRAM. Not thread-safe: the client's instance is used by the protocol task only.
 *
 * The arena holds one document at a time, never a parse and a reply together: a parsed message
 * is read through ParsedJsonMessage, which destroys it once its fields are copied out and before
 * anything acts on them, so the reply a handler builds starts from an arena holding only what
 * the parse stranded (its first key, for a single-pool message). A document falls back to the
 * heap only when it exceeds what the budget has left after what the parse stranded.
 *
 * Every block is wiped as it is freed, with the bytes a top block's shrinking reallocate() returns
 * to free space and the old copy a moving one leaves behind, so a document that held key material
 * (a client/pair-finalize PSK) leaves none of it in the arena or on the heap. Every block, in the
 * buffer or on the heap, carries a header recording its size for that wipe.
 *
 * A capacity of 0, or a failed buffer allocation, sends every request to the heap fallback,
 * wiped the same way.
 */
class SendspinArenaAllocator final : public ArduinoJson::Allocator {
public:
    /// @brief Constructs an arena with a buffer of @p capacity bytes in internal RAM (PSRAM
    /// fallback). A capacity of 0, or a failed allocation, makes every request fall back to the
    /// PSRAM-preferring platform allocator
    explicit SendspinArenaAllocator(size_t capacity) {
        if (capacity == 0 || !this->buffer_.allocate(capacity, MemoryLocation::PREFER_INTERNAL)) {
            return;
        }
        // Align the usable base up to ALIGNMENT so every returned block is aligned without a
        // per-allocation fixup; trim the capacity by however much was skipped, then down to a
        // multiple of ALIGNMENT.
        const auto raw = reinterpret_cast<uintptr_t>(this->buffer_.data());
        const auto aligned = (raw + (ALIGNMENT - 1)) & ~static_cast<uintptr_t>(ALIGNMENT - 1);
        const size_t skip = static_cast<size_t>(aligned - raw);
        if (skip < this->buffer_.size()) {
            this->base_ = reinterpret_cast<uint8_t*>(aligned);
            this->cap_ = (this->buffer_.size() - skip) & ~(ALIGNMENT - 1);
        }
    }

    SendspinArenaAllocator(const SendspinArenaAllocator&) = delete;
    SendspinArenaAllocator& operator=(const SendspinArenaAllocator&) = delete;

    /// @brief Allocates @p size bytes from the arena, or from the heap if it does not fit
    void* allocate(size_t size) override {
        const size_t need = HEADER_SIZE + align_up(size);
        if (need <= this->cap_ - this->offset_) {  // cap_ - offset_ is 0 when cap_ == 0
            uint8_t* hdr = this->base_ + this->offset_;
            store_size(hdr, align_up(size));
            this->offset_ += need;
            this->note_high_water();
            return hdr + HEADER_SIZE;
        }
        return heap_allocate(size);
    }

    /// @brief Wipes and frees a block previously returned by allocate() or reallocate(); null is
    /// a no-op
    void deallocate(void* ptr) override {
        if (ptr == nullptr) {
            return;
        }
        uint8_t* hdr = static_cast<uint8_t*>(ptr) - HEADER_SIZE;
        const size_t block = load_size(hdr);
        secure_zero(ptr, block);
        if (!this->in_arena(ptr)) {
            platform_free(hdr);
            return;
        }
        // If this block sits on top of the bump pointer, pop it; otherwise it stays stranded until
        // reset().
        if (static_cast<size_t>(hdr - this->base_) + HEADER_SIZE + block == this->offset_) {
            this->offset_ = static_cast<size_t>(hdr - this->base_);
        }
    }

    /// @brief Reallocates a block to a new size, preserving its existing contents and wiping
    /// whatever bytes it gives up
    void* reallocate(void* ptr, size_t new_size) override {
        if (ptr == nullptr) {
            return this->allocate(new_size);
        }
        uint8_t* hdr = static_cast<uint8_t*>(ptr) - HEADER_SIZE;
        const size_t old_block = load_size(hdr);
        if (!this->in_arena(ptr)) {
            // A heap block moves rather than growing in place, so its old copy is wiped by
            // deallocate() instead of being left to the heap.
            void* moved = this->move_block(ptr, old_block, this->allocate(new_size), new_size);
            if (moved == nullptr && new_size <= old_block) {
                // A shrink that cannot get a new block keeps the old one, as a shrinking realloc()
                // would: ArduinoJson's shrinkToFit() stores the result without a null check. The
                // header keeps the old size, so deallocate() still wipes all of it.
                secure_zero(static_cast<uint8_t*>(ptr) + new_size, old_block - new_size);
                return ptr;
            }
            return moved;
        }
        const size_t hdr_off = static_cast<size_t>(hdr - this->base_);
        const size_t new_block = align_up(new_size);
        const bool is_top = hdr_off + HEADER_SIZE + old_block == this->offset_;

        if (new_block <= old_block) {
            // Shrink (or no change) in place. A top block returns its tail to free space, so the
            // tail is wiped here. An interior block keeps its recorded size, since the tail cannot
            // be reclaimed until reset(), and deallocate() wipes the tail with the rest of it.
            if (is_top) {
                secure_zero(static_cast<uint8_t*>(ptr) + new_block, old_block - new_block);
                store_size(hdr, new_block);
                this->offset_ = hdr_off + HEADER_SIZE + new_block;
            }
            return ptr;
        }
        if (is_top && HEADER_SIZE + new_block <= this->cap_ - hdr_off) {
            // Top block that still fits: grow in place.
            store_size(hdr, new_block);
            this->offset_ = hdr_off + HEADER_SIZE + new_block;
            this->note_high_water();
            return ptr;
        }
        // Move: a top block that outgrows the buffer goes to the heap, an interior one to fresh
        // space (the arena if it fits, else the heap). deallocate() wipes the old block and pops
        // it if it was the top; an interior one stays stranded until reset(). The interior case
        // is only reachable for pathologically large messages.
        return this->move_block(
            ptr, old_block, is_top ? heap_allocate(new_size) : this->allocate(new_size), new_size);
    }

    /// @brief Discards all arena allocations, the stranded ones included; called before each
    /// inbound message, with no JsonDocument live. Every block it discards was wiped when it was
    /// freed. Does not affect (or free) blocks that escaped to PSRAM
    void reset() {
        this->offset_ = 0;
    }

    /// @brief Usable arena capacity in bytes; 0 if the backing buffer could not be allocated
    size_t capacity() const {
        return this->cap_;
    }

    /// @brief Largest number of arena bytes in use at once since construction, for tuning the
    /// budget; not cleared by reset()
    size_t high_water() const {
        return this->high_water_;
    }

private:
    /// Alignment of every block returned to ArduinoJson (matches malloc semantics).
    static constexpr size_t ALIGNMENT = alignof(std::max_align_t);
    /// Bytes reserved before each block to record its size (used by deallocate/reallocate).
    static constexpr size_t HEADER_SIZE = ALIGNMENT;
    static_assert(HEADER_SIZE >= sizeof(size_t), "block header must hold a size_t");
    static_assert((ALIGNMENT & (ALIGNMENT - 1)) == 0, "ALIGNMENT must be a power of two");

    static constexpr size_t align_up(size_t n) {
        return (n + (ALIGNMENT - 1)) & ~(ALIGNMENT - 1);
    }
    static void store_size(uint8_t* hdr, size_t value) {
        std::memcpy(hdr, &value, sizeof(value));
    }
    static size_t load_size(const uint8_t* hdr) {
        size_t value = 0;
        std::memcpy(&value, hdr, sizeof(value));
        return value;
    }
    /// @brief Allocates @p size bytes from the PSRAM-preferring heap, behind a header that
    /// records the size for deallocate()'s wipe
    static void* heap_allocate(size_t size) {
        auto* hdr = static_cast<uint8_t*>(platform_malloc(HEADER_SIZE + size));
        if (hdr == nullptr) {
            return nullptr;
        }
        store_size(hdr, size);
        return hdr + HEADER_SIZE;
    }
    /// @brief Copies a block into @p moved and frees (wipes) the old one; on a failed allocation
    /// returns nullptr and leaves the old block intact, as realloc() does
    void* move_block(void* ptr, size_t old_block, void* moved, size_t new_size) {
        if (moved == nullptr) {
            return nullptr;
        }
        std::memcpy(moved, ptr, std::min(new_size, old_block));
        this->deallocate(ptr);
        return moved;
    }
    /// @brief Whether @p p was bump-allocated from the backing buffer
    bool in_arena(const void* p) const {
        if (this->cap_ == 0) {
            return false;
        }
        // p may come from platform_malloc, an unrelated allocation; relational comparison of
        // pointers into different objects is UB, so compare the integer addresses instead.
        const auto addr = reinterpret_cast<uintptr_t>(p);
        const auto begin = reinterpret_cast<uintptr_t>(this->base_);
        return addr >= begin && addr < begin + this->cap_;
    }
    void note_high_water() {
        if (this->offset_ > this->high_water_) {
            this->high_water_ = this->offset_;
        }
    }

    // Struct fields
    /// The arena's backing buffer. Every block freed into it (and every heap fallback block) is
    /// wiped first with secure_zero(), one volatile store per byte: with a 32-bit target's 1 KB
    /// variant pool, about 2 KB of stores per JSON message parsed or built, server/time replies
    /// included, roughly 15-25 us of the protocol task at 240 MHz. The price of leaving no protocol
    /// document, key material included, behind.
    PlatformBuffer buffer_;

    // Pointer fields
    uint8_t* base_{nullptr};

    // size_t fields
    size_t cap_{0};
    size_t high_water_{0};
    size_t offset_{0};
};

/// @brief Creates a JsonDocument that allocates from the given arena, which must outlive it
inline JsonDocument make_json_document(SendspinArenaAllocator& arena) {
    return JsonDocument(&arena);
}

/**
 * @brief A parsed inbound JSON message, read once and released before anything acts on it
 *
 * Extract, then release, then act: extract() hands the root to a callable that copies what the
 * caller needs into plain values, then destroys the document, so no handler ever receives the
 * JsonObject and a reply the handler builds fits the arena beside nothing (see
 * SendspinArenaAllocator). read() looks at the root without releasing it, for a step that only
 * decides which extraction applies (a message type); release() drops a document whose payload
 * the caller does not read. None of them may be called after the document is released.
 *
 * The document is destroyed rather than clear()ed: JsonDocument::clear() frees the variant pools
 * before the strings above them, which strands the parse's pool in the arena until reset().
 */
class ParsedJsonMessage {
public:
    /// @brief Creates an empty message whose document allocates from @p arena (must outlive it)
    explicit ParsedJsonMessage(SendspinArenaAllocator& arena) : doc_(std::in_place, &arena) {}

    /// @brief Parses @p len bytes of JSON text, copying its strings into the document
    /// @return false on a parse error or an empty (null) document
    bool parse(const char* data, size_t len) {
        if (!this->doc_) {
            return false;
        }
        JsonDocument& doc = *this->doc_;
        return !deserializeJson(doc, data, len) && !doc.isNull();
    }

    /// @brief Calls @p reader with the root object and returns its result, keeping the document
    template <typename Reader>
    auto read(Reader&& reader) {
        return reader(this->root());
    }

    /// @brief Calls @p extractor with the root object, releases the document, then returns what
    /// @p extractor returned
    template <typename Extractor>
    auto extract(Extractor&& extractor) {
        if constexpr (std::is_void_v<std::invoke_result_t<Extractor&, JsonObject>>) {
            extractor(this->root());
            this->release();
        } else {
            auto extracted = extractor(this->root());
            this->release();
            return extracted;
        }
    }

    /// @brief extract() through one of the protocol's process_*() parsers: fills @p out and
    /// returns whether the message was valid. The parser is a template argument so the call to
    /// it is direct, which keeps it visible to the static stack analysis (tools/stack_usage/)
    template <auto Parser, typename T>
    bool extract(T* out) {
        const bool valid = Parser(this->root(), out);
        this->release();
        return valid;
    }

    /// @brief Destroys the document, wiping and freeing its blocks
    void release() {
        this->doc_.reset();
    }

private:
    /// @brief The root object, or a null object once the document is released
    JsonObject root() {
        return this->doc_ ? this->doc_->as<JsonObject>() : JsonObject();
    }

    // Struct fields
    /// Engaged from construction until release().
    std::optional<JsonDocument> doc_;
};

}  // namespace sendspin
