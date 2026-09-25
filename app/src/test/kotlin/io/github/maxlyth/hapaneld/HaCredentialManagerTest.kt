package io.github.maxlyth.hapaneld

import android.content.SharedPreferences
import com.sun.net.httpserver.HttpServer
import io.github.maxlyth.hapaneld.sensors.DashboardHaApiSessionProvider
import java.lang.reflect.Proxy
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every Home Assistant consumer takes its credential from one authority. These run the real refresh
 * request against a loopback `/auth/token` and count what reaches it, so a second request, a second
 * answer or a repair loop shows up as traffic the server saw.
 */
class HaCredentialManagerTest {
    private val now = System.currentTimeMillis() / 1000
    private val ha = LoopbackHa()

    @After fun stop() = ha.close()

    @Test fun concurrentRendererAndNativeDemandShareOneRefreshAndOneAnswer() {
        val (config, values) = config(refresh = "refresh-shared")
        ha.hold()
        val renderer = Caller { HaCredentialManager.resolve(config, nowSec = now) }
        ha.awaitRequests(1)
        // The native side forces, as a socket does after its token is refused.
        val native = Caller { DashboardHaApiSessionProvider(config).resolve(force = true) }
        ha.awaitSecondRequestOrParked(native.thread)
        ha.answer(200, """{"access_token":"fresh","expires_in":1800,"token_type":"Bearer"}""")

        val rendered = renderer.result()
        val session = native.result()
        assertEquals("one refresh for both consumers", 1, ha.requests.get())
        assertEquals(DashboardAuth.Session("fresh", 1800), rendered.session)
        assertEquals("fresh", session.accessToken)
        assertFalse(session.notAttempted)
        assertTrue("the shared token belongs to the current credential", session.owner != null)
        assertEquals("fresh", values["ha_token"])
    }

    @Test fun revocationIsOneRefusalSharedByEveryConnectionUntilTheCredentialChanges() {
        val (config, values) = config(refresh = "refresh-revoked")
        ha.hold()
        val first = Caller { HaCredentialManager.resolve(config, nowSec = now, force = true) }
        ha.awaitRequests(1)
        val second = Caller { DashboardHaApiSessionProvider(config).resolve(force = true) }
        ha.awaitSecondRequestOrParked(second.thread)
        ha.answer(400, """{"error":"invalid_grant"}""")

        assertTrue(first.result().rejected)
        assertTrue(second.result().rejected)
        // Each connection fails on its own schedule and asks again; none puts the refused
        // credential back to Home Assistant.
        repeat(3) {
            assertTrue(HaCredentialManager.resolve(config, nowSec = now).rejected)
            assertTrue(DashboardHaApiSessionProvider(config).resolve(force = true).rejected)
        }
        assertEquals("a refused refresh token is asked about once", 1, ha.requests.get())

        // A new sign-in is a new generation: it is asked about, and answered.
        values["ha_refresh_token"] = "refresh-after-sign-in"
        ha.answer(200, """{"access_token":"after","expires_in":1800}""")
        assertEquals("after", HaCredentialManager.resolve(config, nowSec = now).session?.accessToken)
        assertEquals(2, ha.requests.get())
    }

    @Test fun anOperatorRetryAsksHomeAssistantAgain() {
        val (config, _) = config(refresh = "refresh-retry")
        ha.answer(403, """{"error":"access_denied"}""")
        assertTrue(HaCredentialManager.resolve(config, nowSec = now).rejected)
        assertTrue(HaCredentialManager.resolve(config, nowSec = now).rejected)
        assertEquals(1, ha.requests.get())

        HaCredentialManager.forgetRefusal()
        ha.answer(200, """{"access_token":"reactivated","expires_in":1800}""")
        assertEquals("reactivated", HaCredentialManager.resolve(config, nowSec = now).session?.accessToken)
        assertEquals(2, ha.requests.get())
    }

    @Test fun aCompletionForAReplacedCredentialIsNeitherStoredNorHandedOut() {
        listOf("ha_url", "ha_refresh_token", "ha_client_id").forEach { field ->
            val (config, values) = config(refresh = "refresh-stale-$field")
            val before = HashMap(values)
            ha.hold()
            val first = Caller { HaCredentialManager.resolve(config, nowSec = now) }
            ha.awaitRequests(1)
            val second = Caller { DashboardHaApiSessionProvider(config).resolve(force = false) }
            ha.awaitSecondRequestOrParked(second.thread)
            values[field] = if (field == "ha_url") "http://127.0.0.1:1" else "replaced-$field"
            ha.answer(200, """{"access_token":"stale","expires_in":1800}""")

            val a = first.result()
            val b = second.result()
            assertNull(field, a.session)
            assertTrue("$field: nothing was judged for the new credential", a.notAttempted)
            assertFalse(field, a.rejected)
            assertNull(field, b.accessToken)
            assertTrue(field, b.notAttempted)
            assertEquals("$field: the stale token was not stored", before["ha_token"], values["ha_token"])
            assertEquals(field, before["ha_token_expiry"], values["ha_token_expiry"])
            ha.reset()
        }
    }

    @Test fun aRefusalOfAReplacedCredentialIsNotRememberedForItsReplacement() {
        val (config, values) = config(refresh = "refresh-replaced-refused")
        ha.hold()
        val caller = Caller { HaCredentialManager.resolve(config, nowSec = now) }
        ha.awaitRequests(1)
        values["ha_refresh_token"] = "refresh-replacement"
        ha.answer(400, """{"error":"invalid_grant"}""")
        assertTrue(caller.result().notAttempted)

        ha.answer(200, """{"access_token":"replacement","expires_in":1800}""")
        assertEquals("replacement", HaCredentialManager.resolve(config, nowSec = now).session?.accessToken)
        assertEquals(2, ha.requests.get())
    }

    @Test fun aRefreshDoesNotLookLikeACredentialChangeToLiveConnections() {
        val (config, _) = config(refresh = "refresh-live")
        val before = config.haAuthSnapshot()
        ha.answer(200, """{"access_token":"rotated","expires_in":1800}""")
        assertEquals("rotated", HaCredentialManager.resolve(config, nowSec = now).session?.accessToken)
        val after = config.haAuthSnapshot()
        assertNotEquals("the refresh was stored", before.accessToken, after.accessToken)

        val projection = MqttProjectionIdentity("maker", "model", emptyList())
        assertEquals(
            "no HA-side effect or ambient-source restart follows another consumer's refresh",
            ConfigRefreshEffects(reannounceMqtt = false, resolveHaLink = false),
            configRefreshEffects(projection, projection, before.stableOwner(), after.stableOwner()),
        )
        val snapshot = NetworkConfigurationSnapshot(
            NetworkRuntimeIdentity("panel", "Panel", 8_888, "tcp://broker:1883", "user", "secret"),
            projection,
            after.stableOwner(),
        )
        assertFalse("the credential is never printed", snapshot.toString().contains("refresh-live"))
    }

    @Test fun afterRevocationEvenAnUnexpiredLookingTokenIsNotHandedOut() {
        // Revoking the refresh token kills every access token derived from it, whatever its clock says.
        val (config, _) = config(refresh = "refresh-revoked-live", access = "looks-alive", expiry = now + 3_600)
        ha.answer(400, """{"error":"invalid_grant"}""")
        assertTrue(HaCredentialManager.resolve(config, nowSec = now, force = true).rejected)
        val renderer = HaCredentialManager.resolve(config, nowSec = now)
        assertNull(renderer.session)
        assertTrue(renderer.rejected)
        assertTrue(DashboardHaApiSessionProvider(config).resolve(force = false).rejected)
        assertEquals(1, ha.requests.get())
    }

    @Test fun aRefreshThatCannotBeStoredCarriesNoAuthenticationVerdict() {
        val (config, values) = config(refresh = "refresh-unstorable", commits = false)
        ha.answer(200, """{"access_token":"unstored","expires_in":1800}""")
        val result = HaCredentialManager.resolve(config, nowSec = now)
        assertNull(result.session)
        assertTrue(result.notAttempted)
        assertFalse(result.rejected)
        assertEquals("expired", values["ha_token"])
    }

    @Test fun aStaticTokenNeverReachesTheRefreshPath() {
        val (config, _) = config(refresh = "", access = "long-lived", expiry = 0L)
        val callers = List(4) { i ->
            Caller { HaCredentialManager.resolve(config, nowSec = now, force = i % 2 == 0) }
        }
        callers.forEach {
            assertEquals(DashboardAuth.Session("long-lived", DashboardAuth.STATIC_TTL_SEC), it.result().session)
        }
        assertEquals(0, ha.requests.get())
    }

    private fun config(
        refresh: String,
        access: String = "expired",
        expiry: Long = now - 10,
        commits: Boolean = true,
    ): Pair<Config, MutableMap<String, Any>> {
        val values = ConcurrentHashMap<String, Any>(
            mapOf(
                "ha_url" to ha.url,
                "ha_token" to access,
                "ha_refresh_token" to refresh,
                "ha_token_expiry" to expiry,
                "ha_client_id" to "client",
            ),
        )
        return Config(preferences(values, commits)) to values
    }

    private class Caller<T>(block: () -> T) {
        @Volatile private var value: Result<T>? = null
        val thread = Thread { value = runCatching(block) }.apply { isDaemon = true; start() }
        fun result(): T {
            thread.join(20_000)
            return checkNotNull(value) { "caller did not finish" }.getOrThrow()
        }
    }

    /** Home Assistant's `/auth/token`, answering from a script the test controls. */
    private class LoopbackHa : AutoCloseable {
        val requests = AtomicInteger()
        @Volatile private var gate = CountDownLatch(0)
        @Volatile private var status = 500
        @Volatile private var body = "{}"
        private val pool = Executors.newCachedThreadPool()
        private val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 16).apply {
            executor = pool
            createContext("/auth/token") { exchange ->
                requests.incrementAndGet()
                exchange.requestBody.readBytes()
                gate.await(20, TimeUnit.SECONDS)
                val bytes = body.toByteArray()
                exchange.sendResponseHeaders(status, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
            start()
        }
        val url = "http://127.0.0.1:${server.address.port}"

        fun hold() { gate = CountDownLatch(1) }

        fun answer(code: Int, json: String) {
            status = code
            body = json
            gate.countDown()
        }

        fun reset() {
            requests.set(0)
            gate = CountDownLatch(0)
        }

        fun awaitRequests(count: Int) {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            while (requests.get() < count) {
                check(System.nanoTime() < deadline) { "Home Assistant saw ${requests.get()} of $count requests" }
                Thread.sleep(5)
            }
        }

        /** Until [caller] either sends its own request or is parked waiting on the one in flight. */
        fun awaitSecondRequestOrParked(caller: Thread) {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            while (requests.get() < 2 && caller.state != Thread.State.TIMED_WAITING &&
                caller.state != Thread.State.WAITING
            ) {
                check(System.nanoTime() < deadline) { "second caller neither asked nor waited" }
                Thread.sleep(5)
            }
        }

        override fun close() {
            gate.countDown()
            server.stop(0)
            pool.shutdownNow()
        }
    }

    private fun preferences(values: MutableMap<String, Any>, commits: Boolean): SharedPreferences = Proxy.newProxyInstance(
        SharedPreferences::class.java.classLoader,
        arrayOf(SharedPreferences::class.java),
    ) { _, method, args ->
        when (method.name) {
            "getAll" -> HashMap(values)
            "getString" -> values[args!![0]] as? String ?: args[1]
            "getStringSet" -> values[args!![0]] as? Set<*> ?: args[1]
            "getInt" -> values[args!![0]] as? Int ?: args[1]
            "getLong" -> values[args!![0]] as? Long ?: args[1]
            "getFloat" -> values[args!![0]] as? Float ?: args[1]
            "getBoolean" -> values[args!![0]] as? Boolean ?: args[1]
            "contains" -> values.containsKey(args!![0])
            "edit" -> editor(values, commits)
            "registerOnSharedPreferenceChangeListener", "unregisterOnSharedPreferenceChangeListener" -> null
            "toString" -> "LoopbackPreferences"
            else -> error("unexpected SharedPreferences call: ${method.name}")
        }
    } as SharedPreferences

    private fun editor(values: MutableMap<String, Any>, commits: Boolean): SharedPreferences.Editor {
        val writes = LinkedHashMap<String, Any?>()
        lateinit var editor: SharedPreferences.Editor
        editor = Proxy.newProxyInstance(
            SharedPreferences.Editor::class.java.classLoader,
            arrayOf(SharedPreferences.Editor::class.java),
        ) { _, method, args ->
            when {
                method.name.startsWith("put") -> editor.also { writes[args!![0] as String] = args[1] }
                method.name == "remove" -> editor.also { writes[args!![0] as String] = null }
                method.name == "commit" || method.name == "apply" -> {
                    if (commits) {
                        writes.forEach { (key, value) -> if (value == null) values.remove(key) else values[key] = value }
                    }
                    if (method.name == "commit") commits else null
                }
                method.name == "toString" -> "LoopbackEditor"
                else -> error("unexpected Editor call: ${method.name}")
            }
        } as SharedPreferences.Editor
        return editor
    }
}
