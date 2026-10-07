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

/// @file artwork_role.h
/// @brief Artwork role that receives artwork images from the server

#pragma once

#include "sendspin/config.h"

#include <cstddef>
#include <cstdint>
#include <memory>

namespace sendspin {

class SendspinClient;

/// @brief Listener for artwork role events
///
/// THREAD SAFETY: on_image_decode() fires on a dedicated decode thread and must be
/// thread-safe with respect to the other callbacks. on_image_display() and on_image_clear()
/// fire on the main loop thread.
///
/// ACK GATE (opt-in per slot via ImageSlotPreference::require_frame_done): a "delivery" is either
/// a frame (on_image_decode() then on_image_display()) or a clear (on_image_clear()). Call
/// ArtworkRole::frame_done(slot) once for every on_image_display() and on_image_clear() a gated
/// slot receives. An extra call is a no-op; a missed one wedges the slot, since there is no
/// timeout. At most one un-acked delivery is in flight:
///  - A payload (a frame, or the server's per-channel clear for that slot) arriving while a
///    delivery is un-acked is buffered latest-wins and delivered after frame_done(slot), then owes
///    its own. It waits behind the outstanding delivery rather than replacing it, so a consumer
///    presenting a delivery is never interrupted.
///  - A stream end is a lifecycle event, not a payload: it fires on_image_clear() immediately for
///    every configured slot, discards anything buffered, replaces whatever was outstanding, and
///    owes one frame_done() afterwards.
///
/// A frame decoded but never displayed is released automatically when its display can no longer
/// fire (a stream restart; the server replacing or cancelling the image before its display was
/// due). A delivery that reached on_image_display()/on_image_clear() stays gated until
/// frame_done().
class ArtworkRoleListener {
public:
    virtual ~ArtworkRoleListener() = default;

    /// @brief Called on the decode thread when encoded image data arrives
    ///
    /// The implementation should decode the image (e.g., JPEG to bitmap) synchronously.
    /// The data pointer is valid for the duration of this call.
    /// @param slot The artwork slot index.
    /// @param data Pointer to the encoded image data.
    /// @param length Length of the encoded image data in bytes.
    /// @param format Image format (JPEG or PNG).
    virtual void on_image_decode(uint8_t /*slot*/, const uint8_t* /*data*/, size_t /*length*/,
                                 SendspinImageFormat /*format*/) {}

    /// @brief Called on the main loop thread at the correct timestamp when the decoded image
    /// should be displayed
    ///
    /// Fires after on_image_decode() once the server timestamp is reached. The deadline can be
    /// shifted per slot via ImageSlotPreference::display_offset_ms (positive fires early, e.g.
    /// to start a cross-fade before the track boundary). If a newer frame for the same slot
    /// finishes decoding before the pending display fires, the older pending display is
    /// superseded and only the newer one is delivered.
    /// @param slot The artwork slot index.
    /// @param lateness_ms How far past the (offset-shifted) deadline this display fired, so a
    /// consumer can compensate (e.g. shorten a cross-fade by the lateness). Displays are
    /// best-effort: an image that arrives or decodes after its deadline fires as soon as it is
    /// ready. On-time displays report a few milliseconds of main-loop polling granularity, so
    /// treat small values as on time. Reports 0 when there is no connection, since no deadline
    /// exists.
    virtual void on_image_display(uint8_t /*slot*/, uint32_t /*lateness_ms*/) {}

    /// @brief Called on the main loop thread when artwork should be cleared for a slot
    ///
    /// Fires for every configured slot on stream end, on connection loss, and when a
    /// server/activate takes the artwork role out of the session's active roles (each also drops
    /// any in-flight transfer). Fires for a single slot when the server clears that channel (the
    /// artwork for the current item is gone, e.g. a track with no album art). A
    /// per-channel clear is scheduled to its server timestamp exactly like on_image_display(),
    /// ImageSlotPreference::display_offset_ms included, so it lands on the item boundary rather
    /// than as soon as it arrives.
    ///
    /// Artwork stays valid until it is replaced or cleared, so the server does not resend an
    /// unchanged image on every track: no callback at a track boundary means the image already
    /// delivered still applies.
    /// @param slot The artwork slot index to clear.
    virtual void on_image_clear(uint8_t /*slot*/) {}
};

/**
 * @brief Artwork role that receives album art and artist images from the server
 *
 * Receives images from the server and delivers them to the platform through
 * ArtworkRoleListener callbacks. Each image arrives as a transfer of several binary messages,
 * which the role reassembles; a dedicated decode thread fires on_image_decode() once the image
 * is complete. on_image_display() and on_image_clear() fire on the main loop thread, with
 * on_image_display() scheduled to the server timestamp. Supports multiple image slots with
 * configurable format and resolution preferences.
 *
 * The server may replace or cancel an image it has sent but whose display time has not arrived,
 * in which case that image is dropped and its on_image_display() never fires. An image the server
 * declares larger than the slot's ImageSlotPreference::max_image_bytes is refused rather than
 * buffered, so a slot's memory is bounded by the budget the consumer set for it.
 *
 * A slot may opt into a back-pressure gate via ImageSlotPreference::require_frame_done; see
 * ArtworkRoleListener for the ack contract.
 *
 * Usage:
 * 1. Implement ArtworkRoleListener with on_image_decode() and on_image_display()
 * 2. Build an ArtworkRoleConfig with the desired slot/format/resolution preferences
 * 3. Add the role to the client via SendspinClient::add_artwork()
 * 4. Call set_listener() with your listener implementation
 *
 * @code
 * struct MyArtworkListener : ArtworkRoleListener {
 *     void on_image_decode(uint8_t slot, const uint8_t* data, size_t length,
 *                          SendspinImageFormat format) override {
 *         decoded_images[slot] = decode(data, length, format);
 *     }
 *     void on_image_display(uint8_t slot, uint32_t lateness_ms) override {
 *         // Slot 0 is ack-gated: frame_done() is called when the fade finishes.
 *         display.start_fade(slot, decoded_images[slot], FADE_MS - std::min(lateness_ms, FADE_MS));
 *     }
 *     void on_image_clear(uint8_t slot) override {
 *         display.clear_slot(slot);
 *         artwork_role->frame_done(slot);
 *     }
 *     void on_fade_complete(uint8_t slot) {
 *         artwork_role->frame_done(slot);
 *     }
 *
 *     ArtworkRole* artwork_role{nullptr};
 * };
 *
 * MyArtworkListener listener;
 * ArtworkRoleConfig config;
 * config.preferred_formats = {{.source = SendspinImageSource::ALBUM,
 *                              .format = SendspinImageFormat::JPEG,
 *                              .width = 240,
 *                              .height = 240,
 *                              .require_frame_done = true}};
 * auto& artwork = client.add_artwork(config);
 * listener.artwork_role = &artwork;
 * artwork.set_listener(&listener);
 * @endcode
 */
class ArtworkRole {
    friend class SendspinClient;

public:
    struct Impl;

    ArtworkRole(ArtworkRoleConfig config, SendspinClient* client);
    ~ArtworkRole();

    /// @brief Sets the listener for artwork events; it must outlive this role
    void set_listener(ArtworkRoleListener* listener);

    /// @brief Acknowledges the most recent delivery for an ack-gated slot, releasing the gate
    ///
    /// Call from the main loop thread after finishing presentation of the most recent delivery
    /// (frame or clear) for a slot with ImageSlotPreference::require_frame_done set, e.g. once a
    /// cross-fade animation completes. Safe no-op if the slot has nothing un-acked (including
    /// slots where require_frame_done is false). Also safe to call from inside
    /// on_image_display() or on_image_clear() for instant (non-animated) presentation.
    /// @param slot The artwork slot index to acknowledge.
    void frame_done(uint8_t slot);

private:
    std::unique_ptr<Impl> impl_;
};

}  // namespace sendspin
