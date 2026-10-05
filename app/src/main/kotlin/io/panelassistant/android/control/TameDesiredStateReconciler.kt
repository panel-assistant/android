package io.panelassistant.android.control

internal enum class TamePackagePresence { PRESENT, ABSENT, UNKNOWN }
internal enum class TamePackageSafety { SAFE, PROTECTED, UNKNOWN }
internal data class TamePackageObservation(
    val presence: TamePackagePresence,
    val safety: TamePackageSafety,
)

/** A legacy-compatible write-ahead ownership record. Parsed snapshots admit only exact known modes. */
internal data class TameOwnedMarker(val pkg: String, val overlayMode: String?)

internal sealed interface TameOwnedMarkers {
    data class Ready(val byPackage: Map<String, TameOwnedMarker>) : TameOwnedMarkers
    data class Overflow(val count: Int) : TameOwnedMarkers
    data class Invalid(val prefixedCount: Int) : TameOwnedMarkers
    data object Unavailable : TameOwnedMarkers
}

/** Result of one exact desired-state pass. */
internal data class TameReconcileResult(
    val attempted: Int,
    val retryableFailure: Boolean,
)

/**
 * Stateless reconciliation over the existing overlay restoration markers. It owns no thread, timer or
 * second journal: the marker is both the pre-mutation write-ahead record and the rollback ownership fact.
 */
internal class TameDesiredStateReconciler(
    private val readOwned: () -> TameOwnedMarkers,
    private val observePackages: (Set<String>) -> Map<String, TamePackageObservation>,
    private val reassert: (String) -> Boolean,
    private val restore: (TameOwnedMarker) -> Boolean,
    private val clearAbsent: (TameOwnedMarker) -> Boolean,
) {
    fun reconcile(desired: Set<String>, stopping: () -> Boolean = { false }): TameReconcileResult {
        if (stopping()) return TameReconcileResult(0, retryableFailure = false)
        val owned = when (val snapshot = readOwned()) {
            is TameOwnedMarkers.Ready -> snapshot.byPackage
            is TameOwnedMarkers.Overflow,
            is TameOwnedMarkers.Invalid,
            TameOwnedMarkers.Unavailable -> return TameReconcileResult(0, retryableFailure = true)
        }
        val relevant = desired + owned.keys
        val observed = runCatching { observePackages(relevant) }.getOrDefault(emptyMap())
        var attempted = 0
        var retryableFailure = false

        // Ownership, not current device role, decides rollback. A package that became HOME/IME/persistent
        // after we tamed it must still be restored when the user removes it from the desired selection.
        for (marker in owned.values.filter { it.pkg !in desired }.sortedBy(TameOwnedMarker::pkg)) {
            // Complete any transaction already entered, retaining its marker on failure. The next
            // owner re-reads durable desired state and these same markers to finish the remaining work.
            if (stopping()) return TameReconcileResult(attempted, retryableFailure)
            when (observed[marker.pkg]?.presence ?: TamePackagePresence.UNKNOWN) {
                TamePackagePresence.PRESENT -> {
                    attempted++
                    if (!restore(marker)) retryableFailure = true
                }
                TamePackagePresence.ABSENT -> {
                    attempted++
                    if (!clearAbsent(marker)) retryableFailure = true
                }
                TamePackagePresence.UNKNOWN -> retryableFailure = true
            }
        }

        // Reassert every desired PRESENT+SAFE package on every bounded wake, even when already owned.
        // This heals a reinstall, firmware reset or vendor process/package-state resurrection.
        for (pkg in desired.sorted()) {
            if (stopping()) return TameReconcileResult(attempted, retryableFailure)
            val marker = owned[pkg]
            val observation = observed[pkg]
            when (observation?.presence ?: TamePackagePresence.UNKNOWN) {
                TamePackagePresence.ABSENT -> if (marker != null) {
                    attempted++
                    if (!clearAbsent(marker)) retryableFailure = true
                }
                TamePackagePresence.UNKNOWN -> retryableFailure = true
                TamePackagePresence.PRESENT -> when (observation?.safety ?: TamePackageSafety.UNKNOWN) {
                    TamePackageSafety.SAFE -> {
                        attempted++
                        if (!reassert(pkg)) retryableFailure = true
                    }
                    TamePackageSafety.UNKNOWN -> retryableFailure = true
                    TamePackageSafety.PROTECTED -> Unit
                }
            }
        }
        return TameReconcileResult(attempted, retryableFailure)
    }
}
