package io.github.maxlyth.hapaneld.http

import io.github.maxlyth.hapaneld.util.AppInstaller
import io.github.maxlyth.hapaneld.util.BoundedStreams
import io.github.maxlyth.hapaneld.util.ByteLimitExceeded
import io.github.maxlyth.hapaneld.util.DownloadAbort
import io.github.maxlyth.hapaneld.util.InstallProgress
import io.github.maxlyth.hapaneld.util.InstallPresentation
import io.github.maxlyth.hapaneld.util.Json
import io.github.maxlyth.hapaneld.util.MonotonicDeadline
import io.github.maxlyth.hapaneld.util.ReleaseCatalog
import io.github.maxlyth.hapaneld.util.StreamDeadline
import io.github.maxlyth.hapaneld.util.UpdateChecker
import io.github.maxlyth.hapaneld.backup.PanelBackup
import io.github.maxlyth.hapaneld.security.SensitiveOperation
import io.github.maxlyth.hapaneld.migration.IdentityMigrationSurface
import io.github.maxlyth.hapaneld.migration.SuccessorUploadCapability
import io.github.maxlyth.hapaneld.migration.SuccessorHandoff
import io.github.maxlyth.hapaneld.migration.InstalledSuccessorStatus
import io.github.maxlyth.hapaneld.util.InstallOutcome
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.request.receiveStream
import io.ktor.server.response.respondOutputStream
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.URI
import java.util.concurrent.atomic.AtomicBoolean

internal class CompanionBackupUnavailable(
    val reason: String,
    val presentation: InstallPresentation? = null,
) : Exception(reason)

/**
 * What the caller said about the HA Companion login, kept distinct from what the panel will do about it.
 *
 * The distinction that matters is [OMITTED] versus [REQUIRED]. A caller who never mentioned the Companion
 * asked for "this panel's backup", and a panel without the app installed has one of those; a caller who
 * typed `include_companion=true` asked for the login specifically and has to be told when it cannot be
 * captured. Collapsing the two into one Boolean defaulted to true is what made the simplest possible
 * backup request fail on every panel with no Companion registration.
 */
internal enum class CompanionBackupRequest { OMITTED, EXCLUDED, REQUIRED }

/**
 * `true`/`1` and `false`/`0` are the only values the browser and the documented API send. Anything else is
 * a caller who meant something that cannot be inferred, so this returns null and the route refuses: reading
 * an unrecognised value as "exclude" is how `include_companion=yes` used to hand back a config-only archive
 * while the caller believed the login was inside it.
 */
internal fun parseCompanionBackupRequest(raw: String?): CompanionBackupRequest? = when (raw) {
    null -> CompanionBackupRequest.OMITTED
    "true", "1" -> CompanionBackupRequest.REQUIRED
    "false", "0" -> CompanionBackupRequest.EXCLUDED
    else -> null
}

internal data class ControlPlaneRouteDependencies(
    val playAudio: (String) -> Boolean,
    val installComponent: (String, String, String) -> Boolean,
    val installedComponentVersion: (String) -> String?,
    val buildBackup: suspend (request: CompanionBackupRequest, passphrase: String) -> PanelBackup.Artifact,
    val backupFileStem: () -> String,
    val apkUpload: ApkUploadRouteDependencies,
    val identityMigration: IdentityMigrationSurface = IdentityMigrationSurface.NONE,
    val authorize: suspend (ApplicationCall, SensitiveOperation, String, String) -> Boolean = { _, _, _, _ -> true },
)

internal data class ApkUploadRouteDependencies(
    val enabled: () -> Boolean,
    val rootAvailable: () -> Boolean,
    val pending: PendingUploadStore,
    val createStagingFile: () -> File,
    val inspect: suspend (File) -> UploadedApkIdentity?,
    val startInstall: (PendingUploadStore.Entry, InstallProgress.Ticket) -> Unit,
    val maxBytes: Long = PaneldServer.MAX_APK_UPLOAD_BYTES,
    val usableSpace: (File) -> Long = { staged -> staged.parentFile?.usableSpace ?: 0L },
    val receiveBody: (InputStream, OutputStream, Long) -> Long = { input, output, maxBytes ->
        DeadlineBoundedBody.copy(input, output, maxBytes, APK_UPLOAD_RECEIPT_DEADLINE_MS)
    },
    /** Fetch [url] into a staging file. */
    val fetch: (String, File, Long, DownloadAbort) -> AppInstaller.DownloadResult = { url, dest, maxBytes, abort ->
        AppInstaller.download(url, dest, maxBytes, abort)
    },
)

/**
 * Accepts the client-minted identifier that owns one fetch, or null to refuse it.
 *
 * Bounded and restricted to characters that cannot disturb a log line or a form round-trip. The value
 * carries no authority of its own: it decides only which in-flight download a cancel may stop.
 */
internal fun validApkFetchRequestId(raw: String): String? {
    val trimmed = raw.trim()
    if (trimmed.length !in 1..APK_FETCH_REQUEST_ID_MAX_CHARS) return null
    if (!trimmed.all { it.isLetterOrDigit() || it == '-' || it == '_' }) return null
    return trimmed
}

/**
 * Accepts an operator-supplied APK URL, or null to refuse it before any lease or network work happens.
 *
 * HTTPS-only, inherited unchanged from [AppInstaller.download] rather than widened, so there is one
 * scheme rule for every APK the panel fetches. The bound on length keeps an absurd string out of the
 * staging and logging paths.
 *
 * Redirects are followed normally, and there is deliberately **no** outbound host or address filter.
 * That is a documented proportionality decision, recorded here so it reads as a choice rather
 * than an oversight. Reaching this endpoint at all means being a trusted LAN actor, and such an actor
 * can already upload an arbitrary APK for privileged install and scan the network from their own
 * machine — so having the panel issue the request instead grants them a different source address and
 * little else. Filtering destinations would break LAN-hosted mirrors, and could not be made sound
 * without pinning the connection to the vetted address, which is disproportionate for a convenience
 * feature on a home control panel.
 *
 * What IS enforced, because it is cheap: [SensitiveOperation.APK_FETCH] must be approved for the exact
 * URL before any socket opens, so Hardened mode's approval remains the boundary it is meant to be.
 * The residual accepted here is that an approved fetch may be redirected to a host the operator did
 * not name; the bytes are still inspected and never returned to the caller, and installing them needs
 * its own separate approval.
 */
internal fun validApkFetchUrl(raw: String, maxChars: Int = APK_FETCH_URL_MAX_CHARS): String? {
    val trimmed = raw.trim()
    if (trimmed.isEmpty() || trimmed.length > maxChars) return null
    val parsed = runCatching { URI(trimmed) }.getOrNull() ?: return null
    // An `https` scheme is what makes the reference absolute, so there is no separate absoluteness test.
    if (!parsed.scheme.equals("https", true)) return null
    if (parsed.host.isNullOrBlank()) return null
    return trimmed
}

internal data class UploadedApkIdentity(
    val pkg: String,
    val version: String,
    val signerSha256: String?,
    val signerSha256s: Set<String> = setOfNotNull(signerSha256),
    val versionCode: Long = 0L,
)

private val SUCCESSOR_DIGEST = Regex("[0-9a-f]{64}")

internal fun successorCandidateMatches(identity: UploadedApkIdentity?, capability: SuccessorUploadCapability): Boolean =
    identity?.pkg == capability.pkg && identity.version == capability.version && identity.versionCode == capability.versionCode &&
        identity.signerSha256s.size == 1 &&
        identity.signerSha256s.single().equals(capability.signer, ignoreCase = true)

/** Consumes the ordinary upload claim, with migration preconditions immediately before installation. */
internal suspend fun installUploadedApk(
    entry: PendingUploadStore.Entry,
    migration: IdentityMigrationSurface,
    install: suspend (File) -> InstallOutcome,
): String = try {
    val digest = entry.migrationSha256
    val capability = if (digest != null) migration.successorUploadCapability() else null
    val installed = if (capability != null) migration.installedSuccessorStatus() else InstalledSuccessorStatus.Absent
    if (digest != null && (capability == null || !successorCandidateMatches(entry.identity, capability))) {
        "migration unavailable"
    } else if (digest != null && AppInstaller.sha256(entry.file) != digest) {
        "migration hash mismatch"
    } else if (installed == InstalledSuccessorStatus.Untrusted) {
        "untrusted successor installed"
    } else if (capability != null && (installed as? InstalledSuccessorStatus.Trusted)
            ?.versionCode?.let { it >= capability.versionCode } == true) {
        installedOnlyHandoff(migration)
    } else when (val outcome = install(entry.file)) {
        InstallOutcome.Succeeded -> if (capability == null) "OK" else {
            if (migration.installedSuccessorStatus() != InstalledSuccessorStatus.Trusted(capability.versionCode)) {
                "installed successor was not confirmed"
            } else installedOnlyHandoff(migration)
        }
        is InstallOutcome.Failure -> outcome.message
    }
} finally {
    entry.file.delete()
}

private suspend fun installedOnlyHandoff(migration: IdentityMigrationSurface): String =
    when (val handoff = migration.offerInstalledOnly()) {
        SuccessorHandoff.Outcome.Launched -> "OK"
        null -> "installed-only handover unavailable"
        else -> "installed-only handover: ${handoff.detail}"
    }

/** Builds the physical-approval text from untrusted APK metadata. Package and signer identity are
 * individually bounded and shown before the version field, so version metadata cannot hide them. */
internal fun uploadedApkApprovalSummary(identity: UploadedApkIdentity): String {
    val pkg = approvalDisplayField(identity.pkg, APK_APPROVAL_PACKAGE_CHARS)
    val signer = identity.signerSha256
        ?.let { approvalDisplayField(it, APK_APPROVAL_SIGNER_CHARS) }
        ?: "unsigned"
    val version = approvalDisplayField(identity.version, APK_APPROVAL_VERSION_CHARS)
    return "Install $pkg (signer $signer) version $version"
}

/** Removes non-rendering controls and direction-changing format characters before text reaches the
 * local approval dialog. The character bound never splits a supplementary Unicode character. */
private fun approvalDisplayField(value: String, maxChars: Int): String {
    val result = StringBuilder(minOf(value.length, maxChars))
    var offset = 0
    var acceptedChars = 0
    while (offset < value.length) {
        val codePoint = value.codePointAt(offset)
        val width = Character.charCount(codePoint)
        offset += width
        val type = Character.getType(codePoint)
        if (type == Character.CONTROL.toInt() ||
            type == Character.FORMAT.toInt() ||
            type == Character.LINE_SEPARATOR.toInt() ||
            type == Character.PARAGRAPH_SEPARATOR.toInt() ||
            type == Character.SURROGATE.toInt()
        ) continue
        if (acceptedChars + width > maxChars) break
        result.appendCodePoint(codePoint)
        acceptedChars += width
    }
    return result.toString().trim().ifEmpty { "?" }
}

internal class BodyReceiptTimeout : IOException("request body receipt deadline exceeded")

internal sealed interface BoundedBodyReceipt {
    data class Received(val bytes: ByteArray) : BoundedBodyReceipt
    data object TooLarge : BoundedBodyReceipt
    data object TimedOut : BoundedBodyReceipt
}

/** Applies a whole-body deadline as well as a byte limit. The timer closes a transport that is blocked
 * inside read(), while the wrapper checks elapsed time before and after each successful transport read. */
internal object DeadlineBoundedBody {
    fun copy(
        input: InputStream,
        output: OutputStream,
        maxBytes: Long,
        timeoutMs: Long,
        nanoTime: () -> Long = System::nanoTime,
        deadlineFactory: (Long, () -> Unit) -> AutoCloseable = { timeout, onTimeout ->
            StreamDeadline(timeout, onTimeout)
        },
    ): Long {
        require(timeoutMs in 1..(Long.MAX_VALUE / 1_000_000L))
        val timedOut = AtomicBoolean(false)
        val budget = MonotonicDeadline(timeoutMs, nanoTime)
        val bounded = DeadlineInputStream(input, budget)
        val deadline = deadlineFactory(timeoutMs) {
            timedOut.set(true)
            runCatching { input.close() }
        }
        return try {
            val copied = BoundedStreams.copy(bounded, output, maxBytes)
            if (timedOut.get() || budget.remainingMs() <= 0L) throw BodyReceiptTimeout()
            copied
        } catch (error: ByteLimitExceeded) {
            throw error
        } catch (error: BodyReceiptTimeout) {
            throw error
        } catch (error: Exception) {
            if (timedOut.get() || budget.remainingMs() <= 0L) throw BodyReceiptTimeout()
            throw error
        } finally {
            deadline.close()
        }
    }

    fun readBytes(
        input: InputStream,
        maxBytes: Long,
        timeoutMs: Long,
        nanoTime: () -> Long = System::nanoTime,
        deadlineFactory: (Long, () -> Unit) -> AutoCloseable = { timeout, onTimeout ->
            StreamDeadline(timeout, onTimeout)
        },
    ): ByteArray {
        val output = ByteArrayOutputStream(minOf(maxBytes, DEFAULT_BUFFER_SIZE.toLong()).toInt())
        copy(input, output, maxBytes, timeoutMs, nanoTime, deadlineFactory)
        return output.toByteArray()
    }

    private class DeadlineInputStream(
        source: InputStream,
        private val budget: MonotonicDeadline,
    ) : FilterInputStream(source) {
        override fun read(): Int {
            checkDeadline()
            return super.read().also { checkDeadline() }
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            checkDeadline()
            return super.read(buffer, offset, length).also { checkDeadline() }
        }

        private fun checkDeadline() {
            if (budget.remainingMs() <= 0L) throw BodyReceiptTimeout()
        }
    }
}

/** Shared bounded body admission for every materialized request body on the LAN control plane. */
internal suspend fun receiveBoundedBody(
    call: ApplicationCall,
    maxBytes: Long,
    timeoutMs: Long = STANDARD_BODY_RECEIPT_DEADLINE_MS,
    reader: (InputStream, Long, Long) -> ByteArray = DeadlineBoundedBody::readBytes,
): BoundedBodyReceipt {
    val declared = call.request.headers[HttpHeaders.ContentLength]?.toLongOrNull()
    if (declared != null && declared > maxBytes) return BoundedBodyReceipt.TooLarge
    return try {
        val bytes = withContext(Dispatchers.IO) {
            call.receiveStream().use { reader(it, maxBytes, timeoutMs) }
        }
        BoundedBodyReceipt.Received(bytes)
    } catch (_: ByteLimitExceeded) {
        BoundedBodyReceipt.TooLarge
    } catch (_: BodyReceiptTimeout) {
        BoundedBodyReceipt.TimedOut
    }
}

/**
 * Bytes a staged upload may occupy, or 0 to refuse. Admission is the upload's **actual** staging
 * requirement: the body is bounded by whichever of [maxPayloadBytes] and [usableBytes] is smaller, and
 * nothing beyond it is reserved, because a fixed surplus only ever refused uploads that would have
 * installed. The caller refuses a declared length exceeding this ceiling before writing; a body that
 * outgrows it mid-stream is refused by the stream bound; and space exhausted during the write is
 * reported by the write itself rather than pre-judged here.
 */
internal fun uploadStagingLimit(usableBytes: Long, maxPayloadBytes: Long): Long {
    if (usableBytes <= 0L || maxPayloadBytes <= 0L) return 0L
    return minOf(maxPayloadBytes, usableBytes)
}

/** Registers the control routes whose request parsing and admission decisions are independent of Android. */
internal fun Route.controlPlaneRoutes(dependencies: ControlPlaneRouteDependencies) {
    post("/play") { handlePlay(call, dependencies) }
    route("/api/v1") {
        post("/play") { handlePlay(call, dependencies) }
        post("/install/component") { handleComponentInstall(call, dependencies) }
        post("/install/apk") { handleApkUpload(call, dependencies) }
        post("/install/apk/from-url") { handleApkFetchFromUrl(call, dependencies) }
        post("/install/apk/fetch/cancel") { handleApkFetchCancel(call, dependencies.apkUpload) }
        post("/install/apk/discard") { handleApkDiscard(call, dependencies.apkUpload) }
        get("/install/apk/pending") { handleApkPending(call, dependencies.apkUpload) }
        post("/install/apk/commit") { handleApkCommit(call, dependencies) }
        get("/successor") {
            val capability = dependencies.identityMigration.successorUploadCapability()
            if (capability == null) {
                call.respondText("""{"ok":false,"error":"not-a-bridge"}""", ContentType.Application.Json, HttpStatusCode.NotFound)
            } else {
                val installed = when (val status = dependencies.identityMigration.installedSuccessorStatus()) {
                    InstalledSuccessorStatus.Absent -> ""
                    InstalledSuccessorStatus.Untrusted -> ",\"installed_untrusted\":true"
                    is InstalledSuccessorStatus.Trusted -> ",\"installed_version_code\":${status.versionCode}"
                }
                call.respondText(
                    """{"package":${Json.str(capability.pkg)},"version":${Json.str(capability.version)}$installed}""",
                    ContentType.Application.Json,
                )
            }
        }
        post("/backup") { handleBackup(call, dependencies) }
    }
}

private suspend fun handleApkCommit(call: ApplicationCall, routes: ControlPlaneRouteDependencies) {
    val dependencies = routes.apkUpload
    val parameters = receiveBoundedFormParameters(call) ?: return
    val token = parameters["token"].orEmpty()
    val inspected = dependencies.pending.peek(token)
    val migrationSha256 = inspected?.migrationSha256
    if (migrationSha256 == null && !dependencies.enabled()) {
        call.respondText(
            """{"status":"disabled"}""",
            ContentType.Application.Json,
            HttpStatusCode.Forbidden,
        )
        return
    }
    if (inspected == null) {
        call.respondText(
            """{"status":"stale-or-missing"}""",
            ContentType.Application.Json,
            HttpStatusCode.Conflict,
        )
        return
    }
    val capability = if (migrationSha256 != null) routes.identityMigration.successorUploadCapability() else null
    if (migrationSha256 != null && capability == null) {
        call.respondText("""{"status":"not-a-bridge"}""", ContentType.Application.Json, HttpStatusCode.NotFound)
        return
    }
    val identity = inspected.identity
    val identitySummary = identity?.let(::uploadedApkApprovalSummary)?.let {
        if (migrationSha256 != null) "$it for identity migration" else it
    } ?: "Install the inspected uploaded APK"
    if (!routes.authorize(
            call,
            SensitiveOperation.APK_INSTALL,
            exactHttpApprovalPayload(call, parameters.canonicalDigest()),
            identitySummary,
        )
    ) return
    // The operation lane is taken BEFORE the entry is claimed, so a claim is final: the entry leaves
    // the store only when its install genuinely starts, and there is no "claimed but might come back"
    // window for a discard to be lied to in — the busy answer below leaves the entry pending, visible
    // to the probe and discardable throughout. The lane is taken after authorize(), never before:
    // Hardened approval can block on a human, and holding the exclusive lane through that would
    // starve every other operation. No suspension point sits between start() and claim().
    val progress = InstallProgress.start(
        "APK",
        InstallPresentation("operation-working", mapOf("owner" to "apk")),
    )
    if (progress == null) {
        call.respondText("""{"status":"busy"}""", ContentType.Application.Json)
        return
    }
    var invalidMigration = false
    val claimed = if (migrationSha256 == null) dependencies.pending.claim(token) else dependencies.pending.claimAfter(token) { entry ->
        val current = routes.identityMigration.successorUploadCapability()
        val valid = current != null && successorCandidateMatches(entry.identity, current) &&
            runCatching { AppInstaller.sha256(entry.file) == entry.migrationSha256 }.getOrDefault(false)
        invalidMigration = !valid
        valid
    }
    if (claimed == null) {
        if (invalidMigration) dependencies.pending.discard(token)
        // The entry vanished between the approved peek and the lane grant (discarded, replaced or
        // expired). Release the lane with an honest status rather than leaving it stranded busy.
        InstallProgress.finish(
            progress,
            "Nothing installed: the pending APK was discarded, replaced or expired.",
            presentation = InstallPresentation("apk-pending-lost"),
        )
        call.respondText(
            if (invalidMigration) """{"status":"migration-invalid"}""" else """{"status":"stale-or-missing"}""",
            ContentType.Application.Json,
            HttpStatusCode.Conflict,
        )
        return
    }
    dependencies.startInstall(claimed, progress)
    call.respondText("""{"status":"started"}""", ContentType.Application.Json)
}

/** Whether candidate APK bytes reached the staging file, or the exact wire refusal if they did not.
 *  The refusal is source-specific (an upload that outgrew its ceiling is not worded like a fetch that
 *  did), while everything after the bytes land is identical for both sources. */
private sealed interface StagedBytes {
    data object Written : StagedBytes
    data class Refused(val status: HttpStatusCode, val error: String) : StagedBytes
}

/**
 * The single staging, inspection and hand-off path shared by every APK source.
 *
 * Both the upload route and the URL route end here, so the security-relevant sequence exists once:
 * allocate staging, bound it to what the panel can actually store, obtain the bytes, inspect the exact
 * staged file, and mint a token bound to it. [writeBytes] is the only difference between sources.
 *
 * The caller has already taken [lease]. Every path that does not transfer a response releases that
 * lease and deletes the staging file in [finally], so an abandoned, refused or unreadable candidate
 * can never outlive the request or be reachable by a later token.
 */
private suspend fun stageInspectAndRespond(
    call: ApplicationCall,
    dependencies: ApkUploadRouteDependencies,
    lease: PendingUploadStore.Lease,
    declaredBytes: Long?,
    request: String? = null,
    migrationSha256: String? = null,
    migration: IdentityMigrationSurface = IdentityMigrationSurface.NONE,
    writeBytes: suspend (File, Long) -> StagedBytes,
) {
    // Echoed on every answer for a request-owned source, so a client can discard a reply belonging to
    // an action it has already replaced instead of rendering it over the newer one.
    val owner = request?.let(::requestEcho).orEmpty()
    var responseTransferred = false
    var stagedFile: File? = null
    try {
        val staged = runCatching { dependencies.createStagingFile() }.getOrElse {
            call.respondText(
                """{"ok":false,"error":"upload-staging-failed"$owner}""",
                ContentType.Application.Json,
                HttpStatusCode.InternalServerError,
            )
            return
        }
        stagedFile = staged
        val stagingLimit = uploadStagingLimit(dependencies.usableSpace(staged), dependencies.maxBytes)
        if (stagingLimit == 0L || (declaredBytes != null && declaredBytes > stagingLimit)) {
            call.respondText(
                """{"ok":false,"error":"insufficient-storage"$owner}""",
                ContentType.Application.Json,
                HttpStatusCode.InsufficientStorage,
            )
            return
        }
        when (val written = writeBytes(staged, stagingLimit)) {
            is StagedBytes.Refused -> {
                call.respondText(
                    """{"ok":false,"error":${Json.str(written.error)}$owner}""",
                    ContentType.Application.Json,
                    written.status,
                )
                return
            }
            StagedBytes.Written -> Unit
        }
        val identity = dependencies.inspect(staged)
        if (identity == null) {
            call.respondText(
                """{"ok":false,"error":"not-an-apk"$owner}""",
                ContentType.Application.Json,
                HttpStatusCode.BadRequest,
            )
            return
        }
        if (migrationSha256 != null) {
            val capability = migration.successorUploadCapability()
            val valid = capability != null && successorCandidateMatches(identity, capability) &&
                withContext(Dispatchers.IO) { runCatching { AppInstaller.sha256(staged) == migrationSha256 }.getOrDefault(false) }
            if (!valid) {
                call.respondText(
                    """{"ok":false,"error":"migration-invalid"$owner}""",
                    ContentType.Application.Json,
                    HttpStatusCode.UnprocessableEntity,
                )
                return
            }
        }
        val entry = dependencies.pending.stage(lease, staged, identity, migrationSha256)
        if (entry == null) {
            // A transfer that finished while the operator was cancelling must be reported as cancelled,
            // not as a shutdown, and above all must not have produced a token.
            val cancelled = dependencies.pending.isCancelled(lease)
            call.respondText(
                if (cancelled) """{"ok":false,"error":"cancelled"$owner}""" else """{"ok":false,"error":"stopping"$owner}""",
                ContentType.Application.Json,
                if (cancelled) CLIENT_CLOSED_REQUEST else HttpStatusCode.ServiceUnavailable,
            )
            return
        }
        call.respondText(
            """{"ok":true,"token":${Json.str(entry.token)},"package":${Json.str(identity.pkg)},"version":${Json.str(identity.version)},"signer":${Json.str(identity.signerSha256 ?: "unsigned")}$owner}""",
            ContentType.Application.Json,
        )
        responseTransferred = true
    } finally {
        if (!responseTransferred) {
            dependencies.pending.abort(lease)
            stagedFile?.delete()
        }
    }
}

/** Reserves the single staging slot, or answers the caller and returns null when it is unavailable.
 *  [owner] is the pre-rendered request echo, so a request-owned source answers uniformly here too. */
private suspend fun beginApkStaging(
    call: ApplicationCall,
    dependencies: ApkUploadRouteDependencies,
    echo: String = "",
    owner: String? = null,
): PendingUploadStore.BeginResult.Granted? = when (val admission = dependencies.pending.begin(owner)) {
    is PendingUploadStore.BeginResult.Granted -> admission
    PendingUploadStore.BeginResult.Busy -> {
        call.respondText(
            """{"ok":false,"error":"upload-busy"$echo}""",
            ContentType.Application.Json,
            HttpStatusCode.Conflict,
        )
        null
    }
    PendingUploadStore.BeginResult.Closed -> {
        call.respondText(
            """{"ok":false,"error":"stopping"$echo}""",
            ContentType.Application.Json,
            HttpStatusCode.ServiceUnavailable,
        )
        null
    }
}

/** The request echo appended to every answer for a request-owned source. */
private fun requestEcho(request: String): String = ""","request":${Json.str(request)}"""

/** Names the destination for the physical approval. The URL is attacker-supplied, so it goes through
 *  the same control/format stripping and bounding as every other field shown on the panel. */
internal fun apkFetchApprovalSummary(url: String): String {
    val host = runCatching { URI(url).host }.getOrNull().orEmpty()
    return "Download an APK from ${approvalDisplayField(host, APK_FETCH_HOST_CHARS)}"
}

/**
 * Fetches an operator-named APK so it can be reviewed exactly like an uploaded one.
 *
 * This is a *source* for the existing review flow, never an installer: it ends at the same staged file
 * and the same one-shot token, and installation still requires the unchanged commit route.
 *
 * **Authority is settled before the network is touched.** Making the panel originate a request is not
 * weaker than accepting an upload, and treating it as such was wrong: an upload requires the caller to
 * already hold the bytes, whereas a fetch aims a privileged device inside the network at a destination
 * the caller chooses, following redirects. In Hardened mode that must sit inside the approval boundary,
 * not outside it, so no socket opens until [SensitiveOperation.APK_FETCH] is approved for this exact
 * URL. Once past that gate the transfer is bounded by [AppInstaller.download] — HTTPS-only hops, a
 * redirect cap, a whole-operation deadline, and a byte ceiling enforced on bytes actually read.
 *
 * The download is owned by the client-minted request identifier, so a cancel stops this fetch and only
 * this fetch, and releases the staging slot immediately rather than leaving it held until a timeout.
 */
private suspend fun handleApkFetchFromUrl(call: ApplicationCall, routes: ControlPlaneRouteDependencies) {
    val dependencies = routes.apkUpload
    if (!dependencies.enabled()) {
        call.respondText(
            """{"ok":false,"error":"disabled"}""",
            ContentType.Application.Json,
            HttpStatusCode.Forbidden,
        )
        return
    }
    if (!dependencies.rootAvailable()) {
        call.respondText(
            """{"ok":false,"error":"no-root"}""",
            ContentType.Application.Json,
            HttpStatusCode.ServiceUnavailable,
        )
        return
    }
    val parameters = receiveBoundedFormParameters(call) ?: return
    val url = validApkFetchUrl(parameters["url"].orEmpty())
    if (url == null) {
        call.respondText(
            """{"ok":false,"error":"invalid-url"}""",
            ContentType.Application.Json,
            HttpStatusCode.BadRequest,
        )
        return
    }
    val request = validApkFetchRequestId(parameters["request"].orEmpty())
    if (request == null) {
        call.respondText(
            """{"ok":false,"error":"invalid-request"}""",
            ContentType.Application.Json,
            HttpStatusCode.BadRequest,
        )
        return
    }
    // Bound to the URL alone, not the whole body: the destination is what is being approved, and the
    // request identifier carries no authority, so a retry after approval need not reuse it.
    if (!routes.authorize(
            call,
            SensitiveOperation.APK_FETCH,
            exactHttpApprovalPayload(call, sha256Hex(url.toByteArray(Charsets.UTF_8))),
            apkFetchApprovalSummary(url),
        )
    ) return
    val echo = requestEcho(request)
    // Reserving supersedes any panel-side work the operator has just replaced, so the previous fetch
    // is stopped and its staged token retired rather than stranding this request behind them.
    val admitted = beginApkStaging(call, dependencies, echo, owner = request) ?: return
    val lease = admitted.lease
    val abort = admitted.abort ?: DownloadAbort()
    stageInspectAndRespond(call, dependencies, lease, declaredBytes = null, request = request) { staged, stagingLimit ->
        when (withContext(Dispatchers.IO) { dependencies.fetch(url, staged, stagingLimit, abort) }) {
            AppInstaller.DownloadResult.Succeeded -> StagedBytes.Written
            // A ceiling the panel's own free space imposed is reported as storage, not as the operator
            // having named something too large, because those need different corrective action.
            AppInstaller.DownloadResult.TooLarge -> if (stagingLimit < dependencies.maxBytes) {
                StagedBytes.Refused(HttpStatusCode.InsufficientStorage, "insufficient-storage")
            } else {
                StagedBytes.Refused(HttpStatusCode.PayloadTooLarge, "fetch-too-large")
            }
            AppInstaller.DownloadResult.TimedOut ->
                StagedBytes.Refused(HttpStatusCode.RequestTimeout, "fetch-timeout")
            AppInstaller.DownloadResult.Aborted ->
                StagedBytes.Refused(CLIENT_CLOSED_REQUEST, "cancelled")
            AppInstaller.DownloadResult.Failed ->
                StagedBytes.Refused(HttpStatusCode.BadGateway, "fetch-failed")
        }
    }
}

/** Stops one in-flight fetch, and only for the request that started it. */
private suspend fun handleApkFetchCancel(call: ApplicationCall, dependencies: ApkUploadRouteDependencies) {
    val parameters = receiveBoundedFormParameters(call) ?: return
    val request = validApkFetchRequestId(parameters["request"].orEmpty())
    if (request == null) {
        call.respondText(
            """{"ok":false,"error":"invalid-request"}""",
            ContentType.Application.Json,
            HttpStatusCode.BadRequest,
        )
        return
    }
    val cancelled = dependencies.pending.cancelPanelWork(request)
    call.respondText("""{"ok":true,"cancelled":$cancelled}""", ContentType.Application.Json)
}

/**
 * Retires the inspected-but-uncommitted upload named by `token`, deleting its staged bytes. (Issue #96)
 *
 * `token` is either the preview's commit token or the discard reference handed out by the pending
 * probe, so every discard is scoped to the exact entry its caller saw: a stale recovery card cannot
 * delete a replacement upload staged after its probe — it gets `different-pending` and must re-probe.
 * A committed entry is claimed only once its install genuinely starts (the commit route takes the
 * operation lane first), so "nothing pending" is always durable truth. Deliberately ungated, like the
 * fetch cancel above: it must keep working while the enable/root state changes around it, it removes
 * uncommitted bytes rather than installing anything, and it grants nothing a LAN client does not
 * already have, because a new upload already supersedes a staged entry. No Hardened-mode approval
 * either — cancellation must stay available there, and the physical boundary belongs to commit alone.
 */
private suspend fun handleApkDiscard(call: ApplicationCall, dependencies: ApkUploadRouteDependencies) {
    val parameters = receiveBoundedFormParameters(call) ?: return
    val ref = parameters["token"].orEmpty()
    if (ref.isEmpty()) {
        call.respondText(
            """{"ok":false,"error":"invalid-request"}""",
            ContentType.Application.Json,
            HttpStatusCode.BadRequest,
        )
        return
    }
    when (dependencies.pending.discard(ref)) {
        PendingUploadStore.DiscardResult.DISCARDED ->
            call.respondText("""{"ok":true,"discarded":true}""", ContentType.Application.Json)
        PendingUploadStore.DiscardResult.NOTHING_PENDING ->
            call.respondText("""{"ok":true,"discarded":false}""", ContentType.Application.Json)
        PendingUploadStore.DiscardResult.DIFFERENT_PENDING ->
            call.respondText(
                """{"ok":false,"error":"different-pending"}""",
                ContentType.Application.Json,
                HttpStatusCode.Conflict,
            )
    }
}

/** Answers whether an inspected-but-uncommitted APK is holding staged bytes, and what it is — never
 *  its token, which is the commit authority and must reach only the client that staged the upload.
 *  The `discard` field is the entry's discard reference: it scopes a recovery discard to exactly this
 *  entry and carries no install authority (it cannot peek, claim or commit). Ungated like the status
 *  poll: a probe answering "disabled" would hide exactly the state the recovery UI needs, and when
 *  the capability is off there is never anything pending to reveal. */
private suspend fun handleApkPending(call: ApplicationCall, dependencies: ApkUploadRouteDependencies) {
    val summary = dependencies.pending.pendingSummary()
    if (summary == null) {
        call.respondText("""{"pending":false}""", ContentType.Application.Json)
        return
    }
    val identity = summary.identity
    call.respondText(
        """{"pending":true,"discard":${Json.str(summary.discardId)},"package":${Json.str(identity?.pkg ?: "")},"version":${Json.str(identity?.version ?: "")},"signer":${Json.str(identity?.signerSha256 ?: "unsigned")}}""",
        ContentType.Application.Json,
    )
}

/** Stages an arbitrary user-supplied APK on the unauthenticated LAN-trust surface, so the production source, origin, host, explicit-enable and root-capability gates must remain in force around this route. */
private suspend fun handleApkUpload(call: ApplicationCall, routes: ControlPlaneRouteDependencies) {
    val dependencies = routes.apkUpload
    val migrationParam = call.request.queryParameters.getAll("migration")
    val digestParam = call.request.queryParameters.getAll("sha256")
    val migrationRequested = migrationParam != null || digestParam != null
    if (migrationRequested && routes.identityMigration.successorUploadCapability() == null) {
        call.respondText("""{"ok":false,"error":"not-a-bridge"}""", ContentType.Application.Json, HttpStatusCode.NotFound)
        return
    }
    val migrationSha256 = if (migrationRequested) {
        val value = digestParam?.singleOrNull()
        if (migrationParam?.singleOrNull() != "successor" || value == null || !SUCCESSOR_DIGEST.matches(value) ||
            call.request.queryParameters.names() != setOf("migration", "sha256")
        ) {
            call.respondText("""{"ok":false,"error":"invalid-migration-request"}""", ContentType.Application.Json, HttpStatusCode.BadRequest)
            return
        }
        value
    } else null
    if (!migrationRequested && !dependencies.enabled()) {
        call.respondText(
            """{"ok":false,"error":"disabled"}""",
            ContentType.Application.Json,
            HttpStatusCode.Forbidden,
        )
        return
    }
    if (!migrationRequested && !dependencies.rootAvailable()) {
        call.respondText(
            """{"ok":false,"error":"no-root"}""",
            ContentType.Application.Json,
            HttpStatusCode.ServiceUnavailable,
        )
        return
    }
    val declaredHeader = call.request.headers[HttpHeaders.ContentLength]
    val declaredBytes = declaredHeader?.toLongOrNull()
    if (declaredHeader != null && (declaredBytes == null || declaredBytes < 0L)) {
        call.respondText(
            """{"ok":false,"error":"invalid-content-length"}""",
            ContentType.Application.Json,
            HttpStatusCode.BadRequest,
        )
        return
    }
    if (declaredBytes != null && declaredBytes > dependencies.maxBytes) {
        call.respondText(
            """{"ok":false,"error":"upload-too-large"}""",
            ContentType.Application.Json,
            HttpStatusCode.PayloadTooLarge,
        )
        return
    }
    val lease = (beginApkStaging(call, dependencies) ?: return).lease
    stageInspectAndRespond(
        call, dependencies, lease, declaredBytes,
        migrationSha256 = migrationSha256,
        migration = routes.identityMigration,
    ) { staged, stagingLimit ->
        try {
            val received = withContext(Dispatchers.IO) {
                call.receiveStream().use { input ->
                    staged.outputStream().use { output ->
                        dependencies.receiveBody(input, output, stagingLimit)
                    }
                } > 0L
            }
            if (received) StagedBytes.Written
            else StagedBytes.Refused(HttpStatusCode.BadRequest, "upload-failed")
        } catch (_: ByteLimitExceeded) {
            if (stagingLimit < dependencies.maxBytes) {
                StagedBytes.Refused(HttpStatusCode.InsufficientStorage, "insufficient-storage")
            } else {
                StagedBytes.Refused(HttpStatusCode.PayloadTooLarge, "upload-too-large")
            }
        } catch (_: BodyReceiptTimeout) {
            StagedBytes.Refused(HttpStatusCode.RequestTimeout, "upload-timeout")
        } catch (_: Exception) {
            StagedBytes.Refused(HttpStatusCode.BadRequest, "upload-failed")
        }
    }
}

private suspend fun handlePlay(call: ApplicationCall, dependencies: ControlPlaneRouteDependencies) {
    val body = when (val receipt = receiveBoundedBody(call, PaneldServer.MAX_PLAY_BODY_BYTES)) {
        is BoundedBodyReceipt.Received -> String(receipt.bytes, Charsets.UTF_8)
        BoundedBodyReceipt.TooLarge -> {
            call.respondText("body-too-large\n", status = HttpStatusCode.PayloadTooLarge)
            return
        }
        BoundedBodyReceipt.TimedOut -> {
            call.respondText("body-timeout\n", status = HttpStatusCode.RequestTimeout)
            return
        }
    }
    val url = PLAY_URL.find(body)?.value
    if (url == null) {
        call.respondText("no-url\n", status = HttpStatusCode.BadRequest)
        return
    }
    if (!dependencies.authorize(
            call,
            SensitiveOperation.REMOTE_MEDIA,
            exactHttpApprovalPayload(call, sha256Hex(body.toByteArray(Charsets.UTF_8))),
            "Play media from $url",
        )
    ) return
    if (!dependencies.playAudio(url)) {
        call.respondText("stopping\n", status = HttpStatusCode.ServiceUnavailable)
        return
    }
    call.respondText("playing\n")
}

private suspend fun handleComponentInstall(
    call: ApplicationCall,
    dependencies: ControlPlaneRouteDependencies,
) {
    val parameters = receiveBoundedFormParameters(call) ?: return
    val name = parameters["name"]?.trim().orEmpty()
    val action = parameters["action"]?.trim()?.ifEmpty { "update" } ?: "update"
    val version = parameters["version"]?.trim().orEmpty()
    val allowDowngrade = parameters["allow_downgrade"]?.let { it == "true" || it == "1" } ?: false
    val installedVersion = if (version.isEmpty() || name !in COMPONENT_NAMES || !ReleaseCatalog.validTag(version)) {
        null
    } else {
        dependencies.installedComponentVersion(name)?.takeIf { it.isNotBlank() }
    }
    val versionComparison = installedVersion?.let { current ->
        val requested = if (name == "companion") UpdateChecker.stripVariant(version) else version
        val installed = if (name == "companion") UpdateChecker.stripVariant(current) else current
        UpdateChecker.compareVersions(requested, installed)
    }
    when {
        name !in COMPONENT_NAMES -> call.respondText(
            """{"status":"bad-component"}""",
            ContentType.Application.Json,
            HttpStatusCode.BadRequest,
        )
        action !in COMPONENT_ACTIONS -> call.respondText(
            """{"status":"bad-action"}""",
            ContentType.Application.Json,
            HttpStatusCode.BadRequest,
        )
        version.isNotEmpty() && !ReleaseCatalog.validTag(version) -> call.respondText(
            """{"status":"bad-version"}""",
            ContentType.Application.Json,
            HttpStatusCode.BadRequest,
        )
        installedVersion != null && versionComparison == null -> call.respondText(
            """{"status":"uncomparable-version"}""",
            ContentType.Application.Json,
            HttpStatusCode.BadRequest,
        )
        installedVersion != null && !allowDowngrade && versionComparison!! < 0 -> call.respondText(
            """{"status":"downgrade-refused"}""",
            ContentType.Application.Json,
            HttpStatusCode.Conflict,
        )
        else -> {
            if (!dependencies.authorize(
                    call,
                    SensitiveOperation.APK_INSTALL,
                    exactHttpApprovalPayload(call, parameters.canonicalDigest()),
                    "Install ${name.ifEmpty { "component" }} ${version.ifEmpty { "latest" }}",
                )
            ) return
            val status = if (dependencies.installComponent(name, action, version)) "started" else "busy"
            call.respondText("""{"status":"$status"}""", ContentType.Application.Json)
        }
    }
}

private suspend fun handleBackup(call: ApplicationCall, dependencies: ControlPlaneRouteDependencies) {
    val parameters = receiveBoundedFormParameters(call) ?: return
    val passphrase = parameters["passphrase"].orEmpty()
    val allowPlaintext = parameters["allow_plaintext"]?.let { it == "true" || it == "1" } ?: false
    // Refuse an uninterpretable request before the policy checks below: those answer a request that was
    // understood, and this one was not. `allow_plaintext` above stays lenient on purpose — an unrecognised
    // value there falls to the safe side and only costs the caller an extra explicit acknowledgement,
    // whereas falling to a side here would quietly decide what the backup contains.
    val companionRequest = parseCompanionBackupRequest(parameters["include_companion"])
    if (companionRequest == null) {
        call.respondText(
            """{"ok":false,"error":"invalid-include-companion","message":"include_companion must be true, 1, false or 0. Omit it to include the HA Companion login only when it is installed."}""",
            ContentType.Application.Json,
            HttpStatusCode.BadRequest,
        )
        return
    }
    if (passphrase.isEmpty() && !allowPlaintext) {
        call.respondText(
            """{"ok":false,"error":"passphrase-required","message":"A backup contains credentials. Supply a passphrase, or explicitly acknowledge plaintext export with allow_plaintext=1."}""",
            ContentType.Application.Json,
            HttpStatusCode.BadRequest,
        )
        return
    }
    if (!dependencies.authorize(
            call,
            SensitiveOperation.BACKUP_EXPORT,
            exactHttpApprovalPayload(call, parameters.canonicalDigest()),
            "Export a backup containing panel credentials",
        )
    ) return
    val delivery = BackupDeliveryGate.acquire()
    if (delivery == null) {
        call.respondText(
            """{"ok":false,"error":"busy"}""",
            ContentType.Application.Json,
            HttpStatusCode.Conflict,
        )
        return
    }
    // Every backup owns the destructive/expensive operation lane. Even config-only encrypted bundles
    // run PBKDF2 + AES and must not be multiplied by concurrent unauthenticated LAN requests.
    val progress = InstallProgress.start(
        "Backup",
        InstallPresentation("operation-working", mapOf("owner" to "backup")),
    )
    if (progress == null) {
        delivery.close()
        call.respondText(
            """{"ok":false,"error":"busy"}""",
            ContentType.Application.Json,
            HttpStatusCode.Conflict,
        )
        return
    }
    var progressResult = "backup cancelled"
    var progressPresentation: InstallPresentation? = InstallPresentation("backup-cancelled")
    var progressFinished = false
    var deliveryHandedOff = false
    var artifact: PanelBackup.Artifact? = null
    val deliveryCleaned = AtomicBoolean(false)
    fun cleanDelivery() {
        if (deliveryCleaned.compareAndSet(false, true)) {
            artifact?.close()
            delivery.close()
        }
    }
    try {
        val built = dependencies.buildBackup(companionRequest, passphrase)
        artifact = built
        // The archive records its own incompleteness, but the owner is standing here now: a bundle whose
        // panel state could not be read must not report the same "backup ready" as a whole one, or the
        // silence simply moves from the manifest to the progress line.
        progressResult = if (built.stateUnavailable) "backup ready without panel state" else "backup ready"
        progressPresentation = InstallPresentation(
            if (built.stateUnavailable) "backup-ready-state-unavailable" else "backup-ready",
        )
        InstallProgress.finish(progress, progressResult, presentation = progressPresentation)
        progressFinished = true
        call.response.headers.append(
            "Content-Disposition",
            "attachment; filename=\"${dependencies.backupFileStem()}-backup.${built.extension}\"",
        )
        deliveryHandedOff = true
        try {
            call.respondOutputStream(ContentType.Application.OctetStream) {
                try {
                    built.file.inputStream().use { input -> input.copyTo(this) }
                } finally {
                    cleanDelivery()
                }
            }
        } catch (error: Throwable) {
            deliveryHandedOff = false
            throw error
        }
    } catch (_: ByteLimitExceeded) {
        progressResult = "Companion backup is too large"
        val presentation = InstallPresentation("backup-companion-too-large")
        progressPresentation = presentation
        call.respondText(
            withInstallPresentation("""{"ok":false,"error":"companion-backup-too-large"}""", presentation),
            ContentType.Application.Json,
            HttpStatusCode.PayloadTooLarge,
        )
        return
    } catch (error: CompanionBackupUnavailable) {
        progressResult = error.reason
        progressPresentation = error.presentation
        call.respondText(
            withInstallPresentation("""{"ok":false,"error":${Json.str(error.reason)}}""", error.presentation),
            ContentType.Application.Json,
            HttpStatusCode.UnprocessableEntity,
        )
        return
    } catch (_: BackupStagingRetainedException) {
        progressResult = "Sensitive backup staging file retained"
        val presentation = InstallPresentation("backup-staging-retained")
        progressPresentation = presentation
        call.respondText(
            withInstallPresentation(
                """{"ok":false,"error":"backup-staging-retained","message":"Sensitive temporary backup data could not be removed. Check panel storage, then retry; no backup was downloaded."}""",
                presentation,
            ),
            ContentType.Application.Json,
            HttpStatusCode.InsufficientStorage,
        )
        return
    } finally {
        if (!deliveryHandedOff) {
            cleanDelivery()
        }
        if (!progressFinished) {
            InstallProgress.finish(progress, progressResult, presentation = progressPresentation)
        }
    }
}

/** Append optional presentation metadata without rewriting any existing compatibility field. */
private fun withInstallPresentation(legacyJson: String, presentation: InstallPresentation?): String {
    if (presentation == null) return legacyJson
    require(legacyJson.endsWith('}'))
    return legacyJson.dropLast(1) + ",\"presentation\":" + presentation.json() + "}"
}

private val PLAY_URL = Regex("""https?://[^\s"']+""")
private val COMPONENT_NAMES = setOf("paneld", "companion")
private val COMPONENT_ACTIONS = setOf("update", "reinstall")
internal const val RESTORE_BODY_RECEIPT_DEADLINE_MS = 120_000L
internal const val STANDARD_BODY_RECEIPT_DEADLINE_MS = 30_000L
private const val APK_UPLOAD_RECEIPT_DEADLINE_MS = 600_000L
internal const val APK_FETCH_URL_MAX_CHARS = 2048
internal const val APK_FETCH_REQUEST_ID_MAX_CHARS = 64
private const val APK_FETCH_HOST_CHARS = 64

/** No standard status says "the operator stopped this"; 499 is the established convention for it. */
private val CLIENT_CLOSED_REQUEST = HttpStatusCode(499, "Client Closed Request")
private const val APK_APPROVAL_PACKAGE_CHARS = 64
private const val APK_APPROVAL_SIGNER_CHARS = 64
private const val APK_APPROVAL_VERSION_CHARS = 20
