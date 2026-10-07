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

#include "constants.h"

#include "keys.h"
#include "platform/crypto.h"

#include <cstdlib>

namespace sendspin {

// Sentinel PSK = SHA-256("sendspin-sentinel-psk-v1")
// Pre-computed from the label string at startup so we never hard-code the raw
// bytes; the test KAT verifies the hex matches the spec constant.
// NOLINTNEXTLINE(bugprone-throwing-static-initialization)
const std::array<uint8_t, NOISE_PSK_SIZE> SENTINEL_PSK = []() {
    static constexpr std::string_view LABEL{"sendspin-sentinel-psk-v1"};
    Sha256 h;
    if (!h.ok()) {
        // SHA-256 is unavailable at process startup (noise-c allocation failure). There is no
        // safe fallback value for this security-critical constant: an all-zero SENTINEL_PSK
        // would be a fixed, publicly known PSK that every client and server would both derive
        // and silently authenticate under. Fail loudly at startup instead (same rationale as
        // the CSPRNG-failure abort() in platform_random_bytes()).
        abort();
    }
    h.update(reinterpret_cast<const uint8_t*>(LABEL.data()), LABEL.size());
    auto digest = h.finalize();
    if (!h.ok()) {
        abort();
    }
    return digest;
}();

// SENTINEL_PSK_ID = base64url(SHA-256(PSK_ID_LABEL || SENTINEL_PSK))
// Computed once at startup from SENTINEL_PSK.
// NOLINTNEXTLINE(bugprone-throwing-static-initialization)
const std::string SENTINEL_PSK_ID = psk_id_for(SENTINEL_PSK);

}  // namespace sendspin
