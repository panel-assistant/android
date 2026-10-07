package io.panelassistant.android.upgrade

import io.panelassistant.android.persistence.CleanDatabaseProof
import io.panelassistant.android.persistence.StateQuiescence
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class UpgradeShutdownCoordinatorTest {
    @Test fun onlyOneRequestCanOwnTheShutdownAndWrongNonceCannotCancelIt() {
        val gate = UpgradeRequestGate()
        val first = RecordingCompletion()

        assertTrue(gate.arm(NONCE, first))
        assertFalse(gate.arm(OTHER_NONCE, RecordingCompletion()))
        assertFalse(gate.cancel(OTHER_NONCE, "wrong_nonce").matched)
        assertTrue(gate.cancel(NONCE, "service_not_running").matched)
        assertEquals(listOf("failed:service_not_running"), first.events)
    }

    @Test fun cleanShutdownTransfersFreezeUntilTheHoldIsCancelled() {
        val gate = UpgradeRequestGate()
        val completion = RecordingCompletion()
        val reopened = AtomicBoolean()
        val successorReleased = AtomicBoolean()
        val freeze = StateQuiescence { reopened.set(true) }
        val proof = CleanDatabaseProof(8192, "cd".repeat(32), 14, 27)

        assertTrue(gate.arm(NONCE, completion))
        val claim = checkNotNull(gate.claimShutdown())
        assertTrue(gate.holdReady(claim, freeze, proof) { successorReleased.set(true) })
        assertFalse(reopened.get())
        assertFalse(successorReleased.get())
        assertEquals(listOf("ready:$NONCE"), completion.events)

        val released = gate.cancel(NONCE, "install_finished")
        assertTrue(released.matched)
        assertSame(freeze, released.freeze)
        released.releaseSuccessor?.invoke()
        released.freeze?.close()
        assertTrue(reopened.get())
        assertTrue(successorReleased.get())
    }

    @Test fun shutdownFailureAndWatchdogCancellationNeverReportReady() {
        val gate = UpgradeRequestGate()
        val completion = RecordingCompletion()

        assertTrue(gate.arm(NONCE, completion))
        val cancelled = gate.cancel(NONCE, "checkpoint_failed")

        assertTrue(cancelled.matched)
        assertNull(cancelled.freeze)
        assertEquals(listOf("failed:checkpoint_failed"), completion.events)
        assertFalse(completion.events.any { it.startsWith("ready:") })
    }

    @Test fun finalizerAlreadyInFlightCannotSatisfyOrCancelANewRequest() {
        val gate = UpgradeRequestGate()
        val completion = RecordingCompletion()
        val unbound = UpgradeShutdownClaim(Any())
        val freeze = StateQuiescence {}
        val proof = CleanDatabaseProof(4096, "ef".repeat(32), 14, 1)

        assertTrue(gate.arm(NONCE, completion))
        assertFalse(gate.holdReady(unbound, freeze, proof) {})
        assertFalse(gate.cancelClaim(unbound, "stale_finalizer").matched)
        assertTrue(completion.events.isEmpty())

        val claimed = checkNotNull(gate.claimShutdown())
        assertTrue(gate.holdReady(claimed, freeze, proof) {})
        assertEquals(listOf("ready:$NONCE"), completion.events)
    }

    @Test fun heldReleaseCompletesFreezeAndPredecessorBeforeRestart() {
        val events = mutableListOf<String>()

        releaseUpgradeHold(
            freeze = StateQuiescence { events += "freeze" },
            releaseSuccessor = { events += "barrier" },
            restartService = { events += "start" },
        )

        assertEquals(listOf("freeze", "barrier", "start"), events)
    }

    @Test fun releaseAttemptsEveryOrderedStepEvenWhenEachOneThrows() {
        val events = mutableListOf<String>()

        val failures = releaseUpgradeHold(
            freeze = StateQuiescence { events += "freeze"; error("freeze") },
            additionalFreeze = StateQuiescence { events += "additional"; error("additional") },
            releaseSuccessor = { events += "barrier"; error("barrier") },
            restartService = { events += "start"; error("start") },
        )

        assertEquals(listOf("freeze", "additional", "barrier", "start"), events)
        assertEquals(4, failures.size)
    }

    @Test fun watchdogFromCancelledHoldCannotCancelLaterHold() {
        val gate = readyGate(expiresAtMillis = 180)
        val released = gate.cancel(NONCE, "install_finished")
        assertTrue(released.matched)
        releaseUpgradeHold(released.freeze, releaseSuccessor = released.releaseSuccessor, restartService = null)
        // Even reuse of a nonce cannot give an earlier watchdog authority over the new deadline.
        assertTrue(gate.arm(NONCE, RecordingCompletion(), expiresAtMillis = 280))
        assertFalse(gate.cancel(NONCE, "watchdog_expired", expiredAtMillis = 180).matched)
        assertTrue(gate.cancel(NONCE, "watchdog_expired", expiredAtMillis = 280).matched)
    }

    private fun readyGate(expiresAtMillis: Long?): UpgradeRequestGate = UpgradeRequestGate().also { gate ->
        assertTrue(gate.arm(NONCE, RecordingCompletion(), expiresAtMillis))
        assertTrue(gate.holdReady(checkNotNull(gate.claimShutdown()), StateQuiescence {}, PROOF) {})
    }

    private class RecordingCompletion : UpgradeRequestCompletion {
        val events = mutableListOf<String>()
        override fun ready(nonce: String, proof: CleanDatabaseProof) {
            events += "ready:$nonce"
        }
        override fun failed(reason: String) {
            events += "failed:$reason"
        }
    }

    private companion object {
        const val NONCE = "0123456789abcdef0123456789abcdef"
        const val OTHER_NONCE = "fedcba9876543210fedcba9876543210"
        val PROOF = CleanDatabaseProof(4096, "ef".repeat(32), 14, 1)
    }
}
