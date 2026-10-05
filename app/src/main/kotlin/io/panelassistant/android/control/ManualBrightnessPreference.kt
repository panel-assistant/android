package io.panelassistant.android.control

import kotlin.math.abs
import kotlin.math.roundToInt

internal enum class BrightnessPreferenceOrigin {
    PANEL_CONTROLS,
    HOME_ASSISTANT,
    ANDROID_SYSTEM,
}

internal fun priorAppliedBrightness(
    origin: BrightnessPreferenceOrigin,
    commandedLevel: Int?,
    lastAutomaticApplied: Int?,
    automaticTarget: Int,
): Int = if (origin == BrightnessPreferenceOrigin.ANDROID_SYSTEM) {
    lastAutomaticApplied ?: automaticTarget
} else {
    commandedLevel ?: lastAutomaticApplied ?: automaticTarget
}

internal data class ManualBrightnessPreferenceRecord(
    val version: Int = VERSION,
    val requestedLevel: Int,
    val autoTargetAtStart: Int,
    val origin: BrightnessPreferenceOrigin,
    val startedWallMs: Long,
    val startedElapsedMs: Long,
    val bootCount: Int,
    val modelContextKey: String,
    val ambientSourceKey: String,
) {
    companion object { const val VERSION = 1 }
}

internal interface ManualBrightnessPreferenceStore {
    fun load(): ManualBrightnessPreferenceRecord?
    fun save(record: ManualBrightnessPreferenceRecord)
    fun clear()
}

internal data class ManualBrightnessPreferenceSnapshot(
    val active: Boolean,
    val origin: BrightnessPreferenceOrigin? = null,
    val requestedLevel: Int? = null,
    val automaticTarget: Int? = null,
    val finalTarget: Int? = null,
    val autoAuthority: Double = 1.0,
    val remainingMs: Long = 0L,
)

/**
 * A finite manual anchor layered after the complete automatic brightness policy.
 *
 * The explicit level wins exactly at capture time. Later changes in the automatic target initially
 * retain 20% authority, then regain full authority with a smooth four-hour fade. The record contains
 * no learned preference: it is expiring runtime intent and is invalid across source/model changes.
 */
internal class ManualBrightnessAuthority(
    private val store: ManualBrightnessPreferenceStore,
    private val wallClockMs: () -> Long,
    private val elapsedRealtimeMs: () -> Long,
    private val bootCount: () -> Int,
    private val visibleFloor: Int = BrightnessController.MIN_VISIBLE,
    private val visibleCeiling: Int = 255,
) {
    private val processBootCount = bootCount()
    @Volatile private var record: ManualBrightnessPreferenceRecord? = loadValid()

    @Synchronized
    fun capture(
        requestedLevel: Int,
        automaticTarget: Int,
        currentApplied: Int,
        origin: BrightnessPreferenceOrigin,
        modelContextKey: String,
        ambientSourceKey: String,
        persist: Boolean = true,
    ): Boolean {
        val requested = requestedLevel.coerceIn(visibleFloor, visibleCeiling)
        if (abs(requested - currentApplied) < COMMAND_DEADBAND) return false
        val next = ManualBrightnessPreferenceRecord(
            requestedLevel = requested,
            autoTargetAtStart = automaticTarget.coerceIn(visibleFloor, visibleCeiling),
            origin = origin,
            startedWallMs = wallClockMs(),
            startedElapsedMs = elapsedRealtimeMs(),
            bootCount = processBootCount,
            modelContextKey = modelContextKey,
            ambientSourceKey = ambientSourceKey,
        )
        record = next
        if (persist) store.save(next)
        return true
    }

    @Synchronized fun persistCurrent() { record?.let(store::save) }

    /** A null [modelContextKey] means the context is not yet known: source and expiry are still judged. */
    @Synchronized
    fun evaluate(
        automaticTarget: Int,
        modelContextKey: String?,
        ambientSourceKey: String,
    ): ManualBrightnessPreferenceSnapshot {
        val current = record ?: return inactive(automaticTarget)
        if ((modelContextKey != null && current.modelContextKey != modelContextKey) ||
            current.ambientSourceKey != ambientSourceKey
        ) {
            clearLocked()
            return inactive(automaticTarget)
        }
        val age = ageMs(current) ?: run {
            clearLocked()
            return inactive(automaticTarget)
        }
        if (age >= DURATION_MS) {
            clearLocked()
            return inactive(automaticTarget)
        }
        val x = (age.toDouble() / DURATION_MS).coerceIn(0.0, 1.0)
        val smooth = x * x * (3.0 - 2.0 * x)
        val manualInfluence = 1.0 - smooth
        val autoAuthority = 1.0 - (1.0 - INITIAL_AUTO_AUTHORITY) * manualInfluence
        val automatic = automaticTarget.coerceIn(visibleFloor, visibleCeiling)
        val anchored = current.autoTargetAtStart +
            manualInfluence * (current.requestedLevel - current.autoTargetAtStart) +
            autoAuthority * (automatic - current.autoTargetAtStart)
        val final = anchored.roundToInt().coerceIn(visibleFloor, visibleCeiling)
        if (abs(final - automatic) < COMMAND_DEADBAND && autoAuthority > 0.98) {
            clearLocked()
            return inactive(automatic)
        }
        return ManualBrightnessPreferenceSnapshot(
            active = true,
            origin = current.origin,
            requestedLevel = current.requestedLevel,
            automaticTarget = automatic,
            finalTarget = final,
            autoAuthority = autoAuthority,
            remainingMs = DURATION_MS - age,
        )
    }

    @Synchronized fun snapshot(
        automaticTarget: Int,
        modelContextKey: String?,
        ambientSourceKey: String,
    ): ManualBrightnessPreferenceSnapshot = evaluate(automaticTarget, modelContextKey, ambientSourceKey)

    @Synchronized fun clear() = clearLocked()

    private fun inactive(target: Int) = ManualBrightnessPreferenceSnapshot(
        active = false,
        automaticTarget = target.coerceIn(visibleFloor, visibleCeiling),
        finalTarget = target.coerceIn(visibleFloor, visibleCeiling),
    )

    private fun loadValid(): ManualBrightnessPreferenceRecord? {
        val loaded = runCatching(store::load).getOrNull() ?: return null
        if (loaded.version != ManualBrightnessPreferenceRecord.VERSION) return null
        if (loaded.requestedLevel !in visibleFloor..visibleCeiling ||
            loaded.autoTargetAtStart !in visibleFloor..visibleCeiling ||
            loaded.modelContextKey.isBlank() || loaded.ambientSourceKey.isBlank() ||
            ageMs(loaded) == null
        ) {
            store.clear()
            return null
        }
        return loaded
    }

    private fun ageMs(value: ManualBrightnessPreferenceRecord): Long? {
        val currentBoot = processBootCount
        if (currentBoot >= 0 && value.bootCount >= 0 && currentBoot == value.bootCount) {
            val now = elapsedRealtimeMs()
            if (now < value.startedElapsedMs) return null
            return (now - value.startedElapsedMs).takeIf { it <= DURATION_MS }
        }
        val now = wallClockMs()
        if (value.startedWallMs <= 0L || now < value.startedWallMs) return null
        return (now - value.startedWallMs).takeIf { it <= DURATION_MS }
    }

    private fun clearLocked() {
        if (record != null) {
            record = null
            store.clear()
        }
    }

    companion object {
        const val DURATION_MS = 4L * 60L * 60L * 1_000L
        const val INITIAL_AUTO_AUTHORITY = 0.20
        const val COMMAND_DEADBAND = 4
    }
}
