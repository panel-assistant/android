package io.panelassistant.android.http

import io.panelassistant.android.PanelStatus.DashboardRecoveryState
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DashboardRecoveryWarningTest {
    @Test
    fun noRecoverySuppressionHasNoWarning() {
        assertNull(dashboardRecoveryWarning(DashboardRecoveryState.NONE))
    }

    @Test
    fun builtinWarningNamesWebViewAndExplicitReload() {
        val warning = requireNotNull(dashboardRecoveryWarning(DashboardRecoveryState.BUILTIN_RENDERER))
        assertTrue(warning.contains("Built-in renderer"))
        assertTrue(warning.contains("System WebView"))
        assertTrue(warning.contains("Reload dashboard"))
        assertTrue(!warning.contains("Companion app"))
    }

    @Test
    fun externalWarningRetainsDashboardAppRemediation() {
        val warning = requireNotNull(dashboardRecoveryWarning(DashboardRecoveryState.EXTERNAL_RENDERER))
        assertTrue(warning.contains("Dashboard app is crash-looping"))
        assertTrue(warning.contains("dashboard/Companion app"))
    }
}
