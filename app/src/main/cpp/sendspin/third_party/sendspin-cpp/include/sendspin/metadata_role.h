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

/// @file metadata_role.h
/// @brief Metadata role that receives track metadata and playback progress from the server

#pragma once

#include <cstdint>
#include <memory>
#include <optional>
#include <string>

namespace sendspin {

class SendspinClient;

// ============================================================================
// Metadata types
// ============================================================================

/// @brief Playback progress fields embedded in a metadata state update
struct MetadataProgressObject {
    uint32_t track_progress;
    uint32_t track_duration;
    uint32_t playback_speed;
};

/// @brief Track metadata and playback state received from the server
struct ServerMetadataStateObject {
    int64_t timestamp{};
    std::optional<std::string> title;
    std::optional<std::string> artist;
    std::optional<std::string> album_artist;
    std::optional<std::string> album;
    std::optional<std::string> artwork_url;
    std::optional<uint16_t> year;
    std::optional<uint16_t> track;
    std::optional<MetadataProgressObject> progress;
};

/// @brief Listener for metadata role events. All methods fire on the main loop thread.
class MetadataRoleListener {
public:
    virtual ~MetadataRoleListener() = default;

    /// @brief Called when metadata is updated by the server
    virtual void on_metadata(const ServerMetadataStateObject& /*metadata*/) {}

    /// @brief Called when the cached metadata is dropped: the connection to the server was lost,
    /// or a server/activate took the metadata role out of the session's active roles
    ///
    /// Implementations should clear any displayed track metadata, since the previous state is no
    /// longer valid. Idempotent by contract: a second clear with nothing to clear must be a
    /// no-op. A role removed from an active session
    /// can be added back by a later activation, which resumes with a fresh on_metadata().
    virtual void on_metadata_clear() {}
};

/**
 * @brief Metadata role that receives track metadata and playback progress from the server
 *
 * Maintains a local shadow of the server's metadata state, including track title, artist,
 * album, artwork URL, and playback progress. Each metadata state the server sends carries the
 * full state, so it replaces the shadow outright: a field it leaves out has no value, and a
 * state without `progress` clears the position. The state is delivered to the listener on the
 * main loop thread once the synchronized client clock reaches its `timestamp` (or immediately if
 * there is no active connection). Progress is interpolated locally using the server timestamp so
 * callers always get a current value.
 *
 * Usage:
 * 1. Implement MetadataRoleListener to receive metadata updates
 * 2. Add the role to the client via SendspinClient::add_metadata()
 * 3. Call set_listener() with your listener implementation
 * 4. Poll get_track_progress_ms() and get_track_duration_ms() as needed for UI updates
 *
 * @code
 * struct MyMetadataListener : MetadataRoleListener {
 *     void on_metadata(const ServerMetadataStateObject& m) override {
 *         if (m.title) display_title(*m.title);
 *         if (m.artist) display_artist(*m.artist);
 *     }
 * };
 *
 * MyMetadataListener listener;
 * auto& metadata = client.add_metadata();
 * metadata.set_listener(&listener);
 * uint32_t progress = metadata.get_track_progress_ms();
 * @endcode
 */
class MetadataRole {
    friend class SendspinClient;

public:
    struct Impl;

    explicit MetadataRole(SendspinClient* client);
    ~MetadataRole();

    /// @brief Sets the listener for metadata events; it must outlive this role
    void set_listener(MetadataRoleListener* listener);

    /// @brief Returns the track duration in milliseconds, or 0 if unknown or the stream is live
    uint32_t get_track_duration_ms() const;

    /// @brief Returns the track progress in milliseconds, interpolated from the last server update
    uint32_t get_track_progress_ms() const;

private:
    std::unique_ptr<Impl> impl_;
};

}  // namespace sendspin
