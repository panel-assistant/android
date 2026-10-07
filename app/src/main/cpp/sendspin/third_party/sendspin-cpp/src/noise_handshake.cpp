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

#include "noise_handshake.h"

#include "crypto/constants.h"
#include "crypto/keys.h"
#include "noise_session.h"
#include "platform/base64.h"
#include "platform/json_arena.h"
#include "platform/logging.h"
#include <ArduinoJson.h>

#include <cstring>
#include <string>
#include <vector>

static const char* const TAG = "sendspin.noise_handshake";

namespace sendspin {

// ============================================================================
// JSON serialization helpers
// ============================================================================

/// @brief Serialize client/init to JSON.
/// Format: {"type":"client/init","payload":{"client_id":"...","version":1,"suite":"..."}}
static std::string serialize_client_init(const std::string& client_id,
                                         const std::string& suite_name,
                                         SendspinArenaAllocator& arena) {
    // suite_name is the full name (e.g. NOISE_SUITE_CHACHAPOLY = "Noise_KKpsk2_25519_..."); the
    // wire value is the suffix after "Noise_KKpsk2_" (connection.md "Cipher Suites"). Strip
    // that prefix to produce the wire suite string.
    static constexpr const char* PREFIX = "Noise_KKpsk2_";
    constexpr size_t PREFIX_LEN = std::char_traits<char>::length(PREFIX);
    std::string wire_suite = suite_name;
    if (wire_suite.size() > PREFIX_LEN && wire_suite.substr(0, PREFIX_LEN) == PREFIX) {
        wire_suite = wire_suite.substr(PREFIX_LEN);
    }

    JsonDocument doc = make_json_document(arena);
    doc["type"] = "client/init";
    doc["payload"]["client_id"] = client_id;
    doc["payload"]["version"] = PROTOCOL_VERSION;
    doc["payload"]["suite"] = wire_suite;

    std::string out;
    serializeJson(doc, out);
    return out;
}

/// @brief Serialize a noise/handshake frame containing base64url-encoded noise bytes.
/// Format: {"type":"noise/handshake","payload":{"data":"..."}}
static std::string serialize_noise_handshake(const std::vector<uint8_t>& noise_bytes,
                                             SendspinArenaAllocator& arena) {
    std::string encoded = b64url_encode(noise_bytes.data(), noise_bytes.size());

    JsonDocument doc = make_json_document(arena);
    doc["type"] = "noise/handshake";
    doc["payload"]["data"] = encoded;

    std::string out;
    serializeJson(doc, out);
    return out;
}

// ============================================================================
// Shared msg1-processing core (initial handshake + re-handshake)
// ============================================================================

namespace {

/// @brief Outcome of the shared read-msg1/resolve-psk/set-psk/write-msg2 core.
/// Carries everything both `NoiseHandshake::handle_msg1` and `run_rehandshake_msg1`
/// need to build their own `NoiseHandshakeResult` and deliver msg2 in their own way.
struct Msg1CoreResult {
    /// The session, split into transport cipher states, with the resolved PSK bound.
    NoiseSession session;
    /// The PSK record that admitted the connection.
    ResolvedPsk resolved_psk;
    /// Serialized noise/handshake msg2 bytes (not yet wrapped in a JSON envelope).
    std::vector<uint8_t> msg2_bytes;
};

/// @brief Which handshake a msg1 belongs to.
///
/// The two differ in their prologue, in how msg2 reaches the peer, and in what a psk_id lookup
/// miss means: connection.md "Sentinel Fallback" has the initial handshake answer a miss with the
/// Sentinel PSK, while a miss in a re-handshake fails the handshake.
enum class HandshakeKind : uint8_t {
    INITIAL,
    REHANDSHAKE,
};

/// @brief Name used for this handshake in log lines.
const char* to_cstr(HandshakeKind kind) {
    return kind == HandshakeKind::INITIAL ? "handshake" : "re-handshake";
}

/// @brief Run the shared core of Noise msg1 processing: decode server_id, read msg1
/// to expose psk_id, resolve the PSK via the record store, verify any stored-pubkey
/// binding, bind the resolved PSK onto the same session, and write msg2.
///
/// Used by the initial handshake (`NoiseHandshake::handle_msg1`) and the in-band re-handshake
/// (`run_rehandshake_msg1`), which supply the prologue and deliver msg2 themselves.
///
/// @param kind          Which handshake this msg1 belongs to.
/// @param identity      Our static X25519 identity.
/// @param record_store  Record store for psk_id resolution (read-only on protocol task).
/// @param suite_name    Noise suite name (NOISE_SUITE_CHACHAPOLY; see crypto/constants.h).
/// @param server_id     Known/claimed server peer_id (43-char base64url).
/// @param prologue      Exact prologue bytes for this handshake (caller-specific).
/// @param prologue_len  Length of `prologue`.
/// @param msg1_bytes    The msg1 Noise bytes (read_noise_handshake_data()).
/// @param arena         The client's JSON arena, to parse msg1's payload in.
/// @return Populated Msg1CoreResult on success, or nullopt on any failure (caller aborts).
std::optional<Msg1CoreResult> run_msg1_core(
    HandshakeKind kind, const Identity& identity, const RecordStore& record_store,
    const std::string& suite_name, const std::string& server_id, const uint8_t* prologue,
    size_t prologue_len, const std::vector<uint8_t>& msg1_bytes, SendspinArenaAllocator& arena) {
    const char* log_prefix = to_cstr(kind);
    auto server_pub = public_key_from_peer_id(server_id);
    if (!server_pub.has_value()) {
        SS_LOGE(TAG, "%s: invalid server_id (cannot decode public key)", log_prefix);
        return std::nullopt;
    }

    // psk_id lives inside msg1's encrypted payload, so the PSK is not known yet when the
    // session is built. KKpsk2 mixes the PSK only into msg2, so msg1 authenticates under the
    // static keys alone (KK's "es"/"ss" tokens); build the responder with no PSK bound and
    // defer that until it is resolved below (see NoiseSession::set_psk).
    auto session = NoiseSession::as_responder(suite_name, identity.private_bytes.data(),
                                              server_pub->data(), prologue, prologue_len, nullptr);
    if (!session.has_value()) {
        SS_LOGE(TAG, "%s: failed to build session", log_prefix);
        return std::nullopt;
    }

    auto msg1_payload = session->read_msg1(msg1_bytes.data(), msg1_bytes.size());
    if (msg1_payload.empty()) {
        SS_LOGE(TAG, "%s: read_msg1 failed (auth error in msg1 static DH)", log_prefix);
        return std::nullopt;
    }

    // Parse psk_id from the decrypted msg1 payload: {"psk_id":"...","psk_category":"..."}. The
    // payload is released as soon as its two fields are copied out, so the msg2 envelope the
    // caller builds has the arena to itself.
    ParsedJsonMessage payload(arena);
    if (!payload.parse(reinterpret_cast<const char*>(msg1_payload.data()), msg1_payload.size())) {
        SS_LOGE(TAG, "%s: failed to parse msg1 payload JSON", log_prefix);
        return std::nullopt;
    }
    std::string psk_id;
    std::string psk_category_code;
    payload.extract([&psk_id, &psk_category_code](JsonObjectConst root) {
        psk_id = root["psk_id"] | "";
        psk_category_code = root["psk_category"] | "";
    });

    if (psk_id.empty()) {
        SS_LOGE(TAG, "%s: psk_id missing from msg1 payload", log_prefix);
        return std::nullopt;
    }

    // messaging.md "noise/handshake": the payload declares which category the server is using the
    // referenced PSK as. A missing or unknown code is a malformed payload, which connection.md
    // "Failure Handling" makes a silent failure.
    auto psk_category = psk_category_from_string(psk_category_code);
    if (!psk_category.has_value()) {
        SS_LOGE(TAG, "%s: msg1 payload psk_category missing or unknown ('%s')", log_prefix,
                psk_category_code.c_str());
        return std::nullopt;
    }

    SS_LOGD(TAG, "%s: psk_id='%s' psk_category='%s'", log_prefix, psk_id.c_str(),
            psk_category_code.c_str());

    auto resolved = record_store.resolve_by_psk_id(psk_id, psk_category.value());
    if (resolved.has_value()) {
        // Post-match check (connection.md "Pre-Shared Key"): every long-term PSK is persisted
        // with the server_id it was minted for. The Pairing and Sentinel PSKs are bound to no
        // server and skip it. A misbinding is not a lookup miss, so it fails the handshake in
        // both kinds (connection.md "Sentinel Fallback").
        if (resolved->category == PskCategory::LONG_TERM &&
            resolved->counterparty_id != server_id) {
            SS_LOGW(TAG, "%s: PSK bound to server_id='%s', but connected to '%s'", log_prefix,
                    resolved->counterparty_id.value_or("").c_str(), server_id.c_str());
            return std::nullopt;
        }
    } else if (kind == HandshakeKind::INITIAL) {
        // connection.md "Sentinel Fallback": on a lookup miss in the initial handshake the client
        // completes msg2 with the Sentinel PSK instead of failing. The server, which holds the PSK
        // it referenced, cannot read a msg2 keyed with the Sentinel one, and so receives an
        // authenticated credential-mismatch signal it can offer its operator (pairing.md
        // "Pairing Records": an evicted record needs no wire signal of its own). Failing here
        // instead would leave a client that lost its record reconnect-looping with no way back.
        SS_LOGW(TAG, "%s: no '%s' PSK for psk_id='%s'; answering with the Sentinel PSK", log_prefix,
                psk_category_code.c_str(), psk_id.c_str());
        resolved = record_store.resolve_by_psk_id(SENTINEL_PSK_ID, PskCategory::SENTINEL);
        if (!resolved.has_value()) {
            SS_LOGE(TAG, "%s: the Sentinel PSK did not resolve", log_prefix);
            return std::nullopt;
        }
    } else {
        // connection.md "Sentinel Fallback" applies to the initial handshake alone.
        SS_LOGW(TAG, "%s: no '%s' PSK for psk_id='%s', aborting", log_prefix,
                psk_category_code.c_str(), psk_id.c_str());
        return std::nullopt;
    }

    // Bind the resolved PSK onto the SAME handshake state that already read msg1, then
    // proceed to msg2. This is legal because the "psk" token is not processed until msg2.
    if (!session->set_psk(resolved->psk.data())) {
        SS_LOGE(TAG, "%s: failed to bind resolved PSK", log_prefix);
        return std::nullopt;
    }

    // Write msg2 (payload: `{}`) and split to transport mode
    std::vector<uint8_t> msg2_bytes;
    if (!session->write_msg2_and_split(msg2_bytes)) {
        SS_LOGE(TAG, "%s: write_msg2_and_split failed", log_prefix);
        return std::nullopt;
    }

    return Msg1CoreResult{std::move(session.value()), std::move(resolved.value()),
                          std::move(msg2_bytes)};
}

/// @brief Build a NoiseHandshakeResult from a completed Msg1CoreResult. Shared by
/// NoiseHandshake::handle_msg1 (initial handshake) and run_rehandshake_msg1: both assemble the
/// result identically from the core's session and resolved_psk; only msg2_text differs, so the
/// caller sets it afterward.
/// @param core       Completed core result (session and resolved_psk are moved out of it).
/// @param server_id  Server peer_id to record on the result.
NoiseHandshakeResult make_handshake_result(Msg1CoreResult&& core, std::string server_id) {
    NoiseHandshakeResult result;
    result.session = std::make_unique<NoiseSession>(std::move(core.session));
    result.server_id = std::move(server_id);
    result.resolved_psk = std::move(core.resolved_psk);
    return result;
}

}  // namespace

// ============================================================================
// noise/handshake envelope
// ============================================================================

std::optional<std::vector<uint8_t>> read_noise_handshake_data(JsonObjectConst envelope,
                                                              const char* log_context) {
    const char* data_b64 = envelope["payload"]["data"] | "";
    if (data_b64[0] == '\0') {
        SS_LOGE(TAG, "%s: missing data field", log_context);
        return std::nullopt;
    }

    auto bytes = b64url_decode(data_b64);
    if (!bytes.has_value() || bytes->empty()) {
        SS_LOGE(TAG, "%s: failed to base64url-decode noise msg1", log_context);
        return std::nullopt;
    }
    return bytes;
}

// ============================================================================
// Constructor
// ============================================================================

NoiseHandshake::NoiseHandshake(const Identity& identity, const RecordStore& record_store,
                               const std::string& suite_name, SendspinArenaAllocator& arena)
    : suite_name_(suite_name), arena_(arena), identity_(identity), record_store_(record_store) {}

// ============================================================================
// Public API
// ============================================================================

std::string NoiseHandshake::build_client_init() {
    if (this->state_ != State::INIT) {
        SS_LOGE(TAG, "build_client_init called in wrong state");
        return {};
    }

    std::string text =
        serialize_client_init(this->identity_.peer_id(), this->suite_name_, this->arena_);
    this->client_init_text_ = text;
    this->state_ = State::WAIT_SERVER_INIT;
    return text;
}

void NoiseHandshake::take_server_error(JsonObjectConst root, const char* log_context) {
    // The handshake aborts either way; recognizing the message is what gives the abort the
    // server's own account of the refusal.
    this->server_error_reason_ = root["payload"]["reason"] | "";
    SS_LOGE(TAG, "%s: server/error, reason='%s'; the server refused the connection", log_context,
            this->server_error_reason_.c_str());
}

HandshakeFrameResult NoiseHandshake::on_text_frame(
    const std::string& text, const std::function<bool(const std::string&)>& send_fn) {
    if (this->state_ != State::WAIT_SERVER_INIT && this->state_ != State::WAIT_MSG1) {
        SS_LOGE(TAG, "on_text_frame: no frame expected in this state");
        this->state_ = State::ABORTED;
        return HandshakeFrameResult::ABORT;
    }

    // One parse per frame. The fields the step needs are copied out and the frame released
    // before the step runs, so the msg2 envelope it builds has the arena to itself.
    ParsedJsonMessage frame(this->arena_);
    if (!frame.parse(text.data(), text.size())) {
        SS_LOGE(TAG, "on_text_frame: JSON parse failed");
        this->state_ = State::ABORTED;
        return HandshakeFrameResult::ABORT;
    }

    const bool awaiting_server_init = this->state_ == State::WAIT_SERVER_INIT;
    const char* log_context =
        awaiting_server_init ? "awaiting server/init" : "awaiting noise/handshake msg1";
    const char* expected_type = awaiting_server_init ? "server/init" : "noise/handshake";

    int version = 0;
    std::string server_id;
    std::optional<std::vector<uint8_t>> msg1_bytes;
    const bool expected = frame.extract([&](JsonObjectConst root) {
        const char* type = root["type"] | "";
        // messaging.md "server/error": sent in place of server/init when the server cannot
        // accept our client/init. Recognized in either wait so an abort names the server's
        // reason.
        if (std::strcmp(type, "server/error") == 0) {
            this->take_server_error(root, log_context);
            return false;
        }
        if (std::strcmp(type, expected_type) != 0) {
            SS_LOGE(TAG, "%s: unexpected type '%s'", log_context, type);
            return false;
        }
        if (awaiting_server_init) {
            version = root["payload"]["version"] | 0;
            server_id = root["payload"]["server_id"] | "";
        } else {
            msg1_bytes = read_noise_handshake_data(root, to_cstr(HandshakeKind::INITIAL));
        }
        return true;
    });
    if (!expected) {
        this->state_ = State::ABORTED;
        return HandshakeFrameResult::ABORT;
    }

    if (awaiting_server_init) {
        if (!this->handle_server_init(version, std::move(server_id), text)) {
            this->state_ = State::ABORTED;
            return HandshakeFrameResult::ABORT;
        }
        this->state_ = State::WAIT_MSG1;
        return HandshakeFrameResult::NEED_MORE;
    }

    if (!msg1_bytes.has_value() || !this->handle_msg1(msg1_bytes.value(), send_fn)) {
        this->state_ = State::ABORTED;
        return HandshakeFrameResult::ABORT;
    }
    this->state_ = State::COMPLETE;
    return HandshakeFrameResult::COMPLETE;
}

// ============================================================================
// Private: handle server/init
// ============================================================================

bool NoiseHandshake::handle_server_init(int version, std::string server_id,
                                        const std::string& text) {
    if (version != PROTOCOL_VERSION) {
        SS_LOGE(TAG, "handle_server_init: unsupported version %d (expected %d)", version,
                PROTOCOL_VERSION);
        return false;
    }

    // Checked here rather than left to the handshake: records and the last-playback server store
    // the key, not the text, so a non-canonical spelling would not match itself after a reboot.
    if (!public_key_from_peer_id(server_id).has_value()) {
        SS_LOGE(TAG, "handle_server_init: server_id is not a canonical base64url public key");
        return false;
    }

    this->server_id_ = std::move(server_id);
    this->server_init_text_ = text;  // Retain exact bytes for prologue

    SS_LOGD(TAG, "server/init received: server_id=%s", this->server_id_.c_str());
    return true;
}

// ============================================================================
// Private: handle noise/handshake msg1
// ============================================================================

bool NoiseHandshake::handle_msg1(const std::vector<uint8_t>& msg1_bytes,
                                 const std::function<bool(const std::string&)>& send_fn) {
    // Prologue = exact bytes of client/init || server/init
    std::string prologue_str = this->client_init_text_ + this->server_init_text_;
    const uint8_t* prologue = reinterpret_cast<const uint8_t*>(prologue_str.data());
    size_t prologue_len = prologue_str.size();

    auto core = run_msg1_core(HandshakeKind::INITIAL, this->identity_, this->record_store_,
                              this->suite_name_, this->server_id_, prologue, prologue_len,
                              msg1_bytes, this->arena_);
    if (!core.has_value()) {
        return false;
    }

    // Send noise/handshake msg2 as a TEXT frame
    std::string msg2_text = serialize_noise_handshake(core->msg2_bytes, this->arena_);
    if (!send_fn(msg2_text)) {
        SS_LOGE(TAG, "handle_msg1: failed to send noise/handshake msg2");
        return false;
    }

    SS_LOGI(TAG, "Noise handshake complete: server_id=%s psk_category=%d", this->server_id_.c_str(),
            static_cast<int>(core->resolved_psk.category));

    this->result_ = make_handshake_result(std::move(core.value()), this->server_id_);

    return true;
}

// ============================================================================
// Re-handshake helper
// ============================================================================

std::optional<NoiseHandshakeResult> run_rehandshake_msg1(
    const std::vector<uint8_t>& msg1_bytes, const std::string& server_id, const Identity& identity,
    const RecordStore& record_store, const std::string& suite_name,
    const std::array<uint8_t, 32>& prior_h, SendspinArenaAllocator& arena) {
    // Prologue for re-handshake = prior handshake hash h (32 bytes)
    const uint8_t* prologue = prior_h.data();
    const size_t prologue_len = prior_h.size();

    auto core = run_msg1_core(HandshakeKind::REHANDSHAKE, identity, record_store, suite_name,
                              server_id, prologue, prologue_len, msg1_bytes, arena);
    if (!core.has_value()) {
        return std::nullopt;
    }

    // Serialize the msg2 noise/handshake envelope (caller sends it encrypted)
    std::string msg2_text = serialize_noise_handshake(core->msg2_bytes, arena);

    SS_LOGI(TAG, "Re-handshake complete: server_id=%s psk_category=%d", server_id.c_str(),
            static_cast<int>(core->resolved_psk.category));

    NoiseHandshakeResult result = make_handshake_result(std::move(core.value()), server_id);
    result.msg2_text = std::move(msg2_text);
    return result;
}

}  // namespace sendspin
