package io.panelassistant.android.util

import io.panelassistant.android.platform.DaemonStreamResult
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets

/** Helper command framing shared by the Android local-socket client and host composition tests. */
internal object HelperSocketProtocol {
    fun sendLine(command: String, input: InputStream, output: OutputStream): String? {
        output.apply { write((command + "\n").toByteArray()); flush() }
        return readReply(input)
    }

    /** Read one bounded, exact physical reply without submitting another command. */
    fun readReply(input: InputStream): String? {
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
        beforeRead: () -> Unit = {},
    ): ByteArray? {
        beforeRead()
        output.apply { write((command + "\n").toByteArray()); flush() }
        shutdownOutput()
        return BoundedStreams.readBytes(input, maxBytes, beforeRead).takeIf { it.isNotEmpty() }
    }

    /** Recovery is terminal on its admitted connection; consume only the declared bounded reply. */
    fun adbRecoveryExchange(
        header: String, payload: ByteArray, input: InputStream, timeoutMs: Int,
        submit: (() -> Unit) -> Boolean, write: (ByteArray) -> Unit,
        beforeRead: (Long) -> Unit,
        nowNs: () -> Long = System::nanoTime,
    ): ByteArray? {
        require(timeoutMs in 1..185_000 && payload.size <= 65_536)
        require(header.length <= 159 && header.all { it.code in 32..126 })
        val request = (header + "\n").toByteArray(StandardCharsets.US_ASCII) + payload
        if (!submit { write(request) }) return null
        val deadline = MonotonicDeadline(timeoutMs.toLong(), nowNs)
        fun reading() {
            val remaining = deadline.remainingMs()
            check(remaining > 0) { "local adb reply timed out" }
            beforeRead(remaining)
        }
        val line = java.io.ByteArrayOutputStream(160)
        while (true) {
            reading()
            val value = input.read()
            check(value >= 0) { "incomplete local adb reply" }
            if (value == 10) break
            check(value in 32..126 && line.size() < 159) { "invalid local adb reply header" }
            line.write(value)
        }
        val text = line.toString("US-ASCII")
        line.write(10)
        if (header.startsWith("SHELL ")) {
            val match = Regex("RESULT (-1|[0-9]{1,3}) [01] [01] (0|[1-9][0-9]{0,4})").matchEntire(text)
            check(match != null && match.groupValues[1].toInt() in -1..255)
            val count = match.groupValues[2].toInt()
            check(count in 0..32_768)
            val bytes = ByteArray(count)
            var used = 0
            while (used < count) {
                reading()
                val read = input.read(bytes, used, count - used)
                check(read > 0) { "incomplete local adb output" }
                used += read
            }
            line.write(bytes)
        }
        return line.toByteArray()
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
        replies = input,
        output = output,
        shutdownOutput = shutdownOutput,
    )

    private const val MAX_LINE_BYTES = 512
}
