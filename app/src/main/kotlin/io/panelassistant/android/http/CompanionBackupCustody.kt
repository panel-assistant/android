package io.panelassistant.android.http

import io.panelassistant.android.control.CompanionDataOperationGate
import io.panelassistant.android.control.CompanionDataOperationState
import io.panelassistant.android.util.CompanionOperationStatus
import kotlinx.coroutines.delay

/**
 * Keep app-side launch suppression until the helper affirmatively reports that no Companion-data
 * worker can still be mutating files. An unreachable status socket is not evidence that a worker
 * from an earlier, timed-out connection has stopped; the daemon may merely be temporarily unable to
 * accept or answer the probe. The global Companion gate admits only one lease, so retaining it and
 * one low-frequency polling coroutine is resource-bounded even across a prolonged outage.
 *
 * A legacy `UNSUPPORTED` reply is not affirmative while the app marker is armed: a newer helper may
 * have published a durable journal and then died before an older init-managed helper restarted.
 */
internal suspend fun retainCompanionLeaseUntilHelperIdle(
    lease: CompanionDataOperationGate.Lease,
    operationState: CompanionDataOperationState,
    afterRelease: () -> Unit,
    operationStatus: () -> CompanionOperationStatus,
    pollMs: Long,
) {
    try {
        while (true) {
            val status = runCatching(operationStatus).getOrDefault(CompanionOperationStatus.UNAVAILABLE)
            when (status) {
                CompanionOperationStatus.IDLE -> if (operationState.clear()) break
                CompanionOperationStatus.BUSY,
                CompanionOperationStatus.UNSUPPORTED,
                CompanionOperationStatus.UNAVAILABLE -> delay(pollMs.coerceAtLeast(1L))
            }
            if (status == CompanionOperationStatus.IDLE) delay(pollMs.coerceAtLeast(1L))
        }
    } finally {
        lease.close()
    }
    afterRelease()
}

/**
 * Turn what the caller said about the Companion into what this panel will actually put in the archive.
 *
 * [CompanionBackupRequest.REQUIRED] and [CompanionBackupRequest.EXCLUDED] are answered exactly and never
 * consult [companionInstalled], so no probe result can quietly downgrade a request that named the login:
 * an explicit `true` on a Companion-free panel still reaches the capture path and still refuses there,
 * with the reason the operator needs. Only an omitted request asks whether there is anything to include,
 * and it asks about installation alone. A panel that *has* the Companion but cannot capture it — a stale
 * helper, a busy helper, a database that will not checkpoint — is not a panel with nothing to include, so
 * omission keeps failing loudly there rather than handing back an archive that silently lacks the login.
 */
internal fun resolveCompanionInclusion(
    request: CompanionBackupRequest,
    companionInstalled: () -> Boolean,
): Boolean = when (request) {
    CompanionBackupRequest.REQUIRED -> true
    CompanionBackupRequest.EXCLUDED -> false
    CompanionBackupRequest.OMITTED -> companionInstalled()
}

/** The Companion-dependent parts of the backup card. Empty throughout when the app is absent. */
internal data class BackupCompanionCopy(
    val showLoginChoice: Boolean,
    val explainHelperRequirement: Boolean,
)

/**
 * Decides what the backup card may say about the HA Companion.
 *
 * A panel without the Companion installed has nothing to say about it: the include-login checkbox would
 * offer to back up a login that does not exist, and the "needs the current helper" note would advertise a
 * capability for an absent app. So every mention is gated on the app being [installed], and only the
 * *offer* additionally requires the [helper]. Keeping this pure keeps it directly testable.
 */
internal fun backupCompanionCopy(installed: Boolean, helper: Boolean): BackupCompanionCopy {
    if (!installed) return BackupCompanionCopy(showLoginChoice = false, explainHelperRequirement = false)
    return BackupCompanionCopy(showLoginChoice = helper, explainHelperRequirement = !helper)
}
