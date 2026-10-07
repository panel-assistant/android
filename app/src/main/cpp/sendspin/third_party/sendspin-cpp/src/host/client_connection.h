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

/// @file client_connection.h
/// @brief Host build WebSocket client connection using IXWebSocket

#pragma once

#include "connection.h"
#include "platform/types.h"
#include "sendspin/types.h"
#include <ixwebsocket/IXWebSocket.h>

#include <atomic>
#include <cstddef>
#include <cstdint>
#include <memory>
#include <string>

namespace sendspin {

/**
 * @brief Outbound WebSocket connection to a Sendspin server (host build, IXWebSocket)
 *
 * Connects to a server URL and hands each incoming message to the protocol task through the
 * inbound ring. A lost connection is not reopened: connect_to() opens a new one.
 */
class SendspinClientConnection : public SendspinConnection {
public:
    /// @brief Bound on the opening handshake (TCP connect and WebSocket upgrade), in seconds
    ///
    /// The nursery's establish window (NURSERY_ESTABLISH_TIMEOUT_S in connection_manager.h, 30 s):
    /// an outbound upgrade may legitimately be slow (a proxy, a busy server; see
    /// ConnectionLifecycle.SlowOutboundSurvivesUpgradeTier), so it gets the whole window rather
    /// than the inbound server's 3 s, and the nursery reaps it at that same deadline anyway. It is
    /// also the bound on the destructor for a connection whose upgrade is in flight:
    /// IXWebSocket clears its cancellation flag when it enters the handshake, so a close() that
    /// lands between start() and that point is forgotten and ix::WebSocket::stop() waits for the
    /// handshake to finish or time out (IXWebSocket's own default is 60 s).
    static constexpr int HANDSHAKE_TIMEOUT_SECS = 30;

    /// @brief How long a released connection stays parked for reaping before it is dropped with
    /// its transport still open (ReapEntry in connection_manager.h): the handshake timeout, by
    /// when IXWebSocket has given the attempt up, since the release does not cancel an attempt
    /// that has not opened (see close_transport_now()). A handshake that began after the release
    /// runs a little past it, which the destructor's join then pays.
    static constexpr uint32_t CONNECT_TIMEOUT_MS =
        static_cast<uint32_t>(HANDSHAKE_TIMEOUT_SECS) * 1000U;

    /// @brief Constructs a client connection to the given WebSocket URL
    explicit SendspinClientConnection(std::string url);

    ~SendspinClientConnection() override;

    /// @brief Initiates the WebSocket connection to the server
    void start() override;

    /// @brief Sends a goodbye message, then stops the transport (a join of IX's worker thread)
    void disconnect(SendspinGoodbyeReason reason) override;

    /// @brief Closes the transport immediately without blocking (see base class doc comment).
    /// Safe to call from IX's own worker thread, unlike disconnect() -> ws_->stop(). An attempt
    /// whose upgrade has not completed is left running: IXWebSocket's close() can wait out its
    /// handshake then, so the attempt ends on its own (its failure reports the close), is closed
    /// by the Open handler if it opens, or is stopped by the destructor.
    void close_transport_now() override;

    /// @brief Sends a text message to the server
    SsErr send_text_message(const std::string& message) override;

    /// @brief Sends a binary message to the server
    SsErr send_binary_message(const uint8_t* data, size_t len) override;

    /// @brief No-op on host builds; task configuration is an ESP-IDF concept. Both parameters are
    /// accepted and ignored: the host build has no analogue of a FreeRTOS task priority or stack
    /// size, since IXWebSocket's worker thread uses the OS default stack.
    // cppcheck-suppress functionStatic
    // Instance method by API design, matching the ESP build's set_task_config() (see
    // src/esp/client_connection.h): both platforms expose the same shape so callers do not need
    // to special-case one over the other. (The "missing override" half of cppcheck's message is
    // also a false positive: the host and ESP SendspinClientConnection classes are separate,
    // platform-selected-at-build-time types, not runtime polymorphic siblings.)
    void set_task_config(unsigned /*priority*/, size_t /*stack_size*/) {}

    /// @brief Returns true if the WebSocket connection is currently open
    bool is_connected() const override {
        return this->connected_;
    }

    bool is_outbound() const override {
        return true;
    }

protected:
    /// @brief Registers the IXWebSocket message callback to handle open, close, data, and error
    /// events
    void setup_callbacks();

    /// @brief Shared implementation for send_text_message() and send_binary_message()
    /// @param is_binary Sends as an IX binary frame when true, text frame when false.
    /// @param data      Payload bytes to send.
    /// @param len       Number of bytes in `data`.
    SsErr send_ws_frame(bool is_binary, const uint8_t* data, size_t len);

    // ========================================
    // Member variables
    // ========================================

    // Struct fields

    /// @brief The WebSocket server URL
    std::string url_;

    // Pointer fields

    /// @brief The IXWebSocket instance managing the connection
    std::unique_ptr<ix::WebSocket> ws_;

    // 32-bit fields

    // 8-bit fields

    /// @brief Whether the websocket is currently connected. Written by the IXWebSocket
    /// callback thread, read cross-thread via is_connected(), hence atomic.
    std::atomic<bool> connected_{false};
};

}  // namespace sendspin
