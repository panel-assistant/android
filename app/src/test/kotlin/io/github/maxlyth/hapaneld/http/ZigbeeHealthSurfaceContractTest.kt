package io.github.maxlyth.hapaneld.http

import java.io.File
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
        val working = File(requireNotNull(System.getProperty("user.dir")))
        return listOf(
            File(working, "app/src/main/kotlin/io/github/maxlyth/hapaneld/$relative"),
            File(working, "src/main/kotlin/io/github/maxlyth/hapaneld/$relative"),
        ).first(File::isFile).readText()
    }
}
