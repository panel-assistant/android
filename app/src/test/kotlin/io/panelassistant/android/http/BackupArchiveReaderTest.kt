package io.panelassistant.android.http

import io.panelassistant.android.backup.PanelBackup
import io.panelassistant.android.config.SettingsRegistry
import java.io.File
import java.nio.file.Files
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupArchiveReaderTest {
    @Test fun nestedConfigIsRefusedBeforePlanning() {
        val plan = planRestoreConfig(
            JSONObject().put("friendly_name", JSONObject().put("nested", "value")),
            SettingsRegistry.SCHEMA,
            { throw AssertionError("Malformed settings must not sample the current HA origin") },
            { throw AssertionError("Malformed settings must not sample Zigbee ownership") },
        )
        assertTrue(plan.values.isEmpty())
        assertEquals(listOf("friendly_name: expected a scalar setting value"), plan.errors)
    }

    @Test fun restorePlanningSamplesLiveSettingsAfterArchiveValuesAndInOriginalOrder() {
        var origin = "http://old.example:8123"
        var zigbeeConfigured = true
        val reads = mutableListOf<String>()
        val archive = JSONObject().put("home_dashboard", object {
            override fun toString(): String {
                reads += "archive"
                origin = "http://current.example:8123"
                return "http://current.example:8123/lovelace/home"
            }
        }).put("zigbee_router", "false")
        val plan = planRestoreConfig(
            archive, SettingsRegistry.SCHEMA,
            currentHaOrigin = {
                reads += "origin"
                zigbeeConfigured = false
                origin
            },
            zigbeeRouterConfigured = { reads += "zigbee"; zigbeeConfigured },
        )
        assertEquals(listOf("archive", "origin", "zigbee"), reads)
        assertEquals(emptyList<String>(), plan.errors)
        assertEquals("/lovelace/home", plan.values["home_dashboard"])
        assertTrue("Unconfigured vendor ownership must be preserved", "zigbee_router" !in plan.values)
        assertTrue(plan.warnings.contains("legacy zigbee_router=false skipped to preserve untouched vendor gateway ownership"))
    }

    @Test fun declaredSizeMustMatchExtractedBytes() = withArchive("text".toByteArray()) { reader, archive, directory ->
        val result = runCatching {
            reader.readArchiveText(archive, ArchiveTextRef("entity/filter-ids.txt", 3, 100, true), entries, "read-")
        }
        assertTrue("a false size must refuse the payload", result.exceptionOrNull() is IllegalArgumentException)
        assertEquals(setOf("payload", "backup.zip"), directory.list()!!.toSet())
    }

    @Test fun malformedUtf8IsRefusedAndStagingIsRemoved() = withArchive(byteArrayOf(0xc3.toByte(), 0x28)) { reader, archive, directory ->
        val result = runCatching {
            reader.readArchiveText(archive, ArchiveTextRef("entity/filter-ids.txt", 2, 100, true), entries, "read-")
        }
        assertTrue("replacement text would corrupt restored owner state", result.exceptionOrNull() is java.nio.charset.CharacterCodingException)
        assertEquals(setOf("payload", "backup.zip"), directory.list()!!.toSet())
    }

    @Test fun validTextIsReturnedAndStagingIsRemoved() = withArchive("light.kitchen\n".toByteArray()) { reader, archive, directory ->
        assertEquals(
            "light.kitchen\n",
            reader.readArchiveText(archive, ArchiveTextRef("entity/filter-ids.txt", 14, 100, true), entries, "read-"),
        )
        assertEquals(setOf("payload", "backup.zip"), directory.list()!!.toSet())
    }

    private fun withArchive(bytes: ByteArray, test: (BackupArchiveReader, File, File) -> Unit) {
        val directory = Files.createTempDirectory("archive-reader-test").toFile()
        try {
            val payload = File(directory, "payload").apply { writeBytes(bytes) }
            val archive = File(directory, "backup.zip")
            archive.outputStream().use {
                PanelBackup.writeArchive(it, "{}", listOf(PanelBackup.ArchiveSource("entity/filter-ids.txt", payload)), 1024)
            }
            test(BackupArchiveReader(directory) { emptySet() }, archive, directory)
        } finally {
            directory.deleteRecursively()
        }
    }

    private val entries = setOf("entity/filter-ids.txt")
}
