package io.panelassistant.android.control

import io.panelassistant.android.device.DeviceProfile
import io.panelassistant.android.platform.Daemon
import io.panelassistant.android.platform.RootShell
import io.panelassistant.android.util.HelperClient
import io.panelassistant.android.platform.HELPER_ZIGBEE_DIRECTORY
import io.panelassistant.android.platform.HelperZigbeeState

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

    /** One bounded, complete helper observation. Direct su readiness does not gate the helper. */
    internal fun observe(includeRole: Boolean): ZigbeeObservation {
        val state = state() ?: return UNKNOWN_OBSERVATION
        return observation(state, if (includeRole && state.running) role() else null)
    }

    internal fun state(): HelperZigbeeState? = when (dir) {
        null -> HelperZigbeeState(emptySet(), false, false, null)
        HELPER_ZIGBEE_DIRECTORY -> daemon.zigbeeState()
        else -> null
    }

    /** Unreachable metadata cannot establish capability; a declared profile remains separately known. */
    fun present(): Boolean = state()?.present == true

    /** Null is unavailable, and must never be reported or reconciled as a stopped gateway. */
    fun running(): Boolean? = state()?.running

    fun role(): String? = if (dir == HELPER_ZIGBEE_DIRECTORY) daemon.zigbeeRole() else null

    /**
     * Start the gateway. **Idempotent** — if the radio or a guard is ALREADY running (e.g. the vendor
     * boot-started it), do nothing: starting a second guard makes both fight over the gateway's fixed MQTT
     * client-id (`rkguardsh_zigbee`), thrashing the connection into a CPU spin (root cause of the 120P hog).
     * NSPPT-managed: `run_guard_process.sh`. Vendor-native: launch `guard_process.sh` DETACHED (it's a
     * while-true watchdog — must not block the su call). Then best-effort nudge the role to Repeater.
     */
    fun enable(): Boolean = state()?.let(::enable) ?: false

    private fun enable(state: HelperZigbeeState): Boolean {
        val dir = dir ?: return false
        val layout = ZigbeeGatewayLayout.fromFiles(state.files)
        if (layout == ZigbeeGatewayLayout.UNKNOWN) return false
        val alreadyRunning = state.running
        if (!alreadyRunning && layout != ZigbeeGatewayLayout.VENDOR_4X && state.guardRunning) {
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
            val r = role()
            if (!r.equals(ROLE_REPEATER, ignoreCase = true)) setRole(ROLE_REPEATER)
        }
        return ok
    }

    /**
     * Stop the gateway, freeing the radio (and, on the buggy vendor guard, the CPU). NSPPT-managed:
     * `run_guard_process.sh stop`. Vendor-native has no stop arg → kill the guard first (so it can't
     * respawn zgateway) then zgateway itself.
     */
    fun disable(): Boolean = state()?.let(::disable) ?: false

    private fun disable(state: HelperZigbeeState): Boolean {
        val dir = dir ?: return false
        val layout = ZigbeeGatewayLayout.fromFiles(state.files)
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
        val state = state() ?: return false
        if (ZigbeeGatewayLayout.fromFiles(state.files) == ZigbeeGatewayLayout.UNKNOWN) return false
        return when {
            desiredOn -> enable(state)
            state.running || state.guardRunning -> disable(state)
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
        private const val ROLE_REPEATER = "Repeater"

        internal fun observation(state: HelperZigbeeState, role: String? = null): ZigbeeObservation {
            val layout = ZigbeeGatewayLayout.fromFiles(state.files)
            val driver = if (!state.present) null else when (layout) {
                ZigbeeGatewayLayout.MANAGED -> state.packageVersion?.let(::driverLabel) ?: "nspaneltools-managed"
                ZigbeeGatewayLayout.VENDOR_NATIVE -> "vendor-native"
                ZigbeeGatewayLayout.VENDOR_4X -> "vendor-native 4.x"
                ZigbeeGatewayLayout.UNKNOWN -> (state.files + if (state.running) setOf("running zgateway") else emptySet())
                    .sorted().joinToString(", ")
            }
            return ZigbeeObservation(true, state.present, layout, state.running, driver, role)
        }

        private fun driverLabel(version: String): String {
            val tail = version.substringAfter(':', version)
            val type = tail.substringBefore('-', tail)
            val release = tail.substringAfter('-', "")
            return if (release.isEmpty()) type else "$type $release"
        }
    }
}
