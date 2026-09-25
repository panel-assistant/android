package io.github.maxlyth.hapaneld.mqtt

import io.github.maxlyth.hapaneld.config.SettingsRegistry
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ButtonBacklightDiscoveryContractTest {
    // Source-text reason: pins MqttBridge.kt, deleted with the MQTT removal.
    private val mqtt = listOf(
        File("src/main/kotlin/io/github/maxlyth/hapaneld/MqttBridge.kt"),
        File("app/src/main/kotlin/io/github/maxlyth/hapaneld/MqttBridge.kt"),
    ).first(File::isFile).readText()
    private val settingsRegistry = listOf(
        File("src/main/kotlin/io/github/maxlyth/hapaneld/config/SettingsRegistry.kt"),
        File("app/src/main/kotlin/io/github/maxlyth/hapaneld/config/SettingsRegistry.kt"),
    ).first(File::isFile).readText()
    private val discovery = mqtt.substring(
        mqtt.indexOf("private fun publishDiscovery("),
        mqtt.indexOf("private fun publishConfig("),
    )

    @Test fun buttonBacklightUsesHomeAssistantsNativeLightIcon() {
        val buttonBacklight = discovery.substring(
            discovery.indexOf("if (hasButtonBacklight)"),
            discovery.indexOf("// Config switches/numbers"),
        )

        assertTrue(buttonBacklight.contains("\"light\", \"\${panel}_buttons\""))
        assertTrue(buttonBacklight.contains("\"name\":\"Button backlight\""))
        assertTrue(buttonBacklight.contains("\"supported_color_modes\":[\"brightness\"]"))
        assertFalse(buttonBacklight.contains("\"icon\":"))
    }

    @Test fun touchIconRemainsLimitedToTheNavbarGestureControl() {
        assertEquals(1, Regex("mdi:gesture-tap-button").findAll(settingsRegistry).count())
        val navbarStart = discovery.indexOf("// Soft navbar (select)")
        val navbar = discovery.substring(
            navbarStart,
            discovery.indexOf("// Persistent network adb (switch)", navbarStart),
        )

        assertTrue(navbar.contains("registryExposable(\"navbar_mode\")"))
        assertTrue(settingsRegistry.contains("\"select\", \"navbar\", \"Navbar\""))
        assertTrue(SettingsRegistry.spec("navbar_mode")!!.ha!!.body.contains("\"icon\":\"mdi:gesture-tap-button\""))
    }
}
