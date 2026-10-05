package io.panelassistant.android.util

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SingleFlightExecutorTest {
    @Test fun rejectsDuplicateWhileHardwareActionIsRunningAndAcceptsAfterCompletion() {
        val executor = SingleFlightExecutor("single-flight-test")
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val finished = CountDownLatch(1)
        try {
            assertTrue(executor.execute {
                entered.countDown()
                release.await()
                finished.countDown()
            })
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            assertFalse(executor.execute { error("duplicate action ran") })

            release.countDown()
            assertTrue(finished.await(2, TimeUnit.SECONDS))
            val next = CountDownLatch(1)
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
            var accepted = false
            while (!accepted && System.nanoTime() < deadline) {
                accepted = executor.execute { next.countDown() }
                if (!accepted) Thread.yield()
            }
            assertTrue(accepted)
            assertTrue(next.await(2, TimeUnit.SECONDS))
        } finally {
            release.countDown()
            executor.close(500)
        }
    }

    @Test fun closeReportsWhenAnInterruptIgnoringActionDoesNotTerminate() {
        val executor = SingleFlightExecutor("single-flight-close-test")
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        assertTrue(executor.execute {
            entered.countDown()
            while (release.count > 0L) {
                try {
                    release.await()
                } catch (_: InterruptedException) {
                    // Deliberately model a hardware call that does not respond to cancellation.
                }
            }
        })
        assertTrue(entered.await(2, TimeUnit.SECONDS))

        try {
            assertFalse(executor.closeAndJoin(10L))
        } finally {
            release.countDown()
        }
    }
}
