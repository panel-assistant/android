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

/// @file crc.h
/// @brief CRC checksum functions for FLAC frame validation
///
/// This module provides CRC-8 and CRC-16 calculation functions using lookup tables
/// for efficient validation of FLAC frame headers and frame data integrity.

#pragma once

#include <cstddef>
#include <cstdint>

namespace micro_flac {

/// @brief Calculate CRC-8 checksum over a buffer
///
/// Used to validate FLAC frame headers. The CRC-8 in FLAC covers the frame header
/// from the sync code through the end of the header (before audio data).
///
/// @param data Pointer to data buffer
/// @param len Length of data in bytes
/// @return Calculated CRC-8 checksum
uint8_t calculate_crc8(const uint8_t* data, size_t len);

/// @brief Update a running CRC-16 checksum with additional data
///
/// Allows incremental CRC-16 computation across multiple buffers, used by the
/// streaming decoder to validate frames that span multiple decode_frame() calls.
///
/// @param crc Current CRC-16 value (0 for initial call)
/// @param data Pointer to data buffer
/// @param len Length of data in bytes
/// @return Updated CRC-16 checksum
uint16_t update_crc16(uint16_t crc, const uint8_t* data, size_t len);

}  // namespace micro_flac
