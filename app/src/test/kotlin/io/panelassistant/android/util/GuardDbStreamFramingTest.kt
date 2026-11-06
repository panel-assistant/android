package io.panelassistant.android.util

import io.panelassistant.android.platform.DaemonLongResult
import io.panelassistant.android.platform.DaemonStreamResult
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GuardDbStreamFramingTest {
    private val session = "1".repeat(64)
    private val accepted = GuardDbMaintenanceProtocol.Result.Accepted(3L, GuardDbMaintenanceProtocol.Phase.STAGING)
    private val terminal = "OK GUARDSTREAM 3 STAGING"

    @Test fun malformedReadyNeverOpensPayloadOrHalfCloses() = withCandidate { candidate ->
        val cases = listOf(
            " READY\n", "READY \n", "READY\r\n", "\tREADY\n", "READY\u0000\n", "READY\u007f\n",
            "READY\u0080\n", "READY", "", "R".repeat(512) + "\n", "R".repeat(513) + "\n", "ERR\n",
        )
        for (wire in cases) {
            val transport = WireTransport(wire)
            val result = GuardDbMaintenanceClient(transport).stream(session, 2L, candidate)
            val expected = if (wire in listOf(" READY\n", "READY \n", "R".repeat(512) + "\n", "ERR\n")) {
                GuardDbMaintenanceProtocol.Result.Malformed
            } else GuardDbMaintenanceProtocol.Result.Indeterminate
            assertEquals(wire, expected, result)
            assertEquals(wire, 0, transport.opens)
            assertEquals(wire, 0, transport.halfCloses)
            assertExchange(transport, candidate, payloadSent = false)
        }
    }

    @Test fun terminalCorpusReachesGuardWithoutNormalization() = withCandidate { candidate ->
        for (wire in replyCorpus()) {
            val transport = WireTransport("READY\n" + wire)
            val result = GuardDbMaintenanceClient(transport).stream(session, 2L, candidate)
            assertEquals(wire, expectedStreamResult(wire), result)
            assertEquals(wire, 1, transport.opens)
            assertEquals(wire, 1, transport.halfCloses)
            assertExchange(transport, candidate, payloadSent = true)
        }
    }

    @Test fun shortRepliesUseTheSamePhysicalCorpusAndGuardGrammar() {
        for (wire in replyCorpus()) {
            val output = ByteArrayOutputStream()
            val raw = HelperSocketProtocol.sendLine("GUARDSTREAM probe", wireInput(wire), output)
            val expected = expectedStreamResult(wire).let {
                if (it == GuardDbMaintenanceProtocol.Result.Indeterminate) GuardDbMaintenanceProtocol.Result.Unreachable else it
            }
            assertEquals(wire, expected, GuardDbMaintenanceProtocol.parseMutationReply("GUARDSTREAM", raw))
            assertArrayEquals("GUARDSTREAM probe\n".toByteArray(), output.toByteArray())
        }
        // The physical limit is independent of Guard's narrower mutation grammar.
        val boundary = "X".repeat(512)
        assertEquals(boundary, HelperSocketProtocol.sendLine("PING", wireInput("$boundary\n"), ByteArrayOutputStream()))
    }

    @Test fun validSettingsUploadRetainsPayloadAndHalfCloseCustody() = withCandidate { candidate ->
        val authority = GuardDbSettingsAuthority(GuardDbSettingsAuthority.VERSION, candidate.file, candidate.bytes, candidate.sha256)
        val transport = WireTransport("READY\n$terminal\n")
        assertEquals(accepted, GuardDbMaintenanceClient(transport).streamSettings(session, 2L, authority))
        assertEquals(1, transport.calls)
        assertEquals(1, transport.opens)
        assertEquals(1, transport.halfCloses)
        assertArrayEquals(
            (GuardDbMaintenanceProtocol.streamSettings(session, 2L, authority) + "\n").toByteArray() + candidate.file.readBytes(),
            transport.output.toByteArray(),
        )
        assertTrue(candidate.file.exists())
    }

    private fun replyCorpus() = listOf(
        "$terminal\n", " $terminal\n", "$terminal \n", "$terminal\r\n", "\t$terminal\n",
        "$terminal\u0000\n", "$terminal\u007f\n", "$terminal\u0080\n", terminal, "",
        "X".repeat(512) + "\n", "X".repeat(513) + "\n",
    )

    private fun expectedStreamResult(wire: String): GuardDbMaintenanceProtocol.Result = when (wire) {
        "$terminal\n" -> accepted
        " $terminal\n", "$terminal \n", "X".repeat(512) + "\n" -> GuardDbMaintenanceProtocol.Result.Malformed
        else -> GuardDbMaintenanceProtocol.Result.Indeterminate
    }

    private fun assertExchange(transport: WireTransport, candidate: GuardDbMaintenanceProtocol.Candidate, payloadSent: Boolean) {
        assertEquals(1, transport.calls)
        val command = (GuardDbMaintenanceProtocol.stream(session, 2L, candidate) + "\n").toByteArray()
        assertArrayEquals(command + if (payloadSent) candidate.file.readBytes() else byteArrayOf(), transport.output.toByteArray())
        assertTrue(candidate.file.exists())
    }

    private class WireTransport(private val wire: String) : GuardDbMaintenanceTransport {
        val output = ByteArrayOutputStream()
        var calls = 0
        var opens = 0
        var halfCloses = 0

        override fun sendFile(command: String, file: File, timeoutMs: Long): DaemonStreamResult {
            calls++
            return HelperSocketProtocol.sendFile(
                command = command,
                openSource = { opens++; file.inputStream() },
                expectedBytes = file.length(),
                input = wireInput(wire),
                output = output,
                shutdownOutput = { halfCloses++ },
            )
        }

        override fun send(command: String): String? = error("unexpected short command")
        override fun sendLong(command: String, timeoutMs: Long): DaemonLongResult = error("unexpected long command")
        override fun sendBytesBounded(command: String, maxBytes: Long): ByteArray? = error("unexpected binary command")
    }

    private fun withCandidate(block: (GuardDbMaintenanceProtocol.Candidate) -> Unit) {
        val file = File.createTempFile("guard-framing-", ".apk").apply { writeBytes(byteArrayOf(0, 10, 13, 0x7f, -1)) }
        try {
            block(GuardDbMaintenanceProtocol.Candidate(
                GuardDbMaintenanceProtocol.Role.A, file, file.length(), "a".repeat(64), 568L, 11, 14, 14,
                GuardDbSettingsAuthority.VERSION, 3L, "b".repeat(64),
            ))
        } finally {
            file.delete()
        }
    }

    companion object {
        private fun wireInput(wire: String) = ByteArrayInputStream(wire.toByteArray(Charsets.ISO_8859_1))
    }
}
