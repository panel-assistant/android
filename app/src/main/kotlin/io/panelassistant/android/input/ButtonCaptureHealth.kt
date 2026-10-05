package io.panelassistant.android.input

import io.panelassistant.android.AppIdentity

/** Pure diagnostic projection for the independent Accessibility and helper-evdev capture sources. */
internal object ButtonCaptureHealth {
    data class Result(val status: String, val note: String)

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
        return when {
            evdevButtonCount > 0 && verified && accessibility -> Result("ok", "accessibility key capture plus $evdevButtonCount profiled physical button(s) on a verified helper stream")
            evdevButtonCount > 0 && verified -> Result("ok", "$evdevButtonCount profiled physical button(s) on a verified helper stream")
            evdevButtonCount > 0 && streamActive && accessibility -> Result("degraded", "accessibility key capture plus $evdevButtonCount profiled physical button(s) on an unverified stream ($detail)")
            evdevButtonCount > 0 && streamActive -> Result("degraded", "$evdevButtonCount profiled physical button(s) are streaming but unverified ($detail)")
            evdevButtonCount > 0 && accessibility -> Result("degraded", "accessibility key capture works; $evdevButtonCount profiled physical button(s) are not verified ($detail)")
            evdevButtonCount > 0 -> Result("none", "$evdevButtonCount profiled physical button(s) are not verified ($detail)")
            accessibility -> Result("ok", "accessibility key capture enabled")
            else -> Result("none", "enable (no root): adb shell settings put secure enabled_accessibility_services ${AppIdentity.component(packageName, ".input.PanelAccessibilityService")} && adb shell settings put secure accessibility_enabled 1")
        }
    }
}
