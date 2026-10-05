package io.panelassistant.android.sensors

import kotlin.math.abs
import kotlin.math.max

/**
 * Debug-only rolling **RAM** ring buffer of raw sensor samples plus the auto-brightness engine's
 * internals, for **fit-testing the filters** — NOT a user-facing surface, never persisted (zero eMMC
 * wear, negligible CPU). Served via `GET /sensortrace` (CSV for analysis, JSON for a future on-panel
 * chart). Gated by [enabled] (on by default; negligible RAM/CPU); records are cheap no-ops when off.
 *
 * **Decimation** keeps it bounded yet hours-long: a sample is kept on a *meaningful change* (so a fast
 * lights-on step is captured at full detail) OR once every [MIN_INTERVAL_MS] when quiet (so slow
 * daylight drift still leaves a trail without flooding the buffer). At the 1 Hz floor [CAP] rows span
 * ~1 hour; bursts shorten that but never grow RAM past the cap (oldest auto-purged).
 *
 * Thread-safe: lux arrives on the sensor + MQTT threads, proximity on the sensor thread, reads on the
 * HTTP thread.
 */
object SensorTrace {
    @Volatile var enabled: Boolean = true

    private const val CAP = 4000              // bounded RAM (~160 KB); ~1 h at the 1 Hz floor, less with bursts
    private const val MIN_INTERVAL_MS = 1000L // slow-regime floor: ≤1 sample/sec when nothing's changing
    private const val LUX_CHANGE = 0.15f      // "meaningful" lux change → keep at full rate (fast events)

    private class Row(
        val t: Long, val kind: Char, val raw: Float,
        val smoothed: Float?, val target: Int?, val applied: Int?, val near: Boolean?,
    )

    private val lock = Any()
    private val buf = ArrayDeque<Row>()
    private var lastLuxAt = 0L
    private var lastLuxRaw = -1f
    private var lastProxAt = 0L
    private var lastProxRaw = Float.NaN
    private var lastProxNear: Boolean? = null

    private fun now() = System.currentTimeMillis()

    /** Feed a lux sample + the engine state (smoothed/target/applied are null when the engine is idle). */
    fun recordLux(raw: Float, smoothed: Float?, target: Int?, applied: Int?) {
        if (!enabled || raw.isNaN()) return
        synchronized(lock) {
            val t = now()
            val changed = lastLuxRaw < 0 || abs(raw - lastLuxRaw) >= max(1f, lastLuxRaw * LUX_CHANGE)
            if (!changed && t - lastLuxAt < MIN_INTERVAL_MS) return
            lastLuxAt = t; lastLuxRaw = raw
            add(Row(t, 'L', raw, smoothed, target, applied, null))
        }
    }

    /** Feed a raw proximity sample + the derived near/far. Kept on a near transition, or the 1 Hz floor. */
    fun recordProx(raw: Float, near: Boolean?) {
        if (!enabled || raw.isNaN()) return
        synchronized(lock) {
            val t = now()
            val changed = lastProxNear != near || lastProxRaw.isNaN() || abs(raw - lastProxRaw) >= 1f
            if (!changed && t - lastProxAt < MIN_INTERVAL_MS) return
            lastProxAt = t; lastProxRaw = raw; lastProxNear = near
            add(Row(t, 'P', raw, null, null, null, near))
        }
    }

    private fun add(r: Row) {
        if (buf.size >= CAP) buf.removeFirst()
        buf.addLast(r)
    }

    fun clear() = synchronized(lock) {
        buf.clear(); lastLuxRaw = -1f; lastProxRaw = Float.NaN; lastProxNear = null
    }

    /** CSV for spreadsheet/plot analysis (the fit-testing path). */
    fun toCsv(): String = synchronized(lock) {
        val sb = StringBuilder("t_ms,kind,raw,smoothed,target,applied,near\n")
        for (r in buf) {
            sb.append(r.t).append(',').append(r.kind).append(',').append(r.raw).append(',')
                .append(r.smoothed ?: "").append(',').append(r.target ?: "").append(',')
                .append(r.applied ?: "").append(',').append(r.near?.let { if (it) 1 else 0 } ?: "").append('\n')
        }
        sb.toString()
    }

    /** JSON for programmatic use / a future on-panel history chart. */
    fun toJson(): String = synchronized(lock) {
        val sb = StringBuilder("{\"enabled\":").append(enabled).append(",\"count\":").append(buf.size)
            .append(",\"cap\":").append(CAP).append(",\"rows\":[")
        var first = true
        for (r in buf) {
            if (!first) sb.append(','); first = false
            sb.append("{\"t\":").append(r.t).append(",\"kind\":\"").append(r.kind).append("\",\"raw\":").append(r.raw)
            r.smoothed?.let { sb.append(",\"smoothed\":").append(it) }
            r.target?.let { sb.append(",\"target\":").append(it) }
            r.applied?.let { sb.append(",\"applied\":").append(it) }
            r.near?.let { sb.append(",\"near\":").append(it) }
            sb.append('}')
        }
        sb.append("]}")
        sb.toString()
    }
}
