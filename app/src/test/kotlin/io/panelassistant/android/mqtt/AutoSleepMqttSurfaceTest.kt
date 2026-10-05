package io.panelassistant.android.mqtt

import io.panelassistant.android.MqttBridge
import io.panelassistant.android.AutoSleepWriteResult
import io.panelassistant.android.applyAutoSleepSetting
import io.panelassistant.android.config.SettingsRegistry
import io.panelassistant.android.mqttKnownConfigTopics
import io.panelassistant.android.autoSleepMqttProjection
import io.panelassistant.android.autoSleepAvailabilityFragment
import io.panelassistant.android.autoSleepMqttPublications
import io.panelassistant.android.AutoSleepActivitySnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.json.JSONObject

class AutoSleepMqttSurfaceTest {
    @Test fun `auto sleep discovery payload is valid JSON`() {
        val fragment = autoSleepAvailabilityFragment(
            "ha-paneld/test/availability",
            "ha-paneld/test/auto_sleep_activity/availability",
        )
        val entity = SettingsRegistry.spec("auto_sleep_activity")!!.ha!!
        val payload = entity.buildDiscoveryJson("test", fragment, "\"device\":{}")

        val parsed = JSONObject(payload)
        assertEquals("all", parsed.getString("availability_mode"))
        assertEquals(2, parsed.getJSONArray("availability").length())
        assertEquals("ha-paneld/test/auto_sleep_activity/state", parsed.getString("state_topic"))
        assertEquals("ha-paneld/test/auto_sleep_activity/attributes", parsed.getString("json_attributes_topic"))
    }

    @Test fun `mqtt exposes only the default-off auto sleep config switch`() {
        val spec = SettingsRegistry.spec("auto_sleep")

        assertNotNull(spec?.ha)
        assertFalse(spec?.haExposedByDefault == true)
        assertTrue("auto_sleep" in MqttBridge.APPLY_SETTING_KEYS)
        assertTrue(mqttKnownConfigTopics("test").contains("homeassistant/switch/test_auto_sleep/config"))
    }

    @Test fun `mqtt exposes binary policy activity history entity`() {
        assertNotNull(SettingsRegistry.spec("auto_sleep_activity")?.ha)
        assertTrue(mqttKnownConfigTopics("test").any { "auto_sleep_activity" in it })
    }

    @Test fun `mqtt activity is binary and publishes health before unavailable state`() {
        val projection = autoSleepMqttProjection(AutoSleepActivitySnapshot(
            holdingAwake = true,
            policyHealthy = true,
            reason = "holding_awake",
            learnedDelay = "12m",
            sourceCount = 2,
            phase = "live",
        ))
        assertEquals("ON", projection.state)
        assertEquals("online", projection.availability)
        assertTrue(projection.attributes.contains("\"source_count\":2"))

        val unavailable = autoSleepMqttPublications("test", true, AutoSleepActivitySnapshot())
        assertEquals("availability", unavailable.first().topic.substringAfterLast('/'))
        assertEquals("offline", unavailable.first().payload)
    }

    @Test fun `mqtt activity reports the complete automatic source union`() {
        val projection = autoSleepMqttProjection(AutoSleepActivitySnapshot(sourceCount = 99))

        assertTrue(projection.attributes.contains("\"source_count\":99"))
    }

    @Test fun `mqtt refreshes runtime once only after a changed durable value`() {
        val refreshed = mutableListOf<Boolean>()

        applyAutoSleepSetting(true, { AutoSleepWriteResult.COMMITTED }, refreshed::add)
        applyAutoSleepSetting(true, { AutoSleepWriteResult.UNCHANGED }, refreshed::add)

        assertEquals(listOf(true), refreshed)
    }

    @Test fun `mqtt reports a failed auto sleep commit`() {
        val failure = runCatching {
            applyAutoSleepSetting(true, { AutoSleepWriteResult.FAILED }) { error("must not refresh") }
        }.exceptionOrNull()

        assertTrue(failure is IllegalStateException)
    }
}
