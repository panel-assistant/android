package io.panelassistant.android.control

import io.panelassistant.android.device.ScreenOff
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenControllerProximityHoldTest {
    private val backlight = FakeBacklight()
    private val power = FakeScreenPower()
    private val tap = FakeWakeTap()

    private fun controller(route: ScreenOff = ScreenOff.BRIGHTNESS_ZERO) = ScreenController(
        backlight, power, FakeRootShell(), FakeDaemon(mapOf("BLPOWER" to "0")), tap, route,
        nap = {},
    )

    @Test
    fun holdRefusesManualAndAutomaticOffForEveryRoute() {
        for (route in ScreenOff.entries) {
            val screen = controller(route)
            val owner = Any()
            assertTrue(screen.acquireVisibleHold(owner))
            screen.sleep()
            assertNull(screen.sleepAutomatically())
            assertFalse(screen.isIntendedOff())
            assertEquals(160, backlight.level)
            assertFalse(tap.armed)
            screen.releaseVisibleHold(owner)
        }
    }

    @Test
    fun acquiringHoldRelightsPreviousOffAndReleaseLeavesItLit() {
        val screen = controller()
        screen.sleep()
        assertTrue(screen.isIntendedOff())
        assertEquals(0, backlight.level)
        assertTrue(tap.armed)
        val owner = Any()
        assertTrue(screen.acquireVisibleHold(owner))
        assertFalse(screen.isIntendedOff())
        assertTrue(backlight.level > 0)
        assertFalse(tap.armed)
        screen.releaseVisibleHold(owner)
        assertTrue(backlight.level > 0)
        assertFalse(screen.isIntendedOff())
        // Once released, the ordinary touch-safe route still works in both directions.
        screen.sleep()
        assertEquals(0, backlight.level)
        assertTrue(tap.armed)
        tap.fireTap()
        assertTrue(backlight.level > 0)
        assertFalse(screen.isIntendedOff())
    }

    @Test
    fun ownerUsesIdentityAndRepeatedAcquireNeedsOnlyOneRelease() {
        data class Owner(val label: String)
        val owner = Owner("same")
        val equalButDifferentOwner = Owner("same")
        val screen = controller()
        assertTrue(screen.acquireVisibleHold(owner))
        assertTrue(screen.acquireVisibleHold(owner))
        screen.releaseVisibleHold(equalButDifferentOwner)
        screen.sleep()
        assertFalse(screen.isIntendedOff())
        screen.releaseVisibleHold(owner)
        assertTrue(screen.sleepAutomatically() != null)
        assertTrue(screen.isIntendedOff())
    }

    @Test
    fun lastOwnerMustReleaseBeforeAnyOffIsAllowed() {
        val screen = controller()
        val first = Any()
        val second = Any()
        assertTrue(screen.acquireVisibleHold(first))
        assertTrue(screen.acquireVisibleHold(second))
        screen.releaseVisibleHold(first)
        assertNull(screen.sleepAutomatically())
        screen.releaseVisibleHold(second)
        assertTrue(screen.sleepAutomatically() != null)
    }

    @Test
    fun closedAdmissionRefusesHoldAndLaterScreenOff() {
        val screen = controller()
        val owner = Any()
        assertTrue(screen.acquireVisibleHold(owner))
        screen.closeAdmission()
        screen.releaseVisibleHold(owner)
        val writes = backlight.calls.toList()
        assertFalse(screen.acquireVisibleHold(owner))
        assertFalse(screen.acquireVisibleHold(Any()))
        assertEquals(writes, backlight.calls)
        screen.sleep()
        assertNull(screen.sleepAutomatically())
        assertFalse(screen.isIntendedOff())
        assertTrue(backlight.level > 0)
    }

    @Test
    fun livePresenceBrightensEveryLitRouteWithoutWakingOrChangingBrightness() {
        for (route in ScreenOff.entries) {
            val screen = controller(route)
            val previousPulses = power.brightnessPulses
            assertTrue(screen.brightenForPresence { true })
            assertEquals(previousPulses + 1, power.brightnessPulses)
            assertEquals(0, power.pulses)
            assertTrue(backlight.calls.isEmpty())
            assertEquals(160, backlight.level)
            assertFalse(screen.isIntendedOff())
        }
    }

    @Test
    fun rejectedOrStalePresenceCannotBrighten() {
        val screen = controller()
        assertFalse(screen.brightenForPresence { false })
        screen.closeAdmission()
        assertFalse(screen.brightenForPresence { true })
        assertEquals(0, power.brightnessPulses)
        assertEquals(0, power.pulses)
        assertTrue(backlight.calls.isEmpty())
    }

    @Test
    fun manualAndAutomaticOffRejectQueuedApproachAndPreserveTouchRecovery() {
        for (automatic in listOf(false, true)) {
            val screen = controller()
            if (automatic) screen.sleepAutomatically() else screen.sleep()
            val generation = screen.currentOffGeneration()
            assertTrue(generation != null)
            assertFalse(screen.brightenForPresence { true })
            assertEquals(generation, screen.currentOffGeneration())
            assertEquals(0, power.brightnessPulses)
            assertEquals(0, backlight.level)
            tap.fireTap()
            assertFalse(screen.isIntendedOff())
            assertTrue(backlight.level > 0)
        }
    }

    @Test
    fun physicallyDarkNoninteractiveAndUnknownScreensRejectApproach() {
        val screen = controller()
        backlight.level = 0
        assertFalse(screen.brightenForPresence { true })
        backlight.level = 160
        power.interactive = false
        assertFalse(screen.brightenForPresence { true })
        power.interactive = true
        val unknown = ScreenController(backlight, power, FakeRootShell(), FakeDaemon(), tap, ScreenOff.SU_BLPOWER)
        assertFalse(unknown.brightenForPresence { true })
        assertEquals(0, power.brightnessPulses)
        assertEquals(0, power.pulses)
        assertTrue(backlight.calls.isEmpty())
    }
}
