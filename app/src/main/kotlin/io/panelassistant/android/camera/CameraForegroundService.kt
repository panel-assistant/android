package io.panelassistant.android.camera

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import io.panelassistant.android.R
import io.panelassistant.android.nativeString

/**
 * How the camera owner asks Android for camera-typed foreground standing before it opens the device.
 * Injectable so the owner's lifecycle can be exercised without a real service.
 */
interface CameraForegroundGate {
    /**
     * True only once Android has confirmed the camera-typed foreground service. Blocking. [owner] is
     * the session attempt the standing belongs to, and the only token that can release it again.
     */
    fun promote(owner: Long, timeoutMs: Long): Boolean

    /** Releases [owner]'s standing. A release by anyone but the current holder does nothing. */
    fun demote(owner: Long)

    /** Releases standing whoever holds it. Only for the camera subsystem stopping outright. */
    fun demoteAll()
}

/**
 * A foreground service that exists only to carry the `camera` foreground-service type while a camera
 * session is open. It owns no hardware: the session owner lives in `PaneldService`, subject to that
 * service's drain order and process boundary, and starts this one on demand.
 *
 * Why a second service. `PaneldService` fixes its foreground type once, at `onCreate`, and is
 * legitimately started from the background by the boot receiver — which is exactly the case in which
 * Android 11+ refuses camera access to a foreground service for its whole lifetime. A fresh
 * `startForegroundService` issued while an activity is visible is the only shape whose while-in-use
 * eligibility is unambiguous on every supported Android version.
 *
 * `START_NOT_STICKY`: Android must never resurrect a camera session on its own; idle cost is zero.
 *
 * Every start calls `startForeground` first, whatever has happened since it was issued: a start that
 * is stopped before that call kills the process. Whether the service then stays or stops itself is
 * [CameraForegroundPromotions]' decision, which also issues the owner's start and stop under its own
 * lock so neither can interleave with the other. A stop names its own `startId`: `stopSelfResult` is
 * refused by Android when a newer start has been delivered since, so a burst start that arrived after
 * this one's decision is never brought down unanswered — the same crash by another route. Standing
 * itself is owned by the session that asked for it, so an ended session tearing down out of order
 * cannot stop the service that a live session is using.
 */
class CameraForegroundService : Service() {

    /** Whether this instance has served a start; its destroy answers a promotion only if it has not. */
    private var served = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        served = true
        val outcome = runCatching {
            val notification = notification()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA)
            } else {
                startForeground(NOTIF_ID, notification)
            }
        }
        outcome.onFailure {
            // Android 12+ throws here when no activity is visible; Android 11 succeeds silently and
            // refuses at openCamera instead, which the owner classifies separately.
            Log.w(TAG, "camera foreground promotion refused: ${it.javaClass.simpleName}")
        }
        val keep = promotions.started(outcome.isSuccess)
        if (!keep) stopSelfResult(startId)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        promotions.destroyed(served)
        super.onDestroy()
    }

    private fun notification(): Notification {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, nativeString(R.string.camera_channel), NotificationManager.IMPORTANCE_MIN),
            )
        }
        // The notification is a platform requirement, not the indication: a kiosk panel has no
        // reachable shade, so the room is told by the overlay and the LED.
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(nativeString(R.string.app_name))
            .setContentText(nativeString(R.string.camera_in_use))
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setSilent(true)
            .build()
    }

    companion object {
        private const val TAG = "ha-paneld/camera-fgs"
        private const val CHANNEL_ID = "ha-paneld-camera"
        private const val NOTIF_ID = 7

        /** Shared by the gate and every instance: the process-wide truth about starts and stops. */
        internal val promotions = CameraForegroundPromotions()
    }
}

class AndroidCameraForegroundGate(private val context: Context) : CameraForegroundGate {
    override fun promote(owner: Long, timeoutMs: Long): Boolean {
        val promotions = CameraForegroundService.promotions
        val intent = Intent(context, CameraForegroundService::class.java)
        // The start is issued inside the request, under the registry's lock: no stop can land between
        // the request being recorded and Android accepting the start.
        val promotion = promotions.request(owner) {
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent)
                else context.startService(intent)
            }.onFailure {
                Log.w(TAG, "camera foreground start refused: ${it.javaClass.simpleName}")
            }.isSuccess
        }
        val granted = promotions.await(promotion, timeoutMs)
        if (!granted && promotions.hasUnansweredStart) {
            Log.w(TAG, "camera foreground promotion not answered within ${timeoutMs}ms; its start will stop itself")
        }
        return granted
    }

    override fun demote(owner: Long) {
        // The stop is issued inside the release, under the registry's lock, and only when no start is
        // unanswered; otherwise that start stops itself once it has called startForeground. A release
        // from a session that no longer holds standing does nothing at all.
        CameraForegroundService.promotions.release(owner) { stopService() }
    }

    override fun demoteAll() {
        CameraForegroundService.promotions.releaseAll { stopService() }
    }

    private fun stopService() {
        runCatching { context.stopService(Intent(context, CameraForegroundService::class.java)) }
    }

    private companion object {
        const val TAG = "ha-paneld/camera-fgs"
    }
}
