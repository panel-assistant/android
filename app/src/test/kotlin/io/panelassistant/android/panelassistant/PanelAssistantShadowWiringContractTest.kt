package io.panelassistant.android.panelassistant

import io.panelassistant.android.testsupport.TestSources
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The service owns one shadow reporter for the process: the transport owner reports from it, and every
 * MQTT bridge generation binds its converger to it. Constructing either side needs the Android service,
 * so the wiring is pinned at its source.
 */
class PanelAssistantShadowWiringContractTest {
    // Source-text reason: pins MqttBridge.kt, deleted with the MQTT removal.
    private val bridge by lazy { TestSources.kotlin("MqttBridge.kt").readText() }

    @Test fun everyBridgeGenerationBindsItsConvergerToTheReporter() {
        assertTrue(bridge.contains("internal fun addStateSink(sink: io.panelassistant.android.mqtt.StateSink) { nativeStateSink = sink }"))
        assertTrue(bridge.contains("onObservation = { channel, observation -> nativeStateSink?.invoke(channel, observation) {} }"))
        assertTrue(bridge.contains("internal fun stateChannelKeys(): Set<String> = stateConverger.keys()"))
    }
}
