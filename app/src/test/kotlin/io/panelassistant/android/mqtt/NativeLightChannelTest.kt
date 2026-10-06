package io.panelassistant.android.mqtt

import io.panelassistant.android.panelassistant.PanelAssistantShadowReporter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The light answer through the production bridge, on both transports: the native hello's descriptor and
 * unsupported lists, and MQTT discovery's illuminance config. Described (true) creates the entity,
 * settled absent (false) removes it, and never reported (null) creates nothing and deletes nothing.
 */
internal class NativeLightChannelTest : MqttWireRig() {
    @Test(timeout = 90_000) fun aSensorThatNeverReportedCreatesNoEntityUntilItsFirstReading() {
        var answer: Boolean? = null
        val rig = rig(lightChannel = { answer })
        try {
            val native = PanelAssistantShadowReporter(log = {})
            rig.bridge.addStateSink(native.bindShape(rig.bridge::nativeChannelShape))
            rig.announce()
            assertOffered(native, described = false, unsupported = false)
            assertTrue("no illuminance discovery at all", illuminanceConfigs(rig).isEmpty())

            answer = true
            val from = rig.transport.size()
            rig.bridge.notifyLightAvailabilityChanged()
            rig.transport.awaitPublication(from, "illuminance config") { it.topic() == CONFIG }
            rig.transport.drain()

            assertOffered(native, described = true, unsupported = false)
            assertTrue("the first reading publishes the entity", illuminanceConfigs(rig).last().isNotEmpty())
        } finally {
            rig.close()
        }
    }

    @Test(timeout = 90_000) fun aSettledAbsentSensorIsStatedUnsupportedAndItsMqttEntityRemoved() {
        val rig = rig(lightChannel = { false })
        try {
            val native = PanelAssistantShadowReporter(log = {})
            rig.bridge.addStateSink(native.bindShape(rig.bridge::nativeChannelShape))
            rig.announce()
            assertOffered(native, described = false, unsupported = true)
            assertEquals("an empty config removes the MQTT entity", listOf(""), illuminanceConfigs(rig))
        } finally {
            rig.close()
        }
    }

    @Test(timeout = 90_000) fun aSensorThatReportedStaysDescribed() {
        val rig = rig(lightChannel = { true })
        try {
            val native = PanelAssistantShadowReporter(log = {})
            rig.bridge.addStateSink(native.bindShape(rig.bridge::nativeChannelShape))
            rig.announce()
            assertOffered(native, described = true, unsupported = false)
            assertTrue(illuminanceConfigs(rig).single().isNotEmpty())
        } finally {
            rig.close()
        }
    }

    private fun illuminanceConfigs(rig: Rig): List<String> =
        rig.transport.snapshot().filter { it.topic() == CONFIG }.map { it.decodedPayload() }

    private fun assertOffered(native: PanelAssistantShadowReporter, described: Boolean, unsupported: Boolean) {
        val offer = native.offer()
        assertEquals("illuminance descriptor in hello", described, offer.descriptors.any { it.channel == "illuminance" })
        assertEquals("illuminance stated unsupported", unsupported, "illuminance" in offer.unsupported)
        assertTrue("unrelated screen channel stays described", offer.descriptors.any { it.channel == "screen" })
        assertFalse("screen is not withdrawn", "screen" in offer.unsupported)
    }

    private companion object {
        const val CONFIG = "homeassistant/sensor/golden_illuminance/config"
    }
}
