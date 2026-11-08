package io.panelassistant.android.util

import io.panelassistant.android.BuildConfig
import io.panelassistant.android.panelassistant.RootLocalAdbEndpoint
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream

class MainAdbRecoveryProtocolTest {
    @Test fun everyOperationUsesOnlyItsOwnExactlyAdmittedMainSession() {
        val wire = MainRecoveryWireFixture()
        val endpoint = RootLocalAdbEndpoint(wire.client)
        assertNotNull(endpoint.state())
        assertTrue(endpoint.arm("a".repeat(64)))
        assertTrue(endpoint.beat("a".repeat(64)))
        assertTrue(endpoint.recover("a".repeat(64)) == true)
        assertEquals("AAo=", endpoint.shell("printf test".toByteArray(), 10) { it(); true }!!.getString("stdout_b64"))
        assertEquals(5, wire.opens)
        wire.commands.groupBy { it.first }.values.forEach { commands ->
            assertEquals(listOf("VERSION", "BUILDID"), commands.take(2).map { it.second })
            assertEquals(3, commands.size)
        }
        assertEquals((1..5).toList(), wire.closed)
    }

    @Test fun staleVersionAndBuildWriteNoOperationAndNeverFallBackOrReopen() {
        for (version in listOf("ERR", "HELPER version=1.15.0 proto=1.15", "HELPER version=2.16.0 proto=2.16", "HELPER version=1.16.0 proto=1.16 proto=1.16")) {
            val wire = MainRecoveryWireFixture().apply { this.version = version }
            assertNull(RootLocalAdbEndpoint(wire.client).state())
            assertEquals(listOf(1 to "VERSION"), wire.commands)
            assertEquals(1, wire.opens)
        }
        for (build in listOf("ERR", "BUILDID ${"0".repeat(64)}", "BUILDID ${BuildConfig.HELPER_BUILD_ID} ")) {
            val wire = MainRecoveryWireFixture().apply { this.build = build }
            assertNull(RootLocalAdbEndpoint(wire.client).state())
            assertEquals(listOf(1 to "VERSION", 1 to "BUILDID"), wire.commands)
            assertEquals(1, wire.opens)
        }
    }

    @Test fun replacementAfterVersionCannotMoveAnOperationToAnotherSocket() {
        val wire = MainRecoveryWireFixture()
        wire.afterVersion = { wire.build = "BUILDID ${"0".repeat(64)}" }
        assertTrue(RootLocalAdbEndpoint(wire.client).arm("a".repeat(64)))
        assertNull(RootLocalAdbEndpoint(wire.client).state())
        assertEquals(listOf(1 to "VERSION", 1 to "BUILDID", 1 to "ARM ${"a".repeat(64)}", 2 to "VERSION", 2 to "BUILDID"), wire.commands)
    }

    @Test fun connectVersionAndBuildShareOneDeadlineBeforeAnyOperation() {
        var now = 0L
        val wire = MainRecoveryWireFixture { now }
        wire.phase = { now += 200_000_000L }
        assertTrue(wire.client.supportsAdbRecovery())
        assertEquals(listOf(1_000L, 800L, 600L), wire.budgets)
        for (phase in listOf("OPEN", "VERSION", "BUILDID")) {
            now = 0L
            val expired = MainRecoveryWireFixture { now }.apply { this.phase = { if (it == phase) now += 1_000_000_000L } }
            assertNull(RootLocalAdbEndpoint(expired.client).state())
            assertTrue(expired.commands.none { it.second == "STATUS" })
            assertEquals(1, expired.opens)
        }
    }

    @Test fun authorityRunsAfterIdentityAndTransportPreparationAtActualCombinedPayloadWrite() {
        val wire = MainRecoveryWireFixture()
        var allowed = true
        wire.beforeOperation = { allowed = false }
        assertNull(RootLocalAdbEndpoint(wire.client).shell("id".toByteArray(), 100) { write -> if (allowed) { write(); true } else false })
        assertEquals(listOf(1 to "VERSION", 1 to "BUILDID"), wire.commands)
        assertTrue(wire.requests.isEmpty())
    }

    @Test fun lossBeforeAndAfterSubmissionNeverRetriesOrFallsBack() {
        for (submitted in listOf(false, true)) {
            val wire = MainRecoveryWireFixture().apply {
                if (submitted) afterWrite = { error("lost after payload") } else beforeOperation = { error("lost before payload") }
            }
            assertTrue(runCatching { RootLocalAdbEndpoint(wire.client).shell("id".toByteArray(), 100) { it(); true } }.isFailure)
            assertEquals(1, wire.opens)
            assertEquals(if (submitted) 1 else 0, wire.requests.size)
            assertEquals(listOf(1), wire.closed)
        }
    }

    @Test fun replyFramingIsBoundedBinaryExactAndUsesAnAbsoluteReadDeadline() {
        fun exchange(reply: ByteArray, clock: () -> Long = { 0L }, beforeRead: (Long) -> Unit = {}) = HelperSocketProtocol.adbRecoveryExchange(
            "SHELL 1 2", "id".toByteArray(), ByteArrayInputStream(reply), 100, { it(); true }, {}, beforeRead, clock,
        )
        assertArrayEquals("RESULT 0 0 0 3\n\u0000\nX".toByteArray(), exchange("RESULT 0 0 0 3\n\u0000\nXextra".toByteArray()))
        for (reply in listOf("RESULT 0 0 0 32769\n", "RESULT 256 0 0 0\n", "RESULT 0 2 0 0\n", "RESULT 0 0 0 2\nX", "RESULT 0 0 0 0\r\n", "X".repeat(160))) {
            assertTrue(reply, runCatching { exchange(reply.toByteArray()) }.isFailure)
        }
        var now = 0L
        assertTrue(runCatching { exchange("RESULT 0 0 0 1\nx".toByteArray(), { now }) { now += 20_000_000L } }.isFailure)
    }
}

internal class MainRecoveryWireFixture(private val nowNs: () -> Long = { 0L }) : HelperCommandTransport {
    var version = "HELPER version=1.16.0 proto=1.16"
    var build = "BUILDID ${BuildConfig.HELPER_BUILD_ID}"
    var missing = false
    var afterVersion: () -> Unit = {}
    var beforeOperation: (String) -> Unit = {}
    var afterWrite: () -> Unit = {}
    var phase: (String) -> Unit = {}
    var reply: ((String) -> ByteArray)? = null
    val commands = mutableListOf<Pair<Int, String>>()
    val requests = mutableListOf<ByteArray>()
    val closed = mutableListOf<Int>()
    val budgets = mutableListOf<Long>()
    var opens = 0
    val client = IdentityAdmittingHelperClient(this, nowNs = nowNs)
    override fun open(): HelperCommandSession? = error("main recovery connect must carry a deadline")
    override fun open(deadline: MonotonicDeadline): HelperCommandSession? {
        opens++
        budgets += deadline.remainingMs(); phase("OPEN")
        if (missing) return null
        val id = opens
        val admittedVersion = version
        val admittedBuild = build
        val unused = requireNotNull(ZigbeeReadFixture().open())
        return object : HelperCommandSession by unused {
            override fun bootstrap(command: String, deadline: MonotonicDeadline): HelperBootstrapReply {
                commands += id to command; budgets += deadline.remainingMs(); phase(command)
                return HelperBootstrapReply.Line(when (command) {
                    "VERSION" -> admittedVersion.also { afterVersion() }
                    "BUILDID" -> admittedBuild
                    else -> error("unexpected bootstrap $command")
                })
            }
            override fun adbRecoveryExchange(header: String, payload: ByteArray, timeoutMs: Int, submit: (() -> Unit) -> Boolean): ByteArray? {
                beforeOperation(header)
                val frame = reply?.invoke(header) ?: when {
                    header == "STATUS" -> "READY 1 ${BuildConfig.HELPER_BUILD_ID} 0 0 0\n".toByteArray()
                    header.startsWith("SHELL ") -> "RESULT 0 0 0 2\n\u0000\n".toByteArray()
                    else -> "OK\n".toByteArray()
                }
                return HelperSocketProtocol.adbRecoveryExchange(header, payload, ByteArrayInputStream(frame), timeoutMs, submit,
                    { requests += it; commands += id to header; afterWrite() }, {}, nowNs)
            }
            override fun close() { closed += id }
        }
    }
}
