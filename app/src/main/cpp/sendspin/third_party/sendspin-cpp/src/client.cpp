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

#include "sendspin/client.h"

#include "connection.h"
#include "connection_manager.h"
#include "crypto/keys.h"
#include "crypto/pairing_code.h"
#include "crypto/pairing_token.h"
#include "inbound_ring.h"
#include "inbox.h"
#include "pairing_offers.h"
#include "platform/base64.h"
#include "platform/crypto.h"
#include "platform/json_arena.h"
#include "platform/logging.h"
#include "platform/memory.h"
#include "platform/network_info.h"
#include "record_store.h"
#include "teardown_tracker.h"
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
#include "sendspin/player_role.h"
#endif
#include "protocol_messages.h"
#include "protocol_task.h"
#ifdef SENDSPIN_ENABLE_SOURCE
#include "source_role_impl.h"
#endif
#include "time_filter.h"
#ifdef SENDSPIN_ENABLE_VISUALIZER
#include "visualizer_role_impl.h"
#endif

#include <algorithm>
#include <optional>
#include <utility>

static const char* const TAG = "sendspin.client";

namespace sendspin {

namespace {

/// @brief Discriminates PairingNote entries. The drain switches on it exhaustively with no
/// default, so -Werror forces a new enumerator to be given a case.
enum class PairingNoteType : uint8_t {
    PAIRING_STARTED,
    PAIRING_SUCCEEDED,
    TRUST_CHANGED,
    PAIRING_FAILED,
    DISPLAY_PAIRING_CODE,
    CLEAR_PAIRING_CODE,
    OPEN_PAIRING_WINDOW,
    CLOSE_PAIRING_WINDOW,
};

/// @brief The pairing-UI prompt a PairingNote shows or dismisses
enum class PairingPrompt : uint8_t {
    NONE,
    CODE,
    WINDOW,
};

/// @brief One deferred pairing/trust listener notification, queued by the note_*() methods on
/// the protocol task and dispatched from the main loop's drain
struct PairingNote {
    PairingNoteType type{};
    /// server_id for PAIRING_STARTED/SUCCEEDED/FAILED; the emitted code for
    /// DISPLAY_PAIRING_CODE; empty otherwise.
    std::string text{};
    SendspinPairAbortReason reason{};  ///< Valid only for PAIRING_FAILED.
    ConnectionTrust trust{};           ///< Valid only for TRUST_CHANGED.
    /// Emission format of `text`; valid only for DISPLAY_PAIRING_CODE.
    SendspinPairingCodeFormat format{};
};

/// @brief Returns the prompt `type` shows or dismisses, or PairingPrompt::NONE
constexpr PairingPrompt prompt_of(PairingNoteType type) {
    if (type == PairingNoteType::DISPLAY_PAIRING_CODE ||
        type == PairingNoteType::CLEAR_PAIRING_CODE) {
        return PairingPrompt::CODE;
    }
    if (type == PairingNoteType::OPEN_PAIRING_WINDOW ||
        type == PairingNoteType::CLOSE_PAIRING_WINDOW) {
        return PairingPrompt::WINDOW;
    }
    return PairingPrompt::NONE;
}

/// @brief True for note types the drain skips when they repeat the last note it delivered for
/// their prompt in the batch
constexpr bool is_coalesced_note(PairingNoteType type) {
    return type == PairingNoteType::CLEAR_PAIRING_CODE ||
           type == PairingNoteType::OPEN_PAIRING_WINDOW ||
           type == PairingNoteType::CLOSE_PAIRING_WINDOW;
}

/// @brief Drops every pending note except a dismissal whose prompt reached the listener in an
/// earlier drain: a CLEAR_PAIRING_CODE with no DISPLAY_PAIRING_CODE pending ahead of it, a
/// CLOSE_PAIRING_WINDOW with no OPEN_PAIRING_WINDOW pending ahead of it. A prompt still pending is
/// never shown, so the dismissals behind it go with it. Runs under the Inbox mutex: a pure data
/// operation.
void retain_delivered_dismissals(std::vector<PairingNote>& notes) {
    bool code_pending = false;
    bool window_pending = false;
    size_t kept = 0;
    for (const PairingNote& note : notes) {
        const PairingNoteType type = note.type;
        code_pending |= type == PairingNoteType::DISPLAY_PAIRING_CODE;
        window_pending |= type == PairingNoteType::OPEN_PAIRING_WINDOW;
        if ((type == PairingNoteType::CLEAR_PAIRING_CODE && !code_pending) ||
            (type == PairingNoteType::CLOSE_PAIRING_WINDOW && !window_pending)) {
            // Payload-free, so rebuilt rather than moved (possibly onto itself).
            notes[kept++] = PairingNote{.type = type};
        }
    }
    notes.resize(kept);
}

/// @brief The provider writes the main loop owes, accumulated by request_persist() and
/// note_last_played_server() and performed by flush_pending_persistence()
struct PersistRequest {
    /// The last-played server_id to write, when it changed since the last flush.
    std::optional<std::string> last_played{};
};

/// @brief Folds a later persist request into the one the drain has not taken yet: a newer
/// last-played server replaces an older one.
void merge_persist_request(PersistRequest& current, PersistRequest&& delta) {
    if (delta.last_played.has_value()) {
        current.last_played = std::move(delta.last_played);
    }
}

/// @brief High-performance edges the protocol task queued for the main loop, which calls the
/// listener. Two counts rather than a net value, so an acquire and a release queued inside one
/// stalled main-loop tick still reach the listener as a request followed by its release. 32-bit:
/// each acquire waits for its grant before its burst sends, so the counts grow by at most a few
/// per drain.
struct HighPerformanceRequests {
    uint32_t acquires{0};
    uint32_t releases{0};
};

/// @brief Resolve the `locations` hint for a pair-method descriptor in client/hello.
/// @return The hint to advertise, or nullopt to omit the field.
///
/// The hint is informational (pairing.md "client/hello pair-method descriptor"): only the
/// application knows where its secret was published, so an unset value means the client has
/// nothing to say rather than a default worth sending.
std::optional<std::vector<std::string>> locations_hint(const std::vector<std::string>& configured) {
    if (configured.empty()) {
        return std::nullopt;
    }
    return configured;
}

/// @brief Whether `role` is in `mask`, logging the teardown once per role rather than once per
/// call site.
[[maybe_unused]] bool role_removed(uint16_t mask, SendspinRole role) {
    if ((mask & role_mask_bit(role)) == 0) {
        return false;
    }
    SS_LOGI(TAG, "Role %s left its connection: stopping the role", to_cstr(role));
    return true;
}

}  // namespace

/// @brief The main-loop-bound channels the client itself owns, beside the roles' own slots
struct SendspinClient::EventState {
    Inbox inbox;
    InboxSlot<GroupUpdateObject> group_slot{inbox, INBOX_TOPIC_GROUP};
    /// The filter error of the primary connection's latest completed time burst, for
    /// on_time_sync_updated(). Written by the protocol task, latest-wins.
    InboxSlot<double> time_sync_slot{inbox, INBOX_TOPIC_TIME};
    /// Owed provider writes, merged from any thread (request_persist(),
    /// note_last_played_server()) and drained into flush_pending_persistence(). Its own slot
    /// rather than an event: cleanup_connection_state() wipes the event ring, and an owed write
    /// must survive the connection dying before the next drain.
    InboxSlot<PersistRequest> persist_slot{inbox, INBOX_TOPIC_PERSIST};
    /// High-performance edges from the protocol task (time bursts), applied by the drain, which
    /// grants each acquire (SendspinClient::high_performance_granted_).
    InboxSlot<HighPerformanceRequests> high_performance_slot{inbox, INBOX_TOPIC_HIGH_PERFORMANCE};
    /// Pairing/trust listener notifications, appended on the protocol task in the order they
    /// happen and dispatched by the drain.
    InboxSlot<std::vector<PairingNote>> pairing_slot{inbox, INBOX_TOPIC_PAIRING};
    /// Bumped by stop() before its teardown so a drain frame already on the stack (a listener
    /// callback that called stop()) abandons what it has not delivered yet: the events it copied
    /// out, the rest of the pairing notes, the role drains and the group update. Main loop only.
    uint32_t drain_generation{0};

    /// @brief Appends `note` to the pairing slot. Every SendspinClient::note_*() method funnels
    /// through this so the merge shape lives once. PairingNote is anonymous-namespace-private
    /// to this file, so this lives on EventState (itself private to SendspinClient) rather than as
    /// a SendspinClient member declared in the public header.
    /// @param note The note to append (moved).
    void push_pairing_note(PairingNote&& note) {
        std::vector<PairingNote> delta;
        delta.push_back(std::move(note));
        this->pairing_slot.merge(
            [](std::vector<PairingNote>& current, std::vector<PairingNote>&& added) {
                for (auto& entry : added) {
                    current.push_back(std::move(entry));
                }
            },
            std::move(delta));
    }
};

/// @brief Client-level state the protocol task owns
struct SendspinClient::TaskState {
    /// The newest client/state snapshot the main loop published (publish_state()); each admitted
    /// connection is sent its filtered copy (publish_client_state()). Protocol task only, and
    /// reset by stop() once the task is joined.
    std::optional<ClientStateMessage> client_state;
    /// High-performance acquires queued so far (request_high_performance()); the ticket of the
    /// latest one. Protocol task only.
    uint32_t high_performance_requests{0};
};

// ============================================================================
// Constructor / Destructor
// ============================================================================

SendspinClient::SendspinClient(SendspinClientConfig config)
    : config_(std::move(config)),
      connection_manager_(std::make_unique<ConnectionManager>(this)),
      event_state_(std::make_unique<EventState>()),
      json_arena_(std::make_unique<SendspinArenaAllocator>(this->config_.json_arena_size)),
      protocol_task_(std::make_unique<ProtocolTask>(this->config_.server_max_connections)),
      task_state_(std::make_unique<TaskState>()) {}

SendspinClient::~SendspinClient() {
    // Transport-only teardown: goodbye and close every peer in the same order as stop(), but
    // dispatch no teardown or clear callback (nothing reaches the inbox and no drain runs). A
    // role-thread callback can still run until stop_role_threads() below joins its role, so
    // listeners must outlive the client.
    if (this->lifecycle_.load(std::memory_order_relaxed) != LifecycleState::STOPPED) {
        this->close_transports();
        // The role threads next, as stop() does: each returns the items it holds and unbinds its
        // list from the ring, so the ring's reset below never reaches a list the role resets
        // destroy (a LOCAL item its holder returned but the protocol task never took is counted
        // through that list).
        this->stop_role_threads();
        this->release_inbound_ring();
        // Every high-performance hold ends with the client. Requests the protocol task queued are
        // dropped, and the holds the main loop applied are released.
        this->event_state_->high_performance_slot.reset();
        while (this->high_performance_ref_count_ > 0) {
            this->release_high_performance();
        }
    }

    // Every thread is joined and the inbound ring released (above, by stop(), or never started).
    // Every role is reset explicitly (not just the threaded ones):
    // role InboxSlots release their topic-bit claims against event_state_'s Inbox on destruction,
    // so all roles must be gone before the alphabetized member order destroys event_state_.
#ifdef SENDSPIN_ENABLE_PLAYER
    this->player_.reset();
#endif
#ifdef SENDSPIN_ENABLE_SOURCE
    this->source_.reset();
#endif
#ifdef SENDSPIN_ENABLE_VISUALIZER
    this->visualizer_.reset();
#endif
#ifdef SENDSPIN_ENABLE_ARTWORK
    this->artwork_.reset();
#endif
#ifdef SENDSPIN_ENABLE_CONTROLLER
    this->controller_.reset();
#endif
#ifdef SENDSPIN_ENABLE_METADATA
    this->metadata_.reset();
#endif
#ifdef SENDSPIN_ENABLE_COLOR
    this->color_.reset();
#endif
    this->connection_manager_.reset();
    // No new write can be requested once the connection manager is gone; perform one still owed
    // (a pair-finalize that landed after the last loop() tick) before the store goes away.
    this->flush_pending_persistence();
    // Destroyed after the connection manager: every connection holds raw pointers into
    // identity_/record_store_ (see SendspinConnection::init_noise_handshake), so both must
    // outlive every connection the manager could still be tearing down.
    this->record_store_.reset();
    this->identity_.reset();
}

void SendspinClient::set_log_level(LogLevel level) {
    platform_set_log_level(static_cast<int>(level));
}

LogLevel SendspinClient::get_log_level() {
    return static_cast<LogLevel>(platform_get_log_level());
}

// ============================================================================
// Lifecycle
// ============================================================================

bool SendspinClient::start() {
    switch (this->lifecycle_.load(std::memory_order_relaxed)) {
        case LifecycleState::RUNNING:
            return true;
        case LifecycleState::STOPPING:
            SS_LOGW(TAG, "start() ignored: called from a callback while stop() is in progress");
            return false;
        case LifecycleState::STOPPED:
            break;
    }

    // Fail closed, like a failed identity: a Pairing PSK any peer could hold admits every server
    // as a pairing peer, and a malformed static pairing code (pairing.md "Static Pairing Code
    // Flow") fails every attempt with a code mismatch.
    if (this->config_.pairing_psk.has_value() &&
        !RecordStore::is_usable_pairing_psk(this->config_.pairing_psk->bytes)) {
        SS_LOGE(TAG, "Configured Pairing PSK is all zero or the published Sentinel PSK; cannot "
                     "start");
        return false;
    }
    if (this->config_.static_pairing_code.has_value() &&
        !is_valid_static_pairing_code(this->config_.static_pairing_code.value())) {
        SS_LOGE(TAG, "Configured static pairing code is not %d decimal digits; cannot start",
                STATIC_PAIRING_CODE_DIGITS);
        return false;
    }

    // lifecycle_ stays STOPPED until every step below has succeeded. Marking the client running
    // up front would report it started even when this function returns false (e.g. identity
    // generation failed and identity_ is left null), which is precisely the state connect_to()
    // must refuse to build a connection in.
    //
    // Create the record store (needs the persistence provider, so done here rather than at
    // construction) and load or generate the static X25519 identity. Both must exist before the
    // connection manager can hand them out to any connection (connection_manager_->start() below
    // arms the ws_server, and connect_to() may be called any time after start() RETURNS TRUE).
    // A restart keeps both: the store already holds the records the last run persisted, and the
    // identity is fixed for the lifetime of the stored keypair. They are rebuilt only when the
    // persistence provider changed since they were built (each reads the provider once, at
    // construction, so a provider set between a stop and the next start would otherwise never
    // be consulted) or when the previous start failed part-way (a store without an identity).
    if (this->record_store_ == nullptr || this->identity_ == nullptr ||
        this->identity_provider_ != this->persistence_provider_) {
        this->identity_.reset();
        this->record_store_ =
            std::make_unique<RecordStore>(this->persistence_provider_, this->config_);
        if (!this->load_or_generate_identity()) {
            // identity_ is left null: there is no safe key to fall back to (see
            // load_or_generate_identity()'s doc comment), so the client must not start.
            return false;
        }
        this->identity_provider_ = this->persistence_provider_;
    }

    // A store that came up owing a write (a duplicate slot cleared at load) flushes on the first
    // tick, through the same deferred path a pairing uses: the provider is main-loop-only and the
    // store never calls it itself outside that flush.
    if (this->record_store_->has_pending_writes()) {
        this->request_persist();
    }

    // Load persisted state
    this->load_last_played_server();

    // The inbound ring every admitted connection receives into and the role threads consume
    // from; the role starts below bind their item lists to it.
    if (!this->create_inbound_ring()) {
        return false;
    }

    // Start the role threads. A failure part-way stops the roles that did start, so the client
    // is back in the stopped state and a corrected retry begins clean.
    bool roles_started = true;
#ifdef SENDSPIN_ENABLE_PLAYER
    if (roles_started && this->player_) {
        roles_started =
            this->player_->impl_->start(this->persistence_provider_, this->inbound_ring_.get());
    }
#endif
#ifdef SENDSPIN_ENABLE_VISUALIZER
    if (roles_started && this->visualizer_) {
        roles_started = this->visualizer_->impl_->start(this->inbound_ring_.get());
    }
#endif
#ifdef SENDSPIN_ENABLE_ARTWORK
    if (roles_started && this->artwork_) {
        roles_started = this->artwork_->impl_->start(this->inbound_ring_.get());
    }
#endif
#ifdef SENDSPIN_ENABLE_SOURCE
    if (roles_started && this->source_) {
        roles_started = this->source_->impl_->start(this->protocol_task_.get());
    }
#endif
    if (!roles_started) {
        this->stop_role_threads();
        this->release_inbound_ring();
        return false;
    }

    // A command queued or a request posted after the last stop() finished (a call racing it on
    // another thread) belongs to the run that ended; this run begins with an empty queue and no
    // request waiting. Admission reopens in connection_manager_->start() below.
    this->protocol_task_->drop_commands();

    // The first client/state snapshot, taken by the protocol task's first tick: every
    // connection's client/state is built from the newest snapshot the task holds.
    this->protocol_task_->publish_state(this->build_client_state());

    // Open admission and create the WebSocket server, started here when the network is already
    // up. Before the protocol task starts: everything the manager writes here is the task's from
    // then on, and an accept the server delivers meanwhile waits in the command queue for the
    // task's first tick.
    this->connection_manager_->start();

    // The protocol task. Undersized stacks are clamped to the documented minimum, the measured
    // stack of its deepest call chain, as the transport tasks' are.
    size_t protocol_stack = this->config_.protocol_task_stack_size;
    if (protocol_stack < SendspinClientConfig::DEFAULT_PROTOCOL_TASK_STACK_SIZE) {
        SS_LOGW(TAG, "protocol_task_stack_size %u below minimum %u; clamping",
                static_cast<unsigned>(protocol_stack),
                static_cast<unsigned>(SendspinClientConfig::DEFAULT_PROTOCOL_TASK_STACK_SIZE));
        protocol_stack = SendspinClientConfig::DEFAULT_PROTOCOL_TASK_STACK_SIZE;
    }
    if (!this->protocol_task_->start([this]() { return this->protocol_tick(); }, protocol_stack,
                                     this->config_.protocol_task_priority,
                                     this->config_.protocol_task_psram_stack)) {
        SS_LOGE(TAG, "Failed to start the protocol task");
        // No task ever ran: close admission, stop the server (joining its transport threads),
        // and only then drop the accepts it queued, whose connections no transport reaches any
        // more.
        this->connection_manager_->close_admission();
        (void)this->connection_manager_->finish_stop();
        this->protocol_task_->drop_commands();
        this->stop_role_threads();
        this->release_inbound_ring();
        return false;
    }

    this->lifecycle_.store(LifecycleState::RUNNING, std::memory_order_release);
    return true;
}

void SendspinClient::stop() {
    if (this->lifecycle_.load(std::memory_order_relaxed) != LifecycleState::RUNNING) {
        return;
    }
    // From here the client reads as stopped: is_started() is false, loop() is a no-op, and
    // start()/stop() and every request that queues a command or posts a request are refused, so
    // a listener callback fired below cannot recurse into the teardown, restart the server, or
    // reach a connection.
    this->lifecycle_.store(LifecycleState::STOPPING, std::memory_order_release);

    // 1-4. Signal the drain roles, close admission, join the protocol task (its final tick
    //      goodbyes every peer within a bound), then close every transport and stop the server
    //      (see close_transports()). Nothing reaches a role or the inbox from a transport or the
    //      protocol task after this.
    const PairingUiSnapshot pairing_ui = this->close_transports();

    // 5. Role threads. Each role returns the inbound ring items it held after its own join, and
    //    with every producer and consumer gone the ring is emptied and released.
    this->stop_role_threads();
    this->release_inbound_ring();

    // 6. Reset per-connection and role state exactly as a lost connection does, here on the main
    //    loop since every other thread is joined. With every producer gone, the state this
    //    leaves behind is the state a restart begins from. The drain generation bump abandons
    //    the events a drain frame further up the stack (a listener that called stop()) copied
    //    out before this teardown. The group slot is reset too, so the drain below cannot
    //    repopulate group_state_ from a delta that arrived before the stop.
    ++this->event_state_->drain_generation;
    this->cleanup_connection_state(ALL_ROLES_MASK);
    this->task_state_->client_state.reset();
    this->group_state_ = GroupUpdateObject{};

    // A pairing attempt cut short by the stop leaves its code or pairing-window prompt showing.
    // Queue the dismissals now, after cleanup_connection_state() dropped the pending notes, so the
    // drain below delivers them (same ordering rule as the ConnectionManager drop paths). One an
    // earlier drop left pending survived that cleanup; the two coalesce into one callback.
    this->note_pairing_ui_dismissals(pairing_ui);

    // 7. Deliver what the teardown owes, now rather than on a loop() tick that is not coming:
    //    each role's main-loop half (its clear callback, the player's on_stream_end()), the
    //    high-performance releases the shutdown pass queued, the owed provider writes and the
    //    dismissals above. Every getter already reports the stopped state, so a callback that reads
    //    the client sees exactly what a caller sees once stop() returns.
    this->drain_inbox();

    this->lifecycle_.store(LifecycleState::STOPPED, std::memory_order_release);
}

PairingUiSnapshot SendspinClient::close_transports() {
    // 1. Ask the artwork and visualizer threads to exit now, so a slow on_image_decode() or a
    //    parked drain overlaps the transport teardown instead of following it. Their items stay on
    //    their lists until their joins; the player keeps running, returning the items it plays,
    //    so a transport waiting for ring space is never parked behind a stopped consumer.
    this->signal_drain_role_stops();

    // 2. Close admission before the join (close_admission()).
    this->connection_manager_->close_admission();

    // 3. The protocol task: its final tick acts on the commands queued and the requests posted
    //    so far (refusing every accept with a shutdown goodbye), runs the shutdown pass (detach
    //    every connection, goodbye and close each with reason shutdown), then the join.
    this->protocol_task_->stop();

    // 4. Close every released outbound connection still parked for reaping, and stop the
    //    server, joining every transport thread, then release those connections and the ones the
    //    shutdown pass kept here.
    const PairingUiSnapshot pairing_ui = this->connection_manager_->finish_stop();

    // Commands and requests a consumer pushed meanwhile are dropped here, outside any run.
    this->protocol_task_->drop_commands();
    return pairing_ui;
}

void SendspinClient::signal_drain_role_stops() {
#ifdef SENDSPIN_ENABLE_VISUALIZER
    if (this->visualizer_) {
        this->visualizer_->impl_->signal_stop();
    }
#endif
#ifdef SENDSPIN_ENABLE_ARTWORK
    if (this->artwork_) {
        this->artwork_->impl_->signal_stop();
    }
#endif
}

void SendspinClient::stop_role_threads() {
#ifdef SENDSPIN_ENABLE_PLAYER
    if (this->player_) {
        this->player_->impl_->stop();
    }
#endif
#ifdef SENDSPIN_ENABLE_VISUALIZER
    if (this->visualizer_) {
        this->visualizer_->impl_->stop();
    }
#endif
#ifdef SENDSPIN_ENABLE_ARTWORK
    if (this->artwork_) {
        this->artwork_->impl_->stop();
    }
#endif
#ifdef SENDSPIN_ENABLE_SOURCE
    if (this->source_) {
        this->source_->impl_->stop();
    }
#endif
}

bool SendspinClient::create_inbound_ring() {
    // Created whatever roles are enabled: every admitted connection receives into it, including
    // the server/time replies whose receive stamps must be taken at the socket read, which the
    // one-at-a-time fallback hand-off would delay behind the protocol task. The enabled roles set
    // its size, down to two JSON messages without the player, the visualizer or artwork.
    InboundRingBudget budget;
    budget.time_burst_size = this->config_.time_burst_size;
    budget.time_burst_interval_ms = this->config_.time_burst_interval_ms;
    size_t player_message_bytes = 0;
    size_t visualizer_message_bytes = 0;
    bool artwork = false;
#ifdef SENDSPIN_ENABLE_PLAYER
    if (this->player_) {
        budget.audio_hold_bytes = this->player_->impl_->config.audio_buffer_capacity;
        // The uncapped share: the cap advertised_buffer_capacity() applies is the ring's own
        // largest item, which this derivation sets.
        player_message_bytes =
            inbound_held_message_bytes(this->player_->impl_->buffer_capacity_share());
    }
#endif
#ifdef SENDSPIN_ENABLE_VISUALIZER
    if (this->visualizer_) {
        budget.visualizer_hold_bytes = this->visualizer_->impl_->visualizer_support.buffer_capacity;
        budget.visualizer_stored_bytes_per_second =
            this->visualizer_->impl_->stored_frame_bytes_per_second();
        visualizer_message_bytes =
            inbound_held_message_bytes(this->visualizer_->impl_->advertised_buffer_capacity());
    }
#endif
#ifdef SENDSPIN_ENABLE_ARTWORK
    if (this->artwork_) {
        for (const auto& slot : this->artwork_->impl_->config.preferred_formats) {
            budget.artwork_images_stored_bytes +=
                inbound_artwork_image_stored_bytes(slot.max_image_bytes);
        }
        budget.artwork_hold_bytes =
            INBOUND_ARTWORK_IN_FLIGHT_IMAGES * budget.artwork_images_stored_bytes;
        artwork = true;
    }
#endif
    budget.largest_message_bytes =
        inbound_largest_message_bytes(player_message_bytes, visualizer_message_bytes, artwork);
    const size_t storage_bytes = derive_inbound_ring_bytes(budget);
    auto ring = std::make_unique<InboundRing>();
    if (!ring->create(storage_bytes, this->config_.inbound_ring_location,
                      budget.largest_message_bytes)) {
        return false;
    }
    ring->quota(InboundHolder::PLAYER).set_limit(budget.audio_hold_bytes);
    ring->quota(InboundHolder::VISUALIZER).set_limit(budget.visualizer_hold_bytes);
    ring->quota(InboundHolder::ARTWORK).set_limit(budget.artwork_hold_bytes);
    SS_LOGD(TAG, "Inbound ring: %zu bytes, messages up to %zu bytes", storage_bytes,
            ring->max_message_bytes());
    this->inbound_ring_ = std::move(ring);
    return true;
}

void SendspinClient::release_inbound_ring() {
    if (this->inbound_ring_ == nullptr) {
        return;
    }
    this->inbound_ring_->reset();
    this->inbound_ring_.reset();
}

void SendspinClient::connect_to(const std::string& url) {
    // Running implies start() succeeded, so identity_ and record_store_ exist for the protocol
    // task to hand to init_noise_handshake() once the WebSocket upgrade completes; a connection
    // built before that would dereference null there.
    if (!this->is_started()) {
        SS_LOGW(TAG, "connect_to() ignored: client is not running");
        return;
    }
    this->protocol_task_->post_requests({.connect_to = url});
}

void SendspinClient::disconnect(SendspinGoodbyeReason reason) {
    // A stopped client has nothing to disconnect, and inside stop() the shutdown pass is already
    // goodbying every peer.
    if (!this->is_started()) {
        SS_LOGD(TAG, "disconnect() ignored: client is not running");
        return;
    }
    this->protocol_task_->post_requests({.disconnect = reason});
}

void SendspinClient::loop() {
    // A stopped client is quiescent: no connections, no threads, nothing to deliver.
    if (!this->is_started()) {
        return;
    }
    this->drain_inbox();
}

void SendspinClient::request_persist() {
    this->event_state_->persist_slot.merge(merge_persist_request, PersistRequest{});
}

void SendspinClient::flush_pending_persistence() {
    // Lock-free when nothing is owed.
    if ((this->event_state_->inbox.poll() & INBOX_TOPIC_PERSIST) == 0) {
        return;
    }
    PersistRequest owed;
    if (!this->event_state_->persist_slot.take(owed)) {
        return;
    }
    // persist_records() logs the durability warning itself on a rejected write, so the return
    // value is ignored.
    if (this->record_store_ != nullptr) {
        this->record_store_->persist_records();
    }
    if (owed.last_played.has_value()) {
        this->write_last_played_server(owed.last_played.value());
    }
}

uint32_t SendspinClient::request_high_performance(bool acquire) {
    HighPerformanceRequests delta;
    uint32_t ticket = 0;
    if (acquire) {
        delta.acquires = 1;
        ticket = ++this->task_state_->high_performance_requests;
    } else {
        delta.releases = 1;
    }
    this->event_state_->high_performance_slot.merge(
        [](HighPerformanceRequests& current, HighPerformanceRequests&& added) {
            current.acquires += added.acquires;
            current.releases += added.releases;
        },
        delta);
    return ticket;
}

bool SendspinClient::high_performance_granted(uint32_t ticket) const {
    // Tickets and grants count the same acquires in the same order, so the grant for `ticket`
    // is visible once the granted count has reached it (compared across the wrap).
    return !count_after(ticket, this->high_performance_granted_.load(std::memory_order_acquire));
}

void SendspinClient::apply_high_performance_requests() {
    HighPerformanceRequests requests;
    if (!this->event_state_->high_performance_slot.take(requests)) {
        return;
    }
    // Acquires first: a burst whose hold was requested and then released inside one stalled
    // tick (its connection dropped before the grant) must reach the listener as a request
    // followed by its release, never a release of nothing.
    for (uint32_t i = 0; i < requests.acquires; ++i) {
        this->acquire_high_performance();
    }
    if (requests.acquires != 0) {
        // The grant: the listener has been called for every acquire taken, so the bursts that
        // wait for these tickets may send. Released after the callbacks, and the task woken,
        // since a waiting burst has no deadline of its own.
        this->high_performance_granted_.fetch_add(requests.acquires, std::memory_order_release);
        this->protocol_task_->wake();
    }
    for (uint32_t i = 0; i < requests.releases; ++i) {
        this->release_high_performance();
    }
}

void SendspinClient::post_time_sync_error(double error) {
    this->event_state_->time_sync_slot.write(error);
}

void SendspinClient::drain_inbox() {
    // Process deferred events: all state mutations and user callbacks happen here, on the main
    // loop thread, to avoid cross-thread data races. Two poll() snapshots gate the work below:
    // inbox_bits (here) gates the high-performance requests, the time-sync report and the
    // event-ring drain immediately following it; slot_bits (taken after that drain completes,
    // below) gates the pairing notes, the role drains and the group-update drain, since a slot can
    // be written by a producer between this snapshot and that one. The provider writes poll for
    // themselves right before the pairing notes.
    auto& es = *this->event_state_;
    // A listener callback below may call stop(), which tears every role down and runs a drain of
    // its own; everything this frame has not delivered by then is abandoned (see
    // EventState::drain_generation).
    const uint32_t drain_generation = es.drain_generation;

    // Catches each role up on a teardown: with `cleared` null, to the role's current generation;
    // otherwise only the role a *_CLEARED event names, to the generation it carries.
    // [[maybe_unused]]: with every role compiled out the body is empty.
    const auto catch_up_roles = [&]([[maybe_unused]] const InboxEvent* cleared) {
        [[maybe_unused]] const auto catch_up = [cleared](auto& impl, InboxEventType type) {
            if (cleared == nullptr) {
                catch_up_teardown(impl, impl.cleanup_generation.load(std::memory_order_acquire));
            } else if (cleared->type == type) {
                catch_up_teardown(impl, cleared->epoch);
            }
        };
#ifdef SENDSPIN_ENABLE_PLAYER
        if (this->player_) {
            catch_up(*this->player_->impl_, InboxEventType::PLAYER_CLEARED);
        }
#endif
#ifdef SENDSPIN_ENABLE_CONTROLLER
        if (this->controller_) {
            catch_up(*this->controller_->impl_, InboxEventType::CONTROLLER_CLEARED);
        }
#endif
#ifdef SENDSPIN_ENABLE_METADATA
        if (this->metadata_) {
            catch_up(*this->metadata_->impl_, InboxEventType::METADATA_CLEARED);
        }
#endif
#ifdef SENDSPIN_ENABLE_COLOR
        if (this->color_) {
            catch_up(*this->color_->impl_, InboxEventType::COLOR_CLEARED);
        }
#endif
#ifdef SENDSPIN_ENABLE_ARTWORK
        if (this->artwork_) {
            catch_up(*this->artwork_->impl_, InboxEventType::ARTWORK_CLEARED);
        }
#endif
#ifdef SENDSPIN_ENABLE_VISUALIZER
        if (this->visualizer_) {
            catch_up(*this->visualizer_->impl_, InboxEventType::VISUALIZER_CLEARED);
        }
#endif
#ifdef SENDSPIN_ENABLE_SOURCE
        if (this->source_) {
            catch_up(*this->source_->impl_, InboxEventType::SOURCE_CLEARED);
        }
#endif
    };

    // Each role catches up on the teardowns the protocol task ran since the last drain: one
    // acquire load per role. Event dispatch and the role drains catch up on their own, at the
    // stamp they act on; this pass covers a teardown whose stamped *_CLEARED event the full event
    // ring dropped, which would otherwise leave the role's clear undelivered and the role holding
    // the torn-down connection's state (and the player its playback hold). It can only run a
    // teardown half earlier, never apply anything: a slot payload is still applied only when its
    // stamp is the current generation.
    catch_up_roles(nullptr);

    const uint32_t inbox_bits = es.inbox.poll();

    // --- High-performance requests (time bursts) ---
    if (inbox_bits & INBOX_TOPIC_HIGH_PERFORMANCE) {
        this->apply_high_performance_requests();
    }

    // --- Time sync report ---
    if (inbox_bits & INBOX_TOPIC_TIME) {
        double error = 0.0;
        if (es.time_sync_slot.take(error) && this->listener_ != nullptr) {
            this->listener_->on_time_sync_updated(static_cast<float>(error));
        }
    }

    // --- Lifecycle events ---
    // Skipped once a callback above called stop(): its own drain delivered the ring, and a take
    // here would pull off what a start() from the same callback queued for the next drain.
    if ((inbox_bits & INBOX_TOPIC_EVENTS) && es.drain_generation == drain_generation) {
        // Drain in small batches to bound the stack cost on the shared main-loop task. A batch that
        // comes back partial means the ring is empty, ending the loop; events pushed mid-drain are
        // still delivered this tick as long as full batches keep arriving. Sized as a fraction of
        // the ring so the batch/ring ratio (and the stack cost above) tracks EVENT_CAPACITY
        // automatically.
        constexpr size_t EVENT_DRAIN_BATCH_SIZE = Inbox::EVENT_CAPACITY / 4;
        InboxEvent events[EVENT_DRAIN_BATCH_SIZE];
        size_t event_count = 0;
        // A dispatched event below can run a listener callback that calls stop(), whose teardown
        // bumps the drain generation and wipes the ring. Events already copied into the local
        // batch are stale at that point and must be dropped, exactly as the ring reset intended.
        bool drain_aborted = false;
        do {
            event_count = es.inbox.take_events(events, EVENT_DRAIN_BATCH_SIZE);
            for (size_t i = 0; i < event_count; ++i) {
                if (es.drain_generation != drain_generation) {
                    drain_aborted = true;
                    break;
                }
                const InboxEvent& event = events[i];
                // Every event is stamped with its role's teardown generation. The role catches up
                // on that teardown before the event is acted on (catch_up_teardown()), so the
                // reset, and for a state role its clear callback, lands ahead of what the event
                // starts and of any state the next connection sends.
                switch (event.type) {
                    // Stream lifecycle events from the player role, appended to
                    // awaiting_sync_idle_events (the sync-idle gate itself is untouched).
                    case InboxEventType::PLAYER_STREAM: {
#ifdef SENDSPIN_ENABLE_PLAYER
                        if (this->player_ &&
                            event_is_current(event.epoch,
                                             this->player_->impl_->cleanup_generation.load(
                                                 std::memory_order_acquire),
                                             TAG, "a player stream event")) {
                            catch_up_teardown(*this->player_->impl_, event.epoch);
                            this->player_->impl_->on_stream_ring_event(event);
                        }
#endif
                        break;
                    }
                    // *_CLEARED: pushed by each role's cleanup(). The clear callback is the role's
                    // main-loop teardown half, so the event only catches the role up; a stale one
                    // (a later teardown, or a drain that took the next connection's state, already
                    // caught up past it) is a no-op.
                    case InboxEventType::PLAYER_CLEARED:
                    case InboxEventType::CONTROLLER_CLEARED:
                    case InboxEventType::METADATA_CLEARED:
                    case InboxEventType::COLOR_CLEARED:
                    case InboxEventType::ARTWORK_CLEARED:
                    case InboxEventType::VISUALIZER_CLEARED:
                    case InboxEventType::SOURCE_CLEARED:
                        catch_up_roles(&event);
                        break;
                    // ARTWORK_STREAM / VISUALIZER_STREAM: code is the role-local
                    // ArtworkEventType/VisualizerEventType.
                    case InboxEventType::ARTWORK_STREAM: {
#ifdef SENDSPIN_ENABLE_ARTWORK
                        if (this->artwork_ &&
                            event_is_current(event.epoch,
                                             this->artwork_->impl_->cleanup_generation.load(
                                                 std::memory_order_acquire),
                                             TAG, "an artwork stream event")) {
                            catch_up_teardown(*this->artwork_->impl_, event.epoch);
                            this->artwork_->impl_->handle_stream_ring_event(
                                static_cast<ArtworkEventType>(event.code));
                        }
#endif
                        break;
                    }
                    case InboxEventType::VISUALIZER_STREAM: {
#ifdef SENDSPIN_ENABLE_VISUALIZER
                        if (this->visualizer_ &&
                            event_is_current(event.epoch,
                                             this->visualizer_->impl_->cleanup_generation.load(
                                                 std::memory_order_acquire),
                                             TAG, "a visualizer stream event")) {
                            catch_up_teardown(*this->visualizer_->impl_, event.epoch);
                            this->visualizer_->impl_->handle_stream_ring_event(
                                static_cast<VisualizerEventType>(event.code), event.epoch);
                        }
#endif
                        break;
                    }
                    // SOURCE_STREAM: code is the role-local SourceStreamEventType.
                    case InboxEventType::SOURCE_STREAM: {
#ifdef SENDSPIN_ENABLE_SOURCE
                        if (this->source_ &&
                            event_is_current(event.epoch,
                                             this->source_->impl_->cleanup_generation.load(
                                                 std::memory_order_acquire),
                                             TAG, "a source stream event")) {
                            catch_up_teardown(*this->source_->impl_, event.epoch);
                            this->source_->impl_->handle_stream_ring_event(
                                static_cast<SourceStreamEventType>(event.code));
                        }
#endif
                        break;
                    }
                    default: {
                        // Guards only against a corrupted enum value.
                        SS_LOGD(TAG, "Unhandled inbox event type: %d",
                                static_cast<int>(event.type));
                        break;
                    }
                }
            }
            // A stop() that re-entered on the final event of a full batch bumps the drain
            // generation but leaves drain_aborted false: the check at the top of the inner loop
            // never runs again because there is no next iteration. Re-check here so the loop stops
            // instead of calling take_events() again and destructively pulling the cleanup's
            // freshly re-pushed *_CLEARED events off the live ring (dropping them).
            if (es.drain_generation != drain_generation) {
                drain_aborted = true;
            }
        } while (!drain_aborted && event_count == EVENT_DRAIN_BATCH_SIZE);
    }

    // Second snapshot: catches topic bits a producer set while the ring drain above was running.
    // Gates the pairing notes, the role drains and the group drain below; see the inbox_bits
    // comment above for the staleness argument, which applies identically here.
    const uint32_t slot_bits = es.inbox.poll();

    // --- Pairing/trust notifications, and the deferred provider writes ---
    // After the event ring, so a role's clear can precede a pairing note queued before it. The
    // two never describe the same listener state (role state against the pairing UI and trust).
    // The one ordering that matters is a record's provider write ahead of on_pairing_succeeded:
    // the protocol task requests the write before it queues the PAIRING_SUCCEEDED note, so the
    // flush (which polls for itself) runs after the notes are taken and before they are
    // dispatched, and every note taken finds its write performed.
    std::vector<PairingNote> notes;
    const bool notes_taken = (slot_bits & INBOX_TOPIC_PAIRING) != 0 &&
                             es.drain_generation == drain_generation && es.pairing_slot.take(notes);
    this->flush_pending_persistence();
    if (notes_taken && es.drain_generation == drain_generation) {
        if (this->listener_ != nullptr) {
            // In queue order, skipping a coalesced type (is_coalesced_note()) that repeats its
            // prompt's last delivered note. The listener is set before start() and must outlive
            // the client (see set_listener), so it cannot become null mid-dispatch.
            std::optional<PairingNoteType> last_code_note;
            std::optional<PairingNoteType> last_window_note;
            for (const PairingNote& note : notes) {
                const PairingPrompt prompt = prompt_of(note.type);
                if (prompt != PairingPrompt::NONE) {
                    std::optional<PairingNoteType>& last_note =
                        prompt == PairingPrompt::CODE ? last_code_note : last_window_note;
                    if (is_coalesced_note(note.type) && last_note == note.type) {
                        continue;
                    }
                    last_note = note.type;
                }
                switch (note.type) {
                    case PairingNoteType::PAIRING_STARTED:
                        this->listener_->on_pairing_started(note.text);
                        break;
                    case PairingNoteType::PAIRING_SUCCEEDED:
                        this->listener_->on_pairing_succeeded(note.text);
                        break;
                    case PairingNoteType::TRUST_CHANGED:
                        this->listener_->on_trust_changed(note.trust);
                        break;
                    case PairingNoteType::PAIRING_FAILED:
                        this->listener_->on_pairing_failed(note.text, note.reason);
                        break;
                    case PairingNoteType::DISPLAY_PAIRING_CODE:
                        this->listener_->on_display_pairing_code(note.text, note.format);
                        break;
                    case PairingNoteType::CLEAR_PAIRING_CODE:
                        this->listener_->on_clear_pairing_code();
                        break;
                    case PairingNoteType::OPEN_PAIRING_WINDOW:
                        this->listener_->on_open_pairing_window();
                        break;
                    case PairingNoteType::CLOSE_PAIRING_WINDOW:
                        this->listener_->on_close_pairing_window();
                        break;
                }
                // A re-entrant stop() bumped the generation: abandon the rest of the batch.
                if (es.drain_generation != drain_generation) {
                    break;
                }
            }
        }
    }

    // --- Role events: bit-gated so an idle tick performs zero inbox mutex acquisitions here ---
    // Each role drain takes its slot first and catches the role up on its teardowns before
    // applying anything (see TeardownTracker), and abandons the rest of its own work when one of
    // its callbacks re-entered teardown (its generation moved on). Between role drains, and
    // before the group update, the drain generation decides whether this frame was overtaken by
    // a stop() a callback made.
    const auto overtaken = [&es, drain_generation] {
        return es.drain_generation != drain_generation;
    };
#ifdef SENDSPIN_ENABLE_PLAYER
    if (!overtaken() && this->player_ && this->player_->impl_->needs_drain(slot_bits)) {
        this->player_->impl_->drain_events();
    }
#endif
#ifdef SENDSPIN_ENABLE_CONTROLLER
    if (!overtaken() && this->controller_ && this->controller_->impl_->needs_drain(slot_bits)) {
        this->controller_->impl_->drain_events();
    }
#endif
#ifdef SENDSPIN_ENABLE_METADATA
    if (!overtaken() && this->metadata_ && this->metadata_->impl_->needs_drain(slot_bits)) {
        this->metadata_->impl_->drain_events();
    }
#endif
#ifdef SENDSPIN_ENABLE_COLOR
    if (!overtaken() && this->color_ && this->color_->impl_->needs_drain(slot_bits)) {
        this->color_->impl_->drain_events();
    }
#endif
#ifdef SENDSPIN_ENABLE_ARTWORK
    if (!overtaken() && this->artwork_ && this->artwork_->impl_->needs_drain(slot_bits)) {
        this->artwork_->impl_->drain_events();
    }
#endif

    // --- Group update events ---
    if (!overtaken() && (slot_bits & INBOX_TOPIC_GROUP) != 0) {
        GroupUpdateObject group_delta;
        if (es.group_slot.take(group_delta)) {
            apply_group_update_deltas(&this->group_state_, group_delta);

            if (this->listener_) {
                this->listener_->on_group_update(group_delta);
            }

            SS_LOGD(TAG, "Group update - state: %s, id: %s, name: %s",
                    this->group_state_.playback_state.has_value()
                        ? to_cstr(this->group_state_.playback_state.value())
                        : "unchanged",
                    this->group_state_.group_id.value_or("").c_str(),
                    this->group_state_.group_name.value_or("").c_str());
        }
    }
}

// ============================================================================
// Role registration (call before start())
// ============================================================================

#ifdef SENDSPIN_ENABLE_PLAYER
PlayerRole& SendspinClient::add_player(PlayerRoleConfig config) {
    if (this->lifecycle_.load(std::memory_order_relaxed) != LifecycleState::STOPPED) {
        SS_LOGW(TAG, "add_player() called while started; role may not initialize correctly");
    }
    this->player_ = std::make_unique<PlayerRole>(std::move(config), this);
    // start() refreshes it; set here so a delay the consumer sets before start() is saved.
    this->player_->impl_->persistence = this->persistence_provider_;
    this->player_->impl_->attach_inbox(this->event_state_->inbox);
    return *this->player_;
}
#endif

#ifdef SENDSPIN_ENABLE_CONTROLLER
ControllerRole& SendspinClient::add_controller() {
    if (this->lifecycle_.load(std::memory_order_relaxed) != LifecycleState::STOPPED) {
        SS_LOGW(TAG, "add_controller() called while started");
    }
    this->controller_ = std::make_unique<ControllerRole>(this);
    this->controller_->impl_->attach_inbox(this->event_state_->inbox);
    return *this->controller_;
}
#endif

#ifdef SENDSPIN_ENABLE_METADATA
MetadataRole& SendspinClient::add_metadata() {
    if (this->lifecycle_.load(std::memory_order_relaxed) != LifecycleState::STOPPED) {
        SS_LOGW(TAG, "add_metadata() called while started");
    }
    this->metadata_ = std::make_unique<MetadataRole>(this);
    this->metadata_->impl_->attach_inbox(this->event_state_->inbox);
    return *this->metadata_;
}
#endif

#ifdef SENDSPIN_ENABLE_COLOR
// cppcheck-suppress unusedFunction
// Public API: a live entry point the bundled examples do not exercise.
ColorRole& SendspinClient::add_color() {
    if (this->lifecycle_.load(std::memory_order_relaxed) != LifecycleState::STOPPED) {
        SS_LOGW(TAG, "add_color() called while started");
    }
    this->color_ = std::make_unique<ColorRole>(this);
    this->color_->impl_->attach_inbox(this->event_state_->inbox);
    return *this->color_;
}
#endif

#ifdef SENDSPIN_ENABLE_ARTWORK
// cppcheck-suppress unusedFunction
// Public API: a live entry point the bundled examples do not exercise.
ArtworkRole& SendspinClient::add_artwork(ArtworkRoleConfig config) {
    if (this->lifecycle_.load(std::memory_order_relaxed) != LifecycleState::STOPPED) {
        SS_LOGW(TAG, "add_artwork() called while started");
    }
    this->artwork_ = std::make_unique<ArtworkRole>(std::move(config), this);
    this->artwork_->impl_->attach_inbox(this->event_state_->inbox);
    return *this->artwork_;
}
#endif

#ifdef SENDSPIN_ENABLE_VISUALIZER
VisualizerRole& SendspinClient::add_visualizer(VisualizerRoleConfig config) {
    if (this->lifecycle_.load(std::memory_order_relaxed) != LifecycleState::STOPPED) {
        SS_LOGW(TAG, "add_visualizer() called while started");
    }
    this->visualizer_ = std::make_unique<VisualizerRole>(std::move(config), this);
    this->visualizer_->impl_->attach_inbox(this->event_state_->inbox);
    return *this->visualizer_;
}
#endif

#ifdef SENDSPIN_ENABLE_SOURCE
SourceRole& SendspinClient::add_source(SourceRoleConfig config) {
    if (this->lifecycle_.load(std::memory_order_relaxed) != LifecycleState::STOPPED) {
        SS_LOGW(TAG, "add_source() called while started");
    }
    this->source_ = std::make_unique<SourceRole>(config, this);
    this->source_->impl_->attach(this->event_state_->inbox, *this->json_arena_);
    return *this->source_;
}
#endif

// ============================================================================
// Queries
// ============================================================================

std::optional<std::string> SendspinClient::format_pairing_token(
    const std::array<uint8_t, 32>& pairing_psk) const {
    if (this->identity_ == nullptr) {
        return std::nullopt;
    }
    return sendspin::format_pairing_token(this->identity_->public_bytes, pairing_psk);
}

std::optional<std::string> SendspinClient::pairing_token() const {
    // Main-loop-only, like the other record-store config reads: the Pairing PSK is set when the
    // store is built, inside start().
    if (this->record_store_ == nullptr) {
        return std::nullopt;
    }
    const auto& pairing_psk = this->record_store_->pairing_psk();
    if (!pairing_psk.has_value()) {
        return std::nullopt;
    }
    return this->format_pairing_token(pairing_psk->psk);
}

bool SendspinClient::is_connected() const {
    return this->connection_manager_->is_connected();
}

bool SendspinClient::is_time_synced() const {
    // See ConnectionManager::time_filter().
    auto filter = this->connection_manager_->time_filter();
    return filter != nullptr && filter->has_update();
}

int64_t SendspinClient::get_client_time(int64_t server_time) const {
    // See ConnectionManager::time_filter().
    auto filter = this->connection_manager_->time_filter();
    return filter != nullptr ? filter->compute_client_time(server_time) : 0;
}

std::optional<ServerInformationObject> SendspinClient::get_server_information() const {
    return this->connection_manager_->server_information();
}

// ============================================================================
// State updates
// ============================================================================

void SendspinClient::set_available(bool available) {
    if (available == this->available_) {
        return;
    }
    this->available_ = available;
    this->publish_state();
}

void SendspinClient::leave() {
    // messaging.md "client/leave". Not a role message, so it is not routed to a role owner. The
    // protocol task applies the activation gate every outbound message has
    // (ConnectionManager::leave()).
    if (!this->is_started()) {
        SS_LOGW(TAG, "client/leave ignored: client is not running");
        return;
    }
    this->protocol_task_->post_requests({.leave = true});
}

// ============================================================================
// Role services (called by roles via SendspinClient pointer)
// ============================================================================

void SendspinClient::publish_state() {
    // Inside stop() the connections are being goodbyed and a restart publishes its own first
    // snapshot (start()).
    if (!this->is_started()) {
        return;
    }
    this->protocol_task_->publish_state(this->build_client_state());
}

ClientStateMessage SendspinClient::build_client_state() const {
    ClientStateMessage state_msg;
    state_msg.available = this->available_;

    // Every role's object: the protocol task sends each admitted connection the objects of the
    // roles it owns and has active (client_state_for_roles()).
#ifdef SENDSPIN_ENABLE_PLAYER
    if (this->player_) {
        this->player_->impl_->build_state_fields(state_msg);
    }
#endif
#ifdef SENDSPIN_ENABLE_ARTWORK
    if (this->artwork_) {
        this->artwork_->impl_->build_state_fields(state_msg);
    }
#endif
#ifdef SENDSPIN_ENABLE_VISUALIZER
    if (this->visualizer_) {
        this->visualizer_->impl_->build_state_fields(state_msg);
    }
#endif
#ifdef SENDSPIN_ENABLE_SOURCE
    if (this->source_) {
        this->source_->impl_->build_state_fields(state_msg);
    }
#endif
    return state_msg;
}

#ifdef SENDSPIN_ENABLE_CONTROLLER
bool SendspinClient::send_controller_command(const ClientCommandControllerObject& cmd,
                                             uint16_t generation) {
    // The command the controller role validated, carried as the struct so the protocol task
    // builds the message in its JSON arena and applies the role gate (handle_command()).
    if (!this->is_started()) {
        SS_LOGD(TAG, "Dropping a controller command: client is not running");
        return false;
    }
    ProtocolCommand command;
    command.type = ProtocolCommandType::SEND_CONTROLLER_COMMAND;
    command.controller_command = cmd;
    command.controller_generation = generation;
    return this->protocol_task_->push_command(std::move(command));
}
#endif

void SendspinClient::acquire_high_performance() {
    if (this->high_performance_ref_count_++ == 0 && this->listener_) {
        this->listener_->on_request_high_performance();
    }
}

void SendspinClient::release_high_performance() {
    // A release at count 0 is ignored rather than underflowing the counter.
    if (this->high_performance_ref_count_ == 0) {
        return;
    }
    if (--this->high_performance_ref_count_ == 0 && this->listener_) {
        this->listener_->on_release_high_performance();
    }
}

// ============================================================================
// Private helpers
// ============================================================================

void SendspinClient::cleanup_connection_state(uint16_t teardown_roles) {
    SS_LOGV(TAG, "Cleaning up connection state (roles 0x%04x)", teardown_roles);

    // Client-wide connection state belongs to the primary connection, so it is reset only when
    // the teardown covers every role, which is when no admitted connection is left to own one.
    // A narrower teardown (a connection that owned a subset while another stays admitted) leaves
    // the survivor's events, group and time-sync report in place.
    if (teardown_roles == ALL_ROLES_MASK) {
        // A second teardown before the main loop drains (a handoff chain can displace two
        // connections in one tick) wipes the first teardown's just-pushed *_CLEARED events here
        // before they are ever drained. Nothing is lost: every role's cleanup() below pushes its
        // own again, and the teardowns the two stamp collapse into one main-loop half, which the
        // head of every drain runs from the role's generation even without the event.
        this->event_state_->inbox.reset_events();
        this->event_state_->group_slot.reset();
        this->event_state_->time_sync_slot.reset();

        // Also wipes the not-yet-dispatched pairing listener notifications, except a dismissal
        // whose prompt was already delivered (see retain_delivered_dismissals()): a teardown
        // queues the dismissals for the connection it drops itself, but one queued by an earlier
        // drop that no drain has delivered yet must survive this one. Callers that need another
        // notification to survive teardown (e.g. handle_pair_abort's on_pairing_failed) must
        // call the corresponding note_*() after cleanup_connection_state() returns; see the
        // ConnectionManager pairing handlers.
        // An edit, not a merge: it touches only notes already pending, and one that leaves none
        // behind clears the topic bit instead of waking the drain for nothing.
        this->event_state_->pairing_slot.edit([](std::vector<PairingNote>& current) {
            retain_delivered_dismissals(current);
            return !current.empty();
        });
    }

#ifdef SENDSPIN_ENABLE_PLAYER
    if (this->player_ && (teardown_roles & role_mask_bit(SendspinRole::PLAYER)) != 0) {
        this->player_->impl_->cleanup();
    }
#endif
#ifdef SENDSPIN_ENABLE_CONTROLLER
    if (this->controller_ && (teardown_roles & role_mask_bit(SendspinRole::CONTROLLER)) != 0) {
        this->controller_->impl_->cleanup();
    }
#endif
#ifdef SENDSPIN_ENABLE_METADATA
    if (this->metadata_ && (teardown_roles & role_mask_bit(SendspinRole::METADATA)) != 0) {
        this->metadata_->impl_->cleanup();
    }
#endif
#ifdef SENDSPIN_ENABLE_COLOR
    if (this->color_ && (teardown_roles & role_mask_bit(SendspinRole::COLOR)) != 0) {
        this->color_->impl_->cleanup();
    }
#endif
#ifdef SENDSPIN_ENABLE_ARTWORK
    if (this->artwork_ && (teardown_roles & role_mask_bit(SendspinRole::ARTWORK)) != 0) {
        this->artwork_->impl_->cleanup();
    }
#endif
#ifdef SENDSPIN_ENABLE_VISUALIZER
    if (this->visualizer_ && (teardown_roles & role_mask_bit(SendspinRole::VISUALIZER)) != 0) {
        this->visualizer_->impl_->cleanup();
    }
#endif
#ifdef SENDSPIN_ENABLE_SOURCE
    // The connection is going away, or stop() ended the stream in its shutdown pass: the stream
    // closes without a client-stream/end.
    if (this->source_ && (teardown_roles & role_mask_bit(SendspinRole::SOURCE)) != 0) {
        this->source_->impl_->cleanup();
    }
#endif
}

std::string SendspinClient::build_hello_message() {
    // Reached only from ConnectionManager::send_hello_message(), so only for a connection, so
    // only after a successful start(): record_store_ exists and is dereferenced unguarded below.
    ClientHelloMessage msg;
    msg.name = this->config_.name;

    // Use the explicitly configured MAC when provided; otherwise fall back to platform detection
    // (reliable on ESP, best-effort on host). Leaves the field absent if neither is available.
    const std::optional<std::string> interface_mac =
        this->config_.mac_address ? this->config_.mac_address : platform_get_interface_mac();

    DeviceInfoObject device_info{};
    device_info.product_name = this->config_.product_name;
    device_info.manufacturer = this->config_.manufacturer;
    device_info.software_version = this->config_.software_version;
    device_info.mac_address = interface_mac;
    msg.device_info = device_info;

    // pairing.md "client/hello pair-method descriptor". offers_*() is the single source both this
    // and the server/activate admissibility check read, so a method advertised here is one an
    // activation can select. pairing_psk is always advertised (see pairing_offers.h).
    {
        PairMethodDescriptor psk_desc;
        psk_desc.method = SendspinPairMethod::PAIRING_PSK;
        psk_desc.locations = locations_hint(this->config_.pairing_psk_locations);
        msg.supported_pair_methods.push_back(std::move(psk_desc));
    }
    // out_channels and formats are both required and non-empty. No `locations`: a per-session code
    // has no resting place for the operator to look it up in.
    if (offers_dynamic_pairing_code(this->config_)) {
        PairMethodDescriptor dynamic_desc;
        dynamic_desc.method = SendspinPairMethod::DYNAMIC_PAIRING_CODE;
        dynamic_desc.out_channels = this->config_.pairing_code_out_channels;
        dynamic_desc.formats = this->config_.pairing_code_formats;
        msg.supported_pair_methods.push_back(std::move(dynamic_desc));
    }
    // Offered only when the dynamic code is not: messaging.md "client/hello" permits at most one
    // pairing-code method.
    if (offers_static_pairing_code(this->config_)) {
        PairMethodDescriptor static_desc;
        static_desc.method = SendspinPairMethod::STATIC_PAIRING_CODE;
        static_desc.locations = locations_hint(this->config_.static_pairing_code_locations);
        msg.supported_pair_methods.push_back(std::move(static_desc));
    }

    msg.unpaired_access_enabled = this->unpaired_access_enabled_.load(std::memory_order_acquire);

    // Let each role add its fields to the hello message
#ifdef SENDSPIN_ENABLE_PLAYER
    if (this->player_) {
        this->player_->impl_->build_hello_fields(msg);
    }
#endif
#ifdef SENDSPIN_ENABLE_CONTROLLER
    if (this->controller_) {
        this->controller_->impl_->build_hello_fields(msg);
    }
#endif
#ifdef SENDSPIN_ENABLE_METADATA
    if (this->metadata_) {
        this->metadata_->impl_->build_hello_fields(msg);
    }
#endif
#ifdef SENDSPIN_ENABLE_COLOR
    if (this->color_) {
        this->color_->impl_->build_hello_fields(msg);
    }
#endif
#ifdef SENDSPIN_ENABLE_ARTWORK
    if (this->artwork_) {
        this->artwork_->impl_->build_hello_fields(msg);
    }
#endif
#ifdef SENDSPIN_ENABLE_VISUALIZER
    if (this->visualizer_) {
        this->visualizer_->impl_->build_hello_fields(msg);
    }
#endif
#ifdef SENDSPIN_ENABLE_SOURCE
    if (this->source_) {
        this->source_->impl_->build_hello_fields(msg);
    }
#endif

    return format_client_hello_message(&msg, *this->json_arena_);
}

// ============================================================================
// State publishing
// ============================================================================

void SendspinClient::publish_client_state(SendspinConnection* conn) {
    // is_operational() also covers the latest server/activate: before that we do not know which
    // roles this connection owns.
    AdmittedEntry* entry = this->connection_manager_->find_admitted(conn);
    const std::optional<ClientStateMessage>& client_state = this->task_state_->client_state;
    if (entry == nullptr || !conn->accepts_app_sends() || !conn->is_operational() ||
        !client_state.has_value()) {
        return;
    }
    const ClientStateMessage& snapshot = client_state.value();

    // messaging.md "client/state": a player or source reports `available: true` only after
    // clock synchronization, and `false` would mean it will not yield, so the state waits for
    // this connection's first measurement when it owns either; run_time_sync() sends it then.
    bool clocked_role = false;
#ifdef SENDSPIN_ENABLE_PLAYER
    clocked_role =
        this->player_ && this->connection_manager_->owns_role(conn, SendspinRole::PLAYER);
#endif
#ifdef SENDSPIN_ENABLE_SOURCE
    clocked_role = clocked_role || (this->source_ && this->connection_manager_->owns_role(
                                                         conn, SendspinRole::SOURCE));
#endif
    const bool waits_for_clock = snapshot.available && clocked_role && !conn->is_time_synced();
    entry->state_held = waits_for_clock;
    if (waits_for_clock) {
        return;
    }

    // messaging.md "client/state": a role object is included only while that role is active.
    // This client includes every owned, active role's object on every update, so the first state
    // after a server/activate carries them all.
    const ClientStateMessage state_msg =
        client_state_for_roles(snapshot, entry->owned_roles & conn->get_active_role_mask());
    conn->send_app_json(format_client_state_message(&state_msg, *this->json_arena_));
}

void SendspinClient::adopt_client_state(ClientStateMessage&& snapshot) {
#ifdef SENDSPIN_ENABLE_SOURCE
    // roles/source/v1.md "Source command semantics": a source that becomes unavailable clears its
    // authorization and ends its input stream before it reports available: false.
    if (this->source_ && !snapshot.available) {
        this->source_->impl_->end_stream(
            this->connection_manager_->role_owner(SendspinRole::SOURCE));
    }
#endif
    this->task_state_->client_state = std::move(snapshot);
    this->connection_manager_->for_each_admitted(
        [this](AdmittedEntry& entry) { this->publish_client_state(entry.conn.get()); });
}

bool SendspinClient::adopted_state_available() const {
    const std::optional<ClientStateMessage>& client_state = this->task_state_->client_state;
    return client_state.has_value() && client_state->available;
}

void SendspinClient::merge_group_update(GroupUpdateObject&& delta) {
    this->event_state_->group_slot.merge(
        [](GroupUpdateObject& current, GroupUpdateObject&& added) {
            apply_group_update_deltas(&current, added);
        },
        std::move(delta));
}

// ============================================================================
// Persistence & identity
// ============================================================================

bool SendspinClient::load_or_generate_identity() {
    if (this->persistence_provider_ != nullptr) {
        auto saved_priv = this->persistence_provider_->load_blob(persistence_keys::KEYPAIR);
        // No codec involved: the keypair blob is the raw private key, so the only validation
        // needed here is the exact-length check: anything else is corrupt or the wrong key.
        if (saved_priv.has_value() && saved_priv->size() == persistence_keys::KEYPAIR_SIZE) {
            std::array<uint8_t, 32> priv_bytes{};
            std::copy(saved_priv->begin(), saved_priv->end(), priv_bytes.begin());
            auto loaded = Identity::from_private_bytes(priv_bytes);
            // The two plain byte buffers holding the raw private key cannot wipe themselves the
            // way every Identity-shaped copy does via ~Identity().
            secure_zero_container(priv_bytes);
            secure_zero_container(saved_priv.value());
            if (loaded.has_value()) {
                this->identity_ = std::make_unique<Identity>(loaded.value());
                this->client_id_ = this->identity_->peer_id();
                SS_LOGI(TAG, "Loaded static keypair; client_id=%s", this->client_id_.c_str());
                return true;
            }
            // Stored key is corrupt, or the underlying DH computation failed. Do not treat this
            // as an all-zero identity: generate a fresh one (the device will need to re-pair)
            // rather than proceed with a predictable key.
            SS_LOGW(TAG, "Stored static keypair is invalid; generating a new one");
        } else if (saved_priv.has_value()) {
            SS_LOGW(TAG, "Stored static keypair has the wrong size (%zu bytes); regenerating",
                    saved_priv->size());
        }
    }

    // No saved key, or the saved key was invalid: generate a new one.
    auto generated = Identity::generate();
    if (!generated.has_value()) {
        // No safe fallback: identity_ must never be set to a default-constructed (all-zero)
        // Identity, since that would be a fixed, publicly known private key that lets any peer
        // impersonate this device and would be persisted to flash below. Fail closed instead.
        SS_LOGE(TAG, "Failed to generate static identity keypair; cannot start");
        return false;
    }
    this->identity_ = std::make_unique<Identity>(generated.value());
    this->client_id_ = this->identity_->peer_id();

    if (this->persistence_provider_ != nullptr) {
        if (this->persistence_provider_->save_blob(persistence_keys::KEYPAIR,
                                                   this->identity_->private_bytes.data(),
                                                   this->identity_->private_bytes.size()) &&
            this->persistence_provider_->commit()) {
            SS_LOGI(TAG, "Generated and persisted static keypair; client_id=%s",
                    this->client_id_.c_str());
        } else {
            SS_LOGW(TAG, "Generated static keypair but failed to persist it; client_id=%s",
                    this->client_id_.c_str());
        }
    } else {
        SS_LOGI(TAG, "Generated ephemeral static keypair (no provider); client_id=%s",
                this->client_id_.c_str());
    }
    return true;
}

void SendspinClient::load_last_played_server() {
    if (!this->persistence_provider_) {
        return;
    }

    auto key_blob = this->persistence_provider_->load_blob(persistence_keys::LAST_PLAYED);
    if (key_blob.has_value() && key_blob->size() == persistence_keys::LAST_PLAYED_SIZE) {
        std::string server_id = b64url_encode(key_blob->data(), key_blob->size());
        this->connection_manager_->set_last_played_server_id(server_id);
        SS_LOGI(TAG, "Loaded last played server: %s", server_id.c_str());
    }
}
void SendspinClient::note_last_played_server(const std::string& server_id) {
    if (server_id.empty() || server_id == this->connection_manager_->last_played_server_id()) {
        return;
    }
    SS_LOGD(TAG, "Last played server is now %s", server_id.c_str());
    this->connection_manager_->set_last_played_server_id(server_id);
    PersistRequest request;
    request.last_played = server_id;
    this->event_state_->persist_slot.merge(merge_persist_request, std::move(request));
}

void SendspinClient::write_last_played_server(const std::string& server_id) {
    if (this->persistence_provider_) {
        // The handshake admits only a server_id that is a canonical public key.
        auto key = public_key_from_peer_id(server_id);
        if (!key.has_value()) {
            SS_LOGW(TAG, "Not persisting last played server %s: not a public key",
                    server_id.c_str());
            return;
        }
        if (this->persistence_provider_->save_blob(persistence_keys::LAST_PLAYED, key->data(),
                                                   key->size())) {
            SS_LOGD(TAG, "Persisted last played server: %s", server_id.c_str());
        } else {
            SS_LOGW(TAG, "Failed to persist last played server");
        }
    }
}

// ============================================================================
// Connection event handlers (called by ConnectionManager on the protocol task)
// ============================================================================

void SendspinClient::on_handshake_complete(SendspinConnection* conn) {
    // Entering the operational state structurally ends any pairing exchange: discard the pending
    // pairing record and reset the pairing session so a stale attempt timeout can never fire a
    // stray pair/abort on an operational connection. This is the one place every "connection is
    // now operational" path converges (normal activate, leftover activate, and winning promotion).
    // Idempotent no-op for a connection that never paired.
    const PairingUiSnapshot pairing_ui = snapshot_pairing_ui(conn);
    conn->clear_pairing_state();
    this->note_pairing_ui_dismissals(pairing_ui);

    this->publish_client_state(conn);

    // Report the trust level of the newly operational connection; the listener hears it from
    // the main loop's drain.
    ConnectionTrust trust = (conn->get_psk_category() == PskCategory::LONG_TERM)
                                ? ConnectionTrust::USER
                                : ConnectionTrust::NONE;
    this->note_trust_changed(trust);
}

void SendspinClient::apply_role_removals([[maybe_unused]] SendspinConnection* conn,
                                         [[maybe_unused]] uint16_t removed_roles) {
    // messaging.md "server/activate", "When applying a server/activate, the client MUST": every
    // removed server-to-client stream role stops its remaining output and clears its buffers, even
    // where an earlier stream/end had let buffered data finish, and every removed role with a
    // server/state object discards its current state and any pending scheduled update.
    //
    // That is exactly what each role's cleanup() does, so deactivation runs the same teardown the
    // disconnect path runs. Only the surroundings differ: the connection survives, so the inbox
    // ring is not reset first (the roles that stay active keep their queued lifecycle events) and
    // the role may be re-added later. Coming back is the role's ordinary start path: a role
    // whose state object the server needs again is carried by the client/state the activation
    // publishes, and a stream role re-arms on the next stream/start.
#ifdef SENDSPIN_ENABLE_PLAYER
    if (this->player_ && role_removed(removed_roles, SendspinRole::PLAYER)) {
        this->player_->impl_->cleanup();
    }
#endif
#ifdef SENDSPIN_ENABLE_CONTROLLER
    if (this->controller_ && role_removed(removed_roles, SendspinRole::CONTROLLER)) {
        this->controller_->impl_->cleanup();
    }
#endif
#ifdef SENDSPIN_ENABLE_METADATA
    if (this->metadata_ && role_removed(removed_roles, SendspinRole::METADATA)) {
        this->metadata_->impl_->cleanup();
    }
#endif
#ifdef SENDSPIN_ENABLE_COLOR
    if (this->color_ && role_removed(removed_roles, SendspinRole::COLOR)) {
        this->color_->impl_->cleanup();
    }
#endif
#ifdef SENDSPIN_ENABLE_ARTWORK
    // roles/artwork/v1.md "Server -> Client: Artwork (Binary)" clears the current image and
    // discards the pending one for the whole role, which is what cleanup() carries out per
    // channel.
    if (this->artwork_ && role_removed(removed_roles, SendspinRole::ARTWORK)) {
        this->artwork_->impl_->cleanup();
    }
#endif
#ifdef SENDSPIN_ENABLE_VISUALIZER
    if (this->visualizer_ && role_removed(removed_roles, SendspinRole::VISUALIZER)) {
        this->visualizer_->impl_->cleanup();
    }
#endif
#ifdef SENDSPIN_ENABLE_SOURCE
    // roles/source/v1.md "Source command semantics": the removal clears the authorization and ends
    // an open stream with client-stream/end, on a connection that stays up.
    if (this->source_ && role_removed(removed_roles, SendspinRole::SOURCE)) {
        this->source_->impl_->end_stream(conn);
        this->source_->impl_->cleanup();
    }
#endif
}

void SendspinClient::on_activation_applied([[maybe_unused]] SendspinConnection* conn) {
#ifdef SENDSPIN_ENABLE_SOURCE
    if (this->source_) {
        this->source_->impl_->on_activation_applied(*conn);
    }
#endif
}

void SendspinClient::note_pairing_started(const std::string& server_id) {
    this->event_state_->push_pairing_note(
        {.type = PairingNoteType::PAIRING_STARTED, .text = server_id});
}

void SendspinClient::note_pairing_succeeded(const std::string& server_id) {
    this->event_state_->push_pairing_note(
        {.type = PairingNoteType::PAIRING_SUCCEEDED, .text = server_id});
}

void SendspinClient::note_pairing_failed(const std::string& server_id,
                                         SendspinPairAbortReason reason) {
    this->event_state_->push_pairing_note(
        {.type = PairingNoteType::PAIRING_FAILED, .text = server_id, .reason = reason});
}

void SendspinClient::note_display_pairing_code(const std::string& code,
                                               SendspinPairingCodeFormat format) {
    this->event_state_->push_pairing_note(
        {.type = PairingNoteType::DISPLAY_PAIRING_CODE, .text = code, .format = format});
}

void SendspinClient::note_clear_pairing_code() {
    this->event_state_->push_pairing_note({.type = PairingNoteType::CLEAR_PAIRING_CODE});
}

void SendspinClient::note_open_pairing_window() {
    this->event_state_->push_pairing_note({.type = PairingNoteType::OPEN_PAIRING_WINDOW});
}

void SendspinClient::note_close_pairing_window() {
    this->event_state_->push_pairing_note({.type = PairingNoteType::CLOSE_PAIRING_WINDOW});
}

void SendspinClient::note_pairing_ui_dismissals(const PairingUiSnapshot& ui) {
    if (ui.code_was_emitted) {
        this->note_clear_pairing_code();
    }
    if (ui.window_was_shown) {
        this->note_close_pairing_window();
    }
}

void SendspinClient::note_trust_changed(ConnectionTrust trust) {
    this->event_state_->push_pairing_note({.type = PairingNoteType::TRUST_CHANGED, .trust = trust});
}

void SendspinClient::confirm_pairing_window() {
    if (!this->is_started()) {
        SS_LOGD(TAG, "confirm_pairing_window() ignored: client is not running");
        return;
    }
    this->protocol_task_->post_requests({.pairing_window = PairingWindowRequest::CONFIRM});
}

void SendspinClient::cancel_pairing_window() {
    if (!this->is_started()) {
        SS_LOGD(TAG, "cancel_pairing_window() ignored: client is not running");
        return;
    }
    this->protocol_task_->post_requests({.pairing_window = PairingWindowRequest::CANCEL});
}

void SendspinClient::set_unpaired_access_enabled(bool enabled) {
    if (this->unpaired_access_enabled_.exchange(enabled, std::memory_order_acq_rel) == enabled) {
        return;
    }
    // The new value reaches the next client/hello through the flag. The connections the change
    // no longer fits are closed by the protocol task, which reads the flag when it applies the
    // request, so a change and its reversal before that tick apply as the value they end on;
    // with the client stopped there are none.
    if (!this->is_started()) {
        return;
    }
    this->protocol_task_->post_requests({.unpaired_access_changed = true});
}

bool SendspinClient::is_unpaired_access_enabled() const {
    return this->unpaired_access_enabled_.load(std::memory_order_acquire);
}

}  // namespace sendspin
