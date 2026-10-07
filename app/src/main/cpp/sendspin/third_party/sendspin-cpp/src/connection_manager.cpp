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

#include "connection_manager.h"

#include "admission.h"
#include "client_connection.h"
#include "connection.h"
#include "constants.h"
#include "crypto/constants.h"
#include "pairing_offers.h"
#include "platform/logging.h"
#include "platform/time.h"
#include "platform/types.h"
#include "protocol_messages.h"
#include "protocol_task.h"
#include "record_store.h"
#include "sendspin/config.h"
#include "sendspin/types.h"
#include "server_connection.h"
#include "time_burst.h"
#include "time_filter.h"
#include "ws_server.h"

#include <algorithm>
#include <array>
#include <cassert>
#include <cinttypes>
#include <memory>
#include <string>
#include <utility>

namespace sendspin {

static const char* const TAG = "sendspin.conn_mgr";

static constexpr int64_t WS_SERVER_START_RETRY_MS = 5000LL;
static constexpr int64_t WS_SERVER_START_RETRY_US = WS_SERVER_START_RETRY_MS * US_PER_MS;

/// @brief Stands in for an active_roles set the server left out or the client refuses to keep.
static const std::vector<std::string> EMPTY_ROLES{};

/// @brief Refuses an activation the client cannot act on
///
/// pairing.md "Client <-> Server: pair/abort" answers a method or format the client does not offer
/// with pair/abort rather than a close, so the activation is never applied: the connection keeps
/// the activities and roles it had.
///
/// Answering here rather than ignoring the activate matters: a server that never hears back sits
/// waiting for the device forever, with nothing on either side to explain the stall.
/// @param conn The connection the activation arrived on.
/// @param why What the activation asked for that the client cannot act on.
/// @param method The pairing method the activation named, for the diagnostic.
/// @param arena The client's JSON arena, to build the pair/abort in.
static void refuse_activate(SendspinConnection* conn, const char* why, const char* method,
                            SendspinArenaAllocator& arena) {
    SS_LOGW(TAG,
            "server/activate %s (pairing.method=%s) for server_id=%s; replying "
            "pair/abort(method_not_supported), connection stays open",
            why, method, conn->get_server_id().c_str());
    conn->send_app_json(format_pair_abort_message(PairAbortReason::METHOD_NOT_SUPPORTED, arena));
}

/// @brief Transport-establishment progress of a nursery connection, used for reap diagnostics
///
/// Derived on demand from the connection's proven flags rather than stored, so it can never go
/// stale. Inbound entries are WS_UP or later by construction (delivered only after their upgrade);
/// only an outbound connect_to() still awaiting DNS/TCP resolve can be TCP_OPEN. See the
/// lifecycle-flag axes note above SendspinConnection's atomic flag members in connection.h.
enum class SetupStage : uint8_t {
    TCP_OPEN,
    WS_UP,
    NOISE_PENDING,
    NOISE_DONE,
    HELLO_SENT,
    ACTIVATE_PENDING,
    ESTABLISHED
};

static SetupStage setup_stage(const SendspinConnection& conn) {
    if (conn.is_operational()) {
        return SetupStage::ESTABLISHED;
    }
    if (conn.is_handshake_complete()) {
        return SetupStage::ACTIVATE_PENDING;
    }
    if (conn.has_client_hello_sent()) {
        return SetupStage::HELLO_SENT;
    }
    if (conn.is_noise_handshake_complete()) {
        return SetupStage::NOISE_DONE;
    }
    if (conn.has_noise_handshake()) {
        return SetupStage::NOISE_PENDING;
    }
    if (conn.is_ws_upgraded()) {
        return SetupStage::WS_UP;
    }
    return SetupStage::TCP_OPEN;
}

static const char* to_cstr(SetupStage stage) {
    switch (stage) {
        case SetupStage::TCP_OPEN:
            return "TCP_OPEN";
        case SetupStage::WS_UP:
            return "WS_UP";
        case SetupStage::NOISE_PENDING:
            return "NOISE_PENDING";
        case SetupStage::NOISE_DONE:
            return "NOISE_DONE";
        case SetupStage::HELLO_SENT:
            return "HELLO_SENT";
        case SetupStage::ACTIVATE_PENDING:
            return "ACTIVATE_PENDING";
        case SetupStage::ESTABLISHED:
            return "ESTABLISHED";
    }
    return "UNKNOWN";
}

/// @brief The pairing method an applied server/activate selects, if it selects the pairing flow:
/// the PAIRING activity together with a pairing.method the client recognizes. Shared by every
/// site that must route such an activate into ConnectionManager::handle_enter_pairing().
/// PLAYBACK may ride along (messaging.md "server/activate" allows ['playback', 'pairing']), and
/// the connection then also takes the operational path (SendspinClient::on_handshake_complete()).
/// @return The selected method, or nullopt when the activate does not select pairing.
static std::optional<SendspinPairMethod> selected_pairing_method(
    const std::vector<SendspinActivity>& activities,
    const std::optional<SendspinPairMethod>& pairing_method) {
    if (!contains_activity(activities, SendspinActivity::PAIRING) || !pairing_method.has_value()) {
        return std::nullopt;
    }
    const SendspinPairMethod method = pairing_method.value();
    if (method == SendspinPairMethod::PAIRING_PSK ||
        method == SendspinPairMethod::DYNAMIC_PAIRING_CODE ||
        method == SendspinPairMethod::STATIC_PAIRING_CODE) {
        return method;
    }
    return std::nullopt;
}

// A cap under liveness_expired()'s 2^31 us range leaves minutes in which to see an expiry.
static_assert(SendspinClientConfig::MAX_LIVENESS_TIMEOUT_MS * US_PER_MS < INT32_MAX,
              "liveness cap exceeds the 32-bit arrival stamp's range");

int64_t resolve_liveness_timeout_ms(const SendspinClientConfig& config) {
    // The next message goes out after at most one inter-burst interval, and an unanswered one
    // times out after one response timeout. Not value_or(): it would evaluate the derivation,
    // which can overflow, even when unused.
    const int64_t timeout_ms =
        config.liveness_timeout_ms.has_value()
            ? config.liveness_timeout_ms.value()
            : (LIVENESS_TOLERATED_MISSES + 1) *
                  (config.time_burst_interval_ms + config.time_burst_response_timeout_ms);
    if (timeout_ms > SendspinClientConfig::MAX_LIVENESS_TIMEOUT_MS) {
        SS_LOGW(TAG, "Liveness timeout of %" PRId64 " ms exceeds the maximum, using %" PRId64 " ms",
                timeout_ms, SendspinClientConfig::MAX_LIVENESS_TIMEOUT_MS);
        return SendspinClientConfig::MAX_LIVENESS_TIMEOUT_MS;
    }
    return timeout_ms;
}

bool liveness_expired(int64_t now_us, uint32_t last_receive_us, int64_t timeout_us) {
    // Signed: an arrival stamped after now_us was read is a negative silence, not ~2^32 us.
    const auto silence_us = static_cast<int32_t>(static_cast<uint32_t>(now_us) - last_receive_us);
    return timeout_us > 0 && silence_us >= timeout_us;
}

int64_t liveness_remaining_us(int64_t now_us, uint32_t last_receive_us, int64_t timeout_us) {
    // The same signed 32-bit silence liveness_expired() measures.
    const auto silence_us = static_cast<int32_t>(static_cast<uint32_t>(now_us) - last_receive_us);
    return silence_us >= timeout_us ? 0 : timeout_us - silence_us;
}

// ============================================================================
// Constructor / Destructor
// ============================================================================

// Reading config_ here is safe: SendspinClient declares it before connection_manager_.
ConnectionManager::ConnectionManager(SendspinClient* client)
    : client_(client),
      liveness_timeout_us_(resolve_liveness_timeout_ms(client->config_) * US_PER_MS) {}

ConnectionManager::~ConnectionManager() {
    // The protocol task is joined (or never ran) and finish_stop() released what the shutdown pass
    // kept, so anything left here is released on the thread destroying the client. Detach first:
    // a destructor below can join its transport thread, which must not be parked on its gate.
    for (auto& entry : this->admitted_) {
        if (entry.conn != nullptr) {
            entry.conn->detach_inbound();
            entry.conn->set_admitted(false);
        }
    }
    for (auto& entry : this->nursery_) {
        entry.conn->detach_inbound();
    }
    for (auto& conn : this->closing_) {
        conn->detach_inbound();
    }
}

// ============================================================================
// Main loop
// ============================================================================

void ConnectionManager::start() {
    // A restart begins with no shutdown pass behind it and retries the server at once rather than
    // honoring a backoff from before the stop. The protocol task is not running, so these writes
    // reach it through its start.
    this->shutdown_done_ = false;
    this->shutdown_ui_ = {false, false};
    this->ws_server_start_retry_time_us_ = 0;

    if (this->ws_server_ == nullptr) {
        // First start: create the server object and configure it once. The config is immutable
        // for the client's lifetime, so a restart reuses these values along with the object.
        this->ws_server_ = std::make_unique<SendspinWsServer>();
        this->ws_server_->set_port(this->client_->config_.server_port);
        this->ws_server_->set_max_connections(this->client_->config_.server_max_connections);
        this->ws_server_->set_ctrl_port(this->client_->config_.httpd_ctrl_port);

        // Graceful rejection needs transport headroom: the manager can hold MAX_ADMITTED admitted
        // connections plus NURSERY_CAPACITY unproven ones, and rejecting a surplus peer with a
        // client/goodbye requires the transport to accept that peer's socket on top. Below this
        // bound the nursery-full goodbye path is unreachable; surplus peers are refused at accept
        // instead (and on ESP they wait unanswered in the TCP backlog, since httpd stops
        // accepting).
        constexpr size_t GRACEFUL_SOCKETS = MAX_ADMITTED + NURSERY_CAPACITY + 1;
        if (this->client_->config_.server_max_connections < GRACEFUL_SOCKETS) {
            SS_LOGW(TAG,
                    "server_max_connections (%u) is below %u (%u admitted + %u nursery + 1 spare); "
                    "surplus peers will be refused at accept instead of receiving a goodbye",
                    static_cast<unsigned>(this->client_->config_.server_max_connections),
                    static_cast<unsigned>(GRACEFUL_SOCKETS), static_cast<unsigned>(MAX_ADMITTED),
                    static_cast<unsigned>(NURSERY_CAPACITY));
        }

        this->ws_server_->set_new_connection_callback(
            [this](const std::shared_ptr<SendspinServerConnection>& conn) {
                return this->on_new_connection(conn);
            });
        // The server's own deadline (a pending upgrade) is reported by tick(); a new pending
        // session wakes the task so the deadline it reports covers it.
        ProtocolTask* task = this->client_->protocol_task_.get();
        this->ws_server_->set_wake_callback([task]() { task->wake(); });
    }

    this->client_->protocol_task_->open_accepts();
    // Started here when the network is already up, so the server is listening once start()
    // returns; otherwise the protocol task starts it once the provider reports ready.
    (void)this->maybe_start_ws_server(platform_time_us());
}

void ConnectionManager::close_admission() {
    this->client_->protocol_task_->close_accepts();
    this->client_->protocol_task_->wake();
}

PairingUiSnapshot ConnectionManager::finish_stop() {
    // The protocol task is joined. Its final tick ran the shutdown pass, unless it never ran, in
    // which case the slots are empty: only the task fills them.
    this->shutdown_done_ = true;

    // The shutdown pass's disconnect() already closed every connection it kept that was
    // connected, so the server stop's join does not wait out a peer that never closes. Every gate
    // is detached, so no transport thread the stop joins is parked on one, and one in a ring
    // acquire gives up within INBOUND_ACQUIRE_TIMEOUT_MS. The connections still parked for reaping
    // are closed here, before the stop, so one that opened after its release does not hold its
    // join; one still connecting is stopped by its destructor below.
    for (auto& entry : this->reaping_) {
        entry.conn->close_transport_now();
    }
    if (this->ws_server_ != nullptr) {
        this->ws_server_->stop();
    }
    // Released here, after the server stop: an outbound connection's destructor stops its
    // transport synchronously, up to its connect timeout for one still connecting.
    std::vector<std::shared_ptr<SendspinConnection>> releasing;
    releasing.swap(this->closing_);
    releasing.clear();
    this->reaping_.clear();

    const PairingUiSnapshot ui = this->shutdown_ui_;
    this->shutdown_ui_ = {false, false};
    return ui;
}

// ============================================================================
// Any thread
// ============================================================================

std::shared_ptr<SendspinTimeFilter> ConnectionManager::time_filter() const {
    std::lock_guard<std::mutex> lock(this->published_mutex_);
    return this->published_.time_filter;
}

std::optional<ServerInformationObject> ConnectionManager::server_information() const {
    std::lock_guard<std::mutex> lock(this->published_mutex_);
    return this->published_.server_information;
}

// ============================================================================
// Transport threads
// ============================================================================

bool ConnectionManager::on_new_connection(const std::shared_ptr<SendspinServerConnection>& conn) {
    // Refused unattached, so its transport drops everything and never waits on the gate.
    if (!conn->inbound_gate().is_created()) {
        SS_LOGE(TAG, "No event group for a new connection's inbound gate; refusing it");
        return false;
    }
    // On the transport's delivery thread, ahead of the connection's first frame and before the
    // protocol task can reach the connection: the push below publishes these writes to the task.
    conn->init_time_filter();
    conn->time_burst().configure(this->client_->config_.time_burst_size,
                                 this->client_->config_.time_burst_interval_ms,
                                 this->client_->config_.time_burst_response_timeout_ms);
    conn->set_inbound_buffer_location(this->client_->config_.inbound_ring_location);
    conn->set_noise_buffer_location(this->client_->config_.noise_buffer_location);
    conn->set_json_arena(this->json_arena());
    conn->attach_inbound(this->client_->inbound_ring_.get(), this->client_->protocol_task_.get());

    ProtocolCommand command;
    command.type = ProtocolCommandType::ACCEPT_CONNECTION;
    command.connection = conn;
    if (this->client_->protocol_task_->push_command(std::move(command))) {
        return true;
    }
    // Refused (push_command() logged it: the slots are full, or close_admission() closed
    // accepts): the transport drops what the peer sends from here, and the caller closes
    // the socket and releases the connection on its own close path. The delivery runs ahead of
    // the connection's first frame on the delivering thread, so nothing reached the ring before
    // the detach.
    conn->detach_inbound();
    return false;
}

// ============================================================================
// Protocol task: commands
// ============================================================================

void ConnectionManager::accept(std::shared_ptr<SendspinConnection> conn) {
    if (!this->is_accepting()) {
        this->refuse_accept(std::move(conn));
        return;
    }

    // Start the establish clock: the nursery scan reaps the connection if it does not complete
    // the hello handshake within NURSERY_ESTABLISH_TIMEOUT_US.
    conn->set_provisional_time_us(platform_time_us());

    // The newcomer has not completed the hello handshake, so it never touches an admitted slot;
    // it enters the bounded nursery and is admitted only once it establishes. Only inbound
    // entries count against the capacity (see NURSERY_CAPACITY). If the inbound slots are full,
    // reject the newcomer: every occupant speaks WebSocket, so there is no safe eviction
    // candidate. The goodbye reaches the peer because its session is already upgraded, provided
    // the transport had a socket to accept it on (the socket budget in NURSERY_CAPACITY).
    size_t inbound_count = 0;
    for (const auto& entry : this->nursery_) {
        if (!entry.conn->is_outbound()) {
            ++inbound_count;
        }
    }
    if (inbound_count >= NURSERY_CAPACITY) {
        SS_LOGW(TAG, "Nursery full of live connections, rejecting new connection");
        this->release_connection(std::move(conn), SendspinGoodbyeReason::ANOTHER_SERVER);
        return;
    }

    SS_LOGD(TAG, "Admitting new connection into the nursery");
    this->nursery_.push_back(NurseryEntry{.conn = std::move(conn)});
    // The connection arrives WS-upgraded, so client/init goes out at once.
    this->start_noise_handshake(this->nursery_[this->nursery_.size() - 1]);
}

void ConnectionManager::refuse_accept(std::shared_ptr<SendspinConnection> conn) {
    // Queued before admission closed and taken after (a stop() under way): the slots are being
    // emptied, so the newcomer gets a goodbye and a close instead of one, and is kept for
    // finish_stop().
    SS_LOGD(TAG, "Not accepting connections, rejecting new connection");
    conn->detach_inbound();
    this->goodbye_for_shutdown(std::move(conn));
}

void ConnectionManager::goodbye_for_shutdown(std::shared_ptr<SendspinConnection> conn) {
    conn->disconnect(SendspinGoodbyeReason::SHUTDOWN);
    this->closing_.push_back(std::move(conn));
}

void ConnectionManager::connect_to(const std::string& url) {
    SS_LOGI(TAG, "Initiating client connection to: %s", url.c_str());

    auto client_conn = std::make_shared<SendspinClientConnection>(url);
    if (!client_conn->inbound_gate().is_created()) {
        SS_LOGE(TAG, "No event group for the connection's inbound gate; not connecting");
        return;
    }
    client_conn->set_task_config(this->client_->config_.websocket_priority,
                                 this->client_->config_.websocket_stack_size);
    client_conn->set_inbound_buffer_location(this->client_->config_.inbound_ring_location);
    client_conn->set_noise_buffer_location(this->client_->config_.noise_buffer_location);
    client_conn->set_json_arena(this->json_arena());

    // Attached before start(): the transport may deliver, or report its upgrade, from its own
    // thread as soon as it runs.
    client_conn->attach_inbound(this->client_->inbound_ring_.get(),
                                this->client_->protocol_task_.get());

    client_conn->init_time_filter();
    client_conn->time_burst().configure(this->client_->config_.time_burst_size,
                                        this->client_->config_.time_burst_interval_ms,
                                        this->client_->config_.time_burst_response_timeout_ms);

    // Start the nursery clock: the nursery scan reaps the connection if it has not completed the
    // hello handshake within NURSERY_ESTABLISH_TIMEOUT_US. The stamp predates DNS/TCP resolve,
    // which is why outbound entries are exempt from the short upgrade deadline.
    client_conn->set_provisional_time_us(platform_time_us());

    // An admitted connection whose transport is already gone (its close not processed yet) is
    // being replaced. Tear its state down as a loss would (no goodbye), instead of leaving it to
    // occupy the slot with orphaned role state.
    for (auto& entry : this->admitted_) {
        if (entry.conn != nullptr && !entry.conn->is_connected()) {
            this->drop_connection(entry.conn.get(), std::nullopt);
        }
    }

    // Only one outbound attempt at a time: release any previous outbound entry before pushing
    // the new one. Otherwise it would be dropped with no goodbye and, on ESP, leave its httpd
    // session pinned.
    for (auto it = this->nursery_.begin(); it != this->nursery_.end();) {
        if (it->conn->is_outbound()) {
            it = this->release_nursery_entry(it, SendspinGoodbyeReason::ANOTHER_SERVER);
        } else {
            ++it;
        }
    }

    // A user-initiated connect is admitted even against a full nursery: there is at most one
    // outbound entry (replaced above), so the nursery is still bounded (MAX_NURSERY_ENTRIES)
    // and an explicit user request never fails against inbound peers.
    this->nursery_.push_back(NurseryEntry{.conn = client_conn});
    client_conn->start();
}

void ConnectionManager::disconnect(SendspinGoodbyeReason reason) {
    // Collect, then drop: drop_connection() edits the slots being walked. An unconnected nursery
    // entry (an attempt still connecting, or one whose transport is gone) yields no close, so it
    // is released here rather than left for the establish deadline.
    InlineVector<std::shared_ptr<SendspinConnection>, MAX_OPEN_CONNECTIONS> to_drop;
    for (const auto& entry : this->admitted_) {
        if (entry.conn != nullptr && entry.conn->is_connected()) {
            to_drop.push_back(entry.conn);
        }
    }
    for (auto it = this->nursery_.begin(); it != this->nursery_.end();) {
        if (it->conn->is_connected()) {
            to_drop.push_back(it->conn);
            ++it;
        } else {
            it = this->release_nursery_entry(it, std::nullopt);
        }
    }
    for (const auto& conn : to_drop) {
        this->drop_connection(conn.get(), reason);
    }
}

void ConnectionManager::leave() {
    // messaging.md "client/leave". Not a role message, so it does not route through
    // role_send_target(). It still shares the activation gate every outbound message has: nothing
    // may be sent before the connection is admitted and its server/activate has arrived.
    // Admission does not imply the latter: an in-band re-handshake rewinds the connection to
    // awaiting its next activation while it keeps the admitted slot.
    AdmittedEntry* entry = this->primary();
    SendspinConnection* conn = entry != nullptr ? entry->conn.get() : nullptr;
    if (conn == nullptr || !conn->accepts_app_sends() || !conn->first_activate_received()) {
        SS_LOGW(TAG, "client/leave ignored: no activated connection with a group to leave");
        return;
    }
    SS_LOGI(TAG, "Leaving the group (client/leave)");
    conn->send_app_json(format_client_leave_message(this->json_arena()));
}

SendspinConnection* ConnectionManager::role_send_target(SendspinRole role) const {
    // Routed to the connection that owns the role, whose activation of it is the role's own gate
    // (see owns_role()): the same gate the receive path and the client/state role objects apply.
    // A declared PAIRING activity is not a gate: pairing.md "Entering and leaving pairing" says an
    // activate that adds it does not by itself affect active_roles, so an active role keeps
    // driving its own traffic across the attempt.
    SendspinConnection* conn = this->role_owner(role);
    if (conn == nullptr || !conn->accepts_app_sends()) {
        SS_LOGD(TAG, "Dropping a %s message: no admitted connection owns the role", to_cstr(role));
        return nullptr;
    }
    // connection.md "Re-handshake": once the client has received Noise message 1 it sends nothing
    // but the handshake until the new server/activate arrives. Same gate client/leave and
    // client/state apply.
    if (!conn->first_activate_received()) {
        SS_LOGD(TAG, "Dropping a %s message: the connection awaits its server/activate",
                to_cstr(role));
        return nullptr;
    }
    return conn;
}

void ConnectionManager::cancel_pairing_window() {
    // Operator cancellation is one of the window's closing events (pairing.md "Pairing Window"). An
    // attempt still withheld for the gesture has just lost the only thing that could admit it, so
    // it ends here with the reason that says why; an attempt already under way runs to its own end,
    // as it does on expiry.
    this->close_pairing_window();

    InlineVector<SendspinConnection*, MAX_ADMITTED> waiting;
    for (const auto& entry : this->admitted_) {
        if (entry.conn != nullptr && entry.conn->pairing_session().step ==
                                         SendspinConnection::PairingStep::AWAIT_PAIRING_WINDOW) {
            waiting.push_back(entry.conn.get());
        }
    }
    for (SendspinConnection* conn : waiting) {
        SS_LOGI(TAG, "Pairing window cancelled: ending the waiting attempt for server_id=%s",
                conn->get_server_id().c_str());
        this->local_abort_pairing(conn, PairAbortReason::USER_CANCELLED);
    }
}

void ConnectionManager::apply_unpaired_access_change(bool enabled) {
    // pairing.md "Unpaired Access": disabling closes the connections relying on it with
    // pairing_required; enabling restarts unpaired connections so their servers read the new value
    // in the next client/hello.

    // Collect first: drop_connection() edits the slots being walked.
    InlineVector<std::shared_ptr<SendspinConnection>, MAX_OPEN_CONNECTIONS> doomed;
    auto consider = [&](const std::shared_ptr<SendspinConnection>& conn, bool hello_sent) {
        if (!enabled) {
            // A connection still awaiting its activation is judged against the new setting
            // when that activation is applied.
            if (conn->first_activate_received() &&
                relies_on_unpaired_access(conn->get_psk_category(), conn->get_activities(),
                                          !conn->get_active_roles().empty())) {
                doomed.push_back(conn);
            }
            return;
        }
        // messaging.md "client/goodbye": restart on a connection the client opened promises
        // that the client reopens it, and nothing reopens a connect_to() connection. A
        // connection declaring pairing is left to finish. A Pairing-PSK one that has not
        // activated yet is most likely about to declare pairing, and a restart would cost that
        // attempt, so it is left alone too; if it activates idle instead, it keeps the hello
        // it already read until it reconnects. A Sentinel one awaiting its activation is
        // restarted, since its server may be deciding that activation on the value its hello
        // carried.
        if (hello_sent && !conn->is_outbound() &&
            conn->get_psk_category() != PskCategory::LONG_TERM &&
            !conn->has_activity(SendspinActivity::PAIRING) &&
            (conn->first_activate_received() || conn->get_psk_category() != PskCategory::PAIRING)) {
            doomed.push_back(conn);
        }
    };

    // An admitted connection sent its hello to be admitted. One between a re-handshake and the
    // activation that follows it is left alone: its server may be moving it into pairing.
    for (const auto& entry : this->admitted_) {
        if (entry.conn != nullptr && entry.conn->first_activate_received()) {
            consider(entry.conn, /*hello_sent=*/true);
        }
    }
    for (const auto& entry : this->nursery_) {
        consider(entry.conn, entry.conn->has_client_hello_sent());
    }

    const SendspinGoodbyeReason reason =
        enabled ? SendspinGoodbyeReason::RESTART : SendspinGoodbyeReason::PAIRING_REQUIRED;
    for (const auto& conn : doomed) {
        SS_LOGI(TAG, "Unpaired access %s: closing the session with server_id=%s",
                enabled ? "enabled" : "disabled", conn->get_server_id().c_str());
        this->drop_connection(conn.get(), reason);
    }
}

// ============================================================================
// Protocol task: message handlers
// ============================================================================

void ConnectionManager::on_server_activate(SendspinConnection* conn, ServerActivateMessage&& msg) {
    NurseryEntry* nursery_entry = this->find_in_nursery(conn);
    AdmittedEntry* admitted_entry = this->find_admitted(conn);
    if (nursery_entry == this->nursery_.end() && admitted_entry == nullptr) {
        // Released while its messages were still queued (a rejection, a reap).
        SS_LOGD(TAG, "Ignoring server/activate from a connection the manager released");
        return;
    }

    const bool unpaired_access =
        this->client_->unpaired_access_enabled_.load(std::memory_order_relaxed);

    // Compute the effective active_roles (sticky: nullopt keeps the prior set), except
    // when this activate omits active_roles and its activities are no longer
    // playback-capable: messaging.md "Playback-capable connections" says the client treats the
    // persisted roles as empty in that case rather than rejecting the message (a later
    // activate can legally narrow activities without re-sending an empty active_roles).
    const bool playback_capable =
        is_playback_capable(conn->get_psk_category(), msg.activities, unpaired_access);
    const std::vector<std::string>& effective_roles =
        msg.active_roles.has_value() ? msg.active_roles.value()
                                     : (playback_capable ? conn->get_active_roles() : EMPTY_ROLES);
    const bool has_roles = !effective_roles.empty();

    // Trust enforcement against the PSK category the Noise handshake resolved for this
    // connection. Every connection has one: no server/activate can arrive before the
    // handshake completes.
    const bool trust_ok =
        admissible(conn->get_psk_category(), msg.activities, has_roles, unpaired_access);

    if (!trust_ok) {
        const SendspinGoodbyeReason reject_reason = inadmissible_reject_reason(
            conn->get_psk_category(), msg.activities, has_roles, unpaired_access);
        SS_LOGW(TAG, "server/activate inadmissible (psk_category=%d): closing with %s",
                static_cast<int>(conn->get_psk_category()),
                reject_reason == SendspinGoodbyeReason::PAIRING_REQUIRED ? "pairing_required"
                                                                         : "unauthorized");
        this->drop_connection(conn, reject_reason);
        return;
    }

    // pairing.md "Pairing index": the count is of pairing server/activate messages received,
    // not of accepted attempts, so the server counts every pairing activate it sends, including
    // ones the client goes on to reject (e.g. method_not_supported below). Bump here, at the
    // single point every pairing server/activate is received, so a rejected activate does not
    // leave the client permanently behind the server's count. handle_enter_pairing() reads this
    // value; it must not bump again.
    const bool is_pairing_activate = contains_activity(msg.activities, SendspinActivity::PAIRING);
    if (is_pairing_activate) {
        conn->bump_pairing_index();
    }

    // Structurally admissible already (the activity set passed the table above),
    // but a pairing activate additionally carries a pairing object whose method must
    // (a) match the matched PSK's category (pairing_psk iff the matched PSK IS the
    // Pairing PSK) and (b) be one the client/hello advertised in supported_pair_methods.
    // When it is not, reply pair/abort(method_not_supported) and leave the connection open
    // (unlike the reasons above, this does not close the connection).
    // A pairing activate that names no usable method (pairing object absent, or a
    // method string this client does not recognize; process_server_activate_message
    // logs the raw value) cannot start any flow.
    if (is_pairing_activate && !msg.pairing_method.has_value()) {
        refuse_activate(conn, "declares pairing with no usable pairing.method",
                        "absent or unrecognized", this->json_arena());
        return;
    }

    if (is_pairing_activate && msg.pairing_method.has_value()) {
        const SendspinPairMethod method = msg.pairing_method.value();
        const bool category_ok = (method == SendspinPairMethod::PAIRING_PSK) ==
                                 (conn->get_psk_category() == PskCategory::PAIRING);
        // The same predicates build_hello_message() advertises from (pairing_offers.h).
        const auto& cfg = this->client_->config_;
        bool offered = true;
        switch (method) {
            case SendspinPairMethod::PAIRING_PSK:
                offered = true;  // Always advertised; see pairing_offers.h.
                break;
            case SendspinPairMethod::DYNAMIC_PAIRING_CODE:
                offered = offers_dynamic_pairing_code(cfg);
                break;
            case SendspinPairMethod::STATIC_PAIRING_CODE:
                offered = offers_static_pairing_code(cfg);
                break;
        }
        if (!category_ok || !offered) {
            refuse_activate(conn, "selects an unsupported pairing method", to_cstr(method),
                            this->json_arena());
            return;
        }

        // messaging.md "server/activate" requires `format` on a dynamic_pairing_code
        // activation, drawn from the client's own `formats`; a missing, unrecognized,
        // or unoffered one is pair/abort(method_not_supported) with the connection left
        // open (pairing.md "Client <-> Server: pair/abort").
        if (method == SendspinPairMethod::DYNAMIC_PAIRING_CODE &&
            !offers_pairing_code_format(this->client_->config_, msg.pairing_format)) {
            refuse_activate(conn, "names no usable pairing.format", to_cstr(method),
                            this->json_arena());
            return;
        }
    }

    const bool is_first = !conn->first_activate_received();
    // Pass the same active_roles the admissibility check above used: when the message
    // omitted active_roles but the connection is no longer playback-capable, that is
    // effective_roles == EMPTY_ROLES, and it must be applied (not left sticky) so the
    // persisted active_roles_ is actually cleared (messaging.md "Playback-capable connections").
    const std::optional<std::vector<std::string>> active_roles_to_apply =
        msg.active_roles.has_value()
            ? msg.active_roles
            : (!playback_capable ? std::make_optional(EMPTY_ROLES) : std::nullopt);
    // Copied before the activate is applied: messaging.md "client/state" ties the next state
    // update to the active-role set changing, so the comparison needs the set this activation
    // replaces.
    const uint16_t roles_before = conn->get_active_role_mask();
    conn->apply_server_activate(msg.activities, active_roles_to_apply, msg.pairing_method,
                                msg.pairing_format);
    // Before anything this activation sends (SendspinClient::on_activation_applied())
    this->client_->on_activation_applied(conn);
    const bool roles_changed = roles_before != conn->get_active_role_mask();

    if (admitted_entry != nullptr) {
        // messaging.md "server/activate": a role this activation took out of active_roles stops
        // its output and drops its state as part of applying the activation, so the teardown runs
        // before the branches below act on the new activation. The connection keeps owning what
        // it still activates and claims what it newly activates unless another admitted
        // connection owns it; only the roles it owned and no longer does are torn down. A nursery
        // entry sees one activate and owns nothing yet.
        const uint16_t owned_before = admitted_entry->owned_roles;
        admitted_entry->owned_roles = claimable_roles(conn->get_active_role_mask(),
                                                      this->roles_owned_by_others(admitted_entry));
        const auto removed = static_cast<uint16_t>(owned_before & ~admitted_entry->owned_roles);
        if (removed != 0) {
            this->client_->apply_role_removals(conn, removed);
        }
        if (admitted_entry->owned_roles != owned_before) {
            this->refresh_published_state();
        }

        // Already admitted: no arbitration needed. is_first can still be true here after an
        // in-band re-handshake reset first_activate_received_, so the branches below that act on
        // an is_first activate also require is_handshake_complete().
        this->note_playback_activity(conn);

        const auto& activities = conn->get_activities();
        const std::optional<SendspinPairMethod> pairing_method =
            selected_pairing_method(activities, conn->get_pairing_method());
        const bool selects_pairing = pairing_method.has_value();

        // The pairing-selection check runs on every activate and takes priority over the plain
        // operational branch: a server rehandshaking an admitted connection onto the pairing PSK
        // resets first_activate_received_, so its pairing activate looks like a first activate,
        // and the operational branch would publish client/state while the server waits for
        // client/pair-finalize.
        if (conn->is_pairing_in_progress()) {
            // Pairing was in progress and the server sent another server/activate instead of
            // server/pair-finalize: it abandoned pairing without finalizing. Going operational
            // discards any pending record and resets the pairing session structurally.
            // pairing.md "Entering and leaving pairing" admits one pairing attempt per pairing
            // server/activate, so an activate that selects pairing again also starts the attempt
            // it admits: its pairing_index was already counted, and the server that sent it is
            // waiting for the client/pair-init that opens the new attempt.
            SS_LOGI(TAG,
                    "Subsequent activate during pairing (leftover): clearing pairing "
                    "state and going operational for server_id=%s",
                    conn->get_server_id().c_str());
            this->client_->on_handshake_complete(conn);
            if (selects_pairing) {
                SS_LOGI(TAG,
                        "Leftover activate selects pairing again (%s): starting the "
                        "attempt it admits for server_id=%s",
                        to_cstr(pairing_method.value()), conn->get_server_id().c_str());
                this->handle_enter_pairing(conn);
            }
        } else if (selects_pairing) {
            // Reached both when the operator initiates pairing on an already-operational
            // connection (is_first false) and when the server first rehandshakes the connection
            // onto the pairing PSK (is_first true).
            if (!is_first || conn->is_handshake_complete()) {
                // pairing.md "Entering and leaving pairing": adding 'pairing' does not by
                // itself affect active_roles, streams or group membership. An activate that
                // declares both purposes therefore runs the operational path as well as the
                // pairing one, and first: going operational clears stale pairing state the
                // new attempt must not inherit.
                if (is_first && contains_activity(activities, SendspinActivity::PLAYBACK)) {
                    this->client_->on_handshake_complete(conn);
                }
                SS_LOGI(TAG,
                        "Activate selects pairing (%s): entering pairing for "
                        "server_id=%s",
                        to_cstr(pairing_method.value()), conn->get_server_id().c_str());
                this->handle_enter_pairing(conn);
                // connection.md "Re-handshake" makes a post-re-handshake activation a subsequent
                // one, so messaging.md "client/state" owes an update for a role it activates.
                const bool adds_role = (conn->get_active_role_mask() & ~roles_before) != 0;
                if (is_first && adds_role &&
                    !contains_activity(activities, SendspinActivity::PLAYBACK)) {
                    this->client_->publish_client_state(conn);
                }
            }
        } else if (is_first && conn->is_handshake_complete()) {
            this->client_->on_handshake_complete(conn);
        }

        // messaging.md "client/state": a role that becomes active in active_roles must be told
        // about in an update that includes that role's object, before the server may send its
        // binary data. A first activate publishes through on_handshake_complete(), or above when
        // it selects pairing alone and adds a role; this covers every later one that moves the set.
        if (roles_changed && !is_first && conn->is_operational()) {
            this->client_->publish_client_state(conn);
        }
        return;
    }

    // A nursery entry is admitted here when the hello handshake has already completed, so the
    // messages the server sends behind its activate are dispatched with the connection admitted.
    // A nonconforming peer's server/activate can race ahead of the hello exchange; the
    // level-triggered admission in scan_nursery() then admits it once both are true, in whichever
    // order they complete. A pairing-flavored activate goes through admission like any other
    // operational candidate; see promote_or_arbitrate_nursery_entry().
    if (conn->is_operational()) {
        this->promote_or_arbitrate_nursery_entry(nursery_entry);
    }
}

void ConnectionManager::on_pair_abort(SendspinConnection* conn, PairAbortReason reason) {
    // A pairing attempt only ever runs on an admitted connection (see the pairing branch in
    // promote_or_arbitrate_nursery_entry()).
    if (this->find_admitted(conn) == nullptr) {
        SS_LOGD(TAG, "Discarding pair/abort (reason=%s) for server_id=%s: not admitted",
                to_cstr(reason), conn->get_server_id().c_str());
        return;
    }
    this->handle_pair_abort(conn, reason);
}

void ConnectionManager::on_server_unpair(SendspinConnection* conn) {
    // Only an admitted connection is acted on. A nursery peer that has proven itself can send
    // server/unpair too (the spec calls it valid regardless of the current activities), so this
    // drops one from a peer the client is not actually running a session with. Rare, and it
    // costs that peer nothing but a repeat once it is admitted.
    if (this->find_admitted(conn) == nullptr) {
        SS_LOGD(TAG, "Discarding server/unpair for server_id=%s: not admitted",
                conn->get_server_id().c_str());
        return;
    }
    this->handle_server_unpair(conn);
}

void ConnectionManager::on_pairing_message(SendspinConnection* conn,
                                           const ServerPairingMessage& message) {
    // Only ever targets an admitted connection: a pairing-code session only exists on a
    // connection that already won admission.
    if (this->find_admitted(conn) == nullptr) {
        SS_LOGD(TAG, "Discarding pairing message (kind=%d) for server_id=%s: not admitted",
                static_cast<int>(message.kind), conn->get_server_id().c_str());
        return;
    }
    this->handle_pairing_message(conn, message);
}

void ConnectionManager::on_pairing_succeeded(SendspinConnection* conn) {
    // A completed pairing closes the pairing window (pairing.md "Pairing Window"). The pairing is
    // complete here and not a message earlier: pairing.md "Entering and leaving pairing" has a
    // client that sent client/pair-finalize and then received server/activate in place of
    // server/pair-finalize persist nothing, so the attempt only becomes a pairing when that ack
    // arrives and the record is stored, which is what calls this. The window is closed only when
    // it is the one this connection's attempt ran under: an attempt it never admitted has not
    // spent the operator's gesture.
    if (this->pairing_window_conn_ != nullptr && this->pairing_window_conn_ == conn) {
        this->close_pairing_window();
    }
    this->client_->note_pairing_succeeded(conn->get_server_id());
}

void ConnectionManager::on_connection_lost(SendspinConnection* conn) {
    if (this->find_admitted(conn) != nullptr) {
        // A close the client makes without a drop (close_silently(), fail_inbound()) detaches
        // the gate first; a loss the peer caused arrives with it attached.
        if (conn->inbound_gate().is_detached()) {
            SS_LOGD(TAG, "Admitted connection closed by the client");
        } else {
            SS_LOGI(TAG, "Admitted connection lost");
        }
    } else if (this->find_in_nursery(conn) != this->nursery_.end()) {
        SS_LOGD(TAG, "Nursery connection lost");
    }
    // The transport is already gone, so no goodbye is attempted (nullopt). A connection the
    // manager no longer manages is a no-op.
    this->drop_connection(conn, std::nullopt);
}

std::vector<std::string> ConnectionManager::open_connection_psk_ids() const {
    std::vector<std::string> psk_ids;
    psk_ids.reserve(MAX_OPEN_CONNECTIONS);
    for (const auto& entry : this->admitted_) {
        if (entry.conn != nullptr && !entry.conn->get_psk_id().empty()) {
            psk_ids.push_back(entry.conn->get_psk_id());
        }
    }
    for (const auto& entry : this->nursery_) {
        if (!entry.conn->get_psk_id().empty()) {
            psk_ids.push_back(entry.conn->get_psk_id());
        }
    }
    return psk_ids;
}

// ============================================================================
// Protocol task: tick
// ============================================================================

void ConnectionManager::snapshot_connections(ConnectionSnapshot& out) const {
    for (const auto& entry : this->admitted_) {
        if (entry.conn != nullptr) {
            out.push_back(entry.conn);
        }
    }
    for (const auto& entry : this->nursery_) {
        out.push_back(entry.conn);
    }
}

void ConnectionManager::start_upgraded_handshakes() {
    // Level-triggered on the upgrade flag the transport sets (mark_ws_upgraded()), so a wake that
    // carried several upgrades, or one that raced the previous tick, is never missed. An inbound
    // entry sent its client/init at accept().
    for (auto& entry : this->nursery_) {
        if (!entry.client_init_sent && entry.conn->is_ws_upgraded()) {
            this->start_noise_handshake(entry);
        }
    }
}

uint32_t ConnectionManager::tick(int64_t now_us) {
    uint32_t next = this->scan_nursery(now_us);
    next = std::min(next, this->scan_admitted(now_us));

    // The pairing window's lifetime (pairing.md "Pairing Window"): closed silently at expiry,
    // whether or not it admitted an attempt.
    if (this->pairing_window_open_until_us_ != 0) {
        if (now_us >= this->pairing_window_open_until_us_) {
            this->close_pairing_window();
        } else {
            next = std::min(next, ms_until(this->pairing_window_open_until_us_, now_us));
        }
    }

    // After every scan above that can release a connection, so one released this tick whose
    // transport is already closed is dropped now rather than at a later wake.
    next = std::min(next, this->reap_released(now_us));

    // The platform server's pending-upgrade reap (ESP: close sessions that never complete their
    // upgrade; host: none, IXWebSocket times them out itself).
    if (this->ws_server_ != nullptr) {
        next = std::min(next, this->ws_server_->tick());
    }
    next = std::min(next, this->maybe_start_ws_server(now_us));
    return next;
}

uint32_t ConnectionManager::run_time_sync() {
    uint32_t next = ProtocolTask::NO_DEADLINE;
    AdmittedEntry* primary = this->primary();
    for (auto& entry : this->admitted_) {
        SendspinConnection* conn = entry.conn.get();
        // Gate on is_operational() (hello + first server/activate), not just admission: an
        // in-band re-handshake resets first_activate_received_ on an admitted connection, and a
        // stale pre-re-handshake time exchange must not resume mid-rotation. A declared PAIRING
        // activity is not a gate: pairing.md "Entering and leaving pairing" runs pairing
        // alongside playback, leaving streams open and their timeline unaffected, which a player
        // can only deliver with its time filter still converging. A connection whose transport
        // is gone waits for its close to be processed. This is the burst path's only gate;
        // send_time_message() rechecks the transport.
        if (conn == nullptr || !conn->is_operational() || !conn->is_connected()) {
            continue;
        }
        SendspinTimeBurst& burst = conn->time_burst();

        // A burst holds the platform's high-performance networking for the round trips it
        // measures, so it requests the hold when it comes due and sends its first time frame only
        // once the main loop has granted it: called the listener and counted the grant
        // (SendspinClient::high_performance_granted()). The grant wakes the task, so a burst
        // waiting for it adds no deadline. The release at the burst's end waits for nothing.
        // One clock read for both, so the burst loop() may open is the one the request was made
        // for; loop() opens none without the grant (may_open_burst).
        const int64_t now_ms = platform_time_us() / US_PER_MS;
        if (!entry.high_performance_held && burst.starts_burst(now_ms)) {
            entry.high_performance_ticket = this->client_->request_high_performance(true);
            entry.high_performance_held = true;
        }
        const bool granted = entry.high_performance_held &&
                             this->client_->high_performance_granted(entry.high_performance_ticket);
        if (granted || !entry.high_performance_held) {
            const TimeBurstResult result = burst.loop(conn, now_ms, granted);
            if (result.burst_completed) {
                if (entry.high_performance_held) {
                    this->client_->request_high_performance(false);
                    entry.high_performance_held = false;
                }
                if (&entry == primary && conn->get_time_filter() != nullptr) {
                    this->client_->post_time_sync_error(conn->get_time_filter()->get_error());
                }
            }
            next = std::min(next, burst.ms_until_due(now_ms));
        }
        // A client/state held for this connection's clock goes out with its first measurement.
        if (entry.state_held && conn->is_time_synced()) {
            this->client_->publish_client_state(conn);
        }
    }
    return next;
}

void ConnectionManager::refresh_published_state() {
    AdmittedEntry* primary = this->primary();
    SendspinConnection* primary_conn = primary != nullptr ? primary->conn.get() : nullptr;
    const uint64_t primary_id = primary_conn != nullptr ? primary_conn->get_instance_id() : 0;
    if (primary_id != this->published_primary_id_) {
        // The slot changes only with the primary connection: its filter is fixed at creation and
        // its server information is complete before admission (server/hello precedes it).
        this->published_primary_id_ = primary_id;
        std::shared_ptr<SendspinTimeFilter> filter =
            primary_conn != nullptr ? primary_conn->get_shared_time_filter() : nullptr;
        std::optional<ServerInformationObject> info =
            primary_conn != nullptr ? std::make_optional(primary_conn->get_server_information())
                                    : std::nullopt;
        {
            // Swapped under the leaf lock; what they replace is destroyed outside it.
            std::lock_guard<std::mutex> lock(this->published_mutex_);
            this->published_.time_filter.swap(filter);
            this->published_.server_information.swap(info);
        }
    }

    // Only ever lowered here: a loss is reported as soon as the slots stop naming the connection,
    // while connected is raised by publish_connected() at the end of the tick.
    if (!this->has_operational_connection()) {
        this->connected_.store(false, std::memory_order_release);
    }
}

void ConnectionManager::publish_connected() {
    this->connected_.store(this->has_operational_connection(), std::memory_order_release);
}

bool ConnectionManager::has_operational_connection() const {
    for (const auto& entry : this->admitted_) {
        if (entry.conn != nullptr && entry.conn->is_connected() && entry.conn->is_operational()) {
            return true;
        }
    }
    return false;
}

bool ConnectionManager::is_accepting() const {
    return this->client_->protocol_task_->is_accepting();
}

bool ConnectionManager::shutdown_pending() const {
    return !this->is_accepting() && !this->shutdown_done_;
}

void ConnectionManager::shutdown() {
    this->shutdown_done_ = true;

    // A code or pairing-window prompt still showing must be dismissed once the client has reset
    // its state; the flags live on the connections, so capture them before they go (see
    // PairingUiSnapshot). stop() queues the dismissal after its own cleanup.
    PairingUiSnapshot ui{false, false};
    InlineVector<std::shared_ptr<SendspinConnection>, MAX_OPEN_CONNECTIONS> to_goodbye;
    for (auto& entry : this->admitted_) {
        if (entry.conn == nullptr) {
            continue;
        }
        const PairingUiSnapshot entry_ui = snapshot_pairing_ui(entry.conn.get());
        ui.code_was_emitted |= entry_ui.code_was_emitted;
        ui.window_was_shown |= entry_ui.window_was_shown;
        to_goodbye.push_back(this->vacate_admitted(entry));
    }
    for (auto& entry : this->nursery_) {
        const PairingUiSnapshot entry_ui = snapshot_pairing_ui(entry.conn.get());
        ui.code_was_emitted |= entry_ui.code_was_emitted;
        ui.window_was_shown |= entry_ui.window_was_shown;
        entry.conn->detach_inbound();
        to_goodbye.push_back(std::move(entry.conn));
    }
    this->nursery_.clear();
    // A standing pairing window belongs to this run; a restart begins with it closed.
    this->close_pairing_window();
    // Empties the time filter slot before stop() stops the role threads: a role thread that reads
    // it from here converts to 0, which reads as late.
    this->refresh_published_state();
    this->shutdown_ui_.code_was_emitted |= ui.code_was_emitted;
    this->shutdown_ui_.window_was_shown |= ui.window_was_shown;

    // Each disconnect() sends its goodbye and closes its transport; one already disconnected
    // does neither.
    for (auto& conn : to_goodbye) {
        this->goodbye_for_shutdown(std::move(conn));
    }
}

// ============================================================================
// Protocol task: role ownership
// ============================================================================

AdmittedEntry* ConnectionManager::find_admitted(const SendspinConnection* conn) {
    // A null would match an empty slot.
    assert(conn != nullptr);
    for (auto& entry : this->admitted_) {
        if (entry.conn.get() == conn) {
            return &entry;
        }
    }
    return nullptr;
}

bool ConnectionManager::owns_role(const SendspinConnection* conn, SendspinRole role) const {
    for (const auto& entry : this->admitted_) {
        if (entry.conn.get() == conn && conn != nullptr) {
            return (entry.owned_roles & role_mask_bit(role)) != 0 && conn->is_role_active(role);
        }
    }
    return false;
}

SendspinConnection* ConnectionManager::role_owner(SendspinRole role) const {
    for (const auto& entry : this->admitted_) {
        if (entry.conn != nullptr && (entry.owned_roles & role_mask_bit(role)) != 0 &&
            entry.conn->is_role_active(role)) {
            return entry.conn.get();
        }
    }
    return nullptr;
}

AdmittedEntry* ConnectionManager::primary() {
    AdmittedEntry* first = nullptr;
    for (auto& entry : this->admitted_) {
        if (entry.conn == nullptr) {
            continue;
        }
        if ((entry.owned_roles & role_mask_bit(SendspinRole::PLAYER)) != 0) {
            return &entry;
        }
        if (first == nullptr) {
            first = &entry;
        }
    }
    return first;
}

uint16_t ConnectionManager::roles_owned_by_others(const AdmittedEntry* except) const {
    uint16_t owned = 0;
    for (const auto& entry : this->admitted_) {
        if (entry.conn != nullptr && &entry != except) {
            owned |= entry.owned_roles;
        }
    }
    return owned;
}

// ============================================================================
// Handoff support
// ============================================================================

void ConnectionManager::set_last_played_server_id(const std::string& server_id) {
    if (server_id.empty()) {
        this->last_played_server_id_.reset();
    } else {
        this->last_played_server_id_ = server_id;
    }
}

// ============================================================================
// Tick steps
// ============================================================================

uint32_t ConnectionManager::maybe_start_ws_server(int64_t now_us) {
    if (this->ws_server_ == nullptr || this->ws_server_->is_started() || !this->is_accepting()) {
        return ProtocolTask::NO_DEADLINE;
    }
    // ha-paneld: server_port 0 disables the inbound listener; only connect_to() reaches the client.
    if (this->client_->config_.server_port == 0) {
        return ProtocolTask::NO_DEADLINE;
    }
    if (now_us < this->ws_server_start_retry_time_us_) {
        return ms_until(this->ws_server_start_retry_time_us_, now_us);
    }
    // is_network_ready() is called here, on the protocol task, and from start() on the main loop
    // (see SendspinNetworkProvider).
    if (this->client_->network_provider_ == nullptr) {
        return ProtocolTask::NO_DEADLINE;
    }
    if (!this->client_->network_provider_->is_network_ready()) {
        return NETWORK_POLL_INTERVAL_MS;
    }
    // A dropped frame is drained into a buffer of the longest message a conforming server sends
    // a live connection (see SendspinWsServer::set_discard_capacity()).
    const InboundRing* ring = this->client_->inbound_ring_.get();
    this->ws_server_->set_discard_capacity(
        std::max(ring != nullptr ? ring->largest_message_bytes() : INBOUND_MAX_MESSAGE_BYTES,
                 InboundGate::PRE_ADMISSION_MESSAGE_BYTES));
    if (!this->ws_server_->start(this->client_, this->client_->config_.httpd_psram_stack,
                                 this->client_->config_.httpd_priority,
                                 this->client_->config_.httpd_stack_size)) {
        // A persistent failure (e.g. the server port is already in use) is retried with backoff
        // instead of on every tick, which would spam the log.
        this->ws_server_start_retry_time_us_ = now_us + WS_SERVER_START_RETRY_US;
        return ms_until(this->ws_server_start_retry_time_us_, now_us);
    }
    return ProtocolTask::NO_DEADLINE;
}

uint32_t ConnectionManager::scan_nursery(int64_t now_us) {
    uint32_t next = ProtocolTask::NO_DEADLINE;

    // Hello scan: one send per entry, in the first scan that sees its Noise handshake complete
    // (the receive pass earlier in the same tick sets that flag, with no event of its own). A
    // failed send drops the connection, which erases its entry, so the index is not advanced.
    for (size_t i = 0; i < this->nursery_.size();) {
        NurseryEntry& entry = this->nursery_[i];
        if (entry.hello_attempted || !entry.conn->is_noise_handshake_complete()) {
            ++i;
            continue;
        }
        entry.hello_attempted = true;
        if (this->send_hello_message(entry.conn.get())) {
            ++i;
            continue;
        }
        // The hello is sent once, so a connection whose hello failed, before or after its
        // encrypt, can never be established. One that failed after it is closed already
        // (SendspinConnection::settle_noise_send()), and the tick's loss pass finds it released.
        // No goodbye, as the re-prove watchdog closes: restart would promise a reconnect that
        // nothing performs for a connect_to() connection. The nullopt release performs the close
        // (non-blocking on every platform).
        this->drop_connection(entry.conn.get(), std::nullopt);
    }

    // Admission: every nursery entry that has proven itself (is_operational(): hello handshake
    // complete and first server/activate applied and admissible; trust was already checked when
    // that activate was processed). The activate handler admits at once when the hello completed
    // first; this level-triggered pass covers the other order.
    for (auto it = this->nursery_.begin(); it != this->nursery_.end();) {
        if (!it->conn->is_operational()) {
            ++it;
            continue;
        }
        it = this->promote_or_arbitrate_nursery_entry(it);
    }

    // Establish reap: the only release path for peers that connect and then stall without
    // completing the hello, for outbound attempts that stall without failing, and so also for a
    // connection whose hello never completed.
    for (auto it = this->nursery_.begin(); it != this->nursery_.end();) {
        const int64_t deadline_us =
            it->conn->get_provisional_time_us() + NURSERY_ESTABLISH_TIMEOUT_US;
        if (now_us >= deadline_us) {
            SS_LOGW(TAG, "Nursery connection stalled at %s (>%d s), dropping",
                    to_cstr(setup_stage(*it->conn)),
                    static_cast<int>(NURSERY_ESTABLISH_TIMEOUT_US / US_PER_SECOND));
            it = this->release_nursery_entry(it, SendspinGoodbyeReason::ANOTHER_SERVER);
            continue;
        }
        next = std::min(next, ms_until(deadline_us, now_us));
        ++it;
    }
    return next;
}

uint32_t ConnectionManager::reap_released(int64_t now_us) {
    uint32_t next = ProtocolTask::NO_DEADLINE;
    for (auto it = this->reaping_.begin(); it != this->reaping_.end();) {
        // The transport's close flag, not close_ready(): nothing takes a released connection's
        // ring items any more, so its in-flight count need not reach zero. An attempt that opened
        // after its release is done connecting too, so its destructor's stop is that of an open
        // transport (on ESP up to the websocket task's one-second read poll, see
        // release_connection()); the transport's upgrade report wakes the task for it.
        const bool closed = it->conn->inbound_gate().is_transport_closed();
        const bool opened = it->conn->is_ws_upgraded();
        if (!closed && !opened && now_us < it->deadline_us) {
            next = std::min(next, ms_until(it->deadline_us, now_us));
            ++it;
            continue;
        }
        if (!closed && !opened) {
            SS_LOGW(TAG,
                    "Released outbound connection's transport still open after %u ms; dropping it",
                    static_cast<unsigned>(SendspinClientConnection::CONNECT_TIMEOUT_MS));
        }
        // The erase drops the list's reference; the destructor's transport join is short once the
        // transport has closed, at most the read poll once it has opened, and bounded by what
        // remains of its connect otherwise: at the deadline, on ESP, at most the rest of a slow
        // DNS lookup (CONNECT_TIMEOUT_MS says how long lwIP's resolver can take).
        it = this->reaping_.erase(it);
    }
    return next;
}

uint32_t ConnectionManager::scan_admitted(int64_t now_us) {
    uint32_t next = ProtocolTask::NO_DEADLINE;
    // The array is never compacted, so a drop below frees its slot without moving the others.
    for (auto& entry : this->admitted_) {
        SendspinConnection* conn = entry.conn.get();
        if (conn == nullptr) {
            continue;
        }

        // Liveness: a blackholed socket (no FIN, RST, or close frame) never produces a transport
        // close, so drop an admitted connection once its inbound silence reaches the timeout.
        if (this->liveness_timeout_us_ > 0) {
            if (liveness_expired(now_us, conn->get_last_receive_time_us(),
                                 this->liveness_timeout_us_)) {
                SS_LOGW(TAG, "Admitted connection silent for >%" PRId64 " ms, dropping as lost",
                        this->liveness_timeout_us_ / US_PER_MS);
                // The goodbye actively closes the transport, which the silent peer never will; an
                // inbound session would otherwise hold its server slot. Per the spec's
                // `client/goodbye` section, restart asks a server that was only slow to reconnect.
                this->drop_connection(conn, SendspinGoodbyeReason::RESTART);
                continue;
            }
            next = std::min(next, ms_until(now_us + liveness_remaining_us(
                                                        now_us, conn->get_last_receive_time_us(),
                                                        this->liveness_timeout_us_),
                                           now_us));
        }

        // Re-prove: an admitted connection is briefly non-operational while it re-proves itself,
        // after a successful in-band re-handshake (SendspinConnection::handle_noise_rehandshake(),
        // which leaves it awaiting the post-swap server/activate) or after the server acks
        // client/pair-finalize and is expected to rekey via one
        // (SendspinConnection::note_pairing_finalize_ack()). Both stamp provisional_time_us_ when
        // they enter this window. An admitted connection is never non-operational for any other
        // reason: a nursery entry is only admitted once it is operational, and an in-progress
        // pairing-code exchange keeps is_operational() true throughout (that flow has its own
        // timeouts: the attempt deadline below, and the pairing window while a gesture is
        // awaited). The stamp != 0 guard keeps the check inert for a connection never stamped.
        if (!conn->is_operational() && conn->get_provisional_time_us() != 0) {
            const int64_t deadline_us = conn->get_provisional_time_us() + REPROVE_TIMEOUT_US;
            if (now_us >= deadline_us) {
                SS_LOGW(TAG,
                        "Admitted connection failed to re-prove itself within %d s "
                        "(server_id=%s); dropping",
                        static_cast<int>(REPROVE_TIMEOUT_US / US_PER_SECOND),
                        conn->get_server_id().c_str());
                // Closed without a goodbye. One of the two windows this reaps starts at Noise
                // message 1, and connection.md "Re-handshake" lets the client start no application
                // message there but the handshake; the other ends at a rekey the peer has already
                // failed to perform. No goodbye reason describes either, and the peer learns the
                // same thing from the close, which the nullopt release below performs.
                this->drop_connection(conn, std::nullopt);
                continue;
            }
            next = std::min(next, ms_until(deadline_us, now_us));
        }

        // Pairing attempt timeout (pairing.md "Entering and leaving pairing"); local_abort_pairing
        // also withdraws an emitted code. Between the server's pair-finalize ack and the
        // post-rekey activate that reaches clear_pairing_state(), pairing_session_ still reports
        // its last pre-ack step and attempt_deadline_us keeps counting down against an exchange
        // that has already succeeded, so the check is skipped for that window: a slow rekey must
        // not abort a completed pairing. connection.md "Re-handshake": between Noise message 1 and
        // the new server/activate the client starts no application message but the handshake,
        // which a pair/abort would be. first_activate_received() is false for exactly that window,
        // so the abort waits for the activation; if it never comes, the re-prove check above
        // closes the connection instead and the attempt ends with it. pairing.md "Entering and
        // leaving pairing" bounds an attempt with a timeout "on expiry it sends pair/abort"; the
        // re-handshake rule is the narrower MUST NOT, and the wait it imposes is bounded by
        // REPROVE_TIMEOUT_US.
        const int64_t attempt_deadline_us = conn->pairing_session().attempt_deadline_us;
        if (attempt_deadline_us != 0 && !conn->is_pairing_finalized() &&
            conn->first_activate_received()) {
            if (now_us >= attempt_deadline_us) {
                SS_LOGW(TAG, "Pairing attempt timed out for server_id=%s; aborting",
                        conn->get_server_id().c_str());
                this->local_abort_pairing(conn, PairAbortReason::ATTEMPT_TIMEOUT);
                continue;
            }
            next = std::min(next, ms_until(attempt_deadline_us, now_us));
        }
    }
    return next;
}

// ============================================================================
// Connection setup
// ============================================================================

SendspinArenaAllocator& ConnectionManager::json_arena() const {
    return *this->client_->json_arena_;
}

void ConnectionManager::start_noise_handshake(NurseryEntry& entry) {
    // client/init is sent proactively: the client is always the Noise responder regardless of
    // who opened the socket, but it still sends client/init first as the Sendspin protocol
    // client. The hello is armed later, once the Noise handshake completes (the hello scan in
    // scan_nursery()).
    entry.conn->init_noise_handshake(*this->client_->identity_, *this->client_->record_store_,
                                     std::string(NOISE_SUITE_CHACHAPOLY));
    entry.conn->send_noise_client_init();
    entry.client_init_sent = true;
}

NurseryEntry* ConnectionManager::find_in_nursery(const SendspinConnection* conn) {
    for (auto it = this->nursery_.begin(); it != this->nursery_.end(); ++it) {
        if (it->conn.get() == conn) {
            return it;
        }
    }
    return this->nursery_.end();
}

void ConnectionManager::install_admitted(std::shared_ptr<SendspinConnection> conn,
                                         uint16_t owned_roles) {
    for (auto& entry : this->admitted_) {
        if (entry.conn != nullptr) {
            continue;
        }
        entry = AdmittedEntry{};
        entry.owned_roles = owned_roles;
        // The admitted flag is what the transport routes on (admitted connections receive into
        // the ring) and what the dispatch gate reads; it tracks the slot exactly.
        conn->set_admitted(true);
        entry.conn = std::move(conn);
        this->refresh_published_state();
        return;
    }
    // promote_or_arbitrate_nursery_entry() frees a slot before it installs.
    assert(false && "install_admitted() with every admitted slot taken");
}

std::shared_ptr<SendspinConnection> ConnectionManager::vacate_admitted(AdmittedEntry& entry) {
    entry.conn->detach_inbound();
    entry.conn->set_admitted(false);
    entry.conn->time_burst().reset();
    if (entry.high_performance_held) {
        this->client_->request_high_performance(false);
    }
    std::shared_ptr<SendspinConnection> conn = std::move(entry.conn);
    entry = AdmittedEntry{};
    return conn;
}

NurseryEntry* ConnectionManager::release_nursery_entry(
    NurseryEntry* it, std::optional<SendspinGoodbyeReason> reason) {
    auto conn = std::move(it->conn);
    auto next = this->nursery_.erase(it);
    this->release_connection(std::move(conn), reason);
    return next;
}

void ConnectionManager::release_connection(std::shared_ptr<SendspinConnection> conn,
                                           std::optional<SendspinGoodbyeReason> goodbye) {
    // Leaving the manager: stop its inbound traffic first, so nothing it sends during the goodbye
    // reaches the roles and its transport never waits on the protocol task. Outgoing sends,
    // including the goodbye itself, are unaffected. On ESP the httpd session slot keeps the
    // connection alive until the goodbye worker runs and the session closes; the host transports
    // send synchronously, so the goodbye and the close have both completed when disconnect()
    // returns.
    conn->detach_inbound();
    if (goodbye.has_value()) {
        conn->disconnect(goodbye.value());
    } else if (conn->is_connected()) {
        // No goodbye still closes the WebSocket (connection.md "Failure Handling", pairing.md
        // "Protocol Errors"): an inbound transport is held open by its server until the peer
        // closes otherwise. close_transport_now() is non-blocking on every platform.
        conn->close_transport_now();
    }
    // An outbound connection still connecting has a destructor that joins its transport for the
    // rest of the connect, so it is parked rather than released here. One whose upgrade completed
    // is released here, and the protocol task pays its transport's stop: on host IXWebSocket's
    // close; on ESP esp_websocket_client_stop(), which waits for the websocket task to leave its
    // read poll, up to a second on an open, idle socket. After a goodbye, disconnect() above has
    // already stopped it, usually at once since the peer closes on the goodbye. An inbound one's
    // destructor joins nothing (on ESP its httpd session owns it). The caller's reference drops
    // here in both cases.
    if (conn->is_outbound() && !conn->is_ws_upgraded()) {
        this->park_for_reaping(std::move(conn));
    }
}

void ConnectionManager::park_for_reaping(std::shared_ptr<SendspinConnection> conn) {
    // Non-blocking on every platform, and for an attempt still connecting it ends nothing: on host
    // IXWebSocket's close() could wait out the handshake then, and on ESP only
    // esp_websocket_client_stop() ends an attempt, which waits for the websocket task. The attempt
    // reports its close once its connect fails or its peer closes, and the destructor stops what
    // is left at the deadline.
    conn->close_transport_now();
    if (this->reaping_.size() == REAPING_CAPACITY) {
        // The entry parked longest makes room: its attempt is the furthest along, so the stop in
        // its destructor cancels an upgrade long under way (host) or finds a connect that has
        // nearly timed out (ESP), and its join is the shortest this release could pay: at most
        // the rest of its connect plus its DNS lookup's resolver bound (CONNECT_TIMEOUT_MS).
        SS_LOGW(TAG, "Reaping list full (%zu); dropping the connection parked longest",
                REAPING_CAPACITY);
        this->reaping_.erase(this->reaping_.begin());
    }
    this->reaping_.push_back(ReapEntry{
        .conn = std::move(conn),
        .deadline_us =
            platform_time_us() +
            static_cast<int64_t>(SendspinClientConnection::CONNECT_TIMEOUT_MS) * US_PER_MS,
    });
}

// ============================================================================
// Hello handshake
// ============================================================================

bool ConnectionManager::send_hello_message(SendspinConnection* conn) {
    // A transport that is gone reports its close, which drops the connection; one that never
    // does is reaped at the establish deadline.
    if (!conn->is_connected()) {
        SS_LOGW(TAG, "Cannot send hello - not connected");
        return true;
    }

    std::string hello_message = this->client_->build_hello_message();

    // send_app_json (not send_text_message): client/hello is encrypted like every other
    // post-handshake message. The hello is only sent once the Noise handshake completes, so
    // send_app_json always routes to the active Noise transport here.
    const SsErr err = conn->send_app_json(hello_message);
    if (err == SsErr::OK) {
        // Setting the flag is all that is needed: admission is level-triggered, so the nursery
        // scan observes is_handshake_complete() even when the peer's server/hello raced ahead of
        // this send.
        conn->set_client_hello_sent(true);
        return true;
    }

    if (err == SsErr::INVALID_STATE) {
        // The transport is no longer connected: its close drops the connection, or the tick's
        // loss pass does for a refusal after the encrypt.
        SS_LOGW(TAG, "No client connected for hello message");
        return true;
    }

    SS_LOGW(TAG, "Failed to send hello message (err=%d), dropping the connection",
            static_cast<int>(err));
    return false;
}

// ============================================================================
// Connection lifecycle
// ============================================================================

void ConnectionManager::drop_connections_using_psk_id(const std::string& psk_id,
                                                      const SendspinConnection* except) {
    if (psk_id.empty()) {
        return;
    }

    // Collect first, then drop: drop_connection() mutates the admitted slots and nursery_, so
    // dropping while walking the nursery would invalidate the iterator underneath us.
    InlineVector<std::shared_ptr<SendspinConnection>, MAX_OPEN_CONNECTIONS> doomed;
    for (const auto& entry : this->admitted_) {
        if (entry.conn != nullptr && entry.conn.get() != except &&
            entry.conn->get_psk_id() == psk_id) {
            doomed.push_back(entry.conn);
        }
    }
    for (const auto& entry : this->nursery_) {
        if (entry.conn.get() != except && entry.conn->get_psk_id() == psk_id) {
            doomed.push_back(entry.conn);
        }
    }

    for (const auto& conn : doomed) {
        SS_LOGI(TAG, "Record %s revoked; dropping its live session (server_id=%s)", psk_id.c_str(),
                conn->get_server_id().c_str());
        this->drop_connection(conn.get(), SendspinGoodbyeReason::UNAUTHORIZED);
    }
}

void ConnectionManager::drop_connection(SendspinConnection* conn,
                                        std::optional<SendspinGoodbyeReason> goodbye) {
    if (conn == nullptr) {
        return;
    }

    // A pairing window admits attempts only on the connection carrying its first, so losing that
    // connection closes it (pairing.md "Pairing Window"). Done before the branches below, which
    // release the connection this address identifies.
    if (conn == this->pairing_window_conn_) {
        this->close_pairing_window();
    }

    if (AdmittedEntry* entry = this->find_admitted(conn); entry != nullptr) {
        // Dropping an admitted connection: vacate its slot, then quiesce the client state no
        // remaining admitted connection owns. The slot stays empty; the next nursery
        // establishment is admitted into it.
        //
        // Snapshot before cleanup_connection_state() clears the queued pairing notes (see
        // PairingUiSnapshot), then queue the note_* calls after it.
        const PairingUiSnapshot ui = snapshot_pairing_ui(conn);
        std::shared_ptr<SendspinConnection> dropped = this->vacate_admitted(*entry);
        // Ahead of the slot refresh below: the teardown commands the stream to end before the
        // time filter slot stops naming this connection's filter (see docs/internals.md "Time
        // Filter Slot").
        this->client_->cleanup_connection_state(
            static_cast<uint16_t>(ALL_ROLES_MASK & ~this->roles_owned_by_others(nullptr)));
        this->refresh_published_state();
        this->release_connection(std::move(dropped), goodbye);
        this->client_->note_pairing_ui_dismissals(ui);
        return;
    }

    if (auto it = this->find_in_nursery(conn); it != this->nursery_.end()) {
        // Dropping an unproven connection: no client-state cleanup (it was never admitted). A
        // code session only ever exists on an admitted connection, so no prompt can be showing
        // here; the dismissal is kept for symmetry with the other drop paths and costs two bool
        // reads. Snapshot before release for the same reason as the admitted path above.
        const PairingUiSnapshot ui = snapshot_pairing_ui(conn);
        this->release_nursery_entry(it, goodbye);
        this->client_->note_pairing_ui_dismissals(ui);
    }
    // Not a managed connection: nothing to do (already released).
}

bool ConnectionManager::should_switch_to_new_server(const SendspinConnection* admitted,
                                                    const SendspinConnection* new_conn) const {
    // Applies admission.h::should_admit_connection (activity-priority arbitration). `admitted`
    // may be null (nothing to displace); the pure function's has_admitted=false path always
    // admits.
    const bool has_admitted = admitted != nullptr;
    // An incumbent whose pair-finalize was acked still reports the pre-finalize [PAIRING] set,
    // so tell rule 2 the pairing is no longer in flight rather than rewriting the activities
    // (see admission.h).
    const bool pairing_in_flight = !has_admitted || !admitted->is_pairing_finalized();
    return should_admit_connection(
        /*incoming_activities=*/new_conn->get_activities(),
        /*incoming_server_id=*/new_conn->get_server_id(),
        /*admitted_activities=*/
        has_admitted ? admitted->get_activities() : std::vector<SendspinActivity>{},
        /*admitted_server_id=*/has_admitted ? admitted->get_server_id() : std::string{},
        /*has_admitted=*/has_admitted,
        /*last_playback_server_id=*/this->last_played_server_id_,
        /*admitted_pairing_in_flight=*/pairing_in_flight);
}

void ConnectionManager::note_playback_activity(const SendspinConnection* conn) {
    // connection.md "Multiple servers (server-initiated)": only an ADMITTED connection updates
    // last_played_server_id, and only when it carries PLAYBACK.
    if (this->find_admitted(conn) == nullptr) {
        return;
    }
    if (!conn->has_activity(SendspinActivity::PLAYBACK)) {
        return;
    }
    const std::string& server_id = conn->get_server_id();
    if (server_id.empty()) {
        return;
    }
    // The RAM half updates here, because a later arbitration reads last_played_server_id_; the
    // provider writes for this and for the recency move below wait for
    // SendspinClient::flush_pending_persistence() on the main loop.
    this->client_->note_last_played_server(server_id);
    // A pair-finalize at capacity evicts against the recency order and spares only the records
    // of open connections, so a connection that closes later must not leave its record looking
    // least recent until a deferred move lands. RecordStore::mutex_ is a leaf (docs/conventions.md,
    // "Threading and cross-thread state"), and this holds no other lock.
    if (conn->get_psk_category() == PskCategory::LONG_TERM) {
        const std::string& psk_id = conn->get_psk_id();
        if (!psk_id.empty() && this->client_->record_store_->note_record_played(psk_id)) {
            this->client_->request_persist();
        }
    }
}

NurseryEntry* ConnectionManager::promote_or_arbitrate_nursery_entry(NurseryEntry* it) {
    auto conn = std::move(it->conn);
    auto next = this->nursery_.erase(it);

    // Role ownership decides whom the incoming connection competes with: the admitted
    // connections owning one of its roles, or, with no slot free, every admitted one.
    std::array<AdmittedRoles, MAX_ADMITTED> slots{};
    for (size_t i = 0; i < MAX_ADMITTED; ++i) {
        slots[i].occupied = this->admitted_[i].conn != nullptr;
        slots[i].owned_roles = this->admitted_[i].owned_roles;
    }
    const uint16_t incoming_roles = conn->get_active_role_mask();
    const uint32_t conflicts = admission_conflicts(incoming_roles, slots);

    if (conflicts != 0) {
        // The incoming side is always operational (hello + first activate applied), so
        // arbitration always runs on real activity data. No incumbent is ever evicted on timing
        // alone, and the incoming connection must win against every one it conflicts with.
        for (size_t i = 0; i < MAX_ADMITTED; ++i) {
            if ((conflicts & (1U << i)) == 0 ||
                this->should_switch_to_new_server(this->admitted_[i].conn.get(), conn.get())) {
                continue;
            }
            SS_LOGI(TAG, "Admission arbitration: reject incoming (keep admitted)");
            // Pairing connections receive pair/abort first (the reference dismissal for a
            // displaced pairing attempt); the goodbye after it is a benign over-send, since the
            // transport layer has no close-without-goodbye path to use instead.
            if (conn->has_activity(SendspinActivity::PAIRING)) {
                conn->send_app_json(format_pair_abort_message(PairAbortReason::CONCURRENT_ATTEMPT,
                                                              this->json_arena()));
            }
            this->release_connection(std::move(conn), SendspinGoodbyeReason::CONCURRENT_ATTEMPT);
            return next;
        }
        SS_LOGI(TAG, "Admission arbitration: switch to new server");
        for (size_t i = 0; i < MAX_ADMITTED; ++i) {
            if ((conflicts & (1U << i)) != 0) {
                this->drop_connection(this->admitted_[i].conn.get(),
                                      SendspinGoodbyeReason::ANOTHER_SERVER);
            }
        }
    }

    SendspinConnection* admitted = conn.get();
    this->install_admitted(std::move(conn),
                           claimable_roles(incoming_roles, this->roles_owned_by_others(nullptr)));

    // Notify the client and record playback activity, only for the winner.
    this->note_playback_activity(admitted);

    // An activate declaring pairing alone is not announced to the client as operational until
    // pairing finishes and the post-finalize re-handshake completes; one that also declares
    // playback is announced first, because pairing.md "Entering and leaving pairing" leaves
    // active_roles and streams untouched and going operational is what clears any stale pairing
    // state before the new attempt. A playback-capable connection may carry active roles with
    // pairing alone (messaging.md "server/activate"), and those still owe the initial
    // client/state (messaging.md "client/state"), published without going operational.
    const auto& activities = admitted->get_activities();
    const std::optional<SendspinPairMethod> pairing_method =
        selected_pairing_method(activities, admitted->get_pairing_method());
    const bool selects_pairing = pairing_method.has_value();

    if (!selects_pairing || contains_activity(activities, SendspinActivity::PLAYBACK)) {
        this->client_->on_handshake_complete(admitted);
    }
    if (selects_pairing) {
        SS_LOGI(TAG, "Pairing activate received (%s): entering pairing for server_id=%s",
                to_cstr(pairing_method.value()), admitted->get_server_id().c_str());
        this->handle_enter_pairing(admitted);
        // handle_enter_pairing() may have dropped the connection: checked first.
        if (this->find_admitted(admitted) != nullptr &&
            !contains_activity(activities, SendspinActivity::PLAYBACK) &&
            !admitted->get_active_roles().empty()) {
            this->client_->publish_client_state(admitted);
        }
    }

    if (this->find_admitted(admitted) != nullptr) {
        SS_LOGI(TAG, "Connection admitted: server_id=%s", admitted->get_server_id().c_str());
    }
    return next;
}

}  // namespace sendspin
