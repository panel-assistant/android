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

/// @file source_encoder.h
/// @brief Turns an assembled chunk of capture PCM into the payload of a source audio chunk

#pragma once

#include <cstddef>
#include <cstdint>
#include <cstring>

namespace sendspin {

/// @brief Codec seam that keeps the source task's chunk loop codec-agnostic
class SourceEncoder {
public:
    virtual ~SourceEncoder() = default;

    /// @brief Encodes one chunk of interleaved PCM into the payload area
    /// @param in Assembled PCM chunk: input_buffer(), or `out` itself (exact aliasing) for an
    ///        encoder without one.
    /// @return Payload bytes written to `out`; 0 on failure (the task drops the chunk).
    virtual size_t encode(const uint8_t* in, size_t in_len, uint8_t* out, size_t out_capacity) = 0;

    /// @brief Encoder delay (us), subtracted from each chunk's capture time so the stamp names the
    /// first sample the decoder emits (roles/source/v1.md "Source Audio Chunks (Binary)")
    virtual int64_t lookahead_us() const = 0;

    /// @brief Resets the encoder state before a new stream
    virtual void reset() = 0;

    /// @brief Where the task assembles each chunk's PCM: the encoder's own one-chunk buffer, or
    /// nullptr for the outbound item's payload area. An encoder that needs aligned samples or
    /// cannot write its payload over its input provides one: the payload area sits at an odd
    /// offset, behind the 9-byte chunk header.
    virtual uint8_t* input_buffer() {
        return nullptr;
    }

    /// @brief Prepares the calling thread for encode() so its first chunk allocates nothing.
    /// Leaves encoder state behind (reset() before streaming) and may overwrite input_buffer(),
    /// so it is never called with a chunk partly assembled.
    virtual void warm_up() {}
};

/// @brief Encoder for pcm streams: the assembled chunk already is the payload
class PcmPassthroughEncoder final : public SourceEncoder {
public:
    size_t encode(const uint8_t* in, size_t in_len, uint8_t* out, size_t out_capacity) override {
        if (in_len > out_capacity) {
            return 0;
        }
        if (in != out) {
            std::memcpy(out, in, in_len);
        }
        return in_len;
    }

    int64_t lookahead_us() const override {
        return 0;
    }

    void reset() override {}
};

}  // namespace sendspin
