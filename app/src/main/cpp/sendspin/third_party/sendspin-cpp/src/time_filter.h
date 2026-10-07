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

/// @file time_filter.h
/// @brief Two-dimensional Kalman filter for NTP-style clock offset and drift estimation between
/// client and server

#pragma once

#include <cstdint>
#include <limits>
#include <mutex>

namespace sendspin {

/// @brief Two-dimensional Kalman filter for NTP-style time synchronization between client and
/// server.
///
/// This class implements a time synchronization filter that tracks both the timestamp offset and
/// clock drift rate between a client and server. It processes measurements obtained with NTP-style
/// time messages that contain round-trip timing information to optimally estimate the time
/// relationship while accounting for network latency uncertainty.
///
/// The filter maintains a 2D state vector [offset, drift] with associated covariance matrix to
/// track estimation uncertainty. An adaptive forgetting factor helps the filter recover quickly
/// from network disruptions or server clock adjustments.
///
/// All computations use double precision arithmetic to maintain microsecond-level accuracy over
/// extended periods. Thread-safe access to the current time transformation is provided via
/// std::mutex.
class SendspinTimeFilter {
public:
    /// @brief Configuration parameters for the time synchronization filter.
    struct Config {
        /// Diffusion coefficient for offset random walk, in µs / sqrt(µs), modeling clock jitter.
        /// Offset variance grows by process_std_dev² * dt per µs of elapsed time.
        double process_std_dev = 0.0;
        /// Diffusion coefficient for drift random walk, in 1 / sqrt(µs), modeling frequency wander.
        /// Drift is dimensionless (µs of offset per µs of time), so its variance grows by
        /// drift_process_std_dev² * dt per µs of elapsed time.
        double drift_process_std_dev = 1e-11;
        /// Forgetting factor (>1) applied to covariances when large residuals are detected.
        /// Higher values enable faster recovery from disruptions but may reduce stability.
        double forget_factor = 2.0;
        /// Multiple of max_error that triggers adaptive forgetting.
        /// When residual > adaptive_cutoff * max_error, forgetting is applied; values > 1 require
        /// larger residuals.
        double adaptive_cutoff = 3.0;
        /// Minimum number of samples before adaptive forgetting is enabled.
        /// Building sufficient history before enabling forgetting improves stability.
        uint8_t min_samples = 100U;  // NOLINT(readability-magic-numbers)
        /// SNR threshold for applying drift compensation in time conversions.
        /// Drift is only used when drift² > threshold² * drift_covariance, ensuring
        /// the drift estimate is statistically significant before applying corrections.
        double drift_significance_threshold = 2.0;
        /// Scale factor applied to max_error before it is used as the measurement standard
        /// deviation. Values < 1 indicate the round-trip half-delay overestimates true measurement
        /// noise.
        double max_error_scale = 0.5;
    };

    // ========================================
    // Lifecycle
    // ========================================

    /// @brief Constructs a Kalman filter for time synchronization.
    ///
    /// @param config Filter configuration parameters. See Config for field documentation and
    /// defaults.
    explicit SendspinTimeFilter(const Config& config);
    SendspinTimeFilter();

    // ========================================
    // Core API
    // ========================================

    /// @brief Processes a new time synchronization measurement through the Kalman filter.
    ///
    /// Updates the filter's offset and drift estimates using a two-stage Kalman filter algorithm:
    /// predict based on the drift model then correct using the new measurement. The measurement
    /// uncertainty is derived from the network round-trip delay.
    ///
    /// @param measurement Computed offset from NTP-style exchange: ((T2-T1)+(T3-T4))/2 in
    /// microseconds.
    /// @param max_error Half the round-trip delay: ((T4-T1)-(T3-T2))/2, representing maximum
    /// measurement uncertainty in
    ///                  microseconds.
    /// @param time_added Client timestamp when this measurement was taken in microseconds.
    void update(int64_t measurement, int64_t max_error, int64_t time_added);

    /// @brief Converts a client timestamp to the equivalent server timestamp.
    ///
    /// Applies the current offset and drift compensation to transform from client time domain to
    /// server time domain. The transformation accounts for both static offset and dynamic drift
    /// accumulated since the last filter update.
    ///
    /// @param client_time Client timestamp in microseconds.
    /// @return Equivalent server timestamp in microseconds.
    int64_t compute_server_time(int64_t client_time) const;

    /// @brief Converts a server timestamp to the equivalent client timestamp.
    ///
    /// Inverts the time transformation to convert from server time domain to client time domain.
    /// Accounts for both offset and drift effects in the inverse transformation.
    ///
    /// @param server_time Server timestamp in microseconds.
    /// @return Equivalent client timestamp in microseconds.
    int64_t compute_client_time(int64_t server_time) const;

    /// @brief Resets the filter to its initial uninitialized state.
    ///
    /// Clears all state estimates and resets covariances to initial values. The filter will require
    /// new measurements to re-establish synchronization.
    void reset();

    // ========================================
    // Accessors
    // ========================================

    /// @brief Returns the offset variance in microseconds squared.
    ///
    /// Provides the raw variance value from the Kalman filter's covariance matrix. This represents
    /// the statistical uncertainty in the offset estimate.
    ///
    /// @return Variance of the offset estimate in microseconds squared.
    int64_t get_covariance() const;

    /// @brief Returns the estimated standard deviation of the offset in microseconds.
    ///
    /// Provides a measure of the current synchronization accuracy by computing the square root of
    /// the offset covariance. Smaller values indicate higher confidence in the time
    /// synchronization.
    ///
    /// @return Standard deviation of the offset estimate in microseconds.
    int64_t get_error() const;

    /// @brief Returns true if the filter has received at least one measurement.
    /// @return True if the filter has been updated with at least one time measurement.
    bool has_update() const;

protected:
    // ========================================
    // Member variables
    // ========================================

    // Struct fields
    mutable std::mutex state_mutex_;

    // 64-bit fields
    const double adaptive_forgetting_cutoff;
    double drift_{0.0};
    double drift_covariance_{0.0};
    const double drift_process_variance;
    const double drift_significance_threshold_squared;
    const double forget_variance_factor;
    int64_t last_update_{0};
    const double max_error_scale;
    double offset_{0.0};
    double offset_covariance_{std::numeric_limits<double>::infinity()};
    double offset_drift_covariance_{0.0};
    const double process_variance;

    // 8-bit fields
    uint8_t count_{0};
    const uint8_t min_samples_for_forgetting;
    bool use_drift_{false};
};

}  // namespace sendspin
