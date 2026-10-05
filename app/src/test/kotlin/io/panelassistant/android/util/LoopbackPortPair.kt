package io.panelassistant.android.util

import java.io.IOException
import java.net.BindException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * Allocates one port number held on TWO addresses at once, for tests that model one hostname
 * resolving to a dead and a live address: both routes must share a single port, exactly as one URL
 * carries a single port across every resolved address.
 *
 * The unsafe shape this replaces took an ephemeral port on one address and re-bound that number on
 * the other. The kernel offered the number for the first address only; under concurrent builds an
 * unrelated process holding it on the second failed the bind — or worse, answered a route the test
 * needed dead.
 */
object LoopbackPortPair {

    class Pair internal constructor(val first: ServerSocket, val second: ServerSocket) : AutoCloseable {
        val port: Int get() = first.localPort
        override fun close() {
            runCatching { first.close() }
            runCatching { second.close() }
        }
    }

    /**
     * One port listening on both addresses.
     *
     * A bind that throws still leaves its socket's descriptor open, so both sides are closed before
     * the loop retries; otherwise each attempt leaked one.
     */
    fun bind(
        first: InetAddress,
        firstBacklog: Int,
        second: InetAddress,
        secondBacklog: Int,
        attempts: Int = 8,
    ): Pair {
        repeat(attempts) {
            val a = ServerSocket().apply { bind(InetSocketAddress(first, 0), firstBacklog) }
            val b = ServerSocket()
            try {
                b.bind(InetSocketAddress(second, a.localPort), secondBacklog)
                return Pair(a, b)
            } catch (collision: IOException) {
                runCatching { b.close() }
                a.close()
            }
        }
        throw BindException("no port bindable on both $first and $second after $attempts attempts")
    }

    /**
     * One port that REFUSES on [refusing] and listens on [live].
     *
     * The refusing half is a socket bound and never listened on. That is the whole trick: the kernel
     * reserves the port to us — a competing bind gets `EADDRINUSE` — while a SYN arriving at it is
     * answered with RST, because a bound socket with no accept queue has nothing to complete the
     * handshake with. So the route is refused *and* owned for the test's lifetime.
     *
     * The alternative this replaces bound a listener, closed it, and probed to see whether the port
     * still looked dead. That inferred the premise twice over: the probe could fail for reasons
     * unrelated to refusal, and between probe and dial anything could claim the number.
     */
    class RefusedAndLive internal constructor(
        private val refusingHolder: Socket,
        val live: ServerSocket,
    ) : AutoCloseable {
        val port: Int get() = live.localPort
        override fun close() {
            runCatching { refusingHolder.close() }
            runCatching { live.close() }
        }
    }

    /**
     * One port REFUSING on BOTH addresses, each held by a bound socket that never listens.
     *
     * For tests whose every candidate must fail: leaving the second address merely "probably free"
     * would assume exactly the premise the test depends on.
     */
    class RefusedPair internal constructor(private val holders: List<Socket>, val port: Int) : AutoCloseable {
        override fun close() {
            holders.forEach { runCatching { it.close() } }
        }
    }

    fun refusedOnBoth(first: InetAddress, second: InetAddress, attempts: Int = 8): RefusedPair {
        repeat(attempts) {
            // Draw the number from a listener so it comes from the ephemeral range, then hand it to
            // holders that own it without answering.
            val seed = ServerSocket().apply { bind(InetSocketAddress(first, 0), 1) }
            val port = seed.localPort
            seed.close()
            val a = Socket()
            val b = Socket()
            try {
                a.bind(InetSocketAddress(first, port))
                b.bind(InetSocketAddress(second, port))
                return RefusedPair(listOf(a, b), port)
            } catch (taken: IOException) {
                runCatching { a.close() }
                runCatching { b.close() }
            }
        }
        throw BindException("no port reservable on both $first and $second after $attempts attempts")
    }

    fun refusedAndLive(
        refusing: InetAddress,
        live: InetAddress,
        liveBacklog: Int,
        attempts: Int = 8,
    ): RefusedAndLive {
        repeat(attempts) {
            val listener = ServerSocket().apply { bind(InetSocketAddress(live, 0), liveBacklog) }
            val holder = Socket()
            try {
                holder.bind(InetSocketAddress(refusing, listener.localPort))
                return RefusedAndLive(holder, listener)
            } catch (taken: IOException) {
                runCatching { holder.close() }
                listener.close()
            }
        }
        throw BindException("no port bindable on $live and reservable on $refusing after $attempts attempts")
    }
}
