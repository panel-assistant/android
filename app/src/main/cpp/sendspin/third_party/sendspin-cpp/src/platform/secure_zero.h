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

/// @file secure_zero.h
/// @brief Secret erasure that survives dead-store elimination. Its own header so callers that
/// only need to wipe a buffer (platform/json_arena.h) do not pull in the crypto primitives.

#pragma once

#include <cstddef>
#include <cstdint>

namespace sendspin {

/// @brief Overwrite a buffer with zeroes through a volatile pointer, so the write survives
/// dead-store elimination (a plain memset on a buffer that is never read again is legal for the
/// compiler to delete outright).
///
/// Use on every scope-exit path that leaves key material behind: PSKs, AEAD keys, X25519 private
/// keys, PAKE scalars. It is a defence-in-depth measure, not a substitute for the fact that
/// long-lived secrets necessarily sit in RAM for as long as they are needed.
inline void secure_zero(void* p, size_t n) {
    volatile uint8_t* vp = static_cast<volatile uint8_t*>(p);
    for (size_t i = 0; i < n; ++i) {
        vp[i] = 0;
    }
}

/// @brief secure_zero() over a contiguous container (std::array, std::vector) of bytes.
/// No-op for an empty container: data() may legally be null and n is 0.
template <typename Container>
inline void secure_zero_container(Container& c) {
    if (!c.empty()) {
        secure_zero(c.data(), c.size() * sizeof(typename Container::value_type));
    }
}

}  // namespace sendspin
