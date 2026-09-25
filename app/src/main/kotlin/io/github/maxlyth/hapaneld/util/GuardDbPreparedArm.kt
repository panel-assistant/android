package io.github.maxlyth.hapaneld.util

import android.content.Context
import android.os.Process
import android.system.Os
import android.system.OsConstants
import io.github.maxlyth.hapaneld.persistence.CleanDatabaseProof
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/**
 * Exact clean-database observation retained between the writer shutdown and the separately approved
 * helper custody transfer. No helper mutation occurs until a LAN peer and the physical panel approve
 * this exact record in the writer-free successor process.
 */
internal data class GuardDbPreparedArm(
    val session: String,
    val bootNonce: String,
    val aBytes: Long,
    val aSha256: String,
    val aVersionCode: Long,
    val aContractMinimum: Int,
    val aContractMaximum: Int,
    val aSchema: Int,
    val bBytes: Long,
    val bSha256: String,
    val bVersionCode: Long,
    val bContractMinimum: Int,
    val bContractMaximum: Int,
    val bSchema: Int,
    val databaseBytes: Long,
    val databaseSha256: String,
    val databaseSchema: Int,
    val appStateRows: Long,
    val orderedAppStateSha256: String,
    val settingsSemanticSha256: String,
    val overallBudgetMs: Long,
    val settingsAuthorityVersion: Int,
    val settingsAuthorityBytes: Long,
    val settingsAuthoritySha256: String,
    val securityAuthorityEpoch: Long,
) {
    init {
        require(GuardDbMaintenanceProtocol.validSession(session))
        require(GuardDbMaintenanceProtocol.validSha256(bootNonce))
        require(aBytes > 0L && bBytes > 0L && databaseBytes > 0L && appStateRows > 0L)
        require(GuardDbMaintenanceProtocol.validSha256(aSha256))
        require(GuardDbMaintenanceProtocol.validSha256(bSha256) && aSha256 != bSha256)
        require(GuardDbMaintenanceProtocol.validSha256(databaseSha256))
        require(GuardDbMaintenanceProtocol.validSha256(orderedAppStateSha256))
        require(GuardDbMaintenanceProtocol.validSha256(settingsSemanticSha256))
        require(aVersionCode > 0L && bVersionCode > aVersionCode)
        require(aContractMinimum > 0 && aContractMaximum >= aContractMinimum)
        require(bContractMinimum > 0 && bContractMaximum >= bContractMinimum)
        require(aSchema in aContractMinimum..aContractMaximum)
        require(bSchema in bContractMinimum..bContractMaximum && bSchema == aSchema + 1)
        require(databaseSchema == aSchema)
        require(overallBudgetMs in GuardDbMaintenanceProtocol.MIN_OVERALL_BUDGET_MS..
            GuardDbMaintenanceProtocol.MAX_OVERALL_BUDGET_MS)
        require(settingsAuthorityVersion == GuardDbSettingsAuthority.VERSION)
        require(settingsAuthorityBytes in 1..GuardDbSettingsAuthority.MAX_BYTES)
        require(GuardDbMaintenanceProtocol.validSha256(settingsAuthoritySha256))
        require(securityAuthorityEpoch > 0L)
    }

    fun proof(): CleanDatabaseProof = CleanDatabaseProof(
        databaseBytes = databaseBytes,
        sha256 = databaseSha256,
        userVersion = databaseSchema,
        appStateRows = appStateRows,
        orderedAppStateSha256 = orderedAppStateSha256,
        settingsSemanticSha256 = settingsSemanticSha256,
    )

    fun exactManifest(staging: GuardDbAppStaging): GuardDbArmManifest? {
        val a = staging.load(GuardDbMaintenanceProtocol.Role.A) ?: return null
        val b = staging.load(GuardDbMaintenanceProtocol.Role.B) ?: return null
        if (!matches(a) || !matches(b)) return null
        val authority = GuardDbSettingsAuthorityStore(requireNotNull(a.file.parentFile)).load()?.takeIf {
            it.version == settingsAuthorityVersion && it.bytes == settingsAuthorityBytes &&
                it.sha256 == settingsAuthoritySha256
        } ?: return null
        return runCatching {
            GuardDbArmManifest(session, bootNonce, a, b, overallBudgetMs, authority, securityAuthorityEpoch)
        }.getOrNull()
    }

    fun matches(manifest: GuardDbArmManifest): Boolean =
        manifest.session == session && manifest.bootNonce == bootNonce &&
            manifest.overallBudgetMs == overallBudgetMs &&
            manifest.settingsAuthority.version == settingsAuthorityVersion &&
            manifest.settingsAuthority.bytes == settingsAuthorityBytes &&
            manifest.settingsAuthority.sha256 == settingsAuthoritySha256 &&
            manifest.securityAuthorityEpoch == securityAuthorityEpoch &&
            matches(manifest.a) && matches(manifest.b)

    fun matches(sentinel: GuardDbStartupSentinel): Boolean =
        sentinel.session == session && sentinel.bootNonce == bootNonce &&
            sentinel.aSha256 == aSha256 && sentinel.aVersionCode == aVersionCode && sentinel.aSchema == aSchema &&
            sentinel.bSha256 == bSha256 && sentinel.bVersionCode == bVersionCode && sentinel.bSchema == bSchema
            && sentinel.settingsAuthorityVersion == settingsAuthorityVersion &&
            sentinel.settingsAuthorityBytes == settingsAuthorityBytes &&
            sentinel.settingsAuthoritySha256 == settingsAuthoritySha256 &&
            sentinel.securityAuthorityEpoch == securityAuthorityEpoch

    fun canonical(): String = listOf(
        "GUARD_DB_PREPARED_ARM_V1", session, bootNonce,
        aBytes, aSha256, aVersionCode, aContractMinimum, aContractMaximum, aSchema,
        bBytes, bSha256, bVersionCode, bContractMinimum, bContractMaximum, bSchema,
        databaseBytes, databaseSha256, databaseSchema, appStateRows,
        orderedAppStateSha256, settingsSemanticSha256, overallBudgetMs,
        settingsAuthorityVersion, settingsAuthorityBytes, settingsAuthoritySha256,
        securityAuthorityEpoch,
    ).joinToString("\u0000")

    private fun matches(candidate: GuardDbMaintenanceProtocol.Candidate): Boolean = when (candidate.role) {
        GuardDbMaintenanceProtocol.Role.A ->
            candidate.bytes == aBytes && candidate.sha256 == aSha256 && candidate.versionCode == aVersionCode &&
            candidate.contractMinimum == aContractMinimum && candidate.contractMaximum == aContractMaximum &&
                candidate.expectedSchema == aSchema && candidate.settingsAuthorityVersion == settingsAuthorityVersion &&
                candidate.settingsAuthorityBytes == settingsAuthorityBytes &&
                candidate.settingsAuthoritySha256 == settingsAuthoritySha256
        GuardDbMaintenanceProtocol.Role.B ->
            candidate.bytes == bBytes && candidate.sha256 == bSha256 && candidate.versionCode == bVersionCode &&
            candidate.contractMinimum == bContractMinimum && candidate.contractMaximum == bContractMaximum &&
                candidate.expectedSchema == bSchema && candidate.settingsAuthorityVersion == settingsAuthorityVersion &&
                candidate.settingsAuthorityBytes == settingsAuthorityBytes &&
                candidate.settingsAuthoritySha256 == settingsAuthoritySha256
    }

    companion object {
        fun create(manifest: GuardDbArmManifest, proof: CleanDatabaseProof): GuardDbPreparedArm = GuardDbPreparedArm(
            session = manifest.session,
            bootNonce = manifest.bootNonce,
            aBytes = manifest.a.bytes,
            aSha256 = manifest.a.sha256,
            aVersionCode = manifest.a.versionCode,
            aContractMinimum = manifest.a.contractMinimum,
            aContractMaximum = manifest.a.contractMaximum,
            aSchema = manifest.a.expectedSchema,
            bBytes = manifest.b.bytes,
            bSha256 = manifest.b.sha256,
            bVersionCode = manifest.b.versionCode,
            bContractMinimum = manifest.b.contractMinimum,
            bContractMaximum = manifest.b.contractMaximum,
            bSchema = manifest.b.expectedSchema,
            databaseBytes = proof.databaseBytes,
            databaseSha256 = proof.sha256,
            databaseSchema = proof.userVersion,
            appStateRows = proof.appStateRows,
            orderedAppStateSha256 = proof.orderedAppStateSha256,
            settingsSemanticSha256 = proof.settingsSemanticSha256,
            overallBudgetMs = manifest.overallBudgetMs,
            settingsAuthorityVersion = manifest.settingsAuthority.version,
            settingsAuthorityBytes = manifest.settingsAuthority.bytes,
            settingsAuthoritySha256 = manifest.settingsAuthority.sha256,
            securityAuthorityEpoch = manifest.securityAuthorityEpoch,
        )
    }
}

internal sealed interface GuardDbPreparedArmLoad {
    data object Absent : GuardDbPreparedArmLoad
    data class Valid(val prepared: GuardDbPreparedArm) : GuardDbPreparedArmLoad
    data object Corrupt : GuardDbPreparedArmLoad
}

internal class GuardDbPreparedArmStore(
    private val directory: File,
    private val syncDirectory: (File) -> Boolean = ::syncGuardDbPreparedDirectory,
    private val validateFile: (File) -> Boolean = ::validGuardDbPreparedFile,
) {
    private val record = File(directory, "guard-db-prepared-arm.v1")
    private val temporary = File(directory, ".guard-db-prepared-arm.v1.pending")

    /**
     * Whether the record or its pending write exists at all, read without reconciling either. The
     * pending write is looked at first: a publish moves it onto the record, never the other way.
     */
    fun present(): Boolean = listOf(temporary, record).any { !Files.notExists(it.toPath(), LinkOption.NOFOLLOW_LINKS) }

    @Synchronized
    fun load(): GuardDbPreparedArmLoad {
        if (!reconcilePending()) return GuardDbPreparedArmLoad.Corrupt
        return loadRecord()
    }

    private fun loadRecord(): GuardDbPreparedArmLoad {
        if (Files.notExists(record.toPath(), LinkOption.NOFOLLOW_LINKS)) return GuardDbPreparedArmLoad.Absent
        if (!validateFile(record) || record.length() !in 1..4096) return GuardDbPreparedArmLoad.Corrupt
        val bytes = runCatching { record.readBytes() }.getOrNull() ?: return GuardDbPreparedArmLoad.Corrupt
        return parseGuardDbPreparedArm(bytes)?.let(GuardDbPreparedArmLoad::Valid) ?: GuardDbPreparedArmLoad.Corrupt
    }

    private fun reconcilePending(): Boolean {
        if (Files.notExists(temporary.toPath(), LinkOption.NOFOLLOW_LINKS)) return true
        if (!validateFile(temporary) || temporary.length() !in 1..4096) return false
        val pending = runCatching { temporary.readBytes() }.getOrNull()
            ?.let(::parseGuardDbPreparedArm) ?: return false
        if (loadRecord() !is GuardDbPreparedArmLoad.Absent) return false
        return runCatching {
            Files.move(
                temporary.toPath(), record.toPath(),
                StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING,
            )
            syncDirectory(directory)
        }.getOrDefault(false)
    }

    @Synchronized
    fun write(prepared: GuardDbPreparedArm): Boolean = when (val current = load()) {
        GuardDbPreparedArmLoad.Absent -> publish(prepared)
        is GuardDbPreparedArmLoad.Valid -> current.prepared == prepared && syncDirectory(directory)
        GuardDbPreparedArmLoad.Corrupt -> false
    }

    private fun publish(prepared: GuardDbPreparedArm): Boolean {
        if (!directory.isDirectory || Files.isSymbolicLink(directory.toPath())) return false
        if (!Files.notExists(temporary.toPath(), LinkOption.NOFOLLOW_LINKS)) return false
        return runCatching {
            FileOutputStream(temporary).use { output ->
                output.write(encodeGuardDbPreparedArm(prepared))
                Os.chmod(temporary.absolutePath, 0x180)
                output.fd.sync()
            }
            Files.move(
                temporary.toPath(), record.toPath(),
                StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING,
            )
            syncDirectory(directory)
        }.getOrDefault(false).also { temporary.delete() }
    }

    @Synchronized
    fun clear(expectedSession: String): Boolean {
        val current = load()
        if (current is GuardDbPreparedArmLoad.Valid && current.prepared.session != expectedSession) return false
        if (current is GuardDbPreparedArmLoad.Corrupt) return false
        if (current is GuardDbPreparedArmLoad.Valid && !record.delete()) return false
        return syncDirectory(directory)
    }
}

internal fun guardDbPreparedArmStore(context: Context): GuardDbPreparedArmStore =
    GuardDbPreparedArmStore(context.noBackupFilesDir)

internal fun encodeGuardDbPreparedArm(prepared: GuardDbPreparedArm): ByteArray {
    val payload = buildString {
        append("HAPANELD_GUARD_DB_PREPARED_ARM_V1\n")
        append("SESSION ${prepared.session}\n")
        append("BOOT ${prepared.bootNonce}\n")
        append("A ${prepared.aBytes} ${prepared.aSha256} ${prepared.aVersionCode} ${prepared.aContractMinimum} " +
            "${prepared.aContractMaximum} ${prepared.aSchema}\n")
        append("B ${prepared.bBytes} ${prepared.bSha256} ${prepared.bVersionCode} ${prepared.bContractMinimum} " +
            "${prepared.bContractMaximum} ${prepared.bSchema}\n")
        append("BASELINE ${prepared.databaseBytes} ${prepared.databaseSha256} ${prepared.databaseSchema} " +
            "${prepared.appStateRows} ${prepared.orderedAppStateSha256} ${prepared.settingsSemanticSha256}\n")
        append("SETTINGS ${prepared.settingsAuthorityVersion} ${prepared.settingsAuthorityBytes} " +
            "${prepared.settingsAuthoritySha256}\n")
        append("SECURITY_EPOCH ${prepared.securityAuthorityEpoch}\n")
        append("OVERALL_BUDGET_MS ${prepared.overallBudgetMs}\n")
    }
    return (payload + "CHECKSUM ${guardDbPreparedDigest(payload.toByteArray(Charsets.US_ASCII))}\n")
        .toByteArray(Charsets.US_ASCII)
}

internal fun parseGuardDbPreparedArm(bytes: ByteArray?): GuardDbPreparedArm? {
    if (bytes == null || bytes.isEmpty() || bytes.size > 4096 ||
        bytes.any { it.toInt() !in 0x0a..0x7e || it.toInt() in 0x0b..0x1f }
    ) return null
    val text = bytes.toString(Charsets.US_ASCII)
    if (!text.endsWith('\n')) return null
    val lines = text.split('\n').dropLast(1)
    if (lines.size != 10 || lines[0] != "HAPANELD_GUARD_DB_PREPARED_ARM_V1") return null
    fun fields(index: Int, key: String, count: Int): List<String>? {
        val parts = lines[index].split(' ')
        return parts.drop(1).takeIf { parts.size == count + 1 && parts.none(String::isEmpty) && parts[0] == key }
    }
    val session = fields(1, "SESSION", 1)?.singleOrNull() ?: return null
    val boot = fields(2, "BOOT", 1)?.singleOrNull() ?: return null
    val a = fields(3, "A", 6) ?: return null
    val b = fields(4, "B", 6) ?: return null
    val baseline = fields(5, "BASELINE", 6) ?: return null
    val settings = fields(6, "SETTINGS", 3) ?: return null
    val securityEpoch = fields(7, "SECURITY_EPOCH", 1)?.singleOrNull() ?: return null
    val budget = fields(8, "OVERALL_BUDGET_MS", 1)?.singleOrNull() ?: return null
    val checksum = fields(9, "CHECKSUM", 1)?.singleOrNull() ?: return null
    val payload = lines.take(9).joinToString("\n", postfix = "\n").toByteArray(Charsets.US_ASCII)
    if (!MessageDigest.isEqual(
            checksum.toByteArray(Charsets.US_ASCII),
            guardDbPreparedDigest(payload).toByteArray(Charsets.US_ASCII),
        )
    ) return null
    fun positiveLong(value: String): Long? = value.canonicalGuardLong()?.takeIf { it > 0L }
    fun positiveInt(value: String): Int? = positiveLong(value)?.takeIf { it <= Int.MAX_VALUE }?.toInt()
    return runCatching {
        GuardDbPreparedArm(
            session, boot,
            positiveLong(a[0]) ?: return null, a[1], positiveLong(a[2]) ?: return null,
            positiveInt(a[3]) ?: return null, positiveInt(a[4]) ?: return null, positiveInt(a[5]) ?: return null,
            positiveLong(b[0]) ?: return null, b[1], positiveLong(b[2]) ?: return null,
            positiveInt(b[3]) ?: return null, positiveInt(b[4]) ?: return null, positiveInt(b[5]) ?: return null,
            positiveLong(baseline[0]) ?: return null, baseline[1], positiveInt(baseline[2]) ?: return null,
            positiveLong(baseline[3]) ?: return null, baseline[4], baseline[5],
            positiveLong(budget) ?: return null,
            positiveInt(settings[0]) ?: return null, positiveLong(settings[1]) ?: return null, settings[2],
            positiveLong(securityEpoch) ?: return null,
        )
    }.getOrNull()
}

private fun String.canonicalGuardLong(): Long? {
    if (isEmpty() || any { it !in '0'..'9' } || (length > 1 && first() == '0')) return null
    return toLongOrNull()
}

private fun guardDbPreparedDigest(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
    .digest(bytes).joinToString("") { "%02x".format(it) }

private fun validGuardDbPreparedFile(file: File): Boolean = runCatching {
    val stat = Os.lstat(file.absolutePath)
    val type = stat.st_mode and OsConstants.S_IFMT
    type == OsConstants.S_IFREG && stat.st_nlink == 1L &&
        stat.st_uid == Process.myUid() && stat.st_gid == Process.myUid() &&
        (stat.st_mode and 0x1ff) == 0x180
}.getOrDefault(false)

private fun syncGuardDbPreparedDirectory(directory: File): Boolean = runCatching {
    val fd = Os.open(directory.absolutePath, OsConstants.O_RDONLY, 0)
    try {
        Os.fsync(fd)
    } finally {
        Os.close(fd)
    }
    true
}.getOrDefault(false)
