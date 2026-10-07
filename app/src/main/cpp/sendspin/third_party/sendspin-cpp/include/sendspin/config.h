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

/// @file config.h
/// @brief Configuration structs for the Sendspin client and roles

#pragma once

#include "sendspin/types.h"

#include <array>
#include <cstddef>
#include <cstdint>
#include <limits>
#include <optional>
#include <string>
#include <vector>

namespace sendspin {

namespace detail {

/// @brief Overwrite a 32-byte PSK with zeroes through a volatile pointer, so the write survives
/// dead-store elimination on a buffer about to go out of scope.
///
/// Duplicates `secure_zero()` in the private `platform/secure_zero.h`: config.h is public and must
/// not include a `src/`-private header. Keep the two in sync.
inline void secure_zero_psk(std::array<uint8_t, 32>& psk) {
    volatile uint8_t* vp = psk.data();
    for (size_t i = 0; i < psk.size(); ++i) {
        vp[i] = 0;
    }
}

}  // namespace detail

// ============================================================================
// Persistence types (used by SendspinPersistenceProvider)
// ============================================================================

/// @brief A long-term pairing record stored on behalf of the client.
/// Every long-term PSK is persisted alongside the `server_id` of the server it was minted for,
/// and a handshake that matches the record must come from that server (connection.md "Pre-Shared
/// Key").
struct SendspinPairingRecord {
    std::string psk_id;
    std::array<uint8_t, 32> psk{};
    std::string server_id;

    SendspinPairingRecord() = default;
    SendspinPairingRecord(const SendspinPairingRecord&) = default;
    SendspinPairingRecord(SendspinPairingRecord&&) = default;
    SendspinPairingRecord& operator=(const SendspinPairingRecord&) = default;
    SendspinPairingRecord& operator=(SendspinPairingRecord&&) = default;

    /// @brief Wipes `psk` on destruction: defense in depth against a stale copy (a rollback
    /// snapshot, a superseded element, a temporary) outliving its use. It does not protect the
    /// live record, whose PSK is plaintext in `RecordStore::records_` and in the persisted blob
    /// for as long as the record is paired, by design. The copy/move members are defaulted
    /// explicitly so records keep moving (not copying) through vector/optional.
    ~SendspinPairingRecord() {
        detail::secure_zero_psk(this->psk);
    }
};

/// @brief An accepted Pairing PSK the client stores for admitting a server.
/// See pairing.md "Pairing PSK Flow".
struct SendspinPairingPsk {
    std::string psk_id;
    std::array<uint8_t, 32> psk{};

    SendspinPairingPsk() = default;
    SendspinPairingPsk(const SendspinPairingPsk&) = default;
    SendspinPairingPsk(SendspinPairingPsk&&) = default;
    SendspinPairingPsk& operator=(const SendspinPairingPsk&) = default;
    SendspinPairingPsk& operator=(SendspinPairingPsk&&) = default;

    /// @brief Wipes `psk` on destruction; see SendspinPairingRecord for the rationale.
    ~SendspinPairingPsk() {
        detail::secure_zero_psk(this->psk);
    }
};

// ============================================================================
// Client config
// ============================================================================

/// @brief A 32-byte pre-shared key supplied through configuration.
/// Copies like the array it holds; every copy wipes its bytes on destruction, the same
/// discipline as SendspinPairingPsk.
struct SendspinPsk {
    std::array<uint8_t, 32> bytes{};

    SendspinPsk() = default;
    explicit SendspinPsk(const std::array<uint8_t, 32>& key_bytes) : bytes(key_bytes) {}
    SendspinPsk(const SendspinPsk&) = default;
    SendspinPsk(SendspinPsk&&) = default;
    SendspinPsk& operator=(const SendspinPsk&) = default;
    SendspinPsk& operator=(SendspinPsk&&) = default;

    ~SendspinPsk() {
        detail::secure_zero_psk(this->bytes);
    }
};

/// @brief Configuration for a SendspinClient instance
/// Filled in by the platform (e.g., ESPHome) before calling start()
struct SendspinClientConfig {
    // client_id is derived, not configured: the library computes it from the static X25519
    // keypair (client_id = base64url(public_key)), generated on first boot and persisted via
    // SendspinPersistenceProvider. Read it back via SendspinClient::client_id() after
    // start().
    std::string name;  ///< Friendly display name

    std::optional<std::string> product_name{};  ///< Device product name (optional)
    std::optional<std::string> manufacturer{};  ///< Manufacturer name, e.g., "ESPHome" (optional)
    std::optional<std::string> software_version{};  ///< Software version string (optional)

    /// @brief MAC address of the network interface the connection is opened on.
    /// Sent in the client/hello device_info object. Must be lowercase colon-separated
    /// form (e.g., "aa:bb:cc:dd:ee:ff"). When left unset, the library auto-detects it:
    /// from the default network interface (Wi-Fi or Ethernet) on ESP-IDF, and best-effort
    /// from the active routable interface on host. Set this explicitly to override the
    /// detected value (recommended on multi-homed hosts where detection may pick the wrong
    /// interface).
    std::optional<std::string> mac_address{};

    /// @brief Channels through which this device can emit a dynamic pairing code to the operator.
    /// Advertised as `out_channels` on the dynamic_pairing_code descriptor in client/hello
    /// (pairing.md "client/hello pair-method descriptor"). Set this alongside
    /// `pairing_code_formats` when the application implements the on_display_pairing_code /
    /// on_clear_pairing_code callbacks on its SendspinClientListener. Empty leaves
    /// dynamic_pairing_code unadvertised.
    std::vector<SendspinPairingCodeChannel> pairing_code_out_channels{};

    /// @brief Emission formats this device can render a dynamic pairing code in, in the order
    /// advertised as `formats` on the dynamic_pairing_code descriptor. DIGITS suits any display
    /// or speaker; QR_CODE requires a display able to render a QR code from the pairing token the
    /// listener receives. Empty leaves dynamic_pairing_code unadvertised.
    std::vector<SendspinPairingCodeFormat> pairing_code_formats{};

    /// @brief When true, the platform implements the operator pairing-window gesture.
    /// Set this to true when the application implements on_open_pairing_window /
    /// on_close_pairing_window callbacks on its SendspinClientListener. When false,
    /// static_pairing_code is not advertised even if `static_pairing_code` is set, and a dynamic
    /// attempt held back by the round limit has no way to resume.
    bool pairing_window_supported{false};

    /// @brief The Pairing PSK the device shipped with, for an application that provisions one
    /// itself (for example from a factory partition). pairing.md "Pairing PSK Flow" requires it
    /// to be drawn from a CSPRNG per device, never shared across devices. When set, it is the
    /// Pairing PSK on every start() and is never written to the persistence provider; the library
    /// derives its psk_id. An all-zero key or the published Sentinel PSK is rejected: start()
    /// logs an error and returns false. When unset, the library loads the one stored under
    /// `persistence_keys::PAIRING_PSK`, or generates one on first boot and stores it there.
    /// SendspinClient::pairing_token() carries whichever is in use.
    std::optional<SendspinPsk> pairing_psk{};

    /// @brief The static pairing code the device shipped with: exactly 8 decimal digits, drawn
    /// from a CSPRNG per device (pairing.md "Static Pairing Code Flow"). static_pairing_code is
    /// advertised only when this is set, `pairing_window_supported` is true, and
    /// dynamic_pairing_code is not advertised (messaging.md "client/hello" permits at most one
    /// pairing-code method). A value that is not 8 decimal digits is rejected: start() logs an
    /// error and returns false.
    std::optional<std::string> static_pairing_code{};

    /// @brief Where the operator can find the Pairing PSK the device shipped with (as a pairing
    /// token): any of "device", "leaflet", "operator". Advertised as the informational
    /// `locations` hint on the pairing_psk descriptor in client/hello; empty = omit the hint.
    std::vector<std::string> pairing_psk_locations{};

    /// @brief Where the operator can find the static pairing code the device shipped with: any of
    /// "device", "leaflet", "operator". Advertised as the informational `locations` hint on the
    /// static_pairing_code descriptor in client/hello; empty = omit the hint.
    std::vector<std::string> static_pairing_code_locations{};

    /// @brief Default maximum number of long-term pairing records the store retains. Each record
    /// occupies its own persistence key of `persistence_keys::RECORD_SLOT_SIZE` bytes, so the cap
    /// sets how many keys the store may use rather than the size of any one of them (see
    /// persistence_codec.h's keyspace doc). Pairing at the cap evicts the least recently used
    /// record rather than failing; replacing a record already held for a given psk_id or
    /// server_id evicts nothing, since that never grows the store.
    static constexpr size_t DEFAULT_MAX_PAIRING_RECORDS = 12;

    /// @brief Maximum number of long-term pairing records the store will retain. The protocol
    /// requires room for at least 5, so a smaller value is raised to that floor; a value above
    /// 255 is lowered to that ceiling, so the highest slot is 254 and the record-order blob
    /// (`persistence_keys::RECORD_ORDER` in sendspin/persistence_keys.h) keeps byte value 255
    /// free as its padding.
    size_t max_pairing_records{DEFAULT_MAX_PAIRING_RECORDS};

    bool httpd_psram_stack{false};  ///< Allocate httpd task stack in PSRAM (ESP-IDF only)

    /// @brief Default FreeRTOS priority for the HTTP server task (ESP-IDF only)
    static constexpr unsigned DEFAULT_HTTPD_PRIORITY = 5U;

    unsigned httpd_priority{DEFAULT_HTTPD_PRIORITY};  ///< FreeRTOS priority for the HTTP server
                                                      ///< task (ESP-IDF only)

    /// @brief Default HTTP server task stack size in bytes (ESP-IDF only). Derived in
    /// tools/stack_usage/README.md.
    static constexpr size_t DEFAULT_HTTPD_STACK_SIZE = 4608U;

    size_t httpd_stack_size{DEFAULT_HTTPD_STACK_SIZE};  ///< HTTP server task stack size in bytes
                                                        ///< (ESP-IDF only). Values below
                                                        ///< DEFAULT_HTTPD_STACK_SIZE are clamped
                                                        ///< up to it.
    unsigned websocket_priority{5};  ///< FreeRTOS priority for the WebSocket client task
                                     ///< (ESP-IDF only)

    /// @brief Default esp_websocket_client task stack size in bytes (ESP-IDF only). Derived in
    /// tools/stack_usage/README.md.
    static constexpr size_t DEFAULT_WEBSOCKET_STACK_SIZE = 4608U;

    size_t websocket_stack_size{
        DEFAULT_WEBSOCKET_STACK_SIZE};  ///< esp_websocket_client task stack size in bytes
                                        ///< (ESP-IDF only). Values below
                                        ///< DEFAULT_WEBSOCKET_STACK_SIZE are clamped up to it.

    bool protocol_task_psram_stack{false};  ///< Allocate the protocol task stack in PSRAM
                                            ///< (ESP-IDF only)

    /// @brief Default FreeRTOS priority for the protocol task (ESP-IDF only). The same priority
    /// as the httpd task that hands it messages, so neither starves the other, and below the
    /// player's sync task (PlayerRoleConfig::DEFAULT_SYNC_TASK_PRIORITY), which must keep
    /// decoding through the burst of audio a stream start delivers.
    static constexpr unsigned DEFAULT_PROTOCOL_TASK_PRIORITY = DEFAULT_HTTPD_PRIORITY;

    unsigned protocol_task_priority{DEFAULT_PROTOCOL_TASK_PRIORITY};  ///< FreeRTOS priority for
                                                                      ///< the protocol task
                                                                      ///< (ESP-IDF only)

    /// @brief Default protocol task stack size in bytes (ESP-IDF only). Derived in
    /// tools/stack_usage/README.md.
    static constexpr size_t DEFAULT_PROTOCOL_TASK_STACK_SIZE = 7168U;

    size_t protocol_task_stack_size{
        DEFAULT_PROTOCOL_TASK_STACK_SIZE};  ///< Protocol task stack size in bytes (ESP-IDF only).
                                            ///< Values below DEFAULT_PROTOCOL_TASK_STACK_SIZE are
                                            ///< clamped up to it.

    static constexpr uint16_t DEFAULT_SERVER_PORT = 8928U;  ///< Default WebSocket server port

    uint16_t httpd_ctrl_port{0};  ///< ESP-IDF httpd control port; 0 = ESP_HTTPD_DEF_CTRL_PORT
                                  ///< + 1 (avoids conflict with web_server component)
    uint16_t server_port{DEFAULT_SERVER_PORT};  ///< WebSocket server port

    /// @brief Default maximum simultaneous inbound connections: one established connection, two
    /// unproven connections awaiting the hello handshake (the manager's nursery capacity), and
    /// one spare so a surplus peer can still be accepted long enough to receive a graceful
    /// client/goodbye. Values below this trade that goodbye for a transport-level refusal at
    /// accept (on ESP the surplus peer waits unanswered in the TCP backlog instead).
    static constexpr uint8_t DEFAULT_SERVER_MAX_CONNECTIONS = 4U;

    uint8_t server_max_connections{
        DEFAULT_SERVER_MAX_CONNECTIONS};  ///< Maximum simultaneous connections

    static constexpr int64_t DEFAULT_BURST_INTERVAL_MS = 10000;  ///< Default ms between bursts
    static constexpr int64_t DEFAULT_BURST_TIMEOUT_MS = 10000;   ///< Default burst timeout ms

    static constexpr uint8_t DEFAULT_BURST_SIZE = 8;  ///< Default messages per time sync burst

    uint8_t time_burst_size{DEFAULT_BURST_SIZE};  ///< Number of messages per time sync burst
    int64_t time_burst_interval_ms{DEFAULT_BURST_INTERVAL_MS};  ///< Milliseconds between bursts
    int64_t time_burst_response_timeout_ms{
        DEFAULT_BURST_TIMEOUT_MS};  ///< Milliseconds before a burst message times out

    static constexpr int64_t MAX_LIVENESS_TIMEOUT_MS = 30LL * 60 * 1000;  ///< Liveness timeout cap

    /// @brief Milliseconds of inbound silence before the established connection is dropped as
    /// dead. Unset derives it from the time burst settings, tolerating two consecutive unanswered
    /// time messages (60000 with the defaults); 0 disables. Set or derived, it is capped at
    /// MAX_LIVENESS_TIMEOUT_MS.
    std::optional<int64_t> liveness_timeout_ms{};

    /// @brief Memory placement for the shared inbound ring every admitted connection receives
    /// into (sized from the enabled roles' buffers: the player's audio_buffer_capacity, the
    /// visualizer's buffer_capacity, one image per artwork channel in flight, and a baseline of two
    /// of the longest messages) and for each connection's fallback buffer, which holds a
    /// pre-admission message or a message longer than the ring takes (ESP-IDF only; ignored on
    /// host).
    /// Defaults to PREFER_EXTERNAL (SPIRAM), falling back to internal RAM.
    MemoryLocation inbound_ring_location{MemoryLocation::PREFER_EXTERNAL};

    /// @brief Memory placement for the Noise transport's fragment reassembly buffer, the ~64 KB
    /// fragmentation frame buffer, and the outbound send scratch buffer (ESP-IDF only; ignored on
    /// host). The reassembly buffer grows with the largest fragmented message received (a player
    /// audio chunk, bounded by the buffer capacity the player role advertises) and retains its
    /// capacity, so PREFER_EXTERNAL keeps it out of internal RAM.
    MemoryLocation noise_buffer_location{MemoryLocation::PREFER_EXTERNAL};

    /// @brief Default arena size: one steady-state protocol message, including the FLAC
    /// stream-start header (see json_arena_size).
    static constexpr size_t DEFAULT_JSON_ARENA_SIZE = 2048;

    /// @brief Size in bytes of an internal-RAM scratch arena for the protocol's JSON documents.
    /// Every JSON document the protocol task works with, the parse of each incoming message and
    /// every message it builds, is allocated from a fixed internal-RAM buffer of this size
    /// instead of PSRAM, cutting PSRAM traffic on the protocol task. The arena holds one document
    /// at a time: a parsed message is released before the reply it triggers (a client/state after
    /// a server/activate, a pairing reply, a re-handshake's msg2) is built. The 2048 default
    /// covers one steady-state protocol message, including the FLAC stream-start header; a
    /// document larger than the budget on its own, such as a large track-metadata message, falls
    /// back to PSRAM. That holds on a 32-bit target, where ArduinoJson allocates each variant pool
    /// as one 1,024-byte block; on host a variant pool alone is 4 KB, so the default fits no
    /// document's pool there. Every freed block is wiped, in the arena or in PSRAM. Costs this
    /// many bytes of internal RAM permanently; smaller values just fall back more often.
    /// Set to 0 to send every document to PSRAM. On host there is no PSRAM distinction and the
    /// arena is a plain scratch buffer. Used by the protocol task only.
    size_t json_arena_size{DEFAULT_JSON_ARENA_SIZE};
};

// ============================================================================
// Player config types
// ============================================================================

/// @brief Audio codec format for an audio stream (player playback or source capture)
enum class SendspinCodecFormat : uint8_t {
    FLAC,         // FLAC lossless audio
    OPUS,         // Opus compressed audio
    PCM,          // Raw PCM audio
    UNSUPPORTED,  // Codec not recognized
};

/// @brief One supported audio format entry advertised by the player in the hello message
struct AudioSupportedFormatObject {
    SendspinCodecFormat codec;
    uint8_t channels;
    uint32_t sample_rate;
    uint8_t bit_depth;
};

/// @brief Configuration for the player role
struct PlayerRoleConfig {
    static constexpr size_t DEFAULT_AUDIO_BUFFER_CAPACITY = 1000000U;  ///< ~1MB default buffer
    /// @brief Formats the player supports, in priority order (the first is preferred).
    ///
    /// Must list at least one flac or pcm entry: those are the codecs every server supports, and a
    /// player is not told which others a server has (roles/player/v1.md "client/hello player@v1
    /// support object"). Opus may be listed in addition, but only in a build with the Opus decoder
    /// (SENDSPIN_ENABLE_OPUS, on by default). SendspinClient::start() fails and logs otherwise.
    std::vector<AudioSupportedFormatObject> audio_formats{};
    /// @brief Bytes of the shared inbound ring the player may hold as encoded audio: its quota,
    /// which the ring is sized to include. The client advertises the two thirds of it that hold
    /// encoded frames at the smallest chunk size to the server as buffer_capacity. The advertised
    /// value is at most the ring's largest item so that any single chunk the server may send
    /// fits.
    size_t audio_buffer_capacity{DEFAULT_AUDIO_BUFFER_CAPACITY};
    int32_t fixed_delay_us{0};
    uint16_t initial_output_delay_ms{0};

    /// @brief Default extra silence (ms) inserted at stream start for decode-pipeline headroom
    static constexpr uint16_t DEFAULT_EXTRA_STARTUP_SILENCE_MS = 50U;

    /// @brief Extra silence (ms) inserted at stream start, after the first playback notification
    /// and before the first decoded chunk, on top of the initial-sync priming silence. Gives the
    /// decode pipeline slack to stay ahead of the sink, preventing the initial-playback stutter.
    /// Larger values trade longer startup latency for more underflow protection; 0 disables.
    uint16_t extra_startup_silence_ms{DEFAULT_EXTRA_STARTUP_SILENCE_MS};

    /// @brief Silence the sync task feeds to the sink to prime it before the first decoded chunk.
    static constexpr uint16_t INITIAL_SYNC_PRIMING_MS = 25U;

    /// @brief Allowance for codec init, the first decode and the audio backend's own buffering
    /// on an ESP32-class target.
    static constexpr uint16_t PIPELINE_START_ALLOWANCE_MS = 75U;

    /// @brief Startup lead the decode pipeline itself spends before the first chunk can play in
    /// full, for a given `extra_startup_silence_ms`: the priming silence, the extra startup
    /// silence, and the pipeline start allowance. An overestimate: the extra silence replaces
    /// whatever priming silence is still unsent, so those two terms overlap in part.
    /// @return Lead time in milliseconds, saturated at the field's maximum.
    static constexpr uint16_t pipeline_lead_time_ms(uint16_t extra_startup_silence_ms) {
        constexpr uint32_t MAX = std::numeric_limits<uint16_t>::max();
        const uint32_t lead = static_cast<uint32_t>(INITIAL_SYNC_PRIMING_MS) +
                              extra_startup_silence_ms + PIPELINE_START_ALLOWANCE_MS;
        return static_cast<uint16_t>(lead < MAX ? lead : MAX);
    }

    /// @brief Startup lead in milliseconds reported as `required_lead_time_ms` in every
    /// client/state player object, measured from the server's transmission of a stream/start or
    /// stream/clear to the playback timestamp of the first chunk that can be played in full
    /// (roles/player/v1.md "client/state player object"). The server treats it as a hint and may
    /// give less.
    ///
    /// Unset reports `pipeline_lead_time_ms(extra_startup_silence_ms)`, so raising the startup
    /// silence raises the lead the server gives. Set it to cover an output whose own startup
    /// latency the allowance above does not reach. This library reports a configured or derived
    /// value, not a measured one.
    std::optional<uint16_t> required_lead_time_ms{};

    /// @brief Default ongoing buffer requested from the server. Sized to ride out the
    /// interference bursts a Wi-Fi client sees on a shared channel, and small enough that the
    /// audio it represents fits the default `audio_buffer_capacity` many times over.
    static constexpr uint16_t DEFAULT_MIN_BUFFER_MS = 500U;

    /// @brief Ongoing buffer duration in milliseconds reported as `min_buffer_ms` in every
    /// client/state player object: how much audio the player wants held ahead of playback during
    /// a stream to absorb network jitter and decode timing variance
    /// (roles/player/v1.md "client/state player object"). Mostly relevant for live streams, where
    /// the server has little audio in hand. The audio it represents must fit
    /// `audio_buffer_capacity` at the highest-bitrate format in `audio_formats`.
    uint16_t min_buffer_ms{DEFAULT_MIN_BUFFER_MS};

    bool psram_stack{false};  ///< Allocate sync task stack in PSRAM (ESP-IDF only)

    /// @brief Default FreeRTOS priority for the sync/decode task (ESP-IDF only).
    /// One above SendspinClientConfig::DEFAULT_HTTPD_PRIORITY so the httpd server task and the
    /// protocol task cannot starve the decoder during the initial burst of incoming encoded audio
    /// that fills the audio buffer at stream start.
    static constexpr unsigned DEFAULT_SYNC_TASK_PRIORITY =
        SendspinClientConfig::DEFAULT_HTTPD_PRIORITY + 1U;
    static_assert(SendspinClientConfig::DEFAULT_PROTOCOL_TASK_PRIORITY < DEFAULT_SYNC_TASK_PRIORITY,
                  "The protocol task must stay below the sync task");

    unsigned priority{DEFAULT_SYNC_TASK_PRIORITY};  ///< FreeRTOS priority for the sync/decode
                                                    ///< task (ESP-IDF only)

    /// @brief Memory placement for the decode transfer buffer (ESP-IDF only; ignored on host).
    /// Defaults to PREFER_EXTERNAL (SPIRAM).
    MemoryLocation decode_buffer_location{MemoryLocation::PREFER_EXTERNAL};
};

// ============================================================================
// Artwork config types
// ============================================================================

/// @brief Image format for artwork
/// roles/artwork/v1.md "client/state artwork object" defines exactly these two formats.
enum class SendspinImageFormat : uint8_t {
    JPEG,  // JPEG compressed image
    PNG,   // PNG image
};

/// @brief Source type for an artwork image
enum class SendspinImageSource : uint8_t {
    ALBUM,   // Album cover art
    ARTIST,  // Artist photo
    NONE,    // No image
};

/// @brief Preference for an image slot's format and resolution
struct ImageSlotPreference {
    /// @brief Default max_image_bytes: 128 KiB per artwork channel, which holds any JPEG a
    /// 320x320 channel receives (a noisy worst case measures about 78 KB, though a high-entropy
    /// PNG at that size can exceed the default), with room for a larger channel, and bounds a
    /// four-channel role at 512 KiB of assembly buffers. The inbound ring adds the artwork quota
    /// of one image per channel in flight plus, with the default player, the two per channel its
    /// 87 s hold carries: three images per channel, 1.5 MiB for four. That budget assumes PSRAM: on
    /// a part without it, lower this per channel to what internal RAM can spare, or start() fails
    /// for want of a channel's buffer.
    static constexpr uint32_t DEFAULT_MAX_IMAGE_BYTES = 128U * 1024U;

    SendspinImageSource source{};
    SendspinImageFormat format{};
    /// @brief Pixel dimensions the server delivers this channel's images at.
    uint16_t width{};
    uint16_t height{};

    /// @brief Opt-in per-slot back-pressure gate. When true, the role delivers at most one
    /// un-acked "delivery" at a time for this slot: a delivery is either a frame
    /// (on_image_decode() followed by on_image_display()) or a clear (on_image_clear()). While a
    /// delivery is un-acked, any newer payload that arrives is buffered latest-wins and only
    /// delivered once the consumer calls ArtworkRole::frame_done(slot) from the main loop (e.g.
    /// after a cross-fade animation completes). Defaults to false: every frame is decoded and
    /// displayed as it arrives.
    bool require_frame_done{false};

    /// @brief Fires on_image_display() this many milliseconds before the server's display
    /// timestamp (negative delays it). Lets a cross-fade straddle the track boundary: with a
    /// 2 s fade, an offset of 1000 starts the fade 1 s before the boundary so the incoming image
    /// is fully shown 1 s after it. Positive-equals-earlier mirrors
    /// PlayerRoleConfig::fixed_delay_us. Best-effort: an image that arrives or decodes after the
    /// offset deadline fires as soon as it is ready, same as any past-timestamp display.
    int32_t display_offset_ms{0};

    /// @brief Largest encoded image this channel will hold, in bytes. An image the server
    /// announces as larger is refused: the transfer is followed to its end with its bytes dropped
    /// and the channel keeps whatever it was showing. Raise it for a channel whose images are
    /// genuinely larger; the role logs every image it refuses, with the cap it was measured
    /// against. The role holds one buffer of this size per configured channel, which each image is
    /// assembled in, allocated by SendspinClient::start() and released by stop(); an image that
    /// completes while a require_frame_done channel's last delivery is un-acked waits there. The
    /// image's messages arrive in the shared inbound ring, which reserves one image of this size
    /// per channel in flight, sent in parts of at least 4,096 bytes: each part is charged its
    /// stored size, so an image split into smaller parts can exceed the reservation and is dropped
    /// while the decode thread is busy. A channel with 0 here holds nothing.
    uint32_t max_image_bytes{DEFAULT_MAX_IMAGE_BYTES};
};

/// @brief Configuration for the artwork role
struct ArtworkRoleConfig {
    /// @brief Slot/channel preferences in order. The array index is the channel slot number
    /// (matched against the binary message slot byte and advertised to the server in that
    /// order). Limited to ARTWORK_MAX_SLOTS (4) entries; extra entries are truncated with a
    /// warning.
    std::vector<ImageSlotPreference> preferred_formats{};
    bool psram_stack{false};  ///< Allocate decode thread stack in PSRAM (ESP-IDF only)

    /// @brief Default FreeRTOS priority for the image decode thread (ESP-IDF only). Image
    /// decoding is best-effort work with seconds of slack, so it sits below the protocol and
    /// httpd tasks (SendspinClientConfig::DEFAULT_PROTOCOL_TASK_PRIORITY, DEFAULT_HTTPD_PRIORITY).
    static constexpr unsigned DEFAULT_ARTWORK_PRIORITY = 2U;
    static_assert(DEFAULT_ARTWORK_PRIORITY < SendspinClientConfig::DEFAULT_HTTPD_PRIORITY,
                  "The artwork decode thread must stay below the httpd task");
    static_assert(DEFAULT_ARTWORK_PRIORITY < SendspinClientConfig::DEFAULT_PROTOCOL_TASK_PRIORITY,
                  "The artwork decode thread must stay below the protocol task");

    /// @brief FreeRTOS priority for the image decode thread (ESP-IDF only)
    unsigned priority{DEFAULT_ARTWORK_PRIORITY};
};

// ============================================================================
// Visualizer config types
// ============================================================================

/// @brief Visualizer data stream types
enum class VisualizerDataType : uint8_t {
    BEAT,      // Musical beat events from tempo/beat tracking
    LOUDNESS,  // Overall loudness level
    F_PEAK,    // Dominant frequency and amplitude
    SPECTRUM,  // Full frequency spectrum bins
    PEAK,      // Energy onset (transient) events
};

/// @brief Frequency scale used for spectrum visualization bins
enum class VisualizerSpectrumScale : uint8_t {
    MEL,  // Mel perceptual scale
    LOG,  // Logarithmic scale
    LIN,  // Linear scale
};

/// @brief Spectrum visualization parameters: bin count, frequency range, and scale
struct VisualizerSpectrumConfig {
    /// @brief Number of display bins (bars on a graphical equalizer)
    uint8_t n_disp_bins;
    VisualizerSpectrumScale scale;
    uint16_t f_min;
    uint16_t f_max;
};

/// @brief Visualizer capabilities advertised to the server during the hello handshake
struct VisualizerSupportObject {
    /// @brief RAM budget in bytes for the frames the visualizer holds: its share of the shared
    /// inbound ring and the quota its held frames are charged against. This is not the amount of
    /// wire data that fits: each frame is held as the encrypted message it arrived in, with the
    /// ring's per-item overhead, so for the small visualizer frames only about a seventh of this
    /// budget holds actual wire data. The client advertises that effective capacity to the
    /// server, not this raw budget, so the server's flow control does not overrun the quota.
    /// VisualizerRole's start fails below 70 bytes, the smallest budget that advertises one
    /// smallest frame, the default 0 included. The advertised value is at most the ring's largest
    /// item so that any single chunk the server may send fits, which a seventh of the quota
    /// always is.
    size_t buffer_capacity{};
};

/// @brief Visualization data the client asks the server to stream, reported in client/state
struct VisualizerStreamConfig {
    /// @brief Data types the client wants to receive. May be empty to request no data
    std::vector<VisualizerDataType> types{};
    /// @brief Maximum periodic visualization frames per second (applies to LOUDNESS, F_PEAK,
    /// SPECTRUM). Event types (BEAT, PEAK) are not throttled. Set to the display refresh rate
    uint16_t rate_max{};
    /// @brief Spectrum configuration, required if types includes SPECTRUM
    std::optional<VisualizerSpectrumConfig> spectrum;
};

/// @brief Configuration for the visualizer role
struct VisualizerRoleConfig {
    /// @brief Capabilities the client/hello support object carries
    VisualizerSupportObject support;
    /// @brief Stream configuration the client/state visualizer object carries
    VisualizerStreamConfig stream;
    bool psram_stack{false};  ///< Allocate drain thread stack in PSRAM (ESP-IDF only)

    /// @brief Fires the data callbacks this many milliseconds before the frame's display time
    /// (negative delays them), so a consumer that renders on its own cadence can set its render
    /// latency here. The callbacks' client_timestamp stays the display time. The sign follows
    /// ImageSlotPreference::display_offset_ms.
    int32_t display_offset_ms{0};

    /// @brief Default FreeRTOS priority for the visualization drain thread (ESP-IDF only).
    /// Delivering frames is best-effort work, so it sits below the protocol and httpd tasks
    /// (SendspinClientConfig::DEFAULT_PROTOCOL_TASK_PRIORITY, DEFAULT_HTTPD_PRIORITY).
    static constexpr unsigned DEFAULT_VISUALIZER_PRIORITY = 2U;
    static_assert(DEFAULT_VISUALIZER_PRIORITY < SendspinClientConfig::DEFAULT_HTTPD_PRIORITY,
                  "The visualization drain thread must stay below the httpd task");
    static_assert(DEFAULT_VISUALIZER_PRIORITY <
                      SendspinClientConfig::DEFAULT_PROTOCOL_TASK_PRIORITY,
                  "The visualization drain thread must stay below the protocol task");

    /// @brief FreeRTOS priority for the visualization drain thread (ESP-IDF only)
    unsigned priority{DEFAULT_VISUALIZER_PRIORITY};
};

// ============================================================================
// Source config types
// ============================================================================

/// @brief Configuration for the source role (audio capture streamed to the server)
///
/// The configured format is the format of every input stream the role opens; write_audio() takes
/// PCM in exactly this format. An invalid config is rejected, never clamped or repaired: the role
/// stays added but inert (logged at ERROR, never advertised, never streams).
struct SourceRoleConfig {
    /// @brief Chunk duration bounds, roles/source/v1.md "Source Audio Chunks (Binary)": a chunk
    /// MUST be at most 150 ms and SHOULD be at least 5 ms. A chunk holds whole frames, so its
    /// duration rounds down.
    static constexpr uint32_t CHUNK_MIN_MS = 5U;
    static constexpr uint32_t CHUNK_MAX_MS = 150U;

    /// @brief Default chunk duration: keeps latency and send buffers small and per-chunk framing
    /// and encryption cost negligible
    static constexpr uint32_t DEFAULT_CHUNK_MS = 20U;

    /// @brief Default capture buffer. The buffer bounds the backlog a network stall can build up
    /// (roles/source/v1.md "Source Audio Chunks (Binary)"), so it is also about the longest send
    /// stall the stream rides out without a gap: 500 ms covers the routine stalls of an ESP32 WiFi
    /// link.
    static constexpr uint32_t DEFAULT_CAPTURE_BUFFER_MS = 500U;

    /// @brief Default capture sample rate (Hz), the native rate of most capture hardware
    static constexpr uint32_t DEFAULT_SAMPLE_RATE = 48000U;

    /// @brief Default FreeRTOS priority for the source task (ESP-IDF only). Below the httpd and
    /// protocol tasks that send what it produces, and the player's sync task, so chunk assembly
    /// never starves playback; above the artwork and visualizer threads.
    static constexpr unsigned DEFAULT_SOURCE_TASK_PRIORITY = 3U;
    static_assert(DEFAULT_SOURCE_TASK_PRIORITY <
                      SendspinClientConfig::DEFAULT_PROTOCOL_TASK_PRIORITY,
                  "The source task must stay below the protocol task");

    /// @brief Source task stack size in bytes for a PCM config (ESP-IDF only). Derived in
    /// tools/stack_usage/README.md.
    static constexpr size_t DEFAULT_SOURCE_TASK_STACK_SIZE = 2048U;

    /// @brief Source task stack size in bytes for an OPUS config (ESP-IDF only). Derived in
    /// tools/stack_usage/README.md.
    static constexpr size_t DEFAULT_OPUS_SOURCE_TASK_STACK_SIZE = 6656U;

    /// @brief Opus bitrate bounds in bit/s: the range opus.h documents for OPUS_SET_BITRATE (the
    /// encoder clamps anything outside it). Not named OPUS_BITRATE_*, which opus.h defines as
    /// macros.
    static constexpr uint32_t MIN_OPUS_BITRATE = 500U;
    static constexpr uint32_t MAX_OPUS_BITRATE = 512000U;

    /// @brief Default Opus bitrate (bit/s): transparent-leaning for 48 kHz stereo music. The
    /// encoder runs CELT only (no SILK speech mode), so mono capture still wants 48000 or more.
    static constexpr uint32_t DEFAULT_OPUS_BITRATE = 128000U;

    /// @brief Largest Opus encoder complexity libopus's OPUS_SET_COMPLEXITY accepts
    static constexpr uint8_t MAX_OPUS_COMPLEXITY = 10U;

    /// @brief Default Opus encoder complexity: low, to fit an ESP32-class real-time encode budget.
    /// A host can raise it toward MAX_OPUS_COMPLEXITY, trading CPU for quality.
    static constexpr uint8_t DEFAULT_OPUS_COMPLEXITY = 2U;

    // 32-bit fields
    /// @brief Capture sample rate in Hz; must be > 0. OPUS takes only libopus's rates: 8000,
    /// 12000, 16000, 24000, or 48000.
    uint32_t sample_rate{DEFAULT_SAMPLE_RATE};

    /// @brief Chunk duration in milliseconds, within [CHUNK_MIN_MS, CHUNK_MAX_MS]. One chunk, with
    /// its 9-byte header, must also fit one Noise transport message (65519 bytes), which bounds
    /// the duration for wide or deep formats. OPUS takes only 5, 10, 20, 40, or 60: a chunk is
    /// one Opus frame.
    uint32_t chunk_duration_ms{DEFAULT_CHUNK_MS};

    /// @brief Capture buffer in milliseconds of audio in the configured format; must be > 0.
    /// Approximate: per-write bookkeeping comes out of a fixed 25% margin, so many very small
    /// write_audio() calls hold less audio than this. A single write longer than half of it (with
    /// that margin) is always refused, without disturbing the audio already queued.
    uint32_t capture_buffer_ms{DEFAULT_CAPTURE_BUFFER_MS};

    /// @brief Opus bitrate in bit/s, within [MIN_OPUS_BITRATE, MAX_OPUS_BITRATE]. Ignored (and
    /// not validated) for PCM.
    uint32_t opus_bitrate{DEFAULT_OPUS_BITRATE};

    unsigned priority{DEFAULT_SOURCE_TASK_PRIORITY};  ///< FreeRTOS priority for the source task
                                                      ///< (ESP-IDF only)

    /// @brief Placement of the capture buffer and the outbound chunk buffer (ESP-IDF only). Bulk
    /// audio with sequential access, so PSRAM-preferring like the player's audio buffers.
    MemoryLocation buffer_location{MemoryLocation::PREFER_EXTERNAL};

    // 8-bit fields
    /// @brief Codec the role streams in: PCM (the capture bytes, untouched) or OPUS (each chunk
    /// encoded into one CELT-only packet; needs SENDSPIN_ENABLE_OPUS). An OPUS role streams PCM
    /// to a server whose server/hello does not list opus (roles/source/v1.md "server/hello
    /// source@v1 support object"). OPUS costs the encoder state plus the source task's own
    /// micro-opus scratch arena (about 120 KB, PSRAM-preferring, allocated before the first opus
    /// chunk is encoded).
    SendspinCodecFormat codec{SendspinCodecFormat::PCM};

    /// @brief Opus encoder complexity, at most MAX_OPUS_COMPLEXITY. Ignored (and not validated)
    /// for PCM.
    uint8_t opus_complexity{DEFAULT_OPUS_COMPLEXITY};
    uint8_t channels{2};      ///< Capture channel count; must be > 0 (1 or 2 for OPUS)
    uint8_t bit_depth{16};    ///< Bits per sample: 16, 24 (3 packed bytes), or 32 (16 for OPUS)
    bool line_sense{false};   ///< Advertise signal sensing (see SourceRole::set_signal())
    bool psram_stack{false};  ///< Allocate the source task stack in PSRAM (ESP-IDF only)
};

}  // namespace sendspin
