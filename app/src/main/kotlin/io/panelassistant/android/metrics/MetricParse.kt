package io.panelassistant.android.metrics

/**
 * The daemon's `PERFDUMP` reply, parsed. On sandbox-walled panels (appCanSu=false) the app is
 * SELinux-denied `/proc/stat`, `/proc/loadavg`, the thermal zones and other pids' stat, so one PERFDUMP
 * from the root helper supplies everything at once. [stat] is the `cpu`/`cpuN` jiffy lines; [proc] is
 * the per-process CPU/resident-memory snapshot; [rend] is renderer pid → CrRendererMain jiffies.
 */
data class ProcessMetric(
    val pid: Int,
    val jiffies: Long,
    val name: String,
    /** Resident pages from `/proc/<pid>/stat`, or null when talking to an older helper. */
    val rssPages: Long?,
)

data class PerfDump(
    val stat: List<LongArray>,
    val loadavg: List<String>,
    val tempMilli: Long,
    val gpuRaw: String?,
    val proc: List<ProcessMetric>,
    val rend: Map<Int, Long>,
)

/**
 * Pure `/proc`+`/sys`+PERFDUMP → value parsers, with no Android or I/O dependency, so they're unit-tested
 * directly (like [io.panelassistant.android.http.PanelHealth]). Every function here is lifted verbatim
 * from the old PerfReader / Diagnostics inline logic so the extraction is behaviour-preserving; the reader
 * ([PanelMetrics]) owns the I/O + state, these own the parsing.
 */
object MetricParse {

    /** `/proc/stat` (or the dump's @STAT block) → one jiffy array per `cpu`/`cpuN` line, in order. */
    fun cpuStatLines(text: String): List<LongArray> =
        text.lineSequence()
            .filter { it.startsWith("cpu") }
            .map { line -> line.trim().split(Regex("\\s+")).drop(1).map { it.toLongOrNull() ?: 0L }.toLongArray() }
            .toList()

    /**
     * Per-line CPU busy percentage between two `/proc/stat` snapshots — index 0 is the overall `cpu`
     * aggregate, the rest are per-core. Busy = 1 − (idle+iowait)/total over the interval; normalised by
     * the interval, so ANY elapsed gap yields a valid average (the delta doesn't care who took the
     * snapshots). Empty when there's no prior snapshot or the shapes don't match (first sample).
     */
    fun cpuBusyPercents(prev: List<LongArray>?, cur: List<LongArray>): List<Int> {
        if (prev == null || cur.isEmpty() || cur.size != prev.size) return emptyList()
        val pct = ArrayList<Int>(cur.size)
        for (i in cur.indices) {
            val totA = prev[i].sum(); val totB = cur[i].sum()
            val idleA = prev[i].getOrElse(3) { 0 } + prev[i].getOrElse(4) { 0 }
            val idleB = cur[i].getOrElse(3) { 0 } + cur[i].getOrElse(4) { 0 }
            val dTot = totB - totA; val dIdle = idleB - idleA
            pct.add(if (dTot > 0) (((dTot - dIdle) * 100) / dTot).toInt().coerceIn(0, 100) else 0)
        }
        return pct
    }

    /** `/proc/meminfo` → (usedKb, totalKb), or null when MemTotal is missing/non-positive. `used` is
     *  `MemTotal − (MemAvailable ?: MemFree)`; both are always present on Linux. */
    fun memKb(meminfo: String): Pair<Long, Long>? {
        val m = meminfo.lineSequence().associate {
            val p = it.split(":")
            p[0].trim() to (p.getOrNull(1)?.trim()?.removeSuffix(" kB")?.trim()?.toLongOrNull() ?: 0L)
        }
        val total = m["MemTotal"] ?: 0L
        if (total <= 0L) return null
        val avail = m["MemAvailable"] ?: m["MemFree"] ?: 0L
        return (total - avail) to total
    }

    /** Max thermal-zone temperature in °C from raw millidegree values, or null when there are none. */
    fun maxThermalC(millis: List<Long>): Double? =
        millis.maxOrNull()?.let { if (it > 1000) it / 1000.0 else it.toDouble() }

    /** GPU devfreq `"<load>@<freq>Hz"` → (loadPct, freqMhz); loadPct is −1 when unparseable/absent. */
    fun gpuLoadFreq(raw: String?): Pair<Int, Long> {
        if (raw == null) return -1 to 0L
        val at = raw.indexOf('@')
        val pct = (if (at >= 0) raw.substring(0, at) else raw).trim().toIntOrNull() ?: -1
        val mhz = if (at >= 0) raw.substring(at + 1).removeSuffix("Hz").trim().toLongOrNull()?.div(1_000_000) ?: 0L else 0L
        return pct to mhz
    }

    /** `/proc/loadavg` → the first three load figures as strings. */
    fun loadavg3(text: String): List<String> = text.trim().split(" ").take(3)

    /**
     * The whitespace-split fields of a `/proc/<pid>/stat` (or `/proc/<pid>/task/<tid>/stat`) line that
     * follow the `comm` field — i.e. starting at the process state (stat field 3). `comm` is wrapped in
     * parentheses and may itself contain spaces and `)` (the kernel truncates it to 16 chars but permits
     * arbitrary bytes), so the split point is the **last** `)`, never the first. Returns null when there
     * is no `)` or nothing follows the closing `") "` (guarding the substring, exactly as the old inline
     * `childJiffiesOf` did). Index map into the result: `state=0`, … `utime=11`, `stime=12`, `cutime=13`,
     * `cstime=14` (stat fields 3, 14, 15, 16, 17). This is the single slicer behind every per-PID CPU
     * reader (top-5 by CPU, renderer main-thread jiffies, the reaped-child sampling-probe accounting).
     */
    fun statFieldsAfterComm(statLine: String): List<String>? {
        val rp = statLine.lastIndexOf(')')
        if (rp < 0 || rp + 2 > statLine.length) return null
        return statLine.substring(rp + 2).split(' ')
    }

    /**
     * Parse a `PERFDUMP` reply. The wire format (see helper/src/perf.c): `@STAT` then `/proc/stat`,
     * `@LOAD <1m 5m 15m …>`, `@TEMP <millidegrees>`, `@GPU <load@freqHz | ->`, `@PROC` then
     * `pid\tutime+stime\tcomm[\trss-pages]` lines, `@REND` then `pid\tjiffies` lines, `@END`.
     * The RSS column was appended so a new app remains compatible with the old three-column helper.
     */
    fun parsePerfDump(raw: String): PerfDump {
        val stat = ArrayList<LongArray>()
        val proc = ArrayList<ProcessMetric>()
        val rend = HashMap<Int, Long>()
        var load: List<String> = emptyList()
        var tempMilli = -1L
        var gpuRaw: String? = null
        var section = ""
        for (line in raw.lineSequence()) {
            when {
                line == "@STAT" -> { section = "stat"; continue }
                line == "@PROC" -> { section = "proc"; continue }
                line == "@REND" -> { section = "rend"; continue }
                line == "@END" -> break
                line.startsWith("@LOAD ") -> { load = line.removePrefix("@LOAD ").trim().split(" ").take(3); continue }
                line.startsWith("@TEMP ") -> { tempMilli = line.removePrefix("@TEMP ").trim().toLongOrNull() ?: -1L; continue }
                line.startsWith("@GPU ") -> { gpuRaw = line.removePrefix("@GPU ").trim().takeIf { it != "-" }; continue }
            }
            when (section) {
                "stat" -> if (line.startsWith("cpu"))
                    stat.add(line.trim().split(Regex("\\s+")).drop(1).map { it.toLongOrNull() ?: 0L }.toLongArray())
                "proc" -> line.split('\t').let { t ->
                    val pid = t.getOrNull(0)?.toIntOrNull(); val j = t.getOrNull(1)?.toLongOrNull()
                    if (pid != null && j != null) {
                        proc.add(ProcessMetric(pid, j, t.getOrElse(2) { "" }, t.getOrNull(3)?.toLongOrNull()))
                    }
                }
                "rend" -> line.split('\t').let { t ->
                    val pid = t.getOrNull(0)?.toIntOrNull(); val j = t.getOrNull(1)?.toLongOrNull()
                    if (pid != null && j != null) rend[pid] = j
                }
            }
        }
        return PerfDump(stat, load, tempMilli, gpuRaw, proc, rend)
    }

    /** Dump's `@TEMP` millidegrees → °C (or null when the daemon reported none, i.e. −1). */
    fun dumpTempC(tempMilli: Long): Double? =
        tempMilli.takeIf { it >= 0 }?.let { if (it > 1000) it / 1000.0 else it.toDouble() }

    /**
     * The `CHT8305` daemon reply → a room temperature/humidity reading. The wire format (see
     * helper/src/cht8305.c) is `T=<centi> H=<centi>` where each value is the reading × 100 (the driver's
     * input-axis units). Returns null for "ERR", a missing field, temperature outside the observed driver
     * range (-40..125 °C), or humidity outside 0..100%, so a bad read publishes nothing. Humidity zero is
     * retained as unavailable because these indoor vendor drivers use it as their uninitialised sentinel.
     */
    fun parseCht8305(raw: String?): RoomClimate? {
        if (raw.isNullOrBlank()) return null
        var t: Long? = null
        var h: Long? = null
        for (tok in raw.trim().split(Regex("\\s+"))) when {
            tok.startsWith("T=") -> t = tok.removePrefix("T=").toLongOrNull()
            tok.startsWith("H=") -> h = tok.removePrefix("H=").toLongOrNull()
        }
        if (t == null || h == null || t !in -4_000..12_500 || h !in 1..10_000) return null
        return RoomClimate(t / 100.0, h / 100.0)
    }
}

/** A CHT8305 reading: room air temperature (°C) and relative humidity (%RH), both already scaled from the
 *  driver's centi-unit axis values. The calibration offset is applied downstream (at publish), not here. */
data class RoomClimate(val tempC: Double, val humidityPct: Double)
