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

/// @file teardown_tracker.h
/// @brief The main-loop half of a role's two-half teardown, run once per teardown generation

#pragma once

#include "inbox.h"

#include <atomic>
#include <cstdint>

namespace sendspin {

/**
 * @brief Records which of a role's teardowns the main loop has caught up with
 *
 * A role is torn down in two halves (docs/internals.md "Cleanup"). The protocol-task half,
 * cleanup(), bumps the role's `cleanup_generation`, resets what the protocol task and the role
 * threads reach, and stamps everything it queues from then on with the new generation. The
 * main-loop half, the role's complete_teardown(), resets the state only the main loop touches
 * and delivers the role's clear callback. It must run once per teardown, and before the main loop
 * acts on anything stamped with the new generation, so a clear always lands ahead of the state
 * the next connection sends.
 *
 * Every main-loop path that is about to act on something stamped with a generation calls
 * catch_up_teardown() with it first: the event drain with a stamped event's generation, and a
 * role drain with the role's current generation right after it has taken its GenerationSlot
 * payload (inbox.h). Several teardowns between two catch-ups collapse into one main-loop half.
 *
 * Main loop only: written and read on the main loop, so it needs no lock or atomic.
 */
class TeardownTracker {
public:
    /// @brief Whether `generation` is a teardown the main loop has not caught up with yet
    bool pending(uint32_t generation) const {
        return count_after(generation, this->completed_);
    }

    /// @brief Marks `generation` caught up
    /// @return true when it was pending: the caller runs its main-loop half now. false for a
    ///         generation already caught up, or one older than it (a stamp a later teardown has
    ///         already overtaken).
    [[nodiscard]] bool advance(uint32_t generation) {
        if (!this->pending(generation)) {
            return false;
        }
        this->completed_ = generation;
        return true;
    }

private:
    /// The latest teardown generation whose main-loop half has run.
    uint32_t completed_{0};
};

/**
 * @brief A role's teardown state, shared by every role's Impl: its teardown generation, the
 * check each drain makes against it, and its TeardownTracker
 *
 * cleanup() bumps cleanup_generation and stamps everything the role queues from then on with the
 * new value: its events, its slot payloads, the items it hands a role thread. Each protocol-task
 * handler loads the generation once at entry and stamps what it queues with it. A teardown runs
 * on the protocol task between two handlers, or from stop() once that task is joined, so the
 * generation stays current for the whole handler and the handler does not check it. The stamp
 * protects the threads that consume what the handler queued: the drains check a payload's stamp
 * with accepts() (event_is_current(), GenerationSlot), and a role thread an item's
 * (InboundConsumer::take()), so a teardown that runs before they take it discards it; a drain also
 * uses accepts() to detect a listener callback that re-entered teardown.
 */
struct RoleTeardown {
    /// @brief Whether an effect stamped with `generation` may still be applied
    /// @param generation The counter value the effect was stamped with.
    bool accepts(uint32_t generation) const {
        return generation == this->cleanup_generation.load(std::memory_order_acquire);
    }

    TeardownTracker teardown;  ///< Main loop only.
    /// Written by cleanup() on the protocol task, or on the main loop in SendspinClient::stop()
    /// once the task is joined; read on the main loop, the protocol task, the role's own thread
    /// and, for the controller, by send_command() on any thread.
    std::atomic<uint32_t> cleanup_generation{0};
};

/// @brief Runs `role`'s main-loop teardown half, complete_teardown(), if `generation` is a
/// teardown `role.teardown` has not caught up with. The one chokepoint every role's catch-up
/// goes through. Main loop only.
template <typename RoleImpl>
void catch_up_teardown(RoleImpl& role, uint32_t generation) {
    if (role.teardown.advance(generation)) {
        role.complete_teardown();
    }
}

/// @brief The head of every slot role's drain: takes the role's GenerationSlot payload, catches
/// the role up to its current teardown generation (catch_up_teardown()), then keeps the payload
/// only if it is stamped with that generation, dropping an older one with a debug log
///
/// Taken before the catch-up: a teardown that ran before the take is caught up here (its clear
/// fires first) and drops a payload stamped before it; one that runs after the take is caught up
/// by the next drain, behind what this drain applies. The caller then checks
/// `role.accepts(generation)`, which also catches a clear callback that re-entered teardown (a
/// listener calling stop()). Main loop only.
/// @param[out] have Whether `out` holds a payload stamped with the returned generation.
/// @param what What the payload is, for the drop's log line.
/// @return The role's generation the catch-up ran to.
template <typename RoleImpl, typename T>
uint32_t take_current_payload(RoleImpl& role, GenerationSlot<T>& slot, T& out, bool& have,
                              const char* tag, const char* what) {
    uint32_t stamp = 0;
    have = slot.take(out, stamp);
    const uint32_t generation = role.cleanup_generation.load(std::memory_order_acquire);
    catch_up_teardown(role, generation);
    if (have && stamp != generation) {
        SS_LOGD(tag, "Dropping %s queued before the role was torn down", what);
        have = false;
    }
    return generation;
}

}  // namespace sendspin
