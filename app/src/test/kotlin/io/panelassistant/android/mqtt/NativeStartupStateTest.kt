package io.panelassistant.android.mqtt

import io.panelassistant.android.hardware.LedController
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

internal class NativeStartupStateTest : MqttWireRig() {
    private class Led : LedController {
        var accepted = true
        val writes = mutableListOf<String>()
        override fun available() = true
        override fun colorCapable() = true
        override fun off(): Boolean { writes += "off"; return accepted }
        override fun setRgb(r: Int, g: Int, b: Int): Boolean {
            writes += "$r,$g,$b"
            return accepted
        }
    }

    @Test fun freshNativeStartupPublishesAcknowledgedLedOffWithoutBroker() {
        val led = Led()
        val rig = rig(runtimeBroker = "", led = led)
        val states = CopyOnWriteArrayList<String>()
        try {
            rig.bridge.addStateSink { channel, observation, _ ->
                if (channel == "led" && observation is StateConverger.Observation.Known) states += observation.payload
            }
            rig.bridge.start()
            assertTrue(runBlocking { rig.bridge.observeForNativeHello { true } })
            assertEquals(listOf("off"), led.writes)
            assertTrue(states.isNotEmpty())
            assertTrue(states.all { JSONObject(it).getString("state") == "OFF" })
            assertFalse(rig.bridge.isConnected())
        } finally { rig.close() }
    }

    @Test fun nativeStartupRestoresStoredLedColorWithoutBroker() {
        val led = Led()
        val rig = rig(runtimeBroker = "", led = led, configure = { it.lastLed = "1,128,255,0,0" })
        val states = CopyOnWriteArrayList<String>()
        try {
            rig.bridge.addStateSink { channel, observation, _ ->
                if (channel == "led" && observation is StateConverger.Observation.Known) states += observation.payload
            }
            rig.bridge.start()
            assertTrue(runBlocking { rig.bridge.observeForNativeHello { true } })
            assertEquals(listOf("128,0,0"), led.writes)
            assertTrue(states.isNotEmpty())
            assertEquals("ON", JSONObject(states.last()).getString("state"))
            assertEquals(128, JSONObject(states.last()).getInt("brightness"))
        } finally { rig.close() }
    }

    @Test fun refusedStartupActuationStaysUnknownUntilAcknowledgedRestore() {
        val led = Led().apply { accepted = false }
        val rig = rig(runtimeBroker = "", led = led)
        val states = CopyOnWriteArrayList<String>()
        try {
            rig.bridge.addStateSink { channel, observation, _ ->
                if (channel == "led" && observation is StateConverger.Observation.Known) states += observation.payload
            }
            rig.bridge.start()
            assertTrue(runBlocking { rig.bridge.observeForNativeHello { true } })
            assertEquals(listOf("off"), led.writes)
            assertTrue(states.isEmpty())
            assertTrue("led" in rig.bridge.nativeChannelShape().served)
            led.accepted = true
            rig.bridge.reapplyStoredLed()
            assertTrue(runBlocking { rig.bridge.observeForNativeHello { true } })
            assertEquals("OFF", JSONObject(states.last()).getString("state"))
        } finally { rig.close() }
    }

}
