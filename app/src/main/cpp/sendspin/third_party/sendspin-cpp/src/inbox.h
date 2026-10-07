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

/// @file inbox.h
/// @brief Single shared mutex, dirty-topic bitmask, and fixed-capacity event ring that
/// consolidate all main-loop-bound small-message traffic onto one endpoint

#pragma once

#include "platform/logging.h"

#include <algorithm>
#include <array>
#include <atomic>
#include <cassert>
#include <cstddef>
#include <cstdint>
#include <mutex>
#include <type_traits>
#include <utility>

namespace sendspin {

// ============================================================================
// Topic bits
// ============================================================================

/// One bit per main-loop-drained endpoint. A bit is owned by exactly one InboxSlot
/// (or by the event ring); it is set when that endpoint has pending content and
/// cleared when the endpoint is drained, always under the Inbox mutex.
static constexpr uint32_t INBOX_TOPIC_EVENTS = 1U << 0;                // Shared event ring
static constexpr uint32_t INBOX_TOPIC_GROUP = 1U << 1;                 // Group update slot
static constexpr uint32_t INBOX_TOPIC_CONTROLLER = 1U << 2;            // Controller state slot
static constexpr uint32_t INBOX_TOPIC_METADATA = 1U << 3;              // Metadata state slot
static constexpr uint32_t INBOX_TOPIC_COLOR = 1U << 4;                 // Color state slot
static constexpr uint32_t INBOX_TOPIC_PLAYER_COMMAND = 1U << 5;        // Player command slot
static constexpr uint32_t INBOX_TOPIC_PLAYER_STREAM_PARAMS = 1U << 6;  // Player stream params slot
static constexpr uint32_t INBOX_TOPIC_VISUALIZER_CONFIG = 1U << 7;     // Visualizer config slot
static constexpr uint32_t INBOX_TOPIC_ARTWORK_DISPLAY = 1U << 8;       // Artwork display slot
static constexpr uint32_t INBOX_TOPIC_PERSIST = 1U << 9;               // Owed provider writes slot
static constexpr uint32_t INBOX_TOPIC_TIME = 1U << 10;                 // Time-sync report slot
static constexpr uint32_t INBOX_TOPIC_PAIRING = 1U << 11;              // Pairing/trust notes slot
static constexpr uint32_t INBOX_TOPIC_HIGH_PERFORMANCE = 1U << 12;     // High-performance requests
static constexpr uint32_t INBOX_TOPIC_PLAYER_SYNC_IDLE = 1U << 13;     // Sync task left a stream

// ============================================================================
// Event ring types
// ============================================================================

/// @brief Discriminates InboxEvent entries in the shared ring
///
/// The `code` field on InboxEvent carries a role-local enum value (cast to/from uint8_t by the
/// producer/consumer); the inbox does not interpret it.
///
/// Each role's cleanup() pushes its *_CLEARED event, stamped with the teardown's generation; the
/// drain only catches the role up on it (catch_up_teardown()), and the role's main-loop half
/// delivers the clear (complete_teardown()), before the main loop acts on anything the next
/// connection sends. The head of every drain also catches each role up, so a teardown whose event
/// the full ring dropped still delivers its clear.
enum class InboxEventType : uint8_t {
    PLAYER_STREAM,       // Player stream lifecycle; code = PlayerStreamCallbackType
    PLAYER_CLEARED,      // Player torn down; epoch = the teardown's generation
    CONTROLLER_CLEARED,  // Controller state cleared; epoch = the teardown's generation
    METADATA_CLEARED,    // Metadata cleared; epoch = the teardown's generation
    COLOR_CLEARED,       // Color state cleared; epoch = the teardown's generation
    ARTWORK_STREAM,      // Artwork stream lifecycle; code = ArtworkEventType
    ARTWORK_CLEARED,     // Artwork torn down; epoch = the teardown's generation
    VISUALIZER_STREAM,   // Visualizer stream lifecycle; code = VisualizerEventType
    VISUALIZER_CLEARED,  // Visualizer torn down; epoch = the teardown's generation
    SOURCE_STREAM,       // Source input stream lifecycle; code = SourceStreamEventType
    SOURCE_CLEARED,      // Source torn down; epoch = the teardown's generation
};

/// @brief One entry in the shared event ring
///
/// POD; copied in and out of the ring by value.
struct InboxEvent {
    InboxEventType type{};
    uint8_t code{0};  // Role-local enum value; 0 when unused
    /// Producer-defined sequence number, 0 when unused: the stream ordinal of a player
    /// STREAM_START, which the drain hands back to the sync task as its start acknowledgement.
    uint16_t serial{0};
    /// Teardown generation of the producing role at push time, 0 for events that have none; the
    /// consumer drops a mismatch so an event queued before a teardown cannot act after it (see
    /// event_is_current()).
    uint32_t epoch{0};
};

// ============================================================================
// Inbox
// ============================================================================

template <typename T>
class InboxSlot;

/**
 * @brief Shared main-loop mailbox: one mutex, one dirty-topic bitmask, one fixed event ring
 *
 * Producer threads (the protocol task, decode) write latest-value state through an InboxSlot bound
 * to this Inbox, or push lifecycle events directly with push_event(). The main loop reads poll()
 * once per tick and only locks the mutex to drain topics whose bit is set.
 *
 * @note HARD RULE: no user-visible code (listener callbacks, client/role methods) may run while
 * the inbox mutex is held. merge() functors passed to InboxSlot must be pure data operations on
 * the slot value, so nothing that calls back into application code. The Inbox mutex is a leaf, like
 * every library lock (docs/conventions.md): nothing takes another library lock while holding it,
 * and nothing locks the Inbox while holding another library lock.
 *
 * @code
 * Inbox inbox;
 * InboxSlot<GroupUpdateObject> group_slot(inbox, INBOX_TOPIC_GROUP);
 *
 * // Producer thread:
 * group_slot.write(update);
 *
 * // Main loop:
 * if (inbox.poll() & INBOX_TOPIC_GROUP) {
 *     GroupUpdateObject update;
 *     if (group_slot.take(update)) { ... }
 * }
 * @endcode
 */
class Inbox {
    template <typename>
    friend class InboxSlot;

public:
    static constexpr size_t EVENT_CAPACITY = 32;

    Inbox() = default;
    ~Inbox() = default;

    // Not copyable or movable
    Inbox(const Inbox&) = delete;
    Inbox& operator=(const Inbox&) = delete;

    /// @brief Lock-free hint for which topics currently have pending content
    ///
    /// Read once per main-loop tick without locking. Ground truth is the mutex-guarded
    /// per-endpoint content, so a bit here can go stale the instant this returns: a drain call
    /// must tolerate finding nothing, and a freshly-set bit this load missed is picked up by the
    /// next tick's poll().
    uint32_t poll() const {
        return this->pending_.load(std::memory_order_acquire);
    }

    /// @brief Pushes one event onto the shared ring
    ///
    /// If the ring is full the new event is dropped (matching today's queue-full behavior) and
    /// existing contents are left intact; the caller should log the drop.
    /// @param event Event to append.
    /// @return true if the event was appended, false if the ring was full.
    bool push_event(const InboxEvent& event) {
        std::lock_guard<std::mutex> lock(this->mutex_);
        if (this->count_ >= EVENT_CAPACITY) {
            return false;
        }
        size_t tail = (this->head_ + this->count_) % EVENT_CAPACITY;
        this->events_[tail] = event;
        ++this->count_;
        this->set_bit_locked(INBOX_TOPIC_EVENTS);
        return true;
    }

    /// @brief Copies out and removes up to max_count of the oldest ring events
    ///
    /// Events are delivered in FIFO order. If fewer than max_count events are pending, only
    /// those are copied. The INBOX_TOPIC_EVENTS bit is cleared only when the ring is fully
    /// drained; a partial drain (max_count smaller than the pending count) leaves the bit set so
    /// the remaining events are not silently missed on the next poll().
    size_t take_events(InboxEvent* out, size_t max_count) {
        std::lock_guard<std::mutex> lock(this->mutex_);
        size_t n = std::min(max_count, this->count_);
        for (size_t i = 0; i < n; ++i) {
            out[i] = this->events_[(this->head_ + i) % EVENT_CAPACITY];
        }
        this->head_ = (this->head_ + n) % EVENT_CAPACITY;
        this->count_ -= n;
        if (this->count_ == 0) {
            this->clear_bit_locked(INBOX_TOPIC_EVENTS);
        }
        return n;
    }

    /// @brief Discards all pending ring events
    void reset_events() {
        std::lock_guard<std::mutex> lock(this->mutex_);
        this->head_ = 0;
        this->count_ = 0;
        this->clear_bit_locked(INBOX_TOPIC_EVENTS);
    }

private:
    /// @brief Sets `bit` in the dirty-topic mask. Caller must hold `mutex_`
    void set_bit_locked(uint32_t bit) {
        this->pending_.fetch_or(bit, std::memory_order_release);
    }

    /// @brief Clears `bit` in the dirty-topic mask. Caller must hold `mutex_`
    void clear_bit_locked(uint32_t bit) {
        this->pending_.fetch_and(~bit, std::memory_order_acq_rel);
    }

    /// @brief Records `bit` as owned by a slot (called from InboxSlot::bind())
    ///
    /// Enforces InboxSlot's exclusive-ownership invariant; asserts in debug, no-op in release.
    void claim_bit(uint32_t bit) {
        std::lock_guard<std::mutex> lock(this->mutex_);
        assert((this->claimed_bits_ & bit) == 0 && "INBOX_TOPIC bit already claimed by a slot");
        this->claimed_bits_ |= bit;
    }

    /// @brief Releases a slot's bit claim (called from ~InboxSlot(), which must run before this
    /// Inbox is destroyed)
    void release_bit(uint32_t bit) {
        std::lock_guard<std::mutex> lock(this->mutex_);
        this->claimed_bits_ &= ~bit;
    }

    // Struct fields
    std::mutex mutex_;
    std::array<InboxEvent, EVENT_CAPACITY> events_{};

    // size_t fields
    size_t count_{0};
    size_t head_{0};

    // 32-bit fields
    // Bits owned by a live InboxSlot, plus the ring's own bit. Guarded by mutex_; catches a
    // copy-pasted bind() reusing a bit, which would otherwise drop wakeups intermittently.
    uint32_t claimed_bits_{INBOX_TOPIC_EVENTS};
    std::atomic<uint32_t> pending_{0};
};

/// @brief Pushes a payload-free lifecycle event, logging a drop if the ring is full
///
/// Shared by the role stream-event and cleared-event producers. `epoch` stamps the producing
/// role's teardown generation onto the event for event_is_current() to check; it is required,
/// not defaulted, because an event stamped 0 by omission reads as "the role was never torn
/// down". `error_level` logs the drop at ERROR rather than WARN: use it for events whose loss
/// wedges the stream (player START/END), not for the idempotent CLEARED events. `serial` is the
/// event's InboxEvent::serial.
inline void push_event_or_log(Inbox* inbox, InboxEventType type, uint8_t code, const char* tag,
                              const char* what, uint32_t epoch, bool error_level = false,
                              uint16_t serial = 0) {
    InboxEvent event{};
    event.type = type;
    event.code = code;
    event.serial = serial;
    event.epoch = epoch;
    if (inbox == nullptr || !inbox->push_event(event)) {
        if (error_level) {
            SS_LOGE(tag, "Inbox event ring full; dropping %s", what);
        } else {
            SS_LOGW(tag, "Inbox event ring full; dropping %s", what);
        }
    }
}

/// @brief Whether count `a` is later than `b`, across the unsigned counter's wrap
///
/// For counters that only count up: role teardown generations (one per cleanup()), stream
/// ordinals (one per stream/start) and high-performance tickets. The signed difference orders any
/// two less than half the counter's range apart, far more than are ever in flight.
template <typename T>
constexpr bool count_after(T a, T b) {
    return static_cast<std::make_signed_t<T>>(static_cast<T>(a - b)) > 0;
}

/// @brief Whether a ring event is still current for the role that produced it
///
/// The consumer half of push_event_or_log()'s `epoch`. A role bumps its teardown generation when
/// it is stopped (a lost connection, or a server/activate that removes the role), so an event the
/// ring still holds from before that teardown carries the older generation and must not be acted
/// on: delivering a stream START queued before a teardown would re-arm the producer the teardown
/// just stopped.
inline bool event_is_current(uint32_t event_epoch, uint32_t role_epoch, const char* tag,
                             const char* what) {
    if (event_epoch == role_epoch) {
        return true;
    }
    SS_LOGD(tag, "Discarding %s queued before the role was stopped", what);
    return false;
}

// ============================================================================
// InboxSlot
// ============================================================================

/**
 * @brief Latest-value slot bound to a shared Inbox, for state the main loop reads
 *
 * Owns its T storage and dirty flag but has no mutex of its own: every operation locks the
 * bound Inbox's shared mutex and, while holding it, keeps the slot's owned topic bit in sync
 * with its dirty flag. A topic bit must be owned by exactly one slot (or the event ring).
 * Sharing a bit between two slots would let draining one clear the bit while the other still
 * has pending content, silently losing that endpoint's next wakeup.
 *
 * @code
 * Inbox inbox;
 * InboxSlot<int> slot(inbox, INBOX_TOPIC_GROUP);
 *
 * slot.write(42);
 *
 * int val;
 * if (slot.take(val)) { ... }
 * @endcode
 */
template <typename T>
class InboxSlot {
public:
    InboxSlot() = default;

    /// @brief Constructs and immediately binds to `inbox` (see bind())
    InboxSlot(Inbox& inbox, uint32_t topic_bit) {
        this->bind(inbox, topic_bit);
    }

    ~InboxSlot() {
        // Release the bit claim so a replacement slot (e.g. a role re-added before start) can
        // bind it. Requires the bound Inbox to outlive this slot.
        if (this->inbox_ != nullptr) {
            // Clear the owned topic bit if a value is still pending. release_bit() only drops
            // the claim; without this a slot destroyed while dirty would leave its bit set in
            // pending_ forever, a phantom wakeup a replacement slot inherits but never clears.
            {
                std::lock_guard<std::mutex> lock(this->inbox_->mutex_);
                if (this->dirty_) {
                    this->inbox_->clear_bit_locked(this->topic_bit_);
                }
            }
            this->inbox_->release_bit(this->topic_bit_);
        }
    }

    // Not copyable or movable
    InboxSlot(const InboxSlot&) = delete;
    InboxSlot& operator=(const InboxSlot&) = delete;

    /// @brief Binds this slot to a shared Inbox and the topic bit it exclusively owns
    ///
    /// Must be called exactly once before any other method is used.
    /// @param inbox Shared Inbox whose mutex this slot locks for every operation. Must outlive
    /// this slot (the destructor releases the bit claim).
    /// @param topic_bit Single INBOX_TOPIC_* bit this slot exclusively owns; debug-asserted via
    /// Inbox::claim_bit().
    void bind(Inbox& inbox, uint32_t topic_bit) {
        assert(topic_bit != 0 && (topic_bit & (topic_bit - 1)) == 0 &&
               "InboxSlot topic_bit must be exactly one bit");
        assert(this->inbox_ == nullptr && "InboxSlot bound twice");
        inbox.claim_bit(topic_bit);
        this->inbox_ = &inbox;
        this->topic_bit_ = topic_bit;
    }

    /// @brief Overwrite the slot with a new value (latest-wins)
    /// @param value The new value to store.
    void write(T value) {
        if (!this->check_bound()) {
            return;
        }
        std::lock_guard<std::mutex> lock(this->inbox_->mutex_);
        this->slot_ = std::move(value);
        this->dirty_ = true;
        this->inbox_->set_bit_locked(this->topic_bit_);
    }

    /// @brief Merge a delta into the slot using a callable: fn(T& current, T&& delta)
    ///
    /// `fn` runs while the Inbox mutex is held, so it must be a pure data operation (no
    /// callbacks into application code); see the Inbox class documentation.
    /// @param fn Callable that merges `delta` into the current slot value.
    /// @param delta The new partial value to merge in.
    template <typename MergeFn>
    // NOLINTNEXTLINE(performance-unnecessary-value-param): delta is moved into fn, unseen by tidy
    void merge(MergeFn&& fn, T delta) {
        this->update([&fn, &delta](T& current) { fn(current, std::move(delta)); });
    }

    /// @brief Changes the slot value in place with fn(T& current) and marks it pending. `fn` runs
    /// under the Inbox mutex: a pure data operation only. Unlike merge() it takes no value, so a
    /// caller holding a large payload hands it over by reference instead of by another copy.
    template <typename UpdateFn>
    void update(UpdateFn&& fn) {
        if (!this->check_bound()) {
            return;
        }
        std::lock_guard<std::mutex> lock(this->inbox_->mutex_);
        fn(this->slot_);
        this->dirty_ = true;
        this->inbox_->set_bit_locked(this->topic_bit_);
    }

    /// @brief Edits a pending value in place with fn(T& current), which returns whether anything
    /// is still pending; when it returns false the slot is cleaned and its topic bit cleared, so
    /// an edit that leaves nothing behind wakes no drain. A clean slot is left untouched. `fn`
    /// runs under the Inbox mutex: a pure data operation only.
    template <typename EditFn>
    void edit(EditFn&& fn) {
        if (!this->check_bound()) {
            return;
        }
        std::lock_guard<std::mutex> lock(this->inbox_->mutex_);
        if (!this->dirty_ || fn(this->slot_)) {
            return;
        }
        this->slot_ = T{};
        this->dirty_ = false;
        this->inbox_->clear_bit_locked(this->topic_bit_);
    }

    /// @brief Move the accumulated value out if dirty
    /// @return true if a value was taken, false if the slot was clean or unbound.
    bool take(T& out) {
        if (!this->check_bound()) {
            return false;
        }
        std::lock_guard<std::mutex> lock(this->inbox_->mutex_);
        if (!this->dirty_) {
            return false;
        }
        out = std::move(this->slot_);
        this->slot_ = T{};
        this->dirty_ = false;
        this->inbox_->clear_bit_locked(this->topic_bit_);
        return true;
    }

    /// @brief Discard any pending value
    void reset() {
        if (!this->check_bound()) {
            return;
        }
        std::lock_guard<std::mutex> lock(this->inbox_->mutex_);
        this->slot_ = T{};
        this->dirty_ = false;
        this->inbox_->clear_bit_locked(this->topic_bit_);
    }

private:
    /// Using a slot before bind() is a programming error: loud in debug, a safe false in release.
    bool check_bound() const {
        assert(this->inbox_ != nullptr && "InboxSlot used before bind()");
        return this->inbox_ != nullptr;
    }

    // Struct fields
    T slot_{};

    // Pointer fields
    Inbox* inbox_{nullptr};

    // 32-bit fields
    uint32_t topic_bit_{0};

    // 8-bit fields
    bool dirty_{false};
};

// ============================================================================
// GenerationSlot
// ============================================================================

/**
 * @brief InboxSlot whose payload carries the teardown generation of the role it was admitted
 * under
 *
 * A role's protocol-task handler writes its main-loop payload with the generation it loaded at
 * entry (see RoleTeardown in teardown_tracker.h), and the role's drain takes the payload together
 * with that stamp: it catches its own teardown half up to the role's current generation first, then
 * applies only a payload whose stamp is still current (see teardown_tracker.h). This is the one
 * place a stamp is attached, so no producer can write a payload without one.
 *
 * Every write and merge keeps the newest generation's content: a delta stamped with a later
 * generation than the pending content replaces it, and one stamped with an earlier generation
 * than the pending content is dropped, so a producer that lost a race with a teardown cannot
 * revive the content the teardown discarded. Lives on the shared Inbox mutex like every
 * InboxSlot; no lock of its own.
 */
template <typename T>
class GenerationSlot {
public:
    GenerationSlot() = default;

    /// @brief Binds to `inbox` and the topic bit this slot owns exclusively (see InboxSlot::bind())
    void bind(Inbox& inbox, uint32_t topic_bit) {
        this->slot_.bind(inbox, topic_bit);
    }

    /// @brief Overwrites the pending payload (latest-wins) unless it belongs to a later generation
    /// @param value The payload.
    /// @param generation The producing role's teardown generation the payload was admitted under.
    void write(T value, uint32_t generation) {
        this->merge([](T& current, T&& delta) { current = std::move(delta); }, std::move(value),
                    generation);
    }

    /// @brief Merges a delta into the pending payload with fn(T& current, T&& delta), under the
    /// rule in the class comment. `fn` runs under the Inbox mutex: a pure data operation only.
    /// The delta is taken and merged by reference: a by-value or stamped copy would put another
    /// payload, a metadata state's strings among them, on the protocol task's stack.
    template <typename MergeFn>
    void merge(MergeFn&& fn, T&& delta, uint32_t generation) {
        this->slot_.update([&fn, &delta, generation](Stamped& current) {
            if (current.held && current.generation != generation) {
                if (!count_after(generation, current.generation)) {
                    return;  // Older than what is pending: the teardown discarded it.
                }
                current.value = T{};
            }
            current.held = true;
            current.generation = generation;
            fn(current.value, std::move(delta));
        });
    }

    /// @brief Moves the pending payload and its stamp out
    /// @return false when nothing was pending.
    bool take(T& out, uint32_t& generation) {
        Stamped taken;
        if (!this->slot_.take(taken)) {
            return false;
        }
        out = std::move(taken.value);
        generation = taken.generation;
        return true;
    }

    /// @brief Discards the pending payload
    void reset() {
        this->slot_.reset();
    }

private:
    struct Stamped {
        T value{};
        uint32_t generation{0};
        /// Whether `value` holds a payload; false once taken or reset.
        bool held{false};
    };

    InboxSlot<Stamped> slot_;
};

}  // namespace sendspin
