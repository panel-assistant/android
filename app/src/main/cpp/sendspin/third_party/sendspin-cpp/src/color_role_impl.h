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

/// @file color_role_impl.h
/// @brief Private implementation for the color role (pimpl)

#pragma once

#include "inbox.h"
#include "protocol_messages.h"
#include "sendspin/color_role.h"
#include "teardown_tracker.h"

#include <memory>
#include <optional>

namespace sendspin {

class SendspinClient;
struct ClientHelloMessage;

/// @brief Color palettes handed to the main loop but not yet drained
///
/// messaging.md "server/state" lets a server bring a client up to date and then schedule the next
/// update straight after it, so two palettes can land between two main-loop ticks. Both are kept,
/// in arrival order, so the current one is still applied on the tick that also takes the
/// scheduled one. A third palette in the same window replaces `newest`, which no observer has
/// seen.
struct PendingColorStates {
    std::optional<ServerColorStateObject> oldest;
    std::optional<ServerColorStateObject> newest;
};

/// @brief Private implementation of the color role
struct ColorRole::Impl : RoleTeardown {
    explicit Impl(SendspinClient* client);
    ~Impl() = default;

    // ========================================
    // Event state
    // ========================================

    struct EventState {
        GenerationSlot<PendingColorStates> slot;
    };

    // ========================================
    // Internal integration methods (called by SendspinClient)
    // ========================================

    void attach_inbox(Inbox& inbox);
    void build_hello_fields(ClientHelloMessage& msg);
    // Takes a const reference, unlike the metadata and controller overloads: a
    // ServerColorStateObject holds only optional RGB triples and a timestamp, so there is nothing
    // for an rvalue reference to move out of.
    void handle_server_state(const ServerColorStateObject& color) const;
    // True if a slot palette needs taking, or a palette already held from a prior tick (see
    // held_state) is still waiting out its server-clock deadline: the deadline itself sets no
    // inbox bit, so held_state must be polled every tick until it fires.
    bool needs_drain(uint32_t pending_bits) const {
        return (pending_bits & INBOX_TOPIC_COLOR) != 0 || this->held_state.has_value();
    }
    /// @brief Takes the slot, catches the role up on any teardown (complete_teardown()), then
    /// holds the taken palettes if they were admitted under the current generation and applies
    /// whichever is due. Main loop.
    void drain_events();
    /// Whether a palette's server-clock deadline has passed on the synchronized client clock.
    bool state_is_due(int64_t timestamp) const;
    /// Applies the held palette and fires the listener once its server-clock deadline has passed.
    void apply_due_state();

    /// @brief Stops the role and discards the state the protocol task can reach. Protocol task,
    /// or the main loop in SendspinClient::stop() once every other thread is joined.
    ///
    /// Shared by the two paths that take the role out of service: a connection being torn down
    /// (SendspinClient::cleanup_connection_state()) and a server/activate that removes the role
    /// from active_roles (SendspinClient::apply_role_removals()). Queues a COLOR_CLEARED event
    /// stamped with the new generation; the main loop runs complete_teardown() for it
    /// (catch_up_teardown()).
    void cleanup();

    /// @brief The main-loop teardown half: resets the state only the main loop touches and fires
    /// on_color_clear(). Main loop only, through catch_up_teardown().
    void complete_teardown();

    // ========================================
    // Fields
    // ========================================

    // Struct fields
    ServerColorStateObject color{};
    // Palette taken from the inbox slot, awaiting its server-clock deadline. Main-thread only:
    // written and read exclusively from drain_events()/complete_teardown() on the loop thread.
    std::optional<ServerColorStateObject> held_state;

    // Pointer fields
    SendspinClient* client;
    std::unique_ptr<EventState> event_state;
    Inbox* inbox{nullptr};
    ColorRoleListener* listener{nullptr};
};

}  // namespace sendspin
