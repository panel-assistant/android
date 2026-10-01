package io.github.maxlyth.hapaneld.i18n

import io.github.maxlyth.hapaneld.config.SettingsRegistry
import io.github.maxlyth.hapaneld.config.SettingType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SettingsCatalogueContractTest {
    // Source-text reason: loads the shipped i18n catalogues as input data.
    private val catalogueFile = File("src/main/assets/i18n/en.json")
    private val releaseTargetLocales = AppLocale.RELEASE_LOCALES.filterNot { it == AppLocale.ENGLISH }

    @Test fun `authoritative English catalogue exactly covers visible Settings copy`() {
        val catalogue = SourceCatalogue.parse(catalogueFile.readText())
        val expected = linkedMapOf<String, String>()
        SettingsRegistry.SPECS.forEach { spec ->
            expected[spec.labelKey] = spec.label
            if (spec.help.isNotEmpty()) expected[spec.helpKey] = spec.help
            if (spec.summary.isNotEmpty()) expected[spec.summaryKey] = spec.summary
            if (spec.promotedHelp.isNotEmpty()) expected[spec.promotedHelpKey] = spec.promotedHelp
        }

        assertEquals(87, SettingsRegistry.SPECS.size)
        assertEquals(231, expected.size)
        val settings = catalogue.strings.filterKeys { it.startsWith("settings.") }
        assertEquals("Settings must remain an exact independently-owned subset", expected.keys, settings.keys)
        expected.forEach { (key, text) ->
            val record = checkNotNull(settings[key])
            assertEquals("English drift for $key", text, record.text)
            assertEquals("source hash drift for $key", sourceHash(text), record.sourceHash)
        }
    }

    @Test fun `setting-derived catalogue keys are unique and durable`() {
        val keys = SettingsRegistry.SPECS.flatMap { spec ->
            listOf(spec.labelKey) + (if (spec.help.isEmpty()) emptyList() else listOf(spec.helpKey)) +
                (if (spec.summary.isEmpty()) emptyList() else listOf(spec.summaryKey)) +
                if (spec.promotedHelp.isEmpty()) emptyList() else listOf(spec.promotedHelpKey)
        }
        assertEquals(keys.size, keys.toSet().size)
        assertTrue(keys.all { it.matches(Regex("settings\\.[a-z0-9_]+\\.(label|help|summary|promoted_help)")) })
    }

    @Test fun `every release target has a current reviewed Settings translation`() {
        val source = SourceCatalogue.parse(catalogueFile.readText())
        val settings = source.strings.filterKeys { it.startsWith("settings.") }

        releaseTargetLocales.forEach { locale ->
            val target = TargetCatalogue.parse(File("src/main/assets/i18n/$locale.json").readText(), source)
            assertEquals(
                "$locale Settings key set must exactly match English",
                settings.keys,
                target.strings.keys.filterTo(sortedSetOf()) { it.startsWith("settings.") },
            )
            settings.forEach { (key, english) ->
                val translated = checkNotNull(target.strings[key]) { "$locale is missing $key" }
                assertEquals("$locale has stale source text for $key", english.sourceHash, translated.sourceHash)
                assertTrue(
                    "$locale $key must be reviewed before it can replace the English fallback",
                    translated.state == TranslationState.MACHINE_CROSS_CHECKED ||
                        translated.state == TranslationState.COMMUNITY_CORRECTED ||
                        (locale in AppLocale.EARLY_ACCESS_LOCALES &&
                            translated.state == TranslationState.MACHINE_DRAFT) ||
                        EarlyAccessReviewHold.holds(locale, key, translated),
                )
            }
        }
    }

    @Test fun `every declared enum wire value has a finite display-only localization binding catalogueContract`() {
        val expected = linkedMapOf(
            "mqtt_address_family" to linkedMapOf(
                "Automatic" to ("configure.enum.mqtt_address_family.automatic" to "Automatic"),
                "Prefer IPv4" to ("configure.enum.mqtt_address_family.prefer_ipv4" to "Prefer IPv4"),
                "Force IPv4" to ("configure.enum.mqtt_address_family.force_ipv4" to "Force IPv4"),
            ),
            "navbar_mode" to linkedMapOf(
                "Off" to ("configure.enum.navbar_mode.off" to "Off"),
                "Always on" to ("configure.enum.navbar_mode.always_on" to "Always on"),
                "Swipe reveal" to ("configure.enum.navbar_mode.swipe_reveal" to "Swipe reveal"),
                "Native" to ("configure.enum.navbar_mode.native" to "Native"),
            ),
            "cpu_governor" to linkedMapOf(
                "Performance" to ("configure.enum.cpu_governor.performance" to "Performance"),
                "Efficiency" to ("configure.enum.cpu_governor.efficiency" to "Efficiency"),
                "Auto" to ("configure.enum.cpu_governor.auto" to "Auto"),
            ),
            "camera_resolution" to linkedMapOf(
                "480p" to ("configure.enum.camera_resolution.480p" to "480p (SD)"),
                "720p" to ("configure.enum.camera_resolution.720p" to "720p (HD)"),
                "1080p" to ("configure.enum.camera_resolution.1080p" to "1080p (Full HD)"),
            ),
            "dashboard_theme" to linkedMapOf(
                "Follow Home Assistant" to ("configure.enum.dashboard_theme.follow_home_assistant" to "Follow Home Assistant"),
                "Dark" to ("configure.enum.dashboard_theme.dark" to "Dark"),
                "Light" to ("configure.enum.dashboard_theme.light" to "Light"),
                "Ambient" to ("configure.enum.dashboard_theme.ambient" to "Ambient"),
            ),
            "voice_audio_source" to linkedMapOf(
                "voice_recognition" to ("configure.enum.voice_audio_source.voice_recognition" to "Voice recognition"),
                "mic" to ("configure.enum.voice_audio_source.mic" to "Microphone"),
                "voice_communication" to ("configure.enum.voice_audio_source.voice_communication" to "Voice communication"),
            ),
            "voice_sensitivity" to linkedMapOf(
                "low" to ("configure.enum.voice_sensitivity.low" to "Low"),
                "normal" to ("configure.enum.voice_sensitivity.normal" to "Normal"),
                "high" to ("configure.enum.voice_sensitivity.high" to "High"),
            ),
            "log_ship_protocol" to linkedMapOf(
                "syslog-udp" to ("configure.enum.log_ship_protocol.syslog_udp" to "Syslog over UDP"),
                "syslog-tcp" to ("configure.enum.log_ship_protocol.syslog_tcp" to "Syslog over TCP"),
                "http" to ("configure.enum.log_ship_protocol.http" to "HTTP protocol"),
            ),
        )
        val localPresenceBindings = linkedMapOf(
            "panel" to ("configure.auto_sleep.source_panel" to "This panel’s proximity sensor"),
            "home_assistant" to ("configure.auto_sleep.source_ha" to "Home Assistant Area devices"),
            "touch" to ("configure.auto_sleep.source_touch" to "Touch inactivity"),
        )
        val declared = SettingsRegistry.SPECS.filter { it.type == SettingType.ENUM }.associate { it.key to it.options }
        assertEquals(expected.keys + setOf("ui_language", "auto_sleep_source"), declared.keys)
        assertEquals(localPresenceBindings.keys.toList(), declared["auto_sleep_source"])
        expected.forEach { (setting, bindings) ->
            assertEquals("$setting option domain changed without a localization decision", bindings.keys.toList(), declared[setting])
        }

        val source = SourceCatalogue.parse(catalogueFile.readText())
        localPresenceBindings.values.forEach { (key, english) ->
            assertEquals(english, checkNotNull(source.strings[key]).text)
        }
        val uniqueBindings = expected.values.flatMap { it.values }.toSet()
        assertEquals(26, uniqueBindings.size)
        uniqueBindings.forEach { (key, english) ->
            val record = checkNotNull(source.strings[key]) { "English catalogue is missing $key" }
            assertEquals(english, record.text)
            assertEquals(sourceHash(english), record.sourceHash)
            releaseTargetLocales.forEach { locale ->
                val target = TargetCatalogue.parse(File("src/main/assets/i18n/$locale.json").readText(), source)
                val translated = checkNotNull(target.strings[key]) { "$locale is missing $key" }
                assertEquals(record.sourceHash, translated.sourceHash)
                assertTrue(
                    "$locale $key must be current and reviewed",
                    translated.state == TranslationState.MACHINE_CROSS_CHECKED ||
                        translated.state == TranslationState.COMMUNITY_CORRECTED ||
                        (locale in AppLocale.EARLY_ACCESS_LOCALES &&
                            translated.state == TranslationState.MACHINE_DRAFT),
                )
            }
        }
    }
}
