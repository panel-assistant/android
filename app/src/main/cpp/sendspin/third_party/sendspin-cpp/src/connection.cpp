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

#include "connection.h"

#include "crypto/constants.h"
#include "outbound_ring.h"
#include "platform/compiler.h"
#include "platform/logging.h"
#include "platform/time.h"
#include "protocol_messages.h"
#include "protocol_task.h"
#include "sendspin/types.h"
#include "time_filter.h"

#include <algorithm>
#include <cstddef>
#include <cstring>
#include <memory>
#include <optional>
#include <string>
#include <utility>
#include <vector>

namespace sendspin {

static const char* const TAG = "sendspin.connection";

// ============================================================================
// Constructor / Destructor
// ============================================================================

SendspinConnection::SendspinConnection() {
    this->noise_transport_.set_frame_sink(
        [this](const uint8_t* data, size_t len,
               const NoiseTransport::FrameWriteHook& before_write) {
            return this->send_transport_frame(data, len, before_write);
        });
    this->noise_transport_.set_lent_frame_sink([this](OutboundRing& ring, void* item, size_t len) {
        return this->send_lent_frame(ring, item, len);
    });
}

SendspinConnection::~SendspinConnection() = default;

// ============================================================================
// Transport frames
// ============================================================================

SsErr SendspinConnection::send_transport_frame(const uint8_t* data, size_t len,
                                               const NoiseTransport::FrameWriteHook& before_write) {
    if (before_write) {
        before_write();
    }
    return this->send_binary_message(data, len);
}

SsErr SendspinConnection::send_lent_frame(OutboundRing& ring, void* item, size_t len) {
    const SsErr err = this->send_binary_message(outbound_item_message(item), len);
    ring.return_item(item);
    return err;
}

// ============================================================================
// Time filter
// ============================================================================

void SendspinConnection::init_time_filter() {
    this->time_filter_ = std::make_shared<SendspinTimeFilter>(SendspinTimeFilter::Config{});
}

void SendspinConnection::wake_protocol_task() const {
    if (this->inbound_task_ != nullptr) {
        this->inbound_task_->wake();
    }
}

// ============================================================================
// Message sending
// ============================================================================

SsErr SendspinConnection::send_goodbye_reason(SendspinGoodbyeReason reason) {
    // Routed through send_app_json() so it is encrypted once the Noise transport is active. A
    // goodbye can precede the hello (e.g., when rejecting an excess connection before the
    // handshake finishes).
    return this->send_app_json(format_client_goodbye_message(reason, *this->json_arena_));
}

SsErr SendspinConnection::send_app_json(const std::string& json) {
    // Delegate to the pointer/length overload: same routing (see that overload).
    return this->send_app_json(json.data(), json.size());
}

SsErr SendspinConnection::send_app_json(const char* json, size_t len) {
    // Protocol task only, like the re-handshake swap, so the session cannot change between this
    // check and the encrypt.
    if (this->noise_transport_.is_active()) {
        // Post-handshake: encrypt straight from the caller's buffer.
        return this->send_encrypted_text(json, len);
    }
    // Pre-handshake cold path: the text-frame API takes a std::string.
    return this->send_text_message(std::string(json, len));
}

SsErr SendspinConnection::send_app_binary_lent(OutboundRing& ring, void* item,
                                               size_t message_capacity, size_t plaintext_len) {
    // Before the handshake NoiseTransport::send_binary_lent() refuses it (no cleartext fallback).
    if (!this->accepts_app_sends()) {
        ring.return_item(item);
        return SsErr::INVALID_STATE;
    }
    return this->settle_noise_send(
        this->noise_transport_.send_binary_lent(ring, item, message_capacity, plaintext_len));
}

SsErr SendspinConnection::settle_noise_send(SsErr err) {
    if (this->noise_transport_.is_send_desynced() && !this->inbound_gate_.is_detached() &&
        this->is_connected()) {
        SS_LOGW(TAG, "Send failed after its frame was encrypted (err=%d); closing the connection",
                static_cast<int>(err));
        this->close_silently(SendspinGoodbyeReason::UNAUTHORIZED);
    }
    return err;
}

// ============================================================================
// Time messages
// ============================================================================

namespace {

/// The tag a client/time frame is known by (see time_frame_tag_).
uint32_t time_frame_tag(int64_t client_transmitted) {
    return static_cast<uint32_t>(client_transmitted);
}

}  // namespace

int64_t SendspinConnection::send_time_message() {
    if (!this->is_connected()) {
        return 0;
    }

    int64_t now = platform_time_us();
    // Tag 0 is reserved for "no frame in flight" (see time_frame_tag_).
    if (time_frame_tag(now) == 0) {
        ++now;
    }
    char buf[TIME_MESSAGE_BUF_SIZE];
    const size_t len = format_client_time_message(buf, sizeof(buf), now);
    if (len == 0) {
        return 0;
    }
    this->time_frame_sent_us_.store(time_frame_tag(now), std::memory_order_release);
    this->time_frame_tag_ = time_frame_tag(now);

    // No tag check: a connection's time frames reach the socket in send order, so a hook left
    // over from an earlier frame stores a time no later than the current frame's write. Capturing
    // only this keeps the closure in std::function's inline storage.
    const NoiseTransport::FrameWriteHook before_write = [this]() {
        this->time_frame_sent_us_.store(time_frame_tag(platform_time_us()),
                                        std::memory_order_release);
    };
    if (this->settle_noise_send(this->noise_transport_.send_json(buf, len, before_write)) !=
        SsErr::OK) {
        return 0;
    }
    return now;
}

std::optional<int64_t> SendspinConnection::claim_time_frame(int64_t client_transmitted) {
    const uint32_t tag = time_frame_tag(client_transmitted);
    if (tag == 0 || tag != this->time_frame_tag_) {
        return std::nullopt;
    }
    this->time_frame_tag_ = 0;
    // The frame in flight is this one, so the write time is its seed or what its write hook (or
    // an earlier frame's, see send_time_message()) stored since.
    const uint32_t sent = this->time_frame_sent_us_.load(std::memory_order_acquire);
    // Wrapping subtraction, read as signed: a leftover hook that sampled the clock before the seed
    // reads as negative, and the frame then counts as written at the time it carries.
    const auto delay = static_cast<int32_t>(sent - tag);
    return client_transmitted + std::max<int32_t>(delay, 0);
}

// ============================================================================
// Noise transport
// ============================================================================

void SendspinConnection::init_noise_handshake(const Identity& identity,
                                              const RecordStore& record_store,
                                              const std::string& suite_name) {
    this->noise_handshake_ =
        std::make_unique<NoiseHandshake>(identity, record_store, suite_name, *this->json_arena_);
    // Retain for re-handshake: these pointers outlive connections (owned by the
    // SendspinClient that constructed the manager which called this).
    this->noise_identity_ = &identity;
    this->noise_record_store_ = &record_store;
    this->noise_suite_name_ = suite_name;
}

void SendspinConnection::send_noise_client_init() {
    if (!this->noise_handshake_) {
        return;
    }
    std::string client_init = this->noise_handshake_->build_client_init();
    if (!client_init.empty()) {
        this->send_text_message(client_init);
    }
}

void SendspinConnection::handle_noise_handshake_text(const std::string& text) {
    if (!this->noise_handshake_) {
        return;
    }

    auto send_fn = [this](const std::string& msg) -> bool {
        auto err = this->send_text_message(msg);
        return err == SsErr::OK;
    };

    HandshakeFrameResult result = this->noise_handshake_->on_text_frame(text, send_fn);

    if (result == HandshakeFrameResult::ABORT) {
        const std::string& server_error = this->noise_handshake_->server_error_reason();
        if (server_error.empty()) {
            SS_LOGW(TAG, "Noise handshake aborted; closing connection");
        } else {
            SS_LOGW(TAG,
                    "Noise handshake aborted by server/error (reason='%s'); closing connection",
                    server_error.c_str());
        }
        // connection.md "Failure Handling": a handshake-phase failure closes the WebSocket
        // without sending any application-level message.
        this->noise_handshake_.reset();
        this->close_silently(SendspinGoodbyeReason::UNAUTHORIZED);
        return;
    }

    if (result == HandshakeFrameResult::COMPLETE) {
        auto outcome = this->noise_handshake_->take_result();
        if (!outcome.has_value()) {
            SS_LOGE(TAG, "Noise handshake: COMPLETE but no result");
            this->noise_handshake_.reset();
            return;
        }
        // Record the server's identity (public key) and the PSK category/psk_id that admitted
        // the connection, resolved by the handshake.
        this->set_noise_handshake_result(outcome->server_id, outcome->resolved_psk.category,
                                         outcome->resolved_psk.psk_id);
        // pairing.md "Pairing index": a fresh handshake starts a fresh count for the
        // pairing_index / CPace-sid counter.
        this->reset_pairing_index();
        // Install the cipher session; send_app_json() routes encrypted from here on.
        this->noise_transport_.activate(std::move(outcome->session));
        this->noise_handshake_.reset();
        this->noise_handshake_complete_ = true;
        SS_LOGI(TAG, "Noise transport active (server_id=%s, psk_category=%d)",
                this->server_information_.server_id.c_str(),
                static_cast<int>(this->get_psk_category()));
    }

    // NEED_MORE, or COMPLETE handled above: nothing else to do until the next frame.
}

bool SendspinConnection::handle_noise_rehandshake(const std::vector<uint8_t>& msg1_bytes) {
    // Runs on the protocol task (from SendspinClient::process_json_message() for a decrypted
    // "noise/handshake" message, itself only reachable post-COMPLETE, so this always runs on
    // the same thread as the decrypt path, sequential with it and never concurrent).
    if (!this->noise_transport_.is_active()) {
        SS_LOGE(TAG, "handle_noise_rehandshake: no active Noise transport");
        return false;
    }
    if (this->noise_identity_ == nullptr || this->noise_record_store_ == nullptr ||
        this->noise_suite_name_.empty()) {
        SS_LOGE(TAG, "handle_noise_rehandshake: missing identity/record_store/suite; "
                     "init_noise_handshake() was not called");
        return false;
    }

    // Restart the re-proving watchdog (ConnectionManager's re-prove scan): the connection is
    // once again awaiting its first server/activate, under the new keys.
    this->set_provisional_time_us(platform_time_us());

    // Suppress app-level sends (client/state, client/time) for the duration of the
    // re-handshake. Every send gates on first_activate_received(), so clearing it here
    // stops publish_client_state() and the time burst until the new server/activate arrives
    // after the session swap.
    this->first_activate_received_ = false;

    // Clear the pairing-in-progress flag: the re-handshake is the server's signal that
    // pairing finalized and it is rekeying onto the new long-term PSK. Clearing it before the
    // new server/activate arrives is what makes the manager read that activate as a fresh one
    // rather than a re-entry into the attempt, and discard any pairing message still in flight
    // as stale.
    this->pairing_in_progress_ = false;

    // Run the deferred-PSK-binding msg1 read with prologue = the prior handshake hash h.
    auto prior_h = this->noise_transport_.handshake_hash();
    if (!prior_h.has_value()) {
        SS_LOGE(TAG, "handle_noise_rehandshake: no handshake hash available");
        return false;
    }
    const std::string current_server_id = this->server_information_.server_id;

    auto result = run_rehandshake_msg1(msg1_bytes, current_server_id, *this->noise_identity_,
                                       *this->noise_record_store_, this->noise_suite_name_,
                                       prior_h.value(), *this->json_arena_);
    if (!result.has_value()) {
        SS_LOGW(TAG, "handle_noise_rehandshake: re-handshake failed; closing connection");
        return false;
    }

    // Commit: encrypt msg2 under the OLD session and send it, then swap to the new session.
    SsErr err =
        this->noise_transport_.send_msg2_and_swap(result->msg2_text, std::move(result->session));
    if (err != SsErr::OK) {
        SS_LOGE(TAG, "handle_noise_rehandshake: failed to send msg2 / swap session");
        return false;
    }

    // Update PSK metadata from the re-handshake result. server_id is unchanged (same server).
    this->psk_category_ = result->resolved_psk.category;
    this->psk_id_ = result->resolved_psk.psk_id;

    // pairing.md "Pairing index": a re-handshake starts a fresh count for the pairing_index /
    // CPace-sid counter, same as an initial handshake.
    this->reset_pairing_index();

    // connection.md "Re-handshake": neither hello is re-sent, so the hello state carries over
    // untouched; the server's first message under the new keys is server/activate, which
    // first_activate_received_ (cleared above) now waits on.
    SS_LOGI(TAG,
            "Noise re-handshake complete: server_id=%s psk_category=%d; awaiting server/activate",
            current_server_id.c_str(), static_cast<int>(this->get_psk_category()));
    return true;
}

SendspinConnection::InboundDispatch SendspinConnection::classify_complete_noise_message(
    InboundMessage& complete) {
    // data[0] is the message type; fragment types never reach here.
    if (complete.data[0] == MSG_TYPE_JSON_BODY) {
        // Type 0: JSON control body, routed without the type byte. A frame carrying only
        // the type byte (no body) is a malformed/empty JSON message; drop it.
        if (complete.len < 2) {
            SS_LOGW(TAG, "empty JSON body after Noise decrypt; dropping");
            return InboundDispatch::NONE;
        }
        ++complete.data;
        --complete.len;
        return InboundDispatch::JSON;
    }

    // All other types: route as binary role message (full type-prefixed plaintext).
    return InboundDispatch::BINARY;
}

// ============================================================================
// Inbound messages: protocol task side
// ============================================================================

SS_HOT SendspinConnection::InboundDispatch SendspinConnection::process_inbound_message(
    InboundMessage& message, InboundMessage& complete) {
    // A connection closed or dropped stops dispatching at once, including frames already
    // received before the close was decided.
    if (this->inbound_gate_.is_detached()) {
        return InboundDispatch::NONE;
    }
    // Every application frame is BINARY ciphertext: decrypt, reassemble, and read the message
    // type from the leading plaintext byte. Cleartext TEXT frames carry only the pre-transport
    // handshake exchange (server/init, noise/handshake), which the handshake driver consumes.
    //
    // A WS-upgraded connection with no driver yet (outbound between connect and
    // init_noise_handshake()) never hears anything legitimate, so its frames are dropped.
    const bool noise_active = this->noise_handshake_complete_;
    const bool noise_pending = !noise_active && this->noise_handshake_;

    if (message.kind == InboundKind::TEXT) {
        if (noise_pending) {
            // Feed the handshake driver; it handles server/init and noise/handshake frames.
            this->handle_noise_handshake_text(
                std::string(reinterpret_cast<const char*>(message.data), message.len));
            return InboundDispatch::NONE;
        }
        if (noise_active) {
            // connection.md "Failure Handling": a cleartext message after the switch to transport
            // mode is a silent failure.
            SS_LOGW(TAG, "TEXT frame in transport mode; closing connection");
            this->close_silently(SendspinGoodbyeReason::UNAUTHORIZED);
            return InboundDispatch::NONE;
        }
        SS_LOGW(TAG, "TEXT frame before the Noise handshake started; dropping");
        return InboundDispatch::NONE;
    }

    if (!noise_active) {
        if (noise_pending) {
            // A handshake driver is installed but the transport is not up, so this frame is
            // unauthenticated application data. It must not reach the role dispatch: that would
            // let any peer that merely completed the WebSocket upgrade inject audio/artwork data
            // with the Noise/PSK/admission chain bypassed. Treated as a handshake-phase failure
            // per connection.md "Failure Handling": close without any application-level message.
            SS_LOGW(TAG, "Binary frame before the Noise handshake completed; closing connection");
            this->close_silently(SendspinGoodbyeReason::UNAUTHORIZED);
            return InboundDispatch::NONE;
        }
        SS_LOGW(TAG, "Binary frame before the Noise handshake started; dropping");
        return InboundDispatch::NONE;
    }

    // Decrypt in place: the message holds the full ciphertext (plaintext + AEAD_TAG_SIZE of tag),
    // in the ring item it was received into when it has one.
    const size_t pt_len = this->noise_transport_.decrypt_in_place(message.data, message.len);
    if (pt_len == 0) {
        // Spec Failure Handling: an AEAD failure once in transport mode closes the WebSocket
        // silently. It is also unrecoverable if left open: the underlying Noise decrypt never
        // advances the receive-direction nonce counter on an auth failure, so every later frame
        // on this connection would fail authentication forever too.
        SS_LOGW(TAG, "Noise AEAD failure in transport mode; closing connection");
        this->close_silently(SendspinGoodbyeReason::UNAUTHORIZED);
        return InboundDispatch::NONE;
    }
    // Route through the fragment state machine; hand any completed message to the caller.
    const NoiseTransport::CompleteMessage reassembled = this->noise_transport_.accept_plaintext(
        message.data, pt_len, this->inbound_gate_.is_admitted());
    if (reassembled.malformed) {
        // messaging.md "Malformed sequences" is a protocol error the receiver MUST close the
        // connection for; NoiseTransport::CompleteMessage::malformed enumerates the sequences
        // that set it.
        SS_LOGW(TAG, "Malformed fragment sequence; closing connection");
        this->close_silently(SendspinGoodbyeReason::UNAUTHORIZED);
        return InboundDispatch::NONE;
    }
    if (reassembled.data == nullptr) {
        return InboundDispatch::NONE;
    }
    if (reassembled.data == message.data) {
        // A single-frame message: the plaintext in place, in its ring item.
        complete = message;
        complete.len = reassembled.len;
        message.item = nullptr;
    } else {
        // A reassembled message, in the Noise reassembly buffer.
        complete = InboundMessage{};
        complete.data = reassembled.data;
        complete.len = reassembled.len;
        complete.receive_time_us = message.receive_time_us;
        complete.kind = InboundKind::BINARY;
    }
    return classify_complete_noise_message(complete);
}

bool SendspinConnection::pending_message(InboundMessage& out) {
    // The transport writes nothing while a message is pending, so the in-flight count only falls
    // until the ring pass has taken every item written before it.
    if (!this->inbound_gate_.has_pending_message() || this->inbound_gate_.in_flight() != 0) {
        return false;
    }
    // The acquire load above orders these reads after the transport's writes before its publish.
    out = InboundMessage{};
    out.data = this->fallback_buf_.data();
    out.len = this->fallback_len_;
    out.receive_time_us = this->fallback_receive_time_us_;
    out.kind = this->fallback_kind_;
    return true;
}

// ============================================================================
// Inbound messages: transport side
// ============================================================================

void SendspinConnection::notify_transport_closed() {
    this->inbound_gate_.mark_transport_closed();
    if (this->inbound_task_ != nullptr) {
        this->inbound_task_->wake();
    }
}

void SendspinConnection::fail_inbound() {
    this->detach_inbound();
    this->close_transport_now();
    if (this->inbound_task_ != nullptr) {
        this->inbound_task_->wake();
    }
}

SS_HOT SendspinConnection::InboundTarget SendspinConnection::begin_inbound_message(
    size_t len, bool is_text, int64_t receive_time_us) {
    if (this->fragment_assembly_open_) {
        // RFC 6455 section 5.4: the fragments of one message are not interleaved with another
        // data message. Failing here also keeps the assembly's continuations off a fallback
        // buffer this message would publish.
        SS_LOGW(TAG, "Data frame inside a fragmented message; closing");
        this->fail_inbound();
        return {nullptr, InboundRoute::CLOSE};
    }
    const InboundTarget target =
        this->route_inbound_message(len, is_text ? InboundKind::TEXT : InboundKind::BINARY,
                                    static_cast<uint32_t>(receive_time_us));
    if (target.route == InboundRoute::DROP) {
        // A dropped message is read and discarded without holding anything, so its start
        // stands in for its completion as proof the peer is alive.
        this->note_message_completed();
    }
    // An admitted connection receives into the ring from here on and nothing is pending once a
    // ring write began, so the fallback buffer goes back to the heap.
    if (this->inbound_item_ != nullptr && this->fallback_buf_.data() != nullptr) {
        this->fallback_buf_.reset();
    }
    return target;
}

SendspinConnection::InboundTarget SendspinConnection::route_inbound_message(size_t len,
                                                                            InboundKind kind,
                                                                            uint32_t stamp) {
    if (this->inbound_ring_ == nullptr || this->inbound_gate_.is_detached()) {
        return {nullptr, InboundRoute::DROP};
    }

    if (!this->inbound_gate_.is_admitted()) {
        // An empty frame can never be a valid handshake or JSON message.
        if (len == 0) {
            return {nullptr, InboundRoute::DROP};
        }
        if (len <= InboundGate::PRE_ADMISSION_MESSAGE_BYTES) {
            return this->route_to_fallback(len, kind, stamp, /*admitted=*/false);
        }
        // The message still pending may be the server/activate that admits this connection, so
        // the cap is judged once the protocol task has consumed it.
        const InboundRoute waited = this->wait_until_writable();
        if (waited != InboundRoute::RECEIVE) {
            return {nullptr, waited};
        }
        if (!this->inbound_gate_.is_admitted()) {
            SS_LOGW(TAG, "Pre-admission message of %zu bytes exceeds the %zu-byte cap; closing",
                    len, InboundGate::PRE_ADMISSION_MESSAGE_BYTES);
            this->fail_inbound();
            return {nullptr, InboundRoute::CLOSE};
        }
    }

    if (len > INBOUND_MAX_MESSAGE_BYTES) {
        SS_LOGW(TAG, "Message of %zu bytes exceeds one Noise frame (%zu); closing", len,
                INBOUND_MAX_MESSAGE_BYTES);
        this->fail_inbound();
        return {nullptr, InboundRoute::CLOSE};
    }
    // Longer than any message the enabled roles need in a ring item, which the ring is sized for
    // (InboundRing::max_message_bytes(); without the artwork role or a large player buffer that
    // is a JSON message): delivered through the fallback buffer, in order with this connection's
    // ring items (see InboundGate). The rare path; the buffer is allocated for it and released at
    // the next ring write.
    if (len > this->inbound_ring_->max_message_bytes()) {
        return this->route_to_fallback(len, kind, stamp, /*admitted=*/true);
    }
    // A pre-admission message still pending from before the admission holds every later write
    // back, so the protocol task sees this connection's messages in order.
    while (!this->inbound_gate_.begin_ring_write()) {
        const InboundRoute waited = this->wait_until_writable();
        if (waited != InboundRoute::RECEIVE) {
            return {nullptr, waited};
        }
    }
    void* item = this->inbound_ring_->acquire(len, INBOUND_ACQUIRE_TIMEOUT_MS);
    if (item == nullptr) {
        this->inbound_gate_.abandon_ring_write();
        // A frame the transport never decrypts leaves the Noise receive nonce behind, so the
        // connection cannot continue past it and is closed here rather than at its next frame.
        // Reclamation is in ring order, so with a role's consumer thread holding items the
        // space behind the oldest of them is what ran out (see derive_inbound_ring_bytes()):
        // said so, to tell that limit from a stalled protocol task (docs/internals.md "The
        // Inbound Ring").
        const size_t held = this->inbound_ring_->quota(InboundHolder::PLAYER).outstanding() +
                            this->inbound_ring_->quota(InboundHolder::VISUALIZER).outstanding() +
                            this->inbound_ring_->quota(InboundHolder::ARTWORK).outstanding();
        if (held > 0) {
            SS_LOGW(TAG,
                    "No inbound ring space for a %zu-byte message within %u ms: ring pinned "
                    "behind held items (%zu bytes held); closing the connection",
                    len, static_cast<unsigned>(INBOUND_ACQUIRE_TIMEOUT_MS), held);
        } else {
            SS_LOGW(TAG,
                    "No inbound ring space for a %zu-byte message within %u ms; closing the "
                    "connection",
                    len, static_cast<unsigned>(INBOUND_ACQUIRE_TIMEOUT_MS));
        }
        this->fail_inbound();
        return {nullptr, InboundRoute::CLOSE};
    }
    InboundItemHeader* header = inbound_item_header(item);
    header->connection_id = static_cast<uint32_t>(this->instance_id);
    header->receive_time_us = stamp;
    header->kind = kind;
    this->inbound_item_ = item;
    return {inbound_item_bytes(item), InboundRoute::RECEIVE};
}

SendspinConnection::InboundTarget SendspinConnection::route_to_fallback(size_t len,
                                                                        InboundKind kind,
                                                                        uint32_t stamp,
                                                                        bool admitted) {
    if (admitted) {
        // An admitted connection's message waits for the buffer no longer than one waits for
        // ring space, and then closes the connection, as a full ring does: a frame the transport
        // never decrypts leaves the Noise receive nonce behind, so the connection cannot continue
        // past it and is closed here rather than at its next frame. A detached connection's
        // message is dropped, its connection already on its way out.
        if (!this->inbound_gate_.wait_until_writable(INBOUND_ACQUIRE_TIMEOUT_MS)) {
            if (this->inbound_gate_.is_detached()) {
                return {nullptr, InboundRoute::DROP};
            }
            SS_LOGW(TAG,
                    "Fallback buffer still holds the previous message after %u ms; closing the "
                    "connection for a %zu-byte message",
                    static_cast<unsigned>(INBOUND_ACQUIRE_TIMEOUT_MS), len);
            this->fail_inbound();
            return {nullptr, InboundRoute::CLOSE};
        }
    } else {
        const InboundRoute waited = this->wait_until_writable();
        if (waited != InboundRoute::RECEIVE) {
            return {nullptr, waited};
        }
    }
    if (this->fallback_buf_.size() < len &&
        !this->fallback_buf_.allocate(len, this->fallback_location_)) {
        SS_LOGE(TAG, "Failed to allocate %zu bytes for a fallback message; closing", len);
        this->fail_inbound();
        return {nullptr, InboundRoute::CLOSE};
    }
    this->fallback_len_ = len;
    this->fallback_kind_ = kind;
    this->fallback_receive_time_us_ = stamp;
    this->inbound_to_fallback_ = true;
    return {this->fallback_buf_.data(), InboundRoute::RECEIVE};
}

SendspinConnection::InboundRoute SendspinConnection::wait_until_writable() {
    if (this->inbound_gate_.wait_until_writable(InboundGate::WRITABLE_WAIT_MS)) {
        return InboundRoute::RECEIVE;
    }
    if (this->inbound_gate_.is_detached()) {
        return InboundRoute::DROP;
    }
    SS_LOGW(TAG, "Protocol task took no pending message in %u ms; closing",
            static_cast<unsigned>(InboundGate::WRITABLE_WAIT_MS));
    this->fail_inbound();
    return InboundRoute::CLOSE;
}

void SendspinConnection::note_message_completed() {
    this->last_receive_time_us_.store(static_cast<uint32_t>(platform_time_us()),
                                      std::memory_order_relaxed);
}

void SendspinConnection::end_inbound_message(bool received) {
    if (received) {
        this->note_message_completed();
    }
    if (this->inbound_item_ != nullptr) {
        void* item = std::exchange(this->inbound_item_, nullptr);
        if (received) {
            this->inbound_ring_->complete(item);
        } else {
            // FreeRTOS cannot cancel an acquire: complete it as DISCARD, which take() returns
            // without handing out, and uncount it.
            inbound_item_header(item)->kind = InboundKind::DISCARD;
            this->inbound_ring_->complete(item);
            this->inbound_gate_.abandon_ring_write();
        }
    } else if (this->inbound_to_fallback_) {
        this->inbound_to_fallback_ = false;
        if (!received || !this->inbound_gate_.publish_pending_message()) {
            return;
        }
    } else {
        return;
    }
    this->inbound_task_->wake();
}

void SendspinConnection::abandon_inbound_message() {
    if (this->inbound_item_ != nullptr) {
        this->end_inbound_message(false);
    }
    this->inbound_to_fallback_ = false;
    this->fragment_dropping_ = false;
    this->fragment_assembly_open_ = false;
}

SendspinConnection::InboundTarget SendspinConnection::begin_inbound_fragment(
    size_t len, bool first, bool is_text, int64_t receive_time_us) {
    // The rare path (see the declaration): a multi-frame WebSocket message is assembled in the
    // fallback buffer whatever the admission state, and routed when its last bytes arrive.
    if (first && this->fragment_assembly_open_) {
        // RFC 6455 section 5.4: a fragmented message ends with its final continuation frame
        // before another data message starts.
        SS_LOGW(TAG, "New fragmented message inside an open one; closing");
        this->fail_inbound();
        return {nullptr, InboundRoute::CLOSE};
    }
    if (first) {
        this->fragment_assembly_open_ = true;
        this->fragment_dropping_ = false;
        if (this->inbound_ring_ == nullptr || this->inbound_gate_.is_detached()) {
            this->fragment_dropping_ = true;
        } else {
            const InboundRoute waited = this->wait_until_writable();
            if (waited == InboundRoute::CLOSE) {
                return {nullptr, InboundRoute::CLOSE};
            }
            this->fragment_dropping_ = waited == InboundRoute::DROP;
        }
        if (!this->fragment_dropping_) {
            // The buffer is the transport's from here: nothing is pending. A dropped message
            // leaves the fields alone, since the protocol task may still be reading a detached
            // connection's pending message through them.
            this->fallback_len_ = 0;
            this->fallback_kind_ = is_text ? InboundKind::TEXT : InboundKind::BINARY;
            this->fallback_receive_time_us_ = static_cast<uint32_t>(receive_time_us);
        }
    } else if (!this->fragment_assembly_open_) {
        // RFC 6455 section 5.4: a continuation frame continues a fragmented message, so one with
        // none open is a protocol error. It never reaches the fallback buffer, which may hold a
        // pending message the protocol task is reading.
        SS_LOGW(TAG, "Continuation frame with no fragmented message open; closing");
        this->fail_inbound();
        return {nullptr, InboundRoute::CLOSE};
    }
    if (this->fragment_dropping_ || this->inbound_gate_.is_detached()) {
        this->fragment_dropping_ = true;
        return {nullptr, InboundRoute::DROP};
    }
    const size_t cap = this->inbound_gate_.is_admitted() ? INBOUND_MAX_MESSAGE_BYTES
                                                         : InboundGate::PRE_ADMISSION_MESSAGE_BYTES;
    if (len > cap - this->fallback_len_) {
        SS_LOGW(TAG, "Multi-frame message exceeds %zu bytes; closing", cap);
        this->fail_inbound();
        return {nullptr, InboundRoute::CLOSE};
    }
    const size_t needed = this->fallback_len_ + len;
    if (this->fallback_buf_.size() < needed) {
        const bool grown = this->fallback_buf_.data() == nullptr
                               ? this->fallback_buf_.allocate(needed, this->fallback_location_)
                               : this->fallback_buf_.realloc(needed);
        if (!grown) {
            SS_LOGE(TAG, "Failed to grow the fallback buffer to %zu bytes; closing", needed);
            this->fail_inbound();
            return {nullptr, InboundRoute::CLOSE};
        }
    }
    return {this->fallback_buf_.data() + this->fallback_len_, InboundRoute::RECEIVE};
}

void SendspinConnection::end_inbound_fragment(size_t len, bool last) {
    if (last) {
        this->fragment_assembly_open_ = false;
        this->note_message_completed();
    }
    if (this->fragment_dropping_) {
        if (last) {
            this->fragment_dropping_ = false;
        }
        return;
    }
    this->fallback_len_ += len;
    if (!last) {
        return;
    }
    if (!this->inbound_gate_.is_admitted()) {
        this->inbound_to_fallback_ = true;
        this->end_inbound_message(true);
        return;
    }
    // Admitted: copy the assembled message into a ring item, the one copy this path costs over
    // a single-frame message, and release the buffer.
    const size_t total = this->fallback_len_;
    const InboundTarget target =
        this->route_inbound_message(total, this->fallback_kind_, this->fallback_receive_time_us_);
    if (target.route != InboundRoute::RECEIVE) {
        return;
    }
    if (this->inbound_item_ == nullptr) {
        // Routed to the fallback buffer it is already in: longer than the ring takes, or the
        // admission flag cleared in between.
        this->end_inbound_message(true);
        return;
    }
    std::memcpy(target.data, this->fallback_buf_.data(), total);
    this->fallback_buf_.reset();
    this->fallback_len_ = 0;
    this->end_inbound_message(true);
}

// ============================================================================
// Pairing finalize watchdog
// ============================================================================

void SendspinConnection::note_pairing_finalize_ack() {
    // Restart the re-prove window, as handle_noise_rehandshake() does: the rekey that follows
    // the ack has REPROVE_TIMEOUT_US to deliver its server/activate.
    this->set_provisional_time_us(platform_time_us());
    this->first_activate_received_ = false;
    // Mark the activities snapshot stale: activities_ still reads [PAIRING] until the post-rekey
    // activate lands, and admission must not keep shielding this as an in-flight pairing.
    this->pairing_finalized_ = true;
}

}  // namespace sendspin
