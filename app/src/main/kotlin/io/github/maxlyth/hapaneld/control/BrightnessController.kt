package io.github.maxlyth.hapaneld.control

import android.content.Context
import android.provider.Settings
import android.os.SystemClock
import android.util.Log
import io.github.maxlyth.hapaneld.Config
import io.github.maxlyth.hapaneld.platform.Daemon
import io.github.maxlyth.hapaneld.platform.RootShell
import io.github.maxlyth.hapaneld.util.Cached
import io.github.maxlyth.hapaneld.util.HelperClient
import io.github.maxlyth.hapaneld.util.SuccessStickyProbe
import java.io.File
import java.util.concurrent.atomic.AtomicLong

internal const val NEVER_SCREEN_TIMEOUT_MS = Int.MAX_VALUE
private const val DEFAULT_SCREEN_TIMEOUT_MS = 60_000

internal data class ScreenTimeoutApplyResult(
    val enabled: Boolean,
    val targetMs: Int,
    val observedMs: Int,
    val writeAccepted: Boolean,
) {
    val effective: Boolean get() = observedMs == targetMs
}

/** Small deterministic boundary around Settings.System's boolean write result and mandatory readback. */
internal class ScreenTimeoutController(
    private val read: () -> Int,
    private val write: (Int) -> Boolean,
) {
    fun apply(
        enabled: Boolean,
        savedTimeoutMs: Int,
        rememberTimeout: (Int) -> Unit,
    ): ScreenTimeoutApplyResult {
        val before = read()
        if (enabled && before in 1 until NEVER_SCREEN_TIMEOUT_MS) rememberTimeout(before)
        val target = if (enabled) {
            NEVER_SCREEN_TIMEOUT_MS
        } else {
            savedTimeoutMs.takeIf { it > 0 } ?: DEFAULT_SCREEN_TIMEOUT_MS
        }
        val accepted = write(target)
        return ScreenTimeoutApplyResult(enabled, target, read(), accepted)
    }

    fun current(): Int = read()
}

internal fun preventIdleDimDiagnostic(enabled: Boolean, timeoutMs: Int): String {
    val state = if (enabled) "on" else "off"
    if (timeoutMs < 0) return "$state · timeout unknown"
    val timeout = if (timeoutMs == NEVER_SCREEN_TIMEOUT_MS) "never" else "${timeoutMs / 1_000}s"
    return when {
        enabled && timeoutMs == NEVER_SCREEN_TIMEOUT_MS -> "$state · timeout never"
        enabled -> "$state · timeout $timeout (not applied)"
        else -> "$state · timeout $timeout"
    }
}

/**
 * Screen brightness via the standard `Settings.System` API — no vendor lib. Requires the
 * `WRITE_SETTINGS` app-op (granted at provisioning via `appops set <pkg> WRITE_SETTINGS allow`,
 * or via the setup Activity on non-root installs).
 *
 * Actuator-first by design: brightness *policy* (from room lux / occupancy) is normally computed
 * HA-side and pushed here. The one opt-in exception is [AutoBrightnessController]
 * (`switch.<panel>_auto_brightness`, default off), which runs an on-device curve off the panel's light
 * sensor (or HA-fed lux) — see its docs for why that's worth a local loop.
 *
 * HA brightness is 0–255, and so is Android `SCREEN_BRIGHTNESS`. Without a profile curve they map 1:1.
 * With one, [scale] decides which actuator carries the curve and maps every read back to the HA scale.
 */
class BrightnessController internal constructor(
    private val context: Context,
    private val root: RootShell = Su,
    private val daemon: Daemon = HelperClient,
    private val scale: BacklightScale = BacklightScale.IDENTITY,
) : Backlight {
    constructor(context: Context) : this(context, Su, HelperClient, BacklightScale.IDENTITY)

    private val hardwareWriter = BrightnessHardwareWriter(root, daemon, scale.nodeCurve)
    private val successfulWrites = BacklightWriteTracker()
    private val writeSequencer = BrightnessWriteSequencer(
        actuator = BrightnessWriteSequencer.Actuator(::applyBrightnessUnserialized),
        successfulWrites = successfulWrites,
        elapsedRealtimeMs = BrightnessWriteSequencer.ElapsedRealtimeSource(SystemClock::elapsedRealtime),
    )
    private val writeAttribution = BrightnessWriteAttribution()
    private val screenTimeout = ScreenTimeoutController(
        read = {
            Settings.System.getInt(
                context.contentResolver,
                Settings.System.SCREEN_OFF_TIMEOUT,
                -1,
            )
        },
        write = { value ->
            Settings.System.putInt(
                context.contentResolver,
                Settings.System.SCREEN_OFF_TIMEOUT,
                value,
            )
        },
    )

    fun canWrite(): Boolean = Settings.System.canWrite(context)

    // The hardware backlight node (path, max). Sonoff firmware idle-dims this sysfs node directly and does
    // NOT honour SCREEN_BRIGHTNESS, so we read + drive it to keep HA in sync with the real backlight (and
    // to actually move it). Null → no node or no root → fall back to the Android setting. A successful
    // discovery is sticky, while a transient boot/root failure retries under bounded backoff.
    private val backlight = SuccessStickyProbe(probe = {
        val dir = root.runOutput("ls -d /sys/class/backlight/*/ 2>/dev/null | head -1")?.trim()
        val max = dir?.takeIf(String::isNotEmpty)
            ?.let { root.runOutput("cat ${it}max_brightness 2>/dev/null")?.trim()?.toIntOrNull() }
        if (dir.isNullOrEmpty() || max == null || max <= 0) null else BacklightNode(dir, max)
    })

    /**
     * @param level 0–255. Switches the panel to manual brightness mode and applies [level], floored at
     * [MIN_VISIBLE] so a brightness command (HA, auto-brightness, navbar) can never leave the panel
     * blank + touch-dead. A deliberate screen-off is a separate operation via [ScreenController], which
     * uses [setBrightnessRaw] for its last-resort dim-to-0.
     */
    override fun setBrightness(level: Int) = writeSequencer.write(level.coerceIn(MIN_VISIBLE, 255))

    /** Apply a raw level with NO minimum floor (0 allowed). For [ScreenController]'s screen-off fallback
     *  only — every other caller must use [setBrightness] to preserve the never-blank guarantee. */
    override fun setBrightnessRaw(level: Int) = writeSequencer.write(level.coerceIn(0, 255))

    private fun applyBrightnessUnserialized(level: Int): Boolean {
        val v = scale.settingFor(level)
        scale.recordOwned(level, v)
        val settingWritten = try {
            Settings.System.putInt(
                context.contentResolver,
                Settings.System.SCREEN_BRIGHTNESS_MODE,
                Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL,
            )
            Settings.System.putInt(
                context.contentResolver,
                Settings.System.SCREEN_BRIGHTNESS,
                v,
            ).also { written -> if (written) Log.d(TAG, "brightness setting -> $v") }
        } catch (e: SecurityException) {
            Log.w(TAG, "WRITE_SETTINGS not granted — cannot set brightness", e)
            false
        }
        if (settingWritten) writeAttribution.record(v, SystemClock.elapsedRealtime())
        // Also drive the real hardware node. A discovered su path is only a candidate: its write must
        // succeed, otherwise the helper gets the same operation rather than being masked by stale metadata.
        val hardwareRoute = hardwareWriter.write(v, backlight.get())
        when (hardwareRoute) {
            BrightnessWriteRoute.SU -> Log.d(TAG, "hardware brightness -> $v (su)")
            BrightnessWriteRoute.HELPER -> Log.d(TAG, "hardware brightness -> $v (helper)")
            BrightnessWriteRoute.NONE -> Unit // Android setting is the legitimate actuator on many panels.
        }
        effective.invalidate()   // a read right after a set must see the new level, not the cache
        return settingWritten || hardwareRoute != BrightnessWriteRoute.NONE
    }

    /** One atomic observation; never acquires the brightness, screen, or adaptive-policy monitor. */
    internal fun lastSuccessfulWriteElapsed(): Long = successfulWrites.snapshot()

    // Read path for the EFFECTIVE backlight. The sysfs node carries the generic `sysfs` SELinux label
    // on the supported panels, so actual_brightness is usually readable with NO root — which is what
    // makes effective reporting work on daemon-only panels (TPA10) whose firmware also moves the
    // backlight behind SCREEN_BRIGHTNESS's back. Verified by actually reading during discovery
    // (canRead() only checks DAC; SELinux denials surface as the read throwing).
    private val readNode = SuccessStickyProbe(probe = {
        runCatching {
            File("/sys/class/backlight").listFiles()?.sortedBy { it.name }?.firstNotNullOfOrNull { d ->
                val max = runCatching { File(d, "max_brightness").readText().trim().toIntOrNull() }.getOrNull()
                val f = File(d, "actual_brightness")
                val probe = runCatching { f.readText().trim().toIntOrNull() }.getOrNull()
                if (max != null && max > 0 && probe != null) f to max else null
            }
        }.getOrNull()
    })

    // Effective reads are cheap as plain files but an su round-trip on the fallback path, and callers
    // include per-tick reconciles and UI polls — cache briefly; writes invalidate so a fresh set reads back.
    private val effective = Cached(EFFECTIVE_TTL_MS) { readEffective() }

    /** Sysfs actual_brightness mapped back to the HA scale through [scale] (plain file read, else the
     *  helper, else su), or -1 when unavailable. */
    private fun readEffective(): Int {
        readNode.get()?.let { (f, max) ->
            runCatching { f.readText().trim().toIntOrNull() }.getOrNull()?.let {
                return scale.levelFromNode(it, max)
            }
        }
        // Daemon leg (TPA10-class panels: the app is SELinux-denied on the node and has no su).
        parseBacklightReading(daemon.send("BLREAD"))?.let {
            return scale.levelFromNode(it.actual, it.maximum)
        }
        backlight.get()?.let { node ->
            root.runOutput("cat ${node.directory}actual_brightness 2>/dev/null")?.trim()?.toIntOrNull()?.let {
                return scale.levelFromNode(it, node.maximum)
            }
        }
        return -1
    }

    /** The COMMANDED brightness — the Android setting, i.e. the 0–255 scale HA and the UI command in.
     *  Distinct from [getBrightness]: the framework maps the setting through a per-device brightness
     *  curve before driving the hardware node, so the effective (node) value is a DIFFERENT scale on
     *  curved panels (NSPanel Pro: setting 241 → node ~102). State publishes must use this scale. */
    fun getCommanded(): Int = try {
        levelFromSetting(Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS))
    } catch (e: Settings.SettingNotFoundException) {
        -1
    }

    /** The HA level a raw `SCREEN_BRIGHTNESS` value stands for (the profile's setting route curves it). */
    fun levelFromSetting(setting: Int): Int = scale.levelFromSetting(setting)

    /** Whether reads are already on the HA scale, so observers must not apply the framework ratio again. */
    val curved: Boolean get() = scale.curved

    /** Consume a recent successful write to SCREEN_BRIGHTNESS performed by this controller. [setting] is
     *  the raw setting value, which the attribution journal records. */
    fun consumeOwnedSettingChange(setting: Int, nowMs: Long = SystemClock.elapsedRealtime()): Boolean =
        writeAttribution.consume(setting.coerceIn(0, 255), nowMs)

    /** Reports the EFFECTIVE backlight (sysfs actual_brightness, scaled to 0–255) so HA reflects external /
     *  firmware dimming that bypasses SCREEN_BRIGHTNESS; falls back to the Android setting. */
    override fun getBrightness(): Int {
        val eff = effective.get()
        if (eff >= 0) return eff
        return try {
            levelFromSetting(Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS))
        } catch (e: Settings.SettingNotFoundException) {
            -1
        }
    }

    /**
     * Prevent the vendor firmware's idle backlight dim. Some panel firmwares dim the hardware backlight at
     * the `SCREEN_OFF_TIMEOUT` mark even while the OS keeps the screen on (stay-on-while-plugged), so the
     * panel goes very dim after the timeout despite `SCREEN_BRIGHTNESS` at max. Raising the timeout defers
     * that indefinitely. No root needed — `WRITE_SETTINGS` only.
     *
     * On (default, for mains-powered panels): the prior timeout is saved once, then set to "never". Off:
     * the saved firmware default is restored (60 s if none was captured).
     */
    internal fun applyPreventIdleDim(on: Boolean, config: Config): ScreenTimeoutApplyResult = try {
        screenTimeout.apply(on, config.savedScreenOffTimeout) { config.savedScreenOffTimeout = it }.also { result ->
            if (result.effective && result.writeAccepted) {
                Log.d(TAG, "prevent idle dim ${if (on) "ON" else "OFF"}: screen_off_timeout -> ${result.observedMs}")
            } else if (result.effective) {
                Log.d(
                    TAG,
                    "prevent idle dim ${if (on) "ON" else "OFF"}: timeout already effective " +
                        "despite rejected write (${result.observedMs})",
                )
            } else {
                Log.w(
                    TAG,
                    "prevent idle dim ${if (on) "ON" else "OFF"} ineffective: " +
                        "write=${result.writeAccepted} target=${result.targetMs} observed=${result.observedMs}",
                )
            }
        }
    } catch (e: SecurityException) {
        Log.w(TAG, "WRITE_SETTINGS not granted — cannot set screen_off_timeout", e)
        ScreenTimeoutApplyResult(
            enabled = on,
            targetMs = if (on) NEVER_SCREEN_TIMEOUT_MS else config.savedScreenOffTimeout.takeIf { it > 0 }
                ?: DEFAULT_SCREEN_TIMEOUT_MS,
            observedMs = -1,
            writeAccepted = false,
        )
    }

    fun screenOffTimeoutMs(): Int = runCatching { screenTimeout.current() }.getOrDefault(-1)

    companion object {
        private const val TAG = "ha-paneld/brightness"
        internal const val MIN_VISIBLE = 10 // shared command floor: a dim command must never blank the panel
        private const val EFFECTIVE_TTL_MS = 5_000L // effective-backlight read cache (UI polls + reconcile ticks)
    }
}

/** One ordering boundary around the complete framework + hardware brightness transaction. */
internal class BrightnessWriteSequencer(
    private val actuator: Actuator,
    private val successfulWrites: BacklightWriteTracker,
    private val elapsedRealtimeMs: ElapsedRealtimeSource,
) {
    @Synchronized fun write(level: Int) {
        if (actuator.apply(level)) successfulWrites.record(elapsedRealtimeMs.read())
    }

    /** Primitive seams keep repeated low-end-device writes allocation-free. */
    internal fun interface Actuator { fun apply(level: Int): Boolean }
    internal fun interface ElapsedRealtimeSource { fun read(): Long }
}

/** Successful-write recency belongs to the actuator; policy only takes atomic snapshots. */
internal class BacklightWriteTracker {
    private val lastWriteElapsed = AtomicLong(Long.MIN_VALUE)

    fun record(elapsedRealtimeMs: Long) = lastWriteElapsed.set(elapsedRealtimeMs)
    fun snapshot(): Long = lastWriteElapsed.get()
}
