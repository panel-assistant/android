// See client_connection.h.
#include "client_connection.h"

#include "platform/logging.h"
#include "platform/types.h"
#include "sendspin/types.h"

#include <algorithm>
#include <chrono>
#include <condition_variable>
#include <cstring>
#include <deque>
#include <mutex>
#include <utility>

namespace sendspin {

static const char* const TAG = "sendspin.client_connection";

SendspinClientConnection::SendspinClientConnection(std::string url) : url_(std::move(url)) {}

SendspinClientConnection::~SendspinClientConnection() {
    sendspin_bridge::forget(this);
    if (this->transport_id_ != 0) {
        sendspin_bridge::close(this->transport_id_);
    }
}

void SendspinClientConnection::start() {
    if (this->transport_id_ != 0) {
        SS_LOGW(TAG, "Client already started");
        return;
    }
    this->transport_id_ = sendspin_bridge::open(this, this->url_);
    SS_LOGD(TAG, "Client connection starting");
}

void SendspinClientConnection::disconnect(SendspinGoodbyeReason reason) {
    if (!this->is_connected()) {
        return;
    }
    // Queued ahead of the close, so the app writes the goodbye before it closes the socket.
    this->send_goodbye_reason(reason);
    this->connected_ = false;
    sendspin_bridge::close(this->transport_id_);
}

void SendspinClientConnection::close_transport_now() {
    // Non-blocking: the close is queued for the app's socket thread, whose close report follows.
    this->connected_ = false;
    if (this->transport_id_ != 0) {
        sendspin_bridge::close(this->transport_id_);
    }
}

SsErr SendspinClientConnection::send_text_message(const std::string& message) {
    return this->send_frame(false, reinterpret_cast<const uint8_t*>(message.data()), message.size());
}

SsErr SendspinClientConnection::send_binary_message(const uint8_t* data, size_t len) {
    return this->send_frame(true, data, len);
}

SsErr SendspinClientConnection::send_frame(bool is_binary, const uint8_t* data, size_t len) {
    if (!this->is_connected()) {
        return SsErr::INVALID_STATE;
    }
    if (!sendspin_bridge::send(this->transport_id_, is_binary, data, len)) {
        SS_LOGE(TAG, "Failed to queue a %s message", is_binary ? "binary" : "text");
        return SsErr::FAIL;
    }
    return SsErr::OK;
}

void SendspinClientConnection::on_bridge_open() {
    this->connected_ = true;
    this->mark_ws_upgraded();
    this->wake_protocol_task();
    // Released while it was still connecting: close it now that it has opened (as the IX transport does).
    if (this->inbound_gate_.is_detached()) {
        this->connected_ = false;
        sendspin_bridge::close(this->transport_id_);
    }
}

void SendspinClientConnection::on_bridge_message(const uint8_t* data, size_t len, bool is_text,
                                                 int64_t receive_time_us) {
    const InboundTarget target = this->begin_inbound_message(len, is_text, receive_time_us);
    if (target.route == InboundRoute::RECEIVE) {
        std::copy(data, data + len, target.data);
        this->end_inbound_message(true);
    }
}

void SendspinClientConnection::on_bridge_closed() {
    if (this->closed_.exchange(true)) {
        return;
    }
    this->connected_ = false;
    // A socket that never opened reports here too: the attempt failed. The protocol task reports the loss.
    this->notify_transport_closed();
}

}  // namespace sendspin

namespace sendspin_bridge {
namespace {
// The connection the app's socket feeds, and its transport id. Held across a delivery, so a
// connection cannot be destroyed while the app's thread is inside it. Recursive: a delivery may
// lead the library to close the transport, which takes only the queue lock, but stay safe anyway.
std::recursive_mutex connection_mutex;
sendspin::SendspinClientConnection* current = nullptr;
std::atomic<uint32_t> current_id{0};
uint32_t last_id = 0;

std::mutex queue_mutex;
std::condition_variable queue_ready;
std::deque<Outbound> queue;
constexpr size_t MAX_QUEUED = 4096;

bool enqueue(Outbound&& item, bool required) {
    {
        std::lock_guard<std::mutex> lock(queue_mutex);
        if (!required && queue.size() >= MAX_QUEUED) return false;
        queue.push_back(std::move(item));
    }
    queue_ready.notify_all();
    return true;
}
}  // namespace

uint32_t open(sendspin::SendspinClientConnection* connection, const std::string& url) {
    uint32_t id;
    {
        std::lock_guard<std::recursive_mutex> lock(connection_mutex);
        id = ++last_id;
        if (id == 0) id = ++last_id;
        current = connection;
        current_id.store(id);
    }
    enqueue(Outbound{OPEN, id, std::vector<uint8_t>(url.begin(), url.end())}, true);
    return id;
}

bool send(uint32_t transport_id, bool binary, const uint8_t* data, size_t len) {
    if (transport_id == 0 || transport_id != current_id.load()) return false;
    return enqueue(Outbound{binary ? BINARY : TEXT, transport_id, std::vector<uint8_t>(data, data + len)}, false);
}

void close(uint32_t transport_id) {
    if (transport_id != 0) enqueue(Outbound{CLOSE, transport_id, {}}, true);
}

void forget(sendspin::SendspinClientConnection* connection) {
    std::lock_guard<std::recursive_mutex> lock(connection_mutex);
    if (current == connection) {
        current = nullptr;
        current_id.store(0);
    }
}

bool take(Outbound& out, int timeout_ms) {
    std::unique_lock<std::mutex> lock(queue_mutex);
    if (queue.empty()) {
        queue_ready.wait_for(lock, std::chrono::milliseconds(timeout_ms), [] { return !queue.empty(); });
    }
    if (queue.empty()) return false;
    out = std::move(queue.front());
    queue.pop_front();
    return true;
}

void deliver(uint32_t transport_id, uint8_t type, const uint8_t* data, size_t len, int64_t receive_time_us) {
    std::lock_guard<std::recursive_mutex> lock(connection_mutex);
    if (current == nullptr || transport_id == 0 || transport_id != current_id.load()) return;
    switch (type) {
        case OPEN: current->on_bridge_open(); break;
        case TEXT: current->on_bridge_message(data, len, true, receive_time_us); break;
        case BINARY: current->on_bridge_message(data, len, false, receive_time_us); break;
        case CLOSE: current->on_bridge_closed(); break;
        default: break;
    }
}

void reset() {
    {
        std::lock_guard<std::mutex> lock(queue_mutex);
        queue.clear();
    }
    queue_ready.notify_all();
}
}  // namespace sendspin_bridge
