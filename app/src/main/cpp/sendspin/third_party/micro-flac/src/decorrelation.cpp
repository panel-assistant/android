// Copyright 2026 Kevin Ahrendt
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

#include "decorrelation.h"

#include "wrapping_arithmetic.h"

#include <cstddef>

namespace micro_flac {

template <typename SideT>
void apply_channel_decorrelation(int32_t* block_samples, const SideT* side_channel,
                                 uint32_t block_size, uint32_t channel_assignment) {
    // Channel decorrelation arithmetic uses uint32_t to avoid signed integer overflow UB.
    // The FLAC spec defines these operations with modulo-2^bps wrapping semantics.
    // SideT is int32_t for the normal path, int64_t for the wide path (32-bit audio).

    if (channel_assignment == CHANNEL_LEFT_SIDE) {
        // LEFT_SIDE: Right = Left - Side
        // Process 4 samples at a time
        size_t i = 0;
        for (; i + 3 < block_size; i += 4) {
            block_samples[block_size + i] = wsub32(block_samples[i], side_channel[i]);
            block_samples[block_size + i + 1] = wsub32(block_samples[i + 1], side_channel[i + 1]);
            block_samples[block_size + i + 2] = wsub32(block_samples[i + 2], side_channel[i + 2]);
            block_samples[block_size + i + 3] = wsub32(block_samples[i + 3], side_channel[i + 3]);
        }
        // Handle remaining samples
        for (; i < block_size; i++) {
            block_samples[block_size + i] = wsub32(block_samples[i], side_channel[i]);
        }
    } else if (channel_assignment == CHANNEL_RIGHT_SIDE) {
        // RIGHT_SIDE: Left = Side + Right
        // Process 4 samples at a time
        size_t i = 0;
        for (; i + 3 < block_size; i += 4) {
            block_samples[i] = wadd32(side_channel[i], block_samples[block_size + i]);
            block_samples[i + 1] = wadd32(side_channel[i + 1], block_samples[block_size + i + 1]);
            block_samples[i + 2] = wadd32(side_channel[i + 2], block_samples[block_size + i + 2]);
            block_samples[i + 3] = wadd32(side_channel[i + 3], block_samples[block_size + i + 3]);
        }
        // Handle remaining samples
        for (; i < block_size; i++) {
            block_samples[i] = wadd32(side_channel[i], block_samples[block_size + i]);
        }
    } else if (channel_assignment == CHANNEL_MID_SIDE) {
        // MID_SIDE: Left = Mid + Side/2, Right = Mid - Side/2
        // Arithmetic right shift for side>>1 is required by the FLAC spec.
        // With SideT=int64_t, the shift operates on the full 33-bit value.
        // Process 4 samples at a time
        size_t i = 0;
        for (; i + 3 < block_size; i += 4) {
            SideT side0 = side_channel[i];
            uint32_t right0 = u32(block_samples[i]) - u32(side0 >> 1);
            block_samples[block_size + i] = static_cast<int32_t>(right0);
            block_samples[i] = static_cast<int32_t>(right0 + u32(side0));

            SideT side1 = side_channel[i + 1];
            uint32_t right1 = u32(block_samples[i + 1]) - u32(side1 >> 1);
            block_samples[block_size + i + 1] = static_cast<int32_t>(right1);
            block_samples[i + 1] = static_cast<int32_t>(right1 + u32(side1));

            SideT side2 = side_channel[i + 2];
            uint32_t right2 = u32(block_samples[i + 2]) - u32(side2 >> 1);
            block_samples[block_size + i + 2] = static_cast<int32_t>(right2);
            block_samples[i + 2] = static_cast<int32_t>(right2 + u32(side2));

            SideT side3 = side_channel[i + 3];
            uint32_t right3 = u32(block_samples[i + 3]) - u32(side3 >> 1);
            block_samples[block_size + i + 3] = static_cast<int32_t>(right3);
            block_samples[i + 3] = static_cast<int32_t>(right3 + u32(side3));
        }
        // Handle remaining samples
        for (; i < block_size; i++) {
            SideT side = side_channel[i];
            uint32_t right = u32(block_samples[i]) - u32(side >> 1);
            block_samples[block_size + i] = static_cast<int32_t>(right);
            block_samples[i] = static_cast<int32_t>(right + u32(side));
        }
    }
}

template void apply_channel_decorrelation<int32_t>(int32_t*, const int32_t*, uint32_t, uint32_t);
template void apply_channel_decorrelation<int64_t>(int32_t*, const int64_t*, uint32_t, uint32_t);

}  // namespace micro_flac
