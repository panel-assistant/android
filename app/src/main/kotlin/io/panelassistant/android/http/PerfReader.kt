package io.panelassistant.android.http

import io.panelassistant.android.RendererResolver
import io.panelassistant.android.RendererTarget
import io.panelassistant.android.control.BuiltinDashboard
import io.panelassistant.android.control.Su
import io.panelassistant.android.dashboard.EntityFilterTelemetry
import io.panelassistant.android.dashboard.DashboardTelemetry
import io.panelassistant.android.dashboard.EntityLearningRuntime
import io.panelassistant.android.metrics.MetricParse
import io.panelassistant.android.metrics.MetricRegistry
import io.panelassistant.android.metrics.MetricSample
import io.panelassistant.android.metrics.PanelMetrics
import io.panelassistant.android.metrics.PerfDump
import io.panelassistant.android.metrics.RamRingSink
import io.panelassistant.android.metrics.FeatureCostOperation
import io.panelassistant.android.metrics.FeatureCostOutcome
import io.panelassistant.android.metrics.FeatureCosts
import io.panelassistant.android.util.AndroidInput
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
 * PerfReader-local (their deltas + su cadence are not shared with Diagnostics). On sandbox panels those
 * come free from the shared reader's PERFDUMP (exposed as [io.panelassistant.android.metrics.Snapshot.dump]),
 * so no second dump is fetched; on rooted panels they're separate `su` calls spread across ticks.
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
    // config boundary / clears on stop. One value carries both the gfxinfo/label attribution package
    // ([RendererResolver.attributionOf], built-in → our own package) AND whether the renderer is the
    // built-in WebView (gates the TTI + reload self-measurement, which a foreign Companion can't report).
    // Captured exactly once per diagnostic operation so a projection can't straddle a reconfigure.
    @Volatile private var rendererIdentity = RendererIdentity()
    @Volatile private var renderJson = "null"
    private val stutterHist = ArrayDeque<Int>()   // CrRendererMain %-of-one-core per render window
    private var prevRenderJiffies = HashMap<Int, Long>() // renderer pid -> CrRendererMain utime+stime
    private var prevRenderAt = 0L
    private var rootOk = false
    private var tickCount = 0
    private var prevTopTotal = 0L                 // /proc/stat aggregate jiffies at last top sample
    private var prevProc = HashMap<Int, Long>()   // pid -> utime+stime jiffies at last top sample
    private var prevSelf = 0L                     // own jiffies incl. reaped children at last top sample
    private val nameCache = HashMap<Int, String>() // pid -> cmdline (fetched once; pids recycle rarely)

    // Page-view gate. Instrumentation is the tool, not a tax: it must not be the panel's biggest CPU
    // consumer 24/7. So sampling runs only while the info page has been fetched within [ACTIVE_MS];
    // otherwise the loop just compares a timestamp and sleeps. [enabled] is always true (the old master
    // switch was removed — the page-view gate is the sole cost control).
    @Volatile var enabled = true
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
        rootAvailable = { Su.availableIsolated() },
        elapsedRealtime = { android.os.SystemClock.elapsedRealtime() },
        pause = { delay(it) },
    )

    internal fun startForTest(
        scope: CoroutineScope,
        rootAvailable: () -> Boolean,
        elapsedRealtime: () -> Long,
        pause: suspend (Long) -> Unit,
        ownPackage: String = "",
        initialTarget: RendererTarget? = null,
    ) = startOwned(scope, ownPackage, initialTarget, rootAvailable, elapsedRealtime, pause)

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
        rootAvailable: () -> Boolean,
        elapsedRealtime: () -> Long,
        pause: suspend (Long) -> Unit,
    ) {
        val runGeneration = synchronized(lifecycleLock) {
            if (generation != 0L) return
            resetStateLocked()
            (++nextGeneration).also {
                generation = it
                // Install the identity snapshot as part of admission, so a rejected duplicate start
                // (the early return above) can never install one.
                rendererIdentity = RendererIdentity(initialTarget, ownPackage)
            }
        }
        val candidate = scope.launch {
            var rootProbed = false
            while (isActive && isCurrent(runGeneration)) {
                val now = elapsedRealtime()
                if (enabled && withinActiveWindow(now, lastAccessAt)) {
                    if (!rootProbed) {
                        val available = runCatching(rootAvailable).getOrDefault(false)
                        ifCurrent(runGeneration) { rootOk = available } ?: return@launch
                        rootProbed = true
                    }
                    runCatching { tick(runGeneration) }
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
        rootOk = false
        tickCount = 0
        rendererIdentity = RendererIdentity()
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
        prevTopTotal = 0L; prevSelf = 0L
        prevProc = HashMap(); prevRenderJiffies = HashMap(); prevRenderAt = 0L
        nameCache.clear()
    }

    /** Latest sample + history FIFO + top-5 procs + render jank, as JSON, for `GET /perf`. */
    fun json(): String {
        // Capture the renderer identity exactly once for this operation so the builtin-gated blocks below
        // can't observe two different configurations mid-render.
        val identity = rendererIdentity
        val builtin = identity.target is RendererTarget.Builtin
        val sampled = synchronized(lock) {
            """{"enabled":$enabled,$latestFields,"top":$topJson,"topRam":$topRamJson,"render":$renderJson,"builtin":${builtinJson(builtin)},"network":${networkJson()},"entityFilter":${EntityFilterTelemetry.json()},"hist":{"cpu":${histInts(CPU_KEY)},"ram":${histInts(RAM_KEY)},"gpu":${histInts(GPU_KEY)}}}"""
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
     * renderer (first sample or nothing running). Shared by the direct-su [sampleRender] and the daemon
     * [sampleRenderDump] consumers — same delta shape, each keeping its OWN sampling cadence. The caller
     * holds the generation guard, so mutating [prevRenderJiffies]/[prevRenderAt] here is safe.
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

    /**
     * Dashboard responsiveness. PRIMARY = the WebView renderer's main-thread CPU (`CrRendererMain`,
     * via /proc/<renderer>/task/<tid>/stat) as %-of-one-core: this is the thread the HA frontend
     * processes the WebSocket state firehose on, so it saturates (~100%) when event handling falls
     * behind *even with zero rendering* — the common no-video overload that `dumpsys gfxinfo` jank
     * misses entirely. gfxinfo jank is kept as a SECONDARY "rendering load" field (only meaningful with
     * video/animation, e.g. a camera card). Needs root. HZ assumed 100 (Android default).
     */
    private fun sampleRender(runGeneration: Long) {
        val identity = rendererIdentity
        // Primary: busiest CrRendererMain %-of-one-core across WebView renderer processes (covers a
        // Companion *or* a browser dashboard). One su call emits "<pid> <stat>" per renderer main thread.
        // Shell `read` builtins instead of a `cat` per thread: the old form forked ~2 processes per
        // renderer thread every sample — dozens of execs on a slow SoC — for what a redirect does free.
        val cmd = "ps -A -o PID,NAME 2>/dev/null | grep -i sandboxe | while read pid name; do " +
            "for t in /proc/\$pid/task/*; do IFS= read -r c < \$t/comm 2>/dev/null || continue; " +
            "if [ \"\$c\" = CrRendererMain ]; then IFS= read -r s < \$t/stat 2>/dev/null && echo \"\$pid \$s\"; fi; done; done; true"
        val out = rootDiagnostic(cmd, 2L * 1024L * 1024L)
        val now = android.os.SystemClock.elapsedRealtime()
        val parsed = HashMap<Int, Long>()
        if (out != null) for (line in out.lineSequence()) {
            val sp = line.indexOf(' ')
            if (sp <= 0) continue
            val pid = line.substring(0, sp).trim().toIntOrNull() ?: continue
            val rest = MetricParse.statFieldsAfterComm(line.substring(sp + 1)) ?: continue // utime=11,stime=12
            val ut = rest.getOrNull(11)?.toLongOrNull() ?: continue
            val st = rest.getOrNull(12)?.toLongOrNull() ?: 0
            parsed[pid] = ut + st
        }
        val mainPct = ifCurrent(runGeneration) { advanceRenderDelta(parsed, now) } ?: return
        if (mainPct < 0) { // first sample, or no Chromium renderer running
            ifCurrent(runGeneration) {
                synchronized(lock) { renderJson = noRendererJson() }
            }
            return
        }
        val pct1 = Math.round(mainPct.coerceIn(0.0, 100.0) * 10) / 10.0
        val verdict = if (pct1 < 50) "smooth" else if (pct1 < 85) "occasional" else "janky"

        // Secondary: gfxinfo jank (rendering load — only meaningful with video/animation). Optional.
        var jankFields = ""
        val pkg = RendererResolver.attributionOf(identity.target, identity.ownPackage)
            .takeIf(AndroidInput::isPackage).orEmpty()
        if (pkg.isNotEmpty()) {
            val g = rootDiagnostic(
                "dumpsys gfxinfo $pkg; dumpsys gfxinfo $pkg reset >/dev/null 2>&1; true",
                1L * 1024L * 1024L,
            )
            if (g != null) {
                val tot = Regex("""Total frames rendered:\s*(\d+)""").find(g)?.groupValues?.get(1)?.toLongOrNull() ?: 0
                if (tot > 0) {
                    val janky = Regex("""Janky frames:\s*(\d+)""").find(g)?.groupValues?.get(1)?.toLongOrNull() ?: 0
                    val p99 = Regex("""99th percentile:\s*(\d+)ms""").find(g)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                    jankFields = ",\"jankPct\":${Math.round(janky * 1000.0 / tot) / 10.0},\"p99\":$p99"
                }
            }
        }
        ifCurrent(runGeneration) {
            synchronized(lock) {
                push(stutterHist, Math.round(pct1).toInt())
                renderJson = "{\"pkg\":\"${pkg.ifBlank { "dashboard" }}\",\"mainPct\":$pct1,\"verdict\":\"$verdict\"" +
                    jankFields + ",\"hist\":${stutterHist.toList()}}"
            }
        }
    }

    /**
     * Top-5 processes by CPU, computed from `/proc/[pid]/stat` deltas over the sample interval (what
     * `top` does) — `dumpsys cpuinfo` was tried first but returns a cached snapshot that only the
     * system refreshes (minutes apart), so it never advances. Needs root to read other pids' stat
     * (`/proc` is `hidepid`). CPU is expressed as % of total capacity (sums to <=100 across procs,
     * consistent with the overall CPU figure). Full names come from `/proc/<pid>/cmdline`.
     */
    private fun sampleTop(runGeneration: Long) {
        // `; true` so a vanished-pid `cat` (non-zero) doesn't null the whole capture.
        val out = rootDiagnostic(
            "cat /proc/stat; echo @@; cat /proc/[0-9]*/stat 2>/dev/null; true",
            4L * 1024L * 1024L,
        ) ?: return
        val parts = out.split("@@")
        if (parts.size < 2) return
        val cpuLine = parts[0].lineSequence().firstOrNull { it.startsWith("cpu ") } ?: return
        val total = cpuLine.trim().split(Regex("\\s+")).drop(1).mapNotNull { it.toLongOrNull() }.sum()

        val cur = HashMap<Int, Long>()
        val rssPages = HashMap<Int, Long>()
        val comm = HashMap<Int, String>()
        for (line in parts[1].lineSequence()) {
            val lp = line.indexOf('('); val rp = line.lastIndexOf(')')
            if (lp <= 0 || rp < lp) continue
            val pid = line.substring(0, lp).trim().toIntOrNull() ?: continue
            val rest = MetricParse.statFieldsAfterComm(line) ?: continue // utime=rest[11], stime=rest[12]
            val utime = rest.getOrNull(11)?.toLongOrNull() ?: continue
            val stime = rest.getOrNull(12)?.toLongOrNull() ?: continue
            cur[pid] = utime + stime
            rest.getOrNull(21)?.toLongOrNull()?.takeIf { it >= 0L }?.let { rssPages[pid] = it }
            comm[pid] = line.substring(lp + 1, rp)
        }
        publishTop(runGeneration, cur, rssPages, comm, total, resolveFullNames = true)
    }

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
        resolveFullNames: Boolean,
    ) {
        val identity = rendererIdentity
        val myPid = android.os.Process.myPid()
        val kidsNow = runCatching { java.io.File("/proc/self/stat").readText() }.getOrNull()?.let { childJiffiesOf(it) }
        var dTotal = 0L
        var cpuRanked = emptyList<Pair<Int, Long>>()
        var ramRanked = emptyList<Pair<Int, Long>>()
        var probePct: Double? = null
        var missing = emptyList<Int>()
        ifCurrent(runGeneration) {
            dTotal = total - prevTopTotal
            cpuRanked = if (prevTopTotal != 0L && dTotal > 0) rankCpuProcesses(cur, prevProc) else emptyList()
            ramRanked = rankRamProcesses(rssPages)
            probePct = if (kidsNow != null && prevSelf > 0L && dTotal > 0) {
                Math.round((kidsNow - prevSelf) * 1000.0 / dTotal) / 10.0
            } else null
            prevTopTotal = total
            prevProc = HashMap(cur)
            kidsNow?.let { prevSelf = it }
            if (resolveFullNames) {
                missing = (cpuRanked.asSequence() + ramRanked.asSequence())
                    .map { it.first }.distinct().filter { it !in nameCache }.toList()
            }
        } ?: return

        // Full cmdlines only for ranked pids we haven't resolved before — usually zero extra su calls.
        val resolvedNames = if (missing.isEmpty()) emptyMap() else fullNames(missing)
        ifCurrent(runGeneration) {
            resolvedNames.forEach { (pid, name) -> nameCache[pid] = name }
            val attributionPkg = RendererResolver.attributionOf(identity.target, identity.ownPackage)
            fun processName(pid: Int): String {
                var name = trimProcName((nameCache[pid] ?: comm[pid] ?: pid.toString()).replace("\\", "").replace("\"", ""))
                if (pid == myPid) name = if (attributionPkg == name) "built-in dashboard (ha-paneld)" else "$name (this app)"
                return name
            }
            val cpuRows = cpuRanked.map { (pid, delta) ->
                val pct = Math.round(delta * 1000.0 / dTotal) / 10.0
                """{"name":"${processName(pid)}","cpu":$pct}"""
            }.toMutableList()
            probePct?.let { cpuRows += """{"name":"sampling probes (su/dumpsys)","cpu":$it,"self":true}""" }
            val pageBytes = pageSizeBytes()
            val ramRows = ramRanked.map { (pid, pages) ->
                """{"name":"${processName(pid)}","ramMb":${rssMb(pages, pageBytes)}}"""
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

    /** Full process names from `/proc/<pid>/cmdline` (comm is truncated to 16 chars) for the top pids. */
    private fun fullNames(pids: List<Int>): Map<Int, String> {
        if (pids.isEmpty()) return emptyMap()
        val cmd = "for p in ${pids.joinToString(" ")}; do printf '%s\\t' \"\$p\"; " +
            "cat /proc/\$p/cmdline 2>/dev/null; printf '\\n'; done; true"
        val out = rootDiagnostic(cmd, 256L * 1024L) ?: return emptyMap()
        val map = HashMap<Int, String>()
        for (line in out.lineSequence()) {
            val tab = line.indexOf('\t'); if (tab <= 0) continue
            val pid = line.substring(0, tab).trim().toIntOrNull() ?: continue
            val name = line.substring(tab + 1).replace('\u0000', ' ').trim().substringBefore(' ')
            if (name.isNotEmpty()) map[pid] = name
        }
        return map
    }

    private fun rootDiagnostic(command: String, maxBytes: Long): String? {
        val cost = FeatureCosts.registry.span(FeatureCostOperation.PERF_ROOT_DIAGNOSTICS)
        return try {
            val output = Su.runOutputIsolatedBounded(command, maxBytes)
            // Proc/dumpsys output is ASCII; character count is the byte workload without another array.
            cost.work(units = 1, bytes = output?.length?.toLong() ?: 0L)
            if (output == null) cost.outcome(FeatureCostOutcome.FAILURE)
            output
        } catch (error: Exception) {
            cost.outcome(FeatureCostOutcome.FAILURE)
            null
        } finally {
            cost.close()
        }
    }

    /** Shorten Android "package:process:classname" cmdlines to just the package component — the extra
     *  segments (Chromium sandbox class names, sub-process suffixes) add noise without aiding diagnosis. */
    private fun trimProcName(raw: String): String =
        if (raw.count { it == ':' } >= 2) raw.substringBefore(':') else raw

    private fun push(q: ArrayDeque<Int>, v: Int) {
        q.addLast(v)
        while (q.size > MAX) q.removeFirst()
    }

    // --- daemon-sourced top/render (sandbox panels), from the shared reader's PERFDUMP ---------------

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
        publishTop(runGeneration, cur, rssPages, comm, total, resolveFullNames = false)
    }

    /** Dashboard responsiveness from the daemon's CrRendererMain thread jiffies (primary metric; the
     *  gfxinfo secondary needs dumpsys/su, so it's omitted on sandbox panels). */
    private fun sampleRenderDump(runGeneration: Long, dump: PerfDump) {
        val now = android.os.SystemClock.elapsedRealtime()
        val mainPct = ifCurrent(runGeneration) { advanceRenderDelta(dump.rend, now) } ?: return
        if (mainPct < 0) {
            ifCurrent(runGeneration) {
                synchronized(lock) { renderJson = noRendererJson() }
            }
            return
        }
        val pct1 = Math.round(mainPct.coerceIn(0.0, 100.0) * 10) / 10.0
        val verdict = if (pct1 < 50) "smooth" else if (pct1 < 85) "occasional" else "janky"
        ifCurrent(runGeneration) {
            synchronized(lock) {
                push(stutterHist, Math.round(pct1).toInt())
                renderJson = "{\"pkg\":\"dashboard\",\"mainPct\":$pct1,\"verdict\":\"$verdict\",\"hist\":${stutterHist.toList()}}"
            }
        }
    }

    private fun tick(runGeneration: Long) {
        val snap = PanelMetrics.shared.systemSnapshot()
        if (!isCurrent(runGeneration)) return
        // Top-5 + render jank (PerfReader-only, spread across ticks). Prefer the shared reader's PERFDUMP
        // tables when it fetched a dump this tick (sandbox panels); else the direct su path on rooted panels.
        val dump = snap.dump
        // ~10s cadence for the heavy per-process samplers (was ~6s): they fork su probes and parse
        // every process's stat, which on a slow SoC was itself a visible slice of panel CPU. The 2s
        // chart above is untouched — only top-5/render slow down.
        val runState = ifCurrent(runGeneration) { tickCount % 5 to rootOk } ?: return
        when {
            dump != null -> when (runState.first) {
                0 -> runCatching { sampleTopDump(runGeneration, dump) }
                2 -> runCatching { sampleRenderDump(runGeneration, dump) }
            }
            runState.second -> when (runState.first) { // spread the su calls across ticks (~10s each)
                0 -> runCatching { sampleTop(runGeneration) }
                2 -> runCatching { sampleRender(runGeneration) }
            }
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
