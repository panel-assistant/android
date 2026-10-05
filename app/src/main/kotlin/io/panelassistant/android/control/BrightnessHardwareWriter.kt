package io.panelassistant.android.control

import io.panelassistant.android.platform.Daemon
import io.panelassistant.android.hardware.TransferCurve
import io.panelassistant.android.platform.RootShell

internal data class BacklightNode(val directory: String, val maximum: Int)
internal data class BacklightReading(val actual: Int, val maximum: Int)

internal enum class BrightnessWriteRoute { SU, HELPER, NONE }

/** Applies a 0–255 brightness command to the real hardware backlight, accepting only actual results.
 *  [transfer] maps the command onto the node's own range (the profile's curve; linear by default). */
internal class BrightnessHardwareWriter(
    private val root: RootShell,
    private val daemon: Daemon,
    private val transfer: TransferCurve = TransferCurve.Identity,
) {
    fun write(level: Int, node: BacklightNode?): BrightnessWriteRoute {
        val commanded = level.coerceIn(0, 255)
        node?.takeIf { it.maximum > 0 }?.let {
            val hardware = scale(commanded, it.maximum)
            val path = it.directory.trimEnd('/') + "/brightness"
            if (root.run("echo $hardware > $path")) return BrightnessWriteRoute.SU
        }

        val reading = parseBacklightReading(daemon.send("BLREAD")) ?: return BrightnessWriteRoute.NONE
        val hardware = scale(commanded, reading.maximum)
        return if (daemon.send("BLSET $hardware") == "OK") {
            BrightnessWriteRoute.HELPER
        } else {
            BrightnessWriteRoute.NONE
        }
    }

    private fun scale(level: Int, maximum: Int): Int = transfer.toHardware(level, maximum)
}

internal fun parseBacklightReading(reply: String?): BacklightReading? {
    val fields = reply?.trim()?.split(Regex("\\s+")) ?: return null
    if (fields.size != 2) return null
    val actual = fields[0].toIntOrNull() ?: return null
    val maximum = fields[1].toIntOrNull() ?: return null
    return BacklightReading(actual, maximum).takeIf { actual in 0..maximum && maximum > 0 }
}
