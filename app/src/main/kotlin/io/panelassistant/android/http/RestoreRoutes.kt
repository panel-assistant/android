package io.panelassistant.android.http

import io.panelassistant.android.canonicalHaOrigin
import io.panelassistant.android.Config
import io.panelassistant.android.planOwnerChoices
import io.panelassistant.android.config.Capabilities
import io.panelassistant.android.config.SettingsRegistry
import io.panelassistant.android.haUrl
import io.panelassistant.android.panelAssistantDiscoveryId
import io.panelassistant.android.backup.PanelBackup
import io.panelassistant.android.backup.CompanionRestore
import io.panelassistant.android.device.profile.ProfileAdmin
import io.panelassistant.android.device.profile.ProfileBackup
import io.panelassistant.android.persistence.ConfigVault
import io.panelassistant.android.persistence.StateArchiveSection
import io.panelassistant.android.persistence.BackupIdentity
import io.panelassistant.android.persistence.ProximityCalibrationBackup
import io.panelassistant.android.persistence.StateBackupPolicy
import io.panelassistant.android.security.SensitiveOperation
import io.panelassistant.android.util.BoundedStreams
import io.panelassistant.android.util.isLoopbackPeer
import io.panelassistant.android.util.ByteLimitExceeded
import io.panelassistant.android.util.InstallPresentation
import io.panelassistant.android.util.InstallProgress
import io.panelassistant.android.util.Json
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

internal class RestoreRoutes(
    private val cacheDir: File,
    private val config: Config,
    private val reader: BackupArchiveReader,
    private val profileAdmin: ProfileAdmin?,
    private val ensureCompanionHelper: () -> Boolean,
    private val authorizeSensitive: suspend (OpCall, SensitiveOperation, String, String) -> Boolean,
    private val rejectHardenedNetworkAdb: suspend (OpCall, String?) -> Boolean,
    private val capabilities: () -> Capabilities,
    private val scope: CoroutineScope,
    private val executor: RestoreExecutor,
    private val wakeWords: io.panelassistant.android.assist.wakeword.WakeWordCatalog?,
    private val companionHelperReady: () -> Boolean = { false },
) {

    /** Restore endpoint: decrypt + validate; ?dry_run=1 reports contents without writing. A real restore is
     *  DESTRUCTIVE (config rewrite + Companion force-stop/rewrite), run off-thread with InstallProgress. */
    suspend fun handle(call: OpCall) {
        // Over HTTP the passphrase rides a header so it never lands in a query log; a managed request carries
        // it as `args.passphrase` and the archive as its `args.blob` (protocol section 20.5).
        val pw = call.args?.optString("passphrase")?.takeIf(String::isNotEmpty)
            ?: call.request.headers["X-Backup-Passphrase"].orEmpty()
        val dryRun = call.request.queryParameters["dry_run"] == "1"
        // Claim the shared destructive-operation lane before buffering, decrypting, or parsing a bundle.
        // Otherwise several losing requests can each consume 64 MiB and expensive KDF/JSON work before
        // discovering that another restore/install already owns admission.
        val progress = InstallProgress.start(
            if (dryRun) "Restore preview" else "Restore",
            InstallPresentation(
                "operation-working",
                mapOf("owner" to if (dryRun) "restore-preview" else "restore"),
            ),
        )
            ?: return call.respondText(
                """{"status":"busy"}""",
                ContentType.Application.Json,
                HttpStatusCode.Conflict,
            )
        var transferredToJob = false
        var requestAccepted = false
        val restoreFiles = ArrayList<File>(4)
        var retainedCompanionPlan: CompanionRestore.Plan? = null
        try {
            val receivedFile = File.createTempFile("panel-restore-", ".upload", cacheDir).also(restoreFiles::add)
            val stagingLimit = restoreBodyStagingLimit(cacheDir.usableSpace)
            val declaredBytes = call.request.headers["Content-Length"]?.toLongOrNull()
            if (stagingLimit <= 0L || (declaredBytes != null && declaredBytes > stagingLimit)) {
                return call.respondText(
                    """{"ok":false,"error":"insufficient-storage"}""",
                    ContentType.Application.Json,
                    HttpStatusCode.InsufficientStorage,
                )
            }
            try {
                withContext(Dispatchers.IO) {
                    call.receiveStream().use { input ->
                        receivedFile.outputStream().use { output ->
                            DeadlineBoundedBody.copy(
                                input,
                                output,
                                stagingLimit,
                                RESTORE_BODY_RECEIPT_DEADLINE_MS,
                            )
                        }
                    }
                }
            } catch (_: ByteLimitExceeded) {
                val storageBound = stagingLimit < MAX_RESTORE_BYTES
                return call.respondText(
                    if (storageBound) """{"ok":false,"error":"insufficient-storage"}"""
                    else """{"ok":false,"error":"bundle-too-large"}""",
                    ContentType.Application.Json,
                    if (storageBound) HttpStatusCode.InsufficientStorage else HttpStatusCode.PayloadTooLarge,
                )
            } catch (_: BodyReceiptTimeout) {
                return call.respondText(
                    """{"ok":false,"error":"bundle-timeout"}""",
                    ContentType.Application.Json,
                    HttpStatusCode.RequestTimeout,
                )
            }
            if (receivedFile.length() <= 0L) return call.respondText(
                withInstallPresentation(
                    """{"ok":false,"error":"not a Panel Assistant backup"}""",
                    InstallPresentation("restore-not-panel-backup"),
                ),
                ContentType.Application.Json,
                HttpStatusCode.BadRequest,
            )
            // GCM decryption writes to a private temporary file and the tag must authenticate fully before
            // any JSON is parsed or a restore plan can be applied.
            val plainFile = if (PanelBackup.isSealed(receivedFile)) {
                if (pw.isEmpty()) return call.respondText(
                    withInstallPresentation(
                        """{"ok":false,"error":"this bundle is encrypted — enter its passphrase"}""",
                        InstallPresentation("restore-passphrase-required"),
                    ),
                    ContentType.Application.Json,
                    HttpStatusCode.BadRequest,
                )
                val decrypted = File.createTempFile("panel-restore-", ".plain", cacheDir).also(restoreFiles::add)
                val opened = try {
                    withContext(Dispatchers.IO) {
                        receivedFile.inputStream().use { input ->
                            decrypted.outputStream().use { output ->
                                PanelBackup.open(input, output, pw, MAX_RESTORE_BYTES)
                            }
                        }
                    }
                } catch (_: ByteLimitExceeded) {
                    return call.respondText(
                        """{"ok":false,"error":"bundle-too-large"}""",
                        ContentType.Application.Json,
                        HttpStatusCode.PayloadTooLarge,
                    )
                }
                if (!opened) return call.respondText(
                    withInstallPresentation(
                        """{"ok":false,"error":"wrong passphrase or corrupt bundle"}""",
                        InstallPresentation("restore-passphrase-or-bundle-invalid"),
                    ),
                    ContentType.Application.Json,
                    HttpStatusCode.BadRequest,
                )
                decrypted
            } else receivedFile
            val archiveManifest = PanelBackup.readManifest(plainFile, MAX_BACKUP_MANIFEST_BYTES)
            // Legacy v1 embeds Companion files as base64 inside one JSON object. JSONObject necessarily
            // holds both the source text and parsed strings, so keep that compatibility path under a
            // much smaller semantic ceiling. v2 archives carry large payloads as streamed ZIP entries.
            if (archiveManifest == null && plainFile.length() > MAX_LEGACY_BACKUP_JSON_BYTES) {
                return call.respondText(
                    withInstallPresentation(
                        """{"ok":false,"error":"legacy backup is too large; create a new backup before restoring"}""",
                        InstallPresentation("restore-legacy-too-large"),
                    ),
                    ContentType.Application.Json,
                    HttpStatusCode.PayloadTooLarge,
                )
            }
            val obj = runCatching {
                val json = archiveManifest ?: String(
                    plainFile.inputStream().use { BoundedStreams.readBytes(it, MAX_LEGACY_BACKUP_JSON_BYTES) },
                    Charsets.UTF_8,
                )
                org.json.JSONObject(json)
            }.getOrNull()
            if (obj == null || obj.optString("kind") != "ha-paneld-backup") {
                return call.respondText(
                    withInstallPresentation(
                        """{"ok":false,"error":"not a Panel Assistant backup"}""",
                        InstallPresentation("restore-not-panel-backup"),
                    ),
                    ContentType.Application.Json,
                    HttpStatusCode.BadRequest,
                )
            }
            val cfgObj = obj.optJSONObject("config")
                ?: return call.respondText(
                    withInstallPresentation(
                        """{"ok":false,"error":"backup contains no config object"}""",
                        InstallPresentation("restore-config-missing"),
                    ),
                    ContentType.Application.Json,
                    HttpStatusCode.BadRequest,
                )
            val backupSchema = obj.optInt("schema", -1)
            if (backupSchema < 1) return call.respondText(
                withInstallPresentation(
                    """{"ok":false,"error":"backup contains no valid schema"}""",
                    InstallPresentation("restore-schema-missing"),
                ),
                ContentType.Application.Json,
                HttpStatusCode.BadRequest,
            )
            val configPlan = planRestoreConfig(
                cfgObj, backupSchema, { canonicalHaOrigin(config.haUrl) }, { config.zigbeeRouterConfigured },
            ).let { plan ->
                SettingsRegistry.automaticBrightnessBoundsError(
                    plan.values, config.autoBrightnessMinimumPercent, config.autoBrightnessMaximumPercent,
                )?.let { plan.copy(errors = plan.errors + it) } ?: plan
            }
            if (configPlan.errors.isNotEmpty()) {
                return call.respondText(
                    withInstallPresentation(
                        """{"ok":false,"error":"invalid backup config","errors":${jarr(configPlan.errors)}}""",
                        InstallPresentation("restore-config-invalid"),
                    ),
                    ContentType.Application.Json,
                    HttpStatusCode.UnprocessableEntity,
                )
            }
            val entityObj = obj.optJSONObject("entity_state")
            if (obj.has("entity_state") && entityObj == null) return call.respondText(
                withInstallPresentation(
                    """{"ok":false,"error":"invalid entity_state object"}""",
                    InstallPresentation("restore-entity-object-invalid"),
                ),
                ContentType.Application.Json,
                HttpStatusCode.BadRequest,
            )
            val profilesObj = obj.optJSONObject("profiles")
            if (obj.has("profiles") && profilesObj == null) return call.respondText(
                withInstallPresentation(
                    """{"ok":false,"error":"invalid profiles object"}""",
                    InstallPresentation("restore-profiles-object-invalid"),
                ),
                ContentType.Application.Json,
                HttpStatusCode.BadRequest,
            )
            val comp = obj.optJSONObject("companion")
            if (obj.has("companion") && comp == null) {
                return call.respondText(
                    withInstallPresentation(
                        """{"ok":false,"error":"Invalid Companion restore section"}""",
                        InstallPresentation("restore-companion-section-invalid"),
                    ),
                    ContentType.Application.Json,
                    HttpStatusCode.BadRequest,
                )
            }
            val stateObj = obj.optJSONObject("state")
            if (obj.has("state") && stateObj == null) return call.respondText(
                withInstallPresentation(
                    """{"ok":false,"error":"invalid state object"}""",
                    InstallPresentation("restore-state-object-invalid"),
                ),
                ContentType.Application.Json,
                HttpStatusCode.BadRequest,
            )
            // Resolve the section before anything reads it. A `state` object that declares neither a
            // payload nor a capture failure cannot be resolved to "nothing to restore" — that silence is
            // the defect this marker exists to remove.
            val stateDisposition = StateArchiveSection.restoreStateDisposition(stateObj)
                ?: return call.respondText(
                    withInstallPresentation(
                        """{"ok":false,"error":"invalid state object"}""",
                        InstallPresentation("restore-state-object-invalid"),
                    ),
                    ContentType.Application.Json,
                    HttpStatusCode.BadRequest,
                )
            val stateUnavailable = stateDisposition == StateArchiveSection.Disposition.INCOMPLETE
            // Imported wake words exist only as archive entries; a section anywhere else is malformed.
            val wakeWordsObj = obj.optJSONObject("wake_words")
            if (obj.has("wake_words") && (wakeWordsObj == null || archiveManifest == null)) return call.respondText(
                withInstallPresentation(
                    """{"ok":false,"error":"invalid wake_words object"}""",
                    InstallPresentation("restore-archive-metadata-invalid"),
                ),
                ContentType.Application.Json,
                HttpStatusCode.BadRequest,
            )
            val archiveEntries = if (archiveManifest != null) {
                runCatching { declaredArchiveEntries(entityObj, profilesObj, comp, stateObj, wakeWordsObj) }.getOrNull()
                    ?: return call.respondText(
                        withInstallPresentation(
                            """{"ok":false,"error":"invalid backup archive metadata"}""",
                            InstallPresentation("restore-archive-metadata-invalid"),
                        ),
                        ContentType.Application.Json,
                        HttpStatusCode.BadRequest,
                    )
            } else emptySet()
            if (archiveManifest != null && !PanelBackup.extractArchive(plainFile, emptyList(), archiveEntries)) {
                return call.respondText(
                    withInstallPresentation(
                        """{"ok":false,"error":"backup archive contains missing or unexpected files"}""",
                        InstallPresentation("restore-archive-entries-invalid"),
                    ),
                    ContentType.Application.Json,
                    HttpStatusCode.BadRequest,
                )
            }
            val entityState = entityObj?.let {
                runCatching {
                    if (archiveManifest != null && it.has("filter_ids_entry")) {
                        reader.planEntityArchive(it, plainFile, archiveEntries)
                    } else {
                        planEntityBackup(it)
                    }
                }.getOrNull()
            }
            if (entityObj != null && entityState == null) return call.respondText(
                withInstallPresentation(
                    """{"ok":false,"error":"invalid owner-scoped entity state"}""",
                    InstallPresentation("restore-entity-state-invalid"),
                ),
                ContentType.Application.Json,
                HttpStatusCode.UnprocessableEntity,
            )
            if (entityState == null && (
                    configPlan.values["dashboard_entity_overrides"].orEmpty().isNotBlank() ||
                        configPlan.values["dashboard_entity_learning_applied"] == "true"
                    )
            ) return call.respondText(
                withInstallPresentation(
                    """{"ok":false,"error":"entity state is missing its owner namespace"}""",
                    InstallPresentation("restore-entity-owner-missing"),
                ),
                ContentType.Application.Json,
                HttpStatusCode.UnprocessableEntity,
            )
            // New archives use the health protocol's reinstall link, sampled before configuration changes.
            // Older archives retain their historical pre-restore panel-name classification.
            val samePanel = BackupIdentity.sameRestoreDevice(
                obj,
                config.androidId.takeIf(String::isNotBlank)?.let(::panelAssistantDiscoveryId),
                obj.optString("panel_id").let { it.isNotEmpty() && it == config.panelId },
            )
            val restorableCalibrations = ProximityCalibrationBackup.restorable(obj, samePanel)
            var archivedState = emptyList<ConfigVault.StateRow>()
            val restorableState = if (
                archiveManifest != null && stateDisposition == StateArchiveSection.Disposition.RESTORABLE
            ) {
                runCatching {
                    val ref = archiveTextRef(
                        stateObj,
                        "entry",
                        "size",
                        STATE_BACKUP_ENTRY,
                        MAX_STATE_BACKUP_BYTES,
                        allowEmpty = false,
                    )
                    val decoded = ConfigVault.decode(
                        reader.readArchiveText(plainFile, ref, archiveEntries, "app-state-restore-"),
                    ) ?: throw IllegalArgumentException("corrupt app_state payload")
                    archivedState = decoded.rows
                    StateBackupPolicy.restorableRows(decoded.rows, samePanel)
                }.getOrNull() ?: return call.respondText(
                    withInstallPresentation(
                        """{"ok":false,"error":"invalid app_state payload"}""",
                        InstallPresentation("restore-app-state-invalid"),
                    ),
                    ContentType.Application.Json,
                    HttpStatusCode.UnprocessableEntity,
                )
            } else emptyList()
            val ownerProfile = obj.optJSONObject("owner_profile")
            val profileMatches = config.profile?.let { profile ->
                ownerProfile?.optString("id") == profile.id && ownerProfile?.optString("revision") == profile.revision
            } == true
            val ownerChoices = runCatching { config.planOwnerChoices(archivedState, samePanel, profileMatches) }
                .getOrNull() ?: return call.respondText(
                    """{"ok":false,"error":"invalid owner choices"}""",
                    ContentType.Application.Json,
                    HttpStatusCode.UnprocessableEntity,
                )
            val archivedIssues = runCatching {
                io.panelassistant.android.persistence.DashboardIssueBackup.decode(obj)
            }.getOrNull() ?: return call.respondText(
                """{"ok":false,"error":"invalid dashboard ignored findings"}""",
                ContentType.Application.Json,
                HttpStatusCode.UnprocessableEntity,
            )
            val restorableIssues = io.panelassistant.android.persistence.DashboardIssueBackup.admitted(archivedIssues, entityState)
            val stateSkipped = ownerChoices.skipped.size + archivedIssues.size - restorableIssues.size +
                (StateBackupPolicy.restorableRows(archivedState, true).size -
                    StateBackupPolicy.restorableRows(archivedState, samePanel).size)
            val profilePayload = profilesObj?.let {
                if (archiveManifest != null && it.has("entry")) {
                    reader.readProfileArchive(it, plainFile, archiveEntries)
                } else {
                    ProfileBackup.fromJson(it)
                }
            }
            if (profilesObj != null && profilePayload == null) return call.respondText(
                withInstallPresentation(
                    """{"ok":false,"error":"invalid profile archive entry"}""",
                    InstallPresentation("restore-profile-archive-invalid"),
                ),
                ContentType.Application.Json,
                HttpStatusCode.BadRequest,
            )
            if (profilePayload != null && profilePayload.payload == null) return call.respondText(
                withInstallPresentation(
                    """{"ok":false,"error":"invalid profile catalog","errors":${jarr(profilePayload.issues.map(::profileIssueText))}}""",
                    InstallPresentation("restore-profile-catalog-invalid"),
                ),
                ContentType.Application.Json,
                HttpStatusCode.UnprocessableEntity,
            )
            if (profilePayload?.payload != null && profileAdmin == null) return call.respondText(
                withInstallPresentation(
                    """{"ok":false,"error":"profile catalog restore is unavailable"}""",
                    InstallPresentation("restore-profile-restore-unavailable"),
                ),
                ContentType.Application.Json,
                HttpStatusCode.ServiceUnavailable,
            )
            val profilePlan = profilePayload?.payload?.let { requireNotNull(profileAdmin).planBackupRestore(it) }
            if (profilePlan != null && !profilePlan.valid) return call.respondText(
                withInstallPresentation(
                    """{"ok":false,"error":"profile catalog is not restorable","errors":${jarr(profilePlan.issues.map(::profileIssueText))}}""",
                    InstallPresentation("restore-profile-catalog-not-restorable"),
                ),
                ContentType.Application.Json,
                HttpStatusCode.UnprocessableEntity,
            )
            val plannedCompanion = when {
                comp != null && archiveManifest != null -> reader.planCompanionArchive(comp, plainFile, archiveEntries)
                comp != null -> reader.planCompanionRestore(comp)
                else -> null
            }
            if (plannedCompanion is CompanionRestore.PlanResult.Invalid) {
                return call.respondText(
                    withInstallPresentation(
                        """{"ok":false,"error":${Json.str(plannedCompanion.reason)}}""",
                        plannedCompanion.presentation,
                    ),
                    ContentType.Application.Json,
                    HttpStatusCode.BadRequest,
                )
            }
            val companionPlan = (plannedCompanion as? CompanionRestore.PlanResult.Valid)?.plan
            retainedCompanionPlan = companionPlan
            val compFiles = companionPlan?.files?.size ?: 0
            // Decoded here so a malformed section refuses the whole restore before anything is written;
            // whether the engine accepts each model is only known when it is imported, in the job below.
            val restoreWakeWords = wakeWordsObj?.let { section ->
                runCatching {
                    withContext(Dispatchers.IO) {
                        io.panelassistant.android.backup.WakeWordBackup.read(plainFile, section, archiveEntries, cacheDir)
                    }
                }.getOrNull() ?: return call.respondText(
                    withInstallPresentation(
                        """{"ok":false,"error":"invalid wake word archive entry"}""",
                        InstallPresentation("restore-archive-entries-invalid"),
                    ),
                    ContentType.Application.Json,
                    HttpStatusCode.BadRequest,
                )
            }.orEmpty()
            val wakeWordCatalog = wakeWords
            if (restoreWakeWords.isNotEmpty() && wakeWordCatalog == null) return call.respondText(
                """{"ok":false,"error":"wake word restore is unavailable"}""",
                ContentType.Application.Json,
                HttpStatusCode.ServiceUnavailable,
            )
            // Security admission reads the original intent before capability omission in either mode.
            if (rejectHardenedNetworkAdb(call, configPlan.values["network_adb"])) return
            if (companionPlan != null && !withContext(Dispatchers.IO) {
                    companionHelperReady() || capabilities().networkAdb
                }
            ) {
                return call.respondText(
                    withInstallPresentation(
                        """{"ok":false,"error":"Companion restore needs the current Panel Assistant helper"}""",
                        InstallPresentation("restore-companion-helper-required"),
                    ),
                    ContentType.Application.Json,
                    HttpStatusCode.ServiceUnavailable,
                )
            }
            if (dryRun) {
                val preview = configPlan.withAvailableLiveSettings(capabilities(), config.adbKeepOff)
                requestAccepted = true
                return call.respondText(
                    """{"ok":true,"dry_run":true,"panel_id":${Json.str(obj.optString("panel_id"))},""" +
                        """"config_keys":${preview.values.size},"config_warnings":${jarr(preview.warnings)},"config_skipped":${jarr(preview.skipped)},"profile_revisions":${profilePlan?.toImport?.size ?: 0},"profile_restart_required":${profilePlan?.restartRequired ?: false},"companion_pkg":${Json.str(companionPlan?.packageName ?: "")},"companion_files":$compFiles,"wake_words":${restoreWakeWords.size},"state_unavailable":$stateUnavailable}""",
                    ContentType.Application.Json,
                )
            }
            val restoreDigest = withContext(Dispatchers.IO) {
                val digest = MessageDigest.getInstance("SHA-256")
                receivedFile.inputStream().use { input ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        digest.update(buffer, 0, count)
                    }
                }
                digest.digest().joinToString("") { "%02x".format(it) }
            }
            if (!authorizeSensitive(
                    call,
                    SensitiveOperation.BACKUP_RESTORE,
                    exactHttpApprovalPayload(call, restoreDigest),
                    "Restore this panel backup${if (companionPlan != null) " including the Companion login" else ""}",
                )
            ) return
            if (companionPlan != null && !withContext(Dispatchers.IO) { ensureCompanionHelper() }) {
                return call.respondText(
                    withInstallPresentation(
                        """{"ok":false,"error":"Companion restore needs the current Panel Assistant helper"}""",
                        InstallPresentation("restore-companion-helper-required"),
                    ),
                    ContentType.Application.Json,
                    HttpStatusCode.ServiceUnavailable,
                )
            }
            val job = scope.launch {
                executor.execute(
                    configPlan, entityState, profilePayload, profilePlan, companionPlan,
                    restorableState, stateUnavailable,
                    restoreWakeWords, wakeWordCatalog,
                    progress,
                    restorableCalibrations = restorableCalibrations,
                    ownerChoices = ownerChoices,
                    restorableIssues = restorableIssues,
                    stateSkipped = stateSkipped,
                )
            }
            job.invokeOnCompletion {
                retainedCompanionPlan?.close()
                restoreFiles.forEach(File::delete)
            }
            transferredToJob = true
            requestAccepted = true
            InstallProgress.finishOnFailure(progress, job)
            call.respondText("""{"status":"started"}""", ContentType.Application.Json)
        } finally {
            if (!transferredToJob) {
                // Rejected before any restore ran — a bad bundle, a refused approval, a missing helper.
                retainedCompanionPlan?.close()
                restoreFiles.forEach(File::delete)
                val result = if (requestAccepted) {
                    InstallProgress.OperationResult(InstallProgress.Outcome.SUCCEEDED)
                } else {
                    InstallProgress.OperationResult(InstallProgress.Outcome.FAILED)
                }
                InstallProgress.finish(
                    progress,
                    if (requestAccepted) "Restore preview complete" else "Restore request rejected",
                    result,
                    InstallPresentation(
                        if (requestAccepted) "restore-preview-complete" else "restore-request-rejected",
                    ),
                )
            }
        }
    }

    private fun profileIssueText(issue: io.panelassistant.android.device.profile.ProfileIssue): String =
        "${issue.path}: ${issue.message}"

    private fun jarr(items: List<String>): String =
        "[" + items.joinToString(",") { Json.str(it) } + "]"

    /** Add the optional v3 overlay after the legacy object without rewriting its existing fields. */
    private fun withInstallPresentation(
        legacyJson: String,
        presentation: InstallPresentation?,
    ): String {
        if (presentation == null) return legacyJson
        require(legacyJson.endsWith('}'))
        return legacyJson.dropLast(1) + ",\"presentation\":" + presentation.json() + "}"
    }
}
