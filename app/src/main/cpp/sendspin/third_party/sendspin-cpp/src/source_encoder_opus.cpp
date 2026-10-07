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

#include "source_encoder_opus.h"

#include "opus_state_location.h"
#include "platform/logging.h"
#include "source_task.h"
#include <opus.h>

#include <algorithm>
#include <cstring>

// The source task's stack budget assumes micro-opus's per-thread pseudostack: alloca needs about
// 120 KB of task stack, and the non-thread-safe pseudostack is one arena the player's decode
// would race this encode over.
#if defined(CONFIG_OPUS_USE_ALLOCA) || defined(CONFIG_OPUS_NONTHREADSAFE_PSEUDOSTACK)
#error "The source role's Opus encoder needs micro-opus's thread-safe pseudostack allocation mode"
#endif

namespace sendspin {

static const char* const TAG = "sendspin.source_encoder";

bool OpusSourceEncoder::init(const SourceRoleConfig& config) {
    this->bytes_per_frame_ = source_bytes_per_frame(config.channels, config.bit_depth);

    const int state_size = opus_encoder_get_size(config.channels);
    if (state_size <= 0 ||
        !this->encoder_state_.allocate(static_cast<size_t>(state_size), OPUS_STATE_LOCATION)) {
        SS_LOGE(TAG, "Couldn't allocate %d bytes for the Opus encoder state", state_size);
        return false;
    }

    // RESTRICTED_LOWDELAY is CELT only: SILK keeps over 12 KB of fixed arrays on the task stack
    // in micro-opus's float build (the ESP32 and ESP32-S3 default) and cannot encode in real time
    // there.
    auto* encoder = this->encoder_state_.as<OpusEncoder>();
    int err = opus_encoder_init(encoder, static_cast<opus_int32>(config.sample_rate),
                                config.channels, OPUS_APPLICATION_RESTRICTED_LOWDELAY);
    if (err == OPUS_OK) {
        err = opus_encoder_ctl(encoder,
                               OPUS_SET_BITRATE(static_cast<opus_int32>(config.opus_bitrate)));
    }
    if (err == OPUS_OK) {
        err = opus_encoder_ctl(
            encoder, OPUS_SET_COMPLEXITY(static_cast<opus_int32>(config.opus_complexity)));
    }
    // OPUS_GET_LOOKAHEAD reports samples at the encoder's rate; fixed for fixed settings
    opus_int32 lookahead_samples = 0;
    if (err == OPUS_OK) {
        err = opus_encoder_ctl(encoder, OPUS_GET_LOOKAHEAD(&lookahead_samples));
    }
    if (err != OPUS_OK) {
        SS_LOGE(TAG, "Couldn't initialize the Opus encoder, error %d", err);
        this->encoder_state_.reset();
        return false;
    }
    this->lookahead_us_ =
        source_frames_to_us(static_cast<uint64_t>(lookahead_samples), config.sample_rate);

    if (!this->pcm_scratch_.allocate(source_chunk_bytes(config), config.buffer_location)) {
        SS_LOGE(TAG, "Couldn't allocate the Opus chunk scratch buffer");
        this->encoder_state_.reset();
        return false;
    }
    return true;
}

size_t OpusSourceEncoder::encode(const uint8_t* in, size_t in_len, uint8_t* out,
                                 size_t out_capacity) {
    if (in_len != this->pcm_scratch_.size()) {
        return 0;  // One full chunk is one legal Opus frame; nothing else is encodable
    }
    // Other input (an unaligned payload area the packet may overwrite) goes through the scratch
    if (in != this->pcm_scratch_.data()) {
        std::memcpy(this->pcm_scratch_.data(), in, in_len);
    }
    // max_data_bytes is a hard packet cap: libopus lowers quality to fit rather than failing.
    // The task offers at least MAX_PACKET_BYTES, so its chunks never degrade.
    const auto capacity =
        static_cast<opus_int32>(std::min(out_capacity, static_cast<size_t>(MAX_PACKET_BYTES)));
    const opus_int32 written =
        opus_encode(this->encoder_state_.as<OpusEncoder>(), this->pcm_scratch_.as<opus_int16>(),
                    static_cast<int>(in_len / this->bytes_per_frame_), out, capacity);
    if (written <= 0) {
        if (!this->failing_) {
            this->failing_ = true;
            SS_LOGE(TAG, "Opus encode failed, error %d; dropping chunks until it recovers",
                    static_cast<int>(written));
        }
        return 0;
    }
    if (this->failing_) {
        this->failing_ = false;
        SS_LOGI(TAG, "Opus encode recovered");
    }
    return static_cast<size_t>(written);
}

void OpusSourceEncoder::warm_up() {
    // A silent frame fits a small packet; the packet is discarded either way
    static constexpr size_t WARM_UP_PACKET_BYTES = 64;
    uint8_t packet[WARM_UP_PACKET_BYTES];
    std::memset(this->pcm_scratch_.data(), 0, this->pcm_scratch_.size());
    const opus_int32 written =
        opus_encode(this->encoder_state_.as<OpusEncoder>(), this->pcm_scratch_.as<opus_int16>(),
                    static_cast<int>(this->pcm_scratch_.size() / this->bytes_per_frame_), packet,
                    static_cast<opus_int32>(WARM_UP_PACKET_BYTES));
    if (written <= 0) {
        // Not fatal: the first chunk's encode allocates the pseudostack instead
        SS_LOGW(TAG, "Opus warm-up encode failed, error %d", static_cast<int>(written));
    }
}

void OpusSourceEncoder::reset() {
    opus_encoder_ctl(this->encoder_state_.as<OpusEncoder>(), OPUS_RESET_STATE);
}

}  // namespace sendspin
