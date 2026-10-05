package io.panelassistant.android.mqtt

import io.panelassistant.android.mqtt.StateConverger.Observation
import io.panelassistant.android.panelassistant.PanelAssistantReportResult
import io.panelassistant.android.panelassistant.PanelAssistantShadowReporter
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeObservationDeliveryTest {
    private class Rig {
        val reporter = PanelAssistantShadowReporter(log = {})
        val mqtt = mutableListOf<Pair<String, Observation.Reportable>>()
        val acknowledgements = mutableListOf<(Boolean) -> Unit>()
        val values = (1..5).associate { "relay$it" to "OFF" }.toMutableMap()
        private lateinit var native: StateSink
        val converger = StateConverger(
            sender = { channel, value, done -> mqtt += channel to value; acknowledgements += done },
            schedule = { it() },
            onObservation = { channel, value -> native(channel, value) {} },
        )

        init {
            values.keys.forEach { key -> converger.register(StateConverger.Channel(key, { Observation.Known(values.getValue(key)) })) }
            native = reporter.bind(converger::keys)
            reporter.open(reporter.descriptors())
            report(1)
            report(2)
        }

        fun report(id: Long): JSONObject {
            val request = reporter.next(id, "session", 0L)
            assertTrue("native report $id must be ready", request != null)
            return JSONObject(requireNotNull(request)).also {
                reporter.onResult(PanelAssistantReportResult.Acknowledged(id, emptyMap()), 0L)
            }
        }
    }

    @Test fun fifthNativeChannelAdvancesWhileFourMqttAcknowledgementsAreHeld() {
        val rig = Rig()
        (1..4).forEach { rig.converger.reconcile("relay$it") }
        rig.report(3)
        rig.values["relay5"] = "ON"
        rig.converger.reconcile("relay5")

        val report = rig.report(4).getJSONArray("observations")
        assertEquals(1, report.length())
        assertEquals("relay5", report.getJSONObject(0).getString("channel"))
        assertTrue(report.getJSONObject(0).getBoolean("value"))
        assertEquals(4, rig.mqtt.size)
        assertEquals(4, rig.converger.status().inFlight)
        assertEquals(0L, rig.converger.status().successes)
    }

    @Test fun sameNativeChannelAdvancesWhileItsMqttAcknowledgementIsHeld() {
        val rig = Rig()
        rig.converger.reconcile("relay1")
        rig.report(3)
        rig.values["relay1"] = "ON"
        rig.converger.reconcile("relay1")

        val observation = rig.report(4).getJSONArray("observations").getJSONObject(0)
        assertEquals("relay1", observation.getString("channel"))
        assertTrue(observation.getBoolean("value"))
        assertEquals(listOf("relay1" to Observation.Known("OFF")), rig.mqtt)
        assertEquals(1, rig.converger.status().inFlight)
    }

    @Test fun supersededReadbackReachesNeitherTransportEvenWithMqttCapacityFull() {
        var current = true
        val observed = mutableListOf<String>()
        val mqtt = mutableListOf<String>()
        val converger = StateConverger(
            sender = { channel, _, _ -> mqtt += channel },
            onObservation = { channel, _ -> observed += channel },
        )
        val admitted = (1..4).map { "relay$it" }
        admitted.forEach { channel ->
            converger.register(StateConverger.Channel(channel, { Observation.Known("OFF") }))
            converger.reconcile(channel)
        }
        converger.register(StateConverger.Channel("relay5", {
            current = false
            Observation.Known("ON")
        }))
        converger.reconcile("relay5", force = true, admit = { current })
        assertFalse(current)
        assertEquals(admitted, observed)
        assertEquals(admitted, mqtt)
        assertEquals(4, converger.status().inFlight)
    }

    @Test fun mqttAdmissionIsCheckedAgainAfterNativeRecording() {
        var current = true
        val native = mutableListOf<Observation.Reportable>()
        var mqtt = 0
        val converger = StateConverger(
            sender = { _, _, _ -> mqtt++ },
            onObservation = { _, value -> native += value; current = false },
        )
        converger.register(StateConverger.Channel("relay1", { Observation.Known("ON") }))
        converger.reconcile("relay1", force = true, admit = { current })
        assertEquals(listOf(Observation.Known("ON")), native)
        assertEquals(0, mqtt)
    }

    @Test fun synchronousMqttCompletionCannotReorderNativeObservations() {
        val native = mutableListOf<Observation.Reportable>()
        var value = "OFF"
        lateinit var converger: StateConverger
        converger = StateConverger(
            sender = { _, observation, done ->
                done(true)
                if (observation == Observation.Known("OFF")) {
                    value = "ON"
                    converger.reconcile("relay1")
                }
            },
            schedule = { it() },
            onObservation = { _, observation -> native += observation },
        )
        converger.register(StateConverger.Channel("relay1", { Observation.Known(value) }))
        converger.reconcile("relay1")
        assertEquals(listOf(Observation.Known("OFF"), Observation.Known("ON")), native)
    }

    @Test fun channelIdsFollowTheProtocolGrammar() {
        val converger = StateConverger(sender = { _, _, _ -> })
        listOf("screen", "relay3", "button_led3", "a", "a" + "b".repeat(47)).forEach {
            converger.register(StateConverger.Channel(it, observe = { Observation.Unknown }))
        }
        listOf("", "Screen", "3relay", "screen/state", "http:screen", "a" + "b".repeat(48), "_x").forEach { id ->
            val refused = runCatching { converger.register(StateConverger.Channel(id, observe = { Observation.Unknown })) }
            assertTrue("$id must be refused", refused.exceptionOrNull() is IllegalArgumentException)
        }
    }
}
