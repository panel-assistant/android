package io.github.maxlyth.hapaneld

import android.content.ContextWrapper
import android.content.SharedPreferences
import io.github.maxlyth.hapaneld.util.MonotonicDeadline
import io.github.maxlyth.hapaneld.util.RetirableMutationGate
import java.lang.reflect.Proxy
import java.net.InetAddress
import java.util.concurrent.CountDownLatch
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

    @Test fun closedSecondaryResponderIsReadvertisedWithoutReplacingPrimary() {
        val advertiser = MdnsAdvertiser(
            context = ContextWrapper(null),
            config = Config(readOnlyPreferences()),
            runtimePanelId = "dual-responder-test-panel",
            runtimeFriendlyName = "Dual Responder Test Panel",
            runtimeHttpPort = 8888,
            acquireMulticastLock = { {} },
            discoveryId = { "dual-responder-test-did" },
            refreshIntervalMs = 100,
        )
        try {
            advertiser.start(LOOPBACK, SECONDARY_LOOPBACK)
            assertTrue("IPv4 never advertised", hasAddress(LOOPBACK, "dual-responder-test-panel", LOOPBACK))
            assertTrue("secondary never advertised", hasAddress(SECONDARY_LOOPBACK, "dual-responder-test-panel", SECONDARY_LOOPBACK))
            val primaryToken = JmDNS.create(InetAddress.getByName(LOOPBACK), "primary-token-browser").use {
                browse(it, "dual-responder-test-panel")?.getPropertyString("probe")
            }
            assertNotNull("primary TXT probe token missing", primaryToken)

            // JmDNS closes its own instance when a Responder.send throws. A second IPv4 loopback
            // address exercises the same secondary lifecycle on JVMs without IPv6 multicast.
            val secondary = MdnsAdvertiser::class.java.getDeclaredField("secondaryDns").apply {
                isAccessible = true
            }.get(advertiser) as JmDNS
            secondary.close()
            assertTrue("IPv4 must remain advertised", hasAddress(LOOPBACK, "dual-responder-test-panel", LOOPBACK))

            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
            var restored = false
            while (System.nanoTime() < deadline && !restored) {
                restored = hasAddress(SECONDARY_LOOPBACK, "dual-responder-test-panel", SECONDARY_LOOPBACK)
            }
            assertTrue("closed secondary responder was never readvertised", restored)
            assertTrue("primary must survive secondary repair", hasAddress(LOOPBACK, "dual-responder-test-panel", LOOPBACK))
            assertEquals(
                MdnsProbeResult.VISIBLE,
                probeMdnsService(LOOPBACK, "dual-responder-test-panel", Config.MDNS_SERVICE_TYPE, primaryToken!!),
            )
            assertEquals(0, advertiser.health().liveness.recoveryAttempts)
        } finally {
            advertiser.stop()
        }
    }

    @Test fun queuedSecondaryRepairCannotRestoreRemovedNetworkAddress() {
        val advertiser = MdnsAdvertiser(
            context = ContextWrapper(null),
            config = Config(readOnlyPreferences()),
            runtimePanelId = "removed-secondary-test-panel",
            runtimeFriendlyName = "Removed Secondary Test Panel",
            acquireMulticastLock = { {} },
            discoveryId = { "removed-secondary-test-did" },
            refreshIntervalMs = 100,
        )
        val gate = MdnsAdvertiser::class.java.getDeclaredField("ownerGate").apply {
            isAccessible = true
        }.get(advertiser) as RetirableMutationGate
        val topology = MdnsAdvertiser::class.java.getDeclaredField("topology").apply {
            isAccessible = true
        }.get(advertiser) as MdnsTopology
        val release = CountDownLatch(1)
        val entered = CountDownLatch(1)
        var holder: Thread? = null
        try {
            advertiser.start(LOOPBACK, SECONDARY_LOOPBACK)
            assertTrue(hasAddress(SECONDARY_LOOPBACK, "removed-secondary-test-panel", SECONDARY_LOOPBACK))
            holder = Thread {
                gate.runExclusive {
                    entered.countDown()
                    release.await()
                }
            }.apply { start() }
            assertTrue("owner gate was not held", entered.await(2, TimeUnit.SECONDS))
            val secondary = MdnsAdvertiser::class.java.getDeclaredField("secondaryDns").apply {
                isAccessible = true
            }.get(advertiser) as JmDNS
            secondary.close()

            // A network callback updates topology before entering the owner gate. Hold that gate
            // until the real refresh worker has queued repair and is waiting to execute it.
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
            var recoveryWorker: Thread? = null
            while (System.nanoTime() < deadline && recoveryWorker == null) {
                recoveryWorker = Thread.getAllStackTraces().keys.firstOrNull { thread ->
                    thread.name == "ha-paneld-mdns-recovery" &&
                        thread.stackTrace.any { it.className.endsWith("RetirableMutationGate") }
                }
                if (recoveryWorker == null) Thread.sleep(20)
            }
            assertNotNull("secondary repair was never queued", recoveryWorker)
            topology.request(LOOPBACK, null)
            release.countDown()
            holder.join(2_000)
            while (System.nanoTime() < deadline &&
                recoveryWorker!!.stackTrace.any { it.className.endsWith("RetirableMutationGate") }
            ) Thread.sleep(20)
            assertTrue("queued repair did not finish", recoveryWorker!!.stackTrace.none {
                it.className.endsWith("RetirableMutationGate")
            })
            assertTrue("primary vanished", hasAddress(LOOPBACK, "removed-secondary-test-panel", LOOPBACK))
            assertTrue(
                "queued repair restored an address removed by the network callback",
                !hasAddress(SECONDARY_LOOPBACK, "removed-secondary-test-panel", SECONDARY_LOOPBACK),
            )
        } finally {
            release.countDown()
            holder?.join(2_000)
            advertiser.stop()
        }
    }

    private fun hasAddress(binding: String, name: String, address: String): Boolean =
        JmDNS.create(InetAddress.getByName(binding), "dual-responder-browser").use { browser ->
            repeat(3) {
                if (browser.list(Config.MDNS_SERVICE_TYPE, 1_500)
                        .filter { it.name == name }
                        .flatMap { it.inetAddresses.toList() }
                        .any { it.hostAddress == address }) return@use true
            }
            false
        }

    private fun browse(browser: JmDNS, name: String = "responder-test-panel"): ServiceInfo? {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(9)
        while (System.nanoTime() < deadline) {
            val remainingMs = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime()).coerceAtLeast(1)
            browser.list(Config.MDNS_SERVICE_TYPE, minOf(3_000, remainingMs))
                .firstOrNull { it.name == name && it.getPropertyString("probe") != null }
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
        const val SECONDARY_LOOPBACK = "127.0.0.2"
    }
}
