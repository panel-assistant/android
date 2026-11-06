package io.panelassistant.android.util

import android.net.LocalSocket
import android.util.Log
import io.panelassistant.android.BuildConfig
import io.panelassistant.android.platform.Daemon
import io.panelassistant.android.platform.DaemonLongResult
import io.panelassistant.android.platform.DaemonStreamResult
import io.panelassistant.android.platform.HelperZigbeeHealth
import io.panelassistant.android.platform.HelperZigbeeProcess
import io.panelassistant.android.platform.HelperZigbeeState
import java.io.BufferedInputStream
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import org.json.JSONObject

const val SUPPORTED_HELPER_PROTOCOL_MAJOR = 1

data class HelperIdentity(
    val version: String,
    val protocolMajor: Int,
    val protocolMinor: Int,
)

enum class HelperIdentityIssue {
    MALFORMED_IDENTITY,
    UNSUPPORTED_PROTOCOL,
}

sealed interface HelperIdentityStatus {
    data class Compatible(val identity: HelperIdentity) : HelperIdentityStatus
    data object ReachableUnverified : HelperIdentityStatus
    data object Missing : HelperIdentityStatus
    data class Incompatible(
        val identity: HelperIdentity?,
        val issue: HelperIdentityIssue,
    ) : HelperIdentityStatus
}

private const val MAX_IDENTITY_REPLY_CHARS = 128
private val HELPER_SEMVER = Regex("""(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)""")
private val HELPER_PROTOCOL = Regex("""(0|[1-9]\d*)\.(0|[1-9]\d*)""")
private val IDENTITY_KEY = Regex("""[a-z][a-z0-9_]*""")

internal fun parseHelperIdentity(reply: String): HelperIdentity? {
    if (reply.isEmpty() || reply.length > MAX_IDENTITY_REPLY_CHARS) return null
    if (reply.any { it.code !in 0x20..0x7e }) return null
    val tokens = reply.split(' ')
    if (tokens.firstOrNull() != "HELPER" || tokens.any(String::isEmpty)) return null

    val fields = linkedMapOf<String, String>()
    for (token in tokens.drop(1)) {
        val separator = token.indexOf('=')
        if (separator <= 0 || separator == token.lastIndex) return null
        val key = token.substring(0, separator)
        val value = token.substring(separator + 1)
        if (!IDENTITY_KEY.matches(key) || fields.put(key, value) != null) return null
    }

    val version = fields["version"] ?: return null
    if (!HELPER_SEMVER.matches(version)) return null
    val protocol = HELPER_PROTOCOL.matchEntire(fields["proto"] ?: return null) ?: return null
    val major = protocol.groupValues[1].toIntOrNull() ?: return null
    val minor = protocol.groupValues[2].toIntOrNull() ?: return null
    return HelperIdentity(version, major, minor)
}

private fun classifyHelperIdentity(reply: String): HelperIdentityStatus {
    val identity = parseHelperIdentity(reply)
        ?: return HelperIdentityStatus.Incompatible(null, HelperIdentityIssue.MALFORMED_IDENTITY)
    return if (identity.protocolMajor == SUPPORTED_HELPER_PROTOCOL_MAJOR) {
        HelperIdentityStatus.Compatible(identity)
    } else {
        HelperIdentityStatus.Incompatible(identity, HelperIdentityIssue.UNSUPPORTED_PROTOCOL)
    }
}

private const val GOVREAD_PROTOCOL_MINOR = 5
private const val MAX_GOVERNOR_PAYLOAD_BYTES = 4095
private const val MAX_GOVREAD_REPLY_BYTES = MAX_GOVERNOR_PAYLOAD_BYTES + 9L // prefix plus LF
private val GOVERNOR_TOKEN = Regex("[A-Za-z0-9_.-]{1,63}")

private fun parseGovernorReply(reply: ByteArray?, single: Boolean): List<String>? {
    if (reply == null || reply.size > MAX_GOVREAD_REPLY_BYTES || reply.isEmpty()) return null
    if (reply.last() != '\n'.code.toByte() || reply.dropLast(1).any { (it.toInt() and 0xff) !in 0x20..0x7e }) return null
    val line = String(reply, 0, reply.size - 1, StandardCharsets.US_ASCII)
    if (!line.startsWith("GOVREAD ")) return null
    val payload = line.removePrefix("GOVREAD ")
    if (payload.length > MAX_GOVERNOR_PAYLOAD_BYTES) return null
    val tokens = payload.split(' ')
    return tokens.takeIf { (!single || it.size == 1) && it.all(GOVERNOR_TOKEN::matches) }
}

private val ZIGBEE_STATE_FIELDS = listOf(
    "managed", "guard", "run", "binary", "broker", "package_marker", "running", "guard_running", "package",
)
private val ZIGBEE_HEALTH_FIELDS = ZIGBEE_STATE_FIELDS + listOf(
    "gateway_pid", "gateway_start", "gateway_jiffies", "guard_pid", "guard_start", "guard_jiffies",
    "total_jiffies", "recursive", "netinfo",
)
private val ZIGBEE_FILES = listOf("run_guard_process.sh", "guard_process.sh", "run.sh", "zgateway", "mosquitto", "package_version")
private val UNSIGNED_DECIMAL = Regex("0|[1-9][0-9]*")

private fun zigbeeFrame(bytes: ByteArray?, command: String, fields: List<String>, maxBytes: Int): Map<String, String>? {
    bytes ?: return null
    if (bytes.size > maxBytes || bytes.isEmpty() || bytes.last() != 10.toByte() ||
        bytes.any { (it.toInt() and 255) != 10 && (it.toInt() and 255) !in 32..126 }
    ) return null
    val lines = String(bytes, StandardCharsets.US_ASCII).removeSuffix("\n").split('\n')
    if (lines.size != fields.size + 2 || lines.first() != "$command 1" || lines.last() != "END") return null
    return fields.mapIndexed { index, key ->
        val line = lines[index + 1]
        if (!line.startsWith("$key=")) return null
        key to line.removePrefix("$key=")
    }.toMap()
}

private fun zigbeeFlag(value: String?): Boolean? = when (value) { "0" -> false; "1" -> true; else -> null }
private fun zigbeeNumber(value: String): Long? = value.takeIf(UNSIGNED_DECIMAL::matches)?.toLongOrNull()

private fun zigbeeHex(value: String, maxBytes: Int, allowControls: Boolean = false): String? {
    if (value.length > maxBytes * 2 || value.length % 2 != 0 || value.any { it !in '0'..'9' && it !in 'a'..'f' }) return null
    val bytes = ByteArray(value.length / 2) { value.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    val decoded = runCatching {
        StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
    }.getOrNull() ?: return null
    return decoded.takeIf { allowControls || it.none(Char::isISOControl) }
}

private fun parseZigbeeState(fields: Map<String, String>): HelperZigbeeState? {
    val flags = ZIGBEE_STATE_FIELDS.take(8).map { zigbeeFlag(fields[it]) ?: return null }
    val version = zigbeeHex(fields.getValue("package"), 120) ?: return null
    return HelperZigbeeState(ZIGBEE_FILES.filterIndexed { index, _ -> flags[index] }.toSet(), flags[6], flags[7], version.trim().ifEmpty { null })
}

private fun parseZigbeeHealth(bytes: ByteArray?): HelperZigbeeHealth? {
    val fields = zigbeeFrame(bytes, "ZIGBEEHEALTH", ZIGBEE_HEALTH_FIELDS, 9216) ?: return null
    val state = parseZigbeeState(fields) ?: return null
    fun process(prefix: String): HelperZigbeeProcess? {
        val pid = zigbeeNumber(fields.getValue("${prefix}_pid"))?.takeIf { it in 1..Int.MAX_VALUE.toLong() } ?: return null
        val start = zigbeeNumber(fields.getValue("${prefix}_start")) ?: return null
        val jiffies = zigbeeNumber(fields.getValue("${prefix}_jiffies")) ?: return null
        return HelperZigbeeProcess(pid.toInt(), start, jiffies)
    }
    fun absent(prefix: String) = listOf("pid", "start", "jiffies").all { fields["${prefix}_$it"] == "-" }
    val gateway = process("gateway")
    val guard = process("guard")
    if (gateway == null && !absent("gateway") || guard == null && !absent("guard") ||
        state.running != (gateway != null) || state.guardRunning != (guard != null) ||
        gateway != null && guard != null && gateway.pid == guard.pid
    ) return null
    val total = zigbeeNumber(fields.getValue("total_jiffies")) ?: return null
    val recursive = zigbeeFlag(fields["recursive"]) ?: return null
    val netinfo = fields.getValue("netinfo").let {
        if (it == "-") null else zigbeeHex(it, 4096, allowControls = true) ?: return null
    }
    return HelperZigbeeHealth(state, gateway, guard, total, recursive, netinfo)
}

private const val HELPER_BOOTSTRAP_TIMEOUT_MS = 1_000L

internal sealed interface HelperBootstrapReply {
    data class Line(val value: String) : HelperBootstrapReply
    data object Missing : HelperBootstrapReply
    data object Malformed : HelperBootstrapReply
}

internal enum class HelperReplacementBuild { NEW, OLD }

internal sealed interface HelperReplacementProbe {
    data class Settled(val build: HelperReplacementBuild) : HelperReplacementProbe
    data object Hold : HelperReplacementProbe
}

internal fun parseHelperBuildId(reply: String?): String? = reply
    ?.removePrefix("BUILDID ")
    ?.takeIf { reply == "BUILDID $it" && Regex("[0-9a-f]{64}").matches(it) }

internal fun classifyHelperReplacementProbe(
    ping: String?,
    buildReply: String?,
    capsReply: String?,
    statusReply: String?,
    stagedBuildId: String,
    incumbentBuildId: String,
): HelperReplacementProbe {
    if (ping != "OK" || !GuardDbMaintenanceProtocol.validSha256(stagedBuildId) ||
        !GuardDbMaintenanceProtocol.validSha256(incumbentBuildId) || stagedBuildId == incumbentBuildId
    ) return HelperReplacementProbe.Hold
    val build = parseHelperBuildId(buildReply) ?: return HelperReplacementProbe.Hold
    val capabilities = GuardDbMaintenanceProtocol.parseCapabilities(capsReply)
        ?.takeIf { it.supervised && it.autonomous && it.terminalRetire }
        ?: return HelperReplacementProbe.Hold
    val status = GuardDbMaintenanceProtocol.parseStatus(statusReply)
        ?.takeIf { it.phase == GuardDbMaintenanceProtocol.Phase.EMPTY } ?: return HelperReplacementProbe.Hold
    // Retain both parsed objects in this exact tuple; neither generic reachability nor BUILDID alone is
    // settlement authority while the helper-owned R1 fence may still exist.
    if (!capabilities.supervised || !capabilities.autonomous || !capabilities.terminalRetire ||
        status.generation != 0L
    ) {
        return HelperReplacementProbe.Hold
    }
    return when (build) {
        stagedBuildId -> HelperReplacementProbe.Settled(HelperReplacementBuild.NEW)
        incumbentBuildId -> HelperReplacementProbe.Settled(HelperReplacementBuild.OLD)
        else -> HelperReplacementProbe.Hold
    }
}

/**
 * Read one exact ASCII bootstrap line into fixed storage. Socket timeouts are reset to the remaining
 * absolute budget before every read, so a sender cannot extend the deadline by trickling bytes.
 */
internal fun readHelperBootstrapLine(
    input: InputStream,
    setReadTimeoutMs: (Int) -> Unit,
    deadline: MonotonicDeadline,
): HelperBootstrapReply {
    val bytes = ByteArray(MAX_IDENTITY_REPLY_CHARS + 1)
    var used = 0
    while (true) {
        // The remaining absolute budget is reset before every read, so a sender can't extend the deadline
        // by trickling bytes. remainingMs() == 0 is expiry (the former HelperBootstrapDeadline null).
        val timeoutMs = deadline.remainingMs()
        if (timeoutMs <= 0L) return if (used == 0) HelperBootstrapReply.Missing else HelperBootstrapReply.Malformed
        val next = try {
            setReadTimeoutMs(timeoutMs.toInt())
            input.read()
        } catch (_: Exception) {
            return if (used == 0) HelperBootstrapReply.Missing else HelperBootstrapReply.Malformed
        }
        if (next < 0) {
            return if (used == 0) HelperBootstrapReply.Missing else HelperBootstrapReply.Malformed
        }
        if (next == '\n'.code) {
            return HelperBootstrapReply.Line(String(bytes, 0, used, StandardCharsets.US_ASCII))
        }
        if (next !in 0x20..0x7e) return HelperBootstrapReply.Malformed
        bytes[used++] = next.toByte()
        if (used > MAX_IDENTITY_REPLY_CHARS) return HelperBootstrapReply.Malformed
    }
}

internal interface HelperCommandSession : AutoCloseable {
    fun bootstrap(command: String, deadline: MonotonicDeadline): HelperBootstrapReply
    fun send(cmd: String): String?
    fun sendLong(cmd: String, timeoutMs: Long): DaemonLongResult
    fun sendFile(cmd: String, source: File, timeoutMs: Long): DaemonStreamResult
    fun sendBytes(cmd: String, maxBytes: Long): ByteArray?
    fun readLogcatLine(): String?
    fun backupCompanion(
        packageName: String,
        cacheDir: File,
        timeoutMs: Long,
        hold: Boolean,
    ): CompanionHelperProtocol.BackupResult
    fun restoreCompanion(
        packageName: String,
        files: Map<String, File>,
        timeoutMs: Long,
        expected: CompanionHelperProtocol.LiveDigest?,
    ): CompanionHelperProtocol.RestoreResult
}

/** Raw long/epoch physical lines, without trailing newline; blank lines separate Android entries. */
internal interface HelperLogcatStream : AutoCloseable {
    /** Blocking read. Closing from another thread cancels the socket and the helper child. */
    fun readLine(): String?
}

internal sealed interface HelperLogcatOpenResult {
    data class Open(val stream: HelperLogcatStream) : HelperLogcatOpenResult
    data object Unsupported : HelperLogcatOpenResult
    data object Failed : HelperLogcatOpenResult
}

internal interface HelperCommandTransport {
    fun open(): HelperCommandSession?
}

private data class HelperIdentityProbe(
    val status: HelperIdentityStatus,
    val rawFallbackAllowed: Boolean = false,
)

/** Applies a bootstrap decision on the same connection that will receive the privileged operation. */
internal class IdentityAdmittingHelperClient(
    private val transport: HelperCommandTransport,
    private val nowNs: () -> Long = System::nanoTime,
    private val bootstrapTimeoutMs: Long = HELPER_BOOTSTRAP_TIMEOUT_MS,
) : Daemon {
    init {
        require(bootstrapTimeoutMs in 1..Int.MAX_VALUE.toLong())
    }

    fun identityStatus(): HelperIdentityStatus {
        val session = transport.open() ?: return HelperIdentityStatus.Missing
        return session.use { probeIdentity(it, newDeadline()).status }
    }

    fun replacementProbe(stagedBuildId: String, incumbentBuildId: String): HelperReplacementProbe =
        execute(HelperReplacementProbe.Hold) { session ->
            classifyHelperReplacementProbe(
                ping = session.send("PING"),
                buildReply = session.send("BUILDID"),
                capsReply = session.send("GUARDCAPS"),
                statusReply = session.send("GUARDSTATUS"),
                stagedBuildId = stagedBuildId,
                incumbentBuildId = incumbentBuildId,
            )
        }

    /** A failed connection is distinct from an authenticated helper that rejects this capability. */
    fun openLogcat(): HelperLogcatOpenResult {
        val session = openAdmittedSession(false) ?: return HelperLogcatOpenResult.Failed
        val caps = session.send("LOGCATCAPS")
        if (caps != "LOGCATCAPS 1") {
            session.close()
            return if (caps == "ERR") HelperLogcatOpenResult.Unsupported else HelperLogcatOpenResult.Failed
        }
        if (session.send("LOGCAT") != "OK") {
            session.close()
            return HelperLogcatOpenResult.Failed
        }
        return HelperLogcatOpenResult.Open(object : HelperLogcatStream {
            override fun readLine(): String? = session.readLogcatLine()
            override fun close() = session.close()
        })
    }

    /**
     * Raw long-format backlog lines from `LOGCATDUMP` (blank lines separate entries). Only a
     * compatible helper at protocol 1.4 or later is asked; an older helper, a refusal or a failed
     * exchange yields no backlog. There is no root fallback.
     */
    fun logcatDump(lines: Int): List<String> {
        if (lines !in 1..LOGCAT_DUMP_MAX_LINES) return emptyList()
        val session = transport.open() ?: return emptyList()
        return session.use {
            val status = probeIdentity(it, newDeadline()).status
            if (status !is HelperIdentityStatus.Compatible ||
                status.identity.protocolMinor < LOGCAT_DUMP_PROTOCOL_MINOR
            ) return emptyList()
            val bytes = it.sendBytes("LOGCATDUMP $lines", LOGCAT_DUMP_MAX_REPLY_BYTES) ?: return emptyList()
            val reader = HelperLogcatLineReader(ByteArrayInputStream(bytes))
            if (runCatching { reader.readLine() }.getOrNull() != "OK") return emptyList()
            buildList {
                // A cap or deadline can cut the final line; the reader refuses that partial tail.
                while (true) add(runCatching { reader.readLine() }.getOrNull() ?: break)
            }
        }
    }

    override fun cpuGovernor(): String? = readGovernors("CURRENT", single = true)?.single()

    override fun cpuAvailableGovernors(): List<String>? = readGovernors("AVAILABLE", single = false)

    private fun readGovernors(selector: String, single: Boolean): List<String>? {
        val session = transport.open() ?: return null
        return session.use {
            // GOVREAD has no legacy/raw path: this identity belongs to the socket carrying the read.
            val version = it.bootstrap("VERSION", newDeadline()) as? HelperBootstrapReply.Line ?: return null
            val identity = parseHelperIdentity(version.value) ?: return null
            if (identity.protocolMajor != SUPPORTED_HELPER_PROTOCOL_MAJOR || identity.protocolMinor < GOVREAD_PROTOCOL_MINOR) return null
            parseGovernorReply(it.sendBytes("GOVREAD $selector", MAX_GOVREAD_REPLY_BYTES), single)
        }
    }

    override fun zigbeeState(): HelperZigbeeState? =
        zigbeeFrame(readZigbee("ZIGBEESTATE", 6, 512), "ZIGBEESTATE", ZIGBEE_STATE_FIELDS, 512)?.let(::parseZigbeeState)

    override fun zigbeeHealth(): HelperZigbeeHealth? = parseZigbeeHealth(readZigbee("ZIGBEEHEALTH", 7, 9216))

    override fun zigbeeRole(): String? {
        val bytes = readZigbee("ZIGBEEROLE", 8, 8204) ?: return null
        if (bytes.isEmpty() || bytes.size > 8204 || bytes.last() != 10.toByte() ||
            bytes.dropLast(1).any { (it.toInt() and 255) !in 32..126 }
        ) return null
        val line = String(bytes, 0, bytes.size - 1, StandardCharsets.US_ASCII)
        if (!line.startsWith("ZIGBEEROLE ")) return null
        val raw = zigbeeHex(line.removePrefix("ZIGBEEROLE "), 4096, allowControls = true) ?: return null
        return runCatching {
            (JSONObject(raw.trim()).opt("role") as? String)
                ?.takeIf { it.isNotEmpty() && it.length <= 64 && it.none(Char::isISOControl) }
        }.getOrNull()
    }

    private fun readZigbee(command: String, minimumMinor: Int, maxBytes: Long): ByteArray? {
        val session = transport.open() ?: return null
        return session.use {
            val version = it.bootstrap("VERSION", newDeadline()) as? HelperBootstrapReply.Line ?: return null
            val identity = parseHelperIdentity(version.value) ?: return null
            if (identity.protocolMajor != SUPPORTED_HELPER_PROTOCOL_MAJOR || identity.protocolMinor < minimumMinor) return null
            it.sendBytes(command, maxBytes)
        }
    }

    override fun available(): Boolean = execute(null) { it.send("PING") } == "OK"

    override fun send(cmd: String): String? = execute(null) { it.send(cmd) }

    override fun sendLong(cmd: String, timeoutMs: Long): DaemonLongResult =
        execute(DaemonLongResult.NotSubmitted) { it.sendLong(cmd, timeoutMs) }

    override fun sendLong(cmd: String, timeoutMs: Long, beforeSubmit: () -> Boolean): DaemonLongResult =
        execute(DaemonLongResult.NotSubmitted) {
            if (beforeSubmit()) it.sendLong(cmd, timeoutMs) else DaemonLongResult.NotSubmitted
        }

    override fun sendFile(cmd: String, source: File, timeoutMs: Long): DaemonStreamResult =
        execute(DaemonStreamResult.NotSubmitted) { it.sendFile(cmd, source, timeoutMs) }

    override fun sendFile(cmd: String, source: File, timeoutMs: Long, beforeSubmit: () -> Boolean): DaemonStreamResult =
        execute(DaemonStreamResult.NotSubmitted) {
            if (beforeSubmit()) it.sendFile(cmd, source, timeoutMs) else DaemonStreamResult.NotSubmitted
        }

    override fun sendBytes(cmd: String): ByteArray? =
        execute(null) { it.sendBytes(cmd, Long.MAX_VALUE - 1L) }

    override fun sendBytesBounded(cmd: String, maxBytes: Long): ByteArray? =
        execute(null) { it.sendBytes(cmd, maxBytes) }

    fun backupCompanion(
        packageName: String,
        cacheDir: File,
        timeoutMs: Long,
        hold: Boolean = false,
    ): CompanionHelperProtocol.BackupResult = execute(
        CompanionHelperProtocol.BackupResult.NotSubmitted,
        requireCompanionCapability = true,
    ) { it.backupCompanion(packageName, cacheDir, timeoutMs, hold) }

    fun restoreCompanion(
        packageName: String,
        files: Map<String, File>,
        timeoutMs: Long,
        expected: CompanionHelperProtocol.LiveDigest? = null,
    ): CompanionHelperProtocol.RestoreResult = execute(
        CompanionHelperProtocol.RestoreResult.NOT_SUBMITTED,
        requireCompanionCapability = true,
    ) { it.restoreCompanion(packageName, files, timeoutMs, expected) }

    private fun newDeadline(): MonotonicDeadline =
        MonotonicDeadline(bootstrapTimeoutMs, nowNs)

    private fun probeIdentity(
        session: HelperCommandSession,
        deadline: MonotonicDeadline,
    ): HelperIdentityProbe = when (val version = session.bootstrap("VERSION", deadline)) {
        HelperBootstrapReply.Missing -> HelperIdentityProbe(HelperIdentityStatus.Missing, rawFallbackAllowed = true)
        HelperBootstrapReply.Malformed -> HelperIdentityProbe(
            HelperIdentityStatus.Incompatible(null, HelperIdentityIssue.MALFORMED_IDENTITY),
        )
        is HelperBootstrapReply.Line -> {
            val status = if (version.value == "ERR") {
                if (session.bootstrap("PING", deadline) == HelperBootstrapReply.Line("OK")) {
                    HelperIdentityStatus.ReachableUnverified
                } else {
                    HelperIdentityStatus.Missing
                }
            } else {
                classifyHelperIdentity(version.value)
            }
            HelperIdentityProbe(status)
        }
    }

    private fun openAdmittedSession(requireCompanionCapability: Boolean): HelperCommandSession? {
        var session = transport.open() ?: return null
        var deadline = newDeadline()
        val identity = probeIdentity(session, deadline)
        when (identity.status) {
            is HelperIdentityStatus.Incompatible -> {
                session.close()
                return null
            }
            HelperIdentityStatus.Missing -> {
                if (!identity.rawFallbackAllowed) {
                    session.close()
                    return null
                }
                // A VERSION-unaware helper may close or ignore the bootstrap connection. Preserve the
                // historical command attempt on one fresh connection. An explicit VERSION reply,
                // including ERR followed by any non-OK PING outcome, is never eligible for this fallback.
                session.close()
                session = transport.open() ?: return null
                deadline = newDeadline()
            }
            is HelperIdentityStatus.Compatible,
            HelperIdentityStatus.ReachableUnverified -> Unit
        }
        if (requireCompanionCapability &&
            session.bootstrap("COMPANIONCAPS", deadline) != HelperBootstrapReply.Line(COMPANION_CAPABILITY_VERSION)
        ) {
            session.close()
            return null
        }
        return session
    }

    private inline fun <T> execute(
        blocked: T,
        requireCompanionCapability: Boolean = false,
        operation: (HelperCommandSession) -> T,
    ): T {
        val session = openAdmittedSession(requireCompanionCapability) ?: return blocked
        return session.use(operation)
    }
}

/**
 * Client for the root helper daemon (`helper/hapaneld-helper`) over an **abstract-namespace UNIX
 * socket** (`@hapaneld-helper`). The app (`untrusted_app`) cannot write the root-only sysfs nodes the
 * daemon owns (LED + backlight power), so it asks the daemon. The daemon authenticates us by uid
 * (`SO_PEERCRED`) and rejects any other app — which is why this is a UNIX socket, not the old
 * unauthenticated `127.0.0.1:8889` TCP. Used by the sysfs LED + screen controllers (and others).
 * Calls are blocking socket I/O with verb-appropriate bounds — invoke off the main thread.
 */
object HelperClient : Daemon by admittedHelperClient {

    /** Opens a closeable raw system log stream on the authenticated helper socket. */
    internal fun openLogcat(): HelperLogcatOpenResult = admittedHelperClient.openLogcat()

    /** Bounded system-log backlog for a viewer opening; empty from a helper older than protocol 1.4. */
    internal fun logcatDump(lines: Int): List<String> = admittedHelperClient.logcatDump(lines)

    /**
     * Read-only bootstrap probe for provisioning and diagnostics. Ordinary operations repeat this
     * probe on their own connection; a deployed pre-VERSION helper remains reachable but unverified.
     */
    fun identityStatus(): HelperIdentityStatus = admittedHelperClient.identityStatus()

    /** Exact Companion-data protocol admission; a generic PING is not sufficient for older helpers. */
    internal fun supportsCompanionData(): Boolean = companionCapabilitySupported(send("COMPANIONCAPS"))

    /** Exact source identity of the helper carried by this APK. */
    internal fun matchesBundledHelper(): Boolean = helperBuildIdentitySupported(send("BUILDID"), BuildConfig.HELPER_BUILD_ID)

    internal fun installedBuildId(): String? = parseHelperBuildId(send("BUILDID"))

    internal fun replacementProbe(stagedBuildId: String, incumbentBuildId: String): HelperReplacementProbe =
        admittedHelperClient.replacementProbe(stagedBuildId, incumbentBuildId)

    /** Non-blocking transaction state used to retain app-side launch suppression after a client timeout. */
    internal fun companionOperationStatus(): CompanionOperationStatus =
        parseCompanionOperationStatus(send("COMPANIONSTATUS"))

    /** Descriptor-confined raw Companion capture. The helper owns stop/open/relaunch as one operation;
     *  [hold] leaves a successfully captured Companion stopped for the caller's restore. */
    internal fun backupCompanion(
        packageName: String,
        cacheDir: File,
        hold: Boolean = false,
        timeoutMs: Long = COMPANION_OPERATION_TIMEOUT_MS,
    ): CompanionHelperProtocol.BackupResult =
        admittedHelperClient.backupCompanion(packageName, cacheDir, timeoutMs, hold)

    /** Atomic descriptor-confined Companion restore. No shell or pathname fallback is permitted. With
     *  [expected], the helper commits only while the live database and WAL are still those bytes. */
    internal fun restoreCompanion(
        packageName: String,
        files: Map<String, File>,
        expected: CompanionHelperProtocol.LiveDigest? = null,
        timeoutMs: Long = COMPANION_OPERATION_TIMEOUT_MS,
    ): CompanionHelperProtocol.RestoreResult =
        admittedHelperClient.restoreCompanion(packageName, files, timeoutMs, expected)

    private const val COMPANION_OPERATION_TIMEOUT_MS = 120_000L
}

private const val SOCK = "hapaneld-helper" // matches helper/src/main.c

private val admittedHelperClient = IdentityAdmittingHelperClient(
    LocalSocketHelperTransport(
        connect = { openRootAbstractSocket(SOCK) },
        tag = "ha-paneld/helper",
    ),
)

/** Logs "daemon not reachable" on a reachability change only: the first failure, then again after a success. */
internal class HelperReachabilityLog(
    private val tag: String,
    private val sink: (String, String) -> Unit = { t, m -> Log.d(t, m) },
) {
    private var unreachable = false

    @Synchronized fun failed(message: String?) {
        if (unreachable) return
        unreachable = true
        sink(tag, "daemon not reachable ($message)")
    }

    @Synchronized fun succeeded() { unreachable = false }
}

internal class LocalSocketHelperTransport(
    private val connect: () -> LocalSocket,
    private val tag: String,
    private val reachability: HelperReachabilityLog = HelperReachabilityLog(tag),
) : HelperCommandTransport {
    override fun open(): HelperCommandSession? = try {
        LocalSocketHelperSession(connect(), tag, reachability).also { reachability.succeeded() }
    } catch (e: Exception) {
        reachability.failed(e.message)
        null
    }
}

private class LocalSocketHelperSession(
    private val socket: LocalSocket,
    private val tag: String,
    private val reachability: HelperReachabilityLog,
) : HelperCommandSession {
    private val input = socket.inputStream
    private val output = socket.outputStream
    private val logcatReader = HelperLogcatLineReader(BufferedInputStream(input, 4096))

    override fun bootstrap(command: String, deadline: MonotonicDeadline): HelperBootstrapReply = try {
        require(command == "VERSION" || command == "PING" || command == "COMPANIONCAPS")
        output.apply { write((command + "\n").toByteArray(StandardCharsets.US_ASCII)); flush() }
        readHelperBootstrapLine(input, { socket.soTimeout = it }, deadline)
    } catch (_: Exception) {
        HelperBootstrapReply.Missing
    }

    override fun send(cmd: String): String? = try {
        socket.soTimeout = SHORT_TIMEOUT_MS
        HelperSocketProtocol.sendLine(cmd, input, output).also { reachability.succeeded() }
    } catch (e: Exception) {
        reachability.failed(e.message)
        null
    }

    override fun sendLong(cmd: String, timeoutMs: Long): DaemonLongResult {
        var submissionBegan = false
        return try {
            socket.soTimeout = timeoutMs.asSocketTimeout()
            submissionBegan = true
            HelperSocketProtocol.sendLine(cmd, input, output)
                ?.let(DaemonLongResult::Reply)
                ?: DaemonLongResult.Indeterminate
        } catch (e: Exception) {
            Log.d(tag, "daemon long call ${if (submissionBegan) "indeterminate" else "not submitted"} (${e.message})")
            if (submissionBegan) DaemonLongResult.Indeterminate else DaemonLongResult.NotSubmitted
        }
    }

    override fun sendFile(cmd: String, source: File, timeoutMs: Long): DaemonStreamResult {
        val deadline = StreamDeadline(timeoutMs) { runCatching { socket.close() } }
        return try {
            var submissionBegan = false
            try {
                socket.soTimeout = timeoutMs.asSocketTimeout()
                submissionBegan = true
                HelperSocketProtocol.sendFile(
                    command = cmd,
                    openSource = source::inputStream,
                    expectedBytes = source.length(),
                    input = input,
                    output = output,
                    shutdownOutput = socket::shutdownOutput,
                )
            } catch (e: Exception) {
                Log.d(tag, "daemon stream ${if (submissionBegan) "indeterminate" else "not submitted"} (${e.message})")
                if (submissionBegan) DaemonStreamResult.Indeterminate else DaemonStreamResult.NotSubmitted
            }
        } finally {
            deadline.close()
        }
    }

    override fun sendBytes(cmd: String, maxBytes: Long): ByteArray? = try {
        socket.soTimeout = BYTE_TIMEOUT_MS
        HelperSocketProtocol.sendBytes(
            cmd,
            input,
            output,
            socket::shutdownOutput,
            maxBytes,
        )
    } catch (e: Exception) {
        Log.d(tag, "daemon bytes failed (${e.message})")
        null
    }

    override fun readLogcatLine(): String? {
        socket.soTimeout = LOGCAT_READ_TIMEOUT_MS
        return logcatReader.readLine()
    }

    override fun backupCompanion(
        packageName: String,
        cacheDir: File,
        timeoutMs: Long,
        hold: Boolean,
    ): CompanionHelperProtocol.BackupResult {
        val deadline = StreamDeadline(timeoutMs) { runCatching { socket.close() } }
        return try {
            socket.soTimeout = timeoutMs.asSocketTimeout()
            CompanionHelperProtocol.backup(packageName, cacheDir, input, output, hold)
        } catch (e: Exception) {
            Log.w(tag, "Companion backup exchange failed", e)
            CompanionHelperProtocol.BackupResult.Indeterminate
        } finally {
            deadline.close()
        }
    }

    override fun restoreCompanion(
        packageName: String,
        files: Map<String, File>,
        timeoutMs: Long,
        expected: CompanionHelperProtocol.LiveDigest?,
    ): CompanionHelperProtocol.RestoreResult {
        val deadline = StreamDeadline(timeoutMs) { runCatching { socket.close() } }
        return try {
            socket.soTimeout = timeoutMs.asSocketTimeout()
            CompanionHelperProtocol.restore(
                packageName,
                files,
                input,
                output,
                socket::shutdownOutput,
                expected,
            )
        } catch (e: Exception) {
            Log.w(tag, "Companion restore exchange failed", e)
            CompanionHelperProtocol.RestoreResult.INDETERMINATE
        } finally {
            deadline.close()
        }
    }

    override fun close() {
        runCatching { socket.close() }
    }

    private fun Long.asSocketTimeout(): Int = coerceIn(1L, Int.MAX_VALUE.toLong()).toInt()

    private companion object {
        const val SHORT_TIMEOUT_MS = 500
        const val BYTE_TIMEOUT_MS = 5_000
    }
}

private const val COMPANION_CAPABILITY_VERSION = "COMPANIONCAPS 1 BACKUP RESTORE STATUS JOURNAL"
private const val MAX_LOGCAT_LINE_BYTES = 8192
private const val LOGCAT_READ_TIMEOUT_MS = 1_000
private const val LOGCAT_DUMP_PROTOCOL_MINOR = 4
private const val LOGCAT_DUMP_MAX_LINES = 1_000
private const val LOGCAT_DUMP_MAX_REPLY_BYTES = 3L + 256L * 1024L // "OK\n" + the helper's byte cap

/** Keeps a partial source line across read timeouts so cancellation cannot split an entry. */
internal class HelperLogcatLineReader(private val input: InputStream) {
    private val bytes = ByteArray(MAX_LOGCAT_LINE_BYTES)
    private var used = 0

    fun readLine(): String? {
        while (true) {
            val next = input.read()
            if (next < 0) {
                if (used != 0) throw IOException("partial helper logcat line")
                return null
            }
            if (next == '\n'.code) {
                val line = String(bytes, 0, used, StandardCharsets.UTF_8)
                used = 0
                return line
            }
            if (used == bytes.size) throw IOException("helper logcat line exceeds bound")
            bytes[used++] = next.toByte()
        }
    }
}

internal fun companionCapabilitySupported(reply: String?): Boolean = reply == COMPANION_CAPABILITY_VERSION

internal fun helperBuildIdentitySupported(reply: String?, expectedBuildId: String): Boolean =
    expectedBuildId.matches(Regex("[0-9a-f]{64}")) && reply == "BUILDID $expectedBuildId"

internal fun parseCompanionOperationStatus(reply: String?): CompanionOperationStatus = when (reply) {
    "IDLE" -> CompanionOperationStatus.IDLE
    "BUSY" -> CompanionOperationStatus.BUSY
    "ERR" -> CompanionOperationStatus.UNSUPPORTED
    else -> CompanionOperationStatus.UNAVAILABLE
}

internal enum class CompanionOperationStatus {
    IDLE,
    BUSY,
    UNSUPPORTED,
    UNAVAILABLE,
}
