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

/// @file audio_stream_info.h
/// @brief Audio stream format descriptor with unit conversion helpers for bytes, frames, samples,
/// and durations

#pragma once

#include "constants.h"

#include <cstddef>
#include <cstdint>

namespace sendspin {

/// @brief Default audio sample rate in Hz used when no format is specified
static constexpr uint32_t DEFAULT_SAMPLE_RATE_HZ = 16000U;

/**
 * @brief Audio stream format descriptor with unit conversion helpers for bytes, frames,
 * samples, and durations
 *
 * Stores bits_per_sample, channels, and sample_rate for a single audio stream and
 * provides arithmetic conversions between those units. A sample is a single value for
 * one channel; a frame is one sample per channel (e.g., 2 samples for stereo).
 *
 * Usage:
 * 1. Construct with the stream's bits_per_sample, channels, and sample_rate
 * 2. Use the conversion methods to translate between bytes, frames, samples, and time
 * 3. Use operator== to detect format changes between streams
 *
 * @code
 * AudioStreamInfo info(16, 2, 44100);  // 16-bit stereo at 44.1 kHz
 *
 * size_t bytes_for_100ms = info.ms_to_bytes(100);
 * uint32_t frames        = info.bytes_to_frames(bytes_for_100ms);
 * int64_t duration_us    = info.frames_to_microseconds(frames);
 * @endcode
 */
class AudioStreamInfo {
public:
    /// @brief Default-constructs an empty AudioStreamInfo with zero-initialized fields
    AudioStreamInfo() : AudioStreamInfo(16, 1, DEFAULT_SAMPLE_RATE_HZ) {}
    /// @brief Constructs an AudioStreamInfo with the given format parameters
    /// @param bits_per_sample Number of bits per audio sample (e.g., 16).
    /// @param channels Number of audio channels (e.g., 1 for mono, 2 for stereo).
    /// @param sample_rate Audio sample rate in Hz (e.g., 44100).
    AudioStreamInfo(uint8_t bits_per_sample, uint8_t channels, uint32_t sample_rate);

    /// @brief Returns the number of bits per audio sample
    /// @return Bits per sample (e.g., 16).
    uint8_t get_bits_per_sample() const {
        return this->bits_per_sample_;
    }
    /// @brief Returns the number of audio channels
    /// @return Channel count (e.g., 1 for mono, 2 for stereo).
    uint8_t get_channels() const {
        return this->channels_;
    }
    /// @brief Returns the audio sample rate
    /// @return Sample rate in Hz (e.g., 44100).
    uint32_t get_sample_rate() const {
        return this->sample_rate_;
    }

    /// @brief Converts a byte count to milliseconds of audio
    /// @param bytes Number of bytes of PCM data.
    /// @return Duration in milliseconds.
    uint32_t bytes_to_ms(size_t bytes) const {
        return bytes * MS_PER_SECOND /
               (this->sample_rate_ * this->bytes_per_sample_ * this->channels_);
    }

    /// @brief Converts a byte count to the number of audio frames
    /// @param bytes Number of bytes of PCM data.
    /// @return Number of frames.
    uint32_t bytes_to_frames(size_t bytes) const {
        return bytes / (this->bytes_per_sample_ * this->channels_);
    }

    /// @brief Converts a frame count to bytes
    /// @param frames Number of audio frames.
    /// @return Number of bytes.
    size_t frames_to_bytes(uint32_t frames) const {
        return frames * this->bytes_per_sample_ * this->channels_;
    }

    /// @brief Converts a sample count to bytes
    /// @param samples Number of audio samples (across all channels).
    /// @return Number of bytes.
    size_t samples_to_bytes(uint32_t samples) const {
        return samples * this->bytes_per_sample_;
    }

    /// @brief Converts milliseconds to bytes of PCM data
    /// @param ms Duration in milliseconds.
    /// @return Number of bytes.
    size_t ms_to_bytes(uint32_t ms) const {
        return (ms * this->bytes_per_sample_ * this->channels_ * this->sample_rate_) /
               MS_PER_SECOND;
    }

    /// @brief Converts a frame count to microseconds
    /// @param frames Number of audio frames.
    /// @return Duration in microseconds.
    /// @note The intermediate frames * US_PER_SECOND product is computed in 64-bit, so this is
    ///       avoids overflow for any reasonable frame count. The result is returned as int64_t
    ///       to match the playtime accumulators that consume it.
    int64_t frames_to_microseconds(uint32_t frames) const;

    /// @brief Returns true if both AudioStreamInfo objects describe the same format
    /// @param rhs The other AudioStreamInfo to compare against.
    /// @return true if bits_per_sample, channels, and sample_rate all match.
    bool operator==(const AudioStreamInfo& rhs) const;

    /// @brief Returns true if both AudioStreamInfo objects describe different formats
    /// @param rhs The other AudioStreamInfo to compare against.
    /// @return true if any of bits_per_sample, channels, or sample_rate differ.
    bool operator!=(const AudioStreamInfo& rhs) const {
        return !operator==(rhs);
    }

protected:
    // size_t fields
    size_t bytes_per_sample_;

    // 32-bit fields
    uint32_t sample_rate_;

    // 8-bit fields
    uint8_t bits_per_sample_;
    uint8_t channels_;
};

}  // namespace sendspin
