package io.panelassistant.android.state

import io.panelassistant.android.control.FakeBacklight
import io.panelassistant.android.control.Backlight
import io.panelassistant.android.control.BuiltinDashboard
import io.panelassistant.android.control.FakeWakeTap
import io.panelassistant.android.device.ScreenOff
import io.panelassistant.android.device.profile.BundledProfileFixtures
import io.panelassistant.android.panelassistant.CommandValue
import io.panelassistant.android.panelassistant.PanelAssistantCommandResult
import io.panelassistant.android.platform.Daemon
import io.panelassistant.android.platform.DaemonLongResult
import io.panelassistant.android.platform.RootShell
import io.panelassistant.android.testsupport.PanelRuntimeRig
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Smatek S9E (android #197): a vendor light-sensor service rewrites the brightness every few minutes,
 * outside the panel's API. Home Assistant switched the screen off; whether the next vendor write turns
 * the screen switch back on depends on how the route proves the panel dark.
 */
internal class ScreenOffVendorBrightnessTest : PanelRuntimeRig() {
    @After fun restoreRenderer() { BuiltinDashboard.onScreenAwake(true) }

    /** A backlight power node behind su: `echo 4 >…bl_power` powers it down, `cat …bl_power` reads it. */
    private class BlPowerShell : RootShell {
        @Volatile var blPower = 0
        override fun available() = true
        override fun run(cmd: String): Boolean {
            Regex("""echo ([0-4]) >\S*bl_power""").find(cmd)?.let { blPower = it.groupValues[1].toInt() }
            return true
        }
        override fun runOutput(cmd: String): String? =
            if (cmd.contains("cat") && cmd.contains("bl_power")) "$blPower" else null
        override fun runBytes(cmd: String): ByteArray? = null
    }

    /** The root helper over the same node: `SCREEN OFF|ON` power it, `BLPOWER` reads it. */
    private class BlPowerHelper(private val node: BlPowerShell) : Daemon {
        override fun available() = true
        override fun send(cmd: String): String? = when (cmd) {
            "SCREEN OFF" -> { node.blPower = 4; "OK" }
            "SCREEN ON" -> { node.blPower = 0; "OK" }
            "BLPOWER" -> "${node.blPower}"
            else -> null
        }
        override fun sendLong(cmd: String, timeoutMs: Long): DaemonLongResult =
            send(cmd)?.let(DaemonLongResult::Reply) ?: DaemonLongResult.NotSubmitted
        override fun sendBytes(cmd: String): ByteArray? = null
    }

    private class Outcome(val published: List<String>, val intendedOff: Boolean, val blPower: Int, val level: Int)

    /** Switch the screen off from Home Assistant, let a sync tick see it dark, then let the vendor
     *  service write a level and run the next tick. */
    private fun offThenVendorWrite(route: ScreenOff, helper: Boolean = true): Outcome {
        val backlight = FakeBacklight(160)
        val shell = BlPowerShell()
        val daemon: Daemon = if (helper) BlPowerHelper(shell) else io.panelassistant.android.control.FakeDaemon()
        val rig = rig(screenOff = route, screenBacklight = backlight, screenRoot = shell, screenDaemon = daemon)
        val states = CopyOnWriteArrayList<String>()
        try {
            rig.core.addStateSink { channel, observation ->
                if (channel == "screen" && observation is StateConverger.Observation.Known) {
                    states += JSONObject(observation.payload).getString("state")
                }
            }
            rig.start()
            assertEquals(PanelAssistantCommandResult.Applied, submitNative(rig, "screen", CommandValue.Light(false)))
            rig.core.requestLocalObservation { true }
            drainStatePump()
            if (helper) assertEquals("Home Assistant's off was published", "OFF", states.last())
            backlight.level = 165
            rig.core.requestLocalObservation { true }
            drainStatePump()
            return Outcome(states.toList(), rig.screen.isIntendedOff(), shell.blPower, backlight.level)
        } finally { rig.close() }
    }

    @Test fun theS9eScreenSwitchStaysOffWhenTheVendorRewritesTheBrightness() {
        val route = BundledProfileFixtures.bundled.single { it.profile().id == "s9e" }.profile().screenOff
        val outcome = offThenVendorWrite(route)
        assertEquals("the switch never flips back on", "OFF", outcome.published.last())
        assertTrue("the off is still owned", outcome.intendedOff)
        assertEquals("backlight stays powered down", 4, outcome.blPower)
    }

    /** Without a helper su powers the backlight down, and repeated reads never use su, so the state is
     *  unknown while it is off: never a false ON, and the off and its touch wake are kept. */
    @Test fun withoutAHelperTheS9eOffIsUnknownRatherThanFlippedBackOn() {
        val route = BundledProfileFixtures.bundled.single { it.profile().id == "s9e" }.profile().screenOff
        val outcome = offThenVendorWrite(route, helper = false)
        assertFalse("never published ON while powered down", "ON" in outcome.published)
        assertTrue("the off is still owned", outcome.intendedOff)
        assertEquals("backlight stays powered down", 4, outcome.blPower)
    }

    @Test fun onTheBrightnessZeroRouteAVendorWriteCannotWakeThePanel() {
        val outcome = offThenVendorWrite(ScreenOff.BRIGHTNESS_ZERO)
        assertEquals("OFF", outcome.published.last())
        assertTrue(outcome.intendedOff)
        assertEquals("off is reasserted", 0, outcome.level)
    }

    @Test fun aFailedRawZeroReassertReportsTheLitScreenWithoutGivingUpItsOffOwner() {
        val backlight = object : Backlight {
            var level = 160
            var rawWritesWork = true
            override fun getBrightness() = level
            override fun setBrightness(level: Int) { this.level = level }
            override fun setBrightnessRaw(level: Int) { if (rawWritesWork) this.level = level }
        }
        val wakeTap = FakeWakeTap()
        val rig = rig(screenBacklight = backlight, screenWakeTap = wakeTap)
        val states = CopyOnWriteArrayList<String>()
        try {
            rig.core.addStateSink { channel, observation ->
                if (channel == "screen" && observation is StateConverger.Observation.Known) {
                    states += JSONObject(observation.payload).getString("state")
                }
            }
            rig.start()
            assertEquals(PanelAssistantCommandResult.Applied, submitNative(rig, "screen", CommandValue.Light(false)))
            val offGeneration = rig.screen.currentOffGeneration()
            assertTrue(offGeneration != null)
            assertEquals("OFF", states.last())
            assertFalse(BuiltinDashboard.screenAwakeNow)

            backlight.rawWritesWork = false
            backlight.level = 165
            rig.core.requestLocalObservation { true }
            drainStatePump()

            assertEquals("a failed reassert cannot hide physical light", "ON", states.last())
            assertTrue("a lit dashboard must run", BuiltinDashboard.screenAwakeNow)
            assertEquals(offGeneration, rig.screen.currentOffGeneration())
            assertTrue(rig.screen.isIntendedOff())
            assertTrue(wakeTap.armed)

            backlight.rawWritesWork = true
            rig.core.requestLocalObservation { true }
            drainStatePump()

            assertEquals(0, backlight.level)
            assertEquals("OFF", states.last())
            assertFalse("successful reassert pauses the dashboard again", BuiltinDashboard.screenAwakeNow)
            assertEquals(offGeneration, rig.screen.currentOffGeneration())
            assertTrue(wakeTap.armed)
        } finally { rig.close() }
    }
}
