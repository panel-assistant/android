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

/// @file network_info.h
/// @brief Platform-abstracted detection of the local network interface MAC address

#pragma once

#include <optional>
#include <string>

namespace sendspin {

/// @brief Best-effort lookup of the local MAC address used for Sendspin connections.
///
/// Returned as lowercase colon-separated form (e.g., "aa:bb:cc:dd:ee:ff"), suitable
/// for the `client/hello` `device_info.mac_address` field. Returns std::nullopt when no
/// suitable interface can be determined.
///
/// Platform behaviour:
/// - ESP-IDF: returns the MAC of the default network interface, so it resolves correctly whether
///   the device is on Wi-Fi or Ethernet. Falls back to the factory Wi-Fi STA MAC if no default
///   interface is up yet.
/// - Host: returns the MAC of the first active non-loopback interface that also carries a routable
///   (non-link-local) IP address. This is a heuristic (the "primary" interface), not necessarily
///   the interface a given connection is bound to; on multi-homed hosts it may differ. Prefer
///   setting SendspinClientConfig::mac_address explicitly when the exact interface matters.
std::optional<std::string> platform_get_interface_mac();

}  // namespace sendspin
