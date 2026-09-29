package io.github.maxlyth.hapaneld.util

import java.io.IOException
import java.net.Inet6Address
import java.net.InetAddress
import org.junit.Assert.assertEquals
import org.junit.Test

class LocalPanelAddressesTest {
    @Test fun `hello candidates prefer default network and retain both families and other interfaces`() {
        val primary = listOf(ip("2001:db8::10"), ip("192.0.2.10"))
        val secondary = listOf(ip("192.0.2.10"), ip("198.51.100.10"), ip("fd00::10"))
        assertEquals(
            (primary + secondary.drop(1)).map { it.hostAddress },
            localPanelAddresses(primary) { secondary },
        )
    }

    @Test fun `hello candidates exclude unusable endpoints remove zones and bound the list`() {
        val invalid = listOf("0.0.0.0", "::", "127.0.0.1", "::1", "169.254.1.2", "fe80::1", "224.0.0.1", "ff02::1").map(::ip)
        val scoped = Inet6Address.getByAddress(null, ip("fd00::10").address, 4)
        val secondary = (1..20).map { ip("192.0.2.$it") }
        assertEquals(
            listOf(ip("fd00::10").hostAddress) + secondary.take(15).map { it.hostAddress },
            localPanelAddresses(invalid + scoped) { secondary },
        )
    }

    @Test fun `secondary collection failure retains default network addresses`() {
        assertEquals(listOf("192.0.2.10"), localPanelAddresses(listOf(ip("192.0.2.10"))) {
            throw IOException("interfaces unavailable")
        })
    }

    private fun ip(literal: String): InetAddress = InetAddress.getByName(literal)
}
