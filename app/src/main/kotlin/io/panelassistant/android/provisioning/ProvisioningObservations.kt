package io.panelassistant.android.provisioning

/** A failed probe is different from a negative observation and must stay visible to the planner. */
internal sealed interface ProvisioningObservation<out T> {
    data class Known<T>(val value: T) : ProvisioningObservation<T>
    data class Unknown(val reason: ProvisioningUnknownReason) : ProvisioningObservation<Nothing>
}

internal enum class ProvisioningUnknownReason {
    NOT_READY,
    PROBE_FAILED,
    UNSUPPORTED,
    IDENTITY_UNAVAILABLE,
}

/**
 * COMPATIBLE requires a future helper identity/protocol check. A successful legacy PING alone maps
 * to REACHABLE_UNVERIFIED and must not be promoted to a compatibility claim.
 */
internal enum class ProvisioningHelperState {
    COMPATIBLE,
    MISSING,
    INCOMPATIBLE,
    REACHABLE_UNVERIFIED,
}

internal sealed interface ProvisioningWebViewState {
    data class Active(val version: String) : ProvisioningWebViewState
    data object Missing : ProvisioningWebViewState
}

internal data class ProvisioningObservationSnapshot(
    val helper: ProvisioningObservation<ProvisioningHelperState>,
    val webView: ProvisioningObservation<ProvisioningWebViewState>,
)

/**
 * Collectors are local, read-only probes. Implementations must isolate probe failures by returning
 * [ProvisioningObservation.Unknown] for the affected domain rather than discarding the whole snapshot.
 */
internal fun interface ProvisioningObservationCollector {
    suspend fun collect(): ProvisioningObservationSnapshot
}
