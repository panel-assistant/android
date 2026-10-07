package io.panelassistant.android.input

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import io.panelassistant.android.PaneldService
import io.panelassistant.android.platform.AccessibilityActions
import io.panelassistant.android.util.GuardDbProcessAdmission
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Optional hardware-button capture. Hardware/capacitive buttons that emit standard Android
 * KeyEvents are reported to HA via [ButtonBus] → MQTT `event.<panel>_button`. Enabled at
 * provisioning with `settings put secure enabled_accessibility_services <pkg>/<this>`.
 *
 * ⚠️ Per-hardware uncertain: panels whose buttons are GPIO-only (no KeyEvent) won't surface here
 * and would need a vendor GPIO HAL (load-if-present), not this service. Needs on-device validation.
 * Requires `canRequestFilterKeyEvents` + `flagRequestFilterKeyEvents` in the a11y config XML.
 */
class PanelAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        if (!GuardDbProcessAdmission.ordinaryMutationsAllowed()) return
        instance = this
        revivePaneldService()
    }

    /**
     * Bring [PaneldService] up when the process was revived only for this accessibility bind.
     *
     * A foreground-start timeout kills the process *and* drops the started-service record, so there is
     * no START_STICKY re-create waiting to happen. The accessibility service is bound by the system
     * independently of that record, so the process can come back for it alone — no `:8888`, no MQTT and
     * no log shipping. Observed lasting over eight hours, until `MainActivity` was started by hand.
     * This is the route back.
     *
     * Three reasons this cannot start a second instance or fight a deliberate stop:
     * - `PaneldService.start` issues `startForegroundService`, and Android delivers that to the single
     *   existing instance when one is running; `onStartCommand`'s `started` guard makes the redelivery
     *   inert, so `:8888` is never bound twice.
     * - `onServiceConnected` fires on a bind, not on a timer, so there is no loop to run. A deliberate
     *   stop does not unbind accessibility and therefore does not come back through here.
     * - `PaneldService.start` itself refuses while an upgrade holds the service down, and while the
     *   bridge is retired or Guard DB maintenance is required.
     *
     * Never let this throw: `onServiceConnected` failing takes the accessibility bind down with it, and
     * on Android 12+ a foreground start from a background process can be refused outright.
     */
    private fun revivePaneldService() {
        runCatching { PaneldService.start(this) }
            .onFailure { Log.w(TAG, "could not revive PaneldService from the accessibility bind", it) }
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (!GuardDbProcessAdmission.ordinaryMutationsAllowed()) return super.onKeyEvent(event)
        val name = KeyEvent.keyCodeToString(event.keyCode)
        ButtonEventPolicy.accessibility(
            down = event.action == KeyEvent.ACTION_DOWN,
            repeatCount = event.repeatCount,
            eventType = name,
        )?.let {
            Log.d(TAG, "key down: $name")
            ButtonBus.emit(it)
        }
        // Do not consume — let the system handle the key normally.
        return super.onKeyEvent(event)
    }

    companion object : AccessibilityActions {
        private const val TAG = "ha-paneld/buttons"

        // Set while the a11y service is connected; lets the MQTT command path perform global nav
        // actions (back/recents/home) with no root — performGlobalAction is an a11y capability.
        @Volatile
        private var instance: PanelAccessibilityService? = null

        override fun back(): Boolean = instance?.performGlobalAction(GLOBAL_ACTION_BACK) ?: false
        override fun recents(): Boolean = instance?.performGlobalAction(GLOBAL_ACTION_RECENTS) ?: false


        /**
         * Dispatch a short accessibility gesture and wait for its completion when called off-main, as
         * all production callers are. Completion matters to the navbar overlay: it stays non-touchable
         * until the injected tap has landed, avoiding a feedback loop into its own edge strip.
         */
        override fun tap(x: Int, y: Int): Boolean {
            val service = instance ?: return false
            val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, 1))
                .build()
            val completed = AtomicBoolean(false)
            val done = CountDownLatch(1)
            val callback = object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    completed.set(true)
                    done.countDown()
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    done.countDown()
                }
            }
            val handler = Handler(Looper.getMainLooper())
            val accepted = runCatching { service.dispatchGesture(gesture, callback, handler) }
                .getOrDefault(false)
            if (!accepted) return false
            if (Looper.myLooper() == Looper.getMainLooper()) return true
            return runCatching { done.await(GESTURE_TIMEOUT_MS, TimeUnit.MILLISECONDS) && completed.get() }
                .getOrDefault(false)
        }

        private const val GESTURE_TIMEOUT_MS = 1000L
    }
}
