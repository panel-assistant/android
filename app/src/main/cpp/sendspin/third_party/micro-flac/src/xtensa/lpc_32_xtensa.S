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
 * lpc_32_xtensa.S
 *
 * Optimized 32-bit assembly implementation of linear prediction restoration for FLAC decoding
 * on ESP32/Xtensa architecture.
 *
 * This implementation uses 32-bit integer arithmetic for efficient processing
 * of standard resolution audio where overflow is not possible.
 *
 * Features:
 * - 32-bit multiplication and accumulation
 * - Jump table dispatch for orders 1-12
 * - Fully unrolled inner loops for common orders
 * - Register-cached coefficients for orders 1-10
 * - Hybrid register-cached + memory-loaded coefficients for orders 11-12:
 *   coef[0]-coef[7] cached in a8-a15, coef[8]+ loaded from original pointer (a7)
 * - Software-pipelined MAC chain (orders 3-9): three-deep interleave using load(N+2),
 *   mull(N+1), add(N) ordering with rotating temps (a4/a5/a6) to hide both load-use
 *   and multiply-use latencies
 * - Generic fallback for orders > 12
 *
 * Unified register convention:
 * - a2: buffer pointer (all orders)
 * - a3: accumulator (also holds outer loop count until consumed by loop instruction)
 * - a4, a5, a6: rotating temp triple for pipelined load/multiply/accumulate (orders 3-9)
 * - a4, a5: alternating temp pair (orders 1-2, 10)
 * - Coefficient registers (sequential, orders 1-9):
 *     a7=coef[0], a8=coef[1], a9=coef[2], a10=coef[3], a11=coef[4],
 *     a12=coef[5], a13=coef[6], a14=coef[7], a15=coef[8]
 * - Coefficient registers (order 10, uses a4/a5 temps only):
 *     a6=coef[0], a7=coef[1], a8=coef[2], a9=coef[3], a10=coef[4],
 *     a11=coef[5], a12=coef[6], a13=coef[7], a14=coef[8], a15=coef[9]
 * - Hybrid coefficients (orders 11-12): a7=coef pointer,
 *     a8-a15=coef[0]-coef[7] cached, coef[8]+ loaded via a7;
 *     3-deep pipeline (a4/a5/a6 temps) for cached section,
 *     2-deep interleave (a4/a5 temps, a6 coef temp) for tail
 */

#include "lpc_xtensa.h"
#if (FLAC_LPC_XTENSA_ENABLED == 1)

.section .text
.global restore_lpc_32bit_asm
.type restore_lpc_32bit_asm, @function
.align 4

/**
 * @brief Optimized 32-bit linear prediction restoration for FLAC decoding
 *
 * @param a2 Pointer to sub_frame_buffer (int32_t*)
 * @param a3 Number of samples (num_of_samples)
 * @param a4 Pointer to coefficients array (int32_t*)
 * @param a5 Number of coefficients (order)
 * @param a6 Shift amount (0-31)
 *
 * Uses 32-bit arithmetic: sum = Σ(buffer[i+j] * coef[j]) >> shift
 */
restore_lpc_32bit_asm:
    entry a1, 32                    // Minimum stack frame (windowed ABI base save area)

    beqz a5, .Ldone                // If order is 0, nothing to do

    // Calculate outer loop bound: num_samples - order
    sub a3, a3, a5                  // a3 = outer_loop_bound
    blti a3, 1, .Ldone              // If no iterations needed (a3 <= 0), exit

    // Set shift register before dispatch (all handlers need it, frees a6)
    ssr a6

    // Jump table dispatch based on coefficient order
    movi a6, 12
    blt a6, a5, .Lgeneric           // If 12 < order (order > 12), use generic version

    // Calculate jump address: jump_table + (order-1)*4
    addi a6, a5, -1                 // a6 = order - 1
    movi a7, .Ljump_table
    addx4 a6, a6, a7                // a6 = jump_table + (order-1)*4
    l32i a6, a6, 0                  // Load jump address
    jx a6                           // Jump to specific handler

.align 4
.Ljump_table:
    .word .Lorder_1
    .word .Lorder_2
    .word .Lorder_3
    .word .Lorder_4
    .word .Lorder_5
    .word .Lorder_6
    .word .Lorder_7
    .word .Lorder_8
    .word .Lorder_9
    .word .Lorder_10
    .word .Lorder_11
    .word .Lorder_12

// ============================================================================
// Order 1: Single coefficient
// ============================================================================
.align 4
.Lorder_1:
    l32i a7, a4, 0                  // coef[0]

    loop a3, .Lloop_1_end
.Lloop_1:
    l32i a4, a2, 0                  // buffer[i]
    l32i a5, a2, 4                  // Load residual (also fills load-use latency on a4)
    mull a4, a4, a7                 // sum = buffer[i] * coef[0]
    sra a4, a4                      // sum >>= shift
    add a5, a5, a4                  // Add prediction to residual
    s32i a5, a2, 4                  // Store result back
    addi a2, a2, 4                  // Advance buffer pointer
.Lloop_1_end:
    j .Ldone

// ============================================================================
// Order 2: Two coefficients
// ============================================================================
.align 4
.Lorder_2:
    l32i a7, a4, 0                  // coef[0]
    l32i a8, a4, 4                  // coef[1]

    loop a3, .Lloop_2_end
.Lloop_2:
    l32i a4, a2, 0                  // buffer[i]
    l32i a5, a2, 4                  // buffer[i+1] (also fills load-use latency on a4)
    mull a4, a4, a7                 // buffer[i] * coef[0]
    mull a5, a5, a8                 // buffer[i+1] * coef[1]
    l32i a6, a2, 8                  // Load residual (fills multiply-use latency on a5)
    add a3, a4, a5                  // sum
    sra a3, a3                      // sum >>= shift
    add a6, a6, a3                  // Add prediction
    s32i a6, a2, 8                  // Store back
    addi a2, a2, 4
.Lloop_2_end:
    j .Ldone

// ============================================================================
// Order 3: Three coefficients
// ============================================================================
.align 4
.Lorder_3:
    l32i a7, a4, 0                  // coef[0]
    l32i a8, a4, 4                  // coef[1]
    l32i a9, a4, 8                  // coef[2]

    loop a3, .Lloop_3_end
.Lloop_3:
    movi a3, 0                      // sum = 0

    // Prologue: 2 loads + 1 mull to prime pipeline
    l32i a4, a2, 0                  // load MAC 0
    l32i a5, a2, 4                  // load MAC 1
    mull a4, a4, a7                 // mull MAC 0

    // Steady state: load(N+2), mull(N+1), add(N)
    l32i a6, a2, 8                  // load MAC 2
    mull a5, a5, a8                 // mull MAC 1
    add a3, a3, a4                  // add MAC 0

    // Epilogue: drain last 2 results
    mull a6, a6, a9                 // mull MAC 2
    add a3, a3, a5                  // add MAC 1
    add a3, a3, a6                  // add MAC 2

    // Shift and store result
    l32i a4, a2, 12                 // Load residual
    sra a3, a3
    add a4, a4, a3
    s32i a4, a2, 12
    addi a2, a2, 4
.Lloop_3_end:
    j .Ldone

// ============================================================================
// Order 4: Four coefficients
// ============================================================================
.align 4
.Lorder_4:
    l32i a7, a4, 0                  // coef[0]
    l32i a8, a4, 4                  // coef[1]
    l32i a9, a4, 8                  // coef[2]
    l32i a10, a4, 12                // coef[3]

    loop a3, .Lloop_4_end
.Lloop_4:
    movi a3, 0                      // sum = 0

    // Prologue: 2 loads + 1 mull to prime pipeline
    l32i a4, a2, 0                  // load MAC 0
    l32i a5, a2, 4                  // load MAC 1
    mull a4, a4, a7                 // mull MAC 0

    // Steady state: load(N+2), mull(N+1), add(N)
    l32i a6, a2, 8                  // load MAC 2
    mull a5, a5, a8                 // mull MAC 1
    add a3, a3, a4                  // add MAC 0

    l32i a4, a2, 12                 // load MAC 3
    mull a6, a6, a9                 // mull MAC 2
    add a3, a3, a5                  // add MAC 1

    // Epilogue: drain last 2 results
    mull a4, a4, a10                // mull MAC 3
    add a3, a3, a6                  // add MAC 2
    add a3, a3, a4                  // add MAC 3

    // Shift and store result
    l32i a4, a2, 16                 // Load residual
    sra a3, a3
    add a4, a4, a3
    s32i a4, a2, 16
    addi a2, a2, 4
.Lloop_4_end:
    j .Ldone

// ============================================================================
// Order 5: Five coefficients
// ============================================================================
.align 4
.Lorder_5:
    l32i a7, a4, 0                  // coef[0]
    l32i a8, a4, 4                  // coef[1]
    l32i a9, a4, 8                  // coef[2]
    l32i a10, a4, 12                // coef[3]
    l32i a11, a4, 16                // coef[4]

    loop a3, .Lloop_5_end
.Lloop_5:
    movi a3, 0                      // sum = 0

    // Prologue: 2 loads + 1 mull to prime pipeline
    l32i a4, a2, 0                  // load MAC 0
    l32i a5, a2, 4                  // load MAC 1
    mull a4, a4, a7                 // mull MAC 0

    // Steady state: load(N+2), mull(N+1), add(N)
    l32i a6, a2, 8                  // load MAC 2
    mull a5, a5, a8                 // mull MAC 1
    add a3, a3, a4                  // add MAC 0

    l32i a4, a2, 12                 // load MAC 3
    mull a6, a6, a9                 // mull MAC 2
    add a3, a3, a5                  // add MAC 1

    l32i a5, a2, 16                 // load MAC 4
    mull a4, a4, a10                // mull MAC 3
    add a3, a3, a6                  // add MAC 2

    // Epilogue: drain last 2 results
    mull a5, a5, a11                // mull MAC 4
    add a3, a3, a4                  // add MAC 3
    add a3, a3, a5                  // add MAC 4

    // Shift and store result
    l32i a4, a2, 20                 // Load residual
    sra a3, a3
    add a4, a4, a3
    s32i a4, a2, 20
    addi a2, a2, 4
.Lloop_5_end:
    j .Ldone

// ============================================================================
// Order 6: Six coefficients
// ============================================================================
.align 4
.Lorder_6:
    l32i a7, a4, 0                  // coef[0]
    l32i a8, a4, 4                  // coef[1]
    l32i a9, a4, 8                  // coef[2]
    l32i a10, a4, 12                // coef[3]
    l32i a11, a4, 16                // coef[4]
    l32i a12, a4, 20                // coef[5]

    loop a3, .Lloop_6_end
.Lloop_6:
    movi a3, 0                      // sum = 0

    // Prologue: 2 loads + 1 mull to prime pipeline
    l32i a4, a2, 0                  // load MAC 0
    l32i a5, a2, 4                  // load MAC 1
    mull a4, a4, a7                 // mull MAC 0

    // Steady state: load(N+2), mull(N+1), add(N)
    l32i a6, a2, 8                  // load MAC 2
    mull a5, a5, a8                 // mull MAC 1
    add a3, a3, a4                  // add MAC 0

    l32i a4, a2, 12                 // load MAC 3
    mull a6, a6, a9                 // mull MAC 2
    add a3, a3, a5                  // add MAC 1

    l32i a5, a2, 16                 // load MAC 4
    mull a4, a4, a10                // mull MAC 3
    add a3, a3, a6                  // add MAC 2

    l32i a6, a2, 20                 // load MAC 5
    mull a5, a5, a11                // mull MAC 4
    add a3, a3, a4                  // add MAC 3

    // Epilogue: drain last 2 results
    mull a6, a6, a12                // mull MAC 5
    add a3, a3, a5                  // add MAC 4
    add a3, a3, a6                  // add MAC 5

    // Shift and store result
    l32i a4, a2, 24                 // Load residual
    sra a3, a3
    add a4, a4, a3
    s32i a4, a2, 24
    addi a2, a2, 4
.Lloop_6_end:
    j .Ldone

// ============================================================================
// Order 7: Seven coefficients
// ============================================================================
.align 4
.Lorder_7:
    l32i a7, a4, 0                  // coef[0]
    l32i a8, a4, 4                  // coef[1]
    l32i a9, a4, 8                  // coef[2]
    l32i a10, a4, 12                // coef[3]
    l32i a11, a4, 16                // coef[4]
    l32i a12, a4, 20                // coef[5]
    l32i a13, a4, 24                // coef[6]

    loop a3, .Lloop_7_end
.Lloop_7:
    movi a3, 0                      // sum = 0

    // Prologue: 2 loads + 1 mull to prime pipeline
    l32i a4, a2, 0                  // load MAC 0
    l32i a5, a2, 4                  // load MAC 1
    mull a4, a4, a7                 // mull MAC 0

    // Steady state: load(N+2), mull(N+1), add(N)
    l32i a6, a2, 8                  // load MAC 2
    mull a5, a5, a8                 // mull MAC 1
    add a3, a3, a4                  // add MAC 0

    l32i a4, a2, 12                 // load MAC 3
    mull a6, a6, a9                 // mull MAC 2
    add a3, a3, a5                  // add MAC 1

    l32i a5, a2, 16                 // load MAC 4
    mull a4, a4, a10                // mull MAC 3
    add a3, a3, a6                  // add MAC 2

    l32i a6, a2, 20                 // load MAC 5
    mull a5, a5, a11                // mull MAC 4
    add a3, a3, a4                  // add MAC 3

    l32i a4, a2, 24                 // load MAC 6
    mull a6, a6, a12                // mull MAC 5
    add a3, a3, a5                  // add MAC 4

    // Epilogue: drain last 2 results
    mull a4, a4, a13                // mull MAC 6
    add a3, a3, a6                  // add MAC 5
    add a3, a3, a4                  // add MAC 6

    // Shift and store result
    l32i a4, a2, 28                 // Load residual
    sra a3, a3
    add a4, a4, a3
    s32i a4, a2, 28
    addi a2, a2, 4
.Lloop_7_end:
    j .Ldone

// ============================================================================
// Order 8: Eight coefficients
// ============================================================================
.align 4
.Lorder_8:
    l32i a7, a4, 0                  // coef[0]
    l32i a8, a4, 4                  // coef[1]
    l32i a9, a4, 8                  // coef[2]
    l32i a10, a4, 12                // coef[3]
    l32i a11, a4, 16                // coef[4]
    l32i a12, a4, 20                // coef[5]
    l32i a13, a4, 24                // coef[6]
    l32i a14, a4, 28                // coef[7]

    loop a3, .Lloop_8_end
.Lloop_8:
    movi a3, 0                      // sum = 0

    // Prologue: 2 loads + 1 mull to prime pipeline
    l32i a4, a2, 0                  // load MAC 0
    l32i a5, a2, 4                  // load MAC 1
    mull a4, a4, a7                 // mull MAC 0

    // Steady state: load(N+2), mull(N+1), add(N)
    l32i a6, a2, 8                  // load MAC 2
    mull a5, a5, a8                 // mull MAC 1
    add a3, a3, a4                  // add MAC 0

    l32i a4, a2, 12                 // load MAC 3
    mull a6, a6, a9                 // mull MAC 2
    add a3, a3, a5                  // add MAC 1

    l32i a5, a2, 16                 // load MAC 4
    mull a4, a4, a10                // mull MAC 3
    add a3, a3, a6                  // add MAC 2

    l32i a6, a2, 20                 // load MAC 5
    mull a5, a5, a11                // mull MAC 4
    add a3, a3, a4                  // add MAC 3

    l32i a4, a2, 24                 // load MAC 6
    mull a6, a6, a12                // mull MAC 5
    add a3, a3, a5                  // add MAC 4

    l32i a5, a2, 28                 // load MAC 7
    mull a4, a4, a13                // mull MAC 6
    add a3, a3, a6                  // add MAC 5

    // Epilogue: drain last 2 results
    mull a5, a5, a14                // mull MAC 7
    add a3, a3, a4                  // add MAC 6
    add a3, a3, a5                  // add MAC 7

    // Shift and store result
    l32i a4, a2, 32                 // Load residual
    sra a3, a3
    add a4, a4, a3
    s32i a4, a2, 32
    addi a2, a2, 4
.Lloop_8_end:
    j .Ldone

// ============================================================================
// Order 9: Nine coefficients
// ============================================================================
.align 4
.Lorder_9:
    l32i a7, a4, 0                  // coef[0]
    l32i a8, a4, 4                  // coef[1]
    l32i a9, a4, 8                  // coef[2]
    l32i a10, a4, 12                // coef[3]
    l32i a11, a4, 16                // coef[4]
    l32i a12, a4, 20                // coef[5]
    l32i a13, a4, 24                // coef[6]
    l32i a14, a4, 28                // coef[7]
    l32i a15, a4, 32                // coef[8]

    loop a3, .Lloop_9_end
.Lloop_9:
    movi a3, 0                      // sum = 0

    // Prologue: 2 loads + 1 mull to prime pipeline
    l32i a4, a2, 0                  // load MAC 0
    l32i a5, a2, 4                  // load MAC 1
    mull a4, a4, a7                 // mull MAC 0

    // Steady state: load(N+2), mull(N+1), add(N)
    l32i a6, a2, 8                  // load MAC 2
    mull a5, a5, a8                 // mull MAC 1
    add a3, a3, a4                  // add MAC 0

    l32i a4, a2, 12                 // load MAC 3
    mull a6, a6, a9                 // mull MAC 2
    add a3, a3, a5                  // add MAC 1

    l32i a5, a2, 16                 // load MAC 4
    mull a4, a4, a10                // mull MAC 3
    add a3, a3, a6                  // add MAC 2

    l32i a6, a2, 20                 // load MAC 5
    mull a5, a5, a11                // mull MAC 4
    add a3, a3, a4                  // add MAC 3

    l32i a4, a2, 24                 // load MAC 6
    mull a6, a6, a12                // mull MAC 5
    add a3, a3, a5                  // add MAC 4

    l32i a5, a2, 28                 // load MAC 7
    mull a4, a4, a13                // mull MAC 6
    add a3, a3, a6                  // add MAC 5

    l32i a6, a2, 32                 // load MAC 8
    mull a5, a5, a14                // mull MAC 7
    add a3, a3, a4                  // add MAC 6

    // Epilogue: drain last 2 results
    mull a6, a6, a15                // mull MAC 8
    add a3, a3, a5                  // add MAC 7
    add a3, a3, a6                  // add MAC 8

    // Shift and store result
    l32i a4, a2, 36                 // Load residual
    sra a3, a3
    add a4, a4, a3
    s32i a4, a2, 36
    addi a2, a2, 4
.Lloop_9_end:
    j .Ldone

// ============================================================================
// Order 10: Ten coefficients (register-cached)
// ============================================================================
.align 4
.Lorder_10:
    l32i a6, a4, 0                  // coef[0]
    l32i a7, a4, 4                  // coef[1]
    l32i a8, a4, 8                  // coef[2]
    l32i a9, a4, 12                 // coef[3]
    l32i a10, a4, 16                // coef[4]
    l32i a11, a4, 20                // coef[5]
    l32i a12, a4, 24                // coef[6]
    l32i a13, a4, 28                // coef[7]
    l32i a14, a4, 32                // coef[8]
    l32i a15, a4, 36                // coef[9]

    loop a3, .Lloop_10_end
.Lloop_10:
    movi a3, 0                      // sum = 0

    l32i a4, a2, 0                  // buffer[0]
    l32i a5, a2, 4                  // Pre-load buffer[1]
    mull a4, a4, a6                 // buffer[0] * coef[0]
    add a3, a3, a4                  // Accumulate

    l32i a4, a2, 8                  // Pre-load buffer[2]
    mull a5, a5, a7                 // buffer[1] * coef[1]
    add a3, a3, a5                  // Accumulate

    l32i a5, a2, 12                 // Pre-load buffer[3]
    mull a4, a4, a8                 // buffer[2] * coef[2]
    add a3, a3, a4                  // Accumulate

    l32i a4, a2, 16                 // Pre-load buffer[4]
    mull a5, a5, a9                 // buffer[3] * coef[3]
    add a3, a3, a5                  // Accumulate

    l32i a5, a2, 20                 // Pre-load buffer[5]
    mull a4, a4, a10                // buffer[4] * coef[4]
    add a3, a3, a4                  // Accumulate

    l32i a4, a2, 24                 // Pre-load buffer[6]
    mull a5, a5, a11                // buffer[5] * coef[5]
    add a3, a3, a5                  // Accumulate

    l32i a5, a2, 28                 // Pre-load buffer[7]
    mull a4, a4, a12                // buffer[6] * coef[6]
    add a3, a3, a4                  // Accumulate

    l32i a4, a2, 32                 // Pre-load buffer[8]
    mull a5, a5, a13                // buffer[7] * coef[7]
    add a3, a3, a5                  // Accumulate

    l32i a5, a2, 36                 // Pre-load buffer[9]
    mull a4, a4, a14                // buffer[8] * coef[8]
    add a3, a3, a4                  // Accumulate

    // MAC 10: last - no pre-load
    mull a5, a5, a15                // buffer[9] * coef[9]
    add a3, a3, a5                  // Accumulate

    // Shift and store result
    l32i a4, a2, 40                 // Load residual at offset 40 (10*4)
    sra a3, a3
    add a4, a4, a3
    s32i a4, a2, 40
    addi a2, a2, 4
.Lloop_10_end:
    j .Ldone

// ============================================================================
// Order 11: Eleven coefficients (hybrid: 8 cached + 3 memory-loaded)
// ============================================================================
.align 4
.Lorder_11:
    // Cache coef[0]-coef[7] in registers, keep pointer for coef[8]-coef[10]
    mov a7, a4                      // Save coef pointer for tail section
    l32i a8, a4, 0                  // coef[0]
    l32i a9, a4, 4                  // coef[1]
    l32i a10, a4, 8                 // coef[2]
    l32i a11, a4, 12                // coef[3]
    l32i a12, a4, 16                // coef[4]
    l32i a13, a4, 20                // coef[5]
    l32i a14, a4, 24                // coef[6]
    l32i a15, a4, 28                // coef[7]

    loop a3, .Lloop_11_end
.Lloop_11:
    movi a3, 0                      // sum = 0

    // === Pipelined section: MACs 0-7 (register-cached coefficients) ===
    // Prologue: 2 loads + 1 mull to prime pipeline
    l32i a4, a2, 0                  // load MAC 0
    l32i a5, a2, 4                  // load MAC 1
    mull a4, a4, a8                 // mull MAC 0

    // Steady state: load(N+2), mull(N+1), add(N)
    l32i a6, a2, 8                  // load MAC 2
    mull a5, a5, a9                 // mull MAC 1
    add a3, a3, a4                  // add MAC 0

    l32i a4, a2, 12                 // load MAC 3
    mull a6, a6, a10                // mull MAC 2
    add a3, a3, a5                  // add MAC 1

    l32i a5, a2, 16                 // load MAC 4
    mull a4, a4, a11                // mull MAC 3
    add a3, a3, a6                  // add MAC 2

    l32i a6, a2, 20                 // load MAC 5
    mull a5, a5, a12                // mull MAC 4
    add a3, a3, a4                  // add MAC 3

    l32i a4, a2, 24                 // load MAC 6
    mull a6, a6, a13                // mull MAC 5
    add a3, a3, a5                  // add MAC 4

    l32i a5, a2, 28                 // load MAC 7
    mull a4, a4, a14                // mull MAC 6
    add a3, a3, a6                  // add MAC 5

    // Epilogue: drain last 2 results
    mull a5, a5, a15                // mull MAC 7
    add a3, a3, a4                  // add MAC 6
    add a3, a3, a5                  // add MAC 7

    // === Tail section: MACs 8-10 (memory-loaded coefficients from a7) ===
    l32i a4, a2, 32                 // buffer[8]
    l32i a6, a7, 32                 // coef[8]

    l32i a5, a2, 36                 // Pre-load buffer[9]
    mull a4, a4, a6
    l32i a6, a7, 36                 // Pre-load coef[9]
    add a3, a3, a4                  // Accumulate MAC 8

    l32i a4, a2, 40                 // Pre-load buffer[10]
    mull a5, a5, a6
    l32i a6, a7, 40                 // Pre-load coef[10]
    add a3, a3, a5                  // Accumulate MAC 9

    // MAC 10: last - no pre-load
    mull a4, a4, a6
    add a3, a3, a4                  // Accumulate MAC 10

    // Shift and store result
    l32i a4, a2, 44                 // Load residual at offset 44 (11*4)
    sra a3, a3
    add a4, a4, a3
    s32i a4, a2, 44
    addi a2, a2, 4
.Lloop_11_end:
    j .Ldone

// ============================================================================
// Order 12: Twelve coefficients (hybrid: 8 cached + 4 memory-loaded)
// ============================================================================
.align 4
.Lorder_12:
    // Cache coef[0]-coef[7] in registers, keep pointer for coef[8]-coef[11]
    mov a7, a4                      // Save coef pointer for tail section
    l32i a8, a4, 0                  // coef[0]
    l32i a9, a4, 4                  // coef[1]
    l32i a10, a4, 8                 // coef[2]
    l32i a11, a4, 12                // coef[3]
    l32i a12, a4, 16                // coef[4]
    l32i a13, a4, 20                // coef[5]
    l32i a14, a4, 24                // coef[6]
    l32i a15, a4, 28                // coef[7]

    loop a3, .Lloop_12_end
.Lloop_12:
    movi a3, 0                      // sum = 0

    // === Pipelined section: MACs 0-7 (register-cached coefficients) ===
    // Prologue: 2 loads + 1 mull to prime pipeline
    l32i a4, a2, 0                  // load MAC 0
    l32i a5, a2, 4                  // load MAC 1
    mull a4, a4, a8                 // mull MAC 0

    // Steady state: load(N+2), mull(N+1), add(N)
    l32i a6, a2, 8                  // load MAC 2
    mull a5, a5, a9                 // mull MAC 1
    add a3, a3, a4                  // add MAC 0

    l32i a4, a2, 12                 // load MAC 3
    mull a6, a6, a10                // mull MAC 2
    add a3, a3, a5                  // add MAC 1

    l32i a5, a2, 16                 // load MAC 4
    mull a4, a4, a11                // mull MAC 3
    add a3, a3, a6                  // add MAC 2

    l32i a6, a2, 20                 // load MAC 5
    mull a5, a5, a12                // mull MAC 4
    add a3, a3, a4                  // add MAC 3

    l32i a4, a2, 24                 // load MAC 6
    mull a6, a6, a13                // mull MAC 5
    add a3, a3, a5                  // add MAC 4

    l32i a5, a2, 28                 // load MAC 7
    mull a4, a4, a14                // mull MAC 6
    add a3, a3, a6                  // add MAC 5

    // Epilogue: drain last 2 results
    mull a5, a5, a15                // mull MAC 7
    add a3, a3, a4                  // add MAC 6
    add a3, a3, a5                  // add MAC 7

    // === Tail section: MACs 8-11 (memory-loaded coefficients from a7) ===
    l32i a4, a2, 32                 // buffer[8]
    l32i a6, a7, 32                 // coef[8]

    l32i a5, a2, 36                 // Pre-load buffer[9]
    mull a4, a4, a6
    l32i a6, a7, 36                 // Pre-load coef[9]
    add a3, a3, a4                  // Accumulate MAC 8

    l32i a4, a2, 40                 // Pre-load buffer[10]
    mull a5, a5, a6
    l32i a6, a7, 40                 // Pre-load coef[10]
    add a3, a3, a5                  // Accumulate MAC 9

    l32i a5, a2, 44                 // Pre-load buffer[11]
    mull a4, a4, a6
    l32i a6, a7, 44                 // Pre-load coef[11]
    add a3, a3, a4                  // Accumulate MAC 10

    // MAC 11: last - no pre-load
    mull a5, a5, a6
    add a3, a3, a5                  // Accumulate MAC 11

    // Shift and store result
    l32i a4, a2, 48                 // Load residual at offset 48 (12*4)
    sra a3, a3
    add a4, a4, a3
    s32i a4, a2, 48
    addi a2, a2, 4
.Lloop_12_end:
    j .Ldone

// ============================================================================
// Generic version for any order > 12
// ============================================================================
.align 4
.Lgeneric:
    // NOTE: Cannot use nested hardware loops on Xtensa (only one set of LCOUNT/LBEG/LEND
    // registers). Use branch-based outer loop with hardware loop for the inner loop only.
    // Register allocation:
    //   a2 = buffer pointer (advances each outer iteration)
    //   a3 = outer loop counter (decremented each iteration)
    //   a4 = coefficients pointer (constant)
    //   a5 = order (constant)
    //   a6 = sum accumulator
    //   a7 = j counter
    //   a8 = inner buffer position
    //   a9, a10 = temps
.Louter_generic:
    movi a6, 0                      // sum = 0
    movi a7, 0                      // j = 0 (inner loop counter)
    mov a8, a2                      // Current buffer position for inner loop

    loop a5, .Linner_generic_end
.Linner_generic:
    l32i a9, a8, 0                  // buffer[i+j]
    addx4 a10, a7, a4              // &coef[j]
    l32i a10, a10, 0               // coef[j]
    mull a9, a9, a10               // buffer[i+j] * coef[j]
    add a6, a6, a9                  // sum += ...

    addi a7, a7, 1                  // j++
    addi a8, a8, 4                  // Advance buffer pointer for inner loop
.Linner_generic_end:

    addx4 a7, a5, a2               // &buffer[i + order]
    l32i a8, a7, 0                  // Load residual
    sra a6, a6                      // sum >>= shift
    add a8, a8, a6                  // Add prediction
    s32i a8, a7, 0                  // Store result

    addi a2, a2, 4                  // Advance buffer pointer
    addi a3, a3, -1                 // Decrement outer loop counter
    bnez a3, .Louter_generic
    j .Ldone

// ============================================================================
// Exit point
// ============================================================================
.align 4
.Ldone:
    retw.n

#endif  // FLAC_LPC_XTENSA_ENABLED
