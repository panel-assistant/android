package io.panelassistant.android

import android.content.ContextWrapper
import android.content.SharedPreferences
import io.panelassistant.android.MdnsResponderTestSupport.RUN
import io.panelassistant.android.MdnsResponderTestSupport.browse
import io.panelassistant.android.MdnsResponderTestSupport.readOnlyPreferences
import io.panelassistant.android.http.healthLine
import io.panelassistant.android.util.MonotonicDeadline
import java.lang.reflect.Proxy
import java.net.InetAddress
import javax.jmdns.JmDNS
import javax.jmdns.ServiceInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The production advertiser learns this panel's ADB serial only from an `_adb._tcp` answer at its own
 * address, carries it on the health line at once and on its TXT record from the next registration, and forgets
 * it when that service leaves.
 * Per-JVM loopback addresses keep other responder tests' advertisers from ever counting these services.
 */
class MdnsOwnAdbSerialResponderTest {
    @Test fun ownAdbAnnouncementReachesTxtAndHealthWhileForeignAndInvalidOnesNeverDo() {
        val panel = "adb-serial-test-panel-$RUN"
        val serial = "TESTSERIAL1$RUN"
        val config = config(panel)
        val health = { health(config) }
        val advertiser = MdnsAdvertiser(
            context = ContextWrapper(null),
            config = config,
            runtimePanelId = panel,
            runtimeFriendlyName = "Adb Serial Test Panel",
            runtimeHttpPort = 8888,
            acquireMulticastLock = { {} },
            discoveryId = { "adb-serial-test-did" },
        )
        val sameHost = JmDNS.create(InetAddress.getByName(OWN), "adb-serial-own")
        val otherHost = JmDNS.create(InetAddress.getByName(FOREIGN), "adb-serial-foreign")
        val listener = JmDNS.create(InetAddress.getByName(OWN), "adb-serial-listener")
        try {
            advertiser.start(OWN)
            assertNotNull("advertisement never appeared", JmDNS.create(InetAddress.getByName(OWN), "adb-serial-b0").use { browse(it, panel) })

            // Another panel's adbd, and a collision-renamed instance at our own address, are both heard and ignored.
            otherHost.registerService(ServiceInfo.create(ADB, "adb-OTHER$RUN", 5555, ""))
            sameHost.registerService(ServiceInfo.create(ADB, "adb-$serial (2)", 5555, ""))
            assertTrue("the foreign and renamed services were never on the wire", waitFor(10_000) {
                listener.list(ADB, 1_500).map { it.name }.containsAll(listOf("adb-OTHER$RUN", "adb-$serial (2)"))
            })
            Thread.sleep(3_000)
            assertNull(OwnAdbSerial.shared.current)
            assertFalse("no adb token while unknown", health().contains(" adb"))

            sameHost.registerService(ServiceInfo.create(ADB, "adb-$serial", 5555, ""))
            assertTrue("own serial never learned", waitFor(10_000) { OwnAdbSerial.shared.current == serial })
            assertTrue(health().endsWith(" adb=$serial\n"))
            // The registered record is left alone: it keeps answering, without the serial, until it is next
            // registered; replacing its text in place stopped JmDNS answering for it on a real Portal.
            assertNotNull("the advertisement stopped answering after the serial was learned",
                JmDNS.create(InetAddress.getByName(OWN), "adb-serial-b1").use { browse(it, panel) })
            advertiser.stop()
            advertiser.start(OWN)
            val txt = JmDNS.create(InetAddress.getByName(OWN), "adb-serial-b2").use {
                browse(it, panel) { info -> info.getPropertyString("adb") == serial }
            }
            assertNotNull("the next registration never carried the serial", txt)
            assertEquals("Adb Serial Test Panel", txt!!.getPropertyString("name"))

            sameHost.unregisterService(ServiceInfo.create(ADB, "adb-$serial", 5555, ""))
            assertTrue("removal never cleared the serial", waitFor(25_000) { OwnAdbSerial.shared.current == null })
            assertFalse(health().contains(" adb"))
        } finally {
            advertiser.stop()
            listOf(sameHost, otherHost, listener).forEach { runCatching { it.close() } }
            OwnAdbSerial.shared.forget("adb-$serial")
        }
    }

    @Test fun healthCarriesOnlyAValidLearnedSerial() {
        val config = config("adb-serial-health-panel")
        try {
            assertFalse(OwnAdbSerial.shared.learn("adb-X (2)"))
            assertFalse("no adb token while unknown", health(config).contains(" adb"))
            assertTrue(OwnAdbSerial.shared.learn("adb-TESTSERIAL1"))
            assertTrue(health(config).endsWith(" adb=TESTSERIAL1\n"))
            assertTrue(OwnAdbSerial.shared.forget("adb-TESTSERIAL1"))
            assertFalse(health(config).contains(" adb"))
        } finally {
            OwnAdbSerial.shared.forget("adb-TESTSERIAL1")
        }
    }

    private fun config(panel: String): Config {
        val defaults = readOnlyPreferences()
        return Config(Proxy.newProxyInstance(
            SharedPreferences::class.java.classLoader, arrayOf(SharedPreferences::class.java),
        ) { _, method, args ->
            if (method.name == "getString" && args?.get(0) == "panel_id") panel else method.invoke(defaults, *args.orEmpty())
        } as SharedPreferences).also {
            // Settings reads answer their JVM defaults through any resolver, as in PaneldServerHttpFixture.
            Config::class.java.getDeclaredField("contentResolver").apply { isAccessible = true }
                .set(it, object : android.content.ContentResolver(null) {})
        }
    }

    private fun health(config: Config) = healthLine(config, "io.panelassistant.android", { "b" }, { "c" }) { "" }

    private fun waitFor(timeoutMs: Long, condition: () -> Boolean): Boolean {
        val deadline = MonotonicDeadline(timeoutMs)
        while (deadline.remainingMs() > 0) {
            if (condition()) return true
            Thread.sleep(50)
        }
        return condition()
    }

    private companion object {
        const val ADB = "_adb._tcp.local."
        // Own-ness is by address, so concurrent test JVMs (which share loopback multicast) each take their own.
        val OWN = "127.${1 + RUN % 250}.${RUN / 250 % 250}.5"
        val FOREIGN = "127.${1 + RUN % 250}.${RUN / 250 % 250}.6"
    }
}
