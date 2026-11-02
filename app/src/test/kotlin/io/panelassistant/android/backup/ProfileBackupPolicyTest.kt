package io.panelassistant.android.backup

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Test
import org.w3c.dom.Document
import org.w3c.dom.Element

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
        val legacy = document("src/main/res/xml/backup_rules.xml")
        val modern = document("src/main/res/xml/data_extraction_rules.xml")
        val policies = listOf(
            "legacy" to legacy.documentElement,
            "cloud backup" to section(modern, "cloud-backup"),
            "device transfer" to section(modern, "device-transfer"),
        )
        for ((name, policy) in policies) {
            val exclusions = exclusions(policy)
            for (path in NON_TRANSFERABLE_FILES) {
                assertEquals("$name exclusion for $path", 1, exclusions.count { it == Exclusion("file", path) })
            }
            for (path in NON_TRANSFERABLE_DATABASES + ".") {
                assertEquals("$name database exclusion for $path", 1, exclusions.count { it == Exclusion("database", path) })
            }
        }
    }

    private fun section(document: Document, tag: String): Element {
        val sections = document.getElementsByTagName(tag)
        assertEquals("one $tag policy", 1, sections.length)
        return sections.item(0) as Element
    }

    private fun exclusions(policy: Element): List<Exclusion> {
        val nodes = policy.getElementsByTagName("exclude")
        return (0 until nodes.length).mapNotNull { index ->
            val attributes = nodes.item(index).attributes ?: return@mapNotNull null
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
        val NON_TRANSFERABLE_FILES = listOf(
            "panel-screenshots/",
            "last-panel-screenshot.png",
            "config-revisions/",
        )
        val NON_TRANSFERABLE_DATABASES = listOf("ha-paneld.db")
    }
}
