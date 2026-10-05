package io.panelassistant.android

import io.panelassistant.android.control.BuiltinDashboard
import io.panelassistant.android.control.SystemController

/**
 * Tiny process-wide status, shared with the launcher [MainActivity] which has no binding to the
 * service. Projects the two renderer-specific recovery authorities without copying either budget:
 * [BuiltinDashboard] owns built-in WebView retry suppression, while the watchdog owns foreign-app
 * relaunch backoff. Live MQTT connection state stays with [MqttBridge.isConnected].
 */
object PanelStatus {
    enum class DashboardRecoveryState { NONE, BUILTIN_RENDERER, EXTERNAL_RENDERER }

    private data class ExternalRecovery(val target: String, val blocked: Boolean)

    @Volatile
    private var externalRecovery = ExternalRecovery(target = "", blocked = false)

    /** Publish the watchdog-owned foreign-app projection, keyed so an old renderer cannot suppress a
     *  replacement before the watchdog's next poll. Returns true only when the warning rises. */
    @Synchronized
    internal fun publishExternalRecovery(target: String, blocked: Boolean): Boolean {
        val previous = externalRecovery
        externalRecovery = ExternalRecovery(target, blocked)
        return blocked && !(previous.target == target && previous.blocked)
    }

    @Synchronized
    internal fun clearExternalRecovery() {
        externalRecovery = ExternalRecovery(target = "", blocked = false)
    }

    /** Current recovery admission for the configured renderer. Built-in truth is derived directly from
     *  its monotonic latch, so health and launcher decisions do not wait for a watchdog poll. */
    fun dashboardRecoveryState(
        configuredRenderer: String,
        ownPackage: String,
        now: Long,
    ): DashboardRecoveryState {
        if (SystemController.isBuiltinSelection(configuredRenderer, ownPackage)) {
            return if (BuiltinDashboard.rendererLatched(now)) {
                DashboardRecoveryState.BUILTIN_RENDERER
            } else {
                DashboardRecoveryState.NONE
            }
        }
        val external = externalRecovery
        return if (external.target == configuredRenderer && external.blocked) {
            DashboardRecoveryState.EXTERNAL_RENDERER
        } else {
            DashboardRecoveryState.NONE
        }
    }
}
