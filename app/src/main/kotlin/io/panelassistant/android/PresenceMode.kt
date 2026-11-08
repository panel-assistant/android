package io.panelassistant.android

/**
 * Where the panel takes presence from. One definition for the setting's wire values and for what each
 * mode means to the proximity learner and to auto-sleep.
 */
enum class PresenceMode(val wire: String) {
    AUTOMATIC("automatic"),
    HOME_ASSISTANT("home_assistant"),
    MANUAL("manual"),
    HYBRID("hybrid"),
    OFF("off");

    /** The touch-labelled learner may install its model (ranged sources only). */
    val learnsFromTouches: Boolean get() = this == AUTOMATIC || this == HYBRID

    /** The explicit wizard model is used (Manual as captured, Hybrid as the learner's seed). */
    val usesWizardModel: Boolean get() = this == MANUAL || this == HYBRID

    companion object {
        const val KEY = "presence_mode"
        fun fromWire(value: String?): PresenceMode? = entries.firstOrNull { it.wire == value }
    }
}
