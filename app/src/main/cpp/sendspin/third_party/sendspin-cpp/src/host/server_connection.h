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

/// @file server_connection.h
/// @brief Host build WebSocket server-side connection using IXWebSocket

#pragma once

#include "connection.h"
#include "platform/types.h"
#include "sendspin/types.h"
#include <ixwebsocket/IXWebSocket.h>

#include <cstddef>
#include <cstdint>
#include <memory>
#include <string>

namespace sendspin {

/**
 * @brief Inbound WebSocket connection from a server to the host listener (host build, IXWebSocket)
 *
 * Wraps a shared IXWebSocket handed off by SendspinWsServer. Incoming messages arrive through
 * handle_message() on the server's callback thread, which hands each to the protocol task through
 * the inbound ring. start() is a no-op because the transport is already open on construction.
 */
class SendspinServerConnection : public SendspinConnection {
public:
    /// @brief Constructs a server connection wrapping an IXWebSocket
    explicit SendspinServerConnection(std::shared_ptr<ix::WebSocket> ws);

    ~SendspinServerConnection() override = default;

    /// @brief No-op on server connections; the transport is already established when this is called
    void start() override;

    /// @brief Sends a goodbye message, then closes the connection
    void disconnect(SendspinGoodbyeReason reason) override;

    /// @brief Closes the transport immediately without blocking (see base class doc comment).
    /// Delegates to trigger_close(), the same async primitive disconnect() closes with.
    void close_transport_now() override;

    /// @brief Whether the underlying WebSocket connection is open
    bool is_connected() const override;

    /// @brief Sends a text message to the connected client
    SsErr send_text_message(const std::string& message) override;

    /// @brief Sends a binary message to the connected client
    SsErr send_binary_message(const uint8_t* data, size_t len) override;

    /// @brief Requests the WebSocket connection to close
    void trigger_close();

    /// @brief Hands an incoming complete message from IXWebSocket to the protocol task
    /// Called from the ws_server's message callback, on the connection's IXWebSocket thread.
    /// @param data The complete message payload received from IXWebSocket
    /// @param is_binary true if the message is binary, false if text
    /// @param receive_time Server-relative timestamp at which the message was received
    void handle_message(const std::string& data, bool is_binary, int64_t receive_time);

protected:
    /// @brief Shared implementation for send_text_message() and send_binary_message()
    /// @param is_binary Sends as an IX binary frame when true, text frame when false.
    /// @param data      Payload bytes to send.
    /// @param len       Number of bytes in `data`.
    SsErr send_ws_frame(bool is_binary, const uint8_t* data, size_t len);

    // Pointer fields

    /// @brief The IXWebSocket instance for this connection (shared with the server)
    std::shared_ptr<ix::WebSocket> ws_;
};

}  // namespace sendspin
