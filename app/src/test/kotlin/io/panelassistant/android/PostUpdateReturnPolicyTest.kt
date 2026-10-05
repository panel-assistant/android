package io.panelassistant.android

import io.panelassistant.android.control.SystemController
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PostUpdateReturnPolicyTest {
    @Test fun builtInRendererIsReadyWithMqttDisabled() {
        assertTrue(
            PostUpdateReturnPolicy.dashboardReady(
                configuredRenderer = SystemController.BUILTIN_DASHBOARD,
                builtInUrlConfigured = true,
                dashboardLaunchAvailable = true,
                dashboardRecoveryBlocked = false,
            ),
        )
    }

    @Test fun builtInRendererStillRequiresAConfiguredUrl() {
        assertFalse(
            PostUpdateReturnPolicy.dashboardReady(
                configuredRenderer = SystemController.BUILTIN_DASHBOARD,
                builtInUrlConfigured = false,
                dashboardLaunchAvailable = true,
                dashboardRecoveryBlocked = false,
            ),
        )
    }

    @Test fun foreignRendererIsReadyWithMqttDisabled() {
        assertTrue(
            PostUpdateReturnPolicy.dashboardReady(
                configuredRenderer = "io.homeassistant.companion.android",
                builtInUrlConfigured = false,
                dashboardLaunchAvailable = true,
                dashboardRecoveryBlocked = false,
            ),
        )
    }

    @Test fun unavailableOrCrashLoopingRendererNeverAutoReturns() {
        assertFalse(
            PostUpdateReturnPolicy.dashboardReady("", false, false, false),
        )
        assertFalse(
            PostUpdateReturnPolicy.dashboardReady(
                SystemController.BUILTIN_DASHBOARD, true, true, true,
            ),
        )
    }
}
