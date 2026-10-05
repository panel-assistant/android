package io.panelassistant.android.mqtt

import io.moquette.broker.Server
import java.net.ServerSocket
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HiveMqDisconnectRejectionAdversarialTest {
    @Test(timeout = 15_000)
    fun `rejected bounded teardown still disconnects its client and releases session ownership`() {
        EmbeddedBroker().use { broker ->
            val transport = HiveMqTransport()
            val connected = CountDownLatch(1)
            val clientId = "rejected-teardown-${UUID.randomUUID()}"
            transport.connect(
                broker.config(clientId).copy(automaticReconnect = true),
                object : MqttCallbacks {
                    override fun onConnected(
                        connection: MqttConnectionLease,
                        addressFamily: io.panelassistant.android.MqttAddressFamily?,
                    ) = connected.countDown()
                    override fun onDisconnected(
                        connection: MqttConnectionLease?,
                        causeMessage: String?,
                    ): Boolean = true
                },
            )
            assertTrue("transport did not connect", connected.await(5, TimeUnit.SECONDS))

            val gate = teardownGate()
            val ownerEntered = CountDownLatch(1)
            val releaseOwner = CountDownLatch(1)
            val owner = gate.submit {
                ownerEntered.countDown()
                releaseOwner.await()
            }
            try {
                assertTrue("teardown owner did not occupy the single slot", ownerEntered.await(1, TimeUnit.SECONDS))

                val rejected = transport.disconnectDetached()
                val failure = try {
                    rejected.get(1, TimeUnit.SECONDS)
                    null
                } catch (expected: ExecutionException) {
                    expected.cause
                }
                assertTrue("busy teardown did not report rejection", failure is RejectedExecutionException)

                // disconnectDetached owns and clears the session before admission. A second caller must
                // neither redisconnect the rejected owner's client nor inherit its exceptional result.
                transport.disconnectDetached().get(1, TimeUnit.SECONDS)

                awaitCondition("rejected teardown leaked an auto-reconnecting broker client") {
                    broker.server.listConnectedClients().none { it.clientID == clientId }
                }
                Thread.sleep(750)
                assertFalse(
                    "fallback disconnect left automatic reconnect armed",
                    broker.server.listConnectedClients().any { it.clientID == clientId },
                )
            } finally {
                releaseOwner.countDown()
                owner.get(1, TimeUnit.SECONDS)
            }
        }
    }

    private fun teardownGate(): MqttTeardownGate {
        val field = HiveMqTransport::class.java.getDeclaredField("TEARDOWN").apply { isAccessible = true }
        return field.get(null) as MqttTeardownGate
    }

    private class EmbeddedBroker : AutoCloseable {
        val port = ServerSocket(0).use { it.localPort }
        val server = Server().withConfig()
            .host("127.0.0.1")
            .port(port)
            .disablePersistence()
            .disableTelemetry()
            .startServer()

        fun config(clientId: String) = MqttConnectConfig(
            host = "127.0.0.1",
            port = port,
            tls = false,
            clientId = clientId,
            user = "configured-user",
            password = "configured-password",
            keepAliveSeconds = 10,
            willTopic = "ha-paneld-test/$clientId/availability",
            willPayload = "offline",
        )

        override fun close() = server.stopServer()
    }

    private companion object {
        fun awaitCondition(message: String, condition: () -> Boolean) {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (!condition()) {
                assertTrue(message, System.nanoTime() < deadline)
                Thread.sleep(20)
            }
        }
    }
}
