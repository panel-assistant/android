package io.panelassistant.android.input

import android.net.LocalSocket
import android.util.Log
import io.panelassistant.android.util.openRootAbstractSocket
import io.panelassistant.android.util.nextHelperRetryDelayMs
import io.panelassistant.android.device.EvdevButton

/**
 * Streams hardware-button events from the root helper daemon (`hapaneld-helper`, abstract UNIX socket
 * `@hapaneld-helper`) for
 * keys the Android input pipeline doesn't usefully deliver to the app (e.g. a `KEY_MICMUTE` adc-key,
 * or the power key). On a background thread it connects, sends `WATCH <node> <grab>` for each
 * [EvdevButton] — grabbing where asked, so e.g. the WF1589T power key no longer sleeps the panel —
 * then `SUBSCRIBE`, and maps each `KEY <code> <value>` line (on DOWN) to the button's HA `event_type`
 * via [ButtonBus] (the same path the accessibility key capture uses). Reconnects with a short backoff
 * if the daemon isn't up yet or restarts, so the grab is re-established automatically.
 */
object EvdevButtonClient {
    private const val SOCK = "hapaneld-helper"   // abstract socket name; matches SOCK_NAME in main.c
    private const val TAG = "ha-paneld/evdev"

    private class Run(val buttons: List<EvdevButton>) {
        @Volatile var cancelled = false
        @Volatile var socket: LocalSocket? = null
        lateinit var thread: Thread

        fun attach(candidate: LocalSocket): Boolean = synchronized(this) {
            if (cancelled || thread.isInterrupted) {
                runCatching { candidate.close() }
                false
            } else {
                socket = candidate
                true
            }
        }

        fun detach(candidate: LocalSocket) = synchronized(this) {
            if (socket === candidate) socket = null
        }

        fun cancelAndJoin() {
            cancelled = true
            thread.interrupt()
            synchronized(this) {
                runCatching { socket?.close() } // interruption alone does not unblock LocalSocket readLine()
                socket = null
            }
            runCatching { thread.join(STOP_JOIN_MS) }
        }
    }

    internal enum class State { STOPPED, CONNECTING, ACTIVE, RETRYING }
    internal data class Snapshot(val state: State, val mode: EvdevStreamSession.Mode?, val lastError: String?)

    @Volatile private var active: Run? = null
    @Volatile private var state = State.STOPPED
    @Volatile private var mode: EvdevStreamSession.Mode? = null
    @Volatile private var lastError: String? = null

    internal fun snapshot(): Snapshot = synchronized(this) { Snapshot(state, mode, lastError) }

    /** Idempotent. No-op when [buttons] is empty (panels with no evdev-instrumented buttons). */
    @Synchronized
    fun start(buttons: List<EvdevButton>) {
        if (active != null || buttons.isEmpty()) return
        val r = Run(buttons.toList())
        r.thread = Thread({ run(r) }, "evdev-buttons").apply { isDaemon = true }
        active = r
        state = State.CONNECTING
        mode = null
        lastError = null
        r.thread.start()
    }

    fun stop() {
        // Invalidate delivery before closing the socket. A replacement runtime may start even if an
        // old platform read takes longer than the bounded join; that stale run cannot emit into it.
        val r = synchronized(this) {
            active.also {
                it?.cancelled = true
                if (active === it) {
                    active = null
                    state = State.STOPPED
                    mode = null
                }
            }
        }
        r?.cancelAndJoin()
    }

    private fun run(run: Run) {
        var retryDelayMs = 0L
        try {
            while (!Thread.currentThread().isInterrupted) {
                var candidate: LocalSocket? = null
                try {
                    update(run, State.CONNECTING)
                    candidate = openRootAbstractSocket(SOCK)
                    if (!run.attach(candidate)) break
                    candidate.use { s ->
                        s.soTimeout = HANDSHAKE_TIMEOUT_MS
                        EvdevStreamSession.run(
                            buttons = run.buttons,
                            input = s.inputStream,
                            output = s.outputStream,
                            onSubscribed = { verifiedMode ->
                                s.soTimeout = 0
                                retryDelayMs = 0L
                                update(run, State.ACTIVE, verifiedMode, null)
                            },
                        ) { event ->
                            val current = synchronized(this) { active === run && !run.cancelled }
                            if (current) ButtonBus.emit(event)
                        }
                        update(run, State.RETRYING, error = "helper stream closed")
                    }
                } catch (e: Exception) {
                    update(run, State.RETRYING, error = e.message ?: e.javaClass.simpleName)
                } finally {
                    candidate?.let { run.detach(it) }
                }
                if (Thread.currentThread().isInterrupted) break
                val nextDelay = nextHelperRetryDelayMs(retryDelayMs)
                if (nextDelay != retryDelayMs) {
                    Log.d(TAG, "evdev stream unavailable; retrying in ${nextDelay}ms")
                }
                retryDelayMs = nextDelay
                try { Thread.sleep(retryDelayMs) } catch (e: InterruptedException) { break }
            }
        } finally {
            synchronized(this) {
                if (active === run) {
                    active = null
                    state = State.STOPPED
                    mode = null
                }
            }
        }
    }

    private fun update(
        run: Run,
        next: State,
        verifiedMode: EvdevStreamSession.Mode? = mode,
        error: String? = lastError,
    ) = synchronized(this) {
        if (active === run && !run.cancelled) {
            state = next
            mode = verifiedMode
            lastError = error?.replace(Regex("[\\r\\n]+"), " ")?.take(160)
        }
    }

    private const val STOP_JOIN_MS = 1_000L
    private const val HANDSHAKE_TIMEOUT_MS = 2_000
}
