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

/// @file decoder.h
/// @brief Audio decoder wrapper supporting FLAC, raw PCM, and (with SENDSPIN_ENABLE_OPUS) Opus

#pragma once

#include "audio_stream_info.h"
#include "audio_types.h"
#include "platform/memory.h"
#include "sendspin/player_role.h"  // For SendspinCodecFormat
#include <micro_flac/flac_decoder.h>
#ifdef SENDSPIN_ENABLE_OPUS
#include <opus.h>
#endif

#include <memory>

namespace sendspin {

/// @brief Longest audio chunk a server may send (roles/player/v1.md "Server Audio Send
/// Constraints").
static constexpr uint32_t MAX_AUDIO_CHUNK_MS = 150U;

/**
 * @brief Audio decoder wrapper supporting FLAC, raw PCM, and (with SENDSPIN_ENABLE_OPUS) Opus
 *
 * Manages codec state for a single active stream. The caller first passes a header chunk
 * via process_header() to initialize the decoder and populate an AudioStreamInfo, then
 * calls decode_audio_chunk() for each subsequent encoded chunk. FLAC uses micro_flac. Opus
 * uses libopus and is only compiled in with SENDSPIN_ENABLE_OPUS; an Opus header is rejected
 * otherwise. PCM chunks bypass decoding and are copied directly.
 *
 * Usage:
 * 1. Call process_header() with the first chunk to initialize the codec and stream info
 * 2. Allocate an output buffer of at least get_decode_buffer_size() bytes
 * 3. Call decode_audio_chunk() for each encoded chunk. While it consumes less than the whole
 *    chunk, keep what it decoded, make at least get_decode_buffer_size() bytes free, and call
 *    again with the rest.
 * 4. Call reset_decoders() when the stream ends or a new stream starts
 *
 * @code
 * SendspinDecoder decoder;
 * AudioStreamInfo stream_info;
 *
 * decoder.process_header(header_data, header_size, CHUNK_TYPE_FLAC_HEADER, &stream_info);
 *
 * std::vector<uint8_t> output(decoder.get_decode_buffer_size());
 * size_t consumed = 0;
 * size_t decoded_size = 0;
 * decoder.decode_audio_chunk(encoded_data, encoded_size, output.data(), output.size(), &consumed,
 *                            &decoded_size);
 * @endcode
 */
class SendspinDecoder {
public:
    ~SendspinDecoder() {
        this->reset_decoders();
    }

    /// @brief Resets the state of the FLAC and Opus decoders
    void reset_decoders();

    /// @brief Sets up the appropriate decoder and processes the codec header (which may be a dummy
    /// header)
    /// @param data Pointer to the header data.
    /// @param data_size Size of the header data in bytes.
    /// @param chunk_type Type of header chunk.
    /// @param[out] stream_info Pointer to AudioStreamInfo that will be filled out when decoding the
    /// header.
    /// @return True if successful, false otherwise.
    bool process_header(const uint8_t* data, size_t data_size, ChunkType chunk_type,
                        AudioStreamInfo* stream_info);

    /// @brief Decodes an encoded audio chunk into a caller-provided buffer.
    /// @param data Pointer to the encoded audio data.
    /// @param data_size Size of the encoded audio data in bytes.
    /// @param output_buffer Pointer to the buffer where decoded audio will be written.
    /// @param output_buffer_size Size of the output buffer in bytes.
    /// @param[out] consumed Encoded bytes decoded. Less than data_size means the output ran out
    ///             of room: make get_decode_buffer_size() bytes free and call again with the rest.
    /// @param[out] decoded_size Decoded bytes written.
    /// @return False on a decode error.
    bool decode_audio_chunk(const uint8_t* data, size_t data_size, uint8_t* output_buffer,
                            size_t output_buffer_size, size_t* consumed, size_t* decoded_size);

    /// @brief Returns the currently active codec format.
    /// @return The codec format in use for decoding.
    SendspinCodecFormat get_current_codec() const {
        return this->current_codec_;
    }

    /// @brief Returns the size to allocate for the decoded-output buffer.
    /// @details The free space decode_audio_chunk() needs to make progress: one maximum-size FLAC
    /// frame, MAX_AUDIO_CHUNK_MS of PCM, or one Opus packet (20 ms, raised to 120 ms on a larger
    /// packet).
    /// @return Required decoded-output buffer size in bytes.
    size_t get_decode_buffer_size() const {
        return this->decode_buffer_size_;
    }

    // ========================================
    // Internal helpers
    // ========================================
protected:
    /// @brief Parses a dummy (non-FLAC) codec header to extract stream parameters.
    /// @param data Pointer to the header data.
    /// @param data_size Size of the header data in bytes.
    /// @param[out] stream_info Populated with stream parameters on success.
    /// @return True if the header was valid and stream_info was populated, false otherwise.
    bool decode_dummy_header(const uint8_t* data, size_t data_size, AudioStreamInfo* stream_info);

    // Struct fields
    AudioStreamInfo current_stream_info_;
#ifdef SENDSPIN_ENABLE_OPUS
    PlatformBuffer opus_decoder_buf_;
#endif

    // Pointer fields
    std::unique_ptr<micro_flac::FLACDecoder> flac_decoder_;

    // size_t fields
    size_t decode_buffer_size_{0};

    // 32-bit fields
    SendspinCodecFormat current_codec_ = SendspinCodecFormat::UNSUPPORTED;
};

}  // namespace sendspin
