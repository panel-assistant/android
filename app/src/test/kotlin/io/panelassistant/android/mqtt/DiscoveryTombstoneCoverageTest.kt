package io.panelassistant.android.mqtt

import io.panelassistant.android.mqttKnownConfigTopics
import io.panelassistant.android.mqttReconfigurePublishesOffline
import io.panelassistant.android.mqttStalePanelCleanup
import io.panelassistant.android.mqttRetiredStateTopics
import io.panelassistant.android.hiddenReadOnlyStateTopic
import io.panelassistant.android.diagnosticObservation
import io.panelassistant.android.config.SettingsRegistry
import io.panelassistant.android.mqtt.StateConverger.Observation
import org.junit.Assert.assertTrue
import org.junit.Test

class DiscoveryTombstoneCoverageTest {
    @Test fun everyRegistryEntityHasAHistoricalTombstone() {
        val panel = "test"
        val known = mqttKnownConfigTopics(panel)
        val missing = SettingsRegistry.SPECS.mapNotNull { spec ->
            spec.ha?.let { "homeassistant/${it.component}/${panel}_${it.objectSuffix}/config" }
        }.filterNot { it in known }

        assertTrue("missing discovery tombstones: ${missing.joinToString()}", missing.isEmpty())
        assertTrue(
            "retired config-store diagnostic must remain tombstoned for existing panels",
            "homeassistant/sensor/${panel}_diag_schema_reconcile/config" in known,
        )
    }

    @Test fun replacementConnectionCleansRenamedPanelAndMarksItOffline() {
        val cleanup = mqttStalePanelCleanup("old", "new")

        assertTrue(cleanup.any { it.topic == "homeassistant/sensor/old_diag_cpu/config" && it.payload.isEmpty() && it.retain })
        assertTrue(cleanup.any { it.topic == "homeassistant/sensor/old_diag_schema_reconcile/config" && it.payload.isEmpty() && it.retain })
        assertTrue(cleanup.any { it.topic == "ha-paneld/old/diag_schema_reconcile/state" && it.payload.isEmpty() && it.retain })
        assertTrue(cleanup.any { it.topic == "ha-paneld/old/availability" && it.payload == "offline" && it.retain })
        assertTrue(cleanup.none { "/new_" in it.topic || "/new/" in it.topic })
    }

    @Test fun retiredDiagnosticsKeepHistoricalStateTombstones() {
        assertTrue("ha-paneld/test/diag_schema_reconcile/state" in mqttRetiredStateTopics("test"))
    }

    @Test fun hidingReadOnlySensorsClearsTheirRetainedStateIncludingSensitiveValues() {
        assertTrue(hiddenReadOnlyStateTopic("diag_wifi_ssid", "test") == "ha-paneld/test/diag_wifi_ssid/state")
        assertTrue(hiddenReadOnlyStateTopic("diag_wifi_rssi", "test") == "ha-paneld/test/diag_wifi_rssi/state")
        assertTrue(hiddenReadOnlyStateTopic("proximity", "test") == "ha-paneld/test/proximity/state")
        assertTrue(hiddenReadOnlyStateTopic("auto_sleep_activity", "test") == "ha-paneld/test/auto_sleep_activity/state")
        assertTrue(hiddenReadOnlyStateTopic("screen", "test") == "ha-paneld/test/screen/state")
        assertTrue(hiddenReadOnlyStateTopic("volume", "test") == "ha-paneld/test/volume/state")
        assertTrue(hiddenReadOnlyStateTopic("watchdog_enabled", "test") == null)
    }

    @Test fun unavailableWifiDoesNotRecreateStateBehindDiscoveryTombstones() {
        assertTrue(diagnosticObservation("diag_wifi_ssid", exposed = true, value = null) == Observation.Unavailable)
        assertTrue(diagnosticObservation("diag_wifi_rssi", exposed = true, value = null) == Observation.Unavailable)
        assertTrue(diagnosticObservation("diag_wifi_ssid", exposed = false, value = "Private") == Observation.Unavailable)
        assertTrue(diagnosticObservation("diag_wifi_rssi", exposed = true, value = "-63") == Observation.Known("-63"))
        assertTrue(diagnosticObservation("diag_cpu", exposed = true, value = null) == Observation.Known("unknown"))
    }

    @Test fun hidingTheOutageSensorClearsItsRetainedAttributes() {
        val exact = io.panelassistant.android.control.WifiOutageCounts(last24h = 3, saturated = false)
        val floor = io.panelassistant.android.control.WifiOutageCounts(last24h = 200, saturated = true)
        // Exposed: the attribute states plainly whether the number is a total or a floor.
        assertTrue(
            io.panelassistant.android.wifiOutageAttributeObservation(exposed = true, counts = exact) ==
                Observation.Known("""{"is_lower_bound":false}"""),
        )
        assertTrue(
            io.panelassistant.android.wifiOutageAttributeObservation(exposed = true, counts = floor) ==
                Observation.Known("""{"is_lower_bound":true}"""),
        )
        // Hidden, or with no tracker yet: the retained payload is cleared, never left behind for an
        // entity nobody exposed — the same rule the read-only diagnostics already follow.
        assertTrue(
            io.panelassistant.android.wifiOutageAttributeObservation(exposed = false, counts = floor) ==
                Observation.Unavailable,
        )
        assertTrue(
            io.panelassistant.android.wifiOutageAttributeObservation(exposed = true, counts = null) ==
                Observation.Unavailable,
        )
    }

    @Test fun theRetiredWeeklySensorsStateAndAttributesAreBothCleared() {
        val retired = mqttRetiredStateTopics("test")
        assertTrue("ha-paneld/test/diag_wifi_outages_7d/state" in retired)
        assertTrue("ha-paneld/test/diag_wifi_outages_7d/attributes" in retired)
        // A panel rename republishes the same retirement set, so neither half can survive it.
        val cleanup = mqttStalePanelCleanup("old", "test").map { it.topic }
        assertTrue("ha-paneld/old/diag_wifi_outages_7d/state" in cleanup)
        assertTrue("ha-paneld/old/diag_wifi_outages_7d/attributes" in cleanup)
    }

    @Test fun retiredAutoUpdateSettingsKeepDiscoveryAndStateCleanupAfterPanelRename() {
        val retired = mapOf(
            "self_update" to "switch", "update_channel" to "select",
            "companion_auto_update" to "switch", "companion_update_channel" to "select",
            "webview_auto_update" to "switch",
        )
        val known = mqttKnownConfigTopics("old")
        val cleanup = mqttStalePanelCleanup("old", "new")
        retired.forEach { (channel, platform) ->
            val discovery = "homeassistant/$platform/old_$channel/config"
            val state = "ha-paneld/old/$channel/state"
            assertTrue(discovery in known)
            assertTrue(state in mqttRetiredStateTopics("old"))
            for (topic in listOf(discovery, state)) {
                assertTrue(topic, cleanup.any { it.topic == topic && it.payload.isEmpty() && it.retain })
            }
        }
    }

    @Test fun unchangedPanelIdNeedsNoReplacementCleanup() {
        assertTrue(mqttStalePanelCleanup("same", "same").isEmpty())
        assertTrue(mqttStalePanelCleanup(null, "same").isEmpty())
    }

    @Test fun inPlaceReconfigureDoesNotRaceReplacementAvailability() {
        assertTrue(!mqttReconfigurePublishesOffline("same", "same", "tcp://broker:1883", "tcp://broker:1883"))
        assertTrue(!mqttReconfigurePublishesOffline("same", "same", "broker", "tcp://BROKER:1883"))
        assertTrue(mqttReconfigurePublishesOffline("old", "new", "tcp://broker:1883", "tcp://broker:1883"))
        assertTrue(mqttReconfigurePublishesOffline("same", "same", "tcp://old:1883", "tcp://new:1883"))
        // Equivalent TLS schemes are one broker identity, so a scheme-only edit must not retire availability.
        assertTrue(!mqttReconfigurePublishesOffline("same", "same", "mqtts://BROKER", "ssl://broker:8883"))
    }
}
