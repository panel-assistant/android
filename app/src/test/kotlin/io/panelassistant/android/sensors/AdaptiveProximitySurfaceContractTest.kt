package io.panelassistant.android.sensors

import io.panelassistant.android.config.SettingsRegistry
import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdaptiveProximitySurfaceContractTest {
    // Source-text reason: pins MqttBridge.kt, deleted with the MQTT removal.
    @Test fun homeAssistantReceivesOnlyAvailableFleetNormalizedState() {
        val mqtt = source("MqttBridge.kt")

        assertTrue(mqtt.contains("normalizedLevel?.coerceIn(0, 100)"))
        assertTrue(mqtt.contains("registryExposable(\"proximity_level\""))
        // The proximity_level discovery payload is registry-driven; its % unit now lives in the
        // single SettingsRegistry descriptor rather than a MqttBridge literal (byte-parity is pinned
        // by DiscoveryParityTest).
        assertTrue(
            SettingsRegistry.spec("proximity_level")!!.ha!!
                .buildDiscoveryJson("test", "a", "d").contains("\"unit_of_measurement\":\"%\""),
        )
        assertTrue(mqtt.contains("availability_mode\":\"all\""))
        assertTrue(mqtt.contains("numericDeadband(4.0)"))
        assertTrue(mqtt.contains("publish(stateProximityLevel, \"\", retain = true)"))
        assertTrue(
            mqtt.indexOf("publish(proximityAvailabilityTopic") <
                mqtt.indexOf("publish(availabilityTopic, \"online\""),
        )
        assertFalse(mqtt.contains("fun publishProximity(near: Boolean)"))
    }

    @Test fun wakeOnWaveHelpExplainsTheGestureAndTheTouchFallback() {
        val help = SettingsRegistry.spec("wake_on_wave")!!.help
        assertTrue(help.contains("clear-to-near-to-clear"))
        assertTrue(help.contains("touch-to-wake remains available"))
    }

    @Test fun independentReportMaskIsNotCollapsedByTheMqttStateOwner() {
        val mqtt = source("MqttBridge.kt")

        assertTrue(mqtt.contains("reportMask: Int = ProximityReportGate.BOTH"))
        assertTrue(mqtt.contains("val admitted = admit(nextNear, level, reportMask)"))
        assertTrue(mqtt.contains("proximityPublication.near?.let"))
        assertTrue(mqtt.contains("proximityPublication.level?.let"))
        assertTrue(mqtt.contains("admitted and ProximityReportGate.PRESENCE != 0"))
        assertTrue(mqtt.contains("admitted and ProximityReportGate.LEVEL != 0"))
    }

    private fun source(relative: String): String = locate("src/main/kotlin/io/panelassistant/android/$relative").readText()

    private fun locate(relative: String): File = listOf(File(relative), File("app/$relative"), File("../app/$relative"))
        .firstOrNull(File::isFile) ?: error("missing test input $relative")
}
