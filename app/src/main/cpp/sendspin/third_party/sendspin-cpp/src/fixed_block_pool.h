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

/// @file fixed_block_pool.h
/// @brief Fixed number of equal-size memory blocks stored inside the object

#pragma once

#include <array>
#include <atomic>
#include <bit>
#include <cassert>
#include <cstddef>
#include <cstdint>

namespace sendspin {

/// @brief BLOCK_COUNT raw blocks of BLOCK_SIZE bytes each, stored inline
///
/// Any thread may acquire and release blocks: a block's claim is one bit in an atomic mask, so
/// neither operation locks. Blocks are raw storage aligned to max_align_t; the caller
/// constructs and destroys whatever it places in one.
template <size_t BLOCK_SIZE, size_t BLOCK_COUNT>
class FixedBlockPool {
    static_assert(BLOCK_COUNT >= 1 && BLOCK_COUNT <= 32, "the claim mask is one uint32_t");
    static_assert(BLOCK_SIZE % alignof(std::max_align_t) == 0,
                  "every block must start max_align_t-aligned");

public:
    FixedBlockPool() = default;
    FixedBlockPool(const FixedBlockPool&) = delete;
    FixedBlockPool& operator=(const FixedBlockPool&) = delete;

    /// @brief Claims a free block that can hold `size` bytes.
    /// @return The block, or nullptr if `size` exceeds BLOCK_SIZE or every block is claimed.
    void* try_acquire(size_t size) {
        if (size > BLOCK_SIZE) {
            return nullptr;
        }
        uint32_t free_mask = this->free_mask_.load(std::memory_order_acquire);
        while (free_mask != 0) {
            const int index = std::countr_zero(free_mask);
            if (this->free_mask_.compare_exchange_weak(free_mask, free_mask & ~(1U << index),
                                                       std::memory_order_acquire,
                                                       std::memory_order_acquire)) {
                return this->storage_.data() + static_cast<size_t>(index) * BLOCK_SIZE;
            }
        }
        return nullptr;
    }

    /// @brief Returns a block claimed by try_acquire(). The caller must have destroyed any
    /// object it placed there.
    void release(void* block) {
        const size_t offset = static_cast<std::byte*>(block) - this->storage_.data();
        assert(offset < BLOCK_SIZE * BLOCK_COUNT && offset % BLOCK_SIZE == 0 &&
               "FixedBlockPool::release() of a block it does not own");
        this->free_mask_.fetch_or(1U << (offset / BLOCK_SIZE), std::memory_order_release);
    }

    /// @brief Calls `fn(block)` for every claimed block. Only for when no thread can claim or
    /// release a block, such as before reset() reclaims blocks whose holder will never run.
    template <typename Fn>
    void for_each_claimed(Fn&& fn) {
        uint32_t claimed = ~this->free_mask_.load(std::memory_order_acquire) & ALL_FREE;
        while (claimed != 0) {
            const int index = std::countr_zero(claimed);
            claimed &= claimed - 1;
            fn(static_cast<void*>(this->storage_.data() + static_cast<size_t>(index) * BLOCK_SIZE));
        }
    }

    /// @brief Marks every block free. Only for when no thread can hold or claim a block: a block
    /// still claimed is abandoned, and whatever its holder placed in it is never destroyed.
    void reset() {
        this->free_mask_.store(ALL_FREE, std::memory_order_release);
    }

    /// @brief Bytes available in each block.
    static constexpr size_t block_size() {
        return BLOCK_SIZE;
    }

private:
    static constexpr uint32_t ALL_FREE =
        BLOCK_COUNT == 32 ? UINT32_MAX : (uint32_t{1} << BLOCK_COUNT) - 1;

    // Struct fields
    alignas(std::max_align_t) std::array<std::byte, BLOCK_SIZE * BLOCK_COUNT> storage_;

    // 32-bit fields
    std::atomic<uint32_t> free_mask_{ALL_FREE};
};

}  // namespace sendspin
