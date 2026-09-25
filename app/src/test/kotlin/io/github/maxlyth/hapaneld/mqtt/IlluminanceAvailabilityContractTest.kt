package io.github.maxlyth.hapaneld.mqtt

import io.github.maxlyth.hapaneld.config.Capabilities
import io.github.maxlyth.hapaneld.config.SettingsRegistry
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Illuminance must be advertised from the live capability snapshot, never from a static flag captured
 * when the bridge was built. A constructor snapshot is taken before `SensorReporter.start()` has
 * learned whether the light sensor activates, so it cannot answer the question at all.
 */
class IlluminanceAvailabilityContractTest {
    // Source-text reason: pins MqttBridge.kt, deleted with the MQTT removal.
    private val mqtt = listOf(
        File("src/main/kotlin/io/github/maxlyth/hapaneld/MqttBridge.kt"),
        File("app/src/main/kotlin/io/github/maxlyth/hapaneld/MqttBridge.kt"),
    ).first(File::isFile).readText()

    @Test fun `the illuminance entity carries no static availability override`() {
        val illuminance = Regex("""registryExposable\("illuminance"[^)]*\)""").find(mqtt)
        assertTrue("illuminance is no longer registered for discovery", illuminance != null)
        // Reverting to `availableOverride = hasLight` reinstates the bug this test guards.
        assertFalse(illuminance!!.value.contains("availableOverride"))
    }

    @Test fun `no constructor light flag survives to shadow the capability snapshot`() {
        assertEquals(0, Regex("""private val hasLight: Boolean""").findAll(mqtt).count())
        assertEquals(0, Regex("""\bhasLight\b""").findAll(mqtt).count())
    }

    @Test fun `an unavailable light sensor withdraws the illuminance entity and its retained state`() {
        val spec = SettingsRegistry.spec("illuminance")!!
        assertFalse(spec.availableWhen(Capabilities(hasLight = false)))
        assertTrue(spec.availableWhen(Capabilities(hasLight = true)))
        // Withdrawal clears the retained state topic, so Home Assistant stops recording empty states.
        assertEquals(
            "ha-paneld/test/illuminance/state",
            io.github.maxlyth.hapaneld.hiddenReadOnlyStateTopic("illuminance", "test"),
        )
    }

    @Test fun `a change in light availability re-announces discovery`() {
        // Discovery is published once per connection, so an entity advertised optimistically at boot is
        // only withdrawn if the availability transition invalidates the snapshot and re-announces.
        val notify = mqtt.substring(
            mqtt.indexOf("internal fun notifyLightAvailabilityChanged()"),
            mqtt.indexOf("internal fun notifyLearnedProximityChanged()"),
        )
        assertTrue(notify.contains("discoveryCapabilities.invalidate()"))
        assertTrue(notify.contains("requestReAnnounce()"))
    }
}
