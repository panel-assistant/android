package io.panelassistant.android.http

import io.panelassistant.android.i18n.AppLocale
import org.junit.Assert.assertEquals
import org.junit.Test

class UnfinishedSetupLocationTest {
    private fun location(lang: String? = null, haLang: String? = null, allowPseudo: Boolean = false) =
        unfinishedSetupLocation(lang = lang, haLang = haLang, allowPseudo = allowPseudo)

    @Test fun `a Russian Home Assistant user carries as the Ukrainian automatic signal`() {
        assertEquals("/setup?ha_lang=uk", location(haLang = "ru"))
        assertEquals("/setup?ha_lang=uk", location(haLang = "ru-RU"))
        assertEquals("/setup?ha_lang=uk", location(haLang = "ru_UA"))
    }

    @Test fun `a supported Home Assistant language carries canonically`() {
        assertEquals("/setup?ha_lang=uk", location(haLang = "uk"))
        assertEquals("/setup?ha_lang=uk", location(haLang = "uk-UA"))
        assertEquals("/setup?ha_lang=zh-Hans", location(haLang = "zh-CN"))
    }

    @Test fun `unsupported and malformed tags are never reflected`() {
        assertEquals("/setup", location(haLang = "sv-SE"))
        assertEquals("/setup", location(haLang = "be-RU"))
        assertEquals("/setup", location(haLang = "ru\r\nSet-Cookie: x=1"))
        assertEquals("/setup", location(haLang = "ru&lang=de"))
        assertEquals("/setup", location(haLang = "x".repeat(64)))
        assertEquals("/setup", location(lang = "", haLang = "  "))
    }

    @Test fun `explicit lang stays explicit and ahead of the automatic Russian signal`() {
        assertEquals("/setup?lang=en&ha_lang=uk", location(lang = "en", haLang = "ru"))
        // The redirect never promotes an automatic signal to explicit, and never admits explicit Russian.
        assertEquals("/setup?ha_lang=uk", location(lang = "ru", haLang = "ru"))
        assertEquals(
            "en",
            AppLocale.resolve(
                explicit = "en", persisted = "auto", haUser = "uk",
                acceptLanguage = null, deviceLanguageTag = null, allowPseudo = false,
            ),
        )
    }

    @Test fun `pseudo locale handling is unchanged`() {
        assertEquals("/setup?lang=en-XA", location(lang = "en-XA", allowPseudo = true))
        assertEquals("/setup?lang=en", location(lang = "en-XA", allowPseudo = false))
        assertEquals("/setup?ha_lang=en", location(haLang = "en-XA", allowPseudo = true))
    }

    @Test fun `the reflected signal resolves exactly as the original signal would`() {
        val tags = listOf(
            null, "ru", "ru-RU", "ru_UA", "uk", "uk-UA", "de", "de-AT", "zh-CN", "zh-Hant",
            "sv-SE", "be-RU", "en-XA", "ru\nx", "x".repeat(64),
        )
        tags.forEach { tag ->
            listOf("auto", "de").forEach { persisted ->
                listOf(null, "fr").forEach { explicit ->
                    val reflected = location(lang = explicit, haLang = tag)
                        .substringAfter("ha_lang=", missingDelimiterValue = "")
                        .takeIf { it.isNotEmpty() }
                    fun resolve(haUser: String?) = AppLocale.resolve(
                        explicit = explicit, persisted = persisted, haUser = haUser,
                        acceptLanguage = "it", deviceLanguageTag = "es", allowPseudo = false,
                    )
                    assertEquals("ha_lang=$tag persisted=$persisted lang=$explicit", resolve(tag), resolve(reflected))
                }
            }
        }
    }
}
