package io.panelassistant.android.control

import io.panelassistant.android.util.BoundedLaunchGate
import io.panelassistant.android.util.MonotonicDeadline
import io.panelassistant.android.util.runBoundedLaunch
import java.io.InputStream
import java.util.concurrent.TimeUnit

/** Fixed app-UID commands; a stalled launch or pipe retains custody until its worker exits. */
internal class DirectAppCommands(
    private val processStarter: (List<String>) -> Process = { argv ->
        ProcessBuilder(argv).redirectErrorStream(true).start()
    },
    private val readTimeoutMs: Long = 1_000L,
    private val densityTimeoutMs: Long = 5_000L,
) {
    private val launchGate = BoundedLaunchGate()

    fun windowDensity(arg: String?): String? = execute(
        listOfNotNull("cmd", "window", "density", arg), densityTimeoutMs, requireSuccess = false,
    ) { it.bufferedReader().readText().trim() }

    fun getprop(vararg fixedArguments: String): String? = execute(
        listOf("/system/bin/getprop", *fixedArguments), readTimeoutMs,
    ) { readBounded(it, 128)?.trim() }

    fun tcpInventory(fixedPath: String): String? = execute(
        listOf("/system/bin/cat", fixedPath), readTimeoutMs,
    ) { readBounded(it, MAX_TCP_INVENTORY_BYTES) }

    private fun execute(
        argv: List<String>,
        timeoutMs: Long,
        requireSuccess: Boolean = true,
        read: (InputStream) -> String?,
    ): String? {
        val deadline = MonotonicDeadline(timeoutMs)
        return runBoundedLaunch(
            deadline = deadline,
            threadName = "ha-paneld-direct-command",
            gate = launchGate,
            launch = { processStarter(argv) },
            // Do not close a pipe on the caller: its reader may hold the stream's monitor.
            destroy = { it.destroyForcibly(); Unit },
            consume = { process ->
                try {
                    // Drain before waiting so output pressure cannot stall the command's exit.
                    val output = read(process.inputStream)
                    val remaining = deadline.remainingMs()
                    if (output == null || remaining <= 0L ||
                        !process.waitFor(remaining, TimeUnit.MILLISECONDS) ||
                        (requireSuccess && process.exitValue() != 0)
                    ) null else output
                } finally {
                    runCatching { process.destroyForcibly() }
                    runCatching { process.inputStream.close() }
                    runCatching { process.errorStream.close() }
                    runCatching { process.outputStream.close() }
                }
            },
        )
    }

    private fun readBounded(input: InputStream, maxBytes: Int): String? {
        val bytes = ByteArray(maxBytes + 1)
        var size = 0
        while (size < bytes.size) {
            val count = input.read(bytes, size, bytes.size - size)
            if (count < 0) break
            size += count
        }
        return if (size > maxBytes) null else String(bytes, 0, size, Charsets.UTF_8)
    }
}

internal const val MAX_TCP_INVENTORY_BYTES = 64 * 1024
