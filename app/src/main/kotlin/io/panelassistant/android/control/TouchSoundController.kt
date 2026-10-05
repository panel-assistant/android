package io.panelassistant.android.control

import android.content.Context
import android.content.SharedPreferences
import android.media.AudioManager
import android.media.SoundPool
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import io.panelassistant.android.persistence.AppState
import java.io.File
import kotlin.math.PI
import kotlin.math.sin

/**
 * UI touch-sound control, made consistent across the fleet from HA.
 *
 * Two parts, because neither alone is enough on these panels:
 *  1. The system `SOUND_EFFECTS_ENABLED` flag covers native UI where the OS honours it. We deliberately
 *     do not own `STREAM_SYSTEM`: Android 14 may alias it to ring, where boot-chime suppression must keep
 *     it muted. Touch sound and boot-chime policy must never fight over the same stream.
 *  2. ha-paneld's OWN click. Dashboard taps live in the WebView, which never plays Android touch sounds,
 *     and the IME has its own keypress-sound pref we don't control — so neither covers a wall-panel
 *     dashboard. We subscribe to [PanelTouchObserver]'s shared 1 px `FLAG_WATCH_OUTSIDE_TOUCH` overlay,
 *     which receives `ACTION_OUTSIDE` without consuming it, and play an app-owned generated click on the
 *     media stream. This avoids firmware sample paths and remains independent of ring/system muting
 *     while respecting the user's media volume.
 *
 * App-direct: `SOUND_EFFECTS_ENABLED` needs `WRITE_SETTINGS`; the overlay needs `SYSTEM_ALERT_WINDOW`
 * (already held for the navbar). No root, no daemon.
 */
class TouchSoundController(context: Context, clickGain: Float) {
    private val ctx = context.applicationContext
    private val clickGain = clickGain.coerceIn(0.05f, 1f)
    private val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val main = Handler(Looper.getMainLooper())
    private val touchObserver = PanelTouchObserver.shared(ctx)
    private val statePolicy = TouchSoundStatePolicy(
        AndroidTouchSoundStateStore(AppState.preferences(ctx, "controller-state", STATE_PREFS)),
        AndroidTouchSoundHardware(ctx),
    )

    private var pool: SoundPool? = null
    private var clickId = 0
    @Volatile private var clickReady = false
    private var touchSubscription: PanelTouchObserver.Subscription? = null

    /**
     * What the platform flag says right now, or null when it cannot be read.
     *
     * This is an observation and never a report. `SOUND_EFFECTS_ENABLED` is a global that any holder of
     * `WRITE_SETTINGS` can write, so firmware and vendor settings apps move it under ha-paneld. A surface
     * that showed this value would change the panel's touch-sound setting with no user action, and the
     * next unrelated Configure save would then post a value that no longer matches and queue a
     * touch-sound edit nobody asked for. Only [resolveTouchSoundIntent] may consume it, and only while
     * the panel has no persisted intent yet.
     */
    fun observedPlatformState(): Boolean? = runCatching {
        Settings.System.getString(ctx.contentResolver, Settings.System.SOUND_EFFECTS_ENABLED)?.toIntOrNull()
    }.getOrNull()?.let { it == 1 }

    /** Whether ha-paneld has ever asserted touch sound on this panel, or null if it never has. Actuation
     *  bookkeeping carried across an upgrade, not a value any surface may report. */
    fun recordedState(): Boolean? = statePolicy.recordedState()

    fun set(on: Boolean): Boolean = apply(on).applied

    /**
     * Make the hardware match a durable intent at startup, without performing a transition.
     *
     * [apply] is a transition, and turning touch sound off there restores whatever `SOUND_EFFECTS_ENABLED`
     * held before ha-paneld first enabled it, which is the right thing the moment a user switches it off.
     * A reassertion is not that: the captured prior belongs to a transition that already happened, and
     * replaying it every boot would let a stale capture contradict the very intent it is meant to enforce
     * (a panel whose pre-ha-paneld flag was on would restore the click it was told to silence). So an
     * intended OFF is asserted directly and the restore memory is left for a real transition.
     */
    @Synchronized
    internal fun reassert(on: Boolean): ControlApplyOutcome {
        return try {
            if (on) {
                val enabled = statePolicy.enable()
                if (!enabled.applied) {
                    Log.w(TAG, "touch sound reassert on refused: prior state could not be captured durably")
                    return enabled
                }
                am.loadSoundEffects()
                enableOverlay()
            } else {
                disableOverlay()
                val disabled = statePolicy.assertDisabled()
                if (!disabled.applied) {
                    Log.w(TAG, "touch sound reassert off failed; the panel may still be audible")
                    return disabled
                }
            }
            Log.i(TAG, "touch sound reasserted -> ${if (on) "on" else "off"}")
            ControlApplyOutcome.APPLIED
        } catch (error: Exception) {
            Log.w(TAG, "touch sound reassert failed: ${error.message}")
            ControlApplyOutcome.FAILED
        }
    }

    /**
     * Apply the transition and report what the attempt learned. Touch sound owns `SOUND_EFFECTS_ENABLED`
     * through `WRITE_SETTINGS` alone, so a panel that has never granted that permission can never apply
     * this value, and a caller journalling the intent has to be able to tell that from a failure.
     */
    @Synchronized
    internal fun apply(on: Boolean): ControlApplyOutcome {
        return try {
            if (on) {
                val enabled = statePolicy.enable()
                if (!enabled.applied) {
                    Log.w(TAG, "touch sound enable refused: prior state could not be captured durably")
                    return enabled
                }
                am.loadSoundEffects()
                enableOverlay()
            } else {
                disableOverlay()
                val disabled = statePolicy.disable()
                if (!disabled.applied) {
                    Log.w(TAG, "touch sound exact restore failed; retained for retry")
                    return disabled
                }
            }
            Log.i(TAG, "touch sound -> ${if (on) "on" else "off"}")
            ControlApplyOutcome.APPLIED
        } catch (error: Exception) {
            Log.w(TAG, "touch sound set failed: ${error.message}")
            ControlApplyOutcome.FAILED
        }
    }

    @Suppress("DEPRECATION")
    private fun ensurePool() {
        if (pool != null) return
        val sp = SoundPool(2, AudioManager.STREAM_MUSIC, 0)
        val nativeSample = listOf(
            "/system/media/audio/ui/KeypressStandard.ogg",
            "/product/media/audio/ui/KeypressStandard.ogg",
            "/system/media/audio/ui/Effect_Tick.ogg",
            "/product/media/audio/ui/Effect_Tick.ogg",
        ).map(::File).firstOrNull(File::canRead)
        val sample = nativeSample ?: File(ctx.cacheDir, CLICK_FILE).also { owned ->
            val bytes = touchClickWav()
            if (!owned.isFile || owned.length() != bytes.size.toLong()) {
                runCatching { owned.writeBytes(bytes) }
                    .onFailure { Log.w(TAG, "could not create owned touch sample: ${it.message}") }
            }
        }
        sp.setOnLoadCompleteListener { _, sampleId, status ->
            clickId = sampleId
            clickReady = status == 0
            if (clickReady) Log.i(TAG, "touch sample ready: ${sample.name} gain=$clickGain")
            else Log.w(TAG, "touch sample load failed: ${sample.name} status=$status")
        }
        clickId = if (sample.canRead()) sp.load(sample.absolutePath, 1) else 0
        if (clickId == 0) Log.w(TAG, "owned touch sample could not be queued; overlay click will be silent")
        pool = sp
    }

    private fun enableOverlay() = main.post {
        if (touchSubscription != null) return@post
        ensurePool()
        touchSubscription = touchObserver.subscribe {
            Log.d(TAG, "tap -> click")
            pool?.takeIf { clickId != 0 && clickReady }
                ?.play(clickId, clickGain, clickGain, 1, 0, 1f)
        }
        if (touchSubscription == null) Log.w(TAG, "touch-click overlay unavailable")
    }

    private fun disableOverlay() = main.post {
        touchSubscription?.close()
        touchSubscription = null
    }

    companion object {
        private const val TAG = "ha-paneld/touchsound"
        private const val STATE_PREFS = "ha-paneld-controller-state"
        private const val CLICK_FILE = "ha-paneld-touch-click-v1.wav"
    }
}

/** Small deterministic PCM click owned by ha-paneld; no vendor audio-file dependency. */
internal fun touchClickWav(sampleRate: Int = 16_000, durationMs: Int = 58): ByteArray {
    val frames = (sampleRate * durationMs / 1_000).coerceAtLeast(1)
    val dataBytes = frames * 2
    val out = ByteArray(44 + dataBytes)
    fun ascii(offset: Int, value: String) = value.forEachIndexed { index, char -> out[offset + index] = char.code.toByte() }
    fun le16(offset: Int, value: Int) {
        out[offset] = value.toByte(); out[offset + 1] = (value ushr 8).toByte()
    }
    fun le32(offset: Int, value: Int) {
        out[offset] = value.toByte(); out[offset + 1] = (value ushr 8).toByte()
        out[offset + 2] = (value ushr 16).toByte(); out[offset + 3] = (value ushr 24).toByte()
    }
    ascii(0, "RIFF"); le32(4, out.size - 8); ascii(8, "WAVE"); ascii(12, "fmt ")
    le32(16, 16); le16(20, 1); le16(22, 1); le32(24, sampleRate)
    le32(28, sampleRate * 2); le16(32, 2); le16(34, 16); ascii(36, "data"); le32(40, dataBytes)
    repeat(frames) { index ->
        val elapsed = index.toDouble() / sampleRate
        val remaining = (frames - index).toDouble() / frames
        val attack = (index / 10.0).coerceAtMost(1.0)
        val envelope = remaining * remaining * attack
        val body = sin(2.0 * PI * 215.0 * elapsed) + 0.28 * sin(2.0 * PI * 108.0 * elapsed)
        val sample = (body * envelope * 8_200.0).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
        le16(44 + index * 2, sample)
    }
    return out
}

/**
 * Where a reported touch-sound value came from.
 *
 * The distinction is the whole point of this type. Only [INTENT] is a value a user, Home Assistant or a
 * restored configuration bundle actually chose, and only [INTENT] may reach a reporting surface. [ADOPTED]
 * and [DEFAULT] both describe a panel that has not resolved its intent yet: they are resolved exactly once
 * at startup, written to configuration, and never consulted again.
 */
internal enum class TouchSoundOrigin { INTENT, ADOPTED, DEFAULT }

/** A resolved touch-sound value together with the authority it came from. */
internal data class TouchSoundResolution(val enabled: Boolean, val origin: TouchSoundOrigin) {
    /**
     * Whether this resolution still has to be written down.
     *
     * A resolution that already came from persisted intent is the stored value, so committing it again
     * would be a write with nothing to say. Everything else is a panel mid-migration whose answer only
     * becomes authoritative once it is durable — and if that write fails, nothing has been decided and
     * the next boot resolves from the same evidence.
     */
    val needsAdoption: Boolean get() = origin != TouchSoundOrigin.INTENT
}

/**
 * The one place that decides what this panel's touch sound is.
 *
 * Ordered by authority, and the order is the fix. A persisted intent wins outright, so once a panel has
 * one, nothing observed about the hardware can move the reported value again — which is what stops an
 * unrelated Configure save from posting a stale value and minting a live-setting edit for a key the user
 * never touched. Below that, an upgrading panel is adopted from ha-paneld's own actuation record, then
 * from a single reading of the platform flag, and a panel that can offer neither takes the registry
 * default. Pure, so every one of those transitions is provable without a device.
 */
internal fun resolveTouchSoundIntent(
    persistedIntent: Boolean?,
    controllerRecord: Boolean?,
    observedHardware: Boolean?,
    registryDefault: Boolean,
): TouchSoundResolution = when {
    persistedIntent != null -> TouchSoundResolution(persistedIntent, TouchSoundOrigin.INTENT)
    controllerRecord != null -> TouchSoundResolution(controllerRecord, TouchSoundOrigin.ADOPTED)
    observedHardware != null -> TouchSoundResolution(observedHardware, TouchSoundOrigin.ADOPTED)
    else -> TouchSoundResolution(registryDefault, TouchSoundOrigin.DEFAULT)
}

internal data class TouchSoundState(
    val effectsSetting: Int?,
)

internal interface TouchSoundStateStore {
    fun active(): Boolean?
    fun prior(): TouchSoundState?
    fun saveEnabled(prior: TouchSoundState): Boolean
    fun saveDisabledAndClearPrior(): Boolean
    fun retireLegacyStreamState(): Boolean
}

internal interface TouchSoundHardware {
    fun capture(): TouchSoundState?
    fun enable(): ControlApplyOutcome
    fun restore(state: TouchSoundState): ControlApplyOutcome
    fun disableConservatively(): ControlApplyOutcome
}

/** State machine separated from Android UI/audio objects so ordering and legacy behavior stay testable. */
internal class TouchSoundStatePolicy(
    private val store: TouchSoundStateStore,
    private val hardware: TouchSoundHardware,
) {
    init {
        // Older builds captured STREAM_SYSTEM even though restoring it could undo boot-chime muting.
        // Retire that orphaned preference once; touch sound now owns only SOUND_EFFECTS_ENABLED.
        store.retireLegacyStreamState()
    }

    /** ha-paneld's own actuation record. Null means this panel has never been asserted either way, which
     *  is a question about migration state and never a value to report. */
    fun recordedState(): Boolean? = store.active()

    fun enable(): ControlApplyOutcome {
        // A capture or persistence failure is this attempt's problem, never the panel's: it says nothing
        // about whether the hardware could ever be written.
        if (store.active() != true || store.prior() == null) {
            val prior = hardware.capture() ?: return ControlApplyOutcome.FAILED
            if (!store.saveEnabled(prior)) return ControlApplyOutcome.FAILED
        }
        return hardware.enable()
    }

    fun disable(): ControlApplyOutcome {
        val prior = store.prior()
        val restored = if (prior != null) hardware.restore(prior) else hardware.disableConservatively()
        if (!restored.applied) return restored
        return if (store.saveDisabledAndClearPrior()) ControlApplyOutcome.APPLIED else ControlApplyOutcome.FAILED
    }

    /** Assert a durable OFF intent against the hardware. Unlike [disable] this never restores a captured
     *  prior; see [TouchSoundController.reassert] for why a transition's memory must not be replayed. */
    fun assertDisabled(): ControlApplyOutcome {
        val silenced = hardware.disableConservatively()
        if (!silenced.applied) return silenced
        return if (store.saveDisabledAndClearPrior()) ControlApplyOutcome.APPLIED else ControlApplyOutcome.FAILED
    }
}

private class AndroidTouchSoundStateStore(
    private val preferences: SharedPreferences,
) : TouchSoundStateStore {
    override fun active(): Boolean? =
        if (preferences.contains(KEY_ACTIVE)) preferences.getBoolean(KEY_ACTIVE, false) else null

    override fun prior(): TouchSoundState? {
        if (!preferences.getBoolean(KEY_PRIOR_PRESENT, false)) return null
        if (!preferences.contains(KEY_EFFECTS) && !preferences.getBoolean(KEY_EFFECTS_NULL, false)) return null
        return TouchSoundState(
            effectsSetting = if (preferences.getBoolean(KEY_EFFECTS_NULL, false)) null
            else preferences.getInt(KEY_EFFECTS, 1),
        )
    }

    override fun saveEnabled(prior: TouchSoundState): Boolean {
        val editor = preferences.edit()
            .putBoolean(KEY_ACTIVE, true)
            .putBoolean(KEY_PRIOR_PRESENT, true)
        if (prior.effectsSetting == null) {
            editor.remove(KEY_EFFECTS).putBoolean(KEY_EFFECTS_NULL, true)
        } else {
            editor.putInt(KEY_EFFECTS, prior.effectsSetting).remove(KEY_EFFECTS_NULL)
        }
        return editor.commit()
    }

    override fun saveDisabledAndClearPrior(): Boolean = preferences.edit()
        .putBoolean(KEY_ACTIVE, false)
        .remove(KEY_PRIOR_PRESENT)
        .remove(KEY_EFFECTS).remove(KEY_EFFECTS_NULL).remove(KEY_STREAM)
        .commit()

    override fun retireLegacyStreamState(): Boolean =
        !preferences.contains(KEY_STREAM) || preferences.edit().remove(KEY_STREAM).commit()

    companion object {
        private const val KEY_ACTIVE = "touch_sound.active"
        private const val KEY_PRIOR_PRESENT = "touch_sound.prior.present"
        private const val KEY_EFFECTS = "touch_sound.prior.effects"
        private const val KEY_EFFECTS_NULL = "touch_sound.prior.effects_null"
        private const val KEY_STREAM = "touch_sound.prior.system_stream"
    }
}

private class AndroidTouchSoundHardware(
    private val context: Context,
) : TouchSoundHardware {
    private val cr = context.contentResolver

    override fun capture(): TouchSoundState? = runCatching {
        TouchSoundState(
            Settings.System.getString(cr, Settings.System.SOUND_EFFECTS_ENABLED)?.toIntOrNull(),
        )
    }.getOrNull()

    override fun enable(): ControlApplyOutcome = write {
        Settings.System.putInt(cr, Settings.System.SOUND_EFFECTS_ENABLED, 1)
    }

    override fun restore(state: TouchSoundState): ControlApplyOutcome = write {
        if (state.effectsSetting == null) {
            cr.delete(Settings.System.CONTENT_URI, "name=?", arrayOf(Settings.System.SOUND_EFFECTS_ENABLED)) >= 0
        } else {
            Settings.System.putInt(cr, Settings.System.SOUND_EFFECTS_ENABLED, state.effectsSetting)
        }
    }

    override fun disableConservatively(): ControlApplyOutcome = write {
        Settings.System.putInt(cr, Settings.System.SOUND_EFFECTS_ENABLED, 0)
    }

    /** `WRITE_SETTINGS` is this controller's only path, so its refusal is the panel's answer rather than
     *  this attempt's: a `SecurityException` will be raised again on the next boot. */
    private inline fun write(operation: () -> Boolean): ControlApplyOutcome {
        val result = runCatching(operation)
        return when {
            result.getOrDefault(false) -> ControlApplyOutcome.APPLIED
            result.exceptionOrNull() is SecurityException -> ControlApplyOutcome.UNAVAILABLE
            else -> ControlApplyOutcome.FAILED
        }
    }
}
