package io.panelassistant.android.backup

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Test
import org.w3c.dom.Document

class ProfileBackupPolicyTest {
    @Test
    fun `implicit android backup is disabled`() {
        // Source-text reason: the shipped manifest and backup rules are the Android backup privacy contract.
        val manifest = document("src/main/AndroidManifest.xml")
        val applications = manifest.getElementsByTagName("application")

        assertEquals(1, applications.length)
        assertEquals("false", applications.item(0).attributes.getNamedItemNS(ANDROID_NS, "allowBackup")?.nodeValue)
    }

    @Test
    fun `android backup rules never migrate credentials or panel-local state`() {
        // Source-text reason: the shipped backup rules are the Android backup privacy contract.
        val legacy = exclusions("src/main/res/xml/backup_rules.xml")
        val modern = exclusions("src/main/res/xml/data_extraction_rules.xml")

        for (preferences in NON_TRANSFERABLE_PREFS) {
            val exclusion = Exclusion("sharedpref", preferences)
            assertEquals("legacy exclusion for $preferences", 1, legacy.count { it == exclusion })
            assertEquals("modern exclusions for $preferences", 2, modern.count { it == exclusion })
        }
        for (path in NON_TRANSFERABLE_FILES) {
            val exclusion = Exclusion("file", path)
            assertEquals("legacy exclusion for $path", 1, legacy.count { it == exclusion })
            assertEquals("modern exclusions for $path", 2, modern.count { it == exclusion })
        }
        for (path in NON_TRANSFERABLE_DATABASES) {
            val exclusion = Exclusion("database", path)
            assertEquals("legacy exclusion for $path", 1, legacy.count { it == exclusion })
            assertEquals("modern exclusions for $path", 2, modern.count { it == exclusion })
        }
        assertEquals(1, legacy.count { it == Exclusion("database", ".") })
        assertEquals(2, modern.count { it == Exclusion("database", ".") })
    }

    private fun exclusions(path: String): List<Exclusion> {
        val document = document(path)
        return (0 until document.getElementsByTagName("exclude").length).mapNotNull { index ->
            val attributes = document.getElementsByTagName("exclude").item(index).attributes ?: return@mapNotNull null
            val domain = attributes.getNamedItem("domain")?.nodeValue ?: return@mapNotNull null
            val excludedPath = attributes.getNamedItem("path")?.nodeValue ?: return@mapNotNull null
            Exclusion(domain, excludedPath)
        }
    }

    private data class Exclusion(val domain: String, val path: String)

    private fun document(path: String): Document =
        DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder().parse(File(path))

    private companion object {
        const val ANDROID_NS = "http://schemas.android.com/apk/res/android"
        val NON_TRANSFERABLE_PREFS = listOf(
            "ha-paneld.xml",
            "ha-paneld-shizuku.xml",
            "ha-paneld-performance-binding.xml",
            "ha-paneld-device-profiles.xml",
            "ha-paneld-profile-calibration.xml",
            "ha-paneld-controller-state.xml",
            "ha-paneld-state-bridge.xml",
        )
        val NON_TRANSFERABLE_FILES = listOf(
            "panel-screenshots/",
            "last-panel-screenshot.png",
            "config-revisions/",
        )
        val NON_TRANSFERABLE_DATABASES = listOf("ha-paneld.db", "entity-learning.db")
    }
}
