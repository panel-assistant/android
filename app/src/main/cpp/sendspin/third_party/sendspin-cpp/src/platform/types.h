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

/// @file types.h
/// @brief Platform-agnostic error code enum and utility functions for Sendspin APIs

#pragma once

#include <cstdint>

#ifdef ESP_PLATFORM
#include <esp_err.h>
#endif

namespace sendspin {

/// @brief Platform-agnostic error codes for Sendspin APIs
enum class SsErr : int16_t {
    // Success / informational (>= 0)
    OK = 0,  // Operation succeeded

    // Named error codes (> 0, mirrors esp_err_t values)
    NO_MEM = 0x101,         // Out of memory
    INVALID_ARG = 0x102,    // Invalid argument
    INVALID_STATE = 0x103,  // Invalid state for the operation
    INVALID_SIZE = 0x104,   // Invalid size
    NOT_FOUND = 0x105,      // Resource not found
    NOT_SUPPORTED = 0x106,  // Operation not supported
    TIMEOUT = 0x107,        // Operation timed out

    // Errors (< 0)
    FAIL = -1,  // Generic failure
};

}  // namespace sendspin
