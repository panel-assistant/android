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

/// @file player_role_impl.h
/// @brief Private implementation for the player role (pimpl)

#pragma once

#include "audio_types.h"
#include "inbound_ring.h"
#include "inbox.h"
#include "sendspin/player_role.h"
#include "sync_task.h"
#include "teardown_tracker.h"

#include <atomic>
#include <memory>
#include <optional>
#include <string>
#include <vector>

namespace sendspin {

class SendspinClient;
class SendspinPersistenceProvider;
struct ClientHelloMessage;
struct ClientStateMessage;

/// @brief Deferred stream lifecycle callback types queued from the protocol task
enum class PlayerStreamCallbackType : uint8_t {
    STREAM_START,  // New stream is starting
    STREAM_END,    // Stream ended normally
};

/// @brief One binary audio chunk, split into the parts roles/player/v1.md "Audio Chunks
/// (Binary)" defines after the message type byte.
struct AudioChunk {
    /// Server clock time when the first sample should be output (spec bytes 1-8, big-endian
    /// int64, i.e. the first eight bytes of `data`).
    int64_t timestamp_us{0};
    /// Encoded audio frame, starting at spec byte 13 (offset 12 in `data`). Points into the
    /// caller's buffer.
    const uint8_t* audio{nullptr};
    size_t audio_len{0};
};

/// @brief Private implementation of the player role
struct PlayerRole::Impl : RoleTeardown {
    Impl(PlayerRoleConfig config, SendspinClient* client);
    ~Impl();

    /// @brief Splits one audio chunk's bytes (after the message type byte) into its timestamp
    /// and its encoded audio frame.
    ///
    /// Spec bytes 9-12 carry `send_ahead`, the lead the server had in hand when it transmitted;
    /// it carries no scheduling meaning, so the chunk is parsed past it.
    /// @param data Chunk bytes with the message type byte already stripped.
    /// @return The split chunk, or nullopt when @p len is too short to hold the header.
    static std::optional<AudioChunk> parse_audio_chunk(const uint8_t* data, size_t len);

    // ========================================
    // Event state
    // ========================================

    struct EventState {
        GenerationSlot<ServerPlayerStreamObject> stream_params_slot;
        GenerationSlot<ServerCommandMessage> command_slot;
        /// Written by the sync task each time it returns to idle from a stream it was running or
        /// about to run, so a STREAM_END the drain holds for the sync task to go idle is
        /// re-examined then (see awaiting_sync_idle) rather than on every loop().
        InboxSlot<bool> sync_idle_slot;
    };

    // ========================================
    // Internal integration methods (called by SendspinClient)
    // ========================================

    void attach_inbox(Inbox& inbox);
    /// @param persistence The client's provider at this start, or nullptr; replaces the one
    ///        add_player() set.
    /// @param ring The client's inbound ring for this run, which the sync task's item list links.
    bool start(SendspinPersistenceProvider* persistence, InboundRing* ring);
    void build_hello_fields(ClientHelloMessage& msg);
    /// @brief The share of the quota that holds encoded frames at the smallest frame size (see
    /// AUDIO_BUFFER_ADVERTISE_DENOMINATOR in player_role.cpp): what the inbound ring's derivation
    /// sizes the player's longest chunk from (SendspinClient::create_inbound_ring()), before the
    /// ring, and so the bound advertised_buffer_capacity() applies, exists.
    size_t buffer_capacity_share() const;
    /// @brief The buffer_capacity client/hello advertises, which also bounds the longest chunk
    /// the server sends: buffer_capacity_share(), at most the run's largest ring item
    /// (largest_ring_item_bytes), so any one chunk the server may send fits an item even when it
    /// arrives in several Noise frames and is copied into one. Inside a run only.
    size_t advertised_buffer_capacity() const;
    void build_state_fields(ClientStateMessage& msg) const;
    // Each handler loads the role's teardown generation once at entry and stamps what it queues
    // with it; see RoleTeardown. All run on the protocol task.
    /// @brief Hands an audio chunk to the sync task: by its ring item when it has one (clearing
    /// `message.item`), otherwise copied into an item the protocol task acquires.
    /// @param message The decrypted chunk; `data` points at its message type byte.
    void handle_binary(InboundMessage& message);
    void handle_stream_start(const ServerPlayerStreamObject& player_obj);
    void handle_stream_end() const;
    void handle_stream_clear();
    void handle_server_command(const ServerCommandMessage& cmd) const;
    /// @brief Holds a PLAYER_STREAM event (code: PlayerStreamCallbackType; serial: a STREAM_START's
    /// stream ordinal) in awaiting_sync_idle_events. Main loop.
    void on_stream_ring_event(const InboxEvent& event);
    /// @brief Tells the main loop the sync task returned to idle from a stream (sync_idle_slot).
    /// Sync task.
    void note_sync_idle() const {
        this->event_state->sync_idle_slot.write(true);
    }
    // True if this tick has drainable player work: a server command (volume/mute/output delay)
    // in command_slot; the sync task having left a stream (sync_idle_slot) while a STREAM_END
    // waits for it; or stream lifecycle events in awaiting_sync_idle_events, appended by
    // on_stream_ring_event() during this tick's ring dispatch or by a teardown's catch-up
    // (complete_teardown()), that are not held for the sync task. stream_params_slot's own topic
    // bit needs no term: it is only ever consumed from the STREAM_START branch while that event
    // sits in awaiting_sync_idle_events.
    bool needs_drain(uint32_t pending_bits) const {
        return (pending_bits & (INBOX_TOPIC_PLAYER_COMMAND | INBOX_TOPIC_PLAYER_SYNC_IDLE)) != 0 ||
               (!this->awaiting_sync_idle_events.empty() && !this->awaiting_sync_idle);
    }
    void drain_events();

    /// @brief Stops the role and discards the state the protocol task can reach. Protocol task,
    /// or the main loop in SendspinClient::stop() once every other thread is joined.
    ///
    /// Shared by the two paths that take the role out of service: a connection being torn down
    /// (SendspinClient::cleanup_connection_state()) and a server/activate that removes the role
    /// from active_roles (SendspinClient::apply_role_removals()). Queues PLAYER_CLEARED, stamped
    /// with the new generation, for the main loop to catch up on (catch_up_teardown()).
    void cleanup();

    /// @brief The main-loop teardown half: replaces the stream events the teardown overtook with
    /// the STREAM_END it owes the listener (fired once the sync task reads idle) and releases the
    /// playback high-performance hold. Main loop only, through catch_up_teardown().
    void complete_teardown();

    /// @brief Joins the sync task thread and returns its buffered audio to the inbound ring;
    /// no-op if not started.
    void stop() const;

    // ========================================
    // Consumer-facing method implementations
    // ========================================

    void update_volume(uint8_t volume);
    void update_muted(bool muted);
    void update_output_delay(uint16_t delay_ms);

    // ========================================
    // Helpers
    // ========================================

    /// @brief Base64-decodes a FLAC codec header straight into a ring item (waiting up to
    /// INBOUND_ACQUIRE_TIMEOUT_MS for space) and hands it to the sync task numbered with
    /// `ordinal`. Protocol task only.
    /// @return false when the sync task is not running, the header does not decode, or the ring
    ///         had no room in time.
    bool hand_flac_header(const std::string& codec_header, uint16_t ordinal,
                          uint32_t generation) const;
    /// Queues a stream lifecycle event stamped with `generation`, which the drain compares
    /// against the live counter before dispatching it, and carrying a STREAM_START's `ordinal`.
    void enqueue_stream_event(PlayerStreamCallbackType event, uint32_t generation,
                              uint16_t ordinal) const;
    void load_output_delay();
    void persist_output_delay() const;
    uint16_t get_effective_output_delay_ms() const;

    // ========================================
    // Fields
    // ========================================

    // Struct fields
    PlayerRoleConfig config;
    ServerPlayerStreamObject current_stream_params{};
    std::vector<InboxEvent> awaiting_sync_idle_events;

    // Pointer fields
    SendspinClient* client;
    std::unique_ptr<EventState> event_state;
    Inbox* inbox{nullptr};
    PlayerRoleListener* listener{nullptr};
    SendspinPersistenceProvider* persistence{nullptr};
    std::unique_ptr<SyncTask> sync_task;

    // size_t fields
    /// This run's InboundRing::max_item_message_bytes(), which caps advertised_buffer_capacity().
    /// Written by start() on the main loop whether or not the sync task starts (a player with no
    /// listener still advertises a buffer), before the protocol task that reads it starts.
    size_t largest_ring_item_bytes{0};

    // 16-bit fields
    /// Written on the main loop (a server or consumer change, load_output_delay()); read there
    /// and on the sync task (get_effective_output_delay_ms()).
    std::atomic<uint16_t> output_delay_ms{0};
    /// The ordinal of the latest stream whose codec header went to the sync task, one per
    /// stream/start (see SyncTask::signal_stream_start()). Protocol task only, or the main loop
    /// in SendspinClient::stop() once the task is joined.
    uint16_t stream_ordinal{0};

    // 8-bit fields
    // True while the head of awaiting_sync_idle_events is a STREAM_END waiting for the sync task
    // to go idle; the drain then runs again when sync_idle_slot is written. Main loop only.
    bool awaiting_sync_idle{false};
    bool high_performance_requested_for_playback{false};
    bool muted{false};
    // True between the drained STREAM_START and STREAM_END callbacks (main-thread only); keeps
    // on_stream_end() from firing without a matching on_stream_start()
    bool stream_active{false};
    /// Written by set_output_delay_adjustable() on the consumer's thread; read on the main loop
    /// and the sync task.
    std::atomic<bool> output_delay_adjustable{false};
    uint8_t volume{0};
};

}  // namespace sendspin
