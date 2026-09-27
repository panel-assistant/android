package io.github.maxlyth.hapaneld.util

import io.github.maxlyth.hapaneld.platform.Daemon
import io.github.maxlyth.hapaneld.platform.DaemonLongResult
import io.github.maxlyth.hapaneld.platform.DaemonStreamResult
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class HelperInstallTransactionTest {
    @get:Rule val temporary = TemporaryFolder()

    private class LongDaemon(
        var result: DaemonLongResult,
        var streamResult: DaemonStreamResult = DaemonStreamResult.Unsupported,
        private val onStream: (String, File, Long) -> Unit = { _, _, _ -> },
        private val onSend: (String) -> String? = { null },
        private val onCall: (String, Long) -> Unit = { _, _ -> },
    ) : Daemon {
        val calls = mutableListOf<Pair<String, Long>>()
        val shortCalls = mutableListOf<String>()
        val streamCalls = mutableListOf<Triple<String, File, Long>>()

        override fun available() = true
        override fun send(cmd: String): String? {
            shortCalls += cmd
            return onSend(cmd)
        }
        override fun sendLong(cmd: String, timeoutMs: Long): DaemonLongResult {
            calls += cmd to timeoutMs
            onCall(cmd, timeoutMs)
            return result
        }
        override fun sendFile(cmd: String, source: File, timeoutMs: Long): DaemonStreamResult {
            streamCalls += Triple(cmd, source, timeoutMs)
            onStream(cmd, source, timeoutMs)
            return streamResult
        }
        override fun sendBytes(cmd: String): ByteArray? = null
    }

    private fun apk(name: String, bytes: ByteArray): File =
        temporary.newFile(name).apply { writeBytes(bytes) }

    @Test fun invalidLocalInputIsRejectedBeforeEitherDaemonProtocol() {
        val empty = apk("empty.apk", byteArrayOf())
        val directory = temporary.newFolder("invalid-staging")
        val daemon = LongDaemon(DaemonLongResult.Reply("OK"), streamResult = DaemonStreamResult.Reply("OK"))

        assertEquals(InstallOutcome.Retryable("install failed: invalid APK input"), HelperInstallTransaction(daemon).install(empty, directory))
        assertFalse(empty.exists())
        assertTrue(daemon.streamCalls.isEmpty())
        assertTrue(daemon.calls.isEmpty())
    }

    @Test fun streamSuccessUsesOriginalInputAndNeverCreatesLegacyStaging() {
        val source = apk("stream.apk", byteArrayOf(0x50, 0x4b, 3, 4))
        val directory = temporary.newFolder("stream-staging")
        val daemon = LongDaemon(
            result = DaemonLongResult.Reply("ERR"),
            streamResult = DaemonStreamResult.Reply("OK"),
            onStream = { cmd, input, timeout ->
                assertEquals("INSTALLSTREAM 4", cmd)
                assertEquals(source, input)
                assertArrayEquals(byteArrayOf(0x50, 0x4b, 3, 4), input.readBytes())
                assertEquals(180_000L, timeout)
            },
        )

        assertEquals(InstallOutcome.Succeeded, HelperInstallTransaction(daemon).install(source, directory))
        assertFalse(source.exists())
        assertTrue(directory.listFiles().orEmpty().isEmpty())
        assertTrue(daemon.calls.isEmpty())
    }

    @Test fun streamTerminalFailureAndDefiniteNonSubmissionReleaseInput() {
        listOf<Pair<DaemonStreamResult, InstallOutcome>>(
            DaemonStreamResult.Reply("ERR") to InstallOutcome.Rejected("install failed: daemon install failed"),
            DaemonStreamResult.Reply("BUSY") to InstallOutcome.Retryable("install failed: daemon busy"),
            DaemonStreamResult.Reply("STREAMERR") to InstallOutcome.Retryable("install failed: daemon stream staging failed"),
            DaemonStreamResult.NotSubmitted to InstallOutcome.Retryable("install failed: daemon unreachable"),
        ).forEachIndexed { index, (streamResult, expected) ->
            val source = apk("stream-failed-$index.apk", byteArrayOf(index.toByte(), 7))
            val directory = temporary.newFolder("stream-failed-staging-$index")
            val daemon = LongDaemon(DaemonLongResult.Reply("OK"), streamResult = streamResult)

            assertEquals(expected, HelperInstallTransaction(daemon).install(source, directory))
            assertFalse(source.exists())
            assertTrue(directory.listFiles().orEmpty().isEmpty())
            assertTrue(daemon.calls.isEmpty())
        }
    }

    @Test fun indeterminateStreamReleasesSourceBecauseSocketClosureEndsConsumption() {
        val source = apk("stream-indeterminate.apk", byteArrayOf(1, 2, 3))
        val directory = temporary.newFolder("stream-indeterminate-staging")
        val daemon = LongDaemon(
            result = DaemonLongResult.Reply("OK"),
            streamResult = DaemonStreamResult.Indeterminate,
        )

        assertEquals(
            InstallOutcome.Retryable("install outcome unknown: streamed input released", mayHaveCommitted = true),
            HelperInstallTransaction(daemon).install(source, directory),
        )
        assertFalse(source.exists())
        assertTrue(directory.listFiles().orEmpty().isEmpty())
        assertTrue(daemon.calls.isEmpty())
    }

    @Test fun terminalSuccessKeepsClaimedInputUntilReplyThenDeletesIt() {
        val source = apk("source.apk", byteArrayOf(1, 2, 3))
        val staging = temporary.newFolder("staging")
        val daemon = LongDaemon(DaemonLongResult.Reply("OK")) { cmd, timeout ->
            val owned = File(cmd.substringAfter("INSTALL "))
            assertFalse(source.exists())
            assertEquals(staging.canonicalFile, owned.parentFile?.canonicalFile)
            assertArrayEquals(byteArrayOf(1, 2, 3), owned.readBytes())
            assertEquals(180_000L, timeout)
        }

        assertEquals(InstallOutcome.Succeeded, HelperInstallTransaction(daemon).install(source, staging))
        assertTrue(staging.listFiles().orEmpty().isEmpty())
    }

    @Test fun terminalFailureAndDefiniteNonSubmissionReleaseStaging() {
        listOf<Pair<DaemonLongResult, InstallOutcome>>(
            DaemonLongResult.Reply("ERR") to InstallOutcome.Rejected("install failed: daemon install failed"),
            DaemonLongResult.NotSubmitted to InstallOutcome.Retryable("install failed: daemon unreachable"),
        ).forEachIndexed { index, (daemonResult, expected) ->
            val source = apk("failed-$index.apk", byteArrayOf(index.toByte(), 7))
            val staging = temporary.newFolder("failed-staging-$index")
            val daemon = LongDaemon(daemonResult)

            assertEquals(expected, HelperInstallTransaction(daemon).install(source, staging))
            assertFalse(source.exists())
            assertTrue(staging.listFiles().orEmpty().isEmpty())
        }
    }

    @Test fun indeterminateSubmissionRetainsUniqueImmutableInput() {
        val staging = temporary.newFolder("retained-staging")
        val firstSource = apk("first.apk", byteArrayOf(1, 1, 1))
        val daemon = LongDaemon(DaemonLongResult.Indeterminate)
        val transaction = HelperInstallTransaction(daemon)

        assertEquals(
            InstallOutcome.Retryable("install outcome unknown: helper staging retained for safety", mayHaveCommitted = true),
            transaction.install(firstSource, staging),
        )
        val retained = staging.listFiles().orEmpty().single()
        val retainedBytes = retained.readBytes()

        daemon.result = DaemonLongResult.NotSubmitted
        val secondSource = apk("second.apk", byteArrayOf(2, 2, 2))
        assertEquals(InstallOutcome.Retryable("install failed: daemon unreachable"), transaction.install(secondSource, staging))
        val secondPath = daemon.calls.last().first.substringAfter("INSTALL ")

        assertNotEquals(retained.absolutePath, secondPath)
        assertTrue(retained.exists())
        assertArrayEquals(retainedBytes, retained.readBytes())
        assertEquals(listOf(retained), staging.listFiles().orEmpty().toList())
    }

    @Test fun customTimeoutIsPassedToLongCall() {
        val source = apk("timeout.apk", byteArrayOf(9))
        val staging = temporary.newFolder("timeout-staging")
        val daemon = LongDaemon(DaemonLongResult.NotSubmitted)

        HelperInstallTransaction(daemon, timeoutMs = 42_000L).install(source, staging)

        assertEquals(42_000L, daemon.calls.single().second)
    }

    @Test fun stagingFailureDeletesUnclaimedInputWithoutSubmitting() {
        val source = apk("unstageable.apk", byteArrayOf(4, 5))
        val notDirectory = temporary.newFile("not-a-directory")
        val daemon = LongDaemon(DaemonLongResult.Reply("OK"))

        assertEquals(
            InstallOutcome.Retryable("install failed: could not claim helper staging"),
            HelperInstallTransaction(daemon).install(source, notDirectory),
        )
        assertFalse(source.exists())
        assertTrue(daemon.calls.isEmpty())
    }

    @Test fun reconciliationCannotSelectAnInputOwnedByThisProcess() {
        val source = apk("active.apk", byteArrayOf(1, 2, 3))
        val directory = temporary.newFolder("active-staging")
        val staging = HelperInstallStaging()
        lateinit var daemon: LongDaemon
        daemon = LongDaemon(DaemonLongResult.Reply("OK"), onCall = { _, _ ->
            val result = HelperInstallReconciler(daemon, staging).reconcile(directory)
            assertTrue(result.complete)
            assertTrue(daemon.shortCalls.isEmpty())
        })

        assertEquals(InstallOutcome.Succeeded, HelperInstallTransaction(daemon, staging = staging).install(source, directory))
    }

    @Test fun indeterminateInputIsRemovedOnlyAfterHelperAuthorisesLockedCleanup() {
        val source = apk("indeterminate.apk", byteArrayOf(4, 5, 6))
        val directory = temporary.newFolder("reconcile-staging")
        val staging = HelperInstallStaging()
        val daemon = LongDaemon(
            result = DaemonLongResult.Indeterminate,
            onSend = { cmd ->
                assertTrue(cmd.startsWith("INSTALLGC "))
                assertTrue(File(cmd.substringAfter("INSTALLGC ")).exists())
                "OK"
            },
        )

        assertEquals(
            InstallOutcome.Retryable("install outcome unknown: helper staging retained for safety", mayHaveCommitted = true),
            HelperInstallTransaction(daemon, staging = staging).install(source, directory),
        )
        assertEquals(1, directory.listFiles().orEmpty().size)

        val result = HelperInstallReconciler(daemon, staging).reconcile(directory)

        assertEquals(1, result.removed)
        assertEquals(0, result.remaining)
        assertTrue(result.complete)
        assertTrue(directory.listFiles().orEmpty().isEmpty())
    }

    @Test fun busyFailedOrUnreachableCleanupRetainsInputForRetry() {
        listOf("BUSY", "ERR", null).forEachIndexed { index, reply ->
            val source = apk("retained-$index.apk", byteArrayOf(index.toByte(), 9))
            val directory = temporary.newFolder("retained-reconcile-$index")
            val staging = HelperInstallStaging()
            val daemon = LongDaemon(DaemonLongResult.Indeterminate, onSend = { reply })
            HelperInstallTransaction(daemon, staging = staging).install(source, directory)

            val result = HelperInstallReconciler(daemon, staging).reconcile(directory)

            assertEquals(0, result.removed)
            assertEquals(1, result.remaining)
            assertFalse(result.complete)
            assertTrue(directory.listFiles().orEmpty().single().exists())
        }
    }

    @Test fun reconciliationIgnoresFilesOutsideItsOwnedNamePattern() {
        val directory = temporary.newFolder("unrelated-staging")
        File(directory, "user-upload.apk").writeBytes(byteArrayOf(7))
        File(directory, "${HelperInstallTransaction.STAGING_PREFIX}partial.tmp").writeBytes(byteArrayOf(8))
        val daemon = LongDaemon(DaemonLongResult.NotSubmitted)

        val result = HelperInstallReconciler(daemon, HelperInstallStaging()).reconcile(directory)

        assertTrue(result.complete)
        assertTrue(daemon.shortCalls.isEmpty())
        assertEquals(2, directory.listFiles().orEmpty().size)
    }
}
