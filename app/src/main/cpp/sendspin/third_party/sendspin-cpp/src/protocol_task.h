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

/// @file protocol_task.h
/// @brief The library-owned protocol thread and the bounded command queue that feeds it from the
/// main loop and consumer threads

#pragma once

#include "platform/event_flags.h"
#include "protocol_messages.h"
#include "sendspin/config.h"
#include "sendspin/controller_role.h"
#include "sendspin/types.h"

#include <atomic>
#include <cstddef>
#include <cstdint>
#include <functional>
#include <memory>
#include <mutex>
#include <optional>
#include <string>
#include <thread>
#include <type_traits>

namespace sendspin {

class SendspinConnection;

// ============================================================================
// Commands
// ============================================================================

/// @brief What a ProtocolCommand asks the protocol task to do
enum class ProtocolCommandType : uint8_t {
    ACCEPT_CONNECTION,        ///< A platform server delivered a WebSocket-upgraded connection
    SEND_CONTROLLER_COMMAND,  ///< SendspinClient::send_controller_command()
};

static_assert(std::is_trivially_copyable_v<ClientCommandControllerObject>,
              "a controller command crosses to the protocol task by copy");

/// @brief One request handed to the protocol task. Only the fields its type names are set. A
/// client/state snapshot is not a command (ProtocolTask::publish_state()), nor is a lifecycle
/// request (ProtocolTask::post_requests()). Written by the pushing thread (any thread for a
/// consumer request, a transport's delivery thread for an accept) and read by the protocol task, or
/// by the thread joining it at stop(); the queue's lock hands it over.
struct ProtocolCommand {
    // Struct fields
    /// SEND_CONTROLLER_COMMAND: the validated command, formatted on the protocol task. 24 bytes
    /// on a 32-bit target, in every queue slot: 384 bytes across the default queue
    /// (CONSUMER_COMMAND_BURST plus ACCEPT_SLOTS_PER_SOCKET for each of the default four sockets).
    ClientCommandControllerObject controller_command{};

    // Pointer fields
    /// ACCEPT_CONNECTION: the delivered connection.
    std::shared_ptr<SendspinConnection> connection{};

    // 16-bit fields
    /// SEND_CONTROLLER_COMMAND: the low 16 bits of the controller role's teardown generation the
    /// command was validated under (SendspinClient::handle_command() drops it once the role has
    /// been torn down since). With the type it fills the alignment padding after the pointer
    /// fields, so a slot is no larger for it.
    uint16_t controller_generation{0};

    // 8-bit fields
    ProtocolCommandType type{ProtocolCommandType::SEND_CONTROLLER_COMMAND};
};

// ============================================================================
// Lifecycle requests
// ============================================================================

/// @brief The pairing-window gesture a LifecycleRequests carries. Confirm and cancel are
/// opposites, so one value holds whichever of the two came last.
enum class PairingWindowRequest : uint8_t {
    NONE,     ///< No gesture since the last take
    CONFIRM,  ///< SendspinClient::confirm_pairing_window()
    CANCEL,   ///< SendspinClient::cancel_pairing_window()
};

/// @brief The consumer's lifecycle requests not yet applied by the protocol task
///
/// Each request is idempotent, latest-wins state rather than a step, so it is held in this one
/// slot beside the command queue (ProtocolTask::post_requests()) instead of taking a queue entry:
/// it is never refused, a repeat before the task takes it collapses into one application, and a
/// burst of sends cannot crowd it out. A default-constructed value requests nothing; a field left
/// at its default in a post leaves the waiting request of that kind as it was.
///
/// disconnect() and connect_to() resolve by call order, as two steps would: the task applies a
/// waiting disconnect before a waiting connect, so a connect_to() posted after a disconnect()
/// keeps both, while a disconnect() posted after a connect_to() drops the waiting connect, the
/// attempt it would have released.
struct LifecycleRequests {
    /// SendspinClient::connect_to(): the URL of the latest call, unless a later disconnect()
    /// dropped it.
    std::optional<std::string> connect_to{};
    /// SendspinClient::disconnect(): the goodbye reason of the latest call.
    std::optional<SendspinGoodbyeReason> disconnect{};
    /// The later of confirm_pairing_window() and cancel_pairing_window().
    PairingWindowRequest pairing_window{PairingWindowRequest::NONE};
    /// SendspinClient::leave().
    bool leave{false};
    /// SendspinClient::set_unpaired_access_enabled(): apply the setting the client's flag holds
    /// when the task takes the request, which the call stored before posting.
    bool unpaired_access_changed{false};
};

// ============================================================================
// ProtocolTask
// ============================================================================

/**
 * @brief The thread that owns every connection and performs all protocol work
 *
 * Runs the tick the client hands to start() whenever it is woken (a command, a state snapshot,
 * a lifecycle request, an inbound ring item, a transport close) and when the tick's own next
 * deadline passes. The tick returns the milliseconds until its earliest timer deadline, or
 * NO_DEADLINE, and the task waits for a wake for that long, so an idle task with no timer pending
 * does not run at all.
 *
 * Lifecycle matches the role threads: start() clears every flag and spawns the thread; stop()
 * sets COMMAND_STOP, wakes the wait, lets the thread run one final tick so the commands queued
 * and the lifecycle requests posted before the stop are seen by the task, and joins it. Commands
 * pushed and requests posted after that final tick stay for the joining thread, which takes
 * them (take_command(), take_requests()) or drops them (drop_commands()).
 */
class ProtocolTask {
public:
    /// What a tick returns when none of its timers is pending: the task then waits for a wake
    /// alone. Every source of work wakes the task (wake(), push_command(), publish_state(),
    /// post_requests()), and every timer-driven step reports its own deadline, the
    /// network-readiness poll included, so no periodic re-evaluation is needed.
    static constexpr uint32_t NO_DEADLINE = UINT32_MAX;

    /// Queue slots for consumer sends, each a SEND_CONTROLLER_COMMAND
    /// (ControllerRole::send_command()) issued between two drains of the queue. A discrete
    /// gesture (play, next) is one send, but a volume slider sends one per input event, about six
    /// in a 100 ms window at a 60 Hz input rate, which is the stall this covers: eight holds such
    /// a window plus a gesture issued during it. A control that sends faster than the task
    /// drains, such as a rotary encoder sending a step per detent through a longer stall, still
    /// overruns it. A send past the burst is refused and push_command() returns false, a drop the
    /// client must pass back to whoever called send_command() rather than swallow. A client/state
    /// snapshot (publish_state()) and the lifecycle requests, connect_to() among them
    /// (post_requests()), take no slot, and a transport close is out of band (InboundGate), so
    /// none of them counts.
    static constexpr size_t CONSUMER_COMMAND_BURST = 8;

    /// Accept slots reserved per socket of SendspinClientConfig::server_max_connections. A
    /// socket can close out of band while its accept is still queued, and the platform server
    /// can then deliver an accept for the socket that replaces it, so a queue the task has not
    /// drained can hold one accept per socket plus one per socket that churned. Two per socket
    /// covers a close and a reopen of every socket between two drains of the queue; churn
    /// beyond that while the task is stalled refuses the accept (push_command() returns false
    /// and leaves the connection with the caller).
    static constexpr size_t ACCEPT_SLOTS_PER_SOCKET = 2;

    /// @brief The protocol work; runs on the protocol task only
    /// @return Milliseconds until the tick's earliest timer deadline (0 runs it again at once), or
    ///         NO_DEADLINE.
    using Tick = std::function<uint32_t()>;

    /// @brief Creates the command queue: CONSUMER_COMMAND_BURST consumer slots plus
    /// ACCEPT_SLOTS_PER_SOCKET accept slots per socket, and the event flags every wake sets
    /// @param server_max_connections The client's SendspinClientConfig::server_max_connections.
    explicit ProtocolTask(size_t server_max_connections);
    ~ProtocolTask();

    ProtocolTask(const ProtocolTask&) = delete;
    ProtocolTask& operator=(const ProtocolTask&) = delete;

    /// @brief Spawns the thread, which runs `tick` once at once and then on every wake or
    /// deadline. Main loop only.
    /// @param tick The protocol work.
    /// @param stack_size Task stack size in bytes (ESP-IDF only).
    /// @param priority FreeRTOS task priority (ESP-IDF only).
    /// @param stack_in_psram Allocate the stack in PSRAM (ESP-IDF only).
    /// @return false when the constructor could not create the event flags or the task already
    ///         runs.
    bool start(Tick tick, size_t stack_size, unsigned priority, bool stack_in_psram);

    /// @brief Signals the thread, waits for its final tick and joins it, then drops the state
    /// snapshot still waiting. Commands and lifecycle requests stay for the caller
    /// (take_command(), take_requests(), drop_commands()). No-op when the thread is not running.
    /// Main loop only.
    void stop();

    /// @brief Drops every queued command, one at a time, outside the queue lock, and the
    /// lifecycle requests still waiting. Main loop only, with the thread joined; the destructor
    /// runs it too.
    void drop_commands();

    /// @brief Closes admission (see accepting_). An accept is either queued before it, and taken
    /// by a tick of the task (its final one at the latest), or refused at its push, with nothing
    /// in between. Main loop, from ConnectionManager::close_admission() before stop().
    void close_accepts();

    /// @brief Opens admission again; see close_accepts(). Main loop, from
    /// ConnectionManager::start() before the platform server can deliver.
    void open_accepts();

    /// @brief Whether admission is open (see accepting_). Any thread.
    bool is_accepting() const {
        return this->accepting_.load(std::memory_order_acquire);
    }

    /// @brief Whether the thread is running. Main loop only.
    bool is_running() const {
        return this->thread_.joinable();
    }

    /// @brief Wakes the task out of its wait so the tick runs. Any thread.
    void wake();

    /// @brief Queues a command and wakes the task. Any thread.
    /// @return false when the command's slots are all taken (consumer commands share
    ///         CONSUMER_COMMAND_BURST slots, accepts their reserved ones) or, for an accept,
    ///         admission is closed (close_accepts()): the refusal is logged
    ///         and the command is left with the caller unchanged, so the connection an accept
    ///         carries is released on the caller's thread, outside the queue lock.
    bool push_command(ProtocolCommand&& command);

    /// @brief Takes the oldest queued command. Protocol task only, or the thread that joined it.
    /// @param[out] out Receives the command; whatever it held before is released first, outside
    ///        the queue lock.
    /// @return false when the queue is empty.
    bool take_command(ProtocolCommand& out);

    /// @brief Replaces the latest client/state snapshot and wakes the task. Main loop
    /// (SendspinClient::publish_state() and start()).
    ///
    /// One slot beside the command queue: a snapshot is never refused, only the newest matters,
    /// and the one it replaces is destroyed outside the lock. The tick applies it after draining
    /// the command queue; its order relative to queued commands is not a semantic requirement,
    /// since a snapshot describes the client's whole state rather than a step.
    void publish_state(ClientStateMessage state);

    /// @brief Takes the latest snapshot, if one was published since the last take. Protocol task
    /// only.
    /// @param[out] out Receives the snapshot; what it held before is destroyed outside the lock.
    /// @return false when no snapshot is waiting.
    bool take_state(ClientStateMessage& out);

    /// @brief Merges lifecycle requests into the waiting ones and wakes the task. Any thread.
    ///
    /// One slot beside the command queue, like publish_state(): a request is never refused. Each
    /// field `requests` sets replaces the waiting request of its kind (the latest URL, the
    /// latest disconnect reason, the latter of a confirm and a cancel), and a disconnect drops a
    /// waiting connect (see LifecycleRequests); one left at its default leaves it as it was. The
    /// URL a post replaces or drops is destroyed outside the lock. The tick applies the requests
    /// ahead of the queued commands (SendspinClient::protocol_tick()).
    /// @param requests The requests to add, usually one field set with a designated initializer.
    void post_requests(LifecycleRequests requests);

    /// @brief Takes the waiting lifecycle requests, if any were posted since the last take, and
    /// empties the slot, so each post is applied at most once. Protocol task only, or the thread
    /// that joined it.
    /// @param[out] out Receives the requests.
    /// @return false when no request is waiting; out is then left as it was.
    bool take_requests(LifecycleRequests& out);

private:
    /// Event flag bits
    static constexpr uint32_t COMMAND_STOP = 1U << 0;
    static constexpr uint32_t WORK_PENDING = 1U << 1;

    /// @brief Thread body: tick, wait for a wake or the tick's deadline, repeat; on COMMAND_STOP
    /// run one final tick and exit
    static void thread_entry(ProtocolTask* self);

    /// @brief Releases what a command holds and resets it, without a temporary command
    static void clear_command(ProtocolCommand& command);

    // Struct fields
    /// Ring of queued commands, capacity_ entries. Pushed from any thread, taken by the protocol
    /// task (or the thread that joined it); guarded by command_mutex_. A slot is only ever
    /// moved into while empty (moved-from or default), so no heap memory is freed under the
    /// lock.
    std::unique_ptr<ProtocolCommand[]> commands_;
    /// Guards commands_, the counts, latest_state_, requests_ and the writes to accepting_. A
    /// leaf: no lock is taken and nothing heap-backed is destroyed under it.
    std::mutex command_mutex_;
    /// Created in the constructor, before any thread that wakes the task exists.
    EventFlags event_flags_;
    /// The newest client/state snapshot not yet taken. Written by the main loop, taken by the
    /// protocol task; guarded by command_mutex_ and only ever swapped under it.
    std::optional<ClientStateMessage> latest_state_;
    std::thread thread_;
    /// The protocol work, set by start() before the thread exists and read only by the thread.
    Tick tick_;
    /// The lifecycle requests posted since the last take. Merged from any thread, taken by the
    /// protocol task; guarded by command_mutex_.
    LifecycleRequests requests_;

    // size_t fields
    /// Slots reserved for ACCEPT_CONNECTION: ACCEPT_SLOTS_PER_SOCKET per socket. Fixed at
    /// construction, never written after.
    size_t accept_slots_;
    /// accept_slots_ + CONSUMER_COMMAND_BURST. Fixed at construction, never written after.
    size_t capacity_;
    /// Queued commands in total, and the accepts among them. Guarded by command_mutex_.
    size_t command_count_{0};
    size_t command_head_{0};
    size_t accepts_queued_{0};

    // 8-bit fields
    /// Whether admission is open. While false, push_command() refuses an accept, and the task
    /// refuses the accepts already queued, drops consumer requests and runs the shutdown pass.
    /// Written by close_accepts() and open_accepts() on the main loop under command_mutex_, so
    /// push_command() reads it in the critical section that queues or refuses an accept; read
    /// lock-free (is_accepting()) everywhere else. True from construction, unobservable before
    /// ConnectionManager::start(): no platform server exists to deliver an accept.
    std::atomic<bool> accepting_{true};
};

}  // namespace sendspin
