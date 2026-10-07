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

/// @file lpc.h
/// @brief FLAC Linear Predictive Coding (LPC) restoration
///
/// Provides the LPC restoration function for FLAC decoding. Automatically selects
/// between 32-bit and 64-bit arithmetic based on overflow analysis.

#pragma once

#include <cstddef>
#include <cstdint>

namespace micro_flac {

/// @brief Restore linear prediction, automatically selecting 32-bit or 64-bit arithmetic
///
/// Checks if 32-bit arithmetic is safe for the given parameters and dispatches to the
/// appropriate implementation.
///
/// @param sub_frame_buffer Buffer containing warm-up samples followed by residuals (modified
/// in-place)
/// @param num_of_samples Total number of samples in the buffer
/// @param bits_per_sample Bit depth of the audio samples
/// @param coefs Pointer to array of LPC coefficients
/// @param order Number of LPC coefficients (predictor order)
/// @param shift Right shift amount to apply after prediction
void restore_lpc(int32_t* sub_frame_buffer, size_t num_of_samples, uint32_t bits_per_sample,
                 const int32_t* coefs, uint32_t order, int32_t shift);

/// @brief Restore linear prediction for 33-bit MID_SIDE side channel
///
/// Overload for int64_t buffers where samples may be up to 33 bits wide.
/// Always uses 64-bit accumulation (no Xtensa assembly dispatch).
/// The bits_per_sample parameter is accepted for API symmetry but not used.
///
/// @param sub_frame_buffer Buffer containing int64_t warm-up samples followed by int64_t residuals
/// @param num_of_samples Total number of samples in the buffer
/// @param bits_per_sample Accepted for API symmetry with int32_t overload (unused)
/// @param coefs Pointer to array of LPC coefficients (int32_t)
/// @param order Number of LPC coefficients (predictor order)
/// @param shift Right shift amount to apply after prediction
void restore_lpc(int64_t* sub_frame_buffer, size_t num_of_samples, uint32_t bits_per_sample,
                 const int32_t* coefs, uint32_t order, int32_t shift);

}  // namespace micro_flac
