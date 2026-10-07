// Copyright 2025 Kevin Ahrendt
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

/* microOggDemuxer - Lightweight Ogg Container Demuxer
 * Implements RFC 3533 Ogg page demuxing
 *
 * Platform-agnostic, zero dependencies
 */

#ifndef MICRO_OGG_DEMUXER_H
#define MICRO_OGG_DEMUXER_H

#include <cstddef>
#include <cstdint>

namespace micro_ogg {

/**
 * @brief Ogg page header structure (per RFC 3533)
 */
struct OggPageHeader {
    uint8_t capture_pattern[4];  // "OggS"
    uint8_t version;             // Stream structure version (0x00)
    uint8_t header_type;         // Bitfield (continuation, bos, eos)
    uint64_t granule_position;   // Codec-specific position info
    uint32_t stream_serial;      // Logical bitstream serial number
    uint32_t page_sequence;      // Page sequence number
    uint32_t checksum;           // CRC checksum
    uint8_t segment_count;       // Number of segments in page
};

/**
 * @brief Header type flags
 */
enum OggHeaderType : uint8_t {
    OGG_CONTINUED_PACKET = 0x01,     // Packet continued from previous page
    OGG_BEGINNING_OF_STREAM = 0x02,  // First page of logical bitstream
    OGG_END_OF_STREAM = 0x04         // Last page of logical bitstream
};

/**
 * @brief Result codes for Ogg demuxing
 */
enum OggDemuxResult : int8_t {
    // Success codes
    OGG_OK = 0,              // Success
    OGG_NEED_MORE_DATA = 1,  // Need more data to complete page
    OGG_PACKET_SKIPPED = 2,  // Packet was skipped (too large to buffer)

    // Format errors (potentially recoverable)
    OGG_INVALID_CAPTURE = -1,  // Invalid "OggS" capture pattern
    OGG_INVALID_VERSION = -2,  // Unsupported version
    OGG_CRC_FAILED = -3,       // CRC checksum validation failed

    // Stream structure errors
    OGG_STREAM_SEQUENCE_ERROR = -4,   // Page sequence number mismatch
    OGG_STREAM_BOS_ERROR = -5,        // BOS flag violation (invalid placement)
    OGG_STREAM_EOS_ERROR = -6,        // EOS flag violation (EOS with continued packet)
    OGG_STREAM_SERIAL_MISMATCH = -7,  // New stream serial (concatenated stream)

    OGG_STREAM_CONTINUATION_ERROR = -8,  // Continued flag inconsistent with previous page

    // Resource errors
    OGG_ALLOCATION_FAILED = -9  // Memory allocation failed
};

/**
 * @brief Ogg packet data structure
 *
 * Contains packet payload and metadata. The data pointer may point to
 * either the input buffer (zero-copy) or internal demuxer buffer.
 */
struct OggPacket {
    const uint8_t* data;       // Packet data (may point to input OR internal buffer)
    size_t length;             // Packet length in bytes
    int64_t granule_position;  // Granule position from page header (codec-specific)
    bool is_bos;               // Beginning of stream flag
    bool is_eos;               // End of stream flag
    bool is_last_on_page;      // Last packet completing on current page
    bool is_end_of_packet;     // True when this data reaches a packet boundary (streaming mode)
};

/**
 * @brief Demuxer state after get_next_packet() call
 *
 * Contains both the result code and packet data (when result == OGG_OK).
 * The packet field is only valid when result == OGG_OK.
 */
struct OggDemuxState {
    OggDemuxResult result;  // Demux result code
    size_t bytes_consumed;  // Number of input bytes consumed
    OggPacket packet;       // Packet data (only valid if result == OGG_OK)
};

/**
 * @brief Configuration for OggDemuxer
 *
 * Controls memory allocation behavior and buffer sizes.
 *
 * Custom Allocator Requirements:
 * - alloc: Must return nullptr on failure (like malloc)
 * - realloc: Must return nullptr on failure without freeing original ptr (like realloc)
 * - free: Must handle nullptr gracefully (like free)
 * - If alloc is provided, free must also be provided (and vice versa)
 * - If only some callbacks are set, they will be ignored and standard malloc/free used
 */
struct OggDemuxerConfig {
    size_t min_buffer_size = 1024;
    size_t max_buffer_size = 8192;  // Conservative default
    bool enable_crc = false;

    // Memory callbacks - nullptr means use malloc/free/realloc
    void* (*alloc)(size_t size) = nullptr;
    void* (*realloc)(void* ptr, size_t size) = nullptr;
    void (*free)(void* ptr) = nullptr;
};

/**
 * @brief Ogg container demuxer
 *
 * Demuxes Ogg container pages and extracts packets.
 * Handles page boundaries and packet reassembly.
 *
 * Thread Safety:
 * - Each OggDemuxer instance must be used from a single thread only
 * - For concurrent demuxing, create separate OggDemuxer instances per thread
 * - The demuxer maintains internal state that is not thread-safe
 *
 * Zero-copy design:
 * - Packets are returned as zero-copy pointers when they fit entirely within the input buffer
 * - Multi-segment packets don't require copies since segments are stored contiguously
 * - Internal buffering only occurs when packets span page or input buffer boundaries
 *
 * Memory allocation:
 * - page_header_staging_ (282 bytes): Inline fixed buffer for header accumulation and segment
 *   table storage
 * - internal_buffer_: Packet assembly buffer, allocated lazily on first call to
 *   get_next_packet() (starts at min_buffer_size, grows as needed)
 * - internal_buffer_ uses configurable allocator (malloc/free by default)
 * - Once allocated, internal_buffer_ persists for the lifetime of the object
 * - reset() does not deallocate buffers, only resets demuxer state
 */
class OggDemuxer {
public:
    /**
     * @brief Construct OggDemuxer with configurable settings
     *
     * Note: The internal packet assembly buffer is NOT allocated in constructor.
     * It will be allocated lazily on first call to get_next_packet().
     *
     * The buffer starts at config.min_buffer_size and grows dynamically up to
     * config.max_buffer_size as needed. Typical audio packets fit within the
     * minimum buffer size; the dynamic growth supports larger packets when encountered.
     *
     * @param config Configuration struct (uses defaults if not specified)
     *
     * @note CRC Validation Limitation: Due to the zero-copy architecture, packets
     *       are returned as soon as they are found. CRC validation only occurs when
     *       the final packet on a page is ready - if validation fails, that packet
     *       returns OGG_CRC_FAILED, but earlier packets from the same page were already
     *       returned with OGG_OK. If you enable CRC, you must buffer packet data (copying
     *       it, since the data pointer is only valid until the next get_next_packet() call)
     *       and defer processing until is_last_on_page is true, then discard all buffered
     *       packets if OGG_CRC_FAILED is returned.
     */
    OggDemuxer(const OggDemuxerConfig& config = OggDemuxerConfig{});
    ~OggDemuxer();

    // Prevent copying (would cause double-free of owned pointers)
    OggDemuxer(const OggDemuxer&) = delete;
    OggDemuxer& operator=(const OggDemuxer&) = delete;

    /**
     * @brief Demux data and extract the next complete packet
     *
     * Zero-copy design: packet.data may point directly to user's input buffer
     * when the packet is complete and doesn't require reassembly. Otherwise, it
     * points to an internal buffer. The pointer is only valid until the next call.
     *
     * @param input Input data pointer
     * @param input_len Available input data length
     * @return OggDemuxState containing result, bytes consumed, and packet data
     *
     * Usage:
     * @code
     * OggDemuxState state = demuxer.get_next_packet(input, input_len);
     * if (state.result == OGG_OK) {
     *     // Use state.packet.data, state.packet.length, etc.
     * }
     * input += state.bytes_consumed;
     * input_len -= state.bytes_consumed;
     * @endcode
     */
    OggDemuxState get_next_packet(const uint8_t* input, size_t input_len);

    /**
     * @brief Get next raw body data from the Ogg stream (streaming mode)
     *
     * Strips Ogg framing and offers raw body bytes as a zero-copy pointer,
     * capped at the current packet boundary. No internal buffering is performed.
     * No heap allocation is performed (only the inline header staging buffer is used).
     * Segment tracking, CRC accumulation,
     * and page finalization are handled automatically.
     *
     * When is_end_of_packet is true, the offered data reaches a packet boundary,
     * making it safe to switch to get_next_packet() after consuming.
     *
     * @param input Input data pointer
     * @param input_len Available input data length
     * @return OggDemuxState with result, bytes_consumed (header + body bytes), and packet data
     *
     * Usage:
     * @code
     * OggDemuxState state = demuxer.get_next_data(input, len);
     * if (state.result == OGG_OK) {
     *     // Use packet.data before advancing input (it points into the input buffer)
     *     decoder.decode(state.packet.data, state.packet.length);
     * }
     * input += state.bytes_consumed;
     * len -= state.bytes_consumed;
     * @endcode
     */
    OggDemuxState get_next_data(const uint8_t* input, size_t input_len);

    /**
     * @brief Reset demuxer state
     */
    void reset();

    /**
     * @brief Get the current page's continued packet flag state
     *
     * This returns whether the current page has the OGG_CONTINUED_PACKET flag set.
     * Codec-specific wrappers can use this to enforce codec-specific validation rules.
     *
     * @return true if current page has continued packet flag set, false otherwise
     * @note Only valid after a successful call to get_next_packet() that returned a packet
     */
    bool current_page_has_continued_flag() const;

    /**
     * @brief Get whether the previous page ended with a continued packet
     *
     * This returns whether the previous page ended with a lacing value of 255,
     * indicating that a packet continues on the next page.
     *
     * @return true if previous page ended with lacing value 255, false otherwise
     */
    bool previous_page_ended_with_continued_packet() const;

#ifdef MICRO_OGG_DEMUXER_DEBUG
    /**
     * @brief Get debug state information
     */
    void get_debug_state(int& state, bool& assembling, bool& skipping, size_t& packet_size,
                         size_t& body_consumed, uint8_t& seg_index, uint8_t& seg_count) const {
        state = static_cast<int>(state_);
        assembling = assembling_packet_;
        skipping = skipping_packet_;
        packet_size = packet_assembly_size_;
        body_consumed = page_body_bytes_consumed_;
        seg_index = current_segment_index_;
        seg_count = current_page_.segment_count;
    }

    /**
     * @brief Get zero-copy statistics
     * @param zero_copy_count Output: number of packets returned via zero-copy
     * @param buffered_count Output: number of packets that required buffering
     */
    void get_stats(size_t& zero_copy_count, size_t& buffered_count) const {
        zero_copy_count = zero_copy_packets_;
        buffered_count = buffered_packets_;
    }

    /**
     * @brief Get buffer statistics
     * @param current_capacity Output: current internal buffer capacity in bytes
     * @param peak_capacity Output: peak internal buffer capacity reached in bytes
     */
    void get_buffer_stats(size_t& current_capacity, size_t& peak_capacity) const {
        current_capacity = internal_buffer_capacity_;
        peak_capacity = peak_buffer_capacity_;
    }
#endif  // MICRO_OGG_DEMUXER_DEBUG

private:
    // Information about a packet found in the segment table
    struct PacketInfo {
        size_t size;            // Total size of packet in bytes
        bool complete;          // True if packet ends on this page
        uint8_t segment_count;  // Number of segments in this packet
    };

    // Internal result codes for buffer growth operations
    enum GrowBufferResult : uint8_t {
        GROW_OK,                // Buffer is large enough or was grown successfully
        GROW_EXCEEDS_MAX,       // Requested size exceeds max_buffer_size
        GROW_ALLOCATION_FAILED  // Memory allocation failed
    };

    // Internal result codes for helper method communication
    enum class InternalResult : uint8_t {
        OK,              // Success, continue processing
        NEED_MORE_DATA,  // Need more input data
        PACKET_READY     // Packet is ready to return
    };

    // Parse Ogg page header from raw bytes
    OggDemuxResult parse_page_header(const uint8_t* data, size_t data_len, OggPageHeader& header,
                                     size_t& header_size);

    // Sum segment table lacing values to get total page body size
    size_t calculate_body_size(const uint8_t* segment_table, uint8_t segment_count);

    // Grow internal buffer to accommodate needed_size bytes
    GrowBufferResult grow_buffer(size_t needed_size);

    // Advance segment tracking by specified number of bytes
    void advance_through_segments(size_t bytes_to_advance);

    // Scan segment table from start_segment_index to find next packet boundary
    PacketInfo scan_for_next_packet(uint8_t start_segment_index) const;

    // Return a zero-copy packet directly from input buffer
    void handle_zero_copy_return(const uint8_t* packet_ptr, const PacketInfo& packet_info,
                                 size_t additional_bytes_consumed, OggDemuxState& state);

    // Compare incremental CRC against stored page checksum
    OggDemuxResult validate_page_crc() const;

    // Lazily allocate internal_buffer_ on first use
    bool ensure_buffers_allocated(OggDemuxState& state);

    // Handle STATE_EXPECT_PAGE_HEADER and STATE_ACCUMULATING_PAGE_HEADER
    // When attempt_packet_zero_copy is false, skips the zero-copy packet optimization
    InternalResult handle_page_header(const uint8_t* input, size_t input_len, OggDemuxState& state,
                                      bool attempt_packet_zero_copy = true);

    // Skip packets that exceed max_buffer_size without buffering
    void handle_skipping_packet(const uint8_t* input, size_t input_len, OggDemuxState& state);

    // Buffer and assemble packets spanning pages or input boundaries
    void handle_assembling_packet(const uint8_t* input, size_t input_len, OggDemuxState& state);

    // Attempt zero-copy return, fall back to assembly mode if not possible
    InternalResult handle_zero_copy_path(const uint8_t* input, size_t input_len,
                                         OggDemuxState& state);

    // Return assembled packet from internal_buffer_ with state updates
    void return_assembled_packet(size_t bytes_consumed, OggDemuxState& state);

    // Check if current segment position is at a packet boundary
    bool is_at_packet_boundary() const;

    // Validate CRC and transition to STATE_EXPECT_PAGE_HEADER when page is consumed
    OggDemuxResult finalize_page();

    // Accumulate partial header bytes into page_header_staging_
    InternalResult accumulate_header(const uint8_t* input, size_t input_len, size_t& bytes_added,
                                     OggDemuxState& state);

    // Validate BOS/EOS flags, serial number, and page sequence per RFC 3533
    bool validate_stream_consistency(OggDemuxState& state);

    // Demuxer state
    enum State : uint8_t {
        STATE_EXPECT_PAGE_HEADER,        // Waiting for page header
        STATE_ACCUMULATING_PAGE_HEADER,  // Accumulating partial header
        STATE_PROCESSING_SEGMENTS        // Processing segments from page
    };

    // Member ordering: inline array first (so segment_table_ initializer can reference it),
    // then pointers, size_t, uint64_t, structs, uint32_t, and finally uint8_t/bool

    // Fixed inline buffer for header accumulation (27-byte header + up to 255-byte segment table)
    uint8_t page_header_staging_[282]{};

    // 8-byte aligned: pointers
    uint8_t* segment_table_{page_header_staging_ + 27};  // Points into page_header_staging_ + 27
    uint8_t* internal_buffer_{nullptr};                  // Packet assembly buffer

    // 8-byte aligned: size_t
    size_t page_header_staging_size_{0};
    size_t internal_buffer_capacity_{0};        // Current allocated buffer size
    size_t min_buffer_size_{0};                 // Minimum/initial buffer size
    size_t max_buffer_size_{0};                 // Maximum buffer size limit
    size_t current_segment_bytes_consumed_{0};  // Bytes consumed from current segment
    size_t page_body_bytes_consumed_{0};        // Total bytes consumed from current page body
    size_t page_body_size_{0};                  // Total page body size (cached from segment table)
    size_t packet_assembly_size_{0};            // Size of packet being assembled
    size_t bytes_to_skip_{0};                   // Remaining bytes to skip in current packet

    // 8-byte aligned: uint64_t
    uint64_t granule_position_{0};  // Page-level granule position

    // Structs (contain mixed sizes, place after 8-byte types)
    OggPageHeader current_page_{};
    OggDemuxerConfig config_;

    // 4-byte aligned: uint32_t
    uint32_t stream_serial_{0};
    uint32_t expected_page_sequence_{0};
    uint32_t incremental_crc_{0};  // CRC accumulator for incremental validation

    // 1-byte aligned: uint8_t and bool (grouped to minimize padding)
    State state_{STATE_EXPECT_PAGE_HEADER};
    uint8_t current_segment_index_{0};  // Current segment being processed
    bool assembling_packet_{false};     // True if currently assembling a packet
    bool skipping_packet_{false};       // True if skipping a packet that's too large to buffer
    bool previous_page_ended_with_continued_packet_{false};  // RFC 3533 tracking
    bool stream_initialized_{false};
    bool current_packet_is_bos_{false};
    bool current_packet_is_eos_{false};
    bool bos_flag_used_{false};  // BOS flag only applies to first packet
    bool enable_crc_{false};     // Enable/disable CRC validation

#ifdef MICRO_OGG_DEMUXER_DEBUG
    // Statistics (only enabled with MICRO_OGG_DEMUXER_DEBUG)
    size_t zero_copy_packets_;     // Number of packets returned via zero-copy
    size_t buffered_packets_;      // Number of packets that required buffering
    size_t peak_buffer_capacity_;  // Peak internal buffer capacity reached
#endif
};

}  // namespace micro_ogg

#endif  // MICRO_OGG_DEMUXER_H
