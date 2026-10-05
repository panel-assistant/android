package io.panelassistant.android

import io.panelassistant.android.sensors.ProximityReportGate

/** Stored MQTT projection of the independently admitted proximity channels. */
internal class ProximityPublicationState {
    @Volatile var near: Boolean? = null
        private set
    @Volatile var level: Int? = null
        private set
    @Volatile var available: Boolean? = null
        private set

    /** Serialize an authority decision with the retained MQTT side effects derived from it. */
    internal fun <T> serialized(block: ProximityPublicationState.() -> T): T =
        synchronized(this) { block() }

    /** Returns changed channel bits plus [AVAILABILITY_CHANGED] when both channels were overridden. */
    @Synchronized
    fun admit(nextNear: Boolean?, nextLevel: Int?, reportMask: Int): Int {
        val nextAvailable = nextNear != null && nextLevel != null
        if (available != nextAvailable) {
            near = if (nextAvailable) nextNear else null
            level = if (nextAvailable) nextLevel else null
            available = nextAvailable
            return ProximityReportGate.BOTH or AVAILABILITY_CHANGED
        }
        if (!nextAvailable) return ProximityReportGate.NONE

        var changed = ProximityReportGate.NONE
        if (reportMask and ProximityReportGate.PRESENCE != 0 && near != nextNear) {
            near = nextNear
            changed = changed or ProximityReportGate.PRESENCE
        }
        if (reportMask and ProximityReportGate.LEVEL != 0 && level != nextLevel) {
            level = nextLevel
            changed = changed or ProximityReportGate.LEVEL
        }
        return changed
    }

    @Synchronized
    fun clear(): Boolean {
        val hadState = near != null || level != null || available == true
        near = null
        level = null
        available = false
        return hadState
    }

    companion object {
        const val AVAILABILITY_CHANGED = 4
    }
}
