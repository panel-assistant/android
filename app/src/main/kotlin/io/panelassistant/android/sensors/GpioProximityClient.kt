package io.panelassistant.android.sensors

import android.net.LocalSocket
import android.util.Log
import io.panelassistant.android.util.openRootAbstractSocket
import io.panelassistant.android.util.nextHelperRetryDelayMs
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream

/** Pure framing for the helper's event-driven GPIO stream. */
internal object GpioProximityStreamSession {
    private val event = Regex("GPIO ([0-9]{1,5}) ([01])")
    private val unavailable = Regex("GPIOUNAVAILABLE ([0-9]{1,5})")

    fun run(
        gpio: Int,
        input: InputStream,
        output: OutputStream,
        onSubscribed: () -> Unit,
        onValue: (Float) -> Unit,
        onUnavailable: () -> Unit = {},
    ): Boolean {
        require(gpio in 0..65_535)
        val reader = BufferedReader(InputStreamReader(input))
        fun command(line: String) {
            output.write((line + "\n").toByteArray(Charsets.US_ASCII))
            output.flush()
            check(reader.readLine()?.trim() == "OK") { "$line rejected" }
        }

        command("GPIOV1")
        command("GPIORESET")
        command("GPIOWATCH $gpio")
        output.write("GPIOSUBSCRIBE\n".toByteArray(Charsets.US_ASCII))
        output.flush()
        // A change can race with the helper's OK reply after it admits this subscriber. Accept and
        // deliver those valid records without mistaking them for a failed handshake.
        while (true) {
            val line = reader.readLine()?.trim() ?: error("GPIOSUBSCRIBE closed")
            if (line == "OK") break
            deliver(line, gpio, onValue, onUnavailable)
        }
        onSubscribed()

        while (true) {
            val line = reader.readLine() ?: return false
            deliver(line.trim(), gpio, onValue, onUnavailable)
        }
    }

    private fun deliver(
        line: String,
        gpio: Int,
        onValue: (Float) -> Unit,
        onUnavailable: () -> Unit = {},
    ) {
        event.matchEntire(line)?.let { match ->
            if (match.groupValues[1].toInt() == gpio) {
                onValue(if (match.groupValues[2] == "1") 1f else 0f)
            }
            return
        }
        unavailable.matchEntire(line)?.let { match ->
            if (match.groupValues[1].toInt() == gpio) onUnavailable()
            return
        }
        error("malformed GPIO event")
    }
}

/**
 * Holds one helper socket and receives only GPIO changes. The helper uses edge notification where the
 * kernel supports it and a low-frequency held-fd fallback otherwise, avoiding an app-side root shell
 * and its old continuous process/poll overhead.
 */
internal class GpioProximityClient(
    private val gpio: Int,
    private val onValue: (Float) -> Unit,
    private val onUnavailable: () -> Unit = {},
) {
    private class Run {
        @Volatile var cancelled = false
        @Volatile var socket: LocalSocket? = null
        lateinit var thread: Thread

        fun cancel() {
            cancelled = true
            thread.interrupt()
            synchronized(this) {
                runCatching { socket?.close() }
                socket = null
            }
            runCatching { thread.join(STOP_JOIN_MS) }
        }
    }

    @Volatile private var active: Run? = null

    @Synchronized
    fun start() {
        if (active != null) return
        val run = Run()
        run.thread = Thread({ stream(run) }, "proximity-gpio").apply { isDaemon = true }
        active = run
        run.thread.start()
    }

    fun stop() {
        val run = synchronized(this) {
            active.also {
                it?.cancelled = true
                if (active === it) active = null
            }
        }
        run?.cancel()
    }

    private fun stream(run: Run) {
        var retryDelayMs = 0L
        try {
            while (!run.cancelled && !Thread.currentThread().isInterrupted) {
                var candidate: LocalSocket? = null
                try {
                    candidate = openRootAbstractSocket(SOCKET)
                    synchronized(run) {
                        if (run.cancelled) {
                            candidate.close()
                            return
                        }
                        run.socket = candidate
                    }
                    candidate.use { socket ->
                        socket.soTimeout = HANDSHAKE_TIMEOUT_MS
                        val endedNormally = !GpioProximityStreamSession.run(
                            gpio = gpio,
                            input = socket.inputStream,
                            output = socket.outputStream,
                            onSubscribed = {
                                socket.soTimeout = 0
                                retryDelayMs = 0L
                            },
                            onValue = { value ->
                                if (!run.cancelled && active === run) onValue(value)
                            },
                            onUnavailable = {
                                if (!run.cancelled && active === run) onUnavailable()
                            },
                        )
                        if (endedNormally && !run.cancelled && active === run) {
                            onUnavailable()
                        }
                    }
                } catch (e: Exception) {
                    if (!run.cancelled) {
                        onUnavailable()
                    }
                } finally {
                    synchronized(run) { if (run.socket === candidate) run.socket = null }
                }
                if (run.cancelled || Thread.currentThread().isInterrupted) break
                val nextDelay = nextHelperRetryDelayMs(retryDelayMs)
                if (nextDelay != retryDelayMs) {
                    Log.d(TAG, "GPIO stream unavailable; retrying in ${nextDelay}ms")
                }
                retryDelayMs = nextDelay
                try {
                    Thread.sleep(retryDelayMs)
                } catch (_: InterruptedException) {
                    break
                }
            }
        } finally {
            synchronized(this) { if (active === run) active = null }
        }
    }

    private companion object {
        const val SOCKET = "hapaneld-helper"
        const val TAG = "ha-paneld/proximity-gpio"
        const val HANDSHAKE_TIMEOUT_MS = 2_000
        const val STOP_JOIN_MS = 1_000L
    }
}
