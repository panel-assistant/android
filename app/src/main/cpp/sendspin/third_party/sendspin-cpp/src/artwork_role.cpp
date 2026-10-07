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

#include "artwork_role_impl.h"
#include "constants.h"
#include "crypto/constants.h"
#include "platform/logging.h"
#include "platform/thread.h"
#include "platform/time.h"
#include "protocol_messages.h"
#include "sendspin/client.h"

#include <algorithm>
#include <cstring>
#include <utility>

static const char* const TAG = "sendspin.artwork";

// ============================================================================
// Constants
// ============================================================================

/// @brief Largest artwork message, in bytes: one Noise transport message without fragmentation,
/// per roles/artwork/v1.md "Artwork (Binary)"
static constexpr size_t ARTWORK_MAX_MESSAGE_SIZE = sendspin::MAX_TRANSPORT_PLAINTEXT;

/// @brief Size of an announce message: type, flags, the 8-byte timestamp, the 4-byte total_size
static constexpr size_t ARTWORK_ANNOUNCE_SIZE =
    2 + sendspin::BINARY_TIMESTAMP_SIZE + sizeof(uint32_t);

/// @brief Flags byte bits: a part sets neither cancel nor announce, and bits 2-7 must be zero
static constexpr uint8_t ARTWORK_FLAG_CANCEL = 0x01;
static constexpr uint8_t ARTWORK_FLAG_ANNOUNCE = 0x02;
static constexpr uint8_t ARTWORK_FLAGS_RESERVED = 0xFC;

/// @brief Offset of a part's image data in its message: the type byte, then the flags byte
static constexpr uint8_t ARTWORK_PART_DATA_OFFSET = 2;
static_assert(ARTWORK_PART_DATA_OFFSET == sendspin::INBOUND_ARTWORK_PART_HEADER_BYTES,
              "the artwork quota's part overhead counts the part header");

/// @brief Every channel's bit, the mask of a stream end's DISCARD marker
static constexpr uint8_t ARTWORK_ALL_CHANNELS = (1U << sendspin::ARTWORK_MAX_SLOTS) - 1U;

// Decode thread command bit, signalled on its item list's flags (InboundItemList::signal())
static constexpr uint32_t COMMAND_STOP = sendspin::InboundItemList::FIRST_CONSUMER_BIT;
static_assert(COMMAND_STOP <= sendspin::InboundItemList::LAST_CONSUMER_BIT,
              "the decode thread's command bit must fit the list's usable event bits");

// ============================================================================
// Big-endian helpers
// ============================================================================

/// @brief Swaps bytes of a big-endian 32-bit value to host byte order
static uint32_t be32_to_host(const uint8_t* bytes) {
    uint32_t val = 0;
    for (int i = 0; i < 4; ++i) {
        val = (val << 8) | bytes[i];
    }
    return val;
}

namespace sendspin {

// ============================================================================
// ArtworkRole::Impl lifecycle
// ============================================================================

ArtworkRole::Impl::Impl(ArtworkRoleConfig config, SendspinClient* client)
    : config(std::move(config)),
      client(client),
      drain_task(std::make_unique<DrainTask>()),
      event_state(std::make_unique<EventState>()) {
    // The array index is authoritative for channel/slot mapping: client/state reports channels
    // in this->artwork_channels order, and handle_binary looks up this->config.preferred_formats
    // by index to match.
    if (this->config.preferred_formats.size() > ARTWORK_MAX_SLOTS) {
        SS_LOGW(TAG, "Artwork configured with %zu channels, truncating to %zu",
                this->config.preferred_formats.size(), ARTWORK_MAX_SLOTS);
        this->config.preferred_formats.resize(ARTWORK_MAX_SLOTS);
    }
    for (size_t i = 0; i < this->config.preferred_formats.size(); ++i) {
        const auto& pref = this->config.preferred_formats[i];
        this->artwork_channels.push_back({pref.source, pref.format, pref.width, pref.height});
        // Said once here rather than once per refused image: a channel budgeted 0 bytes is
        // still announced to the server.
        if (pref.max_image_bytes == 0 && pref.source != SendspinImageSource::NONE) {
            SS_LOGW(TAG, "Artwork channel %zu holds no image: max_image_bytes is 0", i);
        }
    }
}

ArtworkRole::Impl::~Impl() {
    this->stop();
}

void ArtworkRole::Impl::attach_inbox(Inbox& inbox) {
    this->inbox = &inbox;
    this->event_state->display_slot.bind(inbox, INBOX_TOPIC_ARTWORK_DISPLAY);
}

bool ArtworkRole::Impl::start(InboundRing* ring) {
    if (this->drain_task->drain_thread.joinable()) {
        return true;  // Already running
    }
    // One assembly buffer per configured channel, at its cap, held for the run: each image is
    // copied into it part by part and decoded from it, so nothing is allocated per image. Image
    // data prefers SPIRAM, where it is decoded from once and never touched on a timing-critical
    // path. stop() hands the buffers back, so a stopped role holds no image memory.
    auto release_buffers = [this]() {
        for (auto& assembly : this->drain_task->assemblies) {
            assembly.buffer = PlatformBuffer{};
        }
    };
    for (size_t i = 0; i < this->config.preferred_formats.size(); ++i) {
        const uint32_t cap = this->config.preferred_formats[i].max_image_bytes;
        if (cap > 0 && !this->drain_task->assemblies[i].buffer.allocate(
                           cap, MemoryLocation::PREFER_EXTERNAL)) {
            SS_LOGE(TAG, "Failed to allocate the %" PRIu32 " byte artwork buffer for channel %zu",
                    cap, i);
            release_buffers();
            return false;
        }
    }
    if (!this->drain_task->inbound.bind(ring, InboundHolder::ARTWORK)) {
        SS_LOGE(TAG, "Failed to create the artwork item list");
        release_buffers();
        return false;
    }

    // So a restart inherits no command from the previous run.
    this->drain_task->inbound.items().clear_signals();

    platform_configure_thread("SsArt", 4096, static_cast<int>(this->config.priority),
                              this->config.psram_stack);
    this->drain_task->drain_thread = std::thread(drain_thread_func, this);
    return true;
}

bool ArtworkRole::Impl::signal_stop() const {
    if (!this->drain_task || !this->drain_task->drain_thread.joinable()) {
        return false;
    }
    this->drain_task->inbound.items().signal(COMMAND_STOP);
    return true;
}

void ArtworkRole::Impl::stop() const {
    if (!this->signal_stop()) {
        return;
    }
    this->drain_task->drain_thread.join();

    // Joined: the thread returns every item as soon as it has read it, so what is left on the
    // list goes back to the ring here, and a restart does not decode the previous session's images.
    this->drain_task->inbound.unbind();
    for (auto& assembly : this->drain_task->assemblies) {
        assembly.buffer = PlatformBuffer{};
    }
}

void ArtworkRole::Impl::build_hello_fields(ClientHelloMessage& msg) const {
    if (this->artwork_channels.empty()) {
        return;
    }
    // messaging.md "client/hello" defines no artwork support object: the role is listed here and
    // the channels it wants are reported in client/state (see build_state_fields).
    msg.supported_roles.push_back(SendspinRole::ARTWORK);
}

void ArtworkRole::Impl::build_state_fields(ClientStateMessage& msg) const {
    if (this->artwork_channels.empty()) {
        return;
    }

    ClientArtworkStateObject artwork_state{};
    artwork_state.channels = this->artwork_channels;
    msg.artwork = std::move(artwork_state);
}

// ============================================================================
// Display-deadline and ack-gate helpers (used from the protocol task, decode, and main threads)
// ============================================================================

void ArtworkRole::Impl::merge_artwork_display_update(ArtworkDisplayUpdate& current,
                                                     ArtworkDisplayUpdate&& delta) {
    current.valid_mask |= delta.valid_mask;
    for (uint8_t slot = 0; slot < ARTWORK_MAX_SLOTS; ++slot) {
        const uint8_t bit = static_cast<uint8_t>(1U << slot);
        if (delta.valid_mask & bit) {
            current.timestamps[slot] = delta.timestamps[slot];
            current.epochs[slot] = delta.epochs[slot];
            // clear_mask is assigned, not OR-ed: it says what kind of delivery this slot's
            // (latest-wins) pending entry is, so a frame arriving after an undrained clear must
            // reset the bit just as a clear after an undrained frame sets it.
            if (delta.clear_mask & bit) {
                current.clear_mask |= bit;
            } else {
                current.clear_mask &= static_cast<uint8_t>(~bit);
            }
        }
    }
}

int64_t ArtworkRole::Impl::display_overdue_us(int64_t client_ts, int32_t display_offset_ms,
                                              int64_t now) {
    // get_client_time returns 0 when there is no current connection. Without a connection we
    // cannot honor the server-clock deadline, so fire immediately rather than starving the
    // listener; the lateness is 0 by definition since no deadline exists. The check must precede
    // the offset shift so the sentinel is never mistaken for a real deadline.
    if (client_ts == 0) {
        return 0;
    }
    // Positive display_offset_ms fires the display early (mirroring
    // PlayerRoleConfig::fixed_delay_us), negative delays it; see ImageSlotPreference.
    return now - (client_ts - static_cast<int64_t>(display_offset_ms) * US_PER_MS);
}

uint32_t ArtworkRole::Impl::display_lateness_ms(int64_t client_ts, int64_t overdue_us) {
    // No connection: no deadline exists, so report the documented 0 sentinel (see
    // display_overdue_us and on_image_display's contract).
    if (client_ts == 0) {
        return 0;
    }
    // Connected: floor at 1 ms. A display firing under a millisecond late truncates to 0 ms,
    // which would collide with the no-connection sentinel above; on-time displays must report a
    // small nonzero value, never exactly 0. Clamp the top so a huge lateness (~49 days) saturates
    // instead of wrapping.
    int64_t ms = std::min<int64_t>(overdue_us / US_PER_MS, UINT32_MAX);
    return static_cast<uint32_t>(std::max<int64_t>(ms, 1));
}

bool ArtworkRole::Impl::ack_enabled(uint8_t slot) const {
    return slot < this->config.preferred_formats.size() &&
           this->config.preferred_formats[slot].require_frame_done;
}

void ArtworkRole::Impl::wake_drain_thread() const {
    // Read on the thread that binds and unbinds the list (the main loop), or on the protocol
    // task, which runs only inside a run.
    if (this->drain_task->inbound.ring() != nullptr) {
        this->drain_task->inbound.items().wake_receiver();
    }
}

// ============================================================================
// Binary handling (protocol task)
// ============================================================================

uint32_t ArtworkRole::Impl::image_cap(uint8_t slot) const {
    // A channel the role never declared never asked for an image, so it holds nothing.
    if (slot >= this->config.preferred_formats.size()) {
        return 0;
    }
    return this->config.preferred_formats[slot].max_image_bytes;
}

SendspinImageFormat ArtworkRole::Impl::image_format(uint8_t slot) const {
    if (slot >= this->config.preferred_formats.size()) {
        return SendspinImageFormat::JPEG;
    }
    return this->config.preferred_formats[slot].format;
}

ArtworkAnnounce ArtworkRole::Impl::parse_announce(const uint8_t* body) {
    ArtworkAnnounce announce;
    announce.timestamp = be64_to_host(body + 1);
    announce.total_size = be32_to_host(body + 1 + BINARY_TIMESTAMP_SIZE);
    return announce;
}

void ArtworkRole::Impl::hand_marker(ArtworkItemType type, uint8_t mask, uint32_t generation) const {
    // A role that is not running hands nothing and has nothing to warn about.
    InboundConsumer& inbound = this->drain_task->inbound;
    if (!inbound.hand_local(
            nullptr, 0,
            {.data_len = 0, .serial = mask, .type = static_cast<uint8_t>(type), .data_offset = 0},
            generation) &&
        inbound.ring() != nullptr) {
        // The slot epochs, already moved on, still keep a discarded image from being delivered.
        SS_LOGW(TAG, "Failed to append an artwork marker");
    }
}

void ArtworkRole::Impl::discard_all_pending() {
    this->streamed_channels.reset();
    this->transfer = ArtworkTransfer{};
    for (auto& epoch : this->slot_epochs) {
        epoch.fetch_add(1, std::memory_order_relaxed);
    }
}

void ArtworkRole::Impl::discard_pending(uint8_t slot) {
    if (this->transfer.in_flight && this->transfer.slot == slot) {
        this->transfer = ArtworkTransfer{};
    }
    this->slot_epochs[slot].fetch_add(1, std::memory_order_relaxed);
}

bool ArtworkRole::Impl::begin_transfer(uint8_t slot, const uint8_t* body, uint32_t generation) {
    ArtworkAnnounce announce = parse_announce(body);

    // roles/artwork/v1.md "Artwork (Binary)": "The server MUST NOT announce an image, on any
    // channel, while a transfer is in flight".
    if (this->transfer.in_flight) {
        SS_LOGW(TAG, "Artwork announce for slot %u while slot %u is mid-transfer", slot,
                this->transfer.slot);
        return false;
    }

    // "An announce discards that channel's pending image." The decode thread drops the image in
    // the channel's buffer when it takes the announce (or the marker below) handed after it.
    announce.epoch = this->slot_epochs[slot].fetch_add(1, std::memory_order_relaxed) + 1;
    const uint8_t mask = static_cast<uint8_t>(1U << slot);
    InboundConsumer& inbound = this->drain_task->inbound;
    const InboundItemFields announce_fields{.data_len = sizeof(announce),
                                            .serial = slot,
                                            .type = static_cast<uint8_t>(ArtworkItemType::ANNOUNCE),
                                            .data_offset = 0};

    // "An announce with total_size 0 completes immediately, with no parts", and is how the server
    // clears a channel. Its announce is all the decode thread needs.
    if (announce.total_size == 0) {
        if (!inbound.hand_local(&announce, sizeof(announce), announce_fields, generation) &&
            inbound.ring() != nullptr) {
            SS_LOGW(TAG, "Failed to append an artwork clear for slot %u; dropping it", slot);
        }
        return true;
    }

    // "During an active stream, unavailable clients SHOULD discard otherwise valid image data and
    // MUST NOT close solely for its arrival": an image the role will not hold is refused here and
    // the transfer runs to its end handing nothing over. A role with no listener has nowhere to
    // put an image either, so it takes the same path.
    const uint32_t cap = this->image_cap(slot);
    bool holds = announce.total_size <= cap && this->listener != nullptr;
    if (announce.total_size > cap && this->listener != nullptr) {
        SS_LOGW(TAG,
                "Artwork image of %" PRIu32 " bytes for slot %u exceeds its %" PRIu32 " byte cap",
                announce.total_size, slot, cap);
    }
    if (holds && !inbound.hand_local(&announce, sizeof(announce), announce_fields, generation)) {
        if (inbound.ring() != nullptr) {
            SS_LOGW(TAG, "Failed to append an artwork announce for slot %u; dropping its image",
                    slot);
        }
        holds = false;
    }
    if (!holds) {
        this->hand_marker(ArtworkItemType::DISCARD, mask, generation);
    }
    this->transfer = ArtworkTransfer{
        .total_size = announce.total_size, .in_flight = true, .slot = slot, .discarding = !holds};
    return true;
}

bool ArtworkRole::Impl::hand_part(uint8_t slot, InboundMessage& message, uint32_t generation) {
    auto& t = this->transfer;
    // roles/artwork/v1.md "Artwork (Binary)": "a part received with no transfer in flight or on
    // a channel other than the in-flight transfer's" is a malformed sequence.
    if (!t.in_flight || t.slot != slot) {
        SS_LOGW(TAG, "Artwork part for slot %u with no transfer in flight on it", slot);
        return false;
    }
    // "a part whose data would extend past total_size" is a malformed sequence.
    const size_t part_len = message.len - ARTWORK_PART_DATA_OFFSET;
    if (part_len > static_cast<size_t>(t.total_size - t.received)) {
        SS_LOGW(TAG, "Artwork part of %zu bytes overruns the %" PRIu32 " byte image on slot %u",
                part_len, t.total_size, slot);
        return false;
    }
    t.received += static_cast<uint32_t>(part_len);
    const bool last = t.received == t.total_size;

    if (!t.discarding) {
        // The artwork quota bounds the parts waiting for the decode thread to copy them out
        // (INBOUND_ARTWORK_IN_FLIGHT_IMAGES).
        const bool handed = this->drain_task->inbound.hand_message(
            message,
            {.data_len = static_cast<uint32_t>(part_len),
             .serial = slot,
             .type = static_cast<uint8_t>(ArtworkItemType::PART),
             .data_offset = ARTWORK_PART_DATA_OFFSET},
            generation);
        if (!handed) {
            // The image cannot be completed without this part, so the rest of its transfer is
            // followed without handing anything over, and the decode thread drops what it has.
            t.discarding = true;
            this->hand_marker(ArtworkItemType::DISCARD, static_cast<uint8_t>(1U << slot),
                              generation);
        }
    }

    // "the transfer is complete when the received data reaches total_size".
    if (last) {
        t = ArtworkTransfer{};
    }
    return true;
}

bool ArtworkRole::Impl::handle_binary(uint8_t slot, InboundMessage& message) {
    const uint32_t generation = this->cleanup_generation.load(std::memory_order_acquire);
    // roles/artwork/v1.md "Artwork (Binary)" splits its closing rules in two. The
    // malformed-message rules judge the shape of the bytes alone and are not scoped to a stream,
    // so they run first; the malformed-sequence rules are scoped to an active artwork stream and
    // run below the stream gate.
    if (message.len < 2 || message.len > ARTWORK_MAX_MESSAGE_SIZE) {
        SS_LOGW(TAG, "Artwork message of %zu bytes is outside the 2 to %zu byte range", message.len,
                ARTWORK_MAX_MESSAGE_SIZE);
        return false;
    }

    // The message from its flags byte on.
    const uint8_t* body = message.data + 1;
    const uint8_t flags = body[0];
    if ((flags & ARTWORK_FLAGS_RESERVED) != 0) {
        SS_LOGW(TAG, "Artwork message sets reserved flag bits (0x%02X)", flags);
        return false;
    }
    const bool is_cancel = (flags & ARTWORK_FLAG_CANCEL) != 0;
    const bool is_announce = (flags & ARTWORK_FLAG_ANNOUNCE) != 0;
    if (is_cancel && is_announce) {
        SS_LOGW(TAG, "Artwork message sets both the cancel and announce flags");
        return false;
    }
    if (is_announce && message.len != ARTWORK_ANNOUNCE_SIZE) {
        SS_LOGW(TAG, "Artwork announce of %zu bytes is not %zu bytes", message.len,
                ARTWORK_ANNOUNCE_SIZE);
        return false;
    }
    if (is_cancel && message.len != 2) {
        SS_LOGW(TAG, "Artwork cancel of %zu bytes carries a body", message.len);
        return false;
    }

    // roles/artwork/v1.md "Artwork (Binary)": "Servers MUST NOT send artwork messages outside an
    // active artwork stream." One that arrives anyway is ignored rather than closed on: the
    // sequence rules below are scoped to an active stream.
    if (!this->stream_active) {
        return true;
    }
    // Unreachable: the caller decodes the slot from binary message IDs 8-11.
    if (slot >= ARTWORK_MAX_SLOTS) {
        return true;
    }

    if (is_cancel) {
        // "Cancel message: ... It discards the channel's pending image, taking effect
        // immediately; the current image is unaffected."
        this->discard_pending(slot);
        this->hand_marker(ArtworkItemType::DISCARD, static_cast<uint8_t>(1U << slot), generation);
        return true;
    }
    return is_announce ? this->begin_transfer(slot, body, generation)
                       : this->hand_part(slot, message, generation);
}

// ============================================================================
// Stream lifecycle (protocol task)
// ============================================================================

void ArtworkRole::Impl::handle_stream_start(const ServerArtworkStreamObject& stream) {
    const uint32_t generation = this->cleanup_generation.load(std::memory_order_acquire);
    if (stream.channels.has_value()) {
        const auto& server_channels = stream.channels.value();
        if (server_channels.size() != this->artwork_channels.size()) {
            SS_LOGW(TAG, "Artwork channel count mismatch: server sent %zu, expected %zu",
                    server_channels.size(), this->artwork_channels.size());
        }
        size_t n = std::min(server_channels.size(), this->artwork_channels.size());
        for (size_t i = 0; i < n; ++i) {
            const auto& srv = server_channels[i];
            const auto& req = this->artwork_channels[i];
            if (srv.source.has_value() && srv.source.value() != req.source) {
                SS_LOGW(TAG, "Artwork channel %zu source mismatch", i);
            }
            if (srv.format.has_value() && srv.format.value() != req.format) {
                SS_LOGW(TAG, "Artwork channel %zu format mismatch", i);
            }
            if (srv.width.has_value() && srv.width.value() != req.width) {
                SS_LOGW(TAG,
                        "Artwork channel %zu width mismatch: server %" PRIu16 ", expected %" PRIu16,
                        i, srv.width.value(), req.width);
            }
            if (srv.height.has_value() && srv.height.value() != req.height) {
                SS_LOGW(TAG,
                        "Artwork channel %zu height mismatch: server %" PRIu16
                        ", expected %" PRIu16,
                        i, srv.height.value(), req.height);
            }
        }
    }

    // No display_slot.reset() here: it would discard the pending display of every channel,
    // including the unchanged ones this stream/start must leave alone. A display published by
    // the decode thread carries the epoch it was decoded under, so drain_events() drops the ones
    // whose channel moved on whether or not they have been folded into the main-thread holds yet.
    //
    // roles/artwork/v1.md "stream/start artwork object": "A stream/start that changes a channel's
    // configuration likewise discards that channel's pending image, and the server re-sends the
    // image if it still applies." A channel the server left alone keeps the image it already
    // scheduled, which the server will neither cancel nor re-send. Bumping the changed channels'
    // epochs is the discard (see slot_epochs); the RECONFIGURE marker has the decode thread drop
    // the image it assembles or parks for them, and release a changed channel's DECODE_DELIVERED
    // ack gate: its epoch was just bumped, so that decode's eventual display can no longer fire,
    // and leaving the gate armed would wedge the slot forever. PRESENTED stays armed: that delivery
    // has already reached the consumer, which may still be mid-fade on it and owes the
    // frame_done() that says so. The marker precedes every item of the new stream on the decode
    // thread's list.
    //
    // A transfer in flight ends here only if its channel changed; the server cancels those first
    // (roles/artwork/v1.md "Artwork (Binary)"), and one on an unchanged channel continues.
    this->stream_active = true;
    const uint8_t changed = this->changed_channel_mask(stream);
    this->streamed_channels = stream.channels;
    if ((changed & static_cast<uint8_t>(1U << this->transfer.slot)) != 0) {
        this->transfer = ArtworkTransfer{};
    }
    for (uint8_t slot = 0; slot < ARTWORK_MAX_SLOTS; ++slot) {
        if ((changed & static_cast<uint8_t>(1U << slot)) != 0) {
            this->slot_epochs[slot].fetch_add(1, std::memory_order_relaxed);
        }
    }
    if (changed != 0) {
        this->hand_marker(ArtworkItemType::RECONFIGURE, changed, generation);
    }
}

uint8_t ArtworkRole::Impl::changed_channel_mask(const ServerArtworkStreamObject& stream) const {
    // Without a channel array on one side or the other there is nothing to compare, so every
    // channel counts as changed. That covers the first stream/start of a connection, where no
    // channel has a pending image to lose anyway.
    if (!this->streamed_channels.has_value() || !stream.channels.has_value()) {
        return ARTWORK_ALL_CHANNELS;
    }
    const auto& before = this->streamed_channels.value();
    const auto& now = stream.channels.value();

    uint8_t changed = 0;
    for (uint8_t slot = 0; slot < ARTWORK_MAX_SLOTS; ++slot) {
        // roles/artwork/v1.md "stream/start artwork object": "The channels array is positional
        // from channel 0 and never longer than 4. A channel the array does not cover ... is not
        // streamed", so coverage differing between the two arrays is itself a change.
        const bool had = slot < before.size();
        const bool has = slot < now.size();
        if (had != has || (had && !same_channel(before[slot], now[slot]))) {
            changed |= static_cast<uint8_t>(1U << slot);
        }
    }
    return changed;
}

bool ArtworkRole::Impl::same_channel(const ServerArtworkChannelObject& a,
                                     const ServerArtworkChannelObject& b) {
    return a.source == b.source && a.format == b.format && a.width == b.width &&
           a.height == b.height;
}

void ArtworkRole::Impl::handle_stream_end() {
    const uint32_t generation = this->cleanup_generation.load(std::memory_order_acquire);
    this->stream_active = false;
    this->discard_all_pending();
    // The decode thread drops every channel's parked or half-assembled image when it reaches the
    // marker; the main loop's STREAM_END clears what is parked behind a gate and the channels.
    this->hand_marker(ArtworkItemType::DISCARD, ARTWORK_ALL_CHANNELS, generation);

    this->enqueue_stream_event(ArtworkEventType::STREAM_END, generation);
}

void ArtworkRole::Impl::enqueue_stream_event(ArtworkEventType event, uint32_t generation) const {
    push_event_or_log(this->inbox, InboxEventType::ARTWORK_STREAM, static_cast<uint8_t>(event), TAG,
                      "STREAM_END", generation);
}

// ============================================================================
// Event dispatch (main thread) - lifecycle via the ring, scheduled displays via polling
// ============================================================================

void ArtworkRole::Impl::handle_stream_ring_event(ArtworkEventType event) {
    switch (event) {
        case ArtworkEventType::STREAM_END:
            this->clear_every_channel();
            break;
    }
}

void ArtworkRole::Impl::clear_every_channel() {
    // Called from the ring drain in SendspinClient::drain_inbox() before this role's
    // drain_events() runs each tick, or from the catch-up that heads it, so dropping the holds
    // here cancels every display that predates the end. A display still in display_slot is left to
    // its slot epoch, which the end bumped (discard_all_pending()): one decoded before it is
    // dropped by the deadline check, and one decoded after it belongs to the next stream and fires
    // after these clears.
    this->held_display_mask = 0;
    this->held_display_clear = 0;
    bool dropped_parked = false;
    {
        // A clear is itself a delivery that must be acked: it may drive a fade-out, and it
        // supersedes any un-acked frame for the slot, so exactly one frame_done() is owed
        // afterward regardless of what ack_state held before (a decode whose display will never
        // fire included). Drop any image parked behind an un-acked frame: it is superseded by the
        // clear, and the decode thread, woken below, drops it from its buffer. Released before
        // firing the callbacks below so a listener calling frame_done() from inside
        // on_image_clear() does not deadlock on this same mutex.
        std::lock_guard<std::mutex> lock(this->drain_task->slot_mutex);
        // Sweep the whole fixed-size slot_gates array (ARTWORK_MAX_SLOTS): ack_enabled() already
        // gates the PRESENTED arm to configured ack slots, and clearing has_parked on any others
        // is a harmless reset (they never park).
        for (size_t i = 0; i < ARTWORK_MAX_SLOTS; ++i) {
            auto& gate = this->drain_task->slot_gates[i];
            dropped_parked = dropped_parked || gate.has_parked;
            gate.has_parked = false;
            if (this->ack_enabled(static_cast<uint8_t>(i))) {
                gate.ack_state = SlotAckState::PRESENTED;
            }
        }
    }
    if (dropped_parked) {
        this->wake_drain_thread();
    }
    if (this->listener) {
        // Array index is the authoritative slot number; see the Impl constructor.
        for (size_t i = 0; i < this->config.preferred_formats.size(); ++i) {
            this->listener->on_image_clear(static_cast<uint8_t>(i));
        }
    }
}

void ArtworkRole::Impl::drain_events() {
    // Fold any newly published display update into the main-thread holds. Latest-wins per
    // artwork slot: a bit set in valid_mask means
    // timestamps[i] is a fresher pending display than whatever (if anything) slot i already
    // held. A stream end or teardown drained this tick has already run clear_every_channel()
    // before this call, so it has already cleared held_display_mask before we get here.
    ArtworkDisplayUpdate update{};
    bool have_update = false;
    const uint32_t generation = take_current_payload(*this, this->event_state->display_slot, update,
                                                     have_update, TAG, "artwork displays");
    if (!this->accepts(generation)) {
        return;
    }
    if (have_update) {
        for (uint8_t slot = 0; slot < ARTWORK_MAX_SLOTS; ++slot) {
            const uint8_t bit = static_cast<uint8_t>(1U << slot);
            if (update.valid_mask & bit) {
                this->held_display_ts[slot] = update.timestamps[slot];
                this->held_display_epoch[slot] = update.epochs[slot];
                this->held_display_mask |= bit;
                // Assigned rather than OR-ed, for the same latest-wins reason as the cross-thread
                // merge: this slot's held entry has just been replaced wholesale, so the kind of
                // delivery it is must be replaced too. This mirrors
                // merge_artwork_display_update(), which is unit-tested directly
                // (ArtworkDisplayMerge); the two must stay in agreement.
                if (update.clear_mask & bit) {
                    this->held_display_clear |= bit;
                } else {
                    this->held_display_clear &= static_cast<uint8_t>(~bit);
                }
            }
        }
    }

    // No listener guard here: the epoch/deadline sweep below must still consume held bits so a
    // listener-less role does not report needs_drain() forever; only the callback itself is
    // gated on the listener.
    if (this->held_display_mask == 0) {
        return;
    }

    // Single now snapshot per tick. No lock is held here: the slot value was already taken
    // above.
    const int64_t now = platform_time_us();
    for (uint8_t slot = 0; slot < ARTWORK_MAX_SLOTS; ++slot) {
        const uint8_t bit = static_cast<uint8_t>(1U << slot);
        if (!(this->held_display_mask & bit)) {
            continue;
        }
        // Drop a display whose channel has since moved past it: the epoch bump cannot reach these
        // main-thread holds to cancel the display directly (see held_display_epoch).
        if (this->held_display_epoch[slot] !=
            this->slot_epochs[slot].load(std::memory_order_relaxed)) {
            this->held_display_mask &= static_cast<uint8_t>(~bit);
            this->held_display_clear &= static_cast<uint8_t>(~bit);
            if (this->ack_enabled(slot)) {
                bool should_wake = false;
                {
                    std::lock_guard<std::mutex> lock(this->drain_task->slot_mutex);
                    auto& gate = this->drain_task->slot_gates[slot];
                    // The consumer got a decode whose display will never fire now; release the
                    // gate so the slot does not wedge on this stream restart. PRESENTED is left
                    // untouched: a delivery that already reached on_image_display()/
                    // on_image_clear() still owes its frame_done() regardless of epoch.
                    if (gate.ack_state == SlotAckState::DECODE_DELIVERED) {
                        gate.ack_state = SlotAckState::IDLE;
                    }
                    should_wake = gate.has_parked;
                }
                if (should_wake) {
                    this->wake_drain_thread();
                }
            }
            continue;
        }
        int64_t client_ts = this->client->get_client_time(this->held_display_ts[slot]);
        int32_t display_offset_ms = slot < this->config.preferred_formats.size()
                                        ? this->config.preferred_formats[slot].display_offset_ms
                                        : 0;
        int64_t overdue_us = display_overdue_us(client_ts, display_offset_ms, now);
        if (overdue_us < 0) {
            continue;
        }
        this->held_display_mask &= static_cast<uint8_t>(~bit);
        // A per-channel clear is scheduled exactly like a frame, offset shift included, so a
        // consumer can fade out on the same lead it would have faded in on.
        const bool is_clear = (this->held_display_clear & bit) != 0;
        this->held_display_clear &= static_cast<uint8_t>(~bit);
        if (this->ack_enabled(slot)) {
            // Arm the "awaiting frame_done()" state before the callback fires and release the
            // mutex before invoking it: frame_done() may be called synchronously from inside
            // on_image_display()/on_image_clear(), which would deadlock if this mutex were still
            // held.
            std::lock_guard<std::mutex> lock(this->drain_task->slot_mutex);
            this->drain_task->slot_gates[slot].ack_state = SlotAckState::PRESENTED;
        }
        if (this->listener) {
            if (is_clear) {
                this->listener->on_image_clear(slot);
            } else {
                this->listener->on_image_display(slot, display_lateness_ms(client_ts, overdue_us));
            }
        }
        // The callback may re-enter teardown (a listener calling stop()), whose own drain already
        // dropped the holds.
        if (!this->accepts(generation)) {
            return;
        }
    }
}

void ArtworkRole::Impl::complete_teardown() {
    // A teardown ends the stream like a stream/end: the held displays are dropped and every
    // channel is cleared, ahead of anything the next connection's stream displays.
    this->clear_every_channel();
}

// ============================================================================
// Cleanup (protocol task, or the main loop in stop() once it is joined)
// ============================================================================

void ArtworkRole::Impl::cleanup() {
    // Stamps every event queued from here on, so an event queued for the stream this teardown
    // ends is discarded at the drain (see event_is_current()).
    const uint32_t generation =
        this->cleanup_generation.fetch_add(1, std::memory_order_acq_rel) + 1;
    this->stream_active = false;
    this->discard_all_pending();

    // Return the items the decode thread has not taken; one it takes before this carries the
    // earlier stamp, which its take() discards. The wake has it drop the images it parks or
    // assembles for the stream this teardown ends (see DrainTask::assembly_generation).
    this->drain_task->inbound.recall();
    this->wake_drain_thread();

    push_event_or_log(this->inbox, InboxEventType::ARTWORK_CLEARED, 0, TAG, "artwork cleared event",
                      generation);
}

// ============================================================================
// Consumer-facing methods (main thread)
// ============================================================================

void ArtworkRole::Impl::frame_done(uint8_t slot) const {
    if (slot >= ARTWORK_MAX_SLOTS) {
        return;
    }

    bool should_wake = false;
    {
        std::lock_guard<std::mutex> lock(this->drain_task->slot_mutex);
        auto& gate = this->drain_task->slot_gates[slot];
        if (gate.ack_state == SlotAckState::IDLE) {
            // Safe no-op: nothing un-acked for this slot, whether because require_frame_done is
            // disabled, the delivery was already acked, or a clear already acked it for us.
            return;
        }
        gate.ack_state = SlotAckState::IDLE;
        should_wake = gate.has_parked;
    }
    if (should_wake) {
        this->wake_drain_thread();
    }
}

// ============================================================================
// Decode thread
// ============================================================================

bool ArtworkRole::Impl::process_next_item(uint32_t timeout_ms) {
    InboundConsumer& inbound = this->drain_task->inbound;
    void* item = inbound.take(timeout_ms, this->cleanup_generation);
    if (item == nullptr) {
        return false;
    }
    const InboundItemHeader* header = inbound_item_header(item);
    this->adopt_generation(header->generation);
    // The protocol task names a channel 0-3 on every announce and part, and a mask of them on a
    // marker.
    const auto serial = static_cast<uint8_t>(header->serial);
    switch (static_cast<ArtworkItemType>(header->type)) {
        case ArtworkItemType::ANNOUNCE:
            this->begin_assembly(serial, item);
            break;
        case ArtworkItemType::PART:
            this->add_part(serial, item);
            break;
        case ArtworkItemType::DISCARD:
        case ArtworkItemType::RECONFIGURE: {
            const bool reconfigure =
                static_cast<ArtworkItemType>(header->type) == ArtworkItemType::RECONFIGURE;
            inbound.return_item(item);
            for (uint8_t slot = 0; slot < ARTWORK_MAX_SLOTS; ++slot) {
                if ((serial & (1U << slot)) != 0) {
                    this->drop_assembly(slot, reconfigure);
                }
            }
            break;
        }
    }
    return true;
}

void ArtworkRole::Impl::adopt_generation(uint32_t generation) const {
    // A teardown since the decode thread last took an item: what it parks and assembles belongs
    // to the stream that teardown ended.
    if (generation == this->drain_task->assembly_generation) {
        return;
    }
    for (uint8_t slot = 0; slot < ARTWORK_MAX_SLOTS; ++slot) {
        this->drop_assembly(slot);
    }
    this->drain_task->assembly_generation = generation;
}

void ArtworkRole::Impl::begin_assembly(uint8_t slot, void* item) {
    // roles/artwork/v1.md "Artwork (Binary)": "An announce discards that channel's pending
    // image." A channel has one current image and at most one pending one, and only the pending
    // one can be in the buffer (a READY image, or one still assembling), so the new image takes
    // the buffer over.
    this->drop_assembly(slot);

    const InboundItemHeader* header = inbound_item_header(item);
    ArtworkAnnounce announce;
    std::memcpy(&announce, inbound_item_data(item), sizeof(announce));
    ArtworkAssembly& assembly = this->drain_task->assemblies[slot];
    assembly.timestamp = announce.timestamp;
    assembly.total_size = announce.total_size;
    assembly.received = 0;
    assembly.epoch = announce.epoch;
    assembly.generation = header->generation;
    this->drain_task->inbound.return_item(item);

    assembly.state = ArtworkAssembly::State::ASSEMBLING;
    // An empty image is complete at its announce.
    if (announce.total_size == 0) {
        this->deliver(slot);
    }
}

void ArtworkRole::Impl::add_part(uint8_t slot, void* item) {
    ArtworkAssembly& assembly = this->drain_task->assemblies[slot];
    // Copied out whatever the channel's gate says, and the item returned at once: a complete
    // image whose gate is closed parks in the buffer (deliver()).
    if (assembly.state == ArtworkAssembly::State::ASSEMBLING) {
        // The protocol task bounds every part by the announced total_size, which it bounds by
        // the channel's max_image_bytes, the buffer's size.
        const uint32_t len = inbound_item_header(item)->data_len;
        std::memcpy(assembly.buffer.data() + assembly.received, inbound_item_data(item), len);
        assembly.received += len;
    }
    // Otherwise no image is in progress on the channel: a marker or a newer announce dropped the
    // one this part belonged to, and the part is returned unused.
    this->drain_task->inbound.return_item(item);
    if (assembly.state == ArtworkAssembly::State::ASSEMBLING &&
        assembly.received == assembly.total_size) {
        this->deliver(slot);
    }
}

void ArtworkRole::Impl::drop_assembly(uint8_t slot, bool release_delivered) const {
    this->drain_task->assemblies[slot].state = ArtworkAssembly::State::IDLE;
    std::lock_guard<std::mutex> lock(this->drain_task->slot_mutex);
    auto& gate = this->drain_task->slot_gates[slot];
    gate.has_parked = false;
    if (release_delivered && gate.ack_state == SlotAckState::DECODE_DELIVERED) {
        gate.ack_state = SlotAckState::IDLE;
    }
}

void ArtworkRole::Impl::deliver(uint8_t slot) {
    ArtworkAssembly& assembly = this->drain_task->assemblies[slot];
    // The channel moved past this image after its announce (a cancel, a newer announce, a stream
    // restart or end) and the marker that says so is still behind it on the list: dropped
    // undecoded, as it would have been had the marker come first.
    if (assembly.epoch != this->slot_epochs[slot].load(std::memory_order_relaxed)) {
        assembly.state = ArtworkAssembly::State::IDLE;
        return;
    }
    {
        // Ack gate: a slot with require_frame_done set allows only one un-acked delivery in
        // flight. If one is outstanding the complete image parks here (READY), latest-wins,
        // until the gate reopens; otherwise the gate is armed (DECODE_DELIVERED) before the
        // decode, so a later image for this slot parks rather than delivering behind this
        // un-acked one. Arming gates on ack_enabled() alone, matching drain_events() and
        // clear_every_channel(); the listener is set before start() (see set_listener), and the
        // callback below is the crash-guard for that pointer.
        std::lock_guard<std::mutex> lock(this->drain_task->slot_mutex);
        auto& gate = this->drain_task->slot_gates[slot];
        if (this->ack_enabled(slot)) {
            if (gate.ack_state != SlotAckState::IDLE) {
                assembly.state = ArtworkAssembly::State::READY;
                gate.has_parked = true;
                return;
            }
            gate.ack_state = SlotAckState::DECODE_DELIVERED;
        }
    }
    assembly.state = ArtworkAssembly::State::IDLE;

    // A per-channel clear (an empty image) has nothing to decode. Everything else is deliberately
    // shared with a frame: the same slot-epoch staleness check, the same ack gate (a clear is a
    // delivery owing exactly one frame_done()), and the same timestamp-scheduled hand-off to the
    // main loop, which fires on_image_clear() rather than on_image_display() at the deadline.
    const bool is_clear = assembly.total_size == 0;
    if (!is_clear && this->listener) {
        this->listener->on_image_decode(slot, assembly.buffer.data(), assembly.total_size,
                                        this->image_format(slot));
    }

    // Hand off the timestamp to the main loop. Skip if the stream ended while we were
    // decoding so the main loop doesn't fire a display after on_image_clear. The delta
    // carries just this slot's bit; merge_artwork_display_update ORs it into whatever the
    // main loop hasn't drained out of display_slot yet.
    if (this->stream_active.load(std::memory_order_acquire)) {
        ArtworkDisplayUpdate delta{};
        delta.timestamps[slot] = assembly.timestamp;
        // The epoch this decode was validated under: lets the main-loop deadline check drop
        // the display if the stream is replaced after this hand-off (see held_display_epoch).
        delta.epochs[slot] = assembly.epoch;
        delta.valid_mask = static_cast<uint8_t>(1U << slot);
        if (is_clear) {
            delta.clear_mask = static_cast<uint8_t>(1U << slot);
        }
        // NOLINTNEXTLINE(performance-move-const-arg): merge() takes the delta as T&&
        this->event_state->display_slot.merge(merge_artwork_display_update, std::move(delta),
                                              assembly.generation);
    }
}

void ArtworkRole::Impl::sweep_parked() {
    // Every gate that reopens (frame_done() or an epoch-mismatch release in drain_events()) and
    // every park the main loop drops (a stream end's clear) wakes this thread, so one pass per
    // wake finds them all.
    for (uint8_t slot = 0; slot < ARTWORK_MAX_SLOTS; ++slot) {
        if (this->drain_task->assemblies[slot].state != ArtworkAssembly::State::READY) {
            continue;
        }
        bool dropped = false;
        bool open = false;
        {
            std::lock_guard<std::mutex> lock(this->drain_task->slot_mutex);
            auto& gate = this->drain_task->slot_gates[slot];
            // The main loop clearing has_parked drops the parked image.
            dropped = !gate.has_parked;
            open = !dropped && gate.ack_state == SlotAckState::IDLE;
            if (open) {
                gate.has_parked = false;
            }
        }
        if (dropped) {
            this->drop_assembly(slot);
        } else if (open) {
            // Revalidated like any delivery: a since-stale epoch drops it, and a gate closed
            // again parks it once more.
            this->deliver(slot);
        }
    }
}

void ArtworkRole::Impl::drain_thread_func(ArtworkRole::Impl* self) {
    SS_LOGD(TAG, "Decode thread started");

    InboundItemList& items = self->drain_task->inbound.items();

    while (true) {
        // Non-blocking check for commands
        uint32_t cmd = items.take_signals(COMMAND_STOP, 0);
        if (cmd & COMMAND_STOP) {
            break;
        }

        // A teardown woke the thread: drop what it assembles for the stream that ended.
        self->adopt_generation(self->cleanup_generation.load(std::memory_order_acquire));
        self->sweep_parked();

        // A stop, a teardown or a parked-slot recheck ends the take early; any return re-runs the
        // checks above.
        self->process_next_item(INBOUND_CONSUMER_FALLBACK_WAKE_MS);
    }

    // A restart begins with nothing assembled or parked: stop() releases the buffers.
    for (uint8_t slot = 0; slot < ARTWORK_MAX_SLOTS; ++slot) {
        self->drop_assembly(slot);
    }
    SS_LOGD(TAG, "Decode thread stopped");
}

// ============================================================================
// ArtworkRole public API (thin forwarding)
// ============================================================================

ArtworkRole::ArtworkRole(ArtworkRoleConfig config, SendspinClient* client)
    : impl_(std::make_unique<Impl>(std::move(config), client)) {}

ArtworkRole::~ArtworkRole() = default;

void ArtworkRole::set_listener(ArtworkRoleListener* listener) {
    this->impl_->listener = listener;
}

void ArtworkRole::frame_done(uint8_t slot) {
    this->impl_->frame_done(slot);
}

}  // namespace sendspin
