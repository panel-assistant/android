package io.panelassistant.android.state

import io.panelassistant.android.testsupport.PanelRuntimeRig
import io.panelassistant.android.hardware.LedController
import io.panelassistant.android.hardware.LedBackend
import io.panelassistant.android.hardware.StripOrHelperLedController
import io.panelassistant.android.hardware.Ws2812Driver
import io.panelassistant.android.hardware.Ws2812LedController
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

internal class NativeStartupStateTest : PanelRuntimeRig() {
    private class Led : LedController {
        var accepted = true
        val writes = mutableListOf<String>()
        override fun available() = true
        override fun backend() = LedBackend.LEDJNI
        override fun colorCapable() = true
        override fun off(): Boolean { writes += "off"; return accepted }
        override fun setRgb(r: Int, g: Int, b: Int): Boolean {
            writes += "$r,$g,$b"
            return accepted
        }
    }

    @Test fun freshStartupReportsAcknowledgedLedOff() {
        val led = Led()
        val rig = rig(led = led)
        val states = CopyOnWriteArrayList<String>()
        try {
            rig.core.addStateSink { channel, observation ->
                if (channel == "led" && observation is StateConverger.Observation.Known) states += observation.payload
            }
            rig.start()
            assertTrue(runBlocking { rig.core.observeForNativeHello { true } })
            assertEquals(listOf("off"), led.writes)
            assertTrue(states.isNotEmpty())
            assertTrue(states.all { JSONObject(it).getString("state") == "OFF" })
        } finally { rig.close() }
    }

    @Test fun startupRestoresStoredLedColor() {
        val led = Led()
        val rig = rig(led = led, configure = { it.lastLed = "1,128,255,0,0" })
        val states = CopyOnWriteArrayList<String>()
        try {
            rig.core.addStateSink { channel, observation ->
                if (channel == "led" && observation is StateConverger.Observation.Known) states += observation.payload
            }
            rig.start()
            assertTrue(runBlocking { rig.core.observeForNativeHello { true } })
            assertEquals(listOf("128,0,0"), led.writes)
            assertTrue(states.isNotEmpty())
            assertEquals("ON", JSONObject(states.last()).getString("state"))
            assertEquals(128, JSONObject(states.last()).getInt("brightness"))
        } finally { rig.close() }
    }

    @Test fun refusedStartupActuationStaysUnknownUntilAcknowledgedRestore() {
        val led = Led().apply { accepted = false }
        val rig = rig(led = led)
        val states = CopyOnWriteArrayList<String>()
        try {
            rig.core.addStateSink { channel, observation ->
                if (channel == "led" && observation is StateConverger.Observation.Known) states += observation.payload
            }
            rig.start()
            assertTrue(runBlocking { rig.core.observeForNativeHello { true } })
            assertEquals(listOf("off"), led.writes)
            assertTrue(states.isEmpty())
            assertTrue("led" in rig.core.nativeChannelShape().served)
            led.accepted = true
            rig.core.reapplyStoredLed()
            assertTrue(runBlocking { rig.core.observeForNativeHello { true } })
            assertEquals("OFF", JSONObject(states.last()).getString("state"))
        } finally { rig.close() }
    }

    @Test fun volumeAndGovernorChangedOutsideThePanelAfterStartupAreSyncedBeforeAnyCommand() {
        val rig = rig(cpuGovernor = "schedutil")
        try {
            rig.audio.percent = 40
            rig.start()
            rig.audio.percent = 70
            rig.metrics.governor = "performance"
            rig.core.requestLocalObservation { true }
            drainStatePump()
            assertEquals(
                listOf("cpu_governor Auto→Performance", "volume 40→70"),
                rig.core.recentSyncEvents().map { it.substringAfter(" · ") }
                    .filter { it.startsWith("cpu_governor ") || it.startsWith("volume ") },
            )
        } finally { rig.close() }
    }

    private class Strip(var opens: Boolean, var node: Boolean = true) : Ws2812Driver {
        val fills = mutableListOf<List<Int>>()
        override fun available() = opens
        override fun fill(r: Int, g: Int, b: Int, count: Int): Int { if (!opens) return -16; fills += listOf(r, g, b); return 0 }
        override fun nodePresent() = node
    }

    private class Helper(var present: Boolean, val absent: Boolean = !present) : LedController {
        val writes = mutableListOf<String>()
        override fun available() = present
        override fun backend() = if (present) LedBackend.HELPER_LEDJNI else if (absent) LedBackend.ABSENT else LedBackend.UNAVAILABLE
        override val confirmedAbsent get() = absent
        override fun colorCapable() = true
        override fun off(): Boolean { writes += "off"; return present }
        override fun setRgb(r: Int, g: Int, b: Int): Boolean { writes += "$r,$g,$b"; return present }
    }

    private fun stripOrHelper(strip: Strip, helper: Helper) =
        StripOrHelperLedController(Ws2812LedController(driver = strip, ledCount = 1), strip, helper)

    // A WS2812 strip that fails to open at startup while the helper finds no LED: the light is kept
    // (indeterminate, never absent) and, once the strip opens, the capability refresh applies the stored colour.
    @Test fun aStripThatRecoversAfterStartupKeepsTheLightAndGetsTheStoredColour() {
        val strip = Strip(opens = false)
        val rig = rig(led = stripOrHelper(strip, Helper(present = false)), configure = { it.lastLed = "1,128,255,0,0" })
        try {
            rig.start()
            assertEquals(null, rig.core.hardwareAvailability("led", null))
            assertTrue("led" in rig.core.nativeChannelShape().served)
            assertTrue(strip.fills.isEmpty())
            strip.opens = true
            rig.core.refreshLedRoute()
            assertEquals(listOf(listOf(128, 0, 0)), strip.fills)
        } finally { rig.close() }
    }

    // The strip node is visible but keeps failing; a helper LED that works is used meanwhile.
    @Test fun aFailingStripNeverStrandsAWorkingHelper() {
        val helper = Helper(present = true)
        val rig = rig(led = stripOrHelper(Strip(opens = false), helper), configure = { it.lastLed = "1,255,0,255,0" })
        try {
            rig.start()
            assertEquals(listOf("0,255,0"), helper.writes)
            assertEquals(null, rig.core.hardwareAvailability("led", null))
        } finally { rig.close() }
    }

    // Absence is reported only once both routes are ruled out: no strip node and the helper found none.
    @Test fun noStripNodeAndNoHelperLedIsAbsent() {
        val rig = rig(led = stripOrHelper(Strip(opens = false, node = false), Helper(present = false)))
        try {
            rig.start()
            assertEquals(false, rig.core.hardwareAvailability("led", null))
        } finally { rig.close() }
    }
}
