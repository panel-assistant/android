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

/// @file decorrelation.h
/// @brief Stereo channel decorrelation for FLAC joint stereo modes
///
/// Converts left-side, right-side, and mid-side channel assignments back to
/// independent left/right channels after subframe decoding.

#pragma once

#include <cstdint>

namespace micro_flac {

// FLAC joint stereo channel assignment codes (RFC 9639 Section 9.1.3)
static constexpr uint32_t CHANNEL_LEFT_SIDE = 8;
static constexpr uint32_t CHANNEL_RIGHT_SIDE = 9;
static constexpr uint32_t CHANNEL_MID_SIDE = 10;

/// @brief Apply stereo channel decorrelation for joint stereo modes
///
/// Converts left-side (8), right-side (9), or mid-side (10) channel assignments
/// back to independent left/right channels.
///
/// SideT is int32_t for the normal path (side_channel points into block_samples)
/// and int64_t for the wide path (32-bit audio, where the side channel requires 33 bits).
///
/// @param block_samples     Planar sample buffer [left_0..left_N, right_0..right_N]
/// @param side_channel      Side channel samples (int32_t or int64_t)
/// @param block_size        Number of samples per channel
/// @param channel_assignment FLAC channel assignment code (8, 9, or 10)
template <typename SideT>
void apply_channel_decorrelation(int32_t* block_samples, const SideT* side_channel,
                                 uint32_t block_size, uint32_t channel_assignment);

}  // namespace micro_flac
