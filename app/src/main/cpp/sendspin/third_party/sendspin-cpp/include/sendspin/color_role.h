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

/// @file color_role.h
/// @brief Color role that receives audio-derived colors from the server

#pragma once

#include <array>
#include <cstdint>
#include <memory>
#include <optional>

namespace sendspin {

class SendspinClient;

// ============================================================================
// Color types
// ============================================================================

/// @brief 8-bit RGB color triple, ordered [R, G, B] with values 0-255
using RgbColor = std::array<uint8_t, 3>;

/// @brief Audio-derived color palette received from the server
///
/// Each color field is `nullopt` when the palette the server sent does not carry that color. The
/// server guarantees WCAG contrast on the background_dark/background_light variants when present.
struct ServerColorStateObject {
    int64_t timestamp{};
    /// @brief Background color suitable for dark mode; safe contrast with white text and on_dark
    std::optional<RgbColor> background_dark;
    /// @brief Background color suitable for light mode; safe contrast with black text and on_light
    std::optional<RgbColor> background_light;
    /// @brief Dominant color, not adjusted for contrast
    std::optional<RgbColor> primary;
    /// @brief Secondary or complementary color, not adjusted for contrast
    std::optional<RgbColor> accent;
    /// @brief Light foreground suitable for use on dark backgrounds
    std::optional<RgbColor> on_dark;
    /// @brief Dark foreground suitable for use on light backgrounds
    std::optional<RgbColor> on_light;
};

/// @brief Listener for color role events. All methods fire on the main loop thread.
class ColorRoleListener {
public:
    virtual ~ColorRoleListener() = default;

    /// @brief Called when the color palette is updated by the server
    virtual void on_color(const ServerColorStateObject& /*color*/) {}

    /// @brief Called when the cached colors are dropped: the connection to the server was lost,
    /// or a server/activate took the color role out of the session's active roles
    ///
    /// Implementations should reset displayed colors to a neutral default. Idempotent by
    /// contract: a second clear with nothing to clear must be a no-op. A role removed from an
    /// active session can be added back by a later activation, which resumes with a fresh
    /// on_color().
    virtual void on_color_clear() {}
};

/**
 * @brief Color role that receives audio-derived colors from the server
 *
 * Maintains a local shadow of the server's color palette. Each palette the server sends carries
 * the full state, so it replaces the shadow outright. The
 * palette is delivered to the listener on the main loop thread once the synchronized client clock
 * reaches its `timestamp` (or immediately if there is no active connection).
 *
 * Usage:
 * 1. Implement ColorRoleListener to receive color updates
 * 2. Add the role to the client via SendspinClient::add_color()
 * 3. Call set_listener() with your listener implementation
 *
 * @code
 * struct MyColorListener : ColorRoleListener {
 *     void on_color(const ServerColorStateObject& c) override {
 *         if (c.primary) led.set_color((*c.primary)[0], (*c.primary)[1], (*c.primary)[2]);
 *     }
 * };
 *
 * MyColorListener listener;
 * auto& color = client.add_color();
 * color.set_listener(&listener);
 * @endcode
 */
class ColorRole {
    friend class SendspinClient;

public:
    struct Impl;

    explicit ColorRole(SendspinClient* client);
    ~ColorRole();

    /// @brief Sets the listener for color events; it must outlive this role
    void set_listener(ColorRoleListener* listener);

private:
    std::unique_ptr<Impl> impl_;
};

}  // namespace sendspin
