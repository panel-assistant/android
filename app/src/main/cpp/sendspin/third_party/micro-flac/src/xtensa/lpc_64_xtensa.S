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

/**
 * lpc_64_xtensa.S
 *
 * Optimized 64-bit assembly implementation of linear prediction restoration for FLAC decoding
 * on ESP32/Xtensa architecture.
 *
 * This implementation uses MULL and MULSH instructions to perform 64-bit arithmetic
 * for high-resolution audio where 32-bit arithmetic could overflow.
 *
 * Features:
 * - 64-bit multiplication using MULL/MULSH instruction pair
 * - Jump table dispatch for orders 1-12
 * - Fully unrolled inner loops for common orders
 * - Register-cached coefficients for orders 1-8
 * - Hybrid register-cached + memory-loaded coefficients for orders 9-12:
 *   coef[0]-coef[5] cached in a10-a15, coef[6]+ loaded from original pointer (a9)
 * - Interleaved memory operations: loads for next MAC are issued during current multiply,
 *   hiding memory latency and improving instruction throughput
 * - Generic fallback for orders > 12
 * - Proper carry propagation for 64-bit accumulation
 * - Efficient 64-bit shifting using SRC instruction
 */

#include "lpc_xtensa.h"
#if (FLAC_LPC_XTENSA_ENABLED == 1)

// ============================================================================
// MAC64_CARRY macro: 64-bit accumulation with carry propagation
//
// Usage: MAC64_CARRY acc_low, acc_high, low_prod, high_prod
//   acc_low  += low_prod  (carry detected from unsigned overflow)
//   acc_high += high_prod + carry
//   low_prod is clobbered
//
// On LX7 (ESP32-S3): branchless saltu instruction
// On LX6 (ESP32):    bgeu branch with GAS numeric local label (safe in macros)
// ============================================================================
#if (FLAC_LPC_HAVE_SALTU == 1)
.macro MAC64_CARRY acc_low, acc_high, low_prod, high_prod
    add \acc_low, \acc_low, \low_prod
    add \acc_high, \acc_high, \high_prod
    saltu \low_prod, \acc_low, \low_prod
    add \acc_high, \acc_high, \low_prod
.endm
#else
.macro MAC64_CARRY acc_low, acc_high, low_prod, high_prod
    add \acc_low, \acc_low, \low_prod
    bgeu \acc_low, \low_prod, 1f
    addi \acc_high, \acc_high, 1
1:
    add \acc_high, \acc_high, \high_prod
.endm
#endif

.section .text
.global restore_lpc_64bit_asm
.type restore_lpc_64bit_asm, @function
.align 4

/**
 * @brief Optimized 64-bit linear prediction restoration for FLAC decoding
 *
 * @param a2 Pointer to sub_frame_buffer (int32_t*)
 * @param a3 Number of samples (num_of_samples)
 * @param a4 Pointer to coefficients array (int32_t*)
 * @param a5 Number of coefficients (order)
 * @param a6 Shift amount (0-31)
 *
 * Uses 64-bit arithmetic: sum = Σ(buffer[i+j] * coef[j]) >> shift
 */
restore_lpc_64bit_asm:
    entry a1, 32                    // Minimum stack frame (windowed ABI base save area)

    beqz a5, .L64_done             // If order is 0, nothing to do

    // Calculate outer loop bound: num_samples - order
    sub a7, a3, a5                 // a7 = outer_loop_bound
    blti a7, 1, .L64_done          // If no iterations needed, exit

    ssr a6                         // Set shift amount (used by all orders)

    // Jump table dispatch based on coefficient order
    movi a8, 12
    blt a8, a5, .L64_generic       // If order > 12, use generic version

    // Calculate jump address: jump_table + (order-1)*4
    addi a8, a5, -1                // a8 = order - 1
    movi a9, .L64_jump_table
    addx4 a8, a8, a9               // a8 = jump_table + (order-1)*4
    l32i a8, a8, 0                 // Load jump address
    jx a8                          // Jump to specific handler

.align 4
.L64_jump_table:
    .word .L64_order_1
    .word .L64_order_2
    .word .L64_order_3
    .word .L64_order_4
    .word .L64_order_5
    .word .L64_order_6
    .word .L64_order_7
    .word .L64_order_8
    .word .L64_order_9
    .word .L64_order_10
    .word .L64_order_11
    .word .L64_order_12

// ============================================================================
// Unified register convention (orders 1-12):
//
//   a2:      buffer pointer (advanced each outer iteration)
//   a3:      64-bit accumulator low
//   a4:      64-bit accumulator high
//   a5:      interleaved sample load A
//   a6:      interleaved sample load B / shift result
//   a7:      temporary mull low result
//
// Register-cached orders 1-8:
//   a8-a15:  coefficients 0-7
//
// Hybrid orders 9-12:
//   a9:      coef pointer (for memory-loaded coefficients)
//   a10-a15: coef[0]-coef[5] cached in registers
//   a8:      coefficient temp (loaded from a9 for coef[6]+)
//
// MAC pattern (example with sample in a5):
//   mull  a7, a5, a[coef]    -- low product into a7 (a5 unchanged)
//   mulsh a5, a5, a[coef]    -- high product overwrites sample in a5
//   accumulate a7 (low) into a3, a5 (high) into a4 with carry propagation
// ============================================================================

// ============================================================================
// Order 1: One coefficient (register-cached)
// ============================================================================
.align 4
.L64_order_1:
    l32i a8, a4, 0                 // coef[0]

    loop a7, .L64_loop_1_end

    l32i a5, a2, 0                 // buffer[i]

    // Single MAC: multiply directly into accumulator registers (no carry needed)
    l32i a6, a2, 4                 // Pre-load residual (hides load-use latency on a5)
    mull a3, a5, a8                // acc_low = low product
    mulsh a4, a5, a8               // acc_high = high product

    // Shift and store
    src a5, a4, a3                 // (acc_high:acc_low) >> shift
    add a6, a6, a5
    s32i a6, a2, 4
    addi a2, a2, 4
.L64_loop_1_end:
    j .L64_done

// ============================================================================
// Order 2: Two coefficients (register-cached)
// ============================================================================
.align 4
.L64_order_2:
    l32i a8, a4, 0                 // coef[0]
    l32i a9, a4, 4                 // coef[1]

    loop a7, .L64_loop_2_end
    movi a3, 0                     // acc_low = 0
    movi a4, 0                     // acc_high = 0

    l32i a5, a2, 0                 // buffer[i]

    // MAC 0: a5 * coef[0]
    l32i a6, a2, 4                 // Pre-load buffer[i+1]
    mull a7, a5, a8
    mulsh a5, a5, a8
    MAC64_CARRY a3, a4, a7, a5

    // MAC 1: a6 * coef[1]
    mull a7, a6, a9
    mulsh a6, a6, a9
    MAC64_CARRY a3, a4, a7, a6

    // Shift and store
    l32i a5, a2, 8                 // Load residual
    src a6, a4, a3
    add a5, a5, a6
    s32i a5, a2, 8
    addi a2, a2, 4
.L64_loop_2_end:
    j .L64_done

// ============================================================================
// Order 3: Three coefficients (register-cached)
// ============================================================================
.align 4
.L64_order_3:
    l32i a8, a4, 0                 // coef[0]
    l32i a9, a4, 4                 // coef[1]
    l32i a10, a4, 8                // coef[2]

    loop a7, .L64_loop_3_end
    movi a3, 0
    movi a4, 0

    l32i a5, a2, 0                 // buffer[i]

    // MAC 0: a5 * coef[0]
    l32i a6, a2, 4                 // Pre-load buffer[i+1]
    mull a7, a5, a8
    mulsh a5, a5, a8
    MAC64_CARRY a3, a4, a7, a5

    // MAC 1: a6 * coef[1]
    l32i a5, a2, 8                 // Pre-load buffer[i+2]
    mull a7, a6, a9
    mulsh a6, a6, a9
    MAC64_CARRY a3, a4, a7, a6

    // MAC 2: a5 * coef[2]
    mull a7, a5, a10
    mulsh a5, a5, a10
    MAC64_CARRY a3, a4, a7, a5

    // Shift and store
    l32i a5, a2, 12                // Load residual
    src a6, a4, a3
    add a5, a5, a6
    s32i a5, a2, 12
    addi a2, a2, 4
.L64_loop_3_end:
    j .L64_done

// ============================================================================
// Order 4: Four coefficients (register-cached)
// ============================================================================
.align 4
.L64_order_4:
    l32i a8, a4, 0                 // coef[0]
    l32i a9, a4, 4                 // coef[1]
    l32i a10, a4, 8                // coef[2]
    l32i a11, a4, 12               // coef[3]

    loop a7, .L64_loop_4_end
    movi a3, 0
    movi a4, 0

    l32i a5, a2, 0                 // buffer[i]

    // MAC 0: a5 * coef[0]
    l32i a6, a2, 4                 // Pre-load buffer[i+1]
    mull a7, a5, a8
    mulsh a5, a5, a8
    MAC64_CARRY a3, a4, a7, a5

    // MAC 1: a6 * coef[1]
    l32i a5, a2, 8                 // Pre-load buffer[i+2]
    mull a7, a6, a9
    mulsh a6, a6, a9
    MAC64_CARRY a3, a4, a7, a6

    // MAC 2: a5 * coef[2]
    l32i a6, a2, 12                // Pre-load buffer[i+3]
    mull a7, a5, a10
    mulsh a5, a5, a10
    MAC64_CARRY a3, a4, a7, a5

    // MAC 3: a6 * coef[3]
    mull a7, a6, a11
    mulsh a6, a6, a11
    MAC64_CARRY a3, a4, a7, a6

    // Shift and store
    l32i a5, a2, 16                // Load residual
    src a6, a4, a3
    add a5, a5, a6
    s32i a5, a2, 16
    addi a2, a2, 4
.L64_loop_4_end:
    j .L64_done

// ============================================================================
// Order 5: Five coefficients (register-cached)
// ============================================================================
.align 4
.L64_order_5:
    l32i a8, a4, 0                 // coef[0]
    l32i a9, a4, 4                 // coef[1]
    l32i a10, a4, 8                // coef[2]
    l32i a11, a4, 12               // coef[3]
    l32i a12, a4, 16               // coef[4]

    loop a7, .L64_loop_5_end
    movi a3, 0
    movi a4, 0

    l32i a5, a2, 0                 // buffer[i]

    // MAC 0: a5 * coef[0]
    l32i a6, a2, 4                 // Pre-load buffer[i+1]
    mull a7, a5, a8
    mulsh a5, a5, a8
    MAC64_CARRY a3, a4, a7, a5

    // MAC 1: a6 * coef[1]
    l32i a5, a2, 8                 // Pre-load buffer[i+2]
    mull a7, a6, a9
    mulsh a6, a6, a9
    MAC64_CARRY a3, a4, a7, a6

    // MAC 2: a5 * coef[2]
    l32i a6, a2, 12                // Pre-load buffer[i+3]
    mull a7, a5, a10
    mulsh a5, a5, a10
    MAC64_CARRY a3, a4, a7, a5

    // MAC 3: a6 * coef[3]
    l32i a5, a2, 16                // Pre-load buffer[i+4]
    mull a7, a6, a11
    mulsh a6, a6, a11
    MAC64_CARRY a3, a4, a7, a6

    // MAC 4: a5 * coef[4]
    mull a7, a5, a12
    mulsh a5, a5, a12
    MAC64_CARRY a3, a4, a7, a5

    // Shift and store
    l32i a5, a2, 20                // Load residual
    src a6, a4, a3
    add a5, a5, a6
    s32i a5, a2, 20
    addi a2, a2, 4
.L64_loop_5_end:
    j .L64_done

// ============================================================================
// Order 6: Six coefficients (register-cached)
// ============================================================================
.align 4
.L64_order_6:
    l32i a8, a4, 0                 // coef[0]
    l32i a9, a4, 4                 // coef[1]
    l32i a10, a4, 8                // coef[2]
    l32i a11, a4, 12               // coef[3]
    l32i a12, a4, 16               // coef[4]
    l32i a13, a4, 20               // coef[5]

    loop a7, .L64_loop_6_end
    movi a3, 0
    movi a4, 0

    l32i a5, a2, 0                 // buffer[i]

    // MAC 0: a5 * coef[0]
    l32i a6, a2, 4                 // Pre-load buffer[i+1]
    mull a7, a5, a8
    mulsh a5, a5, a8
    MAC64_CARRY a3, a4, a7, a5

    // MAC 1: a6 * coef[1]
    l32i a5, a2, 8                 // Pre-load buffer[i+2]
    mull a7, a6, a9
    mulsh a6, a6, a9
    MAC64_CARRY a3, a4, a7, a6

    // MAC 2: a5 * coef[2]
    l32i a6, a2, 12                // Pre-load buffer[i+3]
    mull a7, a5, a10
    mulsh a5, a5, a10
    MAC64_CARRY a3, a4, a7, a5

    // MAC 3: a6 * coef[3]
    l32i a5, a2, 16                // Pre-load buffer[i+4]
    mull a7, a6, a11
    mulsh a6, a6, a11
    MAC64_CARRY a3, a4, a7, a6

    // MAC 4: a5 * coef[4]
    l32i a6, a2, 20                // Pre-load buffer[i+5]
    mull a7, a5, a12
    mulsh a5, a5, a12
    MAC64_CARRY a3, a4, a7, a5

    // MAC 5: a6 * coef[5]
    mull a7, a6, a13
    mulsh a6, a6, a13
    MAC64_CARRY a3, a4, a7, a6

    // Shift and store
    l32i a5, a2, 24                // Load residual
    src a6, a4, a3
    add a5, a5, a6
    s32i a5, a2, 24
    addi a2, a2, 4
.L64_loop_6_end:
    j .L64_done

// ============================================================================
// Order 7: Seven coefficients (register-cached)
// ============================================================================
.align 4
.L64_order_7:
    l32i a8, a4, 0                 // coef[0]
    l32i a9, a4, 4                 // coef[1]
    l32i a10, a4, 8                // coef[2]
    l32i a11, a4, 12               // coef[3]
    l32i a12, a4, 16               // coef[4]
    l32i a13, a4, 20               // coef[5]
    l32i a14, a4, 24               // coef[6]

    loop a7, .L64_loop_7_end
    movi a3, 0
    movi a4, 0

    l32i a5, a2, 0                 // buffer[i]

    // MAC 0: a5 * coef[0]
    l32i a6, a2, 4                 // Pre-load buffer[i+1]
    mull a7, a5, a8
    mulsh a5, a5, a8
    MAC64_CARRY a3, a4, a7, a5

    // MAC 1: a6 * coef[1]
    l32i a5, a2, 8                 // Pre-load buffer[i+2]
    mull a7, a6, a9
    mulsh a6, a6, a9
    MAC64_CARRY a3, a4, a7, a6

    // MAC 2: a5 * coef[2]
    l32i a6, a2, 12                // Pre-load buffer[i+3]
    mull a7, a5, a10
    mulsh a5, a5, a10
    MAC64_CARRY a3, a4, a7, a5

    // MAC 3: a6 * coef[3]
    l32i a5, a2, 16                // Pre-load buffer[i+4]
    mull a7, a6, a11
    mulsh a6, a6, a11
    MAC64_CARRY a3, a4, a7, a6

    // MAC 4: a5 * coef[4]
    l32i a6, a2, 20                // Pre-load buffer[i+5]
    mull a7, a5, a12
    mulsh a5, a5, a12
    MAC64_CARRY a3, a4, a7, a5

    // MAC 5: a6 * coef[5]
    l32i a5, a2, 24                // Pre-load buffer[i+6]
    mull a7, a6, a13
    mulsh a6, a6, a13
    MAC64_CARRY a3, a4, a7, a6

    // MAC 6: a5 * coef[6]
    mull a7, a5, a14
    mulsh a5, a5, a14
    MAC64_CARRY a3, a4, a7, a5

    // Shift and store
    l32i a5, a2, 28                // Load residual
    src a6, a4, a3
    add a5, a5, a6
    s32i a5, a2, 28
    addi a2, a2, 4
.L64_loop_7_end:
    j .L64_done

// ============================================================================
// Order 8: Eight coefficients (register-cached)
// ============================================================================
.align 4
.L64_order_8:
    l32i a8, a4, 0                 // coef[0]
    l32i a9, a4, 4                 // coef[1]
    l32i a10, a4, 8                // coef[2]
    l32i a11, a4, 12               // coef[3]
    l32i a12, a4, 16               // coef[4]
    l32i a13, a4, 20               // coef[5]
    l32i a14, a4, 24               // coef[6]
    l32i a15, a4, 28               // coef[7]

    loop a7, .L64_loop_8_end
    movi a3, 0
    movi a4, 0

    l32i a5, a2, 0                 // buffer[i]

    // MAC 0: a5 * coef[0]
    l32i a6, a2, 4                 // Pre-load buffer[i+1]
    mull a7, a5, a8
    mulsh a5, a5, a8
    MAC64_CARRY a3, a4, a7, a5

    // MAC 1: a6 * coef[1]
    l32i a5, a2, 8                 // Pre-load buffer[i+2]
    mull a7, a6, a9
    mulsh a6, a6, a9
    MAC64_CARRY a3, a4, a7, a6

    // MAC 2: a5 * coef[2]
    l32i a6, a2, 12                // Pre-load buffer[i+3]
    mull a7, a5, a10
    mulsh a5, a5, a10
    MAC64_CARRY a3, a4, a7, a5

    // MAC 3: a6 * coef[3]
    l32i a5, a2, 16                // Pre-load buffer[i+4]
    mull a7, a6, a11
    mulsh a6, a6, a11
    MAC64_CARRY a3, a4, a7, a6

    // MAC 4: a5 * coef[4]
    l32i a6, a2, 20                // Pre-load buffer[i+5]
    mull a7, a5, a12
    mulsh a5, a5, a12
    MAC64_CARRY a3, a4, a7, a5

    // MAC 5: a6 * coef[5]
    l32i a5, a2, 24                // Pre-load buffer[i+6]
    mull a7, a6, a13
    mulsh a6, a6, a13
    MAC64_CARRY a3, a4, a7, a6

    // MAC 6: a5 * coef[6]
    l32i a6, a2, 28                // Pre-load buffer[i+7]
    mull a7, a5, a14
    mulsh a5, a5, a14
    MAC64_CARRY a3, a4, a7, a5

    // MAC 7: a6 * coef[7]
    mull a7, a6, a15
    mulsh a6, a6, a15
    MAC64_CARRY a3, a4, a7, a6

    // Shift and store
    l32i a5, a2, 32                // Load residual
    src a6, a4, a3
    add a5, a5, a6
    s32i a5, a2, 32
    addi a2, a2, 4
.L64_loop_8_end:
    j .L64_done

// ============================================================================
// Order 9: Nine coefficients (hybrid: 6 cached + 3 memory-loaded)
// ============================================================================
.align 4
.L64_order_9:
    // Cache coef[0]-coef[5] in registers, keep pointer for coef[6]-coef[8]
    mov a9, a4                     // Save coef pointer for tail section
    l32i a10, a4, 0                // coef[0]
    l32i a11, a4, 4                // coef[1]
    l32i a12, a4, 8                // coef[2]
    l32i a13, a4, 12               // coef[3]
    l32i a14, a4, 16               // coef[4]
    l32i a15, a4, 20               // coef[5]

    loop a7, .L64_loop_9_end
    movi a3, 0                     // acc_low = 0
    movi a4, 0                     // acc_high = 0

    l32i a5, a2, 0                 // buffer[0]

    // === Cached section: MACs 0-5 (register coefficients) ===
    // MAC 0: a5 * coef[0]
    l32i a6, a2, 4                 // Pre-load buffer[1]
    mull a7, a5, a10
    mulsh a5, a5, a10
    MAC64_CARRY a3, a4, a7, a5

    // MAC 1: a6 * coef[1]
    l32i a5, a2, 8                 // Pre-load buffer[2]
    mull a7, a6, a11
    mulsh a6, a6, a11
    MAC64_CARRY a3, a4, a7, a6

    // MAC 2: a5 * coef[2]
    l32i a6, a2, 12                // Pre-load buffer[3]
    mull a7, a5, a12
    mulsh a5, a5, a12
    MAC64_CARRY a3, a4, a7, a5

    // MAC 3: a6 * coef[3]
    l32i a5, a2, 16                // Pre-load buffer[4]
    mull a7, a6, a13
    mulsh a6, a6, a13
    MAC64_CARRY a3, a4, a7, a6

    // MAC 4: a5 * coef[4]
    l32i a6, a2, 20                // Pre-load buffer[5]
    mull a7, a5, a14
    mulsh a5, a5, a14
    MAC64_CARRY a3, a4, a7, a5

    // MAC 5: a6 * coef[5] (last cached, transition to tail)
    l32i a5, a2, 24                // Pre-load buffer[6]
    mull a7, a6, a15
    mulsh a6, a6, a15
    l32i a8, a9, 24                // Pre-load coef[6]
    MAC64_CARRY a3, a4, a7, a6

    // === Tail section: MACs 6-8 (memory-loaded coefficients from a9) ===
    // MAC 6: a5 * coef[6]
    l32i a6, a2, 28                // Pre-load buffer[7]
    mull a7, a5, a8
    mulsh a5, a5, a8
    l32i a8, a9, 28                // Pre-load coef[7]
    MAC64_CARRY a3, a4, a7, a5

    // MAC 7: a6 * coef[7]
    l32i a5, a2, 32                // Pre-load buffer[8]
    mull a7, a6, a8
    mulsh a6, a6, a8
    l32i a8, a9, 32                // Pre-load coef[8]
    MAC64_CARRY a3, a4, a7, a6

    // MAC 8: a5 * coef[8] - last, no pre-loads
    mull a7, a5, a8
    mulsh a5, a5, a8
    MAC64_CARRY a3, a4, a7, a5

    // Shift and store
    l32i a5, a2, 36                // Load residual (9*4 = 36)
    src a6, a4, a3
    add a5, a5, a6
    s32i a5, a2, 36
    addi a2, a2, 4
.L64_loop_9_end:
    j .L64_done

// ============================================================================
// Order 10: Ten coefficients (hybrid: 6 cached + 4 memory-loaded)
// ============================================================================
.align 4
.L64_order_10:
    // Cache coef[0]-coef[5] in registers, keep pointer for coef[6]-coef[9]
    mov a9, a4                     // Save coef pointer for tail section
    l32i a10, a4, 0                // coef[0]
    l32i a11, a4, 4                // coef[1]
    l32i a12, a4, 8                // coef[2]
    l32i a13, a4, 12               // coef[3]
    l32i a14, a4, 16               // coef[4]
    l32i a15, a4, 20               // coef[5]

    loop a7, .L64_loop_10_end
    movi a3, 0                     // acc_low = 0
    movi a4, 0                     // acc_high = 0

    l32i a5, a2, 0                 // buffer[0]

    // === Cached section: MACs 0-5 (register coefficients) ===
    // MAC 0: a5 * coef[0]
    l32i a6, a2, 4                 // Pre-load buffer[1]
    mull a7, a5, a10
    mulsh a5, a5, a10
    MAC64_CARRY a3, a4, a7, a5

    // MAC 1: a6 * coef[1]
    l32i a5, a2, 8                 // Pre-load buffer[2]
    mull a7, a6, a11
    mulsh a6, a6, a11
    MAC64_CARRY a3, a4, a7, a6

    // MAC 2: a5 * coef[2]
    l32i a6, a2, 12                // Pre-load buffer[3]
    mull a7, a5, a12
    mulsh a5, a5, a12
    MAC64_CARRY a3, a4, a7, a5

    // MAC 3: a6 * coef[3]
    l32i a5, a2, 16                // Pre-load buffer[4]
    mull a7, a6, a13
    mulsh a6, a6, a13
    MAC64_CARRY a3, a4, a7, a6

    // MAC 4: a5 * coef[4]
    l32i a6, a2, 20                // Pre-load buffer[5]
    mull a7, a5, a14
    mulsh a5, a5, a14
    MAC64_CARRY a3, a4, a7, a5

    // MAC 5: a6 * coef[5] (last cached, transition to tail)
    l32i a5, a2, 24                // Pre-load buffer[6]
    mull a7, a6, a15
    mulsh a6, a6, a15
    l32i a8, a9, 24                // Pre-load coef[6]
    MAC64_CARRY a3, a4, a7, a6

    // === Tail section: MACs 6-9 (memory-loaded coefficients from a9) ===
    // MAC 6: a5 * coef[6]
    l32i a6, a2, 28                // Pre-load buffer[7]
    mull a7, a5, a8
    mulsh a5, a5, a8
    l32i a8, a9, 28                // Pre-load coef[7]
    MAC64_CARRY a3, a4, a7, a5

    // MAC 7: a6 * coef[7]
    l32i a5, a2, 32                // Pre-load buffer[8]
    mull a7, a6, a8
    mulsh a6, a6, a8
    l32i a8, a9, 32                // Pre-load coef[8]
    MAC64_CARRY a3, a4, a7, a6

    // MAC 8: a5 * coef[8]
    l32i a6, a2, 36                // Pre-load buffer[9]
    mull a7, a5, a8
    mulsh a5, a5, a8
    l32i a8, a9, 36                // Pre-load coef[9]
    MAC64_CARRY a3, a4, a7, a5

    // MAC 9: a6 * coef[9] - last, no pre-loads
    mull a7, a6, a8
    mulsh a6, a6, a8
    MAC64_CARRY a3, a4, a7, a6

    // Shift and store
    l32i a5, a2, 40                // Load residual (10*4 = 40)
    src a6, a4, a3
    add a5, a5, a6
    s32i a5, a2, 40
    addi a2, a2, 4
.L64_loop_10_end:
    j .L64_done

// ============================================================================
// Order 11: Eleven coefficients (hybrid: 6 cached + 5 memory-loaded)
// ============================================================================
.align 4
.L64_order_11:
    // Cache coef[0]-coef[5] in registers, keep pointer for coef[6]-coef[10]
    mov a9, a4                     // Save coef pointer for tail section
    l32i a10, a4, 0                // coef[0]
    l32i a11, a4, 4                // coef[1]
    l32i a12, a4, 8                // coef[2]
    l32i a13, a4, 12               // coef[3]
    l32i a14, a4, 16               // coef[4]
    l32i a15, a4, 20               // coef[5]

    loop a7, .L64_loop_11_end
    movi a3, 0                     // acc_low = 0
    movi a4, 0                     // acc_high = 0

    l32i a5, a2, 0                 // buffer[0]

    // === Cached section: MACs 0-5 (register coefficients) ===
    // MAC 0: a5 * coef[0]
    l32i a6, a2, 4                 // Pre-load buffer[1]
    mull a7, a5, a10
    mulsh a5, a5, a10
    MAC64_CARRY a3, a4, a7, a5

    // MAC 1: a6 * coef[1]
    l32i a5, a2, 8                 // Pre-load buffer[2]
    mull a7, a6, a11
    mulsh a6, a6, a11
    MAC64_CARRY a3, a4, a7, a6

    // MAC 2: a5 * coef[2]
    l32i a6, a2, 12                // Pre-load buffer[3]
    mull a7, a5, a12
    mulsh a5, a5, a12
    MAC64_CARRY a3, a4, a7, a5

    // MAC 3: a6 * coef[3]
    l32i a5, a2, 16                // Pre-load buffer[4]
    mull a7, a6, a13
    mulsh a6, a6, a13
    MAC64_CARRY a3, a4, a7, a6

    // MAC 4: a5 * coef[4]
    l32i a6, a2, 20                // Pre-load buffer[5]
    mull a7, a5, a14
    mulsh a5, a5, a14
    MAC64_CARRY a3, a4, a7, a5

    // MAC 5: a6 * coef[5] (last cached, transition to tail)
    l32i a5, a2, 24                // Pre-load buffer[6]
    mull a7, a6, a15
    mulsh a6, a6, a15
    l32i a8, a9, 24                // Pre-load coef[6]
    MAC64_CARRY a3, a4, a7, a6

    // === Tail section: MACs 6-10 (memory-loaded coefficients from a9) ===
    // MAC 6: a5 * coef[6]
    l32i a6, a2, 28                // Pre-load buffer[7]
    mull a7, a5, a8
    mulsh a5, a5, a8
    l32i a8, a9, 28                // Pre-load coef[7]
    MAC64_CARRY a3, a4, a7, a5

    // MAC 7: a6 * coef[7]
    l32i a5, a2, 32                // Pre-load buffer[8]
    mull a7, a6, a8
    mulsh a6, a6, a8
    l32i a8, a9, 32                // Pre-load coef[8]
    MAC64_CARRY a3, a4, a7, a6

    // MAC 8: a5 * coef[8]
    l32i a6, a2, 36                // Pre-load buffer[9]
    mull a7, a5, a8
    mulsh a5, a5, a8
    l32i a8, a9, 36                // Pre-load coef[9]
    MAC64_CARRY a3, a4, a7, a5

    // MAC 9: a6 * coef[9]
    l32i a5, a2, 40                // Pre-load buffer[10]
    mull a7, a6, a8
    mulsh a6, a6, a8
    l32i a8, a9, 40                // Pre-load coef[10]
    MAC64_CARRY a3, a4, a7, a6

    // MAC 10: a5 * coef[10] - last, no pre-loads
    mull a7, a5, a8
    mulsh a5, a5, a8
    MAC64_CARRY a3, a4, a7, a5

    // Shift and store
    l32i a5, a2, 44                // Load residual (11*4 = 44)
    src a6, a4, a3
    add a5, a5, a6
    s32i a5, a2, 44
    addi a2, a2, 4
.L64_loop_11_end:
    j .L64_done

// ============================================================================
// Order 12: Twelve coefficients (hybrid: 6 cached + 6 memory-loaded)
// ============================================================================
.align 4
.L64_order_12:
    // Cache coef[0]-coef[5] in registers, keep pointer for coef[6]-coef[11]
    mov a9, a4                     // Save coef pointer for tail section
    l32i a10, a4, 0                // coef[0]
    l32i a11, a4, 4                // coef[1]
    l32i a12, a4, 8                // coef[2]
    l32i a13, a4, 12               // coef[3]
    l32i a14, a4, 16               // coef[4]
    l32i a15, a4, 20               // coef[5]

    loop a7, .L64_loop_12_end
    movi a3, 0                     // acc_low = 0
    movi a4, 0                     // acc_high = 0

    l32i a5, a2, 0                 // buffer[0]

    // === Cached section: MACs 0-5 (register coefficients) ===
    // MAC 0: a5 * coef[0]
    l32i a6, a2, 4                 // Pre-load buffer[1]
    mull a7, a5, a10
    mulsh a5, a5, a10
    MAC64_CARRY a3, a4, a7, a5

    // MAC 1: a6 * coef[1]
    l32i a5, a2, 8                 // Pre-load buffer[2]
    mull a7, a6, a11
    mulsh a6, a6, a11
    MAC64_CARRY a3, a4, a7, a6

    // MAC 2: a5 * coef[2]
    l32i a6, a2, 12                // Pre-load buffer[3]
    mull a7, a5, a12
    mulsh a5, a5, a12
    MAC64_CARRY a3, a4, a7, a5

    // MAC 3: a6 * coef[3]
    l32i a5, a2, 16                // Pre-load buffer[4]
    mull a7, a6, a13
    mulsh a6, a6, a13
    MAC64_CARRY a3, a4, a7, a6

    // MAC 4: a5 * coef[4]
    l32i a6, a2, 20                // Pre-load buffer[5]
    mull a7, a5, a14
    mulsh a5, a5, a14
    MAC64_CARRY a3, a4, a7, a5

    // MAC 5: a6 * coef[5] (last cached, transition to tail)
    l32i a5, a2, 24                // Pre-load buffer[6]
    mull a7, a6, a15
    mulsh a6, a6, a15
    l32i a8, a9, 24                // Pre-load coef[6]
    MAC64_CARRY a3, a4, a7, a6

    // === Tail section: MACs 6-11 (memory-loaded coefficients from a9) ===
    // MAC 6: a5 * coef[6]
    l32i a6, a2, 28                // Pre-load buffer[7]
    mull a7, a5, a8
    mulsh a5, a5, a8
    l32i a8, a9, 28                // Pre-load coef[7]
    MAC64_CARRY a3, a4, a7, a5

    // MAC 7: a6 * coef[7]
    l32i a5, a2, 32                // Pre-load buffer[8]
    mull a7, a6, a8
    mulsh a6, a6, a8
    l32i a8, a9, 32                // Pre-load coef[8]
    MAC64_CARRY a3, a4, a7, a6

    // MAC 8: a5 * coef[8]
    l32i a6, a2, 36                // Pre-load buffer[9]
    mull a7, a5, a8
    mulsh a5, a5, a8
    l32i a8, a9, 36                // Pre-load coef[9]
    MAC64_CARRY a3, a4, a7, a5

    // MAC 9: a6 * coef[9]
    l32i a5, a2, 40                // Pre-load buffer[10]
    mull a7, a6, a8
    mulsh a6, a6, a8
    l32i a8, a9, 40                // Pre-load coef[10]
    MAC64_CARRY a3, a4, a7, a6

    // MAC 10: a5 * coef[10]
    l32i a6, a2, 44                // Pre-load buffer[11]
    mull a7, a5, a8
    mulsh a5, a5, a8
    l32i a8, a9, 44                // Pre-load coef[11]
    MAC64_CARRY a3, a4, a7, a5

    // MAC 11: a6 * coef[11] - last, no pre-loads
    mull a7, a6, a8
    mulsh a6, a6, a8
    MAC64_CARRY a3, a4, a7, a6

    // Shift and store
    l32i a5, a2, 48                // Load residual (12*4 = 48)
    src a6, a4, a3
    add a5, a5, a6
    s32i a5, a2, 48
    addi a2, a2, 4
.L64_loop_12_end:
    j .L64_done

// ============================================================================
// Generic version for any order > 12 with 64-bit accumulation
// ============================================================================
.align 4
.L64_generic:
    // NOTE: Cannot use nested hardware loops on Xtensa (only one set of LCOUNT/LBEG/LEND
    // registers). Use branch-based outer loop with hardware loop for the inner loop only.
    // Register allocation:
    //   a4  = coefficients pointer (constant)
    //   a5  = order (constant)
    //   a7  = outer loop counter (decremented each iteration)
    //   a10 = working buffer pointer (advances each outer iteration)
    //   a11 = sum_low
    //   a12 = sum_high
    //   a13 = j counter / shifted result temp
    //   a14 = inner buffer position / address temp
    //   a15 = buffer value / residual temp
    //   a3, a9 = temps
    mov a10, a2                    // Working buffer pointer

.L64_outer_generic:
    movi a11, 0                    // sum_low = 0
    movi a12, 0                    // sum_high = 0
    movi a13, 0                    // j = 0 (inner loop counter)
    mov a14, a10                   // Current buffer position for inner loop

    loop a5, .L64_inner_generic_end
.L64_inner_generic:
    l32i a15, a14, 0               // buffer[i+j]
    addx4 a9, a13, a4              // &coef[j]
    l32i a3, a9, 0                 // coef[j]
    addi a13, a13, 1               // j++ (fill: resolves a3 load-use)

    // 64-bit multiplication and accumulation
    mull a9, a15, a3               // Low 32 bits (no stall on a3)
    mulsh a3, a15, a3              // High 32 bits (fill: separates mull from consumer)
    MAC64_CARRY a11, a12, a9, a3

    addi a14, a14, 4               // Advance buffer pointer
.L64_inner_generic_end:

    // Shift 64-bit result and add to residual
    addx4 a14, a5, a10             // &buffer[i + order]
    l32i a15, a14, 0               // Load residual
    src a13, a12, a11              // result = (high:low) >> shift
    add a15, a15, a13              // Add prediction
    s32i a15, a14, 0               // Store result

    addi a10, a10, 4               // Advance buffer pointer
    addi a7, a7, -1                // Decrement outer loop counter
    bnez a7, .L64_outer_generic
    j .L64_done

// ============================================================================
// Exit points
// ============================================================================
.align 4
.L64_done:
    retw.n

#endif  // FLAC_LPC_XTENSA_ENABLED
