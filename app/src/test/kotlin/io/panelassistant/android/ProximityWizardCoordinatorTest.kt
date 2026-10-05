package io.panelassistant.android

import io.panelassistant.android.control.AppState
import io.panelassistant.android.control.CrashLoopTracker
import io.panelassistant.android.control.DashboardRecoveryPolicy
import io.panelassistant.android.control.SystemController
import io.panelassistant.android.control.evaluateDashboardRecovery
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest

class ProximityWizardCoordinatorTest {
    private class Fixture(narrator: ProximityWizardNarrator? = null) {
        var active = false
        var startAccepted = true
        var launchAccepted = true
        var acquireAccepted = true
        var starts = 0
        var launches = 0
        var releases = 0
        var cancels = 0
        val coordinator = ProximityWizardCoordinator(
            startSession = { starts++; active = startAccepted; startAccepted },
            active = { active },
            status = { """{"sessionId":"current"}""" },
            visible = { true },
            localAction = { false },
            cancel = { _, _ -> cancels++; active = false; true },
            heartbeat = { it == "current" },
            reset = { true },
            acquireDisplay = { acquireAccepted },
            releaseDisplay = { releases++ },
            launch = { launches++; launchAccepted },
            narrator = narrator,
        )
    }

    @Test fun failedLaunchCancelsSessionAndReleasesDisplay() {
        val f = Fixture()
        try {
            f.launchAccepted = false
            assertFalse(f.coordinator.remote("start", ""))
            assertFalse(f.active)
            assertEquals(1, f.cancels)
            assertEquals(1, f.releases)
            assertEquals(1, f.launches)
        } finally { f.coordinator.close() }
    }

    @Test fun failedStartReleasesDisplayWithoutLaunching() {
        val f = Fixture()
        try {
            f.startAccepted = false
            assertFalse(f.coordinator.remote("start", ""))
            assertEquals(1, f.releases)
            assertEquals(0, f.launches)
            assertEquals(0, f.cancels)
        } finally { f.coordinator.close() }
    }

    @Test fun failedDisplayAdmissionCannotStartSession() {
        val f = Fixture()
        try {
            f.acquireAccepted = false
            assertFalse(f.coordinator.remote("start", ""))
            assertEquals(0, f.starts)
            assertEquals(0, f.launches)
        } finally { f.coordinator.close() }
    }

    @Test fun activeSessionRejectsSecondLaunchAndCloseDetachesAdmission() {
        val f = Fixture()
        try {
            assertTrue(f.coordinator.remote("start", ""))
            assertFalse(f.coordinator.remote("start", ""))
            assertEquals(1, f.starts)
            assertEquals(1, f.launches)
            f.coordinator.close()
            assertFalse(f.coordinator.remote("start", ""))
            assertFalse(ProximityWizardHost.action("current", "save"))
            assertEquals(1, f.releases)
        } finally { f.coordinator.close() }
    }

    @Test fun activePanelSetupStaysInFrontWhenDashboardWatchdogTicks() {
        val f = Fixture()
        val policy = DashboardRecoveryPolicy(2, 300, CrashLoopTracker(2, 1_000, 5_000))
        try {
            assertTrue(f.coordinator.remote("start", ""))
            assertEquals(1, f.launches)
            for (now in listOf(0L, 300L, 600L)) {
                val decision = evaluateDashboardRecovery(
                    policy, SystemController.BUILTIN_DASHBOARD, AppState.BG, now,
                    builtinTarget = true, calibrationActive = f.active,
                )
                assertEquals(DashboardRecoveryPolicy.Action.NONE, decision.action)
            }
            assertTrue(f.coordinator.remote("cancel", "current"))
            assertEquals(
                DashboardRecoveryPolicy.Action.NONE,
                evaluateDashboardRecovery(
                    policy, SystemController.BUILTIN_DASHBOARD, AppState.BG, 601,
                    builtinTarget = true, calibrationActive = f.active,
                ).action,
            )
            assertEquals(
                DashboardRecoveryPolicy.Action.RETURN_FROM_BACKGROUND,
                evaluateDashboardRecovery(
                    policy, SystemController.BUILTIN_DASHBOARD, AppState.BG, 901,
                    builtinTarget = true, calibrationActive = f.active,
                ).action,
            )
        } finally { f.coordinator.close() }
    }

    @Test fun hostNarrationIsServiceOwnedAndCancelSilencesIt() = runTest {
        val spoken = mutableListOf<String>()
        val narrator = ProximityWizardNarrator({ text, _, _ -> spoken += text }, dispatcher = StandardTestDispatcher(testScheduler))
        val f = Fixture(narrator)
        try {
            assertTrue(ProximityWizardHost.narrate("current", "near|APPROACH", "Approach", "en-GB"))
            testScheduler.runCurrent()
            assertEquals(listOf("Approach"), spoken)
            assertTrue(f.coordinator.remote("cancel", "current"))
            assertTrue(ProximityWizardHost.narrate("current", "near|APPROACH", "Approach", "en-GB"))
            testScheduler.runCurrent()
            assertEquals(listOf("Approach", "Approach"), spoken)
        } finally { f.coordinator.close() }
        assertFalse(ProximityWizardHost.narrate("current", "waves|WAVE", "Wave", "en-GB"))
    }
}
