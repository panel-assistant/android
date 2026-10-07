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

/// @file transfer_buffer.h
/// @brief Simple flat byte buffer with read/write cursors for transferring decoded audio to the
/// audio output sink

#pragma once

#include "platform/memory.h"
#include "sendspin/types.h"

#include <cstddef>
#include <cstdint>
#include <memory>

namespace sendspin {

class PlayerRoleListener;

/**
 * @brief Simple flat byte buffer with read/write cursors for transferring decoded audio to the
 * audio output sink.
 *
 * Manages a flat byte array with read/write cursors for audio data transfer. Data is written at
 * get_buffer_end() and read from get_buffer_start(). Supports dynamic reallocation and flushing
 * accumulated data to a PlayerRoleListener sink.
 *
 * Usage:
 *   1. Call create() to allocate the backing buffer.
 *   2. Call set_listener() to register the audio sink.
 *   3. Write decoded audio to the buffer via the internal methods.
 *   4. Call transfer_data_to_sink() to flush buffered data to the listener.
 *
 * @code
 * auto buf = TransferBuffer::create(4096, MemoryLocation::PREFER_EXTERNAL);
 * buf->set_listener(&my_player_listener);
 * // ... write decoded audio into buf->get_buffer_end() ...
 * buf->increase_buffer_length(bytes_written);
 * buf->transfer_data_to_sink(100);
 * @endcode
 */
class TransferBuffer {
public:
    ~TransferBuffer();

    /// @brief Creates a new transfer buffer.
    /// @param buffer_size Size of the buffer in bytes.
    /// @param location Memory placement preference for the backing allocation. Subsequent
    /// reallocate() calls reuse this preference.
    /// @return unique_ptr if successfully allocated, nullptr otherwise.
    static std::unique_ptr<TransferBuffer> create(size_t buffer_size, MemoryLocation location);

    /// @brief Writes buffered data to the sink.
    /// @param timeout_ms Milliseconds to block while waiting for the sink (UINT32_MAX = wait
    /// forever).
    /// @return Number of bytes written to the sink.
    size_t transfer_data_to_sink(uint32_t timeout_ms);

    /// @brief Returns the number of bytes available to read.
    /// @return Number of bytes currently held in the buffer.
    size_t available() const {
        return this->buffer_length_;
    }

    /// @brief Returns a pointer past the end of available data (where new data can be written).
    /// @return Pointer to the first writable byte after the current data.
    uint8_t* get_buffer_end() const {
        return this->data_start_ + this->buffer_length_;
    }

    /// @brief Returns a pointer to the start of available data.
    /// @return Pointer to the first byte of buffered data ready to be read.
    uint8_t* get_buffer_start() const {
        return this->data_start_;
    }

    /// @brief Returns the allocated capacity in bytes.
    /// @return Total size of the backing allocation in bytes.
    size_t capacity() const {
        return this->buffer_.size();
    }

    /// @brief Returns the number of free bytes available to write.
    /// @return Number of bytes that can be written before the buffer is full.
    size_t free() const;

    /// @brief Sets the player listener for transfer_data_to_sink().
    /// @param listener Pointer to the listener that receives audio data via on_audio_write().
    void set_listener(PlayerRoleListener* listener) {
        this->listener_ = listener;
    }

    /// @brief Advances the read cursor after data has been consumed.
    /// @param bytes Number of bytes consumed from the start of the buffer.
    void decrease_buffer_length(size_t bytes);

    /// @brief Advances the write cursor after data has been written.
    /// @param bytes Number of bytes written past the current end of the buffer.
    void increase_buffer_length(size_t bytes);

    /// @brief Reallocates the buffer, preserving existing data.
    /// @param new_buffer_size New size in bytes. Must be >= available().
    /// @return True if successful, false otherwise (original buffer remains valid).
    bool reallocate(size_t new_buffer_size);

protected:
    TransferBuffer() = default;

    /// @brief Allocates the backing buffer and resets tracking state.
    /// @return True if allocation succeeded, false otherwise.
    bool allocate_buffer(size_t buffer_size, MemoryLocation location);
    /// @brief Releases the backing buffer and resets all tracking state.
    void deallocate_buffer();

    // Struct fields
    PlatformBuffer buffer_;

    // Pointer fields
    uint8_t* data_start_{nullptr};
    PlayerRoleListener* listener_{nullptr};

    // size_t fields
    size_t buffer_length_{0};
};

}  // namespace sendspin
