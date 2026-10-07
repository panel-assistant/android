package io.panelassistant.android.mqtt

import io.panelassistant.android.testsupport.TestSources
import io.moquette.broker.Server
import java.io.File
import java.net.ServerSocket
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The transport behavior is executable here. MqttBridge now accepts an MqttTransport constructor
 * parameter (see MqttWireGoldenTest for a bridge driven through it), but no bridge-level behavioral test
 * of the anonymous-discovery selection exists yet, so the source guard below still pins that selection
 * while the broker test proves what false does.
 */
class CredentiallessDiscoveryReconnectAdversarialTest {
    // Source-text reason: pins MqttBridge.kt, deleted with the MQTT removal.
    @Test(timeout = 15_000)
    fun `credentialless discovery client does not reconnect after broker returns`() {
        val port = ServerSocket(0).use { it.localPort }
        var broker = EmbeddedBroker(port)
        val transport = HiveMqTransport()
        val connections = AtomicInteger()
        val firstConnected = CountDownLatch(1)
        val disconnected = CountDownLatch(1)
        val clientId = "anonymous-discovery-${UUID.randomUUID()}"
        try {
            transport.connect(
                broker.config(clientId).copy(automaticReconnect = false),
                object : MqttCallbacks {
                    override fun onConnected(
                        connection: MqttConnectionLease,
                        addressFamily: io.panelassistant.android.MqttAddressFamily?,
                    ) {
                        connections.incrementAndGet()
                        firstConnected.countDown()
                    }

                    override fun onDisconnected(
                        connection: MqttConnectionLease?,
                        causeMessage: String?,
                    ): Boolean {
                        disconnected.countDown()
                        return true
                    }
                },
            )
            assertTrue("anonymous discovery probe did not connect", firstConnected.await(5, TimeUnit.SECONDS))

            broker.close()
            assertTrue("transport did not observe broker loss", disconnected.await(5, TimeUnit.SECONDS))
            broker = EmbeddedBroker(port)

            Thread.sleep(2_500)
            assertEquals("credential-less probe reconnected after broker restart", 1, connections.get())
            assertTrue("credential-less client returned to the broker", broker.server.listConnectedClients().isEmpty())
        } finally {
            transport.disconnectDetached().get(3, TimeUnit.SECONDS)
            broker.close()
        }
    }

    @Test
    fun `bridge grants automatic reconnect only to configured or credentialed connections`() {
        val source = productionSource("io/panelassistant/android/MqttBridge.kt").readText()

        assertTrue(
            "MqttBridge no longer disables transport retries for anonymous discovery probes",
            source.contains(
                "automaticReconnect = credentials.user.isNotEmpty() || configuredBroker.isNotEmpty(),",
            ),
        )
    }

    private fun productionSource(relative: String): File = TestSources.appFile("src/main/kotlin/$relative")

    private class EmbeddedBroker(private val port: Int) : AutoCloseable {
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
            user = null,
            password = null,
            keepAliveSeconds = 10,
            willTopic = "ha-paneld-test/$clientId/availability",
            willPayload = "offline",
        )

        override fun close() = runCatching { server.stopServer() }.let { Unit }
    }
}
