package io.panelassistant.android.util

import io.panelassistant.android.testsupport.scratchDirectory

import io.panelassistant.android.platform.Daemon
import io.panelassistant.android.panelassistant.RootLocalAdbEndpoint
import io.panelassistant.android.platform.DaemonLongResult
import io.panelassistant.android.platform.DaemonStreamResult
import java.io.File
import java.net.SocketAddress
import java.nio.channels.Channels
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.SocketChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermissions
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Assume.assumeTrue
import io.panelassistant.android.platform.HelperRelayClass
import io.panelassistant.android.platform.HelperCdpState
import io.panelassistant.android.platform.HelperGpioState
import org.junit.AfterClass
import org.junit.BeforeClass
import org.junit.Test

class HelperSocketCompositionTest {
    @Test(timeout = 30_000)
    fun allRecoveryVerbsCrossTheAdmittedMainSocketAndRealLifecycleOwner() {
        val executable = File(requireNotNull(System.getProperty("hapaneld.helper.socketTestServer"))).absoluteFile.parentFile.resolve("adb-recovery")
        assertTrue(executable.isFile, "native lifecycle fixture was not built")
        for (chunk in listOf(Int.MAX_VALUE, 1, 3)) {
            val mainPath = Path.of(System.getProperty("java.io.tmpdir"), "hapaneld-main-${UUID.randomUUID()}.sock")
            val fixture = ProcessBuilder(executable.toString(), "--serve").apply {
                environment()["HAPANELD_MAIN_FIXTURE_PATH"] = mainPath.toString()
            }.redirectErrorStream(true).start()
            try {
                val started = java.util.concurrent.CompletableFuture.supplyAsync { fixture.inputStream.bufferedReader().readLine() }
                    .get(5, TimeUnit.SECONDS)
                assertEquals(mainPath.toString(), started)
                assertTrue(Files.exists(mainPath), "real main socket did not bind")
                val sessions = mutableListOf<MutableList<String>>()
                val transport = ChannelSessionTransport(mainPath, chunk, sessions)
                val endpoint = RootLocalAdbEndpoint(IdentityAdmittingHelperClient(transport, expectedBuildId = FIXTURE_BUILD_ID))
                val nonce = "a".repeat(64)
                assertEquals(FIXTURE_BUILD_ID, assertNotNull(endpoint.state()).buildId)
                assertTrue(endpoint.arm(nonce))
                assertTrue(endpoint.beat(nonce))
                val program = "printf '\\000\\nX'".toByteArray()
                val shell = assertNotNull(endpoint.shell(program, 1_000) { it(); true })
                assertEquals("AApY", shell.getString("stdout_b64"))
                assertEquals(0, shell.getInt("exit_code"))
                assertEquals(true, endpoint.recover(nonce) { it(); true })
                assertEquals(listOf("STATUS", "ARM $nonce", "BEAT $nonce", "SHELL 1000 ${program.size}", "RECOVER $nonce"), sessions.map { it.last() })
                sessions.forEach { assertEquals(listOf("VERSION", "BUILDID"), it.take(2)); assertEquals(3, it.size) }
                for (stale in listOf("VERSION", "BUILDID")) {
                    val sessions = mutableListOf<MutableList<String>>()
                    val transport = ChannelSessionTransport(mainPath, sessions = sessions, bootstrapReply = { command, reply ->
                        if (stale == "VERSION" && command == "VERSION") "HELPER version=1.15.0 proto=1.15" else reply
                    })
                    val expectedBuild = if (stale == "BUILDID") "b".repeat(64) else FIXTURE_BUILD_ID
                    assertNull(RootLocalAdbEndpoint(IdentityAdmittingHelperClient(transport, expectedBuildId = expectedBuild)).state())
                    assertEquals(1, sessions.size)
                    assertEquals(if (stale == "VERSION") listOf("VERSION") else listOf("VERSION", "BUILDID"), sessions.single())
                }
            } finally {
                // ProcessHandle is host-only and absent from the Android compilation API.
                val handleType = Class.forName("java.lang.ProcessHandle")
                val handle = Process::class.java.getMethod("toHandle").invoke(fixture)
                val descendants = (handleType.getMethod("descendants").invoke(handle) as java.util.stream.Stream<*>)
                    .use { it.toArray().toList() }
                fixture.outputStream.close()
                if (!fixture.waitFor(10, TimeUnit.SECONDS)) fixture.destroyForcibly().waitFor(3, TimeUnit.SECONDS)
                descendants.asReversed().forEach {
                    if (handleType.getMethod("isAlive").invoke(it) == true) handleType.getMethod("destroyForcibly").invoke(it)
                }
                Files.deleteIfExists(mainPath)
            }
            assertEquals(0, fixture.exitValue(), "native lifecycle fixture cleanup failed")
        }
    }

    @Test(timeout = 10_000)
    fun recoveryBootstrapRefusesTheHostFixtureBuildAndInvalidArgumentsBeforePublication() {
        // This host fixture advertises a fixed test build, not the app's expected embedded digest.
        val daemon = IdentityAdmittingHelperClient(ChannelSessionTransport(socketPath))
        assertFalse(daemon.supportsAdbRecoveryEnsure())
        assertFalse(daemon.ensureAdbRecovery())
        assertEquals("BUILDID ${FIXTURE_BUILD_ID}", SocketDaemon(socketPath).send("BUILDID"))
        assertEquals("ERR", SocketDaemon(socketPath).send("ADBRECOVERYENSURE extra"))
    }

    @Test(timeout = 10_000)
    fun fixedSelfGfxCountersCrossTheAdmittedNativeSocket() {
        val daemon = IdentityAdmittingHelperClient(ChannelSessionTransport(socketPath))
        assertEquals(io.panelassistant.android.platform.HelperGfxInfo("123456789", 120, 7), daemon.gfxInfo())
        for (request in listOf("GFXINFO", "GFXINFO other.package", "GFXINFO SELF reset", "GFXINFO 123")) {
            assertEquals("ERR", SocketDaemon(socketPath).send(request))
        }
    }

    @Test(timeout = 10_000)
    fun concurrentNativeSocketKeepsItsOwnGuardCustody() {
        val root = scratchDirectory("hapaneld-helper-guard-")
        val socket = Path.of(System.getProperty("java.io.tmpdir"), "hapaneld-helper-${UUID.randomUUID()}.sock")
        var second: Process? = null
        try {
            second = ProcessBuilder(
                System.getProperty("hapaneld.helper.socketTestServer"), socket.toString(), root.toString(),
            ).redirectErrorStream(true).start()
            assertEquals("READY", second.inputStream.bufferedReader().readLine())
            assertTrue(Files.isDirectory(root.resolve(".hapaneld-guard-db-test")))
            assertEquals("HELPER version=1.16.0 proto=1.16", SocketDaemon(socket).send("VERSION"))
        } finally {
            second?.let {
                it.destroy()
                if (!it.waitFor(5, TimeUnit.SECONDS)) it.destroyForcibly().waitFor()
            }
            Files.deleteIfExists(socket)
            deleteGuardRoot(root)
        }
    }

    @Test(timeout = 10_000)
    fun textFramingCrossesNativeServerAndExactDispatch() {
        val daemon = SocketDaemon(socketPath)

        assertTrue(daemon.available())
        assertEquals("HELPER version=1.16.0 proto=1.16", daemon.send("VERSION"))
        assertEquals("ERR", daemon.send("PINGEXTRA"))
        assertEquals("OK", daemon.sendLong("RELOAD io.example.dashboard", 5_000).replyValue())
    }

    /**
     * The app and root helper intentionally have no generated protocol. Keep the commonly emitted
     * line forms as a small executable corpus at the real Kotlin-to-C socket boundary: an accepted
     * request, a host-safe reply, and a malformed sibling for each capability family.  This catches
     * framing or dispatch drift which the separate manifest check cannot see.
     */
    @Test(timeout = 10_000)
    fun appWireCompatibilityCorpusCrossesKotlinAndNativeHelper() {
        val daemon = SocketDaemon(socketPath)
        // The host fixture redirects fixed governor reads and refuses governor writes.
        listOf(
            WireTranscript("VERSION", "HELPER version=1.16.0 proto=1.16"),
            WireTranscript("PING", "OK"),
            WireTranscript("BUILDID", "BUILDID ${FIXTURE_BUILD_ID}"),
            WireTranscript("COMPANIONCAPS", "COMPANIONCAPS 1 BACKUP RESTORE STATUS JOURNAL"),
            WireTranscript("COMPANIONSTATUS", "IDLE"),
            WireTranscript("LOGCATCAPS", "LOGCATCAPS 1"),
            WireTranscript("LOGCATCAPS extra", "ERR"),
            WireTranscript("LOGCAT ;sh", "ERR"),
            WireTranscript("LEDPROBE", "none"),
            WireTranscript("RGB 1 2 3", "ERR"),
            WireTranscript("OFF", "ERR"),
            WireTranscript("BTN 1", "ERR"),
            WireTranscript("BLPOWER", "ERR"),
            WireTranscript("BLREAD", "ERR"),
            WireTranscript("BLSET 42", "ERR"),
            WireTranscript("SCREEN ON", "ERR"),
            // The two named keys are the whole accepted vocabulary: anything numeric, unknown or
            // differently cased must be refused before it can reach `input`.
            WireTranscript("KEYEVENT SLEEP", "OK"),
            WireTranscript("KEYEVENT WAKEUP", "OK"),
            WireTranscript("KEYEVENT 26", "ERR"),
            WireTranscript("KEYEVENT POWER", "ERR"),
            WireTranscript("KEYEVENT", "ERR"),
            WireTranscript("REBOOT NOW", "ERR"),
            WireTranscript("START io.homeassistant.companion.android/.Home", "OK"),
            WireTranscript("RELOAD io.homeassistant.companion.android", "OK"),
            WireTranscript("SETHOME io.homeassistant.companion.android/.Home", "OK"),
            WireTranscript("APPSTATE io.homeassistant.companion.android", "ERR"),
            WireTranscript("DENSITY 240", "OK"),
            WireTranscript("FONTSCALE 1.0", "OK"),
            WireTranscript("GOV performance", "ERR"),
            WireTranscript("GOVREAD CURRENT", "GOVREAD Vendor_1.fast-2"),
            WireTranscript("GOVREAD AVAILABLE", "GOVREAD powersave Vendor_1.fast-2 performance"),
            WireTranscript("GOVREAD", "ERR"),
            WireTranscript("GOVREAD CURRENT extra", "ERR"),
            WireTranscript("GOVREAD current", "ERR"),
            WireTranscript("GOVREAD /path", "ERR"),
            WireTranscript("RELAYREAD STRELAY COUNT", "RELAYREAD STRELAY COUNT=2"),
            WireTranscript("RELAYREAD ST_RELAY COUNT", "RELAYREAD ST_RELAY COUNT=1"),
            WireTranscript("RELAYREAD RELAY COUNT", "RELAYREAD RELAY COUNT=0"),
            WireTranscript("RELAYREAD STRELAY 1", "RELAYREAD STRELAY RELAY=1 VALUE=1"),
            WireTranscript("RELAYREAD STRELAY 2", "RELAYREAD STRELAY RELAY=2 VALUE=0"),
            WireTranscript("RELAYREAD STRELAY 01", "ERR"),
            WireTranscript("RELAYREAD /sys/class/strelay COUNT", "ERR"),
            WireTranscript("RELAYREAD STRELAY COUNT extra", "ERR"),
            WireTranscript("GPIOREAD 147", "GPIOREAD 147 VALUE=1 PREPARED=1"),
            WireTranscript("GPIOREAD 148", "GPIOREAD 148 VALUE=? PREPARED=1"),
            WireTranscript("GPIOREAD 149", "GPIOREAD 149 VALUE=0 PREPARED=0"),
            WireTranscript("GPIOREAD 0147", "ERR"),
            WireTranscript("GPIOREAD 65536", "ERR"),
            WireTranscript("GPIOREAD 147 extra", "ERR"),
            WireTranscript("ADBSTATE", "PROPS=1 LISTEN=0"),
            WireTranscript("ADBSTATE extra", "ERR"),
            WireTranscript("ADBSTATE /path", "ERR"),
            WireTranscript("WIFISTATUS", "SSID=${"Home, β".zigbeeHex()} RSSI=-63"),
            WireTranscript("WIFISTATUS extra", "ERR"),
            WireTranscript("WIFISTATUS /path", "ERR"),
            WireTranscript("ZIGBEESTATE extra", "ERR"),
            WireTranscript("ZIGBEEHEALTH /path", "ERR"),
            WireTranscript("ZIGBEEROLE extra", "ERR"),
            WireTranscript("ZIGBEECONTAIN", "OK"),
            WireTranscript("VERSION unexpected", "ERR"),
            WireTranscript("PINGEXTRA", "ERR"),
            WireTranscript("RGB 1 2", "ERR"),
            WireTranscript("SETHOME ;reboot", "ERR"),
            WireTranscript("ZIGBEECONTAIN unexpected", "ERR"),
        ).forEach { (request, reply) ->
            assertEquals(reply, daemon.send(request), request)
        }
    }

    @Test(timeout = 10_000)
    fun connectionScopedIdentityAndOperationCrossTheNativeServerOnOneSocket() {
        val daemon = IdentityAdmittingHelperClient(ChannelSessionTransport(socketPath), nowNs = { 0L })

        assertTrue(daemon.available())
        assertEquals("OK", daemon.send("RELOAD io.example.dashboard"))
        assertContentEquals("PNG\nfixture\n".toByteArray(), daemon.sendBytesBounded("SCREENCAP", 1024L))
    }

    @Test(timeout = 10_000)
    fun typedSysfsReadsCrossNativeFixedSelectorsWithoutChangingFiles() {
        val before = sysfsReadRoot.toFile().walkTopDown().filter { it.isFile }.associate { it.relativeTo(sysfsReadRoot.toFile()).path to it.readText() }
        val daemon = IdentityAdmittingHelperClient(ChannelSessionTransport(socketPath))
        assertEquals(2, daemon.relayCount(HelperRelayClass.STRELAY))
        assertEquals(1, daemon.relayCount(HelperRelayClass.ST_RELAY))
        assertEquals(0, daemon.relayCount(HelperRelayClass.RELAY))
        assertEquals(true, daemon.relayRead(HelperRelayClass.STRELAY, 1))
        assertEquals(false, daemon.relayRead(HelperRelayClass.STRELAY, 2))
        assertEquals(HelperGpioState(true, true), daemon.gpioRead(147))
        assertEquals(HelperGpioState(null, true), daemon.gpioRead(148))
        assertEquals(HelperGpioState(false, false), daemon.gpioRead(149))
        assertEquals(before, sysfsReadRoot.toFile().walkTopDown().filter { it.isFile }.associate { it.relativeTo(sysfsReadRoot.toFile()).path to it.readText() })
    }

    @Test(timeout = 10_000)
    fun typedAdbCrossesVersionAdmissionAndNativeFixedObservations() {
        val daemon = IdentityAdmittingHelperClient(ChannelSessionTransport(socketPath))
        val state = requireNotNull(daemon.adbState())
        assertEquals(true, state.properties)
        assertEquals(false, state.listener)
    }

    @Test(timeout = 10_000)
    fun typedGovernorsCrossVersionAdmissionAndNativeFixedPathReader() {
        val daemon = IdentityAdmittingHelperClient(ChannelSessionTransport(socketPath))
        assertEquals("Vendor_1.fast-2", daemon.cpuGovernor())
        assertEquals(listOf("powersave", "Vendor_1.fast-2", "performance"), daemon.cpuAvailableGovernors())
    }

    @Test(timeout = 10_000)
    fun typedWifiStatusCrossesNativeCurrentRecordParserWithoutIdentifiers() {
        val daemon = IdentityAdmittingHelperClient(ChannelSessionTransport(socketPath))
        val status = assertNotNull(daemon.wifiStatus())
        assertEquals("Home, β", status.ssid)
        assertEquals(-63, status.rssiDbm)
        assertFalse(status.toString().contains("00:11:22:33:44:55"))
    }

    @Test(timeout = 10_000)
    fun typedZigbeeReadsCrossNativeFixedPathsAndBoundedFrames() {
        val daemon = IdentityAdmittingHelperClient(ChannelSessionTransport(socketPath))
        val state = assertNotNull(daemon.zigbeeState())
        assertEquals(managedZigbeeFiles, state.files)
        assertEquals("sonoff-v3.5.4:sonoff-3.5.0", state.packageVersion)
        assertFalse(state.running)
        assertFalse(state.guardRunning)
        val health = assertNotNull(daemon.zigbeeHealth())
        assertEquals(state, health.state)
        assertEquals(558L, health.totalJiffies)
        assertNull(health.gateway)
        assertNull(health.guard)
        assertFalse(health.recursiveWatchdogAssignment)
        assertEquals("""{"roleType":1,"nodeId":123,"panId":456}""", health.netInfo)
        assertEquals("Repeater", daemon.zigbeeRole())
    }

    @Test(timeout = 10_000)
    fun strictCdpObservationCrossesNativeProcessAndListenerInventory() {
        val daemon = IdentityAdmittingHelperClient(ChannelSessionTransport(socketPath))
        assertEquals(HelperCdpState.ABSENT, daemon.cdpState())
        val pid = Files.createDirectory(cdpRoot.resolve("123"))
        try {
            Files.writeString(pid.resolve("comm"), "cdprelay\n")
            assertEquals(HelperCdpState.PRESENT, daemon.cdpState())
            Files.writeString(pid.resolve("comm"), "other\n")
            assertEquals(HelperCdpState.ABSENT, daemon.cdpState())
            Files.delete(cdpRoot.resolve("net/tcp"))
            assertEquals(HelperCdpState.UNKNOWN, daemon.cdpState())
            Files.writeString(pid.resolve("comm"), "cdprelay\n")
            assertEquals(HelperCdpState.PRESENT, daemon.cdpState())
            assertEquals("ERR", SocketDaemon(socketPath).send("CDPSTATE extra"))
        } finally {
            pid.toFile().deleteRecursively()
            Files.writeString(cdpRoot.resolve("net/tcp"), TCP_HEADER)
        }
    }

    @Test(timeout = 10_000)
    fun connectionScopedAdmissionComposesWithNativeStreamAndCompanionFraming() {
        val daemon = IdentityAdmittingHelperClient(ChannelSessionTransport(socketPath), nowNs = { 0L })
        val directory = scratchDirectory("helper-admitted-stream-").toFile()
        val source = File(directory, "fixture.apk").apply { writeBytes(byteArrayOf(0x50, 0x4b, 0, 4, 0x7f)) }
        try {
            assertEquals(
                DaemonStreamResult.Reply("OK"),
                daemon.sendFile("INSTALLSTREAM ${source.length()}", source, 5_000L),
            )
            assertEquals(
                CompanionHelperProtocol.BackupResult.Failed(relaunchFailed = false),
                daemon.backupCompanion("io.example.invalid", directory, 5_000L),
            )
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test(timeout = 10_000)
    fun binaryReplyUsesHalfCloseAndReadsThroughNativeEof() {
        assertContentEquals("PNG\nfixture\n".toByteArray(), SocketDaemon(socketPath).sendBytes("SCREENCAP"))
    }

    @Test(timeout = 10_000)
    fun installTransactionCompletesTwoPhaseNativeStreamAndReleasesInput() {
        val directory = scratchDirectory("helper-socket-install-").toFile()
        val source = File(directory, "fixture.apk").apply { writeBytes(byteArrayOf(0x50, 0x4b, 0, 4, 0x7f)) }
        try {
            assertEquals(InstallOutcome.Succeeded, HelperInstallTransaction(SocketDaemon(socketPath)).install(source, File(directory, "staging")))
            assertFalse(source.exists())
            assertFalse(File("/tmp/hapaneld-helper-install-stream-test.apk").exists())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test(timeout = 10_000)
    fun nativeStreamPreflightRejectsInvalidLengthBeforeOpeningPayload() {
        var opened = false
        val result = SocketDaemon(socketPath).withConnection { channel ->
            HelperSocketProtocol.sendFile(
                command = "INSTALLSTREAM 0",
                openSource = { opened = true; error("native preflight must reject before payload") },
                expectedBytes = 0,
                input = Channels.newInputStream(channel),
                output = Channels.newOutputStream(channel),
                shutdownOutput = channel::shutdownOutput,
            )
        }

        assertEquals(DaemonStreamResult.Reply("STREAMERR"), result)
        assertFalse(opened)
    }

    private class SocketDaemon(private val path: Path) : Daemon {
        override fun available(): Boolean = send("PING") == "OK"

        override fun send(cmd: String): String? = withConnection { channel ->
            HelperSocketProtocol.sendLine(cmd, Channels.newInputStream(channel), Channels.newOutputStream(channel))
        }

        override fun sendLong(cmd: String, timeoutMs: Long): DaemonLongResult = send(cmd)
            ?.let(DaemonLongResult::Reply)
            ?: DaemonLongResult.Indeterminate

        override fun sendFile(cmd: String, source: File, timeoutMs: Long): DaemonStreamResult = withConnection { channel ->
            HelperSocketProtocol.sendFile(
                command = cmd,
                openSource = source::inputStream,
                expectedBytes = source.length(),
                input = Channels.newInputStream(channel),
                output = Channels.newOutputStream(channel),
                shutdownOutput = channel::shutdownOutput,
            )
        }

        override fun sendBytes(cmd: String): ByteArray? = withConnection { channel ->
            HelperSocketProtocol.sendBytes(
                command = cmd,
                input = Channels.newInputStream(channel),
                output = Channels.newOutputStream(channel),
                shutdownOutput = channel::shutdownOutput,
            )
        }

        fun <T> withConnection(block: (SocketChannel) -> T): T = SocketChannel.open(unixAddress(path)).use { channel ->
            block(channel)
        }

        private fun unixAddress(path: Path): SocketAddress = Class.forName("java.net.UnixDomainSocketAddress")
            .getMethod("of", Path::class.java)
            .invoke(null, path) as SocketAddress
    }

    private data class WireTranscript(val request: String, val reply: String)

    private class ChannelSessionTransport(
        private val path: Path,
        private val recoveryWriteChunk: Int = Int.MAX_VALUE,
        private val sessions: MutableList<MutableList<String>>? = null,
        private val bootstrapReply: (String, String) -> String = { _, reply -> reply },
    ) : HelperCommandTransport {
        override fun open(): HelperCommandSession? = runCatching {
            val transcript = mutableListOf<String>().also { sessions?.add(it) }
            ChannelSession(SocketChannel.open(unixAddress(path)), recoveryWriteChunk, transcript, bootstrapReply)
        }.getOrNull()

        private fun unixAddress(path: Path): SocketAddress = Class.forName("java.net.UnixDomainSocketAddress")
            .getMethod("of", Path::class.java)
            .invoke(null, path) as SocketAddress
    }

    private class ChannelSession(
        private val channel: SocketChannel,
        private val recoveryWriteChunk: Int,
        private val transcript: MutableList<String>,
        private val bootstrapReply: (String, String) -> String,
    ) : HelperCommandSession {
        private val input = Channels.newInputStream(channel)
        private val output = Channels.newOutputStream(channel)
        private val logcatReader = HelperLogcatLineReader(input)

        override fun bootstrap(command: String, deadline: MonotonicDeadline): HelperBootstrapReply {
            output.apply { write((command + "\n").toByteArray()); flush() }
            transcript += command
            val reply = readHelperBootstrapLine(input, {}, deadline)
            return if (reply is HelperBootstrapReply.Line) HelperBootstrapReply.Line(bootstrapReply(command, reply.value)) else reply
        }

        override fun send(cmd: String): String? = HelperSocketProtocol.sendLine(cmd, input, output)

        override fun sendLong(cmd: String, timeoutMs: Long): DaemonLongResult = send(cmd)
            ?.let(DaemonLongResult::Reply)
            ?: DaemonLongResult.Indeterminate

        override fun sendFile(cmd: String, source: File, timeoutMs: Long): DaemonStreamResult =
            HelperSocketProtocol.sendFile(
                command = cmd,
                openSource = source::inputStream,
                expectedBytes = source.length(),
                input = input,
                output = output,
                shutdownOutput = channel::shutdownOutput,
            )

        override fun sendBytes(cmd: String, maxBytes: Long): ByteArray? = HelperSocketProtocol.sendBytes(
            command = cmd,
            input = input,
            output = output,
            shutdownOutput = channel::shutdownOutput,
            maxBytes = maxBytes,
        )

        override fun adbRecoveryExchange(header: String, payload: ByteArray, timeoutMs: Int, submit: (() -> Unit) -> Boolean): ByteArray? =
            HelperSocketProtocol.adbRecoveryExchange(header, payload, input, timeoutMs, submit,
                write = { bytes ->
                    transcript += header
                    var at = 0
                    while (at < bytes.size) {
                        val count = minOf(recoveryWriteChunk, bytes.size - at)
                        output.write(bytes, at, count); output.flush(); at += count
                    }
                }, beforeRead = {},
            )

        override fun readLogcatLine(): String? = logcatReader.readLine()

        override fun backupCompanion(
            packageName: String,
            cacheDir: File,
            timeoutMs: Long,
            hold: Boolean,
        ): CompanionHelperProtocol.BackupResult =
            CompanionHelperProtocol.backup(packageName, cacheDir, input, output, hold)

        override fun restoreCompanion(
            packageName: String,
            files: Map<String, File>,
            timeoutMs: Long,
            expected: CompanionHelperProtocol.LiveDigest?,
        ): CompanionHelperProtocol.RestoreResult = error("not used by this composition fixture")

        override fun close() {
            channel.close()
        }
    }

    private companion object {
        lateinit var socketPath: Path
        lateinit var guardRoot: Path
        lateinit var server: Process
        lateinit var sysfsReadRoot: Path
        lateinit var zigbeeRoot: Path
        lateinit var cdpRoot: Path
        private const val FIXTURE_BUILD_ID = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        private const val TCP_HEADER = "  sl  local_address rem_address st tx_queue rx_queue tr tm->when retrnsmt uid timeout inode\n"
        lateinit var fixtureLock: FileLock

        @JvmStatic
        @BeforeClass
        fun startServer() {
            val executablePath = System.getProperty("hapaneld.helper.socketTestServer")
            assumeTrue("native UNIX-socket composition requires a Linux host", executablePath != null)
            val executable = File(requireNotNull(executablePath))
            assertTrue(executable.isFile, "native socket test server was not built")
            // INSTALLSTREAM still uses one fixed staging file across JVMs.
            val lockPath = Path.of("/tmp", "hapaneld-helper-socket-composition.lock")
            fixtureLock = FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE).lock()
            runCatching { Files.setPosixFilePermissions(lockPath, PosixFilePermissions.fromString("rw-rw-rw-")) }
            guardRoot = scratchDirectory("hapaneld-helper-guard-")
            socketPath = Path.of(System.getProperty("java.io.tmpdir"), "hapaneld-helper-${UUID.randomUUID()}.sock")
            val current = guardRoot.resolve("governor.current")
            val available = guardRoot.resolve("governor.available")
            Files.writeString(current, "Vendor_1.fast-2\n")
            Files.writeString(available, "powersave\tVendor_1.fast-2  performance\n")
            val tcp = guardRoot.resolve("adb.tcp")
            val tcp6 = guardRoot.resolve("adb.tcp6")
            for (path in listOf(tcp, tcp6)) Files.writeString(path, "  sl  local_address rem_address   st tx_queue rx_queue tr tm->when retrnsmt   uid  timeout inode\n")
            sysfsReadRoot = executable.parentFile.toPath().resolve("sysfs-read-socket-fixture")
            sysfsReadRoot.toFile().deleteRecursively()
            for (name in listOf("relay", "strelay", "st_relay", "gpio")) Files.createDirectories(sysfsReadRoot.resolve(name))
            Files.writeString(sysfsReadRoot.resolve("strelay/relay1"), "1\n")
            Files.writeString(sysfsReadRoot.resolve("strelay/relay2"), "0\n")
            Files.writeString(sysfsReadRoot.resolve("st_relay/relay1"), "1\n")
            for ((pin, value, direction) in listOf(Triple(147, "1", "out"), Triple(148, "bad", "out"), Triple(149, "0", "in"))) {
                val directory = Files.createDirectories(sysfsReadRoot.resolve("gpio/gpio$pin"))
                Files.writeString(directory.resolve("value"), "$value\n")
                Files.writeString(directory.resolve("direction"), "$direction\n")
            }
            cdpRoot = executable.parentFile.toPath().resolve("cdp-state-socket-fixture")
            cdpRoot.toFile().deleteRecursively()
            Files.createDirectories(cdpRoot.resolve("net"))
            Files.writeString(cdpRoot.resolve("net/tcp"), TCP_HEADER)
            zigbeeRoot = executable.parentFile.toPath().resolve("zigbee-socket-fixture")
            zigbeeRoot.toFile().deleteRecursively()
            val vendor = Files.createDirectories(zigbeeRoot.resolve("vendor"))
            Files.createDirectories(zigbeeRoot.resolve("proc"))
            Files.createDirectories(zigbeeRoot.resolve("data"))
            for (name in managedZigbeeFiles) Files.writeString(vendor.resolve(name), "fixture\n")
            Files.writeString(vendor.resolve("package_version"), "sonoff-v3.5.4:sonoff-3.5.0\n")
            Files.writeString(vendor.resolve("mosquitto_sub"), "fixture\n")
            for (name in listOf("zgateway", "mosquitto", "mosquitto_sub")) {
                Files.setPosixFilePermissions(vendor.resolve(name), PosixFilePermissions.fromString("rwx------"))
            }
            Files.writeString(zigbeeRoot.resolve("proc/stat"), "cpu 100 2 30 400 5 6 7 8 20 1\n")
            Files.writeString(zigbeeRoot.resolve("data/netinfo"), """{"roleType":1,"nodeId":123,"panId":456}""")
            server = ProcessBuilder(executable.absolutePath, socketPath.toString(), guardRoot.toString())
                .apply {
                    environment()["HAPANELD_TEST_GFXINFO"] =
                        "** Graphics info for pid 42 [io.panelassistant.android] **\nStats since: 123456789ns\nTotal frames rendered: 120\nJanky frames: 7 (5.83%)\nJanky frames (legacy): 99 (82.5%)\n"
                    environment()["HAPANELD_TEST_GOV_CURRENT"] = current.toString()
                    environment()["HAPANELD_TEST_GOV_AVAILABLE"] = available.toString()
                    environment()["HAPANELD_TEST_ZIGBEE_ROLE"] = """{"role":"Repeater"}"""
                    for ((index, value) in listOf("", "5555", "-1", "0", "0").withIndex()) environment()["HAPANELD_TEST_ADB_PROP_$index"] = value
                    environment()["HAPANELD_TEST_ADB_TCP"] = tcp.toString()
                    environment()["HAPANELD_TEST_ADB_TCP6"] = tcp6.toString()
                    environment()["HAPANELD_TEST_WIFI_STATUS"] =
                        "WifiInfo: SSID: \"Home, β\", BSSID: 00:11:22:33:44:55, RSSI: -63, Link speed: 433Mbps\n"
                }
                .redirectErrorStream(true)
                .start()
            val ready = server.inputStream.bufferedReader().readLine()
            assertEquals("READY", ready, "native socket test server did not start")
            assertTrue(server.isAlive)
        }

        @JvmStatic
        @AfterClass
        fun stopServer() {
            if (::server.isInitialized) {
                server.destroy()
                if (!server.waitFor(5, TimeUnit.SECONDS)) server.destroyForcibly().waitFor()
            }
            if (::socketPath.isInitialized) Files.deleteIfExists(socketPath)
            Files.deleteIfExists(Path.of("/tmp/hapaneld-helper-install-stream-test.apk"))
            if (::guardRoot.isInitialized) deleteGuardRoot(guardRoot)
            if (::sysfsReadRoot.isInitialized) sysfsReadRoot.toFile().deleteRecursively()
            if (::cdpRoot.isInitialized) cdpRoot.toFile().deleteRecursively()
            if (::zigbeeRoot.isInitialized) zigbeeRoot.toFile().deleteRecursively()
            if (::fixtureLock.isInitialized) fixtureLock.channel().close()
        }

        private fun deleteGuardRoot(root: Path) {
            val custody = root.resolve(".hapaneld-guard-db-test")
            Files.deleteIfExists(custody.resolve(".owner.lock"))
            Files.deleteIfExists(custody)
            Files.deleteIfExists(root.resolve("governor.current"))
            Files.deleteIfExists(root.resolve("governor.available"))
            Files.deleteIfExists(root.resolve("adb.tcp"))
            Files.deleteIfExists(root.resolve("adb.tcp6"))
            Files.deleteIfExists(root)
        }

        fun DaemonLongResult.replyValue(): String? = (this as? DaemonLongResult.Reply)?.value


    }
}
