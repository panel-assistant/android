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

/// @file frame_header.h
/// @brief FLAC frame header parsing and validation
///
/// Provides frame header length computation and parsing with CRC-8 validation
/// and STREAMINFO consistency checks.

#pragma once

#include "micro_flac/flac_decoder.h"

#include <cstdint>

namespace micro_flac {

/// @brief Parsed frame header fields
struct FrameHeaderInfo {
    uint32_t block_size{0};
    uint32_t channel_assignment{0};
    uint32_t bits_per_sample{0};
};

// Minimum valid frame header length (sync + block/rate + channel/bps + utf8 + crc8)
static constexpr uint8_t FRAME_HEADER_MIN_LENGTH = 6;

/// @brief Compute the exact frame header length from the first 5 accumulated bytes.
///
/// FLAC frame headers are 6-16 bytes. After 5 bytes, the remaining length is deterministic.
/// Layout: sync(2) + block_size/sample_rate(1) + channel/depth(1) + utf8(1-7) +
/// block_size_extra(0-2) + sample_rate_extra(0-2) + crc8(1)
///
/// @param header Pointer to first 5 bytes of the frame header
/// @return Total frame header length in bytes, or 0 if the UTF-8 coded number is invalid
uint8_t compute_frame_header_length(const uint8_t* header);

/// @brief Parse a complete, pre-buffered FLAC frame header
///
/// Parses the frame header bytes, validates CRC8 (if enabled), and checks that
/// the frame parameters match the STREAMINFO metadata. The caller must ensure the
/// header buffer contains exactly the number of bytes returned by
/// compute_frame_header_length().
///
/// @param header Pointer to complete frame header bytes
/// @param header_len Length of the header buffer in bytes
/// @param stream_info Reference to STREAMINFO for validation
/// @param crc_check Whether to validate the CRC8 checksum
/// @param info [out] Parsed frame header fields
/// @return FLAC_DECODER_SUCCESS on success, negative error code on failure
FLACDecoderResult parse_frame_header(const uint8_t* header, uint8_t header_len,
                                     const FLACStreamInfo& stream_info, bool crc_check,
                                     FrameHeaderInfo& info);

}  // namespace micro_flac
