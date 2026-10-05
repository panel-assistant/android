package io.panelassistant.android.control

/**
 * The backlight-brightness operations [ScreenController] needs — a seam over [BrightnessController] so the
 * never-blank screen logic can be unit-tested with a fake. Implemented by [BrightnessController].
 */
interface Backlight {
    /** Effective backlight, 0–255 (reads sysfs actual_brightness where available, else the Android setting). */
    fun getBrightness(): Int

    /** Set brightness, floored at a minimum-visible level — a dim command can never blank the panel. */
    fun setBrightness(level: Int)

    /** Set the raw level with NO floor (0 allowed) — for [ScreenController]'s screen-off fallback only. */
    fun setBrightnessRaw(level: Int)
}
