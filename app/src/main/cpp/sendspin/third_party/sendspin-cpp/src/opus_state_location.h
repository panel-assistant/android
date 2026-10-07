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

/// @file opus_state_location.h
/// @brief Memory placement for the libopus codec state this library allocates itself

#pragma once

#include "sendspin/types.h"

// The CONFIG_OPUS_* tests below and in the Opus sources that include this header need the
// sdkconfig regardless of include order; host builds have none and take the defaults
#if __has_include(<sdkconfig.h>)
#include <sdkconfig.h>
#endif

namespace sendspin {

// Mirrors micro-opus's CONFIG_OPUS_STATE_MEMORY_PREFERENCE, which covers only state that
// opus_*_create() allocates: the player's decoder and the source's encoder use the *_init()
// variants and own the buffer, and take the same placement rule. The strict *_ONLY modes become
// a soft preference; the state is only tens of KB.
#if defined(CONFIG_OPUS_STATE_PREFER_INTERNAL) || defined(CONFIG_OPUS_STATE_INTERNAL_ONLY)
constexpr MemoryLocation OPUS_STATE_LOCATION = MemoryLocation::PREFER_INTERNAL;
#else
constexpr MemoryLocation OPUS_STATE_LOCATION = MemoryLocation::PREFER_EXTERNAL;
#endif

}  // namespace sendspin
