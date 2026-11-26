package io.panelassistant.android

import com.sun.net.httpserver.HttpServer
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Home Assistant's `/auth/token`, answering from a script the test controls. */
internal class LoopbackHa : AutoCloseable {
    val requests = AtomicInteger()
    @Volatile private var gate = CountDownLatch(0)
    @Volatile private var status = 500
    @Volatile private var body = "{}"
    /** When set, a refresh naming another `client_id` gets Home Assistant's 400 `invalid_request`. */
    @Volatile var requiredClientId: String? = null
    private val pool = Executors.newCachedThreadPool()
    private val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 16).apply {
        executor = pool
        createContext("/auth/token") { exchange ->
            requests.incrementAndGet()
            val form = String(exchange.requestBody.readBytes()).split('&').associate {
                java.net.URLDecoder.decode(it.substringBefore('='), "UTF-8") to
                    java.net.URLDecoder.decode(it.substringAfter('=', ""), "UTF-8")
            }
            gate.await(20, TimeUnit.SECONDS)
            val required = requiredClientId
            val refused = required != null && form["client_id"] != required
            val bytes = (if (refused) """{"error":"invalid_request","error_description":"Invalid client id"}""" else body).toByteArray()
            exchange.sendResponseHeaders(if (refused) 400 else status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        start()
    }
    val url = "http://127.0.0.1:${server.address.port}"

    fun hold() { gate = CountDownLatch(1) }

    fun answer(code: Int, json: String) {
        status = code
        body = json
        gate.countDown()
    }

    fun reset() {
        requests.set(0)
        gate = CountDownLatch(0)
    }

    fun awaitRequests(count: Int) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (requests.get() < count) {
            check(System.nanoTime() < deadline) { "Home Assistant saw ${requests.get()} of $count requests" }
            Thread.sleep(5)
        }
    }

    /** Until [caller] either sends its own request or is parked waiting on the one in flight. */
    fun awaitSecondRequestOrParked(caller: Thread) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (requests.get() < 2 && caller.state != Thread.State.TIMED_WAITING &&
            caller.state != Thread.State.WAITING
        ) {
            check(System.nanoTime() < deadline) { "second caller neither asked nor waited" }
            Thread.sleep(5)
        }
    }

    override fun close() {
        gate.countDown()
        server.stop(0)
        pool.shutdownNow()
    }
}
