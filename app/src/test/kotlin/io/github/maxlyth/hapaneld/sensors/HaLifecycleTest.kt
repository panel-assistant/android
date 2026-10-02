package io.github.maxlyth.hapaneld.sensors

import org.junit.Assert.*
import org.junit.Test

class HaLifecycleTest {
    private fun lifecycle() = HaLifecycle(backOnlineWindowMs = 8_000L)

    private fun HaLifecycle.notice(phase: HaLifecyclePhase, nowMs: Long) =
        onNativeNotice(HaLifecycleNotice(phase, HaLifecycleReason.UNKNOWN, null, null), nowMs)

    @Test fun normalRestartWalksShuttingDownThenStartingThenBackOnline() {
        val ha = lifecycle()
        assertEquals(HaLifecycleState.NORMAL, ha.state(0))

        ha.notice(HaLifecyclePhase.SHUTTING_DOWN, 1_000)
        assertEquals(HaLifecycleState.SHUTTING_DOWN, ha.state(1_000))

        ha.notice(HaLifecyclePhase.SHUTTING_DOWN, 1_100)
        assertEquals(HaLifecycleState.SHUTTING_DOWN, ha.state(1_100))

        ha.notice(HaLifecyclePhase.SHUTTING_DOWN, 1_200)
        assertEquals(HaLifecycleState.SHUTTING_DOWN, ha.state(1_200))

        ha.notice(HaLifecyclePhase.STARTING, 20_000)
        assertEquals(HaLifecycleState.STARTING, ha.state(20_000))

        ha.notice(HaLifecyclePhase.READY, 30_000)
        assertEquals(HaLifecycleState.BACK_ONLINE, ha.state(30_000))
    }

    @Test fun backOnlineDecaysToNormalOnceItsWindowElapses() {
        val ha = lifecycle()
        ha.notice(HaLifecyclePhase.SHUTTING_DOWN, 0L)
        ha.notice(HaLifecyclePhase.READY, 5_000)
        assertEquals(HaLifecycleState.BACK_ONLINE, ha.state(12_999))
        assertEquals(HaLifecycleState.NORMAL, ha.state(13_000))
    }

    @Test fun theRecoveryNoticeReportsItsRemainingLifetimeNotAFreshWindow() {
        val ha = lifecycle()
        ha.notice(HaLifecyclePhase.SHUTTING_DOWN, 0L)
        ha.notice(HaLifecyclePhase.READY, 1_000)
        assertEquals(8_000L, ha.snapshot(1_000).backOnlineRemainingMs)
        assertEquals("half spent", 4_000L, ha.snapshot(5_000).backOnlineRemainingMs)
        assertEquals("nearly done", 1L, ha.snapshot(8_999).backOnlineRemainingMs)
    }

    @Test fun thereIsNoRemainingLifetimeWhenNoNoticeIsShowing() {
        val ha = lifecycle()
        assertEquals("nothing showing", 0L, ha.snapshot(1_000).backOnlineRemainingMs)

        ha.notice(HaLifecyclePhase.SHUTTING_DOWN, 500)
        ha.notice(HaLifecyclePhase.READY, 1_000)
        assertEquals("expired", 0L, ha.snapshot(9_000).backOnlineRemainingMs)

        ha.notice(HaLifecyclePhase.SHUTTING_DOWN, 20_000)
        assertEquals("an outage is not a recovery notice", 0L, ha.snapshot(20_000).backOnlineRemainingMs)
    }

    @Test fun aBackwardsClockReportsNoRemainingLifetimeBecauseTheNoticeHasExpired() {
        val ha = lifecycle()
        ha.notice(HaLifecyclePhase.SHUTTING_DOWN, 0L)
        ha.notice(HaLifecyclePhase.READY, 50_000)
        // The state expires first, so there is no notice left to measure — which is also why no clamp
        // is needed on the arithmetic.
        assertEquals(0L, ha.snapshot(10).backOnlineRemainingMs)
    }

    @Test fun backOnlineExpiresRatherThanExtendsWhenTheClockMovesBackwards() {
        val ha = lifecycle()
        ha.notice(HaLifecyclePhase.SHUTTING_DOWN, 0L)
        ha.notice(HaLifecyclePhase.READY, 50_000)
        assertEquals(HaLifecycleState.NORMAL, ha.state(10))
    }

    @Test fun startIsNotBackOnline() {
        val ha = lifecycle()
        ha.notice(HaLifecyclePhase.SHUTTING_DOWN, 1_000)
        ha.notice(HaLifecyclePhase.STARTING, 2_000)
        assertEquals(HaLifecycleState.STARTING, ha.state(2_000))
        // Still an outage: the frontend cannot render yet, so the notice must not clear.
        assertNotNull(HaLifecycleMessage.text(ha.state(2_000), HaLifecycleSource.NATIVE))
    }

    @Test fun startingNeverDecaysOnItsOwn() {
        val ha = lifecycle()
        ha.notice(HaLifecyclePhase.STARTING, 1_000)
        assertEquals(HaLifecycleState.STARTING, ha.state(9_999_000))
    }

    @Test fun duplicateShutdownEventsDoNotReopenOrReannounce() {
        val ha = lifecycle()
        ha.notice(HaLifecyclePhase.SHUTTING_DOWN, 1_000)
        ha.notice(HaLifecyclePhase.SHUTTING_DOWN, 1_050)
        ha.notice(HaLifecyclePhase.SHUTTING_DOWN, 1_060)
        assertEquals(HaLifecycleState.SHUTTING_DOWN, ha.state(1_060))
    }

    @Test fun duplicateStartedDoesNotRestartTheBackOnlineWindow() {
        val ha = lifecycle()
        ha.notice(HaLifecyclePhase.SHUTTING_DOWN, 0L)
        ha.notice(HaLifecyclePhase.READY, 1_000)
        ha.notice(HaLifecyclePhase.READY, 8_000)
        // Had the duplicate re-armed the window it would still be BACK_ONLINE at 9_000.
        assertEquals(HaLifecycleState.NORMAL, ha.state(9_000))
    }

    @Test fun duplicateStartDoesNotDisturbStarting() {
        val ha = lifecycle()
        ha.notice(HaLifecyclePhase.STARTING, 1_000)
        ha.notice(HaLifecyclePhase.STARTING, 2_000)
        assertEquals(HaLifecycleState.STARTING, ha.state(2_000))
    }

    @Test fun disconnectImmediatelyAfterStopKeepsTheShutdownExplanation() {
        val ha = lifecycle()
        ha.notice(HaLifecyclePhase.SHUTTING_DOWN, 1_000)
        ha.onDisconnected(1_010)
        assertEquals(HaLifecycleState.SHUTTING_DOWN, ha.state(1_010))
    }

    @Test fun disconnectWhileStartingKeepsTheStartingExplanation() {
        val ha = lifecycle()
        ha.notice(HaLifecyclePhase.STARTING, 1_000)
        ha.onDisconnected(1_010)
        assertEquals(HaLifecycleState.STARTING, ha.state(1_010))
    }

    @Test fun ordinaryLanLossIsGenericAndNeverCalledAShutdown() {
        val ha = lifecycle()
        ha.onDisconnected(1_000)
        assertEquals(HaLifecycleState.CONNECTION_LOST, ha.state(1_000))
        assertNull(
            "a generic loss must not borrow Home Assistant shutdown wording",
            HaLifecycleMessage.text(ha.state(1_000), null),
        )
    }

    @Test fun repeatedDisconnectsStayGeneric() {
        val ha = lifecycle()
        ha.onDisconnected(1_000)
        ha.onDisconnected(2_000)
        assertEquals(HaLifecycleState.CONNECTION_LOST, ha.state(2_000))
    }

    @Test fun aGenuinelyNewOutageStillAnnouncesItsOwnRecovery() {
        // The suppression must be per-episode, not once per process, or the second real restart of the
        // day would go unannounced.
        val ha = lifecycle()
        ha.notice(HaLifecyclePhase.SHUTTING_DOWN, 1_000)
        ha.notice(HaLifecyclePhase.READY, 5_000)
        assertEquals(HaLifecycleState.BACK_ONLINE, ha.state(5_000))
        assertEquals(HaLifecycleState.NORMAL, ha.state(13_000))
        ha.notice(HaLifecyclePhase.SHUTTING_DOWN, 100_000)
        ha.notice(HaLifecyclePhase.READY, 140_000)
        assertEquals(
            "a second outage is a second episode and earns its own notice",
            HaLifecycleState.BACK_ONLINE,
            ha.state(140_000),
        )
    }

    @Test fun stateWireValuesAreStableAndDistinct() {
        val wire = HaLifecycleState.entries.map { it.wireValue }
        assertEquals(wire.size, wire.toSet().size)
        assertEquals(
            listOf("normal", "shutting_down", "starting", "back_online", "connection_lost"),
            wire,
        )
    }

    @Test fun messagesNameHomeAssistantAsTheActorAndCarryNoPanelDetail() {
        val shutdown = HaLifecycleMessage.text(HaLifecycleState.SHUTTING_DOWN, HaLifecycleSource.NATIVE).orEmpty()
        assertTrue(shutdown.startsWith("Home Assistant"))
        listOf(
            HaLifecycleState.SHUTTING_DOWN,
            HaLifecycleState.STARTING,
            HaLifecycleState.BACK_ONLINE,
        ).forEach { state ->
            val text = HaLifecycleMessage.text(state, HaLifecycleSource.NATIVE).orEmpty()
            assertTrue("$state must name Home Assistant", text.contains("Home Assistant"))
            assertFalse("$state must not blame ha-paneld", text.contains("ha-paneld"))
        }
    }

    @Test fun theBackOnlineWindowMustBePositive() {
        listOf(0L, -1L).forEach { invalid ->
            val failed = runCatching { HaLifecycle(backOnlineWindowMs = invalid) }.isFailure
            assertTrue("window $invalid must be rejected", failed)
        }
    }

    @Test fun aNullSourceRendersTheWeakerOfflineClaimNeverTheDeliberateOne() {
        assertEquals(
            "Home Assistant has gone offline — controls may be temporarily unavailable.",
            HaLifecycleMessage.text(HaLifecycleState.SHUTTING_DOWN, null),
        )
    }

    @Test fun aConnectionLossDuringARecoveryNoticeDropsTheNoticesSource() {
        val ha = lifecycle()
        ha.notice(HaLifecyclePhase.SHUTTING_DOWN, 0L)
        ha.notice(HaLifecyclePhase.READY, 1_000)
        assertEquals(HaLifecycleSource.NATIVE, ha.snapshot(1_000).source)

        ha.onDisconnected(2_000)
        val snap = ha.snapshot(2_000)
        assertEquals(HaLifecycleState.CONNECTION_LOST, snap.state)
        assertNull(
            "nobody observed the loss, so the recovery notice's source must not be inherited",
            snap.source,
        )
    }
}
