package io.github.maxlyth.hapaneld.control

import android.content.Context
import android.content.SharedPreferences
import android.media.AudioManager
import android.provider.Settings
import android.util.Log
import io.github.maxlyth.hapaneld.Config
import io.github.maxlyth.hapaneld.persistence.AppState
import io.github.maxlyth.hapaneld.platform.Daemon
import io.github.maxlyth.hapaneld.platform.RootRunOutcome
import io.github.maxlyth.hapaneld.platform.RootShell
import io.github.maxlyth.hapaneld.util.HelperClient

/**
 * Silences the firmware startup chime while preserving the exact prior ring/notification state.
 *
 * Persistent Settings values are the reliable pre-boot control on the supported panels. Android 14
 * also keeps live stream values, so both are snapshotted before the first silence and restored on
 * disable. A legacy installation without a snapshot is left at its current volume when disabled:
 * inventing a "reasonable" louder volume is less safe than preserving silence.
 */
class BootChimeController internal constructor(
    private val configured: () -> Boolean,
    private val setConfigured: (Boolean) -> Unit,
    private val stateStore: BootChimeStateStore,
    private val hardware: BootChimeHardware,
) {
    constructor(
        context: Context,
        config: Config,
        root: RootShell = Su,
        daemon: Daemon = HelperClient,
    ) : this(
        configured = { config.silenceBootChime },
        setConfigured = config::setSilenceBootChime,
        stateStore = AndroidBootChimeStateStore(
            AppState.preferences(context, "controller-state", STATE_PREFS),
        ),
        hardware = AndroidBootChimeHardware(context.applicationContext, root, daemon),
    )

    fun isEnabled(): Boolean = configured()

    fun set(on: Boolean): Boolean = apply(on).applied

    /**
     * Apply the transition and report what the attempt learned. A caller that journals unapplied intent
     * needs to tell a panel that has no path to this hardware from one that merely failed this time —
     * only the attempt itself knows, and a snapshot or persistence failure here is always retryable.
     */
    @Synchronized
    internal fun apply(on: Boolean): ControlApplyOutcome {
        if (on) {
            if (!ensureSnapshot()) return ControlApplyOutcome.FAILED
            setConfigured(true)
            val silenced = hardware.silence()
            if (silenced.applied) Log.i(TAG, "boot chime silenced")
            else Log.w(TAG, "silence failed through app and root settings paths")
            return silenced
        }

        val prior = stateStore.load()
        if (prior == null) {
            // Old versions did not record the prior volume. Do not resurrect sound with a guessed level.
            setConfigured(false)
            Log.i(TAG, "boot chime control disabled; no legacy volume snapshot to restore")
            return ControlApplyOutcome.APPLIED
        }
        val restored = hardware.restore(prior)
        if (!restored.applied) {
            Log.w(TAG, "exact boot-chime restore failed; retaining snapshot for retry")
            return restored
        }
        if (!stateStore.clear()) {
            Log.w(TAG, "boot-chime state restored but snapshot cleanup failed; retaining enabled state for retry")
            return ControlApplyOutcome.FAILED
        }
        setConfigured(false)
        Log.i(TAG, "boot chime state restored exactly")
        return ControlApplyOutcome.APPLIED
    }

    fun applyPersisted() {
        if (configured()) {
            // Service startup already owns an off-main serialized lifecycle lane. Keeping this work on
            // that lane avoids a detached generation that could silence audio after a successor restores it.
            applyPersistedOffMain()
        }
    }

    @Synchronized
    private fun applyPersistedOffMain() {
        // The setting may have been turned off after the worker was submitted. Serializing with [set]
        // guarantees an in-flight silence happens before its exact restore, never after it.
        if (!configured() || !ensureSnapshot()) return
        if (hardware.silence().applied) Log.i(TAG, "boot chime silenced")
        else Log.w(TAG, "silence failed through app and root settings paths")
    }

    private fun ensureSnapshot(): Boolean {
        if (stateStore.load() != null) return true
        val captured = hardware.capture()
        if (captured == null) {
            Log.w(TAG, "could not capture prior boot-chime state; refusing to silence")
            return false
        }
        if (!stateStore.save(captured)) {
            Log.w(TAG, "could not persist prior boot-chime state; refusing to silence")
            return false
        }
        return true
    }

    companion object {
        private const val TAG = "ha-paneld/bootchime"
        private const val STATE_PREFS = "ha-paneld-controller-state"
    }
}

internal data class BootChimeState(
    val ringSpeakerSetting: Int?,
    val ringSetting: Int?,
    val notificationSetting: Int?,
    val ringStream: Int,
    val notificationStream: Int,
)

internal interface BootChimeStateStore {
    fun load(): BootChimeState?
    fun save(state: BootChimeState): Boolean
    fun clear(): Boolean
}

internal interface BootChimeHardware {
    fun capture(): BootChimeState?
    fun silence(): ControlApplyOutcome
    fun restore(state: BootChimeState): ControlApplyOutcome
}

private class AndroidBootChimeStateStore(
    private val preferences: SharedPreferences,
) : BootChimeStateStore {
    override fun load(): BootChimeState? {
        if (!preferences.getBoolean(KEY_PRESENT, false)) return null
        if (!preferences.hasNullableInt(KEY_RING_SPEAKER) ||
            !preferences.hasNullableInt(KEY_RING) ||
            !preferences.hasNullableInt(KEY_NOTIFICATION) ||
            !preferences.contains(KEY_RING_STREAM) ||
            !preferences.contains(KEY_NOTIFICATION_STREAM)
        ) return null
        return BootChimeState(
            preferences.nullableInt(KEY_RING_SPEAKER),
            preferences.nullableInt(KEY_RING),
            preferences.nullableInt(KEY_NOTIFICATION),
            preferences.getInt(KEY_RING_STREAM, 0),
            preferences.getInt(KEY_NOTIFICATION_STREAM, 0),
        )
    }

    override fun save(state: BootChimeState): Boolean = preferences.edit()
        .putNullableInt(KEY_RING_SPEAKER, state.ringSpeakerSetting)
        .putNullableInt(KEY_RING, state.ringSetting)
        .putNullableInt(KEY_NOTIFICATION, state.notificationSetting)
        .putInt(KEY_RING_STREAM, state.ringStream)
        .putInt(KEY_NOTIFICATION_STREAM, state.notificationStream)
        .putBoolean(KEY_PRESENT, true)
        .commit()

    override fun clear(): Boolean = preferences.edit()
        .remove(KEY_PRESENT)
        .remove(KEY_RING_SPEAKER).remove("$KEY_RING_SPEAKER.null")
        .remove(KEY_RING).remove("$KEY_RING.null")
        .remove(KEY_NOTIFICATION).remove("$KEY_NOTIFICATION.null")
        .remove(KEY_RING_STREAM).remove(KEY_NOTIFICATION_STREAM)
        .commit()

    private fun SharedPreferences.nullableInt(key: String): Int? =
        if (getBoolean("$key.null", false)) null else getInt(key, 0)

    private fun SharedPreferences.hasNullableInt(key: String): Boolean =
        contains(key) || getBoolean("$key.null", false)

    private fun SharedPreferences.Editor.putNullableInt(key: String, value: Int?): SharedPreferences.Editor =
        if (value == null) remove(key).putBoolean("$key.null", true)
        else putInt(key, value).remove("$key.null")

    companion object {
        private const val KEY_PRESENT = "boot_chime.prior.present"
        private const val KEY_RING_SPEAKER = "boot_chime.prior.ring_speaker"
        private const val KEY_RING = "boot_chime.prior.ring"
        private const val KEY_NOTIFICATION = "boot_chime.prior.notification"
        private const val KEY_RING_STREAM = "boot_chime.prior.ring_stream"
        private const val KEY_NOTIFICATION_STREAM = "boot_chime.prior.notification_stream"
    }
}

internal class AndroidBootChimeHardware(
    private val direct: BootChimeDirectAccess,
    private val root: RootShell,
    private val daemon: Daemon,
) : BootChimeHardware {
    constructor(
        context: Context,
        root: RootShell,
        daemon: Daemon = HelperClient,
    ) : this(AndroidBootChimeDirectAccess(context), root, daemon)

    override fun capture(): BootChimeState? = direct.capture()

    override fun silence(): ControlApplyOutcome = applyTransition(
        state = SILENCED_STATE,
        helperCommand = "BOOTCHIME SILENCE",
        rootCommand = silenceShellCommand(0),
    )

    override fun restore(state: BootChimeState): ControlApplyOutcome = applyTransition(
        state = state,
        helperCommand = restoreHelperCommand(state),
        rootCommand = restoreShellCommand(state),
    )

    /**
     * Every path in turn, and unavailability only when all three agree.
     *
     * The helper and root go first. The app path cannot complete a transition on these panels:
     * `volume_ring_speaker` is not a public System setting, so its write is refused whatever
     * `WRITE_SETTINGS` says, and moving the ring stream to or from 0 changes the ringer mode, which
     * needs Do Not Disturb access the app never requests. Trying it first only logged those refusals
     * before a privileged path did the work.
     *
     * Boot chime is not a root-only setting — it has an app path — so root's absence alone proves
     * nothing, which is why an earlier attempt to classify this from the root probe was wrong. Each
     * path here reports what its own attempt found, and a single path that merely failed keeps the
     * whole transition retryable.
     */
    private fun applyTransition(
        state: BootChimeState,
        helperCommand: String,
        rootCommand: String,
    ): ControlApplyOutcome {
        // A null reply is an unreachable daemon socket. Within one boot that cannot be told from a
        // helper which simply has not started yet — hence "every path agrees, on two separate boots"
        // before anything is presented as unappliable.
        val helperReply = runCatching { daemon.send(helperCommand) }
            .onFailure { Log.w(TAG, "helper boot-chime transition failed; trying root: ${it.message}") }
            .getOrNull()
        if (helperReply == "OK") return ControlApplyOutcome.APPLIED

        val rootOutcome = runCatching { root.runClassified(rootCommand) }
            .onFailure { Log.w(TAG, "root boot-chime transition failed; trying app: ${it.message}") }
            .getOrDefault(RootRunOutcome.RAN_FAILED)
        if (rootOutcome == RootRunOutcome.RAN_OK) return ControlApplyOutcome.APPLIED

        val direct = runCatching { direct.apply(state) }
            .onFailure { Log.w(TAG, "app boot-chime transition failed: ${it.message}") }
            .getOrDefault(ControlApplyOutcome.FAILED)
        if (direct.applied) return ControlApplyOutcome.APPLIED

        // Root counts as structurally absent only when no root process was ever created. A root manager
        // that ran this command and refused it is a transient failure, not a missing capability.
        val rootMissing = rootOutcome == RootRunOutcome.NO_LAUNCH
        return if (direct == ControlApplyOutcome.UNAVAILABLE && helperReply == null && rootMissing) {
            ControlApplyOutcome.UNAVAILABLE
        } else {
            ControlApplyOutcome.FAILED
        }
    }

    companion object {
        private const val TAG = "ha-paneld/bootchime"
        private val SILENCED_STATE = BootChimeState(0, 0, 0, 0, 0)
    }
}

internal interface BootChimeDirectAccess {
    fun capture(): BootChimeState?
    fun apply(state: BootChimeState): ControlApplyOutcome
}

private class AndroidBootChimeDirectAccess(context: Context) : BootChimeDirectAccess {
    private val cr = context.contentResolver
    private val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    override fun capture(): BootChimeState? = runCatching {
        BootChimeState(
            Settings.System.getString(cr, RING_SPEAKER_KEY)?.toIntOrNull(),
            Settings.System.getString(cr, RING_STANDARD_KEY)?.toIntOrNull(),
            Settings.System.getString(cr, NOTIFICATION_KEY)?.toIntOrNull(),
            am.getStreamVolume(AudioManager.STREAM_RING),
            am.getStreamVolume(AudioManager.STREAM_NOTIFICATION),
        )
    }.onFailure { Log.w(TAG, "boot-chime state capture failed: ${it.message}") }.getOrNull()

    override fun apply(state: BootChimeState): ControlApplyOutcome = applyBootChimeDirect(
        state = state,
        writeSetting = ::writeSetting,
        writeStream = { stream, level ->
            am.setStreamVolume(stream, level, 0)
            true
        },
        onFailure = { Log.w(TAG, "app boot-chime write failed: ${it.message}") },
    )

    private fun writeSetting(key: String, value: Int?): Boolean =
        if (value == null) cr.delete(Settings.System.CONTENT_URI, "name=?", arrayOf(key)) >= 0
        else Settings.System.putInt(cr, key, value)

    companion object {
        private const val TAG = "ha-paneld/bootchime"
    }
}

/**
 * Every direct mutation is attempted; a partial transition is never reported as successful.
 *
 * The app path is unavailable rather than failed when every write that did not succeed was refused for
 * a permission this app does not hold: without `WRITE_SETTINGS` the `Settings.System` writes throw on
 * this panel and will throw on the next boot too. A write that returned false, or threw anything else,
 * could succeed next time and keeps the whole transition merely failed.
 */
internal fun applyBootChimeDirect(
    state: BootChimeState,
    writeSetting: (String, Int?) -> Boolean,
    writeStream: (Int, Int) -> Boolean,
    onFailure: (Throwable) -> Unit = {},
): ControlApplyOutcome {
    var complete = true
    var everyFailureStructural = true
    fun attempt(operation: () -> Boolean) {
        val result = runCatching(operation)
        result.exceptionOrNull()?.let(onFailure)
        if (result.getOrDefault(false)) return
        complete = false
        if (result.exceptionOrNull() !is SecurityException) everyFailureStructural = false
    }

    attempt { writeSetting(RING_SPEAKER_KEY, state.ringSpeakerSetting) }
    attempt { writeSetting(RING_STANDARD_KEY, state.ringSetting) }
    attempt { writeSetting(NOTIFICATION_KEY, state.notificationSetting) }
    attempt { writeStream(RING_STREAM, state.ringStream) }
    attempt { writeStream(NOTIFICATION_STREAM, state.notificationStream) }
    return when {
        complete -> ControlApplyOutcome.APPLIED
        everyFailureStructural -> ControlApplyOutcome.UNAVAILABLE
        else -> ControlApplyOutcome.FAILED
    }
}

internal fun restoreHelperCommand(state: BootChimeState): String = listOf(
    "BOOTCHIME",
    "RESTORE",
    state.ringSpeakerSetting.helperValue(),
    state.ringSetting.helperValue(),
    state.notificationSetting.helperValue(),
    state.ringStream.toString(),
    state.notificationStream.toString(),
).joinToString(" ")

private fun Int?.helperValue(): String = this?.toString() ?: "-"

internal fun restoreShellCommand(state: BootChimeState): String =
    bootChimeTransitionCommand(
        settingShellCommand(RING_SPEAKER_KEY, state.ringSpeakerSetting),
        settingShellCommand(RING_STANDARD_KEY, state.ringSetting),
        settingShellCommand(NOTIFICATION_KEY, state.notificationSetting),
        "cmd media_session volume --stream $RING_STREAM --set ${state.ringStream}",
        "cmd media_session volume --stream $NOTIFICATION_STREAM --set ${state.notificationStream}",
    )

internal fun silenceShellCommand(level: Int): String =
    bootChimeTransitionCommand(
        "settings put system $RING_SPEAKER_KEY $level",
        "settings put system $RING_STANDARD_KEY $level",
        "settings put system $NOTIFICATION_KEY $level",
        "cmd media_session volume --stream $RING_STREAM --set $level",
        "cmd media_session volume --stream $NOTIFICATION_STREAM --set $level",
    )

private fun settingShellCommand(key: String, value: Int?): String =
    if (value == null) "settings delete system $key" else "settings put system $key $value"

/**
 * RootShell returns the status of the complete expression. Fail-fast chaining prevents a successful
 * final stream write from disguising an earlier failed settings write and causing snapshot cleanup.
 */
private fun bootChimeTransitionCommand(vararg commands: String): String = commands.joinToString(" && ")

private const val RING_SPEAKER_KEY = "volume_ring_speaker"
private const val RING_STANDARD_KEY = "volume_ring"
private const val NOTIFICATION_KEY = "volume_notification"
private const val RING_STREAM = 2
private const val NOTIFICATION_STREAM = 5
