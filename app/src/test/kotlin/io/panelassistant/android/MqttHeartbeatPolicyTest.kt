package io.panelassistant.android

import io.panelassistant.android.config.SettingsRegistry
import io.panelassistant.android.mqtt.StateConverger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MqttHeartbeatPolicyTest {
    @Test fun fiveMinuteRefreshThresholdIsLimitedToReadOnlyMeasurementSensors() {
        val expected = setOf(
            "illuminance", "proximity_level", "temperature", "humidity",
            "diag_cpu", "diag_memory", "diag_soc_temp", "diag_wifi_rssi",
            "diag_wifi_outages_24h", "room_temp", "room_humidity",
        )

        assertEquals(300_000L, MQTT_MEASUREMENT_REFRESH_AFTER_ACK_MS)
        assertEquals(
            expected,
            SettingsRegistry.haCapable().mapNotNull { spec ->
                mqttMeasurementRefreshAfterAckMs(spec.key)?.let {
                    val ha = requireNotNull(spec.ha)
                    assertEquals(MQTT_MEASUREMENT_REFRESH_AFTER_ACK_MS, it)
                    assertEquals("sensor", ha.component)
                    assertTrue(ha.readOnly)
                    assertTrue(ha.body.contains("\"state_class\":\"measurement\""))
                    spec.key
                }
            }.toSet(),
        )
        listOf(
            "screen", "volume", "proximity", "auto_sleep_activity",
            "diag_ip", "diag_boot", "diag_wifi_ssid",
        ).forEach { assertNull("$it must remain change-only", mqttMeasurementRefreshAfterAckMs(it)) }

        val observation = { StateConverger.Observation.Known("50") }
        val measurement = mqttStateChannel("diag_cpu", "cpu/state", retain = false, observe = observation)
        assertEquals(MQTT_MEASUREMENT_REFRESH_AFTER_ACK_MS, measurement.channel.maxSilenceMs)
        assertFalse("the channel factory must preserve per-channel retain policy", measurement.route.retain)
        assertTrue(measurement.channel.refreshEligible("50"))
        assertFalse(measurement.channel.refreshEligible("unknown"))
        assertFalse(measurement.channel.refreshEligible("NaN"))
        assertNull(mqttStateChannel("screen", "screen/state", observe = observation).channel.maxSilenceMs)
    }
}
