package io.panelassistant.android.state

import io.panelassistant.android.testsupport.PanelRuntimeRig
import io.panelassistant.android.hardware.LedController
import io.panelassistant.android.hardware.LedBackend
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
}
