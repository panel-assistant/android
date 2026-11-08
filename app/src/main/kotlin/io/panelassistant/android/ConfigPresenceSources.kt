package io.panelassistant.android

import android.util.Log
import org.json.JSONArray
import java.util.Locale

/**
 * Owner-chosen Home Assistant presence sensors. Empty means automatic Area discovery; any entry
 * replaces the Area guess entirely. Stored as an ordered JSON array of binary_sensor entity ids.
 * Like the Area-source exclusions this is an installation-specific owner choice, not a fleet setting.
 */
internal fun Config.presenceHaSources(): List<String> = synchronized(Config.CONFIG_LOCK) {
    val raw = prefs.getString(PRESENCE_HA_SOURCES_PREF, "").orEmpty()
    if (raw.isBlank()) return@synchronized emptyList()
    runCatching { decodePresenceHaSources(raw, strict = false) }
        .onFailure { Log.w(TAG_PRESENCE_SOURCES, "ignoring malformed chosen presence sources") }
        .getOrDefault(emptyList())
}

/** Replaces the chosen list; an empty list returns the panel to automatic Area discovery. */
internal fun Config.setPresenceHaSources(entityIds: List<String>): Boolean = synchronized(Config.CONFIG_LOCK) {
    val normalized = entityIds.map(::normalizePresenceSourceId)
    if (normalized.any(String::isBlank) || normalized.size > MAX_PRESENCE_HA_SOURCES) return@synchronized false
    val distinct = normalized.distinct()
    durableCommit {
        if (distinct.isEmpty()) remove(PRESENCE_HA_SOURCES_PREF)
        else putString(PRESENCE_HA_SOURCES_PREF, JSONArray(distinct).toString())
    }
}

/** Strict backup admission: rejects anything the live setter would refuse. */
internal fun normalizePresenceHaSources(raw: String): String {
    if (raw.isBlank()) return ""
    val ids = runCatching { decodePresenceHaSources(raw, strict = true) }
        .getOrElse { throw IllegalArgumentException("invalid chosen presence sources", it) }
    return if (ids.isEmpty()) "" else JSONArray(ids).toString()
}

internal fun isPresenceSourceEntityId(raw: String): Boolean = normalizePresenceSourceId(raw).isNotBlank()

private fun decodePresenceHaSources(raw: String, strict: Boolean): List<String> {
    val values = JSONArray(raw)
    require(!strict || values.length() <= MAX_PRESENCE_HA_SOURCES) { "too many chosen presence sources" }
    val ids = (0 until values.length()).mapNotNull { index ->
        val id = normalizePresenceSourceId(if (strict) values.get(index) as? String ?: "" else values.optString(index))
        if (strict) require(id.isNotBlank()) { "invalid chosen presence source" }
        id.takeIf(String::isNotBlank)
    }
    if (strict) require(ids.distinct().size == ids.size) { "duplicate chosen presence source" }
    return ids.distinct().take(MAX_PRESENCE_HA_SOURCES)
}

private fun normalizePresenceSourceId(raw: String): String = raw.trim().lowercase(Locale.ROOT)
    .takeIf { it.length <= 255 && it.matches(Regex("binary_sensor\\.[a-z0-9_]+")) }.orEmpty()

internal const val PRESENCE_HA_SOURCES_PREF = "presence_ha_sources_v1"
internal const val MAX_PRESENCE_HA_SOURCES = 16
private const val TAG_PRESENCE_SOURCES = "PresenceSources"
