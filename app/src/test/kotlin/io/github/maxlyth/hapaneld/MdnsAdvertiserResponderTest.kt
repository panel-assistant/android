package io.github.maxlyth.hapaneld

import android.content.ContextWrapper
import android.content.SharedPreferences
import io.github.maxlyth.hapaneld.util.MonotonicDeadline
import java.lang.reflect.Proxy
import java.net.InetAddress
import java.util.concurrent.TimeUnit
import javax.jmdns.JmDNS
import javax.jmdns.ServiceInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The production advertiser, started on the loopback IPv4 address and read back by an independent
 * JmDNS browser: an IPv4-only panel still publishes one A record and the TXT keys it always has, and
 * holds the multicast lock only while it advertises.
 */
class MdnsAdvertiserResponderTest {
    @Test fun ipv4OnlyAdvertiserPublishesRecordsAndReportsCompleteRetirement() {
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
            val retired = advertiser.retire(MonotonicDeadline(2_000)).get(15, TimeUnit.SECONDS)
            assertEquals("the retired responder must release its multicast lock", 0, locksHeld)
            assertTrue("completed responder retirement must report success", retired)
        } finally {
            advertiser.stop()
            browser.close()
        }
        assertEquals("stop must release the multicast lock", 0, locksHeld)
    }

    @Test fun lostIpv4ResponderRecoversAfterCompletedTeardown() {
        val advertiser = MdnsAdvertiser(
            context = ContextWrapper(null),
            config = Config(readOnlyPreferences()),
            runtimePanelId = "responder-recovery-panel",
            runtimeFriendlyName = "Responder Recovery Panel",
            runtimeHttpPort = 8888,
            acquireMulticastLock = {
                // Model a close that finishes after the 2-second worker-drain budget.
                { Thread.sleep(2_100) }
            },
            discoveryId = { "responder-recovery-did" },
            refreshIntervalMs = 100,
        )
        val ipv4Browser = JmDNS.create(InetAddress.getByName(LOOPBACK), "recovery-ipv4-browser")
        try {
            advertiser.start(LOOPBACK)
            val original = browse(ipv4Browser, "responder-recovery-panel")
            assertNotNull("the primary never advertised", original)
            val oldToken = original!!.getPropertyString("probe")

            // Kill only the primary responder, as observed on the affected panel; its owner still
            // believes it is advertising. This reflection only injects the fault, not the verdict.
            val primary = MdnsAdvertiser::class.java.getDeclaredField("jmdns").apply {
                isAccessible = true
            }.get(advertiser) as JmDNS
            primary.close()
            assertEquals(
                "the production probe must see the dropped responder",
                MdnsProbeResult.MISSING,
                probeMdnsService(LOOPBACK, "responder-recovery-panel", Config.MDNS_SERVICE_TYPE, oldToken),
            )

            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60)
            var replacement: ServiceInfo? = null
            while (System.nanoTime() < deadline) {
                val state = advertiser.health().liveness
                assertTrue(
                    "closed responders must not be reported as an undrained teardown: $state",
                    state.reasonCode != MdnsReasonCode.TEARDOWN_FAILED,
                )
                JmDNS.create(InetAddress.getByName(LOOPBACK), "recovery-check-browser").use { fresh ->
                    replacement = browse(fresh, "responder-recovery-panel")
                }
                val replacementToken = replacement?.getPropertyString("probe")
                if (replacementToken != null && replacementToken != oldToken) break
            }
            assertNotNull("three missing IPv4 probes must recreate the responder", replacement)
            assertNotEquals(oldToken, replacement!!.getPropertyString("probe"))
        } finally {
            advertiser.stop()
            ipv4Browser.close()
        }
    }

    private fun browse(browser: JmDNS, name: String = "responder-test-panel"): ServiceInfo? {
        repeat(3) {
            browser.list(Config.MDNS_SERVICE_TYPE, 3_000)
                .firstOrNull { it.name == name && it.getPropertyString("probe") != null }
                ?.let { return it }
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
