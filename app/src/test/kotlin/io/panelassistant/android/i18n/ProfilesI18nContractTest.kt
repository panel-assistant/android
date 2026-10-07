package io.panelassistant.android.i18n

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import io.panelassistant.android.device.profile.ProfilePresentation
import org.json.JSONArray

/**
 * Exact release exceptions approved after semantic review. A fallback is never admitted by state
 * alone: its locale and key must appear here, and the catalogue parser requires its text to equal
 * the authoritative English source.
 */
internal val APPROVED_PROFILES_ENGLISH_FALLBACKS: Set<Pair<String, String>> = setOf(
    "de" to "profiles.action.rollback",
    "fr" to "profiles.error.http",
    "fr" to "profiles.section.validation",
    "it" to "profiles.error.http",
    "it" to "profiles.report.hardware",
)

/** Exact catalogue boundary for the Profiles HTML authoring surface. */
class ProfilesI18nContractTest {
    private val assets = File("src/main/assets")
    private val sourceFile = File(assets, "i18n/en.json")
    private val profilesScript = File(assets, "profiles.js")
    // Source-text reason: whole-file scans of HTTP Kotlin sources and profiles.js for literal profiles.* catalogue keys and their bound English fallbacks (translation catalogue contract).
    private val serverSource = httpCatalogueSources()
    private val releaseTargetLocales = AppLocale.RELEASE_LOCALES.filterNot { it == AppLocale.ENGLISH }

    @Test fun `English Profiles records are exactly the server and browser consumer union`() {
        val source = SourceCatalogue.parse(sourceFile.readText())
        val records = source.strings.keys.filterTo(sortedSetOf()) { it.startsWith("profiles.") }
        val consumers = quotedProfileKeys(serverSource) + quotedProfileKeys(profilesScript.readText()) +
            records.filter { presentationCode(it) in ProfilePresentation.SUPPORTED_CODES }

        assertFalse("Profiles must retain a finite non-empty catalogue surface", consumers.isEmpty())
        assertEquals(
            "English Profiles records and visible server-browser consumers diverged",
            consumers,
            records,
        )
    }

    @Test fun `each presentation code names one record whose placeholders are its parameters`() {
        val source = SourceCatalogue.parse(sourceFile.readText())
        // Backup restore presentation belongs to the integration API, and the passive-draft TODO note is
        // profile-authored YAML guidance; neither is browser prose, so neither has a Profiles record.
        val notBrowserProse = { code: String -> code.startsWith("backup-") ||
            code == "profile-catalog-restore-unavailable" || code == "draft-todos-recorded-as-limitations" }
        ProfilePresentation.SUPPORTED_CODES.forEach { code ->
            val records = listOf("profiles.issue.$code", "profiles.result.$code").mapNotNull { source.strings[it] }
            assertEquals("$code must name ${if (notBrowserProse(code)) "no" else "one"} record", if (notBrowserProse(code)) 0 else 1, records.size)
            records.forEach { record ->
                assertEquals(
                    "$code placeholders drifted from its parameter contract",
                    ProfilePresentation.expectedParams(code),
                    record.placeholders.mapTo(sortedSetOf()) { it.removePrefix("{").removeSuffix("}") },
                )
            }
        }
    }

    @Test fun `literal browser fallbacks equal their authoritative English records`() {
        val source = SourceCatalogue.parse(sourceFile.readText())
        val bindings = Regex("""\bt\(\s*(\"(?:\\.|[^\"\\])*\")\s*,\s*(\"(?:\\.|[^\"\\])*\")""")
            .findAll(profilesScript.readText())
            .map { match -> JSONArray("[${match.groupValues[1]},${match.groupValues[2]}]") }
            .map { values -> values.getString(0) to values.getString(1) }
            .filter { (key, _) -> key.startsWith("profiles.") }
            .toList()

        assertTrue("Profiles browser must expose literal English-safe fallback bindings", bindings.isNotEmpty())
        bindings.forEach { (key, fallback) ->
            assertEquals(
                "$key browser fallback drifted from the authoritative English record",
                checkNotNull(source.strings[key]) { "browser consumes missing English record $key" }.text,
                fallback,
            )
        }
    }

    @Test fun `every target carries a current promoted Profiles translation or exact approved fallback`() {
        val source = SourceCatalogue.parse(sourceFile.readText())
        val profiles = source.strings.filterKeys { it.startsWith("profiles.") }
        val observedFallbacks = mutableSetOf<Pair<String, String>>()

        APPROVED_PROFILES_ENGLISH_FALLBACKS.forEach { (locale, key) ->
            assertTrue("approved Profiles fallback names an unsupported locale: $locale", locale in releaseTargetLocales)
            assertTrue("approved Profiles fallback names a key outside the Profiles slice: $key", key in profiles)
        }

        releaseTargetLocales.forEach { locale ->
            val target = TargetCatalogue.parse(File(assets, "i18n/$locale.json").readText(), source)
            assertEquals("$locale Profiles key set must be exact", profiles.keys, target.strings.keys.filterTo(sortedSetOf()) { it.startsWith("profiles.") })
            profiles.forEach { (key, english) ->
                val translated = checkNotNull(target.strings[key]) { "$locale is missing $key" }
                assertEquals("$locale has stale source text for $key", english.sourceHash, translated.sourceHash)
                val fallback = locale to key
                if (translated.state == TranslationState.ENGLISH_FALLBACK) {
                    if (!EarlyAccessReviewHold.holds(locale, key, translated)) observedFallbacks += fallback
                    assertEquals("$locale English fallback must equal the authoritative source for $key", english.text, translated.text)
                }
                assertTrue(
                    "$locale $key must be reviewed or named as an exact approved English fallback",
                    translated.state == TranslationState.MACHINE_CROSS_CHECKED ||
                        translated.state == TranslationState.COMMUNITY_CORRECTED ||
                        (translated.state == TranslationState.ENGLISH_FALLBACK &&
                            fallback in APPROVED_PROFILES_ENGLISH_FALLBACKS) ||
                        (locale in AppLocale.EARLY_ACCESS_LOCALES &&
                            translated.state == TranslationState.MACHINE_DRAFT) ||
                        EarlyAccessReviewHold.holds(locale, key, translated),
                )
            }
        }

        assertEquals(
            "approved Profiles English fallback policy contains a stale or unexercised exception",
            APPROVED_PROFILES_ENGLISH_FALLBACKS,
            observedFallbacks,
        )
    }

    private fun quotedProfileKeys(source: String): Set<String> =
        Regex("[\\\"'](profiles\\.[a-z0-9._-]*[a-z0-9_-])[\\\"']")
            .findAll(source)
            .mapTo(sortedSetOf()) { it.groupValues[1] }

    private fun presentationCode(key: String): String? =
        key.removePrefix("profiles.issue.").removePrefix("profiles.result.").takeIf { it != key }
}
