package io.github.maxlyth.hapaneld

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SelfUpdateChannelEntryPointContractTest {
    // Source-text reason: pins MqttBridge.kt, deleted with the MQTT removal.
    private fun source(relative: String): String = listOf(File(relative), File("app/$relative"))
        .first(File::isFile)
        .readText()

    @Test
    fun `mqtt channel change leaves persistence to exact candidate transaction`() {
        val bridge = source("src/main/kotlin/io/github/maxlyth/hapaneld/MqttBridge.kt")
        val stage = bridge.substring(bridge.indexOf("internal fun stageSelfUpdateChannelChange"), bridge.indexOf("internal class MqttBridge"))
        assertTrue(stage.indexOf("requestAdmittedInstall(requested, current)") < stage.lastIndexOf("publishCurrent()"))
        val handler = bridge.substring(bridge.indexOf("override fun handleUpdateChannel("), bridge.indexOf("override fun handleCompanionChannel("))
        assertTrue(handler.contains("stageSelfUpdateChannelChange("))
        assertFalse(handler.contains("config.setUpdateChannel(requested)"))
    }
}
