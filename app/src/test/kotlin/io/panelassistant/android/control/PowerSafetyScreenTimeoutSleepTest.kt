package io.panelassistant.android.control

import io.panelassistant.android.device.ScreenOff
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A screen-timeout sleep writes a temporary short SCREEN_OFF_TIMEOUT and clears stay-on for the whole
 * sleep. Power safety must judge the panel's configured values (the displaced record), not those
 * temporary ones, so a guarded panel stays SAFE while asleep and after wake.
 */
class PowerSafetyScreenTimeoutSleepTest {
    private val power = FakeScreenPower().apply {
        canWrite = true
        canWriteSecure = true
        stayOnMask = 7
        timeoutMs = Int.MAX_VALUE
    }
    private val displaced = InMemoryDisplacedScreenTimeout()
    private val screen = ScreenController(
        FakeBacklight(200), power, FakeRootShell(runResult = false), FakeDaemon(), FakeWakeTap(),
        ScreenOff.SCREEN_TIMEOUT, nap = {}, displacedTimeout = displaced, touchCount = { 0L },
    )

    /** PowerSafetyController.observe needs a real Context; its inputs and substitution rule are shared here. */
    private fun observed() = PowerSafetyObservation(
        keepAwakeConfigured = true, wakeLockHeld = true, wifiLockRequired = false, wifiLockHeld = true,
        preventIdleDimConfigured = true,
        screenOffTimeoutMs = configuredDuringSleep(screen.heldSleepRecord()?.displacedMs ?: -1, power.screenOffTimeoutMs()),
        interactive = power.isInteractive(), pluggedMask = 1,
        stayOnWhilePluggedIn = configuredDuringSleep(screen.heldSleepRecord()?.displacedStayOnMask ?: -1, power.stayOnWhilePluggedInMask()),
        deviceIdleMode = false, ignoringBatteryOptimizations = false,
        screenOffMechanism = "screen_timeout",
        screenOffSelected = screen.routeSelection().selected?.name?.lowercase(),
        screenOffReason = screen.routeSelection().reason,
    )

    private fun assertSafe(phase: String) {
        val assessment = PowerSafetyPolicy.assess(observed())
        val advisory = PowerSafetyAdvisoryPolicy.evaluate(assessment, PowerRepairCapability.APP_ONLY, null)
        assertEquals("$phase: level", PowerRiskLevel.SAFE, assessment.level)
        assertEquals("$phase: action", PowerSafetyAdvisoryAction.NONE, advisory.action)
        assertFalse("$phase: reasons ${assessment.reasonCodes}", "native_screen_timeout_finite" in assessment.reasonCodes)
    }

    @Test fun aGuardedPanelStaysSafeWhileAScreenTimeoutSleepHoldsTheShortTimeoutAndAfterWake() {
        screen.sleep()
        assertEquals("precondition: the SCREEN_TIMEOUT route displaced the timeout", Int.MAX_VALUE, displaced.displacedMs)
        assertFalse("precondition: Android is asleep", power.isInteractive())
        assertSafe("asleep")

        screen.wake()
        power.interactive = true
        assertSafe("awake")
    }

    @Test fun aTimeoutChangeRacingWakeIsTheTimeoutAndroidRestores() {
        screen.sleep()
        val selected = java.util.concurrent.CountDownLatch(1)
        val finishUpdate = java.util.concurrent.CountDownLatch(1)
        val wakeStarted = java.util.concurrent.CountDownLatch(1)
        val failure = java.util.concurrent.atomic.AtomicReference<Throwable?>()
        val timeout = ScreenTimeoutController(
            read = power::screenOffTimeoutMs,
            write = power::setScreenOffTimeoutMs,
        )
        val update = Thread {
            try {
                assertTrue(screen.updateScreenOffTimeout { held ->
                    selected.countDown()
                    check(finishUpdate.await(5, java.util.concurrent.TimeUnit.SECONDS))
                    timeout.set(300_000, held)
                })
            } catch (t: Throwable) { failure.set(t) }
        }
        val wake = Thread {
            wakeStarted.countDown()
            screen.wake()
        }
        try {
            update.start()
            assertTrue(selected.await(5, java.util.concurrent.TimeUnit.SECONDS))
            wake.start()
            assertTrue(wakeStarted.await(5, java.util.concurrent.TimeUnit.SECONDS))
            // Wait until wake either contends with the update or completes it. Without serialization
            // it restores the old value here, leaving the later choice stranded in the saved record.
            val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5)
            while (wake.isAlive && wake.state != Thread.State.BLOCKED && System.nanoTime() < deadline) Thread.yield()
        } finally {
            finishUpdate.countDown()
            update.join(5_000)
            wake.join(5_000)
        }
        failure.get()?.let { throw it }
        assertFalse("update completed", update.isAlive)
        assertFalse("wake completed", wake.isAlive)
        assertEquals(300_000, power.screenOffTimeoutMs())
        assertEquals(-1, displaced.displacedMs)
    }

    @Test fun aWakeWhoseRestoreFailedReportsTheShortTimeoutItLeftBehind() {
        screen.sleep()
        assertEquals("precondition: the SCREEN_TIMEOUT route displaced the timeout", Int.MAX_VALUE, displaced.displacedMs)
        power.canWrite = false
        power.canWriteSecure = false

        // Model Android responding to the wake pulse before ScreenController confirms it.
        power.interactive = true
        screen.wake()

        assertEquals("precondition: the failed restore kept its record", Int.MAX_VALUE, displaced.displacedMs)
        assertReportsTheShortTimeout("awake")

        // A retried off is refused by the same missing grants and falls back to a dim: the panel still
        // runs the short timeout, and the kept record is still not its configured state.
        screen.sleep()
        assertTrue("precondition: the retried off is held", screen.isIntendedOff())
        assertEquals("precondition: the record is still kept", Int.MAX_VALUE, displaced.displacedMs)
        assertReportsTheShortTimeout("refused retry")
    }

    @Test fun aDeviceAdminFallbackAfterAFailedRestoreReportsTheShortTimeout() {
        screen.sleep()
        power.canWrite = false
        power.canWriteSecure = false
        screen.wake()
        power.interactive = true
        power.adminActive = true

        screen.sleep()

        assertFalse("precondition: the device-admin lock slept Android", power.isInteractive())
        assertEquals("precondition: the record is still kept", Int.MAX_VALUE, displaced.displacedMs)
        assertReportsTheShortTimeout("device-admin fallback")
    }

    private fun assertReportsTheShortTimeout(phase: String) {
        val assessment = PowerSafetyPolicy.assess(observed())
        assertNotEquals("$phase: level", PowerRiskLevel.SAFE, assessment.level)
        assertTrue("$phase: reasons ${assessment.reasonCodes}", "native_screen_timeout_finite" in assessment.reasonCodes)
    }
}
