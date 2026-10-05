package io.panelassistant.android.http

import java.io.Closeable
import java.io.File
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class BackupStagingCleanupTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun acquiredCaptureIsReleasedWhenTheFollowingAllocationFails() {
        var releases = 0
        val capture = Closeable { releases++ }

        val failure = assertThrows(IOException::class.java) {
            withBackupCaptureAndPlaintext(
                capture,
                createPlaintext = { throw IOException("plain staging allocation failed") },
            ) { _, _ -> error("allocation failure must prevent the backup build") }
        }

        assertEquals("plain staging allocation failed", failure.message)
        assertEquals(1, releases)
    }

    @Test fun retainedFailureRemainsPrimaryAcrossLaterCleanupFailures() {
        val retained = BackupStagingRetainedException()
        val generic = IOException("generic cleanup failed")
        val sealed = IOException("sealed cleanup failed")
        val owned = IOException("owned cleanup failed")
        val attempted = mutableListOf<String>()
        fun failingFile(name: String, failure: IOException): File = object : File(name) {
            override fun delete(): Boolean {
                attempted += name
                throw failure
            }
        }

        val failure = assertThrows(BackupStagingRetainedException::class.java) {
            withBackupArtifactCleanup(
                plain = failingFile("generic", generic),
                sealed = { failingFile("sealed", sealed) },
                ownedFiles = { listOf(failingFile("owned", owned)) },
            ) { throw retained }
        }

        assertSame(retained, failure)
        assertEquals(listOf("generic", "sealed", "owned"), attempted)
        assertEquals(listOf(generic, sealed, owned), failure.suppressed.toList())
    }

    @Test fun encryptedArtifactIsReturnedOnlyAfterPlaintextRemoval() {
        val plain = temporary.newFile("backup.zip").apply { writeText("secret") }
        val sealed = temporary.newFile("backup.hpb").apply { writeText("sealed") }

        val artifact = encryptedBackupArtifact(plain, sealed)

        assertFalse(plain.exists())
        assertSame(sealed, artifact.file)
    }

    @Test fun survivingPlaintextWithdrawsTheEncryptedArtifact() {
        val source = temporary.newFile("retained.zip").apply { writeText("secret") }
        val retained = object : File(source.path) {
            override fun delete(): Boolean = false
        }
        val sealed = temporary.newFile("withdrawn.hpb").apply { writeText("sealed") }

        assertThrows(BackupStagingRetainedException::class.java) {
            encryptedBackupArtifact(retained, sealed)
        }

        assertTrue(source.exists())
        assertFalse(sealed.exists())
    }
}
