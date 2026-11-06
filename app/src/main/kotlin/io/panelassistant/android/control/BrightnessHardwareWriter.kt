package io.panelassistant.android.control

import io.panelassistant.android.platform.Daemon
import io.panelassistant.android.hardware.TransferCurve

internal data class BacklightReading(val actual: Int, val maximum: Int)

internal enum class BrightnessWriteRoute { HELPER, NONE }

/** Applies a 0–255 brightness command to the real hardware backlight through the root helper, accepting
 *  only actual results. [transfer] maps the command onto the node's own range (the profile's curve; linear
 *  by default). Never su: these writes repeat up to once a second on an auto-brightness ramp, and repeated
 *  root operations belong to the helper (maintainer rule, 2026-11-05). Without a helper the Android
 *  setting the caller writes is the only actuator. */
internal class BrightnessHardwareWriter(
    private val daemon: Daemon,
    private val transfer: TransferCurve = TransferCurve.Identity,
) {
    fun write(level: Int): BrightnessWriteRoute {
        val commanded = level.coerceIn(0, 255)
        val reading = parseBacklightReading(daemon.send("BLREAD")) ?: return BrightnessWriteRoute.NONE
        val hardware = transfer.toHardware(commanded, reading.maximum)
        return if (daemon.send("BLSET $hardware") == "OK") {
            BrightnessWriteRoute.HELPER
        } else {
            BrightnessWriteRoute.NONE
        }
    }
}

internal fun parseBacklightReading(reply: String?): BacklightReading? {
    val fields = reply?.trim()?.split(Regex("\\s+")) ?: return null
    if (fields.size != 2) return null
    val actual = fields[0].toIntOrNull() ?: return null
    val maximum = fields[1].toIntOrNull() ?: return null
    return BacklightReading(actual, maximum).takeIf { actual in 0..maximum && maximum > 0 }
}
