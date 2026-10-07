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

/// @file flac_decoder.h
/// @brief FLAC decoder optimized for ESP32
///
/// Based on: https://www.nayuki.io/res/simple-flac-implementation/
/// Spec: https://xiph.org/flac/format.html

#pragma once

#include <cstddef>
#include <cstdint>
#include <memory>
#include <vector>

#ifndef MICRO_FLAC_DISABLE_OGG
namespace micro_ogg {
class OggDemuxer;
}
#endif

namespace micro_flac {

// ============================================================================
// Bit Buffer Configuration
// ============================================================================

// Use a 32-bit bit buffer on 32-bit platforms and a 64-bit bit buffer on 64-bit
// platforms to reduce refill frequency.
#if UINTPTR_MAX == 0xFFFFFFFF
using bit_buffer_t = uint32_t;
#else
using bit_buffer_t = uint64_t;
#endif

// ============================================================================
// Public Types
// ============================================================================

/// @brief Result codes returned by FLACDecoder methods
/// Positive values indicate success/informational states, negative values indicate errors.
enum FLACDecoderResult : int8_t {
    // Success / informational (>= 0)
    FLAC_DECODER_SUCCESS = 0,         // Frame decoded (check samples_decoded)
    FLAC_DECODER_HEADER_READY = 1,    // Header parsed, stream info available
    FLAC_DECODER_END_OF_STREAM = 2,   // No more frames
    FLAC_DECODER_NEED_MORE_DATA = 3,  // Feed more input data

    // Errors (< 0)
    FLAC_DECODER_ERROR_BAD_HEADER = -1,
    FLAC_DECODER_ERROR_SYNC_NOT_FOUND = -2,
    FLAC_DECODER_ERROR_CRC_MISMATCH = -3,
    FLAC_DECODER_ERROR_MEMORY_ALLOCATION = -4,
    FLAC_DECODER_ERROR_BAD_MAGIC_NUMBER = -5,
    FLAC_DECODER_ERROR_BAD_SAMPLE_DEPTH = -6,
    FLAC_DECODER_ERROR_RESERVED_CHANNEL_ASSIGNMENT = -7,
    FLAC_DECODER_ERROR_RESERVED_SUBFRAME_TYPE = -8,
    FLAC_DECODER_ERROR_RESERVED_RESIDUAL_CODING_METHOD = -9,
    FLAC_DECODER_ERROR_BAD_BLOCK_SIZE = -10,
    FLAC_DECODER_ERROR_BLOCK_SIZE_NOT_DIVISIBLE_RICE = -11,
    FLAC_DECODER_ERROR_BAD_SAMPLE_RATE = -12,
    FLAC_DECODER_ERROR_BAD_LPC_PARAMS = -13,
    FLAC_DECODER_ERROR_OGG_DEMUX = -14,
    FLAC_DECODER_ERROR_OGG_BAD_HEADER = -15,
    FLAC_DECODER_ERROR_INPUT_INVALID = -16,
    FLAC_DECODER_ERROR_OUTPUT_TOO_SMALL = -17,
    FLAC_DECODER_ERROR_FRAME_MISMATCH = -18,
    FLAC_DECODER_ERROR_INTERNAL = -19,
    FLAC_DECODER_ERROR_BAD_RICE_PARTITION = -20,
};

/// @brief FLAC metadata block types as defined in the FLAC specification
/// These identify the different types of metadata that can appear in a FLAC file's header.
enum FLACMetadataType : uint8_t {
    FLAC_METADATA_TYPE_STREAMINFO = 0,  // Required stream information (sample rate, channels, etc.)
    FLAC_METADATA_TYPE_PADDING = 1,     // Empty space for future metadata
    FLAC_METADATA_TYPE_APPLICATION = 2,     // Application-specific data
    FLAC_METADATA_TYPE_SEEKTABLE = 3,       // Seek points for fast random access
    FLAC_METADATA_TYPE_VORBIS_COMMENT = 4,  // Vorbis-style comments (tags)
    FLAC_METADATA_TYPE_CUESHEET = 5,        // CD cuesheet information
    FLAC_METADATA_TYPE_PICTURE = 6,         // Embedded album art or pictures
    FLAC_METADATA_TYPE_INVALID = 127        // Invalid metadata type
};

/// @brief Container for a decoded FLAC metadata block
///
/// Owns its data buffer via FLAC_MALLOC/FLAC_FREE for consistent PSRAM routing on ESP32.
/// Move-only: copy construction and assignment are deleted.
struct FLACMetadataBlock {
    FLACMetadataType type{FLAC_METADATA_TYPE_INVALID};  // Type of metadata block
    uint32_t length{0};                                 // Length of data in bytes
    uint8_t* data{nullptr};                             // Raw metadata block data (FLAC_MALLOC'd)

    ~FLACMetadataBlock();
    FLACMetadataBlock() = default;
    FLACMetadataBlock(FLACMetadataBlock&& other) noexcept;
    FLACMetadataBlock& operator=(FLACMetadataBlock&& other) noexcept;
    FLACMetadataBlock(const FLACMetadataBlock&) = delete;
    FLACMetadataBlock& operator=(const FLACMetadataBlock&) = delete;
};

/// @brief Stream information from the FLAC STREAMINFO metadata block
///
/// Stores the raw 34-byte STREAMINFO block and provides computed accessors.
/// This avoids duplicating data between parsed fields and raw storage, and
/// reduces struct size from ~44 bytes (with alignment) to exactly 34 bytes.
///
/// Raw STREAMINFO layout (34 bytes, big-endian):
///   [0..1]   min_block_size  (16 bits)
///   [2..3]   max_block_size  (16 bits)
///   [4..6]   min_frame_size  (24 bits)
///   [7..9]   max_frame_size  (24 bits)
///   [10..12] sample_rate     (20 bits) | num_channels-1 (3 bits) | bits_per_sample-1 (5 bits)
///   [13..17] total_samples   (36 bits, upper 4 bits in byte 13)
///   [18..33] MD5 signature   (128 bits)
class FLACStreamInfo {
    friend class FLACDecoder;

    static constexpr size_t RAW_SIZE = 34;  // Size of raw STREAMINFO block
    uint8_t raw_[RAW_SIZE]{};               // Raw STREAMINFO bytes

public:
    uint32_t min_block_size() const {
        return (static_cast<uint32_t>(raw_[0]) << 8) | raw_[1];
    }
    uint32_t max_block_size() const {
        return (static_cast<uint32_t>(raw_[2]) << 8) | raw_[3];
    }
    // NOLINTBEGIN(readability-magic-numbers) -- byte offsets per FLAC STREAMINFO spec
    uint32_t sample_rate() const {
        return (static_cast<uint32_t>(raw_[10]) << 12) | (static_cast<uint32_t>(raw_[11]) << 4) |
               (raw_[12] >> 4);
    }
    uint32_t num_channels() const {
        return ((raw_[12] >> 1) & 0x07) + 1;
    }
    uint32_t bits_per_sample() const {
        return static_cast<uint32_t>(((raw_[12] & 0x01) << 4) | (raw_[13] >> 4)) + 1;
    }
    uint32_t bytes_per_sample() const {
        return (bits_per_sample() + 7) / 8;
    }
    uint64_t total_samples_per_channel() const {
        return (static_cast<uint64_t>(raw_[13] & 0x0F) << 32) |
               (static_cast<uint64_t>(raw_[14]) << 24) | (static_cast<uint64_t>(raw_[15]) << 16) |
               (static_cast<uint64_t>(raw_[16]) << 8) | raw_[17];
    }
    const uint8_t* md5_signature() const {
        return raw_ + 18;
    }
    // NOLINTEND(readability-magic-numbers)

    /// @brief Check if the STREAMINFO has been populated with valid data
    ///
    /// Before FLAC_DECODER_HEADER_READY, accessors like num_channels() and bits_per_sample()
    /// return non-zero values (1) due to the FLAC spec's +1 encoding of zero-initialized fields.
    /// Use this method to verify the STREAMINFO has been parsed before relying on its values.
    bool is_valid() const {
        return sample_rate() != 0;
    }
};

// ============================================================================
// FLACDecoder
// ============================================================================

/**
 * @brief FLAC audio decoder optimized for ESP32
 *
 * Supports both native FLAC and Ogg FLAC containers with automatic format detection.
 * The unified decode() method handles header parsing, frame decoding, and container
 * demuxing in a single streaming API.
 *
 * Usage:
 * 1. Create a FLACDecoder instance
 * 2. (Optional) Configure metadata limits and CRC checking
 * 3. Call decode() in a loop, feeding input data and receiving PCM output
 *
 * The decoder auto-detects the container format from the first 4 bytes:
 * - "fLaC" → native FLAC stream
 * - "OggS" → Ogg FLAC container
 *
 * @code
 * FLACDecoder decoder;
 * uint8_t* output = nullptr;
 * size_t output_size = 0;
 *
 * while (has_data) {
 *     size_t consumed = 0, samples = 0;
 *     auto result = decoder.decode(input, input_len, output, output_size, consumed, samples);
 *     input += consumed; input_len -= consumed;
 *
 *     if (result == FLAC_DECODER_HEADER_READY) {
 *         auto& info = decoder.get_stream_info();
 *         output_size = info.max_block_size() * info.num_channels()
 *                     * info.bytes_per_sample();
 *         output = new uint8_t[output_size];
 *     } else if (result == FLAC_DECODER_SUCCESS) {
 *         // Process 'samples' decoded samples in output buffer
 *     } else if (result == FLAC_DECODER_NEED_MORE_DATA) {
 *         // Feed more input data
 *     } else if (result == FLAC_DECODER_END_OF_STREAM) {
 *         break;
 *     }
 * }
 * @endcode
 */
class FLACDecoder {
public:
    // ========================================
    // Lifecycle
    // ========================================

    FLACDecoder();

    ~FLACDecoder();

    FLACDecoder(const FLACDecoder&) = delete;
    FLACDecoder& operator=(const FLACDecoder&) = delete;
    FLACDecoder(FLACDecoder&&) = delete;
    FLACDecoder& operator=(FLACDecoder&&) = delete;

    /// @brief Reset the decoder to its initial state, ready to decode a new stream
    ///
    /// Resets all decode state (container detection, header parsing, frame decoding,
    /// subframe state, LPC state, residual state, Rice state, bit buffer, stream info,
    /// and metadata blocks). The `block_samples_` working buffer is freed so the decoder
    /// can reallocate it at the correct size for the new stream. Any allocated Ogg demuxer
    /// is also freed.
    ///
    /// User configuration is preserved across the reset:
    /// - CRC checking enable/disable (set via set_crc_check_enabled())
    /// - Per-type metadata size limits (set via set_max_metadata_size())
    ///
    /// After this call the decoder is in the same state as a freshly constructed
    /// FLACDecoder, except that the preserved configuration above is retained.
    void reset();

    // ========================================
    // Core Decoding API
    // ========================================

    /// @brief Decode FLAC audio from a streaming input (native byte packing)
    ///
    /// Auto-detects container format (native FLAC or Ogg FLAC) from the first 4 bytes.
    /// Call repeatedly in a loop, feeding input data. The decoder handles header parsing,
    /// metadata extraction, and frame decoding internally.
    ///
    /// Output samples use the stream's native byte packing (e.g., 2 bytes for 16-bit,
    /// 3 bytes for 24-bit). Use the int32_t* overload for uniform 32-bit output.
    ///
    /// @param input Pointer to input data buffer
    /// @param input_len Number of bytes available in input buffer
    /// @param output Pointer to output buffer for PCM samples (may be nullptr before HEADER_READY)
    /// @param output_size_bytes Size of the output buffer in bytes
    /// @param bytes_consumed [out] Number of input bytes consumed by this call
    /// @param samples_decoded [out] Number of total interleaved samples decoded (all channels)
    /// @return FLAC_DECODER_HEADER_READY when header is complete (allocate output buffer now)
    ///         FLAC_DECODER_SUCCESS when a frame was decoded
    ///         FLAC_DECODER_NEED_MORE_DATA when more input is needed
    ///         FLAC_DECODER_END_OF_STREAM when decoding is complete
    ///         Negative error code on failure (call reset() before decoding a new stream)
    ///
    /// @note Error recovery: After a negative error code during audio decoding (after
    ///       HEADER_READY), the decoder resets its internal frame state so that the next
    ///       call begins parsing a new frame header from the current input position.
    ///       The caller is responsible for advancing the input pointer past corrupted
    ///       data before retrying. However, errors during container
    ///       detection or header parsing (before HEADER_READY) are fatal. The decoder
    ///       enters an error state and will return the same error code on every subsequent
    ///       call until reset() is called. To decode a different stream, always call
    ///       reset() first.
    FLACDecoderResult decode(const uint8_t* input, size_t input_len, uint8_t* output,
                             size_t output_size_bytes, size_t& bytes_consumed,
                             size_t& samples_decoded);

    /// @brief Decode FLAC audio from a streaming input (32-bit output)
    ///
    /// Same as the uint8_t* overload, but outputs all samples as 32-bit left-justified
    /// (MSB-aligned) values regardless of the original bit depth. For example, 24-bit
    /// audio is shifted left by 8 bits, 16-bit by 16 bits, etc. This simplifies
    /// downstream processing on embedded devices by providing a uniform sample format.
    ///
    /// @param input Pointer to input data buffer
    /// @param input_len Number of bytes available in input buffer
    /// @param output Pointer to int32_t output buffer (may be nullptr before HEADER_READY)
    /// @param output_size_samples Size of the output buffer in samples (number of int32_t elements,
    ///        all channels; e.g., stereo block_size=4096 → 8192 samples)
    /// @param bytes_consumed [out] Number of input bytes consumed by this call
    /// @param samples_decoded [out] Number of total interleaved samples decoded (all channels)
    /// @return Same result codes as the uint8_t* overload
    FLACDecoderResult decode(const uint8_t* input, size_t input_len, int32_t* output,
                             size_t output_size_samples, size_t& bytes_consumed,
                             size_t& samples_decoded);

    // ========================================
    // Stream Information
    // ========================================

    /// @brief Get stream information from the FLAC STREAMINFO metadata block
    ///
    /// Only valid after decode() returns FLAC_DECODER_HEADER_READY. Before that, fields like
    /// num_channels() and bits_per_sample() return invalid values. Use
    /// get_stream_info().is_valid() to check.
    ///
    /// @return Reference to the FLACStreamInfo struct
    const FLACStreamInfo& get_stream_info() const {
        return this->stream_info_;
    }

    // ========================================
    // Output Buffer Helpers
    // ========================================

    /// @brief Get required output buffer size in samples (max_block_size * num_channels)
    /// @return Output buffer size in total samples (all channels)
    uint32_t get_output_buffer_size_samples() const {
        return this->stream_info_.max_block_size() * this->stream_info_.num_channels();
    }

    // ========================================
    // Metadata Access
    // ========================================

    /// @brief Get all decoded metadata blocks
    /// @return Vector of all metadata blocks parsed from the file
    const std::vector<FLACMetadataBlock>& get_metadata_blocks() const {
        return this->metadata_blocks_;
    }

    /// @brief Get the first metadata block matching a given type
    ///
    /// Only returns the first match. For types that can appear multiple times (e.g.,
    /// FLAC_METADATA_TYPE_PICTURE), use get_metadata_blocks() and filter manually.
    ///
    /// @param type The metadata type to find (e.g., FLAC_METADATA_TYPE_PICTURE for album art)
    /// @return Pointer to the first matching metadata block, or nullptr if not found
    const FLACMetadataBlock* get_metadata_block(FLACMetadataType type) const {
        for (const auto& block : this->metadata_blocks_) {
            if (block.type == type) {
                return &block;
            }
        }
        return nullptr;
    }

    // ========================================
    // Configuration
    // ========================================

    /// @brief Set maximum metadata block size for a specific type
    ///
    /// Controls which metadata blocks are stored during header parsing. Blocks larger
    /// than the specified size will be skipped to save memory. Set to 0 to skip that
    /// metadata type entirely.
    ///
    /// @note Must be called before the first call to decode(). Has no effect once
    ///       header parsing has begun.
    ///
    /// @param type The metadata type (e.g., FLAC_METADATA_TYPE_VORBIS_COMMENT)
    /// @param max_size Maximum size in bytes (0 = skip this type)
    void set_max_metadata_size(FLACMetadataType type, uint32_t max_size);

    /// @brief Get current maximum metadata block size for a specific type
    /// @param type The metadata type
    /// @return Maximum size in bytes (0 = this type is skipped)
    uint32_t get_max_metadata_size(FLACMetadataType type) const;

    /// @brief Enable or disable CRC checking
    ///
    /// When enabled, frame header CRC-8 and frame data CRC-16 values are validated.
    /// Disabling can improve performance but may allow corrupted data to go undetected.
    ///
    /// @param enabled true to enable CRC checks (default), false to disable
    void set_crc_check_enabled(bool enabled) {
        this->enable_crc_check_ = enabled;
    }

    /// @brief Get current CRC checking state
    /// @return true if CRC checking is enabled, false otherwise
    bool is_crc_check_enabled() const {
        return this->enable_crc_check_;
    }

private:
    // ========================================
    // Private Types
    // ========================================

    /// @brief Container type for auto-detection
    enum class ContainerType : uint8_t { UNKNOWN, NATIVE_FLAC, OGG_FLAC };

    /// @brief Decode phase state machine
    enum class DecodePhase : uint8_t { DETECT_CONTAINER, HEADER, AUDIO, DONE, ERROR };

    /// @brief Decode stage for incremental streaming state machine
    enum class FrameDecodeStage : uint8_t {
        IDLE,          // No frame in progress
        FRAME_HEADER,  // Accumulating/parsing header (~5-16 bytes)
        SUBFRAME,      // Decoding subframes (dispatches to sub-stages)
        FRAME_FOOTER,  // Reading + discarding CRC-16 (2 bytes)
    };

    /// @brief Substage within subframe decoding
    enum class SubframeDecodeStage : uint8_t {
        SUBFRAME_HEADER,           // Reading subframe type + wasted bits
        SUBFRAME_CONSTANT,         // Reading constant value
        SUBFRAME_VERBATIM,         // Reading verbatim samples
        SUBFRAME_WARMUP,           // Reading warm-up samples (fixed/LPC)
        LPC_PARAMS,                // Reading LPC precision, shift, coefficients
        RESIDUAL_HEADER,           // Reading residual method + partition order
        RESIDUAL_PARTITION_PARAM,  // Reading partition Rice parameter
        RESIDUAL_ESCAPE_BITS,      // Reading 5-bit escape sample size
        RESIDUAL_SAMPLES,          // Decoding rice/escape samples
    };

    // ========================================
    // Private Structs
    // ========================================

    struct HeaderParseState {
        uint8_t* data{nullptr};         // Accumulated data for current block (FLAC_MALLOC'd)
        uint32_t data_capacity{0};      // Allocated size of data buffer
        uint32_t type{0};               // Type of current metadata block being read
        uint32_t length{0};             // Total length of current metadata block
        uint32_t bytes_read{0};         // Bytes read so far for current block
        uint8_t block_header_buf[4]{};  // Accumulator for partial metadata block headers
        uint8_t block_header_len{0};    // Bytes accumulated in block_header_buf (0-4)
        bool in_progress{false};        // In middle of reading header
        bool last_block{false};         // Current metadata block is the last one

        ~HeaderParseState();
        HeaderParseState() = default;
        HeaderParseState(HeaderParseState&& other) noexcept;
        HeaderParseState& operator=(HeaderParseState&& other) noexcept;
        HeaderParseState(const HeaderParseState&) = delete;
        HeaderParseState& operator=(const HeaderParseState&) = delete;
    };

    struct FrameState {
        size_t block_samples_offset{0};  // Offset into block_samples_
        uint8_t header_buffer[16]{};     // Internal buffer for frame header accumulation
        uint16_t running_crc16{0};       // Running CRC-16 accumulated across decode_frame calls
        FrameDecodeStage stage{FrameDecodeStage::IDLE};  // Current decode stage
        uint8_t channel_idx{0};                          // Current channel being decoded
        uint8_t header_buffer_len{0};                    // Bytes accumulated in header buffer
        bool resuming{false};        // Whether resuming a partial subframe decode
        bool footer_pending{false};  // CRC-16 read is pending from previous call
        bool wide_side{false};       // 32-bit joint stereo: side channel uses int64_t path
    };

    struct SubframeState {
        size_t sample_idx{0};         // Sample index (verbatim/warmup progress)
        uint32_t shift{0};            // Wasted bits shift
        uint32_t bits_per_sample{0};  // Effective sample depth
        SubframeDecodeStage stage{SubframeDecodeStage::SUBFRAME_HEADER};  // Substage
        uint8_t type{0};                                                  // Subframe type code
        bool wasted_pending{false};  // Resuming mid-wasted-bits-loop
    };

    struct LpcState {
        int32_t coefs[32]{};    // LPC coefficients
        uint32_t order{0};      // Prediction order
        uint32_t precision{0};  // Coefficient precision
        int32_t shift{0};       // Quantization shift
        uint32_t coef_idx{0};   // Coefficient read progress
    };

    struct ResidualState {
        size_t out_ptr_offset{0};  // Write position in sub_frame_buffer
        uint32_t partition_order{0};
        uint32_t num_partitions{0};
        uint32_t partition_idx{0};    // Current partition
        uint32_t partition_count{0};  // Samples in current partition
        uint32_t sample_idx{0};       // Sample within partition
        uint32_t param{0};            // Current Rice parameter
        uint32_t escape_bits{0};      // Bits per escape sample
        uint8_t param_bits{0};        // 4 or 5
        uint8_t escape_param{0};      // 0xF or 0x1F
        bool is_escape{false};        // Escape partition flag
    };

    struct RiceState {
        uint32_t unary_count{0};     // Accumulated unary count
        bool binary_pending{false};  // Unary done, binary read pending
        bool pending{false};         // A rice read was interrupted and needs resume
    };

    // ========================================
    // Decode Pipeline
    // ========================================

    /// @brief Internal decode implementation with explicit 32-bit output flag
    FLACDecoderResult decode_impl(const uint8_t* input, size_t input_len, uint8_t* output,
                                  size_t output_size, size_t& bytes_consumed,
                                  size_t& samples_decoded, bool output_32bit);

    /// @brief Native FLAC decode path
    FLACDecoderResult decode_native(const uint8_t* input, size_t input_len, uint8_t* output,
                                    size_t output_size, size_t& bytes_consumed,
                                    size_t& samples_decoded, bool output_32bit);

#ifndef MICRO_FLAC_DISABLE_OGG
    /// @brief Ogg FLAC decode path
    FLACDecoderResult decode_ogg(const uint8_t* input, size_t input_len, uint8_t* output,
                                 size_t output_size, size_t& bytes_consumed,
                                 size_t& samples_decoded, bool output_32bit);
#endif

    FLACDecoderResult read_header(const uint8_t* buffer, size_t buffer_length,
                                  size_t& bytes_consumed);

    FLACDecoderResult decode_frame(const uint8_t* buffer, size_t buffer_length,
                                   uint8_t* output_buffer, uint32_t* num_samples,
                                   bool output_32bit);

    void reset_frame_state();

    // ========================================
    // Streaming State Machine
    // ========================================

    /// @brief Process frame header phase (accumulation, parsing, and initial decode attempt)
    FLACDecoderResult decode_frame_header_phase(const uint8_t* buffer, size_t buffer_length);

    /// @brief Process subframe decode phase (resume from previous call)
    FLACDecoderResult decode_frame_subframe_phase(const uint8_t* buffer, size_t buffer_length);

    /// @brief Process frame footer phase (alignment + CRC-16)
    FLACDecoderResult decode_frame_footer_phase(const uint8_t* buffer, size_t buffer_length);

    /// @brief Decode all subframes, resuming from saved state if s_resuming_ is set
    FLACDecoderResult decode_subframes(uint32_t block_size, uint32_t bits_per_sample,
                                       uint32_t channel_assignment);

    /// @brief Decode a single subframe (templated on sample type)
    /// SampleT = int32_t for normal channels, int64_t for 33-bit MID_SIDE side channel
    template <typename SampleT>
    FLACDecoderResult decode_subframe_impl(uint32_t block_size, uint32_t bits_per_sample,
                                           size_t block_samples_offset);

    /// @brief Decode Rice-coded residuals, resuming from saved state if applicable
    /// OutputT = int32_t for normal channels, int64_t for wide side channel
    template <typename OutputT>
    FLACDecoderResult decode_residuals(OutputT* buffer, uint32_t warm_up_samples,
                                       uint32_t block_size);

    /// @brief Read partition parameter and escape bits, advancing stage accordingly
    FLACDecoderResult read_partition_param(uint32_t block_size, uint32_t warm_up_samples);

    /// @brief Read Rice-coded signed integer
    /// @tparam Resuming  false = fresh read (hot path), true = resume after out-of-data
    template <bool Resuming>
    inline int32_t read_rice_sint(uint8_t param);

    /// @brief Drain remaining unconsumed bytes from user buffer into bit_buffer_
    void drain_remaining_to_bit_buffer();

    // ========================================
    // Bit Stream Reading
    // ========================================

    /// @brief Refill bit buffer from input stream
    inline bool refill_bit_buffer();

    /// @brief Read unsigned integer of specified bit width
    inline uint32_t read_uint(uint8_t num_bits);

    /// @brief Read signed integer of specified bit width (two's complement)
    /// SampleT = int32_t for normal channels (truncates 33-bit), int64_t for wide side channel
    template <typename SampleT>
    inline SampleT read_sint_t(uint8_t num_bits);

    /// @brief Reset bit buffer state and adjust buffer pointers (must be byte-aligned)
    void reset_bit_buffer();

    // ========================================
    // Internal Utilities
    // ========================================

    /// @brief Set up buffer context for reading
    /// @param buf Pointer to input buffer
    /// @param len Length of buffer in bytes
    /// @param reset_bits Whether to reset bit buffer state (default true)
    void set_buffer(const uint8_t* buf, size_t len, bool reset_bits = true);

    /// @brief Free allocated buffers (called by destructor)
    void free_buffers();

    /// @brief Transition to unrecoverable error state, latching the error code
    FLACDecoderResult set_fatal_error(FLACDecoderResult error) {
        this->decode_phase_ = DecodePhase::ERROR;
        this->latched_error_ = error;
        return error;
    }

    /// @brief Get required output buffer size in bytes (accounts for 32-bit mode)
    uint32_t get_output_buffer_size_bytes(bool output_32bit) const {
        uint32_t bytes_per_sample = output_32bit ? 4 : this->stream_info_.bytes_per_sample();
        return this->stream_info_.max_block_size() * this->stream_info_.num_channels() *
               bytes_per_sample;
    }

    size_t get_bytes_index() const {
        return this->buffer_index_;
    }

    // ========================================
    // Member Variables
    // ========================================

    // Input buffer state
    const uint8_t* buffer_{nullptr};  // Pointer to current input buffer
    size_t buffer_index_{0};          // Current position in input buffer
    size_t bytes_left_{0};            // Bytes remaining in input buffer

    // Current frame state
    uint32_t curr_frame_block_size_{0};       // Block size of current frame
    uint32_t curr_frame_channel_assign_{0};   // Channel assignment of current frame
    uint32_t curr_frame_bits_per_sample_{0};  // Bits per sample of current frame

    // Decode buffers
    int32_t* block_samples_{nullptr};  // Working buffer for decoded samples (all channels)
    int64_t* side_subframe_{nullptr};  // Wide buffer for 33-bit MID_SIDE side channel (lazy)

    // Bit buffer state
    bit_buffer_t bit_buffer_{
        0};  // Bit buffer for bit-level reading (32-bit on Xtensa, 64-bit on host)
    uint8_t bit_buffer_length_{0};  // Number of valid bits in bit_buffer_

    // Decoder flags
    bool out_of_data_{false};      // Flag indicating end of input data reached
    bool enable_crc_check_{true};  // Flag to enable/disable CRC validation

    // Container detection state
    ContainerType container_type_{ContainerType::UNKNOWN};
    DecodePhase decode_phase_{DecodePhase::DETECT_CONTAINER};
    FLACDecoderResult latched_error_{FLAC_DECODER_SUCCESS};  // Stored error for ERROR phase

    // Stream properties (from STREAMINFO)
    FLACStreamInfo stream_info_{};

    // Buffer for container detection. Holds up to 4 "fLaC"/"OggS" magic bytes during detection.
    // For native FLAC, those 4 bytes are fed directly to read_header(), which accumulates
    // the rest of the header incrementally without needing further buffering here.
    static constexpr uint8_t DETECT_BUFFER_SIZE = 4;
    uint8_t detect_buffer_[DETECT_BUFFER_SIZE]{};
    uint8_t detect_buffer_len_{0};
    bool detect_buffer_fed_{false};  // Whether detect_buffer_ has been fed to read_header()

#ifndef MICRO_FLAC_DISABLE_OGG
    // Ogg state
    std::unique_ptr<micro_ogg::OggDemuxer> ogg_demuxer_;
    uint8_t ogg_bos_prefix_consumed_{0};  // 0-9 tracks BOS prefix validation progress
    bool ogg_bos_processed_{false};
    bool ogg_eos_seen_{false};  // EOS flag from demuxer
#endif

    // Metadata storage
    std::vector<FLACMetadataBlock> metadata_blocks_;

    // Maximum size limits for each metadata type, indexed by FLACMetadataType enum value (0-6)
    // Index 7 is used for unknown/other types (types > 6)
    static constexpr size_t METADATA_SIZE_LIMITS_COUNT = 8;      // Types 0-6 plus unknown (index 7)
    uint32_t max_metadata_sizes_[METADATA_SIZE_LIMITS_COUNT]{};  // Maximum size limits for each
                                                                 // metadata type

    // Header parsing state
    HeaderParseState header_parse_{};

    // Streaming decode state machine
    FrameState frame_{};
    SubframeState subframe_{};
    LpcState lpc_{};
    ResidualState residual_{};
    RiceState rice_{};
};

}  // namespace micro_flac
