package io.panelassistant.android.control

/**
 * One owner for the app-side Companion data lease. Claiming the process-local CAS launch gate, arming
 * the durable app-private marker, and settling both back down happen through this single type so the
 * arm/clear/settle ordering and its fail-closed semantics live in one place instead of being repeated at
 * every Companion backup/restore call site.
 *
 * This is a correctness lease, not the root security boundary: the helper still uses descriptor-relative
 * no-follow I/O because an external launcher cannot be prevented by an app-process flag.
 */
internal class CompanionDataLease private constructor(
    private val lease: CompanionDataOperationGate.Lease,
    private val operationState: CompanionDataOperationState,
    private val retain: (CompanionDataOperationGate.Lease, () -> Unit) -> Unit,
) {
    private var settled = false

    /**
     * Resolve this lease at most once. A [possiblyInFlight] outcome, or a marker clear that cannot be made
     * durable, transfers ownership to asynchronous retention so launch suppression survives an unreachable
     * helper; every affirmative terminal outcome clears the marker and closes the gate synchronously.
     *
     * Repeat calls are no-ops, so a `finally` settle cannot double-release a lease that an earlier branch
     * already transferred to retention.
     */
    fun settle(possiblyInFlight: Boolean, afterRelease: () -> Unit) {
        if (settled) return
        settled = true
        if (possiblyInFlight || !operationState.clear()) {
            retain(lease, afterRelease)
        } else {
            lease.close()
            afterRelease()
        }
    }

    sealed class Acquisition {
        class Acquired(val lease: CompanionDataLease) : Acquisition()

        /** Another Companion data operation already holds the single process-local gate. */
        object GateBusy : Acquisition()

        /** The durable marker could not be persisted, so the operation must not start. */
        object MarkerFailed : Acquisition()
    }

    companion object {
        /**
         * Claim the single process-local gate and arm the durable marker before any Companion data
         * transaction opens. Failing to persist the marker rolls the gate back so the operation never
         * starts with launch suppression it could not recover after process death.
         */
        fun acquireArmed(
            packageName: String,
            operationState: CompanionDataOperationState,
            retain: (CompanionDataOperationGate.Lease, () -> Unit) -> Unit,
        ): Acquisition {
            val lease = CompanionDataOperationGate.acquire(packageName) ?: return Acquisition.GateBusy
            if (!operationState.arm()) {
                lease.close()
                return Acquisition.MarkerFailed
            }
            return Acquisition.Acquired(CompanionDataLease(lease, operationState, retain))
        }

        internal fun forTest(
            lease: CompanionDataOperationGate.Lease,
            operationState: CompanionDataOperationState,
            retain: (CompanionDataOperationGate.Lease, () -> Unit) -> Unit,
        ): CompanionDataLease = CompanionDataLease(lease, operationState, retain)
    }
}
