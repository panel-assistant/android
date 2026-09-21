package io.github.maxlyth.hapaneld.sensors

import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.max

internal enum class HaPresenceValue { ON, OFF, UNAVAILABLE }

internal enum class HaPresenceBehaviour { PULSE_LIKE, SUSTAINED_CAPABLE, MIXED, INSUFFICIENT }

internal fun presenceHistoryBatches(entityIds: Set<String>): List<Set<String>> =
    presenceBatches(entityIds, HISTORY_FILTER_BYTE_BUDGET) { id ->
        URLEncoder.encode(id, Charsets.UTF_8.name()).toByteArray(Charsets.US_ASCII).size + 3
    }

internal fun presenceSubscriptionBatches(entityIds: Set<String>): List<Set<String>> =
    presenceBatches(entityIds, SUBSCRIPTION_FRAME_BYTE_BUDGET) { id -> id.toByteArray(Charsets.UTF_8).size + 3 }

private fun presenceBatches(
    entityIds: Set<String>,
    byteBudget: Int,
    encodedBytes: (String) -> Int,
): List<Set<String>> {
    if (entityIds.isEmpty()) return emptyList()
    val batches = mutableListOf<Set<String>>()
    var current = linkedSetOf<String>()
    var bytes = 0
    entityIds.sorted().forEach { id ->
        val addition = encodedBytes(id)
        if (current.isNotEmpty() && bytes + addition > byteBudget) {
            batches += current
            current = linkedSetOf()
            bytes = 0
        }
        current += id
        bytes += addition
    }
    if (current.isNotEmpty()) batches += current
    return batches
}

private const val HISTORY_FILTER_BYTE_BUDGET = 4 * 1024
private const val SUBSCRIPTION_FRAME_BYTE_BUDGET = 1024 * 1024

internal data class HaPresenceTransition(
    val entityId: String,
    val atEpochMs: Long,
    val value: HaPresenceValue,
)

internal data class HaPresenceEpisode(val startedAtEpochMs: Long, val endedAtEpochMs: Long) {
    val durationMs: Long get() = (endedAtEpochMs - startedAtEpochMs).coerceAtLeast(0L)
}

internal data class HaPresenceEvidence(
    val behaviour: HaPresenceBehaviour,
    val episodeCount: Int,
    val coveredDays: Int,
    val medianDurationMs: Long?,
    val p10DurationMs: Long?,
    val p90DurationMs: Long?,
    val suggestedLeaseMs: Long,
) {
    val autoEligible: Boolean get() = behaviour != HaPresenceBehaviour.INSUFFICIENT
}

internal data class HaPresenceCandidate(
    val entityId: String,
    val friendlyName: String,
    val areaId: String,
    val areaName: String,
    val deviceClass: String,
    val platform: String,
    val authority: HaPresenceAuthority,
    val value: HaPresenceValue,
    val evidence: HaPresenceEvidence = HaPresenceEvidence(
        HaPresenceBehaviour.INSUFFICIENT,
        0,
        0,
        null,
        null,
        null,
        MIN_AUTO_SLEEP_LEASE_MS,
    ),
)

internal enum class HaPresenceAuthority {
    /** Device-backed evidence may assert that the Area is active. */
    ASSERT_PRESENCE,

    /** May be inspected during discovery, but is excluded from every auto-sleep decision input. */
    SUPPORTING_ONLY,
}

internal data class HaPresenceAreaProjection(
    val panelAreaId: String,
    val panelAreaName: String,
    val candidates: List<HaPresenceCandidate>,
)

internal data class HaPanelArea(
    val id: String,
    val name: String,
)

/** Strict, side-effect-free projection of the HA registries and current states needed by auto-sleep. */
internal object HaPresenceProtocol {
    private val candidateClasses = setOf("motion", "occupancy", "presence")
    private val supportingOnlyPlatforms = setOf(
        "area_occupancy", "bayesian", "group", "magic_areas", "template", "threshold", "tod", "trend",
    )

    fun projectArea(
        deviceResponse: JSONObject,
        areaResponse: JSONObject,
        entityResponse: JSONObject,
        states: JSONArray,
        deviceUid: String,
        panelId: String,
        preferredAreaName: String = "",
    ): HaPresenceAreaProjection {
        val devices = rows(deviceResponse.optJSONArray("result"), "device registry")
        val panelArea = projectPanelArea(deviceResponse, areaResponse, deviceUid, panelId, preferredAreaName)
        val entities = entityResponse.optJSONObject("result")?.optJSONArray("entities")
            ?: entityResponse.optJSONArray("result")
            ?: throw HaProtocolException("Home Assistant entity registry is incomplete")

        val panelDeviceIds = panelDeviceIds(devices, deviceUid, panelId)
        val panelAreaId = panelArea.id
        val panelAreaName = panelArea.name

        // Device registry rows outside the selected Area are not discovery authority. A stale or
        // malformed unrelated row must therefore not take auto-sleep down for every panel in the HA
        // installation. Referenced entities whose device cannot be projected remain supporting-only.
        val deviceAreas = devices.mapNotNull { device ->
            registryIdOrNull(device, "id", "device_id")?.let { id ->
                id to device.optString("area_id").trim().lowercase(Locale.ROOT)
            }
        }.toMap()
        val currentStates = linkedMapOf<String, JSONObject>()
        require(states.length() <= MAX_STATE_ROWS) { "Home Assistant state list is too large" }
        for (index in 0 until states.length()) {
            val state = states.optJSONObject(index) ?: continue
            val entityId = state.optString("entity_id").trim().lowercase(Locale.ROOT)
            if (validEntityId(entityId)) currentStates[entityId] = state
        }

        val projected = ArrayList<HaPresenceCandidate>()
        require(entities.length() <= MAX_ENTITY_ROWS) { "Home Assistant entity registry is too large" }
        for (index in 0 until entities.length()) {
            val row = entities.optJSONObject(index) ?: continue
            val entityId = row.optString("ei").ifBlank { row.optString("entity_id") }
                .trim().lowercase(Locale.ROOT)
            if (!entityId.startsWith("binary_sensor.") || !validEntityId(entityId)) continue
            val deviceId = row.optString("di").ifBlank { row.optString("device_id") }
                .trim().lowercase(Locale.ROOT)
            val platform = row.optString("pl").ifBlank { row.optString("platform") }
                .trim().lowercase(Locale.ROOT)
            // The panel's learned proximity is already admitted directly as local evidence. Its HA
            // projection is not an independent room source and may contain a large retained transition
            // burst, so feeding it back through Area history duplicates evidence and can saturate the
            // bounded history bootstrap.
            if (deviceId in panelDeviceIds) continue
            val effectiveArea = row.optString("ai").ifBlank { row.optString("area_id") }
                .trim().lowercase(Locale.ROOT).ifBlank { deviceAreas[deviceId].orEmpty() }
            if (effectiveArea != panelAreaId) continue
            val state = currentStates[entityId] ?: continue
            val attributes = state.optJSONObject("attributes") ?: JSONObject()
            val deviceClass = attributes.optString("device_class").trim().lowercase(Locale.ROOT)
            if (deviceClass !in candidateClasses) continue
            projected += HaPresenceCandidate(
                entityId = entityId,
                friendlyName = attributes.optString("friendly_name").safeName().ifBlank { entityId },
                areaId = panelAreaId,
                areaName = panelAreaName,
                deviceClass = deviceClass,
                platform = platform,
                authority = if (deviceAreas.containsKey(deviceId) && platform.isNotBlank() &&
                    validRegistryId(platform) &&
                    platform !in supportingOnlyPlatforms
                ) {
                    HaPresenceAuthority.ASSERT_PRESENCE
                } else {
                    HaPresenceAuthority.SUPPORTING_ONLY
                },
                value = value(state.optString("state")),
            )
        }
        return HaPresenceAreaProjection(
            panelAreaId,
            panelAreaName,
            projected.distinctBy(HaPresenceCandidate::entityId)
                .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER, HaPresenceCandidate::friendlyName)
                    .thenBy(HaPresenceCandidate::entityId)),
        )
    }

    /**
     * Resolves only this MQTT device's Area; it deliberately needs no entities or current states.
     *
     * [preferredAreaName] is the panel's locally configured area (`ha_area`), resolved by NAME first: a
     * person may deliberately point the panel at a different room than its HA device sits in: a panel
     * whose own area holds no motion entities takes its presence sources
     * from a neighbouring room (2026-07-26). An unknown or blank name falls back to the device's own
     * registry area, so a renamed area degrades to today's behaviour instead of failing.
     */
    fun projectPanelArea(
        deviceResponse: JSONObject,
        areaResponse: JSONObject,
        deviceUid: String,
        panelId: String,
        preferredAreaName: String = "",
    ): HaPanelArea {
        val devices = rows(deviceResponse.optJSONArray("result"), "device registry")
        val areas = rows(areaResponse.optJSONArray("result"), "area registry")
            .associateBy { registryId(it, "area_id", "id") }
        val preferred = preferredAreaName.trim()
        if (preferred.isNotEmpty()) {
            for ((id, row) in areas) {
                val name = row.optString("name").safeName()
                if (name.equals(preferred, ignoreCase = true)) return HaPanelArea(id, name)
            }
        }
        val device = panelDevice(devices, deviceUid, panelId)
        val areaId = device.optString("area_id").trim().lowercase(Locale.ROOT)
        if (!validRegistryId(areaId)) throw HaProtocolException("Home Assistant panel device has no Area")
        return HaPanelArea(areaId, areas[areaId]?.optString("name")?.safeName() ?: areaId)
    }

    fun parseHistory(
        response: JSONArray,
        requestedEntityIds: Set<String>,
        startEpochMs: Long,
        endEpochMs: Long,
    ): Map<String, List<HaPresenceTransition>> {
        require(endEpochMs > startEpochMs)
        require(response.length() <= requestedEntityIds.size) { "Home Assistant returned unexpected history series" }
        val normalized = requestedEntityIds.mapTo(linkedSetOf()) { it.lowercase(Locale.ROOT) }
        val result = linkedMapOf<String, List<HaPresenceTransition>>()
        var totalEvents = 0
        for (seriesIndex in 0 until response.length()) {
            val series = response.optJSONArray(seriesIndex)
                ?: throw HaProtocolException("Home Assistant returned invalid history")
            totalEvents += series.length()
            if (totalEvents > MAX_HISTORY_EVENTS) {
                throw HaPresenceHistoryLimitException("Home Assistant presence history is too large")
            }
            if (series.length() == 0) continue
            val first = series.optJSONObject(0) ?: throw HaProtocolException("Home Assistant returned invalid history state")
            val entityId = first.optString("entity_id").trim().lowercase(Locale.ROOT)
            if (entityId !in normalized) throw HaProtocolException("Home Assistant returned history for a different entity")
            val transitions = ArrayList<HaPresenceTransition>(series.length())
            for (index in 0 until series.length()) {
                val row = series.optJSONObject(index)
                    ?: throw HaProtocolException("Home Assistant returned invalid history state")
                val returned = row.optString("entity_id").trim().lowercase(Locale.ROOT)
                if (returned.isNotBlank() && returned != entityId) {
                    throw HaProtocolException("Home Assistant mixed presence history entities")
                }
                val at = timestamp(row) ?: throw HaProtocolException("Home Assistant presence history has an invalid timestamp")
                if (at > endEpochMs || transitions.lastOrNull()?.atEpochMs?.let { at < it } == true) {
                    throw HaProtocolException("Home Assistant presence history is out of order")
                }
                val next = HaPresenceTransition(entityId, max(startEpochMs, at), value(row.optString("state")))
                if (transitions.lastOrNull()?.atEpochMs == next.atEpochMs) transitions[transitions.lastIndex] = next
                else if (transitions.lastOrNull()?.value != next.value) transitions += next
            }
            result[entityId] = transitions
        }
        return normalized.associateWith { result[it].orEmpty() }
    }

    fun episodes(transitions: List<HaPresenceTransition>): List<HaPresenceEpisode> {
        var knownOff = false
        var startedAt: Long? = null
        val out = ArrayList<HaPresenceEpisode>()
        for (transition in transitions) when (transition.value) {
            HaPresenceValue.OFF -> {
                val start = startedAt
                if (start != null && transition.atEpochMs > start) out += HaPresenceEpisode(start, transition.atEpochMs)
                startedAt = null
                knownOff = true
            }
            HaPresenceValue.ON -> if (knownOff && startedAt == null) startedAt = transition.atEpochMs
            HaPresenceValue.UNAVAILABLE -> {
                startedAt = null
                knownOff = false
            }
        }
        return out
    }

    fun evidence(transitions: List<HaPresenceTransition>): HaPresenceEvidence {
        val episodes = episodes(transitions)
        if (episodes.isEmpty()) return insufficient()
        val durations = episodes.map(HaPresenceEpisode::durationMs).sorted()
        val coveredDays = episodes.mapTo(linkedSetOf()) { it.startedAtEpochMs / DAY_MS }.size
        val median = percentile(durations, 0.50)
        val p10 = percentile(durations, 0.10)
        val p90 = percentile(durations, 0.90)
        val tightlyGrouped = p90 - p10 <= max(MIN_TIGHT_SPREAD_MS, median / 2L)
        val pulse = episodes.size >= MIN_PULSE_EPISODES && coveredDays >= MIN_COVERED_DAYS &&
            median <= MAX_PULSE_DURATION_MS && tightlyGrouped
        val sustained = episodes.count { it.durationMs > MAX_PULSE_DURATION_MS } >= MIN_SUSTAINED_EPISODES &&
            coveredDays >= MIN_COVERED_DAYS
        val recurring = episodes.size >= MIN_PULSE_EPISODES && coveredDays >= MIN_COVERED_DAYS
        val behaviour = when {
            pulse -> HaPresenceBehaviour.PULSE_LIKE
            sustained -> HaPresenceBehaviour.SUSTAINED_CAPABLE
            recurring -> HaPresenceBehaviour.MIXED
            else -> HaPresenceBehaviour.INSUFFICIENT
        }
        val gaps = if (pulse || behaviour == HaPresenceBehaviour.MIXED) episodes.zipWithNext()
            .map { (left, right) -> right.startedAtEpochMs - left.endedAtEpochMs }
            .filter { it in 1..MAX_AUTO_SLEEP_LEASE_MS }
            .sorted() else emptyList()
        val suggested = gaps.takeIf { it.size >= MIN_GAP_EVIDENCE }
            ?.let { nearestRankPercentile(it, 0.80) + LEASE_MARGIN_MS }
            ?.coerceIn(MIN_AUTO_SLEEP_LEASE_MS, MAX_AUTO_SLEEP_LEASE_MS)
            ?: MIN_AUTO_SLEEP_LEASE_MS
        return HaPresenceEvidence(behaviour, episodes.size, coveredDays, median, p10, p90, suggested)
    }

    fun value(raw: String): HaPresenceValue = when (raw.trim().lowercase(Locale.ROOT)) {
        "on" -> HaPresenceValue.ON
        "off" -> HaPresenceValue.OFF
        else -> HaPresenceValue.UNAVAILABLE
    }

    private fun insufficient() = HaPresenceEvidence(
        HaPresenceBehaviour.INSUFFICIENT,
        0,
        0,
        null,
        null,
        null,
        MIN_AUTO_SLEEP_LEASE_MS,
    )

    private fun rows(array: JSONArray?, name: String): List<JSONObject> {
        val source = array ?: throw HaProtocolException("Home Assistant $name is incomplete")
        require(source.length() <= MAX_REGISTRY_ROWS) { "Home Assistant $name is too large" }
        return (0 until source.length()).map { source.optJSONObject(it) ?: throw HaProtocolException("Home Assistant $name is invalid") }
    }

    private fun registryIdOrNull(row: JSONObject, vararg keys: String): String? = keys.firstNotNullOfOrNull { key ->
        row.optString(key).trim().lowercase(Locale.ROOT).takeIf(::validRegistryId)
    }

    private fun registryId(row: JSONObject, vararg keys: String): String =
        registryIdOrNull(row, *keys) ?: throw HaProtocolException("Home Assistant registry row has no valid id")

    private fun hasIdentifier(device: JSONObject, domain: String, identifier: String): Boolean {
        val identifiers = device.optJSONArray("identifiers") ?: return false
        for (index in 0 until identifiers.length()) {
            val tuple = identifiers.optJSONArray(index) ?: continue
            if (tuple.length() == 2 && tuple.optString(0) == domain && tuple.optString(1) == identifier) return true
        }
        return false
    }

    /**
     * The minted `ha-paneld-uid-<deviceUid>` identifier preferred, the historical `ha-paneld-<panelId>`
     * as fallback. `ha-paneld-aid-<androidId>` is deliberately not matched: it is duplicated across a
     * cloned factory image (#155), so it resolves several panels to one device row.
     */
    private fun panelDeviceIds(devices: List<JSONObject>, deviceUid: String, panelId: String): Set<String> {
        val immutable = deviceUid.trim().takeIf(String::isNotEmpty)?.let { "ha-paneld-uid-$it" }
        val legacy = "ha-paneld-${panelId.trim()}"
        val matches = devices.filter { device ->
            immutable != null && hasIdentifier(device, "mqtt", immutable) || hasIdentifier(device, "mqtt", legacy)
        }
        if (matches.isEmpty()) throw HaProtocolException("Home Assistant panel device match is missing")
        return matches.mapNotNullTo(linkedSetOf()) { registryIdOrNull(it, "id", "device_id") }
            .takeIf(Set<String>::isNotEmpty)
            ?: throw HaProtocolException("Home Assistant panel device has no valid id")
    }

    /** Same identifier precedence, and the same exclusion, as [panelDeviceIds]. */
    private fun panelDevice(devices: List<JSONObject>, deviceUid: String, panelId: String): JSONObject {
        val immutable = deviceUid.trim().takeIf(String::isNotEmpty)?.let { "ha-paneld-uid-$it" }
        val legacy = "ha-paneld-${panelId.trim()}"
        val exact = devices.filter { device -> immutable != null && hasIdentifier(device, "mqtt", immutable) }
        val matches = if (exact.isNotEmpty()) exact else devices.filter { hasIdentifier(it, "mqtt", legacy) }
        if (matches.size != 1) {
            throw HaProtocolException(
                "Home Assistant panel device match is ${if (matches.isEmpty()) "missing" else "ambiguous"}",
            )
        }
        return matches.single()
    }

    private fun timestamp(row: JSONObject): Long? {
        val raw = row.optString("last_changed").ifBlank { row.optString("last_updated") }
        return parseHaTimestampEpochMs(raw)
    }

    private fun percentile(sorted: List<Long>, fraction: Double): Long {
        require(sorted.isNotEmpty())
        val index = ((sorted.size - 1) * fraction).toInt().coerceIn(0, sorted.lastIndex)
        return sorted[index]
    }

    private fun nearestRankPercentile(sorted: List<Long>, fraction: Double): Long {
        require(sorted.isNotEmpty())
        val index = (ceil(sorted.size * fraction).toInt() - 1).coerceIn(0, sorted.lastIndex)
        return sorted[index]
    }

    private fun String.safeName(): String = trim().take(MAX_NAME_CHARS).filterNot(Char::isISOControl)
    private fun validRegistryId(value: String): Boolean = value.matches(Regex("^[a-z0-9_-]+$"))
    private fun validEntityId(value: String): Boolean = value.length <= 255 && value.matches(Regex("^[a-z0-9_]+\\.[a-z0-9_]+$"))

    private const val MAX_REGISTRY_ROWS = 100_000
    private const val MAX_ENTITY_ROWS = 100_000
    private const val MAX_STATE_ROWS = 100_000
    private const val MAX_HISTORY_EVENTS = 20_000
    private const val MAX_NAME_CHARS = 256
    private const val MIN_PULSE_EPISODES = 8
    private const val MIN_SUSTAINED_EPISODES = 3
    private const val MIN_COVERED_DAYS = 2
    private const val MIN_GAP_EVIDENCE = 3
    private const val MAX_PULSE_DURATION_MS = 5L * 60_000L
    private const val MIN_TIGHT_SPREAD_MS = 60_000L
    private const val LEASE_MARGIN_MS = 2L * 60_000L
    private const val DAY_MS = 24L * 60L * 60_000L
}

internal class HaPresenceHistoryLimitException(message: String) : RuntimeException(message)

internal const val MIN_AUTO_SLEEP_LEASE_MS = 10L * 60_000L
internal const val MAX_AUTO_SLEEP_LEASE_MS = 60L * 60_000L
