package io.panelassistant.android.sensors

import android.net.LocalSocket
import android.util.Log
import io.panelassistant.android.util.openRootAbstractSocket
import io.panelassistant.android.util.nextHelperRetryDelayMs
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream

/**
 * Pure framing for the helper's VI530x range verb.
 *
 * Unlike the GPIO source this one polls rather than subscribing: the driver has no readable event
 * stream until it is started, and once started it republishes the latest measurement on demand, so a
 * request/response read is both the simplest and the most faithful shape. The helper starts the sensor
 * on the first request and keeps it running, so only the first read pays that cost.
 */
internal object Vi530xRangeSession {
    private val reading = Regex("D=(-?[0-9]{1,7}) S=([0-9]{1,7}) C=([0-9]{1,10})")

    /**
     * Parse one reply. Returns the raw range, or null when the helper reported that it could not read
     * the sensor. The status and confidence are deliberately *not* interpreted here: what counts as a
     * usable measurement on this part has not been established on hardware, and a guess would silently
     * discard real readings. Everything the driver returns is passed to the learning engine, which
     * derives polarity and levels from what it actually observes.
     */
    fun parse(line: String?): Float? {
        val match = reading.matchEntire(line?.trim().orEmpty()) ?: return null
        return match.groupValues[1].toFloatOrNull()
    }

    /** Issue one request and deliver its result. Returns false when the connection is unusable. */
    fun poll(
        input: InputStream,
        output: OutputStream,
        onValue: (Float) -> Unit,
        onUnavailable: () -> Unit = {},
    ): Boolean {
        val reader = BufferedReader(InputStreamReader(input))
        output.write("VI530X\n".toByteArray(Charsets.US_ASCII))
        output.flush()
        val line = reader.readLine() ?: return false
        if (line.trim() == "ERR") {
            onUnavailable()
            return true
        }
        val value = parse(line) ?: return false
        onValue(value)
        return true
    }
}

/**
 * Holds one helper socket and polls the VI530x range at a fixed cadence, feeding raw values into the
 * same proximity pipeline every other source uses. No near/far threshold is applied on the way: the
 * learning engine establishes polarity and levels from observation, so this source contributes
 * measurements rather than opinions.
 */
internal class Vi530xProximityClient(
    private val onValue: (Float) -> Unit,
    private val onUnavailable: () -> Unit = {},
    private val pollIntervalMs: Long = POLL_INTERVAL_MS,
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
        run.thread = Thread({ stream(run) }, "proximity-vi530x").apply { isDaemon = true }
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
                var delivered = false
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
                        socket.soTimeout = REQUEST_TIMEOUT_MS
                        while (!run.cancelled && active === run) {
                            val alive = Vi530xRangeSession.poll(
                                socket.inputStream,
                                socket.outputStream,
                                onValue = { value ->
                                    if (!run.cancelled && active === run) {
                                        delivered = true
                                        retryDelayMs = 0L
                                        onValue(value)
                                    }
                                },
                                onUnavailable = {
                                    if (!run.cancelled && active === run) onUnavailable()
                                },
                            )
                            if (!alive) break
                            try {
                                Thread.sleep(pollIntervalMs)
                            } catch (_: InterruptedException) {
                                return
                            }
                        }
                    }
                } catch (_: Exception) {
                    // fall through to the shared unavailable/retry path
                } finally {
                    synchronized(run) { if (run.socket === candidate) run.socket = null }
                }
                if (!run.cancelled && active === run) onUnavailable()
                if (run.cancelled || Thread.currentThread().isInterrupted) break
                val nextDelay = nextHelperRetryDelayMs(retryDelayMs)
                if (nextDelay != retryDelayMs) {
                    Log.d(TAG, "VI530x range unavailable (delivered=$delivered); retrying in ${nextDelay}ms")
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
        const val TAG = "ha-paneld/proximity-vi530x"
        const val REQUEST_TIMEOUT_MS = 2_000
        const val STOP_JOIN_MS = 1_000L

        // The driver is configured for the vendor's own measurement period, so this is how often the
        // latest measurement is collected, not how often the sensor measures. Fast enough that an
        // approaching hand is several samples wide, slow enough to stay a background cost.
        const val POLL_INTERVAL_MS = 200L
    }
}
