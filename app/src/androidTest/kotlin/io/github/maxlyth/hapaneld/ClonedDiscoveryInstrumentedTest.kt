package io.github.maxlyth.hapaneld

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.maxlyth.hapaneld.dashboard.HistoricalCatalogFixture
import io.github.maxlyth.hapaneld.persistence.AppState
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.UUID
import javax.jmdns.JmDNS
import javax.jmdns.ServiceInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Separate application stores on the same Android device reproduce a cloned ANDROID_ID. */
@RunWith(AndroidJUnit4::class)
class ClonedDiscoveryInstrumentedTest {
    @Test fun independentInstallsSharingAndroidIdAdvertiseIndependentStableIdentities() {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val run = UUID.randomUUID().toString().replace("-", "")
        val contexts = listOf("one", "two").map { label ->
            val isolated = HistoricalCatalogFixture.isolatedContext(base, "discovery-$run-$label")
            object : ContextWrapper(isolated) {
                override fun getApplicationContext(): Context = this
                override fun getPackageName(): String = "${base.packageName}.discovery.$run.$label"
                override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                    base.getSharedPreferences("discovery-$run-$label-$name", mode)
            }
        }
        val configs = contexts.map(::Config)
        assertTrue("the test must use a real nonempty Android ID", configs[0].androidId.isNotBlank())
        assertEquals("both installs must share the cloned Android ID", configs[0].androidId, configs[1].androidId)
        configs.forEach { it.ensureDeviceUid() }
        val address = NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && it.supportsMulticast() }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>().first { !it.isLoopbackAddress }
        val host = requireNotNull(address.hostAddress)
        var browser = JmDNS.create(address, "discovery-browser-$run")
        var names = listOf("discovery-one-$run", "discovery-two-$run")
        fun advertisers(configurations: List<Config>) = configurations.mapIndexed { index, config ->
            MdnsAdvertiser(
                context = contexts[index], config = config,
                runtimePanelId = names[index], runtimeFriendlyName = names[index], runtimeHttpPort = 8888,
                acquireMulticastLock = { {} },
            )
        }
        var running = advertisers(configs)
        fun discovered(): List<String> = names.map { name ->
            var service: ServiceInfo? = null
            val deadline = android.os.SystemClock.elapsedRealtime() + 15_000
            while (service == null && android.os.SystemClock.elapsedRealtime() < deadline) {
                service = browser.list(Config.MDNS_SERVICE_TYPE, 1_000)
                    .firstOrNull { it.name == name }
                if (service == null) Thread.sleep(100)
            }
            val did = service?.getPropertyString("did")
            assertTrue("$name must publish its discovery identity (service=$service, did=$did)", did?.matches(Regex("[0-9a-f]{64}")) == true)
            did!!
        }
        try {
            running.forEach { it.start(host) }
            val first = discovered()
            assertNotEquals("cloned Android IDs must not merge separate installs", first[0], first[1])
            running.forEach { it.stop() }
            browser.close()
            browser = JmDNS.create(address, "discovery-restarted-browser-$run")
            contexts.forEach { assertTrue(AppState.flush(it, 5_000)) }
            // A fresh AppState cache reads the same database, without reusing in-memory identities.
            val reopenedConfigs = contexts.map { original ->
                object : ContextWrapper(original) {
                    override fun getApplicationContext(): Context = this
                    override fun getPackageName(): String = "${original.packageName}.reopened"
                }
            }.map(::Config)
            reopenedConfigs.forEachIndexed { index, reopened ->
                assertEquals(configs[index].deviceUid, reopened.ensureDeviceUid())
            }
            names = names.map { "$it-restarted" }
            running = advertisers(reopenedConfigs)
            running.forEach { it.start(host) }
            assertEquals("a responder restart must retain both identities", first, discovered())
        } finally {
            running.forEach { it.stop() }
            browser.close()
            contexts.forEach(HistoricalCatalogFixture::clean)
        }
    }
}
