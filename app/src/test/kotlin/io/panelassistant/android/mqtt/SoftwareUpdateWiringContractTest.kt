package io.panelassistant.android.mqtt

import io.panelassistant.android.testsupport.TestSources
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The update entities are pure in [SoftwareUpdateEntities]; these checks pin the few lines that connect
 * them to the bridge, where a wrong wire would not show up in the
 * pure tests: a topic still dispatched straight to the legacy forced reinstall, an install that reads
 * its tag from somewhere other than the admission, or a header check that accepts any value.
 */
class SoftwareUpdateWiringContractTest {
    // Source-text reason: pins MqttBridge.kt, deleted with the MQTT removal.
    private val bridge by lazy { TestSources.kotlin("MqttBridge.kt").readText() }

    private fun between(text: String, from: String, to: String): String {
        val start = text.indexOf(from)
        assertTrue("marker not found: $from", start >= 0)
        val end = text.indexOf(to, start)
        assertTrue("marker not found after $from: $to", end > start)
        return text.substring(start, end)
    }

    @Test fun bothUpdateTopicsDispatchThroughTheSharedRouter() {
        val dispatch = between(bridge, "private fun dispatchCommand(", "fun publishScreenOn()")
        assertTrue(dispatch.contains("cmdUpdateCompanion -> handleSoftwareCommand(SoftwareComponent.COMPANION, payload)"))
        assertTrue(dispatch.contains("cmdUpdatePaneld -> handleSoftwareCommand(SoftwareComponent.PANELD, payload)"))
        // The forced legacy reinstall is reachable only as the PRESS callback of the router.
        assertEquals(1, Regex("""onSelfUpdate\(true\)""").findAll(bridge).count())
        val handler = between(bridge, "private fun handleSoftwareCommand(", "fun publishSoftwareUpdates()")
        assertTrue(handler.contains("SoftwareUpdateEntities.route("))
        assertTrue(handler.contains("install = { tag -> onSoftwareInstall(component, tag) }"))
    }

    @Test fun aWithheldEntityClearsItsRetainedStateForEitherReason() {
        // The state channel must follow the same withheld rule as discovery, or an absent Companion
        // would keep a retained "not installed" payload behind its tombstone.
        val channel = between(bridge, "SoftwareUpdateEntities.stateChannelKey(component),", "channel(\"storage_health_attributes\"")
        assertTrue(channel.contains("if (SoftwareUpdateEntities.withheld(inputs)) io.panelassistant.android.mqtt.StateConverger.Observation.Unavailable"))
    }

    @Test fun bridgeSyncAndDiscoveryRepublishTheEntities() {
        val sync = between(bridge, "private fun syncLocalState()", "private fun diagValue(")
        assertTrue(sync.contains("reconcileSoftwareUpdates(announcing = false)"))
        val discovery = between(bridge, "private fun publishDiscovery(", "private fun jsonEsc(")
        assertTrue(discovery.contains("reconcileSoftwareUpdates(announcing = true)"))
    }
}
