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

/// @file audio_utils.h
/// @brief Inline helpers for packing and unpacking PCM audio samples to and from Q31 fixed-point

#pragma once

#include <cstddef>
#include <cstdint>

namespace sendspin {

/// @brief Unpacks a quantized audio sample into a Q31 fixed-point number.
/// @param data Pointer to uint8_t array containing the audio sample (little-endian).
/// @param bytes_per_sample The number of bytes per sample (1-4).
/// @return Q31 sample.
inline int32_t unpack_audio_sample_to_q31(const uint8_t* data, size_t bytes_per_sample) {
    int32_t sample = 0;
    if (bytes_per_sample == 1) {
        sample |= data[0] << 24;
    } else if (bytes_per_sample == 2) {
        sample |= data[0] << 16;
        sample |= data[1] << 24;
    } else if (bytes_per_sample == 3) {
        sample |= data[0] << 8;
        sample |= data[1] << 16;
        sample |= data[2] << 24;
    } else if (bytes_per_sample == 4) {
        sample |= data[0];
        sample |= data[1] << 8;
        sample |= data[2] << 16;
        sample |= data[3] << 24;
    }
    return sample;
}

/// @brief Packs a Q31 fixed-point number as an audio sample with the specified number of bytes per
/// sample.
/// @note Packs the most significant bits - no dithering is applied.
/// @param sample Q31 fixed-point number to pack.
/// @param data Pointer to data array to store the packed sample.
/// @param bytes_per_sample The audio data's bytes per sample (1-4).
inline void pack_q31_as_audio_sample(int32_t sample, uint8_t* data, size_t bytes_per_sample) {
    if (bytes_per_sample == 1) {
        data[0] = static_cast<uint8_t>(sample >> 24);
    } else if (bytes_per_sample == 2) {
        data[0] = static_cast<uint8_t>(sample >> 16);
        data[1] = static_cast<uint8_t>(sample >> 24);
    } else if (bytes_per_sample == 3) {
        data[0] = static_cast<uint8_t>(sample >> 8);
        data[1] = static_cast<uint8_t>(sample >> 16);
        data[2] = static_cast<uint8_t>(sample >> 24);
    } else if (bytes_per_sample == 4) {
        data[0] = static_cast<uint8_t>(sample);
        data[1] = static_cast<uint8_t>(sample >> 8);
        data[2] = static_cast<uint8_t>(sample >> 16);
        data[3] = static_cast<uint8_t>(sample >> 24);
    }
}

}  // namespace sendspin
