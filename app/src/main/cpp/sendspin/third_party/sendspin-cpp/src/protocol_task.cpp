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

#include "protocol_task.h"

#include "platform/logging.h"
#include "platform/thread.h"

#include <utility>

namespace sendspin {

static const char* const TAG = "sendspin.protocol";

// ============================================================================
// Lifecycle
// ============================================================================

ProtocolTask::ProtocolTask(size_t server_max_connections)
    : accept_slots_(ACCEPT_SLOTS_PER_SOCKET * server_max_connections),
      capacity_(this->accept_slots_ + CONSUMER_COMMAND_BURST) {
    this->commands_ = std::make_unique<ProtocolCommand[]>(this->capacity_);
    // Here rather than in start(): the client starts its connection manager, whose server can
    // accept (and wake this task from a transport thread) before the task itself starts.
    if (!this->event_flags_.create()) {
        SS_LOGE(TAG, "Couldn't create the protocol task's event flags");
    }
}

ProtocolTask::~ProtocolTask() {
    this->stop();
    this->drop_commands();
}

bool ProtocolTask::start(Tick tick, size_t stack_size, unsigned priority, bool stack_in_psram) {
    if (this->thread_.joinable()) {
        SS_LOGW(TAG, "Protocol task already running");
        return false;
    }
    if (!this->event_flags_.is_created()) {
        SS_LOGE(TAG, "Protocol task has no event flags; not starting");
        return false;
    }
    // A restart inherits nothing: no stop or wake left over from the previous run.
    this->event_flags_.clear_all();
    this->tick_ = std::move(tick);

    platform_configure_thread("SsProto", stack_size, static_cast<int>(priority), stack_in_psram);
    this->thread_ = std::thread(thread_entry, this);
    return true;
}

void ProtocolTask::stop() {
    if (!this->thread_.joinable()) {
        return;
    }
    // Setting the bit is the wake: the thread's wait returns on it and runs its final tick.
    this->event_flags_.set(COMMAND_STOP);
    this->thread_.join();

    // Joined. A snapshot the final tick did not take describes a run that is over. Commands
    // pushed and requests posted after the final tick stay for the joining thread
    // (take_command(), take_requests(), drop_commands()), which releases what a command holds
    // off this queue's lock.
    std::optional<ClientStateMessage> dropped;
    {
        std::lock_guard<std::mutex> lock(this->command_mutex_);
        dropped.swap(this->latest_state_);
    }
}

void ProtocolTask::drop_commands() {
    // One command at a time through one reused local: each may hold a connection whose release
    // must not run under the queue lock.
    ProtocolCommand command;
    while (this->take_command(command)) {}
    clear_command(command);
    LifecycleRequests dropped;
    (void)this->take_requests(dropped);
}

void ProtocolTask::close_accepts() {
    std::lock_guard<std::mutex> lock(this->command_mutex_);
    this->accepting_.store(false, std::memory_order_release);
}

void ProtocolTask::open_accepts() {
    std::lock_guard<std::mutex> lock(this->command_mutex_);
    this->accepting_.store(true, std::memory_order_release);
}

void ProtocolTask::thread_entry(ProtocolTask* self) {
    for (;;) {
        const uint32_t next_deadline_ms = self->tick_();
        // NO_DEADLINE is UINT32_MAX, which EventFlags::wait() takes as no timeout.
        const uint32_t bits =
            self->event_flags_.wait(COMMAND_STOP | WORK_PENDING, false, true, next_deadline_ms);
        if ((bits & COMMAND_STOP) != 0) {
            // One final tick, so the work queued and posted before stop() was called is seen by
            // the task.
            self->tick_();
            return;
        }
    }
}

void ProtocolTask::wake() {
    // Created in the constructor, before any thread that wakes the task exists; a failed create
    // leaves none to set, and start() then refuses to run the task.
    if (this->event_flags_.is_created()) {
        this->event_flags_.set(WORK_PENDING);
    }
}

// ============================================================================
// Command queue
// ============================================================================

bool ProtocolTask::push_command(ProtocolCommand&& command) {
    const ProtocolCommandType type = command.type;
    const bool is_accept = type == ProtocolCommandType::ACCEPT_CONNECTION;
    bool queued = false;
    bool accepts_closed = false;
    {
        std::lock_guard<std::mutex> lock(this->command_mutex_);
        accepts_closed = is_accept && !this->accepting_.load(std::memory_order_relaxed);
        const size_t others_queued = this->command_count_ - this->accepts_queued_;
        const bool has_room = is_accept
                                  ? !accepts_closed && this->accepts_queued_ < this->accept_slots_
                                  : others_queued < CONSUMER_COMMAND_BURST;
        if (has_room) {
            // The slot is empty (moved-from or default), so this move frees nothing.
            this->commands_[(this->command_head_ + this->command_count_) % this->capacity_] =
                std::move(command);
            ++this->command_count_;
            if (is_accept) {
                ++this->accepts_queued_;
            }
            queued = true;
        }
    }
    if (!queued) {
        if (accepts_closed) {
            SS_LOGD(TAG, "Stopping; refusing a delivered connection");
        } else if (is_accept) {
            SS_LOGE(TAG, "All %zu accept slots are taken; refusing a delivered connection",
                    this->accept_slots_);
        } else {
            SS_LOGW(TAG, "Protocol command queue full (%zu); dropping a command of type %d",
                    CONSUMER_COMMAND_BURST, static_cast<int>(type));
        }
        return false;
    }
    this->wake();
    return true;
}

bool ProtocolTask::take_command(ProtocolCommand& out) {
    // Released before the lock is taken, so the move below lands in an empty command.
    clear_command(out);
    std::lock_guard<std::mutex> lock(this->command_mutex_);
    if (this->command_count_ == 0) {
        return false;
    }
    // Leaves the slot moved-from: empty, holding nothing heap-backed.
    out = std::move(this->commands_[this->command_head_]);
    this->command_head_ = (this->command_head_ + 1) % this->capacity_;
    --this->command_count_;
    if (out.type == ProtocolCommandType::ACCEPT_CONNECTION) {
        --this->accepts_queued_;
    }
    return true;
}

void ProtocolTask::clear_command(ProtocolCommand& command) {
    command.connection.reset();
    command.controller_command = {};
    command.controller_generation = 0;
    command.type = ProtocolCommandType::SEND_CONTROLLER_COMMAND;
}

// ============================================================================
// State slot
// ============================================================================

void ProtocolTask::publish_state(ClientStateMessage state) {
    std::optional<ClientStateMessage> replaced(std::move(state));
    {
        std::lock_guard<std::mutex> lock(this->command_mutex_);
        replaced.swap(this->latest_state_);
    }
    // `replaced` now holds the superseded snapshot, destroyed here outside the lock.
    this->wake();
}

bool ProtocolTask::take_state(ClientStateMessage& out) {
    std::optional<ClientStateMessage> taken;
    {
        std::lock_guard<std::mutex> lock(this->command_mutex_);
        taken.swap(this->latest_state_);
    }
    if (!taken.has_value()) {
        return false;
    }
    out = std::move(*taken);
    return true;
}

// ============================================================================
// Lifecycle requests
// ============================================================================

void ProtocolTask::post_requests(LifecycleRequests requests) {
    // Receives a waiting URL this post drops, so it is destroyed after the lock is released.
    std::optional<std::string> dropped;
    {
        std::lock_guard<std::mutex> lock(this->command_mutex_);
        LifecycleRequests& waiting = this->requests_;
        if (requests.disconnect.has_value()) {
            waiting.disconnect = requests.disconnect;
            // A disconnect after a connect_to() cancels the attempt the connect would open.
            dropped.swap(waiting.connect_to);
        }
        if (requests.connect_to.has_value()) {
            // The replaced URL lands in `requests`, destroyed after the lock is released.
            waiting.connect_to.swap(requests.connect_to);
        }
        if (requests.pairing_window != PairingWindowRequest::NONE) {
            waiting.pairing_window = requests.pairing_window;
        }
        waiting.leave = waiting.leave || requests.leave;
        waiting.unpaired_access_changed =
            waiting.unpaired_access_changed || requests.unpaired_access_changed;
    }
    this->wake();
}

bool ProtocolTask::take_requests(LifecycleRequests& out) {
    LifecycleRequests taken;
    {
        std::lock_guard<std::mutex> lock(this->command_mutex_);
        std::swap(taken, this->requests_);
    }
    if (!taken.connect_to.has_value() && !taken.disconnect.has_value() &&
        taken.pairing_window == PairingWindowRequest::NONE && !taken.leave &&
        !taken.unpaired_access_changed) {
        return false;
    }
    out = std::move(taken);
    return true;
}

}  // namespace sendspin
