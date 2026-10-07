package io.panelassistant.android.mqtt

import io.panelassistant.android.AutoSleepActivitySnapshot
import io.panelassistant.android.control.ZigbeeContainmentResult
import io.panelassistant.android.control.ZigbeeHealthSnapshot
import io.panelassistant.android.control.ZigbeeHealthState
import io.panelassistant.android.input.ButtonBus
import io.panelassistant.android.panelassistant.PanelAssistantReportResult
import io.panelassistant.android.panelassistant.PanelAssistantShadowReporter
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

/** The reports MQTT used to carry alone, reaching the native transport on a panel with no broker. */
internal class NativeMqttOnlyChannelsTest : MqttWireRig() {
    @Test fun aButtonPressWithoutBrokerIsReportedAsANativeEvent() {
        val rig = rig(runtimeBroker = "")
        try {
            val native = bound(rig)
            rig.bridge.start()
            assertTrue(runBlocking { rig.bridge.observeForNativeHello { true } })
            val button = native.offer().descriptors.single { it.channel == "button" }
            assertEquals("event", button.platform)
            assertTrue("keycode_home" in button.options!!)
            fullSync(native)

            ButtonBus.emit("KEYCODE_HOME")
            val event = JSONObject(requireNotNull(native.next(4, "native-session", 0L)))
            assertEquals("panel_assistant/report_event", event.getString("type"))
            assertEquals("native-session", event.getString("session"))
            assertEquals("button", event.getString("channel"))
            assertEquals("keycode_home", event.getString("event_type"))
            ButtonBus.emit("KEYCODE_BACK")
            val second = JSONObject(requireNotNull(native.next(5, "native-session", 0L)))
            assertTrue("event ids only grow", second.getLong("event_id") > event.getLong("event_id"))
            assertFalse(rig.bridge.isConnected())
        } finally { rig.close() }
    }

    @Test fun autoSleepActivityIsReportedWithoutBroker() {
        val rig = rig(
            runtimeBroker = "",
            autoSleepActivity = { AutoSleepActivitySnapshot(holdingAwake = true, policyHealthy = true) },
        )
        try {
            val native = bound(rig)
            rig.bridge.start()
            assertTrue(runBlocking { rig.bridge.observeForNativeHello { true } })
            val observation = observation(fullSync(native), "auto_sleep_activity")
            assertEquals("known", observation.getString("state"))
            assertEquals(true, observation.get("value"))
        } finally { rig.close() }
    }

    @Test fun cameraSnapshotIsReportedOnceAndNotRepeatedForFreshness() {
        val rig = rig(
            runtimeBroker = "",
            hasCamera = true,
            cameraSnapshotUrl = { "http://192.0.2.10:8888/api/v1/camera/snapshot" },
            configure = { it.setCameraEnabled(true) },
        )
        try {
            val native = bound(rig)
            rig.bridge.start()
            assertTrue(runBlocking { rig.bridge.observeForNativeHello { true } })
            val snapshot = observation(fullSync(native), "camera_snapshot")
            assertEquals("known", snapshot.getString("state"))
            assertEquals(
                "http://192.0.2.10:8888/api/v1/camera/snapshot",
                snapshot.getJSONObject("value").getString("url"),
            )

            // Another local pass refreshes the other channels, but never the acknowledged snapshot URL:
            // Home Assistant fetches a new frame for each image report.
            assertTrue(runBlocking { rig.bridge.observeForNativeHello { true } })
            val delta = JSONObject(requireNotNull(native.next(4, "native-session", 0L)))
            assertTrue("other channels still refresh", channels(delta).isNotEmpty())
            assertFalse("camera_snapshot" in channels(delta))
        } finally { rig.close() }
    }

    @Test fun zigbeeGatewayHealthCarriesItsStateAndMqttAttributes() {
        val native = PanelAssistantShadowReporter(log = {})
        val sink = native.bind { listOf("zigbee_gateway_health", "zigbee_gateway_health_attributes") }
        val health = ZigbeeHealthSnapshot(
            state = ZigbeeHealthState.DEGRADED_HIGH_CPU,
            gatewayCpu = 87,
            containment = ZigbeeContainmentResult.PARTIAL,
        )
        sink("zigbee_gateway_health_attributes", StateConverger.Observation.Known(health.mqttAttributes())) {}
        sink("zigbee_gateway_health", StateConverger.Observation.Known(health.state.wireValue)) {}
        val descriptor = native.offer().descriptors.single { it.channel == "zigbee_gateway_health" }
        assertEquals("sensor", descriptor.platform)
        assertEquals("zigbee_gateway_health", descriptor.uniqueSuffix)
        assertEquals("diagnostic", descriptor.entityCategory)
        assertEquals(ZigbeeHealthState.entries.map { it.wireValue }, descriptor.options)

        val observation = observation(fullSync(native), "zigbee_gateway_health")
        assertEquals("degraded_high_cpu", observation.getString("value"))
        val attributes = observation.getJSONObject("attributes")
        assertEquals(JSONObject(health.mqttAttributes()).keys().asSequence().toSet(), attributes.keys().asSequence().toSet())
        assertEquals(87, attributes.getInt("gateway_cpu_percent"))
        assertEquals("partial", attributes.getString("containment_result"))
        assertTrue(attributes.isNull("firmware"))
    }

    @Test fun nativeStartupRestoresTheButtonBacklightWithoutBroker() {
        val sent = CopyOnWriteArrayList<String>()
        val rig = rig(
            runtimeBroker = "",
            helperSend = { command -> sent += command; "OK" },
            configure = {
                it.attachProfile(io.panelassistant.android.control.fakeProfile(hasButtonBacklight = true))
                it.lastButtonBacklight = 255
            },
        )
        try {
            rig.bridge.start()
            assertFalse(rig.bridge.isConnected())
            assertNotNull("the stored level was re-applied: $sent", sent.singleOrNull { it.startsWith("BTN ") })
        } finally { rig.close() }
    }

    @Test fun anUnsetButtonBacklightIsLeftAlone() {
        val sent = CopyOnWriteArrayList<String>()
        val rig = rig(runtimeBroker = "", helperSend = { command -> sent += command; "OK" })
        try {
            rig.bridge.start()
            assertNull(sent.firstOrNull { it.startsWith("BTN ") })
        } finally { rig.close() }
    }

    private fun bound(rig: Rig): PanelAssistantShadowReporter = PanelAssistantShadowReporter(log = {}).also { native ->
        rig.bridge.addStateSink(native.bindShape(rig.bridge::nativeChannelShape))
        rig.bridge.addEventSink(native::event)
    }

    /** Runs an accepted session's full sync and returns its `full_begin` report. */
    private fun fullSync(native: PanelAssistantShadowReporter): JSONObject {
        native.open(native.offer().descriptors)
        val begin = JSONObject(requireNotNull(native.next(2, "native-session", 0L)))
        assertEquals("full_begin", begin.getString("sync"))
        native.onResult(PanelAssistantReportResult.Acknowledged(2, emptyMap()), 0L)
        assertEquals("full_end", JSONObject(requireNotNull(native.next(3, "native-session", 0L))).getString("sync"))
        native.onResult(PanelAssistantReportResult.Acknowledged(3, emptyMap()), 0L)
        assertTrue(native.fullSyncComplete())
        return begin
    }

    private fun observation(report: JSONObject, channel: String): JSONObject {
        val observations = report.getJSONArray("observations")
        return (0 until observations.length()).map(observations::getJSONObject).singleOrNull { it.getString("channel") == channel }
            ?: throw AssertionError("$channel not reported in $report")
    }

    private fun channels(report: JSONObject): Set<String> = report.getJSONArray("observations").let { observations ->
        (0 until observations.length()).mapTo(linkedSetOf()) { observations.getJSONObject(it).getString("channel") }
    }
}
