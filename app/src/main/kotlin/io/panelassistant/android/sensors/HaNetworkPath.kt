package io.panelassistant.android.sensors

import org.json.JSONObject

/**
 * Why one attempt to reach Home Assistant produced no round trip.
 *
 * The split is the point of the feature: a stopped, restarting, overloaded or misconfigured server
 * must never be reported as packet loss, because the remedy is different. [NETWORK] is the only kind
 * that counts toward loss and the consecutive-failure alarm.
 */
internal enum class HaPathFailureKind(val wireValue: String) {
    /** The path itself: a probe that never came back, an unreachable host, a connect that timed out. */
    NETWORK("network"),

    /** Home Assistant answered, or the host did, but not usefully: closed socket, refused port, error frame. */
    SERVER("server"),

    /** Credentials were refused. The bytes got there and back; the sign-in is what failed. */
    AUTH("auth"),
}

/**
 * The shared Home Assistant socket's state, as the only thing entitled to start and stop a
 * measurement. Reported by the stream owner; nothing else may synthesise it.
 */
internal enum class HaSocketState(val wireValue: String) {
    /** No socket and none being sought: no demand, no credentials, or the stream has parked. */
    STOPPED("stopped"),

    /** Connecting, authenticating, subscribing or backing off between attempts. */
    CONNECTING("connecting"),

    /** Authenticated and subscribed: probes can be sent and the path can be described. */
    LIVE("live"),
}

/** The one classification every surface renders. Wire values are the `/health` and `/diag` tokens. */
internal enum class HaNetworkPathSeverity(val wireValue: String) {
    HEALTHY("healthy"),
    WARNING("warning"),
    SEVERE("severe"),
}

/**
 * Rolling classification of the network path between this panel and its configured Home Assistant.
 *
 * Measures the path the product actually uses: the round trip of a Home Assistant WebSocket `ping`
 * on the app's own authenticated socket. No ICMP, no subprocess, no root. Pure and time-injected in
 * the `HaLifecycle` style: every observation carries its own monotonic millisecond, so nothing here
 * can be satisfied or defeated by real elapsed time, and the bounded window ages state out by itself.
 *
 * Policy. TWO classifications come out of one measurement, and only the first is entitled to a
 * prominent warning:
 *  - [Snapshot.severity], the PATH: WARNING when network loss exceeds [SEVERE_LOSS_PERCENT] of the
 *    probes in the [WINDOW_MS] window, SEVERE after [SEVERE_CONSECUTIVE_FAILURES] consecutive
 *    network failures. Latency cannot raise it at all.
 *  - [Snapshot.responsiveness], HOW FAST HOME ASSISTANT ANSWERS: WARNING over [WARN_P95_MS],
 *    SEVERE over [SEVERE_P95_MS]. Reported everywhere as a performance number, prominent nowhere.
 *  - one isolated miss or one isolated spike is diagnostic evidence only and never alarms;
 *  - nothing at all is judged within [STARTUP_SETTLE_MS] of process start.
 *
 * Why latency is not a path verdict: the round trip also contains Home Assistant's event-loop time
 * and this panel's own thread scheduling, neither of which is the network. Both are real and worth
 * showing; neither is evidence about the Wi-Fi. A true layer-3 probe is a separate, queued lane.
 *
 * The "exceeds p95" and "exceeds loss" tests are both written as [exceedsShare]: at least two events
 * AND more than five percent of the window. Nearest-rank p95 over fewer than twenty samples IS the
 * maximum, so a threshold on the reported p95 alone would let a single spike right after connecting
 * raise a warning; the two-event floor is what keeps the isolated cases diagnostic. Once the window
 * holds twenty or more round trips the two tests coincide exactly with "nearest-rank p95 > T".
 *
 * Retained: bounded samples only. Never a hostname, address, SSID or raw frame.
 */
internal class HaNetworkPath(
    private val windowMs: Long = WINDOW_MS,
    private val maxSamples: Int = MAX_SAMPLES,
    private val warnP95Ms: Long = WARN_P95_MS,
    private val severeP95Ms: Long = SEVERE_P95_MS,
    private val severeConsecutiveFailures: Int = SEVERE_CONSECUTIVE_FAILURES,
    /**
     * When this process started, on the SAME monotonic clock every observation carries. Probes taken
     * within [settleMs] of it are discarded rather than averaged in, because startup is a known
     * high-latency period and its figures describe the panel's own load, not the path.
     *
     * [NO_STARTUP_GATE] disables the gate outright, which is the default so that a trace driven from
     * an arbitrary origin measures exactly what it says it measures. Production supplies the real
     * process start; only tests that are ABOUT settling need to pass one.
     */
    private val processStartElapsedMs: Long = NO_STARTUP_GATE,
    private val settleMs: Long = STARTUP_SETTLE_MS,
) {
    private enum class Outcome { ROUND_TRIP, NETWORK_FAILURE, SERVER_FAILURE, AUTH_FAILURE }

    private class Sample(val atMs: Long, val outcome: Outcome, val rttMs: Long)

    private val samples = ArrayDeque<Sample>()
    private var measuring = false
    private var socketLive = false
    private var consecutiveNetworkFailures = 0
    private var lastRoundTripAtMs = -1L

    /**
     * The layer-3 verdict, when the echo probe has one.
     *
     * A genuine path measurement outranks anything this class can derive from a WebSocket, so when
     * it is present it IS the path verdict. The socket-derived loss rules remain for panels whose
     * platform denies the probe, which is the only reason they are still here.
     */
    private var probeSeverity: HaNetworkPathSeverity? = null
    private var probeCause: PathProbeCause = PathProbeCause.NONE

    /**
     * Follow the shared socket's own state. Measurement is owned by an AUTHENTICATED socket, not by
     * the demand for one: until the stream has actually reached [HaSocketState.LIVE] there is no
     * Home Assistant application path to describe, so a panel that is still authenticating — or one
     * whose stream has parked on a refused sign-in or repeated protocol failures — reports nothing
     * rather than a verdict it cannot support.
     *
     * [HaSocketState.CONNECTING] deliberately does NOT stop measurement once a socket has been held.
     * Reconnect attempts are exactly when a broken path must still be reported: dropping the verdict
     * there would hide "severely degraded" for the whole outage it exists to explain, and would
     * discard the very failures that prove it. The distinction is published instead
     * ([Snapshot.socketLive]) so a reader can tell a live measurement from one being re-established.
     *
     * [HaSocketState.STOPPED] discards the samples, so a socket re-demanded hours later starts clean
     * instead of replaying a stale verdict.
     */
    fun onSocketState(state: HaSocketState) {
        socketLive = state == HaSocketState.LIVE
        when (state) {
            HaSocketState.LIVE -> measuring = true
            HaSocketState.CONNECTING -> Unit
            HaSocketState.STOPPED -> {
                measuring = false
                samples.clear()
                consecutiveNetworkFailures = 0
                lastRoundTripAtMs = -1L
            }
        }
    }

    /**
     * True while this observation falls inside the startup settling period. Read at the ENTRY of
     * every observation and of [snapshot], because the run counter and the last-reply stamp are kept
     * outside `record` — gating only the sample store would leave those two moving on discarded data.
     */
    private fun settling(nowMs: Long): Boolean =
        processStartElapsedMs != NO_STARTUP_GATE && nowMs < processStartElapsedMs + settleMs

    /**
     * Publish the layer-3 verdict, or null when the probe cannot speak — unsupported, unproven, or
     * with nothing measured yet. Null restores the socket-derived rules rather than clearing the
     * verdict, so a panel that loses its probe is no worse off than one that never had it.
     */
    fun onPathProbeVerdict(severity: HaNetworkPathSeverity?, cause: PathProbeCause) {
        probeSeverity = severity
        probeCause = if (severity == null) PathProbeCause.NONE else cause
    }

    /** One probe answered: [rttMs] is the send-to-decode round trip on the monotonic clock. */
    fun onRoundTrip(nowMs: Long, rttMs: Long) {
        if (settling(nowMs)) return
        record(Sample(nowMs, Outcome.ROUND_TRIP, rttMs.coerceAtLeast(0L)))
        consecutiveNetworkFailures = 0
        lastRoundTripAtMs = nowMs
    }

    /** One attempt failed for the reason [kind]; only [HaPathFailureKind.NETWORK] is loss. */
    fun onFailure(nowMs: Long, kind: HaPathFailureKind) {
        if (settling(nowMs)) return
        when (kind) {
            HaPathFailureKind.NETWORK -> {
                record(Sample(nowMs, Outcome.NETWORK_FAILURE, -1L))
                consecutiveNetworkFailures++
            }
            // A server or sign-in failure is neither a hit nor a miss on the path: it neither extends
            // nor resets the consecutive-network-failure count, because it says nothing about the path.
            HaPathFailureKind.SERVER -> record(Sample(nowMs, Outcome.SERVER_FAILURE, -1L))
            HaPathFailureKind.AUTH -> record(Sample(nowMs, Outcome.AUTH_FAILURE, -1L))
        }
    }

    private fun record(sample: Sample) {
        samples.addLast(sample)
        while (samples.size > maxSamples) samples.removeFirst()
    }

    /**
     * One atomic rendered tuple for [nowMs]. Samples older than the window are dropped first, which is
     * the only ageing mechanism: recovery is not an event, it is the bad samples leaving the window.
     */
    fun snapshot(nowMs: Long): Snapshot {
        while (samples.isNotEmpty() && samples.first().atMs < nowMs - windowMs) samples.removeFirst()
        val roundTrips = samples.filter { it.outcome == Outcome.ROUND_TRIP }
        val rtts = roundTrips.map { it.rttMs }
        val sorted = rtts.sorted()
        val networkFailures = samples.count { it.outcome == Outcome.NETWORK_FAILURE }
        val serverFailures = samples.count { it.outcome == Outcome.SERVER_FAILURE }
        val authFailures = samples.count { it.outcome == Outcome.AUTH_FAILURE }
        val probes = rtts.size + networkFailures
        // The run is a claim about the window, so it ages WITH the window: once no failure sample
        // survives, nothing evidences a run and it is zeroed rather than carried indefinitely. A run
        // whose older half has aged out but whose newest failure is still inside remains provable and
        // is deliberately kept — that is what carries a verdict across a slow reconnect ladder.
        if (networkFailures == 0) consecutiveNetworkFailures = 0
        val consecutive = consecutiveNetworkFailures
        val settling = settling(nowMs)
        // The PROMINENT verdict is about the path, so only evidence about the path may raise it: a
        // probe that produced no frame at all, or a run of them. Latency deliberately cannot reach
        // this, because the round trip also contains Home Assistant's event-loop time and this panel's
        // own thread scheduling: panels of different speeds on ONE server measure round trips in
        // order of how weak their processor is, which is not a fact about anybody's network. Latency
        // is reported as [responsiveness] instead.
        // No `settling` term here on purpose. Observations inside the settle window are discarded at
        // the entry of onRoundTrip/onFailure, so the window is empty and every rule below already
        // yields HEALTHY. A `|| settling` branch here survived its own mutant — nothing could tell the
        // two apart — so it is left out rather than kept as protection no test can reach. What the
        // period IS reported as comes from [Snapshot.settling], which every surface reads.
        // A layer-3 measurement outranks anything derivable from the socket, so when the echo probe
        // has a verdict it IS the path verdict — including a latency one, because an echo carries no
        // server time and no scheduling of ours. The socket rules below survive only for panels
        // whose platform refuses the probe.
        val fromProbe = probeSeverity.takeIf { measuring && !settling }
        val severity = fromProbe ?: when {
            !measuring || settling -> HaNetworkPathSeverity.HEALTHY
            consecutive >= severeConsecutiveFailures -> HaNetworkPathSeverity.SEVERE
            exceedsShare(networkFailures, probes) -> HaNetworkPathSeverity.WARNING
            else -> HaNetworkPathSeverity.HEALTHY
        }
        val cause = when {
            fromProbe != null -> probeCause
            severity == HaNetworkPathSeverity.HEALTHY -> PathProbeCause.NONE
            else -> PathProbeCause.LOSS
        }
        // How quickly Home Assistant answers on a socket that is demonstrably up. A useful performance
        // number and never a network claim: it is reported, but it never raises the banner, the panel
        // chip, or a network likely-cause.
        val responsiveness = when {
            !measuring -> HaNetworkPathSeverity.HEALTHY
            exceedsShare(rtts.count { it > severeP95Ms }, rtts.size) -> HaNetworkPathSeverity.SEVERE
            exceedsShare(rtts.count { it > warnP95Ms }, rtts.size) -> HaNetworkPathSeverity.WARNING
            else -> HaNetworkPathSeverity.HEALTHY
        }
        return Snapshot(
            measuring = measuring,
            settling = settling,
            socketLive = socketLive,
            severity = severity,
            cause = cause,
            responsiveness = responsiveness,
            windowMs = windowMs,
            probes = probes,
            roundTrips = rtts.size,
            networkFailures = networkFailures,
            serverFailures = serverFailures,
            authFailures = authFailures,
            p50Ms = nearestRank(sorted, 0.50),
            p95Ms = nearestRank(sorted, 0.95),
            maxMs = sorted.lastOrNull() ?: -1L,
            jitterMs = jitter(rtts),
            lossPercent = if (probes == 0) 0.0 else networkFailures * 100.0 / probes,
            consecutiveFailures = consecutive,
            lastRoundTripAgeMs = if (lastRoundTripAtMs < 0L) -1L else (nowMs - lastRoundTripAtMs).coerceAtLeast(0L),
        )
    }

    /**
     * The one atomic tuple a surface may render. Every millisecond field is `-1` when there is no
     * round trip in the window, never `0`, so "fast" and "unmeasured" cannot be confused.
     */
    data class Snapshot(
        val measuring: Boolean,
        /** Inside the post-start settling period: measured, but not yet describable. */
        val settling: Boolean,
        /** Whether the authenticated socket is up right now, as opposed to being re-established. */
        val socketLive: Boolean,
        /** The path verdict, and the ONLY thing entitled to a prominent warning. */
        val severity: HaNetworkPathSeverity,
        /** What raised [severity], so the wording can name the right thing to go and look at. */
        val cause: PathProbeCause,
        /** How fast Home Assistant answers. A performance metric; never a network claim. */
        val responsiveness: HaNetworkPathSeverity,
        val windowMs: Long,
        val probes: Int,
        val roundTrips: Int,
        val networkFailures: Int,
        val serverFailures: Int,
        val authFailures: Int,
        val p50Ms: Long,
        val p95Ms: Long,
        val maxMs: Long,
        val jitterMs: Long,
        val lossPercent: Double,
        val consecutiveFailures: Int,
        val lastRoundTripAgeMs: Long,
    ) {
        /**
         * True while a surface has a PROMINENT warning to show. Deliberately reads [severity] only:
         * a slow-but-intact path is a performance observation, not something to interrupt anyone with.
         */
        val degraded: Boolean get() = measuring && !settling && severity != HaNetworkPathSeverity.HEALTHY

        /**
         * The key surfaces are re-poked on. Numbers move with every probe and the polling surfaces
         * read them anyway; only a change of verdict is worth waking the native chip for. Settling is
         * part of the key because leaving it changes the wording every surface renders.
         */
        val reportableKey: Triple<Boolean, Boolean, HaNetworkPathSeverity>
            get() = Triple(measuring, settling, severity)
    }

    companion object {
        /** The rolling window every rate and percentile is judged over. */
        const val WINDOW_MS = 5L * 60_000L

        /**
         * Retention bound. At [PROBE_INTERVAL_MS] a window holds thirty probes; the bound leaves room
         * for the reconnect attempts a bad five minutes adds (backoff runs 1 s doubling to 60 s, so at
         * most about ten) plus server-side failures, and drops the oldest beyond that.
         */
        const val MAX_SAMPLES = 64

        /**
         * How often the socket owner sends a probe while live, regardless of entity traffic.
         *
         * The arithmetic this was chosen for: 300 s / 10 s = 30 probes per window. One miss is
         * 3.3 % loss and one spike is a p95 rank of 29 of 30 that ignores it, so both the loss rule
         * and the p95 rule need TWO bad probes before they fire; two misses are 6.7 %, over the 5 %
         * line. The two-consecutive-timeout rule fires at any window size. A 15 s cadence would give
         * twenty probes and the same two-probe property; anything slower than 20 s would let one miss
         * exceed 5 % on its own, which the policy forbids.
         */
        const val PROBE_INTERVAL_MS = 10_000L

        /**
         * The APPLICATION-layer warning line, raised from 100 ms to 250 ms.
         *
         * This figure contains the server's own answer time and this panel's thread
         * scheduling as well as the path, so on the weakest hardware it sits in the low hundreds
         * while nothing is wrong; 100 ms produced a warning on panels that were simply slow.
         * Reported, never prominent — the prominent warning belongs to layer 3.
         */
        const val WARN_P95_MS = 250L
        const val SEVERE_P95_MS = 1_000L
        const val SEVERE_LOSS_PERCENT = 5.0
        const val SEVERE_CONSECUTIVE_FAILURES = 2

        /** Sentinel for [processStartElapsedMs]: no startup gate, so every observation counts. */
        const val NO_STARTUP_GATE = Long.MIN_VALUE

        /**
         * How long after process start observations are discarded rather than believed.
         *
         * Startup is the one period whose latency is known in advance to describe the panel and not
         * the path: the WebView initialises, the dashboard first-paints and the entity catalogue
         * hydrates, all competing for the same cores the socket's reader thread needs. A wired panel
         * with a sub-millisecond path can report a p95 in the hundreds of milliseconds during it.
         *
         * Three minutes is chosen against the five-minute [WINDOW_MS]: by the time a verdict can be
         * formed the window has been refilled from post-settle probes, so no startup figure survives
         * into a judgement. It is deliberately generous — the cost of waiting is a few minutes of
         * "settling" on a freshly started panel, and the cost of being early is the false alarm this
         * whole lane exists to remove.
         */
        const val STARTUP_SETTLE_MS = 3L * 60_000L

        /**
         * "More than five percent of the window, and at least two events." The floor is what keeps an
         * isolated miss or spike diagnostic; the share is what "p95 > T" and "loss > 5 %" both reduce to.
         */
        fun exceedsShare(count: Int, total: Int): Boolean =
            count >= 2 && count * 100.0 > SEVERE_LOSS_PERCENT * total

        /** Nearest-rank percentile over an ascending list; `-1` for an empty one. */
        fun nearestRank(sorted: List<Long>, fraction: Double): Long {
            if (sorted.isEmpty()) return -1L
            val rank = kotlin.math.ceil(sorted.size * fraction).toInt().coerceIn(1, sorted.size)
            return sorted[rank - 1]
        }

        /** Mean absolute difference between successive round trips (RFC 3550 style), `0` under two. */
        fun jitter(rttsInOrder: List<Long>): Long {
            if (rttsInOrder.size < 2) return 0L
            var total = 0L
            for (i in 1 until rttsInOrder.size) total += kotlin.math.abs(rttsInOrder[i] - rttsInOrder[i - 1])
            return total / (rttsInOrder.size - 1)
        }
    }
}

/**
 * The wording and machine projections of one [HaNetworkPath.Snapshot]. Pure, so every surface renders
 * the same tuple the same way and the contract tests can pin the text without a service.
 *
 * None of these ever include a host, address, SSID, BSSID or an individual sample.
 */
internal object HaNetworkPathPresentation {
    /**
     * The panel-facing headline: one short phrase readable from across a room. It says packets are
     * going missing, because that is now the only thing that can raise it — never "slow", which is
     * a claim a round trip cannot support.
     */
    const val PANEL_TEXT = "HA network unreliable"

    /** `/health` tokens, or empty when there is nothing to report (unreportable, exactly like `ha=`). */
    fun healthToken(snap: HaNetworkPath.Snapshot?): String {
        if (snap == null || !snap.measuring) return ""
        if (snap.settling) return " ha_net=settling"
        // ha_net is the PATH verdict; ha_resp is how fast Home Assistant answers. ha_net_p95 keeps its
        // name because it is documented wire, but it has only ever been the round trip, which is now
        // classified by ha_resp rather than by ha_net.
        val cause = when (snap.cause) {
            PathProbeCause.LATENCY -> " ha_net_cause=latency"
            PathProbeCause.LOSS -> " ha_net_cause=loss"
            PathProbeCause.NONE -> ""
        }
        return " ha_net=${snap.severity.wireValue}$cause ha_resp=${snap.responsiveness.wireValue} " +
            "ha_net_p95=${snap.p95Ms} ha_net_n=${snap.probes} " +
            "ha_net_miss=${snap.networkFailures} ha_net_age=${snap.lastRoundTripAgeMs}"
    }

    /**
     * The Runtime diagnostics row, or null when no service owns the monitor.
     *
     * ONE row carries both halves of the measurement: the path verdict, then — only when it has
     * something to add — how slowly Home Assistant is answering. They are kept in one row rather than
     * two so that a reader always sees the path verdict beside the latency that used to be mistaken
     * for it, and because a second row would need its own reviewed catalogue entry.
     */
    fun statusText(snap: HaNetworkPath.Snapshot?): String? {
        if (snap == null) return null
        if (!snap.measuring) return NOT_MEASURED
        if (snap.settling) return SETTLING
        val path = when {
            snap.severity == HaNetworkPathSeverity.HEALTHY -> "healthy"
            snap.cause == PathProbeCause.LATENCY && snap.severity == HaNetworkPathSeverity.WARNING -> "slow"
            snap.cause == PathProbeCause.LATENCY -> "very slow"
            snap.severity == HaNetworkPathSeverity.WARNING -> "losing probes"
            else -> "failing"
        }
        if (snap.cause == PathProbeCause.LATENCY) {
            val responsiveness = responsivenessClause(snap).removeSuffix("; ")
            return if (responsiveness.isEmpty()) path else "$path; $responsiveness"
        }
        return "$path; ${responsivenessClause(snap)}${evidence(snap)}"
    }

    /**
     * How slowly Home Assistant is answering, as a clause for the one row, or empty while it is
     * answering normally. Never says "network": a slow reply on a path that is losing nothing is the
     * server or this panel, and calling it the network is the mistake this wording exists to prevent.
     */
    fun responsivenessClause(snap: HaNetworkPath.Snapshot): String = when (snap.responsiveness) {
        HaNetworkPathSeverity.HEALTHY -> ""
        HaNetworkPathSeverity.WARNING -> "Home Assistant answering slowly; "
        HaNetworkPathSeverity.SEVERE -> "Home Assistant answering very slowly; "
    }

    const val NOT_MEASURED = "not measured; this panel holds no authenticated Home Assistant socket"

    /**
     * Startup is a known high-latency period, so it is reported as its own state rather than as a
     * verdict. Saying "healthy" here would be a claim the panel has not earned, and reusing
     * [NOT_MEASURED] would be false: the socket IS held.
     */
    const val SETTLING = "settling after startup; no verdict yet"

    /**
     * The terse numbers every surface shares: window, probe count, p95, misses and, when the window
     * is empty, how long ago the last reply was. An empty window is two different facts: a socket
     * that has only just connected, and a stream that parked (a refused sign-in, repeated protocol
     * failures) and stopped probing. Only the age tells them apart, so an empty window with a
     * remembered reply says so rather than reading like a fresh connect.
     */
    fun evidence(snap: HaNetworkPath.Snapshot): String {
        val minutes = snap.windowMs / 60_000L
        if (snap.probes == 0) {
            return if (snap.lastRoundTripAgeMs < 0L) "no probes yet in the last $minutes min"
            else "no probe answered in the last $minutes min; last reply ${age(snap.lastRoundTripAgeMs)} ago"
        }
        val p95 = if (snap.p95Ms < 0L) "no reply" else "p95 ${ms(snap.p95Ms)}"
        val misses = when (snap.networkFailures) {
            0 -> "no misses"
            else -> "${snap.networkFailures} of ${snap.probes} probes missed"
        }
        return "$p95, $misses in the last $minutes min"
    }

    /**
     * The `:8888` banner copy, rendered client-side by `buildwatch.js` from the `/health` tokens so
     * it retracts itself on recovery. Kept here as the single source the contract test compares the
     * script against, so the two cannot drift.
     */
    const val BANNER_WARNING_PREFIX = "⚠ Probes to Home Assistant are going missing"

    /** Latency-raised copy. The path itself is slow, which is a different thing to go and check. */
    const val BANNER_WARNING_SLOW_PREFIX = "⚠ The network path to Home Assistant is slow"
    const val BANNER_SEVERE_SLOW_PREFIX = "⚠ The network path to Home Assistant is very slow"
    const val BANNER_SLOW_ADVICE =
        "Every action waits on this path. It is measured at the network level, so this is not the panel " +
            "or Home Assistant being slow — check the Wi-Fi or the link between them."
    const val BANNER_SEVERE_PREFIX = "⚠ The network path to Home Assistant is failing"
    const val BANNER_ADVICE =
        "Packets are not getting through. Check the Wi-Fi path between this panel and Home Assistant before blaming the panel."

    /** One `/diag` line: classified state and terse aggregates only. */
    fun diagnosticLine(snap: HaNetworkPath.Snapshot?): String {
        if (snap == null) return "[ha-network] state=unowned"
        if (!snap.measuring) return "[ha-network] state=idle measuring=false"
        if (snap.settling) return "[ha-network] state=settling measuring=true"
        return "[ha-network] state=${snap.severity.wireValue} cause=${snap.cause.name.lowercase()} " +
            "responsiveness=${snap.responsiveness.wireValue} " +
            "measuring=true " +
            "socket=${if (snap.socketLive) "live" else "reconnecting"} window=${snap.windowMs / 60_000L}m " +
            "probes=${snap.probes} round_trips=${snap.roundTrips} p50=${snap.p50Ms} p95=${snap.p95Ms} " +
            "max=${snap.maxMs} jitter=${snap.jitterMs} loss=${"%.1f".format(java.util.Locale.ROOT, snap.lossPercent)}% " +
            "consecutive=${snap.consecutiveFailures} server_errors=${snap.serverFailures} auth_errors=${snap.authFailures} " +
            "last_reply_age=${snap.lastRoundTripAgeMs}"
    }

    /** The `/api/v1/status` object, emitted unconditionally so an absent field cannot read as healthy. */
    fun statusJson(snap: HaNetworkPath.Snapshot?): String {
        val json = JSONObject()
            .put("measuring", snap?.measuring ?: false)
            .put(
                "state",
                when {
                    snap == null || !snap.measuring -> "idle"
                    snap.settling -> "settling"
                    else -> snap.severity.wireValue
                },
            )
        if (snap != null && snap.measuring) {
            json.put("responsiveness", if (snap.settling) "settling" else snap.responsiveness.wireValue)
                .put("settling", snap.settling)
                .put("socket", if (snap.socketLive) "live" else "reconnecting")
                .put("window_ms", snap.windowMs)
                .put("probes", snap.probes)
                .put("round_trips", snap.roundTrips)
                .put("network_failures", snap.networkFailures)
                .put("server_failures", snap.serverFailures)
                .put("auth_failures", snap.authFailures)
                .put("p50_ms", snap.p50Ms)
                .put("p95_ms", snap.p95Ms)
                .put("max_ms", snap.maxMs)
                .put("jitter_ms", snap.jitterMs)
                .put("loss_percent", Math.round(snap.lossPercent * 10.0) / 10.0)
                .put("consecutive_failures", snap.consecutiveFailures)
                .put("last_round_trip_age_ms", snap.lastRoundTripAgeMs)
        }
        return json.toString()
    }

    /** A remembered age in the coarsest honest unit: seconds under a minute, whole minutes after. */
    fun age(ms: Long): String = if (ms < 60_000L) "${ms / 1_000L} s" else "${ms / 60_000L} min"

    /** The likely-cause code `DashboardTelemetry` ranks first while the path is degraded. */
    const val LIKELY_CAUSE = "ha_network_path"

    private fun ms(value: Long): String = "${"%,d".format(java.util.Locale.ROOT, value)} ms"
}
