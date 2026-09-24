package io.github.maxlyth.hapaneld.control

import android.content.Context
import android.os.Build
import android.util.Log
import io.github.maxlyth.hapaneld.Config
import io.github.maxlyth.hapaneld.RendererResolver
import io.github.maxlyth.hapaneld.util.AndroidInput
import java.io.File
import java.net.ConnectException
import java.net.InetSocketAddress
import java.net.Socket

internal enum class RelayExposureState { PRESENT, ABSENT, UNKNOWN }

/** Truthful lifecycle state for the native relay: command acceptance is not process liveness. */
internal class RelayProcessState(
    private val probe: () -> RelayExposureState,
    private val stopProbe: () -> RelayExposureState = probe,
    private val pause: (Long) -> Unit = Thread::sleep,
) {
    @Synchronized fun start(launch: () -> Boolean): Boolean {
        if (!launch()) return false
        return probe() == RelayExposureState.PRESENT
    }

    /** UNKNOWN is exposed as running so a failed root/proc read can never hide an orphan. */
    @Synchronized fun running(): Boolean = probe() != RelayExposureState.ABSENT

    @Synchronized fun stop(terminate: () -> Unit): Boolean {
        terminate()
        repeat(STOP_VERIFY_ATTEMPTS) { attempt ->
            when (stopProbe()) {
                RelayExposureState.ABSENT -> return true
                RelayExposureState.UNKNOWN -> return false
                RelayExposureState.PRESENT -> if (attempt + 1 < STOP_VERIFY_ATTEMPTS) {
                    pause(STOP_VERIFY_DELAY_MS)
                }
            }
        }
        return false
    }

    companion object {
        private const val STOP_VERIFY_ATTEMPTS = 4
        private const val STOP_VERIFY_DELAY_MS = 50L
    }
}

internal enum class VerifiedRelayTransition { APPLIED, ACTION_FAILED, VERIFICATION_FAILED }

internal enum class LocalRelayListenerState { PRESENT, ABSENT, UNKNOWN }

/**
 * On-demand bridge that exposes the dashboard WebView's Chrome DevTools endpoint
 * (`@webview_devtools_remote_<pid>`) to the LAN on :[PORT], so a user can open chrome://inspect
 * against the panel with **no adb** — the step that's undocumented and makes people give up.
 *
 * Mechanics: a tiny native relay ([assets]/cdprelay-*) pumps `0.0.0.0:PORT` <-> the abstract socket.
 * It must run as root (an app can't connect to another app's abstract socket under SELinux; the su
 * domain can). We verified the WebView's CDP HTTP handler accepts an IP `Host`, so no proxying is
 * needed — a dumb byte-pump suffices and chrome://inspect rewrites nothing.
 *
 * Requires: WebView debugging enabled on the dashboard app (Companion → Settings → Troubleshooting →
 * "WebView remote debugging") and root. **Security:** while running, full DevTools (read + control)
 * is exposed to the whole LAN — so it is off by default, started on demand, and stoppable.
 */
object CdpRelay {
    private const val TAG = "ha-paneld/cdp"
    const val PORT = 9222
    private const val ROOT_DIR = "/data/local/.hapaneld-cdp"
    private const val BIN = "$ROOT_DIR/cdprelay"

    private val process = RelayProcessState(
        probe = ::probeStatusExposure,
        // Status may truthfully fall back to the fixed TCP listener on a non-root panel. A privileged
        // stop has a stronger contract: both the process table and listener table must be readable and
        // absent, so a failed root probe can never be mistaken for successful termination.
        stopProbe = ::probeExposure,
    )

    val running: Boolean @Synchronized get() = process.running()

    /** Resolve only the configured renderer's WebView socket. Picking the first global socket can
     * expose another app's DevTools endpoint when multiple debuggable WebViews are running. */
    private fun socketName(ctx: Context): String? {
        val pkg = relayRendererPackage(Config(ctx).dashboardPackage, ctx.packageName) ?: return null
        val pids = Su.runOutput("pidof $pkg")
            ?.trim()
            ?.split(Regex("\\s+"))
            ?.mapNotNull { it.toIntOrNull()?.takeIf { pid -> pid > 0 } }
            ?.toSet()
            .orEmpty()
        if (pids.isEmpty()) return null
        val sockets = Su.runOutput("cat /proc/net/unix") ?: return null
        return selectDevToolsSocket(sockets, pids)
    }

    private fun extractBinary(ctx: Context): File? {
        val abi = if (Build.SUPPORTED_ABIS.any { it == "arm64-v8a" }) "cdprelay-arm64" else "cdprelay-arm"
        return runCatching {
            val f = File(ctx.filesDir, "cdprelay")
            ctx.assets.open(abi).use { input -> f.outputStream().use { input.copyTo(it) } }
            f
        }.getOrElse { Log.w(TAG, "extract failed", it); null }
    }

    /** Status codes the HTTP layer maps to UI text. */
    fun start(ctx: Context): String = RemoteDebugSecurityTransitionGate.mutate {
        synchronized(this) {
            // A DevTools relay would allow its LAN client to drive an authenticated browser after the one
            // physical approval has been consumed. It is therefore incompatible with Hardened mode, and
            // this check shares the transition lock so a Relaxed-mode request cannot race mode entry.
            if (Config(ctx).hardenedSecurityEnabled) return@mutate "failed"
            if (!Su.available()) return@mutate "needs-root"
            val name = socketName(ctx) ?: return@mutate "no-socket" // WebView debugging not enabled
            val bin = extractBinary(ctx) ?: return@mutate "no-binary"
            if (!stopLocked()) return@mutate "failed" // clear and prove away any stale instance first
            val ok = process.start {
                // Give the child a short chance to bind and fail before probing; a successful background
                // shell launch alone cannot prove that the socket name or TCP port was usable.
                Su.run(startCommand(bin.absolutePath, name))
            }
            Log.i(TAG, "start relay ($name) -> $ok")
            if (ok) "started" else "failed"
        }
    }

    fun stop(): Boolean = RemoteDebugSecurityTransitionGate.mutate { synchronized(this) { stopLocked() } }

    /** Process exit needs proof that no relay is externally reachable, not proof that this app has never
     * staged the immutable source binary. Keep Hardened-mode admission conservative while allowing a
     * formerly rooted panel to restart after root disappears and repeated loopback probes refuse. */
    internal fun stopAndVerifyForProcessExit(): Boolean = RemoteDebugSecurityTransitionGate.mutate {
        synchronized(this) {
            if (Su.availableIsolated()) {
                stopLocked()
            } else {
                noRootRelayInactiveForProcessExit(
                    buildList {
                        repeat(NO_ROOT_LISTENER_PROBES) { attempt ->
                            add(probeLocalListener())
                            if (attempt + 1 < NO_ROOT_LISTENER_PROBES) {
                                Thread.sleep(NO_ROOT_LISTENER_PROBE_DELAY_MS)
                            }
                        }
                    },
                )
            }
        }
    }

    /** Guard DB has already committed an exact physical-approval epoch into its sentinel. From that
     * point shutdown may only prove relay absence; attempting a new stop would mutate that epoch after
     * approval. Any present/unknown exposure fails the clean handoff instead. */
    internal fun proveAbsentForGuardDbHandoff(): Boolean = RemoteDebugSecurityTransitionGate.withLock {
        synchronized(this) {
            if (Su.availableIsolated()) {
                probeExposure() == RelayExposureState.ABSENT
            } else {
                noRootRelayInactiveForProcessExit(
                    buildList {
                        repeat(NO_ROOT_LISTENER_PROBES) { attempt ->
                            add(probeLocalListener())
                            if (attempt + 1 < NO_ROOT_LISTENER_PROBES) {
                                Thread.sleep(NO_ROOT_LISTENER_PROBE_DELAY_MS)
                            }
                        }
                    },
                )
            }
        }
    }

    /** Keep verified relay absence and the security-mode commit in one lifecycle critical section. */
    internal fun stopAndVerifyThen(
        ctx: Context,
        action: () -> Boolean,
    ): VerifiedRelayTransition = RemoteDebugSecurityTransitionGate.mutate {
        synchronized(this) {
            val verifiedAbsent = if (Su.availableIsolated()) {
                stopLocked()
            } else {
            // A never-rooted panel cannot have launched this root-owned relay. Do not require root merely
            // to enable Hardened there, but retain two independent facts: no prior extraction attempt in
            // this app data and repeated connection-refused results from the relay's fixed loopback port.
            noRootRelayAbsent(
                priorRelayArtifact = File(ctx.filesDir, "cdprelay").exists(),
                listenerProbes = buildList {
                    repeat(NO_ROOT_LISTENER_PROBES) { attempt ->
                        add(probeLocalListener())
                        if (attempt + 1 < NO_ROOT_LISTENER_PROBES) {
                            Thread.sleep(NO_ROOT_LISTENER_PROBE_DELAY_MS)
                        }
                    }
                },
            )
            }
            if (!verifiedAbsent) return@mutate VerifiedRelayTransition.VERIFICATION_FAILED
            if (action()) VerifiedRelayTransition.APPLIED else VerifiedRelayTransition.ACTION_FAILED
        }
    }

    private fun stopLocked(): Boolean = process.stop {
        // pkill returns non-zero when there was nothing to kill. That is not failure; the bounded
        // process/listener probe below is the authority. SIGKILL also closes a wedged relay promptly.
        Su.runOutputIsolatedBounded(
            "pkill -9 -f $BIN 2>/dev/null || true; echo stopped",
            maxBytes = MAX_RELAY_STOP_BYTES,
            timeoutMs = RELAY_STOP_TIMEOUT_MS,
        )
    }

    private fun probeExposure(): RelayExposureState = relayExposureState(
        Su.runOutputIsolatedBounded(
            relayProbeCommand(),
            maxBytes = MAX_RELAY_PROBE_BYTES,
            timeoutMs = RELAY_PROBE_TIMEOUT_MS,
        ),
    )

    private fun probeStatusExposure(): RelayExposureState {
        val privileged = probeExposure()
        if (privileged != RelayExposureState.UNKNOWN) return privileged
        return relayStatusExposure(privileged, probeLocalListener())
    }

    private fun probeLocalListener(): LocalRelayListenerState = try {
        Socket().use { socket ->
            socket.connect(InetSocketAddress("127.0.0.1", PORT), LOCAL_CONNECT_TIMEOUT_MS)
        }
        LocalRelayListenerState.PRESENT
    } catch (_: ConnectException) {
        LocalRelayListenerState.ABSENT
    } catch (_: Exception) {
        LocalRelayListenerState.UNKNOWN
    }

    /** Atomically stage below root-controlled /data/local. `/data/local/tmp` is shell-owned, so even a
     * root-owned child there could be renamed and replaced by a Shizuku-capable shell peer. */
    internal fun startCommand(source: String, socketName: String): String =
        "rm -rf $ROOT_DIR && mkdir -m 700 $ROOT_DIR && chown 0:0 $ROOT_DIR && " +
            "cp $source $BIN.new && chown 0:0 $BIN.new && chmod 755 $BIN.new && mv -f $BIN.new $BIN && " +
            "( $BIN $PORT $socketName >/dev/null 2>&1 & ) && sleep 1"

    /** The package whose WebView the relay exposes. Auto (blank) and the built-in sentinel are this app's
     * own renderer; anything else must be a well-formed package name. */
    internal fun relayRendererPackage(configured: String, ownPackage: String): String? {
        val pkg = if (RendererResolver.isBuiltinSelection(configured, ownPackage)) ownPackage else configured
        return pkg.takeIf(AndroidInput::isPackage)
    }

    internal fun selectDevToolsSocket(unixSockets: String, rendererPids: Set<Int>): String? =
        Regex("(?:^|[^A-Za-z0-9_])(webview_devtools_remote_([0-9]+))(?:$|[^0-9])")
            .findAll(unixSockets)
            .mapNotNull { match ->
                match.groupValues[2].toIntOrNull()
                    ?.takeIf(rendererPids::contains)
                    ?.let { match.groupValues[1] }
            }
            .distinct()
            .singleOrNull()

    internal fun relayProbeCommand(): String =
        "found=0; for comm in /proc/[0-9]*/comm; do " +
            // A fork per process can exhaust the bounded probe before it reaches the listener tables.
            // A process disappearing during the scan is harmless; an unreadable live entry is not proof.
            "[ -e \"\$comm\" ] || continue; " +
            "IFS= read -r process_name < \"\$comm\" 2>/dev/null || " +
            "{ [ ! -e \"\$comm\" ] && continue; exit 1; }; " +
            "[ \"\$process_name\" = cdprelay ] && found=1 && break; done; " +
            "echo process=\$found; echo tcp_begin; " +
            "[ -r /proc/net/tcp ] || exit 1; cat /proc/net/tcp || exit 1; " +
            "if [ -e /proc/net/tcp6 ]; then [ -r /proc/net/tcp6 ] || exit 1; " +
            "cat /proc/net/tcp6 || exit 1; fi; echo tcp_end"

    private const val MAX_RELAY_PROBE_BYTES = 64 * 1024L
    private const val MAX_RELAY_STOP_BYTES = 32L
    private const val RELAY_PROBE_TIMEOUT_MS = 2_000L
    private const val RELAY_STOP_TIMEOUT_MS = 2_000L
    private const val LOCAL_CONNECT_TIMEOUT_MS = 200
    private const val NO_ROOT_LISTENER_PROBES = 2
    private const val NO_ROOT_LISTENER_PROBE_DELAY_MS = 50L
}

/** A no-root proof is valid only for a panel with no evidence this app ever staged the relay. */
internal fun noRootRelayAbsent(
    priorRelayArtifact: Boolean,
    listenerProbes: List<LocalRelayListenerState>,
): Boolean = !priorRelayArtifact && listenerProbes.size >= 2 &&
    listenerProbes.all { it == LocalRelayListenerState.ABSENT }

/** A surviving native relay binds before its long-lived pump loop, so repeated connection refusal is
 * sufficient process-exit evidence even when an old app-private extraction artifact remains. */
internal fun noRootRelayInactiveForProcessExit(
    listenerProbes: List<LocalRelayListenerState>,
): Boolean = listenerProbes.size >= 2 && listenerProbes.all { it == LocalRelayListenerState.ABSENT }

/** Status may use the fixed local listener when a non-root panel cannot inspect global `/proc`. */
internal fun relayStatusExposure(
    privileged: RelayExposureState,
    localListener: LocalRelayListenerState,
): RelayExposureState = when {
    privileged != RelayExposureState.UNKNOWN -> privileged
    localListener == LocalRelayListenerState.PRESENT -> RelayExposureState.PRESENT
    localListener == LocalRelayListenerState.ABSENT -> RelayExposureState.ABSENT
    else -> RelayExposureState.UNKNOWN
}

/** Parse one bounded root snapshot of both the process table and kernel TCP listener tables. */
internal fun relayExposureState(output: String?): RelayExposureState {
    val text = output ?: return RelayExposureState.UNKNOWN
    val lines = text.lineSequence().map(String::trim).toList()
    val processLine = lines.singleOrNull { it.startsWith("process=") }
        ?: return RelayExposureState.UNKNOWN
    val processPresent = when (processLine) {
        "process=1" -> true
        "process=0" -> false
        else -> return RelayExposureState.UNKNOWN
    }
    val tcpStart = lines.indexOf("tcp_begin")
    val tcpEnd = lines.indexOf("tcp_end")
    if (tcpStart < 0 || tcpEnd <= tcpStart) return RelayExposureState.UNKNOWN

    var listenerPresent = false
    for (line in lines.subList(tcpStart + 1, tcpEnd)) {
        if (line.isEmpty() || line.startsWith("sl")) continue
        val fields = line.split(Regex("\\s+"))
        if (fields.size < 4) return RelayExposureState.UNKNOWN
        val port = fields[1].substringAfterLast(':', missingDelimiterValue = "")
            .toIntOrNull(16) ?: return RelayExposureState.UNKNOWN
        if (fields[3].equals("0A", ignoreCase = true) && port == CdpRelay.PORT) {
            listenerPresent = true
        }
    }
    return if (processPresent || listenerPresent) RelayExposureState.PRESENT else RelayExposureState.ABSENT
}
