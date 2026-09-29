package io.github.maxlyth.hapaneld.http

import io.github.maxlyth.hapaneld.backup.PanelBackup
import io.github.maxlyth.hapaneld.util.CompanionHelperProtocol
import java.io.File

/** Conservative disk peak while source entries, archive plaintext and optional ciphertext overlap. */
internal fun backupStagingRequirement(includeCompanion: Boolean, encrypted: Boolean): Long {
    val sources = PaneldServer.MAX_BACKUP_MANIFEST_BYTES +
        2L * PaneldServer.MAX_ENTITY_BACKUP_TEXT_BYTES +
        PaneldServer.MAX_PROFILE_BACKUP_ENTRY_BYTES +
        if (includeCompanion) PaneldServer.MAX_COMPANION_BACKUP_BYTES else 0L
    val archives = PaneldServer.MAX_RESTORE_BYTES * if (encrypted) 2L else 1L
    val archivePeak = sources + archives
    val rawCapturePeak = if (includeCompanion) CompanionHelperProtocol.MAX_BACKUP_STREAM_BYTES else 0L
    return PaneldServer.BACKUP_STORAGE_MARGIN_BYTES + maxOf(archivePeak, rawCapturePeak)
}

internal class BackupStagingRetainedException : Exception("sensitive backup staging file retained")

/** Attempt one bounded cleanup without allowing it to replace an earlier backup failure. */
internal inline fun attemptBackupCleanup(primary: Exception?, cleanup: () -> Unit): Exception? = try {
    cleanup()
    primary
} catch (failure: Exception) {
    if (primary == null) failure else primary.apply {
        if (failure !== this) addSuppressed(failure)
    }
}

internal inline fun <R> withBackupArtifactCleanup(
    plain: File,
    sealed: () -> File?,
    ownedFiles: () -> List<File>,
    block: () -> R,
): R {
    var primary: Exception? = null
    var failed = false
    try {
        return block()
    } catch (error: Exception) {
        failed = true
        primary = error
        primary = attemptBackupCleanup(primary) { plain.delete() }
        primary = attemptBackupCleanup(primary) { sealed()?.delete() }
        throw error
    } finally {
        ownedFiles().forEach { file ->
            primary = attemptBackupCleanup(primary) { file.delete() }
        }
        if (!failed) primary?.let { throw it }
    }
}

internal inline fun <T : java.io.Closeable, R> withBackupCaptureAndPlaintext(
    capture: T?,
    createPlaintext: () -> File,
    block: (T?, File) -> R,
): R {
    var primary: Exception? = null
    try {
        return block(capture, createPlaintext())
    } catch (error: Exception) {
        primary = error
        throw error
    } finally {
        val failure = attemptBackupCleanup(primary) { capture?.close() }
        if (primary == null && failure != null) throw failure
    }
}

internal fun encryptedBackupArtifact(
    plain: File,
    sealed: File,
    stateUnavailable: Boolean = false,
): PanelBackup.Artifact {
    val retained = runCatching {
        plain.delete()
        plain.exists()
    }.getOrDefault(true)
    if (retained) {
        // The encrypted temp is never returned, even if its best-effort cleanup also fails.
        runCatching { sealed.delete() }
        throw BackupStagingRetainedException()
    }
    return PanelBackup.Artifact(sealed, stateUnavailable = stateUnavailable)
}

/** Keep room for the received envelope, authenticated plaintext, and extracted Companion payloads. */
internal fun restoreBodyStagingLimit(
    usableBytes: Long,
    maxPayloadBytes: Long = PaneldServer.MAX_RESTORE_BYTES,
    safetyMarginBytes: Long = 64L * 1024L * 1024L,
): Long {
    if (usableBytes <= safetyMarginBytes || maxPayloadBytes <= 0L) return 0L
    return minOf(maxPayloadBytes, (usableBytes - safetyMarginBytes) / 3L)
}
