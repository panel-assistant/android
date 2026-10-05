package io.panelassistant.android.sensors

import org.junit.Assert.*
import org.junit.Test

class HaLifecycleCoordinatorTest {
    private var now = 0L
    private val seen = mutableListOf<Int>()

    private fun coordinator() = HaLifecycleCoordinator(
        lifecycle = HaLifecycle(backOnlineWindowMs = 8_000L),
        nowMs = { now },
        onChanged = { seen += 1 },
    )

    @Test fun aLocallyNoticedConnectionLossCarriesNoSource() {
        val c = coordinator()
        c.onNativeDisconnected()
        val snap = c.snapshot()
        assertEquals(HaLifecycleState.CONNECTION_LOST, snap.state)
        assertNull("nobody observed this state; naming a source would invent an observation", snap.source)
    }

    @Test fun theInitialNormalCarriesNoSource() {
        assertNull("nothing has been observed yet", coordinator().snapshot().source)
    }

    @Test fun repeatedIdenticalSignalsDoNotRenotifyTheListener() {
        val c = coordinator()
        repeat(5) { c.onNativeDisconnected() }
        assertEquals(1, seen.size)
        assertEquals(HaLifecycleState.CONNECTION_LOST, c.snapshot().state)
    }

    @Test fun thePublishDecisionNeverGoesBackwardsAndNeverRepeatsItself() {
        val older = HaLifecycle.Snapshot(HaLifecycleState.SHUTTING_DOWN, HaLifecycleSource.NATIVE, false, 3L, 0L)
        val newer = HaLifecycle.Snapshot(HaLifecycleState.BACK_ONLINE, HaLifecycleSource.NATIVE, false, 4L, 8_000L)
        assertTrue(lifecyclePublishDecision(newer, older))
        assertFalse("an older revision must lose, even though it renders differently",
            lifecyclePublishDecision(older, newer))
        assertFalse("a rendering-identical snapshot must not renotify",
            lifecyclePublishDecision(newer.copy(revision = 9L), newer))
    }

    @Test fun theRemainingLifetimeIsNotPartOfTheRenderingIdentity() {
        val early = HaLifecycle.Snapshot(HaLifecycleState.BACK_ONLINE, HaLifecycleSource.NATIVE, false, 4L, 8_000L)
        val late = early.copy(revision = 5L, backOnlineRemainingMs = 1_000L)
        assertFalse(
            "a notice merely aging is not a new fact and must not renotify",
            lifecyclePublishDecision(late, early),
        )
    }
}
