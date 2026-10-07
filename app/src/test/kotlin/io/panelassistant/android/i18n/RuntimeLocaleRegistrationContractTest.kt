package io.panelassistant.android.i18n

import io.panelassistant.android.config.SettingsRegistry
import io.panelassistant.android.http.browserI18nPayload
import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** One admission boundary for every runtime surface that must move with a new release locale. */
class RuntimeLocaleRegistrationContractTest {
    // Source-text reason: the locale lists shipped in the i18n catalogues and the web assets are the public ?lang contract.
    private val assets = File("src/main/assets")
    private val release = AppLocale.RELEASE_LOCALES.toList()

    @Test fun `catalogue assets and runtime authorities expose the exact release sequence`() {
        val catalogues = assets.resolve("i18n").listFiles { file -> file.isFile && file.extension == "json" }
            .orEmpty()
            .sortedBy { it.name }
        val catalogueLocales = catalogues.map { file ->
            JSONObject(file.readText()).getString("locale").also { locale ->
                assertEquals("catalogue filename and declared locale differ", locale, file.nameWithoutExtension)
            }
        }
        assertExactMembers("catalogue assets", release, catalogueLocales)
        assertEquals(listOf(SettingsRegistry.DEFAULT_UI_LANGUAGE) + release, SettingsRegistry.UI_LANGUAGES)

        val configure = assets.resolve("configure-state.js").readText()
        val labels = jsObjectKeys(configure, "UI_LANGUAGE_LABELS")
        assertEquals(SettingsRegistry.UI_LANGUAGES, labels)
    }

    @Test fun `every page is told the admitted locales and only the debug pseudolocale extra`() {
        val strings = CatalogueLoader { assets.resolve(it).readText() }.strings("de")
        val payload = JSONObject(browserI18nPayload(strings, setOf("shell.")))
        val locales = payload.getJSONArray("locales").let { array -> (0 until array.length()).map(array::getString) }
        assertExactSequence("page locale list", release + AppLocale.PSEUDO, locales)
    }

    @Test fun `registration comparison rejects omissions duplicates extras reorderings and no-op mutants`() {
        val actual = registrationSnapshot()
        validateRegistration(actual)
        val mutants = listOf<Pair<String, Registration>>(
            "catalogue omission" to actual.copy(catalogues = actual.catalogues.dropLast(1)),
            "Settings duplicate" to actual.copy(settings = actual.settings + actual.settings.last()),
            "Configure extra" to actual.copy(configure = actual.configure + "zz"),
            "Configure reorder" to actual.copy(configure = actual.configure.reversed()),
            "release tranche not registered anywhere" to actual.copy(release = actual.release + "nl"),
        )
        assertEquals("mutation names must be unique", mutants.size, mutants.map { it.first }.toSet().size)
        mutants.forEach { (name, mutant) ->
            assertTrue("registration mutant unexpectedly survived: $name", runCatching {
                validateRegistration(mutant)
            }.isFailure)
        }
        assertFalse("the mutation battery must not contain a no-op", mutants.any { it.second == actual })
    }

    private fun registrationSnapshot(): Registration {
        val catalogues = assets.resolve("i18n").listFiles { file -> file.isFile && file.extension == "json" }
            .orEmpty().map { JSONObject(it.readText()).getString("locale") }
        val configure = jsObjectKeys(assets.resolve("configure-state.js").readText(), "UI_LANGUAGE_LABELS")
        return Registration(
            release = release,
            catalogues = catalogues,
            settings = SettingsRegistry.UI_LANGUAGES,
            configure = configure,
        )
    }

    private fun validateRegistration(value: Registration) {
        assertExactMembers("catalogues", value.release, value.catalogues)
        assertExactSequence("Settings", listOf(SettingsRegistry.DEFAULT_UI_LANGUAGE) + value.release, value.settings)
        assertExactSequence("Configure", listOf(SettingsRegistry.DEFAULT_UI_LANGUAGE) + value.release, value.configure)
    }

    private fun jsObjectKeys(source: String, variable: String): List<String> {
        val body = checkNotNull(
            Regex("var\\s+$variable\\s*=\\s*\\{([\\s\\S]*?)\\n\\s*};").find(source),
        ) { "$variable object is missing" }.groupValues[1]
        val keys = Regex("[\\\"']([^\\\"']+)[\\\"']\\s*:").findAll(body).map { it.groupValues[1] }.toList()
        assertEquals("$variable contains duplicate keys", keys.size, keys.toSet().size)
        return keys
    }

    private fun assertExactMembers(owner: String, expected: List<String>, actual: List<String>) {
        assertEquals("$owner contains duplicate locales", actual.size, actual.toSet().size)
        assertEquals("$owner differs from release locales", expected.toSet(), actual.toSet())
    }

    private fun assertExactSequence(owner: String, expected: List<String>, actual: List<String>) {
        assertEquals("$owner contains duplicate locales", actual.size, actual.toSet().size)
        assertEquals("$owner differs from canonical runtime order", expected, actual)
    }

    private data class Registration(
        val release: List<String>,
        val catalogues: List<String>,
        val settings: List<String>,
        val configure: List<String>,
    )
}
