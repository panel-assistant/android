package io.panelassistant.android

import io.panelassistant.android.util.LatestDispatcher
import io.panelassistant.android.util.MonotonicDeadline
import io.panelassistant.android.util.RetirableMutationGate
import io.panelassistant.android.util.interruptAndJoin
import io.panelassistant.android.util.shutdownNowAndAwait
import io.panelassistant.android.metrics.FeatureCostOperation
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MqttLifecycleWorkerTest {
    @Test fun lifecycleAdmissionClosesWithoutWaitingForAWedgedMutationLock() {
        val gate = RetirableMutationGate()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val worker = Thread {
            gate.runIfOpen(Unit) {
                entered.countDown()
                while (release.count > 0L) {
                    try {
                        release.await()
                    } catch (_: InterruptedException) {
                        // Model an MQTT client call which cannot be cancelled after admission.
                    }
                }
            }
        }.apply { start() }
        try {
            assertTrue(entered.await(1, TimeUnit.SECONDS))
            gate.closeAdmission()
            assertFalse(gate.awaitDrained(MonotonicDeadline(0L)))
            var lateRan = false
            gate.runIfOpen(Unit) { lateRan = true }
            assertFalse(lateRan)
        } finally {
            release.countDown()
            worker.join(1_000L)
        }
        assertTrue(gate.awaitDrained(MonotonicDeadline(1_000L)))
    }

    @Test fun reannouncementCostUsesAFixedNonIdentifyingOperation() {
        assertEquals("mqtt.discovery_reannounce", FeatureCostOperation.MQTT_DISCOVERY_REANNOUNCE.id)
    }

    @Test fun birthBurstRunsOnlyTheLatestDebouncedReannouncement() {
        val pauseStarted = CountDownLatch(1)
        val releasePause = CountDownLatch(1)
        val performed = CountDownLatch(1)
        var calls = 0
        val dispatcher = MqttReannounceDispatcher(
            debounceMs = 250,
            pause = {
                pauseStarted.countDown()
                releasePause.await(1, TimeUnit.SECONDS)
            },
            perform = {
                calls += 1
                performed.countDown()
            },
        )

        assertEquals(LatestDispatcher.Admission.ACCEPTED, dispatcher.submit())
        assertTrue(pauseStarted.await(1, TimeUnit.SECONDS))
        assertEquals(LatestDispatcher.Admission.ACCEPTED, dispatcher.submit())
        assertEquals(LatestDispatcher.Admission.COALESCED, dispatcher.submit())
        assertEquals(1, dispatcher.pendingCount())
        releasePause.countDown()

        assertTrue(performed.await(1, TimeUnit.SECONDS))
        assertEquals(1, calls)
        assertTrue(dispatcher.closeAndJoin(1_000))
        assertEquals(LatestDispatcher.Admission.CLOSED, dispatcher.submit())
    }

    @Test fun interruptIgnoringAuthenticationActionFailsLifecycleDrainProof() {
        val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        try {
            executor.execute {
                entered.countDown()
                while (release.count > 0L) {
                    try {
                        release.await()
                    } catch (_: InterruptedException) {
                        // Model a transport connect that cannot be cancelled by scheduler shutdown.
                    }
                }
            }
            assertTrue(entered.await(1, TimeUnit.SECONDS))
            assertFalse(executor.shutdownNowAndAwait(MonotonicDeadline(25L)))
        } finally {
            release.countDown()
            executor.shutdownNow()
            executor.awaitTermination(1, TimeUnit.SECONDS)
        }
    }

    @Test fun interruptIgnoringHaLinkWorkerFailsTheSharedRetirementProof() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val worker = Thread {
            entered.countDown()
            while (release.count > 0L) {
                try {
                    release.await()
                } catch (_: InterruptedException) {
                    // Model a DNS/HTTP implementation that ignores interruption after admission.
                }
            }
        }.apply { start() }
        try {
            assertTrue(entered.await(1, TimeUnit.SECONDS))
            assertFalse(worker.interruptAndJoin(MonotonicDeadline(25L)))
        } finally {
            release.countDown()
            worker.join(1_000L)
        }
        assertFalse(worker.isAlive)
    }

    @Test fun teardownCancelsAReannouncementStillInsideItsDebounceWindow() {
        val pauseStarted = CountDownLatch(1)
        val releasePause = CountDownLatch(1)
        var calls = 0
        val dispatcher = MqttReannounceDispatcher(
            debounceMs = 250,
            pause = {
                pauseStarted.countDown()
                try {
                    releasePause.await(1, TimeUnit.SECONDS)
                } catch (_: InterruptedException) {
                    // Let the dispatcher observe its closed generation after the bounded pause.
                }
            },
            perform = { calls += 1 },
        )

        dispatcher.submit()
        assertTrue(pauseStarted.await(1, TimeUnit.SECONDS))
        assertTrue(dispatcher.closeAndJoin(1_000))
        releasePause.countDown()
        assertEquals(0, calls)
    }

    @Test fun connectAnnouncementSuppressesARetainedBirthAlreadyInTheDebounceSlot() {
        val pauseStarted = CountDownLatch(1)
        val releasePause = CountDownLatch(1)
        var calls = 0
        val dispatcher = MqttReannounceDispatcher(
            debounceMs = 250,
            pause = {
                pauseStarted.countDown()
                releasePause.await(1, TimeUnit.SECONDS)
            },
            perform = { calls += 1 },
        )

        dispatcher.submit()
        assertTrue(pauseStarted.await(1, TimeUnit.SECONDS))
        dispatcher.suppressPending()
        releasePause.countDown()

        assertTrue(dispatcher.closeAndJoin(1_000))
        assertEquals(0, calls)
    }

    @Test fun replacementActuationAlwaysRunsAfterAnUninterruptibleOldGeneration() {
        val coordinator = MqttZigbeeActuationCoordinator()
        val oldLease = coordinator.activate()
        val oldStarted = CountDownLatch(1)
        val releaseOld = CountDownLatch(1)
        val finished = CountDownLatch(2)
        val events = Collections.synchronizedList(mutableListOf<String>())

        val old = Thread {
            val result = coordinator.executeIfCurrent(oldLease) {
                oldStarted.countDown()
                releaseOld.await(1, TimeUnit.SECONDS)
                events += "old-on"
            }
            assertTrue(result.executed)
            assertFalse(result.currentAfter)
            finished.countDown()
        }
        old.start()
        assertTrue(oldStarted.await(1, TimeUnit.SECONDS))

        coordinator.retire(oldLease)
        val replacementLease = coordinator.activate()
        val replacement = Thread {
            val result = coordinator.executeIfCurrent(replacementLease) { events += "replacement-off" }
            assertTrue(result.executed)
            assertTrue(result.currentAfter)
            finished.countDown()
        }
        replacement.start()
        releaseOld.countDown()

        assertTrue(finished.await(1, TimeUnit.SECONDS))
        assertEquals(listOf("old-on", "replacement-off"), events.toList())
    }

    @Test fun invalidatedGenerationCannotBeginAQueuedActuation() {
        val coordinator = MqttZigbeeActuationCoordinator()
        val oldLease = coordinator.activate()
        coordinator.retire(oldLease)
        coordinator.activate()

        val result = coordinator.executeIfCurrent(oldLease) { error("obsolete action ran") }

        assertFalse(result.executed)
        assertFalse(result.currentAfter)
    }
}
