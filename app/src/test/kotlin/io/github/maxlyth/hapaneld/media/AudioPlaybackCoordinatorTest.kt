package io.github.maxlyth.hapaneld.media

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.withContext
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

@OptIn(ExperimentalCoroutinesApi::class)
class AudioPlaybackCoordinatorTest {
    @Test fun onlyExplicitSpeechUsesPreparedPlayback() = runTest {
        val selected = mutableListOf<String>()
        val factory = object : AudioPlaybackRunFactory {
            private fun run(kind: String) = object : AudioPlaybackRun {
                override suspend fun execute() { selected += kind }
                override fun cancel() {}
            }
            override fun create(url: String) = run("media:$url")
            override fun createSpeech(url: String) = run("speech:$url")
        }
        val coordinator = AudioPlaybackCoordinator(factory, StandardTestDispatcher(testScheduler))
        coordinator.submit("music")
        runCurrent()
        coordinator.submitForGeneration("instruction", speech = true)
        runCurrent()
        assertEquals(listOf("media:music", "speech:instruction"), selected)
        assertTrue(coordinator.close(1_000L))
    }

    @Test fun busyChangesAreReportedForQueuedPlayingAndFinishedAnnouncements() = runTest {
        val busy = mutableListOf<Boolean>()
        val release = CompletableDeferred<Unit>()
        val factory = AudioPlaybackRunFactory { _ ->
            object : AudioPlaybackRun {
                override suspend fun execute() { release.await() }
                override fun cancel() {}
            }
        }
        val coordinator = AudioPlaybackCoordinator(
            factory, StandardTestDispatcher(testScheduler), onBusyChanged = { busy += it },
        )
        coordinator.submit("first")
        runCurrent()
        assertEquals("queued then active is one busy span", listOf(true), busy)
        release.complete(Unit)
        runCurrent()
        assertEquals(listOf(true, false), busy)
        val generation = requireNotNull(coordinator.submitForGeneration("second"))
        assertTrue(coordinator.cancelGeneration(generation))
        assertEquals("a cancelled request ends the busy span", listOf(true, false, true, false), busy)
        assertTrue(coordinator.close(1_000L))
    }

    private class FakeRun(
        private val name: String,
        private val events: MutableList<String>,
        private val active: AtomicInteger,
        private val maximum: AtomicInteger,
        private val error: Throwable? = null,
    ) : AudioPlaybackRun {
        val started = CompletableDeferred<Unit>()
        var cancelCalls = 0

        override suspend fun execute() {
            val now = active.incrementAndGet()
            maximum.updateAndGet { maxOf(it, now) }
            events += "start:$name"
            started.complete(Unit)
            try {
                error?.let { throw it }
                awaitCancellation()
            } finally {
                events += "finish:$name"
                active.decrementAndGet()
            }
        }

        override fun cancel() {
            cancelCalls++
            events += "cancel:$name"
        }
    }

    @Test fun replacementFinishesTheOldRunBeforeStartingTheNewRun() = runTest {
        val events = mutableListOf<String>()
        val active = AtomicInteger()
        val maximum = AtomicInteger()
        val runs = linkedMapOf<String, FakeRun>()
        val coordinator = AudioPlaybackCoordinator(
            AudioPlaybackRunFactory { url -> FakeRun(url, events, active, maximum).also { runs[url] = it } },
            StandardTestDispatcher(testScheduler),
        )

        assertTrue(coordinator.submit("first"))
        runCurrent()
        assertTrue(runs.getValue("first").started.isCompleted)
        assertTrue(coordinator.submit("second"))
        runCurrent()

        assertEquals(listOf("start:first", "cancel:first", "finish:first", "start:second"), events)
        assertEquals(1, maximum.get())
        assertEquals(AudioPlaybackCoordinator.State.ACTIVE, coordinator.snapshot().state)
        assertEquals(2L, coordinator.snapshot().generation)
        assertTrue(coordinator.close(1_000L))
        assertEquals(1, runs.getValue("second").cancelCalls)
    }

    @Test fun queuedReplacementsConflateToTheNewestRequest() = runTest {
        val events = mutableListOf<String>()
        val active = AtomicInteger()
        val maximum = AtomicInteger()
        val created = mutableListOf<String>()
        val coordinator = AudioPlaybackCoordinator(
            AudioPlaybackRunFactory { url ->
                created += url
                FakeRun(url, events, active, maximum)
            },
            StandardTestDispatcher(testScheduler),
        )

        coordinator.submit("first")
        runCurrent()
        coordinator.submit("superseded")
        coordinator.submit("newest")
        runCurrent()

        assertEquals(listOf("first", "newest"), created)
        assertEquals(3L, coordinator.snapshot().generation)
        assertTrue(coordinator.close(1_000L))
    }

    @Test fun exactGenerationCancellationInvalidatesQueuedWorkBeforeFactoryCreation() = runTest {
        val created = mutableListOf<String>()
        val coordinator = AudioPlaybackCoordinator(
            AudioPlaybackRunFactory { url ->
                created += url
                FakeRun(url, mutableListOf(), AtomicInteger(), AtomicInteger())
            },
            StandardTestDispatcher(testScheduler),
        )
        val generation = coordinator.submitForGeneration("wizard")!!
        assertTrue(coordinator.cancelGeneration(generation))
        runCurrent()
        assertTrue(created.isEmpty())
        assertEquals(AudioPlaybackCoordinator.State.IDLE, coordinator.snapshot().state)
        assertTrue(coordinator.close(1_000L))
    }

    @Test fun staleGenerationCancellationCannotStopAnewerAnnouncement() = runTest {
        val events = mutableListOf<String>()
        val active = AtomicInteger()
        val maximum = AtomicInteger()
        val runs = linkedMapOf<String, FakeRun>()
        val coordinator = AudioPlaybackCoordinator(
            AudioPlaybackRunFactory { url -> FakeRun(url, events, active, maximum).also { runs[url] = it } },
            StandardTestDispatcher(testScheduler),
        )
        val wizard = coordinator.submitForGeneration("wizard")!!
        runCurrent()
        val newer = coordinator.submitForGeneration("newer")!!
        runCurrent()
        assertFalse(coordinator.cancelGeneration(wizard))
        assertEquals(0, runs.getValue("newer").cancelCalls)
        assertEquals(newer, coordinator.snapshot().generation)
        assertEquals(AudioPlaybackCoordinator.State.ACTIVE, coordinator.snapshot().state)
        assertTrue(coordinator.cancelGeneration(newer))
        assertEquals(1, runs.getValue("newer").cancelCalls)
        assertEquals(AudioPlaybackCoordinator.State.IDLE, coordinator.snapshot().state)
        assertTrue(coordinator.close(1_000L))
    }

    @Test fun cancelledRunReturningNormallyCannotOverwriteTheReplacementGeneration() = runTest {
        val firstStarted = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val secondStarted = CompletableDeferred<Unit>()
        val coordinator = AudioPlaybackCoordinator(
            AudioPlaybackRunFactory { url ->
                object : AudioPlaybackRun {
                    override suspend fun execute() {
                        if (url == "first") {
                            firstStarted.complete(Unit)
                            withContext(NonCancellable) { releaseFirst.await() }
                        } else {
                            secondStarted.complete(Unit)
                            awaitCancellation()
                        }
                    }

                    override fun cancel() = Unit
                }
            },
            StandardTestDispatcher(testScheduler),
        )

        assertTrue(coordinator.submit("first"))
        runCurrent()
        assertTrue(firstStarted.isCompleted)
        assertTrue(coordinator.submit("second"))
        runCurrent()
        assertFalse(secondStarted.isCompleted)

        releaseFirst.complete(Unit)
        runCurrent()

        assertTrue(secondStarted.isCompleted)
        assertEquals(AudioPlaybackCoordinator.State.ACTIVE, coordinator.snapshot().state)
        assertEquals(2L, coordinator.snapshot().generation)
        assertTrue(coordinator.close(1_000L))
    }

    @Test fun closedAdmissionRejectsWithoutClaimingPlayback() = runTest {
        val events = mutableListOf<String>()
        val active = AtomicInteger()
        val maximum = AtomicInteger()
        lateinit var run: FakeRun
        val coordinator = AudioPlaybackCoordinator(
            AudioPlaybackRunFactory { FakeRun(it, events, active, maximum).also { created -> run = created } },
            StandardTestDispatcher(testScheduler),
        )
        coordinator.submit("current")
        runCurrent()

        coordinator.closeAdmission()
        assertFalse(coordinator.submit("late"))
        assertEquals(AudioPlaybackCoordinator.State.CLOSED, coordinator.snapshot().state)
        assertEquals(0, run.cancelCalls)
        coordinator.cancelCurrent()
        assertEquals(1, run.cancelCalls)
        assertTrue(coordinator.close(1_000L))
        assertEquals(1, run.cancelCalls)
    }

    @Test fun failureIsBoundedSanitizedAndOwnedByItsGeneration() = runTest {
        val failures = mutableListOf<Throwable>()
        val message = "bad\n" + "x".repeat(300)
        val coordinator = AudioPlaybackCoordinator(
            AudioPlaybackRunFactory {
                FakeRun(it, mutableListOf(), AtomicInteger(), AtomicInteger(), IllegalStateException(message))
            },
            StandardTestDispatcher(testScheduler),
            failures::add,
        )

        coordinator.submit("secret-url")
        runCurrent()

        val snapshot = coordinator.snapshot()
        assertEquals(AudioPlaybackCoordinator.State.FAILED, snapshot.state)
        assertEquals(1, failures.size)
        assertFalse(snapshot.error.orEmpty().contains('\n'))
        assertFalse(snapshot.error.orEmpty().contains("secret-url"))
        assertTrue(snapshot.error.orEmpty().length <= 160)
        assertTrue(coordinator.close(1_000L))
    }

    @Test fun closeIsBoundedEvenWhenABackendIgnoresCoroutineCancellation() = runTest {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var cancelCalls = 0
        val coordinator = AudioPlaybackCoordinator(
            AudioPlaybackRunFactory {
                object : AudioPlaybackRun {
                    override suspend fun execute() {
                        started.complete(Unit)
                        withContext(NonCancellable) { release.await() }
                    }

                    override fun cancel() {
                        cancelCalls++
                    }
                }
            },
            StandardTestDispatcher(testScheduler),
        )
        coordinator.submit("stubborn")
        runCurrent()
        assertTrue(started.isCompleted)

        assertFalse(coordinator.close(10L))
        assertFalse(coordinator.submit("late"))
        assertEquals(1, cancelCalls)
        release.complete(Unit)
        runCurrent()
    }

    @Test fun closeDuringFactoryCreationCannotPublishAStartAfterCancel() = runTest {
        val events = mutableListOf<String>()
        val active = AtomicInteger()
        val maximum = AtomicInteger()
        lateinit var coordinator: AudioPlaybackCoordinator
        lateinit var created: FakeRun
        coordinator = AudioPlaybackCoordinator(
            AudioPlaybackRunFactory { url ->
                coordinator.closeAdmission()
                created = FakeRun(url, events, active, maximum)
                created
            },
            StandardTestDispatcher(testScheduler),
        )

        assertTrue(coordinator.submit("racing"))
        runCurrent()

        assertEquals(AudioPlaybackCoordinator.State.CLOSED, coordinator.snapshot().state)
        assertEquals(1, created.cancelCalls)
        assertFalse(created.started.isCompleted)
        assertEquals(0, active.get())
        assertTrue(coordinator.close(1_000L))
        assertEquals(1, created.cancelCalls)
    }
}
