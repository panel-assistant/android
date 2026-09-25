package io.github.maxlyth.hapaneld.util

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Absorbs the union of the three former dispatcher tests (ConflatedWorker, BoundedLatestDispatcher,
 * KeyedLatestDispatcher) plus the pins the merge review required: single-slot never REJECTs, the
 * onDiscard hook runs outside the lock, the interrupting close interrupts before it joins, and
 * awaitTermination completes on a single-slot dispatcher (the notifyAll-in-finally the old
 * ConflatedWorker lacked). These are proven on the unified class before any call site migrates.
 */
class LatestDispatcherTest {
    // --- single-slot (former ConflatedWorker / BoundedLatestDispatcher) -------------------------

    @Test fun singleSlotRetainsOnlyLatestPendingAndNeverRejects() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val secondFinished = CountDownLatch(1)
        val consumed = mutableListOf<Int>()
        val d = LatestDispatcher.singleSlot<Int>("t", { v ->
            synchronized(consumed) { consumed += v }
            when (v) {
                1 -> { started.countDown(); release.await(2, TimeUnit.SECONDS) }
                3 -> secondFinished.countDown()
            }
        })
        try {
            assertEquals(LatestDispatcher.Admission.ACCEPTED, d.submit(1))
            assertTrue(started.await(2, TimeUnit.SECONDS))
            // 1 is running (not pending): a fresh submit is ACCEPTED, a burst then COALESCEs — never REJECTED.
            assertEquals(LatestDispatcher.Admission.ACCEPTED, d.submit(2))
            assertEquals(LatestDispatcher.Admission.COALESCED, d.submit(3))
            assertEquals(1, d.pendingCount())
            release.countDown()
            assertTrue(secondFinished.await(2, TimeUnit.SECONDS))   // 2 was coalesced away; only 1 and 3 run
            assertEquals(listOf(1, 3), synchronized(consumed) { consumed.toList() })
        } finally { d.close() }
        assertEquals(LatestDispatcher.Admission.CLOSED, d.submit(4))
    }

    @Test fun singleSlotFloodDiscardsReplacedValuesOutsideConsume() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val consumed = mutableListOf<Int>()
        val discarded = mutableListOf<Int>()
        val d = LatestDispatcher.singleSlot<Int>("t",
            consume = { v ->
                synchronized(consumed) { consumed += v }
                if (v == 0) { entered.countDown(); release.await(5, TimeUnit.SECONDS) } else finished.countDown()
            },
            onDiscard = { v -> synchronized(discarded) { discarded += v } })
        d.submit(0)
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        for (i in 1..1_000) d.submit(i)   // only the last survives as pending; the rest are discarded
        assertEquals(1, d.pendingCount())
        release.countDown()
        assertTrue(finished.await(5, TimeUnit.SECONDS))
        assertEquals(listOf(0, 1_000), synchronized(consumed) { consumed.toList() })
        assertEquals((1 until 1_000).toList(), synchronized(discarded) { discarded.sorted() })
        d.close()
    }

    @Test fun politeCloseDrainsPendingToDiscardAndAwaitTerminationCompletes() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val consumed = mutableListOf<Int>()
        val discarded = mutableListOf<Int>()
        val d = LatestDispatcher.singleSlot<Int>("t",
            consume = { v -> synchronized(consumed) { consumed += v }; if (v == 1) { entered.countDown(); release.await(5, TimeUnit.SECONDS) } },
            onDiscard = { v -> synchronized(discarded) { discarded += v } })
        d.submit(1)
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        d.submit(2)                       // becomes pending behind the running item
        d.close()                          // polite: drains pending (2) to onDiscard, does not interrupt
        assertEquals(LatestDispatcher.Admission.CLOSED, d.submit(3))
        release.countDown()
        assertTrue(d.awaitTermination(5_000L))   // completes only because runLoop's finally notifies
        assertEquals(listOf(1), synchronized(consumed) { consumed.toList() })
        // 2 drained by close(), 3 discarded because it arrived after close (CLOSED)
        assertEquals(listOf(2, 3), synchronized(discarded) { discarded.toList() })
        assertEquals(0, d.pendingCount())
    }

    @Test fun awaitTerminationIsBoundedWhileTheRunningItemFinishes() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val d = LatestDispatcher.singleSlot<Int>("t", { if (it == 1) { entered.countDown(); release.await(5, TimeUnit.SECONDS) } })
        d.submit(1)
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        d.close()
        assertFalse(d.awaitTermination(10L))     // running item still blocked
        release.countDown()
        assertTrue(d.awaitTermination(5_000L))
    }

    @Test fun interruptedAwaitReturnsPromptlyAndRestoresCallerInterrupt() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val result = java.util.concurrent.atomic.AtomicReference<Boolean>()
        val callerInterrupted = java.util.concurrent.atomic.AtomicBoolean()
        val d = LatestDispatcher.singleSlot<Unit>("t", {
            entered.countDown()
            release.await(5, TimeUnit.SECONDS)
        })
        d.submit(Unit)
        assertTrue(entered.await(2, TimeUnit.SECONDS))
        d.close()
        val waiter = Thread {
            result.set(d.awaitTermination(5_000L))
            callerInterrupted.set(Thread.currentThread().isInterrupted)
        }.apply { start() }
        waiter.interrupt()
        waiter.join(1_000L)
        assertFalse("interrupted wait returned promptly", waiter.isAlive)
        assertEquals(false, result.get())
        assertTrue("interrupt flag restored before returning", callerInterrupted.get())
        release.countDown()
        assertTrue(d.awaitTermination(5_000L))
    }

    // The item ignores interruption until released after the assertions, so an unbounded join hangs.
    @Test(timeout = 10_000)
    fun closeAndJoinIsBoundedThenSucceedsAfterDrain() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        // interrupt-resistant consumer: the bounded closeAndJoin must return within budget even when
        // the running item ignores interruption (the stuck-I/O case the bound exists for).
        val d = LatestDispatcher.singleSlot<Unit>("t", {
            started.countDown()
            while (release.count > 0L) { try { release.await(5, TimeUnit.SECONDS) } catch (_: InterruptedException) {} }
        })
        d.submit(Unit)
        assertTrue(started.await(2, TimeUnit.SECONDS))
        assertFalse(d.closeAndJoin(0L))          // zero budget returns, not blocking on the stuck item
        assertFalse(d.closeAndJoin(20))
        assertEquals(LatestDispatcher.Admission.CLOSED, d.submit(Unit))
        release.countDown()
        assertTrue(d.closeAndJoin(1_000))
    }

    // --- keyed (former KeyedLatestDispatcher) --------------------------------------------------

    @Test fun keyedBoundsPerKeyRetainsOrderAndRejectsWhenFull() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val consumedThree = CountDownLatch(3)
        val seen = mutableListOf<Pair<String, Int>>()
        val d = LatestDispatcher<String, Int>("t", maxPendingKeys = 2, consume = { k, v ->
            synchronized(seen) { seen += k to v }
            if (k == "running") { entered.countDown(); release.await(2, TimeUnit.SECONDS) }
            consumedThree.countDown()
        })
        try {
            assertEquals(LatestDispatcher.Admission.ACCEPTED, d.submit("running", 0))
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            assertEquals(LatestDispatcher.Admission.ACCEPTED, d.submit("a", 1))
            assertEquals(LatestDispatcher.Admission.COALESCED, d.submit("a", 2))
            assertEquals(LatestDispatcher.Admission.ACCEPTED, d.submit("b", 3))
            assertEquals(LatestDispatcher.Admission.REJECTED, d.submit("c", 4))  // full: 2 pending keys
            release.countDown()
            assertTrue(consumedThree.await(2, TimeUnit.SECONDS))
            assertTrue(d.closeAndJoin(2_000))
            assertEquals(listOf("running" to 0, "a" to 2, "b" to 3), synchronized(seen) { seen.toList() })
        } finally { d.close() }
    }

    // --- merge-review pins ---------------------------------------------------------------------

    @Test fun interruptingCloseAndJoinUnblocksAnInterruptibleConsumer() {
        val entered = CountDownLatch(1)
        val interrupted = CountDownLatch(1)
        val d = LatestDispatcher.singleSlot<Unit>("t", {
            entered.countDown()
            try { Thread.sleep(10_000) } catch (_: InterruptedException) { interrupted.countDown() }
        })
        d.submit(Unit)
        assertTrue(entered.await(2, TimeUnit.SECONDS))
        assertTrue(d.closeAndJoin(2_000))                     // interrupts the sleeping consumer, then drains
        assertTrue(interrupted.await(1, TimeUnit.SECONDS))    // the consumer actually received the interrupt
    }

    @Test fun interruptingCloseSignalsWorkerBeforeRunningDiscardHook() {
        val consumeEntered = CountDownLatch(1)
        val consumerInterrupted = CountDownLatch(1)
        val discardEntered = CountDownLatch(1)
        val releaseDiscard = CountDownLatch(1)
        val closeResult = java.util.concurrent.atomic.AtomicReference<Boolean>()
        val d = LatestDispatcher.singleSlot<Int>("t",
            consume = {
                consumeEntered.countDown()
                try { Thread.sleep(10_000L) } catch (_: InterruptedException) { consumerInterrupted.countDown() }
            },
            onDiscard = {
                discardEntered.countDown()
                releaseDiscard.await(5, TimeUnit.SECONDS)
            },
        )
        d.submit(1)
        assertTrue(consumeEntered.await(2, TimeUnit.SECONDS))
        d.submit(2)
        val closer = Thread { closeResult.set(d.closeAndJoin(2_000L)) }.apply { start() }
        assertTrue(discardEntered.await(2, TimeUnit.SECONDS))
        assertTrue("running consumer was interrupted before discard callback", consumerInterrupted.await(1, TimeUnit.SECONDS))
        releaseDiscard.countDown()
        closer.join(2_000L)
        assertFalse(closer.isAlive)
        assertEquals(true, closeResult.get())
    }

    @Test fun consumerFailureIsContainedAndDoesNotStrandANewerPendingValue() {
        val firstEntered = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val failureReported = CountDownLatch(1)
        val secondConsumed = CountDownLatch(1)
        val reported = java.util.concurrent.atomic.AtomicReference<Exception>()
        val d = LatestDispatcher.singleSlot<Int>(
            threadName = "t",
            consume = { value ->
                if (value == 1) {
                    firstEntered.countDown()
                    releaseFirst.await(5, TimeUnit.SECONDS)
                    throw IllegalStateException("expected test failure")
                }
                if (value == 2) secondConsumed.countDown()
            },
            onFailure = { failure ->
                reported.set(failure)
                failureReported.countDown()
            },
        )
        d.submit(1)
        assertTrue(firstEntered.await(2, TimeUnit.SECONDS))
        d.submit(2)
        releaseFirst.countDown()
        assertTrue("consumer failure was contained and reported", failureReported.await(2, TimeUnit.SECONDS))
        assertTrue(reported.get() is IllegalStateException)
        assertTrue("pending work restarted without a third submit", secondConsumed.await(2, TimeUnit.SECONDS))
        assertTrue(d.closeAndJoin(2_000L))
    }

    @Test fun onDiscardRunsOutsideTheLockSoAHookMayReenterTheDispatcher() {
        val reentrantCount = java.util.concurrent.atomic.AtomicInteger(-1)
        lateinit var d: LatestDispatcher<Unit, Int>
        d = LatestDispatcher.singleSlot<Int>("t",
            consume = { Thread.sleep(50) },
            onDiscard = { reentrantCount.compareAndSet(-1, d.pendingCount()) }) // re-enters under no lock
        d.submit(1)                    // starts running
        d.submit(2)                    // pending
        d.submit(3)                    // replaces 2 -> onDiscard(2) re-enters pendingCount(); must not deadlock
        assertTrue("onDiscard hook re-entered the dispatcher without deadlock", reentrantCount.get() >= 0)
        assertTrue(d.closeAndJoin(2_000))
    }
}
