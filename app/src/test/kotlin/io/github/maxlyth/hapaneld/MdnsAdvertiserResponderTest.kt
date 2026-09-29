package io.github.maxlyth.hapaneld

import android.content.ContextWrapper
import android.content.SharedPreferences
import java.lang.reflect.Proxy
import java.net.InetAddress
import java.util.concurrent.TimeUnit
import javax.jmdns.JmDNS
import javax.jmdns.ServiceInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The production advertiser, started on the loopback IPv4 address and read back by an independent
 * JmDNS browser: an IPv4-only panel still publishes one A record and the TXT keys it always has, and
 * holds the multicast lock only while it advertises.
 */
class MdnsAdvertiserResponderTest {
    @Test fun ipv4OnlyAdvertiserPublishesOneAddressRecordAndItsTxtKeys() {
        var locksHeld = 0
        val advertiser = MdnsAdvertiser(
            context = ContextWrapper(null),
            config = Config(readOnlyPreferences()),
            runtimePanelId = "responder-test-panel",
            runtimeFriendlyName = "Responder Test Panel",
            runtimeHttpPort = 8888,
            acquireMulticastLock = {
                locksHeld++
                { locksHeld-- }
            },
            discoveryId = { "responder-test-did" },
        )
        val browser = JmDNS.create(InetAddress.getByName(LOOPBACK), "responder-test-browser")
        try {
            advertiser.start(LOOPBACK)
            assertTrue(advertiser.health().advertising)
            assertEquals(1, locksHeld)

            val service = browse(browser)
            assertNotNull("the advertisement never reached the browser", service)
            assertEquals(listOf(LOOPBACK), service!!.inet4Addresses.map { it.hostAddress })
            assertTrue(service.inet6Addresses.isEmpty())
            assertEquals(8888, service.port)
            assertEquals("Responder Test Panel", service.getPropertyString("name"))
            assertEquals("responder-test-did", service.getPropertyString("did"))
            assertEquals(
                setOf("ver", "caps", "path", "name", "probe", "did"),
                service.propertyNames.toList().toSet(),
            )
        } finally {
            advertiser.stop()
            browser.close()
        }
        assertEquals("stop must release the multicast lock", 0, locksHeld)
    }

    private fun browse(browser: JmDNS): ServiceInfo? {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(9)
        while (System.nanoTime() < deadline) {
            val remainingMs = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime()).coerceAtLeast(1)
            browser.list(Config.MDNS_SERVICE_TYPE, minOf(3_000, remainingMs))
                .firstOrNull { it.name == "responder-test-panel" }
                ?.let { return it }
            // A cached unrelated service can make list return immediately.
            val remainingNs = deadline - System.nanoTime()
            if (remainingNs > 0) TimeUnit.NANOSECONDS.sleep(minOf(TimeUnit.MILLISECONDS.toNanos(20), remainingNs))
        }
        return null
    }

    /** Every read answers its default; the advertiser only reads identity from its configuration. */
    private fun readOnlyPreferences(): SharedPreferences {
        lateinit var editor: SharedPreferences.Editor
        editor = Proxy.newProxyInstance(
            SharedPreferences.Editor::class.java.classLoader,
            arrayOf(SharedPreferences.Editor::class.java),
        ) { _, method, _ ->
            when (method.name) {
                "commit" -> true
                "apply" -> null
                else -> editor
            }
        } as SharedPreferences.Editor
        return Proxy.newProxyInstance(
            SharedPreferences::class.java.classLoader,
            arrayOf(SharedPreferences::class.java),
        ) { _, method, args ->
            when (method.name) {
                "getAll" -> emptyMap<String, Any?>()
                "contains" -> false
                "edit" -> editor
                "registerOnSharedPreferenceChangeListener", "unregisterOnSharedPreferenceChangeListener" -> null
                "toString" -> "ReadOnlyPreferences"
                else -> args?.getOrNull(1)
            }
        } as SharedPreferences
    }

    private companion object {
        const val LOOPBACK = "127.0.0.1"
    }
}
