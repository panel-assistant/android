package io.github.maxlyth.hapaneld.i18n

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
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
        val consumers = quotedProfileKeys(serverSource) + quotedProfileKeys(profilesScript.readText())

        assertFalse("Profiles must retain a finite non-empty catalogue surface", consumers.isEmpty())
        assertEquals(
            "English Profiles records and visible server-browser consumers diverged",
            consumers,
            records,
        )
    }

    @Test fun `closed presentation maps have exact namespaces and parameter metadata`() {
        val source = SourceCatalogue.parse(sourceFile.readText())
        val script = profilesScript.readText()
        val bindings = presentationBindings(objectLiteral(script, "PRESENTATIONS"))
        val issue = bindings.filterValues { it.startsWith("profiles.issue.") }
        val result = bindings.filterValues { it.startsWith("profiles.result.") }
        val parameters = presentationParameters(objectLiteral(script, "PRESENTATION_PARAMS"))
        val expectedParameterizedCodes = bindings.mapNotNullTo(sortedSetOf()) { (code, key) ->
            code.takeIf { checkNotNull(source.strings[key]).placeholders.isNotEmpty() }
        }

        assertTrue("Profiles must expose backend issue presentation codes", issue.isNotEmpty())
        assertTrue("Profiles must expose backend result presentation codes", result.isNotEmpty())
        assertEquals("the closed Profiles issue presentation vocabulary changed", 124, issue.size)
        assertEquals("the closed Profiles result presentation vocabulary changed", 47, result.size)
        assertEquals("the closed Profiles parameterized vocabulary changed", 34, parameters.size)
        assertTrue("one presentation code must not be assigned to issue and result namespaces", issue.keys.intersect(result.keys).isEmpty())
        assertTrue("issue codes must map only to profiles.issue records", issue.values.all { it.startsWith("profiles.issue.") })
        assertTrue("result codes must map only to profiles.result records", result.values.all { it.startsWith("profiles.result.") })
        assertEquals(
            "parameter metadata must name every and only parameterized presentation code",
            expectedParameterizedCodes,
            parameters.keys,
        )

        bindings.forEach { (code, key) ->
            val record = checkNotNull(source.strings[key]) { "$code maps to missing English record $key" }
            val expected = record.placeholders.map { it.removePrefix("{").removeSuffix("}") }.sorted()
            assertEquals("$code parameter contract drifted from $key placeholders", expected, parameters[code].orEmpty().sorted())
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
        Regex("[\\\"'](profiles\\.[a-z0-9._-]+)[\\\"']")
            .findAll(source)
            .mapTo(sortedSetOf()) { it.groupValues[1] }

    private fun presentationBindings(body: String): Map<String, String> =
        Regex("[\\\"']([a-z0-9-]+)[\\\"']\\s*:\\s*[\\\"'](profiles\\.(?:issue|result)\\.[a-z0-9._-]+)[\\\"']")
            .findAll(body)
            .map { it.groupValues[1] to it.groupValues[2] }
            .toList()
            .also { pairs ->
                assertEquals("presentation codes must be unique", pairs.size, pairs.map { it.first }.toSet().size)
                assertEquals("presentation catalogue keys must be unique", pairs.size, pairs.map { it.second }.toSet().size)
            }
            .toMap()

    private fun presentationParameters(body: String): Map<String, List<String>> {
        val pairs = Regex("""["']([a-z0-9-]+)["']\s*:\s*Object\.freeze\(\[([^\]]*)\]\)""")
            .findAll(body)
            .map { match ->
                match.groupValues[1] to Regex("[\\\"']([a-z][a-z0-9_]*)[\\\"']")
                    .findAll(match.groupValues[2])
                    .map { it.groupValues[1] }
                    .toList()
            }
            .toList()
        assertEquals("presentation parameter codes must be unique", pairs.size, pairs.map { it.first }.toSet().size)
        pairs.forEach { (code, names) ->
            assertEquals("$code presentation parameter names must be unique", names.size, names.toSet().size)
        }
        return pairs.toMap()
    }

    private fun objectLiteral(source: String, name: String): String {
        val marker = "var $name = Object.freeze({"
        val start = source.indexOf(marker).also { require(it >= 0) { "missing $name" } } + marker.length
        val end = source.indexOf("\n  });", start).also { require(it >= 0) { "unterminated $name" } }
        return source.substring(start, end)
    }
}
