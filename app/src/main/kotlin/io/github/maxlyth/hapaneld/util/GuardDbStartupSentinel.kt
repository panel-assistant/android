package io.github.maxlyth.hapaneld.util

import android.content.Context
import android.system.Os
import android.system.OsConstants
import android.os.Process
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicReference

internal enum class GuardDbSentinelState { INTENT, BASELINE_READY, ARMED }

/** App-private authority which survives the package/process replacements in one Guard canary. */
internal data class GuardDbStartupSentinel(
    val state: GuardDbSentinelState,
    val session: String,
    val bootNonce: String,
    val aSha256: String,
    val aVersionCode: Long,
    val aSchema: Int,
    val bSha256: String,
    val bVersionCode: Long,
    val bSchema: Int,
    val settingsAuthorityVersion: Int,
    val settingsAuthorityBytes: Long,
    val settingsAuthoritySha256: String,
    val securityAuthorityEpoch: Long,
    val httpPort: Int,
    val hardened: Boolean,
) {
    init {
        require(GuardDbMaintenanceProtocol.validSession(session))
        require(GuardDbMaintenanceProtocol.validSha256(bootNonce))
        require(GuardDbMaintenanceProtocol.validSha256(aSha256))
        require(GuardDbMaintenanceProtocol.validSha256(bSha256))
        require(aSha256 != bSha256 && aVersionCode > 0L && bVersionCode > aVersionCode)
        require(aSchema > 0 && bSchema == aSchema + 1)
        require(settingsAuthorityVersion == GuardDbSettingsAuthority.VERSION)
        require(settingsAuthorityBytes in 1..GuardDbSettingsAuthority.MAX_BYTES)
        require(GuardDbMaintenanceProtocol.validSha256(settingsAuthoritySha256))
        require(securityAuthorityEpoch > 0L)
        require(httpPort in 1..65535)
        require(hardened)
    }
}

internal sealed interface GuardDbSentinelLoad {
    data object Absent : GuardDbSentinelLoad
    data class Valid(val sentinel: GuardDbStartupSentinel) : GuardDbSentinelLoad
    data object Corrupt : GuardDbSentinelLoad
}

internal fun encodeGuardDbSentinel(sentinel: GuardDbStartupSentinel): ByteArray {
    val payload = buildString {
        append("HAPANELD_GUARD_DB_SENTINEL_V1\n")
        append("STATE ${sentinel.state}\n")
        append("SESSION ${sentinel.session}\n")
        append("BOOT ${sentinel.bootNonce}\n")
        append("A ${sentinel.aSha256} ${sentinel.aVersionCode} ${sentinel.aSchema}\n")
        append("B ${sentinel.bSha256} ${sentinel.bVersionCode} ${sentinel.bSchema}\n")
        append("SETTINGS ${sentinel.settingsAuthorityVersion} ${sentinel.settingsAuthorityBytes} " +
            "${sentinel.settingsAuthoritySha256}\n")
        append("SECURITY_EPOCH ${sentinel.securityAuthorityEpoch}\n")
        append("HTTP_PORT ${sentinel.httpPort}\n")
        append("HARDENED 1\n")
    }
    return (payload + "CHECKSUM ${guardDbSentinelDigest(payload.toByteArray(Charsets.US_ASCII))}\n")
        .toByteArray(Charsets.US_ASCII)
}

internal fun parseGuardDbSentinel(bytes: ByteArray?): GuardDbStartupSentinel? {
    if (bytes == null || bytes.isEmpty() || bytes.size > 2048 ||
        bytes.any { it.toInt() !in 0x0a..0x7e || it.toInt() in 0x0b..0x1f }
    ) return null
    val text = bytes.toString(Charsets.US_ASCII)
    if (!text.endsWith('\n')) return null
    val lines = text.split('\n').dropLast(1)
    if (lines.size != 11 || lines[0] != "HAPANELD_GUARD_DB_SENTINEL_V1") return null
    fun exact(index: Int, key: String, values: Int): List<String>? {
        val fields = lines[index].split(' ')
        return fields.drop(1).takeIf {
            fields.size == values + 1 && fields.none(String::isEmpty) && fields[0] == key
        }
    }
    val state = exact(1, "STATE", 1)?.singleOrNull()
        ?.let { value -> GuardDbSentinelState.values().firstOrNull { it.name == value } } ?: return null
    val session = exact(2, "SESSION", 1)?.singleOrNull() ?: return null
    val boot = exact(3, "BOOT", 1)?.singleOrNull() ?: return null
    val a = exact(4, "A", 3) ?: return null
    val b = exact(5, "B", 3) ?: return null
    val settings = exact(6, "SETTINGS", 3) ?: return null
    val securityEpoch = exact(7, "SECURITY_EPOCH", 1)?.singleOrNull()?.canonicalPositiveLong() ?: return null
    val port = exact(8, "HTTP_PORT", 1)?.singleOrNull()?.canonicalPositiveLong()
        ?.takeIf { it <= Int.MAX_VALUE }?.toInt() ?: return null
    if (exact(9, "HARDENED", 1)?.singleOrNull() != "1") return null
    val checksum = exact(10, "CHECKSUM", 1)?.singleOrNull() ?: return null
    val payload = lines.take(10).joinToString(separator = "\n", postfix = "\n").toByteArray(Charsets.US_ASCII)
    if (!MessageDigest.isEqual(
            checksum.toByteArray(Charsets.US_ASCII),
            guardDbSentinelDigest(payload).toByteArray(Charsets.US_ASCII),
        )
    ) return null
    return runCatching {
        GuardDbStartupSentinel(
            state = state,
            session = session,
            bootNonce = boot,
            aSha256 = a[0],
            aVersionCode = a[1].canonicalPositiveLong() ?: return null,
            aSchema = a[2].canonicalPositiveLong()?.takeIf { it <= Int.MAX_VALUE }?.toInt() ?: return null,
            bSha256 = b[0],
            bVersionCode = b[1].canonicalPositiveLong() ?: return null,
            bSchema = b[2].canonicalPositiveLong()?.takeIf { it <= Int.MAX_VALUE }?.toInt() ?: return null,
            settingsAuthorityVersion = settings[0].canonicalPositiveLong()
                ?.takeIf { it <= Int.MAX_VALUE }?.toInt() ?: return null,
            settingsAuthorityBytes = settings[1].canonicalPositiveLong() ?: return null,
            settingsAuthoritySha256 = settings[2],
            securityAuthorityEpoch = securityEpoch,
            httpPort = port,
            hardened = true,
        )
    }.getOrNull()
}

private fun String.canonicalPositiveLong(): Long? {
    if (isEmpty() || any { it !in '0'..'9' } || first() == '0') return null
    return toLongOrNull()?.takeIf { it > 0L }
}

private fun guardDbSentinelDigest(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
    .digest(bytes).joinToString("") { "%02x".format(it) }

internal fun guardDbBootNonce(
    readBootId: () -> String? = {
        runCatching { File("/proc/sys/kernel/random/boot_id").readText().trim() }.getOrNull()
    },
): String? {
    val bootId = readBootId()?.takeIf {
        Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}").matches(it)
    } ?: return null
    return guardDbSentinelDigest("hapaneld-guard-boot-v1\u0000$bootId".toByteArray(Charsets.US_ASCII))
}

internal class GuardDbSentinelStore(
    private val directory: File,
    private val syncDirectory: (File) -> Boolean = ::syncGuardDbSentinelDirectory,
    private val validateMarker: (File) -> Boolean = ::validGuardDbSentinelFile,
) {
    private val marker = File(directory, "guard-db-maintenance.v1")
    private val temporary = File(directory, ".guard-db-maintenance.v1.pending")

    /**
     * Whether the record or its pending write exists at all, read without reconciling either. The
     * pending write is looked at first: a publish moves it onto the record, never the other way.
     */
    fun present(): Boolean = listOf(temporary, marker).any { !Files.notExists(it.toPath(), LinkOption.NOFOLLOW_LINKS) }

    @Synchronized
    fun load(): GuardDbSentinelLoad {
        if (!reconcilePending()) return GuardDbSentinelLoad.Corrupt
        return loadRecord()
    }

    private fun loadRecord(): GuardDbSentinelLoad {
        if (Files.notExists(marker.toPath(), LinkOption.NOFOLLOW_LINKS)) return GuardDbSentinelLoad.Absent
        if (!validateMarker(marker) || marker.length() !in 1..2048) {
            return GuardDbSentinelLoad.Corrupt
        }
        val bytes = runCatching { marker.readBytes() }.getOrNull() ?: return GuardDbSentinelLoad.Corrupt
        return parseGuardDbSentinel(bytes)?.let(GuardDbSentinelLoad::Valid) ?: GuardDbSentinelLoad.Corrupt
    }

    private fun reconcilePending(): Boolean {
        if (Files.notExists(temporary.toPath(), LinkOption.NOFOLLOW_LINKS)) return true
        if (!validateMarker(temporary) || temporary.length() !in 1..2048) return false
        val pending = runCatching { temporary.readBytes() }.getOrNull()
            ?.let(::parseGuardDbSentinel) ?: return false
        val allowed = when (val current = loadRecord()) {
            GuardDbSentinelLoad.Absent -> pending.state == GuardDbSentinelState.INTENT
            GuardDbSentinelLoad.Corrupt -> false
            is GuardDbSentinelLoad.Valid -> current.sentinel.copy(state = pending.state) == pending &&
                allowedGuardDbSentinelPromotion(current.sentinel.state, pending.state)
        }
        if (!allowed) return false
        return runCatching {
            Files.move(
                temporary.toPath(),
                marker.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
            syncDirectory(directory)
        }.getOrDefault(false)
    }

    @Synchronized
    fun write(sentinel: GuardDbStartupSentinel): Boolean = when (val current = load()) {
        GuardDbSentinelLoad.Absent ->
            sentinel.state == GuardDbSentinelState.INTENT && publish(sentinel)
        is GuardDbSentinelLoad.Valid -> current.sentinel == sentinel && syncDirectory(directory)
        GuardDbSentinelLoad.Corrupt -> false
    }

    private fun publish(sentinel: GuardDbStartupSentinel): Boolean {
        // noBackupFilesDir already exists and is durably owned by Package Manager. Keeping the fixed
        // marker directly in it avoids a crash seam where a newly-created child directory itself was
        // never fsynced into its parent.
        if (!directory.isDirectory || Files.isSymbolicLink(directory.toPath())) return false
        if (!Files.notExists(temporary.toPath(), LinkOption.NOFOLLOW_LINKS)) return false
        return runCatching {
            FileOutputStream(temporary).use { output ->
                output.write(encodeGuardDbSentinel(sentinel))
                Os.chmod(temporary.absolutePath, 0x180) // 0600, before file fsync
                output.fd.sync()
            }
            Files.move(
                temporary.toPath(),
                marker.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
            syncDirectory(directory)
        }.getOrDefault(false).also { temporary.delete() }
    }

    @Synchronized
    fun promoteArmed(expectedSession: String): Boolean {
        return promote(expectedSession, GuardDbSentinelState.ARMED)
    }

    @Synchronized
    fun promoteBaselineReady(expectedSession: String): Boolean {
        return promote(expectedSession, GuardDbSentinelState.BASELINE_READY)
    }

    private fun promote(expectedSession: String, state: GuardDbSentinelState): Boolean {
        val current = (load() as? GuardDbSentinelLoad.Valid)?.sentinel ?: return false
        if (current.session != expectedSession) return false
        if (current.state == state) return syncDirectory(directory)
        return allowedGuardDbSentinelPromotion(current.state, state) && publish(current.copy(state = state))
    }

    @Synchronized
    fun clear(expectedSession: String): Boolean {
        val current = load()
        if (current is GuardDbSentinelLoad.Valid && current.sentinel.session != expectedSession) return false
        if (current is GuardDbSentinelLoad.Corrupt) return false
        if (current is GuardDbSentinelLoad.Valid && !marker.delete()) return false
        return syncDirectory(directory)
    }
}

private fun allowedGuardDbSentinelPromotion(
    current: GuardDbSentinelState,
    pending: GuardDbSentinelState,
): Boolean = current == GuardDbSentinelState.INTENT && pending == GuardDbSentinelState.BASELINE_READY ||
    current == GuardDbSentinelState.BASELINE_READY && pending == GuardDbSentinelState.ARMED

internal fun guardDbSentinelStore(context: Context): GuardDbSentinelStore =
    GuardDbSentinelStore(context.noBackupFilesDir)

private fun validGuardDbSentinelFile(file: File): Boolean = runCatching {
    val stat = Os.lstat(file.absolutePath)
    val type = stat.st_mode and OsConstants.S_IFMT
    type == OsConstants.S_IFREG && stat.st_nlink == 1L &&
        stat.st_uid == Process.myUid() && stat.st_gid == Process.myUid() &&
        (stat.st_mode and 0x1ff) == 0x180 // exact 0600
}.getOrDefault(false)

private fun syncGuardDbSentinelDirectory(directory: File): Boolean = runCatching {
    val fd = Os.open(directory.absolutePath, OsConstants.O_RDONLY, 0)
    try {
        Os.fsync(fd)
    } finally {
        Os.close(fd)
    }
    true
}.getOrDefault(false)

/** Primed in Application.attachBaseContext, before the app's only ContentProvider is created. */
internal object GuardDbProcessAdmission {
    private val sentinel = AtomicReference<GuardDbSentinelLoad>(GuardDbSentinelLoad.Absent)
    private val terminalRetirement = AtomicReference<GuardDbTerminalRetirementLoad>(
        GuardDbTerminalRetirementLoad.Absent,
    )

    fun prime(context: Context): GuardDbSentinelLoad {
        terminalRetirement.set(guardDbTerminalRetirementStore(context).load())
        return guardDbSentinelStore(context).load().also(sentinel::set)
    }
    fun current(): GuardDbSentinelLoad = sentinel.get()
    fun maintenanceRequired(): Boolean = current() !is GuardDbSentinelLoad.Absent
    fun ordinaryMutationsAllowed(): Boolean = current() is GuardDbSentinelLoad.Absent &&
        !terminalRetirementBlocksOrdinaryMutations(terminalRetirement.get())
    fun update(value: GuardDbSentinelLoad) = sentinel.set(value)
    fun updateTerminalRetirement(value: GuardDbTerminalRetirementLoad) = terminalRetirement.set(value)
}

internal fun terminalRetirementBlocksOrdinaryMutations(load: GuardDbTerminalRetirementLoad): Boolean = when (load) {
    GuardDbTerminalRetirementLoad.Absent -> false
    GuardDbTerminalRetirementLoad.Corrupt -> true
    is GuardDbTerminalRetirementLoad.Valid ->
        load.retirement.state == GuardDbTerminalRetirementState.INTENT
}
