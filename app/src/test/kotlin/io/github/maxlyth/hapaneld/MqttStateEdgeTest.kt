package io.github.maxlyth.hapaneld

import io.github.maxlyth.hapaneld.mqtt.StateConverger.Observation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MqttStateEdgeTest {
    @Test fun unavailableClearsTheRetainedValueWithAnEmptyPayload() {
        assertEquals("", mqttStatePayload(Observation.Unavailable))
        assertEquals("""{"state":"ON","brightness":73}""", mqttStatePayload(Observation.Known("""{"state":"ON","brightness":73}""")))
        assertEquals("", mqttStatePayload(Observation.Known("")))
        assertTrue(
            "state channels retain by default",
            mqttStateChannel("relay1", "ha-paneld/p/relay1/state", observe = { Observation.Unavailable }).route.retain,
        )
    }

    @Test fun stateCommandsConflateByTheirChannelLeaf() {
        assertEquals("screen", mqttCommandChannel("p", "ha-paneld/p/screen/set"))
        assertEquals("relay3", mqttCommandChannel("p", "ha-paneld/p/relay3/set"))
        assertEquals("button_led3", mqttCommandChannel("p", "ha-paneld/p/button_led3/set"))
        assertEquals("home_dashboard", mqttCommandChannel("alpha", "ha-paneld/alpha/home_dashboard/set"))
    }

    @Test fun topicsOutsideTheCommandShapeHaveNoChannel() {
        listOf(
            "ha-paneld/p/set",
            "ha-paneld/p//set",
            "ha-paneld/p/screen/state",
            "ha-paneld/q/screen/set",
            "ha-paneld/p/a/b/set",
            "ha-paneld/p/Screen/set",
            "xha-paneld/p/screen/set",
            // An index in non-ASCII digits was once admitted by the relay topic check; it has no channel.
            "ha-paneld/p/relay٣/set",
        ).forEach { assertNull(it, mqttCommandChannel("p", it)) }
    }

    @Test fun anMqttChannelKeyCanNeverCollideWithAnHttpLiveSettingKey() {
        assertNull(mqttCommandChannel("p", "ha-paneld/p/http:screen/set"))
    }
}
