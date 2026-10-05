package io.panelassistant.android.util

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BoundedLaunchTest {
    // The launch stays wedged until after the assertion, so a caller that waited for it never returns.
    @Test(timeout = 10_000)
    fun launchThatIgnoresInterruptionCannotExtendTheCallerDeadline() {
        val launchEntered = CountDownLatch(1)
        val releaseLaunch = CountDownLatch(1)
        val destroyed = AtomicBoolean(false)
        val consumed = AtomicBoolean(false)
        val result = runBoundedLaunch(
            deadline = MonotonicDeadline(40L),
            threadName = "bounded-launch-test",
            gate = BoundedLaunchGate(),
            launch = {
                launchEntered.countDown()
                var released = false
                while (!released) {
                    try {
                        released = releaseLaunch.await(10, TimeUnit.MILLISECONDS)
                    } catch (_: InterruptedException) {
                        // Model Runtime.exec ignoring interruption while native process creation is wedged.
                    }
                }
                Any()
            },
            destroy = { destroyed.set(true) },
            consume = { consumed.set(true); "unexpected" },
        )
        assertTrue(launchEntered.await(1, TimeUnit.SECONDS))
        assertNull(result)
        assertFalse(consumed.get())
        releaseLaunch.countDown()
        val destroyDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1)
        while (!destroyed.get() && System.nanoTime() < destroyDeadline) Thread.yield()
        assertTrue("late-launched resource was not destroyed", destroyed.get())
        assertFalse(consumed.get())
    }

    @Test fun stuckLaunchConsumesTheOnlySlotUntilItsLateResourceIsDestroyed() {
        val gate = BoundedLaunchGate()
        val releaseLaunch = CountDownLatch(1)
        val lateDestroyed = AtomicBoolean(false)
        val first = runBoundedLaunch(
            deadline = MonotonicDeadline(30L),
            threadName = "bounded-launch-stuck-test",
            gate = gate,
            launch = {
                var released = false
                while (!released) {
                    try {
                        released = releaseLaunch.await(10, TimeUnit.MILLISECONDS)
                    } catch (_: InterruptedException) {
                        // Deliberately ignore cancellation.
                    }
                }
                Any()
            },
            destroy = { lateDestroyed.set(true) },
            consume = { "unexpected" },
        )
        assertNull(first)

        var secondLaunched = false
        val second = runBoundedLaunch(
            deadline = MonotonicDeadline(100L),
            threadName = "bounded-launch-rejected-test",
            gate = gate,
            launch = { secondLaunched = true; Any() },
            destroy = {},
            consume = { "unexpected" },
        )
        assertNull(second)
        assertFalse(secondLaunched)

        releaseLaunch.countDown()
        var third: String? = null
        val recoveryDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1)
        while (third == null && System.nanoTime() < recoveryDeadline) {
            third = runBoundedLaunch(
                deadline = MonotonicDeadline(100L),
                threadName = "bounded-launch-recovered-test",
                gate = gate,
                launch = { Any() },
                destroy = {},
                consume = { "ok" },
            )
        }
        assertTrue(lateDestroyed.get())
        assertEquals("ok", third)
    }

    @Test fun successorWaitsForARealWorkerExitInsteadOfLosingItsDialectAttempt() {
        val gate = BoundedLaunchGate()
        val firstEntered = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val firstDone = CountDownLatch(1)
        val firstThread = Thread {
            runBoundedLaunch(
                deadline = MonotonicDeadline(500L),
                threadName = "bounded-launch-first-dialect-test",
                gate = gate,
                launch = { Any() },
                destroy = {},
                consume = {
                    firstEntered.countDown()
                    releaseFirst.await()
                    "first"
                },
            )
            firstDone.countDown()
        }.apply { isDaemon = true; start() }

        assertTrue(firstEntered.await(1, TimeUnit.SECONDS))
        val releaser = Thread {
            Thread.sleep(50L)
            releaseFirst.countDown()
        }.apply { isDaemon = true; start() }

        var secondLaunched = false
        val second = runBoundedLaunch(
            deadline = MonotonicDeadline(500L),
            threadName = "bounded-launch-second-dialect-test",
            gate = gate,
            launch = { secondLaunched = true; Any() },
            destroy = {},
            consume = { "second" },
        )

        assertEquals("second", second)
        assertTrue(secondLaunched)
        assertTrue(firstDone.await(1, TimeUnit.SECONDS))
        firstThread.join(1_000L)
        releaser.join(1_000L)
    }
}
