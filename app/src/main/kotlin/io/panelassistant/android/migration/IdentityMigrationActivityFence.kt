package io.panelassistant.android.migration

import android.app.Activity
import io.panelassistant.android.AppIdentity
import io.panelassistant.android.PaneldService

/** What an activity of this app must do instead of showing itself, given the start disposition. */
internal enum class ActivityDisposition { SHOW, FORWARD_TO_SUCCESSOR, RUN_MIGRATION_UNSEEN }

internal fun activityDisposition(start: StartDisposition): ActivityDisposition = when (start) {
    // The panel belongs to the successor now. If anything still opens this app, including a HOME press
    // the platform resolved to it, the person ends up where the panel actually is.
    StartDisposition.RETIRED_BRIDGE -> ActivityDisposition.FORWARD_TO_SUCCESSOR
    // The legacy app still owns the panel and is what the person must keep seeing. Starting this app is
    // only how its migration gets to run.
    StartDisposition.PASSIVE_SUCCESSOR -> ActivityDisposition.RUN_MIGRATION_UNSEEN
    StartDisposition.HELD_SUCCESSOR, StartDisposition.NORMAL -> ActivityDisposition.SHOW
}

internal object IdentityMigrationActivityFence {
    /**
     * True when [activity] is finished instead of shown; the caller returns from its lifecycle method.
     * Activities consult the fence from several lifecycle methods, so it acts once per activity.
     */
    fun stop(activity: Activity): Boolean {
        val disposition = activityDisposition(IdentityMigrationGate.disposition(activity))
        if (disposition == ActivityDisposition.SHOW) return false
        if (!activity.isFinishing) {
            runCatching {
                when (disposition) {
                    ActivityDisposition.FORWARD_TO_SUCCESSOR ->
                        activity.packageManager.getLaunchIntentForPackage(AppIdentity.SUCCESSOR)
                            ?.let(activity::startActivity)
                    else -> PaneldService.start(activity)
                }
            }
            activity.finish()
        }
        return true
    }
}
