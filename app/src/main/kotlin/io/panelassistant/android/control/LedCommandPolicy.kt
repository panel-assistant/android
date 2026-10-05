package io.panelassistant.android.control

import io.panelassistant.android.hardware.LedEffects
import org.json.JSONObject

/** Pure parsing, persistence, and observation policy for the command-authoritative RGB LED. */
internal object LedCommandPolicy {
    data class Desired(
        val on: Boolean,
        val brightness: Int = 0,
        val red: Int = 0,
        val green: Int = 0,
        val blue: Int = 0,
        val effect: LedEffects.Effect? = null,
    ) {
        fun storedColor(): String = if (on) "1,$brightness,$red,$green,$blue" else "0,0,0,0,0"
        fun storedEffect(): String = if (on) effect?.effectName.orEmpty() else ""
    }

    fun stored(color: String, effect: String): Desired {
        val values = color.split(",").mapNotNull { it.toIntOrNull() }
        if (values.size != 5 || values[0] != 1) return Desired(on = false)
        return Desired(
            on = true,
            brightness = clamp(values[1]),
            red = clamp(values[2]),
            green = clamp(values[3]),
            blue = clamp(values[4]),
            effect = LedEffects.Effect.from(effect),
        )
    }

    fun command(payload: String, previous: Desired): Desired {
        val json = JSONObject(payload)
        if (!json.optString("state", "ON").equals("ON", ignoreCase = true)) return Desired(on = false)
        val brightness = if (json.has("brightness")) clamp(json.getInt("brightness"))
            else previous.takeIf { it.on }?.brightness ?: 255
        val color = json.optJSONObject("color")
        return Desired(
            on = true,
            brightness = brightness,
            red = clamp(color?.optInt("r", 255) ?: previous.takeIf { it.on }?.red ?: 255),
            green = clamp(color?.optInt("g", 255) ?: previous.takeIf { it.on }?.green ?: 255),
            blue = clamp(color?.optInt("b", 255) ?: previous.takeIf { it.on }?.blue ?: 255),
            effect = LedEffects.Effect.from(json.optString("effect")),
        )
    }

    /** Null means the desired state is not backed by a confirmed current actuation. */
    fun statePayload(desired: Desired, actuationKnown: Boolean, effectStatus: LedEffectController.Status): String? {
        if (!actuationKnown) return null
        if (!desired.on) return """{"state":"OFF"}"""
        if (desired.effect != null && effectStatus != LedEffectController.Status.RUNNING) return null
        val effect = desired.effect?.effectName ?: "none"
        return """{"state":"ON","color_mode":"rgb","brightness":${desired.brightness},"color":{"r":${desired.red},"g":${desired.green},"b":${desired.blue}},"effect":"$effect"}"""
    }

    private fun clamp(value: Int) = value.coerceIn(0, 255)
}
