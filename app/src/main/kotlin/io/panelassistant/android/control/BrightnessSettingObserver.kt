package io.panelassistant.android.control

import android.database.ContentObserver
import android.os.Handler

/**
 * Attributes a `SCREEN_BRIGHTNESS` change nobody here wrote as an Android-system brightness preference.
 *
 * Android delivers [onChange] on the main looper, so only cheap bookkeeping runs there: the setting read,
 * the prior level, the screen-off owner, and the owned-write check. That check must run at delivery
 * because the attribution journal forgets a write after a few seconds. The darkness check can read
 * `bl_power` through `su` and queue for seconds behind other root work, so it and the capture run on
 * [background], which must run tasks one at
 * a time in submission order so successive changes are attributed in the order they happened.
 */
internal class BrightnessSettingObserver(
    handler: Handler?,
    initialLevel: Int,
    private val readSetting: () -> Int?,
    private val levelFromSetting: (Int) -> Int,
    private val consumeOwnedWrite: (Int) -> Boolean,
    private val observedDark: () -> Boolean?,
    private val noteExternal: (level: Int, prior: Int) -> Unit,
    private val background: (() -> Unit) -> Unit,
    private val captureOffGeneration: () -> Long? = { null },
    private val handleWhileOff: (Long, Int) -> Unit = { _, _ -> },
) : ContentObserver(handler) {
    // Touched only by the delivering looper.
    private var lastLevel = initialLevel

    override fun onChange(selfChange: Boolean) {
        val setting = readSetting() ?: return
        // Attribution matches the raw setting ha-paneld wrote; preferences are on the HA scale.
        val level = levelFromSetting(setting)
        val prior = lastLevel
        lastLevel = level
        if (consumeOwnedWrite(setting)) return
        // A delayed outside write belongs to the off it arrived during, never a newer off or preference.
        val offGeneration = captureOffGeneration()
        background {
            if (offGeneration != null) {
                handleWhileOff(offGeneration, level)
            } else if (captureOffGeneration() == null && observedDark() != true) {
                noteExternal(level, prior)
            }
        }
    }
}
