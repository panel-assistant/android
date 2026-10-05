package io.panelassistant.android

import android.content.ContextWrapper
import android.content.SharedPreferences
import io.panelassistant.android.util.MonotonicDeadline
import io.panelassistant.android.util.RetirableMutationGate
import java.lang.reflect.Proxy
import java.net.InetAddress
import java.net.SocketException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import javax.jmdns.JmDNS
import javax.jmdns.ServiceInfo
import javax.jmdns.impl.DNSOutgoing
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
    @Test fun oneFailedResponseSendKeepsTheOriginalAdvertisementOnWire() {
        val failNext = AtomicBoolean(false)
        val sendFailed = CountDownLatch(1)
        val sendRetried = CountDownLatch(1)
        val failedPacket = AtomicReference<DNSOutgoing?>()
        val advertiser = MdnsAdvertiser(
            context = ContextWrapper(null),
            config = Config(readOnlyPreferences()),
            runtimePanelId = "transient-send-panel-$RUN",
            runtimeFriendlyName = "Transient Send Panel",
            runtimeHttpPort = 8888,
            acquireMulticastLock = { {} },
            discoveryId = { "transient-send-did" },
            refreshIntervalMs = 100,
            createDns = { address, name ->
                object : MdnsSendRetryDns(address, name) {
                    override fun sendPacket(outgoing: DNSOutgoing) {
                        if (outgoing === failedPacket.get()) sendRetried.countDown()
                        if (isResponderReply(outgoing) && failNext.compareAndSet(true, false)) {
                            failedPacket.set(outgoing)
                            sendFailed.countDown()
                            throw SocketException("sendto failed: EPERM (Operation not permitted)")
                        }
                        super.sendPacket(outgoing)
                    }
                }
            },
        )
        try {
            advertiser.start(LOOPBACK)
            val original = JmDNS.create(InetAddress.getByName(LOOPBACK), "initial-send-browser").use {
                browse(it, "transient-send-panel-$RUN")
            }
            assertNotNull("the original service never appeared", original)
            val token = original!!.getPropertyString("probe")

            failNext.set(true)
            val afterFault = JmDNS.create(InetAddress.getByName(LOOPBACK), "fault-send-browser").use {
                browse(it, "transient-send-panel-$RUN")
            }
            assertTrue("the browser never triggered a response send", sendFailed.await(5, TimeUnit.SECONDS))
            assertNotNull("one failed send withdrew the real advertisement", afterFault)
            assertEquals("the responder was replaced after one failed send", token, afterFault!!.getPropertyString("probe"))
            assertTrue("the failed response was not retried", sendRetried.await(5, TimeUnit.SECONDS))
            assertEquals(0, advertiser.health().liveness.recoveryAttempts)
        } finally {
            advertiser.stop()
        }
    }

    @Test fun ipv4OnlyAdvertiserPublishesRecordsAndReportsCompleteRetirement() {
        var locksHeld = 0
        val advertiser = MdnsAdvertiser(
            context = ContextWrapper(null),
            config = Config(readOnlyPreferences()),
            runtimePanelId = "responder-test-panel-$RUN",
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

    @Test fun persistentFailedResponseSendRecoversAfterCompletedTeardown() {
        val remainingFailures = AtomicInteger(0)
        val failedSends = CountDownLatch(2)
        val advertiser = MdnsAdvertiser(
            context = ContextWrapper(null),
            config = Config(readOnlyPreferences()),
            runtimePanelId = "responder-recovery-panel-$RUN",
            runtimeFriendlyName = "Responder Recovery Panel",
            runtimeHttpPort = 8888,
            acquireMulticastLock = {
                // Model a close that finishes after the 2-second worker-drain budget.
                { Thread.sleep(2_100) }
            },
            discoveryId = { "responder-recovery-did" },
            refreshIntervalMs = 100,
            createDns = { address, name ->
                object : MdnsSendRetryDns(address, name) {
                    override fun sendPacket(outgoing: DNSOutgoing) {
                        if (isResponderReply(outgoing) && remainingFailures.getAndUpdate { maxOf(0, it - 1) } > 0) {
                            failedSends.countDown()
                            throw SocketException("sendto failed: EPERM (Operation not permitted)")
                        }
                        super.sendPacket(outgoing)
                    }
                }
            },
        )
        val ipv4Browser = JmDNS.create(InetAddress.getByName(LOOPBACK), "recovery-ipv4-browser")
        try {
            advertiser.start(LOOPBACK)
            val original = browse(ipv4Browser, "responder-recovery-panel-$RUN")
            assertNotNull("the primary never advertised", original)
            val oldToken = original!!.getPropertyString("probe")

            // Two failed attempts for one real response still reach JmDNS's self-close path.
            remainingFailures.set(2)
            assertEquals(
                "the production probe must see the withdrawn responder",
                MdnsProbeResult.MISSING,
                probeMdnsService(LOOPBACK, "responder-recovery-panel-$RUN", Config.MDNS_SERVICE_TYPE, oldToken),
            )
            assertTrue("the response did not fail twice", failedSends.await(5, TimeUnit.SECONDS))

            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60)
            var replacement: ServiceInfo? = null
            while (System.nanoTime() < deadline) {
                val state = advertiser.health().liveness
                assertTrue(
                    "closed responders must not be reported as an undrained teardown: $state",
                    state.reasonCode != MdnsReasonCode.TEARDOWN_FAILED,
                )
                JmDNS.create(InetAddress.getByName(LOOPBACK), "recovery-check-browser").use { fresh ->
                    replacement = browse(fresh, "responder-recovery-panel-$RUN")
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
            runtimePanelId = "dual-responder-test-panel-$RUN",
            runtimeFriendlyName = "Dual Responder Test Panel",
            runtimeHttpPort = 8888,
            acquireMulticastLock = { {} },
            discoveryId = { "dual-responder-test-did" },
            refreshIntervalMs = 100,
        )
        try {
            advertiser.start(LOOPBACK, SECONDARY_LOOPBACK)
            assertTrue("IPv4 never advertised", hasAddress(LOOPBACK, "dual-responder-test-panel-$RUN", LOOPBACK))
            assertTrue("secondary never advertised", hasAddress(SECONDARY_LOOPBACK, "dual-responder-test-panel-$RUN", SECONDARY_LOOPBACK))
            val primaryToken = JmDNS.create(InetAddress.getByName(LOOPBACK), "primary-token-browser").use {
                browse(it, "dual-responder-test-panel-$RUN")?.getPropertyString("probe")
            }
            assertNotNull("primary TXT probe token missing", primaryToken)

            // JmDNS closes its own instance when a Responder.send throws. A second IPv4 loopback
            // address exercises the same secondary lifecycle on JVMs without IPv6 multicast.
            val secondary = MdnsAdvertiser::class.java.getDeclaredField("secondaryDns").apply {
                isAccessible = true
            }.get(advertiser) as JmDNS
            secondary.close()
            assertTrue("IPv4 must remain advertised", hasAddress(LOOPBACK, "dual-responder-test-panel-$RUN", LOOPBACK))

            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
            var restored = false
            while (System.nanoTime() < deadline && !restored) {
                restored = hasAddress(SECONDARY_LOOPBACK, "dual-responder-test-panel-$RUN", SECONDARY_LOOPBACK)
            }
            assertTrue("closed secondary responder was never readvertised", restored)
            assertTrue("primary must survive secondary repair", hasAddress(LOOPBACK, "dual-responder-test-panel-$RUN", LOOPBACK))
            assertEquals(
                MdnsProbeResult.VISIBLE,
                probeMdnsService(LOOPBACK, "dual-responder-test-panel-$RUN", Config.MDNS_SERVICE_TYPE, primaryToken!!),
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
            runtimePanelId = "removed-secondary-test-panel-$RUN",
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
            assertTrue(hasAddress(SECONDARY_LOOPBACK, "removed-secondary-test-panel-$RUN", SECONDARY_LOOPBACK))
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
            assertTrue("primary vanished", hasAddress(LOOPBACK, "removed-secondary-test-panel-$RUN", LOOPBACK))
            assertTrue(
                "queued repair restored an address removed by the network callback",
                !hasAddress(SECONDARY_LOOPBACK, "removed-secondary-test-panel-$RUN", SECONDARY_LOOPBACK),
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

    private fun browse(browser: JmDNS, name: String = "responder-test-panel-$RUN"): ServiceInfo? {
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

    private fun isResponderReply(outgoing: DNSOutgoing): Boolean = outgoing.isResponse &&
        Thread.currentThread().stackTrace.any { it.className == "javax.jmdns.impl.tasks.Responder" }

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
        /** Per-JVM suffix: concurrent debug and release test JVMs share the loopback multicast group. */
        val RUN = ProcessHandle.current().pid()
    }
}
