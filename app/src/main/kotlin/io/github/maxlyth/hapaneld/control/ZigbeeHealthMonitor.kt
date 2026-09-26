package io.github.maxlyth.hapaneld.control

import android.os.Build
import android.os.SystemClock
import android.util.Log
import io.github.maxlyth.hapaneld.metrics.FeatureCostOperation
import io.github.maxlyth.hapaneld.metrics.FeatureCostOutcome
import io.github.maxlyth.hapaneld.metrics.FeatureCosts
import io.github.maxlyth.hapaneld.platform.Daemon
import io.github.maxlyth.hapaneld.platform.RootShell
import io.github.maxlyth.hapaneld.util.HelperClient
import io.github.maxlyth.hapaneld.util.InstallPresentation
import io.github.maxlyth.hapaneld.util.SystemProps
import org.json.JSONObject
import java.util.ArrayDeque
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.roundToInt

enum class ZigbeeHealthState(val wireValue: String) {
    OFF("off"),
    STARTING("starting"),
    HEALTHY("healthy"),
    DEGRADED_UNJOINED("degraded_unjoined"),
    DEGRADED_HIGH_CPU("degraded_high_cpu"),
    RUNAWAY("runaway"),
    CONTAINED("contained"),
    CONTAINMENT_FAILED("containment_failed"),
    UNKNOWN("unknown"),
}

enum class ZigbeeGatewayLayout(val wireValue: String) {
    MANAGED("nspaneltools-managed"),
    VENDOR_NATIVE("vendor-native"),
    VENDOR_4X("vendor-native-4.x"),
    UNKNOWN("unknown"),

    ;

    companion object {
        /** The captured launchers, binary and package marker determine which stop route is safe. */
        fun fromFiles(files: Set<String>): ZigbeeGatewayLayout = when {
            listOf("run_guard_process.sh", "guard_process.sh", "zgateway", "package_version")
                .all(files::contains) -> MANAGED
            "run_guard_process.sh" !in files && "package_version" !in files &&
                listOf("guard_process.sh", "zgateway").all(files::contains) -> VENDOR_NATIVE
            "run_guard_process.sh" !in files && "guard_process.sh" !in files &&
                "package_version" !in files &&
                listOf("run.sh", "zgateway", "mosquitto").all(files::contains) -> VENDOR_4X
            else -> UNKNOWN
        }
    }
}

enum class ZigbeeContainmentResult(val wireValue: String) {
    NONE("none"),
    COMPLETE("complete"),
    PARTIAL("partial"),
    FAILED("failed"),
}

data class ZigbeeNetInfo(
    val roleType: Int?,
    val nodeId: Long?,
    val panId: Long?,
) {
    val explicitlyInvalid: Boolean
        get() = roleType == 0 || nodeId == 0L || panId == 0xffffL
    val explicitlyJoined: Boolean
        get() = roleType != null && roleType != 0 && nodeId != null && nodeId != 0L &&
            panId != null && panId != 0xffffL
}

data class ZigbeeGatewayObservation(
    val present: Boolean,
    val layout: ZigbeeGatewayLayout,
    val packageVersion: String?,
    val productVersion: String?,
    val gatewayPid: Int?,
    val gatewayCpu: Double?,
    val guardPid: Int?,
    val guardCpu: Double?,
    val role: String?,
    val netInfo: ZigbeeNetInfo?,
    val recursiveWatchdogAssignment: Boolean,
)

data class ZigbeeHealthSnapshot(
    val state: ZigbeeHealthState = ZigbeeHealthState.UNKNOWN,
    val observedAtMs: Long = 0L,
    val firmware: String? = null,
    val productVersion: String? = null,
    val layout: String = ZigbeeGatewayLayout.UNKNOWN.wireValue,
    val packageVersion: String? = null,
    val joined: Boolean? = null,
    val role: String? = null,
    val gatewayCpu: Int? = null,
    val guardCpu: Int? = null,
    val restartCount: Int = 0,
    val containment: ZigbeeContainmentResult = ZigbeeContainmentResult.NONE,
    val recursiveWatchdogAssignment: Boolean = false,
) {
    fun mqttAttributes(): String = JSONObject()
        .put("firmware", firmware ?: JSONObject.NULL)
        .put("product_version", productVersion ?: JSONObject.NULL)
        .put("gateway_layout", layout)
        .put("gateway_package_version", packageVersion ?: JSONObject.NULL)
        .put("joined", joined ?: JSONObject.NULL)
        .put("role", role ?: JSONObject.NULL)
        .put("gateway_cpu_percent", gatewayCpu ?: JSONObject.NULL)
        .put("guard_cpu_percent", guardCpu ?: JSONObject.NULL)
        .put("restart_count_10m", restartCount)
        .put("containment_result", containment.wireValue)
        .put("recursive_watchdog_assignment", recursiveWatchdogAssignment)
        .toString()

    fun publicSummary(): String {
        val details = mutableListOf(state.wireValue)
        joined?.let { details += if (it) "joined" else "unjoined" }
        role?.takeIf(String::isNotBlank)?.let(details::add)
        gatewayCpu?.let { details += "zgateway ${it}%" }
        guardCpu?.let { details += "guard ${it}%" }
        if (containment != ZigbeeContainmentResult.NONE) details += "containment ${containment.wireValue}"
        return details.joinToString(" · ")
    }
}

/** Typed counterpart of the server's ordered Zigbee warning projection. */
internal fun zigbeeHealthPresentation(
    snapshot: ZigbeeHealthSnapshot,
    configuredOn: Boolean,
): InstallPresentation? = when {
    snapshot.state == ZigbeeHealthState.CONTAINED -> InstallPresentation("status-zigbee-contained")
    snapshot.state == ZigbeeHealthState.CONTAINMENT_FAILED ->
        InstallPresentation("status-zigbee-containment-incomplete")
    snapshot.state == ZigbeeHealthState.RUNAWAY -> InstallPresentation("status-zigbee-runaway")
    snapshot.state == ZigbeeHealthState.DEGRADED_HIGH_CPU -> InstallPresentation("status-zigbee-high-cpu")
    snapshot.state == ZigbeeHealthState.DEGRADED_UNJOINED && configuredOn ->
        InstallPresentation("status-zigbee-not-joined")
    snapshot.recursiveWatchdogAssignment -> InstallPresentation("status-zigbee-legacy-watchdog")
    else -> null
}

internal object ZigbeeNetInfoParser {
    private const val MAX_CHARS = 4096

    fun parse(raw: String?): ZigbeeNetInfo? {
        val text = raw?.trim()?.takeIf { it.isNotEmpty() && it.length <= MAX_CHARS } ?: return null
        val objectStart = text.indexOf('{')
        val objectEnd = text.lastIndexOf('}')
        if (objectStart < 0 || objectEnd <= objectStart) return null
        val obj = runCatching { JSONObject(text.substring(objectStart, objectEnd + 1)) }.getOrNull() ?: return null
        fun value(vararg keys: String): Any? {
            val wanted = keys.map { it.lowercase(Locale.ROOT) }.toSet()
            val queue = ArrayDeque<JSONObject>()
            queue.add(obj)
            var visited = 0
            while (queue.isNotEmpty() && visited++ < 32) {
                val current = queue.removeFirst()
                val names = current.keys()
                while (names.hasNext()) {
                    val key = names.next()
                    val v = current.opt(key)
                    if (key.lowercase(Locale.ROOT) in wanted) return v
                    if (v is JSONObject) queue.add(v)
                }
            }
            return null
        }
        fun number(v: Any?): Long? = when (v) {
            is Number -> v.toLong()
            is String -> v.trim().let {
                when {
                    it.startsWith("0x", true) -> it.substring(2).toLongOrNull(16)
                    else -> it.toLongOrNull()
                }
            }
            else -> null
        }
        val roleType = number(value("roleType", "role_type"))?.toInt()
        val nodeId = number(value("nodeId", "node_id", "node"))
        val panId = number(value("panId", "pan_id", "pan"))
        return ZigbeeNetInfo(roleType, nodeId, panId)
            .takeIf { it.roleType != null || it.nodeId != null || it.panId != null }
    }
}

internal fun zigbeeNetInfoCommand(gatewayDir: String): String =
    "if [ -s /data/vendor/siliconlabs_host/netinfo ]; then " +
        "head -c 4097 /data/vendor/siliconlabs_host/netinfo; else " +
        "tail -n 200 /data/vendor/siliconlabs_host/zgateway.log $gatewayDir/zgateway.log 2>/dev/null | " +
        "grep -i netinfo | tail -n 1 | head -c 4097; fi"

internal fun normalizeGatewayCpu(rawPercent: Double?, cpuCount: Int, wholeMachineScale: Boolean): Double? {
    val raw = rawPercent?.takeIf { it.isFinite() && it >= 0.0 } ?: return null
    return (if (wholeMachineScale) raw * cpuCount.coerceAtLeast(1) else raw).coerceIn(0.0, 1000.0)
}

internal fun gatewayCpuFromJiffyDelta(processDelta: Long, totalDelta: Long, cpuCount: Int): Double? {
    if (processDelta < 0L || totalDelta <= 0L) return null
    return normalizeGatewayCpu(processDelta * 100.0 / totalDelta, cpuCount, true)
}

class ZigbeeHealthPolicy(
    private val startupGraceMs: Long = STARTUP_GRACE_MS,
    private val highCpuThreshold: Double = HIGH_CPU_PERCENT,
    private val requiredHighCpuSamples: Int = REQUIRED_HIGH_CPU_SAMPLES,
    private val churnWindowMs: Long = CHURN_WINDOW_MS,
    private val requiredPidChanges: Int = REQUIRED_PID_CHANGES,
) {
    data class Decision(
        val state: ZigbeeHealthState,
        val joined: Boolean?,
        val restartCount: Int,
        val shouldContain: Boolean,
    )

    private var graceStartedAt = 0L
    private var highCpuSamples = 0
    private var lastRunningPid: Int? = null
    private val pidChanges = ArrayDeque<Long>()

    fun resetGrace(nowMs: Long) {
        graceStartedAt = nowMs
        highCpuSamples = 0
        resetPidChurn()
    }

    fun evaluate(nowMs: Long, configuredOn: Boolean, observation: ZigbeeGatewayObservation): Decision {
        if (graceStartedAt == 0L) graceStartedAt = nowMs
        if (!observation.present) {
            highCpuSamples = 0
            resetPidChurn()
            return Decision(ZigbeeHealthState.UNKNOWN, null, 0, false)
        }

        val running = observation.gatewayPid != null || observation.guardPid != null
        if (!configuredOn && !running) {
            highCpuSamples = 0
            resetPidChurn()
            return Decision(ZigbeeHealthState.OFF, null, 0, false)
        }

        val inGrace = nowMs - graceStartedAt < startupGraceMs
        val restarts = when {
            !configuredOn -> {
                resetPidChurn()
                0
            }
            inGrace -> {
                seedPidTracking(observation.gatewayPid)
                0
            }
            else -> {
                updatePidChurn(nowMs, observation.gatewayPid)
                restartCount(nowMs)
            }
        }
        val joined = joinedEvidence(observation)
        val maxCpu = listOfNotNull(observation.gatewayCpu, observation.guardCpu).maxOrNull()
        val highCpu = maxCpu != null && maxCpu > highCpuThreshold
        highCpuSamples = if (highCpu) highCpuSamples + 1 else 0

        if (!configuredOn) {
            return Decision(
                when {
                    joined == true && highCpuSamples >= requiredHighCpuSamples -> ZigbeeHealthState.DEGRADED_HIGH_CPU
                    joined == true -> ZigbeeHealthState.HEALTHY
                    joined == false -> ZigbeeHealthState.DEGRADED_UNJOINED
                    else -> ZigbeeHealthState.UNKNOWN
                },
                joined,
                restarts,
                false,
            )
        }
        if (inGrace) {
            highCpuSamples = 0
            return Decision(ZigbeeHealthState.STARTING, joined, 0, false)
        }
        if (observation.layout == ZigbeeGatewayLayout.UNKNOWN || joined == null) {
            return Decision(ZigbeeHealthState.UNKNOWN, joined, restarts, false)
        }

        val runaway = restarts >= requiredPidChanges ||
            joined == false && highCpuSamples >= requiredHighCpuSamples
        return Decision(
            state = when {
                runaway -> ZigbeeHealthState.RUNAWAY
                joined == true && highCpuSamples >= requiredHighCpuSamples -> ZigbeeHealthState.DEGRADED_HIGH_CPU
                joined == true -> ZigbeeHealthState.HEALTHY
                else -> ZigbeeHealthState.DEGRADED_UNJOINED
            },
            joined = joined,
            restartCount = restarts,
            shouldContain = runaway,
        )
    }

    private fun joinedEvidence(observation: ZigbeeGatewayObservation): Boolean? {
        observation.netInfo?.let {
            if (it.explicitlyInvalid) return false
            if (it.explicitlyJoined) return true
        }
        return when {
            observation.role.equals("Repeater", true) -> true
            observation.role.equals("Coordinator", true) -> true
            else -> null
        }
    }

    private fun updatePidChurn(nowMs: Long, pid: Int?) {
        if (pid == null) {
            restartCount(nowMs)
            return
        }
        val previous = lastRunningPid
        if (previous != null && pid != previous) {
            // Retaining the last running PID across a down sample counts the common A -> down -> B
            // sequence. The PID difference prevents A -> down -> A from creating a false event.
            pidChanges.addLast(nowMs)
        }
        lastRunningPid = pid
        restartCount(nowMs)
    }

    /** Establish a grace-period baseline without carrying startup churn into containment decisions. */
    private fun seedPidTracking(pid: Int?) {
        lastRunningPid = pid
        pidChanges.clear()
    }

    private fun resetPidChurn() {
        lastRunningPid = null
        pidChanges.clear()
    }

    private fun restartCount(nowMs: Long): Int {
        while (pidChanges.isNotEmpty() && nowMs - pidChanges.first() > churnWindowMs) pidChanges.removeFirst()
        return pidChanges.size
    }

    companion object {
        const val STARTUP_GRACE_MS = 15 * 60_000L
        const val HIGH_CPU_PERCENT = 50.0
        const val REQUIRED_HIGH_CPU_SAMPLES = 5
        const val CHURN_WINDOW_MS = 10 * 60_000L
        const val REQUIRED_PID_CHANGES = 3
    }
}

interface ZigbeeGatewayHealthSource {
    fun observe(): ZigbeeGatewayObservation
    fun contain(layout: ZigbeeGatewayLayout): ZigbeeContainmentResult
}

class AndroidZigbeeGatewayHealthSource(
    private val dir: String?,
    private val controller: ZigbeeController,
    private val root: RootShell = Su,
    private val daemon: Daemon = HelperClient,
    private val productVersion: () -> String? = { SystemProps.get("ro.product.version").ifBlank { null } },
) : ZigbeeGatewayHealthSource {
    private var previousTotalJiffies: Long? = null
    private var previousProcessJiffies = emptyMap<Int, Long>()

    override fun observe(): ZigbeeGatewayObservation {
        val gatewayDir = dir ?: return absent()
        val files = zigbeeGatewayFiles(root, gatewayDir).orEmpty()
        val layout = ZigbeeGatewayLayout.fromFiles(files)
        val processes = parseProcesses(root.runOutput("ps -A -o PID=,ARGS= 2>/dev/null").orEmpty(), gatewayDir)
        val cpu = sampleCpu(processes.gatewayPid, processes.guardPid)
        val packageVersion = root.runOutput("cat $gatewayDir/package_version 2>/dev/null")
            ?.trim()?.takeIf { it.isNotEmpty() && it.length <= 120 }
        // Firmware writes the authoritative network snapshot to this canonical file. Older layouts may
        // instead log an embedded `netinfo` object, so retain that as a fallback. Bound acquisition before
        // parsing because the file also contains credentials/identity fields which must never be surfaced.
        val netInfoRaw = root.runOutput(zigbeeNetInfoCommand(gatewayDir))
        val recursive = root.runOutput(
            "grep -Fxc 'export LD_LIBRARY_PATH=/vendor/bin/siliconlabs_host/:${'$'}{LD_LIBRARY_PATH}' " +
                "$gatewayDir/guard_process.sh 2>/dev/null",
        )?.trim()?.toIntOrNull() == 1
        return ZigbeeGatewayObservation(
            present = files.isNotEmpty() || processes.gatewayPid != null,
            layout = layout,
            packageVersion = packageVersion,
            productVersion = productVersion(),
            gatewayPid = processes.gatewayPid,
            gatewayCpu = processes.gatewayPid?.let(cpu::get),
            guardPid = processes.guardPid,
            guardCpu = processes.guardPid?.let(cpu::get),
            role = processes.gatewayPid?.let { controller.role() },
            netInfo = ZigbeeNetInfoParser.parse(netInfoRaw),
            recursiveWatchdogAssignment = recursive,
        )
    }

    override fun contain(layout: ZigbeeGatewayLayout): ZigbeeContainmentResult {
        if (layout == ZigbeeGatewayLayout.VENDOR_NATIVE || layout == ZigbeeGatewayLayout.VENDOR_4X) {
            return when (daemon.send("ZIGBEECONTAIN")) {
                "OK" -> ZigbeeContainmentResult.COMPLETE
                "PARTIAL" -> ZigbeeContainmentResult.PARTIAL
                else -> ZigbeeContainmentResult.FAILED
            }
        }
        if (layout == ZigbeeGatewayLayout.MANAGED) {
            val stopped = controller.disable() && !controller.running()
            return if (stopped) ZigbeeContainmentResult.COMPLETE else ZigbeeContainmentResult.FAILED
        }
        return ZigbeeContainmentResult.FAILED
    }

    private fun absent() = ZigbeeGatewayObservation(
        false, ZigbeeGatewayLayout.UNKNOWN, null, productVersion(), null, null, null, null,
        null, null, false,
    )

    private data class Processes(
        val gatewayPid: Int? = null,
        val guardPid: Int? = null,
    )

    private fun parseProcesses(raw: String, gatewayDir: String): Processes {
        var gatewayPid: Int? = null
        var guardPid: Int? = null
        raw.lineSequence().forEach { line ->
            val match = Regex("""^\s*(\d+)\s+(.+)$""").find(line) ?: return@forEach
            val pid = match.groupValues[1].toIntOrNull() ?: return@forEach
            val args = match.groupValues[2]
            when {
                args == "$gatewayDir/zgateway" || args.startsWith("$gatewayDir/zgateway ") ||
                    args == "zgateway" -> gatewayPid = pid
                args == "sh $gatewayDir/guard_process.sh" ||
                    args == "/system/bin/sh $gatewayDir/guard_process.sh" -> guardPid = pid
            }
        }
        return Processes(gatewayPid, guardPid)
    }

    private fun sampleCpu(vararg candidatePids: Int?): Map<Int, Double> {
        val pids = candidatePids.filterNotNull().distinct()
        if (pids.isEmpty()) {
            previousTotalJiffies = null
            previousProcessJiffies = emptyMap()
            return emptyMap()
        }
        val command = buildString {
            append("head -n 1 /proc/stat 2>/dev/null")
            pids.forEach { append("; cat /proc/$it/stat 2>/dev/null") }
        }
        val lines = root.runOutput(command)?.lineSequence()?.filter(String::isNotBlank)?.toList()
            ?: return emptyMap()
        val total = lines.firstOrNull()?.takeIf { it.startsWith("cpu ") }
            ?.trim()?.split(Regex("\\s+"))?.drop(1)?.mapNotNull(String::toLongOrNull)?.sum()
            ?: return emptyMap()
        val current = linkedMapOf<Int, Long>()
        lines.drop(1).forEach { line ->
            val pid = line.substringBefore(' ').toIntOrNull() ?: return@forEach
            val afterComm = line.substringAfterLast(") ", "")
            val fields = afterComm.split(' ')
            val user = fields.getOrNull(11)?.toLongOrNull() ?: return@forEach
            val system = fields.getOrNull(12)?.toLongOrNull() ?: return@forEach
            current[pid] = user + system
        }
        val previousTotal = previousTotalJiffies
        val previous = previousProcessJiffies
        previousTotalJiffies = total
        previousProcessJiffies = current
        val totalDelta = previousTotal?.let { total - it }?.takeIf { it > 0L } ?: return emptyMap()
        val cores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
        return current.mapNotNull { (pid, jiffies) ->
            val processDelta = previous[pid]?.let { jiffies - it }?.takeIf { it >= 0L } ?: return@mapNotNull null
            pid to gatewayCpuFromJiffyDelta(processDelta, totalDelta, cores)!!
        }.toMap()
    }
}

class ZigbeeHealthMonitor(
    private val configuredOn: () -> Boolean,
    private val source: ZigbeeGatewayHealthSource,
    private val onContain: () -> Unit,
    private val onSnapshot: (ZigbeeHealthSnapshot, Boolean) -> Unit,
    private val nowMs: () -> Long = SystemClock::elapsedRealtime,
    private val policy: ZigbeeHealthPolicy = ZigbeeHealthPolicy(),
    private val sampleIntervalMs: Long = SAMPLE_INTERVAL_MS,
) {
    private val executor = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "zigbee-health").apply { isDaemon = true }
    }
    private val generation = AtomicLong()
    @Volatile private var current = ZigbeeHealthSnapshot()
    private var containmentAttempted = false
    private var lastHeartbeatAt = 0L

    fun start() {
        if (!generation.compareAndSet(0L, 1L)) return
        val runGeneration = 1L
        policy.resetGrace(nowMs())
        executor.scheduleWithFixedDelay({
            if (generation.get() == runGeneration) sample(runGeneration)
        }, 0L, sampleIntervalMs.coerceAtLeast(1L), TimeUnit.MILLISECONDS)
    }

    fun explicitRetry() {
        val runGeneration = generation.get()
        try {
            executor.execute {
                if (generation.get() != runGeneration) return@execute
                containmentAttempted = false
                // A user-driven retry must sample immediately, whatever the slow-sample backoff says.
                slowSampleSkips = 0
                consecutiveSlowSamples = 0
                policy.resetGrace(nowMs())
                transition(current.copy(state = ZigbeeHealthState.STARTING, containment = ZigbeeContainmentResult.NONE), true)
            }
        } catch (_: RejectedExecutionException) {
            // A terminal monitor has already closed its lane; retry must not resurrect its state.
        }
    }

    fun snapshot(): ZigbeeHealthSnapshot = current

    fun stop(): Boolean {
        generation.updateAndGet { if (it == 0L) 2L else it + 1L }
        executor.shutdownNow()
        return runCatching { executor.awaitTermination(STOP_JOIN_MS, TimeUnit.MILLISECONDS) }
            .getOrDefault(false)
    }

    internal fun sample() = sample(null)

    /** Scheduled sampling backs off while the root shell is degraded: on affected hardware the runaway
     *  zgateway condition made every observation ride a 5s su timeout, and the resulting storm starved
     *  the su lane for everything else (MQTT retire fences included) while producing no new signal.
     *  A slow sample quarters the effective rate; consecutive slow samples reduce it 8×. An explicit
     *  retry or direct test call ([sample] with no generation) never skips. */
    @Volatile private var slowSampleSkips = 0
    @Volatile private var consecutiveSlowSamples = 0

    private fun sample(runGeneration: Long?) {
        if (runGeneration != null && slowSampleSkips > 0) {
            slowSampleSkips--
            return
        }
        val sampleStartedAtMs = nowMs()
        fun isCurrentRun(): Boolean = runGeneration == null || generation.get() == runGeneration
        val started = FeatureCosts.registry.beginSynchronous(FeatureCostOperation.ZIGBEE_HEALTH_SAMPLE)
        var outcome = FeatureCostOutcome.SUCCESS
        fun continueIfCurrent(): Boolean {
            if (isCurrentRun()) return true
            outcome = FeatureCostOutcome.CANCELLED
            return false
        }
        try {
            val now = nowMs()
            val observation = source.observe()
            // A root observation may ignore interruption and outlive stop(). Once this monitor has
            // retired, the obsolete sample must not contain hardware or publish into its replacement.
            if (!continueIfCurrent()) return
            if (observation.recursiveWatchdogAssignment && !current.recursiveWatchdogAssignment) {
                Log.w(TAG, "legacy Zigbee watchdog contains the recursive LD_LIBRARY_PATH assignment")
            }
            val decision = policy.evaluate(now, configuredOn(), observation)
            if (!continueIfCurrent()) return
            var containment = current.containment
            var state = decision.state
            if (containmentAttempted && !configuredOn() && containment != ZigbeeContainmentResult.NONE) {
                state = if (containment == ZigbeeContainmentResult.COMPLETE) {
                    ZigbeeHealthState.CONTAINED
                } else {
                    ZigbeeHealthState.CONTAINMENT_FAILED
                }
            }
            if (decision.shouldContain && !containmentAttempted) {
                if (!continueIfCurrent()) return
                containmentAttempted = true
                onContain()
                if (!continueIfCurrent()) return
                containment = source.contain(observation.layout)
                if (!continueIfCurrent()) return
                state = if (containment == ZigbeeContainmentResult.COMPLETE) {
                    ZigbeeHealthState.CONTAINED
                } else {
                    ZigbeeHealthState.CONTAINMENT_FAILED
                }
            }
            val next = ZigbeeHealthSnapshot(
                state = state,
                observedAtMs = now,
                firmware = Build.DISPLAY.takeIf(String::isNotBlank),
                productVersion = observation.productVersion,
                layout = observation.layout.wireValue,
                packageVersion = observation.packageVersion,
                joined = decision.joined,
                role = observation.role,
                gatewayCpu = observation.gatewayCpu?.roundToInt(),
                guardCpu = observation.guardCpu?.roundToInt(),
                restartCount = decision.restartCount,
                containment = containment,
                recursiveWatchdogAssignment = observation.recursiveWatchdogAssignment,
            )
            val heartbeat = lastHeartbeatAt == 0L || now - lastHeartbeatAt >= HEARTBEAT_MS
            if (!continueIfCurrent()) return
            transition(next, heartbeat)
            if (heartbeat) lastHeartbeatAt = now
        } catch (_: Exception) {
            outcome = FeatureCostOutcome.FAILURE
            if (isCurrentRun()) {
                transition(current.copy(state = ZigbeeHealthState.UNKNOWN, observedAtMs = nowMs()), true)
            }
        } finally {
            if (runGeneration != null) {
                if (nowMs() - sampleStartedAtMs >= SLOW_SAMPLE_MS) {
                    consecutiveSlowSamples++
                    slowSampleSkips = if (consecutiveSlowSamples >= 2) 7 else 3
                } else {
                    consecutiveSlowSamples = 0
                    slowSampleSkips = 0
                }
            }
            FeatureCosts.registry.finishSynchronous(
                FeatureCostOperation.ZIGBEE_HEALTH_SAMPLE,
                started,
                outcome,
                workUnits = 1,
            )
        }
    }

    internal fun lifecycleGeneration(): Long = generation.get()

    internal fun awaitIdleForTest(timeoutMs: Long = STOP_JOIN_MS): Boolean = try {
        executor.submit {}.get(timeoutMs, TimeUnit.MILLISECONDS)
        true
    } catch (_: Exception) {
        false
    }

    private fun transition(next: ZigbeeHealthSnapshot, heartbeat: Boolean) {
        val previous = current
        current = next
        val changed = previous.state != next.state ||
            previous.containment != next.containment ||
            previous.recursiveWatchdogAssignment != next.recursiveWatchdogAssignment
        if (changed) {
            Log.i(
                TAG,
                "Zigbee health ${previous.state.wireValue} -> ${next.state.wireValue}; " +
                    "joined=${next.joined} gatewayCpu=${next.gatewayCpu} guardCpu=${next.guardCpu} " +
                    "restarts=${next.restartCount} containment=${next.containment.wireValue}",
            )
        }
        if (changed || heartbeat) onSnapshot(next, changed)
    }

    companion object {
        const val SAMPLE_INTERVAL_MS = 60_000L

        /** A sample this slow rode a root-shell timeout; see the backoff note on [sample]. */
        const val SLOW_SAMPLE_MS = 4_000L
        const val HEARTBEAT_MS = 15 * 60_000L
        const val STOP_JOIN_MS = 2_000L
        private const val TAG = "ha-paneld/zigbee-health"
    }
}
