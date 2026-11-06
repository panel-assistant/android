package io.panelassistant.android.i18n

import io.panelassistant.android.config.SettingsRegistry
import io.panelassistant.android.http.browserI18nPayload
import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
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

}
