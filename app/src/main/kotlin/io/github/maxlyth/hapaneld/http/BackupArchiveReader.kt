package io.github.maxlyth.hapaneld.http

import java.io.File
import io.github.maxlyth.hapaneld.DashboardEntityBackupState
import io.github.maxlyth.hapaneld.backup.PanelBackup
import io.github.maxlyth.hapaneld.backup.CompanionRestore
import io.github.maxlyth.hapaneld.device.profile.ProfileBackup
import io.github.maxlyth.hapaneld.util.BoundedStreams
import io.github.maxlyth.hapaneld.util.withStagedFiles
import io.github.maxlyth.hapaneld.http.PaneldServer.Companion.PROFILE_BACKUP_ENTRY
import io.github.maxlyth.hapaneld.http.PaneldServer.Companion.MAX_PROFILE_BACKUP_ENTRY_BYTES
import io.github.maxlyth.hapaneld.http.PaneldServer.Companion.MAX_STATE_BACKUP_BYTES
import io.github.maxlyth.hapaneld.http.PaneldServer.Companion.MAX_ENTITY_BACKUP_TEXT_BYTES

internal class BackupArchiveReader(
    private val cacheDir: File,
    private val installedCompanionPackages: () -> Set<String>,
) {
    fun readArchiveText(
        archive: File,
        ref: ArchiveTextRef,
        allowedEntries: Set<String>,
        prefix: String,
    ): String {
        return withStagedFiles { staged ->
            val target = staged.stage(File.createTempFile(prefix, ".payload", cacheDir))
            require(
                PanelBackup.extractArchive(
                    archive,
                    listOf(PanelBackup.ArchiveTarget(ref.entry, target, ref.maxBytes, ref.allowEmpty)),
                    allowedEntries,
                ),
            )
            require(target.length() == ref.size)
            val bytes = target.inputStream().use { BoundedStreams.readBytes(it, ref.maxBytes) }
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                .decode(java.nio.ByteBuffer.wrap(bytes))
                .toString()
        }
    }

    fun readProfileArchive(
        metadata: org.json.JSONObject,
        archive: File,
        allowedEntries: Set<String>,
    ): io.github.maxlyth.hapaneld.device.profile.ProfileBackupDecodeResult? = runCatching {
        val ref = archiveTextRef(
            metadata,
            "entry",
            "size",
            PROFILE_BACKUP_ENTRY,
            MAX_PROFILE_BACKUP_ENTRY_BYTES,
            allowEmpty = false,
        )
        ProfileBackup.fromJson(org.json.JSONObject(readArchiveText(archive, ref, allowedEntries, "profile-restore-")))
    }.getOrNull()

    fun planEntityArchive(
        metadata: org.json.JSONObject,
        archive: File,
        allowedEntries: Set<String>,
    ): DashboardEntityBackupState {
        val filterRef = archiveTextRef(
            metadata,
            "filter_ids_entry",
            "filter_ids_size",
            ENTITY_FILTER_BACKUP_ENTRY,
            MAX_ENTITY_BACKUP_TEXT_BYTES,
            allowEmpty = true,
        )
        val overridesRef = archiveTextRef(
            metadata,
            "overrides_entry",
            "overrides_size",
            ENTITY_OVERRIDES_BACKUP_ENTRY,
            MAX_ENTITY_BACKUP_TEXT_BYTES,
            allowEmpty = true,
        )
        val filterIds = readArchiveText(archive, filterRef, allowedEntries, "entity-filter-restore-")
        val overrides = readArchiveText(archive, overridesRef, allowedEntries, "entity-overrides-restore-")
        return planEntityBackup(
            org.json.JSONObject(metadata.toString())
                .put("filter_ids", filterIds)
                .put("overrides", overrides),
        )
    }

    /** Convert untrusted JSON to a completely validated and decoded plan before any config commit or app stop. */
    fun planCompanionRestore(comp: org.json.JSONObject): CompanionRestore.PlanResult {
        val files = comp.optJSONArray("files")
            ?: return invalidCompanionPayload("Companion restore contains no files")
        val encoded = ArrayList<CompanionRestore.EncodedFile>(files.length())
        for (i in 0 until files.length()) {
            val file = files.optJSONObject(i)
                ?: return invalidCompanionPayload("Invalid Companion file entry at index $i")
            encoded += CompanionRestore.EncodedFile(file.optString("rel"), file.optString("b64"))
        }
        return CompanionRestore.plan(
            packageName = comp.optString("pkg"),
            files = encoded,
            installedPackages = installedCompanionPackages(),
            stagingDir = cacheDir,
        )
    }

    /** Extract a v2 archive's raw Companion entries under per-file and aggregate decoded limits. */
    fun planCompanionArchive(
        comp: org.json.JSONObject,
        archive: File,
        allowedEntries: Set<String>,
    ): CompanionRestore.PlanResult {
        val files = comp.optJSONArray("files")
            ?: return invalidCompanionPayload("Companion restore contains no files")
        if (files.length() !in 1..CompanionRestore.ALLOWED_FILES.size) {
            return invalidCompanionPayload("Companion restore contains an invalid file count")
        }
        data class Pending(val relativePath: String, val entry: String, val size: Long, val target: File)
        return withStagedFiles { staged ->
            val pending = ArrayList<Pending>(files.length())
            for (index in 0 until files.length()) {
                val file = files.optJSONObject(index)
                    ?: return@withStagedFiles invalidCompanionPayload("Invalid Companion file entry at index $index")
                val relativePath = file.optString("rel")
                val entry = file.optString("entry")
                val declaredSize = file.optLong("size", -1L)
                if (relativePath !in CompanionRestore.ALLOWED_FILES ||
                    declaredSize !in 1..CompanionRestore.maxBytes(relativePath)
                ) return@withStagedFiles invalidCompanionPayload("Invalid Companion file metadata at index $index")
                pending += Pending(
                    relativePath,
                    entry,
                    declaredSize,
                    staged.stage(File.createTempFile("companion-restore-", ".payload", cacheDir)),
                )
            }
            if (pending.map { it.relativePath }.toSet().size != pending.size ||
                pending.map { it.entry }.toSet().size != pending.size ||
                pending.sumOf { it.size } > CompanionRestore.MAX_AGGREGATE_BYTES
            ) return@withStagedFiles invalidCompanionPayload("Duplicate or oversized Companion archive metadata")
            val extracted = PanelBackup.extractArchive(
                archive,
                pending.map { PanelBackup.ArchiveTarget(it.entry, it.target, CompanionRestore.maxBytes(it.relativePath)) },
                allowedEntries,
            )
            if (!extracted || pending.any { it.target.length() != it.size }) {
                return@withStagedFiles invalidCompanionPayload("Companion archive files are missing, corrupt, or too large")
            }
            val result = CompanionRestore.planFiles(
                packageName = comp.optString("pkg"),
                files = pending.map { CompanionRestore.FilePayload(it.relativePath, it.target) },
                installedPackages = installedCompanionPackages(),
            )
            if (result is CompanionRestore.PlanResult.Valid) staged.commit()
            result
        }
    }

}
