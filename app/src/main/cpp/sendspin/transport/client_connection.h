// Outbound Sendspin connection carried by the Panel Assistant session (see PATCHES.md).
//
// The library's IXWebSocket client is replaced by a bridge: start() asks the app to open the
// connection, sends become queued frames the app sends as session commands, and the app hands back the
// open, each frame Panel Assistant sent on the session (in session order) and the close. Frames are
// complete WebSocket messages.
#pragma once

#include "bridge.h"
#include "connection.h"
#include "platform/types.h"
#include "sendspin/types.h"

#include <atomic>
#include <cstddef>
#include <cstdint>
#include <string>
#include <vector>

namespace sendspin {

class SendspinClientConnection : public SendspinConnection {
public:
    /// Bound on the opening handshake, as in the IXWebSocket transport (the nursery's window).
    static constexpr int HANDSHAKE_TIMEOUT_SECS = 30;
    static constexpr uint32_t CONNECT_TIMEOUT_MS = static_cast<uint32_t>(HANDSHAKE_TIMEOUT_SECS) * 1000U;

    explicit SendspinClientConnection(std::string url);
    ~SendspinClientConnection() override;

    void start() override;
    void disconnect(SendspinGoodbyeReason reason) override;
    void close_transport_now() override;
    SsErr send_text_message(const std::string& message) override;
    SsErr send_binary_message(const uint8_t* data, size_t len) override;
    void set_task_config(unsigned /*priority*/, size_t /*stack_size*/) {}
    bool is_connected() const override { return this->connected_; }
    bool is_outbound() const override { return true; }

    // Called by the bridge, on the app's socket thread, while this connection is the bridge's current one.
    void on_bridge_open();
    void on_bridge_message(const uint8_t* data, size_t len, bool is_text, int64_t receive_time_us);
    void on_bridge_closed();

protected:
    SsErr send_frame(bool is_binary, const uint8_t* data, size_t len);

    std::string url_;
    /// The bridge's id for this connection's socket, 0 before start().
    uint32_t transport_id_{0};
    std::atomic<bool> connected_{false};
    std::atomic<bool> closed_{false};
};

}  // namespace sendspin

/// The bridge between the library's connection and the app's socket (implemented in client_connection.cpp).
namespace sendspin_bridge {
// --- Library side ---
/// Make [connection] current and ask the app to open [url]; returns its transport id.
uint32_t open(sendspin::SendspinClientConnection* connection, const std::string& url);
/// Queue one frame for the socket [transport_id]; false when that socket is no longer current or the queue is full.
bool send(uint32_t transport_id, bool binary, const uint8_t* data, size_t len);
/// Ask the app to close the socket [transport_id].
void close(uint32_t transport_id);
/// [connection] is being destroyed: no further bridge call may reach it.
void forget(sendspin::SendspinClientConnection* connection);

}  // namespace sendspin_bridge
