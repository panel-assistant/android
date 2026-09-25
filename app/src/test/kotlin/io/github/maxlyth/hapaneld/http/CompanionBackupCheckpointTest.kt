package io.github.maxlyth.hapaneld.http

import io.github.maxlyth.hapaneld.backup.CompanionRestore
import io.github.maxlyth.hapaneld.backup.PanelBackup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CompanionBackupCheckpointTest {
    @Test fun `legacy object materialization stays far below streamed archive payload ceiling`() {
        assertTrue(PaneldServer.MAX_BACKUP_MANIFEST_BYTES < PaneldServer.MAX_RESTORE_BYTES)
        assertTrue(PaneldServer.MAX_LEGACY_BACKUP_JSON_BYTES < PaneldServer.MAX_RESTORE_BYTES / 2L)
        assertTrue(PaneldServer.MAX_BACKUP_MANIFEST_BYTES <= 1L * 1024L * 1024L)
        assertTrue(PaneldServer.MAX_PROFILE_BACKUP_ENTRY_BYTES >= 2L * 4L * 1024L * 1024L)
        assertEquals(13_000_000L, PaneldServer.MAX_ENTITY_BACKUP_TEXT_BYTES)
    }

    @Test fun `self-generated backup ceilings are symmetric with restore admission`() {
        assertEquals(CompanionRestore.MAX_AGGREGATE_BYTES, PaneldServer.MAX_COMPANION_BACKUP_BYTES)
        assertEquals(
            PaneldServer.MAX_RESTORE_BYTES - PanelBackup.SEALED_OVERHEAD_BYTES,
            PanelBackup.maxSealablePlaintextBytes(PaneldServer.MAX_RESTORE_BYTES),
        )
    }

    @Test fun `backup storage admission covers sources plaintext ciphertext and raw Companion frames`() {
        val configPlain = backupStagingRequirement(includeCompanion = false, encrypted = false)
        val configEncrypted = backupStagingRequirement(includeCompanion = false, encrypted = true)
        val companionEncrypted = backupStagingRequirement(includeCompanion = true, encrypted = true)
        assertTrue(configPlain > PaneldServer.BACKUP_STORAGE_MARGIN_BYTES + PaneldServer.MAX_RESTORE_BYTES)
        assertEquals(PaneldServer.MAX_RESTORE_BYTES, configEncrypted - configPlain)
        assertTrue(companionEncrypted > configEncrypted)
        assertTrue(
            companionEncrypted >=
                PaneldServer.BACKUP_STORAGE_MARGIN_BYTES +
                io.github.maxlyth.hapaneld.util.CompanionHelperProtocol.MAX_BACKUP_STREAM_BYTES,
        )
    }
}
