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

#include "audio_stream_info.h"

namespace sendspin {

// ============================================================================
// Constructor / Destructor
// ============================================================================

AudioStreamInfo::AudioStreamInfo(uint8_t bits_per_sample, uint8_t channels, uint32_t sample_rate)
    : sample_rate_(sample_rate), bits_per_sample_(bits_per_sample), channels_(channels) {
    this->bytes_per_sample_ = (this->bits_per_sample_ + 7) / 8;
}

// ============================================================================
// Public API
// ============================================================================

int64_t AudioStreamInfo::frames_to_microseconds(uint32_t frames) const {
    // The product is widened to 64-bit before the multiply so it cannot overflow for any reasonable
    // frame count.
    return (static_cast<uint64_t>(frames) * US_PER_SECOND + (this->sample_rate_ >> 1)) /
           this->sample_rate_;
}

bool AudioStreamInfo::operator==(const AudioStreamInfo& rhs) const {
    return (this->bits_per_sample_ == rhs.get_bits_per_sample()) &&
           (this->channels_ == rhs.get_channels()) && (this->sample_rate_ == rhs.get_sample_rate());
}

}  // namespace sendspin
