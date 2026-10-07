package io.panelassistant.android.i18n

import io.panelassistant.android.testsupport.TestSources
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The one release-readiness rule for a translated record: reviewed (cross-checked or community-corrected),
 * a machine draft in an early-access locale, an early-access review hold, or a named approved English
 * fallback. Every i18n contract asks this function instead of restating the rule.
 */
internal fun releaseReady(locale: String, key: String, translated: TargetString): Boolean = when (translated.state) {
    TranslationState.MACHINE_CROSS_CHECKED, TranslationState.COMMUNITY_CORRECTED -> true
    TranslationState.MACHINE_DRAFT -> locale in AppLocale.EARLY_ACCESS_LOCALES
    TranslationState.ENGLISH_FALLBACK ->
        (locale to key) in APPROVED_PROFILES_ENGLISH_FALLBACKS || EarlyAccessReviewHold.holds(locale, key, translated)
}

class ReleaseReadinessContractTest {
    // Source-text reason: loads the shipped i18n catalogues as input data.
    private val sourceJson = TestSources.asset("i18n/en.json").readText()
    private val source = SourceCatalogue.parse(sourceJson)
    private val surfaces = JSONObject(sourceJson).getJSONObject("strings").let { records ->
        source.strings.keys.associateWith { records.getJSONObject(it).getString("surface") }
    }
    private val targets = AppLocale.RELEASE_LOCALES.filterNot { it == AppLocale.ENGLISH }
    private fun shipped(locale: String) = TestSources.asset("i18n/$locale.json").readText()

    /** Each promoted slice of the catalogue, by the surface or key family that owns it. */
    private val slices: Map<String, (String) -> Boolean> = mapOf(
        "HTML UI" to { key -> surfaces[key] in setOf("shell", "dashboard", "configure", "profiles", "entities", "install", "api") },
        "Setup" to { key -> key.startsWith("setup.") },
        "Settings" to { key -> key.startsWith("settings.") || key.startsWith("configure.enum.") },
        "Logs and Fleet" to { key -> surfaces[key] in setOf("logs", "fleet") },
        "OAuth callback" to { key -> key.startsWith("oauth.callback.") },
        "Proximity learning" to { key -> key.startsWith("configure.proximity") },
        "Runtime" to { key -> key.startsWith("runtime.") },
    )

    private fun failures(load: (String) -> String): List<String> = targets.flatMap { locale ->
        val target = TargetCatalogue.parse(load(locale), source)
        slices.flatMap { (slice, owns) ->
            source.strings.filterKeys(owns).mapNotNull { (key, english) ->
                val translated = target.strings[key]
                when {
                    translated == null -> "$slice: $locale is missing $key"
                    translated.sourceHash != english.sourceHash -> "$slice: $locale has stale source text for $key"
                    !releaseReady(locale, key, translated) -> "$slice: $locale $key is ${translated.state} and not release-ready"
                    else -> null
                }
            }
        }
    }

    @Test fun `every promoted slice is current and release-ready in every release locale`() {
        slices.forEach { (slice, owns) -> assertTrue("$slice owns no keys", source.strings.keys.any(owns)) }
        assertEquals(emptyList<String>(), failures(::shipped))
    }

    @Test fun `a Setup key set back to machine draft outside early access fails the rule`() {
        val locale = targets.first { it !in AppLocale.EARLY_ACCESS_LOCALES }
        val json = JSONObject(shipped(locale))
        val records = json.getJSONObject("strings")
        val key = source.strings.keys.first { it.startsWith("setup.") && records.getJSONObject(it).getString("state") != "english-fallback" }
        records.getJSONObject(key).put("state", "machine-draft")

        val found = failures { if (it == locale) json.toString() else shipped(it) }

        assertEquals(listOf("Setup: $locale $key is MACHINE_DRAFT and not release-ready"), found)
    }
}
