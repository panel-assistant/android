package io.panelassistant.android.util

import android.content.Context
import android.os.Process
import android.system.Os
import android.system.OsConstants
import io.panelassistant.android.persistence.canonicalGuardDbSettingsAuthority
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

internal data class GuardDbSettingsAuthority(
    val version: Int,
    val file: File,
    val bytes: Long,
    val sha256: String,
) {
    init {
        require(version == VERSION && bytes in 1..MAX_BYTES && GuardDbMaintenanceProtocol.validSha256(sha256))
        require(file.isFile && file.length() == bytes)
    }

    companion object {
        const val VERSION = 2
        const val MAX_BYTES = 262_144L
    }
}

internal class GuardDbSettingsAuthorityStore(private val directory: File) {
    private val authority = File(directory, "guard-db-settings-authority-v2")
    private val pending = File(directory, ".guard-db-settings-authority-v2.pending")

    @Synchronized
    fun materializeExact(): GuardDbSettingsAuthority? {
        val expected = canonicalGuardDbSettingsAuthority()
        if (expected.size.toLong() !in 1..GuardDbSettingsAuthority.MAX_BYTES) return null
        load()?.takeIf { loaded ->
            loaded.bytes == expected.size.toLong() && loaded.sha256 == settingsAuthoritySha256(expected)
        }?.let { return it }
        val written = runCatching {
            pending.delete()
            FileOutputStream(pending).use { output ->
                output.write(expected)
                Os.chmod(pending.absolutePath, 0x180)
                output.fd.sync()
            }
            Files.move(
                pending.toPath(), authority.toPath(),
                StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING,
            )
            syncDirectory(directory)
        }.getOrDefault(false)
        pending.delete()
        if (!written) return null
        return load()?.takeIf {
            it.bytes == expected.size.toLong() && it.sha256 == settingsAuthoritySha256(expected)
        }
    }

    @Synchronized
    fun load(): GuardDbSettingsAuthority? {
        if (!validFile(authority) || authority.length() !in 1..GuardDbSettingsAuthority.MAX_BYTES) return null
        return GuardDbSettingsAuthority(
            version = GuardDbSettingsAuthority.VERSION,
            file = authority,
            bytes = authority.length(),
            sha256 = AppInstaller.sha256(authority),
        )
    }

    private fun validFile(file: File): Boolean = runCatching {
        val stat = Os.lstat(file.absolutePath)
        (stat.st_mode and OsConstants.S_IFMT) == OsConstants.S_IFREG && stat.st_nlink == 1L &&
            stat.st_uid == Process.myUid() && stat.st_gid == Process.myUid() &&
            (stat.st_mode and 0x1ff) == 0x180
    }.getOrDefault(false)

    private fun syncDirectory(value: File): Boolean = runCatching {
        val fd = Os.open(value.absolutePath, OsConstants.O_RDONLY, 0)
        try {
            Os.fsync(fd)
        } finally {
            Os.close(fd)
        }
        true
    }.getOrDefault(false)
}

internal fun guardDbSettingsAuthorityStore(context: Context): GuardDbSettingsAuthorityStore =
    GuardDbSettingsAuthorityStore(context.filesDir)

internal fun settingsAuthoritySha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
    .digest(bytes).joinToString("") { byte -> "%02x".format(byte) }
