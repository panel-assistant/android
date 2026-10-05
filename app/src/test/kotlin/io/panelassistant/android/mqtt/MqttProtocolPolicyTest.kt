package io.panelassistant.android.mqtt

import io.panelassistant.android.mqttAcceptsCommand
import io.panelassistant.android.mqttButtonEventTypes
import io.panelassistant.android.mqttDiscoveryCleanupMarker
import io.panelassistant.android.mqttDiscoveryRetain
import io.panelassistant.android.mqttDeviceSoftwareVersion
import io.panelassistant.android.mqttIsHaOnline
import io.panelassistant.android.shouldRepublishDiscoveryAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MqttProtocolPolicyTest {
    @Test fun `profile button events extend the discovery event type set`() {
        val types = mqttButtonEventTypes(setOf("KEYCODE_CUSTOM_PANEL", "KEYCODE_POWER"))
        assertTrue("KEYCODE_CUSTOM_PANEL" in types)
        assertEquals(1, types.count { it == "KEYCODE_POWER" })
        assertEquals(types.sorted(), types)
    }

    @Test fun onlyFreshCommandsReachHardware() {
        assertTrue(mqttAcceptsCommand(stopped = false, retained = false))
        assertFalse(mqttAcceptsCommand(stopped = false, retained = true))
        assertFalse(mqttAcceptsCommand(stopped = true, retained = false))
        assertFalse(mqttAcceptsCommand(stopped = true, retained = true))
    }

    @Test fun aPanelCutOverToTheNativeTransportTakesNoMqttCommand() {
        for (authority in listOf("", "mqtt", "shadow", "unrecognised")) {
            assertTrue(authority, mqttAcceptsCommand(stopped = false, retained = false, panelAssistantAuthority = authority))
        }
        assertFalse(mqttAcceptsCommand(stopped = false, retained = false, panelAssistantAuthority = "native"))
    }

    @Test fun onlyHomeAssistantOnlineBirthReannounces() {
        assertTrue(mqttIsHaOnline(" online\n".toByteArray()))
        assertTrue(mqttIsHaOnline("ONLINE".toByteArray()))
        assertFalse(mqttIsHaOnline("offline".toByteArray()))
        assertFalse(mqttIsHaOnline(byteArrayOf()))
    }

    @Test fun onlyDiscoveryTombstonesAreRetained() {
        assertFalse(mqttDiscoveryRetain("{\"name\":\"Screen\"}"))
        assertTrue(mqttDiscoveryRetain(""))
    }

    @Test fun deviceFirmwareVersionIncludesTheInstalledBuildNumber() {
        assertEquals("0.9.6-rc1 (build 480)", mqttDeviceSoftwareVersion("0.9.6-rc1", 480))
    }

    @Test fun discoveryCleanupMarkerChangesAcrossCoreAndProfileRevisions() {
        val first = mqttDiscoveryCleanupMarker("0.9.3", "panel.example@aaa", "tcp://broker-a:1883")

        assertTrue(first != mqttDiscoveryCleanupMarker("0.9.4", "panel.example@aaa", "tcp://broker-a:1883"))
        assertTrue(first != mqttDiscoveryCleanupMarker("0.9.3", "panel.example@bbb", "tcp://broker-a:1883"))
        assertTrue(first != mqttDiscoveryCleanupMarker("0.9.3", "other.panel@aaa", "tcp://broker-a:1883"))
        assertTrue(first != mqttDiscoveryCleanupMarker("0.9.3", "panel.example@aaa", "tcp://broker-b:1883"))
    }

    @Test fun discoveryCleanupMarkerChangesWhenTheEntityShapeChangesAtTheSameVersion() {
        val beforeRetirement = mqttDiscoveryCleanupMarker(
            "0.9.5-rc1",
            "panel.example@aaa",
            "tcp://broker:1883",
            discoveryShapeRevision = 1,
        )
        val afterRetirement = mqttDiscoveryCleanupMarker(
            "0.9.5-rc1",
            "panel.example@aaa",
            "tcp://broker:1883",
            discoveryShapeRevision = 2,
        )

        assertTrue(beforeRetirement != afterRetirement)
    }

    @Test fun discoveryCleanupMarkerIsStableAndAlwaysIncludesTheShapeRevision() {
        assertTrue(
            mqttDiscoveryCleanupMarker("0.9.3", "panel.example@aaa", "tcp://broker:1883") ==
                mqttDiscoveryCleanupMarker("0.9.3", "panel.example@aaa", "tcp://broker:1883"),
        )
        assertTrue(mqttDiscoveryCleanupMarker("0.9.3", "", "tcp://broker:1883") == "0.9.3|d6|b17:tcp://broker:1883")
    }

    @Test fun connectedPanelReannouncesOnlyWhenItsLiveConfigurationAddressChanges() {
        val old = "http://192.0.2.10:8888/"
        val replacement = "http://192.0.2.11:8888/"

        assertFalse(shouldRepublishDiscoveryAddress(connected = true, old, old))
        assertTrue(shouldRepublishDiscoveryAddress(connected = true, old, replacement))
        assertTrue(shouldRepublishDiscoveryAddress(connected = true, old, null))
        assertFalse(shouldRepublishDiscoveryAddress(connected = false, old, replacement))
    }
}
