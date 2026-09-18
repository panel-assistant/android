package io.github.maxlyth.hapaneld.sensors

/**
 * Whether the panel's ambient light sensor is a real source, as opposed to a device-tree part that
 * Android lists but the bus never answers for. A declared-but-dead part reports `SENSOR_STATUS` fine
 * to `getDefaultSensor`, so presence alone advertised an illuminance entity that only ever stored
 * empty states in Home Assistant.
 */
internal enum class LightAvailability {
    /** No `TYPE_LIGHT` sensor is declared at all. */
    ABSENT,

    /** Declared but not registered yet: nothing has been proven either way. */
    IDLE,

    /** Registered; waiting for the current-value event that activation owes an on-change sensor. */
    ACQUIRING,

    /** A reading arrived. */
    AVAILABLE,

    /** Registration was refused, or activation never produced a reading. */
    UNAVAILABLE,
}

/**
 * Tracks one light sensor's activation across one [SensorReporter] run.
 *
 * `ACQUIRING` counts as available so a panel with a healthy sensor never withdraws and re-announces
 * its illuminance entity across a restart. Only a refused registration or an expired acquire window
 * withdraws it, and a late reading restores it — a sensor that merely woke slowly is not hidden for
 * the life of the process.
 *
 * Pure of Android: [schedule] supplies the acquire timer, so the state machine is directly testable.
 */
internal class LightAvailabilityTracker(
    present: Boolean,
    private val acquireTimeoutMs: Long,
    private val schedule: (Long, () -> Unit) -> ActivationRetryCancellation,
    private val onChange: () -> Unit = {},
) {
    @Volatile
    var state: LightAvailability = if (present) LightAvailability.IDLE else LightAvailability.ABSENT
        private set

    private val absent = !present
    private var generation = 0L
    private var scheduled: ActivationRetryCancellation? = null

    /** The single shared answer both the MQTT and native transports read through `Capabilities`. */
    fun available(): Boolean =
        state != LightAvailability.ABSENT && state != LightAvailability.UNAVAILABLE

    /** What the sensor actually did, for the start log — never merely what the device tree declared. */
    fun label(): String = when (state) {
        LightAvailability.ABSENT -> "absent"
        LightAvailability.IDLE -> "idle"
        LightAvailability.ACQUIRING -> "acquiring"
        LightAvailability.AVAILABLE -> "available"
        LightAvailability.UNAVAILABLE -> "failed"
    }

    /** Record the `registerListener` result. A refusal is terminal for this run. */
    @Synchronized
    fun registered(ok: Boolean) {
        if (absent) return
        cancelLocked()
        if (!ok) {
            setState(LightAvailability.UNAVAILABLE)
            return
        }
        setState(LightAvailability.ACQUIRING)
        val expected = generation
        scheduled = schedule(acquireTimeoutMs) { expire(expected) }
    }

    /** A reading arrived: the sensor is real, whatever it did before. */
    @Synchronized
    fun reading() {
        if (absent) return
        cancelLocked()
        setState(LightAvailability.AVAILABLE)
    }

    /** End the run. The next [registered] call re-decides from scratch. */
    @Synchronized
    fun stop() {
        if (absent) return
        cancelLocked()
        setState(LightAvailability.IDLE)
    }

    @Synchronized
    private fun expire(expectedGeneration: Long) {
        if (generation != expectedGeneration) return
        scheduled = null
        if (state != LightAvailability.ACQUIRING) return
        setState(LightAvailability.UNAVAILABLE)
    }

    private fun cancelLocked() {
        generation++
        scheduled?.cancel()
        scheduled = null
    }

    private fun setState(next: LightAvailability) {
        if (state == next) return
        val wasAvailable = available()
        state = next
        // Consumers re-read this tracker; the notification carries no truth, only the fact that the
        // advertised answer moved. Silent internal transitions must not cost a discovery re-announce.
        if (wasAvailable != available()) onChange()
    }
}
