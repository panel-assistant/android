package io.panelassistant.android.sensors

import kotlin.math.abs
import kotlin.math.max

/**
 * Callback and cadence state owned by exactly one SensorReporter start. Closing the run rejects queued
 * SensorManager events from that generation, including after a replacement run has started.
 */
internal class SensorRunCallbacks(
    private val onLux: (Int) -> Unit,
    private val onLuxRaw: (Float) -> Unit,
    private val onProximity: (Boolean?, Int?, Int) -> Unit,
    private val onGesture: () -> Unit,
    private val onTemperature: (Float) -> Unit,
    private val onHumidity: (Float) -> Unit,
    private val onPresenceApproach: () -> Unit = {},
) {
    @Volatile private var open = true
    private var lastLux = -1f
    private var lastLuxAt = 0L
    private var lastTemp = Float.NaN
    private var lastTempAt = 0L
    private var lastHumidity = Float.NaN
    private var lastHumidityAt = 0L

    fun light(lux: Float, now: Long) {
        if (!open) return
        onLuxRaw(lux)
        if (!open) return
        val changed = lastLux < 0 || abs(lux - lastLux) >= max(1f, lastLux * 0.2f)
        if (changed && due(now, lastLuxAt, LIGHT_INTERVAL_MS)) {
            lastLux = lux
            lastLuxAt = now
            onLux(lux.toInt())
        }
    }

    fun proximity(
        near: Boolean?,
        normalizedLevel: Int?,
        reportMask: Int = ProximityReportGate.BOTH,
    ) {
        if (open) onProximity(near, normalizedLevel, reportMask)
    }

    fun presenceApproach() {
        if (open) onPresenceApproach()
    }

    fun gesture() {
        if (open) onGesture()
    }

    fun temperature(value: Float, now: Long) {
        if (!open) return
        if ((lastTemp.isNaN() || abs(value - lastTemp) >= 0.2f) && due(now, lastTempAt, CLIMATE_INTERVAL_MS)) {
            lastTemp = value
            lastTempAt = now
            onTemperature(value)
        }
    }

    fun humidity(value: Float, now: Long) {
        if (!open) return
        if ((lastHumidity.isNaN() || abs(value - lastHumidity) >= 1.0f) && due(now, lastHumidityAt, CLIMATE_INTERVAL_MS)) {
            lastHumidity = value
            lastHumidityAt = now
            onHumidity(value)
        }
    }

    fun close() {
        open = false
    }

    internal fun isOpen(): Boolean = open

    private fun due(now: Long, previous: Long, intervalMs: Long): Boolean =
        previous <= 0L || now < previous || now - previous >= intervalMs

    private companion object {
        const val LIGHT_INTERVAL_MS = 15_000L
        const val CLIMATE_INTERVAL_MS = 60_000L
    }
}

/**
 * Publish the unavailable startup state before activating a source that may immediately deliver its
 * current value. The lifecycle checks also prevent a callback that stops this run from allowing the
 * remainder of sensor registration to continue.
 */
internal inline fun initializeProximitySource(
    run: SensorRunCallbacks,
    isCurrent: () -> Boolean,
    activate: () -> Unit,
): Boolean {
    run.proximity(null, null)
    if (!run.isOpen() || !isCurrent()) return false
    activate()
    return run.isOpen() && isCurrent()
}
