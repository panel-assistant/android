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
 * Implements RFC 3533 Ogg page demuxing with zero-copy optimization.
 * See ogg_demuxer.h for detailed architecture documentation.
 */

#include <micro_ogg/ogg_demuxer.h>

#include <algorithm>
#include <cstdlib>
#include <cstring>

namespace micro_ogg {

// Ogg container constants (RFC 3533)
constexpr size_t OGG_PAGE_HEADER_SIZE = 27;       // Fixed header before segment table
constexpr size_t OGG_SEGMENT_COUNT_OFFSET = 26;   // Offset to segment_count field
constexpr size_t OGG_MAX_HEADER_SIZE = 282;       // 27 + 255 segment table entries
constexpr size_t OGG_MAX_PAGE_BODY_SIZE = 65025;  // 255 segments × 255 bytes
constexpr uint64_t OGG_INVALID_GRANULE_POSITION = 0xFFFFFFFFFFFFFFFFULL;
constexpr uint8_t OGG_MAX_LACING_VALUE = 255;  // Lacing value indicating packet continues

// Ogg page header field offsets (RFC 3533)
constexpr size_t OGG_GRANULE_OFFSET = 6;    // Offset to granule_position field
constexpr size_t OGG_SERIAL_OFFSET = 14;    // Offset to stream_serial field
constexpr size_t OGG_SEQUENCE_OFFSET = 18;  // Offset to page_sequence field
constexpr size_t OGG_CHECKSUM_OFFSET = 22;  // Offset to checksum field

// Little-endian helpers
static inline uint32_t read_le32(const uint8_t* p) {
    return static_cast<uint32_t>(p[0]) | (static_cast<uint32_t>(p[1]) << 8) |
           (static_cast<uint32_t>(p[2]) << 16) | (static_cast<uint32_t>(p[3]) << 24);
}

static inline uint64_t read_le64(const uint8_t* p) {
    return static_cast<uint64_t>(p[0]) | (static_cast<uint64_t>(p[1]) << 8) |
           (static_cast<uint64_t>(p[2]) << 16) | (static_cast<uint64_t>(p[3]) << 24) |
           (static_cast<uint64_t>(p[4]) << 32) | (static_cast<uint64_t>(p[5]) << 40) |
           (static_cast<uint64_t>(p[6]) << 48) | (static_cast<uint64_t>(p[7]) << 56);
}

// CRC-32 lookup table (Ogg/Ethernet polynomial 0x04C11DB7)
static const uint32_t crc_lookup[256] = {
    0x00000000, 0x04c11db7, 0x09823b6e, 0x0d4326d9, 0x130476dc, 0x17c56b6b, 0x1a864db2, 0x1e475005,
    0x2608edb8, 0x22c9f00f, 0x2f8ad6d6, 0x2b4bcb61, 0x350c9b64, 0x31cd86d3, 0x3c8ea00a, 0x384fbdbd,
    0x4c11db70, 0x48d0c6c7, 0x4593e01e, 0x4152fda9, 0x5f15adac, 0x5bd4b01b, 0x569796c2, 0x52568b75,
    0x6a1936c8, 0x6ed82b7f, 0x639b0da6, 0x675a1011, 0x791d4014, 0x7ddc5da3, 0x709f7b7a, 0x745e66cd,
    0x9823b6e0, 0x9ce2ab57, 0x91a18d8e, 0x95609039, 0x8b27c03c, 0x8fe6dd8b, 0x82a5fb52, 0x8664e6e5,
    0xbe2b5b58, 0xbaea46ef, 0xb7a96036, 0xb3687d81, 0xad2f2d84, 0xa9ee3033, 0xa4ad16ea, 0xa06c0b5d,
    0xd4326d90, 0xd0f37027, 0xddb056fe, 0xd9714b49, 0xc7361b4c, 0xc3f706fb, 0xceb42022, 0xca753d95,
    0xf23a8028, 0xf6fb9d9f, 0xfbb8bb46, 0xff79a6f1, 0xe13ef6f4, 0xe5ffeb43, 0xe8bccd9a, 0xec7dd02d,
    0x34867077, 0x30476dc0, 0x3d044b19, 0x39c556ae, 0x278206ab, 0x23431b1c, 0x2e003dc5, 0x2ac12072,
    0x128e9dcf, 0x164f8078, 0x1b0ca6a1, 0x1fcdbb16, 0x018aeb13, 0x054bf6a4, 0x0808d07d, 0x0cc9cdca,
    0x7897ab07, 0x7c56b6b0, 0x71159069, 0x75d48dde, 0x6b93dddb, 0x6f52c06c, 0x6211e6b5, 0x66d0fb02,
    0x5e9f46bf, 0x5a5e5b08, 0x571d7dd1, 0x53dc6066, 0x4d9b3063, 0x495a2dd4, 0x44190b0d, 0x40d816ba,
    0xaca5c697, 0xa864db20, 0xa527fdf9, 0xa1e6e04e, 0xbfa1b04b, 0xbb60adfc, 0xb6238b25, 0xb2e29692,
    0x8aad2b2f, 0x8e6c3698, 0x832f1041, 0x87ee0df6, 0x99a95df3, 0x9d684044, 0x902b669d, 0x94ea7b2a,
    0xe0b41de7, 0xe4750050, 0xe9362689, 0xedf73b3e, 0xf3b06b3b, 0xf771768c, 0xfa325055, 0xfef34de2,
    0xc6bcf05f, 0xc27dede8, 0xcf3ecb31, 0xcbffd686, 0xd5b88683, 0xd1799b34, 0xdc3abded, 0xd8fba05a,
    0x690ce0ee, 0x6dcdfd59, 0x608edb80, 0x644fc637, 0x7a089632, 0x7ec98b85, 0x738aad5c, 0x774bb0eb,
    0x4f040d56, 0x4bc510e1, 0x46863638, 0x42472b8f, 0x5c007b8a, 0x58c1663d, 0x558240e4, 0x51435d53,
    0x251d3b9e, 0x21dc2629, 0x2c9f00f0, 0x285e1d47, 0x36194d42, 0x32d850f5, 0x3f9b762c, 0x3b5a6b9b,
    0x0315d626, 0x07d4cb91, 0x0a97ed48, 0x0e56f0ff, 0x1011a0fa, 0x14d0bd4d, 0x19939b94, 0x1d528623,
    0xf12f560e, 0xf5ee4bb9, 0xf8ad6d60, 0xfc6c70d7, 0xe22b20d2, 0xe6ea3d65, 0xeba91bbc, 0xef68060b,
    0xd727bbb6, 0xd3e6a601, 0xdea580d8, 0xda649d6f, 0xc423cd6a, 0xc0e2d0dd, 0xcda1f604, 0xc960ebb3,
    0xbd3e8d7e, 0xb9ff90c9, 0xb4bcb610, 0xb07daba7, 0xae3afba2, 0xaafbe615, 0xa7b8c0cc, 0xa379dd7b,
    0x9b3660c6, 0x9ff77d71, 0x92b45ba8, 0x9675461f, 0x8832161a, 0x8cf30bad, 0x81b02d74, 0x857130c3,
    0x5d8a9099, 0x594b8d2e, 0x5408abf7, 0x50c9b640, 0x4e8ee645, 0x4a4ffbf2, 0x470cdd2b, 0x43cdc09c,
    0x7b827d21, 0x7f436096, 0x7200464f, 0x76c15bf8, 0x68860bfd, 0x6c47164a, 0x61043093, 0x65c52d24,
    0x119b4be9, 0x155a565e, 0x18197087, 0x1cd86d30, 0x029f3d35, 0x065e2082, 0x0b1d065b, 0x0fdc1bec,
    0x3793a651, 0x3352bbe6, 0x3e119d3f, 0x3ad08088, 0x2497d08d, 0x2056cd3a, 0x2d15ebe3, 0x29d4f654,
    0xc5a92679, 0xc1683bce, 0xcc2b1d17, 0xc8ea00a0, 0xd6ad50a5, 0xd26c4d12, 0xdf2f6bcb, 0xdbee767c,
    0xe3a1cbc1, 0xe760d676, 0xea23f0af, 0xeee2ed18, 0xf0a5bd1d, 0xf464a0aa, 0xf9278673, 0xfde69bc4,
    0x89b8fd09, 0x8d79e0be, 0x803ac667, 0x84fbdbd0, 0x9abc8bd5, 0x9e7d9662, 0x933eb0bb, 0x97ffad0c,
    0xafb010b1, 0xab710d06, 0xa6322bdf, 0xa2f33668, 0xbcb4666d, 0xb8757bda, 0xb5365d03, 0xb1f740b4};

static uint32_t calculate_crc32(const uint8_t* buffer, size_t size, uint32_t crc) {
    while (size >= 8) {
        crc ^= (static_cast<uint32_t>(buffer[0]) << 24) | (static_cast<uint32_t>(buffer[1]) << 16) |
               (static_cast<uint32_t>(buffer[2]) << 8) | buffer[3];
        crc = crc_lookup[(crc >> 24) & 0xff] ^ (crc << 8);
        crc = crc_lookup[(crc >> 24) & 0xff] ^ (crc << 8);
        crc = crc_lookup[(crc >> 24) & 0xff] ^ (crc << 8);
        crc = crc_lookup[(crc >> 24) & 0xff] ^ (crc << 8);

        crc ^= (static_cast<uint32_t>(buffer[4]) << 24) | (static_cast<uint32_t>(buffer[5]) << 16) |
               (static_cast<uint32_t>(buffer[6]) << 8) | buffer[7];
        crc = crc_lookup[(crc >> 24) & 0xff] ^ (crc << 8);
        crc = crc_lookup[(crc >> 24) & 0xff] ^ (crc << 8);
        crc = crc_lookup[(crc >> 24) & 0xff] ^ (crc << 8);
        crc = crc_lookup[(crc >> 24) & 0xff] ^ (crc << 8);

        buffer += 8;
        size -= 8;
    }

    while (size != 0) {
        crc = (crc << 8) ^ crc_lookup[((crc >> 24) & 0xff) ^ *buffer++];
        --size;
    }

    return crc;
}

OggDemuxer::OggDemuxer(const OggDemuxerConfig& config)
    : min_buffer_size_(config.min_buffer_size),
      max_buffer_size_(config.max_buffer_size),
      config_(config),
      enable_crc_(config.enable_crc) {
    // Validate and fix buffer size configuration
    if (min_buffer_size_ == 0) {
        min_buffer_size_ = 1024;  // Safe default
    }
    if (max_buffer_size_ < min_buffer_size_) {
        max_buffer_size_ = min_buffer_size_;
    }

    // Warn/fix inconsistent allocator configuration
    // If only some callbacks are provided, fall back to all standard functions
    bool has_alloc = (config_.alloc != nullptr);
    bool has_free = (config_.free != nullptr);
    if (has_alloc != has_free) {
        // Inconsistent - clear both to use standard malloc/free
        config_.alloc = nullptr;
        config_.realloc = nullptr;
        config_.free = nullptr;
    }

    reset();
}

OggDemuxer::~OggDemuxer() {
    // Use configured free function or standard free
    if (config_.free) {
        if (internal_buffer_) {
            config_.free(internal_buffer_);
        }
    } else {
        if (internal_buffer_) {
            std::free(internal_buffer_);
        }
    }
}

// ==============================================================================
// PUBLIC API: State Management
// ==============================================================================

void OggDemuxer::reset() {
    state_ = STATE_EXPECT_PAGE_HEADER;
    page_header_staging_size_ = 0;
    current_segment_index_ = 0;
    current_segment_bytes_consumed_ = 0;
    page_body_bytes_consumed_ = 0;
    page_body_size_ = 0;
    packet_assembly_size_ = 0;
    assembling_packet_ = false;
    skipping_packet_ = false;
    bytes_to_skip_ = 0;
    previous_page_ended_with_continued_packet_ = false;
    granule_position_ = 0;
    stream_serial_ = 0;
    expected_page_sequence_ = 0;
    stream_initialized_ = false;
    current_packet_is_bos_ = false;
    current_packet_is_eos_ = false;
    bos_flag_used_ = false;
    incremental_crc_ = 0;
#ifdef MICRO_OGG_DEMUXER_DEBUG
    zero_copy_packets_ = 0;
    buffered_packets_ = 0;
#endif
}

bool OggDemuxer::current_page_has_continued_flag() const {
    return (current_page_.header_type & OGG_CONTINUED_PACKET) != 0;
}

bool OggDemuxer::previous_page_ended_with_continued_packet() const {
    return previous_page_ended_with_continued_packet_;
}

// ==============================================================================
// PUBLIC API: Packet Demuxing
// ==============================================================================

OggDemuxState OggDemuxer::get_next_packet(const uint8_t* input, size_t input_len) {
    OggDemuxState state{};

    // Validate input parameters
    if (input_len > 0 && !input) {
        state.result = OGG_INVALID_CAPTURE;  // Use existing error code
        state.bytes_consumed = 0;
        state.packet.length = 0;
        state.packet.is_bos = false;
        state.packet.is_eos = false;
        state.packet.is_last_on_page = false;
        state.packet.granule_position = OGG_INVALID_GRANULE_POSITION;
        return state;
    }

    // Lazy allocation: allocate buffers on first use
    if (!ensure_buffers_allocated(state)) {
        return state;
    }

    state.bytes_consumed = 0;
    state.packet.length = 0;
    state.packet.is_bos = false;
    state.packet.is_eos = false;
    state.packet.is_last_on_page = false;
    state.packet.granule_position = OGG_INVALID_GRANULE_POSITION;

    while (true) {
        // ======================================================================
        // PHASE A: PAGE HEADER PARSING
        // ======================================================================
        if (state_ == STATE_EXPECT_PAGE_HEADER || state_ == STATE_ACCUMULATING_PAGE_HEADER) {
            InternalResult result = handle_page_header(input, input_len, state);
            if (result != InternalResult::OK) {
                return state;  // NEED_MORE_DATA or PACKET_READY (including errors)
            }
            // InternalResult::OK means we transitioned to STATE_PROCESSING_SEGMENTS
            // but need more data, so return
            return state;
        }

        // ======================================================================
        // PHASE B: PACKET EXTRACTION
        // ======================================================================
        if (state_ == STATE_PROCESSING_SEGMENTS) {
            // ===== Case 0: Skipping Packet (Too Large to Buffer) =====
            if (skipping_packet_) {
                handle_skipping_packet(input, input_len, state);
                return state;
            }

            // ===== Case 1: Assembling Packet (Greedy Buffering) =====
            if (assembling_packet_) {
                handle_assembling_packet(input, input_len, state);
                return state;
            }

            // ===== Case 2: Zero-Copy Mode =====
            handle_zero_copy_path(input, input_len, state);
            return state;
        }
    }
}

// ==============================================================================
// PUBLIC API: Streaming Mode
// ==============================================================================

OggDemuxState OggDemuxer::get_next_data(const uint8_t* input, size_t input_len) {
    OggDemuxState state{};

    if (input_len > 0 && !input) {
        state.result = OGG_INVALID_CAPTURE;
        state.bytes_consumed = 0;
        state.packet.length = 0;
        state.packet.is_bos = false;
        state.packet.is_eos = false;
        state.packet.is_last_on_page = false;
        state.packet.granule_position = OGG_INVALID_GRANULE_POSITION;
        return state;
    }

    state.bytes_consumed = 0;
    state.packet.length = 0;
    state.packet.data = nullptr;
    state.packet.is_bos = false;
    state.packet.is_eos = false;
    state.packet.is_last_on_page = false;
    state.packet.is_end_of_packet = false;
    state.packet.granule_position = OGG_INVALID_GRANULE_POSITION;

    // Header states: parse header without zero-copy packet optimization
    if (state_ == STATE_EXPECT_PAGE_HEADER || state_ == STATE_ACCUMULATING_PAGE_HEADER) {
        InternalResult result = handle_page_header(input, input_len, state, false);
        if (result != InternalResult::OK) {
            return state;
        }
        // Header parsed, now in STATE_PROCESSING_SEGMENTS
        // Check if input has remaining bytes to offer as body data
        size_t header_bytes = state.bytes_consumed;
        const uint8_t* remaining_input = input + header_bytes;
        size_t remaining_len = (header_bytes < input_len) ? (input_len - header_bytes) : 0;

        if (remaining_len == 0) {
            state.result = OGG_NEED_MORE_DATA;
            return state;
        }

        // Fall through to body offering
        size_t total_body = page_body_size_;
        size_t remaining_body = total_body - page_body_bytes_consumed_;

        // Handle zero-body pages: transition back to header parsing
        if (remaining_body == 0) {
            OggDemuxResult page_result = finalize_page();
            state.result = (page_result == OGG_OK) ? OGG_NEED_MORE_DATA : page_result;
            return state;
        }

        // Skip past any zero-length packets at the current position
        while (current_segment_index_ < current_page_.segment_count &&
               segment_table_[current_segment_index_] == 0) {
            current_segment_index_++;
            current_segment_bytes_consumed_ = 0;
        }

        // Cap at packet boundary so we don't bleed into the next packet
        PacketInfo pkt = scan_for_next_packet(current_segment_index_);
        size_t bytes_remaining_in_packet = pkt.size - current_segment_bytes_consumed_;
        size_t to_offer = std::min(remaining_len, bytes_remaining_in_packet);

        if (to_offer == 0) {
            state.result = OGG_NEED_MORE_DATA;
            return state;
        }

        state.packet.data = remaining_input;
        state.packet.length = to_offer;
        state.packet.granule_position = granule_position_;
        state.packet.is_bos = current_packet_is_bos_;
        state.packet.is_eos = (current_page_.header_type & OGG_END_OF_STREAM) != 0;
        state.packet.is_end_of_packet = pkt.complete && (to_offer >= bytes_remaining_in_packet);

        // Auto-advance: accumulate CRC, update segment tracking
        if (enable_crc_) {
            incremental_crc_ = calculate_crc32(remaining_input, to_offer, incremental_crc_);
        }
        page_body_bytes_consumed_ += to_offer;
        advance_through_segments(to_offer);
        current_packet_is_bos_ = false;

        state.packet.is_last_on_page = (page_body_bytes_consumed_ >= total_body);
        state.bytes_consumed = header_bytes + to_offer;

        // Finalize page if fully consumed
        if (page_body_bytes_consumed_ >= total_body) {
            OggDemuxResult page_result = finalize_page();
            if (page_result != OGG_OK) {
                state.result = page_result;
                return state;
            }
        }

        state.result = OGG_OK;
        return state;
    }

    // STATE_PROCESSING_SEGMENTS: offer body bytes as zero-copy pointer
    if (state_ == STATE_PROCESSING_SEGMENTS) {
        size_t total_body = page_body_size_;
        size_t remaining_body = total_body - page_body_bytes_consumed_;

        // Handle fully consumed page: transition back to header parsing
        if (remaining_body == 0) {
            OggDemuxResult page_result = finalize_page();
            state.result = (page_result == OGG_OK) ? OGG_NEED_MORE_DATA : page_result;
            return state;
        }

        // Skip past any zero-length packets at the current position
        while (current_segment_index_ < current_page_.segment_count &&
               segment_table_[current_segment_index_] == 0) {
            current_segment_index_++;
            current_segment_bytes_consumed_ = 0;
        }

        // Check if skipping zero-length packets consumed the page
        remaining_body = total_body - page_body_bytes_consumed_;
        if (remaining_body == 0) {
            OggDemuxResult page_result = finalize_page();
            state.result = (page_result == OGG_OK) ? OGG_NEED_MORE_DATA : page_result;
            return state;
        }

        // Cap at packet boundary so we don't bleed into the next packet
        PacketInfo pkt = scan_for_next_packet(current_segment_index_);
        size_t bytes_remaining_in_packet = pkt.size - current_segment_bytes_consumed_;
        size_t to_offer = std::min(input_len, bytes_remaining_in_packet);

        if (to_offer == 0) {
            state.result = OGG_NEED_MORE_DATA;
            return state;
        }

        state.packet.data = input;
        state.packet.length = to_offer;
        state.packet.granule_position = granule_position_;
        state.packet.is_bos = current_packet_is_bos_;
        state.packet.is_eos = (current_page_.header_type & OGG_END_OF_STREAM) != 0;
        state.packet.is_end_of_packet = pkt.complete && (to_offer >= bytes_remaining_in_packet);

        // Auto-advance: accumulate CRC, update segment tracking
        if (enable_crc_) {
            incremental_crc_ = calculate_crc32(input, to_offer, incremental_crc_);
        }
        page_body_bytes_consumed_ += to_offer;
        advance_through_segments(to_offer);
        current_packet_is_bos_ = false;

        state.packet.is_last_on_page = (page_body_bytes_consumed_ >= total_body);
        state.bytes_consumed = to_offer;

        // Finalize page if fully consumed
        if (page_body_bytes_consumed_ >= total_body) {
            OggDemuxResult page_result = finalize_page();
            if (page_result != OGG_OK) {
                state.result = page_result;
                return state;
            }
        }

        state.result = OGG_OK;
        return state;
    }

    state.result = OGG_NEED_MORE_DATA;
    return state;
}

// ==============================================================================
// PRIVATE HELPERS: Page Header Parsing
// ==============================================================================

OggDemuxResult OggDemuxer::parse_page_header(const uint8_t* data, size_t data_len,
                                             OggPageHeader& header, size_t& header_size) {
    // Minimum header size: 27 bytes + segment_count
    if (data_len < OGG_PAGE_HEADER_SIZE) {
        return OGG_NEED_MORE_DATA;
    }

    // Check capture pattern "OggS"
    if (data[0] != 'O' || data[1] != 'g' || data[2] != 'g' || data[3] != 'S') {
        return OGG_INVALID_CAPTURE;
    }

    // Check version
    if (data[4] != 0x00) {
        return OGG_INVALID_VERSION;
    }

    // Parse header fields
    std::memcpy(header.capture_pattern, data, 4);
    header.version = data[4];
    header.header_type = data[5];
    header.granule_position = read_le64(data + OGG_GRANULE_OFFSET);
    header.stream_serial = read_le32(data + OGG_SERIAL_OFFSET);
    header.page_sequence = read_le32(data + OGG_SEQUENCE_OFFSET);
    header.checksum = read_le32(data + OGG_CHECKSUM_OFFSET);
    header.segment_count = data[OGG_SEGMENT_COUNT_OFFSET];

    // Total header size = 27 + segment_count
    header_size = OGG_PAGE_HEADER_SIZE + header.segment_count;

    // Check if we have enough data for complete header
    if (data_len < header_size) {
        return OGG_NEED_MORE_DATA;
    }

    return OGG_OK;
}

OggDemuxResult OggDemuxer::validate_page_crc() const {
    if (incremental_crc_ != current_page_.checksum) {
        return OGG_CRC_FAILED;
    }
    return OGG_OK;
}

bool OggDemuxer::ensure_buffers_allocated(OggDemuxState& state) {
    if (!internal_buffer_) {
        internal_buffer_capacity_ = min_buffer_size_;
        void* ptr = config_.alloc ? config_.alloc(internal_buffer_capacity_)
                                  : std::malloc(internal_buffer_capacity_);

        if (!ptr) {
            state.result = OGG_ALLOCATION_FAILED;
            return false;
        }
        internal_buffer_ = (uint8_t*)ptr;
#ifdef MICRO_OGG_DEMUXER_DEBUG
        // Track initial allocation
        peak_buffer_capacity_ = internal_buffer_capacity_;
#endif
    }

    return true;
}

void OggDemuxer::handle_skipping_packet(const uint8_t* input, size_t input_len,
                                        OggDemuxState& state) {
    // If bytes_to_skip_ is 0, we're continuing a skip from a previous page
    // Calculate how many bytes to skip on this page
    if (bytes_to_skip_ == 0) {
        // Scan segment table to find packet boundary on this page
        size_t bytes_on_this_page = 0;

        for (uint8_t i = current_segment_index_; i < current_page_.segment_count; i++) {
            bytes_on_this_page += segment_table_[i];
            if (segment_table_[i] < OGG_MAX_LACING_VALUE) {
                // Packet boundary found
                break;
            }
        }

        bytes_to_skip_ = bytes_on_this_page;
    }

    // Skip bytes without buffering until packet is complete
    size_t to_skip = std::min(input_len, bytes_to_skip_);

    if (to_skip == 0) {
        state.result = OGG_NEED_MORE_DATA;
        return;
    }

    bytes_to_skip_ -= to_skip;
    page_body_bytes_consumed_ += to_skip;
    state.bytes_consumed = to_skip;

    // Update CRC (we still need to validate the page)
    if (enable_crc_) {
        incremental_crc_ = calculate_crc32(input, to_skip, incremental_crc_);
    }

    // Advance through segments
    advance_through_segments(to_skip);

    // Check if packet skip complete
    if (bytes_to_skip_ == 0) {
        // Check if we're at the end of the current page
        bool is_last = (current_segment_index_ >= current_page_.segment_count);

        // If page complete, check if packet continues to next page
        if (is_last) {
            // Check if packet continues to next page
            // If last segment = 255, the skipped packet continues to next page
            bool continues_to_next_page =
                (segment_table_[current_page_.segment_count - 1] == OGG_MAX_LACING_VALUE);

            if (continues_to_next_page) {
                // Packet continues to next page - we need to keep skipping
                // Validate CRC for current page
                if (enable_crc_ && validate_page_crc() != OGG_OK) {
                    state.result = OGG_CRC_FAILED;
                    return;
                }

                previous_page_ended_with_continued_packet_ = true;
                state_ = STATE_EXPECT_PAGE_HEADER;
                // Keep skipping_packet_ = true
                // Don't return yet - need more data to continue skipping

                state.result = OGG_NEED_MORE_DATA;
                return;
            }

            // Packet complete - exit skip mode
            skipping_packet_ = false;

            if (enable_crc_ && validate_page_crc() != OGG_OK) {
                state.result = OGG_CRC_FAILED;
                return;
            }

            previous_page_ended_with_continued_packet_ = false;
            state_ = STATE_EXPECT_PAGE_HEADER;

            // Set output parameter for skipped packet
            state.packet.is_last_on_page = true;

            state.result = OGG_PACKET_SKIPPED;
            return;
        }

        // Packet ends mid-page - exit skip mode
        skipping_packet_ = false;

        // Set output parameter for skipped packet
        state.packet.is_last_on_page = false;

        state.result = OGG_PACKET_SKIPPED;
        return;
    }

    // Check if page body fully consumed
    if (page_body_bytes_consumed_ >= page_body_size_) {
        if (enable_crc_ && validate_page_crc() != OGG_OK) {
            state.result = OGG_CRC_FAILED;
            return;
        }

        // Check if packet continues to next page
        bool continues_to_next_page =
            (segment_table_[current_page_.segment_count - 1] == OGG_MAX_LACING_VALUE);
        previous_page_ended_with_continued_packet_ = continues_to_next_page;

        // RFC 3533: Set page boundary output for validation tracking
        state.packet.is_last_on_page = true;
        state.packet.granule_position = OGG_INVALID_GRANULE_POSITION;
        state.packet.is_bos = current_packet_is_bos_;
        current_packet_is_bos_ = false;  // BOS only applies to first packet
        state.packet.is_eos = (current_page_.header_type & OGG_END_OF_STREAM) != 0;

        state_ = STATE_EXPECT_PAGE_HEADER;
        // Keep skipping_packet_ = true and bytes_to_skip_
    }

    state.result = OGG_NEED_MORE_DATA;
}

void OggDemuxer::return_assembled_packet(size_t bytes_consumed, OggDemuxState& state) {
    state.packet.data = internal_buffer_;
    state.packet.length = packet_assembly_size_;
#ifdef MICRO_OGG_DEMUXER_DEBUG
    buffered_packets_++;
#endif

    // Set flags
    state.packet.is_bos = current_packet_is_bos_;
    current_packet_is_bos_ = false;

    bool is_last = (current_segment_index_ >= current_page_.segment_count);
    state.packet.is_last_on_page = is_last;
    state.packet.is_end_of_packet = true;
    state.packet.granule_position = is_last ? granule_position_ : OGG_INVALID_GRANULE_POSITION;

    // Reset assembly state
    assembling_packet_ = false;
    packet_assembly_size_ = 0;

    state.bytes_consumed = bytes_consumed;

    // Check if page complete
    if (is_last) {
        if (enable_crc_ && validate_page_crc() != OGG_OK) {
            state.result = OGG_CRC_FAILED;
            return;
        }
        if (current_page_.header_type & OGG_END_OF_STREAM) {
            state.packet.is_eos = true;
        }
        previous_page_ended_with_continued_packet_ = false;
        state_ = STATE_EXPECT_PAGE_HEADER;
    }

    state.result = OGG_OK;
}

bool OggDemuxer::is_at_packet_boundary() const {
    // Check if CURRENT segment ends the packet (with bounds check)
    if (current_segment_index_ < current_page_.segment_count &&
        segment_table_[current_segment_index_] < OGG_MAX_LACING_VALUE &&
        segment_table_[current_segment_index_] == current_segment_bytes_consumed_) {
        return true;
    }
    // Check if PREVIOUS segment ended the packet
    if (current_segment_bytes_consumed_ == 0 && current_segment_index_ > 0) {
        size_t prev_idx = current_segment_index_ - 1;
        if (segment_table_[prev_idx] < OGG_MAX_LACING_VALUE) {
            return true;
        }
    }
    return false;
}

OggDemuxResult OggDemuxer::finalize_page() {
    if (enable_crc_ && validate_page_crc() != OGG_OK) {
        return OGG_CRC_FAILED;
    }

    // Check if last segment = 255 (packet continues to next page)
    previous_page_ended_with_continued_packet_ =
        (segment_table_[current_page_.segment_count - 1] == OGG_MAX_LACING_VALUE);

    state_ = STATE_EXPECT_PAGE_HEADER;
    return OGG_OK;
}

OggDemuxer::InternalResult OggDemuxer::accumulate_header(const uint8_t* input, size_t input_len,
                                                         size_t& bytes_added,
                                                         OggDemuxState& state) {
    size_t staged_bytes = page_header_staging_size_;
    bytes_added = 0;

    // Step 1: Ensure we have at least 27 bytes to read segment_count
    if (staged_bytes < OGG_PAGE_HEADER_SIZE) {
        size_t needed = OGG_PAGE_HEADER_SIZE - staged_bytes;
        size_t to_copy = std::min(input_len, needed);

        if (to_copy > 0) {
            std::memcpy(page_header_staging_ + staged_bytes, input, to_copy);
            bytes_added = to_copy;
            staged_bytes += to_copy;
            page_header_staging_size_ = staged_bytes;
        }

        if (staged_bytes < OGG_PAGE_HEADER_SIZE) {
            state.bytes_consumed = bytes_added;
            state.result = OGG_NEED_MORE_DATA;
            return InternalResult::NEED_MORE_DATA;
        }
    }

    // Step 2: Now we can read segment_count, calculate full header size
    uint8_t segment_count = page_header_staging_[OGG_SEGMENT_COUNT_OFFSET];
    size_t full_header_size = OGG_PAGE_HEADER_SIZE + segment_count;

    if (staged_bytes < full_header_size) {
        size_t needed = full_header_size - staged_bytes;
        size_t available_in_input = (input_len > bytes_added) ? (input_len - bytes_added) : 0;
        size_t to_copy = std::min(available_in_input, needed);

        if (to_copy > 0) {
            std::memcpy(page_header_staging_ + staged_bytes, input + bytes_added, to_copy);
            bytes_added += to_copy;
            staged_bytes += to_copy;
            page_header_staging_size_ = staged_bytes;
        }

        if (staged_bytes < full_header_size) {
            state.bytes_consumed = bytes_added;
            state.result = OGG_NEED_MORE_DATA;
            return InternalResult::NEED_MORE_DATA;
        }
    }

    return InternalResult::OK;
}

bool OggDemuxer::validate_stream_consistency(OggDemuxState& state) {
    // RFC 3533 validation - page sequence
    if (!stream_initialized_) {
        if (!(current_page_.header_type & OGG_BEGINNING_OF_STREAM)) {
            state.result = OGG_STREAM_BOS_ERROR;
            return false;
        }
        stream_serial_ = current_page_.stream_serial;
        expected_page_sequence_ = current_page_.page_sequence;
        stream_initialized_ = true;
    } else {
        // RFC 3533: BOS flag can only appear on the first page
        if (current_page_.header_type & OGG_BEGINNING_OF_STREAM) {
            state.result = OGG_STREAM_BOS_ERROR;
            return false;
        }
        if (current_page_.stream_serial != stream_serial_) {
            state.result = OGG_STREAM_SERIAL_MISMATCH;
            return false;
        }
        if (current_page_.page_sequence != expected_page_sequence_) {
            state.result = OGG_STREAM_SEQUENCE_ERROR;
            return false;
        }
    }
    expected_page_sequence_++;

    // RFC 3533 Section 6: Validate continued packet flag consistency
    // A page with the continued flag set must follow a page whose last segment was 255
    // (indicating the packet continues). Skip the first page (BOS) since there's no previous.
    bool has_continued_flag = (current_page_.header_type & OGG_CONTINUED_PACKET) != 0;
    if (current_page_.page_sequence > 0 &&
        has_continued_flag != previous_page_ended_with_continued_packet_) {
        state.result = OGG_STREAM_CONTINUATION_ERROR;
        return false;
    }

    // RFC 3533 validation - EOS flag with continued packet
    if ((current_page_.header_type & OGG_END_OF_STREAM) && current_page_.segment_count > 0 &&
        segment_table_[current_page_.segment_count - 1] == OGG_MAX_LACING_VALUE) {
        state.result = OGG_STREAM_EOS_ERROR;
        return false;
    }

    return true;
}

void OggDemuxer::handle_assembling_packet(const uint8_t* input, size_t input_len,
                                          OggDemuxState& state) {
    size_t remaining_page_body = page_body_size_ - page_body_bytes_consumed_;

    // Calculate bytes to packet end, accounting for partially consumed current segment
    size_t bytes_to_packet_end = 0;
    bool packet_boundary_found = false;

    // Start with remaining bytes in current segment
    size_t remaining_in_current_seg =
        segment_table_[current_segment_index_] - current_segment_bytes_consumed_;
    bytes_to_packet_end = remaining_in_current_seg;

    // Check if current segment ends the packet
    if (segment_table_[current_segment_index_] < OGG_MAX_LACING_VALUE) {
        packet_boundary_found = true;
    } else {
        // Scan forward from NEXT segment to find packet end
        for (uint8_t i = current_segment_index_ + 1; i < current_page_.segment_count; i++) {
            bytes_to_packet_end += segment_table_[i];
            if (segment_table_[i] < OGG_MAX_LACING_VALUE) {
                packet_boundary_found = true;
                break;
            }
        }
    }

    // Determine how much to consume
    size_t to_consume = packet_boundary_found ? std::min(input_len, bytes_to_packet_end)
                                              : std::min(input_len, remaining_page_body);

    if (to_consume == 0) {
        // Handle zero-consume edge cases
        if (input_len == 0) {
            state.result = OGG_NEED_MORE_DATA;
            return;
        }

        // Check if page body is fully consumed
        if (page_body_bytes_consumed_ >= page_body_size_) {
            OggDemuxResult page_result = finalize_page();
            if (page_result != OGG_OK) {
                state.result = page_result;
                return;
            }
            state.result = OGG_NEED_MORE_DATA;
            return;
        }

        // Check for zero-length segment completing packet
        if (is_at_packet_boundary()) {
            // Advance past zero-length segment
            if (segment_table_[current_segment_index_] == current_segment_bytes_consumed_) {
                current_segment_index_++;
                current_segment_bytes_consumed_ = 0;
            }
            return_assembled_packet(0, state);
            return;
        }

        state.result = OGG_STREAM_SEQUENCE_ERROR;
        return;
    }

    // Ensure buffer can hold new data
    GrowBufferResult grow_result = grow_buffer(packet_assembly_size_ + to_consume);
    if (grow_result != GROW_OK) {
        if (grow_result == GROW_EXCEEDS_MAX) {
            bytes_to_skip_ = bytes_to_packet_end;
            skipping_packet_ = true;
            assembling_packet_ = false;
            packet_assembly_size_ = 0;
            handle_skipping_packet(input, input_len, state);
            return;
        }
        state.result = OGG_ALLOCATION_FAILED;
        return;
    }

    // Copy to buffer and update state
    std::memcpy(internal_buffer_ + packet_assembly_size_, input, to_consume);
    packet_assembly_size_ += to_consume;
    page_body_bytes_consumed_ += to_consume;
    state.bytes_consumed = to_consume;

    if (enable_crc_) {
        incremental_crc_ = calculate_crc32(input, to_consume, incremental_crc_);
    }

    advance_through_segments(to_consume);

    // Check if packet complete
    if (is_at_packet_boundary()) {
        // Advance past any zero-length terminator segment
        if (current_segment_index_ < current_page_.segment_count &&
            segment_table_[current_segment_index_] == 0) {
            current_segment_index_++;
            current_segment_bytes_consumed_ = 0;
        }
        return_assembled_packet(to_consume, state);
        return;
    }

    // Check if page body fully consumed
    if (page_body_bytes_consumed_ >= page_body_size_) {
        OggDemuxResult page_result = finalize_page();
        if (page_result != OGG_OK) {
            state.result = page_result;
            return;
        }
    }

    state.result = OGG_NEED_MORE_DATA;
}

OggDemuxer::InternalResult OggDemuxer::handle_page_header(const uint8_t* input, size_t input_len,
                                                          OggDemuxState& state,
                                                          bool attempt_packet_zero_copy) {
    const uint8_t* header_data = nullptr;
    size_t header_data_len = 0;
    size_t staged_bytes = page_header_staging_size_;
    size_t bytes_added_to_staging = 0;

    // Combine staged data (if any) with new input
    if (staged_bytes > 0) {
        InternalResult acc_result =
            accumulate_header(input, input_len, bytes_added_to_staging, state);
        if (acc_result != InternalResult::OK) {
            return acc_result;
        }
        header_data = page_header_staging_;
        header_data_len = page_header_staging_size_;
    } else {
        header_data = input;
        header_data_len = input_len;
    }

    // Try to parse header
    size_t header_size = 0;
    OggDemuxResult result =
        parse_page_header(header_data, header_data_len, current_page_, header_size);

    if (result == OGG_NEED_MORE_DATA) {
        if (page_header_staging_size_ == 0 && input_len > 0) {
            size_t to_stage = std::min(input_len, OGG_MAX_HEADER_SIZE);
            std::memcpy(page_header_staging_, input, to_stage);
            page_header_staging_size_ = to_stage;
            state.bytes_consumed = to_stage;
        }
        state_ = STATE_ACCUMULATING_PAGE_HEADER;
        state.result = OGG_NEED_MORE_DATA;
        return InternalResult::NEED_MORE_DATA;
    }

    if (result != OGG_OK) {
        state.result = result;
        return InternalResult::PACKET_READY;
    }

    // Header parsed successfully - copy segment table if from input
    if (header_data != page_header_staging_) {
        std::memcpy(segment_table_, header_data + OGG_PAGE_HEADER_SIZE,
                    current_page_.segment_count);
    }

    // Cache and validate page body size
    page_body_size_ = calculate_body_size(segment_table_, current_page_.segment_count);
    size_t total_page_body_size = page_body_size_;
    if (total_page_body_size > OGG_MAX_PAGE_BODY_SIZE) {
        state.result = OGG_STREAM_SEQUENCE_ERROR;
        return InternalResult::PACKET_READY;
    }

    granule_position_ = current_page_.granule_position;

    // Validate stream consistency (BOS, serial, sequence, EOS)
    if (!validate_stream_consistency(state)) {
        return InternalResult::PACKET_READY;
    }

    // Handle empty page
    if (current_page_.segment_count == 0) {
        if (page_header_staging_size_ == 0) {
            std::memcpy(page_header_staging_, header_data, header_size);
        }

        if (enable_crc_) {
            uint8_t saved_crc[4];
            std::memcpy(saved_crc, page_header_staging_ + OGG_CHECKSUM_OFFSET, 4);
            std::memset(page_header_staging_ + OGG_CHECKSUM_OFFSET, 0, 4);
            incremental_crc_ = calculate_crc32(page_header_staging_, header_size, 0);
            std::memcpy(page_header_staging_ + OGG_CHECKSUM_OFFSET, saved_crc, 4);

            if (validate_page_crc() != OGG_OK) {
                state.result = OGG_CRC_FAILED;
                return InternalResult::PACKET_READY;
            }
        }

        state.bytes_consumed = (bytes_added_to_staging > 0) ? bytes_added_to_staging : header_size;
        page_header_staging_size_ = 0;
        state_ = STATE_EXPECT_PAGE_HEADER;
        state.result = OGG_NEED_MORE_DATA;
        return InternalResult::NEED_MORE_DATA;
    }

    // Non-empty page: initialize for packet extraction
    if (page_header_staging_size_ == 0) {
        std::memcpy(page_header_staging_, header_data, header_size);
    }

    if (enable_crc_) {
        uint8_t saved_crc[4];
        std::memcpy(saved_crc, page_header_staging_ + OGG_CHECKSUM_OFFSET, 4);
        std::memset(page_header_staging_ + OGG_CHECKSUM_OFFSET, 0, 4);
        incremental_crc_ = calculate_crc32(page_header_staging_, header_size, 0);
        std::memcpy(page_header_staging_ + OGG_CHECKSUM_OFFSET, saved_crc, 4);
    }

    current_segment_index_ = 0;
    current_segment_bytes_consumed_ = 0;
    page_body_bytes_consumed_ = 0;

    current_packet_is_bos_ =
        ((current_page_.header_type & OGG_BEGINNING_OF_STREAM) != 0) && !bos_flag_used_;
    if (current_packet_is_bos_) {
        bos_flag_used_ = true;
    }

    // Check for zero-copy opportunity (only in packet mode, not when assembling a continued packet)
    size_t bytes_from_input_for_header = (staged_bytes == 0) ? header_size : bytes_added_to_staging;
    size_t remaining_in_input =
        (bytes_from_input_for_header < input_len) ? (input_len - bytes_from_input_for_header) : 0;

    if (attempt_packet_zero_copy && remaining_in_input > 0 && !assembling_packet_) {
        PacketInfo first_packet = scan_for_next_packet(0);
        if (first_packet.complete && remaining_in_input >= first_packet.size) {
            const uint8_t* body_start = input + bytes_from_input_for_header;
            handle_zero_copy_return(body_start, first_packet, bytes_from_input_for_header, state);
            page_header_staging_size_ = 0;
            return InternalResult::PACKET_READY;
        }
    }

    state.bytes_consumed = (bytes_added_to_staging > 0) ? bytes_added_to_staging : header_size;
    page_header_staging_size_ = 0;
    state_ = STATE_PROCESSING_SEGMENTS;
    state.result = OGG_NEED_MORE_DATA;
    return InternalResult::OK;
}

OggDemuxer::InternalResult OggDemuxer::handle_zero_copy_path(const uint8_t* input, size_t input_len,
                                                             OggDemuxState& state) {
    size_t remaining_page_body = page_body_size_ - page_body_bytes_consumed_;

    // Scan segment table to find next packet size
    PacketInfo next_packet = scan_for_next_packet(current_segment_index_);

    // Check if we have enough data and packet is complete
    if (input_len >= next_packet.size && next_packet.complete) {
        // Zero-copy return!
        handle_zero_copy_return(input, next_packet, 0, state);
        return InternalResult::PACKET_READY;
    }

    // Not enough data OR packet spans pages - switch to assembly mode
    size_t to_buffer = std::min(input_len, remaining_page_body);

    if (to_buffer > 0) {
        // Ensure buffer can hold data
        GrowBufferResult grow_result = grow_buffer(to_buffer);
        if (grow_result != GROW_OK) {
            if (grow_result == GROW_EXCEEDS_MAX) {
                // Packet too large to buffer - enter skip mode
                // If packet is incomplete, we need to calculate total size from segments
                bytes_to_skip_ = next_packet.size;
                skipping_packet_ = true;
                assembling_packet_ = false;  // Not assembling anymore
                packet_assembly_size_ = 0;   // Clear any assembly state

                // Handle skipping in skip mode
                handle_skipping_packet(input, input_len, state);
                return InternalResult::PACKET_READY;  // state has the result
            }
            state.result = OGG_ALLOCATION_FAILED;
            return InternalResult::PACKET_READY;
        }

        // Copy to buffer
        std::memcpy(internal_buffer_, input, to_buffer);
        packet_assembly_size_ = to_buffer;
        assembling_packet_ = true;
        page_body_bytes_consumed_ += to_buffer;
        state.bytes_consumed = to_buffer;

        // Update CRC
        if (enable_crc_) {
            incremental_crc_ = calculate_crc32(input, to_buffer, incremental_crc_);
        }

        // Advance through segments
        advance_through_segments(to_buffer);

        // Check if page body fully consumed
        if (page_body_bytes_consumed_ >= page_body_size_) {
            // Validate CRC
            if (enable_crc_ && validate_page_crc() != OGG_OK) {
                state.result = OGG_CRC_FAILED;
                return InternalResult::PACKET_READY;
            }

            // Check if packet continues to next page
            previous_page_ended_with_continued_packet_ =
                (segment_table_[current_page_.segment_count - 1] == OGG_MAX_LACING_VALUE);

            state_ = STATE_EXPECT_PAGE_HEADER;
        }
    }

    state.result = OGG_NEED_MORE_DATA;
    return InternalResult::NEED_MORE_DATA;
}

// ==============================================================================
// PRIVATE HELPERS: Segment and Packet Navigation
// ==============================================================================

size_t OggDemuxer::calculate_body_size(const uint8_t* segment_table, uint8_t segment_count) {
    size_t total = 0;
    for (uint8_t i = 0; i < segment_count; i++) {
        total += segment_table[i];
    }
    return total;
}

void OggDemuxer::advance_through_segments(size_t bytes_to_advance) {
    while (bytes_to_advance > 0 && current_segment_index_ < current_page_.segment_count) {
        size_t remaining_in_segment =
            segment_table_[current_segment_index_] - current_segment_bytes_consumed_;
        size_t consume_from_segment = std::min(bytes_to_advance, remaining_in_segment);

        current_segment_bytes_consumed_ += consume_from_segment;
        bytes_to_advance -= consume_from_segment;

        if (current_segment_bytes_consumed_ >= segment_table_[current_segment_index_]) {
            current_segment_index_++;
            current_segment_bytes_consumed_ = 0;
        }
    }
}

OggDemuxer::PacketInfo OggDemuxer::scan_for_next_packet(uint8_t start_segment_index) const {
    PacketInfo info = {0, false, 0};

    for (uint8_t i = start_segment_index; i < current_page_.segment_count; i++) {
        info.size += segment_table_[i];
        info.segment_count++;
        if (segment_table_[i] < OGG_MAX_LACING_VALUE) {
            info.complete = true;
            break;
        }
    }

    return info;
}

// ==============================================================================
// PRIVATE HELPERS: Zero-Copy Optimization and Buffer Management
// ==============================================================================

void OggDemuxer::handle_zero_copy_return(const uint8_t* packet_ptr, const PacketInfo& packet_info,
                                         size_t additional_bytes_consumed, OggDemuxState& state) {
    // Set packet output parameters
    state.packet.data = packet_ptr;
    state.packet.length = packet_info.size;
    state.bytes_consumed = additional_bytes_consumed + packet_info.size;

#ifdef MICRO_OGG_DEMUXER_DEBUG
    zero_copy_packets_++;  // Stats: zero-copy packet
#endif

    // Update segment tracking
    current_segment_index_ += packet_info.segment_count;
    current_segment_bytes_consumed_ = 0;
    page_body_bytes_consumed_ += packet_info.size;

    // Update CRC
    if (enable_crc_) {
        incremental_crc_ =
            calculate_crc32(state.packet.data, state.packet.length, incremental_crc_);
    }

    // Set flags
    state.packet.is_bos = current_packet_is_bos_;
    current_packet_is_bos_ = false;

    bool is_last = (current_segment_index_ >= current_page_.segment_count);
    state.packet.is_last_on_page = is_last;
    state.packet.is_end_of_packet = true;
    state.packet.granule_position = is_last ? granule_position_ : OGG_INVALID_GRANULE_POSITION;

    // Check if page complete
    if (is_last) {
        if (enable_crc_ && validate_page_crc() != OGG_OK) {
            state.result = OGG_CRC_FAILED;
            return;
        }
        if (current_page_.header_type & OGG_END_OF_STREAM) {
            state.packet.is_eos = true;
        }
        previous_page_ended_with_continued_packet_ = false;
        state_ = STATE_EXPECT_PAGE_HEADER;
    } else {
        state_ = STATE_PROCESSING_SEGMENTS;
    }

    state.result = OGG_OK;
}

OggDemuxer::GrowBufferResult OggDemuxer::grow_buffer(size_t needed_size) {
    // Check if we need to grow
    if (needed_size <= internal_buffer_capacity_) {
        return GROW_OK;  // Already large enough
    }

    // Check if needed size exceeds maximum
    if (needed_size > max_buffer_size_) {
        return GROW_EXCEEDS_MAX;
    }

    // Calculate new capacity: double current size or use needed size, whichever is larger
    size_t new_capacity = internal_buffer_capacity_ * 2;
    if (new_capacity < needed_size) {
        new_capacity = needed_size;
    }

    // Cap at maximum buffer size
    if (new_capacity > max_buffer_size_) {
        new_capacity = max_buffer_size_;
    }

    // Reallocate buffer using configured allocator
    void* new_buffer = config_.realloc ? config_.realloc(internal_buffer_, new_capacity)
                                       : std::realloc(internal_buffer_, new_capacity);

    if (!new_buffer) {
        return GROW_ALLOCATION_FAILED;
    }

    internal_buffer_ = (uint8_t*)new_buffer;
    internal_buffer_capacity_ = new_capacity;

#ifdef MICRO_OGG_DEMUXER_DEBUG
    // Track peak capacity reached
    if (new_capacity > peak_buffer_capacity_) {
        peak_buffer_capacity_ = new_capacity;
    }
#endif

    return GROW_OK;
}

}  // namespace micro_ogg
