package io.panelassistant.android.util

import io.panelassistant.android.logship.LogCapture
import io.panelassistant.android.control.FakeRootShell
import io.panelassistant.android.metrics.OsMetricSource
import io.panelassistant.android.platform.DaemonLongResult
import io.panelassistant.android.platform.DaemonStreamResult
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.util.Collections
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test

class HelperClientIdentityTest {
    @get:org.junit.Rule val temporary = org.junit.rules.TemporaryFolder()

    @Test
    fun governorReadsUseTypedVersionAdmissionOnTheirOperationConnection() {
        val first = FakeGeneration(
            version = HelperBootstrapReply.Line("HELPER version=1.5.0 proto=1.5"),
            bytesReplies = mapOf("GOVREAD CURRENT" to "GOVREAD Vendor_1.fast-2\n".toByteArray()),
        )
        val transport = FakeHelperTransport(first)
        first.afterVersion = { transport.generation = FakeGeneration(
            version = HelperBootstrapReply.Line("HELPER version=2.0.0 proto=2.5"),
        ) }
        val client = IdentityAdmittingHelperClient(transport)

        assertEquals("Vendor_1.fast-2", client.cpuGovernor())
        val read = transport.byteCommands.single()
        assertEquals("GOVREAD CURRENT", read.command)
        assertEquals(4104L, read.maxBytes)
        assertEquals(transport.bootstrapCommands.single().session, read.session)
        assertEquals(first.id, read.generation)
        assertNull(client.cpuAvailableGovernors()) // Replacement is admitted afresh, never on old proof.
        assertEquals(1, transport.byteCommands.size)
        assertEquals(2, transport.closedSessions.size)
    }

    @Test
    fun governorReadsAcceptForwardMinorAndPreserveAvailableOrderAtTheRawBound() {
        val payload = (listOf("z".repeat(63)) + List(63) { "a".repeat(63) }).joinToString(" ")
        val transport = FakeHelperTransport(FakeGeneration(
            version = HelperBootstrapReply.Line("HELPER version=1.9.0 proto=1.9"),
            bytesReplies = mapOf("GOVREAD AVAILABLE" to "GOVREAD $payload\n".toByteArray()),
        ))
        assertEquals(payload.split(' '), IdentityAdmittingHelperClient(transport).cpuAvailableGovernors())
        assertEquals(4095, payload.length)
    }

    @Test
    fun malformedGovernorRepliesAreUnavailableInsteadOfRootFallback() {
        val oversizedPayload = (List(63) { "a".repeat(63) } + listOf("b".repeat(31), "c".repeat(32))).joinToString(" ")
        val malformed = listOf(
            null, "ERR\n", "", "GOVREAD \n", "GOVREAD a", "GOVREAD a\nextra\n",
            "GOVREAD a\r\n", "GOVREAD a\tbad\n", "GOVREAD a\u0000\n", "GOVREAD véndor\n",
            "GOVREAD  a\n", "GOVREAD a \n", "GOVREAD a  b\n", "GOVREAD /path\n",
            "GOVREAD ${"a".repeat(64)}\n", "GOVREAD $oversizedPayload\n",
        )
        for (reply in malformed) {
            val transport = FakeHelperTransport(FakeGeneration(
                version = HelperBootstrapReply.Line("HELPER version=1.5.0 proto=1.5"),
                bytesReplies = mapOf("GOVREAD CURRENT" to reply?.toByteArray(), "GOVREAD AVAILABLE" to reply?.toByteArray()),
            ))
            assertGovernorsUnavailableWithoutRoot(IdentityAdmittingHelperClient(transport))
        }
        val multiple = FakeHelperTransport(FakeGeneration(
            version = HelperBootstrapReply.Line("HELPER version=1.5.0 proto=1.5"),
            bytesReplies = mapOf("GOVREAD CURRENT" to "GOVREAD a b\n".toByteArray()),
        ))
        assertNull(IdentityAdmittingHelperClient(multiple).cpuGovernor())
    }

    @Test
    fun missingOldUnverifiedOrRefusedHelpersNeverReceiveGovernorReadsOrTriggerRoot() {
        val versions = listOf(
            HelperBootstrapReply.Missing, HelperBootstrapReply.Malformed, HelperBootstrapReply.Line("ERR"),
            HelperBootstrapReply.Line("HELPER version=1.4.0 proto=1.4"),
            HelperBootstrapReply.Line("HELPER version=2.5.0 proto=2.5"),
            HelperBootstrapReply.Line("HELPER version=1.5.0 proto=1.5 extra=bad extra=duplicate"),
        )
        for (version in versions) {
            val transport = FakeHelperTransport(FakeGeneration(version = version))
            assertGovernorsUnavailableWithoutRoot(IdentityAdmittingHelperClient(transport))
            assertTrue(transport.byteCommands.isEmpty())
            assertTrue(transport.bootstrapCommands.all { it.command == "VERSION" })
            assertEquals(12, transport.closedSessions.size) // one connection per read, no raw reconnect
        }
        assertGovernorsUnavailableWithoutRoot(IdentityAdmittingHelperClient(object : HelperCommandTransport {
            override fun open(): HelperCommandSession? = null
        }))
    }

    private fun assertGovernorsUnavailableWithoutRoot(client: IdentityAdmittingHelperClient) {
        val root = FakeRootShell(outputs = mapOf("scaling_governor" to "performance", "scaling_available_governors" to "performance powersave"))
        val source = OsMetricSource(daemon = client, root = root, directRead = { null })
        repeat(6) {
            assertNull(source.cpuGovernor())
            assertNull(source.cpuAvailableGovernors())
        }
        assertTrue(root.outputRan.isEmpty())
        assertTrue(root.isolatedOutputRan.isEmpty())
        assertTrue(root.ran.isEmpty())
        assertEquals(0, root.availabilityChecks)
    }

    @Test
    fun policyRevokedDuringVersionOrPingPreparationBlocksStreamSubmission() {
        listOf(false, true).forEachIndexed { index, legacy ->
            var admitted = true
            val generation = FakeGeneration(version = HelperBootstrapReply.Line(
                if (legacy) "ERR" else "HELPER version=1.1.0 proto=1.1",
            ))
            if (legacy) generation.afterPing = { admitted = false }
            else generation.afterVersion = { admitted = false }
            val transport = FakeHelperTransport(generation)
            val source = temporary.newFile("bootstrap-$index.apk").apply { writeBytes(byteArrayOf(1, 2)) }
            val directory = temporary.newFolder("bootstrap-staging-$index")
            val refusal = InstallOutcome.Retryable("Panel Assistant must be online with update policy")

            assertEquals(refusal, HelperInstallTransaction(IdentityAdmittingHelperClient(transport)).install(source, directory) {
                if (admitted) null else refusal
            })
            assertEquals(if (legacy) listOf("VERSION", "PING") else listOf("VERSION"),
                transport.bootstrapCommands.map { it.command })
            assertTrue(transport.fileCommands.isEmpty())
            assertTrue(transport.longCommands.isEmpty())
            assertFalse(source.exists())
            assertTrue(directory.listFiles().orEmpty().isEmpty())
            assertEquals(1, transport.closedSessions.size)
        }
    }

    @Test
    fun policyRevokedDuringLegacyInstallBootstrapDeletesOnlyClaimedInput() {
        listOf(false, true).forEachIndexed { index, legacy ->
            var admitted = true
            val generation = FakeGeneration(
                version = HelperBootstrapReply.Line(if (legacy) "ERR" else "HELPER version=1.1.0 proto=1.1"),
                streamReply = DaemonStreamResult.Unsupported,
            )
            generation.afterStream = {
                if (legacy) generation.afterPing = { admitted = false }
                else generation.afterVersion = { admitted = false }
            }
            val transport = FakeHelperTransport(generation)
            val source = temporary.newFile("fallback-$index.apk").apply { writeBytes(byteArrayOf(1, 2)) }
            val directory = temporary.newFolder("fallback-staging-$index")
            val unrelated = File(directory, "owner.apk").apply { writeBytes(byteArrayOf(9, 8)) }
            val refusal = InstallOutcome.Rejected("refused (Panel Assistant update policy permits stable builds only)")

            assertEquals(refusal, HelperInstallTransaction(IdentityAdmittingHelperClient(transport)).install(source, directory) {
                if (admitted) null else refusal
            })
            assertEquals(1, transport.fileCommands.size)
            assertTrue(transport.longCommands.isEmpty())
            assertFalse(source.exists())
            assertEquals(listOf(unrelated), directory.listFiles().orEmpty().toList())
            assertContentEquals(byteArrayOf(9, 8), unrelated.readBytes())
            assertEquals(2, transport.closedSessions.size)
        }
    }

    @Test
    fun acceptedStreamMayFinishAndOtherPackagesNeedNoPanelPolicy() {
        listOf(false, true).forEachIndexed { index, otherPackage ->
            var admitted = true
            val generation = FakeGeneration()
            if (otherPackage) generation.afterVersion = { admitted = false }
            else generation.afterStream = { admitted = false }
            val transport = FakeHelperTransport(generation)
            val source = temporary.newFile("accepted-bootstrap-$index.apk").apply { writeBytes(byteArrayOf(1, 2)) }
            val directory = temporary.newFolder("accepted-bootstrap-staging-$index")
            val gate: (() -> InstallOutcome.Failure?)? = if (otherPackage) null else {
                { if (admitted) null else InstallOutcome.Retryable("PA disconnected") }
            }

            assertEquals(InstallOutcome.Succeeded,
                HelperInstallTransaction(IdentityAdmittingHelperClient(transport)).install(source, directory, beforeSubmit = gate))
            assertFalse(admitted)
            assertEquals(1, transport.fileCommands.size)
            assertTrue(transport.longCommands.isEmpty())
            assertFalse(source.exists())
            assertTrue(directory.listFiles().orEmpty().isEmpty())
        }
    }

    private fun systemCapture(client: IdentityAdmittingHelperClient, suStarts: AtomicInteger) = LogCapture.system(
        CoroutineScope(Dispatchers.IO),
        helperLines = { error("no stream in a backlog test") },
        helperDump = client::logcatDump,
        processStarter = { suStarts.incrementAndGet(); error("su must not start for the system backlog") },
    )

    @Test
    fun systemCaptureBacklogComesFromHelperDumpWithoutSu() {
        val dump = "OK\n[ 1790592713.123  123: 456 E/AndroidRuntime ]\n" +
            "java.lang.IllegalStateException: password=hunter2secret\\x0A    at Example.first(Example.kt:12)\n\n" +
            "[ 1790592713.124  123: 456 I/Example ]\nrecovered\n\n[ 1790592713.125  1: 2 I/Cut ]\npart"
        val transport = FakeHelperTransport(FakeGeneration(
            version = HelperBootstrapReply.Line("HELPER version=1.4.0 proto=1.4"),
            bytesReplies = mapOf("LOGCATDUMP 300" to dump.toByteArray()),
        ))
        val suStarts = AtomicInteger()
        val capture = systemCapture(IdentityAdmittingHelperClient(transport, nowNs = { 0L }), suStarts)
        try {
            assertEquals(
                listOf(
                    "[ 1790592713.123  123: 456 E/AndroidRuntime ]\n" +
                        "java.lang.IllegalStateException: password=***\n    at Example.first(Example.kt:12)",
                    "[ 1790592713.124  123: 456 I/Example ]\nrecovered",
                ),
                capture.initialBacklog(),
            )
            assertEquals(0, suStarts.get())
            assertEquals(listOf("LOGCATDUMP 300"), transport.byteCommands.map { it.command })
            assertEquals(transport.bootstrapCommands.single().session, transport.byteCommands.single().session)
        } finally {
            capture.close()
        }
    }

    @Test
    fun systemCaptureBacklogIsEmptyFromAPreDumpHelperWithoutSu() {
        for (version in listOf("HELPER version=1.3.1 proto=1.3", "HELPER version=2.0.0 proto=2.4", "ERR")) {
            val transport = FakeHelperTransport(FakeGeneration(
                version = HelperBootstrapReply.Line(version),
                bytesReplies = mapOf("LOGCATDUMP 300" to "OK\n[ 1.000 1: 2 I/X ]\nleak\n\n".toByteArray()),
            ))
            val suStarts = AtomicInteger()
            val capture = systemCapture(IdentityAdmittingHelperClient(transport, nowNs = { 0L }), suStarts)
            try {
                assertEquals(emptyList(), capture.initialBacklog(), version)
                assertEquals(0, suStarts.get(), version)
                assertTrue(transport.byteCommands.isEmpty(), version)
            } finally {
                capture.close()
            }
        }
    }

    @Test
    fun logcatDumpRefusalYieldsNoBacklog() {
        val transport = FakeHelperTransport(FakeGeneration(
            version = HelperBootstrapReply.Line("HELPER version=1.4.0 proto=1.4"),
            bytesReplies = mapOf("LOGCATDUMP 300" to "ERR\n".toByteArray()),
        ))
        assertEquals(emptyList(), IdentityAdmittingHelperClient(transport, nowNs = { 0L }).logcatDump(300))
        assertEquals(emptyList(), IdentityAdmittingHelperClient(transport, nowNs = { 0L }).logcatDump(1001))
        assertEquals(1, transport.byteCommands.size)
    }

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
    fun admittedLogcatStreamResumesAfterIdleErrnoWithoutLosingUtf8OrReopening() {
        val payload = "[ 1790592713.123 123: 456 W/Vendor ]\n温度\n\n".toByteArray()
        val utf8Pause = payload.indexOf(0xe6.toByte()) + 1
        val pauses = mutableSetOf(0, utf8Pause, payload.size - 1)
        var index = 0
        val source = object : java.io.InputStream() {
            override fun read(): Int {
                if (pauses.remove(index)) throw errno(android.system.OsConstants.EAGAIN)
                return if (index == payload.size) -1 else payload[index++].toInt() and 0xff
            }
            override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                // A syscall returns already-available bytes before reporting idle on a later read.
                var count = 0
                while (count < length) {
                    if (count > 0 && (index in pauses || index == payload.size)) break
                    val next = read()
                    if (next < 0) return if (count == 0) -1 else count
                    bytes[offset + count++] = next.toByte()
                }
                return count
            }
        }
        val transport = FakeHelperTransport(FakeGeneration(
            lineReplies = mapOf("LOGCATCAPS" to "LOGCATCAPS 1"), logcatInput = java.io.BufferedInputStream(source, 4096),
        ))
        val opened = assertIs<HelperLogcatOpenResult.Open>(IdentityAdmittingHelperClient(transport).openLogcat())
        val lines = mutableListOf<String>()
        var timeouts = 0
        opened.stream.use { stream ->
            while (true) {
                val line = try { stream.readLine() } catch (_: java.net.SocketTimeoutException) {
                    timeouts++
                    continue
                } ?: break
                lines += line
            }
        }
        assertEquals(listOf("[ 1790592713.123 123: 456 W/Vendor ]", "温度", ""), lines)
        assertEquals(3, timeouts)
        assertEquals(1, transport.bootstrapCommands.size)
        assertEquals(1, transport.closedSessions.size)
    }

    @Test
    fun admittedLogcatStreamPropagatesHardErrnoAndCloseUnblocksItsReader() {
        val entered = java.util.concurrent.CountDownLatch(1)
        val closed = java.util.concurrent.CountDownLatch(1)
        val source = object : java.io.InputStream() {
            override fun read(): Int {
                entered.countDown()
                check(closed.await(2, TimeUnit.SECONDS))
                throw errno(android.system.OsConstants.EAGAIN + 100)
            }
            override fun close() { closed.countDown() }
        }
        val transport = FakeHelperTransport(FakeGeneration(
            lineReplies = mapOf("LOGCATCAPS" to "LOGCATCAPS 1"), logcatInput = java.io.BufferedInputStream(source, 4096),
        ))
        val stream = assertIs<HelperLogcatOpenResult.Open>(IdentityAdmittingHelperClient(transport).openLogcat()).stream
        val executor = Executors.newSingleThreadExecutor()
        try {
            val result = executor.submit<Throwable?> {
                try { stream.readLine(); null } catch (failure: Throwable) { failure }
            }
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            stream.close()
            val failure = assertIs<IOException>(result.get(2, TimeUnit.SECONDS))
            assertFalse(failure is java.net.SocketTimeoutException)
            assertIs<android.system.ErrnoException>(failure.cause)
            assertEquals(1, transport.closedSessions.size)
        } finally {
            stream.close()
            executor.shutdownNow()
        }
    }

    private fun errno(number: Int): android.system.ErrnoException =
        android.system.ErrnoException("read", number).also {
            // android.jar's host constructor is a no-op; retain the real platform errno field.
            android.system.ErrnoException::class.java.getDeclaredField("errno").apply {
                isAccessible = true
                setInt(it, number)
            }
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
    val bytesReplies: Map<String, ByteArray?> = emptyMap(),
    val lineReplies: Map<String, String?> = emptyMap(),
    val streamReply: DaemonStreamResult = DaemonStreamResult.Reply("OK"),
    val logcatInput: java.io.InputStream? = null,
) {
    @Volatile var afterVersion: (() -> Unit)? = null
    @Volatile var afterPing: (() -> Unit)? = null
    @Volatile var afterStream: (() -> Unit)? = null

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
        val logcatReader = snapshot.logcatInput?.let(::HelperLogcatLineReader)
        return object : HelperCommandSession {
            override fun bootstrap(command: String, deadline: MonotonicDeadline): HelperBootstrapReply {
                bootstrapCommands += RecordedCommand(session, snapshot.id, command)
                return when (command) {
                    "VERSION" -> snapshot.version.also { snapshot.afterVersion?.invoke() }
                    "PING" -> snapshot.ping.also { snapshot.afterPing?.invoke() }
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
                return snapshot.streamReply.also { snapshot.afterStream?.invoke() }
            }

            override fun sendBytes(cmd: String, maxBytes: Long): ByteArray? {
                byteCommands += RecordedBytes(session, snapshot.id, cmd, maxBytes)
                return snapshot.bytesReplies.getOrElse(cmd) { byteArrayOf(1, 2, 3) }
            }

            override fun readLogcatLine(): String? = logcatReader?.readLine()

            override fun backupCompanion(
                packageName: String,
                cacheDir: File,
                timeoutMs: Long,
                hold: Boolean,
            ): CompanionHelperProtocol.BackupResult {
                companionCommands += RecordedCommand(session, snapshot.id, "BACKUP $packageName")
                return CompanionHelperProtocol.BackupResult.Busy
            }

            override fun restoreCompanion(
                packageName: String,
                files: Map<String, File>,
                timeoutMs: Long,
                expected: CompanionHelperProtocol.LiveDigest?,
            ): CompanionHelperProtocol.RestoreResult {
                companionCommands += RecordedCommand(session, snapshot.id, "RESTORE $packageName")
                return CompanionHelperProtocol.RestoreResult.COMMITTED
            }

            override fun close() { snapshot.logcatInput?.close(); closedSessions += session }
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
