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

/// @file time_burst.h
/// @brief Burst-based time synchronization coordinator that selects the best RTT sample per burst
/// for Kalman filter input

#pragma once

#include <cstdint>
#include <limits>

namespace sendspin {

class SendspinConnection;

/// @brief One server/time reply, measured on the protocol task and handed to its connection's
/// time burst
struct TimeResponse {
    int64_t offset{0};     ///< Server clock minus client clock (microseconds).
    int64_t max_error{0};  ///< Half the round-trip delay (microseconds).
    int64_t timestamp{0};  ///< When the reply arrived, on the client clock (microseconds).
    /// The client_transmitted the reply echoed, identifying the client/time it answers.
    int64_t client_transmitted{0};
};

/// @brief Result of a single SendspinTimeBurst::loop() call
struct TimeBurstResult {
    bool sent;             ///< A time message was sent this call.
    bool burst_completed;  ///< The burst just finished (Kalman filter updated).
};

/**
 * @brief Burst-based time synchronization coordinator for a single connection
 *
 * Sends a rapid burst of time messages over a WebSocket connection, tracks each RTT
 * measurement, and feeds only the best sample (lowest round-trip delay) to the
 * connection's Kalman filter. Waiting for the inter-burst interval between rounds
 * reduces filter update frequency while still capturing clean measurements.
 *
 * Each SendspinConnection owns one, beside its time filter, and drives it on the protocol task:
 * 1. Call loop() on each tick while the connection is admitted and operational, and wake again
 *    after ms_until_due(); starts_burst() says when the next loop() opens a burst, which the
 *    caller may hold back until the platform is ready for it
 * 2. Call on_time_response() when a SERVER_TIME response arrives for that connection
 * 3. Call reset() when the connection is dropped
 * 4. Check TimeBurstResult::burst_completed to know when to act on updated time estimates
 *
 * @code
 * SendspinTimeBurst burst;
 * burst.reset();
 *
 * // On the protocol task's tick:
 * const int64_t now_ms = platform_time_us() / US_PER_MS;
 * TimeBurstResult result = burst.loop(conn, now_ms, burst.starts_burst(now_ms));
 * if (result.burst_completed) {
 *     int64_t server_now = conn->get_time_filter()->compute_server_time(platform_time_us());
 * }
 *
 * // When a SERVER_TIME response arrives:
 * burst.on_time_response(conn, response);
 * @endcode
 */
class SendspinTimeBurst {
public:
    // ========================================
    // Public API
    // ========================================

    /// @brief Drive the burst state machine. Protocol task only.
    ///
    /// A time message the connection does not send counts as one that timed out, so a send that
    /// keeps failing ends the burst instead of re-ticking the protocol task at once.
    /// @param conn The connection that owns this burst, to send time messages on; connected and
    ///        operational (ConnectionManager::run_time_sync() gates it; loop() does not recheck).
    /// @param now_ms platform_time_us() / US_PER_MS, read once by the caller for this call and
    ///        its starts_burst().
    /// @param may_open_burst Whether a burst that is due may be opened (its first time message
    ///        sent) by this call. A burst already open runs on regardless.
    /// @return Result indicating whether a message was sent and/or the burst completed.
    TimeBurstResult loop(SendspinConnection* conn, int64_t now_ms, bool may_open_burst);

    /// @brief Called when a SERVER_TIME response arrives; ignored unless it answers the time
    /// message still pending
    /// @param conn The connection that received the response.
    /// @param response The measurement and the echo identifying the message it answers.
    /// @return true if this completed the burst (Kalman filter was updated).
    bool on_time_response(SendspinConnection* conn, const TimeResponse& response);

    /// @brief Whether the next loop() starts a new burst: the previous one is complete and the
    /// interval since it has elapsed, so loop() would send the burst's first time frame
    /// @param now_ms platform_time_us() / US_PER_MS, the value the caller passes loop().
    [[nodiscard]] bool starts_burst(int64_t now_ms) const {
        return this->burst_index_ >= this->burst_size_ && !this->pending_burst_completed_ &&
               now_ms - this->last_burst_complete_time_ >= this->burst_interval_ms_;
    }

    /// @brief Milliseconds until loop() has something to do: the next message of a burst (0),
    /// the timeout of the message in flight, or the end of the inter-burst interval
    /// @param now_ms platform_time_us() / US_PER_MS, as loop() reads it.
    /// @return 0 when loop() is due now.
    [[nodiscard]] uint32_t ms_until_due(int64_t now_ms) const;

    // ========================================
    // Lifecycle
    // ========================================

    /// @brief Configures burst parameters. Call before the first loop() invocation.
    /// Defaults match the library's built-in values if configure() is never called.
    /// @param burst_size Number of time messages per burst.
    /// @param burst_interval_ms Milliseconds between bursts.
    /// @param response_timeout_ms Milliseconds before an individual message times out.
    void configure(uint8_t burst_size, int64_t burst_interval_ms, int64_t response_timeout_ms);

    /// @brief Reset state (call on connection loss/change)
    void reset();

protected:
    // ========================================
    // Internal helpers
    // ========================================

    /// @brief Ends the current message without a measurement (timed out or not sent); at the
    /// burst's last message, feeds the best measurement to the time filter.
    /// @param conn The connection that owns this burst.
    /// @param now_ms The loop() call's clock, stamped as the completion time.
    /// @return true if this completed the burst.
    bool retire_message(SendspinConnection* conn, int64_t now_ms);

    static constexpr int64_t DEFAULT_BURST_INTERVAL_MS = 10000;
    static constexpr int64_t DEFAULT_RESPONSE_TIMEOUT_MS = 10000;

    // 64-bit fields
    int64_t best_max_error_{std::numeric_limits<int64_t>::max()};
    int64_t best_offset_{0};
    int64_t best_timestamp_{0};
    int64_t current_message_sent_time_{0};
    int64_t last_burst_complete_time_{0};
    int64_t burst_interval_ms_{DEFAULT_BURST_INTERVAL_MS};
    int64_t response_timeout_ms_{DEFAULT_RESPONSE_TIMEOUT_MS};
    // client_transmitted of the time message awaiting a reply, 0 when none is (see
    // on_time_response())
    int64_t pending_embedded_{0};

    // 8-bit fields
    uint8_t burst_size_{8};
    uint8_t burst_index_{8};  // starts "complete" so first loop triggers a burst
    // Flag set by on_time_response() when burst completes, consumed by loop()
    bool pending_burst_completed_{false};
};

}  // namespace sendspin
