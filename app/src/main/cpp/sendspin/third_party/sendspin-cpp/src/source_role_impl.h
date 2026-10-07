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

/// @file source_role_impl.h
/// @brief Private implementation for the source role (pimpl)

#pragma once

#include "inbox.h"
#include "sendspin/source_role.h"
#include "source_task.h"
#include "teardown_tracker.h"

#include <atomic>
#include <cstdint>
#include <memory>
#include <optional>

namespace sendspin {

class ProtocolTask;
class SendspinArenaAllocator;
class SendspinClient;
class SendspinConnection;
enum class SourceCommand : uint8_t;
struct ClientHelloMessage;
struct ClientStateMessage;

/// @brief Stream lifecycle events the protocol task queues for the main loop
enum class SourceStreamEventType : uint8_t {
    STREAMING_STARTED,  // client-stream/start was sent; write_audio() accepts audio
    STREAMING_STOPPED,  // The stream closed; write_audio() rejects audio
};

/**
 * @brief Private implementation of the source role
 *
 * The protocol-task half runs the input stream's lifecycle (roles/source/v1.md "Source command
 * semantics"; docs/internals.md "Source Stream Start and End"); SourceTask carries the data path.
 */
struct SourceRole::Impl : RoleTeardown {
    Impl(SourceRoleConfig config, SendspinClient* client);
    ~Impl();

    /// @brief Whether a config is a usable capture format, logging every rejection at ERROR.
    static bool validate_config(const SourceRoleConfig& config);

    // ========================================
    // Internal integration methods (called by SendspinClient)
    // ========================================

    /// @param arena The client's JSON arena, which the protocol-task half builds its stream
    ///        messages in.
    void attach(Inbox& inbox, SendspinArenaAllocator& arena);
    /// @brief Starts the source task, unless the config is invalid, which leaves the role inert.
    /// Main loop, from SendspinClient::start(), before the protocol task starts.
    bool start(ProtocolTask* protocol_task) const;
    /// @brief Joins the source task; no-op if it is not running. Main loop.
    void stop() const;
    void build_hello_fields(ClientHelloMessage& msg) const;
    /// @brief The client/state source object. Main loop, from build_client_state().
    void build_state_fields(ClientStateMessage& msg) const;

    // The protocol-task half. Protocol task; cleanup() also runs on the main loop in
    // SendspinClient::stop() once that task is joined.

    /// @brief A server/command source object from `conn`, which owns the role
    /// (roles/source/v1.md "Source command semantics")
    /// @param available Whether the adopted client/state snapshot reports the client available.
    void handle_server_command(SourceCommand command, SendspinConnection& conn, bool available);
    /// @brief Clears any pending authorization and closes the open stream, sending
    /// client-stream/end on `conn` when the stream is open there, or owing it to the next
    /// activation inside a quiet window.
    /// @param conn The connection that owns or owned the role, or nullptr.
    void end_stream(SendspinConnection* conn);
    /// @brief A server/activate was applied on `conn`: sends the client-stream/end a close inside
    /// its quiet window owed.
    void on_activation_applied(SendspinConnection& conn);
    /// @brief Once per protocol tick: opens a stream authorized on `owner` once it may open, then
    /// sends the chunks the source task completed, at most OUTBOUND_RING_ITEM_COUNT per tick.
    /// @param owner The admitted connection that owns the role, or nullptr.
    void send_chunks(SendspinConnection* owner);
    /// @brief Closes the gate without a message, clears the authorization and queues
    /// SOURCE_CLEARED stamped with the new teardown generation. Recalls nothing: the source holds
    /// no inbound ring item.
    void cleanup();

    // The main-loop half.

    /// @brief Fires the listener callback for a current stream event, keeping started and stopped
    /// paired 1:1 through streaming_active, and holds high-performance networking while the
    /// stream is open. Main loop.
    void handle_stream_ring_event(SourceStreamEventType event);
    /// @brief The main-loop teardown half: the STREAMING_STOPPED a teardown owes the listener, when
    /// a stream was open. Main loop only, through catch_up_teardown().
    void complete_teardown();

    // ========================================
    // Consumer-facing method implementations
    // ========================================

    bool write_audio(const uint8_t* data, size_t len, int64_t capture_time_us) const;
    void set_signal(SourceSignal new_signal);

    // ========================================
    // Helpers
    // ========================================

    /// @brief Sends client-stream/start on `conn` and opens the gate, if an opening is authorized
    /// there and `conn` is time-synced (chunks are stamped through its filter) and outside a
    /// quiet window.
    void try_open(SendspinConnection& conn);
    /// @brief The codec of a stream opened on `conn`: the configured one when the server lists it
    /// (roles/source/v1.md "server/hello source@v1 support object"), otherwise pcm, which every
    /// server accepts
    SendspinCodecFormat choose_codec(const SendspinConnection& conn) const;
    /// @brief Closes the gate on the current generation
    void close_gate();
    /// @brief Queues a stream lifecycle event stamped with the current teardown generation
    void enqueue_stream_event(SourceStreamEventType event) const;
    /// @brief Sends client-stream/end on `conn`
    void send_stream_end(SendspinConnection& conn) const;

    // ========================================
    // Fields
    // ========================================

    // Struct fields
    SourceRoleConfig config;
    /// Last set_signal() value, reported in client/state once set. Main loop only.
    std::optional<SourceSignal> signal;

    // Pointer fields
    SendspinClient* client;
    Inbox* inbox{nullptr};
    SendspinArenaAllocator* json_arena{nullptr};
    SourceRoleListener* listener{nullptr};
    std::unique_ptr<SourceTask> task;

    // 64-bit fields
    /// Instance id of the connection a pending authorization is for; 0 for none. Protocol task,
    /// like the next two.
    uint64_t authorized_connection_id{0};
    /// Instance id of the connection the open stream belongs to; 0 while closed.
    uint64_t open_connection_id{0};
    /// Instance id of the connection a close inside its quiet window owes client-stream/end; 0
    /// for none.
    uint64_t owed_end_connection_id{0};

    // 32-bit fields
    /// write_audio()'s gate: SOURCE_GATE_OPEN while a stream is open, and the stream generation
    /// below it. Written by the protocol task (or the main loop in stop() once it is joined) with
    /// release; read with acquire by the capture thread and the source task, and by the protocol
    /// task's chunk sends.
    std::atomic<uint32_t> stream_gate{0};

    // 8-bit fields
    /// The codec of the stream the gate's generation names. Written before the gate opens it,
    /// read by the source task once the gate admitted that generation.
    std::atomic<SendspinCodecFormat> stream_codec{SendspinCodecFormat::PCM};
    /// Set once in the constructor; an invalid config leaves the role inert.
    const bool config_valid;
    /// Keeps the listener's started/stopped callbacks, and the high-performance hold, paired 1:1.
    /// Main loop only.
    bool streaming_active{false};
    /// Whether a start refused because the role is not running was logged. Protocol task only.
    bool not_running_warned{false};
};

}  // namespace sendspin
