package io.panelassistant.android.control

import android.content.Context
import android.util.Log
import io.panelassistant.android.platform.WakeTap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Touch-to-wake through a full-screen consuming overlay. Android owns the whole gesture from DOWN,
 * so disarming after wake cancels that stream instead of passing its remaining events to the renderer.
 * Ordinary touch observation remains non-consuming while the screen is awake.
 *
 * Needs `SYSTEM_ALERT_WINDOW` (root-granted for the navbar). [canArm] reports whether it's held so
 * ScreenController can degrade a screen-off to a dim when there'd otherwise be no guaranteed local wake.
 */
class OverlayWakeTap internal constructor(
    private val canObserve: () -> Boolean,
    private val captureWakeTouches: (onTouch: () -> Unit) -> AutoCloseable?,
    // Screen wake can call helper/root I/O; never run it on the main touch callback.
    private val runWake: (() -> Unit) -> Unit = { Thread(it, "screen-wake-tap").start() },
) : WakeTap {
    constructor(context: Context, observer: PanelTouchObserver = PanelTouchObserver.shared(context)) :
        this(observer::canObserve, observer::captureWakeTouches)

    @Volatile private var subscription: AutoCloseable? = null
    private val generation = AtomicLong()

    override fun canArm(): Boolean = canObserve()

    @Synchronized
    override fun arm(onTap: () -> Unit): Boolean {
        if (!canArm()) return false
        val token = generation.incrementAndGet()
        subscription?.close()
        subscription = null
        val waking = AtomicBoolean()
        val installed = captureWakeTouches {
            if (generation.get() == token && waking.compareAndSet(false, true)) {
                Log.d(TAG, "tap while dark -> wake")
                runWake {
                    try {
                        onTap()
                    } catch (e: Throwable) {
                        Log.w(TAG, "wake onTap failed: ${e.message}")
                    } finally {
                        // A wake that lit the screen disarmed this generation, so this admits nothing more.
                        // One that returned or threw with the screen still dark must leave the next tap
                        // able to retry: a failed wake is an ordinary return, not only an exception.
                        waking.set(false)
                    }
                }
            }
        }
        if (installed == null || generation.get() != token) {
            installed?.close()
            Log.w(TAG, "wake overlay was not confirmed; refusing a true screen-off")
            return false
        }
        subscription = installed
        return true
    }

    @Synchronized
    override fun disarm() {
        generation.incrementAndGet()
        subscription?.close()
        subscription = null
    }

    companion object {
        private const val TAG = "ha-paneld/wake"
    }
}
