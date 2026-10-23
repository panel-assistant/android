package io.panelassistant.android.state

import io.panelassistant.android.control.FakeBacklight
import io.panelassistant.android.device.ScreenOff
import io.panelassistant.android.device.profile.BundledProfileFixtures
import io.panelassistant.android.panelassistant.CommandValue
import io.panelassistant.android.panelassistant.PanelAssistantCommandResult
import io.panelassistant.android.platform.RootShell
import io.panelassistant.android.testsupport.PanelRuntimeRig
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Smatek S9E (android #197): a vendor light-sensor service rewrites the brightness every few minutes,
 * outside the panel's API. Home Assistant switched the screen off; whether the next vendor write turns
 * the screen switch back on depends on how the route proves the panel dark.
 */
internal class ScreenOffVendorBrightnessTest : PanelRuntimeRig() {
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

    private class Outcome(val published: List<String>, val intendedOff: Boolean, val blPower: Int)

    /** Switch the screen off from Home Assistant, let a sync tick see it dark, then let the vendor
     *  service write a level and run the next tick. */
    private fun offThenVendorWrite(route: ScreenOff): Outcome {
        val backlight = FakeBacklight(160)
        val shell = BlPowerShell()
        val rig = rig(screenOff = route, screenBacklight = backlight, screenRoot = shell)
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
            assertEquals("Home Assistant's off was published", "OFF", states.last())
            backlight.level = 165
            rig.core.requestLocalObservation { true }
            drainStatePump()
            return Outcome(states.toList(), rig.screen.isIntendedOff(), shell.blPower)
        } finally { rig.close() }
    }

    @Test fun theS9eScreenSwitchStaysOffWhenTheVendorRewritesTheBrightness() {
        val route = BundledProfileFixtures.bundled.single { it.profile().id == "s9e" }.profile().screenOff
        val outcome = offThenVendorWrite(route)
        assertEquals("the switch never flips back on", "OFF", outcome.published.last())
        assertTrue("the off is still owned", outcome.intendedOff)
        assertEquals("backlight stays powered down", 4, outcome.blPower)
    }

    /** The #197 flip-back: brightness 0 is this route's only evidence of dark, so a level written by
     *  someone else after the off is indistinguishable from a person waking the panel. */
    @Test fun onTheBrightnessZeroRouteAVendorWriteIsAdoptedAsAWake() {
        val outcome = offThenVendorWrite(ScreenOff.BRIGHTNESS_ZERO)
        assertEquals("ON", outcome.published.last())
        assertFalse(outcome.intendedOff)
    }
}
