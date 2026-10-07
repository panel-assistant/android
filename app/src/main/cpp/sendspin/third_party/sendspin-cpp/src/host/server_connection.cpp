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

#include "server_connection.h"

#include "platform/logging.h"
#include "platform/types.h"
#include "protocol_messages.h"
#include "sendspin/types.h"

#include <algorithm>
#include <string>
#include <utility>

namespace sendspin {

static const char* const TAG = "sendspin.server_conn";

SendspinServerConnection::SendspinServerConnection(std::shared_ptr<ix::WebSocket> ws)
    : ws_(std::move(ws)) {
    // No TCP_NODELAY setsockopt is needed here: IXWebSocket disables Nagle on accepted sockets
    // itself. SocketServer::run() calls SocketConnect::configure(clientFd) on every accepted
    // client fd (the same routine that sets TCP_NODELAY on outbound connects) so time messages
    // on this host-server path are not subject to Nagle coalescing delay.
}

void SendspinServerConnection::start() {
    // No action needed: the connection is constructed by the ws_server only after the WebSocket
    // Open event, so the transport is already established and upgraded by the time it exists.
}

void SendspinServerConnection::disconnect(SendspinGoodbyeReason reason) {
    if (!this->is_connected()) {
        return;
    }

    // The send is synchronous, so the goodbye has been written (or failed) before the close.
    this->send_goodbye_reason(reason);
    this->trigger_close();
}

void SendspinServerConnection::close_transport_now() {
    // trigger_close() -> ws_->close() is already async/non-blocking (the same primitive
    // disconnect() closes with), so it is safe from any thread. The resulting Close event reaches
    // notify_transport_closed() through the ws_server.
    this->trigger_close();
}

bool SendspinServerConnection::is_connected() const {
    return this->ws_ && this->ws_->getReadyState() == ix::ReadyState::Open;
}

SsErr SendspinServerConnection::send_text_message(const std::string& message) {
    return this->send_ws_frame(false, reinterpret_cast<const uint8_t*>(message.data()),
                               message.size());
}

SsErr SendspinServerConnection::send_binary_message(const uint8_t* data, size_t len) {
    return this->send_ws_frame(true, data, len);
}

SsErr SendspinServerConnection::send_ws_frame(bool is_binary, const uint8_t* data, size_t len) {
    if (!this->is_connected()) {
        return SsErr::INVALID_STATE;
    }

    std::string buf(reinterpret_cast<const char*>(data), len);
    auto info = is_binary ? this->ws_->sendBinary(buf) : this->ws_->send(buf);
    if (!info.success) {
        if (is_binary) {
            SS_LOGE(TAG, "Failed to send binary message");
        } else {
            SS_LOGE(TAG, "Failed to send text message");
        }
        return SsErr::FAIL;
    }

    return SsErr::OK;
}

void SendspinServerConnection::trigger_close() {
    if (this->ws_) {
        this->ws_->close();
    }
}

void SendspinServerConnection::handle_message(const std::string& data, bool is_binary,
                                              int64_t receive_time) {
    // IXWebSocket hands over each message reassembled, so every message takes the single-frame
    // path: one copy, from IXWebSocket's string into a ring item (or, before admission, into
    // the fallback buffer).
    const InboundTarget target = this->begin_inbound_message(data.size(), !is_binary, receive_time);
    if (target.route != InboundRoute::RECEIVE) {
        return;
    }
    std::copy(data.begin(), data.end(), target.data);
    this->end_inbound_message(true);
}

}  // namespace sendspin
