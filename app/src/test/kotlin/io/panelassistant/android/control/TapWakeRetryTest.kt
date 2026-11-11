package io.panelassistant.android.control

import io.panelassistant.android.device.ScreenOff
import io.panelassistant.android.platform.VendorScreen
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A tap on a dark panel whose wake fails must leave the next tap able to wake it. Driven through the real
 * [ScreenController] and the real [OverlayWakeTap]; only the wake window Android would attach is replaced,
 * by one that delivers a touch to the listener of every window still attached. A wake tap fake that calls
 * back on every tap whatever the production gate does cannot see this.
 */
class TapWakeRetryTest {
    private val windows = WakeWindows()
    private val power = FakeScreenPower()
    private val wokenByTap = mutableListOf<AutomaticOffEpoch?>()
    private var wakesCompleted = 0

    @After fun restoreRenderer() { BuiltinDashboard.onScreenAwake(true) }

    /** A manual OFF on a vendor screen; the vendor goes on enforcing it through the first tap's wake. */
    @Test fun aManualVendorOffWhoseFirstTapStaysDarkIsWokenByTheNextTap() {
        for (answersWhileEnforcing in listOf(false, true)) {
            val vendor = EnforcingVendor(answersWhileEnforcing)
            val sc = vendorController(vendor, tapWake())
            assertEquals(SleepOutcome.APPLIED, sc.sleep())
            assertEquals(0, vendor.node)
            assertTrue(windows.attached())

            vendor.enforcesOff = true
            windows.tap()
            assertEquals("the first tap asked the vendor to relight", 1, vendor.onRequests)
            assertEquals("it stayed dark through the brightness restore", 0, vendor.node)
            assertTrue("a failed wake keeps the manual OFF", sc.isIntendedOff())
            assertEquals("and is not reported as a wake", 0, wokenByTap.size + wakesCompleted)
            assertFalse("the dashboard stays paused behind the dark screen", BuiltinDashboard.screenAwakeNow)

            vendor.enforcesOff = false
            assertEquals("the vendor answers again, and nothing lit the screen meanwhile", 0, vendor.node)
            assertTrue("the next tap is still consumed by the wake window", windows.attached())
            windows.tap()
            assertEquals("the next tap retried the vendor", 2, vendor.onRequests)
            assertTrue(vendor.node > 0)
            assertFalse(sc.isIntendedOff())
            assertEquals("woken by a tap on a manual OFF, which never trains auto-sleep", listOf(null), wokenByTap)
            assertEquals(1, wakesCompleted)
            assertTrue(BuiltinDashboard.screenAwakeNow)
            assertFalse("later touches go to the dashboard", windows.attached())

            wokenByTap.clear()
            wakesCompleted = 0
        }
    }

    /** The same on a bl_power panel whose helper refuses the first wake: the fix is not vendor-specific. */
    @Test fun aManualBlPowerOffWhoseFirstTapStaysDarkIsWokenByTheNextTap() {
        var blPower = 0
        var helperLights = true
        val daemon = object : io.panelassistant.android.platform.Daemon by FakeDaemon() {
            override fun send(cmd: String): String? = when (cmd) {
                "SCREEN OFF" -> { blPower = 4; "OK" }
                "SCREEN ON" -> if (helperLights) { blPower = 0; "OK" } else "ERR"
                "BLPOWER" -> blPower.toString()
                else -> null
            }
        }
        val sc = ScreenController(
            FakeBacklight(), power, FakeRootShell(runResult = false), daemon, tapWake(), ScreenOff.DAEMON_BLPOWER,
            nap = {},
        ).observed()
        assertEquals(SleepOutcome.APPLIED, sc.sleep())

        helperLights = false
        windows.tap()
        assertEquals("the first tap left the backlight off", 4, blPower)
        assertTrue("a failed wake keeps the manual OFF", sc.isIntendedOff())

        helperLights = true
        assertEquals("nothing lit the screen meanwhile", 4, blPower)
        windows.tap()
        assertEquals("the next tap powered the backlight", 0, blPower)
        assertFalse(sc.isIntendedOff())
        assertEquals(listOf(null), wokenByTap)
        assertFalse(windows.attached())
    }

    /** The retry of an automatic OFF still proves its own epoch; see also the raw-touch control in AutoSleepControllerTest. */
    @Test fun theRetryTapOfAnAutomaticOffReportsItsAutomaticEpoch() {
        val vendor = EnforcingVendor()
        val sc = vendorController(vendor, tapWake())
        val epoch = requireNotNull(sc.sleepAutomatically())

        vendor.enforcesOff = true
        windows.tap()
        assertTrue(sc.isIntendedOff())
        assertEquals("the failed wake keeps the automatic owner", epoch, sc.automaticRecoveryEpoch())

        vendor.enforcesOff = false
        windows.tap()
        assertFalse("the next tap woke the automatic OFF", sc.isIntendedOff())
        assertEquals(listOf<AutomaticOffEpoch?>(epoch), wokenByTap)
    }

    /** While one wake runs, further taps start no second one; each tap after it returned starts exactly one. */
    @Test fun oneWakeRunsAtATimeAndEachLaterTapRetriesOnce() {
        val workers = ArrayDeque<() -> Unit>()
        val vendor = EnforcingVendor()
        val sc = vendorController(vendor, tapWake { workers += it })
        sc.sleep()
        vendor.enforcesOff = true

        windows.tap()
        windows.tap()
        assertEquals("a tap during a running wake is dropped", 1, workers.size)
        workers.removeFirst()()
        assertEquals(1, vendor.onRequests)

        windows.tap()
        assertEquals("a tap after the failed wake returned starts one retry", 1, workers.size)
        workers.removeFirst()()
        assertEquals("still enforced: retried once, still an owned OFF", 2, vendor.onRequests)
        assertTrue(sc.isIntendedOff())
    }

    /** A retry tap whose worker runs after a newer OFF took over must not wake that newer OFF. */
    @Test fun aRetryQueuedBeforeANewerOffDoesNotWakeIt() {
        val workers = ArrayDeque<() -> Unit>()
        val vendor = EnforcingVendor()
        val sc = vendorController(vendor, tapWake { workers += it })
        sc.sleep()
        val firstOffWindow = windows.listeners().single()
        vendor.enforcesOff = true
        windows.tap()
        workers.removeFirst()()
        assertTrue("the first tap failed", sc.isIntendedOff())

        vendor.enforcesOff = false
        windows.tap()
        assertEquals("the retry is queued for the first OFF", 1, workers.size)
        assertTrue("Home Assistant turns the screen on", sc.wake())
        assertEquals("and off again", SleepOutcome.APPLIED, sc.sleep())
        val requestsBefore = vendor.onRequests

        workers.removeFirst()()
        firstOffWindow()
        assertTrue("the newer OFF is not woken by the older tap", sc.isIntendedOff())
        assertEquals("no worker starts for a replaced wake window", 0, workers.size)
        assertEquals(requestsBefore, vendor.onRequests)
        assertEquals(0, vendor.node)
        assertTrue(wokenByTap.isEmpty())

        windows.tap()
        workers.removeFirst()()
        assertFalse("its own tap wakes it", sc.isIntendedOff())
        assertEquals(listOf(null), wokenByTap)
    }

    /** Failed outcomes stay truthful, and a wake that succeeds another way releases the touch. */
    @Test fun afterAFailedTapAnOnCommandReportsItsOwnOutcomeAndReleasesTheTouch() {
        val vendor = EnforcingVendor()
        val sc = vendorController(vendor, tapWake())
        sc.sleep()
        vendor.enforcesOff = true
        windows.tap()
        assertEquals("still enforced: an ON command is told it failed", WakeOutcome.ACTUATION_FAILED, sc.ensureOn())
        assertTrue("and the touch wake stays for a person", windows.attached())

        vendor.enforcesOff = false
        assertEquals(WakeOutcome.WOKEN, sc.ensureOn())
        assertFalse("later touches go to the dashboard", windows.attached())
        assertTrue("the ON command was not a tap", wokenByTap.isEmpty())
    }

    /** A wake that throws is the same to a person as one that returned with the screen dark. */
    @Test fun aWakeThatThrowsLeavesTheNextTapAbleToRetry() {
        val wakeTap = tapWake()
        var attempts = 0
        assertTrue(wakeTap.arm {
            if (++attempts == 1) error("wake actuator failed")
            wakeTap.disarm()
        })
        windows.tap()
        assertTrue("the failed wake keeps the touch", windows.attached())
        windows.tap()
        assertEquals(2, attempts)
        assertFalse(windows.attached())
    }

    private fun tapWake(runWake: ((() -> Unit) -> Unit)? = null): OverlayWakeTap =
        if (runWake == null) OverlayWakeTap({ true }, windows::attach) { it() }
        else OverlayWakeTap({ true }, windows::attach, runWake)

    private fun vendorController(vendor: EnforcingVendor, wakeTap: OverlayWakeTap) = ScreenController(
        vendor, power, FakeRootShell(runResult = false), FakeDaemon(), wakeTap, ScreenOff.VENDOR_RPC,
        nap = {}, vendor = vendor,
    ).observed()

    private fun ScreenController.observed() = apply {
        onWakeByTap = { wokenByTap += it }
        onWakeCompleted = { wakesCompleted++ }
    }

    /** The wake windows currently attached; Android delivers a DOWN to the one on top. */
    private class WakeWindows {
        private val attached = mutableListOf<() -> Unit>()
        fun attach(onTouch: () -> Unit): AutoCloseable {
            attached += onTouch
            return AutoCloseable { attached.remove(onTouch) }
        }
        fun attached() = attached.isNotEmpty()
        fun listeners() = attached.toList()
        fun tap() { attached.lastOrNull()?.invoke() }
    }

    /**
     * A vendor screen and the backlight node it owns. While [enforcesOff], the vendor holds the node dark:
     * its ON request does not relight it (and is refused unless [answersWhileEnforcing]), and a brightness
     * setting write does not reach the node either.
     */
    private class EnforcingVendor(private val answersWhileEnforcing: Boolean = false) : VendorScreen, Backlight {
        var node = 128
        var enforcesOff = false
        var onRequests = 0
        private var setting = 128
        override fun set(on: Boolean): Boolean {
            if (!on) { node = 0; return true }
            onRequests++
            if (enforcesOff) return answersWhileEnforcing
            node = setting
            return true
        }
        override fun level(): Int = node
        override fun holdBrightness() = true
        override fun getBrightness() = node
        override fun setBrightness(level: Int) {
            if (level != setting && !enforcesOff) node = level
            setting = level
        }
        override fun setBrightnessRaw(level: Int) = setBrightness(level)
        override fun getCommanded() = setting
    }
}
