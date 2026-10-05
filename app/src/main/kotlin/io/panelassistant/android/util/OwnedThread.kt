package io.panelassistant.android.util

/**
 * A single service-owned daemon worker thread with a bounded interrupt-then-join stop handoff.
 *
 * The lifecycle field, its lock, and the interrupt/join teardown are the same protocol the
 * MQTT watchdog and kiosk-reassert threads previously hand-rolled independently. Owning it once
 * keeps the handoff (publish under lock → self-clear on exit only if still registered → capture,
 * interrupt, bounded join) identical across every caller.
 *
 * [start] publishes a fresh daemon thread under an internal lock, runs [body], and on exit clears
 * the reference — running [onExit] first — only when the exiting thread is still the registered
 * one, so a worker's self-clear never races a concurrent [start] that already published a
 * replacement. A repeated [start] simply publishes a new worker; the previous one runs to its own
 * completion, and its stale self-clear is suppressed by the identity check.
 *
 * [stop] captures and clears the reference under the same lock, interrupts the worker, and joins
 * for at most [joinMs]. A non-positive [joinMs] skips the join, because an unbounded `join(0)`
 * could block a teardown deadline indefinitely.
 */
internal class OwnedThread(
    private val name: String,
    private val onExit: () -> Unit = {},
    private val body: () -> Unit,
) {
    private val lock = Any()
    private var thread: Thread? = null

    fun start() {
        val worker = Thread {
            try {
                body()
            } finally {
                synchronized(lock) {
                    if (thread === Thread.currentThread()) {
                        onExit()
                        thread = null
                    }
                }
            }
        }.apply { isDaemon = true; name = this@OwnedThread.name }
        synchronized(lock) { thread = worker }
        worker.start()
    }

    fun stop(joinMs: Long) {
        val worker = synchronized(lock) { thread.also { thread = null } }
        worker?.interrupt()
        if (joinMs > 0L) runCatching { worker?.join(joinMs) }
    }
}
