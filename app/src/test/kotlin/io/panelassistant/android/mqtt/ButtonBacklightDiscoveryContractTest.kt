package io.panelassistant.android.mqtt

import io.panelassistant.android.config.SettingsRegistry
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ButtonBacklightDiscoveryContractTest {
    // Source-text reason: pins MqttBridge.kt, deleted with the MQTT removal.
    private val mqtt = listOf(
        File("src/main/kotlin/io/panelassistant/android/MqttBridge.kt"),
        File("app/src/main/kotlin/io/panelassistant/android/MqttBridge.kt"),
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
        assertEquals(
            listOf("navbar_mode"),
            SettingsRegistry.SPECS.filter { it.ha?.body?.contains("mdi:gesture-tap-button") == true }.map { it.key },
        )
        val navbarStart = discovery.indexOf("// Soft navbar (select)")
        val navbar = discovery.substring(
            navbarStart,
            discovery.indexOf("// Persistent network adb (switch)", navbarStart),
        )

        assertTrue(navbar.contains("registryExposable(\"navbar_mode\")"))
        assertTrue(SettingsRegistry.spec("navbar_mode")!!.ha!!.body.contains("\"icon\":\"mdi:gesture-tap-button\""))
    }
}
