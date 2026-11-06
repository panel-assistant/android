package io.panelassistant.android

import android.content.ContextWrapper
import io.panelassistant.android.MdnsResponderTestSupport.LOOPBACK
import io.panelassistant.android.MdnsResponderTestSupport.RUN
import io.panelassistant.android.MdnsResponderTestSupport.browse
import io.panelassistant.android.util.MonotonicDeadline
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
class MdnsAdvertiserResponderTest {
    @Test fun ipv4OnlyAdvertiserPublishesRecordsAndReportsCompleteRetirement() =
        io.panelassistant.android.http.PaneldListenerPortTest.withDefaultPortLock {
        var locksHeld = 0
        val rig = io.panelassistant.android.http.PaneldServerHttpFixture()
        val advertiser = MdnsAdvertiser(
            context = ContextWrapper(null),
            config = rig.config,
            runtimePanelId = "responder-test-panel-$RUN",
            runtimeFriendlyName = "Responder Test Panel",
            acquireMulticastLock = {
                locksHeld++
                { locksHeld-- }
            },
            discoveryId = { "responder-test-did" },
        )
        val browser = JmDNS.create(InetAddress.getByName(LOOPBACK), "responder-test-browser")
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)
        val holder = java.net.ServerSocket().apply { bind(java.net.InetSocketAddress(LOOPBACK, 8888)) }
        val listener = io.panelassistant.android.http.PaneldListener(scope, { rig.config.httpPort }, { true }, onBound = { rig.config.httpPort = it }) {}
        try {
            advertiser.start(LOOPBACK)
            assertEquals(8888, browse(browser)!!.port)
            listener.start {}
            assertTrue(rig.config.httpPort in 1024..32767 && rig.config.httpPort != 8888)
            advertiser.ensureStarted()
            assertTrue(advertiser.health().advertising)
            assertEquals(1, locksHeld)

            val service = browse(browser)
            assertNotNull("the advertisement never reached the browser", service)
            assertEquals(listOf(LOOPBACK), service!!.inet4Addresses.map { it.hostAddress })
            assertTrue(service.inet6Addresses.isEmpty())
            assertEquals(rig.config.httpPort, service.port)
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
            listener.stop()
            holder.close()
            scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
            rig.close()
        }
        assertEquals("stop must release the multicast lock", 0, locksHeld)
    }

}
