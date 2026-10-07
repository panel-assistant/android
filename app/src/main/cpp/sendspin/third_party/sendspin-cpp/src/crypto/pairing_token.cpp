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

#include "pairing_token.h"

#include <cstddef>
#include <vector>

namespace sendspin {

namespace {

/// @brief RFC 4648 base32 alphabet (section 6): A-Z, 2-7.
constexpr char BASE32_ALPHABET[32] = {'A', 'B', 'C', 'D', 'E', 'F', 'G', 'H', 'I', 'J', 'K',
                                      'L', 'M', 'N', 'O', 'P', 'Q', 'R', 'S', 'T', 'U', 'V',
                                      'W', 'X', 'Y', 'Z', '2', '3', '4', '5', '6', '7'};

/// @brief Bits one base32 character encodes, and the mask selecting them.
constexpr int BASE32_BITS_PER_CHAR = 5;
constexpr uint64_t BASE32_CHAR_MASK = 0x1F;

/// @brief RFC 4648 base32-encode `data`, padded to a multiple of 8 characters with '='.
std::string base32_encode(const uint8_t* data, size_t len) {
    std::string out;
    out.reserve(((len + 4) / 5) * 8);

    size_t i = 0;
    while (i < len) {
        uint8_t chunk[5] = {0, 0, 0, 0, 0};
        size_t chunk_len = (len - i < 5) ? (len - i) : 5;
        for (size_t j = 0; j < chunk_len; ++j) {
            chunk[j] = data[i + j];
        }

        // 5 bytes (40 bits) -> 8 base32 characters (5 bits each).
        uint64_t buf = (static_cast<uint64_t>(chunk[0]) << 32) |
                       (static_cast<uint64_t>(chunk[1]) << 24) |
                       (static_cast<uint64_t>(chunk[2]) << 16) |
                       (static_cast<uint64_t>(chunk[3]) << 8) | static_cast<uint64_t>(chunk[4]);

        char out_chars[8];
        for (int k = 0; k < 8; ++k) {
            int shift = (7 - k) * BASE32_BITS_PER_CHAR;
            out_chars[k] = BASE32_ALPHABET[(buf >> shift) & BASE32_CHAR_MASK];
        }

        // Number of meaningful output characters for this (possibly partial) chunk, per
        // RFC 4648 section 6's padding table, indexed by chunk_len (1..5 input bytes).
        static constexpr int VALID_CHARS[6] = {0, 2, 4, 5, 7, 8};
        int valid = VALID_CHARS[chunk_len];
        for (int k = 0; k < 8; ++k) {
            out.push_back(k < valid ? out_chars[k] : '=');
        }

        i += chunk_len;
    }

    return out;
}

/// @brief Wrap a payload as a pairing token of the given version (pairing.md "Pairing Token").
/// Shared by both versions so their bodies cannot diverge.
std::string format_token(char version, const uint8_t* payload, size_t payload_len) {
    std::string body = base32_encode(payload, payload_len);

    size_t pad_start = body.find('=');
    if (pad_start != std::string::npos) {
        body.resize(pad_start);
    }

    for (char& c : body) {
        if (c == '2') {
            c = '9';
        }
    }

    std::string token;
    token.reserve(3 + 1 + body.size());
    token += "SP:";
    token += version;
    token += body;
    return token;
}

}  // namespace

std::string format_pairing_token(const std::array<uint8_t, X25519_KEY_SIZE>& client_key,
                                 const std::array<uint8_t, NOISE_PSK_SIZE>& pairing_psk) {
    std::vector<uint8_t> payload;
    payload.reserve(64);
    payload.insert(payload.end(), client_key.begin(), client_key.end());
    payload.insert(payload.end(), pairing_psk.begin(), pairing_psk.end());

    return format_token(PAIRING_PSK_TOKEN_VERSION, payload.data(), payload.size());
}

std::string format_pairing_code_token(const std::array<uint8_t, QR_PAIRING_CODE_SIZE>& code) {
    return format_token(PAIRING_CODE_TOKEN_VERSION, code.data(), code.size());
}

}  // namespace sendspin
