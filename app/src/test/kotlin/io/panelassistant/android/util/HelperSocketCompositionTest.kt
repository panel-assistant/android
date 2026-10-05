package io.panelassistant.android.util

import io.panelassistant.android.platform.Daemon
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
import kotlin.test.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.AfterClass
import org.junit.BeforeClass
import org.junit.Test

class HelperSocketCompositionTest {
    @Test(timeout = 10_000)
    fun concurrentNativeSocketKeepsItsOwnGuardCustody() {
        val root = Files.createTempDirectory("hapaneld-helper-guard-")
        val socket = Path.of(System.getProperty("java.io.tmpdir"), "hapaneld-helper-${UUID.randomUUID()}.sock")
        var second: Process? = null
        try {
            second = ProcessBuilder(
                System.getProperty("hapaneld.helper.socketTestServer"), socket.toString(), root.toString(),
            ).redirectErrorStream(true).start()
            assertEquals("READY", second.inputStream.bufferedReader().readLine())
            assertTrue(Files.isDirectory(root.resolve(".hapaneld-guard-db-test")))
            assertEquals("HELPER version=1.3.1 proto=1.3", SocketDaemon(socket).send("VERSION"))
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
        assertEquals("HELPER version=1.3.1 proto=1.3", daemon.send("VERSION"))
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
        // GOV writes sysfs directly and fails closed when the cpufreq nodes cannot be written.
        // A privileged run on a host that exposes them can write one, so take the expected reply
        // from the same condition the helper itself tests rather than assuming an outcome.
        val governorReply = if (governorAcceptsWrites()) "OK" else "ERR"
        listOf(
            WireTranscript("VERSION", "HELPER version=1.3.1 proto=1.3"),
            WireTranscript("PING", "OK"),
            WireTranscript("BUILDID", "BUILDID development"),
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
            WireTranscript("GOV performance", governorReply),
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
    fun connectionScopedAdmissionComposesWithNativeStreamAndCompanionFraming() {
        val daemon = IdentityAdmittingHelperClient(ChannelSessionTransport(socketPath), nowNs = { 0L })
        val directory = Files.createTempDirectory("helper-admitted-stream-").toFile()
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
        val directory = Files.createTempDirectory("helper-socket-install-").toFile()
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

    private class ChannelSessionTransport(private val path: Path) : HelperCommandTransport {
        override fun open(): HelperCommandSession? = runCatching {
            ChannelSession(SocketChannel.open(unixAddress(path)))
        }.getOrNull()

        private fun unixAddress(path: Path): SocketAddress = Class.forName("java.net.UnixDomainSocketAddress")
            .getMethod("of", Path::class.java)
            .invoke(null, path) as SocketAddress
    }

    private class ChannelSession(private val channel: SocketChannel) : HelperCommandSession {
        private val input = Channels.newInputStream(channel)
        private val output = Channels.newOutputStream(channel)
        private val logcatReader = HelperLogcatLineReader(input)

        override fun bootstrap(command: String, deadline: MonotonicDeadline): HelperBootstrapReply {
            output.apply { write((command + "\n").toByteArray()); flush() }
            return readHelperBootstrapLine(input, {}, deadline)
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

        override fun readLogcatLine(): String? = logcatReader.readLine()

        override fun backupCompanion(
            packageName: String,
            cacheDir: File,
            timeoutMs: Long,
        ): CompanionHelperProtocol.BackupResult =
            CompanionHelperProtocol.backup(packageName, cacheDir, input, output)

        override fun restoreCompanion(
            packageName: String,
            files: Map<String, File>,
            timeoutMs: Long,
        ): CompanionHelperProtocol.RestoreResult = error("not used by this composition fixture")

        override fun close() {
            channel.close()
        }
    }

    private companion object {
        lateinit var socketPath: Path
        lateinit var guardRoot: Path
        lateinit var server: Process
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
            guardRoot = Files.createTempDirectory("hapaneld-helper-guard-")
            socketPath = Path.of(System.getProperty("java.io.tmpdir"), "hapaneld-helper-${UUID.randomUUID()}.sock")
            server = ProcessBuilder(executable.absolutePath, socketPath.toString(), guardRoot.toString())
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
            if (::fixtureLock.isInitialized) fixtureLock.channel().close()
        }

        private fun deleteGuardRoot(root: Path) {
            val custody = root.resolve(".hapaneld-guard-db-test")
            Files.deleteIfExists(custody.resolve(".owner.lock"))
            Files.deleteIfExists(custody)
            Files.deleteIfExists(root)
        }

        fun DaemonLongResult.replyValue(): String? = (this as? DaemonLongResult.Reply)?.value

        // Neither access(2) nor open(2) predicts the helper's result: inside an unprivileged container
        // /sys is mounted read-only, yet both succeed and only write(2) is refused. Perform the write the
        // helper performs, with the governor the node already holds, so nothing changes either way.
        fun governorAcceptsWrites(): Boolean {
            val node = File("/sys/devices/system/cpu/cpu0/cpufreq/scaling_governor")
            val current = runCatching { node.readText().trim() }.getOrNull() ?: return false
            return runCatching { java.io.FileOutputStream(node).use { it.write(current.toByteArray()) } }.isSuccess
        }
    }
}
