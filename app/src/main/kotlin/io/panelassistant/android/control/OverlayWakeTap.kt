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
class OverlayWakeTap(
    context: Context,
    private val observer: PanelTouchObserver = PanelTouchObserver.shared(context),
) : WakeTap {
    @Volatile private var subscription: PanelTouchObserver.Subscription? = null
    private val generation = AtomicLong()

    override fun canArm(): Boolean = observer.canObserve()

    @Synchronized
    override fun arm(onTap: () -> Unit): Boolean {
        if (!canArm()) return false
        val token = generation.incrementAndGet()
        subscription?.close()
        subscription = null
        val fired = AtomicBoolean()
        val installed = observer.captureWakeTouches {
            if (generation.get() == token && fired.compareAndSet(false, true)) {
                Log.d(TAG, "tap while dark -> wake")
                // Screen wake can call helper/root I/O; never run it on the main touch callback.
                Thread({
                    runCatching { onTap() }.onFailure {
                        fired.set(false) // an exceptional wake must leave the next tap able to retry
                        Log.w(TAG, "wake onTap failed: ${it.message}")
                    }
                }, "screen-wake-tap").start()
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
