package io.panelassistant.android.util

import io.panelassistant.android.platform.DaemonStreamResult
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.nio.charset.StandardCharsets

/** Helper command framing shared by the Android local-socket client and host composition tests. */
internal object HelperSocketProtocol {
    fun sendLine(command: String, input: InputStream, output: OutputStream): String? {
        output.apply { write((command + "\n").toByteArray()); flush() }
        val bytes = ByteArray(MAX_LINE_BYTES)
        var used = 0
        while (true) {
            val next = input.read()
            if (next < 0) return null
            if (next == '\n'.code) return String(bytes, 0, used, StandardCharsets.US_ASCII)
            if (next !in 0x20..0x7e || used == bytes.size) return null
            bytes[used++] = next.toByte()
        }
    }

    fun sendBytes(
        command: String,
        input: InputStream,
        output: OutputStream,
        shutdownOutput: () -> Unit,
        maxBytes: Long = Long.MAX_VALUE - 1L,
    ): ByteArray? {
        output.apply { write((command + "\n").toByteArray()); flush() }
        shutdownOutput()
        return BoundedStreams.readBytes(input, maxBytes).takeIf { it.isNotEmpty() }
    }

    fun sendFile(
        command: String,
        openSource: () -> InputStream,
        expectedBytes: Long,
        input: InputStream,
        output: OutputStream,
        shutdownOutput: () -> Unit,
    ): DaemonStreamResult = DaemonStreamProtocol.exchange(
        command = command,
        openSource = openSource,
        expectedBytes = expectedBytes,
        replies = BufferedReader(InputStreamReader(input)),
        output = output,
        shutdownOutput = shutdownOutput,
    )

    private const val MAX_LINE_BYTES = 512
}
