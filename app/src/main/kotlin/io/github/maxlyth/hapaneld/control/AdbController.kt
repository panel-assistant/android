package io.github.maxlyth.hapaneld.control

import android.content.Context
import android.os.SystemClock
import android.webkit.WebView
import io.github.maxlyth.hapaneld.Config
import io.github.maxlyth.hapaneld.util.AccessDenialMemo
import io.github.maxlyth.hapaneld.util.DurableRecoveryMarker
import java.io.File
import java.io.InputStream
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardOpenOption
import java.util.concurrent.TimeUnit

/**
 * Persistent network-adb control. Network adb (`adb tcpip 5555`) normally resets on reboot; setting
 * `persist.adb.tcp.port` is *supposed* to make it survive — but some panel firmwares strip that prop at
 * boot, so relying on the OS alone is unreliable. Instead ha-paneld records its own **intent** (the
 * switch) in [Config] and **re-asserts** adb-tcp at boot/reconnect ([reassert]) while the switch is on.
 *
 * Two states are distinguished for the UI: adb persisted BY ha-paneld vs adb merely **active** because
 * something else turned it on. On OFF, ha-paneld only tears adb down if it was the one that enabled it —
 * it never disables an adb port another mechanism started.
 *
 * ⚠️ Security: this leaves a standing adb port open on the LAN — opt-in and root-gated ([Su]). Toggling
 * restarts adbd, briefly dropping an existing adb-over-tcp connection (it re-establishes).
 */
class AdbController private constructor(
    private val config: Config,
    private val disableMarker: NetworkAdbDisableTransitionMarker,
) {
    constructor(context: Context, config: Config) : this(
        config = config,
        disableMarker = NetworkAdbDisableTransitionMarker(
            context.applicationContext.noBackupFilesDir.resolve(NETWORK_ADB_DISABLE_MARKER_FILE),
        ),
    )

    /** Root present — required to set the props and restart adbd. */
    fun available(): Boolean = Su.available()

    /** ha-paneld's persist intent = the switch state. ha-paneld re-asserts adb-tcp at boot/reconnect
     *  while this is true, so it survives firmwares that drop `persist.adb.tcp.port`. */
    fun isPersisted(): Boolean = config.networkAdbEnabled

    /** Exact Hardened/Guard admission: durable OFF marker settled, ownership false, and live state
     * repeatedly proven inactive. Caller composes this under [RemoteDebugSecurityTransitionGate]. */
    internal fun hardenedRemoteDebugOff(): Boolean = !config.networkAdbEnabled &&
        disableMarker.isAbsentDurably() && proveInactive()

    /** Whether network adb is live or enabled right now, or null when the relevant properties cannot
     *  all be proven inactive. The unprivileged reads detect Developer-options/external adb even on
     *  devices without root; root cross-checks reads that are empty, unavailable, or malformed for the
     *  app UID. */
    fun activeState(allowRootCrossCheck: Boolean = true): Boolean? = remoteAdbActiveState(
        propertyState = networkAdbActiveState(
            directRead = ::readSystemPropertyDirect,
            rootRead = if (allowRootCrossCheck) ::readSystemPropertyRoot else null,
        ),
        listenerState = networkAdbListenerActiveState(
            directRead = ::readTcpListenerInventoryDirect,
            rootRead = if (allowRootCrossCheck) ::readTcpListenerInventoryRoot else null,
        ),
    )

    /** True when network adb is known to be active or enabled over the LAN now. */
    fun isActive(allowRootCrossCheck: Boolean = true): Boolean = activeState(allowRootCrossCheck) == true

    /** Bounded post-restart proof used both by explicit OFF and Hardened admission. */
    fun proveInactive(allowRootCrossCheck: Boolean = true): Boolean = proveSettledNetworkAdbInactive(
        sample = { activeState(allowRootCrossCheck) },
        monotonicMs = SystemClock::elapsedRealtime,
        waitMs = { delay ->
            try {
                Thread.sleep(delay)
                true
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                false
            }
        },
    )

    /**
     * Enable/disable ha-paneld-persistent network adb and apply it now (restarting adbd). ON records the
     * intent + brings adb up. OFF clears the intent, and only tears adb down if ha-paneld was the one
     * persisting it — an adb port another mechanism opened is left running.
     */
    fun set(on: Boolean): Boolean = RemoteDebugSecurityTransitionGate.mutate {
        if (on) {
            // A marker is a durable OFF instruction, including when its contents are corrupt. Never
            // convert an interrupted teardown into TCP enablement merely because config ownership is
            // still true from the pre-teardown state.
            if (disableMarker.isPending()) return@mutate false
            if (!config.setNetworkAdbEnabled(true)) return@mutate false
            return@mutate apply()
        }
        // This switch is also the explicit admission control for the built-in renderer's DevTools
        // socket. Closing the global WebView debugging surface before the potentially slow root teardown
        // prevents a previously-created WebView from remaining debuggable until its next rebuild.
        WebView.setWebContentsDebuggingEnabled(false)
        val complete = completeDisableTransition()
        if (complete) resealHardenedAuthorityIfExact()
        complete
    }

    /**
     * Boot/reconnect re-assert: if ha-paneld is persisting network adb but it isn't live (a firmware
     * that stripped the prop at boot, or adbd died), bring it back. Idempotent no-op when already active,
     * intent is off, or there's no root.
     */
    fun reassert(): Unit = RemoteDebugSecurityTransitionGate.mutate {
        if (disableMarker.isPending()) {
            // Startup/reconnect recovery always gives an interrupted OFF transition priority over the
            // stale pre-transition ownership bit. The marker remains armed if root or verification is
            // unavailable, so a later reconnect retries instead of enabling TCP.
            WebView.setWebContentsDebuggingEnabled(false)
            if (available()) completeDisableTransition()
            resealHardenedAuthorityIfExact()
            return@mutate
        }
        val persisted = config.networkAdbEnabled
        val rootAvailable = available()
        if (shouldReassertNetworkAdb(
                persisted = persisted,
                disablePending = false,
                rootAvailable = rootAvailable,
                active = if (persisted && rootAvailable) isActive() else false,
            )
        ) apply()
        resealHardenedAuthorityIfExact()
    }

    /** Commit Hardened only inside the same process-wide critical section that proves remote ADB
     * inactive and removes any disable marker. */
    internal fun commitHardenedWhenRemoteAdbInactive(commit: () -> Boolean): HardenedNetworkAdbAdmission =
        RemoteDebugSecurityTransitionGate.mutate {
            WebView.setWebContentsDebuggingEnabled(false)
            val disableRequired = config.networkAdbEnabled || disableMarker.isPending()
            completeHardenedNetworkAdbAdmission(
                disableRequired = disableRequired,
                finishDisable = ::completeDisableTransition,
                proveInactive = ::proveInactive,
                activeReadback = ::activeState,
                ownershipEnabled = { config.networkAdbEnabled },
                disableAbsentDurably = disableMarker::isAbsentDurably,
                commitHardened = commit,
            )
        }

    private fun completeDisableTransition(): Boolean = completeNetworkAdbDisableTransition(
        owned = { config.networkAdbEnabled },
        disablePending = disableMarker::isPending,
        armDisable = disableMarker::arm,
        teardown = { Su.run(networkAdbDisableCommand()) },
        inactiveReadback = ::proveInactive,
        clearOwnership = { config.setNetworkAdbEnabled(false) },
        clearDisable = disableMarker::clear,
    )

    private fun apply(): Boolean =
        Su.run(networkAdbEnableCommand())

    private fun resealHardenedAuthorityIfExact() {
        if (config.hardenedSecurityEnabled && !CdpRelay.running && hardenedRemoteDebugOff()) {
            check(RemoteDebugSecurityTransitionGate.sealHardened()) {
                "durable Hardened security authority publication failed"
            }
        }
    }

    /** UI status — ha-paneld-persisted vs merely externally-active vs off. */
    fun statusText(): String {
        val active = activeState()
        return when {
            disableMarker.isPending() -> "disabling (recovery pending)"
            config.networkAdbEnabled -> "persistent (ha-paneld)"
            active == true -> "active (external — not ha-paneld)"
            active == null -> "unknown"
            else -> "off"
        }
    }

    companion object {
        internal const val PORT = "5555"

        /** DB-free successor proof: the durable OFF transition is absent and all fixed Android
         * property plus IPv4/IPv6 listener observations remain inactive across restart settling. */
        internal fun proveMaintenanceRemoteDebugOff(context: Context): Boolean {
            val marker = NetworkAdbDisableTransitionMarker(
                context.applicationContext.noBackupFilesDir.resolve(NETWORK_ADB_DISABLE_MARKER_FILE),
            )
            if (!marker.isAbsentDurably()) return false
            return proveSettledNetworkAdbInactive(
                sample = {
                    remoteAdbActiveState(
                        propertyState = networkAdbActiveState(
                            directRead = ::readSystemPropertyDirect,
                            rootRead = ::readSystemPropertyRoot,
                        ),
                        listenerState = networkAdbListenerActiveState(
                            directRead = ::readTcpListenerInventoryDirect,
                            rootRead = ::readTcpListenerInventoryRoot,
                        ),
                    )
                },
                monotonicMs = SystemClock::elapsedRealtime,
                waitMs = { delay ->
                    try {
                        Thread.sleep(delay)
                        true
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                        false
                    }
                },
            )
        }
    }
}

/** One process-wide lock serializes remote-debug proof and security-mode authority changes. Callers that
 * compose CdpRelay state, ADB state, Hardened/Relaxed config, or an ARM sentinel must hold this gate for
 * the complete proof-and-commit sequence. JVM monitors are reentrant, so gated compositions may call the
 * AdbController methods above without changing lock order. */
internal object RemoteDebugSecurityTransitionGate {
    private val lock = Any()
    private var epoch = 0L
    private var authorityStore: RemoteDebugSecurityAuthorityStore? = null

    fun install(noBackupFilesDir: File) = synchronized(lock) {
        authorityStore = RemoteDebugSecurityAuthorityStore(noBackupFilesDir)
        epoch = ((authorityStore?.load() as? RemoteDebugSecurityAuthorityLoad.Valid)?.authority?.epoch) ?: 0L
    }

    fun <T> withLock(action: () -> T): T = synchronized(lock, action)

    fun authorityEpoch(): Long = synchronized(lock) { epoch }

    fun hardenedAuthorityEpoch(): Long? = synchronized(lock) {
        val authority = (authorityStore?.load() as? RemoteDebugSecurityAuthorityLoad.Valid)?.authority
        authority?.epoch?.takeIf { authority.state == RemoteDebugSecurityState.HARDENED && it == epoch }
    }

    fun sealHardened(): Boolean = synchronized(lock) {
        val store = authorityStore ?: return@synchronized false
        store.publishHardened(epoch) && hardenedAuthorityEpoch() == epoch
    }

    fun <T> mutate(action: () -> T): T = synchronized(lock) {
        val store = authorityStore
        if (store != null) {
            val transition = store.publishTransition()
                ?: throw IllegalStateException("remote-debug security transition was not durable")
            epoch = transition.epoch
        } else {
            epoch = if (epoch == Long.MAX_VALUE) 1L else epoch + 1L
        }
        action()
    }

    fun <T> withEpoch(expected: Long, action: () -> T): RemoteDebugAuthorityResult<T> = synchronized(lock) {
        if (epoch != expected) RemoteDebugAuthorityResult.Changed
        else RemoteDebugAuthorityResult.Value(action())
    }
}

internal sealed interface RemoteDebugAuthorityResult<out T> {
    data object Changed : RemoteDebugAuthorityResult<Nothing>
    data class Value<T>(val value: T) : RemoteDebugAuthorityResult<T>
}

internal enum class HardenedNetworkAdbAdmission {
    APPLIED,
    DISABLE_FAILED,
    ACTIVE,
    UNVERIFIED,
    COMMIT_FAILED,
}

/** A regular marker with unexpected contents is still pending. A directory, symlink, unreadable entry,
 * or any other corruption also fails closed and is preserved until the OFF proof reaches final cleanup. */
internal class NetworkAdbDisableTransitionMarker(private val file: File) {
    private val durable = DurableRecoveryMarker(file)
    private val path = file.toPath()
    private val parent = requireNotNull(file.parentFile).toPath()

    fun isPending(): Boolean = !Files.notExists(path, LinkOption.NOFOLLOW_LINKS)

    fun arm(): Boolean {
        if (!isPending() || Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) return durable.arm()
        // A corrupt entry still carries the fail-closed OFF meaning. It survived long enough to be
        // observed, and syncing its parent makes that directory entry durable before teardown resumes.
        return runCatching { syncParent(); true }.getOrDefault(false)
    }

    fun clear(): Boolean {
        if (!isPending()) return true
        if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) return durable.clear()
        // Delete the corrupt entry itself (including a symlink), never a target. A non-empty directory
        // refuses deletion and therefore remains a fail-closed recovery instruction.
        return runCatching {
            if (!Files.deleteIfExists(path)) return@runCatching true
            syncParent()
            !isPending()
        }.getOrDefault(false)
    }

    /** Directory fsync makes a prior unlink authoritative even when its original clear call died or
     * reported failure after removing the entry. Hardened admission requires this stronger absence. */
    fun isAbsentDurably(): Boolean {
        if (isPending()) return false
        return runCatching {
            syncParent()
            !isPending()
        }.getOrDefault(false)
    }

    private fun syncParent() {
        FileChannel.open(parent, StandardOpenOption.READ).use { it.force(true) }
    }
}

/** A marker-backed OFF transition is a write-ahead state machine. Each false return retains either the
 * durable marker or the already-safe ownership=false state, so the next process can repeat the work. */
internal fun completeNetworkAdbDisableTransition(
    owned: () -> Boolean,
    disablePending: () -> Boolean,
    armDisable: () -> Boolean,
    teardown: () -> Boolean,
    inactiveReadback: () -> Boolean,
    clearOwnership: () -> Boolean,
    clearDisable: () -> Boolean,
): Boolean {
    if (!owned() && !disablePending()) return true // externally-active adb — don't kill it
    if (!armDisable()) return false
    if (!teardown()) return false
    if (!inactiveReadback()) return false
    // The SQLite-backed ownership write has durable visibility before marker removal. A cut after this
    // commit is safe: marker recovery ignores false ownership and repeats OFF rather than enabling TCP.
    if (!clearOwnership() || owned()) return false
    if (!clearDisable()) return false
    return !disablePending()
}

/** Hardened admission consumes the same disable state machine. A completed disable already includes the
 * settled property + IPv4/IPv6 listener proof; an externally-owned/absent transition needs a fresh proof. */
internal fun completeHardenedNetworkAdbAdmission(
    disableRequired: Boolean,
    finishDisable: () -> Boolean,
    proveInactive: () -> Boolean,
    activeReadback: () -> Boolean?,
    ownershipEnabled: () -> Boolean,
    disableAbsentDurably: () -> Boolean,
    commitHardened: () -> Boolean,
): HardenedNetworkAdbAdmission {
    val settledInactive = if (disableRequired) finishDisable() else proveInactive()
    if (!settledInactive) {
        if (disableRequired) return HardenedNetworkAdbAdmission.DISABLE_FAILED
        return when (activeReadback()) {
            true -> HardenedNetworkAdbAdmission.ACTIVE
            else -> HardenedNetworkAdbAdmission.UNVERIFIED
        }
    }
    if (ownershipEnabled() || !disableAbsentDurably()) return HardenedNetworkAdbAdmission.DISABLE_FAILED
    return if (commitHardened()) {
        HardenedNetworkAdbAdmission.APPLIED
    } else {
        HardenedNetworkAdbAdmission.COMMIT_FAILED
    }
}

internal fun shouldReassertNetworkAdb(
    persisted: Boolean,
    disablePending: Boolean,
    rootAvailable: Boolean,
    active: Boolean,
): Boolean = persisted && !disablePending && rootAvailable && !active

/**
 * Prefer app-readable Android properties so an unrooted panel can still detect externally enabled adb.
 * Android may return an empty value when SELinux hides a property from the app UID, so empty, failed,
 * and malformed direct reads are cross-checked as root. A known-positive signal wins; false is returned
 * only when every classic-TCP and wireless-debugging signal is authoritatively inactive. Returning null
 * lets security-sensitive callers fail closed when that cannot be established.
 */
internal fun networkAdbActiveState(
    directRead: (String) -> String?,
    rootRead: ((String) -> String?)?,
): Boolean? {
    val directStates = NETWORK_ADB_PROPERTIES.associateWith { property ->
        property.parse(directRead(property.name), emptyIsInactive = false)
    }
    if (directStates.values.any { it == true }) return true

    var unknown = false
    for (property in NETWORK_ADB_PROPERTIES) {
        val state = directStates.getValue(property)
            ?: rootRead?.let { property.parse(it(property.name), emptyIsInactive = true) }
        when (state) {
            true -> return true
            false -> Unit
            null -> unknown = true
        }
    }
    return if (unknown) null else false
}

/** Properties describe adbd's requested transports; the kernel socket tables prove that the classic
 *  listener they are meant to control has actually disappeared. Either positive wins, and Hardened
 *  admission receives false only when both independent observations are authoritatively inactive. */
internal fun remoteAdbActiveState(propertyState: Boolean?, listenerState: Boolean?): Boolean? = when {
    propertyState == true || listenerState == true -> true
    propertyState == false && listenerState == false -> false
    else -> null
}

/**
 * Require a complete inactive property+listener observation to remain unchanged across adbd's
 * restart-stabilization window. The first sample is delayed, then at least five complete samples span
 * two more seconds. Any active/unknown sample, interrupted wait, backwards/stalled clock or deadline
 * expiry fails closed; there is no transient-zero success window for vendor init to republish into.
 */
internal fun proveSettledNetworkAdbInactive(
    sample: () -> Boolean?,
    monotonicMs: () -> Long,
    waitMs: (Long) -> Boolean,
    initialSettleMs: Long = NETWORK_ADB_INITIAL_SETTLE_MS,
    sampleIntervalMs: Long = NETWORK_ADB_SAMPLE_INTERVAL_MS,
    requiredStableMs: Long = NETWORK_ADB_REQUIRED_STABLE_MS,
    deadlineMs: Long = NETWORK_ADB_SETTLE_DEADLINE_MS,
): Boolean {
    require(initialSettleMs >= 0L && sampleIntervalMs > 0L && requiredStableMs >= 0L)
    require(deadlineMs >= initialSettleMs + requiredStableMs)
    val started = monotonicMs()
    if (!waitMs(initialSettleMs)) return false
    var sampledAt = monotonicMs()
    if (sampledAt < started || sampledAt - started > deadlineMs) return false
    val firstInactiveAt = sampledAt
    var samples = 0
    while (true) {
        if (sample() != false) return false
        samples++
        sampledAt = monotonicMs()
        if (sampledAt < firstInactiveAt || sampledAt - started > deadlineMs) return false
        if (samples >= MIN_SETTLED_INACTIVE_SAMPLES && sampledAt - firstInactiveAt >= requiredStableMs) return true
        val remaining = deadlineMs - (sampledAt - started)
        if (remaining <= 0L) return false
        val delay = minOf(sampleIntervalMs, remaining)
        if (!waitMs(delay)) return false
        val afterWait = monotonicMs()
        if (afterWait <= sampledAt) return false
    }
}

/** Read both IPv4 and IPv6 kernel TCP inventories. A missing, truncated or malformed inventory is
 *  unknown rather than absence; no device-side text processor is required. */
internal fun networkAdbListenerActiveState(
    directRead: (String) -> String?,
    rootRead: ((String) -> String?)?,
): Boolean? {
    var unknown = false
    for (path in NETWORK_ADB_TCP_INVENTORIES) {
        val direct = parseTcpListenerInventory(directRead(path))
        val state = direct ?: rootRead?.let { parseTcpListenerInventory(it(path)) }
        when (state) {
            true -> return true
            false -> Unit
            null -> unknown = true
        }
    }
    return if (unknown) null else false
}

/** `/proc/net/tcp*` rows are stable kernel ABI: local address is column 2 and state is column 4. */
internal fun parseTcpListenerInventory(raw: String?): Boolean? {
    if (raw == null || raw.length > MAX_TCP_INVENTORY_BYTES) return null
    val lines = raw.lineSequence().filter(String::isNotBlank).toList()
    if (lines.isEmpty() || !lines.first().contains("local_address") || !lines.first().contains("st")) return null
    for (line in lines.drop(1)) {
        val columns = line.trim().split(Regex("\\s+"))
        if (columns.size < 4) return null
        val local = columns[1]
        val separator = local.lastIndexOf(':')
        if (separator <= 0 || separator == local.lastIndex) return null
        val port = local.substring(separator + 1).toIntOrNull(16) ?: return null
        val state = columns[3].uppercase()
        if (state.length != 2 || state.any { it !in "0123456789ABCDEF" }) return null
        if (state == TCP_LISTEN_STATE && port == AdbController.PORT.toInt()) return true
    }
    return false
}

/** A property SELinux hides from the app reads as empty, exactly like an unset one; only the denied
 *  case is remembered, so an unset property is still read on every poll and a later value is seen. */
private fun readSystemPropertyDirect(name: String): String? {
    val fixedName = NETWORK_ADB_PROPERTIES.firstOrNull { it.name == name }?.name ?: return null
    return AccessDenialMemo.app.read(
        key = "property:$fixedName",
        what = "Android property $fixedName",
        suspect = { it.isNullOrEmpty() },
        probeDenied = { systemPropertyAreaDenied(fixedName) },
    ) { runGetprop(fixedName) }
}

/** The app opens `/dev/__properties__/<context>` to read a property; EACCES there is the denial. */
private fun systemPropertyAreaDenied(fixedName: String): Boolean {
    val context = runGetprop("-Z", fixedName)?.takeIf { PROPERTY_CONTEXT.matches(it) } ?: return false
    return AccessDenialMemo.openDenied("$PROPERTY_AREA_DIR/$context")
}

private fun runGetprop(vararg fixedArguments: String): String? =
    runCatching {
        val process = ProcessBuilder(SYSTEM_GETPROP, *fixedArguments)
            .redirectErrorStream(true)
            .start()
        try {
            if (!process.waitFor(PROPERTY_READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                process.destroy()
                if (!process.waitFor(PROPERTY_DESTROY_GRACE_MS, TimeUnit.MILLISECONDS)) {
                    process.destroyForcibly()
                }
                return@runCatching null
            }
            if (process.exitValue() != 0) return@runCatching null
            readBounded(process.inputStream, MAX_PROPERTY_OUTPUT_BYTES)?.trim()
        } finally {
            runCatching { process.inputStream.close() }
            runCatching { process.errorStream.close() }
            runCatching { process.outputStream.close() }
        }
    }.getOrNull()

private fun readSystemPropertyRoot(name: String): String? {
    val command = networkAdbRootReadCommand(name) ?: return null
    return Su.runOutputIsolatedBounded(
        command,
        maxBytes = MAX_PROPERTY_OUTPUT_BYTES.toLong(),
        timeoutMs = PROPERTY_READ_TIMEOUT_MS,
    )?.trim()
}

private fun readTcpListenerInventoryDirect(path: String): String? {
    val fixedPath = NETWORK_ADB_TCP_INVENTORIES.firstOrNull { it == path } ?: return null
    return AccessDenialMemo.app.read(
        key = "file:$fixedPath",
        what = "Kernel socket table $fixedPath",
        probeDenied = { AccessDenialMemo.openDenied(fixedPath) },
    ) { readFixedCommand(fixedArgument = fixedPath, maxBytes = MAX_TCP_INVENTORY_BYTES) }
}

private fun readTcpListenerInventoryRoot(path: String): String? {
    val command = networkAdbRootListenerReadCommand(path) ?: return null
    return Su.runOutputIsolatedBounded(
        command,
        maxBytes = MAX_TCP_INVENTORY_BYTES.toLong(),
        timeoutMs = PROPERTY_READ_TIMEOUT_MS,
    )
}

private fun readFixedCommand(fixedArgument: String, maxBytes: Int): String? = runCatching {
    val process = ProcessBuilder(SYSTEM_CAT, fixedArgument)
        .redirectErrorStream(true)
        .start()
    try {
        if (!process.waitFor(PROPERTY_READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            process.destroy()
            if (!process.waitFor(PROPERTY_DESTROY_GRACE_MS, TimeUnit.MILLISECONDS)) process.destroyForcibly()
            return@runCatching null
        }
        if (process.exitValue() != 0) return@runCatching null
        readBounded(process.inputStream, maxBytes)
    } finally {
        runCatching { process.inputStream.close() }
        runCatching { process.errorStream.close() }
        runCatching { process.outputStream.close() }
    }
}.getOrNull()

private fun readBounded(input: InputStream, maxBytes: Int): String? {
    val bytes = ByteArray(maxBytes + 1)
    var size = 0
    while (size < bytes.size) {
        val read = input.read(bytes, size, bytes.size - size)
        if (read < 0) break
        if (read == 0) continue
        size += read
    }
    if (size > maxBytes) return null
    return String(bytes, 0, size, Charsets.UTF_8)
}

private const val SYSTEM_GETPROP = "/system/bin/getprop"
private const val SYSTEM_CAT = "/system/bin/cat"
private const val PROPERTY_AREA_DIR = "/dev/__properties__"
private val PROPERTY_CONTEXT = Regex("u:object_r:[a-z0-9_]+:s0")
private const val PROPERTY_READ_TIMEOUT_MS = 1_000L
private const val PROPERTY_DESTROY_GRACE_MS = 100L
private const val NETWORK_ADB_DISABLE_MARKER_FILE = "network-adb-disable.v1"
private const val MAX_PROPERTY_OUTPUT_BYTES = 128
private const val MAX_TCP_INVENTORY_BYTES = 64 * 1024
private const val TCP_LISTEN_STATE = "0A"
private val NETWORK_ADB_TCP_INVENTORIES = listOf("/proc/net/tcp", "/proc/net/tcp6")
internal const val NETWORK_ADB_INITIAL_SETTLE_MS = 1_000L
internal const val NETWORK_ADB_SAMPLE_INTERVAL_MS = 500L
internal const val NETWORK_ADB_REQUIRED_STABLE_MS = 2_000L
internal const val NETWORK_ADB_SETTLE_DEADLINE_MS = 6_000L
private const val MIN_SETTLED_INACTIVE_SAMPLES = 5

internal const val SERVICE_ADB_TCP_PORT_PROPERTY = "service.adb.tcp.port"
internal const val PERSIST_ADB_TCP_PORT_PROPERTY = "persist.adb.tcp.port"
internal const val SERVICE_ADB_LISTEN_ADDRS_PROPERTY = "service.adb.listen_addrs"
internal const val SERVICE_ADB_TLS_PORT_PROPERTY = "service.adb.tls.port"
internal const val PERSIST_ADB_TLS_SERVER_ENABLE_PROPERTY = "persist.adb.tls_server.enable"

private enum class NetworkAdbPropertyKind { PORT, ENABLED, LISTEN_ADDRESSES }

private data class NetworkAdbProperty(
    val name: String,
    val kind: NetworkAdbPropertyKind,
) {
    fun parse(raw: String?, emptyIsInactive: Boolean): Boolean? {
        val value = raw?.trim() ?: return null
        if (value.isEmpty()) return if (emptyIsInactive) false else null
        return when (kind) {
            NetworkAdbPropertyKind.PORT -> value.toIntOrNull()?.let { port ->
                when {
                    port in 1..MAX_NETWORK_PORT -> true
                    port <= 0 -> false
                    else -> null
                }
            }
            NetworkAdbPropertyKind.ENABLED -> when (value) {
                "1" -> true
                "0" -> false
                else -> null
            }
            // AOSP gives this property precedence over every port property and passes each comma-
            // separated socket spec directly to adbd. Treat any non-empty value as active: attempting
            // to duplicate every present and future socket-spec grammar here could misclassify an
            // unfamiliar but valid endpoint as off. A malformed value therefore also fails closed.
            NetworkAdbPropertyKind.LISTEN_ADDRESSES -> true
        }
    }
}

private val NETWORK_ADB_PROPERTIES = listOf(
    NetworkAdbProperty(SERVICE_ADB_LISTEN_ADDRS_PROPERTY, NetworkAdbPropertyKind.LISTEN_ADDRESSES),
    NetworkAdbProperty(SERVICE_ADB_TCP_PORT_PROPERTY, NetworkAdbPropertyKind.PORT),
    NetworkAdbProperty(PERSIST_ADB_TCP_PORT_PROPERTY, NetworkAdbPropertyKind.PORT),
    NetworkAdbProperty(SERVICE_ADB_TLS_PORT_PROPERTY, NetworkAdbPropertyKind.PORT),
    NetworkAdbProperty(PERSIST_ADB_TLS_SERVER_ENABLE_PROPERTY, NetworkAdbPropertyKind.ENABLED),
)

/** Only fixed, enumerated property names can enter the privileged shell command. */
internal fun networkAdbRootReadCommand(name: String): String? =
    NETWORK_ADB_PROPERTIES.firstOrNull { it.name == name }
        ?.let { "$SYSTEM_GETPROP ${it.name}" }

/** Only the two fixed kernel socket inventories can enter the privileged command. */
internal fun networkAdbRootListenerReadCommand(path: String): String? =
    NETWORK_ADB_TCP_INVENTORIES.firstOrNull { it == path }
        ?.let { "$SYSTEM_CAT $it" }

private const val MAX_NETWORK_PORT = 65_535

internal fun networkAdbEnableCommand(): String = adbTransitionCommand(
    "setprop persist.adb.tcp.port ${AdbController.PORT}",
    "setprop service.adb.tcp.port ${AdbController.PORT}",
    "setprop ctl.restart adbd",
)

internal fun networkAdbDisableCommand(): String = adbTransitionCommand(
    "setprop service.adb.listen_addrs \"\"",
    "setprop persist.adb.tcp.port \"\"",
    "setprop service.adb.tcp.port \"\"",
    "setprop persist.adb.tls_server.enable 0",
    "setprop service.adb.tls.port \"\"",
    "setprop ctl.restart adbd",
)

/**
 * RootShell reports the exit status of the complete shell expression. A semicolon-separated sequence
 * would therefore report only the final command and could mask an earlier failed property write.
 */
private fun adbTransitionCommand(vararg commands: String): String = commands.joinToString(" && ")
