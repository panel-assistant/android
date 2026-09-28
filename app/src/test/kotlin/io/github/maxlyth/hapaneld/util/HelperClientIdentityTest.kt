package io.github.maxlyth.hapaneld.util

import io.github.maxlyth.hapaneld.platform.DaemonLongResult
import io.github.maxlyth.hapaneld.platform.DaemonStreamResult
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.util.Collections
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test

class HelperClientIdentityTest {
    @Test
    fun logcatOpensOnlyAfterCapabilityOnTheAdmittedConnection() {
        val transport = FakeHelperTransport(FakeGeneration(lineReplies = mapOf("LOGCATCAPS" to "LOGCATCAPS 1")))
        val client = IdentityAdmittingHelperClient(transport, nowNs = { 0L })

        val opened = assertIs<HelperLogcatOpenResult.Open>(client.openLogcat())
        assertEquals(listOf("LOGCATCAPS", "LOGCAT"), transport.lineCommands.map { it.command })
        assertEquals(1, transport.lineCommands.map { it.session }.distinct().size)
        assertEquals(transport.bootstrapCommands.single().session, transport.lineCommands.first().session)
        opened.stream.close()
        assertTrue(transport.closedSessions.contains(transport.lineCommands.first().session))
    }

    @Test
    fun logcatFallbackIsOnlyForExactUnsupportedCapability() {
        val old = FakeHelperTransport(FakeGeneration(lineReplies = mapOf("LOGCATCAPS" to "ERR")))
        assertEquals(HelperLogcatOpenResult.Unsupported, IdentityAdmittingHelperClient(old).openLogcat())
        assertEquals(listOf("LOGCATCAPS"), old.lineCommands.map { it.command })

        val failed = FakeHelperTransport(FakeGeneration(lineReplies = mapOf("LOGCATCAPS" to null)))
        assertEquals(HelperLogcatOpenResult.Failed, IdentityAdmittingHelperClient(failed).openLogcat())
        assertEquals(listOf("LOGCATCAPS"), failed.lineCommands.map { it.command })
    }

    @Test
    fun rawLogcatReaderPreservesBlankLinesAndRefusesPartialOrOversizeLines() {
        val input = ByteArrayInputStream("[ 123.456 1: 2 E/App ]\n at App.one(App.java:1)\n\n".toByteArray())
        val reader = HelperLogcatLineReader(input)
        assertEquals("[ 123.456 1: 2 E/App ]", reader.readLine())
        assertEquals(" at App.one(App.java:1)", reader.readLine())
        assertEquals("", reader.readLine())
        assertNull(reader.readLine())
        kotlin.test.assertFailsWith<IOException> { HelperLogcatLineReader(ByteArrayInputStream("partial".toByteArray())).readLine() }
        kotlin.test.assertFailsWith<IOException> {
            HelperLogcatLineReader(ByteArrayInputStream(("X".repeat(8193) + "\n").toByteArray())).readLine()
        }
    }

    @Test
    fun logcatTimeoutRetainsPartialLineUntilTheNextRead() {
        val source = object : java.io.InputStream() {
            private val bytes = " at App.one(App.java:1)\n\n".toByteArray()
            private var index = 0
            private var timedOut = false
            override fun read(): Int {
                if (index == 6 && !timedOut) {
                    timedOut = true
                    throw java.net.SocketTimeoutException("read pause")
                }
                return if (index == bytes.size) -1 else bytes[index++].toInt() and 0xff
            }
        }
        val reader = HelperLogcatLineReader(source)
        kotlin.test.assertFailsWith<java.net.SocketTimeoutException> { reader.readLine() }
        assertEquals(" at App.one(App.java:1)", reader.readLine())
        assertEquals("", reader.readLine())
    }
    @Test
    fun parsesCurrentAndForwardCompatibleIdentity() {
        assertEquals(
            HelperIdentity(version = "1.0.0", protocolMajor = 1, protocolMinor = 0),
            parseHelperIdentity("HELPER version=1.0.0 proto=1.0"),
        )
        assertEquals(
            HelperIdentity(version = "12.34.56", protocolMajor = 1, protocolMinor = 9),
            parseHelperIdentity("HELPER version=12.34.56 proto=1.9 build=0123456789ab"),
        )
    }

    @Test
    fun rejectsMalformedOrAmbiguousIdentity() {
        val malformed = listOf(
            "",
            "HELPER",
            "helper version=1.0.0 proto=1.0",
            "HELPER  version=1.0.0 proto=1.0",
            "HELPER version=1.0 proto=1.0",
            "HELPER version=01.0.0 proto=1.0",
            "HELPER version=1.0.0",
            "HELPER version=1.0.0 proto=1",
            "HELPER version=1.0.0 proto=1.0 proto=1.1",
            "HELPER version=1.0.0 proto=99999999999999999999.0",
            "HELPER version=1.0.0 proto=1.0\n",
            "X".repeat(129),
        )
        malformed.forEach { assertNull(parseHelperIdentity(it), it) }
    }

    @Test
    fun bootstrapReaderAcceptsExactBoundedAsciiWithoutTrimming() {
        fun read(value: String): HelperBootstrapReply = readHelperBootstrapLine(
            ByteArrayInputStream(value.toByteArray()),
            {},
            MonotonicDeadline(1_000L) { 0L },
        )

        assertEquals(HelperBootstrapReply.Line("HELPER version=1.0.0 proto=1.0"), read("HELPER version=1.0.0 proto=1.0\n"))
        assertEquals(HelperBootstrapReply.Line("X".repeat(128)), read("${"X".repeat(128)}\n"))
        assertEquals(HelperBootstrapReply.Line(" HELPER version=1.0.0 proto=1.0"), read(" HELPER version=1.0.0 proto=1.0\n"))
        assertEquals(HelperBootstrapReply.Line("ERR "), read("ERR \n"))
    }

    @Test
    fun bootstrapReaderRejectsOverflowControlsPartialEofAndPartialTimeout() {
        fun read(bytes: ByteArray): HelperBootstrapReply = readHelperBootstrapLine(
            ByteArrayInputStream(bytes),
            {},
            MonotonicDeadline(1_000L) { 0L },
        )

        assertEquals(HelperBootstrapReply.Malformed, read(("X".repeat(129) + "\n").toByteArray()))
        assertEquals(HelperBootstrapReply.Malformed, read("HELPER\rversion\n".toByteArray()))
        assertEquals(HelperBootstrapReply.Malformed, read("HELPER version=1.0.0".toByteArray()))
        assertEquals(HelperBootstrapReply.Missing, read(byteArrayOf()))

        var reads = 0
        val timeoutAfterPrefix = object : java.io.InputStream() {
            override fun read(): Int = when (reads++) {
                0 -> 'H'.code
                1 -> 'E'.code
                else -> throw java.net.SocketTimeoutException("fixture")
            }
        }
        assertEquals(
            HelperBootstrapReply.Malformed,
            readHelperBootstrapLine(timeoutAfterPrefix, {}, MonotonicDeadline(1_000L) { 0L }),
        )
    }

    @Test
    fun bootstrapReaderEnforcesOneAbsoluteDeadlineAgainstTrickledBytes() {
        var nowNs = 0L
        val timeouts = mutableListOf<Int>()
        val trickle = object : java.io.InputStream() {
            private val bytes = "HELPER version=1.0.0 proto=1.0\n".toByteArray()
            private var index = 0
            override fun read(): Int {
                nowNs += 400_000_000L
                return bytes[index++].toInt() and 0xff
            }
        }

        val reply = readHelperBootstrapLine(
            trickle,
            timeouts::add,
            MonotonicDeadline(1_000L) { nowNs },
        )

        assertEquals(HelperBootstrapReply.Malformed, reply)
        assertEquals(listOf(1_000, 600, 200), timeouts)
        assertTrue(nowNs <= 1_200_000_000L)
    }

    @Test
    fun compatibleIdentityAdmitsEveryCommandShapeOnItsOperationConnection() {
        val transport = FakeHelperTransport()
        val client = IdentityAdmittingHelperClient(transport, nowNs = { 0L })

        assertEquals(
            HelperIdentityStatus.Compatible(HelperIdentity("1.1.0", 1, 1)),
            client.identityStatus(),
        )
        assertTrue(client.available())
        assertEquals("OK", client.send("LED 1 2 3"))
        assertEquals(DaemonLongResult.Reply("OK"), client.sendLong("INSTALL /data/local/tmp/app.apk", 1_000L))
        assertEquals(
            DaemonStreamResult.Reply("OK"),
            client.sendFile("INSTALLSTREAM 4", File("unused"), 1_000L),
        )
        assertContentEquals(byteArrayOf(1, 2, 3), client.sendBytes("SCREENCAP"))
        assertContentEquals(byteArrayOf(1, 2, 3), client.sendBytesBounded("PERFDUMP", 4_096L))

        assertEquals(7, transport.bootstrapCommands.count { it.command == "VERSION" })
        assertEquals(listOf("PING", "LED 1 2 3"), transport.lineCommands.map { it.command })
        assertEquals(listOf("INSTALL /data/local/tmp/app.apk"), transport.longCommands.map { it.command })
        assertEquals(listOf("INSTALLSTREAM 4"), transport.fileCommands.map { it.command })
        assertEquals(
            listOf("SCREENCAP" to Long.MAX_VALUE - 1L, "PERFDUMP" to 4_096L),
            transport.byteCommands.map { it.command to it.maxBytes },
        )
        assertTrue(transport.operationSessions().all { session ->
            transport.bootstrapCommands.any { it.session == session && it.command == "VERSION" }
        })
    }

    @Test
    fun malformedAndMajorIncompatibleIdentitiesBlockAllCommandShapesBeforeSubmission() {
        listOf(
            "HELLO" to HelperIdentityIssue.MALFORMED_IDENTITY,
            " HELPER version=1.1.0 proto=1.1" to HelperIdentityIssue.MALFORMED_IDENTITY,
            "HELPER version=1.1.0 proto=1.1 " to HelperIdentityIssue.MALFORMED_IDENTITY,
            "HELPER version=2.0.0 proto=2.0" to HelperIdentityIssue.UNSUPPORTED_PROTOCOL,
        ).forEach { (reply, issue) ->
            val transport = FakeHelperTransport(FakeGeneration(version = HelperBootstrapReply.Line(reply)))
            val client = IdentityAdmittingHelperClient(transport, nowNs = { 0L })

            assertFalse(client.available())
            val status = assertIs<HelperIdentityStatus.Incompatible>(client.identityStatus())
            assertEquals(issue, status.issue)
            assertNull(client.send("LED 1 2 3"))
            assertEquals(DaemonLongResult.NotSubmitted, client.sendLong("INSTALL file", 1_000L))
            assertEquals(
                DaemonStreamResult.NotSubmitted,
                client.sendFile("INSTALLSTREAM 4", File("unused"), 1_000L),
            )
            assertNull(client.sendBytes("SCREENCAP"))
            assertNull(client.sendBytesBounded("PERFDUMP", 4_096L))

            assertTrue(transport.bootstrapCommands.all { it.command == "VERSION" })
            assertTrue(transport.longCommands.isEmpty())
            assertTrue(transport.fileCommands.isEmpty())
            assertTrue(transport.byteCommands.isEmpty())
            assertTrue(transport.lineCommands.isEmpty())
        }
    }

    @Test
    fun legacyHelperRemainsAvailableAndAdmitsEveryCommandShape() {
        val transport = FakeHelperTransport(FakeGeneration(version = HelperBootstrapReply.Line("ERR")))
        val client = IdentityAdmittingHelperClient(transport, nowNs = { 0L })

        assertTrue(client.available())
        assertEquals(HelperIdentityStatus.ReachableUnverified, client.identityStatus())
        assertEquals("OK", client.send("LED 1 2 3"))
        assertEquals(DaemonLongResult.Reply("OK"), client.sendLong("INSTALL file", 1_000L))
        assertEquals(
            DaemonStreamResult.Reply("OK"),
            client.sendFile("INSTALLSTREAM 4", File("unused"), 1_000L),
        )
        assertContentEquals(byteArrayOf(1, 2, 3), client.sendBytes("SCREENCAP"))

        assertEquals(12, transport.bootstrapCommands.size)
        assertTrue(transport.bootstrapCommands.chunked(2).all { pair -> pair.map { it.command } == listOf("VERSION", "PING") })
        assertEquals(1, transport.longCommands.size)
        assertEquals(1, transport.fileCommands.size)
        assertEquals(1, transport.byteCommands.size)
    }

    @Test
    fun legacyErrRequiresExactPingOkWithoutRawFallbackForOrdinaryOrCompanionOperations() {
        fun readPing(input: java.io.InputStream): HelperBootstrapReply = readHelperBootstrapLine(
            input,
            {},
            MonotonicDeadline(1_000L) { 0L },
        )

        val failures = listOf(
            "empty EOF" to readPing(ByteArrayInputStream(byteArrayOf())),
            "partial EOF" to readPing(ByteArrayInputStream("O".toByteArray())),
            "control byte" to readPing(ByteArrayInputStream("O\r\n".toByteArray())),
            "timeout" to readPing(object : java.io.InputStream() {
                override fun read(): Int = throw java.net.SocketTimeoutException("fixture")
            }),
            "partial timeout" to readPing(object : java.io.InputStream() {
                private var first = true
                override fun read(): Int = if (first) {
                    first = false
                    'O'.code
                } else {
                    throw java.net.SocketTimeoutException("fixture")
                }
            }),
            "negative reply" to readPing(ByteArrayInputStream("NO\n".toByteArray())),
            "untrimmed reply" to readPing(ByteArrayInputStream("OK \n".toByteArray())),
        )

        failures.forEach { (case, ping) ->
            val transport = FakeHelperTransport(
                FakeGeneration(version = HelperBootstrapReply.Line("ERR"), ping = ping),
            )
            val client = IdentityAdmittingHelperClient(transport, nowNs = { 0L })

            assertEquals(HelperIdentityStatus.Missing, client.identityStatus(), case)
            assertFalse(client.available(), case)
            assertNull(client.send("LED 1 2 3"), case)
            assertEquals(DaemonLongResult.NotSubmitted, client.sendLong("INSTALL file", 1_000L), case)
            assertEquals(
                DaemonStreamResult.NotSubmitted,
                client.sendFile("INSTALLSTREAM 4", File("unused"), 1_000L),
                case,
            )
            assertNull(client.sendBytes("SCREENCAP"), case)
            assertNull(client.sendBytesBounded("PERFDUMP", 4_096L), case)
            assertEquals(
                CompanionHelperProtocol.BackupResult.NotSubmitted,
                client.backupCompanion("io.example.companion", File("unused"), 1_000L),
                case,
            )
            assertEquals(
                CompanionHelperProtocol.RestoreResult.NOT_SUBMITTED,
                client.restoreCompanion("io.example.companion", emptyMap(), 1_000L),
                case,
            )

            assertEquals(18, transport.bootstrapCommands.size, case)
            assertTrue(
                transport.bootstrapCommands.chunked(2).all { commands ->
                    commands.map(RecordedCommand::command) == listOf("VERSION", "PING")
                },
                case,
            )
            assertTrue(transport.operationSessions().isEmpty(), case)
        }
    }

    @Test
    fun missingVersionResponseDoesNotBreakPreVersionCommandCompatibility() {
        val transport = FakeHelperTransport(FakeGeneration(version = HelperBootstrapReply.Missing))
        val client = IdentityAdmittingHelperClient(transport, nowNs = { 0L })

        assertEquals("OK", client.send("LEGACYCOMMAND"))
        assertEquals(DaemonLongResult.Reply("OK"), client.sendLong("INSTALL file", 1_000L))
        assertEquals(
            DaemonStreamResult.Reply("OK"),
            client.sendFile("INSTALLSTREAM 4", File("unused"), 1_000L),
        )
        assertContentEquals(byteArrayOf(1, 2, 3), client.sendBytes("SCREENCAP"))
        assertContentEquals(byteArrayOf(1, 2, 3), client.sendBytesBounded("PERFDUMP", 4_096L))

        assertEquals(5, transport.bootstrapCommands.size)
        transport.operationSessions().forEach { operationSession ->
            assertTrue(transport.bootstrapCommands.none { it.session == operationSession })
        }

        transport.generation = FakeGeneration(version = HelperBootstrapReply.Line("HELLO"))
        assertNull(client.send("BLOCKED"))
        assertEquals(listOf("LEGACYCOMMAND"), transport.lineCommands.map { it.command })
    }

    @Test
    fun replacementBetweenAdmissionAndOperationStaysOnTheAdmittedConnection() {
        val old = FakeGeneration()
        val transport = FakeHelperTransport(old)
        val incompatible = FakeGeneration(version = HelperBootstrapReply.Line("HELPER version=2.0.0 proto=2.0"))
        old.afterVersion = { transport.generation = incompatible }
        val client = IdentityAdmittingHelperClient(transport, nowNs = { 0L })

        assertEquals("OK", client.send("ONE"))
        assertEquals(old.id, transport.lineCommands.single().generation)
        assertNull(client.send("TWO"))
        assertEquals(listOf("ONE"), transport.lineCommands.map { it.command })
    }

    @Test
    fun replacementSettlementTupleIsIdentityAdmittedAndUsesOneFreshConnectionPerProbe() {
        val staged = "a".repeat(64)
        val incumbent = "b".repeat(64)
        val generation = FakeGeneration(
            lineReplies = mapOf(
                "PING" to "OK",
                "BUILDID" to "BUILDID $staged",
                "GUARDCAPS" to
                    "${GuardDbMaintenanceProtocol.CAPS_REPLY} AUTONOMOUS SUPERVISED TERMINAL_RETIRE",
                "GUARDSTATUS" to "OK GUARDSTATUS 0 EMPTY NONE NONE NONE NONE 0 0 0 NONE NONE 0 0",
            ),
        )
        val transport = FakeHelperTransport(generation)
        val client = IdentityAdmittingHelperClient(transport, nowNs = { 0L })

        repeat(2) {
            assertEquals(
                HelperReplacementProbe.Settled(HelperReplacementBuild.NEW),
                client.replacementProbe(staged, incumbent),
            )
        }

        val sessions = transport.lineCommands.map(RecordedCommand::session).distinct()
        assertEquals(2, sessions.size)
        sessions.forEach { session ->
            assertEquals(
                listOf("PING", "BUILDID", "GUARDCAPS", "GUARDSTATUS"),
                transport.lineCommands.filter { it.session == session }.map(RecordedCommand::command),
            )
            assertEquals(
                listOf("VERSION"),
                transport.bootstrapCommands.filter { it.session == session }.map(RecordedCommand::command),
            )
        }
    }

    @Test
    fun failedOperationCannotCarryAdmissionAcrossAnInstallerReplacement() {
        val transport = FakeHelperTransport(FakeGeneration(lineReply = null))
        val client = IdentityAdmittingHelperClient(transport, nowNs = { 0L })

        assertNull(client.send("FAIL"))
        transport.generation = FakeGeneration(version = HelperBootstrapReply.Line("HELPER version=2.0.0 proto=2.0"))
        assertNull(client.send("BLOCKED"))

        assertEquals(listOf("FAIL"), transport.lineCommands.map { it.command })
    }

    @Test
    fun companionBackupAndRestoreRequireIdentityAndCapabilityOnTheirOperationConnection() {
        val transport = FakeHelperTransport()
        val client = IdentityAdmittingHelperClient(transport, nowNs = { 0L })
        val unused = File("unused")

        assertEquals(
            CompanionHelperProtocol.BackupResult.Busy,
            client.backupCompanion("io.example.companion", unused, 1_000L),
        )
        assertEquals(
            CompanionHelperProtocol.RestoreResult.COMMITTED,
            client.restoreCompanion("io.example.companion", emptyMap(), 1_000L),
        )

        assertEquals(2, transport.companionCommands.size)
        transport.companionCommands.forEach { operation ->
            assertEquals(
                listOf("VERSION", "COMPANIONCAPS"),
                transport.bootstrapCommands.filter { it.session == operation.session }.map { it.command },
            )
        }
    }

    @Test
    fun missingVersionCompanionFallbackUsesOneFreshCapabilityAndOperationConnection() {
        val transport = FakeHelperTransport(FakeGeneration(version = HelperBootstrapReply.Missing))
        val client = IdentityAdmittingHelperClient(transport, nowNs = { 0L })

        assertEquals(
            CompanionHelperProtocol.BackupResult.Busy,
            client.backupCompanion("io.example.companion", File("unused"), 1_000L),
        )
        assertEquals(
            CompanionHelperProtocol.RestoreResult.COMMITTED,
            client.restoreCompanion("io.example.companion", emptyMap(), 1_000L),
        )

        assertEquals(2, transport.bootstrapCommands.count { it.command == "VERSION" })
        transport.companionCommands.forEach { operation ->
            assertEquals(
                listOf("COMPANIONCAPS"),
                transport.bootstrapCommands.filter { it.session == operation.session }.map { it.command },
            )
        }
    }

    @Test
    fun companionOperationsBlockMalformedIncompatibleAndCapabilityMismatchBeforeSubmission() {
        listOf(
            FakeGeneration(version = HelperBootstrapReply.Malformed),
            FakeGeneration(version = HelperBootstrapReply.Line("HELPER version=2.0.0 proto=2.0")),
            FakeGeneration(companionCaps = HelperBootstrapReply.Line("ERR")),
        ).forEach { generation ->
            val transport = FakeHelperTransport(generation)
            val client = IdentityAdmittingHelperClient(transport, nowNs = { 0L })

            assertEquals(
                CompanionHelperProtocol.BackupResult.NotSubmitted,
                client.backupCompanion("io.example.companion", File("unused"), 1_000L),
            )
            assertEquals(
                CompanionHelperProtocol.RestoreResult.NOT_SUBMITTED,
                client.restoreCompanion("io.example.companion", emptyMap(), 1_000L),
            )
            assertTrue(transport.companionCommands.isEmpty())
        }
    }

    @Test
    fun companionReplacementAfterVersionCannotMoveTheOperationToAnUnverifiedHelper() {
        val old = FakeGeneration()
        val transport = FakeHelperTransport(old)
        old.afterVersion = {
            transport.generation = FakeGeneration(
                version = HelperBootstrapReply.Line("HELPER version=2.0.0 proto=2.0"),
            )
        }
        val client = IdentityAdmittingHelperClient(transport, nowNs = { 0L })

        assertEquals(
            CompanionHelperProtocol.BackupResult.Busy,
            client.backupCompanion("io.example.companion", File("unused"), 1_000L),
        )
        assertEquals(old.id, transport.companionCommands.single().generation)
        assertEquals(
            CompanionHelperProtocol.BackupResult.NotSubmitted,
            client.backupCompanion("io.example.companion", File("unused"), 1_000L),
        )
        assertEquals(1, transport.companionCommands.size)
    }

    @Test
    fun concurrentCallersUseIndependentConnectionScopedAdmission() {
        val transport = FakeHelperTransport()
        val client = IdentityAdmittingHelperClient(transport, nowNs = { 0L })
        val pool = Executors.newFixedThreadPool(8)
        try {
            val calls = (1..8).map { index -> pool.submit<String?> { client.send("OP$index") } }
            calls.forEach { assertEquals("OK", it.get(5, TimeUnit.SECONDS)) }
        } finally {
            pool.shutdownNow()
        }

        assertEquals(8, transport.bootstrapCommands.count { it.command == "VERSION" })
        assertEquals(8, transport.lineCommands.count { it.command.startsWith("OP") })
        assertEquals(8, transport.operationSessions().size)
    }
}

private data class FakeGeneration(
    val id: Int = nextId(),
    val version: HelperBootstrapReply = HelperBootstrapReply.Line("HELPER version=1.1.0 proto=1.1"),
    val ping: HelperBootstrapReply = HelperBootstrapReply.Line("OK"),
    val companionCaps: HelperBootstrapReply = HelperBootstrapReply.Line("COMPANIONCAPS 1 BACKUP RESTORE STATUS JOURNAL"),
    val lineReply: String? = "OK",
    val lineReplies: Map<String, String?> = emptyMap(),
) {
    @Volatile var afterVersion: (() -> Unit)? = null

    companion object {
        private val ids = java.util.concurrent.atomic.AtomicInteger()
        private fun nextId(): Int = ids.incrementAndGet()
    }
}

private data class RecordedCommand(val session: Int, val generation: Int, val command: String)
private data class RecordedBytes(val session: Int, val generation: Int, val command: String, val maxBytes: Long)

private class FakeHelperTransport(initial: FakeGeneration = FakeGeneration()) : HelperCommandTransport {
    @Volatile var generation: FakeGeneration = initial
    private val sessions = java.util.concurrent.atomic.AtomicInteger()
    val bootstrapCommands: MutableList<RecordedCommand> = Collections.synchronizedList(mutableListOf())
    val lineCommands: MutableList<RecordedCommand> = Collections.synchronizedList(mutableListOf())
    val longCommands: MutableList<RecordedCommand> = Collections.synchronizedList(mutableListOf())
    val fileCommands: MutableList<RecordedCommand> = Collections.synchronizedList(mutableListOf())
    val byteCommands: MutableList<RecordedBytes> = Collections.synchronizedList(mutableListOf())
    val companionCommands: MutableList<RecordedCommand> = Collections.synchronizedList(mutableListOf())
    val closedSessions: MutableList<Int> = Collections.synchronizedList(mutableListOf())

    override fun open(): HelperCommandSession {
        val session = sessions.incrementAndGet()
        val snapshot = generation
        return object : HelperCommandSession {
            override fun bootstrap(command: String, deadline: MonotonicDeadline): HelperBootstrapReply {
                bootstrapCommands += RecordedCommand(session, snapshot.id, command)
                return when (command) {
                    "VERSION" -> snapshot.version.also { snapshot.afterVersion?.invoke() }
                    "PING" -> snapshot.ping
                    "COMPANIONCAPS" -> snapshot.companionCaps
                    else -> error("unexpected bootstrap command $command")
                }
            }

            override fun send(cmd: String): String? {
                lineCommands += RecordedCommand(session, snapshot.id, cmd)
                return snapshot.lineReplies.getOrElse(cmd) { snapshot.lineReply }
            }

            override fun sendLong(cmd: String, timeoutMs: Long): DaemonLongResult {
                longCommands += RecordedCommand(session, snapshot.id, cmd)
                return DaemonLongResult.Reply("OK")
            }

            override fun sendFile(cmd: String, source: File, timeoutMs: Long): DaemonStreamResult {
                fileCommands += RecordedCommand(session, snapshot.id, cmd)
                return DaemonStreamResult.Reply("OK")
            }

            override fun sendBytes(cmd: String, maxBytes: Long): ByteArray? {
                byteCommands += RecordedBytes(session, snapshot.id, cmd, maxBytes)
                return byteArrayOf(1, 2, 3)
            }

            override fun readLogcatLine(): String? = null

            override fun backupCompanion(
                packageName: String,
                cacheDir: File,
                timeoutMs: Long,
            ): CompanionHelperProtocol.BackupResult {
                companionCommands += RecordedCommand(session, snapshot.id, "BACKUP $packageName")
                return CompanionHelperProtocol.BackupResult.Busy
            }

            override fun restoreCompanion(
                packageName: String,
                files: Map<String, File>,
                timeoutMs: Long,
            ): CompanionHelperProtocol.RestoreResult {
                companionCommands += RecordedCommand(session, snapshot.id, "RESTORE $packageName")
                return CompanionHelperProtocol.RestoreResult.COMMITTED
            }

            override fun close() { closedSessions += session }
        }
    }

    fun operationSessions(): Set<Int> = buildSet {
        lineCommands.forEach { add(it.session) }
        longCommands.forEach { add(it.session) }
        fileCommands.forEach { add(it.session) }
        byteCommands.forEach { add(it.session) }
        companionCommands.forEach { add(it.session) }
    }
}
