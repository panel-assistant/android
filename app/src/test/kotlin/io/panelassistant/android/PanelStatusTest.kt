package io.panelassistant.android

import io.panelassistant.android.control.BuiltinDashboard
import io.panelassistant.android.control.SystemController
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class PanelStatusTest {
    @Before
    @After
    fun resetGlobalRecoveryState() {
        BuiltinDashboard.clearRendererLatch()
        PanelStatus.clearExternalRecovery()
    }

    @Test
    fun builtinRecoveryIsDerivedImmediatelyFromItsLatch() {
        repeat(BuiltinDashboard.MAX_REBUILDS) {
            assertTrue(BuiltinDashboard.consumeRebuildBudget(0L))
        }
        assertFalse(BuiltinDashboard.consumeRebuildBudget(0L))

        assertEquals(
            PanelStatus.DashboardRecoveryState.BUILTIN_RENDERER,
            PanelStatus.dashboardRecoveryState(SystemController.BUILTIN_DASHBOARD, OWN_PACKAGE, 0L),
        )
        assertEquals(
            PanelStatus.DashboardRecoveryState.NONE,
            PanelStatus.dashboardRecoveryState(
                SystemController.BUILTIN_DASHBOARD,
                OWN_PACKAGE,
                BuiltinDashboard.RENDERER_LATCH_MS,
            ),
        )
    }

    @Test
    fun explicitBuiltinClearRemovesRecoveryWithoutAWatchdogPoll() {
        repeat(BuiltinDashboard.MAX_REBUILDS + 1) { BuiltinDashboard.consumeRebuildBudget(0L) }
        BuiltinDashboard.clearRendererLatch()

        assertEquals(
            PanelStatus.DashboardRecoveryState.NONE,
            PanelStatus.dashboardRecoveryState(SystemController.BUILTIN_DASHBOARD, OWN_PACKAGE, 0L),
        )
    }

    @Test
    fun externalRecoveryProjectionIsTargetKeyed() {
        assertTrue(PanelStatus.publishExternalRecovery("renderer.a", blocked = true))
        assertFalse(PanelStatus.publishExternalRecovery("renderer.a", blocked = true))

        assertEquals(
            PanelStatus.DashboardRecoveryState.EXTERNAL_RENDERER,
            PanelStatus.dashboardRecoveryState("renderer.a", OWN_PACKAGE, 0L),
        )
        assertEquals(
            PanelStatus.DashboardRecoveryState.NONE,
            PanelStatus.dashboardRecoveryState("renderer.b", OWN_PACKAGE, 0L),
        )
        assertEquals(
            PanelStatus.DashboardRecoveryState.NONE,
            PanelStatus.dashboardRecoveryState(SystemController.BUILTIN_DASHBOARD, OWN_PACKAGE, 0L),
        )
    }

    @Test
    fun ownPackageAliasUsesTheBuiltinLatchProjection() {
        repeat(BuiltinDashboard.MAX_REBUILDS + 1) { BuiltinDashboard.consumeRebuildBudget(0L) }

        assertEquals(
            PanelStatus.DashboardRecoveryState.BUILTIN_RENDERER,
            PanelStatus.dashboardRecoveryState(OWN_PACKAGE, OWN_PACKAGE, 0L),
        )
    }

    private companion object {
        const val OWN_PACKAGE = "io.github.maxlyth.hapaneld"
    }
}
