package io.panelassistant.android.util

import io.panelassistant.android.platform.Daemon
import io.panelassistant.android.platform.DaemonLongResult
import io.panelassistant.android.platform.DaemonStreamResult
import io.panelassistant.android.testsupport.scratchDirectory
import java.io.File
import java.net.SocketAddress
import java.nio.channels.Channels
import java.nio.channels.FileChannel
import java.nio.channels.SocketChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermissions
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class HelperStreamInstallContractTest {
    @Test(timeout = 120_000)
    fun ordinaryInstallUsesNativeStreamContract() = checkInstall(strictUpgrade = false, verb = "INSTALLSTREAM")

    @Test(timeout = 120_000)
    fun strictUpgradeUsesNativeUpgradeStreamContract() = checkInstall(strictUpgrade = true, verb = "INSTALLSTREAMUPGRADE")

    private fun checkInstall(strictUpgrade: Boolean, verb: String) {
        val executable = System.getProperty("hapaneld.helper.socketTestServer")
        assumeTrue("native socket contract requires a Linux host", executable != null)
        assertTrue(File(requireNotNull(executable)).isFile, "native socket server was not built")
        // The native stream receiver has one staging path shared with other socket fixtures.
        val lockPath = Path.of("/tmp/hapaneld-helper-socket-composition.lock")
        FileChannel.open(
            lockPath,
            StandardOpenOption.CREATE, StandardOpenOption.WRITE,
        ).use { lockChannel ->
            lockChannel.lock().use {
                runCatching { Files.setPosixFilePermissions(lockPath, PosixFilePermissions.fromString("rw-rw-rw-")) }
                val root = scratchDirectory("helper-stream-contract-")
                val socket = Path.of(System.getProperty("java.io.tmpdir"), "helper-stream-${UUID.randomUUID()}.sock")
                var server: Process? = null
                try {
                    server = ProcessBuilder(executable, socket.toString(), root.toString())
                        .redirectErrorStream(true).start()
                    assertEquals("READY", server.inputStream.bufferedReader().readLine())
                    val source = root.resolve("input.apk").toFile().apply {
                        writeBytes(byteArrayOf(0x50, 0x4b, 0, 4, 0x7f))
                    }
                    val length = source.length()
                    val staging = root.resolve("caller-staging").toFile().apply { mkdir() }
                    val daemon = StreamDaemon(socket)

                    assertEquals(
                        InstallOutcome.Succeeded,
                        HelperInstallTransaction(daemon).install(source, staging, strictUpgrade),
                    )
                    assertEquals(listOf("$verb $length"), daemon.streamCommands)
                    assertEquals(emptyList(), daemon.legacyCommands, "stream success must not use pathname fallback")
                    assertFalse(source.exists(), "terminal stream reply releases caller input")
                    assertEquals(emptyList(), staging.listFiles()?.toList())
                    assertFalse(File("/tmp/hapaneld-helper-install-stream-test.apk").exists())
                } finally {
                    server?.let {
                        it.destroy()
                        if (!it.waitFor(5, TimeUnit.SECONDS)) it.destroyForcibly().waitFor()
                    }
                    Files.deleteIfExists(socket)
                    Files.deleteIfExists(Path.of("/tmp/hapaneld-helper-install-stream-test.apk"))
                    root.toFile().deleteRecursively()
                }
            }
        }
    }

    private class StreamDaemon(private val socket: Path) : Daemon {
        val streamCommands = mutableListOf<String>()
        val legacyCommands = mutableListOf<String>()

        override fun available(): Boolean = error("not used by install transaction")
        override fun send(cmd: String): String? = withConnection { channel ->
            HelperSocketProtocol.sendLine(cmd, Channels.newInputStream(channel), Channels.newOutputStream(channel))
        }
        override fun sendLong(cmd: String, timeoutMs: Long): DaemonLongResult {
            legacyCommands += cmd
            return send(cmd)?.let(DaemonLongResult::Reply) ?: DaemonLongResult.Indeterminate
        }
        override fun sendFile(cmd: String, source: File, timeoutMs: Long): DaemonStreamResult {
            streamCommands += cmd
            return withConnection { channel ->
                HelperSocketProtocol.sendFile(
                    command = cmd,
                    openSource = source::inputStream,
                    expectedBytes = source.length(),
                    input = Channels.newInputStream(channel),
                    output = Channels.newOutputStream(channel),
                    shutdownOutput = channel::shutdownOutput,
                )
            }
        }
        override fun sendBytes(cmd: String): ByteArray? = error("not used by install transaction")

        private fun <T> withConnection(block: (SocketChannel) -> T): T {
            val address = Class.forName("java.net.UnixDomainSocketAddress")
                .getMethod("of", Path::class.java).invoke(null, socket) as SocketAddress
            return SocketChannel.open(address).use(block)
        }
    }
}
