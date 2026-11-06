package io.panelassistant.android.http

import io.panelassistant.android.RendererResolver
import io.panelassistant.android.RendererTarget
import io.panelassistant.android.control.BuiltinDashboard
import io.panelassistant.android.dashboard.EntityFilterTelemetry
import io.panelassistant.android.dashboard.DashboardTelemetry
import io.panelassistant.android.dashboard.EntityLearningRuntime
import io.panelassistant.android.metrics.MetricParse
import io.panelassistant.android.metrics.MetricRegistry
import io.panelassistant.android.metrics.MetricSample
import io.panelassistant.android.metrics.OsMetricSource
import io.panelassistant.android.metrics.PanelMetrics
import io.panelassistant.android.metrics.PerfDump
import io.panelassistant.android.metrics.RamRingSink
import io.panelassistant.android.platform.HelperGfxInfo
import io.panelassistant.android.util.HelperClient
import io.panelassistant.android.util.Json
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/**
 * Background performance sampler for the info page. A coroutine ticks every [INTERVAL_MS], reads the
 * system telemetry union from the shared [PanelMetrics] reader (CPU/GPU/RAM/temp/load/freq, with its
 * direct→daemon fallback), and keeps the last [MAX] samples in an in-RAM FIFO ([RamRingSink]) so
 * `GET /perf` returns the latest values **plus** the history — the chart is populated immediately on page
 * load and the series survives page reloads (cleared when the service-owned sampler stops).
 *
 * The heavy per-process metrics that only this page needs — top-5 by CPU/RAM and dashboard render jank — stay
 * PerfReader-local (their deltas and cadence are not shared with Diagnostics). Both come from the helper's
 * PERFDUMP: free from the shared reader's dump when it fetched one this tick (exposed as
 * [io.panelassistant.android.metrics.Snapshot.dump]), otherwise one dump of PerfReader's own on the ticks that
 * need it. They are repeated reads while the page is open, so they never use su; with no helper they are
 * unavailable. Built-in rendering also uses fixed SELF GFXINFO counters for an optional interval jank
 * percentage, without resetting HWUI counters or inferring an interval percentile.
 *
 * CPU % is a delta the reader computes (so the first tick has no value). GPU is Rockchip Mali devfreq load
 * ("<load>@<freq>Hz"); absent on panels without it.
 */
object PerfReader {
    private data class RendererIdentity(
        val target: RendererTarget? = null,
        val ownPackage: String = "",
    )

    private const val MAX = 120          // ~4 min at 2s
    private const val INTERVAL_MS = 2000L
    private const val ACTIVE_MS = 30_000L // sample only within this window of the last page view

    // Internal history keys (also the future store's schema — see MetricRegistry). The /perf JSON still
    // exposes them as the stable "cpu"/"ram"/"gpu" fields the chart expects.
    private val CPU_KEY = MetricRegistry.CPU.key
    private val RAM_KEY = MetricRegistry.MEM.key
    private val GPU_KEY = MetricRegistry.GPU_LOAD.key

    private val lock = Any()
    // History retention behind the MetricSink seam (a durable store swaps in without touching this class).
    private val sink = RamRingSink(MAX)
    @Volatile private var latestFields = EMPTY_FIELDS
    @Volatile private var latestCpu: Int? = null

    // Top-5 process rankings reuse one /proc snapshot on a slower cadence than the 2s chart. CPU is the
    // existing interval delta; RAM is current resident footprint (RSS, including shared pages).
    // "null" means the privileged snapshot or (for RAM) the helper's appended RSS field is unavailable.
    @Volatile private var topJson = "null"
    @Volatile private var topRamJson = "null"
    // The active dashboard renderer identity, an immutable snapshot the service resolves off the sampling
    // path (no PackageManager here) and installs under generation admission / updates at the applied-
    // config boundary / clears on stop. One value carries both the label attribution package
    // ([RendererResolver.attributionOf], built-in → our own package) AND whether the renderer is the
    // built-in WebView (gates the TTI + reload self-measurement, which a foreign Companion can't report).
    // Captured exactly once per diagnostic operation so a projection can't straddle a reconfigure.
    @Volatile private var rendererIdentity = RendererIdentity()
    @Volatile private var renderJson = "null"
    private val stutterHist = ArrayDeque<Int>()   // CrRendererMain %-of-one-core per render window
    private var prevRenderJiffies = HashMap<Int, Long>() // renderer pid -> CrRendererMain utime+stime
    private var prevGfx: Pair<RendererIdentity, HelperGfxInfo>? = null
    private var prevRenderAt = 0L
    private var tickCount = 0
    private var prevTopTotal = 0L                 // /proc/stat aggregate jiffies at last top sample
    private var prevProc = HashMap<Int, Long>()   // pid -> utime+stime jiffies at last top sample
    private var prevComm = HashMap<Int, String>() // pid -> comm at last top sample, to spot a reused pid
    private var prevSelf = 0L                     // own jiffies incl. reaped children at last top sample
    // Fetches a helper PERFDUMP for a tick whose shared snapshot carried none; tests install a fake.
    @Volatile private var helperDump: () -> PerfDump? = ::fetchHelperDump
    @Volatile private var helperGfx: () -> HelperGfxInfo? = { HelperClient.gfxInfo() }

    // Page-view gate. Instrumentation is the tool, not a tax: it must not be the panel's biggest CPU
    // consumer 24/7. So sampling runs only while the info page has been fetched within [ACTIVE_MS];
    // otherwise the loop just compares a timestamp and sleeps. The page-view gate is the sole cost control.
    @Volatile private var lastAccessAt = 0L
    private val lifecycleLock = Any()
    @Volatile private var generation = 0L
    private var nextGeneration = 0L
    private var samplerJob: Job? = null

    /** Mark the perf page as being viewed; sampling stays live for [ACTIVE_MS] after the last call. */
    fun touch() { lastAccessAt = android.os.SystemClock.elapsedRealtime() }

    /** Start one owned sampling generation on [scope], installing the initial renderer-target snapshot
     *  ([ownPackage] + [initialTarget]) atomically inside generation admission. */
    internal fun start(
        scope: CoroutineScope,
        ownPackage: String = "",
        initialTarget: RendererTarget? = null,
    ) = startOwned(
        scope = scope,
        ownPackage = ownPackage,
        initialTarget = initialTarget,
        perfDump = ::fetchHelperDump,
        gfxInfo = { HelperClient.gfxInfo() },
        elapsedRealtime = { android.os.SystemClock.elapsedRealtime() },
        pause = { delay(it) },
    )

    internal fun startForTest(
        scope: CoroutineScope,
        perfDump: () -> PerfDump?,
        elapsedRealtime: () -> Long,
        pause: suspend (Long) -> Unit,
        ownPackage: String = "",
        initialTarget: RendererTarget? = null,
        gfxInfo: () -> HelperGfxInfo? = { null },
    ) = startOwned(scope, ownPackage, initialTarget, perfDump, gfxInfo, elapsedRealtime, pause)

    /** Update the renderer-target snapshot at the applied-configuration boundary. Generation-guarded so a
     *  call before start or after stop is a no-op — a dead/rejected generation can never publish. */
    internal fun updateRendererTarget(target: RendererTarget?) {
        synchronized(lifecycleLock) {
            if (generation != 0L) rendererIdentity = rendererIdentity.copy(target = target)
        }
    }

    internal fun touchForTest(atMs: Long) {
        lastAccessAt = atMs
    }

    private fun startOwned(
        scope: CoroutineScope,
        ownPackage: String,
        initialTarget: RendererTarget?,
        perfDump: () -> PerfDump?,
        gfxInfo: () -> HelperGfxInfo?,
        elapsedRealtime: () -> Long,
        pause: suspend (Long) -> Unit,
    ) {
        val runGeneration = synchronized(lifecycleLock) {
            if (generation != 0L) return
            resetStateLocked()
            helperDump = perfDump
            helperGfx = gfxInfo
            (++nextGeneration).also {
                generation = it
                // Install the identity snapshot as part of admission, so a rejected duplicate start
                // (the early return above) can never install one.
                rendererIdentity = RendererIdentity(initialTarget, ownPackage)
            }
        }
        val candidate = scope.launch {
            while (isActive && isCurrent(runGeneration)) {
                val now = elapsedRealtime()
                if (withinActiveWindow(now, lastAccessAt)) {
                    runCatching { tick(runGeneration, now) }
                } else {
                    ifCurrent(runGeneration) { resetLocalBaselines() }
                }
                pause(INTERVAL_MS)
            }
        }
        synchronized(lifecycleLock) {
            if (generation == runGeneration) samplerJob = candidate else candidate.cancel()
        }
        candidate.invokeOnCompletion {
            synchronized(lifecycleLock) {
                if (samplerJob === candidate) {
                    samplerJob = null
                    if (generation == runGeneration) generation = 0L
                }
            }
        }
    }

    /** Cancel the owned loop and remove values that would otherwise survive a service recreation. */
    fun stop() {
        val oldJob = synchronized(lifecycleLock) {
            generation = 0L
            resetStateLocked()
            samplerJob.also { samplerJob = null }
        }
        oldJob?.cancel()
    }

    /** Caller holds [lifecycleLock], so a terminal reset cannot interleave with a generation mutation. */
    private fun resetStateLocked() {
        lastAccessAt = 0L
        tickCount = 0
        rendererIdentity = RendererIdentity()
        helperDump = ::fetchHelperDump
        helperGfx = { HelperClient.gfxInfo() }
        resetLocalBaselines()
        synchronized(lock) {
            sink.clear()
            latestFields = EMPTY_FIELDS
            latestCpu = null
            topJson = "null"
            topRamJson = "null"
            renderJson = "null"
            stutterHist.clear()
        }
    }

    private fun isCurrent(candidate: Long): Boolean = generation == candidate

    internal fun lifecycleGeneration(): Long = generation

    internal fun rendererTargetForTest(): RendererTarget? = rendererIdentity.target

    private inline fun <T : Any> ifCurrent(candidate: Long, action: () -> T): T? =
        synchronized(lifecycleLock) { if (generation == candidate) action() else null }

    internal fun withinActiveWindow(now: Long, lastAccess: Long): Boolean =
        lastAccess > 0L && now >= lastAccess && now - lastAccess < ACTIVE_MS

    /** Clear PerfReader's OWN top/render delta baselines while idle. The shared /proc/stat baseline lives
     *  in [PanelMetrics] and is deliberately NOT reset here (a stale prev just yields one valid
     *  longer-window CPU% on the next read, never a bogus delta). */
    private fun resetLocalBaselines() {
        prevGfx = null
        resetPerfDumpBaselines()
    }

    private fun resetPerfDumpBaselines() {
        prevTopTotal = 0L; prevSelf = 0L
        prevProc = HashMap(); prevComm = HashMap(); prevRenderJiffies = HashMap(); prevRenderAt = 0L
    }

    /** Latest sample + history FIFO + top-5 procs + render jank, as JSON, for `GET /perf`. */
    fun json(): String {
        // Capture the renderer identity exactly once for this operation so the builtin-gated blocks below
        // can't observe two different configurations mid-render.
        val identity = rendererIdentity
        val builtin = identity.target is RendererTarget.Builtin
        val sampled = synchronized(lock) {
            """{"enabled":true,$latestFields,"top":$topJson,"topRam":$topRamJson,"render":$renderJson,"builtin":${builtinJson(builtin)},"network":${networkJson()},"entityFilter":${EntityFilterTelemetry.json()},"hist":{"cpu":${histInts(CPU_KEY)},"ram":${histInts(RAM_KEY)},"gpu":${histInts(GPU_KEY)}}}"""
        }
        // Dashboard diagnostics allocate JSON. Keep the projection outside the sampler lock so
        // diagnostics readers cannot delay history publication on low-end panels. Feature costs are
        // intentionally available only through /perf/costs; the regularly polled Info payload must not
        // serialize and transfer the full engineering operation table on every refresh.
        return sampled.dropLast(1) + ""","dashboard":${dashboardJson(builtin)}}"""
    }

    private fun dashboardJson(builtin: Boolean): String {
        val (filterActive, entityCount) = EntityFilterTelemetry.dashboardFilterState()
        val rendererPct = synchronized(lock) {
            runCatching { JSONObject(renderJson).optDouble("mainPct").takeUnless(Double::isNaN) }.getOrNull()
        }
        val reloads = if (builtin) {
            BuiltinDashboard.rendererPerf(android.os.SystemClock.elapsedRealtime()).reloads24h
        } else 0
        val top = runCatching { JSONArray(EntityLearningRuntime.performanceSummaryJson()) }.getOrDefault(JSONArray())
        // One read of the one network-path owner; only a degraded verdict is passed, so the
        // classifier sees null for healthy and not-measured alike.
        val networkPath = io.panelassistant.android.sensors.HaNetworkPathRuntime.snapshot()
            ?.takeIf { it.degraded }?.severity?.wireValue
        return DashboardTelemetry.json(
            builtinActive = builtin,
            filterActive = filterActive,
            entityCount = entityCount,
            reloads24h = reloads,
            systemCpuPct = latestCpu,
            rendererMainPct = rendererPct,
            topEntities = top,
            networkPath = networkPath,
        )
    }

    /** Absolute host-UID counters; harvesters take arm start/end deltas. -1 means unsupported. */
    private fun networkJson(): String {
        val uid = android.os.Process.myUid()
        return "{\"uidRxBytes\":${android.net.TrafficStats.getUidRxBytes(uid)}," +
            "\"uidTxBytes\":${android.net.TrafficStats.getUidTxBytes(uid)}}"
    }

    /** Built-in renderer responsiveness (time-to-interactive + 24h involuntary-reload count) for the perf
     *  card, or `null` when the active renderer isn't the built-in WebView. -1 fields = not yet captured. */
    private fun builtinJson(builtin: Boolean): String {
        if (!builtin) return "null"
        val p = BuiltinDashboard.rendererPerf(android.os.SystemClock.elapsedRealtime())
        return """{"ttiColdMs":${p.coldTtiMs},"ttiWarmMedianMs":${p.warmTtiMedianMs},"reloads24h":${p.reloads24h}}"""
    }

    private fun histInts(key: String): List<Int> = sink.history(key).map { it.num?.toInt() ?: 0 }

    /** Process names (full cmdlines) of the latest top-by-CPU sample, most-active first — for the tame
     *  picker's "using the most CPU" group. Empty until the sampler has produced a ranking. */
    fun topNames(): List<String> = synchronized(lock) {
        Regex(""""name":"(.*?)"""").findAll(topJson).map { it.groupValues[1] }.toList()
    }

    /**
     * Advance the per-renderer jiffy-delta baseline from the current CrRendererMain jiffies and return the
     * busiest main-thread %-of-one-core over the interval, or -1.0 when there's no prior baseline / no
     * renderer (first sample or nothing running), for [sampleRenderDump]. The caller holds the generation guard, so mutating [prevRenderJiffies]/[prevRenderAt] here is safe.
     */
    private fun advanceRenderDelta(cur: Map<Int, Long>, now: Long): Double {
        var mainPct = -1.0
        for ((pid, j) in cur) {
            val prev = prevRenderJiffies[pid]
            if (prev != null && prevRenderAt > 0L) {
                val dt = (now - prevRenderAt) / 1000.0
                if (dt > 0) { val p = (j - prev) / dt; if (p > mainPct) mainPct = p }
            }
        }
        prevRenderJiffies = HashMap(cur)
        prevRenderAt = now
        return mainPct
    }

    /** The single "no Chromium renderer" render payload (first sample or nothing running). Read under
     *  [lock] by the caller, since [stutterHist] is shared state. */
    private fun noRendererJson(): String = "{\"status\":\"no-renderer\",\"hist\":${stutterHist.toList()}}"

    internal fun rankCpuProcesses(
        current: Map<Int, Long>,
        previous: Map<Int, Long>,
        limit: Int = 5,
    ): List<Pair<Int, Long>> = current.entries
        .mapNotNull { (pid, jiffies) -> previous[pid]?.let { pid to (jiffies - it) } }
        .filter { it.second > 0L }
        .sortedWith(compareByDescending<Pair<Int, Long>> { it.second }.thenBy { it.first })
        .take(limit)

    internal fun rankRamProcesses(rssPages: Map<Int, Long>, limit: Int = 5): List<Pair<Int, Long>> =
        rssPages.entries
            .filter { it.value > 0L }
            .sortedWith(compareByDescending<Map.Entry<Int, Long>> { it.value }.thenBy { it.key })
            .take(limit)
            .map { it.key to it.value }

    /** Familiar UI units with one decimal, while retaining the kernel's exact page count internally. */
    internal fun rssMb(pages: Long, pageSizeBytes: Long): Double =
        Math.round(
            pages.coerceAtLeast(0L).toDouble() * pageSizeBytes.coerceAtLeast(1L).toDouble() * 10.0 /
                (1024.0 * 1024.0),
        ) / 10.0

    private fun pageSizeBytes(): Long = runCatching {
        android.system.Os.sysconf(android.system.OsConstants._SC_PAGESIZE)
    }.getOrNull()?.takeIf { it > 0L } ?: 4096L

    /**
     * Rank + publish the top-5. ha-paneld ranks like any other process — with the built-in renderer the
     * WebView's browser side (compositing, service threads) genuinely runs in this process, so it IS
     * panel workload and hiding it would lie; on a builtin panel its row is labelled "(built-in
     * dashboard)" so the reading is obvious. What's kept OUT of the ranking is the measurement itself:
     * the su/dumpsys/cat probes this page spawns are reaped children of this process, so their exact
     * cost (`cutime+cstime` from `/proc/self/stat`) is shown as a separate, dimmed "sampling probes"
     * row — the observer's overhead, attributed honestly instead of polluting the workload list.
     */
    private fun publishTop(
        runGeneration: Long,
        cur: Map<Int, Long>,
        rssPages: Map<Int, Long>,
        comm: Map<Int, String>,
        total: Long,
    ) {
        val identity = rendererIdentity
        val myPid = android.os.Process.myPid()
        val kidsNow = runCatching { java.io.File("/proc/self/stat").readText() }.getOrNull()?.let { childJiffiesOf(it) }
        var dTotal = 0L
        var cpuRanked = emptyList<Pair<Int, Long>>()
        var ramRanked = emptyList<Pair<Int, Long>>()
        var probePct: Double? = null
        ifCurrent(runGeneration) {
            dTotal = total - prevTopTotal
            // A pid that vanished or changed comm since the last sample is a different process: give it no
            // interval delta against the dead process's counter.
            val sameProcess = { pid: Int -> comm[pid]?.let(::stableComm) == prevComm[pid]?.let(::stableComm) }
            cpuRanked = if (prevTopTotal != 0L && dTotal > 0) {
                rankCpuProcesses(cur.filterKeys(sameProcess), prevProc)
            } else emptyList()
            ramRanked = rankRamProcesses(rssPages)
            probePct = if (kidsNow != null && prevSelf > 0L && dTotal > 0) {
                Math.round((kidsNow - prevSelf) * 1000.0 / dTotal) / 10.0
            } else null
            prevTopTotal = total
            prevProc = HashMap(cur)
            prevComm = HashMap(comm)
            if (kidsNow != null) prevSelf = kidsNow
        } ?: return

        ifCurrent(runGeneration) {
            val attributionPkg = RendererResolver.attributionOf(identity.target, identity.ownPackage)
            fun processName(pid: Int): String {
                var name = trimProcName(comm[pid] ?: pid.toString())
                if (pid == myPid) name = if (attributionPkg == name) "built-in dashboard (Panel Assistant)" else "$name (this app)"
                return name
            }
            val cpuRows = cpuRanked.map { (pid, delta) ->
                val pct = Math.round(delta * 1000.0 / dTotal) / 10.0
                """{"name":${Json.str(processName(pid))},"cpu":$pct}"""
            }.toMutableList()
            probePct?.let { cpuRows += """{"name":"sampling probes (su/dumpsys)","cpu":$it,"self":true}""" }
            val pageBytes = pageSizeBytes()
            val ramRows = ramRanked.map { (pid, pages) ->
                """{"name":${Json.str(processName(pid))},"ramMb":${rssMb(pages, pageBytes)}}"""
            }
            synchronized(lock) {
                if (cpuRows.isNotEmpty()) topJson = cpuRows.joinToString(",", "[", "]")
                // Unlike CPU deltas, RSS can disappear when an older helper replaces a newer one. Clear
                // the prior ranking immediately rather than presenting stale memory data as current.
                topRamJson = if (ramRows.isEmpty()) "null" else ramRows.joinToString(",", "[", "]")
            }
        }
    }

    /** Reaped-children jiffies (cutime+cstime) from a `/proc/self/stat` line — the measurement probes
     *  this process spawns and reaps. Null on a malformed line. */
    internal fun childJiffiesOf(statLine: String): Long? {
        val rest = MetricParse.statFieldsAfterComm(statLine) ?: return null
        rest.getOrNull(12)?.toLongOrNull() ?: return null // require a well-formed line through stime
        val cu = rest.getOrNull(13)?.toLongOrNull() ?: return null
        val cs = rest.getOrNull(14)?.toLongOrNull() ?: return null
        return cu + cs
    }

    /** Since Linux 4.18 a kernel worker's comm carries its current workqueue after a '+' (busy) or '-'
     *  (idle), e.g. `kworker/u8:2+events_unbound`. Drop that suffix so the same worker still matches itself
     *  when it flips between samples. */
    private fun stableComm(comm: String): String {
        if (!comm.startsWith("kworker/")) return comm
        val cut = comm.indexOfAny(charArrayOf('+', '-'), startIndex = "kworker/".length)
        return if (cut < 0) comm else comm.substring(0, cut)
    }

    /** Shorten Android "package:process:classname" cmdlines to just the package component — the extra
     *  segments (Chromium sandbox class names, sub-process suffixes) add noise without aiding diagnosis. */
    private fun trimProcName(raw: String): String =
        if (raw.count { it == ':' } >= 2) raw.substringBefore(':') else raw

    private fun push(q: ArrayDeque<Int>, v: Int) {
        q.addLast(v)
        while (q.size > MAX) q.removeFirst()
    }

    // --- helper-sourced top/render, from a PERFDUMP ------------------------------------------------

    private val helperSource by lazy { OsMetricSource() }

    private fun fetchHelperDump(): PerfDump? = helperSource.perfDump()?.let(MetricParse::parsePerfDump)

    /** Top-5 by CPU/RAM from the daemon's process table (name is supplied by the helper). */
    private fun sampleTopDump(runGeneration: Long, dump: PerfDump) {
        val total = dump.stat.firstOrNull()?.sum() ?: return       // "cpu" aggregate line
        val cur = HashMap<Int, Long>()
        val rssPages = HashMap<Int, Long>()
        val comm = HashMap<Int, String>()
        for (process in dump.proc) {
            cur[process.pid] = process.jiffies
            process.rssPages?.let { rssPages[process.pid] = it }
            comm[process.pid] = process.name
        }
        publishTop(runGeneration, cur, rssPages, comm, total)
    }

    /** Cumulative HWUI counts only form an interval within one reset epoch and renderer identity. */
    private fun advanceGfx(identity: RendererIdentity, current: HelperGfxInfo?): Double? {
        val previous = prevGfx
        prevGfx = current?.let { identity to it }
        if (current == null || previous == null || previous.first !== identity) return null
        val old = previous.second
        if (current.since != old.since || current.total < old.total || current.janky < old.janky) return null
        val frames = current.total - old.total
        val janky = current.janky - old.janky
        if (frames <= 0L || janky > frames) return null
        return Math.round(janky * 1000.0 / frames) / 10.0
    }

    /** Read callbacks run outside the lifecycle lock. Admit both baselines and publication together. */
    private fun sampleRenderDump(
        runGeneration: Long, dump: PerfDump?, now: Long, identity: RendererIdentity, gfx: HelperGfxInfo?,
    ) = synchronized(lifecycleLock) {
        if (generation != runGeneration || rendererIdentity !== identity) return@synchronized
        // Seed on the first cycle, before the CPU delta's first-sample early return.
        val jankPct = advanceGfx(identity, gfx)
        if (dump == null) {
            resetPerfDumpBaselines()
            synchronized(lock) {
                topJson = "null"; topRamJson = "null"; renderJson = "null"; stutterHist.clear()
            }
            return@synchronized
        }
        val mainPct = advanceRenderDelta(dump.rend, now)
        if (mainPct < 0) {
            synchronized(lock) { renderJson = noRendererJson() }
            return@synchronized
        }
        val pct1 = Math.round(mainPct.coerceIn(0.0, 100.0) * 10) / 10.0
        val verdict = if (pct1 < 50) "smooth" else if (pct1 < 85) "occasional" else "janky"
        synchronized(lock) {
            push(stutterHist, Math.round(pct1).toInt())
            val jank = jankPct?.let { ",\"jankPct\":$it" } ?: ""
            renderJson = "{\"pkg\":\"dashboard\",\"mainPct\":$pct1,\"verdict\":\"$verdict\",\"hist\":${stutterHist.toList()}$jank}"
        }
    }

    private fun tick(runGeneration: Long, now: Long) {
        val snap = PanelMetrics.shared.systemSnapshot()
        if (!isCurrent(runGeneration)) return
        // Top-5 + render jank (PerfReader-only) from ONE helper PERFDUMP per ~10s cycle: the shared
        // reader's when it fetched one this tick, else one of our own. The dump reads every process's
        // stat, which on a slow SoC is itself a visible slice of panel CPU, so the 2s chart above is
        // untouched and only top-5/render run at this cadence. Never su: with no helper they stay null.
        val phase = ifCurrent(runGeneration) { tickCount % 5 } ?: return
        if (phase == 0) {
            val identity = rendererIdentity
            val fetch = helperDump
            val fetchGfx = helperGfx
            val dump = snap.dump ?: runCatching(fetch).getOrNull()
            // SELF is meaningful only for our built-in renderer; a foreign target never borrows it.
            val gfx = if (identity.target is RendererTarget.Builtin) runCatching(fetchGfx).getOrNull() else null
            if (dump != null) runCatching { sampleTopDump(runGeneration, dump) }
            sampleRenderDump(runGeneration, dump, now, identity, gfx)
        }
        ifCurrent(runGeneration) { tickCount++ } ?: return

        val projection = projectSnapshot(snap)
        ifCurrent(runGeneration) {
            synchronized(lock) {
                projection.samples.forEach(sink::record)
                latestFields = projection.fields
                latestCpu = snap.cpuOverall
            }
        }
    }

    internal data class Projection(val fields: String, val samples: List<MetricSample>)

    /** Keep independent metrics independent, and omit unavailable values from history instead of writing zero. */
    internal fun projectSnapshot(snap: io.panelassistant.android.metrics.Snapshot): Projection {
        val loadJson = snap.loadavg.joinToString(",") { "\"$it\"" }
        val memAvailable = snap.memTotalMb > 0
        val samples = buildList {
            snap.cpuOverall?.let { add(MetricSample.num(CPU_KEY, it.toDouble(), snap.ts, "%")) }
            snap.memPercent?.let { add(MetricSample.num(RAM_KEY, it.toDouble(), snap.ts, "%")) }
            if (snap.gpuPct >= 0) add(MetricSample.num(GPU_KEY, snap.gpuPct.toDouble(), snap.ts, "%"))
        }
        val fields = """"cpu":${snap.cpuOverall ?: "null"},"cores":${snap.cpuCores},"load":[$loadJson],"freqMhz":${snap.freqCurMhz},"freqMaxMhz":${snap.freqMaxMhz},""" +
            """"gpu":${if (snap.gpuPct >= 0) snap.gpuPct else "null"},"gpuMhz":${snap.gpuMhz},""" +
            """"tempC":${snap.socTempC ?: "null"},"memUsedMb":${if (memAvailable) snap.memUsedMb else "null"},"memTotalMb":${if (memAvailable) snap.memTotalMb else "null"}"""
        return Projection(fields, samples)
    }

    private const val EMPTY_FIELDS = """"cpu":null,"cores":[],"load":[],"freqMhz":[],"freqMaxMhz":0,"gpu":null,"gpuMhz":0,"tempC":null,"memUsedMb":null,"memTotalMb":null"""
}
