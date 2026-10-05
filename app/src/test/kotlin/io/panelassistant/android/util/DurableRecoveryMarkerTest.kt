package io.panelassistant.android.util

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DurableRecoveryMarkerTest {
    @Test fun failedRecoveryKeepsMarkerForTheNextProcess() {
        val dir = Files.createTempDirectory("navbar-recovery-test").toFile()
        try {
            val marker = DurableRecoveryMarker(dir.resolve("pending"))
            assertTrue(marker.arm())

            assertFalse(marker.recoverIfArmed { false })
            assertTrue(marker.isArmed())
            assertTrue(DurableRecoveryMarker(dir.resolve("pending")).isArmed())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test fun successfulRecoveryClearsMarkerDurably() {
        val dir = Files.createTempDirectory("navbar-recovery-test").toFile()
        try {
            val file = dir.resolve("pending")
            val marker = DurableRecoveryMarker(file)
            assertTrue(marker.arm())
            assertTrue(marker.recoverIfArmed { true })
            assertFalse(marker.isArmed())
            assertTrue(DurableRecoveryMarker(file).recoverIfArmed { error("already resolved") })
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test fun armSyncsContentsBeforeAtomicPublicationAndDirectory() {
        val persistence = RecordingPersistence()
        val marker = DurableRecoveryMarker(File("/state/pending"), persistence)

        assertTrue(marker.arm())
        assertEquals(
            listOf(
                "mkdir:/state",
                "isFile:/state/pending",
                "writeSync:/state/.pending.tmp:1",
                "rename:/state/.pending.tmp:/state/pending",
                "syncDir:/state",
            ),
            persistence.operations,
        )
        assertTrue(marker.isArmed())
    }

    @Test fun existingMarkerStillSyncsItsDirectory() {
        val persistence = RecordingPersistence(files = mutableSetOf("/state/pending"))
        val marker = DurableRecoveryMarker(File("/state/pending"), persistence)

        assertTrue(marker.arm())
        assertEquals(
            listOf("mkdir:/state", "isFile:/state/pending", "syncDir:/state"),
            persistence.operations,
        )
    }

    @Test fun failedDirectorySyncAfterPublicationReportsFailureAndLeavesMarkerArmed() {
        val persistence = RecordingPersistence(failAt = "syncDir:/state")
        val marker = DurableRecoveryMarker(File("/state/pending"), persistence)

        assertFalse(marker.arm())
        assertTrue(marker.isArmed())
        assertEquals(
            listOf(
                "mkdir:/state",
                "isFile:/state/pending",
                "writeSync:/state/.pending.tmp:1",
                "rename:/state/.pending.tmp:/state/pending",
                "syncDir:/state",
                "delete:/state/.pending.tmp",
                "isFile:/state/pending",
            ),
            persistence.operations,
        )
    }

    @Test fun clearSyncsDirectoryAfterUnlink() {
        val persistence = RecordingPersistence(files = mutableSetOf("/state/pending"))
        val marker = DurableRecoveryMarker(File("/state/pending"), persistence)

        assertTrue(marker.clear())
        assertEquals(
            listOf("isFile:/state/pending", "delete:/state/pending", "syncDir:/state"),
            persistence.operations,
        )
        assertFalse(marker.isArmed())
    }

    @Test fun failedDirectorySyncAfterUnlinkReportsFailure() {
        val persistence = RecordingPersistence(
            files = mutableSetOf("/state/pending"),
            failAt = "syncDir:/state",
        )
        val marker = DurableRecoveryMarker(File("/state/pending"), persistence)

        assertFalse(marker.clear())
        assertFalse(marker.isArmed())
    }

    @Test fun failedRecoveryDoesNotAttemptToRemoveMarker() {
        val persistence = RecordingPersistence(files = mutableSetOf("/state/pending"))
        val marker = DurableRecoveryMarker(File("/state/pending"), persistence)

        assertFalse(marker.recoverIfArmed { false })
        assertEquals(listOf("isFile:/state/pending"), persistence.operations)
        assertTrue(marker.isArmed())
    }

    private class RecordingPersistence(
        val files: MutableSet<String> = mutableSetOf(),
        private val failAt: String? = null,
    ) : RecoveryMarkerPersistence {
        val operations = mutableListOf<String>()

        private fun record(operation: String) {
            operations += operation
            if (operation == failAt) error("injected failure at $operation")
        }

        override fun isFile(file: File): Boolean {
            record("isFile:${file.path}")
            return file.path in files
        }

        override fun createDirectories(directory: File) {
            record("mkdir:${directory.path}")
        }

        override fun writeAndSync(file: File, contents: ByteArray) {
            record("writeSync:${file.path}:${contents.single()}")
            files += file.path
        }

        override fun replaceAtomically(source: File, target: File) {
            record("rename:${source.path}:${target.path}")
            check(files.remove(source.path))
            files += target.path
        }

        override fun syncDirectory(directory: File) {
            record("syncDir:${directory.path}")
        }

        override fun delete(file: File): Boolean {
            record("delete:${file.path}")
            return files.remove(file.path)
        }
    }
}
