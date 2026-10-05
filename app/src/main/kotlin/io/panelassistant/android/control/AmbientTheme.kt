package io.panelassistant.android.control

/**
 * The Ambient dashboard theme's verdict: dark or light, from the room's normalised light level
 * ([AdaptiveLuxCurve.normalizedLevel], 0 = this room's dark end, 1 = its bright end).
 *
 * Two rules keep it from flickering, and each is the whole defence against one kind of noise:
 *
 *  - **Hysteresis.** Dark needs the level at or below [darkAtOrBelow]; light needs it at or above
 *    [lightAtOrAbove]. Between the two the verdict holds, so a room sitting on one threshold cannot
 *    alternate.
 *  - **Dwell.** A change is adopted only after the level has stayed beyond the other threshold for
 *    [dwellMs] without interruption. A reading back inside the gap or on the current side restarts
 *    the wait, so a light switched on for half a minute changes nothing.
 *
 * The first verdict, with nothing to hold, still waits out one dwell. The caller follows Home
 * Assistant until then rather than guessing from one reading. Time is the caller's monotonic clock;
 * this class keeps no clock of its own, so every rule is directly testable.
 */
internal class AmbientThemeDecider(
    initialDark: Boolean?,
    private val darkAtOrBelow: Double = DARK_AT_OR_BELOW,
    private val lightAtOrAbove: Double = LIGHT_AT_OR_ABOVE,
    private val dwellMs: Long = DWELL_MS,
) {
    init {
        require(darkAtOrBelow < lightAtOrAbove) { "the dark threshold must sit below the light threshold" }
        require(dwellMs > 0L) { "dwell must be positive" }
    }

    /** The verdict in force, or null before the first one. */
    var dark: Boolean? = initialDark
        private set

    private var candidate: Boolean? = null
    private var candidateSinceMs = 0L

    /** Record [level] observed at [nowMs]; true when the verdict changed. */
    fun observe(nowMs: Long, level: Double): Boolean {
        if (!level.isFinite()) return false
        val side = when {
            level <= darkAtOrBelow -> true
            level >= lightAtOrAbove -> false
            else -> null
        }
        if (side == null || side == dark) {
            candidate = null
            return false
        }
        if (candidate != side) {
            candidate = side
            candidateSinceMs = nowMs
        }
        if (nowMs - candidateSinceMs < dwellMs) return false
        dark = side
        candidate = null
        return true
    }

    /** When a pending change would be adopted if the level held, or null when none is pending. */
    fun pendingDeadlineMs(): Long? = candidate?.let { candidateSinceMs + dwellMs }

    /** The change being waited out, or null. */
    fun pendingDark(): Boolean? = candidate

    /** Forget a pending change without touching the verdict (a new evaluation session starts). */
    fun restartDwell() {
        candidate = null
    }

    companion object {
        /**
         * Thresholds on the normalised level, calibrated by replaying about three months of six panels'
         * recorded illuminance, in five rooms, through this model and scoring each verdict against an
         * independent light sensor in the same room. At 0.40/0.55 the verdict was dark for 89-99% of
         * the minutes that sensor read under 5 lx, and light for 94-100% of the minutes it read over
         * 30 lx, at about two switches a day. The first guess, 0.15/0.30, missed a third to two thirds
         * of the dark minutes. On the fixed curve a cold model uses, 0.40 is about 190 lx and 0.55
         * about 520 lx; the learned range replaces that within a few days.
         */
        const val DARK_AT_OR_BELOW = 0.40
        const val LIGHT_AT_OR_ABOVE = 0.55
        const val DWELL_MS = 60_000L
    }
}

/** Why the Ambient theme resolves the way it does, as a stable wire token for status and diagnostics. */
internal enum class AmbientThemeReason(val wire: String) {
    /** The room is dark and the verdict is dark. */
    ROOM_DARK("room_dark"),
    /** The room is light and the verdict is light. */
    ROOM_LIGHT("room_light"),
    /** The light source has stopped reporting; the last verdict is held rather than dropped. */
    HOLDING("holding_source_unavailable"),
    /** Auto-brightness is on but no verdict exists yet; Home Assistant decides until one does. */
    WAITING("waiting_for_verdict"),
    /** Auto-brightness is off, so the ambient model is not running; Home Assistant decides. */
    AUTO_BRIGHTNESS_OFF("auto_brightness_off"),
    /** The panel has no light sensor and no Home Assistant illuminance source; Home Assistant decides. */
    NO_LIGHT_SOURCE("no_light_source");

    companion object {
        fun of(
            autoBrightness: Boolean,
            hasLightSource: Boolean,
            verdictDark: Boolean?,
            sourceAvailable: Boolean,
        ): AmbientThemeReason = when {
            !hasLightSource -> NO_LIGHT_SOURCE
            !autoBrightness -> AUTO_BRIGHTNESS_OFF
            verdictDark == null -> WAITING
            !sourceAvailable -> HOLDING
            verdictDark -> ROOM_DARK
            else -> ROOM_LIGHT
        }
    }
}

/** What status and diagnostics say about the Ambient theme. */
internal data class AmbientThemeReport(val reason: AmbientThemeReason, val level: Double?)

/** The ambient model's contribution to the theme, as the controller last evaluated it. */
internal data class AmbientThemeSnapshot(
    /** The verdict, independent of whether the policy is Ambient or auto-brightness is on. */
    val verdictDark: Boolean?,
    /** The last normalised level, or null before the model has evaluated one. */
    val level: Double?,
    /** The change being waited out, or null. */
    val pendingDark: Boolean?,
    val sourceAvailable: Boolean,
)
