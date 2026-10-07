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

/// @file compiler.h
/// @brief Compiler hints and platform-specific macros
///
/// Provides optimization attributes, branch prediction hints, forced inlining,
/// and bit buffer constants.

#pragma once

// Define optimization attribute for GCC/ESP32 builds
// This ensures PlatformIO builds get O3 optimization for critical functions
#if defined(__GNUC__) && !defined(__clang__)
#define FLAC_OPTIMIZE_O3 __attribute__((optimize("O3")))
#else
#define FLAC_OPTIMIZE_O3
#endif

// Force inline for performance-critical functions
#if defined(__GNUC__) || defined(__clang__)
#define FLAC_ALWAYS_INLINE inline __attribute__((always_inline))
#elif defined(_MSC_VER)
#define FLAC_ALWAYS_INLINE __forceinline
#else
#define FLAC_ALWAYS_INLINE inline
#endif

// Cache line alignment for hot path functions
// Prevents performance regressions from code layout changes in preceding functions
#if defined(__GNUC__) || defined(__clang__)
#define FLAC_HOT __attribute__((hot))
#else
#define FLAC_HOT
#endif

// Branch prediction hints
#if defined(__GNUC__) || defined(__clang__)
#define FLAC_LIKELY(x) __builtin_expect(!!(x), 1)
#define FLAC_UNLIKELY(x) __builtin_expect(!!(x), 0)
#else
#define FLAC_LIKELY(x) (x)
#define FLAC_UNLIKELY(x) (x)
#endif

#include <cstdint>

namespace micro_flac {

// ============================================================================
// Bit Buffer Constants (implementation-only)
// ============================================================================

// The bit_buffer_t type is defined in flac_decoder.h (public header).
// These constants and macros are only needed internally by the bit reader implementation.
// Use a 32-bit bit buffer on 32-bit platforms (Xtensa, ARM32, etc.) and a 64-bit
// bit buffer on 64-bit platforms to reduce refill frequency.
#if UINTPTR_MAX == 0xFFFFFFFF
#define BIT_BUFFER_BITS 32
#define FLAC_CLZ(x) __builtin_clz(x)
#else
#define BIT_BUFFER_BITS 64
#define FLAC_CLZ(x) __builtin_clzll(x)
#endif

// Number of bytes that fill the bit buffer completely
#define BIT_BUFFER_BYTES (BIT_BUFFER_BITS / 8)

}  // namespace micro_flac
