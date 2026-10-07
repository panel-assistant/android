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

/// @file visualizer_role.h
/// @brief Visualizer role that receives real-time audio visualization data from the server

#pragma once

#include "sendspin/config.h"

#include <cstdint>
#include <memory>
#include <optional>
#include <vector>

namespace sendspin {

class SendspinClient;

// ============================================================================
// Visualizer types
// ============================================================================

/// @brief Visualizer stream parameters sent by the server in stream/start messages
struct ServerVisualizerStreamObject {
    /// @brief Data types the server will stream
    std::vector<VisualizerDataType> types{};
    /// @brief Periodic frames per second the server will emit
    uint16_t rate_max{};
    /// @brief True if the server's beat tracker also identifies bar starts (downbeats).
    /// Only meaningful when types includes BEAT
    bool tracks_downbeats{false};
    /// @brief Spectrum configuration, present when types includes SPECTRUM
    std::optional<VisualizerSpectrumConfig> spectrum;
};

/// @brief Listener for visualizer role events
///
/// Data values (loudness, spectrum bins, f_peak amplitude) use the full uint16 range
/// 0-65535, where 0 = silence (-60 dB) and 65535 = full scale (0 dB), A-weighted and
/// mapped linearly in dB across that range.
///
/// THREAD SAFETY: the per-type data callbacks (on_loudness, on_beat, on_f_peak,
/// on_spectrum, on_peak) fire on a dedicated drain thread, VisualizerRoleConfig::display_offset_ms
/// ahead of each frame's display timestamp (client_timestamp stays the display time). A frame
/// already past its display time on arrival, or more than 20 ms behind schedule, is dropped
/// without a callback. Implementations must be thread-safe for these methods (copy data quickly,
/// defer heavy processing). on_visualizer_stream_start/end/clear fire on the main loop thread.
class VisualizerRoleListener {
public:
    virtual ~VisualizerRoleListener() = default;

    /// @brief Called with an overall A-weighted loudness value
    virtual void on_loudness(int64_t /*client_timestamp*/, uint16_t /*loudness*/) {}

    /// @brief Called on musical beat events
    /// @param downbeat True if this beat is a bar start; always false unless the stream
    ///                 was started with tracks_downbeats
    virtual void on_beat(int64_t /*client_timestamp*/, bool /*downbeat*/) {}

    /// @brief Called with the dominant FFT frequency
    /// @param frequency_hz Dominant frequency in Hz (0 = no peak detected)
    /// @param amplitude Amplitude of the dominant frequency (0 when no peak detected)
    virtual void on_f_peak(int64_t /*client_timestamp*/, uint16_t /*frequency_hz*/,
                           uint16_t /*amplitude*/) {}

    /// @brief Called with spectrum magnitudes per display bin, low to high frequency
    /// @note The vector is reused across calls; copy it if it must outlive the callback
    virtual void on_spectrum(int64_t /*client_timestamp*/, const std::vector<uint16_t>& /*bins*/) {}

    /// @brief Called on energy onset (transient) events, independent of musical timing
    /// @param strength Onset strength 0-255 for scaling flash intensity
    virtual void on_peak(int64_t /*client_timestamp*/, uint8_t /*strength*/) {}

    /// @brief Called when a visualizer stream starts or its configuration changes
    virtual void on_visualizer_stream_start(const ServerVisualizerStreamObject& /*stream*/) {}

    /// @brief Called when a visualizer stream ends
    ///
    /// Also fires when a server/activate takes the visualizer role out of the session's active
    /// roles, which discards the buffered frames along with the stream.
    virtual void on_visualizer_stream_end() {}

    /// @brief Called when a visualizer stream is cleared
    virtual void on_visualizer_stream_clear() {}
};

/**
 * @brief Visualizer role that receives real-time audio visualization data from the server
 *
 * Receives timestamped loudness, beat, dominant-frequency, spectrum, and onset data from
 * the server and delivers each datum to the platform through VisualizerRoleListener
 * callbacks at the correct playback timestamp. A dedicated drain thread handles timing and
 * delivery of data callbacks; lifecycle callbacks fire on the main loop thread.
 *
 * Usage:
 * 1. Implement VisualizerRoleListener with the data callbacks you need
 * 2. Fill a VisualizerRoleConfig: the buffer capacity it can hold, and the data types,
 *    frame-rate cap and spectrum layout it wants streamed
 * 3. Add the role to the client via SendspinClient::add_visualizer()
 * 4. Call set_listener() with your listener implementation
 *
 * @code
 * struct MyVisualizerListener : VisualizerRoleListener {
 *     void on_spectrum(int64_t client_timestamp, const std::vector<uint16_t>& bins) override {
 *         display.update_spectrum(bins);
 *     }
 *     void on_beat(int64_t client_timestamp, bool downbeat) override {
 *         display.flash_beat(downbeat);
 *     }
 * };
 *
 * MyVisualizerListener listener;
 * VisualizerRoleConfig config;
 * config.support.buffer_capacity = 4096;
 * config.stream.types = {VisualizerDataType::SPECTRUM, VisualizerDataType::BEAT};
 * config.stream.rate_max = 30;
 * config.stream.spectrum = VisualizerSpectrumConfig{
 *     .n_disp_bins = 32,
 *     .scale = VisualizerSpectrumScale::MEL,
 *     .f_min = 40,
 *     .f_max = 16000,
 * };
 * auto& visualizer = client.add_visualizer(config);
 * visualizer.set_listener(&listener);
 * @endcode
 */
class VisualizerRole {
    friend class SendspinClient;

public:
    struct Impl;

    VisualizerRole(VisualizerRoleConfig config, SendspinClient* client);
    ~VisualizerRole();

    /// @brief Sets the listener for visualizer events; it must outlive this role
    void set_listener(VisualizerRoleListener* listener);

private:
    std::unique_ptr<Impl> impl_;
};

}  // namespace sendspin
