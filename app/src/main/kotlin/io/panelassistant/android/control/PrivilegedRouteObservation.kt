package io.panelassistant.android.control

import io.panelassistant.android.shizuku.ShizukuBridge

/**
 * One immutable view of the privilege routes available to a read-only management request.
 *
 * Root-capable routes and the locally approved shell-UID Shizuku route remain deliberately
 * distinct: Shizuku can perform a small set of typed operations, but it is not proof that arbitrary
 * root control is available.
 */
internal data class PrivilegedRouteObservation(
    val directSuReady: Boolean,
    val helperRootReady: Boolean,
    val shizuku: ShizukuBridge.Snapshot,
) {
    val rootControlReady: Boolean = directSuReady || helperRootReady
    val typedShellControlReady: Boolean = rootControlReady || shizuku.ready

    /** Whether this request has already proved [route] usable. Read-only projections use this to avoid
     * retrying a privileged transport that the authority probe just proved unavailable. */
    fun admits(route: PrivilegeRoute): Boolean = when (route) {
        PrivilegeRoute.SU -> directSuReady
        PrivilegeRoute.DAEMON -> helperRootReady
        PrivilegeRoute.SHIZUKU -> shizuku.ready
        PrivilegeRoute.ACCESSIBILITY -> false
    }
}

/** Read every authority exactly once; this function owns no cache, retry, thread, or lifecycle. */
internal fun observePrivilegedRoutes(
    directSuProbe: () -> Boolean,
    helperRootProbe: () -> Boolean,
    shizukuSnapshot: () -> ShizukuBridge.Snapshot,
): PrivilegedRouteObservation = PrivilegedRouteObservation(
    directSuReady = directSuProbe(),
    helperRootReady = helperRootProbe(),
    shizuku = shizukuSnapshot(),
)

/**
 * Minimal privilege projection for callers that only advertise typed shell operations. Helper truth
 * is intentionally not exposed here because its probe may be skipped once another typed route proves
 * the aggregate capability; callers needing root-route truth must use [observePrivilegedRoutes].
 */
internal data class TypedShellCapabilityObservation(
    val directSuReady: Boolean,
    val shizuku: ShizukuBridge.Snapshot,
    val typedShellControlReady: Boolean,
)

internal fun observeTypedShellCapability(
    directSuProbe: () -> Boolean,
    helperRootProbe: () -> Boolean,
    shizukuSnapshot: () -> ShizukuBridge.Snapshot,
): TypedShellCapabilityObservation {
    val shizuku = shizukuSnapshot()
    val directSu = directSuProbe()
    return TypedShellCapabilityObservation(
        directSuReady = directSu,
        shizuku = shizuku,
        typedShellControlReady = directSu || shizuku.ready || helperRootProbe(),
    )
}
