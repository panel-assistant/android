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

#include "outbound_ring.h"

#include "platform/logging.h"

namespace sendspin {

static const char* const TAG = "sendspin.outbound";

bool OutboundRing::create(size_t storage_bytes, MemoryLocation location) {
    if (!this->storage_.allocate(storage_bytes, location)) {
        SS_LOGE(TAG, "Failed to allocate %zu bytes for the outbound ring", storage_bytes);
        return false;
    }
    if (!this->ring_.create(storage_bytes, this->storage_.data())) {
        SS_LOGE(TAG, "Failed to create the outbound ring over %zu bytes", storage_bytes);
        this->storage_.reset();
        return false;
    }
    const size_t max_item = SharedRingLayout::max_item_size(storage_bytes);
    this->max_message_bytes_ =
        max_item > OUTBOUND_ITEM_HEADER_BYTES ? max_item - OUTBOUND_ITEM_HEADER_BYTES : 0;
    return true;
}

void* OutboundRing::acquire(size_t message_capacity, uint32_t timeout_ms) {
    if (message_capacity > this->max_message_bytes_) {
        return nullptr;
    }
    void* item = this->ring_.acquire(OUTBOUND_ITEM_HEADER_BYTES + message_capacity, timeout_ms);
    if (item != nullptr) {
        // The storage is reused, so without this the header would carry an earlier item's fields.
        set_outbound_item_header(item, OutboundItemHeader{});
    }
    return item;
}

}  // namespace sendspin
