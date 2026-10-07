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

/// @file constants.h
/// @brief Shared unit-conversion constants, and the conversion of a deadline into a wait

#pragma once

#include <cstdint>

namespace sendspin {

static constexpr int64_t US_PER_MS = 1000LL;
static constexpr uint32_t MS_PER_SECOND = 1000U;
static constexpr uint32_t US_PER_SECOND = 1000000U;

/// @brief Converts a deadline into the milliseconds a protocol-task wait may last, rounded up so
/// the wake is never early; 0 when the deadline has passed, and never UINT32_MAX, which is
/// ProtocolTask::NO_DEADLINE
/// @param due_us The deadline, on the platform_time_us() clock.
/// @param now_us The current time on the same clock.
inline uint32_t ms_until(int64_t due_us, int64_t now_us) {
    if (due_us <= now_us) {
        return 0;
    }
    const int64_t wait_ms = (due_us - now_us + US_PER_MS - 1) / US_PER_MS;
    return wait_ms >= static_cast<int64_t>(UINT32_MAX) ? UINT32_MAX - 1
                                                       : static_cast<uint32_t>(wait_ms);
}

}  // namespace sendspin
