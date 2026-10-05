package io.panelassistant.android.http

import io.panelassistant.android.device.profile.PassiveProfileDraft
import io.panelassistant.android.device.profile.PassiveProfileReport
import io.panelassistant.android.device.profile.ProfileActivationState
import io.panelassistant.android.device.profile.ProfileAdmin
import io.panelassistant.android.device.profile.ProfileDiff
import io.panelassistant.android.device.profile.ProfileIssue
import io.panelassistant.android.device.profile.ProfileMutation
import io.panelassistant.android.device.profile.ProfilePresentation
import io.panelassistant.android.device.profile.ProfileRef
import io.panelassistant.android.device.profile.ProfileSelection
import io.panelassistant.android.device.profile.ProfileStatus
import io.panelassistant.android.device.profile.ProfileSummary
import io.panelassistant.android.device.profile.ProfileSoc
import io.panelassistant.android.security.SensitiveOperation
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import org.json.JSONArray
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest

/** Read-only runtime facts stay separately injectable until they join the stable [ProfileAdmin] surface. */
internal data class ProfileRouteReadOnlyProviders(
    val template: () -> String? = { null },
    val deviceDraft: () -> PassiveProfileDraft? = { null },
    val latestReport: () -> PassiveProfileReport? = { null },
    val probe: (String) -> PassiveProfileReport? = { null },
)

internal data class ProfileRouteDependencies(
    val admin: ProfileAdmin,
    /** Must schedule a delayed controlled restart; it must never stop the service inline. */
    val requestRestart: () -> Boolean = { true },
    /** Prevent process exit while an install, restore, or other destructive transaction owns its lane. */
    val restartAllowed: () -> Boolean = { true },
    /** Restore the previous selection when restart admission fails after durable staging. */
    val abortPendingRestart: (String) -> Boolean = { false },
    val readOnly: ProfileRouteReadOnlyProviders = ProfileRouteReadOnlyProviders(),
    val hardMaxYamlBytes: Long = MAX_PROFILE_YAML_BYTES,
    val authorize: suspend (ApplicationCall, SensitiveOperation, String, String) -> Boolean = { _, _, _, _ -> true },
)

internal data class ProfileRestartRejection(
    val error: String,
    val message: String,
    val abortPersisted: Boolean,
    val presentation: ProfilePresentation,
)

/** Records whether a staged activation was durably rolled back when restart admission fails. */
internal fun rejectFailedProfileRestart(
    restartAllowed: Boolean,
    requestRestart: () -> Boolean,
    abortPendingRestart: (String) -> Boolean,
): ProfileRestartRejection? {
    val rejection = when {
        !restartAllowed ->
            "destructive-operation-in-progress" to
                "A destructive install or restore operation is in progress. Try profile activation again when it finishes."
        !runCatching(requestRestart).getOrDefault(false) ->
            "profile-restart-unavailable" to "The profile restart could not be scheduled. Try again."
        else -> return null
    }
    val abortPersisted = runCatching { abortPendingRestart(rejection.second) }.getOrDefault(false)
    return if (abortPersisted) {
        ProfileRestartRejection(
            rejection.first,
            rejection.second,
            true,
            ProfilePresentation(rejection.first),
        )
    } else {
        ProfileRestartRejection(
            error = "profile-activation-abort-persist-failed",
            message = "The profile restart could not be scheduled, and its pending activation could not be rolled back durably. The activation remains pending and may apply on the next process restart.",
            abortPersisted = false,
            presentation = ProfilePresentation("profile-activation-abort-persist-failed"),
        )
    }
}

internal const val MAX_PROFILE_YAML_BYTES = 256L * 1024L
private const val MAX_PROFILE_ACTION_BYTES = 16L * 1024L
private val PROFILE_ID = Regex("^[a-z0-9](?:[a-z0-9.-]{0,126}[a-z0-9])?$")
private val PROFILE_REVISION = Regex("^[a-f0-9]{64}$")

/**
 * Profile administration transport. The route owns bounds, explicit confirmations, optimistic
 * catalog revisions, and safe JSON projection; parsing, persistence, matching and activation state
 * remain entirely behind [ProfileAdmin]. Mount inside the existing `/api/v1` route so the server's
 * LAN-source, origin and Host guards apply before any profile bytes are read.
 */
internal fun Route.profileRoutes(dependencies: ProfileRouteDependencies) {
    val admin = dependencies.admin
    route("/profiles") {
        get {
            val status = admin.status()
            val lastKnownGood = (status.lastKnownGood as? ProfileSelection.Pinned)?.ref
            val body = statusJson(status).apply {
                put("profiles", JSONArray(admin.list().map { summary ->
                    summaryJson(summary).put("last_known_good", summary.ref == lastKnownGood)
                }))
            }
            call.respondProfileJson(body)
        }
        get("/schema") { call.respondProfileJson(schemaJson(admin)) }
        get("/drivers") {
            call.respondProfileJson(JSONObject().put("drivers", JSONArray(admin.drivers().map { driver ->
                JSONObject()
                    .put("id", driver.id)
                    .put("kind", driver.kind.name.lowercase())
                    .put("description", driver.description)
                    .put("privileged", driver.privileged)
            })))
        }
        get("/template") {
            val yaml = dependencies.readOnly.template()
            if (yaml == null) call.respondProfileTextError(
                HttpStatusCode.NotImplemented,
                "profile template unavailable\n",
                "profile-template-unavailable",
            )
            else call.respondProfileYaml(yaml, "new-panel-profile.yaml")
        }
        get("/device-draft") {
            val draft = dependencies.readOnly.deviceDraft()
            if (draft == null) call.respondProfileTextError(
                HttpStatusCode.NotImplemented,
                "passive device draft unavailable\n",
                "passive-device-draft-unavailable",
            )
            else call.respondProfileYaml(draft.rawYaml, "passive-device-draft.yaml")
        }
        get("/report") {
            val report = dependencies.readOnly.latestReport()
            if (report == null) call.respondProfileTextError(
                HttpStatusCode.NotFound,
                "passive report unavailable\n",
                "passive-report-unavailable",
            )
            else call.respondProfileJson(reportJson(report))
        }
        get("/{id}/revisions/{revision}") {
            val ref = call.profileRefOrRespond() ?: return@get
            val yaml = admin.exportProfile(ref)
            if (yaml == null) call.respondProfileTextError(
                HttpStatusCode.NotFound,
                "profile revision not found\n",
                "profile-revision-not-found",
            )
            else call.respondProfileYaml(yaml, "${safeFileStem(ref.id)}-${ref.revision.take(12)}.yaml")
        }

        post("/preview") { handlePreview(call, dependencies, includeProbe = false) }
        post("/validate") { handlePreview(call, dependencies, includeProbe = false) }
        post("/compare") { handlePreview(call, dependencies, includeProbe = false) }
        post("/probe") { handlePreview(call, dependencies, includeProbe = true) }
        post("/import") { handleImport(call, dependencies) }
        post("/save") { handleImport(call, dependencies) }
        post("/select") { handleSelection(call, dependencies, rollback = false) }
        post("/activate") { handleSelection(call, dependencies, rollback = false) }
        post("/rollback") { handleSelection(call, dependencies, rollback = true) }
        post("/delete") { handleDelete(call, dependencies) }
    }
}

/** Keep the tab and documented paths honest before the runtime repository is wired by the service. */
internal fun Route.unavailableProfileRoutes() {
    route("/profiles") {
        get { call.respondProfileTextError(HttpStatusCode.ServiceUnavailable, "profile administration unavailable\n", "profile-administration-unavailable") }
        get("/{path...}") { call.respondProfileTextError(HttpStatusCode.ServiceUnavailable, "profile administration unavailable\n", "profile-administration-unavailable") }
        post("/{path...}") { call.respondProfileTextError(HttpStatusCode.ServiceUnavailable, "profile administration unavailable\n", "profile-administration-unavailable") }
    }
}

private suspend fun handlePreview(
    call: ApplicationCall,
    dependencies: ProfileRouteDependencies,
    includeProbe: Boolean,
) {
    val yaml = call.receiveProfileYaml(dependencies.maxYamlBytes()) ?: return
    val preview = dependencies.admin.preview(yaml)
    val body = previewJson(preview)
    if (includeProbe) dependencies.readOnly.probe(yaml)?.let { body.put("report", reportJson(it)) }
    call.respondProfileJson(body)
}

private suspend fun handleImport(call: ApplicationCall, dependencies: ProfileRouteDependencies) {
    val token = call.request.headers["X-Profile-Preview-Token"]?.trim().orEmpty()
    if (token.isBlank() || token.length > 512) {
        call.respondProfileError(HttpStatusCode.BadRequest, "preview-token-required")
        return
    }
    val yaml = call.receiveProfileYaml(dependencies.maxYamlBytes()) ?: return
    val mutation = dependencies.admin.importProfile(yaml, token)
    val importedRef = if (mutation is ProfileMutation.Success) {
        val hash = MessageDigest.getInstance("SHA-256").digest(yaml.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        dependencies.admin.list().singleOrNull { it.ref.revision == hash }?.ref
    } else null
    call.respondMutation(mutation, importedRef = importedRef)
}

private suspend fun handleSelection(
    call: ApplicationCall,
    dependencies: ProfileRouteDependencies,
    rollback: Boolean,
) {
    val request = call.receiveProfileAction() ?: return
    if (!request.optBoolean("confirm", false)) {
        call.respondProfileError(HttpStatusCode.Conflict, "explicit-confirmation-required")
        return
    }
    val expectedRevision = request.optLong("expected_catalog_revision", Long.MIN_VALUE)
    if (expectedRevision == Long.MIN_VALUE) {
        call.respondProfileError(HttpStatusCode.BadRequest, "expected-catalog-revision-required")
        return
    }
    val selection = if (rollback) {
        null
    } else if (request.optBoolean("auto", false)) {
        ProfileSelection.Auto
    } else {
        val ref = when (val parsed = request.parseProfileRef()) {
            is ProfileRefParse.Valid -> parsed.ref
            ProfileRefParse.BothFormsSupplied -> {
                call.respondProfileError(HttpStatusCode.BadRequest, "profile-ref-both-forms-supplied")
                return
            }
            ProfileRefParse.Invalid -> {
                call.respondProfileError(HttpStatusCode.BadRequest, "invalid-profile-ref")
                return
            }
        }
        ProfileSelection.Pinned(ref)
    }
    val approvalTarget = if (rollback) dependencies.admin.status().lastKnownGood else selection
    val targetSummary = when (approvalTarget) {
        ProfileSelection.Auto -> "automatic device matching"
        is ProfileSelection.Pinned ->
            "${approvalTarget.ref.id}@${approvalTarget.ref.revision.take(16)}"
        null -> "no recorded last-known-good profile"
    }
    if (!dependencies.authorize(
            call,
            SensitiveOperation.PROFILE_ACTIVATE,
            exactHttpApprovalPayload(call, sha256Hex(request.toString().toByteArray(Charsets.UTF_8))),
            if (rollback) "Roll back to $targetSummary" else "Activate $targetSummary and restart ha-paneld",
        )
    ) return
    val mutation = if (rollback) {
        dependencies.admin.rollbackToLastKnownGood(expectedRevision)
    } else {
        dependencies.admin.select(requireNotNull(selection), expectedRevision)
    }
    if (mutation is ProfileMutation.Success && mutation.restartRequired) {
        val restartAllowed = dependencies.restartAllowed()
        val rejection = rejectFailedProfileRestart(
            restartAllowed,
            dependencies.requestRestart,
            dependencies.abortPendingRestart,
        )
        if (rejection != null) {
            call.respondProfileJson(
                JSONObject()
                    .put("ok", false)
                    .put("error", rejection.error)
                    .put("message", rejection.message)
                    .putPresentation(rejection.presentation)
                    .put("activation_pending", !rejection.abortPersisted)
                    .put("status", statusJson(dependencies.admin.status())),
                HttpStatusCode.ServiceUnavailable,
            )
            return
        }
    }
    call.respondMutation(
        mutation,
        expectedCatalogRevision = expectedRevision,
        selectedRef = (selection as? ProfileSelection.Pinned)?.ref,
    )
}

private suspend fun handleDelete(call: ApplicationCall, dependencies: ProfileRouteDependencies) {
    val request = call.receiveProfileAction() ?: return
    if (!request.optBoolean("confirm", false)) {
        call.respondProfileError(HttpStatusCode.Conflict, "explicit-confirmation-required")
        return
    }
    val ref = when (val parsed = request.parseProfileRef()) {
        is ProfileRefParse.Valid -> parsed.ref
        ProfileRefParse.BothFormsSupplied -> {
            call.respondProfileError(HttpStatusCode.BadRequest, "profile-ref-both-forms-supplied")
            return
        }
        ProfileRefParse.Invalid -> null
    }
    val expectedRevision = request.optLong("expected_catalog_revision", Long.MIN_VALUE)
    if (ref == null || expectedRevision == Long.MIN_VALUE) {
        call.respondProfileError(HttpStatusCode.BadRequest, "invalid-delete-request")
        return
    }
    call.respondMutation(
        dependencies.admin.deleteProfile(ref, expectedRevision),
        expectedCatalogRevision = expectedRevision,
    )
}

private fun ProfileRouteDependencies.maxYamlBytes(): Long =
    minOf(hardMaxYamlBytes, admin.schema().maxBytes.toLong().coerceAtLeast(1L))

private suspend fun ApplicationCall.receiveProfileYaml(maxBytes: Long): String? {
    val contentType = request.headers[HttpHeaders.ContentType]?.substringBefore(';')?.trim()?.lowercase()
    if (contentType !in setOf("application/yaml", "application/x-yaml", "text/yaml", "text/plain")) {
        respondProfileError(HttpStatusCode.UnsupportedMediaType, "yaml-content-type-required")
        return null
    }
    return receiveBoundedUtf8(maxBytes, "profile-yaml-too-large")
}

private suspend fun ApplicationCall.receiveProfileAction(): JSONObject? {
    val contentType = request.headers[HttpHeaders.ContentType]?.substringBefore(';')?.trim()?.lowercase()
    if (contentType != ContentType.Application.Json.toString()) {
        respondProfileError(HttpStatusCode.UnsupportedMediaType, "json-content-type-required")
        return null
    }
    val body = receiveBoundedUtf8(MAX_PROFILE_ACTION_BYTES, "profile-action-too-large") ?: return null
    return try {
        JSONObject(body)
    } catch (_: Throwable) {
        respondProfileError(HttpStatusCode.BadRequest, "invalid-json")
        null
    }
}

private suspend fun ApplicationCall.receiveBoundedUtf8(maxBytes: Long, tooLarge: String): String? {
    val bytes = when (val receipt = receiveBoundedBody(this, maxBytes)) {
        is BoundedBodyReceipt.Received -> receipt.bytes
        BoundedBodyReceipt.TooLarge -> {
            respondProfileError(HttpStatusCode.PayloadTooLarge, tooLarge)
            return null
        }
        BoundedBodyReceipt.TimedOut -> {
            respondProfileError(HttpStatusCode.RequestTimeout, "profile-body-timeout")
            return null
        }
    }
    return try {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    } catch (_: CharacterCodingException) {
        respondProfileError(HttpStatusCode.BadRequest, "invalid-utf8")
        null
    }
}

private suspend fun ApplicationCall.profileRefOrRespond(): ProfileRef? {
    val ref = ProfileRef(parameters["id"].orEmpty(), parameters["revision"].orEmpty())
    if (!ref.valid()) {
        respondProfileError(HttpStatusCode.BadRequest, "invalid-profile-ref")
        return null
    }
    return ref
}

/**
 * A profile ref may arrive flat (`{"id","revision"}`, the shape every write route has always
 * accepted) or nested (`{"ref":{"id","revision"}}`, the shape [refJson] hands back from every read
 * route). Supplying both at once is ambiguous rather than redundant, so it is rejected distinctly
 * from a malformed ref.
 */
private sealed class ProfileRefParse {
    data class Valid(val ref: ProfileRef) : ProfileRefParse()
    object Invalid : ProfileRefParse()
    object BothFormsSupplied : ProfileRefParse()
}

private fun JSONObject.parseProfileRef(): ProfileRefParse {
    val hasFlat = has("id") || has("revision")
    val hasNested = has("ref")
    if (hasFlat && hasNested) return ProfileRefParse.BothFormsSupplied
    val source = if (hasNested) optJSONObject("ref") ?: return ProfileRefParse.Invalid else this
    val ref = ProfileRef(source.optString("id"), source.optString("revision"))
    return if (ref.valid()) ProfileRefParse.Valid(ref) else ProfileRefParse.Invalid
}

private fun ProfileRef.valid(): Boolean = PROFILE_ID.matches(id) && ".." !in id && PROFILE_REVISION.matches(revision)

private suspend fun ApplicationCall.respondMutation(
    mutation: ProfileMutation,
    expectedCatalogRevision: Long? = null,
    selectedRef: ProfileRef? = null,
    importedRef: ProfileRef? = null,
): Boolean {
    return when (mutation) {
        is ProfileMutation.Success -> {
            val body = mutationJson(mutation).apply {
                selectedRef?.let { put("ref", refJson(it)) }
                importedRef?.let { put("imported_ref", refJson(it)) }
            }
            respondProfileJson(body, if (mutation.restartRequired) HttpStatusCode.Accepted else HttpStatusCode.OK)
            mutation.restartRequired
        }
        is ProfileMutation.Rejected -> {
            val staleCatalog = expectedCatalogRevision != null && mutation.status.catalogRevision != expectedCatalogRevision
            val stalePreview = mutation.issues.any {
                it.path == "preview_token" || it.presentation?.code == "preview-token-invalid"
            }
            respondProfileJson(
                mutationJson(mutation),
                if (staleCatalog || stalePreview) HttpStatusCode.Conflict else HttpStatusCode.UnprocessableEntity,
            )
            false
        }
    }
}

private suspend fun ApplicationCall.respondProfileJson(
    body: JSONObject,
    status: HttpStatusCode = HttpStatusCode.OK,
) {
    response.headers.append(HttpHeaders.CacheControl, "no-store")
    respondText(body.toString(), ContentType.Application.Json, status)
}

private suspend fun ApplicationCall.respondProfileError(status: HttpStatusCode, error: String) {
    respondProfileJson(
        JSONObject()
            .put("ok", false)
            .put("error", error)
            .putPresentation(ProfilePresentation(error)),
        status,
    )
}

private suspend fun ApplicationCall.respondProfileTextError(
    status: HttpStatusCode,
    message: String,
    presentationCode: String,
) {
    val presentation = ProfilePresentation(presentationCode)
    response.headers.append("X-Profile-Presentation-Code", presentation.code)
    response.headers.append("X-Profile-Presentation-Params", JSONObject(presentation.params).toString())
    respondText(message, status = status)
}

private suspend fun ApplicationCall.respondProfileYaml(yaml: String, fileName: String) {
    response.headers.append(HttpHeaders.CacheControl, "no-store")
    response.headers.append(HttpHeaders.ContentDisposition, "attachment; filename=\"$fileName\"")
    respondText(yaml, ContentType.parse("application/yaml"))
}

private fun schemaJson(admin: ProfileAdmin): JSONObject {
    val schema = admin.schema()
    return JSONObject()
        .put("schema", schema.schema)
        .put("max_bytes", schema.maxBytes)
        .put("fields", JSONArray(schema.fields.map { field ->
            JSONObject()
                .put("path", field.path)
                .put("type", field.type)
                .put("required", field.required)
                .put("enum_values", JSONArray(field.enumValues))
                .put("description", field.description)
        }))
}

private fun statusJson(status: ProfileStatus): JSONObject = JSONObject()
    .put("catalog_revision", status.catalogRevision)
    .put("selection", selectionJson(status.selection))
    .put("active", status.active?.let(::summaryJson) ?: JSONObject.NULL)
    .put("activation", activationJson(status.activation))
    .put("rollback_ref", (status.lastKnownGood as? ProfileSelection.Pinned)?.ref?.let(::refJson) ?: JSONObject.NULL)
    .put("rollback_auto", status.lastKnownGood is ProfileSelection.Auto)
    .put("issues", JSONArray(status.issues.map(::issueJson)))

private fun activationJson(activation: ProfileActivationState): JSONObject = JSONObject()
    .put("state", activation.phase.name.lowercase())
    .put("generation", activation.generation)
    .put("previous", activation.previous?.let(::selectionJson) ?: JSONObject.NULL)
    .put("desired", activation.desired?.let(::selectionJson) ?: JSONObject.NULL)
    .put("message", activation.message ?: JSONObject.NULL)
    .putPresentation(activation.presentation)

private fun selectionJson(selection: ProfileSelection): JSONObject = when (selection) {
    ProfileSelection.Auto -> JSONObject().put("mode", "auto")
    is ProfileSelection.Pinned -> JSONObject().put("mode", "pinned").put("ref", refJson(selection.ref))
}

private fun summaryJson(summary: ProfileSummary): JSONObject = JSONObject()
    .put("ref", refJson(summary.ref))
    .put("display_name", summary.displayName)
    .put("origin", summary.origin.name.lowercase())
    .put("schema", summary.schema)
    .put("content_version", summary.contentVersion)
    .put("imported_at", summary.importedAtEpochMs ?: JSONObject.NULL)
    .put("author", summary.author ?: JSONObject.NULL)
    .put("maturity", summary.maturity.name.lowercase())
    .put("trusted_provenance", summary.trustedProvenance)
    .put("min_core_version", summary.minCoreVersion ?: JSONObject.NULL)
    .put("matches_this_device", summary.matchesThisDevice)
    .put("active", summary.active)
    .put("selected", summary.selected)
    .put("shizuku_recommendation", summary.shizukuRecommendation.name.lowercase())
    .put("compatible", summary.compatible)
    .put("issues", JSONArray(summary.issues.map(::issueJson)))
    .put("risks", JSONArray(summary.risks.map { it.name.lowercase() }.sorted()))
    .put("soc", summary.soc?.let(::socJson) ?: JSONObject.NULL)
    .put("links", JSONArray(summary.links.takeIf { summary.compatible }.orEmpty().map { link ->
        JSONObject().put("label", link.label).put("url", link.url)
    }))

private fun socJson(soc: ProfileSoc): JSONObject = JSONObject()
    .put("model", soc.model)
    .put("introduced_year", soc.introducedYear ?: JSONObject.NULL)
    .put("cpu_cores", JSONArray(soc.cpuCores.map { cluster ->
        JSONObject().put("architecture", cluster.architecture).put("count", cluster.count)
    }))

private fun previewJson(preview: io.panelassistant.android.device.profile.ProfilePreview): JSONObject = JSONObject()
    .put("preview_token", preview.previewToken ?: JSONObject.NULL)
    .put("content_sha256", preview.contentSha256)
    .put("expires_at_epoch_ms", preview.expiresAtEpochMs ?: JSONObject.NULL)
    .put("summary", preview.summary?.let(::summaryJson) ?: JSONObject.NULL)
    .put("issues", JSONArray(preview.issues.map(::issueJson)))
    .put("diff_from_active", JSONArray(preview.diffFromActive.map(::diffJson)))
    .put("compatible", preview.compatible)

private fun mutationJson(mutation: ProfileMutation): JSONObject = when (mutation) {
    is ProfileMutation.Success -> JSONObject()
        .put("ok", true)
        .put("message", mutation.message)
        .putPresentation(mutation.presentation)
        .put("restart_required", mutation.restartRequired)
        .put("catalog_revision", mutation.status.catalogRevision)
        .put("status", statusJson(mutation.status))
    is ProfileMutation.Rejected -> JSONObject()
        .put("ok", false)
        .put("error", "profile-mutation-rejected")
        .put("catalog_revision", mutation.status.catalogRevision)
        .put("status", statusJson(mutation.status))
        .put("issues", JSONArray(mutation.issues.map(::issueJson)))
}

private fun issueJson(issue: ProfileIssue): JSONObject = JSONObject()
    .put("severity", issue.severity.name.lowercase())
    .put("path", issue.path)
    .put("message", issue.message)
    .putPresentation(issue.presentation)

private fun JSONObject.putPresentation(presentation: ProfilePresentation?): JSONObject = apply {
    if (presentation != null) {
        put("presentation_code", presentation.code)
        put("presentation_params", JSONObject(presentation.params))
    }
}

private fun diffJson(diff: ProfileDiff): JSONObject = JSONObject()
    .put("path", diff.path)
    .put("before", diff.before ?: JSONObject.NULL)
    .put("after", diff.after ?: JSONObject.NULL)

private fun refJson(ref: ProfileRef): JSONObject = JSONObject().put("id", ref.id).put("revision", ref.revision)

private fun reportJson(report: PassiveProfileReport): JSONObject = JSONObject()
    .put("generated_at_epoch_ms", report.generatedAtEpochMs)
    .put("facts", JSONObject()
        .put("model", report.facts.model)
        .put("device", report.facts.device)
        .put("product_version", report.facts.productVersion))
    .put("items", JSONArray(report.observations.map { observation ->
        JSONObject()
            .put("path", observation.path)
            .put("value", observation.value ?: JSONObject.NULL)
            .put("source", observation.source)
            .put("status", observation.confidence.name.lowercase())
            .put("note", observation.note ?: JSONObject.NULL)
    }))
    .put("issues", JSONArray(report.issues.map(::issueJson)))

private fun safeFileStem(value: String): String = value.replace(Regex("[^A-Za-z0-9._-]+"), "-").take(128)
