package io.panelassistant.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.net.InetAddress

class MqttTransportStatusTest {
    @Test fun selectedAddressFamilyIsReportedWithTheTransport() {
        assertEquals(MqttAddressFamily.IPV4, mqttAddressFamily(InetAddress.getByName("192.0.2.1")))
        assertEquals(MqttAddressFamily.IPV6, mqttAddressFamily(InetAddress.getByName("2001:db8::1")))
        assertNull(mqttAddressFamily(null))
        assertEquals("TCP/IPv4", mqttTransportLabel(tls = false, MqttAddressFamily.IPV4))
        assertEquals("TLS/IPv6", mqttTransportLabel(tls = true, MqttAddressFamily.IPV6))
        assertEquals("TCP", mqttTransportLabel(tls = false, family = null))
    }
}
