package io.panelassistant.android.http

import io.panelassistant.android.assist.wakeword.WakeWordCatalog
import io.panelassistant.android.backup.PanelBackup
import io.panelassistant.android.backup.WakeWordBackup
import io.panelassistant.android.migration.IdentityMigrationSurface
import io.panelassistant.android.migration.MigrationState
import io.panelassistant.android.migration.SuccessorMigration.Step
import io.panelassistant.android.panelAssistantDiscoveryId
import io.panelassistant.android.util.AppInstaller
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class BackupReconciliationTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun `builder carries imported models and install identity without changing old archive shape`() {
        val catalog = wakeWordCatalog(folder.newFolder())
        PaneldServerHttpFixture().use { fixture ->
            val uid = fixture.config.ensureDeviceUid { "0123456789abcdef0123456789abcdef" }
            val builder = fixture.backupBuilder(catalog)
            builder.build(CompanionBackupRequest.EXCLUDED, "").use { artifact ->
                val manifest = JSONObject(PanelBackup.readManifest(artifact.file, 1024 * 1024)!!)
                assertFalse(manifest.has("wake_words"))
                assertTrue("the backup must carry this installation's discovery identity", manifest.has("discovery_id"))
                assertEquals(panelAssistantDiscoveryId(uid), manifest.getString("discovery_id"))
                assertNotEquals(panelAssistantDiscoveryId(fixture.config.androidId), manifest.getString("discovery_id"))
            }
            val word = importedWakeWord("porch")
            assertTrue(catalog.import(word.id, word.manifest, word.model) is WakeWordCatalog.ImportResult.Imported)
            builder.build(CompanionBackupRequest.EXCLUDED, "").use { artifact ->
                val manifest = JSONObject(PanelBackup.readManifest(artifact.file, 1024 * 1024)!!)
                assertTrue("an imported model must have an archive section", manifest.has("wake_words"))
                val section = manifest.getJSONObject("wake_words")
                val entries = declaredArchiveEntries(
                    manifest.optJSONObject("entity_state"), manifest.optJSONObject("profiles"),
                    manifest.optJSONObject("companion"), manifest.optJSONObject("state"), section,
                )
                val carried = WakeWordBackup.read(artifact.file, section, entries, fixture.directory).single()
                assertEquals("porch", carried.id)
                assertArrayEquals(word.model, carried.model)
                assertEquals(1, section.getInt("count"))
            }
            assertFalse(fixture.directory.listFiles().orEmpty().any { it.name.endsWith(".payload") || it.name.endsWith(".zip") })
        }
    }

    @Test fun `builder refuses unreadable or oversized imports as a whole and releases staged files`() {
        val imports = folder.newFolder()
        val catalog = wakeWordCatalog(imports)
        PaneldServerHttpFixture().use { fixture ->
            val builder = fixture.backupBuilder(catalog)
            val word = importedWakeWord("porch")
            catalog.import(word.id, word.manifest, word.model)
            File(imports, "porch/porch.tflite").delete()
            assertTrue(runCatching { builder.build(CompanionBackupRequest.EXCLUDED, "") }.exceptionOrNull() is CompanionBackupUnavailable)
            catalog.import(word.id, word.manifest, ByteArray(2 * 1024 * 1024) { 1 })
            for (id in listOf("garden", "kitchen", "hall", "office")) {
                val large = importedWakeWord(id)
                assertTrue(catalog.import(id, large.manifest, ByteArray(2 * 1024 * 1024) { 1 }) is WakeWordCatalog.ImportResult.Imported)
            }
            val failure = runCatching { builder.build(CompanionBackupRequest.EXCLUDED, "") }.exceptionOrNull()
            assertTrue(failure.toString(), failure is CompanionBackupUnavailable)
            assertTrue(failure!!.message!!, failure.message!!.contains("MiB limit"))
            assertFalse(fixture.directory.listFiles().orEmpty().any { it.name.endsWith(".payload") || it.name.endsWith(".zip") })
        }
    }

    @Test fun `full mount validates archive only wake words before preview or any import`() {
        val catalog = wakeWordCatalog(folder.newFolder())
        PaneldServerHttpFixture(wakeWords = catalog).use { fixture ->
            testApplication {
                application { fixture.mount(this) }
                for (section in listOf("{}", "null", "1")) {
                    val response = client.post("/api/v1/restore?dry_run=1") {
                        setBody("""{"kind":"ha-paneld-backup","schema":1,"config":{},"wake_words":$section}""")
                    }
                    assertEquals(HttpStatusCode.BadRequest, response.status)
                    assertEquals("invalid wake_words object", JSONObject(response.bodyAsText()).getString("error"))
                }
                val malformed = archive("not-json")
                val rejected = client.post("/api/v1/restore?dry_run=1") { setBody(malformed.readBytes()) }
                assertEquals(HttpStatusCode.BadRequest, rejected.status)
                assertEquals("invalid wake word archive entry", JSONObject(rejected.bodyAsText()).getString("error"))
                val valid = archive(WakeWordBackup.encode(listOf(importedWakeWord("porch")))!!)
                val accepted = client.post("/api/v1/restore?dry_run=1") { setBody(valid.readBytes()) }
                assertEquals(accepted.bodyAsText(), HttpStatusCode.OK, accepted.status)
                assertEquals(1, JSONObject(accepted.bodyAsText()).getInt("wake_words"))
                assertTrue(catalog.exportImported().isEmpty())
                assertEquals("contract-panel", fixture.config.panelId)
            }
        }
    }

    @Test fun `full mount refuses wake word restore when catalogue is unavailable`() {
        val archive = archive(WakeWordBackup.encode(listOf(importedWakeWord("porch")))!!)
        PaneldServerHttpFixture().use { fixture ->
            testApplication {
                application { fixture.mount(this) }
                repeat(2) {
                    val response = client.post("/api/v1/restore?dry_run=1") { setBody(archive.readBytes()) }
                    assertEquals(response.bodyAsText(), HttpStatusCode.ServiceUnavailable, response.status)
                    assertEquals("wake word restore is unavailable", JSONObject(response.bodyAsText()).getString("error"))
                }
            }
        }
    }

    @Test fun `full mount migration proves install identity or an exact verified retired receipt`() {
        val migrating = object : IdentityMigrationSurface { override fun restoreOpen() = true }
        PaneldServerHttpFixture(identityMigration = migrating).use { fixture ->
            val uid = fixture.config.ensureDeviceUid { "0123456789abcdef0123456789abcdef" }
            fun backup(discoveryId: String) = """{"kind":"ha-paneld-backup","schema":1,"config":{},"discovery_id":"$discoveryId"}"""
            val current = backup(panelAssistantDiscoveryId(uid)!!)
            val legacy = backup("f".repeat(64))
            testApplication {
                application { fixture.mount(this) }
                val accepted = client.post("/api/v1/restore?dry_run=1&mode=migration") { setBody(current) }
                assertEquals(accepted.bodyAsText(), HttpStatusCode.OK, accepted.status)
                val refused = client.post("/api/v1/restore?dry_run=1&mode=migration") { setBody(legacy) }
                assertEquals(HttpStatusCode.UnprocessableEntity, refused.status)
                assertEquals("migration-backup-not-from-this-device", JSONObject(refused.bodyAsText()).getString("error"))
                val receipt = folder.newFile().apply { writeText(legacy) }
                val state = MigrationState.of(fixture.context)
                val hash = AppInstaller.sha256(receipt)
                state.record(Step.PULL, hash)
                state.record(Step.VERIFY, hash)
                state.record(Step.RELEASE, "released")
                val retired = client.post("/api/v1/restore?dry_run=1&mode=migration") { setBody(legacy) }
                assertEquals(retired.bodyAsText(), HttpStatusCode.OK, retired.status)
                val changed = client.post("/api/v1/restore?dry_run=1&mode=migration") { setBody("$legacy ") }
                assertEquals(HttpStatusCode.UnprocessableEntity, changed.status)
            }
        }
    }

    private fun archive(text: String): File {
        val entry = folder.newFile().apply { writeText(text) }
        val section = WakeWordBackup.manifestFragment(entry.length(), 1)
        return folder.newFile().apply {
            outputStream().use {
                PanelBackup.writeArchive(
                    it,
                    """{"kind":"ha-paneld-backup","schema":1,"config":{},"wake_words":$section}""",
                    listOf(PanelBackup.ArchiveSource(WakeWordBackup.ENTRY, entry)),
                )
            }
        }
    }
}

internal fun wakeWordCatalog(directory: File, accepts: Boolean = true) = WakeWordCatalog(
    bundled = object : WakeWordCatalog.BundledModels {
        override fun ids() = emptyList<String>()
        override fun manifest(id: String): String = error("no bundled words")
        override fun model(file: String): ByteArray = error("no bundled words")
    },
    importDir = directory,
    accepts = { _, _ -> accepts },
)

internal fun importedWakeWord(id: String) = WakeWordCatalog.ImportedFiles(
    id,
    """{"type":"micro","wake_word":"$id","author":"me","model":"$id.tflite","trained_languages":["en"],"version":2,
        "micro":{"probability_cutoff":0.97,"feature_step_size":10,"sliding_window_size":5,"tensor_arena_size":26080}}""".toByteArray(),
    byteArrayOf(1, 2, 3),
)
