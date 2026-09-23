package io.github.maxlyth.hapaneld.i18n

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.maxlyth.hapaneld.CoreInstrumentation
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises catalogue parsing on Android's regex engine rather than the desktop JVM implementation. */
@CoreInstrumentation
@RunWith(AndroidJUnit4::class)
class CatalogueAndroidTest {
    @Test fun productionLoaderReadsPackagedTargetsAndCachesEligibleTranslationsOnAndroid() {
        val assets = InstrumentationRegistry.getInstrumentation().targetContext.assets
        val key = "settings.auto_brightness.label"
        val reads = mutableListOf<String>()
        val loader = CatalogueLoader { path -> assets.readText(path).also { reads += path } }
        loader.strings(AppLocale.ENGLISH)

        (AppLocale.RELEASE_LOCALES - AppLocale.ENGLISH).forEach { locale ->
            val record = JSONObject(assets.readText("i18n/$locale.json"))
                .getJSONObject("strings")
                .getJSONObject(key)
            assertEquals("machine-cross-checked", record.getString("state"))
            val expectedText = record.getString("text")
            val localized = loader.strings(locale)
            assertEquals(expectedText, localized.get(key))
            assertEquals(locale, localized.resolve(key).language)

            val cached = loader.strings(locale)
            assertEquals(expectedText, cached.get(key))
            assertEquals(locale, cached.resolve(key).language)
        }
        assertEquals(AppLocale.RELEASE_LOCALES.map { "i18n/$it.json" }, reads)
    }

    @Test fun allBundledReleaseCataloguesParseOnAndroid() {
        val assets = InstrumentationRegistry.getInstrumentation().targetContext.assets
        val source = SourceCatalogue.parse(assets.readText("i18n/en.json"))

        assertTrue(source.strings.isNotEmpty())
        (AppLocale.RELEASE_LOCALES - AppLocale.ENGLISH).forEach { locale ->
            val target = TargetCatalogue.parse(assets.readText("i18n/$locale.json"), source)
            assertEquals(locale, target.locale)
            assertTrue(target.strings.isNotEmpty())
        }
    }

    @Test fun mixedTargetEligibilityFallsBackPerKeyOnAndroid() {
        val source = SourceCatalogue.parse(syntheticEnglish)
        val target = TargetCatalogue.parse(syntheticGerman, source)
        val strings = Strings(source, target)

        assertEquals("Geprüfte Übersetzung.", strings.get("settings.checked.label"))
        assertEquals("de", strings.resolve("settings.checked.label").language)
        assertEquals("Draft English.", strings.get("settings.draft.label"))
        assertEquals("en", strings.resolve("settings.draft.label").language)
        assertEquals("Stale English.", strings.get("settings.stale.label"))
        assertEquals("en", strings.resolve("settings.stale.label").language)
        assertEquals("Missing English.", strings.get("settings.missing.label"))
        assertEquals("en", strings.resolve("settings.missing.label").language)
        assertEquals("en", strings.locale)
        assertEquals(listOf("de", "en"), strings.languages)
    }

    private fun android.content.res.AssetManager.readText(path: String): String =
        open(path).bufferedReader().use { it.readText() }

    private companion object {
        private const val REVISION = "e7c01506e9519d51b57fcf0e2b0b969a1ce44a6e"

        private val syntheticEnglish = """{
          "schema": 1,
          "locale": "en",
          "sourceRevision": "$REVISION",
          "strings": {
            "settings.checked.label": ${sourceRecord("Checked English.")},
            "settings.draft.label": ${sourceRecord("Draft English.")},
            "settings.missing.label": ${sourceRecord("Missing English.")},
            "settings.stale.label": ${sourceRecord("Stale English.")}
          }
        }""".trimIndent()

        private val syntheticGerman = """{
          "schema": 1,
          "locale": "de",
          "sourceRevision": "$REVISION",
          "strings": {
            "settings.checked.label": {
              "text": "Geprüfte Übersetzung.",
              "sourceHash": "${sourceHash("Checked English.")}",
              "state": "machine-cross-checked"
            },
            "settings.draft.label": {
              "text": "Übersetzungsentwurf.",
              "sourceHash": "${sourceHash("Draft English.")}",
              "state": "machine-draft"
            },
            "settings.stale.label": {
              "text": "Veraltete Übersetzung.",
              "sourceHash": "${"0".repeat(64)}",
              "state": "machine-cross-checked"
            }
          }
        }""".trimIndent()

        private fun sourceRecord(text: String): String = """{
          "text": "$text",
          "sourceHash": "${sourceHash(text)}",
          "surface": "settings",
          "context": "Android catalogue acceptance fixture.",
          "risk": "ordinary",
          "siblings": [],
          "placeholders": [],
          "frozen": [],
          "softMaxChars": 40,
          "hardMaxChars": 80
        }""".trimIndent()
    }
}
