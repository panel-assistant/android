package io.github.maxlyth.hapaneld.upgrade

import android.content.Context
import android.content.ContextWrapper
import io.github.maxlyth.hapaneld.migration.AndroidBridgeReleasePorts
import io.github.maxlyth.hapaneld.persistence.CleanDatabaseProof
import io.github.maxlyth.hapaneld.persistence.StateQuiescence
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class UpgradeExitTest {
    @Test fun matchingClaimReceivesExactReasonBeforeCancellation() {
        val gate = UpgradeRequestGate()
        val completion = RecordingCompletion()
        assertTrue(gate.arm(NONCE, completion))
        val claim = checkNotNull(gate.claimShutdown())

        gate.notifyExitingProcess(claim, REASON)
        assertTrue("notification must leave cancellation to its caller", gate.isArmed())
        assertEquals(listOf("exit:$REASON"), completion.events)
        assertTrue(gate.cancelClaim(claim, REASON).matched)
        assertEquals(listOf("exit:$REASON", "failed:$REASON"), completion.events)
    }

    @Test fun nullClaimStillNotifiesTheArmedRequest() {
        for (alreadyClaimed in listOf(false, true)) {
            val gate = UpgradeRequestGate()
            val completion = RecordingCompletion()
            assertTrue(gate.arm(NONCE, completion))
            if (alreadyClaimed) checkNotNull(gate.claimShutdown())

            gate.notifyExitingProcess(null, REASON)

            assertEquals("null claim, claimed=$alreadyClaimed", listOf("exit:$REASON"), completion.events)
        }
    }

    @Test fun staleClaimCannotNotifyReplacementRequest() {
        val gate = UpgradeRequestGate()
        assertTrue(gate.arm(NONCE, RecordingCompletion()))
        val stale = checkNotNull(gate.claimShutdown())
        assertTrue(gate.cancelClaim(stale, "cancelled").matched)
        val replacement = RecordingCompletion()
        assertTrue(gate.arm("replacement", replacement))
        checkNotNull(gate.claimShutdown())

        gate.notifyExitingProcess(stale, REASON)

        assertEquals(emptyList<String>(), replacement.events)
    }

    @Test fun readyRequestIsNotRetiredAgain() {
        val gate = UpgradeRequestGate()
        val completion = RecordingCompletion()
        assertTrue(gate.arm(NONCE, completion))
        val claim = checkNotNull(gate.claimShutdown())
        assertTrue(gate.holdReady(claim, StateQuiescence {}, CleanDatabaseProof(4096, "ab".repeat(32), 14, 1)) {})

        gate.notifyExitingProcess(claim, REASON)
        gate.notifyExitingProcess(null, REASON)

        assertEquals(listOf("ready:$NONCE"), completion.events)
    }

    @Test fun exitCallbackRunsOutsideTheGateMonitor() {
        val gate = UpgradeRequestGate()
        var called = false
        assertTrue(gate.arm(NONCE, object : RecordingCompletion() {
            override fun exitingProcess(reason: String) {
                assertFalse("retirement must not hold the request gate", Thread.holdsLock(gate))
                called = true
            }
        }))

        gate.notifyExitingProcess(checkNotNull(gate.claimShutdown()), REASON)

        assertTrue("the monitor check must execute", called)
    }

    @Test fun completionIsReadUnderTheGateMonitor() {
        val gate = UpgradeRequestGate()
        val completion = RecordingCompletion()
        assertTrue(gate.arm(NONCE, completion))
        val claim = checkNotNull(gate.claimShutdown())
        val started = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val worker = Thread {
            started.countDown()
            try {
                gate.notifyExitingProcess(claim, REASON)
            } finally {
                finished.countDown()
            }
        }
        try {
            synchronized(gate) {
                worker.start()
                assertTrue("worker started", started.await(5, TimeUnit.SECONDS))
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
                while (worker.state != Thread.State.BLOCKED && finished.count != 0L && System.nanoTime() < deadline) {
                    Thread.sleep(1)
                }
                assertEquals("the completion read must wait for the gate", Thread.State.BLOCKED, worker.state)
                assertEquals("no callback before acquiring the gate", emptyList<String>(), completion.events)
            }
        } finally {
            worker.join(5000)
        }
        assertFalse("notification finished after the gate was released", worker.isAlive)
        assertEquals(listOf("exit:$REASON"), completion.events)
    }

    @Test fun failedShutdownNotifiesBeforeCancelling() {
        val context = StubContext()
        val retirement = RuntimeException("stand-in for process exit")
        val completion = object : RecordingCompletion() {
            override fun exitingProcess(reason: String) {
                super.exitingProcess(reason)
                assertTrue("request still armed when retirement starts", UpgradeShutdownCoordinator.isArmed())
                throw retirement
            }
        }
        assertTrue(UpgradeShutdownCoordinator.armWithoutWatchdog(NONCE, completion))
        try {
            val claim = checkNotNull(UpgradeShutdownCoordinator.claimShutdown())
            val thrown = runCatching {
                UpgradeShutdownCoordinator.failShutdown(context, claim, null, {}, REASON)
            }.exceptionOrNull()

            assertSame("retirement must be reached before cancellation", retirement, thrown)
            assertEquals(listOf("exit:$REASON"), completion.events)
        } finally {
            UpgradeShutdownCoordinator.cancelAndResume(context, NONCE, "test_cleanup")
        }
    }

    @Test fun bridgeExitUsesTheRetirementCallback() {
        val context = StubContext()
        val ports = AndroidBridgeReleasePorts(context)
        val retirement = RuntimeException("stand-in for process exit")
        var retired = 0
        try {
            // Exercise the real completion installed by the Android port. The JVM Handler stub
            // does not run delayed service work, so the test supplies the shutdown failure itself.
            assertTrue(ports.beginQuiesce { retired++; throw retirement })
            val thrown = runCatching {
                UpgradeShutdownCoordinator.failShutdown(context, null, null, {}, REASON)
            }.exceptionOrNull()

            assertSame("the bridge must complete retirement on process exit", retirement, thrown)
            assertEquals(1, retired)
        } finally {
            ports.resumeBridge()
        }
    }

    private open class RecordingCompletion : UpgradeRequestCompletion {
        val events = mutableListOf<String>()
        override fun ready(nonce: String, proof: CleanDatabaseProof) { events += "ready:$nonce" }
        override fun failed(reason: String) { events += "failed:$reason" }
        override fun exitingProcess(reason: String) { events += "exit:$reason" }
    }

    private class StubContext : ContextWrapper(null) {
        override fun getApplicationContext(): Context = this
    }

    private companion object {
        const val NONCE = "0123456789abcdef0123456789abcdef"
        const val REASON = "shutdown_checkpoint_failed"
    }
}
