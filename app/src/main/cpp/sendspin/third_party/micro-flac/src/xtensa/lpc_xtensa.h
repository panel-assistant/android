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

/// @file lpc_xtensa.h
/// @brief Xtensa assembly LPC platform detection and function declarations
///
/// Detects Xtensa hardware features (MULL, MULSH, hardware loops) and declares
/// optimized 32-bit and 64-bit LPC restoration assembly functions for ESP32/ESP32-S3.

#pragma once

#ifdef ESP_PLATFORM
#include "sdkconfig.h"
#endif

#ifdef __XTENSA__
#include <xtensa/config/core-isa.h>
#include <xtensa/config/core-matmap.h>

// Enable assembly optimization if we have the required Xtensa features
// The assembly uses MULL (multiply) and SSR (shift amount register) instructions
#if ((XCHAL_HAVE_LOOPS == 1) && (XCHAL_HAVE_MUL32 == 1) && (XCHAL_HAVE_MUL32_HIGH == 1) && \
     !defined(MICRO_FLAC_DISABLE_XTENSA_ASM))
#define FLAC_LPC_XTENSA_ENABLED 1
#endif

// ESP32-S2 and ESP32-S3 (Xtensa LX7) supports the saltu instruction for branchless carry detection.
// ESP32 (Xtensa LX6) does not have saltu and must use a conditional branch instead.
// This checks the core-isa.h hardware version and only enables it for LX7 processors.
#if XCHAL_HW_VERSION >= 270000
#define FLAC_LPC_HAVE_SALTU 1
#endif

#endif  // __XTENSA__

// C/C++ declarations - not visible to the assembler
#ifndef __ASSEMBLER__

#include <cstddef>
#include <cstdint>

#ifdef __cplusplus
extern "C" {
#endif

#if (FLAC_LPC_XTENSA_ENABLED == 1)

/// @brief Optimized 32-bit assembly implementation of linear prediction restoration for FLAC
/// decoding
///
/// @param buffer Pointer to sub_frame_buffer (int32_t*) - contains warm-up samples followed by
/// residuals
/// @param num_samples Total number of samples in the buffer
/// @param coefficients Pointer to LPC coefficients array (int32_t*)
/// @param order Number of coefficients (predictor order)
/// @param shift Shift amount for the prediction
///
/// @note This function modifies the buffer in-place, replacing residuals with
///       restored sample values.
/// @note Optimized for orders 1-12 with fully unrolled loops; uses a generic
///       loop for higher orders.
void restore_lpc_32bit_asm(int32_t* buffer, size_t num_samples, const int32_t* coefficients,
                           uint32_t order, int32_t shift);

/// @brief Optimized 64-bit assembly implementation of linear prediction restoration for FLAC
/// decoding
///
/// This function uses MULL and MULSH instructions to perform 64-bit arithmetic
/// for high-resolution audio where 32-bit arithmetic could overflow.
///
/// @param buffer Pointer to the sub-frame buffer (int32_t*). The first 'order' samples
///               should contain the warm-up samples, and the remaining samples contain
///               the residuals that will be restored in-place.
/// @param num_samples Total number of samples in the buffer (including warm-up samples)
/// @param coefficients Pointer to the array of LPC coefficients
/// @param order Number of coefficients (predictor order)
/// @param shift Right shift amount to apply after accumulation (0-31)
///
/// @note This function modifies the buffer in-place, replacing residuals with
///       restored sample values.
/// @note Optimized for orders 1-12 with fully unrolled loops; uses a generic
///       loop for higher orders.
void restore_lpc_64bit_asm(int32_t* buffer, size_t num_samples, const int32_t* coefficients,
                           uint32_t order, int32_t shift);

#endif  // FLAC_LPC_XTENSA_ENABLED

#ifdef __cplusplus
}
#endif

#endif  // __ASSEMBLER__
