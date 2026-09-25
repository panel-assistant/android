package io.github.maxlyth.hapaneld.dashboard

import android.os.Process
import android.system.Os
import android.system.OsConstants
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** Optional Guard identity retained in the durable restore receipt. */
internal data class DatabaseRestoreGuardBinding(val session: String, val generation: Long) {
    init {
        require(Regex("[0-9a-f]{64}").matches(session))
        require(generation >= 0L)
    }
}

/** The pre-open SQLiteOpenHelper name calculation is synchronous, so a thread-local binding is exact. */
internal object DatabaseRestoreGuardContext {
    private val binding = ThreadLocal<DatabaseRestoreGuardBinding?>()

    fun current(): DatabaseRestoreGuardBinding? = binding.get()

    fun <T> withBinding(value: DatabaseRestoreGuardBinding, action: () -> T): T {
        check(binding.get() == null) { "nested database restore Guard binding" }
        binding.set(value)
        return try {
            action()
        } finally {
            binding.remove()
        }
    }
}

internal enum class DatabaseRestoreState { PREPARED, SOURCE_ASIDE, RESTORED }

internal data class DatabaseRestoreRecord(
    val state: DatabaseRestoreState,
    val targetDirectory: String,
    val targetName: String,
    val sourceSchema: Int,
    val sourceBytes: Long,
    val sourceSha256: String,
    val stagedSchema: Int,
    val stagedBytes: Long,
    val stagedSha256: String,
    val supersededName: String,
    val guard: DatabaseRestoreGuardBinding?,
) {
    init {
        require(targetDirectory.isNotEmpty() && File(targetDirectory).isAbsolute)
        require(SAFE_NAME.matches(targetName) && SAFE_NAME.matches(supersededName))
        require(sourceSchema > stagedSchema && stagedSchema > 0)
        require(sourceBytes > 0L && stagedBytes > 0L)
        require(SHA256.matches(sourceSha256) && SHA256.matches(stagedSha256))
    }

    private companion object {
        val SAFE_NAME = Regex("[A-Za-z0-9._-]{1,160}")
        val SHA256 = Regex("[0-9a-f]{64}")
    }
}

internal sealed interface DatabaseRestoreResult {
    data object Absent : DatabaseRestoreResult
    data class Restored(val reconcile: SchemaReconcile, val record: DatabaseRestoreRecord) : DatabaseRestoreResult
    data class Hold(val reason: String) : DatabaseRestoreResult
}

internal sealed interface DatabaseRestoreOpenedReceipt {
    data object Absent : DatabaseRestoreOpenedReceipt
    data object OrdinaryConsumed : DatabaseRestoreOpenedReceipt
    data class GuardRetained(val record: DatabaseRestoreRecord) : DatabaseRestoreOpenedReceipt
    data object Hold : DatabaseRestoreOpenedReceipt
}

internal enum class DatabaseRestoreEstablishedReceipt { MATCHES, ABSENT, MISMATCH }

/** Named cut points used by the process-death fixture. Production's observer is a no-op. */
internal enum class DatabaseRestoreCut {
    PREPARED_FILE_SYNC,
    PREPARED_DIRECTORY_SYNC,
    PREPARED_RECORD_FILE_SYNC,
    PREPARED_RECORD_RENAME,
    PREPARED_RECORD_DIRECTORY_SYNC,
    SOURCE_RENAME,
    SOURCE_DIRECTORY_SYNC,
    SOURCE_ASIDE_RECORD_FILE_SYNC,
    SOURCE_ASIDE_RECORD_RENAME,
    SOURCE_ASIDE_RECORD_DIRECTORY_SYNC,
    TARGET_RENAME,
    TARGET_DIRECTORY_SYNC,
    RESTORED_RECORD_FILE_SYNC,
    RESTORED_RECORD_RENAME,
    RESTORED_RECORD_DIRECTORY_SYNC,
    BEFORE_RESULT,
    AFTER_RESULT_CREATED,
}

internal class DatabaseRestoreHoldException(message: String) : java.sql.SQLException(message)

internal data class DatabaseRestoreSidecarIdentity(
    val regular: Boolean,
    val device: Long,
    val inode: Long,
    val uid: Int,
    val gid: Int,
    val links: Long,
    val bytes: Long,
    val sha256: String,
)

internal fun exactOwnedStableDatabaseSidecar(
    first: DatabaseRestoreSidecarIdentity?,
    second: DatabaseRestoreSidecarIdentity?,
    expectedUid: Int,
    requireEmpty: Boolean,
): Boolean = first != null && first == second && first.regular && first.links == 1L &&
    first.uid == expectedUid && first.gid == expectedUid && (!requireEmpty || first.bytes == 0L)

/** Serializes pre-open restore reconciliation through the restored owner's first SQLite open. */
internal class DatabaseRestoreOpenLease {
    private val lock = ReentrantLock(true)
    private var establishedGuard: DatabaseRestoreRecord? = null
    private var admissionActive = false
    private var poisoned = false

    fun reconcileAndOpen(
        establishedGuardReceipt: (DatabaseRestoreRecord) -> DatabaseRestoreEstablishedReceipt,
        reconcile: () -> Boolean,
        open: (joiningRetainedGuard: Boolean) -> DatabaseRestoreRecord?,
    ) = lock.withLock {
        if (poisoned) throw DatabaseRestoreHoldException("database restore open did not settle")
        if (admissionActive) throw DatabaseRestoreHoldException("database restore admission already in progress")
        admissionActive = true
        try {
            val retained = establishedGuard
            if (retained != null) {
                if (establishedGuardReceipt(retained) == DatabaseRestoreEstablishedReceipt.MISMATCH) {
                    throw DatabaseRestoreHoldException("established Guard restore receipt changed")
                }
                try {
                    open(true)
                } catch (failure: Throwable) {
                    poisoned = true
                    throw failure
                }
                return@withLock
            }
            if (!reconcile()) return@withLock
            try {
                open(false)?.let { record ->
                    check(record.state == DatabaseRestoreState.RESTORED && record.guard != null)
                    establishedGuard = record
                }
            } catch (failure: Throwable) {
                poisoned = true
                throw failure
            }
        } finally {
            admissionActive = false
        }
    }
}

/**
 * Same-directory, crash-durable schema restore. The main files move atomically; a checksummed record
 * makes every intermediate topology resumable without treating a missing canonical primary as fresh.
 */
internal class DatabaseRestoreTransaction(
    private val target: File,
    private val guard: DatabaseRestoreGuardBinding? = DatabaseRestoreGuardContext.current(),
    private val ownedStableSidecar: (File, Boolean) -> Boolean = ::ownedStableDatabaseSidecar,
    private val beforeEstablishedReceiptRead: () -> Unit = {},
    private val cut: (DatabaseRestoreCut) -> Unit = {},
) {
    private val directory = requireNotNull(target.absoluteFile.parentFile).absoluteFile.normalize()
    private val normalizedTarget = target.absoluteFile.normalize()
    private val recordFile = File(directory, ".${target.name}.restore.v1")
    private val recordTemporary = File(directory, ".${target.name}.restore.v1.tmp")
    private val preparedFile = File(directory, ".${target.name}.restore.prepared.v1")

    fun reconcile(): DatabaseRestoreResult {
        if (!directory.isDirectory || Files.isSymbolicLink(directory.toPath())) {
            return DatabaseRestoreResult.Hold("database directory is not an exact directory")
        }
        val record = loadAuthoritativeRecord() ?: return when {
            entryExists(recordFile) || entryExists(recordTemporary) ->
                DatabaseRestoreResult.Hold("unbound or corrupt database restore state")
            entryExists(preparedFile) && discardExactPreMutationPreparedOrphan() -> DatabaseRestoreResult.Absent
            entryExists(preparedFile) -> DatabaseRestoreResult.Hold("ambiguous unbound database recovery copy")
            else -> DatabaseRestoreResult.Absent
        }
        if (!recordBindsTarget(record)) return DatabaseRestoreResult.Hold("database restore target changed")
        return resume(record)
    }

    fun restore(
        staged: File,
        sourceSchema: Int,
        stagedSchema: Int,
        checkpoint: (File) -> Boolean,
    ): DatabaseRestoreResult {
        when (val existing = reconcile()) {
            DatabaseRestoreResult.Absent -> Unit
            else -> return existing
        }
        if (sourceSchema <= stagedSchema || !exactRegularFile(normalizedTarget) || !exactRegularFile(staged)) {
            return DatabaseRestoreResult.Hold("database restore inputs are not exact regular files")
        }
        if (!checkpoint(normalizedTarget) || !normalizeCanonicalSidecars()) {
            return DatabaseRestoreResult.Hold("database did not reach a closed canonical checkpoint")
        }
        val source = identity(normalizedTarget)
            ?: return DatabaseRestoreResult.Hold("database source identity unavailable")
        val recovery = identity(staged)
            ?: return DatabaseRestoreResult.Hold("database recovery identity unavailable")
        val superseded = supersededFile(normalizedTarget, sourceSchema)
        if (!replaceableSupersededTopology(superseded)) {
            return DatabaseRestoreResult.Hold("superseded destination is not exactly replaceable")
        }
        if (!copyPrepared(staged, recovery)) {
            return DatabaseRestoreResult.Hold("database recovery preparation failed")
        }
        val record = DatabaseRestoreRecord(
            state = DatabaseRestoreState.PREPARED,
            targetDirectory = directory.path,
            targetName = normalizedTarget.name,
            sourceSchema = sourceSchema,
            sourceBytes = source.bytes,
            sourceSha256 = source.sha256,
            stagedSchema = stagedSchema,
            stagedBytes = recovery.bytes,
            stagedSha256 = recovery.sha256,
            supersededName = superseded.name,
            guard = guard,
        )
        if (!publish(record)) return DatabaseRestoreResult.Hold("database restore PREPARED publication failed")
        return resume(record)
    }

    /** Remove only an exact terminal receipt. The superseded data remains available for recovery. */
    fun clearRestored(expectedGuardSession: String): Boolean {
        val record = loadAuthoritativeRecord()
        if (record == null) return !entryExists(recordFile) && !entryExists(recordTemporary) &&
            !entryExists(preparedFile)
        if (!recordBindsTarget(record) || record.guard?.session != expectedGuardSession) return false
        return runCatching {
            Files.deleteIfExists(recordTemporary.toPath())
            Files.delete(recordFile.toPath())
            syncDirectory()
        }.getOrDefault(false)
    }

    /** Settle a receipt only after SQLiteOpenHelper has successfully opened the restored owner. */
    fun settleRestoredAfterOpen(): DatabaseRestoreOpenedReceipt {
        val record = loadAuthoritativeRecord() ?: return if (!entryExists(recordFile) &&
            !entryExists(recordTemporary) && !entryExists(preparedFile)
        ) DatabaseRestoreOpenedReceipt.Absent else DatabaseRestoreOpenedReceipt.Hold
        val aside = File(directory, record.supersededName)
        if (!recordBindsTarget(record) || record.state != DatabaseRestoreState.RESTORED ||
            !matches(normalizedTarget, record.stagedBytes, record.stagedSha256) ||
            !matches(aside, record.sourceBytes, record.sourceSha256) ||
            !exactStandaloneSuperseded(aside) || entryExists(preparedFile) ||
            !exactOpenCanonicalSidecars()
        ) return DatabaseRestoreOpenedReceipt.Hold
        if (record.guard != null) return DatabaseRestoreOpenedReceipt.GuardRetained(record)
        val consumed = runCatching {
            Files.deleteIfExists(recordTemporary.toPath())
            Files.delete(recordFile.toPath())
            syncDirectory()
        }.getOrDefault(false)
        return if (consumed) DatabaseRestoreOpenedReceipt.OrdinaryConsumed
        else DatabaseRestoreOpenedReceipt.Hold
    }

    /** A live in-process Guard owner accepts its exact receipt or its legitimate terminal removal. */
    fun establishedGuardReceipt(expected: DatabaseRestoreRecord): DatabaseRestoreEstablishedReceipt {
        if (expected.state != DatabaseRestoreState.RESTORED || expected.guard == null ||
            !recordBindsTarget(expected) || entryExists(recordTemporary) || entryExists(preparedFile)
        ) return DatabaseRestoreEstablishedReceipt.MISMATCH
        if (!entryExists(recordFile)) return DatabaseRestoreEstablishedReceipt.ABSENT
        beforeEstablishedReceiptRead()
        val current = readRecord(recordFile)
        if (current != null) {
            return if (current == expected && !entryExists(recordTemporary) && !entryExists(preparedFile)) {
                DatabaseRestoreEstablishedReceipt.MATCHES
            } else DatabaseRestoreEstablishedReceipt.MISMATCH
        }
        return if (!entryExists(recordFile) && !entryExists(recordTemporary) && !entryExists(preparedFile)) {
            DatabaseRestoreEstablishedReceipt.ABSENT
        } else {
            DatabaseRestoreEstablishedReceipt.MISMATCH
        }
    }

    private fun resume(initial: DatabaseRestoreRecord): DatabaseRestoreResult {
        var record = initial
        while (true) {
            val aside = File(directory, record.supersededName)
            val targetIsSource = matches(normalizedTarget, record.sourceBytes, record.sourceSha256)
            val targetIsStaged = matches(normalizedTarget, record.stagedBytes, record.stagedSha256)
            val asideIsSource = matches(aside, record.sourceBytes, record.sourceSha256)
            val preparedIsStaged = matches(preparedFile, record.stagedBytes, record.stagedSha256)
            when (record.state) {
                DatabaseRestoreState.PREPARED -> when {
                    targetIsSource && preparedIsStaged && replaceableSupersededTopology(aside) -> {
                        // PREPARED durably binds the current source before this latest-wins rotation.
                        // Replacing the one exact prior aside in the same atomic rename means the current
                        // source is always either canonical or retained here, never absent from both names.
                        if (!noCanonicalSidecars() || !atomicMove(normalizedTarget, aside, replace = true)) {
                            return DatabaseRestoreResult.Hold("database source rename failed")
                        }
                        cut(DatabaseRestoreCut.SOURCE_RENAME)
                        if (!syncDirectory()) return DatabaseRestoreResult.Hold("database source rename not durable")
                        cut(DatabaseRestoreCut.SOURCE_DIRECTORY_SYNC)
                        record = record.copy(state = DatabaseRestoreState.SOURCE_ASIDE)
                        if (!publish(record)) {
                            return DatabaseRestoreResult.Hold("database SOURCE_ASIDE publication failed")
                        }
                    }
                    !entryExists(normalizedTarget) && asideIsSource && preparedIsStaged -> {
                        record = record.copy(state = DatabaseRestoreState.SOURCE_ASIDE)
                        if (!publish(record)) {
                            return DatabaseRestoreResult.Hold("database inferred SOURCE_ASIDE publication failed")
                        }
                    }
                    targetIsStaged && asideIsSource && !entryExists(preparedFile) -> {
                        record = record.copy(state = DatabaseRestoreState.RESTORED)
                        if (!publish(record)) {
                            return DatabaseRestoreResult.Hold("database inferred RESTORED publication failed")
                        }
                    }
                    else -> return DatabaseRestoreResult.Hold("database PREPARED topology is ambiguous")
                }
                DatabaseRestoreState.SOURCE_ASIDE -> when {
                    !entryExists(normalizedTarget) && asideIsSource && preparedIsStaged -> {
                        if (!atomicMove(preparedFile, normalizedTarget, replace = false)) {
                            return DatabaseRestoreResult.Hold("database recovery promote failed")
                        }
                        cut(DatabaseRestoreCut.TARGET_RENAME)
                        if (!syncDirectory()) return DatabaseRestoreResult.Hold("database recovery promote not durable")
                        cut(DatabaseRestoreCut.TARGET_DIRECTORY_SYNC)
                        record = record.copy(state = DatabaseRestoreState.RESTORED)
                        if (!publish(record)) {
                            return DatabaseRestoreResult.Hold("database RESTORED publication failed")
                        }
                    }
                    targetIsStaged && asideIsSource && !entryExists(preparedFile) -> {
                        record = record.copy(state = DatabaseRestoreState.RESTORED)
                        if (!publish(record)) {
                            return DatabaseRestoreResult.Hold("database inferred RESTORED publication failed")
                        }
                    }
                    else -> return DatabaseRestoreResult.Hold("database SOURCE_ASIDE topology is ambiguous")
                }
                DatabaseRestoreState.RESTORED -> {
                    if (!targetIsStaged || !asideIsSource || !exactStandaloneSuperseded(aside) ||
                        entryExists(preparedFile) || !normalizeCanonicalSidecars()
                    ) {
                        return DatabaseRestoreResult.Hold("database RESTORED receipt does not match files")
                    }
                    cut(DatabaseRestoreCut.BEFORE_RESULT)
                    val result = DatabaseRestoreResult.Restored(
                        SchemaReconcile(
                            SchemaReconcileAction.RESTORED,
                            record.sourceSchema,
                            record.stagedSchema,
                            record.stagedSchema,
                        ),
                        record,
                    )
                    cut(DatabaseRestoreCut.AFTER_RESULT_CREATED)
                    return result
                }
            }
        }
    }

    private fun normalizeCanonicalSidecars(): Boolean {
        val journal = File(normalizedTarget.path + "-journal")
        if (entryExists(journal)) return false
        val wal = File(normalizedTarget.path + "-wal")
        if (entryExists(wal) && !ownedStableSidecar(wal, true)) return false
        val shm = File(normalizedTarget.path + "-shm")
        if (entryExists(shm) && !ownedStableSidecar(shm, false)) return false
        val deletionFailed = listOf(wal, shm).any { file ->
            entryExists(file) && !runCatching { Files.delete(file.toPath()); true }.getOrDefault(false)
        }
        if (deletionFailed) return false
        if ((entryExists(wal) || entryExists(shm)) || !syncDirectory()) return false
        return noCanonicalSidecars()
    }

    /** SQLiteOpenHelper has opened the restored owner, so its proven inert WAL/SHM may now exist. */
    private fun exactOpenCanonicalSidecars(): Boolean {
        if (entryExists(File(normalizedTarget.path + "-journal"))) return false
        val wal = File(normalizedTarget.path + "-wal")
        if (entryExists(wal) && !ownedStableSidecar(wal, true)) return false
        val shm = File(normalizedTarget.path + "-shm")
        return !entryExists(shm) || ownedStableSidecar(shm, false)
    }

    private fun noCanonicalSidecars(): Boolean = DB_SIDECARS.none { entryExists(File(normalizedTarget.path + it)) }

    private fun copyPrepared(staged: File, expected: FileIdentity): Boolean {
        if (entryExists(preparedFile) || entryExists(recordFile) || entryExists(recordTemporary)) return false
        val copied = runCatching {
            FileInputStream(staged).use { input ->
                FileOutputStream(preparedFile).use { output ->
                    input.copyTo(output)
                    output.fd.sync()
                }
            }
            true
        }.getOrDefault(false)
        if (!copied || !matches(preparedFile, expected.bytes, expected.sha256)) return false
        cut(DatabaseRestoreCut.PREPARED_FILE_SYNC)
        if (!syncDirectory()) return false
        cut(DatabaseRestoreCut.PREPARED_DIRECTORY_SYNC)
        return true
    }

    private fun publish(record: DatabaseRestoreRecord): Boolean {
        val bytes = encodeDatabaseRestoreRecord(record)
        val wrote = runCatching {
            Files.deleteIfExists(recordTemporary.toPath())
            FileOutputStream(recordTemporary).use { output ->
                output.write(bytes)
                output.fd.sync()
            }
            true
        }.getOrDefault(false)
        if (!wrote) return false
        cut(record.fileSyncCut())
        if (!atomicMove(recordTemporary, recordFile, replace = true)) return false
        cut(record.renameCut())
        if (!syncDirectory()) return false
        cut(record.directorySyncCut())
        return parseDatabaseRestoreRecord(runCatching { recordFile.readBytes() }.getOrNull()) == record
    }

    private fun loadAuthoritativeRecord(): DatabaseRestoreRecord? {
        if (entryExists(recordTemporary)) {
            val temporary = readRecord(recordTemporary) ?: return null
            if (!recordBindsTarget(temporary)) return null
            if (!entryExists(recordFile)) {
                if (!atomicMove(recordTemporary, recordFile, replace = false) || !syncDirectory()) return null
            } else {
                val current = readRecord(recordFile) ?: return null
                if (!sameTransaction(current, temporary)) return null
                if (!runCatching { Files.delete(recordTemporary.toPath()); syncDirectory() }.getOrDefault(false)) {
                    return null
                }
            }
        }
        return if (entryExists(recordFile) && syncDirectory()) readRecord(recordFile) else null
    }

    /**
     * The prepared copy precedes the first record publication, but no canonical mutation has happened.
     * Only that exact topology is safe to abandon. One exact standalone superseded database can predate
     * this attempted transaction: deleting the private prepared copy still changes neither canonical nor
     * retained data. A missing source or ambiguous superseded topology remains a physical-recovery hold.
     */
    private fun discardExactPreMutationPreparedOrphan(): Boolean {
        if (!exactRegularFile(normalizedTarget) || !exactRegularFile(preparedFile) || !noCanonicalSidecars()) {
            return false
        }
        if (!safePreMutationSupersededTopology()) return false
        return runCatching {
            Files.delete(preparedFile.toPath())
            syncDirectory()
        }.getOrDefault(false)
    }

    private fun supersededObjects(): List<File>? {
        val prefix = "${normalizedTarget.name}.v"
        return directory.listFiles()?.filter { file ->
            file.name.startsWith(prefix) && file.name.contains(".superseded")
        }
    }

    private fun replaceableSupersededTopology(expected: File): Boolean {
        val objects = supersededObjects() ?: return false
        return when (objects.size) {
            0 -> true
            1 -> objects.single().name == expected.name && exactStandaloneSuperseded(expected)
            else -> false
        }
    }

    private fun safePreMutationSupersededTopology(): Boolean {
        val objects = supersededObjects() ?: return false
        if (objects.isEmpty()) return true
        if (objects.size != 1) return false
        val retained = objects.single()
        val match = Regex(
            "^${Regex.escape(normalizedTarget.name)}\\.v([1-9][0-9]*)\\.superseded$",
        ).matchEntire(retained.name) ?: return false
        if (match.groupValues[1].toIntOrNull() == null) return false
        return exactStandaloneSuperseded(retained)
    }

    private fun exactStandaloneSuperseded(file: File): Boolean {
        // Android's lstat is authoritative in production. Robolectric's StructStat does not retain
        // st_nlink, so the host regression uses the equivalent no-follow Unix NIO attribute.
        val links = runCatching { Os.lstat(file.absolutePath).st_nlink }.getOrNull()
            ?.takeIf { it > 0L }
            ?: runCatching {
                (Files.getAttribute(file.toPath(), "unix:nlink", LinkOption.NOFOLLOW_LINKS) as Number).toLong()
            }.getOrNull()
        val singleLink = exactRegularFile(file) && links == 1L
        return singleLink && identity(file) != null &&
            RECOVERY_COMPANIONS.none { entryExists(File(file.path + it)) }
    }

    private fun readRecord(file: File): DatabaseRestoreRecord? {
        if (!exactRegularFile(file) || file.length() !in 1..MAX_RECORD_BYTES) return null
        return parseDatabaseRestoreRecord(runCatching { file.readBytes() }.getOrNull())
    }

    private fun recordBindsTarget(record: DatabaseRestoreRecord): Boolean =
        record.targetDirectory == directory.path && record.targetName == normalizedTarget.name &&
            record.supersededName == supersededFile(normalizedTarget, record.sourceSchema).name

    private fun sameTransaction(left: DatabaseRestoreRecord, right: DatabaseRestoreRecord): Boolean =
        left.copy(state = right.state) == right

    private fun DatabaseRestoreRecord.fileSyncCut(): DatabaseRestoreCut = when (state) {
        DatabaseRestoreState.PREPARED -> DatabaseRestoreCut.PREPARED_RECORD_FILE_SYNC
        DatabaseRestoreState.SOURCE_ASIDE -> DatabaseRestoreCut.SOURCE_ASIDE_RECORD_FILE_SYNC
        DatabaseRestoreState.RESTORED -> DatabaseRestoreCut.RESTORED_RECORD_FILE_SYNC
    }

    private fun DatabaseRestoreRecord.renameCut(): DatabaseRestoreCut = when (state) {
        DatabaseRestoreState.PREPARED -> DatabaseRestoreCut.PREPARED_RECORD_RENAME
        DatabaseRestoreState.SOURCE_ASIDE -> DatabaseRestoreCut.SOURCE_ASIDE_RECORD_RENAME
        DatabaseRestoreState.RESTORED -> DatabaseRestoreCut.RESTORED_RECORD_RENAME
    }

    private fun DatabaseRestoreRecord.directorySyncCut(): DatabaseRestoreCut = when (state) {
        DatabaseRestoreState.PREPARED -> DatabaseRestoreCut.PREPARED_RECORD_DIRECTORY_SYNC
        DatabaseRestoreState.SOURCE_ASIDE -> DatabaseRestoreCut.SOURCE_ASIDE_RECORD_DIRECTORY_SYNC
        DatabaseRestoreState.RESTORED -> DatabaseRestoreCut.RESTORED_RECORD_DIRECTORY_SYNC
    }

    private fun atomicMove(source: File, destination: File, replace: Boolean): Boolean = runCatching {
        val options = if (replace) {
            arrayOf(StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } else {
            arrayOf(StandardCopyOption.ATOMIC_MOVE)
        }
        Files.move(source.toPath(), destination.toPath(), *options)
        true
    }.getOrDefault(false)

    private fun syncDirectory(): Boolean = runCatching {
        FileChannel.open(directory.toPath(), StandardOpenOption.READ).use { it.force(true) }
        true
    }.getOrDefault(false)

    private fun matches(file: File, bytes: Long, sha256: String): Boolean =
        identity(file)?.let { it.bytes == bytes && it.sha256 == sha256 } == true

    private fun identity(file: File): FileIdentity? = runCatching {
        if (!exactRegularFile(file)) return null
        val before = file.length().takeIf { it > 0L } ?: return null
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (count > 0) digest.update(buffer, 0, count)
            }
        }
        if (file.length() != before) return null
        FileIdentity(before, digest.digest().joinToString("") { "%02x".format(it) })
    }.getOrNull()

    private fun exactRegularFile(file: File): Boolean =
        Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(file.toPath())

    private fun entryExists(file: File): Boolean = Files.exists(file.toPath(), LinkOption.NOFOLLOW_LINKS)

    private data class FileIdentity(val bytes: Long, val sha256: String)

    private companion object {
        const val MAX_RECORD_BYTES = 2048L
        val DB_SIDECARS = listOf("-wal", "-shm", "-journal")
        val RECOVERY_COMPANIONS = DB_SIDECARS + ".tmp"
    }
}

private fun ownedStableDatabaseSidecar(file: File, requireEmpty: Boolean): Boolean {
    fun snapshot(): DatabaseRestoreSidecarIdentity? = runCatching {
        val stat = Os.lstat(file.absolutePath)
        val regular = (stat.st_mode and OsConstants.S_IFMT) == OsConstants.S_IFREG
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (count > 0) digest.update(buffer, 0, count)
            }
        }
        DatabaseRestoreSidecarIdentity(
            regular = regular,
            device = stat.st_dev,
            inode = stat.st_ino,
            uid = stat.st_uid,
            gid = stat.st_gid,
            links = stat.st_nlink,
            bytes = stat.st_size,
            sha256 = digest.digest().joinToString("") { "%02x".format(it) },
        )
    }.getOrNull()
    return exactOwnedStableDatabaseSidecar(snapshot(), snapshot(), Process.myUid(), requireEmpty)
}

internal fun encodeDatabaseRestoreRecord(record: DatabaseRestoreRecord): ByteArray {
    val guard = record.guard?.let { "${it.session} ${it.generation}" } ?: "NONE 0"
    val payload = buildString {
        append("HAPANELD_DATABASE_RESTORE_V1\n")
        append("STATE ${record.state}\n")
        append("TARGET ${hex(record.targetDirectory)} ${hex(record.targetName)}\n")
        append("SOURCE ${record.sourceSchema} ${record.sourceBytes} ${record.sourceSha256}\n")
        append("STAGED ${record.stagedSchema} ${record.stagedBytes} ${record.stagedSha256}\n")
        append("SUPERSEDED ${hex(record.supersededName)}\n")
        append("GUARD $guard\n")
    }
    return (payload + "CHECKSUM ${sha256(payload.toByteArray(Charsets.US_ASCII))}\n")
        .toByteArray(Charsets.US_ASCII)
}

internal fun parseDatabaseRestoreRecord(bytes: ByteArray?): DatabaseRestoreRecord? {
    if (bytes == null || bytes.isEmpty() || bytes.size > 2048 ||
        bytes.any { it.toInt() !in 0x0a..0x7e || it.toInt() in 0x0b..0x1f }
    ) return null
    val text = bytes.toString(Charsets.US_ASCII)
    if (!text.endsWith('\n')) return null
    val lines = text.split('\n').dropLast(1)
    if (lines.size != 8 || lines[0] != "HAPANELD_DATABASE_RESTORE_V1") return null
    fun fields(index: Int, name: String, count: Int): List<String>? {
        val split = lines[index].split(' ')
        return split.drop(1).takeIf { split.size == count + 1 && split[0] == name && split.none(String::isEmpty) }
    }
    val state = fields(1, "STATE", 1)?.singleOrNull()
        ?.let { value -> DatabaseRestoreState.values().firstOrNull { it.name == value } } ?: return null
    val target = fields(2, "TARGET", 2) ?: return null
    val source = fields(3, "SOURCE", 3) ?: return null
    val staged = fields(4, "STAGED", 3) ?: return null
    val superseded = fields(5, "SUPERSEDED", 1)?.singleOrNull() ?: return null
    val guardFields = fields(6, "GUARD", 2) ?: return null
    val checksum = fields(7, "CHECKSUM", 1)?.singleOrNull() ?: return null
    val payload = lines.take(7).joinToString("\n", postfix = "\n").toByteArray(Charsets.US_ASCII)
    if (!Regex("[0-9a-f]{64}").matches(checksum) || !MessageDigest.isEqual(
            checksum.toByteArray(Charsets.US_ASCII),
            sha256(payload).toByteArray(Charsets.US_ASCII),
        )
    ) return null
    val guard = when (guardFields[0]) {
        "NONE" -> if (guardFields[1] == "0") null else return null
        else -> DatabaseRestoreGuardBinding(
            guardFields[0].takeIf { Regex("[0-9a-f]{64}").matches(it) } ?: return null,
            guardFields[1].canonicalNonNegativeLong() ?: return null,
        )
    }
    return runCatching {
        DatabaseRestoreRecord(
            state = state,
            targetDirectory = unhex(target[0]) ?: return null,
            targetName = unhex(target[1]) ?: return null,
            sourceSchema = source[0].canonicalPositiveInt() ?: return null,
            sourceBytes = source[1].canonicalPositiveLong() ?: return null,
            sourceSha256 = source[2],
            stagedSchema = staged[0].canonicalPositiveInt() ?: return null,
            stagedBytes = staged[1].canonicalPositiveLong() ?: return null,
            stagedSha256 = staged[2],
            supersededName = unhex(superseded) ?: return null,
            guard = guard,
        )
    }.getOrNull()
}

private fun String.canonicalPositiveLong(): Long? =
    takeIf { it.isNotEmpty() && it.all(Char::isDigit) && it.first() != '0' }?.toLongOrNull()?.takeIf { it > 0L }

private fun String.canonicalNonNegativeLong(): Long? = when {
    this == "0" -> 0L
    else -> canonicalPositiveLong()
}

private fun String.canonicalPositiveInt(): Int? = canonicalPositiveLong()?.takeIf { it <= Int.MAX_VALUE }?.toInt()

private fun hex(value: String): String = value.toByteArray(Charsets.UTF_8).joinToString("") { "%02x".format(it) }

private fun unhex(value: String): String? {
    if (value.isEmpty() || value.length % 2 != 0 || value.any { it !in "0123456789abcdef" }) return null
    return runCatching {
        ByteArray(value.length / 2) { index -> value.substring(index * 2, index * 2 + 2).toInt(16).toByte() }
            .toString(Charsets.UTF_8)
    }.getOrNull()?.takeIf { hex(it) == value }
}

private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
    .digest(bytes).joinToString("") { "%02x".format(it) }
