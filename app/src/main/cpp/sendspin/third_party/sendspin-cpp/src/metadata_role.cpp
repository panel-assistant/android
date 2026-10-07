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

#include "audio_stream_info.h"
#include "metadata_role_impl.h"
#include "platform/time.h"
#include "protocol_messages.h"
#include "sendspin/client.h"

#include <algorithm>
#include <utility>

static const char* const TAG = "sendspin.metadata";

namespace sendspin {

namespace {

/// @brief Folds a newly arrived state into the states the main loop has not taken yet
///
/// `incoming` always carries exactly one state, in `oldest`. The first one to arrive after a
/// drain keeps that place; every later one becomes `newest` (see PendingMetadataStates). Runs
/// under the Inbox mutex, so it stays a pure data operation.
void coalesce_metadata_states(PendingMetadataStates& current, PendingMetadataStates&& incoming) {
    if (!current.oldest.has_value()) {
        current.oldest = std::move(incoming.oldest);
        return;
    }
    current.newest = std::move(incoming.oldest);
}

}  // namespace

// ============================================================================
// Impl constructor / destructor
// ============================================================================

MetadataRole::Impl::Impl(SendspinClient* client)
    : client(client), event_state(std::make_unique<EventState>()) {}

// ============================================================================
// MetadataRole forwarding (public API → Impl)
// ============================================================================

MetadataRole::MetadataRole(SendspinClient* client) : impl_(std::make_unique<Impl>(client)) {}

MetadataRole::~MetadataRole() = default;

void MetadataRole::set_listener(MetadataRoleListener* listener) {
    this->impl_->listener = listener;
}

uint32_t MetadataRole::get_track_duration_ms() const {
    return this->impl_->get_track_duration_ms();
}

uint32_t MetadataRole::get_track_progress_ms() const {
    return this->impl_->get_track_progress_ms();
}

// ============================================================================
// Impl method implementations
// ============================================================================

uint32_t MetadataRole::Impl::get_track_duration_ms() const {
    if (!this->metadata.progress.has_value()) {
        return 0;
    }
    return this->metadata.progress.value().track_duration;
}

uint32_t MetadataRole::Impl::get_track_progress_ms() const {
    if (!this->metadata.progress.has_value()) {
        return 0;
    }

    const auto& progress = this->metadata.progress.value();

    // If paused (playback_speed == 0), return the snapshot value directly
    if (progress.playback_speed == 0) {
        return progress.track_progress;
    }

    int64_t client_target = this->client->get_client_time(this->metadata.timestamp);
    if (client_target == 0) {
        return progress.track_progress;
    }

    // calculated_progress = track_progress + (now - metadata_client_time) * playback_speed /
    // 1_000_000
    int64_t elapsed_us = platform_time_us() - client_target;
    int64_t calculated = static_cast<int64_t>(progress.track_progress) +
                         elapsed_us * static_cast<int64_t>(progress.playback_speed) /
                             static_cast<int64_t>(US_PER_SECOND);

    if (progress.track_duration != 0) {
        calculated = std::max(std::min(calculated, static_cast<int64_t>(progress.track_duration)),
                              static_cast<int64_t>(0));
    } else {
        calculated = std::max(calculated, static_cast<int64_t>(0));
    }

    return static_cast<uint32_t>(calculated);
}

void MetadataRole::Impl::attach_inbox(Inbox& inbox) {
    this->inbox = &inbox;
    this->event_state->slot.bind(inbox, INBOX_TOPIC_METADATA);
}

void MetadataRole::Impl::build_hello_fields(ClientHelloMessage& msg) {
    msg.supported_roles.push_back(SendspinRole::METADATA);
}

void MetadataRole::Impl::handle_server_state(ServerMetadataStateObject&& metadata) const {
    const uint32_t generation = this->cleanup_generation.load(std::memory_order_acquire);
    // messaging.md "server/state": each included metadata object is the role's full state, never
    // an overlay on the one before it (see coalesce_metadata_states).
    PendingMetadataStates arrival;
    arrival.oldest = std::move(metadata);
    this->event_state->slot.merge(coalesce_metadata_states, std::move(arrival), generation);
}

bool MetadataRole::Impl::state_is_due(int64_t timestamp) const {
    // get_client_time returns 0 when there is no current connection. Without a connection we
    // cannot honor the server-clock deadline, so a state is due at once rather than starving the
    // listener.
    const int64_t client_ts = this->client->get_client_time(timestamp);
    return client_ts == 0 || client_ts <= platform_time_us();
}

void MetadataRole::Impl::apply_due_state() {
    if (!this->held_state.has_value() || !this->state_is_due(this->held_state->timestamp)) {
        return;
    }

    this->metadata = std::move(this->held_state.value());
    this->held_state.reset();
    if (this->listener) {
        this->listener->on_metadata(this->metadata);
    }
}

void MetadataRole::Impl::drain_events() {
    PendingMetadataStates taken;
    bool have_taken = false;
    const uint32_t generation =
        take_current_payload(*this, this->event_state->slot, taken, have_taken, TAG, "metadata");
    // Every check below against `generation` also catches a listener callback that re-entered
    // teardown (a listener calling stop()): its cleanup() moved the generation on, and its own
    // drain already reset this role.
    if (!this->accepts(generation)) {
        return;
    }

    // InboxSlot has no take_if (a deadline predicate must not run under the shared Inbox mutex;
    // see inbox.h), so the server-clock deadline gate is split in two: take() unconditionally
    // moves the pending states into held_state, then each deadline is evaluated below with no
    // lock held at all.
    //
    // roles/metadata/v1.md "Scheduled metadata updates": a state whose timestamp is still in the
    // future is the pending update and a newer one replaces it, while a past or present one is
    // applied at once and discards the pending update. Both fall out of replacing held_state with
    // each taken state in arrival order, applying whatever is due in between.
    if (have_taken) {
        const bool collapses =
            taken.newest.has_value() && this->state_is_due(taken.newest->timestamp);
        if (taken.oldest.has_value() && !collapses) {
            // Two states that are both due collapse instead: the older would be superseded
            // within this tick, so no listener could observe it.
            this->held_state = std::move(taken.oldest);
            this->apply_due_state();
            if (!this->accepts(generation)) {
                return;
            }
        }
        if (taken.newest.has_value()) {
            this->held_state = std::move(taken.newest);
        }
    }

    // A future-dated state is held across ticks without any topic bit set (take() above cleared
    // it). It is re-evaluated against its deadline on later ticks only because needs_drain() ORs
    // in held_state.has_value() alongside the INBOX_TOPIC_METADATA bit test, so this
    // drain_events() keeps running each tick until the deadline fires. Dropping that OR term
    // would strand the state until an unrelated new one re-set the topic bit.
    this->apply_due_state();
}

void MetadataRole::Impl::cleanup() {
    // Bumped first, so the drain discards a payload stamped before it (see RoleTeardown).
    const uint32_t generation =
        this->cleanup_generation.fetch_add(1, std::memory_order_acq_rel) + 1;
    this->event_state->slot.reset();

    push_event_or_log(this->inbox, InboxEventType::METADATA_CLEARED, 0, TAG,
                      "metadata cleared event", generation);
}

void MetadataRole::Impl::complete_teardown() {
    this->metadata = {};
    this->held_state.reset();
    if (this->listener) {
        this->listener->on_metadata_clear();
    }
}

}  // namespace sendspin
