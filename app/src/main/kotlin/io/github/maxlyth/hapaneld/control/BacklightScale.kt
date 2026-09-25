package io.github.maxlyth.hapaneld.control

import io.github.maxlyth.hapaneld.device.BacklightRoute
import io.github.maxlyth.hapaneld.hardware.TransferCurve

/**
 * The one place Home Assistant's 0..255 brightness meets the backlight's two actuators, Android's
 * `SCREEN_BRIGHTNESS` setting and the node ha-paneld writes itself. Pure; unit-tested in BacklightScaleTest.
 *
 * - Identity curve: both actuators carry the Home Assistant level, byte-identical to before curves existed.
 * - [BacklightRoute.NODE]: the setting keeps the Home Assistant level and the node write is curved. For
 *   panels where ha-paneld is the node's only writer; the setting then reads back exactly by construction.
 * - [BacklightRoute.SETTING]: the setting carries the curved value, because the firmware pushes the setting
 *   to the node itself (NSPanel 86, within about 2 s) and would overwrite a curved node write. ha-paneld's own
 *   node write follows the setting linearly so the two writers agree. The level Home Assistant set cannot be
 *   recovered from the setting, so it is kept as an [OwnedLevel] and answered while the setting still holds
 *   the value that level produced; anything else is an external change and is read through the inverse.
 *
 * Auto-brightness computes its target on the Home Assistant scale and writes it through the same path, so a
 * curved profile shapes its output too (perceptual gamma halves a mid-range 128 to about 64 on the node).
 */
internal class BacklightScale(
    private val curve: TransferCurve,
    route: BacklightRoute,
    private val owned: OwnedLevelStore = OwnedLevelStore.NONE,
) {
    /** The Home Assistant [level] ha-paneld last wrote, and the [setting] value that write produced. */
    data class OwnedLevel(val level: Int, val setting: Int)

    /** Durable home of the [OwnedLevel], so a process restart still reads back the level Home Assistant set. */
    interface OwnedLevelStore {
        fun load(): OwnedLevel?
        fun save(owned: OwnedLevel)

        companion object {
            val NONE = object : OwnedLevelStore {
                override fun load(): OwnedLevel? = null
                override fun save(owned: OwnedLevel) = Unit
            }
        }
    }

    private val throughSetting = route == BacklightRoute.SETTING && curve != TransferCurve.Identity

    /** Whether reads are already on the Home Assistant scale (true for every curved profile). */
    val curved: Boolean get() = curve != TransferCurve.Identity

    /** The curve ha-paneld's node write applies to [settingFor]'s value. */
    val nodeCurve: TransferCurve get() = if (throughSetting) TransferCurve.Identity else curve

    /** The `SCREEN_BRIGHTNESS` value for a Home Assistant [level]; 0 stays 0. */
    fun settingFor(level: Int): Int =
        if (throughSetting) curve.toHardware(level, SETTING_MAX) else level.coerceIn(0, SETTING_MAX)

    /** Remember an own write of [level] that produced [setting]. Only the setting route needs it. */
    fun recordOwned(level: Int, setting: Int) {
        if (throughSetting) owned.save(OwnedLevel(level.coerceIn(0, SETTING_MAX), setting))
    }

    /** The Home Assistant level a `SCREEN_BRIGHTNESS` value stands for; a negative (unknown) value passes through. */
    fun levelFromSetting(setting: Int): Int {
        if (!throughSetting || setting < 0) return setting
        owned.load()
            // Self-validating: a record written under another curve no longer maps to this setting.
            ?.takeIf { it.setting == setting && curve.toHardware(it.level, SETTING_MAX) == setting }
            ?.let { return it.level }
        return curve.toLevel(setting, SETTING_MAX)
    }

    /** The Home Assistant level an observed node reading ([actual] of [maximum]) stands for. */
    fun levelFromNode(actual: Int, maximum: Int): Int =
        if (throughSetting) {
            curve.toLevel(TransferCurve.Identity.toLevel(actual, maximum), SETTING_MAX)
        } else {
            curve.toLevel(actual, maximum)
        }

    companion object {
        private const val SETTING_MAX = 255
        val IDENTITY = BacklightScale(TransferCurve.Identity, BacklightRoute.NODE)
    }
}
