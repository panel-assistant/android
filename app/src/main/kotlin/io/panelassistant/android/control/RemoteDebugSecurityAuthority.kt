package io.panelassistant.android.control

import android.os.Process
import android.system.Os
import android.system.OsConstants
import java.io.File
import java.io.FileOutputStream
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.security.SecureRandom

internal enum class RemoteDebugSecurityState { TRANSITION, HARDENED }

internal data class RemoteDebugSecurityAuthority(
    val state: RemoteDebugSecurityState,
    val epoch: Long,
) {
    init { require(epoch > 0L) }
}

internal sealed interface RemoteDebugSecurityAuthorityLoad {
    data object Absent : RemoteDebugSecurityAuthorityLoad
    data object Corrupt : RemoteDebugSecurityAuthorityLoad
    data class Valid(val authority: RemoteDebugSecurityAuthority) : RemoteDebugSecurityAuthorityLoad
}

/**
 * DB-free, crash-durable authority for Guard DB control-plane admission. A transition record is
 * published before any security-mode, relay, or network-ADB mutation. HARDENED is published only after
 * the SQLite-backed mode/ownership commits and the live relay/property/listener proof have completed.
 */
internal class RemoteDebugSecurityAuthorityStore(
    noBackupFilesDir: File,
    private val nextEpoch: () -> Long = ::randomRemoteDebugSecurityEpoch,
    private val validRegularFile: (File) -> Boolean = ::validRemoteDebugSecurityAuthorityFile,
    private val chmod0600: (File) -> Boolean = ::chmodRemoteDebugSecurityAuthorityFile,
    private val syncDirectory: (File) -> Boolean = ::syncRemoteDebugSecurityAuthorityDirectory,
) {
    private val record = noBackupFilesDir.resolve(RECORD_FILE)
    private val temporary = noBackupFilesDir.resolve(TEMPORARY_FILE)
    private val publication = noBackupFilesDir.resolve(PUBLICATION_FILE)
    private val parent = noBackupFilesDir

    fun load(): RemoteDebugSecurityAuthorityLoad {
        // A durable publication fence means the target rename was not durably settled. Any entry,
        // including a foreign type or unreadable object, is fail-closed until a new TRANSITION
        // publication replaces the authority and retires the fence.
        if (!Files.notExists(publication.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            return RemoteDebugSecurityAuthorityLoad.Corrupt
        }
        if (Files.notExists(record.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            return RemoteDebugSecurityAuthorityLoad.Absent
        }
        if (!validRegularFile(record) || record.length() !in 1..MAX_RECORD_BYTES) {
            return RemoteDebugSecurityAuthorityLoad.Corrupt
        }
        val bytes = runCatching { record.readBytes() }.getOrNull()
            ?: return RemoteDebugSecurityAuthorityLoad.Corrupt
        return parseRemoteDebugSecurityAuthority(bytes)
            ?.let(RemoteDebugSecurityAuthorityLoad::Valid)
            ?: RemoteDebugSecurityAuthorityLoad.Corrupt
    }

    @Synchronized
    fun publishTransition(): RemoteDebugSecurityAuthority? {
        val prior = (load() as? RemoteDebugSecurityAuthorityLoad.Valid)?.authority
        var epoch = nextEpoch()
        repeat(4) {
            if (epoch > 0L && epoch != prior?.epoch) return@repeat
            epoch = nextEpoch()
        }
        if (epoch <= 0L || epoch == prior?.epoch) return null
        val authority = RemoteDebugSecurityAuthority(RemoteDebugSecurityState.TRANSITION, epoch)
        return authority.takeIf { writeAndReadBack(it) }
    }

    @Synchronized
    fun publishHardened(expectedEpoch: Long): Boolean {
        val current = (load() as? RemoteDebugSecurityAuthorityLoad.Valid)?.authority ?: return false
        if (current.epoch != expectedEpoch || current.state != RemoteDebugSecurityState.TRANSITION) return false
        return writeAndReadBack(RemoteDebugSecurityAuthority(RemoteDebugSecurityState.HARDENED, expectedEpoch))
    }

    private fun writeAndReadBack(authority: RemoteDebugSecurityAuthority): Boolean = runCatching {
        if (!parent.isDirectory || Files.isSymbolicLink(parent.toPath())) return@runCatching false
        if (authority.state == RemoteDebugSecurityState.HARDENED && !armPublicationFence(authority)) {
            return@runCatching false
        }
        Files.deleteIfExists(temporary.toPath())
        FileOutputStream(temporary).use { output ->
            output.write(encodeRemoteDebugSecurityAuthority(authority))
            if (!chmod0600(temporary)) return@runCatching false
            output.fd.sync()
        }
        if (!validRegularFile(temporary)) return@runCatching false
        Files.move(
            temporary.toPath(), record.toPath(),
            StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING,
        )
        // The target entry must be durable before the fence can be retired. On failure, the
        // already-durable fence remains and both this process and a fresh process reject HARDENED.
        if (!syncDirectory(parent)) return@runCatching false
        if (!retirePublicationFence()) return@runCatching false
        load() == RemoteDebugSecurityAuthorityLoad.Valid(authority)
    }.getOrDefault(false).also { runCatching { Files.deleteIfExists(temporary.toPath()) } }

    private fun armPublicationFence(authority: RemoteDebugSecurityAuthority): Boolean = runCatching {
        if (!Files.notExists(publication.toPath(), LinkOption.NOFOLLOW_LINKS)) return@runCatching false
        FileOutputStream(publication).use { output ->
            output.write("HARDENED ${authority.epoch}\n".toByteArray(Charsets.US_ASCII))
            if (!chmod0600(publication)) return@runCatching false
            output.fd.sync()
        }
        if (!validRegularFile(publication)) return@runCatching false
        syncDirectory(parent)
    }.getOrDefault(false)

    private fun retirePublicationFence(): Boolean = runCatching {
        if (Files.notExists(publication.toPath(), LinkOption.NOFOLLOW_LINKS)) return@runCatching true
        if (!Files.deleteIfExists(publication.toPath())) return@runCatching false
        // The target record was synced first. Failure to persist this unlink can only resurrect the
        // fence after power loss, which is a safe HOLD rather than an unproved HARDENED authority.
        runCatching { syncDirectory(parent) }
        true
    }.getOrDefault(false)

    private companion object {
        const val RECORD_FILE = "remote-debug-security-authority.v1"
        const val TEMPORARY_FILE = ".remote-debug-security-authority.v1.tmp"
        const val PUBLICATION_FILE = ".remote-debug-security-authority.v1.publish"
        const val MAX_RECORD_BYTES = 512L
        const val MODE_0600 = 0x180
        const val MODE_0777 = 0x1ff
    }
}

private fun chmodRemoteDebugSecurityAuthorityFile(file: File): Boolean = runCatching {
    Os.chmod(file.absolutePath, 0x180)
    true
}.getOrDefault(false)

private fun syncRemoteDebugSecurityAuthorityDirectory(directory: File): Boolean = runCatching {
    FileChannel.open(directory.toPath(), StandardOpenOption.READ).use { it.force(true) }
    true
}.getOrDefault(false)

private fun validRemoteDebugSecurityAuthorityFile(file: File): Boolean = runCatching {
    val stat = Os.lstat(file.absolutePath)
    val type = stat.st_mode and OsConstants.S_IFMT
    type == OsConstants.S_IFREG && stat.st_nlink == 1L &&
        stat.st_uid == Process.myUid() && stat.st_gid == Process.myUid() &&
        (stat.st_mode and 0x1ff) == 0x180
}.getOrDefault(false)

internal fun encodeRemoteDebugSecurityAuthority(authority: RemoteDebugSecurityAuthority): ByteArray {
    val payload = buildString {
        append("HAPANELD_REMOTE_DEBUG_SECURITY_AUTHORITY_V1\n")
        append("STATE ${authority.state}\n")
        append("EPOCH ${authority.epoch.toString(16).padStart(16, '0')}\n")
    }
    val digest = MessageDigest.getInstance("SHA-256")
        .digest(payload.toByteArray(Charsets.US_ASCII))
        .joinToString("") { "%02x".format(it) }
    return (payload + "CHECKSUM $digest\n").toByteArray(Charsets.US_ASCII)
}

internal fun parseRemoteDebugSecurityAuthority(bytes: ByteArray?): RemoteDebugSecurityAuthority? {
    if (bytes == null || bytes.isEmpty() || bytes.size > 512 ||
        bytes.any { it.toInt() !in 0x0a..0x7e || it.toInt() in 0x0b..0x1f }
    ) return null
    val text = bytes.toString(Charsets.US_ASCII)
    if (!text.endsWith('\n')) return null
    val lines = text.split('\n').dropLast(1)
    if (lines.size != 4 || lines[0] != "HAPANELD_REMOTE_DEBUG_SECURITY_AUTHORITY_V1") return null
    val state = lines[1].removePrefix("STATE ").takeIf { "STATE $it" == lines[1] }
        ?.let { token -> RemoteDebugSecurityState.values().firstOrNull { it.name == token } }
        ?: return null
    val epochToken = lines[2].removePrefix("EPOCH ")
    if (!Regex("[0-9a-f]{16}").matches(epochToken)) return null
    if (epochToken.first() !in '0'..'7') return null
    val epoch = epochToken.toLongOrNull(16)?.takeIf { it > 0L } ?: return null
    val checksum = lines[3].removePrefix("CHECKSUM ")
    if (!Regex("[0-9a-f]{64}").matches(checksum) || "CHECKSUM $checksum" != lines[3]) return null
    val payload = lines.take(3).joinToString("\n", postfix = "\n").toByteArray(Charsets.US_ASCII)
    val expected = MessageDigest.getInstance("SHA-256").digest(payload)
        .joinToString("") { "%02x".format(it) }
    if (!MessageDigest.isEqual(checksum.toByteArray(), expected.toByteArray())) return null
    return RemoteDebugSecurityAuthority(state, epoch)
}

private val REMOTE_DEBUG_SECURITY_RANDOM = SecureRandom()

private fun randomRemoteDebugSecurityEpoch(): Long = REMOTE_DEBUG_SECURITY_RANDOM.nextLong().and(Long.MAX_VALUE)
    .takeIf { it > 0L } ?: 1L
