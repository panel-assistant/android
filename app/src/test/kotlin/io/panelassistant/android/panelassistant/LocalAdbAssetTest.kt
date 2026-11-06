package io.panelassistant.android.panelassistant

import java.nio.file.Files
import java.security.MessageDigest
import org.junit.Assert.*
import org.junit.Test

class LocalAdbAssetTest {
    @Test fun preparedAssetClearsInheritedSetgidAndMeetsTheExistingBootstrapContract() {
        val cache = Files.createTempDirectory("adb-asset-").toFile()
        try {
            val directory = cache.resolve("adb-recovery").apply { mkdir() }
            Files.setAttribute(directory.toPath(), "unix:mode", 0x5c0) // 02700
            val bytes = "verified helper payload".toByteArray()
            val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            val asset = LocalAdbAsset({ cache }, { bytes }) { file, mode ->
                Files.setAttribute(file.toPath(), "unix:mode", mode)
            }
            repeat(2) {
                val prepared = asset.prepare()
                val path = java.io.File(prepared.getString("path"))
                assertEquals(0x1c0, (Files.getAttribute(directory.toPath(), "unix:mode") as Int) and 0xfff)
                assertEquals(0x180, (Files.getAttribute(path.toPath(), "unix:mode") as Int) and 0xfff)
                assertArrayEquals(bytes, path.readBytes())
                assertEquals(hash, prepared.getString("sha256"))
                assertEquals(directory.resolve("helper-$hash"), path)
            }
        } finally {
            cache.deleteRecursively()
        }
    }
}
