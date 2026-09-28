package io.github.maxlyth.hapaneld.backup

import io.github.maxlyth.hapaneld.assist.wakeword.WakeWordCatalog
import io.github.maxlyth.hapaneld.http.restoreOverallStatus
import io.github.maxlyth.hapaneld.http.wakeWordRestoreComponent
import io.github.maxlyth.hapaneld.http.wakeWordRestoreNote
import io.github.maxlyth.hapaneld.util.InstallProgress
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Imported wake words through the backup archive: written and read by the same [PanelBackup] container
 * and [WakeWordBackup] section functions the backup and restore routes use, and put back through the
 * catalogue's own import.
 */
class WakeWordBackupTest {
    @get:Rule
    val folder = TemporaryFolder()

    private var engineAccepts: (Int) -> Boolean = { true }

    private fun catalog(dir: File) = WakeWordCatalog(
        bundled = object : WakeWordCatalog.BundledModels {
            override fun ids() = listOf("okay_nabu")
            override fun manifest(id: String) = manifest("Okay Nabu", "okay_nabu.tflite")
            override fun model(file: String) = byteArrayOf(1)
        },
        importDir = dir,
        accepts = { buffer, _ -> engineAccepts(buffer.capacity()) },
    )

    /** Build an archive the way the backup route does: the section is an entry beside a manifest naming it. */
    private fun backup(source: WakeWordCatalog): File {
        val imported = source.exportImported()
        val text = WakeWordBackup.encode(imported)
        val archive = folder.newFile()
        val sources = ArrayList<PanelBackup.ArchiveSource>()
        val manifest = StringBuilder("{\"kind\":\"ha-paneld-backup\",\"schema\":2")
        if (text != null) {
            val entry = folder.newFile().apply { writeText(text) }
            sources += PanelBackup.ArchiveSource(WakeWordBackup.ENTRY, entry)
            manifest.append(",\"wake_words\":").append(WakeWordBackup.manifestFragment(entry.length(), imported.size))
        }
        archive.outputStream().use { PanelBackup.writeArchive(it, manifest.append('}').toString(), sources) }
        return archive
    }

    /** Read an archive the way the restore route does: declared entries only, then the section. */
    private fun restoreSection(archive: File): List<WakeWordCatalog.ImportedFiles> {
        val manifest = JSONObject(requireNotNull(PanelBackup.readManifest(archive, 1024 * 1024)))
        val section = manifest.optJSONObject("wake_words") ?: return emptyList()
        val entries = setOf(WakeWordBackup.declaredEntry(section))
        assertTrue(PanelBackup.extractArchive(archive, emptyList(), entries))
        return WakeWordBackup.read(archive, section, entries, folder.newFolder())
    }

    @Test fun `an imported wake word survives a backup and restore onto a panel without it`() {
        val source = catalog(folder.newFolder("source"))
        source.import("porch", manifest("Porch", "porch.tflite").toByteArray(), byteArrayOf(1, 2, 3))
        val target = catalog(folder.newFolder("target"))

        val outcome = WakeWordBackup.restore(target, restoreSection(backup(source)))

        assertEquals(listOf("porch"), outcome.restored)
        assertEquals(listOf("okay_nabu", "porch"), target.available().map { it.id })
        assertEquals("Porch", target.available().last().wakeWord)
        val restored = target.exportImported().single()
        assertArrayEquals(byteArrayOf(1, 2, 3), restored.model)
        assertEquals(InstallProgress.Outcome.SUCCEEDED, wakeWordRestoreComponent(outcome).status)
        assertEquals(1, wakeWordRestoreComponent(outcome).items)
    }

    @Test fun `a model the engine refuses on restore is reported and the rest are restored`() {
        val source = catalog(folder.newFolder("source"))
        source.import("porch", manifest("Porch", "porch.tflite").toByteArray(), byteArrayOf(1, 2, 3))
        source.import("garden", manifest("Garden", "garden.tflite").toByteArray(), byteArrayOf(4, 5))
        val target = catalog(folder.newFolder("target"))
        engineAccepts = { size -> size != 3 }

        val outcome = WakeWordBackup.restore(target, restoreSection(backup(source)))

        assertEquals(listOf("garden"), outcome.restored)
        assertEquals(listOf("porch"), outcome.refused.map { it.first })
        assertEquals(listOf("okay_nabu", "garden"), target.available().map { it.id })
        val component = wakeWordRestoreComponent(outcome)
        assertEquals(InstallProgress.Outcome.PARTIAL, component.status)
        assertEquals(1, component.items)
        assertTrue(component.detail, component.detail.startsWith("not restored: porch: "))
        assertEquals("; 1 wake word not restored (porch)", wakeWordRestoreNote(outcome))
        // Not a success overall: a migration must not retire the old install while this model is missing.
        assertEquals(InstallProgress.Outcome.PARTIAL, restoreOverallStatus(outcome))
        assertEquals(InstallProgress.Outcome.SUCCEEDED, restoreOverallStatus(WakeWordBackup.restore(catalog(folder.newFolder("again")), emptyList())))
        assertEquals(InstallProgress.Outcome.SUCCEEDED, restoreOverallStatus(null))
    }

    @Test fun `a model that cannot be read fails the backup instead of being left out`() {
        val dir = folder.newFolder("source")
        val source = catalog(dir)
        source.import("porch", manifest("Porch", "porch.tflite").toByteArray(), byteArrayOf(1, 2, 3))
        File(dir, "porch/porch.tflite").delete()
        File(dir, "porch/porch.tflite").mkdir() // present but unreadable as a file

        val failure = runCatching { source.exportImported() }.exceptionOrNull()

        assertTrue("expected an IOException, got $failure", failure is java.io.IOException)
        assertTrue(failure!!.message!!.contains("porch"))
    }

    @Test fun `a panel without imports writes no wake word section`() {
        val archive = backup(catalog(folder.newFolder("source")))

        val manifest = JSONObject(requireNotNull(PanelBackup.readManifest(archive, 1024 * 1024)))
        assertFalse(manifest.has("wake_words"))
        assertNull(WakeWordBackup.encode(emptyList()))
        // Only the manifest: an archive an older build, which knows no wake word entry, still accepts.
        assertTrue(PanelBackup.extractArchive(archive, emptyList(), emptySet()))
    }

    @Test fun `a section naming another entry or an impossible size is refused`() {
        val wrongEntry = JSONObject().put("entry", "voice/other.json").put("size", 10)
        val tooLarge = JSONObject().put("entry", WakeWordBackup.ENTRY).put("size", WakeWordBackup.MAX_ENTRY_BYTES + 1)

        assertTrue(runCatching { WakeWordBackup.declaredEntry(wrongEntry) }.isFailure)
        assertTrue(runCatching { WakeWordBackup.declaredEntry(tooLarge) }.isFailure)
    }

    private fun manifest(phrase: String, model: String) = """
        {"type":"micro","wake_word":"$phrase","author":"me","model":"$model","trained_languages":["en"],"version":2,
         "micro":{"probability_cutoff":0.97,"feature_step_size":10,"sliding_window_size":5,"tensor_arena_size":26080}}
    """.trimIndent()
}
