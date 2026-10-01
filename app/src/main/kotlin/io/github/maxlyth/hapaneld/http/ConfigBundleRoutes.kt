package io.github.maxlyth.hapaneld.http

import io.github.maxlyth.hapaneld.canonicalHaOrigin
import io.github.maxlyth.hapaneld.Config
import io.github.maxlyth.hapaneld.DashboardEntityBackupState
import io.github.maxlyth.hapaneld.config.ConfigBundle
import io.github.maxlyth.hapaneld.config.Migrations
import io.github.maxlyth.hapaneld.config.Scope
import io.github.maxlyth.hapaneld.config.SettingValue
import io.github.maxlyth.hapaneld.config.SettingsRegistry
import io.github.maxlyth.hapaneld.config.Validation
import io.github.maxlyth.hapaneld.security.SensitiveOperation
import io.github.maxlyth.hapaneld.util.LogShipEndpoint
import io.github.maxlyth.hapaneld.util.Json
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.Route
import org.json.JSONObject
import io.github.maxlyth.hapaneld.http.ConfigValueProjection.Companion.ENTITY_REVISION_PREFIX

/** Versioned config exchange and revision rollback use the same accepted-config transaction. */
internal class ConfigBundleRoutes(
    private val config: Config,
    private val revisions: RevisionStore,
    private val values: ConfigValueProjection,
    private val transaction: AcceptedConfigTransaction,
    private val authorizeSensitive: suspend (ApplicationCall, SensitiveOperation, String, String) -> Boolean,
    private val rejectHardenedNetworkAdb: suspend (ApplicationCall, String?) -> Boolean,
    private val planEntityBackup: (org.json.JSONObject) -> DashboardEntityBackupState,
) {
    /** Export a versioned config bundle. Secrets are excluded unless `?include_secrets=1`. */
    suspend fun handleConfigExport(call: ApplicationCall) {
        val includeSecrets = call.request.queryParameters["include_secrets"] == "1"
        if (includeSecrets && !authorizeSensitive(
                call,
                SensitiveOperation.CONFIG_SECRET_EXPORT,
                exactHttpApprovalPayload(call, sha256Hex(ByteArray(0))),
                "Export settings including stored credentials",
            )
        ) return
        val live = values.liveValues()
        val values = projectConfigSnapshot(
            specs = SettingsRegistry.settable().filter { includeSecrets || !it.secret },
            zigbeeRouterConfigured = config.zigbeeRouterConfigured,
            effectiveValue = { values.effectiveValue(it, live) },
        )
        SettingsRegistry.SPECS.filter { it.ha != null }.forEach { spec ->
            values[SettingsRegistry.exposureKey(spec)] = config.haExposed(spec.key, spec.haExposedByDefault).toString()
        }
        val bundle = ConfigBundle.fromValues(
            values, exportedAt = System.currentTimeMillis().toString(), exportedBy = config.panelId,
        )
        call.response.headers.append("Content-Disposition", "attachment; filename=\"${config.panelId}-config.json\"")
        call.respondText(bundle.serialize(), ContentType.Application.Json)
    }

    /**
     * Bundle import — BEST-EFFORT by design (a bundle exported from different hardware or a different
     * ha-paneld version must still restore what it can). Parse → migrate to the current schema →
     * scope/secret filter (`?mode=fleet` applies only PORTABLE, non-secret keys; default `restore`
     * applies everything) → validate per-key against the registry: valid keys apply, invalid keys are
     * reported in `errors` and skipped, unknown keys warn and skip. `?strict=1` restores the old
     * all-or-nothing validation behaviour. Apply is ordered in two phases: atomically commit ordinary
     * preferences, then apply controller/hardware-backed live settings and reconfigure. The latter
     * cannot be rolled back across Android settings, sysfs, services, and hardware. `?dry_run=1`
     * returns the diff without writing.
     * Status: "applied" (all valid), "partial" (some skipped as invalid), "rejected" (nothing usable
     * or strict mode with any error).
     */
    suspend fun handleConfigImport(call: ApplicationCall) {
        val bodyBytes = when (val receipt = receiveBoundedBody(call, PaneldServer.MAX_CONFIG_IMPORT_BYTES)) {
            is BoundedBodyReceipt.Received -> receipt.bytes
            BoundedBodyReceipt.TooLarge -> {
                call.respondText("""{"status":"too-large"}""", ContentType.Application.Json, HttpStatusCode.PayloadTooLarge)
                return
            }
            BoundedBodyReceipt.TimedOut -> {
                call.respondText("""{"status":"timeout"}""", ContentType.Application.Json, HttpStatusCode.RequestTimeout)
                return
            }
        }
        val body = String(bodyBytes, Charsets.UTF_8)
        val bundle = ConfigBundle.parse(body)
        if (bundle == null) {
            call.respondText("""{"status":"bad-bundle"}""", ContentType.Application.Json, HttpStatusCode.BadRequest)
            return
        }
        if (bundle.kind != ConfigBundle.KIND_CONFIG || bundle.schema < 1) {
            call.respondText("""{"status":"wrong-kind-or-schema"}""", ContentType.Application.Json, HttpStatusCode.BadRequest)
            return
        }
        val (migrated, warnings) = Migrations.migrate(bundle.schema, bundle.values)
        val fleet = call.request.queryParameters["mode"] == "fleet"
        val dryRun = call.request.queryParameters["dry_run"] == "1"
        val expectedConfig = call.request.queryParameters["expected_cfg"]?.trim().orEmpty()
        if (expectedConfig.isNotEmpty() && !expectedConfig.matches(Regex("^[a-f0-9]{8}$"))) {
            call.respondText(
                """{"status":"bad-expected-cfg"}""",
                ContentType.Application.Json,
                HttpStatusCode.BadRequest,
            )
            return
        }
        val accepted = LinkedHashMap<String, String>()
        val skipped = ArrayList<String>()
        val errors = ArrayList<String>()
        val warn = warnings.toMutableList()
        for ((key, raw) in migrated) {
            val spec = SettingsRegistry.spec(key)
            val exposedSpec = SettingsRegistry.parseExposure(key)
            if (exposedSpec != null) {
                val normalized = SettingValue.parseBool(raw)?.toString()
                if (normalized == null) errors.add("$key: expected a boolean") else accepted[key] = normalized
                continue
            }
            if (spec == null) { warn.add("unknown key skipped: $key"); continue }
            if (spec.readOnly || spec.transient) { skipped.add(key); continue }
            if (fleet && (spec.scope != Scope.PORTABLE || spec.secret)) { skipped.add(key); continue }
            // Same rule the upgrade and the restore apply: a value an older release was allowed to store
            // is read in the form the current validator understands, so an import of an older export
            // carries it across instead of silently dropping it. A route naming a different server is
            // still refused here, exactly as it is there.
            when (val v = SettingValue.validate(spec, restorableSettingValue(key, raw, canonicalHaOrigin(config.haUrl)))) {
                is Validation.Ok -> {
                    // A blank portable HA URL means “renderer not configured” on the source panel. In
                    // fleet mode it must not clear a target's URL and, through import dependencies, its
                    // device-local OAuth credentials. A non-blank common endpoint remains portable.
                    if (fleetImportPreservesTargetLocalValue(fleet, key, v.normalized)) {
                        skipped.add(key)
                        warn.add("blank ha_url skipped in fleet mode to preserve target-local Home Assistant login")
                    } else {
                        accepted[key] = v.normalized
                    }
                }
                is Validation.Bad -> errors.add(v.reason)
            }
        }
        if (preserveUnconfiguredZigbeeOwnership(accepted, config.zigbeeRouterConfigured)) {
            skipped.add("zigbee_router")
            warn.add("legacy zigbee_router=false skipped to preserve untouched vendor gateway ownership")
        }
        // Canonicalise the sink triple before it is previewed OR applied, so a dry run cannot advertise
        // a destination the apply would not write. Applying it here is not what makes the stored fields
        // consistent — Config.stageImportDependencies does that for every applyAccepted path — but doing
        // it before the branch keeps preview and apply the same operation on the same values.
        LogShipEndpoint.canonicalUpdate(accepted, config.logShipHost, config.logShipPort, config.logShipProtocol)
            ?.let { accepted.putAll(it) }
        val strict = call.request.queryParameters["strict"] == "1"
        if ((strict && errors.isNotEmpty()) || (accepted.isEmpty() && errors.isNotEmpty())) {
            call.respondText(importJson("rejected", emptyList(), skipped, warn, errors), ContentType.Application.Json, HttpStatusCode.UnprocessableEntity)
            return
        }
        SettingsRegistry.automaticBrightnessBoundsError(
            accepted, config.autoBrightnessMinimumPercent, config.autoBrightnessMaximumPercent,
        )?.let { reason ->
            call.respondText(importJson("rejected", emptyList(), skipped, warn, errors + reason), ContentType.Application.Json, HttpStatusCode.UnprocessableEntity)
            return
        }
        if (dryRun) {
            val current = values.currentValues()
            call.respondText(
                configDryRunJson(
                    configPreviewDiff(current, accepted),
                    skipped,
                    warn + errors.map { "would skip (invalid): $it" },
                    configConcurrencyHash(current),
                ),
                ContentType.Application.Json,
            )
            return
        }
        if (rejectHardenedNetworkAdb(call, accepted["network_adb"])) return
        if (accepted.isEmpty()) {
            call.respondText(importJson("no-op", emptyList(), skipped, warn, errors), ContentType.Application.Json)
            return
        }
        val importDigest = sha256Hex(bodyBytes)
        if (!authorizeSensitive(
                call,
                SensitiveOperation.CONFIG_IMPORT,
                exactHttpApprovalPayload(call, importDigest),
                "Import ${accepted.size} panel setting${if (accepted.size == 1) "" else "s"}",
            )
        ) return
        when (val applyResult = transaction.applyAccepted(accepted, expectedConfig.ifEmpty { null })) {
            ApplyAcceptedResult.Stale -> {
                val actual = configConcurrencyHash(values.currentValues())
                call.respondText(
                    """{"status":"stale-preview","expected_cfg":${jsonStr(expectedConfig)},"actual_cfg":${jsonStr(actual)}}""",
                    ContentType.Application.Json,
                    HttpStatusCode.Conflict,
                )
                return
            }
            ApplyAcceptedResult.CommitFailed -> {
                call.respondText(
                    importJson("error", emptyList(), skipped, warn, listOf("configuration commit failed")),
                    ContentType.Application.Json,
                    HttpStatusCode.InternalServerError,
                )
                return
            }
            is ApplyAcceptedResult.CompatibilityRefused -> {
                call.respondText(
                    importJson("database-compatibility-refused", emptyList(), skipped, warn, listOf(applyResult.message)),
                    ContentType.Application.Json,
                    HttpStatusCode.Conflict,
                )
                return
            }
            ApplyAcceptedResult.Applied -> Unit
        }
        val status = if (errors.isEmpty()) "applied" else "partial"
        call.respondText(importJson(status, accepted.keys.toList(), skipped, warn, errors), ContentType.Application.Json)
    }

    /** List on-panel revisions (newest first) as `[{id, exported_at, keys}]`. */
    fun revisionsJson(): String =
        "[" + revisions.list().joinToString(",") { (id, b) ->
            "{\"id\":$id,\"exported_at\":\"${b.exportedAt}\",\"keys\":${b.values.size}}"
        } + "]"

    /** Roll back to a stored revision (itself recorded as a new revision, so restores are undoable). */
    suspend fun handleRevisionRestore(call: ApplicationCall, id: Long) {
        val bundle = revisions.get(id)
        if (bundle == null) {
            call.respondText("""{"status":"not-found"}""", ContentType.Application.Json, HttpStatusCode.NotFound)
            return
        }
        val entityState = revisionEntityState(bundle.values)
        val ordinaryValues = bundle.values.filterKeys { !it.startsWith("$ENTITY_REVISION_PREFIX.") }
        val (migrated, _) = Migrations.migrate(bundle.schema, ordinaryValues)
        val accepted = LinkedHashMap<String, String>()
        for ((key, raw) in migrated) {
            if (SettingsRegistry.parseExposure(key) != null) {
                SettingValue.parseBool(raw)?.let { accepted[key] = it.toString() }
                continue
            }
            val spec = SettingsRegistry.spec(key) ?: continue
            if (spec.readOnly || spec.transient) continue
            (SettingValue.validate(spec, raw) as? Validation.Ok)?.let { accepted[key] = it.normalized }
        }
        preserveUnconfiguredZigbeeOwnership(accepted, config.zigbeeRouterConfigured)
        if (rejectHardenedNetworkAdb(call, accepted["network_adb"])) return
        val revisionDigest = sha256Hex(bundle.serialize().toByteArray(Charsets.UTF_8))
        if (!authorizeSensitive(
                call,
                SensitiveOperation.CONFIG_IMPORT,
                exactHttpApprovalPayload(call, revisionDigest),
                "Restore stored configuration revision $id",
            )
        ) return
        if (entityState == null && (
                accepted["dashboard_entity_overrides"].orEmpty().isNotBlank() ||
                    accepted["dashboard_entity_learning_applied"] == "true"
                )
        ) {
            call.respondText(
                importJson("rejected", emptyList(), emptyList(), emptyList(), listOf("revision lacks entity owner metadata")),
                ContentType.Application.Json,
                HttpStatusCode.UnprocessableEntity,
            )
            return
        }
        val applied = transaction.applyAccepted(accepted, entityState = entityState)
        if (applied != ApplyAcceptedResult.Applied) {
            call.respondText(
                importJson(
                    if (applied is ApplyAcceptedResult.CompatibilityRefused) "database-compatibility-refused" else "error",
                    emptyList(), emptyList(), emptyList(),
                    listOf(
                        if (applied is ApplyAcceptedResult.CompatibilityRefused) {
                            applied.message
                        } else "configuration commit failed",
                    ),
                ),
                ContentType.Application.Json,
                if (applied is ApplyAcceptedResult.CompatibilityRefused) HttpStatusCode.Conflict
                else HttpStatusCode.InternalServerError,
            )
            return
        }
        call.respondText(importJson("restored", accepted.keys.toList(), emptyList(), emptyList(), emptyList()), ContentType.Application.Json)
    }

    private fun revisionEntityState(values: Map<String, String>): DashboardEntityBackupState? {
        val fields = values.filterKeys { it.startsWith("$ENTITY_REVISION_PREFIX.") }
        if (fields.isEmpty()) return null
        val obj = org.json.JSONObject()
        for ((key, value) in fields) {
            val name = key.removePrefix("$ENTITY_REVISION_PREFIX.")
            obj.put(name, if (name == "filter_enabled" || name == "learning_applied") {
                SettingValue.parseBool(value) ?: return null
            } else value)
        }
        return runCatching { planEntityBackup(obj) }.getOrNull()
    }

    private fun importJson(status: String, applied: List<String>, skipped: List<String>, warnings: List<String>, errors: List<String>): String =
        "{\"status\":\"$status\",\"applied\":${jarr(applied)},\"skipped\":${jarr(skipped)},\"warnings\":${jarr(warnings)},\"errors\":${jarr(errors)}}"

    private fun configConcurrencyHash(values: Map<String, String>): String =
        io.github.maxlyth.hapaneld.config.ConfigHash.of(configConcurrencyValues(values))

    private fun jsonStr(value: String): String = Json.str(value)

    private fun jarr(items: List<String>): String =
        "[" + items.joinToString(",") { Json.str(it) } + "]"
}

internal fun Route.configBundleRoutes(owner: () -> ConfigBundleRoutes) {
    // Versioned config bundle: backup (export) and validated restore/deploy (import).
    get("/config/export") { owner().handleConfigExport(call) }
    post("/config/import") { owner().handleConfigImport(call) }
    // On-panel revision history + rollback.
    get("/config/revisions") { call.respondText(owner().revisionsJson(), ContentType.Application.Json) }
    post("/config/revisions/{id}/restore") {
        val id = call.parameters["id"]?.toLongOrNull()
        if (id == null) call.respondText("bad-id\n", status = HttpStatusCode.BadRequest)
        else owner().handleRevisionRestore(call, id)
    }
}
