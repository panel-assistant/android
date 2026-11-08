package io.panelassistant.android

import io.panelassistant.android.device.profile.BundledProfileFixtures
import io.panelassistant.android.persistence.ConfigVault
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class ConfigOwnerChoicesTest {
    @Test fun selectedChoicesReturnThroughTheirOrdinaryGettersAndRepeatSafely() {
        val prefs = fakePreferences(mapOf("ha_url" to "https://ha.example", "device_local_device_uid" to "keep"))
        val config = Config(prefs.instance).apply { attachProfile(BundledProfileFixtures.profile("tpa10")) }
        val button = buttonKey(config)
        val plan = config.planOwnerChoices(
            listOf(
                row("auto_sleep_source_exclusions_v1", """{"origin:https://ha.example":{"Office":["binary_sensor.occupied"]}}"""),
                row("update_ignored", "Panel Assistant\t1.2.3\nCompanion\t4.5.6"),
                row("last_led", "1,120,10,20,30"),
                row("last_led_effect", "pulse"),
                row(button, "123", "int"),
                row("ui_dashboard_layout", "opaque layout"),
                row("allow_apk_upload", "0", "boolean"),
                row("device_local_device_uid", "replace"),
                row("auto_sleep_generation_v1", "999", "long"),
            ),
            samePanel = true,
            profileMatches = true,
        )

        repeat(2) {
            assertEquals(7, config.applyOwnerChoices(plan))
            assertEquals(setOf("binary_sensor.occupied"), config.autoSleepExcludedEntityIds("office"))
            assertEquals(mapOf("Panel Assistant" to "1.2.3", "Companion" to "4.5.6"), config.ignoredUpdates)
            assertEquals("1,120,10,20,30", config.lastLed)
            assertEquals("pulse", config.lastLedEffect)
            assertEquals(123, config.lastButtonBacklight)
            assertEquals("opaque layout", config.prefs.getString("ui_dashboard_layout", ""))
            assertFalse(config.apkUploadAllowed)
            assertEquals("keep", prefs.values["device_local_device_uid"])
            assertFalse(prefs.values.containsKey("auto_sleep_generation_v1"))
        }
        config.setHaConnection("https://another.example", "")
        assertEquals(emptySet<String>(), config.autoSleepExcludedEntityIds("office"))
    }

    @Test fun deviceAndProfileChangesWithholdActuatorsButKeepPortableChoices() {
        for ((samePanel, profileMatches) in listOf(false to true, true to false)) {
            val config = Config(fakePreferences().instance).apply { attachProfile(BundledProfileFixtures.profile("tpa10")) }
            val plan = config.planOwnerChoices(
                listOf(row("last_led", "1,255,0,0,0"), row("last_led_effect", "blink"), row(buttonKey(config), "50", "int"), row("ui_dashboard_layout", "saved")),
                samePanel, profileMatches,
            )
            assertEquals(1, config.applyOwnerChoices(plan))
            assertEquals(3, plan.skipped.size)
            assertEquals("", config.lastLed)
            assertEquals(-1, config.lastButtonBacklight)
            assertEquals("saved", config.prefs.getString("ui_dashboard_layout", ""))
        }
    }

    @Test fun buttonIntentRequiresExactRevisionAndHardwareCapability() {
        val config = Config(fakePreferences().instance).apply { attachProfile(BundledProfileFixtures.profile("tpa10")) }
        val otherRevision = buttonKey(config) + "-old"
        val plan = config.planOwnerChoices(listOf(row(otherRevision, "50", "int")), true, true)
        assertEquals(listOf(otherRevision), plan.skipped)
        assertEquals(0, config.applyOwnerChoices(plan))
        val noLights = Config(fakePreferences().instance).apply { attachProfile(BundledProfileFixtures.profile("nspanel-pro")) }
        val unavailable = noLights.planOwnerChoices(listOf(row("last_led", "1,1,1,1,1"), row(buttonKey(config), "50", "int")), true, true)
        assertEquals(0, noLights.applyOwnerChoices(unavailable))
        assertEquals(-1, noLights.lastButtonBacklight)
    }

    @Test fun archiveUploadEnableNeverWeakensTheReceivingChoice() {
        for (current in listOf(false, true)) {
            val config = Config(fakePreferences(mapOf("allow_apk_upload" to current)).instance)
            assertEquals(0, config.applyOwnerChoices(config.planOwnerChoices(listOf(row("allow_apk_upload", "1", "boolean")), false)))
            assertEquals(current, config.apkUploadAllowed)
            assertEquals(1, config.applyOwnerChoices(config.planOwnerChoices(listOf(row("allow_apk_upload", "0", "boolean")), false)))
            assertFalse(config.apkUploadAllowed)
        }
    }

    @Test fun invalidSelectedPayloadRefusesBeforeAnyChoiceIsWritten() {
        val config = Config(fakePreferences().instance).apply { attachProfile(BundledProfileFixtures.profile("tpa10")) }
        val invalid = listOf(
            row("allow_apk_upload", "false", "boolean"),
            row("allow_apk_upload", "0"),
            row("last_led", "1,256,0,0,0"),
            row("last_led", "1,1,2,3"),
            row("last_led_effect", "unknown"),
            row("last_led_effect", "pulse"),
            row(buttonKey(config), "256", "int"),
            row("update_ignored", "label\tversion\textra"),
            row("update_ignored", "label\tversion\nlabel\tother"),
            row("auto_sleep_source_exclusions_v1", """{"origin:https://ha.example":{"office":["light.lamp"]}}"""),
            row("auto_sleep_source_exclusions_v1", """{"wrong-owner":{"office":["binary_sensor.motion"]}}"""),
            row("ui_dashboard_layout", "x".repeat(256 * 1024 + 1)),
        )
        invalid.forEach { bad ->
            val untouched = if (bad.key == "ui_dashboard_layout") row("update_ignored", "sentinel\t1")
                else row("ui_dashboard_layout", "must not land")
            assertThrows(IllegalArgumentException::class.java) {
                config.planOwnerChoices(listOf(untouched, bad), false, false)
            }
            assertEquals("", config.prefs.getString("ui_dashboard_layout", ""))
            assertTrue(config.apkUploadAllowed)
        }
        assertThrows(IllegalArgumentException::class.java) {
            config.planOwnerChoices(listOf(row("last_led", "0,0,0,0,0"), row("last_led_effect", "blink")), true, true)
        }
    }

    @Test fun duplicateChoiceRefusesAndLegacyColorClearsAnUnrelatedEffect() {
        val config = Config(fakePreferences(mapOf("last_led_effect" to "pulse")).instance)
            .apply { attachProfile(BundledProfileFixtures.profile("tpa10")) }
        assertThrows(IllegalArgumentException::class.java) {
            config.planOwnerChoices(listOf(row("update_ignored", ""), row("update_ignored", "")), true)
        }
        assertEquals(2, config.applyOwnerChoices(config.planOwnerChoices(listOf(row("last_led", "1,50,1,2,3")), true, true)))
        assertEquals("1,50,1,2,3", config.lastLed)
        assertEquals("", config.lastLedEffect)
    }

    @Test fun failedDurableCommitRestoresNothingAndDoesNotPublishCandidateValues() {
        val prefs = fakePreferences(mapOf("ui_dashboard_layout" to "before"), commitSucceeds = false)
        val config = Config(prefs.instance)
        val plan = config.planOwnerChoices(listOf(row("ui_dashboard_layout", "after"), row("allow_apk_upload", "0", "boolean")), true)
        assertEquals(0, config.applyOwnerChoices(plan))
        assertEquals("before", config.prefs.getString("ui_dashboard_layout", ""))
        assertTrue(config.apkUploadAllowed)
        prefs.commitsSucceed.set(true)
        assertEquals(2, config.applyOwnerChoices(plan))
        assertEquals("after", config.prefs.getString("ui_dashboard_layout", ""))
        assertFalse(config.apkUploadAllowed)
    }

    @Test fun chosenPresenceSourcesRoundTripAndRejectMalformedArchives() {
        val source = Config(fakePreferences().instance)
        assertTrue(source.setPresenceHaSources(listOf("binary_sensor.kitchen_zone", "binary_sensor.hall_motion")))
        val stored = source.prefs.getString(PRESENCE_HA_SOURCES_PREF, "")!!
        val target = Config(fakePreferences().instance)
        assertEquals(1, target.applyOwnerChoices(target.planOwnerChoices(listOf(row(PRESENCE_HA_SOURCES_PREF, stored)), false)))
        assertEquals(listOf("binary_sensor.kitchen_zone", "binary_sensor.hall_motion"), target.presenceHaSources())
        for (bad in listOf("""["light.kitchen"]""", """["binary_sensor.a","binary_sensor.a"]""", "not json", """[1]""")) {
            assertThrows(IllegalArgumentException::class.java) {
                target.planOwnerChoices(listOf(row(PRESENCE_HA_SOURCES_PREF, bad)), false)
            }
        }
        assertFalse(target.setPresenceHaSources(listOf("sensor.kitchen")))
        assertTrue(target.setPresenceHaSources(emptyList()))
        assertEquals(emptyList<String>(), target.presenceHaSources())
    }

    private fun row(key: String, value: String, type: String = "string") = ConfigVault.StateRow("config", key, type, value, 1L)

    private fun buttonKey(config: Config): String = requireNotNull(config.profile).let { "last_button_backlight.${it.id}.${it.revision}" }
}
