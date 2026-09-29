package io.github.maxlyth.hapaneld.http

import io.github.maxlyth.hapaneld.device.profile.PhysicalDisplayGeometry
import io.github.maxlyth.hapaneld.i18n.Strings as AppStrings
import io.github.maxlyth.hapaneld.util.isRoutable

// Panel-info rows blurred by default (screenshot hygiene) — identity + network values a casual share
// shouldn't leak. "Reveal" un-blurs them. Not access control: the values are still in the page source.
private val SECRET_FIELDS = setOf("Device ID", "MQTT")
// Address rows blur ONLY when the value is globally ROUTABLE — an unroutable RFC1918 / ULA / link-local
// address (e.g. the LAN IPv4, or a ULA v6) has no external use, so it stays visible.
private val ADDRESS_FIELDS = setOf("Local IP", "Local IPv6")

internal fun factLabel(key: String, strings: AppStrings): String {
    val suffix = when (key) {
        "panel_id" -> "panel_id"
        "Android" -> "android"
        "Firmware" -> "firmware"
        "Device" -> "device"
        "Device ID" -> "device_id"
        "CPU" -> "cpu"
        "RAM" -> "ram"
        "Storage" -> "storage"
        "Display" -> "display"
        "System WebView" -> "system_webview"
        "HA Companion" -> "ha_companion"
        "Friendly name" -> "friendly_name"
        "HTTP port" -> "http_port"
        "Local IP" -> "local_ip"
        "Local IPv6" -> "local_ipv6"
        "MQTT" -> "mqtt"
        "MQTT state" -> "mqtt_timing"
        "Security mode" -> "security_mode"
        "mDNS" -> "mdns"
        "Platform" -> "platform"
        "SoC" -> "soc"
        "Model" -> "model"
        "LED" -> "led"
        "Light sensor" -> "light_sensor"
        "Proximity" -> "proximity"
        "Navbar" -> "navbar"
        "Zigbee" -> "zigbee"
        "Relays" -> "relays"
        "CPU profile" -> "cpu_profile"
        "Network ADB" -> "network_adb"
        "Log shipping" -> "log_shipping"
        "Audio playback" -> "audio_playback"
        "App database" -> "app_database"
        "Wi-Fi stability" -> "wifi_stability"
        "HA network path" -> "ha_network_path"
        "HA renderer" -> "ha_renderer"
        "State convergence" -> "state_convergence"
        "Local-state sync" -> "local_state_sync"
        "Camera" -> "camera"
        "HA lifecycle" -> "ha_lifecycle"
        "System WebView reporting" -> "webview_reporting"
        else -> return key
    }
    return strings.get("dashboard.fact.$suffix")
}

/** Table rows for one facts card (Panel information / Networking / ha-paneld profile). */
internal fun factRowsHtml(
    facts: Map<String, String>,
    keys: List<String>,
    webViewTooOld: Boolean,
    strings: AppStrings,
    displayCell: (String) -> String,
): String {
    return keys.filter { facts.containsKey(it) }.joinToString("\n") { k ->
        val v = facts.getValue(k)
        // Version: plain text + a compact GitHub releases icon (a hyperlinked version reads ugly).
        val cell = if (k == "ha-paneld") {
            """${esc(v)}&nbsp;<a class="gh gh-inline" href="$RELEASES_URL" target="_blank" rel="noopener" """ +
                """title="${esc(strings.get("dashboard.fact.releases_on_github"))}" aria-label="${esc(strings.get("dashboard.fact.releases_on_github"))}"><svg viewBox="0 0 24 24"><path d="$GH_ICON"/></svg></a>"""
        } else if (k == "Display") {
            displayCell(v)
        } else if (k == "System WebView" && webViewTooOld) {
            """<span style="color:#f5c451">${esc(v)} ⚠</span>"""
        } else if (k in SECRET_FIELDS || (k in ADDRESS_FIELDS && isRoutable(v))) {
            // Blurred by default so a casual screenshot doesn't leak it; "Reveal" un-blurs (screenshot
            // hygiene, not access control — the value is still in the page source).
            """<span class="secret">${esc(v)}</span>"""
        } else {
            esc(v)
        }
        // Facts backed by a setting get the ✎ marker (configurable vs static at a glance),
        // deep-linking to the exact row on the Configure tab.
        val edit = FACT_CFG[k]?.let { cfgIcon(it, strings) } ?: ""
        "<tr><th>${esc(factLabel(k, strings))}</th><td>$cell$edit</td></tr>"
    }
}

internal fun capRowsHtml(capabilities: List<DiagReader.Cap>, strings: AppStrings): String {
    val capColor = mapOf("ok" to "#48c774", "degraded" to "#d9a528", "none" to "#d04a3b")
    return capabilities.joinToString("\n") { c ->
        val col = capColor[c.status] ?: "#888"
        """<tr><th>${esc(capabilityName(c.name, strings))}</th><td><span style="color:$col">●</span> ${esc(capabilityNote(c.note, strings))}</td></tr>"""
    }
}

/** Appends physical dimensions only when profile evidence selects this panel's physical geometry.
 *  Logical density is a layout setting and must never be used to infer the panel's physical size. */
internal fun displayCell(v: String, size: PhysicalDisplayGeometry): String {
    val inchS = "%.1f".format(size.diagonalInches)
    val cmS = "%.1f".format(size.diagonalInches * 2.54)
    val title = "W %.1f × H %.1f cm".format(size.widthMm / 10, size.heightMm / 10)
    return """${esc(v)} · <span class="diag" data-in="$inchS″" data-cm="$cmS cm" """ +
        """title="${esc(title)}" onclick="diagToggle(this)">$inchS″</span>"""
}

// Dashboard fact rows that are BACKED BY A SETTING → the Configure anchor the ✎ marker
// deep-links to. Facts absent here are static (hardware/runtime) and get no marker.
private val FACT_CFG = mapOf(
    "panel_id" to "cfg-panel_id",
    "Friendly name" to "cfg-friendly_name",
    "MQTT" to "cfg-mqtt_broker",
    "Navbar" to "cfg-navbar_mode",
    "Zigbee" to "cfg-zigbee_router",
    "CPU profile" to "cfg-cpu_governor",
    "Network ADB" to "cfg-network_adb",
    "Log shipping" to "cfg-log_ship_enabled",
)
private const val RELEASES_URL = "https://github.com/panel-assistant/android/releases"
