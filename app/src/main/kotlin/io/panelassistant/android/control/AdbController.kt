package io.panelassistant.android.control

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.webkit.WebView
import io.panelassistant.android.Config
import io.panelassistant.android.platform.Daemon
import io.panelassistant.android.platform.HelperAdbState
import io.panelassistant.android.util.HelperClient
import io.panelassistant.android.platform.RootRunOutcome
import io.panelassistant.android.platform.RootShell
import io.panelassistant.android.util.AccessDenialMemo
import io.panelassistant.android.util.DurableRecoveryMarker
import java.io.File
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardOpenOption
import java.util.concurrent.CountDownLatch
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
class AdbController internal constructor(
    private val config: Config,
    private val disableMarker: NetworkAdbDisableTransitionMarker,
    // Production injects Su; a JVM test injects a fake root boundary.
    private val root: RootShell = Su,
    private val managedOff: () -> Boolean = { false },
    private val daemon: Daemon = HelperClient,
    private val directPropertyRead: (String) -> String? = ::readSystemPropertyDirect,
    private val directListenerRead: (String) -> String? = ::readTcpListenerInventoryDirect,
) {
    constructor(context: Context, config: Config) : this(
        config = config,
        disableMarker = NetworkAdbDisableTransitionMarker(
            context.applicationContext.noBackupFilesDir.resolve(NETWORK_ADB_DISABLE_MARKER_FILE),
        ),
        managedOff = { io.panelassistant.android.panelassistant.RootLocalAdbEndpoint().state()?.let { it.managed && !it.recoveryHold } == true },
    )

    /** Root present — required to set the props and restart adbd. */
    fun available(): Boolean = root.available()

    /** ha-paneld's persist intent = the switch state. ha-paneld re-asserts adb-tcp at boot/reconnect
     *  while this is true, so it survives firmwares that drop `persist.adb.tcp.port`. */
    fun isPersisted(): Boolean = config.networkAdbEnabled && !ManagedNetworkAdb.managed

    /** Exact Hardened/Guard admission: durable OFF marker settled, ownership false, and live state
     * repeatedly proven inactive. Caller composes this under [RemoteDebugSecurityTransitionGate]. */
    internal fun hardenedRemoteDebugOff(): Boolean = !config.networkAdbEnabled &&
        disableMarker.isAbsentDurably() && (managedOffProof() || proveInactive())

    private fun managedOffProof(): Boolean = ManagedNetworkAdb.managed && managedOff()

    internal fun resealManagedHardened(): Boolean = RemoteDebugSecurityTransitionGate.withLock {
        if (!config.hardenedSecurityEnabled || !managedOffProof() || !CdpRelay.proveAbsent() || !disableMarker.isAbsentDurably()) false
        else config.setNetworkAdbEnabled(false) && RemoteDebugSecurityTransitionGate.sealHardened()
    }

    /** App-readable positive observations remain authoritative; unknown reads use one fixed helper
     * observation on its admitted connection. Missing or refused helpers remain unknown. */
    fun activeState(): Boolean? = observeNetworkAdbActiveState(directPropertyRead, directListenerRead, daemon::adbState)

    /** Persisted intent reports the listener alone, rather than its own property writes. */
    fun listenerState(): Boolean? = networkAdbListenerActiveState(directListenerRead) { daemon.adbState()?.listener }

    fun isActive(): Boolean = activeState() == true

    /** Every settled sample acquires fresh helper observations; no management cache can prove OFF. */
    fun proveInactive(): Boolean = proveSettledNetworkAdbInactive(
        sample = ::activeState,
        monotonicMs = SystemClock::elapsedRealtime,
        waitMs = ::waitForNetworkAdb,
    )

    /**
     * Enable/disable ha-paneld-persistent network adb and apply it now (restarting adbd). ON records the
     * intent + brings adb up. OFF clears the intent, and only tears adb down if ha-paneld was the one
     * persisting it — an adb port another mechanism opened is left running.
     */
    internal fun set(on: Boolean): ControlApplyOutcome = RemoteDebugSecurityTransitionGate.mutate {
        if (on) {
            if (ManagedNetworkAdb.ownsRuntime()) return@mutate ControlApplyOutcome.FAILED
            // A marker is a durable OFF instruction, including when its contents are corrupt. Never
            // convert an interrupted teardown into TCP enablement merely because config ownership is
            // still true from the pre-teardown state.
            if (disableMarker.isPending()) return@mutate ControlApplyOutcome.FAILED
            // Without root the app cannot open the port, so recording the intent would leave a switch
            // that reads ON over a port the app never owned (and, before OFF learned the same rule, one
            // it could not turn off). Only a root process that could not even start is a missing path; a
            // root manager that said no can say yes later.
            when (root.runClassified("true")) {
                RootRunOutcome.NO_LAUNCH -> return@mutate ControlApplyOutcome.UNAVAILABLE
                RootRunOutcome.RAN_FAILED -> return@mutate ControlApplyOutcome.FAILED
                RootRunOutcome.RAN_OK -> Unit
            }
            if (!config.setNetworkAdbEnabled(true)) return@mutate ControlApplyOutcome.FAILED
            return@mutate if (apply()) ControlApplyOutcome.APPLIED else ControlApplyOutcome.FAILED
        }
        if (ManagedNetworkAdb.ownsRuntime()) {
            closeWebViewDebugging()
            return@mutate if (config.setNetworkAdbEnabled(false)) ControlApplyOutcome.APPLIED else ControlApplyOutcome.FAILED
        }
        // This switch is also the explicit admission control for the built-in renderer's DevTools
        // socket. Closing the global WebView debugging surface before the potentially slow root teardown
        // prevents a previously-created WebView from remaining debuggable until its next rebuild.
        closeWebViewDebugging()
        val complete = completeDisableTransition()
        if (complete) resealHardenedAuthorityIfExact()
        if (complete) ControlApplyOutcome.APPLIED else ControlApplyOutcome.FAILED
    }

    /**
     * Boot/reconnect re-assert: if ha-paneld is persisting network adb but nothing listens on its port (a
     * firmware that stripped or overrode the prop at boot, or adbd restarted without TCP), bring it back.
     * No-op while it listens, when the listener cannot be read (re-asserting restarts adbd and would drop
     * live sessions), when intent is off, or without root.
     */
    fun reassert(): Unit = RemoteDebugSecurityTransitionGate.mutate {
        if (disableMarker.isPending()) {
            // Startup/reconnect recovery always gives an interrupted OFF transition priority over the
            // stale pre-transition ownership bit. The marker remains armed if root or verification is
            // unavailable, so a later reconnect retries instead of enabling TCP.
            closeWebViewDebugging()
            if (available()) completeDisableTransition()
            resealHardenedAuthorityIfExact()
            return@mutate
        }
        if (ManagedNetworkAdb.ownsRuntime()) return@mutate
        val persisted = config.networkAdbEnabled
        val rootAvailable = available()
        if (shouldReassertNetworkAdb(
                persisted = persisted,
                disablePending = false,
                rootAvailable = rootAvailable,
                listening = if (persisted && rootAvailable) listenerState() else null,
            )
        ) apply()
        resealHardenedAuthorityIfExact()
    }

    /** Commit Hardened only inside the same process-wide critical section that proves remote ADB
     * inactive and removes any disable marker. */
    internal fun commitHardenedWhenRemoteAdbInactive(commit: () -> Boolean): HardenedNetworkAdbAdmission =
        RemoteDebugSecurityTransitionGate.mutate {
            closeWebViewDebugging()
            if (managedOffProof()) {
                if (!config.setNetworkAdbEnabled(false) || !disableMarker.clear() || !disableMarker.isAbsentDurably()) return@mutate HardenedNetworkAdbAdmission.DISABLE_FAILED
                return@mutate if (commit()) HardenedNetworkAdbAdmission.APPLIED else HardenedNetworkAdbAdmission.COMMIT_FAILED
            }
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

    /**
     * WebView refuses the debugging toggle off its UI thread ("Toggling of Web Contents Debugging must
     * be done on the UI thread"), and OFF arrives on the command-dispatch thread, so the call threw and
     * the transition never ran. Run it on the main thread and wait briefly; the transition goes on
     * either way, and the renderer reads the setting again on its next rebuild.
     */
    private fun closeWebViewDebugging() {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            WebView.setWebContentsDebuggingEnabled(false)
            return
        }
        val done = CountDownLatch(1)
        Handler(Looper.getMainLooper()).post {
            runCatching { WebView.setWebContentsDebuggingEnabled(false) }
            done.countDown()
        }
        done.await(WEBVIEW_DEBUG_TOGGLE_WAIT_MS, TimeUnit.MILLISECONDS)
    }

    private fun completeDisableTransition(): Boolean = completeNetworkAdbDisableTransition(
        owned = { config.networkAdbEnabled },
        rootAvailable = ::available,
        disablePending = disableMarker::isPending,
        armDisable = disableMarker::arm,
        teardown = { root.run(networkAdbDisableCommand()) },
        inactiveReadback = ::proveInactive,
        clearOwnership = { config.setNetworkAdbEnabled(false) },
        clearDisable = disableMarker::clear,
    )

    private fun apply(): Boolean =
        root.run(networkAdbEnableCommand())

    private fun resealHardenedAuthorityIfExact() {
        if (config.hardenedSecurityEnabled && CdpRelay.proveAbsent() && hardenedRemoteDebugOff()) {
            check(RemoteDebugSecurityTransitionGate.sealHardened()) {
                "durable Hardened security authority publication failed"
            }
        }
    }

    /** UI status — ha-paneld-persisted vs merely externally-active vs off. */
    fun statusText(): String {
        val active = activeState()
        return when {
            ManagedNetworkAdb.managed -> "off (managed by Panel Assistant)"
            ManagedNetworkAdb.recoveryHold -> "active (recovery)"
            disableMarker.isPending() -> "disabling (recovery pending)"
            config.networkAdbEnabled -> "persistent (Panel Assistant)"
            active == true -> "active (external — not Panel Assistant)"
            active == null -> "unknown"
            else -> "off"
        }
    }

    companion object {
        internal const val PORT = "5555"
        private const val WEBVIEW_DEBUG_TOGGLE_WAIT_MS = 1_000L

        /** DB-free successor proof: the durable OFF transition is absent and all fixed Android
         * property plus IPv4/IPv6 listener observations remain inactive across restart settling. */
        internal fun proveMaintenanceRemoteDebugOff(
            context: Context,
            daemon: Daemon = HelperClient,
            directPropertyRead: (String) -> String? = ::readSystemPropertyDirect,
            directListenerRead: (String) -> String? = ::readTcpListenerInventoryDirect,
            managedOff: () -> Boolean = {
                io.panelassistant.android.panelassistant.RootLocalAdbEndpoint().state()
                    ?.let { it.managed && !it.recoveryHold } == true
            },
            monotonicMs: () -> Long = SystemClock::elapsedRealtime,
            waitMs: (Long) -> Boolean = ::waitForNetworkAdb,
        ): Boolean {
            val marker = NetworkAdbDisableTransitionMarker(
                context.applicationContext.noBackupFilesDir.resolve(NETWORK_ADB_DISABLE_MARKER_FILE),
            )
            if (!marker.isAbsentDurably()) return false
            if (managedOff()) return true
            return proveSettledNetworkAdbInactive(
                sample = { observeNetworkAdbActiveState(directPropertyRead, directListenerRead, daemon::adbState) },
                monotonicMs = monotonicMs,
                waitMs = waitMs,
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
    rootAvailable: () -> Boolean = { true },
    disablePending: () -> Boolean,
    armDisable: () -> Boolean,
    teardown: () -> Boolean,
    inactiveReadback: () -> Boolean,
    clearOwnership: () -> Boolean,
    clearDisable: () -> Boolean,
): Boolean {
    if (!owned() && !disablePending()) return true // externally-active adb — don't kill it
    if (!rootAvailable()) {
        // Without root OFF cannot tear anything down, but it can still stop the app persisting the port.
        // A port proven closed owes nothing, so intent and any old marker both go. A live listener may
        // be one the app opened before it lost root, so the durable OFF marker stays armed and the next
        // reassert with root finishes the teardown; ownership is cleared either way so the switch reads
        // OFF, and the caller's success means the intent is off, not that the port is closed.
        val closed = inactiveReadback()
        if (!closed && !armDisable()) return false
        if (!clearOwnership() || owned()) return false
        if (!closed) return true
        if (disablePending() && !clearDisable()) return false
        return !disablePending()
    }
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

/** The diagnostics line. A persisted intent states whether adbd listens, so it never claims a port
 *  that a firmware or an adbd restart has closed. */
internal fun networkAdbDiagnostic(persisted: Boolean, active: Boolean?): String = when {
    persisted && active == true -> "persistent (5555) · listening"
    persisted && active == false -> "persistent (5555) · NOT listening; Panel Assistant re-asserts it when it next starts"
    persisted -> "persistent (5555) · listener unreadable"
    active == true -> "active (5555) · external — not persisted by Panel Assistant"
    active == null -> "unknown"
    else -> "off"
}

internal fun shouldReassertNetworkAdb(
    persisted: Boolean,
    disablePending: Boolean,
    rootAvailable: Boolean,
    listening: Boolean?,
): Boolean = persisted && !disablePending && rootAvailable && listening == false

/** A helper aggregate is read lazily once per observation and shared by both fixed read groups. */
internal fun observeNetworkAdbActiveState(
    directPropertyRead: (String) -> String?,
    directListenerRead: (String) -> String?,
    helperRead: () -> HelperAdbState?,
): Boolean? {
    val directProperties = networkAdbActiveState(directPropertyRead)
    val directListener = networkAdbListenerActiveState(directListenerRead)
    if (directProperties == true || directListener == true) return true
    val helper by lazy(helperRead)
    return remoteAdbActiveState(
        directProperties ?: helper?.properties,
        directListener ?: helper?.listener,
    )
}

/** Direct empty properties may be hidden by SELinux, so only the helper can confirm their absence. */
internal fun networkAdbActiveState(
    directRead: (String) -> String?,
    helperRead: () -> Boolean? = { null },
): Boolean? {
    val states = NETWORK_ADB_PROPERTIES.map { it.parse(directRead(it.name)) }
    if (states.any { it == true }) return true
    return if (states.all { it == false }) false else helperRead()
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
    helperRead: () -> Boolean? = { null },
): Boolean? {
    val states = NETWORK_ADB_TCP_INVENTORIES.map { parseTcpListenerInventory(directRead(it)) }
    if (states.any { it == true }) return true
    return if (states.all { it == false }) false else helperRead()
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

private val directAdbCommands = DirectAppCommands()

private fun runGetprop(vararg fixedArguments: String): String? =
    directAdbCommands.getprop(*fixedArguments)

private fun readTcpListenerInventoryDirect(path: String): String? {
    val fixedPath = NETWORK_ADB_TCP_INVENTORIES.firstOrNull { it == path } ?: return null
    return AccessDenialMemo.app.read(
        key = "file:$fixedPath",
        what = "Kernel socket table $fixedPath",
        probeDenied = { AccessDenialMemo.openDenied(fixedPath) },
    ) { directAdbCommands.tcpInventory(fixedPath) }
}

private const val PROPERTY_AREA_DIR = "/dev/__properties__"
private val PROPERTY_CONTEXT = Regex("u:object_r:[a-z0-9_]+:s0")
private const val NETWORK_ADB_DISABLE_MARKER_FILE = "network-adb-disable.v1"
private const val TCP_LISTEN_STATE = "0A"
private val NETWORK_ADB_TCP_INVENTORIES = listOf("/proc/net/tcp", "/proc/net/tcp6")
internal const val NETWORK_ADB_INITIAL_SETTLE_MS = 1_000L
internal const val NETWORK_ADB_SAMPLE_INTERVAL_MS = 500L
internal const val NETWORK_ADB_REQUIRED_STABLE_MS = 2_000L
// Five complete property/socket observations can take about eight seconds on vendor panels.
internal const val NETWORK_ADB_SETTLE_DEADLINE_MS = 10_000L
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
    fun parse(raw: String?): Boolean? {
        val value = raw?.trim() ?: return null
        if (value.isEmpty()) return null
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

private fun waitForNetworkAdb(delay: Long): Boolean = try {
    Thread.sleep(delay)
    true
} catch (_: InterruptedException) {
    Thread.currentThread().interrupt()
    false
}
