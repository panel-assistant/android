package io.github.maxlyth.hapaneld.security

import android.os.SystemClock
import java.security.MessageDigest
import java.security.SecureRandom

internal enum class SensitiveOperation(val label: String) {
    APK_INSTALL("Install uploaded APK"),
    APK_FETCH("Download an APK from a link"),
    BACKUP_EXPORT("Export panel backup"),
    CONFIG_SECRET_EXPORT("Export settings with secrets"),
    CONFIG_IMPORT("Import panel settings"),
    BACKUP_RESTORE("Restore panel backup"),
    PACKAGE_UNINSTALL("Uninstall app"),
    PROFILE_ACTIVATE("Activate hardware profile"),
    DEVTOOLS_ENABLE("Expose WebView developer tools"),
    REMOTE_MEDIA("Play a remote media URL"),
    PACKAGE_TAME("Change vendor package state"),
    HOME_ROLE_HAND_BACK("Hand the home screen back"),
    DASHBOARD_RELOAD("Reload dashboard renderer"),
    DEVICE_REBOOT("Reboot panel"),
    COMPANION_REPAIR("Repair Companion configuration"),
    DASHBOARD_STORAGE_CLEAR("Clear dashboard browsing data"),
    DISPLAY_CONFIGURATION("Change display sizing"),
    POWER_CONFIGURATION("Change panel power safety guards"),
    POWER_SAFETY_ACKNOWLEDGEMENT("Hide one exact power safety caution"),
    GUARD_DB_MAINTENANCE("Advance database recovery"),
    CAMERA_ENABLE("Enable the panel camera"),
    // Migration scaffolding for moving entities off MQTT; it is deleted with MQTT.
    TRANSPORT_RELEASE("Hand panel control back to MQTT"),
}

internal data class PendingApproval(
    val id: String,
    val operation: SensitiveOperation,
    val summary: String,
    val peer: String,
    val createdAtMs: Long,
)

/**
 * Process-local, one-shot physical-presence approval. No approval is transferable between peers or
 * request payloads, and an approved request is consumed on its first exact replay. A process restart
 * intentionally forgets all approvals.
 */
internal class ApprovalBroker(
    private val monotonicMs: () -> Long = SystemClock::elapsedRealtime,
    private val random: SecureRandom = SecureRandom(),
    private val ttlMs: Long = DEFAULT_TTL_MS,
    private val maxPending: Int = MAX_PENDING,
) {
    enum class Decision { PENDING, APPROVED }
    enum class State { PENDING, APPROVED, ABSENT }

    private data class Record(
        val visible: PendingApproval,
        val digest: String,
        var approved: Boolean = false,
    )

    private val records = linkedMapOf<String, Record>()

    @Synchronized
    fun request(operation: SensitiveOperation, peer: String, payload: String, summary: String): Pair<Decision, String> {
        val now = monotonicMs()
        prune(now)
        val digest = requestDigest(operation, peer, payload)
        val match = records.values.firstOrNull { it.digest == digest }
        if (match != null) {
            if (match.approved) {
                records.remove(match.visible.id)
                return Decision.APPROVED to match.visible.id
            }
            return Decision.PENDING to match.visible.id
        }
        while (records.size >= maxPending) records.remove(records.keys.first())
        val id = token()
        records[id] = Record(
            PendingApproval(id, operation, summary.take(MAX_SUMMARY_CHARS), peer, now),
            digest,
        )
        return Decision.PENDING to id
    }

    @Synchronized
    fun pending(): List<PendingApproval> {
        prune()
        return records.values.filterNot { it.approved }.map { it.visible }
    }

    @Synchronized
    fun approve(id: String): Boolean {
        prune()
        val record = records[id] ?: return false
        record.approved = true
        return true
    }

    /** Where one record stands, without consuming it. Denied, expired and evicted records are all absent. */
    @Synchronized
    fun state(id: String): State {
        prune()
        val record = records[id] ?: return State.ABSENT
        return if (record.approved) State.APPROVED else State.PENDING
    }

    @Synchronized
    fun deny(id: String): Boolean = records.remove(id) != null

    @Synchronized
    fun clear() = records.clear()

    private fun prune() = prune(monotonicMs())

    private fun prune(now: Long) {
        records.entries.removeAll { expired(it.value.visible.createdAtMs, now) }
    }

    /** A monotonic clock should not move backwards; fail closed if an injected/platform clock does. */
    private fun expired(createdAt: Long, now: Long): Boolean =
        now < createdAt || now - createdAt >= ttlMs

    private fun token(): String = ByteArray(16).also(random::nextBytes).joinToString("") { "%02x".format(it) }

    private fun requestDigest(operation: SensitiveOperation, peer: String, payload: String): String {
        val bytes = "${operation.name}\u0000$peer\u0000$payload".toByteArray(Charsets.UTF_8)
        return MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }

    companion object {
        const val DEFAULT_TTL_MS = 10 * 60 * 1000L
        private const val MAX_PENDING = 12
        private const val MAX_SUMMARY_CHARS = 180
    }
}

internal object LocalApprovalBroker {
    val instance = ApprovalBroker()
}
