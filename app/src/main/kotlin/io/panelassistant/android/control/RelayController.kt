package io.panelassistant.android.control

import io.panelassistant.android.device.DeviceProfile
import io.panelassistant.android.metrics.FeatureCostOperation
import io.panelassistant.android.metrics.FeatureCostOutcome
import io.panelassistant.android.metrics.FeatureCosts
import io.panelassistant.android.platform.RootShell

/**
 * On-board relay + button-LED control for panels that expose them — currently the **Smatek S9E**,
 * which has two mains relays (sysfs class `/sys/class/strelay` on firmware 1.1.0+, `/sys/class/st_relay`
 * on the initial 1.0.2 — both probed) and four button LEDs at `/sys/class/gpio/gpio147`–`gpio150`, all
 * driven by `echo 1`/`echo 0`. The sysfs locations come from the active [DeviceProfile]
 * ([DeviceProfile.relayBase] + [DeviceProfile.relayBaseFallbacks] / [DeviceProfile.buttonLedGpioBase]); a
 * profile with none (every panel except the S9E) makes this controller inert with no su probes. Relay
 * class nodes are runtime-confirmed. Button LEDs are surfaced from the reviewed profile declaration,
 * then their GPIO is exported and verified only on an explicit [ledSet] write. A preparation proven
 * `ready` is cached for the process lifetime so rapid commands pay one privileged round trip, not two;
 * any failed preparation or failed value write invalidates the pin so the next command re-verifies
 * export and direction (Issue #93 — every command re-ran the full preparation on the serialized root
 * lane). Reads never consult or populate that cache; only the read-only startup [warmUp] may seed it with
 * pins the kernel already holds as writable outputs.
 *
 * The nodes are root-owned, so writes go through [Su]. On a panel without `su` the capability simply
 * doesn't activate (graceful — like the other root-gated controllers).
 *
 * ⚠️ UNTESTED on hardware — derived from the vendor paths reported in
 * seaky/nspanel_pro_tools_apk#98 + two stock firmware images; no S9E was available to validate. These
 * switch **mains loads**, so treat as experimental until confirmed on a real unit.
 */
class RelayController(profile: DeviceProfile, private val root: RootShell = Su) {

    // Candidate relay-class bases, primary first. The S9E renamed st_relay -> strelay across firmware,
    // so the profile supplies both; resolution picks the first whose dir actually holds relayN nodes.
    private val candidateBases: List<String> = listOfNotNull(profile.relayBase) + profile.relayBaseFallbacks
    private val ledBase: Int? = profile.buttonLedGpioBase

    @Volatile private var resolvedBase: String? = null
    @Volatile private var resolvedRelayCount: Int? = if (candidateBases.isEmpty()) 0 else null
    @Volatile private var resolvedLedCount: Int = if (ledBase == null) 0 else BUTTON_LED_COUNT

    // GPIOs whose output preparation was PROVEN (`ready`), guarded by the class monitor. ledSet and the
    // read-only warmUp populate it; a failed value write removes the pin because the kernel state it
    // proved (exported, direction out, writable value node) may no longer hold.
    private val preparedGpios = mutableSetOf<Int>()

    // Advanced under the monitor by every failed preparation or withdrawn proof, so a warm-up probe that
    // started before the failure cannot re-prove the pin after it.
    private var invalidations = 0L

    /** Resolve immutable sysfs topology once. A failed privileged probe is deliberately not cached:
     * root access can become available after boot. State reads still avoid repeating successful
     * directory probes on every MQTT heartbeat. */
    private fun resolveRelays(allowRootProbe: Boolean) {
        if (resolvedRelayCount != null || !allowRootProbe) return
        val cost = FeatureCosts.registry.span(FeatureCostOperation.RELAY_TOPOLOGY_DISCOVERY)
        try {
            for (candidate in candidateBases) {
                val count = try {
                    relayNodeCount(candidate)
                } catch (_: Exception) {
                    null
                }
                if (count == null) {
                    cost.outcome(FeatureCostOutcome.FAILURE)
                    return
                }
                if (count > 0) {
                    resolvedBase = candidate
                    resolvedRelayCount = count
                    cost.work(units = count.toLong())
                    return
                }
            }
            resolvedRelayCount = 0
        } finally {
            cost.close()
        }
    }

    private fun relayNodeCount(b: String): Int? {
        val out = root.listSysfs(b) ?: return null
        val present = out.split(Regex("\\s+"))
            .mapNotNull { name -> RELAY_NODE.matchEntire(name)?.groupValues?.get(1)?.toIntOrNull() }
            .filter { it > 0 }
            .toSet()
        var contiguous = 0
        while (contiguous + 1 in present) contiguous++
        return contiguous
    }

    /** Number of relays exposed (0 if no candidate relay base is present). */
    @Synchronized
    fun count(allowRootProbe: Boolean = true): Int {
        resolveRelays(allowRootProbe)
        return resolvedRelayCount ?: 0
    }

    @Synchronized
    fun available(): Boolean = count() > 0

    /** Set relay [n] (1-based) on/off. Returns true if the write ran. */
    @Synchronized
    fun set(n: Int, on: Boolean): Boolean {
        if (n !in 1..count()) return false
        val base = resolvedBase ?: return false
        val started = FeatureCosts.registry.beginSynchronous(FeatureCostOperation.RELAY_HARDWARE_WRITE)
        var outcome = FeatureCostOutcome.FAILURE
        try {
            val ok = root.writeSysfs("$base/relay$n", if (on) "1" else "0")
            if (ok) outcome = FeatureCostOutcome.SUCCESS
            return ok
        } finally {
            FeatureCosts.registry.finishSynchronous(
                FeatureCostOperation.RELAY_HARDWARE_WRITE,
                started,
                outcome = outcome,
                workUnits = 1,
            )
        }
    }

    /** Current state of relay [n], retaining the legacy false fallback for existing callers. */
    fun get(n: Int): Boolean = read(n) == true

    /** Physical state, preserving unreadable as null rather than inventing OFF. */
    @Synchronized
    fun read(n: Int): Boolean? {
        if (n !in 1..count()) return null
        val base = resolvedBase ?: return null
        val cost = FeatureCosts.registry.beginSynchronous(FeatureCostOperation.RELAY_STATE_READ)
        var outcome = FeatureCostOutcome.SUCCESS
        return try {
            when (root.readSysfs("$base/relay$n")?.trim()) {
                "1" -> true
                "0" -> false
                else -> null.also { outcome = FeatureCostOutcome.FAILURE }
            }
        } catch (failure: Exception) {
            outcome = FeatureCostOutcome.FAILURE
            throw failure
        } finally {
            FeatureCosts.registry.finishSynchronous(
                FeatureCostOperation.RELAY_STATE_READ,
                cost,
                outcome = outcome,
                workUnits = 1,
            )
        }
    }

    // --- S9E button LEDs: gpio <buttonLedGpioBase..+3>, on/off via su ---

    /** Number of profile-declared button LEDs (0 or 4). Discovery is deliberately read-only: importing
     * a profile must not export GPIOs or change pin directions merely because capabilities are listed. */
    @Synchronized
    fun ledCount(): Int = resolvedLedCount

    /**
     * Startup warm-up (Issue #93): record pins the kernel already holds as writable outputs — typically
     * left so by the previous ha-paneld process — so the first command after an app restart skips
     * preparation. Read-only by the same rule as [ledCount]: it never exports, changes a direction or
     * writes a value, so after a reboot it proves nothing and [ledSet] prepares lazily as before.
     *
     * The probe holds neither this monitor nor the interactive root shell, so a command arriving while it
     * runs, blocks or fails proceeds and re-verifies on its own. It shares only the bounded one-shot su
     * launch, which a command reaches only when the persistent shell is unusable (su dialect not yet
     * proven, or a failed round trip falling back). Proofs are recorded only when no failure was seen
     * meanwhile.
     */
    fun warmUp() {
        val base = ledBase ?: return
        val (epoch, pins) = synchronized(this) { invalidations to (0 until resolvedLedCount).map { base + it } }
        // Best-effort: any failure leaves the lazy path exactly as it was, so there is nothing to report.
        val ready = try {
            root.preparedOutputGpios(pins)
        } catch (_: Exception) {
            null
        } ?: return
        synchronized(this) { if (invalidations == epoch) preparedGpios += ready }
    }

    /** Set button LED [i] (0-based, F1..F4) on/off. */
    @Synchronized
    fun ledSet(i: Int, on: Boolean): Boolean {
        if (i !in 0 until ledCount()) return false
        val gpio = ledBase?.plus(i) ?: return false
        val started = FeatureCosts.registry.beginSynchronous(FeatureCostOperation.RELAY_HARDWARE_WRITE)
        var outcome = FeatureCostOutcome.FAILURE
        try {
            if (!ensureGpio(gpio)) return false
            val node = ledNode(i) ?: return false
            val ok = root.writeSysfs(node, if (on) "1" else "0")
            if (ok) {
                outcome = FeatureCostOutcome.SUCCESS
            } else {
                preparedGpios.remove(gpio)
                invalidations++
            }
            return ok
        } finally {
            FeatureCosts.registry.finishSynchronous(
                FeatureCostOperation.RELAY_HARDWARE_WRITE,
                started,
                outcome = outcome,
                workUnits = 1,
            )
        }
    }


    /** Physical button-LED state, preserving unreadable as null rather than inventing OFF. */
    @Synchronized
    fun ledRead(i: Int): Boolean? {
        if (i !in 0 until ledCount()) return null
        val node = ledNode(i) ?: return null
        val cost = FeatureCosts.registry.beginSynchronous(FeatureCostOperation.RELAY_STATE_READ)
        var outcome = FeatureCostOutcome.SUCCESS
        return try {
            when (root.readSysfs(node)?.trim()) {
                "1" -> true
                "0" -> false
                else -> null.also { outcome = FeatureCostOutcome.FAILURE }
            }
        } catch (failure: Exception) {
            outcome = FeatureCostOutcome.FAILURE
            throw failure
        } finally {
            FeatureCosts.registry.finishSynchronous(
                FeatureCostOperation.RELAY_STATE_READ,
                cost,
                outcome = outcome,
                workUnits = 1,
            )
        }
    }

    private fun ledNode(i: Int): String? = ledBase?.let { "/sys/class/gpio/gpio${it + i}/value" }

    /** Export [gpio] if the kernel hasn't (the S9E exports only gpio113 at boot), set it to output, and
     * verify the resulting direction and writable value node in one privileged round trip. Any failure
     * remains retryable: an input pin or partially initialised export must never be published as an LED.
     * A proven preparation is cached; only [ledSet]'s failed value write invalidates it. */
    private fun ensureGpio(gpio: Int): Boolean {
        if (gpio in preparedGpios) return true
        val started = FeatureCosts.registry.beginSynchronous(FeatureCostOperation.RELAY_GPIO_PREPARE)
        var outcome = FeatureCostOutcome.FAILURE
        try {
            val ready = root.prepareOutputGpio(gpio)
            if (ready) {
                preparedGpios.add(gpio)
                outcome = FeatureCostOutcome.SUCCESS
            } else {
                invalidations++
            }
            return ready
        } finally {
            FeatureCosts.registry.finishSynchronous(
                FeatureCostOperation.RELAY_GPIO_PREPARE,
                started,
                outcome = outcome,
                workUnits = 1,
            )
        }
    }

    private companion object {
        const val BUTTON_LED_COUNT = 4
        val RELAY_NODE = Regex("relay([1-9]\\d*)")
    }
}
