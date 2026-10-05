package io.panelassistant.android

internal enum class ProximityWizardCue {
    PREPARE, APPROACH, HOLD, MOVE_AWAY, WAIT_CLEAR, WAVE, NONE;

    companion object {
        fun fromWire(value: String): ProximityWizardCue = entries.firstOrNull {
            it.name.equals(value, ignoreCase = true)
        } ?: NONE
    }
}

/** Countdown is a presentation of a finite runtime cue, never a local calibration clock. */
internal fun proximityWizardCountdownSeconds(remainingMs: Long, durationMs: Long): Int? {
    if (durationMs <= 0 || remainingMs <= 0 || remainingMs > durationMs) return null
    return (remainingMs / 1000 + if (remainingMs % 1000 > 0) 1 else 0)
        .coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
}

internal fun proximityWizardUsesHand(stage: String, cue: ProximityWizardCue): Boolean =
    stage in setOf("wave_capture", "waves") || cue == ProximityWizardCue.WAVE

internal fun proximityWizardWaveCount(pattern: String): Int = if (pattern == "double") 2 else 1

internal fun proximityWizardHoldsNearPanel(stage: String, pattern: String): Boolean =
    stage == "near" || (stage == "wave_baseline" && pattern != "double")

internal fun proximityWizardHasLocalStepAction(stage: String): Boolean =
    stage in setOf("intro", "review", "saved", "cancelled", "timed_out", "failed", "unavailable")

internal enum class ProximityWizardCapabilities { BOTH, PRESENCE_ONLY, WAVE_ONLY, NEITHER }

internal fun proximityWizardCapabilities(presence: Boolean, wave: Boolean): ProximityWizardCapabilities = when {
    presence && wave -> ProximityWizardCapabilities.BOTH
    presence -> ProximityWizardCapabilities.PRESENCE_ONLY
    wave -> ProximityWizardCapabilities.WAVE_ONLY
    else -> ProximityWizardCapabilities.NEITHER
}
