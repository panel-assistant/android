package io.panelassistant.android.control

import android.graphics.PixelFormat
import android.os.Build
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Real Android input dispatch: removing the wake window must never retarget its gesture. */
@RunWith(AndroidJUnit4::class)
class OverlayWakeTapInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test
    fun wakingGestureNeverReachesDashboardAndTheNextTapDoes() {
        val wakeTap = OverlayWakeTap(context)
        try {
            withDashboard { dashboardEvents, x, y ->
                val woken = CountDownLatch(1)
                assertTrue("the wake target must attach before sleep is allowed", wakeTap.arm {
                    wakeTap.disarm()
                    woken.countDown()
                })
                instrumentation.waitForIdleSync()

                val wakingDown = SystemClock.uptimeMillis()
                inject(MotionEvent.ACTION_DOWN, wakingDown, x, y)
                assertTrue("DOWN must actually trigger wake", woken.await(2, TimeUnit.SECONDS))
                // The disarm removal is posted to main. Flush it BEFORE the rest of this gesture.
                instrumentation.waitForIdleSync()
                inject(MotionEvent.ACTION_MOVE, wakingDown, x + 5, y + 5)
                inject(MotionEvent.ACTION_UP, wakingDown, x + 5, y + 5)
                instrumentation.waitForIdleSync()

                assertEquals("no part of the waking gesture may reach the renderer", 0, dashboardEvents.get())

                tap(x, y)
                instrumentation.waitForIdleSync()
                assertEquals("the following ordinary tap must reach the renderer as DOWN and UP", 2, dashboardEvents.get())
            }
        } finally {
            wakeTap.disarm()
            instrumentation.waitForIdleSync()
        }
    }

    @Test
    fun replacingAnArmedGenerationLeavesOnlyTheReplacementAbleToWake() {
        val wakeTap = OverlayWakeTap(context)
        try {
            withDashboard { dashboardEvents, x, y ->
                val obsoleteWakes = AtomicInteger()
                val replacementWoken = CountDownLatch(1)
                assertTrue(wakeTap.arm { obsoleteWakes.incrementAndGet() })
                assertTrue(wakeTap.arm {
                    wakeTap.disarm()
                    replacementWoken.countDown()
                })
                instrumentation.waitForIdleSync()

                tap(x, y)
                assertTrue("replacement must retain a usable wake target", replacementWoken.await(2, TimeUnit.SECONDS))
                instrumentation.waitForIdleSync()

                assertEquals("the replaced generation must be disposed", 0, obsoleteWakes.get())
                assertEquals("the replacement still consumes the waking gesture", 0, dashboardEvents.get())
                tap(x, y)
                instrumentation.waitForIdleSync()
                assertEquals("generation disposal must leave ordinary input usable", 2, dashboardEvents.get())
            }
        } finally {
            wakeTap.disarm()
            instrumentation.waitForIdleSync()
        }
    }

    @Test
    fun aFailedWakeLeavesTheNextTouchAbleToRetryWithoutReachingTheDashboard() {
        val wakeTap = OverlayWakeTap(context)
        try {
            withDashboard { dashboardEvents, x, y ->
                val attempts = AtomicInteger()
                val failedAttempt = CountDownLatch(1)
                val retried = CountDownLatch(1)
                assertTrue(wakeTap.arm {
                    if (attempts.incrementAndGet() == 1) {
                        failedAttempt.countDown()
                        error("wake actuator failed")
                    }
                    wakeTap.disarm()
                    retried.countDown()
                })
                instrumentation.waitForIdleSync()

                tap(x, y)
                assertTrue("the initial wake path must be exercised", failedAttempt.await(2, TimeUnit.SECONDS))
                // Failure is handled on the wake worker; allow later physical taps until it reopens.
                val retryDeadline = SystemClock.uptimeMillis() + 2_000
                while (retried.count != 0L && SystemClock.uptimeMillis() < retryDeadline) {
                    tap(x, y)
                    retried.await(20, TimeUnit.MILLISECONDS)
                }
                assertTrue("an exceptional wake must permit a later touch to retry", retried.await(0, TimeUnit.MILLISECONDS))
                instrumentation.waitForIdleSync()

                assertEquals("only the failed wake and successful retry may run", 2, attempts.get())
                assertEquals("both wake gestures must stay out of the dashboard", 0, dashboardEvents.get())
                tap(x, y)
                instrumentation.waitForIdleSync()
                assertEquals("successful retry releases capture for ordinary input", 2, dashboardEvents.get())
            }
        } finally {
            wakeTap.disarm()
            instrumentation.waitForIdleSync()
        }
    }

    private fun withDashboard(block: (AtomicInteger, Float, Float) -> Unit) {
        assertTrue("grant SYSTEM_ALERT_WINDOW to the target app before running this suite", PanelTouchObserver.shared(context).canObserve())
        // A separate real window provides the renderer seam without launching any app Activity.
        // This permits the same test APK to qualify a sealed release candidate on panel hardware.
        val wm = context.getSystemService(WindowManager::class.java)
        val events = AtomicInteger()
        val target = View(context)
        try {
            instrumentation.runOnMainSync {
                target.setOnTouchListener { _, _ -> events.incrementAndGet(); true }
                val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                } else {
                    @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
                }
                wm.addView(target, WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    type,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT,
                ))
            }
            var x = 0f
            var y = 0f
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync {
                val position = IntArray(2)
                target.getLocationOnScreen(position)
                assertTrue("the renderer fixture must have a touchable area", target.width > 20 && target.height > 20)
                x = position[0] + target.width / 2f
                y = position[1] + target.height / 2f
            }
            block(events, x, y)
        } finally {
            instrumentation.runOnMainSync { if (target.isAttachedToWindow) wm.removeViewImmediate(target) }
            instrumentation.waitForIdleSync()
        }
    }

    private fun tap(x: Float, y: Float) {
        val down = SystemClock.uptimeMillis()
        inject(MotionEvent.ACTION_DOWN, down, x, y)
        inject(MotionEvent.ACTION_UP, down, x, y)
    }

    private fun inject(action: Int, down: Long, x: Float, y: Float) {
        val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0)
        event.source = InputDevice.SOURCE_TOUCHSCREEN
        try {
            // InputDispatcher may discard MOVE/UP after removal: the dashboard must still see zero.
            val accepted = instrumentation.uiAutomation.injectInputEvent(event, true)
            if (action == MotionEvent.ACTION_DOWN) assertTrue("Android must accept the injected DOWN", accepted)
        } finally {
            event.recycle()
        }
    }
}
