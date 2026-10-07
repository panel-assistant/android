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

/// @file wrapping_arithmetic.h
/// @brief uint32_t-based wrapping arithmetic helpers
///
/// FLAC channel decorrelation is defined with modulo-2^bps wrapping semantics, so all
/// arithmetic must go through uint32_t to avoid signed integer overflow UB.
/// wadd32/wsub32 perform the full operation and return int32_t; u32 casts a single
/// value for use in expressions that produce an intermediate uint32_t result.

#pragma once

#include <cstdint>

namespace micro_flac {

inline constexpr int32_t wadd32(int32_t a, int32_t b) {
    return static_cast<int32_t>(static_cast<uint32_t>(a) + static_cast<uint32_t>(b));
}
inline constexpr int32_t wadd32(int64_t a, int32_t b) {
    return static_cast<int32_t>(static_cast<uint32_t>(a) + static_cast<uint32_t>(b));
}
inline constexpr int32_t wsub32(int32_t a, int32_t b) {
    return static_cast<int32_t>(static_cast<uint32_t>(a) - static_cast<uint32_t>(b));
}
inline constexpr int32_t wsub32(int32_t a, int64_t b) {
    return static_cast<int32_t>(static_cast<uint32_t>(a) - static_cast<uint32_t>(b));
}
inline constexpr int32_t wshl32(int32_t a, uint32_t n) {
    return static_cast<int32_t>(static_cast<uint32_t>(a) << n);
}
inline constexpr int32_t wshr32(int32_t a, uint32_t n) {
    return static_cast<int32_t>(static_cast<uint32_t>(a) >> n);
}
inline constexpr uint32_t u32(int32_t x) {
    return static_cast<uint32_t>(x);
}
inline constexpr uint32_t u32(int64_t x) {
    return static_cast<uint32_t>(x);
}

}  // namespace micro_flac
