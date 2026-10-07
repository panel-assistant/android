package io.panelassistant.android.i18n

import io.panelassistant.android.config.SettingsRegistry
import io.panelassistant.android.config.SettingType
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

        assertEquals(86, SettingsRegistry.SPECS.size)
        assertEquals(228, expected.size)
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

    @Test fun `every declared enum wire value has a display-only label record catalogueContract`() {
        val localPresenceBindings = linkedMapOf(
            "panel" to ("configure.auto_sleep.source_panel" to "This panel’s proximity sensor"),
            "home_assistant" to ("configure.auto_sleep.source_ha" to "Home Assistant Area devices"),
            "touch" to ("configure.auto_sleep.source_touch" to "Touch inactivity"),
        )
        val enums = SettingsRegistry.SPECS.filter { it.type == SettingType.ENUM }
        assertEquals(localPresenceBindings.keys.toList(), enums.single { it.key == "auto_sleep_source" }.options)
        // ui_language shows native language names and auto_sleep_source has its own copy; every other
        // option's label is its SettingSpec record, served with the schema.
        val labelled = enums.filter { it.key != "ui_language" && it.key != "auto_sleep_source" }
            .flatMap { spec -> spec.options.map(spec::optionLabelKey) }
        assertTrue("the labelled option set must not shrink silently", labelled.size >= 26)

        val source = SourceCatalogue.parse(catalogueFile.readText())
        localPresenceBindings.values.forEach { (key, english) ->
            assertEquals(english, checkNotNull(source.strings[key]).text)
        }
        labelled.forEach { key ->
            val record = checkNotNull(source.strings[key]) { "English catalogue is missing $key" }
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
