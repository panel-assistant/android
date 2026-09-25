package io.github.maxlyth.hapaneld.http

import android.util.Log
import io.github.maxlyth.hapaneld.security.SensitiveOperation
import io.github.maxlyth.hapaneld.control.RemoteDebugAuthorityResult
import io.github.maxlyth.hapaneld.control.RemoteDebugSecurityTransitionGate
import io.github.maxlyth.hapaneld.util.BoundedStreams
import io.github.maxlyth.hapaneld.util.ByteLimitExceeded
import io.github.maxlyth.hapaneld.util.GuardDbAppStaging
import io.github.maxlyth.hapaneld.util.GuardDbArmManifest
import io.github.maxlyth.hapaneld.util.GuardDbCandidateInspection
import io.github.maxlyth.hapaneld.util.GuardDbMaintenanceClient
import io.github.maxlyth.hapaneld.util.GuardDbMaintenanceProtocol
import io.github.maxlyth.hapaneld.util.GuardDbProcessAdmission
import io.github.maxlyth.hapaneld.util.GuardDbSentinelLoad
import io.github.maxlyth.hapaneld.util.GuardDbSentinelState
import io.github.maxlyth.hapaneld.util.GuardDbStartupSentinel
import io.github.maxlyth.hapaneld.util.GuardDbSentinelStore
import io.github.maxlyth.hapaneld.util.GuardDbSettingsAuthority
import io.github.maxlyth.hapaneld.util.GuardDbTerminalRetirement
import io.github.maxlyth.hapaneld.util.GuardDbTerminalRetirementLoad
import io.github.maxlyth.hapaneld.util.GuardDbTerminalRetirementState
import io.github.maxlyth.hapaneld.util.GuardDbTerminalRetirementStore
import io.github.maxlyth.hapaneld.util.InstallProgress
import io.github.maxlyth.hapaneld.util.InstallPresentation
import io.github.maxlyth.hapaneld.util.Json
import io.github.maxlyth.hapaneld.util.isLocalSource
import io.github.maxlyth.hapaneld.util.isLoopbackPeer
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.plugins.origin
import io.ktor.server.request.receiveStream
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import org.json.JSONObject

internal data class GuardDbBootstrapRouteDependencies(
    val pendingUploads: PendingUploadStore,
    val staging: GuardDbAppStaging,
    val inspectPending: (java.io.File) -> GuardDbCandidateInspection?,
    val inspectInstalled: () -> GuardDbCandidateInspection?,
    val settingsAuthority: () -> GuardDbSettingsAuthority?,
    val client: GuardDbMaintenanceClient,
    val sentinelStore: GuardDbSentinelStore,
    val bootNonce: () -> String?,
    val monotonicMs: () -> Long,
    val httpPort: () -> Int,
    val hardened: () -> Boolean,
    val securityEpoch: () -> Long?,
    val commitSentinel: (Long, GuardDbStartupSentinel) -> GuardDbSentinelCommit,
    val authorize: suspend (ApplicationCall, SensitiveOperation, String, String) -> Boolean,
    val prepare: (GuardDbArmManifest, ((() -> Unit) -> Unit)) -> Boolean,
    val contain: () -> Unit,
    val terminalRetirement: GuardDbTerminalRetirementRouteDependencies? = null,
)

internal data class GuardDbTerminalRetirementRouteDependencies(
    val client: GuardDbMaintenanceClient,
    val store: GuardDbTerminalRetirementStore,
    val hardened: () -> Boolean,
    val securityEpoch: () -> Long?,
    val authorize: suspend (ApplicationCall, SensitiveOperation, String, String) -> Boolean,
)

internal sealed interface GuardDbSentinelCommit {
    data class Committed(val load: GuardDbSentinelLoad.Valid) : GuardDbSentinelCommit
    data class Failed(val load: GuardDbSentinelLoad) : GuardDbSentinelCommit
    data object SecurityRefused : GuardDbSentinelCommit
}

internal fun guardDbDirectLanPeer(peer: String): Boolean =
    isLocalSource(peer) && !isLoopbackPeer(peer)

private fun guardDbWorkingPresentation() = InstallPresentation(
    "operation-working",
    mapOf("owner" to "guard-db"),
)

internal fun Route.guardDbBootstrapRoutes(dependencies: GuardDbBootstrapRouteDependencies) {
    route("/api/v1/guard-db") {
        get("/clock") {
            if (!guardDbDirectLanPeer(call.request.origin.remoteAddress)) {
                return@get call.guardDbError(HttpStatusCode.Forbidden, "direct-lan-required")
            }
            val now = dependencies.monotonicMs()
            call.respondText(
                "{\"ok\":true,\"elapsed_realtime_ms\":$now," +
                    "\"minimum_overall_budget_ms\":$MIN_OVERALL_BUDGET_MS," +
                    "\"maximum_overall_budget_ms\":$MAX_OVERALL_BUDGET_MS," +
                    "\"recovery_reserve_ms\":$RECOVERY_RESERVE_MS}",
                ContentType.Application.Json,
            )
        }
        get("/status") {
            if (!guardDbDirectLanPeer(call.request.origin.remoteAddress)) {
                return@get call.guardDbError(HttpStatusCode.Forbidden, "direct-lan-required")
            }
            when (val probe = dependencies.client.statusProbe()) {
                is GuardDbMaintenanceClient.StatusProbe.Valid -> with(probe.status) {
                    call.respondText(
                        "{\"ok\":true,\"generation\":$generation,\"phase\":${Json.str(phase.name)}," +
                            "\"session\":${Json.str(session.orEmpty())}," +
                            "\"error\":${Json.str(error.orEmpty())}," +
                            "\"outcome\":${Json.str(outcome?.name.orEmpty())}," +
                            "\"overall_deadline_elapsed_ms\":$overallDeadlineElapsedMs," +
                            "\"forward_deadline_elapsed_ms\":$forwardDeadlineElapsedMs}",
                        ContentType.Application.Json,
                    )
                }
                GuardDbMaintenanceClient.StatusProbe.Unreachable ->
                    call.guardDbError(HttpStatusCode.ServiceUnavailable, "helper-unreachable")
                GuardDbMaintenanceClient.StatusProbe.Unsupported ->
                    call.guardDbError(HttpStatusCode.ServiceUnavailable, "helper-unsupported")
                GuardDbMaintenanceClient.StatusProbe.Malformed ->
                    call.guardDbError(HttpStatusCode.BadGateway, "helper-status-malformed")
            }
        }
        get("/evidence") {
            if (!guardDbDirectLanPeer(call.request.origin.remoteAddress)) {
                return@get call.guardDbError(HttpStatusCode.Forbidden, "direct-lan-required")
            }
            val session = call.request.queryParameters.getAll("session")?.singleOrNull()
                ?.takeIf(GuardDbMaintenanceProtocol::validSession)
                ?: return@get call.guardDbError(HttpStatusCode.BadRequest, "invalid-session")
            val evidence = dependencies.client.evidence(session)
                ?: return@get call.guardDbError(HttpStatusCode.ServiceUnavailable, "evidence-unavailable")
            call.respondText(evidence.toString(Charsets.US_ASCII), ContentType.Text.Plain)
        }
        dependencies.terminalRetirement?.let { guardDbTerminalRetirementRoute(it, dependencies.staging) }
        post("/stage") { stageGuardDbCandidate(call, dependencies) }
        post("/discard") { discardGuardDbCandidate(call, dependencies) }
        post("/arm") { armGuardDbCanary(call, dependencies) }
    }
}

internal fun Route.guardDbTerminalRetirementRoute(
    dependencies: GuardDbTerminalRetirementRouteDependencies,
    staging: GuardDbAppStaging,
) {
    post("/evidence/retire") { retireGuardDbTerminalEvidence(call, dependencies, staging) }
}

private data class GuardDbTerminalRetirementRequest(
    val session: String,
    val generation: Long,
    val evidenceSha256: String,
)

private data class GuardDbTerminalObservation(
    val status: GuardDbMaintenanceProtocol.Status,
    val evidenceSha256: String,
    val retirement: GuardDbTerminalRetirement,
)

private sealed interface GuardDbTerminalRetirementSettlement {
    data class Complete(val retirementGeneration: Long) : GuardDbTerminalRetirementSettlement
    data class Pending(val state: String) : GuardDbTerminalRetirementSettlement
    data object RequiresReapproval : GuardDbTerminalRetirementSettlement
    data class Refused(
        val status: HttpStatusCode,
        val error: String,
        val holdLane: Boolean = false,
    ) : GuardDbTerminalRetirementSettlement
}

private val GuardDbTerminalRetirementSettlement.holdsLane: Boolean
    get() = this is GuardDbTerminalRetirementSettlement.Pending ||
        this is GuardDbTerminalRetirementSettlement.RequiresReapproval ||
        (this is GuardDbTerminalRetirementSettlement.Refused && holdLane)

private object GuardDbTerminalRetirementOperationLane {
    private var owner: String? = null
    private var ticket: InstallProgress.Ticket? = null

    @Synchronized
    fun acquire(key: String): Boolean {
        val current = ticket
        if (owner == key && current != null && InstallProgress.owns(current)) return true
        if (current != null || owner != null) return false
        val claimed = InstallProgress.start(
            "Guard DB terminal evidence retirement",
            guardDbWorkingPresentation(),
        ) ?: return false
        owner = key
        ticket = claimed
        return true
    }

    @Synchronized
    fun release(key: String, result: String) {
        if (owner != key) return
        ticket?.let {
            InstallProgress.finish(
                it,
                result,
                presentation = InstallPresentation("guard-db-retirement-settled"),
            )
        }
        ticket = null
        owner = null
    }
}

private suspend fun retireGuardDbTerminalEvidence(
    call: ApplicationCall,
    dependencies: GuardDbTerminalRetirementRouteDependencies,
    staging: GuardDbAppStaging,
) {
    if (!guardDbDirectLanPeer(call.request.origin.remoteAddress)) {
        return call.guardDbError(HttpStatusCode.Forbidden, "direct-lan-required")
    }
    if (!dependencies.hardened()) {
        return call.guardDbError(HttpStatusCode.PreconditionFailed, "hardened-required")
    }
    val body = call.guardDbBody() ?: return
    val request = parseTerminalRetirementRequest(body)
        ?: return call.guardDbError(HttpStatusCode.BadRequest, "invalid-retirement-request")
    val securityEpoch = dependencies.securityEpoch()
        ?: return call.guardDbError(HttpStatusCode.PreconditionFailed, "hardened-debug-off-proof-required")
    val laneKey = request.laneKey()
    var unresolvedIntent: GuardDbTerminalRetirement? = null

    when (val durable = dependencies.store.load().also(GuardDbProcessAdmission::updateTerminalRetirement)) {
        GuardDbTerminalRetirementLoad.Corrupt ->
            return call.guardDbError(HttpStatusCode.Locked, "retirement-record-corrupt")
        is GuardDbTerminalRetirementLoad.Valid -> {
            when (durable.retirement.state) {
                GuardDbTerminalRetirementState.COMPLETE -> if (durable.retirement.matches(request)) {
                    if (!GuardDbTerminalRetirementOperationLane.acquire(laneKey)) {
                        return call.guardDbError(HttpStatusCode.Conflict, "operation-busy")
                    }
                    val settlement = settleDurableTerminalRetirementCompletion(
                        dependencies.store,
                        durable.retirement,
                    )
                    if (!settlement.holdsLane) {
                        GuardDbTerminalRetirementOperationLane.release(
                            laneKey,
                            "Guard DB retirement settled",
                        )
                    }
                    return call.respondTerminalRetirement(settlement, request.session)
                }
                GuardDbTerminalRetirementState.RETRYABLE -> Unit
                GuardDbTerminalRetirementState.INTENT -> {
                    if (!durable.retirement.matches(request)) {
                        return call.guardDbError(HttpStatusCode.Conflict, "retirement-record-conflict")
                    }
                    if (!GuardDbTerminalRetirementOperationLane.acquire(laneKey)) {
                        return call.guardDbError(HttpStatusCode.Conflict, "operation-busy")
                    }
                    val settlement = reconcileTerminalRetirementIntent(
                        dependencies,
                        staging,
                        durable.retirement,
                        securityEpoch,
                    )
                    if (settlement is GuardDbTerminalRetirementSettlement.RequiresReapproval) {
                        unresolvedIntent = durable.retirement
                    } else {
                        if (!settlement.holdsLane) {
                            GuardDbTerminalRetirementOperationLane.release(
                                laneKey,
                                "Guard DB retirement settled",
                            )
                        }
                        return call.respondTerminalRetirement(settlement, request.session)
                    }
                }
            }
        }
        GuardDbTerminalRetirementLoad.Absent -> Unit
    }

    if (!dependencies.client.supported()) {
        return call.guardDbError(HttpStatusCode.ServiceUnavailable, "terminal-retirement-helper-required")
    }
    val preview = observeTerminalRetirement(dependencies.client, request)
    if (preview is GuardDbTerminalObservationResult.Refused) {
        unresolvedIntent?.let { intent ->
            val settlement = reconcileTerminalRetirementIntent(dependencies, staging, intent, securityEpoch)
            if (settlement !is GuardDbTerminalRetirementSettlement.RequiresReapproval) {
                if (!settlement.holdsLane) {
                    GuardDbTerminalRetirementOperationLane.release(
                        laneKey,
                        "Guard DB retirement settled",
                    )
                }
                return call.respondTerminalRetirement(settlement, request.session)
            }
        }
        return call.guardDbError(preview.status, preview.error)
    }
    preview as GuardDbTerminalObservationResult.Valid
    val approvalDigest = sha256Hex(
        (body + "\u0000" + preview.observation.status.retirementCanonical() +
            "\u0000EVIDENCE_SHA256\u0000" + preview.observation.evidenceSha256 +
            "\u0000SECURITY_EPOCH\u0000$securityEpoch").toByteArray(Charsets.UTF_8),
    )
    if (!dependencies.authorize(
            call,
            SensitiveOperation.GUARD_DB_MAINTENANCE,
            exactHttpApprovalPayload(call, approvalDigest),
            "Retire exact Guard DB terminal evidence ${request.session.take(12)} " +
                "${preview.observation.status.outcome}",
        )
    ) return

    if (!GuardDbTerminalRetirementOperationLane.acquire(laneKey)) {
        return call.guardDbError(HttpStatusCode.Conflict, "operation-busy")
    }

    val gated = RemoteDebugSecurityTransitionGate.withEpoch(securityEpoch) {
        if (!dependencies.hardened() || dependencies.securityEpoch() != securityEpoch ||
            !dependencies.client.supported()
        ) return@withEpoch GuardDbTerminalRetirementSettlement.Refused(
            HttpStatusCode.Conflict,
            "retirement-authority-changed",
            holdLane = unresolvedIntent != null,
        )
        val exact = observeTerminalRetirement(dependencies.client, request)
        if (exact !is GuardDbTerminalObservationResult.Valid || exact.observation != preview.observation) {
            unresolvedIntent?.let { intent ->
                val settlement = settleTerminalRetirementProbe(
                    dependencies.store,
                    staging,
                    intent,
                    dependencies.client.statusProbe(),
                )
                if (settlement !is GuardDbTerminalRetirementSettlement.RequiresReapproval) {
                    return@withEpoch settlement
                }
            }
            return@withEpoch GuardDbTerminalRetirementSettlement.Refused(
                HttpStatusCode.Conflict,
                "retirement-authority-changed",
                holdLane = unresolvedIntent != null,
            )
        }
        when (val durable = dependencies.store.load().also(GuardDbProcessAdmission::updateTerminalRetirement)) {
            GuardDbTerminalRetirementLoad.Corrupt -> return@withEpoch GuardDbTerminalRetirementSettlement.Refused(
                HttpStatusCode.Locked,
                "retirement-record-corrupt",
            )
            is GuardDbTerminalRetirementLoad.Valid -> {
                when (durable.retirement.state) {
                    GuardDbTerminalRetirementState.COMPLETE -> if (durable.retirement.matches(request)) {
                        return@withEpoch settleDurableTerminalRetirementCompletion(
                            dependencies.store,
                            durable.retirement,
                        )
                    }
                    GuardDbTerminalRetirementState.RETRYABLE -> Unit
                    GuardDbTerminalRetirementState.INTENT -> {
                        if (durable.retirement != exact.observation.retirement) {
                            return@withEpoch GuardDbTerminalRetirementSettlement.Refused(
                                HttpStatusCode.Conflict,
                                "retirement-record-conflict",
                                holdLane = true,
                            )
                        }
                        val settlement = settleTerminalRetirementProbe(
                            dependencies.store,
                            staging,
                            durable.retirement,
                            dependencies.client.statusProbe(),
                        )
                        if (settlement !is GuardDbTerminalRetirementSettlement.RequiresReapproval) {
                            return@withEpoch settlement
                        }
                    }
                }
            }
            GuardDbTerminalRetirementLoad.Absent -> Unit
        }
        val intent = exact.observation.retirement
        val written = dependencies.store.writeIntent(intent)
        val intentLoad = dependencies.store.load().also(GuardDbProcessAdmission::updateTerminalRetirement)
        if (!written || (intentLoad as? GuardDbTerminalRetirementLoad.Valid)?.retirement != intent) {
            return@withEpoch GuardDbTerminalRetirementSettlement.Refused(
                HttpStatusCode.InsufficientStorage,
                "retirement-intent-not-durable",
                holdLane = (intentLoad as? GuardDbTerminalRetirementLoad.Valid)?.retirement == intent,
            )
        }
        when (val result = dependencies.client.retireTerminal(
            request.session,
            request.generation,
            request.evidenceSha256,
        )) {
            is GuardDbMaintenanceProtocol.TerminalRetireResult.Accepted ->
                completeTerminalRetirement(dependencies.store, staging, intent)
            GuardDbMaintenanceProtocol.TerminalRetireResult.Indeterminate ->
                settleTerminalRetirementProbe(dependencies.store, staging, intent, dependencies.client.statusProbe())
            GuardDbMaintenanceProtocol.TerminalRetireResult.NotSubmitted ->
                if (unresolvedIntent != null) {
                    settleTerminalRetirementProbe(
                        dependencies.store,
                        staging,
                        intent,
                        dependencies.client.statusProbe(),
                    )
                } else {
                    markTerminalRetirementRetryable(
                        dependencies.store,
                        intent,
                        HttpStatusCode.ServiceUnavailable,
                        "retirement-not-submitted-reapproval-required",
                    )
                }
            is GuardDbMaintenanceProtocol.TerminalRetireResult.Rejected ->
                if (unresolvedIntent != null) {
                    settleTerminalRetirementProbe(
                        dependencies.store,
                        staging,
                        intent,
                        dependencies.client.statusProbe(),
                    )
                } else {
                    markTerminalRetirementRetryable(
                        dependencies.store,
                        intent,
                        HttpStatusCode.Conflict,
                        "retirement-rejected-reapproval-required",
                    )
                }
        }
    }
    val settlement = when (gated) {
        RemoteDebugAuthorityResult.Changed -> GuardDbTerminalRetirementSettlement.Refused(
            HttpStatusCode.Conflict,
            "retirement-authority-changed",
            holdLane = unresolvedIntent != null,
        )
        is RemoteDebugAuthorityResult.Value -> gated.value
    }
    if (!settlement.holdsLane) {
        GuardDbTerminalRetirementOperationLane.release(laneKey, "Guard DB retirement settled")
    }
    call.respondTerminalRetirement(settlement, request.session)
}

private suspend fun reconcileTerminalRetirementIntent(
    dependencies: GuardDbTerminalRetirementRouteDependencies,
    staging: GuardDbAppStaging,
    intent: GuardDbTerminalRetirement,
    securityEpoch: Long,
): GuardDbTerminalRetirementSettlement {
    val gated = RemoteDebugSecurityTransitionGate.withEpoch(securityEpoch) {
        if (!dependencies.hardened() || dependencies.securityEpoch() != securityEpoch) {
            return@withEpoch GuardDbTerminalRetirementSettlement.Refused(
                HttpStatusCode.Conflict,
                "retirement-authority-changed",
                holdLane = true,
            )
        }
        settleTerminalRetirementProbe(dependencies.store, staging, intent, dependencies.client.statusProbe())
    }
    return when (gated) {
        RemoteDebugAuthorityResult.Changed -> GuardDbTerminalRetirementSettlement.Refused(
            HttpStatusCode.Conflict,
            "retirement-authority-changed",
            holdLane = true,
        )
        is RemoteDebugAuthorityResult.Value -> gated.value
    }
}

private fun settleTerminalRetirementProbe(
    store: GuardDbTerminalRetirementStore,
    staging: GuardDbAppStaging,
    intent: GuardDbTerminalRetirement,
    probe: GuardDbMaintenanceClient.StatusProbe,
): GuardDbTerminalRetirementSettlement {
    val status = (probe as? GuardDbMaintenanceClient.StatusProbe.Valid)?.status
        ?: return GuardDbTerminalRetirementSettlement.Refused(
            HttpStatusCode.Locked,
            "retirement-status-held",
            holdLane = true,
        )
    if (status.phase == GuardDbMaintenanceProtocol.Phase.EMPTY) {
        return completeTerminalRetirement(store, staging, intent)
    }
    if (intent.matchesRetiring(status)) {
        return GuardDbTerminalRetirementSettlement.Pending("retiring")
    }
    if (intent.matchesTerminal(status)) {
        return GuardDbTerminalRetirementSettlement.RequiresReapproval
    }
    return GuardDbTerminalRetirementSettlement.Refused(
        HttpStatusCode.Locked,
        "retirement-status-held",
        holdLane = true,
    )
}

/**
 * The helper has retired its custody, so this canary is over: clear its staged pair, then record
 * COMPLETE, so no crash leaves a durable completion beside a pair nothing will clear. A pair that
 * cannot be cleared, or that a session record references, is kept and never holds completion back.
 * Replaying a durable completion clears nothing: the staging may by then hold a new run's pair.
 */
private fun completeTerminalRetirement(
    store: GuardDbTerminalRetirementStore,
    staging: GuardDbAppStaging,
    intent: GuardDbTerminalRetirement,
): GuardDbTerminalRetirementSettlement {
    if (!staging.clear()) Log.w("ha-paneld/guard-db-http", "Guard DB terminal retirement kept its staged candidate pair")
    return if (store.markComplete(intent)) {
        GuardDbProcessAdmission.updateTerminalRetirement(store.load())
        GuardDbTerminalRetirementSettlement.Complete(intent.finalGeneration + 1L)
    } else {
        GuardDbProcessAdmission.updateTerminalRetirement(store.load())
        GuardDbTerminalRetirementSettlement.Refused(
            HttpStatusCode.ServiceUnavailable,
            "retirement-completion-not-durable",
            holdLane = true,
        )
    }
}

private fun settleDurableTerminalRetirementCompletion(
    store: GuardDbTerminalRetirementStore,
    completion: GuardDbTerminalRetirement,
): GuardDbTerminalRetirementSettlement {
    val intent = completion.copy(state = GuardDbTerminalRetirementState.INTENT)
    val durable = store.markComplete(intent)
    val load = store.load().also(GuardDbProcessAdmission::updateTerminalRetirement)
    return if (durable && (load as? GuardDbTerminalRetirementLoad.Valid)?.retirement == completion) {
        GuardDbTerminalRetirementSettlement.Complete(completion.finalGeneration + 1L)
    } else {
        GuardDbTerminalRetirementSettlement.Refused(
            HttpStatusCode.ServiceUnavailable,
            "retirement-completion-not-durable",
            holdLane = true,
        )
    }
}

private fun markTerminalRetirementRetryable(
    store: GuardDbTerminalRetirementStore,
    intent: GuardDbTerminalRetirement,
    status: HttpStatusCode,
    error: String,
): GuardDbTerminalRetirementSettlement {
    val retryable = store.markRetryable(intent)
    val load = store.load().also(GuardDbProcessAdmission::updateTerminalRetirement)
    val expected = intent.copy(state = GuardDbTerminalRetirementState.RETRYABLE)
    return if (retryable && (load as? GuardDbTerminalRetirementLoad.Valid)?.retirement == expected) {
        GuardDbTerminalRetirementSettlement.Refused(status, error)
    } else {
        GuardDbTerminalRetirementSettlement.Refused(
            HttpStatusCode.ServiceUnavailable,
            "retirement-retry-state-not-durable",
            holdLane = true,
        )
    }
}

private sealed interface GuardDbTerminalObservationResult {
    data class Valid(val observation: GuardDbTerminalObservation) : GuardDbTerminalObservationResult
    data class Refused(val status: HttpStatusCode, val error: String) : GuardDbTerminalObservationResult
}

private fun observeTerminalRetirement(
    client: GuardDbMaintenanceClient,
    request: GuardDbTerminalRetirementRequest,
): GuardDbTerminalObservationResult {
    val status = when (val probe = client.statusProbe()) {
        is GuardDbMaintenanceClient.StatusProbe.Valid -> probe.status
        GuardDbMaintenanceClient.StatusProbe.Unreachable -> return GuardDbTerminalObservationResult.Refused(
            HttpStatusCode.ServiceUnavailable, "helper-unreachable",
        )
        GuardDbMaintenanceClient.StatusProbe.Unsupported -> return GuardDbTerminalObservationResult.Refused(
            HttpStatusCode.ServiceUnavailable, "helper-unsupported",
        )
        GuardDbMaintenanceClient.StatusProbe.Malformed -> return GuardDbTerminalObservationResult.Refused(
            HttpStatusCode.BadGateway, "helper-status-malformed",
        )
    }
    if (status.phase != GuardDbMaintenanceProtocol.Phase.FINALIZED ||
        status.session != request.session || status.generation != request.generation ||
        status.role != GuardDbMaintenanceProtocol.Role.A || status.error != null || status.outcome == null
    ) return GuardDbTerminalObservationResult.Refused(HttpStatusCode.Conflict, "exact-finalized-status-required")
    val evidence = client.evidence(request.session)
        ?: return GuardDbTerminalObservationResult.Refused(
            HttpStatusCode.ServiceUnavailable,
            "evidence-unavailable",
        )
    val evidenceStatus = GuardDbMaintenanceProtocol.evidenceStatus(evidence)
        ?: return GuardDbTerminalObservationResult.Refused(HttpStatusCode.BadGateway, "evidence-malformed")
    if (evidenceStatus != status) {
        return GuardDbTerminalObservationResult.Refused(HttpStatusCode.Conflict, "evidence-status-mismatch")
    }
    val evidenceSha256 = sha256Hex(evidence)
    if (evidenceSha256 != request.evidenceSha256) {
        return GuardDbTerminalObservationResult.Refused(HttpStatusCode.Conflict, "evidence-hash-mismatch")
    }
    val retirement = runCatching {
        GuardDbTerminalRetirement(
            state = GuardDbTerminalRetirementState.INTENT,
            session = request.session,
            finalGeneration = request.generation,
            bootNonce = requireNotNull(status.bootNonce),
            aSha256 = requireNotNull(status.apkSha256),
            aVersionCode = requireNotNull(status.versionCode),
            aSchema = requireNotNull(status.schema),
            outcome = requireNotNull(status.outcome),
            evidenceSha256 = evidenceSha256,
        )
    }.getOrNull() ?: return GuardDbTerminalObservationResult.Refused(
        HttpStatusCode.Conflict,
        "exact-finalized-status-required",
    )
    return GuardDbTerminalObservationResult.Valid(
        GuardDbTerminalObservation(status, evidenceSha256, retirement),
    )
}

private fun parseTerminalRetirementRequest(body: String): GuardDbTerminalRetirementRequest? = runCatching {
    val json = JSONObject(body)
    if (json.keys().asSequence().toSet() != setOf("session", "generation", "evidence_sha256")) return null
    val session = json.getString("session").takeIf(GuardDbMaintenanceProtocol::validSession) ?: return null
    val generation = json.getLong("generation").takeIf { it >= 0L && it < Long.MAX_VALUE } ?: return null
    val evidence = json.getString("evidence_sha256")
        .takeIf(GuardDbMaintenanceProtocol::validSha256) ?: return null
    GuardDbTerminalRetirementRequest(session, generation, evidence)
}.getOrNull()

private fun GuardDbTerminalRetirement.matches(request: GuardDbTerminalRetirementRequest): Boolean =
    session == request.session && finalGeneration == request.generation && evidenceSha256 == request.evidenceSha256

private fun GuardDbTerminalRetirementRequest.laneKey(): String =
    "$session\u0000$generation\u0000$evidenceSha256"

private fun GuardDbMaintenanceProtocol.Status.retirementCanonical(): String = listOf(
    generation,
    phase.name,
    session.orEmpty(),
    bootNonce.orEmpty(),
    role?.name.orEmpty(),
    apkSha256.orEmpty(),
    versionCode ?: 0L,
    schema ?: 0,
    baselineAppStateCount,
    error.orEmpty(),
    outcome?.name.orEmpty(),
    overallDeadlineElapsedMs,
    forwardDeadlineElapsedMs,
).joinToString("\u0000")

private suspend fun ApplicationCall.respondTerminalRetirement(
    settlement: GuardDbTerminalRetirementSettlement,
    session: String,
) {
    when (settlement) {
        is GuardDbTerminalRetirementSettlement.Complete -> respondText(
            "{\"ok\":true,\"state\":\"empty\",\"session\":${Json.str(session)}," +
                "\"retirement_generation\":${settlement.retirementGeneration},\"settlement\":\"complete\"}",
            ContentType.Application.Json,
        )
        is GuardDbTerminalRetirementSettlement.Pending -> respondText(
            "{\"ok\":true,\"state\":${Json.str(settlement.state)},\"session\":${Json.str(session)}," +
                "\"settlement\":\"poll-status\"}",
            ContentType.Application.Json,
            HttpStatusCode.Accepted,
        )
        GuardDbTerminalRetirementSettlement.RequiresReapproval -> guardDbError(
            HttpStatusCode.ServiceUnavailable,
            "retirement-reapproval-required",
        )
        is GuardDbTerminalRetirementSettlement.Refused -> guardDbError(settlement.status, settlement.error)
    }
}

private suspend fun discardGuardDbCandidate(
    call: ApplicationCall,
    dependencies: GuardDbBootstrapRouteDependencies,
) {
    if (!guardDbDirectLanPeer(call.request.origin.remoteAddress)) {
        return call.guardDbError(HttpStatusCode.Forbidden, "direct-lan-required")
    }
    if (!dependencies.hardened()) {
        return call.guardDbError(HttpStatusCode.PreconditionFailed, "hardened-required")
    }
    val body = call.guardDbBody() ?: return
    val role = parseDiscardRequest(body)
        ?: return call.guardDbError(HttpStatusCode.BadRequest, "invalid-discard-request")
    val current = dependencies.staging.load(role)
        ?: return call.guardDbError(HttpStatusCode.Conflict, "candidate-not-staged")
    val securityEpoch = dependencies.securityEpoch()
        ?: return call.guardDbError(HttpStatusCode.PreconditionFailed, "hardened-debug-off-proof-required")
    val approvalDigest = sha256Hex(
        (body + "\u0000" + current.canonical() + "\u0000SECURITY_EPOCH\u0000$securityEpoch")
            .toByteArray(Charsets.UTF_8),
    )
    if (!dependencies.authorize(
            call,
            SensitiveOperation.GUARD_DB_MAINTENANCE,
            exactHttpApprovalPayload(call, approvalDigest),
            "Discard exact $role candidate vc${current.versionCode} ${current.sha256.take(12)}",
        )
    ) return
    val ticket = InstallProgress.start("Guard DB $role discard", guardDbWorkingPresentation())
        ?: return call.guardDbError(HttpStatusCode.Conflict, "operation-busy")
    try {
        val cleared = RemoteDebugSecurityTransitionGate.withEpoch(securityEpoch) {
            dependencies.securityEpoch() == securityEpoch && dependencies.hardened() &&
                dependencies.staging.load(role) == current && dependencies.staging.clear(role)
        }
        when (cleared) {
            RemoteDebugAuthorityResult.Changed ->
                call.guardDbError(HttpStatusCode.Conflict, "discard-authority-changed")
            is RemoteDebugAuthorityResult.Value -> if (cleared.value) {
                call.respondText("{\"ok\":true,\"role\":${Json.str(role.name)}}", ContentType.Application.Json)
            } else {
                call.guardDbError(HttpStatusCode.Conflict, "discard-failed-candidate-retained")
            }
        }
    } finally {
        InstallProgress.finish(
            ticket,
            "Guard DB candidate discard finished",
            presentation = InstallPresentation("guard-db-candidate-discard-finished"),
        )
    }
}

private suspend fun stageGuardDbCandidate(
    call: ApplicationCall,
    dependencies: GuardDbBootstrapRouteDependencies,
) {
    if (!guardDbDirectLanPeer(call.request.origin.remoteAddress)) {
        return call.guardDbError(HttpStatusCode.Forbidden, "direct-lan-required")
    }
    if (!dependencies.hardened()) {
        return call.guardDbError(HttpStatusCode.PreconditionFailed, "hardened-required")
    }
    val body = call.guardDbBody() ?: return
    val request = parseStageRequest(body)
        ?: return call.guardDbError(HttpStatusCode.BadRequest, "invalid-stage-request")
    val pending = dependencies.pendingUploads.peek(request.token)
        ?: return call.guardDbError(HttpStatusCode.Conflict, "stale-or-missing-upload")
    val inspection = dependencies.inspectPending(pending.file)
        ?: return call.guardDbError(HttpStatusCode.UnprocessableEntity, "candidate-refused")
    val previous = dependencies.staging.load(request.role)
    val securityEpoch = dependencies.securityEpoch()
        ?: return call.guardDbError(HttpStatusCode.PreconditionFailed, "hardened-debug-off-proof-required")
    val approvalDigest = sha256Hex(
        (body + "\u0000" + inspection.canonical(request.role) + "\u0000PREVIOUS\u0000" +
            (previous?.canonical() ?: "NONE") + "\u0000SECURITY_EPOCH\u0000$securityEpoch")
            .toByteArray(Charsets.UTF_8),
    )
    if (!dependencies.authorize(
            call,
            SensitiveOperation.GUARD_DB_MAINTENANCE,
            exactHttpApprovalPayload(call, approvalDigest),
            "Claim exact ${request.role} candidate vc${inspection.versionCode} ${inspection.sha256.take(12)}",
        )
    ) return
    val ticket = InstallProgress.start(
        "Guard DB ${request.role} staging",
        guardDbWorkingPresentation(),
    )
        ?: return call.guardDbError(HttpStatusCode.Conflict, "operation-busy")
    try {
        val claim = RemoteDebugSecurityTransitionGate.withEpoch(securityEpoch) {
            if (dependencies.securityEpoch() != securityEpoch || !dependencies.hardened() ||
                dependencies.staging.load(request.role) != previous
            ) null
            else dependencies.staging.claim(
                request.role,
                dependencies.pendingUploads,
                request.token,
                inspection,
            )
        }
        val candidate = when (claim) {
            RemoteDebugAuthorityResult.Changed ->
                return call.guardDbError(HttpStatusCode.Conflict, "stage-authority-changed")
            is RemoteDebugAuthorityResult.Value -> claim.value
                ?: return call.guardDbError(HttpStatusCode.Conflict, "stage-failed-upload-retained")
        }
        call.respondText(
            "{\"ok\":true,\"role\":${Json.str(candidate.role.name)}," +
                "\"sha256\":${Json.str(candidate.sha256)},\"version_code\":${candidate.versionCode}," +
                "\"schema\":${candidate.expectedSchema}}",
            ContentType.Application.Json,
        )
    } finally {
        InstallProgress.finish(
            ticket,
            "Guard DB candidate staging finished",
            presentation = InstallPresentation("guard-db-candidate-staging-finished"),
        )
    }
}

private suspend fun armGuardDbCanary(
    call: ApplicationCall,
    dependencies: GuardDbBootstrapRouteDependencies,
) {
    if (!guardDbDirectLanPeer(call.request.origin.remoteAddress)) {
        return call.guardDbError(HttpStatusCode.Forbidden, "direct-lan-required")
    }
    if (!dependencies.hardened()) {
        return call.guardDbError(HttpStatusCode.PreconditionFailed, "hardened-required")
    }
    val body = call.guardDbBody() ?: return
    val request = parseArmRequest(body)
        ?: return call.guardDbError(HttpStatusCode.BadRequest, "invalid-arm-request")
    if (request.overallBudgetMs !in MIN_OVERALL_BUDGET_MS..MAX_OVERALL_BUDGET_MS) {
        return call.guardDbError(HttpStatusCode.BadRequest, "invalid-overall-budget")
    }
    val boot = dependencies.bootNonce()
        ?: return call.guardDbError(HttpStatusCode.ServiceUnavailable, "boot-identity-unavailable")
    val securityEpoch = dependencies.securityEpoch()
        ?: return call.guardDbError(HttpStatusCode.PreconditionFailed, "hardened-debug-off-proof-required")
    val preview = exactArmManifest(
        dependencies, request.session, boot, request.overallBudgetMs, securityEpoch,
    )
        ?: return call.guardDbError(HttpStatusCode.Conflict, "candidate-pair-refused")
    if (!dependencies.client.supported()) {
        return call.guardDbError(HttpStatusCode.ServiceUnavailable, "autonomous-supervised-helper-required")
    }
    val empty = (dependencies.client.statusProbe() as? GuardDbMaintenanceClient.StatusProbe.Valid)?.status
    if (empty?.phase != GuardDbMaintenanceProtocol.Phase.EMPTY || empty.generation != 0L) {
        return call.guardDbError(HttpStatusCode.Conflict, "helper-not-empty")
    }
    val approvalDigest = sha256Hex(
        (body + "\u0000" + preview.canonical() + "\u0000HELPER\u00000\u0000EMPTY\u0000AUTONOMOUS\u0000SUPERVISED")
            .plus("\u0000SECURITY_EPOCH\u0000$securityEpoch")
            .toByteArray(Charsets.UTF_8),
    )
    if (!dependencies.authorize(
            call,
            SensitiveOperation.GUARD_DB_MAINTENANCE,
            exactHttpApprovalPayload(call, approvalDigest),
            "ARM exact A/B database canary ${preview.a.versionCode}→${preview.b.versionCode} ${request.session.take(12)}",
        )
    ) return
    val ticket = InstallProgress.start("Guard DB ARM", guardDbWorkingPresentation())
        ?: return call.guardDbError(HttpStatusCode.Conflict, "operation-busy")
    var handedOff = false
    var shutdown: (() -> Unit)? = null
    try {
        // Reinspect every authority after approval and lane acquisition. A replacement candidate,
        // helper generation, security mode, ADB state, or boot identity requires a new challenge.
        val exact = exactArmManifest(
            dependencies, request.session, boot, request.overallBudgetMs, securityEpoch,
        )
        if (exact != preview || dependencies.bootNonce() != boot) {
            return call.guardDbError(HttpStatusCode.Conflict, "arm-authority-changed")
        }
        if (!dependencies.client.supported()) {
            return call.guardDbError(HttpStatusCode.ServiceUnavailable, "autonomous-supervised-helper-required")
        }
        val rechecked = (dependencies.client.statusProbe() as? GuardDbMaintenanceClient.StatusProbe.Valid)?.status
        if (rechecked?.phase != GuardDbMaintenanceProtocol.Phase.EMPTY || rechecked.generation != 0L) {
            return call.guardDbError(HttpStatusCode.Conflict, "helper-not-empty")
        }
        val sentinel = GuardDbStartupSentinel(
            state = GuardDbSentinelState.INTENT,
            session = request.session,
            bootNonce = boot,
            aSha256 = exact.a.sha256,
            aVersionCode = exact.a.versionCode,
            aSchema = exact.a.expectedSchema,
            bSha256 = exact.b.sha256,
            bVersionCode = exact.b.versionCode,
            bSchema = exact.b.expectedSchema,
            settingsAuthorityVersion = exact.settingsAuthority.version,
            settingsAuthorityBytes = exact.settingsAuthority.bytes,
            settingsAuthoritySha256 = exact.settingsAuthority.sha256,
            securityAuthorityEpoch = exact.securityAuthorityEpoch,
            httpPort = dependencies.httpPort(),
            hardened = true,
        )
        val commit = dependencies.commitSentinel(securityEpoch, sentinel)
        val durable = when (commit) {
            GuardDbSentinelCommit.SecurityRefused ->
                return call.guardDbError(HttpStatusCode.PreconditionFailed, "hardened-debug-off-proof-required")
            is GuardDbSentinelCommit.Committed -> commit.load.takeIf { it.sentinel == sentinel }
                ?: return call.guardDbError(HttpStatusCode.Conflict, "sentinel-authority-changed")
            is GuardDbSentinelCommit.Failed -> {
                if (commit.load !is GuardDbSentinelLoad.Absent) {
                    GuardDbProcessAdmission.update(commit.load)
                    dependencies.contain()
                }
                return call.guardDbError(HttpStatusCode.InsufficientStorage, "sentinel-not-durable")
            }
        }
        GuardDbProcessAdmission.update(durable)
        if (!dependencies.prepare(exact) { callback -> shutdown = callback }) {
            val cleared = dependencies.sentinelStore.clear(request.session)
            if (cleared) GuardDbProcessAdmission.update(GuardDbSentinelLoad.Absent)
            else dependencies.contain()
            return call.guardDbError(
                if (cleared) HttpStatusCode.Conflict else HttpStatusCode.Locked,
                if (cleared) "shutdown-handoff-busy" else "shutdown-handoff-held",
            )
        }
        handedOff = true
        try {
            call.respondText(
                    "{\"ok\":true,\"state\":\"preparing-clean-proof\",\"session\":${Json.str(request.session)}," +
                    "\"settlement\":\"approve-arm-commit-after-shutdown\",\"same_boot_only\":true}",
                ContentType.Application.Json,
                HttpStatusCode.Accepted,
            )
        } finally {
            // respondText has completed its response pipeline before the service-drain callback can
            // stop the server. If the peer disconnects, the durable INTENT remains the receipt.
            requireNotNull(shutdown).invoke()
        }
    } finally {
        if (!handedOff) InstallProgress.finish(
            ticket,
            "Guard DB ARM did not start",
            presentation = InstallPresentation("guard-db-arm-not-started"),
        )
        // On success the one-way CAPTURE/INSTALL_B handoff kills this process. The ticket deliberately
        // remains occupied until then; the durable sentinel gates every successor and mutation path.
    }
}

private data class GuardDbStageRequest(val token: String, val role: GuardDbMaintenanceProtocol.Role)

private fun parseDiscardRequest(body: String): GuardDbMaintenanceProtocol.Role? = runCatching {
    val json = JSONObject(body)
    if (json.keys().asSequence().toSet() != setOf("role")) return null
    GuardDbMaintenanceProtocol.Role.values().firstOrNull { it.name == json.getString("role") }
}.getOrNull()

private fun parseStageRequest(body: String): GuardDbStageRequest? = runCatching {
    val json = JSONObject(body)
    if (json.keys().asSequence().toSet() != setOf("token", "role")) return null
    val token = json.getString("token").takeIf { Regex("[A-Za-z0-9-]{1,128}").matches(it) } ?: return null
    val role = GuardDbMaintenanceProtocol.Role.values().firstOrNull { it.name == json.getString("role") }
        ?: return null
    GuardDbStageRequest(token, role)
}.getOrNull()

private data class GuardDbArmRequest(val session: String, val overallBudgetMs: Long)

private fun parseArmRequest(body: String): GuardDbArmRequest? = runCatching {
    val json = JSONObject(body)
    if (json.keys().asSequence().toSet() != setOf("session", "overall_budget_ms")) return null
    val session = json.getString("session").takeIf(GuardDbMaintenanceProtocol::validSession) ?: return null
    val budget = json.getLong("overall_budget_ms").takeIf { it > 0L } ?: return null
    GuardDbArmRequest(session, budget)
}.getOrNull()

private fun exactArmManifest(
    dependencies: GuardDbBootstrapRouteDependencies,
    session: String,
    boot: String,
    overallBudgetMs: Long,
    securityAuthorityEpoch: Long,
): GuardDbArmManifest? {
    val a = dependencies.staging.load(GuardDbMaintenanceProtocol.Role.A) ?: return null
    val b = dependencies.staging.load(GuardDbMaintenanceProtocol.Role.B) ?: return null
    val installed = dependencies.inspectInstalled() ?: return null
    val settingsAuthority = dependencies.settingsAuthority() ?: return null
    if (a.bytes != installed.bytes || a.sha256 != installed.sha256 ||
        a.versionCode != installed.versionCode || a.contractMinimum != installed.contractMinimum ||
        a.contractMaximum != installed.contractMaximum || a.expectedSchema != installed.expectedSchema ||
        a.settingsAuthorityVersion != installed.settingsAuthorityVersion ||
        a.settingsAuthorityBytes != installed.settingsAuthorityBytes ||
        a.settingsAuthoritySha256 != installed.settingsAuthoritySha256
    ) return null
    if (listOf(a, b).any {
            it.settingsAuthorityVersion != settingsAuthority.version ||
                it.settingsAuthorityBytes != settingsAuthority.bytes ||
                it.settingsAuthoritySha256 != settingsAuthority.sha256
        }
    ) return null
    return runCatching {
        GuardDbArmManifest(
            session, boot, a, b, overallBudgetMs, settingsAuthority, securityAuthorityEpoch,
        )
    }.getOrNull()
}

private fun GuardDbCandidateInspection.canonical(role: GuardDbMaintenanceProtocol.Role): String =
    listOf(
        role.name, bytes, sha256, versionCode, signerSha256, contractMinimum, contractMaximum, expectedSchema,
        settingsAuthorityVersion, settingsAuthorityBytes, settingsAuthoritySha256,
    )
        .joinToString("\u0000")

private fun GuardDbMaintenanceProtocol.Candidate.canonical(): String = listOf(
    role.name, bytes, sha256, versionCode, contractMinimum, contractMaximum, expectedSchema,
    settingsAuthorityVersion, settingsAuthorityBytes, settingsAuthoritySha256,
).joinToString("\u0000")

private fun GuardDbArmManifest.canonical(): String = listOf(
    session,
    bootNonce,
    overallBudgetMs,
    settingsAuthority.version, settingsAuthority.bytes, settingsAuthority.sha256,
    securityAuthorityEpoch,
    a.role.name, a.bytes, a.sha256, a.versionCode, a.contractMinimum, a.contractMaximum, a.expectedSchema,
    b.role.name, b.bytes, b.sha256, b.versionCode, b.contractMinimum, b.contractMaximum, b.expectedSchema,
).joinToString("\u0000")

private suspend fun ApplicationCall.guardDbBody(): String? = try {
    BoundedStreams.readBytes(receiveStream(), MAX_GUARD_BODY_BYTES).toString(Charsets.UTF_8)
} catch (_: ByteLimitExceeded) {
    guardDbError(HttpStatusCode.PayloadTooLarge, "body-too-large")
    null
} catch (_: Exception) {
    guardDbError(HttpStatusCode.BadRequest, "body-unreadable")
    null
}

private suspend fun ApplicationCall.guardDbError(status: HttpStatusCode, error: String) = respondText(
    "{\"ok\":false,\"error\":${Json.str(error)}}",
    ContentType.Application.Json,
    status,
)

private const val MAX_GUARD_BODY_BYTES = 4096L
internal const val MAX_OVERALL_BUDGET_MS = GuardDbMaintenanceProtocol.MAX_OVERALL_BUDGET_MS
internal const val MIN_OVERALL_BUDGET_MS = GuardDbMaintenanceProtocol.MIN_OVERALL_BUDGET_MS
internal const val RECOVERY_RESERVE_MS = GuardDbMaintenanceProtocol.RECOVERY_RESERVE_MS
