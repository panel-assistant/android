package io.panelassistant.android

import io.panelassistant.android.util.HaTransportFault
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.net.UnknownHostException
import java.util.concurrent.CountDownLatch

class HaTriedAddressTest {
    private val asked = mutableListOf<String>()

    private fun answers(ip: String?): (String) -> String? = { host ->
        asked += host
        ip
    }

    @Test fun `a named server is shown with the address its name resolves to`() = runTest {
        assertEquals(
            "http://homeassistant.local:8123 (192.168.1.20)",
            HaTriedAddress.describe("http://homeassistant.local:8123/", HaTransportFault.TIMEOUT, answers("192.168.1.20")),
        )
        assertEquals(listOf("homeassistant.local"), asked)
    }

    @Test fun `an address that is already an IP is shown alone and never looked up`() = runTest {
        assertEquals(
            "http://192.168.1.20:8123",
            HaTriedAddress.describe("http://192.168.1.20:8123", HaTransportFault.REFUSED, answers("192.168.1.20")),
        )
        assertEquals(
            "http://[fd00::20]:8123",
            HaTriedAddress.describe("http://[fd00::20]:8123", HaTransportFault.REFUSED, answers("fd00::20")),
        )
        assertEquals(emptyList<String>(), asked)
    }

    @Test fun `a name that did not resolve is shown alone and not looked up again`() = runTest {
        assertEquals(
            "https://ha.example",
            HaTriedAddress.describe("https://ha.example", HaTransportFault.DNS, answers("203.0.113.9")),
        )
        assertEquals(emptyList<String>(), asked)
    }

    @Test fun `a lookup that fails or finds nothing leaves the address alone`() = runTest {
        assertEquals(
            "https://ha.example",
            HaTriedAddress.describe("https://ha.example", HaTransportFault.TIMEOUT, { throw UnknownHostException(it) }),
        )
        assertEquals("https://ha.example", HaTriedAddress.describe("https://ha.example", HaTransportFault.TIMEOUT, answers(null)))
    }

    @Test fun `a resolver that does not answer in time never holds the screen`() = runTest {
        val release = CountDownLatch(1)
        try {
            assertEquals(
                "https://ha.example",
                HaTriedAddress.describe("https://ha.example", HaTransportFault.TIMEOUT, { release.await(); "203.0.113.9" }, timeoutMs = 50),
            )
        } finally {
            release.countDown()
        }
    }

    @Test fun `no configured address shows nothing`() = runTest {
        assertNull(HaTriedAddress.describe("  ", HaTransportFault.UNKNOWN, answers("192.168.1.20")))
        assertEquals(emptyList<String>(), asked)
    }
}
