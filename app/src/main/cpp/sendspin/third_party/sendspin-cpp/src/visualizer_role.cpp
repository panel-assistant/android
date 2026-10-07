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

#include "constants.h"
#include "platform/logging.h"
#include "platform/thread.h"
#include "platform/time.h"
#include "protocol_messages.h"
#include "sendspin/client.h"
#include "visualizer_role_impl.h"

#include <algorithm>
#include <cinttypes>
#include <cstring>
#include <utility>

static const char* const TAG = "sendspin.visualizer";

// ============================================================================
// Entry format constants
// ============================================================================

// Each frame stays in the inbound ring item it arrived in: its plaintext is
// [wire_type(1)][server_ts(8)][payload], the item header carries the wire type, the payload's
// offset and length, and the transport's receive stamp. buffer_capacity is the visualizer's quota
// of ring storage, not a wire-data quota: each frame also costs the ring's per-item overhead, so
// effective wire-data capacity is smaller (see the buffer_capacity note in config.h).
static constexpr size_t ENTRY_TYPE_SIZE = 1;
/// @brief Offset of a frame's payload in its plaintext
static constexpr size_t FRAME_PAYLOAD_OFFSET = ENTRY_TYPE_SIZE + sendspin::BINARY_TIMESTAMP_SIZE;

// Minimum payload bytes after the timestamp, per wire message type
static constexpr size_t LOUDNESS_PAYLOAD_SIZE = 2;  // uint16 value
static constexpr size_t BEAT_PAYLOAD_SIZE = 1;      // uint8 flags
static constexpr size_t F_PEAK_PAYLOAD_SIZE = 4;    // uint16 freq + uint16 amp
static constexpr size_t PEAK_PAYLOAD_SIZE = 1;      // uint8 strength

/// @brief Bit 0 of the beat flags byte marks a downbeat (bar start)
static constexpr uint8_t BEAT_FLAG_DOWNBEAT = 0x01;

/// @brief The smallest frame on the wire: a beat or peak, one payload byte behind the type byte and
/// the timestamp
static constexpr size_t MIN_FRAME_WIRE_BYTES = FRAME_PAYLOAD_OFFSET + BEAT_PAYLOAD_SIZE;

// buffer_capacity is the visualizer's quota of inbound ring storage, but each frame is held at
// its stored cost, at most sendspin::INBOUND_ITEM_STORED_OVERHEAD_BYTES over its wire size, so
// the smallest frames (10 wire bytes stored as 68) leave only about a seventh of the quota for
// wire data. Advertise that fraction to the server (not the raw budget) so its flow control never
// sends more than the quota holds. This mirrors the player role's derived buffer advertisement.
static constexpr size_t BUFFER_ADVERTISE_DIVISOR =
    (MIN_FRAME_WIRE_BYTES + sendspin::INBOUND_ITEM_STORED_OVERHEAD_BYTES + MIN_FRAME_WIRE_BYTES -
     1) /
    MIN_FRAME_WIRE_BYTES;

/// @brief The smallest buffer_capacity the role starts with: one that advertises at least one
/// smallest frame (MIN_FRAME_WIRE_BYTES) to the server, which also holds that frame's stored size.
static constexpr size_t MIN_BUFFER_CAPACITY = BUFFER_ADVERTISE_DIVISOR * MIN_FRAME_WIRE_BYTES;
static_assert(MIN_BUFFER_CAPACITY >=
                  sendspin::SharedRingLayout::stored_size(sizeof(sendspin::InboundItemHeader) +
                                                          MIN_FRAME_WIRE_BYTES +
                                                          sendspin::AEAD_TAG_SIZE),
              "the floor must hold one stored frame");

/// @brief Whether a message with `payload_size` bytes after its timestamp stores within the
/// advertised fraction.
static constexpr bool fits_advertised_fraction(size_t payload_size) {
    const size_t wire_size = FRAME_PAYLOAD_OFFSET + payload_size;
    return BUFFER_ADVERTISE_DIVISOR * wire_size >=
           sendspin::SharedRingLayout::stored_size(sizeof(sendspin::InboundItemHeader) + wire_size +
                                                   sendspin::AEAD_TAG_SIZE);
}
static_assert(fits_advertised_fraction(BEAT_PAYLOAD_SIZE) &&
                  fits_advertised_fraction(PEAK_PAYLOAD_SIZE) &&
                  fits_advertised_fraction(LOUDNESS_PAYLOAD_SIZE) &&
                  fits_advertised_fraction(F_PEAK_PAYLOAD_SIZE),
              "the advertised buffer_capacity would exceed the visualizer's quota");

// Drain thread command bits, signalled on its item list's flags (InboundItemList::signal())
static constexpr uint32_t COMMAND_STOP = sendspin::InboundItemList::FIRST_CONSUMER_BIT;
// boundary_sequence moved on (signal_boundary()).
static constexpr uint32_t COMMAND_BOUNDARY = sendspin::InboundItemList::FIRST_CONSUMER_BIT << 1;
static_assert(COMMAND_BOUNDARY <= sendspin::InboundItemList::LAST_CONSUMER_BIT,
              "the drain thread's command bits must fit the list's usable event bits");

// ============================================================================
// Big-endian helpers
// ============================================================================

static uint16_t read_be16(const uint8_t* p) {
    return static_cast<uint16_t>(p[0]) << 8 | static_cast<uint16_t>(p[1]);
}

/// @brief Maps a negotiated data type to its binary wire-type byte
static uint8_t wire_type_for(sendspin::VisualizerDataType type) {
    switch (type) {
        case sendspin::VisualizerDataType::LOUDNESS:
            return sendspin::SENDSPIN_BINARY_VISUALIZER_LOUDNESS;
        case sendspin::VisualizerDataType::BEAT:
            return sendspin::SENDSPIN_BINARY_VISUALIZER_BEAT;
        case sendspin::VisualizerDataType::F_PEAK:
            return sendspin::SENDSPIN_BINARY_VISUALIZER_F_PEAK;
        case sendspin::VisualizerDataType::SPECTRUM:
            return sendspin::SENDSPIN_BINARY_VISUALIZER_SPECTRUM;
        case sendspin::VisualizerDataType::PEAK:
            return sendspin::SENDSPIN_BINARY_VISUALIZER_PEAK;
    }
    return 0;
}

namespace sendspin {

// ============================================================================
// Impl constructor / destructor
// ============================================================================

VisualizerRole::Impl::Impl(VisualizerRoleConfig config, SendspinClient* client)
    : config(std::move(config)),
      visualizer_support(this->config.support),
      client(client),
      event_state(std::make_unique<EventState>()) {
    this->drain_task = std::make_unique<DrainTask>();
}

VisualizerRole::Impl::~Impl() {
    this->stop();
}

// ============================================================================
// VisualizerRole forwarding (public API → Impl)
// ============================================================================

VisualizerRole::VisualizerRole(VisualizerRoleConfig config, SendspinClient* client)
    : impl_(std::make_unique<Impl>(std::move(config), client)) {}

VisualizerRole::~VisualizerRole() = default;

void VisualizerRole::set_listener(VisualizerRoleListener* listener) {
    this->impl_->listener = listener;
}

// ============================================================================
// Impl method implementations
// ============================================================================

void VisualizerRole::Impl::attach_inbox(Inbox& inbox) {
    this->inbox = &inbox;
    this->event_state->config_slot.bind(inbox, INBOX_TOPIC_VISUALIZER_CONFIG);
}

bool VisualizerRole::Impl::start(InboundRing* ring) {
    // roles/visualizer/v1.md "client/state visualizer object": rate_max is a positive integer,
    // and a types list containing 'spectrum' without a spectrum object is a protocol error the
    // server SHOULD close the connection for. The configuration is reported as given, so a
    // config the spec forbids refuses to start here rather than being discovered as an
    // unexplained disconnect, the same posture the player takes on its format list.
    const VisualizerStreamConfig& stream = this->config.stream;
    if (stream.rate_max == 0 && !stream.types.empty()) {
        SS_LOGE(TAG, "VisualizerStreamConfig::rate_max must be positive while types are requested");
        return false;
    }
    // A quota that advertises less than one frame (or cannot store one) drops every frame;
    // refuse it as the configuration error it is.
    if (this->visualizer_support.buffer_capacity < MIN_BUFFER_CAPACITY) {
        SS_LOGE(TAG, "VisualizerSupportObject::buffer_capacity must be at least %zu bytes",
                MIN_BUFFER_CAPACITY);
        return false;
    }
    if (!stream.spectrum.has_value() &&
        std::find(stream.types.begin(), stream.types.end(), VisualizerDataType::SPECTRUM) !=
            stream.types.end()) {
        SS_LOGE(TAG, "VisualizerStreamConfig::spectrum is required to request the spectrum type");
        return false;
    }
    if (this->drain_task->drain_thread.joinable()) {
        return true;  // Already running
    }
    if (!this->drain_task->inbound.bind(ring, InboundHolder::VISUALIZER)) {
        SS_LOGE(TAG, "Failed to create the visualizer item list");
        return false;
    }

    // So a restart inherits no command from the previous run.
    this->drain_task->inbound.items().clear_signals();

    platform_configure_thread("SsVis", 4096, static_cast<int>(this->config.priority),
                              this->config.psram_stack);
    this->drain_task->drain_thread = std::thread(drain_thread_func, this);
    return true;
}

bool VisualizerRole::Impl::signal_stop() const {
    if (!this->drain_task || !this->drain_task->drain_thread.joinable()) {
        return false;
    }
    this->drain_task->inbound.items().signal(COMMAND_STOP);
    return true;
}

void VisualizerRole::Impl::stop() const {
    if (!this->signal_stop()) {
        return;
    }
    this->drain_task->drain_thread.join();
    this->drain_task->inbound.unbind();
}

size_t VisualizerRole::Impl::stored_frame_bytes_per_second() const {
    // roles/visualizer/v1.md "client/state visualizer object": rate_max caps the frames per second
    // the server sends of each periodic type (loudness, f_peak, spectrum). Beat and peak frames
    // are events the spec does not throttle; they are budgeted at the same rate, an assumption
    // (onsets and beats come at most a few times a second), not a bound.
    const VisualizerStreamConfig& stream = this->config.stream;
    size_t per_round = 0;
    for (const VisualizerDataType type : stream.types) {
        size_t payload = 0;
        switch (type) {
            case VisualizerDataType::LOUDNESS:
                payload = LOUDNESS_PAYLOAD_SIZE;
                break;
            case VisualizerDataType::BEAT:
                payload = BEAT_PAYLOAD_SIZE;
                break;
            case VisualizerDataType::F_PEAK:
                payload = F_PEAK_PAYLOAD_SIZE;
                break;
            case VisualizerDataType::PEAK:
                payload = PEAK_PAYLOAD_SIZE;
                break;
            case VisualizerDataType::SPECTRUM:
                payload = stream.spectrum.has_value()
                              ? sizeof(uint16_t) * stream.spectrum->n_disp_bins
                              : 0;
                break;
        }
        per_round += SharedRingLayout::stored_size(sizeof(InboundItemHeader) +
                                                   FRAME_PAYLOAD_OFFSET + payload + AEAD_TAG_SIZE);
    }
    return per_round * stream.rate_max;
}

void VisualizerRole::Impl::build_hello_fields(ClientHelloMessage& msg) const {
    msg.supported_roles.push_back(SendspinRole::VISUALIZER);
    // Advertise the effective wire-data capacity, not the raw budget: the quota is
    // buffer_capacity bytes of ring storage, but per-item overhead leaves only a fraction of it
    // for wire data (see BUFFER_ADVERTISE_DIVISOR). The quota itself is the full value.
    VisualizerSupportObject advertised = this->visualizer_support;
    advertised.buffer_capacity = this->advertised_buffer_capacity();
    msg.visualizer_support = advertised;
}

size_t VisualizerRole::Impl::advertised_buffer_capacity() const {
    // Unlike the player's, never capped at the ring's largest item, since it never reaches it:
    // the ring holds the whole quota (derive_inbound_ring_bytes()), so its largest item, half the
    // storage, is over half the quota, while this is a seventh of it.
    return this->visualizer_support.buffer_capacity / BUFFER_ADVERTISE_DIVISOR;
}

void VisualizerRole::Impl::build_state_fields(ClientStateMessage& msg) const {
    ClientVisualizerStateObject visualizer_state{};
    visualizer_state.types = this->config.stream.types;
    visualizer_state.rate_max = this->config.stream.rate_max;
    visualizer_state.spectrum = this->config.stream.spectrum;
    msg.visualizer = std::move(visualizer_state);
}

// ============================================================================
// Binary handling (protocol task)
// ============================================================================

void VisualizerRole::Impl::handle_binary(uint8_t binary_type, InboundMessage& message) {
    const uint32_t generation = this->cleanup_generation.load(std::memory_order_acquire);
    InboundConsumer& inbound = this->drain_task->inbound;
    if (!this->stream_active || inbound.ring() == nullptr) {
        return;
    }

    // Admit only wire types the active stream negotiated in stream/start. The mask is written by
    // handle_stream_start on this same thread, so a message is always judged against the config
    // in force when it arrived. The caller guarantees binary_type is in the visualizer range.
    uint8_t type_bit = 1U << (binary_type - SENDSPIN_BINARY_VISUALIZER_FIRST);
    if ((this->negotiated_types_mask & type_bit) == 0) {
        return;
    }

    // Of the length, only the timestamp's is checked here; the drain thread checks each type's
    // payload length (decode_visualizer_message()).
    if (message.len < FRAME_PAYLOAD_OFFSET) {
        inbound.note_drop(InboundConsumer::DropReason::TOO_SHORT);
        return;
    }
    (void)inbound.hand_message(
        message,
        {.data_len = static_cast<uint32_t>(message.len - FRAME_PAYLOAD_OFFSET),
         .serial = this->boundary_sequence.load(std::memory_order_relaxed),
         .type = binary_type,
         .data_offset = static_cast<uint8_t>(FRAME_PAYLOAD_OFFSET)},
        generation);
}

// ============================================================================
// Stream lifecycle (protocol task)
// ============================================================================

void VisualizerRole::Impl::handle_stream_start(const ServerVisualizerStreamObject& stream) {
    const uint32_t generation = this->cleanup_generation.load(std::memory_order_acquire);
    // Cache stream config for handle_binary (same thread) and the drain thread
    uint8_t bin_count = 0;
    uint8_t types_mask = 0;
    bool has_spectrum = false;
    for (auto type : stream.types) {
        if (type == VisualizerDataType::SPECTRUM) {
            has_spectrum = true;
        }
        types_mask |= 1U << (wire_type_for(type) - SENDSPIN_BINARY_VISUALIZER_FIRST);
    }
    if (has_spectrum) {
        // roles/visualizer/v1.md "Server -> Client: stream/start": the spectrum object is present
        // when types includes 'spectrum' and MUST match the requested configuration. The object is
        // reported to the listener as the server sent it, so a mismatch is logged, not rejected.
        const std::optional<VisualizerSpectrumConfig>& requested = this->config.stream.spectrum;
        if (!stream.spectrum.has_value()) {
            SS_LOGW(TAG, "Visualizer stream/start requests the spectrum type with no spectrum "
                         "object; spectrum frames will be dropped");
        } else if (requested.has_value()) {
            const VisualizerSpectrumConfig& srv = stream.spectrum.value();
            if (srv.n_disp_bins != requested->n_disp_bins) {
                SS_LOGW(TAG, "Spectrum bin count mismatch: server %" PRIu8 ", expected %" PRIu8,
                        srv.n_disp_bins, requested->n_disp_bins);
            }
            if (srv.scale != requested->scale) {
                SS_LOGW(TAG, "Spectrum scale mismatch");
            }
            if (srv.f_min != requested->f_min || srv.f_max != requested->f_max) {
                SS_LOGW(TAG,
                        "Spectrum frequency range mismatch: server %" PRIu16 "-%" PRIu16
                        ", expected %" PRIu16 "-%" PRIu16,
                        srv.f_min, srv.f_max, requested->f_min, requested->f_max);
            }
        }
    }
    if (has_spectrum && stream.spectrum.has_value()) {
        bin_count = stream.spectrum->n_disp_bins;
    }
    this->spectrum_bin_count = bin_count;
    this->tracks_downbeats = stream.tracks_downbeats;
    this->negotiated_types_mask = types_mask;
    this->stream_active = true;

    // After the config writes, so a frame stamped with the new sequence decodes under them.
    this->signal_boundary();

    // Write the config to the inbox slot for the main thread, then push the event. Both lock the
    // same shared Inbox mutex, in this order, so a consumer that later takes the START event is
    // guaranteed to observe this config (see config_slot.take() in handle_stream_ring_event()).
    // Both carry `generation`, so a config left over from a torn-down stream is never applied.
    this->event_state->config_slot.write(stream, generation);
    this->enqueue_stream_event(VisualizerEventType::STREAM_START, generation);
}

void VisualizerRole::Impl::handle_stream_end() {
    const uint32_t generation = this->cleanup_generation.load(std::memory_order_acquire);
    this->stream_active = false;
    this->negotiated_types_mask = 0;

    // roles/visualizer/v1.md "stream/end": every listed frame is now stale.
    this->signal_boundary();

    this->enqueue_stream_event(VisualizerEventType::STREAM_END, generation);
}

void VisualizerRole::Impl::handle_stream_clear() {
    const uint32_t generation = this->cleanup_generation.load(std::memory_order_acquire);
    // messaging.md "stream/clear": buffered frames are discarded, later ones still flow.
    this->signal_boundary();

    this->enqueue_stream_event(VisualizerEventType::STREAM_CLEAR, generation);
}

void VisualizerRole::Impl::enqueue_stream_event(VisualizerEventType event,
                                                uint32_t generation) const {
    const char* name = "STREAM_CLEAR";
    if (event == VisualizerEventType::STREAM_START) {
        name = "STREAM_START";
    } else if (event == VisualizerEventType::STREAM_END) {
        name = "STREAM_END";
    }
    push_event_or_log(this->inbox, InboxEventType::VISUALIZER_STREAM, static_cast<uint8_t>(event),
                      TAG, name, generation);
}

// ============================================================================
// Event dispatch (main thread) - lifecycle events only, called from the ring drain in
// SendspinClient::drain_inbox()
// ============================================================================

void VisualizerRole::Impl::handle_stream_ring_event(VisualizerEventType event,
                                                    uint32_t generation) const {
    switch (event) {
        case VisualizerEventType::STREAM_START: {
            ServerVisualizerStreamObject config{};
            uint32_t stamp = 0;
            if (!this->event_state->config_slot.take(config, stamp)) {
                break;
            }
            if (stamp != generation) {
                SS_LOGD(TAG, "Dropping a visualizer config queued before the role was torn down");
                break;
            }
            if (this->listener) {
                this->listener->on_visualizer_stream_start(config);
            }
            break;
        }
        case VisualizerEventType::STREAM_END:
            if (this->listener) {
                this->listener->on_visualizer_stream_end();
            }
            break;
        case VisualizerEventType::STREAM_CLEAR:
            if (this->listener) {
                this->listener->on_visualizer_stream_clear();
            }
            break;
    }
}

// ============================================================================
// Cleanup (protocol task, or the main loop in stop() once it is joined)
// ============================================================================

void VisualizerRole::Impl::cleanup() {
    // Stamps every event queued from here on, so an event queued for the stream this teardown
    // ends is discarded (see cleanup_generation).
    const uint32_t generation =
        this->cleanup_generation.fetch_add(1, std::memory_order_acq_rel) + 1;
    this->stream_active = false;
    this->negotiated_types_mask = 0;

    // Returns the frames the drain thread has not taken (one it takes before this carries the
    // earlier stamp, which its take() discards) and a frame it holds.
    this->signal_boundary();

    // Discard stale slot content. Stale ring-borne events (an in-flight
    // STREAM_START/STREAM_END/STREAM_CLEAR queued before this teardown) need no per-event ring
    // reset either way: on the connection-loss path
    // SendspinClient::cleanup_connection_state()'s inbox.reset_events() has already wiped them,
    // and on the deactivation path, which leaves the ring alone for the roles that stay active,
    // they carry the generation this teardown just left behind and the drain discards them (see
    // event_is_current()).
    this->event_state->config_slot.reset();

    push_event_or_log(this->inbox, InboxEventType::VISUALIZER_CLEARED, 0, TAG,
                      "visualizer cleared event", generation);
}

void VisualizerRole::Impl::complete_teardown() const {
    if (this->listener) {
        this->listener->on_visualizer_stream_end();
    }
}

// ============================================================================
// Drain thread helpers
// ============================================================================

std::optional<int64_t> visualizer_delivery_wait_us(int64_t client_ts, int64_t arrival_us,
                                                   int32_t display_offset_ms, int64_t now) {
    // A frame that arrived in time and only waited behind others sharing its timestamp is not
    // stale.
    if (client_ts < arrival_us) {
        return std::nullopt;
    }
    const int64_t deliver_at_us = client_ts - static_cast<int64_t>(display_offset_ms) * US_PER_MS;
    if (now - std::max(deliver_at_us, arrival_us) > VISUALIZER_MAX_DELIVERY_LAG_US) {
        return std::nullopt;
    }
    return std::max<int64_t>(deliver_at_us - now, 0);
}

VisualizerDelivery decode_visualizer_message(uint8_t wire_type, const uint8_t* payload,
                                             size_t payload_len, uint8_t configured_bins,
                                             bool tracks_downbeats,
                                             std::vector<uint16_t>& spectrum_out) {
    VisualizerDelivery out;
    switch (wire_type) {
        case SENDSPIN_BINARY_VISUALIZER_LOUDNESS:
            if (payload_len < LOUDNESS_PAYLOAD_SIZE) {
                break;
            }
            out.kind = VisualizerDelivery::Kind::LOUDNESS;
            out.loudness = read_be16(payload);
            break;
        case SENDSPIN_BINARY_VISUALIZER_BEAT:
            if (payload_len < BEAT_PAYLOAD_SIZE) {
                break;
            }
            out.kind = VisualizerDelivery::Kind::BEAT;
            // Bit 0 is only meaningful when the stream tracks downbeats
            out.downbeat = tracks_downbeats && (payload[0] & BEAT_FLAG_DOWNBEAT) != 0;
            break;
        case SENDSPIN_BINARY_VISUALIZER_F_PEAK:
            if (payload_len < F_PEAK_PAYLOAD_SIZE) {
                break;
            }
            out.kind = VisualizerDelivery::Kind::F_PEAK;
            out.frequency_hz = read_be16(payload);
            out.amplitude = read_be16(payload + 2);
            break;
        case SENDSPIN_BINARY_VISUALIZER_SPECTRUM:
            // Deliver exactly the negotiated n_disp_bins. Drop the frame if SPECTRUM was not
            // negotiated (bin count 0) or the payload is short; ignore any trailing bytes.
            if (configured_bins == 0 || payload_len < static_cast<size_t>(configured_bins) * 2) {
                break;
            }
            spectrum_out.resize(configured_bins);
            for (uint8_t b = 0; b < configured_bins; ++b) {
                spectrum_out[b] = read_be16(payload + static_cast<size_t>(b) * 2);
            }
            out.kind = VisualizerDelivery::Kind::SPECTRUM;
            break;
        case SENDSPIN_BINARY_VISUALIZER_PEAK:
            if (payload_len < PEAK_PAYLOAD_SIZE) {
                break;
            }
            out.kind = VisualizerDelivery::Kind::PEAK;
            out.strength = payload[0];
            break;
        default:
            break;  // Reserved types 21-23
    }
    return out;
}

void VisualizerRole::Impl::signal_boundary() {
    // Every listed frame predates the boundary, since the protocol task is the only appender.
    InboundConsumer& inbound = this->drain_task->inbound;
    inbound.recall();
    // Release pairs with is_stale()'s acquire, publishing handle_stream_start()'s config; the
    // signal follows the store so the woken thread reads the new value.
    this->boundary_sequence.store(
        static_cast<uint16_t>(this->boundary_sequence.load(std::memory_order_relaxed) + 1),
        std::memory_order_release);
    if (inbound.ring() != nullptr) {
        inbound.items().signal(COMMAND_BOUNDARY);
    }
}

bool VisualizerRole::Impl::is_stale(void* item) const {
    return inbound_item_header(item)->serial !=
           this->boundary_sequence.load(std::memory_order_acquire);
}

void VisualizerRole::Impl::drain_thread_func(VisualizerRole::Impl* self) {
    SS_LOGD(TAG, "Drain thread started");

    // Bound by start() before this thread exists and unbound by stop() only after it is joined.
    InboundRing& ring = *self->drain_task->inbound.ring();
    InboundItemList& items = self->drain_task->inbound.items();
    const int32_t offset_ms = self->config.display_offset_ms;

    // Reused across iterations to avoid a heap alloc/free per frame. The vector's capacity
    // grows to the largest bin count seen and is resized (not reallocated) after that.
    std::vector<uint16_t> spectrum_bins;

    // Returns the ring item exactly once: a skip leaves it to the destructor, and delivery
    // releases it before the listener callback so a slow callback never holds ring space.
    struct ItemGuard {
        InboundRing& ring;
        void* item = nullptr;
        bool released = false;
        void release() {
            if (!this->released) {
                this->ring.return_item(this->item);
                this->released = true;
            }
        }
        ~ItemGuard() {
            this->release();
        }
    };

    while (true) {
        uint32_t cmd = items.take_signals(COMMAND_STOP | COMMAND_BOUNDARY, 0);
        if (cmd & COMMAND_STOP) {
            break;
        }

        // A command ends the take early with nullptr.
        void* item = self->drain_task->inbound.take(INBOUND_CONSUMER_FALLBACK_WAKE_MS,
                                                    self->cleanup_generation);
        if (item == nullptr) {
            continue;
        }
        ItemGuard guard{ring, item};
        const InboundItemHeader* header = inbound_item_header(item);

        // Waiting for time sync.
        if (!self->client->is_time_synced()) {
            continue;
        }

        const uint8_t wire_type = header->type;
        const int64_t server_ts = be64_to_host(inbound_item_bytes(item) + ENTRY_TYPE_SIZE);
        int64_t client_ts = self->client->get_client_time(server_ts);

        if (client_ts == 0) {
            continue;
        }

        const int64_t now = platform_time_us();
        const std::optional<int64_t> wait_us = visualizer_delivery_wait_us(
            client_ts, widen_time_stamp_us(header->receive_time_us, now), offset_ms, now);
        if (!wait_us.has_value()) {
            continue;
        }

        // Sleep until delivery time. A boundary that left the held frame current resumes the wait:
        // nothing listed behind it can be stale either.
        const int64_t deliver_at_us = now + *wait_us;
        bool stop = false;
        for (int64_t left_us = *wait_us; left_us >= US_PER_MS;
             left_us = deliver_at_us - platform_time_us()) {
            const auto wait_ms =
                static_cast<uint32_t>(std::min<int64_t>(left_us / US_PER_MS, UINT32_MAX));
            cmd = items.take_signals(COMMAND_STOP | COMMAND_BOUNDARY, wait_ms);
            stop = (cmd & COMMAND_STOP) != 0;
            if (stop || (cmd & COMMAND_BOUNDARY) == 0 || self->is_stale(item)) {
                break;
            }
        }
        if (stop) {
            break;
        }
        // Also catches a boundary the wait missed; one landing after this check still reaches
        // the decode.
        if (self->is_stale(item)) {
            continue;
        }

        if (self->listener == nullptr) {
            continue;
        }

        // Decode and deliver. The protocol task hands messages over verbatim, so decode validates
        // each payload's length before reading. Decode out of the item, release it via the
        // guard, then deliver, so a slow listener callback never holds ring space.
        VisualizerDelivery out = decode_visualizer_message(
            wire_type, inbound_item_data(item), header->data_len, self->spectrum_bin_count,
            self->tracks_downbeats, spectrum_bins);
        guard.release();

        switch (out.kind) {
            case VisualizerDelivery::Kind::LOUDNESS:
                self->listener->on_loudness(client_ts, out.loudness);
                break;
            case VisualizerDelivery::Kind::BEAT:
                self->listener->on_beat(client_ts, out.downbeat);
                break;
            case VisualizerDelivery::Kind::F_PEAK:
                self->listener->on_f_peak(client_ts, out.frequency_hz, out.amplitude);
                break;
            case VisualizerDelivery::Kind::SPECTRUM:
                self->listener->on_spectrum(client_ts, spectrum_bins);
                break;
            case VisualizerDelivery::Kind::PEAK:
                self->listener->on_peak(client_ts, out.strength);
                break;
            case VisualizerDelivery::Kind::NONE:
                break;
        }
    }

    SS_LOGD(TAG, "Drain thread stopped");
}

}  // namespace sendspin
