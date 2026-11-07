package io.panelassistant.android

import android.os.SystemClock
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import io.panelassistant.android.DashboardRendererRig.Companion.HA
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import kotlin.coroutines.CoroutineContext

/** Drive the activity's real network, frontend, reconcile and watchdog callers against one held page. */
class DashboardNetworkBlipTest {
    private var builds = 0
    private val rig = DashboardRendererRig(
        DashboardRendererRig.SIGNED_IN + mapOf("dashboard_entity_learning" to true, "device_local_setup_ever_completed" to true),
        entityGateScope = CoroutineScope(object : CoroutineDispatcher() {
            override fun dispatch(context: CoroutineContext, block: Runnable) { builds++ }
        }),
    )
    private val page = RecordingWebView("$HA/lovelace/0")
    private lateinit var session: ExternalBusController.Session
    private val generation get() = rig.get("rendererGeneration") as Long
    private val network = NetworkRecoveryGate(initiallyAvailable = true)
    private val navigation get() = page.calls.filter { it == "reload" || it == "loadData" || it == "destroy" || it.startsWith("loadUrl ") }

    @Before fun connect() {
        SystemClock.beginSensorTest()
        val paintReload: (AppCompatActivity, View?, String) -> View = { _, _, reason ->
            rig.reloadNotices += reason
            page.calls += "notice"
            DashboardRendererRig.allocate(View::class.java)
        }
        DashboardStatusScreens::class.java.getDeclaredField("paintReloadCard").apply { isAccessible = true }
            .set(rig.statusScreens, paintReload)
        rig.set("networkRecovery", network)
        rig.show(page)
        rig.phase = RendererPhase.LOADING
        rig.admitted()
        rig.set("builtAuthOwner", rig.config.haAuthSnapshot().stableOwner())
        rig.set("dashboardThemePolicy", rig.config.dashboardTheme)
        session = rig.bridge.beginDocument(page, rig.config, generation)
        event(ExternalBusProtocol.ConnectionEvent.CONNECTED)
        page.calls.clear()
    }

    @After fun release() {
        SystemClock.endSensorTest()
        RendererAdmissionRuntime.reset()
        rig.close()
    }

    private fun event(event: ExternalBusProtocol.ConnectionEvent) = rig.call("onConnectionStatus", event, generation, session)
    private fun drop() {
        network.onLost()
        event(ExternalBusProtocol.ConnectionEvent.DISCONNECTED)
    }
    private fun regain() = rig.call("onNetworkAvailable")

    @Test fun aTwoSecondDropLetsTheFrontendReconnectInTheSameDocument() {
        drop()
        SystemClock.advanceSensorTest(2_000)
        regain()
        assertEquals("regaining Android's network keeps the HA page", emptyList<String>(), navigation)
        event(ExternalBusProtocol.ConnectionEvent.CONNECTED)
        SystemClock.advanceSensorTest(120_000)
        assertEquals("the reconnect cancelled recovery", emptyList<String>(), navigation)
        assertEquals(RendererPhase.CONNECTED, rig.phase)
        assertEquals("no fallback notice for an in-place reconnect", emptyList<String>(), rig.reloadNotices)
    }

    @Test fun aReturningNetworkWithoutAFrontendReconnectRecoversOnceAfterTheGrace() {
        drop()
        SystemClock.advanceSensorTest(2_000)
        regain()
        assertEquals("network return alone does not recover the page", emptyList<String>(), navigation)
        SystemClock.advanceSensorTest(87_000)
        assertEquals("the existing 90-second grace survives network regain", emptyList<String>(), navigation)
        SystemClock.advanceSensorTest(1_100)
        assertEquals("one bounded recovery", listOf("reload"), navigation)
        assertEquals("fallback uses the existing reload Notice", listOf("reconnecting to Home Assistant"), rig.reloadNotices)
        assertTrue("Notice precedes navigation", page.calls.indexOf("notice") < page.calls.indexOf("reload"))
        regain()
        assertEquals("duplicate availability adds no recovery", listOf("reload"), navigation)
        val notice = DashboardStatusScreens::class.java.getDeclaredField("reloadNotice").apply { isAccessible = true }
        assertNotNull(notice.get(rig.statusScreens))
        session = rig.bridge.beginDocument(page, rig.config, generation)
        event(ExternalBusProtocol.ConnectionEvent.CONNECTED)
        assertNull("the replacement frontend clears the recovery Notice", notice.get(rig.statusScreens))
    }

    @Test fun repeatedFrontendDisconnectsCannotShortenTheReconnectGrace() {
        drop()
        SystemClock.advanceSensorTest(2_000)
        event(ExternalBusProtocol.ConnectionEvent.DISCONNECTED)
        SystemClock.advanceSensorTest(10_000)
        assertEquals("duplicate frontend events retain the same page", emptyList<String>(), navigation)
        SystemClock.advanceSensorTest(77_000)
        assertEquals(emptyList<String>(), navigation)
        SystemClock.advanceSensorTest(1_100)
        assertEquals("duplicates do not extend the grace", listOf("reload"), navigation)
    }

    @Test fun aRouteEpochChangeStillRebuildsOnceInsteadOfWaitingForTheOldPage() {
        drop()
        val oldRoute = HaConnectionRoutes.current(rig.config)
        assertTrue(HaConnectionRoutes.learn(rig.config, oldRoute,
            HaConnectionAdvertisement("a".repeat(32), "b".repeat(32), listOf(HA))))
        assertNotEquals("authenticated route learning retires the old authority", oldRoute, HaConnectionRoutes.current(rig.config))
        // Deliver the epoch recorded by the Android preference listener into its serialized reconcile.
        rig.set("routeChangePending", true)
        rig.relaunch(null)
        assertEquals("route recovery retires the old renderer once", 1, navigation.count { it == "destroy" })
        assertEquals("route recovery starts one replacement", 1, builds)
        assertEquals("route recovery announces before replacing the document", listOf("reconnecting to Home Assistant"), rig.reloadNotices)
        assertTrue(page.calls.indexOf("notice") < page.calls.indexOf("destroy"))
        SystemClock.advanceSensorTest(100_000)
        assertEquals("the retired watchdog cannot reload the old page", 0, navigation.count { it == "reload" })
    }
    @Test fun anAlreadyBrokenPageRecoversImmediatelyWhenTheNetworkReturns() {
        drop()
        rig.phase = RendererPhase.RECONNECTING
        (rig.get("shownPage") as ShownPageTracker).forget()
        regain()
        assertEquals(listOf("loadUrl $HA/lovelace?external_auth=1"), navigation)
        assertEquals(listOf("reconnecting to Home Assistant"), rig.reloadNotices)
    }

    @Test fun aDarkPageWaitsForWakeRatherThanReloadingOnNetworkRegain() {
        rig.setPaced("screenAwake", false)
        drop()
        SystemClock.advanceSensorTest(2_000)
        regain()
        SystemClock.advanceSensorTest(120_000)
        assertEquals(emptyList<String>(), navigation)
        assertEquals(emptyList<String>(), rig.reloadNotices)
    }

    @Test fun aChangedEndpointRecoversOnceWithTheNotice() {
        drop()
        assertTrue(rig.config.setHaConnection("http://ha-new.local:8123", null))
        rig.relaunch(null)
        assertEquals(1, navigation.count { it == "destroy" })
        assertEquals(1, builds)
        assertEquals(listOf("reconnecting to Home Assistant"), rig.reloadNotices)
        SystemClock.advanceSensorTest(100_000)
        assertEquals(0, navigation.count { it == "reload" })
    }

}
