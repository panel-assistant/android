package io.panelassistant.android

import io.panelassistant.android.control.SystemController

/** Pure renderer-aware gate for leaving the launcher after an app update. */
internal object PostUpdateReturnPolicy {
    fun dashboardReady(
        configuredRenderer: String,
        builtInUrlConfigured: Boolean,
        dashboardLaunchAvailable: Boolean,
        dashboardRecoveryBlocked: Boolean,
    ): Boolean {
        if (!dashboardLaunchAvailable || dashboardRecoveryBlocked) return false
        return if (configuredRenderer == SystemController.BUILTIN_DASHBOARD) {
            // The native renderer talks directly to HA and its own activity holds until any entity-filter
            // preparation is complete. MQTT is optional and must not strand these panels on the launcher.
            builtInUrlConfigured
        } else {
            // A launchable external renderer owns its own HA readiness. MQTT is an independent control
            // transport and may be intentionally disabled, so it must not strand the panel here.
            true
        }
    }
}
