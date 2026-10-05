package io.panelassistant.android.util

import java.util.concurrent.atomic.AtomicBoolean

/**
 * Single-flight service restart requested after an HTTP profile activation response has been queued.
 *
 * The service owner supplies a scheduler and controlled process-restart callback. ha-paneld's foreground
 * service is START_STICKY, so process death lets Android recreate it without attempting a background
 * startForegroundService call that Android 12+ may reject. Process death also makes overlapping hardware
 * owners impossible.
 */
class ProfileRestartCoordinator(
    private val schedule: (Long, () -> Unit) -> Boolean,
    private val restartProcess: () -> Unit,
    private val safeToRestart: () -> Boolean = { true },
    private val shouldAbandon: () -> Boolean = { false },
    private val responseGraceMs: Long = RESPONSE_GRACE_MS,
    private val busyRetryMs: Long = BUSY_RETRY_MS,
) {
    private val requested = AtomicBoolean(false)

    /** Returns false when an activation has already scheduled this process's one restart. */
    fun request(): Boolean {
        if (!requested.compareAndSet(false, true)) return false
        if (schedule(responseGraceMs, ::restartWhenSafe)) return true
        requested.set(false)
        return false
    }

    private fun restartWhenSafe() {
        if (shouldAbandon()) {
            requested.set(false)
        } else if (safeToRestart()) {
            restartProcess()
        } else if (!schedule(busyRetryMs, ::restartWhenSafe)) {
            // Keep the process alive if its scheduler is shutting down. The durable PENDING state is
            // recovered on the next ordinary process start, so never force an unsafe exit here.
            requested.set(false)
        }
    }

    companion object {
        const val RESPONSE_GRACE_MS = 350L
        const val BUSY_RETRY_MS = 500L
    }
}
