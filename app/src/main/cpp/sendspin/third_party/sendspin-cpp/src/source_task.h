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

/// @file source_task.h
/// @brief Background task that assembles captured audio into timestamped chunks for the protocol
/// task to send

#pragma once

#include "constants.h"
#include "inbound_ring.h"
#include "outbound_ring.h"
#include "platform/event_flags.h"
#include "sendspin/config.h"
#include "sendspin/source_role.h"
#include "source_encoder.h"

#include <atomic>
#include <cstddef>
#include <cstdint>
#include <memory>
#include <thread>

namespace sendspin {

class ProtocolTask;

/// @brief Event flag bits for the source task (distinct names from the sync task's
/// EventGroupBits: both unscoped enums can be visible in one translation unit)
enum SourceTaskBits : uint16_t {
    SOURCE_COMMAND_STOP = (1 << 0),  // Exit the task thread
    SOURCE_TASK_IDLE = (1 << 8),     // The thread has started
    SOURCE_TASK_STOPPED = (1 << 9),  // The thread has exited
};

// ============================================================================
// Stream gate
// ============================================================================

/// The stream gate's open bit (SourceRole::Impl::stream_gate); the bits below it hold the stream
/// generation.
static constexpr uint32_t SOURCE_GATE_OPEN = 1U << 31;
static constexpr uint32_t SOURCE_GENERATION_MASK = SOURCE_GATE_OPEN - 1;

/// @brief Whether a gate value admits audio stamped with `generation`: open, on that generation
constexpr bool source_gate_admits(uint32_t gate, uint32_t generation) {
    return (gate & SOURCE_GATE_OPEN) != 0 && (gate & SOURCE_GENERATION_MASK) == generation;
}

// ============================================================================
// Chunk and timestamp bookkeeping (pure helpers)
// ============================================================================

/// @brief Bytes in one frame (one sample across all channels) of the given capture format
constexpr size_t source_bytes_per_frame(uint8_t channels, uint8_t bit_depth) {
    return static_cast<size_t>(channels) * (static_cast<size_t>(bit_depth) / 8U);
}

/// @brief Frames in `ms` milliseconds at sample_rate. 64-bit so the product cannot wrap on
/// 32-bit targets.
constexpr uint64_t source_ms_to_frames(uint32_t ms, uint32_t sample_rate) {
    return static_cast<uint64_t>(ms) * sample_rate / MS_PER_SECOND;
}

/// @brief Duration in microseconds of frame_count frames at sample_rate
constexpr int64_t source_frames_to_us(uint64_t frame_count, uint32_t sample_rate) {
    return static_cast<int64_t>(frame_count * US_PER_SECOND / sample_rate);
}

/// @brief Capture time of the first not-yet-consumed sample of a capture item: a chunk that
/// starts mid-item is stamped with its own first sample (roles/source/v1.md "Source Audio Chunks
/// (Binary)"), not the item's
constexpr int64_t source_entry_anchor_us(int64_t entry_capture_us, size_t consumed_bytes,
                                         size_t bytes_per_frame, uint32_t sample_rate) {
    return entry_capture_us + source_frames_to_us(consumed_bytes / bytes_per_frame, sample_rate);
}

/// @brief Type byte plus the big-endian capture timestamp that precede a chunk's audio
/// (roles/source/v1.md "Source Audio Chunks (Binary)")
inline constexpr size_t SOURCE_CHUNK_HEADER_SIZE = 1U + 8U;

/// @brief Bytes of audio in one full chunk of the config, or 0 when the chunk holds no whole
/// frame or would not fit one Noise transport message with its header (the send path encrypts
/// a chunk in place and never fragments it)
size_t source_chunk_bytes(const SourceRoleConfig& config);

/// @brief Storage for the capture ring: capture_buffer_ms of audio plus a quarter for the
/// per-write ring overhead (SharedRingLayout's item header and padding, and the
/// OutboundItemHeader each write carries: 24 to 27 bytes a write, a quarter of a write of 96
/// bytes, half a millisecond of 48 kHz stereo 16-bit). Writes smaller than that hold less audio
/// than capture_buffer_ms, and one longer than the ring's largest item (half the storage) is
/// always refused. 0 when the size would overflow or is too small for a ring.
size_t source_capture_ring_bytes(const SourceRoleConfig& config);

/**
 * @brief The source role's data path between the capture thread and the protocol task
 * (docs/internals.md "Source Streaming Pipeline"). The capture ring reuses OutboundRing with this
 * task as its consumer and nothing lent.
 */
class SourceTask {
public:
    /// @param role The owning role, which outlives the task: its validated config, its stream
    ///        gate and its stream codec.
    explicit SourceTask(SourceRole::Impl* role);
    ~SourceTask();

    SourceTask(const SourceTask&) = delete;
    SourceTask& operator=(const SourceTask&) = delete;

    /// @brief Creates the rings, the event flags and an OPUS config's encoder, then starts the
    /// thread, which waits for captured audio. Main loop, from SourceRole::Impl::start().
    /// @param protocol_task Woken whenever a chunk is completed.
    /// @return false on an allocation failure or when the thread is already running.
    bool start(ProtocolTask* protocol_task);

    /// @brief Signals the thread to exit, joins it, then destroys the outbound ring. No-op if
    /// the thread is not running. Main loop, from SendspinClient::stop_role_threads().
    void stop();

    /// @brief Writes captured audio into the capture ring; the contract is
    /// SourceRole::write_audio()'s. The capture thread.
    bool write_audio(const uint8_t* data, size_t len, int64_t capture_time_us);

    /// @brief The ring the protocol task takes completed chunks from, between start() and stop();
    /// nullptr otherwise
    OutboundRing* outbound_ring() const {
        return this->outbound_ring_.get();
    }

protected:
    /// @brief Entry point for the thread
    static void thread_entry(void* params);

    /// @brief Thread body: process() until SOURCE_COMMAND_STOP, then drops the chunk in progress
    void run();

    /// @brief One step of the chunk loop: takes the next capture item when none is in progress
    /// (waiting up to `take_timeout_ms`), then copies as much of it as the chunk in progress
    /// takes, completing the chunk when it is full
    void process(uint32_t take_timeout_ms);

    /// @brief Starts a chunk at the capture item in progress: acquires its outbound item, waiting
    /// at most chunk_duration_ms, and anchors it on the item's first unread sample. A new
    /// generation also resets the encoder, warming the Opus encoder up first on this thread's first
    /// Opus stream.
    /// @return false when the outbound ring had no room: the capture item stays held for the next
    ///         attempt, unless the capture ring overflowed meanwhile, when the backlog is flushed
    ///         to live capture.
    bool begin_chunk(const OutboundItemHeader& capture);

    /// @brief Encodes the full chunk into its outbound item, stamps the header, completes it and
    /// wakes the protocol task
    void finish_chunk();

    /// @brief Completes the chunk in progress, if any, with no data, so the protocol task returns
    /// it unsent
    void drop_chunk();

    /// @brief Returns the capture item in progress, if any
    void release_capture_item();

    /// @brief Returns the capture item in progress and every one waiting, so streaming resumes
    /// from live capture (roles/source/v1.md "Source Audio Chunks (Binary)")
    void flush_to_live();

    /// @brief The encoder for a stream of `codec`: the Opus encoder for opus, the passthrough
    /// otherwise
    SourceEncoder* encoder_for(SendspinCodecFormat codec);

    // Struct fields
    EventFlags event_flags_;
    /// The run of writes the full capture ring refused; the capture thread's, like every
    /// producer_ field.
    InboundDropLog producer_drop_log_;
    PcmPassthroughEncoder pcm_encoder_;
    /// The Opus encoder of an OPUS config, created by the first start() and kept, like the
    /// capture ring; null otherwise.
    std::unique_ptr<SourceEncoder> opus_encoder_;
    std::unique_ptr<OutboundRing> capture_ring_;
    std::unique_ptr<OutboundRing> outbound_ring_;
    std::thread task_thread_;

    // Pointer fields
    /// The capture item being read; task thread only, like every field below that is not atomic
    /// or set by start().
    void* capture_item_{nullptr};
    /// The outbound item the chunk in progress fills; nullptr between chunks.
    void* chunk_item_{nullptr};
    /// Where the chunk in progress is assembled: the encoder's input buffer, or the outbound
    /// item's payload area for an encoder without one.
    uint8_t* chunk_pcm_{nullptr};
    /// The encoder of the stream chunk_generation_ belongs to.
    SourceEncoder* encoder_{&pcm_encoder_};
    ProtocolTask* protocol_task_{nullptr};
    SourceRole::Impl* role_;

    // 64-bit fields
    /// Local-clock capture time of the chunk's first sample.
    int64_t chunk_anchor_us_{0};

    // size_t fields
    /// The config's frame size, set at construction like the two below.
    size_t bytes_per_frame_;
    /// Bytes of PCM in one full chunk.
    size_t chunk_bytes_;
    /// Message bytes each outbound item is acquired with: the chunk header, the largest payload
    /// and the AEAD tag room.
    size_t chunk_message_bytes_;
    /// Bytes of capture_item_ already copied into chunks.
    size_t capture_consumed_{0};
    /// Bytes of the chunk in progress assembled so far.
    size_t chunk_filled_{0};

    // 32-bit fields
    /// The generation of the stream whose write the full capture ring refused last, or 0: set by
    /// the capture thread, taken by the task with its next capture item, which then resumes from
    /// live capture. Generation 0 is never opened.
    std::atomic<uint32_t> overflow_generation_{0};
    /// The stream generation of the chunk in progress.
    uint32_t chunk_generation_{0};
    /// The generation encoder_ was last reset for; generation 0 is never opened.
    uint32_t encoder_generation_{0};
    /// The stream generation the producer's warning state belongs to; write_audio() ends its drop
    /// run and clears its flags when a write is admitted under a new one. Capture thread only,
    /// like every producer_ field.
    uint32_t producer_generation_{0};

    // 8-bit fields
    /// Whether write_audio() warned of a partial-frame write, and of one longer than any capture
    /// item, since its last accepted write.
    bool producer_partial_warned_{false};
    bool producer_oversize_warned_{false};
    /// Task-only: opus_encoder_ was warmed up on this run's thread (SourceEncoder::warm_up());
    /// micro-opus's pseudostack is per thread.
    bool opus_warm_{false};
};

}  // namespace sendspin
