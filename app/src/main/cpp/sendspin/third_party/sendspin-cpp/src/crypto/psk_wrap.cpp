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

#include "psk_wrap.h"

#include "platform/crypto.h"

#include <cstring>

namespace sendspin {

std::optional<std::array<uint8_t, 32>> derive_wrap_key(
    std::string_view label_view, const std::vector<uint8_t>& sid,
    const std::array<uint8_t, CPACE_ISK_SIZE>& isk) {
    const auto* label = reinterpret_cast<const uint8_t*>(label_view.data());
    size_t label_len = label_view.size();

    Sha256 h;
    if (!h.ok()) {
        // noise_hashstate_new_by_name() failed (allocation failure or missing algorithm); h is
        // a no-op in this state and finalize() would silently yield an all-zero digest. Returning
        // that as K_wrap would let wrap_value() seal its value under a publicly derivable key,
        // defeating the wrapping (pairing.md "Wrapping"), so fail loudly instead.
        return std::nullopt;
    }
    h.update(label, label_len);
    h.update(sid.data(), sid.size());
    h.update(isk.data(), isk.size());
    auto digest = h.finalize();
    if (!h.ok()) {
        return std::nullopt;
    }
    return digest;
}

std::optional<std::array<uint8_t, WRAPPED_VALUE_SIZE>> wrap_value(
    std::string_view label, const char* cipher_name, const std::vector<uint8_t>& sid,
    const std::array<uint8_t, CPACE_ISK_SIZE>& isk, const std::array<uint8_t, 32>& value) {
    if (cipher_name == nullptr) {
        return std::nullopt;
    }
    auto k_wrap = derive_wrap_key(label, sid, isk);
    if (!k_wrap.has_value()) {
        return std::nullopt;
    }
    auto ct = aead_oneshot_encrypt(cipher_name, k_wrap->data(), k_wrap->size(), value.data(),
                                   value.size());
    // K_wrap has done its job. Wiped here, before the branches, so every exit path below is
    // covered by the one call. The ciphertext it produced is not secret.
    secure_zero_container(k_wrap.value());
    if (!ct.has_value() || ct->size() != WRAPPED_VALUE_SIZE) {
        return std::nullopt;
    }
    std::array<uint8_t, WRAPPED_VALUE_SIZE> out{};
    std::memcpy(out.data(), ct->data(), WRAPPED_VALUE_SIZE);
    return out;
}

}  // namespace sendspin
