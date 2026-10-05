package io.panelassistant.android.storage

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class StorageHealthRecoveryTest {
    @Test fun transientBusyVerifiesPromptlyButOnlyALaterDurableWriteClears() = runTest {
        val authority = StorageHealthAuthority(StorageHealthPolicy())
        authority.refresh(observation())
        val failures = StorageHealthFailureHub(authority)
        var checks = 0
        val lifecycle = StorageHealthRecoveryLifecycle(
            scope = this,
            delaysMs = longArrayOf(5_000L, 15_000L),
            subscribeFailures = failures::subscribe,
        ) {
            checks++
            val token = authority.beginObservation()
            authority.refresh(observation(), token)
            true
        }
        try {
            val writers = List(20) {
                thread { failures.recordDatabaseFailure("catalog-sync", SQLiteBusyException()) }
            }
            writers.forEach(Thread::join)
            advanceTimeBy(4_999L)
            runCurrent()
            assertEquals(0, checks)
            assertEquals(StorageHealthSeverity.DATABASE_FAILURE, authority.recordDatabaseWriteSuccess().severity)

            advanceTimeBy(1L)
            runCurrent()
            assertEquals(1, checks)
            assertEquals(StorageHealthSeverity.DATABASE_FAILURE, authority.snapshot().severity)
            assertEquals(StorageHealthSeverity.HEALTHY, authority.recordDatabaseWriteSuccess().severity)
        } finally {
            lifecycle.close()
        }
    }

    @Test fun repeatedFailuresBackOffToTheFiniteCapAndANewFailureCanRearm() = runTest {
        val delays = longArrayOf(5L, 10L, 20L)
        val checks = mutableListOf<Long>()
        val owner = StorageHealthRecoveryOwner(this, delays) {
            checks += testScheduler.currentTime
            false
        }
        try {
            repeat(20) { assertTrue(owner.request()) }
            delays.forEach {
                advanceTimeBy(it)
                runCurrent()
            }
            assertEquals(listOf(5L, 15L, 35L), checks)
            advanceTimeBy(1_000L)
            runCurrent()
            assertEquals("an exhausted run must not become a background retry storm", 3, checks.size)

            assertTrue(owner.request())
            advanceTimeBy(5L)
            runCurrent()
            assertEquals(listOf(5L, 15L, 35L, 1_040L), checks)
        } finally {
            owner.close()
        }
    }

    @Test fun failureDuringAnActiveProbeCoalescesIntoOneFreshPostFailureProbe() = runTest {
        val authority = StorageHealthAuthority(StorageHealthPolicy())
        authority.refresh(observation())
        authority.recordDatabaseFailure("catalog-sync", SQLiteBusyException())
        val firstEntered = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        var checks = 0
        val owner = StorageHealthRecoveryOwner(this, longArrayOf(5L, 10L)) {
            checks++
            val token = authority.beginObservation()
            if (checks == 1) {
                firstEntered.complete(Unit)
                releaseFirst.await()
            }
            authority.refresh(observation(), token)
            true
        }
        try {
            repeat(10) { owner.request() }
            advanceTimeBy(5L)
            runCurrent()
            assertTrue(firstEntered.isCompleted)
            assertEquals(1, checks)

            authority.recordDatabaseFailure("catalog-sync", SQLiteBusyException())
            repeat(10) { owner.request() }
            releaseFirst.complete(Unit)
            runCurrent()
            assertEquals(StorageHealthSeverity.DATABASE_FAILURE, authority.recordDatabaseWriteSuccess().severity)

            advanceTimeBy(10L)
            runCurrent()
            assertEquals(2, checks)
            assertEquals(StorageHealthSeverity.HEALTHY, authority.recordDatabaseWriteSuccess().severity)
        } finally {
            owner.close()
        }
    }

    @Test fun closeCancelsDelayedAndRunningOwnerWorkAndRejectsOldRequests() = runTest {
        val delayedRuns = mutableListOf<String>()
        val old = StorageHealthRecoveryOwner(this, longArrayOf(5L)) {
            delayedRuns += "old"
            true
        }
        old.request()
        old.close()
        advanceTimeBy(5L)
        runCurrent()
        assertTrue(delayedRuns.isEmpty())
        assertFalse(old.request())

        val entered = CompletableDeferred<Unit>()
        val neverReleased = CompletableDeferred<Unit>()
        val running = StorageHealthRecoveryOwner(this, longArrayOf(5L)) {
            entered.complete(Unit)
            neverReleased.await()
            delayedRuns += "late"
            true
        }
        running.request()
        advanceTimeBy(5L)
        runCurrent()
        assertTrue(entered.isCompleted)
        running.close()
        runCurrent()
        assertTrue(delayedRuns.isEmpty())

        val fresh = StorageHealthRecoveryOwner(this, longArrayOf(5L)) {
            delayedRuns += "fresh"
            true
        }
        try {
            fresh.request()
            advanceTimeBy(5L)
            runCurrent()
            assertEquals(listOf("fresh"), delayedRuns)
        } finally {
            fresh.close()
        }
    }

    @Test fun recreatedLifecycleAutomaticallyReplaysTheRetainedFailure() = runTest {
        val authority = StorageHealthAuthority(StorageHealthPolicy())
        authority.refresh(observation())
        val failures = StorageHealthFailureHub(authority)
        failures.recordDatabaseFailure("catalog-sync", SQLiteBusyException())
        var checks = 0
        val old = StorageHealthRecoveryLifecycle(this, longArrayOf(5L), failures::subscribe) {
            checks++
            true
        }
        old.close()
        advanceTimeBy(5L)
        runCurrent()
        assertEquals(0, checks)

        val replacement = StorageHealthRecoveryLifecycle(this, longArrayOf(5L), failures::subscribe) {
            checks++
            val token = authority.beginObservation()
            authority.refresh(observation(), token)
            true
        }
        try {
            advanceTimeBy(5L)
            runCurrent()
            assertEquals(1, checks)
            assertEquals(StorageHealthSeverity.DATABASE_FAILURE, authority.snapshot().severity)
            assertEquals(StorageHealthSeverity.HEALTHY, authority.recordDatabaseWriteSuccess().severity)
        } finally {
            replacement.close()
        }
    }

    @Test fun freshProcessAuthorityStartsUncheckedAndOwnFailuresScheduleRecovery() = runTest {
        val previousProcess = StorageHealthAuthority(StorageHealthPolicy())
        previousProcess.refresh(observation())
        previousProcess.recordDatabaseFailure("catalog-sync", SQLiteBusyException())

        val freshAuthority = StorageHealthAuthority(StorageHealthPolicy())
        val freshFailures = StorageHealthFailureHub(freshAuthority)
        var checks = 0
        val lifecycle = StorageHealthRecoveryLifecycle(this, longArrayOf(5L), freshFailures::subscribe) {
            checks++
            true
        }
        try {
            advanceTimeBy(5L)
            runCurrent()
            assertEquals(StorageHealthSeverity.UNCHECKED, freshAuthority.snapshot().severity)
            assertEquals("a previous process cannot leak its in-memory latch", 0, checks)

            freshFailures.recordDatabaseFailure("catalog-sync", SQLiteBusyException())
            advanceTimeBy(5L)
            runCurrent()
            assertEquals(1, checks)
        } finally {
            lifecycle.close()
        }
    }

    @Test fun newerFailureDuringFinalCleanProbeGetsAFreshBoundedRun() = runTest {
        val authority = StorageHealthAuthority(StorageHealthPolicy())
        authority.refresh(observation())
        val failures = StorageHealthFailureHub(authority)
        var checks = 0
        val lifecycle = StorageHealthRecoveryLifecycle(
            this,
            longArrayOf(5L, 10L, 20L),
            failures::subscribe,
        ) {
            checks++
            val token = authority.beginObservation()
            if (checks < 3) {
                false
            } else {
                if (checks == 3) failures.recordDatabaseFailure("catalog-sync", SQLiteBusyException())
                authority.refresh(observation(), token)
                true
            }
        }
        try {
            failures.recordDatabaseFailure("catalog-sync", SQLiteBusyException())
            listOf(5L, 10L, 20L).forEach {
                advanceTimeBy(it)
                runCurrent()
            }
            assertEquals(3, checks)
            assertEquals(StorageHealthSeverity.DATABASE_FAILURE, authority.recordDatabaseWriteSuccess().severity)

            advanceTimeBy(5L)
            runCurrent()
            assertEquals(4, checks)
            assertEquals(StorageHealthSeverity.HEALTHY, authority.recordDatabaseWriteSuccess().severity)
        } finally {
            lifecycle.close()
        }
    }

    @Test fun everyFailureEventIsDeliveredAndCloseSuppressesOldSubscriptions() {
        var delivered = 0
        val immediate = StorageHealthFailureEventDispatch()
        val subscription = immediate.subscribe(replayCurrentFailure = { true }) { delivered++ }
        immediate.publish()
        immediate.publish()
        assertEquals(3, delivered)
        subscription.close()
        immediate.publish()
        assertEquals(3, delivered)

        val closed = immediate.subscribe(replayCurrentFailure = { false }) { delivered++ }
        closed.close()
        immediate.publish()
        assertEquals("callbacks from an old service generation must be fenced", 3, delivered)

        val registrationRace = StorageHealthFailureEventDispatch()
        var racedDelivery = 0
        registrationRace.subscribe(
            replayCurrentFailure = {
                registrationRace.publish()
                false
            },
        ) { racedDelivery++ }
        assertEquals("registration must precede the retained-latch read", 1, racedDelivery)

        val isolated = StorageHealthFailureEventDispatch()
        isolated.subscribe(replayCurrentFailure = { false }) { error("broken") }
        isolated.subscribe(replayCurrentFailure = { false }) { delivered++ }
        isolated.publish()
        assertEquals("one broken demand listener cannot suppress another", 4, delivered)
    }

    @Test fun aFailureRaisedByEveryVerificationCannotSelfRearmPastTheAttemptBound() = runTest {
        val delays = longArrayOf(5L, 10L, 20L)
        var checks = 0
        lateinit var owner: StorageHealthRecoveryOwner
        owner = StorageHealthRecoveryOwner(this, delays) {
            checks++
            // Models storageHealthObservation recording BUSY before it throws back to the owner.
            owner.request()
            false
        }
        try {
            owner.request()
            delays.forEach {
                advanceTimeBy(it)
                runCurrent()
            }
            assertEquals(3, checks)
            advanceTimeBy(1_000L)
            runCurrent()
            assertEquals("self-generated failure demand must be consumed by the bounded run", 3, checks)
        } finally {
            owner.close()
        }
    }

    @Test fun dailyAndRecoveryDemandQueueBehindOneActiveObservation() = runTest {
        data class Probe(var cancelled: Boolean = false)
        val queue = StorageHealthObservationQueue(create = ::Probe) { it.cancelled = true }
        val firstEntered = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val order = mutableListOf<String>()

        val recovery = launch {
            queue.run {
                order += "recovery-start"
                firstEntered.complete(Unit)
                releaseFirst.await()
                order += "recovery-end"
            }
        }
        runCurrent()
        assertTrue(firstEntered.isCompleted)
        val daily = launch { queue.run { order += "daily" } }
        runCurrent()
        assertEquals(listOf("recovery-start"), order)

        releaseFirst.complete(Unit)
        recovery.join()
        daily.join()
        assertEquals(listOf("recovery-start", "recovery-end", "daily"), order)
        queue.close()
    }

    @Test fun observationQueueCloseCancelsTheActiveProbeAndRejectsQueuedWork() = runTest {
        data class Probe(var cancelled: Boolean = false)
        lateinit var active: Probe
        val queue = StorageHealthObservationQueue(
            create = { Probe().also { active = it } },
            cancel = { it.cancelled = true },
        )
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val first = launch {
            queue.run {
                entered.complete(Unit)
                release.await()
            }
        }
        runCurrent()
        assertTrue(entered.isCompleted)
        var queuedRan = false
        val queued = launch { queue.run { queuedRan = true } }
        runCurrent()

        queue.close()
        assertTrue(active.cancelled)
        release.complete(Unit)
        first.join()
        queued.join()
        assertFalse(queuedRan)
        assertEquals(null, queue.run { "late" })
    }

    @Test fun mutationSequencesNeverLetAWriteUseMissingOrSupersededEvidence() {
        val authority = StorageHealthAuthority(StorageHealthPolicy())
        authority.refresh(observation())

        authority.recordDatabaseFailure("catalog-sync", SQLiteBusyException())
        assertEquals(StorageHealthSeverity.DATABASE_FAILURE, authority.recordDatabaseWriteSuccess().severity)
        val cleanAfterWrite = authority.beginObservation()
        assertEquals("beginning the observation is not completed integrity evidence",
            StorageHealthSeverity.DATABASE_FAILURE, authority.recordDatabaseWriteSuccess().severity)
        authority.refresh(observation(), cleanAfterWrite)
        assertEquals("the observation itself is not a write", StorageHealthSeverity.DATABASE_FAILURE, authority.snapshot().severity)

        authority.recordDatabaseFailure("catalog-sync", SQLiteBusyException())
        assertEquals("a newer identical failure invalidates the clean proof", StorageHealthSeverity.DATABASE_FAILURE,
            authority.recordDatabaseWriteSuccess().severity)

        val incomplete = authority.beginObservation()
        authority.refresh(observation().copy(quickCheck = StorageQuickCheck.NOT_RUN), incomplete)
        assertEquals(StorageHealthSeverity.DATABASE_FAILURE, authority.recordDatabaseWriteSuccess().severity)

        val finalClean = authority.beginObservation()
        authority.refresh(observation(), finalClean)
        assertEquals(StorageHealthSeverity.HEALTHY, authority.recordDatabaseWriteSuccess().severity)
    }

    private fun observation() = StorageHealthObservation(
        checkedAtMillis = 123L,
        usableBytes = 1_024L * 1_024L * 1_024L,
        totalBytes = 2_048L * 1_024L * 1_024L,
        mainDatabaseBytes = 100L,
        walBytes = 0L,
        sidecarBytes = 0L,
        pageSizeBytes = 4_096L,
        pageCount = 100L,
        freelistCount = 0L,
        schemaVersion = 14,
        quickCheck = StorageQuickCheck.OK,
    )

    private class SQLiteBusyException : RuntimeException("database is busy")
}
