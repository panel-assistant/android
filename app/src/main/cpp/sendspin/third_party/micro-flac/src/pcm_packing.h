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

/// @file pcm_packing.h
/// @brief Interleaved PCM output packing for FLAC decoding
///
/// Converts planar decoded samples to interleaved PCM with optimized fast paths
/// for common formats (16-bit stereo/mono, 24-bit stereo, 32-bit output).

#pragma once

#include <cstdint>

namespace micro_flac {

/// @brief Write decoded block samples to an interleaved PCM output buffer
///
/// Dispatches to optimized fast paths for common formats (16-bit stereo/mono,
/// 24-bit stereo, 32-bit stereo/mono) and falls back to a general path for
/// other configurations.
///
/// @param output_buffer    Destination for interleaved PCM samples
/// @param block_samples    Decoded samples in planar layout [ch0...ch0, ch1...ch1, ...]
/// @param block_size       Number of samples per channel in this block
/// @param bits_per_sample     Bit depth of the decoded samples (e.g. 16, 24)
/// @param num_channels     Number of audio channels
/// @param output_32bit     If true, output all samples as 32-bit left-justified values
void write_samples(uint8_t* output_buffer, const int32_t* block_samples, uint32_t block_size,
                   uint32_t bits_per_sample, uint32_t num_channels, bool output_32bit);

}  // namespace micro_flac
