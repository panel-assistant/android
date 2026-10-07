package io.panelassistant.android.provisioning

import io.panelassistant.android.device.profile.ProfileActivationPhase
import io.panelassistant.android.device.profile.ProfileOrigin
import io.panelassistant.android.device.profile.ProfileRef

internal const val PROVISIONING_PLAN_SCHEMA = 1

internal enum class ProvisioningImportance {
    REQUIRED,
    RECOMMENDED,
    OPTIONAL,
}

internal enum class ProvisioningItemStatus {
    SATISFIED,
    ACTIONABLE,
    MANUAL,
    BLOCKED,
    DEGRADED,
    NOT_APPLICABLE,
}

internal enum class ProvisioningExecutor {
    APP,
    HOST,
    NONE,
}

internal enum class ProvisioningPlanState {
    SATISFIED,
    ATTENTION,
}

/**
 * Normalized, non-executable profile intent consumed by the planner. The service adapts the active
 * [io.panelassistant.android.device.DeviceProfile] to this view; the planner never parses YAML and
 * never receives commands, paths, URLs, or profile-authored prose.
 */
internal data class ProvisioningProfile(
    val ref: ProfileRef,
    val displayName: String,
    val origin: ProfileOrigin,
    val contentVersion: String,
    val directRootExpected: Boolean = false,
    val helperImportance: ProvisioningImportance? = null,
    val webView: ProvisioningWebViewTarget? = null,
)

/** Release-owned WebView identity resolved from a profile's allowlisted artifact id. */
internal data class ProvisioningWebViewTarget(
    val artifactId: String,
    val version: String,
)

internal data class ProvisioningCoreIdentity(
    val version: String,
    val versionCode: Int,
)

/**
 * One registry snapshot, captured once per request. A plan is available only when the registry has
 * completed activation and its immutable active ref is the ref used to construct the coordinator.
 */
internal data class ProvisioningActivationSnapshot(
    val phase: ProfileActivationPhase,
    val activeRef: ProfileRef?,
    val generation: Long,
) {
    fun isStableFor(expected: ProfileRef): Boolean =
        phase in setOf(ProfileActivationPhase.ACTIVE, ProfileActivationPhase.ROLLED_BACK) &&
            activeRef == expected
}

internal data class ProvisioningPlan(
    val core: ProvisioningCoreIdentity,
    val profile: ProvisioningProfile,
    val activation: ProvisioningActivationSnapshot,
    val state: ProvisioningPlanState,
    val items: List<ProvisioningPlanItem>,
    val issues: List<ProvisioningPlanIssue> = emptyList(),
)

internal data class ProvisioningPlanItem(
    val id: String,
    val importance: ProvisioningImportance,
    val status: ProvisioningItemStatus,
    val executor: ProvisioningExecutor,
    val desiredState: String,
    val observedState: String,
    val reasonCode: String,
)

internal data class ProvisioningPlanIssue(
    val code: String,
)

internal sealed interface ProvisioningReadResult {
    data class Ready(val plan: ProvisioningPlan) : ProvisioningReadResult
    data class Unavailable(val reasonCode: String) : ProvisioningReadResult
}

internal interface ProvisioningReader {
    val expectedProfileRef: ProfileRef

    suspend fun plan(
        activation: ProvisioningActivationSnapshot,
        forceRefresh: Boolean = false,
    ): ProvisioningReadResult
}
