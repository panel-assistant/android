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

/// @file client_dispatch.cpp
/// @brief SendspinClient's protocol-task half: the tick, the command handler and the routing of
/// every inbound message to the connection manager and the roles

#include "connection.h"
#include "connection_manager.h"
#include "inbound_ring.h"
#include "noise_handshake.h"
#include "platform/compiler.h"
#include "platform/json_arena.h"
#include "platform/logging.h"
#include "platform/time.h"
#include "protocol_messages.h"
#include "protocol_task.h"
#include "record_store.h"
#include "sendspin/client.h"
#include "time_burst.h"
#ifdef SENDSPIN_ENABLE_ARTWORK
#include "artwork_role_impl.h"
#endif
#ifdef SENDSPIN_ENABLE_COLOR
#include "color_role_impl.h"
#endif
#ifdef SENDSPIN_ENABLE_CONTROLLER
#include "controller_role_impl.h"
#endif
#ifdef SENDSPIN_ENABLE_METADATA
#include "metadata_role_impl.h"
#endif
#ifdef SENDSPIN_ENABLE_PLAYER
#include "player_role_impl.h"
#endif
#ifdef SENDSPIN_ENABLE_SOURCE
#include "source_role_impl.h"
#endif
#ifdef SENDSPIN_ENABLE_VISUALIZER
#include "visualizer_role_impl.h"
#endif
#include <ArduinoJson.h>

#include <algorithm>
#include <optional>
#include <utility>
#include <vector>

static const char* const TAG = "sendspin.client";

namespace sendspin {

namespace {

/// @brief Whether inbound traffic for `role` from `conn` may be acted on.
///
/// Every role this client drives has at most one owner among the admitted connections, and only
/// that connection's traffic reaches the role: a connection that is not admitted, or one that
/// does not own the role, is ignored. Ownership implies the role is active on the connection.
///
/// messaging.md "Communication" keeps a message the client implements *recognized* while its role
/// is inactive ("An ID the receiver implements is still recognized when its role is inactive"), so
/// this is not the unknown-message rule: the message is parsed and validated as usual, the
/// connection is never closed for it, and only the role's own handling is skipped. messaging.md
/// "server/activate" requires the mirror of this of servers, so a role removal the peer has not
/// yet seen never costs the connection.
///
/// The handler a true verdict admits loads the role's teardown generation itself and stamps what
/// it queues with it for the drains to check (RoleTeardown). Protocol task only.
[[maybe_unused]] bool role_accepts_traffic(const ConnectionManager& manager,
                                           const SendspinConnection* conn, SendspinRole role) {
    if (manager.owns_role(conn, role)) {
        return true;
    }
    SS_LOGD(TAG, "Ignoring %s traffic: the connection does not own the role", to_cstr(role));
    return false;
}

}  // namespace

// ============================================================================
// Protocol task
// ============================================================================

uint32_t SendspinClient::protocol_tick() {
    ConnectionManager& manager = *this->connection_manager_;

    // 1. The lifecycle requests posted since the last tick, then the commands in the order they
    //    were queued. A request and a command arriving between the same two ticks are not
    //    ordered against each other, since a request is a slot rather than a queue entry; the
    //    requests go first. A send queued ahead of a disconnect() finds its connection dropped;
    //    an accept queued ahead of it still enters the nursery. Each command's connection is
    //    released as the next take replaces it.
    {
        LifecycleRequests requests;
        if (this->protocol_task_->take_requests(requests)) {
            this->apply_lifecycle_requests(requests);
        }
        ProtocolCommand command;
        while (this->protocol_task_->take_command(command)) {
            this->handle_command(command);
        }
    }

    // 2. Once admission is closed: the shutdown pass. Every slot is empty afterwards, so the
    //    steps below find no connection and the ring pass only returns the items still in
    //    flight. An open input stream ends ahead of the goodbye.
    if (manager.shutdown_pending()) {
#ifdef SENDSPIN_ENABLE_SOURCE
        if (this->source_) {
            this->source_->impl_->end_stream(manager.role_owner(SendspinRole::SOURCE));
        }
#endif
        manager.shutdown();
    }

    // 3. The newest client/state snapshot, sent to every admitted connection that can take it.
    {
        ClientStateMessage snapshot;
        if (this->protocol_task_->take_state(snapshot)) {
            this->adopt_client_state(std::move(snapshot));
        }
    }

    // 4. client/init on the outbound connections whose upgrade completed: the server says
    //    nothing before it, so it goes ahead of the receive pass.
    manager.start_upgraded_handshakes();

    // 5. The receive pass, over a snapshot of the managed connections: a handler below can drop
    //    a connection from its slot, and the snapshot keeps it alive until the tick ends (see
    //    ConnectionManager::snapshot_connections()). Each connection's message pending in its
    //    fallback buffer comes before the ring items it wrote after it, and after the ones it
    //    wrote before it: pending_message() holds it back until those are taken (InboundGate).
    ConnectionManager::ConnectionSnapshot connections;
    manager.snapshot_connections(connections);
    for (auto& conn : connections) {
        InboundMessage message;
        if (!conn->pending_message(message)) {
            continue;
        }
        // A detached connection's message is dropped inside (process_inbound_message()); the
        // buffer goes back to the transport either way.
        this->process_inbound(*conn, message);
        conn->consume_pending_message();
    }

    // The ring, in arrival order across connections. Bounded per tick so the steps around it (a
    // command, a pending pre-admission message, a close report, a timer) come round again while
    // a stream keeps the ring busy: an audio or visualizer item costs microseconds to hand over,
    // so 32 of them delay those steps by well under a millisecond, while 32 still covers 0.6 s
    // of 20 ms audio chunks in one pass. The tick asks to run again at once when it stops here.
    constexpr size_t MAX_ITEMS_PER_TICK = 32;
    bool ring_drained = true;
    for (size_t taken = 0;; ++taken) {
        if (taken == MAX_ITEMS_PER_TICK) {
            ring_drained = false;
            break;
        }
        size_t item_len = 0;
        void* item = this->inbound_ring_->take(&item_len, 0);
        if (item == nullptr) {
            break;
        }
        const InboundItemHeader* header = inbound_item_header(item);
        SendspinConnection* conn = nullptr;
        for (auto& candidate : connections) {
            if (static_cast<uint32_t>(candidate->get_instance_id()) == header->connection_id) {
                conn = candidate.get();
                break;
            }
        }
        if (conn == nullptr) {
            // The connection left the manager since it wrote the item.
            this->inbound_ring_->return_item(item);
            continue;
        }
        // Counted before dispatch, which a detached connection's item skips (returned by
        // process_inbound()).
        conn->inbound_gate().note_item_taken();
        InboundMessage message;
        message.item = item;
        message.item_len = item_len;
        message.data = inbound_item_bytes(item);
        message.len = item_len;
        message.receive_time_us = header->receive_time_us;
        message.kind = header->kind;
        this->process_inbound(*conn, message);
    }

    // 6. Losses: a connection the receive path or a failed send closed, one the manager released,
    //    or one whose transport closed with every message it sent before the close processed, is
    //    dropped. One the manager already released this tick is a no-op there, and the next
    //    tick's snapshot leaves it out. A fallback message held back behind ring items the pass
    //    above has now taken is due at once.
    bool fallback_due = false;
    for (auto& conn : connections) {
        InboundGate& gate = conn->inbound_gate();
        if (gate.is_detached() || gate.close_ready()) {
            manager.on_connection_lost(conn.get());
        }
        fallback_due = fallback_due || (gate.has_pending_message() && gate.in_flight() == 0);
    }

    // 7. The lifecycle scans and timers, 8. the time bursts.
    uint32_t next_deadline = manager.tick(platform_time_us());
    next_deadline = std::min(next_deadline, manager.run_time_sync());

    // 9. The source's input stream: an authorized opening that may happen now (a time sync or
    //    an activation in this tick can allow it), then the chunks the source task completed,
    //    each stamped through the stream connection's time filter as it is sent.
#ifdef SENDSPIN_ENABLE_SOURCE
    if (this->source_) {
        this->source_->impl_->send_chunks(manager.role_owner(SendspinRole::SOURCE));
    }
#endif

    // 10. Losses from the sends of steps 7 to 9 (SendspinConnection::settle_noise_send()),
    //     dropped before step 11 publishes. Every connection those steps send on is in the
    //     snapshot: no step after the commands brings in a new one.
    for (auto& conn : connections) {
        if (conn->inbound_gate().is_detached()) {
            manager.on_connection_lost(conn.get());
        }
    }

    // 11. What other threads read.
    manager.refresh_published_state();
    manager.publish_connected();
    if (!ring_drained || fallback_due) {
        next_deadline = 0;
    }
    // The snapshot's references drop here; see ConnectionManager::snapshot_connections().
    return next_deadline;
}

void SendspinClient::apply_lifecycle_requests(const LifecycleRequests& requests) {
    ConnectionManager& manager = *this->connection_manager_;
    // The gate handle_command() applies to every consumer command, for the same reason.
    if (!manager.is_accepting()) {
        SS_LOGD(TAG, "Dropping lifecycle requests: the client is stopping");
        return;
    }
    // Ahead of the disconnect, which leaves no connection for the others to act on: the setting
    // and the window are applied to the connections as they stand, and the client/leave reaches
    // the server before the goodbye that follows it. The connect comes last: a connect waiting
    // beside a disconnect was posted after it (LifecycleRequests), so it opens the new attempt
    // the disconnect must not release.
    if (requests.unpaired_access_changed) {
        manager.apply_unpaired_access_change(
            this->unpaired_access_enabled_.load(std::memory_order_acquire));
    }
    switch (requests.pairing_window) {
        case PairingWindowRequest::NONE:
            break;
        case PairingWindowRequest::CONFIRM:
            manager.open_pairing_window();
            break;
        case PairingWindowRequest::CANCEL:
            manager.cancel_pairing_window();
            break;
    }
    if (requests.leave) {
        manager.leave();
    }
    if (requests.disconnect.has_value()) {
        manager.disconnect(requests.disconnect.value());
    }
    if (requests.connect_to.has_value()) {
        manager.connect_to(requests.connect_to.value());
    }
}

void SendspinClient::handle_command(ProtocolCommand& command) {
    ConnectionManager& manager = *this->connection_manager_;
    switch (command.type) {
        case ProtocolCommandType::ACCEPT_CONNECTION:
            manager.accept(std::move(command.connection));
            return;
        case ProtocolCommandType::SEND_CONTROLLER_COMMAND:
            break;
    }
    // A consumer request that reaches the task once admission is closed belongs to a run that
    // is ending: the shutdown pass goodbyes every peer, so acting on it could only open or
    // address a connection that is about to be closed.
    if (!manager.is_accepting()) {
        SS_LOGD(TAG, "Dropping a controller command: the client is stopping");
        return;
    }
#ifdef SENDSPIN_ENABLE_CONTROLLER
    // roles/controller/v1.md "client/command controller object": only a command in the latest
    // supported_commands. A command validated against an owner that a teardown replaced before
    // this drain (a switchover that admitted a new owner later in the tick it was queued during)
    // was never offered by the new one. cleanup() bumps the generation on this task too, so the
    // comparison is exact.
    if (this->controller_ != nullptr &&
        command.controller_generation !=
            static_cast<uint16_t>(
                this->controller_->impl_->cleanup_generation.load(std::memory_order_acquire))) {
        SS_LOGD(TAG, "Dropping a controller command: validated against a torn-down owner");
        return;
    }
#endif
    // Formatted here rather than on the caller's thread so the document is built in the task's
    // JSON arena, and only once the role gate has passed, so a command no connection may receive
    // formats nothing.
    SendspinConnection* conn = manager.role_send_target(SendspinRole::CONTROLLER);
    if (conn != nullptr) {
        conn->send_app_json(
            format_client_command_message(command.controller_command, *this->json_arena_));
    }
}

void SendspinClient::process_inbound(SendspinConnection& conn, InboundMessage& message) {
    // One reset per inbound message of any kind, the Noise handshake frames included, reclaiming
    // what the documents of the previous one stranded (see SendspinArenaAllocator). Safe: this is
    // called only from the tick's top level, where no arena document is live; every document the
    // task parsed or built since the last reset was destroyed before its parser or builder
    // returned.
    this->json_arena_->reset();
    InboundMessage complete;
    switch (conn.process_inbound_message(message, complete)) {
        case SendspinConnection::InboundDispatch::NONE:
            break;
        case SendspinConnection::InboundDispatch::JSON:
            this->process_json_message(
                conn, reinterpret_cast<const char*>(complete.data), complete.len,
                widen_time_stamp_us(complete.receive_time_us, platform_time_us()));
            break;
        case SendspinConnection::InboundDispatch::BINARY:
            this->process_binary_message(conn, complete);
            break;
    }
    // At most one of the two holds the ring item.
    if (complete.item != nullptr) {
        this->inbound_ring_->return_item(complete.item);
    }
    if (message.item != nullptr) {
        this->inbound_ring_->return_item(message.item);
        message.item = nullptr;
    }
}

void SendspinClient::report_malformed_pairing_message(SendspinConnection* conn,
                                                      const char* type_name) {
    SS_LOGW(TAG, "Malformed %s; aborting any active code pairing", type_name);
    ServerPairingMessage message;
    message.kind = PairingMessageKind::MALFORMED;
    this->connection_manager_->on_pairing_message(conn, message);
}

void SendspinClient::process_json_message(SendspinConnection& connection, const char* data,
                                          size_t len, int64_t timestamp) {
    SendspinConnection* conn = &connection;
    // Every connection's messages are processed on the protocol task, one at a time, so the
    // shared arena, the parse and the handlers it dispatches to need no lock. process_inbound()
    // reset the arena before this message.
    //
    // Extract, then release, then act: handlers never receive the JsonObject. Each case below
    // copies the message into its plain struct through parsed.extract(), which releases the
    // document before any handler runs, so a reply a handler builds (client/state after
    // server/activate, a pairing reply, a re-handshake's msg2) fits the arena beside nothing. A
    // case that reads no payload calls parsed.release() before acting.
    ParsedJsonMessage parsed(*this->json_arena_);
    if (!parsed.parse(data, len)) {
        SS_LOGW(TAG, "Failed to parse JSON message");
        return;
    }

    // A lambda rather than the function itself: a function passed by reference is an indirect
    // call to the stack derivation (tools/stack_usage/), a lambda body a direct one.
    const SendspinServerToClientMessageType message_type =
        parsed.read([](JsonObject root) { return determine_message_type(root); });

    // Role-bound traffic is gated per role on ownership (role_accepts_traffic()): only the
    // admitted connection that owns a role reaches it. The connection is not closed over a
    // message it may not act on: a nursery member that is still racing toward promotion, or one
    // that just lost arbitration, is not misbehaving, and the establish/re-prove watchdogs
    // already reap a connection that never gets admitted. The server/activate that admits a
    // connection is applied before the next message is parsed, so the role traffic behind it is
    // never dropped for lack of admission.
    switch (message_type) {
        case SendspinServerToClientMessageType::STREAM_START: {
            SS_LOGD(TAG, "Stream Started");

            StreamStartMessage stream_msg;
            if (!parsed.extract<process_stream_start_message>(&stream_msg)) {
                SS_LOGE(TAG, "Failed to parse stream/start message");
                break;
            }

#ifdef SENDSPIN_ENABLE_PLAYER
            if (this->player_ && stream_msg.player.has_value() &&
                role_accepts_traffic(*this->connection_manager_, conn, SendspinRole::PLAYER)) {
                this->player_->impl_->handle_stream_start(stream_msg.player.value());
            }
#endif

#ifdef SENDSPIN_ENABLE_ARTWORK
            if (this->artwork_ && stream_msg.artwork.has_value() &&
                role_accepts_traffic(*this->connection_manager_, conn, SendspinRole::ARTWORK)) {
                this->artwork_->impl_->handle_stream_start(stream_msg.artwork.value());
            }
#endif

#ifdef SENDSPIN_ENABLE_VISUALIZER
            if (this->visualizer_ && stream_msg.visualizer.has_value() &&
                role_accepts_traffic(*this->connection_manager_, conn, SendspinRole::VISUALIZER)) {
                this->visualizer_->impl_->handle_stream_start(stream_msg.visualizer.value());
            }
#endif
            break;
        }
        case SendspinServerToClientMessageType::STREAM_END: {
            StreamEndMessage end_msg;
            if (parsed.extract<process_stream_end_message>(&end_msg)) {
                bool end_player = !end_msg.roles.has_value();
                bool end_artwork = !end_msg.roles.has_value();
                bool end_visualizer = !end_msg.roles.has_value();

                if (end_msg.roles.has_value()) {
                    for (const auto& role : end_msg.roles.value()) {
                        if (role == "player") {
                            end_player = true;
                        } else if (role == "artwork") {
                            end_artwork = true;
                        } else if (role == "visualizer") {
                            end_visualizer = true;
                        }
                    }
                }

                SS_LOGD(TAG, "Stream ended - player:%d artwork:%d visualizer:%d", end_player,
                        end_artwork, end_visualizer);

#ifdef SENDSPIN_ENABLE_PLAYER
                if (this->player_ && end_player &&
                    role_accepts_traffic(*this->connection_manager_, conn, SendspinRole::PLAYER)) {
                    this->player_->impl_->handle_stream_end();
                }
#endif

#ifdef SENDSPIN_ENABLE_ARTWORK
                if (this->artwork_ && end_artwork &&
                    role_accepts_traffic(*this->connection_manager_, conn, SendspinRole::ARTWORK)) {
                    this->artwork_->impl_->handle_stream_end();
                }
#endif

#ifdef SENDSPIN_ENABLE_VISUALIZER
                if (this->visualizer_ && end_visualizer &&
                    role_accepts_traffic(*this->connection_manager_, conn,
                                         SendspinRole::VISUALIZER)) {
                    this->visualizer_->impl_->handle_stream_end();
                }
#endif
            }
            break;
        }
        case SendspinServerToClientMessageType::STREAM_CLEAR: {
            StreamClearMessage clear_msg;
            if (parsed.extract<process_stream_clear_message>(&clear_msg)) {
                // messaging.md "stream/clear": only player and visualizer streams clear, and an
                // omitted roles list clears both.
                bool clear_player = !clear_msg.roles.has_value();
                bool clear_visualizer = !clear_msg.roles.has_value();

                if (clear_msg.roles.has_value()) {
                    for (const auto& role : clear_msg.roles.value()) {
                        if (role == "player") {
                            clear_player = true;
                        } else if (role == "visualizer") {
                            clear_visualizer = true;
                        }
                    }
                }

                SS_LOGD(TAG, "Stream clear - player:%d visualizer:%d", clear_player,
                        clear_visualizer);

#ifdef SENDSPIN_ENABLE_PLAYER
                if (this->player_ && clear_player &&
                    role_accepts_traffic(*this->connection_manager_, conn, SendspinRole::PLAYER)) {
                    this->player_->impl_->handle_stream_clear();
                }
#endif

#ifdef SENDSPIN_ENABLE_VISUALIZER
                if (this->visualizer_ && clear_visualizer &&
                    role_accepts_traffic(*this->connection_manager_, conn,
                                         SendspinRole::VISUALIZER)) {
                    this->visualizer_->impl_->handle_stream_clear();
                }
#endif
            }
            break;
        }
        case SendspinServerToClientMessageType::SERVER_HELLO: {
            ServerHelloMessage hello_msg;
            if (parsed.extract<process_server_hello_message>(&hello_msg)) {
                // server_id comes from the Noise handshake result (already set on the
                // connection); server/hello supplies the display name.
                ServerInformationObject info = conn->get_server_information();
                info.name = hello_msg.name;
                conn->set_server_information(std::move(info));
                conn->set_server_source_codecs(hello_msg.source_codecs);
                // The nursery scan on this task observes is_handshake_complete() and
                // establishes the connection; nothing needs to be scheduled here.
                conn->set_server_hello_received(true);

                SS_LOGD(TAG, "Connected to server '%s' (server_id=%s)", hello_msg.name.c_str(),
                        conn->get_server_id().c_str());
            }
            break;
        }
        case SendspinServerToClientMessageType::SERVER_ACTIVATE: {
            ServerActivateMessage activate_msg;
            if (parsed.extract<process_server_activate_message>(&activate_msg)) {
                SS_LOGD(TAG, "server/activate received (activities_count=%zu)",
                        activate_msg.activities.size());
                // Applied before the next message is parsed: trust enforcement, role
                // ownership and, for a nursery connection that is now operational, admission
                // all land here, so the role traffic the server sends behind this activate
                // meets the gate this activation set.
                this->connection_manager_->on_server_activate(conn, std::move(activate_msg));
            }
            break;
        }
        case SendspinServerToClientMessageType::NOISE_HANDSHAKE: {
            // In-band re-handshake initiated by the server. This runs on the protocol task, the
            // thread that decrypts this connection's frames and sends on it, so the session swap
            // is ordered with the decrypt of the next frame and with every send
            // (connection.md "Re-handshake").
            SS_LOGI(TAG, "noise/handshake received in-band: starting re-handshake");
            const std::optional<std::vector<uint8_t>> msg1_bytes =
                parsed.extract([](JsonObjectConst root) {
                    return read_noise_handshake_data(root, "re-handshake");
                });
            if (!msg1_bytes.has_value() || !conn->handle_noise_rehandshake(msg1_bytes.value())) {
                SS_LOGW(TAG, "noise/handshake re-handshake failed; closing connection");
                // Do not leave a half-swapped session. UNAUTHORIZED is the closest available
                // reason for a crypto failure, though close_silently() never transmits it
                // (connection.md "Failure Handling": close without any application-level
                // message). disconnect() can block on a transport join, which close_silently()
                // avoids; see close_transport_now()'s doc comment in connection.h.
                conn->close_silently(SendspinGoodbyeReason::UNAUTHORIZED);
            }
            break;
        }
        case SendspinServerToClientMessageType::SERVER_TIME: {
            ServerTimeMessage time_msg;
            if (!parsed.extract<process_server_time_message>(&time_msg)) {
                break;
            }
            // Only the reply to this connection's frame in flight is taken, so no peer can feed
            // a burst a measurement it did not ask for. Each connection measures its own clock
            // into its own filter.
            const std::optional<int64_t> client_sent =
                conn->claim_time_frame(time_msg.client_transmitted);
            if (!client_sent.has_value()) {
                SS_LOGV(TAG, "server/time answers no client/time in flight; discarding");
                break;
            }
            TimeResponse response;
            compute_time_exchange(time_msg, client_sent.value(), timestamp, &response.offset,
                                  &response.max_error);
            response.timestamp = timestamp;
            response.client_transmitted = time_msg.client_transmitted;
            conn->time_burst().on_time_response(conn, response);
            break;
        }
        case SendspinServerToClientMessageType::SERVER_STATE: {
            // The three sections are live together so one parse serves all three: every section a
            // role takes is extracted in the one pass that releases the document, at the cost of
            // the sections' combined size in this frame (the protocol task's stack is a fixed
            // budget on ESP-IDF). Each section's role gate is decided first, so a section no role
            // takes is not parsed.
#ifdef SENDSPIN_ENABLE_CONTROLLER
            const bool take_controller =
                this->controller_ &&
                role_accepts_traffic(*this->connection_manager_, conn, SendspinRole::CONTROLLER);
            ServerStateControllerObject controller_state;
            bool controller_valid = false;
#endif
#ifdef SENDSPIN_ENABLE_METADATA
            const bool take_metadata =
                this->metadata_ &&
                role_accepts_traffic(*this->connection_manager_, conn, SendspinRole::METADATA);
            ServerMetadataStateObject metadata_state;
            bool metadata_valid = false;
#endif
#ifdef SENDSPIN_ENABLE_COLOR
            const bool take_color = this->color_ && role_accepts_traffic(*this->connection_manager_,
                                                                         conn, SendspinRole::COLOR);
            ServerColorStateObject color_state;
            bool color_valid = false;
#endif
            parsed.extract([&]([[maybe_unused]] JsonObject root) {
#ifdef SENDSPIN_ENABLE_CONTROLLER
                controller_valid =
                    take_controller && process_server_state_controller(root, &controller_state);
#endif
#ifdef SENDSPIN_ENABLE_METADATA
                metadata_valid =
                    take_metadata && process_server_state_metadata(root, &metadata_state);
#endif
#ifdef SENDSPIN_ENABLE_COLOR
                color_valid = take_color && process_server_state_color(root, &color_state);
#endif
            });

#ifdef SENDSPIN_ENABLE_CONTROLLER
            if (controller_valid) {
                this->controller_->impl_->handle_server_state(std::move(controller_state));
            }
#endif

#ifdef SENDSPIN_ENABLE_METADATA
            if (metadata_valid) {
                this->metadata_->impl_->handle_server_state(std::move(metadata_state));
            }
#endif

#ifdef SENDSPIN_ENABLE_COLOR
            if (color_valid) {
                this->color_->impl_->handle_server_state(color_state);
            }
#endif
            break;
        }
        case SendspinServerToClientMessageType::SERVER_COMMAND: {
            // One parse serves both role objects, as for server/state: each is parsed only when
            // its role takes it, and a malformed one never affects the other.
#ifdef SENDSPIN_ENABLE_PLAYER
            const bool take_player =
                this->player_ &&
                role_accepts_traffic(*this->connection_manager_, conn, SendspinRole::PLAYER);
            ServerCommandMessage cmd_msg;
            bool player_valid = false;
#endif
#ifdef SENDSPIN_ENABLE_SOURCE
            const bool take_source =
                this->source_ &&
                role_accepts_traffic(*this->connection_manager_, conn, SendspinRole::SOURCE);
            SourceCommand source_cmd{};
            bool source_valid = false;
#endif
            parsed.extract([&]([[maybe_unused]] JsonObject root) {
#ifdef SENDSPIN_ENABLE_PLAYER
                player_valid = take_player && process_server_command_message(root, &cmd_msg);
#endif
#ifdef SENDSPIN_ENABLE_SOURCE
                source_valid = take_source && process_server_command_source(root, &source_cmd);
#endif
            });
#ifdef SENDSPIN_ENABLE_PLAYER
            if (player_valid) {
                this->player_->impl_->handle_server_command(cmd_msg);
            }
#endif
#ifdef SENDSPIN_ENABLE_SOURCE
            if (source_valid) {
                // The availability step 3 adopted
                this->source_->impl_->handle_server_command(source_cmd, *conn,
                                                            this->adopted_state_available());
            }
#endif
            break;
        }
        case SendspinServerToClientMessageType::GROUP_UPDATE: {
            // group/update describes the group of the connection the client reports from: the
            // primary admitted connection.
            AdmittedEntry* primary = this->connection_manager_->primary();
            if (primary == nullptr || primary->conn.get() != conn) {
                SS_LOGD(TAG, "Ignoring group/update from a connection that is not the primary");
                break;
            }
            GroupUpdateMessage group_msg;
            if (parsed.extract<process_group_update_message>(&group_msg)) {
                this->merge_group_update(std::move(group_msg.group));
            }
            break;
        }
        case SendspinServerToClientMessageType::SERVER_PAIR_FINALIZE: {
            // server/pair-finalize: server acked our client/pair-finalize.
            // Commit the pending pairing record to RAM here: the server rekeys onto the new
            // long-term PSK immediately after this ack, and its re-handshake msg1 (the next
            // message on this same thread) resolves that PSK against the RecordStore. The record
            // must therefore be resolvable before this handler returns, else the re-handshake
            // sees an unknown psk_id and aborts. RecordStore locks its own mutex, since the main
            // loop reads it for the provider write. Only the RAM commit happens here: the provider
            // write is deferred to the main loop via request_persist() below, because the
            // persistence provider is main-loop-only.
            // The payload is spec'd as empty; the message-type dispatch above is the only
            // validation this message needs.
            parsed.release();
            auto record = conn->take_pending_pairing_record();
            bool stored_record = false;
            if (record.has_value() && this->record_store_ != nullptr) {
                // Logged before the store takes ownership of the record; a rejection warns
                // from inside store_record_superseding().
                SS_LOGI(TAG, "server/pair-finalize: storing pairing record (psk_id=%s)",
                        record->psk_id.c_str());
                // store_record_superseding() mutates RAM only. At capacity it evicts the
                // least recently used record rather than failing, since a pairing never
                // fails for lack of record storage (pairing.md "Pairing Records"); the
                // psk_ids of every open connection are handed over so none of them is the
                // victim. A provider that later rejects the deferred write does not fail the
                // pairing: the record works for this boot and persist_records() warns that it
                // will not survive a reboot.
                //
                // The superseding form is correct here and only here: this PSK replaces
                // whatever this server held before, so the prior record for the same
                // server_id must be retired or the old PSK stays valid forever.
                if (this->record_store_->store_record_superseding(
                        std::move(record.value()),
                        this->connection_manager_->open_connection_psk_ids())) {
                    stored_record = true;
                }
            } else {
                SS_LOGI(TAG, "server/pair-finalize: no pending pairing record to store");
            }
            if (stored_record) {
                // Before on_pairing_succeeded, so the drain that fires it flushes the write
                // first. Not fired for the capacity-rejection case.
                this->request_persist();
                this->connection_manager_->on_pairing_succeeded(conn);
            }
            // Re-arm the provisional timeout so the 30 s watchdog fires if the server
            // acks but never sends the in-band re-handshake that follows pair-finalize.
            conn->note_pairing_finalize_ack();
            break;
        }
        case SendspinServerToClientMessageType::PAIR_ABORT: {
            // pair/abort: the server aborted the pairing exchange.
            PairAbortMessage abort_msg;
            if (parsed.extract<process_pair_abort_message>(&abort_msg)) {
                SS_LOGW(TAG, "pair/abort received: reason=%s", to_cstr(abort_msg.reason));
                this->connection_manager_->on_pair_abort(conn, abort_msg.reason);
            } else {
                // pair/abort must trigger cleanup even when the reason is unrecognized.
                SS_LOGW(TAG, "Malformed pair/abort message; treating as abort with "
                             "method_not_supported");
                this->connection_manager_->on_pair_abort(conn,
                                                         PairAbortReason::METHOD_NOT_SUPPORTED);
            }
            break;
        }
        case SendspinServerToClientMessageType::SERVER_UNPAIR: {
            // server/unpair. Trust gating (LONG_TERM only) happens in handle_server_unpair.
            parsed.release();
            SS_LOGI(TAG, "server/unpair received (psk_id=%s)", conn->get_psk_id().c_str());
            this->connection_manager_->on_server_unpair(conn);
            break;
        }
        case SendspinServerToClientMessageType::SERVER_PAIR_INIT: {
            // server/pair-init: nonce_A from the server (the emission format arrived in the
            // activation's pairing object).
            ServerPairInitPayload payload;
            if (parsed.extract<process_server_pair_init_message>(&payload)) {
                ServerPairingMessage message;
                message.kind = PairingMessageKind::PAIR_INIT;
                message.nonce_a = payload.nonce_a;
                this->connection_manager_->on_pairing_message(conn, message);
            } else {
                this->report_malformed_pairing_message(conn, "server/pair-init");
            }
            break;
        }
        case SendspinServerToClientMessageType::SERVER_PAIR_AUTH: {
            // server/pair-auth: server CPace share.
            ServerPairAuthPayload payload;
            if (parsed.extract<process_server_pair_auth_message>(&payload)) {
                ServerPairingMessage message;
                message.kind = PairingMessageKind::PAIR_AUTH;
                message.pake_msg_1 = payload.pake_msg_1;
                this->connection_manager_->on_pairing_message(conn, message);
            } else {
                this->report_malformed_pairing_message(conn, "server/pair-auth");
            }
            break;
        }
        case SendspinServerToClientMessageType::SERVER_PAIR_CONFIRM: {
            // server/pair-confirm: server CPace confirmation tag.
            ServerPairConfirmPayload payload;
            if (parsed.extract<process_server_pair_confirm_message>(&payload)) {
                ServerPairingMessage message;
                message.kind = PairingMessageKind::PAIR_CONFIRM;
                message.server_kc = payload.server_kc;
                this->connection_manager_->on_pairing_message(conn, message);
            } else {
                this->report_malformed_pairing_message(conn, "server/pair-confirm");
            }
            break;
        }
        default: {
            // Nothing acts on an unhandled message, so its type is read in place.
            const char* type = parsed.read([](JsonObject root) {
                return root["type"].is<const char*>() ? root["type"].as<const char*>() : "unknown";
            });
            SS_LOGW(TAG, "Unhandled server message type: %s", type);
        }
    }
}

SS_HOT void SendspinClient::process_binary_message(SendspinConnection& connection,
                                                   InboundMessage& message) {
    SendspinConnection* conn = &connection;
    // One byte is enough to name the role that owns the message; how short a body that role
    // tolerates is the role's own rule (roles/artwork/v1.md, for one, closes the connection on a
    // message shorter than 2 bytes).
    if (message.len < 1) {
        return;
    }

    // Every binary message feeds a role, so only an admitted connection's reach one; each role
    // below also gates on the connection owning it (role_accepts_traffic()).
    if (this->connection_manager_->find_admitted(conn) == nullptr) {
        SS_LOGW(TAG, "Ignoring binary message from a connection that is not admitted");
        return;
    }

    uint8_t binary_type = message.data[0];
    uint8_t role = get_binary_role(binary_type);

    // The visualizer role has an expanded 8-slot allocation (IDs 16-23), so it is
    // dispatched by ID range before the standard 4-slot role decoding below
    if (binary_type >= SENDSPIN_BINARY_VISUALIZER_FIRST &&
        binary_type <= SENDSPIN_BINARY_VISUALIZER_LAST) {
#ifdef SENDSPIN_ENABLE_VISUALIZER
        if (this->visualizer_ &&
            role_accepts_traffic(*this->connection_manager_, conn, SendspinRole::VISUALIZER)) {
            this->visualizer_->impl_->handle_binary(binary_type, message);
        }
#endif
        return;
    }

    switch (role) {
        case SENDSPIN_ROLE_PLAYER: {
#ifdef SENDSPIN_ENABLE_PLAYER
            if (this->player_ &&
                role_accepts_traffic(*this->connection_manager_, conn, SendspinRole::PLAYER)) {
                uint8_t slot = get_binary_slot(binary_type);
                if (slot != 0) {
                    SS_LOGW(TAG, "Unknown player binary slot %d", slot);
                } else if (!this->adopted_state_available()) {
                    // roles/player/v1.md "Audio Chunks (Binary)": an unavailable client discards
                    // audio. Read from the snapshot step 3 adopted, so the gate flips with the
                    // client/state it sends.
                    SS_LOGV(TAG, "Discarding audio chunk while unavailable");
                } else {
                    this->player_->impl_->handle_binary(message);
                }
            }
#endif
            break;
        }
        case SENDSPIN_ROLE_ARTWORK: {
#ifdef SENDSPIN_ENABLE_ARTWORK
            // Deliberately not gated on the role being active, unlike every other role dispatch
            // here. messaging.md "Communication" keeps a message the client implements recognized
            // while its role is inactive, and "the validation, direction, and sequencing rules for
            // recognized messages still apply", so an artwork message that is malformed as a
            // message is still the protocol error the role closes on. The role already drops the
            // payload of a message that arrives outside an active stream, below those shape
            // checks, and a removed role has no active stream (cleanup() clears it and a
            // stream/start for an inactive role is refused above). It is gated on ownership
            // conflicts alone: the artwork stream belongs to its owner, so a connection that does
            // not own the role while another one does cannot feed it.
            SendspinConnection* artwork_owner =
                this->connection_manager_->role_owner(SendspinRole::ARTWORK);
            if (this->artwork_ && (artwork_owner == nullptr || artwork_owner == conn)) {
                uint8_t slot = get_binary_slot(binary_type);
                if (!this->artwork_->impl_->handle_binary(slot, message)) {
                    // roles/artwork/v1.md "Artwork (Binary)": a malformed artwork message, and a
                    // malformed sequence within an active artwork stream, are protocol errors the
                    // client MUST close the connection on. Closed silently, like every other
                    // protocol error the receive path finds (see close_silently()).
                    SS_LOGW(TAG, "Malformed artwork message; closing connection");
                    conn->close_silently(SendspinGoodbyeReason::UNAUTHORIZED);
                }
            }
#endif
            break;
        }
        default: {
            SS_LOGW(TAG, "Unknown binary role %d (type %d)", role, binary_type);
            break;
        }
    }
}

}  // namespace sendspin
