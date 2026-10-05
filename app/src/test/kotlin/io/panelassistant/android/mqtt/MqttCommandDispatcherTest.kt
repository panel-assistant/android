package io.panelassistant.android.mqtt

import io.panelassistant.android.MqttCommandDispatcher
import io.panelassistant.android.LiveSettingApplyResult
import io.panelassistant.android.LiveSettingAuthority
import io.panelassistant.android.LiveSettingRequestOutcome
import io.panelassistant.android.liveSettingApplication
import io.panelassistant.android.metrics.FeatureCostOperation
import io.panelassistant.android.util.MonotonicDeadline
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MqttCommandDispatcherTest {
    @Test fun blockedOnThenFloodEndsAtLatestOffWithConstantBacklog() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val finished = CountDownLatch(2)
        val states = Collections.synchronizedList(mutableListOf<String>())
        val dispatcher = MqttCommandDispatcher(threadName = "mqtt-latest-flood-test")

        assertEquals(MqttCommandDispatcher.Admission.ACCEPTED, dispatcher.submitLatest("screen") {
            entered.countDown()
            assertTrue(release.await(5, TimeUnit.SECONDS))
            states += "ON"
            finished.countDown()
        })
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        repeat(999) { index ->
            dispatcher.submitLatest("screen") { states += if (index % 2 == 0) "ON" else "OFF" }
        }
        assertEquals(MqttCommandDispatcher.Admission.COALESCED, dispatcher.submitLatest("screen") {
            states += "OFF"
            finished.countDown()
        })
        assertEquals(1, dispatcher.pendingCount())

        release.countDown()
        assertTrue(finished.await(5, TimeUnit.SECONDS))
        assertTrue(dispatcher.closeAndDrain(MonotonicDeadline(5_000)).drained)
        assertEquals(listOf("ON", "OFF"), states)
    }

    @Test fun aCommandFromEitherTransportConflatesOnTheSameChannelAndAnObservedOneLearnsItWasSuperseded() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val done = CountDownLatch(1)
        val ran = Collections.synchronizedList(mutableListOf<String>())
        val skipped = Collections.synchronizedList(mutableListOf<MqttCommandDispatcher.Execution>())
        val dispatcher = MqttCommandDispatcher(threadName = "mqtt-cross-transport-test")

        dispatcher.submitLatest("blocker") {
            entered.countDown()
            assertTrue(release.await(5, TimeUnit.SECONDS))
        }
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        // A native command queued first, then an MQTT command for the same channel replaces it.
        assertEquals(
            MqttCommandDispatcher.Admission.ACCEPTED,
            dispatcher.submitLatest("relay1", onSkipped = { skipped += it }) { ran += "native" },
        )
        assertEquals(MqttCommandDispatcher.Admission.COALESCED, dispatcher.submitLatest("relay1") { ran += "mqtt" })
        assertEquals(listOf(MqttCommandDispatcher.Execution.SUPERSEDED), skipped.toList())
        // And the other way round: an observed command that runs reports nothing through onSkipped.
        dispatcher.submitLatest("relay2", onSkipped = { skipped += it }) {
            ran += "native2"
            done.countDown()
        }

        release.countDown()
        assertTrue(done.await(5, TimeUnit.SECONDS))
        assertTrue(dispatcher.closeAndDrain(MonotonicDeadline(5_000)).drained)
        assertEquals(listOf("mqtt", "native2"), ran.toList())
        assertEquals(listOf(MqttCommandDispatcher.Execution.SUPERSEDED), skipped.toList())
    }

    @Test fun anObservedCommandThatCannotBeAdmittedSaysSo() {
        val dispatcher = MqttCommandDispatcher(threadName = "mqtt-closed-observed-test")
        dispatcher.close()
        val skipped = mutableListOf<MqttCommandDispatcher.Execution>()
        assertEquals(
            MqttCommandDispatcher.Admission.CLOSED,
            dispatcher.submitLatest("relay1", onSkipped = { skipped += it }) { error("must not run") },
        )
        assertEquals(listOf(MqttCommandDispatcher.Execution.NOT_ADMITTED), skipped)
    }

    @Test fun queueWaitIsMeasuredFromTheExecutedSubmissionOnly() {
        val now = java.util.concurrent.atomic.AtomicLong(0L)
        val waits = Collections.synchronizedList(mutableListOf<Long>())
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val finished = CountDownLatch(2)
        val dispatcher = MqttCommandDispatcher(
            threadName = "queue-wait-test",
            nowNanos = now::get,
            onQueueWait = { waits += it },
        )

        dispatcher.submitLatest("screen") {
            entered.countDown()
            assertTrue(release.await(5, TimeUnit.SECONDS))
            finished.countDown()
        }
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        now.set(7L)
        dispatcher.submitLatest("volume") { finished.countDown() }
        now.set(9L)
        // Conflation restarts the receipt clock: the replaced submission reports no wait at all.
        assertEquals(
            MqttCommandDispatcher.Admission.COALESCED,
            dispatcher.submitLatest("volume") { finished.countDown() },
        )
        now.set(10L)
        release.countDown()
        assertTrue(finished.await(5, TimeUnit.SECONDS))
        assertTrue(dispatcher.closeAndDrain(MonotonicDeadline(5_000)).drained)

        assertEquals(listOf(0L, 1L), waits)
    }

    @Test fun replacingAStateMovesItBehindOtherAcceptedTopics() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val finished = CountDownLatch(3)
        val observed = Collections.synchronizedList(mutableListOf<String>())
        val dispatcher = MqttCommandDispatcher(threadName = "mqtt-state-order-test")

        dispatcher.submitAction {
            entered.countDown()
            assertTrue(release.await(5, TimeUnit.SECONDS))
            observed += "blocker"
            finished.countDown()
        }
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        dispatcher.submitLatest("screen") { observed += "screen-old" }
        dispatcher.submitLatest("volume") { observed += "volume"; finished.countDown() }
        assertEquals(MqttCommandDispatcher.Admission.COALESCED, dispatcher.submitLatest("screen") {
            observed += "screen-new"
            finished.countDown()
        })

        release.countDown()
        assertTrue(finished.await(5, TimeUnit.SECONDS))
        assertTrue(dispatcher.closeAndDrain(MonotonicDeadline(5_000)).drained)
        assertEquals(listOf("blocker", "volume", "screen-new"), observed)
    }

    @Test fun actionFifoHasAnIndependentSmallBound() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val finished = CountDownLatch(3)
        val actions = Collections.synchronizedList(mutableListOf<Int>())
        val dispatcher = MqttCommandDispatcher(
            maxPending = 5,
            maxPendingActions = 2,
            threadName = "mqtt-action-bound-test",
        )
        dispatcher.submitAction {
            entered.countDown()
            release.await(5, TimeUnit.SECONDS)
            actions += 0
            finished.countDown()
        }
        assertTrue(entered.await(5, TimeUnit.SECONDS))

        assertEquals(MqttCommandDispatcher.Admission.ACCEPTED, dispatcher.submitAction {
            actions += 1
            finished.countDown()
        })
        assertEquals(MqttCommandDispatcher.Admission.ACCEPTED, dispatcher.submitAction {
            actions += 2
            finished.countDown()
        })
        assertEquals(MqttCommandDispatcher.Admission.REJECTED, dispatcher.submitAction { actions += 3 })
        // Stateful work still has room even when the action allowance is full.
        assertEquals(MqttCommandDispatcher.Admission.ACCEPTED, dispatcher.submitLatest("screen") {})

        release.countDown()
        assertTrue(finished.await(5, TimeUnit.SECONDS))
        assertTrue(dispatcher.closeAndDrain(MonotonicDeadline(5_000)).drained)
        assertEquals(listOf(0, 1, 2), actions)
    }

    @Test fun synchronousSettingSharesOrderingAndReportsFailureTruthfully() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val order = Collections.synchronizedList(mutableListOf<String>())
        val dispatcher = MqttCommandDispatcher(threadName = "mqtt-http-authority-test")
        val caller = Executors.newSingleThreadExecutor()
        try {
            dispatcher.submitLatest("mqtt-screen") {
                entered.countDown()
                assertTrue(release.await(5, TimeUnit.SECONDS))
                order += "mqtt"
            }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val http = caller.submit<Boolean> {
                dispatcher.runLatest("http-screen") { order += "http" }
            }
            assertFalse(http.isDone)
            release.countDown()
            assertTrue(http.get(5, TimeUnit.SECONDS))
            assertEquals(listOf("mqtt", "http"), order)
            assertFalse(dispatcher.runLatest("failure") { error("controller failed") })
        } finally {
            assertTrue(dispatcher.closeAndDrain(MonotonicDeadline(5_000)).drained)
            caller.shutdownNow()
        }
    }

    @Test fun blockedWorkerDefersUnrelatedSynchronousSettingsWithoutBreakingOrder() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val finished = CountDownLatch(2)
        val applied = Collections.synchronizedList(mutableListOf<String>())
        val dispatcher = MqttCommandDispatcher(
            threadName = "mqtt-http-deferred-test",
            resultWaitMs = 50,
        )
        try {
            dispatcher.submitAction {
                entered.countDown()
                assertTrue(release.await(5, TimeUnit.SECONDS))
            }
            assertTrue(entered.await(5, TimeUnit.SECONDS))

            val home = dispatcher.runLatestResult("http:home_dashboard") {
                applied += "home"
                finished.countDown()
            }
            val watchdog = dispatcher.runLatestResult("http:watchdog_enabled") {
                applied += "watchdog"
                finished.countDown()
            }

            assertEquals(MqttCommandDispatcher.Execution.PENDING, home.execution)
            assertEquals(MqttCommandDispatcher.Execution.PENDING, watchdog.execution)
            release.countDown()
            assertTrue(finished.await(5, TimeUnit.SECONDS))
            assertEquals(listOf("home", "watchdog"), applied)
        } finally {
            release.countDown()
            assertTrue(dispatcher.closeAndDrain(MonotonicDeadline(5_000)).drained)
        }
    }

    @Test fun lateSuccessClearsOnlyItsMatchingDurableGeneration() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val dispatcher = MqttCommandDispatcher(threadName = "late-success-authority", resultWaitMs = 30)
        val authority = LiveSettingAuthority(setOf("screen"))
        try {
            val outcome = authority.applyOrQueueOutcomeObserved("screen", "ON", "OFF") { _, value, _ ->
                liveSettingApplication(dispatcher.runLatestResult("http:screen") {
                    entered.countDown()
                    assertTrue(release.await(5, TimeUnit.SECONDS))
                    assertEquals("ON", value)
                })
            }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            assertEquals(LiveSettingRequestOutcome.DEFERRED, outcome)
            assertEquals(mapOf("screen" to "ON"), authority.pendingSnapshot())
            release.countDown()
            awaitPending(authority, emptyMap())
        } finally {
            release.countDown()
            assertTrue(dispatcher.closeAndDrain(MonotonicDeadline(5_000)).drained)
        }
    }

    @Test fun lateFailureRemainsDurableAndBecomesReplayable() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val dispatcher = MqttCommandDispatcher(threadName = "late-failure-authority", resultWaitMs = 30)
        val authority = LiveSettingAuthority(setOf("screen"))
        try {
            assertEquals(
                LiveSettingRequestOutcome.DEFERRED,
                authority.applyOrQueueOutcomeObserved("screen", "ON", "OFF") { _, _, _ ->
                    liveSettingApplication(dispatcher.runLatestResult("http:screen") {
                        entered.countDown()
                        assertTrue(release.await(5, TimeUnit.SECONDS))
                        error("controller failed late")
                    })
                },
            )
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            release.countDown()
            // The contract under test is durability: the late failure must BECOME replayable. The earlier
            // awaitPending(pending == {screen=ON}) barrier was vacuous — that state holds from the moment
            // the apply deferred — so a single replayKeys call raced the worker's late-completion cleanup:
            // if it observed the key still in-flight it skipped its whole body, a scheduling coin-flip that
            // failed deterministically on loaded hosts. (awaitDrained is no help mid-test: it is an
            // interrupt-and-join shutdown drain.) So replay until it takes; the loop is the barrier.
            val replayDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (authority.pendingSnapshot().isNotEmpty() && System.nanoTime() < replayDeadline) {
                authority.replayKeys(setOf("screen")) { _, _, _, _ -> LiveSettingApplyResult.APPLIED }
                if (authority.pendingSnapshot().isNotEmpty()) Thread.sleep(5)
            }
            assertTrue(authority.pendingSnapshot().isEmpty())
        } finally {
            release.countDown()
            assertTrue(dispatcher.closeAndDrain(MonotonicDeadline(5_000)).drained)
        }
    }

    @Test fun timedOutSameKeyIsSupersededAndOnlyNewerGenerationClears() {
        val blockerEntered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val applied = Collections.synchronizedList(mutableListOf<String>())
        val dispatcher = MqttCommandDispatcher(threadName = "same-key-authority", resultWaitMs = 30)
        val authority = LiveSettingAuthority(setOf("screen"))
        try {
            dispatcher.submitAction {
                blockerEntered.countDown()
                assertTrue(release.await(5, TimeUnit.SECONDS))
            }
            assertTrue(blockerEntered.await(5, TimeUnit.SECONDS))
            fun apply(value: String) = authority.applyOrQueueOutcomeObserved("screen", value, "OFF") { _, queued, _ ->
                liveSettingApplication(dispatcher.runLatestResult("http:screen") { applied += queued })
            }
            assertEquals(LiveSettingRequestOutcome.DEFERRED, apply("ON"))
            assertEquals(LiveSettingRequestOutcome.DEFERRED, apply("OFF"))
            assertEquals(mapOf("screen" to "OFF"), authority.pendingSnapshot())
            release.countDown()
            awaitPending(authority, emptyMap())
            assertEquals(listOf("OFF"), applied)
        } finally {
            release.countDown()
            assertTrue(dispatcher.closeAndDrain(MonotonicDeadline(5_000)).drained)
        }
    }

    @Test fun inFlightHttpGenerationIsNotReplayedBehindNewerMqttCommand() {
        val httpEntered = CountDownLatch(1)
        val releaseHttp = CountDownLatch(1)
        val finished = CountDownLatch(2)
        val applied = Collections.synchronizedList(mutableListOf<String>())
        val dispatcher = MqttCommandDispatcher(threadName = "http-mqtt-order-authority", resultWaitMs = 30)
        val authority = LiveSettingAuthority(setOf("screen"))
        try {
            assertEquals(
                LiveSettingRequestOutcome.DEFERRED,
                authority.applyOrQueueOutcomeObserved("screen", "HTTP-OLD", "OFF") { _, value, _ ->
                    liveSettingApplication(dispatcher.runLatestResult("http:screen") {
                        httpEntered.countDown()
                        assertTrue(releaseHttp.await(5, TimeUnit.SECONDS))
                        applied += value
                        finished.countDown()
                    })
                },
            )
            assertTrue(httpEntered.await(5, TimeUnit.SECONDS))
            dispatcher.submitLatest("mqtt:screen") {
                applied += "MQTT-NEW"
                finished.countDown()
            }
            authority.replayKeysObserved(setOf("screen")) { _, _, _, _ ->
                error("in-flight generation must not be replayed")
            }
            releaseHttp.countDown()
            assertTrue(finished.await(5, TimeUnit.SECONDS))
            awaitPending(authority, emptyMap())
            assertEquals(listOf("HTTP-OLD", "MQTT-NEW"), applied)
        } finally {
            releaseHttp.countDown()
            assertTrue(dispatcher.closeAndDrain(MonotonicDeadline(5_000)).drained)
        }
    }

    @Test fun lateHttpFailureCannotReplayAfterNewerExternalMqttTruth() {
        val httpEntered = CountDownLatch(1)
        val releaseHttp = CountDownLatch(1)
        val mqttFinished = CountDownLatch(1)
        val applied = Collections.synchronizedList(mutableListOf<String>())
        val dispatcher = MqttCommandDispatcher(threadName = "failed-http-newer-mqtt", resultWaitMs = 30)
        val authority = LiveSettingAuthority(setOf("screen"))
        try {
            assertEquals(
                LiveSettingRequestOutcome.DEFERRED,
                authority.applyOrQueueOutcomeObserved("screen", "HTTP-OLD", "OFF") { _, _, _ ->
                    liveSettingApplication(dispatcher.runLatestResult("http:screen") {
                        httpEntered.countDown()
                        assertTrue(releaseHttp.await(5, TimeUnit.SECONDS))
                        error("old HTTP actuation failed")
                    })
                },
            )
            assertTrue(httpEntered.await(5, TimeUnit.SECONDS))
            dispatcher.submitLatest("mqtt:screen") {
                applied += "MQTT-NEW"
                assertTrue(authority.discard("screen"))
                mqttFinished.countDown()
            }
            releaseHttp.countDown()
            assertTrue(mqttFinished.await(5, TimeUnit.SECONDS))
            awaitPending(authority, emptyMap())
            authority.replayKeys(setOf("screen")) { _, _, _, _ ->
                error("superseded HTTP generation replayed")
            }
            assertEquals(listOf("MQTT-NEW"), applied)
        } finally {
            releaseHttp.countDown()
            assertTrue(dispatcher.closeAndDrain(MonotonicDeadline(5_000)).drained)
        }
    }

    @Test fun identicalValueAbaUsesGenerationIdentityAcrossLateSuccess() {
        val firstEntered = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val secondEntered = CountDownLatch(1)
        val releaseSecond = CountDownLatch(1)
        val dispatcher = MqttCommandDispatcher(threadName = "equal-value-aba", resultWaitMs = 30)
        val authority = LiveSettingAuthority(setOf("screen"))
        try {
            fun save(block: () -> Unit) = authority.applyOrQueueOutcomeObserved("screen", "ON", "OFF") { _, _, _ ->
                liveSettingApplication(dispatcher.runLatestResult("http:screen", command = block))
            }
            assertEquals(LiveSettingRequestOutcome.DEFERRED, save {
                firstEntered.countDown()
                assertTrue(releaseFirst.await(5, TimeUnit.SECONDS))
            })
            assertTrue(firstEntered.await(5, TimeUnit.SECONDS))
            val firstGeneration = authority.pendingGenerationSnapshot().getValue("screen")

            assertEquals(LiveSettingRequestOutcome.DEFERRED, save {
                secondEntered.countDown()
                assertTrue(releaseSecond.await(5, TimeUnit.SECONDS))
            })
            val secondGeneration = authority.pendingGenerationSnapshot().getValue("screen")
            assertFalse(firstGeneration == secondGeneration)

            releaseFirst.countDown()
            assertTrue(secondEntered.await(5, TimeUnit.SECONDS))
            assertEquals(mapOf("screen" to "ON"), authority.pendingSnapshot())
            assertEquals(secondGeneration, authority.pendingGenerationSnapshot().getValue("screen"))
            releaseSecond.countDown()
            awaitPending(authority, emptyMap())
        } finally {
            releaseFirst.countDown()
            releaseSecond.countDown()
            assertTrue(dispatcher.closeAndDrain(MonotonicDeadline(5_000)).drained)
        }
    }

    @Test fun dispatcherInterruptionRetainsGenerationAcrossAuthorityRecreation() {
        val blockerEntered = CountDownLatch(1)
        val releaseBlocker = CountDownLatch(1)
        val journal = TestJournal()
        val dispatcher = MqttCommandDispatcher(threadName = "interrupted-generation", resultWaitMs = 30)
        val first = LiveSettingAuthority(setOf("screen"), journal)
        dispatcher.submitAction {
            blockerEntered.countDown()
            while (releaseBlocker.count > 0) {
                try {
                    releaseBlocker.await(20, TimeUnit.MILLISECONDS)
                } catch (_: InterruptedException) {
                    // Keep the active blocker alive until the test releases it; close still cancels queued work.
                }
            }
        }
        assertTrue(blockerEntered.await(5, TimeUnit.SECONDS))
        assertEquals(
            LiveSettingRequestOutcome.DEFERRED,
            first.applyOrQueueOutcomeObserved("screen", "ON", "OFF") { _, _, _ ->
                liveSettingApplication(dispatcher.runLatestResult("http:screen") {
                    error("cancelled queued command ran")
                })
            },
        )
        val generation = first.pendingGenerationSnapshot().getValue("screen")
        dispatcher.close()
        releaseBlocker.countDown()
        assertTrue(dispatcher.awaitDrained(MonotonicDeadline(5_000)))

        val replacement = LiveSettingAuthority(setOf("screen"), journal)
        assertEquals(generation, replacement.pendingGenerationSnapshot().getValue("screen"))
        replacement.replayKeys(setOf("screen")) { _, _, _, _ -> LiveSettingApplyResult.APPLIED }
        assertTrue(replacement.pendingSnapshot().isEmpty())
    }

    private fun awaitPending(authority: LiveSettingAuthority, expected: Map<String, String>) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        while (authority.pendingSnapshot() != expected && System.nanoTime() < deadline) Thread.yield()
        assertEquals(expected, authority.pendingSnapshot())
    }

    private class TestJournal : LiveSettingAuthority.Journal {
        private val values = linkedMapOf<String, LiveSettingAuthority.Pending>()
        override fun load(): Map<String, LiveSettingAuthority.Pending> = values.toMap()
        override fun put(key: String, value: LiveSettingAuthority.Pending): Boolean {
            values[key] = value
            return true
        }
        override fun remove(key: String): Boolean {
            values.remove(key)
            return true
        }
    }

    @Test fun supersededSynchronousSettingReturnsFalseAndLatestReturnsTrue() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val secondAdmitted = CountDownLatch(1)
        val applied = Collections.synchronizedList(mutableListOf<String>())
        val dispatcher = MqttCommandDispatcher(threadName = "mqtt-http-conflation-test")
        val callers = Executors.newFixedThreadPool(2)
        try {
            dispatcher.submitAction {
                entered.countDown()
                assertTrue(release.await(5, TimeUnit.SECONDS))
            }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val first = callers.submit<MqttCommandDispatcher.RunResult> {
                dispatcher.runLatestResult("http:screen") { applied += "ON" }
            }
            val pendingDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
            while (dispatcher.pendingCount() != 1 && System.nanoTime() < pendingDeadline) Thread.yield()
            assertEquals(1, dispatcher.pendingCount())
            val second = callers.submit<MqttCommandDispatcher.RunResult> {
                dispatcher.runLatestResult("http:screen", onAdmission = { secondAdmitted.countDown() }) {
                    applied += "OFF"
                }
            }
            assertTrue(secondAdmitted.await(5, TimeUnit.SECONDS))
            release.countDown()

            val firstResult = first.get(5, TimeUnit.SECONDS)
            val secondResult = second.get(5, TimeUnit.SECONDS)
            assertEquals(MqttCommandDispatcher.Admission.ACCEPTED, firstResult.admission)
            assertFalse(firstResult.executed)
            assertEquals(MqttCommandDispatcher.Execution.SUPERSEDED, firstResult.execution)
            assertEquals(MqttCommandDispatcher.Admission.COALESCED, secondResult.admission)
            assertTrue(secondResult.executed)
            assertEquals(MqttCommandDispatcher.Execution.SUCCEEDED, secondResult.execution)
            assertEquals(listOf("OFF"), applied)
        } finally {
            assertTrue(dispatcher.closeAndDrain(MonotonicDeadline(5_000)).drained)
            callers.shutdownNow()
        }
    }

    @Test fun closeCancelsPendingDrainsActiveAndRejectsLateCommands() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val closeReturned = CountDownLatch(1)
        val closeRestoredInterrupt = AtomicBoolean(false)
        val observed = Collections.synchronizedList(mutableListOf<String>())
        val dispatcher = MqttCommandDispatcher(threadName = "mqtt-close-test")

        dispatcher.submitAction {
            entered.countDown()
            while (release.count > 0) {
                try {
                    release.await(50, TimeUnit.MILLISECONDS)
                } catch (_: InterruptedException) {
                    // Model a platform call which cannot be cancelled after it entered the controller.
                }
            }
            observed += "active"
        }
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        dispatcher.submitAction { observed += "pending" }
        val closer = Thread {
            val result = dispatcher.closeAndDrain(MonotonicDeadline(5_000))
            assertEquals(1, result.cancelled)
            assertTrue(result.drained)
            closeRestoredInterrupt.set(Thread.currentThread().isInterrupted)
            closeReturned.countDown()
        }.apply { start() }

        assertFalse(closeReturned.await(100, TimeUnit.MILLISECONDS))
        val admissionDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        while (dispatcher.pendingCount() != 0 && System.nanoTime() < admissionDeadline) Thread.yield()
        assertEquals(0, dispatcher.pendingCount())
        closer.interrupt()
        assertEquals(MqttCommandDispatcher.Admission.CLOSED, dispatcher.submitLatest("late") {
            observed += "late"
        })
        release.countDown()
        assertTrue(closeReturned.await(5, TimeUnit.SECONDS))
        assertTrue(closeRestoredInterrupt.get())
        assertEquals(listOf("active"), observed)
        assertEquals(0, dispatcher.pendingCount())
    }

    @Test fun expiredDrainKeepsAdmissionClosedUntilTheActiveCommandActuallyExits() {
        val entered = CountDownLatch(1)
        val interrupted = CountDownLatch(1)
        val release = CountDownLatch(1)
        val dispatcher = MqttCommandDispatcher(threadName = "mqtt-expired-drain-test")
        dispatcher.submitAction {
            entered.countDown()
            while (release.count > 0L) {
                try {
                    release.await()
                } catch (_: InterruptedException) {
                    interrupted.countDown()
                }
            }
        }
        try {
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            dispatcher.submitAction { error("cancelled command ran") }

            val expired = dispatcher.closeAndDrain(MonotonicDeadline(0))

            assertEquals(1, expired.cancelled)
            assertFalse(expired.drained)
            assertTrue(interrupted.await(2, TimeUnit.SECONDS))
            assertEquals(MqttCommandDispatcher.Admission.CLOSED, dispatcher.submitAction {
                error("late command ran")
            })
            release.countDown()
            assertTrue(dispatcher.closeAndDrain(MonotonicDeadline(2_000)).drained)
        } finally {
            release.countDown()
            dispatcher.closeAndDrain(MonotonicDeadline(2_000))
        }
    }

    @Test fun instrumentationKeyIsFixedAndPublicSafe() {
        assertEquals("mqtt.command_dispatch", FeatureCostOperation.MQTT_COMMAND_DISPATCH.id)
    }

    @Test fun admittedHandlerExceptionIsDistinguishedFromClosedAdmission() {
        val dispatcher = MqttCommandDispatcher(threadName = "mqtt-result-test")
        val failed = dispatcher.runLatestResult("failure") { error("controller failed") }
        assertEquals(MqttCommandDispatcher.Admission.ACCEPTED, failed.admission)
        assertEquals(MqttCommandDispatcher.Execution.FAILED, failed.execution)

        assertTrue(dispatcher.closeAndDrain(MonotonicDeadline(5_000)).drained)
        val closed = dispatcher.runLatestResult("closed") {}
        assertEquals(MqttCommandDispatcher.Admission.CLOSED, closed.admission)
        assertEquals(MqttCommandDispatcher.Execution.NOT_ADMITTED, closed.execution)
    }
}
