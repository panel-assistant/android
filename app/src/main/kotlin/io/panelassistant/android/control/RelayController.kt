package io.panelassistant.android.control

import io.panelassistant.android.device.DeviceProfile
import io.panelassistant.android.metrics.FeatureCostOperation
import io.panelassistant.android.metrics.FeatureCostOutcome
import io.panelassistant.android.metrics.FeatureCosts
import io.panelassistant.android.platform.RootShell
import io.panelassistant.android.platform.Daemon
import io.panelassistant.android.platform.HelperRelayClass
import io.panelassistant.android.util.HelperClient
import io.panelassistant.android.util.MonotonicDeadline
import java.io.File

/**
 * On-board relay + button-LED control for profile-declared sysfs paths. The **Smatek S9E**
 * has two mains relays (sysfs class `/sys/class/strelay` on firmware 1.1.0+, `/sys/class/st_relay`
 * on the initial 1.0.2 — both probed) and four button LEDs at `/sys/class/gpio/gpio147`–`gpio150`, all
 * driven by `echo 1`/`echo 0`. The sysfs locations come from the active [DeviceProfile]
 * ([DeviceProfile.relayBase] + [DeviceProfile.relayBaseFallbacks] / [DeviceProfile.buttonLedGpioBase]); a
 * profile with none makes this controller inert with no helper reads. Relay
 * class nodes are runtime-confirmed. Button LEDs are surfaced from the reviewed profile declaration,
 * then their GPIO is exported and verified only on an explicit [ledSet] write. A preparation proven
 * `ready` is cached for the process lifetime so rapid commands pay one privileged round trip, not two;
 * any failed preparation or failed value write invalidates the pin so the next command re-verifies
 * export and direction (Issue #93 — every command re-ran the full preparation on the serialized root
 * lane). Reads never consult or populate that cache; only the read-only startup [warmUp] may seed it with
 * pins the kernel already holds as writable outputs.
 *
 * Relay classes readable and writable by the app use ordinary file I/O, without a helper or root.
 * Discovery is read-only and requires contiguous binary relay nodes. Otherwise passive observations
 * use fixed helper reads and explicit writes go through [Su]. Lazy GPIO preparation always uses [Su].
 *
 * ⚠️ UNTESTED on hardware — derived from the vendor paths reported in
 * seaky/nspanel_pro_tools_apk#98 + two stock firmware images; no S9E was available to validate. These
 * switch **mains loads**, so treat as experimental until confirmed on a real unit.
 */
class RelayController(
    profile: DeviceProfile,
    private val root: RootShell = Su,
    private val daemon: Daemon = HelperClient,
    private val nowNs: () -> Long = System::nanoTime,
    private val relayFile: (String) -> File = ::File,
) {

    // Candidate relay-class bases, primary first. The S9E renamed st_relay -> strelay across firmware,
    // so the profile supplies both; resolution picks the first whose dir actually holds relayN nodes.
    private val candidateBases: List<String> = listOfNotNull(profile.relayBase) + profile.relayBaseFallbacks
    private val ledBase: Int? = profile.buttonLedGpioBase

    @Volatile private var resolvedBase: String? = null
    private var directRelays = false
    @Volatile private var resolvedRelayCount: Int? = if (candidateBases.isEmpty()) 0 else null
    @Volatile private var resolvedLedCount: Int = if (ledBase == null) 0 else BUTTON_LED_COUNT

    // GPIOs whose output preparation was PROVEN (`ready`), guarded by the class monitor. ledSet and the
    // read-only warmUp populate it; a failed value write removes the pin because the kernel state it
    // proved (exported, direction out, writable value node) may no longer hold.
    private val preparedGpios = mutableSetOf<Int>()

    // Advanced under the monitor by every failed preparation or withdrawn proof, so a warm-up probe that
    // started before the failure cannot re-prove the pin after it.
    private var invalidations = 0L

    /** Resolve topology without holding the actuator monitor. Unknown observations remain retryable. */
    private fun resolveRelays() {
        if (resolvedRelayCount != null) return
        val cost = FeatureCosts.registry.span(FeatureCostOperation.RELAY_TOPOLOGY_DISCOVERY)
        try {
            var probeFailed = false
            for (candidate in candidateBases) {
                // Keep direct I/O inside the same reviewed class allowlist as helper observations.
                val relayClass = HelperRelayClass.fromSysfsBase(candidate)
                if (relayClass == null) {
                    probeFailed = true
                    continue
                }
                val directCount = directRelayCount(candidate)
                val count = try {
                    directCount ?: daemon.relayCount(relayClass)
                } catch (_: Exception) {
                    null
                }
                if (count == null) {
                    probeFailed = true
                    continue
                }
                if (count > 0) {
                    synchronized(this) {
                        if (resolvedRelayCount == null) {
                            resolvedBase = candidate
                            directRelays = directCount != null
                            resolvedRelayCount = count
                        }
                    }
                    cost.work(units = count.toLong())
                    return
                }
            }
            // An unknown candidate may recover. Absence is established only when all are known empty.
            if (probeFailed) cost.outcome(FeatureCostOutcome.FAILURE) else synchronized(this) {
                if (resolvedRelayCount == null) resolvedRelayCount = 0
            }
        } finally {
            cost.close()
        }
    }

    /** Access checks run as the app UID. Never write a node merely to discover capabilities. */
    private fun directRelayCount(base: String): Int? = try {
        val names = relayFile(base).list()?.filter { it.matches(Regex("relay[1-9][0-9]*")) }
        if (names.isNullOrEmpty() || names.size > 16 ||
            names.toSet() != (1..names.size).map { "relay$it" }.toSet()) null
        else names.size.takeIf {
            names.all { name ->
                val node = relayFile("$base/$name")
                node.canRead() && node.canWrite() && readDirect(node) != null
            }
        }
    } catch (_: Exception) { null }

    private fun readDirect(node: File): Boolean? = try {
        when (node.readText().trim()) { "0" -> false; "1" -> true; else -> null }
    } catch (_: Exception) { null }

    /** Number of confirmed relays (0 also when discovery is currently unavailable). */
    fun count(): Int {
        resolveRelays()
        return resolvedRelayCount ?: 0
    }

    fun available(): Boolean = count() > 0

    /** Set relay [n] (1-based) on/off. Returns true if the write ran. */
    fun set(n: Int, on: Boolean): Boolean {
        if (n !in 1..count()) return false
        return synchronized(this) { setResolved(n, on) }
    }

    private fun setResolved(n: Int, on: Boolean): Boolean {
        val base = resolvedBase ?: return false
        val started = FeatureCosts.registry.beginSynchronous(FeatureCostOperation.RELAY_HARDWARE_WRITE)
        var outcome = FeatureCostOutcome.FAILURE
        try {
            val value = if (on) "1" else "0"
            val ok = if (directRelays) {
                try { relayFile("$base/relay$n").writeText(value); true } catch (_: Exception) { false }
            } else root.writeSysfs("$base/relay$n", value)
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
    fun read(n: Int): Boolean? {
        if (n !in 1..count()) return null
        val relayClass = resolvedBase?.let(HelperRelayClass::fromSysfsBase) ?: return null
        val cost = FeatureCosts.registry.beginSynchronous(FeatureCostOperation.RELAY_STATE_READ)
        var outcome = FeatureCostOutcome.SUCCESS
        return try {
            (if (directRelays) readDirect(relayFile("${relayClass.sysfsBase}/relay$n"))
            else daemon.relayRead(relayClass, n)).also { if (it == null) outcome = FeatureCostOutcome.FAILURE }
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
     * Helper I/O holds no controller monitor or root lane, so commands can re-verify independently.
     * All pins share a five-second budget; each connection, VERSION and complete reply consumes the
     * remaining time. Proofs are recorded only when no write/preparation failure was seen meanwhile.
     */
    fun warmUp() {
        val base = ledBase ?: return
        val (epoch, pins) = synchronized(this) { invalidations to (0 until resolvedLedCount).map { base + it } }
        val deadline = MonotonicDeadline(5_000L, nowNs)
        val ready = mutableSetOf<Int>()
        for (pin in pins) {
            val remaining = deadline.remainingMs()
            if (remaining == 0L) break
            val observed = try { daemon.gpioRead(pin, remaining.coerceAtMost(1_500L)) } catch (_: Exception) { null }
            if (deadline.remainingMs() > 0L && observed?.prepared == true) ready += pin
        }
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
    fun ledRead(i: Int): Boolean? {
        if (i !in 0 until ledCount()) return null
        val pin = ledBase?.plus(i) ?: return null
        val cost = FeatureCosts.registry.beginSynchronous(FeatureCostOperation.RELAY_STATE_READ)
        var outcome = FeatureCostOutcome.SUCCESS
        return try {
            daemon.gpioRead(pin)?.value.also { if (it == null) outcome = FeatureCostOutcome.FAILURE }
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
    }
}
