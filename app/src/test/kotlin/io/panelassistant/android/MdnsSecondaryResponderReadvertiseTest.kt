package io.panelassistant.android

import android.content.ContextWrapper
import io.panelassistant.android.MdnsResponderTestSupport.LOOPBACK
import io.panelassistant.android.MdnsResponderTestSupport.RUN
import io.panelassistant.android.MdnsResponderTestSupport.SECONDARY_LOOPBACK
import io.panelassistant.android.MdnsResponderTestSupport.browse
import io.panelassistant.android.MdnsResponderTestSupport.hasAddress
import io.panelassistant.android.MdnsResponderTestSupport.readOnlyPreferences
import java.net.InetAddress
import java.util.concurrent.TimeUnit
import javax.jmdns.JmDNS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The production advertiser, started on the loopback IPv4 address and read back by an independent
 * JmDNS browser. One test per class, so the slow responder tests spread across test forks.
 */
class MdnsSecondaryResponderReadvertiseTest {
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
            assertTrue("IPv4 must remain advertised", hasAddress(LOOPBACK, "dual-responder-test-panel-$RUN", LOOPBACK, timeoutMs = 15_000))

            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
            var restored = false
            while (System.nanoTime() < deadline && !restored) {
                restored = hasAddress(SECONDARY_LOOPBACK, "dual-responder-test-panel-$RUN", SECONDARY_LOOPBACK)
            }
            assertTrue("closed secondary responder was never readvertised", restored)
            assertTrue("primary must survive secondary repair", hasAddress(LOOPBACK, "dual-responder-test-panel-$RUN", LOOPBACK, timeoutMs = 15_000))
            assertEquals(
                MdnsProbeResult.VISIBLE,
                probeMdnsService(LOOPBACK, "dual-responder-test-panel-$RUN", Config.MDNS_SERVICE_TYPE, primaryToken!!),
            )
            assertEquals(0, advertiser.health().liveness.recoveryAttempts)
        } finally {
            advertiser.stop()
        }
    }

}
