package io.panelassistant.android.http

import android.content.Context
import android.util.Log
import io.panelassistant.android.security.ApprovalBroker
import io.panelassistant.android.security.LocalApprovalBroker
import io.panelassistant.android.security.SensitiveOperation
import io.panelassistant.android.util.BoundedStreams
import io.panelassistant.android.util.ByteLimitExceeded
import io.panelassistant.android.util.GuardDbMaintenanceClient
import io.panelassistant.android.util.GuardDbMaintenanceProtocol
import io.panelassistant.android.util.GuardDbAppStaging
import io.panelassistant.android.util.GuardDbArmCoordinator
import io.panelassistant.android.util.GuardDbArmManifest
import io.panelassistant.android.util.GuardDbArmTransferResult
import io.panelassistant.android.util.GuardDbPreparedArm
import io.panelassistant.android.util.GuardDbPreparedArmLoad
import io.panelassistant.android.util.GuardDbPreparedArmStore
import io.panelassistant.android.util.GuardDbSentinelStore
import io.panelassistant.android.util.GuardDbExactARefusalProof
import io.panelassistant.android.util.proveExactARefusalUnderB
import io.panelassistant.android.util.canonicalizeGuardDbMainForRefusal
import io.panelassistant.android.util.exactGuardDbFinalStatus
import io.panelassistant.android.util.GuardDbStartupSentinel
import io.panelassistant.android.util.Json
import io.panelassistant.android.util.isLocalSource
import io.panelassistant.android.util.isLoopbackPeer
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.origin
import io.ktor.server.request.receiveStream
import io.ktor.server.request.uri
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** Writer-free same-boot control plane used only while the no-backup Guard sentinel is present. */
internal class GuardDbMaintenanceServer(
    private val context: Context?,
    private val sentinel: GuardDbStartupSentinel,
    private val client: GuardDbMaintenanceClient,
    private val staging: GuardDbAppStaging,
    private val preparedStore: GuardDbPreparedArmStore,
    private val sentinelStore: GuardDbSentinelStore,
    private val security: GuardDbMaintenanceSecurityAuthority,
    private val broker: ApprovalBroker = LocalApprovalBroker.instance,
    private val loadPrepared: () -> GuardDbPreparedArmLoad = preparedStore::load,
    private val exactManifest: (GuardDbPreparedArm) -> GuardDbArmManifest? = { it.exactManifest(staging) },
    private val promoteArmed: (String) -> Boolean = sentinelStore::promoteArmed,
    private val refusalProof: (GuardDbPreparedArm) -> GuardDbExactARefusalProof? = { prepared ->
        val exactContext = requireNotNull(context)
        if (!canonicalizeGuardDbMainForRefusal(exactContext)) null
        else proveExactARefusalUnderB(exactContext, prepared, staging)
    },
    private val exactFinalStatus: (GuardDbMaintenanceProtocol.Status) -> Boolean = { status ->
        exactGuardDbFinalStatus(requireNotNull(context), status, sentinel)
    },
    private val bootstrapExport: GuardDbBootstrapExportDependencies? = null,
    private val onFinalized: () -> Unit,
) {
    private val mutation = Mutex()
    private val bootstrapLeases = bootstrapExport?.let {
        GuardDbBootstrapExportLeaseStore(it.monotonicMs, it.leaseLifetimeMs)
    }
    @Volatile private var stopEngine: (() -> Unit)? = null

    fun start() {
        check(stopEngine == null)
        val started = embeddedServer(CIO, port = sentinel.httpPort, host = "::") {
            configureGuardDbMaintenanceApplication(this)
        }.also { it.start(wait = false) }
        stopEngine = { started.stop(500L, 1_500L) }
        Log.i(TAG, "writer-free Guard DB maintenance HTTP listening on :${sentinel.httpPort}")
    }

    /** Same application module as the physical listener, exposed for an in-process Ktor fixture. */
    internal fun configureGuardDbMaintenanceApplication(application: Application) = with(application) {
            intercept(ApplicationCallPipeline.Plugins) {
                call.response.headers.append("X-Content-Type-Options", "nosniff")
                call.response.headers.append("X-Frame-Options", "DENY")
                call.response.headers.append("Content-Security-Policy", "default-src 'none'; frame-ancestors 'none'")
                call.response.headers.append("Cache-Control", "no-store")
                val peer = call.request.origin.remoteAddress
                if (!isLocalSource(peer) || isLoopbackPeer(peer)) {
                    call.respondText("forbidden\n", status = HttpStatusCode.Forbidden)
                    return@intercept finish()
                }
                if (!OriginGuard.allowed(
                        call.request.origin.method.value,
                        call.request.headers["Origin"],
                        call.request.headers["Referer"],
                        call.request.headers["Host"],
                    ) || !OriginGuard.hostAllowed(call.request.headers["Host"], emptySet())
                ) {
                    call.respondText("request origin refused\n", status = HttpStatusCode.Forbidden)
                    return@intercept finish()
                }
            }
            routing {
                get("/health") {
                    call.respondText(
                        "{\"ok\":true,\"mode\":\"guard-db-maintenance\",\"same_boot_only\":true}",
                        ContentType.Application.Json,
                    )
                }
                route("/api/v1/guard-db") {
                    get("/status") { respondStatus(call, client.statusProbe()) }
                    get("/evidence") {
                        val status = (client.statusProbe() as? GuardDbMaintenanceClient.StatusProbe.Valid)?.status
                        if (status?.session != sentinel.session || status.bootNonce != sentinel.bootNonce) {
                            return@get call.respondJsonError(HttpStatusCode.Conflict, "sentinel-status-mismatch")
                        }
                        val evidence = client.evidence(sentinel.session)
                            ?: return@get call.respondJsonError(HttpStatusCode.ServiceUnavailable, "evidence-unavailable")
                        call.respondBytes(evidence, ContentType.Text.Plain)
                    }
                    if (bootstrapExport != null) {
                        post("/bootstrap/export") { createBootstrapExport(call, bootstrapExport) }
                        get("/bootstrap/proof") { serveBootstrapProof(call, bootstrapExport) }
                        get("/bootstrap/database") { serveBootstrapDatabase(call, bootstrapExport) }
                    }
                    post("/arm/commit") { commitPreparedArm(call) }
                    post("/refusal") { submitExactARefusal(call) }
                    post("/cancel") { cancelBeforeCustody(call) }
                    post("/action") {
                        val body = try {
                            BoundedStreams.readBytes(call.receiveStream(), MAX_ACTION_BODY_BYTES).toString(Charsets.UTF_8)
                        } catch (_: ByteLimitExceeded) {
                            return@post call.respondJsonError(HttpStatusCode.PayloadTooLarge, "body-too-large")
                        }
                        val request = parseAction(body)
                            ?: return@post call.respondJsonError(HttpStatusCode.BadRequest, "invalid-action")
                        if (request.session != sentinel.session) {
                            return@post call.respondJsonError(HttpStatusCode.Conflict, "session-mismatch")
                        }
                        val previewStatus = (client.statusProbe()
                            as? GuardDbMaintenanceClient.StatusProbe.Valid)?.status
                            ?: return@post call.respondJsonError(
                                HttpStatusCode.ServiceUnavailable,
                                "status-unavailable",
                            )
                        if (!request.matches(previewStatus) ||
                            !guardDbActionAllowed(previewStatus.phase, request.action) ||
                            !statusMatchesSentinelIdentity(previewStatus, sentinel)
                        ) return@post call.respondJsonError(HttpStatusCode.Conflict, "stale-transition")
                        val securityEpoch = security.readyEpoch()
                            ?: return@post call.respondJsonError(
                                HttpStatusCode.PreconditionFailed,
                                "hardened-debug-off-proof-required",
                            )
                        val peer = call.request.origin.remoteAddress
                        val approvalPayload = exactHttpApprovalPayload(call, sha256Hex(
                            (body + "\u0000STATUS\u0000" + previewStatus.canonical() +
                                "\u0000SECURITY_EPOCH\u0000$securityEpoch").toByteArray(Charsets.UTF_8),
                        ))
                        val (decision, id) = broker.request(
                            SensitiveOperation.GUARD_DB_MAINTENANCE,
                            peer,
                            approvalPayload,
                            "${request.action} Guard DB generation ${request.generation}",
                        )
                        if (decision != ApprovalBroker.Decision.APPROVED) {
                            return@post call.respondText(
                                "{\"ok\":false,\"error\":\"approval-required\",\"approval_id\":${Json.str(id)}}",
                                ContentType.Application.Json,
                                HttpStatusCode.Accepted,
                            )
                        }
                        mutation.withLock {
                            val secured = security.commit(securityEpoch) {
                                val status = (client.statusProbe()
                                    as? GuardDbMaintenanceClient.StatusProbe.Valid)?.status
                                if (status == null || status != previewStatus || !request.matches(status) ||
                                    !guardDbActionAllowed(status.phase, request.action) ||
                                    !statusMatchesSentinelIdentity(status, sentinel)
                                ) return@commit null
                                val initial = settleGuardDbAction(client, sentinel, request)
                                if (initial is GuardDbMutationSettlement.Settled &&
                                    initial.status.phase == GuardDbMaintenanceProtocol.Phase.FINALIZED &&
                                    !exactFinalStatus(initial.status)
                                ) {
                                    GuardDbMutationSettlement.Held(
                                        GuardDbMaintenanceClient.StatusProbe.Valid(initial.status),
                                    )
                                } else initial
                            }
                            val settlement = when (secured) {
                                GuardDbMaintenanceSecurityResult.Changed ->
                                    return@withLock call.respondJsonError(
                                        HttpStatusCode.Conflict, "security-authority-changed",
                                    )
                                GuardDbMaintenanceSecurityResult.Refused ->
                                    return@withLock call.respondJsonError(
                                        HttpStatusCode.PreconditionFailed,
                                        "hardened-debug-off-proof-required",
                                    )
                                is GuardDbMaintenanceSecurityResult.Value -> secured.value
                                    ?: return@withLock call.respondJsonError(
                                        HttpStatusCode.Conflict, "stale-transition",
                                    )
                            }
                            respondMutation(call, settlement)
                            if (settlement is GuardDbMutationSettlement.Settled &&
                                settlement.status.phase == GuardDbMaintenanceProtocol.Phase.FINALIZED &&
                                settlement.status.outcome != null
                            ) onFinalized()
                        }
                    }
                }
            }
    }

    fun stop() {
        stopEngine?.invoke()
        stopEngine = null
    }

    private suspend fun createBootstrapExport(
        call: ApplicationCall,
        dependencies: GuardDbBootstrapExportDependencies,
    ) {
        if (sentinel.state != io.panelassistant.android.util.GuardDbSentinelState.BASELINE_READY) {
            return call.respondJsonError(HttpStatusCode.Conflict, "clean-baseline-not-ready")
        }
        val body = try {
            BoundedStreams.readBytes(call.receiveStream(), MAX_ACTION_BODY_BYTES).toString(Charsets.UTF_8)
        } catch (_: ByteLimitExceeded) {
            return call.respondJsonError(HttpStatusCode.PayloadTooLarge, "body-too-large")
        }
        val request = parseBootstrapExport(body)
            ?: return call.respondJsonError(HttpStatusCode.BadRequest, "invalid-bootstrap-export")
        if (request.session != sentinel.session) {
            return call.respondJsonError(HttpStatusCode.Conflict, "session-mismatch")
        }
        val securityEpoch = security.readyEpoch()
            ?: return call.respondJsonError(
                HttpStatusCode.PreconditionFailed,
                "hardened-debug-off-proof-required",
            )
        val preview = dependencies.snapshot(securityEpoch)?.takeIf { it.exactFor(sentinel) }
            ?: return call.respondJsonError(HttpStatusCode.Conflict, "bootstrap-authority-unavailable")
        if (preview.prepared.databaseBytes > MAX_GUARD_DB_BOOTSTRAP_DATABASE_BYTES) {
            return call.respondJsonError(HttpStatusCode.PayloadTooLarge, "baseline-database-too-large")
        }
        val payload = exactHttpApprovalPayload(call, sha256Hex(
            (body + "\u0000BOOTSTRAP_EXPORT\u0000" + preview.approvalBinding())
                .toByteArray(Charsets.UTF_8),
        ))
        val peer = call.request.origin.remoteAddress
        val (decision, id) = broker.request(
            SensitiveOperation.GUARD_DB_MAINTENANCE,
            peer,
            payload,
            "Export exact prepared baseline ${preview.prepared.databaseSha256.take(12)} for bootstrap sealing",
        )
        if (decision != ApprovalBroker.Decision.APPROVED) {
            return call.respondText(
                "{\"ok\":false,\"error\":\"approval-required\",\"approval_id\":${Json.str(id)}}",
                ContentType.Application.Json,
                HttpStatusCode.Accepted,
            )
        }
        mutation.withLock {
            val secured = security.commit(securityEpoch) {
                val current = dependencies.snapshot(securityEpoch)
                    ?.takeIf { it == preview && it.exactFor(sentinel) }
                    ?: return@commit GuardDbBootstrapExportBuild.AuthorityChanged
                val read = dependencies.readDatabase(current.prepared)
                val database = when (read) {
                    is GuardDbBootstrapDatabaseRead.Exact -> read.bytes
                    GuardDbBootstrapDatabaseRead.TooLarge ->
                        return@commit GuardDbBootstrapExportBuild.TooLarge
                    GuardDbBootstrapDatabaseRead.Mismatch ->
                        return@commit GuardDbBootstrapExportBuild.DatabaseMismatch
                }
                if (database.size.toLong() > MAX_GUARD_DB_BOOTSTRAP_DATABASE_BYTES) {
                    return@commit GuardDbBootstrapExportBuild.TooLarge
                }
                if (database.size.toLong() != current.prepared.databaseBytes ||
                    sha256Hex(database) != current.prepared.databaseSha256
                ) return@commit GuardDbBootstrapExportBuild.DatabaseMismatch
                if (dependencies.snapshot(securityEpoch) != current ||
                    !dependencies.databaseStillExact(current.prepared)
                ) return@commit GuardDbBootstrapExportBuild.AuthorityChanged
                val receipt = requireNotNull(bootstrapLeases).issue(
                    snapshot = current,
                    peer = peer,
                    captureId = request.captureId,
                    token = dependencies.freshToken(),
                    database = database,
                ) ?: return@commit GuardDbBootstrapExportBuild.AuthorityChanged
                GuardDbBootstrapExportBuild.Created(receipt)
            }
            val result = when (secured) {
                GuardDbMaintenanceSecurityResult.Changed ->
                    return@withLock call.respondJsonError(
                        HttpStatusCode.Conflict,
                        "security-authority-changed",
                    )
                GuardDbMaintenanceSecurityResult.Refused ->
                    return@withLock call.respondJsonError(
                        HttpStatusCode.PreconditionFailed,
                        "hardened-debug-off-proof-required",
                    )
                is GuardDbMaintenanceSecurityResult.Value -> secured.value
            }
            when (result) {
                GuardDbBootstrapExportBuild.AuthorityChanged -> call.respondJsonError(
                    HttpStatusCode.Conflict,
                    "bootstrap-authority-changed",
                )
                GuardDbBootstrapExportBuild.DatabaseMismatch -> call.respondJsonError(
                    HttpStatusCode.Conflict,
                    "baseline-database-mismatch",
                )
                GuardDbBootstrapExportBuild.TooLarge -> call.respondJsonError(
                    HttpStatusCode.PayloadTooLarge,
                    "baseline-database-too-large",
                )
                is GuardDbBootstrapExportBuild.Created -> call.respondText(
                    result.receipt.canonical(),
                    ContentType.Application.Json,
                )
            }
        }
    }

    private suspend fun serveBootstrapProof(
        call: ApplicationCall,
        dependencies: GuardDbBootstrapExportDependencies,
    ) {
        val expectedEpoch = sentinel.securityAuthorityEpoch
        val secured = security.commit(expectedEpoch) {
            requireNotNull(bootstrapLeases).proof(bootstrapCredentials(call)) { bound ->
                bootstrapLeaseStillExact(dependencies, bound, expectedEpoch)
            }
        }
        if (secured !is GuardDbMaintenanceSecurityResult.Value) {
            bootstrapLeases?.invalidate()
            return call.respondJsonError(HttpStatusCode.Forbidden, "bootstrap-export-unavailable")
        }
        val proof = secured.value
            ?: return call.respondJsonError(HttpStatusCode.Forbidden, "bootstrap-export-unavailable")
        if (security.readyEpoch() != expectedEpoch) {
            bootstrapLeases?.invalidate()
            return call.respondJsonError(HttpStatusCode.Forbidden, "bootstrap-export-unavailable")
        }
        call.respondBytes(proof, ContentType.Application.Json)
    }

    private suspend fun serveBootstrapDatabase(
        call: ApplicationCall,
        dependencies: GuardDbBootstrapExportDependencies,
    ) {
        val expectedEpoch = sentinel.securityAuthorityEpoch
        val secured = security.commit(expectedEpoch) {
            requireNotNull(bootstrapLeases).database(bootstrapCredentials(call)) { bound ->
                bootstrapLeaseStillExact(dependencies, bound, expectedEpoch)
            }
        }
        if (secured !is GuardDbMaintenanceSecurityResult.Value) {
            bootstrapLeases?.invalidate()
            return call.respondJsonError(HttpStatusCode.Forbidden, "bootstrap-export-unavailable")
        }
        val database = secured.value
            ?: return call.respondJsonError(HttpStatusCode.Forbidden, "bootstrap-export-unavailable")
        if (security.readyEpoch() != expectedEpoch) {
            bootstrapLeases?.invalidate()
            return call.respondJsonError(HttpStatusCode.Forbidden, "bootstrap-export-unavailable")
        }
        call.response.headers.append(GUARD_DB_BOOTSTRAP_CAPTURE_HEADER, database.captureId)
        call.response.headers.append(GUARD_DB_BOOTSTRAP_DATABASE_SHA256_HEADER, database.databaseSha256)
        call.response.headers.append(GUARD_DB_BOOTSTRAP_SESSION_HEADER, database.session)
        call.respondBytes(database.bytes, ContentType.Application.OctetStream)
    }

    private fun bootstrapLeaseStillExact(
        dependencies: GuardDbBootstrapExportDependencies,
        bound: GuardDbBootstrapExportSnapshot,
        expectedEpoch: Long,
    ): Boolean {
        return expectedEpoch == bound.security.epoch && dependencies.snapshot(expectedEpoch) == bound &&
            dependencies.databaseStillExact(bound.prepared)
    }

    private fun bootstrapCredentials(call: ApplicationCall) = GuardDbBootstrapExportCredentials(
        peer = call.request.origin.remoteAddress,
        session = call.request.headers[GUARD_DB_BOOTSTRAP_SESSION_HEADER],
        captureId = call.request.headers[GUARD_DB_BOOTSTRAP_CAPTURE_HEADER],
        token = call.request.headers[GUARD_DB_BOOTSTRAP_TOKEN_HEADER],
    )

    private suspend fun commitPreparedArm(call: ApplicationCall) {
        if (sentinel.state != io.panelassistant.android.util.GuardDbSentinelState.BASELINE_READY) {
            return call.respondJsonError(HttpStatusCode.Conflict, "clean-baseline-not-ready")
        }
        val body = try {
            BoundedStreams.readBytes(call.receiveStream(), MAX_ACTION_BODY_BYTES).toString(Charsets.UTF_8)
        } catch (_: ByteLimitExceeded) {
            return call.respondJsonError(HttpStatusCode.PayloadTooLarge, "body-too-large")
        }
        val request = parsePreparedCommit(body)
            ?: return call.respondJsonError(HttpStatusCode.BadRequest, "invalid-arm-commit")
        if (request.session != sentinel.session) {
            return call.respondJsonError(HttpStatusCode.Conflict, "session-mismatch")
        }
        if (request.generation != 0L) {
            return call.respondJsonError(HttpStatusCode.BadRequest, "arm-commit-generation-must-be-zero")
        }
        val prepared = (loadPrepared() as? GuardDbPreparedArmLoad.Valid)?.prepared
            ?: return call.respondJsonError(HttpStatusCode.Conflict, "prepared-proof-unavailable")
        val manifest = exactManifest(prepared)
            ?.takeIf { prepared.matches(sentinel) }
            ?: return call.respondJsonError(HttpStatusCode.Conflict, "prepared-proof-mismatch")
        if (!client.supported()) {
            return call.respondJsonError(HttpStatusCode.ServiceUnavailable, "autonomous-supervised-helper-required")
        }
        val empty = (client.statusProbe() as? GuardDbMaintenanceClient.StatusProbe.Valid)?.status
        if (empty?.phase != GuardDbMaintenanceProtocol.Phase.EMPTY || empty.generation != request.generation) {
            return call.respondJsonError(HttpStatusCode.Conflict, "helper-not-empty")
        }
        val securityEpoch = security.readyEpoch()
            ?: return call.respondJsonError(
                HttpStatusCode.PreconditionFailed,
                "hardened-debug-off-proof-required",
            )
        val payload = exactHttpApprovalPayload(call, sha256Hex(
            (body + "\u0000" + prepared.canonical() + "\u0000HELPER\u0000${empty.generation}\u0000${empty.phase}")
                .plus("\u0000SECURITY_EPOCH\u0000$securityEpoch")
                .toByteArray(Charsets.UTF_8),
        ))
        val (decision, id) = broker.request(
            SensitiveOperation.GUARD_DB_MAINTENANCE,
            call.request.origin.remoteAddress,
            payload,
            "Commit exact clean baseline ${prepared.databaseSha256.take(12)} to A/B custody",
        )
        if (decision != ApprovalBroker.Decision.APPROVED) {
            return call.respondText(
                "{\"ok\":false,\"error\":\"approval-required\",\"approval_id\":${Json.str(id)}}",
                ContentType.Application.Json,
                HttpStatusCode.Accepted,
            )
        }
        mutation.withLock {
            val currentPrepared = (loadPrepared() as? GuardDbPreparedArmLoad.Valid)?.prepared
            val currentManifest = currentPrepared?.let(exactManifest)
            val current = (client.statusProbe() as? GuardDbMaintenanceClient.StatusProbe.Valid)?.status
            if (currentPrepared != prepared || currentManifest != manifest || current?.phase != GuardDbMaintenanceProtocol.Phase.EMPTY ||
                current.generation != request.generation
            ) {
                return@withLock call.respondJsonError(HttpStatusCode.Conflict, "arm-commit-authority-changed")
            }
            // This acknowledged public receipt is not proof that helper custody already exists. Only
            // after its pipeline completes may CAPTURE force-stop this process and submit B. A fresh
            // HTTP request remains safe only when typed status later proves exact EMPTY generation zero;
            // its one-shot physical approval has already been consumed and cannot be reused.
            call.respondText(
                "{\"ok\":true,\"state\":\"submitting-custody\",\"session\":${Json.str(sentinel.session)}," +
                    "\"settlement\":\"poll-status\"," +
                    "\"retry_policy\":\"fresh-approval-after-exact-empty\"}",
                ContentType.Application.Json,
                HttpStatusCode.Accepted,
            )
            val secured = security.commit(securityEpoch) {
                val exactPrepared = (loadPrepared() as? GuardDbPreparedArmLoad.Valid)?.prepared
                val revalidatedManifest = exactPrepared?.let(exactManifest)
                val exactStatus = (client.statusProbe() as? GuardDbMaintenanceClient.StatusProbe.Valid)?.status
                if (exactPrepared != prepared || revalidatedManifest != manifest || exactStatus == null ||
                    exactStatus.phase != GuardDbMaintenanceProtocol.Phase.EMPTY ||
                    exactStatus.generation != request.generation
                ) null else GuardDbArmCoordinator.submitPrepared(client, manifest, prepared)
            }
            val result = when (secured) {
                GuardDbMaintenanceSecurityResult.Changed -> {
                    Log.e(TAG, "security authority changed after ARM receipt; helper remains untouched")
                    return@withLock
                }
                GuardDbMaintenanceSecurityResult.Refused -> {
                    Log.e(TAG, "Hardened/debug-off proof failed after ARM receipt; helper remains untouched")
                    return@withLock
                }
                is GuardDbMaintenanceSecurityResult.Value -> secured.value ?: run {
                    Log.e(TAG, "ARM authority changed after receipt; helper remains untouched")
                    return@withLock
                }
            }
            when (result) {
                is GuardDbArmTransferResult.Submitted -> {
                    if (!promoteArmed(sentinel.session)) {
                        Log.e(TAG, "helper custody submitted but ARMED sentinel promotion failed")
                    }
                    Log.i(TAG, "helper custody submitted generation=${result.generation} phase=${result.phase}")
                }
                is GuardDbArmTransferResult.Failed -> Log.e(TAG, "helper custody failed: ${result.result}")
                is GuardDbArmTransferResult.Indeterminate -> Log.e(TAG, "helper custody indeterminate: ${result.probe}")
                is GuardDbArmTransferResult.InvalidProof -> Log.e(TAG, "helper custody proof invalid: ${result.reason}")
            }
        }
    }

    private suspend fun submitExactARefusal(call: ApplicationCall) {
        val body = try {
            BoundedStreams.readBytes(call.receiveStream(), MAX_ACTION_BODY_BYTES).toString(Charsets.UTF_8)
        } catch (_: ByteLimitExceeded) {
            return call.respondJsonError(HttpStatusCode.PayloadTooLarge, "body-too-large")
        }
        val request = parsePreparedCommit(body)
            ?: return call.respondJsonError(HttpStatusCode.BadRequest, "invalid-refusal-request")
        if (request.session != sentinel.session) {
            return call.respondJsonError(HttpStatusCode.Conflict, "session-mismatch")
        }
        val prepared = (loadPrepared() as? GuardDbPreparedArmLoad.Valid)?.prepared
            ?.takeIf { it.matches(sentinel) }
            ?: return call.respondJsonError(HttpStatusCode.Conflict, "prepared-proof-unavailable")
        val status = (client.statusProbe() as? GuardDbMaintenanceClient.StatusProbe.Valid)?.status
            ?: return call.respondJsonError(HttpStatusCode.ServiceUnavailable, "status-unavailable")
        if (!refusalPending(status, request, sentinel)) {
            return call.respondJsonError(HttpStatusCode.Conflict, "refusal-not-pending")
        }
        val proof = withContext(Dispatchers.IO) {
            refusalProof(prepared)
        }
            ?: return call.respondJsonError(HttpStatusCode.Conflict, "exact-a-refusal-not-proven")
        val securityEpoch = security.readyEpoch()
            ?: return call.respondJsonError(
                HttpStatusCode.PreconditionFailed,
                "hardened-debug-off-proof-required",
            )
        val payload = exactHttpApprovalPayload(call, sha256Hex(
            (body + "\u0000" + proof.canonical() + "\u0000STATUS\u0000${status.generation}\u0000${status.phase}")
                .plus("\u0000SECURITY_EPOCH\u0000$securityEpoch")
                .toByteArray(Charsets.UTF_8),
        ))
        val (decision, id) = broker.request(
            SensitiveOperation.GUARD_DB_MAINTENANCE,
            call.request.origin.remoteAddress,
            payload,
            "Submit exact A-withheld refusal ${proof.aSha256.take(12)} while B remains installed",
        )
        if (decision != ApprovalBroker.Decision.APPROVED) {
            return call.respondText(
                "{\"ok\":false,\"error\":\"approval-required\",\"approval_id\":${Json.str(id)}}",
                ContentType.Application.Json,
                HttpStatusCode.Accepted,
            )
        }
        mutation.withLock {
            val current = (client.statusProbe() as? GuardDbMaintenanceClient.StatusProbe.Valid)?.status
            val repeated = withContext(Dispatchers.IO) {
                refusalProof(prepared)
            }
            val secured = security.commit(securityEpoch) {
                if (current == null || !refusalPending(current, request, sentinel) || repeated != proof) {
                    null
                } else {
                    settleGuardDbRefusal(
                        client,
                        sentinel,
                        request,
                        client.refusal(current, proof.aSha256, proof.aVersionCode),
                    )
                }
            }
            val settlement = when (secured) {
                GuardDbMaintenanceSecurityResult.Changed ->
                    return@withLock call.respondJsonError(HttpStatusCode.Conflict, "security-authority-changed")
                GuardDbMaintenanceSecurityResult.Refused ->
                    return@withLock call.respondJsonError(
                        HttpStatusCode.PreconditionFailed,
                        "hardened-debug-off-proof-required",
                    )
                is GuardDbMaintenanceSecurityResult.Value -> secured.value
                    ?: return@withLock call.respondJsonError(
                        HttpStatusCode.Conflict,
                        "refusal-authority-changed",
                    )
            }
            respondMutation(call, settlement)
        }
    }

    private suspend fun cancelBeforeCustody(call: ApplicationCall) {
        val body = try {
            BoundedStreams.readBytes(call.receiveStream(), MAX_ACTION_BODY_BYTES).toString(Charsets.UTF_8)
        } catch (_: ByteLimitExceeded) {
            return call.respondJsonError(HttpStatusCode.PayloadTooLarge, "body-too-large")
        }
        val request = parsePreparedCommit(body)
            ?: return call.respondJsonError(HttpStatusCode.BadRequest, "invalid-cancel-request")
        if (request.session != sentinel.session) {
            return call.respondJsonError(HttpStatusCode.Conflict, "session-mismatch")
        }
        val status = (client.statusProbe() as? GuardDbMaintenanceClient.StatusProbe.Valid)?.status
            ?: return call.respondJsonError(HttpStatusCode.ServiceUnavailable, "status-unavailable")
        if (!guardDbCancellationAllowed(status, request, sentinel)) {
            return call.respondJsonError(HttpStatusCode.Conflict, "custody-cannot-be-cancelled")
        }
        val prepared = (loadPrepared() as? GuardDbPreparedArmLoad.Valid)?.prepared
            ?.takeIf { it.matches(sentinel) }
            ?: return call.respondJsonError(HttpStatusCode.Conflict, "prepared-proof-unavailable")
        val securityEpoch = security.readyEpoch()
            ?: return call.respondJsonError(
                HttpStatusCode.PreconditionFailed,
                "hardened-debug-off-proof-required",
            )
        val payload = exactHttpApprovalPayload(call, sha256Hex(
            (body + "\u0000" + prepared.canonical() + "\u0000STATUS\u0000${status.generation}\u0000${status.phase}")
                .plus("\u0000SECURITY_EPOCH\u0000$securityEpoch")
                .toByteArray(Charsets.UTF_8),
        ))
        val (decision, id) = broker.request(
            SensitiveOperation.GUARD_DB_MAINTENANCE,
            call.request.origin.remoteAddress,
            payload,
            "Cancel Guard DB before package/database custody generation ${status.generation}",
        )
        if (decision != ApprovalBroker.Decision.APPROVED) {
            return call.respondText(
                "{\"ok\":false,\"error\":\"approval-required\",\"approval_id\":${Json.str(id)}}",
                ContentType.Application.Json,
                HttpStatusCode.Accepted,
            )
        }
        mutation.withLock {
            val secured = security.commit(securityEpoch) {
                val current = (client.statusProbe() as? GuardDbMaintenanceClient.StatusProbe.Valid)?.status
                if (current == null || current != status ||
                    (loadPrepared() as? GuardDbPreparedArmLoad.Valid)?.prepared != prepared
                ) return@commit null
                if (current.phase != GuardDbMaintenanceProtocol.Phase.EMPTY) {
                    when (val result = client.cancel(sentinel.session, current.generation)) {
                        is GuardDbMaintenanceProtocol.Result.Rejected ->
                            return@commit GuardDbCancelResult.Mutation(GuardDbMutationSettlement.Rejected(result))
                        GuardDbMaintenanceProtocol.Result.Unreachable ->
                            return@commit GuardDbCancelResult.Mutation(GuardDbMutationSettlement.NotSubmitted)
                        else -> if ((client.statusProbe()
                                as? GuardDbMaintenanceClient.StatusProbe.Valid)?.status?.phase !=
                            GuardDbMaintenanceProtocol.Phase.EMPTY
                        ) return@commit GuardDbCancelResult.Indeterminate
                    }
                }
                if (!preparedStore.clear(sentinel.session) || !sentinelStore.clear(sentinel.session)) {
                    GuardDbCancelResult.LocalHold
                } else GuardDbCancelResult.Complete
            }
            val result = when (secured) {
                GuardDbMaintenanceSecurityResult.Changed ->
                    return@withLock call.respondJsonError(HttpStatusCode.Conflict, "security-authority-changed")
                GuardDbMaintenanceSecurityResult.Refused ->
                    return@withLock call.respondJsonError(
                        HttpStatusCode.PreconditionFailed,
                        "hardened-debug-off-proof-required",
                    )
                is GuardDbMaintenanceSecurityResult.Value -> secured.value
                    ?: return@withLock call.respondJsonError(
                        HttpStatusCode.Conflict,
                        "cancel-authority-changed",
                    )
            }
            when (result) {
                GuardDbCancelResult.Complete -> Unit
                GuardDbCancelResult.LocalHold -> return@withLock call.respondJsonError(
                    HttpStatusCode.Locked, "cancelled-helper-empty-local-hold",
                )
                GuardDbCancelResult.Indeterminate -> return@withLock call.respondJsonError(
                    HttpStatusCode.Accepted, "cancel-indeterminate-poll-status",
                )
                is GuardDbCancelResult.Mutation -> return@withLock respondMutation(call, result.settlement)
            }
            call.respondText(
                "{\"ok\":true,\"phase\":\"EMPTY\",\"outcome\":\"CANCELLED_NO_MUTATION\"}",
                ContentType.Application.Json,
            )
            onFinalized()
        }
    }

    private suspend fun respondMutation(call: ApplicationCall, result: GuardDbMutationSettlement) {
        when (result) {
            is GuardDbMutationSettlement.Settled -> call.respondText(
                "{\"ok\":true,\"generation\":${result.status.generation}," +
                    "\"phase\":${Json.str(result.status.phase.name)}," +
                    "\"outcome\":${Json.str(result.status.outcome?.name.orEmpty())}}",
                ContentType.Application.Json,
            )
            is GuardDbMutationSettlement.Rejected -> call.respondText(
                "{\"ok\":false,\"error\":\"helper-rejected\",\"code\":${Json.str(result.result.code)}," +
                    "\"detail\":${Json.str(result.result.token)}}",
                ContentType.Application.Json,
                HttpStatusCode.Conflict,
            )
            is GuardDbMutationSettlement.Held -> call.respondJsonError(
                HttpStatusCode.Accepted,
                "indeterminate-poll-status",
            )
            GuardDbMutationSettlement.NotSubmitted -> call.respondJsonError(
                HttpStatusCode.ServiceUnavailable,
                "helper-unreachable-not-submitted",
            )
        }
    }

    private companion object {
        const val TAG = "ha-paneld/guard-db-http"
        const val MAX_ACTION_BODY_BYTES = 4096L
    }
}

internal sealed interface GuardDbMaintenanceSecurityResult<out T> {
    data object Changed : GuardDbMaintenanceSecurityResult<Nothing>
    data object Refused : GuardDbMaintenanceSecurityResult<Nothing>
    data class Value<T>(val value: T) : GuardDbMaintenanceSecurityResult<T>
}

internal interface GuardDbMaintenanceSecurityAuthority {
    /** Atomic Hardened + durable-debug-off proof and the epoch it proved. */
    fun readyEpoch(): Long?

    /** Re-prove the same authority at the exact mutation boundary; no suspend is allowed inside. */
    fun <T> commit(expectedEpoch: Long, action: () -> T): GuardDbMaintenanceSecurityResult<T>
}

private data class GuardDbActionRequest(
    val session: String,
    val generation: Long,
    val action: GuardDbMaintenanceProtocol.Action,
) {
    fun matches(status: GuardDbMaintenanceProtocol.Status): Boolean =
        session == status.session && generation == status.generation
}

internal sealed interface GuardDbMutationSettlement {
    data class Settled(val status: GuardDbMaintenanceProtocol.Status) : GuardDbMutationSettlement
    data class Rejected(val result: GuardDbMaintenanceProtocol.Result.Rejected) : GuardDbMutationSettlement
    data class Held(val probe: GuardDbMaintenanceClient.StatusProbe) : GuardDbMutationSettlement
    data object NotSubmitted : GuardDbMutationSettlement
}

private sealed interface GuardDbCancelResult {
    data object Complete : GuardDbCancelResult
    data object LocalHold : GuardDbCancelResult
    data object Indeterminate : GuardDbCancelResult
    data class Mutation(val settlement: GuardDbMutationSettlement) : GuardDbCancelResult
}

private fun settleGuardDbAction(
    client: GuardDbMaintenanceClient,
    sentinel: GuardDbStartupSentinel,
    request: GuardDbActionRequest,
): GuardDbMutationSettlement {
    val result = client.action(request.session, request.generation, request.action)
    if (result is GuardDbMaintenanceProtocol.Result.Rejected) return GuardDbMutationSettlement.Rejected(result)
    if (result == GuardDbMaintenanceProtocol.Result.Unreachable) return GuardDbMutationSettlement.NotSubmitted
    val probe = client.statusProbe()
    val status = (probe as? GuardDbMaintenanceClient.StatusProbe.Valid)?.status
    if (status != null && status.session == sentinel.session && status.bootNonce == sentinel.bootNonce &&
        status.generation > request.generation && status.phase in guardDbActionSettlementPhases(request.action) &&
        statusMatchesSentinelIdentity(status, sentinel)
    ) return GuardDbMutationSettlement.Settled(status)
    return GuardDbMutationSettlement.Held(probe)
}

private fun statusMatchesSentinelIdentity(
    status: GuardDbMaintenanceProtocol.Status,
    sentinel: GuardDbStartupSentinel,
): Boolean = when (status.role) {
    GuardDbMaintenanceProtocol.Role.A -> status.apkSha256 == sentinel.aSha256 &&
        status.versionCode == sentinel.aVersionCode && status.schema == sentinel.aSchema
    GuardDbMaintenanceProtocol.Role.B -> status.apkSha256 == sentinel.bSha256 &&
        status.versionCode == sentinel.bVersionCode && status.schema == sentinel.bSchema
    null -> status.phase in setOf(
        GuardDbMaintenanceProtocol.Phase.STAGING,
        GuardDbMaintenanceProtocol.Phase.PREPARED,
        GuardDbMaintenanceProtocol.Phase.SUBMITTED_A,
        GuardDbMaintenanceProtocol.Phase.SUBMITTED_B,
        GuardDbMaintenanceProtocol.Phase.ROLLBACK_REQUIRED,
        GuardDbMaintenanceProtocol.Phase.ROLLBACK_A_SUBMITTED,
        GuardDbMaintenanceProtocol.Phase.AMBIGUOUS,
    )
}

internal fun guardDbActionSettlementPhases(
    action: GuardDbMaintenanceProtocol.Action,
): Set<GuardDbMaintenanceProtocol.Phase> = when (action) {
    GuardDbMaintenanceProtocol.Action.WITHHOLD_PREMIGRATE -> setOf(
        GuardDbMaintenanceProtocol.Phase.WAIT_A_REFUSAL,
        GuardDbMaintenanceProtocol.Phase.ROLLBACK_REQUIRED,
    )
    GuardDbMaintenanceProtocol.Action.RESTORE_PREMIGRATE -> setOf(
        GuardDbMaintenanceProtocol.Phase.RECOVERY_RESTORED,
        GuardDbMaintenanceProtocol.Phase.SUBMITTED_A,
        GuardDbMaintenanceProtocol.Phase.WAIT_A_HEALTH,
        GuardDbMaintenanceProtocol.Phase.ROLLBACK_REQUIRED,
        GuardDbMaintenanceProtocol.Phase.ROLLBACK_A_SUBMITTED,
        GuardDbMaintenanceProtocol.Phase.ROLLBACK_DB_PREPARED,
        GuardDbMaintenanceProtocol.Phase.ROLLBACK_DB_RESTORED,
    )
    // Rollback is owned and initiated by the native supervisor. The maintenance HTTP successor may
    // observe its phases through status, but must never claim or reconcile it as a public action.
    GuardDbMaintenanceProtocol.Action.ROLLBACK -> emptySet()
    GuardDbMaintenanceProtocol.Action.FINALIZE -> setOf(GuardDbMaintenanceProtocol.Phase.FINALIZED)
    else -> emptySet()
}

private data class GuardDbPreparedCommitRequest(val session: String, val generation: Long)

private data class GuardDbBootstrapExportRequest(val session: String, val captureId: String)

private sealed interface GuardDbBootstrapExportBuild {
    data object AuthorityChanged : GuardDbBootstrapExportBuild
    data object DatabaseMismatch : GuardDbBootstrapExportBuild
    data object TooLarge : GuardDbBootstrapExportBuild
    data class Created(val receipt: GuardDbBootstrapExportReceipt) : GuardDbBootstrapExportBuild
}

private fun GuardDbExactARefusalProof.canonical(): String = listOf(
    aSha256, aVersionCode, installedBSha256, installedBVersionCode, databaseInventorySha256,
).joinToString("\u0000")

private fun GuardDbMaintenanceProtocol.Status.canonical(): String = listOf(
    generation, phase.name, session ?: "NONE", bootNonce ?: "NONE", role?.name ?: "NONE",
    apkSha256 ?: "NONE", versionCode ?: 0L, schema ?: 0, baselineAppStateCount, error ?: "NONE",
    outcome?.name ?: "NONE", overallDeadlineElapsedMs, forwardDeadlineElapsedMs,
).joinToString("\u0000")

private fun refusalPending(
    status: GuardDbMaintenanceProtocol.Status,
    request: GuardDbPreparedCommitRequest,
    sentinel: GuardDbStartupSentinel,
): Boolean = status.session == sentinel.session && status.bootNonce == sentinel.bootNonce &&
    status.generation == request.generation && status.role == GuardDbMaintenanceProtocol.Role.B &&
    status.apkSha256 == sentinel.bSha256 && status.versionCode == sentinel.bVersionCode &&
    status.schema == sentinel.bSchema && status.phase == GuardDbMaintenanceProtocol.Phase.WAIT_A_REFUSAL

private fun guardDbCancellationAllowed(
    status: GuardDbMaintenanceProtocol.Status,
    request: GuardDbPreparedCommitRequest,
    sentinel: GuardDbStartupSentinel,
): Boolean {
    if (status.generation != request.generation) return false
    if (status.phase == GuardDbMaintenanceProtocol.Phase.EMPTY) {
        return request.generation == 0L && sentinel.state ==
            io.panelassistant.android.util.GuardDbSentinelState.BASELINE_READY
    }
    if (status.session != sentinel.session || status.bootNonce != sentinel.bootNonce) return false
    return when (status.phase) {
        GuardDbMaintenanceProtocol.Phase.STAGING -> status.error == null || status.error == "FAILED_NO_MUTATION"
        GuardDbMaintenanceProtocol.Phase.PREPARED -> status.error == null
        else -> false
    }
}

private fun settleGuardDbRefusal(
    client: GuardDbMaintenanceClient,
    sentinel: GuardDbStartupSentinel,
    request: GuardDbPreparedCommitRequest,
    result: GuardDbMaintenanceProtocol.Result,
): GuardDbMutationSettlement {
    if (result is GuardDbMaintenanceProtocol.Result.Rejected) return GuardDbMutationSettlement.Rejected(result)
    if (result == GuardDbMaintenanceProtocol.Result.Unreachable) return GuardDbMutationSettlement.NotSubmitted
    val probe = client.statusProbe()
    val status = (probe as? GuardDbMaintenanceClient.StatusProbe.Valid)?.status
    if (status != null && status.session == sentinel.session && status.bootNonce == sentinel.bootNonce &&
        status.generation > request.generation && status.phase == GuardDbMaintenanceProtocol.Phase.A_REFUSED &&
        status.role == GuardDbMaintenanceProtocol.Role.B && status.apkSha256 == sentinel.bSha256 &&
        status.versionCode == sentinel.bVersionCode && status.schema == sentinel.bSchema
    ) return GuardDbMutationSettlement.Settled(status)
    return GuardDbMutationSettlement.Held(probe)
}

private fun parsePreparedCommit(body: String): GuardDbPreparedCommitRequest? = runCatching {
    val json = JSONObject(body)
    if (json.keys().asSequence().toSet() != setOf("session", "generation")) return null
    val session = json.getString("session")
    val generation = json.getLong("generation")
    if (!GuardDbMaintenanceProtocol.validSession(session) || generation < 0L) return null
    GuardDbPreparedCommitRequest(session, generation)
}.getOrNull()

private fun parseBootstrapExport(body: String): GuardDbBootstrapExportRequest? = runCatching {
    BOOTSTRAP_EXPORT_SESSION_FIRST.matchEntire(body)?.let {
        return GuardDbBootstrapExportRequest(requireNotNull(it.groups[1]).value, requireNotNull(it.groups[2]).value)
    }
    BOOTSTRAP_EXPORT_CAPTURE_FIRST.matchEntire(body)?.let {
        return GuardDbBootstrapExportRequest(requireNotNull(it.groups[2]).value, requireNotNull(it.groups[1]).value)
    }
    null
}.getOrNull()

private const val JSON_ASCII_SPACE = "[ \\t\\r\\n]*"
private const val JSON_HEX_64 = "[0-9a-f]{64}"
private val BOOTSTRAP_EXPORT_SESSION_FIRST = Regex(
    "^$JSON_ASCII_SPACE\\{$JSON_ASCII_SPACE\"session\"$JSON_ASCII_SPACE:$JSON_ASCII_SPACE" +
        "\"($JSON_HEX_64)\"$JSON_ASCII_SPACE,$JSON_ASCII_SPACE" +
        "\"capture_id\"$JSON_ASCII_SPACE:$JSON_ASCII_SPACE\"($JSON_HEX_64)\"" +
        "$JSON_ASCII_SPACE\\}$JSON_ASCII_SPACE$",
)
private val BOOTSTRAP_EXPORT_CAPTURE_FIRST = Regex(
    "^$JSON_ASCII_SPACE\\{$JSON_ASCII_SPACE\"capture_id\"$JSON_ASCII_SPACE:$JSON_ASCII_SPACE" +
        "\"($JSON_HEX_64)\"$JSON_ASCII_SPACE,$JSON_ASCII_SPACE" +
        "\"session\"$JSON_ASCII_SPACE:$JSON_ASCII_SPACE\"($JSON_HEX_64)\"" +
        "$JSON_ASCII_SPACE\\}$JSON_ASCII_SPACE$",
)

private fun parseAction(body: String): GuardDbActionRequest? = runCatching {
    val json = JSONObject(body)
    if (json.keys().asSequence().toSet() != setOf("session", "generation", "action")) return null
    val session = json.getString("session")
    val generation = json.getLong("generation")
    val action = parsePublicGuardDbAction(json.getString("action")) ?: return null
    if (!GuardDbMaintenanceProtocol.validSession(session) || generation < 0L) return null
    GuardDbActionRequest(session, generation, action)
}.getOrNull()

internal fun parsePublicGuardDbAction(value: String): GuardDbMaintenanceProtocol.Action? = when (value) {
    GuardDbMaintenanceProtocol.Action.WITHHOLD_PREMIGRATE.name ->
        GuardDbMaintenanceProtocol.Action.WITHHOLD_PREMIGRATE
    GuardDbMaintenanceProtocol.Action.RESTORE_PREMIGRATE.name ->
        GuardDbMaintenanceProtocol.Action.RESTORE_PREMIGRATE
    GuardDbMaintenanceProtocol.Action.FINALIZE.name -> GuardDbMaintenanceProtocol.Action.FINALIZE
    else -> null
}

internal fun guardDbActionAllowed(
    phase: GuardDbMaintenanceProtocol.Phase,
    action: GuardDbMaintenanceProtocol.Action,
): Boolean = when (phase) {
    GuardDbMaintenanceProtocol.Phase.B_HEALTHY -> action == GuardDbMaintenanceProtocol.Action.WITHHOLD_PREMIGRATE
    GuardDbMaintenanceProtocol.Phase.A_REFUSED -> action == GuardDbMaintenanceProtocol.Action.RESTORE_PREMIGRATE
    GuardDbMaintenanceProtocol.Phase.A_HEALTHY -> action == GuardDbMaintenanceProtocol.Action.FINALIZE
    // Observe-only: the native supervisor consumes ROLLBACK_REQUIRED without an HTTP action.
    GuardDbMaintenanceProtocol.Phase.ROLLBACK_REQUIRED -> false
    else -> false
}

private suspend fun respondStatus(call: ApplicationCall, probe: GuardDbMaintenanceClient.StatusProbe) {
    when (probe) {
        is GuardDbMaintenanceClient.StatusProbe.Valid -> with(probe.status) {
            call.respondText(
                "{\"ok\":true,\"generation\":$generation,\"phase\":${Json.str(phase.name)}," +
                    "\"session\":${Json.str(session.orEmpty())},\"role\":${Json.str(role?.name.orEmpty())}," +
                    "\"version_code\":${versionCode ?: 0},\"schema\":${schema ?: 0}," +
                    "\"error\":${Json.str(error.orEmpty())}," +
                    "\"outcome\":${Json.str(outcome?.name.orEmpty())}," +
                    "\"overall_deadline_elapsed_ms\":$overallDeadlineElapsedMs," +
                    "\"forward_deadline_elapsed_ms\":$forwardDeadlineElapsedMs," +
                    "\"same_boot_only\":true}",
                ContentType.Application.Json,
            )
        }
        GuardDbMaintenanceClient.StatusProbe.Unreachable ->
            call.respondJsonError(HttpStatusCode.ServiceUnavailable, "helper-unreachable")
        GuardDbMaintenanceClient.StatusProbe.Unsupported ->
            call.respondJsonError(HttpStatusCode.ServiceUnavailable, "helper-unsupported")
        GuardDbMaintenanceClient.StatusProbe.Malformed ->
            call.respondJsonError(HttpStatusCode.BadGateway, "helper-status-malformed")
    }
}

private suspend fun ApplicationCall.respondJsonError(status: HttpStatusCode, error: String) = respondText(
    "{\"ok\":false,\"error\":${Json.str(error)}}",
    ContentType.Application.Json,
    status,
)
