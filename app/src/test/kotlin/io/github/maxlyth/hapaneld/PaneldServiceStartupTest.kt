package io.github.maxlyth.hapaneld

import io.github.maxlyth.hapaneld.control.CompanionDataOperationGate
import io.github.maxlyth.hapaneld.control.CompanionDataOperationState
import io.github.maxlyth.hapaneld.migration.SuccessorHandoff
import io.github.maxlyth.hapaneld.util.CompanionOperationStatus
import io.github.maxlyth.hapaneld.util.DurableRecoveryMarker
import io.github.maxlyth.hapaneld.util.LatestOperationPolicy
import io.github.maxlyth.hapaneld.util.RendererPreparationCoordinator
import io.github.maxlyth.hapaneld.util.ServiceRuntimeOwner
import java.util.Collections
import java.nio.file.Files
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PaneldServiceStartupTest {
    @Test fun ordinaryServiceStartsNeverRequestInstalledSuccessorHandoff() {
        for (bridge in listOf(false, true)) {
            for (action in listOf(null, "android.intent.action.MAIN", "io.github.maxlyth.hapaneld.action.PREPARE_UPGRADE")) {
                assertFalse(installedHandoffWakeRequested(bridge, action))
            }
        }
    }

    @Test fun successorServiceNeverHandlesTheBridgeHandoffWake() {
        assertFalse(installedHandoffWakeRequested(false, INSTALLED_SUCCESSOR_HANDOFF_ACTION))
    }

    @Test fun ordinaryHealthyStartupNeverInventsAnInstalledHandoffRequest() {
        val wake = InstalledSuccessorHandoffWake()
        assertFalse(wake.markStartupHealthy())
        assertFalse(wake.markStartupHealthy())
    }

    @Test fun explicitWakeBeforeStartupIsRetainedAndCoalescedUntilHealthy() {
        val wake = InstalledSuccessorHandoffWake()
        assertTrue(installedHandoffWakeRequested(true, INSTALLED_SUCCESSOR_HANDOFF_ACTION))
        assertFalse(wake.request())
        assertFalse(wake.request())
        assertTrue("the requested handoff must survive startup", wake.markStartupHealthy())
        assertFalse("the pending request must be consumed only once", wake.markStartupHealthy())
    }

    @Test fun concurrentExplicitWakeAndStartupReadinessScheduleExactlyOneHandoff() {
        val executor = Executors.newFixedThreadPool(2)
        try {
            repeat(20) {
                val wake = InstalledSuccessorHandoffWake()
                val start = CountDownLatch(1)
                val requested = executor.submit<Boolean> { start.await(); wake.request() }
                val healthy = executor.submit<Boolean> { start.await(); wake.markStartupHealthy() }
                start.countDown()
                assertEquals(1, listOf(requested.get(2, TimeUnit.SECONDS), healthy.get(2, TimeUnit.SECONDS)).count { it })
                assertFalse(wake.markStartupHealthy())
            }
        } finally {
            executor.shutdownNow()
        }
    }

    @Test fun healthyBridgeExplicitWakeRetriesAfterAnEarlierHandoffRefusal() = runBlocking {
        val successor = "io.panelassistant.android"
        val signer = "a".repeat(64)
        var installed: SuccessorHandoff.InstalledSuccessor? = null
        var launches = 0
        val companions = mutableSetOf<String>()
        val wake = InstalledSuccessorHandoffWake()
        val handoff = SuccessorHandoff(object : SuccessorHandoff.Ports {
            override fun retired() = false
            override fun helperRefusal(): String? = null
            override fun installedSuccessor() = installed
            override fun trustedSigner() = signer
            override fun ownVersion() = "1.0"
            override fun compareVersions(left: String, right: String) = left.compareTo(right)
            override fun successorAssetUrl(): String? = error("installed-only wake must not resolve an APK")
            override suspend fun installSuccessor(url: String): String? = error("installed-only wake must not install an APK")
            override fun companionPackages(): Set<String> = companions.toSet()
            override fun addCompanionPackage(pkg: String) { companions += pkg }
            override fun launchSuccessor(): Boolean { launches++; return true }
            override fun deliverReleaseToken() = true
        })

        assertFalse("ordinary startup must not launch an installed successor", wake.markStartupHealthy())
        suspend fun explicitWake(): SuccessorHandoff.Outcome? =
            if (installedHandoffWakeRequested(true, INSTALLED_SUCCESSOR_HANDOFF_ACTION) && wake.request()) {
                handoff.offer(successor, allowInstall = false)
            } else null

        assertEquals(SuccessorHandoff.Outcome.NoSuitableInstalledSuccessor, explicitWake())
        assertEquals(0, launches)
        installed = SuccessorHandoff.InstalledSuccessor("1.0", setOf(signer))
        repeat(2) {
            assertEquals("an explicit wake must remain retryable after the earlier refusal", SuccessorHandoff.Outcome.Launched, explicitWake())
        }
        assertEquals(2, launches)
        assertEquals(setOf(successor), companions)
    }

    @Test fun unrelatedConfigChangesDoNotRefreshAutoSleepOrOtherLiveOwners() {
        listOf(emptySet(), setOf("touch_sound"), setOf("friendly_name"), setOf("ha_expose_touch_sound")).forEach { keys ->
            assertEquals(
                ConfigOwnerRefreshPlan(false, false, false, false, false, false, false, false, false),
                configOwnerRefreshPlan(keys),
            )
        }
    }

    @Test fun configOwnerRefreshesAreExplicitlyKeyed() {
        assertEquals(
            ConfigOwnerRefreshPlan(true, true, false, false, true, true, true, false, true),
            configOwnerRefreshPlan(setOf("ha_url")),
        )
        assertEquals(
            ConfigOwnerRefreshPlan(false, false, true, true, false, false, false, false, false),
            configOwnerRefreshPlan(setOf("log_ship_host", "keep_awake")),
        )
        assertTrue(configOwnerRefreshPlan(setOf("panel_id")).autoSleep)
        assertTrue(configOwnerRefreshPlan(setOf("auto_sleep_source")).autoSleep)
        assertTrue(configOwnerRefreshPlan(setOf("log_ship_system_enabled")).logShipping)
        assertTrue(configOwnerRefreshPlan(setOf("dashboard_package")).rendererTarget)
    }

    @Test fun lifecycleRefreshDefersEnablingButNeverDisabling() {
        // The launch-latency decision is void if any path can open the socket early: EVERY refresh —
        // startup, live configuration save, onboarding first-save — passes this gate.
        assertFalse(haLifecycleRefreshPermitted(rendererSettled = false, wanted = true))
        assertTrue(haLifecycleRefreshPermitted(rendererSettled = true, wanted = true))
        // But a DISABLE must never wait: settlement dies with a released renderer, so a deferred
        // disable after deselecting the built-in renderer would wait forever with the socket open.
        assertTrue(haLifecycleRefreshPermitted(rendererSettled = false, wanted = false))
        assertTrue(haLifecycleRefreshPermitted(rendererSettled = true, wanted = false))
    }

    @Test fun theHomeAssistantLifecycleWatchFollowsTheRendererAndCredentials() {
        // Selecting a renderer or changing credentials must re-evaluate the watch, because either can
        // start or stop the only socket the panel holds.
        assertTrue(configOwnerRefreshPlan(setOf("dashboard_package")).haLifecycle)
        assertTrue(configOwnerRefreshPlan(setOf("ha_refresh_token")).haLifecycle)
        assertFalse(configOwnerRefreshPlan(setOf("keep_awake")).haLifecycle)

        assertTrue(haLifecycleWatchWanted(builtinRendererSelected = true, credentialsPresent = true))
        assertFalse(
            "a foreign renderer has no native surface to show the outage on",
            haLifecycleWatchWanted(builtinRendererSelected = false, credentialsPresent = true),
        )
        assertFalse(
            "without credentials there is nothing to authenticate with",
            haLifecycleWatchWanted(builtinRendererSelected = true, credentialsPresent = false),
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
                broker = "tcp://$name:1883",
                user = "user-$name",
                password = "secret-$name",
            ),
            projection = MqttProjectionIdentity("maker", "model-$name", listOf("wake" to true)),
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

    @Test fun localConfigRefreshOnlyRunsTheMqttEffectsWhoseProjectionChanged() {
        val projection = MqttProjectionIdentity("maker", "model", listOf("wake" to true))
        val ha = HaAuthOwner("http://ha", "refresh", "client", "")
        assertEquals(ConfigRefreshEffects(false, false), configRefreshEffects(projection, projection, ha, ha))
        assertEquals(
            ConfigRefreshEffects(true, false),
            configRefreshEffects(projection, projection.copy(exposures = listOf("wake" to false)), ha, ha),
        )
        assertEquals(
            ConfigRefreshEffects(true, false),
            configRefreshEffects(projection, projection.copy(model = "other"), ha, ha),
        )
        assertEquals(
            ConfigRefreshEffects(false, true),
            configRefreshEffects(projection, projection, ha, HaAuthOwner("http://other", "r", "c", "")),
        )
    }

    @Test fun ordinaryStartupRecoveryIsBoundedAndResetsAfterWindowOrClockRollback() {
        assertEquals(StartupRecoveryDecision(true, 1), startupRecoveryDecision(0, 0, 1_000))
        assertEquals(StartupRecoveryDecision(true, 3), startupRecoveryDecision(2, 900, 1_000))
        assertEquals(StartupRecoveryDecision(false, 4), startupRecoveryDecision(3, 900, 1_000))
        assertEquals(StartupRecoveryDecision(true, 1), startupRecoveryDecision(9, 1_000, 700_001))
        assertEquals(StartupRecoveryDecision(true, 1), startupRecoveryDecision(9, 2_000, 1_000))
    }

    @Test fun networkRuntimeIdentityIgnoresLocalSettingsButIncludesConnectionAndAdvertisementValues() {
        fun identity(
            panel: String = "panel",
            friendly: String = "Panel",
            broker: String = "tcp://ha.local:1883",
            user: String = "user",
            password: String = "secret",
            addressFamily: String = "Automatic",
        ) = NetworkRuntimeIdentity(panel, friendly, 8888, broker, user, password, addressFamily)

        assertEquals(identity(), identity())
        assertFalse(identity() == identity(panel = "other"))
        assertFalse(identity() == identity(friendly = "Other"))
        assertFalse(identity() == identity(broker = "ssl://ha.local:8883"))
        assertFalse(identity() == identity(user = "other"))
        assertFalse(identity() == identity(password = "other"))
        assertFalse(identity() == identity(addressFamily = "Force IPv4"))
        assertEquals("NetworkRuntimeIdentity(redacted)", identity().toString())
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

    @Test fun liveReconfigureResolvesNativeHaLinkWithoutWaitingForMqttConnection() {
        val events = mutableListOf<String>()

        startReconfiguredNetworkRuntime(
            startMdns = { events += "mdns" },
            resolveHaLink = { events += "ha-link" },
            // Models the MQTT-disabled path: start returns without an onConnected callback.
            startMqtt = { events += "mqtt-disabled" },
        )

        assertEquals(listOf("mdns", "ha-link", "mqtt-disabled"), events)
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
            val dir = Files.createTempDirectory("companion-startup-test").toFile()
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
        val dir = Files.createTempDirectory("companion-startup-test").toFile()
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

    @Test fun networkReturnRetriesBlankBrokerDiscoveryButNotTerminalOrAuthStates() {
        assertEquals(
            NetworkAvailableAction.RETRY_DISCOVERY,
            networkAvailableAction("discovering", configuredBroker = ""),
        )
        assertEquals(
            NetworkAvailableAction.RECONNECT,
            networkAvailableAction("reconnecting", configuredBroker = "tcp://ha:1883"),
        )
        assertEquals(NetworkAvailableAction.NONE, networkAvailableAction("connected", ""))
        assertEquals(NetworkAvailableAction.NONE, networkAvailableAction("disabled", ""))
        assertEquals(NetworkAvailableAction.NONE, networkAvailableAction("config-error", "wss://ha:1883"))
        assertEquals(NetworkAvailableAction.NONE, networkAvailableAction("auth-retrying", "tcp://ha:1883"))
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
