// Copyright 2026 Kevin Ahrendt
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

#include "micro_flac/flac_decoder.h"

#include "alloc.h"
#include "compiler.h"
#include "crc.h"
#include "decorrelation.h"
#include "frame_header.h"
#include "lpc.h"
#include "pcm_packing.h"
#ifndef MICRO_FLAC_DISABLE_OGG
#include "micro_ogg/ogg_demuxer.h"
#endif

#include <algorithm>
#include <cassert>
#include <cstring>
#include <type_traits>

namespace micro_flac {

static constexpr uint8_t MAGIC_BYTES[] = {'f', 'L', 'a', 'C'};

// FLAC subframe type ranges (RFC 9639 Section 9.2.1)
static constexpr uint8_t SUBFRAME_FIXED_MAX = 12;
static constexpr uint8_t SUBFRAME_LPC_MIN = 32;
static constexpr uint8_t SUBFRAME_LPC_MAX = 63;

// Fixed prediction coefficients for orders 0-4
// Order 0: no coefficients
// Order 1: [1]
// Order 2: [-1, 2]
// Order 3: [1, -3, 3]
// Order 4: [-1, 4, -6, 4]
static constexpr int32_t FIXED_COEFFICIENTS_1[] = {1};
static constexpr int32_t FIXED_COEFFICIENTS_2[] = {-1, 2};
static constexpr int32_t FIXED_COEFFICIENTS_3[] = {1, -3, 3};
static constexpr int32_t FIXED_COEFFICIENTS_4[] = {-1, 4, -6, 4};

// Index 0 is nullptr (order 0 has no coefficients; the LPC loop iterates 0 times)
static constexpr const int32_t* FIXED_COEFFICIENTS[] = {nullptr, FIXED_COEFFICIENTS_1,
                                                        FIXED_COEFFICIENTS_2, FIXED_COEFFICIENTS_3,
                                                        FIXED_COEFFICIENTS_4};

// Generate a bitmask with num_bits set to 1 (e.g., num_bits=3 -> 0b111 = 7)
// This replaces the UINT_MASK lookup table with bit manipulation for better performance
static FLAC_ALWAYS_INLINE uint32_t uint_mask(uint32_t num_bits) {
    return (num_bits >= 32) ? UINT32_MAX : ((1U << num_bits) - 1);
}

// Mask for bit buffer width (used to mask shift amounts)
static constexpr uint32_t BIT_BUFFER_SHIFT_MASK = BIT_BUFFER_BITS - 1;

// ============================================================================
// FLACMetadataBlock Lifecycle
// ============================================================================

FLACMetadataBlock::~FLACMetadataBlock() {
    if (this->data) {
        FLAC_FREE(this->data);
    }
}

FLACMetadataBlock::FLACMetadataBlock(FLACMetadataBlock&& other) noexcept
    : type(other.type), length(other.length), data(other.data) {
    other.type = FLAC_METADATA_TYPE_INVALID;
    other.data = nullptr;
    other.length = 0;
}

FLACMetadataBlock& FLACMetadataBlock::operator=(FLACMetadataBlock&& other) noexcept {
    if (this != &other) {
        if (this->data) {
            FLAC_FREE(this->data);
        }
        this->type = other.type;
        this->length = other.length;
        this->data = other.data;
        other.data = nullptr;
        other.length = 0;
    }
    return *this;
}

// ============================================================================
// HeaderParseState Lifecycle
// ============================================================================

FLACDecoder::HeaderParseState::~HeaderParseState() {
    if (this->data) {
        FLAC_FREE(this->data);
    }
}

FLACDecoder::HeaderParseState::HeaderParseState(HeaderParseState&& other) noexcept
    : data(other.data),
      data_capacity(other.data_capacity),
      type(other.type),
      length(other.length),
      bytes_read(other.bytes_read),
      block_header_len(other.block_header_len),
      in_progress(other.in_progress),
      last_block(other.last_block) {
    std::memcpy(this->block_header_buf, other.block_header_buf, sizeof(this->block_header_buf));
    other.data = nullptr;
    other.data_capacity = 0;
}

FLACDecoder::HeaderParseState& FLACDecoder::HeaderParseState::operator=(
    HeaderParseState&& other) noexcept {
    if (this != &other) {
        if (this->data) {
            FLAC_FREE(this->data);
        }
        this->data = other.data;
        this->data_capacity = other.data_capacity;
        this->type = other.type;
        this->length = other.length;
        this->bytes_read = other.bytes_read;
        std::memcpy(this->block_header_buf, other.block_header_buf, sizeof(this->block_header_buf));
        this->block_header_len = other.block_header_len;
        this->in_progress = other.in_progress;
        this->last_block = other.last_block;
        other.data = nullptr;
        other.data_capacity = 0;
    }
    return *this;
}

// ============================================================================
// Lifecycle
// ============================================================================

FLACDecoder::FLACDecoder() = default;

FLACDecoder::~FLACDecoder() {
    this->free_buffers();
}

void FLACDecoder::reset() {
    // Free dynamic allocations
    this->free_buffers();

    // Reset container detection and Ogg state
    this->container_type_ = ContainerType::UNKNOWN;
    this->decode_phase_ = DecodePhase::DETECT_CONTAINER;
    this->latched_error_ = FLAC_DECODER_SUCCESS;
    this->detect_buffer_len_ = 0;
    this->detect_buffer_fed_ = false;
    std::memset(this->detect_buffer_, 0, sizeof(this->detect_buffer_));
#ifndef MICRO_FLAC_DISABLE_OGG
    this->ogg_bos_processed_ = false;
    this->ogg_bos_prefix_consumed_ = 0;
    this->ogg_eos_seen_ = false;
#endif

    // Reset stream info (new stream may have different parameters)
    this->stream_info_ = FLACStreamInfo{};

    // Reset current frame properties
    this->curr_frame_block_size_ = 0;
    this->curr_frame_channel_assign_ = 0;
    this->curr_frame_bits_per_sample_ = 0;

    // Reset input buffer state (stale pointers from the previous stream are not valid)
    this->buffer_ = nullptr;
    this->buffer_index_ = 0;
    this->bytes_left_ = 0;

    // Reset bit buffer
    this->bit_buffer_ = 0;
    this->bit_buffer_length_ = 0;

    // Reset decoder flags
    this->out_of_data_ = false;

    // Reset all streaming decode state machines
    this->frame_ = FrameState{};
    this->subframe_ = SubframeState{};
    this->lpc_ = LpcState{};
    this->residual_ = ResidualState{};
    this->rice_ = RiceState{};
    this->header_parse_ = HeaderParseState{};

    // NOTE: enable_crc_check_ and max_metadata_sizes_ are intentionally NOT reset here;
    // they represent user configuration that should persist across resets.
}

// ============================================================================
// Core Decoding API
// ============================================================================

FLACDecoderResult FLACDecoder::decode(const uint8_t* input, size_t input_len, int32_t* output,
                                      size_t output_size_samples, size_t& bytes_consumed,
                                      size_t& samples_decoded) {
    return this->decode_impl(input, input_len, reinterpret_cast<uint8_t*>(output),
                             output_size_samples * sizeof(int32_t), bytes_consumed, samples_decoded,
                             true);
}

FLACDecoderResult FLACDecoder::decode(const uint8_t* input, size_t input_len, uint8_t* output,
                                      size_t output_size_bytes, size_t& bytes_consumed,
                                      size_t& samples_decoded) {
    return this->decode_impl(input, input_len, output, output_size_bytes, bytes_consumed,
                             samples_decoded, false);
}

FLACDecoderResult FLACDecoder::decode_impl(const uint8_t* input, size_t input_len, uint8_t* output,
                                           size_t output_size, size_t& bytes_consumed,
                                           size_t& samples_decoded, bool output_32bit) {
    bytes_consumed = 0;
    samples_decoded = 0;

    if (this->decode_phase_ == DecodePhase::DONE) {
        return FLAC_DECODER_END_OF_STREAM;
    }

    if (this->decode_phase_ == DecodePhase::ERROR) {
        return this->latched_error_;
    }

    // Phase 1: Detect container format from first 4 bytes
    if (this->decode_phase_ == DecodePhase::DETECT_CONTAINER) {
        while (this->detect_buffer_len_ < 4 && bytes_consumed < input_len) {
            this->detect_buffer_[this->detect_buffer_len_++] = input[bytes_consumed++];
        }

        if (this->detect_buffer_len_ < 4) {
            return FLAC_DECODER_NEED_MORE_DATA;
        }

        // Check for "OggS" or "fLaC"
        if (this->detect_buffer_[0] == 'O' && this->detect_buffer_[1] == 'g' &&
            this->detect_buffer_[2] == 'g' && this->detect_buffer_[3] == 'S') {
#ifndef MICRO_FLAC_DISABLE_OGG
            this->container_type_ = ContainerType::OGG_FLAC;
            // Streaming mode: only a 282-byte header staging buffer is needed
            this->ogg_demuxer_ = std::make_unique<micro_ogg::OggDemuxer>();
#else
            return this->set_fatal_error(FLAC_DECODER_ERROR_INPUT_INVALID);
#endif
        } else if (this->detect_buffer_[0] == 'f' && this->detect_buffer_[1] == 'L' &&
                   this->detect_buffer_[2] == 'a' && this->detect_buffer_[3] == 'C') {
            this->container_type_ = ContainerType::NATIVE_FLAC;
        } else {
            return this->set_fatal_error(FLAC_DECODER_ERROR_INPUT_INVALID);
        }

        this->decode_phase_ = DecodePhase::HEADER;
        // Fall through: remaining bytes after detection go to header parsing
    }

    // Delegate to container-specific handler
    const uint8_t* remaining_input = input + bytes_consumed;
    size_t remaining_len = input_len - bytes_consumed;
    size_t delegate_consumed = 0;

    FLACDecoderResult result = FLAC_DECODER_ERROR_INTERNAL;
#ifndef MICRO_FLAC_DISABLE_OGG
    if (this->container_type_ == ContainerType::OGG_FLAC) {
        result = this->decode_ogg(remaining_input, remaining_len, output, output_size,
                                  delegate_consumed, samples_decoded, output_32bit);
    } else
#endif
    {
        result = this->decode_native(remaining_input, remaining_len, output, output_size,
                                     delegate_consumed, samples_decoded, output_32bit);
    }
    bytes_consumed += delegate_consumed;
    return result;
}

// ============================================================================
// Configuration Methods
// ============================================================================

void FLACDecoder::set_max_metadata_size(FLACMetadataType type, uint32_t max_size) {
    // Use array indexing for faster access (types 0-6 map directly, others use index 7)
    size_t index = (static_cast<size_t>(type) < METADATA_SIZE_LIMITS_COUNT - 1)
                       ? static_cast<size_t>(type)
                       : METADATA_SIZE_LIMITS_COUNT - 1;
    this->max_metadata_sizes_[index] = max_size;
}

uint32_t FLACDecoder::get_max_metadata_size(FLACMetadataType type) const {
    // Use array indexing for faster access (types 0-6 map directly, others use index 7)
    size_t index = (static_cast<size_t>(type) < METADATA_SIZE_LIMITS_COUNT - 1)
                       ? static_cast<size_t>(type)
                       : METADATA_SIZE_LIMITS_COUNT - 1;
    return this->max_metadata_sizes_[index];
}

// ============================================================================
// Decode Pipeline
// ============================================================================

FLACDecoderResult FLACDecoder::decode_native(const uint8_t* input, size_t input_len,
                                             uint8_t* output, size_t output_size,
                                             size_t& bytes_consumed, size_t& samples_decoded,
                                             bool output_32bit) {
    bytes_consumed = 0;
    samples_decoded = 0;

    if (this->decode_phase_ == DecodePhase::HEADER) {
        // First call for native FLAC: feed detect_buffer_ (4 "fLaC" magic bytes) to read_header.
        // For Ogg FLAC, detect_buffer_len_ is 0 (magic comes from packet body), so skip this.
        // read_header accumulates STREAMINFO bytes incrementally, so no pre-buffering needed.
        if (!this->detect_buffer_fed_) {
            this->detect_buffer_fed_ = true;

            if (this->detect_buffer_len_ > 0) {
                size_t header_consumed = 0;
                auto result = this->read_header(this->detect_buffer_, this->detect_buffer_len_,
                                                header_consumed);

                if (result == FLAC_DECODER_SUCCESS) {
                    this->decode_phase_ = DecodePhase::AUDIO;
                    return FLAC_DECODER_HEADER_READY;
                }
                if (result < 0) {
                    return this->set_fatal_error(result);
                }
                // NEED_MORE_DATA: fall through to process user input
                if (bytes_consumed >= input_len) {
                    return FLAC_DECODER_NEED_MORE_DATA;
                }
            }
        }

        // Feed remaining input to read_header for additional metadata blocks
        size_t header_consumed = 0;
        auto result =
            this->read_header(input + bytes_consumed, input_len - bytes_consumed, header_consumed);
        bytes_consumed += header_consumed;

        if (result == FLAC_DECODER_NEED_MORE_DATA) {
            return FLAC_DECODER_NEED_MORE_DATA;
        }
        if (result == FLAC_DECODER_SUCCESS) {
            this->decode_phase_ = DecodePhase::AUDIO;
            return FLAC_DECODER_HEADER_READY;
        }
        // Header parse error: transition to ERROR so subsequent calls return the
        // same error. The caller must use reset() before retrying.
        return this->set_fatal_error(result);
    }

    if (this->decode_phase_ == DecodePhase::AUDIO) {
        if (!output || output_size < this->get_output_buffer_size_bytes(output_32bit)) {
            return FLAC_DECODER_ERROR_OUTPUT_TOO_SMALL;
        }
        uint32_t num_samples = 0;
        auto result = this->decode_frame(input, input_len, output, &num_samples, output_32bit);
        bytes_consumed = this->get_bytes_index();

        if (result == FLAC_DECODER_NEED_MORE_DATA) {
            bytes_consumed = input_len;  // decode_frame consumed all input
            return FLAC_DECODER_NEED_MORE_DATA;
        }
        if (result == FLAC_DECODER_SUCCESS) {
            samples_decoded = num_samples;
            return FLAC_DECODER_SUCCESS;
        }
        if (result == FLAC_DECODER_END_OF_STREAM) {
            this->decode_phase_ = DecodePhase::DONE;
            return FLAC_DECODER_END_OF_STREAM;
        }
        return result;  // Error
    }

    return FLAC_DECODER_END_OF_STREAM;
}

#ifndef MICRO_FLAC_DISABLE_OGG
FLACDecoderResult FLACDecoder::decode_ogg(const uint8_t* input, size_t input_len, uint8_t* output,
                                          size_t output_size, size_t& bytes_consumed,
                                          size_t& samples_decoded, bool output_32bit) {
    bytes_consumed = 0;
    samples_decoded = 0;

    // Step 1: Feed initial "OggS" detect_buffer to demuxer (once)
    if (this->ogg_bos_prefix_consumed_ == 0 && !this->ogg_bos_processed_) {
        auto state =
            this->ogg_demuxer_->get_next_data(this->detect_buffer_, this->detect_buffer_len_);
        this->detect_buffer_len_ = 0;
        // detect_buffer_fed_ stays false for decode_native reuse

        if (state.result != micro_ogg::OGG_NEED_MORE_DATA && state.result != micro_ogg::OGG_OK) {
            return this->set_fatal_error(FLAC_DECODER_ERROR_OGG_DEMUX);
        }
    }

    // Expected BOS prefix: 0x7F 'F' 'L' 'A' 'C' 0x01 <minor> <num_header_packets(2)>
    static constexpr uint8_t BOS_PREFIX[] = {0x7F, 'F', 'L', 'A', 'C', 0x01};
    static constexpr uint8_t BOS_PREFIX_LEN = 9;

    // This loop is bounded by input_len: each iteration either advances bytes_consumed
    // (via demuxer consumption) or returns NEED_MORE_DATA when bytes_consumed >= input_len.
    // Malformed files with many tiny Ogg pages may cause many iterations per call, but
    // the count is proportional to input size.
    while (true) {
        size_t remaining = input_len - bytes_consumed;
        auto state = this->ogg_demuxer_->get_next_data(input + bytes_consumed, remaining);
        bytes_consumed += state.bytes_consumed;

        if (state.result == micro_ogg::OGG_NEED_MORE_DATA) {
            if (this->ogg_eos_seen_) {
                return FLAC_DECODER_END_OF_STREAM;
            }
            if (bytes_consumed < input_len) {
                continue;  // More input available; e.g., next page header after page finalization
            }
            return FLAC_DECODER_NEED_MORE_DATA;
        }

        if (state.result != micro_ogg::OGG_OK) {
            return this->set_fatal_error(FLAC_DECODER_ERROR_OGG_DEMUX);
        }

        if (state.packet.is_eos && state.packet.is_last_on_page) {
            this->ogg_eos_seen_ = true;
        }

        const uint8_t* body = state.packet.data;
        size_t body_len = state.packet.length;

        if (body_len == 0) {
            if (bytes_consumed < input_len) {
                continue;
            }
            return FLAC_DECODER_NEED_MORE_DATA;
        }

        // Step 2: Strip 9-byte BOS prefix byte-by-byte (streaming safe)
        if (!this->ogg_bos_processed_) {
            while (this->ogg_bos_prefix_consumed_ < BOS_PREFIX_LEN && body_len > 0) {
                uint8_t idx = this->ogg_bos_prefix_consumed_;
                if (idx < 6 && body[0] != BOS_PREFIX[idx]) {
                    return this->set_fatal_error(FLAC_DECODER_ERROR_OGG_BAD_HEADER);
                }
                this->ogg_bos_prefix_consumed_++;
                body++;
                body_len--;
            }

            if (this->ogg_bos_prefix_consumed_ < BOS_PREFIX_LEN) {
                continue;
            }
            this->ogg_bos_processed_ = true;

            if (body_len == 0) {
                continue;
            }
        }

        // Step 3: Delegate body bytes to decode_native for header/frame decoding
        size_t native_consumed = 0;
        size_t native_samples = 0;
        auto result = this->decode_native(body, body_len, output, output_size, native_consumed,
                                          native_samples, output_32bit);
        samples_decoded = native_samples;

        // The demuxer assumes all offered data is consumed (it auto-advances).
        // Each Ogg FLAC packet contains exactly one FLAC frame, so decode_native
        // must consume all bytes: either streaming them in (NEED_MORE_DATA) or
        // completing a frame (SUCCESS/HEADER_READY). A mismatch means data loss.
        if (native_consumed != body_len) {
            return this->set_fatal_error(FLAC_DECODER_ERROR_OGG_DEMUX);
        }

        if (result == FLAC_DECODER_NEED_MORE_DATA) {
            continue;
        }
        if (result == FLAC_DECODER_HEADER_READY) {
            return FLAC_DECODER_HEADER_READY;
        }
        if (result == FLAC_DECODER_SUCCESS) {
            return FLAC_DECODER_SUCCESS;
        }
        if (result == FLAC_DECODER_END_OF_STREAM) {
            return FLAC_DECODER_END_OF_STREAM;
        }
        return result;  // error
    }
}
#endif  // MICRO_FLAC_DISABLE_OGG

// ============================================================================
// Header Parsing
// ============================================================================

FLACDecoderResult FLACDecoder::read_header(const uint8_t* buffer, size_t buffer_length,
                                           size_t& bytes_consumed) {
    size_t pos = 0;
    size_t remaining = buffer_length;
    bytes_consumed = 0;

    if (!this->header_parse_.in_progress) {
        this->metadata_blocks_.clear();
        if (this->header_parse_.data) {
            FLAC_FREE(this->header_parse_.data);
            this->header_parse_.data = nullptr;
            this->header_parse_.data_capacity = 0;
        }

        // Accumulate 'fLaC' magic bytes (streaming-safe).
        // block_header_buf/block_header_len track progress across calls.
        // in_progress stays false until magic is complete, so re-entry
        // continues accumulation (the clear/free above are harmless no-ops).
        while (this->header_parse_.block_header_len < sizeof(MAGIC_BYTES) && remaining > 0) {
            if (buffer[pos] != MAGIC_BYTES[this->header_parse_.block_header_len]) {
                this->header_parse_.block_header_len = 0;
                return FLAC_DECODER_ERROR_BAD_MAGIC_NUMBER;
            }
            this->header_parse_.block_header_len++;
            pos++;
            remaining--;
        }
        if (this->header_parse_.block_header_len < sizeof(MAGIC_BYTES)) {
            bytes_consumed = pos;
            return FLAC_DECODER_NEED_MORE_DATA;
        }
        // Magic complete; reset block_header_len for metadata block header use
        this->header_parse_.block_header_len = 0;
        this->header_parse_.in_progress = true;
    }

    while (!this->header_parse_.last_block || (this->header_parse_.length > 0)) {
        if (remaining == 0) {
            this->header_parse_.in_progress = true;
            bytes_consumed = pos;
            return FLAC_DECODER_NEED_MORE_DATA;
        }

        if (this->header_parse_.length == 0) {
            // Accumulate metadata block header bytes (4 bytes: 1-bit last + 7-bit type + 24-bit
            // length). This allows byte-by-byte streaming without requiring 4 bytes at once.
            while (this->header_parse_.block_header_len < 4 && remaining > 0) {
                this->header_parse_.block_header_buf[this->header_parse_.block_header_len++] =
                    buffer[pos++];
                remaining--;
            }
            if (this->header_parse_.block_header_len < 4) {
                this->header_parse_.in_progress = true;
                bytes_consumed = pos;
                return FLAC_DECODER_NEED_MORE_DATA;
            }

            // Parse the completed 4-byte block header
            uint8_t* bh = this->header_parse_.block_header_buf;
            this->header_parse_.last_block = (bh[0] & 0x80) != 0;
            this->header_parse_.type = bh[0] & 0x7F;  // NOLINT(readability-magic-numbers)
            this->header_parse_.length =
                (static_cast<uint32_t>(bh[1]) << 16) | (static_cast<uint32_t>(bh[2]) << 8) | bh[3];
            this->header_parse_.block_header_len = 0;
            this->header_parse_.bytes_read = 0;
        }

        // Determine if we should skip this metadata block due to size limits
        // Use array indexing for faster lookup (types 0-6 map directly, others use index 7)
        bool should_skip = false;
        if (this->header_parse_.type != FLAC_METADATA_TYPE_STREAMINFO) {
            size_t size_index = (this->header_parse_.type < METADATA_SIZE_LIMITS_COUNT - 1)
                                    ? this->header_parse_.type
                                    : METADATA_SIZE_LIMITS_COUNT - 1;
            uint32_t max_size = this->max_metadata_sizes_[size_index];

            if (this->header_parse_.length > max_size) {
                should_skip = true;
            }
        }

        if (this->header_parse_.type == FLAC_METADATA_TYPE_STREAMINFO) {
            // FLAC spec mandates STREAMINFO is exactly 34 bytes
            if (this->header_parse_.length != FLACStreamInfo::RAW_SIZE) {
                bytes_consumed = pos;
                return FLAC_DECODER_ERROR_BAD_HEADER;
            }

            // Copy STREAMINFO bytes into stream_info_.raw_, streaming-safe.
            uint32_t needed = FLACStreamInfo::RAW_SIZE - this->header_parse_.bytes_read;
            uint32_t avail = (remaining < needed) ? static_cast<uint32_t>(remaining) : needed;
            std::memcpy(this->stream_info_.raw_ + this->header_parse_.bytes_read, buffer + pos,
                        avail);
            this->header_parse_.bytes_read += avail;
            pos += avail;
            remaining -= avail;

            if (this->header_parse_.bytes_read < FLACStreamInfo::RAW_SIZE) {
                this->header_parse_.in_progress = true;
                bytes_consumed = pos;
                return FLAC_DECODER_NEED_MORE_DATA;
            }

            this->header_parse_.length = 0;
            this->header_parse_.bytes_read = 0;
        } else if (should_skip) {
            uint32_t bytes_to_skip =
                std::min(this->header_parse_.length - this->header_parse_.bytes_read,
                         static_cast<uint32_t>(remaining));

            // Skip bytes in batch by advancing buffer position directly
            pos += bytes_to_skip;
            remaining -= bytes_to_skip;
            this->header_parse_.bytes_read += bytes_to_skip;

            if (this->header_parse_.bytes_read == this->header_parse_.length) {
                this->header_parse_.length = 0;
                this->header_parse_.bytes_read = 0;
            }
        } else {
            // Allocate data buffer once when block parsing starts
            if (!this->header_parse_.data && this->header_parse_.length > 0) {
                this->header_parse_.data =
                    static_cast<uint8_t*>(FLAC_MALLOC(this->header_parse_.length));
                if (!this->header_parse_.data) {
                    bytes_consumed = pos;
                    return FLAC_DECODER_ERROR_MEMORY_ALLOCATION;
                }
                this->header_parse_.data_capacity = this->header_parse_.length;
            }

            uint32_t bytes_to_read =
                std::min(this->header_parse_.length - this->header_parse_.bytes_read,
                         static_cast<uint32_t>(remaining));

            // Use batch memcpy into the pre-allocated buffer
            std::memcpy(this->header_parse_.data + this->header_parse_.bytes_read, buffer + pos,
                        bytes_to_read);
            pos += bytes_to_read;
            remaining -= bytes_to_read;
            this->header_parse_.bytes_read += bytes_to_read;

            if (this->header_parse_.bytes_read == this->header_parse_.length) {
                // Grow vector before transferring ownership so that if emplace_back
                // fails, header_parse_ still owns the data and its destructor cleans up.
                this->metadata_blocks_.emplace_back();
                auto& block = this->metadata_blocks_.back();
                block.type = static_cast<FLACMetadataType>(this->header_parse_.type);
                block.length = this->header_parse_.length;
                block.data = this->header_parse_.data;  // Transfer ownership
                this->header_parse_.data = nullptr;
                this->header_parse_.data_capacity = 0;

                this->header_parse_.length = 0;
                this->header_parse_.bytes_read = 0;
            }
        }
    }

    if ((this->stream_info_.sample_rate() == 0) || (this->stream_info_.max_block_size() == 0)) {
        bytes_consumed = pos;
        return FLAC_DECODER_ERROR_BAD_HEADER;
    }

    if ((this->stream_info_.min_block_size() < 16) ||
        (this->stream_info_.min_block_size() > this->stream_info_.max_block_size()) ||
        (this->stream_info_.max_block_size() > UINT16_MAX)) {
        bytes_consumed = pos;
        return FLAC_DECODER_ERROR_BAD_BLOCK_SIZE;
    }

    bytes_consumed = pos;
    return FLAC_DECODER_SUCCESS;
}

FLAC_HOT FLACDecoderResult FLACDecoder::decode_frame(const uint8_t* buffer, size_t buffer_length,
                                                     uint8_t* output_buffer, uint32_t* num_samples,
                                                     bool output_32bit) {
    *num_samples = 0;

    // Allocate block_samples_ if needed
    if (!this->block_samples_) {
        this->block_samples_ = static_cast<int32_t*>(
            FLAC_MALLOC(static_cast<size_t>(this->stream_info_.max_block_size()) *
                        this->stream_info_.num_channels() * sizeof(int32_t)));
    }
    if (!this->block_samples_) {
        return FLAC_DECODER_ERROR_MEMORY_ALLOCATION;
    }

    // If idle, start a new frame
    if (this->frame_.stage == FrameDecodeStage::IDLE) {
        this->frame_.stage = FrameDecodeStage::FRAME_HEADER;
        this->frame_.header_buffer_len = 0;
        this->frame_.footer_pending = false;
        // CRC-16 covers all frame bytes from sync code through the last byte before the 2-byte
        // CRC field. Accumulated incrementally across three phases:
        //   1. header_buffer after header parse (decode_frame_header_phase)
        //   2. full user buffer on subframe NEED_MORE_DATA (decode_frame_subframe_phase)
        //   3. current buffer up to CRC field on footer entry (decode_frame_footer_phase)
        // Each phase CRCs exactly the bytes it consumed.
        this->frame_.running_crc16 = 0;
    }

    FLACDecoderResult ret = FLAC_DECODER_SUCCESS;
    size_t consumed = 0;

    if (this->frame_.stage == FrameDecodeStage::FRAME_HEADER) {
        ret = this->decode_frame_header_phase(buffer, buffer_length);
        if (ret != FLAC_DECODER_SUCCESS) {
            return ret;
        }
        consumed = this->buffer_index_;
    }

    // Remaining user buffer bytes after header consumption
    const uint8_t* data = buffer + consumed;
    size_t data_len = buffer_length - consumed;

    if (this->frame_.stage == FrameDecodeStage::SUBFRAME) {
        ret = this->decode_frame_subframe_phase(data, data_len);
        if (ret == FLAC_DECODER_NEED_MORE_DATA) {
            this->buffer_index_ = buffer_length;
            return ret;
        }
        if (ret != FLAC_DECODER_SUCCESS) {
            this->reset_frame_state();
            return ret;
        }

        this->frame_.stage = FrameDecodeStage::FRAME_FOOTER;
        this->frame_.footer_pending = false;
    }

    if (this->frame_.stage == FrameDecodeStage::FRAME_FOOTER) {
        ret = this->decode_frame_footer_phase(data, data_len);

        if (ret == FLAC_DECODER_NEED_MORE_DATA) {
            this->buffer_index_ = buffer_length;
            return ret;
        }
        if (ret != FLAC_DECODER_SUCCESS) {
            this->reset_frame_state();
            return ret;
        }

        // Footer used the user sub-buffer (data), so add the header offset.
        this->buffer_index_ += consumed;
        this->buffer_ = buffer;
        this->bytes_left_ = buffer_length - this->buffer_index_;
        this->frame_.stage = FrameDecodeStage::IDLE;
    }

    // Output phase
    if (this->frame_.stage == FrameDecodeStage::IDLE) {
        *num_samples = this->curr_frame_block_size_ * this->stream_info_.num_channels();
        write_samples(output_buffer, this->block_samples_, this->curr_frame_block_size_,
                      this->curr_frame_bits_per_sample_, this->stream_info_.num_channels(),
                      output_32bit);
        return FLAC_DECODER_SUCCESS;
    }

    // Should not reach here
    this->reset_frame_state();
    return FLAC_DECODER_ERROR_INTERNAL;
}

FLAC_HOT void FLACDecoder::reset_frame_state() {
    this->frame_ = FrameState{};
    this->subframe_ = SubframeState{};
    this->residual_ = ResidualState{};
    this->rice_ = RiceState{};
    this->bit_buffer_ = 0;
    this->bit_buffer_length_ = 0;
    this->out_of_data_ = false;
}

// ============================================================================
// Streaming State Machine
// ============================================================================

FLAC_HOT FLACDecoderResult FLACDecoder::decode_frame_header_phase(const uint8_t* buffer,
                                                                  size_t buffer_length) {
    size_t buf_offset = 0;

    // Phase 1: Accumulate first 5 bytes
    while (buf_offset < buffer_length && this->frame_.header_buffer_len < 5) {
        this->frame_.header_buffer[this->frame_.header_buffer_len++] = buffer[buf_offset++];
    }
    if (this->frame_.header_buffer_len < 5) {
        this->buffer_index_ = buffer_length;
        return FLAC_DECODER_NEED_MORE_DATA;
    }

    // Sync validation: quick-reject before computing target length
    // NOLINTNEXTLINE(readability-magic-numbers)
    if (this->frame_.header_buffer[0] != 0xFF || (this->frame_.header_buffer[1] & 0xFE) != 0xF8) {
        if (this->frame_.header_buffer_len <= 1 && buf_offset >= buffer_length) {
            this->reset_frame_state();
            return FLAC_DECODER_END_OF_STREAM;
        }
        this->reset_frame_state();
        return FLAC_DECODER_ERROR_SYNC_NOT_FOUND;
    }

    // Phase 2: Compute exact target length, accumulate remaining bytes
    uint8_t target = compute_frame_header_length(this->frame_.header_buffer);
    if (target < FRAME_HEADER_MIN_LENGTH) {
        // Minimum valid frame header is 6 bytes; 0 indicates an invalid UTF-8 coded number
        this->reset_frame_state();
        return FLAC_DECODER_ERROR_BAD_HEADER;
    }
    while (buf_offset < buffer_length && this->frame_.header_buffer_len < target) {
        this->frame_.header_buffer[this->frame_.header_buffer_len++] = buffer[buf_offset++];
    }
    if (this->frame_.header_buffer_len < target) {
        this->buffer_index_ = buffer_length;
        return FLAC_DECODER_NEED_MORE_DATA;
    }

    // Parse the exact-length header buffer
    FrameHeaderInfo header_info{};
    FLACDecoderResult ret =
        parse_frame_header(this->frame_.header_buffer, this->frame_.header_buffer_len,
                           this->stream_info_, this->enable_crc_check_, header_info);

    if (ret != FLAC_DECODER_SUCCESS) {
        this->reset_frame_state();
        return ret;
    }

    this->curr_frame_block_size_ = header_info.block_size;
    this->curr_frame_channel_assign_ = header_info.channel_assignment;
    this->curr_frame_bits_per_sample_ = header_info.bits_per_sample;

    if (this->curr_frame_block_size_ > this->stream_info_.max_block_size()) {
        this->reset_frame_state();
        return FLAC_DECODER_ERROR_BAD_BLOCK_SIZE;
    }

    // CRC the frame header bytes
    if (this->enable_crc_check_) {
        this->frame_.running_crc16 = update_crc16(
            this->frame_.running_crc16, this->frame_.header_buffer, this->frame_.header_buffer_len);
    }

    this->frame_.header_buffer_len = 0;

    // Set up for subframe decoding
    this->frame_.stage = FrameDecodeStage::SUBFRAME;
    this->frame_.resuming = false;
    this->frame_.channel_idx = 0;
    this->frame_.block_samples_offset = 0;

    this->buffer_index_ = buf_offset;

    return FLAC_DECODER_SUCCESS;
}

FLAC_HOT FLACDecoderResult FLACDecoder::decode_frame_subframe_phase(const uint8_t* buffer,
                                                                    size_t buffer_length) {
    bool resuming = this->frame_.resuming;
    this->set_buffer(buffer, buffer_length, !resuming);

    FLACDecoderResult sf_ret =
        this->decode_subframes(this->curr_frame_block_size_, this->curr_frame_bits_per_sample_,
                               this->curr_frame_channel_assign_);

    if (sf_ret == FLAC_DECODER_NEED_MORE_DATA) {
        this->drain_remaining_to_bit_buffer();
        this->frame_.resuming = true;
        this->buffer_index_ = buffer_length;
        this->bytes_left_ = 0;
        if (this->enable_crc_check_) {
            this->frame_.running_crc16 =
                update_crc16(this->frame_.running_crc16, buffer, buffer_length);
        }
    }

    return sf_ret;
}

FLAC_HOT FLACDecoderResult FLACDecoder::decode_frame_footer_phase(const uint8_t* buffer,
                                                                  size_t buffer_length) {
    // Phase 3: Frame Footer - consume alignment padding + 2 CRC-16 bytes via bit reader
    // We use read_uint to properly consume padding bits and CRC bytes from the bit stream,
    // since bit_buffer_ may contain bits from previous drains that can't be pushed back.
    if (!this->frame_.footer_pending) {
        // First entry (from Phase 2): consume byte-alignment padding (0-7 bits)
        if (this->bit_buffer_length_ % 8 != 0) {
            this->bit_buffer_length_ = static_cast<uint8_t>(this->bit_buffer_length_ & ~7U);
        }
        // CRC the frame data bytes from the current buffer
        if (this->enable_crc_check_) {
            size_t remaining_bytes = this->bit_buffer_length_ / 8;
            size_t frame_data_end = (this->buffer_index_ > remaining_bytes)
                                        ? (this->buffer_index_ - remaining_bytes)
                                        : 0;
            this->frame_.running_crc16 =
                update_crc16(this->frame_.running_crc16, this->buffer_, frame_data_end);
        }
    } else {
        // Resuming CRC read from a previous decode_frame call.
        this->set_buffer(buffer, buffer_length, false);
    }

    // Read 16 bits (2 bytes) of CRC-16 via the bit reader
    uint16_t crc_read = static_cast<uint16_t>(this->read_uint(16));
    if (FLAC_UNLIKELY(this->out_of_data_)) {
        this->drain_remaining_to_bit_buffer();
        this->frame_.footer_pending = true;
        this->buffer_index_ = buffer_length;
        this->bytes_left_ = 0;
        return FLAC_DECODER_NEED_MORE_DATA;
    }

    // Validate CRC-16
    if (this->enable_crc_check_ && crc_read != this->frame_.running_crc16) {
        return FLAC_DECODER_ERROR_CRC_MISMATCH;
    }

    // Push back any remaining whole bytes so buffer_index_ reflects
    // the true consumed position (start of next frame)
    this->reset_bit_buffer();
    return FLAC_DECODER_SUCCESS;
}

FLAC_HOT FLACDecoderResult FLACDecoder::decode_subframes(uint32_t block_size,
                                                         uint32_t bits_per_sample,
                                                         uint32_t channel_assignment) {
    FLACDecoderResult result = FLAC_DECODER_SUCCESS;
    bool resuming = this->frame_.resuming;

    // Compute per-channel sample depths
    // For stereo decorrelation (8-10), the side channel needs +1 bit
    uint32_t num_channels = (channel_assignment <= 7) ? channel_assignment + 1 : 2;
    uint32_t depths[8] = {};
    for (uint32_t ch = 0; ch < num_channels; ch++) {
        depths[ch] = bits_per_sample;
    }
    if (channel_assignment == CHANNEL_LEFT_SIDE || channel_assignment == CHANNEL_MID_SIDE) {
        depths[1] += 1;  // LEFT_SIDE or MID_SIDE: side channel is ch 1
    } else if (channel_assignment == CHANNEL_RIGHT_SIDE) {
        depths[0] += 1;  // RIGHT_SIDE: side channel is ch 0
    }

    // With 32-bit audio, all three joint stereo modes produce a 33-bit side channel.
    // LEFT_SIDE (8) and RIGHT_SIDE (9) warm-up samples decoded by LPC would be truncated
    // to int32_t at lpc_.shift > 0, producing wrong results. We use a separate int64_t
    // buffer (side_subframe_) to preserve the full 33-bit value for all joint stereo modes
    // when bps == 32. Decorrelation for LEFT_SIDE and RIGHT_SIDE uses only add/subtract
    // (which wraps safely in uint32_t), but MID_SIDE additionally needs the arithmetic
    // right shift to operate on the full int64_t value.
    bool wide_side = false;
    if (resuming) {
        wide_side = this->frame_.wide_side;
    } else {
        wide_side = (channel_assignment >= 8 && bits_per_sample == 32);
        this->frame_.wide_side = wide_side;
    }

    // Lazily allocate the wide side buffer when needed
    if (wide_side && !this->side_subframe_) {
        this->side_subframe_ = static_cast<int64_t*>(FLAC_MALLOC(
            static_cast<size_t>(this->stream_info_.max_block_size()) * sizeof(int64_t)));
        if (!this->side_subframe_) {
            return FLAC_DECODER_ERROR_MEMORY_ALLOCATION;
        }
    }

    // side_ch: index of the side channel within the stereo pair
    // RIGHT_SIDE has the side on ch 0; LEFT_SIDE and MID_SIDE have it on ch 1
    const uint32_t side_ch = (channel_assignment == CHANNEL_RIGHT_SIDE) ? 0U : 1U;

    uint32_t i = resuming ? this->frame_.channel_idx : 0;
    size_t offset = resuming ? this->frame_.block_samples_offset : 0;

    // Resume the interrupted channel if applicable
    if (resuming) {
        if (wide_side && i == side_ch) {
            result = this->decode_subframe_impl<int64_t>(block_size, depths[i], 0);
        } else {
            result = this->decode_subframe_impl<int32_t>(block_size, depths[i], offset);
        }
        if (result != FLAC_DECODER_SUCCESS) {
            this->frame_.channel_idx = static_cast<uint8_t>(i);
            this->frame_.block_samples_offset = offset;
            return result;
        }
        i++;
        offset += block_size;
    }

    // Continue with remaining channels
    for (; i < num_channels; i++) {
        if (wide_side && i == side_ch) {
            result = this->decode_subframe_impl<int64_t>(block_size, depths[i], 0);
        } else {
            result = this->decode_subframe_impl<int32_t>(block_size, depths[i], offset);
        }
        if (result != FLAC_DECODER_SUCCESS) {
            this->frame_.channel_idx = static_cast<uint8_t>(i);
            this->frame_.block_samples_offset = offset;
            return result;
        }
        offset += block_size;
    }

    // Apply stereo channel decorrelation if needed (no I/O, always completes)
    if (channel_assignment >= 8) {
        if (wide_side) {
            apply_channel_decorrelation(this->block_samples_, this->side_subframe_, block_size,
                                        channel_assignment);
        } else {
            const int32_t* side_ptr = (channel_assignment == CHANNEL_RIGHT_SIDE)
                                          ? this->block_samples_
                                          : (this->block_samples_ + block_size);
            apply_channel_decorrelation(this->block_samples_, side_ptr, block_size,
                                        channel_assignment);
        }
    }

    return FLAC_DECODER_SUCCESS;
}

template <typename SampleT>
FLAC_HOT FLACDecoderResult FLACDecoder::decode_subframe_impl(uint32_t block_size,
                                                             uint32_t bits_per_sample,
                                                             size_t block_samples_offset) {
    constexpr bool IS_WIDE = std::is_same<SampleT, int64_t>::value;
    using UnsignedT = typename std::make_unsigned<SampleT>::type;
    FLACDecoderResult result = FLAC_DECODER_SUCCESS;
    SampleT* dest = IS_WIDE
                        ? reinterpret_cast<SampleT*>(this->side_subframe_)
                        : reinterpret_cast<SampleT*>(this->block_samples_ + block_samples_offset);

    while (true) {
        switch (this->subframe_.stage) {
            case SubframeDecodeStage::SUBFRAME_HEADER: {
                // If resuming mid-wasted-bits-loop, skip header byte read
                if (!this->subframe_.wasted_pending) {
                    // Read subframe header atomically: 1 (padding) + 6 (type) + 1 (shift flag) = 8
                    // bits
                    uint32_t header_byte = this->read_uint(8);
                    if (FLAC_UNLIKELY(this->out_of_data_)) {
                        return FLAC_DECODER_NEED_MORE_DATA;
                    }

                    // Parse: bit 7 = padding (ignored), bits 6-1 = type, bit 0 = shift flag
                    this->subframe_.type =
                        (header_byte >> 1) & 0x3F;  // NOLINT(readability-magic-numbers)
                    this->subframe_.shift = header_byte & 1;
                    this->subframe_.sample_idx = 0;
                }

                // Handle wasted bits: each read_uint(1) is atomic
                if (this->subframe_.shift >= 1) {
                    while (true) {
                        uint32_t bit = this->read_uint(1);
                        if (FLAC_UNLIKELY(this->out_of_data_)) {
                            this->subframe_.wasted_pending = true;
                            return FLAC_DECODER_NEED_MORE_DATA;
                        }
                        if (bit == 1) {
                            break;
                        }
                        this->subframe_.shift++;
                    }
                }
                this->subframe_.wasted_pending = false;

                if (this->subframe_.shift >= bits_per_sample) {
                    return FLAC_DECODER_ERROR_BAD_SAMPLE_DEPTH;
                }
                this->subframe_.bits_per_sample = bits_per_sample - this->subframe_.shift;

                // Dispatch based on type
                if (this->subframe_.type == 0) {
                    this->subframe_.stage = SubframeDecodeStage::SUBFRAME_CONSTANT;
                } else if (this->subframe_.type == 1) {
                    this->subframe_.stage = SubframeDecodeStage::SUBFRAME_VERBATIM;
                } else if (this->subframe_.type >= 8 &&
                           this->subframe_.type <= SUBFRAME_FIXED_MAX) {
                    this->lpc_.order = this->subframe_.type - 8;
                    if (this->lpc_.order >= block_size) {
                        return FLAC_DECODER_ERROR_BAD_LPC_PARAMS;
                    }
                    this->subframe_.sample_idx = 0;
                    this->subframe_.stage = SubframeDecodeStage::SUBFRAME_WARMUP;
                } else if (this->subframe_.type >= SUBFRAME_LPC_MIN &&
                           this->subframe_.type <= SUBFRAME_LPC_MAX) {
                    this->lpc_.order = this->subframe_.type - (SUBFRAME_LPC_MIN - 1);
                    if (this->lpc_.order >= block_size) {
                        return FLAC_DECODER_ERROR_BAD_LPC_PARAMS;
                    }
                    this->subframe_.sample_idx = 0;
                    this->subframe_.stage = SubframeDecodeStage::SUBFRAME_WARMUP;
                } else {
                    return FLAC_DECODER_ERROR_RESERVED_SUBFRAME_TYPE;
                }
                continue;
            }

            case SubframeDecodeStage::SUBFRAME_CONSTANT: {
                SampleT value = this->read_sint_t<SampleT>(
                    static_cast<uint8_t>(this->subframe_.bits_per_sample));
                if (FLAC_UNLIKELY(this->out_of_data_)) {
                    return FLAC_DECODER_NEED_MORE_DATA;
                }
                value =
                    static_cast<SampleT>(static_cast<UnsignedT>(value) << this->subframe_.shift);
                std::fill(dest, dest + block_size, static_cast<SampleT>(value));
                this->subframe_.stage = SubframeDecodeStage::SUBFRAME_HEADER;
                this->subframe_.wasted_pending = false;
                return FLAC_DECODER_SUCCESS;
            }

            case SubframeDecodeStage::SUBFRAME_VERBATIM: {
                size_t i = this->subframe_.sample_idx;

                for (; i < block_size; i++) {
                    SampleT val = this->read_sint_t<SampleT>(
                        static_cast<uint8_t>(this->subframe_.bits_per_sample));
                    if (FLAC_UNLIKELY(this->out_of_data_)) {
                        this->subframe_.sample_idx = i;
                        return FLAC_DECODER_NEED_MORE_DATA;
                    }
                    dest[i] =
                        static_cast<SampleT>(static_cast<UnsignedT>(val) << this->subframe_.shift);
                }
                this->subframe_.stage = SubframeDecodeStage::SUBFRAME_HEADER;
                this->subframe_.wasted_pending = false;
                return FLAC_DECODER_SUCCESS;
            }

            case SubframeDecodeStage::SUBFRAME_WARMUP: {
                size_t i = this->subframe_.sample_idx;

                for (; i < this->lpc_.order; i++) {
                    SampleT val = this->read_sint_t<SampleT>(
                        static_cast<uint8_t>(this->subframe_.bits_per_sample));
                    if (FLAC_UNLIKELY(this->out_of_data_)) {
                        this->subframe_.sample_idx = i;
                        return FLAC_DECODER_NEED_MORE_DATA;
                    }
                    dest[i] = static_cast<SampleT>(val);
                }
                this->subframe_.sample_idx = 0;

                if (this->subframe_.type >= SUBFRAME_LPC_MIN &&
                    this->subframe_.type <= SUBFRAME_LPC_MAX) {
                    this->lpc_.precision = 0;
                    this->subframe_.stage = SubframeDecodeStage::LPC_PARAMS;
                } else {
                    this->residual_.out_ptr_offset = this->lpc_.order;
                    this->subframe_.stage = SubframeDecodeStage::RESIDUAL_HEADER;
                }
                continue;
            }

            case SubframeDecodeStage::LPC_PARAMS: {
                if (this->lpc_.precision == 0) {
                    // NOLINTNEXTLINE(readability-magic-numbers) -- LPC precision (4b) + shift (5b)
                    uint32_t precision_and_shift = this->read_uint(9);
                    if (FLAC_UNLIKELY(this->out_of_data_)) {
                        this->lpc_.precision = 0;  // sentinel: precision not read yet
                        this->lpc_.coef_idx = 0;
                        return FLAC_DECODER_NEED_MORE_DATA;
                    }

                    this->lpc_.precision = (precision_and_shift >> 5) + 1;

                    // Precision of 16 (0b1111 + 1) is reserved per RFC 9639
                    if (FLAC_UNLIKELY(this->lpc_.precision == 16)) {
                        return FLAC_DECODER_ERROR_BAD_LPC_PARAMS;
                    }

                    // Sign-extend bottom 5 bits
                    int32_t raw_shift =
                        precision_and_shift & 0x1F;  // NOLINT(readability-magic-numbers)
                    this->lpc_.shift = (raw_shift >= 16) ? (raw_shift - 32) : raw_shift;

                    // Negative shift is forbidden per RFC 9639 Appendix B.4
                    if (FLAC_UNLIKELY(this->lpc_.shift < 0)) {
                        return FLAC_DECODER_ERROR_BAD_LPC_PARAMS;
                    }

                    this->lpc_.coef_idx = 0;
                }

                uint32_t i = this->lpc_.coef_idx;
                for (; i < this->lpc_.order; i++) {
                    // LPC coefficients are always <=15 bits, use int32_t path
                    int32_t coef =
                        this->read_sint_t<int32_t>(static_cast<uint8_t>(this->lpc_.precision));
                    if (FLAC_UNLIKELY(this->out_of_data_)) {
                        this->lpc_.coef_idx = i;
                        return FLAC_DECODER_NEED_MORE_DATA;
                    }
                    this->lpc_.coefs[this->lpc_.order - i - 1] = coef;
                }

                this->residual_.out_ptr_offset = this->lpc_.order;
                this->subframe_.stage = SubframeDecodeStage::RESIDUAL_HEADER;
                continue;
            }

            case SubframeDecodeStage::RESIDUAL_HEADER:
            case SubframeDecodeStage::RESIDUAL_PARTITION_PARAM:
            case SubframeDecodeStage::RESIDUAL_ESCAPE_BITS:
            case SubframeDecodeStage::RESIDUAL_SAMPLES: {
                // Decode residuals directly into the destination buffer
                result = this->decode_residuals(dest, this->lpc_.order, block_size);
                if (result != FLAC_DECODER_SUCCESS) {
                    return result;
                }

                const int32_t* coefs = nullptr;
                int32_t shift = 0;
                if (this->subframe_.type >= 8 && this->subframe_.type <= SUBFRAME_FIXED_MAX) {
                    coefs = FIXED_COEFFICIENTS[this->lpc_.order];
                    shift = 0;
                } else {
                    coefs = this->lpc_.coefs;
                    shift = this->lpc_.shift;
                }

                restore_lpc(dest, block_size, this->subframe_.bits_per_sample, coefs,
                            this->lpc_.order, shift);

                if (this->subframe_.shift > 0) {
                    for (size_t i = 0; i < block_size; i++) {
                        dest[i] = static_cast<SampleT>(static_cast<UnsignedT>(dest[i])
                                                       << this->subframe_.shift);
                    }
                }

                this->subframe_.stage = SubframeDecodeStage::SUBFRAME_HEADER;
                this->subframe_.wasted_pending = false;
                return FLAC_DECODER_SUCCESS;
            }
        }
    }
}

template FLACDecoderResult FLACDecoder::decode_subframe_impl<int32_t>(uint32_t, uint32_t, size_t);
template FLACDecoderResult FLACDecoder::decode_subframe_impl<int64_t>(uint32_t, uint32_t, size_t);

template <typename OutputT>
FLAC_OPTIMIZE_O3 FLACDecoderResult FLACDecoder::decode_residuals(OutputT* sub_frame_buffer,
                                                                 uint32_t warm_up_samples,
                                                                 uint32_t block_size) {
    while (true) {
        switch (this->subframe_.stage) {
            case SubframeDecodeStage::RESIDUAL_HEADER: {
                // Read method (2 bits) + partition_order (4 bits) = 6 bits atomically
                uint32_t method_and_order = this->read_uint(6);
                if (FLAC_UNLIKELY(this->out_of_data_)) {
                    return FLAC_DECODER_NEED_MORE_DATA;
                }

                uint32_t method = method_and_order >> 4;
                if (method >= 2) {
                    return FLAC_DECODER_ERROR_RESERVED_RESIDUAL_CODING_METHOD;
                }

                this->residual_.param_bits = (method == 1) ? 5 : 4;
                // NOLINTNEXTLINE(readability-magic-numbers) -- Rice escape: 5-bit or 4-bit param
                this->residual_.escape_param = (method == 1) ? 0x1F : 0xF;
                this->residual_.partition_order =
                    method_and_order & 0xF;  // NOLINT(readability-magic-numbers)
                this->residual_.num_partitions = 1 << this->residual_.partition_order;

                if ((block_size % this->residual_.num_partitions) != 0) {
                    return FLAC_DECODER_ERROR_BLOCK_SIZE_NOT_DIVISIBLE_RICE;
                }

                this->residual_.partition_idx = 0;
                this->residual_.out_ptr_offset = warm_up_samples;
                this->residual_.is_escape = false;
                this->rice_.pending = false;

                this->subframe_.stage = SubframeDecodeStage::RESIDUAL_PARTITION_PARAM;
                continue;
            }

            case SubframeDecodeStage::RESIDUAL_PARTITION_PARAM:
            case SubframeDecodeStage::RESIDUAL_ESCAPE_BITS: {
                FLACDecoderResult param_ret =
                    this->read_partition_param(block_size, warm_up_samples);
                if (param_ret != FLAC_DECODER_SUCCESS) {
                    return param_ret;
                }
                continue;
            }

            case SubframeDecodeStage::RESIDUAL_SAMPLES: {
                OutputT* out_ptr = sub_frame_buffer + this->residual_.out_ptr_offset;

                if (this->residual_.is_escape) {
                    uint32_t sample_idx_e = this->residual_.sample_idx;
                    const uint32_t partition_count_e = this->residual_.partition_count;

                    if (this->residual_.escape_bits == 0) {
                        size_t remaining = partition_count_e - sample_idx_e;
                        std::memset(out_ptr + sample_idx_e, 0, remaining * sizeof(OutputT));
                        sample_idx_e = partition_count_e;
                    } else {
                        const uint8_t escape_bits =
                            static_cast<uint8_t>(this->residual_.escape_bits);
                        for (; sample_idx_e < partition_count_e; sample_idx_e++) {
                            int32_t val = this->read_sint_t<int32_t>(escape_bits);
                            if (FLAC_UNLIKELY(this->out_of_data_)) {
                                this->residual_.sample_idx = sample_idx_e;
                                return FLAC_DECODER_NEED_MORE_DATA;
                            }
                            out_ptr[sample_idx_e] = val;
                        }
                    }
                    this->residual_.sample_idx = sample_idx_e;
                } else {
                    // Residuals are always decoded as int32_t even on the int64_t (wide-side)
                    // path. Per RFC 9639 §9.2.7.3, Rice-coded residuals must fit in a signed
                    // 32-bit two's complement integer (excluding INT32_MIN), so int32_t is
                    // sufficient regardless of the output sample type.

                    // Hoist struct fields into locals for the hot loop.
                    // This lets the compiler keep them in registers instead
                    // of reloading from memory on every iteration.
                    uint32_t sample_idx = this->residual_.sample_idx;
                    const uint32_t partition_count = this->residual_.partition_count;
                    const uint8_t rice_param = static_cast<uint8_t>(this->residual_.param);

                    // If resuming mid-rice-read, finish that one sample first
                    if (this->rice_.pending) {
                        int32_t val = this->read_rice_sint<true>(rice_param);
                        if (FLAC_UNLIKELY(this->out_of_data_)) {
                            this->residual_.sample_idx = sample_idx;
                            return FLAC_DECODER_NEED_MORE_DATA;
                        }
                        out_ptr[sample_idx] = val;
                        sample_idx++;
                        this->rice_.pending = false;
                    }
                    for (; sample_idx < partition_count; sample_idx++) {
                        int32_t val = this->read_rice_sint<false>(rice_param);
                        if (FLAC_UNLIKELY(this->out_of_data_)) {
                            this->residual_.sample_idx = sample_idx;
                            this->rice_.pending = true;
                            return FLAC_DECODER_NEED_MORE_DATA;
                        }
                        out_ptr[sample_idx] = val;
                    }
                    this->residual_.sample_idx = sample_idx;
                }

                this->residual_.out_ptr_offset += this->residual_.partition_count;
                this->residual_.partition_idx++;

                if (this->residual_.partition_idx >= this->residual_.num_partitions) {
                    return FLAC_DECODER_SUCCESS;
                }

                this->subframe_.stage = SubframeDecodeStage::RESIDUAL_PARTITION_PARAM;
                continue;
            }

            default:
                // Should not reach here: called with non-residual stage
                return FLAC_DECODER_ERROR_RESERVED_SUBFRAME_TYPE;
        }
    }
}

template FLACDecoderResult FLACDecoder::decode_residuals<int32_t>(int32_t*, uint32_t, uint32_t);
template FLACDecoderResult FLACDecoder::decode_residuals<int64_t>(int64_t*, uint32_t, uint32_t);

FLAC_HOT FLACDecoderResult FLACDecoder::read_partition_param(uint32_t block_size,
                                                             uint32_t warm_up_samples) {
    if (this->subframe_.stage == SubframeDecodeStage::RESIDUAL_PARTITION_PARAM) {
        this->residual_.param = this->read_uint(this->residual_.param_bits);
        if (FLAC_UNLIKELY(this->out_of_data_)) {
            return FLAC_DECODER_NEED_MORE_DATA;
        }

        this->residual_.partition_count = block_size >> this->residual_.partition_order;
        if (this->residual_.partition_idx == 0) {
            if (this->residual_.partition_count < warm_up_samples) {
                return FLAC_DECODER_ERROR_BAD_RICE_PARTITION;
            }
            this->residual_.partition_count -= warm_up_samples;
        }
        this->residual_.sample_idx = 0;

        if (this->residual_.param < this->residual_.escape_param) {
            this->residual_.is_escape = false;
            this->subframe_.stage = SubframeDecodeStage::RESIDUAL_SAMPLES;
            return FLAC_DECODER_SUCCESS;
        }

        this->residual_.is_escape = true;
        this->subframe_.stage = SubframeDecodeStage::RESIDUAL_ESCAPE_BITS;
        // Intentional fall-through: escape partition needs the 5-bit escape sample size next
    }

    // RESIDUAL_ESCAPE_BITS
    this->residual_.escape_bits = this->read_uint(5);
    if (FLAC_UNLIKELY(this->out_of_data_)) {
        return FLAC_DECODER_NEED_MORE_DATA;
    }

    this->subframe_.stage = SubframeDecodeStage::RESIDUAL_SAMPLES;
    return FLAC_DECODER_SUCCESS;
}

template <bool Resuming>
FLAC_ALWAYS_INLINE int32_t FLACDecoder::read_rice_sint(uint8_t param) {
    uint32_t unary_count = Resuming ? this->rice_.unary_count : 0;

    if (!Resuming || !this->rice_.binary_pending) {
        // Unary phase: count leading zeros
        while (true) {
            if (this->bit_buffer_length_ == 0) {
                if (FLAC_UNLIKELY(this->refill_bit_buffer())) {
                    this->rice_.unary_count = unary_count;
                    this->rice_.binary_pending = false;
                    this->out_of_data_ = true;
                    return 0;
                }
            }

            bit_buffer_t shifted_buffer = this->bit_buffer_
                                          << (BIT_BUFFER_BITS - this->bit_buffer_length_);

            if (FLAC_UNLIKELY(shifted_buffer == 0)) {
                unary_count += this->bit_buffer_length_;
                this->bit_buffer_length_ = 0;
                continue;
            }

            uint32_t leading_zeros = static_cast<uint32_t>(FLAC_CLZ(shifted_buffer));
            unary_count += leading_zeros;
            this->bit_buffer_length_ =
                static_cast<uint8_t>(this->bit_buffer_length_ - (leading_zeros + 1));
            break;
        }
    }

    // Binary phase: read rice parameter bits
    uint32_t binary = this->read_uint(param);
    if (FLAC_UNLIKELY(this->out_of_data_)) {
        this->rice_.unary_count = unary_count;
        this->rice_.binary_pending = true;
        return 0;
    }

    // Rice parameter is at most 30 (5-bit param, with 31 reserved as escape code),
    // so shifting a uint32_t left by param is always well-defined.
    assert(param < 32);
    uint32_t value = (unary_count << param) | binary;
    return static_cast<int32_t>((value >> 1) ^ -(value & 1));
}

void FLACDecoder::drain_remaining_to_bit_buffer() {
    // Drain unconsumed bytes from user's buffer into bit_buffer_.
    // Safe because: when read_uint fails, bit_buffer_length_ + 8*bytes_left_ < BIT_BUFFER_BITS
    while (this->bytes_left_ > 0) {
        this->bit_buffer_ = (this->bit_buffer_ << 8) |
                            static_cast<bit_buffer_t>(this->buffer_[this->buffer_index_++]);
        this->bit_buffer_length_ += 8;
        this->bytes_left_--;
    }
}

// ============================================================================
// Bit Stream Reading
// ============================================================================

FLAC_ALWAYS_INLINE bool FLACDecoder::refill_bit_buffer() {
    // ESP-IDF disables jump tables by default (-fno-jump-tables), so a switch statement
    // compiles to a chain of comparisons anyway. Using explicit if/else with FLAC_LIKELY
    // on the hot path lets the compiler prioritize it.
    //
    // All paths overwrite bit_buffer_ with only the newly loaded bytes. Old bits are NOT
    // preserved. This is safe because both callers handle old bits before calling refill:
    //   - read_uint() extracts old bits into its local `result` before calling refill
    //   - read_rice_sint() only calls refill when bit_buffer_length_ == 0 (no old bits)
#if (BIT_BUFFER_BITS == 64)
    if (FLAC_LIKELY(this->bytes_left_ >= 8)) {
        // 8 or more bytes available: load 8 bytes big-endian
        this->bit_buffer_ = (static_cast<uint64_t>(this->buffer_[this->buffer_index_]) << 56) |
                            (static_cast<uint64_t>(this->buffer_[this->buffer_index_ + 1]) << 48) |
                            (static_cast<uint64_t>(this->buffer_[this->buffer_index_ + 2]) << 40) |
                            (static_cast<uint64_t>(this->buffer_[this->buffer_index_ + 3]) << 32) |
                            (static_cast<uint64_t>(this->buffer_[this->buffer_index_ + 4]) << 24) |
                            (static_cast<uint64_t>(this->buffer_[this->buffer_index_ + 5]) << 16) |
                            (static_cast<uint64_t>(this->buffer_[this->buffer_index_ + 6]) << 8) |
                            this->buffer_[this->buffer_index_ + 7];
        this->buffer_index_ += 8;
        this->bytes_left_ -= 8;
        this->bit_buffer_length_ = 64;
        return false;
    }
    if (this->bytes_left_ == 7) {
        this->bit_buffer_ = (static_cast<uint64_t>(this->buffer_[this->buffer_index_]) << 48) |
                            (static_cast<uint64_t>(this->buffer_[this->buffer_index_ + 1]) << 40) |
                            (static_cast<uint64_t>(this->buffer_[this->buffer_index_ + 2]) << 32) |
                            (static_cast<uint64_t>(this->buffer_[this->buffer_index_ + 3]) << 24) |
                            (static_cast<uint64_t>(this->buffer_[this->buffer_index_ + 4]) << 16) |
                            (static_cast<uint64_t>(this->buffer_[this->buffer_index_ + 5]) << 8) |
                            this->buffer_[this->buffer_index_ + 6];
        this->buffer_index_ += 7;
        this->bit_buffer_length_ = 56;
        this->bytes_left_ = 0;
        return false;
    }
    if (this->bytes_left_ == 6) {
        this->bit_buffer_ = (static_cast<uint64_t>(this->buffer_[this->buffer_index_]) << 40) |
                            (static_cast<uint64_t>(this->buffer_[this->buffer_index_ + 1]) << 32) |
                            (static_cast<uint64_t>(this->buffer_[this->buffer_index_ + 2]) << 24) |
                            (static_cast<uint64_t>(this->buffer_[this->buffer_index_ + 3]) << 16) |
                            (static_cast<uint64_t>(this->buffer_[this->buffer_index_ + 4]) << 8) |
                            this->buffer_[this->buffer_index_ + 5];
        this->buffer_index_ += 6;
        this->bit_buffer_length_ = 48;
        this->bytes_left_ = 0;
        return false;
    }
    if (this->bytes_left_ == 5) {
        this->bit_buffer_ = (static_cast<uint64_t>(this->buffer_[this->buffer_index_]) << 32) |
                            (static_cast<uint64_t>(this->buffer_[this->buffer_index_ + 1]) << 24) |
                            (static_cast<uint64_t>(this->buffer_[this->buffer_index_ + 2]) << 16) |
                            (static_cast<uint64_t>(this->buffer_[this->buffer_index_ + 3]) << 8) |
                            this->buffer_[this->buffer_index_ + 4];
        this->buffer_index_ += 5;
        this->bit_buffer_length_ = 40;
        this->bytes_left_ = 0;
        return false;
    }
    if (this->bytes_left_ == 4) {
        this->bit_buffer_ = (static_cast<uint64_t>(this->buffer_[this->buffer_index_]) << 24) |
                            (static_cast<uint64_t>(this->buffer_[this->buffer_index_ + 1]) << 16) |
                            (static_cast<uint64_t>(this->buffer_[this->buffer_index_ + 2]) << 8) |
                            this->buffer_[this->buffer_index_ + 3];
        this->buffer_index_ += 4;
        this->bit_buffer_length_ = 32;
        this->bytes_left_ = 0;
        return false;
    }
#else
    if (FLAC_LIKELY(this->bytes_left_ >= 4)) {
        // 4 or more bytes available: load 4 bytes big-endian
        this->bit_buffer_ = (static_cast<uint32_t>(this->buffer_[this->buffer_index_]) << 24) |
                            (static_cast<uint32_t>(this->buffer_[this->buffer_index_ + 1]) << 16) |
                            (static_cast<uint32_t>(this->buffer_[this->buffer_index_ + 2]) << 8) |
                            this->buffer_[this->buffer_index_ + 3];
        this->buffer_index_ += 4;
        this->bytes_left_ -= 4;
        this->bit_buffer_length_ = 32;
        return false;
    }
#endif
    if (this->bytes_left_ == 3) {
        this->bit_buffer_ =
            (static_cast<bit_buffer_t>(this->buffer_[this->buffer_index_]) << 16) |
            (static_cast<bit_buffer_t>(this->buffer_[this->buffer_index_ + 1]) << 8) |
            this->buffer_[this->buffer_index_ + 2];
        this->buffer_index_ += 3;
        this->bit_buffer_length_ = 24;
        this->bytes_left_ = 0;
        return false;
    }
    if (this->bytes_left_ == 2) {
        this->bit_buffer_ = (static_cast<bit_buffer_t>(this->buffer_[this->buffer_index_]) << 8) |
                            this->buffer_[this->buffer_index_ + 1];
        this->buffer_index_ += 2;
        this->bit_buffer_length_ = 16;
        this->bytes_left_ = 0;
        return false;
    }
    if (this->bytes_left_ == 1) {
        this->bit_buffer_ = this->buffer_[this->buffer_index_];
        this->buffer_index_ += 1;
        this->bit_buffer_length_ = 8;
        this->bytes_left_ = 0;
        return false;
    }
    return true;
}

FLAC_ALWAYS_INLINE uint32_t FLACDecoder::read_uint(uint8_t num_bits) {
    uint32_t result = 0;

    if (num_bits > this->bit_buffer_length_) {
        const uint32_t new_bits_needed = num_bits - this->bit_buffer_length_;
        size_t bytes_needed = (new_bits_needed + 7) / 8;

        if (FLAC_UNLIKELY(this->bytes_left_ < bytes_needed)) {
            this->out_of_data_ = true;
            return 0;
        }

        if (new_bits_needed < BIT_BUFFER_BITS) {
            // Some of the current bits will be used in the result
            result = static_cast<uint32_t>(this->bit_buffer_ << new_bits_needed);
        }

        this->refill_bit_buffer();
        this->bit_buffer_length_ = static_cast<uint8_t>(this->bit_buffer_length_ - new_bits_needed);
    } else {
        this->bit_buffer_length_ -= num_bits;
    }

    result |= static_cast<uint32_t>(this->bit_buffer_ >>
                                    (this->bit_buffer_length_ & BIT_BUFFER_SHIFT_MASK));

    result &= uint_mask(num_bits);

    return result;
}

template <typename SampleT>
FLAC_ALWAYS_INLINE SampleT FLACDecoder::read_sint_t(uint8_t num_bits) {
    if (FLAC_LIKELY(num_bits < 32)) {
        uint32_t next_int = this->read_uint(num_bits);
        uint32_t sign_bit = uint32_t(1) << (num_bits - 1);
        return static_cast<SampleT>(static_cast<int32_t>((next_int ^ sign_bit) - sign_bit));
    }
    if (num_bits == 32) {
        uint32_t next_int = this->read_uint(num_bits);
        return static_cast<SampleT>(static_cast<int32_t>(next_int));
    }
    // Handle 33-bit reads for side channel in 32-bit stereo

    // Pre-check: ensure enough data for the full read before consuming anything.
    // Without this, the first read_uint could succeed and consume bits, then
    // the second could fail, leaving the stream in a corrupt state on resume.
    size_t total_bits_available = this->bit_buffer_length_ + 8 * this->bytes_left_;
    if (total_bits_available < num_bits) {
        this->out_of_data_ = true;
        return 0;
    }
    // Both reads are now guaranteed to succeed
    uint32_t upper_bits = this->read_uint(num_bits - 32);
    uint32_t lower_bits = this->read_uint(32);

    int64_t value = (static_cast<int64_t>(upper_bits) << 32) | lower_bits;
    // Only 33-bit reads reach this branch (side channel in 32-bit stereo),
    // so the shifts of 32 and 33 on int64_t are well within the 63-bit limit.
    assert(num_bits > 32 && num_bits < 64);
    int64_t sign_bit = static_cast<int64_t>(1) << (num_bits - 1);
    if (value & sign_bit) {
        value |= ~((static_cast<int64_t>(1) << num_bits) - 1);
    }
    // Preserve the full 33-bit value; only int64_t reaches this branch.
    return static_cast<SampleT>(value);
}

template int32_t FLACDecoder::read_sint_t<int32_t>(uint8_t num_bits);
template int64_t FLACDecoder::read_sint_t<int64_t>(uint8_t num_bits);

void FLACDecoder::reset_bit_buffer() {
    assert(this->bit_buffer_length_ % 8 == 0);

    this->buffer_index_ -= static_cast<size_t>(this->bit_buffer_length_ / 8);
    this->bytes_left_ += static_cast<size_t>(this->bit_buffer_length_ / 8);
    this->bit_buffer_length_ = 0;
    this->bit_buffer_ = 0;
}

// ============================================================================
// Internal Utilities
// ============================================================================

void FLACDecoder::set_buffer(const uint8_t* buf, size_t len, bool reset_bits) {
    this->buffer_ = buf;
    this->buffer_index_ = 0;
    this->bytes_left_ = len;
    this->out_of_data_ = false;
    if (reset_bits) {
        this->bit_buffer_ = 0;
        this->bit_buffer_length_ = 0;
    }
}

void FLACDecoder::free_buffers() {
    if (this->block_samples_) {
        FLAC_FREE(this->block_samples_);
        this->block_samples_ = nullptr;
    }

    if (this->side_subframe_) {
        FLAC_FREE(this->side_subframe_);
        this->side_subframe_ = nullptr;
    }

#ifndef MICRO_FLAC_DISABLE_OGG
    // Clean up Ogg demuxer
    this->ogg_demuxer_.reset();
#endif

    // Clear metadata blocks (destructors free each block's data via FLAC_FREE)
    this->metadata_blocks_.clear();

    // Free header parse buffer
    if (this->header_parse_.data) {
        FLAC_FREE(this->header_parse_.data);
        this->header_parse_.data = nullptr;
        this->header_parse_.data_capacity = 0;
    }
}

}  // namespace micro_flac
