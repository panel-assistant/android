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

/// @file thread.h
/// @brief Platform-abstracted thread configuration helper for setting stack size, priority, name,
/// and PSRAM stack allocation before spawning a std::thread

#pragma once

#include <cstddef>

#ifdef ESP_PLATFORM

#include <esp_heap_caps.h>
#include <esp_pthread.h>

namespace sendspin {

/// @brief Configures the next std::thread created on this calling thread
/// On ESP-IDF, sets stack size, priority, name, and optionally SPIRAM stack allocation
/// via esp_pthread_set_cfg.
/// @param name Thread name shown in the RTOS task list.
/// @param stack_size Stack size in bytes.
/// @param priority FreeRTOS task priority.
/// @param stack_in_psram If true, allocates the stack in PSRAM.
inline void platform_configure_thread(const char* name, size_t stack_size, int priority,
                                      bool stack_in_psram) {
    esp_pthread_cfg_t cfg = esp_pthread_get_default_config();
    cfg.stack_size = stack_size;
    cfg.prio = priority;
    cfg.thread_name = name;
    if (stack_in_psram) {
        cfg.stack_alloc_caps = MALLOC_CAP_SPIRAM;
    }
    esp_pthread_set_cfg(&cfg);
}

}  // namespace sendspin

#else  // Host

namespace sendspin {

/// @brief No-op on host; std::thread uses OS-default stack and scheduling
/// @param name Ignored on host.
/// @param stack_size Ignored on host.
/// @param priority Ignored on host.
/// @param stack_in_psram Ignored on host.
inline void platform_configure_thread(const char* /*name*/, size_t /*stack_size*/, int /*priority*/,
                                      bool /*stack_in_psram*/) {
    // No-op on host - std::thread uses OS defaults.
}

}  // namespace sendspin

#endif  // ESP_PLATFORM
