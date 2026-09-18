package io.github.maxlyth.hapaneld.persistence

import io.github.maxlyth.hapaneld.util.Json
import org.json.JSONObject

/**
 * Which device and which installed identity wrote a backup.
 *
 * `panel_id` names a panel to its owner and is restorable configuration, so it cannot also prove which
 * physical device an archive came from: a freshly installed app has not adopted it yet. The discovery
 * id can. It is the Panel Assistant pseudonym, a domain-separated digest of the Android id, which is
 * scoped to the signing key rather than the package, so the legacy and successor identities of this
 * app compute the same value on the same device and a different one anywhere else. The raw Android id
 * is never written.
 */
object BackupIdentity {
    const val DISCOVERY_ID_KEY = "discovery_id"
    const val PACKAGE_KEY = "package"
    const val MQTT_CONNECTED_KEY = "mqtt_connected"
    private val DISCOVERY_ID_RE = Regex("[0-9a-f]{64}")

    /** The manifest members, each prefixed with a comma; an unavailable discovery id is omitted. */
    fun manifestFragment(discoveryId: String?, packageName: String, mqttConnected: Boolean): String = buildString {
        if (discoveryId != null && DISCOVERY_ID_RE.matches(discoveryId)) {
            append(",\"$DISCOVERY_ID_KEY\":").append(Json.str(discoveryId))
        }
        append(",\"$PACKAGE_KEY\":").append(Json.str(packageName))
        // Whether the writer was connected to its broker when it wrote this. The other identity of this
        // app uses it to decide what "MQTT unchanged" means after a handover: a panel that was
        // connected must connect again, and one that never was is not held to a broker it never had.
        append(",\"$MQTT_CONNECTED_KEY\":").append(mqttConnected)
    }

    /** True only when the archive records that its writer was connected to MQTT. */
    fun writerMqttConnected(manifest: JSONObject): Boolean = manifest.opt(MQTT_CONNECTED_KEY) == true

    /** The archive's discovery id, or null when it is absent or not a well-formed pseudonym. */
    fun discoveryId(manifest: JSONObject): String? =
        (manifest.opt(DISCOVERY_ID_KEY) as? String)?.takeIf(DISCOVERY_ID_RE::matches)

    /**
     * True only when the archive was written on this device. Both sides must present a well-formed
     * pseudonym: an archive that predates the field, or a device that cannot read its Android id,
     * proves nothing and is treated as a different device.
     */
    fun sameDevice(manifest: JSONObject, ownDiscoveryId: String?): Boolean {
        val own = ownDiscoveryId?.takeIf(DISCOVERY_ID_RE::matches) ?: return false
        return discoveryId(manifest) == own
    }
}

/**
 * Durable preference stores the app keeps outside `app_state`, carried so a move to the other
 * installed identity does not silently drop them.
 *
 * The list is closed and every store is string-to-string. The legacy `ha-paneld` XML file is not here:
 * it is the downgrade mirror of the `config` namespace and is rewritten by the configuration restore.
 * These stores describe the device that wrote them, so they return only under a same-device proof.
 */
object RawPreferenceBackup {
    const val SECTION_KEY = "raw_preferences"

    /** Store name to the most entries it may carry. */
    val STORES: Map<String, Int> = mapOf("proximity-wake-invalidation" to 64)

    private const val MAX_KEY_CHARS = 256
    private const val MAX_VALUE_CHARS = 1_024

    /** The manifest member (comma-prefixed), or empty when no listed store holds a string entry. */
    fun manifestFragment(read: (store: String) -> Map<String, *>): String {
        val stores = STORES.keys.mapNotNull { store ->
            val entries = bounded(store, read(store).mapNotNull { (key, value) -> (value as? String)?.let { key to it } })
            if (entries.isEmpty()) null
            else Json.str(store) + ":{" + entries.joinToString(",") { (k, v) -> "${Json.str(k)}:${Json.str(v)}" } + "}"
        }
        return if (stores.isEmpty()) "" else ",\"$SECTION_KEY\":{${stores.joinToString(",")}}"
    }

    /**
     * The entries a restore may write, or null when the section is malformed and the restore must be
     * refused. Unknown stores, non-string values and oversized entries make it malformed rather than
     * being skipped: a writer never emits them, so they mean a substituted or hand-edited manifest.
     */
    fun restorable(manifest: JSONObject): Map<String, Map<String, String>>? {
        if (!manifest.has(SECTION_KEY)) return emptyMap()
        val section = manifest.optJSONObject(SECTION_KEY) ?: return null
        val result = LinkedHashMap<String, Map<String, String>>()
        for (store in section.keys()) {
            val limit = STORES[store] ?: return null
            val entries = section.optJSONObject(store) ?: return null
            if (entries.length() > limit) return null
            val values = LinkedHashMap<String, String>()
            for (key in entries.keys()) {
                val value = entries.opt(key) as? String ?: return null
                if (key.isEmpty() || key.length > MAX_KEY_CHARS || value.length > MAX_VALUE_CHARS) return null
                values[key] = value
            }
            result[store] = values
        }
        return result
    }

    private fun bounded(store: String, entries: List<Pair<String, String>>): List<Pair<String, String>> =
        entries
            .filter { (key, value) -> key.isNotEmpty() && key.length <= MAX_KEY_CHARS && value.length <= MAX_VALUE_CHARS }
            .sortedBy { it.first }
            .take(STORES.getValue(store))
}
