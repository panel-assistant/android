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

#include "pcm_packing.h"

#include "compiler.h"
#include "wrapping_arithmetic.h"

namespace micro_flac {

FLAC_OPTIMIZE_O3
static void write_samples_nch(uint8_t* output_buffer, const int32_t* block_samples,
                              uint32_t block_size, uint32_t bytes_per_sample, uint32_t shift_amount,
                              uint32_t num_channels) {
    uint32_t output_index = 0;

    for (uint32_t i = 0; i < block_size; i++) {
        for (uint32_t j = 0; j < num_channels; j++) {
            int32_t sample = block_samples[(j * block_size) + i];
            sample = wshl32(sample, shift_amount);

            for (uint32_t byte = 0; byte < bytes_per_sample; byte++) {
                output_buffer[output_index++] =
                    static_cast<uint8_t>(wshr32(sample, byte * 8) & 0xFF);
            }
        }
    }
}

FLAC_OPTIMIZE_O3
static void write_samples_16bit_1ch(uint8_t* output_buffer, const int32_t* block_samples,
                                    uint32_t block_size) {
    // 16-bit mono fast path with pointer arithmetic and loop unrolling
    int16_t* output_samples = reinterpret_cast<int16_t*>(output_buffer);
    const int32_t* samples = block_samples;

    uint32_t i = 0;
    const uint32_t unroll_limit = block_size & ~3U;  // Round down to multiple of 4

    // Process 4 samples at a time
    for (; i < unroll_limit; i += 4) {
        output_samples[i] = static_cast<int16_t>(samples[i]);
        output_samples[i + 1] = static_cast<int16_t>(samples[i + 1]);
        output_samples[i + 2] = static_cast<int16_t>(samples[i + 2]);
        output_samples[i + 3] = static_cast<int16_t>(samples[i + 3]);
    }

    // Handle remaining samples
    for (; i < block_size; ++i) {
        output_samples[i] = static_cast<int16_t>(samples[i]);
    }
}

FLAC_OPTIMIZE_O3
static void write_samples_16bit_2ch(uint8_t* output_buffer, const int32_t* block_samples,
                                    uint32_t block_size) {
    // 16-bit stereo fast path with pointer arithmetic and 4-sample unrolling
    int16_t* output_samples = reinterpret_cast<int16_t*>(output_buffer);
    const int32_t* left = block_samples;
    const int32_t* right = block_samples + block_size;

    uint32_t i = 0;
    const uint32_t unroll_limit = block_size & ~3U;  // Round down to multiple of 4

    // Process 4 samples at a time
    for (; i < unroll_limit; i += 4) {
        output_samples[0] = static_cast<int16_t>(left[i]);
        output_samples[1] = static_cast<int16_t>(right[i]);
        output_samples[2] = static_cast<int16_t>(left[i + 1]);
        output_samples[3] = static_cast<int16_t>(right[i + 1]);
        output_samples[4] = static_cast<int16_t>(left[i + 2]);
        output_samples[5] = static_cast<int16_t>(right[i + 2]);
        output_samples[6] = static_cast<int16_t>(left[i + 3]);
        output_samples[7] = static_cast<int16_t>(right[i + 3]);
        output_samples += 8;
    }

    // Handle remaining samples
    for (; i < block_size; ++i) {
        output_samples[0] = static_cast<int16_t>(left[i]);
        output_samples[1] = static_cast<int16_t>(right[i]);
        output_samples += 2;
    }
}

FLAC_OPTIMIZE_O3
static void write_samples_24bit_2ch(uint8_t* output_buffer, const int32_t* block_samples,
                                    uint32_t block_size) {
    // 24-bit stereo fast path with 2-sample unrolling (due to larger sample size)
    uint32_t output_index = 0;
    uint32_t i = 0;
    const uint32_t unroll_limit = block_size & ~1U;  // Round down to multiple of 2

    // Process 2 samples at a time
    for (; i < unroll_limit; i += 2) {
        // Sample 0 - Left and Right channels
        int32_t sample_0_l = block_samples[i];
        int32_t sample_0_r = block_samples[block_size + i];
        // Sample 1 - Left and Right channels
        int32_t sample_1_l = block_samples[i + 1];
        int32_t sample_1_r = block_samples[block_size + i + 1];

        // Direct 24-bit writes (little-endian)
        output_buffer[output_index++] = static_cast<uint8_t>(sample_0_l & 0xFF);
        output_buffer[output_index++] = static_cast<uint8_t>((sample_0_l >> 8) & 0xFF);
        output_buffer[output_index++] = static_cast<uint8_t>((sample_0_l >> 16) & 0xFF);
        output_buffer[output_index++] = static_cast<uint8_t>(sample_0_r & 0xFF);
        output_buffer[output_index++] = static_cast<uint8_t>((sample_0_r >> 8) & 0xFF);
        output_buffer[output_index++] = static_cast<uint8_t>((sample_0_r >> 16) & 0xFF);

        output_buffer[output_index++] = static_cast<uint8_t>(sample_1_l & 0xFF);
        output_buffer[output_index++] = static_cast<uint8_t>((sample_1_l >> 8) & 0xFF);
        output_buffer[output_index++] = static_cast<uint8_t>((sample_1_l >> 16) & 0xFF);
        output_buffer[output_index++] = static_cast<uint8_t>(sample_1_r & 0xFF);
        output_buffer[output_index++] = static_cast<uint8_t>((sample_1_r >> 8) & 0xFF);
        output_buffer[output_index++] = static_cast<uint8_t>((sample_1_r >> 16) & 0xFF);
    }

    // Handle remaining samples
    for (; i < block_size; ++i) {
        int32_t sample_l = block_samples[i];
        int32_t sample_r = block_samples[block_size + i];

        output_buffer[output_index++] = static_cast<uint8_t>(sample_l & 0xFF);
        output_buffer[output_index++] = static_cast<uint8_t>((sample_l >> 8) & 0xFF);
        output_buffer[output_index++] = static_cast<uint8_t>((sample_l >> 16) & 0xFF);
        output_buffer[output_index++] = static_cast<uint8_t>(sample_r & 0xFF);
        output_buffer[output_index++] = static_cast<uint8_t>((sample_r >> 8) & 0xFF);
        output_buffer[output_index++] = static_cast<uint8_t>((sample_r >> 16) & 0xFF);
    }
}

FLAC_OPTIMIZE_O3
static void write_samples_32bit_1ch(uint8_t* output_buffer, const int32_t* block_samples,
                                    uint32_t block_size, uint32_t shift_amount) {
    // 32-bit mono fast path with pointer arithmetic and loop unrolling
    int32_t* output_samples = reinterpret_cast<int32_t*>(output_buffer);
    const int32_t* samples = block_samples;

    uint32_t i = 0;
    const uint32_t unroll_limit = block_size & ~3U;  // Round down to multiple of 4

    // Process 4 samples at a time
    for (; i < unroll_limit; i += 4) {
        output_samples[i] = wshl32(samples[i], shift_amount);
        output_samples[i + 1] = wshl32(samples[i + 1], shift_amount);
        output_samples[i + 2] = wshl32(samples[i + 2], shift_amount);
        output_samples[i + 3] = wshl32(samples[i + 3], shift_amount);
    }

    // Handle remaining samples
    for (; i < block_size; ++i) {
        output_samples[i] = wshl32(samples[i], shift_amount);
    }
}

FLAC_OPTIMIZE_O3
static void write_samples_32bit_2ch(uint8_t* output_buffer, const int32_t* block_samples,
                                    uint32_t block_size, uint32_t shift_amount) {
    // 32-bit stereo fast path with pointer arithmetic and 4-sample unrolling
    int32_t* output_samples = reinterpret_cast<int32_t*>(output_buffer);
    const int32_t* left = block_samples;
    const int32_t* right = block_samples + block_size;

    uint32_t i = 0;
    const uint32_t unroll_limit = block_size & ~3U;  // Round down to multiple of 4

    // Process 4 samples at a time
    for (; i < unroll_limit; i += 4) {
        output_samples[0] = wshl32(left[i], shift_amount);
        output_samples[1] = wshl32(right[i], shift_amount);
        output_samples[2] = wshl32(left[i + 1], shift_amount);
        output_samples[3] = wshl32(right[i + 1], shift_amount);
        output_samples[4] = wshl32(left[i + 2], shift_amount);
        output_samples[5] = wshl32(right[i + 2], shift_amount);
        output_samples[6] = wshl32(left[i + 3], shift_amount);
        output_samples[7] = wshl32(right[i + 3], shift_amount);
        output_samples += 8;
    }

    // Handle remaining samples
    for (; i < block_size; ++i) {
        output_samples[0] = wshl32(left[i], shift_amount);
        output_samples[1] = wshl32(right[i], shift_amount);
        output_samples += 2;
    }
}

FLAC_OPTIMIZE_O3
static void write_samples_32bit_nch(uint8_t* output_buffer, const int32_t* block_samples,
                                    uint32_t block_size, uint32_t shift_amount,
                                    uint32_t num_channels) {
    int32_t* output_samples = reinterpret_cast<int32_t*>(output_buffer);
    uint32_t output_index = 0;

    for (uint32_t i = 0; i < block_size; ++i) {
        for (uint32_t ch = 0; ch < num_channels; ++ch) {
            output_samples[output_index++] =
                wshl32(block_samples[ch * block_size + i], shift_amount);
        }
    }
}

void write_samples(uint8_t* output_buffer, const int32_t* block_samples, uint32_t block_size,
                   uint32_t bits_per_sample, uint32_t num_channels, bool output_32bit) {
    // Check output buffer alignment for fast paths that use reinterpret_cast.
    const bool aligned_4 = (reinterpret_cast<uintptr_t>(output_buffer) % 4) == 0;
    const bool aligned_2 = (reinterpret_cast<uintptr_t>(output_buffer) % 2) == 0;

    if (output_32bit) {
        // 32-bit output mode: all samples output as 4 bytes, left-justified (MSB-aligned)
        uint32_t shift_amount = 32 - bits_per_sample;

        if (aligned_4 && num_channels == 1) {
            write_samples_32bit_1ch(output_buffer, block_samples, block_size, shift_amount);
        } else if (aligned_4 && num_channels == 2) {
            write_samples_32bit_2ch(output_buffer, block_samples, block_size, shift_amount);
        } else if (aligned_4) {
            write_samples_32bit_nch(output_buffer, block_samples, block_size, shift_amount,
                                    num_channels);
        } else {
            write_samples_nch(output_buffer, block_samples, block_size, 4, shift_amount,
                              num_channels);
        }
    } else {
        // Native output mode: pack to nearest byte boundary
        uint32_t bytes_per_sample = (bits_per_sample + 7) / 8;
        uint32_t shift_amount = 0;
        if (bits_per_sample % 8 != 0) {
            shift_amount = 8 - (bits_per_sample % 8);
        }

        if (aligned_2 && bits_per_sample == 16 && num_channels == 1) {
            write_samples_16bit_1ch(output_buffer, block_samples, block_size);
        } else if (aligned_2 && bits_per_sample == 16 && num_channels == 2) {
            write_samples_16bit_2ch(output_buffer, block_samples, block_size);
        } else if (bits_per_sample == 24 && num_channels == 2) {
            write_samples_24bit_2ch(output_buffer, block_samples, block_size);
        } else {
            write_samples_nch(output_buffer, block_samples, block_size, bytes_per_sample,
                              shift_amount, num_channels);
        }
    }
}

}  // namespace micro_flac
