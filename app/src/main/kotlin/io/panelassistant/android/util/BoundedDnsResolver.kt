package io.panelassistant.android.util

import java.io.IOException
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ExecutionException
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger

/**
 * Runs the platform DNS resolver behind a caller-visible deadline.
 *
 * `InetAddress.getAllByName` has no application timeout and is not reliably interruptible on every
 * Android resolver. A small daemon pool means a broken resolver cannot pin an HTTP request or a log
 * worker forever, while the bounded queue prevents repeated probes from creating unbounded work.
 */
internal class BoundedDnsResolver(
    private val lookup: (String) -> Array<InetAddress> = { InetAddress.getAllByName(it) },
    maxConcurrentLookups: Int = 2,
    queueCapacity: Int = 4,
    threadPrefix: String = "ha-paneld-dns",
) : AutoCloseable {
    private val threadNumber = AtomicInteger()
    private val executor = ThreadPoolExecutor(
        maxConcurrentLookups,
        maxConcurrentLookups,
        30L,
        TimeUnit.SECONDS,
        ArrayBlockingQueue(queueCapacity),
        ThreadFactory { runnable ->
            Thread(runnable, "$threadPrefix-${threadNumber.incrementAndGet()}").apply { isDaemon = true }
        },
        ThreadPoolExecutor.AbortPolicy(),
    )

    @Throws(IOException::class)
    fun resolveOne(host: String, timeoutMs: Long): InetAddress = resolveAll(host, timeoutMs).first()

    /**
     * Every address the name resolves to, so a caller that can retry is not stuck with whichever
     * record the platform happened to return first. A dual-stack name whose service listens on only
     * one family is otherwise a total, silent failure.
     */
    @Throws(IOException::class)
    fun resolveAll(host: String, timeoutMs: Long): List<InetAddress> {
        val future = try {
            executor.submit<List<InetAddress>> {
                lookup(host).toList().ifEmpty { throw UnknownHostException(host) }
            }
        } catch (e: RejectedExecutionException) {
            throw IOException("DNS resolver busy for $host", e)
        }
        try {
            return future.get(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (e: TimeoutException) {
            future.cancel(true)
            throw SocketTimeoutException("DNS resolution timed out for $host")
        } catch (e: InterruptedException) {
            future.cancel(true)
            Thread.currentThread().interrupt()
            throw IOException("DNS resolution interrupted for $host", e)
        } catch (e: ExecutionException) {
            val cause = e.cause ?: e
            when (cause) {
                is IOException -> throw cause
                is RuntimeException -> throw cause
                else -> throw IOException("DNS resolution failed for $host", cause)
            }
        }
    }

    override fun close() {
        executor.shutdownNow()
    }
}

/** Shared resolver for production network paths. */
internal object BoundedDns {
    private val resolver = BoundedDnsResolver()

    @Throws(IOException::class)
    fun resolveOne(host: String, timeoutMs: Long): InetAddress = resolver.resolveOne(host, timeoutMs)

    @Throws(IOException::class)
    fun resolveAll(host: String, timeoutMs: Long): List<InetAddress> =
        resolver.resolveAll(host, timeoutMs)
}
