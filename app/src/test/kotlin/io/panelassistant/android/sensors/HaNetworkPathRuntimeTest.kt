package io.panelassistant.android.sensors

import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The coordinator, the process-global holder and the projections every surface renders from.
 *
 * Every surface is fed from ONE injected snapshot here, which is the behavioural half of the
 * one-owner proof; the source-text half lives in `HaNetworkPathSurfaceContractTest`.
 */
class HaNetworkPathRuntimeTest {
    private var now = 0L
    private var restarting = false
    private val pokes = mutableListOf<Triple<Boolean, Boolean, HaNetworkPathSeverity>>()
    private val monitor = HaNetworkPathMonitor(
        nowMs = { now },
        haRestarting = { restarting },
        onChanged = { pokes += HaNetworkPathRuntime.snapshot()!!.reportableKey },
    )

    @After fun uninstall() {
        HaNetworkPathRuntime.uninstall(monitor)
    }

    private fun installed(): HaNetworkPathMonitor {
        HaNetworkPathRuntime.install(monitor)
        return monitor
    }

    @Test fun anUninstalledHolderAnswersNullAndTheHonestDefaults() {
        assertNull(HaNetworkPathRuntime.snapshot())
        assertNull(HaNetworkPathRuntime.statusText())
        assertEquals("", HaNetworkPathRuntime.healthToken())
        assertEquals("[ha-network] state=unowned", HaNetworkPathRuntime.diagnosticLine())
        val json = JSONObject(HaNetworkPathRuntime.statusJson())
        assertFalse(json.getBoolean("measuring"))
        assertEquals("idle", json.getString("state"))
    }

    @Test fun theMonitorPokesOnlyWhenTheVerdictChanges() {
        val m = installed()
        m.onSocketState(HaSocketState.LIVE)
        assertEquals(listOf(Triple(true, false, HaNetworkPathSeverity.HEALTHY)), pokes)
        now += 10_000L; m.onRoundTrip(8L)
        now += 10_000L; m.onRoundTrip(9L)
        assertEquals(1, pokes.size)
        now += 10_000L; m.onProbeTimeout()
        assertEquals(1, pokes.size)
        now += 15_000L; m.onProbeTimeout()
        assertEquals(listOf(
                Triple(true, false, HaNetworkPathSeverity.HEALTHY),
                Triple(true, false, HaNetworkPathSeverity.SEVERE),
            ),
            pokes)
        now += 1_000L; m.onConnectionFailure(HaPathFailureKind.NETWORK)
        assertEquals(2, pokes.size)
        m.onSocketState(HaSocketState.STOPPED)
        assertEquals(Triple(false, false, HaNetworkPathSeverity.HEALTHY), pokes.last())
        assertEquals(3, pokes.size)
    }

    @Test fun measurementIsOwnedByAnAuthenticatedSocketNotByDemandAndEndsWhenTheStreamParks() {
        val m = installed()
        // Demand alone: the stream is authenticating and has never been live, so there is no path to
        // describe and every surface omits it rather than reporting an empty healthy window.
        m.onSocketState(HaSocketState.CONNECTING)
        val connecting = HaNetworkPathRuntime.snapshot()!!
        assertFalse(connecting.measuring)
        assertFalse(connecting.socketLive)
        assertEquals("", HaNetworkPathRuntime.healthToken())
        assertEquals(HaNetworkPathPresentation.NOT_MEASURED, HaNetworkPathRuntime.statusText())
        assertEquals("[ha-network] state=idle measuring=false", HaNetworkPathRuntime.diagnosticLine())
        // Authenticated: measurement starts.
        now += 1_000L; m.onSocketState(HaSocketState.LIVE)
        now += 10_000L; m.onRoundTrip(12L)
        val live = HaNetworkPathRuntime.snapshot()!!
        assertTrue(live.measuring)
        assertTrue(live.socketLive)
        assertTrue(HaNetworkPathRuntime.diagnosticLine().contains("socket=live"))
        // The socket drops and is being re-established. Measurement CONTINUES — this is exactly when
        // a broken path must still be reported — and the evidence is kept, but the surfaces say the
        // socket is being re-established rather than claiming it is up.
        now += 5_000L; m.onSocketState(HaSocketState.CONNECTING)
        now += 1_000L; m.onConnectionFailure(HaPathFailureKind.NETWORK)
        now += 2_000L; m.onConnectionFailure(HaPathFailureKind.NETWORK)
        val reconnecting = HaNetworkPathRuntime.snapshot()!!
        assertTrue("a broken path must stay reportable while reconnecting", reconnecting.measuring)
        assertFalse(reconnecting.socketLive)
        assertEquals(HaNetworkPathSeverity.SEVERE, reconnecting.severity)
        assertEquals(1, reconnecting.roundTrips)
        assertTrue(HaNetworkPathRuntime.diagnosticLine().contains("socket=reconnecting"))
        assertTrue(HaNetworkPathRuntime.healthToken().contains("ha_net=severe"))
        // The stream parks: no probe will ever follow, so the verdict must not be left standing.
        now += 1_000L; m.onSocketState(HaSocketState.STOPPED)
        val parked = HaNetworkPathRuntime.snapshot()!!
        assertFalse(parked.measuring)
        assertEquals(0, parked.probes)
        assertEquals(0, parked.consecutiveFailures)
        assertEquals("", HaNetworkPathRuntime.healthToken())
        assertEquals(HaNetworkPathPresentation.NOT_MEASURED, HaNetworkPathRuntime.statusText())
    }

    @Test fun aTimeOnlyRecoveryIsPokedFromTheReadThatObservesItExactlyOnce() {
        // Recovery is samples ageing out, which is time passing rather than an event. The polled web
        // surfaces re-render from every read; the native chip is poke-driven, so without a poke here
        // it would keep showing the panel chip after the web page had already recovered.
        val m = installed()
        m.onSocketState(HaSocketState.LIVE)
        now += 1_000L; m.onProbeTimeout()
        now += 1_000L; m.onProbeTimeout()
        assertEquals(HaNetworkPathSeverity.SEVERE, HaNetworkPathRuntime.snapshot()!!.severity)
        val pokesBefore = pokes.size
        // Still inside the window: a read changes nothing and pokes nothing.
        now += HaNetworkPath.WINDOW_MS - 1_000L
        assertEquals(HaNetworkPathSeverity.SEVERE, HaNetworkPathRuntime.snapshot()!!.severity)
        assertEquals(pokesBefore, pokes.size)
        // Both failures have now aged out: the reading itself recovers the verdict and pokes once.
        now += 2_001L
        assertEquals(HaNetworkPathSeverity.HEALTHY, HaNetworkPathRuntime.snapshot()!!.severity)
        assertEquals(pokesBefore + 1, pokes.size)
        assertEquals(Triple(true, false, HaNetworkPathSeverity.HEALTHY), pokes.last())
        // Idempotent: further reads of the same state poke nothing, so the redraw cannot loop.
        repeat(3) { HaNetworkPathRuntime.snapshot() }
        assertEquals(pokesBefore + 1, pokes.size)
    }

    @Test fun aNetworkMissDuringAnAnnouncedHomeAssistantRestartIsTheServersNotThePaths() {
        val m = installed()
        m.onSocketState(HaSocketState.LIVE)
        restarting = true
        now += 10_000L; m.onProbeTimeout()
        now += 15_000L; m.onConnectionFailure(HaPathFailureKind.NETWORK)
        val snap = HaNetworkPathRuntime.snapshot()!!
        assertEquals(0, snap.networkFailures)
        assertEquals(2, snap.serverFailures)
        assertEquals(HaNetworkPathSeverity.HEALTHY, snap.severity)
        // Once the announcement is over, the same event is loss again.
        restarting = false
        now += 15_000L; m.onProbeTimeout()
        now += 15_000L; m.onProbeTimeout()
        assertEquals(HaNetworkPathSeverity.SEVERE, HaNetworkPathRuntime.snapshot()!!.severity)
    }

    @Test fun anAuthOrServerFailureIsNeverRelabelledByTheRestartSignal() {
        val m = installed()
        m.onSocketState(HaSocketState.LIVE)
        restarting = true
        m.onConnectionFailure(HaPathFailureKind.AUTH)
        m.onConnectionFailure(HaPathFailureKind.SERVER)
        val snap = HaNetworkPathRuntime.snapshot()!!
        assertEquals(1, snap.authFailures)
        assertEquals(1, snap.serverFailures)
        assertEquals(0, snap.networkFailures)
    }

    @Test fun aSupersededOwnerCannotUninstallItsSuccessorOrAnswerForIt() {
        val successor = HaNetworkPathMonitor(nowMs = { now })
        HaNetworkPathRuntime.install(monitor)
        HaNetworkPathRuntime.install(successor)
        assertFalse(HaNetworkPathRuntime.uninstall(monitor))
        successor.onSocketState(HaSocketState.LIVE)
        assertTrue(HaNetworkPathRuntime.snapshot()!!.measuring)
        assertTrue(HaNetworkPathRuntime.uninstall(successor))
        assertNull(HaNetworkPathRuntime.snapshot())
    }

    // ---- projections: one snapshot in, every surface out ----

    private fun degraded(severity: HaNetworkPathSeverity, misses: Int, p95: Long): HaNetworkPath.Snapshot =
        HaNetworkPath.Snapshot(
            measuring = true, settling = false, socketLive = true, severity = severity,
            cause = if (misses > 0) PathProbeCause.LOSS else PathProbeCause.NONE,
            responsiveness = severity, windowMs = HaNetworkPath.WINDOW_MS, probes = 30,
            roundTrips = 30 - misses, networkFailures = misses, serverFailures = 1, authFailures = 0,
            p50Ms = 35L, p95Ms = p95, maxMs = 5_900L, jitterMs = 310L,
            lossPercent = misses * 100.0 / 30, consecutiveFailures = 0, lastRoundTripAgeMs = 4_000L,
        )

    @Test fun theHealthTokensCarryTheVerdictAndTerseNumbersOnlyWhileMeasuring() {
        assertEquals("", HaNetworkPathPresentation.healthToken(null))
        val idle = HaNetworkPath().snapshot(0L)
        assertEquals("", HaNetworkPathPresentation.healthToken(idle))
        val snap = degraded(HaNetworkPathSeverity.SEVERE, misses = 3, p95 = 4_200L)
        assertEquals(" ha_net=severe ha_net_cause=loss ha_resp=severe ha_net_p95=4200 ha_net_n=30 ha_net_miss=3 ha_net_age=4000", HaNetworkPathPresentation.healthToken(snap))
        val healthy = degraded(HaNetworkPathSeverity.HEALTHY, misses = 0, p95 = 23L)
        assertEquals(
            " ha_net=healthy ha_resp=healthy ha_net_p95=23 ha_net_n=30 ha_net_miss=0 ha_net_age=4000",
            HaNetworkPathPresentation.healthToken(healthy),
        )
        val fresh = HaNetworkPath().apply { onSocketState(HaSocketState.LIVE) }.snapshot(0L)
        assertEquals(
            " ha_net=healthy ha_resp=healthy ha_net_p95=-1 ha_net_n=0 ha_net_miss=0 ha_net_age=-1",
            HaNetworkPathPresentation.healthToken(fresh),
        )
    }

    @Test fun theDiagnosticsRowWordsEachVerdictFromTheSameEvidence() {
        assertNull(HaNetworkPathPresentation.statusText(null))
        assertEquals(HaNetworkPathPresentation.NOT_MEASURED, HaNetworkPathPresentation.statusText(HaNetworkPath().snapshot(0L)))
        assertEquals(
            "healthy; p95 23 ms, no misses in the last 5 min",
            HaNetworkPathPresentation.statusText(degraded(HaNetworkPathSeverity.HEALTHY, 0, 23L)),
        )
        assertEquals(
            "losing probes; Home Assistant answering slowly; p95 240 ms, no misses in the last 5 min",
            HaNetworkPathPresentation.statusText(degraded(HaNetworkPathSeverity.WARNING, 0, 240L)),
        )
        assertEquals(
            "failing; Home Assistant answering very slowly; p95 4,200 ms, 3 of 30 probes missed in the last 5 min",
            HaNetworkPathPresentation.statusText(degraded(HaNetworkPathSeverity.SEVERE, 3, 4_200L)),
        )
        val slowPath = degraded(HaNetworkPathSeverity.WARNING, 0, 9L).copy(
            cause = PathProbeCause.LATENCY,
            responsiveness = HaNetworkPathSeverity.HEALTHY,
        )
        assertEquals("slow", HaNetworkPathPresentation.statusText(slowPath))
        val measuringButEmpty = HaNetworkPath().apply { onSocketState(HaSocketState.LIVE) }.snapshot(0L)
        assertEquals("healthy; no probes yet in the last 5 min", HaNetworkPathPresentation.statusText(measuringButEmpty))
    }

    @Test fun anEmptyWindowWithARememberedReplyIsAParkedStreamNotAFreshConnect() {
        // A stream that parked (refused sign-in, repeated protocol failures) stops probing while the
        // socket is still wanted; five minutes later the window is empty. The age is the only fact
        // that separates that from a socket that has just connected, so it must be projected.
        val parked = HaNetworkPath().apply { onSocketState(HaSocketState.LIVE) }
        parked.onRoundTrip(0L, 12L)
        val snap = parked.snapshot(7L * 60_000L)
        assertEquals(0, snap.probes)
        assertEquals(7L * 60_000L, snap.lastRoundTripAgeMs)
        assertEquals(
            "healthy; no probe answered in the last 5 min; last reply 7 min ago",
            HaNetworkPathPresentation.statusText(snap),
        )
        assertTrue(HaNetworkPathPresentation.diagnosticLine(snap).endsWith("last_reply_age=420000"))
        assertEquals(420_000L, JSONObject(HaNetworkPathPresentation.statusJson(snap)).getLong("last_round_trip_age_ms"))
        assertEquals("45 s", HaNetworkPathPresentation.age(45_999L))
        assertEquals("1 min", HaNetworkPathPresentation.age(60_000L))
    }

    @Test fun theDiagLineIsTerseHostFreeAndAlwaysPresent() {
        val snap = degraded(HaNetworkPathSeverity.SEVERE, misses = 3, p95 = 4_200L)
        assertEquals(
            "[ha-network] state=severe cause=loss responsiveness=severe measuring=true socket=live window=5m probes=30 " +
                "round_trips=27 p50=35 p95=4200 " +
                "max=5900 jitter=310 loss=10.0% consecutive=0 server_errors=1 auth_errors=0 last_reply_age=4000",
            HaNetworkPathPresentation.diagnosticLine(snap),
        )
        assertEquals("[ha-network] state=idle measuring=false", HaNetworkPathPresentation.diagnosticLine(HaNetworkPath().snapshot(0L)))
    }

    @Test fun theStatusObjectIsEmittedUnconditionallyWithNumbersOnlyWhileMeasuring() {
        val idle = JSONObject(HaNetworkPathPresentation.statusJson(HaNetworkPath().snapshot(0L)))
        assertFalse(idle.getBoolean("measuring"))
        assertEquals("idle", idle.getString("state"))
        assertFalse(idle.has("p95_ms"))
        val json = JSONObject(HaNetworkPathPresentation.statusJson(degraded(HaNetworkPathSeverity.WARNING, 1, 240L)))
        assertTrue(json.getBoolean("measuring"))
        assertEquals("warning", json.getString("state"))
        assertEquals(300_000L, json.getLong("window_ms"))
        assertEquals(30, json.getInt("probes"))
        assertEquals(29, json.getInt("round_trips"))
        assertEquals(1, json.getInt("network_failures"))
        assertEquals(1, json.getInt("server_failures"))
        assertEquals(240L, json.getLong("p95_ms"))
        assertEquals(3.3, json.getDouble("loss_percent"), 0.0001)
        assertEquals(0, json.getInt("consecutive_failures"))
    }

    @Test fun noProjectionEverCarriesAHostAddressOrSample() {
        val snap = degraded(HaNetworkPathSeverity.SEVERE, misses = 3, p95 = 4_200L)
        val everything = listOf(
            HaNetworkPathPresentation.healthToken(snap),
            HaNetworkPathPresentation.statusText(snap)!!,
            HaNetworkPathPresentation.diagnosticLine(snap),
            HaNetworkPathPresentation.statusJson(snap),
        ).joinToString("\n")
        for (forbidden in listOf("http", "://", "ssid", "bssid", "192.", "samples")) {
            assertFalse("projection leaks '$forbidden'", everything.lowercase().contains(forbidden))
        }
    }
}
