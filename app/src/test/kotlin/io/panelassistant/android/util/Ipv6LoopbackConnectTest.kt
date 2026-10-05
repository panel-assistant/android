package io.panelassistant.android.util

import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * IPv6 connectivity regression guard: stand up a listener on the real `::1` loopback, then take the
 * SAME path the MQTT bridge uses — [BrokerEndpoint.parse] a `tcp://[::1]:port` spec, resolve + [select]
 * the address, [hostString] it — and prove the result actually CONNECTS over IPv6. If IPv6-literal
 * bracket handling or family selection regresses, the socket won't connect and this fails.
 *
 * Skips (does not fail) when the environment has no IPv6 loopback, so it's safe in CI without IPv6.
 */
class Ipv6LoopbackConnectTest {

    private fun ipv6LoopbackAvailable(): Boolean = runCatching {
        ServerSocket().use { it.bind(InetSocketAddress(InetAddress.getByName("::1"), 0)); true }
    }.getOrDefault(false)

    @Test fun connectsOverIpv6LoopbackViaBrokerEndpoint() {
        assumeTrue("no IPv6 loopback in this environment", ipv6LoopbackAvailable())

        ServerSocket().use { server ->
            server.bind(InetSocketAddress(InetAddress.getByName("::1"), 0))
            val port = server.localPort

            // 1) parse the bracketed IPv6 broker spec exactly as MqttBridge would.
            val (host, parsedPort) = BrokerEndpoint.parse("tcp://[::1]:$port")!!
            assertEquals("::1", host)
            assertEquals(port, parsedPort)

            // 2) resolve + family-select (prefer IPv6) + format for the client.
            val addrs = InetAddress.getAllByName(host).toList()
            val chosen = BrokerEndpoint.select(addrs, preferIpv4 = false)!!
            assertTrue("selected an IPv6 address", chosen is java.net.Inet6Address)
            val connectHost = BrokerEndpoint.hostString(chosen)

            // 3) the derived endpoint must actually connect over IPv6.
            val accepted = Thread { runCatching { server.accept().close() } }.apply { start() }
            Socket().use { s ->
                s.connect(InetSocketAddress(connectHost, parsedPort), 2000)
                assertTrue("connected over IPv6 loopback", s.isConnected)
            }
            accepted.join(2000)
        }
    }
}
