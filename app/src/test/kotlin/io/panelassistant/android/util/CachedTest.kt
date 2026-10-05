package io.panelassistant.android.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger

class CachedTest {
    @Test fun peekNeverBuildsAndGetCachesWithinTtl() {
        val builds = AtomicInteger()
        val c = Cached(60_000) { builds.incrementAndGet() }
        assertNull(c.peek())
        assertEquals(0, builds.get())
        assertEquals(1, c.get())
        assertEquals(1, c.get())            // fresh → no rebuild
        assertEquals(1, builds.get())
        assertEquals(1, c.peek())
    }

    @Test fun requestScopedSupplierBuildsOnlyWhenCacheIsExpired() {
        var now = 100L
        var defaultBuilds = 0
        var overrideBuilds = 0
        val c = Cached(10, nowMs = { now }) { defaultBuilds += 1; "default" }

        assertEquals("routed", c.getWithSupplier { overrideBuilds += 1; "routed" })
        assertEquals("routed", c.getWithSupplier { overrideBuilds += 1; "ignored" })
        assertEquals(1, overrideBuilds)
        assertEquals(0, defaultBuilds)

        now = 111L
        assertEquals("default", c.get())
        assertEquals(1, defaultBuilds)
    }

    @Test fun requestScopedSupplierRetainsSingleFlight() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val c = Cached(60_000) { "default" }
        val results = java.util.concurrent.CopyOnWriteArrayList<String>()
        val first = Thread {
            results += c.getWithSupplier {
                entered.countDown()
                release.await()
                "first"
            }
        }.apply { start() }
        assertTrue(entered.await(2, java.util.concurrent.TimeUnit.SECONDS))
        val second = Thread { results += c.getWithSupplier { "second" } }.apply { start() }
        release.countDown()
        first.join(2_000L)
        second.join(2_000L)

        assertEquals(listOf("first", "first"), results.sorted())
        assertEquals("first", c.peek())
    }

    @Test fun invalidateForcesRebuildButPeekKeepsLastKnown() {
        val builds = AtomicInteger()
        val c = Cached(60_000) { builds.incrementAndGet() }
        c.get()
        c.invalidate()
        assertEquals(1, c.peek())           // stale value still readable without blocking
        assertEquals(2, c.get())
    }

    @Test fun expiredTtlRebuilds() {
        val builds = AtomicInteger()
        val c = Cached(1) { builds.incrementAndGet() }
        c.get()
        Thread.sleep(10)
        assertEquals(2, c.get())
    }

    @Test fun concurrentGetsSingleFlight() {
        val builds = AtomicInteger()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val c = Cached(60_000) {
            entered.countDown()
            release.await()
            builds.incrementAndGet()
        }
        val results = java.util.concurrent.CopyOnWriteArrayList<Int>()
        val threads = (1..8).map { Thread { results.add(c.get()) }.apply { start() } }
        assertTrue(entered.await(2, java.util.concurrent.TimeUnit.SECONDS))
        release.countDown()                 // one builder runs; the other 7 wait and reuse
        threads.forEach { it.join(5_000) }
        assertEquals(8, results.size)
        assertTrue(results.all { it == 1 })
        assertEquals(1, builds.get())
    }

    @Test fun backwardClockMovementExpiresInsteadOfPinningAStaleValue() {
        var now = 10_000L
        val builds = AtomicInteger()
        val c = Cached(60_000, nowMs = { now }) { builds.incrementAndGet() }
        assertEquals(1, c.get())
        now = 1_000L
        assertEquals(Long.MAX_VALUE, c.ageMs())
        assertEquals(2, c.get())
    }

    @Test fun setAndInvalidateUseTheInjectedMonotonicClock() {
        var now = 100L
        val c = Cached(10, nowMs = { now }) { "built" }
        c.set("known")
        now = 105L
        assertEquals(5L, c.ageMs())
        assertEquals("known", c.get())
        c.invalidate()
        assertEquals(Long.MAX_VALUE, c.ageMs())
        assertEquals("built", c.get())
    }

    @Test fun staleWhileRevalidateReturnsImmediatelyAndAdmitsOnlyOneRefresh() {
        var now = 100L
        val builds = AtomicInteger()
        val c = Cached(10, nowMs = { now }) { builds.incrementAndGet() }
        assertEquals(1, c.get())
        now = 111L
        val admitted = mutableListOf<() -> Unit>()

        assertEquals(1, c.staleWhileRevalidate { refresh, _ -> admitted.add(refresh) })
        assertEquals(1, c.staleWhileRevalidate { refresh, _ -> admitted.add(refresh) })
        assertEquals(1, admitted.size)
        assertEquals(1, builds.get())

        admitted.single().invoke()
        assertEquals(2, builds.get())
        assertEquals(2, c.peek())
    }

    @Test fun failedRefreshReleasesAdmissionForTheNextAttempt() {
        var now = 100L
        val builds = AtomicInteger()
        var fail = false
        val c = Cached(10, nowMs = { now }) {
            builds.incrementAndGet().also { if (fail) error("probe failed") }
        }
        assertEquals(1, c.get())
        now = 111L
        fail = true
        var first: (() -> Unit)? = null
        assertEquals(1, c.staleWhileRevalidate { refresh, _ -> first = refresh; true })
        assertThrows(IllegalStateException::class.java) { first!!.invoke() }

        fail = false
        var second: (() -> Unit)? = null
        assertEquals(1, c.staleWhileRevalidate { refresh, _ -> second = refresh; true })
        second!!.invoke()
        assertEquals(3, builds.get())
        assertEquals(3, c.peek())
    }

    @Test fun rejectedRefreshLaunchDoesNotPermanentlyConsumeAdmission() {
        var now = 100L
        val c = Cached(10, nowMs = { now }) { "value" }
        c.get()
        now = 111L

        assertEquals("value", c.staleWhileRevalidate { _, _ -> false })
        var admitted = false
        assertEquals("value", c.staleWhileRevalidate { _, _ -> admitted = true; true })
        assertTrue(admitted)
    }

    @Test fun completionWithoutRunningRefreshReleasesAdmission() {
        var now = 100L
        val c = Cached(10, nowMs = { now }) { "value" }
        c.get()
        now = 111L
        var completion: (() -> Unit)? = null
        assertEquals("value", c.staleWhileRevalidate { _, release -> completion = release; true })
        completion!!.invoke()

        var admitted = false
        assertEquals("value", c.staleWhileRevalidate { _, _ -> admitted = true; true })
        assertTrue(admitted)
    }

    @Test fun oldCompletionCannotReleaseANewerRefreshAdmission() {
        var now = 100L
        val builds = AtomicInteger()
        val c = Cached(10, nowMs = { now }) { builds.incrementAndGet() }
        c.get()
        now = 111L
        var firstRefresh: (() -> Unit)? = null
        var firstCompletion: (() -> Unit)? = null
        c.staleWhileRevalidate { refresh, release ->
            firstRefresh = refresh
            firstCompletion = release
            true
        }
        firstRefresh!!.invoke()
        now = 122L
        var secondAdmitted = false
        c.staleWhileRevalidate { _, _ -> secondAdmitted = true; true }
        assertTrue(secondAdmitted)

        firstCompletion!!.invoke()
        var thirdAdmitted = false
        c.staleWhileRevalidate { _, _ -> thirdAdmitted = true; true }
        assertFalse(thirdAdmitted)
    }

    @Test fun invalidationDuringBuildCannotBeLost() {
        var now = 100L
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val builds = AtomicInteger()
        var block = false
        val c = Cached(10, nowMs = { now }) {
            if (block) {
                entered.countDown()
                release.await()
            }
            builds.incrementAndGet()
        }
        assertEquals(1, c.get())
        now = 111L
        block = true
        val worker = Thread { c.get() }.apply { start() }
        assertTrue(entered.await(2, java.util.concurrent.TimeUnit.SECONDS))
        c.invalidate()
        release.countDown()
        worker.join(2_000L)
        block = false

        assertEquals(Long.MAX_VALUE, c.ageMs())
        assertEquals(3, c.get())
    }
}
