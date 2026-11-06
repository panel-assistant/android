package io.panelassistant.android.util

import io.panelassistant.android.BuildConfig
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.OutputStream

class EnsureHelperProtocolTest {
    @Test fun versionAndExactBuildAdmitOnlyTheirOwnCarryingSession() {
        val wire = EnsureWireFixture()
        wire.afterVersion = { wire.identity = wire.identity.copy(build = "BUILDID ${"0".repeat(64)}") }
        assertTrue(wire.client.ensureAdbRecovery())
        assertFalse(wire.client.ensureAdbRecovery())
        assertEquals(listOf(1 to "VERSION", 1 to "BUILDID", 1 to "ADBRECOVERYENSURE", 2 to "VERSION", 2 to "BUILDID"), wire.commands)
        assertEquals(listOf(1, 2), wire.closed)
        assertEquals(listOf(32L), wire.replyCaps)
        for (version in listOf("ERR", "HELPER version=1.14.0 proto=1.14", "HELPER version=2.0.0 proto=2.15", "HELPER version=1.15.0 proto=1.15 proto=1.15")) {
            val refused = EnsureWireFixture().apply { identity = identity.copy(version = version) }
            assertFalse(refused.client.ensureAdbRecovery())
            assertEquals(listOf(1 to "VERSION"), refused.commands)
            assertEquals(listOf(1), refused.closed)
        }
        for (build in listOf(null, "ERR", "BUILDID ${"0".repeat(64)}", "BUILDID ${BuildConfig.HELPER_BUILD_ID} ", "BUILDID ${"A".repeat(64)}")) {
            val refused = EnsureWireFixture().apply { identity = identity.copy(build = build) }
            assertFalse(refused.client.ensureAdbRecovery())
            assertEquals(listOf(1 to "VERSION", 1 to "BUILDID"), refused.commands)
            assertEquals(listOf(1), refused.closed)
        }
    }

    @Test fun offerIsReadOnlyAndCannotAuthorizeAReplacementOrAnotherActionConnection() {
        val wire = EnsureWireFixture()
        repeat(2) { assertTrue(wire.client.supportsAdbRecoveryEnsure()) }
        assertEquals(listOf(1 to "VERSION", 1 to "BUILDID", 2 to "VERSION", 2 to "BUILDID"), wire.commands)
        wire.identity = wire.identity.copy(build = "BUILDID ${"0".repeat(64)}")
        assertFalse(wire.client.ensureAdbRecovery())
        assertTrue(wire.replyCaps.isEmpty())
        assertEquals(listOf(1, 2, 3), wire.closed)
    }

    @Test fun onlyTheExactReadyFrameSucceedsAndTransportFailuresReturnFalse() {
        for (reply in listOf(null, "ERR\n", "OK\n", "ADBRECOVERYENSURE UNAVAILABLE\n", "ADBRECOVERYENSURE READY", "ADBRECOVERYENSURE READY\r\n", "ADBRECOVERYENSURE READY\nextra", "ADBRECOVERYENSURE READY\u0000\n", " ".repeat(33))) {
            val wire = EnsureWireFixture().apply { identity = identity.copy(reply = reply?.toByteArray()) }
            assertFalse(reply, wire.client.ensureAdbRecovery())
            assertEquals(listOf(1), wire.closed)
        }
        val missing = EnsureWireFixture().apply { missing = true }
        assertFalse(missing.client.ensureAdbRecovery()); assertFalse(missing.client.supportsAdbRecoveryEnsure())
        assertTrue(missing.commands.isEmpty())
        for (phase in listOf("OPEN", "VERSION", "BUILDID", "ADBRECOVERYENSURE")) {
            val wire = EnsureWireFixture().apply { onCommand = { if (it == phase) error("lost helper") } }
            assertFalse(phase, wire.client.ensureAdbRecovery())
            if (phase != "OPEN") assertEquals(listOf(1), wire.closed)
        }
    }

    @Test fun connectAdmissionAndFinalReplyHaveAbsoluteSharedBudgets() {
        for (submit in listOf(false, true)) {
            var now = 0L
            val wire = EnsureWireFixture(nowNs = { now })
            wire.onCommand = { now += when(it) { "OPEN" -> 100_000_000L; "VERSION" -> 200_000_000L; "BUILDID" -> 300_000_000L; else -> 100_000_000L } }
            assertTrue(if (submit) wire.client.ensureAdbRecovery() else wire.client.supportsAdbRecoveryEnsure())
            val expected = if (submit) listOf("OPEN" to 30_000L, "VERSION" to 1_000L, "BUILDID" to 800L, "ADBRECOVERYENSURE" to 29_400L)
                else listOf("OPEN" to 1_000L, "VERSION" to 900L, "BUILDID" to 700L)
            assertEquals(expected, wire.budgets)
        }
        for (phase in listOf("OPEN", "VERSION", "BUILDID", "ADBRECOVERYENSURE")) {
            var now = 0L
            val wire = EnsureWireFixture(nowNs = { now }).apply {
                onCommand = { if (it == phase) now += if (phase == "OPEN" || phase == "ADBRECOVERYENSURE") 30_000_000_000L else 1_000_000_000L }
            }
            assertFalse(phase, wire.client.ensureAdbRecovery())
            if (phase != "ADBRECOVERYENSURE") assertTrue(wire.replyCaps.isEmpty())
            assertEquals(listOf(1), wire.closed)
        }
    }

    @Test fun identityWritesInstallTheirRemainingTimeoutBeforeWritingAndNeverRenewTheDeadline() {
        for (command in listOf("VERSION", "BUILDID")) {
            var now = 0L
            var timeout = 0
            val output = object : ByteArrayOutputStream() {
                override fun write(bytes: ByteArray, offset: Int, length: Int) {
                    assertEquals("write must already have the admission timeout", 500, timeout)
                    now += 600_000_000L
                    super.write(bytes, offset, length)
                }
            }
            assertEquals(HelperBootstrapReply.Missing, exchangeHelperBootstrap(command, ByteArrayInputStream("OK\n".toByteArray()), output, { timeout = it }, MonotonicDeadline(500) { now }))
            assertEquals("$command\n", output.toString("US-ASCII"))
        }
        for (command in listOf("VERSION", "BUILDID")) {
            val input = ByteArrayInputStream("OK\n".toByteArray())
            var timeoutInstalled = false
            val failedWrite = object : OutputStream() {
                override fun write(value: Int) {
                    assertTrue(timeoutInstalled)
                    throw java.io.IOException("peer stopped reading")
                }
            }
            assertEquals(HelperBootstrapReply.Missing, exchangeHelperBootstrap(command, input, failedWrite, { timeoutInstalled = true }, MonotonicDeadline(500) { 0L }))
            assertEquals("a failed identity write cannot consume or trust a reply", 3, input.available())
        }
        var writes = 0
        val expired = MonotonicDeadline(0) { 0L }
        val output = object : OutputStream() { override fun write(value: Int) { writes++ } }
        assertEquals(HelperBootstrapReply.Missing, exchangeHelperBootstrap("BUILDID", ByteArrayInputStream(byteArrayOf()), output, {}, expired))
        assertEquals(0, writes)
        assertEquals(HelperBootstrapReply.Line("BUILDID ${BuildConfig.HELPER_BUILD_ID}"), exchangeHelperBootstrap("BUILDID", ByteArrayInputStream("BUILDID ${BuildConfig.HELPER_BUILD_ID}\n".toByteArray()), ByteArrayOutputStream(), {}, MonotonicDeadline(1000) { 0L }))
        assertEquals(HelperBootstrapReply.Missing, exchangeHelperBootstrap("ADBRECOVERYENSURE", ByteArrayInputStream("OK\n".toByteArray()), output, {}, MonotonicDeadline(1000) { 0L }))
        assertEquals(0, writes)
    }
}

internal class EnsureWireFixture(nowNs: () -> Long = { 0L }) : HelperCommandTransport {
    data class Identity(val version: String = "HELPER version=1.15.0 proto=1.15", val build: String? = "BUILDID ${BuildConfig.HELPER_BUILD_ID}", val reply: ByteArray? = "ADBRECOVERYENSURE READY\n".toByteArray())
    var identity = Identity()
    var missing = false
    var afterVersion: (() -> Unit)? = null
    var onCommand: (String) -> Unit = {}
    val commands = mutableListOf<Pair<Int, String>>()
    val budgets = mutableListOf<Pair<String, Long>>()
    val closed = mutableListOf<Int>()
    val replyCaps = mutableListOf<Long>()
    private var opened = 0
    val client = IdentityAdmittingHelperClient(this, nowNs = nowNs)
    override fun open(): HelperCommandSession = error("ENSURE connect must carry a deadline")
    override fun open(deadline: MonotonicDeadline): HelperCommandSession? {
        budgets += "OPEN" to deadline.remainingMs(); onCommand("OPEN")
        if (missing) return null
        val id = ++opened
        val snapshot = identity
        val unused = requireNotNull(ZigbeeReadFixture().open())
        return object : HelperCommandSession by unused {
            override fun bootstrap(command: String, deadline: MonotonicDeadline): HelperBootstrapReply {
                commands += id to command; budgets += command to deadline.remainingMs(); onCommand(command)
                val reply = when(command) { "VERSION" -> snapshot.version.also { afterVersion?.invoke() }; "BUILDID" -> snapshot.build; else -> error("unexpected bootstrap: $command") }
                return reply?.let(HelperBootstrapReply::Line) ?: HelperBootstrapReply.Missing
            }
            override fun sendBytes(cmd: String, maxBytes: Long): ByteArray? = error("action must carry deadline")
            override fun sendBytes(cmd: String, maxBytes: Long, deadline: MonotonicDeadline): ByteArray? {
                check(cmd == "ADBRECOVERYENSURE"); commands += id to cmd; replyCaps += maxBytes
                budgets += cmd to deadline.remainingMs(); onCommand(cmd); return snapshot.reply
            }
            override fun send(cmd: String): String? = error("unexpected raw command: $cmd")
            override fun close() { closed += id }
        }
    }
}
