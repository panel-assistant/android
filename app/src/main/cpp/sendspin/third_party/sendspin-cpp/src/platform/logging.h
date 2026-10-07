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

/// @file logging.h
/// @brief Platform-abstracted logging macros mapping SS_LOG* to ESP-IDF or printf-based output

#pragma once

#ifdef ESP_PLATFORM

#include <esp_log.h>

#define SS_LOGE(tag, fmt, ...) ESP_LOGE(tag, fmt, ##__VA_ARGS__)
#define SS_LOGW(tag, fmt, ...) ESP_LOGW(tag, fmt, ##__VA_ARGS__)
#define SS_LOGI(tag, fmt, ...) ESP_LOGI(tag, fmt, ##__VA_ARGS__)
#define SS_LOGD(tag, fmt, ...) ESP_LOGD(tag, fmt, ##__VA_ARGS__)
#define SS_LOGV(tag, fmt, ...) ESP_LOGV(tag, fmt, ##__VA_ARGS__)

namespace sendspin {

/// @brief No-op on ESP-IDF; log levels are controlled at compile time
/// @param level Ignored on ESP-IDF.
inline void platform_set_log_level(int /*level*/) {}

/// @brief Returns INFO on ESP-IDF; runtime log level is not available
/// @return Always returns 3 (INFO) on ESP-IDF.
inline int platform_get_log_level() {
    return 3;
}

}  // namespace sendspin

#else  // Host

#include <cinttypes>
#include <cstdio>

#define SS_LOG_ERROR 1
#define SS_LOG_WARN 2
#define SS_LOG_INFO 3
#define SS_LOG_DEBUG 4
#define SS_LOG_VERBOSE 5

// Runtime log level - defaults to INFO, settable by the application (e.g., via command line)
inline int ss_host_log_level = SS_LOG_INFO;

#ifdef __ANDROID__
// ha-paneld: route host logging to logcat (tag "sendspin") instead of stderr.
#include <android/log.h>
// clang-format off
#define SS_LOG_ANDROID_(lvl, prio, tag, fmt, ...) do { if (ss_host_log_level >= (lvl)) __android_log_print((prio), "sendspin", "%s: " fmt, tag __VA_OPT__(,) __VA_ARGS__); } while(0)
#define SS_LOGE(tag, fmt, ...) SS_LOG_ANDROID_(SS_LOG_ERROR,   ANDROID_LOG_ERROR,   tag, fmt __VA_OPT__(,) __VA_ARGS__)
#define SS_LOGW(tag, fmt, ...) SS_LOG_ANDROID_(SS_LOG_WARN,    ANDROID_LOG_WARN,    tag, fmt __VA_OPT__(,) __VA_ARGS__)
#define SS_LOGI(tag, fmt, ...) SS_LOG_ANDROID_(SS_LOG_INFO,    ANDROID_LOG_INFO,    tag, fmt __VA_OPT__(,) __VA_ARGS__)
#define SS_LOGD(tag, fmt, ...) SS_LOG_ANDROID_(SS_LOG_DEBUG,   ANDROID_LOG_DEBUG,   tag, fmt __VA_OPT__(,) __VA_ARGS__)
#define SS_LOGV(tag, fmt, ...) SS_LOG_ANDROID_(SS_LOG_VERBOSE, ANDROID_LOG_VERBOSE, tag, fmt __VA_OPT__(,) __VA_ARGS__)
// clang-format on
#else
// clang-format off
#define SS_LOGE(tag, fmt, ...) do { if (ss_host_log_level >= SS_LOG_ERROR)   fprintf(stderr, "E %s: " fmt "\n", tag __VA_OPT__(,) __VA_ARGS__); } while(0)
#define SS_LOGW(tag, fmt, ...) do { if (ss_host_log_level >= SS_LOG_WARN)    fprintf(stderr, "W %s: " fmt "\n", tag __VA_OPT__(,) __VA_ARGS__); } while(0)
#define SS_LOGI(tag, fmt, ...) do { if (ss_host_log_level >= SS_LOG_INFO)    fprintf(stderr, "I %s: " fmt "\n", tag __VA_OPT__(,) __VA_ARGS__); } while(0)
#define SS_LOGD(tag, fmt, ...) do { if (ss_host_log_level >= SS_LOG_DEBUG)   fprintf(stderr, "D %s: " fmt "\n", tag __VA_OPT__(,) __VA_ARGS__); } while(0)
#define SS_LOGV(tag, fmt, ...) do { if (ss_host_log_level >= SS_LOG_VERBOSE) fprintf(stderr, "V %s: " fmt "\n", tag __VA_OPT__(,) __VA_ARGS__); } while(0)
// clang-format on
#endif  // __ANDROID__

namespace sendspin {

/// @brief Sets the runtime log level
/// @param level One of the SS_LOG_* constants.
inline void platform_set_log_level(int level) {
    ss_host_log_level = level;
}

/// @brief Returns the current runtime log level
/// @return One of the SS_LOG_* constants.
inline int platform_get_log_level() {
    return ss_host_log_level;
}

}  // namespace sendspin

#endif  // ESP_PLATFORM
