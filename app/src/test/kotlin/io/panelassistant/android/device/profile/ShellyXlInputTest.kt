package io.panelassistant.android.device.profile

import io.panelassistant.android.BuildConfig
import io.panelassistant.android.input.AccessibilityKeyRouter
import io.panelassistant.android.input.ButtonBus
import io.panelassistant.android.input.EvdevButtonClient
import io.panelassistant.android.sensors.ProximityAcquisition
import io.panelassistant.android.sensors.proximityAcquisition
import io.panelassistant.android.sensors.proximitySourcePolicy
import io.panelassistant.android.testsupport.scratchDirectory
import org.junit.Assert.*
import org.junit.Test

class ShellyXlInputTest {
    private fun selected(model: String): io.panelassistant.android.device.DeviceProfile {
        val directory = scratchDirectory("xl-input").toFile()
        val prefs = object : ProfilePreferences {
            private val values = mutableMapOf<String, Any?>()
            override fun getString(key: String, default: String) = values[key] as? String ?: default
            override fun getLong(key: String, default: Long) = values[key] as? Long ?: default
            override fun put(vararg values: Pair<String, Any?>): Boolean { this.values.putAll(values); return true }
        }
        return try {
            RuntimeProfileRegistry(directory, prefs, BundledProfileFixtures::rawByName,
                DeviceFacts(model, "", ""), BuildConfig.VERSION_NAME, { 1_000L }).resolveForStartup().profile
        } finally { directory.deleteRecursively() }
    }

    @Test fun `selected XL maps named inputs without helper and isolates proximity pulses`() {
        val profile = selected("blake")
        assertEquals("shelly-wall-display-xl", profile.id)
        assertFalse(profile.appCanSu)
        assertEquals(setOf("input.android-keys", "screen.vendor-rpc", "sensor.android"),
            BundledProfileFixtures.bundledById.getValue(profile.id).document.requires.drivers)
        val buttons = mutableListOf<String>()
        val raw = mutableListOf<Float>()
        val consumer = ButtonBus.subscribe(buttons::add)
        val proximity = AccessibilityKeyRouter.subscribeProximity(requireNotNull(profile.proximityKeys), emit = raw::add)
        try {
            // This is the runtime entry point used by PaneldService; named inputs start no helper thread.
            EvdevButtonClient.start(profile.evdevButtons)
            assertEquals(EvdevButtonClient.State.STOPPED, EvdevButtonClient.snapshot().state)
            for (code in 59..62) {
                assertFalse(AccessibilityKeyRouter.dispatch("adc-keys", code, true, 0, "KEYCODE_F${code - 58}"))
                AccessibilityKeyRouter.dispatch("adc-keys", code, true, 1, "KEYCODE_F${code - 58}")
                AccessibilityKeyRouter.dispatch("adc-keys", code, false, 0, "KEYCODE_F${code - 58}")
            }
            AccessibilityKeyRouter.dispatch("rk805 pwrkey", 68, true, 0, "KEYCODE_F10")
            // Same key on a USB keyboard, a renamed source and a missing source must not impersonate XL buttons.
            for (name in listOf("USB keyboard", "adc-keys-extra", null)) {
                AccessibilityKeyRouter.dispatch(name, 59, true, 0, "KEYCODE_F1")
            }
            assertFalse(AccessibilityKeyRouter.dispatch("USB keyboard", 87, true, 0, "KEYCODE_F11"))
            for (code in listOf(63, 64)) {
                assertTrue(AccessibilityKeyRouter.dispatch("gpio_keys", code, true, 0, "KEYCODE_F${code - 58}"))
                assertTrue(AccessibilityKeyRouter.dispatch("gpio_keys", code, true, 1, "KEYCODE_F${code - 58}"))
                assertTrue(AccessibilityKeyRouter.dispatch("gpio_keys", code, false, 0, "KEYCODE_F${code - 58}"))
                assertFalse(AccessibilityKeyRouter.dispatch("other-device", code, true, 0, "KEYCODE_F${code - 58}"))
            }
            assertEquals(listOf("KEYCODE_F1", "KEYCODE_F2", "KEYCODE_F3", "KEYCODE_F4", "KEYCODE_F10", "KEYCODE_F11"), buttons)
            assertEquals(listOf(1f, 0f), raw)
            val acquisition = proximityAcquisition(false, null, false, hasKeyProximity = profile.proximityKeys != null)
            assertEquals(ProximityAcquisition.ANDROID_KEYS, acquisition)
            assertTrue(proximitySourcePolicy(acquisition, null).sparseLearning)
        } finally {
            EvdevButtonClient.stop()
            proximity.close()
            consumer.close()
        }
    }

    @Test fun `other modern Shelly models do not inherit XL input declarations`() {
        for (name in listOf("cally", "maverick", "dayna")) {
            val profile = selected(name)
            assertEquals("shelly-wall-display-v2", profile.id)
            assertTrue(profile.evdevButtons.isEmpty())
            assertNull(profile.proximityKeys)
        }
    }

    @Test fun `named input selectors round trip and reject conflicting helper options`() {
        val document = BundledProfileFixtures.bundledById.getValue("shelly-wall-display-xl").document
        assertEquals(document, ProfileYaml.parse(ProfileYaml.serialize(document)).document)
        for (button in listOf(
            document.input.evdevButtons.first().copy(node = "/dev/input/event3"),
            document.input.evdevButtons.first().copy(grab = true),
            document.input.evdevButtons.first().copy(deviceName = ""),
        )) {
            val invalid = document.copy(input = document.input.copy(evdevButtons = listOf(button)))
            assertTrue(ProfileValidator.validate(invalid, BuildConfig.VERSION_NAME, bundled = true).any {
                it.severity == ProfileIssueSeverity.ERROR
            })
        }
    }
}
