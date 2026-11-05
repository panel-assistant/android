package io.panelassistant.android.http

import com.sun.net.httpserver.HttpServer
import io.panelassistant.android.Config
import io.panelassistant.android.fakePreferences
import io.panelassistant.android.platform.LocalRpcVendorScreen
import io.panelassistant.android.platform.ActivityRef
import io.panelassistant.android.preservePreviousHome
import io.panelassistant.android.control.HandBackHomeController
import io.panelassistant.android.control.HandBackHomePolicy
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import java.net.InetSocketAddress
import java.net.URLDecoder
import org.junit.Assert.*
import org.junit.Test

/** The runtime RPC and the real removal route share durable custody across reconstructed callers. */
class VendorBrightnessRemovalTest {
    private class Vendor(initial: Boolean) : AutoCloseable {
        var auto = initial
        var unreadable = false
        var refuse = false
        var ignore = false
        var beforeWrite: (Boolean) -> Unit = {}
        val writes = mutableListOf<Boolean>()
        val requests = java.util.concurrent.Executors.newCachedThreadPool()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            executor = requests
            createContext("/rpc/") { exchange ->
                val body = if (exchange.requestURI.path.endsWith("Ui.GetConfig")) {
                    if (unreadable) """{"brightness":{"auto":"false"}}""" else """{"brightness":{"auto":$auto}}"""
                } else {
                    val decoded = URLDecoder.decode(exchange.requestURI.rawQuery, "UTF-8")
                    val value = decoded.contains("true")
                    beforeWrite(value)
                    writes += value
                    if (!refuse && !ignore) auto = value
                    if (refuse) """{"code":403}""" else "{}"
                }
                val bytes = body.toByteArray()
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
            start()
        }
        fun screen(config: Config) = LocalRpcVendorScreen(server.address.port, config.vendorBrightnessCustody())
        override fun close() { server.stop(0); requests.shutdownNow() }
    }

    private fun remove(screen: LocalRpcVendorScreen): Pair<HttpStatusCode, String> {
        var answer: Pair<HttpStatusCode, String>? = null
        testApplication {
            application {
                routing {
                    handBackHomeRoutes(HandBackHomeRouteDependencies(
                        profileKnownPackages = { emptySet() },
                        handBack = { HandBackHomeController.Result.Done(HandBackHomePolicy.Outcome(
                            restored = emptyList(), adopted = emptyList(), outstanding = emptyList(),
                            homeHandedTo = "cloud.shelly.stargate",
                        )) },
                        recordTamed = { HandBackHomePolicy.RecordOutcome.UNKNOWN },
                        restoreVendorBrightness = screen::restoreBrightness,
                    ))
                }
            }
            val response = client.post("/api/v1/hand-back-home")
            answer = response.status to response.bodyAsText()
        }
        return answer!!
    }

    @Test fun `true and false originals survive repeated takeover restart and removal`() {
        for (original in listOf(true, false)) Vendor(original).use { vendor ->
            val storage = fakePreferences()
            val firstCaller = Config(storage.instance)
            firstCaller.initializeVendorBrightnessCustody()
            assertTrue(firstCaller.preservePreviousHome(
                ActivityRef("cloud.shelly.stargate", "cloud.shelly.stargate.Home"),
                listOf(ActivityRef("cloud.shelly.stargate", "cloud.shelly.stargate.Home")), armTakeover = true,
            ))
            val config = Config(storage.instance)
            assertTrue(vendor.screen(config).holdBrightness())
            assertFalse(vendor.auto)
            val restarted = Config(storage.instance)
            assertTrue(vendor.screen(restarted).holdBrightness())
            assertTrue(restarted.clearHandBackDesiredState())
            repeat(2) {
                val (status, body) = remove(vendor.screen(restarted))
                assertEquals(HttpStatusCode.OK, status)
                assertTrue(body, body.contains("\"vendor_brightness\":\"restored\""))
                assertEquals(original, vendor.auto)
                assertFalse("removal fences subsequent runtime reconciliation", vendor.screen(Config(storage.instance)).holdBrightness())
            }
            // A fresh explicit installer takeover may recapture the owner's current preference.
            vendor.auto = !original
            assertTrue(restarted.preservePreviousHome(
                ActivityRef("cloud.shelly.stargate", "cloud.shelly.stargate.Home"),
                listOf(ActivityRef("cloud.shelly.stargate", "cloud.shelly.stargate.Home")), armTakeover = true,
            ))
            assertTrue(vendor.screen(restarted).holdBrightness())
            assertEquals(HttpStatusCode.OK, remove(vendor.screen(restarted)).first)
            assertEquals(!original, vendor.auto)
        }
    }

    @Test fun `unreadable or unrecordable original is never changed and retries capture`() {
        Vendor(true).use { vendor ->
            val storage = fakePreferences()
            val screen = vendor.screen(Config(storage.instance))
            vendor.unreadable = true
            assertFalse(screen.holdBrightness())
            assertTrue(vendor.writes.isEmpty())
            vendor.unreadable = false
            storage.commitsSucceed.set(false)
            assertFalse(screen.holdBrightness())
            assertTrue(vendor.writes.isEmpty())
            storage.commitsSucceed.set(true)
            assertTrue(screen.holdBrightness())
            assertEquals(HttpStatusCode.OK, remove(screen).first)
            assertTrue(vendor.auto)
        }
    }

    @Test fun `failed restore and incorrect readback refuse removal preserve original and fence retries`() {
        Vendor(true).use { vendor ->
            val storage = fakePreferences()
            val screen = vendor.screen(Config(storage.instance))
            assertTrue(screen.holdBrightness())
            storage.commitsSucceed.set(false)
            assertEquals(HttpStatusCode.Conflict, remove(screen).first)
            assertFalse(vendor.auto)
            storage.commitsSucceed.set(true)
            vendor.refuse = true
            val (status, body) = remove(screen)
            assertEquals(HttpStatusCode.Conflict, status)
            assertTrue(body.contains("vendor-brightness-unrestored"))
            assertFalse(vendor.screen(Config(storage.instance)).holdBrightness())
            vendor.refuse = false
            vendor.ignore = true
            assertEquals(HttpStatusCode.Conflict, remove(screen).first)
            vendor.ignore = false
            assertEquals(HttpStatusCode.OK, remove(vendor.screen(Config(storage.instance))).first)
            assertTrue(vendor.auto)
        }
    }

    @Test fun `restoration excludes an admitted concurrent runtime reassertion`() {
        Vendor(true).use { vendor ->
            val storage = fakePreferences()
            val screen = vendor.screen(Config(storage.instance))
            assertTrue(screen.holdBrightness())
            val entered = java.util.concurrent.CountDownLatch(1)
            val release = java.util.concurrent.CountDownLatch(1)
            vendor.beforeWrite = { value ->
                if (!value) {
                    entered.countDown()
                    check(release.await(5, java.util.concurrent.TimeUnit.SECONDS))
                }
            }
            val pool = java.util.concurrent.Executors.newFixedThreadPool(2)
            try {
                val runtime = pool.submit<Boolean> { vendor.screen(Config(storage.instance)).holdBrightness() }
                assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS))
                val restoring = java.util.concurrent.CountDownLatch(1)
                val restore = pool.submit<String> { restoring.countDown(); screen.restoreBrightness() }
                assertTrue(restoring.await(5, java.util.concurrent.TimeUnit.SECONDS))
                try {
                    restore.get(200, java.util.concurrent.TimeUnit.MILLISECONDS)
                    fail("restoration must wait for the admitted runtime write")
                } catch (_: java.util.concurrent.TimeoutException) { }
                release.countDown()
                assertTrue(runtime.get(5, java.util.concurrent.TimeUnit.SECONDS))
                assertEquals("restored", restore.get(5, java.util.concurrent.TimeUnit.SECONDS))
                assertTrue(vendor.auto)
                assertEquals(listOf(false, false, true), vendor.writes)
            } finally {
                release.countDown()
                pool.shutdownNow()
            }
        }
    }

    @Test fun `original vendor custody is withheld from same and other panel restores`() {
        val rows = listOf(io.panelassistant.android.persistence.ConfigVault.StateRow(
            "config", "device_local_vendor_brightness_custody", "string", "active:true", 1L,
        ))
        for (samePanel in listOf(true, false)) {
            assertTrue(io.panelassistant.android.persistence.StateBackupPolicy.restorableRows(rows, samePanel).isEmpty())
        }
    }

    @Test fun `profile without vendor route cannot discard existing custody`() {
        val storage = fakePreferences(mapOf("device_local_vendor_brightness_custody" to "active:true"))
        val dependencies = handBackHomeDependencies(
            config = Config(storage.instance),
            tameController = { error("restoration does not need the HOME controller") },
            profileKnownPackages = { emptySet() },
            setHome = { true },
            ownPackage = { "io.panelassistant.android" },
            authorize = { _, _, _, _ -> true },
        )
        assertEquals("unrestored", dependencies.restoreVendorBrightness())
        assertEquals("active:true", storage.instance.getString("device_local_vendor_brightness_custody", null))
    }

    @Test fun `existing non vendor configuration can still be removed without inventing a baseline`() {
        val storage = fakePreferences(mapOf("panel_id" to "existing"))
        val config = Config(storage.instance)
        config.initializeVendorBrightnessCustody()
        val dependencies = handBackHomeDependencies(
            config = config,
            tameController = { error("unused") },
            profileKnownPackages = { emptySet() },
            setHome = { true }, ownPackage = { "io.panelassistant.android" },
            authorize = { _, _, _, _ -> true },
        )
        assertEquals("unknown", dependencies.restoreVendorBrightness())
        assertEquals("restoring:unknown", config.vendorBrightnessCustody().read())
    }

    @Test fun `missing historical baseline is explicitly unknown and never guessed`() {
        Vendor(false).use { vendor ->
            val storage = fakePreferences(mapOf("panel_id" to "existing"))
            val screen = vendor.screen(Config(storage.instance))
            assertTrue(screen.holdBrightness())
            val writes = vendor.writes.size
            val (status, body) = remove(screen)
            assertEquals(HttpStatusCode.OK, status)
            assertTrue(body.contains("\"vendor_brightness\":\"unknown\""))
            assertFalse(vendor.auto)
            assertEquals(writes, vendor.writes.size)
            assertFalse(vendor.screen(Config(storage.instance)).holdBrightness())
        }
    }
}
