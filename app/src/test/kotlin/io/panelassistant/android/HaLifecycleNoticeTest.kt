package io.panelassistant.android

import io.panelassistant.android.control.BuiltinDashboard
import io.panelassistant.android.sensors.HaLifecycle
import io.panelassistant.android.sensors.HaLifecycleSource
import io.panelassistant.android.sensors.HaLifecycleState
import io.panelassistant.android.util.HaTransportEvidence
import io.panelassistant.android.util.HaTransportFault
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class HaLifecycleNoticeTest {
    private var owner = 0L

    @Before fun acquire() {
        RendererAdmissionRuntime.reset()
        owner = BuiltinDashboard.acquireActivityOwner()
    }

    @After fun release() {
        BuiltinDashboard.releaseActivityOwner(owner)
        RendererAdmissionRuntime.reset()
    }

    private fun admit(cached: Boolean = true) {
        RendererAdmissionRuntime.record(
            owner, RendererAdmissionState.ADMITTED, 1_000L,
            admittedOnCachedVersion = cached,
            evidence = HaTransportEvidence(HaTransportFault.DNS, "UnknownHostException"),
        )
    }

    private fun snapshot(state: HaLifecycleState) =
        HaLifecycle.Snapshot(state, HaLifecycleSource.NATIVE, false, 1L, 8_000L)

    @Test fun panelThatMissedStopShowsOfflineAfterConnectionLoss() {
        val tracker = HaLifecycle()
        tracker.onDisconnected(100L)
        assertEquals(HaLifecycleState.CONNECTION_LOST,
            haLifecycleNoticeState(tracker.snapshot(10_100L), null))
    }

    @Test fun cachedDisconnectedDashboardShowsAnOutageUntilTheFrontendConnects() {
        admit()
        for (state in listOf(null, HaLifecycleState.NORMAL, HaLifecycleState.CONNECTION_LOST, HaLifecycleState.BACK_ONLINE)) {
            assertEquals("cached dashboard with lifecycle $state", HaLifecycleState.CONNECTION_LOST,
                haLifecycleNoticeState(state?.let(::snapshot), RendererAdmissionRuntime.current()))
        }
        assertNull("cached rendering must not bypass an observed loss grace",
            haLifecycleNoticeState(snapshot(HaLifecycleState.CONNECTION_LOST).copy(offlineGraceRemainingMs = 1L),
                RendererAdmissionRuntime.current()))
        RendererAdmissionRuntime.setFrontendConnected(owner, true)
        assertNull(haLifecycleNoticeState(snapshot(HaLifecycleState.NORMAL), RendererAdmissionRuntime.current()))
        RendererAdmissionRuntime.setFrontendConnected(owner, false)
        assertEquals(HaLifecycleState.CONNECTION_LOST,
            haLifecycleNoticeState(null, RendererAdmissionRuntime.current()))
    }

    @Test fun deliberateShutdownAndStartupKeepTheirWording() {
        admit()
        for (state in listOf(HaLifecycleState.SHUTTING_DOWN, HaLifecycleState.STARTING)) {
            assertEquals(state, haLifecycleNoticeState(snapshot(state), RendererAdmissionRuntime.current()))
        }
    }

    @Test fun liveAdmissionShowsAnObservedLossOnlyAfterItsGrace() {
        assertNull(haLifecycleNoticeState(null, RendererAdmissionRuntime.current()))
        admit(cached = false)
        assertNull(haLifecycleNoticeState(snapshot(HaLifecycleState.CONNECTION_LOST).copy(offlineGraceRemainingMs = 1L), RendererAdmissionRuntime.current()))
        assertEquals(HaLifecycleState.CONNECTION_LOST,
            haLifecycleNoticeState(snapshot(HaLifecycleState.CONNECTION_LOST), RendererAdmissionRuntime.current()))
    }

    @Test fun replacingTheActivityRetiresTheOldCachedOutage() {
        admit()
        val replacement = BuiltinDashboard.acquireActivityOwner()
        try {
            assertNull(haLifecycleNoticeState(null, RendererAdmissionRuntime.current()))
            RendererAdmissionRuntime.setFrontendConnected(owner, false)
            assertNull(haLifecycleNoticeState(null, RendererAdmissionRuntime.current()))
        } finally {
            BuiltinDashboard.releaseActivityOwner(replacement)
        }
    }
}
