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

#include "audio_types.h"
#include "platform/base64.h"
#include "platform/compiler.h"
#include "platform/logging.h"
#include "player_role_impl.h"
#include "protocol_messages.h"
#include "sendspin/client.h"

#include <algorithm>
#include <cstring>
#include <string>
#include <utility>

static const char* const TAG = "sendspin.player";

/// @brief Reads the output-delay blob (persistence_keys::OUTPUT_DELAY): a native uint16_t.
/// @return The stored value, or nullopt when the blob is not OUTPUT_DELAY_SIZE bytes.
static std::optional<uint16_t> parse_output_delay_blob(const std::vector<uint8_t>& blob) {
    static_assert(sendspin::persistence_keys::OUTPUT_DELAY_SIZE == sizeof(uint16_t));
    if (blob.size() != sendspin::persistence_keys::OUTPUT_DELAY_SIZE) {
        return std::nullopt;
    }
    uint16_t value = 0;
    std::memcpy(&value, blob.data(), sizeof(value));
    return value;
}

/// @brief Size of the big-endian 32-bit send_ahead that follows the timestamp in an audio chunk
/// (roles/player/v1.md "Audio Chunks (Binary)")
static constexpr size_t BINARY_SEND_AHEAD_SIZE = 4;
/// @brief Bytes an audio chunk spends on its header, after the message type byte.
static constexpr size_t AUDIO_CHUNK_HEADER_SIZE =
    sendspin::BINARY_TIMESTAMP_SIZE + BINARY_SEND_AHEAD_SIZE;
/// @brief Upper bound on the output delay, per roles/player/v1.md "Output delay": clients MUST
/// clamp output_delay_ms to the range 0-5000.
static constexpr uint16_t MAX_OUTPUT_DELAY_MS = 5000U;
/// @brief Offset of an audio chunk's encoded frame in its plaintext: the message type byte, then
/// the chunk header.
static constexpr size_t AUDIO_FRAME_OFFSET = 1 + AUDIO_CHUNK_HEADER_SIZE;
static_assert(AUDIO_FRAME_OFFSET == sendspin::INBOUND_AUDIO_CHUNK_HEADER_BYTES,
              "the inbound ring's hold window is derived from the same chunk layout");

/// @brief The smallest encoded audio frame the advertised buffer share is derived against
/// (sendspin::INBOUND_MIN_AUDIO_FRAME_BYTES: a 20 ms Opus packet at 64 kbps). FLAC and PCM chunks
/// are usually far larger. A server filling the advertised share with frames below the
/// break-even size overruns the quota, and the excess is dropped with a warning: at the (N-1)/N
/// share below that is the f with (N-1) * (f + AUDIO_CHUNK_OVERHEAD_BYTES) = N * f, 144 bytes
/// with N = 3 and the 72-byte chunk overhead.
static constexpr size_t MIN_AUDIO_FRAME_BYTES = sendspin::INBOUND_MIN_AUDIO_FRAME_BYTES;

/// @brief What one audio chunk costs the player's quota beyond its encoded frame: the ring's
/// per-item overhead (sendspin::INBOUND_ITEM_STORED_OVERHEAD_BYTES) and the bytes ahead of the
/// frame in the plaintext.
static constexpr size_t AUDIO_CHUNK_OVERHEAD_BYTES =
    sendspin::INBOUND_ITEM_STORED_OVERHEAD_BYTES + AUDIO_FRAME_OFFSET;

/// @brief Denominator for the advertised buffer capacity fraction: advertises (N-1)/N of the
/// quota. roles/player/v1.md has the server fill buffer_capacity with encoded frames, and each is
/// held at its stored cost, so (N-1)/N must not exceed the frame's share of its cost at the
/// smallest frame, MIN_AUDIO_FRAME_BYTES / (MIN_AUDIO_FRAME_BYTES + AUDIO_CHUNK_OVERHEAD_BYTES).
static constexpr size_t AUDIO_BUFFER_ADVERTISE_DENOMINATOR =
    (MIN_AUDIO_FRAME_BYTES + AUDIO_CHUNK_OVERHEAD_BYTES) / AUDIO_CHUNK_OVERHEAD_BYTES;
static_assert(AUDIO_BUFFER_ADVERTISE_DENOMINATOR >= 2, "the player must advertise some buffer");
static_assert((AUDIO_BUFFER_ADVERTISE_DENOMINATOR - 1) *
                      (MIN_AUDIO_FRAME_BYTES + AUDIO_CHUNK_OVERHEAD_BYTES) <=
                  AUDIO_BUFFER_ADVERTISE_DENOMINATOR * MIN_AUDIO_FRAME_BYTES,
              "the advertised share must not let the smallest chunks overrun the quota");
static_assert((AUDIO_BUFFER_ADVERTISE_DENOMINATOR - 1) *
                      sendspin::SharedRingLayout::stored_size(sizeof(sendspin::InboundItemHeader) +
                                                              AUDIO_FRAME_OFFSET +
                                                              MIN_AUDIO_FRAME_BYTES +
                                                              sendspin::AEAD_TAG_SIZE) <=
                  AUDIO_BUFFER_ADVERTISE_DENOMINATOR * MIN_AUDIO_FRAME_BYTES,
              "the exact stored size of the smallest chunk must fit the advertised share");
// Whether this build decodes Opus (SENDSPIN_ENABLE_OPUS); opus entries in audio_formats and opus
// streams are refused otherwise
#ifdef SENDSPIN_ENABLE_OPUS
static constexpr bool OPUS_DECODER_ENABLED = true;
#else
static constexpr bool OPUS_DECODER_ENABLED = false;
#endif

namespace sendspin {

/// @brief Checks the configured formats against the codec rules of the player spec
///
/// roles/player/v1.md "client/hello player@v1 support object" requires supported_formats to be a
/// non-empty list in which the player lists either flac or pcm, since those are the two codecs
/// every server supports and a player is not told which others a server has. Opus may be listed
/// only when this build decodes it (SENDSPIN_ENABLE_OPUS).
static bool audio_formats_valid(const std::vector<AudioSupportedFormatObject>& formats) {
    bool has_baseline = false;
    bool has_opus = false;
    for (const auto& format : formats) {
        if (format.codec == SendspinCodecFormat::FLAC || format.codec == SendspinCodecFormat::PCM) {
            has_baseline = true;
        } else if (format.codec == SendspinCodecFormat::OPUS) {
            has_opus = true;
        }
    }
    if (!has_baseline) {
        SS_LOGE(TAG,
                "audio_formats has no flac or pcm entry; servers need not support any other codec");
        return false;
    }
    if (has_opus && !OPUS_DECODER_ENABLED) {
        SS_LOGE(TAG, "audio_formats lists opus but this build has no Opus decoder");
        return false;
    }
    return true;
}

// ============================================================================
// Impl constructor / destructor
// ============================================================================

PlayerRole::Impl::Impl(PlayerRoleConfig config, SendspinClient* client)
    : config(std::move(config)),
      client(client),
      event_state(std::make_unique<EventState>()),
      sync_task(std::make_unique<SyncTask>()) {}

PlayerRole::Impl::~Impl() {
    // Stop the sync task thread first, before destroying other members.
    // External callbacks (e.g., PortAudio) may still call notify_audio_played() on another thread,
    // so the sync task must be stopped while it's still valid.
    this->sync_task.reset();
}

// ============================================================================
// PlayerRole forwarding (public API → Impl)
// ============================================================================

PlayerRole::PlayerRole(PlayerRoleConfig config, SendspinClient* client)
    : impl_(std::make_unique<Impl>(std::move(config), client)) {}

PlayerRole::~PlayerRole() = default;

void PlayerRole::set_listener(PlayerRoleListener* listener) {
    this->impl_->listener = listener;
}

void PlayerRole::notify_audio_played(uint32_t frames, int64_t timestamp) {
    if (this->impl_->sync_task && this->impl_->sync_task->is_running()) {
        this->impl_->sync_task->notify_audio_played(frames, timestamp);
    }
}

void PlayerRole::update_volume(uint8_t volume) {
    this->impl_->update_volume(volume);
}

void PlayerRole::update_muted(bool muted) {
    this->impl_->update_muted(muted);
}

void PlayerRole::update_output_delay(uint16_t delay_ms) {
    this->impl_->update_output_delay(delay_ms);
}

void PlayerRole::set_output_delay_adjustable(bool adjustable) {
    this->impl_->output_delay_adjustable.store(adjustable, std::memory_order_relaxed);
    this->impl_->client->publish_state();
}

const ServerPlayerStreamObject& PlayerRole::get_current_stream_params() const {
    return this->impl_->current_stream_params;
}

bool PlayerRole::get_muted() const {
    return this->impl_->muted;
}

uint16_t PlayerRole::get_output_delay_ms() const {
    return this->impl_->get_effective_output_delay_ms();
}

uint8_t PlayerRole::get_volume() const {
    return this->impl_->volume;
}

// ============================================================================
// Impl: State updates
// ============================================================================

void PlayerRole::Impl::update_volume(uint8_t volume) {
    this->volume = std::min(volume, VOLUME_MAX);
    this->client->publish_state();
}

void PlayerRole::Impl::update_muted(bool muted) {
    this->muted = muted;
    this->client->publish_state();
}

void PlayerRole::Impl::update_output_delay(uint16_t delay_ms) {
    if (delay_ms > MAX_OUTPUT_DELAY_MS) {
        delay_ms = MAX_OUTPUT_DELAY_MS;
    }
    // A server that re-sends the delay it already set, or a consumer control that lands on the
    // current value, must not cost a flash write.
    bool changed = this->output_delay_ms.exchange(delay_ms, std::memory_order_relaxed) != delay_ms;
    if (changed) {
        this->persist_output_delay();
    }
    this->client->publish_state();
}

// ============================================================================
// Impl: Internal integration methods
// ============================================================================

void PlayerRole::Impl::attach_inbox(Inbox& inbox) {
    this->inbox = &inbox;
    this->event_state->stream_params_slot.bind(inbox, INBOX_TOPIC_PLAYER_STREAM_PARAMS);
    this->event_state->command_slot.bind(inbox, INBOX_TOPIC_PLAYER_COMMAND);
    this->event_state->sync_idle_slot.bind(inbox, INBOX_TOPIC_PLAYER_SYNC_IDLE);
}

bool PlayerRole::Impl::start(SendspinPersistenceProvider* persistence, InboundRing* ring) {
    if (!audio_formats_valid(this->config.audio_formats)) {
        return false;
    }

    this->persistence = persistence;
    this->largest_ring_item_bytes = ring->max_item_message_bytes();
    this->load_output_delay();

    // A player with no listener has nowhere to write audio, so the sync task is not started and
    // the role reports state and takes commands without ever playing. Unlike a format list no
    // server can serve, this is a legitimate intermediate state for a consumer that wires its
    // output separately.
    if (!this->listener) {
        SS_LOGW(TAG, "Player has no listener: no audio will be played");
        return true;
    }
    // Init once (event flags); the thread is created and its item list bound to this run's ring
    // on every start(), including a restart after stop(), which joined the previous one.
    if (!this->sync_task->is_initialized() && !this->sync_task->init(this)) {
        SS_LOGE(TAG, "Failed to initialize sync task");
        return false;
    }
    if (!this->sync_task->start(ring, this->config.psram_stack, this->config.priority)) {
        SS_LOGE(TAG, "Failed to start sync task thread");
        return false;
    }
    return true;
}

void PlayerRole::Impl::stop() const {
    this->sync_task->stop();
}

void PlayerRole::Impl::build_hello_fields(ClientHelloMessage& msg) {
    msg.supported_roles.push_back(SendspinRole::PLAYER);

    // Advertise the share of the quota that holds encoded frames at the smallest frame size, so
    // the server's fill never overruns the quota, capped so its longest chunk fits one ring item
    PlayerSupportObject player_support = {
        .supported_formats = this->config.audio_formats,
        .buffer_capacity = this->advertised_buffer_capacity(),
    };
    msg.player_v1_support = std::move(player_support);
}

size_t PlayerRole::Impl::buffer_capacity_share() const {
    return this->config.audio_buffer_capacity * (AUDIO_BUFFER_ADVERTISE_DENOMINATOR - 1) /
           AUDIO_BUFFER_ADVERTISE_DENOMINATOR;
}

size_t PlayerRole::Impl::advertised_buffer_capacity() const {
    // roles/player/v1.md "Player Buffer Accounting" lets the server send one chunk as long as the
    // advertised capacity. One longer than a Noise frame arrives in fragments and is copied whole
    // into a ring item (handle_binary()), which the ring's largest item bounds; with a large quota
    // that bound is below the share (about 621 KB against 666,666 bytes by default), so the
    // advertisement stops there rather than the ring growing for a chunk no stream needs.
    return std::min(this->buffer_capacity_share(), this->largest_ring_item_bytes);
}

void PlayerRole::Impl::build_state_fields(ClientStateMessage& msg) const {
    ClientPlayerStateObject player_state{};
    player_state.volume = this->volume;
    player_state.muted = this->muted;
    bool adjustable = this->output_delay_adjustable.load(std::memory_order_relaxed);
    player_state.output_delay_ms =
        adjustable ? this->output_delay_ms.load(std::memory_order_relaxed) : 0;
    // Never below what the pipeline itself spends: the server extends lead only toward the
    // reported number, so a configured value under that floor would truncate the stream start
    // (roles/player/v1.md "Server Audio Send Constraints").
    player_state.required_lead_time_ms =
        std::max(this->config.required_lead_time_ms.value_or(0),
                 PlayerRoleConfig::pipeline_lead_time_ms(this->config.extra_startup_silence_ms));
    player_state.min_buffer_ms = this->config.min_buffer_ms;
    // roles/player/v1.md "client/state player object": the commands the server may send.
    player_state.supported_commands = {SendspinPlayerCommand::VOLUME, SendspinPlayerCommand::MUTE};
    if (adjustable) {
        player_state.supported_commands.push_back(SendspinPlayerCommand::SET_OUTPUT_DELAY);
    }
    msg.player = std::move(player_state);
}

std::optional<AudioChunk> PlayerRole::Impl::parse_audio_chunk(const uint8_t* data, size_t len) {
    if (len < AUDIO_CHUNK_HEADER_SIZE) {
        return std::nullopt;
    }
    return AudioChunk{.timestamp_us = be64_to_host(data),
                      .audio = data + AUDIO_CHUNK_HEADER_SIZE,
                      .audio_len = len - AUDIO_CHUNK_HEADER_SIZE};
}

SS_HOT void PlayerRole::Impl::handle_binary(InboundMessage& message) {
    const uint32_t generation = this->cleanup_generation.load(std::memory_order_acquire);
    InboundConsumer& inbound = this->sync_task->inbound();
    if (inbound.ring() == nullptr) {
        // Verbose on the per-chunk path: start() already warned once.
        SS_LOGV(TAG, "Discarding audio chunk: the sync task is not running");
        return;
    }
    auto chunk = parse_audio_chunk(message.data + 1, message.len - 1);
    if (!chunk.has_value()) {
        inbound.note_drop(InboundConsumer::DropReason::TOO_SHORT);
        return;
    }
    if (chunk->audio_len == 0) {
        // A complete header carrying no frame is nothing to decode. Verbose because this is the
        // per-chunk path.
        SS_LOGV(TAG, "Audio chunk carries no encoded frame");
        return;
    }
    // roles/player/v1.md "client/hello player@v1 support object": the server keeps the
    // advertised buffer_capacity, which the quota covers at the smallest chunk size.
    (void)inbound.hand_message(message,
                               {.data_len = static_cast<uint32_t>(chunk->audio_len),
                                .serial = 0,
                                .type = CHUNK_TYPE_ENCODED_AUDIO,
                                .data_offset = AUDIO_FRAME_OFFSET},
                               generation);
}

void PlayerRole::Impl::handle_stream_start(const ServerPlayerStreamObject& player_obj) {
    const uint32_t generation = this->cleanup_generation.load(std::memory_order_acquire);
    bool header_sent = false;
    // Numbers the codec header and the STREAM_START, so the sync task starts the stream only on
    // its own acknowledgement. Adopted once the header is handed over.
    const auto ordinal = static_cast<uint16_t>(this->stream_ordinal + 1);

    if (!player_obj.bit_depth.has_value() || !player_obj.channels.has_value() ||
        !player_obj.sample_rate.has_value() || !player_obj.codec.has_value()) {
        SS_LOGE(TAG, "Stream start message missing required audio parameters");
    } else {
        auto codec = player_obj.codec.value();

        if (codec == SendspinCodecFormat::PCM ||
            (OPUS_DECODER_ENABLED && codec == SendspinCodecFormat::OPUS)) {
            DummyHeader header{};
            header.sample_rate = player_obj.sample_rate.value();
            header.bits_per_sample = player_obj.bit_depth.value();
            header.channels = player_obj.channels.value();

            ChunkType chunk_type = (codec == SendspinCodecFormat::PCM)
                                       ? CHUNK_TYPE_PCM_DUMMY_HEADER
                                       : CHUNK_TYPE_OPUS_DUMMY_HEADER;

            header_sent = this->sync_task->inbound().hand_local(&header, sizeof(DummyHeader),
                                                                {.data_len = sizeof(DummyHeader),
                                                                 .serial = ordinal,
                                                                 .type = chunk_type,
                                                                 .data_offset = 0},
                                                                generation);
            if (!header_sent) {
                SS_LOGE(TAG, "Failed to send codec header");
            }
        } else if (codec == SendspinCodecFormat::FLAC) {
            if (!player_obj.codec_header.has_value()) {
                SS_LOGE(TAG, "FLAC codec header missing");
            } else {
                header_sent =
                    this->hand_flac_header(player_obj.codec_header.value(), ordinal, generation);
                if (!header_sent) {
                    SS_LOGE(TAG, "Failed to send codec header");
                }
            }
        } else {
            SS_LOGE(TAG, "Unsupported codec: %d", static_cast<int>(codec));
        }
    }

    if (!header_sent) {
        this->sync_task->signal_stream_end(this->stream_ordinal);
        this->enqueue_stream_event(PlayerStreamCallbackType::STREAM_END, generation, 0);
        return;
    }
    this->stream_ordinal = ordinal;

    // Write stream params to the inbox slot for the main thread, then signal. The high-performance
    // acquire for playback happens when the main loop drains the STREAM_START event, keeping
    // high_performance_requested_for_playback main-thread-only. Both carry `generation`, so a
    // teardown that runs before the drain takes them leaves a START the drain discards and params
    // it never applies (see drain_events()).
    this->event_state->stream_params_slot.write(player_obj, generation);
    this->enqueue_stream_event(PlayerStreamCallbackType::STREAM_START, generation, ordinal);
}

void PlayerRole::Impl::handle_stream_end() const {
    const uint32_t generation = this->cleanup_generation.load(std::memory_order_acquire);
    this->sync_task->signal_stream_end(this->stream_ordinal);
    this->enqueue_stream_event(PlayerStreamCallbackType::STREAM_END, generation, 0);
}

void PlayerRole::Impl::handle_stream_clear() {
    const uint32_t generation = this->cleanup_generation.load(std::memory_order_acquire);
    // stream/clear is a seek within the active stream: the server flushes our buffered audio and
    // immediately resumes sending new audio with the same codec/params (no new stream/start). Tell
    // the sync task to discard buffered audio, then append a marker so it knows exactly where the
    // discarded (pre-seek) audio ends and the new audio begins. The flag is set before the marker
    // so the sync task starts draining (freeing ring space) before the marker is written.
    // No listener callback: a seek is not a stream lifecycle event for the consumer.
    this->sync_task->signal_stream_clear();
    if (!this->sync_task->inbound().hand_local(
            nullptr, 0,
            {.data_len = 0, .serial = 0, .type = CHUNK_TYPE_STREAM_CLEAR_MARKER, .data_offset = 0},
            generation)) {
        // The marker couldn't be appended (no ring space). The sync task will
        // still drain to empty and apply the clear, but the pre-seek/post-seek boundary is lost,
        // so new audio may be discarded along with the old.
        SS_LOGW(TAG, "Failed to append stream/clear marker; seek boundary may be imprecise");
    }
}

void PlayerRole::Impl::handle_server_command(const ServerCommandMessage& cmd) const {
    const uint32_t generation = this->cleanup_generation.load(std::memory_order_acquire);
    if (!cmd.player.has_value()) {
        SS_LOGV(TAG, "Server command has no player commands");
        return;
    }
    this->event_state->command_slot.merge(
        [](ServerCommandMessage& current, ServerCommandMessage&& delta) {
            if (!delta.player.has_value()) {
                return;
            }
            if (!current.player.has_value()) {
                current.player = delta.player;
                return;
            }
            // Overlay individual optional fields so different command types
            // don't clobber each other when merged between drain ticks
            const auto& dp = delta.player.value();
            auto& cp = current.player.value();
            if (dp.volume.has_value()) {
                cp.volume = dp.volume;
            }
            if (dp.mute.has_value()) {
                cp.mute = dp.mute;
            }
            if (dp.output_delay_ms.has_value()) {
                cp.output_delay_ms = dp.output_delay_ms;
            }
        },
        ServerCommandMessage{cmd}, generation);
}

void PlayerRole::Impl::on_stream_ring_event(const InboxEvent& event) {
    this->awaiting_sync_idle_events.push_back(event);
}

void PlayerRole::Impl::drain_events() {
    // The sync-idle note is taken before is_running() is read below, so an idle transition after
    // that read sets the bit again for the next drain.
    bool sync_idle_note = false;
    (void)this->event_state->sync_idle_slot.take(sync_idle_note);
    ServerCommandMessage cmd_msg{};
    bool have_command = false;
    const uint32_t generation = take_current_payload(*this, this->event_state->command_slot,
                                                     cmd_msg, have_command, TAG, "server command");

    // --- Server command events (volume, mute, output delay) ---
    // Check each field independently since multiple command types may have been merged into one
    // inbox slot between drain ticks. Each callback may re-enter teardown (a listener calling
    // stop()), whose own drain already reset this role, so the rest is abandoned when the
    // generation moved on.
    if (have_command && cmd_msg.player.has_value()) {
        const ServerPlayerCommandObject& player_cmd = cmd_msg.player.value();

        if (player_cmd.volume.has_value()) {
            this->update_volume(player_cmd.volume.value());
            if (this->listener) {
                this->listener->on_volume_changed(player_cmd.volume.value());
            }
        }

        if (player_cmd.mute.has_value() && this->accepts(generation)) {
            this->update_muted(player_cmd.mute.value());
            if (this->listener) {
                this->listener->on_mute_changed(player_cmd.mute.value());
            }
        }

        // roles/player/v1.md "server/command player object": a command absent from the current
        // supported_commands is ignored.
        const bool delay_advertised = this->output_delay_adjustable.load(std::memory_order_relaxed);
        if (player_cmd.output_delay_ms.has_value() && !delay_advertised) {
            SS_LOGD(TAG, "Ignoring set_output_delay: not in supported_commands");
        } else if (player_cmd.output_delay_ms.has_value() && this->accepts(generation)) {
            this->update_output_delay(player_cmd.output_delay_ms.value());
            if (this->listener) {
                this->listener->on_output_delay_changed(
                    this->output_delay_ms.load(std::memory_order_relaxed));
            }
        }
    }
    if (!this->accepts(generation)) {
        return;
    }

    // --- Process awaiting sync idle events ---
    // Stream lifecycle arrivals (STREAM_START/STREAM_END) are appended directly to
    // awaiting_sync_idle_events by on_stream_ring_event(), called from the ring drain in
    // SendspinClient::drain_inbox() before role drain_events() runs each tick, so every event
    // pushed this tick is already in the vector below in FIFO arrival order.
    this->awaiting_sync_idle = false;
    if (!this->awaiting_sync_idle_events.empty()) {
        bool sync_idle = !this->sync_task->is_running();
        size_t processed = 0;
        bool teardown_reentered = false;

        // Indexed with a fresh size() check per iteration (not a range-for): the listener
        // callbacks below may re-enter connection teardown, whose own drain clears this vector
        // mid-loop. Cached range-for iterators would dangle; re-checking size() ends the loop
        // and the clamp before the erase below keeps the range valid.
        // NOLINTNEXTLINE(modernize-loop-convert): body mutates the vector, see above
        for (size_t idx = 0; idx < this->awaiting_sync_idle_events.size(); ++idx) {
            const InboxEvent event = this->awaiting_sync_idle_events[idx];
            const auto type = static_cast<PlayerStreamCallbackType>(event.code);
            if (type == PlayerStreamCallbackType::STREAM_END && !sync_idle) {
                // Wait for the sync task to go idle before firing this and anything after it. The
                // sync task writes sync_idle_slot when it does, which runs this drain again.
                this->awaiting_sync_idle = true;
                break;
            }

            switch (type) {
                case PlayerStreamCallbackType::STREAM_END:
                    // Only fire the callback when a stream is actually open: every teardown owes
                    // a STREAM_END (complete_teardown()), and a failed stream start enqueues one
                    // with no preceding START, so gating here keeps on_stream_end() paired 1:1
                    // with on_stream_start(). Cleared before the callback, which may re-enter
                    // teardown and owe a STREAM_END of its own: that one finds the stream closed.
                    if (this->stream_active) {
                        this->stream_active = false;
                        if (this->listener) {
                            this->listener->on_stream_end();
                        }
                    }
                    if (this->high_performance_requested_for_playback) {
                        this->client->release_high_performance();
                        this->high_performance_requested_for_playback = false;
                    }
                    if (!this->accepts(generation)) {
                        teardown_reentered = true;
                    }
                    break;
                case PlayerStreamCallbackType::STREAM_START: {
                    // Request high-performance networking for playback (deferred from the
                    // protocol task's handle_stream_start so the listener callback and the
                    // pairing flag stay on the main thread)
                    if (!this->high_performance_requested_for_playback) {
                        this->client->acquire_high_performance();
                        this->high_performance_requested_for_playback = true;
                    }
                    // The params handle_stream_start() wrote with this START. A teardown since
                    // then was caught up above and removed the START from this vector, so a stamp
                    // that is not current belongs to an older stream whose START was dropped.
                    ServerPlayerStreamObject stream_params;
                    uint32_t params_stamp = 0;
                    if (this->event_state->stream_params_slot.take(stream_params, params_stamp)) {
                        if (params_stamp == generation) {
                            this->current_stream_params = std::move(stream_params);
                        } else {
                            SS_LOGD(TAG, "Dropping stream params queued before the role was torn "
                                         "down");
                        }
                    }
                    // Mark the stream active before invoking the listener. on_stream_start() may
                    // re-enter teardown, and the STREAM_END the teardown owes fires
                    // on_stream_end() only when stream_active is set (see the gate above). Setting
                    // it first keeps start/end paired even when the batch is abandoned below.
                    this->stream_active = true;
                    if (this->listener) {
                        this->listener->on_stream_start();
                        // on_stream_start() may re-enter connection teardown, whose own drain
                        // already ended the stream and replaced this vector's content.
                        // Re-arming the sync task below would resurrect the dead stream, so
                        // abandon the batch instead (the clamp below keeps the erase in range).
                        // stream_active stays true so a STREAM_END the teardown still owes
                        // delivers a paired on_stream_end().
                        if (!this->accepts(generation)) {
                            teardown_reentered = true;
                            break;
                        }
                    }
                    this->sync_task->signal_stream_start(event.serial);
                    // The sync task sets TASK_RUNNING asynchronously after this signal, so the
                    // pre-loop snapshot is stale now: a STREAM_END later in this same batch must
                    // wait for the just-started task to drain
                    sync_idle = false;
                    break;
                }
            }
            if (teardown_reentered) {
                break;
            }
            ++processed;
        }

        // Clamp: a re-entrant teardown may have cleared the vector mid-loop, so processed can
        // exceed the current size.
        if (processed > this->awaiting_sync_idle_events.size()) {
            processed = this->awaiting_sync_idle_events.size();
        }
        if (processed > 0) {
            this->awaiting_sync_idle_events.erase(
                this->awaiting_sync_idle_events.begin(),
                this->awaiting_sync_idle_events.begin() + static_cast<ptrdiff_t>(processed));
        }
    }
}

void PlayerRole::Impl::cleanup() {
    // Flag the teardown before anything else: it tells a drain_events() frame that may be on the
    // call stack right now (a listener callback re-entering teardown) that the stream is gone,
    // and it stamps every event queued from here on, so a START this teardown just invalidated is
    // discarded (see cleanup_generation).
    const uint32_t generation =
        this->cleanup_generation.fetch_add(1, std::memory_order_acq_rel) + 1;

    // Return the items the sync task has not taken; one it takes before this carries the earlier
    // stamp, which its take() discards.
    this->sync_task->inbound().recall();

    // End the current stream: the sync task drains and returns to idle. (Not signal_stream_clear():
    // that path is a seek within a live stream and expects a marker to follow.)
    this->sync_task->signal_stream_end(this->stream_ordinal);

    // Discard stale slot content. Stale ring-borne events (an in-flight STREAM_START/STREAM_END
    // queued before this teardown) need no per-queue ring reset either way: on the
    // connection-loss path SendspinClient::cleanup_connection_state()'s inbox.reset_events() has
    // already wiped them, and on the deactivation path, which leaves the ring alone for the roles
    // that stay active, they carry the generation this teardown just left behind and the drain
    // discards them (see event_is_current()).
    this->event_state->stream_params_slot.reset();
    this->event_state->command_slot.reset();

    push_event_or_log(this->inbox, InboxEventType::PLAYER_CLEARED, 0, TAG, "player cleared event",
                      generation);
}

void PlayerRole::Impl::complete_teardown() {
    // The lifecycle events the teardown overtook, replaced by the STREAM_END it owes the
    // listener. The drain fires it once the sync task, which cleanup() told to end the stream,
    // reads idle, and only when a stream is open (see the STREAM_END gate in drain_events()).
    this->awaiting_sync_idle_events.clear();
    this->awaiting_sync_idle_events.push_back(InboxEvent{
        InboxEventType::PLAYER_STREAM, static_cast<uint8_t>(PlayerStreamCallbackType::STREAM_END)});
    this->awaiting_sync_idle = false;
    if (this->high_performance_requested_for_playback) {
        this->client->release_high_performance();
        this->high_performance_requested_for_playback = false;
    }
}

// ============================================================================
// Impl: Helpers
// ============================================================================

bool PlayerRole::Impl::hand_flac_header(const std::string& codec_header, uint16_t ordinal,
                                        uint32_t generation) const {
    InboundConsumer& inbound = this->sync_task->inbound();
    InboundRing* ring = inbound.ring();
    if (ring == nullptr) {
        return false;
    }
    // Decoded straight into the item, saving hand_local()'s second buffer and copy.
    const auto* input = reinterpret_cast<const unsigned char*>(codec_header.data());
    size_t decoded_len = 0;
    platform_base64_decode(nullptr, 0, &decoded_len, input, codec_header.size());
    if (decoded_len == 0 || decoded_len > INBOUND_MAX_MESSAGE_BYTES) {
        SS_LOGW(TAG, "FLAC codec header of %zu bytes is out of range", decoded_len);
        return false;
    }
    void* item = ring->acquire_local(decoded_len, INBOUND_ACQUIRE_TIMEOUT_MS);
    if (item == nullptr) {
        return false;
    }
    size_t written = 0;
    const int ret = platform_base64_decode(inbound_item_bytes(item), decoded_len, &written, input,
                                           codec_header.size());
    if (ret != 0) {
        SS_LOGW(TAG, "base64 decode failed: %d", ret);
        written = 0;
    }
    ring->complete(item);
    if (written == 0) {
        // Completed regardless (every acquired item must be), and handed back unused.
        ring->return_item(item);
        return false;
    }
    // Exempt, like every codec header.
    return inbound.hand(item, decoded_len,
                        {.data_len = static_cast<uint32_t>(written),
                         .serial = ordinal,
                         .type = CHUNK_TYPE_FLAC_HEADER,
                         .data_offset = 0},
                        generation, /*exempt=*/true);
}

void PlayerRole::Impl::enqueue_stream_event(PlayerStreamCallbackType event, uint32_t generation,
                                            uint16_t ordinal) const {
    // A dropped STREAM_START would leave the sync task waiting for its start signal forever;
    // a dropped STREAM_END would leave the consumer believing the stream is still active. Both
    // wedge the stream, so log the drop at ERROR (the helper defaults to WARN, which suits the
    // idempotent CLEARED events but understates a wedged player stream).
    push_event_or_log(
        this->inbox, InboxEventType::PLAYER_STREAM, static_cast<uint8_t>(event), TAG,
        event == PlayerStreamCallbackType::STREAM_START ? "STREAM_START" : "STREAM_END", generation,
        /*error_level=*/true, ordinal);
}

void PlayerRole::Impl::load_output_delay() {
    if (!this->persistence) {
        // No persistence provider - use initial value from config
        if (this->config.initial_output_delay_ms > 0) {
            this->output_delay_ms.store(this->config.initial_output_delay_ms,
                                        std::memory_order_relaxed);
            SS_LOGI(TAG, "Using initial output delay from config: %u ms",
                    this->config.initial_output_delay_ms);
        }
        return;
    }

    std::optional<uint16_t> delay;
    if (auto blob = this->persistence->load_blob(persistence_keys::OUTPUT_DELAY)) {
        delay = parse_output_delay_blob(blob.value());
    }
    if (delay.has_value()) {
        if (delay.value() <= MAX_OUTPUT_DELAY_MS) {
            this->output_delay_ms.store(delay.value(), std::memory_order_relaxed);
            SS_LOGI(TAG, "Loaded output delay: %u ms", delay.value());
        } else {
            SS_LOGW(TAG, "Persisted output delay out of range (%u), ignoring", delay.value());
        }
    } else if (this->config.initial_output_delay_ms > 0) {
        this->output_delay_ms.store(this->config.initial_output_delay_ms,
                                    std::memory_order_relaxed);
        SS_LOGI(TAG, "Using initial output delay from config: %u ms",
                this->config.initial_output_delay_ms);
    }
}

uint16_t PlayerRole::Impl::get_effective_output_delay_ms() const {
    return this->output_delay_adjustable.load(std::memory_order_relaxed)
               ? this->output_delay_ms.load(std::memory_order_relaxed)
               : 0;
}

void PlayerRole::Impl::persist_output_delay() const {
    if (this->persistence) {
        uint16_t delay = this->output_delay_ms.load(std::memory_order_relaxed);
        if (this->persistence->save_blob(persistence_keys::OUTPUT_DELAY,
                                         reinterpret_cast<const uint8_t*>(&delay), sizeof(delay))) {
            SS_LOGD(TAG, "Persisted output delay: %u ms", delay);
        } else {
            SS_LOGW(TAG, "Failed to persist output delay");
        }
    }
}

}  // namespace sendspin
