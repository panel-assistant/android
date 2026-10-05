package io.panelassistant.android.util

import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Launch and consume one blocking resource under a single monotonic deadline.
 *
 * [launch] itself runs on the throwaway worker. If it ignores interruption and returns after the caller
 * has timed out, the late resource is destroyed before [consume] can touch it. The launch lease remains
 * owned by that worker until it really exits, so cancellation can never admit an overlapping launch.
 */
internal fun <P : Any, T> runBoundedLaunch(
    deadline: MonotonicDeadline,
    threadName: String,
    gate: BoundedLaunchGate,
    launch: () -> P?,
    destroy: (P) -> Unit,
    consume: (P) -> T,
): T? {
    if (deadline.remainingMs() <= 0L) return null
    val lease = gate.acquire(deadline) ?: return null
    val resource = AtomicReference<P?>()
    val abandoned = AtomicBoolean(false)
    val destroyed = AtomicBoolean(false)
    val completion = CompletableFuture<T?>()

    fun destroyOnce(target: P) {
        if (destroyed.compareAndSet(false, true)) runCatching { destroy(target) }
    }

    val worker = try {
        Thread(
            {
                try {
                    val launched = launch()
                    if (launched == null) {
                        completion.complete(null)
                        return@Thread
                    }
                    resource.set(launched)
                    if (abandoned.get() || deadline.remainingMs() <= 0L) {
                        destroyOnce(launched)
                        completion.complete(null)
                        return@Thread
                    }
                    completion.complete(consume(launched))
                } catch (error: Throwable) {
                    resource.get()?.let(::destroyOnce)
                    completion.completeExceptionally(error)
                } finally {
                    lease.release()
                }
            },
            threadName,
        ).apply { isDaemon = true }
    } catch (_: Throwable) {
        lease.release()
        return null
    }

    try {
        worker.start()
    } catch (_: Throwable) {
        lease.release()
        return null
    }

    var observedCompletion = false
    return try {
        val remainingMs = deadline.remainingMs()
        if (remainingMs <= 0L) return null
        completion.get(remainingMs, TimeUnit.MILLISECONDS).also { observedCompletion = true }
    } catch (_: Exception) {
        null
    } finally {
        if (!observedCompletion) {
            abandoned.set(true)
            resource.get()?.let(::destroyOnce)
            worker.interrupt()
        }
    }
}

/**
 * At most one launch/consume worker may remain live. A successor waits only within its own deadline;
 * a permanently wedged worker therefore makes later attempts fail closed without accumulating threads.
 */
internal class BoundedLaunchGate {
    private val monitor = Object()
    private var inFlight = false

    fun acquire(deadline: MonotonicDeadline): Lease? = synchronized(monitor) {
        while (inFlight) {
            val remainingMs = deadline.remainingMs()
            if (remainingMs <= 0L) return null
            try {
                monitor.wait(remainingMs)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return null
            }
        }
        if (deadline.remainingMs() <= 0L) return null
        inFlight = true
        Lease {
            synchronized(monitor) {
                inFlight = false
                monitor.notifyAll()
            }
        }
    }

    internal class Lease(private val onRelease: () -> Unit) {
        private val released = AtomicBoolean(false)

        fun release() {
            if (released.compareAndSet(false, true)) onRelease()
        }
    }
}
