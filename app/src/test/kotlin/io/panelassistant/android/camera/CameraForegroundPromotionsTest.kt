package io.panelassistant.android.camera

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The interleavings that killed the process during a snapshot burst, and the ones review found would:
 * the previous service instance's destroy must not answer the next promotion; a stop must never be
 * issued while a start is unanswered, nor slip between a request and its start; an older start's
 * outcome must not answer the newest promotion; a timeout must not hide a grant that landed first; a
 * refused start must not swallow a stop deferred before it; and an ended session's release, which
 * routinely arrives after the next session has promoted, must not touch the newer session's standing.
 * Every rule here is the difference between a refused snapshot and a dead process.
 */
class CameraForegroundPromotionsTest {

    /** A registry with counted start and stop actions; [startAccepted] is what Android answers. */
    private class Harness(var startAccepted: Boolean = true) {
        val g = CameraForegroundPromotions()
        val starts = AtomicInteger()
        val stops = AtomicInteger()
        fun request(owner: Long = SESSION) = g.request(owner) { starts.incrementAndGet(); startAccepted }
        fun release(owner: Long = SESSION) = g.release(owner) { stops.incrementAndGet() }
        fun releaseAll() = g.releaseAll { stops.incrementAndGet() }
    }

    private companion object {
        /** The single session most cases model; identity only matters where two sessions overlap. */
        const val SESSION = 1L
    }

    private fun answer(p: CameraForegroundPromotions.Promotion): Boolean? = p.future.getNow(null)

    // ---- session identity: an ended session tearing down out of order ---------------------------

    @Test fun anEndedSessionsReleaseCannotWithdrawAPendingNewerRequest() {
        val h = Harness()
        val old = h.request(owner = 1L)
        assertTrue(h.g.started(true))
        assertEquals(true, answer(old))

        // The next session opens and asks for standing; its start has not been answered yet.
        val fresh = h.request(owner = 2L)
        assertTrue(h.g.hasUnansweredStart)

        // Only now does the ended session's finish run. It must do nothing at all.
        h.release(owner = 1L)
        assertEquals("no stop is issued for a session that no longer holds standing", 0, h.stops.get())
        assertTrue("the newer session's claim survives", h.g.isWanted)
        assertEquals(2L, h.g.standingHolder)

        assertTrue("so the pending start keeps the service rather than refusing itself", h.g.started(true))
        assertEquals("and the newer session is told it has standing", true, answer(fresh))
    }

    @Test fun anEndedSessionsReleaseCannotStopTheServiceCarryingAnAnsweredNewerRequest() {
        val h = Harness()
        h.request(owner = 1L)
        assertTrue(h.g.started(true))

        val fresh = h.request(owner = 2L)
        assertTrue("the newer session's start is answered and keeps the service", h.g.started(true))
        assertEquals(true, answer(fresh))

        // The ended session's finish arrives last, as it routinely does.
        h.release(owner = 1L)
        assertEquals("the live session's service is not stopped", 0, h.stops.get())
        assertTrue(h.g.isWanted)
        assertEquals(2L, h.g.standingHolder)

        // The holder's own release still works.
        h.release(owner = 2L)
        assertEquals(1, h.stops.get())
        assertFalse(h.g.isWanted)
        assertNull(h.g.standingHolder)
    }

    @Test fun theSubsystemStoppingTakesStandingFromWhoeverHoldsIt() {
        val h = Harness()
        h.request(owner = 7L)
        assertTrue(h.g.started(true))
        h.releaseAll()
        assertEquals("stopping outright does not need to know the holder", 1, h.stops.get())
        assertNull(h.g.standingHolder)
        assertFalse(h.g.isWanted)
    }

    @Test fun aRefusedStartRestoresThePreviousHolderSoItsOwnReleaseStillWorks() {
        val h = Harness()
        h.request(owner = 1L)
        assertTrue(h.g.started(true))
        h.startAccepted = false
        val refused = h.request(owner = 2L)
        assertEquals(false, answer(refused))
        assertEquals("the refused request never took the standing", 1L, h.g.standingHolder)
        h.release(owner = 2L)
        assertEquals("and cannot release what it never held", 0, h.stops.get())
        h.release(owner = 1L)
        assertEquals(1, h.stops.get())
    }

    // ---- the original burst crash and the earlier review findings -------------------------------

    @Test fun aServedInstancesDestroyDoesNotAnswerTheNextPromotion() {
        val h = Harness()
        val first = h.request()
        assertTrue("the first start is served and kept", h.g.started(true))
        assertEquals(true, answer(first))
        h.release()
        assertEquals("with nothing unanswered the stop is issued at once", 1, h.stops.get())

        val second = h.request()
        h.g.destroyed(served = true)
        assertNull("the old instance's destroy answers nothing: the new start is still pending", answer(second))
        assertTrue(h.g.hasUnansweredStart)
        assertTrue("the new instance's start keeps the service", h.g.started(true))
        assertEquals(true, answer(second))
    }

    @Test fun aStopIsDeferredWhileAStartIsUnansweredAndThatStartStopsItself() {
        val h = Harness()
        val p = h.request()
        h.release()
        assertEquals("a stop while the start is unanswered is deferred, never issued", 0, h.stops.get())
        assertFalse("the late start must stop itself", h.g.started(true))
        assertEquals("the promotion nobody wants is refused", false, answer(p))
        assertFalse(h.g.hasUnansweredStart)
    }

    @Test fun aTimedOutPromotionLeavesItsStartToStopItself() {
        val h = Harness()
        val p = h.request()
        assertFalse("a wait that runs out refuses the owner at once", h.g.await(p, 0L))
        h.release()
        assertEquals("the start is still outstanding, so the owner's stop is deferred", 0, h.stops.get())
        assertFalse("when the start finally runs it stops itself", h.g.started(true))
    }

    @Test fun aTimeoutThatLosesTheRaceToAGrantReportsTheGrant() {
        val h = Harness()
        val p = h.request()
        // The wait ran out (null), but the start answered before the decision was taken.
        assertTrue(h.g.started(true))
        assertTrue("the answer that landed before the decision is the answer", h.g.decide(p, null))
        assertTrue("and the service is not left unwanted by a wait that lost the race", h.g.isWanted)
    }

    @Test fun aTimeoutWithNoAnswerRefusesNowAndLeavesTheStartOutstanding() {
        val h = Harness()
        val p = h.request()
        assertFalse("nothing answered: refused at the decision", h.g.decide(p, null))
        assertEquals(false, answer(p))
        assertTrue("the start is still outstanding", h.g.hasUnansweredStart)
        // The refused owner demotes; the stop is deferred to the outstanding start, which honours it.
        h.release()
        assertEquals(0, h.stops.get())
        assertFalse("the late start stops itself", h.g.started(true))
    }

    @Test fun aNewRequestAfterADeferredStopKeepsTheService() {
        val h = Harness()
        val first = h.request()
        h.release()
        assertEquals(0, h.stops.get())
        val second = h.request()
        assertEquals("the superseded promotion is refused", false, answer(first))
        assertTrue("the older start keeps the service and leaves the answer to the newest", h.g.started(true))
        assertNull(answer(second))
        assertTrue("the newest start answers and the service stays", h.g.started(true))
        assertEquals(true, answer(second))
    }

    @Test fun aRefusedStartForegroundRefusesThePromotionAndStops() {
        val h = Harness()
        val p = h.request()
        assertFalse(h.g.started(false))
        assertEquals(false, answer(p))
        assertNull("nothing holds standing after a refusal", h.g.standingHolder)
    }

    @Test fun anOlderStartsFailedForegroundDoesNotAnswerTheNewestPromotion() {
        val h = Harness()
        h.request()
        val newest = h.request()
        assertTrue("the older start's failure keeps the service for the newer start", h.g.started(false))
        assertNull("and does not answer the promotion that belongs to the newer start", answer(newest))
        assertTrue("the newest start answers with its own outcome", h.g.started(true))
        assertEquals(true, answer(newest))
    }

    @Test fun anUnservedInstancesDestroyRefusesItsOwnStart() {
        val h = Harness()
        val p = h.request()
        h.g.destroyed(served = false)
        assertEquals("a start that died with its instance is refused", false, answer(p))
        h.release()
        assertEquals("nothing is outstanding, and nothing holds standing to stop", 0, h.stops.get())
    }

    @Test fun aStartAndroidRefusedSynchronouslyIsNotCounted() {
        val h = Harness(startAccepted = false)
        val p = h.request()
        assertEquals(1, h.starts.get())
        assertEquals(false, answer(p))
        assertFalse(h.g.hasUnansweredStart)
        h.release()
        assertEquals("nothing holds standing, so there is nothing to stop", 0, h.stops.get())
    }

    @Test fun aDeferredStopSurvivesASynchronousRefusalOfTheNextStart() {
        val h = Harness()
        h.request()
        h.release()
        assertEquals("deferred: the first start is unanswered", 0, h.stops.get())
        h.startAccepted = false
        val refused = h.request()
        assertEquals(false, answer(refused))
        assertFalse("a refused request restores the owner's previous word", h.g.isWanted)
        assertTrue("the first start is still outstanding", h.g.hasUnansweredStart)
        assertFalse("and it honours the deferred stop when it runs", h.g.started(true))
    }

    @Test fun anOlderStartNeverStopsTheServiceWhileANewerStartIsUnanswered() {
        val h = Harness()
        val first = h.request(owner = 1L)
        assertFalse(h.g.await(first, 0L))
        val second = h.request(owner = 2L)
        assertFalse(h.g.await(second, 0L))
        h.release(owner = 2L)
        assertEquals("nobody wants the service, but two starts are outstanding", 0, h.stops.get())
        assertTrue("the older start leaves the decision to the newer one, which alone can call startForeground for it", h.g.started(true))
        assertTrue(h.g.hasUnansweredStart)
        assertFalse("the newest start stops the service nobody wants", h.g.started(true))
        assertFalse(h.g.hasUnansweredStart)
    }

    @Test fun twoStartsInFlightAnswerTheNewestPromotionAndBothKeepAWantedService() {
        val h = Harness()
        val first = h.request(owner = 1L)
        assertFalse(h.g.await(first, 0L))
        val second = h.request(owner = 2L)
        assertTrue("the older start keeps the service", h.g.started(true))
        assertNull("only the newest start answers the newest promotion", answer(second))
        assertTrue("the newest start finds the service still wanted", h.g.started(true))
        assertEquals(true, answer(second))
        assertFalse(h.g.hasUnansweredStart)
        h.release(owner = 2L)
        assertEquals(1, h.stops.get())
    }

    @Test fun aRequestCannotSlipBetweenAReleasesDecisionAndItsStop() {
        val h = Harness()
        val first = h.request(owner = 1L)
        assertTrue(h.g.started(true))
        assertEquals(true, answer(first))

        val stopEntered = CountDownLatch(1)
        val letStopFinish = CountDownLatch(1)
        val order = mutableListOf<String>()
        var seenWhenStopReturned: List<String>? = null
        val releaser = Thread {
            h.g.release(1L) {
                synchronized(order) { order += "stop" }
                stopEntered.countDown()
                letStopFinish.await(5, TimeUnit.SECONDS)
                // Captured from inside the stop: whatever a concurrent request managed to do so far.
                seenWhenStopReturned = synchronized(order) { order.toList() }
            }
        }
        releaser.start()
        assertTrue(stopEntered.await(5, TimeUnit.SECONDS))

        // A request arriving while the stop is in flight must wait for it, so its start lands after.
        val requester = Thread {
            h.g.request(2L) { synchronized(order) { order += "start" }; true }
        }
        requester.start()
        // Give the request every chance to run before the stop returns: with the stop under the lock
        // it cannot, and this join simply runs out; without the lock it completes at once.
        requester.join(2_000)
        letStopFinish.countDown()
        releaser.join(5_000)
        requester.join(5_000)
        assertEquals("nothing of the request ran while the stop was in flight", listOf("stop"), seenWhenStopReturned)
        assertEquals("the start follows the stop, never interleaves with it", listOf("stop", "start"), synchronized(order) { order.toList() })
        assertTrue("and that start is counted, so a later release is deferred to it", h.g.hasUnansweredStart)
    }
}
