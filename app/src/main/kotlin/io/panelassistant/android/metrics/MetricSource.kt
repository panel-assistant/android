package io.panelassistant.android.metrics

import io.panelassistant.android.control.Su
import io.panelassistant.android.platform.Daemon
import io.panelassistant.android.platform.RootShell
import io.panelassistant.android.shizuku.ShizukuBridge
import io.panelassistant.android.util.HelperClient
import io.panelassistant.android.util.localIpv4
import java.io.File

/**
 * The I/O boundary the reader reads through — the seam that makes direct-vs-daemon source selection
 * mockable. Production is [OsMetricSource] (real `/proc`+`/sys` files, `Su`, the root helper daemon);
 * tests inject a fake to drive every path without a device.
 *
 * Deliberately dumb: it exposes each *raw* read and nothing decides which one to use. The reader
 * ([PanelMetrics]) owns strategy selection — it probes DIRECT first, falls back to the daemon, and caches
 * the winner per metric — so the source never consults the device profile (Issue #21 showed `appCanSu`
 * doesn't guarantee a direct `/proc` read succeeds; the winning strategy is discovered by probing, not
 * declared). A read returns null/empty when it's unavailable or SELinux-denied — that's the signal the
 * reader falls through on.
 */
interface MetricSource {
    /** One batched `PERFDUMP` (raw text) from the daemon, or null when unreachable. */
    fun perfDump(): String?

    // Direct reads (world-readable / rooted path). Null/empty ⇒ unavailable or SELinux-denied.
    fun statText(): String?
    fun meminfoText(): String?
    fun loadavgText(): String?
    fun thermalMilliValues(): List<Long>
    fun gpuLoadRaw(): String?
    fun cpuFreqCurMhz(): List<Long>
    fun cpuFreqMaxMhz(): Long

    // Simple point reads shared by the union.
    fun localIp(): String?
    fun selinuxEnforce(): String?

    // Control read-backs routed through the reader (direct→su fallback; no daemon read verb exists).
    fun cpuGovernor(allowRootFallback: Boolean = true): String?
    fun cpuAvailableGovernors(allowRootFallback: Boolean = true): String?

    // Raw room-climate readers (`T=<centi> H=<centi>`), one per authority. The reader ([PanelMetrics])
    // owns the helper→Shizuku source selection + the single parse, exactly like every other metric —
    // the source just exposes each raw read and never parses or decides which one wins.
    /** Raw room-climate reply from the authenticated helper daemon (`CHT8305`), or null when unreachable. */
    fun roomClimateDaemon(): String?

    /** Raw room-climate reply from the fixed Shizuku shell-UID input reader, or null when unavailable. */
    fun roomClimateShell(): String?
}

/**
 * Production [MetricSource]: the real device. All direct reads are lifted verbatim from the old PerfReader
 * inline `File(...)` logic. `/proc/meminfo` and the cpufreq sysfs are world-readable on every panel (even
 * sandbox ones), so mem + frequency always read direct; `/proc/stat`, `/proc/loadavg`, the thermal zones
 * and devfreq are direct where allowed and daemon-sourced where the app is SELinux-denied — the reader
 * decides which by probing.
 */
class OsMetricSource(
    private val daemon: Daemon = HelperClient,
    private val root: RootShell = Su,
    private val shellRoomClimate: () -> String? = ShizukuBridge::roomClimate,
) : MetricSource {

    override fun perfDump(): String? = daemon.sendBytes("PERFDUMP")?.toString(Charsets.UTF_8)

    override fun statText(): String? = read("/proc/stat")
    override fun meminfoText(): String? = read("/proc/meminfo")
    override fun loadavgText(): String? = read("/proc/loadavg")

    override fun thermalMilliValues(): List<Long> = runCatching {
        File("/sys/class/thermal").listFiles { f -> f.name.startsWith("thermal_zone") }
            ?.mapNotNull { File(it, "temp").takeIf { t -> t.exists() }?.readText()?.trim()?.toLongOrNull() }
            ?: emptyList()
    }.getOrDefault(emptyList())

    override fun gpuLoadRaw(): String? = runCatching {
        File("/sys/class/devfreq").listFiles { f -> f.name.contains("gpu") }
            ?.firstNotNullOfOrNull { d -> File(d, "load").takeIf { it.exists() }?.readText()?.trim() }
    }.getOrNull()

    override fun cpuFreqCurMhz(): List<Long> = runCatching {
        cpuDirs()?.sortedBy { it.name }
            ?.map { File(it, "cpufreq/scaling_cur_freq") }
            ?.map { if (it.exists()) (it.readText().trim().toLongOrNull() ?: 0L) / 1000 else 0L }
            ?: emptyList()
    }.getOrDefault(emptyList())

    override fun cpuFreqMaxMhz(): Long = runCatching {
        cpuDirs()
            ?.mapNotNull { File(it, "cpufreq/cpuinfo_max_freq").takeIf { x -> x.exists() }?.readText()?.trim()?.toLongOrNull() }
            ?.maxOrNull()?.div(1000) ?: 0L
    }.getOrDefault(0L)

    override fun localIp(): String? = localIpv4()

    /** SELinux mode ("1"/"0"): world-readable on most panels, su-fallback on the few that guard it. Read
     *  off-tick (rarely), so a plain direct→su fallback is fine — no per-metric resolution cache needed. */
    override fun selinuxEnforce(): String? = directThenSu("/sys/fs/selinux/enforce")

    /** cpu0 scaling governor (raw). Off-tick read (heartbeat sync + UI), world-readable cpufreq sysfs, so
     *  direct wins on every panel; su only if a panel guards the node. No daemon read verb exists. */
    override fun cpuGovernor(allowRootFallback: Boolean): String? = directThenSu(GOV0, allowRootFallback)

    /** cpu0 available governors (raw, space-separated). Same direct→su read path as [cpuGovernor]. */
    override fun cpuAvailableGovernors(allowRootFallback: Boolean): String? = directThenSu(AVAIL, allowRootFallback)

    /** Raw `CHT8305` daemon reply — the established helper authority. Parsing + fallback live in the reader. */
    override fun roomClimateDaemon(): String? = daemon.send("CHT8305")

    /** Raw Shizuku shell-UID room-climate reply — the locally approved fallback authority. */
    override fun roomClimateShell(): String? = shellRoomClimate()

    /** Direct file read, falling back to `su cat` when the app can't read the node directly. */
    private fun directThenSu(path: String, allowRootFallback: Boolean = true): String? =
        read(path)?.trim()?.takeIf { it.isNotEmpty() }
            ?: if (allowRootFallback) {
                root.runOutput("cat $path 2>/dev/null")?.trim()?.takeIf { it.isNotEmpty() }
            } else {
                null
            }

    private fun cpuDirs(): Array<File>? =
        File("/sys/devices/system/cpu").listFiles { f -> f.name.matches(Regex("cpu[0-9]+")) }

    private fun read(path: String): String? = runCatching { File(path).readText() }.getOrNull()

    private companion object {
        const val GOV0 = "/sys/devices/system/cpu/cpu0/cpufreq/scaling_governor"
        const val AVAIL = "/sys/devices/system/cpu/cpu0/cpufreq/scaling_available_governors"
    }
}
