package io.panelassistant.android.util

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StagedFilesTest {
    private fun tempFile(): File = File.createTempFile("staged-files-test-", ".tmp").apply { writeText("x") }

    @Test fun uncommittedFileIsDeletedOnNormalReturn() {
        val file = tempFile()
        val result = withStagedFiles { staged ->
            staged.stage(file)
            "done"
        }
        assertEquals("done", result)
        assertFalse(file.exists())
    }

    @Test fun committedFileIsKeptOnSuccess() {
        val file = tempFile()
        try {
            withStagedFiles { staged ->
                staged.stage(file)
                staged.commit()
            }
            assertTrue(file.exists())
        } finally {
            file.delete()
        }
    }

    @Test fun uncommittedFileIsDeletedOnThrow() {
        val file = tempFile()
        val error = runCatching {
            withStagedFiles<Unit> { staged ->
                staged.stage(file)
                throw IllegalStateException("boom")
            }
        }.exceptionOrNull()
        assertTrue(error is IllegalStateException)
        assertFalse(file.exists())
    }

    @Test fun stageReturnsTheFileForFluentUse() {
        val file = tempFile()
        withStagedFiles { staged ->
            val same = staged.stage(file)
            assertEquals(file, same)
        }
        assertFalse(file.exists())
    }

    @Test fun multipleFilesAreAllDeletedWhenNotCommitted() {
        val a = tempFile()
        val b = tempFile()
        val c = tempFile()
        withStagedFiles { staged ->
            staged.stage(a)
            staged.stage(b)
            staged.stage(c)
        }
        assertFalse(a.exists())
        assertFalse(b.exists())
        assertFalse(c.exists())
    }

    @Test fun ownershipTransferKeepsAllStagedFilesOnCommit() {
        val a = tempFile()
        val b = tempFile()
        try {
            withStagedFiles { staged ->
                staged.stage(a)
                staged.stage(b)
                staged.commit()
            }
            assertTrue(a.exists())
            assertTrue(b.exists())
        } finally {
            a.delete()
            b.delete()
        }
    }

    @Test fun ownershipNotTransferredDeletesAllStagedFilesOnEarlyReturn() {
        val a = tempFile()
        val result = withStagedFiles { staged ->
            staged.stage(a)
            if (a.exists()) return@withStagedFiles "rejected"
            "accepted"
        }
        assertEquals("rejected", result)
        assertFalse(a.exists())
    }
}
