package io.panelassistant.android.http

import android.content.ContextWrapper
import android.content.SharedPreferences
import io.panelassistant.android.Config
import io.panelassistant.android.MdnsAdvertiser
import io.panelassistant.android.Config.SecurityMode
import io.panelassistant.android.persistence.SqliteStatePreferences
import java.io.File
import java.util.concurrent.Executors
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.URL
import javax.jmdns.JmDNS
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The actual server callback owns durable port selection; publication consumes its result. */
class PaneldServerPortPublicationTest {
    @Test fun actualServerPersistsFallbackBeforeStartupAndRebindPublication() =
        PaneldListenerPortTest.withDefaultPortLock {
            PortRig().use { rig ->
                val name = "port-publication-${ProcessHandle.current().pid()}"
                val advertiser = MdnsAdvertiser(
                    ContextWrapper(null), rig.config, runtimePanelId = name,
                    runtimeFriendlyName = "Port publication", acquireMulticastLock = { {} },
                    discoveryId = { "port-publication" },
                )
                val published = mutableListOf<Pair<Set<String>, Int>>()
                rig.onReconfigure { keys ->
                    // The production owner receives a durable choice, with HTTP already listening.
                    val durablePort = rig.reconstructedPort()
                    assertHttp(durablePort)
                    published += keys to durablePort
                    advertiser.ensureStarted()
                }
                ServerSocket().use { occupiedDefault ->
                    occupiedDefault.bind(InetSocketAddress("127.0.0.1", 8888))
                    try {
                        advertiser.start("127.0.0.1")
                        assertAdvertised(name, 8888)
                        val started = runCatching { rig.server.start() }
                        assertTrue("The actual server must start after collision: ${started.exceptionOrNull()}", started.isSuccess)
                        val initialPort = rig.socketPort()
                        assertNotEquals(8888, initialPort)
                        assertHttp(initialPort)
                        assertEquals(initialPort, rig.reconstructedPort())
                        assertTrue("Initial startup must not rebuild its own owner", published.isEmpty())
                        // The service calls ensureStarted after server.start, consuming the persisted port.
                        advertiser.ensureStarted()
                        assertAdvertised(name, initialPort)

                        // IPv4-specific HTTP can coexist with this IPv6 socket. Opening HTTP to ::
                        // must collide with it and choose a new real connector during security rebind.
                        ServerSocket().use { occupiedLanPort ->
                            occupiedLanPort.bind(InetSocketAddress("::1", initialPort))
                            assertTrue(rig.config.setSecurityMode(SecurityMode.RELAXED))
                            assertTrue(rig.rebind())
                            val reboundPort = rig.socketPort()
                            assertNotEquals(initialPort, reboundPort)
                            assertHttp(reboundPort)
                            assertEquals(reboundPort, rig.reconstructedPort())
                            assertEquals(listOf(setOf("http_port") to reboundPort), published)
                            assertAdvertised(name, reboundPort)
                        }
                    } finally {
                        advertiser.stop()
                    }
                }
            }
        }

    @Test fun failedDurableChoiceFailsActualServerStartupWithoutPublication() =
        PaneldListenerPortTest.withDefaultPortLock {
            PortRig().use { rig ->
                val published = mutableListOf<Set<String>>()
                rig.onReconfigure(published::add)
                ServerSocket().use { occupiedDefault ->
                    occupiedDefault.bind(InetSocketAddress("127.0.0.1", 8888))
                    rig.rejectCommits()
                    val started = runCatching { rig.server.start() }
                    assertTrue("Failed persistence must fail server startup", started.exceptionOrNull() is IllegalStateException)
                    assertEquals(8888, rig.reconstructedPort())
                    assertTrue("A rejected port must never reach publication", published.isEmpty())
                    assertTrue("Failed startup must close upload admission", rig.fixture.pending.begin() is PendingUploadStore.BeginResult.Closed)
                }
            }
        }

    private class PortRig : AutoCloseable {
        val fixture = PaneldServerHttpFixture()
        val server = fixture.server
        private val database = File(fixture.directory, "port-state.db")
        private val persistence = JdbcStatePersistence(database)
        private val writer = Executors.newSingleThreadExecutor()
        private val prefs = SqliteStatePreferences(persistence, writer).also {
            check(it.edit().putString("panel_id", "port-publication")
                .putBoolean(Config.SECURITY_MODE_PREF, true)
                .putBoolean(Config.UI_ROUTES_CLOSED_PREF, true).commit())
        }
        val config = Config(prefs)
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        init {
            server.field("config", config)
            server.field("scope", scope)
            server.field("haArea", HaAreaRuntime(config, scope, Any()) { false })
            // This fixture drives the real listener rebind directly. Preference-event dispatch is
            // outside this callback proof; registration still executes through server.start.
            server.field("securityModeChanged", SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> })
        }
        fun onReconfigure(action: (Set<String>) -> Unit) = server.field("onReconfigure", action)
        fun reconstructedPort() = Config(SqliteStatePreferences(JdbcStatePersistence(database), writer)).httpPort
        fun rejectCommits() { persistence.failWrites = true }
        fun rebind(): Boolean {
            val worker = Executors.newSingleThreadExecutor()
            try {
                val result = runCatching {
                    worker.submit<Boolean> { listener().rebind() }.get(20, java.util.concurrent.TimeUnit.SECONDS)
                }
                if (result.isFailure) listener().stop()
                assertTrue("The server must finish rebind: ${result.exceptionOrNull()}", result.isSuccess)
                return result.getOrThrow()
            } finally { worker.shutdownNow() }
        }
        fun listener() = server.readField("listener") as PaneldListener
        fun socketPort() = listener().readField("boundPort") as Int
        override fun close() {
            (server.readField("listener") as? PaneldListener)?.stop()
            scope.cancel()
            writer.shutdownNow()
            fixture.close()
        }
    }

    private companion object {
        fun Any.field(name: String, value: Any) {
            javaClass.getDeclaredField(name).apply { isAccessible = true }.set(this, value)
        }
        fun Any.readField(name: String): Any? = javaClass.getDeclaredField(name).apply { isAccessible = true }.get(this)
        fun assertHttp(port: Int) {
            val connection = URL("http://127.0.0.1:$port/api/v1/radio").openConnection().apply {
                connectTimeout = 2_000
                readTimeout = 2_000
            } as java.net.HttpURLConnection
            try {
                assertEquals("The selected socket must serve the production retired-route response", 410, connection.responseCode)
                assertEquals(StubEndpoints.GONE_BODY, connection.errorStream.bufferedReader().use { it.readText() })
            } finally { connection.disconnect() }
        }
        fun assertAdvertised(name: String, expectedPort: Int) {
            JmDNS.create(InetAddress.getByName("127.0.0.1"), "port-publication-browser").use { browser ->
                val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(12)
                var observed: javax.jmdns.ServiceInfo? = null
                while (System.nanoTime() < deadline) {
                    observed = browser.list(Config.MDNS_SERVICE_TYPE, 1_000).firstOrNull { it.name == name && it.port == expectedPort }
                    if (observed != null) break
                }
                assertTrue("No advertisement for $name:$expectedPort", observed != null)
                assertEquals(listOf("127.0.0.1"), observed!!.inet4Addresses.map { it.hostAddress })
            }
        }
    }
}
