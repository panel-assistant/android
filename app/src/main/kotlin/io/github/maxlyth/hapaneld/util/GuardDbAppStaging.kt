package io.github.maxlyth.hapaneld.util

import android.content.Context
import android.system.Os
import android.system.OsConstants
import android.os.Process
import io.github.maxlyth.hapaneld.http.PendingUploadStore
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.zip.ZipFile
import io.github.maxlyth.hapaneld.persistence.canonicalGuardDbSettingsAuthority

internal data class GuardDbCandidateInspection(
    val bytes: Long,
    val sha256: String,
    val versionCode: Long,
    val signerSha256: String,
    val contractMinimum: Int,
    val contractMaximum: Int,
    val expectedSchema: Int,
    val settingsAuthorityVersion: Int,
    val settingsAuthorityBytes: Long,
    val settingsAuthoritySha256: String,
)

internal fun inspectGuardDbCandidate(context: Context, file: File): GuardDbCandidateInspection? {
    if (!file.isFile || file.length() <= 0L) return null
    val info = AppInstaller.inspect(context, file.absolutePath) ?: return null
    if (AppInstaller.selfReplacementRefusal(info) { null } != null) return null
    val boundary = (info.databaseCompatibility as? DatabaseCompatibilityApkContract.Parsed.Valid)?.boundary
        ?: return null
    val signer = info.signerSha256s.singleOrNull()?.lowercase() ?: return null
    if (signer != AppInstaller.HA_PANELD.certSha256 || info.versionCode <= 0L) return null
    val authority = readGuardDbSettingsAuthorityAsset(file) ?: return null
    val expectedAuthority = canonicalGuardDbSettingsAuthority()
    if (!MessageDigest.isEqual(authority, expectedAuthority)) return null
    val sha256 = AppInstaller.sha256(file)
    return GuardDbCandidateInspection(
        bytes = file.length(),
        sha256 = sha256,
        versionCode = info.versionCode,
        signerSha256 = signer,
        contractMinimum = boundary.minimumSchema,
        contractMaximum = boundary.maximumSchema,
        expectedSchema = boundary.maximumSchema,
        settingsAuthorityVersion = GuardDbSettingsAuthority.VERSION,
        settingsAuthorityBytes = authority.size.toLong(),
        settingsAuthoritySha256 = settingsAuthoritySha256(authority),
    )
}

private fun readGuardDbSettingsAuthorityAsset(apk: File): ByteArray? = runCatching {
    ZipFile(apk).use { zip ->
        val entry = zip.getEntry("assets/guard-db-settings-authority-v2") ?: return null
        if (entry.isDirectory || entry.size !in 1..GuardDbSettingsAuthority.MAX_BYTES) return null
        zip.getInputStream(entry).use { input ->
            BoundedStreams.readBytes(input, GuardDbSettingsAuthority.MAX_BYTES).also {
                if (it.size.toLong() != entry.size) return null
            }
        }
    }
}.getOrNull()

/**
 * Process-independent app-side holding area between the existing one-slot upload store and ARM.
 * Paths are fixed by role and never cross the helper protocol. These copies are inspection evidence;
 * root-owned helper custody becomes the only mutation authority once ARM completes.
 */
internal class GuardDbAppStaging(
    private val directory: File,
    private val inspect: (File) -> GuardDbCandidateInspection?,
    private val syncDirectory: (File) -> Boolean = ::fsyncDirectory,
    private val copyAndSync: (File, File) -> Boolean = ::copyAndSync,
    private val atomicMove: (File, File) -> Boolean = ::atomicMoveWithoutReplacement,
    private val validateFile: (File) -> Boolean = ::validGuardDbAppFile,
) {
    @Synchronized
    fun claim(
        role: GuardDbMaintenanceProtocol.Role,
        pendingUploads: PendingUploadStore,
        token: String,
        expected: GuardDbCandidateInspection? = null,
    ): GuardDbMaintenanceProtocol.Candidate? {
        val temporary = File(directory, guardDbCandidatePendingName(role))
        val destination = candidateFile(role)
        var candidate: GuardDbMaintenanceProtocol.Candidate? = null
        val claimed = pendingUploads.claimAfter(token) { entry ->
            val source = entry.file
            try {
                if (!directory.exists() && !directory.mkdirs()) return@claimAfter false
                if (!directory.isDirectory || !validateFile(source) || source.length() <= 0L || destination.exists()) {
                    return@claimAfter false
                }
                temporary.delete()
                if (!copyAndSync(source, temporary)) return@claimAfter false
                val inspected = inspect(temporary) ?: return@claimAfter false
                if (expected != null && inspected != expected) return@claimAfter false
                if (inspected.bytes != temporary.length() || inspected.sha256 != AppInstaller.sha256(temporary)) {
                    return@claimAfter false
                }
                if (!atomicMove(temporary, destination)) return@claimAfter false
                if (!syncDirectory(directory)) {
                    destination.delete()
                    syncDirectory(directory)
                    return@claimAfter false
                }
                candidate = inspected.toCandidate(role, destination)
                // Destination and its parent entry are durable. From this point the final pending
                // claim is represented even if deleting the expendable upload-cache copy fails.
                source.delete()
                true
            } catch (_: Exception) {
                false
            } finally {
                temporary.delete()
            }
        }
        return candidate?.takeIf { claimed != null }
    }

    @Synchronized
    fun load(role: GuardDbMaintenanceProtocol.Role): GuardDbMaintenanceProtocol.Candidate? {
        val file = candidateFile(role)
        if (!validateFile(file)) return null
        return inspect(file)?.toCandidate(role, file)
    }

    @Synchronized
    fun clear(role: GuardDbMaintenanceProtocol.Role): Boolean {
        val candidate = candidateFile(role)
        if (candidate.exists() && !candidate.delete()) return false
        File(directory, guardDbCandidatePendingName(role)).delete()
        return !directory.exists() || syncDirectory(directory)
    }

    @Synchronized
    fun clear(): Boolean {
        var cleared = true
        GuardDbMaintenanceProtocol.Role.values().forEach { role ->
            val file = candidateFile(role)
            if (file.exists() && !file.delete()) cleared = false
            File(directory, guardDbCandidatePendingName(role)).delete()
        }
        return cleared && (!directory.exists() || syncDirectory(directory))
    }

    private fun candidateFile(role: GuardDbMaintenanceProtocol.Role): File =
        File(directory, "guard-db-candidate-${role.name.lowercase()}.apk")

    private fun GuardDbCandidateInspection.toCandidate(
        role: GuardDbMaintenanceProtocol.Role,
        file: File,
    ): GuardDbMaintenanceProtocol.Candidate = GuardDbMaintenanceProtocol.Candidate(
        role = role,
        file = file,
        bytes = bytes,
        sha256 = sha256,
        versionCode = versionCode,
        contractMinimum = contractMinimum,
        contractMaximum = contractMaximum,
        expectedSchema = expectedSchema,
        settingsAuthorityVersion = settingsAuthorityVersion,
        settingsAuthorityBytes = settingsAuthorityBytes,
        settingsAuthoritySha256 = settingsAuthoritySha256,
    )
}

/**
 * The temporary a claim copies into before its atomic move. It exists only inside one synchronized
 * [GuardDbAppStaging.claim], which deletes it on every exit, so one left behind by an earlier process
 * is a dead claim. The final `guard-db-candidate-<role>.apk` is different: it is process-independent
 * by design and stays until the operator discards it.
 */
internal fun guardDbCandidatePendingName(role: GuardDbMaintenanceProtocol.Role): String =
    ".guard-db-candidate-${role.name.lowercase()}.pending"

internal fun guardDbAppStaging(context: Context): GuardDbAppStaging = GuardDbAppStaging(
    // filesDir is a Package Manager-created durable directory. Fixed files directly underneath it
    // avoid a crash seam in which a newly-created child directory was never fsynced into its parent.
    directory = context.filesDir,
    inspect = { inspectGuardDbCandidate(context, it) },
)

private fun fsyncDirectory(directory: File): Boolean = runCatching {
    val descriptor = Os.open(directory.absolutePath, OsConstants.O_RDONLY, 0)
    try {
        Os.fsync(descriptor)
    } finally {
        Os.close(descriptor)
    }
    true
}.getOrDefault(false)

private fun copyAndSync(source: File, destination: File): Boolean = runCatching {
    FileOutputStream(destination).use { output ->
        source.inputStream().use { input -> input.copyTo(output) }
        Os.chmod(destination.absolutePath, 0x180) // exact 0600 before file fsync
        output.fd.sync()
    }
    true
}.getOrDefault(false)

internal fun validGuardDbAppFile(file: File): Boolean = runCatching {
    val stat = Os.lstat(file.absolutePath)
    (stat.st_mode and OsConstants.S_IFMT) == OsConstants.S_IFREG && stat.st_nlink == 1L &&
        stat.st_uid == Process.myUid() && stat.st_gid == Process.myUid() &&
        (stat.st_mode and 0x1ff) == 0x180
}.getOrDefault(false)

private fun atomicMoveWithoutReplacement(source: File, destination: File): Boolean = runCatching {
    Files.move(source.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE)
    true
}.getOrDefault(false)
