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
    @Test fun nonceIsExactlyThirtyTwoLowercaseHexCharacters() {
        assertEquals("0123456789abcdef0123456789abcdef", canonicalUpgradeNonce("0123456789abcdef0123456789abcdef"))
        assertNull(canonicalUpgradeNonce("0123456789ABCDEF0123456789ABCDEF"))
        assertNull(canonicalUpgradeNonce("0123456789abcdef0123456789abcde"))
        assertNull(canonicalUpgradeNonce("0123456789abcdef0123456789abcdeg"))
        assertNull(canonicalUpgradeNonce(" 0123456789abcdef0123456789abcdef"))
    }

    @Test fun readyWireResultIsExactAndContainsStableDatabaseEvidence() {
        val proof = CleanDatabaseProof(
            databaseBytes = 4096,
            sha256 = "ab".repeat(32),
            userVersion = 14,
            appStateRows = 133,
        )

        assertEquals(
            "HAPANELD_UPGRADE_READY_V1:0123456789abcdef0123456789abcdef:321:522:4096:" +
                "${"ab".repeat(32)}:14:133",
            formatUpgradeReady("0123456789abcdef0123456789abcdef", 321, 522, proof),
        )
        assertEquals(
            "HAPANELD_UPGRADE_RELEASED_V1:0123456789abcdef0123456789abcdef",
            formatUpgradeReleased("0123456789abcdef0123456789abcdef"),
        )
    }

    @Test fun onlyOneRequestCanOwnTheShutdownAndWrongNonceCannotReleaseIt() {
        val gate = UpgradeRequestGate()
        val first = RecordingCompletion()

        assertTrue(gate.arm(NONCE, first))
        assertFalse(gate.arm(OTHER_NONCE, RecordingCompletion()))
        assertFalse(gate.release(OTHER_NONCE).matched)
        assertTrue(gate.release(NONCE).matched)
        assertEquals(listOf("failed:released_before_ready"), first.events)
    }

    @Test fun cleanShutdownTransfersFreezeUntilMatchingRelease() {
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

        val released = gate.release(NONCE)
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

    @Test fun releaseIsStatelessWhenNoReadyProcessStateSurvives() {
        val gate = UpgradeRequestGate()
        assertTrue(gate.release(NONCE).matched)
        assertTrue(gate.release(NONCE).matched)
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

    @Test fun statelessReleaseIsTruthfulAboutRestartSuccessAndFailure() {
        val successful = executeUpgradeRelease(UpgradeRequestGate(), NONCE) {}
        assertTrue(successful.succeeded)

        val failed = executeUpgradeRelease(UpgradeRequestGate(), NONCE) { error("start") }
        assertTrue(failed.accepted)
        assertFalse(failed.succeeded)
        assertEquals(1, failed.failures.size)
    }

    @Test fun liveDifferentNonceIsNotReportedAsReleased() {
        val gate = UpgradeRequestGate()
        assertTrue(gate.arm(NONCE, RecordingCompletion()))
        val outcome = executeUpgradeRelease(gate, OTHER_NONCE) {}
        assertFalse(outcome.accepted)
        assertFalse(outcome.succeeded)
    }

    @Test fun renewalWireResultIsExact() {
        assertEquals("HAPANELD_UPGRADE_RENEWED_V1:$NONCE", formatUpgradeRenewed(NONCE))
    }

    @Test fun renewalKeepsOriginalFreezeAndSuppressesEarlierWatchdog() {
        val gate = UpgradeRequestGate()
        val completion = RecordingCompletion()
        val events = mutableListOf<String>()
        val freeze = StateQuiescence { events += "freeze" }
        assertTrue(gate.arm(NONCE, completion, expiresAtMillis = 180))
        assertTrue(gate.holdReady(checkNotNull(gate.claimShutdown()), freeze, PROOF) { events += "barrier" })

        assertTrue(gate.renew(NONCE, nowMillis = 150, timeoutMillis = 180))
        assertFalse(gate.cancel(NONCE, "watchdog_expired", expiredAtMillis = 180).matched)
        assertTrue(gate.isArmed())
        assertEquals(listOf("ready:$NONCE"), completion.events)
        assertTrue(events.isEmpty())
        assertFalse(gate.cancel(NONCE, "watchdog_expired", expiredAtMillis = 329).matched)

        val expired = gate.cancel(NONCE, "watchdog_expired", expiredAtMillis = 330)
        assertTrue(expired.matched)
        assertSame(freeze, expired.freeze)
        releaseUpgradeHold(expired.freeze, releaseSuccessor = expired.releaseSuccessor, restartService = { events += "start" })
        assertEquals(listOf("freeze", "barrier", "start"), events)
        assertFalse(gate.isArmed())
        assertFalse(gate.cancel(NONCE, "watchdog_expired", expiredAtMillis = 510).matched)
    }

    @Test fun expiredHoldCannotBeRevivedEvenBeforeItsWatchdogRuns() {
        for (now in listOf(180L, 181L)) {
            val gate = readyGate(expiresAtMillis = 180)
            assertFalse(gate.renew(NONCE, nowMillis = now, timeoutMillis = 180))
            assertTrue(gate.cancel(NONCE, "watchdog_expired", expiredAtMillis = now).matched)
        }
    }

    @Test fun renewalRequiresMatchingReadyFiniteHold() {
        assertFalse(UpgradeRequestGate().renew(NONCE, 1, 180))
        val pending = UpgradeRequestGate()
        assertTrue(pending.arm(NONCE, RecordingCompletion(), expiresAtMillis = 180))
        assertFalse(pending.renew(NONCE, 100, 180))
        assertTrue(pending.cancel(NONCE, "watchdog_expired", expiredAtMillis = 180).matched)

        val ready = readyGate(expiresAtMillis = 180)
        assertFalse(ready.renew(OTHER_NONCE, 100, 180))
        assertTrue(ready.cancel(NONCE, "watchdog_expired", expiredAtMillis = 180).matched)

        val guardOwned = readyGate(expiresAtMillis = null)
        assertFalse(guardOwned.renew(NONCE, 100, 180))
        assertFalse(guardOwned.cancel(NONCE, "watchdog_expired", expiredAtMillis = 500).matched)
        assertTrue(guardOwned.isArmed())
        assertTrue(guardOwned.release(NONCE).matched)
    }

    @Test fun watchdogFromReleasedHoldCannotCancelLaterHold() {
        val gate = readyGate(expiresAtMillis = 180)
        val released = gate.release(NONCE)
        assertTrue(released.matched)
        releaseUpgradeHold(released.freeze, releaseSuccessor = released.releaseSuccessor, restartService = null)
        assertFalse(gate.renew(NONCE, 100, 180))
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
