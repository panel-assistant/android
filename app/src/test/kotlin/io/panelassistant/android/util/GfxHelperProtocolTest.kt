package io.panelassistant.android.util

import io.panelassistant.android.platform.HelperGfxInfo
import org.junit.Assert.*
import org.junit.Test

class GfxHelperProtocolTest {
    @Test fun capabilityRequiresMinorFourteenOnTheActualCarryingConnection() {
        val wire = wire("GFXINFO SINCE=7 TOTAL=100 JANKY=20\n")
        wire.afterVersion = { wire.version = "HELPER version=1.13.0 proto=1.13" }
        assertEquals(HelperGfxInfo("7", 100, 20), wire.client.gfxInfo())
        assertNull(wire.client.gfxInfo())
        assertEquals(listOf(Triple(1, "GFXINFO SELF", 128L)), wire.bytes)
        assertEquals(listOf(1 to "VERSION", 2 to "VERSION"), wire.bootstraps)
        assertEquals(2, wire.closed)
        assertTrue(wire.rawCommands.isEmpty())
        for (version in listOf("ERR", "HELPER version=1.13.0 proto=1.13", "HELPER version=2.0.0 proto=2.14", "HELPER version=1.14.0 proto=1.14 proto=1.14")) {
            val refused = wire("GFXINFO SINCE=7 TOTAL=100 JANKY=20\n").apply { this.version = version }
            assertNull(refused.client.gfxInfo()); assertTrue(refused.bytes.isEmpty())
        }
    }

    @Test fun canonicalWholeFrameAndUnsignedBoundsAreRequired() {
        assertEquals(HelperGfxInfo("18446744073709551615", 4294967295, 4294967295),
            wire("GFXINFO SINCE=18446744073709551615 TOTAL=4294967295 JANKY=4294967295\n").client.gfxInfo())
        assertEquals(HelperGfxInfo("0", 0, 0), wire("GFXINFO SINCE=0 TOTAL=0 JANKY=0\n").client.gfxInfo())
        for (raw in listOf(null, "ERR\n", "GFXINFO SINCE=7 TOTAL=100 JANKY=20", "GFXINFO SINCE=7 TOTAL=100 JANKY=20\r\n", "GFXINFO SINCE=7 TOTAL=100 JANKY=20\nextra", "GFXINFO SINCE=7 TOTAL=100 JANKY=20\u0000\n", "GFXINFO SINCE=07 TOTAL=100 JANKY=20\n", "GFXINFO SINCE=-1 TOTAL=100 JANKY=20\n", "GFXINFO SINCE=18446744073709551616 TOTAL=100 JANKY=20\n", "GFXINFO SINCE=7 TOTAL=4294967296 JANKY=20\n", "GFXINFO SINCE=7 TOTAL=100 JANKY=101\n", "GFXINFO SINCE=7 TOTAL=100 JANKY=-1\n", "GFXINFO TOTAL=100 SINCE=7 JANKY=20\n", "GFXINFO SINCE=7 TOTAL=100 JANKY=20 P99=17\n", " ".repeat(129))) {
            assertNull(raw, wire(raw).client.gfxInfo())
        }
        for (mode in listOf("missing", "old", "refused", "malformed")) {
            val wire = wire("GFXINFO SINCE=7 TOTAL=100 JANKY=20\n")
            when (mode) {
                "missing" -> wire.missing = true
                "old" -> wire.version = "HELPER version=1.13.0 proto=1.13"
                "refused" -> wire.replies["GFXINFO SELF"] = "ERR\n".toByteArray()
                "malformed" -> wire.replies["GFXINFO SELF"] = "GFXINFO SINCE=7 TOTAL=1 JANKY=2\n".toByteArray()
            }
            repeat(3) { assertNull(mode, wire.client.gfxInfo()) }
            assertTrue(wire.rawCommands.isEmpty())
        }
    }

    @Test fun connectVersionAndReplyShareFiveSecondsAndLateRepliesAreRejected() {
        for (late in listOf(false, true)) {
            var now = 0L
            val phases = mutableListOf<Long>()
            val wire = wire("GFXINFO SINCE=7 TOTAL=100 JANKY=20\n")
            val transport = object : HelperCommandTransport {
                override fun open(): HelperCommandSession = error("deadline required for connection")
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
                            now += if (late) 4_700_000_000L else 100_000_000L
                            return delegate.sendBytes(cmd, maxBytes)
                        }
                    }
                }
            }
            val client = IdentityAdmittingHelperClient(transport, nowNs = { now }, bootstrapTimeoutMs = 10_000L)
            assertEquals(if (late) null else HelperGfxInfo("7", 100, 20), client.gfxInfo())
            assertEquals(listOf(5_000L, 4_900L, 4_700L), phases)
            assertEquals(1, wire.closed)
        }
    }

    private fun wire(raw: String?) = ZigbeeReadFixture().apply {
        version = "HELPER version=1.14.0 proto=1.14"
        replies["GFXINFO SELF"] = raw?.toByteArray()
    }
}
