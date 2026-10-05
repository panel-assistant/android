package io.panelassistant.android.sensors

import org.json.JSONArray
import org.json.JSONObject

/**
 * The one definition of which Home Assistant device rows are this panel. Presence discovery, the
 * presence Area prerequisite and the Area catalogue all ask it; two copies of this rule drifted once
 * already, when a panel registered only by Panel Assistant matched in neither.
 *
 * A panel can be registered by two integrations. The MQTT bridge registers identifiers the panel
 * publishes itself. Panel Assistant registers one config-entry-scoped identifier,
 * `(panel_assistant, <entry_id>)`, whose value the panel is never told: the `hello` reply omits it and
 * Core's config-entry listing omits the entry's `unique_id`. What the panel does know is its discovery
 * id, which is that entry's `unique_id` and the prefix of every native entity Panel Assistant creates
 * for it (`<did>_<suffix>`). So the entry is proven through those entities' full registry rows, read
 * with [PROBE_COMMAND], and the device is then matched by its identifier.
 *
 * Identity tiers, strongest first; the first tier that matches anything decides:
 * 1. MQTT `ha-paneld-uid-<deviceUid>`, minted at random per installation and never shared.
 * 2. `(panel_assistant, <entry_id>)` for an entry proven to be this panel's discovery id. The
 *    discovery id derives from the Android ID, which a cloned factory image can share, so it ranks
 *    below the minted identity.
 * 3. MQTT `ha-paneld-<panelId>`, the historical identifier, which a merged device can carry for
 *    several panels.
 *
 * `ha-paneld-aid-<androidId>` is deliberately never matched: it is duplicated across a cloned fleet
 * (#155), so matching it is what resolved several panels to one device row.
 */
internal object HaPanelDeviceMatcher {
    const val PANEL_ASSISTANT_DOMAIN = "panel_assistant"
    const val PROBE_COMMAND = "config/entity_registry/get_entries"
    private const val MQTT_DOMAIN = "mqtt"

    /**
     * Probe rows read per Panel Assistant device. Two of a device's entities are keyed by the entry id
     * (its status sensor and its update entity); every other one by the discovery id. Reading a few per
     * device finds a discovery-id row without reading every entity in the installation.
     */
    private const val PROBES_PER_DEVICE = 8

    sealed interface Match {
        data class Found(val device: JSONObject) : Match
        data object Missing : Match
        data object Ambiguous : Match
    }

    /** Every device row that is this panel, in any tier. It is what excludes the panel's own entities. */
    fun all(
        devices: List<JSONObject>,
        deviceUid: String,
        panelId: String,
        panelAssistantEntryIds: Set<String>,
    ): List<JSONObject> {
        val tiers = tiers(deviceUid, panelId, panelAssistantEntryIds)
        return devices.filter { device -> tiers.any { it(device) } }
    }

    /** The single device row that speaks for this panel: the first matching tier, which must be unique. */
    fun preferred(
        devices: List<JSONObject>,
        deviceUid: String,
        panelId: String,
        panelAssistantEntryIds: Set<String>,
    ): Match {
        for (tier in tiers(deviceUid, panelId, panelAssistantEntryIds)) {
            val matches = devices.filter(tier)
            if (matches.size == 1) return Match.Found(matches.single())
            if (matches.size > 1) return Match.Ambiguous
        }
        return Match.Missing
    }

    /**
     * Entity ids whose full registry rows can prove which Panel Assistant entry is this panel: a bounded
     * sample of the `panel_assistant` entities on each device, taken from
     * `config/entity_registry/list_for_display`. Empty when there is nothing to prove.
     */
    fun probeEntityIds(displayEntities: JSONObject?): List<String> {
        val rows = displayEntities?.optJSONObject("result")?.optJSONArray("entities")
            ?: displayEntities?.optJSONArray("result")
            ?: return emptyList()
        val byDevice = linkedMapOf<String, MutableList<String>>()
        for (index in 0 until rows.length()) {
            val row = rows.optJSONObject(index) ?: continue
            val platform = row.optString("pl").ifBlank { row.optString("platform") }.trim()
            if (platform != PANEL_ASSISTANT_DOMAIN) continue
            val deviceId = row.optString("di").ifBlank { row.optString("device_id") }.trim()
            val entityId = row.optString("ei").ifBlank { row.optString("entity_id") }.trim()
            if (deviceId.isEmpty() || entityId.isEmpty()) continue
            byDevice.getOrPut(deviceId) { mutableListOf() } += entityId
        }
        return byDevice.values.flatMap { it.sorted().take(PROBES_PER_DEVICE) }
    }

    /** The [PROBE_COMMAND] request for [probeEntityIds], or null when there is nothing to ask. */
    fun probeRequest(entityIds: List<String>): JSONObject? =
        entityIds.takeIf(List<String>::isNotEmpty)?.let {
            JSONObject().put("type", PROBE_COMMAND).put("entity_ids", JSONArray(it))
        }

    /**
     * The Panel Assistant entries proven to be this panel: entries owning a `panel_assistant` entity whose
     * `unique_id` is `<discoveryId>_<suffix>`. Empty when the panel has no discovery id or nothing matched.
     */
    fun panelAssistantEntryIds(probeResponse: JSONObject?, discoveryId: String?): Set<String> {
        val did = discoveryId?.trim()?.takeIf(String::isNotEmpty) ?: return emptySet()
        val rows = probeResponse?.optJSONObject("result") ?: return emptySet()
        val entries = linkedSetOf<String>()
        for (key in rows.keys()) {
            val row = rows.optJSONObject(key) ?: continue
            if (row.optString("platform") != PANEL_ASSISTANT_DOMAIN) continue
            if (!row.optString("unique_id").startsWith("${did}_")) continue
            row.optString("config_entry_id").trim().takeIf(String::isNotEmpty)?.let(entries::add)
        }
        return entries
    }

    /** Whether any device row is registered by Panel Assistant, and so worth probing for. */
    fun hasPanelAssistantDevice(devices: JSONObject?): Boolean {
        val rows = devices?.optJSONArray("result") ?: return false
        return (0 until rows.length()).any { index ->
            rows.optJSONObject(index)?.let { hasDomain(it, PANEL_ASSISTANT_DOMAIN) } == true
        }
    }

    /**
     * Read what [panelAssistantEntryIds] needs over an open registry socket. [displayEntities] is reused
     * when the caller already holds `list_for_display`; otherwise it is read only when a Panel Assistant
     * device exists. Returns the probe response, or null when there was nothing to probe.
     */
    suspend fun readProbe(
        request: suspend (JSONObject) -> JSONObject,
        devices: JSONObject?,
        displayEntities: JSONObject? = null,
    ): JSONObject? {
        if (!hasPanelAssistantDevice(devices)) return null
        val display = displayEntities
            ?: request(JSONObject().put("type", "config/entity_registry/list_for_display"))
        return probeRequest(probeEntityIds(display))?.let { request(it) }
    }

    private fun tiers(
        deviceUid: String,
        panelId: String,
        panelAssistantEntryIds: Set<String>,
    ): List<(JSONObject) -> Boolean> {
        val immutable = deviceUid.trim().takeIf(String::isNotEmpty)?.let { "ha-paneld-uid-$it" }
        val legacy = "ha-paneld-${panelId.trim()}"
        return listOfNotNull(
            immutable?.let { id -> { device: JSONObject -> hasIdentifier(device, MQTT_DOMAIN, id) } },
            { device: JSONObject ->
                panelAssistantEntryIds.any { hasIdentifier(device, PANEL_ASSISTANT_DOMAIN, it) }
            },
            { device: JSONObject -> hasIdentifier(device, MQTT_DOMAIN, legacy) },
        )
    }

    private fun hasIdentifier(device: JSONObject, domain: String, identifier: String): Boolean =
        identifiers(device).any { (d, value) -> d == domain && value == identifier }

    private fun hasDomain(device: JSONObject, domain: String): Boolean =
        identifiers(device).any { (d, _) -> d == domain }

    private fun identifiers(device: JSONObject): List<Pair<String, String>> {
        val identifiers = device.optJSONArray("identifiers") ?: return emptyList()
        return (0 until identifiers.length()).mapNotNull { index ->
            val tuple = identifiers.optJSONArray(index) ?: return@mapNotNull null
            if (tuple.length() == 2) tuple.optString(0) to tuple.optString(1) else null
        }
    }
}
