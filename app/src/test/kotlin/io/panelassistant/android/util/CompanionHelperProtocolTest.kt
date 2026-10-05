package io.panelassistant.android.util

import io.panelassistant.android.backup.CompanionRestore
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CompanionHelperProtocolTest {
    @get:Rule val temporary = TemporaryFolder()
    private val pkg = "io.homeassistant.companion.android"

    @Test fun `backup parses raw frames without consuming payload bytes as lines`() {
        val database = byteArrayOf(0, '\n'.code.toByte(), 0x7f, 4)
        val preference = "<map/>".toByteArray()
        val response = ByteArrayOutputStream().apply {
            write("BACKUP 2 ${database.size + preference.size}\n".toByteArray())
            write("FILE ${CompanionRestore.DATABASE_FILE} ${database.size}\n".toByteArray())
            write(database)
            write("FILE shared_prefs/session_0.xml ${preference.size}\n".toByteArray())
            write(preference)
            write("DONE RELAUNCH_ERR\n".toByteArray())
        }.toByteArray()
        val command = ByteArrayOutputStream()

        val result = CompanionHelperProtocol.backup(
            pkg,
            temporary.root,
            ByteArrayInputStream(response),
            command,
        ) as CompanionHelperProtocol.BackupResult.Success

        result.capture.use { capture ->
            assertFalse(capture.relaunched)
            assertArrayEquals(database, capture.files.getValue(CompanionRestore.DATABASE_FILE).readBytes())
            assertArrayEquals(preference, capture.files.getValue("shared_prefs/session_0.xml").readBytes())
        }
        assertArrayEquals("COMPANIONBACKUP $pkg\n".toByteArray(), command.toByteArray())
        assertTrue(temporary.root.listFiles().orEmpty().isEmpty())
    }

    @Test fun `backup rejects out of order or inconsistent frames and removes staged bytes`() {
        val response = (
            "BACKUP 2 2\n" +
                "FILE shared_prefs/session_0.xml 1\nx" +
                "FILE ${CompanionRestore.DATABASE_FILE} 1\ny" +
                "DONE\n"
            ).toByteArray()

        assertEquals(
            CompanionHelperProtocol.BackupResult.Indeterminate,
            CompanionHelperProtocol.backup(
                pkg,
                temporary.root,
                ByteArrayInputStream(response),
                ByteArrayOutputStream(),
            ),
        )
        assertTrue(temporary.root.listFiles().orEmpty().isEmpty())
    }

    @Test fun `restore advertises exact slots then streams present files in fixed order`() {
        val database = temporary.newFile("db").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val preference = temporary.newFile("session").apply { writeBytes(byteArrayOf(4, 5)) }
        val output = ByteArrayOutputStream()
        var shutdown = false

        val result = CompanionHelperProtocol.restore(
            pkg,
            mapOf(
                CompanionRestore.DATABASE_FILE to database,
                "shared_prefs/session_0.xml" to preference,
            ),
            ByteArrayInputStream("READY\nOK\n".toByteArray()),
            output,
            shutdownOutput = { shutdown = true },
        )

        assertEquals(CompanionHelperProtocol.RestoreResult.COMMITTED, result)
        assertTrue(shutdown)
        val expected = ByteArrayOutputStream().apply {
            write("COMPANIONRESTORE $pkg 3 2 -\n".toByteArray())
            write(byteArrayOf(1, 2, 3, 4, 5))
        }.toByteArray()
        assertArrayEquals(expected, output.toByteArray())
    }

    @Test fun `restore preserves rollback and relaunch terminal distinctions`() {
        val database = temporary.newFile("db-terminal").apply { writeBytes(byteArrayOf(1)) }
        val result = CompanionHelperProtocol.restore(
            pkg,
            mapOf(CompanionRestore.DATABASE_FILE to database),
            ByteArrayInputStream("READY\nROLLBACK_FAILED RELAUNCH_SUPPRESSED\n".toByteArray()),
            ByteArrayOutputStream(),
            shutdownOutput = {},
        )
        assertEquals(CompanionHelperProtocol.RestoreResult.ROLLBACK_FAILED_RELAUNCH_SUPPRESSED, result)
    }
}
