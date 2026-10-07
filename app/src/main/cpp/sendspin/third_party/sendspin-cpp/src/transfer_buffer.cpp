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

#include "transfer_buffer.h"

#include "sendspin/player_role.h"

#include <cstring>

namespace sendspin {

// ============================================================================
// Constructor / Destructor
// ============================================================================

TransferBuffer::~TransferBuffer() {
    this->deallocate_buffer();
}

std::unique_ptr<TransferBuffer> TransferBuffer::create(size_t buffer_size,
                                                       MemoryLocation location) {
    std::unique_ptr<TransferBuffer> buffer(new TransferBuffer());

    if (!buffer->allocate_buffer(buffer_size, location)) {
        return nullptr;
    }

    return buffer;
}

// ============================================================================
// Public API
// ============================================================================

size_t TransferBuffer::transfer_data_to_sink(uint32_t timeout_ms) {
    size_t bytes_written = 0;
    if (this->available() > 0 && this->listener_) {
        bytes_written =
            this->listener_->on_audio_write(this->data_start_, this->available(), timeout_ms);
        this->decrease_buffer_length(bytes_written);
    }

    return bytes_written;
}

size_t TransferBuffer::free() const {
    if (this->buffer_.size() == 0) {
        return 0;
    }
    return this->buffer_.size() -
           (this->buffer_length_ + (this->data_start_ - this->buffer_.data()));
}

void TransferBuffer::decrease_buffer_length(size_t bytes) {
    this->buffer_length_ -= bytes;
    if (this->buffer_length_ > 0) {
        this->data_start_ += bytes;
    } else {
        this->data_start_ = this->buffer_.data();
    }
}

void TransferBuffer::increase_buffer_length(size_t bytes) {
    this->buffer_length_ += bytes;
}

bool TransferBuffer::reallocate(size_t new_buffer_size) {
    if (new_buffer_size < this->buffer_length_) {
        return false;
    }

    // Shift existing data to the start so realloc preserves it
    if ((this->buffer_length_ > 0) && (this->data_start_ != this->buffer_.data())) {
        std::memmove(this->buffer_.data(), this->data_start_, this->buffer_length_);
        this->data_start_ = this->buffer_.data();
    }

    if (!this->buffer_.realloc(new_buffer_size)) {
        return false;
    }

    this->data_start_ = this->buffer_.data();
    return true;
}

// ============================================================================
// Private helpers
// ============================================================================

bool TransferBuffer::allocate_buffer(size_t buffer_size, MemoryLocation location) {
    if (!this->buffer_.allocate(buffer_size, location)) {
        return false;
    }

    this->data_start_ = this->buffer_.data();
    this->buffer_length_ = 0;
    return true;
}

void TransferBuffer::deallocate_buffer() {
    this->buffer_.reset();
    this->data_start_ = nullptr;
    this->buffer_length_ = 0;
}

}  // namespace sendspin
