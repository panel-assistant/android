package io.github.maxlyth.hapaneld.metrics

import android.os.SystemClock
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * A coherent reading of the system telemetry union at [ts]. [cpuOverall] is null on the very first read
 * (no prior baseline) or when the CPU source is unavailable; [gpuPct] is −1 when there's no GPU load
 * figure. [dump] is populated only when this reading came from a helper-daemon `PERFDUMP` — it carries the
 * process + renderer tables PerfReader needs for its top/render deltas, so no second dump is ever fetched.
 */
data class Snapshot(
    val ts: Long,
    val cpuOverall: Int?,
    val cpuCores: List<Int>,
    val memUsedMb: Long,
    val memTotalMb: Long,
    val memPercent: Int?,
    val socTempC: Double?,
    val gpuPct: Int,
    val gpuMhz: Long,
    val loadavg: List<String>,
    val freqCurMhz: List<Long>,
    val freqMaxMhz: Long,
    val dump: PerfDump?,
)

/**
 * The single source of truth for ha-paneld's OS-sourced **poll** telemetry — the union of what PerfReader
 * (the page-gated `/perf` chart) and the always-on HA `diag_*` publisher (in `MqttBridge`) used to read
 * via duplicated, divergent `/proc`+`/sys` logic. Both now call this one reader directly while keeping
 * their own lifecycles; only the data source is shared. It also owns the constant boot-time timestamp
 * ([bootTime]) that the `diag_boot` sensor publishes.
 *
 * **Two distinct caches** (they must not be conflated):
 *  - *Source-resolution* ([Resolvable]) — which strategy WORKS for a metric (direct `/proc`+`/sys`, then
 *    the root daemon) is a static property of the panel (SELinux mode, uid, which nodes exist, daemon
 *    reachability). It's discovered ONCE by probing cheap-first, then cached sticky; steady-state reads go
 *    straight to the winner with zero probing. A read failure triggers exactly one re-resolution pass; a
 *    metric that resolves to "no working source" is retried only slowly ([unavailRetryMs]) so a
 *    late-arriving daemon can still recover it.
 *  - *Value-freshness* — the last coherent [Snapshot] + its timestamp, reused within [freshMs] so the 2 s
 *    chart and 60 s heartbeat consumers coalesce onto one read (and one PERFDUMP) instead of double-reading.
 *
 * **One `/proc/stat` baseline.** The CPU busy-% delta lives here once (guarded by [lock]) so two callers
 * at different cadences can't each keep a broken half-baseline. Only a real read advances it. PerfReader's
 * top/render deltas stay PerfReader-local.
 *
 * **One PERFDUMP per tick.** A [TickCtx] memoizes the dump for a single [readCoherent], so however many
 * metrics resolve to the daemon, they share one round-trip.
 *
 * The whole read runs under [lock] (single-flight, like [io.github.maxlyth.hapaneld.util.Cached.get]).
 */
class PanelMetrics(
    private val source: MetricSource = OsMetricSource(),
    private val clock: () -> Long = System::currentTimeMillis,
    private val freshMs: Long = FRESH_MS,
    unavailRetryMs: Long = UNAVAIL_RETRY_MS,
    private val elapsedRealtime: () -> Long = SystemClock::elapsedRealtime,
) {
    private val lock = Any()

    // Boot wall-clock, computed once — a constant timestamp sensor never flaps (vs an ever-rising uptime).
    // An Android-only constant (never a `/proc` poll), so it lives here rather than being a polled metric:
    // wall-now minus elapsed-realtime, formatted as ISO-8601 UTC.
    private val bootIso: String by lazy {
        val bootMs = clock() - elapsedRealtime()
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }
            .format(Date(bootMs))
    }
    private var prevStat: List<LongArray>? = null   // the ONE shared /proc/stat delta baseline
    private var cached: Snapshot? = null
    private var roomCachedAt: Long? = null           // value-freshness cache for the off-tick room read (null = never read)
    private var roomCached: RoomClimate? = null

    // Per-metric source resolvers, cheap-first (DIRECT before DAEMON). A DAEMON strategy pulls from the
    // tick's memoized PERFDUMP, so several daemon-resolved metrics still cost one round-trip.
    private val statR = Resolvable(unavailRetryMs, listOf(
        Strategy(SourceKind.DIRECT) { c -> MetricParse.cpuStatLines(c.source.statText().orEmpty()).takeIf { it.isNotEmpty() } },
        Strategy(SourceKind.DAEMON) { c -> c.dump()?.stat?.takeIf { it.isNotEmpty() } },
    ))
    private val tempR = Resolvable(unavailRetryMs, listOf(
        Strategy(SourceKind.DIRECT) { c -> MetricParse.maxThermalC(c.source.thermalMilliValues()) },
        Strategy(SourceKind.DAEMON) { c -> c.dump()?.let { MetricParse.dumpTempC(it.tempMilli) } },
    ))
    private val gpuR = Resolvable(unavailRetryMs, listOf(
        Strategy(SourceKind.DIRECT) { c -> c.source.gpuLoadRaw() },
        Strategy(SourceKind.DAEMON) { c -> c.dump()?.gpuRaw },
    ))
    private val loadR = Resolvable(unavailRetryMs, listOf(
        Strategy(SourceKind.DIRECT) { c -> c.source.loadavgText()?.let { MetricParse.loadavg3(it) }?.takeIf { it.isNotEmpty() } },
        Strategy(SourceKind.DAEMON) { c -> c.dump()?.loadavg?.takeIf { it.isNotEmpty() } },
    ))

    // Room climate (CHT8305): the helper→Shizuku authority ladder, folded into the same [Resolvable]
    // source-selection every other metric uses — each strategy reads its raw authority and parses ONCE,
    // so the winner's value is produced without the reader re-parsing it. `unavailRetryMs = 0` keeps the
    // prior semantics on chip-less panels (re-run the ladder on every fresh-miss read, no source backoff);
    // the value-freshness cache below is what coalesces the temp + humidity heartbeat reads onto one round-trip.
    private val roomR = Resolvable(0L, listOf(
        Strategy(SourceKind.DAEMON) { c -> MetricParse.parseCht8305(c.source.roomClimateDaemon()) },
        Strategy(SourceKind.SHIZUKU) { c -> MetricParse.parseCht8305(c.source.roomClimateShell()) },
    ), stickyWinner = false)

    /** A coherent reading, freshness-cached. Thread-safe: safe to call from the PerfReader coroutine and
     *  the MQTT heartbeat thread concurrently. */
    fun systemSnapshot(now: Long = clock()): Snapshot = synchronized(lock) {
        cached?.let { if (isWithinForwardWindow(now, it.ts, freshMs)) return it }
        readCoherent(now).also { cached = it }
    }

    /** LAN IPv4 (headline diag sensor). Not delta/cache-sensitive — a straight source read. */
    fun ipAddress(): String? = source.localIp()

    /** Boot time as an ISO-8601 UTC timestamp (constant until reboot → HA shows "N hours ago"). */
    fun bootTime(): String = bootIso

    /** SELinux mode ("1"/"0"). A control read-back routed through the reader (with [cpuGovernor] below);
     *  backlight / relay / LED reads are registered in [MetricRegistry] but still read in their controllers
     *  pending a follow-on — see the registry notes. Read off-tick, so a plain direct→su fallback in the
     *  source, not a [Resolvable] (avoids a resolution-state race with the tick). */
    fun selinuxEnforce(): String? = source.selinuxEnforce()

    /** Raw cpu0 scaling governor — a control read-back routed through the reader (direct→su in the source;
     *  no daemon read verb). Off-tick, so a plain passthrough, not a cached [Resolvable]. */
    fun cpuGovernor(allowRootFallback: Boolean = true): String? = source.cpuGovernor(allowRootFallback)

    /** Raw cpu0 available governors (space-separated). */
    fun cpuAvailableGovernors(allowRootFallback: Boolean = true): String? =
        source.cpuAvailableGovernors(allowRootFallback)

    /**
     * Room air temperature + humidity (CHT8305), or null on panels without the chip. Read off-tick on the
     * heartbeat (not the 2 s perf tick); source selection runs through [roomR] (the helper→Shizuku ladder as
     * a [Resolvable], parsed once at the source), and its own [freshMs] value cache coalesces the two
     * heartbeat consumers (the temp sensor and the humidity sensor) onto one round-trip instead of reading twice.
     */
    fun roomClimate(now: Long = clock()): RoomClimate? = synchronized(lock) {
        roomCachedAt?.let { if (isWithinForwardWindow(now, it, freshMs)) return roomCached }
        roomCached = roomR.read(TickCtx(source), now)
        roomCachedAt = now
        roomCached
    }

    private fun readCoherent(now: Long): Snapshot {
        val ctx = TickCtx(source)

        // CPU busy% vs the single shared baseline. Advance the baseline only on a real read.
        val statLines = statR.read(ctx, now) ?: emptyList()
        val cpuPct = MetricParse.cpuBusyPercents(prevStat, statLines)
        if (statLines.isNotEmpty()) prevStat = statLines
        val cpuOverall = cpuPct.firstOrNull()
        val cpuCores = if (cpuPct.size > 1) cpuPct.drop(1) else emptyList()

        // Memory — /proc/meminfo is world-readable on every panel, always direct (no daemon verb exists).
        val memKb = MetricParse.memKb(source.meminfoText().orEmpty())
        val memUsedMb = memKb?.let { it.first / 1024 } ?: 0L
        val memTotalMb = memKb?.let { it.second / 1024 } ?: 0L
        val memPercent = memKb?.let { (used, total) ->
            if (total > 0) ((used * 100) / total).toInt().coerceIn(0, 100) else null
        }

        val socTempC = tempR.read(ctx, now)
        val (gpuPct, gpuMhz) = MetricParse.gpuLoadFreq(gpuR.read(ctx, now))
        val loadavg = loadR.read(ctx, now) ?: emptyList()

        // CPU frequency — cpufreq sysfs is world-readable on every panel, always direct.
        val freqCurMhz = source.cpuFreqCurMhz()
        val freqMaxMhz = source.cpuFreqMaxMhz()

        return Snapshot(
            ts = now,
            cpuOverall = cpuOverall,
            cpuCores = cpuCores,
            memUsedMb = memUsedMb,
            memTotalMb = memTotalMb,
            memPercent = memPercent,
            socTempC = socTempC,
            gpuPct = gpuPct,
            gpuMhz = gpuMhz,
            loadavg = loadavg,
            freqCurMhz = freqCurMhz,
            freqMaxMhz = freqMaxMhz,
            dump = ctx.dumpOrNull(),
        )
    }

    companion object {
        private const val FRESH_MS = 1500L          // < PerfReader's 2 s tick, so its chart samples fresh + undisturbed
        // Slow retry for a metric that resolved to "no working source": rare (both consumers first read
        // after the daemon is up), and a down daemon's socket refuses instantly so the retry is cheap — but
        // capped well above the tick so a hung/absent daemon isn't re-probed every 2 s. A daemon-connect
        // event could wake these sooner; the periodic retry is the simpler baseline that still recovers.
        private const val UNAVAIL_RETRY_MS = 120_000L
        /** Process-wide shared reader — the one baseline both consumers coordinate through. */
        val shared: PanelMetrics by lazy { PanelMetrics() }
    }
}

/** Which kind of source a strategy reads from — cheap-first ordering is DIRECT < DAEMON < SU/SHIZUKU. */
internal enum class SourceKind { DIRECT, DAEMON, SU, SHIZUKU }

/** One way to read a metric; [read] returns null when that source is unavailable/denied this tick. */
internal class Strategy<out T>(val kind: SourceKind, val read: (TickCtx) -> T?)

/**
 * Per-tick context handed to strategies: exposes the [source] and memoizes the one `PERFDUMP` so multiple
 * daemon-resolved metrics in a single [PanelMetrics.readCoherent] share one round-trip (constraint: one
 * dump per tick). The dump is fetched lazily — only if some metric actually needs the daemon.
 */
internal class TickCtx(val source: MetricSource) {
    private var fetched = false
    private var dump: PerfDump? = null

    /** The tick's PERFDUMP (fetched at most once), or null if the daemon is unreachable. */
    fun dump(): PerfDump? {
        if (!fetched) {
            dump = runCatching { source.perfDump()?.let(MetricParse::parsePerfDump) }.getOrNull()
            fetched = true
        }
        return dump
    }

    /** The dump if one was fetched during this tick, else null — without triggering a fetch. */
    fun dumpOrNull(): PerfDump? = if (fetched) dump else null
}

/**
 * A metric's source-resolution cache. [resolved] is −1 (never resolved), −2 (no working source — retried
 * only every [unavailRetryMs]), or the winning strategy index. Most metrics use the sticky winner because
 * authority availability is stable; a transient-priority ladder can set [stickyWinner] false to rerun its
 * ordered strategies on each read without probing a failed winner twice. All state is mutated only inside
 * [PanelMetrics.readCoherent], under the reader lock, so it needs no separate synchronization.
 */
internal class Resolvable<T : Any>(
    private val unavailRetryMs: Long,
    private val strategies: List<Strategy<T>>,
    private val stickyWinner: Boolean = true,
) {
    private var resolved = -1
    private var lastResolveAt = 0L

    fun read(ctx: TickCtx, now: Long): T? {
        if (!stickyWinner) return reResolve(ctx, now)
        val idx = resolved
        return when {
            idx >= 0 -> strategies[idx].read(ctx) ?: reResolve(ctx, now)   // sticky winner; re-resolve if it fails
            idx == -1 -> reResolve(ctx, now)                                // first read
            // Unavailable: retry once the backoff window has elapsed. "Not still inside the forward window"
            // is the same forward-window clock stance the freshness caches use — a rollback (now < lastResolve)
            // ends the window immediately so a recovered source is retried, never suppressed.
            !isWithinForwardWindow(now, lastResolveAt, unavailRetryMs) -> reResolve(ctx, now)
            else -> null                                                    // unavailable, within backoff
        }
    }

    /** One pass down the ordered strategies; caches the first that yields a value, else marks unavailable. */
    private fun reResolve(ctx: TickCtx, now: Long): T? {
        lastResolveAt = now
        for (i in strategies.indices) {
            val v = strategies[i].read(ctx)
            if (v != null) { resolved = i; return v }
        }
        resolved = -2
        return null
    }
}

/**
 * The one forward-window clock guard for the reader: `then <= now < then + windowMs`. Used for both the
 * value-freshness caches (fresh = still inside the window) and the source-resolution backoff (retry = no
 * longer inside the window). Wall-clock rollback (`now < then`) is an expiry boundary, never a reason to
 * retain stale data or suppress recovery.
 */
private fun isWithinForwardWindow(now: Long, then: Long, windowMs: Long): Boolean =
    now >= then && now - then < windowMs
