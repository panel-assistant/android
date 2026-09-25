package io.github.maxlyth.hapaneld.control

import java.io.File
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
        val working = File(requireNotNull(System.getProperty("user.dir")))
        return listOf(
            File(working, "app/src/main/kotlin/io/github/maxlyth/hapaneld/$relative"),
            File(working, "src/main/kotlin/io/github/maxlyth/hapaneld/$relative"),
        ).first(File::isFile).readText()
    }
}
