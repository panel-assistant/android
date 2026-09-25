package io.github.maxlyth.hapaneld.i18n

import io.github.maxlyth.hapaneld.testsupport.TestSources
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// Source-text reason: shipped res/, locale config, manifest and i18n catalogues are the platform locale contract.
class PlatformLocaleContractTest {
    @Test fun `native resource directories exactly cover release locales and every translatable key`() {
        val resourceRoot = TestSources.appDir("src/main/res")
        val expectedDirectories = AppLocale.RELEASE_LOCALES.associateWith { locale ->
            androidValuesDirectory(androidResourceQualifier(locale))
        }
        assertEquals(expectedDirectories.size, expectedDirectories.values.toSet().size)

        val actualDirectories = resourceRoot.listFiles { file ->
            file.isDirectory && file.name.startsWith("values") && File(file, "strings.xml").isFile
        }.orEmpty().mapTo(sortedSetOf()) { it.name }
        assertEquals(expectedDirectories.values.map { it.substringAfterLast('/') }.toSortedSet(), actualDirectories)

        val base = stringKeys("src/main/res/values/strings.xml")
        val translatable = base.filterValues { it }.keys
        expectedDirectories.filterKeys { it != AppLocale.ENGLISH }.forEach { (locale, directory) ->
            val target = stringKeys("$directory/strings.xml")
            assertEquals("$locale must translate every and only translatable base key", translatable, target.keys)
            assertTrue("$locale must not contain translatable=false declarations", target.values.all { it })
        }
    }

    @Test fun `platform locale declaration matches the JSON resolver boundary`() {
        val config = document("src/main/res/xml/locales_config.xml")
        val declared = config.getElementsByTagName("locale").let { locales ->
            (0 until locales.length).map { index ->
                locales.item(index).attributes.getNamedItemNS(ANDROID_NS, "name").nodeValue
            }
        }

        assertEquals(AppLocale.RELEASE_LOCALES.toList(), declared)
        assertEquals(declared.size, declared.toSet().size)

        val catalogues = TestSources.appDir("src/main/assets/i18n")
            .listFiles { file -> file.isFile && file.extension == "json" }
            .orEmpty()
            .map { it.nameWithoutExtension }
            .sorted()
        assertEquals(AppLocale.RELEASE_LOCALES.sorted(), catalogues)
    }

    @Test fun `manifest advertises the finite locale declaration`() {
        val manifest = document("src/main/AndroidManifest.xml")
        val applications = manifest.getElementsByTagName("application")

        assertEquals(1, applications.length)
        assertEquals(
            "@xml/locales_config",
            applications.item(0).attributes.getNamedItemNS(ANDROID_NS, "localeConfig")?.nodeValue,
        )

        val merged = document("build/intermediates/merged_manifests/debug/processDebugManifest/AndroidManifest.xml")
        val mergedApplications = merged.getElementsByTagName("application")
        assertEquals(1, mergedApplications.length)
        assertEquals(
            "@xml/locales_config",
            mergedApplications.item(0).attributes.getNamedItemNS(ANDROID_NS, "localeConfig")?.nodeValue,
        )
    }

    private fun document(path: String) = DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = true
    }.newDocumentBuilder().parse(TestSources.appFile(path))

    private fun stringKeys(path: String): Map<String, Boolean> {
        val nodes = document(path).getElementsByTagName("string")
        val entries = (0 until nodes.length).map { index ->
            val attributes = nodes.item(index).attributes
            attributes.getNamedItem("name").nodeValue to
                (attributes.getNamedItem("translatable")?.nodeValue != "false")
        }
        assertEquals("$path contains duplicate string names", entries.size, entries.map { it.first }.toSet().size)
        return entries.toMap()
    }

    private fun androidResourceQualifier(locale: String): String {
        if (locale == "zh-Hans") return "zh-rCN"
        val parts = locale.split('-')
        if (parts.size == 1) return locale
        if (parts.size == 2 && (parts[1].length == 2 || parts[1].all(Char::isDigit))) {
            return "${parts[0]}-r${parts[1]}"
        }
        return "b+${parts.joinToString("+")}"
    }

    private fun androidValuesDirectory(qualifier: String): String =
        if (qualifier == AppLocale.ENGLISH) "src/main/res/values" else "src/main/res/values-$qualifier"

    private companion object {
        const val ANDROID_NS = "http://schemas.android.com/apk/res/android"
    }
}
