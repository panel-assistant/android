package io.panelassistant.android.sensors

import io.panelassistant.android.HaAuthOwner
import io.panelassistant.android.control.AMBIENT_MINUTE_MS
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.ln1p

internal data class HaAmbientHistoryMinute(
    val minute: Long,
    val luxIntegral: Double,
    val coverageMs: Long,
    val minLux: Double,
    val maxLux: Double,
    val lastLux: Double,
    val sampleCount: Long,
    val baselineLogIntegral: Double,
    val baselineCoverageMs: Long,
)

internal data class HaAmbientHistorySeed(
    val entityId: String,
    val baseUrl: String,
    val authOwner: HaAuthOwner,
    val minutes: List<HaAmbientHistoryMinute>,
)

/** Strict, bounded projection of Home Assistant's state history into time-weighted minute rows. */
internal object HaAmbientHistoryProtocol {
    fun parse(
        response: JSONArray,
        entityId: String,
        startEpochMs: Long,
        endEpochMs: Long,
    ): List<HaAmbientHistoryMinute> {
        require(endEpochMs > startEpochMs)
        if (response.length() > 1) throw HaProtocolException("Home Assistant returned unexpected history series")
        if (response.length() == 0) return emptyList()
        val series = response.optJSONArray(0)
            ?: throw HaProtocolException("Home Assistant returned invalid history")
        if (series.length() > MAX_EVENTS) throw HaProtocolException("Home Assistant history is too large")
        if (series.length() == 0) return emptyList()

        val states = ArrayList<TimedState>(series.length())
        for (index in 0 until series.length()) {
            val row = series.optJSONObject(index)
                ?: throw HaProtocolException("Home Assistant returned invalid history state")
            val returnedEntity = row.optString("entity_id").trim()
            if (index == 0 && returnedEntity != entityId || returnedEntity.isNotEmpty() && returnedEntity != entityId) {
                throw HaProtocolException("Home Assistant returned history for a different entity")
            }
            val at = timestamp(row) ?: throw HaProtocolException("Home Assistant history has an invalid timestamp")
            val value = finiteLux(row.optString("state"))
            if (states.isNotEmpty() && at < states.last().atEpochMs) {
                throw HaProtocolException("Home Assistant history is out of order")
            }
            val next = TimedState(at, value)
            if (states.lastOrNull()?.atEpochMs == at) states[states.lastIndex] = next else states += next
        }

        val rows = linkedMapOf<Long, MutableMinute>()
        states.forEachIndexed { index, state ->
            val value = state.lux ?: return@forEachIndexed
            var cursor = maxOf(startEpochMs, state.atEpochMs)
            val nextAt = states.getOrNull(index + 1)?.atEpochMs ?: endEpochMs
            val until = minOf(endEpochMs, nextAt)
            while (cursor < until) {
                val minute = cursor / AMBIENT_MINUTE_MS
                val boundary = (minute + 1) * AMBIENT_MINUTE_MS
                val duration = minOf(until, boundary) - cursor
                rows.getOrPut(minute, ::MutableMinute).add(value, duration)
                cursor += duration
            }
        }
        if (rows.size > MAX_OUTPUT_MINUTES) throw HaProtocolException("Home Assistant history covers too many minutes")
        return rows.map { (minute, row) -> row.freeze(minute) }
    }

    private fun timestamp(row: JSONObject): Long? {
        val raw = row.optString("last_changed").ifBlank { row.optString("last_updated") }
        return parseHaTimestampEpochMs(raw)
    }

    private fun finiteLux(raw: String): Double? = raw.trim().toDoubleOrNull()
        ?.takeIf { it.isFinite() && it in 0.0..MAX_LUX }

    private data class TimedState(val atEpochMs: Long, val lux: Double?)

    private class MutableMinute {
        var luxIntegral = 0.0
        var coverageMs = 0L
        var minLux = Double.POSITIVE_INFINITY
        var maxLux = Double.NEGATIVE_INFINITY
        var lastLux = 0.0
        var sampleCount = 0L
        var baselineLogIntegral = 0.0

        fun add(lux: Double, durationMs: Long) {
            if (durationMs <= 0L) return
            luxIntegral += lux * durationMs
            coverageMs += durationMs
            minLux = minOf(minLux, lux)
            maxLux = maxOf(maxLux, lux)
            lastLux = lux
            sampleCount++
            baselineLogIntegral += ln1p(lux) * durationMs
        }

        fun freeze(minute: Long) = HaAmbientHistoryMinute(
            minute = minute,
            luxIntegral = luxIntegral,
            coverageMs = coverageMs.coerceAtMost(AMBIENT_MINUTE_MS),
            minLux = minLux,
            maxLux = maxLux,
            lastLux = lastLux,
            sampleCount = sampleCount,
            baselineLogIntegral = baselineLogIntegral,
            baselineCoverageMs = coverageMs.coerceAtMost(AMBIENT_MINUTE_MS),
        )
    }

    private const val MAX_EVENTS = 20_000
    private const val MAX_OUTPUT_MINUTES = 10_080
    private const val MAX_LUX = 10_000_000.0
}
