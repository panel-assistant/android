package io.panelassistant.android.util

import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** One daemon worker with no queue: duplicate slow hardware actions are discarded, never accumulated. */
internal class SingleFlightExecutor(threadName: String) : AutoCloseable {
    private val executor = ThreadPoolExecutor(
        1,
        1,
        0L,
        TimeUnit.MILLISECONDS,
        SynchronousQueue(),
        { task -> Thread(task, threadName).apply { isDaemon = true } },
        ThreadPoolExecutor.AbortPolicy(),
    )

    fun execute(action: () -> Unit): Boolean = try {
        executor.execute(action)
        true
    } catch (_: RejectedExecutionException) {
        false
    }

    fun closeAndJoin(timeoutMs: Long): Boolean {
        executor.shutdownNow()
        return runCatching { executor.awaitTermination(timeoutMs, TimeUnit.MILLISECONDS) }
            .getOrDefault(false)
    }

    fun close(timeoutMs: Long) {
        closeAndJoin(timeoutMs)
    }

    override fun close() = close(0L)
}
