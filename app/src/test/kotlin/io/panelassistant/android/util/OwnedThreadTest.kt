package io.panelassistant.android.util

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OwnedThreadTest {
    @Test fun startRunsBodyOnADaemonThreadWithTheGivenName() {
        val started = CountDownLatch(1)
        val observedName = AtomicReference<String>()
        val observedDaemon = AtomicBoolean(false)
        val owned = OwnedThread("owned-thread-test") {
            observedName.set(Thread.currentThread().name)
            observedDaemon.set(Thread.currentThread().isDaemon)
            started.countDown()
        }
        owned.start()
        assertTrue(started.await(2, TimeUnit.SECONDS))
        assertEquals("owned-thread-test", observedName.get())
        assertTrue(observedDaemon.get())
        owned.stop(1_000L)
    }

    @Test fun stopInterruptsTheWorkerAndJoinsIt() {
        val entered = CountDownLatch(1)
        val interrupted = AtomicBoolean(false)
        val exited = CountDownLatch(1)
        val owned = OwnedThread("owned-stop-test") {
            entered.countDown()
            try {
                Thread.sleep(60_000L)
            } catch (_: InterruptedException) {
                interrupted.set(true)
            } finally {
                exited.countDown()
            }
        }
        owned.start()
        assertTrue(entered.await(2, TimeUnit.SECONDS))

        owned.stop(2_000L)

        assertTrue("worker must have exited after the join returned", exited.await(1, TimeUnit.SECONDS))
        assertTrue("worker must have observed the interrupt", interrupted.get())
    }

    // The worker stays wedged until after stop returns, so an unbounded join never returns.
    @Test(timeout = 10_000)
    fun stopJoinIsBoundedWhenTheWorkerIgnoresInterruption() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val owned = OwnedThread("owned-bounded-join-test") {
            entered.countDown()
            // Model a worker that swallows interruption while wedged.
            var released = false
            while (!released) {
                released = try {
                    release.await(20, TimeUnit.MILLISECONDS)
                } catch (_: InterruptedException) {
                    false
                }
            }
        }
        owned.start()
        assertTrue(entered.await(2, TimeUnit.SECONDS))

        owned.stop(200L)

        release.countDown() // let the test's worker finish
    }

    @Test fun onExitRunsWhenTheWorkerSelfCompletes() {
        val exitObserved = CountDownLatch(1)
        val owned = OwnedThread("owned-onexit-test", onExit = { exitObserved.countDown() }) {
            // returns immediately
        }
        owned.start()
        assertTrue("onExit must run on a self-completing worker", exitObserved.await(2, TimeUnit.SECONDS))
    }

    @Test fun stopIsANoOpBeforeAnyStart() {
        // No thread has been published, so stop must not throw.
        OwnedThread("owned-nostart-test") {}.stop(1_000L)
    }
}
