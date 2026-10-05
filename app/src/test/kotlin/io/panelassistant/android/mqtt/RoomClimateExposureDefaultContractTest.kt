package io.panelassistant.android.mqtt

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RoomClimateExposureDefaultContractTest {
    // Source-text reason: pins MqttBridge.kt, deleted with the MQTT removal.
    private val bridge = File("src/main/kotlin/io/panelassistant/android/MqttBridge.kt").readText()

    @Test fun diagnosticAndRoomClimateRuntimeUsesRegistryExposureDefaults() {
        assertEquals(
            2,
            Regex("requireNotNull\\(SettingsRegistry\\.spec\\(key\\)\\)\\.haExposedByDefault")
                .findAll(bridge)
                .count(),
        )
        assertTrue("config.haExposed(key, exposedByDefault)" in bridge)
    }
}
