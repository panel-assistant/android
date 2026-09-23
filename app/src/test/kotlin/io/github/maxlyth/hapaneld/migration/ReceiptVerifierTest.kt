package io.github.maxlyth.hapaneld.migration

import io.github.maxlyth.hapaneld.backup.PanelBackup
import io.github.maxlyth.hapaneld.panelAssistantDiscoveryId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ReceiptVerifierTest {
    @get:Rule val temp = TemporaryFolder()

    private val own = panelAssistantDiscoveryId("9774d56d682e549c")!!
    private val entries = listOf("entity/filter", "entity/overrides", "state/app-state")

    private fun manifest(
        kind: String = "ha-paneld-backup",
        discoveryId: String? = own,
        config: String = """{"panel_id":"alpha"}""",
        state: String? = """{"entry":"state/app-state","size":5,"rows":76}""",
        entity: String? = """{"filter_ids_entry":"entity/filter","overrides_entry":"entity/overrides"}""",
        profiles: String? = null,
    ) = buildString {
        append("{\"kind\":\"$kind\",\"schema\":1,\"panel_id\":\"alpha\"")
        if (discoveryId != null) append(",\"discovery_id\":\"$discoveryId\"")
        append(",\"config\":$config")
        if (entity != null) append(",\"entity_state\":$entity")
        if (state != null) append(",\"state\":$state")
        if (profiles != null) append(",\"profiles\":$profiles")
        append("}")
    }

    private fun archive(manifest: String, present: List<String> = entries): File {
        val target = temp.newFile()
        val sources = present.map { entry ->
            PanelBackup.ArchiveSource(entry, temp.newFile().apply { writeText("payload") })
        }
        target.outputStream().use { PanelBackup.writeArchive(it, manifest, sources) }
        return target
    }

    private fun refusal(file: File, ownId: String? = own) = ReceiptVerifier.refusal(file, ownId)

    @Test fun aCompleteBackupOfThisDeviceIsAccepted() {
        assertNull(refusal(archive(manifest())))
    }

    @Test fun anEmptyEntityFilterIsStillAWholeReceipt() {
        val target = temp.newFile()
        val sources = entries.map { entry ->
            PanelBackup.ArchiveSource(entry, temp.newFile().apply { if (entry == "state/app-state") writeText("rows") })
        }
        target.outputStream().use { PanelBackup.writeArchive(it, manifest(), sources) }

        assertNull(refusal(target))
    }

    @Test fun aMissingOrEmptyFileIsRefused() {
        assertEquals("receipt is missing", refusal(File(temp.root, "absent.zip")))
        assertEquals("receipt is missing", refusal(temp.newFile()))
    }

    @Test fun aTruncatedArchiveIsRefused() {
        val whole = archive(manifest())
        val truncated = temp.newFile().apply { writeBytes(whole.readBytes().copyOf(whole.length().toInt() / 2)) }

        assertEquals("receipt is not a readable archive", refusal(truncated))
    }

    @Test fun anArchiveWithACorruptedEntryIsRefused() {
        val whole = archive(manifest())
        val bytes = whole.readBytes()
        // The first occurrence of an entry name is its local header; the entry's data follows the name.
        // Corrupt a stored CRC rather than the deflate stream, so the archive still inflates cleanly and
        // only a reader that checks CRCs can notice.
        val name = "state/app-state"
        val dataStart = String(bytes, Charsets.ISO_8859_1).indexOf(name) + name.length
        val descriptor = String(bytes, Charsets.ISO_8859_1).indexOf("PK" + 7.toChar() + 8.toChar(), dataStart)
        assertTrue("the writer streams entries with a data descriptor", descriptor > dataStart)
        bytes[descriptor + 4] = (bytes[descriptor + 4].toInt() xor 0x5a).toByte()
        val corrupted = temp.newFile().apply { writeBytes(bytes) }

        assertEquals("receipt is not a readable archive", refusal(corrupted))
    }

    @Test fun somethingThatIsNotAPanelBackupIsRefused() {
        assertEquals("receipt is not a panel backup", refusal(archive(manifest(kind = "other"))))
        assertEquals("receipt has no manifest", refusal(archive("not json")))
    }

    @Test fun aBackupFromAnotherDeviceOrWithoutAnIdentityIsRefused() {
        val other = panelAssistantDiscoveryId("0000000000000001")

        assertEquals("receipt was not written on this device", refusal(archive(manifest(discoveryId = other))))
        assertEquals("receipt was not written on this device", refusal(archive(manifest(discoveryId = null))))
        assertEquals("receipt was not written on this device", refusal(archive(manifest()), ownId = null))
    }

    @Test fun aBackupWithoutConfigurationIsRefused() {
        assertEquals("receipt carries no configuration", refusal(archive(manifest(config = "{}"))))
    }

    @Test fun aBackupWhoseStateWasNotCapturedIsRefused() {
        assertEquals("receipt carries no panel state", refusal(archive(manifest(state = null))))
        assertEquals(
            "receipt carries no panel state",
            refusal(archive(manifest(state = """{"error":"capture-failed"}"""))),
        )
        assertEquals(
            "receipt carries no panel state rows",
            refusal(archive(manifest(state = """{"entry":"state/app-state","size":5,"rows":0}"""))),
        )
    }

    @Test fun aBackupWithoutEntityStateIsRefused() {
        assertEquals("receipt carries no entity state", refusal(archive(manifest(entity = null))))
        assertEquals(
            "receipt manifest does not name its payloads",
            refusal(archive(manifest(entity = """{"filter_ids_entry":"entity/filter"}"""))),
        )
    }

    @Test fun theLegacyMqttConnectionIsReadFromTheReceiptAndAnUnreadableReceiptDemandsAConnection() {
        val connected = manifest().dropLast(1) + ",\"mqtt_connected\":true}"
        val never = manifest().dropLast(1) + ",\"mqtt_connected\":false}"

        assertTrue(ReceiptVerifier.legacyMqttConnected(archive(connected)))
        assertEquals(false, ReceiptVerifier.legacyMqttConnected(archive(never)))
        assertEquals(false, ReceiptVerifier.legacyMqttConnected(archive(manifest())))
        assertTrue(ReceiptVerifier.legacyMqttConnected(File(temp.root, "absent.zip")))
    }

    @Test fun aDeclaredPayloadThatIsNotInTheArchiveIsRefused() {
        assertEquals(
            "receipt is missing entity/overrides",
            refusal(archive(manifest(), present = listOf("entity/filter", "state/app-state"))),
        )
        assertEquals(
            "receipt is missing profiles/catalog",
            refusal(archive(manifest(profiles = """{"entry":"profiles/catalog","size":7}"""))),
        )
    }
}
