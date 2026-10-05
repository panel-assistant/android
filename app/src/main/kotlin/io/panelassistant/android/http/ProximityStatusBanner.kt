package io.panelassistant.android.http

/** Project the same explicit calibration phases used by the configuration card. */
internal object ProximityStatusBanner {
    fun titleKey(
        enabled: Boolean,
        present: Boolean,
        phase: String,
        health: String,
        active: Boolean,
        wakeReady: Boolean,
    ): String? = when {
        !enabled -> null
        active || phase == "calibrating" -> "configure.proximity.setup.calibrating"
        !present || health == "source_unavailable" || phase == "source_unavailable" || phase == "unavailable" ->
            "configure.proximity.setup.unavailable"
        wakeReady -> null
        phase == "calibration_required" -> "configure.proximity.setup.required"
        else -> "dashboard.banner.proximity_learning.title"
    }
}
