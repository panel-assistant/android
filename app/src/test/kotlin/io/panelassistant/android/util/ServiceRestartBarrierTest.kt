package io.panelassistant.android.util

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ServiceRestartBarrierTest {
    @Test fun cleanTeardownOpensOnlyItsImmediateSuccessor() {
        val barrier = ServiceRestartBarrier()
        val first = barrier.enter()
        val replacement = barrier.enter()
        val third = barrier.enter()
        val firstStarted = CountDownLatch(1)
        val replacementStarted = CountDownLatch(1)
        val thirdStarted = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(3)

        try {
            val initialStartup = pool.submit {
                first.awaitPredecessor()
                firstStarted.countDown()
            }
            val replacementStartup = pool.submit {
                replacement.awaitPredecessor()
                replacementStarted.countDown()
            }
            val thirdStartup = pool.submit {
                third.awaitPredecessor()
                thirdStarted.countDown()
            }

            assertTrue(firstStarted.await(1, TimeUnit.SECONDS))
            initialStartup.get(1, TimeUnit.SECONDS)
            assertFalse(replacementStarted.await(150, TimeUnit.MILLISECONDS))
            assertFalse(thirdStarted.await(150, TimeUnit.MILLISECONDS))

            first.completeTeardown()
            first.completeTeardown()
            assertTrue(replacementStarted.await(1, TimeUnit.SECONDS))
            replacementStartup.get(1, TimeUnit.SECONDS)
            assertFalse(thirdStarted.await(150, TimeUnit.MILLISECONDS))

            replacement.completeTeardown()
            assertTrue(thirdStarted.await(1, TimeUnit.SECONDS))
            thirdStartup.get(1, TimeUnit.SECONDS)
        } finally {
            pool.shutdownNow()
        }
    }
}
