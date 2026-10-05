package io.panelassistant.android.http

import io.panelassistant.android.util.GuardDbMaintenanceProtocol
import io.panelassistant.android.util.GuardDbPreparedArm
import io.panelassistant.android.util.GuardDbSentinelState
import io.panelassistant.android.util.GuardDbStartupSentinel
import io.panelassistant.android.util.Json
import java.security.MessageDigest
import java.security.SecureRandom

internal data class GuardDbBootstrapInstalledApp(
    val bytes: Long,
    val sha256: String,
    val signerSha256: String,
    val versionCode: Long,
    val versionName: String,
    val contractMinimum: Int,
    val contractMaximum: Int,
    val schema: Int,
)

internal data class GuardDbBootstrapHelper(
    val bytes: Long,
    val sha256: String,
    val buildId: String,
    val capabilitiesReply: String,
    val statusReply: String,
)

internal data class GuardDbBootstrapSecurity(
    val state: String,
    val epoch: Long,
    val authoritySha256: String,
    val remoteDebugOff: Boolean,
    val cdpRelayAbsent: Boolean,
)

/** Complete DB-free observation which must remain byte-for-byte stable for a bootstrap lease. */
internal data class GuardDbBootstrapExportSnapshot(
    val sentinel: GuardDbStartupSentinel,
    val prepared: GuardDbPreparedArm,
    val installedA: GuardDbBootstrapInstalledApp,
    val helper: GuardDbBootstrapHelper,
    val security: GuardDbBootstrapSecurity,
) {
    fun exactFor(expected: GuardDbStartupSentinel): Boolean {
        if (sentinel != expected || sentinel.state != GuardDbSentinelState.BASELINE_READY ||
            !prepared.matches(sentinel)
        ) return false
        if (installedA.bytes != prepared.aBytes || installedA.sha256 != prepared.aSha256 ||
            installedA.versionCode != prepared.aVersionCode ||
            installedA.contractMinimum != prepared.aContractMinimum ||
            installedA.contractMaximum != prepared.aContractMaximum || installedA.schema != prepared.aSchema ||
            installedA.versionName.isEmpty() || !GuardDbMaintenanceProtocol.validSha256(installedA.signerSha256)
        ) return false
        if (helper.bytes <= 0L || !GuardDbMaintenanceProtocol.validSha256(helper.sha256) ||
            !GuardDbMaintenanceProtocol.validSha256(helper.buildId) ||
            helper.capabilitiesReply != SUPPORTED_CAPABILITIES_REPLY || helper.statusReply != EMPTY_STATUS_REPLY
        ) return false
        return security.state == "HARDENED" && security.epoch == prepared.securityAuthorityEpoch &&
            security.epoch == sentinel.securityAuthorityEpoch &&
            GuardDbMaintenanceProtocol.validSha256(security.authoritySha256) &&
            security.remoteDebugOff && security.cdpRelayAbsent
    }

    fun approvalBinding(): String = listOf(
        sentinel.session,
        sentinel.bootNonce,
        prepared.canonical(),
        installedA.bytes,
        installedA.sha256,
        installedA.signerSha256,
        installedA.versionCode,
        installedA.versionName,
        installedA.contractMinimum,
        installedA.contractMaximum,
        installedA.schema,
        helper.bytes,
        helper.sha256,
        helper.buildId,
        helper.capabilitiesReply,
        helper.statusReply,
        security.state,
        security.epoch,
        security.authoritySha256,
        security.remoteDebugOff,
        security.cdpRelayAbsent,
    ).joinToString("\u0000")

    companion object {
        const val EMPTY_STATUS_REPLY =
            "OK GUARDSTATUS 0 EMPTY NONE NONE NONE NONE 0 0 0 NONE NONE 0 0"
        const val SUPPORTED_CAPABILITIES_REPLY =
            "${GuardDbMaintenanceProtocol.CAPS_REPLY} AUTONOMOUS SUPERVISED TERMINAL_RETIRE"
    }
}

internal sealed interface GuardDbBootstrapDatabaseRead {
    data class Exact(val bytes: ByteArray) : GuardDbBootstrapDatabaseRead
    data object TooLarge : GuardDbBootstrapDatabaseRead
    data object Mismatch : GuardDbBootstrapDatabaseRead
}

internal data class GuardDbBootstrapExportDependencies(
    val snapshot: (expectedSecurityEpoch: Long) -> GuardDbBootstrapExportSnapshot?,
    val readDatabase: (GuardDbPreparedArm) -> GuardDbBootstrapDatabaseRead,
    val databaseStillExact: (GuardDbPreparedArm) -> Boolean,
    val monotonicMs: () -> Long,
    val freshToken: () -> String = ::freshGuardDbBootstrapToken,
    val leaseLifetimeMs: Long = MAX_GUARD_DB_BOOTSTRAP_LEASE_MS,
)

internal data class GuardDbBootstrapExportCredentials(
    val peer: String,
    val session: String?,
    val captureId: String?,
    val token: String?,
)

internal data class GuardDbBootstrapExportReceipt(
    val captureId: String,
    val expiresElapsedRealtimeMs: Long,
    val token: String,
) {
    fun canonical(): String =
        "{\"capture_id\":${Json.str(captureId)}," +
            "\"database_path\":\"/api/v1/guard-db/bootstrap/database\"," +
            "\"expires_elapsed_realtime_ms\":$expiresElapsedRealtimeMs," +
            "\"ok\":true," +
            "\"proof_path\":\"/api/v1/guard-db/bootstrap/proof\"," +
            "\"token\":${Json.str(token)}}\n"
}

internal data class GuardDbBootstrapExportDatabase(
    val bytes: ByteArray,
    val captureId: String,
    val databaseSha256: String,
    val session: String,
)

/** One process-local lease. A successful issue replaces the prior lease; invalid probes never do. */
internal class GuardDbBootstrapExportLeaseStore(
    private val monotonicMs: () -> Long,
    private val lifetimeMs: Long,
) {
    init { require(lifetimeMs in 1..MAX_GUARD_DB_BOOTSTRAP_LEASE_MS) }

    private data class Lease(
        val snapshot: GuardDbBootstrapExportSnapshot,
        val peer: String,
        val captureId: String,
        val token: String,
        val expiresElapsedRealtimeMs: Long,
        val proof: ByteArray,
        val database: ByteArray,
    )

    private var active: Lease? = null

    @Synchronized
    fun issue(
        snapshot: GuardDbBootstrapExportSnapshot,
        peer: String,
        captureId: String,
        token: String,
        database: ByteArray,
    ): GuardDbBootstrapExportReceipt? {
        if (!GuardDbMaintenanceProtocol.validSession(captureId) ||
            !GuardDbMaintenanceProtocol.validSession(token) || database.isEmpty()
        ) return null
        val captured = monotonicMs().takeIf { it >= 0L } ?: return null
        val expires = runCatching { Math.addExact(captured, lifetimeMs) }.getOrNull() ?: return null
        val proof = canonicalGuardDbBootstrapProof(snapshot, peer, captureId, captured)
            .toByteArray(Charsets.UTF_8)
        // The caller just allocated this verified array for the lease and does not retain it. Keep
        // one exact in-memory copy; duplicating a permitted 64 MiB baseline can strand small panels.
        active = Lease(snapshot, peer, captureId, token, expires, proof, database)
        return GuardDbBootstrapExportReceipt(captureId, expires, token)
    }

    @Synchronized
    fun proof(
        credentials: GuardDbBootstrapExportCredentials,
        stillExact: (GuardDbBootstrapExportSnapshot) -> Boolean,
    ): ByteArray? = validLease(credentials, stillExact)?.proof?.copyOf()

    @Synchronized
    fun database(
        credentials: GuardDbBootstrapExportCredentials,
        stillExact: (GuardDbBootstrapExportSnapshot) -> Boolean,
    ): GuardDbBootstrapExportDatabase? = validLease(credentials, stillExact)?.let { lease ->
        GuardDbBootstrapExportDatabase(
            bytes = lease.database,
            captureId = lease.captureId,
            databaseSha256 = lease.snapshot.prepared.databaseSha256,
            session = lease.snapshot.sentinel.session,
        )
    }

    @Synchronized
    fun invalidate() {
        active = null
    }

    private fun validLease(
        credentials: GuardDbBootstrapExportCredentials,
        stillExact: (GuardDbBootstrapExportSnapshot) -> Boolean,
    ): Lease? {
        val lease = active ?: return null
        if (monotonicMs() >= lease.expiresElapsedRealtimeMs) {
            active = null
            return null
        }
        if (credentials.peer != lease.peer ||
            !secretEquals(credentials.session, lease.snapshot.sentinel.session) ||
            !secretEquals(credentials.captureId, lease.captureId) || !secretEquals(credentials.token, lease.token)
        ) return null
        if (!stillExact(lease.snapshot)) {
            active = null
            return null
        }
        return lease
    }
}

internal fun canonicalGuardDbBootstrapProof(
    snapshot: GuardDbBootstrapExportSnapshot,
    peer: String,
    captureId: String,
    capturedElapsedRealtimeMs: Long,
): String = with(snapshot) {
    "{\"baseline\":{" +
        "\"app_state_count\":${prepared.appStateRows}," +
        "\"database_bytes\":${prepared.databaseBytes}," +
        "\"database_sha256\":${Json.str(prepared.databaseSha256)}," +
        "\"ordered_app_state_sha256\":${Json.str(prepared.orderedAppStateSha256)}," +
        "\"schema\":${prepared.databaseSchema}," +
        "\"settings_semantic_sha256\":${Json.str(prepared.settingsSemanticSha256)}}," +
        "\"boot_nonce\":${Json.str(sentinel.bootNonce)}," +
        "\"capture_id\":${Json.str(captureId)}," +
        "\"captured_elapsed_realtime_ms\":$capturedElapsedRealtimeMs," +
        "\"format\":\"hapaneld-guard-db-bootstrap-proof-v2\"," +
        "\"guard\":{\"generation\":0,\"phase\":\"EMPTY\"}," +
        "\"helper\":{" +
        "\"build_id\":${Json.str(helper.buildId)}," +
        "\"bytes\":${helper.bytes}," +
        "\"capabilities_reply\":${Json.str(helper.capabilitiesReply)}," +
        "\"sha256\":${Json.str(helper.sha256)}," +
        "\"status_reply\":${Json.str(helper.statusReply)}}," +
        "\"installed_a\":{" +
        "\"bytes\":${installedA.bytes}," +
        "\"contract_maximum\":${installedA.contractMaximum}," +
        "\"contract_minimum\":${installedA.contractMinimum}," +
        "\"schema\":${installedA.schema}," +
        "\"sha256\":${Json.str(installedA.sha256)}," +
        "\"signer_sha256\":${Json.str(installedA.signerSha256)}," +
        "\"version_code\":${installedA.versionCode}," +
        "\"version_name\":${Json.str(installedA.versionName)}}," +
        "\"ok\":true," +
        "\"request_peer\":${Json.str(peer)}," +
        "\"security\":{" +
        "\"authority_sha256\":${Json.str(security.authoritySha256)}," +
        "\"cdp_relay_absent\":${security.cdpRelayAbsent}," +
        "\"epoch\":${security.epoch}," +
        "\"remote_debug_off\":${security.remoteDebugOff}," +
        "\"state\":${Json.str(security.state)}}," +
        "\"session\":${Json.str(sentinel.session)}}\n"
}

private val GUARD_DB_BOOTSTRAP_RANDOM = SecureRandom()

private fun freshGuardDbBootstrapToken(): String = ByteArray(32).also(GUARD_DB_BOOTSTRAP_RANDOM::nextBytes)
    .joinToString("") { "%02x".format(it) }

private fun secretEquals(provided: String?, expected: String): Boolean = provided != null &&
    MessageDigest.isEqual(provided.toByteArray(Charsets.US_ASCII), expected.toByteArray(Charsets.US_ASCII))

internal const val MAX_GUARD_DB_BOOTSTRAP_DATABASE_BYTES = 64L * 1024L * 1024L
internal const val MAX_GUARD_DB_BOOTSTRAP_LEASE_MS = 120_000L
internal const val GUARD_DB_BOOTSTRAP_SESSION_HEADER = "X-Guard-Db-Session"
internal const val GUARD_DB_BOOTSTRAP_CAPTURE_HEADER = "X-Guard-Db-Capture-Id"
internal const val GUARD_DB_BOOTSTRAP_TOKEN_HEADER = "X-Guard-Db-Bootstrap-Token"
internal const val GUARD_DB_BOOTSTRAP_DATABASE_SHA256_HEADER = "X-Guard-Db-Database-Sha256"
