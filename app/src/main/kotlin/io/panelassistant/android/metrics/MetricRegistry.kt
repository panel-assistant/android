package io.panelassistant.android.metrics

/** A metric's stable identity + unit — the schema a history store keys off. [key] never changes. */
data class MetricSpec(val key: String, val unit: String?, val description: String)

/**
 * The central, enumerable registry of every OS-sourced **poll** metric ha-paneld reads (the
 * `SensorManager` lux/proximity stream is event-driven and deliberately out of scope — it stays in
 * `sensors/`). Keys are stable and subsystem-namespaced: they ARE the future metric-store's schema, so a
 * store can be built against this list without re-enumerating anything.
 *
 * Metrics fall in two bands:
 *  - **reader-owned** — read today by [PanelMetrics] with the uniform direct→daemon-fallback strategy.
 *  - **follow-on** — the control read-backs whose *read* still lives in their controller. They are
 *    registered here now so the union is complete and enumerable; migrating their read strategy into the
 *    reader is deferred because it either touches the never-blank backlight path (needs a hardware-attended
 *    session) or needs a new helper-daemon read verb (a separate subsystem change). The registry hosting
 *    them is what lets that migration land later "without touching consumers".
 */
object MetricRegistry {
    // --- system telemetry (reader-owned; the PerfReader ∪ Diagnostics union) ---------------------------
    val CPU = MetricSpec("cpu", "%", "Overall CPU busy percentage")
    val CPU_CORE = MetricSpec("cpu.core", "%", "Per-core CPU busy percentage (indexed cpu.core.N)")
    val MEM = MetricSpec("mem.percent", "%", "Used memory percentage")
    val MEM_USED = MetricSpec("mem.used", "MB", "Used memory")
    val MEM_TOTAL = MetricSpec("mem.total", "MB", "Total memory")
    val SOC_TEMP = MetricSpec("soc.temp", "°C", "Max thermal-zone temperature")
    val GPU_LOAD = MetricSpec("gpu.load", "%", "GPU devfreq load")
    val GPU_FREQ = MetricSpec("gpu.freq", "MHz", "GPU devfreq frequency")
    val LOAD1 = MetricSpec("loadavg.1", null, "1-minute load average")
    val CPU_FREQ = MetricSpec("cpu.freq", "MHz", "Per-core current CPU frequency")
    val CPU_FREQ_MAX = MetricSpec("cpu.freq.max", "MHz", "Hardware max CPU frequency")
    val NET_IP = MetricSpec("net.ip", null, "LAN IPv4 address")
    val BOOT = MetricSpec("boot.time", null, "Boot wall-clock (ISO-8601 UTC)")
    val SELINUX = MetricSpec("selinux.enforce", null, "SELinux mode (1=enforcing, 0=permissive)")

    // --- control read-backs (schema now; read-strategy migration into PanelMetrics is a follow-on) -----
    val BACKLIGHT = MetricSpec("backlight.effective", null, "Effective backlight 0–255 (follow-on: never-blank read path)")
    val BACKLIGHT_POWER = MetricSpec("backlight.power", null, "bl_power (0=on, 4=off) (follow-on)")
    val CPU_GOV = MetricSpec("cpu.governor", null, "CPU scaling governor (reader-owned; direct→su)")
    val RELAY = MetricSpec("relay.state", null, "On-board relay states (follow-on: needs daemon read verb)")
    val LED = MetricSpec("led.state", null, "LED read-back (follow-on: needs daemon read verb)")

    // --- environmental (reader-owned; authenticated exact layouts on supported panels) -----------------
    val ROOM_TEMP = MetricSpec("room.temp", "°C", "Room air temperature (supported input)")
    val ROOM_HUMIDITY = MetricSpec("room.humidity", "%", "Room relative humidity (supported input)")

    /** Every registered metric — the complete enumerable schema. */
    val ALL: List<MetricSpec> = listOf(
        CPU, CPU_CORE, MEM, MEM_USED, MEM_TOTAL, SOC_TEMP, GPU_LOAD, GPU_FREQ, LOAD1,
        CPU_FREQ, CPU_FREQ_MAX, NET_IP, BOOT, SELINUX,
        BACKLIGHT, BACKLIGHT_POWER, CPU_GOV, RELAY, LED,
        ROOM_TEMP, ROOM_HUMIDITY,
    )
}
