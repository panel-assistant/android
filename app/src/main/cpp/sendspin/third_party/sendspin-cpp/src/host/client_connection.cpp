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

#include "client_connection.h"

#include "platform/logging.h"
#include "platform/time.h"
#include "platform/types.h"
#include "protocol_messages.h"
#include "sendspin/types.h"
#include <ixwebsocket/IXWebSocketMessage.h>
#include <ixwebsocket/IXWebSocketMessageType.h>

#include <algorithm>
#include <cstring>
#include <utility>

namespace sendspin {

static const char* const TAG = "sendspin.client_connection";

// ============================================================================
// Constructor / Destructor
// ============================================================================

SendspinClientConnection::SendspinClientConnection(std::string url) : url_(std::move(url)) {}

SendspinClientConnection::~SendspinClientConnection() {
    if (this->ws_) {
        this->ws_->stop();
        this->ws_.reset();
    }
}

// ============================================================================
// Public API
// ============================================================================

void SendspinClientConnection::start() {
    if (this->ws_) {
        SS_LOGW(TAG, "Client already started, stopping first");
        this->ws_->stop();
        this->ws_.reset();
    }

    this->ws_ = std::make_unique<ix::WebSocket>();
    this->ws_->setUrl(this->url_);
    this->ws_->disableAutomaticReconnection();
    // Bounds the handshake, and with it the destructor's join for a connection whose upgrade is
    // in flight; see HANDSHAKE_TIMEOUT_SECS.
    this->ws_->setHandshakeTimeout(HANDSHAKE_TIMEOUT_SECS);

    this->setup_callbacks();

    this->ws_->start();
    SS_LOGD(TAG, "Client connection starting to %s", this->url_.c_str());
}

void SendspinClientConnection::disconnect(SendspinGoodbyeReason reason) {
    if (!this->is_connected()) {
        return;
    }

    // The send is synchronous, so the goodbye has been written (or failed) before the stop.
    this->send_goodbye_reason(reason);
    if (this->ws_) {
        this->ws_->stop();
    }
}

void SendspinClientConnection::close_transport_now() {
    // ws_->stop() (used by disconnect() above) joins IX's own worker thread, so it deadlocks (and
    // on host, crashes via an uncaught std::system_error -> std::terminate()) when called from a
    // callback already running on that thread. ws_->close() is async and does not join, so it is
    // safe here. The loss is reported by the protocol task, which sees the detached inbound gate
    // (see SendspinConnection::close_silently() and fail_inbound()); the resulting Close event
    // reports it again, which the manager tolerates (drop_connection() no-ops on a connection it
    // no longer manages).
    //
    // An attempt whose upgrade has not completed is left to end on its own (its failure is an
    // Error, which reports the close) or to be stopped by the destructor: before the Open,
    // ws_->close() takes the transport mutex the connect holds for the whole handshake, and when
    // the cancellation it sets lands before the handshake clears it, it waits out the handshake.
    // The Open handler closes an attempt released before it opened (see setup_callbacks()).
    this->connected_ = false;
    if (this->ws_ && this->is_ws_upgraded()) {
        this->ws_->close();
    }
}

SsErr SendspinClientConnection::send_text_message(const std::string& message) {
    return this->send_ws_frame(false, reinterpret_cast<const uint8_t*>(message.data()),
                               message.size());
}

SsErr SendspinClientConnection::send_binary_message(const uint8_t* data, size_t len) {
    return this->send_ws_frame(true, data, len);
}

SsErr SendspinClientConnection::send_ws_frame(bool is_binary, const uint8_t* data, size_t len) {
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

// ============================================================================
// Private helpers / callbacks
// ============================================================================

void SendspinClientConnection::setup_callbacks() {
    this->ws_->setOnMessageCallback([this](const ix::WebSocketMessagePtr& msg) {
        int64_t receive_time = platform_time_us();

        switch (msg->type) {
            case ix::WebSocketMessageType::Open:
                SS_LOGD(TAG, "WebSocket connected to %s", this->url_.c_str());
                this->connected_ = true;
                // The protocol task starts the Noise handshake on its next tick. This can run
                // during the destructor's ws_->stop() join, so it reaches no owner of this
                // connection.
                this->mark_ws_upgraded();
                this->wake_protocol_task();
                // Released while it was still connecting: close_transport_now() left the attempt
                // running, so close it now that the close cannot wait on the handshake. A release
                // that races this check (the detach and this load, the upgrade mark and the
                // release's own load of it, are a store-load pair either side can miss) is caught
                // by the reap instead, which drops a parked connection once its upgrade is marked
                // (the wake above schedules it).
                if (this->inbound_gate_.is_detached()) {
                    this->connected_ = false;
                    this->ws_->close();
                }
                break;

            case ix::WebSocketMessageType::Close:
                SS_LOGD(TAG, "WebSocket disconnected from %s", this->url_.c_str());
                this->connected_ = false;
                // The protocol task reports the loss once the messages before it are processed.
                this->notify_transport_closed();
                break;

            case ix::WebSocketMessageType::Message: {
                // IXWebSocket delivers complete reassembled messages, so every message takes the
                // single-frame path: one copy, from IXWebSocket's string into a ring item (or,
                // before admission, into the fallback buffer).
                const std::string& data = msg->str;
                const InboundTarget target =
                    this->begin_inbound_message(data.size(), !msg->binary, receive_time);
                if (target.route == InboundRoute::RECEIVE) {
                    std::copy(data.begin(), data.end(), target.data);
                    this->end_inbound_message(true);
                }
                break;
            }

            case ix::WebSocketMessageType::Error:
                SS_LOGE(TAG, "WebSocket error on connection to %s: %s", this->url_.c_str(),
                        msg->errorInfo.reason.c_str());
                // With automatic reconnection off, IXWebSocket reports a failed connect or
                // upgrade as an Error and its thread then exits without a Close event, so this
                // is the close of a connection that never opened.
                this->notify_transport_closed();
                break;

            default:
                break;
        }
    });
}

}  // namespace sendspin
