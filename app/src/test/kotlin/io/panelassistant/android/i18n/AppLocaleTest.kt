package io.panelassistant.android.i18n

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppLocaleTest {
    @Test fun `Czech and Brazilian Portuguese retain canonical tags through every production signal`() {
        for ((signal, expected) in listOf("cs-CZ" to "cs", "PT_br" to "pt-BR")) {
            assertEquals(expected, AppLocale.canonical(signal))
            assertEquals(expected, AppLocale.resolve(signal, "de", "fr", "it", "es", false))
            assertEquals(expected, AppLocale.resolve(null, signal, "fr", "it", "es", false))
            assertEquals(expected, AppLocale.resolve(null, "auto", signal, "it", "es", false))
            assertEquals(expected, AppLocale.resolve(null, "auto", null, "$signal;q=.9, de;q=.8", "es", false))
            assertEquals(expected, AppLocale.resolve(null, "auto", null, null, signal, false))
            assertEquals("en", AppLocale.resolve("en", signal, signal, signal, signal, false))
        }
    }

    @Test fun `Brazilian Portuguese never consumes generic or European Portuguese signals`() {
        for (unsupported in listOf("pt", "pt-PT", "pt_PT", "pt-AO")) {
            assertNull(AppLocale.canonical(unsupported))
            assertEquals("de", AppLocale.resolve(unsupported, null, unsupported, "$unsupported, de;q=.8", "pt-BR", false))
            assertEquals("en", AppLocale.resolve(null, "auto", null, null, unsupported, false))
        }
        assertEquals("pt-BR", AppLocale.canonical("pt-BR-x-test"))
    }

    @Test fun `every release locale is uniquely canonical and accepts a more specific tag`() {
        assertEquals(AppLocale.RELEASE_LOCALES.size, AppLocale.RELEASE_LOCALES.toSet().size)
        AppLocale.RELEASE_LOCALES.forEach { locale ->
            assertEquals(locale, AppLocale.canonical(locale))
            assertEquals(locale, AppLocale.canonical("$locale-Test"))
        }
        assertTrue(
            AppLocale.RELEASE_LOCALES.all {
                it.matches(Regex("[a-z]{2,3}(?:-(?:[A-Z][a-z]{3}|[A-Z]{2}|[0-9]{3}))*"))
            },
        )
        assertNull("a different Chinese script must fail closed", AppLocale.canonical("zh-Hant"))
    }

    @Test fun `explicit locale wins and regional tags use RFC lookup`() {
        assertEquals(
            "fr",
            AppLocale.resolve(
                explicit = "fr-CA", persisted = "it", haUser = "es",
                acceptLanguage = "de-DE", deviceLanguageTag = "it-IT", allowPseudo = false,
            ),
        )
        assertEquals("zh-Hans", AppLocale.resolve("zh-CN", acceptLanguage = null, deviceLanguageTag = null, allowPseudo = false))
        assertEquals("zh-Hans", AppLocale.resolve(null, acceptLanguage = "zh", deviceLanguageTag = null, allowPseudo = false))
    }

    @Test fun `panel setting and HA user precede inherited browser and device signals`() {
        assertEquals(
            "it",
            AppLocale.resolve(
                explicit = null, persisted = "it", haUser = "es",
                acceptLanguage = "de", deviceLanguageTag = "fr", allowPseudo = false,
            ),
        )
        assertEquals(
            "es",
            AppLocale.resolve(
                explicit = null, persisted = "auto", haUser = "es-MX",
                acceptLanguage = "de", deviceLanguageTag = "fr", allowPseudo = false,
            ),
        )
        assertEquals(
            "de",
            AppLocale.resolve(
                explicit = null, persisted = "unsupported", haUser = "unsupported",
                acceptLanguage = "de", deviceLanguageTag = "fr", allowPseudo = false,
            ),
        )
    }

    @Test fun `accept language honors quality and falls through unsupported languages`() {
        assertEquals(
            "de",
            AppLocale.resolve(null, acceptLanguage = "sv-SE, de-DE;q=0.8, fr;q=0.7", deviceLanguageTag = "it-IT", allowPseudo = false),
        )
        assertEquals("es", AppLocale.resolve(null, acceptLanguage = "ar;q=1, es-MX;q=.9", deviceLanguageTag = "de", allowPseudo = false))
    }

    @Test fun `device locale and English are final fallbacks`() {
        assertEquals("it", AppLocale.resolve(null, acceptLanguage = null, deviceLanguageTag = "it-CH", allowPseudo = false))
        assertEquals("en", AppLocale.resolve(null, acceptLanguage = "ar", deviceLanguageTag = "ja-JP", allowPseudo = false))
    }

    @Test fun `Russian automatic signals select Ukrainian now that Ukrainian is a release locale`() {
        assertTrue(AppLocale.UKRAINIAN in AppLocale.RELEASE_LOCALES)
        assertEquals("uk", AppLocale.automaticLocaleOverride("ru-RU", AppLocale.RELEASE_LOCALES))
        assertEquals(
            "de",
            AppLocale.resolve(
                explicit = null, persisted = "de-DE", haUser = "ru-RU",
                acceptLanguage = "ru", deviceLanguageTag = "fr", allowPseudo = false,
            ),
        )
        assertEquals(
            "uk",
            AppLocale.resolve(
                explicit = null, persisted = "auto", haUser = "ru",
                acceptLanguage = "ru-RU", deviceLanguageTag = "ru_UA", allowPseudo = false,
            ),
        )
    }

    @Test fun `Russian automatic signals select Ukrainian after Ukrainian is supported`() {
        val supported = AppLocale.RELEASE_LOCALES + AppLocale.UKRAINIAN
        assertEquals("uk", AppLocale.automaticLocaleOverride("ru-RU", supported))
        assertNull(AppLocale.automaticLocaleOverride("be-RU", supported))
        assertEquals("uk", resolveSupported(haUser = "ru", supported = supported))
        assertEquals("uk", resolveSupported(acceptLanguage = "ru-RU", supported = supported))
        assertEquals("uk", resolveSupported(deviceLanguageTag = "ru_UA", supported = supported))
        assertEquals("uk", resolveSupported(acceptLanguage = "ru-Latn-RU", supported = supported))
        assertEquals("de", resolveSupported(acceptLanguage = "de;q=1, ru;q=.8", supported = supported))
        assertEquals("uk", resolveSupported(acceptLanguage = "de;q=.8, ru;q=1", supported = supported))
    }

    @Test fun `the Russian automatic alias is dormant whenever Ukrainian is not supported`() {
        val withoutUkrainian = AppLocale.RELEASE_LOCALES - AppLocale.UKRAINIAN
        assertNull(AppLocale.automaticLocaleOverride("ru", withoutUkrainian))
        assertNull(AppLocale.canonicalAutomatic("ru-RU", withoutUkrainian))
        assertEquals("fr", resolveSupported(haUser = "ru", deviceLanguageTag = "fr", supported = withoutUkrainian))
        assertEquals("uk", AppLocale.automatic("ru-RU"))
        assertNull("an explicit Russian choice never canonicalizes", AppLocale.canonical("ru"))
    }

    @Test fun `supported explicit choices outrank the Russian automatic alias`() {
        val supported = AppLocale.RELEASE_LOCALES + AppLocale.UKRAINIAN
        assertEquals("fr", resolveSupported(explicit = "fr-CA", haUser = "ru", supported = supported))
        assertEquals("de", resolveSupported(persisted = "de-DE", haUser = "ru", supported = supported))
        assertEquals("uk", resolveSupported(explicit = "uk-UA", haUser = "ru", supported = supported))
        assertEquals("uk", resolveSupported(persisted = "uk", haUser = "ru", supported = supported))
    }

    @Test fun `unsupported explicit Russian tags are not treated as automatic signals`() {
        val supported = AppLocale.RELEASE_LOCALES + AppLocale.UKRAINIAN
        assertEquals("fr", resolveSupported(explicit = "ru-RU", acceptLanguage = "fr", supported = supported))
        assertEquals("it", resolveSupported(persisted = "ru", acceptLanguage = "it", supported = supported))
    }

    @Test fun `pseudolocale requires an explicit debug admission`() {
        assertEquals("en-XA", AppLocale.resolve("en-XA", acceptLanguage = "de", deviceLanguageTag = "fr", allowPseudo = true))
        assertEquals("en", AppLocale.resolve("en-XA", acceptLanguage = "de", deviceLanguageTag = "fr", allowPseudo = false))
        assertEquals("en", AppLocale.resolve(null, acceptLanguage = "en-XA, de;q=.9", deviceLanguageTag = "fr", allowPseudo = true))
    }

    @Test fun `malformed and unbounded language input is ignored`() {
        assertEquals("fr", AppLocale.resolve("de\nInjected", acceptLanguage = null, deviceLanguageTag = "fr", allowPseudo = true))
        assertEquals("it", AppLocale.resolve(null, acceptLanguage = "x".repeat(1_025), deviceLanguageTag = "it", allowPseudo = false))
        assertEquals("fr", AppLocale.resolve(null, acceptLanguage = "de;q=broken, fr;q=.8", deviceLanguageTag = "it", allowPseudo = false))
        assertEquals("fr", AppLocale.resolve(null, acceptLanguage = "de;q=.9;q=.8, fr;q=.7", deviceLanguageTag = "it", allowPseudo = false))
        assertEquals("fr", AppLocale.resolve(null, acceptLanguage = "de;q=NaN, fr;q=.8", deviceLanguageTag = "it", allowPseudo = false))
        assertEquals("fr", AppLocale.resolve(null, acceptLanguage = "de;q=Infinity, fr;q=.8", deviceLanguageTag = "it", allowPseudo = false))
    }

    private fun resolveSupported(
        explicit: String? = null,
        persisted: String? = "auto",
        haUser: String? = null,
        acceptLanguage: String? = null,
        deviceLanguageTag: String? = null,
        supported: Collection<String>,
    ): String = AppLocale.resolveForSupportedLocales(
        explicit = explicit,
        persisted = persisted,
        haUser = haUser,
        acceptLanguage = acceptLanguage,
        deviceLanguageTag = deviceLanguageTag,
        allowPseudo = false,
        supportedLocales = supported,
    )
}
