package io.panelassistant.android.http

import io.panelassistant.android.testsupport.TestSources
import org.junit.Assert.assertTrue
import org.junit.Test

class ZigbeeHealthSurfaceContractTest {
    // Source-text reason: pins MqttBridge.kt, deleted with the MQTT removal.

    @Test fun mqttDiscoveryPublishesAlwaysPresentDiagnosticHealthSensor() {
        val source = source("MqttBridge.kt")
        assertTrue(source.contains("\"sensor\", \"\${panel}_zigbee_gateway_health\""))
        assertTrue(source.contains("\"json_attributes_topic\":\"\$attrZigbeeHealth\""))
        assertTrue(source.contains("\"entity_category\":\"diagnostic\""))
        assertTrue(source.contains("if (on) onZigbeeExplicitRetry()"))
    }

    @Test fun bridgeJoinRequestUsesTheRouterAdmission() {
        val bridge = source("MqttBridge.kt")
        assertTrue(bridge.contains("fun requestZigbeeJoin(): Boolean"))
        assertTrue(bridge.contains("return admitZigbee(true) != LatestDispatcher.Admission.CLOSED"))
    }

    private fun source(relative: String): String {
        return TestSources.appFile("src/main/kotlin/io/panelassistant/android/$relative").readText()
    }
}
