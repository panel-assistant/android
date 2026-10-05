package io.panelassistant.android.sensors

/**
 * Whether the panel's ambient light sensor is a real source, as opposed to a device-tree part that
 * Android lists but the bus never answers for. A declared-but-dead part reports fine to
 * `getDefaultSensor`, so presence alone advertised an illuminance entity that only ever stored
 * empty states in Home Assistant.
 */
internal enum class LightAvailability {
    /** No `TYPE_LIGHT` sensor is declared at all. */
    ABSENT,

    /** Declared but never registered: nothing has been proven either way. */
    IDLE,

    /** Registered; waiting for the current-value event that activation owes an on-change sensor. */
    ACQUIRING,

    /** A reading arrived. */
    AVAILABLE,

    /** Registration was refused, or activation never produced a reading. */
    UNAVAILABLE,
}

/**
 * Tracks one light sensor's activation across [SensorReporter] runs.
 *
 * `ACQUIRING` counts as available so a panel with a healthy sensor never withdraws and re-announces
 * its illuminance entity across a restart. Only a refused registration or an expired acquire window
 * withdraws it, and a later reading restores it — a sensor that merely woke slowly is not hidden for
 * the life of the process.
 *
 * A verdict outlives the run that reached it. Ending a run carries no new information about the
 * hardware, so [stop] cancels the acquire window and leaves the answer alone: resetting it would
 * re-advertise a known-dead part at every service stop and withdraw it again at the next start.
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
    private val lock = Any()
    private var generation = 0L
    private var scheduled: ActivationRetryCancellation? = null

    /** The single shared answer every advertising path reads through `Capabilities`. */
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
    fun registered(ok: Boolean) = mutate {
        if (!ok) {
            state = LightAvailability.UNAVAILABLE
            return@mutate
        }
        state = LightAvailability.ACQUIRING
        val expected = generation
        scheduled = schedule(acquireTimeoutMs) { expire(expected) }
    }

    /** A reading arrived: the sensor is real, whatever it did before. */
    fun reading() = mutate { state = LightAvailability.AVAILABLE }

    /** End the run. The verdict stands until the next run produces a new one. */
    fun stop() = mutate { }

    private fun expire(expectedGeneration: Long) = mutate(guard = { generation == expectedGeneration }) {
        if (state == LightAvailability.ACQUIRING) state = LightAvailability.UNAVAILABLE
    }

    /**
     * Applies a state change, cancelling any pending acquire window first, and reports afterwards
     * only when the advertised answer actually moved. The notification carries no truth, only the
     * fact that consumers must re-read; it runs outside the lock so a consumer cannot re-enter it.
     */
    private fun mutate(guard: () -> Boolean = { true }, block: () -> Unit) {
        if (absent) return
        val moved = synchronized(lock) {
            if (!guard()) return
            val before = available()
            generation++
            scheduled?.cancel()
            scheduled = null
            block()
            before != available()
        }
        if (moved) onChange()
    }
}
