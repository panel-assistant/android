package io.panelassistant.android.control

import android.accessibilityservice.AccessibilityServiceInfo
import android.graphics.PixelFormat
import android.graphics.Rect
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
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

/** Real Android input dispatch: removing the wake window must never retarget its gesture. */
@RunWith(AndroidJUnit4::class)
class OverlayWakeTapInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    // Set by withDashboard: the point every test taps, the renderer fixture's window there, and every window
    // listed once it was, so a wake window is one listed later.
    private var touchX = 0f
    private var touchY = 0f
    private var dashboardWindow = -1
    private var listedBeforeWake = emptySet<Int>()

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
                awaitWakeOverlay()

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
                // Replace a generation whose window is up, as a screen that has been off is re-armed.
                val obsoleteWindow = awaitWakeOverlay()
                assertTrue(wakeTap.arm {
                    wakeTap.disarm()
                    replacementWoken.countDown()
                })
                awaitWakeOverlay(replacing = obsoleteWindow)

                tap(x, y)
                assertTrue("replacement must retain a usable wake target", replacementWoken.await(2, TimeUnit.SECONDS))
                instrumentation.waitForIdleSync()

                assertEquals("the replaced generation must be disposed", 0, obsoleteWakes.get())
                assertEquals("the replacement still consumes the waking gesture", 0, dashboardEvents.get())
                awaitTouchTarget("the renderer fixture to take touches again") { it == dashboardWindow }
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
                awaitWakeOverlay()

                tap(x, y)
                assertTrue("the initial wake path must be exercised", failedAttempt.await(2, TimeUnit.SECONDS))
                // Failure is handled on the wake worker; allow later physical taps until it reopens.
                val retryDeadline = SystemClock.uptimeMillis() + 2_000
                // Stop once the retry has started: its disarm removes the wake window, and a tap racing that
                // removal is dropped by Android (API 35 under load, 2026-11-08), so those taps need not be accepted.
                while (attempts.get() < 2 && SystemClock.uptimeMillis() < retryDeadline) {
                    tap(x, y, requireAccepted = false)
                    retried.await(20, TimeUnit.MILLISECONDS)
                }
                assertTrue("an exceptional wake must permit a later touch to retry", retried.await(2, TimeUnit.SECONDS))
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
        enableWindowList()
        // The previous test's windows can still be listed while Android removes them.
        val listedBefore = windowIds()
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
            touchX = x
            touchY = y
            dashboardWindow = awaitTouchTarget("the renderer fixture to take touches") { it !in listedBefore }
            listedBeforeWake = windowIds()
            block(events, x, y)
        } finally {
            instrumentation.runOnMainSync { if (target.isAttachedToWindow) wm.removeViewImmediate(target) }
            instrumentation.waitForIdleSync()
        }
    }

    private fun tap(x: Float, y: Float, requireAccepted: Boolean = true) {
        val down = SystemClock.uptimeMillis()
        inject(MotionEvent.ACTION_DOWN, down, x, y, requireAccepted)
        inject(MotionEvent.ACTION_UP, down, x, y)
    }

    /** arm() returns once the overlay is added, but Android routes touches to a window only after its first
     *  frame is composed; a tap sent sooner fell through to the window below on API 35 (2026-11-06/07). A wait
     *  for accessibility events to go quiet for 500 ms alone still ended before the window showed on a loaded
     *  emulator, whose first frames took over a second (2026-11-08). So first wait until accessibility lists the
     *  wake window as the one at the tap point, above the renderer fixture and the generation it replaces, then
     *  for the events to go quiet: on a one-CPU emulator input dispatch once lagged that listing (2026-11-09).
     *  A panel's sleeping screen is tapped seconds after arming, so this is fixture timing, not a product race. */
    private fun awaitWakeOverlay(replacing: Int? = null): Int {
        instrumentation.waitForIdleSync()
        val window = awaitTouchTarget("the wake overlay to take touches") { it !in listedBeforeWake && it != replacing }
        try {
            instrumentation.uiAutomation.waitForIdle(500, 5_000)
        } catch (_: TimeoutException) {
            // Unrelated system events never went quiet; the wake latch still decides the test.
        }
        return window
    }

    private fun enableWindowList() {
        val automation = instrumentation.uiAutomation
        val info = automation.serviceInfo
        if (info.flags and AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS != 0) return
        info.flags = info.flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        automation.serviceInfo = info
    }

    private fun windowIds(): Set<Int> = instrumentation.uiAutomation.windows.map { it.id }.toSet()

    /** The window a touch at (x, y) reaches: the highest one accessibility reports there. Accessibility lists a
     *  window once it shows and is touchable (on API 34+ from the same window list input dispatch uses), and
     *  leaves out what a full-screen window above hides. */
    private fun touchTargetAt(x: Float, y: Float): Int? {
        val bounds = Rect()
        return instrumentation.uiAutomation.windows
            .filter { it.getBoundsInScreen(bounds); bounds.contains(x.toInt(), y.toInt()) }
            .maxByOrNull { it.layer }?.id
    }

    private fun describeWindows(): String {
        val bounds = Rect()
        return instrumentation.uiAutomation.windows.joinToString { w ->
            w.getBoundsInScreen(bounds)
            "${w.id}/layer ${w.layer}/type ${w.type}/${bounds.toShortString()}/${w.title}"
        } + " (fixture $dashboardWindow)"
    }

    /** Waits for the touch target at the tap point to satisfy [accepted]; the deadline only bounds a hang. */
    private fun awaitTouchTarget(what: String, accepted: (Int) -> Boolean): Int {
        val deadline = SystemClock.uptimeMillis() + 15_000
        while (true) {
            touchTargetAt(touchX, touchY)?.let { if (accepted(it)) return it }
            if (SystemClock.uptimeMillis() > deadline) fail("$what within 15 s; windows ${describeWindows()}")
            SystemClock.sleep(20)
        }
    }

    private fun inject(action: Int, down: Long, x: Float, y: Float, requireAccepted: Boolean = true) {
        val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0)
        event.source = InputDevice.SOURCE_TOUCHSCREEN
        try {
            // InputDispatcher may discard MOVE/UP after removal: the dashboard must still see zero.
            val accepted = instrumentation.uiAutomation.injectInputEvent(event, true)
            if (action == MotionEvent.ACTION_DOWN && requireAccepted) assertTrue("Android must accept the injected DOWN", accepted)
        } finally {
            event.recycle()
        }
    }
}
