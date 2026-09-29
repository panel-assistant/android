package io.github.maxlyth.hapaneld

import io.github.maxlyth.hapaneld.control.BuiltinDashboard
import io.github.maxlyth.hapaneld.sensors.HaLifecycle
import io.github.maxlyth.hapaneld.sensors.HaLifecycleSource
import io.github.maxlyth.hapaneld.sensors.HaLifecycleState
import io.github.maxlyth.hapaneld.util.HaTransportEvidence
import io.github.maxlyth.hapaneld.util.HaTransportFault
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
        HaLifecycle.Snapshot(state, HaLifecycleSource.SOCKET, false, 1L, 8_000L)

    @Test fun cachedDisconnectedDashboardShowsAnOutageUntilTheFrontendConnects() {
        admit()
        for (state in listOf(null, HaLifecycleState.NORMAL, HaLifecycleState.CONNECTION_LOST, HaLifecycleState.BACK_ONLINE)) {
            assertEquals("cached dashboard with lifecycle $state", HaLifecycleState.CONNECTION_LOST,
                haLifecycleNoticeState(state?.let(::snapshot), RendererAdmissionRuntime.current()))
        }
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

    @Test fun liveAdmissionAndAnUnobservedRendererDoNotInventAnOutage() {
        assertNull(haLifecycleNoticeState(null, RendererAdmissionRuntime.current()))
        admit(cached = false)
        assertNull(haLifecycleNoticeState(snapshot(HaLifecycleState.CONNECTION_LOST), RendererAdmissionRuntime.current()))
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
