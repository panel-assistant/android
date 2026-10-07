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

#include "time_burst.h"

#include "connection.h"
#include "constants.h"
#include "platform/logging.h"
#include "platform/time.h"
#include "time_filter.h"

namespace sendspin {

static const char* const TAG = "sendspin.time_burst";

// ============================================================================
// Public API
// ============================================================================

TimeBurstResult SendspinTimeBurst::loop(SendspinConnection* conn, int64_t now_ms,
                                        bool may_open_burst) {
    // Consume burst completion flag set by on_time_response() (called between loop() invocations)
    // and report it alone: the next burst starts on a later call, so a caller that holds a burst
    // back until the platform is ready (starts_burst()) sees the completion before the start.
    if (this->pending_burst_completed_) {
        this->pending_burst_completed_ = false;
        return {.sent = false, .burst_completed = true};
    }

    if (conn == nullptr) {
        return {.sent = false, .burst_completed = false};
    }

    // State 1: Burst complete / inter-burst wait
    if (this->burst_index_ >= this->burst_size_) {
        // The caller's permission is the one gate on opening a burst: the burst it opens is the one
        // starts_burst() reported at the same `now_ms`, which the caller has prepared for.
        if (!may_open_burst ||
            now_ms - this->last_burst_complete_time_ < this->burst_interval_ms_) {
            return {.sent = false, .burst_completed = false};
        }
        // Start a new burst
        this->burst_index_ = 0;
        this->best_max_error_ = std::numeric_limits<int64_t>::max();
        SS_LOGV(TAG, "Starting new time burst");
        // Fall through to send first message
    }

    // State 2: Waiting for response - check timeout
    if (this->pending_embedded_ != 0) {
        if (now_ms - this->current_message_sent_time_ > this->response_timeout_ms_) {
            SS_LOGW(TAG, "Time message %u/%u timed out", this->burst_index_ + 1, this->burst_size_);
            conn->cancel_time_frame();
            this->pending_embedded_ = 0;
            return {.sent = false, .burst_completed = this->retire_message(conn, now_ms)};
        }
        return {.sent = false, .burst_completed = false};
    }

    // State 3: Ready to send next message in burst
    const int64_t embedded = conn->send_time_message();

    if (embedded != 0) {
        this->pending_embedded_ = embedded;
        this->current_message_sent_time_ = now_ms;
        SS_LOGV(TAG, "Sent time message %u/%u", this->burst_index_ + 1, this->burst_size_);
        return {.sent = true, .burst_completed = false};
    }

    SS_LOGD(TAG, "Time message %u/%u not sent", this->burst_index_ + 1, this->burst_size_);
    return {.sent = false, .burst_completed = this->retire_message(conn, now_ms)};
}

bool SendspinTimeBurst::on_time_response(SendspinConnection* conn, const TimeResponse& response) {
    // Compare the whole echo (the claim matched only its low 32 bits): a claimed reply can still
    // be drained after loop() timed its message out or sent the next. A claimed echo is never 0,
    // so nothing matches while no message is pending.
    if (response.client_transmitted != this->pending_embedded_) {
        return false;
    }

    // Track the best (lowest RTT) measurement in this burst.
    // max_error is half the round-trip delay and must be strictly positive; zero or negative
    // values arise from clock skew or timestamp quantization in the time message and would
    // yield zero/negative measurement variance in the Kalman filter (risking divide-by-zero
    // in the update step), so we skip updating the best_* tracking for those samples.
    if (response.max_error > 0 && response.max_error < this->best_max_error_) {
        this->best_max_error_ = response.max_error;
        this->best_offset_ = response.offset;
        this->best_timestamp_ = response.timestamp;
    } else if (response.max_error <= 0) {
        SS_LOGW(TAG, "Dropping time response with non-positive max_error: %" PRId64 " us",
                response.max_error);
    }

    this->pending_embedded_ = 0;
    this->burst_index_++;

    // Check if burst is complete
    if (this->burst_index_ >= this->burst_size_) {
        auto* time_filter = conn->get_time_filter();
        if (time_filter != nullptr && this->best_max_error_ < std::numeric_limits<int64_t>::max()) {
            time_filter->update(this->best_offset_, this->best_max_error_, this->best_timestamp_);
            SS_LOGV(TAG, "Burst complete, best max_error: %" PRId64 " us", this->best_max_error_);
        }
        this->last_burst_complete_time_ = platform_time_us() / US_PER_MS;
        this->pending_burst_completed_ = true;
        return true;
    }

    return false;
}

// ============================================================================
// Lifecycle
// ============================================================================

uint32_t SendspinTimeBurst::ms_until_due(int64_t now_ms) const {
    if (this->pending_burst_completed_) {
        return 0;
    }
    int64_t due_ms = now_ms;
    if (this->burst_index_ >= this->burst_size_) {
        // loop() starts the next burst once the interval has fully elapsed.
        due_ms = this->last_burst_complete_time_ + this->burst_interval_ms_;
    } else if (this->pending_embedded_ != 0) {
        // loop() times the message out once strictly more than the timeout has passed.
        due_ms = this->current_message_sent_time_ + this->response_timeout_ms_ + 1;
    }
    return ms_until(due_ms * US_PER_MS, now_ms * US_PER_MS);
}

void SendspinTimeBurst::configure(uint8_t burst_size, int64_t burst_interval_ms,
                                  int64_t response_timeout_ms) {
    this->burst_size_ = burst_size;
    this->burst_interval_ms_ = burst_interval_ms;
    this->response_timeout_ms_ = response_timeout_ms;
    this->burst_index_ = burst_size;  // "complete" state; next loop will wait for interval
}

void SendspinTimeBurst::reset() {
    this->burst_index_ = this->burst_size_;  // "complete" state; next loop will wait for interval
    this->last_burst_complete_time_ = 0;
    this->current_message_sent_time_ = 0;
    this->pending_burst_completed_ = false;
    this->pending_embedded_ = 0;
    this->best_max_error_ = std::numeric_limits<int64_t>::max();
    this->best_offset_ = 0;
    this->best_timestamp_ = 0;
}

// ============================================================================
// Internal helpers
// ============================================================================

bool SendspinTimeBurst::retire_message(SendspinConnection* conn, int64_t now_ms) {
    this->burst_index_++;
    if (this->burst_index_ < this->burst_size_) {
        return false;
    }
    auto* time_filter = conn->get_time_filter();
    if (time_filter != nullptr && this->best_max_error_ < std::numeric_limits<int64_t>::max()) {
        time_filter->update(this->best_offset_, this->best_max_error_, this->best_timestamp_);
        SS_LOGV(TAG, "Burst complete (with retired messages), best max_error: %" PRId64 " us",
                this->best_max_error_);
    }
    this->last_burst_complete_time_ = now_ms;
    return true;
}

}  // namespace sendspin
