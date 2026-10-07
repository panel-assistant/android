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

/// @file source_encoder_opus.h
/// @brief Opus implementation of the source encoder

#pragma once

#include "platform/memory.h"
#include "sendspin/config.h"
#include "source_encoder.h"

namespace sendspin {

/**
 * @brief Encodes each assembled PCM chunk into exactly one RFC 6716 Opus packet, with no
 * container (roles/source/v1.md "client-stream/start", codec framing), in CELT-only mode. Config
 * validation makes a full chunk one legal Opus frame, so encode() is a single opus_encode()
 * call.
 */
class OpusSourceEncoder final : public SourceEncoder {
public:
    /// @brief Upper bound on any packet encode() produces: libopus's recommended max_data_bytes,
    /// above any accepted config's worst case (512 kbit/s x 60 ms = 3840 bytes)
    static constexpr size_t MAX_PACKET_BYTES = 4000U;

    /// @brief Allocates and initializes the libopus encoder state and the PCM scratch buffer
    /// @param config A config validated for OPUS.
    /// @return false on an allocation or libopus failure (logged).
    bool init(const SourceRoleConfig& config);

    size_t encode(const uint8_t* in, size_t in_len, uint8_t* out, size_t out_capacity) override;

    int64_t lookahead_us() const override {
        return this->lookahead_us_;
    }

    void reset() override;

    uint8_t* input_buffer() override {
        return this->pcm_scratch_.data();
    }

    /// @brief Encodes one silent frame and discards it, so micro-opus allocates the calling
    /// thread's pseudostack here rather than in the first chunk's encode
    void warm_up() override;

private:
    // Struct fields
    /// libopus encoder state, sized by opus_encoder_get_size() and placed by OPUS_STATE_LOCATION
    PlatformBuffer encoder_state_;
    /// The chunk under encode, aligned for libopus's int16 input; also input_buffer()
    PlatformBuffer pcm_scratch_;

    // 64-bit fields
    /// Encoder delay in us, from OPUS_GET_LOOKAHEAD at init
    int64_t lookahead_us_{0};

    // size_t fields
    size_t bytes_per_frame_{0};

    // 8-bit fields
    /// Set by a failed encode and cleared by the next success; keeps the failure log to one line
    bool failing_{false};
};

}  // namespace sendspin
