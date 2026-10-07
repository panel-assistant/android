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

/// @file compiler.h
/// @brief Compiler hints and platform-specific macros

#pragma once

// Mark a function as hot. The compiler places hot functions in a dedicated
// .text.hot section for better i-cache locality and optimizes them more
// aggressively. Use on per-packet / per-frame functions on the audio receive
// and decode path.
#if defined(__GNUC__) || defined(__clang__)
#define SS_HOT __attribute__((hot))
#else
#define SS_HOT
#endif
