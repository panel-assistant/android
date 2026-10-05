package io.panelassistant.android.sensors

import kotlin.math.abs

/**
 * Projects the local proximity learner onto two independently paced fleet channels.
 *
 * The gate owns no timer or worker. Dense presence must remain changed continuously before it is
 * admitted, while dense numeric telemetry has its own interval. Sparse/on-change edges bypass the
 * interval because silence is part of that source's evidence.
 */
internal class ProximityReportGate(
    private val densePresenceStabilityMs: Long = DENSE_PRESENCE_STABILITY_MS,
    private val denseLevelIntervalMs: Long = DENSE_LEVEL_REPORT_INTERVAL_MS,
    private val levelDeadband: Int = LEVEL_DEADBAND,
) {
    private var initialized = false
    private var lastNear: Boolean? = null
    private var lastLevel: Int? = null
    private var lastLevelAt = UNSET_TIME
    private var pendingPresence = false
    private var pendingNear = false
    private var pendingSince = UNSET_TIME

    init {
        require(densePresenceStabilityMs >= 0L)
        require(denseLevelIntervalMs >= 0L)
        require(levelDeadband >= 0)
    }

    fun project(
        near: Boolean?,
        level: Int?,
        now: Long,
        sparseReporting: Boolean,
    ): Int {
        val available = near != null && level != null
        if (!initialized) {
            initialized = true
            acceptBoth(near, level, now)
            return BOTH
        }

        val wasAvailable = lastNear != null && lastLevel != null
        if (available != wasAvailable) {
            acceptBoth(if (available) near else null, if (available) level else null, now)
            return BOTH
        }
        if (!available) return NONE

        val admittedNear = lastNear!!
        var mask = NONE
        if (near != admittedNear) {
            if (sparseReporting || densePresenceStabilityMs == 0L) {
                lastNear = near
                clearPendingPresence()
                mask = mask or PRESENCE
            } else if (!pendingPresence || pendingNear != near || now < pendingSince) {
                pendingPresence = true
                pendingNear = near
                pendingSince = now
            } else if (now - pendingSince >= densePresenceStabilityMs) {
                lastNear = near
                clearPendingPresence()
                mask = mask or PRESENCE
            }
        } else {
            clearPendingPresence()
        }

        // Do not publish a numeric value from a presence transition that is still unproven.
        if (pendingPresence) return mask

        val admittedLevel = lastLevel!!
        val levelChanged = abs(level - admittedLevel) >= levelDeadband
        val levelDue = sparseReporting || lastLevelAt == UNSET_TIME || now < lastLevelAt ||
            now - lastLevelAt >= denseLevelIntervalMs
        if (levelChanged && levelDue) {
            lastLevel = level
            lastLevelAt = now
            mask = mask or LEVEL
        }
        return mask
    }

    fun reset() {
        initialized = false
        lastNear = null
        lastLevel = null
        lastLevelAt = UNSET_TIME
        clearPendingPresence()
    }

    private fun acceptBoth(near: Boolean?, level: Int?, now: Long) {
        lastNear = near
        lastLevel = level
        lastLevelAt = if (near != null && level != null) now else UNSET_TIME
        clearPendingPresence()
    }

    private fun clearPendingPresence() {
        pendingPresence = false
        pendingNear = false
        pendingSince = UNSET_TIME
    }

    companion object {
        const val NONE = 0
        const val PRESENCE = 1
        const val LEVEL = 2
        const val BOTH = PRESENCE or LEVEL
        const val DENSE_PRESENCE_STABILITY_MS = 250L
        private const val DENSE_LEVEL_REPORT_INTERVAL_MS = 15_000L
        private const val LEVEL_DEADBAND = 4
        private const val UNSET_TIME = Long.MIN_VALUE
    }
}
