package io.panelassistant.android.util

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import io.panelassistant.android.PaneldService

/** Same-boot OS authority for retrying the writer-free Guard maintenance successor. */
internal object GuardDbSuccessorAlarm {
    internal const val ACTION = "io.panelassistant.android.action.GUARD_DB_SUCCESSOR_RETRY"
    internal const val REQUEST_CODE = 0x48414752
    internal const val DELAY_MS = 1_000L

    fun schedule(context: Context) {
        val alarm = alarmManager(context)
        val operation = retryIntent(context)
            ?: error("could not create Guard DB successor alarm authority")
        val triggerAt = SystemClock.elapsedRealtime() + DELAY_MS
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !alarm.canScheduleExactAlarms()) {
            alarm.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, operation)
            return
        }
        try {
            alarm.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, operation)
        } catch (_: SecurityException) {
            // Exact-alarm authority may be revoked between the API-31 capability check and publish.
            alarm.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, operation)
        }
    }

    private fun alarmManager(context: Context): AlarmManager =
        requireNotNull(context.getSystemService(AlarmManager::class.java))

    private fun retryIntent(context: Context): PendingIntent? =
        PendingIntent.getForegroundService(
            context,
            REQUEST_CODE,
            Intent(context, PaneldService::class.java).setAction(ACTION),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
}
