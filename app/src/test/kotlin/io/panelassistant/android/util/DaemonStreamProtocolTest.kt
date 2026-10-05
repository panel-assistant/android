package io.panelassistant.android.util

import io.panelassistant.android.platform.DaemonStreamResult
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedReader
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.InputStreamReader
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class DaemonStreamProtocolTest {
    private fun replies(value: String) = BufferedReader(InputStreamReader(ByteArrayInputStream(value.toByteArray())))

    @Test fun payloadIsOpenedAndWrittenOnlyAfterReady() {
        val output = ByteArrayOutputStream()
        var opened = false
        var shutdown = false

        val result = DaemonStreamProtocol.exchange(
            command = "INSTALLSTREAM 4",
            openSource = {
                opened = true
                ByteArrayInputStream(byteArrayOf(0x50, 0x4b, 3, 4))
            },
            expectedBytes = 4,
            replies = replies("READY\nOK\n"),
            output = output,
            shutdownOutput = { shutdown = true },
        )

        assertEquals(DaemonStreamResult.Reply("OK"), result)
        assertTrue(opened)
        assertTrue(shutdown)
        assertArrayEquals("INSTALLSTREAM 4\n".toByteArray() + byteArrayOf(0x50, 0x4b, 3, 4), output.toByteArray())
    }

    @Test fun legacyErrDoesNotOpenOrWritePayload() {
        val output = ByteArrayOutputStream()
        var opened = false

        val result = DaemonStreamProtocol.exchange(
            command = "INSTALLSTREAM 4",
            openSource = { opened = true; ByteArrayInputStream(byteArrayOf(1, 2, 3, 4)) },
            expectedBytes = 4,
            replies = replies("ERR\n"),
            output = output,
            shutdownOutput = { error("must not half-close before READY") },
        )

        assertEquals(DaemonStreamResult.Unsupported, result)
        assertFalse(opened)
        assertArrayEquals("INSTALLSTREAM 4\n".toByteArray(), output.toByteArray())
    }

    @Test fun supportedPreflightFailureIsTerminalWithoutPayload() {
        listOf("BUSY", "STREAMERR").forEach { reply ->
            val output = ByteArrayOutputStream()
            var opened = false

            val result = DaemonStreamProtocol.exchange(
                command = "INSTALLSTREAM 4",
                openSource = { opened = true; ByteArrayInputStream(byteArrayOf(1)) },
                expectedBytes = 4,
                replies = replies("$reply\n"),
                output = output,
                shutdownOutput = { error("must not half-close before READY") },
            )

            assertEquals(DaemonStreamResult.Reply(reply), result)
            assertFalse(opened)
            assertArrayEquals("INSTALLSTREAM 4\n".toByteArray(), output.toByteArray())
        }
    }

    @Test fun missingHandshakeOrTerminalReplyIsIndeterminate() {
        val noHandshake = DaemonStreamProtocol.exchange(
            "INSTALLSTREAM 1",
            { ByteArrayInputStream(byteArrayOf(1)) },
            1,
            replies(""),
            ByteArrayOutputStream(),
            {},
        )
        val noTerminal = DaemonStreamProtocol.exchange(
            "INSTALLSTREAM 1",
            { ByteArrayInputStream(byteArrayOf(1)) },
            1,
            replies("READY\n"),
            ByteArrayOutputStream(),
            {},
        )

        assertEquals(DaemonStreamResult.Indeterminate, noHandshake)
        assertEquals(DaemonStreamResult.Indeterminate, noTerminal)
    }

    @Test fun shortPayloadFailsAfterWritingOnlyAvailableBytesWithoutHalfClose() {
        val output = ByteArrayOutputStream()
        var shutdown = false

        val failure = runCatching {
            DaemonStreamProtocol.exchange(
                command = "INSTALLSTREAM 4",
                openSource = { ByteArrayInputStream(byteArrayOf(1, 2, 3)) },
                expectedBytes = 4,
                replies = replies("READY\n"),
                output = output,
                shutdownOutput = { shutdown = true },
            )
        }.exceptionOrNull()

        assertEquals(EOFException::class.java, failure?.javaClass)
        assertArrayEquals("INSTALLSTREAM 4\n".toByteArray() + byteArrayOf(1, 2, 3), output.toByteArray())
        assertFalse(shutdown)
    }

    @Test fun overlongPayloadFailsAfterDeclaredBytesWithoutHalfClose() {
        val output = ByteArrayOutputStream()
        var shutdown = false

        val failure = runCatching {
            DaemonStreamProtocol.exchange(
                command = "INSTALLSTREAM 4",
                openSource = { ByteArrayInputStream(byteArrayOf(1, 2, 3, 4, 5)) },
                expectedBytes = 4,
                replies = replies("READY\n"),
                output = output,
                shutdownOutput = { shutdown = true },
            )
        }.exceptionOrNull()

        assertEquals(IllegalStateException::class.java, failure?.javaClass)
        assertArrayEquals("INSTALLSTREAM 4\n".toByteArray() + byteArrayOf(1, 2, 3, 4), output.toByteArray())
        assertFalse(shutdown)
    }

    @Test fun deadlineCanBeCancelledWithoutRunningTimeoutAction() {
        var timedOut = false
        StreamDeadline(60_000L) { timedOut = true }.close()
        assertFalse(timedOut)
    }

    @Test fun deadlineRunsTimeoutAction() {
        val timedOut = CountDownLatch(1)
        StreamDeadline(1L) { timedOut.countDown() }.use {
            assertTrue(timedOut.await(2L, TimeUnit.SECONDS))
        }
    }
}
