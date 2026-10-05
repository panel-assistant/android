package io.panelassistant.android.control

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Settlement is CURRENT-generation truth, not a process-lifetime latch: it begins false with each
 * acquired renderer generation, is earned by that renderer's own frontend connect, and dies with it.
 * The listener seam is identity-safe in both directions — a stopped service is neither retained nor
 * called by a later renderer, and a predecessor's late release cannot un-settle a live replacement.
 */
class BuiltinDashboardRendererSettledTest {
    private var owner = 0L

    @Before fun begin() {
        owner = BuiltinDashboard.acquireActivityOwner()
    }

    @After fun teardown() {
        BuiltinDashboard.setRendererSettledListener(null)
        BuiltinDashboard.releaseActivityOwner(owner)
    }

    @Test fun aNewGenerationBeginsUnsettledAndTheListenerWaitsForItsOwnSettle() {
        var fired = 0
        BuiltinDashboard.setRendererSettledListener { fired++ }
        assertFalse("acquisition begins a generation unsettled", BuiltinDashboard.rendererSettled)
        assertEquals(0, fired)

        BuiltinDashboard.onRendererSettled(owner)
        assertTrue(BuiltinDashboard.rendererSettled)
        assertEquals(1, fired)
    }

    @Test fun installingAfterSettleFiresImmediatelySoARestartedServiceIsNotStranded() {
        BuiltinDashboard.onRendererSettled(owner)
        var fired = 0
        BuiltinDashboard.setRendererSettledListener { fired++ }
        assertEquals("the settled fact must reach a late listener", 1, fired)
    }

    @Test fun settlingIsIdempotentWithinOneGeneration() {
        BuiltinDashboard.onRendererSettled(owner)
        var fired = 0
        BuiltinDashboard.setRendererSettledListener { fired++ }
        BuiltinDashboard.onRendererSettled(owner)
        BuiltinDashboard.onRendererSettled(owner)
        assertEquals("repeat reports must not re-fire the demand evaluation", 1, fired)
    }

    @Test fun settlementDiesWithItsRenderer() {
        BuiltinDashboard.onRendererSettled(owner)
        BuiltinDashboard.releaseActivityOwner(owner)
        assertFalse("a released renderer takes its settlement with it", BuiltinDashboard.rendererSettled)
        owner = BuiltinDashboard.acquireActivityOwner()
        assertFalse("and the next generation must earn its own", BuiltinDashboard.rendererSettled)
    }

    /**
     * Android's overlapping replacement, the other way around: the predecessor SETTLED and never got
     * to release before the successor acquired. Without the acquire-side reset the successor inherits
     * a settlement it has not earned, and launch-deferred work runs during its launch.
     */
    @Test fun predecessorSettlementDoesNotCarryIntoAnOverlappingReplacement() {
        BuiltinDashboard.onRendererSettled(owner)
        assertTrue(BuiltinDashboard.rendererSettled)
        owner = BuiltinDashboard.acquireActivityOwner() // replacement acquires; predecessor never released
        assertFalse("a new generation must earn its own settlement", BuiltinDashboard.rendererSettled)
    }

    @Test fun aPredecessorsLateReleaseCannotUnsettleALiveReplacement() {
        val stale = owner
        owner = BuiltinDashboard.acquireActivityOwner() // replacement overlaps, as Android allows
        BuiltinDashboard.onRendererSettled(owner)
        BuiltinDashboard.releaseActivityOwner(stale)
        assertTrue("only the owning generation may reset settlement", BuiltinDashboard.rendererSettled)
    }

    @Test fun clearingTheListenerIsIdentitySafe() {
        var stale = 0
        var live = 0
        val staleListener: () -> Unit = { stale++ }
        val liveListener: () -> Unit = { live++ }
        BuiltinDashboard.setRendererSettledListener(staleListener)
        BuiltinDashboard.setRendererSettledListener(liveListener)
        // The stopped service clears ITS listener; the replacement's registration must survive.
        BuiltinDashboard.clearRendererSettledListener(staleListener)
        BuiltinDashboard.onRendererSettled(owner)
        assertEquals(0, stale)
        assertEquals("the live listener still hears the settle", 1, live)

        BuiltinDashboard.clearRendererSettledListener(liveListener)
        owner.let { BuiltinDashboard.releaseActivityOwner(it) }
        owner = BuiltinDashboard.acquireActivityOwner()
        BuiltinDashboard.onRendererSettled(owner)
        assertEquals("a cleared listener is never called again", 1, live)
    }

    /**
     * The same overlap seen from the settle side. A predecessor whose frontend connects LATE — after
     * Android has already handed the generation to its replacement — must not settle on the
     * replacement's behalf, or launch-deferred work runs during a launch that has not finished.
     */
    @Test fun aPredecessorsLateSettleCannotSettleTheGenerationThatReplacedIt() {
        val stale = owner
        owner = BuiltinDashboard.acquireActivityOwner()
        var fired = 0
        BuiltinDashboard.setRendererSettledListener { fired++ }

        BuiltinDashboard.onRendererSettled(stale)
        assertFalse("a superseded renderer cannot settle the live generation", BuiltinDashboard.rendererSettled)
        assertEquals(0, fired)

        BuiltinDashboard.onRendererSettled(owner)
        assertTrue(BuiltinDashboard.rendererSettled)
        assertEquals(1, fired)
    }
}
