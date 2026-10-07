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

#include "platform/base64.h"
#include "platform/json_arena.h"
#include "platform/logging.h"
#include "platform/secure_zero.h"
#include "protocol_messages.h"
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
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <limits>
#include <optional>
#include <string>
#include <utility>
#include <vector>

namespace sendspin {

static const char* const TAG = "sendspin.protocol";

// ============================================================================
// Static helpers
// ============================================================================

/// @brief Reads an optional unsigned-integer field with strict type and optional range validation.
/// Absent or null returns nullopt silently (a legal state for an optional field). A present value
/// that is the wrong JSON type, outside the target type's range, or outside [min, max] is logged
/// and dropped. Strict about representation: a float or numeric string is rejected, matching a
/// spec-compliant server that sends genuine JSON integers.
template <typename T>
static std::optional<T> read_uint_field(JsonVariantConst var, const char* name,
                                        T min = std::numeric_limits<T>::min(),
                                        T max = std::numeric_limits<T>::max()) {
    if (var.isUnbound() || var.isNull()) {
        return std::nullopt;
    }
    if (!var.is<T>()) {
        SS_LOGW(TAG, "Ignoring field '%s': expected integer in [%llu, %llu]", name,
                static_cast<unsigned long long>(min), static_cast<unsigned long long>(max));
        return std::nullopt;
    }
    T value = var.as<T>();
    if (value < min || value > max) {
        SS_LOGW(TAG, "Ignoring field '%s': %llu outside [%llu, %llu]", name,
                static_cast<unsigned long long>(value), static_cast<unsigned long long>(min),
                static_cast<unsigned long long>(max));
        return std::nullopt;
    }
    return value;
}

/// @brief Reads an optional boolean field. Absent or null returns nullopt silently; a present
/// non-boolean value (including 0/1 integers) is logged and dropped. Strict: only genuine JSON
/// true/false is accepted, matching a spec-compliant server.
static std::optional<bool> read_bool_field(JsonVariantConst var, const char* name) {
    if (var.isUnbound() || var.isNull()) {
        return std::nullopt;
    }
    if (!var.is<bool>()) {
        SS_LOGW(TAG, "Ignoring field '%s': expected boolean", name);
        return std::nullopt;
    }
    return var.as<bool>();
}

/// @brief Reads an optional enum field parsed from a wire string via `from_string`. Absent or null
/// returns nullopt silently; a present non-string, or a string that `from_string` does not
/// recognize, is logged and dropped. For enum fields whose policy is "apply if valid, otherwise
/// leave the current value untouched" (not for fields that map unknown values to a sentinel or that
/// reject the whole message).
template <typename E>
static std::optional<E> read_enum_field(JsonVariantConst var, const char* name,
                                        std::optional<E> (*from_string)(const std::string&)) {
    if (var.isUnbound() || var.isNull()) {
        return std::nullopt;
    }
    if (!var.is<const char*>()) {
        SS_LOGW(TAG, "Ignoring field '%s': expected string", name);
        return std::nullopt;
    }
    std::optional<E> value = from_string(var.as<std::string>());
    if (!value) {
        SS_LOGW(TAG, "Ignoring field '%s': unknown value '%s'", name, var.as<const char*>());
    }
    return value;
}

static bool process_player_stream_object(const JsonObject player_object,
                                         ServerPlayerStreamObject* player_obj,
                                         bool require_all_fields) {
    if (player_obj == nullptr) {
        return false;
    }

    if (require_all_fields) {
        if (!player_object["bit_depth"].is<JsonVariant>() ||
            !player_object["channels"].is<JsonVariant>() ||
            !player_object["sample_rate"].is<JsonVariant>() ||
            !player_object["codec"].is<JsonVariant>()) {
            SS_LOGE(TAG, "Invalid player object: missing required fields");
            return false;
        }
    }

    if (player_object["codec"].is<JsonVariant>()) {
        std::string codec_type = player_object["codec"].as<std::string>();
        auto codec = codec_format_from_string(codec_type);
        player_obj->codec = codec.value_or(SendspinCodecFormat::UNSUPPORTED);
    }

    if (auto v = read_uint_field<uint32_t>(player_object["sample_rate"], "sample_rate")) {
        player_obj->sample_rate = v;
    }

    if (auto v = read_uint_field<uint8_t>(player_object["channels"], "channels")) {
        player_obj->channels = v;
    }

    if (auto v = read_uint_field<uint8_t>(player_object["bit_depth"], "bit_depth")) {
        player_obj->bit_depth = v;
    }

    if (player_object["codec_header"].is<JsonVariant>()) {
        player_obj->codec_header = player_object["codec_header"].as<std::string>();
    }

    // For FLAC, codec_header is required
    if (player_obj->codec.has_value() && player_obj->codec.value() == SendspinCodecFormat::FLAC &&
        !player_obj->codec_header.has_value()) {
        SS_LOGE(TAG, "Invalid player object: FLAC requires codec_header");
        return false;
    }

    return true;
}

static bool process_artwork_channel_object(const JsonObject channel_object,
                                           ServerArtworkChannelObject* channel,
                                           bool require_all_fields) {
    if (channel == nullptr) {
        return false;
    }

    if (require_all_fields) {
        if (!channel_object["source"].is<JsonVariant>() ||
            !channel_object["format"].is<JsonVariant>() ||
            !channel_object["width"].is<JsonVariant>() ||
            !channel_object["height"].is<JsonVariant>()) {
            SS_LOGE(TAG, "Invalid artwork channel: missing required fields");
            return false;
        }
    }

    if (auto source =
            read_enum_field(channel_object["source"], "source", image_source_from_string)) {
        channel->source = source;
    }

    if (auto format =
            read_enum_field(channel_object["format"], "format", image_format_from_string)) {
        channel->format = format;
    }

    if (auto v = read_uint_field<uint16_t>(channel_object["width"], "width")) {
        channel->width = v;
    }

    if (auto v = read_uint_field<uint16_t>(channel_object["height"], "height")) {
        channel->height = v;
    }

    return true;
}

static bool process_server_player_command_object(const JsonObject player_object,
                                                 ServerPlayerCommandObject* player_cmd) {
    if (player_cmd == nullptr || !player_object["command"].is<JsonVariant>()) {
        return false;
    }

    std::string command_str = player_object["command"].as<std::string>();
    auto command = player_command_from_string(command_str);

    if (!command.has_value()) {
        SS_LOGE(TAG, "Invalid server player command type: %s", command_str.c_str());
        return false;
    }
    player_cmd->command = command.value();

    // Parse optional fields
    if (auto v = read_uint_field<uint8_t>(player_object["volume"], "volume", 0, VOLUME_MAX)) {
        player_cmd->volume = v;
    }

    if (auto v = read_bool_field(player_object["mute"], "mute")) {
        player_cmd->mute = v;
    }

    if (auto v = read_uint_field<uint16_t>(player_object["output_delay_ms"], "output_delay_ms")) {
        player_cmd->output_delay_ms = v;
    }

    return true;
}

// Parses a single string field of a server/state metadata object. A field the object does not
// carry, or carries with the wrong type (logged), leaves `out` without a value.
static void parse_metadata_string_field(JsonVariantConst var, const char* name,
                                        std::optional<std::string>* out) {
    if (var.is<const char*>()) {
        *out = var.as<std::string>();
    } else if (!var.isUnbound() && !var.isNull()) {
        SS_LOGW(TAG, "Ignoring field '%s': expected string", name);
    }
}

// Parses a single `[R, G, B]` color field of a server/state color object. A field the object does
// not carry, or carries malformed (logged), leaves `out` without a value. Each component is read
// as a uint8, so its type check is also its range check.
static void parse_color_field(JsonVariantConst var, const char* name,
                              std::optional<RgbColor>* out) {
    // An explicit null is treated as an absent field rather than a wrong-typed one:
    // roles/color/v1.md defines no null form to log against.
    if (var.isUnbound() || var.isNull()) {
        return;
    }
    if (!var.is<JsonArrayConst>()) {
        SS_LOGW(TAG, "Ignoring field '%s': expected [R, G, B] array", name);
        return;
    }
    JsonArrayConst arr = var.as<JsonArrayConst>();
    if (arr.size() != 3) {
        SS_LOGW(TAG, "Ignoring field '%s': expected 3 components, got %zu", name, arr.size());
        return;
    }
    RgbColor color{};
    for (size_t i = 0; i < 3; i++) {
        auto component = read_uint_field<uint8_t>(arr[i], name);
        if (!component) {
            return;  // out of [0, 255] or wrong type; already logged
        }
        color[i] = *component;
    }
    // cppcheck-suppress autoVariables
    // False positive: RgbColor is std::array<uint8_t, 3> (include/sendspin/color_role.h), a
    // plain value type. This assigns *out by value through std::optional's assignment operator;
    // it does not take the address of the local `color`.
    *out = color;
}

// ============================================================================
// Protocol functions
// ============================================================================

// Message type determination

SendspinServerToClientMessageType determine_message_type(JsonObject root) {
    // Compared in place against the arena's NUL-terminated string: extracting an
    // std::string here would heap-allocate for every type name longer than the SSO limit.
    const char* type_str = root["type"].as<const char*>();
    if (type_str == nullptr) {
        return SendspinServerToClientMessageType::UNKNOWN;
    }

    if (std::strcmp(type_str, "server/hello") == 0) {
        return SendspinServerToClientMessageType::SERVER_HELLO;
    }
    if (std::strcmp(type_str, "server/activate") == 0) {
        return SendspinServerToClientMessageType::SERVER_ACTIVATE;
    }
    if (std::strcmp(type_str, "server/time") == 0) {
        return SendspinServerToClientMessageType::SERVER_TIME;
    }
    if (std::strcmp(type_str, "server/state") == 0) {
        return SendspinServerToClientMessageType::SERVER_STATE;
    }
    if (std::strcmp(type_str, "server/command") == 0) {
        return SendspinServerToClientMessageType::SERVER_COMMAND;
    }
    if (std::strcmp(type_str, "stream/start") == 0) {
        return SendspinServerToClientMessageType::STREAM_START;
    }
    if (std::strcmp(type_str, "stream/end") == 0) {
        return SendspinServerToClientMessageType::STREAM_END;
    }
    if (std::strcmp(type_str, "stream/clear") == 0) {
        return SendspinServerToClientMessageType::STREAM_CLEAR;
    }
    if (std::strcmp(type_str, "group/update") == 0) {
        return SendspinServerToClientMessageType::GROUP_UPDATE;
    }
    if (std::strcmp(type_str, "noise/handshake") == 0) {
        return SendspinServerToClientMessageType::NOISE_HANDSHAKE;
    }
    if (std::strcmp(type_str, "server/pair-finalize") == 0) {
        return SendspinServerToClientMessageType::SERVER_PAIR_FINALIZE;
    }
    if (std::strcmp(type_str, "pair/abort") == 0) {
        return SendspinServerToClientMessageType::PAIR_ABORT;
    }
    if (std::strcmp(type_str, "server/unpair") == 0) {
        return SendspinServerToClientMessageType::SERVER_UNPAIR;
    }
    if (std::strcmp(type_str, "server/pair-init") == 0) {
        return SendspinServerToClientMessageType::SERVER_PAIR_INIT;
    }
    if (std::strcmp(type_str, "server/pair-auth") == 0) {
        return SendspinServerToClientMessageType::SERVER_PAIR_AUTH;
    }
    if (std::strcmp(type_str, "server/pair-confirm") == 0) {
        return SendspinServerToClientMessageType::SERVER_PAIR_CONFIRM;
    }

    return SendspinServerToClientMessageType::UNKNOWN;
}

// Message processing

bool process_server_hello_message(JsonObject root, ServerHelloMessage* hello_msg) {
    // server_id is taken from the Noise handshake result, not parsed here.
    if (!root["payload"]["name"].is<const char*>()) {
        SS_LOGE(TAG, "Invalid server/hello message: missing name");
        return false;
    }

    if (hello_msg == nullptr) {
        return true;
    }
    hello_msg->name = root["payload"]["name"].as<std::string>();

    // roles/source/v1.md "server/hello source@v1 support object": supported_codecs is a required
    // list of strings that must include flac and pcm, and a source ignores the codec identifiers
    // it does not recognize. A malformed object rejects only itself, never the hello.
    const JsonVariantConst source_support = root["payload"]["source@v1_support"];
    if (!source_support.isNull()) {
        const JsonVariantConst codecs = source_support["supported_codecs"];
        if (!codecs.is<JsonArrayConst>()) {
            SS_LOGW(TAG, "Ignoring server/hello source@v1_support: missing supported_codecs");
        } else {
            constexpr uint8_t REQUIRED = source_codec_bit(SendspinCodecFormat::PCM) |
                                         source_codec_bit(SendspinCodecFormat::FLAC);
            uint8_t accepted = 0;
            bool all_strings = true;
            for (JsonVariantConst codec : codecs.as<JsonArrayConst>()) {
                if (!codec.is<const char*>()) {
                    all_strings = false;
                    break;
                }
                if (auto format = codec_format_from_string(codec.as<std::string>())) {
                    accepted |= source_codec_bit(*format);
                }
            }
            if (!all_strings) {
                SS_LOGW(TAG, "Ignoring server/hello source@v1_support: non-string codec entry");
            } else if ((accepted & REQUIRED) != REQUIRED) {
                SS_LOGW(TAG, "Ignoring server/hello source@v1_support: pcm or flac not listed");
            } else {
                hello_msg->source_codecs = accepted;
            }
        }
    }

    return true;
}

bool process_server_activate_message(JsonObject root, ServerActivateMessage* activate_msg) {
    if (!root["payload"]["activities"].is<JsonArrayConst>()) {
        SS_LOGE(TAG, "Invalid server/activate message: missing activities array");
        return false;
    }

    if (activate_msg == nullptr) {
        return true;
    }

    activate_msg->activities.clear();
    JsonArrayConst activities_array = root["payload"]["activities"].as<JsonArrayConst>();
    for (JsonVariantConst act_var : activities_array) {
        if (act_var.is<const char*>()) {
            auto act = activity_from_string(act_var.as<std::string>());
            if (act.has_value()) {
                activate_msg->activities.push_back(act.value());
            }
        }
    }

    // active_roles is optional and sticky (omitted = keep prior set).
    JsonVariantConst roles_var = root["payload"]["active_roles"];
    if (roles_var.is<JsonArrayConst>()) {
        activate_msg->active_roles = std::vector<std::string>{};
        for (JsonVariantConst role_var : roles_var.as<JsonArrayConst>()) {
            if (role_var.is<const char*>()) {
                activate_msg->active_roles->push_back(role_var.as<std::string>());
            }
        }
    } else {
        activate_msg->active_roles = std::nullopt;
    }

    // pairing object: required when 'pairing' is in activities, ignored otherwise (the
    // activities check lives in apply_server_activate, which nulls the method outside pairing).
    activate_msg->pairing_method = std::nullopt;
    activate_msg->pairing_format = std::nullopt;
    JsonVariantConst pairing_var = root["payload"]["pairing"];
    if (pairing_var.is<JsonObjectConst>()) {
        activate_msg->pairing_method =
            read_enum_field(pairing_var["method"], "pairing.method", pair_method_from_string);
        activate_msg->pairing_format = read_enum_field(pairing_var["format"], "pairing.format",
                                                       pairing_code_format_from_string);
        // The server's `languages` (BCP 47 tags, descending operator preference) hints at what
        // the operator understands (messaging.md "server/hello"). Its only use here would be
        // spoken pairing-code emission (pairing.md "Digits emission"), which this library does
        // not implement, so it is not parsed; a client adding speaker emission should read it
        // from server/hello and match per RFC 4647 Lookup.
    }

    return true;
}

bool process_server_time_message(JsonObject root, ServerTimeMessage* time_msg) {
    // messaging.md "server/time": all three timestamps are required integers (microsecond clock
    // values, so wider than 32 bits).
    const JsonVariantConst client_transmitted_var = root["payload"]["client_transmitted"];
    const JsonVariantConst server_received_var = root["payload"]["server_received"];
    const JsonVariantConst server_transmitted_var = root["payload"]["server_transmitted"];
    if (!client_transmitted_var.is<int64_t>() || !server_received_var.is<int64_t>() ||
        !server_transmitted_var.is<int64_t>()) {
        SS_LOGE(TAG, "Invalid server/time message: missing or non-integer timestamp");
        return false;
    }

    if (time_msg != nullptr) {
        time_msg->client_transmitted = client_transmitted_var.as<int64_t>();
        time_msg->server_received = server_received_var.as<int64_t>();
        time_msg->server_transmitted = server_transmitted_var.as<int64_t>();
    }
    return true;
}

void compute_time_exchange(const ServerTimeMessage& time_msg, int64_t client_sent,
                           int64_t client_received, int64_t* offset, int64_t* max_error) {
    if (offset != nullptr) {
        *offset = ((time_msg.server_received - client_sent) +
                   (time_msg.server_transmitted - client_received)) /
                  2;
    }

    if (max_error != nullptr) {
        const int64_t delay = (client_received - client_sent) -
                              (time_msg.server_transmitted - time_msg.server_received);
        *max_error = delay / 2;
    }
}

bool process_group_update_message(JsonObject root, GroupUpdateMessage* group_msg) {
    if (group_msg == nullptr) {
        return true;
    }

    // Parse optional playback_state
    JsonVariantConst playback_state_var = root["payload"]["playback_state"];
    if (!playback_state_var.isUnbound() && playback_state_var.isNull()) {
        // Field set to null: clear from state
        group_msg->group.playback_state = std::nullopt;
    } else if (auto state = read_enum_field(playback_state_var, "playback_state",
                                            playback_state_from_string)) {
        group_msg->group.playback_state = state;
    }

    // Parse optional group_id; use empty string to signal clearing
    JsonVariantConst group_id_var = root["payload"]["group_id"];
    if (group_id_var.is<const char*>()) {
        group_msg->group.group_id = group_id_var.as<std::string>();
    } else if (!group_id_var.isUnbound() && group_id_var.isNull()) {
        // Field set to null: use empty string to clear
        group_msg->group.group_id = "";
    }

    // Parse optional group_name; use empty string to signal clearing
    JsonVariantConst group_name_var = root["payload"]["group_name"];
    if (group_name_var.is<const char*>()) {
        group_msg->group.group_name = group_name_var.as<std::string>();
    } else if (!group_name_var.isUnbound() && group_name_var.isNull()) {
        // Field set to null: use empty string to clear
        group_msg->group.group_name = "";
    }

    return true;
}

// State delta application

void apply_group_update_deltas(GroupUpdateObject* current, const GroupUpdateObject& updates) {
    if (current == nullptr) {
        return;
    }

    // Update playback_state if present in the delta
    if (updates.playback_state.has_value()) {
        current->playback_state = updates.playback_state;
    }

    // Update group_id if present in the delta (including empty string for clearing)
    if (updates.group_id.has_value()) {
        current->group_id = updates.group_id;
    }

    // Update group_name if present in the delta (including empty string for clearing)
    if (updates.group_name.has_value()) {
        current->group_name = updates.group_name;
    }
}

bool process_server_command_message(JsonObject root, ServerCommandMessage* cmd_msg) {
    if (cmd_msg != nullptr && root["payload"]["player"].is<JsonObject>()) {
        ServerPlayerCommandObject player_cmd{};
        if (process_server_player_command_object(root["payload"]["player"], &player_cmd)) {
            cmd_msg->player = player_cmd;
            return true;
        }
        return false;
    }
    return true;
}

// A standalone section parser, like the server/state sections below, so a malformed source
// object never affects the player command the same message carries.
bool process_server_command_source(JsonObject root, SourceCommand* source_cmd) {
    if (source_cmd == nullptr || !root["payload"]["source"].is<JsonObject>()) {
        return false;
    }
    // roles/source/v1.md "server/command source object": command is required, with no default.
    auto command = read_enum_field(root["payload"]["source"]["command"], "command",
                                   source_command_from_string);
    if (!command) {
        SS_LOGW(TAG, "Rejecting server/command source object: missing or invalid 'command'");
        return false;
    }
    *source_cmd = command.value();
    return true;
}

// server/state is parsed per section rather than into a single aggregate struct. Each section
// here is an out-of-line function that fills a caller-owned struct directly, so no section's
// fields are materialized twice (once in a parser frame, once in the caller's). The caller keeps
// every section a role takes live together in its own frame for one parse of the document; that
// combined frame is on the protocol task, whose stack is bounded on ESP-IDF and covers it (see
// SendspinClientConfig::DEFAULT_PROTOCOL_TASK_STACK_SIZE).

bool process_server_state_metadata(JsonObject root, ServerMetadataStateObject* metadata) {
    if (metadata == nullptr || !root["payload"]["metadata"].is<JsonObject>()) {
        return false;
    }
    const JsonObject metadata_object = root["payload"]["metadata"];

    // roles/metadata/v1.md "server/state metadata object": timestamp is a required integer.
    const JsonVariantConst timestamp = metadata_object["timestamp"];
    if (!timestamp.is<int64_t>()) {
        SS_LOGE(TAG, "Invalid metadata state object: missing or non-integer timestamp");
        return false;
    }
    // messaging.md "server/state": every message carries the full state of each role object it
    // includes, so an included metadata object is parsed into a fresh state rather than overlaid
    // on what came before.
    *metadata = ServerMetadataStateObject{};
    metadata->timestamp = timestamp.as<int64_t>();

    parse_metadata_string_field(metadata_object["title"], "title", &metadata->title);
    parse_metadata_string_field(metadata_object["artist"], "artist", &metadata->artist);
    parse_metadata_string_field(metadata_object["album_artist"], "album_artist",
                                &metadata->album_artist);
    parse_metadata_string_field(metadata_object["album"], "album", &metadata->album);
    parse_metadata_string_field(metadata_object["artwork_url"], "artwork_url",
                                &metadata->artwork_url);
    if (auto v = read_uint_field<uint16_t>(metadata_object["year"], "year")) {
        metadata->year = v;
    }
    if (auto v = read_uint_field<uint16_t>(metadata_object["track"], "track")) {
        metadata->track = v;
    }

    // roles/metadata/v1.md "server/state metadata object": omitting progress clears the client's
    // position, which the full-state reset above already did.
    if (metadata_object["progress"].is<JsonObject>()) {
        JsonObject progress_object = metadata_object["progress"];
        MetadataProgressObject progress{};
        if (auto v =
                read_uint_field<uint32_t>(progress_object["track_progress"], "track_progress")) {
            progress.track_progress = *v;
        }
        if (auto v =
                read_uint_field<uint32_t>(progress_object["track_duration"], "track_duration")) {
            progress.track_duration = *v;
        }
        if (auto v =
                read_uint_field<uint32_t>(progress_object["playback_speed"], "playback_speed")) {
            progress.playback_speed = *v;
        }
        metadata->progress = progress;
    }

    return true;
}

bool process_server_state_color(JsonObject root, ServerColorStateObject* color) {
    if (color == nullptr || !root["payload"]["color"].is<JsonObject>()) {
        return false;
    }
    const JsonObject color_object = root["payload"]["color"];

    // roles/color/v1.md "server/state color object": timestamp is a required integer.
    const JsonVariantConst timestamp = color_object["timestamp"];
    if (!timestamp.is<int64_t>()) {
        SS_LOGE(TAG, "Invalid color state object: missing or non-integer timestamp");
        return false;
    }
    // messaging.md "server/state": an included color object carries the full palette, so it is
    // parsed into a fresh state.
    *color = ServerColorStateObject{};
    color->timestamp = timestamp.as<int64_t>();

    parse_color_field(color_object["background_dark"], "background_dark", &color->background_dark);
    parse_color_field(color_object["background_light"], "background_light",
                      &color->background_light);
    parse_color_field(color_object["primary"], "primary", &color->primary);
    parse_color_field(color_object["accent"], "accent", &color->accent);
    parse_color_field(color_object["on_dark"], "on_dark", &color->on_dark);
    parse_color_field(color_object["on_light"], "on_light", &color->on_light);

    return true;
}

bool process_server_state_controller(JsonObject root,
                                     ServerStateControllerObject* controller_state) {
    if (controller_state == nullptr || !root["payload"]["controller"].is<JsonObject>()) {
        return false;
    }
    const JsonObject controller_object = root["payload"]["controller"];

    // messaging.md "server/state": every message carries the full state of each role object it
    // includes, so an included controller object is parsed into a fresh state rather than
    // overlaid on what came before (matching the metadata and color parsers).
    *controller_state = ServerStateControllerObject{};

    // Parse supported_commands array. The controller role is frozen at v1, so an unrecognized
    // command is a non-compliant value rather than a forward-compatible one: drop and log it.
    if (controller_object["supported_commands"].is<JsonArray>()) {
        std::vector<SendspinControllerCommand> commands;
        JsonArrayConst commands_array =
            controller_object["supported_commands"].as<JsonArrayConst>();
        for (JsonVariantConst command_var : commands_array) {
            if (auto command = read_enum_field(command_var, "supported_commands",
                                               controller_command_from_string)) {
                commands.push_back(*command);
            }
        }
        controller_state->supported_commands = std::move(commands);
    }

    // Parse volume
    if (auto v = read_uint_field<uint8_t>(controller_object["volume"], "volume", 0, VOLUME_MAX)) {
        controller_state->volume = *v;
    }

    // Parse muted
    if (auto v = read_bool_field(controller_object["muted"], "muted")) {
        controller_state->muted = *v;
    }

    // Parse repeat
    if (auto repeat =
            read_enum_field(controller_object["repeat"], "repeat", repeat_mode_from_string)) {
        controller_state->repeat = *repeat;
    }

    // Parse shuffle
    if (auto v = read_bool_field(controller_object["shuffle"], "shuffle")) {
        controller_state->shuffle = *v;
    }

    // Parse seek_max_ms. Present only when the server offers 'seek' and the range is known;
    // left absent (nullopt) otherwise so consumers can distinguish "unknown range" from 0.
    if (auto v = read_uint_field<uint32_t>(controller_object["seek_max_ms"], "seek_max_ms")) {
        controller_state->seek_max_ms = v;
    }

    return true;
}

bool process_stream_start_message(JsonObject root, StreamStartMessage* stream_msg) {
    if (stream_msg == nullptr) {
        return true;
    }

    // Each section is parsed straight into the caller's struct rather than into a local that is
    // then moved in. Both copies would otherwise be live at once in this frame, which matters on
    // the ESP httpd task (4 KB stack); on a parse failure the section is reset instead.

    if (root["payload"]["player"].is<JsonObject>()) {
        ServerPlayerStreamObject& player_obj = stream_msg->player.emplace();
        if (!process_player_stream_object(root["payload"]["player"], &player_obj, true)) {
            stream_msg->player.reset();
            return false;
        }
        if (!player_obj.is_complete()) {
            SS_LOGE(TAG, "Invalid stream/start message: incomplete player object");
            stream_msg->player.reset();
            return false;
        }
    }

    if (root["payload"]["artwork"]["channels"].is<JsonArray>()) {
        std::vector<ServerArtworkChannelObject>& channels =
            stream_msg->artwork.emplace().channels.emplace();

        JsonArray channels_array = root["payload"]["artwork"]["channels"].as<JsonArray>();
        for (JsonObject channel_json : channels_array) {
            ServerArtworkChannelObject channel{};
            if (process_artwork_channel_object(channel_json, &channel, true)) {
                if (!channel.is_complete()) {
                    SS_LOGE(TAG, "Invalid stream/start message: incomplete artwork channel");
                    stream_msg->artwork.reset();
                    return false;
                }
                channels.push_back(channel);
            } else {
                stream_msg->artwork.reset();
                return false;
            }
        }
    }

    if (root["payload"]["visualizer"].is<JsonObject>()) {
        ServerVisualizerStreamObject& vis_obj = stream_msg->visualizer.emplace();
        JsonObject vis_json = root["payload"]["visualizer"];

        // Parse types array
        if (vis_json["types"].is<JsonArray>()) {
            JsonArray types_array = vis_json["types"].as<JsonArray>();
            for (JsonVariant type_var : types_array) {
                if (auto type =
                        read_enum_field(type_var, "types", visualizer_data_type_from_string)) {
                    vis_obj.types.push_back(*type);
                }
            }
        }

        if (auto v = read_uint_field<uint16_t>(vis_json["rate_max"], "rate_max")) {
            vis_obj.rate_max = *v;
        }

        if (auto v = read_bool_field(vis_json["tracks_downbeats"], "tracks_downbeats")) {
            vis_obj.tracks_downbeats = *v;
        }

        // Parse spectrum config if present. Every field is required by the spec; if any is
        // absent or malformed, leave the config unset so the SPECTRUM validation below rejects
        // the stream rather than acting on a fabricated default.
        if (vis_json["spectrum"].is<JsonObject>()) {
            JsonObject spec_json = vis_json["spectrum"];
            auto n_disp_bins = read_uint_field<uint8_t>(spec_json["n_disp_bins"], "n_disp_bins", 1);
            auto scale =
                read_enum_field(spec_json["scale"], "scale", visualizer_spectrum_scale_from_string);
            auto f_min = read_uint_field<uint16_t>(spec_json["f_min"], "f_min");
            auto f_max = read_uint_field<uint16_t>(spec_json["f_max"], "f_max");
            if (n_disp_bins && scale && f_min && f_max) {
                vis_obj.spectrum = VisualizerSpectrumConfig{*n_disp_bins, *scale, *f_min, *f_max};
            }
        }

        // If SPECTRUM is advertised, a fully valid spectrum config must be present; otherwise the
        // expected size of binary spectrum messages is indeterminate and they could not be
        // validated. The defect is local to the visualizer object, so drop only that object and
        // let a well-formed player/artwork start in the same message go through.
        bool advertises_spectrum = false;
        for (auto type : vis_obj.types) {
            if (type == VisualizerDataType::SPECTRUM) {
                advertises_spectrum = true;
                break;
            }
        }
        if (advertises_spectrum && !vis_obj.spectrum.has_value()) {
            SS_LOGE(TAG, "Ignoring visualizer stream config: SPECTRUM advertised without valid "
                         "spectrum config");
            stream_msg->visualizer.reset();
        }
    }

    return true;
}

bool process_stream_end_message(JsonObject root, StreamEndMessage* end_msg) {
    if (end_msg == nullptr) {
        return true;
    }

    // Parse optional roles array
    if (root["payload"]["roles"].is<JsonArray>()) {
        std::vector<std::string> roles;
        JsonArray roles_array = root["payload"]["roles"].as<JsonArray>();
        for (JsonVariant role_var : roles_array) {
            if (role_var.is<const char*>()) {
                roles.push_back(role_var.as<std::string>());
            }
        }
        end_msg->roles = std::move(roles);
    }

    return true;
}

bool process_stream_clear_message(JsonObject root, StreamClearMessage* clear_msg) {
    if (clear_msg == nullptr) {
        return true;
    }

    // Parse optional roles array
    if (root["payload"]["roles"].is<JsonArray>()) {
        std::vector<std::string> roles;
        JsonArray roles_array = root["payload"]["roles"].as<JsonArray>();
        for (JsonVariant role_var : roles_array) {
            if (role_var.is<const char*>()) {
                roles.push_back(role_var.as<std::string>());
            }
        }
        clear_msg->roles = std::move(roles);
    }

    return true;
}

// Message formatting

std::string format_client_hello_message(const ClientHelloMessage* msg,
                                        SendspinArenaAllocator& arena) {
    JsonDocument doc = make_json_document(arena);
    JsonObject root = doc.to<JsonObject>();

    root["type"] = "client/hello";
    // Under encryption: client_id and version are carried in client/init, not repeated here.
    root["payload"]["name"] = msg->name;
    if (msg->device_info.has_value()) {
        const auto& info = msg->device_info.value();
        if (info.product_name.has_value()) {
            root["payload"]["device_info"]["product_name"] = info.product_name.value();
        }
        if (info.manufacturer.has_value()) {
            root["payload"]["device_info"]["manufacturer"] = info.manufacturer.value();
        }
        if (info.software_version.has_value()) {
            root["payload"]["device_info"]["software_version"] = info.software_version.value();
        }
        if (info.mac_address.has_value()) {
            root["payload"]["device_info"]["mac_address"] = info.mac_address.value();
        }
    }
    // pairing.md "client/hello pair-method descriptor": supported_pair_methods is an object keyed
    // by pairing method identifier, each value the method's descriptor. It is REQUIRED (every
    // client implements at least pairing_psk, messaging.md "client/hello"), so the object is
    // emitted even for an empty list.
    {
        JsonObject methods_obj = root["payload"]["supported_pair_methods"].to<JsonObject>();
        for (const auto& desc : msg->supported_pair_methods) {
            JsonObject method_obj = methods_obj[to_cstr(desc.method)].to<JsonObject>();
            const auto& out_channels = desc.out_channels;
            if (out_channels.has_value() && !out_channels->empty()) {
                JsonArray ch_arr = method_obj["out_channels"].to<JsonArray>();
                for (const auto& ch : out_channels.value()) {
                    ch_arr.add(to_cstr(ch));
                }
            }
            const auto& formats = desc.formats;
            if (formats.has_value() && !formats->empty()) {
                JsonArray fmt_arr = method_obj["formats"].to<JsonArray>();
                for (const auto& fmt : formats.value()) {
                    fmt_arr.add(to_cstr(fmt));
                }
            }
            const auto& locations = desc.locations;
            if (locations.has_value() && !locations->empty()) {
                JsonArray loc_arr = method_obj["locations"].to<JsonArray>();
                for (const auto& loc : locations.value()) {
                    loc_arr.add(loc.c_str());
                }
            }
        }
    }
    root["payload"]["unpaired_access"]["enabled"] = msg->unpaired_access_enabled;
    JsonArray supported_roles_list = root["payload"]["supported_roles"].to<JsonArray>();
    for (const auto& role : msg->supported_roles) {
        supported_roles_list.add(to_cstr(role));
    }

    if (msg->player_v1_support.has_value()) {
        JsonArray formats_list =
            root["payload"]["player@v1_support"]["supported_formats"].to<JsonArray>();
        for (const auto& format : msg->player_v1_support.value().supported_formats) {
            JsonObject format_obj = formats_list.add<JsonObject>();
            format_obj["codec"] = to_cstr(format.codec);
            format_obj["channels"] = format.channels;
            format_obj["sample_rate"] = format.sample_rate;
            format_obj["bit_depth"] = format.bit_depth;
        }
        root["payload"]["player@v1_support"]["buffer_capacity"] =
            msg->player_v1_support.value().buffer_capacity;
    }

    if (msg->visualizer_support.has_value()) {
        // roles/visualizer/v1.md "client/hello visualizer@v1 support object": buffer_capacity is
        // the object's only field; the data types, frame-rate cap and spectrum configuration are
        // dynamic and reported in the client/state visualizer object.
        root["payload"]["visualizer@v1_support"]["buffer_capacity"] =
            msg->visualizer_support.value().buffer_capacity;
    }

    // messaging.md "client/hello" requires the object whenever source@v1 is listed; line_sense is
    // emitted only when set (roles/source/v1.md "client/hello source@v1 support object").
    if (msg->source_v1_support.has_value()) {
        JsonObject source_json = root["payload"]["source@v1_support"].to<JsonObject>();
        if (msg->source_v1_support.value().line_sense) {
            source_json["features"]["line_sense"] = true;
        }
    }

    std::string output;
    serializeJson(doc, output);
    return output;
}

std::string format_client_state_message(const ClientStateMessage* msg,
                                        SendspinArenaAllocator& arena) {
    JsonDocument doc = make_json_document(arena);
    JsonObject root = doc.to<JsonObject>();

    root["type"] = "client/state";
    // messaging.md "External Source Handling": false means the client will not yield to Sendspin.
    root["payload"]["available"] = msg->available;

    if (msg->player.has_value()) {
        const ClientPlayerStateObject& player_state = msg->player.value();
        root["payload"]["player"]["volume"] = player_state.volume;
        root["payload"]["player"]["muted"] = player_state.muted;
        root["payload"]["player"]["output_delay_ms"] = player_state.output_delay_ms;
        root["payload"]["player"]["required_lead_time_ms"] = player_state.required_lead_time_ms;
        root["payload"]["player"]["min_buffer_ms"] = player_state.min_buffer_ms;
        // roles/player/v1.md "client/state player object": supported_commands is a required key
        // that is empty when the player accepts no commands, so the array is emitted even then.
        JsonArray commands_list = root["payload"]["player"]["supported_commands"].to<JsonArray>();
        for (const auto& cmd : player_state.supported_commands) {
            commands_list.add(to_cstr(cmd));
        }
    }

    if (msg->artwork.has_value()) {
        JsonArray channels_list = root["payload"]["artwork"]["channels"].to<JsonArray>();
        for (const auto& channel : msg->artwork.value().channels) {
            JsonObject channel_obj = channels_list.add<JsonObject>();
            channel_obj["source"] = to_cstr(channel.source);
            // roles/artwork/v1.md "client/state artwork object": format, width and height are
            // required unless the channel's source is 'none'.
            if (channel.source != SendspinImageSource::NONE) {
                channel_obj["format"] = to_cstr(channel.format);
                channel_obj["width"] = channel.width;
                channel_obj["height"] = channel.height;
            }
        }
    }

    if (msg->visualizer.has_value()) {
        const auto& vis = msg->visualizer.value();
        JsonObject vis_json = root["payload"]["visualizer"].to<JsonObject>();
        // roles/visualizer/v1.md "client/state visualizer object": types and rate_max are always
        // present (types may be empty to request no data), spectrum only with that type.
        JsonArray types_list = vis_json["types"].to<JsonArray>();
        for (const auto& type : vis.types) {
            types_list.add(to_cstr(type));
        }
        vis_json["rate_max"] = vis.rate_max;
        if (vis.spectrum.has_value()) {
            const VisualizerSpectrumConfig& spec = vis.spectrum.value();
            vis_json["spectrum"]["n_disp_bins"] = spec.n_disp_bins;
            vis_json["spectrum"]["scale"] = to_cstr(spec.scale);
            vis_json["spectrum"]["f_min"] = spec.f_min;
            vis_json["spectrum"]["f_max"] = spec.f_max;
        }
    }

    // signal only when set.
    if (msg->source.has_value()) {
        JsonObject source_json = root["payload"]["source"].to<JsonObject>();
        if (msg->source.value().signal.has_value()) {
            source_json["signal"] = to_cstr(msg->source.value().signal.value());
        }
    }

    std::string output;
    serializeJson(doc, output);
    return output;
}

std::string format_client_stream_start_message(const ClientStreamStartMessage* msg,
                                               SendspinArenaAllocator& arena) {
    JsonDocument doc = make_json_document(arena);
    JsonObject root = doc.to<JsonObject>();

    // roles/source/v1.md "client-stream/start": bit_depth is required even for opus, which ignores
    // it.
    root["type"] = "client-stream/start";
    JsonObject source_json = root["payload"]["source"].to<JsonObject>();
    source_json["codec"] = to_cstr(msg->codec);
    source_json["channels"] = msg->channels;
    source_json["sample_rate"] = msg->sample_rate;
    source_json["bit_depth"] = msg->bit_depth;
    if (msg->codec_header.has_value()) {
        source_json["codec_header"] = msg->codec_header.value();
    }

    std::string output;
    serializeJson(doc, output);
    return output;
}

std::string format_client_stream_end_message(SendspinArenaAllocator& arena) {
    JsonDocument doc = make_json_document(arena);
    JsonObject root = doc.to<JsonObject>();

    root["type"] = "client-stream/end";
    // roles/source/v1.md "client-stream/end": no payload fields, but the envelope carries a payload
    // object like every other message.
    root["payload"].to<JsonObject>();

    std::string output;
    serializeJson(doc, output);
    return output;
}

std::string format_client_leave_message(SendspinArenaAllocator& arena) {
    JsonDocument doc = make_json_document(arena);
    JsonObject root = doc.to<JsonObject>();

    root["type"] = "client/leave";
    // messaging.md "client/leave": no payload fields, but the envelope carries a payload object
    // like every other message.
    root["payload"].to<JsonObject>();

    std::string output;
    serializeJson(doc, output);
    return output;
}

std::string format_client_goodbye_message(SendspinGoodbyeReason reason,
                                          SendspinArenaAllocator& arena) {
    JsonDocument doc = make_json_document(arena);
    JsonObject root = doc.to<JsonObject>();

    root["type"] = "client/goodbye";
    root["payload"]["reason"] = to_cstr(reason);

    std::string output;
    serializeJson(doc, output);
    return output;
}

namespace {

/// Maximum decimal digits in a uint64_t value.
constexpr int MAX_UINT64_DIGITS = 19;

/// Radix used for two-digit-at-a-time integer formatting.
constexpr uint64_t RADIX_100 = 100U;

/// Threshold for single vs. two-digit final write.
constexpr uint64_t RADIX_10 = 10U;

// Two-digit ASCII lookup. Index 2*N..2*N+1 holds the decimal digits of N for N in [0, 99].
// Lets us emit two characters per 64-bit division instead of one, halving the libgcc
// __udivdi3 calls, which are the expensive part on a 32-bit MCU.
constexpr char TWO_DIGIT_TABLE[201] = "00010203040506070809"
                                      "10111213141516171819"
                                      "20212223242526272829"
                                      "30313233343536373839"
                                      "40414243444546474849"
                                      "50515253545556575859"
                                      "60616263646566676869"
                                      "70717273747576777879"
                                      "80818283848586878889"
                                      "90919293949596979899";

// log10(v) + 1, computed without any division. Uses clz to get an approximate base-10 length
// from the base-2 length, then a single table comparison to round to the exact value.
inline int decimal_digits(uint64_t v) {
    if (v == 0U) {
        return 1;
    }
    // floor(log2(v)) for v != 0
    const int log2 = 63 - __builtin_clzll(v);
    // floor(log10(v)) ≈ floor(log2(v) * log10(2)). 1233 / 4096 ≈ 0.30108, accurate to within 1.
    const int approx = (log2 * 1233) >> 12;
    static constexpr uint64_t POW10[20] = {1ULL,
                                           10ULL,
                                           100ULL,
                                           1000ULL,
                                           10000ULL,
                                           100000ULL,
                                           1000000ULL,
                                           10000000ULL,
                                           100000000ULL,
                                           1000000000ULL,
                                           10000000000ULL,
                                           100000000000ULL,
                                           1000000000000ULL,
                                           10000000000000ULL,
                                           100000000000000ULL,
                                           1000000000000000ULL,
                                           10000000000000000ULL,
                                           100000000000000000ULL,
                                           1000000000000000000ULL,
                                           10000000000000000000ULL};
    return approx + 1 + static_cast<int>(v >= POW10[approx + 1]);
}

}  // namespace

size_t format_client_time_message(char* buf, size_t cap, int64_t client_transmitted) {
    // Hot path on the time-sync send side. ESP-IDF newlib's snprintf("%lld", ...) costs ~50us
    // for a single int64 because it instantiates a full printf state machine and does 64-bit
    // division through generic code. We bypass it entirely: memcpy the two constant chunks
    // around a hand-rolled int64-to-decimal conversion that uses clz for digit count and a
    // two-digit-at-a-time write to halve the 64-bit division count.
    static constexpr char PREFIX[] = R"({"type":"client/time","payload":{"client_transmitted":)";
    static constexpr size_t PREFIX_LEN = sizeof(PREFIX) - 1;
    static constexpr char SUFFIX[] = "}}";
    static constexpr size_t SUFFIX_LEN = sizeof(SUFFIX) - 1;

    // Worst case: prefix + '-' + MAX_UINT64_DIGITS digits + suffix.
    if (cap < PREFIX_LEN + 1 + MAX_UINT64_DIGITS + SUFFIX_LEN) {
        return 0;
    }

    char* p = buf;
    std::memcpy(p, PREFIX, PREFIX_LEN);
    p += PREFIX_LEN;

    uint64_t v = 0;
    if (client_transmitted < 0) {
        *p++ = '-';
        // Cast through uint64_t to handle INT64_MIN without UB
        v = static_cast<uint64_t>(-(client_transmitted + 1)) + 1U;
    } else {
        v = static_cast<uint64_t>(client_transmitted);
    }

    // Place the digit cursor at the end of the integer field and write backwards, two digits
    // per loop iteration. The clz-based digit count means we know exactly where to start.
    const int n = decimal_digits(v);
    char* end = p + n;
    char* w = end;
    while (v >= RADIX_100) {
        const uint64_t q = v / RADIX_100;
        const auto r = static_cast<size_t>(v - q * RADIX_100);
        w -= 2;
        std::memcpy(w, &TWO_DIGIT_TABLE[r * 2U], 2);
        v = q;
    }
    if (v >= RADIX_10) {
        w -= 2;
        std::memcpy(w, &TWO_DIGIT_TABLE[static_cast<size_t>(v) * 2U], 2);
    } else {
        w -= 1;
        *w = static_cast<char>('0' + v);
    }
    p = end;

    std::memcpy(p, SUFFIX, SUFFIX_LEN);
    p += SUFFIX_LEN;

    return static_cast<size_t>(p - buf);
}

std::string format_client_command_message(const ClientCommandControllerObject& cmd,
                                          SendspinArenaAllocator& arena) {
    JsonDocument doc = make_json_document(arena);
    JsonObject root = doc.to<JsonObject>();

    root["type"] = "client/command";
    JsonObject controller = root["payload"]["controller"].to<JsonObject>();
    controller["command"] = to_cstr(cmd.command);
    if (cmd.command == SendspinControllerCommand::VOLUME && cmd.volume.has_value()) {
        controller["volume"] = cmd.volume.value();
    }
    if (cmd.command == SendspinControllerCommand::MUTE && cmd.muted.has_value()) {
        controller["mute"] = cmd.muted.value();
    }
    if (cmd.command == SendspinControllerCommand::SEEK && cmd.position_ms.has_value()) {
        controller["position_ms"] = cmd.position_ms.value();
    }
    if (cmd.command == SendspinControllerCommand::SEEK_RELATIVE && cmd.offset_ms.has_value()) {
        controller["offset_ms"] = cmd.offset_ms.value();
    }

    std::string output;
    serializeJson(doc, output);
    return output;
}

// ============================================================================
// Pairing-PSK protocol messages
// ============================================================================

std::string format_client_pair_finalize_message(const std::array<uint8_t, 32>& psk,
                                                SendspinArenaAllocator& arena) {
    // The document holds the base64 long-term PSK, which admits a server permanently; the arena
    // wipes every block it frees, and the caller wipes the returned string once it has been sent
    // (see handle_enter_pairing_psk()). The encoded copy staged on the way is wiped here.
    JsonDocument doc = make_json_document(arena);
    JsonObject root = doc.to<JsonObject>();

    root["type"] = "client/pair-finalize";
    // Encode the 32-byte PSK as 43-char base64url (no padding).
    std::string psk_b64 = b64url_encode(psk.data(), psk.size());
    root["payload"]["long_term_psk"] = psk_b64;
    secure_zero(psk_b64.data(), psk_b64.size());

    // Reserved to the exact length so serializeJson() writes in place: a growing string would
    // leave PSK-bearing copies behind in the heap blocks it outgrew, which nothing wipes.
    std::string output;
    output.reserve(measureJson(doc));
    serializeJson(doc, output);
    return output;
}

std::string format_client_pair_finalize_wrapped_message(const std::array<uint8_t, 48>& wrapped_psk,
                                                        SendspinArenaAllocator& arena) {
    // Wiped like format_client_pair_finalize_message(); see the note there.
    JsonDocument doc = make_json_document(arena);
    JsonObject root = doc.to<JsonObject>();

    root["type"] = "client/pair-finalize";
    // Pairing-code flows only (pairing.md "Wrapping"); exactly one of long_term_psk/wrapped_psk
    // is present per message.
    std::string wrapped_b64 = b64url_encode(wrapped_psk.data(), wrapped_psk.size());
    root["payload"]["wrapped_psk"] = wrapped_b64;
    secure_zero(wrapped_b64.data(), wrapped_b64.size());

    // Reserved to the exact length, as in format_client_pair_finalize_message().
    std::string output;
    output.reserve(measureJson(doc));
    serializeJson(doc, output);
    return output;
}

std::string format_pair_abort_message(PairAbortReason reason, SendspinArenaAllocator& arena) {
    JsonDocument doc = make_json_document(arena);
    JsonObject root = doc.to<JsonObject>();

    root["type"] = "pair/abort";
    root["payload"]["reason"] = to_cstr(reason);

    std::string output;
    serializeJson(doc, output);
    return output;
}

bool process_pair_abort_message(JsonObject root, PairAbortMessage* abort_msg) {
    if (!root["payload"]["reason"].is<const char*>()) {
        SS_LOGE(TAG, "Invalid pair/abort message: missing reason");
        return false;
    }

    if (abort_msg == nullptr) {
        return true;
    }

    const std::string reason_str = root["payload"]["reason"].as<std::string>();
    auto reason = pair_abort_reason_from_string(reason_str);
    if (!reason.has_value()) {
        SS_LOGW(TAG, "pair/abort: unrecognized reason '%s'", reason_str.c_str());
        return false;
    }

    abort_msg->reason = reason.value();
    return true;
}

// ============================================================================
// Pairing-code protocol functions
// ============================================================================

bool process_server_pair_init_message(JsonObject root, ServerPairInitPayload* payload) {
    // nonce_A (base64url, 43 chars -> 32 bytes) is present in the attempt's first round only, so
    // an absent field parses to an absent value; the state machine decides whether that is right
    // for the round it is in (pairing.md "Rounds").
    JsonVariantConst nonce_var = root["payload"]["nonce_A"];
    if (nonce_var.isUnbound() || nonce_var.isNull()) {
        if (payload != nullptr) {
            payload->nonce_a = std::nullopt;
        }
        return true;
    }
    if (!nonce_var.is<const char*>()) {
        SS_LOGE(TAG, "server/pair-init: nonce_A is not a string");
        return false;
    }
    auto decoded = b64url_decode(nonce_var.as<std::string>());
    if (!decoded.has_value() || decoded->size() != 32) {
        SS_LOGE(TAG, "server/pair-init: nonce_A is not 32 base64url-encoded bytes");
        return false;
    }
    if (payload == nullptr) {
        return true;
    }
    std::array<uint8_t, 32> nonce_a{};
    std::memcpy(nonce_a.data(), decoded->data(), nonce_a.size());
    payload->nonce_a = nonce_a;
    return true;
}

bool process_server_pair_auth_message(JsonObject root, ServerPairAuthPayload* payload) {
    if (!root["payload"]["pake_msg_1"].is<const char*>()) {
        SS_LOGE(TAG, "server/pair-auth: missing pake_msg_1");
        return false;
    }

    if (payload == nullptr) {
        return true;
    }

    const std::string msg1_b64 = root["payload"]["pake_msg_1"].as<std::string>();
    auto msg1_bytes = b64url_decode(msg1_b64);
    if (!msg1_bytes.has_value() || msg1_bytes->size() != 32) {
        SS_LOGE(TAG, "server/pair-auth: pake_msg_1 is not 32 bytes");
        return false;
    }
    std::memcpy(payload->pake_msg_1.data(), msg1_bytes->data(), 32);
    return true;
}

bool process_server_pair_confirm_message(JsonObject root, ServerPairConfirmPayload* payload) {
    if (!root["payload"]["server_kc"].is<const char*>()) {
        SS_LOGE(TAG, "server/pair-confirm: missing server_kc");
        return false;
    }

    if (payload == nullptr) {
        return true;
    }

    const std::string kc_b64 = root["payload"]["server_kc"].as<std::string>();
    auto kc_bytes = b64url_decode(kc_b64);
    if (!kc_bytes.has_value() || kc_bytes->size() != 64) {
        SS_LOGE(TAG, "server/pair-confirm: server_kc is not 64 bytes");
        return false;
    }
    std::memcpy(payload->server_kc.data(), kc_bytes->data(), 64);
    return true;
}

std::string format_client_pair_pending_message(uint32_t pairing_index,
                                               SendspinArenaAllocator& arena) {
    JsonDocument doc = make_json_document(arena);
    JsonObject root = doc.to<JsonObject>();

    root["type"] = "client/pair-pending";
    root["payload"]["pairing_index"] = pairing_index;

    std::string output;
    serializeJson(doc, output);
    return output;
}

std::string format_client_pair_init_message(const std::array<uint8_t, 32>& commit_b,
                                            uint32_t pairing_index, SendspinArenaAllocator& arena) {
    JsonDocument doc = make_json_document(arena);
    JsonObject root = doc.to<JsonObject>();

    root["type"] = "client/pair-init";
    root["payload"]["commit_B"] = b64url_encode(commit_b.data(), commit_b.size());
    root["payload"]["pairing_index"] = pairing_index;

    std::string output;
    serializeJson(doc, output);
    return output;
}

std::string format_client_pair_init_message(uint32_t pairing_index, SendspinArenaAllocator& arena) {
    JsonDocument doc = make_json_document(arena);
    JsonObject root = doc.to<JsonObject>();

    root["type"] = "client/pair-init";
    // commit_B belongs to the dynamic pairing code flow only; pairing_index is required on
    // every client/pair-init (pairing.md "client/pair-init").
    root["payload"]["pairing_index"] = pairing_index;

    std::string output;
    serializeJson(doc, output);
    return output;
}

std::string format_client_pair_retry_message(SendspinArenaAllocator& arena) {
    JsonDocument doc = make_json_document(arena);
    JsonObject root = doc.to<JsonObject>();

    root["type"] = "client/pair-retry";
    // Empty payload, emitted explicitly so the message carries the object every other pairing
    // message does (pairing.md "Client -> Server: client/pair-retry").
    root["payload"].to<JsonObject>();

    std::string output;
    serializeJson(doc, output);
    return output;
}

std::string format_client_pair_auth_message(const std::array<uint8_t, 32>& pake_msg_2,
                                            SendspinArenaAllocator& arena) {
    JsonDocument doc = make_json_document(arena);
    JsonObject root = doc.to<JsonObject>();

    root["type"] = "client/pair-auth";
    root["payload"]["pake_msg_2"] = b64url_encode(pake_msg_2.data(), pake_msg_2.size());

    std::string output;
    serializeJson(doc, output);
    return output;
}

std::string format_client_pair_confirm_message(
    const std::array<uint8_t, 64>& client_kc,
    const std::array<uint8_t, WRAPPED_VALUE_SIZE>& wrapped_nonce_b, SendspinArenaAllocator& arena) {
    JsonDocument doc = make_json_document(arena);
    JsonObject root = doc.to<JsonObject>();

    root["type"] = "client/pair-confirm";
    root["payload"]["client_kc"] = b64url_encode(client_kc.data(), client_kc.size());
    // The commitment opening crosses the wire sealed under the CPace output, never in the clear
    // (pairing.md "Wrapping"): an observer that cannot complete the PAKE cannot reconstruct the
    // pairing code from the handshake it watched.
    root["payload"]["wrapped_nonce_B"] =
        b64url_encode(wrapped_nonce_b.data(), wrapped_nonce_b.size());

    std::string output;
    serializeJson(doc, output);
    return output;
}

std::string format_client_pair_confirm_message(const std::array<uint8_t, 64>& client_kc,
                                               SendspinArenaAllocator& arena) {
    JsonDocument doc = make_json_document(arena);
    JsonObject root = doc.to<JsonObject>();

    root["type"] = "client/pair-confirm";
    // The static flow sends no commit_B, so it has no opening to wrap.
    root["payload"]["client_kc"] = b64url_encode(client_kc.data(), client_kc.size());

    std::string output;
    serializeJson(doc, output);
    return output;
}

}  // namespace sendspin
