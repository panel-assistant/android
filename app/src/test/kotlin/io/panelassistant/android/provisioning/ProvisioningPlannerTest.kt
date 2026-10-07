package io.panelassistant.android.provisioning

import io.panelassistant.android.device.profile.ProfileActivationPhase
import io.panelassistant.android.device.profile.ProfileOrigin
import io.panelassistant.android.device.profile.ProfileRef
import io.panelassistant.android.metrics.FeatureCostOperation
import io.panelassistant.android.metrics.FeatureCostRegistry
import io.panelassistant.android.util.HelperIdentity
import io.panelassistant.android.util.HelperIdentityIssue
import io.panelassistant.android.util.HelperIdentityStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.json.JSONObject

@OptIn(ExperimentalCoroutinesApi::class)
class ProvisioningPlannerTest {
    @Test
    fun genericProfileProducesSatisfiedEmptyPlan() {
        val plan = plan(profile())

        assertEquals(ProvisioningPlanState.SATISFIED, plan.state)
        assertTrue(plan.items.isEmpty())
    }

    @Test
    fun missingHelperRemainsIndependentFromSatisfiedWebView() {
        val profile = profile(
            helperImportance = ProvisioningImportance.REQUIRED,
            webView = ProvisioningWebViewTarget("lineageos-150-arm64", "150.0.1"),
        )
        val plan = plan(
            profile,
            ProvisioningObservationSnapshot(
                helper = known(ProvisioningHelperState.MISSING),
                webView = known(ProvisioningWebViewState.Active("150.0.2")),
            ),
        )

        assertEquals(ProvisioningPlanState.ATTENTION, plan.state)
        assertEquals(
            listOf("access.helper", "software.webview"),
            plan.items.map { it.id },
        )
        assertItem(
            plan,
            "access.helper",
            ProvisioningItemStatus.MANUAL,
            "missing",
            "daemon_driver_without_helper",
        )
        assertItem(
            plan,
            "software.webview",
            ProvisioningItemStatus.SATISFIED,
            "current",
            "webview_recommendation_satisfied",
        )
    }

    @Test
    fun sandboxPanelWithoutHelperDriverRecommendsHelperForSandboxControls() {
        val plan = plan(
            profile(helperImportance = ProvisioningImportance.RECOMMENDED),
            observations().copy(helper = known(ProvisioningHelperState.MISSING)),
        )

        assertEquals(ProvisioningPlanState.ATTENTION, plan.state)
        assertEquals(ProvisioningImportance.RECOMMENDED, plan.items.single().importance)
        assertItem(
            plan,
            "access.helper",
            ProvisioningItemStatus.MANUAL,
            "missing",
            "sandbox_controls_without_helper",
        )
        val text = ProvisioningTextRenderer.render(plan)
        assertTrue(
            text.contains(
                "- Root helper [recommended]: Display sizing, CPU governor, screenshots and performance readings",
            ),
        )
        assertFalse(text.contains("profiled hardware controls"))
    }

    @Test
    fun compatibleHelperSatisfiesARequiredHelper() {
        val plan = plan(
            profile(
                helperImportance = ProvisioningImportance.REQUIRED,
            ),
            observations().copy(helper = known(ProvisioningHelperState.COMPATIBLE)),
        )

        assertEquals(listOf("access.helper"), plan.items.map { it.id })
        assertItem(
            plan,
            "access.helper",
            ProvisioningItemStatus.SATISFIED,
            "compatible",
            "helper_compatible",
        )
    }

    @Test
    fun partialProbeFailuresAreBlockedWithoutSuppressingKnownItems() {
        val plan = plan(
            profile(
                helperImportance = ProvisioningImportance.REQUIRED,
                webView = ProvisioningWebViewTarget("lineageos-138-armv7", "138.0.7204.63"),
            ),
            ProvisioningObservationSnapshot(
                helper = unknown(ProvisioningUnknownReason.PROBE_FAILED),
                webView = unknown(ProvisioningUnknownReason.PROBE_FAILED),
            ),
        )

        assertItem(plan, "access.helper", ProvisioningItemStatus.BLOCKED, "unknown", "helper_probe_failed")
        assertItem(plan, "software.webview", ProvisioningItemStatus.BLOCKED, "unknown", "webview_probe_failed")
    }

    @Test
    fun helperPingDoesNotClaimCompatibilityAndOlderWebViewIsActionable() {
        val plan = plan(
            profile(
                helperImportance = ProvisioningImportance.REQUIRED,
                webView = ProvisioningWebViewTarget("lineageos-150-arm64", "150.0.7871.63"),
            ),
            observations().copy(
                helper = known(ProvisioningHelperState.REACHABLE_UNVERIFIED),
                webView = known(ProvisioningWebViewState.Active("149.9.9")),
            ),
        )

        assertItem(
            plan,
            "access.helper",
            ProvisioningItemStatus.BLOCKED,
            "reachable_unverified",
            "helper_identity_unavailable",
        )
        assertItem(
            plan,
            "software.webview",
            ProvisioningItemStatus.ACTIONABLE,
            "outdated",
            "webview_outdated",
        )
    }

    @Test
    fun malformedObservedWebViewVersionNeverBecomesSatisfied() {
        val plan = plan(
            profile(webView = ProvisioningWebViewTarget("lineageos-150-arm64", "150.0.1")),
            observations().copy(webView = known(ProvisioningWebViewState.Active("150-beta"))),
        )

        assertItem(
            plan,
            "software.webview",
            ProvisioningItemStatus.BLOCKED,
            "unknown",
            "webview_version_unreadable",
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun plannerRejectsMismatchedActivationRef() {
        ProvisioningPlanner.plan(
            CORE,
            profile(),
            ACTIVE.copy(activeRef = ProfileRef("other", REVISION)),
            observations(),
        )
    }

    @Test
    fun coordinatorRequiresExactStableRefAndCachesSnapshots() = runTest {
        var now = 100L
        var collections = 0
        var nanos = 1_000L
        val costs = FeatureCostRegistry({ nanos.also { nanos += 100L } }, { -1L }, { 1L })
        val coordinator = ProvisioningCoordinator(
            core = CORE,
            profile = profile(helperImportance = ProvisioningImportance.REQUIRED),
            collector = ProvisioningObservationCollector {
                collections++
                observations()
            },
            monotonicMs = { now },
            cacheTtlMs = 50,
            featureCosts = costs,
        )

        assertTrue(coordinator.plan(ACTIVE.copy(phase = ProfileActivationPhase.APPLYING)) is ProvisioningReadResult.Unavailable)
        assertTrue(coordinator.plan(ACTIVE.copy(activeRef = ProfileRef("other", REVISION))) is ProvisioningReadResult.Unavailable)
        assertEquals(0, collections)

        assertTrue(coordinator.plan(ACTIVE) is ProvisioningReadResult.Ready)
        assertTrue(coordinator.plan(ACTIVE) is ProvisioningReadResult.Ready)
        assertEquals(1, collections)

        now += 51
        assertTrue(coordinator.plan(ACTIVE) is ProvisioningReadResult.Ready)
        assertEquals(2, collections)

        assertTrue(coordinator.plan(ACTIVE, forceRefresh = true) is ProvisioningReadResult.Ready)
        assertEquals(3, collections)

        val operations = JSONObject(costs.json()).getJSONArray("operations")
        fun operation(id: FeatureCostOperation) = (0 until operations.length()).asSequence()
            .map(operations::getJSONObject)
            .first { it.getString("id") == id.id }
        assertEquals(6L, operation(FeatureCostOperation.PROVISIONING_PLAN).getLong("calls"))
        assertEquals(3L, operation(FeatureCostOperation.PROVISIONING_OBSERVATION_REFRESH).getLong("calls"))
        assertEquals(9L, operation(FeatureCostOperation.PROVISIONING_OBSERVATION_REFRESH).getLong("work_units"))
    }

    @Test
    fun coordinatorRethrowsCollectorCancellationAndRecordsCancelledSpans() = runTest {
        var nanos = 1_000L
        val costs = FeatureCostRegistry({ nanos.also { nanos += 100L } }, { -1L }, { 1L })
        val coordinator = ProvisioningCoordinator(
            core = CORE,
            profile = profile(),
            collector = ProvisioningObservationCollector {
                throw CancellationException("probe cancelled")
            },
            monotonicMs = { 100L },
            featureCosts = costs,
        )

        val failure = runCatching { coordinator.plan(ACTIVE) }.exceptionOrNull()

        assertTrue(failure is CancellationException)
        assertOutcome(costs, FeatureCostOperation.PROVISIONING_PLAN, cancelled = 1L)
        assertOutcome(costs, FeatureCostOperation.PROVISIONING_OBSERVATION_REFRESH, cancelled = 1L)
    }

    @Test
    fun cancellationWhileWaitingForRefreshMutexIsNotReportedAsFailure() = runTest {
        var nanos = 1_000L
        val costs = FeatureCostRegistry({ nanos.also { nanos += 100L } }, { -1L }, { 1L })
        val collectorStarted = CompletableDeferred<Unit>()
        val releaseCollector = CompletableDeferred<Unit>()
        val coordinator = ProvisioningCoordinator(
            core = CORE,
            profile = profile(),
            collector = ProvisioningObservationCollector {
                collectorStarted.complete(Unit)
                releaseCollector.await()
                observations()
            },
            monotonicMs = { 100L },
            featureCosts = costs,
        )
        val first = async { coordinator.plan(ACTIVE, forceRefresh = true) }
        collectorStarted.await()
        val waiting = async { coordinator.plan(ACTIVE, forceRefresh = true) }
        runCurrent()

        waiting.cancel(CancellationException("request cancelled"))
        runCurrent()

        assertTrue(waiting.isCancelled)
        assertOutcome(costs, FeatureCostOperation.PROVISIONING_PLAN, cancelled = 1L)
        assertOutcome(costs, FeatureCostOperation.PROVISIONING_OBSERVATION_REFRESH, cancelled = 0L)

        releaseCollector.complete(Unit)
        assertTrue(first.await() is ProvisioningReadResult.Ready)
    }

    @Test
    fun ordinaryCollectorFailureRemainsUnavailableAndRecordsFailure() = runTest {
        var nanos = 1_000L
        val costs = FeatureCostRegistry({ nanos.also { nanos += 100L } }, { -1L }, { 1L })
        val coordinator = ProvisioningCoordinator(
            core = CORE,
            profile = profile(),
            collector = ProvisioningObservationCollector {
                throw IllegalStateException("probe unavailable")
            },
            monotonicMs = { 100L },
            featureCosts = costs,
        )

        assertEquals(
            ProvisioningReadResult.Unavailable("observation_snapshot_unavailable"),
            coordinator.plan(ACTIVE),
        )
        assertOutcome(costs, FeatureCostOperation.PROVISIONING_PLAN, failed = 1L)
        assertOutcome(costs, FeatureCostOperation.PROVISIONING_OBSERVATION_REFRESH, failed = 1L)
    }

    @Test
    fun rendererStripsTerminalControlsAndUsesOnlyStaticGuidance() {
        val hostileName = "\u001B[31mTPA10\u001B[0m\nspoof\u202Etxt\u2066"
        val plan = plan(
            profile(
                displayName = hostileName,
                helperImportance = ProvisioningImportance.REQUIRED,
            ),
            observations().copy(helper = known(ProvisioningHelperState.MISSING)),
        )

        val text = ProvisioningTextRenderer.render(plan)

        assertTrue(text.startsWith("ha-paneld provisioning plan\nPanel: TPA10spoof"))
        assertFalse(text.contains('\u001B'))
        assertFalse(text.contains('\u202E'))
        assertFalse(text.contains('\u2066'))
        assertFalse(text.contains("\nspoof"))
        assertTrue(text.endsWith('\n'))
        assertTrue(text.contains("trusted host"))
    }

    @Test
    fun androidCollectorContainsProbeFailuresAndPreservesLegacyHelperUncertainty() = runTest {
        val collector = AndroidProvisioningObservationCollector(
            helperIdentity = { HelperIdentityStatus.ReachableUnverified },
            webViewEngineMajor = { throw IllegalStateException("provider unavailable") },
        )

        val snapshot = collector.collect()

        assertEquals(
            ProvisioningObservation.Known(ProvisioningHelperState.REACHABLE_UNVERIFIED),
            snapshot.helper,
        )
        assertEquals(
            ProvisioningObservation.Unknown(ProvisioningUnknownReason.PROBE_FAILED),
            snapshot.webView,
        )
    }

    @Test
    fun androidCollectorMapsVersionedHelperCompatibility() = runTest {
        suspend fun helper(status: HelperIdentityStatus) =
            AndroidProvisioningObservationCollector(
                helperIdentity = { status },
                webViewEngineMajor = { null },
            ).collect().helper

        val identity = HelperIdentity("1.0.0", 1, 0)
        assertEquals(
            ProvisioningObservation.Known(ProvisioningHelperState.COMPATIBLE),
            helper(HelperIdentityStatus.Compatible(identity)),
        )
        assertEquals(
            ProvisioningObservation.Known(ProvisioningHelperState.INCOMPATIBLE),
            helper(
                HelperIdentityStatus.Incompatible(
                    identity,
                    HelperIdentityIssue.UNSUPPORTED_PROTOCOL,
                ),
            ),
        )
    }

    private fun plan(
        profile: ProvisioningProfile,
        observations: ProvisioningObservationSnapshot = observations(),
    ): ProvisioningPlan = ProvisioningPlanner.plan(CORE, profile, ACTIVE, observations)

    private fun assertItem(
        plan: ProvisioningPlan,
        id: String,
        status: ProvisioningItemStatus,
        observed: String,
        reason: String,
    ) {
        val item = plan.items.single { it.id == id }
        assertEquals(status, item.status)
        assertEquals(observed, item.observedState)
        assertEquals(reason, item.reasonCode)
    }

    private fun assertOutcome(
        costs: FeatureCostRegistry,
        operation: FeatureCostOperation,
        failed: Long = 0L,
        cancelled: Long = 0L,
    ) {
        val operations = JSONObject(costs.json()).getJSONArray("operations")
        val record = (0 until operations.length()).asSequence()
            .map(operations::getJSONObject)
            .first { it.getString("id") == operation.id }
        assertEquals(failed, record.getLong("failed"))
        assertEquals(cancelled, record.getLong("cancelled"))
    }

    private companion object {
        const val REVISION = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        val REF = ProfileRef("test.panel", REVISION)
        val CORE = ProvisioningCoreIdentity("0.9.4-rc1", 194)
        val ACTIVE = ProvisioningActivationSnapshot(ProfileActivationPhase.ACTIVE, REF, 4)

        fun profile(
            displayName: String = "Test panel",
            directRootExpected: Boolean = false,
            helperImportance: ProvisioningImportance? = null,
            webView: ProvisioningWebViewTarget? = null,
        ) = ProvisioningProfile(
            ref = REF,
            displayName = displayName,
            origin = ProfileOrigin.BUNDLED,
            contentVersion = "2.0.0",
            directRootExpected = directRootExpected,
            helperImportance = helperImportance,
            webView = webView,
        )

        fun observations() = ProvisioningObservationSnapshot(
            helper = known(ProvisioningHelperState.MISSING),
            webView = known(ProvisioningWebViewState.Missing),
        )

        fun <T> known(value: T): ProvisioningObservation<T> = ProvisioningObservation.Known(value)
        fun unknown(reason: ProvisioningUnknownReason): ProvisioningObservation<Nothing> =
            ProvisioningObservation.Unknown(reason)
    }
}
