package io.panelassistant.android.control

import io.panelassistant.android.testsupport.TestSources
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoBrightnessHistoryPartitionContractTest {
    // Source-text reason: pins MqttBridge.kt, deleted with the MQTT removal.
    private val mqtt = source("MqttBridge.kt")

    @Test fun `sensitivity reapplies policy without restarting the ambient source`() {
        val handler = mqtt.substringAfter("override fun handleAutoBrightnessSensitivity")
            .substringBefore("override fun handleAutoBrightnessMinimum")

        assertTrue(handler.contains("autoBright.reapplyLatest()"))
        assertFalse(handler.contains("onAutoBrightnessConfigChanged()"))
    }

    private fun source(relative: String): String {
        return TestSources.appFile("src/main/kotlin/io/panelassistant/android/$relative").readText()
    }
}
