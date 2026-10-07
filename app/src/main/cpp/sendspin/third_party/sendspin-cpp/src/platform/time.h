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

/// @file time.h
/// @brief Platform-abstracted monotonic time source providing microsecond resolution

#pragma once

#include <cstdint>

#ifdef ESP_PLATFORM

#include <esp_timer.h>
#include <freertos/FreeRTOS.h>

namespace sendspin {

/// @brief Returns monotonic time in microseconds
/// @return Microseconds elapsed since boot.
inline int64_t platform_time_us() {
    return esp_timer_get_time();
}

/// @brief Converts a millisecond timeout to FreeRTOS ticks: UINT32_MAX waits indefinitely, and
/// any other non-zero timeout waits at least one tick, so a wait shorter than a tick period
/// blocks rather than polling (the host primitives wait the requested time either way).
/// pdMS_TO_TICKS() rounds down, and a one-tick wait ends at the next tick interrupt, which can
/// come almost at once, so a timeout here is not a minimum; a caller that needs one adds a tick.
inline TickType_t platform_ms_to_ticks(uint32_t timeout_ms) {
    if (timeout_ms == UINT32_MAX) {
        return portMAX_DELAY;
    }
    const TickType_t ticks = pdMS_TO_TICKS(timeout_ms);
    return (timeout_ms != 0 && ticks == 0) ? 1 : ticks;
}

}  // namespace sendspin

#else  // Host

#include <chrono>

namespace sendspin {

/// @brief Returns monotonic time in microseconds
/// @return Microseconds elapsed since an arbitrary epoch (steady_clock).
inline int64_t platform_time_us() {
    auto now = std::chrono::steady_clock::now();
    return std::chrono::duration_cast<std::chrono::microseconds>(now.time_since_epoch()).count();
}

}  // namespace sendspin

#endif  // ESP_PLATFORM
