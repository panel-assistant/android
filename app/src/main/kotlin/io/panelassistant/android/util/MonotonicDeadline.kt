package io.panelassistant.android.util

import java.util.concurrent.TimeUnit
import java.util.concurrent.ExecutorService
import java.util.concurrent.Future

/**
 * One monotonic timeout shared by a sequence of blocking phases.
 *
 * [remainingMs] rounds a positive sub-millisecond remainder up to one millisecond so callers do not
 * accidentally turn a live deadline into APIs where zero means "wait forever". Callers must still
 * treat an actual zero as non-blocking.
 */
internal class MonotonicDeadline private constructor(
    private val deadlineNanos: Long,
    private val nanoTime: () -> Long = System::nanoTime,
    @Suppress("UNUSED_PARAMETER") exactDeadline: Boolean,
) {
    constructor(
        timeoutMs: Long,
        nanoTime: () -> Long = System::nanoTime,
    ) : this(
        deadlineNanos = nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs.coerceAtLeast(0L)),
        nanoTime = nanoTime,
        exactDeadline = true,
    )

    fun remainingMs(): Long {
        val remainingNanos = deadlineNanos - nanoTime()
        if (remainingNanos <= 0L) return 0L
        return TimeUnit.NANOSECONDS.toMillis(remainingNanos - 1L) + 1L
    }

    /** A child deadline capped to both [timeoutMs] and this deadline's exact remaining duration. */
    fun cappedTo(timeoutMs: Long): MonotonicDeadline {
        val now = nanoTime()
        val parentRemainingNanos = (deadlineNanos - now).coerceAtLeast(0L)
        val requestedNanos = TimeUnit.MILLISECONDS.toNanos(timeoutMs.coerceAtLeast(0L))
        return MonotonicDeadline(
            deadlineNanos = now + minOf(parentRemainingNanos, requestedNanos),
            nanoTime = nanoTime,
            exactDeadline = true,
        )
    }
}

/** Close an executor immediately and prove its active task exited within the shared deadline. */
internal fun ExecutorService.shutdownNowAndAwait(deadline: MonotonicDeadline): Boolean {
    shutdownNow()
    var interrupted = false
    while (!isTerminated) {
        val timeoutMs = deadline.remainingMs()
        if (timeoutMs <= 0L) break
        try {
            awaitTermination(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            interrupted = true
        }
    }
    if (interrupted) Thread.currentThread().interrupt()
    return isTerminated
}

/** Interrupt one exact worker and prove it exited within the shared deadline. */
internal fun Thread?.interruptAndJoin(deadline: MonotonicDeadline): Boolean {
    val worker = this ?: return true
    if (!worker.isAlive) return true
    if (worker === Thread.currentThread()) return false
    worker.interrupt()
    var interrupted = false
    while (worker.isAlive) {
        val timeoutMs = deadline.remainingMs()
        if (timeoutMs <= 0L) break
        try {
            worker.join(timeoutMs)
        } catch (_: InterruptedException) {
            interrupted = true
        }
    }
    if (interrupted) Thread.currentThread().interrupt()
    return !worker.isAlive
}

/** Await successful completion without giving this phase a fresh timeout. */
internal fun Future<*>.awaitSuccessful(deadline: MonotonicDeadline): Boolean = try {
    get(deadline.remainingMs(), TimeUnit.MILLISECONDS)
    true
} catch (_: InterruptedException) {
    Thread.currentThread().interrupt()
    false
} catch (_: Exception) {
    false
}

/** Await an affirmative lifecycle proof without giving this phase a fresh timeout. */
internal fun Future<Boolean>.awaitTrue(deadline: MonotonicDeadline): Boolean = try {
    get(deadline.remainingMs(), TimeUnit.MILLISECONDS) == true
} catch (_: InterruptedException) {
    Thread.currentThread().interrupt()
    false
} catch (_: Exception) {
    false
}
