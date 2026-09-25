package io.github.maxlyth.hapaneld.i18n

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProximityLearningI18nContractTest {
    // Source-text reason: loads the shipped i18n catalogues as input data, and the setup label keys proximity-learning.js binds, a catalogue key contract.
    private val assets = File("src/main/assets")
    private val script = File(assets, "proximity-learning.js").readText()
    private val source = SourceCatalogue.parse(File(assets, "i18n/en.json").readText())
    private val keys = source.strings.keys.filterTo(sortedSetOf()) { it.startsWith(PREFIX) && !it.startsWith("$PREFIX.setup.") }

    @Test fun `finite proximity learning catalogue is complete and promoted in every release locale`() {
        assertEquals("the reviewed proximity-learning vocabulary changed", 63, keys.size)
        AppLocale.RELEASE_LOCALES.filterNot { it == AppLocale.ENGLISH }.forEach { locale ->
            val target = TargetCatalogue.parse(File(assets, "i18n/$locale.json").readText(), source)
            assertEquals(
                "$locale proximity-learning keys must exactly match English",
                keys,
                target.strings.keys.filterTo(sortedSetOf()) { it.startsWith(PREFIX) && !it.startsWith("$PREFIX.setup.") },
            )
            keys.forEach { key ->
                val english = source.strings.getValue(key)
                val translated = target.strings.getValue(key)
                assertEquals("$locale $key source hash drifted", english.sourceHash, translated.sourceHash)
                assertTrue(
                    "$locale $key must be promoted beyond a draft",
                    translated.state == TranslationState.MACHINE_CROSS_CHECKED ||
                        translated.state == TranslationState.COMMUNITY_CORRECTED ||
                        (locale in AppLocale.EARLY_ACCESS_LOCALES &&
                            translated.state == TranslationState.MACHINE_DRAFT) ||
                        EarlyAccessReviewHold.holds(locale, key, translated),
                )
            }
            val permittedSourceIdentical = when (locale) {
                "es" -> setOf("$PREFIX.detail.with_health", "$PREFIX.experimental")
                else -> setOf("$PREFIX.detail.with_health")
            }
            assertEquals(
                "$locale has an unreviewed source-identical proximity target",
                permittedSourceIdentical,
                keys.filterTo(sortedSetOf()) { key ->
                    val translated = target.strings.getValue(key)
                    translated.text == source.strings.getValue(key).text &&
                        !EarlyAccessReviewHold.holds(locale, key, translated)
                },
            )
        }
    }

    @Test fun `on-panel setup vocabulary is registered with truthful English fallback catalogueContract`() {
        val setupKeys = source.strings.keys.filterTo(sortedSetOf()) { it.startsWith("$PREFIX.setup.") }
        val boundKeys = Regex("label\\(\"([a-z_.]+)\", \"([^\"]*)\"")
            .findAll(script).mapTo(sortedSetOf()) { "$PREFIX.setup.${it.groupValues[1]}" }
        listOf("phases" to "", "stages" to "stage.").forEach { (map, prefix) ->
            Regex("\\[\"([a-z_]+)\", \"([^\"]*)\"\\]")
                .findAll(script.substringAfter("var $map = {").substringBefore("};"))
                .forEach { boundKeys.add("$PREFIX.setup.$prefix${it.groupValues[1]}") }
        }
        assertEquals("setup labels and canonical English keys diverged", setupKeys, boundKeys)
        assertEquals("Set up proximity on panel", source.strings.getValue("$PREFIX.setup.start").text)
        assertTrue(source.strings.getValue("$PREFIX.setup.follow").text.contains("instructions on the panel"))
        assertTrue(source.strings.getValue("$PREFIX.setup.binary").text.contains("distance cannot be adjusted"))
    }

    private companion object {
        const val PREFIX = "configure.proximity"
    }
}
