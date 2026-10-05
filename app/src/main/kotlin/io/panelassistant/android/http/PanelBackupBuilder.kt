package io.panelassistant.android.http

import android.content.Context
import android.util.Log
import io.panelassistant.android.Config
import io.panelassistant.android.DashboardEntityBackupState
import io.panelassistant.android.panelAssistantDiscoveryId
import io.panelassistant.android.backup.PanelBackup
import io.panelassistant.android.config.SettingSpec
import io.panelassistant.android.config.SettingsRegistry
import io.panelassistant.android.dashboard.EntityCatalogStore
import io.panelassistant.android.dashboard.readThenClose
import io.panelassistant.android.device.profile.ProfileAdmin
import io.panelassistant.android.persistence.ConfigVault
import io.panelassistant.android.persistence.StateArchiveSection
import io.panelassistant.android.persistence.BackupIdentity
import io.panelassistant.android.persistence.RawPreferenceBackup
import io.panelassistant.android.util.CompanionInstaller
import io.panelassistant.android.util.ByteLimitExceeded
import io.panelassistant.android.util.Json
import io.panelassistant.android.util.withStagedFiles
import java.io.File

internal class PanelBackupBuilder(
    private val appContext: android.content.Context,
    private val config: Config,
    private val cacheDir: File,
    private val configLiveValues: () -> Map<String, String>,
    private val effectiveValue: (io.panelassistant.android.config.SettingSpec, Map<String, String>) -> String,
    private val profileAdmin: ProfileAdmin?,
    private val companion: CompanionBackupOperations,
    private val mqttState: () -> String,
    private val wakeWords: io.panelassistant.android.assist.wakeword.WakeWordCatalog?,
) {
    private data class BackupArchiveParts(
        val manifest: String,
        val sources: List<PanelBackup.ArchiveSource>,
        val ownedFiles: List<File>,
        val stateUnavailable: Boolean,
    )

    /** Build a file-backed v2 container. Companion bytes are raw ZIP entries, not base64 JSON. */
    fun build(request: CompanionBackupRequest, passphrase: String): PanelBackup.Artifact {
        // Resolve before reserving: the staging bound has to describe the backup this panel is going to
        // build, not the largest one the request could have meant. An omitted request on a Companion-free
        // panel would otherwise reserve room for a capture that never happens, and could be refused for
        // storage the archive never needed.
        val includeCompanion = resolveCompanionInclusion(request) {
            CompanionInstaller.installedPkg(appContext) != null
        }
        if (cacheDir.usableSpace < backupStagingRequirement(includeCompanion, passphrase.isNotEmpty())) {
            throw CompanionBackupUnavailable("Insufficient storage to stage a backup safely")
        }
        val capture = if (includeCompanion) companion.capture() else null
        return withBackupCaptureAndPlaintext(
            capture,
            createPlaintext = { File.createTempFile("panel-backup-", ".zip", cacheDir) },
        ) { ownedCapture, plain ->
            var sealed: File? = null
            var parts: BackupArchiveParts? = null
            withBackupArtifactCleanup(
                plain = plain,
                sealed = { sealed },
                ownedFiles = { parts?.ownedFiles.orEmpty() },
            ) {
                parts = backupArchiveParts(ownedCapture)
                plain.outputStream().use { output ->
                    PanelBackup.writeArchive(
                        output,
                        parts.manifest,
                        parts.sources,
                        MAX_BACKUP_MANIFEST_BYTES,
                    )
                }
                val plaintextLimit = if (passphrase.isEmpty()) MAX_RESTORE_BYTES
                    else PanelBackup.maxSealablePlaintextBytes(MAX_RESTORE_BYTES)
                if (plain.length() !in 1..plaintextLimit) throw ByteLimitExceeded(plaintextLimit)
                val stateUnavailable = parts.stateUnavailable
                if (passphrase.isEmpty()) {
                    return@withBackupCaptureAndPlaintext PanelBackup.Artifact(plain, "zip", stateUnavailable)
                }
                sealed = File.createTempFile("panel-backup-", ".hpb", cacheDir)
                plain.inputStream().use { input ->
                    sealed.outputStream().use { output -> PanelBackup.seal(input, output, passphrase) }
                }
                if (sealed.length() !in 1..MAX_RESTORE_BYTES) throw ByteLimitExceeded(MAX_RESTORE_BYTES)
                encryptedBackupArtifact(plain, sealed, stateUnavailable)
            }
        }
    }

    /** Keep the v2 manifest small: large profile and owner-scoped entity strings are bounded ZIP entries. */
    private fun backupArchiveParts(companion: CapturedCompanion?): BackupArchiveParts {
        return withStagedFiles { staged ->
            val owned = ArrayList<File>(3)
            fun textEntry(name: String, prefix: String, text: String, maxBytes: Long): PanelBackup.ArchiveSource {
                val file = staged.stage(File.createTempFile(prefix, ".payload", cacheDir)).also(owned::add)
                file.writer(Charsets.UTF_8).use { it.write(text) }
                if (file.length() > maxBytes) throw ByteLimitExceeded(maxBytes)
                return PanelBackup.ArchiveSource(name, file)
            }
            val entity = config.dashboardEntityBackupState()
            val filter = textEntry(ENTITY_FILTER_BACKUP_ENTRY, "entity-filter-backup-", entity.filterIds, MAX_ENTITY_BACKUP_TEXT_BYTES)
            val overrides = textEntry(ENTITY_OVERRIDES_BACKUP_ENTRY, "entity-overrides-backup-", entity.overrides, MAX_ENTITY_BACKUP_TEXT_BYTES)
            val profile = profileAdmin?.exportBackup()?.let {
                textEntry(PROFILE_BACKUP_ENTRY, "profile-backup-", it.toJson().toString(), MAX_PROFILE_BACKUP_ENTRY_BYTES)
            }
            // Imported wake words are files the settings only name. The section exists only when there is
            // one, so a panel without imports still writes an archive older builds restore.
            // A model that cannot be read refuses the backup rather than leaving a word the settings select
            // out of it, which a restore could never put back.
            val importedWakeWords = try {
                wakeWords?.exportImported().orEmpty()
            } catch (unreadable: java.io.IOException) {
                throw CompanionBackupUnavailable(unreadable.message ?: "An imported wake word could not be read")
            }
            val wakeWordEntry = io.panelassistant.android.backup.WakeWordBackup.encode(importedWakeWords)?.let { text ->
                if (text.length.toLong() > io.panelassistant.android.backup.WakeWordBackup.MAX_ENTRY_BYTES) {
                    throw CompanionBackupUnavailable(
                        "Imported wake words exceed the backup's " +
                            "${io.panelassistant.android.backup.WakeWordBackup.MAX_ENTRY_BYTES / (1024 * 1024)} MiB limit",
                    )
                }
                textEntry(
                    io.panelassistant.android.backup.WakeWordBackup.ENTRY,
                    "wake-word-backup-",
                    text,
                    io.panelassistant.android.backup.WakeWordBackup.MAX_ENTRY_BYTES,
                )
            }
            // A database that will not read must not cost the owner the rest of the backup, which still
            // carries the validated config projection — but it must not be silent either. The failure is
            // logged and marked in the manifest below, so this archive can never be mistaken for one taken
            // from a panel that simply had nothing stored.
            // Not `use { }`: SQLiteOpenHelper only implements AutoCloseable from API 29, so `use`
            // compiles against the current compileSdk yet throws ClassCastException at runtime on
            // Android 8.1. readThenClose also isolates the close, so a store that exported successfully
            // but failed to close still contributes its rows.
            val stateCapture = runCatching {
                readThenClose(EntityCatalogStore(appContext), { it.close() }) { it.exportAppState() }
            }
            val stateFailure = stateCapture.exceptionOrNull()
            if (stateFailure != null) Log.w(TAG, "backup could not read app_state", stateFailure)
            val stateRows = stateCapture.getOrDefault(emptyList())
            val state = stateRows.takeIf { it.isNotEmpty() }?.let { rows ->
                textEntry(
                    STATE_BACKUP_ENTRY,
                    "app-state-backup-",
                    ConfigVault.encode(ConfigVault.Export(rows, emptyMap())),
                    MAX_STATE_BACKUP_BYTES,
                )
            }
            val sources = ArrayList<PanelBackup.ArchiveSource>(8)
            sources.add(filter)
            sources.add(overrides)
            profile?.let(sources::add)
            state?.let(sources::add)
            wakeWordEntry?.let(sources::add)
            sources += companion?.files.orEmpty().mapIndexed { index, file ->
                PanelBackup.ArchiveSource("companion/$index", file.file)
            }
            val parts = BackupArchiveParts(
                manifest = backupManifest(
                    companion,
                    entity,
                    filter.file.length(),
                    overrides.file.length(),
                    profile?.file?.length(),
                    state?.file?.length(),
                    stateRows.size,
                    stateFailure != null,
                    wakeWordEntry?.let {
                        io.panelassistant.android.backup.WakeWordBackup.manifestFragment(it.file.length(), importedWakeWords.size)
                    },
                ),
                sources = sources,
                ownedFiles = owned,
                stateUnavailable = stateFailure != null,
            )
            staged.commit()
            parts
        }
    }

    /** Build bounded metadata only. A requested Companion capture is all-or-error. */
    private fun backupManifest(
        companion: CapturedCompanion?,
        entity: DashboardEntityBackupState,
        filterBytes: Long,
        overrideBytes: Long,
        profileBytes: Long?,
        stateBytes: Long?,
        stateRows: Int,
        stateCaptureFailed: Boolean,
        wakeWordSection: String?,
    ): String {
        val live = configLiveValues()
        val cfg = projectConfigSnapshot(
            specs = SettingsRegistry.settable(),
            zigbeeRouterConfigured = config.zigbeeRouterConfigured,
            excludedKeys = ENTITY_STATE_CONFIG_KEYS,
            effectiveValue = { effectiveValue(it, live) },
        ).entries.joinToString(",") { (key, value) -> "${Json.str(key)}:${Json.str(value)}" }
        val exposures = persistedExposureValues(config)
            .entries.joinToString(",") { (key, value) ->
                "${Json.str(key)}:${Json.str(value)}"
            }
        val sb = StringBuilder("{\"kind\":\"ha-paneld-backup\",\"schema\":${SettingsRegistry.SCHEMA}")
        sb.append(",\"panel_id\":${Json.str(config.panelId)},\"created\":${Json.str(System.currentTimeMillis().toString())}")
        // Which device and which installed identity wrote this archive: the pseudonym Panel Assistant
        // already sees, never the Android id. It lets the other identity of this app, installed beside
        // this one, prove the archive is from the same device before it has adopted the panel id.
        sb.append(
            BackupIdentity.manifestFragment(
                panelAssistantDiscoveryId(config.deviceUid),
                appContext.packageName,
                mqttConnected = mqttState() == "connected",
            ),
        )
        sb.append(
            RawPreferenceBackup.manifestFragment { store ->
                appContext.getSharedPreferences(store, android.content.Context.MODE_PRIVATE).all
            },
        )
        sb.append(",\"config\":{").append(listOf(cfg, exposures).filter { it.isNotEmpty() }.joinToString(",")).append("}")
        sb.append(",\"entity_state\":").append(entityBackupArchiveJson(entity, filterBytes, overrideBytes))
        if (profileBytes != null) {
            sb.append(",\"profiles\":{\"entry\":").append(Json.str(PROFILE_BACKUP_ENTRY))
                .append(",\"size\":").append(profileBytes).append('}')
        }
        StateArchiveSection.manifestFragment(
            STATE_BACKUP_ENTRY,
            stateBytes,
            stateRows,
            stateCaptureFailed,
        )?.let { sb.append(",\"state\":").append(it) }
        wakeWordSection?.let { sb.append(",\"wake_words\":").append(it) }
        if (companion != null) {
            val files = companion.files.mapIndexed { index, file ->
                "{\"rel\":${Json.str(file.relativePath)},\"entry\":${Json.str("companion/$index")},\"size\":${file.file.length()}}"
            }.joinToString(",")
            sb.append(",\"companion\":{\"pkg\":${Json.str(companion.packageName)},\"files\":[")
                .append(files).append("]}")
        }
        return sb.append("}").toString()
    }


}

private const val TAG = "ha-paneld/http"
