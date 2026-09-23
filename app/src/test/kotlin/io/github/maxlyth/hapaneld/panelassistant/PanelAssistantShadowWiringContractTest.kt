package io.github.maxlyth.hapaneld.panelassistant

import io.github.maxlyth.hapaneld.testsupport.TestSources
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The service owns one shadow reporter for the process: the transport owner reports from it, and every
 * MQTT bridge generation binds its converger to it. Constructing either side needs the Android service,
 * so the wiring is pinned at its source.
 */
class PanelAssistantShadowWiringContractTest {
    private val service by lazy { TestSources.kotlin("PaneldService.kt").readText() }
    private val bridge by lazy { TestSources.kotlin("MqttBridge.kt").readText() }

    @Test fun theTransportOwnerReportsFromTheServicesShadowReporter() {
        val owner = service.substringAfter("panelAssistantTransport = PanelAssistantTransportOwner(").substringBefore("\n        )\n")
        assertTrue(owner, owner.contains("shadow = panelAssistantShadow,"))
        assertEquals(1, Regex("""PanelAssistantShadowReporter\(""").findAll(service).count())
    }

    @Test fun everyBridgeGenerationBindsItsConvergerToTheReporter() {
        val build = service.substringAfter("private fun buildMqtt(").substringBefore("\n    }\n")
        assertTrue(build.contains(".also { bridge -> bridge.addStateSink(panelAssistantShadow.bind(bridge::stateChannelKeys)) }"))
        assertTrue(bridge.contains("internal fun addStateSink(sink: io.github.maxlyth.hapaneld.mqtt.StateSink) { nativeStateSink = sink }"))
        assertTrue(bridge.contains("onObservation = { channel, observation -> nativeStateSink?.invoke(channel, observation) {} }"))
        assertTrue(bridge.contains("internal fun stateChannelKeys(): Set<String> = stateConverger.keys()"))
    }
}
