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

#include "source_task.h"

#include "crypto/constants.h"
#include "platform/crypto.h"
#include "platform/logging.h"
#include "platform/thread.h"
#include "platform/time.h"
#include "protocol_messages.h"
#include "protocol_task.h"
#include "source_encoder_opus.h"
#include "source_role_impl.h"

#include <algorithm>
#include <cstring>

namespace sendspin {

static const char* const TAG = "sendspin.source_task";

static_assert(SOURCE_CHUNK_HEADER_SIZE == 1 + BINARY_TIMESTAMP_SIZE,
              "a source chunk's header is its type byte and its timestamp");

/// @brief Capture ring margin for the per-write overhead: +1/N over the audio capacity (see
/// source_capture_ring_bytes())
static constexpr size_t CAPTURE_RING_OVERHEAD_DENOMINATOR = 4U;

/// @brief Largest capture buffer, in bytes of audio: past this a config is rejected rather than
/// its size computed in a type that could wrap
static constexpr uint64_t MAX_CAPTURE_BUFFER_BYTES = UINT32_MAX / 4;

size_t source_chunk_bytes(const SourceRoleConfig& config) {
    const size_t bytes_per_frame = source_bytes_per_frame(config.channels, config.bit_depth);
    if (bytes_per_frame == 0 || config.sample_rate == 0) {
        return 0;
    }
    const uint64_t frames = source_ms_to_frames(config.chunk_duration_ms, config.sample_rate);
    const size_t max_audio_bytes = MAX_TRANSPORT_PLAINTEXT - SOURCE_CHUNK_HEADER_SIZE;
    if (frames == 0 || frames > max_audio_bytes / bytes_per_frame) {
        return 0;
    }
    return static_cast<size_t>(frames) * bytes_per_frame;
}

size_t source_capture_ring_bytes(const SourceRoleConfig& config) {
    const size_t bytes_per_frame = source_bytes_per_frame(config.channels, config.bit_depth);
    if (bytes_per_frame == 0) {
        return 0;
    }
    const uint64_t frames = source_ms_to_frames(config.capture_buffer_ms, config.sample_rate);
    if (frames > MAX_CAPTURE_BUFFER_BYTES / bytes_per_frame) {
        return 0;
    }
    const auto audio_bytes = static_cast<size_t>(frames * bytes_per_frame);
    const size_t storage =
        SharedRingLayout::align(audio_bytes + audio_bytes / CAPTURE_RING_OVERHEAD_DENOMINATOR);
    return SharedRingLayout::is_valid_storage_size(storage) ? storage : 0;
}

// ============================================================================
// Lifecycle
// ============================================================================

SourceTask::SourceTask(SourceRole::Impl* role)
    : role_(role),
      bytes_per_frame_(source_bytes_per_frame(role->config.channels, role->config.bit_depth)),
      chunk_bytes_(source_chunk_bytes(role->config)),
      // The largest payload: the PCM chunk, which an OPUS role still streams to a server that
      // does not list opus, and for an OPUS config an Opus packet, which can outgrow a short
      // chunk's PCM. AEAD_TAG_SIZE of room behind the message takes the in-place encrypt's tag.
      chunk_message_bytes_(SOURCE_CHUNK_HEADER_SIZE +
                           (role->config.codec == SendspinCodecFormat::OPUS
                                ? std::max(this->chunk_bytes_, OpusSourceEncoder::MAX_PACKET_BYTES)
                                : this->chunk_bytes_) +
                           AEAD_TAG_SIZE) {}

SourceTask::~SourceTask() {
    this->stop();
}

bool SourceTask::start(ProtocolTask* protocol_task) {
    if (this->task_thread_.joinable()) {
        return false;
    }
    this->protocol_task_ = protocol_task;
    const SourceRoleConfig& config = this->role_->config;

    if (!this->event_flags_.is_created() && !this->event_flags_.create()) {
        SS_LOGE(TAG, "Couldn't create event flags");
        return false;
    }
    if (this->capture_ring_ == nullptr) {
        auto capture_ring = std::make_unique<OutboundRing>();
        if (!capture_ring->create(source_capture_ring_bytes(config), config.buffer_location)) {
            return false;  // Logged by the ring
        }
        this->capture_ring_ = std::move(capture_ring);
    }
    if (config.codec == SendspinCodecFormat::OPUS && this->opus_encoder_ == nullptr) {
#ifdef SENDSPIN_ENABLE_OPUS
        auto opus_encoder = std::make_unique<OpusSourceEncoder>();
        if (!opus_encoder->init(config)) {
            return false;  // Logged by the encoder
        }
        this->opus_encoder_ = std::move(opus_encoder);
#else
        return false;  // Unreachable: validate_config() refuses OPUS without the encoder
#endif
    }
    auto outbound_ring = std::make_unique<OutboundRing>();
    if (!outbound_ring->create(
            derive_outbound_ring_bytes(this->chunk_message_bytes_, OUTBOUND_RING_ITEM_COUNT),
            config.buffer_location)) {
        return false;  // Logged by the ring
    }
    this->outbound_ring_ = std::move(outbound_ring);

    // A fresh thread starts from a clean group, with nothing left over from the previous run.
    this->event_flags_.clear_all();
    this->capture_item_ = nullptr;
    this->chunk_item_ = nullptr;
    this->encoder_generation_ = 0;
    this->opus_warm_ = false;
    const size_t stack_size = config.codec == SendspinCodecFormat::OPUS
                                  ? SourceRoleConfig::DEFAULT_OPUS_SOURCE_TASK_STACK_SIZE
                                  : SourceRoleConfig::DEFAULT_SOURCE_TASK_STACK_SIZE;
    platform_configure_thread("SsSrc", stack_size, static_cast<int>(config.priority),
                              config.psram_stack);
    this->task_thread_ = std::thread(thread_entry, this);
    this->event_flags_.wait(SourceTaskBits::SOURCE_TASK_IDLE | SourceTaskBits::SOURCE_TASK_STOPPED,
                            false, false, UINT32_MAX);
    return true;
}

void SourceTask::stop() {
    if (!this->task_thread_.joinable()) {
        return;
    }
    // Flag first, then wake: the thread re-checks its flags after every take returns. An
    // outbound acquire in progress ends within its own chunk_duration_ms bound.
    this->event_flags_.set(SourceTaskBits::SOURCE_COMMAND_STOP);
    this->capture_ring_->wake_consumer();
    this->task_thread_.join();
    // Every lent item is back: see docs/internals.md "Source Streaming Pipeline".
    this->outbound_ring_.reset();
}

// ============================================================================
// Capture thread
// ============================================================================

bool SourceTask::write_audio(const uint8_t* data, size_t len, int64_t capture_time_us) {
    // Closed by default and opened only once a run's rings exist, so an inert or stopped role
    // rejects too. Read once: the write is stamped with the generation it was admitted under.
    const uint32_t gate = this->role_->stream_gate.load(std::memory_order_acquire);
    if ((gate & SOURCE_GATE_OPEN) == 0) {
        return false;
    }
    const uint32_t generation = gate & SOURCE_GENERATION_MASK;
    if (generation != this->producer_generation_) {
        // A new stream ends the last one's drop run and starts its warnings afresh, here on the
        // capture thread that owns that state
        this->producer_generation_ = generation;
        this->producer_drop_log_.end_run(TAG, "capture writes");
        this->producer_partial_warned_ = false;
        this->producer_oversize_warned_ = false;
    }
    if (len == 0 || (len % this->bytes_per_frame_) != 0U) {
        // A partial frame would shift the channel interleaving of every later sample; warn once
        // per episode
        if (!this->producer_partial_warned_) {
            this->producer_partial_warned_ = true;
            SS_LOGW(TAG,
                    "write_audio() rejected: %zu bytes is not a whole number of %zu-byte frames",
                    len, this->bytes_per_frame_);
        }
        return false;
    }
    if (len > this->capture_ring_->max_message_bytes()) {
        // No item can ever hold it, so it is no overflow: the queued audio is kept
        if (!this->producer_oversize_warned_) {
            this->producer_oversize_warned_ = true;
            SS_LOGW(TAG, "write_audio() rejected: %zu bytes exceeds the %zu-byte largest write",
                    len, this->capture_ring_->max_message_bytes());
        }
        return false;
    }
    if (capture_time_us == 0) {
        // The write ends about now, so its first frame was captured one write duration earlier
        capture_time_us = platform_time_us() - source_frames_to_us(len / this->bytes_per_frame_,
                                                                   this->role_->config.sample_rate);
    }
    void* item = this->capture_ring_->acquire(len, 0);
    if (item == nullptr) {
        // The ring bounds a stall's backlog; the task resumes from live capture once it drains
        this->overflow_generation_.store(generation, std::memory_order_release);
        if (this->producer_drop_log_.note_drop()) {
            SS_LOGW(TAG, "Capture buffer full; dropping writes until it drains");
        }
        return false;
    }
    std::memcpy(outbound_item_message(item), data, len);
    set_outbound_item_header(item, OutboundItemHeader{.capture_time_us = capture_time_us,
                                                      .generation = generation,
                                                      .data_len = static_cast<uint32_t>(len)});
    // Wakes the task's take
    this->capture_ring_->complete(item);
    this->producer_drop_log_.end_run(TAG, "capture writes");
    this->producer_partial_warned_ = false;
    this->producer_oversize_warned_ = false;
    return true;
}

// ============================================================================
// Task loop
// ============================================================================

void SourceTask::thread_entry(void* params) {
    static_cast<SourceTask*>(params)->run();
}

void SourceTask::run() {
    this->event_flags_.set(SourceTaskBits::SOURCE_TASK_IDLE);
    while ((this->event_flags_.get() & SourceTaskBits::SOURCE_COMMAND_STOP) == 0U) {
        // Waits for capture alone: stop() wakes it, and the gate keeps capture out while no
        // stream is open.
        this->process(UINT32_MAX);
    }
    // A partial chunk is dropped: the spec allows but does not require a short final chunk.
    this->drop_chunk();
    this->release_capture_item();
    this->event_flags_.clear(SourceTaskBits::SOURCE_TASK_IDLE);
    this->event_flags_.set(SourceTaskBits::SOURCE_TASK_STOPPED);
}

void SourceTask::process(uint32_t take_timeout_ms) {
    bool fresh_item = false;
    if (this->capture_item_ == nullptr) {
        size_t capacity = 0;
        this->capture_item_ = this->capture_ring_->take(&capacity, take_timeout_ms);
        if (this->capture_item_ == nullptr) {
            return;
        }
        this->capture_consumed_ = 0;
        fresh_item = true;
    }
    const OutboundItemHeader capture = outbound_item_header(this->capture_item_);
    if (!source_gate_admits(this->role_->stream_gate.load(std::memory_order_acquire),
                            capture.generation)) {
        // Audio of a stream that has closed: neither it nor the chunk it would join is sent.
        this->drop_chunk();
        this->release_capture_item();
        return;
    }
    if (this->chunk_item_ != nullptr && capture.generation != this->chunk_generation_) {
        // The stream changed part-way through the chunk
        this->drop_chunk();
    }
    // The capture ring overflowed while the sends were stalled: drop the backlog, and the chunk
    // that joins it, and resume from live capture rather than burst stale audio
    // (roles/source/v1.md "Source Audio Chunks (Binary)"). An overflow of an earlier stream says
    // nothing about this one.
    if (fresh_item &&
        this->overflow_generation_.exchange(0, std::memory_order_acq_rel) == capture.generation) {
        this->drop_chunk();
        this->flush_to_live();
        return;
    }
    if (this->chunk_item_ == nullptr) {
        if (!this->begin_chunk(capture)) {
            return;
        }
    }

    const size_t take = std::min(static_cast<size_t>(capture.data_len) - this->capture_consumed_,
                                 this->chunk_bytes_ - this->chunk_filled_);
    std::memcpy(this->chunk_pcm_ + this->chunk_filled_,
                outbound_item_message(this->capture_item_) + this->capture_consumed_, take);
    this->chunk_filled_ += take;
    this->capture_consumed_ += take;
    if (this->capture_consumed_ == capture.data_len) {
        this->release_capture_item();
    }
    if (this->chunk_filled_ == this->chunk_bytes_) {
        this->finish_chunk();
    }
}

bool SourceTask::begin_chunk(const OutboundItemHeader& capture) {
    // A full ring waits one chunk's duration at a time, so stop() and a closed gate are seen
    // between waits. The capture item stays held and the backlog builds in the capture ring,
    // which bounds the send stall the stream rides out (capture_buffer_ms).
    this->chunk_item_ = this->outbound_ring_->acquire(this->chunk_message_bytes_,
                                                      this->role_->config.chunk_duration_ms);
    if (this->chunk_item_ == nullptr) {
        // The capture ring overflowed during the stall: the backlog is stale, so resume from live
        // capture (roles/source/v1.md "Source Audio Chunks (Binary)"). The write path logged it.
        uint32_t overflowed = capture.generation;
        if (this->overflow_generation_.compare_exchange_strong(overflowed, 0,
                                                               std::memory_order_acq_rel)) {
            this->flush_to_live();
        }
        return false;
    }
    if (capture.generation != this->encoder_generation_) {
        // Read after the gate admitted this generation, which publishes the codec chosen for it
        this->encoder_ =
            this->encoder_for(this->role_->stream_codec.load(std::memory_order_acquire));
        if (this->encoder_ == this->opus_encoder_.get() && !this->opus_warm_) {
            // So micro-opus allocates this thread's pseudostack here, before the first Opus
            // chunk's encode, and a run that never streams opus never allocates it
            this->encoder_->warm_up();
            this->opus_warm_ = true;
        }
        this->encoder_->reset();
        this->encoder_generation_ = capture.generation;
    }
    uint8_t* input = this->encoder_->input_buffer();
    this->chunk_pcm_ = input != nullptr
                           ? input
                           : outbound_item_message(this->chunk_item_) + SOURCE_CHUNK_HEADER_SIZE;
    this->chunk_filled_ = 0;
    this->chunk_generation_ = capture.generation;
    this->chunk_anchor_us_ =
        source_entry_anchor_us(capture.capture_time_us, this->capture_consumed_,
                               this->bytes_per_frame_, this->role_->config.sample_rate);
    return true;
}

void SourceTask::finish_chunk() {
    uint8_t* payload = outbound_item_message(this->chunk_item_) + SOURCE_CHUNK_HEADER_SIZE;
    const size_t payload_len = this->encoder_->encode(
        this->chunk_pcm_, this->chunk_bytes_, payload,
        this->chunk_message_bytes_ - SOURCE_CHUNK_HEADER_SIZE - AEAD_TAG_SIZE);
    // The type byte and the server-clock timestamp are the protocol task's to write; a failed
    // encode leaves data_len 0, which the protocol task returns unsent.
    set_outbound_item_header(
        this->chunk_item_,
        OutboundItemHeader{
            .capture_time_us = this->chunk_anchor_us_ - this->encoder_->lookahead_us(),
            .generation = this->chunk_generation_,
            .data_len = payload_len != 0
                            ? static_cast<uint32_t>(SOURCE_CHUNK_HEADER_SIZE + payload_len)
                            : 0U});
    this->outbound_ring_->complete(this->chunk_item_);
    this->chunk_item_ = nullptr;
    this->protocol_task_->wake();
}

void SourceTask::drop_chunk() {
    if (this->chunk_item_ == nullptr) {
        return;
    }
    // acquire() zeroed the header, so data_len is 0. Completed regardless, as every acquired item
    // must be, and woken for, so the protocol task frees the space it holds.
    this->outbound_ring_->complete(this->chunk_item_);
    this->chunk_item_ = nullptr;
    this->protocol_task_->wake();
}

void SourceTask::release_capture_item() {
    if (this->capture_item_ != nullptr) {
        this->capture_ring_->return_item(this->capture_item_);
        this->capture_item_ = nullptr;
    }
}

void SourceTask::flush_to_live() {
    this->release_capture_item();
    size_t capacity = 0;
    while (void* item = this->capture_ring_->take(&capacity, 0)) {
        this->capture_ring_->return_item(item);
    }
}

SourceEncoder* SourceTask::encoder_for(SendspinCodecFormat codec) {
    // Opus is chosen only for an OPUS config (SourceRole::Impl::choose_codec()), whose start()
    // created the encoder
    if (codec == SendspinCodecFormat::OPUS && this->opus_encoder_ != nullptr) {
        return this->opus_encoder_.get();
    }
    return &this->pcm_encoder_;
}

}  // namespace sendspin
