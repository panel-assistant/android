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

#include "ws_server.h"

#include "connection.h"
#include "platform/logging.h"
#include "platform/time.h"
#include "server_connection.h"
#include <ixwebsocket/IXConnectionState.h>
#include <ixwebsocket/IXSocketServer.h>
#include <ixwebsocket/IXWebSocket.h>
#include <ixwebsocket/IXWebSocketMessage.h>
#include <ixwebsocket/IXWebSocketMessageType.h>

#include <atomic>
#include <cstddef>
#include <utility>

namespace sendspin {

static const char* const TAG = "sendspin.ws_server";

/// @brief Deadline (seconds) for an accepted socket to complete its WebSocket handshake.
///
/// This is the host counterpart of the ESP build's WS_UPGRADE_TIMEOUT_US: sockets that never
/// speak WebSocket are closed inside the transport layer and stay invisible to the manager.
/// IXWebSocket's own server default happens to be the same 3 s, but the delivery contract in
/// ws_server.h depends on the bound existing, so it is pinned here explicitly rather than
/// inherited from a dependency default an IXWebSocket upgrade could silently rescope.
static constexpr int WS_HANDSHAKE_TIMEOUT_SECS = 3;

SendspinWsServer::~SendspinWsServer() {
    this->stop();
}

bool SendspinWsServer::start(SendspinClient* client, bool /*task_stack_in_psram*/,
                             unsigned /*task_priority*/, size_t /*task_stack_size*/) {
    if (this->server_ != nullptr) {
        SS_LOGW(TAG, "Server already started");
        return true;
    }

    this->client_ = client;

    // Create IXWebSocket server on the configured port. max_connections_ is enforced here (IX
    // closes surplus sockets at accept), so the configured budget is real on host, matching the
    // ESP build's httpd max_open_sockets.
    this->server_ = std::make_unique<ix::WebSocketServer>(
        this->server_port_, "0.0.0.0", ix::SocketServer::kDefaultTcpBacklog, this->max_connections_,
        WS_HANDSHAKE_TIMEOUT_SECS);

    this->server_->setOnConnectionCallback(
        [this](const std::weak_ptr<ix::WebSocket>& weak_ws,
               const std::shared_ptr<ix::ConnectionState>& /*state*/) {
            auto ws = weak_ws.lock();
            if (!ws) {
                return;
            }

            // A synthetic sockfd from a monotonic counter names the socket in the logs.
            static std::atomic<int> next_synthetic_sockfd{1};
            int synthetic_sockfd = next_synthetic_sockfd.fetch_add(1);
            if (synthetic_sockfd <= 0) {
                // Wrapped after ~2 billion connections; restart the sequence
                next_synthetic_sockfd.store(1);
                synthetic_sockfd = next_synthetic_sockfd.fetch_add(1);
            }

            SS_LOGD(TAG, "Accepted connection (synthetic sockfd %d), awaiting WS upgrade",
                    synthetic_sockfd);

            // This callback fires at TCP accept, before the WebSocket handshake. Nothing is
            // created or delivered here: a socket that never completes the handshake (port scan,
            // health check, plain HTTP) is IXWebSocket's problem; its server-side handshake timeout
            // (3 s) closes such sockets without ever surfacing an event. The manager only ever
            // learns about connections that reach Open.
            //
            // The connection delivered for this socket, observed (the manager is its long-term
            // owner). Filled at Open so the Close event can report the connection by identity. A
            // never-delivered socket leaves it empty, and its Close is not reported (the manager
            // never knew the connection existed). Every callback below runs on this socket's
            // own IXWebSocket thread, so it needs no lock.
            auto delivered = std::make_shared<std::weak_ptr<SendspinServerConnection>>();
            ws->setOnMessageCallback([this, synthetic_sockfd, weak_ws,
                                      delivered](const ix::WebSocketMessagePtr& msg) {
                int64_t receive_time = platform_time_us();

                if (msg->type == ix::WebSocketMessageType::Message) {
                    // The reference keeps the connection alive for the hand-off. A connection the
                    // manager rejected at delivery or has since released is detached, so its
                    // frames are dropped inside handle_message().
                    auto conn = delivered->lock();
                    if (conn == nullptr) {
                        SS_LOGD(TAG, "Dropping message for released sockfd %d", synthetic_sockfd);
                        return;
                    }
                    conn->handle_message(msg->str, msg->binary, receive_time);

                } else if (msg->type == ix::WebSocketMessageType::Open) {
                    // The WebSocket upgrade completed: this is where the connection comes
                    // into existence for the rest of the library. Delivering here (rather
                    // than at accept) means the manager never has to reason about sockets
                    // that might not speak WebSocket, and a rejection's goodbye reaches the
                    // peer because the session is already Open.
                    auto self_ws = weak_ws.lock();
                    if (!self_ws) {
                        return;
                    }
                    if (!this->new_connection_callback_) {
                        SS_LOGW(TAG, "No new connection callback set, closing connection");
                        self_ws->close();
                        return;
                    }
                    auto connection =
                        std::make_shared<SendspinServerConnection>(std::move(self_ws));
                    connection->mark_ws_upgraded();
                    *delivered = connection;
                    if (!this->new_connection_callback_(connection)) {
                        // Refused: close the socket. The connection is released here, after the
                        // delivery returned, when `connection` goes out of scope. Unlike ESP,
                        // where the httpd session owns it until the session is freed, nothing
                        // needs to keep it: its destructor is trivial, the IX server holds the
                        // WebSocket, and holding it in `delivered` would make a reference cycle
                        // (WebSocket, this callback, the connection, its ws_) that only the Close
                        // event breaks.
                        connection->trigger_close();
                    }
                } else if (msg->type == ix::WebSocketMessageType::Close) {
                    SS_LOGD(TAG, "Client closed connection (synthetic sockfd %d)",
                            synthetic_sockfd);
                    // lock() fails once the manager (the sole long-term owner) has already
                    // released the connection; there is nothing left to notify it about.
                    // Otherwise the protocol task drops it once the messages before the close
                    // are processed.
                    if (auto conn = delivered->lock()) {
                        conn->notify_transport_closed();
                    }
                } else if (msg->type == ix::WebSocketMessageType::Error) {
                    SS_LOGE(TAG, "WebSocket error: %s", msg->errorInfo.reason.c_str());
                }
            });
        });

    SS_LOGI(TAG, "Starting server on port: %u (max connections: %d)",
            static_cast<unsigned int>(this->server_port_), this->max_connections_);

    auto result = this->server_->listen();
    if (!result.first) {
        SS_LOGE(TAG, "Error starting server: %s", result.second.c_str());
        this->server_.reset();
        return false;
    }

    this->server_->start();
    return true;
}

void SendspinWsServer::stop() {
    if (this->server_ != nullptr) {
        SS_LOGD(TAG, "Stopping server");
        this->server_->stop();
        this->server_.reset();
    }
}

}  // namespace sendspin
