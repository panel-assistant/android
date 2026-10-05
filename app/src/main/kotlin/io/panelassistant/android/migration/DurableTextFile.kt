package io.panelassistant.android.migration

import java.io.File
import java.io.FileOutputStream
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption

/**
 * One small text record that is either wholly present or absent after a process death at any instant:
 * written to a sibling temporary, synced, renamed over the target, and the directory synced. Used for
 * the migration's per-step markers and the release token, all of which live in `noBackupFilesDir`.
 */
internal class DurableTextFile(private val file: File, private val maxChars: Int = 4_096) {
    private val parent: File = requireNotNull(file.parentFile) { "durable record needs a parent" }
    private val temporary: File = parent.resolve(".${file.name}.tmp")

    /** The stored text, or null when absent, unreadable or larger than this record may be. */
    fun read(): String? = runCatching {
        if (!file.isFile || file.length() > maxChars * 4L) return@runCatching null
        file.readText(Charsets.UTF_8).takeIf { it.length <= maxChars }
    }.getOrNull()

    fun exists(): Boolean = file.isFile

    @Synchronized
    fun write(text: String): Boolean = runCatching {
        require(text.length <= maxChars) { "durable record is too large" }
        Files.createDirectories(parent.toPath())
        FileOutputStream(temporary, false).use { output ->
            output.write(text.toByteArray(Charsets.UTF_8))
            output.fd.sync()
        }
        Files.move(
            temporary.toPath(),
            file.toPath(),
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING,
        )
        FileChannel.open(parent.toPath(), StandardOpenOption.READ).use { it.force(true) }
        true
    }.getOrElse {
        runCatching { Files.deleteIfExists(temporary.toPath()) }
        false
    }
}
