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

/// @file controller_role.h
/// @brief Playback controller role that sends commands to and receives state from the Sendspin
/// server

#pragma once

#include <cstdint>
#include <memory>
#include <optional>
#include <vector>

namespace sendspin {

class SendspinClient;

// ============================================================================
// Controller types
// ============================================================================

/// @brief Playback commands the controller role can send to the server
enum class SendspinControllerCommand : uint8_t {
    PLAY,           // Resume or start playback
    PAUSE,          // Pause playback
    STOP,           // Stop playback
    NEXT,           // Skip to next track
    PREVIOUS,       // Skip to previous track
    VOLUME,         // Set volume level
    MUTE,           // Set mute state
    REPEAT_OFF,     // Disable repeat
    REPEAT_ONE,     // Repeat current track
    REPEAT_ALL,     // Repeat entire queue
    SHUFFLE,        // Enable shuffle
    UNSHUFFLE,      // Disable shuffle
    SWITCH,         // Switch playback source
    SEEK,           // Seek to an absolute position
    SEEK_RELATIVE,  // Seek by a relative offset
};

/// @brief Repeat mode for playback
enum class SendspinRepeatMode : uint8_t {
    OFF,  // No repeat
    ONE,  // Repeat current track
    ALL,  // Repeat entire queue
};

/// @brief Controller state received from the server in server/state messages
struct ServerStateControllerObject {
    std::vector<SendspinControllerCommand> supported_commands{};
    uint8_t volume{};
    bool muted{};
    SendspinRepeatMode repeat{SendspinRepeatMode::OFF};
    bool shuffle{};
    // Maximum absolute position (ms) a 'seek' may target. Present only when the server offers the
    // 'seek' command and the seekable range is known; absent for live/unknown-duration streams.
    std::optional<uint32_t> seek_max_ms{};
};

/// @brief A playback command sent from the client to the server via client/command messages.
///
/// Construct with designated initializers, setting only the field the command uses:
/// @code
/// controller.send_command({.command = SendspinControllerCommand::PLAY});
/// controller.send_command({.command = SendspinControllerCommand::SEEK, .position_ms = 30000});
/// @endcode
struct ClientCommandControllerObject {
    SendspinControllerCommand command{};
    std::optional<uint8_t> volume{};        // only for VOLUME (0-100)
    std::optional<bool> muted{};            // only for MUTE
    std::optional<uint32_t> position_ms{};  // only for SEEK (0 to ServerStateControllerObject::
                                            // seek_max_ms)
    std::optional<int32_t> offset_ms{};     // only for SEEK_RELATIVE (signed offset from current)
};

/// @brief Listener for controller role events. All methods fire on the main loop thread.
class ControllerRoleListener {
public:
    virtual ~ControllerRoleListener() = default;

    /// @brief Called when the server sends updated controller state
    virtual void on_controller_state(const ServerStateControllerObject& /*state*/) {}

    /// @brief Called when the cached controller state is dropped: the connection to the server was
    /// lost, or a server/activate took the controller role out of the session's active roles
    ///
    /// Implementations should clear any displayed controller state (volume, mute, repeat,
    /// shuffle, supported commands) since the previous state is no longer valid. Idempotent by
    /// contract: a second clear with nothing to clear must be a no-op. A role removed from an
    /// active session can be added back by a later activation, which resumes with a fresh
    /// on_controller_state().
    virtual void on_controller_state_clear() {}
};

/**
 * @brief Playback controller role that sends commands to and receives state from the server
 *
 * Maintains a local copy of the server's controller state (volume, mute, repeat, shuffle,
 * supported commands) and provides a send_command() method for dispatching playback control
 * messages. State updates from the server are queued and delivered to the listener on the
 * main loop thread.
 *
 * Usage:
 * 1. Implement ControllerRoleListener to receive server state updates
 * 2. Add the role to the client via SendspinClient::add_controller()
 * 3. Call set_listener() with your listener implementation
 *
 * @code
 * struct MyControllerListener : ControllerRoleListener {
 *     void on_controller_state(const ServerStateControllerObject& state) override {
 *         update_volume_ui(state.volume, state.muted);
 *         update_repeat_ui(state.repeat);
 *         update_shuffle_ui(state.shuffle);
 *     }
 * };
 *
 * MyControllerListener listener;
 * auto& controller = client.add_controller();
 * controller.set_listener(&listener);
 * controller.send_command({.command = SendspinControllerCommand::PLAY});
 * controller.send_command({.command = SendspinControllerCommand::VOLUME, .volume = 75});
 * @endcode
 */
class ControllerRole {
    friend class SendspinClient;

public:
    struct Impl;

    explicit ControllerRole(SendspinClient* client);
    ~ControllerRole();

    /// @brief Returns the current controller state from the server
    const ServerStateControllerObject& get_controller_state() const;

    /// @brief Sets the listener for controller events; it must outlive this role
    void set_listener(ControllerRoleListener* listener);

    /// @brief Sends a controller command to the server; callable from any thread
    ///
    /// Sent only while controller@v1 is among the connection's active roles: the protocol task
    /// drops a command issued outside that window, though this returns true once it is queued.
    /// A command missing from the latest supported_commands, or without the parameter it
    /// requires (volume in 0-100, mute, position_ms, offset_ms), is dropped with a warning. Both
    /// checks run on the calling thread; a command that passes them is queued to the library's
    /// protocol task, which formats and sends it.
    /// @param cmd The command plus any command-specific parameters
    /// @return false when the command was dropped before it reached the library's protocol task:
    ///         not in supported_commands, a missing parameter, the client not running, or the
    ///         request queue full. Retry only on a full queue (a burst of commands faster than the
    ///         protocol task drains them); gate on supported_commands for the rest, since a retry
    ///         cannot fix them. true means queued, not sent: the protocol task still drops it
    ///         unless the connection that owns the controller role has it active.
    bool send_command(const ClientCommandControllerObject& cmd);

private:
    std::unique_ptr<Impl> impl_;
};

}  // namespace sendspin
