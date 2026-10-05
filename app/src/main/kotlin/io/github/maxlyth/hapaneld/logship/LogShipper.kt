package io.github.maxlyth.hapaneld.logship

import android.util.Log
import io.panelassistant.android.BuildConfig
import io.github.maxlyth.hapaneld.Config
import io.github.maxlyth.hapaneld.metrics.FeatureCostOperation
import io.github.maxlyth.hapaneld.metrics.FeatureCostOutcome
import io.github.maxlyth.hapaneld.metrics.FeatureCostRegistry
import io.github.maxlyth.hapaneld.metrics.FeatureCosts
import io.github.maxlyth.hapaneld.util.Json
import io.github.maxlyth.hapaneld.util.BoundedDns
import io.github.maxlyth.hapaneld.util.LogShipEndpoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/** Immutable view of the preferences relevant to one log-shipping decision. */
internal data class LogShipConfigSnapshot(
    val enabled: Boolean,
    val host: String,
    val port: Int,
    val protocol: String,
    val panelId: String,
    val systemEnabled: Boolean = false,
) {
    fun targetOrNull(): LogShipTarget? {
        if (!enabled || host.isBlank()) return null
        val endpoint = LogShipEndpoint.resolve(host, port, protocol)
        if (endpoint.host.isBlank()) return null
        return LogShipTarget(endpoint.host, endpoint.port, endpoint.protocol, panelId, systemEnabled)
    }
}

/** A target belongs to one run; live preference reads never change a run underneath its worker. */
internal data class LogShipTarget(
    val host: String, val port: Int, val protocol: String, val panelId: String,
    val systemEnabled: Boolean = false,
)

/** Dedicated, cheap control-plane projection of the synchronized shipper generation. */
internal data class LogShipStatusProjection(
    val enabled: Boolean,
    val configured: Boolean,
    val text: String,
)

/** Status text for a feature that is switched off — the one value the dashboard suppresses. */
internal const val LOG_SHIP_STATUS_OFF = "off"

/** Matches an HTTP status in a transport error so the code alone can be lifted out of the message. */
private val HTTP_STATUS = Regex("""http[ -](\d{3})""")

internal fun logShipTrafficText(sent: Long, dropped: Long): String =
    "$sent ${if (sent == 1L) "record" else "records"} sent" +
        if (dropped > 0) " · $dropped dropped" else ""

/** A transport is created inert, attached to its run, and only then allowed to block in [connect]. */
internal interface LogSink : AutoCloseable {
    fun connect()
    fun send(lines: List<String>)
}

internal fun interface LogSinkFactory {
    fun create(target: LogShipTarget, encode: (String) -> String): LogSink
}

/**
 * Everything mutable for one shipping generation. Its queue, counters, capture subscription, worker,
 * and blocking transport cannot be observed or consumed by a replacement generation.
 */
internal class LogShipRun(
    val target: LogShipTarget,
    queueCapacity: Int,
    private val onDropped: ((Long) -> Unit)? = null,
    private val nowNs: () -> Long = System::nanoTime,
) : AutoCloseable {
    data class Status(val connected: Boolean, val sent: Long, val dropped: Long, val lastError: String?)

    private val queue = ArrayBlockingQueue<Any>(queueCapacity)
    private val sent = AtomicLong()
    private val dropped = AtomicLong()
    @Volatile private var open = true
    @Volatile private var connected = false
    @Volatile private var lastError: String? = null
    private var subscription: AutoCloseable? = null
    private var job: Job? = null
    private var sink: LogSink? = null
    private var systemTokens = SYSTEM_BURST_BYTES
    private var lastSystemRefillNs = nowNs()

    fun isOpen(): Boolean = open

    fun bindSubscription(candidate: AutoCloseable) = synchronized(this) {
        if (open) subscription = candidate else runCatching { candidate.close() }
    }

    fun bindJob(candidate: Job) = synchronized(this) {
        if (open) job = candidate else candidate.cancel()
    }

    fun attachSink(candidate: LogSink): Boolean = synchronized(this) {
        if (!open) false else {
            sink = candidate
            true
        }
    }

    fun detachSink(candidate: LogSink) = synchronized(this) {
        if (sink === candidate) sink = null
    }

    /** Bounded, drop-oldest admission. A closed generation rejects lines from a late capture callback. */
    fun offer(line: String) {
        if (!open) return
        if (!queue.offer(line)) {
            queue.poll()
            dropped.incrementAndGet()
            onDropped?.invoke(1)
            queue.offer(line)
        }
    }

    /** Admit complete Android entries only; a burst absorbs stack traces without a physical-line cap. */
    fun offerSystem(entry: String) {
        if (!open) return
        val bytes = boundedUtf8Bytes(entry, SYSTEM_ENTRY_MAX_BYTES + 1L)
        val admitted = synchronized(this) {
            val now = nowNs()
            val elapsed = (now - lastSystemRefillNs).coerceIn(0L, SYSTEM_REFILL_NS)
            systemTokens = (systemTokens + elapsed * SYSTEM_RATE_BYTES / 1_000_000_000L)
                .coerceAtMost(SYSTEM_BURST_BYTES)
            lastSystemRefillNs = now
            if (bytes > SYSTEM_ENTRY_MAX_BYTES || bytes > systemTokens) false
            else { systemTokens -= bytes; true }
        }
        if (admitted) offer(entry) else recordDropped(1)
    }

    /** A close wake-up is deliberately not a String, so it can never collide with captured content. */
    fun takeBatch(max: Int, timeoutSeconds: Long): List<String> {
        val first = queue.poll(timeoutSeconds, TimeUnit.SECONDS) as? String ?: return emptyList()
        val out = ArrayList<String>(max)
        out.add(first)
        while (out.size < max) {
            val next = queue.poll() ?: break
            if (next is String) out.add(next)
        }
        return out
    }

    fun markConnected() {
        if (!open) return
        connected = true
        lastError = null
    }

    fun markFailure(reason: String) {
        if (!open) return
        connected = false
        lastError = reason
    }

    fun recordSent(count: Int) {
        if (count > 0) sent.addAndGet(count.toLong())
    }

    fun recordDropped(count: Int) {
        if (count > 0) {
            dropped.addAndGet(count.toLong())
            onDropped?.invoke(count.toLong())
        }
    }

    fun status(): Status = Status(connected, sent.get(), dropped.get(), lastError)

    override fun close() {
        val resources = synchronized(this) {
            if (!open) return
            open = false
            connected = false
            queue.clear()
            queue.offer(WAKE)
            val captured = Triple(subscription, job, sink)
            subscription = null
            job = null
            sink = null
            captured
        }
        resources.second?.cancel()
        runCatching { resources.first?.close() }
        runCatching { resources.third?.close() }
    }

    private companion object {
        val WAKE = Any()
        const val SYSTEM_ENTRY_MAX_BYTES = 8L * 1024
        const val SYSTEM_RATE_BYTES = 128L * 1024
        const val SYSTEM_BURST_BYTES = 512L * 1024
        const val SYSTEM_REFILL_NS = 4_000_000_000L
    }
}

/**
 * Optional remote log shipping. Streams ha-paneld's own redacted process log to a configured syslog or
 * HTTP/NDJSON collector. Capture is demand-driven and the sink queue is bounded, so this best-effort
 * telemetry cannot back-pressure the app or grow without limit.
 *
 * Each start owns a complete [LogShipRun]. Reconfiguration closes the old capture subscription and any
 * blocking transport before creating the replacement, while late callbacks are rejected by the closed run.
 */
class LogShipper internal constructor(
    private val configSnapshot: () -> LogShipConfigSnapshot,
    private val scope: CoroutineScope,
    private val subscribeCapture: ((String) -> Unit) -> AutoCloseable,
    private val subscribeSystem: (((String) -> Unit) -> AutoCloseable)? = null,
    private val sinkFactory: LogSinkFactory,
    private val featureCosts: FeatureCostRegistry = FeatureCosts.registry,
    private val build: LogShipRecord.Build = LogShipRecord.Build.CURRENT,
) {
    /** Ships [capture] and, when given, the dashboard [console] source through the same run. */
    constructor(
        config: Config, scope: CoroutineScope, capture: LogCapture,
        console: LogCapture? = null, system: LogCapture? = null,
    ) : this(
        configSnapshot = {
            LogShipConfigSnapshot(
                enabled = config.logShipEnabled,
                host = config.logShipHost,
                port = config.logShipPort,
                protocol = config.logShipProtocol,
                panelId = config.panelId,
                systemEnabled = config.logShipSystemEnabled,
            )
        },
        scope = scope,
        subscribeCapture = if (console == null) capture::subscribe else subscribeAll(capture, console),
        subscribeSystem = system?.let { it::subscribe },
        sinkFactory = NetworkLogSinkFactory,
    )

    private val lock = Any()
    private var run: LogShipRun? = null
    private val rfc3339 = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
        .apply { timeZone = TimeZone.getTimeZone("UTC") }

    /** Start shipping if enabled and a host is set. Idempotent and serialized with reconfiguration. */
    fun start() = synchronized(lock) {
        if (run != null) return@synchronized
        val target = configSnapshot().targetOrNull()
        if (target == null) {
            Log.i(TAG, "disabled (no sink configured)")
            return@synchronized
        }
        startLocked(target)
    }

    /** Replace the complete run when enablement, target, protocol, or panel identity changes. */
    fun reconfigure() = synchronized(lock) {
        val target = configSnapshot().targetOrNull()
        if (run?.target == target || (run == null && target == null)) return@synchronized
        run?.close()
        run = null
        if (target != null) startLocked(target)
    }

    /** Terminally detach and close the current generation. Idempotent. */
    fun stop() = synchronized(lock) {
        run?.close()
        run = null
    }

    /** Live state from the current generation, serialized with start/reconfigure/stop. */
    internal fun status(): LogShipStatusProjection = synchronized(lock) {
        val config = configSnapshot()
        val current = run
        val configured = config.enabled && config.host.isNotBlank()
        if (current == null && !config.enabled) {
            return@synchronized LogShipStatusProjection(false, false, LOG_SHIP_STATUS_OFF)
        }
        if (current == null && config.host.isBlank()) {
            return@synchronized LogShipStatusProjection(config.enabled, false, "enabled · no host set")
        }
        val target = current?.target ?: config.targetOrNull()
            ?: return@synchronized LogShipStatusProjection(config.enabled, configured, "disconnected")
        val status = current?.status() ?: LogShipRun.Status(false, 0, 0, null)
        // The destination is deliberately absent. The settings state where logs go; this line states
        // only what they cannot — whether the sink is accepting anything, and how much has gone. A
        // scheme or port embedded in log_ship_host can override the separate Protocol and Port fields,
        // so repeating either the configured fields or resolved target here would be misleading.
        // "Records" is spelled out because one multiline exception is one sink message.
        LogShipStatusProjection(
            enabled = config.enabled,
            configured = configured,
            text = liveness(target, status) +
                " · ${logShipTrafficText(status.sent, status.dropped)}" +
                if (target.systemEnabled && target.protocol == LogShipEndpoint.SYSLOG_UDP) {
                    " · system logs need TCP or HTTP"
                } else "",
        )
    }

    /** One-line compatibility projection for the info page and diagnostics. */
    fun statusText(): String = status().text

    /**
     * UDP has no connection and no acknowledgement, so it must never claim to be "connected" — that
     * word would assert a delivery guarantee the transport cannot make, and a silently-black-holed
     * sink would read exactly like a healthy one.
     */
    private fun liveness(target: LogShipTarget, status: LogShipRun.Status): String {
        if (!status.connected) return "disconnected${status.lastError?.let { " (${shortReason(it)})" } ?: ""}"
        if (target.protocol != LogShipEndpoint.SYSLOG_UDP) return "connected"
        return if (status.sent > 0) "sending (UDP is unacknowledged)" else "ready (UDP is unacknowledged)"
    }

    /**
     * A short, destination-free reason for the status line.
     *
     * The raw transport message names the address it failed to reach — `failed to connect to
     * /192.0.2.118 (port 514) …` — which would put the destination straight back into a status line
     * that deliberately omits it. Mapping to a small fixed vocabulary cannot leak one by construction,
     * and reads better than a raw exception string. The full message is still logged, and the Logs tab
     * still shows it.
     */
    private fun shortReason(reason: String): String {
        val r = reason.lowercase(Locale.ROOT)
        return when {
            "unknownhost" in r || "not resolve" in r || "no address" in r -> "host not found"
            "refused" in r -> "connection refused"
            "timed out" in r || "timeout" in r -> "timed out"
            "unreachable" in r -> "network unreachable"
            // Only the matched status token is emitted — never the surrounding message. An HTTP failure
            // routinely quotes the request URL, and a sink URL may carry userinfo credentials, so
            // returning the raw text here would defeat the whole point of a destination-free status.
            else -> HTTP_STATUS.find(r)?.let { "HTTP ${it.groupValues[1]}" } ?: "send failed"
        }
    }

    private fun startLocked(target: LogShipTarget) {
        val onDropped: ((Long) -> Unit)? = if (featureCosts.recordingEnabled) {
            { dropped -> featureCosts.recordDropped(FeatureCostOperation.LOG_SHIP_BATCH, dropped) }
        } else {
            null
        }
        val candidate = LogShipRun(target, if (target.systemEnabled) SYSTEM_QUEUE_CAP else QUEUE_CAP, onDropped)
        run = candidate
        try {
            val ordinary = subscribeCapture(candidate::offer)
            val system = try {
                if (target.systemEnabled && target.protocol != LogShipEndpoint.SYSLOG_UDP) {
                    subscribeSystem?.invoke(candidate::offerSystem)
                } else null
            } catch (e: Exception) {
                ordinary.close()
                throw e
            }
            candidate.bindSubscription(AutoCloseable {
                runCatching { system?.close() }
                ordinary.close()
            })
            candidate.bindJob(scope.launch(Dispatchers.IO) { shipLoop(candidate) })
            Log.i(
                TAG,
                "started → ${LogShipEndpoint.scheme(target.protocol)}://" +
                    "${LogShipEndpoint.displayHost(target.host)}:${target.port}",
            )
        } catch (e: Exception) {
            run = null
            candidate.close()
            Log.w(TAG, "start failed: ${e.message}")
        }
    }

    private suspend fun CoroutineScope.shipLoop(run: LogShipRun) {
        val target = run.target
        // Batching is a property of the transport, not of whether cost metrics happen to be recording.
        // Draining what is already queued never adds latency — the poll after the first line does not
        // block — so there is no reason for the two to have ever been coupled.
        val batchMax = BATCH_MAX
        val encode: (String) -> String = if (target.protocol == LogShipEndpoint.HTTP) {
            { line -> jsonEvent(line, target.panelId) }
        } else {
            { line -> syslogFrame(line, target.panelId) }
        }
        while (isActive && run.isOpen()) {
            var sink: LogSink? = null
            try {
                val candidate = sinkFactory.create(target, encode)
                if (!run.attachSink(candidate)) {
                    candidate.close()
                    return
                }
                sink = candidate
                candidate.connect()
                // Syslog has no application handshake, so a completed connect (or, for UDP, a
                // successful name resolution) is the only positive signal the transport ever gives.
                if (target.protocol != LogShipEndpoint.HTTP) run.markConnected()
                while (isActive && run.isOpen()) {
                    val batch = run.takeBatch(batchMax, POLL_SECONDS)
                    if (batch.isEmpty()) continue
                    val cost = featureCosts.beginSynchronous(FeatureCostOperation.LOG_SHIP_BATCH)
                    var outcome = FeatureCostOutcome.SUCCESS
                    try {
                        candidate.send(batch)
                        run.recordSent(batch.size)
                        run.markConnected()
                    } catch (e: Exception) {
                        outcome = FeatureCostOutcome.FAILURE
                        // The batch left the bounded queue and will not be retried, so account for its loss.
                        run.recordDropped(batch.size)
                        throw e
                    } finally {
                        featureCosts.finishSynchronous(
                            FeatureCostOperation.LOG_SHIP_BATCH,
                            cost,
                            outcome = outcome,
                            workUnits = batch.size.toLong(),
                            workBytes = if (featureCosts.recordingEnabled) {
                                boundedUtf8Bytes(batch, SHIP_WORK_BYTES_MAX)
                            } else {
                                0L
                            },
                        )
                    }
                }
            } catch (e: Exception) {
                // Closing a run deliberately breaks its transport. Do not leak that expected exception
                // into a replacement generation's newly captured log stream.
                if (run.isOpen()) {
                    val reason = describeFailure(e, target.host)
                    run.markFailure(reason)
                    Log.w(
                        TAG,
                        "${LogShipEndpoint.scheme(target.protocol)} " +
                            "${LogShipEndpoint.displayHost(target.host)}:${target.port}: $reason",
                    )
                    if (isActive) delay(SINK_BACKOFF_MS)
                }
            } finally {
                sink?.let {
                    run.detachSink(it)
                    runCatching { it.close() }
                }
            }
        }
    }

    /**
     * A failure reason a reader can act on. `Socket.connect` on an unresolved address throws
     * `UnknownHostException` whose message *is* the hostname, so the warning used to render as
     * "syslog collector:514: collector" — it named the destination and no fault whatsoever. Any
     * message that uninformative is replaced by the exception type.
     */
    private fun describeFailure(e: Throwable, host: String): String {
        val message = e.message?.trim().orEmpty()
        if (e is UnknownHostException || message.startsWith("$host:")) {
            val detail = LogShipEndpoint.displayFailure(message, host)
            return "${e.javaClass.simpleName}${detail.takeIf { it.isNotBlank() }?.let { " ($it)" } ?: ""}"
        }
        if (message.isNotEmpty() && !message.equals(host, ignoreCase = true)) {
            return LogShipEndpoint.displayFailure(message, host)
        }
        return "${e.javaClass.simpleName} (${LogShipEndpoint.displayHost(host)})"
    }

    private fun timestamp(): String = synchronized(rfc3339) { rfc3339.format(Date()) }

    private fun syslogFrame(line: String, panelId: String): String =
        LogShipRecord.syslogFrame(FACILITY_USER * 8 + severityOf(line), timestamp(), panelId, build, line)

    private fun jsonEvent(line: String, panelId: String): String =
        LogShipRecord.jsonEvent(timestamp(), panelId, build, line)

    private fun severityOf(line: String): Int {
        val level = (LEVEL_RE.find(line) ?: LONG_LEVEL_RE.find(line))?.groupValues?.get(1) ?: return SEV_INFO
        return when (level) {
            "V", "D" -> SEV_DEBUG
            "W" -> SEV_WARNING
            "E" -> SEV_ERROR
            "F" -> SEV_CRIT
            else -> SEV_INFO
        }
    }

    companion object {
        private const val TAG = "ha-paneld/logship"
        private const val QUEUE_CAP = 4000
        private const val SYSTEM_QUEUE_CAP = 1000
        private const val BATCH_MAX = 200
        private const val SHIP_WORK_BYTES_MAX = 4L * 1024 * 1024
        private const val POLL_SECONDS = 5L
        private const val SINK_BACKOFF_MS = 5_000L
        internal const val NETWORK_TIMEOUT_MS = 5_000
        internal const val DNS_TIMEOUT_MS = 2_000L

        /**
         * RFC5426 obliges a receiver to accept only 480 bytes and recommends 2048; 1024 stays well
         * inside any Ethernet path MTU so a long line is truncated here rather than silently dropped
         * by a middlebox that will not fragment it.
         */
        internal const val UDP_DATAGRAM_MAX_BYTES = 1024

        private const val FACILITY_USER = 1
        private const val SEV_CRIT = 2
        private const val SEV_ERROR = 3
        private const val SEV_WARNING = 4
        private const val SEV_INFO = 6
        private const val SEV_DEBUG = 7

        private val LEVEL_RE =
            Regex("""^\d\d-\d\d \d\d:\d\d:\d\d\.\d{3}\s+\d+\s+\d+\s+([VDIWEF])\s""")
        private val LONG_LEVEL_RE = Regex("""^\[\s*\S+\s+\d+:\s*\d+\s+([VDIWEF])/""")

    }
}

/**
 * The one definition of a shipped record's wire shape, shared by [LogShipper] and the sink probe.
 *
 * Every record names the build that wrote it, because panels sharing one collector can run different
 * builds and package names, and a log line is otherwise unattributable to a build. Syslog carries it as an RFC5424
 * STRUCTURED-DATA element, so a collector that ignores structured data sees the same MSG as before;
 * NDJSON carries it as two more keys.
 */
internal object LogShipRecord {
    const val APP = "ha-paneld"

    /**
     * RFC5424 SD-ID. A private SD-ID must be `name@<IANA enterprise number>`; the project has none, so
     * this uses 32473, the number RFC5612 reserves for documentation and examples. Vector's syslog
     * parser surfaces the params as `hapaneld@32473.versionCode` and `hapaneld@32473.package`.
     */
    const val SD_ID = "hapaneld@32473"

    data class Build(val versionCode: Int, val packageName: String) {
        companion object {
            val CURRENT = Build(BuildConfig.VERSION_CODE, BuildConfig.APPLICATION_ID)
        }
    }

    /** RFC5424 frame: `<PRI>1 TIMESTAMP HOSTNAME APP-NAME PROCID MSGID SD MSG`, newline-terminated. */
    fun syslogFrame(pri: Int, timestamp: String, panelId: String, build: Build, msg: String): String =
        "<$pri>1 $timestamp ${hostname(panelId)} $APP - - ${structuredData(build)} " +
            (if ('\n' in msg || '\r' in msg) msg.replace("\\", "\\\\").replace("\r", "\\r").replace("\n", "\\n") else msg) + "\n"

    fun jsonEvent(timestamp: String, panelId: String, build: Build, msg: String): String =
        "{\"timestamp\":\"$timestamp\",\"host\":${Json.str(panelId.ifBlank { "panel" })}," +
            "\"app\":\"$APP\",\"versionCode\":${build.versionCode},\"package\":${Json.str(build.packageName)}," +
            "\"message\":${Json.str(msg)}}"

    fun structuredData(build: Build): String =
        "[$SD_ID versionCode=\"${build.versionCode}\" package=\"${paramValue(build.packageName)}\"]"

    /** RFC5424 §6.3.3: inside PARAM-VALUE, `"`, `\` and `]` MUST be escaped with a backslash. */
    fun paramValue(value: String): String = buildString(value.length) {
        for (c in value) {
            if (c == '"' || c == '\\' || c == ']') append('\\')
            append(c)
        }
    }

    private fun hostname(panelId: String): String = panelId.ifBlank { "panel" }.replace(' ', '_')
}

/**
 * Production transports. Creation never blocks; [LogSink.connect] begins only after run attachment.
 * Internal rather than private so the transport tests can put real bytes on a real loopback socket —
 * an in-memory fake sink cannot tell TCP from UDP, which is how a TCP-only "syslog" transport aimed
 * at the UDP default port passed every test it had.
 */
internal fun interface LogAddressResolver {
    fun resolve(host: String, timeoutMs: Long): List<InetAddress>
}

private object ProductionLogAddressResolver : LogAddressResolver {
    override fun resolve(host: String, timeoutMs: Long): List<InetAddress> =
        BoundedDns.resolveAll(host, timeoutMs)
}

private class NetworkDeadline(timeoutMs: Long) {
    private val expiresAtNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)

    fun remainingMs(): Int {
        val remaining = expiresAtNanos - System.nanoTime()
        if (remaining <= 0L) throw SocketTimeoutException("log sink deadline exceeded")
        return TimeUnit.NANOSECONDS.toMillis(remaining).coerceAtLeast(1L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }
}

internal data class LogSinkProbeResult(
    val ok: Boolean,
    val delivered: Boolean,
    val candidate: InetAddress?,
    val error: String? = null,
    val status: Int? = null,
)

internal object NetworkLogSinkFactory : LogSinkFactory {
    override fun create(target: LogShipTarget, encode: (String) -> String): LogSink =
        create(target, encode, ProductionLogAddressResolver, LogShipper.NETWORK_TIMEOUT_MS.toLong())

    internal fun create(
        target: LogShipTarget,
        encode: (String) -> String,
        resolver: LogAddressResolver,
        timeoutMs: Long,
    ): LogSink =
        when (target.protocol) {
            LogShipEndpoint.HTTP -> HttpLogSink(target, encode, resolver, timeoutMs)
            LogShipEndpoint.SYSLOG_UDP -> UdpSyslogLogSink(target, encode, resolver, timeoutMs)
            else -> TcpSyslogLogSink(target, encode, resolver, timeoutMs)
        }

    internal fun probe(
        target: LogShipTarget,
        payload: ByteArray,
        resolver: LogAddressResolver = ProductionLogAddressResolver,
        timeoutMs: Long = 2_000L,
    ): LogSinkProbeResult {
        val deadline = NetworkDeadline(timeoutMs)
        val candidates = try {
            resolveCandidates(target.host, resolver, deadline)
        } catch (_: Exception) {
            return LogSinkProbeResult(false, false, null, "unresolvable")
        }
        return when (target.protocol) {
            LogShipEndpoint.SYSLOG_UDP -> {
                val candidate = candidates.first()
                try {
                    DatagramSocket().use {
                        it.connect(candidate, target.port)
                        it.send(DatagramPacket(payload, payload.size, candidate, target.port))
                    }
                    LogSinkProbeResult(true, false, candidate)
                } catch (_: Exception) {
                    LogSinkProbeResult(false, false, candidate, "send-failed")
                }
            }
            LogShipEndpoint.HTTP -> postAcrossCandidates(target, candidates, payload, deadline, null)
            else -> tcpWriteAcrossCandidates(target, candidates, payload, deadline, null)
        }
    }
}

private fun resolveCandidates(
    host: String,
    resolver: LogAddressResolver,
    deadline: NetworkDeadline,
): List<InetAddress> {
    val timeout = minOf(LogShipper.DNS_TIMEOUT_MS, deadline.remainingMs().toLong())
    return LogShipEndpoint.orderedCandidates(resolver.resolve(host, timeout)).ifEmpty {
        throw IOException("no address resolved for $host")
    }
}

private fun tcpWriteAcrossCandidates(
    target: LogShipTarget,
    candidates: List<InetAddress>,
    payload: ByteArray,
    deadline: NetworkDeadline,
    publish: ((Socket?) -> Unit)?,
): LogSinkProbeResult {
    var last: Exception? = null
    var lastCandidate: InetAddress? = null
    for (candidate in candidates) {
        lastCandidate = candidate
        val socket = Socket()
        publish?.invoke(socket)
        try {
            socket.connect(InetSocketAddress(candidate, target.port), deadline.remainingMs())
            socket.soTimeout = deadline.remainingMs()
            socket.getOutputStream().apply { write(payload); flush() }
            return LogSinkProbeResult(true, false, candidate)
        } catch (e: Exception) {
            last = e
        } finally {
            publish?.invoke(null)
            runCatching { socket.close() }
        }
    }
    return LogSinkProbeResult(false, false, lastCandidate, last?.message ?: "unreachable")
}

private val httpDeadlineExecutor = Executors.newSingleThreadScheduledExecutor { runnable ->
    Thread(runnable, "ha-paneld-logship-deadline").apply { isDaemon = true }
}

private fun postAcrossCandidates(
    target: LogShipTarget,
    candidates: List<InetAddress>,
    payload: ByteArray,
    deadline: NetworkDeadline,
    publish: ((Socket?) -> Unit)?,
): LogSinkProbeResult {
    var lastResult: LogSinkProbeResult? = null
    for (candidate in candidates) {
        val socket = Socket()
        publish?.invoke(socket)
        var timeout: java.util.concurrent.ScheduledFuture<*>? = null
        try {
            socket.connect(InetSocketAddress(candidate, target.port), deadline.remainingMs())
            socket.soTimeout = deadline.remainingMs()
            timeout = httpDeadlineExecutor.schedule(
                { runCatching { socket.close() } },
                deadline.remainingMs().toLong(),
                TimeUnit.MILLISECONDS,
            )
            val authority = "${LogShipEndpoint.urlHost(target.host)}:${target.port}"
            val head = "POST / HTTP/1.1\r\n" +
                "Host: $authority\r\n" +
                "Content-Type: application/x-ndjson\r\n" +
                "Content-Length: ${payload.size}\r\n" +
                "Connection: close\r\n\r\n"
            socket.getOutputStream().apply {
                write(head.toByteArray(Charsets.US_ASCII))
                write(payload)
                flush()
            }
            socket.soTimeout = deadline.remainingMs()
            val statusLine = socket.getInputStream().bufferedReader(Charsets.US_ASCII).readLine()
                ?: throw IOException("empty HTTP response")
            val status = statusLine.split(' ').getOrNull(1)?.toIntOrNull()
                ?: throw IOException("invalid HTTP response")
            if (status in 200..299) return LogSinkProbeResult(true, true, candidate, status = status)
            lastResult = LogSinkProbeResult(false, false, candidate, "http-$status", status)
        } catch (e: Exception) {
            lastResult = LogSinkProbeResult(false, false, candidate, e.message ?: "unreachable")
        } finally {
            timeout?.cancel(false)
            publish?.invoke(null)
            runCatching { socket.close() }
        }
    }
    return lastResult ?: LogSinkProbeResult(false, false, null, "unreachable")
}

/** RFC6587 non-transparent framing: newline-delimited RFC5424 frames on a stream. */
private class TcpSyslogLogSink(
    private val target: LogShipTarget,
    private val encode: (String) -> String,
    private val resolver: LogAddressResolver,
    private val timeoutMs: Long,
) : LogSink {
    // A java.net.Socket is single-use: a failed connect leaves it unusable, so each candidate needs
    // its own. Volatile because close() can arrive from another thread mid-connect.
    @Volatile private var socket: Socket? = null
    @Volatile private var closed = false

    override fun connect() {
        // A stream transport reports failure, so walk every resolved address rather than trusting
        // the first: a dual-stack name whose collector listens on one family only must still work.
        val deadline = NetworkDeadline(timeoutMs)
        var last: Exception? = null
        for (candidate in resolveCandidates(target.host, resolver, deadline)) {
            if (closed) throw IOException("closed")
            val attempt = Socket()
            // Publish before connecting: closing the run must be able to break a connect that is
            // still blocking, which is how stop() unblocks a worker parked on an unreachable sink.
            socket = attempt
            if (closed) {
                runCatching { attempt.close() }
                throw IOException("closed")
            }
            try {
                attempt.connect(InetSocketAddress(candidate, target.port), deadline.remainingMs())
                attempt.tcpNoDelay = true
                return
            } catch (e: Exception) {
                runCatching { attempt.close() }
                last = e
            }
        }
        throw last ?: IOException("no address resolved for ${target.host}")
    }

    override fun send(lines: List<String>) {
        val active = socket ?: throw IOException("tcp sink used before connect")
        val out = active.getOutputStream()
        for (line in lines) out.write(encode(line).toByteArray(Charsets.UTF_8))
        out.flush()
    }

    override fun close() {
        closed = true
        runCatching { socket?.close() }
    }
}

/**
 * RFC5426 UDP syslog: one datagram per message, no framing delimiter, and no delivery signal at all.
 *
 * [connect] resolves the name and connects the socket, which buys the only two failure signals UDP
 * offers — a resolution error up front, and the kernel surfacing ICMP port-unreachable on a later
 * send instead of discarding every datagram in silence.
 */
private class UdpSyslogLogSink(
    private val target: LogShipTarget,
    private val encode: (String) -> String,
    private val resolver: LogAddressResolver,
    private val timeoutMs: Long,
) : LogSink {
    private val socket = DatagramSocket()
    private var address: InetAddress? = null

    override fun connect() {
        // UDP has no failure signal to fall back on: a datagram sent to an address whose collector
        // is not listening succeeds locally and vanishes. Ordering is therefore the only lever, so
        // take the first candidate rather than "the first the platform happened to return".
        val resolved = resolveCandidates(target.host, resolver, NetworkDeadline(timeoutMs)).first()
        socket.connect(resolved, target.port)
        address = resolved
    }

    /** The address datagrams are actually going to, so the status line can name it. */
    fun resolvedAddress(): InetAddress? = address

    override fun send(lines: List<String>) {
        val destination = address ?: throw IOException("udp sink used before connect")
        for (line in lines) {
            // The trailing newline is stream framing (RFC6587). Inside a datagram the length is the
            // frame, so it is pure noise — and several collectors surface it as a trailing blank line.
            val bytes = truncateUtf8(encode(line).trimEnd('\n'), LogShipper.UDP_DATAGRAM_MAX_BYTES)
            socket.send(DatagramPacket(bytes, bytes.size, destination, target.port))
        }
    }

    override fun close() = socket.close()
}

/** Marks a line the datagram cap cut short, so a truncated record can never be read as a whole one. */
private const val TRUNCATION_MARK = "…"

/**
 * Encode [text] into at most [maxBytes] UTF-8 bytes, cutting on a character boundary. Splitting a
 * multi-byte sequence would corrupt the frame rather than just its tail: a receiver that fails to
 * decode the datagram discards the whole record, losing the part that fitted too.
 */
internal fun truncateUtf8(text: String, maxBytes: Int): ByteArray {
    val bytes = text.toByteArray(Charsets.UTF_8)
    if (bytes.size <= maxBytes) return bytes
    val mark = TRUNCATION_MARK.toByteArray(Charsets.UTF_8)
    // A continuation byte is 10xxxxxx; step back to the start of the sequence it belongs to.
    fun boundary(limit: Int): Int {
        var end = limit.coerceIn(0, bytes.size)
        while (end > 0 && (bytes[end].toInt() and 0xC0) == 0x80) end--
        return end
    }
    if (maxBytes <= mark.size) return bytes.copyOf(boundary(maxBytes))
    return bytes.copyOf(boundary(maxBytes - mark.size)) + mark
}

private class HttpLogSink(
    private val target: LogShipTarget,
    private val encode: (String) -> String,
    private val resolver: LogAddressResolver,
    private val timeoutMs: Long,
) : LogSink {
    @Volatile private var closed = false
    @Volatile private var connection: Socket? = null

    // Resolve once per run and POST directly to these addresses, preserving the original Host header
    // without handing a URL implementation an unbounded second DNS lookup.
    @Volatile private var candidates: List<InetAddress> = emptyList()

    override fun connect() {
        if (closed) throw IOException("closed")
        candidates = resolveCandidates(target.host, resolver, NetworkDeadline(timeoutMs))
    }

    override fun send(lines: List<String>) {
        val body = lines.joinToString("\n") { encode(it) }.toByteArray(Charsets.UTF_8)
        val result = postAcrossCandidates(target, candidates, body, NetworkDeadline(timeoutMs)) { active ->
            synchronized(this) {
                if (closed && active != null) {
                    runCatching { active.close() }
                    throw IOException("closed")
                }
                connection = active
            }
        }
        if (!result.ok) {
            throw IOException(result.status?.let { "HTTP $it" } ?: result.error ?: "unreachable")
        }
    }

    override fun close() {
        val active = synchronized(this) {
            closed = true
            connection.also { connection = null }
        }
        runCatching { active?.close() }
    }
}

/** The dashboard console source runs only while log shipping is configured, and never in Hardened
 * mode, which refuses the CDP relay it reads through. */
internal fun webViewConsoleEnabled(config: Config): Boolean =
    webViewConsoleEnabled(config.logShipEnabled, config.logShipHost, config.hardenedSecurityEnabled)

internal fun webViewConsoleEnabled(shippingEnabled: Boolean, shippingHost: String, hardened: Boolean): Boolean =
    shippingEnabled && shippingHost.isNotBlank() && !hardened

/** One subscription over several captures; closing it detaches every one. */
internal fun subscribeAll(vararg captures: LogCapture): ((String) -> Unit) -> AutoCloseable = { listener ->
    val subscriptions = ArrayList<AutoCloseable>(captures.size)
    try {
        for (capture in captures) subscriptions.add(capture.subscribe(listener))
    } catch (failure: Exception) {
        subscriptions.forEach { runCatching { it.close() } }
        throw failure
    }
    AutoCloseable { subscriptions.forEach { runCatching { it.close() } } }
}
