package io.panelassistant.android.http

import io.ktor.server.application.Application
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import java.net.BindException

/**
 * The address the `:8888` listener binds (protocol section 20.7): loopback only when [offLan] (hardened mode
 * with the REST routes closed, section 20.8), where the panel's own WebView still signs in through it, else
 * the IPv6 wildcard, which on Android is dual-stack (net.ipv6.bindv6only=0) and so answers IPv4 too.
 */
internal fun listenerHost(offLan: Boolean): String = if (offLan) "127.0.0.1" else "::"

/** Whether the listener is off the LAN: hardened mode with the REST routes closed (sections 20.7 and 20.8). */
internal fun listenerOffLan(config: io.panelassistant.android.Config): Boolean =
    config.hardenedSecurityEnabled && config.uiRoutesClosed

/**
 * The listener's engine, one generation at a time, bound for whether it was off the LAN when started.
 * [rebind] replaces the engine when that has moved since, so hardened mode with the routes closed takes the
 * stub off the LAN without restarting the service.
 */
internal class PaneldListener(
    private val scope: CoroutineScope,
    private val port: () -> Int,
    private val hardened: () -> Boolean,
    /** A bind that failed (thrown, or raised inside the engine); a rebind keeps trying. */
    private val onBindFailure: (Throwable) -> Unit = {},
    private val onBound: (Int) -> Unit = {},
    private val retryInitialMs: Long = 500,
    private val retryMaxMs: Long = 30_000,
    private val module: Application.() -> Unit,
) {
    private var stopEngine: (() -> Unit)? = null
    /** The mode the engine is bound for; null while no engine is bound. */
    private var boundHardened: Boolean? = null
    private var started = false
    @Volatile private var stopped = false

    /** Bind now; a failure releases the engine, runs [closeIngress] and propagates. */
    @Synchronized
    fun start(closeIngress: () -> Unit) {
        bind(closeIngress)
        started = true
    }

    private var boundPort: Int? = null

    private fun bind(closeIngress: () -> Unit) {
        val mode = hardened()
        val preferred = boundPort ?: port()
        // Bind the real engine directly: probing with a temporary socket leaves a reservation race.
        val candidates = sequence {
            yield(preferred)
            val first = if (preferred in 1024 until 32767) preferred + 1 else 1024
            for (offset in 0 until (32768 - 1024)) {
                val candidate = 1024 + (first - 1024 + offset) % (32768 - 1024)
                if (candidate != preferred) yield(candidate)
            }
        }
        try {
            var lastFailure: Exception? = null
            for (candidate in candidates) {
                // A failed CIO bind must not cancel the service scope or the next engine attempt.
                val engineJob = SupervisorJob(scope.coroutineContext[Job])
                val engineScope = CoroutineScope(scope.coroutineContext + engineJob +
                    CoroutineExceptionHandler { _, failure -> onBindFailure(failure) })
                try {
                    val server = engineScope.embeddedServer(CIO, port = candidate, host = listenerHost(mode)) { module() }
                    try {
                        server.start(wait = false)
                        // CIO may return from start before its socket bind has finished.
                        val actualPort = runBlocking { server.engine.resolvedConnectors() }.single().port
                        onBound(actualPort)
                        stopEngine = { try { server.stop(500, 1500) } finally { engineJob.cancel() } }
                        boundPort = actualPort
                        boundHardened = mode
                        return
                    } catch (failure: Exception) {
                        runCatching { server.stop(0, 0) }
                        throw failure
                    }
                } catch (failure: Exception) {
                    engineJob.cancel()
                    // Failed coroutine jobs can wrap the socket exception in cancellation.
                    if (generateSequence<Throwable>(failure) { it.cause }.none { it is BindException }) throw failure
                    lastFailure = failure
                }
            }
            throw checkNotNull(lastFailure)
        } catch (failure: Exception) {
            runCatching(closeIngress)
            throw failure
        }
    }

    /**
     * Bind again when the security mode moved; true once bound for the new mode, false when nothing needed
     * doing or [stop] ran first. Without a listener the panel's own WebView cannot sign in, so a bind that fails
     * (the old socket may hold the port for a while) is retried with backoff until it succeeds; meanwhile the
     * listener records itself unbound. Blocks its caller, which runs it off the main thread.
     */
    fun rebind(): Boolean {
        synchronized(this) {
            if (stopped || !started) return false
            if (stopEngine != null && boundHardened == hardened()) return false
            stopEngine?.invoke()
            stopEngine = null
            boundHardened = null
        }
        var waitMs = retryInitialMs
        while (true) {
            synchronized(this) {
                if (stopped) return false
                if (stopEngine != null) return true
                try {
                    bind(closeIngress = {})
                    return true
                } catch (failure: Exception) {
                    onBindFailure(failure)
                }
            }
            Thread.sleep(waitMs)
            waitMs = minOf(waitMs * 2, retryMaxMs)
        }
    }

    /** Whether an engine is bound now. */
    @Synchronized
    fun bound(): Boolean = stopEngine != null

    @Synchronized
    fun stop() {
        stopped = true
        stopEngine?.invoke()
        stopEngine = null
        boundHardened = null
    }
}

/**
 * Do not admit a new best-effort startup probe once HTTP teardown has begun. The volatile admission
 * read is the phase boundary: an already-admitted read-only phase may finish while teardown closes
 * HTTP, then the service scope's cancellation/join owns its bounded drain.
 */
internal fun runPrewarmPhases(
    isStopping: () -> Boolean,
    management: () -> Unit,
    companion: () -> Unit,
) {
    if (isStopping()) return
    management()
    if (isStopping()) return
    companion()
}

/** Close every HTTP-owned admission/resource and retain any ambiguous result while continuing the sweep. */
internal fun stopHttpOwners(
    closeOperationAdmission: () -> Unit,
    closeUploadIngress: () -> Unit,
    stopEngine: () -> Unit,
    stopRelay: () -> Boolean,
    drainTameMutations: () -> Boolean,
    drainRemoteControls: () -> Boolean,
    onIncomplete: (step: String, error: Throwable?) -> Unit,
): Boolean {
    var complete = true
    fun prove(step: String, action: () -> Boolean) {
        val result = runCatching(action)
        if (result.getOrDefault(false)) return
        complete = false
        runCatching { onIncomplete(step, result.exceptionOrNull()) }
    }

    prove("clear-storage admission") { closeOperationAdmission(); true }
    prove("pending uploads") { closeUploadIngress(); true }
    prove("HTTP engine stop request") { stopEngine(); true }
    prove("CDP relay", stopRelay)
    prove("vendor mutation", drainTameMutations)
    prove("remote control", drainRemoteControls)
    return complete
}
