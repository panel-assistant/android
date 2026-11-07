package io.panelassistant.android.panelassistant

import io.panelassistant.android.config.ChannelOption
import io.panelassistant.android.config.SettingsRegistry
import io.panelassistant.android.state.StateConverger
import io.panelassistant.android.testsupport.PanelRuntimeRig
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

internal class PanelAssistantChannelCatalogTest : PanelRuntimeRig() {
    @Test fun writableNumberDescriptorsProjectTheirRegisteredDomain() {
        val numbers = SettingsRegistry.settable().filter { it.ha?.component == "number" }
        assertTrue(numbers.any { it.key == "dashboard_idle_return_min" })
        numbers.forEach { spec ->
            val json = requireNotNull(PanelAssistantChannelCatalog.describe(spec.ha!!.channel)).toJson()
            assertEquals(spec.key, requireNotNull(spec.min), json.getDouble("min"), 0.0)
            assertEquals(spec.key, requireNotNull(spec.max), json.getDouble("max"), 0.0)
            assertEquals(spec.key, spec.step ?: 1.0, json.getDouble("step"), 0.0)
        }
    }

    @Test fun theFormerSnapshotImageIsRetiredInsteadOfDescribed() {
        assertNull(PanelAssistantChannelCatalog.describe("camera_snapshot"))
        assertTrue("camera_snapshot" in PanelAssistantChannelCatalog.RETIRED_CHANNELS)
    }

    @Test fun cameraControlDescribesAnOrdinaryEnabledCameraAndReportsBothPowerStates() {
        val descriptor = PanelAssistantChannelCatalog.describe("camera_enabled")!!
        val json = descriptor.toJson()
        assertEquals("camera", json.getString("platform"))
        assertEquals("camera", json.getString("translation_key"))
        assertEquals("camera", json.getString("unique_suffix"))
        assertTrue(json.getBoolean("enabled_default"))
        assertTrue(json.isNull("entity_category"))
        assertEquals(PanelAssistantWireValue.Known(false), PanelAssistantValueTranslation.translate(descriptor, StateConverger.Observation.Known("OFF")))
        assertEquals(PanelAssistantWireValue.Known(true), PanelAssistantValueTranslation.translate(descriptor, StateConverger.Observation.Known("ON")))
    }

    @Test fun mediaIsAnEnabledMediaPlayerTheNativeTransportCommands() {
        val json = PanelAssistantChannelCatalog.describe("media")!!.toJson()
        assertEquals("media_player", json.getString("platform"))
        assertEquals("media", json.getString("translation_key"))
        assertEquals("media", json.getString("unique_suffix"))
        assertTrue(json.getBoolean("enabled_default"))
        assertTrue(json.isNull("entity_category"))
        assertTrue("media_player" in PanelAssistantCommandProcessor.COMMANDABLE_PLATFORMS)
        assertTrue("the converger registers media", "media" in everyChannel())
    }

    @Test fun everyRegisteredChannelIsDescribed() {
        val keys = everyChannel()
        // The rig must register the channels this exists to guard, or an empty set would pass vacuously.
        assertTrue("registered only ${keys.size} channels: $keys", keys.size >= 40)
        assertTrue(keys.containsAll(listOf("screen", "relay1", "button_led1", "cpu_governor", "room_humidity", "update_paneld")))
        val undescribed = keys.filter { PanelAssistantChannelCatalog.describe(it) == null }
        assertEquals("converger channels with no descriptor", emptyList<String>(), undescribed)
        assertNull(PanelAssistantChannelCatalog.describe("future_leaf"))
    }

    @Test fun renamingADisplayLabelChangesNoWireCode() {
        val renamedSelects = SettingsRegistry.haCapable().filter { it.ha!!.facts.options != null }
        assertTrue(renamedSelects.map { it.key }.containsAll(listOf("navbar_mode", "cpu_governor")))
        for (spec in renamedSelects) {
            val entity = spec.ha!!
            val renamed = entity.facts.options!!.map { ChannelOption(it.code, "${it.label} (renamed)") }
            val before = PanelAssistantChannelCatalog.describe(spec)
            val after = PanelAssistantChannelCatalog.describe(spec.copy(ha = entity.copy(facts = entity.facts.copy(options = renamed))))
            assertEquals("${spec.key} wire codes", before.options, after.options)
            assertEquals("${spec.key} descriptor json", before.toJson().toString(), after.toJson().toString())
            for (option in renamed) {
                assertEquals("${spec.key} ${option.code} state", PanelAssistantWireValue.Known(option.code),
                    PanelAssistantValueTranslation.translate(after, StateConverger.Observation.Known(option.label)))
                if (after.platform == "select") {
                    assertEquals("${spec.key} ${option.code} command", CommandValue.Option(option), decode(after, option.code))
                }
            }
        }
    }

    @Test fun uniqueSuffixIsTheHistoricalUniqueIdAfterThePanelPrefix() {
        assertEquals("ha_paneld_update", PanelAssistantChannelCatalog.describe("update_paneld")?.uniqueSuffix)
        assertEquals("ha_companion_update", PanelAssistantChannelCatalog.describe("update_companion")?.uniqueSuffix)
        assertEquals("navbar", PanelAssistantChannelCatalog.describe("navbar")?.uniqueSuffix)
        // A registry entity takes its object suffix, not its channel name, when the two differ.
        val navbar = requireNotNull(SettingsRegistry.spec("navbar_mode"))
        val renamed = navbar.copy(ha = navbar.ha!!.copy(objectSuffix = "navigation_bar", channel = "navbar"))
        assertEquals("navigation_bar", PanelAssistantChannelCatalog.describe(renamed).uniqueSuffix)
    }

    @Test fun familiesCarryTheirFamilyAndIndexAndShareOneTranslationKey() {
        val relay = described("relay3")
        assertEquals(listOf("switch", "relay", "relay", "3", "relay3"), listOf(relay.platform, relay.translationKey, relay.family, relay.index.toString(), relay.uniqueSuffix))
        val led = described("button_led2")
        assertEquals(listOf("light", "button_led", "button_led", "2"), listOf(led.platform, led.translationKey, led.family, led.index.toString()))
        assertNull(PanelAssistantChannelCatalog.describe("relay0"))
    }

    @Test fun retiredAutoUpdateSettingsAreAbsentWhileExplicitUpdateDescriptorsRemain() {
        listOf("self_update", "update_channel", "companion_auto_update", "companion_update_channel", "webview_auto_update").forEach { channel ->
            assertNull(channel, PanelAssistantChannelCatalog.describe(channel))
        }
        assertEquals(PanelAssistantValueKind.UPDATE, PanelAssistantChannelCatalog.describe("update_paneld")?.kind)
        assertEquals(PanelAssistantValueKind.UPDATE, PanelAssistantChannelCatalog.describe("update_companion")?.kind)
    }

    @Test fun selectsAndClosedSensorsCarrySnakeCaseOptionCodes() {
        assertEquals(listOf("off", "always_on", "swipe_reveal", "native"), PanelAssistantChannelCatalog.describe("navbar")?.options)
        assertEquals(listOf("performance", "efficiency", "auto"), PanelAssistantChannelCatalog.describe("cpu_governor")?.options)
        assertEquals(listOf("unchecked", "healthy", "warning", "critical", "database_failure"), PanelAssistantChannelCatalog.describe("storage_health")?.options)
        assertEquals(PanelAssistantValueKind.TEXT, PanelAssistantChannelCatalog.describe("diag_wifi_ssid")?.kind)
        assertEquals(PanelAssistantValueKind.NUMBER, PanelAssistantChannelCatalog.describe("diag_wifi_rssi")?.kind)
    }

    @Test fun enabledDefaultFollowsTheExposureDefaultAndWireOnlySettingsStartDisabled() {
        assertEquals(false, PanelAssistantChannelCatalog.describe("diag_cpu")?.enabledDefault)
        assertEquals(false, PanelAssistantChannelCatalog.describe("watchdog")?.enabledDefault)
        assertEquals(true, PanelAssistantChannelCatalog.describe("relay1")?.enabledDefault)
        assertNotNull(PanelAssistantChannelCatalog.describe("illuminance")?.takeIf { it.enabledDefault })
    }

    @Test fun descriptorJsonHasEveryRequiredFieldWithTheIntegrationsGrammar() {
        val code = Regex("^[a-z][a-z0-9_]{0,63}$")
        val suffix = Regex("^[a-z0-9][a-z0-9_]{0,47}$")
        for (key in everyChannel()) {
            val json = described(key).toJson()
            assertTrue(json.toString(), code.matches(json.getString("translation_key")))
            assertTrue(json.toString(), suffix.matches(json.getString("unique_suffix")))
            assertEquals(json.toString(), json.isNull("family"), json.isNull("index"))
            if (json.getString("platform") == "select") assertTrue(json.toString(), !json.isNull("options"))
        }
    }

    /** Asserts before dereferencing, so a missing descriptor fails as an assertion rather than an error. */
    private fun described(wire: String): PanelAssistantChannelDescriptor {
        val descriptor = PanelAssistantChannelCatalog.describe(wire)
        assertNotNull("no descriptor for $wire", descriptor)
        return descriptor!!
    }
}
