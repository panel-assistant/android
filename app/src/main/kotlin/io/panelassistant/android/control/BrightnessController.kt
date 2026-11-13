package io.panelassistant.android.control

import android.content.Context
import android.provider.Settings
import android.os.SystemClock
import android.util.Log
import io.panelassistant.android.Config
import io.panelassistant.android.platform.Daemon
import io.panelassistant.android.util.AccessDenialMemo
import io.panelassistant.android.util.Cached
import io.panelassistant.android.util.HelperClient
import io.panelassistant.android.util.SuccessStickyProbe
import java.io.File
import java.util.concurrent.atomic.AtomicLong

internal const val NEVER_SCREEN_TIMEOUT_MS = Int.MAX_VALUE
private const val DEFAULT_SCREEN_TIMEOUT_MS = 60_000
private const val SYS_BACKLIGHT = "/sys/class/backlight"

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
        displaced: DisplacedScreenTimeout? = null,
        rememberTimeout: (Int) -> Unit,
    ): ScreenTimeoutApplyResult {
        // While the screen-timeout screen-off has the panel asleep, the live value is its short timeout
        // and the real one is in the displaced record. Read and change the record, never the live value:
        // the short timeout must never be remembered as the firmware default, and the new choice takes
        // effect when the wake restores the record.
        val held = displaced?.displacedMs?.takeIf { it > 0 }
        if (held != null) {
            if (enabled && held in 1 until NEVER_SCREEN_TIMEOUT_MS) rememberTimeout(held)
            val target = if (enabled) NEVER_SCREEN_TIMEOUT_MS else savedTimeoutMs.takeIf { it > 0 } ?: DEFAULT_SCREEN_TIMEOUT_MS
            displaced?.displacedMs = target
            return ScreenTimeoutApplyResult(enabled, target, target, writeAccepted = true)
        }
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

    /**
     * Set the timeout to [ms]. While a screen-timeout sleep has the panel asleep, [held] is the value its
     * wake restores, so that is what changes; otherwise the live value, proved by reading it back.
     */
    fun set(ms: Int, held: DisplacedScreenTimeout?): Boolean {
        if (held != null) {
            held.displacedMs = ms
            return true
        }
        return write(ms) && read() == ms
    }
}

/** Android's screen-off timeout as Home Assistant's number shows it: whole seconds, from Android's 10 s floor. */
internal const val SCREEN_OFF_TIMEOUT_MIN_S = 10
internal const val SCREEN_OFF_TIMEOUT_MAX_S = NEVER_SCREEN_TIMEOUT_MS / 1_000

internal fun screenOffTimeoutSeconds(ms: Int): Int = (ms / 1_000).coerceIn(SCREEN_OFF_TIMEOUT_MIN_S, SCREEN_OFF_TIMEOUT_MAX_S)

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
    private val daemon: Daemon = HelperClient,
    private val scale: BacklightScale = BacklightScale.IDENTITY,
    private val setting: BrightnessSetting = SystemBrightnessSetting(context),
    // The SCREEN_OFF_TIMEOUT boundary prevent_idle_dim writes; a seam so a JVM test can refuse the write.
    private val screenTimeout: ScreenTimeoutController = systemScreenTimeout(context),
) : Backlight {
    constructor(context: Context) : this(context, HelperClient, BacklightScale.IDENTITY)

    private val hardwareWriter = BrightnessHardwareWriter(daemon, scale.nodeCurve)
    @Volatile private var loggedNoHardwareRoute = false
    private val successfulWrites = BacklightWriteTracker()
    private val writeSequencer = BrightnessWriteSequencer(
        actuator = BrightnessWriteSequencer.Actuator(::applyBrightnessUnserialized),
        successfulWrites = successfulWrites,
        elapsedRealtimeMs = BrightnessWriteSequencer.ElapsedRealtimeSource(SystemClock::elapsedRealtime),
    )
    private val writeAttribution = BrightnessWriteAttribution()

    fun canWrite(): Boolean = Settings.System.canWrite(context)

    /**
     * @param level 0–255. Switches the panel to manual brightness mode and applies [level], floored at
     * [MIN_VISIBLE] so a brightness command (HA, auto-brightness, navbar) can never leave the panel
     * blank + touch-dead. A deliberate screen-off is a separate operation via [ScreenController], which
     * uses [setBrightnessRaw] for its last-resort dim-to-0.
     */
    override fun setBrightness(level: Int) { trySetBrightness(level) }

    internal fun trySetBrightness(level: Int): Boolean = writeSequencer.write(level.coerceIn(MIN_VISIBLE, 255))

    /** Apply a raw level with NO minimum floor (0 allowed). For [ScreenController]'s screen-off fallback
     *  only — every other caller must use [setBrightness] to preserve the never-blank guarantee. */
    override fun setBrightnessRaw(level: Int) { writeSequencer.write(level.coerceIn(0, 255)) }

    private fun applyBrightnessUnserialized(level: Int): Boolean {
        val v = scale.settingFor(level)
        val settingWritten = try {
            setting.write(v).also { written -> if (written) Log.d(TAG, "brightness setting -> $v (level $level)") }
        } catch (e: SecurityException) {
            Log.w(TAG, "WRITE_SETTINGS not granted — cannot set brightness", e)
            false
        }
        if (settingWritten) {
            // Only a write Android accepted may claim the setting; a failed one leaves the last owner.
            scale.recordOwned(level, v)
            writeAttribution.record(v, SystemClock.elapsedRealtime())
        }
        // Also drive the real hardware node (Sonoff firmware idle-dims it directly and does not always
        // honour SCREEN_BRIGHTNESS). Only through the helper: these writes repeat on every ramp step, so su
        // is never used for them. Without a helper the Android setting is the actuator; say so once.
        val hardwareRoute = hardwareWriter.write(v)
        when (hardwareRoute) {
            BrightnessWriteRoute.HELPER -> Log.d(TAG, "hardware brightness -> $v (helper)")
            BrightnessWriteRoute.NONE -> if (!loggedNoHardwareRoute) {
                loggedNoHardwareRoute = true
                Log.i(TAG, "hardware backlight node not driven: the root helper did not accept BLREAD/BLSET; " +
                    "the Android brightness setting is the actuator (su is not used for repeated writes)")
            }
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
            AccessDenialMemo.app.read(
                key = "dir:$SYS_BACKLIGHT",
                what = "Backlight class $SYS_BACKLIGHT",
                probeDenied = { AccessDenialMemo.listDenied(SYS_BACKLIGHT) },
            ) { File(SYS_BACKLIGHT).listFiles() }?.sortedBy { it.name }?.firstNotNullOfOrNull { d ->
                val max = runCatching { File(d, "max_brightness").readText().trim().toIntOrNull() }.getOrNull()
                val f = File(d, "actual_brightness")
                val probe = runCatching { f.readText().trim().toIntOrNull() }.getOrNull()
                if (max != null && max > 0 && probe != null) f to max else null
            }
        }.getOrNull()
    })

    // Effective reads are cheap as plain files but a helper round-trip on the fallback path, and callers
    // include per-tick reconciles and UI polls — cache briefly; writes invalidate so a fresh set reads back.
    private val effective = Cached(EFFECTIVE_TTL_MS) { readEffective() }

    /** Sysfs actual_brightness mapped back to the HA scale through [scale] (plain file read, else the
     *  helper), or -1 when unavailable. Never su: this read repeats per tick and per UI poll. */
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
        return -1
    }

    /** The COMMANDED brightness — the Android setting, i.e. the 0–255 scale HA and the UI command in.
     *  Distinct from [getBrightness]: the framework maps the setting through a per-device brightness
     *  curve before driving the hardware node, so the effective (node) value is a DIFFERENT scale on
     *  curved panels (NSPanel Pro: setting 241 → node ~102). State publishes must use this scale. */
    override fun getCommanded(): Int = setting.read()?.let(::levelFromSetting) ?: -1

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
        return setting.read()?.let(::levelFromSetting) ?: -1
    }

    /**
     * Prevent the vendor firmware's idle backlight dim. Some panel firmwares dim the hardware backlight at
     * the `SCREEN_OFF_TIMEOUT` mark even while the OS keeps the screen on (stay-on-while-plugged), so the
     * panel goes very dim after the timeout despite `SCREEN_BRIGHTNESS` at max. Raising the timeout defers
     * that indefinitely. No root needed — `WRITE_SETTINGS` only.
     *
     * On (default, for mains-powered panels): the prior timeout is saved once, then set to "never". Off:
     * the saved firmware default is restored (60 s if none was captured).
     *
     * This is the transition, for an owner or Home Assistant change and the power-safety repair. Startup
     * uses [applyPreventIdleDimAtStart], which writes only until the first application has succeeded.
     */
    internal fun applyPreventIdleDim(on: Boolean, config: Config): ScreenTimeoutApplyResult = try {
        screenTimeout.apply(on, config.savedScreenOffTimeout, ConfigDisplacedScreenTimeout(config)) {
            config.savedScreenOffTimeout = it
        }.also { result ->
            // Off clears it; on records whether "never" took, so an application that failed is tried again
            // at the next start.
            config.preventIdleDimApplied = on && result.effective
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

    /**
     * Startup: apply "never" once, when the setting is on and the app has not yet applied it (the first run
     * with the default on, or an ON that was stored but never actuated). Android keeps SCREEN_OFF_TIMEOUT
     * across reboots, so once applied the start leaves it alone, and a timeout the owner later changes in
     * Android stays as they set it. Off writes nothing: its restore happened when it was turned off.
     *
     * An interrupted screen-timeout sleep is not this method's business: [ScreenController] puts its
     * displaced timeout back when it is constructed, before this runs.
     */
    internal fun applyPreventIdleDimAtStart(config: Config): ScreenTimeoutApplyResult? {
        if (!config.preventIdleDim || config.preventIdleDimApplied) return null
        return applyPreventIdleDim(true, config)
    }

    fun screenOffTimeoutMs(): Int = runCatching { screenTimeout.current() }.getOrDefault(-1)

    /** The timeout the panel uses: while a screen-timeout sleep holds it ([held]), the one its wake restores. */
    fun configuredScreenOffTimeoutMs(held: DisplacedScreenTimeout?): Int =
        held?.displacedMs?.takeIf { it > 0 } ?: screenOffTimeoutMs()

    fun setScreenOffTimeout(ms: Int, held: DisplacedScreenTimeout?): Boolean =
        runCatching { screenTimeout.set(ms, held) }.getOrDefault(false)

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
    @Synchronized fun write(level: Int): Boolean = actuator.apply(level).also { accepted ->
        if (accepted) successfulWrites.record(elapsedRealtimeMs.read())
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

/** Android's `SCREEN_BRIGHTNESS` setting, as the one seam [BrightnessController] reads and writes it through. */
internal interface BrightnessSetting {
    /** Select manual mode and write [value]; true when Android accepted it. May throw [SecurityException]. */
    fun write(value: Int): Boolean

    /** The raw setting, or null when it has never been set. */
    fun read(): Int?
}

internal class SystemBrightnessSetting(private val context: Context) : BrightnessSetting {
    override fun write(value: Int): Boolean {
        Settings.System.putInt(
            context.contentResolver,
            Settings.System.SCREEN_BRIGHTNESS_MODE,
            Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL,
        )
        return Settings.System.putInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, value)
    }

    override fun read(): Int? = try {
        Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS)
    } catch (e: Settings.SettingNotFoundException) {
        null
    }
}

private fun systemScreenTimeout(context: Context) = ScreenTimeoutController(
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
