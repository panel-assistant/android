package io.panelassistant.android.util

import java.io.File
import java.io.FileOutputStream
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption

/**
 * Filesystem operations used by [DurableRecoveryMarker].
 *
 * Kept injectable so the ordering and failure contract can be proved on the host JVM without
 * depending on the host filesystem's interpretation of directory fsync.
 */
internal interface RecoveryMarkerPersistence {
    fun isFile(file: File): Boolean
    fun createDirectories(directory: File)
    fun writeAndSync(file: File, contents: ByteArray)
    fun replaceAtomically(source: File, target: File)
    fun syncDirectory(directory: File)
    fun delete(file: File): Boolean
}

private object FileRecoveryMarkerPersistence : RecoveryMarkerPersistence {
    override fun isFile(file: File): Boolean = file.isFile

    override fun createDirectories(directory: File) {
        Files.createDirectories(directory.toPath())
    }

    override fun writeAndSync(file: File, contents: ByteArray) {
        FileOutputStream(file, false).use { output ->
            output.write(contents)
            output.fd.sync()
        }
    }

    override fun replaceAtomically(source: File, target: File) {
        Files.move(
            source.toPath(),
            target.toPath(),
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING,
        )
    }

    override fun syncDirectory(directory: File) {
        FileChannel.open(directory.toPath(), StandardOpenOption.READ).use { it.force(true) }
    }

    override fun delete(file: File): Boolean = Files.deleteIfExists(file.toPath())
}

/** Small no-backup marker for a platform mutation that must be retried after process death. */
internal class DurableRecoveryMarker(
    private val file: File,
    private val persistence: RecoveryMarkerPersistence = FileRecoveryMarkerPersistence,
) {
    private val parent: File = requireNotNull(file.parentFile) { "recovery marker needs a parent" }
    private val temporary: File = parent.resolve(".${file.name}.tmp")

    fun isArmed(): Boolean = persistence.isFile(file)

    /**
     * Publish only after the marker contents are stable, then make the directory entry durable.
     *
     * An existing marker is already fail-safe. Syncing its directory closes the small window where a
     * prior process completed the atomic rename but died before syncing that rename.
     */
    @Synchronized
    fun arm(): Boolean = runCatching {
        persistence.createDirectories(parent)
        if (persistence.isFile(file)) {
            persistence.syncDirectory(parent)
            return@runCatching true
        }
        persistence.writeAndSync(temporary, MARKER_CONTENTS)
        persistence.replaceAtomically(temporary, file)
        persistence.syncDirectory(parent)
        true
    }.getOrElse {
        runCatching { persistence.delete(temporary) }
        false
    }

    /**
     * Clearing is complete only once both the unlink and its containing directory are durable.
     *
     * If the directory sync fails after unlink, reporting failure is intentional: a power loss may
     * resurrect the marker, which safely causes the platform recovery to be retried.
     */
    @Synchronized
    fun clear(): Boolean = runCatching {
        if (!persistence.isFile(file)) return@runCatching true
        if (!persistence.delete(file)) return@runCatching false
        persistence.syncDirectory(parent)
        true
    }.getOrDefault(false)

    /** Clear only after [recover] proves the platform state was restored. */
    @Synchronized
    fun recoverIfArmed(recover: () -> Boolean): Boolean {
        if (!isArmed()) return true
        if (!recover()) return false
        return clear()
    }

    private companion object {
        val MARKER_CONTENTS = byteArrayOf(1)
    }
}
