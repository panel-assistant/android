package io.panelassistant.android.backup

import io.panelassistant.android.util.AndroidInput
import io.panelassistant.android.util.BoundedStreams
import io.panelassistant.android.util.InstallPresentation
import java.io.Closeable
import java.io.File
import java.io.InputStream
import java.util.Base64

/** Pure validation and file-backed execution boundary for a destructive HA Companion data restore. */
object CompanionRestore {
    const val DATABASE_FILE = "databases/HomeAssistantDB"
    const val MAX_DATABASE_BYTES = 32L * 1024L * 1024L
    const val MAX_PREFERENCE_BYTES = 2L * 1024L * 1024L
    const val MAX_AGGREGATE_BYTES = MAX_DATABASE_BYTES + 2L * MAX_PREFERENCE_BYTES

    val ALLOWED_FILES = listOf(
        DATABASE_FILE,
        "shared_prefs/session_0.xml",
        "shared_prefs/integration_0.xml",
    )
    data class EncodedFile(val relativePath: String, val base64: String)
    data class FilePayload(val relativePath: String, val file: File, val deleteOnClose: Boolean = true) {
        val size: Long get() = file.length()
    }

    class Plan(val packageName: String, val files: List<FilePayload>) : Closeable {
        override fun close() {
            files.filter { it.deleteOnClose }.forEach { it.file.delete() }
        }
    }

    sealed class PlanResult {
        data class Valid(val plan: Plan) : PlanResult()
        data class Invalid(
            val reason: String,
            val presentation: InstallPresentation? = null,
        ) : PlanResult()
    }

    /** Validate and stream-decode a legacy base64 plan into private temporary files. */
    fun plan(
        packageName: String,
        files: List<EncodedFile>,
        installedPackages: Set<String>,
        stagingDir: File,
    ): PlanResult {
        validateMetadata(packageName, files.map { it.relativePath }, installedPackages)?.let { return it }
        val staged = ArrayList<FilePayload>(files.size)
        return try {
            for (encoded in files) {
                val max = maxBytes(encoded.relativePath)
                // Four base64 characters represent at most three decoded bytes. Reject implausible
                // strings before creating a decoder or a staging file.
                if (encoded.base64.length.toLong() > ((max + 2L) / 3L) * 4L + 4L) {
                    return invalidAndDelete(staged, "Companion file is too large: ${encoded.relativePath}")
                }
                val target = File.createTempFile("companion-restore-", ".payload", stagingDir)
                val ok = runCatching {
                    Base64.getDecoder().wrap(StringAsciiInputStream(encoded.base64)).use { decoded ->
                        target.outputStream().use { output -> BoundedStreams.copy(decoded, output, max) }
                    }
                    target.length() in 1..max
                }.getOrDefault(false)
                if (!ok) {
                    target.delete()
                    return invalidAndDelete(staged, "Invalid base64 for Companion file: ${encoded.relativePath}")
                }
                staged += FilePayload(encoded.relativePath, target)
            }
            validateFiles(packageName, staged, installedPackages)
        } catch (_: Exception) {
            invalidAndDelete(staged, "Could not stage Companion restore files")
        }
    }

    /** Validate already file-backed payloads, taking ownership only when [FilePayload.deleteOnClose] is true. */
    fun planFiles(
        packageName: String,
        files: List<FilePayload>,
        installedPackages: Set<String>,
    ): PlanResult {
        validateMetadata(packageName, files.map { it.relativePath }, installedPackages)?.let {
            files.filter { file -> file.deleteOnClose }.forEach { file -> file.file.delete() }
            return it
        }
        var total = 0L
        for (file in files) {
            val size = file.size
            val max = maxBytes(file.relativePath)
            if (!file.file.isFile || size !in 1..max || total > MAX_AGGREGATE_BYTES - size) {
                files.filter { it.deleteOnClose }.forEach { it.file.delete() }
                return invalidPayload("Companion file is empty or too large: ${file.relativePath}")
            }
            total += size
        }
        val ordered = ALLOWED_FILES.mapNotNull { rel -> files.singleOrNull { it.relativePath == rel } }
        return PlanResult.Valid(Plan(packageName, ordered))
    }

    private fun validateMetadata(
        packageName: String,
        paths: List<String>,
        installedPackages: Set<String>,
    ): PlanResult.Invalid? {
        if (!AndroidInput.isPackage(packageName) || packageName !in installedPackages) {
            return PlanResult.Invalid(
                "Companion package is not a supported installed package",
                InstallPresentation("companion-unsupported-package"),
            )
        }
        if (paths.isEmpty()) return invalidPayload("Companion restore contains no files")
        val seen = HashSet<String>()
        for (path in paths) {
            if (path !in ALLOWED_FILES) return invalidPayload("Unsupported Companion file: $path")
            if (!seen.add(path)) return invalidPayload("Duplicate Companion file: $path")
        }
        return null
    }

    private fun validateFiles(
        packageName: String,
        files: List<FilePayload>,
        installedPackages: Set<String>,
    ): PlanResult = planFiles(packageName, files, installedPackages)

    private fun invalidAndDelete(files: List<FilePayload>, reason: String): PlanResult.Invalid {
        files.filter { it.deleteOnClose }.forEach { it.file.delete() }
        return invalidPayload(reason)
    }

    private fun invalidPayload(reason: String) = PlanResult.Invalid(
        reason,
        InstallPresentation("companion-payload-invalid"),
    )

    fun maxBytes(relativePath: String): Long =
        if (relativePath == DATABASE_FILE) MAX_DATABASE_BYTES else MAX_PREFERENCE_BYTES

    /** Payloads after bounded local SQLite validation and any approved preparation mutation. */
    class Preparation(
        val files: List<FilePayload>,
        val repairedInternalUrls: Int = 0,
        private val ownedFiles: List<File> = emptyList(),
    ) : Closeable {
        override fun close() = ownedFiles.forEach { it.delete() }

        companion object {
            fun unchanged(plan: Plan) = Preparation(plan.files)
            fun withPreparedFiles(files: List<FilePayload>, repaired: Int, ownedFiles: List<File>) =
                Preparation(files, repaired, ownedFiles)
        }
    }

    /** ASCII view over a String, avoiding a second 43 MiB array for a 32 MiB legacy database. */
    private class StringAsciiInputStream(private val value: String) : InputStream() {
        private var index = 0
        override fun read(): Int {
            if (index >= value.length) return -1
            val c = value[index++].code
            return if (c <= 0x7f) c else 0xff // strict Base64 decoder rejects non-ASCII
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (index >= value.length) return -1
            val count = minOf(length, value.length - index)
            for (i in 0 until count) {
                val c = value[index++].code
                buffer[offset + i] = (if (c <= 0x7f) c else 0xff).toByte()
            }
            return count
        }
    }
}
