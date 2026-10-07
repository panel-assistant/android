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

/// @file connection_manager_pairing.cpp
/// @brief ConnectionManager's pairing state machines: entering pairing, the Pairing PSK and
/// pairing-code flows, pair/abort, the pairing window, and server/unpair

#include "connection.h"
#include "connection_manager.h"
#include "crypto/constants.h"
#include "crypto/cpace.h"
#include "crypto/pairing_code.h"
#include "crypto/pairing_token.h"
#include "crypto/psk_wrap.h"
#include "platform/crypto.h"
#include "platform/logging.h"
#include "platform/time.h"
#include "protocol_messages.h"
#include "record_store.h"
#include "sendspin/types.h"

#include <array>
#include <cassert>
#include <memory>
#include <optional>
#include <string>
#include <utility>
#include <vector>

namespace sendspin {

static const char* const TAG = "sendspin.conn_mgr";

/// @brief Overall deadline for a pairing attempt in any flow, bounding it from its first message
/// (pairing.md "Entering and leaving pairing" recommends 2 minutes). In the pairing-code flows it
/// spans every round: a retry keeps the running deadline rather than re-arming it. On expiry the
/// attempt is aborted with reason attempt_timeout and any emitted code is withdrawn.
static constexpr double PAIRING_ATTEMPT_TIMEOUT_S = 120.0;

/// @brief Attempt deadline in microseconds (derived from PAIRING_ATTEMPT_TIMEOUT_S).
static constexpr int64_t PAIRING_ATTEMPT_TIMEOUT_US = seconds_to_us(PAIRING_ATTEMPT_TIMEOUT_S);

/// @brief Lifetime of an open pairing window, measured from opening and not paused during an
/// attempt (pairing.md "Pairing Window" recommends 5 minutes). On expiry the window closes
/// silently.
static constexpr double WINDOW_LIFETIME_S = 300.0;

/// @brief Window lifetime in microseconds (derived from WINDOW_LIFETIME_S).
static constexpr int64_t WINDOW_LIFETIME_US = seconds_to_us(WINDOW_LIFETIME_S);

/// @brief Attempts under one pairing window whose server_kc verification may fail before the
/// window closes (pairing.md "Pairing Window"): the window is the operator's consent to a bounded
/// run of guesses.
static constexpr uint32_t WINDOW_FAILED_ATTEMPT_LIMIT = 5;

/// @brief Rounds a dynamic pairing code may run since the last verified server_kc before the
/// client stops retrying (pairing.md "Rounds"). Reaching it aborts the attempt with
/// pairing_code_mismatch and holds further attempts behind a deliberate operator gesture, which
/// bounds an online guessing attacker to this many tries per gesture.
static constexpr uint32_t PAIRING_ROUND_LIMIT = 20;

/// @brief CPace sid label (pairing.md "PAKE"):
/// sid = LABEL || h || pairing_index || round, each counter a big-endian uint32.
static constexpr char PAKE_SID_LABEL[] = "sendspin-pair-pake-v1";

/// @brief CPace ADa/ADb (pairing.md "PAKE"): distinct associated data per side fixes a
/// reflected-MAC issue. The server is CPace role A, the client is role B.
static constexpr char PAKE_AD_SERVER[] = "server";  // ADa
static constexpr char PAKE_AD_CLIENT[] = "client";  // ADb

namespace {

/// @brief A CPace ISK held for the length of one handler and wiped when it goes out of scope.
///
/// CPace::isk() answers by value, so the caller owns a copy of the 64-byte secret that
/// CPace::~CPace never sees. K_wrap is SHA-256(label || sid || ISK) and the sid is not secret,
/// so a copy left in a dead stack frame is worth both wrap keys. The scope guard covers the
/// early returns between the two wraps, as the surrounding code already does for K_wrap itself
/// and for every PSK-bearing struct.
class ScopedIsk {
public:
    /// @brief Takes ownership of an ISK and wipes the caller's copy
    /// @param isk Taken by value, so the caller's temporary does not outlive this copy.
    explicit ScopedIsk(std::optional<std::array<uint8_t, CPACE_ISK_SIZE>> isk) : value_(isk) {
        if (isk.has_value()) {
            // The bytes were copied, so the argument still holds them.
            secure_zero_container(*isk);
        }
    }

    ScopedIsk(const ScopedIsk&) = delete;
    ScopedIsk& operator=(const ScopedIsk&) = delete;

    ~ScopedIsk() {
        if (this->value_.has_value()) {
            secure_zero_container(*this->value_);
        }
    }

    /// @brief Whether an ISK was handed over
    /// @return true when a value is held.
    [[nodiscard]] bool has_value() const {
        return this->value_.has_value();
    }

    /// @brief The held ISK; only valid when has_value()
    /// @return Reference to the held bytes, valid until this object goes out of scope.
    [[nodiscard]] const std::array<uint8_t, CPACE_ISK_SIZE>& value() const {
        assert(this->value_.has_value());
        // NOLINTNEXTLINE(bugprone-unchecked-optional-access): callers check has_value() first
        return *this->value_;
    }

private:
    std::optional<std::array<uint8_t, CPACE_ISK_SIZE>> value_;
};

}  // namespace

/// @brief Append `value` to `out` as a big-endian uint32, the encoding pairing.md "PAKE" gives
/// both of the sid's counters.
static void append_be32(std::vector<uint8_t>& out, uint32_t value) {
    out.push_back(static_cast<uint8_t>((value >> 24) & 0xFF));
    out.push_back(static_cast<uint8_t>((value >> 16) & 0xFF));
    out.push_back(static_cast<uint8_t>((value >> 8) & 0xFF));
    out.push_back(static_cast<uint8_t>(value & 0xFF));
}

/// @brief Build the CPace sid for one round of a pairing attempt (pairing.md "PAKE"):
/// LABEL || h (32 bytes) || pairing_index || round, the counters big-endian uint32.
/// Binding the round in means every round of an attempt runs its own CPace transcript, so a
/// retry cannot replay the previous round's shares or reuse its wrap keys.
/// @param handshake_hash The Noise handshake hash the attempt is bound to.
/// @param pairing_index The pairing_index captured for this attempt
///        (SendspinConnection::PairingSession::pairing_index).
/// @param round The round number within the attempt, 1 for the first.
static std::vector<uint8_t> build_pake_sid(const std::array<uint8_t, 32>& handshake_hash,
                                           uint32_t pairing_index, uint32_t round) {
    std::vector<uint8_t> sid;
    sid.reserve(sizeof(PAKE_SID_LABEL) - 1 + 32 + 4 + 4);
    sid.insert(sid.end(), PAKE_SID_LABEL, PAKE_SID_LABEL + sizeof(PAKE_SID_LABEL) - 1);
    sid.insert(sid.end(), handshake_hash.begin(), handshake_hash.end());
    append_be32(sid, pairing_index);
    append_be32(sid, round);
    return sid;
}

/// @brief The client's own CPace associated data (ADb = "client"), as raw bytes.
static std::vector<uint8_t> pake_ad_client() {
    return std::vector<uint8_t>(PAKE_AD_CLIENT, PAKE_AD_CLIENT + sizeof(PAKE_AD_CLIENT) - 1);
}

/// @brief The peer's (server's) CPace associated data (ADa = "server"), as raw bytes.
static std::vector<uint8_t> pake_ad_server() {
    return std::vector<uint8_t>(PAKE_AD_SERVER, PAKE_AD_SERVER + sizeof(PAKE_AD_SERVER) - 1);
}

PairingUiSnapshot snapshot_pairing_ui(SendspinConnection* conn) {
    return {conn->pairing_session().code_emitted, conn->pairing_session().window_shown};
}

// ============================================================================
// Pairing handlers
// ============================================================================

void ConnectionManager::handle_enter_pairing(SendspinConnection* conn) {
    // conn is an admitted connection whose activate selects pairing: one that just won admission
    // (see promote_or_arbitrate_nursery_entry()) or one already admitted (on_server_activate()).

    // A new attempt inherits nothing from an earlier one. on_handshake_complete() has already
    // reset the session on most paths here, but an in-band re-handshake (connection.md
    // "Re-handshake") clears only pairing_in_progress_, so a pairing-only activate after one still
    // finds the earlier attempt's session and any prompt it showed.
    const PairingUiSnapshot ui = snapshot_pairing_ui(conn);
    conn->clear_pairing_state();
    this->client_->note_pairing_ui_dismissals(ui);

    // An attempt is in flight from here until it finalizes or aborts: pairing messages are only
    // routed while it is (pairing.md "Entering and leaving pairing"). Playback is untouched.
    conn->set_pairing_in_progress(true);
    // Queued before any other note of the attempt, so the queue-order drain delivers it ahead of
    // the attempt's prompt or failure.
    this->client_->note_pairing_started(conn->get_server_id());

    // The pairing server/activate counter (pairing.md "Pairing index") was already bumped at the
    // point this activate was received (on_server_activate()). Do not bump again here: this
    // handler can also be reached after the activate was applied (the "subsequent activate
    // transitions into pairing" branch applies the activate first, then calls this), so bumping
    // here would double-count or use a stale value.
    // The current value is captured into the pairing session below for the code-based branches
    // (sent as pairing_index and reused for the CPace sid).
    const uint32_t pairing_index = conn->get_pairing_index();

    const std::string& server_id = conn->get_server_id();
    const auto& selected_method = conn->get_pairing_method();

    if (selected_method.has_value() &&
        (selected_method.value() == SendspinPairMethod::DYNAMIC_PAIRING_CODE ||
         selected_method.value() == SendspinPairMethod::STATIC_PAIRING_CODE)) {
        this->handle_enter_pairing_code(conn, pairing_index, server_id, selected_method.value());
        return;
    }

    this->handle_enter_pairing_psk(conn, pairing_index, server_id);
}

void ConnectionManager::handle_enter_pairing_code(SendspinConnection* conn, uint32_t pairing_index,
                                                  const std::string& server_id,
                                                  SendspinPairMethod selected_method) {
    const bool is_dynamic = selected_method == SendspinPairMethod::DYNAMIC_PAIRING_CODE;
    const auto& static_code = this->client_->config_.static_pairing_code;
    const auto& pairing_format = conn->get_pairing_format();

    // Defensive: the client should not have advertised static_pairing_code without a configured
    // code, nor dynamic_pairing_code without an emission format the activation could name.
    if (!is_dynamic && !static_code.has_value()) {
        SS_LOGE(TAG, "handle_enter_pairing: no static pairing code configured for server_id=%s",
                server_id.c_str());
        this->local_abort_pairing(conn, PairAbortReason::METHOD_NOT_SUPPORTED);
        return;
    }
    if (is_dynamic && !pairing_format.has_value()) {
        SS_LOGE(TAG, "handle_enter_pairing: no emission format selected for server_id=%s",
                server_id.c_str());
        this->local_abort_pairing(conn, PairAbortReason::METHOD_NOT_SUPPORTED);
        return;
    }

    // Capture the Noise handshake hash now, before any further I/O: a re-handshake would replace
    // it. If the hash is unavailable the PAKE sid and the code derivation cannot be computed, so
    // abort.
    auto hash_opt = conn->get_noise_handshake_hash();
    if (!hash_opt.has_value()) {
        SS_LOGE(TAG, "handle_enter_pairing: no handshake hash for server_id=%s; aborting",
                server_id.c_str());
        this->local_abort_pairing(conn, PairAbortReason::METHOD_NOT_SUPPORTED);
        return;
    }

    auto& ps = conn->pairing_session();
    ps.method = selected_method;
    ps.handshake_hash = hash_opt.value();
    ps.pairing_index = pairing_index;
    if (is_dynamic) {
        // Checked against the advertised formats when the activation was admitted.
        ps.format = pairing_format.value();
    } else {
        // CPace consumes the static code as PRS directly, so it is known here; a dynamic code
        // is only known once nonce_A arrives.
        // NOLINTNEXTLINE(bugprone-unchecked-optional-access): checked above for the static method
        ps.prs = pairing_code_digits_prs(static_code.value());
    }

    // Gesture gating: the static pairing code gates every attempt on an operator gesture
    // (pairing.md "Pairing Window"); the dynamic one runs ungated until the round limit stands,
    // which holds attempts back until the same deliberate operator action clears it
    // (pairing.md "Rounds"). Both wait the same way, sending client/pair-pending, which is what
    // the limit asks a held-back attempt to send.
    const bool gesture_gated = !is_dynamic || this->pairing_round_limit_reached();

    if (gesture_gated && !this->pairing_window_admits(conn)) {
        // No window open: report the pending gesture with client/pair-pending and wait.
        // pair-pending does not start the attempt or its timeout (the server applies its
        // own timeout and cancels via server/activate), so no attempt deadline is armed
        // here (attempt_deadline_us == 0 disables the timeout check in scan_admitted()).
        ps.step = SendspinConnection::PairingStep::AWAIT_PAIRING_WINDOW;
        ps.attempt_deadline_us = 0;
        SS_LOGI(TAG,
                "Sending client/pair-pending (%s, gesture-gated, no window open) for "
                "server_id=%s",
                to_cstr(ps.method), server_id.c_str());
        conn->send_app_json(
            format_client_pair_pending_message(ps.pairing_index, this->json_arena()));

        // Surface the pairing-window prompt to the operator, but only when the platform
        // implements the gesture UI (on_open_pairing_window's contract is that it fires only
        // when pairing_window_supported is true). A static_pairing_code attempt never gets here
        // without that flag (see offers_static_pairing_code()); a dynamic attempt held back by
        // the round limit does, and then has no way to proceed and waits for the server's own
        // timeout to cancel it.
        if (this->client_->config_.pairing_window_supported) {
            this->client_->note_open_pairing_window();
            ps.window_shown = true;
        } else {
            SS_LOGW(TAG,
                    "Gesture-gated %s attempt for server_id=%s but "
                    "pairing_window_supported=false: no operator prompt can be shown; "
                    "waiting for the server to cancel the attempt",
                    to_cstr(ps.method), server_id.c_str());
        }
        return;
    }

    // Not gated, or a standing window is already open: start the attempt immediately
    // (start_pairing_attempt binds an open window to this connection; it does not spend it).
    this->start_pairing_attempt(conn);
}

void ConnectionManager::handle_enter_pairing_psk(SendspinConnection* conn, uint32_t pairing_index,
                                                 const std::string& server_id) {
    // resolve_pairing_outcome mints the long-term PSK and the record that holds it. It cannot
    // fail: a pairing never fails for lack of record storage (pairing.md "Pairing Records"),
    // and room for the record is made where it is stored.
    auto outcome = this->client_->record_store_->resolve_pairing_outcome(server_id);

    // pairing.md "Pairing PSK Flow": after the pairing server/activate the client sends
    // client/pair-init followed immediately by client/pair-finalize, without waiting for a
    // server response. pair-init starts the attempt and carries the pairing index alone; the
    // PSK flow has no PAKE round and so no commit_B.
    SS_LOGI(TAG, "Sending client/pair-init (pairing_psk) for server_id=%s", server_id.c_str());
    conn->send_app_json(format_client_pair_init_message(pairing_index, this->json_arena()));
    conn->pairing_session().attempt_deadline_us = platform_time_us() + PAIRING_ATTEMPT_TIMEOUT_US;

    // Send client/pair-finalize with the long-term PSK (base64url-encoded, 43 chars).
    SS_LOGI(TAG, "Sending client/pair-finalize for server_id=%s", server_id.c_str());
    // Named local rather than a temporary so the serialized message, which carries the raw
    // base64 long-term PSK, can be wiped once it has been handed to the transport.
    std::string finalize_msg = format_client_pair_finalize_message(outcome.psk, this->json_arena());
    conn->send_app_json(finalize_msg);
    secure_zero(finalize_msg.data(), finalize_msg.size());

    // Hold the pending record: committed to the RecordStore by the server/pair-finalize handler
    // on ack.
    conn->set_pending_pairing_record(std::move(outcome.record));
}

void ConnectionManager::handle_pair_abort(SendspinConnection* conn, PairAbortReason reason) {
    // pair/abort received from the server during pairing.

    // A pair/abort that arrives after the receiver (us) has already ended the attempt (locally
    // aborted, or the server itself left pairing via a leftover server/activate) has no effect
    // (pairing.md "pair/abort"). is_pairing_in_progress() is cleared by clear_pairing_state() on
    // every path that ends an attempt, so it is the right proxy for "already ended" here.
    if (!conn->is_pairing_in_progress()) {
        SS_LOGI(TAG,
                "pair/abort (reason=%s) received for server_id=%s after the attempt already "
                "ended; ignoring (stale)",
                to_cstr(reason), conn->get_server_id().c_str());
        return;
    }

    SS_LOGW(TAG, "pair/abort received for server_id=%s reason=%s", conn->get_server_id().c_str(),
            to_cstr(reason));

    // Clean up pairing state. pairing.md "pair/abort": the sender closes the connection only for
    // reason concurrent_attempt, leaving it open on every other reason so the server can
    // re-activate pairing (or resume normal operation) on it. Mirrored here (the server, as
    // sender, is closing its side regardless; closing here just avoids waiting on the TCP
    // teardown). No wire pair/abort is sent: this one already arrived from the server.
    this->abort_pairing_attempt(
        conn, /*wire_abort_reason=*/std::nullopt,
        reason == PairAbortReason::CONCURRENT_ATTEMPT ? PairingDropAction::CLOSE_WITH_GOODBYE
                                                      : PairingDropAction::KEEP_OPEN,
        to_public_abort_reason(reason), SendspinGoodbyeReason::CONCURRENT_ATTEMPT);
}

void ConnectionManager::abort_pairing_attempt(SendspinConnection* conn,
                                              std::optional<PairAbortReason> wire_abort_reason,
                                              PairingDropAction drop_action,
                                              SendspinPairAbortReason public_reason,
                                              SendspinGoodbyeReason goodbye_reason) {
    // server_id is copied so it survives any tear-down below.
    const std::string server_id = conn->get_server_id();
    // Snapshot before clear_pairing_state()/drop_connection() clear it (see PairingUiSnapshot).
    const PairingUiSnapshot ui = snapshot_pairing_ui(conn);

    if (wire_abort_reason.has_value()) {
        conn->send_app_json(
            format_pair_abort_message(wire_abort_reason.value(), this->json_arena()));
    }

    // On the admitted path drop_connection() -> cleanup_connection_state() clears the queued
    // pairing notes, so the note_* calls below must come after it.
    conn->clear_pairing_state();
    if (drop_action != PairingDropAction::KEEP_OPEN) {
        const std::optional<SendspinGoodbyeReason> drop_goodbye_reason =
            drop_action == PairingDropAction::CLOSE_WITH_GOODBYE
                ? std::optional<SendspinGoodbyeReason>(goodbye_reason)
                : std::nullopt;
        this->drop_connection(conn, drop_goodbye_reason);
    }

    // Queued after any drop_connection() so they survive to be dispatched on the main loop.
    this->client_->note_pairing_failed(server_id, public_reason);
    this->client_->note_pairing_ui_dismissals(ui);
}

// ============================================================================
// Pairing-code handlers
// ============================================================================

void ConnectionManager::handle_pairing_message(SendspinConnection* conn,
                                               const ServerPairingMessage& message) {
    // All CPace / nonce / hash state is protocol-task-only, like this handler.

    // pairing.md "Entering and leaving pairing": a client that has aborted an attempt silently
    // discards pairing messages received before the next server/activate. is_pairing_in_progress()
    // is cleared by clear_pairing_state() on every path that ends an attempt (local abort, received
    // pair/abort, leftover activate), so a pairing message that races the abort and lands here
    // after the fact is discarded without re-aborting (which would otherwise fire on every stray,
    // now-stale message since ps.step is back to IDLE).
    if (!conn->is_pairing_in_progress()) {
        SS_LOGI(TAG,
                "handle_pairing_message: discarding pairing message (kind=%d) for "
                "server_id=%s; no attempt in progress",
                static_cast<int>(message.kind), conn->get_server_id().c_str());
        return;
    }

    switch (message.kind) {
        case PairingMessageKind::PAIR_INIT:
            this->handle_pair_init(conn, message);
            break;

        case PairingMessageKind::PAIR_AUTH:
            this->handle_pair_auth(conn, message);
            break;

        case PairingMessageKind::PAIR_CONFIRM:
            this->handle_pair_confirm(conn, message);
            break;

        case PairingMessageKind::MALFORMED: {
            auto& ps = conn->pairing_session();
            const std::string& server_id = conn->get_server_id();

            // A server pairing message (server/pair-init, server/pair-auth, or
            // server/pair-confirm) failed to parse. If no pairing-code session is active on this
            // connection, the frame is a stray protocol violation: e.g. a code-flow message
            // arriving during a pairing_psk exchange, which leaves ps.step IDLE. So drop it
            // without tearing the connection down.
            if (ps.step == SendspinConnection::PairingStep::IDLE) {
                SS_LOGW(TAG,
                        "handle_pairing_message: malformed pairing frame with no active "
                        "pairing-code session for server_id=%s; ignoring",
                        server_id.c_str());
                return;
            }

            // pairing.md "Protocol Errors": "a malformed or missing field ... is a protocol error:
            // the detecting side closes the WebSocket without sending any application-level error
            // message, and persists nothing." This is the one pairing-abort path that must NOT
            // send pair/abort and must close unconditionally, so it cannot route through
            // local_abort_pairing() (which always sends pair/abort and only closes for
            // concurrent_attempt).
            SS_LOGW(TAG,
                    "handle_pairing_message: malformed pairing frame during code pairing for "
                    "server_id=%s; closing per pairing.md Protocol Errors (no pair/abort sent)",
                    server_id.c_str());
            // clear_pairing_state() drops any pending pairing record, so nothing is persisted;
            // drop_connection() with goodbye=std::nullopt closes the transport without sending a
            // client/goodbye (or any other application-level message).
            // SendspinPairAbortReason has no dedicated "protocol error" value and none of the
            // wire-facing reasons fit (no pair/abort was received or sent); UNKNOWN is the
            // closest available local-only fit.
            this->abort_pairing_attempt(conn, /*wire_abort_reason=*/std::nullopt,
                                        PairingDropAction::CLOSE_SILENTLY,
                                        SendspinPairAbortReason::UNKNOWN);
            break;
        }
    }
}

void ConnectionManager::handle_pair_init(SendspinConnection* conn,
                                         const ServerPairingMessage& message) {
    auto& ps = conn->pairing_session();
    const std::string& server_id = conn->get_server_id();

    // Step 1: server/pair-init received. This step belongs to the dynamic pairing code alone;
    // the static flow goes straight from client/pair-init to server/pair-auth (pairing.md
    // "Static Pairing Code Flow"), so a PAIR_INIT while ps.method == STATIC_PAIRING_CODE is out
    // of sequence exactly as an out-of-order message is.
    if (ps.method != SendspinPairMethod::DYNAMIC_PAIRING_CODE ||
        ps.step != SendspinConnection::PairingStep::AWAIT_SERVER_PAIR_INIT) {
        this->close_on_sequence_violation(conn, "server/pair-init");
        return;
    }

    // pairing.md "Server -> Client: server/pair-init" carries nonce_A in the attempt's first
    // round only: the binding values, and so the pairing code, are unchanged across the rounds
    // that follow. A first round without it cannot derive a code, and a later round that carries
    // one is a message no conformant server sends, so both are protocol errors
    // (pairing.md "Protocol Errors"): close without a pair/abort and persist nothing. Ignoring a
    // late nonce would be no safer, since it must not move the code the operator already holds.
    const bool first_round = ps.round == 0;
    if (first_round != message.nonce_a.has_value()) {
        SS_LOGW(TAG,
                "handle_pairing_message: server/pair-init %s nonce_A in round %u for "
                "server_id=%s; closing per pairing.md Protocol Errors (no pair/abort sent)",
                message.nonce_a.has_value() ? "carries an unexpected" : "is missing its",
                static_cast<unsigned>(ps.round + 1), server_id.c_str());
        this->abort_pairing_attempt(conn, /*wire_abort_reason=*/std::nullopt,
                                    PairingDropAction::CLOSE_SILENTLY,
                                    SendspinPairAbortReason::UNKNOWN);
        return;
    }

    if (first_round) {
        ps.nonce_a = message.nonce_a.value();

        // Derive the code both formats share: the digest over the handshake hash and the two
        // binding nonces (pairing.md "Pairing code derivation").
        auto digest = pairing_code_digest(ps.handshake_hash.data(), ps.handshake_hash.size(),
                                          ps.nonce_a.data(), ps.nonce_a.size(), ps.nonce_b.data(),
                                          ps.nonce_b.size());
        if (!digest.has_value()) {
            SS_LOGE(TAG, "handle_pairing_message: pairing-code derivation failed for server_id=%s",
                    server_id.c_str());
            this->local_abort_pairing(conn, PairAbortReason::METHOD_NOT_SUPPORTED);
            return;
        }

        // The emission format decides both what the operator receives and what CPace consumes as
        // PRS: the six ASCII digits, or the 24 raw digest bytes the version-1 pairing token
        // carries (pairing.md "Pairing code derivation", "QR-code emission").
        std::string emitted;
        if (ps.format == SendspinPairingCodeFormat::QR_CODE) {
            auto code = pairing_code_qr_bytes(digest.value());
            ps.prs.assign(code.begin(), code.end());
            emitted = format_pairing_code_token(code);
        } else {
            std::string digits = pairing_code_digits(digest.value());
            ps.prs = pairing_code_digits_prs(digits);
            emitted = std::move(digits);
        }

        // Emit the code to the operator (queued for the main loop by note_display_pairing_code).
        // Record that one is being emitted so the abort/cleanup paths know to withdraw it. A
        // later round re-emits nothing: the code has not changed, so an emission that persists
        // (a display) is already showing the right one (pairing.md "Client verification").
        this->client_->note_display_pairing_code(emitted, ps.format);
        ps.code_emitted = true;
    }

    // The round counts from the moment its code is being emitted, whatever becomes of it
    // (pairing.md "Rounds").
    ++ps.round;
    ++this->pairing_rounds_since_verified_kc_;

    if (!this->start_pake_round(conn)) {
        return;
    }
    // No message sent yet: the client waits for server/pair-auth.
}

void ConnectionManager::handle_pair_auth(SendspinConnection* conn,
                                         const ServerPairingMessage& message) {
    auto& ps = conn->pairing_session();
    const std::string& server_id = conn->get_server_id();

    // Step 2: server/pair-auth received. Expect step AWAIT_SERVER_PAIR_AUTH.
    if (ps.step != SendspinConnection::PairingStep::AWAIT_SERVER_PAIR_AUTH) {
        this->close_on_sequence_violation(conn, "server/pair-auth");
        return;
    }

    // Send client/pair-auth (pake_msg_2 = client CPace share) before deriving.
    const auto& client_share = ps.cpace.public_share();
    conn->send_app_json(format_client_pair_auth_message(client_share, this->json_arena()));

    // Derive the MAC key from the server's share (pake_msg_1).
    // A derive failure means the peer share has the wrong length or encodes a
    // low-order point (a malformed or hostile share), not a wrong code: a wrong code
    // still produces a well-formed, non-low-order shared secret that only fails the
    // confirm-tag check in handle_pair_confirm().
    //
    // pairing.md "Protocol Errors": "a CPace share with the wrong length or encoding a
    // low-order point" is a protocol error: the detecting side closes the WebSocket
    // without sending any application-level error message, and persists nothing. This
    // is the same class as the MALFORMED case in handle_pairing_message(), so it follows
    // the same shape: no pair/abort, unconditional close.
    if (!ps.cpace.derive(message.pake_msg_1.data(), message.pake_msg_1.size())) {
        SS_LOGW(TAG,
                "handle_pairing_message: CPace::derive failed (malformed/low-order "
                "peer share) for server_id=%s; closing per pairing.md Protocol Errors "
                "(no pair/abort sent)",
                server_id.c_str());
        this->abort_pairing_attempt(conn, /*wire_abort_reason=*/std::nullopt,
                                    PairingDropAction::CLOSE_SILENTLY,
                                    SendspinPairAbortReason::UNKNOWN);
        return;
    }

    ps.step = SendspinConnection::PairingStep::AWAIT_SERVER_PAIR_CONFIRM;
}

void ConnectionManager::handle_pair_confirm(SendspinConnection* conn,
                                            const ServerPairingMessage& message) {
    auto& ps = conn->pairing_session();
    RecordStore& store = *this->client_->record_store_;
    const std::string& server_id = conn->get_server_id();

    // Step 3: server/pair-confirm received. Expect step AWAIT_SERVER_PAIR_CONFIRM.
    if (ps.step != SendspinConnection::PairingStep::AWAIT_SERVER_PAIR_CONFIRM) {
        this->close_on_sequence_violation(conn, "server/pair-confirm");
        return;
    }

    // Verify server_kc (server confirmation tag). A failure means the operator entered a
    // different code than the one this client emitted, which the Dynamic Pairing Code Flow
    // answers with another round rather than ending the attempt (pairing.md "Rounds").
    if (!ps.cpace.verify(message.server_kc.data(), message.server_kc.size())) {
        const bool can_retry = ps.method == SendspinPairMethod::DYNAMIC_PAIRING_CODE &&
                               !this->pairing_round_limit_reached();
        if (!can_retry) {
            SS_LOGW(TAG,
                    "handle_pairing_message: server_kc verification failed (pairing-code "
                    "mismatch) for server_id=%s; aborting (%s)",
                    server_id.c_str(),
                    ps.method == SendspinPairMethod::DYNAMIC_PAIRING_CODE
                        ? "round limit reached"
                        : "the static flow runs one round");
            // The attempt ends on a failed verification, which is what a pairing window counts
            // (pairing.md "Pairing Window").
            this->note_pairing_window_attempt_failed();
            this->local_abort_pairing(conn, PairAbortReason::PAIRING_CODE_MISMATCH);
            return;
        }

        // A retry keeps the attempt, its pairing code and its running attempt timeout in place;
        // the server answers with a fresh server/pair-init that begins the next round, which
        // carries no nonce because the binding values do not move.
        SS_LOGW(TAG,
                "handle_pairing_message: server_kc verification failed (pairing-code mismatch) "
                "for server_id=%s after round %u; sending client/pair-retry",
                server_id.c_str(), static_cast<unsigned>(ps.round));
        conn->send_app_json(format_client_pair_retry_message(this->json_arena()));
        ps.step = SendspinConnection::PairingStep::AWAIT_SERVER_PAIR_INIT;
        return;
    }

    // A verified server_kc is what the round limit counts back from (pairing.md "Rounds").
    this->pairing_rounds_since_verified_kc_ = 0;

    // Compute client_kc (our confirmation tag).
    auto client_kc_opt = ps.cpace.tag();
    if (!client_kc_opt.has_value()) {
        SS_LOGE(TAG, "handle_pairing_message: CPace::tag() failed for server_id=%s",
                server_id.c_str());
        this->local_abort_pairing(conn, PairAbortReason::METHOD_NOT_SUPPORTED);
        return;
    }

    // Both wrapped fields are sealed under the same CPace run (pairing.md "Wrapping"), so the
    // AEAD and the ISK are resolved once here, before the first of them is sent.
    const char* cipher_name = aead_cipher_name_from_noise_suite(conn->get_noise_suite_name());
    ScopedIsk isk(ps.cpace.isk());
    if (cipher_name == nullptr || !isk.has_value()) {
        SS_LOGE(TAG, "handle_pairing_message: cannot wrap (cipher=%s, isk=%s) for server_id=%s",
                cipher_name != nullptr ? cipher_name : "unknown",
                isk.has_value() ? "present" : "missing", server_id.c_str());
        this->local_abort_pairing(conn, PairAbortReason::METHOD_NOT_SUPPORTED);
        return;
    }

    // Send client/pair-confirm: the dynamic flow carries client_kc plus the sealed opening of
    // commit_B, the static flow client_kc alone (pairing.md "Client -> Server:
    // client/pair-confirm").
    if (ps.method == SendspinPairMethod::STATIC_PAIRING_CODE) {
        conn->send_app_json(
            format_client_pair_confirm_message(client_kc_opt.value(), this->json_arena()));
    } else {
        auto wrapped_nonce =
            wrap_value(NONCE_WRAP_LABEL, cipher_name, ps.cpace.sid(), isk.value(), ps.nonce_b);
        if (!wrapped_nonce.has_value()) {
            SS_LOGE(TAG, "handle_pairing_message: wrapping nonce_B failed for server_id=%s",
                    server_id.c_str());
            this->local_abort_pairing(conn, PairAbortReason::METHOD_NOT_SUPPORTED);
            return;
        }
        conn->send_app_json(format_client_pair_confirm_message(
            client_kc_opt.value(), wrapped_nonce.value(), this->json_arena()));
    }

    // Reset both flags immediately after dismissing: clear_pairing_state() does not run on this
    // success path, so a later inspection must not dismiss the same attempt's UI twice.
    this->client_->note_pairing_ui_dismissals(snapshot_pairing_ui(conn));
    ps.code_emitted = false;
    ps.window_shown = false;

    // Now run the same resolve_pairing_outcome path as pairing_psk, then send
    // client/pair-finalize. The server will respond with server/pair-finalize.
    auto outcome = store.resolve_pairing_outcome(server_id);

    // The code-based flows carry the new PSK wrapped under the CPace output, not in the clear
    // (pairing.md "Wrapping"). K_wrap = SHA-256(PSK_WRAP_LABEL || sid || ISK); the PSK is
    // sealed with the connection's negotiated AEAD, a 12-byte all-zero nonce, and empty AD. The
    // label differs from the one nonce_B was sealed under, so the two fields never share a key.
    auto wrapped =
        wrap_value(PSK_WRAP_LABEL, cipher_name, ps.cpace.sid(), isk.value(), outcome.psk);
    if (!wrapped.has_value()) {
        SS_LOGE(TAG, "handle_pairing_message: wrapping the long-term PSK failed for server_id=%s",
                server_id.c_str());
        this->local_abort_pairing(conn, PairAbortReason::METHOD_NOT_SUPPORTED);
        return;
    }

    SS_LOGI(TAG, "Sending client/pair-finalize (%s) for server_id=%s", to_cstr(ps.method),
            server_id.c_str());
    // Wiped after the send for the same reason as the unwrapped form above. The payload here is
    // the AEAD-wrapped PSK rather than raw key bytes, so this is the weaker of the two cases,
    // but the two finalize paths are kept identical so neither drifts.
    std::string finalize_msg =
        format_client_pair_finalize_wrapped_message(wrapped.value(), this->json_arena());
    conn->send_app_json(finalize_msg);
    secure_zero(finalize_msg.data(), finalize_msg.size());
    conn->set_pending_pairing_record(std::move(outcome.record));

    ps.step = SendspinConnection::PairingStep::AWAIT_SERVER_PAIR_FINALIZE;
}

void ConnectionManager::local_abort_pairing(SendspinConnection* conn, PairAbortReason reason) {
    // Ends the pairing-code session with a pair/abort on the wire; per pairing.md "pair/abort" only
    // concurrent_attempt also closes the connection.

    SS_LOGW(TAG, "local_abort_pairing: server_id=%s reason=%s", conn->get_server_id().c_str(),
            to_cstr(reason));

    // Best-effort pair/abort to the server (the connection is still live here).
    this->abort_pairing_attempt(
        conn, reason,
        reason == PairAbortReason::CONCURRENT_ATTEMPT ? PairingDropAction::CLOSE_WITH_GOODBYE
                                                      : PairingDropAction::KEEP_OPEN,
        to_public_abort_reason(reason), SendspinGoodbyeReason::CONCURRENT_ATTEMPT);
}

void ConnectionManager::close_on_sequence_violation(SendspinConnection* conn,
                                                    const char* message_type) {
    // pairing.md "Sequence violations": a pairing message out of sequence for the selected
    // method and the current state is a protocol error, and "Protocol Errors" has the detecting
    // side close the WebSocket without sending any application-level message. No pair/abort
    // goes out, and clear_pairing_state() inside abort_pairing_attempt() drops the pending
    // record, so nothing is persisted. SendspinPairAbortReason has no protocol-error value, so
    // the listener hears the local-only UNKNOWN.
    const auto& ps = conn->pairing_session();
    SS_LOGW(TAG,
            "handle_pairing_message: %s out of sequence (step=%d method=%s) for server_id=%s; "
            "closing per pairing.md Protocol Errors (no pair/abort sent)",
            message_type, static_cast<int>(ps.step), to_cstr(ps.method),
            conn->get_server_id().c_str());
    this->abort_pairing_attempt(conn, /*wire_abort_reason=*/std::nullopt,
                                PairingDropAction::CLOSE_SILENTLY,
                                SendspinPairAbortReason::UNKNOWN);
}

// ============================================================================
// Pairing window
// ============================================================================

void ConnectionManager::start_pairing_attempt(SendspinConnection* conn) {
    // The PairingSession was populated by handle_enter_pairing; this sends the client/pair-init
    // that starts the attempt and arms the attempt timeout that bounds it (pairing.md "Entering and
    // leaving pairing").
    //
    // An open window is not spent by starting an attempt: it runs for its own lifetime and
    // admits further attempts, but only on the connection carrying its first
    // (pairing.md "Pairing Window"), which is bound here.
    if (this->pairing_window_open() && this->pairing_window_conn_ == nullptr) {
        this->pairing_window_conn_ = conn;
    }

    auto& ps = conn->pairing_session();
    const std::string& server_id = conn->get_server_id();

    if (ps.method == SendspinPairMethod::DYNAMIC_PAIRING_CODE) {
        // Generate nonce_B and its commitment, then send client/pair-init with commit_B and
        // the required pairing_index (pairing.md "Client -> Server: client/pair-init"). The code
        // itself cannot be derived until the server's nonce_A arrives, so CPace starts in
        // handle_pair_init() rather than here.
        ps.nonce_b = pairing_generate_nonce();
        auto commit_b = pairing_code_commit(ps.nonce_b.data(), ps.nonce_b.size());
        if (!commit_b.has_value()) {
            SS_LOGE(TAG, "start_pairing_attempt: commitment derivation failed for server_id=%s",
                    server_id.c_str());
            this->local_abort_pairing(conn, PairAbortReason::METHOD_NOT_SUPPORTED);
            return;
        }

        SS_LOGI(TAG, "Sending client/pair-init (dynamic_pairing_code) for server_id=%s",
                server_id.c_str());
        conn->send_app_json(format_client_pair_init_message(commit_b.value(), ps.pairing_index,
                                                            this->json_arena()));

        ps.step = SendspinConnection::PairingStep::AWAIT_SERVER_PAIR_INIT;
        ps.attempt_deadline_us = platform_time_us() + PAIRING_ATTEMPT_TIMEOUT_US;
        return;
    }

    // Static pairing code: the PRS handle_enter_pairing_code() set. Empty means it was never set
    // (defensive; that path always sets it for a static attempt).
    if (ps.prs.empty()) {
        SS_LOGE(TAG, "start_pairing_attempt: no static pairing code captured for server_id=%s",
                server_id.c_str());
        this->local_abort_pairing(conn, PairAbortReason::METHOD_NOT_SUPPORTED);
        return;
    }

    // pairing_index is required on every client/pair-init (pairing.md "Pairing index"); the
    // static flow carries no commit_B.
    SS_LOGI(TAG, "Sending client/pair-init (static_pairing_code) for server_id=%s",
            server_id.c_str());
    conn->send_app_json(format_client_pair_init_message(ps.pairing_index, this->json_arena()));

    // The static flow has no rounds: its single CPace run is round 1 (pairing.md "PAKE").
    ps.round = 1;
    if (!this->start_pake_round(conn)) {
        return;
    }
    ps.attempt_deadline_us = platform_time_us() + PAIRING_ATTEMPT_TIMEOUT_US;
}

bool ConnectionManager::start_pake_round(SendspinConnection* conn) {
    // Both code-based flows run the same CPace exchange over the attempt's PRS and sid (pairing.md
    // "PAKE"); only the point at which the PRS becomes known differs, so the start lives here
    // rather than in each caller. The caller has already set ps.round to the number of the round
    // this run belongs to.
    auto& ps = conn->pairing_session();
    std::vector<uint8_t> sid = build_pake_sid(ps.handshake_hash, ps.pairing_index, ps.round);

    // ADb = "client" (our own AD), ADa = "server" (peer's AD): pairing.md "PAKE".
    if (!ps.cpace.start(CPaceRole::RESPONDER, ps.prs, sid, {}, pake_ad_client(),
                        pake_ad_server())) {
        SS_LOGE(TAG, "start_pake_round: CPace::start failed for server_id=%s",
                conn->get_server_id().c_str());
        this->local_abort_pairing(conn, PairAbortReason::METHOD_NOT_SUPPORTED);
        return false;
    }
    ps.step = SendspinConnection::PairingStep::AWAIT_SERVER_PAIR_AUTH;
    return true;
}

bool ConnectionManager::pairing_window_open() const {
    return this->pairing_window_open_until_us_ != 0 &&
           platform_time_us() < this->pairing_window_open_until_us_;
}

void ConnectionManager::open_pairing_window() {
    // A pairing session only ever exists on an admitted connection: pairing only starts once a
    // nursery entry has won admission (see the pairing branch in
    // promote_or_arbitrate_nursery_entry()).
    //
    // The window's lifetime runs from here and is not paused by the attempts it admits
    // (pairing.md "Pairing Window"), so it is armed whether or not an attempt is waiting: with
    // none waiting it stands open for a pairing activate arriving within its lifetime.
    //
    // The gesture is also the deliberate, manufacturer-defined operator action pairing.md
    // "Rounds" requires to clear a standing round limit, so it resets the count before anything
    // it admits can run.
    this->pairing_rounds_since_verified_kc_ = 0;
    this->pairing_window_conn_ = nullptr;
    this->pairing_window_failed_attempts_ = 0;
    this->pairing_window_open_until_us_ = platform_time_us() + WINDOW_LIFETIME_US;

    // The window admits attempts on one connection only (pairing.md "Pairing Window"), so the
    // gesture starts the first attempt it finds waiting and binds itself to that connection.
    for (const auto& entry : this->admitted_) {
        SendspinConnection* conn = entry.conn.get();
        if (conn != nullptr &&
            conn->pairing_session().step == SendspinConnection::PairingStep::AWAIT_PAIRING_WINDOW) {
            SS_LOGI(TAG, "Pairing window opened: starting the waiting %s attempt for server_id=%s",
                    to_cstr(conn->pairing_session().method), conn->get_server_id().c_str());
            this->start_pairing_attempt(conn);
            return;
        }
    }

    SS_LOGI(TAG, "Pairing window opened: standing open for %lld s awaiting a pairing attempt",
            static_cast<long long>(WINDOW_LIFETIME_S));
}

bool ConnectionManager::pairing_round_limit_reached() const {
    return this->pairing_rounds_since_verified_kc_ >= PAIRING_ROUND_LIMIT;
}

void ConnectionManager::close_pairing_window() {
    if (this->pairing_window_open_until_us_ == 0) {
        return;
    }
    this->pairing_window_open_until_us_ = 0;
    this->pairing_window_conn_ = nullptr;
    this->pairing_window_failed_attempts_ = 0;
    SS_LOGI(TAG, "Pairing window closed");
}

bool ConnectionManager::pairing_window_admits(const SendspinConnection* conn) const {
    return this->pairing_window_open() &&
           (this->pairing_window_conn_ == nullptr || this->pairing_window_conn_ == conn);
}

void ConnectionManager::note_pairing_window_attempt_failed() {
    if (!this->pairing_window_open()) {
        return;
    }
    ++this->pairing_window_failed_attempts_;
    if (this->pairing_window_failed_attempts_ < WINDOW_FAILED_ATTEMPT_LIMIT) {
        SS_LOGW(TAG, "Pairing window: %u of %u attempts failed verification",
                static_cast<unsigned>(this->pairing_window_failed_attempts_),
                static_cast<unsigned>(WINDOW_FAILED_ATTEMPT_LIMIT));
        return;
    }
    SS_LOGW(TAG, "Pairing window: %u failed attempts; closing the window",
            static_cast<unsigned>(WINDOW_FAILED_ATTEMPT_LIMIT));
    this->close_pairing_window();
}

// ============================================================================
// Unpair handler
// ============================================================================

void ConnectionManager::handle_server_unpair(SendspinConnection* conn) {
    // Only a session running on a long-term record is paired at all (messaging.md "server/unpair":
    // if the session is unpaired, ignore the message), so a pairing or Sentinel handshake has
    // nothing to drop.
    if (conn->get_psk_category() != PskCategory::LONG_TERM) {
        SS_LOGD(TAG, "server/unpair ignored (non-LONG_TERM category, server_id=%s)",
                conn->get_server_id().c_str());
        return;
    }

    // Copied: the drops below release the connection that owns the string.
    const std::string psk_id = conn->get_psk_id();
    SS_LOGI(TAG, "server/unpair: dropping record and disconnecting (server_id=%s, psk_id=%s)",
            conn->get_server_id().c_str(), psk_id.c_str());

    // Drop the matched pairing record (messaging.md "server/unpair"). The RAM erase runs here,
    // because a re-handshake on the revoked psk_id resolves against the store and must miss it
    // from this instant: deferring it would leave the credential usable until the flush. The slot
    // write is left to SendspinClient::flush_pending_persistence() on the main loop.
    if (this->client_->record_store_->note_record_removed(psk_id)) {
        this->client_->request_persist();
    }

    // Any OTHER session running on the same record is no longer trusted either; see
    // drop_connections_using_psk_id(). `conn` itself is excluded and dropped below with the
    // spec's UNPAIRED reason rather than UNAUTHORIZED.
    this->drop_connections_using_psk_id(psk_id, conn);

    this->drop_connection(conn, SendspinGoodbyeReason::UNPAIRED);
}

}  // namespace sendspin
