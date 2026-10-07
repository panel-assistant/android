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

/// @file event_flags.h
/// @brief Platform-abstracted event flag group backed by a FreeRTOS event group on ESP and
/// condition variables on host

#pragma once

#include <cstdint>

#ifdef ESP_PLATFORM

// ESP-IDF: thin wrapper around FreeRTOS event group (uses direct task notifications)
#include "platform/time.h"
#include <freertos/FreeRTOS.h>
#include <freertos/event_groups.h>

namespace sendspin {

/**
 * @brief Bit-flag group that supports blocking waits on one or more bits
 *
 * Backed by a FreeRTOS event group on ESP and a mutex/condition-variable pair on host.
 * Threads can block until any or all of a set of bits are set, with an optional timeout.
 * Call create() before any other method.
 *
 * Usage:
 * 1. Declare an EventFlags member and call create() during initialization
 * 2. Set bits from any thread with set()
 * 3. Block a thread until desired bits appear with wait()
 * 4. Clear bits explicitly with clear() when no longer needed
 *
 * @code
 * EventFlags flags;
 * flags.create();
 *
 * // In one thread:
 * flags.set(0x01);
 *
 * // In another thread:
 * uint32_t bits = flags.wait(0x01, false, true, 1000);
 * @endcode
 */
class EventFlags {
public:
    EventFlags() = default;
    ~EventFlags() {
        if (this->handle_ != nullptr) {
            vEventGroupDelete(this->handle_);
        }
    }

    // Not copyable or movable
    EventFlags(const EventFlags&) = delete;
    EventFlags& operator=(const EventFlags&) = delete;

    /// @brief Creates the event flags group
    /// @return true on success.
    bool create() {
        this->handle_ = xEventGroupCreate();
        return this->handle_ != nullptr;
    }

    /// @brief Returns true if the event flags group has been successfully created
    /// @return true if the event group is ready for use.
    bool is_created() const {
        return this->handle_ != nullptr;
    }

    /// @brief Sets the specified bits
    /// @param bits Bitmask of bits to set.
    /// @return Resulting bit pattern after the set.
    uint32_t set(uint32_t bits) {
        return xEventGroupSetBits(this->handle_, bits);
    }

    /// @brief Clears the specified bits
    /// @param bits Bitmask of bits to clear.
    /// @return Bit pattern captured before clearing.
    uint32_t clear(uint32_t bits) {
        return xEventGroupClearBits(this->handle_, bits);
    }

    /// @brief Clears every bit, returning the group to its freshly created state
    ///
    /// For resetting a group whose consumer thread has been joined before a new one starts, so
    /// no caller has to enumerate the bits its group defines.
    /// @return Bit pattern captured before clearing.
    uint32_t clear_all() {
        return xEventGroupClearBits(this->handle_, USABLE_BITS);
    }

    /// @brief Returns the current bit pattern
    /// @return Current bit pattern.
    uint32_t get() const {
        return xEventGroupGetBits(this->handle_);
    }

    /// @brief Waits for bits to be set
    /// @param bits_to_wait Bitmask of bits to wait for.
    /// @param wait_all If true, wait for ALL bits; if false, wait for ANY bit.
    /// @param clear_on_exit If true, clear the waited bits before returning.
    /// @param timeout_ms Milliseconds to wait (UINT32_MAX = wait forever).
    /// @return The bit pattern at the time the wait completed or timed out.
    uint32_t wait(uint32_t bits_to_wait, bool wait_all, bool clear_on_exit, uint32_t timeout_ms) {
        return xEventGroupWaitBits(this->handle_, bits_to_wait, clear_on_exit ? pdTRUE : pdFALSE,
                                   wait_all ? pdTRUE : pdFALSE, platform_ms_to_ticks(timeout_ms));
    }

private:
    /// The bits a FreeRTOS event group exposes: 24 with 32-bit ticks, 8 with 16-bit ticks. The
    /// upper bits are reserved by the kernel and must never be passed to the clear/set calls.
#if configUSE_16_BIT_TICKS == 1
    static constexpr uint32_t USABLE_BITS = 0x00FFU;
#else
    static constexpr uint32_t USABLE_BITS = 0x00FFFFFFU;
#endif

    // Pointer fields
    EventGroupHandle_t handle_{nullptr};
};

}  // namespace sendspin

#else  // Host

#include <chrono>
#include <condition_variable>
#include <mutex>

namespace sendspin {

/**
 * @brief Bit-flag group that supports blocking waits on one or more bits
 *
 * Backed by a mutex/condition-variable pair on host. Threads can block until any or
 * all of a set of bits are set, with an optional timeout. Call create() before any
 * other method.
 *
 * Usage:
 * 1. Declare an EventFlags member and call create() during initialization
 * 2. Set bits from any thread with set()
 * 3. Block a thread until desired bits appear with wait()
 * 4. Clear bits explicitly with clear() when no longer needed
 *
 * @code
 * EventFlags flags;
 * flags.create();
 *
 * // In one thread:
 * flags.set(0x01);
 *
 * // In another thread:
 * uint32_t bits = flags.wait(0x01, false, true, 1000);
 * @endcode
 */
class EventFlags {
public:
    EventFlags() = default;
    ~EventFlags() = default;

    // Not copyable or movable
    EventFlags(const EventFlags&) = delete;
    EventFlags& operator=(const EventFlags&) = delete;

    /// @brief Creates the event flags group
    /// @return true on success.
    bool create() {
        this->created_ = true;
        return true;
    }

    /// @brief Returns true if the event flags group has been successfully created
    /// @return true if the event group is ready for use.
    bool is_created() const {
        return this->created_;
    }

    /// @brief Sets the specified bits
    /// @param bits Bitmask of bits to set.
    /// @return Resulting bit pattern after the set.
    uint32_t set(uint32_t bits) {
        std::lock_guard<std::mutex> lock(this->mtx_);
        this->bits_ |= bits;
        this->cv_.notify_all();
        return this->bits_;
    }

    /// @brief Clears the specified bits
    /// @param bits Bitmask of bits to clear.
    /// @return Bit pattern captured before clearing.
    uint32_t clear(uint32_t bits) {
        std::lock_guard<std::mutex> lock(this->mtx_);
        uint32_t old = this->bits_;
        this->bits_ &= ~bits;
        return old;
    }

    /// @brief Clears every bit, returning the group to its freshly created state
    ///
    /// For resetting a group whose consumer thread has been joined before a new one starts, so
    /// no caller has to enumerate the bits its group defines.
    /// @return Bit pattern captured before clearing.
    uint32_t clear_all() {
        return this->clear(~0U);
    }

    /// @brief Returns the current bit pattern
    /// @return Current bit pattern.
    uint32_t get() const {
        std::lock_guard<std::mutex> lock(this->mtx_);
        return this->bits_;
    }

    /// @brief Waits for bits to be set
    /// @param bits_to_wait Bitmask of bits to wait for.
    /// @param wait_all If true, wait for ALL bits; if false, wait for ANY bit.
    /// @param clear_on_exit If true, clear the waited bits before returning.
    /// @param timeout_ms Milliseconds to wait (UINT32_MAX = wait forever).
    /// @return The bit pattern at the time the wait completed or timed out.
    uint32_t wait(uint32_t bits_to_wait, bool wait_all, bool clear_on_exit, uint32_t timeout_ms) {
        std::unique_lock<std::mutex> lock(this->mtx_);

        auto pred = [&]() -> bool {
            if (wait_all) {
                return (this->bits_ & bits_to_wait) == bits_to_wait;
            }
            return (this->bits_ & bits_to_wait) != 0;
        };

        if (!pred()) {
            if (timeout_ms == 0) {
                return this->bits_;
            }
            if (timeout_ms == UINT32_MAX) {
                this->cv_.wait(lock, pred);
            } else {
                this->cv_.wait_for(lock, std::chrono::milliseconds(timeout_ms), pred);
            }
        }

        uint32_t result = this->bits_;
        if (clear_on_exit && pred()) {
            this->bits_ &= ~bits_to_wait;
        }
        return result;
    }

private:
    // Struct fields
    std::condition_variable cv_;
    mutable std::mutex mtx_;

    // 32-bit fields
    uint32_t bits_{0};

    // 8-bit fields
    bool created_{false};
};

}  // namespace sendspin

#endif  // ESP_PLATFORM
