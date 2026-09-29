package io.github.maxlyth.hapaneld.http

import io.github.maxlyth.hapaneld.canonicalHaOrigin
import io.github.maxlyth.hapaneld.Config
import io.github.maxlyth.hapaneld.panelAssistantDiscoveryId
import io.github.maxlyth.hapaneld.backup.PanelBackup
import io.github.maxlyth.hapaneld.backup.CompanionRestore
import io.github.maxlyth.hapaneld.device.profile.ProfileAdmin
import io.github.maxlyth.hapaneld.device.profile.ProfileBackup
import io.github.maxlyth.hapaneld.persistence.ConfigVault
import io.github.maxlyth.hapaneld.persistence.StateArchiveSection
import io.github.maxlyth.hapaneld.migration.IdentityMigrationSurface
import io.github.maxlyth.hapaneld.migration.MigrationRestoreAdmission
import io.github.maxlyth.hapaneld.migration.RestoreAttempt
import io.github.maxlyth.hapaneld.migration.claimsRestoreAttempt
import io.github.maxlyth.hapaneld.migration.migrationRestoreAdmission
import io.github.maxlyth.hapaneld.persistence.BackupIdentity
import io.github.maxlyth.hapaneld.persistence.RawPreferenceBackup
import io.github.maxlyth.hapaneld.persistence.StateBackupPolicy
import io.github.maxlyth.hapaneld.security.SensitiveOperation
import io.github.maxlyth.hapaneld.util.BoundedStreams
import io.github.maxlyth.hapaneld.util.isLoopbackPeer
import io.github.maxlyth.hapaneld.util.ByteLimitExceeded
import io.github.maxlyth.hapaneld.util.InstallPresentation
import io.github.maxlyth.hapaneld.util.InstallProgress
import io.github.maxlyth.hapaneld.util.Json
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.plugins.origin
import io.ktor.server.request.receiveStream
import io.ktor.server.response.respondText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import io.github.maxlyth.hapaneld.http.PaneldServer.Companion.MAX_RESTORE_BYTES
import io.github.maxlyth.hapaneld.http.PaneldServer.Companion.MAX_BACKUP_MANIFEST_BYTES
import io.github.maxlyth.hapaneld.http.PaneldServer.Companion.MAX_LEGACY_BACKUP_JSON_BYTES
import io.github.maxlyth.hapaneld.http.PaneldServer.Companion.MAX_STATE_BACKUP_BYTES

internal class RestoreRoutes(
    private val cacheDir: File,
    private val config: Config,
    private val identityMigration: IdentityMigrationSurface,
    private val reader: BackupArchiveReader,
    private val profileAdmin: ProfileAdmin?,
    private val ensureCompanionHelper: () -> Boolean,
    private val authorizeSensitive: suspend (ApplicationCall, SensitiveOperation, String, String) -> Boolean,
    private val rejectHardenedNetworkAdb: suspend (ApplicationCall, String?) -> Boolean,
    private val scope: CoroutineScope,
    private val executor: RestoreExecutor,
    private val wakeWords: io.github.maxlyth.hapaneld.assist.wakeword.WakeWordCatalog?,
    private val verifiedRetiredReceipt: (File) -> Boolean,
) {

    /** Restore endpoint: decrypt + validate; ?dry_run=1 reports contents without writing. A real restore is
     *  DESTRUCTIVE (config rewrite + Companion force-stop/rewrite), run off-thread with InstallProgress. */
    suspend fun handle(call: ApplicationCall) {
        val pw = call.request.headers["X-Backup-Passphrase"].orEmpty()
        val dryRun = call.request.queryParameters["dry_run"] == "1"
        val migrationRestore = when (
            migrationRestoreAdmission(
                requested = call.request.queryParameters["mode"] == "migration",
                loopbackPeer = isLoopbackPeer(call.request.origin.remoteAddress),
                restoreOpen = identityMigration.restoreOpen(),
            )
        ) {
            MigrationRestoreAdmission.NOT_REQUESTED -> false
            MigrationRestoreAdmission.ADMITTED -> true
            MigrationRestoreAdmission.REFUSED -> return call.respondText(
                """{"ok":false,"error":"migration-restore-refused"}""",
                ContentType.Application.Json,
                HttpStatusCode.Forbidden,
            )
        }
        // Claim the successor's wait now, not when the restore ends. A restore that outlives the five
        // minute wait must still answer the attempt that started it: by the time it finishes, the
        // successor may already have opened another, and answering that one would report this restore's
        // outcome for a restore that has not run.
        val restoreAttempt =
            if (claimsRestoreAttempt(migrationRestore, dryRun)) identityMigration.claimRestoreAttempt()
            else RestoreAttempt.NONE
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
                    """{"ok":false,"error":"not a ha-paneld backup"}""",
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
                        """{"ok":false,"error":"not a ha-paneld backup"}""",
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
                cfgObj, backupSchema, canonicalHaOrigin(config.haUrl), config.zigbeeRouterConfigured,
            ).let { plan ->
                if (migrationRestore) plan.copy(values = migrationRestoreConfig(plan.values)) else plan
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
            // A migration-mode restore is this app's other identity handing its state over on one device.
            // The successor has not adopted the panel id yet, so the device is proven by the discovery
            // pseudonym instead, and an archive from anywhere else is refused outright rather than
            // restored with its device-local rows withheld.
            val sameDeviceByDiscoveryId =
                BackupIdentity.sameDevice(obj, panelAssistantDiscoveryId(config.deviceUid)) ||
                    (migrationRestore && verifiedRetiredReceipt(plainFile))
            if (migrationRestore && !sameDeviceByDiscoveryId) return call.respondText(
                """{"ok":false,"error":"migration-backup-not-from-this-device"}""",
                ContentType.Application.Json,
                HttpStatusCode.UnprocessableEntity,
            )
            val rawPreferences = if (migrationRestore) {
                RawPreferenceBackup.restorable(obj) ?: return call.respondText(
                    """{"ok":false,"error":"invalid raw_preferences object"}""",
                    ContentType.Application.Json,
                    HttpStatusCode.BadRequest,
                )
            } else emptyMap()
            // Durable state outside the settings registry. `panel_id` is read before any config write, so
            // it still identifies the physical target: device-local rows return only to their own panel.
            // A cleared panel that no longer carries its old id is treated as a different one, which
            // withholds hardware-specific rows rather than guessing.
            val restorableState = if (
                archiveManifest != null && stateDisposition == StateArchiveSection.Disposition.RESTORABLE
            ) {
                val samePanel = StateBackupPolicy.sameDevice(
                    migrationRestore = migrationRestore,
                    panelIdMatches = obj.optString("panel_id").let { it.isNotEmpty() && it == config.panelId },
                    discoveryIdMatches = sameDeviceByDiscoveryId,
                )
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
                    StateBackupPolicy.restorableRows(decoded.rows, samePanel) +
                        io.github.maxlyth.hapaneld.migration.migrationNoticeHistoryRows(
                            decoded.rows, migrationRestore, sameDeviceByDiscoveryId,
                        )
                }.getOrNull() ?: return call.respondText(
                    withInstallPresentation(
                        """{"ok":false,"error":"invalid app_state payload"}""",
                        InstallPresentation("restore-app-state-invalid"),
                    ),
                    ContentType.Application.Json,
                    HttpStatusCode.UnprocessableEntity,
                )
            } else emptyList()
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
                        io.github.maxlyth.hapaneld.backup.WakeWordBackup.read(plainFile, section, archiveEntries, cacheDir)
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
            if (dryRun) {
                requestAccepted = true
                return call.respondText(
                    """{"ok":true,"dry_run":true,"panel_id":${Json.str(obj.optString("panel_id"))},""" +
                        """"config_keys":${configPlan.values.size},"config_warnings":${jarr(configPlan.warnings)},"profile_revisions":${profilePlan?.toImport?.size ?: 0},"profile_restart_required":${profilePlan?.restartRequired ?: false},"companion_pkg":${Json.str(companionPlan?.packageName ?: "")},"companion_files":$compFiles,"wake_words":${restoreWakeWords.size},"state_unavailable":$stateUnavailable}""",
                    ContentType.Application.Json,
                )
            }
            if (rejectHardenedNetworkAdb(call, configPlan.values["network_adb"])) return
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
                        """{"ok":false,"error":"Companion restore needs the current ha-paneld helper"}""",
                        InstallPresentation("restore-companion-helper-required"),
                    ),
                    ContentType.Application.Json,
                    HttpStatusCode.ServiceUnavailable,
                )
            }
            val job = scope.launch {
                executor.execute(
                    configPlan, entityState, profilePayload, profilePlan, companionPlan,
                    rawPreferences, restorableState, stateUnavailable, migrationRestore,
                    restoreWakeWords, wakeWordCatalog,
                    progress, restoreAttempt,
                )
            }
            job.invokeOnCompletion {
                // A job that was cancelled, or that failed somewhere its own result never reaches,
                // still ends the attempt. Reporting is first-wins, so a job that already reported its
                // real outcome keeps it and only an unanswered attempt is failed here — which the
                // successor retries at once rather than waiting out the five minute timeout.
                restoreAttempt.finished(false)
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
                // The attempt is answered here for the same reason the job answers its own: the
                // successor should retry now, not in five minutes.
                restoreAttempt.finished(false)
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

    private fun profileIssueText(issue: io.github.maxlyth.hapaneld.device.profile.ProfileIssue): String =
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
