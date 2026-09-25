package io.github.maxlyth.hapaneld

import io.github.maxlyth.hapaneld.control.SystemController
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LaunchScreenPolicyTest {
    @Test fun kioskOffPreservesIntroForBuiltinAndExternalRenderers() {
        assertIntro(decide(kiosk = false, renderer = SystemController.BUILTIN_DASHBOARD))
        assertIntro(decide(kiosk = false, renderer = "com.example.renderer"))
    }

    @Test fun builtinKioskShowsIntroOnFirstInstallThenBypassesForSameVersion() {
        val first = decide(lastShown = null)
        assertIntro(first)
        assertTrue(first.rememberVersionShown)

        val same = decide(lastShown = VERSION)
        assertEquals(LaunchDestination.DASHBOARD, same.destination)
        assertFalse(same.rememberVersionShown)
    }

    @Test fun builtinKioskShowsIntroForBothUpgradeAndDowngrade() {
        for (otherVersion in listOf(VERSION - 1, VERSION + 1)) {
            val decision = decide(lastShown = otherVersion)
            assertIntro(decision)
            assertTrue(decision.rememberVersionShown)
        }
    }

    @Test fun externalKioskBypassesIntroRegardlessOfRememberedVersion() {
        for (lastShown in listOf<Long?>(null, VERSION, VERSION - 1)) {
            assertEquals(
                LaunchDestination.DASHBOARD,
                decide(renderer = "com.example.renderer", lastShown = lastShown).destination,
            )
        }
    }

    @Test fun coldStartupDoesNotFlashIntroWhileWaitingForRendererHealthEvidence() {
        assertEquals(
            LaunchDestination.DASHBOARD,
            decide(crashLooping = false, lastShown = VERSION).destination,
        )
        assertEquals(
            LaunchDestination.DASHBOARD,
            decide(
                renderer = "com.example.renderer",
                crashLooping = false,
                lastShown = null,
            ).destination,
        )
    }

    @Test fun unavailableUnconfiguredAndCrashLoopingTargetsKeepRecoveryVisible() {
        assertIntro(decide(launchAvailable = false))
        assertIntro(decide(builtInUrlConfigured = false))
        assertIntro(decide(crashLooping = true))
    }

    @Test fun explicitAdminEntryAlwaysKeepsIntroVisible() {
        val decision = decide(explicitAdmin = true, lastShown = VERSION)
        assertIntro(decision)
        assertFalse(decision.rememberVersionShown)
    }

    @Test fun introAcknowledgementRequiresTheCurrentAttachedPresentedGeneration() {
        fun admitted(
            generation: Boolean = true,
            presented: Boolean = true,
            attached: Boolean = true,
            finishing: Boolean = false,
            destroyed: Boolean = false,
            chosen: Boolean = true,
        ) = mayAcknowledgePresentedIntro(
            generationMatches = generation,
            presentedViewMatches = presented,
            viewAttached = attached,
            activityFinishing = finishing,
            activityDestroyed = destroyed,
            startupChosen = chosen,
        )

        assertTrue(admitted())
        assertFalse(admitted(generation = false))
        assertFalse(admitted(presented = false))
        assertFalse(admitted(attached = false))
        assertFalse(admitted(finishing = true))
        assertFalse(admitted(destroyed = true))
        assertFalse(admitted(chosen = false))
    }

    @Test fun recreationBeforeFirstDrawRetainsPendingAcknowledgementAndReturnDeadline() {
        val plan = restoreLaunchIntro(
            saved = SavedLaunchIntroState(
                explicitAdminEntry = false,
                pendingVersionCode = VERSION,
                autoReturnRemainingMs = 88_000L,
                autoReturnNextAttemptRemainingMs = 6_000L,
            ),
            currentVersionCode = VERSION,
            lastShownVersionCode = null,
            minimumReturnDelayMs = 250L,
            maximumReturnWindowMs = 90_000L,
        )

        assertFalse(plan.explicitAdminEntry)
        assertEquals(VERSION, plan.pendingVersionCode)
        assertEquals(88_000L, plan.autoReturnRemainingMs)
        assertEquals(6_000L, plan.autoReturnDelayMs)
    }

    @Test fun recreationAfterAcknowledgementRetainsOnlyRemainingReturnSchedule() {
        val plan = restoreLaunchIntro(
            saved = SavedLaunchIntroState(
                explicitAdminEntry = false,
                // onSave may capture pending immediately before the first-draw commit completes;
                // durable config wins when the replacement activity restores that stale bundle.
                pendingVersionCode = VERSION,
                autoReturnRemainingMs = 87_000L,
                autoReturnNextAttemptRemainingMs = 5_000L,
            ),
            currentVersionCode = VERSION,
            lastShownVersionCode = VERSION,
            minimumReturnDelayMs = 250L,
            maximumReturnWindowMs = 90_000L,
        )

        assertNull(plan.pendingVersionCode)
        assertEquals(87_000L, plan.autoReturnRemainingMs)
        assertEquals(5_000L, plan.autoReturnDelayMs)
    }

    @Test fun explicitAdminRecreationNeverAcquiresAnAutoReturnSchedule() {
        val plan = restoreLaunchIntro(
            saved = SavedLaunchIntroState(
                explicitAdminEntry = true,
                pendingVersionCode = null,
                autoReturnRemainingMs = 87_000L,
                autoReturnNextAttemptRemainingMs = 5_000L,
            ),
            currentVersionCode = VERSION,
            lastShownVersionCode = VERSION,
            minimumReturnDelayMs = 250L,
            maximumReturnWindowMs = 90_000L,
        )

        assertTrue(plan.explicitAdminEntry)
        assertNull(plan.pendingVersionCode)
        assertNull(plan.autoReturnRemainingMs)
        assertNull(plan.autoReturnDelayMs)
    }

    @Test fun rebootLikeUptimeResetCannotExtendRestoredReturnBeyondOneWindow() {
        val plan = restoreLaunchIntro(
            saved = SavedLaunchIntroState(
                explicitAdminEntry = false,
                pendingVersionCode = null,
                autoReturnRemainingMs = Long.MAX_VALUE,
                autoReturnNextAttemptRemainingMs = 8_000L,
            ),
            currentVersionCode = VERSION,
            lastShownVersionCode = VERSION,
            minimumReturnDelayMs = 250L,
            maximumReturnWindowMs = 90_000L,
        )

        assertEquals(90_000L, plan.autoReturnRemainingMs)
        assertEquals(8_000L, plan.autoReturnDelayMs)
    }

    @Test fun slowStartupDoesNotConsumeVisibleIntroDwellTime() {
        val prepared = prepareVisibleAutoReturn(
            restoredRemainingMs = null,
            restoredDelayMs = null,
            defaultWindowMs = 90_000L,
            defaultFirstDelayMs = 8_000L,
        )
        assertEquals(PreparedVisibleAutoReturn(90_000L, 8_000L), prepared)

        // Simulate an old panel taking seven seconds between preparing the view and drawing it.
        val armed = armVisibleAutoReturn(requireNotNull(prepared), firstDrawAtMs = 8_000L)
        assertEquals(98_000L, armed.deadlineMs)
        assertEquals(16_000L, armed.firstAttemptMs)
    }

    @Test fun restoredReturnKeepsItsRemainingVisibleWindow() {
        val prepared = prepareVisibleAutoReturn(
            restoredRemainingMs = 60_000L,
            restoredDelayMs = 6_000L,
            defaultWindowMs = 90_000L,
            defaultFirstDelayMs = 8_000L,
        )
        assertEquals(PreparedVisibleAutoReturn(60_000L, 6_000L), prepared)

        val armed = armVisibleAutoReturn(requireNotNull(prepared), firstDrawAtMs = 12_000L)
        assertEquals(72_000L, armed.deadlineMs)
        assertEquals(18_000L, armed.firstAttemptMs)
    }

    private fun decide(
        kiosk: Boolean = true,
        renderer: String = SystemController.BUILTIN_DASHBOARD,
        builtInUrlConfigured: Boolean = true,
        launchAvailable: Boolean = true,
        crashLooping: Boolean = false,
        lastShown: Long? = VERSION,
        explicitAdmin: Boolean = false,
    ): LaunchScreenDecision = LaunchScreenPolicy.decide(
        kioskEnabled = kiosk,
        configuredRenderer = renderer,
        builtInUrlConfigured = builtInUrlConfigured,
        dashboardLaunchAvailable = launchAvailable,
        dashboardRecoveryBlocked = crashLooping,
        currentVersionCode = VERSION,
        lastShownVersionCode = lastShown,
        explicitAdminEntry = explicitAdmin,
    )

    private fun assertIntro(decision: LaunchScreenDecision) {
        assertEquals(LaunchDestination.INTRO, decision.destination)
    }

    private companion object {
        const val VERSION = 323L
    }
}
