package io.github.maxlyth.hapaneld.http

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PerformanceBindingTest {
    private val comparison = "0123456789abcdef0123456789abcdef"
    private val deviceSecret = "a1b2c3d4e5f60718".repeat(4)
    private val workload = PERFORMANCE_WORKLOAD_KEYS.associateWith { "value-$it" }

    @Test fun workloadBindingIncludesEveryCurrentAdaptiveBrightnessControl() {
        val adaptiveKeys = setOf(
            "auto_brightness",
            "auto_brightness_minimum_percent", "auto_brightness_maximum_percent",
            "auto_brightness_response_percent",
            "auto_brightness_ha_entity",
        )

        assertTrue(PERFORMANCE_WORKLOAD_KEYS.containsAll(adaptiveKeys))
        assertFalse(PERFORMANCE_WORKLOAD_KEYS.contains("brightness_bias"))

        val baseline = JSONObject(requireNotNull(performanceBindingJson(comparison, deviceSecret, "panel-a", workload)))
        adaptiveKeys.forEach { key ->
            val changed = workload.toMutableMap().also { it[key] = "different-$key" }
            val binding = JSONObject(requireNotNull(
                performanceBindingJson(comparison, deviceSecret, "panel-a", changed),
            ))
            assertNotEquals(key, baseline.getString("workload_fingerprint"), binding.getString("workload_fingerprint"))
        }
    }

    @Test fun deviceBindingSecretMustBeAFullRandomKeyRatherThanAUserNameOrShortId() {
        assertTrue(validPerformanceDeviceSecret(deviceSecret))
        assertFalse(validPerformanceDeviceSecret("kitchen"))
        assertFalse(validPerformanceDeviceSecret("0000000000000000"))
        assertFalse(validPerformanceDeviceSecret("a1b2c3d4e5f60718"))
    }

    @Test fun bindingIsPairScopedAndDoesNotExposePrivateInputs() {
        val firstText = requireNotNull(performanceBindingJson(comparison, deviceSecret, "private-room", workload))
        val first = JSONObject(firstText)
        val repeated = JSONObject(requireNotNull(performanceBindingJson(comparison, deviceSecret, "private-room", workload)))
        val otherPair = JSONObject(requireNotNull(
            performanceBindingJson("fedcba9876543210fedcba9876543210", deviceSecret, "private-room", workload),
        ))

        assertEquals(first.getString("panel_fingerprint"), repeated.getString("panel_fingerprint"))
        assertNotEquals(first.getString("panel_fingerprint"), otherPair.getString("panel_fingerprint"))
        assertFalse(firstText.contains("private-room"))
        assertFalse(firstText.contains("value-home_dashboard"))
    }

    @Test fun bindingChangesForAnotherPanelOrWorkloadAndRejectsMalformedInputs() {
        val baseline = JSONObject(requireNotNull(performanceBindingJson(comparison, deviceSecret, "panel-a", workload)))
        val panel = JSONObject(requireNotNull(performanceBindingJson(comparison, deviceSecret, "panel-b", workload)))
        val changedWorkload = workload.toMutableMap().also { it["home_dashboard"] = "/different" }
        val target = JSONObject(requireNotNull(
            performanceBindingJson(comparison, deviceSecret, "panel-a", changedWorkload),
        ))

        assertNotEquals(baseline.getString("panel_fingerprint"), panel.getString("panel_fingerprint"))
        assertNotEquals(baseline.getString("workload_fingerprint"), target.getString("workload_fingerprint"))
        assertNull(performanceBindingJson("bad", deviceSecret, "panel-a", workload))
        assertNull(performanceBindingJson(comparison, "room-name", "panel-a", workload))
        assertNull(performanceBindingJson(comparison, deviceSecret, "panel-a", workload - "ha_url"))
    }
}
