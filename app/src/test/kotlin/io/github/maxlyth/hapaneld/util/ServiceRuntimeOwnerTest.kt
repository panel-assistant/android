package io.github.maxlyth.hapaneld.util

import io.github.maxlyth.hapaneld.MqttCommandAdmission
import io.github.maxlyth.hapaneld.sensors.SensorRunCallbacks
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ServiceRuntimeOwnerTest {
    private class FakeNetwork(
        val name: String,
        private val events: MutableList<String>,
    ) {
        val commands = MqttCommandAdmission()
        @Volatile private var stopped = false

        fun start() {
            check(!stopped)
            events += "$name-start"
        }

        @Synchronized fun stop() {
            events += "$name-stop-enter"
            commands.closeAndDrain()
            stopped = true
            events += "$name-stop-exit"
        }

        @Synchronized fun reconnect(entered: CountDownLatch, release: CountDownLatch) {
            events += "$name-reconnect-enter"
            entered.countDown()
            assertTrue(release.await(2, TimeUnit.SECONDS))
            if (stopped) events += "$name-reconnect-terminal" else events += "$name-reconnect-applied"
        }
    }

    @Test fun serviceCompositionOwnsDrainReplacementLateCallbacksReconnectAndShutdown() {
        val events = Collections.synchronizedList(mutableListOf<String>())
        val samples = Collections.synchronizedList(mutableListOf<Int>())
        val initial = FakeNetwork("initial", events)
        val owner = ServiceRuntimeOwner(initial, "service-composed-test")
        val commandEntered = CountDownLatch(1)
        val releaseCommand = CountDownLatch(1)
        val reconnectEntered = CountDownLatch(1)
        val releaseReconnect = CountDownLatch(1)
        val workers = Executors.newFixedThreadPool(2)
        lateinit var sensorRun: SensorRunCallbacks

        try {
            assertTrue(owner.start { network ->
                events += "service-start"
                sensorRun = SensorRunCallbacks(
                    onLux = samples::add,
                    onLuxRaw = {},
                    onProximity = { _, _, _ -> },
                    onGesture = {},
                    onTemperature = {},
                    onHumidity = {},
                )
                network.start()
            }.get(2, TimeUnit.SECONDS))
            val initialObservation = owner.observe()!!
            assertSame(initial, initialObservation.value)
            sensorRun.light(12f, now = 100L)

            val command = workers.submit<Boolean> {
                initial.commands.run {
                    events += "command-enter"
                    commandEntered.countDown()
                    assertTrue(releaseCommand.await(2, TimeUnit.SECONDS))
                    events += "command-exit"
                }
            }
            assertTrue(commandEntered.await(2, TimeUnit.SECONDS))

            val reconfigured = owner.reconfigure(
                retire = FakeNetwork::stop,
                build = {
                    events += "replacement-build"
                    FakeNetwork("replacement", events)
                },
                start = FakeNetwork::start,
                complete = { events += "replacement-complete" },
            )
            awaitEvent(events, "initial-stop-enter")
            assertSame("replacement must not publish before command drain", initial, owner.current())
            assertFalse(events.contains("replacement-build"))

            releaseCommand.countDown()
            assertTrue(command.get(2, TimeUnit.SECONDS))
            assertTrue(reconfigured.get(2, TimeUnit.SECONDS))
            val replacement = owner.current()
            assertNotSame(initial, replacement)
            assertEquals(2L, owner.observe()!!.generation)

            assertFalse(owner.reconnect(initialObservation) { events += "stale-reconnect-ran" }.get(2, TimeUnit.SECONDS))
            val replacementObservation = owner.observe()!!
            val reconnect = owner.reconnect(replacementObservation) { network ->
                network.reconnect(reconnectEntered, releaseReconnect)
            }
            assertTrue(reconnectEntered.await(2, TimeUnit.SECONDS))

            assertTrue(owner.shutdown(2_000) { network ->
                events += "service-stop-enter"
                sensorRun.close()
                network.stop()
                events += "service-stop-exit"
            })
            assertTrue(owner.isStopped())
            sensorRun.light(99f, now = 100_000L)
            assertEquals("a late sensor result must not cross the terminal callback generation", listOf(12), samples)
            assertFalse(replacement.commands.run { events += "late-command-ran" })
            assertFalse(owner.reconnect(replacementObservation) { events += "post-stop-reconnect-ran" }.get(2, TimeUnit.SECONDS))

            releaseReconnect.countDown()
            assertFalse("terminal shutdown cancels an accepted recovery worker", reconnect.get(2, TimeUnit.SECONDS))
            assertEquals(
                listOf(
                    "service-start",
                    "initial-start",
                    "command-enter",
                    "initial-stop-enter",
                    "command-exit",
                    "initial-stop-exit",
                    "replacement-build",
                    "replacement-start",
                    "replacement-complete",
                    "replacement-reconnect-enter",
                    "service-stop-enter",
                    "replacement-stop-enter",
                    "replacement-stop-exit",
                    "service-stop-exit",
                ),
                events.toList(),
            )
        } finally {
            releaseCommand.countDown()
            releaseReconnect.countDown()
            if (!owner.isStopped()) {
                owner.shutdown(2_000) { it.stop() }
            }
            workers.shutdownNow()
        }
    }

    @Test fun failedReplacementRemainsOwnedForRecoveryAndTerminalCleanup() {
        val events = Collections.synchronizedList(mutableListOf<String>())
        val initial = FakeNetwork("initial", events)
        val owner = ServiceRuntimeOwner(initial, "service-failure-test")

        try {
            assertTrue(owner.start(FakeNetwork::start).get(2, TimeUnit.SECONDS))
            lateinit var failed: FakeNetwork
            assertFalse(owner.reconfigure(
                retire = FakeNetwork::stop,
                build = {
                    failed = FakeNetwork("failed", events)
                    failed
                },
                start = {
                    it.start()
                    error("start failed after acquiring resources")
                },
            ).get(2, TimeUnit.SECONDS))
            assertFalse(owner.isRunning())
            assertSame("the partially started replacement must remain reachable for cleanup", failed, owner.current())

            assertTrue(owner.reconfigure(
                retire = FakeNetwork::stop,
                build = { FakeNetwork("recovered", events) },
                start = FakeNetwork::start,
            ).get(2, TimeUnit.SECONDS))
            assertTrue(owner.isRunning())
            assertTrue(owner.shutdown(2_000, FakeNetwork::stop))
            assertEquals(
                listOf(
                    "initial-start",
                    "initial-stop-enter",
                    "initial-stop-exit",
                    "failed-start",
                    "failed-stop-enter",
                    "failed-stop-exit",
                    "recovered-start",
                    "recovered-stop-enter",
                    "recovered-stop-exit",
                ),
                events.toList(),
            )
        } finally {
            if (!owner.isStopped()) {
                owner.shutdown(2_000) { it.stop() }
            }
        }
    }

    @Test fun failedRetirementNeverBuildsOrPublishesAReplacement() {
        val events = Collections.synchronizedList(mutableListOf<String>())
        val initial = FakeNetwork("initial", events)
        val owner = ServiceRuntimeOwner(initial, "service-stop-failure-test")
        assertTrue(owner.start(FakeNetwork::start).get(2, TimeUnit.SECONDS))
        var built = false

        assertFalse(owner.reconfigure(
            retire = { error("owner cleanup unproved") },
            build = { built = true; FakeNetwork("replacement", events) },
            start = FakeNetwork::start,
        ).get(2, TimeUnit.SECONDS))

        assertFalse(built)
        assertSame(initial, owner.current())
        assertFalse(owner.isRunning())
        assertTrue(owner.shutdown(2_000, FakeNetwork::stop))
    }

    @Test fun shutdownWaitsForInFlightStartupThenClosesResourcesStartupOpened() {
        val events = Collections.synchronizedList(mutableListOf<String>())
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val owner = ServiceRuntimeOwner("network", "startup-shutdown-test")
        val worker = Executors.newSingleThreadExecutor()

        try {
            val startup = owner.start {
                events += "startup-enter"
                entered.countDown()
                assertTrue(release.await(2, TimeUnit.SECONDS))
                events += "http-start"
            }
            assertTrue(entered.await(2, TimeUnit.SECONDS))

            val shutdown = worker.submit<Boolean> {
                owner.shutdown(2_000) {
                    events += "http-stop"
                    events += "runtime-stop"
                }
            }
            Thread.yield()
            assertFalse("cleanup must remain queued behind startup", events.contains("http-stop"))

            release.countDown()
            assertTrue(startup.get(2, TimeUnit.SECONDS))
            assertTrue(shutdown.get(2, TimeUnit.SECONDS))
            assertEquals(
                listOf("startup-enter", "http-start", "http-stop", "runtime-stop"),
                events.toList(),
            )
        } finally {
            release.countDown()
            if (!owner.isStopped()) {
                owner.shutdown(2_000) {}
            }
            worker.shutdownNow()
        }
    }

    // Startup stays blocked (30 s) until after shutdown returns, so a shutdown that waited on it hangs.
    @Test(timeout = 10_000)
    fun expiredShutdownReturnsPromptlyThenCleansUpBehindBlockedStartup() {
        val owner = ServiceRuntimeOwner("runtime", "expired-shutdown-test")
        val startupEntered = CountDownLatch(1)
        val releaseStartup = CountDownLatch(1)
        val cleanupRan = CountDownLatch(1)
        val startup = owner.start {
            startupEntered.countDown()
            assertTrue(releaseStartup.await(30, TimeUnit.SECONDS))
        }
        assertTrue(startupEntered.await(2, TimeUnit.SECONDS))

        assertFalse(owner.shutdown(0L) { cleanupRan.countDown() })
        assertFalse(owner.isStopped())
        assertFalse(cycle(owner) { error("late transition ran") }.get(2, TimeUnit.SECONDS))
        assertEquals(1L, cleanupRan.count)

        releaseStartup.countDown()
        assertTrue(startup.get(2, TimeUnit.SECONDS))
        assertTrue(owner.shutdown(2_000L) { error("replacement cleanup ran") })
        assertTrue(cleanupRan.await(2, TimeUnit.SECONDS))
        assertTrue(owner.isStopped())
    }

    @Test fun startupAndReconfigureShareOneLaneAndAdvanceGeneration() {
        val owner = ServiceRuntimeOwner("runtime", "lifecycle-test")
        val events = Collections.synchronizedList(mutableListOf<String>())
        val startEntered = CountDownLatch(1)
        val releaseStart = CountDownLatch(1)

        val start = owner.start {
            events += "start-enter"
            startEntered.countDown()
            assertTrue(releaseStart.await(2, TimeUnit.SECONDS))
            events += "start-exit"
        }
        assertTrue(startEntered.await(2, TimeUnit.SECONDS))
        assertEquals(null, owner.observe())
        val reconfigure = cycle(owner) { events += "reconfigure" }
        assertEquals(listOf("start-enter"), events.toList())

        releaseStart.countDown()
        assertTrue(start.get(2, TimeUnit.SECONDS))
        assertTrue(reconfigure.get(2, TimeUnit.SECONDS))
        assertEquals(listOf("start-enter", "start-exit", "reconfigure"), events.toList())
        assertEquals(2L, owner.observe()!!.generation)
        assertTrue(owner.shutdown(2_000) {})
    }

    @Test fun reconnectRunsOnlyForTheObservedGenerationAndResource() {
        val initial = String(charArrayOf('a'))
        val replacement = String(charArrayOf('b'))
        val owner = ServiceRuntimeOwner(initial, "lifecycle-test")
        assertTrue(owner.start {}.get(2, TimeUnit.SECONDS))
        val oldObservation = owner.observe()!!
        val reconfigureEntered = CountDownLatch(1)
        val releaseReconfigure = CountDownLatch(1)
        val reconfigure = owner.reconfigure(
            retire = {},
            build = { replacement },
            start = {
                reconfigureEntered.countDown()
                assertTrue(releaseReconfigure.await(2, TimeUnit.SECONDS))
            },
        )
        assertTrue(reconfigureEntered.await(2, TimeUnit.SECONDS))
        assertEquals(null, owner.observe())
        var staleReconnectRuns = 0
        val staleReconnect = owner.reconnect(oldObservation) { staleReconnectRuns++ }
        releaseReconfigure.countDown()
        assertTrue(reconfigure.get(2, TimeUnit.SECONDS))
        val currentObservation = owner.observe()!!

        assertFalse(staleReconnect.get(2, TimeUnit.SECONDS))
        assertEquals(0, staleReconnectRuns)
        assertFalse(owner.isCurrent(oldObservation))
        assertTrue(owner.isCurrent(currentObservation))

        val currentGenerationOldResource = ServiceRuntimeOwner.Observation(
            currentObservation.generation,
            oldObservation.value,
        )
        val oldGenerationCurrentResource = ServiceRuntimeOwner.Observation(
            oldObservation.generation,
            currentObservation.value,
        )
        assertFalse(owner.reconnect(currentGenerationOldResource) { staleReconnectRuns++ }.get(2, TimeUnit.SECONDS))
        assertFalse(owner.reconnect(oldGenerationCurrentResource) { staleReconnectRuns++ }.get(2, TimeUnit.SECONDS))
        assertTrue(owner.reconnect(currentObservation) { staleReconnectRuns++ }.get(2, TimeUnit.SECONDS))
        assertEquals(1, staleReconnectRuns)
        assertTrue(owner.shutdown(2_000) {})
    }

    @Test fun resourceLifecycleMonitorDrainsAcceptedReconnectBeforeReplacementRetirement() {
        val initial = String(charArrayOf('a'))
        val replacement = String(charArrayOf('b'))
        val events = Collections.synchronizedList(mutableListOf<String>())
        val initialRuntime = FakeNetwork(initial, events)
        val replacementRuntime = FakeNetwork(replacement, events)
        val owner = ServiceRuntimeOwner(initialRuntime, "lifecycle-monitor-test")
        assertTrue(owner.start {}.get(2, TimeUnit.SECONDS))
        val observation = owner.observe()!!
        val reconnectEntered = CountDownLatch(1)
        val releaseReconnect = CountDownLatch(1)
        val retirementRequested = CountDownLatch(1)
        val reconnect = owner.reconnect(observation) { target ->
            assertSame(initialRuntime, target)
            target.reconnect(reconnectEntered, releaseReconnect)
        }
        try {
            assertTrue(reconnectEntered.await(2, TimeUnit.SECONDS))

            val reconfigure = owner.reconfigure(
                retire = {
                    retirementRequested.countDown()
                    it.stop()
                },
                build = {
                    events += "replacement-build"
                    replacementRuntime
                },
                start = { events += "replacement-start" },
            )
            assertTrue(retirementRequested.await(2, TimeUnit.SECONDS))
            awaitThreadState("lifecycle-monitor-test", Thread.State.BLOCKED)
            assertFalse(reconfigure.isDone)
            assertEquals(listOf("$initial-reconnect-enter"), events.toList())
            assertEquals(null, owner.observe())
            releaseReconnect.countDown()
            assertTrue(reconnect.get(2, TimeUnit.SECONDS))
            assertTrue(reconfigure.get(2, TimeUnit.SECONDS))
            assertSame(replacementRuntime, owner.current())
            assertEquals(observation.generation + 1L, owner.observe()!!.generation)
            assertEquals(
                listOf(
                    "$initial-reconnect-enter",
                    "$initial-reconnect-applied",
                    "$initial-stop-enter",
                    "$initial-stop-exit",
                    "replacement-build",
                    "replacement-start",
                ),
                events.toList(),
            )
            assertTrue(owner.shutdown(2_000, FakeNetwork::stop))
        } finally {
            releaseReconnect.countDown()
            if (!owner.isStopped()) owner.shutdown(2_000, FakeNetwork::stop)
        }
    }

    @Test fun wedgedReconnectsAreBoundedAndFurtherRecoveryEscalates() {
        var saturated = 0
        val owner = ServiceRuntimeOwner(
            "runtime",
            "bounded-recovery-test",
            onRecoverySaturated = { saturated++ },
        )
        assertTrue(owner.start {}.get(2, TimeUnit.SECONDS))
        val observation = owner.observe()!!
        val entered = CountDownLatch(ServiceRuntimeOwner.MAX_RECOVERY_WORKERS)
        val release = CountDownLatch(1)
        val reconnects = (0 until ServiceRuntimeOwner.MAX_RECOVERY_WORKERS).map {
            owner.reconnect(observation) {
                entered.countDown()
                release.await()
            }
        }
        try {
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            assertFalse(owner.reconnect(observation) { error("bounded pool grew") }.get(2, TimeUnit.SECONDS))
            assertEquals(1, saturated)

            release.countDown()
            reconnects.forEach { assertTrue(it.get(2, TimeUnit.SECONDS)) }
        } finally {
            release.countDown()
            owner.shutdown(2_000) {}
        }
    }

    @Test fun shutdownWaitsForRecoveryExitBeforeTeardownAndEventuallyStops() {
        val errors = Collections.synchronizedList(mutableListOf<String>())
        val events = Collections.synchronizedList(mutableListOf<String>())
        val owner = ServiceRuntimeOwner(
            "runtime",
            "late-reconnect-test",
            onError = { operation, error -> errors += "$operation: ${error.message}" },
        )
        assertTrue(owner.start {}.get(2, TimeUnit.SECONDS))
        val observation = owner.observe()!!
        val entered = CountDownLatch(1)
        val interrupted = CountDownLatch(1)
        val release = CountDownLatch(1)
        val teardownRan = CountDownLatch(1)
        try {
            val reconnect = owner.reconnect(observation) {
                events += "reconnect-enter"
                entered.countDown()
                while (release.count > 0L) {
                    try {
                        release.await()
                    } catch (_: InterruptedException) {
                        interrupted.countDown()
                        // Model a blocking dependency that ignores cancellation and returns later.
                    }
                }
                events += "reconnect-exit"
            }
            assertTrue(entered.await(2, TimeUnit.SECONDS))

            assertFalse(owner.shutdown(0L) {
                events += "teardown"
                teardownRan.countDown()
            })
            assertFalse(owner.isStopped())
            assertFalse(owner.reconnect(observation) { error("late reconnect ran") }.get(2, TimeUnit.SECONDS))
            assertFalse(owner.shutdown(0L) { error("replacement teardown ran") })
            assertTrue(interrupted.await(2, TimeUnit.SECONDS))
            assertFalse(teardownRan.await(3, TimeUnit.SECONDS))
            assertEquals(listOf("reconnect-enter"), events.toList())
            assertTrue(errors.isEmpty())

            release.countDown()
            assertTrue(reconnect.get(2, TimeUnit.SECONDS))
            assertTrue(owner.shutdown(2_000L) { error("replacement teardown ran") })
            assertTrue(owner.isStopped())
            assertEquals(listOf("reconnect-enter", "reconnect-exit", "teardown"), events.toList())
            assertTrue(errors.isEmpty())
        } finally {
            release.countDown()
            if (!owner.isStopped()) owner.shutdown(2_000L) {}
        }
    }

    @Test fun shutdownClosesAdmissionAndDrainsOnlyTheActiveTransition() {
        val owner = ServiceRuntimeOwner("runtime", "lifecycle-test")
        assertTrue(owner.start {}.get(2, TimeUnit.SECONDS))
        val events = Collections.synchronizedList(mutableListOf<String>())
        val activeEntered = CountDownLatch(1)
        val releaseActive = CountDownLatch(1)
        val active = cycle(owner) {
            events += "active-enter"
            activeEntered.countDown()
            assertTrue(releaseActive.await(2, TimeUnit.SECONDS))
            events += "active-exit"
        }
        assertTrue(activeEntered.await(2, TimeUnit.SECONDS))
        val queued = cycle(owner) { events += "queued-must-not-run" }

        assertFalse(owner.shutdown(0L) { events += "shutdown" })
        assertFalse(cycle(owner) { events += "late-must-not-run" }.get(2, TimeUnit.SECONDS))
        releaseActive.countDown()
        assertFalse(
            "an active transition must not complete after shutdown crosses its terminal phase fence",
            active.get(2, TimeUnit.SECONDS),
        )
        assertFalse(queued.get(2, TimeUnit.SECONDS))
        assertTrue(owner.shutdown(2_000L) { events += "replacement-shutdown-must-not-run" })
        assertEquals(listOf("active-enter", "active-exit", "shutdown"), events.toList())
        assertTrue(owner.isStopped())
    }

    @Test fun latestRequestsQueuedBehindStartupSampleOnlyTheNewestValue() {
        val desired = AtomicReference("a")
        val events = Collections.synchronizedList(mutableListOf<String>())
        val startupEntered = CountDownLatch(1)
        val releaseStartup = CountDownLatch(1)
        val latestCompleted = CountDownLatch(1)
        val owner = ServiceRuntimeOwner(
            initial = "initial",
            threadName = "latest-startup-test",
            latestOperation = latestPolicy("latest-test") {
                val target = desired.get()
                replace(
                    retire = {},
                    build = { target },
                    start = {},
                    complete = {
                        events += target
                        latestCompleted.countDown()
                    },
                )
            },
        )
        val startup = owner.start {
            startupEntered.countDown()
            assertTrue(releaseStartup.await(2, TimeUnit.SECONDS))
        }
        try {
            assertTrue(startupEntered.await(2, TimeUnit.SECONDS))
            assertEquals(ServiceRuntimeOwner.LatestAdmission.ACCEPTED, owner.requestLatest())
            desired.set("b")
            assertEquals(ServiceRuntimeOwner.LatestAdmission.COALESCED, owner.requestLatest())
            desired.set("c")
            assertEquals(ServiceRuntimeOwner.LatestAdmission.COALESCED, owner.requestLatest())
            assertEquals(1, owner.pendingLatestCount())

            releaseStartup.countDown()
            assertTrue(startup.get(2, TimeUnit.SECONDS))
            assertTrue(latestCompleted.await(2, TimeUnit.SECONDS))
            // Two barriers prove the latest runner's tail-scheduling decision has drained even if the
            // first barrier was admitted before that runner reached its finally block.
            assertTrue(cycle(owner).get(2, TimeUnit.SECONDS))
            assertTrue(cycle(owner).get(2, TimeUnit.SECONDS))
            assertEquals(listOf("c"), events.toList())
            assertEquals("c", owner.current())
        } finally {
            releaseStartup.countDown()
            owner.shutdown(2_000L) {}
        }
    }

    @Test fun latestRerunYieldsToOrdinaryLaneWorkAndKeepsOnlyNewestPendingValue() {
        val desired = AtomicReference("a")
        val events = Collections.synchronizedList(mutableListOf<String>())
        val firstStarted = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val newestCompleted = CountDownLatch(1)
        val owner = ServiceRuntimeOwner(
            initial = "initial",
            threadName = "latest-fairness-test",
            latestOperation = latestPolicy {
                val target = desired.get()
                replace(
                    retire = {},
                    build = { target },
                    start = {
                        if (target == "a") {
                            firstStarted.countDown()
                            assertTrue(releaseFirst.await(2, TimeUnit.SECONDS))
                        }
                    },
                    complete = {
                        events += "latest-$target"
                        if (target == "c") newestCompleted.countDown()
                    },
                )
            },
        )
        assertTrue(owner.start {}.get(2, TimeUnit.SECONDS))
        try {
            assertEquals(ServiceRuntimeOwner.LatestAdmission.ACCEPTED, owner.requestLatest())
            assertTrue(firstStarted.await(2, TimeUnit.SECONDS))
            desired.set("b")
            assertEquals(ServiceRuntimeOwner.LatestAdmission.ACCEPTED, owner.requestLatest())
            desired.set("c")
            assertEquals(ServiceRuntimeOwner.LatestAdmission.COALESCED, owner.requestLatest())
            val ordinary = owner.reconfigure(
                retire = {},
                build = { it },
                start = { events += "ordinary" },
            )

            releaseFirst.countDown()
            assertTrue(ordinary.get(2, TimeUnit.SECONDS))
            assertTrue(newestCompleted.await(2, TimeUnit.SECONDS))
            assertEquals(listOf("latest-a", "ordinary", "latest-c"), events.toList())
            assertEquals("c", owner.current())
        } finally {
            releaseFirst.countDown()
            owner.shutdown(2_000L) {}
        }
    }

    @Test fun latestMutationCannotBeRetainedAcrossOwnerLaneCallbacks() {
        val escaped = AtomicReference<(() -> String)?>(null)
        val firstCompleted = CountDownLatch(1)
        val secondCompleted = CountDownLatch(1)
        val escapedRejected = AtomicReference(false)
        val owner = ServiceRuntimeOwner(
            initial = "runtime",
            threadName = "latest-scope-test",
            latestOperation = latestPolicy {
                val previous = escaped.getAndSet { current }
                if (previous == null) {
                    firstCompleted.countDown()
                } else {
                    escapedRejected.set(runCatching(previous).isFailure)
                    secondCompleted.countDown()
                }
            },
        )
        assertTrue(owner.start {}.get(2, TimeUnit.SECONDS))
        try {
            assertEquals(ServiceRuntimeOwner.LatestAdmission.ACCEPTED, owner.requestLatest())
            assertTrue(firstCompleted.await(2, TimeUnit.SECONDS))
            assertEquals(ServiceRuntimeOwner.LatestAdmission.ACCEPTED, owner.requestLatest())
            assertTrue(secondCompleted.await(2, TimeUnit.SECONDS))
            assertTrue(escapedRejected.get())
        } finally {
            owner.shutdown(2_000L) {}
        }
    }

    @Test fun latestMutationAuthorityCannotBeCounterfeitedByAModuleCaller() {
        val owner = ServiceRuntimeOwner(
            initial = "runtime",
            threadName = "latest-counterfeit-test",
            latestOperation = latestPolicy {},
        )

        assertTrue(runCatching { owner.LatestMutation(Any(), Any()) }.isFailure)
        assertTrue(owner.start {}.get(2, TimeUnit.SECONDS))
        assertTrue(owner.shutdown(2_000L) {})
    }

    @Test fun latestTicketTimesOutBehindStartupAndRetainsOneLaterSuccessor() {
        val now = AtomicLong(0L)
        val scheduled = Collections.synchronizedList(mutableListOf<Runnable>())
        val cancelled = Collections.synchronizedList(mutableListOf<Runnable>())
        val timeouts = AtomicInteger()
        val operations = Collections.synchronizedList(mutableListOf<Long>())
        val startupEntered = CountDownLatch(1)
        val releaseStartup = CountDownLatch(1)
        val successorCompleted = CountDownLatch(1)
        val owner = ServiceRuntimeOwner(
            initial = "runtime",
            threadName = "latest-queue-timeout-test",
            latestOperation = latestPolicy(
                timeout = LatestOperationTimeoutPolicy(
                    budgetMs = 10L,
                    nanoTime = now::get,
                    schedule = { task, delayMs ->
                        assertEquals(10L, delayMs)
                        scheduled += task
                        true
                    },
                    cancel = { cancelled += it },
                    onTimeout = { timeouts.incrementAndGet() },
                ),
            ) {
                operations += deadline.remainingMs()
                successorCompleted.countDown()
            },
        )
        val startup = owner.start {
            startupEntered.countDown()
            assertTrue(releaseStartup.await(2, TimeUnit.SECONDS))
        }
        try {
            assertTrue(startupEntered.await(2, TimeUnit.SECONDS))
            assertEquals(ServiceRuntimeOwner.LatestAdmission.ACCEPTED, owner.requestLatest())
            assertEquals(ServiceRuntimeOwner.LatestAdmission.COALESCED, owner.requestLatest())
            assertEquals(1, owner.pendingLatestCount())
            assertEquals(1, scheduled.size)

            now.set(TimeUnit.MILLISECONDS.toNanos(11L))
            scheduled.single().run()
            assertEquals(1, timeouts.get())
            assertEquals(0, owner.pendingLatestCount())
            assertEquals(ServiceRuntimeOwner.LatestAdmission.ACCEPTED, owner.requestLatest())
            assertEquals(ServiceRuntimeOwner.LatestAdmission.COALESCED, owner.requestLatest())
            assertEquals(1, owner.pendingLatestCount())
            releaseStartup.countDown()
            assertTrue(startup.get(2, TimeUnit.SECONDS))
            assertTrue(successorCompleted.await(2, TimeUnit.SECONDS))

            assertEquals(listOf(10L), operations.toList())
            assertEquals(2, scheduled.size)
            awaitCondition { cancelled.size == 2 }
            scheduled.forEach(Runnable::run)
            assertEquals(1, timeouts.get())
        } finally {
            releaseStartup.countDown()
            owner.shutdown(2_000L) {}
        }
    }

    @Test fun latestWatchdogScheduleRejectionDropsTheTicketAndRequestsRecoveryOnce() {
        var scheduled: Runnable? = null
        var cancelled: Runnable? = null
        val timeouts = AtomicInteger()
        val operations = AtomicInteger()
        val errors = Collections.synchronizedList(mutableListOf<String>())
        val owner = ServiceRuntimeOwner(
            initial = "runtime",
            threadName = "latest-watchdog-rejection-test",
            onError = { operation, _ -> errors += operation },
            latestOperation = latestPolicy(
                timeout = LatestOperationTimeoutPolicy(
                    budgetMs = 10L,
                    schedule = { task, _ -> scheduled = task; false },
                    cancel = { cancelled = it },
                    onTimeout = { timeouts.incrementAndGet() },
                ),
            ) { operations.incrementAndGet() },
        )
        try {
            assertTrue(owner.start {}.get(2, TimeUnit.SECONDS))
            assertEquals(ServiceRuntimeOwner.LatestAdmission.CLOSED, owner.requestLatest())
            assertSame(scheduled, cancelled)
            assertEquals(1, timeouts.get())
            assertEquals(0, operations.get())
            assertEquals(listOf("latest-admission"), errors.toList())
        } finally {
            owner.shutdown(2_000L) {}
        }
    }

    @Test fun latestOwnerPostcheckDetectsALateWatchdogWithoutDoubleTimeout() {
        val now = AtomicLong(0L)
        var scheduled: Runnable? = null
        var cancelled: Runnable? = null
        val timeouts = AtomicInteger()
        val completed = CountDownLatch(1)
        val owner = ServiceRuntimeOwner(
            initial = "runtime",
            threadName = "latest-late-watchdog-test",
            latestOperation = latestPolicy(
                timeout = LatestOperationTimeoutPolicy(
                    budgetMs = 10L,
                    nanoTime = now::get,
                    schedule = { task, _ -> scheduled = task; true },
                    cancel = { cancelled = it },
                    onTimeout = { timeouts.incrementAndGet() },
                ),
            ) {
                now.set(TimeUnit.MILLISECONDS.toNanos(11L))
                completed.countDown()
            },
        )
        try {
            assertTrue(owner.start {}.get(2, TimeUnit.SECONDS))
            assertEquals(ServiceRuntimeOwner.LatestAdmission.ACCEPTED, owner.requestLatest())
            assertTrue(completed.await(2, TimeUnit.SECONDS))
            awaitCondition { timeouts.get() == 1 }
            assertSame(scheduled, cancelled)
            scheduled!!.run()
            assertEquals(1, timeouts.get())
        } finally {
            owner.shutdown(2_000L) {}
        }
    }

    @Test fun expiredLatestBuildPublishesOnlyTheTerminalCleanupTarget() {
        val now = AtomicLong(0L)
        val events = Collections.synchronizedList(mutableListOf<String>())
        val timedOut = CountDownLatch(1)
        val owner = ServiceRuntimeOwner(
            initial = "initial",
            threadName = "latest-build-deadline-test",
            latestOperation = latestPolicy(
                timeout = LatestOperationTimeoutPolicy(
                    budgetMs = 10L,
                    nanoTime = now::get,
                    schedule = { _, _ -> true },
                    cancel = {},
                    onTimeout = { timedOut.countDown() },
                ),
            ) {
                replace(
                    retire = { events += "retire-$it" },
                    build = {
                        events += "build"
                        now.set(TimeUnit.MILLISECONDS.toNanos(11L))
                        "replacement"
                    },
                    start = { events += "start" },
                    complete = { events += "complete" },
                )
            },
        )
        assertTrue(owner.start {}.get(2, TimeUnit.SECONDS))
        assertEquals(ServiceRuntimeOwner.LatestAdmission.ACCEPTED, owner.requestLatest())
        assertTrue(timedOut.await(2, TimeUnit.SECONDS))
        assertTrue(owner.shutdown(2_000L) { events += "cleanup-$it" })
        assertEquals(listOf("retire-initial", "build", "cleanup-replacement"), events.toList())
    }

    @Test fun failedLatestReplacementLeavesItsPartialResourceForThePendingRetry() {
        val desired = AtomicReference("a")
        val events = Collections.synchronizedList(mutableListOf<String>())
        val firstStarted = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val recovered = CountDownLatch(1)
        val owner = ServiceRuntimeOwner(
            initial = "initial",
            threadName = "latest-failure-test",
            latestOperation = latestPolicy {
                val target = desired.get()
                val replaced = replace(
                    retire = { events += "retire-$it" },
                    build = { target },
                    start = {
                        events += "start-$target"
                        if (target == "a") {
                            firstStarted.countDown()
                            assertTrue(releaseFirst.await(2, TimeUnit.SECONDS))
                            error("a failed")
                        }
                    },
                    complete = {
                        events += "complete-$target"
                    },
                )
                if (target == "b" && replaced) recovered.countDown()
            },
        )
        assertTrue(owner.start {}.get(2, TimeUnit.SECONDS))
        try {
            assertEquals(ServiceRuntimeOwner.LatestAdmission.ACCEPTED, owner.requestLatest())
            assertTrue(firstStarted.await(2, TimeUnit.SECONDS))
            desired.set("b")
            assertEquals(ServiceRuntimeOwner.LatestAdmission.ACCEPTED, owner.requestLatest())

            releaseFirst.countDown()
            assertTrue(recovered.await(2, TimeUnit.SECONDS))
            assertEquals(
                listOf("retire-initial", "start-a", "retire-a", "start-b", "complete-b"),
                events.toList(),
            )
            assertEquals("b", owner.current())
            assertTrue(owner.isRunning())
        } finally {
            releaseFirst.countDown()
            owner.shutdown(2_000L) {}
        }
    }

    // Retirement stays blocked (30 s) until after shutdown returns, so a shutdown that waited on it hangs.
    @Test(timeout = 10_000)
    fun zeroBudgetShutdownDropsPendingLatestWorkAndStopsBeforeBuild() {
        val events = Collections.synchronizedList(mutableListOf<String>())
        val retireEntered = CountDownLatch(1)
        val releaseRetire = CountDownLatch(1)
        val cleanupRan = CountDownLatch(1)
        val cleaned = AtomicReference<String>()
        val owner = ServiceRuntimeOwner(
            initial = "initial",
            threadName = "latest-shutdown-test",
            latestOperation = latestPolicy {
                replace(
                    retire = {
                        events += "retire"
                        retireEntered.countDown()
                        assertTrue(releaseRetire.await(30, TimeUnit.SECONDS))
                    },
                    build = { events += "build"; "replacement" },
                    start = { events += "start" },
                    complete = { events += "complete" },
                )
            },
        )
        assertTrue(owner.start {}.get(2, TimeUnit.SECONDS))
        assertEquals(ServiceRuntimeOwner.LatestAdmission.ACCEPTED, owner.requestLatest())
        assertTrue(retireEntered.await(2, TimeUnit.SECONDS))
        assertEquals(ServiceRuntimeOwner.LatestAdmission.ACCEPTED, owner.requestLatest())

        assertFalse(owner.shutdown(0L) {
            cleaned.set(it)
            events += "cleanup"
            cleanupRan.countDown()
        })
        assertEquals(ServiceRuntimeOwner.LatestAdmission.CLOSED, owner.requestLatest())
        assertEquals(0, owner.pendingLatestCount())

        releaseRetire.countDown()
        assertTrue(cleanupRan.await(2, TimeUnit.SECONDS))
        assertTrue(owner.shutdown(2_000L) { error("replacement cleanup ran") })
        assertEquals("initial", cleaned.get())
        assertEquals(listOf("retire", "cleanup"), events.toList())
        assertTrue(owner.isStopped())
    }

    @Test fun shutdownDuringBuildPublishesReplacementOnlyAsTerminalCleanupTarget() {
        val events = Collections.synchronizedList(mutableListOf<String>())
        val buildEntered = CountDownLatch(1)
        val releaseBuild = CountDownLatch(1)
        val cleanupRan = CountDownLatch(1)
        val cleaned = AtomicReference<String>()
        val owner = ServiceRuntimeOwner(
            initial = "initial",
            threadName = "latest-build-shutdown-test",
            latestOperation = latestPolicy {
                replace(
                    retire = { events += "retire-$it" },
                    build = {
                        events += "build"
                        buildEntered.countDown()
                        assertTrue(releaseBuild.await(2, TimeUnit.SECONDS))
                        "replacement"
                    },
                    start = { events += "start" },
                    complete = { events += "complete" },
                )
            },
        )
        assertTrue(owner.start {}.get(2, TimeUnit.SECONDS))
        assertEquals(ServiceRuntimeOwner.LatestAdmission.ACCEPTED, owner.requestLatest())
        assertTrue(buildEntered.await(2, TimeUnit.SECONDS))

        assertFalse(owner.shutdown(0L) {
            cleaned.set(it)
            events += "cleanup-$it"
            cleanupRan.countDown()
        })
        releaseBuild.countDown()
        assertTrue(cleanupRan.await(2, TimeUnit.SECONDS))
        assertTrue(owner.shutdown(2_000L) { error("replacement cleanup ran") })
        assertEquals("replacement", cleaned.get())
        assertEquals(listOf("retire-initial", "build", "cleanup-replacement"), events.toList())
    }

    @Test fun failedShutdownIsStickyAndReportedAsFinishedWithoutBeingStopped() {
        val errors = Collections.synchronizedList(mutableListOf<String>())
        val owner = ServiceRuntimeOwner(
            "runtime",
            "failed-shutdown-test",
            onError = { operation, error -> errors += "$operation: ${error.message}" },
        )
        assertTrue(owner.start {}.get(2, TimeUnit.SECONDS))
        var cleanupRuns = 0

        assertFalse(owner.shutdown(2_000L) {
            cleanupRuns++
            error("cleanup failed")
        })
        assertTrue(owner.hasFailedShutdown())
        assertFalse(owner.isStopped())
        assertFalse(owner.shutdown(2_000L) { error("replacement cleanup ran") })
        assertEquals(1, cleanupRuns)
        assertEquals(listOf("shutdown: cleanup failed"), errors.toList())
    }

    private fun cycle(
        owner: ServiceRuntimeOwner<String>,
        block: () -> Unit = {},
    ) = owner.reconfigure(
        retire = {},
        build = { it },
        start = { block() },
    )

    private fun <T : Any> latestPolicy(
        name: String = "latest",
        timeout: LatestOperationTimeoutPolicy? = null,
        operation: ServiceRuntimeOwner<T>.LatestMutation.() -> Unit,
    ) = LatestOperationPolicy(name = name, timeout = timeout, operation = operation)

    private fun awaitCondition(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        while (!condition() && System.nanoTime() < deadline) Thread.yield()
        assertTrue("condition did not become true", condition())
    }

    private fun awaitEvent(events: List<String>, expected: String) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        while (!events.contains(expected) && System.nanoTime() < deadline) Thread.yield()
        assertTrue("timed out waiting for $expected; events=$events", events.contains(expected))
    }

    private fun awaitThreadState(threadName: String, expected: Thread.State) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        var observed: Thread.State? = null
        while (System.nanoTime() < deadline) {
            observed = Thread.getAllStackTraces().keys.firstOrNull { it.name == threadName }?.state
            if (observed == expected) return
            Thread.yield()
        }
        assertEquals("thread $threadName did not reach $expected", expected, observed)
    }
}
