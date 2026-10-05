package io.panelassistant.android.control

import io.panelassistant.android.device.DeviceProfile
import io.panelassistant.android.platform.Daemon
import io.panelassistant.android.platform.RootShell
import io.panelassistant.android.util.HelperClient
import org.json.JSONObject

/** One self-consistent, request-scoped view of the Zigbee gateway. */
internal data class ZigbeeObservation(
    val probeSucceeded: Boolean,
    val present: Boolean,
    val layout: ZigbeeGatewayLayout,
    val running: Boolean,
    val driver: String?,
    val role: String?,
) {
    val status: String
        get() {
            if (!probeSucceeded) return "gateway · status unavailable"
            if (!present) return "none"
            if (layout == ZigbeeGatewayLayout.UNKNOWN) return "gateway · unknown layout: ${driver.orEmpty()}"
            val roleSuffix = role?.let { " · $it" }.orEmpty()
            return "${driver ?: "gateway"} · ${if (running) "running" else "stopped"}$roleSuffix"
        }
}

/** One bounded file footprint for both explicit control and the health monitor. */
internal fun zigbeeGatewayFiles(root: RootShell, dir: String): Set<String>? {
    val raw = root.runOutput(
        "for f in run_guard_process.sh guard_process.sh run.sh package_version; do " +
            "[ -f $dir/\$f ] && [ ! -L $dir/\$f ] && echo \$f; done; " +
            "for f in zgateway mosquitto; do " +
            "[ -x $dir/\$f ] && [ ! -L $dir/\$f ] && echo \$f; done",
    ) ?: return null
    val files = raw.lineSequence().map(String::trim).filter(String::isNotEmpty).toList()
    val known = setOf("run_guard_process.sh", "guard_process.sh", "run.sh", "package_version", "zgateway", "mosquitto")
    return files.takeIf { it.size <= known.size && it.size == it.distinct().size && it.all(known::contains) }
        ?.toSet()
}

/**
 * Zigbee gateway control for panels that ship a Sonoff-style gateway (currently the NSPanel Pro — a
 * Silicon Labs EFR32 EZSP NCP on `/dev/ttyS5`). The gateway dir comes from [DeviceProfile.zigbeeGatewayDir]
 * (`/vendor/bin/siliconlabs_host` on NSPanel Pro); a profile with no dir makes this controller inert.
 *
 * Three gateway layouts were captured on NSPanel Pro panels:
 *   - **NSPanelTools-managed** — NSPPT side-loaded a Sonoff package: `run_guard_process.sh` launchers +
 *     a `package_version` marker (e.g. `sonoff-v3.5.4:sonoff-3.5.0`). The footprint PERSISTS after NSPPT
 *     is uninstalled. Start/stop via `run_guard_process.sh [stop]`.
 *   - **vendor-native** — stock firmware ships `guard_process.sh` (a while-true supervisor), no
 *     managed launcher or `package_version`. Boot-started by a vendor hook (reparented to init). It has
 *     **no stop argument**, so disable = kill the guard (so it can't respawn) then `zgateway`.
 *     (Note: on 120P/3.7.1 the vendor guard has a CPU-spin defect — disabling it is a real win.)
 *   - **vendor-native 4.x** — `run.sh` starts a broker and gateway without a persistent guard. The
 *     script has no stop argument. Stop uses the helper's exact-path process containment.
 *
 * zgateway is controlled over a LOCAL anonymous mosquitto broker on `127.0.0.1:1883`:
 *   - role status:  `zigbee/system/network-role/information`  →  `{"role":"Repeater"|"Coordinator"}`
 *   - role switch:  `zigbee/system/network-role/switch`        ←  `{"role":"Repeater"}`
 * Process control needs root through [Su] or the bundled helper.
 */
class ZigbeeController(
    profile: DeviceProfile,
    private val root: RootShell = Su,
    private val daemon: Daemon = HelperClient,
) {

    private val dir: String? = profile.zigbeeGatewayDir

    /**
     * Read all cheap gateway metadata through one bounded root command. Fixed framing distinguishes a
     * valid absence from partial, oversized, reordered, or otherwise malformed output, which remains
     * unknown. Role is a separate bounded broker read, attempted only when this frame proves it running.
     */
    internal fun observe(includeRole: Boolean, directSuReady: Boolean = true): ZigbeeObservation {
        val gatewayDir = dir ?: return ABSENT_OBSERVATION
        if (!directSuReady) return UNKNOWN_OBSERVATION
        val metadata = parseMetadata(
            root.runOutputIsolatedBounded(
                metadataCommand(gatewayDir),
                maxBytes = METADATA_MAX_BYTES,
                timeoutMs = METADATA_TIMEOUT_MS,
            ),
        ) ?: return UNKNOWN_OBSERVATION
        return if (includeRole && metadata.present && metadata.running) {
            metadata.copy(role = readRole(gatewayDir))
        } else {
            metadata
        }
    }

    /** True when any gateway footprint is present, or one is already running. */
    fun present(): Boolean {
        val dir = dir ?: return false
        // Keep the low-overhead control-path checks independent of the management observation. In
        // particular, MQTT reconciliation must not wait for the diagnostic lane just to read a PID.
        return fileExists("$dir/run_guard_process.sh") || fileExists("$dir/guard_process.sh") ||
            fileExists("$dir/run.sh") || fileExists("$dir/zgateway") || fileExists("$dir/mosquitto") ||
            fileExists("$dir/package_version") || running()
    }

    private fun layout(): ZigbeeGatewayLayout {
        val dir = dir ?: return ZigbeeGatewayLayout.UNKNOWN
        return ZigbeeGatewayLayout.fromFiles(zigbeeGatewayFiles(root, dir) ?: return ZigbeeGatewayLayout.UNKNOWN)
    }

    private fun fileExists(path: String): Boolean =
        root.runOutput("ls $path 2>/dev/null")?.trim()?.isNotEmpty() == true

    /** True when the zgateway host process is running (the radio is in use). */
    fun running(): Boolean = root.runOutput("pidof zgateway")?.trim()?.isNotEmpty() == true

    /** Current network role from the local broker, or null if unreadable (gateway/broker down). */
    fun role(): String? = dir?.let(::readRole)

    private fun readRole(gatewayDir: String): String? {
        val out = root.runOutputIsolatedBounded(
            "export LD_LIBRARY_PATH=$gatewayDir; $gatewayDir/mosquitto_sub -h 127.0.0.1 -p 1883 -i hapaneld_zr " +
                "-t zigbee/system/network-role/information -C 1 -W 3",
            maxBytes = ROLE_MAX_BYTES,
            timeoutMs = ROLE_TIMEOUT_MS,
        )?.trim() ?: return null
        return runCatching {
            (JSONObject(out).opt("role") as? String)
                ?.takeIf { role -> role.isNotEmpty() && role.length <= ROLE_MAX_CHARS && role.none(Char::isISOControl) }
        }.getOrNull()
    }

    /** A guard supervisor is already running (matched by exact cmdline, excluding our own shells). */
    private fun guardRunning(): Boolean =
        root.runOutput("ps -A -o ARGS= 2>/dev/null | grep guard_process.sh | grep -v ' -c ' | grep -v grep")
            ?.trim()?.isNotEmpty() == true

    /**
     * Start the gateway. **Idempotent** — if the radio or a guard is ALREADY running (e.g. the vendor
     * boot-started it), do nothing: starting a second guard makes both fight over the gateway's fixed MQTT
     * client-id (`rkguardsh_zigbee`), thrashing the connection into a CPU spin (root cause of the 120P hog).
     * NSPPT-managed: `run_guard_process.sh`. Vendor-native: launch `guard_process.sh` DETACHED (it's a
     * while-true watchdog — must not block the su call). Then best-effort nudge the role to Repeater.
     */
    fun enable(): Boolean = enable(layout())

    private fun enable(layout: ZigbeeGatewayLayout): Boolean {
        val dir = dir ?: return false
        if (layout == ZigbeeGatewayLayout.UNKNOWN) return false
        val alreadyRunning = running()
        if (!alreadyRunning && layout != ZigbeeGatewayLayout.VENDOR_4X && guardRunning()) {
            return true // startup already in flight — never duplicate it
        }
        val ok = alreadyRunning || when (layout) {
            ZigbeeGatewayLayout.MANAGED -> root.run("sh $dir/run_guard_process.sh")
            ZigbeeGatewayLayout.VENDOR_NATIVE -> root.run("nohup sh $dir/guard_process.sh >/dev/null 2>&1 &")
            // run.sh sleeps while it starts the broker and gateway; wait for it once before role control.
            ZigbeeGatewayLayout.VENDOR_4X -> root.runSingleAttempt("sh $dir/run.sh >/dev/null 2>&1", 12_000L)
            ZigbeeGatewayLayout.UNKNOWN -> false
        }
        if (!ok) return false
        runCatching {
            val r = readRole(dir)
            if (!r.equals(ROLE_REPEATER, ignoreCase = true)) setRole(ROLE_REPEATER)
        }
        return ok
    }

    /**
     * Stop the gateway, freeing the radio (and, on the buggy vendor guard, the CPU). NSPPT-managed:
     * `run_guard_process.sh stop`. Vendor-native has no stop arg → kill the guard first (so it can't
     * respawn zgateway) then zgateway itself.
     */
    fun disable(): Boolean = disable(layout())

    private fun disable(layout: ZigbeeGatewayLayout): Boolean {
        val dir = dir ?: return false
        if (layout == ZigbeeGatewayLayout.UNKNOWN) return false
        // NSPPT-managed: the clean stop script. Vendor-native (no stop arg): kill the guard — it's the
        // supervisor + CPU hog + respawner, and it's killable (shell domain). Match it by full cmdline via
        // ps, EXCLUDING our own su/sh shells (`-c`) and grep, so we don't kill the shell running this (the
        // bug pkill -f had: its cmdline contains "guard_process.sh"). Then best-effort SIGKILL the radio —
        // but on stock firmware zgateway runs in the init domain and the vendor `su` returns EPERM, so this
        // is a no-op there and the radio persists until reboot (a firmware limit surfaced in status).
        return when (layout) {
            ZigbeeGatewayLayout.MANAGED -> root.run("sh $dir/run_guard_process.sh stop")
            ZigbeeGatewayLayout.VENDOR_NATIVE -> root.run(
                "for p in \$(ps -A -o PID=,ARGS= 2>/dev/null | grep guard_process.sh | grep -v ' -c ' | " +
                    "grep -v grep | awk '{print \$1}'); do kill -9 \$p 2>/dev/null; done; " +
                    "killall -9 zgateway 2>/dev/null; true",
            )
            ZigbeeGatewayLayout.VENDOR_4X -> daemon.send("ZIGBEECONTAIN") == "OK"
            ZigbeeGatewayLayout.UNKNOWN -> false
        }
    }

    /**
     * Drive the gateway to [desiredOn], whoever started it. Vendor firmware boot-starts the gateway
     * independently, so persisting an explicit "off" means actively stopping it; "on" starts it if down.
     * Caller gates this on the user having EXPLICITLY configured the switch — we must never disable a
     * vendor-started gateway a user relies on just because our default is off.
     */
    fun reconcile(desiredOn: Boolean): Boolean {
        val layout = layout()
        if (layout == ZigbeeGatewayLayout.UNKNOWN) return false
        val run = running()
        return when {
            desiredOn -> enable(layout)
            !desiredOn && run -> disable(layout)
            else -> true
        }
    }

    /** Publish a role switch to the local broker. Allowlist the role so an arbitrary string can never
     *  be interpolated into the shell command. */
    private fun setRole(role: String) {
        val dir = dir ?: return
        val r = when (role.lowercase()) {
            "coordinator" -> "Coordinator"
            "repeater" -> "Repeater"
            else -> return
        }
        root.run(
            "export LD_LIBRARY_PATH=$dir; $dir/mosquitto_pub -h 127.0.0.1 -p 1883 -i hapaneld_zp " +
                "-t zigbee/system/network-role/switch -m '{\"role\":\"$r\"}'",
        )
    }

    companion object {
        private val ABSENT_OBSERVATION = ZigbeeObservation(
            probeSucceeded = true,
            present = false,
            layout = ZigbeeGatewayLayout.UNKNOWN,
            running = false,
            driver = null,
            role = null,
        )
        private val UNKNOWN_OBSERVATION = ABSENT_OBSERVATION.copy(probeSucceeded = false)
        private const val METADATA_HEADER = "HAPANELD_ZIGBEE_V1"
        private const val METADATA_FOOTER = "HAPANELD_ZIGBEE_END"
        private const val METADATA_MAX_BYTES = 1_024L
        private const val METADATA_TIMEOUT_MS = 3_500L
        private const val PACKAGE_VERSION_MAX_BYTES = 120
        private const val ROLE_REPEATER = "Repeater"
        private const val ROLE_MAX_CHARS = 64
        private const val ROLE_MAX_BYTES = 4_096L
        private const val ROLE_TIMEOUT_MS = 3_500L

        private fun metadataCommand(dir: String): String =
            "printf '$METADATA_HEADER\\n'; " +
                "if [ -f '$dir/run_guard_process.sh' ] && [ ! -L '$dir/run_guard_process.sh' ]; then printf 'managed=1\\n'; " +
                "else printf 'managed=0\\n'; fi; " +
                "if [ -f '$dir/guard_process.sh' ] && [ ! -L '$dir/guard_process.sh' ]; then printf 'guard=1\\n'; " +
                "else printf 'guard=0\\n'; fi; " +
                "if [ -f '$dir/run.sh' ] && [ ! -L '$dir/run.sh' ]; then printf 'run=1\\n'; " +
                "else printf 'run=0\\n'; fi; " +
                "if [ -x '$dir/zgateway' ] && [ ! -L '$dir/zgateway' ]; then printf 'binary=1\\n'; " +
                "else printf 'binary=0\\n'; fi; " +
                "if [ -x '$dir/mosquitto' ] && [ ! -L '$dir/mosquitto' ]; then printf 'broker=1\\n'; " +
                "else printf 'broker=0\\n'; fi; " +
                "if [ -f '$dir/package_version' ] && [ ! -L '$dir/package_version' ]; then printf 'package_marker=1\\n'; " +
                "else printf 'package_marker=0\\n'; fi; " +
                "if pidof zgateway >/dev/null 2>&1; then printf 'running=1\\n'; " +
                "else printf 'running=0\\n'; fi; " +
                "package_version=\$(head -c ${PACKAGE_VERSION_MAX_BYTES + 1} '$dir/package_version' " +
                "2>/dev/null); printf 'package=%s\\n' \"\$package_version\"; " +
                "printf '$METADATA_FOOTER\\n'"

        private fun parseMetadata(raw: String?): ZigbeeObservation? {
            raw ?: return null
            val framed = raw.removeSuffix("\n")
            val lines = framed.split('\n').map { it.removeSuffix("\r") }
            if (lines.size != 10 || lines.first() != METADATA_HEADER || lines.last() != METADATA_FOOTER) {
                return null
            }
            fun flag(index: Int, name: String): Boolean? = when (lines[index]) {
                "$name=0" -> false
                "$name=1" -> true
                else -> null
            }
            val managed = flag(1, "managed") ?: return null
            val guard = flag(2, "guard") ?: return null
            val run = flag(3, "run") ?: return null
            val binary = flag(4, "binary") ?: return null
            val broker = flag(5, "broker") ?: return null
            val packageMarker = flag(6, "package_marker") ?: return null
            val running = flag(7, "running") ?: return null
            val packageLine = lines[8]
            if (!packageLine.startsWith("package=")) return null
            val packageVersion = packageLine.removePrefix("package=")
            if (packageVersion.toByteArray(Charsets.UTF_8).size > PACKAGE_VERSION_MAX_BYTES ||
                packageVersion.any(Char::isISOControl)
            ) return null
            val present = managed || guard || run || binary || broker || packageMarker || running
            val files = buildSet {
                if (managed) add("run_guard_process.sh")
                if (guard) add("guard_process.sh")
                if (run) add("run.sh")
                if (binary) add("zgateway")
                if (broker) add("mosquitto")
                if (packageMarker) add("package_version")
            }
            val layout = ZigbeeGatewayLayout.fromFiles(files)
            val rawDriver = packageVersion.trim().takeIf(String::isNotEmpty)
            val driver = if (!present) null else when (layout) {
                ZigbeeGatewayLayout.MANAGED -> rawDriver?.let(::driverLabel) ?: "nspaneltools-managed"
                ZigbeeGatewayLayout.VENDOR_NATIVE -> "vendor-native"
                ZigbeeGatewayLayout.VENDOR_4X -> "vendor-native 4.x"
                ZigbeeGatewayLayout.UNKNOWN -> (files + if (running) setOf("running zgateway") else emptySet())
                    .sorted().joinToString(", ")
            }
            return ZigbeeObservation(
                probeSucceeded = true,
                present = present,
                layout = layout,
                running = running,
                driver = driver,
                role = null,
            )
        }

        private fun driverLabel(version: String): String {
            val tail = version.substringAfter(':', version)
            val type = tail.substringBefore('-', tail)
            val release = tail.substringAfter('-', "")
            return if (release.isEmpty()) type else "$type $release"
        }
    }
}
