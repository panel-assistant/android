package io.github.maxlyth.hapaneld

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ShutdownProofOwnerTest {
    @Test fun successfulProofReturnsItsValueFromADaemonOwner() {
        val daemon = AtomicBoolean()

        val result = runBoundedShutdownProof(1_000) {
            daemon.set(Thread.currentThread().isDaemon)
            "proved"
        }

        assertEquals("proved", result)
        assertTrue(daemon.get())
    }

    // The proof ignores interruption until released in `finally`, so a finalizer that waited on it hangs.
    @Test(timeout = 10_000)
    fun blockingNativeLikeProofCannotHoldTheFinalizerPastItsDeadline() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val exited = CountDownLatch(1)
        val daemon = AtomicBoolean()
        try {
            val result = runBoundedShutdownProof(50) {
                daemon.set(Thread.currentThread().isDaemon)
                entered.countDown()
                while (release.count > 0L) {
                    try {
                        release.await()
                    } catch (_: InterruptedException) {
                        // Model a native call that ignores cancellation until its own operation returns.
                    }
                }
                exited.countDown()
                "too late"
            }

            assertNull(result)
            assertTrue(entered.await(1, TimeUnit.SECONDS))
            assertTrue(daemon.get())
        } finally {
            release.countDown()
            assertTrue(exited.await(1, TimeUnit.SECONDS))
        }
    }
}
