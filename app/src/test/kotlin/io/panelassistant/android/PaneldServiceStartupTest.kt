package io.panelassistant.android

import io.panelassistant.android.testsupport.scratchDirectory

import io.panelassistant.android.control.CompanionDataOperationGate
import io.panelassistant.android.control.CompanionDataOperationState
import io.panelassistant.android.control.NavbarModeApplyOutcome
import io.panelassistant.android.control.NavbarModeApplyStatus
import io.panelassistant.android.util.CompanionOperationStatus
import io.panelassistant.android.util.DurableRecoveryMarker
import io.panelassistant.android.util.LatestOperationPolicy
import io.panelassistant.android.util.RendererPreparationCoordinator
import io.panelassistant.android.util.ServiceRuntimeOwner
import io.panelassistant.android.util.MonotonicDeadline
import java.util.Collections
import java.nio.file.Files
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PaneldServiceStartupTest {
    @Test fun inlineLateNavbarFailureDoesNotSpendRetryBudgetTwice() {
        val service = PaneldService()
        val attempts = AtomicInteger()
        val retried = CountDownLatch(2)
        val retryCompletion = LinkedBlockingQueue<(LiveSettingApplyResult) -> Unit>()
        val schedule = PaneldService::class.java.getDeclaredMethod("scheduleLiveSettingRetries", Set::class.java)
            .apply { isAccessible = true }
        fun scheduleRetry() { schedule.invoke(service, setOf("navbar_mode")) }
        val authority = LiveSettingAuthority(setOf("navbar_mode"), onLatePending = { scheduleRetry() })
        val owner = ServiceRuntimeOwner(
            initial = Any(),
            threadName = "navbar-startup-retry-test",
            latestOperation = LatestOperationPolicy(operation = {
                authority.replayKeysObserved(setOf("navbar_mode")) { _, _, _, _ ->
                    val attempt = attempts.incrementAndGet()
                    retried.countDown()
                    if (attempt == 2) LiveSettingApplication(LiveSettingApplyResult.DEFERRED) { retryCompletion.add(it) }
                    else LiveSettingApplication.immediate(LiveSettingApplyResult.FAILED)
                }
                scheduleRetry()
            }),
        )
        listOf("liveSettingAuthority" to authority, "runtime" to owner).forEach { (name, value) ->
            PaneldService::class.java.getDeclaredField(name).apply { isAccessible = true }.set(service, value)
        }
        try {
            assertTrue(owner.start {}.get(1, TimeUnit.SECONDS))
            assertEquals(
                LiveSettingRequestOutcome.DEFERRED,
                authority.applyOrQueueOutcomeObserved("navbar_mode", "Swipe reveal", "Swipe reveal") { _, _, _ ->
                    attempts.incrementAndGet()
                    // Completion can race the bounded dispatcher wait and arrive during registration.
                    LiveSettingApplication(LiveSettingApplyResult.DEFERRED) { it(LiveSettingApplyResult.FAILED) }
                },
            )
            scheduleRetry()
            scheduleRetry()
            val completeRetry = requireNotNull(retryCompletion.poll(5, TimeUnit.SECONDS))
            // Duplicate timers have fired before this first actual retry fails.
            Thread.sleep(1_250)
            completeRetry(LiveSettingApplyResult.FAILED)
            assertTrue("the service must retry a failed restore", retried.await(5, TimeUnit.SECONDS))
            // Wait past the production one-second delay to prove no third retry was admitted.
            Thread.sleep(1_250)
            assertEquals(3, attempts.get())
            assertEquals(mapOf("navbar_mode" to "Swipe reveal"), authority.pendingSnapshot())
        } finally {
            (PaneldService::class.java.getDeclaredField("scope").apply { isAccessible = true }
                .get(service) as CoroutineScope).cancel()
            owner.shutdown(1_000L) {}
        }
    }

    @Test fun slowNavbarAcknowledgementFailuresReceiveTwoActualServiceRetries() {
        val service = PaneldService()
        val attempts = AtomicInteger()
        val actuations = LinkedBlockingQueue<CompletableFuture<NavbarModeApplyOutcome>>()
        val retried = CountDownLatch(1)
        val dispatcher = CommandQueue(threadName = "navbar-late-retry-test")
        val schedule = PaneldService::class.java.getDeclaredMethod("scheduleLiveSettingRetries", Set::class.java)
            .apply { isAccessible = true }
        fun scheduleRetry() { schedule.invoke(service, setOf("navbar_mode")) }
        val authority = LiveSettingAuthority(setOf("navbar_mode"), onLatePending = { scheduleRetry() })
        fun apply(value: String): LiveSettingApplication = liveSettingApplication(
            dispatcher.runLatestResult("http:navbar_mode") {
                val attempt = attempts.incrementAndGet()
                if (attempt == 3) retried.countDown()
                applyAcknowledgedNavbarMode(
                    payload = value,
                    previousMode = "Swipe reveal",
                    actuate = {
                        if (attempt <= 2) CompletableFuture<NavbarModeApplyOutcome>().also(actuations::add)
                        else CompletableFuture.completedFuture(NavbarModeApplyOutcome(it, NavbarModeApplyStatus.FAILED))
                    },
                    rollback = { _, previous ->
                        CompletableFuture.completedFuture(NavbarModeApplyOutcome(previous, NavbarModeApplyStatus.APPLIED))
                    },
                    persist = { true },
                    reconcile = {},
                )
            },
        )
        val owner = ServiceRuntimeOwner(
            initial = Any(),
            threadName = "navbar-delayed-retry-runtime",
            latestOperation = LatestOperationPolicy(operation = {
                authority.replayKeysObserved(setOf("navbar_mode")) { _, value, _, _ -> apply(value) }
                scheduleRetry()
            }),
        )
        listOf("liveSettingAuthority" to authority, "runtime" to owner).forEach { (name, value) ->
            PaneldService::class.java.getDeclaredField(name).apply { isAccessible = true }.set(service, value)
        }
        try {
            assertTrue(owner.start {}.get(1, TimeUnit.SECONDS))
            assertEquals(
                LiveSettingRequestOutcome.DEFERRED,
                authority.applyOrQueueOutcomeObserved("navbar_mode", "Swipe reveal", "Swipe reveal") { _, value, _ -> apply(value) },
            )
            scheduleRetry()
            repeat(2) { index ->
                val actuation = requireNotNull(actuations.poll(5, TimeUnit.SECONDS))
                // Longer than both the real dispatcher wait and both one-second retry delays.
                Thread.sleep(2_250)
                assertEquals(index + 1, attempts.get())
                assertEquals(emptySet<String>(), authority.retryablePendingKeys())
                actuation.complete(NavbarModeApplyOutcome("Swipe reveal", NavbarModeApplyStatus.FAILED))
            }
            assertTrue("late terminal failures must receive both actual retries", retried.await(5, TimeUnit.SECONDS))
            Thread.sleep(1_250)
            assertEquals(3, attempts.get())
            assertEquals(mapOf("navbar_mode" to "Swipe reveal"), authority.pendingSnapshot())
        } finally {
            actuations.forEach { it.complete(NavbarModeApplyOutcome("Swipe reveal", NavbarModeApplyStatus.FAILED)) }
            dispatcher.closeAndDrain(MonotonicDeadline(1_000))
            (PaneldService::class.java.getDeclaredField("scope").apply { isAccessible = true }
                .get(service) as CoroutineScope).cancel()
            owner.shutdown(1_000L) {}
        }
    }

    @Test fun unrelatedConfigChangesDoNotRefreshAutoSleepOrOtherLiveOwners() {
        listOf(emptySet(), setOf("touch_sound"), setOf("ha_expose_touch_sound")).forEach { keys ->
            assertEquals(
                ConfigOwnerRefreshPlan(false, false, false, false, false, false, false, false, false),
                configOwnerRefreshPlan(keys),
            )
        }
    }

    @Test fun configOwnerRefreshesAreExplicitlyKeyed() {
        assertEquals(
            ConfigOwnerRefreshPlan(true, true, false, false, true, true, false, true, false),
            configOwnerRefreshPlan(setOf("ha_url")),
        )
        assertEquals(
            ConfigOwnerRefreshPlan(false, false, true, true, false, false, false, false, false),
            configOwnerRefreshPlan(setOf("log_ship_host", "keep_awake")),
        )
        assertTrue(configOwnerRefreshPlan(setOf("panel_id")).autoSleep)
        assertTrue(configOwnerRefreshPlan(setOf("presence_mode")).autoSleep)
        assertTrue(configOwnerRefreshPlan(setOf("auto_sleep_touch_delay_seconds")).autoSleep)
        assertTrue(configOwnerRefreshPlan(setOf("log_ship_system_enabled")).logShipping)
        assertTrue(configOwnerRefreshPlan(setOf("dashboard_package")).rendererTarget)
        assertEquals(
            ConfigOwnerRefreshPlan(false, false, false, false, false, false, false, false, true),
            configOwnerRefreshPlan(setOf("friendly_name")),
        )
    }

    @Test fun liveSettingRetryBudgetIsBounded() {
        assertEquals(1, nextLiveSettingRetryAttempt(0))
        assertEquals(2, nextLiveSettingRetryAttempt(1))
        assertEquals(null, nextLiveSettingRetryAttempt(2))
    }

    @Test fun permanentPolicyRecoveryFailureForcesFreshProcessAfterBoundedAttempts() {
        assertFalse(shouldForceFreshProcessAfterExternalRecovery(4, 5, false, true, true, true))
        assertTrue(shouldForceFreshProcessAfterExternalRecovery(5, 5, false, true, true, true))
        assertFalse(shouldForceFreshProcessAfterExternalRecovery(5, 5, false, true, false, true))
        assertFalse(shouldForceFreshProcessAfterExternalRecovery(5, 5, true, true, true, true))
    }

    @Test fun blockedReplacementPublishesItsOwnSnapshotThenConflatedNewerConfigReplacesAgain() {
        fun snapshot(name: String) = NetworkConfigurationSnapshot(
            runtime = NetworkRuntimeIdentity(
                panelId = name,
                friendlyName = "Panel $name",
                httpPort = 8_888,
            ),
            haLink = HaAuthOwner("http://ha/$name", "refresh-$name", "client-$name", ""),
        )

        val initial = snapshot("initial")
        val replacementA = snapshot("a")
        val replacementB = snapshot("b")
        val desired = AtomicReference(replacementA)
        val replacements = Collections.synchronizedList(mutableListOf<NetworkConfigurationSnapshot>())
        val replacementAStarted = CountDownLatch(1)
        val releaseReplacementA = CountDownLatch(1)
        val replacementBCompleted = CountDownLatch(1)
        val owner = ServiceRuntimeOwner(
            initial = initial,
            threadName = "network-identity-race-test",
            latestOperation = LatestOperationPolicy(operation = {
                val target = desired.get()
                if (target.runtime != current.runtime) {
                    // Model constructors consuming the immutable target rather than rereading desired config.
                    val concreteReplacement = target
                    replacements += concreteReplacement
                    replace(
                        retire = {},
                        build = { concreteReplacement },
                        start = {
                            if (concreteReplacement == replacementA) {
                                replacementAStarted.countDown()
                                assertTrue(releaseReplacementA.await(2, TimeUnit.SECONDS))
                            }
                        },
                        complete = {
                            if (concreteReplacement == replacementB) replacementBCompleted.countDown()
                        },
                    )
                }
            }),
        )

        try {
            assertTrue(owner.start {}.get(1, TimeUnit.SECONDS))
            assertEquals(ServiceRuntimeOwner.LatestAdmission.ACCEPTED, owner.requestLatest())
            assertTrue(replacementAStarted.await(1, TimeUnit.SECONDS))
            desired.set(replacementB)
            assertEquals(ServiceRuntimeOwner.LatestAdmission.ACCEPTED, owner.requestLatest())
            releaseReplacementA.countDown()
            assertTrue(replacementBCompleted.await(1, TimeUnit.SECONDS))
            assertEquals(listOf(replacementA, replacementB), replacements.toList())
            assertEquals(replacementB, owner.current())
        } finally {
            releaseReplacementA.countDown()
            owner.shutdown(1_000L) {}
        }
    }

    @Test fun ordinaryStartupRecoveryIsBoundedAndResetsAfterWindowOrClockRollback() {
        assertEquals(StartupRecoveryDecision(true, 1), startupRecoveryDecision(0, 0, 1_000))
        assertEquals(StartupRecoveryDecision(true, 3), startupRecoveryDecision(2, 900, 1_000))
        assertEquals(StartupRecoveryDecision(false, 4), startupRecoveryDecision(3, 900, 1_000))
        assertEquals(StartupRecoveryDecision(true, 1), startupRecoveryDecision(9, 1_000, 700_001))
        assertEquals(StartupRecoveryDecision(true, 1), startupRecoveryDecision(9, 2_000, 1_000))
    }

    @Test fun successfulOrdinaryStartupBecomesRunning() {
        assertEquals(
            ServiceStartupDisposition.RUNNING,
            awaitServiceStartup(CompletableFuture.completedFuture(true), profileActivationGeneration = null),
        )
    }

    @Test fun failedOrdinaryStartupBecomesDegraded() {
        assertEquals(
            ServiceStartupDisposition.DEGRADED,
            awaitServiceStartup(CompletableFuture.completedFuture(false), profileActivationGeneration = null),
        )
    }

    @Test fun failedProfileActivationStillRequestsRollback() {
        assertEquals(
            ServiceStartupDisposition.PROFILE_ACTIVATION_ROLLBACK,
            awaitServiceStartup(CompletableFuture.completedFuture(false), profileActivationGeneration = 41L),
        )
    }

    @Test fun exceptionalOrdinaryStartupBecomesDegraded() {
        val startup = CompletableFuture<Boolean>().apply {
            completeExceptionally(IllegalStateException("bind failed"))
        }

        assertEquals(
            ServiceStartupDisposition.DEGRADED,
            awaitServiceStartup(startup, profileActivationGeneration = null),
        )
    }

    @Test fun mdnsStartsBeforeRendererReconciliationAndLearning() {
        val events = mutableListOf<String>()

        val result = prepareEntityLearningStartup(
            startMdns = { events += "mdns" },
            reconcileRenderer = {
                events += "reconcile"
                RendererPreparationCoordinator.Result.ALREADY_READY
            },
            startLearning = { events += "learning" },
        )

        assertEquals(RendererPreparationCoordinator.Result.ALREADY_READY, result)
        assertEquals(listOf("mdns", "reconcile", "learning"), events)
    }

    @Test fun closedRendererPreparationDoesNotStartLearning() {
        var learningStarted = false

        val result = prepareEntityLearningStartup(
            startMdns = {},
            reconcileRenderer = { RendererPreparationCoordinator.Result.CLOSED },
            startLearning = { learningStarted = true },
        )

        assertEquals(RendererPreparationCoordinator.Result.CLOSED, result)
        assertFalse(learningStarted)
    }

    @Test fun processRestartReconstructsCompanionGateUntilStatusIsAffirmativelySafe() {
        val pkg = "io.homeassistant.companion.android.minimal"
        for (status in CompanionOperationStatus.entries) {
            val dir = scratchDirectory("companion-startup-test").toFile()
            val operationState = CompanionDataOperationState.forTest(
                DurableRecoveryMarker(dir.resolve("pending")),
            )
            assertTrue(operationState.arm())
            var retained: CompanionDataOperationGate.Lease? = null

            assertEquals(
                status,
                restoreCompanionLaunchSuppression(
                    packageName = pkg,
                    operationState = operationState,
                    operationStatus = { status },
                    retain = { retained = it },
                ),
            )

            val unsafe = status == CompanionOperationStatus.BUSY ||
                status == CompanionOperationStatus.UNSUPPORTED ||
                status == CompanionOperationStatus.UNAVAILABLE
            assertEquals("$status gate state", unsafe, CompanionDataOperationGate.blocks(pkg))
            assertEquals("$status retention", unsafe, retained != null)
            assertEquals("$status marker state", unsafe, operationState.isPending())
            retained?.close()
            assertFalse(CompanionDataOperationGate.blocks(pkg))
            dir.deleteRecursively()
        }
    }

    @Test fun ordinaryNoMarkerNoHelperStartupDoesNotAcquireCompanionGate() {
        val pkg = "io.homeassistant.companion.android"
        val dir = scratchDirectory("companion-startup-test").toFile()
        val operationState = CompanionDataOperationState.forTest(
            DurableRecoveryMarker(dir.resolve("pending")),
        )
        var statusProbed = false
        var retained: CompanionDataOperationGate.Lease? = null
        assertEquals(
            CompanionOperationStatus.IDLE,
            restoreCompanionLaunchSuppression(
                pkg,
                operationState,
                { statusProbed = true; CompanionOperationStatus.UNAVAILABLE },
                { retained = it },
            ),
        )
        assertFalse(statusProbed)
        assertFalse(CompanionDataOperationGate.blocks(pkg))
        assertEquals(null, retained)
        dir.deleteRecursively()
    }

    @Test fun successfulBorrowCommitNotifiesLearnerAfterPersistence() {
        val events = mutableListOf<String>()

        val committed = commitBorrowedRendererTarget(
            commit = { events += "persist"; true },
            onCommitted = { events += "notify" },
        )

        assertTrue(committed)
        assertEquals(listOf("persist", "notify"), events)
    }

    @Test fun failedBorrowCommitDoesNotNotifyLearner() {
        var notified = false

        val committed = commitBorrowedRendererTarget(
            commit = { false },
            onCommitted = { notified = true },
        )

        assertFalse(committed)
        assertFalse(notified)
    }

    @Test fun pendingKioskRecoveryRetriesAfterEscapeWindowBeforeEnabling() {
        val pauses = mutableListOf<Long>()
        var attempts = 0
        var enables = 0

        val recovered = recoverAndMaybeEnableKiosk(
            escapeDelayMs = 60_000L,
            retryDelayMs = 1_000L,
            maxAttempts = 3,
            shouldContinue = { true },
            recover = { ++attempts == 3 },
            enabled = { true },
            enable = { ++enables; true },
            pause = { pauses += it },
        )

        assertTrue(recovered)
        assertEquals(3, attempts)
        assertEquals(1, enables)
        assertEquals(listOf(60_000L, 1_000L, 1_000L), pauses)
    }

    @Test fun kioskRecoveryAWorkerDidNotRunWaitsWithoutUsingAnAttempt() {
        val pauses = mutableListOf<Long>()
        val recoverAnswers = ArrayDeque(listOf<Boolean?>(null, null, false, true))
        val enableAnswers = ArrayDeque(listOf<Boolean?>(null, true))

        val recovered = recoverAndMaybeEnableKiosk(
            escapeDelayMs = 60_000L,
            retryDelayMs = 1_000L,
            maxAttempts = 2,
            shouldContinue = { true },
            recover = { recoverAnswers.removeFirst() },
            enabled = { true },
            enable = { enableAnswers.removeFirst() },
            pause = { pauses += it },
        )

        assertTrue(recovered)
        assertTrue(recoverAnswers.isEmpty())
        assertTrue(enableAnswers.isEmpty())
        assertEquals(listOf(60_000L, 1_000L, 1_000L, 1_000L, 1_000L), pauses)
    }

    @Test fun kioskRecoveryStopsWaitingForAWorkerWhenTheServiceStops() {
        var stopping = false
        var asks = 0

        val recovered = recoverAndMaybeEnableKiosk(
            escapeDelayMs = 0L,
            retryDelayMs = 1_000L,
            maxAttempts = 3,
            shouldContinue = { !stopping },
            recover = { if (++asks == 5) stopping = true; null },
            enabled = { true },
            enable = { error("never reached") },
            pause = {},
        )

        assertFalse(recovered)
        assertEquals(5, asks)
    }

    @Test fun pendingKioskRecoveryDrainsWhileDisabledWithoutEnabling() {
        var attempts = 0
        var enables = 0

        val recovered = recoverAndMaybeEnableKiosk(
            escapeDelayMs = 60_000L,
            retryDelayMs = 1_000L,
            maxAttempts = 3,
            shouldContinue = { true },
            recover = { ++attempts == 2 },
            enabled = { false },
            enable = { ++enables; true },
            pause = {},
        )

        assertTrue(recovered)
        assertEquals(2, attempts)
        assertEquals(0, enables)
    }

    @Test fun ordinaryCleanStopReleasesWhileExplicitOrIncompleteTeardownExits() {
        assertEquals(
            ServiceTeardownDisposition.RELEASE,
            serviceTeardownDisposition(completed = true, explicitProcessBoundary = false),
        )
        assertEquals(
            ServiceTeardownDisposition.EXIT,
            serviceTeardownDisposition(completed = true, explicitProcessBoundary = true),
        )
        assertEquals(
            ServiceTeardownDisposition.EXIT,
            serviceTeardownDisposition(completed = false, explicitProcessBoundary = false),
        )
    }

    @Test fun teardownBoundaryMakesReleaseAndDelayedRestartOneTerminalClaim() {
        val clean = ServiceTeardownBoundary()
        assertTrue(clean.recordCompletionAndClaimRecovery(completed = true))
        assertEquals(ServiceTeardownDisposition.RELEASE, clean.claim())
        assertFalse(clean.requestExplicitBoundary())
        assertFalse(clean.recordCompletionAndClaimRecovery(completed = true))
        assertEquals(null, clean.claim())

        val requested = ServiceTeardownBoundary()
        assertTrue(requested.requestExplicitBoundary())
        assertTrue(requested.recordCompletionAndClaimRecovery(completed = true))
        assertEquals(ServiceTeardownDisposition.EXIT, requested.claim())
        assertFalse(requested.requestExplicitBoundary())

        val incomplete = ServiceTeardownBoundary()
        assertTrue(incomplete.recordCompletionAndClaimRecovery(completed = true))
        assertFalse(incomplete.recordCompletionAndClaimRecovery(completed = false))
        assertEquals(ServiceTeardownDisposition.EXIT, incomplete.claim())
    }

    @Test fun audioAdmissionClosesAndActivePlaybackCancelsBeforeLaterTeardownCanBlock() {
        val events = mutableListOf<String>()

        beginAudioTeardown(
            closeAdmission = { events += "admission-closed" },
            cancelCurrent = { events += "playback-cancelled" },
        )
        events += "runtime-cleanup"

        assertEquals(listOf("admission-closed", "playback-cancelled", "runtime-cleanup"), events)
    }

    @Test fun ownerCleanupFailureIsStickyWhileTheRemainingSweepContinues() {
        val tracker = ServiceOwnerCleanupTracker()
        val events = mutableListOf<String>()

        assertEquals(null, tracker.run { events += "first" })
        assertTrue(tracker.run {
            events += "failed"
            error("owner cleanup failed")
        } is IllegalStateException)
        tracker.record(false)
        assertEquals(null, tracker.run { events += "last" })

        assertEquals(listOf("first", "failed", "last"), events)
        assertFalse(tracker.isComplete())
        assertEquals(
            ServiceTeardownDisposition.EXIT,
            serviceTeardownDisposition(
                completed = tracker.isComplete(),
                explicitProcessBoundary = false,
            ),
        )
    }

    @Test fun existingScreenRecoveryOwnerPreventsASecondHardwareMutator() {
        val pending = CompletableFuture<Boolean>()

        assertFalse(proveScreenSafeForBoundary(pending))

        pending.complete(true)
        assertTrue(proveScreenSafeForBoundary(pending))
    }
}
