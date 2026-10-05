package io.panelassistant.android.shizuku

import io.panelassistant.android.util.BoundedStreams
import io.panelassistant.android.util.MonotonicDeadline
import io.panelassistant.android.util.StreamDeadline
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/** Minimal process surface for deterministic whole-install deadline tests. */
internal interface ShizukuInstallProcess {
    val input: OutputStream
    val output: InputStream
    fun waitFor(timeoutMs: Long): Boolean
    fun destroyForcibly()
}

/**
 * Streams one exact APK into one fixed `pm install` child. A single monotonic budget covers both the
 * copy and process completion; the independent watchdog closes blocked streams and kills the child.
 */
internal class ShizukuInstallRunner(
    private val timeoutMs: Long,
    private val nanoTime: () -> Long = System::nanoTime,
    private val deadlineFactory: (Long, () -> Unit) -> AutoCloseable = { timeout, onTimeout ->
        StreamDeadline(timeout, onTimeout)
    },
) {
    fun run(source: InputStream, expectedBytes: Long, process: ShizukuInstallProcess): String? {
        require(timeoutMs > 0L)
        val budget = MonotonicDeadline(timeoutMs, nanoTime)
        val timedOut = AtomicBoolean(false)
        val reply = ByteArrayOutputStream()
        val reader = thread(name = "hapaneld-shizuku-install-output", isDaemon = true) {
            runCatching { copyBounded(process.output, reply, MAX_REPLY_BYTES.toLong()) }
        }
        val deadline = deadlineFactory(timeoutMs) {
            timedOut.set(true)
            closeQuietly(source)
            closeQuietly(process.input)
            closeQuietly(process.output)
            runCatching { process.destroyForcibly() }
        }
        var exited = false
        return try {
            copyExact(source, process.input, expectedBytes)
            process.input.flush()
            closeQuietly(process.input)

            val waitMs = budget.remainingMs()
            if (timedOut.get() || waitMs <= 0L || !process.waitFor(waitMs)) {
                null
            } else {
                exited = true
                val readerWaitMs = budget.remainingMs()
                if (timedOut.get() || readerWaitMs <= 0L) {
                    null
                } else {
                    reader.join(readerWaitMs)
                    if (timedOut.get() || reader.isAlive) null
                    else reply.toString(Charsets.UTF_8.name()).trim().take(MAX_REPLY_BYTES)
                }
            }
        } catch (_: Exception) {
            null
        } finally {
            deadline.close()
            closeQuietly(source)
            closeQuietly(process.input)
            closeQuietly(process.output)
            if (!exited || timedOut.get()) {
                runCatching { process.destroyForcibly() }
                runCatching { process.waitFor(CLEANUP_TIMEOUT_MS) }
            }
            if (reader.isAlive) reader.join(CLEANUP_TIMEOUT_MS)
        }
    }

    private fun copyExact(input: InputStream, output: OutputStream, expected: Long) {
        BoundedStreams.copyExact(input, output, expected)
        if (input.read() != -1) throw java.io.IOException("extra APK bytes")
    }

    private fun copyBounded(input: InputStream, output: OutputStream, max: Long) {
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var copied = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) return
            if (read == 0) continue
            copied += read
            if (copied > max) throw java.io.IOException("stream too large")
            output.write(buffer, 0, read)
        }
    }

    private fun closeQuietly(closeable: AutoCloseable) {
        runCatching { closeable.close() }
    }

    private companion object {
        const val MAX_REPLY_BYTES = 16 * 1024
        const val CLEANUP_TIMEOUT_MS = 1_000L
    }
}
