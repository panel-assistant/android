package io.panelassistant.android.http

/** Pure projection for the Dashboard Controls card's navigation capability and explanatory text. */
internal object ControlAvailability {
    const val INPUT_REQUIREMENT = "Accessibility or privileged input access"

    data class Navigation(
        val backEnabled: Boolean,
        val recentsEnabled: Boolean,
        val recentsRequirement: String,
        val rootlessNote: String,
    )

    fun navigation(
        accessibilityReady: Boolean,
        shizukuReady: Boolean,
        hasRecents: Boolean,
    ): Navigation {
        val inputReady = accessibilityReady || shizukuReady
        val supportedActions = buildList {
            if (inputReady) {
                add("Back")
                if (hasRecents) add("Recents")
            }
        }
        val note = if (inputReady) {
            "${supportedActions.joinToString(", ")} still ${if (supportedActions.size == 1) "works" else "work"}."
        } else {
            val unavailable = if (hasRecents) "Back and Recents need" else "Back needs"
            "$unavailable $INPUT_REQUIREMENT."
        }
        return Navigation(
            backEnabled = inputReady,
            recentsEnabled = inputReady && hasRecents,
            recentsRequirement = if (hasRecents) INPUT_REQUIREMENT
                else "a Recents/overview screen (absent on this panel)",
            rootlessNote = note,
        )
    }
}
