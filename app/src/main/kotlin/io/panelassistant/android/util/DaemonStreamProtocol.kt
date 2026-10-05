package io.panelassistant.android.util

import io.panelassistant.android.platform.DaemonStreamResult
import java.io.BufferedReader
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicBoolean

/** Pure two-phase file-upload exchange shared by the Android socket client and JVM protocol tests. */
internal object DaemonStreamProtocol {
    fun exchange(
        command: String,
        openSource: () -> InputStream,
        expectedBytes: Long,
        replies: BufferedReader,
        output: OutputStream,
        shutdownOutput: () -> Unit,
    ): DaemonStreamResult {
        output.apply { write((command + "\n").toByteArray()); flush() }
        return when (val first = replies.readLine()?.trim()) {
            null -> DaemonStreamResult.Indeterminate
            "ERR" -> DaemonStreamResult.Unsupported
            "READY" -> {
                openSource().use { source ->
                    BoundedStreams.copyExact(source, output, expectedBytes)
                    check(source.read() < 0) { "stream source exceeds declared length" }
                }
                output.flush()
                shutdownOutput()
                replies.readLine()?.trim()
                    ?.let(DaemonStreamResult::Reply)
                    ?: DaemonStreamResult.Indeterminate
            }
            else -> DaemonStreamResult.Reply(first)
        }
    }
}

/** Closes a blocking transport when its whole-operation deadline expires, including blocked writes. */
internal class StreamDeadline(
    timeoutMs: Long,
    private val onTimeout: () -> Unit,
) : AutoCloseable {
    private val settled = AtomicBoolean(false)
    private val worker = Thread {
        try {
            Thread.sleep(timeoutMs.coerceAtLeast(1L))
        } catch (_: InterruptedException) {
            return@Thread
        }
        if (settled.compareAndSet(false, true)) onTimeout()
    }.apply {
        isDaemon = true
        name = "helper-stream-deadline"
        start()
    }

    override fun close() {
        settled.compareAndSet(false, true)
        worker.interrupt()
        if (Thread.currentThread() !== worker) runCatching { worker.join(1_000L) }
    }
}
