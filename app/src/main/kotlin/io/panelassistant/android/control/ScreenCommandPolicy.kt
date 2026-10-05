package io.panelassistant.android.control

import org.json.JSONObject

/**
 * Pure parsing and sequencing policy for the Home Assistant screen light command.
 *
 * A missing brightness is carried as absent, never as zero and never as the current level, because
 * the two commands ask for different things: `{"state":"ON"}` asks for the screen to be on and says
 * nothing about brightness, while `{"state":"ON","brightness":n}` asks for both. The bridge publishes
 * the level actually in force either way.
 */
internal object ScreenCommandPolicy {
    /** [brightness] is null when the command did not carry one. A present value is already floored. */
    data class Command(val on: Boolean, val brightness: Int?)

    fun parse(payload: String): Command {
        val json = JSONObject(payload)
        val on = json.optString("state", "ON").equals("ON", ignoreCase = true)
        val brightness = if (json.has("brightness") && !json.isNull("brightness")) {
            json.getInt("brightness").coerceIn(BrightnessController.MIN_VISIBLE, 255)
        } else {
            null
        }
        return Command(on, brightness)
    }

    /**
     * Run an ON command against the screen. The screen is lit first if it is not already; a bare ON on
     * a lit panel therefore touches no actuator. An explicit brightness is applied whether or not a
     * wake was needed, and the level in force is remembered as the off/on restore point and reported.
     *
     * Returns false when the wake actuator failed. Nothing is applied or published then, so the state
     * Home Assistant holds stays whatever was last true rather than an ON the panel could not deliver.
     */
    fun executeOn(
        command: Command,
        ensureOn: () -> WakeOutcome,
        setBrightness: (Int) -> Unit,
        commandedLevel: () -> Int,
        noteLevel: (Int) -> Unit,
        publish: (Int) -> Unit,
    ): Boolean {
        require(command.on) { "executeOn takes an ON command" }
        if (ensureOn() == WakeOutcome.ACTUATION_FAILED) return false
        val explicit = command.brightness
        if (explicit != null) setBrightness(explicit)
        val level = explicit ?: commandedLevel().coerceAtLeast(1)
        noteLevel(level)
        publish(level)
        return true
    }
}
