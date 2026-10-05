package io.panelassistant.android.provisioning

import io.panelassistant.android.device.profile.ShizukuRecommendation

/** Pure, Android-free translation from validated intent plus typed observations to a read-only plan. */
internal object ProvisioningPlanner {
    fun plan(
        core: ProvisioningCoreIdentity,
        profile: ProvisioningProfile,
        activation: ProvisioningActivationSnapshot,
        observations: ProvisioningObservationSnapshot,
    ): ProvisioningPlan {
        require(activation.isStableFor(profile.ref)) { "activation must match the planner profile" }

        val items = buildList {
            profile.helperImportance?.let { add(helperItem(it, observations.helper)) }
            // Profile-declared appCanSu only orders runtime attempts; it does not prove that this
            // installation currently has root. A compatible helper is observed privileged readiness.
            val preferredPrivilegeReady =
                (observations.helper as? ProvisioningObservation.Known)?.value ==
                ProvisioningHelperState.COMPATIBLE
            if (!preferredPrivilegeReady && profile.shizuku != ShizukuRecommendation.NONE) {
                add(shizukuItem(profile.shizuku, observations.shizuku))
            }
            profile.webView?.let { add(webViewItem(it, observations.webView)) }
        }
        return ProvisioningPlan(
            core = core,
            profile = profile,
            activation = activation,
            state = if (items.all { it.status in SATISFIED_STATUSES }) {
                ProvisioningPlanState.SATISFIED
            } else {
                ProvisioningPlanState.ATTENTION
            },
            items = items,
        )
    }

    private fun helperItem(
        importance: ProvisioningImportance,
        observation: ProvisioningObservation<ProvisioningHelperState>,
    ): ProvisioningPlanItem {
        val result = when (observation) {
            is ProvisioningObservation.Known -> when (observation.value) {
                ProvisioningHelperState.COMPATIBLE ->
                    Result(ProvisioningItemStatus.SATISFIED, "compatible", "helper_compatible")
                ProvisioningHelperState.MISSING -> Result(
                    ProvisioningItemStatus.MANUAL,
                    "missing",
                    if (importance == ProvisioningImportance.REQUIRED) {
                        "daemon_driver_without_helper"
                    } else {
                        "sandbox_controls_without_helper"
                    },
                )
                ProvisioningHelperState.INCOMPATIBLE ->
                    Result(ProvisioningItemStatus.DEGRADED, "incompatible", "helper_incompatible")
                ProvisioningHelperState.REACHABLE_UNVERIFIED ->
                    Result(ProvisioningItemStatus.BLOCKED, "reachable_unverified", "helper_identity_unavailable")
            }
            is ProvisioningObservation.Unknown ->
                Result(ProvisioningItemStatus.BLOCKED, "unknown", observation.reason.helperReason())
        }
        return ProvisioningPlanItem(
            id = "access.helper",
            importance = importance,
            status = result.status,
            executor = ProvisioningExecutor.HOST,
            desiredState = "compatible",
            observedState = result.observed,
            reasonCode = result.reason,
        )
    }

    private fun shizukuItem(
        recommendation: ShizukuRecommendation,
        observation: ProvisioningObservation<ProvisioningShizukuState>,
    ): ProvisioningPlanItem {
        val importance = when (recommendation) {
            ShizukuRecommendation.RECOMMENDED -> ProvisioningImportance.RECOMMENDED
            ShizukuRecommendation.OPTIONAL -> ProvisioningImportance.OPTIONAL
            ShizukuRecommendation.NONE -> error("NONE is filtered before planning")
        }
        val result = when (observation) {
            is ProvisioningObservation.Known -> when (observation.value) {
                ProvisioningShizukuState.READY ->
                    Result(ProvisioningItemStatus.SATISFIED, "ready", "shizuku_ready")
                ProvisioningShizukuState.CONSENT_DISABLED ->
                    Result(ProvisioningItemStatus.MANUAL, "disabled", "shizuku_consent_disabled")
                ProvisioningShizukuState.PERMISSION_REQUIRED ->
                    Result(ProvisioningItemStatus.MANUAL, "permission_required", "shizuku_permission_required")
                ProvisioningShizukuState.SERVICE_NOT_RUNNING ->
                    Result(ProvisioningItemStatus.MANUAL, "service_not_running", "shizuku_service_not_running")
                ProvisioningShizukuState.MANAGER_MISSING ->
                    Result(
                        ProvisioningItemStatus.MANUAL,
                        "manager_missing",
                        if (importance == ProvisioningImportance.RECOMMENDED) {
                            "profile_recommended"
                        } else {
                            "profile_optional"
                        },
                    )
            }
            is ProvisioningObservation.Unknown ->
                Result(ProvisioningItemStatus.BLOCKED, "unknown", observation.reason.shizukuReason())
        }
        return ProvisioningPlanItem(
            id = "access.shizuku",
            importance = importance,
            status = result.status,
            executor = ProvisioningExecutor.LOCAL_USER,
            desiredState = "ready",
            observedState = result.observed,
            reasonCode = result.reason,
        )
    }

    private fun webViewItem(
        target: ProvisioningWebViewTarget,
        observation: ProvisioningObservation<ProvisioningWebViewState>,
    ): ProvisioningPlanItem {
        val result = when (observation) {
            is ProvisioningObservation.Known -> when (val value = observation.value) {
                is ProvisioningWebViewState.Active -> {
                    val comparison = compareChromiumMajor(value.version, target.version)
                    when {
                        comparison == null ->
                            Result(ProvisioningItemStatus.BLOCKED, "unknown", "webview_version_unreadable")
                        comparison >= 0 ->
                            Result(ProvisioningItemStatus.SATISFIED, "current", "webview_recommendation_satisfied")
                        else ->
                            Result(ProvisioningItemStatus.ACTIONABLE, "outdated", "webview_outdated")
                    }
                }
                ProvisioningWebViewState.Missing ->
                    Result(ProvisioningItemStatus.ACTIONABLE, "missing", "webview_missing")
            }
            is ProvisioningObservation.Unknown ->
                Result(ProvisioningItemStatus.BLOCKED, "unknown", observation.reason.webViewReason())
        }
        return ProvisioningPlanItem(
            id = "software.webview",
            importance = ProvisioningImportance.RECOMMENDED,
            status = result.status,
            executor = ProvisioningExecutor.APP,
            desiredState = "recommended",
            observedState = result.observed,
            reasonCode = result.reason,
        )
    }

    /**
     * PanelInfo can reliably observe the real Chromium major even when a replacement provider stamps
     * an OEM package version. Full engine versions are not yet a trustworthy observation, so stage one
     * deliberately compares only that major rather than manufacturing false patch-level precision.
     */
    private fun compareChromiumMajor(left: String, right: String): Int? {
        fun parse(value: String): Int? {
            if (value.isBlank() || value.length > 96) return null
            val major = value.substringBefore('.')
            if (major.isEmpty() || major.length > 4 || major.any { !it.isDigit() }) return null
            return major.toIntOrNull()
        }
        return (parse(left) ?: return null).compareTo(parse(right) ?: return null)
    }

    private fun ProvisioningUnknownReason.helperReason(): String = when (this) {
        ProvisioningUnknownReason.IDENTITY_UNAVAILABLE -> "helper_identity_unavailable"
        ProvisioningUnknownReason.NOT_READY -> "helper_observation_not_ready"
        ProvisioningUnknownReason.PROBE_FAILED -> "helper_probe_failed"
        ProvisioningUnknownReason.UNSUPPORTED -> "helper_probe_unsupported"
    }

    private fun ProvisioningUnknownReason.shizukuReason(): String = when (this) {
        ProvisioningUnknownReason.NOT_READY -> "shizuku_observation_not_ready"
        ProvisioningUnknownReason.PROBE_FAILED -> "shizuku_probe_failed"
        ProvisioningUnknownReason.UNSUPPORTED -> "shizuku_probe_unsupported"
        ProvisioningUnknownReason.IDENTITY_UNAVAILABLE -> "shizuku_identity_unavailable"
    }

    private fun ProvisioningUnknownReason.webViewReason(): String = when (this) {
        ProvisioningUnknownReason.NOT_READY -> "webview_observation_not_ready"
        ProvisioningUnknownReason.PROBE_FAILED -> "webview_probe_failed"
        ProvisioningUnknownReason.UNSUPPORTED -> "webview_probe_unsupported"
        ProvisioningUnknownReason.IDENTITY_UNAVAILABLE -> "webview_identity_unavailable"
    }

    private data class Result(
        val status: ProvisioningItemStatus,
        val observed: String,
        val reason: String,
    )

    private val SATISFIED_STATUSES = setOf(
        ProvisioningItemStatus.SATISFIED,
        ProvisioningItemStatus.NOT_APPLICABLE,
    )
}
