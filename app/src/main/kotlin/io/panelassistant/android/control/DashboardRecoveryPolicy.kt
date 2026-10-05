package io.panelassistant.android.control

/** Pure dashboard-watchdog policy. Runtime polling and privileged launches stay in [WatchdogController].
 *  It owns background timing for every renderer and dead-process recovery for foreign renderers. Built-in
 *  DEAD is a WebView latch owned by [BuiltinDashboard] and is routed around this foreign crash budget. */
internal class DashboardRecoveryPolicy(
    private val deadStreakLimit: Int,
    private val backgroundTimeoutMs: Long,
    private val crashLoop: CrashLoopTracker = CrashLoopTracker(),
) {
    enum class Action { NONE, RELAUNCH_DEAD, RETURN_FROM_BACKGROUND }
    data class Decision(val action: Action, val crashLooping: Boolean)

    private var targetPackage: String? = null
    private var deadStreak = 0
    private var backgroundSince: Long? = null
    private var crashLooping = false

    /** Consume one observation. Changing dashboard package atomically retires all state owned by the old
     *  target, so its crash history or background timer can never suppress or launch the replacement. */
    fun evaluate(pkg: String, state: AppState, now: Long): Decision {
        if (targetPackage != pkg) {
            resetState()
            targetPackage = pkg
        }
        val action = when (state) {
            AppState.FG -> {
                resetState(keepTarget = true)
                Action.NONE
            }
            AppState.BG -> {
                deadStreak = 0
                val since = backgroundSince
                if (since == null) {
                    backgroundSince = now
                    Action.NONE
                } else if (now - since >= backgroundTimeoutMs) {
                    backgroundSince = null
                    Action.RETURN_FROM_BACKGROUND
                } else {
                    Action.NONE
                }
            }
            AppState.DEAD -> {
                backgroundSince = null
                if (++deadStreak < deadStreakLimit) {
                    Action.NONE
                } else {
                    deadStreak = 0
                    if (crashLoop.onRelaunchAttempt(now)) {
                        Action.RELAUNCH_DEAD
                    } else {
                        crashLooping = true
                        Action.NONE
                    }
                }
            }
            AppState.UNKNOWN -> {
                deadStreak = 0
                Action.NONE
            }
        }
        return Decision(action, crashLooping)
    }

    /** Disable/stop boundary: no warning or target-owned recovery state survives it. */
    fun reset() {
        resetState()
        targetPackage = null
    }

    private fun resetState(keepTarget: Boolean = false) {
        deadStreak = 0
        backgroundSince = null
        crashLoop.reset()
        crashLooping = false
        if (!keepTarget) targetPackage = null
    }
}
