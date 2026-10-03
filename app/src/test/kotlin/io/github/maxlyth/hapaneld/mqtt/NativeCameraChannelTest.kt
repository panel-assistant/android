package io.github.maxlyth.hapaneld.mqtt

import io.github.maxlyth.hapaneld.panelassistant.PanelAssistantShadowReporter
import io.github.maxlyth.hapaneld.util.SuccessStickyProbe
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Camera enumeration through the production bridge and the native hello's descriptor/unsupported offer. */
internal class NativeCameraChannelTest : MqttWireRig() {
    @Test fun anUnsettledProbeKeepsTheCameraUntilEnumerationSettlesAbsent() {
        var now = 0L
        var enumerated: Boolean? = null
        val probe = SuccessStickyProbe(probe = { enumerated }, nowMs = { now })
        val rig = rig(runtimeBroker = "", cameraAvailability = probe::get)
        try {
            val native = PanelAssistantShadowReporter(log = {})
            rig.bridge.addStateSink(native.bindShape(rig.bridge::nativeChannelShape))
            assertCameraOffered(native, true)
            enumerated = false
            now = 1_000L
            assertCameraOffered(native, false)
            // A settled empty enumeration is sticky, even if a later service read would disagree.
            enumerated = true
            now = 2_000L
            assertCameraOffered(native, false)
        } finally {
            rig.close()
        }
    }

    @Test fun aFailingProbeKeepsTheCameraAcrossBackoffAndRetry() {
        var now = 0L
        var probes = 0
        val probe = SuccessStickyProbe<Boolean>(probe = { probes++; error("camera service starting") }, nowMs = { now })
        val rig = rig(runtimeBroker = "", cameraAvailability = probe::get)
        try {
            val native = PanelAssistantShadowReporter(log = {})
            rig.bridge.addStateSink(native.bindShape(rig.bridge::nativeChannelShape))
            assertCameraOffered(native, true)
            now = 999L
            assertCameraOffered(native, true)
            assertEquals(1, probes)
            now = 1_000L
            assertCameraOffered(native, true)
            assertEquals(2, probes)
        } finally {
            rig.close()
        }
    }

    @Test fun anUnsettledProbeKeepsTheCameraWhenEnumerationSettlesPresent() {
        var now = 0L
        var enumerated: Boolean? = null
        val probe = SuccessStickyProbe(probe = { enumerated }, nowMs = { now })
        val rig = rig(runtimeBroker = "", cameraAvailability = probe::get)
        try {
            val native = PanelAssistantShadowReporter(log = {})
            rig.bridge.addStateSink(native.bindShape(rig.bridge::nativeChannelShape))
            assertCameraOffered(native, true)
            enumerated = true
            now = 1_000L
            assertCameraOffered(native, true)
            enumerated = false
            now = 2_000L
            assertCameraOffered(native, true)
        } finally {
            rig.close()
        }
    }

    private fun assertCameraOffered(native: PanelAssistantShadowReporter, offered: Boolean) {
        val offer = native.offer()
        assertEquals("camera descriptor in hello", offered, offer.descriptors.any { it.channel == "camera_enabled" })
        assertEquals("only settled absence is explicitly unsupported", !offered, "camera_enabled" in offer.unsupported)
        if (offered) assertEquals("camera", offer.descriptors.single { it.channel == "camera_enabled" }.platform)
        assertTrue("unrelated screen channel stays described", offer.descriptors.any { it.channel == "screen" })
        assertFalse("screen is not withdrawn", "screen" in offer.unsupported)
    }
}
