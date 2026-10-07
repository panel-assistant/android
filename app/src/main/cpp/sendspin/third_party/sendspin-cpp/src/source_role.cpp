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

#include "connection.h"
#include "platform/json_arena.h"
#include "platform/logging.h"
#include "protocol_messages.h"
#include "sendspin/client.h"
#include "source_role_impl.h"
#include "time_filter.h"

#include <algorithm>
#include <cinttypes>
#include <iterator>

static const char* const TAG = "sendspin.source";

// Whether this build has the Opus encoder; an opus config is refused otherwise
#ifdef SENDSPIN_ENABLE_OPUS
static constexpr bool OPUS_ENCODER_ENABLED = true;
#else
static constexpr bool OPUS_ENCODER_ENABLED = false;
#endif

namespace sendspin {

/// @brief The Opus-only format rules: 16-bit capture (the encoder consumes int16), a chunk that
/// is exactly one legal Opus frame, and libopus's sample rates, channel counts, and bitrate and
/// complexity ranges
static bool validate_opus_config(const SourceRoleConfig& config) {
    static constexpr uint32_t OPUS_SAMPLE_RATES[] = {8000, 12000, 16000, 24000, 48000};
    // Opus frame durations within the chunk bounds of roles/source/v1.md "Source Audio Chunks
    // (Binary)", up to the 60 ms whose packet at MAX_OPUS_BITRATE still fits
    // OpusSourceEncoder::MAX_PACKET_BYTES (libopus also takes 80, 100, and 120 ms)
    static constexpr uint32_t OPUS_CHUNK_DURATIONS_MS[] = {5, 10, 20, 40, 60};
    const auto contains = [](const auto& values, uint32_t value) {
        return std::find(std::begin(values), std::end(values), value) != std::end(values);
    };
    bool valid = true;
    if (!OPUS_ENCODER_ENABLED) {
        SS_LOGE(TAG, "Rejecting source config: opus is not compiled in (SENDSPIN_ENABLE_OPUS)");
        valid = false;
    }
    if (!contains(OPUS_SAMPLE_RATES, config.sample_rate)) {
        SS_LOGE(TAG,
                "Rejecting source config: opus sample_rate must be 8000, 12000, 16000, 24000, or "
                "48000 (got %" PRIu32 ")",
                config.sample_rate);
        valid = false;
    }
    if (config.channels != 1 && config.channels != 2) {
        SS_LOGE(TAG, "Rejecting source config: opus channels must be 1 or 2 (got %u)",
                config.channels);
        valid = false;
    }
    if (config.bit_depth != 16) {
        SS_LOGE(TAG, "Rejecting source config: opus bit_depth must be 16 (got %u)",
                config.bit_depth);
        valid = false;
    }
    if (!contains(OPUS_CHUNK_DURATIONS_MS, config.chunk_duration_ms)) {
        SS_LOGE(TAG,
                "Rejecting source config: opus chunk_duration_ms must be 5, 10, 20, 40, or 60 "
                "(got %" PRIu32 ")",
                config.chunk_duration_ms);
        valid = false;
    }
    if (config.opus_bitrate < SourceRoleConfig::MIN_OPUS_BITRATE ||
        config.opus_bitrate > SourceRoleConfig::MAX_OPUS_BITRATE) {
        SS_LOGE(TAG,
                "Rejecting source config: opus_bitrate %" PRIu32 " outside [%" PRIu32 ", %" PRIu32
                "]",
                config.opus_bitrate, SourceRoleConfig::MIN_OPUS_BITRATE,
                SourceRoleConfig::MAX_OPUS_BITRATE);
        valid = false;
    }
    if (config.opus_complexity > SourceRoleConfig::MAX_OPUS_COMPLEXITY) {
        SS_LOGE(TAG, "Rejecting source config: opus_complexity %u exceeds %u",
                config.opus_complexity, SourceRoleConfig::MAX_OPUS_COMPLEXITY);
        valid = false;
    }
    return valid;
}

// ============================================================================
// Impl constructor / destructor
// ============================================================================

SourceRole::Impl::Impl(SourceRoleConfig config, SendspinClient* client)
    : config(config),
      client(client),
      task(std::make_unique<SourceTask>(this)),
      config_valid(validate_config(config)) {}

SourceRole::Impl::~Impl() {
    // Joined first, while the role it reads its config and gate through is still whole
    this->task.reset();
}

bool SourceRole::Impl::validate_config(const SourceRoleConfig& config) {
    bool valid = true;
    if (config.codec == SendspinCodecFormat::OPUS) {
        valid = validate_opus_config(config);
    } else if (config.codec != SendspinCodecFormat::PCM) {
        SS_LOGE(TAG, "Rejecting source config: codec must be pcm or opus");
        valid = false;
    }
    if (config.sample_rate == 0) {
        SS_LOGE(TAG, "Rejecting source config: sample_rate must be > 0");
        valid = false;
    }
    if (config.channels == 0) {
        SS_LOGE(TAG, "Rejecting source config: channels must be > 0");
        valid = false;
    }
    if (config.bit_depth != 16 && config.bit_depth != 24 && config.bit_depth != 32) {
        SS_LOGE(TAG, "Rejecting source config: bit_depth must be 16, 24, or 32 (got %u)",
                config.bit_depth);
        valid = false;
    }
    if (config.chunk_duration_ms < SourceRoleConfig::CHUNK_MIN_MS ||
        config.chunk_duration_ms > SourceRoleConfig::CHUNK_MAX_MS) {
        SS_LOGE(TAG,
                "Rejecting source config: chunk_duration_ms %" PRIu32 " outside [%" PRIu32
                ", %" PRIu32 "]",
                config.chunk_duration_ms, SourceRoleConfig::CHUNK_MIN_MS,
                SourceRoleConfig::CHUNK_MAX_MS);
        valid = false;
    } else if (valid && source_chunk_bytes(config) == 0) {
        SS_LOGE(TAG,
                "Rejecting source config: a %" PRIu32
                " ms chunk holds no whole frame or does not fit one "
                "transport message",
                config.chunk_duration_ms);
        valid = false;
    }
    if (config.capture_buffer_ms == 0) {
        SS_LOGE(TAG, "Rejecting source config: capture_buffer_ms must be > 0");
        valid = false;
    } else if (valid && source_capture_ring_bytes(config) == 0) {
        SS_LOGE(TAG,
                "Rejecting source config: a capture buffer of %" PRIu32 " ms at %" PRIu32
                " Hz is unusable",
                config.capture_buffer_ms, config.sample_rate);
        valid = false;
    }
    return valid;
}

// ============================================================================
// SourceRole forwarding (public API -> Impl)
// ============================================================================

SourceRole::SourceRole(SourceRoleConfig config, SendspinClient* client)
    : impl_(std::make_unique<Impl>(config, client)) {}

SourceRole::~SourceRole() = default;

void SourceRole::set_listener(SourceRoleListener* listener) {
    this->impl_->listener = listener;
}

bool SourceRole::write_audio(const uint8_t* data, size_t len, int64_t capture_time_us) {
    return this->impl_->write_audio(data, len, capture_time_us);
}

void SourceRole::set_signal(SourceSignal signal) {
    this->impl_->set_signal(signal);
}

bool SourceRole::is_streaming() const {
    return this->impl_->streaming_active;
}

// ============================================================================
// Impl: Internal integration methods
// ============================================================================

void SourceRole::Impl::attach(Inbox& inbox, SendspinArenaAllocator& arena) {
    this->inbox = &inbox;
    this->json_arena = &arena;
}

bool SourceRole::Impl::start(ProtocolTask* protocol_task) const {
    // An invalid config leaves the role inert: never advertised, and any start is ignored.
    if (!this->config_valid) {
        return true;
    }
    if (!this->task->start(protocol_task)) {
        SS_LOGE(TAG, "Failed to start the source task");
        return false;
    }
    return true;
}

void SourceRole::Impl::stop() const {
    this->task->stop();
}

void SourceRole::Impl::build_hello_fields(ClientHelloMessage& msg) const {
    if (!this->config_valid) {
        return;
    }
    msg.supported_roles.push_back(SendspinRole::SOURCE);
    msg.source_v1_support = SourceSupportObject{.line_sense = this->config.line_sense};
}

void SourceRole::Impl::build_state_fields(ClientStateMessage& msg) const {
    // roles/source/v1.md "client/state source object": present while the role is active, even
    // empty; signal only once one has been reported
    msg.source = ClientSourceStateObject{.signal = this->signal};
}

// ============================================================================
// Impl: Protocol-task half
// ============================================================================

void SourceRole::Impl::handle_server_command(SourceCommand command, SendspinConnection& conn,
                                             bool available) {
    if (command == SourceCommand::STOP) {
        this->end_stream(&conn);
        return;
    }
    // roles/source/v1.md "Source command semantics": a start received while unavailable is
    // ignored, and becoming available does not resume it; one received while an authorization is
    // pending or the stream is open has no effect.
    if (!available) {
        SS_LOGI(TAG, "Ignoring source start: the client is unavailable");
        return;
    }
    if (this->authorized_connection_id != 0 || this->open_connection_id != 0) {
        SS_LOGD(TAG, "Ignoring source start: the stream is already open or authorized");
        return;
    }
    // A rejected config, or a role added after SendspinClient::start(), has no task to stream.
    if (!this->config_valid || this->task->outbound_ring() == nullptr) {
        if (!this->not_running_warned) {
            this->not_running_warned = true;
            SS_LOGW(TAG, "Ignoring source start: the source role is not running");
        }
        return;
    }
    this->authorized_connection_id = conn.get_instance_id();
    this->try_open(conn);
}

void SourceRole::Impl::end_stream(SendspinConnection* conn) {
    this->authorized_connection_id = 0;
    const uint64_t stream_connection_id = this->open_connection_id;
    if (stream_connection_id == 0) {
        return;  // roles/source/v1.md "Source command semantics": nothing open, so ignored
    }
    this->close_gate();
    if (conn != nullptr && conn->get_instance_id() == stream_connection_id &&
        conn->accepts_app_sends()) {
        if (conn->first_activate_received()) {
            this->send_stream_end(*conn);
        } else {
            // connection.md "Re-handshake": nothing but the handshake until the next activation
            this->owed_end_connection_id = stream_connection_id;
        }
    }
    this->enqueue_stream_event(SourceStreamEventType::STREAMING_STOPPED);
}

void SourceRole::Impl::on_activation_applied(SendspinConnection& conn) {
    if (this->owed_end_connection_id != 0 &&
        this->owed_end_connection_id == conn.get_instance_id()) {
        this->owed_end_connection_id = 0;
        this->send_stream_end(conn);
    }
}

void SourceRole::Impl::send_chunks(SendspinConnection* owner) {
    if (owner != nullptr && this->authorized_connection_id == owner->get_instance_id()) {
        this->try_open(*owner);
    }
    OutboundRing* ring = this->task->outbound_ring();
    if (ring == nullptr) {
        return;
    }
    // The ring holds at most OUTBOUND_RING_ITEM_COUNT chunks, every item being reserved at the
    // largest chunk, so this takes all a tick can find; a chunk completed later wakes the task.
    for (size_t sent = 0; sent < OUTBOUND_RING_ITEM_COUNT; ++sent) {
        size_t capacity = 0;
        void* item = ring->take(&capacity, 0);
        if (item == nullptr) {
            return;
        }
        const OutboundItemHeader header = outbound_item_header(item);
        // A chunk of a closed stream, a dropped chunk, or one on a connection inside its
        // re-handshake's quiet window (connection.md "Re-handshake": the stream persists, and a
        // chunk dropped there is a capture gap like any other) is returned unsent.
        const bool sendable = header.data_len != 0 &&
                              source_gate_admits(this->stream_gate.load(std::memory_order_relaxed),
                                                 header.generation) &&
                              owner != nullptr &&
                              owner->get_instance_id() == this->open_connection_id &&
                              owner->is_operational();
        if (!sendable) {
            ring->return_item(item);
            continue;
        }
        // roles/source/v1.md "Source Audio Chunks (Binary)": the server-clock time the first
        // sample was captured, mapped through this connection's filter (offset and drift) as late
        // as possible, on the thread that feeds it.
        uint8_t* message = outbound_item_message(item);
        message[0] = SENDSPIN_BINARY_SOURCE_AUDIO;
        host_to_be64(owner->get_time_filter()->compute_server_time(header.capture_time_us),
                     message + 1);
        // The send chain owns the item from here on (OutboundRing "Lending"). A failure after
        // the encrypt closes the connection, which the loss pass that follows drops.
        (void)owner->send_app_binary_lent(*ring, item, capacity, header.data_len);
    }
}

void SourceRole::Impl::cleanup() {
    // Bumped first: an event queued before this teardown is void from here on.
    const uint32_t generation =
        this->cleanup_generation.fetch_add(1, std::memory_order_acq_rel) + 1;
    this->authorized_connection_id = 0;
    this->owed_end_connection_id = 0;
    if (this->open_connection_id != 0) {
        this->close_gate();
    }
    push_event_or_log(this->inbox, InboxEventType::SOURCE_CLEARED, 0, TAG, "source cleared event",
                      generation);
}

// ============================================================================
// Impl: Main-loop half
// ============================================================================

void SourceRole::Impl::handle_stream_ring_event(SourceStreamEventType event) {
    const bool started = event == SourceStreamEventType::STREAMING_STARTED;
    if (started == this->streaming_active) {
        return;  // Already reported
    }
    // Flipped before the callback, which may re-enter teardown: its catch-up then finds the
    // state the callback reports. The hold changes before the callback too: a stop() from
    // on_streaming_stopped() catches up through complete_teardown(), which finds streaming_active
    // already clear and releases nothing.
    this->streaming_active = started;
    if (started) {
        // An open stream holds high-performance networking, as playback does
        this->client->acquire_high_performance();
        if (this->listener != nullptr) {
            this->listener->on_streaming_started();
        }
        return;
    }
    this->client->release_high_performance();
    if (this->listener != nullptr) {
        this->listener->on_streaming_stopped();
    }
}

void SourceRole::Impl::complete_teardown() {
    this->handle_stream_ring_event(SourceStreamEventType::STREAMING_STOPPED);
}

// ============================================================================
// Impl: Consumer-facing method implementations
// ============================================================================

bool SourceRole::Impl::write_audio(const uint8_t* data, size_t len, int64_t capture_time_us) const {
    return this->task->write_audio(data, len, capture_time_us);
}

void SourceRole::Impl::set_signal(SourceSignal new_signal) {
    if (!this->config.line_sense) {
        SS_LOGW(TAG, "set_signal() ignored: line_sense not configured");
        return;
    }
    this->signal = new_signal;
    this->client->publish_state();
}

// ============================================================================
// Impl: Helpers
// ============================================================================

void SourceRole::Impl::try_open(SendspinConnection& conn) {
    if (this->authorized_connection_id != conn.get_instance_id() || !conn.is_time_synced() ||
        !conn.first_activate_received()) {
        return;  // Waits: send_chunks() retries on every tick
    }
    // roles/source/v1.md "Source command semantics": a start authorizes at most one opening.
    this->authorized_connection_id = 0;
    const SendspinCodecFormat codec = this->choose_codec(conn);
    ClientStreamStartMessage start_msg;
    start_msg.codec = codec;
    start_msg.channels = this->config.channels;
    start_msg.sample_rate = this->config.sample_rate;
    start_msg.bit_depth = this->config.bit_depth;
    if (conn.send_app_json(format_client_stream_start_message(&start_msg, *this->json_arena)) !=
        SsErr::OK) {
        // The authorization is consumed: the server must send another start.
        SS_LOGW(TAG, "Failed to send client-stream/start; stream not opened");
        return;
    }
    // A fresh generation, so no audio of an earlier stream is sent on this one. 0 is skipped:
    // it names no stream (SourceTask::encoder_generation_).
    uint32_t generation =
        (this->stream_gate.load(std::memory_order_relaxed) + 1) & SOURCE_GENERATION_MASK;
    if (generation == 0) {
        generation = 1;
    }
    this->stream_codec.store(codec, std::memory_order_relaxed);
    this->stream_gate.store(SOURCE_GATE_OPEN | generation, std::memory_order_release);
    this->open_connection_id = conn.get_instance_id();
    this->enqueue_stream_event(SourceStreamEventType::STREAMING_STARTED);
}

SendspinCodecFormat SourceRole::Impl::choose_codec(const SendspinConnection& conn) const {
    // roles/source/v1.md "client/hello source@v1 support object": a source announces only a codec
    // the server lists, and every server accepts pcm.
    if (this->config.codec != SendspinCodecFormat::PCM &&
        conn.server_accepts_source_codec(this->config.codec)) {
        return this->config.codec;
    }
    return SendspinCodecFormat::PCM;
}

void SourceRole::Impl::close_gate() {
    this->stream_gate.store(this->stream_gate.load(std::memory_order_relaxed) & ~SOURCE_GATE_OPEN,
                            std::memory_order_release);
    this->open_connection_id = 0;
}

void SourceRole::Impl::enqueue_stream_event(SourceStreamEventType event) const {
    // A drop leaves the listener out of step with the stream, so it logs at ERROR
    push_event_or_log(this->inbox, InboxEventType::SOURCE_STREAM, static_cast<uint8_t>(event), TAG,
                      event == SourceStreamEventType::STREAMING_STARTED ? "STREAMING_STARTED"
                                                                        : "STREAMING_STOPPED",
                      this->cleanup_generation.load(std::memory_order_acquire),
                      /*error_level=*/true);
}

void SourceRole::Impl::send_stream_end(SendspinConnection& conn) const {
    // A failure after the encrypt closes the connection, which ends the stream at the server too.
    (void)conn.send_app_json(format_client_stream_end_message(*this->json_arena));
}

}  // namespace sendspin
