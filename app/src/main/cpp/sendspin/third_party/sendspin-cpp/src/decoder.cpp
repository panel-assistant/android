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

#include "decoder.h"

#include "opus_state_location.h"
#include "platform/logging.h"

#include <algorithm>
#include <cstring>
#include <memory>

namespace sendspin {

static const char* const TAG = "sendspin.decoder";

void SendspinDecoder::reset_decoders() {
    if (this->flac_decoder_ != nullptr) {
        this->flac_decoder_->reset();
        this->flac_decoder_.reset();
    }

#ifdef SENDSPIN_ENABLE_OPUS
    this->opus_decoder_buf_.reset();
#endif

    this->current_codec_ = SendspinCodecFormat::UNSUPPORTED;
}

bool SendspinDecoder::process_header(const uint8_t* data, size_t data_size, ChunkType chunk_type,
                                     AudioStreamInfo* stream_info) {
    if (data == nullptr || stream_info == nullptr) {
        SS_LOGE(TAG, "Null pointer passed to process_header");
        return false;
    }

    switch (chunk_type) {
        case CHUNK_TYPE_FLAC_HEADER: {
            this->flac_decoder_ = std::make_unique<micro_flac::FLACDecoder>();
            this->flac_decoder_->set_crc_check_enabled(
                false);  // Disable CRC check for small speed up

            size_t bytes_consumed = 0;
            size_t samples_decoded = 0;
            auto result =
                this->flac_decoder_->decode(data, data_size, static_cast<uint8_t*>(nullptr), 0,
                                            bytes_consumed, samples_decoded);

            if (result == micro_flac::FLAC_DECODER_NEED_MORE_DATA) {
                SS_LOGW(TAG, "Need more data to decode FLAC header");
                return false;
            }

            if (result != micro_flac::FLAC_DECODER_HEADER_READY) {
                SS_LOGE(TAG, "Serious error decoding FLAC header");
                return false;
            }

            const auto& info = this->flac_decoder_->get_stream_info();
            this->current_codec_ = SendspinCodecFormat::FLAC;
            this->current_stream_info_ =
                AudioStreamInfo(static_cast<uint8_t>(info.bits_per_sample()),
                                static_cast<uint8_t>(info.num_channels()), info.sample_rate());
            *stream_info = this->current_stream_info_;
            this->decode_buffer_size_ = static_cast<size_t>(info.max_block_size()) *
                                        static_cast<size_t>(info.num_channels()) *
                                        static_cast<size_t>(info.bytes_per_sample());
            break;
        }
#ifdef SENDSPIN_ENABLE_OPUS
        case CHUNK_TYPE_OPUS_DUMMY_HEADER: {
            if (!this->decode_dummy_header(data, data_size, stream_info)) {
                return false;
            }

            size_t opus_size = opus_decoder_get_size(stream_info->get_channels());
            if (!this->opus_decoder_buf_.allocate(opus_size, OPUS_STATE_LOCATION)) {
                SS_LOGE(TAG, "Failed to allocate %zu bytes for OPUS decoder", opus_size);
                return false;
            }

            auto decoder_error =
                opus_decoder_init(this->opus_decoder_buf_.as<OpusDecoder>(),
                                  stream_info->get_sample_rate(), stream_info->get_channels());

            if (decoder_error != OPUS_OK) {
                SS_LOGE(TAG, "Failed to create OPUS decoder, error %d", decoder_error);
                this->opus_decoder_buf_.reset();
                return false;
            }

            // Opus packets are almost always a single 20ms frame, so size the decode buffer for
            // that. decode_audio_chunk() raises this estimate (up to the 120ms spec maximum) the
            // first time it sees a larger packet, signalling the caller to grow its buffer.
            static constexpr uint32_t OPUS_TYPICAL_FRAME_MS = 20U;
            this->decode_buffer_size_ = stream_info->ms_to_bytes(OPUS_TYPICAL_FRAME_MS);
            this->current_stream_info_ = *stream_info;
            this->current_codec_ = SendspinCodecFormat::OPUS;
            break;
        }
#endif
        case CHUNK_TYPE_PCM_DUMMY_HEADER: {
            if (!this->decode_dummy_header(data, data_size, stream_info)) {
                return false;
            }
            this->current_stream_info_ = *stream_info;
            this->current_codec_ = SendspinCodecFormat::PCM;
            // Room for any valid chunk, so one call decodes it.
            this->decode_buffer_size_ = stream_info->ms_to_bytes(MAX_AUDIO_CHUNK_MS);
            break;
        }
        default: {
            SS_LOGE(TAG, "Audio chunk isn't a codec header");
            return false;
        }
    }

    return true;
}

bool SendspinDecoder::decode_audio_chunk(const uint8_t* data, size_t data_size,
                                         uint8_t* output_buffer, size_t output_buffer_size,
                                         size_t* consumed, size_t* decoded_size) {
    if (data == nullptr || data_size == 0 || output_buffer == nullptr || consumed == nullptr ||
        decoded_size == nullptr) {
        SS_LOGE(TAG, "Invalid data passed to decode_audio_chunk");
        return false;
    }
    *consumed = 0;
    *decoded_size = 0;

    if (this->current_codec_ == SendspinCodecFormat::PCM) {
        // roles/player/v1.md "Codec framing": a PCM chunk is a whole number of frames.
        const size_t frame_bytes = this->current_stream_info_.frames_to_bytes(1);
        if (frame_bytes == 0 || data_size % frame_bytes != 0) {
            SS_LOGE(TAG, "PCM chunk of %zu bytes is not whole frames", data_size);
            return false;
        }
        size_t copy = std::min(data_size, output_buffer_size);
        copy -= copy % frame_bytes;
        std::memcpy(output_buffer, data, copy);
        *consumed = copy;
        *decoded_size = copy;
    } else if ((this->flac_decoder_ != nullptr) &&
               (this->current_codec_ == SendspinCodecFormat::FLAC)) {
        // roles/player/v1.md "Codec framing": a FLAC chunk carries one or more complete frames,
        // and micro-flac decodes one per call.
        while (*consumed < data_size) {
            size_t bytes_consumed = 0;
            size_t samples_decoded = 0;
            auto result = this->flac_decoder_->decode(
                data + *consumed, data_size - *consumed, output_buffer + *decoded_size,
                output_buffer_size - *decoded_size, bytes_consumed, samples_decoded);

            if (result == micro_flac::FLAC_DECODER_ERROR_OUTPUT_TOO_SMALL) {
                break;  // No room for another frame; the caller grows the buffer and resumes.
            }
            if (result == micro_flac::FLAC_DECODER_NEED_MORE_DATA) {
                SS_LOGE(TAG, "FLAC chunk ends mid-frame");
                return false;
            }
            if (result != micro_flac::FLAC_DECODER_SUCCESS || bytes_consumed == 0) {
                SS_LOGE(TAG, "Error decoding FLAC frame: %d", static_cast<int>(result));
                return false;
            }
            *consumed += bytes_consumed;
            *decoded_size += this->current_stream_info_.samples_to_bytes(samples_decoded);
        }
#ifdef SENDSPIN_ENABLE_OPUS
    } else if (this->opus_decoder_buf_ && (this->current_codec_ == SendspinCodecFormat::OPUS)) {
        int output_frames = opus_decode(
            this->opus_decoder_buf_.as<OpusDecoder>(), data, data_size, (int16_t*)output_buffer,
            this->current_stream_info_.bytes_to_frames(output_buffer_size), 0);
        if (output_frames == OPUS_BUFFER_TOO_SMALL) {
            // The buffer is sized for a typical 20ms frame. Raise the estimate to the Opus
            // maximum (120ms) and consume nothing, so the caller grows the buffer and resumes.
            static constexpr uint32_t OPUS_MAX_FRAME_MS = 120U;
            this->decode_buffer_size_ = this->current_stream_info_.ms_to_bytes(OPUS_MAX_FRAME_MS);
            SS_LOGD(TAG, "Opus packet exceeds decode buffer; raising estimate to %zu bytes",
                    this->decode_buffer_size_);
            return true;
        }
        if (output_frames < 0) {
            SS_LOGE(TAG, "Error decoding opus chunk: %d", output_frames);
            return false;
        }
        *consumed = data_size;
        *decoded_size = this->current_stream_info_.frames_to_bytes(output_frames);
#endif
    } else {
        return false;
    }

    return true;
}

bool SendspinDecoder::decode_dummy_header(const uint8_t* data, size_t data_size,
                                          AudioStreamInfo* stream_info) {
    if (data_size < sizeof(DummyHeader)) {
        SS_LOGE(TAG, "Invalid dummy codec header: size %zu < %zu", data_size, sizeof(DummyHeader));
        return false;
    }

    // Copy into local struct to avoid alignment issues
    DummyHeader header{};
    std::memcpy(&header, data, sizeof(DummyHeader));
    this->current_stream_info_ =
        AudioStreamInfo(header.bits_per_sample, header.channels, header.sample_rate);
    *stream_info = this->current_stream_info_;
    return true;
}

}  // namespace sendspin
