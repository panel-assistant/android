// The app side of the Sendspin socket bridge (see client_connection.h), for the JNI shim. Free of
// library headers so the shim needs none of the library's private include paths.
#pragma once

#include <cstddef>
#include <cstdint>
#include <vector>

namespace sendspin_bridge {
// --- App side (the JNI shim) ---
enum : uint8_t { OPEN = 1, TEXT = 2, BINARY = 3, CLOSE = 4 };
struct Outbound {
    uint8_t type;
    uint32_t transport_id;
    std::vector<uint8_t> data;  // the URL for OPEN, the payload for TEXT and BINARY
};
/// The next request for the app's socket, waiting up to [timeout_ms]; false when none arrived.
bool take(Outbound& out, int timeout_ms);
/// The app's socket [transport_id] opened (OPEN), received a message (TEXT, BINARY) or closed (CLOSE).
void deliver(uint32_t transport_id, uint8_t type, const uint8_t* data, size_t len, int64_t receive_time_us);
/// Drop every queued request and wake a waiting take(); called after the client stops.
void reset();
}  // namespace sendspin_bridge
