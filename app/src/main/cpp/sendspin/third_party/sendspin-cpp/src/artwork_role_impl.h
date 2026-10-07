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

/// @file artwork_role_impl.h
/// @brief Private implementation for the artwork role (pimpl)

#pragma once

#include "inbound_ring.h"
#include "inbox.h"
#include "platform/memory.h"
#include "protocol_messages.h"
#include "sendspin/artwork_role.h"
#include "teardown_tracker.h"

#include <atomic>
#include <cstddef>
#include <cstdint>
#include <memory>
#include <mutex>
#include <optional>
#include <thread>
#include <type_traits>
#include <vector>

namespace sendspin {

class SendspinClient;
struct ClientHelloMessage;
struct ClientStateMessage;

/// @brief Deferred artwork event types
enum class ArtworkEventType : uint8_t {
    STREAM_END,
};

/// @brief Maximum number of artwork slots (2-bit slot field in protocol binary type byte)
static constexpr size_t ARTWORK_MAX_SLOTS = 4;

/// @brief Ack-gate state for a slot with require_frame_done enabled
enum class SlotAckState : uint8_t {
    IDLE,              // no un-acked delivery; next frame or clear may be processed
    DECODE_DELIVERED,  // the decode thread claimed a delivery (on_image_decode fired for a frame;
                       // nothing fires for a per-channel clear), its display/clear not yet fired
    PRESENTED,         // on_image_display or on_image_clear fired, awaiting frame_done()
};

/// @brief What an item on the artwork decode thread's list is (InboundItemHeader::type)
///
/// The protocol task hands an image over in message order: its announce, then each part, and a
/// marker for anything that discards a channel's pending image. Position in the list alone orders
/// them, so a part handed after a marker for its channel can never be added to an image the marker
/// discarded.
enum class ArtworkItemType : uint8_t {
    /// A LOCAL item carrying the ArtworkAnnounce of an image the role takes; serial is the
    /// channel. Exempt from the quota, like a codec header.
    ANNOUNCE,
    /// One part of that image, in the ring item it arrived in (or copied into a LOCAL one when it
    /// was not in a ring item), which the decode thread copies into the channel's buffer and
    /// returns; data_offset and data_len name its image bytes, serial is the channel. Charged to
    /// the artwork quota.
    PART,
    /// A LOCAL marker discarding the pending image of every channel in serial's bit mask: a
    /// cancel, a stream end, or an image the role refused or lost a part of. Exempt.
    DISCARD,
    /// A DISCARD for the channels a stream/start reconfigured, which also releases a
    /// DECODE_DELIVERED gate on each: that decode's display can no longer fire. Exempt.
    RECONFIGURE,
};

/// @brief An image announce as the decode thread reads it from an ArtworkItemType::ANNOUNCE item
///
/// `total_size == 0` is the protocol's empty image, which clears the channel: it completes at
/// its announce and, apart from naming no bytes, is delivered like a frame (the ack gate and the
/// timestamp-scheduled hand-off to the main loop). `epoch` is the channel's slot epoch after the
/// announce bumped it (see ArtworkRole::Impl::slot_epochs): the decode thread skips an image the
/// channel has since moved past and stamps the display hand-off with it.
struct ArtworkAnnounce {
    int64_t timestamp{0};
    uint32_t total_size{0};
    uint32_t epoch{0};
};
static_assert(std::is_trivially_copyable_v<ArtworkAnnounce>,
              "an announce is copied into ring storage as bytes");

/// @brief The one image transfer the role has in flight, across all of its channels
///
/// roles/artwork/v1.md "Artwork (Binary)" allows at most one transfer in flight per role: it
/// begins at an announce and ends when the accumulated part data reaches `total_size`, when a
/// cancel abandons it, or when the stream it belongs to goes away. A second announce arriving
/// while `in_flight` is set is the malformed-sequence rule rather than a second record.
///
/// Protocol task only (handle_binary() and the stream lifecycle handlers), or the main loop in
/// stop() once the protocol task is joined.
///
/// `discarding` marks a transfer whose parts the role does not hand over: an image it will not
/// hold (see ArtworkRole::Impl::image_cap), or one whose announce or part it could not hand over.
/// Per "Artwork (Binary)", a client discarding image data must still count each part's bytes
/// toward `total_size`, so the sequence is tracked to the end and only the bytes are dropped.
struct ArtworkTransfer {
    uint32_t total_size{0};
    uint32_t received{0};
    bool in_flight{false};
    uint8_t slot{0};
    bool discarding{false};
};

/// @brief One channel's ack gate, shared by the decode thread and the main loop
///
/// Both fields are guarded by DrainTask::slot_mutex. ack_state is meaningful only for a channel
/// with require_frame_done (see ack_enabled()). has_parked is set by the decode thread when a
/// complete delivery waits behind the gate (ArtworkAssembly::State::READY), and cleared by
/// whichever thread takes or drops it: the decode thread when it
/// delivers or replaces it, the main loop when a stream end supersedes it, which the decode thread
/// then reads as the parked image being dropped.
struct SlotGate {
    SlotAckState ack_state{SlotAckState::IDLE};
    bool has_parked{false};
};

/// @brief One channel's image being assembled by the decode thread
///
/// Decode thread only; the buffer is allocated by start() and released by stop() on the main
/// loop, around the thread's run. ASSEMBLING: each part is copied into `buffer` as the decode
/// thread takes it, whatever the channel's gate says, and its item returned at once. READY: the
/// image is complete in `buffer` but the channel's gate holds an un-acked delivery, so it waits
/// there, the channel's pending image, until frame_done() reopens the gate. One buffer is enough:
/// roles/artwork/v1.md "Artwork (Binary)" allows a channel one current image and at most one
/// pending one, and "an announce discards that channel's pending image", so a new announce always
/// finds the buffer free or holding the image it discards.
struct ArtworkAssembly {
    enum class State : uint8_t {
        IDLE,
        ASSEMBLING,
        READY,
    };

    PlatformBuffer buffer;
    int64_t timestamp{0};
    uint32_t total_size{0};
    uint32_t received{0};
    uint32_t epoch{0};
    /// The teardown generation the announce was handed over under, which stamps the display.
    uint32_t generation{0};
    State state{State::IDLE};
};

/// @brief Latest-wins display timestamps accumulated across artwork slots
///
/// Merged cross-thread by the decode thread (one slot per merge) and taken whole by the
/// main-loop drain; a bit set in valid_mask means timestamps[i] holds a pending display.
/// epochs[i] carries the slot epoch the decode ran under, so the main-loop deadline check can
/// drop a display the slot has since moved past (a stream restart, a cancel, or a fresh announce
/// bumps the epoch but cannot reach a display already folded into the main-thread holds). A bit
/// set in clear_mask means slot i's pending delivery is a per-channel clear rather than a decoded
/// frame, so the deadline fires on_image_clear() instead of on_image_display(); it is meaningful
/// only where valid_mask is set.
struct ArtworkDisplayUpdate {
    int64_t timestamps[ARTWORK_MAX_SLOTS]{};
    uint32_t epochs[ARTWORK_MAX_SLOTS]{};
    uint8_t valid_mask{0};
    uint8_t clear_mask{0};
};

/// @brief Private implementation of the artwork role
struct ArtworkRole::Impl : RoleTeardown {
    Impl(ArtworkRoleConfig config, SendspinClient* client);
    ~Impl();

    // ========================================
    // Nested types
    // ========================================

    /// @brief Persistent decode thread context and the artwork role's end of the inbound ring,
    /// through which the protocol task hands it announces, image parts and markers
    struct DrainTask {
        /// Its item list's flags also carry the decode thread's command bit.
        InboundConsumer inbound;
        std::thread drain_thread;
        /// Decode thread only; see ArtworkAssembly.
        ArtworkAssembly assemblies[ARTWORK_MAX_SLOTS];
        /// Guarded by slot_mutex; see SlotGate.
        SlotGate slot_gates[ARTWORK_MAX_SLOTS];
        /// @brief Guards slot_gates, between the decode thread and the main loop: the ack gate
        /// (frame_done(), the displays and clears drain_events() fires) and whether a complete
        /// delivery is parked behind it. One mutex for all slots is intentional: artwork is not a
        /// hot path, so cross-slot contention is negligible. The protocol task never takes it.
        std::mutex slot_mutex;
        /// The teardown generation of what the decode thread assembles and parks: a teardown
        /// that moves cleanup_generation past it drops all of it. Decode thread only.
        uint32_t assembly_generation{0};
    };

    /// @brief Deferred event state for artwork display timestamps, delivered to the main thread
    /// via the shared Inbox
    struct EventState {
        GenerationSlot<ArtworkDisplayUpdate> display_slot;
    };

    // ========================================
    // Internal integration methods (called by SendspinClient)
    // ========================================

    void attach_inbox(Inbox& inbox);
    /// @brief Allocates each configured channel's assembly buffer, binds the decode thread's item
    /// list to `ring` and starts the thread. Main loop, before the protocol task starts.
    /// @param ring The client's inbound ring for this run.
    /// @return false when a buffer or the list cannot be created.
    bool start(InboundRing* ring);
    void build_hello_fields(ClientHelloMessage& msg) const;
    void build_state_fields(ClientStateMessage& msg) const;
    /// @brief Handles one artwork binary message. Protocol task.
    /// @param slot Artwork channel the message's type byte named (0-3).
    /// @param message The whole message, type byte first. A part handed to the decode thread in
    /// its ring item takes the item over (message.item cleared).
    /// @return false when the message is a protocol error per roles/artwork/v1.md "Artwork
    /// (Binary)" and the caller must close the connection; true when processed or ignored.
    bool handle_binary(uint8_t slot, InboundMessage& message);
    // handle_stream_start() and handle_stream_end() load the role's teardown generation once at
    // entry and stamp the items and events they queue with it; see RoleTeardown.
    void handle_stream_start(const ServerArtworkStreamObject& stream);
    void handle_stream_end();
    void handle_stream_ring_event(ArtworkEventType event);
    // True if this tick has drainable artwork work. The display-slot bit covers newly decoded
    // images; a nonzero held_display_mask means displays folded in on a prior tick are still
    // waiting out their server-clock deadlines (see held_display_ts): the deadline itself sets
    // no inbox bit, so a nonzero mask must be polled every tick until each slot fires or is
    // dropped for a slot-epoch mismatch.
    bool needs_drain(uint32_t pending_bits) const {
        return (pending_bits & INBOX_TOPIC_ARTWORK_DISPLAY) != 0 || this->held_display_mask != 0;
    }
    /// @brief Takes the display slot, catches the role up on any teardown (complete_teardown()),
    /// folds the taken displays into the holds if they were decoded under the current generation,
    /// and fires the displays whose deadline has passed. Main loop.
    void drain_events();
    /// @brief The main-loop teardown half: drops the held displays, which the protocol task
    /// cannot reach, and clears every channel (clear_every_channel()), the clears the teardown
    /// owes the listener. Main loop only, through catch_up_teardown().
    void complete_teardown();
    /// @brief Ends the stream on the main loop: drops the held displays, arms each ack-gated
    /// channel for the frame_done() its clear is owed, and fires on_image_clear() for every
    /// configured channel. A stream/end's STREAM_END event and a teardown's catch-up. Main loop.
    void clear_every_channel();

    /// @brief Stops the role and discards its state. Protocol task, or the main loop in
    /// SendspinClient::stop() once every other thread is joined.
    ///
    /// Shared by the two paths that take the role out of service: a connection being torn down
    /// (SendspinClient::cleanup_connection_state()) and a server/activate that removes the role
    /// from active_roles (SendspinClient::apply_role_removals()). Recalls the items the decode
    /// thread has not taken and wakes it to drop the images it assembles or parks (see
    /// DrainTask::assembly_generation), and queues ARTWORK_CLEARED, stamped with the new
    /// generation, for the main loop to catch up on (catch_up_teardown()).
    void cleanup();

    // ========================================
    // Consumer-facing method implementations
    // ========================================

    void frame_done(uint8_t slot) const;

    // ========================================
    // Helpers
    // ========================================

    /// @brief Asks the decode thread to exit without waiting for it; stop() joins. Lets a caller
    /// overlap the thread's exit with other teardown.
    /// @return true if a running thread was signalled, false if none was running.
    bool signal_stop() const;
    /// @brief Joins the decode thread, returns the items left on its list, unbinds the list and
    /// releases the assembly buffers.
    void stop() const;
    /// Queues a stream lifecycle event stamped with `generation`, which the drain compares
    /// against the live counter before dispatching it.
    void enqueue_stream_event(ArtworkEventType event, uint32_t generation) const;
    // Merges a single-slot display delta into the accumulated cross-thread update. Called under
    // the Inbox mutex via InboxSlot::merge() (see deliver()), so it must stay a pure data
    // operation with no callbacks into application code. `delta` carries exactly one slot's bit
    // (set by the decode thread after a single image finishes decoding or a clear is validated);
    // OR-ing valid_mask and overwriting only the masked entries preserves latest-wins per slot
    // while leaving any other slot's already-accumulated (not yet drained) entry untouched.
    // clear_mask is assigned per bit rather than OR-ed; drain_events() folds the taken update into
    // the main-thread holds with the same per-bit assignment, so the two must agree. Pure and
    // static for direct unit testing.
    static void merge_artwork_display_update(ArtworkDisplayUpdate& current,
                                             ArtworkDisplayUpdate&& delta);
    // How far past its display deadline a held slot is, in microseconds: >= 0 means due (the
    // value is the lateness reported to on_image_display), < 0 means not yet due. client_ts is
    // the server-clock deadline already converted to the client clock (0 = no connection: due
    // immediately with lateness 0, since no deadline exists); display_offset_ms shifts the
    // deadline, positive firing early (mirroring PlayerRoleConfig::fixed_delay_us) and negative
    // delaying. Pure and static for direct unit testing.
    static int64_t display_overdue_us(int64_t client_ts, int32_t display_offset_ms, int64_t now);
    // Maps a due display's overdue microseconds (from display_overdue_us) to the lateness_ms
    // reported to on_image_display(). client_ts == 0 means no connection: report 0, the
    // documented "no deadline exists" sentinel. When connected, floor the result at 1 ms so a
    // sub-millisecond-late display never truncates to 0 and collides with that sentinel; the top
    // is clamped at UINT32_MAX ms (~49 days), past which lateness is not meaningful. Pure and
    // static for direct unit testing.
    static uint32_t display_lateness_ms(int64_t client_ts, int64_t overdue_us);
    // The timestamp and total_size of an announce message, read from its flags byte on
    // (roles/artwork/v1.md "Artwork (Binary)": an 8-byte signed timestamp and a 4-byte total_size,
    // both big-endian); the epoch is left 0 for the caller. Pure and static for direct unit
    // testing: nothing observable distinguishes a timestamp read wrongly while the client has no
    // connection.
    static ArtworkAnnounce parse_announce(const uint8_t* body);
    // True if `slot` is within range and configured with require_frame_done.
    bool ack_enabled(uint8_t slot) const;
    // Largest encoded image the role will hold for `slot`: the channel's configured
    // ImageSlotPreference::max_image_bytes, or 0 for a slot the role declared no channel for.
    uint32_t image_cap(uint8_t slot) const;
    // Format the decode callback reports for `slot`: the one the channel was configured with, so
    // the array index stays authoritative for slot mapping (see the Impl constructor).
    SendspinImageFormat image_format(uint8_t slot) const;
    // Discards the slot's pending image by bumping its epoch, and abandons the in-flight transfer
    // if it is that slot's. Both a cancel message and a fresh announce need exactly this.
    // Protocol task.
    void discard_pending(uint8_t slot);
    // Discards every channel's pending image, and forgets the streamed configuration so the next
    // stream/start compares against nothing. A stream end and a disconnect each end the whole
    // stream this way; a stream/start discards only the channels it changed (see
    // changed_channel_mask()). Protocol task.
    void discard_all_pending();
    // Which channels this stream/start changed the configuration of, as a slot bitmask. Every
    // channel counts as changed when either side has no channel array to compare.
    uint8_t changed_channel_mask(const ServerArtworkStreamObject& stream) const;
    // True if two stream/start channel entries declare the same configuration.
    static bool same_channel(const ServerArtworkChannelObject& a,
                             const ServerArtworkChannelObject& b);
    // Starts the transfer an announce declares and hands its announce to the decode thread, or a
    // DISCARD marker for an image the role will not hold. @return false for the malformed
    // sequence of an announce while a transfer is in flight.
    bool begin_transfer(uint8_t slot, const uint8_t* body, uint32_t generation);
    // Counts one part toward the transfer in flight and hands it to the decode thread unless the
    // transfer is discarding. @return false for a malformed sequence.
    bool hand_part(uint8_t slot, InboundMessage& message, uint32_t generation);
    // Hands a DISCARD or RECONFIGURE marker for the channels in `mask`, logging a failure.
    // Protocol task.
    void hand_marker(ArtworkItemType type, uint8_t mask, uint32_t generation) const;
    // Wakes the decode thread out of its blocking take so it re-runs the parked-slot sweep and
    // its generation check at the top of its loop. A no-op while the role is not running.
    void wake_drain_thread() const;

    // Decode thread
    // Takes the next item handed over under the current generation and processes it; false when
    // none was taken in `timeout_ms`. The decode thread's step.
    bool process_next_item(uint32_t timeout_ms);
    // Drops every image assembled or parked when `generation` is not the one it was handed under.
    void adopt_generation(uint32_t generation) const;
    // An announce: drops the channel's pending image and starts assembling the new one.
    void begin_assembly(uint8_t slot, void* item);
    // A part: copied into the buffer (ASSEMBLING) or unused (no image in progress), and its item
    // returned at once either way.
    void add_part(uint8_t slot, void* item);
    // Drops the slot's image whatever its state, and the parked flag with it; with
    // `release_delivered` also reopens a DECODE_DELIVERED gate (a RECONFIGURE marker).
    void drop_assembly(uint8_t slot, bool release_delivered = false) const;
    // Delivers a complete image (or empty image): skipped when the channel's epoch moved past it,
    // parked READY when its gate is closed, otherwise decoded and its display handed to the main
    // loop.
    void deliver(uint8_t slot);
    // Delivers each channel's parked (READY) image whose gate reopened, and drops one whose park
    // the main loop dropped.
    void sweep_parked();
    static void drain_thread_func(ArtworkRole::Impl* self);

    // ========================================
    // Fields
    // ========================================

    // Struct fields
    ArtworkRoleConfig config;
    std::vector<ArtworkChannelFormatObject> artwork_channels;
    // Protocol task only (or the main loop in stop() once it is joined); see ArtworkTransfer.
    ArtworkTransfer transfer;
    // The channel array of the stream/start in force, kept so the next one can be compared
    // against it: only the channels whose configuration changes lose their pending image.
    // Protocol task only, like `transfer`: written by handle_stream_start() and cleared by
    // discard_all_pending().
    std::optional<std::vector<ServerArtworkChannelObject>> streamed_channels;

    // Pointer fields
    SendspinClient* client;
    std::unique_ptr<DrainTask> drain_task;
    std::unique_ptr<EventState> event_state;
    Inbox* inbox{nullptr};
    ArtworkRoleListener* listener{nullptr};

    // 64-bit fields
    // Latest-wins display timestamps folded in from display_slot, awaiting their server-clock
    // deadlines. Main-thread only: written and read exclusively from drain_events()/
    // handle_stream_ring_event()/cleanup() on the loop thread. held_display_ts[i] is valid only
    // when bit i of held_display_mask is set.
    int64_t held_display_ts[ARTWORK_MAX_SLOTS]{};

    // 32-bit fields
    // Slot epoch each held display was decoded under; a mismatch against slot_epochs at
    // deadline-check time means the slot has since moved past it (a stream restart, a cancel, or
    // a fresh announce) and the display must be dropped, since the protocol task cannot reach
    // the main-thread holds to cancel it. Main-thread only; see held_display_ts.
    uint32_t held_display_epoch[ARTWORK_MAX_SLOTS]{};

    /// @brief Per-channel delivery epoch, bumped whenever the channel's pending image is
    /// discarded: by a stream end or cleanup (every channel at once), by a stream/start (the
    /// channels whose configuration it changed), and by a cancel message or a fresh announce (that
    /// channel alone, per roles/artwork/v1.md "Artwork (Binary)"). An announce, a decode hand-off,
    /// and a held display all carry the epoch they were made under, so each drops itself at its
    /// next check: the decode thread skips an image the channel moved past before its marker
    /// reaches it, and the main loop drops a display whose image was discarded after its decode.
    /// An image already displayed has left the pipeline, which is what makes the current image
    /// survive a cancel. Bumped on the protocol task (or the main loop in stop() once it is
    /// joined); read there, on the decode thread and on the main loop.
    std::atomic<uint32_t> slot_epochs[ARTWORK_MAX_SLOTS]{};

    // 8-bit fields
    /// Written on the protocol task (or the main loop in stop() once it is joined); read there
    /// and on the decode thread, which skips a display hand-off once the stream ended.
    std::atomic<bool> stream_active{false};
    // Main-thread only; see held_display_ts.
    uint8_t held_display_mask{0};
    // Which held deliveries are per-channel clears rather than decoded frames: bit i selects
    // on_image_clear() over on_image_display() when slot i's deadline fires. Only meaningful where
    // held_display_mask is set. Main-thread only; see held_display_ts.
    uint8_t held_display_clear{0};
};

}  // namespace sendspin
