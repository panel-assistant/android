package io.github.maxlyth.hapaneld.migration

import io.github.maxlyth.hapaneld.backup.PanelBackup
import io.github.maxlyth.hapaneld.device.profile.DeviceFacts
import io.github.maxlyth.hapaneld.device.profile.ProfileBackup
import io.github.maxlyth.hapaneld.device.profile.ProfileRef
import io.github.maxlyth.hapaneld.device.profile.ProfileSelection
import io.github.maxlyth.hapaneld.device.profile.ProfileYaml
import io.github.maxlyth.hapaneld.device.profile.RuntimeProfileRegistry
import io.github.maxlyth.hapaneld.device.profile.TransientProfilePreferences
import io.github.maxlyth.hapaneld.device.profile.testProfileDocument
import io.github.maxlyth.hapaneld.http.PaneldServer
import io.github.maxlyth.hapaneld.panelAssistantDiscoveryId
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The profile catalog is checked before the legacy app releases the panel, and a restore that is refused
 * anyway says why. Both exist because a refused restore runs after the release: the panel is left on
 * defaults, and until now the successor reported only "restore did not complete".
 */
class ReceiptProfileRefusalTest {
    @get:Rule val temp = TemporaryFolder()

    private val board = DeviceFacts("test-panel", "test-device", "fw-1")
    private val genericYaml = ProfileYaml.serialize(testProfileDocument(id = "generic", fallback = true))
    private val vendorYaml = ProfileYaml.serialize(testProfileDocument(id = "vendor.test-panel", version = "1.0.1", facts = board))
    private val retired = ProfileRef("vendor.test-panel", "9".repeat(64))

    private fun catalog(selection: ProfileSelection) = ProfileBackup(
        revisions = emptyList(),
        selection = selection,
        active = (selection as? ProfileSelection.Pinned)?.ref,
        lastKnownGood = null,
    )

    private fun receipt(profiles: ProfileBackup?, entry: String = PaneldServer.PROFILE_BACKUP_ENTRY): File {
        val sources = listOf("entity/filter", "entity/overrides", "state/app-state")
            .mapTo(mutableListOf()) { PanelBackup.ArchiveSource(it, temp.newFile().apply { writeText("payload") }) }
        val manifest = StringBuilder(
            "{\"kind\":\"ha-paneld-backup\",\"schema\":1,\"panel_id\":\"alpha\",\"discovery_id\":\"$own\"," +
                "\"config\":{\"panel_id\":\"alpha\"},\"state\":{\"entry\":\"state/app-state\",\"size\":7,\"rows\":76}," +
                "\"entity_state\":{\"filter_ids_entry\":\"entity/filter\",\"overrides_entry\":\"entity/overrides\"}",
        )
        if (profiles != null) {
            val payload = temp.newFile().apply { writeText(profiles.toJson().toString()) }
            sources += PanelBackup.ArchiveSource(entry, payload)
            manifest.append(",\"profiles\":{\"entry\":\"$entry\",\"size\":${payload.length()}}")
        }
        manifest.append("}")
        return temp.newFile().apply { outputStream().use { PanelBackup.writeArchive(it, manifest.toString(), sources) } }
    }

    /** What the successor's own planner answers: this build's bundled catalog and nothing else. */
    private fun plan(payload: ProfileBackup) = RuntimeProfileRegistry(
        filesDir = File(temp.root, "never-created"),
        preferences = TransientProfilePreferences(),
        bundledLoader = { mapOf("generic.yaml" to genericYaml, "panel.yaml" to vendorYaml) },
        facts = board,
        coreVersion = "1.0.0",
        clock = { 1000L },
    ).planBackupRestore(payload)

    private val own = panelAssistantDiscoveryId("9774d56d682e549c")!!

    /** Exactly what the successor's VERIFY step asks. */
    private fun refusal(archive: File) = ReceiptVerifier.migrationRefusal(archive, own, ::plan)

    @Test fun aWholeReceiptIsCheckedFirstAndTheCatalogOnlyAfterIt() {
        val archive = receipt(catalog(ProfileSelection.Pinned(ProfileRef("vendor.dropped", "d".repeat(64)))))
        assertEquals("receipt was not written on this device", ReceiptVerifier.migrationRefusal(archive, "someone-else", ::plan))
    }

    @Test fun aCatalogPinnedToARetiredBundledRevisionIsAdmittedBeforeTheRelease() {
        assertNull(refusal(receipt(catalog(ProfileSelection.Pinned(retired)))))
    }

    @Test fun aCatalogThatCannotRestoreIsRefusedBeforeTheReleaseWithItsReason() {
        val dropped = ProfileRef("vendor.dropped", "d".repeat(64))

        val reason = refusal(receipt(catalog(ProfileSelection.Pinned(dropped))))

        assertEquals(
            "receipt's profile catalog is not restorable: profiles.selection: Referenced immutable revision is missing or incompatible.",
            reason,
        )
    }

    @Test fun anAutomaticCatalogAndAReceiptWithoutOneAreAdmitted() {
        assertNull(refusal(receipt(catalog(ProfileSelection.Auto))))
        assertNull(refusal(receipt(null)))
    }

    @Test fun aProfileEntryUnderAnotherNameIsNotRead() {
        assertEquals(
            "receipt's profile catalog could not be read",
            refusal(receipt(catalog(ProfileSelection.Auto), entry = "profiles/other.json")),
        )
    }

    @Test fun aRefusalBeforeTheReleaseLeavesTheLegacyAppServingThePanel() = runBlocking {
        val archive = receipt(catalog(ProfileSelection.Pinned(ProfileRef("vendor.dropped", "d".repeat(64)))))
        val calls = mutableListOf<String>()
        val markers = object : SuccessorMigration.Markers {
            val values = mutableMapOf<SuccessorMigration.Step, String>()
            override fun done(step: SuccessorMigration.Step) = step in values
            override fun value(step: SuccessorMigration.Step) = values[step]
            override fun record(step: SuccessorMigration.Step, value: String) = true.also { values[step] = value }
            override fun complete() = false
            override fun recordComplete() = true
        }
        val ports = object : SuccessorMigration.Ports {
            override fun environment() = SuccessorMigration.Environment.PASSIVE
            override fun legacyInstalled() = true
            override fun releaseTokenHeld() = true
            override fun receiptSha256() = "sha-1"
            override suspend fun pullReceipt() = "sha-1".also { calls += "pull" }
            override fun receiptRefusal() = refusal(archive).also { calls += "verify" }
            override suspend fun legacyRetired() = false
            override suspend fun requestRelease(): String? = throw AssertionError("released a panel whose catalog cannot restore")
            override fun portFree() = false
            override suspend fun restoreReceipt(): String? = throw AssertionError("restored before the release")
            override fun missingGrants() = emptySet<String>()
            override fun claimGrant(grant: String) = throw AssertionError("claimed a grant before the release")
            override fun claimHome() = throw AssertionError("claimed HOME before the release")
            override fun homeSettled() = false
            override fun healthy() = false
            override fun mqttConverged() = false
            override fun uninstallLegacy() = throw AssertionError("removed the legacy app")
            override fun forgetSecrets() = throw AssertionError("forgot the receipt")
        }

        val result = SuccessorMigration(ports, markers).pass()

        assertTrue(result is SuccessorMigration.Result.Waiting)
        result as SuccessorMigration.Result.Waiting
        assertEquals(SuccessorMigration.Step.VERIFY, result.step)
        assertTrue(result.reason, result.reason.startsWith("receipt's profile catalog is not restorable"))
        assertEquals(listOf("pull", "verify"), calls)
        assertTrue(!markers.done(SuccessorMigration.Step.RELEASE))
    }

    @Test fun aRefusedRestoreNamesItsPresentationCodeAndReasons() {
        // The body a panel's own endpoint returned for its own receipt when its catalog could not restore.
        val body = """
            {"ok":false,"error":"profile catalog is not restorable",
             "errors":["profiles.selection: Referenced immutable revision is missing or incompatible."],
             "presentation":{"code":"restore-profile-catalog-not-restorable"}}
        """.trimIndent().toByteArray()

        assertEquals(
            "restore refused (HTTP 422, restore-profile-catalog-not-restorable): profile catalog is not restorable" +
                " — profiles.selection: Referenced immutable revision is missing or incompatible.",
            restoreRefusal(422, body),
        )
    }

    @Test fun aRefusalWithoutAReadableBodyStillNamesItsStatus() {
        assertEquals("restore refused (HTTP 503)", restoreRefusal(503, null))
        assertEquals("restore refused (HTTP 400)", restoreRefusal(400, "not json".toByteArray()))
    }
}
