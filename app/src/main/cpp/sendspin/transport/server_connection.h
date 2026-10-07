// Inbound connection stand-in for the app transport (see PATCHES.md). Never constructed: the
// listener in ws_server.h never starts. Present so the connection manager compiles unchanged.
#pragma once

#include "connection.h"
#include "platform/types.h"
#include "sendspin/types.h"

#include <cstddef>
#include <cstdint>
#include <string>

namespace sendspin {

class SendspinServerConnection : public SendspinConnection {
public:
    void start() override {}
    void disconnect(SendspinGoodbyeReason /*reason*/) override {}
    void close_transport_now() override {}
    bool is_connected() const override { return false; }
    SsErr send_text_message(const std::string& /*message*/) override { return SsErr::INVALID_STATE; }
    SsErr send_binary_message(const uint8_t* /*data*/, size_t /*len*/) override { return SsErr::INVALID_STATE; }
};

}  // namespace sendspin
