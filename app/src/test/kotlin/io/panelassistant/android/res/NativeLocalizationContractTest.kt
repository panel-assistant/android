package io.panelassistant.android.res

import java.io.File
import io.panelassistant.android.i18n.AppLocale
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeLocalizationContractTest {
    // Source-text reason: native string resources, manifest labels and whole-tree R.string/literal scans are the
    // native translation catalogue contract; no test here depends on a production class, function or file name.
    private val productionRoot = File("src/main")
    private val productionKotlin = File(productionRoot, "kotlin").walkTopDown()
        .filter { it.isFile && it.extension == "kt" }
        .associate { it.relativeTo(productionRoot).invariantSeparatorsPath to it.readText() }

    private fun baseStrings(): Map<String, Boolean> {
        return stringsIn(File("src/main/res/values/strings.xml"))
    }

    private fun stringsIn(file: File): Map<String, Boolean> {
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        val strings = document.getElementsByTagName("string")
        return (0 until strings.length).associate { index ->
            val attributes = strings.item(index).attributes
            attributes.getNamedItem("name").nodeValue to
                (attributes.getNamedItem("translatable")?.nodeValue != "false")
        }
    }

    @Test fun baseCatalogueHasAUniqueBoundedKeyset() {
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(File("src/main/res/values/strings.xml"))
        val catalogue = baseStrings()
        assertEquals(230, document.getElementsByTagName("string").length)
        assertEquals(230, catalogue.size)
        assertEquals(228, catalogue.count { it.value })
        assertEquals(
            setOf("app_name", "wordmark_description"),
            catalogue.filterValues { !it }.keys,
        )
    }

    @Test fun componentLabelsAndAccessibilityServiceUseResources() {
        val manifest = File("src/main/AndroidManifest.xml").readText()
        listOf(
            "@string/config_activity_label",
            "@string/guard_db_activity_label",
            "@string/dashboard_activity_label",
            "@string/admin_launcher_activity_label",
        ).forEach { assertTrue("missing manifest resource $it", manifest.contains(it)) }
        val accessibility = File("src/main/res/xml/accessibility_config.xml").readText()
        assertTrue(accessibility.contains("android:description=\"@string/a11y_description\""))
    }

    @Test fun nativeUiSinksDoNotEmbedEnglishLiterals() {
        val sink = Regex(
            "(?:\\b(?:text|contentDescription|navigationContentDescription|subtitle)\\s*=|" +
                "\\.(?:setText|setTitle|setMessage|setPositiveButton|setNegativeButton|setNeutralButton|" +
                "setContentTitle|setContentText)\\s*\\(|" +
                "\\b(?:menu\\.add|surface\\.(?:heading|detail|caption|action)|text|button|Tile|" +
                "updateForegroundStatus|startForegroundCompat|announceDeliberateRestart|" +
                "showBlockedAdmissionScreen|foregroundNotification)\\s*\\()\\s*\"([^\"\\n]*)\"",
        )
        val deliberateNonLanguageLiterals = setOf(
            "ha-paneld",
            "v\${BuildConfig.VERSION_NAME}",
            "\${(brightness.getCommanded().coerceAtLeast(0) * 100 + 127) / 255}%",
            "\${volume.getPercent()}%",
        )
        productionKotlin.forEach { (name, source) ->
            val violations = sink.findAll(source)
                .map { it.groupValues[1] }
                .filter { literal -> literal.any(Char::isLetter) && literal !in deliberateNonLanguageLiterals }
                .toList()
            assertEquals("hardcoded native UI sink in $name", emptyList<String>(), violations)
        }
    }

    @Test fun everyAppStringReferenceResolvesAndEveryFrozenKeyIsAccountedFor() {
        val catalogue = baseStrings()
        val supplemental = stringsIn(File("src/main/res/values/proximity_wizard.xml")) +
            stringsIn(File("src/main/res/values/ha_lifecycle.xml"))
        val kotlinReferences = productionKotlin.values.flatMap { source ->
            Regex("(?<!android\\.)R\\.string\\.([A-Za-z0-9_]+)").findAll(source).map { it.groupValues[1] }.toList()
        }.toSet()
        val xmlReferences = productionRoot.walkTopDown()
            .filter { it.isFile && (it.extension == "xml" || it.name == "AndroidManifest.xml") }
            .flatMap { file ->
                Regex("@string/([A-Za-z0-9_]+)").findAll(file.readText()).map { it.groupValues[1] }
            }
            .toSet()
        val references = kotlinReferences + xmlReferences

        assertEquals("missing base resources", emptySet<String>(), references - catalogue.keys - supplemental.keys)
        assertEquals(
            "unaccounted frozen resources",
            emptySet<String>(),
            catalogue.keys - references,
        )
    }

    @Test fun proximityWizardResourcesAreCompleteInEveryReleaseLocale() {
        val base = stringsIn(File("src/main/res/values/proximity_wizard.xml"))
        for (locale in AppLocale.RELEASE_LOCALES.filterNot { it == AppLocale.ENGLISH }) {
            val directory = when (locale) {
                "zh-Hans" -> "values-zh-rCN"
                "pt-BR" -> "values-pt-rBR"
                else -> "values-$locale"
            }
            val translated = stringsIn(File("src/main/res/$directory/proximity_wizard.xml"))
            assertEquals("$directory proximity strings", base.keys, translated.keys)
            assertTrue("$directory must contain only translated strings", translated.values.all { it })
        }
    }
}
