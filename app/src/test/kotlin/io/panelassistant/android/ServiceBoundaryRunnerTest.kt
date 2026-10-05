package io.panelassistant.android

import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ServiceBoundaryRunnerTest {
    @Test fun cleanProofReleasesExactlyOnce() {
        val events = mutableListOf<String>()

        runServiceBoundary(
            boundary = ServiceTeardownBoundary(),
            completed = true,
            prepare = { events += "prepare" },
            prove = { attempt ->
                events += "prove-$attempt"
                ServiceBoundaryProof(externalStateSafe = true, forceFreshProcess = false)
            },
            pauseBeforeRetry = { events += "pause" },
            finish = { events += it.name },
        )

        assertEquals(listOf("prepare", "prove-1", "RELEASE"), events)
    }

    @Test fun forcedFreshProcessRetriesWithoutOpeningTheSuccessor() {
        val events = mutableListOf<String>()

        runServiceBoundary(
            boundary = ServiceTeardownBoundary(),
            completed = true,
            prepare = { events += "prepare" },
            prove = { attempt ->
                events += "prove-$attempt"
                ServiceBoundaryProof(
                    externalStateSafe = false,
                    forceFreshProcess = attempt == 2,
                )
            },
            pauseBeforeRetry = { events += "pause" },
            finish = { events += it.name },
        )

        assertEquals(listOf("prepare", "prove-1", "pause", "prove-2", "EXIT"), events)
    }

    @Test fun concurrentIncompleteProofIsStickyBeforeTheRecoveryOwnerFinishes() {
        val boundary = ServiceTeardownBoundary()
        val ownerEntered = CountDownLatch(1)
        val allowProof = CountDownLatch(1)
        val terminal = Collections.synchronizedList(mutableListOf<ServiceTeardownDisposition>())
        val pool = Executors.newFixedThreadPool(2)

        try {
            val cleanOwner = pool.submit {
                runServiceBoundary(
                    boundary = boundary,
                    completed = true,
                    prepare = { ownerEntered.countDown() },
                    prove = {
                        assertTrue(allowProof.await(1, TimeUnit.SECONDS))
                        ServiceBoundaryProof(externalStateSafe = true, forceFreshProcess = false)
                    },
                    pauseBeforeRetry = {},
                    finish = terminal::add,
                )
            }
            assertTrue(ownerEntered.await(1, TimeUnit.SECONDS))

            val incompleteCaller = pool.submit {
                runServiceBoundary(
                    boundary = boundary,
                    completed = false,
                    prepare = { error("second caller acquired recovery") },
                    prove = { error("second caller proved external state") },
                    pauseBeforeRetry = { error("second caller retried") },
                    finish = { error("second caller finished") },
                )
            }
            incompleteCaller.get(1, TimeUnit.SECONDS)
            allowProof.countDown()
            cleanOwner.get(1, TimeUnit.SECONDS)

            assertEquals(listOf(ServiceTeardownDisposition.EXIT), terminal)
        } finally {
            pool.shutdownNow()
        }
    }

    @Test fun explicitRequestAndIncompleteRunnerBeforeClaimExitExactlyOnce() {
        val boundary = ServiceTeardownBoundary()
        val ownerPrepared = CountDownLatch(1)
        val allowProof = CountDownLatch(1)
        val prepareCount = AtomicInteger()
        val proveCount = AtomicInteger()
        val finishCount = AtomicInteger()
        val terminal = AtomicReference<ServiceTeardownDisposition?>()
        val pool = Executors.newSingleThreadExecutor()

        try {
            val cleanOwner = pool.submit {
                runServiceBoundary(
                    boundary = boundary,
                    completed = true,
                    prepare = {
                        prepareCount.incrementAndGet()
                        ownerPrepared.countDown()
                    },
                    prove = {
                        proveCount.incrementAndGet()
                        assertTrue(allowProof.await(1, TimeUnit.SECONDS))
                        ServiceBoundaryProof(externalStateSafe = true, forceFreshProcess = false)
                    },
                    pauseBeforeRetry = { error("clean proof retried") },
                    finish = {
                        finishCount.incrementAndGet()
                        assertTrue(terminal.compareAndSet(null, it))
                    },
                )
            }
            assertTrue(ownerPrepared.await(1, TimeUnit.SECONDS))

            assertTrue(boundary.requestExplicitBoundary())
            runServiceBoundary(
                boundary = boundary,
                completed = false,
                prepare = { error("incomplete caller acquired recovery") },
                prove = { error("incomplete caller proved external state") },
                pauseBeforeRetry = { error("incomplete caller retried") },
                finish = { error("incomplete caller finished") },
            )
            allowProof.countDown()
            cleanOwner.get(1, TimeUnit.SECONDS)

            assertEquals(1, prepareCount.get())
            assertEquals(1, proveCount.get())
            assertEquals(1, finishCount.get())
            assertEquals(ServiceTeardownDisposition.EXIT, terminal.get())
            assertFalse(boundary.requestExplicitBoundary())
        } finally {
            allowProof.countDown()
            pool.shutdownNow()
        }
    }

    @Test fun explicitRequestBeforeProofReturnsExitsExactlyOnce() {
        val boundary = ServiceTeardownBoundary()
        val proofEntered = CountDownLatch(1)
        val allowProof = CountDownLatch(1)
        val prepareCount = AtomicInteger()
        val proveCount = AtomicInteger()
        val finishCount = AtomicInteger()
        val terminal = AtomicReference<ServiceTeardownDisposition?>()
        val pool = Executors.newSingleThreadExecutor()

        try {
            val runner = pool.submit {
                runServiceBoundary(
                    boundary = boundary,
                    completed = true,
                    prepare = { prepareCount.incrementAndGet() },
                    prove = {
                        proveCount.incrementAndGet()
                        proofEntered.countDown()
                        assertTrue(allowProof.await(1, TimeUnit.SECONDS))
                        ServiceBoundaryProof(externalStateSafe = true, forceFreshProcess = false)
                    },
                    pauseBeforeRetry = { error("clean proof retried") },
                    finish = {
                        finishCount.incrementAndGet()
                        assertTrue(terminal.compareAndSet(null, it))
                    },
                )
            }
            assertTrue(proofEntered.await(1, TimeUnit.SECONDS))

            assertTrue(boundary.requestExplicitBoundary())
            allowProof.countDown()
            runner.get(1, TimeUnit.SECONDS)

            assertEquals(1, prepareCount.get())
            assertEquals(1, proveCount.get())
            assertEquals(1, finishCount.get())
            assertEquals(ServiceTeardownDisposition.EXIT, terminal.get())
        } finally {
            allowProof.countDown()
            pool.shutdownNow()
        }
    }

    @Test fun explicitRequestWhileFinishBlockedIsRejectedAfterOneCleanRelease() {
        val boundary = ServiceTeardownBoundary()
        val finishEntered = CountDownLatch(1)
        val allowFinish = CountDownLatch(1)
        val prepareCount = AtomicInteger()
        val proveCount = AtomicInteger()
        val finishCount = AtomicInteger()
        val terminal = AtomicReference<ServiceTeardownDisposition?>()
        val pool = Executors.newSingleThreadExecutor()

        try {
            val runner = pool.submit {
                runServiceBoundary(
                    boundary = boundary,
                    completed = true,
                    prepare = { prepareCount.incrementAndGet() },
                    prove = {
                        proveCount.incrementAndGet()
                        ServiceBoundaryProof(externalStateSafe = true, forceFreshProcess = false)
                    },
                    pauseBeforeRetry = { error("clean proof retried") },
                    finish = {
                        finishCount.incrementAndGet()
                        assertTrue(terminal.compareAndSet(null, it))
                        finishEntered.countDown()
                        assertTrue(allowFinish.await(1, TimeUnit.SECONDS))
                    },
                )
            }
            assertTrue(finishEntered.await(1, TimeUnit.SECONDS))

            assertFalse(boundary.requestExplicitBoundary())
            allowFinish.countDown()
            runner.get(1, TimeUnit.SECONDS)

            assertEquals(1, prepareCount.get())
            assertEquals(1, proveCount.get())
            assertEquals(1, finishCount.get())
            assertEquals(ServiceTeardownDisposition.RELEASE, terminal.get())
        } finally {
            allowFinish.countDown()
            pool.shutdownNow()
        }
    }

    @Test fun interruptedRetryPauseStillReachesOneTerminalClaim() {
        val boundary = ServiceTeardownBoundary()
        val pauseEntered = CountDownLatch(1)
        val interruptionObserved = CountDownLatch(1)
        val prepareCount = AtomicInteger()
        val proveCount = AtomicInteger()
        val pauseCount = AtomicInteger()
        val finishCount = AtomicInteger()
        val terminal = AtomicReference<ServiceTeardownDisposition?>()
        val failure = AtomicReference<Throwable?>()
        val runner = Thread {
            try {
                runServiceBoundary(
                    boundary = boundary,
                    completed = true,
                    prepare = { prepareCount.incrementAndGet() },
                    prove = { attempt ->
                        proveCount.incrementAndGet()
                        ServiceBoundaryProof(
                            externalStateSafe = attempt == 2,
                            forceFreshProcess = false,
                        )
                    },
                    pauseBeforeRetry = {
                        pauseCount.incrementAndGet()
                        pauseEntered.countDown()
                        try {
                            CountDownLatch(1).await()
                        } catch (_: InterruptedException) {
                            interruptionObserved.countDown()
                        }
                    },
                    finish = {
                        finishCount.incrementAndGet()
                        assertTrue(terminal.compareAndSet(null, it))
                    },
                )
            } catch (caught: Throwable) {
                failure.set(caught)
            }
        }

        try {
            runner.start()
            assertTrue(pauseEntered.await(1, TimeUnit.SECONDS))
            runner.interrupt()
            assertTrue(interruptionObserved.await(1, TimeUnit.SECONDS))
            runner.join(1_000)

            assertFalse("recovery runner remained blocked after interruption", runner.isAlive)
            failure.get()?.let { throw AssertionError("recovery runner failed", it) }
            assertEquals(1, prepareCount.get())
            assertEquals(2, proveCount.get())
            assertEquals(1, pauseCount.get())
            assertEquals(1, finishCount.get())
            assertEquals(ServiceTeardownDisposition.RELEASE, terminal.get())
        } finally {
            runner.interrupt()
            runner.join(1_000)
        }
    }

    @Test fun delayedExplicitRequestAndCleanTerminalClaimHaveOneLinearOutcome() {
        val pool = Executors.newFixedThreadPool(2)
        try {
            repeat(250) {
                val boundary = ServiceTeardownBoundary()
                val startRace = CountDownLatch(1)
                val requestAccepted = AtomicBoolean(false)
                val terminal = AtomicReference<ServiceTeardownDisposition?>()

                val runner = pool.submit {
                    runServiceBoundary(
                        boundary = boundary,
                        completed = true,
                        prepare = {},
                        prove = {
                            assertTrue(startRace.await(1, TimeUnit.SECONDS))
                            ServiceBoundaryProof(externalStateSafe = true, forceFreshProcess = false)
                        },
                        pauseBeforeRetry = {},
                        finish = { assertTrue(terminal.compareAndSet(null, it)) },
                    )
                }
                val requester = pool.submit {
                    assertTrue(startRace.await(1, TimeUnit.SECONDS))
                    requestAccepted.set(boundary.requestExplicitBoundary())
                }

                startRace.countDown()
                runner.get(1, TimeUnit.SECONDS)
                requester.get(1, TimeUnit.SECONDS)
                assertEquals(
                    if (requestAccepted.get()) ServiceTeardownDisposition.EXIT
                    else ServiceTeardownDisposition.RELEASE,
                    terminal.get(),
                )
                assertFalse(boundary.requestExplicitBoundary())
            }
        } finally {
            pool.shutdownNow()
        }
    }

    @Test fun stickyStopSignalDefaultsFalse() {
        assertFalse(ServiceTeardownBoundary().isStopping)
    }

    @Test fun markStoppingLatchesTheSignalAndIsIdempotent() {
        val boundary = ServiceTeardownBoundary()
        boundary.markStopping()
        assertTrue(boundary.isStopping)
        boundary.markStopping()
        assertTrue(boundary.isStopping)
    }

    @Test fun explicitBoundaryLatchesStopSignalImmediately() {
        // Accepting an explicit boundary latches the stop signal immediately, and it stays set across
        // the boundary's finalization. Ordinary teardown still calls markStopping() independently.
        val boundary = ServiceTeardownBoundary()
        assertTrue(boundary.requestExplicitBoundary())
        assertTrue(boundary.isStopping)

        runServiceBoundary(
            boundary = boundary,
            completed = false,
            prepare = {},
            prove = { ServiceBoundaryProof(externalStateSafe = true, forceFreshProcess = false) },
            pauseBeforeRetry = {},
            finish = {},
        )
        assertTrue(boundary.isStopping)
    }

    @Test fun exactlyOneCallerCommitsTheProcessAndTheCommitmentIsIrreversible() {
        val commitment = ProcessBoundaryCommitment()

        assertEquals(ServiceGenerationAdmission.START, commitment.admitServiceGeneration())
        assertTrue(commitment.commit())
        assertEquals(ServiceGenerationAdmission.STAND_DOWN, commitment.admitServiceGeneration())
        assertFalse(commitment.commit())
        assertEquals(ServiceGenerationAdmission.STAND_DOWN, commitment.admitServiceGeneration())
    }

    @Test fun racingBoundaryRequestsCommitOnceAndEveryLaterGenerationStandsDown() {
        // Several owners can request a boundary at the same instant (a profile activation, a WebView
        // rebind, bounded recovery). Exactly one commits, and no generation admitted after the race may
        // start: it would only claim state it cannot live long enough to prove.
        val commitment = ProcessBoundaryCommitment()
        val racers = 8
        val ready = CountDownLatch(racers)
        val go = CountDownLatch(1)
        val winners = AtomicInteger()
        val pool = Executors.newFixedThreadPool(racers)

        try {
            repeat(racers) {
                pool.execute {
                    ready.countDown()
                    go.await()
                    if (commitment.commit()) winners.incrementAndGet()
                }
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS))
            go.countDown()
            pool.shutdown()
            assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS))
        } finally {
            pool.shutdownNow()
        }

        assertEquals(1, winners.get())
        assertEquals(ServiceGenerationAdmission.STAND_DOWN, commitment.admitServiceGeneration())
    }
}
