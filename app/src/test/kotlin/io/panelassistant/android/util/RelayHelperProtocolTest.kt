package io.panelassistant.android.util

import io.panelassistant.android.platform.HelperGpioState
import io.panelassistant.android.platform.HelperRelayClass
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.SocketTimeoutException
import org.junit.Assert.*
import org.junit.Test

class RelayHelperProtocolTest {
    private val relayClass = HelperRelayClass.STRELAY
    private fun wire(command: String, reply: String?) = ZigbeeReadFixture().apply {
        version = "HELPER version=1.12.0 proto=1.12"
        replies[command] = reply?.toByteArray()
    }

    @Test fun admissionUsesCarryingConnectionAndEachVerbsMinimumMinor() {
        val wire = wire("RELAYREAD STRELAY COUNT", "RELAYREAD STRELAY COUNT=2\n")
        wire.afterVersion = { wire.version = "HELPER version=2.0.0 proto=2.0" }
        assertEquals(2, wire.client.relayCount(relayClass))
        assertNull(wire.client.relayCount(relayClass))
        assertEquals(listOf(Triple(1, "RELAYREAD STRELAY COUNT", 64L)), wire.bytes)
        assertEquals(2, wire.closed)
        for (version in listOf("ERR", "HELPER version=1.10.0 proto=1.10", "HELPER version=2.12.0 proto=2.12")) {
            val old = wire("RELAYREAD STRELAY 1", "RELAYREAD STRELAY RELAY=1 VALUE=1\n").apply { this.version = version }
            assertNull(old.client.relayRead(relayClass, 1)); assertTrue(old.bytes.isEmpty()); assertTrue(old.rawCommands.isEmpty())
        }
        val eleven = wire("GPIOREAD 147", "GPIOREAD 147 VALUE=1 PREPARED=1\n").apply { version = "HELPER version=1.11.0 proto=1.11" }
        eleven.replies["RELAYREAD STRELAY COUNT"] = "RELAYREAD STRELAY COUNT=1\n".toByteArray()
        assertEquals(1, eleven.client.relayCount(relayClass))
        assertNull(eleven.client.gpioRead(147))
        assertEquals(listOf("RELAYREAD STRELAY COUNT"), eleven.bytes.map { it.second })
    }

    @Test fun countRequiresCanonicalWholeFrameMatchingClassAndRange() {
        for (n in listOf(0, 1, 16)) assertEquals(n, wire("RELAYREAD STRELAY COUNT", "RELAYREAD STRELAY COUNT=$n\n").client.relayCount(relayClass))
        for (raw in listOf(null, "ERR\n", "RELAYREAD STRELAY COUNT=?\n", "RELAYREAD ST_RELAY COUNT=2\n", "RELAYREAD STRELAY COUNT=17\n", "RELAYREAD STRELAY COUNT=02\n", "RELAYREAD STRELAY COUNT=-1\n", "RELAYREAD STRELAY COUNT=2", "RELAYREAD STRELAY COUNT=2\r\n", "RELAYREAD STRELAY COUNT=2\nextra\n", " ".repeat(65))) {
            assertNull(raw, wire("RELAYREAD STRELAY COUNT", raw).client.relayCount(relayClass))
        }
    }

    @Test fun indexedReplyMustEchoCanonicalIndexAndValueUnknownStaysUnknown() {
        for ((flag, expected) in listOf("0" to false, "1" to true, "?" to null)) assertEquals(expected, wire("RELAYREAD STRELAY 16", "RELAYREAD STRELAY RELAY=16 VALUE=$flag\n").client.relayRead(relayClass, 16))
        for (raw in listOf("RELAYREAD STRELAY RELAY=2 VALUE=1\n", "RELAYREAD STRELAY RELAY=01 VALUE=1\n", "RELAYREAD RELAY RELAY=1 VALUE=1\n", "RELAYREAD STRELAY RELAY=1 VALUE=2\n", "RELAYREAD STRELAY RELAY=1 VALUE=1\n\n")) assertNull(raw, wire("RELAYREAD STRELAY 1", raw).client.relayRead(relayClass, 1))
        val invalid = wire("RELAYREAD STRELAY 0", "ERR\n")
        assertNull(invalid.client.relayRead(relayClass, 0)); assertNull(invalid.client.relayRead(relayClass, 17)); assertEquals(0, invalid.opened)
    }

    @Test fun gpioMetadataIsIndependentAndPinMustMatchWithoutAcceptingExtraBytes() {
        for ((v, value) in listOf("0" to false, "1" to true, "?" to null)) for ((p, prepared) in listOf("0" to false, "1" to true, "?" to null)) {
            assertEquals(HelperGpioState(value, prepared), wire("GPIOREAD 65535", "GPIOREAD 65535 VALUE=$v PREPARED=$p\n").client.gpioRead(65535))
        }
        assertEquals(HelperGpioState(false, true), wire("GPIOREAD 0", "GPIOREAD 0 VALUE=0 PREPARED=1\n").client.gpioRead(0))
        for (raw in listOf(null, "ERR\n", "GPIOREAD 148 VALUE=1 PREPARED=1\n", "GPIOREAD 0147 VALUE=1 PREPARED=1\n", "GPIOREAD 147 PREPARED=1 VALUE=1\n", "GPIOREAD 147 VALUE=2 PREPARED=1\n", "GPIOREAD 147 VALUE=1 PREPARED=1\n\n", "GPIOREAD 147 VALUE=1 PREPARED=1\u0000\n")) assertNull(raw, wire("GPIOREAD 147", raw).client.gpioRead(147))
        val invalid = wire("GPIOREAD 147", "ERR\n")
        assertNull(invalid.client.gpioRead(-1)); assertNull(invalid.client.gpioRead(65536)); assertNull(invalid.client.gpioRead(147, 0)); assertEquals(0, invalid.opened)
    }

    @Test fun connectBootstrapAndReplyConsumeOneBudgetAndLateRepliesAreUnavailable() {
        for (kind in listOf("gpio", "count", "read")) for (late in listOf(false, true)) {
            val budget = if (kind == "gpio") 500L else 5_000L
            var now = 0L
            val wire = wire("GPIOREAD 147", "GPIOREAD 147 VALUE=1 PREPARED=1\n").apply {
                replies["RELAYREAD STRELAY COUNT"] = "RELAYREAD STRELAY COUNT=2\n".toByteArray()
                replies["RELAYREAD STRELAY 1"] = "RELAYREAD STRELAY RELAY=1 VALUE=1\n".toByteArray()
            }
            val phases = mutableListOf<Long>()
            val transport = object : HelperCommandTransport {
                override fun open(): HelperCommandSession = error("must pass deadline into connect")
                override fun open(deadline: MonotonicDeadline): HelperCommandSession {
                    phases += deadline.remainingMs(); now += 100_000_000L
                    val delegate = requireNotNull(wire.open())
                    return object : HelperCommandSession by delegate {
                        override fun bootstrap(command: String, deadline: MonotonicDeadline): HelperBootstrapReply {
                            phases += deadline.remainingMs(); now += 200_000_000L
                            return delegate.bootstrap(command, deadline)
                        }
                        override fun sendBytes(cmd: String, maxBytes: Long, deadline: MonotonicDeadline): ByteArray? {
                            phases += deadline.remainingMs()
                            now += if (late) (budget - 300L) * 1_000_000L else 100_000_000L
                            return delegate.sendBytes(cmd, maxBytes)
                        }
                    }
                }
            }
            val client = IdentityAdmittingHelperClient(transport, nowNs = { now }, bootstrapTimeoutMs = 10_000L)
            val actual: Any? = when (kind) {
                "gpio" -> client.gpioRead(147, 500)
                "count" -> client.relayCount(relayClass)
                else -> client.relayRead(relayClass, 1)
            }
            val expected: Any? = if (late) null else when (kind) { "gpio" -> HelperGpioState(true, true); "count" -> 2; else -> true }
            assertEquals(expected, actual)
            assertEquals(listOf(budget, budget - 100L, budget - 300L), phases)
            assertEquals(1, wire.closed)
        }
    }

    @Test fun tricklingBytesAndEofProbeCannotExtendTheOriginalDeadline() {
        var now = 0L
        val deadline = MonotonicDeadline(5) { now }
        val timeouts = mutableListOf<Long>()
        val stream = object : InputStream() {
            override fun read(): Int = error("bulk only")
            override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                now += 2_000_000L; bytes[offset] = '1'.code.toByte(); return 1
            }
        }
        assertThrows(SocketTimeoutException::class.java) {
            HelperSocketProtocol.sendBytes("GPIOREAD 147", stream, ByteArrayOutputStream(), {}, 64) {
                val remaining = deadline.remainingMs()
                if (remaining == 0L) throw SocketTimeoutException()
                timeouts += remaining
            }
        }
        assertEquals(listOf(5L, 5L, 3L, 1L), timeouts)
        var reads = 0
        assertArrayEquals(byteArrayOf(1), BoundedStreams.readBytes(ByteArrayInputStream(byteArrayOf(1)), 1) { reads++ })
        assertEquals(2, reads) // Includes the EOF/overflow probe.
    }
}
