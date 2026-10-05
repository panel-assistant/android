package io.panelassistant.android.util

import io.panelassistant.android.backup.CompanionRestore
import java.io.Closeable
import java.io.EOFException
import java.io.File
import java.io.InputStream
import java.io.OutputStream

/** Binary-safe framing for the descriptor-confined Companion backup/restore helper verbs. */
internal object CompanionHelperProtocol {
    const val DATABASE_WAL_FILE = "databases/HomeAssistantDB-wal"
    const val DATABASE_SHM_FILE = "databases/HomeAssistantDB-shm"
    const val MAX_WAL_BYTES = 64L * 1024L * 1024L
    const val MAX_SHM_BYTES = 8L * 1024L * 1024L
    const val MAX_BACKUP_STREAM_BYTES =
        CompanionRestore.MAX_AGGREGATE_BYTES + MAX_WAL_BYTES + MAX_SHM_BYTES

    /** Every capture directory is `<prefix><nanoTime>-<attempt>` directly inside the cache directory. */
    const val CAPTURE_DIRECTORY_PREFIX = "companion-capture-"
    const val CAPTURE_DIRECTORY_ATTEMPTS = 8

    /** The only relative paths a capture directory holds; storage remediation proves ownership by them. */
    val capturePaths: List<String> get() = backupOrder

    private val backupOrder = listOf(
        CompanionRestore.DATABASE_FILE,
        DATABASE_WAL_FILE,
        DATABASE_SHM_FILE,
        "shared_prefs/session_0.xml",
        "shared_prefs/integration_0.xml",
    )
    private val backupLimits = mapOf(
        CompanionRestore.DATABASE_FILE to CompanionRestore.MAX_DATABASE_BYTES,
        DATABASE_WAL_FILE to MAX_WAL_BYTES,
        DATABASE_SHM_FILE to MAX_SHM_BYTES,
        "shared_prefs/session_0.xml" to CompanionRestore.MAX_PREFERENCE_BYTES,
        "shared_prefs/integration_0.xml" to CompanionRestore.MAX_PREFERENCE_BYTES,
    )
    private val restoreOrder = listOf(
        CompanionRestore.DATABASE_FILE,
        "shared_prefs/session_0.xml",
        "shared_prefs/integration_0.xml",
    )

    class Capture(
        val files: Map<String, File>,
        val relaunched: Boolean,
        private val directory: File,
    ) : Closeable {
        override fun close() {
            files.values.forEach(File::delete)
            directory.listFiles()?.forEach { parent ->
                parent.listFiles()?.forEach(File::delete)
                parent.delete()
            }
            directory.delete()
        }
    }

    sealed interface BackupResult {
        data class Success(val capture: Capture) : BackupResult
        data object Busy : BackupResult
        data class Failed(val relaunchFailed: Boolean) : BackupResult
        data object NotSubmitted : BackupResult
        data object Indeterminate : BackupResult
    }

    enum class RestoreResult {
        COMMITTED,
        COMMITTED_RELAUNCH_FAILED,
        ROLLED_BACK,
        ROLLED_BACK_RELAUNCH_FAILED,
        ROLLBACK_FAILED,
        ROLLBACK_FAILED_RELAUNCH_FAILED,
        ROLLBACK_FAILED_RELAUNCH_SUPPRESSED,
        BUSY,
        NOT_SUBMITTED,
        FAILED,
        INDETERMINATE,
    }

    fun backup(
        packageName: String,
        cacheDir: File,
        input: InputStream,
        output: OutputStream,
    ): BackupResult {
        writeLine(output, "COMPANIONBACKUP $packageName")
        val first = readLine(input) ?: return BackupResult.Indeterminate
        if (first == "BUSY") return BackupResult.Busy
        if (first == "ERR") return BackupResult.Failed(relaunchFailed = false)
        if (first == "ERR RELAUNCH_ERR") return BackupResult.Failed(relaunchFailed = true)
        val header = first.split(' ')
        val count = header.getOrNull(1)?.toIntOrNull()
        val declaredTotal = header.getOrNull(2)?.toLongOrNull()
        if (header.size != 3 || header[0] != "BACKUP" || count == null || count !in 1..backupOrder.size ||
            declaredTotal == null || declaredTotal !in 1..MAX_BACKUP_STREAM_BYTES
        ) return BackupResult.Indeterminate

        val directory = createCaptureDirectory(cacheDir) ?: return BackupResult.Indeterminate
        val captured = LinkedHashMap<String, File>(count)
        var total = 0L
        var lastOrder = -1
        try {
            repeat(count) {
                val frame = readLine(input)?.split(' ') ?: throw EOFException("missing Companion frame")
                val path = frame.getOrNull(1).orEmpty()
                val bytes = frame.getOrNull(2)?.toLongOrNull()
                val order = backupOrder.indexOf(path)
                val limit = backupLimits[path]
                if (frame.size != 3 || frame[0] != "FILE" || order <= lastOrder || limit == null ||
                    bytes == null || bytes !in 1..limit || total > declaredTotal - bytes
                ) throw IllegalArgumentException("invalid Companion backup frame")
                lastOrder = order
                total += bytes
                val target = File(directory, path)
                val parent = target.parentFile ?: throw IllegalStateException("missing Companion capture parent")
                if (!parent.isDirectory && !parent.mkdirs()) {
                    throw IllegalStateException("could not create Companion capture directory")
                }
                target.outputStream().use { BoundedStreams.copyExact(input, it, bytes) }
                captured[path] = target
            }
            if (total != declaredTotal || CompanionRestore.DATABASE_FILE !in captured) {
                throw IllegalArgumentException("incomplete Companion backup")
            }
            val terminal = readLine(input)
            val relaunched = when (terminal) {
                "DONE" -> true
                "DONE RELAUNCH_ERR" -> false
                else -> throw EOFException("missing Companion backup terminal")
            }
            return BackupResult.Success(Capture(captured, relaunched, directory))
        } catch (_: Exception) {
            captured.values.forEach(File::delete)
            directory.deleteRecursively()
            return BackupResult.Indeterminate
        }
    }

    fun restore(
        packageName: String,
        files: Map<String, File>,
        input: InputStream,
        output: OutputStream,
        shutdownOutput: () -> Unit,
    ): RestoreResult {
        if (files.isEmpty() || files.keys.any { it !in restoreOrder }) return RestoreResult.FAILED
        var total = 0L
        val sizes = restoreOrder.map { path ->
            files[path]?.let { file ->
                val size = file.length()
                val limit = CompanionRestore.maxBytes(path)
                if (!file.isFile || size !in 1..limit || total > CompanionRestore.MAX_AGGREGATE_BYTES - size) {
                    return RestoreResult.FAILED
                }
                total += size
                size
            }
        }
        writeLine(
            output,
            "COMPANIONRESTORE $packageName " + sizes.joinToString(" ") { it?.toString() ?: "-" },
        )
        return when (readLine(input)) {
            "BUSY" -> RestoreResult.BUSY
            "STREAMERR" -> RestoreResult.FAILED
            "READY" -> {
                try {
                    restoreOrder.forEach { path ->
                        files[path]?.inputStream()?.use { source -> BoundedStreams.copyExact(source, output, files.getValue(path).length()) }
                    }
                    output.flush()
                    shutdownOutput()
                    when (readLine(input)) {
                        "OK" -> RestoreResult.COMMITTED
                        "COMMITTED RELAUNCH_ERR" -> RestoreResult.COMMITTED_RELAUNCH_FAILED
                        "ROLLED_BACK" -> RestoreResult.ROLLED_BACK
                        "ROLLED_BACK RELAUNCH_ERR" -> RestoreResult.ROLLED_BACK_RELAUNCH_FAILED
                        "ROLLBACK_FAILED" -> RestoreResult.ROLLBACK_FAILED
                        "ROLLBACK_FAILED RELAUNCH_ERR" -> RestoreResult.ROLLBACK_FAILED_RELAUNCH_FAILED
                        "ROLLBACK_FAILED RELAUNCH_SUPPRESSED" -> RestoreResult.ROLLBACK_FAILED_RELAUNCH_SUPPRESSED
                        "ERR" -> RestoreResult.FAILED
                        else -> RestoreResult.INDETERMINATE
                    }
                } catch (_: Exception) {
                    RestoreResult.INDETERMINATE
                }
            }
            null -> RestoreResult.INDETERMINATE
            else -> RestoreResult.FAILED
        }
    }

    private fun createCaptureDirectory(cacheDir: File): File? {
        repeat(CAPTURE_DIRECTORY_ATTEMPTS) {
            val directory = File(cacheDir, "$CAPTURE_DIRECTORY_PREFIX${System.nanoTime()}-$it")
            if (directory.mkdir()) return directory
        }
        return null
    }

    private fun writeLine(output: OutputStream, line: String) {
        output.write((line + "\n").toByteArray(Charsets.US_ASCII))
        output.flush()
    }

    /** Read one line byte-by-byte so binary payload bytes can never be consumed by reader buffering. */
    private fun readLine(input: InputStream, maxBytes: Int = 256): String? {
        val bytes = ByteArray(maxBytes)
        var count = 0
        while (true) {
            val next = input.read()
            if (next < 0) return if (count == 0) null else throw EOFException("unterminated helper line")
            if (next == '\n'.code) {
                if (count > 0 && bytes[count - 1] == '\r'.code.toByte()) count--
                return String(bytes, 0, count, Charsets.US_ASCII)
            }
            if (count == bytes.size || next !in 0x20..0x7e) throw IllegalArgumentException("invalid helper line")
            bytes[count++] = next.toByte()
        }
    }

}
