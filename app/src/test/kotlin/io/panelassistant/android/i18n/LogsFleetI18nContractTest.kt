package io.panelassistant.android.i18n

import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LogsFleetI18nContractTest {
    private val assets = File("src/main/assets")
    // Source-text reason: whole-file scan for literal logs./fleet. catalogue keys (translation catalogue contract).
    private val serverSource = httpCatalogueSources()
    private val sourceRecords = JSONObject(File(assets, "i18n/en.json").readText()).getJSONObject("strings")
    private val sourceCatalogue = SourceCatalogue.parse(File(assets, "i18n/en.json").readText())
    private val releaseTargetLocales = AppLocale.RELEASE_LOCALES.filterNot { it == AppLocale.ENGLISH }

    private val expectedKeys = mapOf(
        "logs" to setOf(
            "logs.action.clear",
            "logs.action.follow",
            "logs.action.pause",
            "logs.action.resume",
            "logs.filter.placeholder",
            "logs.level.debug",
            "logs.level.error",
            "logs.level.info",
            "logs.level.minimum",
            "logs.level.verbose",
            "logs.level.warning",
            "logs.note.privacy",
            "logs.note.raw_stream",
            "logs.note.sources",
            "logs.source.app",
            "logs.source.system",
            "logs.source.system_root_check",
            "logs.source.webview",
            "logs.source.webview_hint",
            "logs.state.app_live",
            "logs.state.app_paused",
            "logs.state.connecting",
            "logs.state.hidden",
            "logs.state.reconnecting",
            "logs.state.system_live",
            "logs.state.system_paused",
            "logs.state.webview_live",
            "logs.state.webview_paused",
            "logs.title",
        ),
    )

    @Test fun `Logs and Fleet catalogue contract matches the literal consumer keys`() {
        expectedKeys.forEach { (surface, expected) ->
            val browserScript = if (surface == "logs") File(assets, "logs.js").readText() else ""
            val consumed = literalKeys(serverSource, surface, "strings\\.get") +
                literalKeys(browserScript, surface, "i18nText")
            assertEquals("$surface must consume its complete bounded catalogue slice", expected, consumed)
            expected.forEach { key ->
                val entry = sourceCatalogue.strings[key]
                assertTrue("English catalogue is missing $key", entry != null)
                assertEquals("$key is assigned to the wrong surface", surface, sourceRecords.getJSONObject(key).getString("surface"))
            }
        }
    }

    @Test fun `Logs and Fleet catalogue slices are current and promoted in every release locale`() {
        val allExpected = expectedKeys.values.flatten().toSet()

        releaseTargetLocales.forEach { locale ->
            val target = TargetCatalogue.parse(File(assets, "i18n/$locale.json").readText(), sourceCatalogue)
            allExpected.forEach { key ->
                val english = checkNotNull(sourceCatalogue.strings[key]) { "English catalogue is missing $key" }
                val translated = checkNotNull(target.strings[key]) { "$locale is missing $key" }
                assertEquals("$locale has stale source text for $key", english.sourceHash, translated.sourceHash)
                assertTrue(
                    "$locale must promote $key beyond draft before release",
                    translated.state == TranslationState.MACHINE_CROSS_CHECKED ||
                        translated.state == TranslationState.COMMUNITY_CORRECTED ||
                        (locale in AppLocale.EARLY_ACCESS_LOCALES &&
                            translated.state == TranslationState.MACHINE_DRAFT) ||
                        EarlyAccessReviewHold.holds(locale, key, translated),
                )
            }
        }
    }

    @Test fun `Logs and Fleet language accounting exposes shared per-key English fallback`() {
        val targetJson = JSONObject(File(assets, "i18n/de.json").readText())
        targetJson.getJSONObject("strings")
            .getJSONObject("configure.hardened.action_approval")
            .put("state", "machine-draft")
        val target = TargetCatalogue.parse(targetJson.toString(), sourceCatalogue)
        val strings = Strings(source = sourceCatalogue, target = target)

        listOf("logs").forEach { surface ->
            assertEquals(
                "shared hidden fallback must be represented for /$surface",
                listOf("de", "en"),
                strings.languages(setOf("shell.", "configure.hardened.", "$surface.")),
            )
        }
    }

    private fun literalKeys(source: String, prefix: String, function: String): Set<String> =
        Regex("$function\\(\\s*[\\\"'](($prefix)\\.[a-z0-9._-]+)[\\\"']")
            .findAll(source)
            .mapTo(sortedSetOf()) { it.groupValues[1] }
}
