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

/// @file protocol_messages.h
/// @brief Internal protocol types, message envelope structs, and JSON serialization/parsing
/// functions for the Sendspin wire protocol

#pragma once

#include "crypto/psk_wrap.h"
#include "sendspin/color_role.h"
#include "sendspin/config.h"
#include "sendspin/controller_role.h"
#include "sendspin/metadata_role.h"
#include "sendspin/player_role.h"
#include "sendspin/source_role.h"
#include "sendspin/types.h"
#include "sendspin/visualizer_role.h"
#include <ArduinoJson.h>

#include <array>
#include <cstddef>
#include <cstdint>
#include <optional>
#include <string>
#include <vector>

namespace sendspin {

class SendspinArenaAllocator;

// ============================================================================
// Internal protocol types
// ============================================================================

/// @brief Role field values for binary message type bytes
///
/// Bits 7-2 encode the role (upper 6 bits of the type byte); bits 1-0 encode
/// the slot. Each role therefore has 4 slots (IDs = role << 2 through role << 2 + 3).
/// The visualizer role has an expanded 8-slot allocation (IDs 16-23, bits 2-0 as slot)
/// and is dispatched by ID range rather than through this enum.
enum SendspinBinaryRole : uint8_t {
    SENDSPIN_ROLE_PLAYER = 1,   // 000001xx (IDs 4-7)
    SENDSPIN_ROLE_ARTWORK = 2,  // 000010xx (IDs 8-11)
    SENDSPIN_ROLE_SOURCE = 3,   // 000011xx (IDs 12-15), client to server only
};

/// @brief Role field (bits 7-2) of a standard 4-slot binary type byte
/// Valid only for PLAYER/ARTWORK/SOURCE (IDs 4-15); the visualizer's 8-slot range is dispatched
/// by range in SendspinClient::process_binary_message, not through these helpers.
inline uint8_t get_binary_role(uint8_t type) {
    return type >> 2;
}
/// @brief Slot field (bits 1-0) of a standard 4-slot binary type byte; same restriction
inline uint8_t get_binary_slot(uint8_t type) {
    return type & 0x03;
}

/// @brief Size of the big-endian int64 timestamp that follows the type byte in a role's binary
/// message (player, source, and visualizer messages; artwork announces)
inline constexpr size_t BINARY_TIMESTAMP_SIZE = 8;

/// @brief Decodes a big-endian 64-bit value
/// @param bytes Pointer to at least 8 bytes.
inline int64_t be64_to_host(const uint8_t* bytes) {
    uint64_t val = 0;
    for (size_t i = 0; i < sizeof(uint64_t); ++i) {
        val = (val << 8) | bytes[i];
    }
    return static_cast<int64_t>(val);
}

/// @brief Encodes a 64-bit value as big-endian bytes
/// @param bytes [out] Destination for 8 bytes.
inline void host_to_be64(int64_t value, uint8_t* bytes) {
    const auto val = static_cast<uint64_t>(value);
    for (size_t i = 0; i < sizeof(uint64_t); ++i) {
        bytes[i] = static_cast<uint8_t>(val >> ((sizeof(uint64_t) - 1 - i) * 8U));
    }
}

/// @brief Binary message type byte values for known message kinds
enum SendspinBinaryType : uint8_t {
    SENDSPIN_BINARY_PLAYER_AUDIO = SENDSPIN_ROLE_PLAYER << 2,    // Player slot 0: encoded audio
    SENDSPIN_BINARY_ARTWORK_IMAGE = SENDSPIN_ROLE_ARTWORK << 2,  // Artwork slot 0: image data
    SENDSPIN_BINARY_SOURCE_AUDIO = SENDSPIN_ROLE_SOURCE << 2,    // Source slot 0: captured audio
    // Visualizer expanded allocation (IDs 16-23); each data type is its own message
    // carrying exactly one frame of [timestamp:8][data]
    SENDSPIN_BINARY_VISUALIZER_LOUDNESS = 16,  // uint16 A-weighted loudness
    SENDSPIN_BINARY_VISUALIZER_BEAT = 17,      // uint8 flags (bit 0 = downbeat)
    SENDSPIN_BINARY_VISUALIZER_F_PEAK = 18,    // uint16 freq Hz + uint16 amplitude
    SENDSPIN_BINARY_VISUALIZER_SPECTRUM = 19,  // uint16[n_disp_bins] magnitudes
    SENDSPIN_BINARY_VISUALIZER_PEAK = 20,      // uint8 onset strength
    SENDSPIN_BINARY_VISUALIZER_FIRST = 16,     // Start of visualizer ID range
    SENDSPIN_BINARY_VISUALIZER_LAST = 23,      // End of visualizer ID range (21-23 reserved)
};

/// @brief JSON message types sent from the server to the client
enum class SendspinServerToClientMessageType : uint8_t {
    SERVER_HELLO,          // server/hello handshake
    SERVER_ACTIVATE,       // server/activate declares activities and active_roles
    SERVER_TIME,           // server/time clock sync reply
    SERVER_STATE,          // server/state playback state update
    SERVER_COMMAND,        // server/command player command
    STREAM_START,          // stream/start new stream parameters
    STREAM_END,            // stream/end normal stream completion
    STREAM_CLEAR,          // stream/clear immediate buffer flush
    GROUP_UPDATE,          // group/update group membership change
    NOISE_HANDSHAKE,       // noise/handshake in-band re-handshake
    SERVER_PAIR_FINALIZE,  // server/pair-finalize empty ack from server
    PAIR_ABORT,            // pair/abort pairing failure from either side
    SERVER_UNPAIR,         // server/unpair request to drop pairing record
    SERVER_PAIR_INIT,      // server/pair-init: nonce_A
    SERVER_PAIR_AUTH,      // server/pair-auth: pake_msg_1
    SERVER_PAIR_CONFIRM,   // server/pair-confirm: server_kc
    UNKNOWN,               // Unrecognized message type
};

/// @brief Protocol role identifiers used in hello messages and role negotiation
enum class SendspinRole : uint8_t {
    PLAYER,      // Audio playback role
    CONTROLLER,  // Playback command/state role
    METADATA,    // Track metadata role
    ARTWORK,     // Album artwork role
    VISUALIZER,  // Audio visualization role
    COLOR,       // Audio-derived color palette role
    SOURCE,      // Audio capture role (streams to the server)
    COUNT,       // Not a role; bounds the walk in active_role_mask(). Keep last.
};

/// @brief Protocol wire string for a role (e.g. "player@v1")
inline const char* to_cstr(SendspinRole role) {
    switch (role) {
        case SendspinRole::PLAYER:
            return "player@v1";
        case SendspinRole::CONTROLLER:
            return "controller@v1";
        case SendspinRole::METADATA:
            return "metadata@v1";
        case SendspinRole::ARTWORK:
            return "artwork@v1";
        case SendspinRole::VISUALIZER:
            return "visualizer@v1";
        case SendspinRole::COLOR:
            return "color@v1";
        case SendspinRole::SOURCE:
            return "source@v1";
        default:
            return "unknown";
    }
}

/// @brief Whether a versioned role name is in an active_roles set
///
/// The comparison is on the exact versioned name this library implements, not the family, because
/// messaging.md "server/activate" counts "replacement of an active role version" as removal of the
/// version that was active: a client offered `player@v1` that is handed `player@v2` implements
/// neither the new version's behavior nor, any longer, the old one's.
inline bool role_in(const std::vector<std::string>& active_roles, SendspinRole role) {
    const char* name = to_cstr(role);
    for (const auto& active : active_roles) {
        if (active == name) {
            return true;
        }
    }
    return false;
}

/// @brief Bit that represents `role` in an active-role mask
inline uint16_t role_mask_bit(SendspinRole role) {
    return static_cast<uint16_t>(1U << static_cast<uint8_t>(role));
}

static_assert(static_cast<uint8_t>(SendspinRole::COUNT) <= 16, "role mask is a uint16_t");

/// @brief Every role this library implements, as an active-role mask
static constexpr uint16_t ALL_ROLES_MASK =
    static_cast<uint16_t>((1U << static_cast<uint8_t>(SendspinRole::COUNT)) - 1U);

/// @brief Builds the mask of roles this library implements that `active_roles` names.
///
/// The mask is what the receive path reads, so it is built with the same exact-version test role
/// removal uses and the two can never disagree.
inline uint16_t active_role_mask(const std::vector<std::string>& active_roles) {
    uint16_t mask = 0;
    for (uint8_t i = 0; i < static_cast<uint8_t>(SendspinRole::COUNT); ++i) {
        const auto role = static_cast<SendspinRole>(i);
        if (role_in(active_roles, role)) {
            mask |= role_mask_bit(role);
        }
    }
    return mask;
}

/// @brief Activity declared in a server/activate message.
/// A connection declares a SET of activities rather than a single reason (messaging.md
/// "server/activate").
enum class SendspinActivity : uint8_t {
    PLAYBACK,  // Active or upcoming playback
    PAIRING,   // A pairing exchange
};

/// @brief Protocol wire string for an activity (e.g. "playback")
inline const char* to_cstr(SendspinActivity activity) {
    switch (activity) {
        case SendspinActivity::PLAYBACK:
            return "playback";
        case SendspinActivity::PAIRING:
            return "pairing";
        default:
            return "unknown";
    }
}

/// @brief Parses a wire string into a SendspinActivity; nullopt if unrecognized
inline std::optional<SendspinActivity> activity_from_string(const std::string& str) {
    if (str == "playback") {
        return SendspinActivity::PLAYBACK;
    }
    if (str == "pairing") {
        return SendspinActivity::PAIRING;
    }
    return std::nullopt;
}

/// @brief Pairing method on the wire (advertised in client/hello, selected in server/activate).
enum class SendspinPairMethod : uint8_t {
    PAIRING_PSK,           // Out-of-band distributed Pairing PSK
    DYNAMIC_PAIRING_CODE,  // Per-session pairing code via PAKE
    STATIC_PAIRING_CODE,   // Fixed pairing code via PAKE
};

/// @brief Protocol wire string for a pairing method (e.g. "pairing_psk")
inline const char* to_cstr(SendspinPairMethod method) {
    switch (method) {
        case SendspinPairMethod::PAIRING_PSK:
            return "pairing_psk";
        case SendspinPairMethod::DYNAMIC_PAIRING_CODE:
            return "dynamic_pairing_code";
        case SendspinPairMethod::STATIC_PAIRING_CODE:
            return "static_pairing_code";
        default:
            return "unknown";
    }
}

/// @brief Parses a wire string into a SendspinPairMethod; nullopt if unrecognized
inline std::optional<SendspinPairMethod> pair_method_from_string(const std::string& str) {
    if (str == "pairing_psk") {
        return SendspinPairMethod::PAIRING_PSK;
    }
    if (str == "dynamic_pairing_code") {
        return SendspinPairMethod::DYNAMIC_PAIRING_CODE;
    }
    if (str == "static_pairing_code") {
        return SendspinPairMethod::STATIC_PAIRING_CODE;
    }
    return std::nullopt;
}

/// @brief Protocol wire string for an emission format ("digits" or "qr_code")
inline const char* to_cstr(SendspinPairingCodeFormat format) {
    switch (format) {
        case SendspinPairingCodeFormat::DIGITS:
            return "digits";
        case SendspinPairingCodeFormat::QR_CODE:
            return "qr_code";
        default:
            return "unknown";
    }
}

/// @brief Parses a wire string into a pairing-code emission format; nullopt if unrecognized
inline std::optional<SendspinPairingCodeFormat> pairing_code_format_from_string(
    const std::string& str) {
    if (str == "digits") {
        return SendspinPairingCodeFormat::DIGITS;
    }
    if (str == "qr_code") {
        return SendspinPairingCodeFormat::QR_CODE;
    }
    return std::nullopt;
}

/// @brief Protocol wire string for an out-channel ("display" or "speaker")
inline const char* to_cstr(SendspinPairingCodeChannel channel) {
    switch (channel) {
        case SendspinPairingCodeChannel::DISPLAY:
            return "display";
        case SendspinPairingCodeChannel::SPEAKER:
            return "speaker";
        default:
            return "unknown";
    }
}

/// @brief Reason a pairing attempt was aborted (pairing.md "Client <-> Server: pair/abort")
/// The client emits every reason; the full set is parsed so an unexpected one from a server is
/// still named.
enum class PairAbortReason : uint8_t {
    ATTEMPT_TIMEOUT,        // attempt_timeout
    CONCURRENT_ATTEMPT,     // concurrent_attempt
    METHOD_NOT_SUPPORTED,   // method_not_supported
    PAIRING_CODE_MISMATCH,  // pairing_code_mismatch
    USER_CANCELLED,         // user_cancelled
};

/// @brief Wire string for an abort reason (e.g. "method_not_supported")
inline const char* to_cstr(PairAbortReason reason) {
    switch (reason) {
        case PairAbortReason::ATTEMPT_TIMEOUT:
            return "attempt_timeout";
        case PairAbortReason::CONCURRENT_ATTEMPT:
            return "concurrent_attempt";
        case PairAbortReason::METHOD_NOT_SUPPORTED:
            return "method_not_supported";
        case PairAbortReason::PAIRING_CODE_MISMATCH:
            return "pairing_code_mismatch";
        case PairAbortReason::USER_CANCELLED:
            return "user_cancelled";
        default:
            return "unknown";
    }
}

/// @brief Maps the internal PairAbortReason to the public one; UNKNOWN if unrecognized
inline SendspinPairAbortReason to_public_abort_reason(PairAbortReason reason) {
    switch (reason) {
        case PairAbortReason::ATTEMPT_TIMEOUT:
            return SendspinPairAbortReason::ATTEMPT_TIMEOUT;
        case PairAbortReason::CONCURRENT_ATTEMPT:
            return SendspinPairAbortReason::CONCURRENT_ATTEMPT;
        case PairAbortReason::METHOD_NOT_SUPPORTED:
            return SendspinPairAbortReason::METHOD_NOT_SUPPORTED;
        case PairAbortReason::PAIRING_CODE_MISMATCH:
            return SendspinPairAbortReason::PAIRING_CODE_MISMATCH;
        case PairAbortReason::USER_CANCELLED:
            return SendspinPairAbortReason::USER_CANCELLED;
        default:
            return SendspinPairAbortReason::UNKNOWN;
    }
}

/// @brief Parses a wire string into a PairAbortReason; nullopt if unrecognized
inline std::optional<PairAbortReason> pair_abort_reason_from_string(const std::string& str) {
    if (str == "attempt_timeout") {
        return PairAbortReason::ATTEMPT_TIMEOUT;
    }
    if (str == "concurrent_attempt") {
        return PairAbortReason::CONCURRENT_ATTEMPT;
    }
    if (str == "method_not_supported") {
        return PairAbortReason::METHOD_NOT_SUPPORTED;
    }
    if (str == "pairing_code_mismatch") {
        return PairAbortReason::PAIRING_CODE_MISMATCH;
    }
    if (str == "user_cancelled") {
        return PairAbortReason::USER_CANCELLED;
    }
    return std::nullopt;
}

// ============================================================================
// Conversion helpers (internal use only)
// ============================================================================

// --- types.h ---

inline const char* to_cstr(SendspinGoodbyeReason reason) {
    switch (reason) {
        case SendspinGoodbyeReason::ANOTHER_SERVER:
            return "another_server";
        case SendspinGoodbyeReason::SHUTDOWN:
            return "shutdown";
        case SendspinGoodbyeReason::RESTART:
            return "restart";
        case SendspinGoodbyeReason::USER_REQUEST:
            return "user_request";
        case SendspinGoodbyeReason::UNAUTHORIZED:
            return "unauthorized";
        case SendspinGoodbyeReason::PAIRING_REQUIRED:
            return "pairing_required";
        case SendspinGoodbyeReason::CONCURRENT_ATTEMPT:
            return "concurrent_attempt";
        case SendspinGoodbyeReason::UNPAIRED:
            return "unpaired";
        default:
            return "shutdown";
    }
}

inline const char* to_cstr(SendspinPlaybackState state) {
    switch (state) {
        case SendspinPlaybackState::PLAYING:
            return "playing";
        case SendspinPlaybackState::STOPPED:
        default:
            return "stopped";
    }
}

inline std::optional<SendspinPlaybackState> playback_state_from_string(const std::string& str) {
    if (str == "playing") {
        return SendspinPlaybackState::PLAYING;
    }
    if (str == "stopped") {
        return SendspinPlaybackState::STOPPED;
    }
    return std::nullopt;
}

/// @brief Optional hardware and software identity fields sent in client/hello messages
struct DeviceInfoObject {
    std::optional<std::string> product_name{};
    std::optional<std::string> manufacturer{};
    std::optional<std::string> software_version{};
    std::optional<std::string> mac_address{};
};

// --- player_role.h ---

inline const char* to_cstr(SendspinCodecFormat format) {
    switch (format) {
        case SendspinCodecFormat::FLAC:
            return "flac";
        case SendspinCodecFormat::OPUS:
            return "opus";
        case SendspinCodecFormat::PCM:
            return "pcm";
        default:
            return "unsupported";
    }
}

inline std::optional<SendspinCodecFormat> codec_format_from_string(const std::string& str) {
    if (str == "flac") {
        return SendspinCodecFormat::FLAC;
    }
    if (str == "opus") {
        return SendspinCodecFormat::OPUS;
    }
    if (str == "pcm") {
        return SendspinCodecFormat::PCM;
    }
    return std::nullopt;
}

inline const char* to_cstr(SendspinPlayerCommand cmd) {
    switch (cmd) {
        case SendspinPlayerCommand::VOLUME:
            return "volume";
        case SendspinPlayerCommand::MUTE:
            return "mute";
        case SendspinPlayerCommand::SET_OUTPUT_DELAY:
            return "set_output_delay";
        default:
            return "unknown";
    }
}

inline std::optional<SendspinPlayerCommand> player_command_from_string(const std::string& str) {
    if (str == "volume") {
        return SendspinPlayerCommand::VOLUME;
    }
    if (str == "mute") {
        return SendspinPlayerCommand::MUTE;
    }
    if (str == "set_output_delay") {
        return SendspinPlayerCommand::SET_OUTPUT_DELAY;
    }
    return std::nullopt;
}

/// @brief Player capabilities advertised to the server during the hello handshake
struct PlayerSupportObject {
    std::vector<AudioSupportedFormatObject> supported_formats{};
    size_t buffer_capacity{};
};

/// @brief Protocol maximum for every volume field: volume is 0-100 on the wire.
static constexpr uint8_t VOLUME_MAX = 100;

/// @brief Player state reported by the client to the server in client/state messages
struct ClientPlayerStateObject {
    uint8_t volume{};
    bool muted{};
    uint16_t output_delay_ms{};
    uint16_t required_lead_time_ms{};
    uint16_t min_buffer_ms{};
    std::vector<SendspinPlayerCommand> supported_commands{};
};

// --- controller_role.h ---

inline const char* to_cstr(SendspinControllerCommand cmd) {
    switch (cmd) {
        case SendspinControllerCommand::PLAY:
            return "play";
        case SendspinControllerCommand::PAUSE:
            return "pause";
        case SendspinControllerCommand::STOP:
            return "stop";
        case SendspinControllerCommand::NEXT:
            return "next";
        case SendspinControllerCommand::PREVIOUS:
            return "previous";
        case SendspinControllerCommand::VOLUME:
            return "volume";
        case SendspinControllerCommand::MUTE:
            return "mute";
        case SendspinControllerCommand::REPEAT_OFF:
            return "repeat_off";
        case SendspinControllerCommand::REPEAT_ONE:
            return "repeat_one";
        case SendspinControllerCommand::REPEAT_ALL:
            return "repeat_all";
        case SendspinControllerCommand::SHUFFLE:
            return "shuffle";
        case SendspinControllerCommand::UNSHUFFLE:
            return "unshuffle";
        case SendspinControllerCommand::SWITCH:
            return "switch";
        case SendspinControllerCommand::SEEK:
            return "seek";
        case SendspinControllerCommand::SEEK_RELATIVE:
            return "seek_relative";
        default:
            return "unknown";
    }
}

inline std::optional<SendspinControllerCommand> controller_command_from_string(
    const std::string& str) {
    if (str == "play") {
        return SendspinControllerCommand::PLAY;
    }
    if (str == "pause") {
        return SendspinControllerCommand::PAUSE;
    }
    if (str == "stop") {
        return SendspinControllerCommand::STOP;
    }
    if (str == "next") {
        return SendspinControllerCommand::NEXT;
    }
    if (str == "previous") {
        return SendspinControllerCommand::PREVIOUS;
    }
    if (str == "volume") {
        return SendspinControllerCommand::VOLUME;
    }
    if (str == "mute") {
        return SendspinControllerCommand::MUTE;
    }
    if (str == "repeat_off") {
        return SendspinControllerCommand::REPEAT_OFF;
    }
    if (str == "repeat_one") {
        return SendspinControllerCommand::REPEAT_ONE;
    }
    if (str == "repeat_all") {
        return SendspinControllerCommand::REPEAT_ALL;
    }
    if (str == "shuffle") {
        return SendspinControllerCommand::SHUFFLE;
    }
    if (str == "unshuffle") {
        return SendspinControllerCommand::UNSHUFFLE;
    }
    if (str == "switch") {
        return SendspinControllerCommand::SWITCH;
    }
    if (str == "seek") {
        return SendspinControllerCommand::SEEK;
    }
    if (str == "seek_relative") {
        return SendspinControllerCommand::SEEK_RELATIVE;
    }
    return std::nullopt;
}

inline const char* to_cstr(SendspinRepeatMode mode) {
    switch (mode) {
        case SendspinRepeatMode::OFF:
            return "off";
        case SendspinRepeatMode::ONE:
            return "one";
        case SendspinRepeatMode::ALL:
            return "all";
        default:
            return "off";
    }
}

inline std::optional<SendspinRepeatMode> repeat_mode_from_string(const std::string& str) {
    if (str == "off") {
        return SendspinRepeatMode::OFF;
    }
    if (str == "one") {
        return SendspinRepeatMode::ONE;
    }
    if (str == "all") {
        return SendspinRepeatMode::ALL;
    }
    return std::nullopt;
}

// --- artwork_role.h ---

inline const char* to_cstr(SendspinImageFormat format) {
    switch (format) {
        case SendspinImageFormat::JPEG:
            return "jpeg";
        case SendspinImageFormat::PNG:
            return "png";
        default:
            return "jpeg";
    }
}

inline std::optional<SendspinImageFormat> image_format_from_string(const std::string& str) {
    if (str == "jpeg") {
        return SendspinImageFormat::JPEG;
    }
    if (str == "png") {
        return SendspinImageFormat::PNG;
    }
    return std::nullopt;
}

inline const char* to_cstr(SendspinImageSource source) {
    switch (source) {
        case SendspinImageSource::ALBUM:
            return "album";
        case SendspinImageSource::ARTIST:
            return "artist";
        case SendspinImageSource::NONE:
        default:
            return "none";
    }
}

inline std::optional<SendspinImageSource> image_source_from_string(const std::string& str) {
    if (str == "album") {
        return SendspinImageSource::ALBUM;
    }
    if (str == "artist") {
        return SendspinImageSource::ARTIST;
    }
    if (str == "none") {
        return SendspinImageSource::NONE;
    }
    return std::nullopt;
}

/// @brief Format and resolution the client wants for a single artwork channel
struct ArtworkChannelFormatObject {
    SendspinImageSource source{};
    SendspinImageFormat format{};
    uint16_t width{};
    uint16_t height{};
};

/// @brief Server-side description of one artwork channel's format and dimensions
struct ServerArtworkChannelObject {
    std::optional<SendspinImageSource> source;
    std::optional<SendspinImageFormat> format;
    std::optional<uint16_t> width;
    std::optional<uint16_t> height;

    /// @brief Returns true if all fields in this channel have received data
    bool is_complete() const {
        return source.has_value() && format.has_value() && width.has_value() && height.has_value();
    }
};

/// @brief Artwork stream parameters sent by the server in stream/start messages
struct ServerArtworkStreamObject {
    std::optional<std::vector<ServerArtworkChannelObject>> channels;
};

/// @brief Visualizer stream configuration reported by the client in client/state messages
struct ClientVisualizerStateObject {
    std::vector<VisualizerDataType> types{};
    uint16_t rate_max{};
    std::optional<VisualizerSpectrumConfig> spectrum;
};

/// @brief Artwork channel configuration reported by the client in client/state messages
struct ClientArtworkStateObject {
    std::vector<ArtworkChannelFormatObject> channels;
};

// --- visualizer_role.h ---

inline const char* to_cstr(VisualizerDataType type) {
    switch (type) {
        case VisualizerDataType::BEAT:
            return "beat";
        case VisualizerDataType::LOUDNESS:
            return "loudness";
        case VisualizerDataType::F_PEAK:
            return "f_peak";
        case VisualizerDataType::SPECTRUM:
            return "spectrum";
        case VisualizerDataType::PEAK:
            return "peak";
        default:
            return "unknown";
    }
}

inline std::optional<VisualizerDataType> visualizer_data_type_from_string(const std::string& str) {
    if (str == "beat") {
        return VisualizerDataType::BEAT;
    }
    if (str == "loudness") {
        return VisualizerDataType::LOUDNESS;
    }
    if (str == "f_peak") {
        return VisualizerDataType::F_PEAK;
    }
    if (str == "spectrum") {
        return VisualizerDataType::SPECTRUM;
    }
    if (str == "peak") {
        return VisualizerDataType::PEAK;
    }
    return std::nullopt;
}

inline const char* to_cstr(VisualizerSpectrumScale scale) {
    switch (scale) {
        case VisualizerSpectrumScale::MEL:
            return "mel";
        case VisualizerSpectrumScale::LOG:
            return "log";
        case VisualizerSpectrumScale::LIN:
            return "lin";
        default:
            return "mel";
    }
}

inline std::optional<VisualizerSpectrumScale> visualizer_spectrum_scale_from_string(
    const std::string& str) {
    if (str == "mel") {
        return VisualizerSpectrumScale::MEL;
    }
    if (str == "log") {
        return VisualizerSpectrumScale::LOG;
    }
    if (str == "lin") {
        return VisualizerSpectrumScale::LIN;
    }
    return std::nullopt;
}

// --- source_role.h ---

inline const char* to_cstr(SourceSignal signal) {
    switch (signal) {
        case SourceSignal::PRESENT:
            return "present";
        case SourceSignal::ABSENT:
        default:
            return "absent";
    }
}

/// @brief Commands addressed to the source role in server/command messages
enum class SourceCommand : uint8_t {
    START,  // Authorize one opening of the input stream
    STOP,   // Clear any pending authorization and end the input stream
};

inline std::optional<SourceCommand> source_command_from_string(const std::string& str) {
    if (str == "start") {
        return SourceCommand::START;
    }
    if (str == "stop") {
        return SourceCommand::STOP;
    }
    return std::nullopt;
}

/// @brief Source capabilities advertised in client/hello (roles/source/v1.md "client/hello
/// source@v1 support object")
struct SourceSupportObject {
    bool line_sense{false};
};

/// @brief Source state reported in client/state (roles/source/v1.md "client/state source
/// object"). The object may be present and empty; signal only when line_sense was advertised.
struct ClientSourceStateObject {
    std::optional<SourceSignal> signal{};
};

// ============================================================================
// Message envelope structs
// ============================================================================

/// @brief A pairing method descriptor for client/hello supported_pair_methods.
/// Optional fields are omitted from the wire when not set (omit_none semantics). The methods are
/// held as a list rather than a map because the wire object has at most two keys and the
/// descriptor names its own: `method` is serialized as the key this descriptor sits under, not as
/// a field of it (pairing.md "client/hello pair-method descriptor").
struct PairMethodDescriptor {
    SendspinPairMethod method{SendspinPairMethod::PAIRING_PSK};
    /// @brief Channels the dynamic pairing code is conveyed through. Required and non-empty on
    /// the dynamic_pairing_code descriptor, absent on the others.
    std::optional<std::vector<SendspinPairingCodeChannel>> out_channels;
    /// @brief Emission formats the client offers. Required and non-empty on the
    /// dynamic_pairing_code descriptor, absent on the others.
    std::optional<std::vector<SendspinPairingCodeFormat>> formats;
    /// @brief Where the operator can find the method's configured secret:
    /// 'device' | 'leaflet' | 'operator'. Informational hint for static_pairing_code and
    /// pairing_psk only; absent for dynamic_pairing_code, whose code has no resting place.
    std::optional<std::vector<std::string>> locations;
};

/// @brief Outgoing client/hello handshake message sent at connection startup.
/// Under encryption, client_id and version are carried in client/init (the cleartext Noise
/// handshake frame), not repeated here.
struct ClientHelloMessage {
    std::string name{};
    std::optional<DeviceInfoObject> device_info{};
    std::vector<SendspinRole> supported_roles{};
    std::optional<PlayerSupportObject> player_v1_support{};
    std::optional<VisualizerSupportObject> visualizer_support{};
    std::optional<SourceSupportObject> source_v1_support{};
    bool unpaired_access_enabled{false};
    std::vector<PairMethodDescriptor> supported_pair_methods{};
};

/// @brief Outgoing client/state message reporting client playback state to the server
struct ClientStateMessage {
    bool available{true};
    std::optional<ClientPlayerStateObject> player{};
    std::optional<ClientArtworkStateObject> artwork{};
    std::optional<ClientVisualizerStateObject> visualizer{};
    std::optional<ClientSourceStateObject> source{};
};

/// @brief The client/state one connection receives: the snapshot's availability and the role
/// objects of the roles in `roles` only
///
/// messaging.md "client/state" includes a role object only while that role is active, and a
/// role this client drives has one owner among the admitted connections, so each connection is
/// sent the objects of the roles it owns and has active, and no other.
/// @param snapshot Every role's state object, as the main loop built it.
/// @param roles The receiving connection's owned, active roles, as role_mask_bit() bits.
inline ClientStateMessage client_state_for_roles(const ClientStateMessage& snapshot,
                                                 uint16_t roles) {
    ClientStateMessage msg;
    msg.available = snapshot.available;
    if ((roles & role_mask_bit(SendspinRole::PLAYER)) != 0) {
        msg.player = snapshot.player;
    }
    if ((roles & role_mask_bit(SendspinRole::ARTWORK)) != 0) {
        msg.artwork = snapshot.artwork;
    }
    if ((roles & role_mask_bit(SendspinRole::VISUALIZER)) != 0) {
        msg.visualizer = snapshot.visualizer;
    }
    if ((roles & role_mask_bit(SendspinRole::SOURCE)) != 0) {
        msg.source = snapshot.source;
    }
    return msg;
}

/// @brief Outgoing client-stream/start message announcing the source's input stream format
/// (roles/source/v1.md "client-stream/start"). codec_header is required for flac and absent for
/// pcm and opus; that is the producer's contract, not checked here.
struct ClientStreamStartMessage {
    SendspinCodecFormat codec{};
    uint8_t channels{};
    uint32_t sample_rate{};
    uint8_t bit_depth{};
    std::optional<std::string> codec_header{};
};

/// @brief Bit that represents `codec` in a codec mask
constexpr uint8_t source_codec_bit(SendspinCodecFormat codec) {
    return static_cast<uint8_t>(1U << static_cast<uint8_t>(codec));
}

// Every codec, UNSUPPORTED included, has a bit in a uint8_t mask
static_assert(static_cast<uint8_t>(SendspinCodecFormat::UNSUPPORTED) < 8,
              "codec mask is a uint8_t");

/// @brief Parsed server/hello handshake message received at connection startup.
/// server_id comes from the Noise handshake result (set on the connection at COMPLETE), and
/// activities/active_roles come from the server/activate message that follows.
struct ServerHelloMessage {
    std::string name{};
    /// Codecs the server accepts in client-stream/start, as source_codec_bit() bits; a set value
    /// always carries the PCM and FLAC bits, nullopt when server/hello carried no valid
    /// source@v1_support object (roles/source/v1.md "server/hello source@v1 support object").
    std::optional<uint8_t> source_codecs{};
};

/// @brief Parsed server/activate message that follows server/hello.
/// Declares the server's current activity set and active roles for this connection
/// (messaging.md "server/activate").
struct ServerActivateMessage {
    std::vector<SendspinActivity> activities{};
    std::optional<std::vector<std::string>> active_roles;  // sticky: nullopt = keep prior set
    /// From payload.pairing.method: the pairing method the server picked. nullopt when the
    /// message carries no pairing object or names an unrecognized method string.
    std::optional<SendspinPairMethod> pairing_method;
    /// From payload.pairing.format: the emission format the server picked for a dynamic pairing
    /// code. nullopt when the message carries no format or names an unrecognized one; required
    /// on the wire when pairing_method is dynamic_pairing_code (messaging.md "server/activate").
    std::optional<SendspinPairingCodeFormat> pairing_format;
};

/// @brief Parsed group/update message containing the group state delta
struct GroupUpdateMessage {
    GroupUpdateObject group;
};

/// @brief Parsed stream/start message with per-role stream parameters
struct StreamStartMessage {
    std::optional<ServerPlayerStreamObject> player;
    std::optional<ServerArtworkStreamObject> artwork;
    std::optional<ServerVisualizerStreamObject> visualizer;
};

/// @brief Parsed stream/end message listing which roles the stream end applies to
struct StreamEndMessage {
    std::optional<std::vector<std::string>> roles{};
};

/// @brief Parsed stream/clear message listing which roles the buffer flush applies to
struct StreamClearMessage {
    std::optional<std::vector<std::string>> roles{};
};

/// @brief Parsed pair/abort message (received or sent during a pairing exchange)
struct PairAbortMessage {
    PairAbortReason reason{PairAbortReason::METHOD_NOT_SUPPORTED};
};

// ============================================================================
// Pairing-code message structs (server -> client)
// ============================================================================

/// @brief Parsed server/pair-init payload.
/// Begins a round of the Dynamic Pairing Code Flow. Carries nonce_A (32 raw bytes,
/// base64url-encoded on the wire, 43 chars) in the attempt's first round only; a round opened by
/// client/pair-retry reuses the binding values already in hand and carries none
/// (pairing.md "Server -> Client: server/pair-init").
struct ServerPairInitPayload {
    std::optional<std::array<uint8_t, 32>> nonce_a;
};

/// @brief Parsed server/pair-auth payload.
/// Carries pake_msg_1 (32 raw bytes, base64url-encoded, 43 chars): the server's CPace share.
struct ServerPairAuthPayload {
    std::array<uint8_t, 32> pake_msg_1{};  ///< Server CPace public share.
};

/// @brief Parsed server/pair-confirm payload.
/// Carries server_kc (64 raw bytes, base64url-encoded, 86 chars): the server confirmation tag.
struct ServerPairConfirmPayload {
    std::array<uint8_t, 64> server_kc{};  ///< Server CPace confirmation tag (HMAC-SHA-512).
};

// ============================================================================
// Protocol functions
// ============================================================================

// Every process_*() below takes the parsed JSON object and fills the caller-owned output it is
// handed, returning false on a missing or malformed required field; the output holds copies, never
// a view into the document, so the caller can release the document before acting on it
// (ParsedJsonMessage::extract()). Every format_*() that returns a std::string builds its document
// in the `arena` it is handed (the client's, on the protocol task) and destroys it before it
// returns (a document that fits one variant pool frees the arena back down to where it found it;
// see SendspinArenaAllocator). It never resets the arena.

/// @brief Determines the message type of an incoming server-to-client JSON message; UNKNOWN if
/// not recognized
SendspinServerToClientMessageType determine_message_type(JsonObject root);

/// @brief Parses a server/hello JSON message into the provided struct.
/// server_id comes from the Noise handshake result, not from server/hello.
bool process_server_hello_message(JsonObject root, ServerHelloMessage* hello_msg);

/// @brief Parses a server/activate JSON message into the provided struct.
bool process_server_activate_message(JsonObject root, ServerActivateMessage* activate_msg);

/// @brief A server/time reply (messaging.md "server/time"), every field in microseconds
struct ServerTimeMessage {
    int64_t client_transmitted{0};  ///< Echo of the client/time it answers (client clock)
    int64_t server_received{0};     ///< When the server received that client/time (server clock)
    int64_t server_transmitted{0};  ///< When the server sent this reply (server clock)
};

/// @brief Parses a server/time JSON message into the provided struct
bool process_server_time_message(JsonObject root, ServerTimeMessage* time_msg);

/// @brief Computes one time exchange's server-to-client clock offset and round-trip error bound,
/// both in microseconds
/// @param client_sent When the client/time was handed to the socket, on the client clock (see
///                    SendspinConnection::claim_time_frame()); time_msg.client_transmitted is
///                    not used.
/// @param client_received When the reply arrived, on the client clock.
void compute_time_exchange(const ServerTimeMessage& time_msg, int64_t client_sent,
                           int64_t client_received, int64_t* offset, int64_t* max_error);

/// @brief Parses a group/update JSON message into the provided struct
bool process_group_update_message(JsonObject root, GroupUpdateMessage* group_msg);

/// @brief Merges a GroupUpdateObject delta, which carries only the changed fields, into the
/// current group state
void apply_group_update_deltas(GroupUpdateObject* current, const GroupUpdateObject& updates);

/// @brief Parses a server/command JSON message into the provided struct
bool process_server_command_message(JsonObject root, ServerCommandMessage* cmd_msg);

/// @brief Parses the source section of a server/command JSON message; false when the section
/// is absent or invalid
bool process_server_command_source(JsonObject root, SourceCommand* source_cmd);

/// @brief Parses the metadata section of a server/state JSON message; true if the section was
/// present and parsed
///
/// The server/state sections are parsed per section, each into a caller-owned struct, not into
/// an aggregate a parser frame would copy out of. The caller holds every section a role takes
/// live together for one parse, a combined frame on the protocol task, whose stack is bounded on
/// ESP-IDF (at least SendspinClientConfig::DEFAULT_PROTOCOL_TASK_STACK_SIZE).
bool process_server_state_metadata(JsonObject root, ServerMetadataStateObject* metadata);

/// @brief Parses the color section of a server/state JSON message; true if the section was
/// present and parsed
bool process_server_state_color(JsonObject root, ServerColorStateObject* color);

/// @brief Parses the controller section of a server/state JSON message; true if the section was
/// present and parsed
bool process_server_state_controller(JsonObject root,
                                     ServerStateControllerObject* controller_state);

/// @brief Parses a stream/start JSON message into the provided struct
bool process_stream_start_message(JsonObject root, StreamStartMessage* stream_msg);

/// @brief Parses a stream/end JSON message into the provided struct
bool process_stream_end_message(JsonObject root, StreamEndMessage* end_msg);

/// @brief Parses a stream/clear JSON message into the provided struct
bool process_stream_clear_message(JsonObject root, StreamClearMessage* clear_msg);

/// @brief Formats a client/hello message as a JSON string
std::string format_client_hello_message(const ClientHelloMessage* msg,
                                        SendspinArenaAllocator& arena);

/// @brief Formats a client/state message as a JSON string
std::string format_client_state_message(const ClientStateMessage* msg,
                                        SendspinArenaAllocator& arena);

/// @brief Formats a client-stream/start message as a JSON string
std::string format_client_stream_start_message(const ClientStreamStartMessage* msg,
                                               SendspinArenaAllocator& arena);

/// @brief Formats a client-stream/end message as a JSON string
/// roles/source/v1.md "client-stream/end": ends the input stream; no payload fields.
std::string format_client_stream_end_message(SendspinArenaAllocator& arena);

/// @brief Formats a client/leave message as a JSON string
/// messaging.md "client/leave": leaves the client's current group; no payload fields.
std::string format_client_leave_message(SendspinArenaAllocator& arena);

/// @brief Formats a client/goodbye message as a JSON string
std::string format_client_goodbye_message(SendspinGoodbyeReason reason,
                                          SendspinArenaAllocator& arena);

/// Buffer size for format_client_time_message(). Fits the longest possible message:
/// prefix (54) + '-' (1) + 19 digits + suffix (2) = 76 bytes, rounded up.
static constexpr size_t TIME_MESSAGE_BUF_SIZE = 96;

/// @brief Formats a client/time JSON message into a caller-supplied buffer
///
/// Hot path on the time-sync send side: avoids any heap allocation by writing the fixed-shape
/// message directly into the caller's stack buffer. A 96-byte buffer is always large enough.
/// @param client_transmitted The client timestamp to embed (microseconds); the server/time reply
///                           echoes it (see SendspinConnection::claim_time_frame()).
/// @return Number of bytes written (excluding any null terminator), or 0 on error.
size_t format_client_time_message(char* buf, size_t cap, int64_t client_transmitted);

/// @brief Formats a client/command message as a JSON string
/// @param cmd The playback command plus any command-specific parameters. Only the parameter
/// relevant to the command is serialized (e.g. position_ms for SEEK); others are ignored.
std::string format_client_command_message(const ClientCommandControllerObject& cmd,
                                          SendspinArenaAllocator& arena);

/// @brief Formats a client/pair-finalize message carrying long_term_psk directly (Pairing PSK
/// flow only). The PSK is 32 raw bytes, base64url-encoded (no padding, 43 chars).
std::string format_client_pair_finalize_message(const std::array<uint8_t, 32>& psk,
                                                SendspinArenaAllocator& arena);

/// @brief Formats a client/pair-finalize message carrying wrapped_psk (pairing-code flows only;
/// see pairing.md "Wrapping"). wrapped_psk is 48 raw bytes, base64url-encoded (no padding, 64
/// chars).
std::string format_client_pair_finalize_wrapped_message(const std::array<uint8_t, 48>& wrapped_psk,
                                                        SendspinArenaAllocator& arena);

/// @brief Formats a pair/abort message as a JSON string.
/// Sent by the client when it cannot proceed with the selected pairing method.
std::string format_pair_abort_message(PairAbortReason reason, SendspinArenaAllocator& arena);

/// @brief Parses a pair/abort JSON message into the provided struct; false on a missing or
/// unrecognized reason
bool process_pair_abort_message(JsonObject root, PairAbortMessage* abort_msg);

// ============================================================================
// Pairing-code protocol functions
// ============================================================================

/// @brief Parses a server/pair-init JSON message into the provided struct.
/// Validates base64url encoding and decoded length of nonce_A (must be 32 bytes).
/// @param payload [out] Struct to populate; nullptr for validation-only.
bool process_server_pair_init_message(JsonObject root, ServerPairInitPayload* payload);

/// @brief Parses a server/pair-auth JSON message into the provided struct.
/// Validates base64url encoding and decoded length of pake_msg_1 (must be 32 bytes).
/// @param payload [out] Struct to populate; nullptr for validation-only.
bool process_server_pair_auth_message(JsonObject root, ServerPairAuthPayload* payload);

/// @brief Parses a server/pair-confirm JSON message into the provided struct.
/// Validates base64url encoding and decoded length of server_kc (must be 64 bytes).
/// @param payload [out] Struct to populate; nullptr for validation-only.
bool process_server_pair_confirm_message(JsonObject root, ServerPairConfirmPayload* payload);

/// @brief Formats a client/pair-pending message as a JSON string.
/// Sent immediately on receiving a pairing server/activate whose attempt is gesture-gated while
/// no pairing window is open; client/pair-init follows once a window opens. Does not start the
/// attempt or its timeout.
/// @param pairing_index Count of pairing server/activate messages received since the last Noise
///                      handshake (see SendspinConnection::get_pairing_index()).
std::string format_client_pair_pending_message(uint32_t pairing_index,
                                               SendspinArenaAllocator& arena);

/// @brief Formats a client/pair-init message as a JSON string.
/// Starts the dynamic-pairing-code attempt; carries commit_B = SHA-256(PAIRING_COMMIT_LABEL ||
/// nonce_B) and the required pairing_index counter (pairing.md "Pairing index").
/// @param commit_b 32-byte commit_B value to embed (base64url-encoded on the wire).
std::string format_client_pair_init_message(const std::array<uint8_t, 32>& commit_b,
                                            uint32_t pairing_index, SendspinArenaAllocator& arena);

/// @brief Formats a client/pair-init message with only pairing_index.
/// The form used by every flow that carries no commit_B: the static pairing code, sent after the
/// operator confirms the pairing-window gesture and before starting CPace RESPONDER, and Pairing
/// PSK, sent immediately before client/pair-finalize (pairing.md "Pairing PSK Flow").
std::string format_client_pair_init_message(uint32_t pairing_index, SendspinArenaAllocator& arena);

/// @brief Formats a client/pair-retry message as a JSON string.
/// Sent in place of client/pair-confirm when server_kc fails to verify and the client admits
/// another round (pairing.md "Client -> Server: client/pair-retry"). The payload is empty: the
/// attempt, its pairing code and its running attempt timeout all carry over.
std::string format_client_pair_retry_message(SendspinArenaAllocator& arena);

/// @brief Formats a client/pair-auth message as a JSON string.
/// Sent in response to server/pair-auth; carries the client's CPace public share.
/// @param pake_msg_2 32-byte client CPace public share (base64url-encoded on the wire).
std::string format_client_pair_auth_message(const std::array<uint8_t, 32>& pake_msg_2,
                                            SendspinArenaAllocator& arena);

/// @brief Formats a client/pair-confirm message as a JSON string.
/// Sent in response to server/pair-confirm in the Dynamic Pairing Code Flow; carries client_kc
/// and the sealed opening of the commitment sent earlier as commit_B.
/// @param client_kc       64-byte client CPace confirmation tag (base64url-encoded on the wire).
/// @param wrapped_nonce_b 48-byte wrapping of nonce_B (base64url-encoded on the wire, 64 chars);
///                        see pairing.md "Wrapping".
std::string format_client_pair_confirm_message(
    const std::array<uint8_t, 64>& client_kc,
    const std::array<uint8_t, WRAPPED_VALUE_SIZE>& wrapped_nonce_b, SendspinArenaAllocator& arena);

/// @brief Formats a client/pair-confirm message with no commitment opening (the Static Pairing
/// Code Flow, which sends no commit_B and so has nothing to open).
/// @param client_kc 64-byte client CPace confirmation tag (base64url-encoded on the wire).
std::string format_client_pair_confirm_message(const std::array<uint8_t, 64>& client_kc,
                                               SendspinArenaAllocator& arena);

}  // namespace sendspin
