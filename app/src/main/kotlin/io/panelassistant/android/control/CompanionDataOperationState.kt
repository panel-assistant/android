package io.panelassistant.android.control

import android.content.Context
import io.panelassistant.android.util.DurableRecoveryMarker

/**
 * App-private durable evidence that ha-paneld may have submitted a Companion data transaction.
 *
 * The helper owns the authoritative transaction journal. This marker only preserves the app-side
 * launch-suppression obligation across process death: an unavailable helper is unsafe when this is
 * armed, but ordinary helper-less panels remain launchable when it is absent.
 */
internal class CompanionDataOperationState private constructor(
    private val marker: DurableRecoveryMarker,
) {
    fun isPending(): Boolean = marker.isArmed()

    /** Arm before opening the helper transaction socket. Failure means the operation must not start. */
    fun arm(): Boolean = marker.arm()

    /**
     * Clear only after a terminal helper result, an affirmative never-submitted result, or an
     * IDLE status proves that no helper worker or recoverable journal can still mutate Companion data.
     */
    fun clear(): Boolean = marker.clear()

    companion object {
        private const val MARKER_FILE = "companion-data-operation.pending"

        fun from(context: Context): CompanionDataOperationState =
            CompanionDataOperationState(
                DurableRecoveryMarker(context.noBackupFilesDir.resolve(MARKER_FILE)),
            )

        internal fun forTest(marker: DurableRecoveryMarker): CompanionDataOperationState =
            CompanionDataOperationState(marker)
    }
}
