package io.github.maxlyth.hapaneld.http

import io.github.maxlyth.hapaneld.control.TameReconcileResult
import io.github.maxlyth.hapaneld.util.LatestDispatcher
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TameReconcileAuthorityTest {
    @Test fun `close wakes retry backoff without waiting for its delay`() {
        val returned = CountDownLatch(1)
        val calls = AtomicInteger()
        val worker = AtomicReference<Thread>()
        val owner = TameReconcileAuthority(
            readDesired = { setOf("vendor.one") },
            reconcile = { _, _ ->
                worker.set(Thread.currentThread())
                calls.incrementAndGet()
                returned.countDown()
                TameReconcileResult(1, retryableFailure = true)
            },
            stopping = { false },
            retryDelayMs = 60_000,
        )
        try {
            owner.request()
            assertTrue(returned.await(2, TimeUnit.SECONDS))
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
            while (worker.get().state != Thread.State.TIMED_WAITING && System.nanoTime() < deadline) Thread.yield()
            assertEquals("the retry is actually waiting before close", Thread.State.TIMED_WAITING, worker.get().state)
            assertTrue(owner.closeAndJoin(2_000))
            assertEquals(1, calls.get())
        } finally {
            owner.closeAndJoin(2_000)
            worker.get()?.interrupt()
        }
    }

    @Test fun `backlog telemetry clears when queued work is taken and completed`() {
        val firstEntered = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val completed = CountDownLatch(2)
        val calls = AtomicInteger()
        val backlogs = Collections.synchronizedList(mutableListOf<Int>())
        val owner = TameReconcileAuthority(
            readDesired = { setOf("vendor.one") },
            reconcile = { _, _ ->
                if (calls.incrementAndGet() == 1) {
                    firstEntered.countDown()
                    releaseFirst.await(2, TimeUnit.SECONDS)
                }
                completed.countDown()
                TameReconcileResult(1, false)
            },
            stopping = { false },
            retryDelayMs = 1,
            onBacklogChanged = backlogs::add,
        )

        owner.request()
        assertTrue(firstEntered.await(2, TimeUnit.SECONDS))
        owner.request()
        assertTrue(backlogs.toList().contains(1))
        releaseFirst.countDown()
        assertTrue(completed.await(2, TimeUnit.SECONDS))
        assertTrue(owner.closeAndJoin(2_000))
        assertEquals(0, backlogs.toList().last())
    }

    @Test fun `concurrent commits are resolved from newest durable desired value`() {
        val desired = AtomicReference(setOf("vendor.old"))
        val firstEntered = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val converged = CountDownLatch(1)
        val observed = mutableListOf<Set<String>>()
        val owner = TameReconcileAuthority(
            readDesired = desired::get,
            reconcile = { value, _ ->
                synchronized(observed) { observed += value }
                if (value == setOf("vendor.old")) {
                    firstEntered.countDown()
                    releaseFirst.await(2, TimeUnit.SECONDS)
                }
                if (value == setOf("vendor.new")) converged.countDown()
                TameReconcileResult(1, false)
            },
            stopping = { false },
            retryDelayMs = 1,
        )

        owner.request()
        assertTrue(firstEntered.await(2, TimeUnit.SECONDS))
        desired.set(setOf("vendor.new"))
        owner.request()
        releaseFirst.countDown()
        assertTrue(converged.await(2, TimeUnit.SECONDS))
        assertTrue(owner.closeAndJoin(2_000))
        assertEquals(setOf("vendor.new"), synchronized(observed) { observed.last() })
    }

    @Test fun `action failure retries inside the one owner`() {
        val attempts = CountDownLatch(2)
        var calls = 0
        val owner = TameReconcileAuthority(
            readDesired = { setOf("vendor.one") },
            reconcile = { _, _ ->
                calls++
                attempts.countDown()
                TameReconcileResult(1, retryableFailure = calls == 1)
            },
            stopping = { false },
            retryDelayMs = 1,
        )

        owner.request()
        assertTrue(attempts.await(2, TimeUnit.SECONDS))
        assertTrue(owner.closeAndJoin(2_000))
        assertEquals(2, calls)
    }

    @Test fun `persistent privileged failure has bounded local retries`() {
        val calls = AtomicInteger()
        val exhausted = CountDownLatch(4)
        val owner = TameReconcileAuthority(
            readDesired = { setOf("vendor.one") },
            reconcile = { _, _ ->
                calls.incrementAndGet()
                exhausted.countDown()
                TameReconcileResult(1, retryableFailure = true)
            },
            stopping = { false },
            retryDelayMs = 1,
        )

        owner.request()
        assertTrue(exhausted.await(2, TimeUnit.SECONDS))
        Thread.sleep(25)
        assertEquals(4, calls.get())
        assertTrue(owner.closeAndJoin(2_000))
    }

    @Test fun `single reconcile key coalesces instead of rejecting and closed work hands off to startup`() {
        val desired = AtomicReference(setOf("vendor.one"))
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val first = TameReconcileAuthority(
            readDesired = desired::get,
            reconcile = { _, _ ->
                entered.countDown()
                release.await(2, TimeUnit.SECONDS)
                TameReconcileResult(1, false)
            },
            stopping = { false },
        )
        assertEquals(LatestDispatcher.Admission.ACCEPTED, first.request())
        assertTrue(entered.await(2, TimeUnit.SECONDS))
        repeat(20) {
            assertNotEquals(LatestDispatcher.Admission.REJECTED, first.request())
        }
        release.countDown()
        assertTrue(first.closeAndJoin(2_000))
        assertEquals(LatestDispatcher.Admission.CLOSED, first.request())

        val startupConverged = CountDownLatch(1)
        val restarted = TameReconcileAuthority(
            readDesired = desired::get,
            reconcile = { desired, _ ->
                if (desired == setOf("vendor.one")) startupConverged.countDown()
                TameReconcileResult(1, false)
            },
            stopping = { false },
        )
        restarted.request()
        assertTrue(startupConverged.await(2, TimeUnit.SECONDS))
        assertTrue(restarted.closeAndJoin(2_000))
    }
}
