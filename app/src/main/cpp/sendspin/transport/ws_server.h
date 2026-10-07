// Inbound listener stand-in for the app transport (see PATCHES.md). The panel never listens: it only
// connects out to Panel Assistant, so this server never starts and never delivers a connection.
#pragma once

#include "sendspin/config.h"

#include <cstddef>
#include <cstdint>
#include <functional>
#include <memory>

namespace sendspin {

class SendspinClient;
class SendspinServerConnection;

class SendspinWsServer {
public:
    using NewConnectionCallback = std::function<bool(const std::shared_ptr<SendspinServerConnection>&)>;
    using WakeCallback = std::function<void()>;

    bool start(SendspinClient* /*client*/, bool /*psram*/, unsigned /*priority*/, size_t /*stack*/) {
        return false;
    }
    void stop() {}
    uint32_t tick() { return UINT32_MAX; }
    void set_max_connections(uint8_t /*max*/) {}
    void set_port(uint16_t /*port*/) {}
    void set_discard_capacity(size_t /*bytes*/) {}
    void set_ctrl_port(uint16_t /*port*/) {}
    void set_new_connection_callback(NewConnectionCallback&& /*callback*/) {}
    void set_wake_callback(WakeCallback&& /*callback*/) {}
    bool is_started() const { return false; }
};

}  // namespace sendspin
