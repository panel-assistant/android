package io.panelassistant.android.shizuku

import java.util.concurrent.Callable
import java.util.concurrent.Future
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * A fail-fast admission gate for Binder calls. Binder transactions are not reliably interruptible, so
 * an unbounded executor can leak one thread for every timed-out request. A zero-capacity hand-off queue
 * keeps the process-wide cost at [workers]: callers are rejected instead of accumulating hidden work.
 */
internal class BoundedCallExecutor(
    workers: Int,
    threadFactory: ThreadFactory,
) {
    init {
        require(workers > 0)
    }

    private val executor = ThreadPoolExecutor(
        workers,
        workers,
        0L,
        TimeUnit.MILLISECONDS,
        SynchronousQueue(),
        threadFactory,
        ThreadPoolExecutor.AbortPolicy(),
    )

    fun execute(block: () -> Unit): Boolean = try {
        executor.execute(block)
        true
    } catch (_: RejectedExecutionException) {
        false
    }

    fun <T> submit(block: () -> T): Future<T>? = try {
        executor.submit(Callable(block))
    } catch (_: RejectedExecutionException) {
        null
    }
}
