package io.panelassistant.android.control

import io.panelassistant.android.device.ScreenOff
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The vendor-rpc screen-off route through [ScreenController]'s public transitions, over a fake Stargate
 * whose backlight node behaves as measured on the bench X2i: the RPC writes the node behind Android's
 * back, and Android re-drives the node only when `SCREEN_BRIGHTNESS` changes.
 */
class ScreenControllerVendorRpcTest {
    private val vendor = FakeVendorScreen()
    private val backlight = SettingBacklight(vendor, setting = 128)
    private val power = FakeScreenPower()
    private val wakeTap = FakeWakeTap()

    private fun controller() = ScreenController(
        backlight, power, FakeRootShell(runResult = false), FakeDaemon(), wakeTap, ScreenOff.VENDOR_RPC,
        nap = {}, vendor = vendor,
    )

    @Test fun anOffBlanksTheBacklightWhileAndroidStaysAwakeAndATapWakesItWithoutReachingTheDashboard() {
        val sc = controller()

        sc.sleep()
        assertEquals(0, vendor.node)
        assertTrue("Android stays awake", power.interactive)
        assertTrue("the tap is captured by the overlay, not the dashboard", wakeTap.armed)
        assertTrue(sc.looksDark())
        assertEquals(RouteSelection(ScreenOff.VENDOR_RPC, ScreenOff.VENDOR_RPC, ScreenController.REASON_DECLARED), sc.routeSelection())
        assertEquals("no Android sleep is attempted", 0, power.timeoutWrites)

        wakeTap.fireTap()
        assertTrue(vendor.node > 0)
        assertFalse(sc.isIntendedOff())
        assertFalse(wakeTap.armed)
        assertEquals(ScreenController.REASON_DECLARED, sc.routeSelection().reason)
    }

    @Test fun repeatedOffsAndOnsConverge() {
        val sc = controller()
        sc.sleep()
        sc.sleep()
        assertEquals(0, vendor.node)
        assertTrue(sc.isIntendedOff())
        sc.wake()
        sc.wake()
        assertTrue(vendor.node > 0)
        assertFalse(sc.isIntendedOff())
        assertEquals("a lit panel is not woken again", WakeOutcome.ALREADY_ON, sc.ensureOn())
    }

    @Test fun aVendorThatDoesNotAnswerFallsBackToAndroidsScreenTimeout() {
        vendor.answers = false
        power.canWrite = true
        val sc = controller()

        sc.sleep()
        assertFalse("Android sleeps instead", power.interactive)
        assertFalse("a sleeping Android cannot deliver a tap to the overlay", wakeTap.armed)
        assertEquals(ScreenOff.SCREEN_TIMEOUT, sc.routeSelection().selected)
        assertTrue(sc.routeSelection().reason.contains("vendor screen RPC was refused"))
    }

    /** An answer the readable node does not confirm is undone, then nothing else can sleep: the dim. */
    @Test fun anAnswerTheNodeDoesNotConfirmIsUndoneAndDimsWhenNothingElseCanSleep() {
        val unconfirmed = FakeVendorScreen(blanks = false)
        val sc = ScreenController(
            SettingBacklight(unconfirmed), power, FakeRootShell(runResult = false), FakeDaemon(), wakeTap,
            ScreenOff.VENDOR_RPC, nap = {}, vendor = unconfirmed,
        )
        sc.sleep()
        assertEquals("asked to relight after the unconfirmed off", listOf(false, true), unconfirmed.calls)
        assertTrue("never left dark", unconfirmed.node > 0)
        assertFalse(wakeTap.armed)
        assertEquals(ScreenOff.BRIGHTNESS_ZERO, sc.routeSelection().selected)
        assertTrue(sc.routeSelection().reason.contains("never-blank floor"))
    }

    /** Where the node cannot be read (enforcing firmware), the vendor's answer is the off, with the touch wake. */
    @Test fun anUnreadableNodeTakesTheVendorsAnswerAndKeepsTheTouchWake() {
        vendor.readable = false
        val sc = controller()
        sc.sleep()
        assertEquals(0, vendor.node)
        assertTrue(wakeTap.armed)
        assertEquals(ScreenController.REASON_VENDOR_UNREAD, sc.routeSelection().reason)
        wakeTap.fireTap()
        assertTrue(vendor.node > 0)
        assertFalse(sc.isIntendedOff())
    }

    /** The off applied but its reply was lost, the undo failed too,
     *  and the node cannot be read. No other route may own the screen; the touch wake stays and relights. */
    @Test fun aLostOffReplyWithAFailedUndoKeepsATouchWakeThatRelights() {
        val node = FakeVendorScreen(readable = false)
        val lostReplies = object : io.panelassistant.android.platform.VendorScreen {
            override fun set(on: Boolean): Boolean { if (!on) node.node = 0; return false }
            override fun level(): Int? = node.level()
            override fun holdBrightness() = false
        }
        power.canWrite = true
        val setting = SettingBacklight(node, setting = 128)
        val sc = ScreenController(
            setting, power, FakeRootShell(runResult = false), FakeDaemon(), wakeTap, ScreenOff.VENDOR_RPC,
            nap = {}, vendor = lostReplies,
        )
        sc.sleep()
        assertEquals(ScreenOff.VENDOR_RPC, sc.routeSelection().selected)
        assertEquals(ScreenController.REASON_VENDOR_UNRESOLVED, sc.routeSelection().reason)
        assertTrue(wakeTap.armed)
        assertTrue("Android was not handed the screen", power.interactive)
        node.node = 0  // whatever the undo did, assume the vendor still holds it dark
        wakeTap.fireTap()
        assertFalse(sc.isIntendedOff())
        assertTrue("the wake restores visible brightness", node.node > 0)
    }

    /** A lost reply on a readable node that did go dark: the undo is proven lit before Android sleep owns it. */
    @Test fun aLostOffReplyIsUndoneAndProvenLitBeforeTheTimeoutTakesOver() {
        val node = FakeVendorScreen()
        val lostReplies = object : io.panelassistant.android.platform.VendorScreen {
            override fun set(on: Boolean): Boolean { if (!on) node.node = 0; return false }
            override fun level(): Int? = node.level()
            override fun holdBrightness() = false
        }
        power.canWrite = true
        val sc = ScreenController(
            SettingBacklight(node, setting = 128), power, FakeRootShell(runResult = false), FakeDaemon(), wakeTap,
            ScreenOff.VENDOR_RPC, nap = {}, vendor = lostReplies,
        )
        sc.sleep()
        assertTrue("relit by the setting change before the hand-over", node.node > 0)
        assertEquals(ScreenOff.SCREEN_TIMEOUT, sc.routeSelection().selected)
        assertFalse(wakeTap.armed)
    }

    @Test fun withoutTheOverlayTheVendorIsNeverAskedToGoDark() {
        wakeTap.canArm = false
        power.canWrite = true
        val sc = controller()

        sc.sleep()
        assertTrue(vendor.calls.isEmpty())
        assertEquals(128, vendor.node)
        assertEquals(ScreenOff.SCREEN_TIMEOUT, sc.routeSelection().selected)
    }

    /** Stargate died while the panel was dark: the app relights the node it cannot write by changing the setting. */
    @Test fun aWakeTheVendorDoesNotAnswerRelightsThroughTheSetting() {
        val sc = controller()
        sc.sleep()
        assertEquals(0, vendor.node)

        vendor.answers = false
        wakeTap.fireTap()
        assertTrue("lit although the setting already held the saved level", vendor.node > 0)
        assertEquals("the app's level is back", 128, backlight.setting)
        assertFalse(sc.isIntendedOff())
    }

    /** Stargate's `Ui.Screen.Set` relights to its own level (48 against the app's 120 on the X2i). */
    @Test fun aVendorWakeComesBackAtTheAppsLevelNotTheVendors() {
        val stargate = FakeVendorScreen(node = 120, onLevel = 48)
        val setting = SettingBacklight(stargate, setting = 120)
        val sc = ScreenController(
            setting, power, FakeRootShell(runResult = false), FakeDaemon(), wakeTap, ScreenOff.VENDOR_RPC,
            nap = {}, vendor = stargate,
        )
        sc.sleep()
        wakeTap.fireTap()
        assertEquals(120, stargate.node)
        assertEquals(120, setting.setting)
    }

    /** After a boot Stargate holds the node at its own saved level (50 against the app's 150 on the X2i). */
    @Test fun aVendorLevelOnALitPanelIsReplacedByTheAppsButNotWhileOff() {
        val sc = controller()
        vendor.node = 50
        sc.restoreLevelOverVendor()
        assertEquals(128, vendor.node)

        sc.sleep()
        sc.restoreLevelOverVendor()
        assertEquals("a deliberately dark panel stays dark", 0, vendor.node)
    }

    /** Shelly 2.8.2: the wake relit, the app's level went on, and Stargate's 25 replaced it within 2 s. */
    @Test fun aLateVendorWriteAfterTheWakeDoesNotKeepTheAppsLevelOff() {
        val stargate = FakeVendorScreen(node = 151, onLevel = 25).apply { lateOverwriteMs = 1_500 }
        val setting = SettingBacklight(stargate, setting = 151)
        val sc = ScreenController(
            setting, power, FakeRootShell(runResult = false), FakeDaemon(), wakeTap, ScreenOff.VENDOR_RPC,
            nap = stargate::advance, vendor = stargate,
        )
        sc.sleep()
        wakeTap.fireTap()
        stargate.advance(10_000)
        assertEquals(151, stargate.node)
        assertEquals(151, setting.setting)
    }

    /** A slider moved during the post-wake hold is followed; it is never put back to the wake's level. */
    @Test fun aBrightnessChangeDuringTheHoldIsFollowedNotOverwritten() {
        val stargate = FakeVendorScreen(node = 151, onLevel = 25).apply { lateOverwriteMs = 1_500 }
        val setting = SettingBacklight(stargate, setting = 151)
        var naps = 0
        val sc = ScreenController(
            setting, power, FakeRootShell(runResult = false), FakeDaemon(), wakeTap, ScreenOff.VENDOR_RPC,
            nap = { ms -> stargate.advance(ms); if (++naps == 2) setting.setBrightness(200) }, vendor = stargate,
        )
        sc.sleep()
        naps = 0
        wakeTap.fireTap()
        stargate.advance(10_000)
        assertEquals(200, setting.setting)
        assertEquals(200, stargate.node)
    }

    /** Shutdown during the hold stops further corrections; the first relight has already happened. */
    @Test fun closingDuringTheHoldStopsCorrections() {
        val stargate = FakeVendorScreen(node = 151, onLevel = 25).apply { lateOverwriteMs = 1_500 }
        val setting = SettingBacklight(stargate, setting = 151)
        lateinit var sc: ScreenController
        sc = ScreenController(
            setting, power, FakeRootShell(runResult = false), FakeDaemon(), wakeTap, ScreenOff.VENDOR_RPC,
            // Close inside the nap in which Stargate's late write lands, so the node mismatches after it.
            nap = { ms -> stargate.advance(ms); if (stargate.node == 25) sc.closeAdmission() }, vendor = stargate,
        )
        sc.sleep()
        wakeTap.fireTap()
        stargate.advance(10_000)
        assertEquals("no correction after closure", 25, stargate.node)
    }

    /** A wake the vendor refuses on an unreadable node may leave it held dark: one consuming touch retries. */
    @Test fun anUnansweredWakeThatCannotBeProvenLitKeepsARetryTouch() {
        vendor.readable = false
        val sc = controller()
        sc.sleep()
        vendor.answers = false
        wakeTap.fireTap()
        assertFalse(sc.isIntendedOff())
        assertTrue("a retry touch stays armed", wakeTap.armed)
        vendor.answers = true
        wakeTap.fireTap()
        assertEquals(listOf(false, true, true), vendor.calls)
        assertFalse("answered, so no further retry", wakeTap.armed)
    }

    /** Stargate answers the wake yet still holds its off: the node is known dark, so the retry stays. */
    @Test fun anAnsweredWakeOnAKnownDarkNodeKeepsARetryTouch() {
        val enforcing = object : io.panelassistant.android.platform.VendorScreen {
            override fun set(on: Boolean) = true
            override fun level(): Int = 0
            override fun holdBrightness() = true
        }
        val sc = ScreenController(
            backlight, power, FakeRootShell(runResult = false), FakeDaemon(), wakeTap, ScreenOff.VENDOR_RPC,
            nap = {}, vendor = enforcing,
        )
        sc.sleep()
        wakeTap.fireTap()
        assertTrue("a known-dark wake retains the OFF owner for retry", sc.isIntendedOff())
        assertTrue(wakeTap.armed)
    }

    /** A retry that runs after a newer off, or after closure, must not wake anything. */
    @Test fun aStaleOrClosedRetryDoesNothing() {
        vendor.readable = false
        val sc = controller()
        sc.sleep()
        vendor.answers = false
        wakeTap.fireTap()
        val staleRetry = requireNotNull(wakeTap.armedCallback())
        vendor.answers = true
        sc.sleep()
        assertTrue(sc.isIntendedOff())
        val callsBefore = vendor.calls.size
        staleRetry()
        assertTrue("the newer off is not woken", sc.isIntendedOff())
        assertEquals(callsBefore, vendor.calls.size)

        vendor.answers = false
        wakeTap.fireTap()
        val retry = requireNotNull(wakeTap.armedCallback())
        sc.closeAdmission()
        val callsAtClose = vendor.calls.size
        retry()
        assertEquals("closed: no further wake", callsAtClose, vendor.calls.size)
    }

    @Test fun anAnsweredOrProvenWakeLeavesNoRetryTouch() {
        val sc = controller()
        sc.sleep()
        wakeTap.fireTap()
        assertFalse(wakeTap.armed)
    }

    @Test fun aDarkNodeNobodyAskedForIsRelitThroughTheVendor() {
        val sc = controller()
        vendor.node = 0

        assertTrue(sc.recoverUnexpectedDark())
        assertEquals(listOf(true), vendor.calls)
        assertTrue(vendor.node > 0)
    }

    @Test fun anUnreadableNodeIsUnknownSoNothingIsRelitOnAGuess() {
        vendor.readable = false
        val sc = controller()
        assertNull(sc.observedDark())
        assertFalse(sc.recoverUnexpectedDark())
        assertTrue(vendor.calls.isEmpty())
    }
}
