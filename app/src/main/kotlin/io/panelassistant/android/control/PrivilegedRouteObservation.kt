package io.panelassistant.android.control

/** One immutable view of the privilege routes available to a read-only management request. */
internal data class PrivilegedRouteObservation(
    val directSuReady: Boolean,
    val helperRootReady: Boolean,
) {
    val rootControlReady: Boolean = directSuReady || helperRootReady

    /** Whether this request has already proved [route] usable. Read-only projections use this to avoid
     * retrying a privileged transport that the authority probe just proved unavailable. */
    fun admits(route: PrivilegeRoute): Boolean = when (route) {
        PrivilegeRoute.SU -> directSuReady
        PrivilegeRoute.DAEMON -> helperRootReady
        PrivilegeRoute.ACCESSIBILITY -> false
    }
}

/** Read every authority exactly once; this function owns no cache, retry, thread, or lifecycle. */
internal fun observePrivilegedRoutes(
    directSuProbe: () -> Boolean,
    helperRootProbe: () -> Boolean,
): PrivilegedRouteObservation = PrivilegedRouteObservation(
    directSuReady = directSuProbe(),
    helperRootReady = helperRootProbe(),
)

/**
 * Minimal privilege projection for callers that only advertise privileged operations. Helper truth is
 * intentionally not exposed here because its probe is skipped once su proves the aggregate capability;
 * callers needing root-route truth must use [observePrivilegedRoutes].
 */
internal data class TypedShellCapabilityObservation(
    val directSuReady: Boolean,
    val typedShellControlReady: Boolean,
)

internal fun observeTypedShellCapability(
    directSuProbe: () -> Boolean,
    helperRootProbe: () -> Boolean,
): TypedShellCapabilityObservation {
    val directSu = directSuProbe()
    return TypedShellCapabilityObservation(
        directSuReady = directSu,
        typedShellControlReady = directSu || helperRootProbe(),
    )
}
