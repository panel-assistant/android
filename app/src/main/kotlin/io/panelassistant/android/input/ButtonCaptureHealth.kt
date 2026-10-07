package io.panelassistant.android.input

import io.panelassistant.android.AppIdentity
import io.panelassistant.android.i18n.CatalogueText

/** Pure diagnostic projection for the independent Accessibility and helper-evdev capture sources. */
internal object ButtonCaptureHealth {
    data class Result(val status: String, val note: CatalogueText)

    private fun note(id: String, vararg values: Pair<String, Any>) =
        CatalogueText("dashboard.capability.note.$id", *values)

    fun evaluate(
        accessibility: Boolean,
        evdevButtonCount: Int,
        evdev: EvdevButtonClient.Snapshot,
        packageName: String,
    ): Result {
        val streamActive = evdev.state == EvdevButtonClient.State.ACTIVE
        val verified = streamActive && evdev.mode in setOf(
            EvdevStreamSession.Mode.RECONFIGURABLE,
            EvdevStreamSession.Mode.VERIFIED,
        )
        val detail = when {
            streamActive && evdev.mode == EvdevStreamSession.Mode.LEGACY -> "legacy helper stream; update the helper to verify node/grab setup"
            streamActive -> "verified helper stream"
            evdev.lastError != null -> "helper stream ${evdev.state.name.lowercase()}: ${evdev.lastError}"
            else -> "helper stream ${evdev.state.name.lowercase()}"
        }
        val count = "count" to evdevButtonCount
        return when {
            evdevButtonCount > 0 && verified && accessibility -> Result("ok", note("buttons_accessibility_verified", count))
            evdevButtonCount > 0 && verified -> Result("ok", note("buttons_verified", count))
            evdevButtonCount > 0 && streamActive && accessibility ->
                Result("degraded", note("buttons_accessibility_unverified_stream", count, "detail" to detail))
            evdevButtonCount > 0 && streamActive -> Result("degraded", note("buttons_unverified_stream", count, "detail" to detail))
            evdevButtonCount > 0 && accessibility ->
                Result("degraded", note("buttons_accessibility_not_verified", count, "detail" to detail))
            evdevButtonCount > 0 -> Result("none", note("buttons_not_verified", count, "detail" to detail))
            accessibility -> Result("ok", note("buttons_accessibility"))
            else -> Result("none", note("buttons_enable_no_root", "command" to "adb shell settings put secure enabled_accessibility_services ${AppIdentity.component(packageName, ".input.PanelAccessibilityService")} && adb shell settings put secure accessibility_enabled 1"))
        }
    }
}
