package io.panelassistant.android.util

import android.content.Context
import android.os.Process
import android.system.Os
import android.system.OsConstants
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

internal enum class GuardDbTerminalRetirementState { INTENT, RETRYABLE, COMPLETE }

/** App-private durable authority for the non-replayable terminal namespace retirement. */
internal data class GuardDbTerminalRetirement(
    val state: GuardDbTerminalRetirementState,
    val session: String,
    val finalGeneration: Long,
    val bootNonce: String,
    val aSha256: String,
    val aVersionCode: Long,
    val aSchema: Int,
    val outcome: GuardDbMaintenanceProtocol.Outcome,
    val evidenceSha256: String,
) {
    init {
        require(GuardDbMaintenanceProtocol.validSession(session))
        require(finalGeneration >= 0L && finalGeneration < Long.MAX_VALUE)
        require(GuardDbMaintenanceProtocol.validSha256(bootNonce))
        require(GuardDbMaintenanceProtocol.validSha256(aSha256))
        require(aVersionCode > 0L && aSchema > 0)
        require(GuardDbMaintenanceProtocol.terminalOutcome(outcome))
        require(GuardDbMaintenanceProtocol.validSha256(evidenceSha256))
    }

    fun matchesTerminal(status: GuardDbMaintenanceProtocol.Status): Boolean =
        status.phase == GuardDbMaintenanceProtocol.Phase.FINALIZED &&
            status.generation == finalGeneration && status.session == session &&
            status.bootNonce == bootNonce && status.role == GuardDbMaintenanceProtocol.Role.A &&
            status.apkSha256 == aSha256 && status.versionCode == aVersionCode &&
            status.schema == aSchema && status.error == null && status.outcome == outcome

    fun matchesRetiring(status: GuardDbMaintenanceProtocol.Status): Boolean =
        status.phase == GuardDbMaintenanceProtocol.Phase.RETIRING &&
            status.generation == finalGeneration + 1L && status.session == session &&
            status.bootNonce == bootNonce && status.role == GuardDbMaintenanceProtocol.Role.A &&
            status.apkSha256 == aSha256 && status.versionCode == aVersionCode &&
            status.schema == aSchema && status.error == null && status.outcome == outcome
}

internal sealed interface GuardDbTerminalRetirementLoad {
    data object Absent : GuardDbTerminalRetirementLoad
    data class Valid(val retirement: GuardDbTerminalRetirement) : GuardDbTerminalRetirementLoad
    data object Corrupt : GuardDbTerminalRetirementLoad
}

internal class GuardDbTerminalRetirementStore(
    private val directory: File,
    private val syncDirectory: (File) -> Boolean = ::syncGuardDbTerminalRetirementDirectory,
    private val validateFile: (File) -> Boolean = ::validGuardDbTerminalRetirementFile,
) {
    private val record = File(directory, "guard-db-terminal-retirement.v1")
    private val temporary = File(directory, ".guard-db-terminal-retirement.v1.pending")

    @Synchronized
    fun load(): GuardDbTerminalRetirementLoad {
        if (!reconcilePending()) return GuardDbTerminalRetirementLoad.Corrupt
        return loadRecord()
    }

    private fun loadRecord(): GuardDbTerminalRetirementLoad {
        if (Files.notExists(record.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            return GuardDbTerminalRetirementLoad.Absent
        }
        if (!validateFile(record) || record.length() !in 1..2048) return GuardDbTerminalRetirementLoad.Corrupt
        val bytes = runCatching { record.readBytes() }.getOrNull() ?: return GuardDbTerminalRetirementLoad.Corrupt
        return parseGuardDbTerminalRetirement(bytes)
            ?.let(GuardDbTerminalRetirementLoad::Valid) ?: GuardDbTerminalRetirementLoad.Corrupt
    }

    private fun reconcilePending(): Boolean {
        if (Files.notExists(temporary.toPath(), LinkOption.NOFOLLOW_LINKS)) return true
        if (!validateFile(temporary) || temporary.length() !in 1..2048) return false
        val pending = runCatching { temporary.readBytes() }.getOrNull()
            ?.let(::parseGuardDbTerminalRetirement) ?: return false
        val allowed = when (val current = loadRecord()) {
            GuardDbTerminalRetirementLoad.Absent -> pending.state == GuardDbTerminalRetirementState.INTENT
            GuardDbTerminalRetirementLoad.Corrupt -> false
            is GuardDbTerminalRetirementLoad.Valid -> when (current.retirement.state to pending.state) {
                GuardDbTerminalRetirementState.INTENT to GuardDbTerminalRetirementState.COMPLETE,
                GuardDbTerminalRetirementState.INTENT to GuardDbTerminalRetirementState.RETRYABLE,
                -> current.retirement.copy(state = pending.state) == pending
                GuardDbTerminalRetirementState.COMPLETE to GuardDbTerminalRetirementState.INTENT,
                GuardDbTerminalRetirementState.RETRYABLE to GuardDbTerminalRetirementState.INTENT,
                -> allowedGuardDbTerminalIntentReplacement(current.retirement, pending)
                else -> false
            }
        }
        if (!allowed) return false
        return runCatching {
            Files.move(
                temporary.toPath(), record.toPath(),
                StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING,
            )
            syncDirectory(directory)
        }.getOrDefault(false)
    }

    @Synchronized
    fun writeIntent(retirement: GuardDbTerminalRetirement): Boolean {
        if (retirement.state != GuardDbTerminalRetirementState.INTENT) return false
        return when (val current = load()) {
            GuardDbTerminalRetirementLoad.Absent -> write(retirement)
            is GuardDbTerminalRetirementLoad.Valid -> when {
                current.retirement == retirement -> syncDirectory(directory)
                allowedGuardDbTerminalIntentReplacement(current.retirement, retirement) -> write(retirement)
                else -> false
            }
            GuardDbTerminalRetirementLoad.Corrupt -> false
        }
    }

    @Synchronized
    fun markComplete(expectedIntent: GuardDbTerminalRetirement): Boolean {
        if (expectedIntent.state != GuardDbTerminalRetirementState.INTENT) return false
        val current = (load() as? GuardDbTerminalRetirementLoad.Valid)?.retirement ?: return false
        val complete = expectedIntent.copy(state = GuardDbTerminalRetirementState.COMPLETE)
        if (current == complete) return syncDirectory(directory)
        if (current != expectedIntent) return false
        return write(complete)
    }

    @Synchronized
    fun markRetryable(expectedIntent: GuardDbTerminalRetirement): Boolean {
        if (expectedIntent.state != GuardDbTerminalRetirementState.INTENT) return false
        val current = (load() as? GuardDbTerminalRetirementLoad.Valid)?.retirement ?: return false
        val retryable = expectedIntent.copy(state = GuardDbTerminalRetirementState.RETRYABLE)
        if (current == retryable) return true
        if (current != expectedIntent) return false
        return write(retryable)
    }

    private fun write(retirement: GuardDbTerminalRetirement): Boolean = runCatching {
        if (!directory.isDirectory || Files.isSymbolicLink(directory.toPath())) return false
        if (!Files.notExists(temporary.toPath(), LinkOption.NOFOLLOW_LINKS)) return false
        FileOutputStream(temporary).use { output ->
            output.write(encodeGuardDbTerminalRetirement(retirement))
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

private fun allowedGuardDbTerminalIntentReplacement(
    current: GuardDbTerminalRetirement,
    pending: GuardDbTerminalRetirement,
): Boolean = pending.state == GuardDbTerminalRetirementState.INTENT && current.state in setOf(
    GuardDbTerminalRetirementState.RETRYABLE,
    GuardDbTerminalRetirementState.COMPLETE,
) && !(current.state == GuardDbTerminalRetirementState.COMPLETE &&
    current.copy(state = GuardDbTerminalRetirementState.INTENT) == pending)

internal fun guardDbTerminalRetirementStore(context: Context): GuardDbTerminalRetirementStore =
    GuardDbTerminalRetirementStore(context.noBackupFilesDir)

internal fun encodeGuardDbTerminalRetirement(retirement: GuardDbTerminalRetirement): ByteArray {
    val payload = buildString {
        append("HAPANELD_GUARD_DB_TERMINAL_RETIREMENT_V1\n")
        append("STATE ${retirement.state}\n")
        append("SESSION ${retirement.session}\n")
        append("FINAL_GENERATION ${retirement.finalGeneration}\n")
        append("BOOT ${retirement.bootNonce}\n")
        append("A ${retirement.aSha256} ${retirement.aVersionCode} ${retirement.aSchema}\n")
        append("OUTCOME ${retirement.outcome}\n")
        append("EVIDENCE_SHA256 ${retirement.evidenceSha256}\n")
    }
    return (payload + "CHECKSUM ${terminalRetirementDigest(payload.toByteArray(Charsets.US_ASCII))}\n")
        .toByteArray(Charsets.US_ASCII)
}

internal fun parseGuardDbTerminalRetirement(bytes: ByteArray?): GuardDbTerminalRetirement? {
    if (bytes == null || bytes.isEmpty() || bytes.size > 2048 ||
        bytes.any { it.toInt() !in 0x0a..0x7e || it.toInt() in 0x0b..0x1f }
    ) return null
    val text = bytes.toString(Charsets.US_ASCII)
    if (!text.endsWith('\n')) return null
    val lines = text.split('\n').dropLast(1)
    if (lines.size != 9 || lines[0] != "HAPANELD_GUARD_DB_TERMINAL_RETIREMENT_V1") return null
    fun exact(index: Int, key: String, count: Int): List<String>? {
        val fields = lines[index].split(' ')
        return fields.drop(1).takeIf {
            fields.size == count + 1 && fields.none(String::isEmpty) && fields[0] == key
        }
    }
    val state = exact(1, "STATE", 1)?.singleOrNull()?.let { value ->
        GuardDbTerminalRetirementState.values().firstOrNull { it.name == value }
    } ?: return null
    val session = exact(2, "SESSION", 1)?.singleOrNull() ?: return null
    val generation = exact(3, "FINAL_GENERATION", 1)?.singleOrNull()?.canonicalNonNegativeLong() ?: return null
    val boot = exact(4, "BOOT", 1)?.singleOrNull() ?: return null
    val a = exact(5, "A", 3) ?: return null
    val outcome = exact(6, "OUTCOME", 1)?.singleOrNull()?.let { value ->
        GuardDbMaintenanceProtocol.Outcome.values().firstOrNull { it.name == value }
    } ?: return null
    val evidence = exact(7, "EVIDENCE_SHA256", 1)?.singleOrNull() ?: return null
    val checksum = exact(8, "CHECKSUM", 1)?.singleOrNull() ?: return null
    val payload = lines.take(8).joinToString("\n", postfix = "\n").toByteArray(Charsets.US_ASCII)
    if (!MessageDigest.isEqual(
            checksum.toByteArray(Charsets.US_ASCII),
            terminalRetirementDigest(payload).toByteArray(Charsets.US_ASCII),
        )
    ) return null
    return runCatching {
        GuardDbTerminalRetirement(
            state = state,
            session = session,
            finalGeneration = generation,
            bootNonce = boot,
            aSha256 = a[0],
            aVersionCode = a[1].canonicalPositiveLong() ?: return null,
            aSchema = a[2].canonicalPositiveLong()?.takeIf { it <= Int.MAX_VALUE }?.toInt() ?: return null,
            outcome = outcome,
            evidenceSha256 = evidence,
        )
    }.getOrNull()
}

private fun String.canonicalNonNegativeLong(): Long? {
    if (isEmpty() || any { it !in '0'..'9' } || (length > 1 && first() == '0')) return null
    return toLongOrNull()
}

private fun String.canonicalPositiveLong(): Long? = canonicalNonNegativeLong()?.takeIf { it > 0L }

private fun terminalRetirementDigest(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
    .digest(bytes).joinToString("") { "%02x".format(it) }

private fun validGuardDbTerminalRetirementFile(file: File): Boolean = runCatching {
    val stat = Os.lstat(file.absolutePath)
    val type = stat.st_mode and OsConstants.S_IFMT
    type == OsConstants.S_IFREG && stat.st_nlink == 1L &&
        stat.st_uid == Process.myUid() && stat.st_gid == Process.myUid() &&
        (stat.st_mode and 0x1ff) == 0x180
}.getOrDefault(false)

private fun syncGuardDbTerminalRetirementDirectory(directory: File): Boolean = runCatching {
    val fd = Os.open(directory.absolutePath, OsConstants.O_RDONLY, 0)
    try {
        Os.fsync(fd)
    } finally {
        Os.close(fd)
    }
    true
}.getOrDefault(false)
