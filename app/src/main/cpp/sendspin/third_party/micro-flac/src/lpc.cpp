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

#include "lpc.h"

#include "xtensa/lpc_xtensa.h"

#include <cstdint>
#include <cstdlib>

#ifdef MICRO_FLAC_DUMP_LPC_VECTORS
#include <cstdio>
#include <cstring>

static FILE* lpc_dump_file() {
    static FILE* f = std::fopen("lpc_vectors.bin", "ab");
    return f;
}

static void dump_lpc_vector(uint8_t is_64bit, uint32_t order, int32_t shift,
                            uint32_t bits_per_sample, uint32_t num_samples, const int32_t* coefs,
                            const int32_t* input_buffer, const int32_t* output_buffer) {
    const uint32_t max_dump_samples = 256;
    uint32_t dump_samples = (num_samples < max_dump_samples) ? num_samples : max_dump_samples;

    FILE* f = lpc_dump_file();
    if (!f)
        return;

    uint32_t magic = 0x4C504356;  // "LPCV"
    std::fwrite(&magic, 4, 1, f);
    std::fwrite(&is_64bit, 1, 1, f);
    std::fwrite(&order, 4, 1, f);
    std::fwrite(&shift, 4, 1, f);
    std::fwrite(&bits_per_sample, 4, 1, f);
    std::fwrite(&dump_samples, 4, 1, f);
    std::fwrite(coefs, 4, order, f);
    std::fwrite(input_buffer, 4, dump_samples, f);
    std::fwrite(output_buffer, 4, dump_samples, f);
}
#endif  // MICRO_FLAC_DUMP_LPC_VECTORS

namespace micro_flac {

static bool can_use_lpc_32bit(uint32_t bits_per_sample, const int32_t* coefs, uint32_t order,
                              int32_t shift) {
    uint64_t max_abs_sample = static_cast<uint64_t>(1) << (bits_per_sample - 1);

    uint32_t abs_sum = 0;
    for (uint32_t i = 0; i < order; i++) {
        // std::abs(INT32_MIN) is UB, but coefficients are bounded to lpc_.precision bits
        // (max 15), so their magnitude never exceeds ~16383. INT32_MIN is unreachable.
        abs_sum += static_cast<uint32_t>(std::abs(coefs[i]));
    }

    uint64_t max_pred_before_shift = max_abs_sample * abs_sum;

    // Prediction accumulator must fit in 32-bit signed
    if (max_pred_before_shift > INT32_MAX) {
        return false;
    }

    // Residual (sample + prediction after shift) must fit in 32-bit signed
    // Arithmetic right shift of negative value gives worst-case (ceiling) magnitude
    uint64_t max_pred_after_shift =
        static_cast<uint64_t>(-((-static_cast<int64_t>(max_pred_before_shift)) >> shift));
    return (max_abs_sample + max_pred_after_shift) <= INT32_MAX;
}

template <uint32_t ORDER>
static void restore_lpc_32bit_order(int32_t* sub_frame_buffer, size_t num_of_samples,
                                    const int32_t* coefs, int32_t shift) {
    const size_t outer_loop_bound = num_of_samples - ORDER;

    for (size_t i = 0; i < outer_loop_bound; ++i) {
        int32_t sum = 0;
        for (uint32_t j = 0; j < ORDER; ++j) {
            sum += (sub_frame_buffer[i + j] * coefs[j]);
        }
        sub_frame_buffer[i + ORDER] += (sum >> shift);
    }
}

static void restore_lpc_32bit(int32_t* sub_frame_buffer, size_t num_of_samples,
                              const int32_t* coefs, uint32_t order, int32_t shift) {
#if (FLAC_LPC_XTENSA_ENABLED == 1)
    // Use optimized assembly version for Xtensa
    restore_lpc_32bit_asm(sub_frame_buffer, num_of_samples, coefs, order, shift);
#else
    switch (order) {
            // clang-format off
            // NOLINTBEGIN(readability-magic-numbers) -- LPC prediction order dispatch
        case 1:  restore_lpc_32bit_order<1>(sub_frame_buffer, num_of_samples, coefs, shift);  break;
        case 2:  restore_lpc_32bit_order<2>(sub_frame_buffer, num_of_samples, coefs, shift);  break;
        case 3:  restore_lpc_32bit_order<3>(sub_frame_buffer, num_of_samples, coefs, shift);  break;
        case 4:  restore_lpc_32bit_order<4>(sub_frame_buffer, num_of_samples, coefs, shift);  break;
        case 5:  restore_lpc_32bit_order<5>(sub_frame_buffer, num_of_samples, coefs, shift);  break;
        case 6:  restore_lpc_32bit_order<6>(sub_frame_buffer, num_of_samples, coefs, shift);  break;
        case 7:  restore_lpc_32bit_order<7>(sub_frame_buffer, num_of_samples, coefs, shift);  break;
        case 8:  restore_lpc_32bit_order<8>(sub_frame_buffer, num_of_samples, coefs, shift);  break;
        case 9:  restore_lpc_32bit_order<9>(sub_frame_buffer, num_of_samples, coefs, shift);  break;
        case 10: restore_lpc_32bit_order<10>(sub_frame_buffer, num_of_samples, coefs, shift); break;
        case 11: restore_lpc_32bit_order<11>(sub_frame_buffer, num_of_samples, coefs, shift); break;
        case 12: restore_lpc_32bit_order<12>(sub_frame_buffer, num_of_samples, coefs, shift); break;
            // NOLINTEND(readability-magic-numbers)
        // clang-format on
        default: {
            const size_t outer_loop_bound = num_of_samples - order;
            for (size_t i = 0; i < outer_loop_bound; ++i) {
                int32_t sum = 0;
                for (size_t j = 0; j < order; ++j) {
                    sum += (sub_frame_buffer[i + j] * coefs[j]);
                }
                sub_frame_buffer[i + order] += (sum >> shift);
            }
            break;
        }
    }
#endif
}

template <uint32_t ORDER>
static void restore_lpc_64bit_order(int32_t* sub_frame_buffer, size_t num_of_samples,
                                    const int32_t* coefs, int32_t shift) {
    const size_t outer_loop_bound = num_of_samples - ORDER;

    for (size_t i = 0; i < outer_loop_bound; ++i) {
        int64_t sum = 0;
        for (uint32_t j = 0; j < ORDER; ++j) {
            sum += static_cast<int64_t>(sub_frame_buffer[i + j]) * static_cast<int64_t>(coefs[j]);
        }
        sub_frame_buffer[i + ORDER] += static_cast<int32_t>(sum >> shift);
    }
}

static void restore_lpc_64bit(int32_t* sub_frame_buffer, size_t num_of_samples,
                              const int32_t* coefs, uint32_t order, int32_t shift) {
#if (FLAC_LPC_XTENSA_ENABLED == 1)
    // Use optimized 64-bit assembly version for Xtensa
    restore_lpc_64bit_asm(sub_frame_buffer, num_of_samples, coefs, order, shift);
#else
    switch (order) {
            // clang-format off
            // NOLINTBEGIN(readability-magic-numbers) -- LPC prediction order dispatch
        case 1:  restore_lpc_64bit_order<1>(sub_frame_buffer, num_of_samples, coefs, shift);  break;
        case 2:  restore_lpc_64bit_order<2>(sub_frame_buffer, num_of_samples, coefs, shift);  break;
        case 3:  restore_lpc_64bit_order<3>(sub_frame_buffer, num_of_samples, coefs, shift);  break;
        case 4:  restore_lpc_64bit_order<4>(sub_frame_buffer, num_of_samples, coefs, shift);  break;
        case 5:  restore_lpc_64bit_order<5>(sub_frame_buffer, num_of_samples, coefs, shift);  break;
        case 6:  restore_lpc_64bit_order<6>(sub_frame_buffer, num_of_samples, coefs, shift);  break;
        case 7:  restore_lpc_64bit_order<7>(sub_frame_buffer, num_of_samples, coefs, shift);  break;
        case 8:  restore_lpc_64bit_order<8>(sub_frame_buffer, num_of_samples, coefs, shift);  break;
        case 9:  restore_lpc_64bit_order<9>(sub_frame_buffer, num_of_samples, coefs, shift);  break;
        case 10: restore_lpc_64bit_order<10>(sub_frame_buffer, num_of_samples, coefs, shift); break;
        case 11: restore_lpc_64bit_order<11>(sub_frame_buffer, num_of_samples, coefs, shift); break;
        case 12: restore_lpc_64bit_order<12>(sub_frame_buffer, num_of_samples, coefs, shift); break;
            // NOLINTEND(readability-magic-numbers)
        // clang-format on
        default: {
            const size_t outer_loop_bound = num_of_samples - order;
            for (size_t i = 0; i < outer_loop_bound; ++i) {
                int64_t sum = 0;
                for (size_t j = 0; j < order; ++j) {
                    sum += static_cast<int64_t>(sub_frame_buffer[i + j]) *
                           static_cast<int64_t>(coefs[j]);
                }
                sub_frame_buffer[i + order] += static_cast<int32_t>(sum >> shift);
            }
            break;
        }
    }
#endif
}

void restore_lpc(int32_t* sub_frame_buffer, size_t num_of_samples, uint32_t bits_per_sample,
                 const int32_t* coefs, uint32_t order, int32_t shift) {
#ifdef MICRO_FLAC_DUMP_LPC_VECTORS
    const uint32_t max_dump = 256;
    uint32_t save_count = (static_cast<uint32_t>(num_of_samples) < max_dump)
                              ? static_cast<uint32_t>(num_of_samples)
                              : max_dump;
    int32_t input_copy[256];
    std::memcpy(input_copy, sub_frame_buffer, save_count * sizeof(int32_t));
    uint8_t is_64bit = can_use_lpc_32bit(bits_per_sample, coefs, order, shift) ? 0 : 1;
#endif

    // Choose between 32-bit and 64-bit LPC path based on coefficient range
    if (can_use_lpc_32bit(bits_per_sample, coefs, order, shift)) {
        restore_lpc_32bit(sub_frame_buffer, num_of_samples, coefs, order, shift);
    } else {
        restore_lpc_64bit(sub_frame_buffer, num_of_samples, coefs, order, shift);
    }

#ifdef MICRO_FLAC_DUMP_LPC_VECTORS
    dump_lpc_vector(is_64bit, order, shift, bits_per_sample, static_cast<uint32_t>(num_of_samples),
                    coefs, input_copy, sub_frame_buffer);
#endif
}

void restore_lpc(int64_t* sub_frame_buffer, size_t num_of_samples, uint32_t /*bits_per_sample*/,
                 const int32_t* coefs, uint32_t order, int32_t shift) {
    const size_t outer_loop_bound = num_of_samples - order;

    for (size_t i = 0; i < outer_loop_bound; ++i) {
        int64_t sum = 0;
        for (size_t j = 0; j < order; ++j) {
            sum += sub_frame_buffer[i + j] * static_cast<int64_t>(coefs[j]);
        }
        sub_frame_buffer[i + order] += (sum >> shift);
    }
}

}  // namespace micro_flac
