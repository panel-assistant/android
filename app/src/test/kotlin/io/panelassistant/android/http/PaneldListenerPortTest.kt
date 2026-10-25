package io.panelassistant.android.http

import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.URL
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.*
import org.junit.Test

class PaneldListenerPortTest {
    @Test fun occupiedDefaultPortStartsRealHttpOnPublishedUserPortAndPreservesItOnRebind() = withDefaultPortLock {
        ServerSocket().apply { bind(InetSocketAddress("127.0.0.1", 8888)) }.use {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            var requestedPort = 8888
            var offLan = true
            val published = mutableListOf<Int>()
            var ingressClosed = false
            val listener = PaneldListener(scope, { requestedPort }, { offLan }, onBound = { port ->
                // The callback is the publication boundary: HTTP must already answer here.
                assertEquals("ready", get(port))
                published += port
                requestedPort = port
            }) {
                routing { get("/") { call.respondText("ready") } }
            }
            try {
                listener.start { ingressClosed = true }
                assertTrue(listener.bound())
                assertEquals(1, published.size)
                val actualPort = published.single()
                assertTrue(actualPort in 1024..32767)
                assertNotEquals(8888, actualPort)
                assertFalse(ingressClosed)
                offLan = false
                assertTrue(listener.rebind())
                assertEquals(listOf(actualPort, actualPort), published)
                assertEquals("ready", get(actualPort))
            } finally {
                listener.stop()
                scope.cancel()
            }
        }
    }

    @Test fun moduleFailureClosesIngressAndDoesNotPublishOrTryOtherPorts() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val failure = IllegalStateException("broken module")
        var moduleStarts = 0
        val published = mutableListOf<Int>()
        var ingressClosed = false
        val listener = PaneldListener(scope, { 8888 }, { true }, onBound = published::add) {
            moduleStarts++
            throw failure
        }
        try {
            val thrown = assertThrows(IllegalStateException::class.java) {
                listener.start { ingressClosed = true }
            }
            assertEquals(failure.message, thrown.message)
            assertEquals(1, moduleStarts)
            assertTrue(published.isEmpty())
            assertTrue(ingressClosed)
            assertFalse(listener.bound())
        } finally {
            listener.stop()
            scope.cancel()
        }
    }

    private fun get(port: Int): String = URL("http://127.0.0.1:$port/").openConnection().apply {
        connectTimeout = 2_000
        readTimeout = 2_000
    }.getInputStream().bufferedReader().use { it.readText() }
    companion object {
        // These real-bind tests run in separate Gradle forks and must take turns occupying 8888.
        fun withDefaultPortLock(block: () -> Unit) = synchronized(PaneldListenerPortTest::class.java) {
            java.nio.channels.FileChannel.open(
                java.nio.file.Path.of(System.getProperty("java.io.tmpdir"), "panel-assistant-http-port-test.lock"),
                java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.WRITE,
            ).use { channel -> channel.lock().use { block() } }
        }
    }

}
