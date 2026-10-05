package io.panelassistant.android.http

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/** The one content-addressed screenshot cache shared by HTTP capture, remote input and page rendering. */
internal class ScreenshotCache(private val filesDir: File) {
    private val screenshotCacheDir: File
        get() = File(filesDir, "panel-screenshots")

    private val screenshotCachePointer: File
        get() = File(screenshotCacheDir, "current")

    private fun storedScreenshotCacheId(): String? = runCatching {
        screenshotCachePointer.readText().trim().takeIf {
            it.matches(Regex("[0-9a-f]{64}")) && File(screenshotCacheDir, "$it.png").isFile
        }
    }.getOrNull()

    private fun screenshotCacheId(): String? {
        storedScreenshotCacheId()?.let { return it }
        val legacy = File(filesDir, "last-panel-screenshot.png")
        val bytes = runCatching { legacy.takeIf { it.isFile && it.length() > 0 }?.readBytes() }.getOrNull()
            ?: return null
        store(bytes)
        legacy.delete()
        return storedScreenshotCacheId()
    }

    fun placeholderUrl(): String? =
        screenshotCacheId()?.let { "api/v1/screenshot.png?cached=$it" }

    fun read(id: String): ByteArray? = runCatching {
        if (!id.matches(Regex("[0-9a-f]{64}"))) return@runCatching null
        File(screenshotCacheDir, "$id.png").takeIf { it.isFile && it.length() > 0 }?.readBytes()
    }.getOrNull()

    @Synchronized
    fun store(png: ByteArray): String? =
        runCatching {
            val dir = screenshotCacheDir.apply { mkdirs() }
            val id = MessageDigest.getInstance("SHA-256")
                .digest(png)
                .joinToString("") { "%02x".format(it) }
            val target = File(dir, "$id.png")
            if (!target.isFile) {
                atomicReplace(File(dir, "$id.png.new"), target, png)
            }
            val previous = storedScreenshotCacheId()
            atomicReplace(File(dir, "current.new"), screenshotCachePointer, "$id\n".toByteArray())
            dir.listFiles()
                ?.filter { it.extension == "png" && it.nameWithoutExtension !in setOf(id, previous) }
                ?.forEach { it.delete() }
            id
        }.getOrNull()

    private fun atomicReplace(staged: File, target: File, bytes: ByteArray) {
        staged.writeBytes(bytes)
        try {
            Files.move(
                staged.toPath(),
                target.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: Throwable) {
            target.writeBytes(bytes)
            staged.delete()
        }
    }

}
